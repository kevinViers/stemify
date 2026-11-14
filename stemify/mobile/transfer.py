"""Transfer to a New Computer: a backup file of everything that makes this install yours
(settings and sign-ins, EQ profiles, the queue with its stem mix, the list of downloads), and the
restore that applies one live. The music isn't in it; the other computer downloads the same songs
from the server. Same JSON shape as the phone's backup, with "platform": "desktop"."""

import json
import os
import socket
import time
from datetime import datetime

from PySide6.QtCore import QObject, QStandardPaths, QUrl, Slot

from ..player import config
from .backend import EDITABLE

VERSION = 1
# What a backup carries: the settings screens' own keys, the account and the volume. Left out on
# purpose: this computer's id and Stemify Connect trust (another computer is approved on its own).
PORTABLE = set(EDITABLE) | {"plexUser", "plexToken", "plexMachineId", "volume"}


class BackupError(ValueError):
    pass


def _local(p):
    return QUrl(p).toLocalFile() if p.startswith("file:") else p


def _typed(v):
    if isinstance(v, bool):
        return {"t": "b", "v": v}
    if isinstance(v, int):
        return {"t": "i", "v": v}
    if isinstance(v, float):
        return {"t": "f", "v": v}
    if isinstance(v, str):
        return {"t": "s", "v": v}
    return {"t": "j", "v": v}


def _untyped(e):
    """A typed setting back to its value; None for anything malformed."""
    try:
        t, v = e["t"], e["v"]
        ok = {"s": str, "b": bool, "i": int, "l": int, "f": (int, float), "j": (list, dict)}[t]
        if not isinstance(v, ok) or (t != "b" and isinstance(v, bool)):
            return None
        return float(v) if t == "f" else v
    except (KeyError, TypeError):
        return None


def parse(text):
    try:
        b = json.loads(text)
        ok = isinstance(b, dict) and isinstance(b.get("stemifyBackup"), int) and b["stemifyBackup"] >= 1
    except ValueError:
        ok = False
    if not ok:
        raise BackupError("That isn't a Stemify backup.")
    if b.get("platform") != "desktop":
        where = {"android": "a phone"}.get(b.get("platform"), "another device")
        raise BackupError(f"That backup is from Stemify on {where}, not a computer.")
    return b


def _songs(b):
    return [t for t in b.get("songs") or [] if isinstance(t, dict) and t.get("key")]


def _playlists(b):
    out = []
    for p in b.get("playlists") or []:
        tracks = [t for t in (p or {}).get("tracks") or [] if isinstance(t, dict) and t.get("key")] if isinstance(p, dict) else []
        if tracks:
            out.append(tracks)
    return out


class Transfer(QObject):
    """QML's `transfer`: save a backup, read one, say what's in it, and restore it."""

    def __init__(self, backend, downloads, player, eq, engine, parent=None):
        super().__init__(parent)
        self.backend, self.downloads, self.player, self.eq, self.engine = backend, downloads, player, eq, engine
        self._pending = None

    # ---- make ---------------------------------------------------------------------------------
    def make(self):
        cfg = self.backend.cfg
        recs = sorted(self.downloads.records.values(), key=lambda r: r.get("added", 0))
        return {
            "stemifyBackup": VERSION, "platform": "desktop", "device": socket.gethostname() or "computer",
            "made": int(time.time() * 1000),
            "settings": {k: _typed(cfg[k]) for k in sorted(PORTABLE) if k in cfg},
            "eq": self.eq.state,
            "queue": self.player.export_queue(),
            "songs": [r["track"] for r in recs],
            "playlists": [],
        }

    @Slot(result=str)
    def defaultFile(self):
        """Where the Save dialog starts, as a file URL."""
        name = f"Stemify backup {socket.gethostname() or 'computer'} {datetime.now():%Y-%m-%d}.json"
        home = QStandardPaths.writableLocation(QStandardPaths.DocumentsLocation) or os.path.expanduser("~")
        return QUrl.fromLocalFile(os.path.join(home, name)).toString()

    @Slot(str, result=str)
    def save(self, path):
        """Writes the backup to path (or file URL); returns "" or what went wrong."""
        path = _local(path)
        try:
            self.eq.flush()
            text = json.dumps(self.make(), indent=1)
            tmp = path + ".tmp"
            # Owner-only: it holds the Plex and stem server sign-ins.
            with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
                os.fchmod(f.fileno(), 0o600)
                f.write(text)
            os.replace(tmp, path)
        except OSError as e:
            return e.strerror or "Couldn't save the backup"
        return ""

    # ---- read ---------------------------------------------------------------------------------
    @Slot(str, result="QVariantMap")
    def open(self, path):
        """Reads a backup file and describes it: {ok, error} or {ok, title, message}."""
        self._pending = None
        try:
            with open(_local(path), encoding="utf-8") as f:
                b = parse(f.read())
        except BackupError as e:
            return {"ok": False, "error": str(e)}
        except (OSError, UnicodeDecodeError):
            return {"ok": False, "error": "Couldn't read that file"}
        self._pending = b
        songs, lists = _songs(b), _playlists(b)
        albums = len({t.get("albumKey") or t.get("album") for t in songs if t.get("albumKey") or t.get("album")})
        eq = b.get("eq") if isinstance(b.get("eq"), dict) else None
        q = b.get("queue") if isinstance(b.get("queue"), dict) else None
        n = len(q.get("queue") or []) if q else 0
        parts = [f"{len(b.get('settings') or {})} settings"]
        if eq and eq.get("profiles"):
            parts.append(f"{len(eq['profiles'])} EQ profiles")
        if n:
            parts.append(f"a queue of {n} songs with its stem mix")
        parts.append(f"{len(songs)} downloaded song{'' if len(songs) == 1 else 's'} ({albums} album{'' if albums == 1 else 's'})")
        if lists:
            parts.append(f"{len(lists)} downloaded playlists")
        made = datetime.fromtimestamp((b.get("made") or 0) / 1000).strftime("%b %-d, %-I:%M %p")
        return {"ok": True, "title": f"Restore from {b.get('device') or 'another computer'}?",
                "message": f"Made {made}: {', '.join(parts)}. This computer's settings and EQ are replaced; "
                           "the downloads start now."}

    # ---- apply --------------------------------------------------------------------------------
    @Slot()
    def restore(self):
        """Applies the backup read by open(), live. Songs already downloaded are left alone."""
        b, self._pending = self._pending, None
        if not b:
            return
        be = self.backend
        values = {}
        for k, e in (b.get("settings") or {}).items():
            v = _untyped(e) if isinstance(e, dict) else None
            if k in PORTABLE and v is not None:
                values[k] = v
        # Settings with no screen of their own go straight in; the rest through Backend.set, which
        # reloads whatever depends on them (the music library, crossfade).
        for k, v in values.items():
            if k not in EDITABLE:
                be.cfg[k] = v
        for k, v in values.items():
            if k in EDITABLE:
                be.set(k, v)
        config.save(be.cfg)
        if isinstance(values.get("volume"), (int, float)) and 0 <= values["volume"] <= 1:
            self.engine.setVolume(values["volume"])
        be.settingsChanged.emit()
        be.refreshStems()
        if isinstance(b.get("eq"), dict):
            self.eq.replace_state(b["eq"])
        if isinstance(b.get("queue"), dict):
            self.player.adopt_queue(b["queue"])
        songs = _songs(b)
        if songs:
            self.downloads.download(songs)
        for tracks in _playlists(b):
            self.downloads.download(tracks)
