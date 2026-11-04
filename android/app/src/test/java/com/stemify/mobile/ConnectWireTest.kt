package com.stemify.mobile

import com.stemify.mobile.connect.WireEq
import com.stemify.mobile.connect.WireMix
import com.stemify.mobile.connect.WireTrack
import com.stemify.mobile.connect.str
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.engine.EqChannel
import com.stemify.mobile.engine.EqFilter
import com.stemify.mobile.engine.FilterType
import com.stemify.mobile.eq.EqProfile
import com.stemify.mobile.eq.EqState
import com.stemify.mobile.eq.OutputDevice
import com.stemify.mobile.playback.StemSetting
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stemify Connect's wire formats, against what the desktop apps actually send (docs/CONNECT.md). */
class ConnectWireTest {
    private fun json(s: String) = AppJson.parseToJsonElement(s).jsonObject

    @Test fun desktopTrackDecodes() {
        // stemify.connect.protocol.track_to_wire, verbatim
        val t = WireTrack.decode(json("""{"ratingKey": "12345", "title": "Carry On Wayward Son", "artist": "Kansas", "album": "Leftoverture",
            "albumKey": "123", "artistKey": "12", "year": 1976, "index": 1, "disc": 1, "durationMs": 323000,
            "thumb": "/library/metadata/123/thumb/1", "partFile": "/music/a.flac", "partKey": "/library/parts/4567/file.flac",
            "partId": 4567, "partSize": 41234567, "codec": "flac"}"""))
        assertNotNull(t)
        assertEquals("12345", t!!.ratingKey)
        assertEquals(4567L, t.partId)
        assertEquals(323000L, t.durationMs)
        // A minimal one (older desktop queues): still a track, to be looked up in Plex.
        val bare = WireTrack.decode(json("""{"ratingKey": "9", "title": "T", "artist": "A", "album": ""}"""))
        assertEquals(null, bare?.partId)
        // And back: the desktop reads ratingKey, title and friends.
        val back = WireTrack.encode(t).jsonObject
        assertEquals("12345", back.str("ratingKey"))
        assertEquals("Leftoverture", back.str("album"))
    }

    @Test fun mixRoundTrips() {
        val m = mapOf("vocals" to StemSetting(muted = true), "drums" to StemSetting(level = 0.5f, solo = true))
        assertEquals(m, WireMix.decode(WireMix.encode(m)))
        // The desktop leaves out fields at their defaults.
        assertEquals(mapOf("bass" to StemSetting(level = 0.3f)), WireMix.decode(json("""{"bass": {"level": 0.3}}""")))
    }

    @Test fun androidEqRoundTrips() {
        val headset = OutputDevice(8, "WH-1000XM5")  // TYPE_BLUETOOTH_A2DP
        val s = EqState(
            enabled = true, automatic = false, manualId = "p2", defaultId = "flat", antiClip = false,
            profiles = listOf(
                EqProfile("flat", "Flat"),
                EqProfile("p2", "Headset", List(10) { it * 0.5f }, listOf(headset),
                    listOf(EqFilter("f", FilterType.LowShelf, 90f, 3f, 0.7f, 12, EqChannel.Left, true)), -2f),
            ),
            seenDevices = listOf(OutputDevice.SPEAKER, headset),
        )
        val wire = WireEq()
        val out = wire.encode(s)
        val f = out["profiles"]!!.jsonArray[1].jsonObject["filters"]!!.jsonArray[0].jsonObject
        assertEquals("lowshelf", f.str("type"))
        assertEquals("left", f.str("channel"))
        assertEquals("android:8|WH-1000XM5", out["profiles"]!!.jsonArray[1].jsonObject["devices"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(s, WireEq().decode(json(out.toString())))
    }

    @Test fun desktopEqKeepsItsDeviceIds() {
        // stemify.mobile.eq's state, as a Stemify Mobile receiver sends it
        val desk = json("""{"enabled": true, "automatic": true, "manualId": null, "defaultId": "flat", "antiClip": true,
            "profiles": [{"id": "flat", "name": "Flat", "gains": [0,0,0,0,0,0,0,0,0,0], "devices": ["speaker|"], "filters": [], "preamp": 0.0},
                         {"id": "x", "name": "Cans", "gains": [1,2,3], "devices": ["bluetooth|bluez_output.AC_80_0A.1"],
                          "filters": [{"id": "a", "type": "peak", "freq": 1000.0, "gain": -3.0, "q": 1.41, "slope": 12, "channel": "both", "on": true},
                                      {"id": "b", "type": "nonsense"}], "preamp": -1.5}],
            "seenDevices": [{"id": "speaker|", "kind": "speaker", "label": "Speakers"},
                            {"id": "bluetooth|bluez_output.AC_80_0A.1", "kind": "bluetooth", "label": "WH-1000XM5"}]}""")
        val wire = WireEq()
        val s = wire.decode(desk)!!
        assertEquals(10, s.profiles[1].gains.size)
        assertEquals(1, s.profiles[1].filters.size)  // the unknown filter type is dropped
        assertEquals("WH-1000XM5", s.profiles[1].devices[0].label)
        assertEquals("Speakers", s.seenDevices[0].label)  // not "Phone Speaker"
        val device = wire.deviceFrom(json("""{"id": "speaker|", "kind": "speaker", "label": "Speakers"}"""))
        // Edited here and sent back: the desktop's own ids come back unchanged.
        val back: JsonObject = wire.encode(s.copy(profiles = s.profiles.map { if (it.id == "flat") it.copy(devices = listOf(device)) else it }))
        val ids = back["profiles"]!!.jsonArray.map { p -> p.jsonObject["devices"]!!.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(listOf(listOf("speaker|"), listOf("bluetooth|bluez_output.AC_80_0A.1")), ids)
        assertTrue(back["seenDevices"]!!.jsonArray.any { it.jsonObject.str("id") == "bluetooth|bluez_output.AC_80_0A.1" })
    }
}
