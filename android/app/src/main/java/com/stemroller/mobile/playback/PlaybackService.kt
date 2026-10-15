package com.stemroller.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stemroller.mobile.App
import com.stemroller.mobile.MainActivity
import com.stemroller.mobile.R
import com.stemroller.mobile.auto.CarArtProvider
import com.stemroller.mobile.auto.CarIds
import com.stemroller.mobile.auto.CarLibrary
import com.stemroller.mobile.auto.Resolved
import com.stemroller.mobile.plex.NotSignedInException
import com.stemroller.mobile.plex.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/** Rides on a MediaItem from the library callback to [StemPlayer.handleSetMediaItems]. */
private class PlayTag(val track: Track, val shuffle: Boolean)

/** Marks the items [MediaLibraryService] gets back from playback resumption: just resume the saved queue. */
private object ResumeTag

/**
 * Presents [PlaybackManager] to Media3 so the notification, lock screen, Bluetooth
 * buttons, Android Auto and other controllers drive the same queue the UI does.
 */
@OptIn(UnstableApi::class)
class StemPlayer(private val pm: PlaybackManager, scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val onChange: () -> Unit = { invalidateState() }
    private val items = HashMap<String, MediaItem>()
    private var error: PlaybackException? = null
    private var errorAt = 0L

    init {
        pm.addListener(onChange)
        // Auto has no toasts: a failed song shows as a player error until something plays.
        scope.launch {
            pm.messages.collect { msg ->
                error = PlaybackException(msg, null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                errorAt = System.nanoTime()
                invalidateState()
            }
        }
    }

    /** "Karaoke", "Custom mix"… for the playing song when it isn't the full mix; null otherwise. */
    fun mixLabel(): String? {
        if (pm.source.value != SourceKind.Stems) return null
        val p = MixPresets.current(pm.stemNames.value, pm.mix.value)
        return when {
            p == null -> "Custom mix"
            p === MixPresets.FULL -> null
            else -> p.label
        }
    }

    private fun mediaItem(item: QueueItem, label: String?): MediaItem = items.getOrPut("${item.uid}|$label") {
        val t = item.track
        MediaItem.Builder()
            .setMediaId("${item.uid}:${t.ratingKey}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    // The mix rides on the artist line: in the car it's the only feedback the mix button gets.
                    .setArtist(if (label != null) "${t.artist} · $label" else t.artist)
                    .setAlbumTitle(t.album)
                    .setTrackNumber(t.index)
                    .setDurationMs(t.durationMs)
                    .setArtworkUri(CarArtProvider.uri(t.thumb))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            ).build()
    }

    override fun getState(): State {
        val q = pm.queue.value
        val idx = pm.index.value
        if (items.size > q.size * 2 + 64) {
            val keep = q.map { it.uid.toString() }.toSet()
            items.keys.retainAll { it.substringBefore('|') in keep }
        }
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_PREPARE, COMMAND_RELEASE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
            COMMAND_SET_SHUFFLE_MODE, COMMAND_SET_REPEAT_MODE, COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
        ).build()
        if (error != null && (System.nanoTime() - errorAt > ERROR_NS || pm.playWhenReady.value && !pm.buffering.value)) error = null
        val b = State.Builder().setAvailableCommands(commands)
            .setPlayWhenReady(pm.playWhenReady.value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(pm.shuffle.value)
            .setRepeatMode(
                when (pm.repeat.value) {
                    RepeatMode.Off -> REPEAT_MODE_OFF
                    RepeatMode.All -> REPEAT_MODE_ALL
                    RepeatMode.One -> REPEAT_MODE_ONE
                },
            )
        if (q.isEmpty()) return b.setPlaybackState(STATE_IDLE).setPlayerError(error).build()
        val cur = idx.coerceIn(0, q.lastIndex)
        val label = mixLabel()
        val playlist = q.mapIndexed { i, item ->
            MediaItemData.Builder(item.uid)
                .setMediaItem(mediaItem(item, if (i == cur) label else null))
                .setDurationUs(if (item.track.durationMs > 0) item.track.durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val state = when {
            error != null && !pm.playWhenReady.value -> STATE_IDLE
            pm.buffering.value && pm.playWhenReady.value -> STATE_BUFFERING
            else -> STATE_READY
        }
        return b.setPlaylist(playlist)
            .setCurrentMediaItemIndex(cur)
            .setPlaybackState(state)
            // Media3 only allows an error alongside STATE_IDLE.
            .setPlayerError(if (state == STATE_IDLE) error else null)
            .setIsLoading(pm.buffering.value)
            .setContentPositionMs { pm.positionMs() }
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) pm.play() else pm.pause()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> = done()

    override fun handleStop(): ListenableFuture<*> {
        pm.pause()
        return done()
    }

    /** Items come from [PlaybackService]'s library callback, which tags each with its track. */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val tags = mediaItems.map { it.localConfiguration?.tag }
        if (tags.firstOrNull() === ResumeTag) return done()
        val picks = tags.filterIsInstance<PlayTag>()
        if (picks.isEmpty()) return done()
        val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        error = null
        pm.playTracks(picks.map { it.track }, start, shuffle = picks.first().shuffle)
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val tracks = mediaItems.mapNotNull { (it.localConfiguration?.tag as? PlayTag)?.track }
        when {
            tracks.isEmpty() -> Unit
            index >= pm.queue.value.size -> pm.addToQueue(tracks)
            index == pm.index.value + 1 -> pm.playNext(tracks)
            else -> pm.addToQueue(tracks)
        }
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        for (i in toIndex - 1 downTo fromIndex) pm.removeAt(i)
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        // Media3 moves a range; the queue moves one at a time, which is all controllers send in practice.
        if (toIndex - fromIndex == 1) pm.move(fromIndex, newIndex)
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == pm.index.value) pm.seekTo(pos) else pm.skipTo(mediaItemIndex, pos)
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != pm.shuffle.value) pm.toggleShuffle()
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        pm.setRepeat(
            when (repeatMode) {
                REPEAT_MODE_ONE -> RepeatMode.One
                REPEAT_MODE_ALL -> RepeatMode.All
                else -> RepeatMode.Off
            },
        )
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        pm.removeListener(onChange)
        return done()
    }

    private companion object {
        const val ERROR_NS = 10_000_000_000L
    }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val graph get() = App.graph
    private val pm get() = graph.playback
    private val library by lazy { CarLibrary(graph) }
    private var buttonsKey = ""
    private var rootLimit = 4
    private val onChange: () -> Unit = { updateButtons() }
    private var homeWatch: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = StemPlayer(pm, graph.scope)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_stems) })
        pm.addListener(onChange)
        // Auto loads every tab up front and keeps them; when home comes into (or out of) reach the
        // lists change (everything vs. what plays offline), so tell it to load them again.
        homeWatch = graph.scope.launch {
            graph.reach.home.drop(1).collect {
                val s = session ?: return@collect
                s.notifyChildrenChanged(CarIds.ROOT, rootLimit, null)
                for (tab in CarIds.TABS) s.notifyChildrenChanged(tab, 1, null)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        homeWatch?.cancel()
        pm.removeListener(onChange)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---- buttons ---------------------------------------------------------------------

    /**
     * The now-playing buttons beside previous / play / next. On the Miata's 800-px-wide screen
     * Auto shows one or two of these and puts the rest behind "more", so the order is the
     * priority: vocals (the karaoke switch), then the preset cycler, then shuffle and repeat.
     * Songs without stems just get shuffle and repeat.
     */
    private fun buttons(): List<CommandButton> {
        val names = pm.stemNames.value
        val stems = pm.source.value == SourceKind.Stems
        val list = ArrayList<CommandButton>()
        if (stems && "vocals" in names) {
            val silent = MixPresets.vocalsSilent(names, pm.mix.value)
            list += custom(ACTION_VOCALS, if (silent) "Vocals on" else "Vocals off", if (silent) R.drawable.ic_car_vocals_off else R.drawable.ic_car_vocals)
        }
        if (stems && names.size > 1) {
            val next = MixPresets.next(names, pm.mix.value)
            list += custom(ACTION_MIX, "Mix: ${next.label}", R.drawable.ic_car_mix)
        }
        val shuffled = pm.shuffle.value
        list += CommandButton.Builder(if (shuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (shuffled) "Shuffle off" else "Shuffle on")
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val repeat = pm.repeat.value
        list += CommandButton.Builder(
            when (repeat) {
                RepeatMode.Off -> CommandButton.ICON_REPEAT_OFF
                RepeatMode.All -> CommandButton.ICON_REPEAT_ALL
                RepeatMode.One -> CommandButton.ICON_REPEAT_ONE
            },
        )
            .setDisplayName(
                when (repeat) {
                    RepeatMode.Off -> "Repeat all"
                    RepeatMode.All -> "Repeat one"
                    RepeatMode.One -> "Repeat off"
                },
            )
            .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        return list
    }

    private fun custom(action: String, name: String, icon: Int) = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setCustomIconResId(icon)
        .setDisplayName(name)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

    private fun updateButtons() {
        val s = session ?: return
        val b = buttons()
        val key = b.joinToString { "${it.sessionCommand?.customAction}:${it.displayName}:${it.icon}" }
        if (key == buttonsKey) return
        buttonsKey = key
        s.setMediaButtonPreferences(b)
    }

    private fun onButton(action: String): Boolean {
        val names = pm.stemNames.value
        val mix = pm.mix.value
        when (action) {
            ACTION_VOCALS -> {
                if (pm.source.value != SourceKind.Stems || "vocals" !in names) return false
                val v = mix["vocals"] ?: StemSetting()
                val anySolo = names.any { mix[it]?.solo == true }
                if (MixPresets.vocalsSilent(names, mix)) {
                    // Muted on their own: unmute. Silenced by a solo elsewhere (Rhythm…): back to the full mix.
                    if (v.muted && !anySolo) pm.toggleMute("vocals") else pm.applyMix(emptyMap())
                } else {
                    // Muting a soloed vocal (A Cappella) would silence everything; go to Karaoke instead.
                    if (anySolo) pm.applyMix(MixPresets.KARAOKE.build(names) ?: emptyMap()) else pm.toggleMute("vocals")
                }
            }
            ACTION_MIX -> {
                if (pm.source.value != SourceKind.Stems) return false
                pm.applyMix(MixPresets.next(names, mix).build(names) ?: emptyMap())
            }
            ACTION_SHUFFLE -> pm.toggleShuffle()
            ACTION_REPEAT -> pm.cycleRepeat()
            else -> return false
        }
        return true
    }

    // ---- library -----------------------------------------------------------------------

    private inner class Callback : MediaLibrarySession.Callback {
        private val scope get() = graph.scope

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            ACTIONS.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setMediaButtonPreferences(buttons())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> =
            Futures.immediateFuture(
                if (onButton(customCommand.customAction)) SessionResult(SessionResult.RESULT_SUCCESS)
                else SessionResult(SessionError.ERROR_NOT_SUPPORTED),
            )

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            // Auto says how many tabs it can show (4 on the Miata's screen).
            params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf { it > 0 }?.let { rootLimit = it }
            val root = MediaItem.Builder().setMediaId(CarIds.ROOT).setMediaMetadata(
                MediaMetadata.Builder().setTitle("StemRoller").setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
            ).build()
            val extras = Bundle().apply {
                putBoolean(SEARCH_SUPPORTED, true)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                val all = if (parentId == CarIds.ROOT) {
                    library.root(rootLimit)
                } else {
                    library.children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
                LibraryResult.ofItemList(pageOf(all, page, pageSize), params)
            } catch (e: Exception) {
                LibraryResult.ofError(sessionError(e))
            }
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
            val n = runCatching { library.searchItems(query).size }.getOrDefault(0)
            session.notifySearchResultChanged(browser, query, n, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(pageOf(library.searchItems(query), page, pageSize), params)
            } catch (e: Exception) {
                LibraryResult.ofError(sessionError(e))
            }
        }

        /** A tapped row (by id) or a voice request (by search query) becomes the whole queue. */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.firstOrNull() ?: throw IllegalArgumentException("nothing to play")
            val query = first.requestMetadata.searchQuery
            val r: Resolved = try {
                if (query != null) library.resolveSearch(query) else library.resolve(first.mediaId)
            } catch (e: Exception) {
                pm.report(if (e is NotSignedInException) "Sign in to Plex on your phone" else "Couldn't load that: ${e.message}")
                null
            } ?: run {
                pm.report(if (query != null) "Nothing found for “$query”" else "That isn't available offline")
                throw IllegalArgumentException("unresolved ${first.mediaId}")
            }
            MediaSession.MediaItemsWithStartPosition(r.tracks.map { tagged(it, r.shuffle) }, r.start, 0)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            scope.future {
                mediaItems.flatMap { item ->
                    val r = runCatching { library.resolve(item.mediaId) }.getOrNull() ?: return@flatMap emptyList()
                    r.tracks.drop(r.start).take(1).map { tagged(it, false) }
                }.toMutableList()
            }

        /** Bluetooth / Auto "play" after the app was killed: the queue is already restored from disk, so just resume it. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val q = pm.queue.value
            if (q.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("no saved queue"))
            val items = q.map { MediaItem.Builder().setMediaId("${it.uid}:${it.track.ratingKey}").setUri("stemroller://resume").setTag(ResumeTag).build() }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, pm.index.value.coerceIn(0, q.lastIndex), pm.positionMs()))
        }
    }

    private fun tagged(t: Track, shuffle: Boolean) = MediaItem.Builder()
        .setMediaId("track:${t.ratingKey}")
        .setUri("stemroller://track/${t.ratingKey}")
        .setTag(PlayTag(t, shuffle))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setArtworkUri(CarArtProvider.uri(t.thumb)).build())
        .build()

    private fun pageOf(all: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return all
        val from = page.toLong() * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from.toInt(), minOf(all.size.toLong(), from + pageSize).toInt())
    }

    private fun sessionError(e: Exception): SessionError {
        Log.w(TAG, "car browse failed", e)
        return when (e) {
            is NotSignedInException -> SessionError(SessionError.ERROR_SESSION_SETUP_REQUIRED, "Sign in to Plex on your phone")
            else -> SessionError(SessionError.ERROR_IO, if (graph.reach.lastKnown) "Couldn't reach Plex" else "Not downloaded – connect to home Wi-Fi")
        }
    }

    companion object {
        private const val TAG = "StemCar"
        private const val ACTION_VOCALS = "stemroller.VOCALS"
        private const val ACTION_MIX = "stemroller.MIX"
        private const val ACTION_SHUFFLE = "stemroller.SHUFFLE"
        private const val ACTION_REPEAT = "stemroller.REPEAT"
        private val ACTIONS = listOf(ACTION_VOCALS, ACTION_MIX, ACTION_SHUFFLE, ACTION_REPEAT)

        /** Tells Auto to show its (voice) search button. */
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
