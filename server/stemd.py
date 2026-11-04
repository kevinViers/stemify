#!/usr/bin/env python3
"""stemd: separates every track in the music library that doesn't have stems yet.

Runs on the server next to stemserve. Walks MUSIC_DIR, and for each track whose folder
under STEMS_DIR has no marker it runs the separation on the GPU and writes the stems with
the same layout and marker as the desktop app (stemify/library_core.py), so stemserve
and the phone pick them up as they land. Once caught up it looks again every RESCAN_MIN.

"Stem Now" in the app goes through stemserve, which drops a request (a JSON list of Plex
file paths) into QUEUE_DIR. Those tracks are separated before the rest: between tracks
during a pass, and within a few seconds while idle.

A web page on UI_PORT (stemd_web.py, stemd_ui.html) shows what's separating, what's queued and
what's done, and can pause, skip, queue, retry and re-stem songs.

Settings (environment):

  MUSIC_DIR   /music           the music library (read-only is fine)
  STEMS_DIR   /stems           where stems go
  STATE_DIR   /state           failed-track list
  QUEUE_DIR   STATE_DIR/queue  "Stem Now" requests from stemserve
  MODELS_DIR  /models          model weights, downloaded on first use
  MODEL       roformer_bs_sw   any id from stemify/catalog.py
  DEVICE      cuda             cuda, cuda:N or cpu
  RESCAN_MIN  30               minutes between scans once caught up
  FP16        1                half precision on the GPU
  OVERLAP / SEGMENT / SHIFTS   as in the desktop app's settings
  UI_PORT     8766             the web page; 0 turns it off
  UI_TOKEN    (empty)          if set, the page needs ?token= once
"""

import collections
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from stemify.catalog import MODELS_BY_ID  # noqa: E402
from stemify.library_core import (MARKER, MAX_SECONDS, SOURCE_RANK, duration, is_done, is_share_error,  # noqa: E402
                                     iter_tracks, signature, write_track)
import stemd_web  # noqa: E402  (beside this file)

MUSIC_DIR = os.environ.get("MUSIC_DIR", "/music")
STEMS_DIR = os.environ.get("STEMS_DIR", "/stems")
STATE_DIR = os.environ.get("STATE_DIR", "/state")
MODELS_DIR = os.environ.get("MODELS_DIR", "/models")
MODEL = os.environ.get("MODEL", "roformer_bs_sw")
DEVICE = os.environ.get("DEVICE", "cuda")
RESCAN_S = float(os.environ.get("RESCAN_MIN", "30")) * 60
FP16 = os.environ.get("FP16", "1") not in ("0", "false", "no", "")
OVERLAP = float(os.environ.get("OVERLAP", "0.25"))
SEGMENT = float(os.environ.get("SEGMENT", "0"))
SHIFTS = int(os.environ.get("SHIFTS", "0"))
QUEUE_DIR = os.environ.get("QUEUE_DIR", os.path.join(STATE_DIR, "queue"))
UI_PORT = int(os.environ.get("UI_PORT", "8766"))
UI_TOKEN = os.environ.get("UI_TOKEN", "")
FAILED_PATH = os.path.join(STATE_DIR, "failed.json")
PAUSED_PATH = os.path.join(STATE_DIR, "paused")  # exists while paused from the page, so a restart stays paused
QUEUE_POLL_S = 5  # how often an idle stemd looks for Stem Now requests
SHARE_RETRY_S = 5 * 60  # stems folder unwritable: try again this soon rather than at RESCAN_S
MAX_CRASHES = 2  # GPU crashes on one track before it's given up on

stop = threading.Event()
STATE = stemd_web.State()
EXCLUDED = "Don't Stem (set on the page)"


def log(*args):
    line = " ".join(str(a) for a in args)
    print(time.strftime("%Y-%m-%d %H:%M:%S"), line, flush=True)
    STATE.log(time.strftime("%H:%M:%S ") + line)


