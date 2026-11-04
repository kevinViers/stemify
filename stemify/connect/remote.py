"""Stand-ins for the local player, engine and EQ while Stemify Mobile controls another device.

They have the same QML interface as MobilePlayer, PlayerEngine and EqController, so every
screen works unchanged; they draw from the receiver's snapshots and send commands back. Edits
show at once and stale snapshots are ignored until the receiver has caught up (docs/CONNECT.md).
"""

import math
import time

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

from ..mobile.eq import EqController, _default_state
from ..mobile.playback import QUEUE_FIELDS, _mix_db, _shape
from ..player.player import COLORS, PRESETS, UNITY, _normalized, _setting, stem_taper
from .protocol import track_from_wire, track_to_wire

MIX_OPS = {"setLevel", "toggleMute", "toggleSolo", "setMix"}
QUEUE_OPS = {"playTracks", "playNext", "addToQueue", "removeAt", "move", "clearUpNext", "toggleShuffle", "skipTo"}
GROUPS = {"setMaster": "master", "seek": "pos", "skipBy": "pos", "toggle": "play", "play": "play", "pause": "play",
          "setKeepMix": "settings", "setCrossfade": "settings", "cycleRepeat": "repeat", "setRepeat": "repeat"}


def group_of(op):
    if op in MIX_OPS:
        return "mix"
    if op in QUEUE_OPS:
        return "queue"
    return GROUPS.get(op, "state")


class RemoteSession(QObject):
    """Sends commands with sequence numbers and remembers, per kind of edit, the last one sent."""

    def __init__(self, link, parent=None):
        super().__init__(parent)
        self.link = link
        self.seq = 0
        self.pending = {}  # group -> seq of the last edit sent

    def cmd(self, op, **args):
        self.seq += 1
        self.pending[group_of(op)] = self.seq
        self.link.send(dict(args, t="cmd", op=op, seq=self.seq))
        return self.seq

    def fresh(self, group, ack):
        """Whether a snapshot with this ack already includes our last edit of this kind."""
        return ack >= self.pending.get(group, 0)


