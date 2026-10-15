"""QML image providers. Everything here renders off the UI thread and is
uploaded once as a texture: no per-frame CPU work, no live blur.

  image://icon/<name>/<RRGGBB>            Material icon tinted, sized by sourceSize
  image://fx/backdrop/<W>x<H>/<radius>     window background (aurora)
  image://fx/glass/<W>x<H>/<radius>/<blur> blurred + tinted snapshot of the backdrop
  image://fx/glasscrop/<W>x<H>/<r>/<blur>/<x>,<y>,<w>,<h>/<sheet r>  rounded glass sheet
  image://fx/shadow/<sigma>/<alpha>/<r>    9-patch elevation shadow
  image://fx/glow/<RRGGBB>/<sigma>/<r>     9-patch hover glow
  image://fx/stripes/<h>                   progress stripe tile
  image://fx/noise                         dither tile
  image://wave/<job>/<stem>/<W>x<H>/<t0>/<t1>/<RRGGBB>[/<rev>]
"""

import threading

import numpy as np
from PySide6.QtCore import QByteArray, QRectF, Qt
from PySide6.QtGui import QImage, QPainter
from PySide6.QtQuick import QQuickImageProvider
from PySide6.QtSvg import QSvgRenderer
from scipy.ndimage import gaussian_filter

# Material Icons (Apache 2.0), 24x24 viewBox.
ICONS = {
    "play": "M8 5v14l11-7z",
    "pause": "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
    "stop": "M6 6h12v12H6z",
    "skip_start": "M6 6h2v12H6zm3.5 6l8.5 6V6z",
    "close": "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    "minimize": "M5 11h14v2H5z",
    "maximize": "M18 4H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H6V6h12v12z",
    "restore": "M3 5H1v16c0 1.1.9 2 2 2h16v-2H3V5zm18-4H7c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zm0 16H7V3h14v14z",
    "settings": "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
    "folder": "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z",
    "download": "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
    "check": "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    "error": "M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z",
    "refresh": "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    "add": "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    "music": "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
    "volume": "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    "volume_off": "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
    "headphones": "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z",
    "chip": "M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z",
    "delete": "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
    "upload": "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z",
    "equalizer": "M7 18h2V6H7v12zm4 4h2V2h-2v20zm-8-8h2v-4H3v4zm12 4h2V6h-2v12zm4-8v4h2v-4h-2z",
    "expand": "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z",
    "layers": "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z",
    "zoom_out": "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM7 9h5v1H7z",
    "speed": "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
}


def _hex(c):
    c = c.lstrip("#")
    return np.array([int(c[i:i + 2], 16) for i in (0, 2, 4)], np.float32) / 255


def _to_qimage(rgba: np.ndarray) -> QImage:
    """float RGBA [0,1] (H, W, 4), straight alpha -> premultiplied QImage (copies)."""
    a = np.clip(rgba[..., 3:4], 0, 1)
    out = np.empty(rgba.shape, np.uint8)
    out[..., :3] = np.clip(rgba[..., :3] * a * 255 + 0.5, 0, 255)
    out[..., 3:] = np.clip(a * 255 + 0.5, 0, 255)
    h, w = out.shape[:2]
    return QImage(out.data, w, h, w * 4, QImage.Format.Format_RGBA8888_Premultiplied).copy()


