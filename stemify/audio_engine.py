"""Stem playback: mixes loaded stems with gain/solo/mute and a master volume in a PortAudio callback.

The callback thread owns mixing; the UI reads position and meter levels once
per frame via snapshot(), which interpolates from a small ring of per-block
DAC timestamps so the playhead stays smooth at 240Hz.
"""

import threading

import numpy as np
import sounddevice as sd
import soundfile as sf
from PySide6.QtCore import Property, QObject, Signal, Slot

RING = 64
BLOCK = 256


class AudioEngine(QObject):
    playingChanged = Signal()
    loadedChanged = Signal()
    volumeChanged = Signal()
    durationChanged = Signal()
    loadFailed = Signal(str)
    _loadedInternal = Signal(object, object)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._stems: list[np.ndarray] = []
        self._names: list[str] = []
        self._sr = 44100
        self._len = 0
        self._pos = 0
        self._playing = False
        self._ended = False
        self._stream: sd.OutputStream | None = None
        self._key = None
        self._load_token = 0
        self._gain_db: list[float] = []
        self._solo: list[bool] = []
        self._mute: list[bool] = []
        self._target = np.zeros(0, np.float32)
        self._current = np.zeros(0, np.float32)
        self._volume = 1.0  # master slider position, 0..1
        self._master = 1.0  # master gain the callback ramps toward
        self._master_cur = 1.0
        self._mix = np.zeros((4096, 2), np.float32)
        self._tmp = np.zeros((4096, 2), np.float32)
        # ring of (dac_time, block_start_frame, peakL, peakR); written by the audio thread
        self._ring = np.zeros((RING, 4), np.float64)
        self._ring_i = 0
        self._loadedInternal.connect(self._on_loaded)

    # ---- loading -------------------------------------------------------
    def load(self, key, stems: list[tuple[str, str]]):
        """Load stems [(name, path)] in a background thread; replaces the current set."""
        if key == self._key and self._stems:
            return
        self.unload()
        self._key = key
        self._load_token += 1
        token = self._load_token

        def work():
            try:
                arrays, sr = [], None
                for name, path in stems:
                    data, file_sr = sf.read(path, dtype="float32", always_2d=True)
                    if data.shape[1] == 1:
                        data = np.repeat(data, 2, axis=1)
                    arrays.append(np.ascontiguousarray(data[:, :2]))
                    sr = sr or file_sr
                n = min(a.shape[0] for a in arrays)
                self._loadedInternal.emit((token, [s[0] for s in stems], sr), [a[:n] for a in arrays])
            except Exception as e:  # surfaced in the UI as a toast
                self._loadedInternal.emit((token, None, str(e)), None)

        threading.Thread(target=work, daemon=True).start()

    def _on_loaded(self, info, arrays):
        token, names, sr = info
        if token != self._load_token:
            return
        if names is None:
            self.loadFailed.emit(sr)
            return
        self._names = names
        self._sr = sr
        self._stems = arrays
        self._len = arrays[0].shape[0] if arrays else 0
        self._pos = 0
        k = len(arrays)
        self._gain_db = [0.0] * k
        self._solo = [False] * k
        self._mute = [False] * k
        self._target = np.ones(k, np.float32)
        self._current = self._target.copy()
        self._ring[:] = 0
        self.loadedChanged.emit()
        self.durationChanged.emit()

    @Slot()
    def unload(self):
        self.pause()
        self._close_stream()
        self._stems, self._names, self._len, self._pos, self._key = [], [], 0, 0, None
        self._load_token += 1
        self.loadedChanged.emit()
        self.durationChanged.emit()

    # ---- transport -----------------------------------------------------
    @Slot()
    def toggle(self):
        self.pause() if self._playing else self.play()

    @Slot()
    def play(self):
        if not self._stems or self._playing:
            return
        if self._pos >= self._len - BLOCK:
            self._pos = 0
        self._ended = False
        if self._stream is None or self._stream.samplerate != self._sr:
            self._close_stream()
            self._stream = sd.OutputStream(samplerate=self._sr, channels=2, dtype="float32",
                                           blocksize=BLOCK, latency="low", callback=self._callback)
        self._ring[:] = 0
        self._playing = True
        self._stream.start()
        self.playingChanged.emit()

    @Slot()
    def pause(self):
        if not self._playing:
            return
        self._playing = False
        if self._stream is not None:
            # Rewind to what was actually heard, not what was queued to the device.
            heard = self._interpolated_pos()
            self._stream.stop()
            self._pos = int(heard)
        self.playingChanged.emit()

    @Slot(float)
    def seek(self, seconds):
        self._pos = int(max(0.0, min(seconds * self._sr, self._len - 1)))
        self._ring[:] = 0
        self._ended = False

    def _close_stream(self):
        if self._stream is not None:
            try:
                self._stream.close()
            except Exception:
                pass
            self._stream = None

    # ---- mixer controls ------------------------------------------------
    @Slot(int, float)
    def setGain(self, i, db):
        if 0 <= i < len(self._gain_db):
            self._gain_db[i] = db
            self._recompute()

    @Slot(int, bool)
    def setSolo(self, i, on):
        if 0 <= i < len(self._solo):
            self._solo[i] = on
            self._recompute()

    @Slot(int, bool)
    def setMute(self, i, on):
        if 0 <= i < len(self._mute):
            self._mute[i] = on
            self._recompute()

    @Slot(float)
    def setVolume(self, v):
        v = max(0.0, min(1.0, float(v)))
        if v != self._volume:
            self._volume = v
            # cubic taper: roughly even loudness steps across the slider
            self._master = v ** 3
            self.volumeChanged.emit()

    def _recompute(self):
        any_solo = any(self._solo)
        t = np.zeros(len(self._stems), np.float32)
        for i, db in enumerate(self._gain_db):
            audible = self._solo[i] if any_solo else not self._mute[i]
            t[i] = 10 ** (db / 20) if audible else 0.0
        self._target = t

    def mix_gains(self) -> np.ndarray:
        return self._target.copy()

    def stem_gain(self, name) -> float:
        i = self._names.index(name)
        return 10 ** (self._gain_db[i] / 20)

    def loaded_key(self):
        return self._key

    def arrays(self):
        return dict(zip(self._names, self._stems)), self._sr

    # ---- audio thread --------------------------------------------------
    def _callback(self, outdata, frames, time_info, status):
        pos = self._pos
        n = max(0, min(frames, self._len - pos))
        mix = self._mix[:frames]
        mix.fill(0)
        if self._playing and n:
            target, current = self._target, self._current
            if len(target) != len(self._stems):
                target = current = np.zeros(len(self._stems), np.float32)
            ramp = None
            for i, stem in enumerate(self._stems):
                g0, g1 = current[i], target[i]
                if g0 == 0 and g1 == 0:
                    continue
                tmp = self._tmp[:n]
                if g0 == g1:
                    np.multiply(stem[pos:pos + n], g1, out=tmp)
                else:  # click-free gain change across one block
                    if ramp is None:
                        ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)[:, None]
                    np.multiply(stem[pos:pos + n], g0 + (g1 - g0) * ramp, out=tmp)
                mix[:n] += tmp
            self._current = target.copy()
            m0, m1 = self._master_cur, self._master
            if m0 != m1:
                if ramp is None:
                    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)[:, None]
                mix[:n] *= m0 + (m1 - m0) * ramp
                self._master_cur = m1
            elif m1 != 1.0:
                mix[:n] *= m1
            self._pos = pos + n
        outdata[:] = mix
        peak = np.abs(mix).max(axis=0) if frames else (0.0, 0.0)
        dac = time_info.outputBufferDacTime
        if dac <= 0:  # some host APIs leave it unset; fall back to the stream clock
            dac = self._stream.time + self._stream.latency
        r = self._ring[self._ring_i % RING]
        r[0] = dac
        r[1] = pos
        r[2], r[3] = peak[0], peak[1]
        self._ring_i += 1
        if self._playing and pos + n >= self._len:
            self._ended = True

    def _played(self, now):
        ring = self._ring
        return ring[(ring[:, 0] > 0) & (ring[:, 0] <= now)]

    def _interpolated_pos(self):
        if self._stream is None:
            return self._pos
        now = self._stream.time
        played = self._played(now)
        if not len(played):
            return self._pos if not self._playing else max(0, self._pos - BLOCK * 2)
        last = played[np.argmax(played[:, 0])]
        return min(self._len, last[1] + (now - last[0]) * self._sr)

    # ---- per-frame UI read ---------------------------------------------
    @Slot(result="QVariantList")
    def snapshot(self):
        """[position_seconds, peakL, peakR, playing] for the current frame."""
        if not self._stems:
            return [0.0, 0.0, 0.0, False]
        if not self._playing or self._stream is None:
            return [float(self._pos) / self._sr, 0.0, 0.0, False]
        now = self._stream.time
        played = self._played(now)
        if len(played):
            last = played[np.argmax(played[:, 0])]
            pos = min(self._len, last[1] + (now - last[0]) * self._sr)
            l, r = float(last[2]), float(last[3])
        else:
            pos, l, r = self._pos, 0.0, 0.0
        if self._ended and pos >= self._len - 1:
            self._playing = False
            self._stream.stop()
            self._pos = 0
            self.playingChanged.emit()
            return [0.0, 0.0, 0.0, False]
        return [float(pos) / self._sr, l, r, True]

    # ---- properties ----------------------------------------------------
    def _get_playing(self):
        return self._playing

    def _get_loaded(self):
        return bool(self._stems)

    def _get_duration(self):
        return self._len / self._sr if self._stems else 0.0

    def _get_volume(self):
        return self._volume

    playing = Property(bool, _get_playing, notify=playingChanged)
    loaded = Property(bool, _get_loaded, notify=loadedChanged)
    duration = Property(float, _get_duration, notify=durationChanged)
    volume = Property(float, _get_volume, setVolume, notify=volumeChanged)
