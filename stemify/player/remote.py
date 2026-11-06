"""Stand-ins for Stemify Player's player and engine while it controls another device.

They extend Stemify Mobile's (connect/remote.py) with what the workbook reads on top: the
status text, the bar-chart waveform, the Queue sheet, the playhead snapshot, and the bits MPRIS
uses, so the ribbon, the mixer pane, the sheets and the media keys all act on the other device.
"""

import time

from PySide6.QtCore import Property, Signal, Slot

from ..connect.remote import RemoteEngine, RemotePlayer
from .engine import BANDS, F_HI, F_LO
from .player import wave_bars


class SheetRemotePlayer(RemotePlayer):
    # PySide can't use a base class's signal as a property's notify (Qt crashes building the
    # QML property cache), so the properties added here get their own, fed by the base's.
    sheetChanged = Signal()
    barsChanged = Signal()

    def __init__(self, session, plex, library, parent=None):
        super().__init__(session, parent)
        self.plex, self.library = plex, library
        self.changed.connect(self.sheetChanged)
        self.waveChanged.connect(self.barsChanged)
        self.mixChanged.connect(self.barsChanged)  # the bars follow the faders
        self.queueChanged.connect(library.update_queue)

    # ---- what the library, the Queue sheet and MPRIS read -------------------------------------
    # The receiver sends a window of its queue (docs/CONNECT.md), so the Queue sheet shows that
    # window: row r is queue position offset + r.
    @property
    def queue(self):
        return [it["track"] for it in self.items]

    @property
    def queue_offset(self):
        return self.offset

    @property
    def index(self):
        return self.st["index"] - self.offset

    @property
    def shuffle(self):
        return bool(self.st["shuffle"])

    @property
    def repeat(self):
        return self.st["repeat"] if self.st["repeat"] in ("off", "all", "one") else "off"

    def current(self):
        t = self.currentTrack()
        return t or None

    def current_key(self):
        t = self.current()
        return t["key"] if t else ""

    def add_to_queue(self, tracks):
        if tracks:
            self.addList(tracks)

    def play_next(self, tracks):
        if tracks:
            self.playNextList(tracks)

    @Slot(int)
    def removeAt(self, row):
        super().removeAt(row + self.offset)

    @Slot()
    def clearQueue(self):
        self.clearUpNext()

    # ---- QML ------------------------------------------------------------------------------------
    def _get_status(self):
        if not self.st.get("track"):
            return ""
        if self.st["buffering"]:
            return "buffering"
        return "playing" if self.st["playing"] else "paused"

    def _get_sheet_track(self):
        t = self.current()
        if not t:
            return {}
        return {"title": t["title"], "artist": t["artist"], "album": t["album"], "year": t.get("year") or 0,
                "art": self.plex.art_url(t.get("thumb"), 600) or "", "durationMs": t.get("durationMs", 0),
                "codec": t.get("codec", ""), "key": t["key"]}

    track = Property("QVariantMap", _get_sheet_track, notify=sheetChanged)
    status = Property(str, lambda self: self._get_status(), notify=sheetChanged)
    wave = Property("QVariantList", lambda self: wave_bars(self._wave, self.mix, self.names), notify=barsChanged)


class SheetRemoteEngine(RemoteEngine):
    durationChanged = Signal()
    bufferingChanged = Signal()

    def __init__(self, player, session, parent=None):
        super().__init__(player, session, parent)
        self._dur = 0.0
        self._buf = False
        player.stateChanged.connect(self._on_state)

    def _on_state(self):
        d, b = self.player.st["durationMs"] / 1000, bool(self.player.st["buffering"])
        if d != self._dur:
            self._dur = d
            self.durationChanged.emit()
        if b != self._buf:
            self._buf = b
            self.bufferingChanged.emit()

    def position(self):
        return self.player.now()

    @Slot(result="QVariantList")
    def snapshot(self):
        """[position seconds, peakL, peakR]; the receiver sends one peak per stem, not per side."""
        lv = self.levels()
        peak = min(1.0, max(lv)) if lv else 0.0
        return [self.player.now(), peak, peak]

    @Slot(result="QVariantList")
    def spectrum(self):
        return []  # spectra aren't sent over the wire; the scope says so

    def _set_playing(self, on):
        p = self.player
        if p.st["playing"] != on:
            p.st["positionMs"] = int(p.now() * 1000)
            p.st["playing"] = on
            p._at = time.monotonic()
            p.s.cmd("play" if on else "pause")
            p._bump()

    @Slot()
    def play(self):
        self._set_playing(True)

    @Slot()
    def pause(self):
        self._set_playing(False)

    @Slot()
    def toggle(self):
        self.player.toggle()

    duration = Property(float, lambda self: self.player.st["durationMs"] / 1000, notify=durationChanged)
    buffering = Property(bool, lambda self: bool(self.player.st["buffering"]), notify=bufferingChanged)
    spectrumRange = Property("QVariantList", lambda self: [BANDS, F_LO, F_HI], constant=True)
