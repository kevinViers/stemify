package com.stemify.mobile.connect

import android.os.SystemClock
import com.stemify.mobile.engine.CrossfadeMode
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.eq.EqState
import com.stemify.mobile.playback.Controls
import com.stemify.mobile.playback.QueueItem
import com.stemify.mobile.playback.RepeatMode
import com.stemify.mobile.playback.SourceKind
import com.stemify.mobile.playback.StemSetting
import com.stemify.mobile.playback.Waveform
import com.stemify.mobile.plex.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

data class RemoteCrossfade(val mode: CrossfadeMode = CrossfadeMode.Off, val seconds: Int = 6, val albums: Boolean = false)

/**
 * Another device's player, as the screens see a player ([Controls]), while Stemify Connect
 * controls it. Its state comes from the receiver's snapshots; commands go back with sequence
 * numbers. Edits show at once, and snapshots that predate an edit of the same kind are ignored
 * until the receiver catches up, so a fader doesn't jump back mid-drag.
 *
 * Queue positions here are within the window of the queue the receiver sends (docs/CONNECT.md);
 * commands add the window's offset back.
 */
class RemotePlayer(
    private val link: Link,
    val device: NearbyDevice,
    val caps: Set<String>,
    private val eq: EqController,
    private val scope: CoroutineScope,
) : Controls {
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    override val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    override val index: StateFlow<Int> = _index.asStateFlow()
    private val _current = MutableStateFlow<QueueItem?>(null)
    override val current: StateFlow<QueueItem?> = _current.asStateFlow()
    private val _playWhenReady = MutableStateFlow(false)
    override val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()
    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    override val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(RepeatMode.Off)
    override val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()
    private val _source = MutableStateFlow(SourceKind.None)
    override val source: StateFlow<SourceKind> = _source.asStateFlow()
    private val _stemNames = MutableStateFlow<List<String>>(emptyList())
    override val stemNames: StateFlow<List<String>> = _stemNames.asStateFlow()
    private val _mix = MutableStateFlow<Map<String, StemSetting>>(emptyMap())
    override val mix: StateFlow<Map<String, StemSetting>> = _mix.asStateFlow()
    private val _master = MutableStateFlow(0.85f)
    override val master: StateFlow<Float> = _master.asStateFlow()
    private val _waveform = MutableStateFlow<Waveform?>(null)
    override val waveform: StateFlow<Waveform?> = _waveform.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _keepMix = MutableStateFlow(true)
    val keepMix: StateFlow<Boolean> = _keepMix.asStateFlow()
    private val _crossfade = MutableStateFlow(RemoteCrossfade())
    val crossfade: StateFlow<RemoteCrossfade> = _crossfade.asStateFlow()
    /** "FLAC", "OPUS 256", or "" for an original file. */
    private val _format = MutableStateFlow("")
    val format: StateFlow<String> = _format.asStateFlow()

    private var seq = 0L
    private val pending = HashMap<String, Long>()
    private var offset = 0
    private var absIndex = -1
    private var posMs = 0L
    private var posAt = SystemClock.elapsedRealtime()
    private var levels = FloatArray(0)
    private var levelsAt = 0L
    /** Slider drags: the latest value per key goes out every [THROTTLE_MS], and before any other command. */
    private val throttled = LinkedHashMap<String, Pair<String, JsonObjectBuilder.() -> Unit>>()
    private var throttleJob: Job? = null
    private val wireEq = WireEq()
    private var eqAttached = false
    private var eqOut: EqState? = null
    private var eqJob: Job? = null

    // ---- sending ---------------------------------------------------------------------------------

    private fun cmd(op: String, args: JsonObjectBuilder.() -> Unit = {}) {
        flushThrottled()
        send(op, args)
    }

    private fun send(op: String, args: JsonObjectBuilder.() -> Unit) {
        seq++
        pending[groupOf(op)] = seq
        link.send(buildJsonObject {
            put("t", "cmd")
            put("op", op)
            put("seq", seq)
            args(this)
        })
    }

    private fun throttle(key: String, op: String, args: JsonObjectBuilder.() -> Unit) {
        pending[groupOf(op)] = seq + 1  // newer than anything sent so far, until it goes
        throttled[key] = op to args
        if (throttleJob?.isActive != true) throttleJob = scope.launch {
            delay(THROTTLE_MS)
            flushThrottled()
        }
    }

    private fun flushThrottled() {
        if (throttled.isEmpty()) return
        val all = throttled.values.toList()
        throttled.clear()
        all.forEach { (op, args) -> send(op, args) }
    }

    private fun fresh(group: String, ack: Long) = ack >= (pending[group] ?: 0L)

    private fun now() = SystemClock.elapsedRealtime()

    // ---- receiving -------------------------------------------------------------------------------

    fun handle(m: JsonObject) {
        when (m.str("t")) {
            "state" -> onState(m)
            "queue" -> onQueue(m)
            "wave" -> onWave(m)
            "levels" -> {
                levels = m.arr("v").orEmpty().map { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0).toFloat() }.toFloatArray()
                levelsAt = now()
            }
            "eq" -> onEq(m)
        }
    }

    private fun onState(m: JsonObject) {
        val ack = m.long("ack") ?: 0L
        if (fresh("queue", ack)) {
            m.int("index")?.let { absIndex = it; _index.value = it - offset }
            m.bool("shuffle")?.let { _shuffle.value = it }
        }
        _current.value = WireTrack.decode(m["track"])?.let { t ->
            _queue.value.getOrNull(_index.value)?.takeIf { it.track.ratingKey == t.ratingKey } ?: QueueItem(-1L, t)
        }
        _durationMs.value = m.long("durationMs") ?: 0L
        _buffering.value = m.bool("buffering") == true
        if (fresh("play", ack)) _playWhenReady.value = m.bool("playing") == true
        if (fresh("pos", ack) && fresh("play", ack)) {
            posMs = m.long("positionMs") ?: 0L
            posAt = now()
        }
        if (fresh("repeat", ack)) _repeat.value = RepeatMode.entries.firstOrNull { it.name.equals(m.str("repeat"), ignoreCase = true) } ?: RepeatMode.Off
        _source.value = SourceKind.entries.firstOrNull { it.name.equals(m.str("source"), ignoreCase = true) } ?: SourceKind.None
        _stemNames.value = m.arr("stems").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (fresh("mix", ack)) _mix.value = WireMix.decode(m.obj("mix"))
        if (fresh("master", ack)) m.num("master")?.let { _master.value = it.toFloat() }
        if (fresh("settings", ack)) {
            _keepMix.value = m.bool("keepMix") ?: true
            m.obj("crossfade")?.let { c ->
                _crossfade.value = RemoteCrossfade(
                    CrossfadeMode.entries.firstOrNull { it.name.equals(c.str("mode"), ignoreCase = true) } ?: CrossfadeMode.Off,
                    c.int("seconds") ?: 6, c.bool("albums") == true,
                )
            }
        }
        _format.value = m.str("format") ?: ""
    }

    private fun onQueue(m: JsonObject) {
        if (!fresh("queue", m.long("ack") ?: 0L)) return
        offset = m.int("offset") ?: 0
        absIndex = m.int("index") ?: absIndex
        _queue.value = m.arr("items").orEmpty().mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            val t = WireTrack.decode(o["track"]) ?: return@mapIndexedNotNull null
            QueueItem(o.long("uid") ?: (offset + i).toLong(), t)
        }
        _index.value = absIndex - offset
        val cur = _current.value
        if (cur != null) _queue.value.getOrNull(_index.value)?.takeIf { it.track.ratingKey == cur.track.ratingKey }?.let { _current.value = it }
    }

    private fun onWave(m: JsonObject) {
        fun floats(e: kotlinx.serialization.json.JsonElement?) =
            (e as? kotlinx.serialization.json.JsonArray)?.map { ((it as? JsonPrimitive)?.doubleOrNull ?: 0.0).toFloat() }?.toFloatArray()
        val stems = m.obj("stems")?.mapValues { (_, v) -> floats(v) ?: FloatArray(0) }
        val db = floats(m["db"])
        _waveform.value = if (stems == null && db == null) null else Waveform(stems, db)
    }

    private fun onEq(m: JsonObject) {
        if (!fresh("eq", m.long("ack") ?: 0L) || eqOut != null) return
        val state = wireEq.decode(m.obj("state") ?: return) ?: return
        val device = wireEq.deviceFrom(m.obj("device"))
        if (!eqAttached) {
            eqAttached = true
            eq.attachRemote(state, device) { s -> sendEq(s) }
        } else eq.showRemote(state, device)
    }

    /** The whole EQ goes back a moment after the last edit, so a slider drag is a few messages, not hundreds. */
    private fun sendEq(s: EqState) {
        eqOut = s
        pending["eq"] = seq + 1
        eqJob?.cancel()
        eqJob = scope.launch {
            delay(EQ_DEBOUNCE_MS)
            val out = eqOut ?: return@launch
            eqOut = null
            cmd("eqState") { put("state", wireEq.encode(out)) }
        }
    }

    fun dispose() {
        flushThrottled()
        eqJob?.cancel()
        eqOut?.let { s -> eqOut = null; cmd("eqState") { put("state", wireEq.encode(s)) } }
        throttleJob?.cancel()
        if (eqAttached) eq.detachRemote()
        eqAttached = false
    }

    // ---- Controls ----------------------------------------------------------------------------------

    override fun positionMs(): Long {
        val pos = posMs + if (_playWhenReady.value && !_buffering.value) now() - posAt else 0L
        val d = _durationMs.value
        return if (d > 0) pos.coerceIn(0L, d) else pos.coerceAtLeast(0L)
    }

    override fun levelsNow(out: FloatArray): Boolean {
        if (!_playWhenReady.value || now() - levelsAt > 400) return false
        for (k in out.indices) out[k] = levels.getOrElse(k) { 0f }
        return true
    }

    override fun playTracks(tracks: List<Track>, start: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        cmd("playTracks") {
            put("tracks", WireTrack.list(tracks))
            put("start", if (shuffle && start <= 0) -1 else start)
            put("shuffle", shuffle)
        }
    }

    /** This phone's queue, playing on the other device from [positionMs], with the same mix. */
    fun transfer(tracks: List<Track>, start: Int, positionMs: Long, mix: Map<String, StemSetting>, paused: Boolean) {
        cmd("playTracks") {
            put("tracks", WireTrack.list(tracks))
            put("start", start)
            put("shuffle", false)
            put("positionMs", positionMs)
            put("mix", WireMix.encode(mix))
            put("paused", paused)
        }
    }

    override fun playNext(tracks: List<Track>) = cmd("playNext") { put("tracks", WireTrack.list(tracks)) }
    override fun addToQueue(tracks: List<Track>) = cmd("addToQueue") { put("tracks", WireTrack.list(tracks)) }

    override fun removeAt(i: Int) {
        val q = _queue.value
        val cur = _index.value
        if (i in q.indices && i != cur) {
            _queue.value = q.filterIndexed { k, _ -> k != i }
            if (i < cur) { _index.value = cur - 1; absIndex-- }
        }
        cmd("removeAt") { put("i", i + offset) }
    }

    override fun move(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        q.add(to, q.removeAt(from))
        _queue.value = q
        val idx = _index.value
        val moved = when {
            from == idx -> to
            from < idx && to >= idx -> idx - 1
            from > idx && to <= idx -> idx + 1
            else -> idx
        }
        _index.value = moved
        absIndex = moved + offset
        cmd("move") { put("from", from + offset); put("to", to + offset) }
    }

    override fun clearUpNext() {
        val idx = _index.value
        if (idx >= 0) _queue.value = _queue.value.take(idx + 1)
        cmd("clearUpNext")
    }

    override fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        cmd("toggleShuffle")
    }

    override fun cycleRepeat() {
        _repeat.value = RepeatMode.entries[(_repeat.value.ordinal + 1) % RepeatMode.entries.size]
        cmd("cycleRepeat")
    }

    private fun setPlaying(on: Boolean) {
        posMs = positionMs()
        posAt = now()
        _playWhenReady.value = on
    }

    override fun play() { setPlaying(true); cmd("play") }
    override fun pause() { setPlaying(false); cmd("pause") }
    override fun togglePlay() = if (_playWhenReady.value) pause() else play()
    override fun next() = cmd("next")
    override fun previous() = cmd("previous")
    override fun skipTo(i: Int, positionMs: Long) = cmd("skipTo") { put("i", i + offset) }

    override fun skipBy(deltaMs: Long) {
        val to = positionMs() + deltaMs
        if (_durationMs.value <= 0 || to < _durationMs.value) {
            posMs = to.coerceAtLeast(0L)
            posAt = now()
        }
        cmd("skipBy") { put("ms", deltaMs) }
    }

    override fun seekTo(ms: Long) {
        posMs = ms.coerceAtLeast(0L)
        posAt = now()
        cmd("seek") { put("ms", ms) }
    }

    private fun editMix(name: String, f: (StemSetting) -> StemSetting) {
        _mix.value = _mix.value + (name to f(_mix.value[name] ?: StemSetting()))
    }

    override fun setStemLevel(name: String, level: Float) {
        val v = level.coerceIn(0f, 1f)
        editMix(name) { it.copy(level = v) }
        throttle("level:$name", "setLevel") { put("stem", name); put("level", v) }
    }

    override fun toggleMute(name: String) {
        editMix(name) { it.copy(muted = !it.muted) }
        cmd("toggleMute") { put("stem", name) }
    }

    override fun toggleSolo(name: String) {
        editMix(name) { it.copy(solo = !it.solo) }
        cmd("toggleSolo") { put("stem", name) }
    }

    override fun applyMix(m: Map<String, StemSetting>) {
        _mix.value = m
        cmd("setMix") { put("mix", WireMix.encode(m)) }
    }

    override fun setMaster(v: Float) {
        val x = v.coerceIn(0f, 1f)
        _master.value = x
        throttle("master", "setMaster") { put("v", x) }
    }

    fun setKeepMix(on: Boolean) {
        _keepMix.value = on
        cmd("setKeepMix") { put("on", on) }
    }

    fun setCrossfade(c: RemoteCrossfade) {
        _crossfade.value = c
        cmd("setCrossfade") {
            put("mode", c.mode.name.lowercase())
            put("seconds", c.seconds)
            put("albums", c.albums)
        }
    }

    private companion object {
        const val THROTTLE_MS = 40L
        const val EQ_DEBOUNCE_MS = 60L

        fun groupOf(op: String) = when (op) {
            "setLevel", "toggleMute", "toggleSolo", "setMix" -> "mix"
            "playTracks", "playNext", "addToQueue", "removeAt", "move", "clearUpNext", "toggleShuffle", "skipTo" -> "queue"
            "setMaster" -> "master"
            "seek", "skipBy" -> "pos"
            "toggle", "play", "pause" -> "play"
            "setKeepMix", "setCrossfade" -> "settings"
            "cycleRepeat", "setRepeat" -> "repeat"
            "eqState" -> "eq"
            else -> "state"
        }
    }
}
