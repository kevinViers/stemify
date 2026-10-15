"""Entry point: Qt Quick window, providers, controller, dev frame-time instrumentation."""

import argparse
import json
import os
import signal
import sys
import time
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))


def _env_defaults():
    # Basic style so every control can take the Material x Aero skin.
    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    # Native Wayland on GNOME; X11 still works if forced.
    os.environ.setdefault("QT_QPA_PLATFORM", "wayland;xcb")
    # Threaded render loop: rendering paces itself to vsync off the GUI thread.
    os.environ.setdefault("QSG_RENDER_LOOP", "threaded")


class FrameStats:
    """Frame stats for the dev overlay and --bench.

    - frame time: sync + render work per frame, timed on the render thread.
      Hooks are only connected while the overlay is visible (a GIL hop per frame).
    - cadence: intervals from a QML FrameAnimation (one tick per presented
      frame), pushed in batches; a gap over 1.5x the vsync period is a miss.
    """

    def __init__(self, window, refresh_hz, render_hooks=False):
        self.refresh = refresh_hz or 240.0
        self.intervals = deque(maxlen=20000)
        self.misses = []
        self.work = deque(maxlen=20000)
        self._window = window
        self._sync_start = None
        self._pc = time.perf_counter_ns
        self._hooks = False
        if render_hooks:
            self.connect_hooks()

    def connect_hooks(self):
        from PySide6.QtCore import Qt
        direct = Qt.ConnectionType.DirectConnection
        self._window.beforeSynchronizing.connect(self._before_sync, direct)
        self._window.afterRendering.connect(self._after_render, direct)
        self._hooks = True

    def disconnect_hooks(self):
        if self._hooks:
            self._window.beforeSynchronizing.disconnect(self._before_sync)
            self._window.afterRendering.disconnect(self._after_render)
            self._hooks = False

    def _before_sync(self):
        self._sync_start = self._pc()

    def _after_render(self):
        if self._sync_start is not None:
            self.work.append((self._pc() - self._sync_start) / 1e6)

    def push(self, intervals_ms):
        self.intervals.extend(d for d in intervals_ms if 0 < d < 250)

    def miss(self, dt_ms):
        self.misses.append((time.monotonic(), round(dt_ms, 2)))

    def reset(self):
        self.intervals.clear()
        self.work.clear()
        self.misses.clear()
        self.t0 = time.monotonic()

    def summary(self, last=None):
        def pct(seq, q):
            a = sorted(seq)
            return a[min(len(a) - 1, int(q * len(a)))] if a else 0.0
        iv = list(self.intervals)[-last:] if last else list(self.intervals)
        wk = list(self.work)[-last:] if last else list(self.work)
        budget = 1000.0 / self.refresh
        missed = sum(1 for d in iv if d > budget * 1.5)
        return {
            "refresh_hz": round(self.refresh, 2), "frames": len(iv),
            "fps": round(1000.0 / (sum(iv) / len(iv)), 1) if iv else 0.0,
            "interval_p50": round(pct(iv, 0.5), 3), "interval_p99": round(pct(iv, 0.99), 3),
            "interval_max": round(max(iv), 3) if iv else 0.0,
            "work_p50": round(pct(wk, 0.5), 3) if wk else None,
            "work_p99": round(pct(wk, 0.99), 3) if wk else None,
            "missed": missed, "budget_ms": round(budget, 3),
        }


