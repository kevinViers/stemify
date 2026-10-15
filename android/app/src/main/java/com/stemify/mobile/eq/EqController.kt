package com.stemify.mobile.eq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.engine.EQ_BANDS
import com.stemify.mobile.engine.EQ_MAX_DB
import com.stemify.mobile.engine.EqFilter
import com.stemify.mobile.engine.EqMath
import com.stemify.mobile.engine.EqParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

enum class DeviceKind { Speaker, Wired, Bluetooth, Usb, Hdmi, Other }

/** An output route, identified by type + product name (BT addresses are anonymised). */
@Serializable
data class OutputDevice(val type: Int, val name: String) {
    val id get() = "$type|$name"
    val kind get() = kindOf(type)
    val label: String
        get() = when (kind) {
            DeviceKind.Speaker -> "Phone Speaker"
            DeviceKind.Wired -> if (name.isBlank() || name == "USB-C to 3.5mm") "Wired Headphones" else name
            else -> name.ifBlank { kind.name }
        }

    companion object {
        val SPEAKER = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "")

        fun of(info: AudioDeviceInfo): OutputDevice {
            val kind = kindOf(info.type)
            // The built-in speaker's product name is the phone model; one speaker profile is enough.
            val name = if (kind == DeviceKind.Speaker) "" else info.productName?.toString().orEmpty()
            val type = if (kind == DeviceKind.Speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else info.type
            return OutputDevice(type, name)
        }

        fun kindOf(type: Int) = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> DeviceKind.Speaker
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> DeviceKind.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> DeviceKind.Bluetooth
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> DeviceKind.Usb
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> DeviceKind.Hdmi
            else -> DeviceKind.Other
        }
    }
}

@Serializable
data class EqProfile(
    val id: String,
    val name: String,
    val gains: List<Float> = List(EQ_BANDS.size) { 0f },
    val devices: List<OutputDevice> = emptyList(),
    /** Parametric filters, after the graphic bands. */
    val filters: List<EqFilter> = emptyList(),
    /** Manual preamp in dB, ahead of the anti-clip one (Equalizer APO's "Preamp:"). */
    val preamp: Float = 0f,
) {
    /** Everything the engine runs: graphic bands as peaks, then the filters that are on. */
    val chain get() = EqFilter.graphic(gains) + filters.filter { it.active }
}

@Serializable
data class EqState(
    val enabled: Boolean = true,
    val automatic: Boolean = true,
    /** Picked by hand; wins over automatic switching until cleared. */
    val manualId: String? = null,
    val defaultId: String = "flat",
    val antiClip: Boolean = true,
    val profiles: List<EqProfile> = listOf(EqProfile("flat", "Flat")),
    val seenDevices: List<OutputDevice> = emptyList(),
)

/** iOS Music's EQ list, approximated on ten octave bands. */
val EQ_PRESETS: List<Pair<String, List<Float>>> = listOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Acoustic" to listOf(4.5f, 4.5f, 3.5f, 1f, 1.5f, 1.5f, 3f, 3.5f, 3f, 2f),
    "Bass Booster" to listOf(5.5f, 4.5f, 3.5f, 2.5f, 1.5f, 0f, 0f, 0f, 0f, 0f),
    "Bass Reducer" to listOf(-5.5f, -4.5f, -3.5f, -2.5f, -1.5f, 0f, 0f, 0f, 0f, 0f),
    "Classical" to listOf(4.5f, 3.5f, 3f, 2.5f, -1.5f, -1.5f, 0f, 2f, 3f, 3.5f),
    "Dance" to listOf(3.5f, 6.5f, 5f, 0f, 2f, 3.5f, 5f, 4.5f, 3.5f, 0f),
    "Deep" to listOf(5f, 3.5f, 1.5f, 1f, 3f, 2.5f, 1.5f, -2f, -3.5f, -4.5f),
    "Electronic" to listOf(4f, 3.5f, 1f, 0f, -2f, 2f, 1f, 1.5f, 4f, 5f),
    "Hip-Hop" to listOf(5f, 4f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f),
    "Jazz" to listOf(4f, 3f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f),
    "Late Night" to listOf(4.5f, 3f, 2f, -0.5f, 1.5f, 1.5f, 2f, 3f, 3.5f, 4f),
    "Loudness" to listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f),
    "Piano" to listOf(3f, 2f, 0f, 2.5f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f),
    "Pop" to listOf(-1.5f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1.5f),
    "R&B" to listOf(2.5f, 7f, 5.5f, 1.5f, -2.5f, -1.5f, 2f, 2.5f, 3f, 3.5f),
    "Rock" to listOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f),
    "Small Speakers" to listOf(5.5f, 4.5f, 3.5f, 2.5f, 1.5f, 0f, -1.5f, -2.5f, -3.5f, -4f),
    "Spoken Word" to listOf(-3.5f, -0.5f, 0f, 0.5f, 3.5f, 4.5f, 4.5f, 4f, 2f, 0f),
    "Treble Booster" to listOf(0f, 0f, 0f, 0f, 0f, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f),
    "Treble Reducer" to listOf(0f, 0f, 0f, 0f, 0f, -1.5f, -2.5f, -3.5f, -4.5f, -5.5f),
    "Vocal Booster" to listOf(-1.5f, -3f, -3f, 1.5f, 3.5f, 3.5f, 3f, 1.5f, 0f, -1.5f),
)

