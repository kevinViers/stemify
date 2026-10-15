#!/usr/bin/env python3
"""
Draws the response graphs in docs/EQUALIZER.md, as SVG, from the same maths the Android app
runs (android/.../engine/Dsp.kt: RBJ cookbook biquads, Butterworth cascades, the anti-clip
preamp). Standard library only. Re-run after changing the EQ maths:

    python3 docs/eq/make_figures.py
"""
import math
import os

FS = 48000.0
BANDS = [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
BAND_Q = 1.41

# ---- filters, mirroring Dsp.kt ---------------------------------------------------------------


def biquad(kind, f0, gain=0.0, q=0.707):
    a = 10 ** (gain / 40)
    w0 = 2 * math.pi * min(f0, FS * 0.49) / FS
    al = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    sa = 2 * math.sqrt(a) * al
    c = {
        "peak": [1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a],
        "lowshelf": [a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                     (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa],
        "highshelf": [a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                      (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa],
        "lowpass": [(1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al],
        "highpass": [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al],
        "bandpass": [al, 0, -al, 1 + al, -2 * cw, 1 - al],
        "notch": [1, -2 * cw, 1, 1 + al, -2 * cw, 1 - al],
    }[kind]
    a0 = c[3]
    return [[c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0]]


def first_order(f0, lowpass):
    k = math.tan(math.pi * f0 / FS)
    a1 = (k - 1) / (k + 1)
    return [k / (k + 1), k / (k + 1), 0, a1, 0] if lowpass else [1 / (k + 1), -1 / (k + 1), 0, a1, 0]


def pass_filter(kind, f0, slope=12, q=0.707):
    lp = kind == "lowpass"
    if slope <= 6:
        return [first_order(f0, lp)]
    if slope <= 12:
        return biquad(kind, f0, 0, q)
    n = slope // 6
    s = [biquad(kind, f0, 0, 1 / (2 * math.cos((2 * k - 1) * math.pi / (2 * n))))[0] for k in range(1, n // 2 + 1)]
    return s + ([first_order(f0, lp)] if n % 2 else [])


def bands(gains):
    return [s for f, g in zip(BANDS, gains) if abs(g) >= 0.01 for s in biquad("peak", f, g, BAND_Q)]


def mag_db(c, f):
    w = 2 * math.pi * f / FS
    nr = c[0] + c[1] * math.cos(w) + c[2] * math.cos(2 * w)
    ni = -(c[1] * math.sin(w) + c[2] * math.sin(2 * w))
    dr = 1 + c[3] * math.cos(w) + c[4] * math.cos(2 * w)
    di = -(c[3] * math.sin(w) + c[4] * math.sin(2 * w))
    return 10 * math.log10(max(1e-30, nr * nr + ni * ni) / max(1e-30, dr * dr + di * di))


# Log-spaced, plus the notch centres so their depth isn't left to where the grid happens to fall.
FREQS = sorted(set([20 * 1000 ** (i / 899) for i in range(900)] + [60.0, 120.0, 180.0]))


def response(sections, offset=0.0):
    return [sum(mag_db(c, f) for c in sections) + offset for f in FREQS]


def argmax(values):
    return FREQS[max(range(len(values)), key=values.__getitem__)]


def anti_clip(sections, manual=0.0):
    return -max(0.0, max(response(sections)) + manual)


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

# ---- drawing -------------------------------------------------------------------------------------

# Reference palette (validated categorical order: blue, orange, aqua, violet stand-in for 4 lines),
# light and dark steps, plus text and grid tokens.
STYLE = """
<style>
  svg { --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --muted:#8a8984; --grid:#e6e5e1; --zero:#b9b8b2;
        --s1:#2a78d6; --s2:#eb6834; --s3:#1baf7a; --s4:#6250d6; --before:#9a9993; }
  @media (prefers-color-scheme: dark) {
    svg { --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7; --muted:#8f8e86; --grid:#2e2e2c; --zero:#55544f;
          --s1:#3987e5; --s2:#d95926; --s3:#199e70; --s4:#9085e9; --before:#7c7b74; }
  }
  text { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
  .title { font-size: 15px; font-weight: 600; fill: var(--ink); }
  .sub { font-size: 12px; fill: var(--ink2); }
  .tick { font-size: 11px; fill: var(--muted); }
  .lab { font-size: 12px; fill: var(--ink2); }
  .ptitle { font-size: 12px; font-weight: 600; fill: var(--ink); }
  .grid { stroke: var(--grid); stroke-width: 1; }
  .zero { stroke: var(--zero); stroke-width: 1.25; }
  .line { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
</style>
"""

XTICKS = [(20, "20"), (50, "50"), (100, "100"), (200, "200"), (500, "500"), (1000, "1k"),
          (2000, "2k"), (5000, "5k"), (10000, "10k"), (20000, "20k")]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Plot:
    _n = 0

    def __init__(self, x, y, w, h, lo, hi, step):
        self.x, self.y, self.w, self.h, self.lo, self.hi, self.step = x, y, w, h, lo, hi, step
        Plot._n += 1
        self.clip = f"c{Plot._n}"

    def px(self, f):
        return self.x + math.log(f / 20) / math.log(1000) * self.w

    def py(self, db, clamp=True):
        span = self.hi - self.lo
        db = max(self.lo, min(self.hi, db)) if clamp else max(self.lo - span, min(self.hi + span, db))
        return self.y + (self.hi - db) / (self.hi - self.lo) * self.h

    def axes(self, ticks=True, band_lines=False):
        out = [f'<clipPath id="{self.clip}"><rect x="{self.x}" y="{self.y}" width="{self.w}" height="{self.h}"/></clipPath>']
        d = math.ceil(self.lo / self.step) * self.step
        while d <= self.hi + 1e-9:
            cls = "zero" if abs(d) < 1e-9 else "grid"
            out.append(f'<line class="{cls}" x1="{self.x:.1f}" x2="{self.x + self.w:.1f}" y1="{self.py(d):.1f}" y2="{self.py(d):.1f}"/>')
            if ticks:
                lab = ("+" if d > 0 else "−" if d < 0 else "") + f"{abs(d):g}"
                out.append(f'<text class="tick" x="{self.x - 6:.1f}" y="{self.py(d) + 4:.1f}" text-anchor="end">{lab}</text>')
            d += self.step
        for f, lab in XTICKS:
            out.append(f'<line class="grid" x1="{self.px(f):.1f}" x2="{self.px(f):.1f}" y1="{self.y:.1f}" y2="{self.y + self.h:.1f}"/>')
            if ticks:
                out.append(f'<text class="tick" x="{self.px(f):.1f}" y="{self.y + self.h + 15:.1f}" text-anchor="middle">{lab}</text>')
        if band_lines:
            for f in BANDS:
                out.append(f'<line class="grid" stroke-dasharray="2 3" x1="{self.px(f):.1f}" x2="{self.px(f):.1f}" y1="{self.y:.1f}" y2="{self.y + self.h:.1f}"/>')
        return out

    def line(self, values, color, dash=None, width=2, opacity=1.0):
        pts = " ".join(f"{self.px(f):.1f},{self.py(v, clamp=False):.1f}" for f, v in zip(FREQS, values))
        da = f' stroke-dasharray="{dash}"' if dash else ""
        op = f' stroke-opacity="{opacity}"' if opacity < 1 else ""
        return f'<polyline clip-path="url(#{self.clip})" class="line" stroke="var({color})" stroke-width="{width}"{da}{op} points="{pts}"/>'

    def dot(self, f, db, color, label=None, above=True, anchor="middle", dx=0):
        out = [f'<circle cx="{self.px(f):.1f}" cy="{self.py(db):.1f}" r="4.5" fill="var({color})" stroke="var(--surface)" stroke-width="2"/>']
        if label:
            dy = -10 if above else 18
            out.append(f'<text class="lab" x="{self.px(f) + dx:.1f}" y="{self.py(db) + dy:.1f}" text-anchor="{anchor}">{esc(label)}</text>')
        return out


def legend(items, x, y):
    out = []
    for color, dash, label in items:
        da = f' stroke-dasharray="{dash}"' if dash else ""
        out.append(f'<line x1="{x}" x2="{x + 22}" y1="{y - 4}" y2="{y - 4}" stroke="var({color})" stroke-width="2.5"{da} stroke-linecap="round"/>')
        out.append(f'<text class="lab" x="{x + 28}" y="{y}">{esc(label)}</text>')
        x += 28 + 7.0 * len(label) + 22
    return out


def chart(name, title, sub, series, lo, hi, step, extra=lambda p: [], band_lines=False, height=300, w=760):
    """series: (values, color var, dash or None, legend label or None)."""
    top = 74 if any(s[3] for s in series) else 58
    p = Plot(52, top, w - 52 - 24, height - top - 34, lo, hi, step)
    body = [f'<rect width="{w}" height="{height}" rx="10" fill="var(--surface)"/>',
            f'<text class="title" x="20" y="26">{esc(title)}</text>',
            f'<text class="sub" x="20" y="45">{esc(sub)}</text>']
    body += p.axes(band_lines=band_lines)
    for vals, color, dash, _ in series:
        body.append(p.line(vals, color, dash))
    body += extra(p)
    labels = [(c, d, l) for _, c, d, l in series if l]
    if labels:
        body += legend(labels, 20, 66)
    write(name, w, height, body, title)


def write(name, w, h, body, title):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" aria-label="{esc(title)}">'
           + STYLE + "\n".join(body) + "</svg>\n")
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), name), "w") as fh:
        fh.write(svg)


# ---- the figures ---------------------------------------------------------------------------------


def figures():
    flat = [0.0] * len(FREQS)

    # The band overlap: one band vs two and three neighbours at +6.
    one = response(bands([0, 0, 0, 0, 0, 6, 0, 0, 0, 0]))
    two = response(bands([0, 0, 0, 0, 6, 6, 0, 0, 0, 0]))
    three = response(bands([0, 0, 0, 6, 6, 6, 0, 0, 0, 0]))
    chart("band-overlap.svg", "Neighbouring bands add up",
          "Graphic EQ bands at +6 dB: the combined curve rises above any single slider",
          [(one, "--s1", None, "1 kHz at +6"), (two, "--s2", None, "500 Hz + 1 kHz at +6"), (three, "--s3", None, "250 + 500 Hz + 1 kHz at +6")],
          -3, 12, 3, band_lines=True,
          extra=lambda p: p.dot(argmax(two), max(two), "--s2", f"+{max(two):.1f} dB", anchor="start", dx=8) + p.dot(argmax(three), max(three), "--s3", f"+{max(three):.1f} dB", anchor="end", dx=-8))

    # Every preset, as small multiples.
    cols, cw, chh, gap = 3, 244, 120, 12
    rows = math.ceil(len(PRESETS) / cols)
    W = 20 + cols * cw + (cols - 1) * gap + 20
    H = 64 + rows * (chh + 34) + 10
    body = [f'<rect width="{W}" height="{H}" rx="10" fill="var(--surface)"/>',
            '<text class="title" x="20" y="26">Every preset in the Presets sheet</text>',
            '<text class="sub" x="20" y="45">Combined curve of the ten bands, ±12 dB, 20 Hz to 20 kHz. The figure after each name is the anti-clip preamp it needs.</text>']
    for i, (n, g) in enumerate(PRESETS):
        r, c = divmod(i, cols)
        x0 = 20 + c * (cw + gap)
        y0 = 64 + r * (chh + 34)
        p = Plot(x0, y0 + 22, cw, chh - 22, -12, 12, 6)
        resp = response(bands(g))
        ac = anti_clip(bands(g))
        body.append(f'<text class="ptitle" x="{x0}" y="{y0 + 12}">{esc(n)}</text>')
        body.append(f'<text class="tick" x="{x0 + cw}" y="{y0 + 12}" text-anchor="end">{"preamp off" if ac == 0 else f"preamp −{abs(ac):.1f} dB"}</text>')
        body += p.axes(ticks=False)
        body.append(p.line(resp, "--s1"))
        for f, lab in [(100, "100"), (1000, "1k"), (10000, "10k")]:
            body.append(f'<text class="tick" x="{p.px(f) + 3:.1f}" y="{y0 + chh + 13}">{lab}</text>')
    write("presets.svg", W, H, body, "Every EQ preset")

    # Anti-clipping, before and after, on Bass Booster.
    bb = bands(dict(PRESETS)["Bass Booster"])
    curve = response(bb)
    ac = anti_clip(bb)
    net = response(bb, ac)
    chart("anti-clip.svg", "What anti-clipping does (Bass Booster preset)",
          f"The preamp lowers everything by {abs(ac):.1f} dB, so the boosted bass lands back at 0 dB and nothing can exceed full scale",
          [(flat, "--before", "5 4", "Flat"), (curve, "--s1", None, "EQ curve"), (net, "--s2", None, f"After anti-clip preamp (−{abs(ac):.1f} dB)")],
          -12, 9, 3,
          extra=lambda p: p.dot(argmax(curve), max(curve), "--s1", f"+{max(curve):.1f} dB") + p.dot(argmax(net), max(net), "--s2", "0 dB", above=False)
          + p.dot(4000, ac, "--s2", f"rest of the mix −{abs(ac):.1f} dB", above=False))

    # Peak: Q.
    qs = [(0.5, "--s1"), (1.41, "--s2"), (4.32, "--s3"), (10, "--s4")]
    chart("peak-q.svg", "Peak filter: what Q does",
          "+6 dB at 1 kHz with four Q values. Higher Q, narrower bell.",
          [(response(biquad("peak", 1000, 6, q)), col, None, f"Q {q:g}" + {0.5: " (≈2½ oct)", 1.41: " (1 oct)", 4.32: " (⅓ oct)", 10: " (⅐ oct)"}[q]) for q, col in qs],
          -3, 9, 3)

    # Peak: boost vs cut, same settings.
    chart("peak-gain.svg", "Peak filter: what gain does",
          "Peaks at 2 kHz, Q 1.41. Boosts and cuts are mirror images.",
          [(response(biquad("peak", 2000, g, 1.41)), col, None, f"{'+' if g > 0 else '−'}{abs(g):g} dB") for g, col in [(9, "--s1"), (3, "--s2"), (-3, "--s3"), (-9, "--s4")]],
          -12, 12, 3)

    # Shelves: Q.
    chart("shelf-q.svg", "Low shelf: what Q does",
          "+6 dB below 100 Hz. Q 0.71 is the smooth standard; above it the shelf overshoots and dips.",
          [(response(biquad("lowshelf", 100, 6, q)), col, None, f"Q {q:g}") for q, col in [(0.5, "--s1"), (0.71, "--s2"), (1.41, "--s3"), (2, "--s4")]],
          -6, 12, 3, extra=lambda p: p.dot(100, 3, "--s2", "+3 dB (half the gain) at the set frequency", above=True, anchor="start", dx=10))

    # Shelves vs peak, same job.
    chart("shelf-vs-peak.svg", "Shelf or peak for more bass?",
          "Both +5 dB. The shelf lifts everything below 90 Hz; the peak lifts a band around 60 Hz and leaves the sub alone.",
          [(response(biquad("lowshelf", 90, 5, 0.71)), "--s1", None, "Low shelf 90 Hz, +5, Q 0.71"),
           (response(biquad("peak", 60, 5, 1.41)), "--s2", None, "Peak 60 Hz, +5, Q 1.41")],
          -3, 9, 3)

    # High shelf vs treble preset.
    chart("high-shelf.svg", "High shelf: three corner frequencies",
          "−4 dB, Q 0.71. The set frequency is where the shelf is halfway there.",
          [(response(biquad("highshelf", f, -4, 0.71)), col, None, f"{lab}") for f, col, lab in [(2000, "--s1", "2 kHz"), (5000, "--s2", "5 kHz"), (10000, "--s3", "10 kHz")]],
          -6, 3, 1.5)

    # High pass: slopes.
    chart("pass-slopes.svg", "High pass: the five slopes",
          "All at 100 Hz, all −3 dB there. Steeper slopes keep more above the cutoff and remove more below it.",
          [(response(pass_filter("highpass", 100, s)), col, dash, f"{s} dB/oct") for s, col, dash in
           [(6, "--s1", None), (12, "--s2", None), (24, "--s3", None), (48, "--s4", None)]],
          -48, 6, 6, extra=lambda p: p.dot(100, -3, "--before", "−3 dB at 100 Hz", above=False, anchor="start", dx=8))

    # Pass: Q at 12 dB/oct.
    chart("pass-q.svg", "Low pass at 12 dB/oct: what Q does",
          "All at 5 kHz. 0.71 is flat up to the corner; higher Q adds a resonant peak at the cutoff.",
          [(response(pass_filter("lowpass", 5000, 12, q)), col, None, f"Q {q:g}") for q, col in [(0.5, "--s1"), (0.71, "--s2"), (2, "--s3"), (5, "--s4")]],
          -24, 18, 6)

    # Band pass.
    chart("band-pass.svg", "Band pass: what Q does",
          "Centred on 1 kHz. It never boosts; Q decides how quickly everything else falls away.",
          [(response(biquad("bandpass", 1000, 0, q)), col, None, f"Q {q:g}") for q, col in [(0.5, "--s1"), (1.41, "--s2"), (4, "--s3"), (10, "--s4")]],
          -36, 3, 6)

    # Notch.
    chart("notch.svg", "Notch: what Q does",
          "At 60 Hz. Every Q kills 60 Hz itself; higher Q touches less of the music around it.",
          [(response(biquad("notch", 60, 0, q)), col, None, f"Q {q:g}") for q, col in [(1, "--s1"), (4, "--s2"), (10, "--s3"), (20, "--s4")]],
          -36, 3, 6)

    # Left / right.
    left = response(biquad("highshelf", 4000, 3, 0.71))
    chart("channels.svg", "A filter on one channel",
          "High shelf +3 dB at 4 kHz on Left only. The app draws the right channel in orange whenever they differ.",
          [(left, "--s1", None, "Left"), (flat, "--s2", None, "Right")],
          -3, 6, 1.5)

    # Recipes, before / after.
    spk = (pass_filter("highpass", 150, 24) + biquad("peak", 1500, 2, 0.71))
    spk_ac = anti_clip(spk)
    chart("recipe-speaker.svg", "Recipe: phone speaker",
          f"High pass 150 Hz at 24 dB/oct + peak 1.5 kHz, +2 dB, Q 0.71. Anti-clip adds −{abs(spk_ac):.1f} dB.",
          [(flat, "--before", "5 4", "Before (flat)"), (response(spk), "--s1", None, "After")],
          -24, 6, 6)

    k = biquad("peak", 2500, 2, 0.7)
    chart("recipe-karaoke.svg", "Recipe: fill the gap the vocals left",
          "Vocal stem muted, then one broad peak: 2.5 kHz, +2 dB, Q 0.7.",
          [(flat, "--before", "5 4", "Before (flat)"), (response(k), "--s1", None, "After")],
          -3, 4.5, 1.5)

    find = biquad("peak", 3150, 10, 8)
    fix = biquad("peak", 3150, -4, 3)
    chart("recipe-resonance.svg", "Recipe: boost to find, cut to fix",
          "Sweep a narrow +10 dB peak until the harsh spot jumps out, then flip it to a gentle, wider cut.",
          [(response(find), "--before", "5 4", "Step 1: search (+10 dB, Q 8)"), (response(fix), "--s1", None, "Step 2: fix (−4 dB, Q 3)")],
          -6, 12, 3)

    hum = biquad("notch", 60, 0, 10) + biquad("notch", 120, 0, 10) + biquad("notch", 180, 0, 10)
    chart("recipe-hum.svg", "Recipe: mains hum",
          "Notches at 60, 120 and 180 Hz, Q 10 each (use 50/100/150 Hz where the mains is 50 Hz).",
          [(flat, "--before", "5 4", "Before (flat)"), (response(hum), "--s1", None, "After")],
          -36, 3, 6)

    bass = biquad("highshelf", 1000, 4, 0.71) + pass_filter("highpass", 40, 24)
    chart("recipe-bassline.svg", "Recipe: learning a bass line",
          "With the bass stem soloed: high shelf 1 kHz +4 dB for the attack, high pass 40 Hz at 24 dB/oct against boom.",
          [(flat, "--before", "5 4", "Before (flat)"), (response(bass), "--s1", None, "After")],
          -24, 6, 6)

    late = bands(dict(PRESETS)["Late Night"])
    late2 = biquad("lowshelf", 100, 4, 0.71) + biquad("highshelf", 10000, 3, 0.71)
    chart("recipe-late-night.svg", "Recipe: late night",
          "The Late Night preset, against the two-filter version: low shelf 100 Hz +4, high shelf 10 kHz +3.",
          [(flat, "--before", "5 4", "Before (flat)"), (response(late), "--s1", None, "Late Night preset"), (response(late2), "--s2", None, "Two shelves")],
          -3, 9, 3)

    # Headphone correction (shape only: a made-up headphone, not a real AutoEQ result).
    corr = (biquad("lowshelf", 105, 5.5, 0.7) + biquad("peak", 180, -2.5, 0.8) + biquad("peak", 2900, 3.5, 2.2)
            + biquad("peak", 6200, -5, 4) + biquad("highshelf", 10000, -2, 0.7) + biquad("peak", 4300, 2, 3))
    manual = -6.0
    chart("autoeq.svg", "What an AutoEQ correction looks like",
          "Illustrative only: six filters of the kind AutoEQ produces, with its own preamp of −6 dB shown as the dashed line.",
          [(response(corr), "--s1", None, "Correction curve"), (response(corr, manual), "--s2", "6 4", "After AutoEQ's −6 dB preamp")],
          -12, 9, 3)


if __name__ == "__main__":
    figures()
    print("ok")
