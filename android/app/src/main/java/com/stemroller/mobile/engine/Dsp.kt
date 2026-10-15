package com.stemroller.mobile.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.serialization.Serializable

/** ISO octave centres for the 10-band graphic EQ. */
val EQ_BANDS = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
const val EQ_MAX_DB = 12f
const val EQ_Q = 1.41f // one octave

/** Equalizer APO's filter types; [apo] is the name its config files use. */
@Serializable
enum class FilterType(val apo: String, val label: String, val hasGain: Boolean, val hasSlope: Boolean = false) {
    Peak("PK", "Peak", true),
    LowShelf("LSC", "Low Shelf", true),
    HighShelf("HSC", "High Shelf", true),
    LowPass("LPQ", "Low Pass", false, hasSlope = true),
    HighPass("HPQ", "High Pass", false, hasSlope = true),
    BandPass("BP", "Band Pass", false),
    Notch("NO", "Notch", false),
    AllPass("AP", "All Pass", false),
}

@Serializable
enum class EqChannel(val label: String) { Both("Both"), Left("Left"), Right("Right") }

/** Slopes offered for low and high pass, in dB per octave. 12 uses the filter's Q; steeper ones are Butterworth. */
val EQ_SLOPES = listOf(6, 12, 24, 36, 48)

/** One parametric filter, as in Equalizer APO. Gain only matters for peaks and shelves. */
@Serializable
data class EqFilter(
    val id: String,
    val type: FilterType = FilterType.Peak,
    val freq: Float = 1000f,
    val gain: Float = 0f,
    val q: Float = 0.707f,
    val slope: Int = 12,
    val channel: EqChannel = EqChannel.Both,
    val on: Boolean = true,
) {
    /** Whether it changes the sound at all (a 0 dB peak or shelf doesn't). */
    val active get() = on && (!type.hasGain || abs(gain) >= 0.01f)

    /** Its biquad sections at [fs]: [b0, b1, b2, a1, a2] each, normalised by a0. */
    fun sections(fs: Double): List<DoubleArray> {
        if (!active) return emptyList()
        val f0 = freq.toDouble().coerceIn(1.0, fs * 0.49)
        val q = q.toDouble().coerceIn(0.025, 100.0)
        val g = gain.toDouble()
        return when (type) {
            FilterType.LowPass, FilterType.HighPass -> {
                val lp = type == FilterType.LowPass
                when {
                    slope <= 6 -> listOf(firstOrder(fs, f0, lp))
                    slope <= 12 -> listOf(Biquad.design(type, fs, f0, 0.0, q))
                    else -> {
                        // Butterworth of order slope/6, as cascaded sections (plus a first-order one when odd).
                        val n = slope / 6
                        (1..n / 2).map { k -> Biquad.design(type, fs, f0, 0.0, 1 / (2 * cos((2 * k - 1) * PI / (2 * n)))) } +
                            (if (n % 2 == 1) listOf(firstOrder(fs, f0, lp)) else emptyList())
                    }
                }
            }
            else -> listOf(Biquad.design(type, fs, f0, g, q))
        }
    }

    private fun firstOrder(fs: Double, f0: Double, lowPass: Boolean): DoubleArray {
        val k = tan(PI * f0 / fs)
        val a1 = (k - 1) / (k + 1)
        return if (lowPass) doubleArrayOf(k / (k + 1), k / (k + 1), 0.0, a1, 0.0)
        else doubleArrayOf(1 / (k + 1), -1 / (k + 1), 0.0, a1, 0.0)
    }

    fun applies(left: Boolean) = channel == EqChannel.Both || (channel == EqChannel.Left) == left

    companion object {
        /** The 10-band graphic EQ as peaking filters, ahead of the parametric ones. */
        fun graphic(gains: List<Float>) = gains.mapIndexedNotNull { i, g ->
            if (abs(g) < 0.01f || i >= EQ_BANDS.size) null else EqFilter("band$i", FilterType.Peak, EQ_BANDS[i], g, EQ_Q)
        }
    }
}