/**
 * EQ profiles and the current output route. In automatic mode the active profile is the
 * one linked to the route (else the default); a hand-picked profile overrides that until
 * the user goes back to automatic.
 */
class EqController(context: Context) {
    private val file = File(context.filesDir, "eq.json")
    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val saveTask = Runnable {
        runCatching {
            val tmp = File(file.parentFile, "eq.json.tmp")
            tmp.writeText(AppJson.encodeToString(EqState.serializer(), state.value))
            tmp.renameTo(file)
        }
    }


    private val _state = MutableStateFlow(load())
    val state: StateFlow<EqState> = _state.asStateFlow()
    private val _device = MutableStateFlow(OutputDevice.SPEAKER)
    val device: StateFlow<OutputDevice> = _device.asStateFlow()
    private val _active = MutableStateFlow(resolve(_state.value, _device.value))
    val active: StateFlow<EqProfile?> = _active.asStateFlow()

    var onParams: ((EqParams?) -> Unit)? = null

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRoute()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshRoute()
        }, main)
        refreshRoute()
    }

    /** Where media would go right now, for when no AudioTrack is open. */
    fun refreshRoute() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { setDevice(OutputDevice.of(it)) }
    }

    /** From the engine's AudioTrack routing callback: the authoritative route while playing. */
    fun onRouted(info: AudioDeviceInfo?) {
        if (info != null) setDevice(OutputDevice.of(info)) else refreshRoute()
    }

    private fun setDevice(d: OutputDevice) {
        if (_device.value == d && d in _state.value.seenDevices) return
        _device.value = d
        val s = _state.value
        if (d !in s.seenDevices) update(s.copy(seenDevices = (listOf(d) + s.seenDevices).take(24))) else publish()
    }

    // ---- edits ------------------------------------------------------------------------

    fun setEnabled(on: Boolean) = update(_state.value.copy(enabled = on))
    fun setAntiClip(on: Boolean) = update(_state.value.copy(antiClip = on))

    /** Automatic switching on clears the manual pick. */
    fun setAutomatic(on: Boolean) = update(_state.value.copy(automatic = on, manualId = if (on) null else _state.value.manualId ?: _active.value?.id))

    /** Picking a profile by hand overrides automatic switching. */
    fun select(id: String) = update(_state.value.copy(manualId = id, automatic = false))

    fun setGain(id: String, band: Int, db: Float) = edit(id) { p ->
        p.copy(gains = p.gains.toMutableList().also { it[band] = db.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) })
    }

    fun setGains(id: String, gains: List<Float>) = edit(id) { it.copy(gains = gains.map { g -> g.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) }) }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.ifBlank { it.name }) }

    fun setPreamp(id: String, db: Float) = edit(id) { it.copy(preamp = db.coerceIn(-PREAMP_RANGE, PREAMP_RANGE)) }

    /** Adds or replaces (by id) a parametric filter. */
    fun putFilter(id: String, f: EqFilter) = edit(id) { p ->
        val clean = f.copy(freq = f.freq.coerceIn(FREQ_MIN, FREQ_MAX), gain = f.gain.coerceIn(-FILTER_GAIN, FILTER_GAIN), q = f.q.coerceIn(Q_MIN, Q_MAX))
        p.copy(filters = if (p.filters.any { it.id == f.id }) p.filters.map { if (it.id == f.id) clean else it } else p.filters + clean)
    }

    fun removeFilter(id: String, filterId: String) = edit(id) { p -> p.copy(filters = p.filters.filter { it.id != filterId }) }

    /** Replaces the filters (and the preamp, when given): an Equalizer APO / AutoEQ import. */
    fun setFilters(id: String, filters: List<EqFilter>, preamp: Float?) = edit(id) { p ->
        p.copy(filters = filters, preamp = preamp?.coerceIn(-PREAMP_RANGE, PREAMP_RANGE) ?: p.preamp)
    }

    fun create(name: String, from: EqProfile? = null): EqProfile {
        val p = EqProfile(UUID.randomUUID().toString(), name.ifBlank { "Profile" }, from?.gains ?: List(EQ_BANDS.size) { 0f },
            filters = from?.filters?.map { it.copy(id = newFilterId()) }.orEmpty(), preamp = from?.preamp ?: 0f)
        val s = _state.value
        update(s.copy(profiles = s.profiles + p))
        return p
    }

    fun delete(id: String) {
        val s = _state.value
        if (s.profiles.size <= 1) return
        val rest = s.profiles.filter { it.id != id }
        update(s.copy(
            profiles = rest,
            defaultId = if (s.defaultId == id) rest.first().id else s.defaultId,
            manualId = s.manualId.takeIf { it != id },
        ))
    }

    fun setDefault(id: String) = update(_state.value.copy(defaultId = id))

    /** Links [device] to [id], unlinking it from any other profile (one profile per route). */
    fun link(id: String, device: OutputDevice, on: Boolean) {
        val s = _state.value
        update(s.copy(profiles = s.profiles.map { p ->
            val others = p.devices.filter { it != device }
            p.copy(devices = if (p.id == id && on) others + device else others)
        }))
    }

    fun profileFor(device: OutputDevice) = _state.value.profiles.firstOrNull { device in it.devices }

    private fun edit(id: String, f: (EqProfile) -> EqProfile) {
        val s = _state.value
        update(s.copy(profiles = s.profiles.map { if (it.id == id) f(it) else it }))
    }

    private fun update(s: EqState) {
        _state.value = s
        publish()
        // Slider drags update many times a second; write once they settle.
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, 400)
    }

    private fun publish() {
        val s = _state.value
        val p = resolve(s, _device.value)
        _active.value = p
        onParams?.invoke(paramsFor(s, p))
    }

    fun currentParams(): EqParams? = paramsFor(_state.value, _active.value)

    private fun resolve(s: EqState, d: OutputDevice): EqProfile? {
        if (!s.automatic && s.manualId != null) s.profiles.firstOrNull { it.id == s.manualId }?.let { return it }
        return s.profiles.firstOrNull { d in it.devices }
            ?: s.profiles.firstOrNull { it.id == s.defaultId }
            ?: s.profiles.firstOrNull()
    }

    private fun paramsFor(s: EqState, p: EqProfile?): EqParams? {
        if (!s.enabled || p == null) return null
        val chain = p.chain
        if (chain.isEmpty() && p.preamp == 0f) return null
        return EqParams(chain, p.preamp + if (s.antiClip) EqMath.autoPreampDb(chain, p.preamp) else 0f)
    }

    companion object {
        const val FREQ_MIN = 10f
        const val FREQ_MAX = 22000f
        const val FILTER_GAIN = 24f
        const val Q_MIN = 0.1f
        const val Q_MAX = 20f
        const val PREAMP_RANGE = 24f

        fun newFilterId() = UUID.randomUUID().toString()
    }

    private fun load(): EqState = runCatching {
        AppJson.decodeFromString(EqState.serializer(), file.readText())
    }.getOrElse {
        EqState(
            profiles = listOf(
                EqProfile("flat", "Flat"),
                EqProfile("speaker", "Small Speaker Tune", EQ_PRESETS.first { it.first == "Small Speakers" }.second, listOf(OutputDevice.SPEAKER)),
            ),
        )
    }
}
