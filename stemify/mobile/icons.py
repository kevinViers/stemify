"""The phone app's SF Symbols-flavoured glyphs (ui/theme/Icons.kt), same path data, on a
24-unit grid: each layer is filled, or stroked with round caps like SF's regular weight.

image://sym/<name>/<hex colour>, drawn at whatever size QML asks for.
"""

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QImage, QPainter
from PySide6.QtQuick import QQuickImageProvider
from PySide6.QtSvg import QSvgRenderer


def _circle(cx, cy, r):
    return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0z"


def f(d):
    return (d, 0.0)


def s(d, w=1.9):
    return (d, w)


_TEN = "M8.9 11.2l1.3-.9v6.2M14.3 10.3c-1 0-1.65 1.25-1.65 3.1s.65 3.1 1.65 3.1 1.65-1.25 1.65-3.1-.65-3.1-1.65-3.1z"
_SPEAKER = "M2.2 9.2h3.1L10 5.3c.4-.33 1-.05 1 .47v12.46c0 .52-.6.8-1 .47l-4.7-3.9H2.2a.7.7 0 0 1-.7-.7V9.9c0-.39.31-.7.7-.7z"
_REPEAT = "M4 11.5V9.5A3.5 3.5 0 0 1 7.5 6H20M17.2 3.2L20 6l-2.8 2.8M20 12.5v2A3.5 3.5 0 0 1 16.5 18H4M6.8 20.8L4 18l2.8-2.8"

