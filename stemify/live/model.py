"""Loading a single Demucs model for streaming use."""

import torch


def load_model(name="htdemucs", window=None):
    """One eval-mode Demucs model on the CPU.

    A one-model bag (htdemucs, hdemucs_mmi) is unwrapped; a real bag (htdemucs_ft) is refused,
    since Live runs one forward pass per hop. [window] (seconds) below the model's training
    segment turns off htdemucs' padding back up to the full segment, so a short window costs
    less; otherwise every window costs the same as 7.8 s.
    """
    from demucs.apply import BagOfModels
    from demucs.pretrained import get_model

    model = get_model(name)
    if isinstance(model, BagOfModels):
        if len(model.models) != 1:
            raise ValueError(f"{name} is a bag of {len(model.models)} models; Live needs a single one")
        model = model.models[0]
    model.eval()
    if window is not None and hasattr(model, "use_train_segment"):
        model.use_train_segment = window >= float(model.segment)
    for p in model.parameters():
        p.requires_grad_(False)
    return model


def forward(model, mix, fp16=False):
    """mix (batch, channels, samples) on the model's device -> (batch, stems, channels, samples)."""
    on_gpu = mix.device.type == "cuda"
    with torch.inference_mode(), torch.autocast("cuda", dtype=torch.float16, enabled=on_gpu and fp16):
        return model(mix).float()
