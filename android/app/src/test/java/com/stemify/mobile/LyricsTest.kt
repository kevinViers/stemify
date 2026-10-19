package com.stemify.mobile

import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.lyrics.Karaoke
import com.stemify.mobile.lyrics.LyricLine
import com.stemify.mobile.lyrics.LyricsParse
import com.stemify.mobile.lyrics.LyricsRepo
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.stems.StemEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun parsesLrcWithRepeatsOffsetAndBreaks() {
        val l = LyricsParse.lrc(
            """
            [ar:Someone]
            [offset:+500]
            [00:12.34]First line
            [00:15.5][01:15.50]Chorus
            [00:20.00]
            [00:22.000]After the break
            """.trimIndent(), "LRCLIB",
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(11_840L, 15_000L, 19_500L, 21_500L, 75_000L), l.lines.map { it.ms })
        assertEquals(listOf("First line", "Chorus", "", "After the break", "Chorus"), l.lines.map { it.text })
    }

    @Test fun parsesEnhancedLrcWords() {
        val l = LyricsParse.lrc("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world", "x")!!
        val line = l.lines.single()
        assertEquals("Hello big world", line.text)
        assertEquals(listOf(1000L, 1500L, 2000L), line.words.map { it.ms })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test fun untimedTextIsPlain() {
        val l = LyricsParse.lrc("one\n\n\n\ntwo\n", "x")!!
        assertFalse(l.synced)
        assertEquals(listOf("one", "", "two"), l.lines.map { it.text })
        assertNull(LyricsParse.lrc("[ar:Nobody]\n\n", "x"))
    }

    @Test fun parsesPlexJson() {
        val body = """{"MediaContainer":{"size":1,"Lyrics":[{"provider":"com.plexapp.agents.lyricfind","timed":true,
            "by":"Lyrics © Someone","Line":[{"startOffset":38040,"endOffset":41210,"Span":[{"text":"Tripping off the beat","startOffset":38040,"endOffset":41210}]},
            {},{"startOffset":41210,"endOffset":44000,"Span":[{"text":"Heat ","startOffset":41210},{"text":"niner","startOffset":42000}]}]}]}}"""
        val l = LyricsParse.plexBody(body, "Plex")!!
        assertTrue(l.synced)
        assertEquals("Lyrics © Someone", l.credit)
        assertEquals(listOf("Tripping off the beat", "Heat niner"), l.lines.map { it.text })
        assertEquals(41_210L, l.lines[0].endMs)
        assertEquals(listOf(41_210L, 42_000L), l.lines[1].words.map { it.ms })
    }

    @Test fun parsesUntimedPlexJson() {
        val body = """{"MediaContainer":{"Lyrics":[{"timed":false,"Line":[{"Span":[{"text":"Hey"}]},{},{"Span":[{"text":"Ho"}]}]}]}}"""
        val l = LyricsParse.plexBody(body, "Plex")!!
        assertFalse(l.synced)
        assertEquals(listOf("Hey", "", "Ho"), l.lines.map { it.text })
    }

    @Test fun karaokeTiming() {
        val lines = listOf(LyricLine(1000, "abcd"), LyricLine(3000, "efgh"), LyricLine(60_000, "late"))
        assertEquals(-1, Karaoke.lineAt(lines, 500))
        assertEquals(0, Karaoke.lineAt(lines, 1000))
        assertEquals(1, Karaoke.lineAt(lines, 59_999))
        // Ends at the next line, but a long gap (a solo) doesn't stretch the line across it.
        assertEquals(3000L, Karaoke.lineEnd(lines, 0))
        assertEquals(3000L + 2000L, Karaoke.lineEnd(lines, 1))
        assertEquals(2f, Karaoke.sung(lines, 0, 2000), 0.01f)
        assertEquals(4f, Karaoke.sung(lines, 1, 10_000), 0.01f)
    }

    @Test fun karaokeFollowsWordTimes() {
        val l = LyricsParse.lrc("[00:01.00]<00:01.00>ab <00:03.00>cd\n[00:05.00]next", "x")!!
        // "ab " (3 chars) from 1s to 3s, then "cd" from 3s to the line's end.
        assertEquals(1.5f, Karaoke.sung(l.lines, 0, 2000), 0.01f)
        assertEquals(3f, Karaoke.sung(l.lines, 0, 3000), 0.01f)
    }

    @Test fun cleansTitlesAndArtists() {
        assertEquals("Song", LyricsRepo.cleanTitle("Song (feat. Someone) [Remastered 2011]"))
        assertEquals("Song", LyricsRepo.cleanTitle("Song - 2011 Remaster"))
        assertEquals("(Untitled)", LyricsRepo.cleanTitle("(Untitled)"))
        assertEquals("Jay-Z", LyricsRepo.primaryArtist("Jay-Z & Kanye West"))
        assertEquals("A", LyricsRepo.primaryArtist("A feat. B"))
    }

    @Test fun stemKeysRoundTrip() {
        val e = StemEntry("Artist/Album/01 Song", "Artist/Album/01 Song.flac")
        val key = PlaybackManager.stemKey(e, "vocals.flac", 1234, StemQuality.High)
        val p = PlaybackManager.parseStemKey(key)!!
        assertEquals("Artist/Album/01 Song", p.entryKey)
        assertEquals("vocals.flac", p.file)
        assertEquals(1234L, p.size)
        assertEquals(StemQuality.High, p.quality)
        assertNull(PlaybackManager.parseStemKey("plex|1|2"))
    }
}
