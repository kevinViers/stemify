package com.stemify.mobile.transfer

import android.os.Build
import com.stemify.mobile.cache.Downloads
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.eq.EqState
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.plex.Playlist
import com.stemify.mobile.plex.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A downloaded playlist in a backup: the playlist and its songs, in order. */
@Serializable
data class BackupPlaylist(val playlist: Playlist, val tracks: List<Track>)

/**
 * Everything a new phone needs to pick up where this one is: settings (servers and their
 * sign-ins, crossfade, stem quality, theme...), EQ profiles, the queue with its stem mix, and
 * what's downloaded. The music itself isn't in it: the new phone downloads the same songs
 * from the server. Plain JSON, so it travels any way a file does.
 */
@Serializable
data class Backup(
    /** Format version; a newer app reads older ones. */
    val stemifyBackup: Int = VERSION,
    val platform: String = "android",
    val device: String = "",
    val made: Long = 0,
    /** Setting name to {"t": s|b|i|l|f, "v": value}. */
    val settings: Map<String, JsonElement> = emptyMap(),
    val eq: EqState? = null,
    /** The queue file as the player saves it (songs, place, repeat, stem mix). */
    val queue: JsonElement? = null,
    /** Songs, albums and artists downloaded directly. */
    val songs: List<Track> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
) {
    val albums get() = songs.mapNotNull { it.albumKey ?: it.album.takeIf { a -> a.isNotEmpty() } }.distinct().size
    val queueLength get() = ((queue as? JsonObject)?.get("items") as? JsonArray)?.size ?: 0

    companion object {
        const val VERSION = 1
        const val MIME = "application/json"

        fun parse(text: String): Backup {
            val b = runCatching { AppJson.decodeFromString(serializer(), text) }
                .getOrElse { throw IllegalArgumentException("That isn't a Stemify backup.") }
            if (b.stemifyBackup < 1) throw IllegalArgumentException("That isn't a Stemify backup.")
            if (b.platform != "android") {
                val where = if (b.platform == "desktop") "a computer" else "another device"
                throw IllegalArgumentException("That backup is from Stemify on $where, not a phone.")
            }
            return b
        }

        fun make(settings: Settings, eq: EqController, playback: PlaybackManager, downloads: Downloads): Backup {
            val recs = downloads.records.value
            return Backup(
                device = Build.MODEL ?: "phone",
                made = System.currentTimeMillis(),
                settings = settings.export().mapValues { (_, v) -> typed(v) },
                eq = eq.localState,
                queue = playback.exportQueue()?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() },
                songs = recs.values.filter { it.direct }.sortedBy { it.added }.map { it.track },
                playlists = downloads.playlists.value.values.sortedBy { it.added }.map { p ->
                    BackupPlaylist(p.playlist, p.keys.mapNotNull { recs[it]?.track })
                },
            )
        }

        private fun typed(v: Any): JsonElement = buildJsonObject {
            when (v) {
                is String -> { put("t", "s"); put("v", v) }
                is Boolean -> { put("t", "b"); put("v", v) }
                is Int -> { put("t", "i"); put("v", v) }
                is Long -> { put("t", "l"); put("v", v) }
                is Float -> { put("t", "f"); put("v", v) }
                else -> { put("t", "s"); put("v", v.toString()) }
            }
        }

        /** A typed setting back to its value; null for anything malformed. */
        internal fun untyped(e: JsonElement): Any? = runCatching {
            val o = e.jsonObject
            val v = o["v"]!!.jsonPrimitive
            when (o["t"]!!.jsonPrimitive.content) {
                "s" -> v.contentOrNull
                "b" -> v.booleanOrNull
                "i" -> v.intOrNull
                "l" -> v.longOrNull
                "f" -> v.floatOrNull
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Applies a backup to this phone, live: settings (and everything watching them), the EQ, the
 * queue and mix (unless something's playing), and the downloads, which queue up as if picked
 * here and follow "Download on Wi-Fi Only". Songs already downloaded here are left alone.
 * Main thread.
 */
fun Backup.restore(settings: Settings, eq: EqController, playback: PlaybackManager, downloads: Downloads) {
    settings.import(this.settings.mapNotNull { (k, v) -> Backup.untyped(v)?.let { k to it } }.toMap())
    this.eq?.let { eq.replaceState(it) }
    queue?.let { playback.adoptQueue(it.toString()) }
    if (songs.isNotEmpty()) downloads.download(songs)
    for (p in playlists) if (p.tracks.isNotEmpty()) downloads.downloadPlaylist(p.playlist, p.tracks)
}
