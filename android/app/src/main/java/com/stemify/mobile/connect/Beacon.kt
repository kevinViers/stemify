package com.stemify.mobile.connect

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.stemify.mobile.core.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Finding Stemify on the local network: a UDP broadcast beacon (docs/CONNECT.md). Announces this
 * phone while its receiver has a port, and keeps the list of everyone else heard.
 */
class Beacon(context: Context, private val identity: Identity, private val scope: CoroutineScope) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val _devices = MutableStateFlow<Map<String, NearbyDevice>>(emptyMap())
    val devices: StateFlow<Map<String, NearbyDevice>> = _devices.asStateFlow()
    @Volatile var port = 0
        set(v) {
            field = v
            if (v > 0) scope.launch { announce() }
        }
    @Volatile private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                val s = runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(Wire.BEACON_PORT))
                    }
                }.getOrNull()
                if (s == null) { delay(30_000); continue }
                socket = s
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val msg = runCatching { AppJson.parseToJsonElement(String(p.data, 0, p.length, Charsets.UTF_8)).jsonObject }.getOrNull() ?: continue
                        withContext(Dispatchers.Main) { heard(msg, p.address) }
                    }
                } catch (_: Exception) {
                    runCatching { s.close() }
                    socket = null
                    delay(5000)
                }
            }
        }
        scope.launch {
            while (true) {
                delay(Wire.BEACON_EVERY_MS)
                if (port > 0) announce()
                prune()
            }
        }
    }

    /** While the device picker is open: ask everyone to say hello now, and hold Wi-Fi's multicast lock so their replies get through. */
    fun scanning(on: Boolean) {
        if (on) {
            if (lock == null) lock = runCatching { wifi?.createMulticastLock("stemify-connect")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            scope.launch { send(buildJsonObject { put("stemify", Wire.VERSION); put("probe", true) }) }
        } else {
            runCatching { lock?.release() }
            lock = null
        }
    }

    fun probe() = scope.launch { send(buildJsonObject { put("stemify", Wire.VERSION); put("probe", true) }) }

    private suspend fun announce() = send(buildJsonObject {
        put("stemify", Wire.VERSION)
        put("id", identity.id)
        put("name", identity.name)
        put("app", identity.app)
        put("port", port)
    })

    private suspend fun send(msg: JsonObject) = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext
        val data = msg.toString().toByteArray(Charsets.UTF_8)
        for (addr in targets()) runCatching { s.send(DatagramPacket(data, data.size, addr, Wire.BEACON_PORT)) }
    }

    private fun targets(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (a in iface.interfaceAddresses) if (a.address is Inet4Address) a.broadcast?.let { out += it }
            }
        }
        return out
    }

    private fun heard(msg: JsonObject, from: InetAddress) {
        if (msg.int("stemify") != Wire.VERSION) return
        if (msg.bool("probe") == true) {
            if (port > 0) scope.launch { delay((20L..250L).random()); announce() }
            return
        }
        val id = msg.str("id") ?: return
        val theirs = msg.int("port") ?: return
        if (id == identity.id || theirs <= 0) return
        val d = NearbyDevice(id, msg.str("name") ?: "Stemify", msg.str("app") ?: "", Transport.Wifi,
            host = from.hostAddress, port = theirs, seenAt = SystemClock.elapsedRealtime())
        _devices.value = _devices.value + (id to d)
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        val keep = _devices.value.filterValues { now - it.seenAt <= Wire.DEVICE_TTL_MS }
        if (keep.size != _devices.value.size) _devices.value = keep
    }
}
