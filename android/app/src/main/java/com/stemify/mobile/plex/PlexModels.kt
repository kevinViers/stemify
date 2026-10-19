package com.stemify.mobile.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// ---- wire format (Accept: application/json) --------------------------------------

@Serializable
data class PlexResponse(@SerialName("MediaContainer") val container: MediaContainer = MediaContainer())

@Serializable
data class MediaContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    @SerialName("Metadata") val metadata: List<PlexItem> = emptyList(),
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
    @SerialName("Level") val levels: List<PlexLevel> = emptyList(),
    @SerialName("Lyrics") val lyrics: List<PlexLyrics> = emptyList(),
)

@Serializable
data class PlexItem(
    val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val titleSort: String? = null,
    val originalTitle: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val composite: String? = null,
    val year: Int? = null,
    val parentYear: Int? = null,
    /** Release date, "1975-11-14", when Plex knows the day. */
    val originallyAvailableAt: String? = null,
    val addedAt: Long? = null,
    val leafCount: Int? = null,
    val playlistItemID: Long? = null,
    val playlistType: String? = null,
    /** Boolean in current servers, "0"/"1" in some older ones. */
    val smart: JsonPrimitive? = null,
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
)

@Serializable
data class PlexMedia(
    val id: Long = 0,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val audioCodec: String? = null,
    val container: String? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val id: Long = 0,
    val key: String = "",
    val duration: Long? = null,
    val file: String? = null,
    val size: Long? = null,
    val container: String? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

/** streamType 2 is audio, 4 lyrics (key, codec "lrc" or "txt"). */
@Serializable
data class PlexStream(
    val id: Long = 0,
    val streamType: Int = 0,
    val key: String? = null,
    val codec: String? = null,
    val format: String? = null,
    val provider: String? = null,
)

/** A lyrics stream as JSON: lines with times in ms when synced, spans for parts of a line. */
@Serializable
data class PlexLyrics(
    val provider: String? = null,
    val by: String? = null,
    @SerialName("Line") val lines: List<PlexLyricLine> = emptyList(),
)

@Serializable
data class PlexLyricLine(
    val startOffset: Long? = null,
    val endOffset: Long? = null,
    @SerialName("Span") val spans: List<PlexLyricSpan> = emptyList(),
)

@Serializable
data class PlexLyricSpan(val text: String = "", val startOffset: Long? = null, val endOffset: Long? = null)

@Serializable
data class PlexTag(val tag: String = "")

@Serializable
data class PlexDirectory(
    val key: String = "",
    val type: String = "",
    val title: String = "",
    @SerialName("Location") val locations: List<PlexLocation> = emptyList(),
)

@Serializable
data class PlexLocation(val path: String = "")

@Serializable
data class PlexHub(
    val type: String = "",
    val hubIdentifier: String = "",
    val title: String = "",
    @SerialName("Metadata") val metadata: List<PlexItem> = emptyList(),
)

@Serializable
data class PlexLevel(val v: Float = 0f)

@Serializable
data class PlexPin(val id: Long = 0, val code: String = "", val authToken: String? = null)

@Serializable
data class PlexUser(val username: String? = null, val title: String? = null, val email: String? = null)

// ---- app models ------------------------------------------------------------------

@Serializable
data class Track(
    val ratingKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumKey: String? = null,
    val artistKey: String? = null,
    val index: Int? = null,
    val disc: Int? = null,
    val durationMs: Long = 0,
    val thumb: String? = null,
    val partKey: String? = null,
    val partId: Long? = null,
    val partFile: String? = null,
    val partSize: Long? = null,
    val codec: String? = null,
    val year: Int? = null,
    val playlistItemId: Long? = null,
    /** Set for tracks known only from the stems index (no Plex match): play this stem folder. */
    val stemKey: String? = null,
)

@Serializable
data class Album(
    val ratingKey: String,
    val title: String,
    val artist: String,
    val artistKey: String? = null,
    val year: Int? = null,
    val thumb: String? = null,
    val trackCount: Int? = null,
    val genre: String? = null,
    /** "1975-11-14" when Plex knows the release day. */
    val released: String? = null,
)

@Serializable
data class Artist(val ratingKey: String, val title: String, val sortTitle: String, val thumb: String? = null)

@Serializable
data class Playlist(
    val ratingKey: String,
    val title: String,
    val thumb: String? = null,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val smart: Boolean = false,
)

data class MusicSection(val key: String, val title: String, val locations: List<String>)

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

fun PlexItem.toTrack(): Track {
    val media = media.firstOrNull()
    val part = media?.parts?.firstOrNull()
    return Track(
        ratingKey = ratingKey,
        title = title,
        artist = originalTitle?.takeIf { it.isNotBlank() } ?: grandparentTitle ?: "Unknown Artist",
        album = parentTitle ?: "",
        albumKey = parentRatingKey,
        artistKey = grandparentRatingKey,
        index = index,
        disc = parentIndex,
        durationMs = duration ?: media?.duration ?: part?.duration ?: 0,
        thumb = thumb ?: parentThumb ?: grandparentThumb,
        partKey = part?.key,
        partId = part?.id,
        partFile = part?.file,
        partSize = part?.size,
        codec = media?.audioCodec ?: part?.container,
        year = parentYear ?: year,
        playlistItemId = playlistItemID,
    )
}

fun PlexItem.toAlbum() = Album(
    ratingKey = ratingKey,
    title = title,
    artist = parentTitle ?: "Unknown Artist",
    artistKey = parentRatingKey,
    year = year,
    thumb = thumb,
    trackCount = leafCount,
    genre = genres.firstOrNull()?.tag,
    released = originallyAvailableAt?.takeIf { it.length >= 10 },
)

fun PlexItem.toArtist() = Artist(ratingKey, title, titleSort ?: title, thumb)

fun PlexItem.toPlaylist() = Playlist(
    ratingKey = ratingKey,
    title = title,
    thumb = composite ?: thumb,
    trackCount = leafCount ?: 0,
    durationMs = duration ?: 0,
    smart = smart?.booleanOrNull ?: (smart?.content == "1"),
)