def main():
    _env_defaults()
    ap = argparse.ArgumentParser(prog="stemify")
    ap.add_argument("files", nargs="*", help="audio files to queue on launch")
    ap.add_argument("--frame-overlay", action="store_true", help="show p50/p99 frame-time overlay (F12 toggles)")
    ap.add_argument("--bench", type=float, default=0, metavar="SECONDS",
                    help="record frame stats for N seconds, print JSON, and quit")
    ap.add_argument("--bench-delay", type=float, default=2.0)
    ap.add_argument("--bench-out", default="")
    ap.add_argument("--screenshot", action="append", default=[], metavar="PNG@SECONDS",
                    help="grab the window to PNG after N seconds (repeatable)")
    ap.add_argument("--size", default="", metavar="WxH")
    ap.add_argument("--autoplay", action="store_true", help="start playback as soon as stems load (dev)")
    args = ap.parse_args()

    from PySide6.QtCore import QTimer, QUrl
    from PySide6.QtGui import QFontDatabase, QGuiApplication, QIcon, QSurfaceFormat
    from PySide6.QtQml import QQmlApplicationEngine
    from PySide6.QtQuick import QQuickWindow

    fmt = QSurfaceFormat.defaultFormat()
    fmt.setSwapInterval(1)  # vsync: pace to the panel (240Hz), never a fixed timer
    fmt.setAlphaBufferSize(8)  # rounded, translucent window corners
    QSurfaceFormat.setDefaultFormat(fmt)
    QQuickWindow.setDefaultAlphaBuffer(True)

    app = QGuiApplication(sys.argv)
    app.setApplicationName("Stemify")
    app.setOrganizationName("stemify")
    app.setDesktopFileName("stemify")
    app.setWindowIcon(QIcon(os.path.join(HERE, "assets", "stemify.svg")))

    font_dir = os.path.join(HERE, "assets", "fonts")
    if os.path.isdir(font_dir):
        for f in sorted(os.listdir(font_dir)):
            if f.endswith((".ttf", ".otf")):
                QFontDatabase.addApplicationFont(os.path.join(font_dir, f))
    families = set(QFontDatabase.families())
    ui_font = "Segoe UI Variable Text" if "Segoe UI Variable Text" in families else \
        "Segoe UI Variable" if "Segoe UI Variable" in families else "Inter"

    from .audio_engine import AudioEngine
    from .controller import Controller
    from .providers import FxProvider, IconProvider, PeakStore, WaveformProvider

    audio = AudioEngine()
    peaks = PeakStore()
    controller = Controller(audio, peaks)

    engine = QQmlApplicationEngine()
    engine.addImageProvider("icon", IconProvider())
    engine.addImageProvider("fx", FxProvider())
    engine.addImageProvider("wave", WaveformProvider(peaks, controller.peaks_path_for))
    engine.addImportPath(os.path.join(HERE, "qml"))
    ctx = engine.rootContext()
    ctx.setContextProperty("app", controller)
    ctx.setContextProperty("appSettings", controller.settings)
    ctx.setContextProperty("jobsModel", controller.jobs)
    ctx.setContextProperty("audio", audio)
    ctx.setContextProperty("uiFont", ui_font)
    ctx.setContextProperty("devOverlay", bool(args.frame_overlay or args.bench))
    from PySide6.QtCore import QObject, Slot

    class StatsBridge(QObject):
        stats = None

        @Slot("QVariantList")
        def push(self, intervals):
            if self.stats is not None:
                self.stats.push(intervals)

        @Slot(result="QVariantMap")
        def summary(self):
            return self.stats.summary(last=int(self.stats.refresh)) if self.stats else {}  # last ~1s

        @Slot()
        def reset(self):
            if self.stats is not None:
                self.stats.reset()

        @Slot(float)
        def miss(self, dt_ms):
            if self.stats is not None:
                self.stats.miss(dt_ms)

        @Slot(bool)
        def setHooks(self, on):
            if self.stats is None:
                return
            if on and not self.stats._hooks:
                self.stats.connect_hooks()
            elif not on:
                self.stats.disconnect_hooks()

    bridge = StatsBridge()
    ctx.setContextProperty("frameStats", bridge)
    engine.load(QUrl.fromLocalFile(os.path.join(HERE, "qml", "Stemify", "Main.qml")))
    if not engine.rootObjects():
        print("failed to load QML", file=sys.stderr)
        controller.shutdown()
        return 1
    window = engine.rootObjects()[0]
    if args.size:
        w, h = (int(v) for v in args.size.split("x"))
        window.resize(w, h)

    screen = window.screen()
    stats = FrameStats(window, screen.refreshRate() if screen else 240.0)
    bridge.stats = stats
    # Render-thread hooks need the GIL; only attach once the event loop owns the
    # GUI thread (Python calls that wait on the render thread would deadlock).
    if args.frame_overlay or args.bench or controller.settings.get("frameOverlay"):
        QTimer.singleShot(0, lambda: stats._hooks or stats.connect_hooks())
    window.screenChanged.connect(lambda sc: setattr(stats, "refresh", sc.refreshRate() if sc else stats.refresh))


    if args.autoplay:
        audio.loadedChanged.connect(lambda: audio.loaded and QTimer.singleShot(300, audio.play))

    if args.files:
        QTimer.singleShot(0, lambda: controller.addUrls([os.path.abspath(f) for f in args.files]))

    if args.bench:
        def start_bench():
            stats.reset()
            QTimer.singleShot(int(args.bench * 1000), finish_bench)

        def finish_bench():
            s = stats.summary()
            s["worker_state"] = controller._get_worker_state()
            s["misses_at"] = [(round(t - stats.t0, 3), d) for t, d in stats.misses if t >= stats.t0]
            out = json.dumps(s, indent=2)
            print(out, flush=True)
            if args.bench_out:
                with open(args.bench_out, "w") as f:
                    f.write(out)
            stats.disconnect_hooks()
            app.quit()

        QTimer.singleShot(int(args.bench_delay * 1000), start_bench)

    for shot in args.screenshot:
        path, _, after = shot.rpartition("@")
        def grab(path=path):
            # grabWindow blocks on the render thread while holding the GIL, so the
            # render-thread hooks must be gone first. A hook call already in flight
            # can still be waiting for the GIL after disconnect; yield to the event
            # loop for a few frames so it drains before blocking.
            hooks = stats._hooks
            stats.disconnect_hooks()

            def do_grab():
                window.grabWindow().save(path)
                if hooks:
                    stats.connect_hooks()
                print("screenshot saved", path, flush=True)
            QTimer.singleShot(100, do_grab)
        QTimer.singleShot(int(float(after) * 1000), grab)

    signal.signal(signal.SIGINT, lambda *_: app.quit())
    # Let Python see SIGINT while Qt's loop runs, without a fixed-rate redraw timer.
    keepalive = QTimer()
    keepalive.start(500)
    keepalive.timeout.connect(lambda: None)

    app.aboutToQuit.connect(stats.disconnect_hooks)
    app.aboutToQuit.connect(controller.shutdown)
    rc = app.exec()
    del engine
    return rc


if __name__ == "__main__":
    sys.exit(main())