class Failed:
    """Tracks that didn't separate, keyed by path, retried once the source file changes."""

    def __init__(self):
        self.lock = threading.RLock()  # the page edits it too
        try:
            with open(FAILED_PATH) as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def skip(self, path, sig):
        e = self.data.get(path)
        return bool(e) and e["sig"] == sig and e.get("tries", MAX_CRASHES) >= MAX_CRASHES

    def status(self, path):
        """("skip" or "failed", message) for a track that's being left alone."""
        e = self.data.get(path) or {}
        return ("skip" if e.get("user") else "failed"), e.get("msg", "")

    def add(self, path, sig, msg, crash=False, user=False):
        with self.lock:
            tries = MAX_CRASHES
            if crash:
                e = self.data.get(path)
                tries = (e.get("tries", 0) if e and e["sig"] == sig else 0) + 1
            self.data[path] = {"sig": sig, "msg": msg, "tries": tries, "at": time.time()}
            if user:
                self.data[path]["user"] = True
            self._save()

    def remove(self, paths):
        with self.lock:
            n = sum(self.data.pop(p, None) is not None for p in paths)
            if n:
                self._save()
            return n

    def _save(self):
        os.makedirs(STATE_DIR, exist_ok=True)
        tmp = FAILED_PATH + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, indent=1)
        os.replace(tmp, FAILED_PATH)


class _Progress:
    """Stands in for the worker's socket: progress goes to the page, not the log."""

    def send(self, type_, **payload):
        if type_ == "progress":
            STATE.progress(payload.get("stage", ""), payload.get("pct", 0))


class _Cancel:
    """The job's cancel flag: stemd stopping, or the page skipping this track."""

    def is_set(self):
        return stop.is_set() or STATE.skip.is_set()


class Separator:
    def __init__(self):
        import torch
        from stemify.worker.backends import DemucsBackend, RoformerBackend

        self.torch = torch
        self.model = MODELS_BY_ID[MODEL]
        self.device = torch.device(DEVICE if DEVICE == "cpu" or torch.cuda.is_available() else "cpu")
        if DEVICE != "cpu" and self.device.type == "cpu":
            log("WARNING: no CUDA device visible; separating on the CPU (slow). Is the container "
                "running with the nvidia runtime?")
        self.backend = (DemucsBackend() if self.model["family"] == "demucs" else RoformerBackend(MODELS_DIR))
        name = torch.cuda.get_device_name(self.device) if self.device.type == "cuda" else "CPU"
        log(f"model {self.model['label']} on {name}, torch {torch.__version__}")

    def run(self, path, tmp):
        # Imported late: worker/__main__ sets ROCm env defaults, harmless here, and owns Job's
        # parameter rules (overlap, fp16, ...), which must match the desktop's.
        from stemify.worker.__main__ import Job
        job = Job({"id": "stemd", "file": path, "model": MODEL, "out_dir": tmp, "segment": SEGMENT,
                   "shifts": SHIFTS, "overlap": OVERLAP, "fp16": FP16}, _Progress())
        if "file" in self.model and not os.path.exists(os.path.join(MODELS_DIR, self.model["file"])):
            log("    downloading the model (first run only)")
        job.cancel_event = _Cancel()
        stems, sr = self.backend.run(job, self.device)
        # Backends return (channels, samples); the writer wants (samples, channels).
        return {name: audio.T for name, audio in stems.items()}, sr

    def release(self):
        self.backend.release()

    def is_fatal(self, e):
        """CUDA errors other than OOM poison the context; only a fresh process recovers."""
        if isinstance(e, self.torch.OutOfMemoryError):
            return False
        return isinstance(e, getattr(self.torch, "AcceleratorError", ())) or "CUDA error" in str(e)


