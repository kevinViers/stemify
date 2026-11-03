package com.stemify.mobile.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Settings choice. Smart lengths cap the overlap; Fixed always overlaps by the chosen seconds. */
enum class CrossfadeMode(val label: String, val cap: Double) {
    Off("Off", 0.0),
    Short("Smart · Short", 4.0),
    Medium("Smart · Medium", 7.0),
    Long("Smart · Long", 12.0),
    Fixed("Fixed", 0.0),
}

/** Loudness readings, one per [Crossfade.HOP]: db[i] covers [startS + i·HOP, startS + (i+1)·HOP). */
class Envelope(val startS: Double, val db: FloatArray)

/**
 * A crossfade, in seconds. Outgoing times are into the song that's ending: the next song comes
 * in at [startS]; the ending one fades from [fadeFromS] to silence at [fadeToS] (equal: no fade,
 * it ends as written) and stops at [endS], its trailing silence cut. The next song starts
 * [inOffsetS] in, its leading silence skipped, and fades in over [inFadeS] (0: full level at once).
 */
data class CrossfadePlan(
    val startS: Double,
    val fadeFromS: Double,
    val fadeToS: Double,
    val endS: Double,
    val inOffsetS: Double,
    val inFadeS: Double,
)

/**
 * When the next song comes in, and how the two fade. Smart crossfades listen to both songs
 * first: the end of the one that's ending and the start of the next are decoded and their
 * loudness read every [HOP] seconds. Then:
 *
 * - silence after the last note and before the first is skipped, so nothing fades over nothing;
 * - a song that fades or dies away is crossed over its fade, up to the chosen length;
 * - a cold ending into a quiet intro lets the intro start under the last moment, unfaded;
 * - a cold ending into a loud start just butts the two together, silences trimmed.
 *
 * The same rules as the desktop's stemify/player/crossfade.py. Keep the two in step.
 */
object Crossfade {
    const val HOP = 0.05
    const val TAIL_S = 25.0
    const val HEAD_S = 20.0
    private const val FLOOR_DB = -60f
    private const val SILENCE_BELOW = 40f
    private const val BODY_BELOW = 9f
    private const val SMOOTH = 8
    private const val FADE_MIN = 1.5
    private const val MAX_SHARE = 0.4
    val FIXED_RANGE = 1..12

    /** The crossfade from a song of [outDurS] into one of [inDurS], or null for none. */
    fun plan(mode: CrossfadeMode, fixedS: Int, tail: Envelope?, outDurS: Double, head: Envelope?, inDurS: Double): CrossfadePlan? {
        if (mode == CrossfadeMode.Off) return null
        val (outEnd, bodyEnd) = outgoing(tail, outDurS)
        var (inStart, inBody) = incoming(head)
        // A song that's silent as far as we looked: play it from the top.
        if (inStart >= inDurS - 1.0) { inStart = 0.0; inBody = 0.0 }
        val limit = max(0.0, MAX_SHARE * min(outDurS, inDurS - inStart))
        if (mode == CrossfadeMode.Fixed) return even(minOf(fixedS.toDouble(), limit, outEnd), outEnd, inStart)
        val cap = min(mode.cap, limit)
        if (tail == null || head == null) return even(cap / 2, outEnd, inStart)
        val fade = outEnd - bodyEnd
        val intro = inBody - inStart
        if (fade >= FADE_MIN) {
            val ov = min(cap, fade)
            // An intro that's already quiet doesn't need a long fade in to sit under the old song.
            return CrossfadePlan(outEnd - ov, outEnd - ov, outEnd, outEnd, inStart, if (intro < 1.0) ov else min(ov, 1.0))
        }
        if (intro >= FADE_MIN) {
            val ov = min(cap * 0.5, intro * 0.5)
            return CrossfadePlan(outEnd - ov, outEnd, outEnd, outEnd, inStart, 0.0)
        }
        return CrossfadePlan(outEnd, outEnd, outEnd, outEnd, inStart, 0.0)
    }

