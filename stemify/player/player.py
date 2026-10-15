"""The queue and the mix: what plays, where its audio comes from, and how loud each stem is."""

import hashlib
import json
import math
import os
import random
import shutil
import subprocess
import threading
import urllib.request

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

from . import config
from .engine import Source
from .stems import COLORS, sort_names

UNITY = 0.8  # stem slider position for 0 dB (the phone uses the same taper)
WAVE_BUCKETS = 240


def stem_taper(v):
    return (max(0.0, min(1.0, v)) / UNITY) ** 2


# One-click mixes, as on the phone. A preset is a function of the stems a song has, or None
# when the song lacks what it needs.
PRESETS = [
    ("Full Mix", lambda n: {}),
    ("Karaoke", lambda n: {"vocals": {"muted": True}} if "vocals" in n else None),
    ("A Cappella", lambda n: {"vocals": {"solo": True}} if "vocals" in n else None),
    ("No Drums", lambda n: {"drums": {"muted": True}} if "drums" in n else None),
    ("Rhythm", lambda n: {"drums": {"solo": True}, "bass": {"solo": True}} if "drums" in n and "bass" in n else None),
    ("No Guitar", lambda n: {"guitar": {"muted": True}} if "guitar" in n else None),
    ("No Bass", lambda n: {"bass": {"muted": True}} if "bass" in n else None),
]


def _setting(mix, name):
    s = mix.get(name, {})
    return s.get("level", UNITY), bool(s.get("muted")), bool(s.get("solo"))


def _normalized(mix):
    out = {}
    for name, s in mix.items():
        level, muted, solo = s.get("level", UNITY), bool(s.get("muted")), bool(s.get("solo"))
        if abs(level - UNITY) > 1e-3 or muted or solo:
            out[name] = (round(level, 3), muted, solo)
    return out


