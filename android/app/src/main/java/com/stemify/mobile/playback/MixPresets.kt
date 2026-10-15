package com.stemify.mobile.playback

/** A one-tap stem mix. [build] returns null when the track lacks the stems it needs. */
class MixPreset(val label: String, val build: (List<String>) -> Map<String, StemSetting>?)

object MixPresets {
    val FULL = MixPreset("Full Mix") { emptyMap() }
    val KARAOKE = MixPreset("Karaoke") { n -> if ("vocals" in n) mapOf("vocals" to StemSetting(muted = true)) else null }

    val ALL = listOf(
        FULL,
        KARAOKE,
        MixPreset("A Cappella") { n -> if ("vocals" in n) mapOf("vocals" to StemSetting(solo = true)) else null },
        MixPreset("No Drums") { n -> if ("drums" in n) mapOf("drums" to StemSetting(muted = true)) else null },
        MixPreset("Rhythm") { n -> if ("drums" in n && "bass" in n) mapOf("drums" to StemSetting(solo = true), "bass" to StemSetting(solo = true)) else null },
        MixPreset("No Guitar") { n -> if ("guitar" in n) mapOf("guitar" to StemSetting(muted = true)) else null },
        MixPreset("No Bass") { n -> if ("bass" in n) mapOf("bass" to StemSetting(muted = true)) else null },
    )

    /** Drops stems left at their defaults, so mixes compare by what they actually change. */
    fun normalized(m: Map<String, StemSetting>) = m.filterValues { it != StemSetting() }

    /** The preset [mix] matches for these stems, or null for a hand-made mix. */
    fun current(names: List<String>, mix: Map<String, StemSetting>): MixPreset? {
        val m = normalized(mix)
        return ALL.firstOrNull { p -> p.build(names)?.let { normalized(it) == m } == true }
    }

    /** The preset after the current one (a custom mix goes back to Full), skipping ones these stems can't do. */
    fun next(names: List<String>, mix: Map<String, StemSetting>): MixPreset {
        val usable = ALL.filter { it.build(names) != null }
        val i = usable.indexOf(current(names, mix))
        return if (i < 0) FULL else usable[(i + 1) % usable.size]
    }

    /** True when the vocals stem can't be heard (muted, or another stem soloed). */
    fun vocalsSilent(names: List<String>, mix: Map<String, StemSetting>): Boolean {
        if ("vocals" !in names) return false
        val v = mix["vocals"] ?: StemSetting()
        val anySolo = names.any { mix[it]?.solo == true }
        return v.muted || anySolo && !v.solo || v.level <= 0f
    }
}
