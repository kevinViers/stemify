package com.stemify.mobile.lyrics

import com.stemify.mobile.cache.MediaCache
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.HttpException
import com.stemify.mobile.core.Settings
import com.stemify.mobile.core.await
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.PlexResponse
import com.stemify.mobile.plex.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** One word (or part of a line) and when it's sung, for word-by-word karaoke. */
@Serializable
data class LyricWord(val ms: Long, val text: String)

/** A line of lyrics. [ms] is when it starts (0 for unsynced lyrics); [text] is "" for a break. */
@Serializable
data class LyricLine(val ms: Long = 0, val text: String = "", val endMs: Long? = null, val words: List<LyricWord> = emptyList())

@Serializable
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    /** Lines carry times. */
    val synced: Boolean = false,
    /** "Plex" or "LRCLIB". */
    val source: String = "",
    /** The publisher's copyright line, when there is one. */
    val credit: String? = null,
    val instrumental: Boolean = false,
)

/** Lyrics formats: LRC (with optional word times), Plex's JSON lyrics, and plain text. */
object LyricsParse {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val TAG = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")

    private fun ms(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    /** LRC, or plain text when nothing in it is timed. Null when there's no text at all. */
    fun lrc(text: String, source: String, credit: String? = null): Lyrics? {
        var offset = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val tag = TAG.matchEntire(line.trim())
            if (tag != null && !TIME.containsMatchIn(line)) {
                // [offset:+250] moves every line 250 ms earlier.
                if (tag.groupValues[1].equals("offset", true)) offset = tag.groupValues[2].trim().toLongOrNull() ?: offset
                continue
            }
            val stamps = ArrayList<Long>()
            var rest = line
            while (true) {
                val m = TIME.find(rest) ?: break
                if (m.range.first != 0) break
                stamps += ms(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                plain += line.trim()
                continue
            }
            val (body, words) = words(rest)
            for (t in stamps) timed += LyricLine(t, body.trim(), words = words)
        }
        if (timed.isNotEmpty()) {
            val lines = timed.map { l -> l.copy(ms = (l.ms - offset).coerceAtLeast(0), words = l.words.map { it.copy(ms = (it.ms - offset).coerceAtLeast(0)) }) }
                .sortedBy { it.ms }
            if (lines.any { it.text.isNotEmpty() }) return Lyrics(trimBreaks(lines), synced = true, source = source, credit = credit)
        }
        return plain(plain.joinToString("\n"), source, credit)
    }

    /** "<00:01.00>Hello <00:01.50>world" -> ("Hello world", words); no tags -> (text, none). */
    private fun words(s: String): Pair<String, List<LyricWord>> {
        val marks = WORD.findAll(s).toList()
        if (marks.isEmpty()) return s to emptyList()
        val words = ArrayList<LyricWord>()
        val lead = s.substring(0, marks.first().range.first)
        marks.forEachIndexed { i, m ->
            val end = marks.getOrNull(i + 1)?.range?.first ?: s.length
            val piece = s.substring(m.range.last + 1, end)
            if (piece.isNotEmpty()) words += LyricWord(ms(m), piece)
        }
        val text = lead + words.joinToString("") { it.text }
        // Words must spell the line exactly, so leading text (rare) folds into the first word.
        if (lead.isNotEmpty() && words.isNotEmpty()) words[0] = words[0].copy(text = lead + words[0].text)
        // The line is shown trimmed: keep the words in step with that.
        val trimmedStart = text.length - text.trimStart().length
        if (trimmedStart > 0 && words.isNotEmpty()) words[0] = words[0].copy(text = words[0].text.drop(trimmedStart))
        if (words.isNotEmpty()) {
            val lastIdx = words.lastIndex
            words[lastIdx] = words[lastIdx].copy(text = words[lastIdx].text.trimEnd())
        }
        return text to words.filter { it.text.isNotEmpty() }
    }

    fun plain(text: String, source: String, credit: String? = null): Lyrics? {
        val lines = text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null
        // Runs of blank lines are one stanza break.
        val out = lines.filterIndexed { i, l -> l.isNotEmpty() || lines.getOrNull(i - 1)?.isNotEmpty() == true }
        return Lyrics(out.map { LyricLine(text = it) }, synced = false, source = source, credit = credit)
    }

    /** No leading break lines, and never two in a row. */
    private fun trimBreaks(lines: List<LyricLine>): List<LyricLine> {
        val ls = lines.dropWhile { it.text.isEmpty() }
        return ls.filterIndexed { i, l -> l.text.isNotEmpty() || ls.getOrNull(i - 1)?.text?.isNotEmpty() == true }
    }

    /** A lyrics stream from Plex: JSON lines (Accept: application/json), else the raw LRC or text file. */
    fun plexBody(body: String, source: String): Lyrics? {
        val t = body.trimStart()
        if (!t.startsWith("{")) return if (t.isEmpty()) null else lrc(t, source)
        val ly = runCatching { AppJson.decodeFromString(PlexResponse.serializer(), t).container.lyrics.firstOrNull() }.getOrNull() ?: return null
        val credit = ly.by?.takeIf { it.isNotBlank() }
        val synced = ly.lines.any { it.startOffset != null }
        if (!synced) return plain(ly.lines.joinToString("\n") { l -> l.spans.joinToString("") { it.text } }, source, credit)
        val lines = ly.lines.filter { it.startOffset != null }.map { l ->
            val text = l.spans.joinToString("") { it.text }
            val words = if (l.spans.size > 1 && l.spans.all { it.startOffset != null }) l.spans.map { LyricWord(it.startOffset!!, it.text) } else emptyList()
            LyricLine(l.startOffset!!, text.trim(), l.endOffset, if (text == text.trim()) words else emptyList())
        }.sortedBy { it.ms }
        if (lines.none { it.text.isNotEmpty() }) return null
        return Lyrics(trimBreaks(lines), synced = true, source = source, credit = credit)
    }
}

/** Timing for the karaoke view. */
object Karaoke {
    /** The line being sung at [ms]: the last one started, -1 before the first. */
    fun lineAt(lines: List<LyricLine>, ms: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].ms <= ms) { hit = mid; lo = mid + 1 } else hi = mid - 1
        }
        return hit
    }

    /**
     * When line [i] has been sung through. Its own end time when known, else the next line's
     * start, but no longer than the words could take (so a solo after it doesn't stretch it).
     */
    fun lineEnd(lines: List<LyricLine>, i: Int): Long {
        val l = lines[i]
        l.endMs?.let { return it }
        val guess = l.ms + (l.text.length * 90L).coerceIn(2000L, 8000L)
        val last = l.words.lastOrNull()?.ms?.plus(600) ?: 0L
        val next = lines.getOrNull(i + 1)?.ms ?: Long.MAX_VALUE
        return minOf(next, maxOf(guess, last))
    }

    /** How many characters of line [i] have been sung at [ms], fractionally, for the highlight. */
    fun sung(lines: List<LyricLine>, i: Int, ms: Long): Float {
        val l = lines[i]
        val end = lineEnd(lines, i)
        if (ms <= l.ms) return 0f
        if (ms >= end) return l.text.length.toFloat()
        if (l.words.isEmpty()) return l.text.length * (ms - l.ms).toFloat() / (end - l.ms).coerceAtLeast(1)
        var done = 0f
        for ((k, w) in l.words.withIndex()) {
            if (ms < w.ms) break
            val wEnd = minOf(l.words.getOrNull(k + 1)?.ms ?: end, end)
            val f = ((ms - w.ms).toFloat() / (wEnd - w.ms).coerceAtLeast(1)).coerceIn(0f, 1f)
            done += w.text.length * f
            if (f < 1f) break
        }
        return done.coerceAtMost(l.text.length.toFloat())
    }
}

