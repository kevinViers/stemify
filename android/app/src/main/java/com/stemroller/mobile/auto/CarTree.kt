package com.stemroller.mobile.auto

import java.text.Normalizer

/**
 * Media IDs for the Android Auto tree. Containers are `kind:key`; playable rows carry the
 * container they were picked from, so choosing a song queues the rest of its list after it.
 * The container goes last because search queries can contain anything.
 */
object CarIds {
    const val ROOT = "root"
    const val DOWNLOADS = "tab:downloads"
    const val PLAYLISTS = "tab:playlists"
    const val ALBUMS = "tab:albums"
    const val ARTISTS = "tab:artists"
    val TABS = listOf(DOWNLOADS, PLAYLISTS, ALBUMS, ARTISTS)

    fun playlist(key: String) = "playlist:$key"
    fun album(key: String) = "album:$key"
    fun artist(key: String) = "artist:$key"
    fun albumLetter(letter: String) = "albums:$letter"
    fun artistLetter(letter: String) = "artists:$letter"
    fun search(query: String) = "search:$query"

    fun play(container: String, trackKey: String) = "play|$trackKey|$container"
    fun shuffle(container: String) = "shuffle|$container"

    sealed interface Pick {
        val container: String
        data class Song(override val container: String, val trackKey: String) : Pick
        data class Shuffle(override val container: String) : Pick
    }

    fun parsePick(id: String): Pick? = when {
        id.startsWith("play|") -> {
            val rest = id.removePrefix("play|")
            val bar = rest.indexOf('|')
            if (bar <= 0 || bar == rest.lastIndex) null else Pick.Song(rest.substring(bar + 1), rest.substring(0, bar))
        }
        id.startsWith("shuffle|") -> id.removePrefix("shuffle|").takeIf { it.isNotEmpty() }?.let { Pick.Shuffle(it) }
        else -> null
    }

    /** (kind, key) of a container id such as `album:123`. */
    fun split(id: String): Pair<String, String>? {
        val c = id.indexOf(':')
        return if (c <= 0) null else id.substring(0, c) to id.substring(c + 1)
    }
}

object CarTree {
    /**
     * Lists longer than this split into A–Z folders: turning the commander knob through a few
     * dozen rows is fine, through hundreds it isn't (and Auto stops long scrolls while moving).
     */
    const val FLAT_MAX = 40

    /** Recently added albums shown above the A–Z folders. */
    const val RECENT_ALBUMS = 12

    /** "#" for anything that doesn't start with a letter; accents fold to their base letter. */
    fun letterOf(sortTitle: String): String {
        val c = Normalizer.normalize(sortTitle.trim(), Normalizer.Form.NFD).firstOrNull { it.isLetterOrDigit() } ?: return "#"
        val u = c.uppercaseChar()
        return if (u in 'A'..'Z') u.toString() else "#"
    }

    /** Items by first letter, "#" first then A–Z, each keeping the incoming order. */
    fun <T> buckets(items: List<T>, sortTitle: (T) -> String): Map<String, List<T>> =
        items.groupBy { letterOf(sortTitle(it)) }.toSortedMap(compareBy { if (it == "#") "" else it })

    fun countLabel(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    /** Loose title match for voice requests: case, accents and punctuation don't matter. */
    fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' }
        .replace(Regex("\\s+"), " ")
        .trim()

    fun matches(title: String, query: String): Boolean {
        val t = norm(title)
        val q = norm(query)
        return q.isNotEmpty() && (t == q || t.removePrefix("the ") == q.removePrefix("the "))
    }
}
