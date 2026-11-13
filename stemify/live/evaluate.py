"""Phase 2 quality check: streaming htdemucs vs offline htdemucs, on tracks from the library.

    python -m stemify.live.evaluate --tracks 5 --lookahead 0.5 0.3 0.2 0.1

For each track, an excerpt is separated three ways:
  - offline: htdemucs over the whole excerpt (plus margin), overlap 0.25, fp32, as Stemify does
  - live: the exact hop-by-hop path Live plays (stream.HopRunner + DemucsSeparator)
  - truth: the library's own stems for the track (made offline by a stronger model, usually a
    RoFormer), folded to drums / bass / other / vocals; the best reference this machine has
SDR is global per stem, in dB. The pass bar is live within 1 dB of offline, both scored
against truth. "agree" is SDR of live against offline directly.
"""

from . import rocm

rocm.prepare_env()

import argparse
import hashlib
import json
import os
import time

import numpy as np
import torch
from scipy.signal import resample_poly

from ..worker.audio_io import load_audio
from .separator import DOWN, RATE, UP, DemucsSeparator
from .stream import HopRunner

STEMS = ["drums", "bass", "other", "vocals"]
FOLD = {"guitar": "other", "piano": "other", "keys": "other", "synth": "other"}
DEFAULT_LIBRARY = "/var/mnt/unraid/Music/Stems"
SILENT_DB = -25.0
CACHE = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "stemify", "live-eval")


def sdr(ref, est):
    num = float((ref.astype(np.float64) ** 2).sum())
    den = float(((ref.astype(np.float64) - est) ** 2).sum())
    return 10 * np.log10(max(num, 1e-10) / max(den, 1e-10))


def resolve_source(path):
    """Sources stemmed by the stemd container are recorded as /music/...; that's musicDir here."""
    if os.path.exists(path) or not path.startswith("/music/"):
        return path
    try:
        with open(os.path.expanduser("~/.config/stemify/settings.json")) as f:
            music_dir = json.load(f).get("musicDir", "")
    except (OSError, ValueError):
        return path
    return os.path.join(music_dir, path[len("/music/"):])


