package com.stemify.mobile.connect

import android.media.AudioDeviceInfo
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.engine.EQ_BANDS
import com.stemify.mobile.engine.EqChannel
import com.stemify.mobile.engine.EqFilter
import com.stemify.mobile.engine.FilterType
import com.stemify.mobile.eq.DeviceKind
import com.stemify.mobile.eq.EqProfile
import com.stemify.mobile.eq.EqState
import com.stemify.mobile.eq.OutputDevice
import com.stemify.mobile.playback.StemSetting
import com.stemify.mobile.plex.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Stemify Connect's constants and wire formats; the protocol is in docs/CONNECT.md. */
object Wire {
    const val VERSION = 1
    const val BEACON_PORT = 47809
    /** Tried in turn (easy firewall rules), then any free port. */
    val TCP_PORTS = listOf(47810, 47811, 47812, 0)
    val BT_UUID: UUID = UUID.fromString("5f3a7c2e-8d1b-4b6a-9c0e-57656d696679")
    const val BEACON_EVERY_MS = 3000L
    const val DEVICE_TTL_MS = 10_000L
    const val PING_EVERY_MS = 5000L
    const val SILENCE_LIMIT_MS = 20_000L
    const val QUEUE_BEHIND = 50
    const val QUEUE_AHEAD = 1000
}

enum class Transport { Wifi, Bt }

data class Identity(val id: String, val name: String, val app: String = "android")

/** Someone else's Stemify, as found by the beacon or a Bluetooth probe. */
data class NearbyDevice(
    val id: String,
    val name: String,
    val app: String,
    val transport: Transport,
    val host: String? = null,
    val port: Int = 0,
    val address: String? = null,
    val seenAt: Long = 0,
) {
    val appLabel get() = when (app) { "android" -> "Phone"; "mobile" -> "Stemify Mobile"; "player" -> "Stemify Player"; else -> "Stemify" }
}

// ---- reading JSON leniently ---------------------------------------------------------------------

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject.int(k: String): Int? = long(k)?.toInt()
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

// ---- tracks, mixes ------------------------------------------------------------------------------

object WireTrack {
    fun encode(t: Track): JsonElement = AppJson.encodeToJsonElement(Track.serializer(), t)

    fun decode(e: JsonElement?): Track? = (e as? JsonObject)?.let { o ->
        runCatching { AppJson.decodeFromJsonElement(Track.serializer(), o) }.getOrNull()
    }

    fun list(tracks: List<Track>) = buildJsonArray { tracks.forEach { add(encode(it)) } }
}

object WireMix {
    fun encode(m: Map<String, StemSetting>) = buildJsonObject {
        m.forEach { (n, s) -> put(n, buildJsonObject { put("level", s.level); put("muted", s.muted); put("solo", s.solo) }) }
    }

    fun decode(o: JsonObject?): Map<String, StemSetting> = o?.entries?.mapNotNull { (n, v) ->
        val s = v as? JsonObject ?: return@mapNotNull null
        n to StemSetting(
            (s.num("level") ?: StemSetting.UNITY.toDouble()).toFloat().coerceIn(0f, 1f),
            s.bool("muted") == true, s.bool("solo") == true,
        )
    }?.toMap().orEmpty()
}

// ---- the EQ, in the desktop's format ------------------------------------------------------------

/**
 * Converts EQ state to and from the wire. Devices from another phone keep their exact identity
 * ("android:<type>|<name>"); a computer's outputs become stand-in OutputDevices for display,
 * and this remembers which wire id each stands for so they go back unchanged.
 */
class WireEq {
    private val wireIds = HashMap<OutputDevice, String>()

    fun deviceId(d: OutputDevice) = wireIds[d] ?: "android:${d.type}|${d.name}"

    fun deviceJson(d: OutputDevice) = buildJsonObject {
        put("id", deviceId(d))
        put("kind", d.kind.name.lowercase())
        put("label", d.label)
    }

    fun deviceFrom(o: JsonObject?): OutputDevice {
        if (o == null) return OutputDevice(0, "Audio Output")
        val id = o.str("id") ?: return OutputDevice(0, "Audio Output")
        return deviceFrom(id, o.str("kind"), o.str("label"))
    }

