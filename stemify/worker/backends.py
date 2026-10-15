"""Separation backends. Each returns ({stem: float32 (channels, samples)}, samplerate)."""

import gc
import logging
import os
import random

import numpy as np
import soundfile as sf
import torch

from .audio_io import load_audio


class Cancelled(BaseException):
    """Raised from progress hooks to abort a running separation."""


class DemucsBackend:
    """Demucs with batched segments.

    demucs.apply.apply_model runs one segment per forward pass, which leaves an
    RX 9070 XT mostly idle waiting on MIOpen host overhead. Batching 8 segments
    under fp16 autocast measured ~110x realtime vs ~31x for the stock loop.
    """

    BATCH = 8

    def __init__(self):
        self._model = None
        self._model_name = None

    def _get(self, name):
        if self._model_name != name:
            from demucs.pretrained import get_model
            self._model = None
            torch.cuda.empty_cache()
            self._model = get_model(name).eval()
            self._model_name = name
        return self._model

    def release(self):
        self._model = self._model_name = None
        gc.collect()
        torch.cuda.empty_cache()

    def run(self, job, device):
        from demucs.apply import BagOfModels

        job.report("Loading model", 0.01)
        bag = self._get(job.model["id"])
        sub_models = bag.models if isinstance(bag, BagOfModels) else [bag]
        weights = bag.weights if isinstance(bag, BagOfModels) else [[1.0] * len(bag.sources)]
        job.check()
        job.report("Decoding audio", 0.03)
        wav = torch.from_numpy(load_audio(job.file, bag.samplerate, bag.audio_channels))
        job.check()

        ref = wav.mean(0)
        mean, std = ref.mean(), ref.std() + 1e-8
        mix = ((wav - mean) / std).to(device)
        length = mix.shape[-1]

        passes = max(1, job.shifts)
        max_shift = int(0.5 * bag.samplerate) if job.shifts else 0
        padded = torch.nn.functional.pad(mix, (max_shift, max_shift)) if max_shift else mix

        # Work units for progress: one per segment per model per shift.
        plans = []
        for sub in sub_models:
            seg_len = int(min(job.segment, float(sub.segment)) * bag.samplerate) if job.segment else int(float(sub.segment) * bag.samplerate)
            stride = max(1, int((1 - job.overlap) * seg_len))
            plans.append((sub, seg_len, stride))
        total_units = sum(passes * len(range(0, length + max_shift, stride)) for _, _, stride in plans)
        done_units = 0

        def tick(n):
            nonlocal done_units
            done_units += n
            job.check()
            job.report("Separating", 0.05 + 0.88 * min(1.0, done_units / total_units))

        self.fp16 = job.fp16
        job.report("Separating", 0.05)
        estimates = torch.zeros(len(bag.sources), mix.shape[0], length, device=device)
        totals = [0.0] * len(bag.sources)
        for (sub, seg_len, stride), w in zip(plans, weights):
            sub.to(device)
            out = torch.zeros_like(estimates)
            for _ in range(passes):
                offset = random.randint(0, max_shift) if max_shift else 0
                piece = padded[..., offset: offset + length + max_shift - offset] if max_shift else padded
                res = self._split(sub, piece, seg_len, stride, job.overlap, device, tick)
                out += res[..., max_shift - offset: max_shift - offset + length]
            out /= passes
            for k, wk in enumerate(w):
                estimates[k] += out[k] * wk
                totals[k] += wk
            sub.cpu()
        for k in range(len(totals)):
            estimates[k] /= totals[k]

        out = (estimates * std.to(device) + mean.to(device)).cpu().numpy()
        return dict(zip(bag.sources, out)), bag.samplerate

    def _split(self, model, mix, seg_len, stride, overlap, device, tick):
        """Overlap-add over fixed-length segments, BATCH segments per forward pass."""
        channels, length = mix.shape
        n_src = len(model.sources)
        weight = torch.cat([torch.arange(1, seg_len // 2 + 1, device=device),
                            torch.arange(seg_len - seg_len // 2, 0, -1, device=device)]).float()
        weight /= weight.max()
        out = torch.zeros(n_src, channels, length, device=device)
        sum_weight = torch.zeros(length, device=device)
        offsets = list(range(0, length, stride))
        use_amp = device.type == "cuda" and self.fp16
        batch = self.BATCH
        i = 0
        while i < len(offsets):
            group = offsets[i:i + batch]
            chunks = torch.zeros(len(group), channels, seg_len, device=device)
            for j, off in enumerate(group):
                piece = mix[:, off:off + seg_len]
                chunks[j, :, :piece.shape[-1]] = piece
            try:
                with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16, enabled=use_amp):
                    res = model(chunks).float()
            except torch.OutOfMemoryError:
                if batch == 1:
                    raise
                torch.cuda.empty_cache()
                batch = max(1, batch // 2)
                continue
            for j, off in enumerate(group):
                n = min(seg_len, length - off)
                out[..., off:off + n] += weight[:n] * res[j, ..., :n]
                sum_weight[off:off + n] += weight[:n]
            i += len(group)
            tick(len(group))
        return out / sum_weight


def _progress_iter_factory(job, lo, hi):
    """Stand-in for tqdm inside audio-separator: reports progress and checks cancel."""

    class ProgressIter:
        def __init__(self, iterable=None, *args, total=None, **kwargs):
            self.iterable = iterable
            self.total = total if total is not None else (len(iterable) if hasattr(iterable, "__len__") else None)
            self.n = 0

        def __iter__(self):
            for item in self.iterable:
                self.update(0)
                yield item
                self.n += 1

        def update(self, n=1):
            self.n += n
            job.check()
            if self.total:
                job.report("Separating", lo + (hi - lo) * min(1.0, self.n / self.total))

        def close(self):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    return ProgressIter


class RoformerBackend:
    def __init__(self, model_dir):
        self.model_dir = model_dir
        self._sep = None
        self._key = None

    def _get(self, job, device):
        key = (job.model["file"], str(device), job.overlap_int, job.fp16)
        if self._key != key:
            from audio_separator.separator import Separator
            self._sep = None
            torch.cuda.empty_cache()
            if not os.path.exists(os.path.join(self.model_dir, job.model["file"])):
                job.report("Downloading model", 0.01)
            sep = Separator(
                log_level=logging.WARNING,
                model_file_dir=self.model_dir,
                output_dir=job.out_dir,
                normalization_threshold=1.0,
                amplification_threshold=0.0,
                use_autocast=device.type == "cuda" and job.fp16,
                mdxc_params={"segment_size": 256, "override_model_segment_size": False,
                             "batch_size": 1, "overlap": job.overlap_int, "pitch_shift": 0},
            )
            # Separator picks "cuda" (current device) on its own; honour an explicit choice.
            if device.type == "cpu":
                sep.torch_device = torch.device("cpu")
            elif device.index is not None:
                torch.cuda.set_device(device.index)
            sep.load_model(model_filename=job.model["file"])
            self._sep, self._key = sep, key
        return self._sep

    def release(self):
        self._sep = self._key = None
        gc.collect()
        torch.cuda.empty_cache()

    def run(self, job, device):
        from audio_separator.separator.architectures import mdxc_separator

        job.report("Loading model", 0.02)
        sep = self._get(job, device)
        job.check()

        captured = {}

        def capture(stem_path, source, stem_name):
            captured[stem_name] = source
            return {stem_name: source}

        inst = sep.model_instance
        inst.final_process = capture
        inst.output_dir = job.out_dir
        # audio-separator reads with libsndfile, which rejects many MP3s (big ID3 tags) and
        # FLACs with one damaged frame; ffmpeg decodes both, so hand it a clean WAV instead.
        job.report("Decoding audio", 0.03)
        os.makedirs(job.out_dir, exist_ok=True)
        mix_path = os.path.join(job.out_dir, "mix.wav")
        sf.write(mix_path, load_audio(job.file, inst.sample_rate, 2).T, inst.sample_rate, subtype="FLOAT")
        job.check()
        mdxc_separator.tqdm = _progress_iter_factory(job, 0.05, 0.93)
        job.report("Separating", 0.05)
        try:
            sep.separate(mix_path)
        finally:
            os.remove(mix_path)

        two_stem_model = len(job.model["stems"]) == 2
        stems = {}
        for name, source in captured.items():
            key = name.lower()
            if two_stem_model and key != "vocals":
                key = "instrumental"
            stems[key] = np.ascontiguousarray(source.T, dtype=np.float32)
        return stems, inst.sample_rate
