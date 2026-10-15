"""Audio decode/encode and waveform peak generation for the worker."""

import subprocess

import numpy as np
import soundfile as sf

PEAK_BASE = 256  # samples per bucket at mip level 0


def _source_channels(path: str) -> int:
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                              "stream=channels", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=10).stdout
        return int(out.strip().splitlines()[0])
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return 0


def load_audio(path: str, samplerate: int, channels: int = 2) -> np.ndarray:
    """Decode any ffmpeg-readable file to float32 (channels, samples)."""
    # ffmpeg's -ac upmix treats mono as centre and spreads it at -3 dB per side;
    # decode mono as mono and duplicate it so stems keep the source level.
    decode_ch = 1 if _source_channels(path) == 1 else channels
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-i", path, "-map", "0:a:0",
           "-f", "f32le", "-acodec", "pcm_f32le", "-ac", str(decode_ch), "-ar", str(samplerate), "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        lines = [l.strip() for l in proc.stderr.decode(errors="replace").splitlines() if l.strip()]
        reason = lines[-1].split(": ", 1)[-1] if lines else f"ffmpeg exited with {proc.returncode}"
        raise RuntimeError(f"Could not decode the file ({reason[:160]})")
    data = np.frombuffer(proc.stdout, dtype=np.float32)
    if data.size == 0:
        raise RuntimeError("file contains no audio")
    data = data.reshape(-1, decode_ch).T
    if decode_ch != channels:
        data = np.repeat(data, channels, axis=0)
    return data.copy()


def write_stem(path: str, audio: np.ndarray, samplerate: int):
    """Write (channels, samples) audio. 24-bit unless it would clip, then float."""
    peak = float(np.abs(audio).max()) if audio.size else 0.0
    subtype = "FLOAT" if peak > 0.999 else "PCM_24"
    sf.write(path, audio.T, samplerate, subtype=subtype, format="WAV")


def compute_peaks(audio: np.ndarray) -> list[np.ndarray]:
    """Min/max mip pyramid of a (channels, samples) signal, mono-folded.

    Level k has PEAK_BASE * 2**k samples per bucket; each level is (n, 2) float16.
    """
    lo = audio.min(axis=0)
    hi = audio.max(axis=0)
    n = -(-lo.size // PEAK_BASE)
    pad = n * PEAK_BASE - lo.size
    if pad:
        lo = np.concatenate([lo, np.zeros(pad, lo.dtype)])
        hi = np.concatenate([hi, np.zeros(pad, hi.dtype)])
    lvl_lo = lo.reshape(n, PEAK_BASE).min(axis=1)
    lvl_hi = hi.reshape(n, PEAK_BASE).max(axis=1)
    levels = [np.stack([lvl_lo, lvl_hi], axis=1).astype(np.float16)]
    while lvl_lo.size > 64:
        if lvl_lo.size % 2:
            lvl_lo = np.append(lvl_lo, 0)
            lvl_hi = np.append(lvl_hi, 0)
        lvl_lo = lvl_lo.reshape(-1, 2).min(axis=1)
        lvl_hi = lvl_hi.reshape(-1, 2).max(axis=1)
        levels.append(np.stack([lvl_lo, lvl_hi], axis=1).astype(np.float16))
    return levels


def save_peaks(path: str, stems: dict[str, np.ndarray], samplerate: int):
    arrays = {"samplerate": np.array(samplerate), "base": np.array(PEAK_BASE)}
    for name, audio in stems.items():
        for k, level in enumerate(compute_peaks(audio)):
            arrays[f"{name}/{k}"] = level
    np.savez(path, **arrays)
