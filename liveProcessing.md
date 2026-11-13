# Stemify Live: Low-Latency Universal Input Design

Oct 25, 2026 · @kevin

## Goal and scope

Stemify Live is a virtual output device named "Stemify Live". Any app that plays into it gets split into stems that can be muted, soloed or rebalanced live, with the smallest delay we can get. The first target is this one Linux desktop. Windows and other machines come later.

We build it in tiers. Tier A should reach about 0.5–1 s of delay with near-offline quality. Tier B aims for under 100 ms using a small streaming model trained on this library's own stems.

**In scope**

- A PipeWire virtual sink that apps can choose like any speaker
- Streaming separation on the RX 9070 XT, with stems mixed back out to the real speakers
- Music sources first (Plexamp, Spotify, browser players). Video and games are a bonus: reporting the sink's delay for lip sync is optional, done last
- A live mode in Stemify Player that reuses its faders and spectrum display

**Out of scope for now**

- Windows (WASAPI loopback) and macOS
- Recording or exporting the live stems
- Running on CPU only

## Target setup

The machine has a fast GPU, a ROCm build of torch, and PipeWire. The defaults to watch are the 1024-sample buffer and the fact that the GPU is shared with games.

| Part | What's there | Why it matters |
| --- | --- | --- |
| GPU | AMD Radeon RX 9070 XT (Navi 48, RDNA4). A Radeon 610M iGPU is also present | Inference runs here through ROCm. Games share it, which can cause latency spikes |
| CPU | Ryzen 7 9800X3D, 16 threads | Handles audio threads, resampling and mixing |
| Torch | torch 2.14.1 from the `pytorch-rocm` (rocm7.2) index, per `pyproject.toml` | No CUDA. Kernel tuning goes through MIOpen |
| Audio server | PipeWire 1.6.9 with the Pulse layer. Fixed rate 48 kHz, float32 stereo | Capture and playback stay at 48 kHz. Models trained at 44.1 kHz need streaming resampling |
| Buffer size | `clock.quantum` 1024 (21 ms), `min-quantum` 32 | Lower it to 128–256 for Stemify Live's nodes only |
| Default output | `alsa_output.pci-0000_0e_00.6.analog-stereo` | The live mix plays here |

**Existing code to reuse**

- `stemify/worker/backends.py`: `DemucsBackend` loads models and runs batched segments. Reuse the model loading. The streaming loop is new.
- `stemify/player/engine.py`: the PortAudio callback with per-stem gain, mute and solo. Reuse it as the live mixer.
- `stemify/player/qml/StemPlayer/StemChart.qml`: the per-stem spectrum display.
- The library of stems already separated offline. It is training and test data for Tier B.

**Constraint for the implementing agent:** don't open GUI windows on the main monitor while testing. Test headless (offscreen Qt, scripted audio).

## Latency budget

Most of the delay comes from how far ahead the model has to hear, not from GPU speed. The total is roughly:

```latex
L = q_{in} + R + F + H + C + q_{out}
```

In that formula, q is the PipeWire buffer on each side, R is resampler delay, F is lookahead (future audio the model must see before it can output a sample), H is the hop size, and C is compute time for one hop. C must stay below H, or the stream falls behind.

| Term | Tier A target (streaming htdemucs) | Tier B target (small streaming model) |
| --- | --- | --- |
| q\_in + q\_out | 2 × 256 samples = 11 ms | 2 × 128 samples = 5 ms |
| R (48 ↔ 44.1 kHz) | \~2 ms | \~2 ms, or 0 if the model is trained at 48 kHz |
| F (lookahead) | 300–500 ms | 0–20 ms |
| H (hop) | 100 ms | 10 ms |
| C (compute per hop) | ≤ 80 ms, to be measured | ≤ 8 ms, to be measured |
| **Total** | **\~0.5–0.7 s** | **\~25–45 ms** |

These are planning targets, not measurements. Phase 0 measures C on this GPU, and F gets tuned by checking quality against offline separation. For reference, about 20 ms feels instant, under 100 ms works for casual listening, and for music-only listening even 0.5 s is only noticeable when you press play or skip.

## Architecture

Ring buffers separate the real-time audio thread from the GPU thread. A slow inference step causes a brief dry passage instead of a click.

&#91;embedded content: Stemify Live signal path · audio thread and GPU thread decoupled by ring buffers\]

The capture and mixer callbacks run on the PipeWire clock and only copy and scale numpy arrays. The separator thread pulls a window from the capture buffer, runs the model, and pushes one hop per stem into the stem buffers. If a hop isn't ready in time, the mixer plays the delayed dry mix for it.

## Model strategy

Lower delay beats stem quality and stem count, so Live doesn't need to use the 6-stem model from offline mode. Ship Tier A first: it reuses htdemucs and stays close to offline quality at \~0.5 s. Run Tier B as a parallel research track, since only a model built for streaming gets under 100 ms.

**Tier A: htdemucs on a sliding window**