class RemotePlayer(QObject):
    changed = Signal()
    mixChanged = Signal()
    crossfadeChanged = Signal()
    waveChanged = Signal()
    toast = Signal(str)
    queueChanged = Signal()
    stateChanged = Signal()
    envelopeChanged = Signal()

    def __init__(self, session, parent=None):
        super().__init__(parent)
        self.s = session
        self.st = {"index": -1, "track": None, "playing": False, "buffering": False, "positionMs": 0, "durationMs": 0,
                   "shuffle": False, "repeat": "off", "source": "none", "stems": [], "mix": {}, "master": 0.85,
                   "keepMix": True, "format": "", "crossfade": {"mode": "off", "seconds": 6, "albums": False}}
        self.items, self.offset, self.total = [], 0, 0
        self._at = time.monotonic()
        self._wave = None
        self._track_cache = None

    # ---- incoming ------------------------------------------------------------------------------
    def on_state(self, m):
        ack = int(m.get("ack") or 0)
        st = self.st
        before_track = (st.get("track") or {}).get("ratingKey"), st.get("index")
        for k in ("track", "index", "durationMs", "shuffle", "source", "stems", "format", "buffering"):
            if k in m and (k not in ("index", "shuffle") or self.s.fresh("queue", ack)):
                st[k] = m[k]
        if self.s.fresh("play", ack):
            st["playing"] = bool(m.get("playing"))
        if self.s.fresh("pos", ack) and self.s.fresh("play", ack):
            st["positionMs"] = int(m.get("positionMs") or 0)
            self._at = time.monotonic()
        if self.s.fresh("repeat", ack):
            st["repeat"] = m.get("repeat", "off")
        if self.s.fresh("mix", ack):
            st["mix"] = m.get("mix") or {}
        if self.s.fresh("master", ack):
            st["master"] = float(m.get("master", st["master"]))
        if self.s.fresh("settings", ack):
            st["keepMix"] = bool(m.get("keepMix"))
            st["crossfade"] = m.get("crossfade") or st["crossfade"]
        self._track_cache = None
        if before_track != ((st.get("track") or {}).get("ratingKey"), st.get("index")):
            self.queueChanged.emit()
        self.changed.emit()
        self.stateChanged.emit()
        self.mixChanged.emit()
        self.envelopeChanged.emit()
        self.crossfadeChanged.emit()

    def on_queue(self, m):
        if not self.s.fresh("queue", int(m.get("ack") or 0)):
            return
        self.items = [{"uid": it.get("uid", i), "track": track_from_wire(it.get("track"))} for i, it in enumerate(m.get("items") or [])]
        self.offset, self.total = int(m.get("offset") or 0), int(m.get("total") or 0)
        self.st["index"] = int(m.get("index", self.st["index"]))
        self.queueChanged.emit()
        self.changed.emit()

    def on_wave(self, m):
        self._wave = {"stems": m["stems"]} if m.get("stems") else {"db": m["db"]} if m.get("db") else None
        self.waveChanged.emit()
        self.envelopeChanged.emit()

    # ---- helpers -------------------------------------------------------------------------------
    @property
    def names(self):
        return list(self.st.get("stems") or [])

    @property
    def mix(self):
        return self.st["mix"]

    def _local(self, i):
        """Queue position (absolute) -> index into the window we hold."""
        return i - self.offset

    def _edit_mix(self, name, **kw):
        m = dict(self.st["mix"])
        m[name] = {**{"level": UNITY, "muted": False, "solo": False}, **m.get(name, {}), **kw}
        self.st["mix"] = m
        self.mixChanged.emit()
        self.envelopeChanged.emit()

    def _bump(self):
        self.changed.emit()
        self.stateChanged.emit()

    # ---- QML: transport ------------------------------------------------------------------------
    @Slot()
    def toggle(self):
        self.st["playing"] = not self.st["playing"]
        self.st["positionMs"] = int(self.now() * 1000)
        self._at = time.monotonic()
        self.s.cmd("toggle")
        self._bump()

    @Slot()
    def next(self):
        self.s.cmd("next")

    @Slot()
    def previous(self):
        self.s.cmd("previous")

    @Slot(float)
    def skipBy(self, seconds):
        to = self.now() + seconds
        if to < self.st["durationMs"] / 1000:
            self.st["positionMs"] = int(max(0.0, to) * 1000)
            self._at = time.monotonic()
        self.s.cmd("skipBy", ms=int(seconds * 1000))
        self._bump()

    @Slot(float)
    def seek(self, seconds):
        self.st["positionMs"] = int(max(0.0, seconds) * 1000)
        self._at = time.monotonic()
        self.s.cmd("seek", ms=int(seconds * 1000))
        self._bump()

    @Slot(int)
    def skipTo(self, i):
        self.s.cmd("skipTo", i=i + self.offset)

    @Slot()
    def toggleShuffle(self):
        self.st["shuffle"] = not self.st["shuffle"]
        self.s.cmd("toggleShuffle")
        self._bump()

    @Slot()
    def cycleRepeat(self):
        self.st["repeat"] = {"off": "all", "all": "one", "one": "off"}.get(self.st["repeat"], "off")
        self.s.cmd("cycleRepeat")
        self._bump()

    @Slot(result=float)
    def now(self):
        pos = self.st["positionMs"] / 1000
        if self.st["playing"] and not self.st["buffering"]:
            pos += time.monotonic() - self._at
        dur = self.st["durationMs"] / 1000
        return min(pos, dur) if dur > 0 else pos

    # ---- QML: queue ----------------------------------------------------------------------------
    @Slot("QVariantList", int, bool)
    def playTracks(self, tracks, start=0, shuffle=False):
        if tracks:
            self.s.cmd("playTracks", tracks=[track_to_wire(t) for t in tracks], start=-1 if shuffle and start <= 0 else start,
                       shuffle=bool(shuffle))

    def play_tracks(self, tracks, start=0, shuffle=False):
        self.playTracks(tracks, start, shuffle)

    @Slot("QVariantList")
    def playNextList(self, tracks):
        self.s.cmd("playNext", tracks=[track_to_wire(t) for t in tracks])

    @Slot("QVariantList")
    def addList(self, tracks):
        self.s.cmd("addToQueue", tracks=[track_to_wire(t) for t in tracks])

    @Slot(int, int)
    def moveItem(self, a, b):
        la, lb = self._local(a), self._local(b)
        if not (0 <= la < len(self.items) and 0 <= lb < len(self.items)) or a == b:
            return
        cur = self._local(self.st["index"])
        it = self.items.pop(la)
        self.items.insert(lb, it)
        if la == cur:
            cur = lb
        elif la < cur <= lb:
            cur -= 1
        elif lb <= cur < la:
            cur += 1
        self.st["index"] = cur + self.offset
        self.s.cmd("move", **{"from": a, "to": b})
        self.queueChanged.emit()

    @Slot(int)
    def removeAt(self, i):
        li, cur = self._local(i), self._local(self.st["index"])
        if 0 <= li < len(self.items) and li != cur:
            del self.items[li]
            if li < cur:
                self.st["index"] -= 1
            self.total -= 1
            self.queueChanged.emit()
        self.s.cmd("removeAt", i=i)

    @Slot()
    def clearUpNext(self):
        cur = self._local(self.st["index"])
        del self.items[cur + 1:]
        self.total = self.offset + len(self.items)
        self.s.cmd("clearUpNext")
        self.queueChanged.emit()

    @Slot()
    def clearQueue(self):
        self.clearUpNext()

    def _get_upcoming(self):
        cur = self._local(self.st["index"])
        return [{k: it["track"].get(k, "") for k in QUEUE_FIELDS} for it in self.items[cur + 1:]]

    def _get_queue_info(self):
        return {"index": self.st["index"], "count": self.total}

    @Slot(result="QVariantMap")
    def currentTrack(self):
        t = self.st.get("track")
        return track_from_wire(t) if t else {}

    def _get_track(self):
        if self._track_cache is None:
            w = self.st.get("track")
            if not w:
                self._track_cache = {}
            else:
                t = track_from_wire(w)
                self._track_cache = {k: t.get(k, "") for k in ("title", "artist", "album", "thumb", "codec", "key", "artistKey",
                                                               "albumKey", "partFile", "stemKey")} | {
                    "year": t.get("year") or 0, "durationMs": t.get("durationMs") or 0, "index": t.get("index") or 0}
        return self._track_cache

    # ---- QML: mix ------------------------------------------------------------------------------
    def _name(self, i):
        return self.names[i] if 0 <= i < len(self.names) else None

    @Slot(int, float)
    def setLevel(self, i, v):
        n = self._name(i)
        if n:
            v = max(0.0, min(1.0, v))
            self._edit_mix(n, level=v)
            self.s.cmd("setLevel", stem=n, level=round(v, 4))

    @Slot(int)
    def toggleMute(self, i):
        n = self._name(i)
        if n:
            self._edit_mix(n, muted=not _setting(self.mix, n)[1])
            self.s.cmd("toggleMute", stem=n)

    @Slot(int)
    def toggleSolo(self, i):
        n = self._name(i)
        if n:
            self._edit_mix(n, solo=not _setting(self.mix, n)[2])
            self.s.cmd("toggleSolo", stem=n)

    @Slot(int)
    def applyPreset(self, p):
        m = PRESETS[p][1](self.names)
        if m is not None:
            self.st["mix"] = {k: {"level": UNITY, "muted": False, "solo": False, **v} for k, v in m.items()}
            self.s.cmd("setMix", mix=self.st["mix"])
            self.mixChanged.emit()
            self.envelopeChanged.emit()

    @Slot()
    def toggleVocals(self):
        if "vocals" in self.names:
            self.applyPreset(0 if self._preset_index() == 1 else 1)

    def _preset_index(self):
        m = _normalized(self.mix)
        for i, (_, build) in enumerate(PRESETS):
            b = build(self.names)
            if b is not None and _normalized(b) == m:
                return i
        return -1

    def _get_stems(self):
        any_solo = any(_setting(self.mix, n)[2] for n in self.names)
        out = []
        for n in self.names:
            level, muted, solo = _setting(self.mix, n)
            out.append({"name": n, "label": "Original mix" if n == "mix" else n.capitalize(), "color": COLORS.get(n, "#8E8E93"),
                        "level": level, "muted": muted, "solo": solo, "audible": not muted and (solo or not any_solo),
                        "db": 20 * math.log10(max(1e-6, stem_taper(level)))})
        return out

    def _get_presets(self):
        cur = self._preset_index()
        stems = self.st["source"] == "stems"
        return [{"label": label, "usable": build(self.names) is not None and stems, "active": i == cur}
                for i, (label, build) in enumerate(PRESETS)]

    def _get_preset_label(self):
        if self.st["source"] != "stems":
            return ""
        i = self._preset_index()
        return PRESETS[i][0] if i >= 0 else "Custom Mix"

    def _get_envelope(self):
        w = self._wave
        if not w:
            return []
        if "stems" in w:
            stems = w["stems"]
            any_solo = any(_setting(self.mix, n)[2] for n in self.names)

            def gain(n):
                s = self.mix.get(n, {})
                return 0.0 if s.get("muted") or (any_solo and not s.get("solo")) else stem_taper(s.get("level", UNITY))
            return _shape(_mix_db(stems, {n: gain(n) for n in stems}), _mix_db(stems, None))
        return _shape(w["db"], w["db"])

    # ---- QML: settings -------------------------------------------------------------------------
    @Slot(bool)
    def setKeepMix(self, on):
        self.st["keepMix"] = bool(on)
        self.s.cmd("setKeepMix", on=bool(on))
        self.mixChanged.emit()

    @Slot(str, int)
    def setCrossfade(self, mode, seconds):
        cf = dict(self.st["crossfade"], mode=mode)
        if seconds > 0:
            cf["seconds"] = int(seconds)
        self.st["crossfade"] = cf
        self.s.cmd("setCrossfade", **cf)
        self.crossfadeChanged.emit()

    @Slot(bool)
    def setCrossfadeAlbums(self, on):
        self.st["crossfade"] = dict(self.st["crossfade"], albums=bool(on))
        self.s.cmd("setCrossfade", **self.st["crossfade"])
        self.crossfadeChanged.emit()

    @Slot()
    def replan(self):
        pass

    def _get_crossfade_label(self):
        cf = self.st["crossfade"]
        if cf.get("mode") == "fixed":
            return f"Fixed: {int(cf.get('seconds', 6))} s"
        return {"off": "Off (gapless)", "short": "Smart: Short", "medium": "Smart: Medium", "long": "Smart: Long"}.get(cf.get("mode"), "Off")

    track = Property("QVariantMap", _get_track, notify=stateChanged)
    upcoming = Property("QVariantList", _get_upcoming, notify=queueChanged)
    queueInfo = Property("QVariantMap", _get_queue_info, notify=changed)
    format = Property(str, lambda self: self.st.get("format") or "", notify=stateChanged)
    duration = Property(float, lambda self: self.st["durationMs"] / 1000, notify=stateChanged)
    playing = Property(bool, lambda self: bool(self.st["playing"]), notify=stateChanged)
    buffering = Property(bool, lambda self: bool(self.st["buffering"]), notify=stateChanged)
    position = Property(float, lambda self: self.now(), notify=stateChanged)
    sourceKind = Property(str, lambda self: "" if self.st["source"] == "none" else self.st["source"], notify=changed)
    stems = Property("QVariantList", _get_stems, notify=mixChanged)
    presets = Property("QVariantList", _get_presets, notify=mixChanged)
    presetLabel = Property(str, _get_preset_label, notify=mixChanged)
    shuffleOn = Property(bool, lambda self: bool(self.st["shuffle"]), notify=changed)
    repeatMode = Property(str, lambda self: self.st["repeat"], notify=changed)
    keepMix = Property(bool, lambda self: bool(self.st["keepMix"]), notify=mixChanged)
    envelope = Property("QVariantList", _get_envelope, notify=envelopeChanged)
    crossfade = Property(str, lambda self: self.st["crossfade"].get("mode", "off"), notify=crossfadeChanged)
    crossfadeSeconds = Property(int, lambda self: int(self.st["crossfade"].get("seconds", 6)), notify=crossfadeChanged)
    crossfadeAlbums = Property(bool, lambda self: bool(self.st["crossfade"].get("albums")), notify=crossfadeChanged)
    crossfadeLabel = Property(str, _get_crossfade_label, notify=crossfadeChanged)


