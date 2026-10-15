#!/usr/bin/env python3
"""stemd: separates every track in the music library that doesn't have stems yet.

Runs on the server next to stemserve. Walks MUSIC_DIR, and for each track whose folder
under STEMS_DIR has no marker it runs the separation on the GPU and writes the stems with
the same layout and marker as the desktop app (stemroller/library_core.py), so stemserve
and the phone pick them up as they land. Once caught up it looks again every RESCAN_MIN.

"Stem Now" in the app goes through stemserve, which drops a request (a JSON list of Plex
file paths) into QUEUE_DIR. Those tracks are separated before the rest: between tracks
during a pass, and within a few seconds while idle.

Settings (environment):

  MUSIC_DIR   /music           the music library (read-only is fine)
  STEMS_DIR   /stems           where stems go
  STATE_DIR   /state           failed-track list
  QUEUE_DIR   STATE_DIR/queue  "Stem Now" requests from stemserve
  MODELS_DIR  /models          model weights, downloaded on first use
  MODEL       roformer_bs_sw   any id from stemroller/catalog.py
  DEVICE      cuda             cuda, cuda:N or cpu
  RESCAN_MIN  30               minutes between scans once caught up
  FP16        1                half precision on the GPU
  OVERLAP / SEGMENT / SHIFTS   as in the desktop app's settings
"""

import glob
import json
import os
import shutil
import signal
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from stemroller.catalog import MODELS_BY_ID  # noqa: E402
from stemroller.library_core import (MAX_SECONDS, SOURCE_RANK, duration, is_done, is_share_error,  # noqa: E402
                                     iter_tracks, signature, write_track)

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
FAILED_PATH = os.path.join(STATE_DIR, "failed.json")
QUEUE_POLL_S = 5  # how often an idle stemd looks for Stem Now requests
SHARE_RETRY_S = 5 * 60  # stems folder unwritable: try again this soon rather than at RESCAN_S
MAX_CRASHES = 2  # GPU crashes on one track before it's given up on

stop = threading.Event()


def log(*args):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *args, flush=True)


class Failed:
    """Tracks that didn't separate, keyed by path, retried once the source file changes."""

    def __init__(self):
        try:
            with open(FAILED_PATH) as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def skip(self, path, sig):
        e = self.data.get(path)
        return bool(e) and e["sig"] == sig and e.get("tries", MAX_CRASHES) >= MAX_CRASHES

    def add(self, path, sig, msg, crash=False):
        tries = MAX_CRASHES
        if crash:
            e = self.data.get(path)
            tries = (e.get("tries", 0) if e and e["sig"] == sig else 0) + 1
        self.data[path] = {"sig": sig, "msg": msg, "tries": tries, "at": time.time()}
        os.makedirs(STATE_DIR, exist_ok=True)
        tmp = FAILED_PATH + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, indent=1)
        os.replace(tmp, FAILED_PATH)


class _NoProgress:
    """Stands in for the worker's socket; per-track progress isn't worth a log line."""

    def send(self, *_, **__):
        pass


class Separator:
    def __init__(self):
        import torch
        from stemroller.worker.backends import DemucsBackend, RoformerBackend

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
        from stemroller.worker.__main__ import Job
        job = Job({"id": "stemd", "file": path, "model": MODEL, "out_dir": tmp, "segment": SEGMENT,
                   "shifts": SHIFTS, "overlap": OVERLAP, "fp16": FP16}, _NoProgress())
        if "file" in self.model and not os.path.exists(os.path.join(MODELS_DIR, self.model["file"])):
            log("    downloading the model (first run only)")
        job.cancel_event = stop
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
        return False
    log(f"{label} {rel}" + (f" ({int(dur) // 60}:{int(dur) % 60:02d})" if dur else ""))
    t1 = time.monotonic()
    tmp = tempfile.mkdtemp(prefix="stemd-")
    try:
        stems, sr = sep.run(path, tmp)
        meta = {"source": path, "size": sig[0], "mtime_ns": sig[1], "model": MODEL, "samplerate": sr,
                "duration": len(next(iter(stems.values()))) / sr}
        write_track(dest, stems, meta)
    except BaseException as e:
        if stop.is_set():
            raise  # Cancelled; the track is redone next start
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
        return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    took = time.monotonic() - t1
    log(f"    done in {took:.0f}s" + (f" ({meta['duration'] / took:.1f}x realtime)" if took else ""))
    return True


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


