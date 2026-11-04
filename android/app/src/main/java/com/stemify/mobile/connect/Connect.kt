package com.stemify.mobile.connect

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.stemify.mobile.core.Settings
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.playback.Controls
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import kotlin.math.min

/**
 * Stemify Connect on this phone (docs/CONNECT.md): the [receiver] that lets nearby devices
 * control it, and the controller side: finding devices, connecting, and handing the screens a
 * [RemotePlayer] ([controls]) while one is picked. Main thread only.
 */
class Connect(
    context: Context,
    private val settings: Settings,
    private val pm: PlaybackManager,
    private val eq: EqController,
    plex: PlexClient,
    stems: StemServer,
    private val scope: CoroutineScope,
) {
    enum class State { Idle, Connecting, Waiting, Connected }

    val identity = Identity(settings.clientId, deviceName(context))
    val bluetooth = Bluetooth(context)
    private val beacon = Beacon(context, identity, scope)
    val receiver = Receiver(settings, pm, eq, stems, plex, identity, beacon, bluetooth, scope)

    private val _target = MutableStateFlow<NearbyDevice?>(null)
    /** The device picked, while connecting or connected. */
    val target: StateFlow<NearbyDevice?> = _target.asStateFlow()
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _remote = MutableStateFlow<RemotePlayer?>(null)
    /** The other device's player while connected; the screens use it in place of this phone's. */
    val remote: StateFlow<RemotePlayer?> = _remote.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _bt = MutableStateFlow<Map<String, NearbyDevice>>(emptyMap())
    private val _btScanning = MutableStateFlow(false)
    val btScanning: StateFlow<Boolean> = _btScanning.asStateFlow()

    /** Every Stemify nearby: Wi-Fi ones from the beacon, Bluetooth ones from the last scan. */
    val devices: StateFlow<List<NearbyDevice>> = combine(beacon.devices, _bt, _target) { wifi, bt, target ->
        val all = LinkedHashMap<String, NearbyDevice>()
        bt.values.forEach { all[it.id] = it }
        wifi.values.forEach { all[it.id] = it }  // Wi-Fi wins: it's faster
        if (target != null && target.id !in all) all[target.id] = target
        all.values.sortedBy { it.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Set by the activity while it's around: asks for the Nearby devices (Bluetooth) permission. */
    var askBluetoothPermission: (() -> Unit)? = null

    val controls: Controls get() = _remote.value ?: pm

    private var link: Link? = null
    private var connectJob: Job? = null
    private var probeJob: Job? = null
    private var btScanJob: Job? = null
    private var scanRefs = 0

    fun start() {
        beacon.start()
        receiver.start()
    }

    fun onBluetoothPermission() {
        receiver.restartBluetooth()
        if (scanRefs > 0) scanBluetooth()
    }

    // ---- finding devices -------------------------------------------------------------------

    /** The device picker is open (on) or closed: look for devices meanwhile. */
    fun scan(on: Boolean) {
        scanRefs = max(0, scanRefs + if (on) 1 else -1)
        beacon.scanning(scanRefs > 0)
        if (!on) return
        if (settings.connectBluetooth.value) {
            if (bluetooth.permitted()) scanBluetooth() else askBluetoothPermission?.invoke()
        }
        if (probeJob?.isActive != true) probeJob = scope.launch {
            while (scanRefs > 0) {
                delay(4000)
                if (scanRefs > 0) beacon.probe()
            }
        }
    }

    private fun scanBluetooth() {
        if (btScanJob?.isActive == true || !bluetooth.usable()) return
        _bt.value = _bt.value.filterKeys { it == _target.value?.id }
        _btScanning.value = true
        btScanJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bluetooth.scan(identity) { d -> scope.launch { _bt.value = _bt.value + (d.id to d) } }
                }
            } finally {
                _btScanning.value = false
            }
        }
    }

    // ---- connecting ------------------------------------------------------------------------

    fun connectTo(d: NearbyDevice) {
        if (_target.value?.id == d.id && _state.value != State.Idle) return
        leave()
        _target.value = d
        _state.value = State.Connecting
        connectJob = scope.launch {
            val l = try {
                open(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Couldn't reach ${d.name}${if (d.transport == Transport.Bt) " over Bluetooth" else ""}")
                return@launch
            }
            link = l
            l.onMessage = { m -> onMessage(l, m) }
            l.onClosed = { onClosed(l) }
            l.start()
            l.send(buildJsonObject {
                put("t", "hello"); put("v", Wire.VERSION); put("id", identity.id); put("name", identity.name); put("app", identity.app)
            })
            delay(CONNECT_TIMEOUT_MS)
            if (link === l && _state.value == State.Connecting) fail("${d.name} didn't answer")
        }
    }

    /** Opens the socket; closes it again if the attempt was abandoned meanwhile. */
    private suspend fun open(d: NearbyDevice): Link = withContext(Dispatchers.IO) {
        when (d.transport) {
            Transport.Wifi -> {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(d.host, d.port), 8000)
                    s.tcpNoDelay = true
                    Link(s.getInputStream(), s.getOutputStream(), s, Transport.Wifi, scope, ping = true)
                } catch (e: Exception) {
                    runCatching { s.close() }
                    throw e
                }
            }
            Transport.Bt -> {
                var sock: BluetoothSocket? = null
                val killer = launch { delay(CONNECT_TIMEOUT_MS); runCatching { sock?.close() } }
                try {
                    val s = bluetooth.open(d.address ?: throw IllegalStateException("no address")) { sock = it }
                    Link(s.inputStream, s.outputStream, s, Transport.Bt, scope, ping = true)
                } catch (e: Exception) {
                    runCatching { sock?.close() }
                    throw e
                } finally {
                    killer.cancel()
                }
            }
        }
    }

    private fun onMessage(l: Link, m: JsonObject) {
        if (link !== l) return
        val d = _target.value ?: return
        when (m.str("t")) {
            "welcome" -> if (_state.value != State.Connected) {
                val caps = (m["caps"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                val named = d.copy(name = m.str("name") ?: d.name, app = m.str("app") ?: d.app)
                _target.value = named
                if (pm.playWhenReady.value) pm.pause()  // one device plays at a time
                _remote.value = RemotePlayer(l, named, caps, eq, scope)
                _state.value = State.Connected
                l.send(buildJsonObject { put("t", "meters"); put("on", true) })
            }
            "pending" -> _state.value = State.Waiting
            "denied" -> fail("${d.name} didn't allow it")
            "bye" -> fail(m.str("reason") ?: "${d.name} ended the connection")
            "toast" -> m.str("text")?.let { _messages.tryEmit(it) }
            else -> _remote.value?.handle(m)
        }
    }

    private fun onClosed(l: Link) {
        if (link !== l) return
        link = null
        _target.value?.let { if (_state.value == State.Connected) _messages.tryEmit("Disconnected from ${it.name}") }
        reset()
    }

    private fun fail(why: String) {
        if (_target.value == null) return
        _messages.tryEmit(why)
        leave()
    }

    /** Back to this phone. */
    fun leave() {
        connectJob?.cancel()
        connectJob = null
        val l = link
        link = null
        _remote.value?.dispose()
        l?.let {
            it.onClosed = {}
            it.close("Controller left")
        }
        reset()
    }

    private fun reset() {
        _remote.value?.dispose()
        _remote.value = null
        _target.value = null
        _state.value = State.Idle
    }

    /** Plays this phone's queue on the device being controlled, from where it is now, with the same mix. */
    fun transfer() {
        val rp = _remote.value ?: return
        val q = pm.queue.value
        val i = pm.index.value
        if (i !in q.indices) return
        val lo = max(0, i - Wire.QUEUE_BEHIND)
        val tracks = q.subList(lo, min(q.size, lo + TRANSFER_MAX)).map { it.track }
        rp.transfer(tracks, i - lo, pm.positionMs(), pm.mix.value, paused = false)
        _messages.tryEmit("Playing on ${rp.device.name}")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 14_000L
        const val TRANSFER_MAX = 2000

        fun deviceName(context: Context): String =
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