def separate(sep, failed, path, dest, sig, label):
    """Separate one track into dest. Returns True if it was written.

    Raises on a stop request, an unwritable stems share, or a GPU error that needs a restart.
    """
    rel = os.path.relpath(path, MUSIC_DIR)
    dur = duration(path)
    if dur is not None and dur > MAX_SECONDS:
        log(f"{label} skip {rel}: {dur / 60:.0f} min long")
        failed.add(path, sig, f"longer than {MAX_SECONDS // 60} minutes")
        STATE.set_track(rel, s="failed", d=dur, e=f"longer than {MAX_SECONDS // 60} minutes")
        return False
    log(f"{label} {rel}" + (f" ({int(dur) // 60}:{int(dur) % 60:02d})" if dur else ""))
    STATE.start(rel, dur)
    outcome, fields = "todo", {}
    t1 = time.monotonic()
    tmp = tempfile.mkdtemp(prefix="stemd-")
    try:
        stems, sr = sep.run(path, tmp)
        STATE.progress("Writing stems", 0.97)
        meta = {"source": path, "size": sig[0], "mtime_ns": sig[1], "model": MODEL, "samplerate": sr,
                "duration": len(next(iter(stems.values()))) / sr}
        write_track(dest, stems, meta)
        outcome, fields = "done", {"d": meta["duration"], "f": time.time(), "m": MODEL, "e": None}
    except BaseException as e:
        if stop.is_set():
            raise  # Cancelled; the track is redone next start
        if STATE.skip.is_set():
            log("    skipped from the page")  # still to do; it comes round again next pass
            return False
        if is_share_error(e):
            log(f"    can't write to {STEMS_DIR}: {e}; pausing")
            raise
        if not isinstance(e, Exception):
            raise
        msg = str(e).strip().splitlines()[0] if str(e).strip() else e.__class__.__name__
        if isinstance(e, sep.torch.OutOfMemoryError):
            # Something else (Plex, a game server) may be holding VRAM; try again next pass.
            log("    out of GPU memory; will retry next pass")
            sep.release()
            return False
        if sep.is_fatal(e):
            failed.add(path, sig, msg, crash=True)
            log(f"    GPU error: {msg}; restarting")
            sys.exit(75)
        failed.add(path, sig, msg)
        log(f"    failed: {msg}")
        outcome, fields = "failed", {"e": msg}
        return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        STATE.finish(outcome, **fields)
    took = time.monotonic() - t1
    log(f"    done in {took:.0f}s" + (f" ({meta['duration'] / took:.1f}x realtime)" if took else ""))
    return True


# The project was renamed to Stemify; songs separated before that carry the old marker name.
# migrate_markers() renames them once so they aren't separated again. Once stemd has run it
# against your library, this constant, the function and its call in main() can be deleted.
LEGACY_MARKER = ".stemroller.json"


def migrate_markers():
    """Rename every legacy done-marker under STEMS_DIR to MARKER (a few seconds per 10k songs)."""
    n = 0
    for root, dirs, files in os.walk(STEMS_DIR):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        if LEGACY_MARKER not in files:
            continue
        old, new = os.path.join(root, LEGACY_MARKER), os.path.join(root, MARKER)
        try:
            if MARKER in files:
                os.remove(old)
            else:
                os.replace(old, new)
            n += 1
        except OSError as e:
            log(f"can't rename {old}: {e}")
    if n:
        log(f"renamed {n:,} {LEGACY_MARKER} markers to {MARKER}")


def resolve(plex_path):
    """The music file a Plex path names, or None. Plex sees the library under its own mount
    point, so this takes the longest tail of the path (folder and file at least) that exists
    under MUSIC_DIR, and prefers the best format when a folder holds the song in several."""
    parts = [p for p in plex_path.replace("\\", "/").split("/") if p not in ("", ".", "..")]
    root = os.path.realpath(MUSIC_DIR)
    for i in range(len(parts) - 1):
        path = os.path.join(root, *parts[i:])
        if os.path.isfile(path) and os.path.realpath(path).startswith(root + os.sep):
            break
    else:
        return None
    folder, name = os.path.split(path)
    base = os.path.splitext(name)[0].casefold()
    try:
        same = [f for f in os.listdir(folder) if os.path.splitext(f)[0].casefold() == base
                and os.path.splitext(f)[1].lower() in SOURCE_RANK]
    except OSError:
        same = []
    if same:
        path = os.path.join(folder, min(same, key=lambda f: (SOURCE_RANK[os.path.splitext(f)[1].lower()], f != name)))
    return path if os.path.splitext(path)[1].lower() in SOURCE_RANK else None


def dest_for(src):
    return os.path.join(STEMS_DIR, os.path.splitext(os.path.relpath(src, MUSIC_DIR))[0])


def local(rel):
    """The music file at rel under MUSIC_DIR (as the page names songs), or None."""
    root = os.path.realpath(MUSIC_DIR)
    path = os.path.realpath(os.path.join(root, rel))
    return path if path.startswith(root + os.sep) and os.path.isfile(path) else None


_requests = {}  # request file name -> (mtime_ns, its music files); resolving Plex paths isn't free
_requests_lock = threading.Lock()


