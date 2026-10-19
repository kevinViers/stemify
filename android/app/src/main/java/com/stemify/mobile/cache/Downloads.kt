package com.stemify.mobile.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.stemify.mobile.App
import com.stemify.mobile.MainActivity
import com.stemify.mobile.R
import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Connectivity
import com.stemify.mobile.core.Settings
import com.stemify.mobile.lyrics.LyricsRepo
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.playback.Stems
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.Playlist
import com.stemify.mobile.plex.Track
import com.stemify.mobile.stems.StemEntry
import com.stemify.mobile.stems.StemFile
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class DownloadRecord(
    val track: Track,
    val keys: List<String> = emptyList(),
    val stems: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
    val added: Long = System.currentTimeMillis(),
    /** Downloaded as a song, album or artist. False when it's only here for a downloaded playlist. */
    val direct: Boolean = true,
    /** Stem name for each of [keys] ("mix" for an original file). Empty in records from before it was kept. */
    val names: List<String> = emptyList(),
)

/** A finished download, exactly as saved: what to play with no server. */
class OfflineCopy(
    /** The stems folder, as it was when downloaded; null for an original file. */
    val entry: StemEntry?,
    /** (stem name, cache key, URL to refill from if a block ever goes missing), in mixer order. */
    val sources: List<Triple<String, String, String>>,
)

/** A playlist kept whole offline: its songs in playlist order. */
@Serializable
data class DownloadedPlaylist(
    val playlist: Playlist,
    val keys: List<String>,
    val added: Long = System.currentTimeMillis(),
)

/**
 * Offline copies: the track's stems (or the original file) fully fetched into
 * [MediaCache] and pinned, plus the metadata needed to browse them with no server.
 */