    private fun deviceFrom(id: String, kind: String?, label: String?): OutputDevice {
        if (id.startsWith("android:")) {
            val rest = id.removePrefix("android:")
            val type = rest.substringBefore('|').toIntOrNull()
            if (type != null) return OutputDevice(type, rest.substringAfter('|', ""))
        }
        val type = when (kind) {
            "bluetooth" -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            "usb" -> AudioDeviceInfo.TYPE_USB_DEVICE
            "hdmi" -> AudioDeviceInfo.TYPE_HDMI
            "wired" -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            else -> AudioDeviceInfo.TYPE_UNKNOWN  // a computer's speakers aren't "Phone Speaker"
        }
        val d = OutputDevice(type, label?.ifBlank { null } ?: id.substringAfter('|').ifBlank { DeviceKind.Other.name })
        wireIds[d] = id
        return d
    }

    fun encode(s: EqState) = buildJsonObject {
        put("enabled", s.enabled)
        put("automatic", s.automatic)
        put("manualId", s.manualId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("defaultId", s.defaultId)
        put("antiClip", s.antiClip)
        put("profiles", buildJsonArray {
            s.profiles.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("gains", buildJsonArray { p.gains.forEach { add(JsonPrimitive(it)) } })
                    put("devices", buildJsonArray { p.devices.forEach { add(JsonPrimitive(deviceId(it))) } })
                    put("filters", buildJsonArray { p.filters.forEach { add(filterJson(it)) } })
                    put("preamp", p.preamp)
                })
            }
        })
        put("seenDevices", buildJsonArray { s.seenDevices.forEach { add(deviceJson(it)) } })
    }

    private fun filterJson(f: EqFilter) = buildJsonObject {
        put("id", f.id)
        put("type", f.type.name.lowercase())
        put("freq", f.freq)
        put("gain", f.gain)
        put("q", f.q)
        put("slope", f.slope)
        put("channel", f.channel.name.lowercase())
        put("on", f.on)
    }

    fun decode(o: JsonObject): EqState? {
        val seen = o.arr("seenDevices").orEmpty().mapNotNull { e -> (e as? JsonObject)?.let { deviceFrom(it) } }
        val labels = o.arr("seenDevices").orEmpty().mapNotNull { it as? JsonObject }
            .associate { (it.str("id") ?: "") to (it.str("kind") to it.str("label")) }
        val profiles = o.arr("profiles").orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            val gains = p.arr("gains").orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }.take(EQ_BANDS.size)
            EqProfile(
                id = id,
                name = p.str("name") ?: "Profile",
                gains = gains + List(EQ_BANDS.size - gains.size) { 0f },
                devices = p.arr("devices").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .map { did -> labels[did].let { kl -> deviceFrom(did, kl?.first, kl?.second) } },
                filters = p.arr("filters").orEmpty().mapNotNull { f -> (f as? JsonObject)?.let { filterFrom(it) } },
                preamp = (p.num("preamp") ?: 0.0).toFloat(),
            )
        }
        if (profiles.isEmpty()) return null
        return EqState(
            enabled = o.bool("enabled") ?: true,
            automatic = o.bool("automatic") ?: true,
            manualId = o.str("manualId")?.takeIf { id -> profiles.any { it.id == id } },
            defaultId = o.str("defaultId")?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
            antiClip = o.bool("antiClip") ?: true,
            profiles = profiles,
            seenDevices = seen,
        )
    }

    private fun filterFrom(o: JsonObject): EqFilter? {
        val type = FilterType.entries.firstOrNull { it.name.equals(o.str("type"), ignoreCase = true) } ?: return null
        return EqFilter(
            id = o.str("id") ?: UUID.randomUUID().toString(),
            type = type,
            freq = (o.num("freq") ?: 1000.0).toFloat(),
            gain = (o.num("gain") ?: 0.0).toFloat(),
            q = (o.num("q") ?: 0.707).toFloat(),
            slope = o.int("slope") ?: 12,
            channel = EqChannel.entries.firstOrNull { it.name.equals(o.str("channel"), ignoreCase = true) } ?: EqChannel.Both,
            on = o.bool("on") ?: true,
        )
    }
}
