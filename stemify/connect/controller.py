"""Controlling another device from Stemify Mobile: the device list, the connection, and
swapping the screens' player, engine and EQ for remote stand-ins while it lasts."""

from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot
from PySide6.QtNetwork import QTcpSocket

from . import bt
from .link import Link
from .protocol import QUEUE_BEHIND, VERSION, track_to_wire
from .receiver import clean_mix
from .remote import RemoteEngine, RemoteEq, RemotePlayer, RemoteSession

CONNECT_TIMEOUT_MS = 12_000
TRANSFER_MAX = 2000
APP_LABELS = {"android": "Phone", "mobile": "Stemify Mobile", "player": "Stemify Player"}


class Nearby(QObject):
    devicesChanged = Signal()
    statusChanged = Signal()
    localChanged = Signal()
    hud = Signal(str, str)

    def __init__(self, cfg, identity, beacon, ctx, local, parent=None):
        """local: {"player", "engine", "eq"}, the objects the screens normally see."""
        super().__init__(parent)
        self.cfg, self.identity, self.beacon, self.ctx, self.local = cfg, identity, beacon, ctx, local
        self.scanner = bt.BtScanner(identity, self)
        self.scanner.found.connect(self._bt_found)
        self.scanner.finished.connect(self._bt_done)
        self._bt = {}  # id -> device, from the last Bluetooth scan
        self._bt_scanning = False
        self._scan_refs = 0
        beacon.devicesChanged.connect(self.devicesChanged)
        self.target = None
        self.status = ""  # "" | connecting | waiting | connected
        self.caps = []
        self.link = self.session = None
        self.remote = None  # (player, engine, eq)
        self._sock = None
        self._timeout = QTimer(self, singleShot=True, interval=CONNECT_TIMEOUT_MS)
        self._timeout.timeout.connect(lambda: self._fail(f"Couldn't reach {self._target_name()}"))
        self._reprobe = QTimer(self, interval=4000)
        self._reprobe.timeout.connect(self.beacon.probe)
        local["player"].changed.connect(self.localChanged)

    # ---- the list ------------------------------------------------------------------------------
    def _merged(self):
        out = {d["id"]: dict(d) for d in self._bt.values()}
        out.update({d["id"]: dict(d) for d in self.beacon.devices.values()})  # Wi-Fi wins
        if self.target and self.target["id"] not in out:
            out[self.target["id"]] = dict(self.target)
        return out

    def _get_devices(self):
        cur = self.target["id"] if self.target else ""
        rows = []
        for d in sorted(self._merged().values(), key=lambda d: d["name"].lower()):
            rows.append({"id": d["id"], "name": d["name"], "app": d.get("app", ""), "appLabel": APP_LABELS.get(d.get("app"), "Stemify"),
                         "transport": d["transport"], "current": d["id"] == cur,
                         "state": self.status if d["id"] == cur else ""})
        return rows

    @Slot(bool)
    def scan(self, on):
        """The picker is open (on) or closed: look for devices meanwhile."""
        self._scan_refs = max(0, self._scan_refs + (1 if on else -1))
        if self._scan_refs and not self._reprobe.isActive():
            self.beacon.probe()
            self._reprobe.start()
            if self.cfg.get("connectBluetooth", True) and not self._bt_scanning:
                self._bt_scanning = True
                self.scanner.scan()
                self.statusChanged.emit()
        elif not self._scan_refs:
            self._reprobe.stop()

    def _bt_found(self, d):
        if d["id"] != self.identity["id"]:
            self._bt[d["id"]] = d
            self.devicesChanged.emit()

    def _bt_done(self):
        self._bt_scanning = False
        self.statusChanged.emit()

    # ---- connecting ----------------------------------------------------------------------------
    def _target_name(self):
        return self.target["name"] if self.target else "the device"

    @Slot(str)
    def connectTo(self, did):
        d = self._merged().get(did)
        if not d or (self.target and self.target["id"] == did and self.status):
            return
        self.leave()
        self.target = d
        self._set_status("connecting")
        self._timeout.start()
        if d["transport"] == "wifi":
            sock = self._sock = QTcpSocket()
            sock.connected.connect(lambda: self._open(sock, "wifi"))
            sock.errorOccurred.connect(lambda *_: sock is self._sock and self._fail(f"Couldn't reach {self._target_name()}"))
            sock.connectToHost(d["host"], int(d["port"]))
        else:
            sock = self._sock = bt.new_socket()
            sock.connected.connect(lambda: self._open(sock, "bt"))
            sock.errorOccurred.connect(lambda *_: sock is self._sock and self._fail(f"Couldn't reach {self._target_name()} over Bluetooth"))
            bt.connect_socket(sock, d["address"])

    def _open(self, sock, transport):
        if sock is not self._sock:
            return
        try:
            sock.errorOccurred.disconnect()
        except (RuntimeError, TypeError):
            pass
        self._sock = None
        self.link = Link(sock, transport, ping=True, parent=self)
        self.link.message.connect(self._on_message)
        self.link.closed.connect(self._on_closed)
        self.link.send(dict(self.identity, t="hello", v=VERSION))

    def _on_message(self, m):
        t = m.get("t")
        if t == "welcome" and self.status != "connected":
            self._timeout.stop()
            self.caps = list(m.get("caps") or [])
            self.target = dict(self.target, name=str(m.get("name") or self._target_name()), app=str(m.get("app") or ""))
            self._go_remote()
        elif t == "pending":
            self._timeout.stop()
            self._set_status("waiting")
        elif t == "denied":
            self._fail(f"{self._target_name()} didn't allow it")
        elif t == "bye":
            self._fail(m.get("reason") or f"{self._target_name()} ended the connection")
        elif self.remote:
            player, engine, eq = self.remote
            if t == "state":
                player.on_state(m)
            elif t == "queue":
                player.on_queue(m)
            elif t == "wave":
                player.on_wave(m)
            elif t == "levels":
                engine.on_levels(m)
            elif t == "eq" and eq is not None:
                eq.on_eq(m)
            elif t == "toast":
                self.hud.emit(str(m.get("text") or ""), "warning")

    def _go_remote(self):
        self.session = RemoteSession(self.link, self)
        player = RemotePlayer(self.session, self)
        engine = RemoteEngine(player, self.session, self)
        eq = RemoteEq(self.session) if "eq" in self.caps else None
        self.remote = (player, engine, eq)
        lp = self.local["player"]
        if lp.engine.playing:
            lp.toggle()  # one device plays at a time
        self.ctx.setContextProperty("player", player)
        self.ctx.setContextProperty("engine", engine)
        if eq is not None:
            self.ctx.setContextProperty("eq", eq)
        self.link.send({"t": "meters", "on": True})
        self._set_status("connected")

    def _go_local(self):
        if self.remote is None:
            return
        self.ctx.setContextProperty("player", self.local["player"])
        self.ctx.setContextProperty("engine", self.local["engine"])
        self.ctx.setContextProperty("eq", self.local["eq"])
        for o in self.remote:
            if o is not None:
                o.deleteLater()
        self.remote = None
        self.session = None

    def _on_closed(self):
        if self.status == "connected":
            self.hud.emit(f"Disconnected from {self._target_name()}", "warning")
        self._reset()

    def _fail(self, why):
        if self.target is None:
            return
        self.hud.emit(why, "warning")
        self.leave()

    @Slot()
    def leave(self):
        if self.link is not None:
            link, self.link = self.link, None
            try:
                link.closed.disconnect(self._on_closed)
            except (RuntimeError, TypeError):
                pass
            link.close("Controller left")
        if self._sock is not None:
            self._sock.abort()
            self._sock.deleteLater()
            self._sock = None
        self._reset()

    def _reset(self):
        self._timeout.stop()
        self._go_local()
        self.link = None
        self.target = None
        self.caps = []
        self._set_status("")
        self.devicesChanged.emit()

    def _set_status(self, s):
        self.status = s
        self.statusChanged.emit()
        self.localChanged.emit()
        self.devicesChanged.emit()

    # ---- moving what's playing here over there -------------------------------------------------
    def _get_can_transfer(self):
        return self.status == "connected" and self.local["player"].current() is not None

    @Slot()
    def transfer(self):
        """Plays this computer's queue on the device being controlled, from where it is now."""
        lp = self.local["player"]
        if not self.session or lp.current() is None:
            return
        lo = max(0, lp.index - QUEUE_BEHIND)
        tracks = lp.queue[lo:lo + TRANSFER_MAX]
        pos = lp.engine.position() if lp.engine.duration else lp._pending_seek
        self.session.cmd("playTracks", tracks=[track_to_wire(t) for t in tracks], start=lp.index - lo, shuffle=False,
                         positionMs=int(pos * 1000), mix=clean_mix(lp.mix))
        self.hud.emit(f"Playing on {self._target_name()}", "airplay")

    def _get_local_title(self):
        t = self.local["player"].current()
        return t["title"] if t else ""

    devices = Property("QVariantList", _get_devices, notify=devicesChanged)
    active = Property(bool, lambda self: self.status == "connected", notify=statusChanged)
    busy = Property(bool, lambda self: self.status in ("connecting", "waiting"), notify=statusChanged)
    state = Property(str, lambda self: self.status, notify=statusChanged)
    targetName = Property(str, lambda self: self.target["name"] if self.target else "", notify=statusChanged)
    targetApp = Property(str, lambda self: (self.target or {}).get("app", ""), notify=statusChanged)
    hasEq = Property(bool, lambda self: "eq" in self.caps, notify=statusChanged)
    scanning = Property(bool, lambda self: self._bt_scanning or self._reprobe.isActive(), notify=statusChanged)
    canTransfer = Property(bool, _get_can_transfer, notify=localChanged)
    localTitle = Property(str, _get_local_title, notify=localChanged)
    thisName = Property(str, lambda self: self.identity["name"], constant=True)

    def shutdown(self):
        self.leave()
