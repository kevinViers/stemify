package com.stemify.mobile.plex

import android.net.Uri
import android.os.Build
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.HttpException
import com.stemify.mobile.core.Settings
import com.stemify.mobile.core.await
import com.stemify.mobile.core.awaitBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NotSignedInException : IOException("Not signed in to Plex")

/**
 * Plex Media Server and plex.tv calls. Library reads go through [http], whose cache
 * answers from disk when the server can't be reached (see Graph).
 */
class PlexClient(private val http: OkHttpClient, private val settings: Settings) {

    val signedIn get() = settings.plexToken.value.isNotEmpty()
    private val base get() = settings.plexUrl.value.trimEnd('/')
    private val token get() = settings.plexToken.value

    private fun Request.Builder.plexHeaders(withToken: Boolean = true): Request.Builder {
        header("Accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", "1.0")
        header("X-Plex-Client-Identifier", settings.clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Platform-Version", Build.VERSION.RELEASE)
        header("X-Plex-Device", Build.MODEL)
        header("X-Plex-Device-Name", "$PRODUCT (${Build.MODEL})")
        if (withToken && token.isNotEmpty()) header("X-Plex-Token", token)
        return this
    }

    private fun url(path: String, vararg params: Pair<String, Any?>): HttpUrl {
        val b = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v.toString())
        return b.build()
    }

    private suspend fun get(path: String, vararg params: Pair<String, Any?>, start: Int? = null, size: Int? = null): MediaContainer {
        if (!signedIn) throw NotSignedInException()
        // Paging goes in the query, not headers, so each page is its own entry in the HTTP cache:
        // offline, a shared entry answered every page with the same one and all() never finished.
        val paging = listOfNotNull(start?.let { "X-Plex-Container-Start" to it }, size?.let { "X-Plex-Container-Size" to it })
        val req = Request.Builder().url(url(path, *params, *paging.toTypedArray())).plexHeaders().build()
        val body = http.newCall(req).awaitBody()
        if (body.isBlank()) return MediaContainer()
        return AppJson.decodeFromString(PlexResponse.serializer(), body).container
    }

    private suspend fun send(method: String, path: String, vararg params: Pair<String, Any?>): MediaContainer {
        if (!signedIn) throw NotSignedInException()
        val body = if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
        val req = Request.Builder().url(url(path, *params)).plexHeaders().method(method, body).build()
        val text = http.newCall(req).awaitBody()
        return if (text.isBlank() || !text.trimStart().startsWith("{")) MediaContainer()
        else AppJson.decodeFromString(PlexResponse.serializer(), text).container
    }

    /** Pages through a section listing; Plex caps a single response on big libraries. */
    private suspend fun all(path: String, vararg params: Pair<String, Any?>): List<PlexItem> {
        val out = ArrayList<PlexItem>()
        var start = 0
        while (true) {
            val page = get(path, *params, start = start, size = PAGE)
            out += page.metadata
            start += page.metadata.size
            val total = page.totalSize ?: page.size
            if (page.metadata.isEmpty() || start >= total) break
        }
        return out
    }

    // ---- server -------------------------------------------------------------------

    suspend fun machineId(): String {
        val req = Request.Builder().url(url("/identity")).plexHeaders(false).build()
        val body = http.newCall(req).awaitBody()
        val id = AppJson.decodeFromString(PlexResponse.serializer(), body).container.machineIdentifier
        return id ?: throw IOException("Not a Plex server")
    }

    suspend fun musicSections(): List<MusicSection> = get("/library/sections").directories
        .filter { it.type == "artist" }
        .map { MusicSection(it.key, it.title, it.locations.map { l -> l.path }) }

    private val section get() = settings.musicSection.value

    // ---- library ------------------------------------------------------------------

    suspend fun artists(): List<Artist> =
        all("/library/sections/$section/all", "type" to 8, "sort" to "titleSort").map { it.toArtist() }

    suspend fun albums(sort: String = "titleSort"): List<Album> =
        all("/library/sections/$section/all", "type" to 9, "sort" to sort).map { it.toAlbum() }

    suspend fun recentlyAdded(count: Int = 20): List<Album> =
        get("/library/sections/$section/all", "type" to 9, "sort" to "addedAt:desc", start = 0, size = count)
            .metadata.map { it.toAlbum() }

    /** One page of every track, sorted by title. Returns the page and the library's track count. */
    suspend fun tracksPage(start: Int, size: Int): Pair<List<Track>, Int> {
        val c = get("/library/sections/$section/all", "type" to 10, "sort" to "titleSort", start = start, size = size)
        return c.metadata.map { it.toTrack() } to (c.totalSize ?: c.size)
    }

    suspend fun album(key: String): Album? = get("/library/metadata/$key").metadata.firstOrNull()?.toAlbum()

    suspend fun albumTracks(key: String): List<Track> = get("/library/metadata/$key/children").metadata.map { it.toTrack() }

    // Not /library/metadata/<artist>/children: that leaves out compilations, singles and live
    // albums (an artist with only "The Best of Kansas" came back empty).
    suspend fun artistAlbums(key: String): List<Album> =
        all("/library/sections/$section/all", "type" to 9, "artist.id" to key).map { it.toAlbum() }

    suspend fun artistTracks(key: String): List<Track> = get("/library/metadata/$key/allLeaves").metadata.map { it.toTrack() }

    suspend fun track(key: String): PlexItem? = get("/library/metadata/$key").metadata.firstOrNull()

    /** Title search inside the music section; used to give stem-only entries Plex metadata. */
    suspend fun searchTracks(title: String): List<Track> =
        get("/library/sections/$section/search", "type" to 10, "query" to title).metadata.map { it.toTrack() }

