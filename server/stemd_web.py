"""stemd's web page: what's separating now, what's queued, what's done, and the controls.

Standard library only. stemd keeps a State up to date as it works; serve() answers the page
(stemd_ui.html) and its JSON calls from a background thread. The actions that change things
(queueing, skipping a song, retrying, re-stemming) live in stemd.py and arrive here as a dict.

  GET  /                       the page
  GET  /api/state              live status, polled every second
  GET  /api/library?rev=N      every track and its status; {"rev": N} alone when unchanged
  GET  /api/log?after=N        log lines after seq N
  GET  /api/track?rel=R        one track in detail (its marker, its failure)
  GET  /api/art?rel=R          the album's cover (a picture in its folder, or the file's own)
  POST /api/control            {"action": "pause"|"resume"|"stop"|"skip"|"rescan"}
  POST /api/tracks             {"action": "next"|"enqueue"|"unqueue"|"exclude"|"retry"|"restem", "rels": [...]}
"""

import collections
import gzip
import json
import os
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stemd_ui.html")
MAX_BODY = 4 << 20


class State:
    """Everything the page shows. stemd writes it from its loop; the page's threads read it."""

    def __init__(self):
        self.lock = threading.RLock()
        self.rev = 0  # bumped whenever a track's status or the queue changes
        self.tracks = {}  # path under MUSIC_DIR -> {"s": status, "d": seconds, "f": finished, "m": model, "e": why}
        self.order = []  # this pass's to-do list, in the order it runs
        self.pos = 0  # how far the pass has got
        self.current = None
        self.phase = "starting"
        self.detail = ""
        self.paused = False
        self.next_scan = None
        self.scan = {}
        self.last = None
        self.session = {"done": 0, "failed": 0, "since": time.time()}
        self.info = {}
        self.skip = threading.Event()  # cancel the song being separated
        self.rescan = threading.Event()
        self.wake = threading.Event()  # pause, resume and queue changes cut an idle wait short
        self.lines = collections.deque(maxlen=2000)
        self.seq = 0

    # ---- written by stemd ----------------------------------------------------------

    def log(self, line):
        with self.lock:
            self.seq += 1
            self.lines.append((self.seq, line))

    def changed(self):
        with self.lock:
            self.rev += 1

    def set_phase(self, phase, detail=""):
        with self.lock:
            self.phase, self.detail = phase, detail

    def set_library(self, tracks, order):
        with self.lock:
            for rel, t in self.tracks.items():  # durations learned along the way survive a rescan
                if "d" in t and rel in tracks and "d" not in tracks[rel]:
                    tracks[rel]["d"] = t["d"]
            if self.current and self.current["rel"] in tracks:
                tracks[self.current["rel"]]["s"] = "now"
            self.tracks, self.order, self.pos = tracks, order, 0
            self.rev += 1

    def set_track(self, rel, **fields):
        with self.lock:
            t = self.tracks.setdefault(rel, {})
            for k, v in fields.items():
                if v is None:
                    t.pop(k, None)
                else:
                    t[k] = v
            self.rev += 1

    def start(self, rel, dur):
        with self.lock:
            self.skip.clear()
            self.current = {"rel": rel, "dur": dur, "started": time.time(), "pct": 0.0, "stage": "Starting"}
            self.phase, self.detail = "separating", ""
            self.set_track(rel, s="now", d=dur)

    def progress(self, stage, pct):
        with self.lock:
            if self.current:
                self.current["stage"], self.current["pct"] = stage, max(0.0, min(1.0, float(pct)))

    def finish(self, outcome, **fields):
        """outcome: "done", "failed", or "todo" (skipped, stopped, out of memory: it comes round again)."""
        with self.lock:
            cur, self.current = self.current, None
            if not cur:
                return
            self.set_track(cur["rel"], s=outcome, **fields)
            if outcome == "done":
                took = time.time() - cur["started"]
                self.session["done"] += 1
                self.last = {"rel": cur["rel"], "took": took,
                             "speed": (fields.get("d") or cur["dur"] or 0) / took if took else None}
            elif outcome == "failed":
                self.session["failed"] += 1

    # ---- read by the page -----------------------------------------------------------

    def queue(self, stem_now):
        """Songs in the order they'll be separated: the current one, Stem Now requests, then the pass."""
        with self.lock:
            out, seen = [], set()
            if self.current:
                out.append(self.current["rel"])
                seen.add(self.current["rel"])
            for rel in stem_now + self.order[self.pos:]:
                t = self.tracks.get(rel)
                if rel not in seen and (t is None or t.get("s") == "todo"):
                    out.append(rel)
                    seen.add(rel)
            return out, set(stem_now)


