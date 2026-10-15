package com.stemify.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import com.stemify.mobile.auto.HomeReach
import com.stemify.mobile.cache.Downloads
import com.stemify.mobile.cache.MediaCache
import com.stemify.mobile.core.Connectivity
import com.stemify.mobile.core.Settings
import com.stemify.mobile.eq.EqController
import com.stemify.mobile.playback.PlaybackManager
import com.stemify.mobile.plex.PlexClient
import com.stemify.mobile.stems.StemCoverage
import com.stemify.mobile.stems.StemServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(File(cacheDir, "art")).maxSizeBytes(512L * 1024 * 1024).build() }
            .crossfade(false)
            // Debug builds draw the UI to a software canvas for screenshots, which can't take hardware bitmaps.
            .allowHardware(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            .build()

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

    val plex = PlexClient(apiHttp, settings)

    /** Is Plex/stemserve answering? Asked by the car UI before every browse. */
    val reach = HomeReach(mediaHttp, settings, net, scope)

    /** The car's Plex: identical, except that away from home it reads the disk cache without trying the network. */
    val carPlex = PlexClient(
        apiHttp.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            if (reach.lastKnown || req.method != "GET") chain.proceed(req)
            else chain.proceed(req.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build())
        }.build(),
        settings,
    )
    val stems = StemServer(apiHttp, settings, app.filesDir, scope)
    val cache = MediaCache(File(app.noBackupFilesDir, "media"), mediaHttp) {
        settings.cacheLimitGb.value.toLong() * 1024 * 1024 * 1024
    }
    val eq = EqController(app)
    val playback = PlaybackManager(app, settings, plex, stems, cache, eq, scope)
    val downloads = Downloads(app, settings, plex, stems, cache, net, scope)
    val coverage = StemCoverage(plex, stems, settings, app.filesDir, scope)

    private companion object {
        /** Plex sends no cache headers; store everything, always revalidate. */
        val cacheable = Interceptor { chain ->
            val resp = chain.proceed(chain.request())
            if (resp.header("Cache-Control") == null && chain.request().method == "GET") {
                resp.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=0").build()
            } else resp
        }

        /** On network failure, answer GETs from the disk cache if it has them. */
        val offlineFallback = Interceptor { chain ->
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
