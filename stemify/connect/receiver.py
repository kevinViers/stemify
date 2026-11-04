"""The receiving end of Stemify Connect: lets nearby controllers drive this app's player.

Wi-Fi controllers are approved once on this device (and remembered); Bluetooth ones are already
paired, so they're trusted. Works with Stemify Player's Player and Stemify Mobile's MobilePlayer."""

import math
import random

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot
from PySide6.QtNetwork import QHostAddress, QTcpServer

from ..player.player import UNITY
from .bt import BtServer
from .discovery import Beacon
from .link import Link
from .protocol import QUEUE_AHEAD, QUEUE_BEHIND, TCP_PORTS, VERSION, track_from_wire, track_to_wire

HELLO_TIMEOUT_MS = 10_000
FLUSH_MS = 60
HEARTBEAT_MS = 2000
METER_MS = 50
QUEUE_OPS = {"playTracks", "playNext", "addToQueue", "removeAt", "move", "clearUpNext", "toggleShuffle", "skipTo"}


class Session:
    def __init__(self, link):
        self.link = link
        self.peer = {}
        self.approved = False
        self.meters = False
        self.ack = 0


def clean_mix(mix, names=None):
    out = {}
    for n, s in (mix or {}).items():
        if not isinstance(s, dict) or (names is not None and n not in names):
            continue
        out[str(n)] = {"level": max(0.0, min(1.0, float(s.get("level", UNITY)))),
                       "muted": bool(s.get("muted")), "solo": bool(s.get("solo"))}
    return out


