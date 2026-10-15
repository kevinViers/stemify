package com.stemify.mobile.stems

import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** How many of an album's (or artist's) songs have stems. */
data class Coverage(val total: Int, val withStems: Int) {
    val none get() = total > 0 && withStems == 0
    val partial get() = withStems in 1 until total
}

/**
 * Which albums and artists have stems. Plex albums carry no file paths, so this keeps a slim
 * copy of every track's path (fetched in pages, cached on disk, refreshed every few hours) and
 * matches it against the stems index whenever that changes. Until the first full listing
 * arrives [ready] is false and nothing is flagged.
 */
class StemCoverage(
    private val plex: PlexClient,
    private val stems: StemServer,
    private val settings: Settings,
    dir: File,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class Slim(val k: String, val al: String? = null, val ar: String? = null, val f: String? = null, val d: Long = 0)

    @Serializable
    private data class Listing(val section: String, val at: Long, val tracks: List<Slim>)

    private val file = File(dir, "library_tracks.json")
    private val lock = Mutex()
    @Volatile private var listing: Listing? = null

    private val _albums = MutableStateFlow<Map<String, Coverage>>(emptyMap())
    val albums: StateFlow<Map<String, Coverage>> = _albums.asStateFlow()
    private val _artists = MutableStateFlow<Map<String, Coverage>>(emptyMap())
    val artists: StateFlow<Map<String, Coverage>> = _artists.asStateFlow()
    private val _ready = MutableStateFlow(false)
    /** True once the whole library has been listed and matched against a non-empty stems index. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            listing = runCatching { AppJson.decodeFromString(Listing.serializer(), file.readText()) }.getOrNull()
            settings.musicSection.flow.collectLatest { section ->
                if (section.isEmpty()) return@collectLatest
                val l = listing
                if (l == null || l.section != section || System.currentTimeMillis() - l.at > STALE_MS) refresh()
            }
        }
        scope.launch(Dispatchers.Default) {
            stems.index.collectLatest { recompute() }
        }
    }

    /** Lists every track in the library again (every few hours). */
    suspend fun refresh() = lock.withLock {
        val section = settings.musicSection.value
        if (section.isEmpty() || !plex.signedIn) return@withLock
        val all = ArrayList<Slim>()
        runCatching {
            var start = 0
            while (true) {
                val (page, total) = plex.tracksPage(start, PAGE)
                all += page.map { Slim(it.ratingKey, it.albumKey, it.artistKey, it.partFile, it.durationMs) }
                start += page.size
                if (page.isEmpty() || start >= total) break
            }
        }.onFailure { return@withLock }
        val l = Listing(section, System.currentTimeMillis(), all)
        listing = l
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(AppJson.encodeToString(Listing.serializer(), l))
                tmp.renameTo(file)
            }
        }
        recompute()
    }

    private fun recompute() {
        val l = listing ?: return
        if (l.section != settings.musicSection.value) return
        if (stems.index.value.size == 0) return
        val albums = HashMap<String, IntArray>()
        val artists = HashMap<String, IntArray>()
        for (t in l.tracks) {
            val has = stems.find(Track(t.k, "", "", "", partFile = t.f, durationMs = t.d)) != null
            t.al?.let { albums.getOrPut(it) { IntArray(2) }.also { c -> c[0]++; if (has) c[1]++ } }
            t.ar?.let { artists.getOrPut(it) { IntArray(2) }.also { c -> c[0]++; if (has) c[1]++ } }
        }
        _albums.value = albums.mapValues { (_, c) -> Coverage(c[0], c[1]) }
        _artists.value = artists.mapValues { (_, c) -> Coverage(c[0], c[1]) }
        _ready.value = true
    }

    private companion object {
        const val PAGE = 1000
        const val STALE_MS = 6 * 3600_000L
    }
}
