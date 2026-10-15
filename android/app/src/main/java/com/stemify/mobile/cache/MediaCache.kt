package com.stemify.mobile.cache

import com.stemify.mobile.core.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Base64
import java.util.BitSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

@Serializable
internal data class Meta(
    val length: Long = -1,
    val bits: String = "",
    val pinned: Boolean = false,
    val access: Long = 0,
)

/**
 * Disk cache for stems and original tracks. Each resource is a sparse file filled in
 * BLOCK-sized pieces with HTTP range requests: reads block only on the bytes they need,
 * a background prefetcher fills the rest in order, and seeks (FLAC without a seek table
 * bisects the file) fetch out of order. Pinned resources are downloads and never evicted.
 */
class MediaCache(
    private val dir: File,
    internal val http: OkHttpClient,
    private val limitBytes: () -> Long,
) {
    private val open = HashMap<String, CachedResource>()
    private val refs = HashMap<String, Int>()
    private val catalog = HashMap<String, CatalogEntry>()
    private val prefetchPool = Executors.newFixedThreadPool(6) { r -> Thread(r, "media-prefetch").apply { isDaemon = true } }
    private val trimPool = Executors.newSingleThreadExecutor { r -> Thread(r, "media-trim").apply { isDaemon = true } }
    private val _usage = MutableStateFlow(Usage(0, 0))
    val usage: StateFlow<Usage> = _usage.asStateFlow()

    data class Usage(val cachedBytes: Long, val pinnedBytes: Long)
    private class CatalogEntry(var bytes: Long, var access: Long, var pinned: Boolean)

    init {
        dir.mkdirs()
        trimPool.execute {
            dir.listFiles { f -> f.name.endsWith(".meta") }?.forEach { f ->
                val m = readMeta(f) ?: return@forEach
                val id = f.name.removeSuffix(".meta")
                synchronized(this) {
                    if (id !in catalog) catalog[id] = CatalogEntry(bitsOf(m).cardinality().toLong() * BLOCK, m.access, m.pinned)
                }
            }
            publishUsage()
        }
    }

    /** Opens (or reuses) a resource; pair every call with [release]. */
    @Synchronized
    fun acquire(key: String, url: () -> String): CachedResource {
        val id = idFor(key)
        refs[id] = (refs[id] ?: 0) + 1
        return open.getOrPut(id) { CachedResource(id, File(dir, "$id.data"), File(dir, "$id.meta"), url, this) }
    }

    @Synchronized
    fun release(res: CachedResource) {
        val n = (refs[res.id] ?: 1) - 1
        if (n > 0) {
            refs[res.id] = n
            return
        }
        refs.remove(res.id)
        open.remove(res.id)
        res.close()
        scheduleTrim()
    }

    fun isComplete(key: String): Boolean {
        val id = idFor(key)
        synchronized(this) { open[id]?.let { return it.isComplete } }
        val m = readMeta(File(dir, "$id.meta")) ?: return false
        return m.length >= 0 && bitsOf(m).cardinality() >= blocksFor(m.length) && File(dir, "$id.data").exists()
    }

    fun isPinned(key: String): Boolean = synchronized(this) { catalog[idFor(key)]?.pinned == true }

    /** Unpins a download so normal eviction can reclaim it. */
    fun unpin(key: String) {
        val id = idFor(key)
        synchronized(this) { open[id] }?.let { it.pinned = false; return }
        val f = File(dir, "$id.meta")
        val m = readMeta(f) ?: return
        writeMeta(f, m.copy(pinned = false))
        synchronized(this) { catalog[id]?.pinned = false }
        publishUsage()
        scheduleTrim()
    }

    /** Deletes every resource that isn't pinned or in use. */
    fun clearUnpinned() {
        trimPool.execute { evict(keepBytes = 0) }
    }

    internal fun submitPrefetch(task: Runnable) = prefetchPool.execute(task)

    internal fun update(id: String, bytes: Long, access: Long, pinned: Boolean) {
        synchronized(this) {
            val e = catalog.getOrPut(id) { CatalogEntry(0, 0, false) }
            e.bytes = bytes; e.access = access; e.pinned = pinned
        }
        publishUsage()
    }

    private fun publishUsage() {
        synchronized(this) {
            var all = 0L
            var pinned = 0L
            for (e in catalog.values) {
                all += e.bytes
                if (e.pinned) pinned += e.bytes
            }
            _usage.value = Usage(all, pinned)
        }
    }

    private var trimQueued = false

    private fun scheduleTrim() {
        synchronized(this) {
            if (trimQueued) return
            trimQueued = true
        }
        trimPool.execute {
            synchronized(this) { trimQueued = false }
            evict(limitBytes())
        }
    }

    private fun evict(keepBytes: Long) {
        val victims = synchronized(this) {
            var total = catalog.values.sumOf { it.bytes }
            val out = ArrayList<String>()
            for ((id, e) in catalog.entries.sortedBy { it.value.access }) {
                if (total <= keepBytes) break
                if (e.pinned || id in open) continue
                out += id
                total -= e.bytes
            }
            out.forEach { catalog.remove(it) }
            out
        }
        for (id in victims) {
            File(dir, "$id.data").delete()
            File(dir, "$id.meta").delete()
        }
        if (victims.isNotEmpty()) publishUsage()
    }

    internal fun readMeta(f: File): Meta? =
        runCatching { AppJson.decodeFromString(Meta.serializer(), f.readText()) }.getOrNull()

    internal fun writeMeta(f: File, m: Meta) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(Meta.serializer(), m))
        tmp.renameTo(f)
    }

    companion object {
        const val BLOCK = 64 * 1024
        const val ON_DEMAND_RUN = 8
        const val PREFETCH_RUN = 32

        fun idFor(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun blocksFor(length: Long) = ((length + BLOCK - 1) / BLOCK).toInt()

        internal fun bitsOf(m: Meta): BitSet =
            if (m.bits.isEmpty()) BitSet() else BitSet.valueOf(Base64.getDecoder().decode(m.bits))
    }

    /** One cached remote file. Thread-safe; readers block until their bytes have arrived. */
    class CachedResource internal constructor(
        val id: String,
        private val dataFile: File,
        private val metaFile: File,
        private val url: () -> String,
        private val cache: MediaCache,
    ) {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val bits: BitSet
        private val claimed = BitSet()
        private var raf: RandomAccessFile? = null
        private var channel: FileChannel? = null
        private var failure: IOException? = null
        private var unsaved = 0
        private var prefetching = false
        @Volatile private var lastRead = 0
        @Volatile private var closed = false
        @Volatile var length: Long = -1
            private set
        @Volatile private var pinnedFlag = false
        var pinned: Boolean
            get() = pinnedFlag
            set(v) {
                pinnedFlag = v
                saveMeta()
            }

        init {
            val m = cache.readMeta(metaFile)
            if (m != null && dataFile.exists()) {
                length = m.length
                bits = bitsOf(m)
                pinnedFlag = m.pinned
            } else {
                bits = BitSet()
                dataFile.delete()
            }
        }

        private val blockCount get() = if (length < 0) Int.MAX_VALUE else blocksFor(length)
        val isComplete get() = lock.withLock { length >= 0 && bits.cardinality() >= blocksFor(length) }
        val cachedBytes get() = lock.withLock { min(bits.cardinality().toLong() * BLOCK, if (length < 0) Long.MAX_VALUE else length) }

        private fun channel(): FileChannel = lock.withLock {
            if (closed) throw IOException("closed")
            channel ?: RandomAccessFile(dataFile, "rw").also { raf = it }.channel.also { channel = it }
        }

        /** Total size, fetching the first run if it isn't known yet. */
        fun size(): Long {
            if (length < 0) awaitBlock(0)
            return length
        }

        /** Reads up to [size] bytes at [pos]; -1 at end of file. Blocks on the network if needed. */
        fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
            val total = size()
            if (pos >= total) return -1
            val n = min(size.toLong(), total - pos).toInt()
            if (n <= 0) return 0
            val first = (pos / BLOCK).toInt()
            val last = ((pos + n - 1) / BLOCK).toInt()
            for (b in first..last) awaitBlock(b)
            lastRead = last
            val bb = ByteBuffer.wrap(buf, off, n)
            var at = pos
            val ch = channel()
            while (bb.hasRemaining()) {
                val r = ch.read(bb, at)
                if (r < 0) break
                at += r
            }
            return n - bb.remaining()
        }

        /** Starts filling the rest of the file in the background, from wherever reading is. */
        fun prefetch() {
            lock.withLock {
                if (prefetching || closed) return
                prefetching = true
            }
            cache.submitPrefetch {
                try {
                    fillAll(fromBlock = { lastRead }, cancelled = { closed })
                } catch (_: IOException) {
                    // A reader that needs these bytes will retry and surface the error.
                } finally {
                    lock.withLock { prefetching = false }
                }
            }
        }

        /** Downloads every missing block on the calling thread. */
        fun downloadAll(progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
            fillAll(fromBlock = { 0 }, cancelled = cancelled, progress = progress)
        }

        private fun fillAll(fromBlock: () -> Int, cancelled: () -> Boolean, progress: ((Long, Long) -> Unit)? = null) {
            size()
            while (!cancelled()) {
                var run: IntRange? = null
                var waitFor = -1
                lock.withLock {
                    val count = blockCount
                    if (bits.cardinality() >= count) return
                    val start = fromBlock().coerceIn(0, count - 1)
                    var b = bits.nextClearBit(start)
                    if (b >= count) b = bits.nextClearBit(0)
                    if (b >= count) return
                    if (claimed[b]) {
                        // Someone else is fetching here; look for a free block after it.
                        var c = b
                        while (c < count && (bits[c] || claimed[c])) c++
                        if (c >= count) waitFor = b else run = claimRun(c, PREFETCH_RUN)
                    } else {
                        run = claimRun(b, PREFETCH_RUN)
                    }
                }
                run?.let { fetch(it) }
                if (waitFor >= 0) awaitBlock(waitFor)
                progress?.invoke(cachedBytes, length)
            }
        }

        private fun awaitBlock(b: Int) {
            var waited = 0L
            while (true) {
                var run: IntRange? = null
                lock.withLock {
                    if (closed) throw IOException("closed")
                    if (length >= 0 && b >= blockCount) return
                    if (bits[b]) return
                    if (!claimed[b]) {
                        run = claimRun(b, ON_DEMAND_RUN)
                    } else {
                        val before = System.nanoTime()
                        changed.await(1, TimeUnit.SECONDS)
                        waited += System.nanoTime() - before
                        if (waited > TimeUnit.SECONDS.toNanos(60)) throw failure ?: IOException("timed out waiting for data")
                    }
                }
                run?.let { fetch(it) }
            }
        }

        /** Claims up to [max] consecutive missing, unclaimed blocks from [b]. Caller holds the lock. */
        private fun claimRun(b: Int, max: Int): IntRange {
            var end = b
            val count = blockCount
            while (end + 1 < count && end + 1 - b < max && !bits[end + 1] && !claimed[end + 1]) end++
            claimed.set(b, end + 1)
            return b..end
        }

        private fun fetch(run: IntRange) {
            var block = run.first
            try {
                val start = run.first.toLong() * BLOCK
                val endExclusive = if (length >= 0) min((run.last + 1L) * BLOCK, length) else (run.last + 1L) * BLOCK
                val req = Request.Builder().url(url()).header("Range", "bytes=$start-${endExclusive - 1}").build()
                cache.http.newCall(req).execute().use { resp ->
                    var skip = 0L
                    when (resp.code) {
                        206 -> {
                            val total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            if (total != null) learnLength(total)
                        }
                        200 -> {
                            // No range support: the body is the whole file.
                            resp.body?.contentLength()?.takeIf { it >= 0 }?.let { learnLength(it) }
                            skip = start
                        }
                        else -> throw IOException("HTTP ${resp.code} fetching media")
                    }
                    if (length < 0) throw IOException("server sent no length")
                    val body = resp.body ?: throw IOException("empty response")
                    val src = body.byteStream()
                    val tmp = ByteArray(BLOCK)
                    while (skip > 0) {
                        val s = src.skip(skip)
                        if (s <= 0) throw IOException("short response")
                        skip -= s
                    }
                    val ch = channel()
                    val lastBlock = min(run.last, blockCount - 1)
                    while (block <= lastBlock) {
                        val blockStart = block.toLong() * BLOCK
                        val want = (min(blockStart + BLOCK, length) - blockStart).toInt()
                        var got = 0
                        while (got < want) {
                            val r = src.read(tmp, got, want - got)
                            if (r < 0) throw IOException("connection ended early")
                            got += r
                        }
                        val bb = ByteBuffer.wrap(tmp, 0, want)
                        var at = blockStart
                        while (bb.hasRemaining()) at += ch.write(bb, at)
                        lock.withLock {
                            bits.set(block)
                            claimed.clear(block)
                            failure = null
                            changed.signalAll()
                        }
                        block++
                        if (++unsaved >= 32 || block > lastBlock && isComplete) saveMeta()
                    }
                }
            } catch (e: IOException) {
                lock.withLock {
                    failure = e
                    changed.signalAll()
                }
                throw e
            } finally {
                lock.withLock {
                    // Release whatever this run didn't deliver, including blocks past EOF.
                    for (b in block..run.last) claimed.clear(b)
                    changed.signalAll()
                }
            }
        }

        private fun learnLength(total: Long) {
            lock.withLock {
                if (length != total) {
                    length = total
                    val count = blocksFor(total)
                    if (bits.length() > count) bits.clear(count, bits.length())
                }
            }
        }

        private fun saveMeta() {
            val m = lock.withLock {
                unsaved = 0
                Meta(length, Base64.getEncoder().encodeToString(bits.toByteArray()), pinnedFlag, System.currentTimeMillis())
            }
            runCatching { cache.writeMeta(metaFile, m) }
            cache.update(id, min(bits.cardinality().toLong() * BLOCK, if (length < 0) 0 else length), m.access, m.pinned)
        }

        internal fun close() {
            closed = true
            lock.withLock { changed.signalAll() }
            saveMeta()
            lock.withLock {
                runCatching { channel?.close() }
                runCatching { raf?.close() }
                channel = null
                raf = null
            }
        }
    }
}
