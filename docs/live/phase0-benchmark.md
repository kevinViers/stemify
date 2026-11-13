# Stemify Live, Phase 0: benchmark

Measured 2026-10-25 with `python -m stemify.live.bench` (see `stemify/live/bench.py`).
torch 2.14.1+rocm7.2, demucs from git, PipeWire 1.6.9. One forward pass, batch 1, after
warmup; 500 runs per row on the 9070 XT.

Column guide: **Live hop** is p99, the smallest hop the device can keep up with. **Offline
xRT** is how fast it would stem a whole song one window at a time (25% overlap, batch 1); the
real offline backend batches 8 windows and is faster on the 9070 XT. **SDR vs CPU** compares the
output with the same model in fp32 on the CPU, per stem (d/b/o/v). Above ~60 dB is rounding, ~20
to 60 dB is fp16 precision, NaN means broken.

## RX 9070 XT (gfx1201), GPU idle

| Model | Window s | Precision | p50 ms | p99 ms | max ms | Offline xRT | Peak MiB | SDR vs CPU dB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| htdemucs | 3 | fp32 | 65.3 | 66.0 | 66.0 | 34.5 | 335 | d119 / b130 / o126 / v88 |
| htdemucs | 3 | fp16 | 42.8 | 43.2 | 43.3 | 52.6 | 330 | d51 / b63 / o58 / v24 |
| htdemucs | 5 | fp32 | 108.2 | 109.9 | 117.4 | 34.6 | 428 | d109 / b130 / o117 / v85 |
| htdemucs | 5 | fp16 | 63.1 | 64.7 | 64.9 | 59.4 | 418 | d47 / b64 / o56 / v21 |
| htdemucs | 7.8 | fp32 | 175.0 | 180.4 | 462.1 | 33.4 | 561 | d97 / b129 / o104 / v84 |
| htdemucs | 7.8 | fp16 | 98.7 | 100.4 | 101.9 | 59.3 | 545 | d49 / b65 / o57 / v20 |
| hdemucs_mmi | 3 | fp32 | 58.3 | 61.7 | 62.5 | 38.6 | 492 | d111 / b123 / o125 / v105 |
| hdemucs_mmi | 3 | fp16 | 45.5 | 47.3 | 47.5 | 49.4 | 480 | NaN |
| hdemucs_mmi | 5 | fp32 | 86.3 | 87.3 | 87.6 | 43.5 | 582 | d107 / b127 / o113 / v103 |
| hdemucs_mmi | 5 | fp16 | 65.1 | 66.5 | 66.9 | 57.6 | 562 | NaN |
| hdemucs_mmi | 7.8 | fp32 | 124.0 | 127.5 | 130.6 | 47.2 | 705 | d110 / b126 / o118 / v101 |
| hdemucs_mmi | 7.8 | fp16 | 93.5 | 94.7 | 97.7 | 62.6 | 668 | NaN |

Notes:

- Compute grows about linearly with window length, so a shorter window buys a smaller hop.
  htdemucs pads every input up to its 7.8 s training segment unless `use_train_segment` is off;
  `stemify.live.model.load_model` turns it off for shorter windows.
- fp16 is 1.6–1.8x faster for htdemucs. It moves vocals furthest from fp32 (about 20 dB SDR
  against the fp32 output); whether that's audible is checked against real music in Phase 2.
- hdemucs_mmi overflows to NaN in fp16, and in fp32 it's slower than htdemucs in fp16. It's out.
- Jitter on an idle GPU is small: p99 is within 3% of p50. The one 462 ms max (7.8 s fp32) was
  a single stall in 500 runs.

## Other devices

| Device | Model | Window s | p50 ms | p99 ms | Offline xRT | Output |
| --- | --- | --- | --- | --- | --- | --- |
| Ryzen 7 9800X3D CPU, 8 threads | htdemucs | 7.8 | 641 | 661 | 9.1 | correct |
| Ryzen 7 9800X3D CPU, 8 threads | htdemucs | 3 | 142 | 143 | 15.9 | correct |
| Radeon 610M iGPU (gfx1036) | htdemucs | 7.8 | 2106 | 2134 | 2.8 | **wrong** |

**The iGPU can't run separation.** The ROCm torch wheels have no kernels for gfx1036, so any
use crashes the process. Running it as gfx1030 (`HSA_OVERRIDE_GFX_VERSION_<node>=10.3.0`, set
per node by `stemify.live.rocm` so the 9070 XT is untouched) makes it run, but with wrong
answers: complex FFTs, reductions over a few elements, and some MIOpen convolutions return
garbage. Moving the FFTs to the CPU fixed the first two, but convolutions still broke under
every MIOpen solver setting tried (default, no direct, no implicit GEMM, MIOpen off). Even if it
worked, at 2.1 s per 7.8 s window it's 3.3x slower than the CPU. It has 2 CUs against the
9070 XT's 64.

**The CPU is the usable second device.** At about 9x realtime it can stem songs offline without
touching the GPU a game is using, and a 3 s window in 142 ms means a CPU-only live mode with a
~250 ms hop is plausible (not in scope yet).

## GPU under load

A stand-in for a game: a second process running back-to-back fp16 4096x4096 matmuls on the
same GPU (`--load cuda:0`). This is close to the worst case, since it saturates the same compute
queues; a real game is mostly graphics work and may share better. That still has to be measured
with a real game.

| Device | Model | Window s | Precision | Load | p50 ms | p99 ms | max ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RX 9070 XT | htdemucs | 7.8 | fp16 | idle | 98.7 | 100.4 | 101.9 |
| RX 9070 XT | htdemucs | 7.8 | fp16 | GPU hog | 370.8 | 378.6 | 379.4 |
| CPU, 8 threads | htdemucs | 7.8 | fp32 | GPU hog | 790 | 800 | 800 |

Under the hog one pass takes 3.8x longer, so a 200 ms hop can't keep up and Live plays dry until
the load stops. A high-priority HIP stream made no difference (p99 383 ms vs 386 ms): stream
priority only orders queues inside one process. The CPU is barely affected.

## Recommendation

- **Model and precision:** htdemucs, single model, fp16. hdemucs_mmi is out (NaN in fp16, and
  slower in fp32).
- **Window:** the full 7.8 s training segment. Phase 2 measured shorter windows as clearly worse
  (5 s cost about 1.3 dB on drums), so context fills whatever the hop and lookahead leave:
  P = 7.8 - H - F - 0.01 s.
- **Hop:** H = 200 ms. A 7.8 s fp16 pass is about 100 ms on the GPU alone and 115 ms with
  resampling and copies, so 100 ms hops don't fit; 200 ms gives 40% headroom.
- **Lookahead:** F = 100 ms (Phase 2: quality barely changes between 100 and 500 ms).
- **Resulting delay:** H + F + crossfade + budget (one hop) + one quantum = 515 ms.
- **Open risk:** GPU contention from games (above). Fallbacks to build: an automatic switch to a
  bigger hop when compute p99 nears the budget, and a CPU mode with a ~1 s hop.
