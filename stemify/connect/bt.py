"""Bluetooth for Connect: an RFCOMM service other devices can open, and finding paired devices
that offer it. Everything here quietly does nothing when there's no adapter or no QtBluetooth."""

import json
import re
import shutil
import subprocess
import threading

from PySide6.QtCore import QObject, QTimer, QUuid, Signal

from .protocol import BT_UUID, VERSION, encode

try:
    from PySide6.QtBluetooth import (QBluetoothAddress, QBluetoothLocalDevice, QBluetoothServer, QBluetoothServiceInfo,
                                     QBluetoothSocket, QBluetoothUuid)
    HAVE_BT = True
except ImportError:  # pragma: no cover - PySide6 without the Addons wheel
    HAVE_BT = False

PROBE_TIMEOUT_MS = 12_000


def _uuid():
    return QBluetoothUuid(QUuid(BT_UUID))


def adapter_on():
    if not HAVE_BT:
        return False
    local = QBluetoothLocalDevice()
    return local.isValid() and local.hostMode() != QBluetoothLocalDevice.HostMode.HostPoweredOff


def new_socket():
    return QBluetoothSocket(QBluetoothServiceInfo.Protocol.RfcommProtocol)


def connect_socket(sock, address):
    sock.connectToService(QBluetoothAddress(address), _uuid())


class BtServer(QObject):
    """Listens for controllers on RFCOMM. Retries every 30 s while the adapter is off."""

    incoming = Signal(object)  # a connected QBluetoothSocket

    def __init__(self, parent=None):
        super().__init__(parent)
        self.server = None
        self.info = None
        self._retry = QTimer(self, interval=30_000)
        self._retry.timeout.connect(self.start)

    @property
    def listening(self):
        return self.server is not None and self.server.isListening()

    def start(self):
        if not HAVE_BT or self.listening:
            return
        self._retry.start()
        if not adapter_on():
            return
        server = QBluetoothServer(QBluetoothServiceInfo.Protocol.RfcommProtocol, self)
        server.newConnection.connect(self._accept)
        # Registers an SPP-style record with our UUID, so phones can find it by UUID.
        info = server.listen(_uuid(), "Stemify Connect")
        if not server.isListening() or not info.isRegistered():
            server.close()
            server.deleteLater()
            if server.isListening() or info.isValid():
                self._retry.stop()  # registered already: another Stemify app on this computer has it
            return
        self.server, self.info = server, info
        self._retry.stop()

    def stop(self):
        self._retry.stop()
        if self.info is not None and self.info.isRegistered():
            self.info.unregisterService()
        if self.server is not None:
            self.server.close()
            self.server.deleteLater()
        self.server = self.info = None

    def _accept(self):
        while self.server is not None and self.server.hasPendingConnections():
            sock = self.server.nextPendingConnection()
            if sock is not None:
                self.incoming.emit(sock)


def paired_devices():
    """[(address, name)] of paired devices, from BlueZ. Blocking: call off the GUI thread."""
    if not shutil.which("bluetoothctl"):
        return []
    out = ""
    for args in (["bluetoothctl", "devices", "Paired"], ["bluetoothctl", "paired-devices"]):
        try:
            r = subprocess.run(args, capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            continue
        if r.returncode == 0 and "Device " in r.stdout:
            out = r.stdout
            break
    found = []
    for line in out.splitlines():
        m = re.match(r"\s*Device ([0-9A-F:]{17}) (.*)", line, re.I)
        if m and not _accessory(m.group(1)):
            found.append((m.group(1).upper(), m.group(2).strip()))
    return found


def _accessory(address):
    """Headphones, speakers, keyboards and the like: they can't run Stemify, so don't probe them."""
    try:
        info = subprocess.run(["bluetoothctl", "info", address], capture_output=True, text=True, timeout=3).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    m = re.search(r"^\s*Icon:\s*(\S+)", info, re.M)
    return bool(m) and m.group(1).startswith(("audio-", "input-", "printer", "camera-", "modem"))


class BtScanner(QObject):
    """Checks paired devices for the Connect service with a probe hello, one at a time."""

    found = Signal(dict)       # {id, name, app, address, transport: "bt"}
    finished = Signal()
    _paired = Signal(object)

    def __init__(self, identity, parent=None):
        super().__init__(parent)
        self.identity = identity
        self._todo = []
        self._busy = False
        self._sock = None
        self._buf = b""
        self._timer = QTimer(self, singleShot=True, interval=PROBE_TIMEOUT_MS)
        self._timer.timeout.connect(self._next)
        self._paired.connect(self._got_paired)

    def scan(self):
        if not HAVE_BT or self._busy or not adapter_on():
            self.finished.emit()
            return
        self._busy = True
        threading.Thread(target=lambda: self._paired.emit(paired_devices()), daemon=True).start()

    def _got_paired(self, devices):
        self._todo = list(devices)
        self._next()

    def _drop_socket(self):
        if self._sock is not None:
            s, self._sock = self._sock, None
            try:
                s.readyRead.disconnect()
                s.connected.disconnect()
                s.errorOccurred.disconnect()
            except (RuntimeError, TypeError):
                pass
            s.abort()
            s.deleteLater()

    def _next(self):
        self._timer.stop()
        self._drop_socket()
        if not self._todo:
            self._busy = False
            self.finished.emit()
            return
        address, name = self._todo.pop(0)
        self._buf = b""
        s = self._sock = new_socket()
        s.connected.connect(lambda: s.write(encode(dict(self.identity, t="hello", v=VERSION, probe=True))))
        s.readyRead.connect(lambda: self._read(s, address, name))
        s.errorOccurred.connect(lambda *_: QTimer.singleShot(0, self._next))
        self._timer.start()
        connect_socket(s, address)

    def _read(self, s, address, name):
        if s is not self._sock:
            return
        self._buf += bytes(s.readAll().data())
        if b"\n" not in self._buf:
            return
        try:
            msg = json.loads(self._buf.split(b"\n", 1)[0])
        except ValueError:
            msg = {}
        if msg.get("t") in ("welcome", "pending") and msg.get("id"):
            self.found.emit({"id": str(msg["id"]), "name": str(msg.get("name") or name), "app": str(msg.get("app") or ""),
                             "address": address, "transport": "bt"})
        QTimer.singleShot(0, self._next)
