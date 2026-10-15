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

<key> is the track's folder relative to STEMS_DIR, e.g. "Artist/Album/01 Song".
"""

import email.utils
import hashlib
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
CHUNK = 1 << 20

_pool = ThreadPoolExecutor(max_workers=max(2, (os.cpu_count() or 2) // 2))
_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()


def log(*args):
    print(time.strftime("%H:%M:%S"), *args, file=sys.stderr, flush=True)


def _lock_for(path):
    with _locks_guard:
        return _locks.setdefault(path, threading.Lock())


# ---- index ------------------------------------------------------------------

class Index:
    def __init__(self):
        self._lock = threading.Lock()
        self._scanning = threading.Lock()
        self._at = 0.0
        self._body = b'{"tracks":[]}'
        self._etag = '"empty"'
        self._count = 0
        self._by_key: dict[str, dict] = {}

    def get(self):
        if time.monotonic() - self._at > INDEX_TTL:
            self.refresh()
        with self._lock:
            return self._body, self._etag

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
            tracks = []
            for root, dirs, files in os.walk(STEMS_DIR):
                dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                if MARKER not in files:
                    continue
                entry = _read_marker(root, set(files))
                if entry:
                    tracks.append(entry)
            tracks.sort(key=lambda t: t["key"].casefold())
            body = json.dumps({"version": VERSION, "tracks": tracks}, separators=(",", ":")).encode()
            with self._lock:
                self._body = body
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
    for s in meta.get("stems", []):
        name, fname = s.get("name"), s.get("file")
        if not name or not fname or fname not in files:
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
    path = os.path.realpath(os.path.join(STEMS_DIR, key, fname))
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
    with _lock_for(out):
        if os.path.exists(out):
            return out
        os.makedirs(os.path.dirname(out), exist_ok=True)
        tmp = out + ".part"
        t0 = time.monotonic()
        subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", src, "-map", "0:a:0",
                        "-c:a", "libopus", "-b:a", f"{br}k", "-vbr", "on", "-application", "audio",
                        "-map_metadata", "-1", "-f", "ogg", tmp], check=True, timeout=600)
        os.replace(tmp, out)
        log(f"opus {br}k {os.path.basename(src)} in {time.monotonic() - t0:.1f}s")
        return out


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
        with open(out + ".part", "wb") as f:
            f.write(body)
        os.replace(out + ".part", out)
        log(f"peaks {entry['key']} in {time.monotonic() - t0:.1f}s")
        return body


# ---- HTTP -------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = f"stemserve/{VERSION}"

    def log_message(self, fmt, *args):
        if os.environ.get("VERBOSE"):
            log(self.address_string(), fmt % args)

    def _authorized(self, query):
        if not TOKEN:
            return True
        return TOKEN in (self.headers.get("X-Stem-Token"), query.get("token", [None])[0])

    def _send(self, status, body=b"", ctype="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
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

    def do_GET(self):
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
                body, etag = INDEX.get()
                if self.headers.get("If-None-Match") == etag:
                    return self._send(304, headers={"ETag": etag})
                return self._send(200, body, headers={"ETag": etag, "Cache-Control": "no-cache"})
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
        n = max(16, min(4096, int((query.get("n") or ["512"])[0])))
        self._send(200, peaks(entry, n), headers={"Cache-Control": "max-age=86400"})

    def _prepare(self, query):
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
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            self.close_connection = True
            return self._error(413, "too many paths")
        try:
            paths = json.loads(self.rfile.read(n) or b"{}").get("paths")
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
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", email.utils.formatdate(st.st_mtime, usegmt=True))
        self.send_header("Cache-Control", "max-age=31536000, immutable")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                buf = f.read(min(CHUNK, left))
                if not buf:
                    break
                self.wfile.write(buf)
                left -= len(buf)


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
