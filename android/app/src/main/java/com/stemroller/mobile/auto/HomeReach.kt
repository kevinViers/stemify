package com.stemroller.mobile.auto

import com.stemroller.mobile.core.Connectivity
import com.stemroller.mobile.core.Settings
import com.stemroller.mobile.core.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Whether Plex or stemserve answers right now. In the car the phone is usually on mobile
 * data and the LAN addresses just time out, so the car UI asks this first (a quick probe,
 * remembered briefly) and goes straight to cached data when home is out of reach.
 */
class HomeReach(http: OkHttpClient, private val settings: Settings, net: Connectivity, scope: CoroutineScope) {
    private val probe = http.newBuilder()
        .cache(null)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Mutex()
    @Volatile private var checkedAt = 0L

    private val _home = MutableStateFlow(true)

    /** The last answer; true until a probe says otherwise. Changes as the phone joins or leaves home Wi-Fi. */
    val home: StateFlow<Boolean> = _home.asStateFlow()
    val lastKnown get() = _home.value

    init {
        // A new network (home Wi-Fi in range, or lost) invalidates the answer; ask again once it settles,
        // so the car's lists can switch over without anyone browsing first.
        scope.launch {
            merge(net.online.drop(1), net.unmetered.drop(1)).collect {
                checkedAt = 0
                delay(1500)
                reachable()
            }
        }
    }

    suspend fun reachable(): Boolean = lock.withLock {
        if (System.nanoTime() - checkedAt < TTL_NS && checkedAt != 0L) return lastKnown
        val ok = coroutineScope {
            val plex = async { answers(settings.plexUrl.value.trimEnd('/') + "/identity") }
            val stems = async { answers(settings.stemUrl.value.trimEnd('/') + "/api/info") }
            plex.await() || stems.await()
        }
        _home.value = ok
        checkedAt = System.nanoTime()
        ok
    }

    private suspend fun answers(url: String): Boolean = runCatching {
        probe.newCall(Request.Builder().url(url).build()).await().use { it.code < 500 }
    }.getOrDefault(false)

    private companion object {
        const val TTL_NS = 20_000_000_000L
    }
}