def request_files(req):
    """The music files a Stem Now request names, in order. "paths" are Plex's (from stemserve),
    "files" are paths under MUSIC_DIR (from the page)."""
    name = os.path.basename(req)
    try:
        mtime = os.stat(req).st_mtime_ns
    except OSError:
        return []
    with _requests_lock:
        hit = _requests.get(name)
        if hit and hit[0] == mtime:
            return hit[1]
    try:
        with open(req) as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        log(f"stem now: unreadable request {name}: {e}")
        data = {}
    if not isinstance(data, dict):
        data = {}
    out = []
    for p in data.get("paths") if isinstance(data.get("paths"), list) else []:
        src = resolve(p) if isinstance(p, str) else None
        if src is None:
            log(f"stem now: not in {MUSIC_DIR}: {p}")
        else:
            out.append(src)
    for r in data.get("files") if isinstance(data.get("files"), list) else []:
        src = local(r) if isinstance(r, str) else None
        if src is None:
            log(f"stem now: not in {MUSIC_DIR}: {r}")
        else:
            out.append(src)
    with _requests_lock:
        if hit is None and out:
            log(f"stem now: {len(out)} track{'' if len(out) == 1 else 's'} ahead of the rest")
        _requests[name] = (mtime, out)
    return out


def requests():
    try:
        return sorted(glob.glob(os.path.join(QUEUE_DIR, "*.json")))
    except OSError:
        return []


_tried = set()  # (request, track) pairs already attempted, so one that keeps failing doesn't loop


def next_requested(failed):
    """The oldest Stem Now track still to do, as (request, src, dest, sig), or None.
    Requests with nothing left in them are deleted."""
    for req in requests():
        name = os.path.basename(req)
        for src in request_files(req):
            if (name, src) in _tried:
                continue
            dest = dest_for(src)
            if is_done(dest):
                continue
            try:
                sig = signature(src)
            except OSError:
                continue
            if failed.skip(src, sig):
                log(f"stem now: skip {os.path.relpath(src, MUSIC_DIR)}: {failed.data[src]['msg']}")
                _tried.add((name, src))
                continue
            return name, src, dest, sig
        try:
            os.remove(req)
        except FileNotFoundError:
            pass
        with _requests_lock:
            _requests.pop(name, None)
    return None


def queued():
    """Stem Now songs still to do, as paths under MUSIC_DIR, in the order they'll run."""
    out, seen = [], set()
    for req in requests():
        name = os.path.basename(req)
        for src in request_files(req):
            if (name, src) not in _tried and src not in seen:
                seen.add(src)
                out.append(os.path.relpath(src, MUSIC_DIR))
    return out


def serve_queue(sep, failed):
    """Separate every Stem Now request, oldest first. Returns whether any track was attempted.

    The queue is read again after every track, so "Stem Next" from the page goes straight to
    the front. A request file is deleted once all its tracks are done, so one cut short by a
    restart picks up where it left off (finished tracks are skipped)."""
    used = False
    while wait_unpaused():
        nxt = next_requested(failed)
        if nxt is None:
            break
        name, src, dest, sig = nxt
        _tried.add((name, src))
        used = True
        separate(sep, failed, src, dest, sig, "[now]")
    return used


def wait_unpaused():
    """Blocks while paused from the page. False once stemd is stopping."""
    while STATE.paused and not stop.is_set():
        if STATE.phase != "paused":
            STATE.set_phase("paused")
        STATE.wake.wait(1)
        STATE.wake.clear()
    return not stop.is_set()


_meta = {}  # stems folder -> (marker mtime, what the page shows); markers are read once


def done_info(dest):
    """What the page shows for a finished song, or None if it isn't finished."""
    try:
        st = os.stat(os.path.join(dest, MARKER))
    except OSError:
        return None
    hit = _meta.get(dest)
    if hit and hit[0] == st.st_mtime_ns:
        return dict(hit[1])
    try:
        with open(os.path.join(dest, MARKER)) as f:
            m = json.load(f)
    except (OSError, ValueError):
        m = {}
    info = {"s": "done", "f": m.get("finished") or st.st_mtime}
    if isinstance(m.get("duration"), (int, float)):
        info["d"] = m["duration"]
    if m.get("model"):
        info["m"] = m["model"]
    _meta[dest] = (st.st_mtime_ns, info)
    return dict(info)


