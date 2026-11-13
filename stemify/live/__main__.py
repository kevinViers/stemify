"""Run Stemify Live from a terminal.

    python -m stemify.live                       # htdemucs, fp16, on the 9070 XT, to the default sink
    python -m stemify.live --gains 1 1 1 0       # drums bass other vocals: karaoke
    python -m stemify.live --separator passthrough

Pick "Stemify Live" as the output in any app (or pavucontrol). Ctrl+C stops it and removes the
sink. Stats print every few seconds.
"""

from . import rocm

rocm.prepare_env()

import argparse
import os
import signal
import sys
import time


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--separator", choices=["demucs", "passthrough"], default="demucs")
    ap.add_argument("--model", default="htdemucs")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--precision", choices=["fp16", "fp32"], default="fp16")
    ap.add_argument("--context", type=float, default=None,
                    help="seconds of past audio per window (default: fill to 7.8 s)")
    ap.add_argument("--hop", type=float, default=0.2, help="seconds")
    ap.add_argument("--lookahead", type=float, default=0.1, help="seconds")
    ap.add_argument("--quantum", type=int, default=256, help="frames per graph cycle")
    ap.add_argument("--output", default="", help="PipeWire sink to play to (default: the default sink)")
    ap.add_argument("--gains", type=float, nargs="+", help="one per stem")
    args = ap.parse_args(argv)

    # The graph runs at the smallest quantum any client asks for; ask for ours before PortAudio
    # creates its JACK client. A short GIL switch interval keeps the audio callback from waiting
    # behind the separator thread's Python code.
    os.environ.setdefault("PIPEWIRE_LATENCY", f"{args.quantum}/48000")
    sys.setswitchinterval(0.0005)

    from .stream import LiveEngine, Passthrough

    if args.separator == "demucs":
        from .separator import DemucsSeparator

        sep = DemucsSeparator(args.model, args.device, args.precision == "fp16", args.context,
                              args.hop, args.lookahead)
    else:
        sep = Passthrough(args.quantum)
    engine = LiveEngine(sep, output=args.output or None, quantum=args.quantum)
    if args.gains:
        engine.set_gains(args.gains)
    signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
    print(f"Loading {args.separator}...", flush=True)
    with engine:
        print(f"Stemify Live is up: stems {sep.stems}, delay {engine.delay / 48:.0f} ms. Ctrl+C to stop.",
              flush=True)
        try:
            while True:
                time.sleep(5)
                s = engine.stats()
                print(f"[{s['seconds']:6.0f}s] hops {s['hops']}  late {s['late']}  missed {s['misses']}  "
                      f"dry blocks {s['dry_blocks']}  xruns {s['xruns']}  "
                      f"compute p50 {s['compute_p50_ms']:.0f} ms  max {s['compute_max_ms']:.0f} ms", flush=True)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
