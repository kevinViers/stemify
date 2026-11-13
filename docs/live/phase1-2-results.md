# Stemify Live, Phases 1–2: results

Measured 2026-10-25 on the target machine with the GPU otherwise idle. Commands are in
`stemify/live/README.md`.

## Phase 1: passthrough plumbing — done

`python -m stemify.live.selftest` (private null sinks, nothing audible; 256-frame quantum).

| Run | Measured latency | Engine delay | Blocks compared | Glitch blocks | Xruns |
| --- | --- | --- | --- | --- | --- |
| 10 s | 21.33 ms | 16.0 ms | 1,852 | 0 | 0 |
| 10 min soak | 21.33 ms | 16.0 ms | 112,477 | 0 | 0 |

Both done-criteria met: under 30 ms, zero xruns over 10 minutes. The soak ran while the GPU
benchmark and quality runs were loading the machine. A glitch block is any 256-frame block that
differs from the input by more than 1e-4, so the check is sample-exact.

How it's wired: one PortAudio duplex stream on PipeWire's JACK layer (pipewire-jack), so capture
and playback run in the same graph cycle and the links are explicit. The measured 21.3 ms is the
16 ms engine delay plus one graph cycle. The speaker's own ALSA buffer comes on top of this,
and is the same for any app.

## Phase 2: streaming htdemucs — working, mostly done

### Quality

`python -m stemify.live.evaluate --tracks 5 --excerpt 30 --window 7.8 --hop 0.2 --lookahead ...`

The design asked for live within 1 dB of offline. There's no ground truth for library tracks, so
both are scored against the library's own stems (RoFormer `roformer_bs_sw`, folded to 4 stems),
and the difference is what's compared. For tracks whose source file isn't reachable from this
machine, the sum of those stems is the input mix. Mean over 5 tracks, live SDR minus offline SDR
in dB (vocals: the 3 tracks that have vocals):

| Window | Lookahead | Drums | Bass | Other | Vocals | Pass |
| --- | --- | --- | --- | --- | --- | --- |
| 7.8 s | 500 ms | -0.32 | -0.40 | -0.20 | +0.26 | yes |
| 7.8 s | 300 ms | -0.48 | -0.50 | -0.22 | +0.37 | yes |
| 7.8 s | 200 ms | -0.44 | -0.36 | -0.20 | +0.22 | yes |
| 7.8 s | 100 ms | -0.56 | -0.57 | -0.26 | +0.12 | yes |
| 5.6 s (1 track) | 500 ms | -1.26 | -0.29 | -0.32 | -0.58 | no |

- **Window length matters most.** htdemucs was trained on 7.8 s; a 5.6 s window cost 1.3 dB on
  drums. Context now fills the window out to 7.8 s.
- **Lookahead barely matters.** 100 ms loses about 0.2 dB against 500 ms and saves 400 ms of delay,
  so 100 ms is the default.
- **One track misses the bar on its own.** "My Old Ways" is 1.1–2.0 dB under offline on drums and
  bass at every lookahead; the bar passes on the mean, not per track.
- **fp16 is fine.** It moves output about 20 dB from fp32 (Phase 0), but live in fp16 matches
  offline in fp32 against the reference within the numbers above.

### Real time

`python -m stemify.live.selftest --separator demucs --music <file> --seconds 300`: 7.8 s window,
200 ms hop, 100 ms lookahead, fp16, real music in.

| Measured latency | Engine delay | Hops | Late / missed | Dry blocks | Xruns | Compute p50 / p99 / max |
| --- | --- | --- | --- | --- | --- | --- |
| 520.7 ms | 515.3 ms | 1,502 | 1 / 2 | 1 | 0 | 114.9 / 116.5 / 119.9 ms |

All the late and missed hops were in the first 0.4 s, while the stream was starting (the input
was still silent). After that, 5 minutes ran without a miss. Zero xruns means the Python audio
callback isn't held up by the separator thread (GIL switch interval set to 0.5 ms).

### Still open for Phase 2

- 30-minute dropout run, and a run with a real game on the GPU. Under a synthetic GPU hog a pass
  takes ~380 ms, past the 200 ms hop, and Live plays dry until the load ends (Phase 0 doc).
- Startup: skip separation until the first full window has real input, so the first hops don't
  count as misses.
- Speed: try `torch.compile` (triton-rocm is installed) to get a pass under 100 ms with
  resampling, which would allow a 100 ms hop and ~315 ms of delay.
