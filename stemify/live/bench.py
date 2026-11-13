"""Phase 0: how long does one separation pass take on each device?

    python -m stemify.live.bench --device cuda:0 --windows 3 5 7.8 --precision fp32 fp16
    python -m stemify.live.bench --device cuda:1 --check          # the iGPU, verified vs the CPU
    python -m stemify.live.bench --device cuda:0 --load cuda:0    # with a GPU hog running

Each configuration is warmed up (MIOpen tunes a fixed shape once), then timed for --runs passes
or --max-seconds, whichever comes first. Rows print as a markdown table, and --out appends them
to a file.

Columns: p50/p99/max are milliseconds for one forward pass of batch 1. "Live hop" is the
smallest hop the device sustains: p99, since a pass has to finish within one hop. "Offline xRT"
is how fast it would stem a whole song this way (25% overlap, batch 1); the real offline
backend batches 8 segments and runs faster on a big GPU.
"""

from stemify.live import rocm

rocm.prepare_env()

import argparse
import os
import subprocess
import sys
import time

import numpy as np
import torch

from stemify.live.model import forward, load_model

SR = 44100


def device_name(dev):
    if dev.type == "cpu":
        return f"CPU ({torch.get_num_threads()} threads)"
    p = torch.cuda.get_device_properties(dev)
    return f"{p.name} ({p.gcnArchName})"


def sync(dev):
    if dev.type == "cuda":
        torch.cuda.synchronize(dev)


def test_signal(seconds, seed=0):
    """Deterministic music-like stereo: a kick, a bass line, chords, and noise hats."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    rng = np.random.default_rng(seed)
    beat = (t % 0.5)
    kick = np.sin(2 * np.pi * 55 * t * np.exp(-beat * 8)) * np.exp(-beat * 12)
    bass = 0.4 * np.sin(2 * np.pi * np.where((t % 2) < 1, 55, 73.4) * t)
    chord = sum(0.15 * np.sin(2 * np.pi * f * t) for f in (220, 277.2, 329.6))
    hats = 0.1 * rng.standard_normal(n) * np.exp(-((t + 0.25) % 0.5) * 40)
    left = kick + bass + chord + hats
    right = kick + bass + 0.8 * chord + np.roll(hats, 200)
    x = np.stack([left, right]).astype(np.float32)
    return torch.from_numpy(x / np.abs(x).max() * 0.5)


def sdr(ref, est):
    num = (ref ** 2).sum().item()
    den = ((ref - est) ** 2).sum().item()
    return 10 * np.log10(max(num, 1e-12) / max(den, 1e-12))


def check(model, dev, window, fp16):
    """Per-stem SDR of the device's output against the CPU in fp32: tells broken kernels
    (a few dB or NaN) from rounding (fp32 > 60 dB, fp16 ~ 30-50 dB)."""
    mix = test_signal(window)[None]
    model.cpu()
    ref = forward(model, mix)[0]
    model.to(dev)
    est = forward(model, mix.to(dev), fp16)[0].cpu()
    if not torch.isfinite(est).all():
        return {s: float("nan") for s in model.sources}
    return {s: sdr(ref[i], est[i]) for i, s in enumerate(model.sources)}


def bench(model, dev, window, fp16, runs, max_seconds, warmup):
    mix = test_signal(window)[None].to(dev)
    model.to(dev)
    for _ in range(warmup):
        forward(model, mix, fp16)
    sync(dev)
    if dev.type == "cuda":
        torch.cuda.reset_peak_memory_stats(dev)
    times = []
    t_end = time.perf_counter() + max_seconds
    for _ in range(runs):
        t0 = time.perf_counter()
        forward(model, mix, fp16)
        sync(dev)
        times.append((time.perf_counter() - t0) * 1000)
        if time.perf_counter() > t_end:
            break
    mem = torch.cuda.max_memory_allocated(dev) / 2 ** 20 if dev.type == "cuda" else 0.0
    return np.array(times), mem


def burn(dev_str):
    """GPU hog standing in for a game: back-to-back fp16 matmuls until killed."""
    dev = torch.device(dev_str)
    a = torch.randn(4096, 4096, device=dev, dtype=torch.float16)
    while True:
        for _ in range(20):
            a = (a @ a).clamp_(-1, 1)
        sync(dev)


HEADER = ("| Device | Model | Window s | Precision | Load | Runs | p50 ms | p99 ms | max ms "
          "| Live hop ms | Offline xRT | Peak MiB | SDR vs CPU dB |\n"
          "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", default="cuda:0", help="cuda:0, cuda:1, cpu")
    ap.add_argument("--models", nargs="+", default=["htdemucs"])
    ap.add_argument("--windows", nargs="+", type=float, default=[3.0, 5.0, 7.8])
    ap.add_argument("--precision", nargs="+", choices=["fp32", "fp16"], default=["fp32", "fp16"])
    ap.add_argument("--runs", type=int, default=500)
    ap.add_argument("--max-seconds", type=float, default=90.0, help="per configuration")
    ap.add_argument("--warmup", type=int, default=5)
    ap.add_argument("--threads", type=int, default=0, help="CPU threads (0: torch default)")
    ap.add_argument("--load", default="", help="device to run a GPU hog on while timing")
    ap.add_argument("--check", action="store_true", help="also compare output with the CPU")
    ap.add_argument("--out", default="", help="append the table rows to this file")
    ap.add_argument("--burn", default="", help=argparse.SUPPRESS)
    args = ap.parse_args(argv)

    if args.burn:
        burn(args.burn)
        return
    if args.threads:
        torch.set_num_threads(args.threads)
    dev = torch.device(args.device)
    rocm.setup_device(dev)
    if dev.type == "cpu":
        args.precision = ["fp32"]
    print(f"# {device_name(dev)}, torch {torch.__version__}", flush=True)
    hog = None
    if args.load:
        hog = subprocess.Popen([sys.executable, "-m", "stemify.live.bench", "--burn", args.load])
        time.sleep(8)  # let it start hammering
        if hog.poll() is not None:
            sys.exit("GPU hog failed to start")
    load = f"hog on {args.load}" if args.load else "idle"
    rows = []
    print(HEADER, flush=True)
    try:
        for name in args.models:
            for window in args.windows:
                model = load_model(name, window)
                for prec in args.precision:
                    fp16 = prec == "fp16"
                    quality = ""
                    if args.check:
                        q = check(model, dev, window, fp16)
                        quality = " / ".join(f"{s[0]}{v:.0f}" for s, v in q.items())
                    t, mem = bench(model, dev, window, fp16, args.runs, args.max_seconds, args.warmup)
                    p50, p99, mx = np.percentile(t, 50), np.percentile(t, 99), t.max()
                    xrt = 0.75 * window / (p50 / 1000)
                    row = (f"| {device_name(dev)} | {name} | {window:g} | {prec} | {load} | {len(t)} "
                           f"| {p50:.1f} | {p99:.1f} | {mx:.1f} | {p99:.0f} | {xrt:.1f} | {mem:.0f} | {quality} |")
                    rows.append(row)
                    print(row, flush=True)
                model.cpu()
                del model
                if dev.type == "cuda":
                    torch.cuda.empty_cache()
    finally:
        if hog is not None:
            hog.kill()
            hog.wait()
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        new = not os.path.exists(args.out)
        with open(args.out, "a") as f:
            if new:
                f.write(HEADER + "\n")
            f.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
