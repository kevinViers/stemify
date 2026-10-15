package com.stemroller.mobile.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.stemroller.mobile.plex.Album
import com.stemroller.mobile.plex.Artist
import com.stemroller.mobile.plex.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Route {
    val title: String

    data object Library : Route { override val title = "Library" }
    data object Artists : Route { override val title = "Artists" }
    data object Albums : Route { override val title = "Albums" }
    data object Songs : Route { override val title = "Songs" }
    data object Downloads : Route { override val title = "Downloads" }
    data object Playlists : Route { override val title = "Playlists" }
    data object Search : Route { override val title = "Search" }
    data object Eq : Route { override val title = "Equalizer" }
    data class EqFilters(val profileId: String) : Route { override val title = "Filters" }
    data class EqFilterPage(val profileId: String, val filterId: String) : Route { override val title = "Filter" }
    data object Settings : Route { override val title = "Settings" }
    data object PlexAccount : Route { override val title = "Plex" }
    data object StemServer : Route { override val title = "Stem Server" }
    data object Quality : Route { override val title = "Stem Quality" }
    data object Storage : Route { override val title = "Storage" }
    data object Appearance : Route { override val title = "Appearance" }
    data class ArtistPage(val artist: Artist) : Route { override val title get() = artist.title }
    data class AlbumPage(val album: Album) : Route { override val title get() = album.title }
    data class PlaylistPage(val playlist: Playlist) : Route { override val title get() = playlist.title }
}

/** A screen instance on a stack. [store] keeps its loaded data while it stays on the stack. */
class Entry(val route: Route) {
    val id: Long = nextId++
    val store = HashMap<String, Any?>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private var nextId = 1L
    }
}

/**
 * One tab's navigation stack. [progress] is how far the top entry has come in: 1 at rest,
 * 0 fully off to the right. Pushes animate it 0→1, pops 1→0, and the predictive back
 * gesture drives it by hand.
 */
class NavStack(root: Route) {
    val entries = mutableStateListOf(Entry(root))
    var progress by mutableFloatStateOf(1f)
        private set
    internal var scope: CoroutineScope? = null
    internal var holder: SaveableStateHolder? = null
    private val busy = Mutex()

    private suspend fun animateTo(target: Float, spec: AnimationSpec<Float> = PUSH) {
        animate(progress, target, animationSpec = spec) { v, _ -> progress = v }
    }

    fun push(route: Route) {
        val s = scope
        if (s == null) {
            entries.add(Entry(route))
            return
        }
        s.launch {
            busy.withLock {
                Snapshot.withMutableSnapshot {
                    entries.add(Entry(route))
                    progress = 0f
                }
                animateTo(1f)
            }
        }
    }

    fun pop() {
        if (entries.size <= 1) return
        val s = scope ?: return removeTop()
        s.launch {
            busy.withLock {
                // A tap queued behind another pop may find nothing left to pop.
                if (entries.size <= 1) return@withLock
                animateTo(0f)
                finishPop()
            }
        }
    }

    fun popToRoot() {
        if (entries.size <= 1) return
        val s = scope
        if (s == null) {
            while (entries.size > 1) removeTop()
            return
        }
        s.launch {
            busy.withLock {
                // A second tap on the tab while the first is still animating lands here after it.
                if (entries.size <= 1) return@withLock
                // Drop the middle silently, then slide the top away.
                while (entries.size > 2) dispose(entries.removeAt(entries.size - 2))
                animateTo(0f)
                finishPop()
            }
        }
    }

    /** Removes the top and resets progress in one snapshot, so no frame shows a half state. */
    private fun finishPop() = Snapshot.withMutableSnapshot {
        removeTop()
        progress = 1f
    }

    private fun removeTop() {
        if (entries.size <= 1) return  // the root never leaves
        dispose(entries.removeAt(entries.lastIndex))
    }

    private fun dispose(e: Entry) {
        e.scope.cancel()
        holder?.removeState(e.id)
    }

    internal suspend fun backProgress(p: Float) = busy.withLock { if (entries.size > 1) progress = 1f - p }

    internal suspend fun backCommit() = busy.withLock {
        if (entries.size <= 1) {
            progress = 1f
            return@withLock
        }
        animateTo(0f, tween(180))
        finishPop()
    }

    internal suspend fun backCancel() = busy.withLock { animateTo(1f, spring(stiffness = 600f)) }

    companion object {
        /** Critically damped, like UINavigationController's push. */
        val PUSH = spring<Float>(dampingRatio = 1f, stiffness = 420f)
    }
}

val LocalNav = staticCompositionLocalOf<NavStack> { error("no NavStack") }
val LocalEntry = staticCompositionLocalOf<Entry> { error("no Entry") }

/**
 * Renders a [NavStack] with iOS's push/pop: the new page slides in from the right while
 * the old one parallaxes left by 30% and dims. During a predictive back swipe the same
 * animation follows the finger, and releasing commits or springs back.
 */
@Composable
fun StackHost(stack: NavStack, holder: SaveableStateHolder, active: Boolean, content: @Composable (Route) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SideEffect {
        stack.scope = scope
        stack.holder = holder
    }
    val moving by remember(stack) { derivedStateOf { stack.progress < 1f } }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val n = stack.entries.size
        val visible = if (moving && n >= 2) listOf(stack.entries[n - 2], stack.entries[n - 1]) else listOf(stack.entries[n - 1])
        for (e in visible) {
            key(e.id) {
                val isTop = e === stack.entries.last()
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            val p = stack.progress
                            translationX = if (isTop) w * (1f - p) else -0.3f * w * p
                        }
                        .drawWithContent {
                            drawContent()
                            if (!isTop) drawRect(Color.Black.copy(alpha = 0.18f * stack.progress))
                        },
                ) {
                    holder.SaveableStateProvider(e.id) {
                        CompositionLocalProvider(LocalNav provides stack, LocalEntry provides e) {
                            content(e.route)
                        }
                    }
                }
            }
        }
    }

    PredictiveBackHandler(enabled = active && stack.entries.size > 1) { events ->
        try {
            events.collect { e: BackEventCompat -> stack.backProgress(e.progress) }
            scope.launch { stack.backCommit() }
        } catch (e: CancellationException) {
            scope.launch { stack.backCancel() }
            throw e
        }
    }
}

// ---- per-entry loading ----------------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

class Loader<T>(private val scope: CoroutineScope, private val fetch: suspend () -> T) {
    private val _state = mutableStateOf<Load<T>>(Load.Loading)
    val state: State<Load<T>> = _state
    val value: T? get() = (_state.value as? Load.Ok<T>)?.value

    init {
        refresh(showLoading = true)
    }

    fun refresh(showLoading: Boolean = false) {
        if (showLoading) _state.value = Load.Loading
        scope.launch {
            _state.value = try {
                Load.Ok(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value is Load.Ok && !showLoading) return@launch
                Load.Err(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

/** Data for this screen, fetched once per stack entry and kept while it's on the stack. */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoader(key: String, fetch: suspend () -> T): Loader<T> {
    val entry = LocalEntry.current
    return remember(entry, key) { entry.store.getOrPut(key) { Loader(entry.scope, fetch) } as Loader<T> }
}

/** Per-entry state that survives the page leaving composition during transitions. */
@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberInEntry(key: String, init: () -> T): T {
    val entry = LocalEntry.current
    return remember(entry, key) { entry.store.getOrPut(key) { init() } as T }
}
