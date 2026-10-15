"""Streaming playback: a decoder thread reads every stem ahead of the playhead in chunks,
and the PortAudio callback mixes them with per-stem gains and the master volume.

Nothing is decoded whole, so a song starts as soon as its first chunk is read (stems sit on
the network share) and memory stays flat. The callback never takes a lock: chunks travel
through a deque tagged with a generation number, and a seek or a new song bumps the
generation so stale chunks are dropped on the floor.
"""

import collections
import json
import subprocess
import threading

import numpy as np
import sounddevice as sd
import soundfile as sf
from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

CHUNK = 8192  # frames per decoder read
AHEAD = 1.5  # seconds buffered ahead of the playhead
BLOCK = 1024
LATENCY = 0.15  # roomy: Python shares the GIL with library loading
RING = 64


class SfReader:
    """libsndfile: FLAC, WAV, AIFF, Ogg, MP3."""

    def __init__(self, path):
        self.f = sf.SoundFile(path)
        self.sr = self.f.samplerate
        self.frames = self.f.frames

    def read(self, n):
        data = self.f.read(n, dtype="float32", always_2d=True)
        if data.shape[1] == 1:
            return np.repeat(data, 2, axis=1)
        return data[:, :2]

    def seek(self, frame):
        self.f.seek(min(frame, self.frames))

    def close(self):
        self.f.close()


class FfmpegReader:
    """Anything else (AAC/ALAC in .m4a and friends), decoded by ffmpeg to float stereo."""

    def __init__(self, path, sr=None):
        self.path = path
        info = json.loads(subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate:format=duration",
             "-of", "json", path], capture_output=True, check=True, timeout=20).stdout)
        self.sr = sr or int(info["streams"][0]["sample_rate"])
        self.frames = int(float(info["format"]["duration"]) * self.sr)
        self.proc = None
        self.seek(0)

    def seek(self, frame):
        self.close()
        self.proc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-ss", f"{frame / self.sr:.6f}", "-i", self.path, "-vn",
             "-f", "f32le", "-ac", "2", "-ar", str(self.sr), "-"],
            stdout=subprocess.PIPE, stdin=subprocess.DEVNULL)

    def read(self, n):
        raw = self.proc.stdout.read(n * 8)
        usable = len(raw) - len(raw) % 8
        return np.frombuffer(raw[:usable], np.float32).reshape(-1, 2)

    def close(self):
        if self.proc:
            self.proc.kill()
            self.proc.stdout.close()
            self.proc.wait()
            self.proc = None


def open_reader(path, sr=None):
    if sr is None:
        try:
            return SfReader(path)
        except (sf.LibsndfileError, RuntimeError):
            pass
    return FfmpegReader(path, sr)


class Source:
    """One song: one reader per stem (or a single reader for the original file)."""

    def __init__(self, names, paths):
        self.names = list(names)
        self.readers = []
        try:
            for p in paths:
                self.readers.append(open_reader(p))
            sr = self.readers[0].sr
            # Stems always share a rate; if one didn't, resample it through ffmpeg.
            for i, (r, p) in enumerate(zip(self.readers, paths)):
                if r.sr != sr:
                    r.close()
                    self.readers[i] = open_reader(p, sr)
        except Exception:
            self.close()
            raise
        self.sr = sr
        self.frames = min(r.frames for r in self.readers)

    def read(self, n):
        """(stems, frames, 2) float32; fewer frames near the end, none at EOF."""
        blocks = [r.read(n) for r in self.readers]
        m = min(b.shape[0] for b in blocks)
        return np.stack([b[:m] for b in blocks])

    def seek(self, frame):
        for r in self.readers:
            r.seek(frame)

    def close(self):
        for r in self.readers:
            try:
                r.close()
            except Exception:
                pass
        self.readers = []


