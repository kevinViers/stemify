"""Persistent settings exposed to QML as plain properties."""

import json
import os

from PySide6.QtCore import Property, QObject, Signal

CONFIG_PATH = os.path.join(os.path.expanduser("~/.config/stemify"), "settings.json")

DEFAULTS = {
    "modelId": "htdemucs",
    "twoStems": False,
    "deviceId": "auto",
    "outputMode": "beside",  # "beside" the source file, or "folder" (stemsDir)
    "stemsDir": os.path.expanduser("~/Music/Stems"),
    "exportFormat": "wav",  # wav | flac | mp3
    "segment": 0.0,  # seconds; 0 = model default
    "shifts": 0,
    "overlap": 0.25,
    "fp16": True,
    "autoExport": False,
    "frameOverlay": False,
    "cacheLimitGb": 8.0,
    "masterVolume": 1.0,  # slider position 0..1 (cubic taper in the audio engine)
}


class _SettingsBase(QObject):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._values = dict(DEFAULTS)
        try:
            with open(CONFIG_PATH) as f:
                stored = json.load(f)
            for k, v in stored.items():
                if k not in DEFAULTS:
                    continue
                want = type(DEFAULTS[k])
                if type(v) is want or (want is float and type(v) is int):
                    self._values[k] = want(v)
        except (OSError, ValueError):
            pass

    def save(self):
        os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
        tmp = CONFIG_PATH + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self._values, f, indent=2)
        os.replace(tmp, CONFIG_PATH)

    def get(self, name):
        return self._values[name]


def _build():
    # Qt only sees properties and signals present when the class is created,
    # so generate one Property + <name>Changed signal per default up front.
    ns = {}
    for name, default in DEFAULTS.items():
        type_ = type(default)
        sig = Signal()
        ns[f"{name}Changed"] = sig

        def getter(self, _n=name):
            return self._values[_n]

        def setter(self, value, _n=name, _t=type_):
            value = _t(value)
            if self._values[_n] != value:
                self._values[_n] = value
                getattr(self, f"{_n}Changed").emit()
                self.save()

        ns[name] = Property(type_, getter, setter, notify=sig)
    return type("Settings", (_SettingsBase,), ns)


Settings = _build()
