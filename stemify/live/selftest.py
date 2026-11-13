"""Headless check of the live path: nothing reaches the speakers.

    python -m stemify.live.selftest --seconds 20            # passthrough latency + glitch check
    python -m stemify.live.selftest --seconds 600           # the 10-minute xrun soak
    python -m stemify.live.selftest --separator demucs --music song.flac --seconds 120

A private "Stemify Live Selftest" sink feeds the engine, which plays into a silent null sink
instead of the speakers. A harness process (its own JACK client) plays clicks over low noise
into the Live sink and records, in one stream so they share every sample clock:
    channel 0: the selftest output's monitor (what the engine played)
    channel 1: the Live sink's monitor (what went in)
The delay between them is the engine's real input-to-output latency in the graph. In
passthrough the output must equal the input delayed by exactly that much, so every block that
doesn't is a glitch (a dropout, an xrun, a dry/wet seam). With a real separator the output
isn't the input, so the delay comes from cross-correlation and the engine's own counters
(xruns, dry blocks, late and missed hops) tell whether it kept up.
"""

import argparse
import json
import os
import subprocess
import sys
import time

import numpy as np

from . import sink as sinkmod

RATE = 48000
OUT_SINK = ("stemify_selftest_out", "Stemify Selftest Out")
LIVE_SINK = ("stemify_live_selftest", "Stemify Live Selftest")
HARNESS = "stemify_selftest"
CLICK_EVERY = RATE // 4


def harness_signal(n, start, rng):
    """Low noise with a full-scale click every CLICK_EVERY frames (absolute frame count)."""
    x = (rng.standard_normal((n, 2)) * 0.02).astype(np.float32)
    idx = np.arange(start, start + n)
    x[idx % CLICK_EVERY == 0] = 0.9
    return x


