package com.stemify.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.stems.Coverage
import com.stemify.mobile.plex.Album
import com.stemify.mobile.plex.Artist
import com.stemify.mobile.plex.Playlist
import com.stemify.mobile.plex.Track
import com.stemify.mobile.ui.components.Artwork
import com.stemify.mobile.ui.components.Chevron
import com.stemify.mobile.ui.components.DownloadedMark
import com.stemify.mobile.ui.components.Hairline
import com.stemify.mobile.ui.components.IconButton
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.MARGIN
import com.stemify.mobile.ui.components.Overlays
import com.stemify.mobile.ui.components.PillButton
import com.stemify.mobile.ui.components.PlayingBars
import com.stemify.mobile.ui.components.ProgressRing
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.Tag
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.nav.Route
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Tab(val label: String) { Library("Library"), Playlists("Playlists"), Search("Search"), Downloads("Downloads"), Settings("Settings") }

/** Things any screen can ask of the shell. */
class AppActions(
    val openNowPlaying: () -> Unit,
    val closeNowPlaying: () -> Unit,
    val selectTab: (Tab) -> Unit,
    /** Switches to the Library tab and pushes [route] there. */
    val showInLibrary: (Route) -> Unit,
    /** Switches to Settings and opens [route] (the Equalizer) there. */
    val showInSettings: (Route) -> Unit,
)

val LocalApp = staticCompositionLocalOf<AppActions> { error("no AppActions") }

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatMinutes(ms: Long): String {
    val m = (ms / 60000).toInt()
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m minute${if (m == 1) "" else "s"}"
}

fun songs(n: Int) = "$n song${if (n == 1) "" else "s"}"

/**
 * Stem / download status for a track, read reactively. Stems are the norm once the server has
 * worked through the library, so the badge marks the songs *without* them (and only once the
 * index has loaded, so nothing is flagged before we know).
 */
@Composable
fun trackBadges(track: Track): Triple<Boolean, Boolean, Float?> {
    val g = App.graph
    val index by g.stems.index.collectAsState()
    val records by g.downloads.records.collectAsState()
    val progress by g.downloads.progress.collectAsState()
    val noStems = remember(track, index) { index.size > 0 && g.stems.find(track) == null }
    return Triple(noStems, records[track.ratingKey]?.done == true, progress[track.ratingKey])
}

/** "NO STEMS" / "SOME STEMS" for an album or artist, once the whole library has been matched. */
@Composable
fun StemFlag(cov: Coverage?, modifier: Modifier = Modifier) {
    val ready by App.graph.coverage.ready.collectAsState()
    if (!ready || cov == null) return
    when {
        cov.none -> Tag("NO STEMS", modifier = modifier)
        cov.partial -> Tag("${cov.withStems}/${cov.total} STEMS", modifier = modifier)
    }
}

/** Apple Music list row: art (or track number), title, artist, badges, and a ••• menu. */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showArt: Boolean = true,
    subtitle: String? = track.artist,
    onMore: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Ios.colors
    val pm = App.graph.playback
    val current by pm.current.collectAsState()
    val playing by pm.playWhenReady.collectAsState()
    val isCurrent = current?.track?.ratingKey == track.ratingKey
    val (noStems, downloaded, progress) = trackBadges(track)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val textStart: Dp = (if (leading != null) 44 else 0).dp + if (showArt) 68.dp else if (number != null) 52.dp else MARGIN.dp
    Box(modifier.fillMaxWidth().background(if (pressed) c.cellPressed else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (showArt) 64.dp else 48.dp)
                .tappable(onLongClick = onMore, interaction = src, onClick = onClick)
                .padding(start = if (leading != null) 8.dp else MARGIN.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(if (showArt) 8.dp else 0.dp))
            }
            if (showArt) {
                Box(contentAlignment = Alignment.Center) {
                    Artwork(track.thumb, 48.dp, corner = 5.dp)
                    if (isCurrent) Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        PlayingBars(playing, color = Color.White)
                    }
                }
                Spacer(Modifier.width(12.dp))
            } else if (number != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                    if (isCurrent) PlayingBars(playing) else Txt("$number", Type.body, color = c.label2)
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Txt(track.title, Type.body, color = if (isCurrent) c.accent else c.label)
                if (subtitle != null) Txt(subtitle, Type.subhead, color = c.label2)
            }
            if (noStems) {
                Spacer(Modifier.width(6.dp))
                Tag("NO STEMS")
            }
            when {
                progress != null -> {
                    Spacer(Modifier.width(8.dp))
                    ProgressRing(progress, size = 16.dp)
                }
                downloaded -> {
                    Spacer(Modifier.width(8.dp))
                    DownloadedMark()
                }
            }
            trailing?.invoke()
            if (onMore != null) IconButton(Sym.ellipsis, onMore, tint = c.label, size = 20.dp, box = 40.dp)
            else Spacer(Modifier.width(14.dp))
        }
        Hairline(Modifier.align(Alignment.BottomStart).padding(start = textStart))
    }
}

