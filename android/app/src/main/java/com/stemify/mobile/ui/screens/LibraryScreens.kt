package com.stemify.mobile.ui.screens

import androidx.compose.foundation.background
import com.stemify.mobile.ui.components.ProgressRing
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.plex.Album
import com.stemify.mobile.plex.Artist
import com.stemify.mobile.plex.Track
import com.stemify.mobile.cache.DownloadRecord
import com.stemify.mobile.ui.components.Artwork
import com.stemify.mobile.ui.components.Chevron
import com.stemify.mobile.ui.components.EmptyState
import com.stemify.mobile.ui.components.ErrorRow
import com.stemify.mobile.ui.components.Hairline
import com.stemify.mobile.ui.components.IconButton
import com.stemify.mobile.ui.components.LoadingRow
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.MARGIN
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.SectionTitle
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.TextButton
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.nav.Load
import com.stemify.mobile.ui.nav.LocalNav
import com.stemify.mobile.ui.nav.Route
import com.stemify.mobile.ui.nav.rememberInEntry
import com.stemify.mobile.ui.nav.rememberLoader
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun LibraryHome() {
    val g = App.graph
    val nav = LocalNav.current
    val c = Ios.colors
    val menu = rememberMediaMenu()
    val token by g.settings.plexToken.flow.collectAsState()
    val section by g.settings.musicSection.flow.collectAsState()
    val today = remember { LocalDate.now() }
    val albums = rememberLoader("albums:$section") { g.plex.albums() }
    val days = remember(albums.value, today) { albums.value?.let { birthdays(it, today) } }
    val ready = token.isNotEmpty() && section.isNotEmpty()

    Page("Library") {
        if (!ready) {
            item("connect") {
                EmptyState(Sym.server, "Connect to Plex", "Sign in to your Plex server to browse your music. Stems come from stemserve on the same box.", "Connect") {
                    nav.push(Route.PlexAccount)
                }
            }
        }
        val rows: List<Triple<ImageVector, Route, String?>> = listOf(
            Triple(Sym.noteList, Route.Playlists, null),
            Triple(Sym.person, Route.Artists, null),
            Triple(Sym.album, Route.Albums, null),
            Triple(Sym.note, Route.Songs, null),
        )
        itemsIndexed(rows, key = { _, r -> r.second.title }) { i, (icon, route, value) ->
            LibraryRow(icon, route.title, value, last = i == rows.lastIndex) { nav.push(route) }
        }
        if (ready) {
            item("dayTitle") {
                Column {
                    SectionTitle("On This Day")
                    Txt(today.format(DateTimeFormatter.ofPattern("MMMM d")), Type.subhead, Modifier.padding(start = MARGIN.dp, bottom = 10.dp), color = c.label2)
                }
            }
            when (val s = albums.state.value) {
                Load.Loading -> item("dayLoading") { LoadingRow() }
                is Load.Err -> item("dayErr") { ErrorRow(s.message) { albums.refresh(true) } }
                is Load.Ok -> {
                    val (onDay, week) = days ?: (emptyList<Birthday>() to emptyList())
                    if (onDay.isNotEmpty()) {
                        albumGrid("today", onDay.map { it.album }, onClick = { nav.push(Route.AlbumPage(it)) }, onLongClick = { menu.album(it) },
                            subtitle = { a -> onDay.first { it.album === a }.let { "${a.artist} · ${yearsAgo(it.years)}" } })
                    } else {
                        item("dayNone") {
                            Txt(if (week.isEmpty()) "No albums in your library came out this week in any year." else "No album birthdays today. Coming up and just past:",
                                Type.subhead, Modifier.padding(horizontal = MARGIN.dp).padding(bottom = 12.dp), color = c.label2, maxLines = 3)
                        }
                        if (week.isNotEmpty()) {
                            albumGrid("week", week.map { it.album }, onClick = { nav.push(Route.AlbumPage(it)) }, onLongClick = { menu.album(it) },
                                subtitle = { a ->
                                    week.first { it.album === a }.let { b ->
                                        val verb = if (b.date.isBefore(today)) "Turned" else "Turns"
                                        "$verb ${b.years} · ${b.date.format(DateTimeFormatter.ofPattern("MMM d"))}"
                                    }
                                })
                        }
                    }
                }
            }
        }
    }
}

/** An album's anniversary: its release day this year, and how old it is then. */
private class Birthday(val album: Album, val date: LocalDate, val years: Int)

