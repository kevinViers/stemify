package com.stemify.mobile.engine

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming sample-rate converter for interleaved stereo floats: windowed sinc (Kaiser, β 9),
 * [TAPS] taps, [PHASES] phases interpolated linearly. When a song joins an output that's already
 * running at another rate (a crossfade, or a gapless change from 44.1 to 48 kHz), it's converted
 * here instead of reopening the AudioTrack, which would leave a gap.
 *
 * Output frame j lines up with input frame j·inRate/outRate, so positions map by the ratio.
 */
class Resampler(val inRate: Int, val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val table: FloatArray

    // Input frames [0, have) of the current window; pos is the next output's place in it.
    private var buf = FloatArray(8192 * 2)
    private var have = HALF  // starts with HALF frames of silence, so output 0 sits on input 0
    private var pos = HALF.toDouble()
    private var eos = false
    private var end = Double.MAX_VALUE  // pos at which the real input runs out

    init {
        // Cut off just under the lower Nyquist, so downsampling doesn't alias.
        val fc = min(1.0, outRate.toDouble() / inRate) * 0.97
        table = FloatArray((PHASES + 1) * TAPS)
        for (p in 0..PHASES) {
            val frac = p.toDouble() / PHASES
            var total = 0.0
            for (k in 0 until TAPS) {
                val x = (k - HALF + 1) - frac  // tap k reads input frame i + k - HALF + 1
                val v = fc * sinc(fc * x) * kaiser(x / HALF)
                table[p * TAPS + k] = v.toFloat()
                total += v
            }
            for (k in 0 until TAPS) table[p * TAPS + k] = (table[p * TAPS + k] / total).toFloat()  // unity at DC
        }
    }

    /** True once every input frame has come out. */
    val drained get() = eos && pos >= end

    /**
     * Writes up to [max] frames to [dst]. [pull] fills its buffer from frame offset `off` with up
     * to `n` input frames and returns how many it gave; [finished] says the input has ended for
     * good (not just that nothing is ready yet). Returns the frames written.
     */
    fun process(dst: FloatArray, max: Int, pull: (buf: FloatArray, off: Int, n: Int) -> Int, finished: () -> Boolean): Int {
        var out = 0
        while (out < max) {
            if (eos && pos >= end) break
            val i = floor(pos).toInt()
            if (i + HALF >= have) {
                if (eos) {
                    pad(i + HALF + 1 - have)
                } else {
                    compact()
                    val want = min(1024, buf.size / 2 - have)
                    val got = pull(buf, have, want)
                    if (got > 0) {
                        have += got
                    } else if (finished()) {
                        eos = true
                        end = have.toDouble()
                    } else break
                }
                continue
            }
            val frac = pos - i
            val pf = frac * PHASES
            val p = pf.toInt()
            val t = (pf - p).toFloat()
            val a = p * TAPS
            val b = a + TAPS
            var l = 0f
            var r = 0f
            var src = (i - HALF + 1) * 2
            for (k in 0 until TAPS) {
                val w = table[a + k] + (table[b + k] - table[a + k]) * t
                l += buf[src] * w
                r += buf[src + 1] * w
                src += 2
            }
            dst[out * 2] = l
            dst[out * 2 + 1] = r
            out++
            pos += step
        }
        return out
    }

    /** Input frames each output frame consumes, for converting buffer sizes. */
    val ratio get() = step

    private fun pad(frames: Int) {
        if (frames <= 0) return
        compact()
        val n = min(frames, buf.size / 2 - have)
        java.util.Arrays.fill(buf, have * 2, (have + n) * 2, 0f)
        have += n
    }

    /** Drops input no longer needed, keeping the TAPS-frame window before pos. */
    private fun compact() {
        val keep = floor(pos).toInt() - HALF + 1
        if (keep <= 0) return
        // Cheap while little is left to move; always once there's no room for the next pull.
        if (have - keep < buf.size / 4 || buf.size / 2 - have < 4096) {
            System.arraycopy(buf, keep * 2, buf, 0, (have - keep) * 2)
            have -= keep
            pos -= keep
            if (end != Double.MAX_VALUE) end -= keep
        }
    }

    companion object {
        const val HALF = 16
        const val TAPS = HALF * 2
        const val PHASES = 512
        private const val BETA = 9.0

        private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            return bessel0(BETA * sqrt(1 - x * x)) / bessel0(BETA)
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            for (k in 1..30) {
                term *= q / (k * k)
                sum += term
            }
            return sum
        }
    }
}
