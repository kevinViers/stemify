"""One Connect conversation: JSON lines over a TCP or Bluetooth socket, with a keepalive."""

import json
import time

from PySide6.QtCore import QObject, QTimer, Signal

from .protocol import PING_EVERY_MS, SILENCE_LIMIT_S, encode

MAX_LINE = 16 << 20  # a whole-library playTracks is a few MB; anything bigger is garbage


class Link(QObject):
    message = Signal(dict)
    closed = Signal()

    def __init__(self, sock, transport, ping=False, parent=None):
        """sock: a connected QTcpSocket or QBluetoothSocket. ping: send pings (controllers do)."""
        super().__init__(parent)
        self.sock = sock
        self.transport = transport  # "wifi" | "bt"
        self._buf = bytearray()
        self._open = True
        self._heard = time.monotonic()
        sock.setParent(self)
        sock.readyRead.connect(self._read)
        sock.disconnected.connect(self.close)
        err = getattr(sock, "errorOccurred", None)
        if err is not None:
            err.connect(lambda *_: self.close())
        self._keepalive = QTimer(self, interval=PING_EVERY_MS)
        self._keepalive.timeout.connect(lambda: self._tick(ping))
        self._keepalive.start()

    @property
    def is_open(self):
        return self._open

    def send(self, msg):
        if self._open:
            self.sock.write(encode(msg))

    def _tick(self, ping):
        if time.monotonic() - self._heard > SILENCE_LIMIT_S:
            self.close()
        elif ping:
            self.send({"t": "ping"})

    def _read(self):
        self._heard = time.monotonic()
        self._buf += bytes(self.sock.readAll().data())
        while self._open:
            nl = self._buf.find(b"\n")
            if nl < 0:
                if len(self._buf) > MAX_LINE:
                    self.close()
                return
            line, self._buf = bytes(self._buf[:nl]), self._buf[nl + 1:]
            if not line.strip():
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            t = msg.get("t")
            if t == "ping":
                self.send({"t": "pong"})
            elif t != "pong":
                self.message.emit(msg)

    def close(self, reason=None):
        if not self._open:
            return
        if reason:
            self.send({"t": "bye", "reason": reason})
            if hasattr(self.sock, "flush"):
                self.sock.flush()
        self._open = False
        self._keepalive.stop()
        try:
            self.sock.readyRead.disconnect(self._read)
        except (RuntimeError, TypeError):
            pass
        self.sock.close()
        self.closed.emit()
        self.deleteLater()
