"""The phone app's equalizer, on the desktop: a 10-band graphic EQ plus Equalizer APO-style
parametric filters, in profiles that follow the output device.

The maths is a port of android/.../engine/Dsp.kt (RBJ cookbook biquads, Butterworth cascades,
the anti-clip preamp) and the profile logic of eq/EqController.kt and eq/ApoConfig.kt, so a
profile sounds and reads the same on both. The output device is PipeWire's default sink.
"""

import json
import math
import os
import subprocess
import threading
import uuid

import numpy as np
from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

BANDS = [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
LABELS = ["31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"]
MAX_DB = 12.0
BAND_Q = 1.41  # one octave
SLOPES = [6, 12, 24, 36, 48]
FREQ_MIN, FREQ_MAX = 10.0, 22000.0
FILTER_GAIN = 24.0
Q_MIN, Q_MAX = 0.1, 20.0
PREAMP_RANGE = 24.0

# type: (APO name, label, has gain, has slope)
TYPES = {
    "peak": ("PK", "Peak", True, False),
    "lowshelf": ("LSC", "Low Shelf", True, False),
    "highshelf": ("HSC", "High Shelf", True, False),
    "lowpass": ("LPQ", "Low Pass", False, True),
    "highpass": ("HPQ", "High Pass", False, True),
    "bandpass": ("BP", "Band Pass", False, False),
    "notch": ("NO", "Notch", False, False),
    "allpass": ("AP", "All Pass", False, False),
}
TYPE_ORDER = list(TYPES)
CHANNELS = ["both", "left", "right"]

# iOS Music's EQ list, approximated on ten octave bands.
PRESETS = [
    ("Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
    ("Acoustic", [4.5, 4.5, 3.5, 1, 1.5, 1.5, 3, 3.5, 3, 2]),
    ("Bass Booster", [5.5, 4.5, 3.5, 2.5, 1.5, 0, 0, 0, 0, 0]),
    ("Bass Reducer", [-5.5, -4.5, -3.5, -2.5, -1.5, 0, 0, 0, 0, 0]),
    ("Classical", [4.5, 3.5, 3, 2.5, -1.5, -1.5, 0, 2, 3, 3.5]),
    ("Dance", [3.5, 6.5, 5, 0, 2, 3.5, 5, 4.5, 3.5, 0]),
    ("Deep", [5, 3.5, 1.5, 1, 3, 2.5, 1.5, -2, -3.5, -4.5]),
    ("Electronic", [4, 3.5, 1, 0, -2, 2, 1, 1.5, 4, 5]),
    ("Hip-Hop", [5, 4, 1.5, 3, -1, -1, 1.5, -0.5, 2, 3]),
    ("Jazz", [4, 3, 1.5, 2, -1.5, -1.5, 0, 1.5, 3, 3.5]),
    ("Late Night", [4.5, 3, 2, -0.5, 1.5, 1.5, 2, 3, 3.5, 4]),
    ("Loudness", [6, 4, 0, 0, -2, 0, -1, -5, 5, 1]),
    ("Piano", [3, 2, 0, 2.5, 3, 1.5, 3.5, 4.5, 3, 3.5]),
    ("Pop", [-1.5, -1, 0, 2, 4, 4, 2, 0, -1, -1.5]),
    ("R&B", [2.5, 7, 5.5, 1.5, -2.5, -1.5, 2, 2.5, 3, 3.5]),
    ("Rock", [5, 4, 3, 1.5, -0.5, -1, 0.5, 2.5, 3.5, 4.5]),
    ("Small Speakers", [5.5, 4.5, 3.5, 2.5, 1.5, 0, -1.5, -2.5, -3.5, -4]),
    ("Spoken Word", [-3.5, -0.5, 0, 0.5, 3.5, 4.5, 4.5, 4, 2, 0]),
    ("Treble Booster", [0, 0, 0, 0, 0, 1.5, 2.5, 3.5, 4.5, 5.5]),
    ("Treble Reducer", [0, 0, 0, 0, 0, -1.5, -2.5, -3.5, -4.5, -5.5]),
    ("Vocal Booster", [-1.5, -3, -3, 1.5, 3.5, 3.5, 3, 1.5, 0, -1.5]),
]


def new_id():
    return str(uuid.uuid4())


# ---- filters ------------------------------------------------------------------------------------

def make_filter(kind="peak", freq=1000.0, gain=0.0, q=0.707, slope=12, channel="both", on=True, fid=None):
    return {"id": fid or new_id(), "type": kind, "freq": float(freq), "gain": float(gain), "q": float(q),
            "slope": int(slope), "channel": channel, "on": bool(on)}


def starter(kind):
    """Sensible starting points, audible but mild, for a new filter of each type."""
    return {
        "peak": make_filter("peak", 1000, 3, 1.41),
        "lowshelf": make_filter("lowshelf", 105, 3, 0.71),
        "highshelf": make_filter("highshelf", 8000, 3, 0.71),
        "lowpass": make_filter("lowpass", 16000, q=0.71, slope=24),
        "highpass": make_filter("highpass", 30, q=0.71, slope=24),
        "bandpass": make_filter("bandpass", 1000, q=0.71),
        "notch": make_filter("notch", 60, q=4),
        "allpass": make_filter("allpass", 1000, q=0.71),
    }[kind]


def is_active(f):
    """Whether it changes the sound at all (a 0 dB peak or shelf doesn't)."""
    return f.get("on", True) and (not TYPES[f["type"]][2] or abs(f.get("gain", 0)) >= 0.01)


def applies(f, left):
    ch = f.get("channel", "both")
    return ch == "both" or (ch == "left") == left


def biquad(kind, fs, f0, gain, q):
    """RBJ Audio EQ Cookbook coefficients, [b0, b1, b2, a1, a2] normalised by a0."""
    a = 10 ** (gain / 40)
    w0 = 2 * math.pi * min(f0, fs * 0.49) / fs
    al = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    sa = 2 * math.sqrt(a) * al
    c = {
        "peak": (1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a),
        "lowshelf": (a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                     (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa),
        "highshelf": (a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                      (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa),
        "lowpass": ((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al),
        "highpass": ((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al),
        "bandpass": (al, 0.0, -al, 1 + al, -2 * cw, 1 - al),
        "notch": (1.0, -2 * cw, 1.0, 1 + al, -2 * cw, 1 - al),
        "allpass": (1 - al, -2 * cw, 1 + al, 1 + al, -2 * cw, 1 - al),
    }[kind]
    a0 = c[3]
    return [c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0]


def _first_order(fs, f0, lowpass):
    k = math.tan(math.pi * f0 / fs)
    a1 = (k - 1) / (k + 1)
    return [k / (k + 1), k / (k + 1), 0.0, a1, 0.0] if lowpass else [1 / (k + 1), -1 / (k + 1), 0.0, a1, 0.0]


def sections(f, fs):
    """A filter's biquad sections at fs."""
    if not is_active(f):
        return []
    f0 = min(max(f["freq"], 1.0), fs * 0.49)
    q = min(max(f["q"], 0.025), 100.0)
    kind = f["type"]
    if kind in ("lowpass", "highpass"):
        lp = kind == "lowpass"
        slope = f.get("slope", 12)
        if slope <= 6:
            return [_first_order(fs, f0, lp)]
        if slope <= 12:
            return [biquad(kind, fs, f0, 0.0, q)]
        # Butterworth of order slope/6, as cascaded sections (plus a first-order one when odd).
        n = slope // 6
        out = [biquad(kind, fs, f0, 0.0, 1 / (2 * math.cos((2 * k - 1) * math.pi / (2 * n)))) for k in range(1, n // 2 + 1)]
        return out + ([_first_order(fs, f0, lp)] if n % 2 else [])
    return [biquad(kind, fs, f0, f.get("gain", 0.0), q)]


def graphic(gains):
    """The 10-band graphic EQ as peaking filters, ahead of the parametric ones."""
    return [make_filter("peak", BANDS[i], g, BAND_Q, fid=f"band{i}") for i, g in enumerate(gains[:len(BANDS)]) if abs(g) >= 0.01]


def chain(profile):
    return graphic(profile.get("gains") or []) + [f for f in profile.get("filters") or [] if is_active(f)]


def log_freqs(n, lo=20.0, hi=20000.0):
    return lo * (hi / lo) ** (np.arange(n) / (n - 1.0))


def _mag_db(c, fs, freqs):
    w = 2 * np.pi * freqs / fs
    cw, sw, c2, s2 = np.cos(w), np.sin(w), np.cos(2 * w), np.sin(2 * w)
    nr = c[0] + c[1] * cw + c[2] * c2
    ni = -(c[1] * sw + c[2] * s2)
    dr = 1 + c[3] * cw + c[4] * c2
    di = -(c[3] * sw + c[4] * s2)
    return 10 * np.log10(np.maximum(1e-30, nr * nr + ni * ni) / np.maximum(1e-30, dr * dr + di * di))


def response(filters, freqs, left=True, fs=48000.0):
    """Combined response of filters on one channel, in dB at each of freqs."""
    out = np.zeros(len(freqs))
    for f in filters:
        if applies(f, left):
            for c in sections(f, fs):
                out += _mag_db(c, fs, freqs)
    return out


def auto_preamp(filters, manual=0.0):
    """Anti-clipping preamp: the negative of the curve's highest point (manual preamp included,
    louder channel), so a full-scale signal at the loudest frequency still peaks at 0 dBFS."""
    f = log_freqs(512)
    peak = max(float(response(filters, f, True).max(initial=0.0)), float(response(filters, f, False).max(initial=0.0)))
    return -max(0.0, peak + manual)


# ---- the audio-thread stage ---------------------------------------------------------------------

class Equalizer:
    """Runs a filter chain on (frames, 2) float32 blocks. The GUI thread hands it new params;
    the audio thread designs them at its sample rate. A slider drag keeps the filters' state,
    a different layout starts fresh."""

    def __init__(self):
        self._want = None  # (filters, preamp dB), or None for bypass
        self._have = object()
        self._fs = 0
        self._sos = [None, None]
        self._zi = [None, None]
        self._pre = 1.0

    def set(self, params):
        self._want = params

    def process(self, buf, fs):
        want = self._want
        if want is None:
            return
        if want is not self._have or fs != self._fs:
            self._design(want, fs)
        if self._pre != 1.0:
            buf *= self._pre
        from scipy.signal import sosfilt
        for ch in (0, 1):
            sos = self._sos[ch]
            if sos is not None:
                y, self._zi[ch] = sosfilt(sos, buf[:, ch], zi=self._zi[ch])
                buf[:, ch] = y

    def _design(self, params, fs):
        filters, pre_db = params
        for ch in (0, 1):
            secs = [c for f in filters if applies(f, ch == 0) for c in sections(f, float(fs))]
            if not secs:
                self._sos[ch], self._zi[ch] = None, None
                continue
            sos = np.array([[c[0], c[1], c[2], 1.0, c[3], c[4]] for c in secs])
            if self._zi[ch] is None or len(self._zi[ch]) != len(sos) or fs != self._fs:
                self._zi[ch] = np.zeros((len(sos), 2))
            self._sos[ch] = sos
        self._pre = 10 ** (pre_db / 20)
        self._fs = fs
        self._have = params


# ---- Equalizer APO / AutoEQ text ----------------------------------------------------------------

_APO_TYPES = {
    "PK": "peak", "PEQ": "peak", "MODAL": "peak",
    "LS": "lowshelf", "LSC": "lowshelf", "LSQ": "lowshelf",
    "HS": "highshelf", "HSC": "highshelf", "HSQ": "highshelf",
    "LP": "lowpass", "LPQ": "lowpass", "HP": "highpass", "HPQ": "highpass",
    "BP": "bandpass", "NO": "notch", "AP": "allpass",
}


def _num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def parse_apo(text):
    """Equalizer APO's config (AutoEQ's ParametricEQ.txt): {filters, preamp, skipped}, or None
    when the text has no preamp or filter lines at all."""
    filters, preamp, channel, skipped = [], None, "both", 0
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if ":" not in line:
            continue
        cmd, rest = line.split(":", 1)
        cmd, args = cmd.strip().lower(), rest.split()
        if cmd == "preamp":
            v = _num(args[0] if args else None)
            if v is not None:
                preamp = (preamp or 0.0) + v
        elif cmd == "channel":
            names = {a.upper() for a in args}
            channel = "left" if names == {"L"} else "right" if names == {"R"} else "both"
        elif cmd.startswith("filter"):
            f = _parse_filter(args, channel)
            if f:
                filters.append(f)
            else:
                skipped += 1
        else:
            skipped += 1
    if not filters and preamp is None:
        return None
    return {"filters": filters, "preamp": preamp, "skipped": skipped}


def _parse_filter(args, channel):
    i, on = 0, True
    head = args[0].upper() if args else ""
    if head in ("ON", "OFF"):
        on, i = head == "ON", 1
    kind = _APO_TYPES.get(args[i].upper() if i < len(args) else "")
    if not kind:
        return None
    i += 1
    freq, gain, q, slope = None, 0.0, None, 12
    while i < len(args):
        key = args[i].lower()
        nxt = _num(args[i + 1]) if i + 1 < len(args) else None
        if key == "fc" and nxt is not None:
            freq, i = nxt, i + 2
        elif key == "gain" and nxt is not None:
            gain, i = nxt, i + 2
        elif key == "q" and nxt is not None:
            q, i = nxt, i + 2
        elif key == "bw" and i + 2 < len(args) and args[i + 1].lower() == "oct" and _num(args[i + 2]) is not None:
            bw = _num(args[i + 2])
            q, i = math.sqrt(2 ** bw) / (2 ** bw - 1), i + 3
        elif key.endswith("db") and _num(key[:-2]) is not None:
            slope, i = int(_num(key[:-2])), i + 1  # "LSC 12dB Fc ...": a slope in dB per octave
        else:
            i += 1
    if freq is None:
        return None
    has_gain, has_slope = TYPES[kind][2], TYPES[kind][3]
    return make_filter(kind, freq, gain if has_gain else 0.0, q if q is not None else (BAND_Q if kind == "peak" else 0.707),
                       min(48, max(6, slope)) if has_slope else 12, channel, on)


def format_apo(p):
    """The profile as an Equalizer APO config: preamp, graphic bands as peaks, then the filters."""
    def n(v):
        return f"{v:.2f}".rstrip("0").rstrip(".")
    out = [f"# {p['name']}, from Stemify", f"Preamp: {n(p.get('preamp', 0.0))} dB"]
    channel, k = "both", 1
    for f in graphic(p.get("gains") or []) + list(p.get("filters") or []):
        if f["channel"] != channel:
            channel = f["channel"]
            out.append("Channel: " + {"left": "L", "right": "R", "both": "all"}[channel])
        apo, label, has_gain, has_slope = TYPES[f["type"]]
        head = f"{'ON' if f['on'] else 'OFF'} {apo} Fc {n(f['freq'])} Hz"
        if has_gain:
            out.append(f"Filter {k}: {head} Gain {n(f['gain'])} dB Q {n(f['q'])}")
            k += 1
        elif not has_slope or f["slope"] == 12:
            out.append(f"Filter {k}: {head} Q {n(f['q'])}")
            k += 1
        elif f["slope"] < 12:
            out.append(f"# 6 dB/oct {label.lower()}: APO has no first-order filter, so this is a gentle 12 dB/oct one")
            out.append(f"Filter {k}: {head} Q 0.5")
            k += 1
        else:
            order = f["slope"] // 6  # steeper slopes are Butterworth: one APO filter per section
            for j in range(1, order // 2 + 1):
                out.append(f"Filter {k}: {head} Q {n(1 / (2 * math.cos((2 * j - 1) * math.pi / (2 * order))))}")
                k += 1
    return "\n".join(out) + "\n"


# ---- output devices (PipeWire / PulseAudio sinks) ----------------------------------------------

def _device(sink):
    """{id, kind, label} for a pactl sink."""
    name = sink.get("name", "")
    desc = sink.get("description") or name
    port = (sink.get("active_port") or "").lower()
    if name.startswith("bluez"):
        kind = "bluetooth"
    elif ".usb-" in name or "usb" in (sink.get("properties") or {}).get("device.bus", ""):
        kind = "usb"
    elif "hdmi" in port or "hdmi" in name.lower() or "-dp" in port:
        kind = "hdmi"
    elif "headphone" in port:
        kind = "wired"
    elif name.startswith("alsa_output"):
        kind = "speaker"
    else:
        kind = "other"
    label = {"speaker": "Speakers", "wired": "Headphones"}.get(kind, desc)
    return {"id": f"{kind}|{name if kind not in ('speaker', 'wired') else ''}", "kind": kind, "label": label}


def current_device():
    try:
        default = subprocess.run(["pactl", "get-default-sink"], capture_output=True, text=True, timeout=3).stdout.strip()
        sinks = json.loads(subprocess.run(["pactl", "-f", "json", "list", "sinks"], capture_output=True, text=True, timeout=3).stdout)
    except (OSError, ValueError, subprocess.SubprocessError):
        return {"id": "other|", "kind": "other", "label": "Audio Output"}
    sink = next((s for s in sinks if s.get("name") == default), sinks[0] if sinks else {})
    return _device(sink) if sink else {"id": "other|", "kind": "other", "label": "Audio Output"}


# ---- profiles -----------------------------------------------------------------------------------

def _profile(pid, name, gains=None, devices=None, filters=None, preamp=0.0):
    return {"id": pid, "name": name, "gains": list(gains or [0.0] * len(BANDS)), "devices": list(devices or []),
            "filters": list(filters or []), "preamp": preamp}


def _default_state():
    return {"enabled": True, "automatic": True, "manualId": None, "defaultId": "flat", "antiClip": True,
            "profiles": [_profile("flat", "Flat")], "seenDevices": []}


class EqController(QObject):
    """EQ profiles and the current output. In automatic mode the active profile is the one
    linked to the output (else the default); a hand-picked profile overrides that until the
    user goes back to automatic."""

    changed = Signal()
    deviceChanged = Signal()
    _device_found = Signal(object)

    def __init__(self, path, engine, parent=None):
        super().__init__(parent)
        self.path = path
        self.engine = engine
        self.eq = Equalizer()
        engine.set_eq(self.eq)
        self.state = _default_state()
        try:
            with open(path) as f:
                self.state.update(json.load(f))
        except (OSError, ValueError):
            pass
        self.device = {"id": "other|", "kind": "other", "label": "Audio Output"}
        self._saver = QTimer(self, interval=400, singleShot=True)
        self._saver.timeout.connect(self._save)
        self._device_found.connect(self._set_device)
        self._publish()
        self._poll = QTimer(self, interval=3000)
        self._poll.timeout.connect(self.refresh_device)
        self._poll.start()
        self.refresh_device()

    def refresh_device(self):
        threading.Thread(target=lambda: self._device_found.emit(current_device()), daemon=True).start()

    def _set_device(self, d):
        if d == self.device and any(s["id"] == d["id"] for s in self.state["seenDevices"]):
            return
        self.device = d
        seen = self.state["seenDevices"]
        if not any(s["id"] == d["id"] for s in seen):
            self.state["seenDevices"] = ([d] + seen)[:24]
            self._save_soon()
        self.deviceChanged.emit()
        self._publish()

    # ---- resolution ----
    def _by_id(self, pid):
        return next((p for p in self.state["profiles"] if p["id"] == pid), None)

    def active(self):
        s = self.state
        if not s["automatic"] and s.get("manualId"):
            p = self._by_id(s["manualId"])
            if p:
                return p
        did = self.device["id"]
        return next((p for p in s["profiles"] if did in p["devices"]), None) or self._by_id(s["defaultId"]) or \
            (s["profiles"][0] if s["profiles"] else None)

    def _effective_preamp(self, p):
        return p.get("preamp", 0.0) + (auto_preamp(chain(p), p.get("preamp", 0.0)) if self.state["antiClip"] else 0.0)

    def _publish(self):
        p = self.active()
        if not self.state["enabled"] or p is None:
            self.eq.set(None)
        else:
            c = chain(p)
            self.eq.set(None if not c and p.get("preamp", 0.0) == 0 else (c, self._effective_preamp(p)))
        self.changed.emit()

    def _update(self):
        self._publish()
        self._save_soon()

    def _save_soon(self):
        self._saver.start()

    def _save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.state, f, indent=1)
        os.replace(tmp, self.path)

    def _edit(self, pid, fn):
        self.state["profiles"] = [fn(dict(p)) if p["id"] == pid else p for p in self.state["profiles"]]
        self._update()

    # ---- QML: state ----
    def _get_state(self):
        return self.state

    def _get_active(self):
        p = self.active()
        if p is None:
            return {}
        return dict(p, effectivePreamp=self._effective_preamp(p), autoPreamp=self._effective_preamp(p) - p.get("preamp", 0.0))

    info = Property("QVariantMap", _get_state, notify=changed)
    profile = Property("QVariantMap", _get_active, notify=changed)
    output = Property("QVariantMap", lambda self: self.device, notify=deviceChanged)
    presets = Property("QVariantList", lambda self: [{"name": n, "gains": g} for n, g in PRESETS], constant=True)
    bandLabels = Property("QVariantList", lambda self: LABELS, constant=True)
    typeList = Property("QVariantList", lambda self: [{"type": t, "label": v[1], "hasGain": v[2], "hasSlope": v[3]}
                                                     for t, v in TYPES.items()], constant=True)

    @Slot(str, result="QVariantMap")
    def typeInfo(self, kind):
        v = TYPES.get(kind, TYPES["peak"])
        return {"type": kind, "label": v[1], "hasGain": v[2], "hasSlope": v[3], "apo": v[0]}

    @Slot(str, result="QVariantMap")
    def profileById(self, pid):
        p = self._by_id(pid)
        return dict(p, effectivePreamp=self._effective_preamp(p)) if p else {}

    # ---- QML: curves ----
    @Slot("QVariantList", int, result="QVariantMap")
    def curve(self, filters, n):
        """Response of filters (a profile's chain, or one filter) at n log-spaced points, 20 Hz to 20 kHz."""
        f = log_freqs(max(2, n))
        left = response(filters, f, True)
        split = any(x.get("channel", "both") != "both" for x in filters)
        return {"left": [float(v) for v in left], "right": [float(v) for v in response(filters, f, False)] if split else []}

    @Slot(str, result="QVariantList")
    def chainOf(self, pid):
        p = self._by_id(pid)
        return chain(p) if p else []

    # ---- QML: edits ----
    @Slot(bool)
    def setEnabled(self, on):
        self.state["enabled"] = on
        self._update()

    @Slot(bool)
    def setAntiClip(self, on):
        self.state["antiClip"] = on
        self._update()

    @Slot(bool)
    def setAutomatic(self, on):
        """Automatic switching on clears the manual pick."""
        a = self.active()
        self.state["automatic"] = on
        self.state["manualId"] = None if on else (self.state.get("manualId") or (a["id"] if a else None))
        self._update()

    @Slot(str)
    def select(self, pid):
        self.state["manualId"], self.state["automatic"] = pid, False
        self._update()

    @Slot(str, int, float)
    def setGain(self, pid, band, db):
        def fn(p):
            g = list(p["gains"])
            g[band] = max(-MAX_DB, min(MAX_DB, db))
            return dict(p, gains=g)
        self._edit(pid, fn)

    @Slot(str, "QVariantList")
    def setGains(self, pid, gains):
        self._edit(pid, lambda p: dict(p, gains=[max(-MAX_DB, min(MAX_DB, float(g))) for g in gains]))

    @Slot(str, str)
    def rename(self, pid, name):
        self._edit(pid, lambda p: dict(p, name=name.strip() or p["name"]))

    @Slot(str, float)
    def setPreamp(self, pid, db):
        self._edit(pid, lambda p: dict(p, preamp=max(-PREAMP_RANGE, min(PREAMP_RANGE, db))))

    @Slot(str, "QVariantMap")
    def putFilter(self, pid, f):
        """Adds or replaces (by id) a parametric filter."""
        clean = dict(f, freq=max(FREQ_MIN, min(FREQ_MAX, float(f["freq"]))),
                     gain=max(-FILTER_GAIN, min(FILTER_GAIN, float(f.get("gain", 0)))),
                     q=max(Q_MIN, min(Q_MAX, float(f.get("q", 0.707)))), slope=int(f.get("slope", 12)))

        def fn(p):
            fs = list(p["filters"])
            i = next((i for i, x in enumerate(fs) if x["id"] == f["id"]), -1)
            if i >= 0:
                fs[i] = clean
            else:
                fs.append(clean)
            return dict(p, filters=fs)
        self._edit(pid, fn)

    @Slot(str, str, result="QVariantMap")
    def newFilter(self, pid, kind):
        f = starter(kind)
        self.putFilter(pid, f)
        return f

    @Slot(str, str)
    def removeFilter(self, pid, fid):
        self._edit(pid, lambda p: dict(p, filters=[x for x in p["filters"] if x["id"] != fid]))

    @Slot(str, str, result=str)
    def duplicateFilter(self, pid, fid):
        p = self._by_id(pid)
        f = next((x for x in (p or {}).get("filters", []) if x["id"] == fid), None)
        if not f:
            return ""
        copy = dict(f, id=new_id())
        self.putFilter(pid, copy)
        return copy["id"]

    @Slot(str)
    def clearFilters(self, pid):
        self._edit(pid, lambda p: dict(p, filters=[]))

    @Slot(str, result="QVariantMap")
    def parseApo(self, text):
        return parse_apo(text) or {}

    @Slot(str, "QVariantMap")
    def importApo(self, pid, parsed):
        pre = parsed.get("preamp")
        self._edit(pid, lambda p: dict(p, filters=list(parsed.get("filters") or []),
                                       preamp=p["preamp"] if pre is None else max(-PREAMP_RANGE, min(PREAMP_RANGE, pre))))

    @Slot(str, result=str)
    def apoText(self, pid):
        p = self._by_id(pid)
        return format_apo(p) if p else ""

    @Slot(str, str, result=str)
    def create(self, name, from_id):
        src = self._by_id(from_id) if from_id else None
        p = _profile(new_id(), name.strip() or "Profile", src["gains"] if src else None,
                     filters=[dict(f, id=new_id()) for f in (src or {}).get("filters", [])],
                     preamp=src["preamp"] if src else 0.0)
        self.state["profiles"] = self.state["profiles"] + [p]
        self._update()
        return p["id"]

    @Slot(str)
    def delete(self, pid):
        s = self.state
        if len(s["profiles"]) <= 1:
            return
        rest = [p for p in s["profiles"] if p["id"] != pid]
        s["profiles"] = rest
        if s["defaultId"] == pid:
            s["defaultId"] = rest[0]["id"]
        if s.get("manualId") == pid:
            s["manualId"] = None
        self._update()

    @Slot(str)
    def setDefault(self, pid):
        self.state["defaultId"] = pid
        self._update()

    @Slot(str, bool)
    def link(self, pid, on):
        """Links the current output to pid, unlinking it from any other profile (one profile per output)."""
        did = self.device["id"]
        self.state["profiles"] = [dict(p, devices=[d for d in p["devices"] if d != did] + ([did] if p["id"] == pid and on else []))
                                  for p in self.state["profiles"]]
        self._update()

    @Slot()
    def unlinkAll(self):
        did = self.device["id"]
        self.state["profiles"] = [dict(p, devices=[d for d in p["devices"] if d != did]) for p in self.state["profiles"]]
        self._update()

    @Slot(result="QVariantMap")
    def linked(self):
        did = self.device["id"]
        return next((p for p in self.state["profiles"] if did in p["devices"]), {})

    @Slot(str, result=str)
    def deviceLabel(self, did):
        return next((s["label"] for s in self.state["seenDevices"] if s["id"] == did), did.split("|", 1)[-1] or did.split("|")[0])

    def flush(self):
        if self._saver.isActive():
            self._saver.stop()
            self._save()
