"""Streaming playback: a decoder thread reads every stem ahead of the playhead in chunks,
and the PortAudio callback mixes them with per-stem gains and the master volume.

Nothing is decoded whole, so a song starts as soon as its first chunk is read (stems sit on
the network share) and memory stays flat. The callback never takes a lock: chunks travel
through a deque tagged with a generation number, and a seek or a new song bumps the
generation so stale chunks are dropped on the floor.

Each song is a Voice with its own queue. The next song can be queued ahead (set_next) and is
decoded in advance; the callback switches to it at the exact frame, gaplessly, or crossfaded:
the ending song then plays on as the fading tail beside it until its plan says stop.
"""

import collections
import itertools
import json
import subprocess
import threading
import time

import numpy as np
import sounddevice as sd
import soundfile as sf
from PySide6.QtCore import Property, QObject, QTimer, Signal, Slot

CHUNK = 8192  # frames per decoder read
AHEAD = 1.5  # seconds buffered ahead of the playhead
BLOCK = 1024
LATENCY = 0.15  # roomy: Python shares the GIL with library loading
RING = 64
MAX_STEMS = 8  # per-stem levels kept per block, for meters
CEILING = 0.985  # limiter: just under full scale
# Spectrum: each stem's post-fader signal is kept in a short tap, and every displayed frame
# analyses the window that ends at the sample being heard, so it moves at the panel's rate
# (240Hz), not the audio block rate.
TAP = 1 << 15  # frames of history per stem (> output latency + FFT window)
FFT = 2048  # analysis window
NFFT = 8192  # zero-padded, so even the bass bands are a bin or more wide
BANDS = 32
F_LO, F_HI = 40.0, 16000.0
FLOOR_DB, TOP_DB = -78.0, -12.0  # band level (with tilt) mapped to 0..1
TILT_DB = 3.0  # per octave about 1 kHz, so the highs read like they sound
FALL_S = 0.18  # release time constant


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

    def __init__(self, names, paths, sr=None):
        """sr forces a rate (resampled through ffmpeg), so a song can join a stream at another rate."""
        self.names = list(names)
        self.paths = list(paths)
        self.readers = []
        try:
            for p in paths:
                self.readers.append(open_reader(p, sr))
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

    def reopen(self, sr=None):
        """A fresh Source of the same files (optionally at another rate); this one is untouched."""
        src = Source(self.names, self.paths, sr)
        if hasattr(self, "fmt"):
            src.fmt = self.fmt
        return src

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




class Voice:
    """One song on its way to the speaker: its source, the blocks decoded ahead of the playhead
    (decoder thread), and where the callback is in them (audio thread)."""
    _serials = itertools.count(1)

    def __init__(self, src: Source, start=0):
        self.src = src
        self.serial = next(Voice._serials)
        self.frames = src.frames
        k = len(src.names)
        self.gains = np.ones(k, np.float32)
        self.gains_cur = self.gains.copy()
        self.queue = collections.deque()
        self.gen = 0
        self.restart(start)
        # audio thread
        self.cb_gen = -1
        self.cur = None
        self.cur_off = 0
        self.pos = self.gen_start
        self.ended = False
        # crossfade: as the tail, it fades from fade_from to silence at fade_to and stops at end;
        # as the incoming song, it fades in over fade_in frames.
        self.fade_from = self.fade_to = self.end = 0
        self.fade_in = 0
        self.fade_in_done = 0

    def restart(self, frame):
        """Starts decoding afresh from frame (engine lock held)."""
        frame = max(0, min(int(frame), max(0, self.frames - 1)))
        self.gen += 1
        self.gen_start = frame
        self.queue.clear()
        self.produced = self.consumed = 0
        self.eof = False
        self.ended = False
        self.seek_to = frame

    def buffered(self):
        return bool(self.queue) or self.eof


