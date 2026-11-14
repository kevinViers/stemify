#!/usr/bin/env python3
"""stemserve: serves Stemify library stems to the Android app over HTTP.

Stdlib only; ffmpeg is optional and enables Opus transcodes and waveforms.

The stems folder is what stemify.library writes:

  <STEMS_DIR>/Artist/Album/01 Song/.stemify.json
  <STEMS_DIR>/Artist/Album/01 Song/01 Song - vocals.flac, ...

Endpoints (all GET unless noted; add ?token=... or X-Stem-Token when TOKEN is set):

  /api/info                                  server facts
  /api/index                                 every finished track (ETag / If-None-Match)
  /api/peaks?key=<key>&n=<buckets>           per-stem RMS envelopes, 0..1000
  POST /api/prepare?key=<key>&fmt=opus&br=160 start transcodes in the background
  POST /api/stem  {"paths": [...]}           "Stem Now": stemd separates these Plex files next
  /file/<key>/<file>[?fmt=opus&br=160]       the stem itself, with Range support

POST /api/stem must be sent as Content-Type: application/json.

<key> is the track's folder relative to STEMS_DIR, e.g. "Artist/Album/01 Song".
"""

import email.utils
import gzip
import hashlib
import hmac
import json
import operator
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.parse
from array import array
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STEMS_DIR = os.path.realpath(os.environ.get("STEMS_DIR", "/stems"))
CACHE_DIR = os.environ.get("CACHE_DIR", "/cache")
PORT = int(os.environ.get("PORT", "8765"))
TOKEN = os.environ.get("TOKEN", "")
INDEX_TTL = float(os.environ.get("INDEX_TTL", "30"))
QUEUE_DIR = os.environ.get("QUEUE_DIR", "/queue")  # stemd's request folder, shared with it
MAX_BODY = 8 << 20
MARKER = ".stemify.json"
VERSION = 1
FFMPEG = shutil.which("ffmpeg")
OPUS_RATES = (64, 96, 128, 160, 192, 256)
PEAK_RATE = 2000  # Hz the envelope is computed at; plenty for a few hundred bars
REQUEST_TIMEOUT = 30  # seconds a client gets to send its request, or to sit idle between two
SEND_TIMEOUT = 600  # seconds a download may stall (a paused player with a full buffer) before it's dropped

