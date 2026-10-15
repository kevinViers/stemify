"""Model catalog and stem metadata shared by the UI and the worker.

Kept free of torch imports so the UI process stays light.
"""

DEMUCS_4 = ["drums", "bass", "other", "vocals"]
DEMUCS_6 = ["drums", "bass", "other", "vocals", "guitar", "piano"]

MODELS = [
    {"id": "htdemucs", "label": "HT Demucs", "family": "demucs",
     "stems": DEMUCS_4, "note": "Fast 4-stem"},
    {"id": "htdemucs_ft", "label": "HT Demucs FT", "family": "demucs",
     "stems": DEMUCS_4, "note": "Fine-tuned 4-stem, ~4x slower"},
    {"id": "htdemucs_6s", "label": "HT Demucs 6-stem", "family": "demucs",
     "stems": DEMUCS_6, "note": "Adds guitar and piano"},
    {"id": "roformer_bs_vocals", "label": "BS-RoFormer Vocals", "family": "roformer",
     "file": "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
     "stems": ["vocals", "instrumental"], "note": "Viperx 1297, vocals / instrumental"},
    {"id": "roformer_mel_kim_ft", "label": "Mel-RoFormer Vocals FT", "family": "roformer",
     "file": "mel_band_roformer_kim_ft_unwa.ckpt",
     "stems": ["vocals", "instrumental"], "note": "Kim FT by unwa, cleanest vocals"},
    {"id": "roformer_bs_sw", "label": "BS-RoFormer SW 6-stem", "family": "roformer",
     "file": "BS-Roformer-SW.ckpt",
     "stems": DEMUCS_6, "note": "jarredou, 6-stem RoFormer"},
]

MODELS_BY_ID = {m["id"]: m for m in MODELS}

STEM_COLORS = {
    "vocals": "#FF7AB6",
    "drums": "#FFB547",
    "bass": "#7C8CFF",
    "other": "#4CD07D",
    "guitar": "#FF8A5C",
    "piano": "#B48CFF",
    "instrumental": "#3DD6C6",
}

# Lane order in the UI, regardless of the order a model emits stems in.
STEM_ORDER = ["vocals", "drums", "bass", "guitar", "piano", "other", "instrumental"]


def stems_for(model_id: str, two_stems: bool) -> list[str]:
    stems = MODELS_BY_ID[model_id]["stems"]
    if two_stems and "vocals" in stems:
        return ["vocals", "instrumental"]
    return stems


def sort_stems(names):
    return sorted(names, key=lambda n: STEM_ORDER.index(n) if n in STEM_ORDER else 99)
