"""Crossfades: when the next song comes in, and how the two fade.

Smart crossfades listen to both songs first. We decode the last TAIL_S seconds of the song
that's ending and the first HEAD_S seconds of the next, measure their loudness every HOP
seconds, and read the shape of the join from that:

- silence after the last note and before the first is skipped, so nothing fades over nothing;
- a song that fades or dies away is crossed over its fade, up to the chosen length;
- a cold ending into a quiet intro lets the intro start under the last moment, unfaded;
- a cold ending into a loud start just butts the two together, silences trimmed.

Short, Medium and Long cap how far the songs overlap. Fixed always overlaps by the chosen
seconds (trailing and leading silence still trimmed). Either way the overlap stays under
MAX_SHARE of the shorter song.

The phone runs the same rules: android/.../engine/Crossfade.kt. Keep the two in step.
"""

import math
from dataclasses import dataclass

import numpy as np

HOP = 0.05           # seconds per loudness reading
TAIL_S = 25.0        # how much of the outgoing song's end is measured
HEAD_S = 20.0        # how much of the incoming song's start is measured
FLOOR_DB = -60.0     # quieter than this is silence, whatever the song
SILENCE_BELOW = 40.0  # ...as is anything this far under the song's loud level
BODY_BELOW = 9.0     # within this of the loud level is the song proper (not a fade or intro)
SMOOTH = 8           # readings averaged when finding the body (0.4 s)
FADE_MIN = 1.5       # a fade-out or intro shorter than this doesn't count
MAX_SHARE = 0.4      # overlap at most this share of the shorter song
CAPS = {"short": 4.0, "medium": 7.0, "long": 12.0}
MODES = ("off", "short", "medium", "long", "fixed")
FIXED_RANGE = (1, 12)


@dataclass
class Envelope:
    """Loudness readings, one per HOP: db[i] covers [start + i*HOP, start + (i+1)*HOP)."""
    start: float
    db: list


@dataclass
class Plan:
    """All times in seconds. Outgoing times are into the song that's ending."""
    start: float      # the next song comes in here
    fade_from: float  # the ending song fades from here...
    fade_to: float    # ...to silence here (equal to fade_from: no fade, it ends as written)
    end: float        # and stops here, its trailing silence cut
    in_offset: float  # the next song starts this far in, its leading silence skipped
    in_fade: float    # and fades in over this long (0: full level at once)


def plan(mode, seconds, tail, out_dur, head, in_dur):
    """The crossfade from a song of out_dur seconds into one of in_dur, or None for none.
    tail and head are Envelopes, or None when the songs couldn't be measured."""
    if mode not in CAPS and mode != "fixed":
        return None
    out_end, body_end = _outgoing(tail, out_dur)
    in_start, in_body = _incoming(head)
    if in_start >= in_dur - 1.0:  # a song that's silent as far as we looked: play it from the top
        in_start = in_body = 0.0
    limit = max(0.0, MAX_SHARE * min(out_dur, in_dur - in_start))
    if mode == "fixed":
        return _even(min(float(seconds), limit, out_end), out_end, in_start)
    cap = min(CAPS[mode], limit)
    if tail is None or head is None:
        return _even(cap / 2, out_end, in_start)
    fade = out_end - body_end
    intro = in_body - in_start
    if fade >= FADE_MIN:
        ov = min(cap, fade)
        # An intro that's already quiet doesn't need a long fade in to sit under the old song.
        return Plan(out_end - ov, out_end - ov, out_end, out_end, in_start, ov if intro < 1.0 else min(ov, 1.0))
    if intro >= FADE_MIN:
        ov = min(cap * 0.5, intro * 0.5)
        return Plan(out_end - ov, out_end, out_end, out_end, in_start, 0.0)
    return Plan(out_end, out_end, out_end, out_end, in_start, 0.0)


def _even(ov, out_end, in_start):
    """A plain equal-power crossfade of ov seconds."""
    ov = max(0.0, ov)
    return Plan(out_end - ov, out_end - ov, out_end, out_end, in_start, ov)


def _outgoing(tail, out_dur):
    """(where the last sound ends, where the song's body ends) in the outgoing song."""
    if tail is None or not len(tail.db):
        return out_dur, out_dur
    db = list(tail.db)
    ref = _pct(db, 0.95)
    silent = max(FLOOR_DB, ref - SILENCE_BELOW)
    last = next((i for i in range(len(db) - 1, -1, -1) if db[i] > silent), None)
    if last is None:
        return min(out_dur, tail.start), min(out_dur, tail.start)
    end = min(out_dur, tail.start + (last + 1) * HOP)
    sm = _smooth(db)
    body = next((i for i in range(last, -1, -1) if sm[i] >= ref - BODY_BELOW), None)
    body_end = tail.start + (body + 1) * HOP if body is not None else tail.start
    return end, min(end, body_end)


def _incoming(head):
    """(where the first sound starts, where the song's body starts) in the incoming song."""
    if head is None or not len(head.db):
        return 0.0, 0.0
    db = list(head.db)
    ref = _pct(db, 0.95)
    silent = max(FLOOR_DB, ref - SILENCE_BELOW)
    first = next((i for i, v in enumerate(db) if v > silent), None)
    if first is None:
        return 0.0, 0.0
    sm = _smooth(db)
    body = next((i for i in range(first, len(db)) if sm[i] >= ref - BODY_BELOW), first)
    return head.start + first * HOP, head.start + body * HOP


def _pct(xs, q):
    s = sorted(xs)
    return s[min(len(s) - 1, int(q * len(s)))]


def _smooth(db):
    """Power average over SMOOTH readings, centred."""
    p = [10 ** (v / 10) for v in db]
    h = SMOOTH // 2
    out = []
    for i in range(len(p)):
        w = p[max(0, i - h):i + h]
        out.append(10 * math.log10(sum(w) / len(w) + 1e-12))
    return out


# ---- measuring ---------------------------------------------------------------------------------
def measure(src, start, length):
    """Loudness of src (an engine.Source; its stems summed) from start for length seconds."""
    sr = src.sr
    first = max(0, int(start * sr))
    src.seek(first)
    hop = max(1, int(HOP * sr))
    want = int(length * sr)
    chunks, got = [], 0
    while got < want:
        block = src.read(min(65536, want - got))
        if block.shape[1] == 0:
            break
        chunks.append(block.sum(axis=0))
        got += block.shape[1]
    if not chunks:
        return Envelope(first / sr, [])
    x = np.concatenate(chunks)
    n = len(x) // hop
    power = (x[:n * hop].reshape(n, hop, 2) ** 2).mean(axis=(1, 2))
    return Envelope(first / sr, (10 * np.log10(power + 1e-12)).astype(float).tolist())


def consecutive(a, b):
    """True when b follows a on the same album (track after track), which stays gapless."""
    if not a or not b or not a.get("albumKey") or a.get("albumKey") != b.get("albumKey"):
        return False
    da, db = a.get("disc") or 1, b.get("disc") or 1
    ia, ib = a.get("index") or 0, b.get("index") or 0
    return (da == db and ib == ia + 1) or (db == da + 1 and ib == 1)
