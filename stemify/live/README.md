# Stemify Live

A virtual output device, "Stemify Live". Whatever plays into it is split into stems as it plays
and mixed back out to the speakers, with per-stem gains. Design: `liveProcessing.md` at the repo
root. Measurements: `docs/live/`.

```sh
python -m stemify.live                       # htdemucs fp16 on the 9070 XT, to the default sink
python -m stemify.live --gains 1 1 1 0       # drums bass other vocals: karaoke
python -m stemify.live --separator passthrough
```

Then pick "Stemify Live" as the output in an app (or move a stream there in pavucontrol). Ctrl+C
removes the sink.

## Files

| File | What it does |
| --- | --- |
| `rocm.py` | ROCm setup before torch loads: per-node gfx override for APU iGPUs, and per-device workarounds |
| `model.py` | Loads one Demucs model for streaming (shorter windows really cost less) |
| `bench.py` | Phase 0: time one forward pass per device, window, precision; optional GPU hog |
| `sink.py` | The PipeWire null sink, owned by a `pw-cli` child that dies with us |
| `stream.py` | `LiveEngine`: JACK duplex callback, capture and stem rings, separator thread, mixer |
| `separator.py` | Tier A: htdemucs on a sliding window, 48 kHz in and out |
| `selftest.py` | Headless latency and glitch test between private null sinks (nothing audible) |
| `evaluate.py` | Phase 2 quality: live path vs offline htdemucs, scored against the library's stems |

## How the audio path works

The engine is one PortAudio duplex stream on PipeWire's JACK layer: input is linked to the Live
sink's monitor, output to the chosen speaker. Capture and playback share each graph cycle
(256 frames, 5.3 ms, asked for with `PIPEWIRE_LATENCY`). The callback copies input into a
capture ring and plays from a stem ring a fixed `delay` behind. The separator thread pulls
`[context | hop | lookahead + xfade]` windows from the capture ring and writes one hop per stem.
A hop that isn't ready in time plays as the dry mix instead, so the output never clicks or
goes silent.

`delay = hop + lookahead + xfade + budget + quantum`, where `budget` is the compute time allowed
per pass (one hop by default). Passthrough: 16 ms engine delay, 21 ms measured. Tier A defaults
(7.8 s window, 200 ms hop, 100 ms lookahead): 515 ms, 521 ms measured.

## Testing without the speakers

`python -m stemify.live.selftest` makes its own sinks ("Stemify Live Selftest" and a silent
"Stemify Selftest Out"), so it never touches the real Live sink or the speakers. A harness
process plays clicks (or `--music file`) into the Live sink and records both ends in one stream.
Exit code 0 means it passed.
