package com.stemify.mobile

import android.content.SharedPreferences
import com.stemify.mobile.core.Settings
import com.stemify.mobile.plex.PlexLink
import com.stemify.mobile.plex.PlexRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Finds the real Plex server at home and "away" (the LAN address swapped for one that never
 * answers). Needs PLEX_TOKEN in the environment and a reachable server; skips otherwise.
 */
class PlexRouteTest {
    private val token = System.getenv("PLEX_TOKEN").orEmpty()
    private val home = System.getenv("PLEX_URL") ?: "http://192.168.86.2:32400"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun done() = scope.cancel()

    private fun settings(url: String, machine: String = "") = Settings(MemPrefs()).also {
        it.plexUrl.value = url
        it.plexToken.value = token
        it.plexMachineId.value = machine
    }

    @Test fun homeIsLocalAndAdoptsTheMachineId() = runBlocking {
        assumeTrue("PLEX_TOKEN not set", token.isNotEmpty())
        val s = settings(home)
        val route = PlexRoute(s, emptyFlow(), scope)
        val base = route.resolve()
        assumeTrue("Plex not reachable at $home", base != null)
        assertEquals(home.trimEnd('/'), base.toString().trimEnd('/'))
        assertEquals(PlexLink.Local, route.link.value)
        assertTrue(s.plexMachineId.value.isNotEmpty())
        // The plex.tv list is fetched in the background of the first look, best first.
        assertTrue(s.plexConnections.value.lines().first().startsWith("Local "))
    }

    @Test fun awayFindsAPlexDirectAddressAndRequestsGoThere() = runBlocking {
        assumeTrue("PLEX_TOKEN not set", token.isNotEmpty())
        val atHome = settings(home)
        assumeTrue("Plex not reachable at $home", PlexRoute(atHome, emptyFlow(), scope).resolve() != null)
        val machine = atHome.plexMachineId.value
        val unreachable = "http://10.255.255.1:32400"
        val s = settings(unreachable, machine)
        val route = PlexRoute(s, emptyFlow(), scope)
        val base = route.resolve()
        assertTrue("found $base", base != null && base.host.endsWith(".plex.direct"))
        assertNotEquals(PlexLink.None, route.link.value)
        // A request built on the settings address lands on the found one.
        val http = OkHttpClient.Builder().addInterceptor(route.interceptor).build()
        http.newCall(Request.Builder().url("$unreachable/identity").header("Accept", "application/json").build())
            .execute().use { r ->
                assertEquals(200, r.code)
                assertEquals(base!!.host, r.request.url.host)
                assertTrue(r.body!!.string().contains(machine))
            }
    }

    @Test fun anotherServerAtTheAddressIsRejected() = runBlocking {
        assumeTrue("PLEX_TOKEN not set", token.isNotEmpty())
        val s = settings(home, machine = "not-this-server")
        s.plexConnectionsAt.value = System.currentTimeMillis() // no plex.tv: only the LAN address to try
        val route = PlexRoute(s, emptyFlow(), scope)
        assumeTrue(PlexRoute(settings(home), emptyFlow(), scope).resolve() != null)
        assertNull(route.resolve())
        assertEquals(PlexLink.None, route.link.value)
    }
}

/** SharedPreferences in memory, enough for Settings. */
private class MemPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { map.clear() }
        override fun commit(): Boolean { map.putAll(pending); return true }
        override fun apply() { map.putAll(pending) }
    }
}
