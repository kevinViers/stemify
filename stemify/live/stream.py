"""Live audio path: capture the "Stemify Live" sink, separate it hop by hop, mix the stems out.

    apps -> [Stemify Live sink] -> monitor -> audio callback -> capture ring
                                                                    |
                                       separator thread: window -> model -> one hop per stem
                                                                    |
    speakers <- audio callback (mixer) <-------------------------- stem ring

One PortAudio duplex stream on PipeWire's JACK layer does both ends, so capture and playback
share a graph cycle and the wiring is explicit (Live monitor in, chosen speaker out). The
callback only copies and scales numpy arrays: it never waits on the separator. Output runs a
fixed [delay] behind the input; a hop the separator hasn't finished by then plays as the dry
mix from the capture ring instead (a "miss"), so a slow pass costs a moment of unseparated
audio, never a click.

Frame positions are absolute counts since start, at the engine rate (48 kHz).
"""

import ctypes
import threading
import time

import numpy as np

from . import sink as sinkmod

RATE = 48000
QUANTUM = 256  # frames per graph cycle we ask PipeWire for (5.3 ms)
CEILING = 0.985


class Ring:
    """Single-producer frame ring of shape (..., size, 2), addressed by absolute frame position.

    The producer copies data in, then advances [end]; a reader may use [start, end) where
    start = max(valid_from, end - size). Ints are swapped whole under the GIL, so readers on
    another thread see either the old range or the new one, never a torn copy.
    """

    def __init__(self, size, lead=()):
        self.size = size
        self.buf = np.zeros((*lead, size, 2), np.float32)
        self.end = 0
        self.valid_from = 0

    def write(self, pos, data):
        """Writes data (..., n, 2) at [pos, pos + n) and makes it readable up to pos + n."""
        n = data.shape[-2]
        i = pos % self.size
        k = min(n, self.size - i)
        self.buf[..., i:i + k, :] = data[..., :k, :]
        if k < n:
            self.buf[..., :n - k, :] = data[..., k:, :]
        if pos > self.end:  # a gap: what lay before pos was never written
            self.valid_from = pos
        self.end = pos + n

    def has(self, pos, n):
        # [end] first: the writer moves valid_from before end, so seeing a new end means
        # seeing its valid_from too, and a gap is never mistaken for data.
        end = self.end
        return pos >= max(self.valid_from, end - self.size) and pos + n <= end

    def read(self, pos, n, out=None):
        """[pos, pos + n) as (..., n, 2); positions before 0 read as silence."""
        if out is None:
            out = np.empty((*self.buf.shape[:-2], n, 2), np.float32)
        lead = min(n, max(0, -pos))
        if lead:
            out[..., :lead, :] = 0
        p, m = pos + lead, n - lead
        i = p % self.size
        k = min(m, self.size - i)
        out[..., lead:lead + k, :] = self.buf[..., i:i + k, :]
        if k < m:
            out[..., lead + k:, :] = self.buf[..., :m - k, :]
        return out


class Passthrough:
    """The separator interface, doing nothing: one stem, the input itself.

    A separator has: stems (names), hop / context / lookahead / xfade (frames at RATE), budget
    (frames of compute time to allow for in the delay), and process(window) taking (n, 2) at
    RATE and returning (stems, n, 2) at RATE for the same frames.
    """

    stems = ["mix"]

    def __init__(self, hop=QUANTUM):
        self.hop = hop
        self.context = 0
        self.lookahead = 0
        self.xfade = 0
        self.budget = QUANTUM

    def warmup(self):
        pass

    def process(self, window):
        return window[None]


def _set_jack_client_name(sd, name):
    """PortAudio names its JACK client "PortAudio" unless told before it initializes."""
    try:
        lib = ctypes.CDLL(sd._libname)
        lib.PaJack_SetClientName(name.encode())
    except (OSError, AttributeError):
        pass


def _jack_device(sd, description, need_in=0, need_out=0):
    for i, d in enumerate(sd.query_devices()):
        api = sd.query_hostapis(d["hostapi"])["name"]
        if (api.startswith("JACK") and d["name"] == description
                and d["max_input_channels"] >= need_in and d["max_output_channels"] >= need_out):
            return i
    raise RuntimeError(f"no JACK device named {description!r}; is pipewire-jack installed?")


