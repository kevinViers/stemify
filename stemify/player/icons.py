"""Office-style ribbon icons: flat, two-tone, drawn from SVG at whatever size QML asks for.

image://icon/<name>[/<hex colour>] — the colour overrides the dark-gray ink (for white-on-green chrome).
"""

from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QImage, QPainter
from PySide6.QtQuick import QQuickImageProvider
from PySide6.QtSvg import QSvgRenderer

INK = "#444444"
GREEN = "#217346"
BLUE = "#2B7CD3"
RED = "#D13438"
ORANGE = "#E8912D"
LIGHT = "#FFFFFF"

# Each icon is SVG body markup on a 32×32 grid; {ink} is the main colour.
ICONS = {
    "play": f'<path fill="{GREEN}" d="M9 5v22l18-11z"/>',
    "pause": '<path fill="{ink}" d="M8 5h6v22H8zM18 5h6v22h-6z"/>',
    "stop": '<rect fill="{ink}" x="7" y="7" width="18" height="18" rx="1"/>',
    "previous": '<path fill="{ink}" d="M6 6h3v20H6z"/><path fill="{ink}" d="M27 6v20L11 16z"/>',
    "next": '<path fill="{ink}" d="M23 6h3v20h-3z"/><path fill="{ink}" d="M5 6v20l16-10z"/>',
    "shuffle": '<path fill="none" stroke="{ink}" stroke-width="2.4" d="M3 9h5c6 0 10 14 16 14h3M3 23h5c2.6 0 4.6-2.6 6.4-5.6M17.6 13.6C19.4 10.6 21.4 9 24 9h3"/>'
               '<path fill="{ink}" d="M25 4l5 5-5 5zM25 18l5 5-5 5z"/>',
    "repeat": '<path fill="none" stroke="{ink}" stroke-width="2.4" d="M6 15v-3c0-2.2 1.8-4 4-4h16M26 17v3c0 2.2-1.8 4-4 4H6"/>'
              '<path fill="{ink}" d="M24 3l6 5-6 5zM8 19l-6 5 6 5z"/>',
    "repeat_one": '<path fill="none" stroke="{ink}" stroke-width="2.4" d="M6 15v-3c0-2.2 1.8-4 4-4h16M26 17v3c0 2.2-1.8 4-4 4H6"/>'
                  f'<path fill="{{ink}}" d="M24 3l6 5-6 5zM8 19l-6 5 6 5z"/><path fill="{GREEN}" d="M15 11h3v10h-3v-7h-2z"/>',
    "volume": '<path fill="{ink}" d="M4 12v8h6l7 6V6l-7 6z"/><path fill="none" stroke="{ink}" stroke-width="2.2" d="M21 11c1.6 1.4 1.6 8.6 0 10M24.5 7.5c4 3.5 4 13.5 0 17"/>',
    "mute": f'<path fill="{{ink}}" d="M4 12v8h6l7 6V6l-7 6z"/><path stroke="{RED}" stroke-width="2.6" d="M21 12l8 8M29 12l-8 8"/>',
    "vocals": '<rect fill="{ink}" x="12" y="3" width="8" height="15" rx="4"/><path fill="none" stroke="{ink}" stroke-width="2.2" d="M8 14c0 4.4 3.6 8 8 8s8-3.6 8-8M16 22v6M11 28h10"/>',
    "vocals_off": f'<rect fill="{{ink}}" x="12" y="3" width="8" height="15" rx="4"/><path fill="none" stroke="{{ink}}" stroke-width="2.2" d="M8 14c0 4.4 3.6 8 8 8s8-3.6 8-8M16 22v6M11 28h10"/><path stroke="{RED}" stroke-width="2.8" d="M5 4l22 24"/>',
    "mixer": f'<path stroke="{{ink}}" stroke-width="2" d="M8 4v24M16 4v24M24 4v24"/><rect fill="{GREEN}" x="4.5" y="17" width="7" height="5" rx="1"/><rect fill="{BLUE}" x="12.5" y="8" width="7" height="5" rx="1"/><rect fill="{ORANGE}" x="20.5" y="13" width="7" height="5" rx="1"/>',
    "queue_add": f'<path fill="{{ink}}" d="M3 6h18v2.5H3zM3 12h18v2.5H3zM3 18h11v2.5H3z"/><path fill="{GREEN}" d="M22 16h3v5h5v3h-5v5h-3v-5h-5v-3h5z"/>',
    "play_next": f'<path fill="{{ink}}" d="M3 14h16v2.5H3zM3 20h16v2.5H3zM3 26h11v2.5H3z"/><path fill="{GREEN}" d="M18 3v10l9-5z"/>',
    "playlist_new": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M5 3h15l6 6v20H5z"/><path fill="{{ink}}" d="M9 13h12v2H9zM9 18h12v2H9zM9 23h7v2H9z"/><path fill="{GREEN}" d="M23 1h3v4h4v3h-4v4h-3V8h-4V5h4z"/>',
    "playlist_add": f'<path fill="{{ink}}" d="M3 7h16v2.5H3zM3 13h16v2.5H3zM3 19h9v2.5H3z"/><circle fill="{BLUE}" cx="23" cy="22" r="7"/><path fill="#FFFFFF" d="M22 18h2v3h3v2h-3v3h-2v-3h-3v-2h3z"/>',
    "delete_rows": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M3 6h26v20H3z"/><path fill="{{ink}}" d="M3 12.5h26v1.5H3zM3 18.5h26v1.5H3z"/><path fill="#FFC7CE" d="M3.8 14h24.4v4.5H3.8z"/><path stroke="{RED}" stroke-width="2.4" d="M12 11.5l8 9M20 11.5l-8 9"/>',
    "refresh": f'<path fill="none" stroke="{GREEN}" stroke-width="3" d="M26 16a10 10 0 1 1-3-7.1"/><path fill="{GREEN}" d="M27 3v9h-9z"/>',
    "plex": f'<path fill="{ORANGE}" d="M8 3h8l9 13-9 13H8l9-13z"/>',
    "database": f'<ellipse fill="{{ink}}" cx="14" cy="7" rx="10" ry="4"/><path fill="{{ink}}" d="M4 9.5c0 2.2 4.5 4 10 4s10-1.8 10-4V14c0 2.2-4.5 4-10 4S4 16.2 4 14zM4 16.5c0 2.2 4.5 4 10 4s10-1.8 10-4V21c0 2.2-4.5 4-10 4S4 23.2 4 21z"/><path fill="{GREEN}" d="M22 18l8 6-8 6z"/>',
    "server": f'<rect fill="{{ink}}" x="5" y="4" width="22" height="10" rx="1.5"/><rect fill="{{ink}}" x="5" y="17" width="22" height="10" rx="1.5"/><circle fill="{GREEN}" cx="22" cy="9" r="2"/><circle fill="{GREEN}" cx="22" cy="22" r="2"/>',
    "sort_az": f'<text x="2" y="14" font-family="Arial" font-weight="bold" font-size="12" fill="{BLUE}">A</text><text x="2" y="29" font-family="Arial" font-weight="bold" font-size="12" fill="{{ink}}">Z</text><path fill="{{ink}}" d="M20 4h3v18h4l-5.5 7-5.5-7h4z"/>',
    "sort_za": f'<text x="2" y="14" font-family="Arial" font-weight="bold" font-size="12" fill="{{ink}}">Z</text><text x="2" y="29" font-family="Arial" font-weight="bold" font-size="12" fill="{BLUE}">A</text><path fill="{{ink}}" d="M20 4h3v18h4l-5.5 7-5.5-7h4z"/>',
    "filter": '<path fill="{ink}" d="M3 4h26l-10 12v10l-6 3V16z"/>',
    "sigma": '<path fill="{ink}" d="M6 4h20v6h-3V7H11.5l7.5 9-7.5 9H23v-3h3v6H6v-2.4L14.8 16 6 6.4z"/>',
    "search": '<circle fill="none" stroke="{ink}" stroke-width="2.6" cx="13" cy="13" r="8"/><path stroke="{ink}" stroke-width="3.4" stroke-linecap="round" d="M19 19l8 8"/>',
    "lightbulb": '<path fill="none" stroke="{ink}" stroke-width="2" d="M11 22c0-4-5-6-5-12a10 10 0 0 1 20 0c0 6-5 8-5 12z"/><path fill="{ink}" d="M11 24h10v2H11zM12 27.5h8V30h-8z"/>',
    "grid": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M3 4h26v24H3z"/><path stroke="{{ink}}" stroke-width="1" d="M3 10h26M3 16h26M3 22h26M11 4v24M20 4v24"/><path fill="{GREEN}" d="M3 4h8v6H3z"/>',
    "freeze": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M3 4h26v24H3z"/><path fill="{BLUE}" d="M3 4h26v6H3z" opacity="0.35"/><path stroke="{BLUE}" stroke-width="2" d="M3 10h26"/><path stroke="{{ink}}" stroke-width="1" d="M3 16h26M3 22h26M11 4v24M20 4v24"/>',
    "headings": '<path fill="#FFFFFF" stroke="{ink}" stroke-width="1.6" d="M3 4h26v24H3z"/><path fill="#E6E6E6" d="M3.8 4.8h24.4V9H3.8zM3.8 9h5v18.2h-5z"/><text x="13" y="8.6" font-family="Arial" font-size="5" fill="{ink}">A  B</text><text x="5" y="16" font-family="Arial" font-size="5" fill="{ink}">1</text><text x="5" y="23" font-family="Arial" font-size="5" fill="{ink}">2</text>',
    "pane": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M3 4h26v24H3z"/><path fill="{GREEN}" d="M20 4.8h8.2v22.4H20z" opacity="0.85"/><path stroke="#FFFFFF" stroke-width="1.6" d="M22 10h4M22 14h4M22 18h4"/>',
    "zoom": '<circle fill="none" stroke="{ink}" stroke-width="2.6" cx="13" cy="13" r="8"/><path stroke="{ink}" stroke-width="3.4" stroke-linecap="round" d="M19 19l8 8"/><path stroke="{ink}" stroke-width="2.2" d="M9 13h8M13 9v8"/>',
    "chart": f'<path fill="#FFFFFF" stroke="{{ink}}" stroke-width="1.6" d="M3 4h26v24H3z"/><path fill="{BLUE}" d="M7 16h4v9H7z"/><path fill="{ORANGE}" d="M14 9h4v16h-4z"/><path fill="#A5A5A5" d="M21 13h4v12h-4z"/>',
    "styles": '<rect fill="#C6EFCE" x="3" y="4" width="12" height="10"/><rect fill="#FFC7CE" x="17" y="4" width="12" height="10"/><rect fill="#FFEB9C" x="3" y="17" width="12" height="10"/><rect fill="#FFFFFF" stroke="{ink}" x="17" y="17" width="12" height="10"/>',
    "info": f'<circle fill="{BLUE}" cx="16" cy="16" r="13"/><path fill="#FFFFFF" d="M14.5 13h3v11h-3zM14.5 7.5h3v3h-3z"/>',
    "account": '<circle fill="{ink}" cx="16" cy="10" r="6"/><path fill="{ink}" d="M4 29c0-6.6 5.4-11 12-11s12 4.4 12 11z"/>',
    "options": '<path fill="{ink}" d="M14 2h4l.7 3.6 2.5 1 3-2 2.8 2.8-2 3 1 2.5 3.6.7v4l-3.6.7-1 2.5 2 3-2.8 2.8-3-2-2.5 1L18 30h-4l-.7-3.6-2.5-1-3 2-2.8-2.8 2-3-1-2.5L2 18v-4l3.6-.7 1-2.5-2-3 2.8-2.8 3 2 2.5-1zM16 11a5 5 0 1 0 0 10 5 5 0 0 0 0-10z"/>',
    "back": '<circle fill="none" stroke="{ink}" stroke-width="2" cx="16" cy="16" r="13"/><path fill="none" stroke="{ink}" stroke-width="2.6" d="M18 9l-7 7 7 7"/>',
    "close": '<path stroke="{ink}" stroke-width="1.4" d="M10 10l12 12M22 10L10 22"/>',
    "minimize": '<path stroke="{ink}" stroke-width="1.4" d="M10 16h12"/>',
    "maximize": '<path fill="none" stroke="{ink}" stroke-width="1.4" d="M10.5 10.5h11v11h-11z"/>',
    "restore": '<path fill="none" stroke="{ink}" stroke-width="1.4" d="M10.5 13.5h8v8h-8zM13.5 13.5v-3h8v8h-3"/>',
    "save": '<path fill="{ink}" d="M5 4h18l4 4v19H5z"/><path fill="#FFFFFF" d="M9 4h12v8H9zM9 17h14v10H9z"/><path fill="{ink}" d="M17 5.5h3v5h-3z"/>',
    "undo": '<path fill="none" stroke="{ink}" stroke-width="2.4" d="M8 12h12a6 6 0 0 1 0 12h-6"/><path fill="{ink}" d="M3 12l7-6v12z"/>',
    "redo": '<path fill="none" stroke="{ink}" stroke-width="2.4" d="M24 12H12a6 6 0 0 0 0 12h6"/><path fill="{ink}" d="M29 12l-7-6v12z"/>',
    "excel": f'<rect fill="{GREEN}" x="2" y="4" width="18" height="24" rx="1.5"/><rect fill="#FFFFFF" x="14" y="8" width="16" height="16" rx="1"/><path stroke="{GREEN}" stroke-width="1" d="M14 13.3h16M14 18.6h16M22 8v16"/><path fill="#FFFFFF" d="M5 10h3.6l2.4 4 2.4-4H17l-4.2 6 4.2 6h-3.6L11 18l-2.4 4H5l4.2-6z"/>',
    "dropdown": '<path fill="{ink}" d="M9 12h14l-7 8z"/>',
    "sort_up": '<path fill="{ink}" d="M16 9l7 8H9z"/><path fill="{ink}" d="M14.5 17h3v7h-3z"/>',
    "sort_down": '<path fill="{ink}" d="M16 24l7-8H9z"/><path fill="{ink}" d="M14.5 8h3v8h-3z"/>',
    "left": '<path fill="{ink}" d="M20 8v16l-10-8z"/>',
    "right": '<path fill="{ink}" d="M12 8v16l10-8z"/>',
    "plus": '<circle fill="none" stroke="{ink}" stroke-width="1.6" cx="16" cy="16" r="12"/><path stroke="{ink}" stroke-width="2" d="M16 10v12M10 16h12"/>',
    "normal_view": '<path fill="none" stroke="{ink}" stroke-width="2" d="M5 7h22v18H5z"/><path stroke="{ink}" stroke-width="1.4" d="M5 13h22M5 19h22M12 7v18M19 7v18"/>',
    "page_view": '<path fill="none" stroke="{ink}" stroke-width="2" d="M5 4h22v24H5z"/><path fill="{ink}" d="M9 9h14v2H9zM9 14h14v2H9zM9 19h9v2H9z"/>',
    "break_view": '<path fill="none" stroke="{ink}" stroke-width="2" d="M5 4h22v24H5z"/><path stroke="{ink}" stroke-width="2" stroke-dasharray="3 2" d="M16 4v24M5 16h22"/>',
    "function": '<text x="3" y="24" font-family="Times New Roman" font-style="italic" font-size="22" fill="{ink}">fx</text>',
    "check": f'<path fill="none" stroke="{GREEN}" stroke-width="3" d="M6 16l7 7 13-14"/>',
    "cancel": f'<path stroke="{RED}" stroke-width="3" d="M8 8l16 16M24 8L8 24"/>',
}


class IconProvider(QQuickImageProvider):
    def __init__(self):
        super().__init__(QQuickImageProvider.ImageType.Image)

    def requestImage(self, id_, size, requested):
        name, _, ink = id_.partition("/")
        w = requested.width() if requested.width() > 0 else 32
        h = requested.height() if requested.height() > 0 else w
        body = ICONS.get(name, ICONS["grid"]).replace("{ink}", "#" + ink if ink else INK)
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">{body}</svg>'
        img = QImage(w, h, QImage.Format.Format_ARGB32_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        p = QPainter(img)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        QSvgRenderer(QByteArray(svg.encode())).render(p, QRectF(0, 0, w, h))
        p.end()
        if size is not None:
            size.setWidth(w)
            size.setHeight(h)
        return img
