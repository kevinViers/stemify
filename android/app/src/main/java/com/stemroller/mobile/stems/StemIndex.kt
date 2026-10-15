package com.stemroller.mobile.stems

import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.abs

@Serializable
data class StemFile(val name: String, val file: String, val size: Long, val mtime: Long = 0)

@Serializable
data class StemEntry(
    val key: String,
    val source: String,
    val duration: Double? = null,
    val samplerate: Int? = null,
    val model: String? = null,
    val finished: Double? = null,
    val stems: List<StemFile> = emptyList(),
) {
    /** "Artist/Album/01 Song" -> "01 Song" etc., for stem-only browsing. */
    val title get() = key.substringAfterLast('/')
    val folder get() = key.substringBeforeLast('/', "")
}

@Serializable
data class StemIndexFile(val version: Int = 1, val tracks: List<StemEntry> = emptyList())

/**
 * Maps Plex file paths to stem folders. The stems folder mirrors the music folder, so the
 * source path relative to the music root identifies a track; Plex reports absolute paths
 * as its container sees them, so the root is unknown up front.
 *
 * Matching tries every suffix of the Plex path, longest first. A suffix that is only a
 * file name, or whose duration disagrees, is accepted only under a root prefix learned
 * from an earlier, longer match, so "Intro.flac" in one album can't grab another's stems.
 */
class StemIndex(val entries: List<StemEntry>, roots: Collection<String> = emptyList()) {
    private val bySource = HashMap<String, StemEntry>(entries.size * 2)
    private val learned = LinkedHashSet(roots)

    init {
        for (e in entries) bySource.putIfAbsent(norm(e.source), e)
    }

    val size get() = entries.size
    val roots: Set<String> @Synchronized get() = learned.toSet()

    /** Returns the entry for a Plex part path, learning the path's root on a confident match. */
    @Synchronized
    fun find(plexPath: String?, durationMs: Long = 0): StemEntry? {
        if (plexPath.isNullOrEmpty() || entries.isEmpty()) return null
        val path = norm(plexPath)
        for (root in learned) {
            if (path.startsWith(root)) bySource[path.substring(root.length)]?.let { return it }
        }
        val parts = path.split('/')
        for (i in 1 until parts.size) {
            val rel = parts.subList(i, parts.size).joinToString("/")
            val hit = bySource[rel] ?: continue
            val deep = parts.size - i >= 2
            if (deep && durationMatches(hit, durationMs)) {
                learned += parts.subList(0, i).joinToString("/", postfix = "/")
                return hit
            }
        }
        return null
    }

    private fun durationMatches(e: StemEntry, durationMs: Long): Boolean {
        val d = e.duration ?: return true
        if (durationMs <= 0) return true
        return abs(d * 1000 - durationMs) < 3000
    }

    companion object {
        /** NFC, forward slashes, no extension, lower case. */
        fun norm(path: String): String {
            var p = Normalizer.normalize(path, Normalizer.Form.NFC).replace('\\', '/')
            val slash = p.lastIndexOf('/')
            val dot = p.lastIndexOf('.')
            if (dot > slash + 1) p = p.substring(0, dot)
            return p.lowercase()
        }
    }
}