class RemoteEngine(QObject):
    """The receiver's master volume and meters, where the screens expect the audio engine."""

    volumeChanged = Signal()
    playingChanged = Signal()

    def __init__(self, player, session, parent=None):
        super().__init__(parent)
        self.player, self.s = player, session
        self._levels, self._levels_at = [], 0.0
        player.stateChanged.connect(self.volumeChanged)
        player.stateChanged.connect(self.playingChanged)

    def on_levels(self, m):
        self._levels, self._levels_at = [float(v) for v in m.get("v") or []], time.monotonic()

    @Slot(float)
    def setVolume(self, v):
        v = max(0.0, min(1.0, v))
        self.player.st["master"] = v
        self.s.cmd("setMaster", v=round(v, 4))
        self.volumeChanged.emit()

    @Slot(result="QVariantList")
    def levels(self):
        if not self.player.st["playing"] or time.monotonic() - self._levels_at > 0.4:
            return []
        return self._levels

    volume = Property(float, lambda self: float(self.player.st["master"]), setVolume, notify=volumeChanged)
    playing = Property(bool, lambda self: bool(self.player.st["playing"]), notify=playingChanged)


class RemoteEq(EqController):
    """The receiver's EQ. Every edit goes back as the whole EQ (an eqState command), a moment
    after the last change, so a slider drag is a handful of messages rather than hundreds."""

    def __init__(self, session):
        QObject.__init__(self)  # not EqController's: there's no engine or file here
        self.s = session
        self.path = None
        self.state = _default_state()
        self.device = {"id": "other|", "kind": "other", "label": "Audio Output"}
        self._dirty = False
        self._sender = QTimer(self, singleShot=True, interval=60)
        self._sender.timeout.connect(self._send)

    def on_eq(self, m):
        if self._dirty or not self.s.fresh("eq", int(m.get("ack") or 0)):
            return
        self.state = m.get("state") or _default_state()
        dev = m.get("device") or self.device
        if dev != self.device:
            self.device = dev
            self.deviceChanged.emit()
        self.changed.emit()

    def refresh_device(self):
        pass

    def _publish(self):
        self.changed.emit()

    def _save_soon(self):
        self._dirty = True
        self._sender.start()

    def _send(self):
        self._dirty = False
        self.s.cmd("eqState", state=self.state)

    def _save(self):
        pass

    def flush(self):
        if self._sender.isActive():
            self._sender.stop()
            self._send()
