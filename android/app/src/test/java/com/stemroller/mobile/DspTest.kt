package com.stemroller.mobile

import com.stemroller.mobile.engine.EQ_BANDS
import com.stemroller.mobile.engine.EqChannel
import com.stemroller.mobile.engine.EqFilter
import com.stemroller.mobile.engine.FilterType
import com.stemroller.mobile.eq.ApoConfig
import com.stemroller.mobile.eq.EqProfile
import com.stemroller.mobile.engine.EqMath
import com.stemroller.mobile.engine.EqParams
import com.stemroller.mobile.engine.Equalizer
import com.stemroller.mobile.engine.FloatRing
import com.stemroller.mobile.engine.Limiter
import com.stemroller.mobile.engine.stemTaper
import com.stemroller.mobile.engine.stemUntaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    private fun gains(vararg pairs: Pair<Int, Float>) = EqFilter.graphic(List(EQ_BANDS.size) { i -> pairs.firstOrNull { it.first == i }?.second ?: 0f })

    @Test fun flatOrCutsNeedNoPreamp() {
        assertEquals(0f, EqMath.autoPreampDb(gains()), 1e-4f)
        assertEquals(0f, EqMath.autoPreampDb(gains(3 to -6f, 7 to -12f)), 1e-4f)
    }

    @Test fun singleBoostIsCancelledExactly() {
        assertEquals(-6f, EqMath.autoPreampDb(gains(5 to 6f)), 0.1f)
        assertEquals(-12f, EqMath.autoPreampDb(gains(9 to 12f)), 0.15f)
    }

    @Test fun overlappingBoostsAddUp() {
        val p = EqMath.autoPreampDb(gains(1 to 6f, 2 to 6f))
        assertTrue("got $p", p < -6.5f)
    }

    @Test fun manualPreampCountsTowardsAntiClip() {
        assertEquals(0f, EqMath.autoPreampDb(gains(5 to 6f), manualDb = -6f), 0.1f)
        assertEquals(-3f, EqMath.autoPreampDb(gains(5 to 6f), manualDb = -3f), 0.1f)
    }

    /** A full-scale sine at the curve's peak must not clip once the auto preamp is applied. */
    @Test fun preampPreventsClippingAtThePeak() {
        val g = gains(0 to 9f, 1 to 9f, 2 to 6f, 8 to 4f)
        val pre = EqMath.autoPreampDb(g)
        val fs = 48000
        val freqs = EqMath.logFreqs(256)
        val resp = EqMath.response(g, freqs)
        val peakF = freqs[resp.indices.maxBy { resp[it] }]
        val eq = Equalizer().apply { configure(EqParams(g, pre), fs) }
        val n = fs * 2
        val buf = FloatArray(n * 2) { i -> sin(2 * PI * peakF * (i / 2) / fs).toFloat() }
        eq.process(buf, n)
        val settled = buf.copyOfRange(n, n * 2).maxOf { abs(it) }
        assertTrue("peak $settled at ${peakF.toInt()} Hz", settled <= 1.01f)
        assertTrue("too quiet: $settled", settled > 0.95f)
    }

    private fun at(f: EqFilter, hz: Double) = EqMath.response(listOf(f), doubleArrayOf(hz))[0]

    @Test fun shelvesReachTheirGain() {
        val ls = EqFilter("a", FilterType.LowShelf, 100f, 6f, 0.707f)
        assertEquals(6.0, at(ls, 20.0), 0.3)
        assertEquals(3.0, at(ls, 100.0), 0.3)
        assertEquals(0.0, at(ls, 5000.0), 0.1)
        val hs = EqFilter("b", FilterType.HighShelf, 5000f, -4f, 0.707f)
        assertEquals(-4.0, at(hs, 19000.0), 0.4)
        assertEquals(0.0, at(hs, 100.0), 0.1)
    }

    @Test fun passSlopesFallAtTheRightRate() {
        for (slope in listOf(6, 12, 24, 36, 48)) {
            val lp = EqFilter("lp", FilterType.LowPass, 1000f, slope = slope, q = 0.707f)
            // Two octaves above the corner, a Butterworth is down ~slope*2 dB, and -3 dB at the corner.
            val drop = at(lp, 4000.0) - at(lp, 8000.0)
            assertEquals("slope $slope", slope.toDouble(), drop, slope * 0.12 + 0.6)
            assertEquals("corner $slope", -3.0, at(lp, 1000.0), 0.3)
            val hp = EqFilter("hp", FilterType.HighPass, 1000f, slope = slope, q = 0.707f)
            assertEquals("hp slope $slope", slope.toDouble(), at(hp, 250.0) - at(hp, 125.0), slope * 0.12 + 0.6)
        }
    }

    @Test fun notchAndAllPass() {
        assertTrue(at(EqFilter("n", FilterType.Notch, 60f, q = 4f), 60.0) < -40)
        assertEquals(0.0, at(EqFilter("n", FilterType.Notch, 60f, q = 4f), 1000.0), 0.1)
        for (hz in listOf(50.0, 1000.0, 15000.0)) assertEquals(0.0, at(EqFilter("ap", FilterType.AllPass, 1000f), hz), 1e-6)
        assertEquals(0.0, at(EqFilter("bp", FilterType.BandPass, 1000f, q = 0.707f), 1000.0), 0.05)
    }

    @Test fun channelFiltersOnlyTouchTheirSide() {
        val f = EqFilter("l", FilterType.Peak, 1000f, 6f, 1.41f, channel = EqChannel.Left)
        val eq = Equalizer().apply { configure(EqParams(listOf(f), 0f), 48000) }
        val n = 48000
        val buf = FloatArray(n * 2) { i -> (0.25 * sin(2 * PI * 1000 * (i / 2) / 48000.0)).toFloat() }
        eq.process(buf, n)
        val l = (n / 2 until n).maxOf { abs(buf[it * 2]) }
        val r = (n / 2 until n).maxOf { abs(buf[it * 2 + 1]) }
        assertEquals(0.5, l.toDouble(), 0.02)
        assertEquals(0.25, r.toDouble(), 0.001)
    }

    @Test fun parsesAutoEqConfig() {
        val text = """
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
            Filter 2: ON PK Fc 2300 Hz Gain -3.1 dB Q 1.41
            Filter 3: OFF HSC Fc 10000 Hz Gain 2 dB Q 0.7
            Channel: R
            Filter 4: ON PK Fc 500 Hz Gain 1 dB BW Oct 1
            GraphicEQ: 20 -1; 30 -2
        """.trimIndent()
        val r = ApoConfig.parse(text)!!
        assertEquals(-6.4f, r.preamp!!, 1e-4f)
        assertEquals(4, r.filters.size)
        assertEquals(1, r.skipped)
        assertEquals(FilterType.LowShelf, r.filters[0].type)
        assertEquals(105f, r.filters[0].freq, 1e-4f)
        assertEquals(-3.1f, r.filters[1].gain, 1e-4f)
        assertEquals(false, r.filters[2].on)
        assertEquals(EqChannel.Right, r.filters[3].channel)
        assertEquals(1.414f, r.filters[3].q, 0.01f)
        assertEquals(null, ApoConfig.parse("hello"))
    }

    @Test fun apoExportRoundTrips() {
        val p = EqProfile("x", "Test", List(EQ_BANDS.size) { if (it == 2) 3f else 0f }, preamp = -2f, filters = listOf(
            EqFilter("a", FilterType.HighShelf, 8000f, -2.5f, 0.71f, channel = EqChannel.Left),
            EqFilter("b", FilterType.HighPass, 30f, slope = 24),
        ))
        val back = ApoConfig.parse(ApoConfig.format(p))!!
        assertEquals(-2f, back.preamp!!, 1e-4f)
        val freqs = EqMath.logFreqs(64)
        val want = EqMath.response(p.chain, freqs, true)
        val got = EqMath.response(back.filters, freqs, true)
        for (i in freqs.indices) assertEquals(want[i], got[i], 0.05)
    }

    /** eq.json written before parametric filters existed must still load, or profiles are lost. */
    @Test fun oldEqSettingsStillLoad() {
        val old = """{"enabled":true,"automatic":false,"manualId":"abc","profiles":[{"id":"abc","name":"XM5",
            "gains":[1,2,3,0,0,0,0,0,-1,-2],"devices":[{"type":8,"name":"WH-1000XM5"}]}]}"""
        val s = com.stemroller.mobile.core.AppJson.decodeFromString(com.stemroller.mobile.eq.EqState.serializer(), old)
        val p = s.profiles.single()
        assertEquals("XM5", p.name)
        assertEquals(0, p.filters.size)
        assertEquals(0f, p.preamp)
        assertEquals(5, p.chain.size)
    }

    @Test fun limiterHoldsTheCeiling() {
        val lim = Limiter().apply { setSampleRate(48000) }
        val n = 48000
        val loud = FloatArray(n * 2) { i -> (2.5 * sin(2 * PI * 220 * (i / 2) / 48000.0)).toFloat() }
        lim.process(loud, n)
        assertTrue(loud.maxOf { abs(it) } <= 0.9851f)
        val lim2 = Limiter().apply { setSampleRate(48000) }
        val quiet = FloatArray(n * 2) { i -> (0.5 * sin(2 * PI * 220 * (i / 2) / 48000.0)).toFloat() }
        val copy = quiet.copyOf()
        lim2.process(quiet, n)
        assertTrue(quiet.indices.all { abs(quiet[it] - copy[it]) < 1e-6f })
    }

    @Test fun stemTaperHasUnityAtTheDetent() {
        assertEquals(1f, stemTaper(0.8f), 1e-6f)
        assertEquals(0f, stemTaper(0f), 1e-6f)
        assertEquals(0.8f, stemUntaper(1f), 1e-6f)
    }

    @Test fun ringWrapsAround() {
        val ring = FloatRing(100)
        val src = FloatArray(140) { it.toFloat() }
        val dst = FloatArray(200)
        var next = 0f
        repeat(20) {
            assertTrue(ring.write(src, 0, 140) { false })
            val got = ring.read(dst, 70)
            assertEquals(70, got)
            for (i in 0 until got * 2) {
                assertEquals(next, dst[i])
                next = (next + 1) % 140
            }
        }
    }
}
