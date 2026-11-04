package com.stemify.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.ImageResult
import coil3.request.crossfade
import com.stemify.mobile.auto.HomeReach
import com.stemify.mobile.cache.Downloads
import com.stemify.mobile.cache.MediaCache
import com.stemify.mobile.core.Connectivity
import com.stemify.mobile.core.Settings
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.lyrics.LyricsRepo
import com.stemify.mobile.connect.Connect
import com.stemify.mobile.playback.Controls
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.stems.StemCoverage
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = Graph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp }))
                add(OfflineArt)
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(File(cacheDir, "art")).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(false)
            // Debug builds draw the UI to a software canvas for screenshots, which can't take hardware bitmaps.
            .allowHardware(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            .build()

    /**
     * Away from home, art the disk cache holds at another size instead of waiting on the server
     * for this exact one. Downloads only save one size; lists and Now Playing each ask for others.
     */
    private object OfflineArt : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data as? String
            if (url == null || graph.reach.lastKnown || "/photo/:/transcode" !in url) return chain.proceed()
            val disk = SingletonImageLoader.get(instance).diskCache ?: return chain.proceed()
            fun cached(u: String) = disk.openSnapshot(u)?.use { true } == true
            if (cached(url)) return chain.proceed()
            val parsed = url.toHttpUrlOrNull() ?: return chain.proceed()
            val thumb = parsed.queryParameter("url") ?: return chain.proceed()
            val want = parsed.queryParameter("width")?.toIntOrNull() ?: 600
            // Nearest size first, larger before smaller.
            val alt = (120..1440 step 120).sortedWith(compareBy({ kotlin.math.abs(it - want) }, { -it }))
                .firstNotNullOfOrNull { px -> graph.plex.artUrl(thumb, px)?.takeIf { it != url && cached(it) } }
                ?: return chain.proceed()
            return chain.withRequest(chain.request.newBuilder().data(alt).build()).proceed()
        }
    }

    companion object {
        lateinit var instance: App
            private set
        val graph: Graph get() = instance.graph
    }
}

/** Hand-wired singletons; there are few enough that a DI framework isn't worth it. */
class Graph(app: App) {
    val packageName: String = app.packageName
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(app)
    val net = Connectivity(app)

    /** Library calls: cached on disk and served from there when the server is unreachable. */
    val apiHttp: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(app.cacheDir, "http"), 64L * 1024 * 1024))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(offlineFallback)
        .addNetworkInterceptor(cacheable)
        .build()

    /** Media ranges; MediaCache is the cache. Long read timeout covers server-side Opus transcodes. */
    val mediaHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val imageHttp: OkHttpClient = mediaHttp.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

    /** Is Plex/stemserve answering? Asked by the car UI before every browse, and once at launch. */
    val reach = HomeReach(mediaHttp, settings, net, scope).also { r -> scope.launch { r.reachable() } }

    /**
     * Away from home, library GETs read the disk cache without waiting on LAN addresses that
     * only time out; a cache miss still tries the network, briefly, in case home came back.
     */
    private val awayHttp = apiHttp.newBuilder().addInterceptor { chain ->
        val req = chain.request()
        if (reach.lastKnown || req.method != "GET") return@addInterceptor chain.proceed(req)
        val cached = chain.proceed(req.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
        if (cached.code != 504) cached else {
            cached.close()
            chain.withConnectTimeout(2, TimeUnit.SECONDS).proceed(req)
        }
    }.build()

    val plex = PlexClient(awayHttp, settings)

    /** The car's Plex; the same client now that the phone also reads the cache away from home. */
    val carPlex = plex
    val stems = StemServer(apiHttp, settings, app.filesDir, scope)
    val cache = MediaCache(File(app.noBackupFilesDir, "media"), mediaHttp) {
        settings.cacheLimitGb.value.toLong() * 1024 * 1024 * 1024
    }
    val eq = EqController(app)
    /** Lyrics from Plex, else LRCLIB on the internet (reachable away from home too). */
    val lyrics = LyricsRepo(
        mediaHttp.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build(),
        plex, settings, app.filesDir, scope,
    )
    /** When albums first came out, from MusicBrainz, for On This Day. */
    val releaseDates = com.stemify.mobile.plex.ReleaseDates(mediaHttp.newBuilder().readTimeout(20, TimeUnit.SECONDS).build(), app.filesDir)
    val downloads = Downloads(app, settings, plex, stems, cache, net, lyrics, scope)
    val playback = PlaybackManager(app, settings, plex, stems, cache, downloads, eq, scope) { reach.lastKnown }
    val coverage = StemCoverage(plex, stems, settings, app.filesDir, scope)
    /** Stemify Connect: nearby devices control this phone, and this phone controls them. */
    val connect = Connect(app, settings, playback, eq, plex, stems, scope).also { it.start() }
    /** The player the screens drive: this phone's, or the nearby device's being controlled. */
    val controls: Controls get() = connect.controls

    private companion object {
        /**
         * Store everything, always revalidate. Plex marks every response "no-cache", which OkHttp
         * stores but then never serves without the network, so offline the cache came up empty.
         */
        val cacheable = okhttp3.Interceptor { chain ->
            val resp = chain.proceed(chain.request())
            val cc = resp.header("Cache-Control")?.trim()?.lowercase()
            if ((cc == null || cc == "no-cache") && chain.request().method == "GET" && resp.isSuccessful) {
                resp.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=0").build()
            } else resp
        }

        /** On network failure, answer GETs from the disk cache if it has them. */
        val offlineFallback = okhttp3.Interceptor { chain ->
            val req = chain.request()
            try {
                chain.proceed(req)
            } catch (e: IOException) {
                if (req.method != "GET") throw e
                val cached = chain.proceed(req.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
                if (cached.code == 504) {
                    cached.close()
                    throw e
                }
                cached
            }
        }
    }
}