class Downloads(
    private val context: Context,
    private val settings: Settings,
    private val plex: PlexClient,
    private val stems: StemServer,
    private val cache: MediaCache,
    private val net: Connectivity,
    private val lyrics: LyricsRepo,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "downloads.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, DownloadRecord>> = _records.asStateFlow()
    private val playlistFile = File(context.filesDir, "download-playlists.json")
    private val _playlists = MutableStateFlow(loadPlaylists())
    /** ratingKey -> playlist, for playlists downloaded as a whole. */
    val playlists: StateFlow<Map<String, DownloadedPlaylist>> = _playlists.asStateFlow()
    /** ratingKey -> 0..1 for queued and running downloads. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val cancelled = HashSet<String>()

    init {
        repeat(2) { scope.launch(Dispatchers.IO) { for (key in queue) run(key) } }
        // Resume anything interrupted last time.
        _records.value.values.filter { !it.done && it.error == null }.forEach { enqueue(it.track.ratingKey) }
    }

    fun isDownloaded(ratingKey: String) = _records.value[ratingKey]?.done == true

    /**
     * The files this song was downloaded as, when every one is fully on the phone. Playback uses
     * these before anything else: working the files out again from the stems index can land on
     * different ones (stems that appeared or changed since, another quality, an index not loaded
     * yet), which then have to come from the server.
     */
    fun offlineCopy(track: Track): OfflineCopy? {
        val r = _records.value[track.ratingKey]?.takeIf { it.done && it.keys.isNotEmpty() } ?: return null
        if (!r.keys.all { cache.isComplete(it) }) return null
        if (!r.stems) {
            val url = plex.partUrl(track.partKey ?: "/")
            return OfflineCopy(null, r.keys.map { Triple("mix", it, url) })
        }
        val parts = r.keys.map { PlaybackManager.parseStemKey(it) ?: return null }
        val folder = parts.first().entryKey
        if (parts.any { it.entryKey != folder }) return null
        val known = stems.index.value.entries.firstOrNull { it.key == folder }
        val files = parts.mapIndexed { i, p ->
            val name = r.names.getOrNull(i).takeIf { r.names.size == r.keys.size }
                ?: known?.stems?.firstOrNull { it.file == p.file }?.name
                ?: p.file.substringBeforeLast('.')
            StemFile(name, p.file, p.size)
        }
        val entry = StemEntry(folder, known?.source ?: "", known?.duration, known?.samplerate, known?.model, known?.finished, files)
        val order = Stems.sort(files.map { it.name })
        val sources = files.indices.sortedBy { order.indexOf(files[it].name) }
            .map { i -> Triple(files[i].name, r.keys[i], stems.fileUrl(entry, files[i], parts[i].quality)) }
        return OfflineCopy(entry, sources)
    }

    /** Downloaded directly (not just as part of a playlist), so it shows under its album. */
    fun isDirect(ratingKey: String) = _records.value[ratingKey]?.direct == true

    /** Songs, albums and artists: each song files under its album in Downloads. */
    fun download(tracks: List<Track>) = add(tracks, direct = true)

    /** Keeps the playlist whole in Downloads; its songs don't show up as albums. */
    fun downloadPlaylist(playlist: Playlist, tracks: List<Track>) {
        updatePlaylists { m -> m + (playlist.ratingKey to DownloadedPlaylist(playlist.copy(trackCount = tracks.size), tracks.map { it.ratingKey }.distinct(), m[playlist.ratingKey]?.added ?: System.currentTimeMillis())) }
        add(tracks, direct = false)
    }

    /** Brings a downloaded playlist up to date with what's on the server now. No-op for others. */
    fun syncPlaylist(playlist: Playlist, tracks: List<Track>) {
        val old = _playlists.value[playlist.ratingKey] ?: return
        val keys = tracks.map { it.ratingKey }.distinct()
        if (keys == old.keys && old.playlist.title == playlist.title && old.playlist.thumb == playlist.thumb) return
        downloadPlaylist(playlist, tracks)
        dropOrphans(old.keys.toSet() - keys.toSet())
    }

    fun removePlaylist(ratingKey: String) {
        val old = _playlists.value[ratingKey] ?: return
        updatePlaylists { it - ratingKey }
        dropOrphans(old.keys.toSet())
    }

    /** Songs of a downloaded playlist that are on the phone, in playlist order. */
    fun playlistTracks(ratingKey: String): List<Track> {
        val recs = _records.value
        return _playlists.value[ratingKey]?.keys.orEmpty().mapNotNull { k -> recs[k]?.takeIf { it.done }?.track }
    }

    private fun inPlaylist(key: String) = _playlists.value.values.any { key in it.keys }

    /** Deletes songs nothing holds on to any more: not downloaded directly, not in a downloaded playlist. */
    private fun dropOrphans(keys: Set<String>) {
        val gone = keys.filter { k -> _records.value[k]?.direct == false && !inPlaylist(k) }
        if (gone.isNotEmpty()) delete(gone.toSet())
    }

    private fun add(tracks: List<Track>, direct: Boolean) {
        val recs = _records.value
        // Already here from a playlist: now it's wanted on its own too.
        val promote = if (direct) tracks.filter { recs[it.ratingKey]?.direct == false } else emptyList()
        if (promote.isNotEmpty()) update { m -> m + promote.mapNotNull { t -> m[t.ratingKey]?.let { t.ratingKey to it.copy(direct = true) } } }
        // New songs, plus failed ones to try again.
        val add = tracks.distinctBy { it.ratingKey }.filter { recs[it.ratingKey].let { r -> r == null || r.error != null } }
        if (add.isEmpty()) return
        update { m -> m + add.associate { t -> t.ratingKey to DownloadRecord(t, direct = direct || m[t.ratingKey]?.direct == true) } }
        add.forEach { enqueue(it.ratingKey) }
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    /**
     * Removes songs downloaded directly. Ones a downloaded playlist still needs stay on the phone,
     * they just leave their album in Downloads.
     */
    fun remove(tracks: List<Track>) {
        val keys = tracks.map { it.ratingKey }.toSet()
        val (kept, gone) = keys.partition { inPlaylist(it) }
        if (kept.isNotEmpty()) update { m -> m + kept.mapNotNull { k -> m[k]?.let { k to it.copy(direct = false) } } }
        if (gone.isNotEmpty()) delete(gone.toSet())
    }

    /** Everything: songs and playlists. */
    fun removeAll() {
        updatePlaylists { emptyMap() }
        delete(_records.value.keys.toSet())
    }

    private fun delete(keys: Set<String>) {
        synchronized(cancelled) { cancelled += keys }
        val gone = _records.value.filterKeys { it in keys }.values
        update { m -> m - keys }
        _progress.update { it - keys }
        scope.launch(Dispatchers.IO) { gone.forEach { r -> r.keys.forEach { cache.unpin(it) } } }
    }

    fun retryFailed() {
        val failed = _records.value.values.filter { it.error != null }
        update { m -> m + failed.associate { it.track.ratingKey to it.copy(error = null) } }
        failed.forEach { enqueue(it.track.ratingKey) }
        if (failed.isNotEmpty()) context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    private fun enqueue(key: String) {
        synchronized(cancelled) { cancelled -= key }
        _progress.update { it + (key to 0f) }
        queue.trySend(key)
    }

    private suspend fun run(key: String) {
        val rec = _records.value[key] ?: return
        if (rec.done) return
        try {
            if (settings.downloadsWifiOnly.value) net.unmetered.first { it }
            val t = rec.track
            val entry = stems.find(t)
            val names = entry?.stems?.map { it.name } ?: listOf("mix")
            val sources = if (entry != null) {
                val q = stems.effectiveQuality(settings.stemQuality.value)
                stems.prepare(entry, q)
                entry.stems.map { PlaybackManager.stemKey(entry, it.file, it.size, q) to stems.fileUrl(entry, it, q) }
            } else {
                val part = t.partKey ?: throw IOException("No file on the server")
                listOf(PlaybackManager.plexKey(t) to plex.partUrl(part))
            }
            update { m -> m + (key to rec.copy(keys = sources.map { it.first }, names = names, stems = entry != null)) }
            sources.forEachIndexed { i, (cacheKey, url) ->
                val res = cache.acquire(cacheKey) { url }
                try {
                    res.pinned = true
                    res.downloadAll(
                        progress = { got, len ->
                            val f = if (len > 0) got.toFloat() / len else 0f
                            _progress.update { it + (key to (i + f) / sources.size) }
                        },
                        cancelled = { synchronized(cancelled) { key in cancelled } },
                    )
                } finally {
                    cache.release(res)
                }
            }
            if (synchronized(cancelled) { key in cancelled }) return
            if (entry != null) stems.peaks(entry, PlaybackManager.WAVE_BUCKETS) // warms the HTTP cache for offline
            plex.artUrl(t.thumb, 600)?.let { url ->
                withContext(Dispatchers.Main) {
                    SingletonImageLoader.get(context).enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
            update { m -> if (key in m) m + (key to m.getValue(key).copy(done = true, error = null)) else m }
            // Lyrics too, kept on the phone for offline; not something to hold the download up for.
            scope.launch(Dispatchers.IO) { runCatching { lyrics.get(t) } }
        } catch (e: Exception) {
            if (synchronized(cancelled) { key in cancelled }) return
            update { m -> if (key in m) m + (key to m.getValue(key).copy(error = e.message ?: "Download failed")) else m }
        } finally {
            _progress.update { it - key }
        }
    }

    private fun update(f: (Map<String, DownloadRecord>) -> Map<String, DownloadRecord>) {
        synchronized(this) { _records.value = f(_records.value) }
        val snapshot = _records.value
        scope.launch(Dispatchers.IO) {
            synchronized(file) {
                runCatching {
                    val tmp = File(file.parentFile, "downloads.json.tmp")
                    tmp.writeText(AppJson.encodeToString(LIST, snapshot.values.toList()))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun load(): Map<String, DownloadRecord> = runCatching {
        AppJson.decodeFromString(LIST, file.readText()).associateBy { it.track.ratingKey }
    }.getOrDefault(emptyMap())

    private fun updatePlaylists(f: (Map<String, DownloadedPlaylist>) -> Map<String, DownloadedPlaylist>) {
        synchronized(this) { _playlists.value = f(_playlists.value) }
        val snapshot = _playlists.value
        scope.launch(Dispatchers.IO) {
            synchronized(playlistFile) {
                runCatching {
                    val tmp = File(playlistFile.parentFile, "download-playlists.json.tmp")
                    tmp.writeText(AppJson.encodeToString(PLAYLISTS, snapshot.values.toList()))
                    tmp.renameTo(playlistFile)
                }
            }
        }
    }

    private fun loadPlaylists(): Map<String, DownloadedPlaylist> = runCatching {
        AppJson.decodeFromString(PLAYLISTS, playlistFile.readText()).associateBy { it.playlist.ratingKey }
    }.getOrDefault(emptyMap())

    companion object {
        private val LIST = kotlinx.serialization.builtins.ListSerializer(DownloadRecord.serializer())
        private val PLAYLISTS = kotlinx.serialization.builtins.ListSerializer(DownloadedPlaylist.serializer())
    }
}

/** Keeps the process alive while downloads run, with progress in a notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, build(0, 0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job == null) {
            job = scope.launch {
                App.graph.downloads.progress.collectLatest { p ->
                    if (p.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(ID, build(p.size, p.values.average().toFloat()))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(left: Int, fraction: Float): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_stems)
            .setContentTitle(if (left > 0) "Downloading $left song${if (left == 1) "" else "s"}" else "Preparing downloads")
            .setProgress(1000, (fraction * 1000).toInt(), left == 0)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 7
    }
}
