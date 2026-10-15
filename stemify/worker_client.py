"""UI-side owner of the separation worker process and its socket."""

import json
import os
import sys

from PySide6.QtCore import QObject, QProcess, QProcessEnvironment, QTimer, Signal
from PySide6.QtNetwork import QLocalServer, QLocalSocket


class WorkerClient(QObject):
    stateChanged = Signal(str)  # starting | ready | crashed
    hello = Signal(dict)
    vram = Signal(str, int, int)  # device, free_mb, total_mb
    progress = Signal(str, float, str)  # id, pct, stage
    done = Signal(str, dict)
    failed = Signal(str, str)  # id, message
    cancelled = Signal(str)
    crashed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        runtime = os.environ.get("XDG_RUNTIME_DIR") or "/tmp"
        self._sock_path = os.path.join(runtime, f"stemify-{os.getpid()}.sock")
        QLocalServer.removeServer(self._sock_path)
        self._server = QLocalServer(self)
        self._server.newConnection.connect(self._on_connection)
        if not self._server.listen(self._sock_path):
            raise RuntimeError(f"cannot listen on {self._sock_path}: {self._server.errorString()}")
        self._sock: QLocalSocket | None = None
        self._buf = b""
        self._proc: QProcess | None = None
        self._quitting = False
        self.state = "starting"
        self._restart_delay = 500

    def start(self):
        self._set_state("starting")
        proc = QProcess(self)
        env = QProcessEnvironment.systemEnvironment()
        pkg_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        env.insert("PYTHONPATH", pkg_root + os.pathsep + env.value("PYTHONPATH", ""))
        env.insert("PYTHONUNBUFFERED", "1")
        proc.setProcessEnvironment(env)
        proc.setProcessChannelMode(QProcess.ProcessChannelMode.ForwardedChannels)
        proc.finished.connect(self._on_finished)
        proc.start(sys.executable, ["-m", "stemify.worker", "--socket", self._sock_path])
        self._proc = proc

    def send(self, type_, **payload):
        if self._sock and self._sock.state() == QLocalSocket.LocalSocketState.ConnectedState:
            self._sock.write((json.dumps({"type": type_, **payload}) + "\n").encode())
            self._sock.flush()
            return True
        return False

    def pid(self):
        return int(self._proc.processId()) if self._proc else 0

    def shutdown(self):
        self._quitting = True
        self.send("quit")
        if self._proc and self._proc.state() != QProcess.ProcessState.NotRunning:
            if not self._proc.waitForFinished(1500):
                self._proc.kill()
                self._proc.waitForFinished(1000)
        self._server.close()

    def _set_state(self, s):
        self.state = s
        self.stateChanged.emit(s)

    def _on_connection(self):
        sock = self._server.nextPendingConnection()
        if self._sock:
            self._sock.deleteLater()
        self._sock = sock
        self._buf = b""
        sock.readyRead.connect(self._on_ready_read)

    def _on_ready_read(self):
        self._buf += bytes(self._sock.readAll())
        while b"\n" in self._buf:
            line, self._buf = self._buf.split(b"\n", 1)
            if line.strip():
                self._dispatch(json.loads(line))

    def _dispatch(self, msg):
        t = msg.pop("type")
        if t == "hello":
            self._restart_delay = 500
            self._set_state("ready")
            self.hello.emit(msg)
        elif t == "vram":
            self.vram.emit(msg["device"], msg["free_mb"], msg["total_mb"])
        elif t == "progress":
            self.progress.emit(msg["id"], msg["pct"], msg["stage"])
        elif t == "done":
            self.done.emit(msg.pop("id"), msg)
        elif t == "error":
            self.failed.emit(msg.get("id") or "", msg.get("msg", "unknown error"))
        elif t == "cancelled":
            self.cancelled.emit(msg["id"])

    def _on_finished(self, code, status):
        self._sock = None
        if self._quitting:
            return
        self._set_state("crashed")
        self.crashed.emit()
        QTimer.singleShot(self._restart_delay, self.start)
        self._restart_delay = min(self._restart_delay * 2, 10000)
