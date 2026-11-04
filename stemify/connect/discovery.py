"""Finding receivers on the local network: a UDP broadcast beacon (docs/CONNECT.md)."""

import json
import random
import time

from PySide6.QtCore import QObject, QTimer, Signal
from PySide6.QtNetwork import QAbstractSocket, QHostAddress, QNetworkInterface, QUdpSocket

from .protocol import BEACON_EVERY_MS, BEACON_PORT, DEVICE_TTL_S, VERSION


def _broadcast_targets():
    out = {QHostAddress(QHostAddress.SpecialAddress.Broadcast).toString()}
    for iface in QNetworkInterface.allInterfaces():
        f = iface.flags()
        if not (f & QNetworkInterface.InterfaceFlag.IsUp) or not (f & QNetworkInterface.InterfaceFlag.CanBroadcast):
            continue
        for e in iface.addressEntries():
            b = e.broadcast()
            if not b.isNull() and b.protocol() == QAbstractSocket.NetworkLayerProtocol.IPv4Protocol:
                out.add(b.toString())
    return [QHostAddress(a) for a in sorted(out)]


class Beacon(QObject):
    """Announces this receiver (once it has a port) and keeps the list of others heard."""

    devicesChanged = Signal()

    def __init__(self, identity, parent=None):
        super().__init__(parent)
        self.identity = identity  # {"id", "name", "app"}
        self.port = 0
        self.devices = {}  # id -> {id, name, app, host, port, transport, seen}
        self.sock = QUdpSocket(self)
        mode = QUdpSocket.BindFlag.ShareAddress | QUdpSocket.BindFlag.ReuseAddressHint
        self.ok = self.sock.bind(QHostAddress(QHostAddress.SpecialAddress.AnyIPv4), BEACON_PORT, mode)
        self.sock.readyRead.connect(self._read)
        self._targets = _broadcast_targets()
        self._announce = QTimer(self, interval=BEACON_EVERY_MS)
        self._announce.timeout.connect(self.announce)
        self._expire = QTimer(self, interval=1000)
        self._expire.timeout.connect(self._prune)
        self._expire.start()
        self._refresh = QTimer(self, interval=30_000)  # networks come and go
        self._refresh.timeout.connect(lambda: setattr(self, "_targets", _broadcast_targets()))
        self._refresh.start()

    def set_port(self, port):
        """Announce a receiver on this TCP port; 0 stops announcing."""
        self.port = port
        if port:
            self._announce.start()
            self.announce()
        else:
            self._announce.stop()

    def announce(self):
        if self.port:
            self._send(dict(self.identity, stemify=VERSION, port=self.port))

    def probe(self):
        self._targets = _broadcast_targets()
        self._send({"stemify": VERSION, "probe": True})

    def _send(self, msg):
        data = json.dumps(msg, separators=(",", ":")).encode()
        for addr in self._targets:
            self.sock.writeDatagram(data, addr, BEACON_PORT)

    def _read(self):
        changed = False
        while self.sock.hasPendingDatagrams():
            dg = self.sock.receiveDatagram(4096)
            try:
                msg = json.loads(bytes(dg.data().data()))
            except ValueError:
                continue
            if not isinstance(msg, dict) or msg.get("stemify") != VERSION:
                continue
            if msg.get("probe"):
                if self.port:
                    QTimer.singleShot(random.randint(20, 250), self.announce)
                continue
            did, port = str(msg.get("id") or ""), int(msg.get("port") or 0)
            if not did or did == self.identity["id"] or not port:
                continue
            host = dg.senderAddress()
            if host.protocol() == QAbstractSocket.NetworkLayerProtocol.IPv6Protocol and host.toIPv4Address():
                host = QHostAddress(host.toIPv4Address())  # ::ffff:a.b.c.d
            old = self.devices.get(did)
            new = {"id": did, "name": str(msg.get("name") or "Stemify"), "app": str(msg.get("app") or ""),
                   "host": host.toString(), "port": port, "transport": "wifi", "seen": time.monotonic()}
            if not old or any(old[k] != new[k] for k in ("name", "app", "host", "port")):
                changed = True
            self.devices[did] = new
        if changed:
            self.devicesChanged.emit()

    def _prune(self):
        now = time.monotonic()
        gone = [d for d, v in self.devices.items() if now - v["seen"] > DEVICE_TTL_S]
        for d in gone:
            del self.devices[d]
        if gone:
            self.devicesChanged.emit()
