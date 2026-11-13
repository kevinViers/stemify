"""Tier A: htdemucs on a sliding window.

Each hop, the model sees [context][hop][lookahead + xfade] of audio and only the hop's frames
are kept (stream.HopRunner crossfades them with the previous window). The window is a fixed
shape, so MIOpen tunes its kernels once at warmup. Capture and playback run at 48 kHz and the
model at 44.1 kHz: the whole window is resampled on the way in, and only the slice that's kept
on the way out.
"""

from . import rocm

rocm.prepare_env()

import numpy as np
import torch
from scipy.signal import resample_poly

from .model import forward, load_model

RATE = 48000
MODEL_RATE = 44100
UP, DOWN = 147, 160  # 48000 * 147 / 160 = 44100
EDGE = 0.02  # seconds of margin kept around the slice so resampling has clean edges


TRAINING_SEGMENT = 7.8  # seconds; htdemucs is clearly worse on shorter windows


class DemucsSeparator:
    """[context], [hop], [lookahead], [xfade] in seconds. [context] None fills the window out to
    the model's 7.8 s training segment. [budget] is the compute time the engine's delay allows
    for one pass (default: one hop); a pass slower than that plays dry."""

    def __init__(self, model="htdemucs", device="cuda:0", fp16=True, context=None, hop=0.2,
                 lookahead=0.1, xfade=0.01, budget=None):
        if context is None:
            context = TRAINING_SEGMENT - hop - lookahead - xfade
        self.model_name = model
        self.device = torch.device(device)
        self.fp16 = fp16 and self.device.type == "cuda"
        self.context = int(round(context * RATE))
        self.hop = int(round(hop * RATE))
        self.lookahead = int(round(lookahead * RATE))
        self.xfade = int(round(xfade * RATE))
        self.budget = int(round((hop if budget is None else budget) * RATE))
        if self.xfade > self.lookahead:
            raise ValueError("xfade must fit inside the lookahead")
        self.n = self.context + self.hop + self.lookahead + self.xfade
        self.window_seconds = self.n / RATE
        self._model = None
        self._stream = None
        self.stems = self._sources()

    def _sources(self):
        # Known without loading weights; checked against the model at warmup.
        return {"htdemucs_6s": ["drums", "bass", "other", "vocals", "guitar", "piano"]}.get(
            self.model_name, ["drums", "bass", "other", "vocals"])

    def warmup(self, passes=3):
        if self._model is None:
            rocm.setup_device(self.device)
            model = load_model(self.model_name, self.window_seconds)
            if list(model.sources) != self.stems:
                raise RuntimeError(f"{self.model_name} has stems {model.sources}, expected {self.stems}")
            self._model = model.to(self.device)
            if self.device.type == "cuda":
                # Ask the GPU to schedule our kernels ahead of a game's.
                lo, hi = torch.cuda.Stream.priority_range()
                self._stream = torch.cuda.Stream(self.device, priority=min(lo, hi))
        silence = np.zeros((self.n, 2), np.float32)
        noise = (np.random.default_rng(0).standard_normal((self.n, 2)) * 0.1).astype(np.float32)
        for i in range(passes):
            self.process(noise if i % 2 else silence)

    def release(self):
        self._model = None
        self._stream = None
        if self.device.type == "cuda":
            torch.cuda.empty_cache()

    def process(self, window):
        """(n, 2) at 48 kHz -> (stems, n, 2) at 48 kHz; only the kept slice is meaningful,
        everything outside [context - EDGE, context + hop + xfade + EDGE] is zeros."""
        return self.process_many(window[None])[0]

    def process_many(self, windows):
        """(batch, n, 2) -> (batch, stems, n, 2). Live uses batch 1; the offline simulator
        batches hops to run faster, with each window normalized on its own as live does."""
        x = resample_poly(windows, UP, DOWN, axis=1).astype(np.float32)  # (batch, m, 2)
        t = torch.from_numpy(np.ascontiguousarray(x.transpose(0, 2, 1)))
        if self.device.type == "cuda":
            with torch.cuda.stream(self._stream):
                y = self._run(t.pin_memory().to(self.device, non_blocking=True))
        else:
            y = self._run(t)
        # Back to 48 kHz, only around what HopRunner keeps. [a] sits on a 160-frame boundary
        # so it maps to a whole 44.1 kHz sample and the two timelines stay aligned.
        edge = int(EDGE * RATE)
        a = max(0, self.context - edge) // DOWN * DOWN
        b = min(self.n, self.context + self.hop + self.xfade + edge)
        a44, b44 = a * UP // DOWN, -(-b * UP // DOWN)
        back = resample_poly(y[..., a44:b44], DOWN, UP, axis=-1)  # (batch, stems, 2, ~b - a)
        out = np.zeros((len(windows), len(self.stems), self.n, 2), np.float32)
        k = min(back.shape[-1], self.n - a)
        out[:, :, a:a + k] = back[..., :k].transpose(0, 1, 3, 2)
        return out

    def _run(self, t):
        ref = t.mean(1, keepdim=True)  # (batch, 1, m)
        mean = ref.mean(-1, keepdim=True)
        std = ref.std(-1, keepdim=True) + 1e-8
        out = forward(self._model, (t - mean) / std, self.fp16) * std[:, None] + mean[:, None]
        return out.cpu().numpy()  # (batch, stems, 2, m)