    private fun even(ov: Double, outEnd: Double, inStart: Double): CrossfadePlan {
        val o = max(0.0, ov)
        return CrossfadePlan(outEnd - o, outEnd - o, outEnd, outEnd, inStart, o)
    }

    /** (where the last sound ends, where the song's body ends) in the outgoing song. */
    private fun outgoing(tail: Envelope?, outDurS: Double): Pair<Double, Double> {
        if (tail == null || tail.db.isEmpty()) return outDurS to outDurS
        val db = tail.db
        val ref = pct(db, 0.95)
        val silent = max(FLOOR_DB, ref - SILENCE_BELOW)
        val last = (db.indices.reversed()).firstOrNull { db[it] > silent }
            ?: return min(outDurS, tail.startS).let { it to it }
        val end = min(outDurS, tail.startS + (last + 1) * HOP)
        val sm = smooth(db)
        val body = (last downTo 0).firstOrNull { sm[it] >= ref - BODY_BELOW }
        val bodyEnd = if (body != null) tail.startS + (body + 1) * HOP else tail.startS
        return end to min(end, bodyEnd)
    }

    /** (where the first sound starts, where the song's body starts) in the incoming song. */
    private fun incoming(head: Envelope?): Pair<Double, Double> {
        if (head == null || head.db.isEmpty()) return 0.0 to 0.0
        val db = head.db
        val ref = pct(db, 0.95)
        val silent = max(FLOOR_DB, ref - SILENCE_BELOW)
        val first = db.indices.firstOrNull { db[it] > silent } ?: return 0.0 to 0.0
        val sm = smooth(db)
        val body = (first until db.size).firstOrNull { sm[it] >= ref - BODY_BELOW } ?: first
        return head.startS + first * HOP to head.startS + body * HOP
    }

    private fun pct(xs: FloatArray, q: Double): Float {
        val s = xs.sortedArray()
        return s[min(s.size - 1, (q * s.size).toInt())]
    }

    /** Power average over [SMOOTH] readings, centred. */
    private fun smooth(db: FloatArray): FloatArray {
        val p = DoubleArray(db.size) { 10.0.pow(db[it] / 10.0) }
        val h = SMOOTH / 2
        return FloatArray(db.size) { i ->
            val a = max(0, i - h)
            val b = min(db.size, i + h)
            var sum = 0.0
            for (k in a until b) sum += p[k]
            (10 * log10(sum / (b - a) + 1e-12)).toFloat()
        }
    }

    /** True when [b] follows [a] on the same album (track after track), which stays gapless. */
    fun consecutive(albumA: String?, discA: Int?, indexA: Int?, albumB: String?, discB: Int?, indexB: Int?): Boolean {
        if (albumA.isNullOrEmpty() || albumA != albumB) return false
        val da = discA ?: 1
        val db = discB ?: 1
        val ia = indexA ?: 0
        val ib = indexB ?: 0
        return (da == db && ib == ia + 1) || (db == da + 1 && ib == 1)
    }

    /** Equal-power gains for a fade [x] of the way through (0..1). */
    fun fadeOutGain(x: Double): Float = cos(x.coerceIn(0.0, 1.0) * PI / 2).toFloat()
    fun fadeInGain(x: Double): Float = sin(x.coerceIn(0.0, 1.0) * PI / 2).toFloat()
}

/** Loudness per [Crossfade.HOP] of interleaved stereo, fed in pieces. */
class LoudnessMeter(sampleRate: Int) {
    private val hop = max(1, (Crossfade.HOP * sampleRate).toInt())
    private var sum = 0.0
    private var n = 0
    private val out = ArrayList<Float>()

    fun add(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            sum += (l * l + r * r) * 0.5
            if (++n == hop) {
                out += (10 * log10(sum / hop + 1e-12)).toFloat()
                sum = 0.0
                n = 0
            }
        }
    }

    fun result(startS: Double) = Envelope(startS, out.toFloatArray())
}
