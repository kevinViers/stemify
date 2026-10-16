"""The desktop player's queue and mix, with the phone's extras: downloads first, Stem Quality,
10-second skips, and a queue you can reorder."""

import math
import os
import random
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtCore import Property, Signal, Slot

from ..player import config
from ..player.engine import Source
from ..player.player import UNITY, Player, stem_taper
from ..player.stems import sort_names

QUEUE_FIELDS = ("key", "title", "artist", "album", "albumKey", "artistKey", "thumb", "durationMs", "_cover")


class MobilePlayer(Player):
    queueChanged = Signal()
    # PySide crashes when a subclass's new properties notify through a base class signal, so the
    # base's signals are relayed through these.
    stateChanged = Signal()
    envelopeChanged = Signal()

    QUEUE_PATH = os.path.join(config.CONFIG_DIR, "mobile-queue.json")

    def __init__(self, cfg, plex, stems, engine, downloads, quality, parent=None):
        self.downloads = downloads
        self.quality = quality  # () -> Opus kbps for streaming, 0 for FLAC
        super().__init__(cfg, plex, stems, engine, parent)
        engine.durationChanged.connect(self.changed)
        self.changed.connect(self.stateChanged)
        self.waveChanged.connect(self.envelopeChanged)
        self.mixChanged.connect(self.envelopeChanged)

    # ---- where the audio comes from ---------------------------------------------------------
    def _open(self, track):
        d = self.downloads.files(track["key"])
        if d:
            entry = self.server.index.by_key.get(d.get("stemKey") or "")
            src = Source(d["names"], d["paths"])
            src.fmt = "OPUS" if d["paths"][0].endswith(".opus") else "FLAC"
            return src, (entry or {"key": d.get("stemKey"), "stems": []}) if d.get("stems") else None
        entry = self.server.index.by_key.get(track.get("stemKey") or "") or self.server.find(track)
        if not entry:
            return Source(["mix"], [self._original_path(track)]), None
        if "_cover" not in track:
            track["_cover"] = self._cover(entry)
        stems = {s["name"]: s for s in entry["stems"]}
        names = sort_names(list(stems))
        local = [self.server.local_path(entry, stems[n]) for n in names]
        kbps = 0 if all(local) else self.quality()
        if kbps:
            self.server.prepare(entry, kbps)

        def fetch(i):
            if local[i]:
                return local[i]
            st = stems[names[i]]
            name = os.path.splitext(st["file"])[0] + ".opus" if kbps else st["file"]
            return self._download(self.server.file_url(entry, st, kbps), name)
        with ThreadPoolExecutor(max_workers=6) as pool:
            paths = list(pool.map(fetch, range(len(names))))
        src = Source(names, paths)
        src.fmt = f"OPUS {kbps}" if kbps else "FLAC"
        return src, entry

    def _on_loaded(self, token, result, track):
        if token == self._token and not isinstance(result, Exception):
            self._format = getattr(result[0], "fmt", "")
        super()._on_loaded(token, result, track)

    def _load_wave(self, token, track, entry):
        peaks = self.downloads.peaks(track["key"])
        if peaks:
            self._wave.emit(token, {"stems": peaks})
            return
        super()._load_wave(token, track, entry)

    def _trim_cache(self, d, limit=None):
        Player._trim_cache(d, int(self.cfg.get("cacheLimitGb", 10)) << 30)

    # ---- queue ---------------------------------------------------------------------------------
    def _queue_changed(self):
        super()._queue_changed()
        self.queueChanged.emit()

    def _load(self, i, play=True, start=0.0):
        super()._load(i, play, start)
        self.queueChanged.emit()

    @Slot("QVariantList", int, bool)
    def playTracks(self, tracks, start=0, shuffle=False):
        if shuffle and start <= 0 and tracks:
            start = random.randrange(len(tracks))
        self.play_tracks(tracks, max(0, start), shuffle)

    @Slot("QVariantList")
    def playNextList(self, tracks):
        self.play_next(tracks)

    @Slot("QVariantList")
    def addList(self, tracks):
        self.add_to_queue(tracks)

    @Slot(int, int)
    def moveItem(self, a, b):
        q = self.queue
        if not (0 <= a < len(q) and 0 <= b < len(q)) or a == b:
            return
        cur = self.current()
        q.insert(b, q.pop(a))
        if cur is not None:
            self.index = next(i for i, t in enumerate(q) if t is cur)
        self._queue_changed()

    @Slot()
    def clearUpNext(self):
        del self.queue[self.index + 1:]
        if self._unshuffled is not None:
            self._unshuffled = None
            self.shuffle = False
        self._queue_changed()

    @Slot(float)
    def skipBy(self, seconds):
        """Jumps back or ahead in the song; past the end is the next song."""
        d = self.engine.duration
        if not d:
            self._pending_seek = max(0.0, self._pending_seek + seconds)
            return
        to = self.engine.position() + seconds
        if to >= d:
            self.next()
        else:
            self.engine.seek(max(0.0, to))
            self.changed.emit()

    def _get_upcoming(self):
        return [{k: t.get(k, "") for k in QUEUE_FIELDS} for t in self.queue[self.index + 1:]]

    def _get_track(self):
        t = self.current()
        if not t:
            return {}
        return {"title": t["title"], "artist": t["artist"], "album": t.get("album", ""), "year": t.get("year") or 0,
                "thumb": t.get("thumb") or t.get("_cover", ""), "durationMs": t.get("durationMs", 0), "codec": t.get("codec", ""),
                "key": t["key"], "artistKey": t.get("artistKey", ""), "albumKey": t.get("albumKey", ""),
                "partFile": t.get("partFile", ""), "stemKey": t.get("stemKey", ""), "index": t.get("index") or 0}

    @Slot(result="QVariantMap")
    def currentTrack(self):
        t = self.current()
        return {k: v for k, v in t.items() if not k.startswith("_")} if t else {}

    upcoming = Property("QVariantList", _get_upcoming, notify=queueChanged)
    format = Property(str, lambda self: getattr(self, "_format", ""), notify=stateChanged)
    track = Property("QVariantMap", _get_track, notify=stateChanged)
    duration = Property(float, lambda self: self.engine.duration or (self._t("durationMs", 0) / 1000), notify=stateChanged)
    playing = Property(bool, lambda self: self.engine.playing, notify=stateChanged)
    buffering = Property(bool, lambda self: self._loading or self.engine.buffering, notify=stateChanged)
    position = Property(float, lambda self: self.engine.position() if self.engine.duration else self._pending_seek,
                        notify=stateChanged)

    @Slot(result=float)
    def now(self):
        """The position this frame (for the seek bar)."""
        return self.engine.position() if self.engine.duration else self._pending_seek

    def _get_envelope(self):
        """0..1 per waveform bucket for the current mix: stems weighted by their faders, scaled to
        the full mix's range so muting a stem still shows as quieter bars (as on the phone)."""
        w = self._wave_raw
        if not w:
            return []
        if "stems" in w:
            stems = w["stems"]
            any_solo = any(self.mix.get(n, {}).get("solo") for n in self.names)

            def gain(n):
                s = self.mix.get(n, {})
                return 0.0 if s.get("muted") or (any_solo and not s.get("solo")) else stem_taper(s.get("level", UNITY))
            return _shape(_mix_db(stems, {n: gain(n) for n in stems}), _mix_db(stems, None))
        return _shape(w["db"], w["db"])

    envelope = Property("QVariantList", _get_envelope, notify=envelopeChanged)


def _mix_db(stems, gains):
    n = max(len(a) for a in stems.values())
    out = []
    for i in range(n):
        e = sum(((a[i] if i < len(a) else 0.0) * (gains[name] if gains else 1.0)) ** 2 for name, a in stems.items())
        out.append(20 * math.log10(math.sqrt(e) + 1e-6))
    return out


def _shape(db, ref):
    """Loudness to bar height, relative to the song itself: its loudest moments (99th percentile)
    fill the bar and its quiet tenth sits near the bottom, over 6 to 24 dB, with a curve that pulls
    the middle down. Modern masters barely move in absolute terms, so an absolute scale draws
    nearly every song as a solid block."""
    audible = sorted(v for v in ref if v > -60) or sorted(ref)
    if not audible:
        return [0.28] * len(db)

    def pct(p):
        return audible[min(int(p * len(audible)), len(audible) - 1)]
    hi = pct(0.99)
    span = min(24.0, max(6.0, hi - pct(0.10)))
    lo = hi - span
    return [0.05 + 0.95 * min(1.0, max(0.0, (v - lo) / span)) ** 1.8 for v in db]
