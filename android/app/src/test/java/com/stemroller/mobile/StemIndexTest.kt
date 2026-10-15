package com.stemroller.mobile

import com.stemroller.mobile.core.AppJson
import com.stemroller.mobile.stems.StemEntry
import com.stemroller.mobile.stems.StemFile
import com.stemroller.mobile.stems.StemIndex
import com.stemroller.mobile.stems.StemIndexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

class StemIndexTest {
    private fun entry(source: String, seconds: Double = 200.0) =
        StemEntry(key = source.substringBeforeLast('.'), source = source, duration = seconds, stems = listOf(StemFile("vocals", "v.flac", 1)))

    private val entries = listOf(
        entry("Artist/Album/01 Song.flac"),
        entry("Intro.flac", 60.0),
        entry("Other/Album2/Intro.flac", 90.0),
        entry("Beyoncé/Lemonade/Hold Up.flac"), // decomposed é, as some filesystems store it
    )

    @Test fun matchesThroughUnknownContainerRoot() {
        val idx = StemIndex(entries)
        val hit = idx.find("/music/Actual music/Flacs/Artist/Album/01 Song.flac", 200_000)
        assertEquals("Artist/Album/01 Song", hit?.key)
        assertEquals(setOf("/music/actual music/flacs/"), idx.roots)
    }

    @Test fun rootLevelFileNeedsALearnedRoot() {
        val idx = StemIndex(entries)
        assertNull(idx.find("/music/Flacs/Intro.flac", 60_000))
        idx.find("/music/Flacs/Artist/Album/01 Song.flac", 200_000)
        assertEquals("Intro", idx.find("/music/Flacs/Intro.flac", 60_000)?.key)
    }

    @Test fun sameNameInAnotherFolderDoesNotCollide() {
        val idx = StemIndex(entries, listOf("/music/flacs/"))
        assertEquals("Other/Album2/Intro", idx.find("/music/Flacs/Other/Album2/Intro.flac", 90_000)?.key)
        assertNull(idx.find("/music/Flacs/Elsewhere/Intro.flac", 90_000))
    }

    @Test fun otherFormatOfSameTrackMatches() {
        val idx = StemIndex(entries)
        assertEquals("Artist/Album/01 Song", idx.find("/data/Artist/Album/01 Song.mp3", 200_500)?.key)
    }

    @Test fun durationMismatchRejectsAGuess() {
        val idx = StemIndex(entries)
        assertNull(idx.find("/data/Artist/Album/01 Song.flac", 120_000))
    }

    @Test fun unicodeNormalisationAndCase() {
        val idx = StemIndex(entries)
        assertEquals("Beyoncé/Lemonade/Hold Up", idx.find("/MUSIC/Beyoncé/Lemonade/hold up.flac", 200_000)?.key)
    }

    /** Every entry the real stemserve reports must map back from a Plex-style path. */
    @Test fun realIndexRoundTrips() {
        val body = runCatching { URI("http://localhost:8765/api/index").toURL().readText() }.getOrNull()
        assumeTrue("stemserve not running locally", body != null)
        val index = AppJson.decodeFromString(StemIndexFile.serializer(), body!!)
        val idx = StemIndex(index.tracks)
        val deep = index.tracks.filter { it.source.count { c -> c == '/' } >= 1 }
        for (e in deep + index.tracks) {
            val ms = ((e.duration ?: 0.0) * 1000).toLong()
            assertEquals(e.source, e.key, idx.find("/data/music/Actual music/Flacs/${e.source}", ms)?.key)
        }
    }
}