class IconProvider(QQuickImageProvider):
    def __init__(self):
        super().__init__(QQuickImageProvider.ImageType.Image)

    def requestImage(self, id_, size, requested):
        parts = id_.split("/")
        name, color = parts[0], (parts[1] if len(parts) > 1 else "ECEDEF")
        w = requested.width() if requested.width() > 0 else 24
        h = requested.height() if requested.height() > 0 else w
        svg = (f"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"
               f"<path fill='#{color}' d='{ICONS.get(name, ICONS['music'])}'/></svg>")
        img = QImage(w, h, QImage.Format.Format_ARGB32_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        p = QPainter(img)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        QSvgRenderer(QByteArray(svg.encode())).render(p, QRectF(0, 0, w, h))
        p.end()
        return img


# ---- backdrop / glass ---------------------------------------------------

BG = _hex("1D1E21")
GLASS_TINT = _hex("242529")
# (color, cx, cy, sigma as fraction of the larger side, strength)
# Kept faint so the window reads as neutral dark gray with a hint of Aero colour.
BLOBS = [
    (_hex("3BA8FF"), 0.10, 0.05, 0.30, 0.14),
    (_hex("7C8CFF"), 0.62, 0.18, 0.22, 0.06),
    (_hex("5FE3FF"), 0.92, 0.95, 0.26, 0.07),
    (_hex("FF7AB6"), 0.02, 0.98, 0.18, 0.025),
]
# Aero-style diagonal light beams: (offset fraction, width fraction, strength)
BEAMS = [(0.18, 0.025, 0.035), (0.30, 0.06, 0.025), (0.74, 0.04, 0.02)]


def _backdrop(w, h, blur_px=0.0, crop=None):
    """Analytic aurora; blurring a gaussian blob is a wider gaussian, so the
    'blurred snapshot' is exact and costs the same as the sharp one.
    crop=(x, y, cw, ch) renders only that window-space rectangle."""
    big = max(w, h)
    cx0, cy0, cw, ch = crop or (0, 0, w, h)
    x = np.arange(cx0, cx0 + cw, dtype=np.float32)
    y = np.arange(cy0, cy0 + ch, dtype=np.float32)
    img = np.empty((ch, cw, 3), np.float32)
    img[:] = BG
    for color, cx, cy, sig, strength in BLOBS:
        s0 = sig * big
        s = np.sqrt(s0 * s0 + blur_px * blur_px)
        amp = strength * (s0 / s) ** 2
        gx = np.exp(-((x - cx * w) ** 2) / (2 * s * s))
        gy = np.exp(-((y - cy * h) ** 2) / (2 * s * s))
        img += np.outer(gy * amp, gx)[..., None] * color
    # beams run along x + 0.55 y = const
    d = (x[None, :] + 0.55 * y[:, None]) / (big * 1.55)
    for off, width, strength in BEAMS:
        s0 = width
        s = np.sqrt(s0 * s0 + (blur_px / (big * 1.55)) ** 2)
        img += (strength * (s0 / s) * np.exp(-((d - off) ** 2) / (2 * s * s)))[..., None]
    return img


def _round_alpha(h, w, r):
    a = np.ones((h, w), np.float32)
    if r <= 0:
        return a
    yy, xx = np.mgrid[0:r, 0:r].astype(np.float32) + 0.5
    dist = np.sqrt((r - xx) ** 2 + (r - yy) ** 2)
    corner = np.clip(r - dist + 0.5, 0, 1)
    a[:r, :r] = corner
    a[:r, -r:] = corner[:, ::-1]
    a[-r:, :r] = corner[::-1, :]
    a[-r:, -r:] = corner[::-1, ::-1]
    return a


class FxProvider(QQuickImageProvider):
    def __init__(self):
        super().__init__(QQuickImageProvider.ImageType.Image)
        self._cache: dict[str, QImage] = {}
        self._lock = threading.Lock()

    def requestImage(self, id_, size, requested):
        with self._lock:
            hit = self._cache.get(id_)
        if hit is not None:
            return hit
        img = self._make(id_.split("/"))
        with self._lock:
            if len(self._cache) > 48:
                self._cache.clear()
            self._cache[id_] = img
        return img

    def _make(self, p):
        kind = p[0]
        if kind in ("backdrop", "glass", "glasscrop"):
            w, h = (max(1, int(v)) for v in p[1].split("x"))
            r = int(p[2])
            crop = None
            if kind == "glasscrop":  # <W>x<H>/<r>/<blur>/<x>,<y>,<cw>,<ch>: a rounded glass sheet (dialogs)
                crop = tuple(max(0, int(float(v))) for v in p[4].split(","))
                crop = (crop[0], crop[1], max(1, crop[2]), max(1, crop[3]))
            if kind == "backdrop":
                rgb = _backdrop(w, h)
                noise = np.random.default_rng(1).random((h, w, 1), dtype=np.float32)
                rgb += (noise - 0.5) / 255  # dither the dark gradients
            else:
                rgb = _backdrop(w, h, blur_px=float(p[3]), crop=crop)
                rgb = rgb * 0.30 + GLASS_TINT * 0.70  # glass-tint rgba(36,37,41,0.70)
                # glass sheen: one broad soft highlight along the top-left
                ox, oy = (crop[0], crop[1]) if crop else (0, 0)
                hh, ww = rgb.shape[:2]
                d = (np.arange(ox, ox + ww)[None, :] + 1.4 * np.arange(oy, oy + hh)[:, None]) / max(w, h)
                rgb += (0.035 * np.exp(-((d - 0.08) ** 2) / 0.004))[..., None]
                if crop:
                    r = int(p[3 + 2]) if len(p) > 5 else 12
            hh, ww = rgb.shape[:2]
            rgba = np.concatenate([rgb, _round_alpha(hh, ww, r)[..., None]], axis=2)
            return _to_qimage(rgba)
        if kind in ("shadow", "glow"):
            if kind == "shadow":
                color, sigma, alpha, r = np.zeros(3, np.float32), float(p[1]), float(p[2]), int(p[3])
            else:
                color, sigma, alpha, r = _hex(p[1]), float(p[2]), 0.9, int(p[3])
            m = int(np.ceil(sigma * 3)) + 1
            n = 2 * m + 2 * r + 2
            mask = np.zeros((n, n), np.float32)
            core = _round_alpha(2 * r + 2, 2 * r + 2, r)
            mask[m:m + 2 * r + 2, m:m + 2 * r + 2] = core
            blurred = gaussian_filter(mask, sigma) * alpha
            rgba = np.zeros((n, n, 4), np.float32)
            rgba[..., :3] = color
            rgba[..., 3] = blurred
            return _to_qimage(rgba)
        if kind == "stripes":
            h = int(p[1])
            period = 2 * h
            xx, yy = np.meshgrid(np.arange(period) + 0.5, np.arange(h) + 0.5)
            phase = ((xx + yy) % period) / period
            band = np.clip(np.minimum(phase, 1 - phase) * period - h / 2 + 0.5, 0, 1)
            rgba = np.zeros((h, period, 4), np.float32)
            rgba[..., :3] = 1
            rgba[..., 3] = band * 0.22
            return _to_qimage(rgba)
        if kind == "noise":
            rng = np.random.default_rng(7)
            v = rng.random((256, 256), dtype=np.float32)
            rgba = np.zeros((256, 256, 4), np.float32)
            rgba[..., :3] = v[..., None]
            rgba[..., 3] = 0.025
            return _to_qimage(rgba)
        img = QImage(1, 1, QImage.Format.Format_RGBA8888_Premultiplied)
        img.fill(Qt.GlobalColor.transparent)
        return img


# ---- waveforms ------------------------------------------------------------

class PeakStore:
    """Loaded peak pyramids keyed by job id. Shared with the controller."""

    def __init__(self):
        self._data: dict[str, dict] = {}
        self._lock = threading.Lock()

    def get(self, job_id, path):
        with self._lock:
            entry = self._data.get(job_id)
        if entry is None or entry["path"] != path:
            with np.load(path) as z:
                levels: dict[str, list] = {}
                for key in z.files:
                    if "/" in key:
                        stem, k = key.split("/")
                        levels.setdefault(stem, []).append((int(k), z[key].astype(np.float32)))
                entry = {"path": path, "sr": int(z["samplerate"]), "base": int(z["base"]),
                         "levels": {s: [a for _, a in sorted(v)] for s, v in levels.items()}}
            with self._lock:
                self._data[job_id] = entry
        return entry

    def drop(self, job_id):
        with self._lock:
            self._data.pop(job_id, None)


class WaveformProvider(QQuickImageProvider):
    def __init__(self, store: PeakStore, paths_for):
        super().__init__(QQuickImageProvider.ImageType.Image)
        self.store = store
        self.paths_for = paths_for  # job_id -> peaks_path (or None)

    def requestImage(self, id_, size, requested):
        p = id_.split("/")
        job_id, stem = p[0], p[1]
        w, h = (max(1, int(v)) for v in p[2].split("x"))
        t0, t1, color = float(p[3]), float(p[4]), _hex(p[5])
        path = self.paths_for(job_id)
        rgba = np.zeros((h, w, 4), np.float32)
        if path:
            try:
                entry = self.store.get(job_id, path)
                levels = entry["levels"].get(stem)
                if levels:
                    rgba = render_wave(levels, entry["base"], entry["sr"], t0, t1, w, h, color)
            except Exception:
                pass
        return _to_qimage(rgba)


def render_wave(levels, base, sr, t0, t1, w, h, color):
    s0, s1 = t0 * sr, max(t0 * sr + 1, t1 * sr)
    spp = (s1 - s0) / w  # samples per pixel
    k = 0
    while k + 1 < len(levels) and base * 2 ** (k + 1) <= spp:
        k += 1
    lvl = levels[k]
    bucket = base * 2 ** k
    edges = np.linspace(s0 / bucket, s1 / bucket, w + 1)
    starts = np.clip(edges[:-1].astype(np.int64), 0, len(lvl) - 1)
    ends = np.clip(np.ceil(edges[1:]).astype(np.int64), 1, len(lvl))
    ends = np.maximum(ends, starts + 1)
    # reduceat spans starts[i]..starts[i+1]; trim so the last pixel stops at its own edge
    view = lvl[:ends[-1]]
    lo = np.minimum.reduceat(view[:, 0], starts)
    hi = np.maximum.reduceat(view[:, 1], starts)
    valid = (edges[:-1] >= 0) & (edges[:-1] < len(lvl))
    lo = np.where(valid, np.clip(lo, -1, 1), 0)
    hi = np.where(valid, np.clip(hi, -1, 1), 0)

    mid = h / 2
    amp = mid - 1
    top = mid - hi * amp
    bot = mid - lo * amp
    # at least 1px tall so silence reads as a line
    short = bot - top < 1
    top = np.where(short, mid - 0.5, top)
    bot = np.where(short, mid + 0.5, bot)
    rows = np.arange(h, dtype=np.float32)[:, None] + 0.5
    cov = np.clip(np.minimum(rows - top[None, :] + 0.5, bot[None, :] - rows + 0.5), 0, 1)
    # brighter toward the centre line, like a lit glass bar
    shade = 0.55 + 0.45 * (1 - np.abs(rows - mid) / mid)
    rgba = np.empty((h, w, 4), np.float32)
    rgba[..., :3] = color
    rgba[..., 3] = cov * shade
    return rgba