_pool = ThreadPoolExecutor(max_workers=max(2, (os.cpu_count() or 2) // 2))
# On-demand transcodes run in the request's own thread; this keeps a burst of them (a new
# album at a new bitrate) from starting more ffmpegs than the box has cores.
_ffmpeg_slots = threading.BoundedSemaphore(max(2, os.cpu_count() or 2))
_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()


def log(*args):
    print(time.strftime("%H:%M:%S"), *args, file=sys.stderr, flush=True)


def _lock_for(path):
    with _locks_guard:
        return _locks.setdefault(path, threading.Lock())


def _unlock(path):
    """Forgets a finished file's lock, so the table doesn't grow with the cache."""
    with _locks_guard:
        _locks.pop(path, None)


# ---- index ------------------------------------------------------------------

class Index:
    def __init__(self):
        self._lock = threading.Lock()
        self._scanning = threading.Lock()
        self._at = 0.0
        self._body = b'{"tracks":[]}'
        self._gzip = gzip.compress(self._body)
        self._etag = '"empty"'
        self._count = 0
        self._by_key: dict[str, dict] = {}
        self._markers: dict[str, tuple] = {}  # folder -> (marker mtime_ns, marker size, entry)

    def get(self):
        """The index as (JSON, the same gzipped, ETag)."""
        if time.monotonic() - self._at > INDEX_TTL:
            self.refresh()
        with self._lock:
            return self._body, self._gzip, self._etag

    def entry(self, key):
        if time.monotonic() - self._at > INDEX_TTL:
            self.refresh()
        with self._lock:
            return self._by_key.get(key)

    @property
    def count(self):
        return self._count

    def refresh(self):
        # One scan at a time; callers that lose the race use the previous result.
        if not self._scanning.acquire(blocking=self._at == 0):
            return
        try:
            tracks, markers = [], {}
            for root, dirs, files in os.walk(STEMS_DIR):
                dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                if MARKER not in files:
                    continue
                # A finished folder never changes without its marker being rewritten, so one
                # stat of the marker stands in for reading it and stat-ing every stem again.
                try:
                    st = os.stat(os.path.join(root, MARKER))
                except OSError:
                    continue
                files = set(files)
                hit = self._markers.get(root)
                if hit and hit[0] == st.st_mtime_ns and hit[1] == st.st_size and all(s["file"] in files for s in hit[2]["stems"]):
                    entry = hit[2]
                else:
                    entry = _read_marker(root, files)
                if entry:
                    tracks.append(entry)
                    markers[root] = (st.st_mtime_ns, st.st_size, entry)
            self._markers = markers
            tracks.sort(key=lambda t: t["key"].casefold())
            body = json.dumps({"version": VERSION, "tracks": tracks}, separators=(",", ":")).encode()
            if body == self._body:  # nothing new: keep the ETag and the gzipped copy
                self._at = time.monotonic()
                return
            packed = gzip.compress(body, 6)
            with self._lock:
                self._body = body
                self._gzip = packed
                self._etag = '"%s"' % hashlib.sha1(body).hexdigest()[:20]
                self._count = len(tracks)
                self._by_key = {t["key"]: t for t in tracks}
                self._at = time.monotonic()
        finally:
            self._scanning.release()


def _read_marker(folder, files):
    try:
        with open(os.path.join(folder, MARKER), encoding="utf-8") as f:
            meta = json.load(f)
    except (OSError, ValueError):
        return None
    key = os.path.relpath(folder, STEMS_DIR).replace(os.sep, "/")
    stems = []
    if not isinstance(meta, dict):
        return None
    for s in meta.get("stems") or []:
        name, fname = (s.get("name"), s.get("file")) if isinstance(s, dict) else (None, None)
        if not isinstance(name, str) or not isinstance(fname, str) or not name or fname not in files:
            continue
        try:
            st = os.stat(os.path.join(folder, fname))
        except OSError:
            continue
        stems.append({"name": name, "file": fname, "size": st.st_size, "mtime": int(st.st_mtime)})
    if not stems:
        return None
    # The stems folder mirrors the music folder, so the source's path relative to the
    # music root is this folder's parent plus the source's file name.
    src_name = os.path.basename(meta.get("source") or "") or os.path.basename(folder)
    parent = os.path.dirname(key)
    return {
        "key": key,
        "source": f"{parent}/{src_name}" if parent else src_name,
        "duration": meta.get("duration"),
        "samplerate": meta.get("samplerate"),
        "model": meta.get("model"),
        "finished": meta.get("finished"),
        "stems": stems,
    }


INDEX = Index()


# ---- transcodes and peaks ---------------------------------------------------

def _resolve(key, fname):
    """Absolute path of a stem file, or None if it escapes STEMS_DIR or is missing."""
    try:
        path = os.path.realpath(os.path.join(STEMS_DIR, key, fname))
    except ValueError:  # a NUL in the name
        return None
    if not path.startswith(STEMS_DIR + os.sep) or not path.lower().endswith(".flac"):
        return None
    return path if os.path.isfile(path) else None


def _cache_name(src, tag, ext):
    st = os.stat(src)
    h = hashlib.sha1(f"{src}|{st.st_size}|{st.st_mtime_ns}|{tag}".encode()).hexdigest()
    return os.path.join(CACHE_DIR, tag.split(":")[0], h[:2], h + ext)


def transcode(src, br):
    """Path of an Ogg Opus copy of src at br kbps, transcoding it first if needed."""
    out = _cache_name(src, f"opus:{br}", ".opus")
    if os.path.exists(out):
        os.utime(out)  # for LRU-ish cleanup by mtime
        return out
    try:
        with _lock_for(out):
            if os.path.exists(out):
                return out
            os.makedirs(os.path.dirname(out), exist_ok=True)
            tmp = f"{out}.{threading.get_ident()}.part"
            with _ffmpeg_slots:
                t0 = time.monotonic()
                try:
                    subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", src, "-map", "0:a:0",
                                    "-c:a", "libopus", "-b:a", f"{br}k", "-vbr", "on", "-application", "audio",
                                    "-map_metadata", "-1", "-f", "ogg", tmp], check=True, timeout=600)
                except BaseException:
                    _remove(tmp)  # a failed or timed-out encode leaves a stub behind otherwise
                    raise
            os.replace(tmp, out)
            log(f"opus {br}k {os.path.basename(src)} in {time.monotonic() - t0:.1f}s")
            return out
    finally:
        _unlock(out)


