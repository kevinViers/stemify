package com.stemroller.mobile.stems

import android.net.Uri
import com.stemroller.mobile.core.AppJson
import com.stemroller.mobile.core.HttpException
import com.stemroller.mobile.core.Settings
import com.stemroller.mobile.core.StemQuality
import com.stemroller.mobile.core.await
import com.stemroller.mobile.core.awaitBody
import com.stemroller.mobile.plex.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

sealed interface StemServerStatus {
    data object Unknown : StemServerStatus
    data class Online(val tracks: Int, val transcode: Boolean) : StemServerStatus
    data class Offline(val reason: String, val cachedTracks: Int) : StemServerStatus
}

@Serializable
private data class StemRequest(val paths: List<String>)

@Serializable
private data class ServerInfo(val name: String = "", val version: Int = 0, val tracks: Int = 0, val ffmpeg: Boolean = false)

/** Client for server/stemserve.py: the stems index, stem file URLs and waveforms. */
class StemServer(
    private val http: OkHttpClient,
    private val settings: Settings,
    private val dir: File,
    private val scope: CoroutineScope,
) {
    private val indexFile = File(dir, "stem_index.json")
    private val etagFile = File(dir, "stem_index.etag")
    private val refreshLock = Mutex()

    private val _index = MutableStateFlow(StemIndex(emptyList()))
    val index: StateFlow<StemIndex> = _index.asStateFlow()
    private val _status = MutableStateFlow<StemServerStatus>(StemServerStatus.Unknown)
    val status: StateFlow<StemServerStatus> = _status.asStateFlow()
    private var transcode = false

    private val base get() = settings.stemUrl.value.trimEnd('/')
    private fun roots() = settings.stemRoots.value.split('\n').filter { it.isNotEmpty() }

    /** When the index was last checked against the server (epoch ms), 0 before the first check. */
    private val _checkedAt = MutableStateFlow(0L)
    val checkedAt: StateFlow<Long> = _checkedAt.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (indexFile.exists()) {
                    val parsed = AppJson.decodeFromString(StemIndexFile.serializer(), indexFile.readText())
                    _index.value = StemIndex(parsed.tracks, roots())
                }
            }
            // stemd on the server separates new music around the clock; look for its new stems
            // every minute. Unchanged indexes cost one 304.
            while (true) {
                refresh()
                delay(CHECK_MS)
            }
        }
    }

    private fun Request.Builder.auth(): Request.Builder {
        val t = settings.stemToken.value
        if (t.isNotEmpty()) header("X-Stem-Token", t)
        return this
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            try {
                val info = AppJson.decodeFromString(
                    ServerInfo.serializer(),
                    http.newCall(Request.Builder().url("$base/api/info").auth().build()).awaitBody(),
                )
                transcode = info.ffmpeg
                val etag = if (!force && etagFile.exists() && indexFile.exists()) etagFile.readText() else null
                val req = Request.Builder().url("$base/api/index").auth()
                    .apply { if (etag != null) header("If-None-Match", etag) }.build()
                http.newCall(req).await().use { r ->
                    when {
                        r.code == 304 -> Unit
                        r.isSuccessful -> {
                            val body = r.body?.string().orEmpty()
                            val parsed = AppJson.decodeFromString(StemIndexFile.serializer(), body)
                            _index.value = StemIndex(parsed.tracks, roots())
                            indexFile.writeText(body)
                            r.header("ETag")?.let { etagFile.writeText(it) }
                        }
                        else -> throw HttpException(r.code, "stemserve answered ${r.code}")
                    }
                }
                _status.value = StemServerStatus.Online(_index.value.size, info.ffmpeg)
                _checkedAt.value = System.currentTimeMillis()
            } catch (e: Exception) {
                _status.value = StemServerStatus.Offline(e.message ?: e.javaClass.simpleName, _index.value.size)
            }
        }
    }

    fun find(track: Track): StemEntry? {
        val idx = _index.value
        track.stemKey?.let { k -> return idx.entries.firstOrNull { it.key == k } }
        val hit = idx.find(track.partFile, track.durationMs)
        val learned = idx.roots.joinToString("\n")
        if (learned != settings.stemRoots.value) settings.stemRoots.value = learned
        return hit
    }

    /** Format the server will actually send: Opus needs ffmpeg there. */
    fun effectiveQuality(q: StemQuality) = if (q.fmt == "opus" && !transcode && _status.value is StemServerStatus.Online) StemQuality.Lossless else q

    fun fileUrl(entry: StemEntry, stem: StemFile, quality: StemQuality): String {
        val b = Uri.parse(base).buildUpon().appendPath("file")
        entry.key.split('/').forEach { b.appendPath(it) }
        b.appendPath(stem.file)
        if (quality.fmt == "opus") b.appendQueryParameter("fmt", "opus").appendQueryParameter("br", quality.kbps.toString())
        settings.stemToken.value.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("token", it) }
        return b.build().toString()
    }

    /** Asks the server to start the Opus transcodes now, so the stems are ready when we need them. */
    fun prepare(entry: StemEntry, quality: StemQuality) {
        if (quality.fmt != "opus") return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val url = Uri.parse("$base/api/prepare").buildUpon().appendQueryParameter("key", entry.key)
                    .appendQueryParameter("fmt", "opus").appendQueryParameter("br", quality.kbps.toString()).build()
                http.newCall(Request.Builder().url(url.toString()).auth().post(ByteArray(0).toRequestBody(null)).build())
                    .await().close()
            }
        }
    }

    /**
     * "Stem Now": asks stemd (through stemserve) to separate these songs before the rest of the
     * library. Returns how many it queued: the ones with no stems yet.
     */
    suspend fun stemNow(tracks: List<Track>): Int = withContext(Dispatchers.IO) {
        val paths = tracks.filter { find(it) == null }.mapNotNull { it.partFile }.distinct()
        if (paths.isEmpty()) return@withContext 0
        val body = AppJson.encodeToString(StemRequest.serializer(), StemRequest(paths)).toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/stem").auth().post(body).build()).await().use { r ->
            if (!r.isSuccessful) {
                val msg = runCatching { AppJson.parseToJsonElement(r.body!!.string()).jsonObject["error"]!!.jsonPrimitive.content }.getOrNull()
                throw HttpException(r.code, when {
                    r.code == 404 -> "stemserve needs updating for Stem Now"
                    msg != null -> msg
                    else -> "stemserve answered ${r.code}"
                })
            }
        }
        paths.size
    }

    /** Per-stem RMS envelopes (0..1 of full scale), [buckets] values each. */
    suspend fun peaks(entry: StemEntry, buckets: Int): Map<String, FloatArray>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = Uri.parse("$base/api/peaks").buildUpon().appendQueryParameter("key", entry.key)
                .appendQueryParameter("n", buckets.toString()).build()
            val body = http.newCall(Request.Builder().url(url.toString()).auth().build()).awaitBody()
            val stems = AppJson.parseToJsonElement(body).jsonObject["stems"] as JsonObject
            stems.mapValues { (_, arr) ->
                val a = arr.jsonArray
                FloatArray(a.size) { a[it].jsonPrimitive.int / 1000f }
            }
        }.getOrNull()
    }

    private companion object {
        const val CHECK_MS = 60_000L
    }
}
