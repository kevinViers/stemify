"""Artwork: Plex's photo transcoder (or a cover file), cached on disk so art shows offline too.

image://art/<px>/<corner radius px>/<source>, where source is a Plex thumb path or a file:// URL.
The corners are rounded here, so lists don't need a mask per row. QML Images that use it set
asynchronous: true, so the fetch happens on Qt's image loader threads.
"""

import colorsys
import hashlib
import os
import urllib.parse
import urllib.request

from PySide6.QtCore import QPointF, QRectF, QSize, Qt
from PySide6.QtGui import QColor, QImage, QPainter, QPainterPath
from PySide6.QtQuick import QQuickImageProvider


class ArtCache:
    def __init__(self, plex, cache_dir):
        self.plex = plex
        self.dir = os.path.join(cache_dir, "art")

    def path(self, thumb, px):
        return os.path.join(self.dir, hashlib.sha1(f"{px}|{thumb}".encode()).hexdigest() + ".img")

    def load(self, thumb, px):
        """Image bytes, from disk or fetched once; None when unavailable."""
        if thumb.startswith("file://"):
            try:
                with open(urllib.request.url2pathname(thumb[7:]), "rb") as f:
                    return f.read()
            except OSError:
                return None
        p = self.path(thumb, px)
        try:
            with open(p, "rb") as f:
                return f.read()
        except OSError:
            pass
        url = thumb if thumb.startswith("http") else self.plex.art_url(thumb, px)
        if not url:
            return None
        try:
            with urllib.request.urlopen(url, timeout=15) as r:
                data = r.read()
        except (OSError, ValueError):
            return None
        os.makedirs(self.dir, exist_ok=True)
        tmp = p + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, p)
        return data

    def image(self, thumb, px):
        data = self.load(thumb, px)
        img = QImage()
        if data:
            img.loadFromData(data)
        return img


class ArtProvider(QQuickImageProvider):
    def __init__(self, cache):
        super().__init__(QQuickImageProvider.ImageType.Image)
        self.cache = cache

    def requestImage(self, ident, size, requested):
        px, _, rest = ident.partition("/")
        radius, _, thumb = rest.partition("/")
        px = int(px or 600)
        try:
            radius = max(0, min(px // 2, int(float(radius))))
        except ValueError:
            radius = 0
        img = self.cache.image(urllib.parse.unquote(thumb), px)
        if img.isNull():
            img = QImage(1, 1, QImage.Format.Format_ARGB32)
            img.fill(Qt.GlobalColor.transparent)
        else:
            img = _rounded(img, px, radius)
        if size is not None:
            size.setWidth(img.width())
            size.setHeight(img.height())
        return img


def _rounded(img, px, radius):
    """Square-cropped to px and clipped to rounded corners."""
    src = img.scaled(QSize(px, px), Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
    out = QImage(px, px, QImage.Format.Format_ARGB32_Premultiplied)
    out.fill(Qt.GlobalColor.transparent)
    p = QPainter(out)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
    path = QPainterPath()
    path.addRoundedRect(QRectF(0, 0, px, px), radius, radius)
    p.setClipPath(path)
    p.drawImage(QPointF((px - src.width()) / 2, (px - src.height()) / 2), src)
    p.end()
    return out


def album_color(img: QImage, dark: bool) -> str:
    """One solid colour from the art, pushed dark (or light for the White theme) so text stays
    readable: the most vivid of its common colours, like Palette's dark-vibrant swatch."""
    if img.isNull():
        return ""
    small = img.scaled(QSize(28, 28), Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
    buckets = {}
    for y in range(small.height()):
        for x in range(small.width()):
            c = QColor(small.pixel(x, y))
            h, s, v = colorsys.rgb_to_hsv(c.redF(), c.greenF(), c.blueF())
            if v < 0.08:
                continue
            key = (int(h * 18) % 18, int(s * 4), int(v * 4))
            n, hs, ss, vs = buckets.get(key, (0, 0.0, 0.0, 0.0))
            buckets[key] = (n + 1, hs + h, ss + s, vs + v)
    if not buckets:
        return ""
    n, hs, ss, vs = max(buckets.values(), key=lambda b: b[0] * (0.25 + b[2] / b[0]) * (0.5 + min(b[3] / b[0], 0.7)))
    h, s = hs / n, ss / n
    if dark:
        s, v = min(s, 0.65), min(max(vs / n, 0.18), 0.30)
    else:
        s, v = min(s, 0.22), 0.96
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return QColor.fromRgbF(r, g, b).name()
