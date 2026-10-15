package com.stemroller.mobile.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stemroller.mobile.App
import com.stemroller.mobile.ui.components.Hairline
import com.stemroller.mobile.ui.components.LocalBottomInset
import com.stemroller.mobile.ui.components.LocalOverlays
import com.stemroller.mobile.ui.components.OverlayHost
import com.stemroller.mobile.ui.components.Overlays
import com.stemroller.mobile.ui.components.tappable
import com.stemroller.mobile.ui.nav.NavStack
import com.stemroller.mobile.ui.nav.Route
import com.stemroller.mobile.ui.nav.StackHost
import com.stemroller.mobile.ui.screens.AlbumScreen
import com.stemroller.mobile.ui.screens.EqFilterScreen
import com.stemroller.mobile.ui.screens.EqFiltersScreen
import com.stemroller.mobile.ui.screens.AlbumsScreen
import com.stemroller.mobile.ui.screens.AppActions
import com.stemroller.mobile.ui.screens.AppearanceScreen
import com.stemroller.mobile.ui.screens.ArtistScreen
import com.stemroller.mobile.ui.screens.ArtistsScreen
import com.stemroller.mobile.ui.screens.DownloadsScreen
import com.stemroller.mobile.ui.screens.EqScreen
import com.stemroller.mobile.ui.screens.LibraryHome
import com.stemroller.mobile.ui.screens.LocalApp
import com.stemroller.mobile.ui.screens.MiniPlayer
import com.stemroller.mobile.ui.screens.NowPlayingScreen
import com.stemroller.mobile.ui.screens.PlaylistScreen
import com.stemroller.mobile.ui.screens.PlaylistsScreen
import com.stemroller.mobile.ui.screens.PlexAccountScreen
import com.stemroller.mobile.ui.screens.QualityScreen
import com.stemroller.mobile.ui.screens.SearchScreen
import com.stemroller.mobile.ui.screens.SettingsScreen
import com.stemroller.mobile.ui.screens.SongsScreen
import com.stemroller.mobile.ui.screens.StemServerScreen
import com.stemroller.mobile.ui.screens.StorageScreen
import com.stemroller.mobile.ui.screens.Tab
import com.stemroller.mobile.ui.theme.Icon
import com.stemroller.mobile.ui.theme.IosTheme
import com.stemroller.mobile.ui.theme.Ios
import com.stemroller.mobile.ui.theme.Sym
import com.stemroller.mobile.ui.theme.Txt
import com.stemroller.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
private fun Screen(route: Route) = when (route) {
    Route.Library -> LibraryHome()
    Route.Artists -> ArtistsScreen()
    Route.Albums -> AlbumsScreen()
    Route.Songs -> SongsScreen()
    Route.Downloads -> DownloadsScreen()
    Route.Playlists -> PlaylistsScreen()
    Route.Search -> SearchScreen()
    Route.Eq -> EqScreen()
    is Route.EqFilters -> EqFiltersScreen(route.profileId)
    is Route.EqFilterPage -> EqFilterScreen(route.profileId, route.filterId)
    Route.Settings -> SettingsScreen()
    Route.PlexAccount -> PlexAccountScreen()
    Route.StemServer -> StemServerScreen()
    Route.Quality -> QualityScreen()
    Route.Storage -> StorageScreen()
    Route.Appearance -> AppearanceScreen()
    is Route.ArtistPage -> ArtistScreen(route.artist)
    is Route.AlbumPage -> AlbumScreen(route.album)
    is Route.PlaylistPage -> PlaylistScreen(route.playlist)
}

private fun rootOf(tab: Tab) = when (tab) {
    Tab.Library -> Route.Library
    Tab.Playlists -> Route.Playlists
    Tab.Search -> Route.Search
    Tab.Downloads -> Route.Downloads
    Tab.Settings -> Route.Settings
}

/** The Now Playing card's position: 0 hidden, 1 fully up. Driven by springs, drags and back swipes. */
@Stable
class SheetState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
    private var job: Job? = null

    fun animateTo(target: Float, velocity: Float = 0f, spec: AnimationSpec<Float> = spring(dampingRatio = 0.88f, stiffness = 380f)) {
        job?.cancel()
        job = scope.launch { animate(progress, target, velocity, spec) { v, _ -> progress = v.coerceIn(0f, 1f) } }
    }

    fun snap(v: Float) {
        job?.cancel()
        progress = v.coerceIn(0f, 1f)
    }
}

@Composable
fun StemApp(openNowPlaying: Int) {
    val g = App.graph
    val theme by g.settings.theme.flow.collectAsState()
    val accent by g.settings.accent.flow.collectAsState()
    IosTheme(theme, accent) { Shell(openNowPlaying) }
}

