package com.stemify.mobile

import com.stemify.mobile.auto.CarIds
import com.stemify.mobile.auto.CarTree
import com.stemify.mobile.playback.MixPresets
import com.stemify.mobile.playback.StemSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTreeTest {
    @Test
    fun picksRoundTrip() {
        assertEquals(CarIds.Pick.Song("album:123", "456"), CarIds.parsePick(CarIds.play("album:123", "456")))
        assertEquals(CarIds.Pick.Shuffle("playlist:9"), CarIds.parsePick(CarIds.shuffle("playlist:9")))
        // Search queries can contain the separator; the container is last so they survive.
        val q = CarIds.search("AC|DC: Back in Black")
        assertEquals(CarIds.Pick.Song(q, "77"), CarIds.parsePick(CarIds.play(q, "77")))
        assertNull(CarIds.parsePick("album:123"))
        assertNull(CarIds.parsePick("play|456"))
        assertNull(CarIds.parsePick("shuffle|"))
    }

    @Test
    fun splitsContainers() {
        assertEquals("album" to "123", CarIds.split("album:123"))
        assertEquals("search" to "a:b", CarIds.split("search:a:b"))
        assertNull(CarIds.split("root"))
    }

    @Test
    fun lettersFoldAccentsAndNonLetters() {
        assertEquals("E", CarTree.letterOf("Éric Serra"))
        assertEquals("#", CarTree.letterOf("2Pac"))
        assertEquals("#", CarTree.letterOf("!!!"))
        assertEquals("B", CarTree.letterOf("  black sabbath"))
        assertEquals("#", CarTree.letterOf(""))
    }

    @Test
    fun bucketsPutNumbersFirst() {
        val b = CarTree.buckets(listOf("Zappa", "ABBA", "2Pac", "AC/DC", "Björk")) { it }
        assertEquals(listOf("#", "A", "B", "Z"), b.keys.toList())
        assertEquals(listOf("ABBA", "AC/DC"), b["A"])
    }

    @Test
    fun voiceMatchingIgnoresCaseAccentsPunctuationAndThe() {
        assertTrue(CarTree.matches("Paranoid", "paranoid"))
        assertTrue(CarTree.matches("The Beatles", "beatles"))
        assertTrue(CarTree.matches("Motörhead", "motorhead"))
        assertTrue(CarTree.matches("AC/DC", "acdc"))
        assertFalse(CarTree.matches("Paranoid", "para"))
        assertFalse(CarTree.matches("Paranoid", ""))
    }

    private val six = listOf("vocals", "drums", "bass", "guitar", "piano", "other")

    @Test
    fun mixCycleSkipsPresetsTheTrackCantDo() {
        val labels = generateSequence(emptyMap<String, StemSetting>()) { m -> MixPresets.next(six, m).build(six) }
            .drop(1).take(MixPresets.ALL.size).map { MixPresets.current(six, it)!!.label }.toList()
        assertEquals(listOf("Karaoke", "A Cappella", "No Drums", "Rhythm", "No Guitar", "No Bass", "Full Mix"), labels)

        val two = listOf("vocals", "instrumental")
        assertEquals("Karaoke", MixPresets.next(two, emptyMap()).label)
        assertEquals("A Cappella", MixPresets.next(two, MixPresets.KARAOKE.build(two)!!).label)
        assertEquals("Full Mix", MixPresets.next(two, MixPresets.ALL[2].build(two)!!).label)
    }

    @Test
    fun customMixIsRecognisedAndCyclesToFull() {
        val custom = mapOf("drums" to StemSetting(level = 0.5f))
        assertNull(MixPresets.current(six, custom))
        assertEquals("Full Mix", MixPresets.next(six, custom).label)
        // Faders left at their defaults don't make a mix custom.
        assertEquals("Full Mix", MixPresets.current(six, mapOf("bass" to StemSetting()))!!.label)
    }

    @Test
    fun vocalsSilence() {
        assertFalse(MixPresets.vocalsSilent(six, emptyMap()))
        assertTrue(MixPresets.vocalsSilent(six, MixPresets.KARAOKE.build(six)!!))
        assertFalse(MixPresets.vocalsSilent(six, MixPresets.ALL[2].build(six)!!)) // A Cappella
        assertTrue(MixPresets.vocalsSilent(six, MixPresets.ALL[4].build(six)!!)) // Rhythm solos drums and bass
        assertFalse(MixPresets.vocalsSilent(listOf("drums"), emptyMap()))
    }
}