def run_pass(sep, failed):
    """Separate everything that needs it. Returns (tracks done, whether the GPU was used)."""
    STATE.rescan.clear()
    t0 = time.monotonic()
    todo, total, tracks = [], 0, {}
    STATE.set_phase("scanning")
    for path, dest in iter_tracks(MUSIC_DIR, STEMS_DIR):
        if stop.is_set():
            return 0, False
        total += 1
        if total % 250 == 0:
            STATE.set_phase("scanning", f"{total:,} tracks")
        rel = os.path.relpath(path, MUSIC_DIR)
        info = done_info(dest)
        if info is not None:
            tracks[rel] = info
            continue
        try:
            sig = signature(path)
        except OSError:
            continue
        if failed.skip(path, sig):
            status, msg = failed.status(path)
            tracks[rel] = {"s": status, "e": msg}
        else:
            tracks[rel] = {"s": "todo"}
            todo.append((path, dest, sig))
    took = time.monotonic() - t0
    log(f"scan: {total:,} tracks, {total - len(todo):,} done or skipped, {len(todo):,} to do ({took:.0f}s)")
    STATE.set_library(tracks, [os.path.relpath(p, MUSIC_DIR) for p, _, _ in todo])
    STATE.scan = {"at": time.time(), "took": took, "total": total}
    if not total:
        log(f"WARNING: no music found in {MUSIC_DIR}; is the share mounted and readable?")

    done, used = 0, False
    for i, (path, dest, sig) in enumerate(todo, 1):
        used = serve_queue(sep, failed) or used
        if not wait_unpaused() or STATE.rescan.is_set():
            break
        with STATE.lock:
            STATE.pos = i - 1
        if is_done(dest) or failed.skip(path, sig):  # the desktop, a Stem Now or the page got there first
            continue
        used = True
        if separate(sep, failed, path, dest, sig, f"[{i}/{len(todo)}]"):
            done += 1
    with STATE.lock:
        STATE.pos = len(todo)
    return done, used


def idle(sep, failed, seconds):
    """Wait for the next scan, separating Stem Now requests as they arrive."""
    end = time.monotonic() + seconds
    STATE.next_scan = time.time() + seconds
    while not stop.is_set() and time.monotonic() < end and not STATE.rescan.is_set():
        if not STATE.paused:
            STATE.set_phase("idle")
            if serve_queue(sep, failed):
                sep.release()
                continue
        STATE.wake.wait(min(QUEUE_POLL_S, max(0.0, end - time.monotonic())))
        STATE.wake.clear()
    STATE.next_scan = None


# ---- the page's actions ----------------------------------------------------------------

def control(action):
    if action in ("pause", "stop"):
        STATE.paused = True
        try:
            open(PAUSED_PATH, "w").close()
        except OSError:
            pass
    if action in ("stop", "skip") and STATE.current:
        STATE.skip.set()
    if action == "resume":
        STATE.paused = False
        try:
            os.remove(PAUSED_PATH)
        except OSError:
            pass
    if action == "rescan":
        STATE.rescan.set()
    if action in ("pause", "stop") and STATE.current is None:
        STATE.set_phase("paused")
    STATE.wake.set()
    log(f"page: {action}")
    return {"ok": True, "paused": STATE.paused}


def write_request(rels, front=False):
    """Queues songs (paths under MUSIC_DIR) for Stem Now; front puts them before every request."""
    files = [r for r in dict.fromkeys(rels) if local(r)]
    if not files:
        return 0
    os.makedirs(QUEUE_DIR, exist_ok=True)
    # Requests run in name order; stemserve's start with the time in ns (1...), so 0... comes
    # first, and a later "next" sorts before an earlier one.
    t = time.time_ns()
    name = f"0{10 ** 19 - t:019d}-page.json" if front else f"{t}-page.json"
    tmp = os.path.join(QUEUE_DIR, "." + name)
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"files": files, "at": time.time()}, f)
    os.replace(tmp, os.path.join(QUEUE_DIR, name))
    return len(files)