class Gpu:
    """nvidia-smi's view of the card, asked at most every couple of seconds."""

    def __init__(self):
        self.exe = shutil.which("nvidia-smi")
        self.at, self.value = 0.0, None
        self.lock = threading.Lock()

    def get(self):
        if not self.exe:
            return None
        with self.lock:
            if time.monotonic() - self.at > 2:
                self.at = time.monotonic()
                try:
                    out = subprocess.run(
                        [self.exe, "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
                         "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=3).stdout
                    f = [x.strip() for x in out.splitlines()[0].split(",")]
                    num = lambda s: float(s) if s.replace(".", "", 1).isdigit() else None  # noqa: E731
                    self.value = {"name": f[0], "util": num(f[1]), "memUsed": num(f[2]), "memTotal": num(f[3]),
                                  "temp": num(f[4]), "power": num(f[5])}
                except (OSError, IndexError, subprocess.SubprocessError):
                    self.value = None
            return self.value


def serve(state, actions, port, token=""):
    """Answers the page on port in a background thread. actions: name -> callable (see stemd.py)."""
    gpu = Gpu()
    lib_cache = {"key": None, "body": None}
    lib_lock = threading.Lock()
    queue_sig = {"sig": None}

    def poll_queue():
        # Requests can arrive from stemserve at any time; a new listing is a new revision.
        sig = actions["queue_sig"]()
        if sig != queue_sig["sig"]:
            queue_sig["sig"] = sig
            state.changed()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "stemd"

        def log_message(self, *_):
            pass

        def _send(self, code, body, ctype="application/json", extra=None, cache="no-store"):
            if isinstance(body, (dict, list)):
                body = json.dumps(body, separators=(",", ":")).encode()
            elif isinstance(body, str):
                body = body.encode()
            gz = len(body) > 2048 and "gzip" in (self.headers.get("Accept-Encoding") or "")
            if gz:
                body = gzip.compress(body, 5)
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", cache)
            if gz:
                self.send_header("Content-Encoding", "gzip")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _error(self, code, msg):
            self._send(code, {"error": msg})

        def _authorized(self, query):
            if not token:
                return True
            if self.headers.get("X-Stem-Token") == token or query.get("token", [""])[0] == token:
                return True
            cookies = dict(c.strip().split("=", 1) for c in (self.headers.get("Cookie") or "").split(";") if "=" in c)
            return cookies.get("stemd_token") == token

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                raise ValueError("request too large")
            data = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(data, dict):
                raise ValueError("expected a JSON object")
            return data

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            url = urlsplit(self.path)
            q = parse_qs(url.query)
            if not self._authorized(q):
                return self._error(401, "this stemd needs ?token=")
            try:
                if url.path in ("/", "/index.html"):
                    with open(PAGE, "rb") as f:
                        page = f.read()
                    extra = {"Set-Cookie": f"stemd_token={token}; Path=/; SameSite=Strict; HttpOnly"} if token else None
                    return self._send(200, page, "text/html; charset=utf-8", extra)
                if url.path == "/api/state":
                    return self._send(200, self._state())
                if url.path == "/api/library":
                    return self._library(q.get("rev", [""])[0])
                if url.path == "/api/log":
                    after = int(q.get("after", ["0"])[0] or 0)
                    with state.lock:
                        lines = [list(x) for x in state.lines if x[0] > after]
                        return self._send(200, {"seq": state.seq, "lines": lines})
                if url.path == "/api/track":
                    t = actions["track"](q.get("rel", [""])[0])
                    return self._send(200, t) if t else self._error(404, "no such track")
                if url.path == "/api/art":
                    art = actions["art"](q.get("rel", [""])[0])
                    if not art:
                        return self._send(404, b"", "text/plain", cache="max-age=600")
                    return self._send(200, art[0], art[1], cache="max-age=3600")
                if url.path == "/favicon.ico":
                    return self._send(204, b"", "image/x-icon")
                return self._error(404, "not found")
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            url = urlsplit(self.path)
            if not self._authorized(parse_qs(url.query)):
                return self._error(401, "this stemd needs ?token=")
            try:
                body = self._body()
            except ValueError as e:
                return self._error(400, str(e))
            action = body.get("action")
            if url.path == "/api/control":
                if action not in ("pause", "resume", "stop", "skip", "rescan"):
                    return self._error(400, "unknown action")
                return self._send(200, actions["control"](action))
            if url.path == "/api/tracks":
                rels = body.get("rels")
                if not isinstance(rels, list) or not all(isinstance(r, str) for r in rels):
                    return self._error(400, "expected {\"action\": ..., \"rels\": [...]}")
                fn = actions.get("tracks:" + str(action))
                if fn is None:
                    return self._error(400, "unknown action")
                result = fn(rels)
                state.changed()
                state.wake.set()
                return self._send(200, result)
            return self._error(404, "not found")

        def _state(self):
            poll_queue()
            with state.lock:
                counts = collections.Counter(t.get("s") for t in state.tracks.values())
                return {
                    "now": time.time(), "rev": state.rev, "phase": state.phase, "detail": state.detail,
                    "paused": state.paused, "current": state.current, "nextScan": state.next_scan,
                    "scan": state.scan, "last": state.last, "session": state.session, "info": state.info,
                    "seq": state.seq, "gpu": gpu.get(),
                    "counts": {"done": counts["done"], "todo": counts["todo"] + counts["now"],
                               "failed": counts["failed"], "skip": counts["skip"], "total": len(state.tracks)},
                }

        def _library(self, have):
            poll_queue()
            with state.lock:
                rev = state.rev
            if have == str(rev):
                return self._send(200, {"rev": rev})
            with lib_lock:  # one build per revision however many tabs are open
                if lib_cache["key"] != rev:
                    stem_now = actions["queued"]()
                    with state.lock:
                        rev = state.rev
                        order, now_set = state.queue(stem_now)
                        pos = {rel: i for i, rel in enumerate(order)}
                        rows = []
                        for rel, t in state.tracks.items():
                            row = dict(t, r=rel)
                            if rel in pos:
                                row["q"] = pos[rel]
                                if rel in now_set:
                                    row["n"] = 1
                            rows.append(row)
                        # Stem Now songs a rescan hasn't seen yet still belong in the queue.
                        for rel in order:
                            if rel not in state.tracks:
                                rows.append({"r": rel, "s": "todo", "q": pos[rel], "n": 1})
                    lib_cache["key"] = rev
                    lib_cache["body"] = json.dumps({"rev": rev, "rows": rows}, separators=(",", ":")).encode()
                body = lib_cache["body"]
            return self._send(200, body)

    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, name="stemd-web", daemon=True).start()
    return srv
