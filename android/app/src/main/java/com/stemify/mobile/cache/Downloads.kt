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
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.plex.Track
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
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "downloads.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<Map<String, DownloadRecord>> = _records.asStateFlow()
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

    fun download(tracks: List<Track>) {
        val add = tracks.filter { _records.value[it.ratingKey]?.let { r -> !r.done && r.error == null } != true && !isDownloaded(it.ratingKey) }
        if (add.isEmpty()) return
        update { m -> m + add.associate { it.ratingKey to DownloadRecord(it) } }
        add.forEach { enqueue(it.ratingKey) }
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    fun remove(tracks: List<Track>) {
        val keys = tracks.map { it.ratingKey }.toSet()
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
            val sources = if (entry != null) {
                val q = stems.effectiveQuality(settings.stemQuality.value)
                stems.prepare(entry, q)
                entry.stems.map { PlaybackManager.stemKey(entry, it.file, it.size, q) to stems.fileUrl(entry, it, q) }
            } else {
                val part = t.partKey ?: throw IOException("No file on the server")
                listOf(PlaybackManager.plexKey(t) to plex.partUrl(part))
            }
            update { m -> m + (key to rec.copy(keys = sources.map { it.first }, stems = entry != null)) }
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

    companion object {
        private val LIST = kotlinx.serialization.builtins.ListSerializer(DownloadRecord.serializer())
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