def unqueue(rels):
    """Takes songs out of every Stem Now request; requests left empty are deleted."""
    gone = set(rels)
    n = 0
    for req in requests():
        srcs = request_files(req)
        if not any(os.path.relpath(s, MUSIC_DIR) in gone for s in srcs):
            continue
        try:
            with open(req) as f:
                data = json.load(f)
        except (OSError, ValueError):
            continue
        keep_paths = [p for p in data.get("paths") or []
                      if not (isinstance(p, str) and (src := resolve(p)) and os.path.relpath(src, MUSIC_DIR) in gone)]
        keep_files = [r for r in data.get("files") or [] if r not in gone]
        n += len(data.get("paths") or []) + len(data.get("files") or []) - len(keep_paths) - len(keep_files)
        if not keep_paths and not keep_files:
            try:
                os.remove(req)
            except FileNotFoundError:
                pass
            continue
        data.update(paths=keep_paths, files=keep_files)
        tmp = os.path.join(QUEUE_DIR, "." + os.path.basename(req))
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp, req)
    if STATE.current and STATE.current["rel"] in gone:
        STATE.skip.set()
    return n


def track_actions(failed):
    def next_(rels):
        n = write_request(rels, front=True)
        log(f"page: stem next, {n} track{'' if n == 1 else 's'}")
        return {"ok": True, "n": n}

    def enqueue(rels):
        n = write_request(rels)
        log(f"page: queued {n} track{'' if n == 1 else 's'}")
        return {"ok": True, "n": n}

    def unqueue_(rels):
        n = unqueue(rels)
        log(f"page: removed {n} from the Stem Now queue")
        return {"ok": True, "n": n}

    def exclude(rels):
        n = 0
        for rel in rels:
            src = local(rel)
            if not src or done_info(dest_for(src)):
                continue
            failed.add(src, signature(src), EXCLUDED, user=True)
            STATE.set_track(rel, s="skip", e=EXCLUDED)
            n += 1
        unqueue(rels)
        if STATE.current and STATE.current["rel"] in rels:
            STATE.skip.set()
        log(f"page: won't stem {n} track{'' if n == 1 else 's'}")
        return {"ok": True, "n": n}

    def retry(rels):
        srcs = [s for s in (local(r) for r in rels) if s]
        n = failed.remove(srcs)
        for src in srcs:
            if not done_info(dest_for(src)):
                STATE.set_track(os.path.relpath(src, MUSIC_DIR), s="todo", e=None)
        write_request([os.path.relpath(s, MUSIC_DIR) for s in srcs], front=True)
        log(f"page: retrying {n} track{'' if n == 1 else 's'}")
        return {"ok": True, "n": n}

    def restem(rels):
        """Separate again: the marker goes now (the old stems stay until the new ones replace them)."""
        n = 0
        for rel in rels:
            src = local(rel)
            if not src:
                continue
            try:
                os.remove(os.path.join(dest_for(src), MARKER))
            except FileNotFoundError:
                pass
            except OSError as e:
                log(f"page: can't re-stem {rel}: {e}")
                continue
            failed.remove([src])
            STATE.set_track(rel, s="todo", f=None, m=None, e=None)
            n += 1
        write_request(rels, front=True)
        log(f"page: re-stemming {n} track{'' if n == 1 else 's'}")
        return {"ok": True, "n": n}

    def track(rel):
        src = local(rel)
        if not src:
            return None
        dest = dest_for(src)
        out = {"rel": rel, "source": src, "stems": dest, "status": STATE.tracks.get(rel, {})}
        try:
            with open(os.path.join(dest, MARKER)) as f:
                out["marker"] = json.load(f)
            for s in out["marker"].get("stems", []):
                try:
                    s["bytes"] = os.path.getsize(os.path.join(dest, s["file"]))
                except OSError:
                    pass
        except (OSError, ValueError):
            pass
        try:
            out["bytes"] = os.path.getsize(src)
        except OSError:
            pass
        if src in failed.data:
            out["failed"] = failed.data[src]
        return out

    art_cache = collections.OrderedDict()  # album folder -> (bytes, type) or None
    art_lock = threading.Lock()

    def art(rel):
        src = local(rel)
        if not src:
            return None
        folder = os.path.dirname(src)
        with art_lock:
            if folder in art_cache:
                art_cache.move_to_end(folder)
                return art_cache[folder]
        found = None
        # A cover beside the song (or beside its CD1/CD2 folder), else the one inside the file.
        for d in (folder, os.path.dirname(folder)):
            try:
                pics = [n for n in os.listdir(d) if n.lower().endswith((".jpg", ".jpeg", ".png")) and not n.startswith(".")]
            except OSError:
                pics = []
            if d != folder:
                pics = [n for n in pics if os.path.splitext(n)[0].lower() in ("cover", "folder", "front")]
            if pics:
                rank = ("cover", "folder", "front", "album")
                pics.sort(key=lambda n: (next((i for i, w in enumerate(rank) if w in n.lower()), 9), n.lower()))
                try:
                    if os.path.getsize(os.path.join(d, pics[0])) < 12 << 20:
                        with open(os.path.join(d, pics[0]), "rb") as f:
                            found = f.read()
                except OSError:
                    pass
                break
        if found is None:
            try:
                found = subprocess.run(["ffmpeg", "-v", "error", "-i", src, "-an", "-frames:v", "1", "-c:v", "copy",
                                        "-f", "image2pipe", "-"], capture_output=True, timeout=10).stdout or None
            except (OSError, subprocess.SubprocessError):
                found = None
        result = (found, "image/png" if found[:4] == b"\x89PNG" else "image/jpeg") if found else None
        with art_lock:
            art_cache[folder] = result
            while len(art_cache) > 64:
                art_cache.popitem(last=False)
        return result

    def queue_sig():
        def mtime(r):
            try:
                return os.stat(r).st_mtime_ns
            except OSError:
                return 0
        return tuple((os.path.basename(r), mtime(r)) for r in requests())

    return {"control": control, "queued": queued, "queue_sig": queue_sig, "track": track, "art": art,
            "tracks:next": next_, "tracks:enqueue": enqueue, "tracks:unqueue": unqueue_,
            "tracks:exclude": exclude, "tracks:retry": retry, "tracks:restem": restem}


