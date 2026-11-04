package com.stemify.mobile.connect

import android.bluetooth.BluetoothServerSocket
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.engine.CrossfadeMode
import com.stemify.mobile.engine.MeterHistory
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.playback.QueueItem
import com.stemify.mobile.playback.RepeatMode
import com.stemify.mobile.playback.SourceKind
import com.stemify.mobile.playback.Waveform
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.Track
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ServerSocket
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The receiving end of Stemify Connect: lets nearby controllers drive this phone's player, EQ and
 * crossfade. Wi-Fi controllers are approved once here (and remembered); Bluetooth ones are
 * already paired, so they're trusted. Main thread only, but for the sockets' own threads.
 */
class Receiver(
    private val settings: Settings,
    private val pm: PlaybackManager,
    private val eq: EqController,
    private val stems: StemServer,
    private val plex: PlexClient,
    private val identity: Identity,
    private val beacon: Beacon,
    private val bluetooth: Bluetooth,
    private val scope: CoroutineScope,
) {
    data class Peer(val id: String, val name: String, val app: String)
    data class Controller(val peer: Peer, val transport: Transport)

    private class Session(val link: Link) {
        var peer: Peer? = null
        var approved = false
        var meters = false
        var ack = 0L
    }

    private val sessions = mutableListOf<Session>()
    private val _pending = MutableStateFlow<List<Peer>>(emptyList())
    /** Wi-Fi devices waiting for this phone's answer, oldest first. */
    val pending: StateFlow<List<Peer>> = _pending.asStateFlow()
    private val _controllers = MutableStateFlow<List<Controller>>(emptyList())
    val controllers: StateFlow<List<Controller>> = _controllers.asStateFlow()
    private val _allowed = MutableStateFlow(readAllowed())
    /** Approved Wi-Fi controllers: id to name. */
    val allowed: StateFlow<Map<String, String>> = _allowed.asStateFlow()

    private val wireEq = WireEq()
    @Volatile private var tcp: ServerSocket? = null
    private var tcpJob: Job? = null
    @Volatile private var btServer: BluetoothServerSocket? = null
    private var btJob: Job? = null
    private var dirty = mutableSetOf<String>()
    private val force = mutableSetOf<String>()
    private var flushJob: Job? = null
    private var meterJob: Job? = null
    private var lastQueue: Any? = null
    private var lastWave: Waveform? = null

    fun start() {
        watch(pm.queue, "queue")
        watch(pm.index, "queue")
        for (f in listOf(pm.current, pm.playWhenReady, pm.buffering, pm.shuffle, pm.repeat, pm.source, pm.stemNames, pm.mix,
            pm.master, pm.durationMs, settings.keepStemMix.flow, settings.crossfade.flow, settings.crossfadeSeconds.flow,
            settings.crossfadeAlbums.flow, settings.stemQuality.flow)) watch(f, "state")
        watch(pm.waveform, "wave")
        watch(eq.localVersion, "eq")
        scope.launch { pm.messages.collect { text -> broadcast(buildJsonObject { put("t", "toast"); put("text", text) }) } }
        scope.launch {
            while (true) {
                delay(2000)
                if (pm.playWhenReady.value) mark("state")  // corrects the controllers' clocks
            }
        }
        scope.launch { settings.connectEnabled.flow.collect { applySettings() } }
        scope.launch { settings.connectBluetooth.flow.drop(1).collect { applySettings() } }
    }

    private fun watch(flow: Flow<*>, what: String) = scope.launch { flow.drop(1).collect { mark(what) } }

    // ---- listening ---------------------------------------------------------------------------

    private fun applySettings() {
        val on = settings.connectEnabled.value
        if (on) startTcp() else {
            stopTcp()
            sessions.toList().forEach { it.link.close("Nearby control was turned off") }
        }
        if (on && settings.connectBluetooth.value) startBluetooth() else {
            stopBluetooth()
            sessions.filter { it.link.transport == Transport.Bt }.forEach { it.link.close("Bluetooth control was turned off") }
        }
    }

    private fun startTcp() {
        if (tcpJob?.isActive == true) return
        tcpJob = scope.launch(Dispatchers.IO) {
            val server = Wire.TCP_PORTS.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return@launch
            tcp = server
            withContext(Dispatchers.Main) { beacon.port = server.localPort }
            try {
                while (isActive) {
                    val sock = server.accept()
                    sock.tcpNoDelay = true
                    withContext(Dispatchers.Main) {
                        add(Link(sock.getInputStream(), sock.getOutputStream(), sock, Transport.Wifi, scope, ping = false))
                    }
                }
            } catch (_: IOException) {
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private fun stopTcp() {
        beacon.port = 0
        runCatching { tcp?.close() }
        tcp = null
        tcpJob?.cancel()
        tcpJob = null
    }

    private fun startBluetooth() {
        if (btJob?.isActive == true) return
        btJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val server = bluetooth.listen()
                if (server == null) {
                    delay(30_000)
                    continue
                }
                btServer = server
                try {
                    while (true) {
                        val sock = server.accept()
                        withContext(Dispatchers.Main) { add(Link(sock.inputStream, sock.outputStream, sock, Transport.Bt, scope, ping = false)) }
                    }
                } catch (_: IOException) {
                } finally {
                    runCatching { server.close() }
                    btServer = null
                }
                delay(3000)
            }
        }
    }

    private fun stopBluetooth() {
        btJob?.cancel()
        btJob = null
        runCatching { btServer?.close() }
        btServer = null
    }

    /** The Nearby devices permission just arrived: listen over Bluetooth now rather than at the next retry. */
    fun restartBluetooth() {
        stopBluetooth()
        applySettings()
    }

    // ---- approvals ---------------------------------------------------------------------------

    fun approve(id: String) {
        var name: String? = null
        sessions.filter { it.peer?.id == id && !it.approved }.forEach {
            name = it.peer?.name
            welcome(it)
        }
        name?.let { saveAllowed(_allowed.value + (id to it)) }
        refresh()
    }

    fun deny(id: String) {
        sessions.filter { it.peer?.id == id && !it.approved }.forEach {
            it.link.send(buildJsonObject { put("t", "denied") })
            it.link.close()
        }
        refresh()
    }

    /** Takes back a Wi-Fi device's permission, dropping it if it's connected. */
    fun forget(id: String) {
        saveAllowed(_allowed.value - id)
        sessions.filter { it.peer?.id == id && it.link.transport == Transport.Wifi }.forEach { it.link.close("Permission withdrawn") }
    }

    private fun readAllowed(): Map<String, String> = runCatching {
        AppJson.parseToJsonElement(settings.connectAllowed.value).jsonObject.mapValues { (it.value as? JsonPrimitive)?.contentOrNull ?: "" }
    }.getOrDefault(emptyMap())

    private fun saveAllowed(m: Map<String, String>) {
        _allowed.value = m
        settings.connectAllowed.value = buildJsonObject { m.forEach { (k, v) -> put(k, v) } }.toString()
    }

    private fun refresh() {
        _pending.value = sessions.filter { !it.approved }.mapNotNull { it.peer }.distinctBy { it.id }
        _controllers.value = sessions.filter { it.approved }.mapNotNull { s -> s.peer?.let { Controller(it, s.link.transport) } }
    }

    // ---- sessions ----------------------------------------------------------------------------

    private fun add(link: Link) {
        val s = Session(link)
        sessions += s
        link.onMessage = { m -> onMessage(s, m) }
        link.onClosed = { drop(s) }
        link.start()
        scope.launch {
            delay(10_000)
            if (link.isOpen && s.peer == null) link.close()
        }
    }

    private fun drop(s: Session) {
        if (sessions.remove(s)) {
            refresh()
            updateMeters()
        }
    }

    private fun hello(t: String) = buildJsonObject {
        put("t", t)
        put("v", Wire.VERSION)
        put("id", identity.id)
        put("name", identity.name)
        put("app", identity.app)
        put("caps", buildJsonArray { listOf("eq", "crossfade", "keepMix").forEach { add(JsonPrimitive(it)) } })
    }

    private fun onMessage(s: Session, m: JsonObject) {
        val t = m.str("t")
        if (s.peer == null) {
            val id = m.str("id")
            if (t != "hello" || id == null) return s.link.close()
            s.peer = Peer(id, (m.str("name") ?: "A device").take(80), m.str("app") ?: "")
            val trusted = s.link.transport == Transport.Bt || id in _allowed.value
            when {
                m.bool("probe") == true -> {
                    s.link.send(hello(if (trusted) "welcome" else "pending"))
                    s.link.close()
                }
                trusted -> welcome(s)
                else -> {
                    s.link.send(hello("pending"))
                    refresh()
                }
            }
            return
        }
        if (!s.approved) return
        when (t) {
            "cmd" -> {
                val op = m.str("op") ?: return
                try {
                    run(op, m)
                } catch (e: Exception) {
                    s.link.send(buildJsonObject { put("t", "toast"); put("text", "Couldn't do that: ${e.message ?: op}") })
                }
                s.ack = max(s.ack, m.long("seq") ?: 0)
                force.add(when (op) {
                    in QUEUE_OPS -> "queue"
                    "eqState" -> "eq"
                    else -> "state"
                })
                mark("state")
            }
            "meters" -> {
                s.meters = m.bool("on") == true
                updateMeters()
            }
        }
    }

    private fun welcome(s: Session) {
        s.approved = true
        s.link.send(hello("welcome"))
        s.link.send(stateMsg(s.ack))
        s.link.send(queueMsg(s.ack))
        s.link.send(waveMsg())
        s.link.send(eqMsg(s.ack))
        refresh()
    }

    private fun broadcast(msg: JsonObject) = sessions.filter { it.approved }.forEach { it.link.send(msg) }

    // ---- commands ------------------------------------------------------------------------------

    private fun run(op: String, m: JsonObject) {
        when (op) {
            "play" -> pm.play()
            "pause" -> pm.pause()
            "toggle" -> pm.togglePlay()
            "next" -> pm.next()
            "previous" -> pm.previous()
            "seek" -> pm.seekTo(m.long("ms") ?: 0)
            "skipBy" -> pm.skipBy(m.long("ms") ?: 0)
            "skipTo" -> pm.skipTo(m.int("i") ?: return)
            "playTracks" -> withTracks(m) { playTracks(it, m) }
            "playNext" -> withTracks(m) { pm.playNext(it) }
            "addToQueue" -> withTracks(m) { pm.addToQueue(it) }
            "removeAt" -> pm.removeAt(m.int("i") ?: return)
            "move" -> pm.move(m.int("from") ?: return, m.int("to") ?: return)
            "clearUpNext" -> pm.clearUpNext()
            "toggleShuffle" -> pm.toggleShuffle()
            "cycleRepeat" -> pm.cycleRepeat()
            "setRepeat" -> RepeatMode.entries.firstOrNull { it.name.equals(m.str("mode"), ignoreCase = true) }?.let { pm.setRepeat(it) }
            "setLevel" -> pm.setStemLevel(m.str("stem") ?: return, (m.num("level") ?: return).toFloat())
            "toggleMute" -> pm.toggleMute(m.str("stem") ?: return)
            "toggleSolo" -> pm.toggleSolo(m.str("stem") ?: return)
            "setMix" -> pm.applyMix(WireMix.decode(m.obj("mix")))
            "setMaster" -> pm.setMaster((m.num("v") ?: return).toFloat())
            "setKeepMix" -> {
                settings.keepStemMix.value = m.bool("on") ?: return
            }
            "setCrossfade" -> {
                CrossfadeMode.entries.firstOrNull { it.name.equals(m.str("mode"), ignoreCase = true) }?.let { settings.crossfade.value = it }
                m.int("seconds")?.takeIf { it > 0 }?.let { settings.crossfadeSeconds.value = it.coerceIn(1, 12) }
                m.bool("albums")?.let { settings.crossfadeAlbums.value = it }
            }
            "eqState" -> wireEq.decode(m.obj("state") ?: return)?.let { eq.replaceState(it) }
            else -> throw IllegalArgumentException("unknown command $op")
        }
    }

    /**
     * The tracks in a command. Ones from a computer may lack the Plex part this phone keys its
     * cache by; those are looked up in Plex first (by rating key), then [then] runs.
     */
    private fun withTracks(m: JsonObject, then: (List<Track>) -> Unit) {
        val tracks = m.arr("tracks").orEmpty().mapNotNull { WireTrack.decode(it) }
        if (tracks.isEmpty()) return
        val missing = tracks.filter { it.partId == null && it.stemKey == null }
        if (missing.isEmpty()) return then(tracks)
        scope.launch {
            val found = runCatching { plex.tracks(missing.map { it.ratingKey }) }.getOrDefault(emptyList()).associateBy { it.ratingKey }
            then(tracks.map { t -> found[t.ratingKey]?.copy(playlistItemId = t.playlistItemId) ?: t })
            mark("queue")
        }
    }

    private fun playTracks(tracks: List<Track>, m: JsonObject) {
        val shuffle = m.bool("shuffle") == true
        val start = (m.int("start") ?: 0).coerceIn(0, tracks.lastIndex)
        pm.playTracks(tracks, start, shuffle)
        // Moving playback here from another device: same song, same moment, same mix.
        val pos = m.long("positionMs") ?: 0
        if (pos > 0 && !shuffle) pm.skipTo(start, pos)
        m.obj("mix")?.let { pm.applyMix(WireMix.decode(it)) }
        if (m.bool("paused") == true) pm.pause()
    }

    // ---- outgoing state ------------------------------------------------------------------------

    private fun mark(what: String) {
        if (sessions.none { it.approved }) return
        dirty.add(what)
        if (flushJob?.isActive != true) flushJob = scope.launch {
            delay(60)
            flush()
        }
    }

    private fun flush() {
        val forced = force.toSet()
        val d = dirty + forced
        dirty = mutableSetOf()
        force.clear()
        val live = sessions.filter { it.approved }
        if (live.isEmpty()) return
        if ("queue" in d) {
            val sig = queueSig()
            if (sig != lastQueue || "queue" in forced) {
                lastQueue = sig
                live.forEach { it.link.send(queueMsg(it.ack)) }
            }
        }
        if ("wave" in d && pm.waveform.value !== lastWave) waveMsg().let { w -> live.forEach { it.link.send(w) } }
        if ("eq" in d) live.forEach { it.link.send(eqMsg(it.ack)) }
        if ("state" in d || "queue" in d) live.forEach { it.link.send(stateMsg(it.ack)) }
    }

    private fun format(): String = when (pm.source.value) {
        SourceKind.Stems -> stems.effectiveQuality(settings.stemQuality.value).let { q -> if (q == StemQuality.Lossless) "FLAC" else "OPUS ${q.kbps}" }
        else -> ""
    }

    private fun stateMsg(ack: Long) = buildJsonObject {
        put("t", "state")
        put("ack", ack)
        put("index", pm.index.value)
        put("track", pm.current.value?.let { WireTrack.encode(it.track) } ?: JsonNull)
        put("playing", pm.playWhenReady.value)
        put("buffering", pm.buffering.value)
        put("positionMs", pm.positionMs())
        put("durationMs", pm.durationMs.value)
        put("shuffle", pm.shuffle.value)
        put("repeat", pm.repeat.value.name.lowercase())
        put("source", pm.source.value.name.lowercase())
        put("stems", buildJsonArray { pm.stemNames.value.forEach { add(JsonPrimitive(it)) } })
        put("mix", WireMix.encode(pm.mix.value))
        put("master", pm.master.value)
        put("keepMix", settings.keepStemMix.value)
        put("format", format())
        put("crossfade", buildJsonObject {
            put("mode", settings.crossfade.value.name.lowercase())
            put("seconds", settings.crossfadeSeconds.value)
            put("albums", settings.crossfadeAlbums.value)
        })
    }

    private fun window(): Pair<Int, List<QueueItem>> {
        val q = pm.queue.value
        val i = pm.index.value
        val lo = max(0, i - Wire.QUEUE_BEHIND)
        val hi = min(q.size, max(i, 0) + 1 + Wire.QUEUE_AHEAD)
        return lo to if (lo < hi) q.subList(lo, hi) else emptyList()
    }

    private fun queueSig(): List<Long> {
        val (lo, items) = window()
        return listOf(lo.toLong(), pm.queue.value.size.toLong(), pm.index.value.toLong()) + items.map { it.uid }
    }

    private fun queueMsg(ack: Long) = buildJsonObject {
        val (lo, items) = window()
        put("t", "queue")
        put("ack", ack)
        put("offset", lo)
        put("total", pm.queue.value.size)
        put("index", pm.index.value)
        put("items", buildJsonArray {
            items.forEach { qi -> add(buildJsonObject { put("uid", qi.uid); put("track", WireTrack.encode(qi.track)) }) }
        })
    }

    private fun waveMsg(): JsonObject {
        val w = pm.waveform.value
        lastWave = w
        return buildJsonObject {
            put("t", "wave")
            put("key", pm.current.value?.track?.ratingKey ?: "")
            val st = w?.stems
            if (st != null) {
                put("stems", buildJsonObject {
                    st.forEach { (n, a) -> put(n, buildJsonArray { a.forEach { v -> add(JsonPrimitive(round3(v))) } }) }
                })
            } else put("stems", JsonNull)
            val db = w?.loudnessDb
            if (db != null) put("db", buildJsonArray { db.forEach { v -> add(JsonPrimitive(round3(v))) } }) else put("db", JsonNull)
        }
    }

    private fun eqMsg(ack: Long) = buildJsonObject {
        put("t", "eq")
        put("ack", ack)
        put("state", wireEq.encode(eq.localState))
        put("device", wireEq.deviceJson(eq.localDevice))
    }

    private fun updateMeters() {
        val want = sessions.any { it.approved && it.meters }
        if (want && meterJob == null) {
            meterJob = scope.launch {
                val raw = FloatArray(MeterHistory.MAX_STEMS)
                while (true) {
                    delay(50)
                    if (!pm.playWhenReady.value || !pm.levelsNow(raw)) continue
                    val n = min(pm.stemNames.value.size, raw.size)
                    val msg = buildJsonObject {
                        put("t", "levels")
                        put("v", buildJsonArray { for (k in 0 until n) add(JsonPrimitive(round3(raw[k]))) })
                    }
                    sessions.filter { it.approved && it.meters }.forEach { it.link.send(msg) }
                }
            }
        } else if (!want) {
            meterJob?.cancel()
            meterJob = null
        }
    }

    private companion object {
        val QUEUE_OPS = setOf("playTracks", "playNext", "addToQueue", "removeAt", "move", "clearUpNext", "toggleShuffle", "skipTo")

        fun round3(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else (v * 1000f).roundToInt() / 1000f
    }
}
