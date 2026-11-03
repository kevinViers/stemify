package com.stemify.mobile

import com.stemify.mobile.engine.Crossfade
import com.stemify.mobile.engine.CrossfadeMode
import com.stemify.mobile.engine.CrossfadePlan
import com.stemify.mobile.engine.Envelope
import com.stemify.mobile.engine.LoudnessMeter
import com.stemify.mobile.engine.Resampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CrossfadeTest {
    /** Loudness readings: (seconds, level in dB or a 0..1 -> dB ramp) in order. */
    private fun env(start: Double, vararg parts: Pair<Double, (Double) -> Float>): Envelope {
        val db = ArrayList<Float>()
        for ((secs, level) in parts) {
            val n = (secs / Crossfade.HOP).roundToInt()
            for (i in 0 until n) db += level(i.toDouble() / n)
        }
        return Envelope(start, db.toFloatArray())
    }

    private fun flat(db: Float): (Double) -> Float = { db }
    private val dur = 200.0
    private val fadeOut = env(dur - 25, 15.0 to flat(-12f), 8.0 to { x -> (-12 - 50 * x).toFloat() }, 2.0 to flat(-90f))
    private val coldOut = env(dur - 25, 22.0 to flat(-12f), 3.0 to flat(-90f))
    private val loudIn = env(0.0, 0.5 to flat(-90f), 19.5 to flat(-12f))
    private val quietIn = env(0.0, 1.0 to flat(-90f), 6.0 to flat(-30f), 13.0 to flat(-12f))

    private fun assertPlan(expected: CrossfadePlan, actual: CrossfadePlan?) {
        requireNotNull(actual)
        val e = listOf(expected.startS, expected.fadeFromS, expected.fadeToS, expected.endS, expected.inOffsetS, expected.inFadeS)
        val a = listOf(actual.startS, actual.fadeFromS, actual.fadeToS, actual.endS, actual.inOffsetS, actual.inFadeS)
        for (i in e.indices) assertEquals("field $i of $actual", e[i], a[i], 1e-6)
    }

    // Same cases and answers as the desktop planner (stemify/player/crossfade.py).
    @Test fun fadingSongIsCrossedOverItsFade() {
        assertPlan(CrossfadePlan(192.4, 192.4, 196.4, 196.4, 0.5, 4.0), Crossfade.plan(CrossfadeMode.Short, 6, fadeOut, dur, loudIn, 180.0))
        assertPlan(CrossfadePlan(191.5, 191.5, 196.4, 196.4, 0.5, 4.9), Crossfade.plan(CrossfadeMode.Long, 6, fadeOut, dur, loudIn, 180.0))
    }

    @Test fun quietIntroNeedsLittleFadeIn() {
        assertPlan(CrossfadePlan(191.5, 191.5, 196.4, 196.4, 1.0, 1.0), Crossfade.plan(CrossfadeMode.Medium, 6, fadeOut, dur, quietIn, 180.0))
    }

    @Test fun coldEndingLetsAQuietIntroStartUnderIt() {
        assertPlan(CrossfadePlan(195.0, 197.0, 197.0, 197.0, 1.0, 0.0), Crossfade.plan(CrossfadeMode.Short, 6, coldOut, dur, quietIn, 180.0))
        assertPlan(CrossfadePlan(194.075, 197.0, 197.0, 197.0, 1.0, 0.0), Crossfade.plan(CrossfadeMode.Long, 6, coldOut, dur, quietIn, 180.0))
    }

    @Test fun coldEndingIntoALoudStartJustTrimsTheSilence() {
        assertPlan(CrossfadePlan(197.0, 197.0, 197.0, 197.0, 0.5, 0.0), Crossfade.plan(CrossfadeMode.Long, 6, coldOut, dur, loudIn, 180.0))
    }

    @Test fun fixedOverlapsByItsSeconds() {
        assertPlan(CrossfadePlan(190.4, 190.4, 196.4, 196.4, 0.5, 6.0), Crossfade.plan(CrossfadeMode.Fixed, 6, fadeOut, dur, loudIn, 180.0))
    }

    @Test fun unmeasuredSongsGetHalfTheCap() {
        assertPlan(CrossfadePlan(196.5, 196.5, 200.0, 200.0, 0.0, 3.5), Crossfade.plan(CrossfadeMode.Medium, 6, null, dur, null, 180.0))
    }

    @Test fun offAndShortSongs() {
        assertNull(Crossfade.plan(CrossfadeMode.Off, 6, fadeOut, dur, loudIn, 180.0))
        // A 10-second song overlaps at most 40% of itself.
        val p = Crossfade.plan(CrossfadeMode.Fixed, 12, null, 10.0, null, 180.0)!!
        assertEquals(4.0, p.endS - p.startS, 1e-9)
    }

    @Test fun albumTracksInOrderAreConsecutive() {
        assertTrue(Crossfade.consecutive("a", 1, 3, "a", 1, 4))
        assertTrue(Crossfade.consecutive("a", 1, 12, "a", 2, 1))
        assertTrue(!Crossfade.consecutive("a", 1, 3, "a", 1, 5))
        assertTrue(!Crossfade.consecutive("a", 1, 3, "b", 1, 4))
        assertTrue(!Crossfade.consecutive(null, 1, 3, null, 1, 4))
    }

    @Test fun equalPowerGainsKeepPower() {
        for (i in 0..10) {
            val x = i / 10.0
            val o = Crossfade.fadeOutGain(x)
            val n = Crossfade.fadeInGain(x)
            assertEquals(1.0, (o * o + n * n).toDouble(), 1e-5)
        }
    }

    @Test fun meterReadsAFullScaleSineAtMinus3Db() {
        val rate = 48_000
        val m = LoudnessMeter(rate)
        val buf = FloatArray(rate * 2)
        for (f in 0 until rate) {
            val v = sin(2 * PI * 1000 * f / rate).toFloat()
            buf[f * 2] = v
            buf[f * 2 + 1] = v
        }
        m.add(buf, rate)
        val e = m.result(0.0)
        assertEquals(20, e.db.size)
        for (v in e.db) assertEquals(-3.01, v.toDouble(), 0.05)
    }

    /** Resamples a sine, checks its pitch and level survive and that every input frame comes out. */
    private fun resample(inRate: Int, outRate: Int, hz: Double) {
        val frames = inRate * 2
        val src = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val v = (0.5 * sin(2 * PI * hz * f / inRate)).toFloat()
            src[f * 2] = v
            src[f * 2 + 1] = -v
        }
        var read = 0
        val rs = Resampler(inRate, outRate)
        val out = ArrayList<Float>()
        val chunk = FloatArray(700 * 2)
        while (true) {
            val n = rs.process(chunk, 700, { buf, off, want ->
                val k = minOf(want, frames - read)
                System.arraycopy(src, read * 2, buf, off * 2, k * 2)
                read += k
                k
            }, { read >= frames })
            if (n == 0) break
            for (i in 0 until n * 2) out += chunk[i]
        }
        val outFrames = out.size / 2
        val expected = frames.toLong() * outRate / inRate
        assertTrue("$outFrames frames out, expected about $expected", abs(outFrames - expected) <= 2)
        // Away from the ends, compare to the ideal sine at the output rate.
        var err = 0.0
        var count = 0
        for (j in 200 until outFrames - 200) {
            val ideal = 0.5 * sin(2 * PI * hz * j / outRate)
            err = max(err, abs(out[j * 2] - ideal))
            assertEquals(-out[j * 2], out[j * 2 + 1], 1e-6f)
            count++
        }
        assertTrue("max error $err", err < 2e-3)
        assertTrue(count > 0)
    }

    @Test fun resamplesUp() = resample(44_100, 48_000, 1_000.0)

    @Test fun resamplesDown() = resample(48_000, 44_100, 5_000.0)

    @Test fun resamplesHighRates() = resample(96_000, 48_000, 10_000.0)

    @Test fun downsamplingRemovesWhatCantBeKept() {
        // 30 kHz can't exist at 48 kHz: it must come out nearly silent, not folded down to 18 kHz.
        val inRate = 96_000
        val frames = inRate
        var read = 0
        val rs = Resampler(inRate, 48_000)
        val chunk = FloatArray(1024 * 2)
        var sum = 0.0
        var n = 0
        while (true) {
            val k = rs.process(chunk, 1024, { buf, off, want ->
                val m = minOf(want, frames - read)
                for (f in 0 until m) {
                    val v = sin(2 * PI * 30_000 * (read + f) / inRate).toFloat()
                    buf[(off + f) * 2] = v
                    buf[(off + f) * 2 + 1] = v
                }
                read += m
                m
            }, { read >= frames })
            if (k == 0) break
            for (i in 0 until k) { sum += chunk[i * 2] * chunk[i * 2]; n++ }
        }
        val rms = sqrt(sum / n)
        assertTrue("rms $rms", rms < 0.01)
    }
}