def main():
    for name, path in (("MUSIC_DIR", MUSIC_DIR), ("STEMS_DIR", STEMS_DIR)):
        if not os.path.isdir(path):
            sys.exit(f"{name} not found: {path}")
    if MODEL not in MODELS_BY_ID:
        sys.exit(f"unknown MODEL {MODEL!r}; pick one of: {', '.join(MODELS_BY_ID)}")
    # docker stop sends SIGTERM; stop between (or inside) tracks instead of dying mid-write.
    signal.signal(signal.SIGTERM, lambda *_: (stop.set(), STATE.wake.set()))
    signal.signal(signal.SIGINT, lambda *_: (stop.set(), STATE.wake.set()))
    try:
        os.nice(10)
    except OSError:
        pass
    try:
        os.makedirs(QUEUE_DIR, exist_ok=True)
    except OSError as e:
        log(f"WARNING: can't create {QUEUE_DIR} ({e}); Stem Now won't work")
    log(f"stemd: {MUSIC_DIR} -> {STEMS_DIR}")
    STATE.paused = os.path.exists(PAUSED_PATH)
    if STATE.paused:
        log("paused from the page; resume there")
    model = MODELS_BY_ID[MODEL]
    STATE.info = {"model": MODEL, "label": model.get("label", MODEL), "stems": model.get("stems"),
                  "device": DEVICE, "fp16": FP16, "overlap": OVERLAP, "segment": SEGMENT, "shifts": SHIFTS,
                  "rescanMin": RESCAN_S / 60, "music": MUSIC_DIR, "stemsDir": STEMS_DIR, "maxMin": MAX_SECONDS / 60,
                  "models": {k: m.get("label", k) for k, m in MODELS_BY_ID.items()}}
    failed = Failed()
    if UI_PORT:
        try:
            stemd_web.serve(STATE, track_actions(failed), UI_PORT, UI_TOKEN)
            log(f"page on port {UI_PORT}")
        except OSError as e:
            log(f"WARNING: no page, port {UI_PORT}: {e}")
    migrate_markers()
    STATE.set_phase("starting", "loading the model")
    sep = Separator()
    STATE.info["gpu"] = sep.torch.cuda.get_device_name(sep.device) if sep.device.type == "cuda" else "CPU"
    STATE.info["torch"] = sep.torch.__version__
    while not stop.is_set():
        try:
            done, used = run_pass(sep, failed)
            if done:
                log(f"pass finished: {done:,} tracks separated")
            if used:
                sep.release()  # hand the VRAM back while idle
            idle(sep, failed, RESCAN_S)
        except OSError as e:
            if stop.is_set():
                break
            if not is_share_error(e):
                raise
            sep.release()
            STATE.set_phase("share", f"can't write to {STEMS_DIR}: {e}")
            stop.wait(SHARE_RETRY_S)
        except BaseException:
            if stop.is_set():
                break
            raise
    log("stopped")


if __name__ == "__main__":
    main()