def serve_queue(sep, failed):
    """Separate every Stem Now request, oldest first. Returns whether any track was attempted.

    A request file is deleted once all its tracks are done, so one cut short by a restart
    picks up where it left off (finished tracks are skipped)."""
    used = False
    while not stop.is_set():
        reqs = sorted(glob.glob(os.path.join(QUEUE_DIR, "*.json")))
        if not reqs:
            break
        req = reqs[0]
        try:
            with open(req) as f:
                paths = json.load(f).get("paths", [])
        except (OSError, ValueError, AttributeError) as e:
            log(f"stem now: unreadable request {os.path.basename(req)}: {e}")
            paths = []
        todo = []
        for p in paths if isinstance(paths, list) else []:
            src = resolve(p) if isinstance(p, str) else None
            if src is None:
                log(f"stem now: not in {MUSIC_DIR}: {p}")
                continue
            dest = os.path.join(STEMS_DIR, os.path.splitext(os.path.relpath(src, MUSIC_DIR))[0])
            if is_done(dest):
                continue
            try:
                sig = signature(src)
            except OSError:
                continue
            if failed.skip(src, sig):
                log(f"stem now: skip {os.path.relpath(src, MUSIC_DIR)}: failed before ({failed.data[src]['msg']})")
                continue
            todo.append((src, dest, sig))
        if todo:
            log(f"stem now: {len(todo)} track{'' if len(todo) == 1 else 's'} ahead of the rest")
        for i, (src, dest, sig) in enumerate(todo, 1):
            if stop.is_set():
                return used
            if is_done(dest):
                continue
            used = True
            separate(sep, failed, src, dest, sig, f"[now {i}/{len(todo)}]")
        if stop.is_set():
            break
        try:
            os.remove(req)
        except FileNotFoundError:
            pass
    return used


def run_pass(sep, failed):
    """Separate everything that needs it. Returns (tracks done, whether the GPU was used)."""
    t0 = time.monotonic()
    todo, total = [], 0
    for path, dest in iter_tracks(MUSIC_DIR, STEMS_DIR):
        if stop.is_set():
            return 0, False
        total += 1
        if is_done(dest):
            continue
        try:
            sig = signature(path)
        except OSError:
            continue
        if not failed.skip(path, sig):
            todo.append((path, dest, sig))
    log(f"scan: {total:,} tracks, {total - len(todo):,} done or skipped, {len(todo):,} to do "
        f"({time.monotonic() - t0:.0f}s)")
    if not total:
        log(f"WARNING: no music found in {MUSIC_DIR}; is the share mounted and readable?")

    done, used = 0, False
    for i, (path, dest, sig) in enumerate(todo, 1):
        used = serve_queue(sep, failed) or used
        if stop.is_set():
            break
        if is_done(dest):  # a full pass takes days; the desktop or a Stem Now may have got there first
            continue
        used = True
        if separate(sep, failed, path, dest, sig, f"[{i}/{len(todo)}]"):
            done += 1
    return done, used


def idle(sep, failed, seconds):
    """Wait for the next scan, separating Stem Now requests as they arrive."""
    end = time.monotonic() + seconds
    while not stop.is_set() and time.monotonic() < end:
        if serve_queue(sep, failed):
            sep.release()
        stop.wait(min(QUEUE_POLL_S, max(0.0, end - time.monotonic())))


def main():
    for name, path in (("MUSIC_DIR", MUSIC_DIR), ("STEMS_DIR", STEMS_DIR)):
        if not os.path.isdir(path):
            sys.exit(f"{name} not found: {path}")
    if MODEL not in MODELS_BY_ID:
        sys.exit(f"unknown MODEL {MODEL!r}; pick one of: {', '.join(MODELS_BY_ID)}")
    # docker stop sends SIGTERM; stop between (or inside) tracks instead of dying mid-write.
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    try:
        os.nice(10)
    except OSError:
        pass
    try:
        os.makedirs(QUEUE_DIR, exist_ok=True)
    except OSError as e:
        log(f"WARNING: can't create {QUEUE_DIR} ({e}); Stem Now won't work")
    log(f"stemd: {MUSIC_DIR} -> {STEMS_DIR}")
    sep = Separator()
    failed = Failed()
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
            stop.wait(SHARE_RETRY_S)
        except BaseException:
            if stop.is_set():
                break
            raise
    log("stopped")


if __name__ == "__main__":
    main()
