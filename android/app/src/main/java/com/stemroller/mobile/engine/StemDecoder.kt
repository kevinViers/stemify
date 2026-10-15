package com.stemroller.mobile.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.stemroller.mobile.cache.MediaCache
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/** Single-producer single-consumer ring of interleaved stereo float frames. */
class FloatRing(capacityFrames: Int) {
    private val buf = FloatArray(capacityFrames * 2)
    private val cap = buf.size
    private var readPos = 0L
    private var writePos = 0L
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    @Volatile var eos = false

    val availableFrames: Int get() = lock.withLock { ((writePos - readPos) / 2).toInt() }

    /** Blocks while full; returns false if [abort] became true first. */
    fun write(src: FloatArray, off: Int, len: Int, abort: () -> Boolean): Boolean {
        var done = 0
        while (done < len) {
            lock.withLock {
                while (writePos - readPos >= cap) {
                    if (abort()) return false
                    notFull.await(20, TimeUnit.MILLISECONDS)
                }
                if (abort()) return false
                val free = (cap - (writePos - readPos)).toInt()
                val n = min(free, len - done)
                var w = (writePos % cap).toInt()
                val first = min(n, cap - w)
                System.arraycopy(src, off + done, buf, w, first)
                if (n > first) System.arraycopy(src, off + done + first, buf, 0, n - first)
                writePos += n
                done += n
            }
        }
        return true
    }

    /** Copies up to [frames] frames into dst; returns frames copied. Never blocks on data. */
    fun read(dst: FloatArray, frames: Int): Int = lock.withLock {
        val n = min(frames * 2L, writePos - readPos).toInt()
        val r = (readPos % cap).toInt()
        val first = min(n, cap - r)
        System.arraycopy(buf, r, dst, 0, first)
        if (n > first) System.arraycopy(buf, 0, dst, first, n - first)
        readPos += n
        notFull.signalAll()
        n / 2
    }

    fun clear() = lock.withLock {
        readPos = writePos
        eos = false
        notFull.signalAll()
    }

    fun wake() = lock.withLock { notFull.signalAll() }
}

/** MediaExtractor's view of a cached resource. Retries brief network trouble before giving up. */
class ResourceDataSource(
    private val res: MediaCache.CachedResource,
    private val onClose: () -> Unit = {},
) : MediaDataSource() {
    @Volatile var failed: IOException? = null
        private set
    @Volatile var closed = false
        private set
    private val closing = AtomicBoolean(false)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var attempt = 0
        while (true) {
            try {
                val n = res.readAt(position, buffer, offset, size)
                failed = null
                if (n > 0 && attempt == 0) res.prefetch()
                return n
            } catch (e: IOException) {
                if (closed || ++attempt > 8) {
                    failed = e
                    throw e
                }
                Thread.sleep(250L * attempt)
            }
        }
    }

    override fun getSize(): Long = try {
        res.size()
    } catch (e: IOException) {
        failed = e
        -1
    }

    override fun close() {
        closed = true
        if (closing.compareAndSet(false, true)) onClose()
    }
}

/**
 * Decodes one stem on its own thread into [ring]. Seeks are sample-accurate: frames
 * before the target are dropped by presentation time, so every stem of a track resumes
 * on exactly the same frame.
 */
class StemDecoder(val name: String, private val source: ResourceDataSource, ringFrames: Int) {
    val ring = FloatRing(ringFrames)
    private val prepared = CountDownLatch(1)
    @Volatile var sampleRate = 0; private set
    @Volatile var durationUs = 0L; private set
    @Volatile var error: Throwable? = null; private set
    /** Generation whose data is in the ring (or that hit EOS). */
    @Volatile var readyGen = -1; private set

    private val seekLock = Object()
    private var pendingGen = -1
    private var pendingFrame = 0L
    @Volatile private var released = false
    private val thread = Thread({ run() }, "stem-$name")

    fun start(gen: Int, frame: Long) {
        seek(gen, frame)
        thread.priority = Thread.MAX_PRIORITY - 1
        thread.start()
    }

    /** Blocks until the stream's format is known. False on error or timeout. */
    fun awaitPrepared(timeoutMs: Long): Boolean = prepared.await(timeoutMs, TimeUnit.MILLISECONDS) && error == null

