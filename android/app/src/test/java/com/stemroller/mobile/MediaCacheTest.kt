package com.stemroller.mobile

import com.stemroller.mobile.cache.MediaCache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Streams a real stem from a locally running stemserve (server/stemserve.py) through the
 * block cache and checks every byte against the file on the share.
 */
class MediaCacheTest {
    private val stems = File("/var/mnt/unraid/Music/Stems")
    private lateinit var dir: File
    private lateinit var src: File
    private lateinit var url: String
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    @Before fun setUp() {
        val up = runCatching { URI("http://localhost:8765/api/info").toURL().readText() }.isSuccess
        assumeTrue("stemserve not running on :8765", up && stems.isDirectory)
        src = stems.walkTopDown().filter { it.name.endsWith(" - vocals.flac") }.minBy { it.length() }
        val rel = src.relativeTo(stems).invariantSeparatorsPath.split('/')
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        url = "http://localhost:8765/file/$rel"
        dir = Files.createTempDirectory("mediacache").toFile()
    }

    @After fun tearDown() {
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun expected(pos: Long, len: Int): ByteArray = RandomAccessFile(src, "r").use { f ->
        f.seek(pos)
        ByteArray(len).also { f.readFully(it) }
    }

    @Test fun randomReadsMatchTheFile() {
        val cache = MediaCache(dir, http) { Long.MAX_VALUE }
        val res = cache.acquire("test|vocals") { url }
        assertEquals(src.length(), res.size())
        val rnd = Random(42)
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (0 until 64).map {
            val len = 1 + rnd.nextInt(300_000)
            val pos = (rnd.nextDouble() * (src.length() - len)).toLong()
            pool.submit<Unit> {
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = res.readAt(pos + got, buf, got, len - got)
                    check(n > 0)
                    got += n
                }
                assertArrayEquals("at $pos+$len", expected(pos, len), buf)
            }
        }
        jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        // Past EOF
        assertEquals(-1, res.readAt(src.length(), ByteArray(10), 0, 10))
        cache.release(res)
    }

    @Test fun downloadPersistsAndSurvivesReopen() {
        val cache = MediaCache(dir, http) { Long.MAX_VALUE }
        val res = cache.acquire("dl|vocals") { url }
        res.pinned = true
        res.downloadAll({ _, _ -> }, { false })
        assertTrue(res.isComplete)
        cache.release(res)
        assertTrue(cache.isComplete("dl|vocals"))

        // A fresh cache over the same directory serves it without any network.
        val offline = MediaCache(dir, http) { Long.MAX_VALUE }
        val again = offline.acquire("dl|vocals") { "http://127.0.0.1:9/unreachable" }
        val buf = ByteArray(4096)
        val tail = src.length() - 4096
        assertEquals(4096, again.readAt(tail, buf, 0, 4096))
        assertArrayEquals(expected(tail, 4096), buf)
        offline.release(again)
    }

    @Test fun evictionSparesPinnedAndInUse() {
        val cache = MediaCache(dir, http) { 1L }
        val pinned = cache.acquire("pinned") { url }
        pinned.pinned = true
        pinned.downloadAll({ _, _ -> }, { false })
        cache.release(pinned)
        val loose = cache.acquire("loose") { url }
        loose.downloadAll({ _, _ -> }, { false })
        cache.release(loose)
        // Trimming runs on the cache's own thread; give it a moment.
        val deadline = System.currentTimeMillis() + 5000
        while (cache.isComplete("loose") && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse(cache.isComplete("loose"))
        assertTrue(cache.isComplete("pinned"))
    }
}