class PlayerEngine(QObject):
    playingChanged = Signal()
    bufferingChanged = Signal()
    volumeChanged = Signal()
    durationChanged = Signal()
    ended = Signal()
    # The queued next song is now what's heard (a gapless or crossfaded switch).
    advanced = Signal()
    failed = Signal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._main: Voice | None = None   # the song being mixed at full
        self._next: Voice | None = None   # queued, decoded ahead
        self._plan = None                 # how to cross into _next (frames), None for gapless
        self._tail: Voice | None = None   # the song that's ending, fading under _main
        self._shown: Voice | None = None  # the song the UI is on (lags a switch until it's heard)
        self._sr = 44100
        self._retired = collections.deque()  # sources the callback is done with; closed by the decoder
        self._to_close = []
        self._cond = threading.Condition()
        self._alive = True
        self._playing = False
        self._buffering = False
        self._ended_flag = False
        self._stream: sd.OutputStream | None = None
        self._volume = 0.8
        self._master = self._volume ** 3
        self._master_cur = self._master
        # callback-owned
        self._underrun = False
        self._mix = np.zeros((BLOCK * 8, 2), np.float32)
        self._tbuf = np.zeros((BLOCK * 8, 2), np.float32)
        self._ramp = np.zeros(0, np.float32)
        # per block: DAC time, main position, peak L/R, main serial, tail serial, tail position, stem levels
        self._ring = np.zeros((RING, 7 + MAX_STEMS), np.float64)
        self._eq = None  # object with process(buf, sr), swapped in whole from the GUI thread
        self._lim = 1.0
        self._ring_i = 0
        self._tap = np.zeros((MAX_STEMS, TAP), np.float32)
        self._win = np.hanning(FFT).astype(np.float32)
        self._bands_sr = None
        self._spec = np.zeros((0, BANDS), np.float32)
        self._spec_t = 0.0
        self._thread = threading.Thread(target=self._decode_loop, daemon=True, name="decoder")
        self._thread.start()
        # The callback only sets flags; this turns them into signals on the GUI thread.
        self._tick = QTimer(self)
        self._tick.setInterval(100)
        self._tick.timeout.connect(self._poll)
        self._tick.start()

    # ---- decoder thread --------------------------------------------------------------
    def _pick(self):
        """The voice that most needs decoding: one being sought, else the least buffered."""
        best, best_ahead = None, AHEAD
        for v in (self._main, self._tail, self._next):
            if v is None:
                continue
            if v.seek_to is not None:
                return v
            if v.eof:
                continue
            ahead = (v.produced - v.consumed) / v.src.sr
            if ahead < best_ahead:
                best, best_ahead = v, ahead
        return best

    def _decode_loop(self):
        while True:
            with self._cond:
                while self._alive and not self._to_close and not self._retired and self._pick() is None:
                    self._cond.wait(0.25)
                if not self._alive:
                    return
                closing, self._to_close = self._to_close, []
                while self._retired:
                    closing.append(self._retired.popleft())
                v = self._pick()
                if v is not None:
                    src, gen, seek = v.src, v.gen, v.seek_to
                    v.seek_to = None
            for s in closing:
                s.close()
            if v is None:
                continue
            try:
                if seek is not None:
                    src.seek(seek)
                block = src.read(CHUNK)
            except Exception as e:  # unreadable file mid-song: treat as its end
                print("decode error:", e, flush=True)
                block = np.zeros((len(src.names), 0, 2), np.float32)
            with self._cond:
                if gen != v.gen:
                    continue
                if block.shape[1] == 0:
                    v.eof = True
                    v.queue.append((gen, None))
                else:
                    v.queue.append((gen, block))
                    v.produced += block.shape[1]

    # ---- GUI thread API -----------------------------------------------------------------
    @property
    def rate(self):
        """The output's sample rate: a song queued with set_next must be at it."""
        return self._sr

    def set_source(self, src: Source, start_seconds=0.0, play=True):
        """Replaces the playing song. Takes ownership of [src]."""
        if self._stream is not None and self._stream.samplerate != src.sr:
            self._close_stream()
        with self._cond:
            self._drop_locked()
            self._sr = src.sr
            self._main = self._shown = Voice(src, int(start_seconds * src.sr))
            self._ring[:] = 0
            self._cond.notify()
        self._ended_flag = False
        self.durationChanged.emit()
        if play:
            self.play()

    def set_next(self, src: Source | None, plan=None, gains=None):
        """Queues the song to follow, decoded ahead so the switch is gapless; with a plan
        (crossfade.Plan, in seconds) it's crossfaded instead. Takes ownership of [src], which must be
        at the output's rate. Returns False (and closes it) when it can't follow."""
        with self._cond:
            if self._next is not None:
                self._to_close.append(self._next.src)
                self._next, self._plan = None, None
            if src is None:
                return True
            if self._main is None or src.sr != self._sr:
                self._to_close.append(src)
                self._cond.notify()
                return False
            sr = self._sr
            v = Voice(src, int(plan.in_offset * sr) if plan else 0)
            if gains is not None and len(gains) == len(v.gains):
                v.gains = np.asarray(gains, np.float32)
                v.gains_cur = v.gains.copy()
            if plan is not None:
                v.fade_in = int(plan.in_fade * sr)
                self._plan = {k: int(getattr(plan, k) * sr) for k in ("start", "fade_from", "fade_to", "end")}
            self._next = v
            self._cond.notify()
        return True

    def clear(self):
        self.pause()
        with self._cond:
            self._drop_locked()
            self._ring[:] = 0
            self._cond.notify()
        self.durationChanged.emit()

    def _drop_locked(self):
        for v in (self._main, self._next, self._tail):
            if v is not None:
                self._to_close.append(v.src)
        self._main = self._next = self._tail = self._shown = None
        self._plan = None

    def _end_crossfade_locked(self):
        """Drops the fading tail and puts the UI on the playing song (a pause or seek mid-crossfade)."""
        if self._tail is not None:
            self._to_close.append(self._tail.src)
            self._tail = None
        if self._main is not None and self._main.fade_in:
            self._main.fade_in_done = self._main.fade_in
        if self._shown is not self._main:
            self._shown = self._main
            QTimer.singleShot(0, self._announce)

    def _announce(self):
        self.durationChanged.emit()
        self.advanced.emit()

    @Slot()
    def play(self):
        if self._main is None or self._playing:
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
        v = self._main
        heard = self._heard(v) if v is not None else 0
        self._playing = False
        if self._stream is not None:
            self._stream.abort()  # instant; what was queued but unheard is decoded again below
        with self._cond:
            self._end_crossfade_locked()
            if v is not None:
                v.restart(heard)
            self._ring[:] = 0
            self._cond.notify()
        self.playingChanged.emit()

    @Slot()
    def toggle(self):
        self.pause() if self._playing else self.play()

    @Slot(float)
    def seek(self, seconds):
        with self._cond:
            self._end_crossfade_locked()
            if self._main is not None:
                self._main.restart(int(seconds * self._sr))
                self._main.ended = False
            self._ring[:] = 0
            self._cond.notify()
        self._ended_flag = False

    def set_eq(self, eq):
        """Inserts an equalizer after the mixer (None bypasses it)."""
        self._eq = eq

    def set_gains(self, gains):
        """Stem gains for the song the UI is on (the playing one; a tail keeps what it had)."""
        v = self._shown
        g = np.asarray(gains, np.float32)
        if v is not None and len(g) == len(v.gains):
            v.gains = g

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
            self._cond.notify()
        # The decoder may be mid-read: close nothing until it's stopped.
        self._thread.join(timeout=5)
        for v in (self._main, self._next, self._tail):
            if v is not None:
                v.src.close()
        for s in self._to_close + list(self._retired):
            s.close()

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
        main = self._main
        # A switch to the next song is announced once it's what's coming out of the speaker.
        if main is not None and self._shown is not main and self._played_rows(main).shape[0]:
            self._shown = main
            self._announce()
        # Wait for the device to play out what's queued, or the song's last moment is cut.
        if self._ended_flag and self._playing and main is not None and self._heard(main) >= main.pos - BLOCK:
            self._ended_flag = False
            self._playing = False
            if self._stream is not None:
                self._stream.abort()
            self.playingChanged.emit()
            self.ended.emit()
            return
        b = self._playing and self._underrun and not (main is not None and main.eof)
        if b != self._buffering:
            self._buffering = b
            self.bufferingChanged.emit()

    # ---- audio thread -----------------------------------------------------------------
    def _pull(self, v: Voice, out, off, count, live):
        """Mixes up to count frames of v into out[off:], with its stem gains (ramped when they
        changed). Returns the frames mixed; sets v.ended at its end of file."""
        if v.gen != v.cb_gen:
            v.cb_gen = v.gen
            v.cur = None
            v.pos = v.gen_start
            v.ended = False
        g1 = v.gains
        g0 = v.gains_cur if len(v.gains_cur) == len(g1) else g1
        ramping = not np.array_equal(g0, g1)
        if ramping and len(self._ramp) != count:
            self._ramp = np.linspace(0.0, 1.0, count, dtype=np.float32)
        filled, q, gen = 0, v.queue, v.gen
        lv = self._lv if live else None
        while filled < count:
            if v.cur is None:
                if not q:
                    break
                item_gen, block = q.popleft()
                if item_gen != gen:
                    continue
                if block is None:
                    v.ended = True
                    break
                v.cur, v.cur_off = block, 0
            cur, o = v.cur, v.cur_off
            take = min(count - filled, cur.shape[1] - o)
            seg = cur[:, o:o + take]
            dst = out[off + filled:off + filled + take]
            if ramping:
                r = self._ramp[filled:filled + take]
                w = g0[:, None] + (g1 - g0)[:, None] * r[None, :]
                dst += np.einsum("kt,ktc->tc", w, seg)
            else:
                dst += np.tensordot(g1, seg, axes=(0, 0))
            if live and take:
                if len(lv) == len(g1):
                    np.maximum(lv, np.abs(seg).max(axis=(1, 2)) * np.abs(g1), out=lv)
                self._write_tap(seg, w if ramping else g1[:, None], v.pos + filled)
            filled += take
            v.cur_off = o + take
            if v.cur_off >= cur.shape[1]:
                v.cur = None
        v.gains_cur = g1
        v.pos += filled
        v.consumed += filled
        return filled

    def _cross(self, main, nxt, plan):
        """main becomes the fading tail, nxt the playing song."""
        late = main.pos - plan["start"]
        main.fade_from, main.fade_to, main.end = plan["fade_from"], plan["fade_to"], plan["end"]
        # Started late (a seek into the overlap): fade over what's left.
        if late > self._sr // 10 and main.fade_to > main.fade_from:
            main.fade_from = max(main.fade_from, main.pos)
            main.fade_to = min(main.end, max(main.fade_to, main.fade_from + self._sr // 2))
        self._tail = main
        self._main, self._next, self._plan = nxt, None, None
        self._tap.fill(0)

    def _cut(self, main, nxt):
        """nxt follows main straight on (gapless)."""
        self._retired.append(main.src)
        self._main, self._next, self._plan = nxt, None, None
        nxt.fade_in = 0
        self._ended_flag = False
        self._tap.fill(0)

    def _callback(self, outdata, frames, time_info, status):
        mix = self._mix[:frames]
        mix.fill(0)
        main0 = self._main
        start_pos = main0.pos if main0 is not None and main0.cb_gen == main0.gen else (main0.gen_start if main0 else 0)
        if main0 is not None and len(getattr(self, "_lv", ())) != len(main0.gains):
            self._lv = np.zeros(len(main0.gains), np.float32)
        lv = getattr(self, "_lv", np.zeros(0, np.float32))
        lv.fill(0)
        switched = None  # where in this block the next song came in
        fade_from_off = 0
        off = 0
        while self._playing and off < frames:
            main = self._main
            if main is None:
                break
            nxt, plan = self._next, self._plan
            if main.ended:
                # At its end: straight on into the next song if it's ready.
                if nxt is not None and nxt.buffered():
                    self._cut(main, nxt)
                    switched = off if switched is None else switched
                    continue
                self._ended_flag = nxt is None
                break
            limit = frames - off
            if nxt is not None and plan is not None and self._tail is None and main.gen == main.cb_gen:
                until = plan["start"] - main.pos
                if until <= 0 and nxt.buffered():
                    if plan["start"] >= plan["end"] or main.pos >= plan["end"]:
                        self._cut(main, nxt)  # no overlap: cut over at the last sound
                    else:
                        self._cross(main, nxt, plan)
                    switched = off if switched is None else switched
                    fade_from_off = off
                    continue
                if until > 0:
                    limit = min(limit, until)
            n = self._pull(main, mix, off, limit, live=True)
            if main.fade_in > main.fade_in_done and n:
                k = np.arange(main.fade_in_done, main.fade_in_done + n, dtype=np.float64) / main.fade_in
                mix[off:off + n] *= np.sin(np.clip(k, 0, 1) * (np.pi / 2)).astype(np.float32)[:, None]
                main.fade_in_done += n
            off += n
            if n < limit and not main.ended:
                break  # underrun
        filled = off
        tail = self._tail
        tail_pos = tail.pos if tail is not None else 0
        if tail is not None and self._playing:
            begin = fade_from_off if switched is not None else 0
            count = min(frames - begin, max(0, tail.end - tail.pos))
            tb = self._tbuf[:frames]
            tb[begin:begin + count] = 0
            p0 = tail.pos
            n = self._pull(tail, tb, begin, count, live=False) if count > 0 else 0
            if n:
                span = tail.fade_to - tail.fade_from
                if span > 0:
                    x = (np.arange(p0, p0 + n, dtype=np.float64) - tail.fade_from) / span
                    g = np.cos(np.clip(x, 0, 1) * (np.pi / 2)).astype(np.float32)
                    mix[begin:begin + n] += tb[begin:begin + n] * g[:, None]
                else:
                    mix[begin:begin + n] += tb[begin:begin + n]
                filled = max(filled, begin + n)
            if tail.pos >= tail.end or tail.ended or (n < count and tail.eof and not tail.queue):
                self._retired.append(tail.src)
                self._tail = None
        main = self._main
        self._underrun = filled < frames and not self._ended_flag
        eq = self._eq
        if eq is not None and filled:
            eq.process(mix, self._sr)
        m0, m1 = self._master_cur, self._master
        if m0 != m1:
            mix *= np.linspace(m0, m1, frames, dtype=np.float32)[:, None]
            self._master_cur = m1
        elif m1 != 1.0:
            mix *= m1
        self._limit(mix, frames)
        outdata[:] = mix
        if len(self._retired) or (main is not None and len(main.queue) < 3) or (tail is not None and len(tail.queue) < 3):
            with self._cond:
                self._cond.notify()
        peak = np.abs(mix).max(axis=0)
        dac = time_info.outputBufferDacTime
        if dac <= 0:
            dac = self._stream.time + self._stream.latency
        r = self._ring[self._ring_i % RING]
        r[0], r[2], r[3] = dac, peak[0], peak[1]
        if main is not None:
            # The new song's frame at the block's start, as if it had begun earlier, so positions run on.
            r[1] = (main.gen_start - switched) if switched is not None and main is not main0 else start_pos
            r[4] = main.serial
        else:
            r[1] = r[4] = 0
        if switched is not None and main0 is not None and main0 is not main:
            r[5], r[6] = main0.serial, start_pos   # the song that was playing, until it's heard leaving
        elif tail is not None:
            r[5], r[6] = tail.serial, tail_pos
        else:
            r[5] = r[6] = 0
        n = min(len(lv), MAX_STEMS)
        r[7:7 + n] = lv[:n]
        r[7 + n:] = 0
        self._ring_i += 1

    def _write_tap(self, seg, w, frame):
        """Post-fader mono of each stem into the spectrum tap, indexed by song frame."""
        k = min(seg.shape[0], MAX_STEMS)
        mono = (seg[:k, :, 0] + seg[:k, :, 1]) * (0.5 * w[:k])
        i = int(frame) % TAP
        n = min(mono.shape[1], TAP - i)
        self._tap[:k, i:i + n] = mono[:, :n]
        if n < mono.shape[1]:
            self._tap[:k, :mono.shape[1] - n] = mono[:, n:]

    def _limit(self, mix, frames):
        """Peak limiter on the master bus, so stems pushed past unity or an EQ boost never clip:
        it drops within a few samples when a block would clip, and recovers over ~120 ms."""
        g0 = self._lim
        peak = float(np.abs(mix).max()) if frames else 0.0
        need = CEILING / peak if peak > CEILING else 1.0
        if need < g0:
            g1 = need
            ramp = np.full(frames, g1, np.float32)
            k = min(frames, 32)
            ramp[:k] = np.linspace(g0, g1, k, dtype=np.float32)
        else:
            g1 = min(need, g0 + (1.0 - g0) * (1.0 - np.exp(-frames / (0.12 * self._sr))))
            if g0 == 1.0 and g1 == 1.0:
                return
            ramp = np.linspace(g0, g1, frames, dtype=np.float32)
        mix *= ramp[:, None]
        np.clip(mix, -CEILING, CEILING, out=mix)
        self._lim = g1

    def _played_rows(self, v):
        """Ring rows already at the DAC that place v: (dac time, v's frame then)."""
        if self._stream is None:
            return np.zeros((0, 2))
        now = self._stream.time
        ring = self._ring
        done = (ring[:, 0] > 0) & (ring[:, 0] <= now)
        as_main = ring[done & (ring[:, 4] == v.serial)][:, [0, 1]]
        as_tail = ring[done & (ring[:, 5] == v.serial)][:, [0, 6]]
        return np.concatenate([as_main, as_tail]) if len(as_tail) else as_main

    def _heard(self, v):
        """Interpolates v's frame at the DAC from the per-block timestamps."""
        if self._stream is None or not self._playing or v.cb_gen != v.gen:
            return float(v.gen_start if v.cb_gen != v.gen else v.pos)
        rows = self._played_rows(v)
        if not len(rows):
            return float(v.gen_start)
        last = rows[np.argmax(rows[:, 0])]
        return float(max(0.0, min(v.frames, last[1] + (self._stream.time - last[0]) * self._sr)))

    # ---- UI reads ----------------------------------------------------------------------
    def _last_played(self):
        if not self._playing or self._stream is None:
            return None
        ring = self._ring
        played = ring[(ring[:, 0] > 0) & (ring[:, 0] <= self._stream.time)]
        return played[np.argmax(played[:, 0])] if len(played) else None

    @Slot(result="QVariantList")
    def snapshot(self):
        """[position seconds, peakL, peakR] for this frame."""
        pos = self.position()
        last = self._last_played()
        return [pos, 0.0, 0.0] if last is None else [pos, float(last[2]), float(last[3])]

    @Slot(result="QVariantList")
    def levels(self):
        """Per-stem peak levels (after faders) of the block at the DAC now."""
        last = self._last_played()
        return [] if last is None else [float(v) for v in last[7:]]

    @Slot(result="QVariantList")
    def spectrum(self):
        """Per-stem spectra of what's at the DAC now: BANDS values (0..1, log-spaced F_LO..F_HI)
        per stem, stem after stem. Peaks hold and fall back over FALL_S; all zeros once settled."""
        now = time.monotonic()
        main = self._main
        k = min(len(main.gains), MAX_STEMS) if main is not None else 0
        if self._spec.shape[0] != k:
            self._spec = np.zeros((k, BANDS), np.float32)
        spec = self._spec
        spec *= np.exp(-min(0.25, now - self._spec_t) / FALL_S)
        self._spec_t = now
        if k and self._playing and self._stream is not None:
            if self._bands_sr != self._sr:
                self._make_bands(self._sr)
            h = int(self._heard(main))
            x = self._tap[:k, (np.arange(h - FFT, h)) % TAP] * self._win
            mag = np.abs(np.fft.rfft(x, NFFT, axis=1))[:, self._b0:self._b1]
            band = np.maximum.reduceat(mag, self._starts, axis=1)
            db = 20 * np.log10(band * (4.0 / FFT) + 1e-9) + self._tilt
            np.maximum(spec, np.clip((db - FLOOR_DB) / (TOP_DB - FLOOR_DB), 0, 1), out=spec)
        spec[spec < 0.002] = 0
        return spec.ravel().tolist()

    def _make_bands(self, sr):
        hi = min(F_HI, sr * 0.45)
        b = np.round(np.geomspace(F_LO, hi, BANDS + 1) * NFFT / sr).astype(int)
        for i in range(1, len(b)):  # never empty, should the rate be very high
            b[i] = max(b[i], b[i - 1] + 1)
        self._b0, self._b1 = b[0], b[-1]
        self._starts = b[:-1] - b[0]
        centre = np.sqrt(b[:-1] * b[1:]) * sr / NFFT
        self._tilt = (TILT_DB * np.log2(centre / 1000.0)).astype(np.float32)
        self._bands_sr = sr

    def position(self):
        v = self._shown
        return self._heard(v) / self._sr if v is not None else 0.0

    def _get_playing(self):
        return self._playing

    def _get_buffering(self):
        return self._buffering

    def _get_duration(self):
        v = self._shown
        return v.frames / self._sr if v is not None else 0.0

    def _get_volume(self):
        return self._volume

    playing = Property(bool, _get_playing, notify=playingChanged)
    buffering = Property(bool, _get_buffering, notify=bufferingChanged)
    duration = Property(float, _get_duration, notify=durationChanged)
    volume = Property(float, _get_volume, setVolume, notify=volumeChanged)
    spectrumRange = Property("QVariantList", lambda self: [BANDS, F_LO, F_HI], constant=True)
