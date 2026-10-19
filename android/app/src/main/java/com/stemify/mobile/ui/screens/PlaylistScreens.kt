package com.stemify.mobile.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stemify.mobile.App
import com.stemify.mobile.plex.Playlist
import com.stemify.mobile.plex.SearchResults
import com.stemify.mobile.plex.Track
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
import com.stemify.mobile.ui.components.SearchField
import com.stemify.mobile.ui.components.SectionTitle
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.Tag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaylistsScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val c = Ios.colors
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
    val lists = rememberLoader("playlists") { g.plex.playlists() }
    val queue by g.playback.queue.collectAsState()
    LaunchedEffect(Unit) { lists.refresh() }
    Page("Playlists", trailing = {
        IconButton(Sym.plus, {
            // Plex can't hold an empty playlist, so new ones start from songs you have.
            val now = g.playback.current.value?.track
            val acts = buildList {
                if (queue.isNotEmpty()) add(SheetAction("New Playlist from Queue (${queue.size})") { menu.newPlaylist(queue.map { it.track }) { lists.refresh() } })
                if (now != null) add(SheetAction("New Playlist with “${now.title}”") { menu.newPlaylist(listOf(now)) { lists.refresh() } })
            }
            if (acts.isEmpty()) overlays.hud("Play something first, or use “Add to Playlist” on any song.", Sym.info)
            else overlays.actions("New Playlist", acts)
        })
    }) {
        when (val s = lists.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { lists.refresh(true) } }
            is Load.Ok -> {
                if (s.value.isEmpty()) item("empty") {
                    EmptyState(Sym.noteList, "No Playlists", "Hold any song, album, artist or playlist and choose “Add to Playlist”, or + to make one from your queue. They sync with Plexamp.")
                }
                items(s.value.size, key = { s.value[it].ratingKey }) { i ->
                    PlaylistRow(s.value[i], onLongClick = { menu.playlist(s.value[i]) }) { nav.push(Route.PlaylistPage(s.value[i])) }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistRow(p: Playlist, onLongClick: (() -> Unit)? = null, subtitle: String? = null, badge: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().background(if (pressed) c.cellPressed else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).tappable(onLongClick = onLongClick, interaction = src, onClick = onClick).padding(start = MARGIN.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(p.thumb, 60.dp, corner = 6.dp, placeholder = Sym.noteList)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(p.title, Type.body)
                Txt(subtitle ?: songs(p.trackCount), Type.subhead, color = c.label2)
            }
            if (badge != null) {
                badge()
                Spacer(Modifier.width(8.dp))
            }
            if (p.smart) {
                Tag("SMART")
                Spacer(Modifier.width(8.dp))
            }
            Chevron()
        }
        Hairline(Modifier.align(Alignment.BottomStart).padding(start = 94.dp))
    }
}

private class EditState {
    var editing by mutableStateOf(false)
    val items = mutableStateListOf<Track>()
    val removed = mutableStateListOf<Track>()
    var dragKey by mutableStateOf<Long?>(null)
    var dragOffset by mutableFloatStateOf(0f)
    var saving by mutableStateOf(false)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(playlist: Playlist) {
    val g = App.graph
    val nav = LocalNav.current
    val c = Ios.colors
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    // Downloaded playlists follow the server's copy, and stand in for it when the server's away.
    val tracks = rememberLoader("tracks") {
        try {
            g.plex.playlistTracks(playlist.ratingKey).also { g.downloads.syncPlaylist(playlist, it) }
        } catch (e: java.io.IOException) {
            g.downloads.playlistTracks(playlist.ratingKey).ifEmpty { throw e }
        }
    }
    val held by g.downloads.playlists.collectAsState()
    val downloaded = playlist.ratingKey in held
    var title by rememberInEntry("title") { mutableStateOf(playlist.title) }
    val edit = rememberInEntry("edit") { EditState() }
    val list = tracks.value.orEmpty()
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }

    fun commit() {
        val original = list
        val target = edit.items.toList()
        val removed = edit.removed.toList()
        edit.saving = true
        g.scope.launch {
            runCatching {
                removed.forEach { t -> t.playlistItemId?.let { g.plex.removeFromPlaylist(playlist.ratingKey, it) } }
                // Replay the new order as "move after" calls against a simulated server list.
                val sim = original.filter { o -> removed.none { it.playlistItemId == o.playlistItemId } }.toMutableList()
                target.forEachIndexed { i, t ->
                    if (sim.getOrNull(i)?.playlistItemId != t.playlistItemId) {
                        val id = t.playlistItemId ?: return@forEachIndexed
                        g.plex.movePlaylistItem(playlist.ratingKey, id, if (i == 0) null else target[i - 1].playlistItemId)
                        sim.removeAll { it.playlistItemId == id }
                        sim.add(i, t)
                    }
                }
            }.onFailure { overlays.hud("Couldn't save: ${it.message}", com.stemify.mobile.ui.theme.Sym.warning) }
            tracks.refresh()
            edit.saving = false
            edit.editing = false
        }
    }

    Page(title, largeTitle = false, trailing = {
        if (!playlist.smart && list.isNotEmpty()) {
            if (edit.saving) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Spinner() }
            else if (edit.editing) TextButton("Done", { commit() }, bold = true)
            else TextButton("Edit", {
                edit.items.clear(); edit.items.addAll(list); edit.removed.clear(); edit.editing = true
            })
        }
        if (!edit.editing && list.isNotEmpty()) IconButton(if (downloaded) Sym.check else Sym.download, {
            if (downloaded) overlays.confirm("Remove Download?", "“$title” will no longer be available offline.", "Remove") { g.downloads.removePlaylist(playlist.ratingKey) }
            else { g.downloads.downloadPlaylist(playlist.copy(title = title), list); overlays.hud("Downloading", Sym.download) }
        })
        if (!edit.editing) IconButton(Sym.ellipsis, {
            menu.playlist(playlist.copy(title = title, trackCount = list.size), extra = buildList {
                if (!playlist.smart) add(SheetAction("Rename…") {
                    overlays.prompt("Rename Playlist", null, title, "Save") { name ->
                        g.scope.launch { runCatching { g.plex.renamePlaylist(playlist.ratingKey, name) }.onSuccess { title = name } }
                    }
                })
                add(SheetAction("Delete Playlist", destructive = true) {
                    overlays.confirm("Delete “$title”?", "This removes it from Plex for every app.", "Delete") {
                        g.scope.launch { runCatching { g.plex.deletePlaylist(playlist.ratingKey) }.onSuccess { nav.pop() } }
                    }
                })
            })
        })
    }) {
        item("header") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(playlist.thumb, 230.dp, corner = 10.dp, placeholder = Sym.noteList)
                Spacer(Modifier.height(14.dp))
                Txt(title, Type.title2, Modifier.padding(horizontal = 32.dp), align = TextAlign.Center, maxLines = 2)
                if (list.isNotEmpty()) Txt("${songs(list.size)} · ${formatMinutes(list.sumOf { it.durationMs })}", Type.subhead, color = c.label2)
                Spacer(Modifier.height(14.dp))
                if (!edit.editing) PlayShuffle(onPlay = { g.playback.playTracks(list, 0) }, onShuffle = { g.playback.playTracks(list, shuffle = true) })
            }
        }
        when (val s = tracks.state.value) {
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(s.message) { tracks.refresh(true) } }
            is Load.Ok -> if (edit.editing) {
                items(edit.items.size, key = { edit.items[it].playlistItemId ?: it.toLong() }) { i ->
                    val t = edit.items[i]
                    val id = t.playlistItemId
                    val dragging = edit.dragKey == id
                    TrackRow(
                        t, onClick = {},
                        modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) edit.dragOffset else 0f }
                            .background(if (dragging) c.cellPressed else Color.Transparent)
                            .animateItem(placementSpec = if (dragging) null else androidx.compose.animation.core.spring(stiffness = 900f)),
                        leading = {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(c.red).tappable {
                                edit.removed += t
                                edit.items.remove(t)
                            }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(11.dp, 2.dp).background(Color.White))
                            }
                        },
                        trailing = {
                            Box(Modifier.size(44.dp).pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = { edit.dragKey = id; edit.dragOffset = 0f },
                                    onDragEnd = { edit.dragKey = null; edit.dragOffset = 0f },
                                    onDragCancel = { edit.dragKey = null; edit.dragOffset = 0f },
                                ) { ch, d ->
                                    ch.consume()
                                    edit.dragOffset += d.y
                                    val from = edit.items.indexOfFirst { it.playlistItemId == id }
                                    if (edit.dragOffset > rowPx / 2 && from < edit.items.lastIndex) {
                                        edit.items.add(from + 1, edit.items.removeAt(from))
                                        edit.dragOffset -= rowPx
                                    } else if (edit.dragOffset < -rowPx / 2 && from > 0) {
                                        edit.items.add(from - 1, edit.items.removeAt(from))
                                        edit.dragOffset += rowPx
                                    }
                                }
                            }, contentAlignment = Alignment.Center) { Icon(Sym.grip, c.label3, size = 22.dp) }
                        },
                    )
                }
            } else {
                items(s.value.size, key = { s.value[it].playlistItemId ?: it.toLong() }) { i ->
                    val t = s.value[i]
                    TrackRow(t, onClick = { g.playback.playTracks(s.value, i) }, onMore = {
                        menu.show(t, extra = if (playlist.smart) emptyList() else listOf(SheetAction("Remove from Playlist", destructive = true) {
                            g.scope.launch {
                                runCatching { t.playlistItemId?.let { g.plex.removeFromPlaylist(playlist.ratingKey, it) } }
                                tracks.refresh()
                            }
                        }))
                    })
                }
            }
        }
    }
}

