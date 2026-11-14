package com.stemify.mobile.plex

import com.stemify.mobile.core.AppJson
import com.stemify.mobile.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How the phone is reaching Plex right now. */
enum class PlexLink(val label: String) { Local("Local"), Remote("Remote"), Relay("Plex Relay"), None("Not Reachable") }

/**
 * Finds a working address for the Plex server, at home or away, the way Plex's own apps do:
 * plex.tv lists the server's connections (LAN, public and relay, each an https plex.direct
 * address), and the first that answers as this server wins, in that order. The address in
 * settings (the LAN one) is tried first and stays what every URL is built from, so the HTTP,
 * art and media caches keep one key per item; [interceptor] sends each request to the
 * address found instead.
 */
class PlexRoute(private val settings: Settings, networkChanges: Flow<Any?>, scope: CoroutineScope) {
    private val probeHttp = OkHttpClient.Builder()
        .connectTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_MS + 500, TimeUnit.MILLISECONDS)
        .build()
    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var resolvedAt = 0L
    @Volatile private var live: HttpUrl? = null

    private val _link = MutableStateFlow(PlexLink.Local)
    /** The last answer. Local until the first look says otherwise. */
    val link: StateFlow<PlexLink> = _link.asStateFlow()

    init {
        // Joining or leaving a network changes which address works: look again once it settles.
        scope.launch {
            networkChanges.collect {
                resolvedAt = 0
                delay(1500)
                runCatching { resolve() }
            }
        }
        // A sign-out or a new address in settings starts over.
        scope.launch { merge(settings.plexToken.flow.drop(1), settings.plexUrl.flow.drop(1)).collect { forget() } }
    }

    private val home: HttpUrl? get() = settings.plexUrl.value.trimEnd('/').toHttpUrlOrNull()

    private fun forget() {
        resolvedAt = 0
        live = null
    }

    /** The base to send Plex requests to, or null if none answers. Quick when recently checked. */
    suspend fun resolve(): HttpUrl? = lock.withLock {
        if (resolvedAt != 0L && System.nanoTime() - resolvedAt < TTL_NS) return live
        // Keep plex.tv's list fresh, so the first time away from home it's already known.
        if (stale() && settings.plexToken.value.isNotEmpty()) refreshConnections()
        var found = probeAll(candidates())
        // Nothing answered from what we knew: the public address may have changed, so ask plex.tv.
        if (found == null && refreshConnections()) found = probeAll(candidates())
        live = found?.first
        _link.value = found?.second ?: PlexLink.None
        resolvedAt = System.nanoTime()
        live
    }

    /** True if Plex answers at some address right now. */
    suspend fun reachable(): Boolean = resolve() != null

    private fun stale() = System.currentTimeMillis() - settings.plexConnectionsAt.value > REFRESH_MS

    private fun candidates(): List<Pair<HttpUrl, PlexLink>> {
        val out = ArrayList<Pair<HttpUrl, PlexLink>>()
        home?.let { out += it to PlexLink.Local }
        for (line in settings.plexConnections.value.lines()) {
            val (kind, uri) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: continue
            val url = uri.trimEnd('/').toHttpUrlOrNull() ?: continue
            val link = runCatching { PlexLink.valueOf(kind) }.getOrNull() ?: continue
            if (out.none { it.first == url }) out += url to link
        }
        return out
    }

    /**
     * Probes every candidate at once and takes the first in priority order that answers as
     * our server, so a quick LAN answer wins without waiting, and away from home the wait is
     * at most one probe timeout.
     */
    private suspend fun probeAll(cands: List<Pair<HttpUrl, PlexLink>>): Pair<HttpUrl, PlexLink>? {
        if (cands.isEmpty()) return null
        val machine = settings.plexMachineId.value
        // Not this coroutine's children: the slower probes run out their timeout on their own
        // instead of holding up an answer already found.
        val jobs = cands.map { (url, _) -> probes.async { identity(url) } }
        for ((i, job) in jobs.withIndex()) {
            val id = job.await() ?: continue
            // A LAN address elsewhere (another house's 192.168.86.2) may be some other server.
            if (machine.isNotEmpty() && id != machine) continue
            if (machine.isEmpty()) settings.plexMachineId.value = id
            return cands[i]
        }
        return null
    }

    private fun identity(base: HttpUrl): String? = runCatching {
        val req = Request.Builder().url(base.newBuilder().addPathSegment("identity").build())
            .header("Accept", "application/json").build()
        probeHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            AppJson.decodeFromString(PlexResponse.serializer(), r.body!!.string()).container.machineIdentifier
        }
    }.getOrNull()

    /** Asks plex.tv for the server's connections and saves them. False if it couldn't. */
    suspend fun refreshConnections(): Boolean = withContext(Dispatchers.IO) {
        val token = settings.plexToken.value
        if (token.isEmpty()) return@withContext false
        runCatching {
            val req = Request.Builder().url(RESOURCES)
                .header("Accept", "application/json")
                .header("X-Plex-Token", token)
                .header("X-Plex-Client-Identifier", settings.clientId)
                .header("X-Plex-Product", PlexClient.PRODUCT)
                .header("X-Plex-Platform", "Android")
                .build()
            val all = probeHttp.newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("plex.tv answered ${r.code}")
                    AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PlexResource.serializer()), r.body!!.string())
                }
            val servers = all.filter { "server" in it.provides.split(',') }
            val machine = settings.plexMachineId.value
            val server = servers.firstOrNull { it.clientIdentifier == machine }
                ?: servers.singleOrNull { it.owned }
                ?: return@runCatching false
            if (machine.isEmpty()) settings.plexMachineId.value = server.clientIdentifier
            // LAN first (cheapest), then the public address, then Plex's relay (slow, capped).
            val lines = server.connections
                .sortedBy { if (it.relay) 2 else if (it.local) 0 else 1 }
                .map { c -> "${if (c.relay) PlexLink.Relay else if (c.local) PlexLink.Local else PlexLink.Remote} ${c.uri}" }
            settings.plexConnections.value = lines.joinToString("\n")
            settings.plexConnectionsAt.value = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }

    /**
     * Sends requests built on the settings address to the address that works. A request that
     * only reads the cache looks under each address, since a response is cached under the one
     * it came from. One that fails gets one more try after looking again.
     */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val home = home
        if (home == null || req.url.scheme != home.scheme || req.url.host != home.host || req.url.port != home.port) {
            return@Interceptor chain.proceed(req)
        }
        if (req.cacheControl.onlyIfCached) {
            val bases = listOfNotNull(live) + candidates().map { it.first }
            var last: Response? = null
            for (b in bases.distinct()) {
                last?.close()
                last = chain.proceed(req.newBuilder().url(rebase(req.url, b)).build())
                if (last.code != 504) return@Interceptor last
            }
            return@Interceptor last ?: chain.proceed(req)
        }
        val base = runBlocking { resolve() } ?: home
        try {
            chain.proceed(req.newBuilder().url(rebase(req.url, base)).build())
        } catch (e: IOException) {
            // Only reads are safe to send twice; a playlist edit may have landed before the failure.
            if (chain.call().isCanceled() || req.method != "GET") throw e
            forget()
            val again = runBlocking { resolve() }
            if (again == null || again == base) throw e
            chain.proceed(req.newBuilder().url(rebase(req.url, again)).build())
        }
    }

    private fun rebase(url: HttpUrl, base: HttpUrl): HttpUrl =
        if (url.scheme == base.scheme && url.host == base.host && url.port == base.port) url
        else url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()

    private companion object {
        const val RESOURCES = "https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1"
        const val PROBE_MS = 2500L
        // A network change or a failed request looks again sooner.
        const val TTL_NS = 600_000_000_000L
        const val REFRESH_MS = 6 * 3600 * 1000L
    }
}

@Serializable
internal data class PlexResource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val owned: Boolean = false,
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable
internal data class PlexConnection(
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
)