@Composable
private fun Shell(openNowPlaying: Int) {
    val g = App.graph
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    val overlays = remember { Overlays() }
    val holder = rememberSaveableStateHolder()
    val stacks = remember { Tab.entries.associateWith { NavStack(rootOf(it)) } }
    var tab by remember { mutableStateOf(Tab.Library) }
    val sheet = remember { SheetState(scope) }
    val sheetShown by remember { derivedStateOf { sheet.progress > 0.001f } }
    val current by g.playback.current.collectAsState()
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val actions = remember {
        AppActions(
            openNowPlaying = { sheet.animateTo(1f) },
            closeNowPlaying = { sheet.animateTo(0f) },
            selectTab = { tab = it },
            showInLibrary = { r ->
                sheet.animateTo(0f)
                tab = Tab.Library
                stacks.getValue(Tab.Library).push(r)
            },
            showInSettings = { r ->
                sheet.animateTo(0f)
                tab = Tab.Settings
                val st = stacks.getValue(Tab.Settings)
                if (st.entries.last().route != r) st.push(r)
            },
        )
    }
    LaunchedEffect(openNowPlaying) { if (openNowPlaying > 0 && g.playback.current.value != null) sheet.animateTo(1f) }
    LaunchedEffect(Unit) { g.playback.messages.collect { overlays.hud(it, Sym.warning) } }

    val bottomInset = navBottom + 50.dp + if (current != null) 64.dp else 0.dp

    CompositionLocalProvider(LocalOverlays provides overlays, LocalApp provides actions, LocalBottomInset provides bottomInset) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val heightPx = constraints.maxHeight.toFloat()
            val sheetDrag = Modifier.pointerInput(Unit) {
                val tracker = VelocityTracker()
                var y = 0f
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking(); y = 0f },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        if (v > 900f || sheet.progress < 0.7f) sheet.animateTo(0f, -v / heightPx) else sheet.animateTo(1f, -v / heightPx)
                    },
                    onDragCancel = { sheet.animateTo(1f) },
                ) { ch, dy ->
                    // The sheet moves with the finger, so track the finger in screen terms.
                    y += dy
                    tracker.addPosition(ch.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, y))
                    ch.consume()
                    sheet.snap(sheet.progress - dy / heightPx)
                }
            }
            val miniDrag = Modifier.pointerInput(Unit) {
                val tracker = VelocityTracker()
                var y = 0f
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking(); y = 0f },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().y
                        if (v < -700f || sheet.progress > 0.3f) sheet.animateTo(1f, -v / heightPx) else sheet.animateTo(0f, -v / heightPx)
                    },
                    onDragCancel = { sheet.animateTo(0f) },
                ) { ch, dy ->
                    // The sheet moves with the finger, so track the finger in screen terms.
                    y += dy
                    tracker.addPosition(ch.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, y))
                    ch.consume()
                    sheet.snap(sheet.progress - dy / heightPx)
                }
            }

            // The app, which recedes into a card while Now Playing is up.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val p = sheet.progress
                        val s = 1f - 0.075f * p
                        scaleX = s; scaleY = s
                        translationY = statusTop.toPx() * 0.55f * p
                        shape = RoundedCornerShape((14f * p).dp)
                        clip = p > 0f
                    }
                    .background(c.bg)
                    .drawWithContent {
                        drawContent()
                        val p = sheet.progress
                        if (p > 0f) drawRect(Color.Black.copy(alpha = 0.4f * p))
                    },
            ) {
                key(tab) {
                    holder.SaveableStateProvider("tab:${tab.name}") {
                        StackHost(stacks.getValue(tab), holder, active = true) { route -> Screen(route) }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (current != null) MiniPlayer(onOpen = { sheet.animateTo(1f) }, dragModifier = miniDrag)
                    TabBar(tab, navBottom) { t ->
                        if (t == tab) stacks.getValue(t).popToRoot() else tab = t
                    }
                }
            }

            if (sheetShown) {
                Box(
                    Modifier.fillMaxSize().padding(top = statusTop + 6.dp)
                        .graphicsLayer {
                            translationY = (1f - sheet.progress) * (heightPx + 40f)
                            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                            clip = true
                        },
                ) {
                    NowPlayingScreen(onClose = { sheet.animateTo(0f) }, dragModifier = sheetDrag)
                }
                // Predictive back slides the card down with the gesture; release to dismiss.
                PredictiveBackHandler(enabled = sheetShown) { events ->
                    try {
                        events.collect { e -> sheet.snap(1f - 0.3f * e.progress) }
                        sheet.animateTo(0f, spec = tween(220))
                    } catch (e: CancellationException) {
                        sheet.animateTo(1f)
                        throw e
                    }
                }
            }

            OverlayHost(overlays)
        }
    }
}

@Composable
private fun TabBar(selected: Tab, navBottom: androidx.compose.ui.unit.Dp, onSelect: (Tab) -> Unit) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().background(c.bar)) {
        Hairline(color = c.barBorder)
        Row(Modifier.fillMaxWidth().height(50.dp)) {
            Tab.entries.forEach { t ->
                val on = t == selected
                val col = if (on) c.accent else c.tabInactive
                Column(
                    Modifier.weight(1f).height(50.dp).tappable { onSelect(t) }.padding(top = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        when (t) {
                            Tab.Library -> Sym.library
                            Tab.Playlists -> Sym.noteList
                            Tab.Search -> Sym.search
                            Tab.Downloads -> Sym.download
                            Tab.Settings -> Sym.gear
                        },
                        col, size = 26.dp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Txt(t.label, Type.caption2.copy(fontSize = 10.sp), color = col, weight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(navBottom))
    }
}