- **Model choice:** start with plain 4-stem `htdemucs`, a single model, rather than `htdemucs_6s` or the `htdemucs_ft` bag. Phase 0 also benchmarks smaller options (`hdemucs_mmi`, and htdemucs with fewer transformer layers or a shorter segment), then picks the fastest one whose quality is acceptable. Lower compute allows a smaller hop H, and that directly reduces delay.

* For every hop H, run the model on a window of P seconds of past audio, plus the current hop, plus F seconds of lookahead. Keep only the hop's output and crossfade it with the previous one.
* htdemucs was trained on \~7.8 s segments. Start with P = 5 s, F = 0.5 s, H = 100 ms, then sweep F down while measuring quality.
* Each hop re-runs a full window, so the GPU does \~10 full passes per second. Mitigations: fp16 where ROCm allows it, a fixed input shape so MIOpen tunes once, warmup at startup, and a single model rather than the 4-model `htdemucs_ft` bag.
* Quality check: SDR of live output against offline htdemucs on the same tracks, per stem. Pass bar: within 1 dB of offline at the chosen F.

**Tier B: a small streaming model, distilled from this library**

- Architecture candidates: HS-TasNet (published as a low-latency music separator, \~23 ms) or a causal Demucs-style conv/LSTM model. Before planning around existing code or weights, check whether they're actually available.
- Training data: the library of mixes plus their offline stems. Use the offline htdemucs or RoFormer stems as teacher targets. MUSDB18-HQ is the held-out test set.
- Train at 48 kHz to remove resampling. Training runs locally on the 9070 XT and doesn't need to finish quickly.
- Expect worse quality than Tier A, especially vocals vs. other. The UI lets users pick a tier ("Tight" or "Clean").

**Rejected**

- Offline-style whole-track separation: needs the entire song before it can start.
- RoFormer models on a sliding window: higher quality offline, but far heavier per pass. Revisit only if Phase 0 shows lots of spare compute.

## Implementation plan

Put the new code in a `stemify/live/` package and build it in six phases. Phase 0 is a measurement gate: its numbers set the window and hop sizes for everything after it.

1. **Phase 0: Benchmark (`stemify/live/bench.py`)**
   - Time a single htdemucs forward pass on the 9070 XT for window lengths 3, 5 and 7.8 s, in fp32 and fp16, after warmup. Report p50, p99 and max over 500 runs, both with the GPU idle and with a GPU load running.
   - Done when: a results table is committed under `docs/` and gives a recommended P, H and precision.
2. **Phase 1: Audio plumbing, passthrough only (`sink.py`, `stream.py`)**
   - Create the "Stemify Live" sink at startup (null sink or `pw-loopback`, with `node.latency` set to 256/48000) and remove it on exit.
   - Capture its monitor and play to the default output through lock-free ring buffers. The audio callback never waits on the model.
   - Done when: round-trip passthrough measures under 30 ms with an impulse test, and there are zero xruns over a 10-minute run.
3. **Phase 2: Tier A streaming separation (`separator.py`)**
   - Inference runs on its own thread: resample to 44.1 kHz, run the sliding window, crossfade the hop, resample back.
   - If inference misses a deadline, output the dry mix for that hop instead of silence, and log it.
   - Done when: SDR is within 1 dB of offline on 5 test tracks, there are zero dropouts over 30 minutes, and measured end-to-end latency matches the budget.
4. **Phase 3: Live mode in Stemify Player**
   - Add a "Live" source that feeds stems into the existing mixer and spectrum. Show the current latency and miss count.
   - Done when: the headless QML test passes and faders change the output within one hop.
5. **Phase 4: Tier B research (`stemify/live/train/`)**
   - Build a dataset from the library, train a streaming model, and export it with a fixed-shape step function.
   - Done when: it's under 50 ms end to end and the per-stem SDR is reported against Tier A.
6. **Phase 5 (optional): Latency reporting for video**
   - Report the sink's delay to PipeWire so players that read it can keep lip sync.

## Risks and open questions

The biggest risk is GPU contention: a game on the same 9070 XT can push compute time past the hop and cause dropouts.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Games share the GPU | Missed hops, dry-mix fallbacks | Measure under load in Phase 0. Raise the GPU queue priority if ROCm allows it. Larger H as a fallback setting |
| ROCm/MIOpen jitter on RDNA4 | p99 compute spikes even when the GPU is idle | Fixed shapes, MIOpen tuning cached to disk, budget for p99 rather than p50 |
| Python GIL stalls the audio callback | Clicks | Keep the callback numpy-only. Torch runs on its own thread, or in a subprocess if needed |
| Short lookahead hurts htdemucs quality | Bleed between stems | Sweep F in Phase 2 and let the user trade delay for quality |
| Tier B quality is too low to be useful | Under 100 ms may not be reachable | Tier A remains the shipped mode |

**Open questions**

- Does HS-TasNet have usable public code or weights, or does it need a reimplementation?
- Is the library big enough for distillation? Count tracks and hours before Phase 4.
- Should the sink be the system default while Live is on, or picked per app (for example with `pavucontrol`)?
- Decided: Live defaults to 4 stems (drums, bass, other, vocals) from the fastest acceptable model. 6 stems is only an opt-in if Phase 0 shows spare compute.