/**
 * Two-column album grid, laid out as rows so it can live in a LazyColumn. [subtitle] replaces
 * the artist (or year) line; [badge] draws over the art's corner.
 */
fun LazyListScope.albumGrid(
    key: String,
    albums: List<Album>,
    onClick: (Album) -> Unit,
    onLongClick: ((Album) -> Unit)? = null,
    showArtist: Boolean = true,
    subtitle: ((Album) -> String?)? = null,
    badge: (@Composable (Album) -> Unit)? = null,
) {
    val rows = albums.chunked(2)
    items(rows.size, key = { "$key.$it" }) { r ->
        val coverage by App.graph.coverage.albums.collectAsState()
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = MARGIN.dp)) {
            val tile = (maxWidth - 16.dp) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rows[r].forEach { a ->
                    Column(
                        Modifier.width(tile).padding(bottom = 18.dp)
                            .tappable(onLongClick = onLongClick?.let { { it(a) } }) { onClick(a) },
                    ) {
                        Box {
                            Artwork(a.thumb, tile, corner = 8.dp, placeholder = Sym.album)
                            badge?.let { Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { it(a) } }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(a.title, Type.subhead, Modifier.weight(1f, fill = false), weight = FontWeight.Medium)
                            StemFlag(coverage[a.ratingKey], Modifier.padding(start = 6.dp))
                        }
                        val sub = subtitle?.invoke(a) ?: if (showArtist) a.artist else a.year?.toString() ?: ""
                        if (sub.isNotEmpty()) Txt(sub, Type.subhead, color = Ios.colors.label2)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistRow(artist: Artist, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val coverage by App.graph.coverage.artists.collectAsState()
    Box(Modifier.fillMaxWidth().background(if (pressed) c.cellPressed else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).tappable(onLongClick = onLongClick, interaction = src, onClick = onClick)
                .padding(start = MARGIN.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artist.thumb, 44.dp, circle = true, placeholder = Sym.person)
            Spacer(Modifier.width(14.dp))
            Txt(artist.title, Type.body, Modifier.weight(1f))
            StemFlag(coverage[artist.ratingKey], Modifier.padding(horizontal = 8.dp))
            Chevron()
        }
        Hairline(Modifier.align(Alignment.BottomStart).padding(start = 78.dp))
    }
}

/** Play / Shuffle pair under a header. */
@Composable
fun PlayShuffle(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = MARGIN.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PillButton("Play", Sym.play, onPlay, Modifier.weight(1f))
        PillButton("Shuffle", Sym.shuffle, onShuffle, Modifier.weight(1f))
    }
}

/**
 * The long-press / ••• menu for anything playable: songs, albums, artists and playlists all
 * offer the front or end of the queue, download, and add-to-playlist (playlists included).
 */
class MediaMenu(private val overlays: Overlays, private val app: AppActions) {
    private val g = App.graph
    private val scope: CoroutineScope = g.scope

    fun show(track: Track, extra: List<SheetAction> = emptyList(), allowGoTo: Boolean = true) {
        val dl = g.downloads.records.value[track.ratingKey]
        val actions = buildList {
            addAll(queueActions { listOf(track) })
            if (dl == null) add(SheetAction("Download") { g.downloads.download(listOf(track)); overlays.hud("Downloading", Sym.download) })
            else add(SheetAction("Remove Download", destructive = true) { g.downloads.remove(listOf(track)) })
            add(SheetAction("Add to Playlist…") { addToPlaylist(listOf(track)) })
            if (track.partFile != null && g.stems.index.value.size > 0 && g.stems.find(track) == null) add(stemNow { listOf(track) })
            if (allowGoTo && track.albumKey != null) add(SheetAction("Go to Album") {
                app.showInLibrary(Route.AlbumPage(Album(track.albumKey, track.album, track.artist, track.artistKey, track.year, track.thumb)))
            })
            if (allowGoTo && track.artistKey != null) add(SheetAction("Go to Artist") {
                app.showInLibrary(Route.ArtistPage(Artist(track.artistKey, track.artist, track.artist)))
            })
            addAll(extra)
        }
        overlays.actions("${track.title} — ${track.artist}", actions)
    }

    fun album(a: Album, extra: List<SheetAction> = emptyList(), allowGoTo: Boolean = true) {
        val have = g.downloads.records.value.values.filter { it.done && it.track.albumKey == a.ratingKey }.map { it.track }
        val total = g.coverage.albums.value[a.ratingKey]?.total ?: a.trackCount ?: Int.MAX_VALUE
        collection("${a.title} — ${a.artist}", have, have.size >= total, { g.plex.albumTracks(a.ratingKey) }, null, stemmed = stemmed(g.coverage.albums.value[a.ratingKey]), extra = buildList {
            if (allowGoTo && a.artistKey != null) add(SheetAction("Go to Artist") { app.showInLibrary(Route.ArtistPage(Artist(a.artistKey, a.artist, a.artist))) })
            addAll(extra)
        })
    }

    fun artist(a: Artist) {
        val have = g.downloads.records.value.values.filter { it.done && it.track.artistKey == a.ratingKey }.map { it.track }
        val total = g.coverage.artists.value[a.ratingKey]?.total ?: Int.MAX_VALUE
        collection(a.title, have, have.size >= total, { g.plex.artistTracks(a.ratingKey) }, null, emptyList(), confirmDownload = true,
            stemmed = stemmed(g.coverage.artists.value[a.ratingKey]))
    }

    fun playlist(p: Playlist, extra: List<SheetAction> = emptyList()) {
        collection(p.title, emptyList(), false, { g.plex.playlistTracks(p.ratingKey) }, p.ratingKey, extra, confirmDownload = p.trackCount > 50)
    }

    /** For songs already in hand (downloads with no Plex album behind them). */
    fun tracks(title: String, tracks: List<Track>, extra: List<SheetAction> = emptyList()) {
        val have = tracks.filter { g.downloads.isDownloaded(it.ratingKey) }
        collection(title, have, have.size == tracks.size, { tracks }, null, extra,
            stemmed = g.stems.index.value.size > 0 && tracks.all { it.partFile == null || g.stems.find(it) != null })
    }

    /** True when every song is known to have stems, so there's nothing for Stem Now to do. */
    private fun stemmed(cov: Coverage?) = g.coverage.ready.value && cov != null && cov.withStems >= cov.total

    private fun collection(
        title: String, downloaded: List<Track>, allDownloaded: Boolean, load: suspend () -> List<Track>,
        playlistKey: String?, extra: List<SheetAction>, confirmDownload: Boolean = false, stemmed: Boolean = false,
    ) {
        overlays.actions(title, buildList {
            addAll(queueActions(load))
            if (!allDownloaded) add(SheetAction(if (downloaded.isEmpty()) "Download" else "Download the Rest") {
                withTracks(load) { tracks ->
                    if (confirmDownload && tracks.size > 20) {
                        overlays.confirm("Download ${songs(tracks.size)}?", "“$title” will play without your server, stems and all.", "Download", destructive = false) {
                            g.downloads.download(tracks); overlays.hud("Downloading", Sym.download)
                        }
                    } else {
                        g.downloads.download(tracks); overlays.hud("Downloading", Sym.download)
                    }
                }
            })
            if (downloaded.isNotEmpty()) add(SheetAction("Remove Download", destructive = true) {
                overlays.confirm("Remove Download?", "“$title” will no longer be available offline.", "Remove") { g.downloads.remove(downloaded) }
            })
            add(SheetAction("Add to Playlist…") { withTracks(load) { addToPlaylist(it, skip = playlistKey) } })
            if (!stemmed) add(stemNow(load))
            addAll(extra)
        })
    }

    /** Front of the queue (right after this song) and the end of it. */
    private fun queueActions(load: suspend () -> List<Track>) = listOf(
        SheetAction("Add to Front of Queue") { withTracks(load) { g.playback.playNext(it); overlays.hud("Playing Next", Sym.playNext) } },
        SheetAction("Add to End of Queue") { withTracks(load) { g.playback.addToQueue(it); overlays.hud("Added to Queue", Sym.addQueue) } },
    )

    /** Has stemd on the server separate the songs that lack stems ahead of the rest of the library. */
    private fun stemNow(load: suspend () -> List<Track>) = SheetAction("Stem Now") {
        scope.launch {
            runCatching { g.stems.stemNow(load()) }
                .onSuccess { n ->
                    if (n == 0) overlays.hud("Already has stems", Sym.info)
                    else overlays.hud(if (n == 1) "Stemming next" else "Stemming ${songs(n)} next", Sym.waveform)
                }
                .onFailure { overlays.hud("Couldn't queue: ${it.message}", Sym.warning) }
        }
    }

    private fun withTracks(load: suspend () -> List<Track>, then: (List<Track>) -> Unit) {
        scope.launch {
            runCatching { load() }
                .onSuccess { if (it.isEmpty()) overlays.hud("Nothing to add", Sym.info) else then(it) }
                .onFailure { overlays.hud("Couldn't load songs: ${it.message}", Sym.warning) }
        }
    }

    fun addToPlaylist(tracks: List<Track>, skip: String? = null) {
        scope.launch {
            val lists = runCatching { g.plex.playlists().filter { !it.smart && it.ratingKey != skip } }.getOrElse {
                overlays.hud("Couldn't load playlists", Sym.warning)
                return@launch
            }
            val keys = tracks.map { it.ratingKey }.filter { !it.startsWith("stem:") }
            if (keys.isEmpty()) {
                overlays.hud("These songs aren't in Plex, so they can't go in a playlist", Sym.info)
                return@launch
            }
            overlays.actions("Add ${songs(keys.size)} to Playlist", listOf(SheetAction("New Playlist…") { newPlaylist(tracks) }) + lists.map { p ->
                SheetAction(p.title) {
                    scope.launch {
                        runCatching { g.plex.addToPlaylist(p.ratingKey, keys) }
                            .onSuccess { overlays.hud("Added to ${p.title}") }
                            .onFailure { overlays.hud("Couldn't add: ${it.message}", Sym.warning) }
                    }
                }
            })
        }
    }

    fun newPlaylist(tracks: List<Track>, onCreated: () -> Unit = {}) {
        overlays.prompt("New Playlist", "Name it; you can add more songs later.", "", "Create", "Playlist name") { name ->
            scope.launch {
                runCatching { g.plex.createPlaylist(name, tracks.map { it.ratingKey }.filter { !it.startsWith("stem:") }) }
                    .onSuccess { overlays.hud("Created “$name”"); onCreated() }
                    .onFailure { overlays.hud("Couldn't create: ${it.message}", Sym.warning) }
            }
        }
    }
}

@Composable
fun rememberMediaMenu(): MediaMenu {
    val overlays = LocalOverlays.current
    val app = LocalApp.current
    return remember(overlays, app) { MediaMenu(overlays, app) }
}