SYMS = {
    "play": [f("M7.5 4.9v14.2c0 .82.9 1.32 1.6.88l11-7.1a1.05 1.05 0 0 0 0-1.76l-11-7.1c-.7-.44-1.6.06-1.6.88z")],
    "pause": [f("M6.2 4.5h3.1c.45 0 .8.35.8.8v13.4c0 .45-.35.8-.8.8H6.2a.8.8 0 0 1-.8-.8V5.3c0-.45.35-.8.8-.8zm8.5 0h3.1c.45 0 .8.35.8.8v13.4c0 .45-.35.8-.8.8h-3.1a.8.8 0 0 1-.8-.8V5.3c0-.45.35-.8.8-.8z")],
    "forward": [f("M1.6 6.8v10.4c0 .8.88 1.28 1.55.84l7.95-5.2a1 1 0 0 0 0-1.68L3.15 5.96c-.67-.44-1.55.04-1.55.84zm11 0v10.4c0 .8.88 1.28 1.55.84l7.95-5.2a1 1 0 0 0 0-1.68l-7.95-5.2c-.67-.44-1.55.04-1.55.84z")],
    "backward": [f("M22.4 6.8v10.4c0 .8-.88 1.28-1.55.84l-7.95-5.2a1 1 0 0 1 0-1.68l7.95-5.2c.67-.44 1.55.04 1.55.84zm-11 0v10.4c0 .8-.88 1.28-1.55.84l-7.95-5.2a1 1 0 0 1 0-1.68l7.95-5.2c.67-.44 1.55.04 1.55.84z")],
    "gobackward10": [s("M12 5a8 8 0 1 1-7.6 5.5M14 2.5L11.5 5 14 7.5", 1.8), s(_TEN, 1.6)],
    "goforward10": [s("M12 5a8 8 0 1 0 7.6 5.5M10 2.5L12.5 5 10 7.5", 1.8), s(_TEN, 1.6)],
    "chevronRight": [s("M9 4.5l7.5 7.5L9 19.5", 2.4)],
    "chevronLeft": [s("M15.5 4l-8 8 8 8", 2.6)],
    "chevronDown": [s("M4.5 9l7.5 7.5L19.5 9", 2.4)],
    "search": [s(f"{_circle(10.5, 10.5, 6.75)}M15.6 15.6l5 5", 2.2)],
    "note": [s(f"{_circle(6.5, 17.5, 3)}M9.5 17.5V4.2l9 -1.7v12.6{_circle(15.5, 14.8, 3)}M9.5 8.2l9-1.7", 1.9)],
    "noteList": [s("M3 6h11M3 11h11M3 16h6", 2), s("M18.5 17.2V5l3-.8", 1.9), f(_circle(16.4, 17.6, 2.4))],
    "library": [s("M6.5 3.8h11M4.5 6.8h15", 1.8),
                f("M4.2 9.6h15.6c.94 0 1.7.76 1.7 1.7v7.5c0 .94-.76 1.7-1.7 1.7H4.2c-.94 0-1.7-.76-1.7-1.7v-7.5c0-.94.76-1.7 1.7-1.7z")],
    "sliders": [s("M6 3.5v17M12 3.5v17M18 3.5v17", 1.8), f(_circle(6, 14.5, 2.6)), f(_circle(12, 8, 2.6)), f(_circle(18, 16, 2.6))],
    "gear": [f("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z")],
    "ellipsis": [f(_circle(5, 12, 1.9)), f(_circle(12, 12, 1.9)), f(_circle(19, 12, 1.9))],
    "plus": [s("M12 5v14M5 12h14", 2.2)],
    "check": [s("M5 12.5l4.5 4.5L19 7.5", 2.4)],
    "xmark": [s("M6.5 6.5l11 11M17.5 6.5l-11 11", 2.2)],
    "shuffle": [s("M3 7h3.2c2.4 0 3.8 1.3 5.3 5s2.9 5 5.3 5H20.5M3 17h3.2c1.2 0 2.1-.35 2.9-1.1M14.3 8.1c.8-.75 1.7-1.1 2.9-1.1H20.5M18 4.5L20.5 7 18 9.5M18 14.5l2.5 2.5-2.5 2.5", 1.9)],
    "repeat": [s(_REPEAT, 1.9)],
    "repeatOne": [s(_REPEAT, 1.9), s("M11 10.6l1.4-.9v5", 1.7)],
    "speaker": [f("M3.2 9.2h3.1L11 5.3c.4-.33 1-.05 1 .47v12.46c0 .52-.6.8-1 .47l-4.7-3.9H3.2a.7.7 0 0 1-.7-.7V9.9c0-.39.31-.7.7-.7z")],
    "speakerWave": [f(_SPEAKER), s("M14 9.3a3.8 3.8 0 0 1 0 5.4M16.6 6.8a7.4 7.4 0 0 1 0 10.4M19.2 4.3a11 11 0 0 1 0 15.4", 1.7)],
    "speakerSlash": [f(_SPEAKER), s("M15 9.5l5 5M20 9.5l-5 5", 1.9)],
    "download": [s(f"{_circle(12, 12, 9.25)}M12 7.5v8.5M8.5 12.6L12 16l3.5-3.4", 1.8)],
    "queue": [s("M8.5 6h12M8.5 12h12M8.5 18h12", 1.9), f(_circle(4, 6, 1.5)), f(_circle(4, 12, 1.5)), f(_circle(4, 18, 1.5))],
    "person": [s(f"{_circle(12, 7.8, 4)}M4.5 20.5c0-3.7 3.4-6.2 7.5-6.2s7.5 2.5 7.5 6.2", 1.9)],
    "album": [s("M6 3.8h12", 1.8), f("M5 6.8h14c.94 0 1.7.76 1.7 1.7v10.8c0 .94-.76 1.7-1.7 1.7H5c-.94 0-1.7-.76-1.7-1.7V8.5c0-.94.76-1.7 1.7-1.7z")],
    "waveform": [s("M3 10.5v3M6.6 7v10M10.2 4.5v15M13.8 8.5v7M17.4 6v12M21 10v4", 2)],
    "headphones": [s("M4 15.5v-3.2a8 8 0 0 1 16 0v3.2", 1.9),
                   f("M3.5 14.2h2.6c.66 0 1.2.54 1.2 1.2v4.2c0 .66-.54 1.2-1.2 1.2H4.9c-.77 0-1.4-.63-1.4-1.4v-5.2zM20.5 14.2h-2.6c-.66 0-1.2.54-1.2 1.2v4.2c0 .66.54 1.2 1.2 1.2h1.2c.77 0 1.4-.63 1.4-1.4v-5.2z")],
    "phone": [s("M8.2 2.8h7.6c1.1 0 2 .9 2 2v14.4c0 1.1-.9 2-2 2H8.2c-1.1 0-2-.9-2-2V4.8c0-1.1.9-2 2-2zM10.6 5.6h2.8", 1.8)],
    "desktop": [s("M3.5 4.5h17c.55 0 1 .45 1 1v10c0 .55-.45 1-1 1h-17a1 1 0 0 1-1-1v-10c0-.55.45-1 1-1zM12 16.5v3.5M8 20h8", 1.8)],
    "usb": [s("M8.5 3.5h7v6.5h-7zM12 10v10.5M10.5 6h.01M13.5 6h.01", 1.8)],
    "server": [s("M4.5 4.5h15c.55 0 1 .45 1 1v3.5c0 .55-.45 1-1 1h-15a1 1 0 0 1-1-1V5.5c0-.55.45-1 1-1zM4.5 14h15c.55 0 1 .45 1 1v3.5c0 .55-.45 1-1 1h-15a1 1 0 0 1-1-1V15c0-.55.45-1 1-1z", 1.8),
               f(_circle(7, 7.25, 1.1)), f(_circle(7, 16.75, 1.1))],
    "refresh": [s("M19.5 12.5A7.5 7.5 0 1 1 17 6.8M19.5 3.5v4.4h-4.4", 2)],
    "grip": [s("M4 7.5h16M4 12h16M4 16.5h16", 1.8)],
    "info": [s(f"{_circle(12, 12, 9.25)}M12 11v6M12 7.6v.01", 2)],
    "warning": [s("M10.3 4.2L2.8 17.6c-.75 1.33.21 2.9 1.7 2.9h15c1.49 0 2.45-1.57 1.7-2.9L13.7 4.2a1.95 1.95 0 0 0-3.4 0zM12 9.5v4.5M12 17.2v.01", 1.8)],
    "paintbrush": [s("M14.5 4.5l5 5-7.8 7.8-5-5zM6.7 12.3c-2.3.3-3.2 2.5-3.2 4.2 0 1.3-.5 2.4-1 3 3.7.6 7.4-.3 7.7-3.7", 1.8)],
    "gauge": [s("M4.2 17.5a9 9 0 1 1 15.6 0M12 13l4-5", 1.9), f(_circle(12, 13.4, 1.7))],
    "externalDrive": [s("M3.5 13l2.4-7.2c.27-.8 1-1.3 1.85-1.3h8.5c.84 0 1.58.5 1.85 1.3L20.5 13v4.5c0 1.1-.9 2-2 2h-13c-1.1 0-2-.9-2-2zM3.5 13h17M16.5 16.3h.01", 1.8)],
    "airplay": [s("M6.3 17.4a8 8 0 1 1 11.4 0M8.9 14.9a4.4 4.4 0 1 1 6.2 0", 1.8), f("M12 14.2l4.5 6.3h-9z")],
    "playNext": [s("M4 6.5h9M4 11.5h9M4 16.5h6", 1.9),
                 f("M15.5 13.6v6.8c0 .5.56.8.98.52l5.1-3.4a.62.62 0 0 0 0-1.04l-5.1-3.4c-.42-.28-.98.02-.98.52z")],
    "addQueue": [s("M4 6.5h16M4 11.5h16M4 16.5h9M17.5 14v6M14.5 17h6", 1.9)],
    "folder": [s("M3.5 6.5c0-.83.67-1.5 1.5-1.5h4.3l2 2.2H19c.83 0 1.5.67 1.5 1.5v9.3c0 .83-.67 1.5-1.5 1.5H5c-.83 0-1.5-.67-1.5-1.5z", 1.8)],
}


def svg(name, color):
    layers = SYMS.get(name) or SYMS["note"]
    body = "".join(
        f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>'
        if w else f'<path d="{d}" fill="{color}"/>' for d, w in layers)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">{body}</svg>'


class SymProvider(QQuickImageProvider):
    def __init__(self):
        super().__init__(QQuickImageProvider.ImageType.Image)

    def requestImage(self, ident, size, requested):
        name, _, color = ident.partition("/")
        w = requested.width() if requested.width() > 0 else 48
        h = requested.height() if requested.height() > 0 else w
        img = QImage(w, h, QImage.Format.Format_ARGB32_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        r = QSvgRenderer(QByteArray(svg(name, "#" + (color or "000000")).encode()))
        p = QPainter(img)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r.render(p, QRectF(0, 0, w, h))
        p.end()
        if size is not None:
            size.setWidth(w)
            size.setHeight(h)
        return img