    /** Plex's own loudness analysis (what Plexamp draws its waveform from), in dB. */
    suspend fun loudness(trackKey: String, samples: Int): FloatArray? {
        val streamId = track(trackKey)?.media?.firstOrNull()?.parts?.firstOrNull()
            ?.streams?.firstOrNull { it.streamType == 2 }?.id ?: return null
        val levels = try {
            get("/library/streams/$streamId/levels", "subsample" to samples).levels
        } catch (e: HttpException) {
            return null // not analysed yet
        }
        return if (levels.isEmpty()) null else FloatArray(levels.size) { levels[it].v }
    }

    suspend fun search(query: String): SearchResults {
        val hubs = get("/hubs/search", "query" to query, "limit" to 12, "sectionId" to section).hubs
        fun hub(type: String) = hubs.firstOrNull { it.type == type }?.metadata.orEmpty()
        return SearchResults(
            artists = hub("artist").map { it.toArtist() },
            albums = hub("album").map { it.toAlbum() },
            tracks = hub("track").map { it.toTrack() },
            playlists = hub("playlist").filter { it.playlistType == "audio" }.map { it.toPlaylist() },
        )
    }

    // ---- playlists ----------------------------------------------------------------

    suspend fun playlists(): List<Playlist> = get("/playlists", "playlistType" to "audio").metadata.map { it.toPlaylist() }

    suspend fun playlistTracks(key: String): List<Track> = all("/playlists/$key/items").map { it.toTrack() }

    private suspend fun libraryUri(keys: List<String>): String {
        var machine = settings.plexMachineId.value
        if (machine.isEmpty()) machine = machineId().also { settings.plexMachineId.value = it }
        return "server://$machine/com.plexapp.plugins.library/library/metadata/${keys.joinToString(",")}"
    }

    suspend fun createPlaylist(title: String, trackKeys: List<String>): Playlist? =
        send("POST", "/playlists", "type" to "audio", "title" to title, "smart" to 0, "uri" to libraryUri(trackKeys))
            .metadata.firstOrNull()?.toPlaylist()

    suspend fun addToPlaylist(key: String, trackKeys: List<String>) {
        send("PUT", "/playlists/$key/items", "uri" to libraryUri(trackKeys))
    }

    suspend fun removeFromPlaylist(key: String, playlistItemId: Long) {
        send("DELETE", "/playlists/$key/items/$playlistItemId")
    }

    /** Moves an item to just after [afterItemId], or to the top when it's null. */
    suspend fun movePlaylistItem(key: String, playlistItemId: Long, afterItemId: Long?) {
        send("PUT", "/playlists/$key/items/$playlistItemId/move", "after" to afterItemId)
    }

    suspend fun renamePlaylist(key: String, title: String) {
        send("PUT", "/playlists/$key", "title" to title)
    }

    suspend fun deletePlaylist(key: String) {
        send("DELETE", "/playlists/$key")
    }

    // ---- playback reporting ---------------------------------------------------------

    suspend fun scrobble(trackKey: String) {
        runCatching { get("/:/scrobble", "key" to trackKey, "identifier" to "com.plexapp.plugins.library") }
    }

    suspend fun timeline(track: Track, state: String, positionMs: Long) {
        runCatching {
            get("/:/timeline", "ratingKey" to track.ratingKey, "key" to "/library/metadata/${track.ratingKey}",
                "state" to state, "time" to positionMs, "duration" to track.durationMs, "hasMDE" to 1)
        }
    }

    // ---- URLs -----------------------------------------------------------------------

    fun artUrl(thumb: String?, px: Int): String? {
        if (thumb.isNullOrEmpty() || !signedIn) return null
        return Uri.parse("$base/photo/:/transcode").buildUpon()
            .appendQueryParameter("width", px.toString())
            .appendQueryParameter("height", px.toString())
            .appendQueryParameter("minSize", "1")
            .appendQueryParameter("upscale", "1")
            .appendQueryParameter("url", thumb)
            .appendQueryParameter("X-Plex-Token", token)
            .build().toString()
    }

    fun partUrl(partKey: String): String = "$base$partKey?X-Plex-Token=${Uri.encode(token)}"

    // ---- plex.tv sign-in ------------------------------------------------------------

    suspend fun createPin(): PlexPin {
        val req = Request.Builder().url("https://plex.tv/api/v2/pins?strong=false").plexHeaders(false)
            .post(ByteArray(0).toRequestBody(null)).build()
        return AppJson.decodeFromString(PlexPin.serializer(), http.newCall(req).awaitBody())
    }

    suspend fun checkPin(id: Long): PlexPin {
        val req = Request.Builder().url("https://plex.tv/api/v2/pins/$id").plexHeaders(false).build()
        return AppJson.decodeFromString(PlexPin.serializer(), http.newCall(req).awaitBody())
    }

    suspend fun user(token: String): PlexUser {
        val req = Request.Builder().url("https://plex.tv/api/v2/user").plexHeaders(false)
            .header("X-Plex-Token", token).build()
        return AppJson.decodeFromString(PlexUser.serializer(), http.newCall(req).awaitBody())
    }

    /** True if [url] answers as a Plex server with the current token. */
    suspend fun probe(url: String, token: String): Boolean = runCatching {
        val req = Request.Builder().url(url.trimEnd('/') + "/library/sections").plexHeaders(false)
            .header("X-Plex-Token", token).build()
        withContext(Dispatchers.IO) { http.newCall(req).await().use { it.isSuccessful } }
    }.getOrDefault(false)

    companion object {
        const val PRODUCT = "Stemify"
        private const val PAGE = 1000
    }
}
