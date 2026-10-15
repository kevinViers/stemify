"""StemRoller Player: the Plex library and its stems, in a workbook."""

import argparse
import os
import signal
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    os.environ.setdefault("QT_QPA_PLATFORM", "wayland;xcb")
    os.environ.setdefault("QSG_RENDER_LOOP", "threaded")
    ap = argparse.ArgumentParser(prog="stemroller-player")
    ap.add_argument("--screenshot", action="append", default=[], metavar="PNG@SECONDS",
                    help="grab the window to PNG after N seconds (repeatable; for headless checks)")
    ap.add_argument("--quit-after", type=float, default=0, metavar="SECONDS")
    ap.add_argument("--size", default="", metavar="WxH")
    ap.add_argument("--no-mpris", action="store_true")
    ap.add_argument("--eval", action="append", default=[], metavar="JS@SECONDS",
                    help="run a JavaScript snippet against the window after N seconds (dev)")
    args = ap.parse_args()

    from PySide6.QtCore import QTimer, QUrl
    from PySide6.QtGui import QFontDatabase, QGuiApplication, QIcon, QSurfaceFormat
    from PySide6.QtQml import QQmlApplicationEngine

    fmt = QSurfaceFormat.defaultFormat()
    fmt.setSwapInterval(1)
    QSurfaceFormat.setDefaultFormat(fmt)

    app = QGuiApplication(sys.argv)
    app.setApplicationName("StemRoller Player")
    app.setOrganizationName("stemroller")
    app.setDesktopFileName("stemroller-player")
    app.setWindowIcon(QIcon(os.path.join(HERE, "assets", "stemroller-player.svg")))
    for d in (os.path.join(HERE, "assets", "fonts"), os.path.join(HERE, "..", "assets", "fonts")):
        if os.path.isdir(d):
            for f in sorted(os.listdir(d)):
                if f.endswith((".ttf", ".otf")):
                    QFontDatabase.addApplicationFont(os.path.join(d, f))
    families = set(QFontDatabase.families())
    # Excel: Segoe UI for the chrome, Calibri (or its metric twin Carlito) in the cells.
    ui_font = next((f for f in ("Segoe UI", "Selawik", "Inter") if f in families), "sans-serif")
    cell_font = next((f for f in ("Aptos", "Calibri", "Carlito") if f in families), ui_font)

    from . import config
    from .engine import PlayerEngine
    from .icons import IconProvider
    from .library import Library
    from .player import Player
    from .plex import Plex
    from .stems import StemServer

    cfg = config.load()
    plex = Plex(cfg)
    stems = StemServer(cfg, config.CACHE_DIR)
    engine = PlayerEngine()
    library = Library(cfg, plex, stems)
    player = Player(cfg, plex, stems, engine)
    library.player = player
    player.library = library
    library.update_queue()

    qml = QQmlApplicationEngine()
    qml.addImageProvider("icon", IconProvider())
    qml.addImportPath(os.path.join(HERE, "qml"))
    ctx = qml.rootContext()
    ctx.setContextProperty("library", library)
    ctx.setContextProperty("sheetModel", library.model)
    ctx.setContextProperty("player", player)
    ctx.setContextProperty("engine", engine)
    settings = _Settings(cfg)
    ctx.setContextProperty("settings", settings)
    ctx.setContextProperty("uiFont", ui_font)
    ctx.setContextProperty("cellFont", cell_font)
    qml.load(QUrl.fromLocalFile(os.path.join(HERE, "qml", "StemPlayer", "Main.qml")))
    if not qml.rootObjects():
        print("failed to load QML", file=sys.stderr)
        return 1
    window = qml.rootObjects()[0]
    if args.size:
        w, h = (int(v) for v in args.size.split("x"))
        window.resize(w, h)

    if not args.no_mpris:
        from .mpris import Mpris
        mpris = Mpris(player, engine, lambda: (window.show(), window.raise_(), window.requestActivate()), app.quit)
        window.setProperty("mprisOk", mpris.ok)

    for e in args.eval:
        code, _, after = e.rpartition("@")
        QTimer.singleShot(int(float(after) * 1000), lambda code=code: window.setProperty("devEval", code))
    for shot in args.screenshot:
        path, _, after = shot.rpartition("@")
        QTimer.singleShot(int(float(after) * 1000),
                          lambda path=path: (window.grabWindow().save(path), print("screenshot saved", path, flush=True)))
    if args.quit_after:
        QTimer.singleShot(int(args.quit_after * 1000), app.quit)

    signal.signal(signal.SIGINT, lambda *_: app.quit())
    keepalive = QTimer()
    keepalive.start(500)
    keepalive.timeout.connect(lambda: None)

    def shutdown():
        player.shutdown()
        engine.shutdown()
        config.save(cfg)
    app.aboutToQuit.connect(shutdown)
    rc = app.exec()
    del qml
    return rc


def _Settings(cfg):
    """Editable settings for File → Options."""
    from PySide6.QtCore import QObject, Slot

    from . import config

    class Settings(QObject):
        @Slot(str, result="QVariant")
        def get(self, key):
            return cfg.get(key)

        @Slot(str, "QVariant")
        def set(self, key, value):
            cfg[key] = value
            config.save(cfg)
    return Settings()


if __name__ == "__main__":
    sys.exit(main())
