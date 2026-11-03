package com.stemify.mobile.plex

import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.HttpException
import com.stemify.mobile.core.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * When albums first came out, from MusicBrainz, for "On This Day".
 *
 * Plex's own album dates aren't safe for this. Its metadata agent sometimes has the wrong date
 * (Madvillainy as 2002-11-01, not 2004), and when it only knows a year or month it stores the 1st
 * (1978 becomes 1978-01-01), which turns into a birthday that never happened.
 *
 * Plex does keep the MusicBrainz release each album's files were tagged with. That release may be
 * a reissue, so we ask for its release group's first release date: the earliest any edition of
 * the album came out. MusicBrainz takes about one request a second, so up to 40 releases share
 * one search, and answers are saved on the phone so each album is looked up once.
 * The desktop app does the same in stemify/mobile/releases.py.
 */
class ReleaseDates(private val http: OkHttpClient, filesDir: File) {
    /** "2004-03-19", "1982-06", "1971", or "" when MusicBrainz has no date. */
    @Serializable
    private data class Entry(val date: String, val at: Long)

    private val file = File(filesDir, "release-dates.json")
    private val lock = Mutex()
    private var known: MutableMap<String, Entry>? = null
    private var last = 0L

    /**
     * Release id -> first release date, for the ones MusicBrainz has answered for. Looks up what
     * isn't saved; ids it couldn't reach (offline) are left out. Never throws for network trouble.
     */
    suspend fun resolve(mbids: Collection<String>): Map<String, String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val k = known ?: load().also { known = it }
            val ids = mbids.filter { it.isNotBlank() }.distinct()
            val now = System.currentTimeMillis()
            val due = ids.filter { stale(k[it], now) }
            if (due.isNotEmpty()) {
                try {
                    lookup(due, k)
                } catch (e: CancellationException) {
                    save(k)
                    throw e
                } catch (e: Exception) {
                    // Offline or MusicBrainz is down: use what's saved, try again next time.
                }
                save(k)
            }
            ids.mapNotNull { id -> k[id]?.let { id to it.date } }.toMap()
        }
    }

    private fun stale(e: Entry?, now: Long) = e == null || now - e.at > (if (e.date.isNotEmpty()) FRESH_MS else RETRY_MISSING_MS)

    private suspend fun lookup(ids: List<String>, k: MutableMap<String, Entry>) {
        val missed = ArrayList<String>()
        for (chunk in ids.chunked(BATCH)) {
            val want = chunk.toSet()
            val body = get("/release-group", "query" to chunk.joinToString(" OR ") { "reid:$it" }, "limit" to "100")
                ?: throw IOException("MusicBrainz search failed")
            val found = HashMap<String, String>()
            for (group in body["release-groups"]?.jsonArray.orEmpty()) {
                val g = group.jsonObject
                val date = g["first-release-date"]?.jsonPrimitive?.content.orEmpty()
                for (r in g["releases"]?.jsonArray.orEmpty()) {
                    val id = r.jsonObject["id"]?.jsonPrimitive?.content ?: continue
                    if (id in want) found[id] = date
                }
            }
            val at = System.currentTimeMillis()
            for ((id, d) in found) k[id] = Entry(d, at)
            missed += want - found.keys
        }
        // Search lists a big release group's releases only in part; ask for those one by one.
        for (id in missed) {
            val body = get("/release/$id", "inc" to "release-groups")  // null: not on MusicBrainz
            val d = body?.get("release-group")?.jsonObject?.get("first-release-date")?.jsonPrimitive?.content.orEmpty()
            k[id] = Entry(d, System.currentTimeMillis())
        }
    }

    /** The JSON answer, or null for 400/404. Waits its turn, and backs off when rate limited. */
    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonObject? {
        val url = "$API$path".toHttpUrl().newBuilder().apply {
            for ((name, v) in params) addQueryParameter(name, v)
            addQueryParameter("fmt", "json")
        }.build()
        repeat(5) { attempt ->
            val wait = last + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
            http.newCall(req).await().use { r ->
                when {
                    r.isSuccessful -> return AppJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    r.code == 400 || r.code == 404 -> return null
                    r.code != 503 || attempt == 4 -> throw HttpException(r.code, "MusicBrainz answered ${r.code}")
                }
            }
            delay(2000L * (attempt + 1))
        }
        throw IOException("MusicBrainz is busy")
    }

    private fun load(): MutableMap<String, Entry> =
        runCatching { AppJson.decodeFromString(MAP, file.readText()).toMutableMap() }.getOrElse { HashMap() }

    private fun save(k: Map<String, Entry>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(MAP, k))
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val API = "https://musicbrainz.org/ws/2"
        private const val USER_AGENT = "Stemify/1.1 (https://github.com/kevinViers/stemify)"
        private const val BATCH = 40
        private const val GAP_MS = 1100L
        private const val FRESH_MS = 90L * 86_400_000
        private const val RETRY_MISSING_MS = 7L * 86_400_000
        private val MAP = MapSerializer(String.serializer(), Entry.serializer())

        /**
         * The day [album] first came out, or null when nobody knows the day. [mb] is resolve()'s
         * answer. MusicBrainz's date wins; when it gives only a year or month, the day is unknown.
         * Without a MusicBrainz answer, Plex's date counts unless it's on the 1st, which is what
         * Plex stores when it doesn't know the day.
         */
        fun releaseDay(album: Album, mb: Map<String, String>): LocalDate? {
            val fromMb = album.mbid?.let { mb[it] }
            if (fromMb != null) return fromMb.takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val d = album.released?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return d.takeIf { it.dayOfMonth != 1 }
        }
    }
}
