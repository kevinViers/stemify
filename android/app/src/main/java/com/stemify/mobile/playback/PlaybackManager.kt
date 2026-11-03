package com.stemify.mobile.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.stemify.mobile.cache.Downloads
import com.stemify.mobile.cache.MediaCache
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.engine.Crossfade
import com.stemify.mobile.engine.CrossfadeMode
import com.stemify.mobile.engine.CrossfadePlan
import com.stemify.mobile.engine.Envelope
import com.stemify.mobile.engine.EqParams
import com.stemify.mobile.engine.LoudnessMeter
import com.stemify.mobile.engine.ResourceDataSource
import com.stemify.mobile.engine.StemDecoder
import com.stemify.mobile.engine.StemEngine
import com.stemify.mobile.engine.TrackSession
import com.stemify.mobile.engine.masterTaper
import com.stemify.mobile.engine.stemTaper
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.Track
import com.stemify.mobile.stems.StemEntry
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Display order and colours for stems, matching the desktop app. */
object Stems {
    val ORDER = listOf("vocals", "drums", "bass", "guitar", "piano", "other", "instrumental", "mix")
    private val COLORS = mapOf(
        "vocals" to 0xFFFF7AB6, "drums" to 0xFFFFB547, "bass" to 0xFF7C8CFF, "other" to 0xFF4CD07D,
        "guitar" to 0xFFFF8A5C, "piano" to 0xFFB48CFF, "instrumental" to 0xFF3DD6C6, "mix" to 0xFF8E8E93,
    )

    fun color(name: String) = COLORS[name] ?: 0xFF8E8E93
    fun label(name: String) = if (name == "mix") "Original Mix" else name.replaceFirstChar { it.uppercase() }
    fun sort(names: List<String>) = names.sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
}

/** One stem's fader. [level] is the slider position; 0.8 is unity gain. */
@Serializable
data class StemSetting(val level: Float = UNITY, val muted: Boolean = false, val solo: Boolean = false) {
    companion object { const val UNITY = 0.8f }
}

enum class RepeatMode { Off, All, One }
enum class SourceKind { None, Stems, Original }

@Serializable
data class QueueItem(val uid: Long, val track: Track)

data class StemKeyParts(val entryKey: String, val file: String, val size: Long, val quality: StemQuality)

/** Waveform data for the seek bar: per-stem envelopes from stemserve, or Plex loudness in dB. */
class Waveform(val stems: Map<String, FloatArray>?, val loudnessDb: FloatArray?)

@Serializable
private data class SavedQueue(
    val items: List<QueueItem> = emptyList(),
    val unshuffled: List<QueueItem>? = null,
    val index: Int = -1,
    val positionMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.Off,
    val mix: Map<String, StemSetting> = emptyMap(),
)

/**
 * Owns the queue and the [StemEngine]. Everything public is main-thread only; the UI
 * reads the flows, and PlaybackService mirrors this into a MediaSession.
 */
