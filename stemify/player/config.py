"""Player settings and saved state: ~/.config/stemify/player.json."""

import json
import os
import uuid

CONFIG_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "stemify")
CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "stemify", "player")
PATH = os.path.join(CONFIG_DIR, "player.json")

DEFAULTS = {
    "plexUrl": "http://192.168.86.2:32400",
    "plexToken": "",
    "plexUser": "",
    "musicSection": "",
    "sectionLocations": [],
    "stemUrl": "http://192.168.86.2:8765",
    "stemToken": "",
    # The unraid shares, mounted here; files play straight from them when present.
    "stemsDir": "/var/mnt/unraid/Music/Stems",
    "musicDir": "/var/mnt/unraid/Music/Actual music/Flacs",
    "stemRoots": [],
    "volume": 0.8,
    "keepMix": False,
    "showMixer": True,
    "gridlines": True,
    "zoom": 100,
}


def load() -> dict:
    cfg = dict(DEFAULTS)
    try:
        with open(PATH) as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    cfg.setdefault("clientId", str(uuid.uuid4()))
    return cfg


def save(cfg: dict):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp = PATH + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cfg, f, indent=1)
    os.replace(tmp, PATH)
