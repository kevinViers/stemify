package com.stemroller.mobile.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ThemeMode(val label: String) { Black("Black"), Dark("Dark"), White("White") }

/** iOS system tints, light then dark variant. */
enum class Accent(val label: String, val light: Long, val dark: Long) {
    Music("Music", 0xFFFA2D48, 0xFFFC3C44),
    Pink("Pink", 0xFFFF2D55, 0xFFFF375F),
    Orange("Orange", 0xFFFF9500, 0xFFFF9F0A),
    Yellow("Yellow", 0xFFFFCC00, 0xFFFFD60A),
    Green("Green", 0xFF34C759, 0xFF30D158),
    Teal("Teal", 0xFF30B0C7, 0xFF40C8E0),
    Blue("Blue", 0xFF007AFF, 0xFF0A84FF),
    Indigo("Indigo", 0xFF5856D6, 0xFF5E5CE6),
    Purple("Purple", 0xFFAF52DE, 0xFFBF5AF2),
}

/** How stems travel from stemserve; Opus is transcoded (and cached) server-side. */
enum class StemQuality(val label: String, val detail: String, val fmt: String, val kbps: Int) {
    Lossless("Lossless", "Original 24-bit FLAC, ~30 MB per stem-minute", "flac", 0),
    High("High", "Opus 256 kbps per stem", "opus", 256),
    Standard("Standard", "Opus 160 kbps per stem", "opus", 160),
    Saver("Data Saver", "Opus 96 kbps per stem", "opus", 96),
}

class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    initial: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(prefs.read(key, initial))
    val flow: StateFlow<T> = state.asStateFlow()
    var value: T
        get() = state.value
        set(v) {
            if (v == state.value) return
            state.value = v
            prefs.edit().apply { write(key, v) }.apply()
        }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun string(key: String, def: String) =
        Pref(prefs, key, def, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    private fun bool(key: String, def: Boolean) =
        Pref(prefs, key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private fun int(key: String, def: Int) =
        Pref(prefs, key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun float(key: String, def: Float) =
        Pref(prefs, key, def, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })

    private inline fun <reified E : Enum<E>> enum(key: String, def: E) = Pref(
        prefs, key, def,
        { k, d -> getString(k, null)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: d },
        { k, v -> putString(k, v.name) },
    )

    val plexUrl = string("plexUrl", "http://192.168.86.2:32400")
    val plexToken = string("plexToken", "")
    val plexUser = string("plexUser", "")
    val plexMachineId = string("plexMachineId", "")
    val musicSection = string("musicSection", "")
    val musicSectionTitle = string("musicSectionTitle", "")
    val stemUrl = string("stemUrl", "http://192.168.86.2:8765")
    val stemToken = string("stemToken", "")
    /** Plex path prefixes that matched the stems index, newline-separated. */
    val stemRoots = string("stemRoots", "")

    val theme = enum("theme", ThemeMode.Black)
    val accent = enum("accent", Accent.Music)
    val albumColorNowPlaying = bool("albumColorNowPlaying", false)
    val highRefreshRate = bool("highRefreshRate", true)

    val stemQuality = enum("stemQuality", StemQuality.High)
    val cacheLimitGb = int("cacheLimitGb", 10)
    val downloadsWifiOnly = bool("downloadsWifiOnly", true)
    /** Downloads page order: 0 download order, 1 artist, 2 release date. */
    val downloadSort = int("downloadSort", 0)
    val keepStemMix = bool("keepStemMix", true)
    val masterVolume = float("masterVolume", 0.85f)

    val clientId: String = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientId", it).apply()
    }
}
