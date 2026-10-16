"""Downloads: proper offline copies, every stem fully fetched (or the original file for songs
without stems), plus their waveform, kept until removed. ~/.local/share/stemify/mobile/downloads."""

import hashlib
import json
import os
import shutil
import threading
import time
import urllib.request

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

from ..player.player import WAVE_BUCKETS
from ..player.stems import sort_names

SLIM = ("key", "title", "artist", "albumArtist", "album", "albumKey", "artistKey", "year", "index", "disc",
        "durationMs", "thumb", "partFile", "partKey", "codec", "stemKey")


def _dir_size(d):
    total = 0
    for root, _, files in os.walk(d):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


class Downloads(QObject):
    changed = Signal()
    progressChanged = Signal()
    usageChanged = Signal()
    _step = Signal(str, object)

    def __init__(self, cfg, plex, stems, art, data_dir, cache_dir, quality, parent=None):
        super().__init__(parent)
        self.cfg, self.plex, self.server, self.art = cfg, plex, stems, art
        self.dir = os.path.join(data_dir, "downloads")
        self.cache_dir = cache_dir
        self.quality = quality
        self._file = os.path.join(data_dir, "downloads.json")
        self.records = {}
        try:
            with open(self._file) as f:
                self.records = json.load(f)
        except (OSError, ValueError):
            pass
        self.progress = {}
        self._usage = {"pinned": 0, "cached": 0}
        self._queue = [k for k, r in self.records.items() if not r.get("done") and not r.get("error")]
        self._cond = threading.Condition()
        self._step.connect(self._on_step)
        self._tick = QTimer(self, interval=250, singleShot=True)
        self._tick.timeout.connect(self.progressChanged)
        threading.Thread(target=self._worker, daemon=True, name="downloads").start()
        self.refreshUsage()

    # ---- queries ------------------------------------------------------------------------------
    def _folder(self, key):
        return os.path.join(self.dir, hashlib.sha1(key.encode()).hexdigest())

    def files(self, key):
        """{names, paths, stems, stemKey} for a finished download, else None."""
        r = self.records.get(key)
        if not r or not r.get("done"):
            return None
        f = r.get("files") or {}
        if not f.get("paths") or not all(os.path.exists(p) for p in f["paths"]):
            return None
        return f

    def peaks(self, key):
        r = self.records.get(key)
        if not r or not r.get("done"):
            return None
        try:
            with open(os.path.join(self._folder(key), "peaks.json")) as f:
                return json.load(f) or None
        except (OSError, ValueError):
            return None

    # ---- QML ----------------------------------------------------------------------------------
    def _get_states(self):
        """key → {done, error} for every downloaded or queued song."""
        return {k: {"done": bool(r.get("done")), "error": r.get("error") or ""} for k, r in self.records.items()}

    def _get_list(self):
        return [dict(r["track"], added=r.get("added", 0), done=bool(r.get("done")), error=r.get("error") or "")
                for r in self.records.values()]

    states = Property("QVariantMap", _get_states, notify=changed)
    tracks = Property("QVariantList", _get_list, notify=changed)
    progressMap = Property("QVariantMap", lambda self: dict(self.progress), notify=progressChanged)
    usage = Property("QVariantMap", lambda self: dict(self._usage), notify=usageChanged)

    @Slot("QVariantList")
    def download(self, tracks):
        now = time.time()
        with self._cond:
            for t in tracks:
                k = t.get("key")
                if not k or (k in self.records and not self.records[k].get("error")):
                    continue
                self.records[k] = {"track": {f: t[f] for f in SLIM if f in t}, "added": now, "done": False, "error": None}
                self._queue.append(k)
                now += 0.001  # keeps the album's order when sorting by download order
            self._cond.notify()
        self._save()
        self.changed.emit()

    @Slot("QVariantList")
    def remove(self, tracks):
        with self._cond:
            for t in tracks:
                k = t.get("key")
                if k in self.records:
                    del self.records[k]
                    if k in self._queue:
                        self._queue.remove(k)
                    shutil.rmtree(self._folder(k), ignore_errors=True)
                    self.progress.pop(k, None)
        self._save()
        self.changed.emit()
        self.refreshUsage()

    @Slot()
    def retryFailed(self):
        with self._cond:
            for k, r in self.records.items():
                if r.get("error"):
                    r["error"] = None
                    self._queue.append(k)
            self._cond.notify()
        self._save()
        self.changed.emit()

    @Slot(str, result=bool)
    def isDownloaded(self, key):
        return bool(self.records.get(key, {}).get("done"))

    @Slot()
    def refreshUsage(self):
        def work():
            pinned = _dir_size(self.dir)
            cached = _dir_size(os.path.join(self.cache_dir, "files"))
            self._step.emit("usage", {"pinned": pinned, "cached": cached})
        threading.Thread(target=work, daemon=True).start()

    @Slot()
    def clearCache(self):
        shutil.rmtree(os.path.join(self.cache_dir, "files"), ignore_errors=True)
        self.refreshUsage()

    # ---- worker -------------------------------------------------------------------------------
    def _save(self):
        os.makedirs(os.path.dirname(self._file), exist_ok=True)
        tmp = self._file + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.records, f)
        os.replace(tmp, self._file)

    def _on_step(self, kind, data):
        if kind == "progress":
            k, frac = data
            if k in self.records:
                self.progress[k] = frac
                if not self._tick.isActive():
                    self._tick.start()
        elif kind == "done":
            k, files, error = data
            r = self.records.get(k)
            self.progress.pop(k, None)
            if r is not None:
                r["done"], r["error"] = error is None, error
                if files:
                    r["files"] = files
                self._save()
                self.changed.emit()
                self.progressChanged.emit()
                self.refreshUsage()
        elif kind == "usage":
            self._usage = data
            self.usageChanged.emit()

    def _worker(self):
        while True:
            with self._cond:
                while not self._queue:
                    self._cond.wait()
                k = self._queue.pop(0)
                r = self.records.get(k)
            if r is None:
                continue
            try:
                files = self._fetch(k, r["track"])
                self._step.emit("done", (k, files, None))
            except Exception as e:  # noqa: BLE001 - shown on the Downloads page
                self._step.emit("done", (k, None, str(e) or type(e).__name__))

    def _fetch(self, key, track):
        folder = self._folder(key)
        os.makedirs(folder, exist_ok=True)
        entry = self.server.index.by_key.get(track.get("stemKey") or "") or self.server.find(track)
        for px in (240, 720):
            if track.get("thumb"):
                self.art.load(track["thumb"], px)
        if entry:
            stems = {s["name"]: s for s in entry["stems"]}
            names = sort_names(list(stems))
            kbps = self.quality()
            if kbps:
                self.server.prepare(entry, kbps)
            paths = []
            for i, n in enumerate(names):
                ext = ".opus" if kbps else os.path.splitext(stems[n]["file"])[1]
                dest = os.path.join(folder, n + ext)
                local = None if kbps else self.server.local_path(entry, stems[n])
                self._get(local, self.server.file_url(entry, stems[n], kbps), dest,
                          lambda f, i=i: self._step.emit("progress", (key, (i + f) / len(names))))
                paths.append(dest)
            peaks = self.server.peaks(entry, WAVE_BUCKETS)
            if peaks:
                with open(os.path.join(folder, "peaks.json"), "w") as f:
                    json.dump(peaks, f)
            return {"names": names, "paths": paths, "stems": True, "stemKey": entry["key"]}
        pf = track.get("partFile") or ""
        dest = os.path.join(folder, "original" + (os.path.splitext(pf)[1] or ".bin"))
        if not track.get("partKey") and not pf:
            raise OSError("No file for this song")
        self._get(self._local_original(pf), self.plex.part_url(track["partKey"]) if track.get("partKey") else "", dest,
                  lambda f: self._step.emit("progress", (key, f)))
        return {"names": ["mix"], "paths": [dest], "stems": False}

    def _local_original(self, pf):
        root = self.cfg.get("musicDir", "")
        if not pf or not root or not os.path.isdir(root):
            return None
        parts = pf.replace("\\", "/").split("/")
        for i in range(1, len(parts)):
            p = os.path.join(root, *parts[i:])
            if os.path.exists(p):
                return p
        return None

    def _get(self, local, url, dest, progress):
        """Copies from the mounted share when it's there, else downloads."""
        tmp = dest + ".part"
        if local:
            shutil.copyfile(local, tmp)
        else:
            if not url:
                raise OSError("Not reachable")
            h = {"X-Stem-Token": self.cfg["stemToken"]} if self.cfg.get("stemToken") and "X-Plex-Token" not in url else {}
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=60) as r, open(tmp, "wb") as f:
                total = int(r.headers.get("Content-Length") or 0)
                got = 0
                while chunk := r.read(1 << 20):
                    f.write(chunk)
                    got += len(chunk)
                    if total:
                        progress(got / total)
        os.replace(tmp, dest)
        progress(1.0)
