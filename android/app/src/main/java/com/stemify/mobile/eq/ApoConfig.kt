package com.stemify.mobile.eq

import com.stemify.mobile.engine.EQ_Q
import com.stemify.mobile.engine.EqChannel
import com.stemify.mobile.engine.EqFilter
import com.stemify.mobile.engine.FilterType
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Equalizer APO's config text, the format AutoEQ publishes headphone corrections in
 * (ParametricEQ.txt):
 *
 *   Preamp: -6.2 dB
 *   Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
 *   Filter 2: ON PK Fc 2300 Hz Gain -3.1 dB Q 1.41
 *
 * Also understood: "Channel: L" / "R" / "all" lines, BW Oct instead of Q, and the older
 * type names (LS, HS, LP, HP, PEQ ...). Other commands (GraphicEQ, Delay, Copy) are skipped.
 */
object ApoConfig {
    class Result(val filters: List<EqFilter>, val preamp: Float?, val skipped: Int)

    private val TYPES = mapOf(
        "PK" to FilterType.Peak, "PEQ" to FilterType.Peak, "MODAL" to FilterType.Peak,
        "LS" to FilterType.LowShelf, "LSC" to FilterType.LowShelf, "LSQ" to FilterType.LowShelf,
        "HS" to FilterType.HighShelf, "HSC" to FilterType.HighShelf, "HSQ" to FilterType.HighShelf,
        "LP" to FilterType.LowPass, "LPQ" to FilterType.LowPass,
        "HP" to FilterType.HighPass, "HPQ" to FilterType.HighPass,
        "BP" to FilterType.BandPass, "NO" to FilterType.Notch, "AP" to FilterType.AllPass,
    )

    /** Null when the text has no preamp or filter lines at all. */
    fun parse(text: String): Result? {
        val filters = ArrayList<EqFilter>()
        var preamp: Float? = null
        var channel = EqChannel.Both
        var skipped = 0
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val cmd = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val args = line.substring(colon + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            when {
                cmd == "preamp" -> preamp = (preamp ?: 0f) + (args.firstOrNull()?.toFloatOrNull() ?: continue)
                cmd == "channel" -> channel = when (args.map { it.uppercase(Locale.ROOT) }.toSet()) {
                    setOf("L") -> EqChannel.Left
                    setOf("R") -> EqChannel.Right
                    else -> EqChannel.Both
                }
                cmd.startsWith("filter") -> {
                    val f = parseFilter(args, channel)
                    if (f != null) filters += f else skipped++
                }
                else -> skipped++
            }
        }
        if (filters.isEmpty() && preamp == null) return null
        return Result(filters, preamp, skipped)
    }

    private fun parseFilter(args: List<String>, channel: EqChannel): EqFilter? {
        var i = 0
        var on = true
        when (args.getOrNull(0)?.uppercase(Locale.ROOT)) {
            "ON" -> i++
            "OFF" -> { on = false; i++ }
        }
        val type = TYPES[args.getOrNull(i)?.uppercase(Locale.ROOT)] ?: return null
        i++
        var freq: Float? = null
        var gain = 0f
        var q: Float? = null
        var slope = 12
        while (i < args.size) {
            val key = args[i].lowercase(Locale.ROOT)
            val num = args.getOrNull(i + 1)?.toFloatOrNull()
            when {
                key == "fc" && num != null -> { freq = num; i += 2 }
                key == "gain" && num != null -> { gain = num; i += 2 }
                key == "q" && num != null -> { q = num; i += 2 }
                key == "bw" && args.getOrNull(i + 1)?.lowercase(Locale.ROOT) == "oct" -> {
                    args.getOrNull(i + 2)?.toFloatOrNull()?.let { bw -> q = (sqrt(2.0.pow(bw.toDouble())) / (2.0.pow(bw.toDouble()) - 1)).toFloat() }
                    i += 3
                }
                // "LSC 12dB Fc ...": a shelf or pass slope in dB per octave.
                key.endsWith("db") && key.removeSuffix("db").toFloatOrNull() != null -> {
                    slope = key.removeSuffix("db").toFloat().toInt(); i++
                }
                else -> i++
            }
        }
        val fc = freq ?: return null
        val defaultQ = when (type) {
            FilterType.Peak -> EQ_Q
            else -> 0.707f
        }
        return EqFilter(
            EqController.newFilterId(), type, fc, if (type.hasGain) gain else 0f, q ?: defaultQ,
            slope = if (type.hasSlope) slope.coerceIn(6, 48) else 12, channel = channel, on = on,
        )
    }

    /** The profile as an Equalizer APO config: preamp, graphic bands as peaks, then the filters. */
    fun format(p: EqProfile): String = buildString {
        fun n(v: Float) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
        appendLine("# ${p.name}, from Stemify")
        appendLine("Preamp: ${n(p.preamp)} dB")
        var channel = EqChannel.Both
        var k = 1
        for (f in EqFilter.graphic(p.gains) + p.filters) {
            if (f.channel != channel) {
                channel = f.channel
                appendLine("Channel: " + when (channel) { EqChannel.Left -> "L"; EqChannel.Right -> "R"; EqChannel.Both -> "all" })
            }
            val head = "${if (f.on) "ON" else "OFF"} ${f.type.apo} Fc ${n(f.freq)} Hz"
            when {
                f.type.hasGain -> appendLine("Filter ${k++}: $head Gain ${n(f.gain)} dB Q ${n(f.q)}")
                !f.type.hasSlope || f.slope == 12 -> appendLine("Filter ${k++}: $head Q ${n(f.q)}")
                f.slope < 12 -> {
                    appendLine("# 6 dB/oct ${f.type.label.lowercase(Locale.ROOT)}: APO has no first-order filter, so this is a gentle 12 dB/oct one")
                    appendLine("Filter ${k++}: $head Q 0.5")
                }
                else -> {
                    // Steeper slopes are Butterworth: one APO filter per second-order section.
                    val order = f.slope / 6
                    for (j in 1..order / 2) {
                        val q = (1 / (2 * kotlin.math.cos((2 * j - 1) * Math.PI / (2 * order)))).toFloat()
                        appendLine("Filter ${k++}: $head Q ${n(q)}")
                    }
                }
            }
        }
    }
}