class Receiver(QObject):
    """Owns the listening sockets and the beacon; the app shows its approval prompts."""

    approvalsChanged = Signal()
    controllersChanged = Signal()
    settingsChanged = Signal()

    def __init__(self, cfg, identity, player, engine, eq=None, backend=None, save=None, beacon=None, parent=None):
        super().__init__(parent)
        self.cfg, self.identity = cfg, identity
        self.player, self.engine, self.eq, self.backend = player, engine, eq, backend
        self._save_cfg = save or (lambda: None)
        self.sessions = []
        self.beacon = beacon or Beacon(identity, self)
        self.tcp = QTcpServer(self)
        self.tcp.newConnection.connect(self._accept_tcp)
        self.bt = BtServer(self)
        self.bt.incoming.connect(lambda s: self._add(Link(s, "bt")))
        self._dirty = set()
        self._force = set()
        self._last_queue = None
        self._last_wave = None
        self._flusher = QTimer(self, singleShot=True, interval=FLUSH_MS)
        self._flusher.timeout.connect(self._flush)
        self._heartbeat = QTimer(self, interval=HEARTBEAT_MS)
        self._heartbeat.timeout.connect(lambda: self.engine.playing and self._mark("state"))
        self._heartbeat.start()
        self._meters = QTimer(self, interval=METER_MS)
        self._meters.timeout.connect(self._send_levels)

        p = player
        p.changed.connect(lambda: self._mark("state", "queue"))
        p.mixChanged.connect(lambda: self._mark("state"))
        p.waveChanged.connect(lambda: self._mark("wave"))
        p.crossfadeChanged.connect(lambda: self._mark("state"))
        if hasattr(p, "queueChanged"):
            p.queueChanged.connect(lambda: self._mark("queue"))
        p.toast.connect(lambda text: self._broadcast({"t": "toast", "text": text}))
        engine.volumeChanged.connect(lambda: self._mark("state"))
        engine.playingChanged.connect(lambda: self._mark("state"))
        if eq is not None:
            eq.changed.connect(lambda: self._mark("eq"))
            eq.deviceChanged.connect(lambda: self._mark("eq"))
        if backend is not None:
            backend.settingsChanged.connect(lambda: self._mark("state"))
        self.apply_settings()

    # ---- settings ------------------------------------------------------------------------------
    @property
    def enabled(self):
        return self.cfg.get("connectEnabled", True)

    @property
    def bluetooth(self):
        return self.cfg.get("connectBluetooth", True)

    def apply_settings(self):
        if self.enabled and not self.tcp.isListening():
            for port in TCP_PORTS + (0,):
                if self.tcp.listen(QHostAddress(QHostAddress.SpecialAddress.Any), port):
                    self.beacon.set_port(self.tcp.serverPort())
                    break
        elif not self.enabled and self.tcp.isListening():
            self.tcp.close()
            self.beacon.set_port(0)
            for s in list(self.sessions):
                s.link.close("Nearby control was turned off")
        if self.enabled and self.bluetooth:
            self.bt.start()
        else:
            self.bt.stop()
            for s in [s for s in self.sessions if s.link.transport == "bt"]:
                s.link.close("Bluetooth control was turned off")
        self.settingsChanged.emit()

    @Slot(bool)
    def setEnabled(self, on):
        self.cfg["connectEnabled"] = bool(on)
        self._save_cfg()
        self.apply_settings()

    @Slot(bool)
    def setBluetooth(self, on):
        self.cfg["connectBluetooth"] = bool(on)
        self._save_cfg()
        self.apply_settings()

    def _allowed(self):
        return self.cfg.setdefault("connectAllowed", {})

    @Slot(str)
    def forget(self, peer_id):
        """Takes back a device's permission, and drops it if it's connected over Wi-Fi."""
        if self._allowed().pop(peer_id, None) is not None:
            self._save_cfg()
        for s in [s for s in self.sessions if s.peer.get("id") == peer_id and s.link.transport == "wifi"]:
            s.link.close("Permission withdrawn")
        self.settingsChanged.emit()

    def _get_allowed(self):
        return [{"id": k, "name": v} for k, v in sorted(self._allowed().items(), key=lambda kv: kv[1].lower())]

    def _get_pending(self):
        seen, out = set(), []
        for s in self.sessions:
            pid = s.peer.get("id")
            if s.peer and not s.approved and pid not in seen:
                seen.add(pid)
                out.append({"id": pid, "name": s.peer.get("name", "A device"), "app": s.peer.get("app", "")})
        return out

    def _get_controllers(self):
        return [{"id": s.peer.get("id"), "name": s.peer.get("name", ""), "transport": s.link.transport}
                for s in self.sessions if s.approved]

    allowed = Property("QVariantList", _get_allowed, notify=settingsChanged)
    pending = Property("QVariantList", _get_pending, notify=approvalsChanged)
    controllers = Property("QVariantList", _get_controllers, notify=controllersChanged)
    enabledOn = Property(bool, lambda self: bool(self.enabled), notify=settingsChanged)
    bluetoothOn = Property(bool, lambda self: bool(self.bluetooth), notify=settingsChanged)
    name = Property(str, lambda self: self.identity["name"], constant=True)

    @Slot(str, bool)
    def approve(self, peer_id, remember):
        name = None
        for s in self.sessions:
            if s.peer.get("id") == peer_id and not s.approved:
                name = s.peer.get("name", "")
                self._welcome(s)
        if remember and name is not None:
            self._allowed()[peer_id] = name
            self._save_cfg()
            self.settingsChanged.emit()
        self.approvalsChanged.emit()

    @Slot(str)
    def deny(self, peer_id):
        for s in [s for s in self.sessions if s.peer.get("id") == peer_id and not s.approved]:
            s.link.send({"t": "denied"})
            s.link.close()
        self.approvalsChanged.emit()

    # ---- connections ---------------------------------------------------------------------------
    def _accept_tcp(self):
        while self.tcp.hasPendingConnections():
            sock = self.tcp.nextPendingConnection()
            if sock is not None:
                self._add(Link(sock, "wifi"))

    def _add(self, link):
        s = Session(link)
        self.sessions.append(s)
        link.message.connect(lambda m, s=s: self._on_message(s, m))
        link.closed.connect(lambda s=s: self._drop(s))
        QTimer.singleShot(HELLO_TIMEOUT_MS, link, lambda: link.is_open and not s.peer and link.close())

    def _drop(self, s):
        if s in self.sessions:
            self.sessions.remove(s)
            if not s.approved and s.peer:
                self.approvalsChanged.emit()
            if s.approved:
                self.controllersChanged.emit()
            self._update_meters()

    def _identity(self):
        caps = ["crossfade", "keepMix"] + (["eq"] if self.eq is not None else [])
        return {"v": VERSION, "id": self.identity["id"], "name": self.identity["name"], "app": self.identity["app"], "caps": caps}

    def _on_message(self, s, m):
        t = m.get("t")
        if not s.peer:
            if t != "hello" or not m.get("id"):
                s.link.close()
                return
            s.peer = {"id": str(m["id"]), "name": str(m.get("name") or "A device")[:80], "app": str(m.get("app") or "")}
            trusted = s.link.transport == "bt" or s.peer["id"] in self._allowed()
            if m.get("probe"):
                s.link.send(dict(self._identity(), t="welcome" if trusted else "pending"))
                s.link.close()
            elif trusted:
                self._welcome(s)
            else:
                s.link.send(dict(self._identity(), t="pending"))
                self.approvalsChanged.emit()
            return
        if not s.approved:
            return
        if t == "cmd":
            seq = int(m.get("seq") or 0)
            try:
                self._apply(m.get("op"), m)
            except (KeyError, TypeError, ValueError, IndexError) as e:
                s.link.send({"t": "toast", "text": f"Couldn't do that: {e}"})
            s.ack = max(s.ack, seq)
            op = m.get("op")
            self._force.add("queue" if op in QUEUE_OPS else "eq" if op == "eqState" else "state")
            self._mark("state")
        elif t == "meters":
            s.meters = bool(m.get("on"))
            self._update_meters()

    def _welcome(self, s):
        s.approved = True
        s.link.send(dict(self._identity(), t="welcome"))
        s.link.send(self._state(s.ack))
        s.link.send(self._queue(s.ack))
        s.link.send(self._wave())
        if self.eq is not None:
            s.link.send(self._eq(s.ack))
        self.controllersChanged.emit()

    def _broadcast(self, msg):
        for s in self.sessions:
            if s.approved:
                s.link.send(msg)

    # ---- outgoing state ------------------------------------------------------------------------
    def _mark(self, *what):
        if not any(s.approved for s in self.sessions):
            return
        self._dirty.update(what)
        if not self._flusher.isActive():
            self._flusher.start()

    def _flush(self):
        dirty, self._dirty = self._dirty | self._force, set()
        force, self._force = self._force, set()
        live = [s for s in self.sessions if s.approved]
        if not live:
            return
        if "queue" in dirty:
            sig = self._queue_sig()
            if sig != self._last_queue or "queue" in force:
                self._last_queue = sig
                for s in live:
                    s.link.send(self._queue(s.ack))
        if "wave" in dirty and self.player._wave_raw is not self._last_wave:
            self._broadcast(self._wave())
        if "eq" in dirty and self.eq is not None:
            for s in live:
                s.link.send(self._eq(s.ack))
        if "state" in dirty or "queue" in dirty:
            for s in live:
                s.link.send(self._state(s.ack))

    def _state(self, ack):
        p, e, c = self.player, self.engine, self.cfg
        cur = p.current()
        pos = e.position() if e.duration else p._pending_seek
        dur = e.duration or ((cur or {}).get("durationMs", 0) / 1000)
        fmt = getattr(p, "_format", "") or ("FLAC" if p.source_kind == "stems" else "")
        return {"t": "state", "ack": ack, "index": p.index, "track": track_to_wire(cur) if cur else None,
                "playing": bool(e.playing), "buffering": bool(p._loading or e.buffering),
                "positionMs": int(max(0.0, pos) * 1000), "durationMs": int(dur * 1000),
                "shuffle": bool(p.shuffle), "repeat": p.repeat, "source": p.source_kind or "none",
                "stems": list(p.names) if cur else [], "mix": clean_mix(p.mix), "master": round(float(e.volume), 4),
                "keepMix": bool(c.get("keepMix")), "format": fmt,
                "crossfade": {"mode": c.get("crossfade", "off"), "seconds": int(c.get("crossfadeSeconds", 6)),
                              "albums": bool(c.get("crossfadeAlbums"))}}

    def _window(self):
        p = self.player
        lo = max(0, p.index - QUEUE_BEHIND)
        return lo, p.queue[lo:max(p.index, 0) + 1 + QUEUE_AHEAD]

    def _queue_sig(self):
        lo, items = self._window()
        return lo, len(self.player.queue), self.player.index, tuple(id(t) for t in items)

    def _queue(self, ack):
        lo, items = self._window()
        p = self.player
        return {"t": "queue", "ack": ack, "offset": lo, "total": len(p.queue), "index": p.index,
                "items": [{"uid": id(t) & 0x7FFFFFFFFFFF, "track": track_to_wire(t)} for t in items]}

    def _wave(self):
        w = self._last_wave = self.player._wave_raw
        cur = self.player.current()
        msg = {"t": "wave", "key": (cur or {}).get("key", ""), "stems": None, "db": None}
        if w and "stems" in w:
            msg["stems"] = {n: [round(float(v), 3) for v in a] for n, a in w["stems"].items()}
        elif w and "db" in w:
            msg["db"] = [round(float(v), 2) for v in w["db"]]
        return msg

    def _eq(self, ack):
        return {"t": "eq", "ack": ack, "state": self.eq.state, "device": self.eq.device}

    def _update_meters(self):
        want = any(s.approved and s.meters for s in self.sessions)
        if want and not self._meters.isActive():
            self._meters.start()
        elif not want:
            self._meters.stop()

    def _send_levels(self):
        if not self.engine.playing:
            return
        v = [round(float(x), 3) if math.isfinite(x) else 0.0 for x in self.engine.levels()]
        for s in self.sessions:
            if s.approved and s.meters:
                s.link.send({"t": "levels", "v": v})

    # ---- commands ------------------------------------------------------------------------------
    def _stem(self, m):
        return self.player.names.index(str(m["stem"]))

    def _apply(self, op, m):
        p, e = self.player, self.engine
        if op == "play":
            if not e.playing:
                p.toggle()
        elif op == "pause":
            if e.playing:
                p.toggle()
        elif op in ("toggle", "next", "previous", "toggleShuffle", "cycleRepeat"):
            getattr(p, op)()
        elif op == "seek":
            p.seek(float(m["ms"]) / 1000)
            self._mark("state")
        elif op == "skipBy":
            p.skipBy(float(m["ms"]) / 1000)
        elif op == "skipTo":
            p.skipTo(int(m["i"]))
        elif op == "playTracks":
            self._play_tracks(m)
        elif op == "playNext":
            p.play_next([track_from_wire(t) for t in m["tracks"]])
        elif op == "addToQueue":
            p.add_to_queue([track_from_wire(t) for t in m["tracks"]])
        elif op == "removeAt":
            p.removeAt(int(m["i"]))
        elif op == "move":
            self._move(int(m["from"]), int(m["to"]))
        elif op == "clearUpNext":
            del p.queue[p.index + 1:]
            if p._unshuffled is not None:
                p._unshuffled, p.shuffle = None, False
            p._queue_changed()
        elif op == "setRepeat":
            if m["mode"] in ("off", "all", "one"):
                p.repeat = m["mode"]
                p.changed.emit()
                p._save_soon()
        elif op == "setLevel":
            p.setLevel(self._stem(m), float(m["level"]))
        elif op == "toggleMute":
            p.toggleMute(self._stem(m))
        elif op == "toggleSolo":
            p.toggleSolo(self._stem(m))
        elif op == "setMix":
            p.mix = clean_mix(m["mix"])
            p._push_mix()
        elif op == "setMaster":
            e.setVolume(max(0.0, min(1.0, float(m["v"]))))
        elif op == "setKeepMix":
            if self.backend is not None:
                self.backend.set("keepMix", bool(m["on"]))
            p.setKeepMix(bool(m["on"]))
        elif op == "setCrossfade":
            mode, secs, albums = str(m.get("mode", "off")), int(m.get("seconds") or 0), bool(m.get("albums"))
            if self.backend is not None:
                if secs > 0:
                    self.backend.set("crossfadeSeconds", max(1, min(12, secs)))
                self.backend.set("crossfade", mode)
                self.backend.set("crossfadeAlbums", albums)
            else:
                p.setCrossfade(mode, secs)
                p.setCrossfadeAlbums(albums)
        elif op == "eqState":
            if self.eq is not None:
                self.eq.replace_state(m["state"])
        else:
            raise ValueError(f"unknown op {op!r}")

    def _play_tracks(self, m):
        p = self.player
        tracks = [track_from_wire(t) for t in m.get("tracks") or []]
        if not tracks:
            return
        shuffle = bool(m.get("shuffle"))
        start = int(m.get("start") or 0)
        if start < 0 or start >= len(tracks):
            start = random.randrange(len(tracks)) if shuffle else 0
        if shuffle:
            p._unshuffled = list(tracks)
            first = tracks.pop(start)
            random.shuffle(tracks)
            tracks.insert(0, first)
            start = 0
        else:
            p._unshuffled = None
        p.shuffle = shuffle
        p.queue = tracks
        mix = m.get("mix")
        if not p.cfg.get("keepMix") and mix is None:
            p.mix = {}
        p._load(start, play=not m.get("paused"), start=max(0.0, float(m.get("positionMs") or 0) / 1000))
        if mix is not None:
            p.mix = clean_mix(mix)  # after _load, which starts a song at 0 with a fresh mix
            p.mixChanged.emit()
        p._queue_changed()

    def _move(self, a, b):
        p = self.player
        if hasattr(p, "moveItem"):
            p.moveItem(a, b)
            return
        q = p.queue
        if not (0 <= a < len(q) and 0 <= b < len(q)) or a == b:
            return
        cur = p.current()
        q.insert(b, q.pop(a))
        if cur is not None:
            p.index = next(i for i, t in enumerate(q) if t is cur)
        p._queue_changed()

    def shutdown(self):
        for s in list(self.sessions):
            s.link.close("Closing")
        self.bt.stop()
        self.tcp.close()