class PlayerEngine(QObject):
    playingChanged = Signal()
    bufferingChanged = Signal()
    volumeChanged = Signal()
    durationChanged = Signal()
    ended = Signal()
    failed = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._src: Source | None = None
        self._sr = 44100
        self._frames = 0
        self._gen = 0
        self._gen_start = 0  # frame the current generation starts at
        self._queue = collections.deque()
        self._produced = 0
        self._consumed = 0
        self._eof = False
        self._seek_to = None
        self._to_close = []
        self._cond = threading.Condition()
        self._alive = True
        self._playing = False
        self._buffering = False
        self._ended_flag = False
        self._stream: sd.OutputStream | None = None
        self._gains = np.ones(0, np.float32)
        self._gains_cur = np.ones(0, np.float32)
        self._volume = 0.8
        self._master = self._volume ** 3
        self._master_cur = self._master
        # callback-owned
        self._cb_gen = -1
        self._cur = None
        self._cur_off = 0
        self._pos = 0
        self._underrun = False
        self._mix = np.zeros((BLOCK * 8, 2), np.float32)
        self._ramp = np.zeros(0, np.float32)
        self._ring = np.zeros((RING, 4), np.float64)
        self._ring_i = 0
        self._thread = threading.Thread(target=self._decode_loop, daemon=True, name="decoder")
        self._thread.start()
        # The callback only sets flags; this turns them into signals on the GUI thread.
        self._tick = QTimer(self)
        self._tick.setInterval(100)
        self._tick.timeout.connect(self._poll)
        self._tick.start()

    # ---- decoder thread --------------------------------------------------------------
    def _decode_loop(self):
        while True:
            with self._cond:
                while self._alive and not self._to_close and self._seek_to is None and (
                        self._src is None or self._eof
                        or self._produced - self._consumed >= AHEAD * self._sr):
                    self._cond.wait(0.25)
                if not self._alive:
                    return
                closing, self._to_close = self._to_close, []
                src, gen, seek = self._src, self._gen, self._seek_to
                self._seek_to = None
            for s in closing:
                s.close()
            if src is None:
                continue
            try:
                if seek is not None:
                    src.seek(seek)
                block = src.read(CHUNK)
            except Exception as e:  # unreadable file mid-song: treat as its end
                print("decode error:", e, flush=True)
                block = np.zeros((len(src.names), 0, 2), np.float32)
            with self._cond:
                if gen != self._gen:
                    continue
                if block.shape[1] == 0:
                    self._eof = True
                    self._queue.append((gen, None))
                else:
                    self._queue.append((gen, block))
                    self._produced += block.shape[1]

    # ---- GUI thread API -----------------------------------------------------------------
    def set_source(self, src: Source, start_seconds=0.0, play=True):
        """Replaces the playing song. Takes ownership of [src]."""
        if self._stream is not None and self._stream.samplerate != src.sr:
            self._close_stream()
        with self._cond:
            if self._src is not None:
                self._to_close.append(self._src)
            self._src = src
            self._sr = src.sr
            self._frames = src.frames
            k = len(src.names)
            if len(self._gains) != k:
                self._gains = np.ones(k, np.float32)
                self._gains_cur = self._gains.copy()
            self._restart_locked(int(start_seconds * src.sr))
        self._ended_flag = False
        self.durationChanged.emit()
        if play:
            self.play()

    def clear(self):
        self.pause()
        with self._cond:
            if self._src is not None:
                self._to_close.append(self._src)
            self._src = None
            self._frames = 0
            self._restart_locked(0)
            self._cond.notify()
        self.durationChanged.emit()

    def _restart_locked(self, frame):
        frame = max(0, min(frame, max(0, self._frames - 1)))
        self._gen += 1
        self._gen_start = frame
        self._queue.clear()
        self._produced = self._consumed = 0
        self._eof = False
        self._seek_to = frame
        self._ring[:] = 0
        self._cond.notify()

    @Slot()
    def play(self):
        if self._src is None or self._playing:
            return
        if self._stream is None:
            self._stream = sd.OutputStream(samplerate=self._sr, channels=2, dtype="float32", blocksize=BLOCK,
                                           latency=LATENCY, callback=self._callback)
        self._playing = True
        self._ring[:] = 0
        self._stream.start()
        self.playingChanged.emit()

    @Slot()
    def pause(self):
        if not self._playing:
            return
        heard = self._heard_frame()
        self._playing = False
        if self._stream is not None:
            self._stream.abort()  # instant; what was queued but unheard is decoded again below
        with self._cond:
            self._restart_locked(int(heard))
        self.playingChanged.emit()

    @Slot()
    def toggle(self):
        self.pause() if self._playing else self.play()

    @Slot(float)
    def seek(self, seconds):
        with self._cond:
            self._restart_locked(int(seconds * self._sr))
        self._ended_flag = False

    def set_gains(self, gains):
        g = np.asarray(gains, np.float32)
        if len(g) == len(self._gains):
            self._gains = g

    @Slot(float)
    def setVolume(self, v):
        v = max(0.0, min(1.0, float(v)))
        if v != self._volume:
            self._volume = v
            self._master = v ** 3  # cubic taper: even loudness steps along the slider
            self.volumeChanged.emit()

    def shutdown(self):
        self._close_stream()
        with self._cond:
            self._alive = False
            if self._src is not None:
                self._src.close()
            self._cond.notify()

    def _close_stream(self):
        if self._stream is not None:
            try:
                self._stream.abort()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
            if self._playing:
                self._playing = False
                self.playingChanged.emit()

    def _poll(self):
        # Wait for the device to play out what's queued, or the song's last moment is cut.
        if self._ended_flag and self._playing and self._heard_frame() >= self._pos - BLOCK:
            self._ended_flag = False
            self._playing = False
            if self._stream is not None:
                self._stream.abort()
            self.playingChanged.emit()
            self.ended.emit()
            return
        b = self._playing and self._underrun and not self._eof
        if b != self._buffering:
            self._buffering = b
            self.bufferingChanged.emit()

    # ---- audio thread -----------------------------------------------------------------
    def _callback(self, outdata, frames, time_info, status):
        gen = self._gen
        if gen != self._cb_gen:
            self._cb_gen = gen
            self._cur = None
            self._pos = self._gen_start
        mix = self._mix[:frames]
        mix.fill(0)
        start_pos = self._pos
        filled = 0
        g1 = self._gains
        g0 = self._gains_cur if len(self._gains_cur) == len(g1) else g1
        ramping = not np.array_equal(g0, g1)
        if ramping and len(self._ramp) != frames:
            self._ramp = np.linspace(0.0, 1.0, frames, dtype=np.float32)
        q = self._queue
        while self._playing and filled < frames:
            if self._cur is None:
                if not q:
                    break
                item_gen, block = q.popleft()
                if item_gen != gen:
                    continue
                if block is None:
                    self._ended_flag = True
                    break
                self._cur, self._cur_off = block, 0
            cur, off = self._cur, self._cur_off
            take = min(frames - filled, cur.shape[1] - off)
            seg = cur[:, off:off + take]
            if ramping:
                r = self._ramp[filled:filled + take]
                w = g0[:, None] + (g1 - g0)[:, None] * r[None, :]
                mix[filled:filled + take] += np.einsum("kt,ktc->tc", w, seg)
            else:
                mix[filled:filled + take] += np.tensordot(g1, seg, axes=(0, 0))
            filled += take
            self._cur_off = off + take
            if self._cur_off >= cur.shape[1]:
                self._cur = None
        self._gains_cur = g1
        self._underrun = filled < frames and not self._ended_flag
        self._pos = start_pos + filled
        self._consumed += filled
        m0, m1 = self._master_cur, self._master
        if m0 != m1:
            mix *= np.linspace(m0, m1, frames, dtype=np.float32)[:, None]
            self._master_cur = m1
        elif m1 != 1.0:
            mix *= m1
        outdata[:] = mix
        if filled and len(q) < 3:
            with self._cond:
                self._cond.notify()
        peak = np.abs(mix).max(axis=0)
        dac = time_info.outputBufferDacTime
        if dac <= 0:
            dac = self._stream.time + self._stream.latency
        r = self._ring[self._ring_i % RING]
        r[0], r[1], r[2], r[3] = dac, start_pos, peak[0], peak[1]
        self._ring_i += 1

    def _heard_frame(self):
        """Interpolates the frame at the DAC from the per-block timestamps."""
        if self._stream is None or not self._playing:
            return float(self._gen_start if self._cb_gen != self._gen else self._pos)
        if self._cb_gen != self._gen:
            return float(self._gen_start)
        now = self._stream.time
        ring = self._ring
        played = ring[(ring[:, 0] > 0) & (ring[:, 0] <= now)]
        if not len(played):
            return float(self._gen_start)
        last = played[np.argmax(played[:, 0])]
        return float(min(self._frames, last[1] + (now - last[0]) * self._sr))

    # ---- UI reads ----------------------------------------------------------------------
    @Slot(result="QVariantList")
    def snapshot(self):
        """[position seconds, peakL, peakR] for this frame."""
        pos = self._heard_frame() / self._sr if self._src is not None else 0.0
        if not self._playing or self._stream is None:
            return [pos, 0.0, 0.0]
        ring = self._ring
        played = ring[(ring[:, 0] > 0) & (ring[:, 0] <= self._stream.time)]
        if not len(played):
            return [pos, 0.0, 0.0]
        last = played[np.argmax(played[:, 0])]
        return [pos, float(last[2]), float(last[3])]

    def position(self):
        return self._heard_frame() / self._sr if self._src is not None else 0.0

    def _get_playing(self):
        return self._playing

    def _get_buffering(self):
        return self._buffering

    def _get_duration(self):
        return self._frames / self._sr if self._src is not None else 0.0

    def _get_volume(self):
        return self._volume

    playing = Property(bool, _get_playing, notify=playingChanged)
    buffering = Property(bool, _get_buffering, notify=bufferingChanged)
    duration = Property(float, _get_duration, notify=durationChanged)
    volume = Property(float, _get_volume, setVolume, notify=volumeChanged)