def _remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _envelope(src, n, duration):
    """RMS of src in n equal buckets across `duration` seconds, as 0..1 of full scale."""
    raw = subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-i", src, "-map", "0:a:0", "-ac", "1",
                          "-ar", str(PEAK_RATE), "-f", "s16le", "-"],
                         check=True, capture_output=True, timeout=300).stdout
    pcm = array("h")
    pcm.frombytes(raw[:len(raw) // 2 * 2])
    if sys.byteorder == "big":
        pcm.byteswap()
    total = max(1, int(round((duration or len(pcm) / PEAK_RATE) * PEAK_RATE)))
    out = []
    for i in range(n):
        a, b = i * total // n, (i + 1) * total // n
        chunk = pcm[a:max(b, a + 1)]
        if not chunk:
            out.append(0)
            continue
        ms = sum(map(operator.mul, chunk, chunk)) / len(chunk)
        out.append(min(1000, int(round((ms ** 0.5) / 32768 * 1000))))
    return out


def peaks(entry, n):
    folder = os.path.join(STEMS_DIR, entry["key"])
    first = _resolve(entry["key"], entry["stems"][0]["file"])
    sig = "|".join(f"{s['file']}:{s['size']}:{s['mtime']}" for s in entry["stems"])
    out = _cache_name(first, f"peaks:{n}:{hashlib.sha1(sig.encode()).hexdigest()}", ".json")
    if os.path.exists(out):
        with open(out, "rb") as f:
            return f.read()
    try:
        with _lock_for(out):
            if os.path.exists(out):
                with open(out, "rb") as f:
                    return f.read()
            t0 = time.monotonic()
            jobs = {s["name"]: _pool.submit(_envelope, os.path.join(folder, s["file"]), n, entry.get("duration"))
                    for s in entry["stems"]}
            body = json.dumps({"key": entry["key"], "n": n, "duration": entry.get("duration"),
                               "stems": {name: job.result() for name, job in jobs.items()}},
                              separators=(",", ":")).encode()
            os.makedirs(os.path.dirname(out), exist_ok=True)
            tmp = f"{out}.{threading.get_ident()}.part"
            with open(tmp, "wb") as f:
                f.write(body)
            os.replace(tmp, out)
            log(f"peaks {entry['key']} in {time.monotonic() - t0:.1f}s")
            return body
    finally:
        _unlock(out)


# ---- HTTP -------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"stemserve/{VERSION}"
    # A connection that never finishes its request (or sits idle) would hold a thread for good.
    timeout = REQUEST_TIMEOUT

    def log_message(self, fmt, *args):
        if os.environ.get("VERBOSE"):
            log(self.address_string(), fmt % args)

    def _authorized(self, query):
        if not TOKEN:
            return True
        given = self.headers.get("X-Stem-Token") or query.get("token", [""])[0]
        return hmac.compare_digest(given.encode(), TOKEN.encode())

    def _send(self, status, body=b"", ctype="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _error(self, status, msg):
        self._send(status, json.dumps({"error": msg}).encode())

    def do_HEAD(self):
        self.do_GET()

    def do_POST(self):
        self.do_GET()
        if not self._body_read and self.headers.get("Content-Length") not in (None, "0"):
            self.close_connection = True  # an unread body would be taken for the next request

    def do_GET(self):
        self._body_read = False
        url = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(url.query)
        path = urllib.parse.unquote(url.path)
        if not self._authorized(query):
            return self._error(HTTPStatus.UNAUTHORIZED, "bad token")
        try:
            if path == "/api/info":
                INDEX.get()
                return self._send(200, json.dumps({
                    "name": "stemserve", "version": VERSION, "tracks": INDEX.count,
                    "ffmpeg": bool(FFMPEG), "opus": list(OPUS_RATES) if FFMPEG else [],
                    "stemNow": os.access(QUEUE_DIR, os.W_OK)}).encode())
            if path == "/api/index":
                body, packed, etag = INDEX.get()
                if self.headers.get("If-None-Match") == etag:
                    return self._send(304, headers={"ETag": etag})
                headers = {"ETag": etag, "Cache-Control": "no-cache", "Vary": "Accept-Encoding"}
                # A big library's index is megabytes of repeated paths; gzipped it's a tenth.
                if "gzip" in (self.headers.get("Accept-Encoding") or "").lower():
                    body, headers["Content-Encoding"] = packed, "gzip"
                return self._send(200, body, headers=headers)
            if path == "/api/peaks":
                return self._peaks(query)
            if path == "/api/prepare":
                return self._prepare(query)
            if path == "/api/stem":
                return self._stem_now()
            if path.startswith("/file/"):
                return self._file(path[len("/file/"):], query)
            return self._error(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass
        except subprocess.CalledProcessError as e:
            self._error(500, f"ffmpeg failed ({e.returncode})")
        except Exception as e:  # keep serving; report what broke
            log("error", self.path, repr(e))
            try:
                self._error(500, str(e) or e.__class__.__name__)
            except OSError:
                pass

    def _entry(self, query):
        key = (query.get("key") or [""])[0]
        entry = INDEX.entry(key)
        if entry is None:
            self._error(404, "unknown key")
        return entry

    def _peaks(self, query):
        if not FFMPEG:
            return self._error(501, "ffmpeg not installed")
        entry = self._entry(query)
        if entry is None:
            return
        try:
            n = max(16, min(4096, int((query.get("n") or ["512"])[0])))
        except ValueError:
            return self._error(400, "n must be a number")
        self._send(200, peaks(entry, n), headers={"Cache-Control": "max-age=86400"})

    def _prepare(self, query):
        if self.command != "POST":
            return self._error(405, "POST to start transcodes")
        entry = self._entry(query)
        if entry is None:
            return
        fmt, br = _format(query)
        if fmt == "opus":
            for s in entry["stems"]:
                src = _resolve(entry["key"], s["file"])
                if src:
                    _pool.submit(transcode, src, br)
        self._send(202, b'{"ok":true}')

    def _stem_now(self):
        """Hands a list of Plex file paths to stemd, which separates them before the rest."""
        if self.command != "POST":
            return self._error(405, "POST a JSON body: {\"paths\": [...]}")
        # A page on another site can make a browser POST here, but not with this type (that
        # needs a preflight this server never answers), so it can't queue work on the GPU.
        if (self.headers.get("Content-Type") or "").split(";")[0].strip().lower() != "application/json":
            return self._error(415, "expected Content-Type: application/json")
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        if n < 0:
            return self._error(400, "bad Content-Length")
        if n > MAX_BODY:
            return self._error(413, "too many paths")
        try:
            body = self.rfile.read(n)
            self._body_read = True
            paths = json.loads(body or b"{}").get("paths")
        except (ValueError, AttributeError):
            paths = None
        if not isinstance(paths, list) or not all(isinstance(p, str) for p in paths):
            return self._error(400, "expected {\"paths\": [...]}")
        if not os.access(QUEUE_DIR, os.W_OK):
            return self._error(503, f"stemd's queue folder isn't mounted at {QUEUE_DIR}")
        if paths:
            # stemd reads requests in name order; the dot keeps a half-written one out of its glob.
            name = f"{time.time_ns()}-{os.getpid()}-{threading.get_ident()}.json"
            tmp = os.path.join(QUEUE_DIR, "." + name)
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"paths": paths, "at": time.time()}, f)
            os.replace(tmp, os.path.join(QUEUE_DIR, name))
            log(f"stem now: {len(paths)} track{'' if len(paths) == 1 else 's'} queued")
        self._send(202, json.dumps({"queued": len(paths)}).encode())

    def _file(self, rel, query):
        key, _, fname = rel.rpartition("/")
        src = _resolve(key, fname)
        if not src:
            return self._error(404, "no such stem")
        fmt, br = _format(query)
        path, ctype = (transcode(src, br), "audio/ogg") if fmt == "opus" else (src, "audio/flac")
        self._serve_file(path, ctype)

    def _serve_file(self, path, ctype):
        st = os.stat(path)
        size = st.st_size
        etag = '"%x-%x"' % (st.st_size, st.st_mtime_ns)
        start, end = 0, size - 1
        status = 200
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes=") and "," not in rng and self.headers.get("If-Range", etag) == etag:
            a, _, b = rng[6:].strip().partition("-")
            try:
                if a:
                    start = int(a)
                    end = min(int(b), size - 1) if b else size - 1
                else:  # suffix range: last N bytes
                    start = max(0, size - int(b))
            except ValueError:
                return self._error(400, "bad range")
            if start >= size or start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", email.utils.formatdate(st.st_mtime, usegmt=True))
        self.send_header("Cache-Control", "max-age=31536000, immutable")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        # The headers have gone out, so nothing that fails from here on can be reported: a
        # second response would land in the middle of the body. Drop the connection instead.
        left = length
        self.connection.settimeout(SEND_TIMEOUT)
        try:
            with open(path, "rb") as f:
                self.wfile.flush()
                # The kernel copies the file straight to the socket (sendfile), so a FLAC
                # stem never passes through Python; it falls back to read/send where it can't.
                left -= self.connection.sendfile(f, start, length) if length else 0
        except OSError as e:
            if not isinstance(e, (BrokenPipeError, ConnectionResetError, TimeoutError)):
                log("error", self.path, repr(e))
            left = left or -1  # how much went out is unknown
        finally:
            try:
                self.connection.settimeout(REQUEST_TIMEOUT)
            except OSError:
                pass
        if left:
            self.close_connection = True


def _format(query):
    fmt = (query.get("fmt") or ["flac"])[0]
    if fmt != "opus" or not FFMPEG:
        return "flac", 0
    try:
        br = int((query.get("br") or ["160"])[0])
    except ValueError:
        br = 160
    return "opus", min(OPUS_RATES, key=lambda r: abs(r - br))


def main():
    if not os.path.isdir(STEMS_DIR):
        sys.exit(f"STEMS_DIR not found: {STEMS_DIR}")
    os.makedirs(CACHE_DIR, exist_ok=True)
    INDEX.refresh()
    log(f"stemserve on :{PORT}, {INDEX.count} tracks in {STEMS_DIR}, ffmpeg={'yes' if FFMPEG else 'no'}, "
        f"token={'on' if TOKEN else 'off'}")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    # As PID 1 in a container, SIGTERM is ignored unless handled; docker stop would hang.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