class Player(QObject):
    changed = Signal()  # track, queue, play state
    mixChanged = Signal()
    waveChanged = Signal()
    toast = Signal(str)
    _loaded = Signal(int, object, object)  # token, Source or error, track
    _wave = Signal(int, object)

    def __init__(self, cfg, plex, stems, engine, parent=None):
        super().__init__(parent)
        self.cfg, self.plex, self.server, self.engine = cfg, plex, stems, engine
        self.library = None
        self.queue: list[dict] = []
        self.index = -1
        self._unshuffled = None
        self.shuffle = False
        self.repeat = "off"  # off | all | one
        self.mix: dict[str, dict] = {}
        self.names: list[str] = []
        self.source_kind = ""  # stems | original
        self._token = 0
        self._loading = False
        self._wave_raw = None  # {"stems": {name: [..]}} or {"db": [..]}
        self._scrobbled = False
        self._pending_seek = 0.0
        self._queue_dirty = False
        self._loaded.connect(self._on_loaded)
        self._wave.connect(self._on_wave)
        engine.ended.connect(self._on_ended)
        engine.playingChanged.connect(self._on_play_state)
        engine.bufferingChanged.connect(self.changed)
        engine.setVolume(cfg.get("volume", 0.8))
        engine.volumeChanged.connect(self._save_soon)
        self._report = QTimer(self, interval=10_000)
        self._report.timeout.connect(self._tick)
        self._report.start()
        self._saver = QTimer(self, interval=1500, singleShot=True)
        self._saver.timeout.connect(self.save)
        self._restore()

    # ---- state -------------------------------------------------------------------------
    # The queue can be the whole library, so it lives in its own file, written off the GUI
    # thread and only when it changes; the small state (position, mix) saves often.
    QUEUE_PATH = os.path.join(config.CONFIG_DIR, "player-queue.json")
    QUEUE_KEYS = ("key", "title", "artist", "albumArtist", "album", "albumKey", "artistKey", "year", "index", "disc",
                  "durationMs", "thumb", "partFile", "partKey", "codec", "stemKey", "_cover")

    def _restore(self):
        st = self.cfg.get("state") or {}
        try:
            with open(self.QUEUE_PATH) as f:
                q = json.load(f)
            self.queue, self._unshuffled = q.get("queue") or [], q.get("unshuffled")
        except (OSError, ValueError):
            self.queue, self._unshuffled = st.get("queue") or [], st.get("unshuffled")
        self.index = st.get("index", -1) if self.queue else -1
        self.shuffle = bool(st.get("shuffle"))
        self.repeat = st.get("repeat", "off")
        self.mix = st.get("mix") or {}
        self._pending_seek = float(st.get("position") or 0)
        if 0 <= self.index < len(self.queue):
            QTimer.singleShot(0, lambda: self._load(self.index, play=False, start=self._pending_seek))

    def save(self, wait=False):
        self.cfg["volume"] = round(self.engine.volume, 3)
        self.cfg["state"] = {
            "index": self.index, "shuffle": self.shuffle, "repeat": self.repeat, "mix": self.mix,
            "position": round(self.engine.position() if self.engine.duration else self._pending_seek, 2),
        }
        config.save(self.cfg)
        if self._queue_dirty:
            self._queue_dirty = False
            slim = lambda ts: [{k: t[k] for k in self.QUEUE_KEYS if k in t} for t in ts] if ts is not None else None
            data = {"queue": slim(self.queue), "unshuffled": slim(self._unshuffled)}

            def write():
                tmp = self.QUEUE_PATH + ".tmp"
                with open(tmp, "w") as f:
                    json.dump(data, f)
                os.replace(tmp, self.QUEUE_PATH)
            t = threading.Thread(target=write, daemon=not wait)
            t.start()
            if wait:
                t.join()

    def _save_soon(self):
        self._saver.start()

    def current(self):
        return self.queue[self.index] if 0 <= self.index < len(self.queue) else None

    def current_key(self):
        t = self.current()
        return t["key"] if t else ""

    # ---- queue ---------------------------------------------------------------------------
    def play_tracks(self, tracks, start=0, shuffle=False):
        tracks = [dict(t) for t in tracks]
        if not tracks:
            return
        self._unshuffled = list(tracks) if shuffle else None
        if shuffle:
            first = tracks.pop(start)
            random.shuffle(tracks)
            tracks.insert(0, first)
            start = 0
        self.shuffle = shuffle
        self.queue = tracks
        if not self.cfg.get("keepMix"):
            self.mix = {}
        self._load(start, play=True)
        self._queue_changed()

    def add_to_queue(self, tracks):
        self.queue += [dict(t) for t in tracks]
        if self.index < 0:
            self._load(0, play=True)
        self._queue_changed()

    def play_next(self, tracks):
        at = self.index + 1
        self.queue[at:at] = [dict(t) for t in tracks]
        if self.index < 0:
            self._load(0, play=True)
        self._queue_changed()

    @Slot(int)
    def removeAt(self, i):
        if not (0 <= i < len(self.queue)):
            return
        del self.queue[i]
        if i < self.index:
            self.index -= 1
        elif i == self.index:
            if self.queue:
                self._load(min(i, len(self.queue) - 1), play=self.engine.playing)
            else:
                self.index = -1
                self.engine.clear()
        self._queue_changed()

    @Slot()
    def clearQueue(self):
        cur = self.current()
        self.queue = [cur] if cur else []
        self.index = 0 if cur else -1
        self._unshuffled = None
        self._queue_changed()

    @Slot(int)
    def skipTo(self, i):
        if 0 <= i < len(self.queue):
            self._load(i, play=True)

    def _queue_changed(self):
        self._queue_dirty = True
        if self.library:
            self.library.update_queue()
        self.changed.emit()
        self._save_soon()

    # ---- transport -------------------------------------------------------------------------
    @Slot()
    def toggle(self):
        if self.engine.duration:
            self.engine.toggle()
        elif self.current() and not self._loading:
            self._load(self.index, play=True, start=self._pending_seek)

    @Slot()
    def next(self):
        if not self.queue:
            return
        i = self.index + 1
        if i >= len(self.queue):
            if self.repeat != "all":
                return
            i = 0
        self._load(i, play=True)

    @Slot()
    def previous(self):
        if not self.queue:
            return
        if self.engine.position() > 3 or self.index <= 0:
            self.engine.seek(0)
            return
        self._load(self.index - 1, play=True)

    @Slot(float)
    def seek(self, seconds):
        if self.engine.duration:
            self.engine.seek(max(0.0, min(seconds, self.engine.duration - 0.25)))
        else:
            self._pending_seek = seconds

    @Slot()
    def toggleShuffle(self):
        cur = self.current()
        if not self.shuffle:
            self._unshuffled = list(self.queue)
            rest = [t for i, t in enumerate(self.queue) if i != self.index]
            random.shuffle(rest)
            self.queue = ([cur] if cur else []) + rest
            self.index = 0 if cur else -1
            self.shuffle = True
        else:
            if self._unshuffled:
                self.queue = self._unshuffled
                keys = [t["key"] for t in self.queue]
                self.index = keys.index(cur["key"]) if cur and cur["key"] in keys else 0
            self._unshuffled = None
            self.shuffle = False
        self._queue_changed()

    @Slot()
    def cycleRepeat(self):
        self.repeat = {"off": "all", "all": "one", "one": "off"}[self.repeat]
        self.changed.emit()
        self._save_soon()

    def _on_ended(self):
        cur = self.current()
        if cur and not self._scrobbled:
            self._scrobbled = True
            threading.Thread(target=self.plex.scrobble, args=(cur["key"],), daemon=True).start()
        if self.repeat == "one":
            self._load(self.index, play=True)
        elif self.index + 1 < len(self.queue) or self.repeat == "all":
            self.next()
        else:
            self.engine.seek(0)
            self.changed.emit()

    def _on_play_state(self):
        self._report_state("playing" if self.engine.playing else "paused")
        self.changed.emit()
        self._save_soon()

    # ---- loading ------------------------------------------------------------------------------
    def _load(self, i, play=True, start=0.0):
        """Opens queue[i] off the GUI thread; a newer request makes older ones moot."""
        if not (0 <= i < len(self.queue)):
            return
        if self.engine.playing and self.current():
            self._report_state("stopped")
        self._token += 1
        token = self._token
        self.index = i
        self._loading = True
        self._scrobbled = False
        self._wave_raw = None
        track = self.queue[i]
        if not self.cfg.get("keepMix") and start == 0.0:
            self.mix = {}
        self.engine.pause()
        if self.library:
            self.library.model.set_playing(track["key"], i)
        self.changed.emit()
        self.waveChanged.emit()
        self._pending_seek = start

        def work():
            try:
                src, entry = self._open(track)
                self._loaded.emit(token, (src, play, start, entry), track)
            except Exception as e:  # noqa: BLE001 - reported, then skipped
                self._loaded.emit(token, e, track)
        threading.Thread(target=work, daemon=True).start()

    def _open(self, track):
        entry = self.server.index.by_key.get(track.get("stemKey") or "") or self.server.find(track)
        if entry and "_cover" not in track:
            track["_cover"] = self._cover(entry)
        if entry:
            stems = {s["name"]: s for s in entry["stems"]}
            names = sort_names(list(stems))
            paths = [self.server.local_path(entry, stems[n]) or self._download(self.server.file_url(entry, stems[n]), stems[n]["file"])
                     for n in names]
            return Source(names, paths), entry
        return Source(["mix"], [self._original_path(track)]), None

    def _cover(self, entry):
        """The album folder's own cover on the music share: art for songs Plex doesn't know."""
        root = self.cfg.get("musicDir", "")
        folder = os.path.join(root, os.path.dirname(entry.get("source", ""))) if root else ""
        try:
            pics = [f for f in os.listdir(folder) if f.lower().endswith((".jpg", ".jpeg", ".png"))]
        except OSError:
            return ""
        if pics:
            pics.sort(key=lambda f: (not f.lower().startswith(("cover", "folder", "front")), f.lower()))
            return "file://" + urllib.request.pathname2url(os.path.join(folder, pics[0]))
        # no picture file: the art embedded in the song itself, pulled out once by ffmpeg
        src = os.path.join(root, entry["source"])
        out = os.path.join(config.CACHE_DIR, "art", hashlib.sha1(folder.encode()).hexdigest() + ".jpg")
        if not os.path.exists(out) and os.path.exists(src) and shutil.which("ffmpeg"):
            os.makedirs(os.path.dirname(out), exist_ok=True)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-an", "-frames:v", "1", "-vf", "scale=600:-2", out],
                           stdin=subprocess.DEVNULL, capture_output=True, timeout=20)
        return "file://" + urllib.request.pathname2url(out) if os.path.exists(out) else ""

    def _original_path(self, track):
        """The file on the music share when it's mounted, else a download from Plex."""
        pf = track.get("partFile") or ""
        local = self.cfg.get("musicDir", "")
        if pf and local and os.path.isdir(local):
            for loc in self.cfg.get("sectionLocations") or []:
                if pf.startswith(loc.rstrip("/") + "/"):
                    p = os.path.join(local, pf[len(loc.rstrip("/")) + 1:])
                    if os.path.exists(p):
                        return p
            parts = pf.replace("\\", "/").split("/")
            for i in range(1, len(parts)):
                p = os.path.join(local, *parts[i:])
                if os.path.exists(p):
                    return p
        if not track.get("partKey"):
            raise OSError(f"No file for {track.get('title')}")
        return self._download(self.plex.part_url(track["partKey"]), os.path.basename(pf) or "track")

    def _download(self, url, name):
        d = os.path.join(config.CACHE_DIR, "files")
        os.makedirs(d, exist_ok=True)
        ext = os.path.splitext(name)[1] or ".bin"
        path = os.path.join(d, hashlib.sha1(url.split("X-Plex-Token")[0].encode()).hexdigest() + ext)
        if os.path.exists(path):
            os.utime(path)
            return path
        tmp = path + ".part"
        req = urllib.request.Request(url, headers={"X-Stem-Token": self.cfg["stemToken"]} if self.cfg.get("stemToken") else {})
        with urllib.request.urlopen(req, timeout=30) as r, open(tmp, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        os.replace(tmp, path)
        self._trim_cache(d)
        return path

    @staticmethod
    def _trim_cache(d, limit=4 << 30):
        files = [os.path.join(d, f) for f in os.listdir(d) if not f.endswith(".part")]
        files.sort(key=lambda p: os.path.getmtime(p))
        total = sum(os.path.getsize(p) for p in files)
        while files and total > limit:
            p = files.pop(0)
            total -= os.path.getsize(p)
            os.remove(p)

    def _on_loaded(self, token, result, track):
        if token != self._token:
            if not isinstance(result, Exception):
                result[0].close()
            return
        self._loading = False
        if isinstance(result, Exception):
            self.toast.emit(f"Couldn't play “{track.get('title')}”: {result}")
            self.changed.emit()
            if self.index + 1 < len(self.queue):
                QTimer.singleShot(800, lambda t=token: t == self._token and self.next())
            return
        src, play, start, entry = result
        self.names = src.names
        self.source_kind = "stems" if entry else "original"
        self.engine.set_source(src, start, play=play)
        self._push_mix()
        self.changed.emit()
        self._load_wave(token, track, entry)
        self._save_soon()

    def _load_wave(self, token, track, entry):
        def work():
            w = None
            if entry:
                st = self.server.peaks(entry, WAVE_BUCKETS)
                w = {"stems": st} if st else None
            if w is None:
                try:
                    db = self.plex.loudness(track["key"], WAVE_BUCKETS)
                    w = {"db": db} if db else None
                except Exception:  # noqa: BLE001 - no waveform is fine
                    w = None
            self._wave.emit(token, w)
        threading.Thread(target=work, daemon=True).start()

    def _on_wave(self, token, w):
        if token == self._token:
            self._wave_raw = w
            self.waveChanged.emit()

    # ---- mix -----------------------------------------------------------------------------------
    def _push_mix(self):
        any_solo = any(_setting(self.mix, n)[2] for n in self.names)
        gains = []
        for n in self.names:
            level, muted, solo = _setting(self.mix, n)
            gains.append(0.0 if (any_solo and not solo) or muted else stem_taper(level))
        self.engine.set_gains(gains)
        self.mixChanged.emit()
        self.waveChanged.emit()
        self._save_soon()

    def _edit(self, i, **kw):
        if 0 <= i < len(self.names):
            n = self.names[i]
            self.mix[n] = {**self.mix.get(n, {}), **kw}
            self._push_mix()

    @Slot(int, float)
    def setLevel(self, i, v):
        self._edit(i, level=max(0.0, min(1.0, v)))

    @Slot(int)
    def toggleMute(self, i):
        if 0 <= i < len(self.names):
            self._edit(i, muted=not _setting(self.mix, self.names[i])[1])

    @Slot(int)
    def toggleSolo(self, i):
        if 0 <= i < len(self.names):
            self._edit(i, solo=not _setting(self.mix, self.names[i])[2])

    @Slot(int)
    def applyPreset(self, p):
        m = PRESETS[p][1](self.names)
        if m is not None:
            self.mix = {k: dict(v) for k, v in m.items()}
            self._push_mix()

    @Slot()
    def toggleVocals(self):
        """Karaoke switch: vocals off, or back to the full mix."""
        if "vocals" not in self.names:
            return
        self.applyPreset(0 if self._preset_index() == 1 else 1)

    def _preset_index(self):
        m = _normalized(self.mix)
        for i, (_, build) in enumerate(PRESETS):
            b = build(self.names)
            if b is not None and _normalized(b) == m:
                return i
        return -1

    # ---- reporting to Plex (play counts, "now playing" on the server) -------------------------------
    def _report_state(self, state):
        cur = self.current()
        if cur:
            pos = self.engine.position() * 1000
            threading.Thread(target=self.plex.timeline, args=(cur, state, pos), daemon=True).start()

    def _tick(self):
        if self.engine.playing:
            self._report_state("playing")
            cur, d = self.current(), self.engine.duration
            if cur and d and not self._scrobbled and self.engine.position() > 0.9 * d:
                self._scrobbled = True
                threading.Thread(target=self.plex.scrobble, args=(cur["key"],), daemon=True).start()
            self.save()

    def shutdown(self):
        self._report_state("stopped")
        self.save(wait=True)

    # ---- QML properties -------------------------------------------------------------------------
    def _t(self, k, default=""):
        t = self.current()
        return t.get(k, default) if t else default

    def _get_track(self):
        t = self.current()
        if not t:
            return {}
        return {"title": t["title"], "artist": t["artist"], "album": t["album"], "year": t.get("year") or 0,
                "art": self.plex.art_url(t.get("thumb"), 600) or t.get("_cover", ""), "durationMs": t.get("durationMs", 0),
                "codec": t.get("codec", ""), "key": t["key"]}

    def _get_status(self):
        if self._loading:
            return "loading"
        if self.engine.buffering:
            return "buffering"
        return "playing" if self.engine.playing else ("paused" if self.current() else "")

    def _get_stems(self):
        any_solo = any(_setting(self.mix, n)[2] for n in self.names)
        out = []
        for n in self.names:
            level, muted, solo = _setting(self.mix, n)
            out.append({"name": n, "label": "Original mix" if n == "mix" else n.capitalize(),
                        "color": COLORS.get(n, "#8E8E93"), "level": level, "muted": muted, "solo": solo,
                        "audible": not muted and (solo or not any_solo), "db": 20 * math.log10(max(1e-6, stem_taper(level)))})
        return out

    def _get_presets(self):
        cur = self._preset_index()
        return [{"label": label, "usable": build(self.names) is not None and self.source_kind == "stems", "active": i == cur}
                for i, (label, build) in enumerate(PRESETS)]

    def _get_preset_label(self):
        if self.source_kind != "stems":
            return ""
        i = self._preset_index()
        return PRESETS[i][0] if i >= 0 else "Custom Mix"

    def _get_wave(self):
        """0..1 loudness per bucket for the current mix: stems weighted by their faders."""
        w = self._wave_raw
        if not w:
            return []
        if "stems" in w:
            any_solo = any(_setting(self.mix, n)[2] for n in self.names)
            gains = {}
            for n in w["stems"]:
                level, muted, solo = _setting(self.mix, n)
                gains[n] = 0.0 if (any_solo and not solo) or muted else stem_taper(level)
            size = max(len(a) for a in w["stems"].values())
            out = []
            for i in range(size):
                e = sum((a[i] if i < len(a) else 0.0) ** 2 * gains.get(n, 1.0) ** 2 for n, a in w["stems"].items())
                out.append(_norm_db(20 * math.log10(math.sqrt(e) + 1e-6)))
            return out
        return [_norm_db(v) for v in w["db"]]

    def _get_queue_info(self):
        return {"index": self.index, "count": len(self.queue)}

    track = Property("QVariantMap", _get_track, notify=changed)
    status = Property(str, _get_status, notify=changed)
    stems = Property("QVariantList", _get_stems, notify=mixChanged)
    presets = Property("QVariantList", _get_presets, notify=mixChanged)
    presetLabel = Property(str, _get_preset_label, notify=mixChanged)
    sourceKind = Property(str, lambda self: self.source_kind, notify=changed)
    wave = Property("QVariantList", _get_wave, notify=waveChanged)
    shuffleOn = Property(bool, lambda self: self.shuffle, notify=changed)
    repeatMode = Property(str, lambda self: self.repeat, notify=changed)
    queueInfo = Property("QVariantMap", _get_queue_info, notify=changed)
    keepMix = Property(bool, lambda self: bool(self.cfg.get("keepMix")), notify=mixChanged)

    @Slot(bool)
    def setKeepMix(self, on):
        self.cfg["keepMix"] = on
        self.mixChanged.emit()
        self._save_soon()


def _norm_db(db):
    """Loudness to bar height, as on the phone: -34 dB and below is a stub, -6 dB and up full."""
    return max(0.06, min(1.0, (db + 34) / 28))