/** What the engine needs from an EQ profile; null means bypass. */
data class EqParams(val filters: List<EqFilter>, val preampDb: Float)

/** A biquad section, transposed direct form II, double state per channel. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var zl1 = 0.0; private var zl2 = 0.0; private var zr1 = 0.0; private var zr2 = 0.0

    fun set(c: DoubleArray) {
        b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]
    }

    fun reset() {
        zl1 = 0.0; zl2 = 0.0; zr1 = 0.0; zr2 = 0.0
    }

    fun process(buf: FloatArray, frames: Int, left: Boolean = true, right: Boolean = true) {
        var l1 = zl1; var l2 = zl2; var r1 = zr1; var r2 = zr2
        for (i in 0 until frames) {
            if (left) {
                val xl = buf[i * 2].toDouble()
                val yl = b0 * xl + l1
                l1 = b1 * xl - a1 * yl + l2
                l2 = b2 * xl - a2 * yl
                buf[i * 2] = yl.toFloat()
            }
            if (right) {
                val xr = buf[i * 2 + 1].toDouble()
                val yr = b0 * xr + r1
                r1 = b1 * xr - a1 * yr + r2
                r2 = b2 * xr - a2 * yr
                buf[i * 2 + 1] = yr.toFloat()
            }
        }
        // Flush denormals so a silent tail doesn't cost CPU.
        zl1 = if (abs(l1) < 1e-25) 0.0 else l1; zl2 = if (abs(l2) < 1e-25) 0.0 else l2
        zr1 = if (abs(r1) < 1e-25) 0.0 else r1; zr2 = if (abs(r2) < 1e-25) 0.0 else r2
    }

    companion object {
        /** RBJ Audio EQ Cookbook coefficients, [b0, b1, b2, a1, a2] normalised by a0. */
        fun design(type: FilterType, fs: Double, f0: Double, gainDb: Double, q: Double): DoubleArray {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * f0.coerceAtMost(fs * 0.49) / fs
            val alpha = sin(w0) / (2 * q)
            val cw = cos(w0)
            val sa = 2 * sqrt(a) * alpha
            val c = when (type) {
                FilterType.Peak -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                FilterType.LowShelf -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                    (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
                )
                FilterType.HighShelf -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                    (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
                )
                FilterType.LowPass -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                FilterType.HighPass -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                FilterType.BandPass -> doubleArrayOf(alpha, 0.0, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
                FilterType.Notch -> doubleArrayOf(1.0, -2 * cw, 1.0, 1 + alpha, -2 * cw, 1 - alpha)
                FilterType.AllPass -> doubleArrayOf(1 - alpha, -2 * cw, 1 + alpha, 1 + alpha, -2 * cw, 1 - alpha)
            }
            val a0 = c[3]
            return doubleArrayOf(c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0)
        }

        /** |H| in dB at frequency f. */
        fun magnitudeDb(c: DoubleArray, fs: Double, f: Double): Double {
            val w = 2 * PI * f / fs
            val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = c[0] + c[1] * cw + c[2] * c2
            val ni = -(c[1] * sw + c[2] * s2)
            val dr = 1 + c[3] * cw + c[4] * c2
            val di = -(c[3] * sw + c[4] * s2)
            return 10 * log10(max(1e-30, nr * nr + ni * ni) / max(1e-30, dr * dr + di * di))
        }
    }
}

object EqMath {
    /** Combined response of [filters] on one channel, in dB at each of [freqs]. */
    fun response(filters: List<EqFilter>, freqs: DoubleArray, left: Boolean = true, fs: Double = 48000.0): DoubleArray {
        val coeffs = filters.filter { it.applies(left) }.flatMap { it.sections(fs) }
        return DoubleArray(freqs.size) { i -> coeffs.sumOf { Biquad.magnitudeDb(it, fs, freqs[i]) } }
    }

