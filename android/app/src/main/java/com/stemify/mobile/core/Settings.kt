package com.stemify.mobile.core

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
    val key: String,
    private val initial: T,
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

    /** Picks up a value written straight to the preferences (a restored backup). */
    internal fun reload() {
        state.value = prefs.read(key, initial)
    }
}

class Settings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** Every setting, for a backup to carry to another phone. */
    private val all = LinkedHashMap<String, Pref<*>>()

    private fun <T> reg(p: Pref<T>) = p.also { all[it.key] = it }

    private fun string(key: String, def: String) =
        reg(Pref(prefs, key, def, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) }))

    private fun bool(key: String, def: Boolean) =
        reg(Pref(prefs, key, def, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) }))

    private fun int(key: String, def: Int) =
        reg(Pref(prefs, key, def, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) }))

    private fun long(key: String, def: Long) =
        reg(Pref(prefs, key, def, { k, d -> getLong(k, d) }, { k, v -> putLong(k, v) }))

    private fun float(key: String, def: Float) =
        reg(Pref(prefs, key, def, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) }))

    private inline fun <reified E : Enum<E>> enum(key: String, def: E) = reg(Pref(
        prefs, key, def,
        { k, d -> getString(k, null)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: d },
        { k, v -> putString(k, v.name) },
    ))

    /**
     * The settings that were changed from their defaults, typed, for a backup. Leaves out what
     * belongs to this phone alone: the devices it trusts for Stemify Connect and the keys
     * they gave it (another phone must be approved on its own).
     */
    fun export(): Map<String, Any> = prefs.all
        .filterKeys { it in all && it !in DEVICE_ONLY }
        .mapNotNull { (k, v) -> v?.let { k to it } }
        .toMap()

    /** Writes a backup's settings over these and updates everything watching them. Unknown keys are ignored. */
    fun import(values: Map<String, Any>) {
        val e = prefs.edit()
        for ((k, v) in values) {
            if (k !in all || k in DEVICE_ONLY) continue
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
            }
        }
        e.commit()
        all.values.forEach { it.reload() }
    }

    val plexUrl = string("plexUrl", "http://192.168.86.2:32400")
    val plexToken = string("plexToken", "")
    val plexUser = string("plexUser", "")
    val plexMachineId = string("plexMachineId", "")
    /** The server's connections from plex.tv, one "Local|Remote|Relay <uri>" per line, best first. */
    val plexConnections = string("plexConnections", "")
    /** When [plexConnections] was last fetched (epoch ms). */
    val plexConnectionsAt = long("plexConnectionsAt", 0L)
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
    /** Unfolded: Now Playing on the left and the library or stems on the right. */
    val wideSwapped = bool("wideSwapped", false)

    val stemQuality = enum("stemQuality", StemQuality.High)
    val cacheLimitGb = int("cacheLimitGb", 10)
    val downloadsWifiOnly = bool("downloadsWifiOnly", true)
    /** Downloads page order: 0 download order, 1 artist, 2 release date. */
    val downloadSort = int("downloadSort", 0)
    /** Flips the sort's dates: oldest first for download order and release date, newest first within an artist. */
    val downloadReversed = bool("downloadReversed", false)
    val keepStemMix = bool("keepStemMix", true)
    /** Between songs: off (gapless), smart (Short, Medium, Long caps), or fixed [crossfadeSeconds]. */
    val crossfade = enum("crossfade", com.stemify.mobile.engine.CrossfadeMode.Off)
    val crossfadeSeconds = int("crossfadeSeconds", 6)
    /** Crossfade between songs that follow each other on an album too (off: albums stay gapless). */
    val crossfadeAlbums = bool("crossfadeAlbums", false)
    /** Look up lyrics on LRCLIB (lrclib.net) when Plex has none, sending it the song's title and artist. */
    val onlineLyrics = bool("onlineLyrics", true)
    val masterVolume = float("masterVolume", 0.85f)

    /** Stemify Connect: nearby devices may control this phone (Wi-Fi ones once approved here). */
    val connectEnabled = bool("connectEnabled", true)
    val connectBluetooth = bool("connectBluetooth", true)
    /** Wi-Fi controllers approved here, as a JSON object of id to {name, key} (just a name, before pairing keys). */
    val connectAllowed = string("connectAllowed", "{}")
    /** The keys receivers gave this phone when they approved it, as a JSON object of receiver id to key. */
    val connectKeys = string("connectKeys", "{}")

    val clientId: String = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientId", it).apply()
    }

    private companion object {
        val DEVICE_ONLY = setOf("connectAllowed", "connectKeys")
    }
}