def run_harness(seconds, out, music=""):
    """The recording side, run as its own process (its own JACK client)."""
    import sounddevice as sd

    from .stream import _jack_device, _set_jack_client_name

    sd._terminate()
    _set_jack_client_name(sd, HARNESS)
    sd._initialize()
    dev_rec = _jack_device(sd, OUT_SINK[1], need_in=2)
    dev_play = _jack_device(sd, LIVE_SINK[1], need_out=2)
    total = int(seconds * RATE)
    rec = np.zeros((total + RATE, 2), np.float32)
    state = {"n": 0, "w": 0, "xruns": 0}
    rng = np.random.default_rng(1)
    song = None
    if music:
        from ..worker.audio_io import load_audio

        song = np.ascontiguousarray(load_audio(music, RATE, 2).T)

    def cb(indata, outdata, frames, t, status):
        if status:
            state["xruns"] += 1
        if song is None:
            outdata[:] = harness_signal(frames, state["n"], rng)
        else:
            idx = np.arange(state["n"], state["n"] + frames) % len(song)
            outdata[:] = song[idx]
        state["n"] += frames
        w = state["w"]
        k = min(frames, len(rec) - w)
        rec[w:w + k] = indata[:k]
        state["w"] = w + k

    s = sd.Stream(device=(dev_rec, dev_play), channels=2, samplerate=RATE, dtype="float32",
                  blocksize=0, latency="low", callback=cb)
    s.start()
    # Channel 1 records the Live sink's input side instead of the selftest output's right.
    time.sleep(0.3)
    subprocess.run(["pw-link", "-d", f"{OUT_SINK[0]}:monitor_FR", f"{HARNESS}:in_1"], check=True)
    subprocess.run(["pw-link", f"{LIVE_SINK[0]}:monitor_FL", f"{HARNESS}:in_1"], check=True)
    relinked_at = state["w"]
    time.sleep(seconds)
    s.stop()
    s.close()
    np.savez(out, rec=rec[:state["w"]], start=relinked_at + RATE // 10, xruns=state["xruns"])


def correlate_lag(rec, start, max_lag=2 * RATE, span=10 * RATE):
    """Delay of channel 0 behind channel 1 by cross-correlation, in frames."""
    from scipy.signal import correlate

    ref = rec[start:start + span, 1]
    got = rec[start:start + span + max_lag, 0]
    if len(got) < span + max_lag or not np.any(ref):
        return None
    c = correlate(got, ref, mode="valid", method="fft")  # c[k]: got shifted by k
    return int(np.argmax(c))


def analyse(rec, start):
    """(latency frames, glitch blocks, blocks checked) from the harness recording."""
    got, ref = rec[start:, 0], rec[start:, 1]
    # Clicks are sparse and full scale: line up the first few for the delay.
    ref_clicks = np.flatnonzero(ref > 0.5)
    got_clicks = np.flatnonzero(got > 0.5)
    if not len(ref_clicks) or not len(got_clicks):
        return None, None, 0
    lag = int(got_clicks[0] - ref_clicks[0])
    if lag < 0:
        lag += CLICK_EVERY * int(np.ceil(-lag / CLICK_EVERY))
    a, b = got[lag:], ref[:len(ref) - lag]
    n = min(len(a), len(b))
    blocks = n // 256
    if not blocks:
        return lag, 0, 0
    diff = np.abs(a[:blocks * 256] - b[:blocks * 256]).reshape(blocks, 256).max(axis=1)
    return lag, int((diff > 1e-4).sum()), blocks


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--quantum", type=int, default=256)
    ap.add_argument("--separator", choices=["passthrough", "demucs"], default="passthrough")
    ap.add_argument("--music", default="", help="audio file to play in (default: clicks over noise)")
    ap.add_argument("--context", type=float, default=None)
    ap.add_argument("--hop", type=float, default=0.2)
    ap.add_argument("--lookahead", type=float, default=0.1)
    ap.add_argument("--precision", choices=["fp16", "fp32"], default="fp16")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--harness", default="", help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    if args.harness:
        run_harness(args.seconds, args.harness, args.music)
        return

    from .stream import LiveEngine, Passthrough

    sys.setswitchinterval(0.0005)  # as the live runner does: the callback never waits long for the GIL
    if args.separator == "demucs":
        from .separator import DemucsSeparator

        sep = DemucsSeparator(device=args.device, fp16=args.precision == "fp16", context=args.context,
                              hop=args.hop, lookahead=args.lookahead)
    else:
        sep = Passthrough(args.quantum)

    tmp = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), f"stemify-selftest-{os.getpid()}.npz")
    env = dict(os.environ, PIPEWIRE_LATENCY=f"{args.quantum}/{RATE}")
    os.environ["PIPEWIRE_LATENCY"] = env["PIPEWIRE_LATENCY"]
    with sinkmod.VirtualSink(*OUT_SINK, latency=args.quantum):
        engine = LiveEngine(sep, output=OUT_SINK[0], quantum=args.quantum,
                            sink_name=LIVE_SINK[0], sink_description=LIVE_SINK[1],
                            client_name="stemify_selftest_engine")
        with engine:
            h = subprocess.run([sys.executable, "-m", "stemify.live.selftest", "--harness", tmp,
                                "--seconds", str(args.seconds), "--music", args.music], env=env)
            stats = engine.stats()
    if h.returncode:
        sys.exit("harness failed")
    data = np.load(tmp)
    os.remove(tmp)
    rec, start = data["rec"], int(data["start"])
    if args.separator == "passthrough":
        lag, glitches, blocks = analyse(rec, start)
    else:
        # Skip the first seconds: the separator starts on a window that's mostly silence.
        lag, glitches, blocks = correlate_lag(rec, start + 3 * RATE), 0, 0
    result = {
        "seconds": args.seconds, "quantum": args.quantum, "separator": args.separator,
        "measured_latency_ms": None if lag is None else round(lag / RATE * 1000, 2),
        "engine_delay_ms": round(stats["delay_ms"], 2),
        "glitch_blocks": glitches, "blocks_checked": blocks,
        "engine_xruns": stats["xruns"], "engine_dry_blocks": stats["dry_blocks"],
        "harness_xruns": int(data["xruns"]), "hops": stats["hops"], "late_hops": stats["late"],
        "missed_hops": stats["misses"], "compute_p50_ms": round(stats["compute_p50_ms"], 1),
        "compute_p99_ms": round(stats["compute_p99_ms"], 1), "compute_max_ms": round(stats["compute_max_ms"], 1),
        "events": stats["events"][:20],
    }
    print(json.dumps(result, indent=1))
    limit = 0.030 if args.separator == "passthrough" else (engine.delay / RATE + 0.030)
    ok = lag is not None and glitches == 0 and stats["xruns"] == 0 and lag / RATE < limit
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
