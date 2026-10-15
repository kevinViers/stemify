package com.stemroller.mobile.auto

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.stemroller.mobile.Graph
import com.stemroller.mobile.plex.Album
import com.stemroller.mobile.plex.Artist
import com.stemroller.mobile.plex.Playlist
import com.stemroller.mobile.plex.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a pick or a voice request turns into: a queue, where to start, and whether to shuffle it. */
class Resolved(val tracks: List<Track>, val start: Int = 0, val shuffle: Boolean = false)

/**
 * The browse tree Android Auto draws. Auto renders everything itself, so "designing for the
 * Miata" means shaping this tree for an 800×480 screen driven only by the commander knob:
 * four tabs, shallow folders, Shuffle as the first row, A–Z folders instead of long lists,
 * and grids only where the art identifies the item (albums).
 *
 * Away from home (Plex and stemserve unreachable) everything comes from the HTTP cache and
 * only songs that will actually play offline are listed, with Downloads as the first tab.
 */
@OptIn(UnstableApi::class)
class CarLibrary(private val g: Graph) {
    private val plex get() = g.carPlex
    private val pm get() = g.playback

    private class Cached(val at: Long, val tracks: List<Track>)
    private val trackCache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > 24
    }

    private suspend fun away() = !g.reach.reachable()

    // ---- tree -----------------------------------------------------------------------

    suspend fun root(limit: Int): List<MediaItem> {
        val tabs = listOf(
            tab(CarIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            tab(CarIds.ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
            tab(CarIds.ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            tab(CarIds.DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
        )
        val ordered = if (away()) listOf(tabs[3]) + tabs.take(3) else tabs
        return ordered.take(limit.coerceAtLeast(1))
    }

    /** Children of [id], or null if the id means nothing to us. */
    suspend fun children(id: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        val away = away()
        when (id) {
            CarIds.DOWNLOADS -> songs(CarIds.DOWNLOADS, tracksOf(CarIds.DOWNLOADS), shuffleRow = true, showArtist = true)
            CarIds.PLAYLISTS -> playlists(away)
            CarIds.ALBUMS -> albumsTab(away)
            CarIds.ARTISTS -> artistsTab(away)
            else -> {
                val (kind, key) = CarIds.split(id) ?: return@withContext null
                when (kind) {
                    "playlist" -> songs(id, tracksOf(id), shuffleRow = true, showArtist = true)
                    "album" -> songs(id, tracksOf(id), shuffleRow = false, showArtist = false)
                    "artist" -> artistPage(key, away)
                    "albums" -> albumsFor(away).filter { CarTree.letterOf(it.title) == key }.map(::albumItem)
                    "artists" -> artistsFor(away).filter { CarTree.letterOf(it.sortTitle) == key }.map(::artistItem)
                    "search" -> searchItems(key)
                    else -> null
                }
            }
        }
    }

    private suspend fun playlists(away: Boolean): List<MediaItem> {
        val all = plex.playlists()
        val shown = if (!away) all else all.filter { p -> runCatching { tracksOf(CarIds.playlist(p.ratingKey)).isNotEmpty() }.getOrDefault(false) }
        return shown.map(::playlistItem)
    }

    private suspend fun albumsTab(away: Boolean): List<MediaItem> {
        val albums = albumsFor(away)
        if (away || albums.size <= CarTree.FLAT_MAX) return albums.map(::albumItem)
        val recent = runCatching { plex.recentlyAdded(CarTree.RECENT_ALBUMS) }.getOrDefault(emptyList())
        return recent.map { albumItem(it, group = "Recently added") } +
            CarTree.buckets(albums) { it.title }.map { (letter, list) ->
                folder(CarIds.albumLetter(letter), letter, CarTree.countLabel(list.size, "album", "albums"),
                    MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, art = CarArtProvider.letterUri(letter), childStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, group = "All albums")
            }
    }

    private suspend fun artistsTab(away: Boolean): List<MediaItem> {
        val artists = artistsFor(away)
        if (artists.size <= CarTree.FLAT_MAX) return artists.map(::artistItem)
        return CarTree.buckets(artists) { it.sortTitle }.map { (letter, list) ->
            folder(CarIds.artistLetter(letter), letter, CarTree.countLabel(list.size, "artist", "artists"), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                art = CarArtProvider.letterUri(letter))
        }
    }

    private suspend fun artistPage(key: String, away: Boolean): List<MediaItem> {
        val id = CarIds.artist(key)
        val albums = if (away) downloadedTracks().filter { it.artistKey == key }.let(::albumsOf) else plex.artistAlbums(key)
        val shuffle = if (tracksOf(id).isNotEmpty()) listOf(shuffleItem(id, "Shuffle artist")) else emptyList()
        return shuffle + albums.map(::albumItem)
    }

    /** Away from home, the library is what's downloaded. */
    private suspend fun albumsFor(away: Boolean): List<Album> =
        if (away) albumsOf(downloadedTracks()).sortedBy { it.title.lowercase() } else plex.albums()

    private suspend fun artistsFor(away: Boolean): List<Artist> =
        if (away) {
            downloadedTracks().filter { it.artistKey != null }.distinctBy { it.artistKey }
                .map { Artist(it.artistKey!!, it.artist, it.artist, null) }.sortedBy { it.sortTitle.lowercase() }
        } else plex.artists()

    private fun albumsOf(tracks: List<Track>) = tracks.filter { it.albumKey != null }.distinctBy { it.albumKey }
        .map { Album(it.albumKey!!, it.album, it.artist, it.artistKey, it.year, it.thumb) }

    private fun downloadedTracks(): List<Track> =
        g.downloads.records.value.values.filter { it.done }.map { it.track }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc ?: 0 }, { it.index ?: 0 }))

    /** A container's songs, minus those that can't play right now. */
    private suspend fun tracksOf(container: String): List<Track> {
        val now = System.nanoTime()
        val away = away()
        // Home and away lists differ (away keeps only what plays offline), so they cache apart.
        val key = "$away|$container"
        synchronized(trackCache) {
            trackCache[key]?.takeIf { now - it.at < CACHE_NS && it.tracks.isNotEmpty() }?.let { return it.tracks }
        }
        val all = when (container) {
            CarIds.DOWNLOADS -> downloadedTracks()
            else -> {
                val (kind, key) = CarIds.split(container) ?: return emptyList()
                when (kind) {
                    "playlist" -> plex.playlistTracks(key)
                    "album" -> plex.albumTracks(key)
                    "artist" -> if (away) downloadedTracks().filter { it.artistKey == key } else plex.artistTracks(key)
                    "search" -> searchTracks(key)
                    else -> emptyList()
                }
            }
        }
        val playable = if (away) all.filter { pm.playableOffline(it) } else all
        synchronized(trackCache) { trackCache[key] = Cached(now, playable) }
        return playable
    }

    // ---- picks -----------------------------------------------------------------------

    /** Turns a tapped row into a queue. */
    suspend fun resolve(mediaId: String): Resolved? = withContext(Dispatchers.IO) {
        val pick = CarIds.parsePick(mediaId) ?: return@withContext null
        val tracks = tracksOf(pick.container)
        if (tracks.isEmpty()) return@withContext null
        when (pick) {
            is CarIds.Pick.Shuffle -> Resolved(tracks, shuffle = true)
            is CarIds.Pick.Song -> Resolved(tracks, tracks.indexOfFirst { it.ratingKey == pick.trackKey }.coerceAtLeast(0))
        }
    }

    /**
     * "Hey Google, play … on StemRoller". Prefers a playlist, then an artist (shuffled), then an
     * album whose name matches; otherwise plays the song hits. Away from home it searches downloads.
     */
    suspend fun resolveSearch(query: String): Resolved? = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            val dl = tracksOf(CarIds.DOWNLOADS)
            return@withContext if (dl.isEmpty()) null else Resolved(dl, shuffle = true)
        }
        if (away()) {
            val hits = offlineSearch(query)
            return@withContext if (hits.isEmpty()) null else Resolved(hits)
        }
        val r = plex.search(query)
        r.playlists.firstOrNull { CarTree.matches(it.title, query) }?.let { p ->
            tracksOf(CarIds.playlist(p.ratingKey)).takeIf { it.isNotEmpty() }?.let { return@withContext Resolved(it) }
        }
        r.artists.firstOrNull { CarTree.matches(it.title, query) }?.let { a ->
            tracksOf(CarIds.artist(a.ratingKey)).takeIf { it.isNotEmpty() }?.let { return@withContext Resolved(it, shuffle = true) }
        }
        r.albums.firstOrNull { CarTree.matches(it.title, query) }?.let { a ->
            tracksOf(CarIds.album(a.ratingKey)).takeIf { it.isNotEmpty() }?.let { return@withContext Resolved(it) }
        }
        if (r.tracks.isNotEmpty()) return@withContext Resolved(r.tracks)
        r.playlists.firstOrNull()?.let { return@withContext Resolved(tracksOf(CarIds.playlist(it.ratingKey))) }
        r.artists.firstOrNull()?.let { return@withContext Resolved(tracksOf(CarIds.artist(it.ratingKey)), shuffle = true) }
        r.albums.firstOrNull()?.let { return@withContext Resolved(tracksOf(CarIds.album(it.ratingKey))) }
        null
    }

    private suspend fun searchTracks(query: String): List<Track> = if (away()) offlineSearch(query) else plex.search(query).tracks

    private fun offlineSearch(query: String): List<Track> {
        val q = CarTree.norm(query)
        if (q.isEmpty()) return emptyList()
        val dl = downloadedTracks()
        return dl.filter { CarTree.norm(it.title).contains(q) } +
            dl.filter { !CarTree.norm(it.title).contains(q) && (CarTree.norm(it.artist).contains(q) || CarTree.norm(it.album).contains(q)) }
    }

    /** Results for Auto's search screen (voice input; there's no keyboard while driving). */
    suspend fun searchItems(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val container = CarIds.search(query)
        if (away()) return@withContext songs(container, tracksOf(container), shuffleRow = false, showArtist = true, group = "Downloaded songs")
        val r = plex.search(query)
        songs(container, tracksOf(container), shuffleRow = false, showArtist = true, group = "Songs") +
            r.artists.map { artistItem(it, group = "Artists") } +
            r.albums.map { albumItem(it, group = "Albums") } +
            r.playlists.map { playlistItem(it, group = "Playlists") }
    }

    // ---- items -----------------------------------------------------------------------

    private fun songs(container: String, tracks: List<Track>, shuffleRow: Boolean, showArtist: Boolean, group: String? = null): List<MediaItem> {
        val rows = tracks.map { t -> songItem(container, t, showArtist, group) }
        return if (shuffleRow && tracks.size > 1) listOf(shuffleItem(container, "Shuffle all", group)) + rows else rows
    }

    private fun songItem(container: String, t: Track, showArtist: Boolean, group: String?): MediaItem {
        // Stems are the norm, so only the exceptions are marked.
        val noStems = g.stems.index.value.size > 0 && !pm.hasStems(t)
        val subtitle = listOfNotNull(if (noStems) "No stems" else null, if (showArtist) t.artist else null).joinToString(" · ")
        val extras = Bundle().apply {
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            if (pm.playableOffline(t)) putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        }
        return item(
            CarIds.play(container, t.ratingKey), t.title, subtitle.ifEmpty { null }, CarArtProvider.uri(t.thumb),
            MediaMetadata.MEDIA_TYPE_MUSIC, playable = true, extras = extras,
        ) { setArtist(t.artist).setAlbumTitle(t.album).setDurationMs(t.durationMs.takeIf { it > 0 }) }
    }

    private fun shuffleItem(container: String, title: String, group: String? = null) = item(
        CarIds.shuffle(container), title, null, Uri.parse("android.resource://${g.packageName}/drawable/ic_car_shuffle"),
        MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true,
        extras = Bundle().apply { group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } },
    )

    private fun playlistItem(p: Playlist, group: String? = null) =
        folder(CarIds.playlist(p.ratingKey), p.title, CarTree.countLabel(p.trackCount, "song", "songs"), MediaMetadata.MEDIA_TYPE_PLAYLIST,
            art = artOr(p.thumb, p.title), group = group)

    private fun albumItem(a: Album) = albumItem(a, null)

    private fun albumItem(a: Album, group: String?) =
        folder(CarIds.album(a.ratingKey), a.title, a.artist, MediaMetadata.MEDIA_TYPE_ALBUM, art = artOr(a.thumb, a.title), group = group)

    private fun artistItem(a: Artist) = artistItem(a, null)

    private fun artistItem(a: Artist, group: String?) =
        folder(CarIds.artist(a.ratingKey), a.title, null, MediaMetadata.MEDIA_TYPE_ARTIST, art = artOr(a.thumb, a.sortTitle),
            childStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, group = group)

    /** Plex art, or a letter tile so Auto never draws its missing-image triangle. */
    private fun artOr(thumb: String?, title: String) = CarArtProvider.uri(thumb) ?: CarArtProvider.letterUri(CarTree.letterOf(title))

    private fun tab(id: String, title: String, type: Int, childStyle: Int) = folder(id, title, null, type, childStyle = childStyle)

    private fun folder(
        id: String, title: String, subtitle: String?, type: Int,
        art: Uri? = null, childStyle: Int? = null, group: String? = null,
    ) = item(id, title, subtitle, art, type, playable = false, extras = Bundle().apply {
        childStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    })

    private fun item(
        id: String, title: String, subtitle: String?, art: Uri?, type: Int, playable: Boolean, extras: Bundle,
        more: MediaMetadata.Builder.() -> Unit = {},
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                // Without a display title Media3 gives Auto the artist as the second line,
                // and the subtitle ("Stems · Artist", counts) never shows.
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art)
                .setMediaType(type)
                .setIsPlayable(playable)
                .setIsBrowsable(!playable)
                .setExtras(extras)
                .apply(more)
                .build(),
        ).build()

    private companion object {
        const val CACHE_NS = 120_000_000_000L
    }
}