    fun seek(gen: Int, frame: Long) {
        synchronized(seekLock) {
            pendingGen = gen
            pendingFrame = frame
            seekLock.notifyAll()
        }
        ring.wake()
    }

    private fun seekPending(current: Int) = synchronized(seekLock) { pendingGen != current }

    fun release() {
        released = true
        source.close()
        synchronized(seekLock) { seekLock.notifyAll() }
        ring.wake()
    }

    private fun run() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(source) }
            val trackIdx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio in $name")
            extractor.selectTrack(trackIdx)
            val fmt = extractor.getTrackFormat(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
            // The FLAC extractor already decodes to audio/raw, whose pass-through "decoder"
            // echoes whatever encoding it's told without converting, so keep the extractor's.
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            codec.outputFormat.let { of ->
                if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            prepared.countDown()

            val info = MediaCodec.BufferInfo()
            var scratch = FloatArray(8192)
            var gen = -1
            var inputDone = false
            var outputDone = false
            var firstOut = true
            var nextFrame = 0L
            var dropUntil = 0L
            var lastInputUs = 0L

            while (!released) {
                synchronized(seekLock) {
                    if (pendingGen != gen) {
                        gen = pendingGen
                        dropUntil = pendingFrame
                        ring.clear()
                        extractor.seekTo(pendingFrame * 1_000_000L / sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        codec.flush()
                        inputDone = false
                        outputDone = false
                        firstOut = true
                    } else if (outputDone) {
                        ring.eos = true
                        readyGen = gen
                        seekLock.wait(100)
                    }
                }
                if (outputDone || released) continue

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(2_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(ib, 0)
                        if (n < 0) {
                            source.failed?.let { if (durationUs > 0 && lastInputUs < durationUs - 2_000_000) throw it }
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            lastInputUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    continue
                }
                if (outIdx < 0) continue
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val bytesPer = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        else -> 2
                    }
                    val frames = info.size / (bytesPer * channels)
                    if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
                    toStereo(ob, encoding, channels, frames, scratch)
                    val start = if (firstOut) info.presentationTimeUs * sampleRate / 1_000_000L else nextFrame
                    firstOut = false
                    nextFrame = start + frames
                    val skip = (dropUntil - start).coerceIn(0, frames.toLong()).toInt()
                    if (frames > skip) {
                        val myGen = gen
                        if (ring.write(scratch, skip * 2, (frames - skip) * 2) { released || seekPending(myGen) }) {
                            readyGen = gen
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        } catch (t: Throwable) {
            if (!released) {
                Log.w("StemDecoder", "$name failed", t)
                error = t
            }
            ring.eos = true
            readyGen = Int.MAX_VALUE
        } finally {
            prepared.countDown()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun toStereo(ob: java.nio.ByteBuffer, encoding: Int, channels: Int, frames: Int, out: FloatArray) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = ob.asFloatBuffer()
                if (channels == 2) {
                    fb.get(out, 0, frames * 2)
                } else {
                    for (i in 0 until frames) {
                        val l = fb.get(i * channels)
                        out[i * 2] = l
                        out[i * 2 + 1] = if (channels > 1) fb.get(i * channels + 1) else l
                    }
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ib = ob.asIntBuffer()
                for (i in 0 until frames) {
                    val l = ib.get(i * channels) / 2147483648f
                    out[i * 2] = l
                    out[i * 2 + 1] = if (channels > 1) ib.get(i * channels + 1) / 2147483648f else l
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val base = ob.position()
                fun s24(idx: Int): Float {
                    val p = base + idx * 3
                    val v = (ob.get(p).toInt() and 0xFF) or ((ob.get(p + 1).toInt() and 0xFF) shl 8) or (ob.get(p + 2).toInt() shl 16)
                    return v / 8388608f
                }
                for (i in 0 until frames) {
                    val l = s24(i * channels)
                    out[i * 2] = l
                    out[i * 2 + 1] = if (channels > 1) s24(i * channels + 1) else l
                }
            }
            else -> {
                val sb = ob.asShortBuffer()
                for (i in 0 until frames) {
                    val l = sb.get(i * channels) / 32768f
                    out[i * 2] = l
                    out[i * 2 + 1] = if (channels > 1) sb.get(i * channels + 1) / 32768f else l
                }
            }
        }
    }
}