private fun yearsAgo(n: Int) = if (n == 1) "1 year ago" else "$n years ago"

/** Albums released on [today]'s date in earlier years, and, for slow days, the three days either side. */
private fun birthdays(albums: List<Album>, today: LocalDate): Pair<List<Birthday>, List<Birthday>> {
    val all = albums.mapNotNull { a ->
        val d = a.released?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@mapNotNull null
        if (d.year >= today.year) return@mapNotNull null
        val anniversary = d.withYear(today.year)  // Feb 29 falls back to Feb 28
        Birthday(a, anniversary, today.year - d.year)
    }
    val onDay = all.filter { it.date == today }.sortedByDescending { it.years }
    val week = all.filter { it.date != today && kotlin.math.abs(it.date.toEpochDay() - today.toEpochDay()) <= 3 }
        .sortedWith(compareBy({ it.date }, { -it.years }))
    return onDay to week
}

@Composable
private fun LibraryRow(icon: ImageVector, title: String, value: String?, last: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().background(if (pressed) c.cellPressed else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).tappable(interaction = src, onClick = onClick).padding(start = MARGIN.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, c.accent, size = 26.dp)
            Spacer(Modifier.width(14.dp))
            Txt(title, Type.title3, Modifier.weight(1f), weight = FontWeight.Normal)
            if (value != null) Txt(value, Type.body, color = c.label2, modifier = Modifier.padding(end = 8.dp))
            Chevron()
        }
        if (!last) Hairline(Modifier.align(Alignment.BottomStart).padding(start = 60.dp))
    }
}

// ---- artists --------------------------------------------------------------------------------------

@Composable
fun ArtistsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val menu = rememberMediaMenu()
    val artists = rememberLoader("artists") { g.plex.artists() }
    val state = rememberLazyListState()
    Page("Artists", state = state, overlay = {
        val list = artists.value
        if (list != null && list.size > 30) SectionIndex(list.map { it.sortTitle }, state, Modifier.align(Alignment.CenterEnd))
    }) {
        when (val s = artists.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { artists.refresh(true) } }
            is Load.Ok -> items(s.value.size, key = { s.value[it].ratingKey }) { i ->
                val a = s.value[i]
                ArtistRow(a, onLongClick = { menu.artist(a) }) { nav.push(Route.ArtistPage(a)) }
            }
        }
    }
}

private fun letterOf(s: String): Char {
    val ch = s.trimStart().removePrefix("The ").firstOrNull()?.uppercaseChar() ?: '#'
    return if (ch in 'A'..'Z') ch else '#'
}

/** UITableView's section index: drag down the letters to jump. */
@Composable
private fun SectionIndex(titles: List<String>, state: LazyListState, modifier: Modifier) {
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val letters = remember { ('A'..'Z').toList() + '#' }
    val firstIndex = remember(titles) {
        val m = HashMap<Char, Int>()
        titles.forEachIndexed { i, t -> m.putIfAbsent(letterOf(t), i) }
        m
    }
    var last by remember { mutableStateOf<Char?>(null) }
    fun jump(fraction: Float) {
        val l = letters[(fraction * letters.size).toInt().coerceIn(0, letters.lastIndex)]
        if (l == last) return
        last = l
        val target = letters.drop(letters.indexOf(l)).firstNotNullOfOrNull { firstIndex[it] } ?: return
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { state.scrollToItem(target + 1) }
    }
    Column(
        modifier.fillMaxHeight(0.62f).width(22.dp).padding(end = 2.dp)
            .pointerInput(titles) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    jump(down.position.y / size.height)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val p = ev.changes.firstOrNull() ?: break
                        if (!p.pressed) break
                        p.consume()
                        jump(p.position.y / size.height)
                    }
                    last = null
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { Txt(it.toString(), Type.caption2, color = c.accent, weight = FontWeight.SemiBold) }
    }
}

// ---- albums ---------------------------------------------------------------------------------------

private val ALBUM_SORTS = listOf("Title" to "titleSort", "Artist" to "artist.titleSort,year", "Recently Added" to "addedAt:desc", "Year" to "year:desc")

