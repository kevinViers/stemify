package com.stemify.mobile.connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import com.stemify.mobile.core.AppJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Classic Bluetooth (RFCOMM) for Connect, with paired devices only: pairing is the trust. All of
 * it needs the Nearby devices permission (BLUETOOTH_CONNECT); without it, it quietly does nothing.
 * Everything that touches a socket blocks, so call it from an IO thread.
 */
class Bluetooth(private val context: Context) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun permitted() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun usable() = permitted() && adapter?.isEnabled == true

    /** The service other devices open by [Wire.BT_UUID]; null when Bluetooth is off or not allowed. */
    @SuppressLint("MissingPermission")
    fun listen(): BluetoothServerSocket? {
        if (!usable()) return null
        return runCatching { adapter?.listenUsingRfcommWithServiceRecord("Stemify Connect", Wire.BT_UUID) }.getOrNull()
    }

    /** Opens the service on a paired device. Blocks for up to ~12 s; [cancel] gets a handle to give up early. */
    @SuppressLint("MissingPermission")
    fun open(address: String, cancel: (BluetoothSocket) -> Unit = {}): BluetoothSocket {
        val a = adapter ?: throw IOException("No Bluetooth")
        if (!usable()) throw IOException("Bluetooth is off")
        val sock = a.getRemoteDevice(address).createRfcommSocketToServiceRecord(Wire.BT_UUID)
        cancel(sock)
        runCatching { a.cancelDiscovery() }
        sock.connect()
        return sock
    }

    /** Paired phones and computers (headphones, cars and watches can't run Stemify). */
    @SuppressLint("MissingPermission")
    fun candidates(): List<Pair<String, String>> {
        if (!usable()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        return devices.filter { d ->
            when (d.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER,
                BluetoothClass.Device.Major.UNCATEGORIZED, null -> true
                else -> false
            }
        }.map { d -> d.address to (runCatching { d.name }.getOrNull() ?: d.address) }
    }

    /**
     * Probes each paired candidate for the Connect service with a hello (probe: true), one at a
     * time, reporting the ones that answer.
     */
    suspend fun scan(me: Identity, found: (NearbyDevice) -> Unit) = coroutineScope {
        for ((address, name) in candidates()) {
            ensureActive()
            var sock: BluetoothSocket? = null
            val killer = launch { delay(12_000); runCatching { sock?.close() } }
            try {
                val s = open(address) { sock = it }
                val hello = buildJsonObject {
                    put("t", "hello"); put("v", Wire.VERSION); put("id", me.id); put("name", me.name); put("app", me.app); put("probe", true)
                }
                s.outputStream.write((hello.toString() + "\n").toByteArray(Charsets.UTF_8))
                s.outputStream.flush()
                val line = s.inputStream.bufferedReader(Charsets.UTF_8).readLine()
                val msg = line?.let { runCatching { AppJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                val id = msg?.str("id")
                if (msg != null && id != null && id != me.id && msg.str("t") in setOf("welcome", "pending")) {
                    found(NearbyDevice(id, msg.str("name") ?: name, msg.str("app") ?: "", Transport.Bt, address = address))
                }
            } catch (_: Exception) {
            } finally {
                killer.cancel()
                runCatching { sock?.close() }
            }
        }
    }
}