// ---- search -------------------------------------------------------------------------------------

@Composable
fun SearchScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val c = Ios.colors
    val menu = rememberMediaMenu()
    var query by rememberInEntry("q") { mutableStateOf("") }
    var results by rememberInEntry("r") { mutableStateOf<Load<SearchResults>?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(280)
        results = Load.Loading
        results = try {
            Load.Ok(g.plex.search(q))
        } catch (e: Exception) {
            Load.Err(e.message ?: "Search failed")
        }
    }
    Page("Search") {
        item("field") { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), "Artists, Albums, Songs") }
        when (val r = results) {
            null -> item("hint") { EmptyState(Sym.search, "Search Your Library", "Find artists, albums, songs and playlists on your Plex server.") }
            Load.Loading -> item("loading") { LoadingRow() }
            is Load.Err -> item("err") { ErrorRow(r.message) { query = query.trim() + " " } }
            is Load.Ok -> {
                val s = r.value
                if (s.isEmpty) item("none") { EmptyState(Sym.search, "No Results", "Nothing matches “${query.trim()}”.") }
                if (s.artists.isNotEmpty()) {
                    item("ah") { SectionTitle("Artists") }
                    items(s.artists.size, key = { "a" + s.artists[it].ratingKey }) {
                        ArtistRow(s.artists[it], onLongClick = { menu.artist(s.artists[it]) }) { nav.push(Route.ArtistPage(s.artists[it])) }
                    }
                }
                if (s.albums.isNotEmpty()) {
                    item("alh") { SectionTitle("Albums") }
                    albumGrid("al", s.albums, onClick = { nav.push(Route.AlbumPage(it)) }, onLongClick = { menu.album(it) })
                }
                if (s.tracks.isNotEmpty()) {
                    item("th") { SectionTitle("Songs") }
                    items(s.tracks.size, key = { "t" + s.tracks[it].ratingKey }) { i ->
                        val t = s.tracks[i]
                        TrackRow(t, onClick = { g.playback.playTracks(s.tracks, i) }, subtitle = "${t.artist} — ${t.album}", onMore = { menu.show(t) })
                    }
                }
                if (s.playlists.isNotEmpty()) {
                    item("ph") { SectionTitle("Playlists") }
                    items(s.playlists.size, key = { "p" + s.playlists[it].ratingKey }) {
                        PlaylistRow(s.playlists[it], onLongClick = { menu.playlist(s.playlists[it]) }) { nav.push(Route.PlaylistPage(s.playlists[it])) }
                    }
                }
            }
        }
    }
}