@Serializable
private data class LrclibTrack(
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

@Serializable
private data class Stored(val at: Long, val lyrics: Lyrics? = null)

/**
 * Finds lyrics for a song: Plex's first (LyricFind or .lrc files in the library), then LRCLIB,
 * a free lyrics database on the internet, which also answers away from home. Synced lyrics win
 * over plain ones from either. Results are kept on the phone, so downloaded songs have their
 * lyrics offline.
 */
class LyricsRepo(
    private val http: OkHttpClient,
    private val plex: PlexClient,
    private val settings: Settings,
    filesDir: File,
    scope: CoroutineScope,
) {
    private val dir = File(filesDir, "lyrics")
    private class Memo(val lyrics: Lyrics?, val at: Long, val final: Boolean)
    private val memory = object : LinkedHashMap<String, Memo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?) = size > 64
    }

    init {
        scope.launch(Dispatchers.IO) { dir.mkdirs(); trim() }
    }

    /** Lyrics for [track], or null when no source has any. [refresh] skips what's saved. */
    suspend fun get(track: Track, refresh: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        val key = track.ratingKey
        val file = File(dir, MediaCache.idFor(key) + ".json")
        val now = System.currentTimeMillis()
        if (!refresh) {
            synchronized(memory) { memory[key] }?.let { m ->
                if (m.lyrics != null || m.final || now - m.at < RETRY_MS) return@withContext m.lyrics
            }
            val saved = runCatching { AppJson.decodeFromString(Stored.serializer(), file.readText()) }.getOrNull()
            if (saved != null && (saved.lyrics != null || now - saved.at < NONE_TTL_MS)) {
                synchronized(memory) { memory[key] = Memo(saved.lyrics, now, true) }
                return@withContext saved.lyrics
            }
        }
        val (found, final) = fetch(track)
        synchronized(memory) { memory[key] = Memo(found, now, final) }
        // Keep a plain or missing answer only when every source answered: a source that was
        // out of reach (Plex, away from home) might have synced lyrics next time.
        if (final || found?.synced == true) {
            runCatching {
                dir.mkdirs()
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(AppJson.encodeToString(Stored.serializer(), Stored(now, found)))
                tmp.renameTo(file)
            }
        }
        found
    }

    private suspend fun fetch(track: Track): Pair<Lyrics?, Boolean> {
        var final = true
        var best: Lyrics? = null
        fun take(l: Lyrics?) {
            if (l != null && (best == null || l.synced && best?.synced != true)) best = l
        }
        // Stem-only songs have no Plex item to ask.
        if (track.stemKey == null && plex.signedIn) {
            try {
                take(plex.lyrics(track.ratingKey))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                final = false
            }
        }
        if (best?.synced != true && settings.onlineLyrics.value) {
            try {
                take(lrclib(track))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                final = false
            }
        }
        return best to final
    }

    // ---- LRCLIB (https://lrclib.net/docs) ----------------------------------------------------

    private suspend fun lrclib(t: Track): Lyrics? {
        val secs = t.durationMs / 1000.0
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", t.artist)
            .addQueryParameter("track_name", t.title)
            .apply {
                if (t.album.isNotBlank()) addQueryParameter("album_name", t.album)
                if (secs > 0) addQueryParameter("duration", Math.round(secs).toString())
            }.build()
        call(exact)?.let { body ->
            toLyrics(AppJson.decodeFromString(LrclibTrack.serializer(), body))?.let { return it }
        }
        // No exact match (album or title spelled differently): search, and keep a version of the right length.
        val tries = listOf(t.artist to t.title, primaryArtist(t.artist) to cleanTitle(t.title)).distinct()
        for ((artist, title) in tries) {
            val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist).build()
            val body = call(url) ?: continue
            val hits = AppJson.decodeFromString(ListSerializer(LrclibTrack.serializer()), body)
                .filter { h -> secs <= 0 || h.duration == null || abs(h.duration - secs) <= 4 }
            val pick = hits.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: hits.firstOrNull { !it.plainLyrics.isNullOrBlank() || it.instrumental }
            toLyrics(pick)?.let { return it }
        }
        return null
    }

    /** The body, or null for 404. Anything else that isn't 200 is an error (and retried later). */
    private suspend fun call(url: HttpUrl): String? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(req).await().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw HttpException(r.code, "LRCLIB answered ${r.code}")
                else -> withContext(Dispatchers.IO) { r.body?.string() } ?: throw IOException("empty LRCLIB answer")
            }
        }
    }

    private fun toLyrics(h: LrclibTrack?): Lyrics? {
        h ?: return null
        if (h.instrumental) return Lyrics(source = "LRCLIB", instrumental = true)
        h.syncedLyrics?.takeIf { it.isNotBlank() }?.let { s -> LyricsParse.lrc(s, "LRCLIB")?.let { if (it.synced) return it } }
        return h.plainLyrics?.takeIf { it.isNotBlank() }?.let { LyricsParse.plain(it, "LRCLIB") }
    }

    /** Old lyrics files beyond the newest few thousand go; they're small, but every song played leaves one. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(KEEP_FILES).forEach { it.delete() }
    }

    companion object {
        private const val USER_AGENT = "Stemify/1.1 (https://github.com/kevinViers/stemify)"
        private const val RETRY_MS = 2 * 60_000L
        private const val NONE_TTL_MS = 7 * 24 * 3600_000L
        private const val KEEP_FILES = 5000

        private val NOISE = Regex("""\s*[(\[][^)\]]*\b(feat|ft|with|remaster|remastered|live|version|edit|mix|explicit|clean|mono|stereo|deluxe|bonus|demo|acoustic)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val DASH_NOISE = Regex("""\s+-\s+.*\b(remaster|remastered|version|live|edit|mix|mono|stereo)\b.*$""", RegexOption.IGNORE_CASE)
        private val SPLIT_ARTIST = Regex("""\s*(,|&|;|/|\bfeat\.?|\bft\.?|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)

        /** "Song (feat. X) [Remastered 2011]" -> "Song". */
        fun cleanTitle(s: String) = s.replace(NOISE, "").replace(DASH_NOISE, "").trim().ifEmpty { s }

        /** "A, B & C" -> "A". */
        fun primaryArtist(s: String) = s.split(SPLIT_ARTIST).firstOrNull { it.isNotBlank() }?.trim() ?: s
    }
}