def pick_tracks(library, count, min_seconds):
    """[count] tracks spread over the library's artist folders, deterministic."""
    artists = sorted(d for d in os.listdir(library) if os.path.isdir(os.path.join(library, d)))
    step = max(1, len(artists) // (count * 2))
    picked = []
    for artist in artists[::step]:
        for root, dirs, files in os.walk(os.path.join(library, artist)):
            dirs.sort()
            if ".stemify.json" not in files:
                continue
            with open(os.path.join(root, ".stemify.json")) as f:
                meta = json.load(f)
            names = {s["name"] for s in meta.get("stems", [])}
            if not {"vocals", "drums", "bass"} <= names or meta.get("duration", 0) < min_seconds:
                continue
            meta["source"] = resolve_source(meta.get("source", ""))
            picked.append((root, meta))
            break
        if len(picked) == count:
            break
    return picked


def truth_stems(root, meta, start, n):
    """Library stems at 48 kHz, folded to STEMS: (4, n, 2); n None for all of it."""
    parts = []
    for s in meta["stems"]:
        name = FOLD.get(s["name"], s["name"])
        if name not in STEMS:
            name = "other"
        audio = load_audio(os.path.join(root, s["file"]), RATE, 2)
        parts.append((name, audio[:, start:None if n is None else start + n].T))
    length = min(len(a) for _, a in parts) if n is None else n
    out = np.zeros((len(STEMS), length, 2), np.float32)
    for name, audio in parts:
        out[STEMS.index(name), :min(len(audio), length)] += audio[:length]
    return out


def offline_stems(mix44, device):
    """Whole-excerpt htdemucs at 44.1 kHz, fp32, normalized over the excerpt like demucs."""
    from demucs.apply import apply_model
    from demucs.pretrained import get_model

    model = get_model("htdemucs").eval()
    wav = torch.from_numpy(mix44)
    ref = wav.mean(0)
    mean, std = ref.mean(), ref.std() + 1e-8
    with torch.inference_mode():
        est = apply_model(model, ((wav - mean) / std)[None], shifts=0, split=True, overlap=0.25,
                          device=device)[0]
    est = (est * std + mean).numpy()  # (4, 2, m)
    assert list(model.sources) == STEMS
    return est


def live_stems(sep, mix48, start, n, batch=16):
    """The live path over mix48[:, start:start + n]: (4, n, 2)."""
    runner = HopRunner(sep)
    hop, ctx = sep.hop, sep.context
    positions = list(range(start, start + n, hop))
    padded = np.pad(mix48, ((0, 0), (ctx, runner.n)))  # positions shift by ctx
    out = np.zeros((len(STEMS), len(positions) * hop, 2), np.float32)
    for i in range(0, len(positions), batch):
        group = positions[i:i + batch]
        windows = np.stack([padded[:, p:p + runner.n].T for p in group])
        ests = sep.process_many(windows)
        for j, est in enumerate(ests):
            k = (i + j) * hop
            out[:, k:k + hop] = runner.run(est)
    return out[:, :n]


def load_case(root, meta, start, n, device):
    """(mix at 48 kHz, offline stems (4, n, 2), truth stems (4, n, 2)), cached on disk since
    decoding from the network share and the offline pass dominate the run time."""
    key = hashlib.sha1(repr((meta["source"], meta.get("mtime_ns"), meta.get("finished"), start, n)).encode()).hexdigest()[:16]
    path = os.path.join(CACHE, key + ".npz")
    if os.path.exists(path):
        d = np.load(path)
        return d["mix"], d["offline"], d["truth"]
    truth = truth_stems(root, meta, 0, None)
    if os.path.exists(meta["source"]):
        mix48 = load_audio(meta["source"], RATE, 2)
    else:
        # Source not reachable from here (stemmed in the container): the stems add up to a mix
        # that's as good a test input, and then truth sums exactly to what was separated.
        mix48 = np.ascontiguousarray(truth.sum(0).T)
    # Offline sees the excerpt with 10 s either side, as it would inside the whole song.
    pad = 10 * RATE
    a, b = max(0, start - pad), min(mix48.shape[1], start + n + pad)
    a -= a % DOWN
    mix44 = resample_poly(mix48[:, a:b], UP, DOWN, axis=1).astype(np.float32)
    off = offline_stems(mix44, device)
    off48 = resample_poly(off, DOWN, UP, axis=-1)[..., start - a:start - a + n].transpose(0, 2, 1)
    off48 = np.ascontiguousarray(off48, dtype=np.float32)
    truth = np.ascontiguousarray(truth[:, start:start + n])
    mix = mix48[:, :start + n + 5 * RATE]  # live needs nothing past the excerpt plus lookahead
    os.makedirs(CACHE, exist_ok=True)
    np.savez(path, mix=mix, offline=off48, truth=truth)
    return mix, off48, truth


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--library", default=DEFAULT_LIBRARY)
    ap.add_argument("--tracks", type=int, default=5)
    ap.add_argument("--start", type=float, default=45.0, help="excerpt start, seconds into the track")
    ap.add_argument("--excerpt", type=float, default=60.0, help="seconds")
    ap.add_argument("--context", type=float, nargs="+", default=[5.0])
    ap.add_argument("--window", type=float, default=0.0,
                    help="if set, context = window - hop - lookahead - xfade (overrides --context)")
    ap.add_argument("--hop", type=float, default=0.1)
    ap.add_argument("--lookahead", type=float, nargs="+", default=[0.5])
    ap.add_argument("--xfade", type=float, default=0.01)
    ap.add_argument("--precision", nargs="+", choices=["fp16", "fp32"], default=["fp16"])
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--out", default="", help="write results as JSON")
    args = ap.parse_args(argv)

    device = torch.device(args.device)
    tracks = pick_tracks(args.library, args.tracks, args.start + args.excerpt + 10)
    print(f"{len(tracks)} tracks; excerpt {args.excerpt:g}s from {args.start:g}s; hop {args.hop * 1000:g}ms",
          flush=True)
    if args.window:
        configs = [(p, round(args.window - args.hop - f - min(args.xfade, f), 4), f)
                   for p in args.precision for f in args.lookahead]
    else:
        configs = [(p, c, f) for p in args.precision for c in args.context for f in args.lookahead]
    label = {cfg: f"{cfg[0]} P={cfg[1]:g}s F={cfg[2]:g}s" for cfg in configs}
    results = []
    for root, meta in tracks:
        title = os.path.basename(root)
        t0 = time.time()
        start, n = int(args.start * RATE), int(args.excerpt * RATE)
        mix48, off48, truth = load_case(root, meta, start, n, device)
        row = {"track": title, "truth_model": meta.get("model"), "offline": {}, "live": {}, "level_db": {}}
        mix_e = float((truth.sum(0).astype(np.float64) ** 2).sum()) + 1e-10
        for i, s in enumerate(STEMS):
            row["offline"][s] = sdr(truth[i], off48[i])
            row["level_db"][s] = 10 * np.log10(float((truth[i].astype(np.float64) ** 2).sum()) / mix_e + 1e-12)
        print(f"\n{title}  ({meta.get('model')})", flush=True)
        print("  offline vs truth:      " + "  ".join(f"{s} {row['offline'][s]:5.2f}" for s in STEMS))
        for cfg in configs:
            prec, ctx, f = cfg
            sep = DemucsSeparator(device=args.device, fp16=prec == "fp16", context=ctx, hop=args.hop,
                                  lookahead=f, xfade=min(args.xfade, f))
            sep.warmup(1)
            live = live_stems(sep, mix48, start, n)
            sep.release()
            lv = row["live"][label[cfg]] = {s: {"truth": sdr(truth[i], live[i]), "agree": sdr(off48[i], live[i])}
                                            for i, s in enumerate(STEMS)}
            print(f"  {label[cfg]:22s} " + "  ".join(
                f"{s} {lv[s]['truth']:5.2f} ({lv[s]['truth'] - row['offline'][s]:+.2f}, agree {lv[s]['agree']:4.1f})"
                for s in STEMS), flush=True)
        print(f"  ({time.time() - t0:.0f}s)")
        results.append(row)

    print("\nMean over tracks (live minus offline, both vs truth; pass = no stem below -1 dB).")
    print(f"Stems more than {-SILENT_DB:g} dB below the mix (an instrumental's vocals) are left out.")
    summary = {}

    def mean(vals):
        return float(np.mean(vals)) if vals else float("nan")

    for cfg in configs:
        k = label[cfg]
        rows = {s: [r for r in results if r["level_db"][s] > SILENT_DB] for s in STEMS}
        d = {s: mean([r["live"][k][s]["truth"] - r["offline"][s] for r in rows[s]]) for s in STEMS}
        agree = {s: mean([r["live"][k][s]["agree"] for r in rows[s]]) for s in STEMS}
        summary[k] = {"delta": d, "agree": agree, "pass": all(v >= -1.0 for v in d.values())}
        print(f"  {k:22s} " + "  ".join(f"{s} {d[s]:+.2f}" for s in STEMS)
              + "   agree " + " ".join(f"{agree[s]:.1f}" for s in STEMS)
              + ("   PASS" if summary[k]["pass"] else "   FAIL"))
    if args.out:
        with open(args.out, "w") as fh:
            json.dump({"args": vars(args), "tracks": results, "summary": summary}, fh, indent=1)


if __name__ == "__main__":
    main()
