"""Stemify Mobile: the phone app, on the desktop. Same screens, same mixer, same EQ."""

import argparse
import json
import os
import signal
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(os.environ.get("XDG_DATA_HOME", os.path.expanduser("~/.local/share")), "stemify", "mobile")

DEFAULTS = {
    "theme": "black",  # black | dark | white
    "accent": "music",
    "albumColor": False,
    "stemQuality": "high",
    "keepMix": True,
    "cacheLimitGb": 10,
    "downloadSort": 0,
    "musicSectionTitle": "",
    "volume": 0.85,
}
# Signed in on the desktop player already? Start from its servers and account.
SHARED = ("plexUrl", "plexToken", "plexUser", "plexMachineId", "musicSection", "sectionLocations", "stemUrl",
          "stemToken", "stemsDir", "musicDir", "stemRoots", "clientId")


def main():
    os.environ.setdefault("QT_QUICK_CONTROLS_STYLE", "Basic")
    os.environ.setdefault("QT_QPA_PLATFORM", "wayland;xcb")
    os.environ.setdefault("QSG_RENDER_LOOP", "threaded")
    ap = argparse.ArgumentParser(prog="stemify-mobile")
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
    app.setApplicationName("Stemify Mobile")
    app.setOrganizationName("stemify")
    app.setDesktopFileName("stemify-mobile")
    app.setWindowIcon(QIcon(os.path.join(HERE, "assets", "stemify-mobile.svg")))
    fonts = os.path.join(HERE, "..", "assets", "fonts")
    for f in sorted(os.listdir(fonts)):
        if f.endswith(".ttf"):
            QFontDatabase.addApplicationFont(os.path.join(fonts, f))

    from ..player import config
    config.use("mobile", DEFAULTS)
    first_run = not os.path.exists(config.PATH)
    cfg = config.load()
    if first_run:
        try:
            with open(os.path.join(config.CONFIG_DIR, "player.json")) as f:
                old = json.load(f)
            cfg.update({k: old[k] for k in SHARED if old.get(k)})
            if cfg.get("musicSection") and not cfg.get("musicSectionTitle"):
                cfg["musicSectionTitle"] = "Music"
        except (OSError, ValueError):
            pass
        config.save(cfg)

    from ..player.engine import PlayerEngine
    from ..player.plex import Plex
    from ..player.stems import StemServer
    from .art import ArtCache, ArtProvider
    from .backend import Backend
    from .downloads import Downloads
    from .eq import EqController
    from .icons import SymProvider
    from .playback import MobilePlayer

    plex = Plex(cfg, product="Stemify")
    stems = StemServer(cfg, config.CACHE_DIR)
    art = ArtCache(plex, config.CACHE_DIR)
    engine = PlayerEngine()
    backend = Backend(cfg, plex, stems, art, config.CACHE_DIR)
    downloads = Downloads(cfg, plex, stems, art, DATA_DIR, config.CACHE_DIR, backend.opus_kbps)
    player = MobilePlayer(cfg, plex, stems, engine, downloads, backend.opus_kbps)
    eq = EqController(os.path.join(DATA_DIR, "eq.json"), engine)

    qml = QQmlApplicationEngine()
    qml.addImageProvider("sym", SymProvider())
    qml.addImageProvider("art", ArtProvider(art))
    qml.addImportPath(os.path.join(HERE, "qml"))
    ctx = qml.rootContext()
    ctx.setContextProperty("app", backend)
    ctx.setContextProperty("player", player)
    ctx.setContextProperty("engine", engine)
    ctx.setContextProperty("downloads", downloads)
    ctx.setContextProperty("eq", eq)
    qml.load(QUrl.fromLocalFile(os.path.join(HERE, "qml", "StemMobile", "Main.qml")))
    if not qml.rootObjects():
        print("failed to load QML", file=sys.stderr)
        return 1
    window = qml.rootObjects()[0]
    if args.size:
        w, h = (int(v) for v in args.size.split("x"))
        window.resize(w, h)

    if not args.no_mpris:
        from ..player.mpris import Mpris
        mpris = Mpris(player, engine, lambda: (window.show(), window.raise_(), window.requestActivate()), app.quit,
                      identity="Stemify Mobile", desktop_entry="stemify-mobile")
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
        eq.flush()
        backend.shutdown()
        config.save(cfg)
    app.aboutToQuit.connect(shutdown)
    rc = app.exec()
    # Everything is saved by now; leave before interpreter teardown, so a worker thread finishing
    # a request can't signal into objects Qt has already destroyed.
    sys.stdout.flush()
    sys.stderr.flush()
    os._exit(rc)


if __name__ == "__main__":
    sys.exit(main())