class LiveEngine:
    """Owns the sink, the duplex stream and the separator thread.

    [output] is the PipeWire node name of the speaker to play to (default: the default sink).
    """

    def __init__(self, separator=None, output=None, quantum=QUANTUM, sink_name=sinkmod.NAME,
                 sink_description=sinkmod.DESCRIPTION, client_name="Stemify Live Engine"):
        self.sep = separator or Passthrough()
        self.output = output
        self.quantum = quantum
        self.sink = sinkmod.VirtualSink(sink_name, sink_description, latency=quantum, rate=RATE)
        self.client_name = client_name
        sep = self.sep
        # Output lags input by this much; a hop has until then to be separated.
        self.delay = sep.hop + sep.lookahead + sep.xfade + sep.budget + quantum
        k = len(sep.stems)
        cap_size = 1 << int(np.ceil(np.log2(max(RATE * 4, 4 * (sep.context + sep.hop + sep.lookahead
                                                                   + sep.xfade + self.delay)))))
        self.cap = Ring(cap_size)
        self.stems = Ring(1 << int(np.ceil(np.log2(4 * (self.delay + sep.hop + quantum)))), (k,))
        self.gains = np.ones(k, np.float32)
        self.master = 1.0
        self._gains_cur = self.gains.copy()
        self._lim = 1.0
        self._mix = np.zeros((8192, 2), np.float32)
        self._wet = np.zeros((k, 8192, 2), np.float32)
        self._stream = None
        self._thread = None
        self._alive = False
        # stats (written by the callback / separator, read anywhere)
        self.blocks = 0
        self.xruns = 0
        self.dry_blocks = 0  # output blocks that played the dry mix
        self.misses = 0  # hops the separator skipped (fell too far behind)
        self.late = 0  # hops finished after their first frames had played
        self.hops = 0
        self.compute = []  # seconds per process() call, last 512
        self.events = []  # (seconds since start, "late" | "missed" | "slow"), last 100
        self.started = 0.0

    # ---- control -----------------------------------------------------------------------
    def start(self):
        self.sink.open()
        try:
            import sounddevice as sd

            # PortAudio lists JACK clients when it initializes: do it after the sink exists.
            sd._terminate()
            _set_jack_client_name(sd, self.client_name)
            sd._initialize()
            out_desc = self._output_description()
            dev_in = _jack_device(sd, self.sink.description, need_in=2)
            dev_out = _jack_device(sd, out_desc, need_out=2)
            self.sep.warmup()
            self._alive = True
            self._thread = threading.Thread(target=self._separate_loop, daemon=True, name="separator")
            self._thread.start()
            self._stream = sd.Stream(device=(dev_in, dev_out), channels=2, samplerate=RATE,
                                     dtype="float32", blocksize=0, latency="low", callback=self._callback)
            self._stream.start()
            self.started = time.monotonic()
        except Exception:
            self.stop()
            raise
        return self

    def stop(self):
        self._alive = False
        if self._stream is not None:
            try:
                self._stream.abort()
                self._stream.close()
            except Exception:
                pass
            self._stream = None
        if self._thread is not None:
            self._thread.join(2)
            self._thread = None
        self.sink.close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()

    def _output_description(self):
        name = self.output or sinkmod.default_sink_name()
        node = sinkmod.find_node(name)
        if node is None:
            raise RuntimeError(f"no PipeWire node named {name!r}")
        return node["info"]["props"].get("node.description", name)

    def set_gains(self, gains):
        g = np.asarray(gains, np.float32)
        if g.shape == self.gains.shape:
            self.gains = g

    def _event(self, kind):
        self.events.append((round(time.monotonic() - self.started, 2) if self.started else 0.0, kind))
        del self.events[:-100]

    def stats(self):
        c = np.array(self.compute[-512:]) * 1000 if self.compute else np.zeros(1)
        return {
            "seconds": time.monotonic() - self.started if self.started else 0.0,
            "delay_ms": self.delay / RATE * 1000,
            "blocks": self.blocks, "xruns": self.xruns, "dry_blocks": self.dry_blocks,
            "hops": self.hops, "misses": self.misses, "late": self.late,
            "compute_p50_ms": float(np.percentile(c, 50)), "compute_p99_ms": float(np.percentile(c, 99)),
            "compute_max_ms": float(c.max()), "events": list(self.events),
        }

    # ---- audio thread --------------------------------------------------------------------
    def _callback(self, indata, outdata, frames, time_info, status):
        if status.input_overflow or status.output_underflow or status.input_underflow or status.output_overflow:
            self.xruns += 1
        cap = self.cap
        pos_in = cap.end
        cap.write(pos_in, indata)
        pos = pos_in - self.delay  # the frames this block plays
        self.blocks += 1
        mix = self._mix[:frames]
        if pos + frames <= 0:
            outdata.fill(0)
            return
        g1 = self.gains
        g0 = self._gains_cur
        if self.stems.has(pos, frames):
            wet = self.stems.read(pos, frames, self._wet[:, :frames])
            if np.array_equal(g0, g1):
                mix[:] = np.tensordot(g1, wet, axes=(0, 0))
            else:
                r = np.linspace(0.0, 1.0, frames, dtype=np.float32)
                w = g0[:, None] + (g1 - g0)[:, None] * r[None, :]
                mix[:] = np.einsum("kt,ktc->tc", w, wet)
        else:
            cap.read(pos, frames, mix)
            self.dry_blocks += 1
        self._gains_cur = g1
        if self.master != 1.0:
            mix *= self.master
        self._limit(mix, frames)
        outdata[:] = mix

    def _limit(self, mix, frames):
        """Same peak limiter as the player's master bus: stems pushed past unity never clip."""
        g0 = self._lim
        peak = float(np.abs(mix).max()) if frames else 0.0
        need = CEILING / peak if peak > CEILING else 1.0
        if need < g0:
            g1 = need
            ramp = np.full(frames, g1, np.float32)
            k = min(frames, 32)
            ramp[:k] = np.linspace(g0, g1, k, dtype=np.float32)
        else:
            g1 = min(need, g0 + (1.0 - g0) * (1.0 - np.exp(-frames / (0.12 * RATE))))
            if g0 == 1.0 and g1 == 1.0:
                return
            ramp = np.linspace(g0, g1, frames, dtype=np.float32)
        mix *= ramp[:, None]
        np.clip(mix, -CEILING, CEILING, out=mix)
        self._lim = g1

    # ---- separator thread ----------------------------------------------------------------
    def _separate_loop(self):
        sep = self.sep
        hop, ctx = sep.hop, sep.context
        runner = HopRunner(sep)
        nxt = None
        while self._alive:
            have = self.cap.end
            if nxt is None:
                if have == 0:
                    time.sleep(0.002)
                    continue
                nxt = have - self.delay  # first hop: the one about to play
                nxt -= nxt % hop
            # Too late for this hop already? Skip to one that can still make it.
            playing = self.cap.end - self.delay
            if nxt < playing:
                skip = playing + self.quantum - nxt
                skip += (-skip) % hop
                self.misses += skip // hop
                self._event("missed")
                nxt += skip
                runner.reset()
            need = nxt + hop + runner.ahead
            if have < need:
                time.sleep(max(0.0005, min(0.01, (need - have) / RATE)))
                continue
            self.cap.read(nxt - ctx, runner.n, runner.window)
            t0 = time.perf_counter()
            out = runner.run()
            self.compute.append(time.perf_counter() - t0)
            if len(self.compute) > 1024:
                del self.compute[:512]
            if self.cap.end - self.delay > nxt:
                self.late += 1  # its start already played dry; the rest still plays separated
                self._event("late")
            elif self.compute[-1] * RATE > sep.budget:
                self._event("slow")  # over budget, but the slack still covered it
            self.stems.write(nxt, out)
            self.hops += 1
            nxt += hop


class HopRunner:
    """Turns one window into one hop per stem, crossfading each hop's start with the previous
    window's estimate of the same frames. Shared by the live thread and the offline simulator,
    so what evaluate.py measures is what plays."""

    def __init__(self, sep):
        self.sep = sep
        self.ahead = sep.lookahead + sep.xfade  # frames needed past the hop's end
        self.n = sep.context + sep.hop + self.ahead
        self.window = np.zeros((self.n, 2), np.float32)  # fill [hop start - context, ...) before run()
        xf = sep.xfade
        self._fade = np.linspace(0.0, 1.0, xf, dtype=np.float32)[None, :, None] if xf else None
        self._tail = None

    def reset(self):
        self._tail = None

    def run(self, est=None):
        """The next hop (stems, hop, 2); [est] is process(window) if already computed."""
        sep = self.sep
        hop, ctx, xf = sep.hop, sep.context, sep.xfade
        if est is None:
            est = sep.process(self.window)
        seg = est[:, ctx:ctx + hop + xf]
        if xf:
            if self._tail is not None:
                seg[:, :xf] = self._tail * (1 - self._fade) + seg[:, :xf] * self._fade
            self._tail = seg[:, hop:hop + xf].copy()
        return seg[:, :hop]