@Composable
fun AlbumsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
    var sort by rememberInEntry("sort") { mutableIntStateOf(0) }
    val albums = rememberLoader("albums:$sort") { g.plex.albums(ALBUM_SORTS[sort].second) }
    Page("Albums", trailing = {
        TextButton("Sort", {
            overlays.actions("Sort By", ALBUM_SORTS.mapIndexed { i, (label, _) -> SheetAction(if (i == sort) "✓ $label" else label) { sort = i } })
        })
    }) {
        when (val s = albums.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { albums.refresh(true) } }
            is Load.Ok -> {
                item("gap") { Spacer(Modifier.height(8.dp)) }
                albumGrid("albums", s.value, onClick = { nav.push(Route.AlbumPage(it)) }, onLongClick = { menu.album(it) })
            }
        }
    }
}

// ---- songs (paged) --------------------------------------------------------------------------------

private class SongPager {
    val tracks = mutableStateListOf<Track>()
    var total by mutableIntStateOf(-1)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun SongsScreen() {
    val g = App.graph
    val menu = rememberMediaMenu()
    val pager = rememberInEntry("pager") { SongPager() }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun loadMore() {
        if (pager.loading || (pager.total >= 0 && pager.tracks.size >= pager.total)) return
        pager.loading = true
        scope.launch {
            runCatching { g.plex.tracksPage(pager.tracks.size, 200) }
                .onSuccess { (page, total) -> pager.tracks += page; pager.total = total; pager.error = null }
                .onFailure { pager.error = it.message }
            pager.loading = false
        }
    }
    LaunchedEffect(Unit) {
        if (pager.tracks.isEmpty()) loadMore()
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged().filter { it > pager.tracks.size - 40 }.collect { loadMore() }
    }
    val subtitleTotal by remember { derivedStateOf { if (pager.total > 0) songs(pager.total) else null } }
    Page("Songs", state = state) {
        if (pager.tracks.isNotEmpty()) {
            item("buttons") {
                Column {
                    subtitleTotal?.let { Txt(it, Type.subhead, Modifier.padding(start = MARGIN.dp, bottom = 12.dp), color = Ios.colors.label2) }
                    PlayShuffle(
                        onPlay = { g.playback.playTracks(pager.tracks.take(1000), 0) },
                        onShuffle = { g.playback.playTracks(pager.tracks.take(1000), shuffle = true) },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        items(pager.tracks.size, key = { pager.tracks[it].ratingKey }) { i ->
            val t = pager.tracks[i]
            TrackRow(t, onClick = {
                val from = (i - 200).coerceAtLeast(0)
                g.playback.playTracks(pager.tracks.subList(from, (i + 800).coerceAtMost(pager.tracks.size)).toList(), i - from)
            }, onMore = { menu.show(t) })
        }
        if (pager.loading) item("more") { LoadingRow() }
        pager.error?.let { e -> item("err") { ErrorRow(e) { loadMore() } } }
    }
}

// ---- album & artist pages -------------------------------------------------------------------------

@Composable
fun AlbumScreen(album: Album) {
    val g = App.graph
    val nav = LocalNav.current
    val c = Ios.colors
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
    val tracks = rememberLoader("tracks") { g.plex.albumTracks(album.ratingKey) }
    val detail = rememberLoader("detail") { g.plex.album(album.ratingKey) ?: album }
    val a = detail.value ?: album
    val records by g.downloads.records.collectAsState()
    val list = tracks.value.orEmpty()
    val allDownloaded = list.isNotEmpty() && list.all { records[it.ratingKey]?.done == true }

    Page(a.title, largeTitle = false, trailing = {
        if (list.isNotEmpty()) {
            IconButton(if (allDownloaded) Sym.check else Sym.download, {
                if (allDownloaded) overlays.confirm("Remove Download?", "“${a.title}” will no longer be available offline.", "Remove") { g.downloads.remove(list) }
                else { g.downloads.download(list); overlays.hud("Downloading", Sym.download) }
            })
            IconButton(Sym.ellipsis, { menu.album(a) })
        }
    }) {
        item("header") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.thumb, 264.dp, corner = 10.dp, placeholder = Sym.album)
                Spacer(Modifier.height(16.dp))
                Txt(a.title, Type.title3, Modifier.padding(horizontal = 32.dp), align = TextAlign.Center, maxLines = 2)
                Txt(a.artist, Type.title3, Modifier.padding(horizontal = 32.dp).tappable {
                    a.artistKey?.let { nav.push(Route.ArtistPage(Artist(it, a.artist, a.artist))) }
                }, color = c.accent, weight = FontWeight.Normal, align = TextAlign.Center)
                val meta = listOfNotNull(a.genre?.uppercase(), a.year?.toString()).joinToString(" · ")
                if (meta.isNotEmpty()) Txt(meta, Type.caption1, Modifier.padding(top = 4.dp), color = c.label2, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                PlayShuffle(onPlay = { g.playback.playTracks(list, 0) }, onShuffle = { g.playback.playTracks(list, shuffle = true) })
                Spacer(Modifier.height(16.dp))
                Hairline(Modifier.padding(start = MARGIN.dp))
            }
        }
        when (val s = tracks.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { tracks.refresh(true) } }
            is Load.Ok -> {
                val discs = s.value.mapNotNull { it.disc }.toSet().size > 1
                s.value.forEachIndexed { i, t ->
                    if (discs && (i == 0 || s.value[i - 1].disc != t.disc)) {
                        item("disc${t.disc}") { Txt("Disc ${t.disc}", Type.headline, Modifier.padding(start = MARGIN.dp, top = 18.dp, bottom = 6.dp), color = c.label2) }
                    }
                    item(t.ratingKey) {
                        TrackRow(t, onClick = { g.playback.playTracks(s.value, i) }, number = t.index ?: (i + 1), showArt = false,
                            subtitle = t.artist.takeIf { it != a.artist }, onMore = { menu.show(t, allowGoTo = false) })
                    }
                }
                item("footer") {
                    Txt("${songs(s.value.size)}, ${formatMinutes(s.value.sumOf { it.durationMs })}", Type.subhead,
                        Modifier.padding(start = MARGIN.dp, top = 14.dp), color = c.label2)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(artist: Artist) {
    val g = App.graph
    val nav = LocalNav.current
    val menu = rememberMediaMenu()
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlays.current
    val albums = rememberLoader("albums") { g.plex.artistAlbums(artist.ratingKey).sortedByDescending { it.year ?: 0 } }
    fun playAll(shuffle: Boolean) = scope.launch {
        runCatching { g.plex.artistTracks(artist.ratingKey) }
            .onSuccess { g.playback.playTracks(it, 0, shuffle) }
            .onFailure { overlays.hud("Couldn't load songs", Sym.warning) }
    }
    Page(artist.title, trailing = { IconButton(Sym.ellipsis, { menu.artist(artist) }) }) {
        item("buttons") {
            Column(Modifier.padding(top = 6.dp, bottom = 18.dp)) { PlayShuffle(onPlay = { playAll(false) }, onShuffle = { playAll(true) }) }
        }
        when (val s = albums.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { albums.refresh(true) } }
            is Load.Ok -> albumGrid("albums", s.value, onClick = { nav.push(Route.AlbumPage(it)) }, onLongClick = { menu.album(it, allowGoTo = false) }, showArtist = false)
        }
    }
}

// ---- downloads ------------------------------------------------------------------------------------

private val DOWNLOAD_SORTS = listOf("Download Order", "Artist", "Release Date")

/** Downloaded songs gathered back into their albums. */
private class DownloadedAlbum(val album: Album, val records: List<DownloadRecord>, val released: String?) {
    val added = records.maxOf { it.added }
    val done = records.count { it.done }
    val failed = records.count { it.error != null }
    val complete get() = done == records.size
    val tracks get() = records.map { it.track }.sortedWith(compareBy({ it.disc ?: 0 }, { it.index ?: 0 }, { it.title.lowercase() }))
}

private fun artistSortKey(s: String) = s.trim().lowercase().removePrefix("the ")

@Composable
fun DownloadsScreen() {
    val g = App.graph
    val c = Ios.colors
    val nav = LocalNav.current
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
    val section by g.settings.musicSection.flow.collectAsState()
    val records by g.downloads.records.collectAsState()
    val progress by g.downloads.progress.collectAsState()
    val usage by g.cache.usage.collectAsState()
    val sort by g.settings.downloadSort.flow.collectAsState()
    // Release days come from the album list (served from the HTTP cache when the server's away).
    val albumInfo = rememberLoader("albums:$section") { g.plex.albums().associateBy { it.ratingKey } }
    val info = albumInfo.value.orEmpty()

    val albums = remember(records, info, sort) {
        val grouped = records.values.groupBy { r -> r.track.albumKey ?: "name:${r.track.artist}|${r.track.album}" }.map { (key, rs) ->
            val t = rs.first().track
            val known = info[key]
            val artists = rs.map { it.track.artist }.distinct()
            DownloadedAlbum(
                known ?: Album(key, t.album.ifEmpty { "Unknown Album" }, if (artists.size > 1) "Various Artists" else t.artist, t.artistKey, t.year, t.thumb),
                rs, known?.released ?: (known?.year ?: t.year)?.toString(),
            )
        }
        when (sort) {
            1 -> grouped.sortedWith(compareBy({ artistSortKey(it.album.artist) }, { it.released ?: "" }, { it.album.title.lowercase() }))
            2 -> grouped.sortedWith(compareByDescending<DownloadedAlbum> { it.released ?: "" }.thenBy { it.album.title.lowercase() })
            else -> grouped.sortedByDescending { it.added }
        }
    }
    val byKey = albums.associateBy { it.album.ratingKey }
    val allDone = albums.flatMap { a -> a.tracks.filter { records[it.ratingKey]?.done == true } }

    Page("Downloads", trailing = {
        if (albums.isNotEmpty()) {
            TextButton("Sort", {
                overlays.actions("Sort By", DOWNLOAD_SORTS.mapIndexed { i, label -> SheetAction(if (i == sort) "✓ $label" else label) { g.settings.downloadSort.value = i } })
            })
            IconButton(Sym.ellipsis, {
                overlays.actions("Downloads", buildList {
                    if (albums.any { it.failed > 0 }) add(SheetAction("Retry Failed") { g.downloads.retryFailed() })
                    add(SheetAction("Remove All Downloads", destructive = true) {
                        overlays.confirm("Remove All Downloads?", "Frees ${"%.1f".format(usage.pinnedBytes / 1e9)} GB. Songs stay in your library.", "Remove All") {
                            g.downloads.remove(records.values.map { it.track })
                        }
                    })
                })
            })
        }
    }) {
        if (albums.isEmpty()) {
            item("empty") { EmptyState(Sym.download, "Nothing Downloaded", "Hold any album, artist, playlist or song and choose Download to play it, stems and all, without your server.") }
            return@Page
        }
        item("summary") {
            Column(Modifier.padding(bottom = 14.dp)) {
                Txt("${albums.size} album${if (albums.size == 1) "" else "s"} · ${songs(records.size)} · ${"%.1f".format(usage.pinnedBytes / 1e9)} GB · by ${DOWNLOAD_SORTS[sort].lowercase()}",
                    Type.subhead, Modifier.padding(start = MARGIN.dp, bottom = 12.dp), color = c.label2)
                PlayShuffle(onPlay = { g.playback.playTracks(allDone, 0) }, onShuffle = { g.playback.playTracks(allDone, shuffle = true) })
            }
        }
        albumGrid(
            "downloads", albums.map { it.album },
            onClick = { a ->
                val d = byKey.getValue(a.ratingKey)
                if (a.ratingKey.startsWith("name:")) g.playback.playTracks(d.tracks, 0) else nav.push(Route.AlbumPage(a))
            },
            onLongClick = { a ->
                val d = byKey.getValue(a.ratingKey)
                val extra = buildList {
                    if (d.failed > 0) add(SheetAction("Retry Failed Songs") { g.downloads.retryFailed() })
                    if (!d.complete) add(SheetAction("Cancel Download", destructive = true) { g.downloads.remove(d.records.filter { !it.done }.map { it.track }) })
                }
                if (a.ratingKey.startsWith("name:")) menu.tracks("${a.title} — ${a.artist}", d.tracks, extra) else menu.album(a, extra)
            },
            subtitle = { a ->
                val d = byKey.getValue(a.ratingKey)
                when {
                    d.failed > 0 -> "${a.artist} · ${d.failed} failed"
                    !d.complete -> "${a.artist} · ${d.done} of ${d.records.size}"
                    sort == 2 && d.released != null -> "${a.artist} · ${d.released.take(4)}"
                    else -> a.artist
                }
            },
            badge = { a ->
                val d = byKey.getValue(a.ratingKey)
                if (!d.complete) {
                    val inFlight = d.records.sumOf { (progress[it.track.ratingKey] ?: 0f).toDouble() }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        if (d.failed > 0) Icon(Sym.warning, Color.White, size = 18.dp)
                        else ProgressRing(((d.done + inFlight) / d.records.size).toFloat(), size = 20.dp, color = Color.White)
                    }
                }
            },
        )
    }
}
