package com.stemify.mobile

import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.engine.CrossfadeMode
import com.stemify.mobile.eq.EqProfile
import com.stemify.mobile.eq.EqState
import com.stemify.mobile.plex.Playlist
import com.stemify.mobile.plex.Track
import com.stemify.mobile.transfer.Backup
import com.stemify.mobile.transfer.BackupPlaylist
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun typed(m: Map<String, Any>) = m.mapValues { (_, v) ->
        buildJsonObject {
            when (v) {
                is String -> { put("t", "s"); put("v", v) }
                is Boolean -> { put("t", "b"); put("v", v) }
                is Int -> { put("t", "i"); put("v", v) }
                is Long -> { put("t", "l"); put("v", v) }
                is Float -> { put("t", "f"); put("v", v) }
            }
        }
    }

    @Test fun settingsCarryOverWithTheirTypesButNotThisPhonesTrust() {
        val old = Settings(MemPrefs()).apply {
            crossfade.value = CrossfadeMode.Fixed
            crossfadeSeconds.value = 9
            masterVolume.value = 0.5f
            keepStemMix.value = false
            stemQuality.value = StemQuality.Lossless
            plexConnectionsAt.value = 1_700_000_000_000L
            stemToken.value = "secret"
            connectKeys.value = """{"tv":"k"}"""
            connectAllowed.value = """{"laptop":{}}"""
        }
        val exported = old.export()
        assertFalse("connectKeys" in exported)
        assertFalse("connectAllowed" in exported)

        // Through the file and back.
        val b = Backup(device = "Old", made = 1, settings = typed(exported))
        val back = Backup.parse(AppJson.encodeToString(Backup.serializer(), b))

        val new = Settings(MemPrefs()).apply { connectKeys.value = """{"mine":"x"}""" }
        new.import(back.settings.mapNotNull { (k, v) -> untyped(v)?.let { k to it } }.toMap())
        assertEquals(CrossfadeMode.Fixed, new.crossfade.value)
        // Watchers see the restored value (the screens, the player), not just the stored one.
        assertEquals(9, new.crossfadeSeconds.flow.value)
        assertEquals(0.5f, new.masterVolume.value)
        assertFalse(new.keepStemMix.value)
        assertEquals(StemQuality.Lossless, new.stemQuality.value)
        assertEquals(1_700_000_000_000L, new.plexConnectionsAt.value)
        assertEquals("secret", new.stemToken.value)
        assertEquals("""{"mine":"x"}""", new.connectKeys.value)
        assertTrue(new.clientId != old.clientId)
    }

    @Test fun importIgnoresUnknownAndForeignKeys() {
        val s = Settings(MemPrefs())
        s.import(mapOf("notASetting" to "x", "connectKeys" to "{}", "crossfadeSeconds" to 4))
        assertEquals(4, s.crossfadeSeconds.value)
        assertEquals("{}", s.connectKeys.value)
    }

    @Test fun downloadsEqAndQueueSurviveTheFile() {
        val t1 = Track("1", "Paranoid", "Black Sabbath", "Paranoid", albumKey = "10")
        val t2 = Track("2", "Iron Man", "Black Sabbath", "Paranoid", albumKey = "10")
        val t3 = Track("3", "Song", "Other", "Other Album", albumKey = "20")
        val queue = AppJson.parseToJsonElement("""{"items":[{"uid":1},{"uid":2}],"index":1,"mix":{"vocals":{"level":0.0}}}""")
        val b = Backup(
            device = "CPH2551", made = 5,
            eq = EqState(profiles = listOf(EqProfile("flat", "Flat"), EqProfile("x", "Car"))),
            queue = queue,
            songs = listOf(t1, t2, t3),
            playlists = listOf(BackupPlaylist(Playlist("p", "Drive"), listOf(t3, t1))),
        )
        val back = Backup.parse(AppJson.encodeToString(Backup.serializer(), b))
        assertEquals(listOf("1", "2", "3"), back.songs.map { it.ratingKey })
        assertEquals(2, back.albums)
        assertEquals(listOf("3", "1"), back.playlists.single().tracks.map { it.ratingKey })
        assertEquals(listOf("Flat", "Car"), back.eq!!.profiles.map { it.name })
        assertEquals(2, back.queueLength)
        assertEquals(queue, back.queue)
    }

    @Test fun otherFilesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Backup.parse("""{"stemifyBackup": 0}""") }
        assertThrows(IllegalArgumentException::class.java) { Backup.parse("not json") }
        val desktop = AppJson.encodeToString(Backup.serializer(), Backup(platform = "desktop"))
        val e = assertThrows(IllegalArgumentException::class.java) { Backup.parse(desktop) }
        assertEquals("That backup is from Stemify on a computer, not a phone.", e.message)
    }

    @Test fun malformedSettingsAreSkipped() {
        assertNull(untyped(JsonPrimitive(3)))
        assertNull(untyped(buildJsonObject { put("t", "i"); put("v", "abc") }))
        assertEquals(3, untyped(buildJsonObject { put("t", "i"); put("v", 3) }))
    }

    private fun untyped(e: kotlinx.serialization.json.JsonElement) = Backup.untyped(e)
}