class PlaybackManager(
    private val context: Context,
    private val settings: Settings,
    private val plex: PlexClient,
    private val stems: StemServer,
    private val cache: MediaCache,
    private val downloads: Downloads,
    private val eq: EqController,
    private val scope: CoroutineScope,
    /** Whether the home server answered last time anyone asked; away, songs that aren't downloaded are skipped. */
    private val home: () -> Boolean,
) : StemEngine.Listener {

    val engine = StemEngine(this)
    private val main = Handler(Looper.getMainLooper())
    private val queueFile = File(context.filesDir, "queue.json")

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _current = MutableStateFlow<QueueItem?>(null)
    val current: StateFlow<QueueItem?> = _current.asStateFlow()
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(RepeatMode.Off)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()
    private val _source = MutableStateFlow(SourceKind.None)
    val source: StateFlow<SourceKind> = _source.asStateFlow()
    private val _stemNames = MutableStateFlow<List<String>>(emptyList())
    val stemNames: StateFlow<List<String>> = _stemNames.asStateFlow()
    private val _mix = MutableStateFlow<Map<String, StemSetting>>(emptyMap())
    val mix: StateFlow<Map<String, StemSetting>> = _mix.asStateFlow()
    private val _master = MutableStateFlow(settings.masterVolume.value)
    val master: StateFlow<Float> = _master.asStateFlow()
    private val _waveform = MutableStateFlow<Waveform?>(null)
    val waveform: StateFlow<Waveform?> = _waveform.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var unshuffled: List<QueueItem>? = null
    private var nextUid = 1L
    private var tokens = 0L
    private var loadJob: Job? = null
    private var nextJob: Job? = null
    private var currentToken = -1L
    private var nextToken = -1L
    private var nextIndex = -1
    private var nextEntry: StemEntry? = null
    private var currentEntry: StemEntry? = null
    /** Where the playing and the queued song's audio comes from, and how long they are, for crossfades. */
    private var currentSources: List<Source> = emptyList()
    private var nextSources: List<Source> = emptyList()
    private var currentDurS = 0.0
    private var nextDurS = 0.0
    /** Measured song ends and starts ("key|tail", "key|head"), so a re-plan doesn't decode them again. */
    private val envelopes = object : LinkedHashMap<String, Envelope>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Envelope>?) = size > 40
    }
    private var pendingPositionMs = 0L
    private var failures = 0
    private var scrobbled = -1L
    private var duck = 1f

    init {
        eq.onParams = { p: EqParams? -> engine.eqParams = p }
        engine.eqParams = eq.currentParams()
        engine.masterGain = masterTaper(_master.value)
        restore()
        // A new crossfade setting applies from the next change of song.
        scope.launch {
            merge(settings.crossfade.flow.drop(1), settings.crossfadeSeconds.flow.drop(1), settings.crossfadeAlbums.flow.drop(1))
                .collect { requeueNext() }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (_playWhenReady.value) reportTimeline("playing")
            }
        }
    }

    /** Surfaces a message wherever messages show (toasts on the phone, the player error in the car). */
    fun report(message: String) {
        _messages.tryEmit(message)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = listeners.forEach { it() }

    fun positionMs(): Long {
        val snap = engine.snapshot
        val pos = if (currentToken >= 0 && snap.token == currentToken) snap.positionMs() else pendingPositionMs
        val d = _durationMs.value
        return if (d > 0) pos.coerceIn(0, d) else pos.coerceAtLeast(0)
    }

    // ---- queue --------------------------------------------------------------------------

    fun playTracks(tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        // Shuffle on the list that's already playing: keep the song going and deal the whole
        // list out again in a new order, rather than starting over on a random song.
        if (shuffle && isPlaying(tracks)) {
            reshuffle()
            if (!_playWhenReady.value) play()
            return
        }
        val items = tracks.map { QueueItem(nextUid++, it) }
        if (shuffle) {
            unshuffled = items
            val first = if (start in items.indices && start > 0) items[start] else items.random()
            _queue.value = listOf(first) + (items - first).shuffled()
            _shuffle.value = true
            load(0, 0, true)
        } else {
            unshuffled = null
            _shuffle.value = false
            _queue.value = items
            load(start.coerceIn(0, items.lastIndex), 0, true)
        }
    }

    fun playNext(tracks: List<Track>) = insert(_index.value + 1, tracks)
    fun addToQueue(tracks: List<Track>) = insert(_queue.value.size, tracks)

    private fun insert(at: Int, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map { QueueItem(nextUid++, it) }
        val q = _queue.value.toMutableList()
        q.addAll(at.coerceIn(0, q.size), items)
        _queue.value = q
        unshuffled = unshuffled?.plus(items)
        if (_index.value < 0) {
            load(0, 0, true)
        } else {
            requeueNext()
            changed()
            save()
        }
    }

    fun removeAt(i: Int) {
        val q = _queue.value.toMutableList()
        if (i !in q.indices) return
        val removed = q.removeAt(i)
        unshuffled = unshuffled?.filter { it.uid != removed.uid }
        _queue.value = q
        val idx = _index.value
        when {
            q.isEmpty() -> stop()
            i == idx -> load(i.coerceAtMost(q.lastIndex), 0, _playWhenReady.value)
            i < idx -> _index.value = idx - 1
        }
        requeueNext()
        changed()
        save()
    }

    fun move(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        q.add(to, q.removeAt(from))
        _queue.value = q
        val idx = _index.value
        _index.value = when {
            from == idx -> to
            from < idx && to >= idx -> idx - 1
            from > idx && to <= idx -> idx + 1
            else -> idx
        }
        requeueNext()
        changed()
        save()
    }

    fun clearUpNext() {
        val idx = _index.value
        if (idx < 0) return
        _queue.value = _queue.value.take(idx + 1)
        unshuffled = null
        _shuffle.value = false
        requeueNext()
        changed()
        save()
    }

    fun toggleShuffle() {
        if (!_shuffle.value) return reshuffle()
        val q = _queue.value
        val cur = q.getOrNull(_index.value)
        val orig = unshuffled ?: q
        _queue.value = orig
        _index.value = orig.indexOfFirst { it.uid == cur?.uid }.coerceAtLeast(0)
        unshuffled = null
        _shuffle.value = false
        requeueNext()
        changed()
        save()
    }

    /**
     * The whole queue in a new random order, the playing song first, so everything gets played,
     * including songs before the one you started on. Again while shuffled, a fresh order.
     */
    private fun reshuffle() {
        val q = _queue.value
        if (q.isEmpty()) return
        val cur = q.getOrNull(_index.value)
        val orig = unshuffled ?: q
        unshuffled = orig
        _queue.value = listOfNotNull(cur) + orig.filter { it.uid != cur?.uid }.shuffled()
        if (cur != null) _index.value = 0
        _shuffle.value = true
        requeueNext()
        changed()
        save()
    }

    /** Is the queue (in its original order) these songs, with one of them playing? */
    private fun isPlaying(tracks: List<Track>): Boolean {
        if (_current.value == null) return false
        val have = (unshuffled ?: _queue.value).map { it.track.ratingKey }
        return have.size == tracks.size && have.sorted() == tracks.map { it.ratingKey }.sorted()
    }

    fun cycleRepeat() {
        _repeat.value = RepeatMode.entries[(_repeat.value.ordinal + 1) % RepeatMode.entries.size]
        requeueNext()
        changed()
        save()
    }

    fun setRepeat(mode: RepeatMode) {
        _repeat.value = mode
        requeueNext()
        changed()
    }

    // ---- transport ----------------------------------------------------------------------

    fun play() {
        if (_queue.value.isEmpty()) return
        if (!requestFocus()) return
        _playWhenReady.value = true
        if (currentToken < 0 && loadJob?.isActive != true) {
            load(_index.value.coerceAtLeast(0), pendingPositionMs, true)
            return
        }
        engine.setPlaying(true)
        holdLocks(true)
        reportTimeline("playing")
        changed()
    }

    fun pause() {
        _playWhenReady.value = false
        engine.setPlaying(false)
        holdLocks(false)
        pendingPositionMs = positionMs()
        reportTimeline("paused")
        changed()
        save()
    }

    fun togglePlay() = if (_playWhenReady.value) pause() else play()

    fun next() {
        val q = _queue.value
        if (q.isEmpty()) return
        val i = _index.value + 1
        when {
            i < q.size -> load(i, 0, _playWhenReady.value)
            _repeat.value != RepeatMode.Off -> load(0, 0, _playWhenReady.value)
            else -> { pause(); seekTo(0) }
        }
    }

    fun previous() {
        if (positionMs() > 3000 || _index.value <= 0) {
            seekTo(0)
            return
        }
        load(_index.value - 1, 0, _playWhenReady.value)
    }

    fun skipTo(i: Int, positionMs: Long = 0) {
        if (i in _queue.value.indices) load(i, positionMs, true)
    }

    /** Jumps [deltaMs] back or ahead in the song; past the end is the next song. */
    fun skipBy(deltaMs: Long) {
        val d = _durationMs.value
        val to = positionMs() + deltaMs
        if (d > 0 && to >= d) next() else seekTo(to.coerceAtLeast(0))
    }

    fun seekTo(ms: Long) {
        pendingPositionMs = ms.coerceAtLeast(0)
        if (currentToken >= 0) engine.seekTo(pendingPositionMs)
        changed()
    }

    fun stop() {
        loadJob?.cancel()
        nextJob?.cancel()
        engine.stop()
        reportTimeline("stopped")
        currentToken = -1
        nextToken = -1
        _queue.value = emptyList()
        _index.value = -1
        _current.value = null
        _playWhenReady.value = false
        _source.value = SourceKind.None
        _stemNames.value = emptyList()
        _waveform.value = null
        holdLocks(false)
        abandonFocus()
        changed()
        save()
    }

    // ---- mixer --------------------------------------------------------------------------

    fun setStemLevel(name: String, level: Float) = editMix(name) { it.copy(level = level.coerceIn(0f, 1f)) }
    fun toggleMute(name: String) = editMix(name) { it.copy(muted = !it.muted) }
    fun toggleSolo(name: String) = editMix(name) { it.copy(solo = !it.solo) }

    fun resetMix() {
        _mix.value = emptyMap()
        pushMix()
    }

    /** Karaoke and friends: set every stem at once. */
    fun applyMix(m: Map<String, StemSetting>) {
        _mix.value = m
        pushMix()
    }

    private fun editMix(name: String, f: (StemSetting) -> StemSetting) {
        _mix.value = _mix.value + (name to f(_mix.value[name] ?: StemSetting()))
        pushMix()
    }

    private fun pushMix() {
        val m = _mix.value
        val anySolo = _stemNames.value.any { m[it]?.solo == true }
        engine.setMix(_stemNames.value.associateWith { n ->
            val s = m[n] ?: StemSetting()
            when {
                anySolo && !s.solo -> 0f
                s.muted -> 0f
                else -> stemTaper(s.level)
            }
        })
        // The media session shows the mix (artist line, Vocals/Mix buttons), so it needs to hear about it.
        changed()
        save()
    }

    /** True when [track] would play without the home network: its stems (or, lacking stems, its file) are fully cached. */
    fun playableOffline(track: Track): Boolean {
        if (downloads.offlineCopy(track) != null) return true
        val entry = stems.find(track)
        if (entry != null) {
            return StemQuality.entries.any { q -> entry.stems.all { cache.isComplete(stemKey(entry, it.file, it.size, q)) } }
        }
        return track.partKey != null && cache.isComplete(plexKey(track))
    }

    fun hasStems(track: Track) = stems.find(track) != null

    fun setMaster(v: Float) {
        _master.value = v.coerceIn(0f, 1f)
        engine.masterGain = masterTaper(_master.value) * duck
        settings.masterVolume.value = _master.value
    }

    // ---- loading ------------------------------------------------------------------------

    private fun load(i: Int, positionMs: Long, play: Boolean) {
        val item = _queue.value.getOrNull(i) ?: return
        loadJob?.cancel()
        nextJob?.cancel()
        nextToken = -1
        engine.queueNext(null)
        _index.value = i
        _current.value = item
        pendingPositionMs = positionMs
        _durationMs.value = item.track.durationMs
        _waveform.value = null
        currentToken = -1
        if (play && !requestFocus()) return
        _playWhenReady.value = play
        holdLocks(play)
        _buffering.value = true
        if (!settings.keepStemMix.value) _mix.value = emptyMap()
        changed()
        save()
        loadJob = scope.launch {
            var session: TrackSession? = null
            try {
                val opened = withContext(Dispatchers.IO) { open(item.track, positionMs) }
                val s = opened.session
                val entry = opened.entry
                session = s
                if (!isActive) throw CancellationException()
                currentToken = s.token
                currentEntry = entry
                currentSources = opened.sources
                currentDurS = s.durationFrames.toDouble() / s.sampleRate
                applySession(s, entry)
                engine.load(s, _playWhenReady.value)
                session = null
                failures = 0
                scrobbled = -1
                reportTimeline(if (_playWhenReady.value) "playing" else "paused")
                loadWaveform(item.track, entry)
                prepareNext()
            } catch (e: CancellationException) {
                session?.release()
            } catch (e: Exception) {
                session?.release()
                _buffering.value = false
                Log.w(TAG, "couldn't play ${item.track.ratingKey} “${item.track.title}”", e)
                // Away from home, go on to the next song that's on the phone rather than through
                // (and stopping after) three that can't load.
                val away = !home()
                val q = _queue.value
                if (away && !playableOffline(item.track)) _messages.tryEmit("“${item.track.title}” isn't downloaded")
                else _messages.tryEmit("Couldn't play “${item.track.title}”: ${e.message ?: e.javaClass.simpleName}")
                val next = if (away) (i + 1 until q.size).firstOrNull { playableOffline(q[it].track) } else (i + 1).takeIf { it < q.size }
                if (next != null && (away || ++failures < 3)) load(next, 0, play) else {
                    failures = 0
                    _playWhenReady.value = false
                    holdLocks(false)
                }
                changed()
            }
        }
    }

    private fun applySession(s: TrackSession, entry: StemEntry?) {
        _source.value = if (entry != null) SourceKind.Stems else SourceKind.Original
        _stemNames.value = s.names
        val frames = s.durationFrames
        if (frames > 0) _durationMs.value = frames * 1000 / s.sampleRate
        pushMix()
        changed()
    }

    /** (stem name, cache key, URL): one stream of a song. */
    private class Source(val name: String, val key: String, val url: String)

    private class Opened(val session: TrackSession, val entry: StemEntry?, val sources: List<Source>)

    /** Opens every stem (or the original file) and waits for the decoders to know their format. */
    private fun open(track: Track, startMs: Long): Opened {
        // A download plays from exactly the files it saved, server or no server.
        val held = downloads.offlineCopy(track)
        val entry = if (held != null) held.entry else stems.find(track)
        val sources: List<Triple<String, String, String>> = if (held != null) {
            held.sources
        } else if (entry != null) {
            val q = qualityFor(entry)
            stems.prepare(entry, q)
            Stems.sort(entry.stems.map { it.name }).map { name ->
                val f = entry.stems.first { it.name == name }
                Triple(name, stemKey(entry, f.file, f.size, q), stems.fileUrl(entry, f, q))
            }
        } else {
            val part = track.partKey ?: throw IOException("Plex has no file for this track")
            listOf(Triple("mix", plexKey(track), plex.partUrl(part)))
        }
        val decoders = sources.map { (name, key, url) ->
            val res = cache.acquire(key) { url }
            StemDecoder(name, ResourceDataSource(res) { cache.release(res) }, RING_FRAMES)
        }
        try {
            decoders.forEach { it.start(0, 0) }
            for (d in decoders) {
                if (!d.awaitPrepared(45_000)) throw IOException(d.error?.message ?: "Timed out opening ${d.name}")
            }
            val rate = decoders.first().sampleRate
            if (decoders.any { it.sampleRate != rate }) throw IOException("Stems have mixed sample rates")
            val s = TrackSession(++tokens, decoders.map { it.name }, decoders)
            if (startMs > 0) s.startAt(startMs * rate / 1000)
            return Opened(s, entry, sources.map { (n, k, u) -> Source(n, k, u) })
        } catch (e: Exception) {
            decoders.forEach { it.release() }
            throw e
        }
    }

    /** Plays a downloaded copy if there is one, whatever quality it was saved at. */
    private fun qualityFor(entry: StemEntry): StemQuality {
        val want = stems.effectiveQuality(settings.stemQuality.value)
        val f = entry.stems.first()
        if (cache.isComplete(stemKey(entry, f.file, f.size, want))) return want
        return StemQuality.entries.firstOrNull { cache.isComplete(stemKey(entry, f.file, f.size, it)) } ?: want
    }

    /**
     * Builds the next track's session now so the switch can be gapless, then measures the end
     * of this song and the start of that one to plan the crossfade (see [Crossfade]). The session
     * is queued first, so a gapless switch is ready even before the plan is.
     */
    private fun prepareNext() {
        nextJob?.cancel()
        nextToken = -1
        engine.queueNext(null)
        val i = upcomingIndex() ?: return
        val item = _queue.value[i]
        val from = _current.value?.track
        val fromSources = currentSources
        val fromDurS = currentDurS
        nextJob = scope.launch {
            delay(4_000) // let the current track's stems get a head start
            var session: TrackSession? = null
            try {
                val opened = withContext(Dispatchers.IO) { open(item.track, 0) }
                val s = opened.session
                session = s
                if (!isActive) throw CancellationException()
                nextToken = s.token
                nextIndex = i
                nextEntry = opened.entry
                nextSources = opened.sources
                nextDurS = s.durationFrames.toDouble() / s.sampleRate
                engine.queueNext(s)
                session = null
                if (from != null) {
                    val plan = withContext(Dispatchers.IO) {
                        planCrossfade(from, fromSources, fromDurS, item.track, opened.sources, nextDurS)
                    }
                    if (plan != null && isActive) engine.planNext(s, plan)
                }
            } catch (e: CancellationException) {
                session?.release()
            } catch (e: Exception) {
                session?.release() // the normal load path will report it
            }
        }
    }

    /** How to cross from [a] into [b], or null to keep the switch plainly gapless. */
    private suspend fun planCrossfade(a: Track, aSources: List<Source>, aDurS: Double, b: Track, bSources: List<Source>, bDurS: Double): CrossfadePlan? {
        val mode = settings.crossfade.value
        if (mode == CrossfadeMode.Off || aDurS <= 0 || bDurS <= 0 || aSources.isEmpty() || bSources.isEmpty()) return null
        if (_repeat.value == RepeatMode.One) return null
        if (!settings.crossfadeAlbums.value && Crossfade.consecutive(a.albumKey, a.disc, a.index, b.albumKey, b.disc, b.index)) return null
        val tail = envelope("${a.ratingKey}|tail", aSources, maxOf(0.0, aDurS - Crossfade.TAIL_S), Crossfade.TAIL_S)
        val head = envelope("${b.ratingKey}|head", bSources, 0.0, Crossfade.HEAD_S)
        val plan = Crossfade.plan(mode, settings.crossfadeSeconds.value, tail, aDurS, head, bDurS)
        Log.i(TAG, "crossfade “${a.title}” → “${b.title}” ($mode): $plan")
        return plan
    }

    private suspend fun envelope(key: String, sources: List<Source>, startS: Double, lengthS: Double): Envelope? {
        synchronized(envelopes) { envelopes[key] }?.let { return it }
        val e = try {
            measure(sources, startS, lengthS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't measure $key", e)
            null
        }
        if (e != null) synchronized(envelopes) { envelopes[key] = e }
        return e
    }

    /**
     * Loudness of a song's streams (stems summed) from [startS] for [lengthS], decoded on their
     * own decoders. The bytes land in the media cache, so playing that part later reuses them.
     */
    private suspend fun measure(sources: List<Source>, startS: Double, lengthS: Double): Envelope? {
        val decoders = sources.map { src ->
            val res = cache.acquire(src.key) { src.url }
            StemDecoder("probe-${src.name}", ResourceDataSource(res) { cache.release(res) }, PROBE_RING)
        }
        try {
            decoders.forEach { it.start(0, 0) }
            if (decoders.any { !it.awaitPrepared(15_000) }) return null
            val rate = decoders.first().sampleRate
            if (decoders.any { it.sampleRate != rate }) return null
            val first = (startS * rate).toLong()
            decoders.forEach { it.seek(1, first) }
            val want = (lengthS * rate).toLong()
            val meter = LoudnessMeter(rate)
            val mix = FloatArray(PROBE_CHUNK * 2)
            val tmp = FloatArray(PROBE_CHUNK * 2)
            val deadline = System.nanoTime() + 30_000_000_000L
            var got = 0L
            while (got < want) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) return null
                var n = minOf(PROBE_CHUNK.toLong(), want - got).toInt()
                var ended = true
                for (d in decoders) {
                    if (d.error != null) return null
                    if (d.readyGen != 1 && d.readyGen != Int.MAX_VALUE) { n = 0; ended = false; continue }
                    val a = d.ring.availableFrames
                    if (d.ring.eos && a == 0) continue  // a stem that's run out doesn't hold the others back
                    ended = false
                    n = minOf(n, a)
                }
                if (ended) break
                if (n == 0) { delay(3); continue }
                java.util.Arrays.fill(mix, 0, n * 2, 0f)
                for (d in decoders) {
                    val r = d.ring.read(tmp, n)
                    for (k in 0 until r * 2) mix[k] += tmp[k]
                }
                meter.add(mix, n)
                got += n
            }
            return meter.result(first.toDouble() / rate)
        } finally {
            decoders.forEach { it.release() }
        }
    }

    private fun requeueNext() {
        if (currentToken >= 0) prepareNext()
    }

    private fun upcomingIndex(): Int? {
        val q = _queue.value
        val i = _index.value
        if (q.isEmpty() || i < 0) return null
        if (_repeat.value == RepeatMode.One) return i
        val after = (i + 1 until q.size) + if (_repeat.value == RepeatMode.All) (0..i) else IntRange.EMPTY
        // Away from home, the next song that's on the phone.
        if (!home()) return after.firstOrNull { playableOffline(q[it].track) }
        return after.firstOrNull()
    }

    private fun loadWaveform(track: Track, entry: StemEntry?) {
        val uid = _current.value?.uid
        scope.launch {
            val w = if (entry != null) {
                stems.peaks(entry, WAVE_BUCKETS)?.let { Waveform(it, null) }
            } else {
                runCatching { plex.loudness(track.ratingKey, WAVE_BUCKETS) }.getOrNull()?.let { Waveform(null, it) }
            }
            if (_current.value?.uid == uid) _waveform.value = w
        }
    }

    // ---- engine callbacks (main thread) ---------------------------------------------------

    override fun onEngineState() {
        _buffering.value = engine.buffering || (loadJob?.isActive == true)
        changed()
    }

    override fun onTransition(token: Long) {
        if (token == currentToken || token != nextToken) return
        scrobble()
        currentToken = token
        nextToken = -1
        _index.value = nextIndex
        _current.value = _queue.value.getOrNull(nextIndex)
        currentEntry = nextEntry
        currentSources = nextSources
        currentDurS = nextDurS
        pendingPositionMs = 0
        scrobbled = -1
        val item = _current.value ?: return
        _durationMs.value = item.track.durationMs
        _waveform.value = null
        if (!settings.keepStemMix.value) _mix.value = emptyMap()
        _source.value = if (currentEntry != null) SourceKind.Stems else SourceKind.Original
        _stemNames.value = Stems.sort((currentEntry?.stems?.map { it.name }) ?: listOf("mix"))
        pushMix()
        loadWaveform(item.track, currentEntry)
        reportTimeline("playing")
        prepareNext()
        changed()
        save()
    }

    override fun onEnded(token: Long) {
        if (token != currentToken) return
        scrobble()
        currentToken = -1
        // Normally the next song was queued for a gapless switch; if preparing it failed, try again now.
        upcomingIndex()?.let { i ->
            load(i, 0, true)
            return
        }
        _playWhenReady.value = false
        holdLocks(false)
        pendingPositionMs = 0
        reportTimeline("stopped")
        changed()
        save()
    }

    override fun onError(token: Long, message: String) {
        if (token != currentToken) return
        _messages.tryEmit("Playback stopped: $message")
        currentToken = -1
        val i = _index.value
        if (i + 1 < _queue.value.size && ++failures < 3) load(i + 1, 0, _playWhenReady.value) else {
            _playWhenReady.value = false
            holdLocks(false)
            changed()
        }
    }

    override fun onRouted(device: AudioDeviceInfo?) = eq.onRouted(device)

    private fun scrobble() {
        val item = _current.value ?: return
        if (scrobbled == item.uid) return
        val d = _durationMs.value
        if (d > 0 && positionMs() < d * 0.5 && engine.snapshot.token == currentToken) return
        scrobbled = item.uid
        scope.launch { plex.scrobble(item.track.ratingKey) }
    }

    private fun reportTimeline(state: String) {
        val t = _current.value?.track ?: return
        val pos = positionMs()
        if (!plex.signedIn) return
        scope.launch { plex.timeline(t, state, pos) }
        if (state == "playing" && _durationMs.value > 0 && pos > _durationMs.value * 0.9) scrobble()
    }

    // ---- focus, locks ---------------------------------------------------------------------

    private val am = context.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = _playWhenReady.value; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.25f; setMaster(_master.value) }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duck = 1f
                    setMaster(_master.value)
                    if (resumeOnGain) play()
                    resumeOnGain = false
                }
            }
        }, main)
        .build()

    private fun requestFocus() = am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    private fun abandonFocus() = am.abandonAudioFocusRequest(focusRequest)

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val noisyRegistered = AtomicBoolean(false)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stemify:playback").apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Stemify:stream").apply { setReferenceCounted(false) }

    private fun holdLocks(on: Boolean) {
        if (on) {
            if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
            if (!wifiLock.isHeld) wifiLock.acquire()
            if (noisyRegistered.compareAndSet(false, true)) {
                context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            }
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
            if (noisyRegistered.compareAndSet(true, false)) runCatching { context.unregisterReceiver(noisy) }
        }
    }

    // ---- persistence ------------------------------------------------------------------------

    private var saveJob: Job? = null

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val snap = SavedQueue(_queue.value, unshuffled, _index.value, positionMs(), _repeat.value, _mix.value)
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(queueFile.parentFile, "queue.json.tmp")
                    tmp.writeText(AppJson.encodeToString(SavedQueue.serializer(), snap))
                    tmp.renameTo(queueFile)
                }
            }
        }
    }

    private fun restore() {
        val saved = runCatching { AppJson.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull() ?: return
        _queue.value = saved.items
        unshuffled = saved.unshuffled
        _shuffle.value = saved.unshuffled != null
        _repeat.value = saved.repeat
        _mix.value = saved.mix
        nextUid = (saved.items.maxOfOrNull { it.uid } ?: 0) + 1
        val i = saved.index.coerceIn(-1, saved.items.lastIndex)
        _index.value = i
        _current.value = saved.items.getOrNull(i)
        _durationMs.value = _current.value?.track?.durationMs ?: 0
        pendingPositionMs = saved.positionMs
    }

    companion object {
        const val RING_FRAMES = StemEngine.RING_SECONDS * 48_000
        const val WAVE_BUCKETS = 256
        /** Measuring a song for a crossfade: its own small rings, read in these steps. */
        private const val PROBE_RING = 48_000
        private const val PROBE_CHUNK = 4096
        private const val TAG = "StemPlayback"

        fun stemKey(entry: StemEntry, file: String, size: Long, q: StemQuality) = "stem|${entry.key}|$file|$size|${q.name}"

        /** The parts of a [stemKey]; null for anything else. */
        fun parseStemKey(key: String): StemKeyParts? {
            if (!key.startsWith("stem|")) return null
            val p = key.split('|')
            if (p.size < 5) return null
            val q = StemQuality.entries.firstOrNull { it.name == p.last() } ?: return null
            val size = p[p.size - 2].toLongOrNull() ?: return null
            return StemKeyParts(p.subList(1, p.size - 3).joinToString("|"), p[p.size - 3], size, q)
        }
        fun plexKey(track: Track) = "plex|${track.partId}|${track.partSize}"
    }
}