    fun logFreqs(n: Int, lo: Double = 20.0, hi: Double = 20000.0) =
        DoubleArray(n) { lo * (hi / lo).pow(it / (n - 1.0)) }

    /**
     * Anti-clipping preamp: the negative of the curve's highest point (manual preamp included,
     * louder channel), so a full-scale signal at the loudest frequency still peaks at 0 dBFS.
     */
    fun autoPreampDb(filters: List<EqFilter>, manualDb: Float = 0f): Float {
        val f = logFreqs(512)
        val peak = maxOf(response(filters, f, true).maxOrNull() ?: 0.0, response(filters, f, false).maxOrNull() ?: 0.0)
        return -max(0.0, peak + manualDb).toFloat()
    }
}

class Equalizer {
    private class Stage(val filter: Biquad, val left: Boolean, val right: Boolean)

    private var stages = emptyList<Stage>()
    private var shape = emptyList<Any>()
    private var fs = 0
    private var params: EqParams? = null
    private var preamp = 1f
    var enabled = false; private set

    fun configure(p: EqParams?, sampleRate: Int) {
        enabled = p != null
        if (p == null || (p == params && sampleRate == fs)) return
        val rateChanged = sampleRate != fs
        fs = sampleRate
        params = p
        preamp = dbToGain(p.preampDb)
        val designed = p.filters.flatMap { f -> f.sections(fs.toDouble()).map { it to f } }
        // Coefficient changes (a slider drag) keep the filters' state; a different layout starts fresh.
        val newShape = designed.map { (_, f) -> Triple(f.id, f.channel, f.type) } + designed.size
        if (rateChanged || newShape != shape) {
            stages = designed.map { (_, f) -> Stage(Biquad(), f.channel != EqChannel.Right, f.channel != EqChannel.Left) }
            shape = newShape
        }
        designed.forEachIndexed { i, (c, _) -> stages[i].filter.set(c) }
    }

    fun process(buf: FloatArray, frames: Int) {
        if (!enabled) return
        if (preamp != 1f) for (i in 0 until frames * 2) buf[i] *= preamp
        for (s in stages) s.filter.process(buf, frames, s.left, s.right)
    }
}

/**
 * Peak limiter on the master bus: stems pushed past unity or an EQ boost never clip.
 * Instant attack, ~120 ms release, ceiling just under full scale.
 */
class Limiter(private val ceiling: Float = 0.985f) {
    private var gain = 1f
    private var release = 0f

    fun setSampleRate(fs: Int) {
        release = 1f - exp(-1f / (0.12f * fs))
    }

    fun process(buf: FloatArray, frames: Int) {
        var g = gain
        for (i in 0 until frames) {
            val l = buf[i * 2]; val r = buf[i * 2 + 1]
            val peak = max(abs(l), abs(r))
            val need = if (peak * g > ceiling) ceiling / peak else 1f
            g = if (need < g) need else g + (1f - g) * release
            if (peak * g > ceiling) g = ceiling / peak
            buf[i * 2] = l * g
            buf[i * 2 + 1] = r * g
        }
        gain = g
    }
}

fun dbToGain(db: Float) = 10f.pow(db / 20f)
fun gainToDb(g: Float) = if (g <= 1e-6f) -120f else 20f * log10(g)

/** Master slider taper (same cubic curve as the desktop app). */
fun masterTaper(v: Float) = v.coerceIn(0f, 1f).let { it * it * it }

/** Stem slider: 0..1 with unity at 0.8 and +3.9 dB at the top. */
fun stemTaper(v: Float) = (v.coerceIn(0f, 1f) / 0.8f).let { it * it }
fun stemUntaper(g: Float) = (sqrt(g.coerceAtLeast(0f)) * 0.8f).coerceIn(0f, 1f)
