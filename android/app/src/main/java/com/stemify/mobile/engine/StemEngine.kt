package com.stemify.mobile.engine

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One track's stems, decoding in sync. Built off the main thread, then handed to the engine. */
class TrackSession(val token: Long, val names: List<String>, val decoders: List<StemDecoder>) {
    val sampleRate get() = decoders.first().sampleRate
    val durationFrames get() = decoders.maxOf { it.durationUs } * sampleRate / 1_000_000L
    internal var gen = 0
    internal var framePos = 0L
    internal val target = FloatArray(names.size) { 1f }
    internal val current = FloatArray(names.size) { 1f }
    internal var mixVersion = -1

    fun error(): Throwable? = decoders.firstNotNullOfOrNull { it.error }

    internal fun ready(need: Int) = decoders.all { d ->
        d.error != null || (d.readyGen == gen || d.readyGen == Int.MAX_VALUE) && (d.ring.eos || d.ring.availableFrames >= need)
    }

    internal fun finished() = decoders.all { d ->
        d.error != null || (d.readyGen == gen || d.readyGen == Int.MAX_VALUE) && d.ring.eos && d.ring.availableFrames == 0
    }

    /** Re-targets decoders; only valid before the session is handed to the engine. */
    fun startAt(frame: Long) {
        gen++
        framePos = frame
        decoders.forEach { it.seek(gen, frame) }
    }

    fun release() = decoders.forEach { it.release() }
}

/**
 * Per-stem RMS of short blocks as they're mixed, stamped with their track frame, so meters can
 * show what's coming out of the speaker now. Reading the newest level instead runs ahead of the
 * audio, and moves in jerks: Android's mixer drains our buffer in bursts (about 5 a second on
 * deep-buffer outputs), so writes, and with them fresh levels, arrive in clumps.
 */
class MeterHistory {
    private val tokens = LongArray(CAP)
    private val starts = LongArray(CAP)
    private val values = FloatArray(CAP * MAX_STEMS)
    private var head = 0
    private var count = 0

    @Synchronized fun clear() {
        count = 0
    }

    @Synchronized fun add(token: Long, start: Long, rms: FloatArray, offset: Int, stems: Int) {
        tokens[head] = token
        starts[head] = start
        System.arraycopy(rms, offset, values, head * MAX_STEMS, min(stems, MAX_STEMS))
        if (stems < MAX_STEMS) java.util.Arrays.fill(values, head * MAX_STEMS + stems, (head + 1) * MAX_STEMS, 0f)
        head = (head + 1) % CAP
        count = min(count + 1, CAP)
    }

    /** Fills [out] with the levels of [token]'s block playing at [frame]; false if there's none. */
    @Synchronized fun read(token: Long, frame: Long, out: FloatArray): Boolean {
        for (k in 1..count) {
            val i = (head - k + CAP) % CAP
            if (tokens[i] != token || starts[i] > frame) continue
            if (frame - starts[i] > STALE_FRAMES) return false
            System.arraycopy(values, i * MAX_STEMS, out, 0, min(out.size, MAX_STEMS))
            return true
        }
        return false
    }

    companion object {
        const val BLOCK = 256
        const val MAX_STEMS = 8
        /** ~5 s at 48 kHz: well past the AudioTrack's buffer. */
        private const val CAP = 1024
        private const val STALE_FRAMES = 8192L
    }
}

/**
 * Mixes a [TrackSession]'s stems into one AudioTrack: per-stem gain (ramped), EQ with
 * anti-clip preamp, master gain, then a peak limiter. All AudioTrack calls happen on the
 * engine thread; the public methods just queue commands.
 *
 * Songs follow one another in the same AudioTrack: gaplessly, or crossfaded when the next one
 * comes with a [CrossfadePlan], the ending song playing on as a fading [Tail] beside it. A song
 * at another sample rate than the output is resampled, so changing songs never needs a new
 * AudioTrack (and the gap that comes with one).
 */
class StemEngine(private val listener: Listener) {
    interface Listener {
        fun onEngineState()
        /** [token]'s audio is now what's coming out of the speaker. */
        fun onTransition(token: Long)
        fun onEnded(token: Long)
        fun onError(token: Long, message: String)
        fun onRouted(device: AudioDeviceInfo?)
    }

    /** Where playback is; position = frame + (now - nanos) while [running]. */
    data class Snapshot(val token: Long, val frame: Long, val rate: Int, val nanos: Long, val running: Boolean) {
        fun positionMs(now: Long = System.nanoTime()): Long {
            if (rate <= 0) return 0
            val f = if (running) frame + (now - nanos) * rate / 1_000_000_000L else frame
            return f * 1000 / rate
        }
    }

    /** From output frame [writtenStart], [token] plays from its frame [frameStart]; [ratio] of its frames per output frame. */
    private class Segment(val writtenStart: Long, val token: Long, val frameStart: Long, val rate: Int, val ratio: Double)

    /** A session on its way to the output, resampled when its rate isn't the output's. */
    private inner class Voice(val s: TrackSession, val outRate: Int) {
        val rs = if (s.sampleRate != outRate) Resampler(s.sampleRate, outRate) else null
        /** Its frames per output frame. */
        val ratio = s.sampleRate.toDouble() / outRate
        /** Feeds the meters and follows mixer changes; a fading tail does neither. */
        var live = true
        private val pull = { buf: FloatArray, off: Int, n: Int -> mixChunk(s, buf, off, min(n, CHUNK), live) }
        private val done = { s.finished() }

        /** Up to [max] output frames into [dest] from frame 0. */
        fun render(dest: FloatArray, max: Int): Int = rs?.process(dest, max, pull, done) ?: mixChunk(s, dest, 0, max, live)

        fun finished() = if (rs == null) s.finished() else rs.drained
    }

    /** The song that's ending, mixed under the next until [endFrame] (all in its own frames). */
    private class Tail(val v: Voice, val endFrame: Long, val fadeFrom: Long, val fadeTo: Long) {
        var pos = v.s.framePos.toDouble()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val commands = ArrayDeque<() -> Unit>()
    @Volatile private var released = false

    private var session: TrackSession? = null
    private var voice: Voice? = null
    private var next: TrackSession? = null
    private var nextPlan: CrossfadePlan? = null
    private var tail: Tail? = null
    private var fadeInFrames = 0L
    private var fadeInDone = 0L
    private var wantPlay = false
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var written = 0L
    private val segments = ArrayDeque<Segment>()
    private var drainUntil = -1L
    private var drainDeadline = 0L
    private var drainToken = -1L
    private var waitingSince = 0L
    private var errorReported = -1L
    private val eq = Equalizer()
    private val limiter = Limiter()
    private var masterCur = 1f
    private val ts = AudioTimestamp()
    private var tsValid = false
    private var tsQueried = 0L
    private var tsFrame = 0L
    private var tsNanos = 0L
    private var out = FloatArray(CHUNK * 2)
    private var tailBuf = FloatArray(CHUNK * 2)
    private var tmp = FloatArray(CHUNK * 2)

    @Volatile var eqParams: EqParams? = null
    @Volatile var masterGain = 1f
    @Volatile private var mix: Map<String, Float> = emptyMap()
    @Volatile private var mixVersion = 0

    @Volatile var snapshot = Snapshot(-1, 0, 44100, 0, false); private set
    @Volatile var playing = false; private set
    @Volatile var buffering = false; private set
    /** Post-gain RMS per stem in short blocks, for the mixer's meters; see [levelsNow]. */
    private val meters = MeterHistory()
    private var meterSums = FloatArray(CHUNK / MeterHistory.BLOCK * MeterHistory.MAX_STEMS)

    private val routing = AudioRouting.OnRoutingChangedListener { r -> listener.onRouted(r.routedDevice) }
    private val thread = Thread({ loop() }, "stem-engine").apply { start() }

    // ---- commands (any thread) --------------------------------------------------------

    private fun post(cmd: () -> Unit) = synchronized(lock) {
        commands.addLast(cmd)
        lock.notifyAll()
    }

    /** Replaces whatever is playing with [s], which must already be prepared. */
    fun load(s: TrackSession, play: Boolean) = post {
        dropAll()
        session = s
        wantPlay = play
        resetOutput(s)
        publish()
    }

    /**
     * The track to continue into when the current one ends (or null): gaplessly, or crossfaded
     * by [plan] (or later through [planNext], which also applies its offset).
     */
    fun queueNext(s: TrackSession?, plan: CrossfadePlan? = null) = post {
        if (next !== s) next?.release()
        next = s
        nextPlan = if (s == null) null else plan
    }

    /**
     * Sets how to cross into [s], if it's still the queued next track: its offset (leading silence
     * skipped) is applied here, on the engine thread, so it can't race a switch already under way.
     */
    fun planNext(s: TrackSession, plan: CrossfadePlan?) = post {
        if (next !== s) return@post
        val off = ((plan?.inOffsetS ?: 0.0) * s.sampleRate).toLong()
        if (s.framePos != off) s.startAt(off)
        nextPlan = plan
    }

    fun setPlaying(play: Boolean) = post {
        wantPlay = play
        if (!play) track?.pause()
        publish()
    }

    fun seekTo(ms: Long) = post {
        val s = session ?: return@post
        val frame = (ms * s.sampleRate / 1000).coerceIn(0, max(0, s.durationFrames - 1))
        s.gen++
        s.framePos = frame
        s.decoders.forEach { it.seek(s.gen, frame) }
        resetOutput(s)
        publish()
    }

    fun stop() = post {
        dropAll()
        wantPlay = false
        publish()
    }

    fun setMix(gains: Map<String, Float>) {
        mix = gains
        mixVersion++
    }

    fun release() {
        released = true
        post { }
    }

    /**
     * The meters' levels for what's audible right now, into [out] (one per stem). False when
     * nothing is playing, so the meters can fall.
     */
    fun levelsNow(out: FloatArray): Boolean {
        val snap = snapshot
        if (!snap.running || snap.rate <= 0) return false
        val frame = snap.frame + (System.nanoTime() - snap.nanos) * snap.rate / 1_000_000_000L
        return meters.read(snap.token, frame, out)
    }

    // ---- engine thread ----------------------------------------------------------------

    private fun dropAll() {
        dropTail()
        fadeInFrames = 0
        fadeInDone = 0
        session?.release()
        next?.release()
        session = null
        voice = null
        next = null
        nextPlan = null
        drainUntil = -1
        track?.let { it.pause(); it.flush() }
        written = 0
        segments.clear()
    }

    private fun dropTail() {
        tail?.v?.s?.release()
        tail = null
    }

    private fun resetOutput(s: TrackSession) {
        dropTail()
        fadeInFrames = 0
        fadeInDone = 0
        track?.let { it.pause(); it.flush() }
        tsValid = false
        written = 0
        voice = null
        segments.clear()
        segments.addLast(Segment(0, s.token, s.framePos, s.sampleRate, 1.0))
        drainUntil = -1
        waitingSince = 0
        meters.clear()
        errorReported = -1
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (!released) {
            while (true) {
                val cmd = synchronized(lock) { commands.removeFirstOrNull() } ?: break
                cmd()
            }
            try {
                step()
            } catch (t: Throwable) {
                val tok = session?.token ?: -1
                dropAll()
                wantPlay = false
                publish()
                main.post { listener.onError(tok, t.message ?: t.javaClass.simpleName) }
            }
        }
        dropAll()
        track?.release()
    }

    private fun idle(ms: Long) = synchronized(lock) { if (commands.isEmpty()) lock.wait(ms) }

    private fun step() {
        val s = session
        if (s == null || !wantPlay) {
            setFlags(false, false)
            publish()
            idle(100)
            return
        }
        s.error()?.let { e ->
            if (errorReported != s.token) {
                errorReported = s.token
                wantPlay = false
                track?.pause()
                setFlags(false, false)
                main.post { listener.onError(s.token, e.message ?: "Playback failed") }
            }
            idle(50)
            return
        }
        if (drainUntil >= 0) {
            drain(s)
            return
        }
        val need = if (written == 0L || buffering) PREFILL else CHUNK
        if (!s.ready(need) && !s.finished()) {
            val now = System.nanoTime()
            if (waitingSince == 0L) waitingSince = now
            if (!buffering && (written == 0L || now - waitingSince > 60_000_000L)) {
                track?.pause()
                setFlags(playing = false, buffering = true)
            }
            publish()
            idle(5)
            return
        }
        waitingSince = 0
        // A new AudioTrack only when output starts afresh; songs joining a running one are resampled.
        if (track == null || written == 0L && trackRate != s.sampleRate) ensureTrack(s.sampleRate)
        val t = track!!
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        setFlags(playing = true, buffering = false)
        voice.let { if (it == null || it.s !== s || it.outRate != trackRate) voice = Voice(s, trackRate) }
        if (tail == null) startCrossfade(s)

        val cur = session!!
        val v = voice!!
        val n = v.render(out, CHUNK)
        if (n == 0) {
            if (v.finished()) endOfTrack(cur) else idle(2)
            return
        }
        if (fadeInDone < fadeInFrames) {
            for (f in 0 until n) {
                val g = Crossfade.fadeInGain((fadeInDone + f).toDouble() / fadeInFrames)
                out[f * 2] *= g
                out[f * 2 + 1] *= g
            }
            fadeInDone += n
        }
        tail?.let { mixTail(it, n) }
        eq.configure(eqParams, trackRate)
        eq.process(out, n)
        val m = masterGain
        rampGain(out, n, masterCur, m)
        masterCur = m
        limiter.process(out, n)
        var off = 0
        while (off < n * 2 && !released) {
            val w = t.write(out, off, n * 2 - off, AudioTrack.WRITE_BLOCKING)
            if (w < 0) throw IllegalStateException("AudioTrack write failed ($w)")
            off += w
        }
        written += n
        publish()
    }

    /** Hands over to the next song once [s] reaches the plan's start, keeping [s] as the fading tail. */
    private fun startCrossfade(s: TrackSession) {
        val nx = next ?: return
        val plan = nextPlan ?: return
        val rate = s.sampleRate
        val start = (plan.startS * rate).toLong()
        if (s.framePos < start || nx.error() != null || !nx.ready(PREFILL)) return
        val end = min((plan.endS * rate).toLong(), s.durationFrames)
        if (start >= end || s.framePos >= end) {
            // No overlap: cut straight over at the last sound, trailing silence dropped.
            cutTo(nx, s)
            return
        }
        var from = (plan.fadeFromS * rate).toLong()
        var to = (plan.fadeToS * rate).toLong()
        // Started late (a seek into the overlap, or the plan came late): fade over what's left.
        if (s.framePos > start + rate / 10 && to > from) {
            from = max(from, s.framePos)
            to = min(end, max(to, from + rate / 2))
        }
        val outgoing = voice!!
        outgoing.live = false
        tail = Tail(outgoing, end, from, to)
        segments.addLast(Segment(written, nx.token, nx.framePos, nx.sampleRate, nx.sampleRate.toDouble() / trackRate))
        session = nx
        voice = Voice(nx, trackRate)
        next = null
        nextPlan = null
        fadeInFrames = (plan.inFadeS * trackRate).toLong()
        fadeInDone = 0
    }

    /** Adds the fading tail under the [n] frames of the next song in [out]. */
    private fun mixTail(t: Tail, n: Int) {
        val v = t.v
        val left = ((t.endFrame - t.pos) / v.ratio).toLong()
        val m = if (left <= 0) 0 else v.render(tailBuf, min(n.toLong(), left).toInt())
        val span = (t.fadeTo - t.fadeFrom).toDouble()
        var p = t.pos
        for (f in 0 until m) {
            val g = if (span <= 0) 1f else Crossfade.fadeOutGain((p - t.fadeFrom) / span)
            out[f * 2] += tailBuf[f * 2] * g
            out[f * 2 + 1] += tailBuf[f * 2 + 1] * g
            p += v.ratio
        }
        t.pos = p
        if (left <= m || m < n && v.finished()) dropTail()
    }

    private fun endOfTrack(s: TrackSession) {
        val nx = next
        if (nx != null && nx.error() == null) {
            cutTo(nx, s)
            return
        }
        // Let the AudioTrack play out, then hand over.
        drainUntil = written
        drainToken = s.token
        drainDeadline = System.nanoTime() + (written * 1_000_000_000L / max(1, trackRate)) + 1_000_000_000L
    }

    /** Gapless: keeps writing into the same AudioTrack, [nx] (resampled if its rate differs) right after [s]. */
    private fun cutTo(nx: TrackSession, s: TrackSession) {
        dropTail()
        fadeInFrames = 0
        segments.addLast(Segment(written, nx.token, nx.framePos, nx.sampleRate, nx.sampleRate.toDouble() / trackRate))
        session = nx
        voice = Voice(nx, trackRate)
        next = null
        nextPlan = null
        s.release()
    }

    private fun drain(s: TrackSession) {
        val (presented, _) = presented()
        publish()
        if (presented < drainUntil - 64 && System.nanoTime() < drainDeadline) {
            idle(10)
            return
        }
        drainUntil = -1
        val nx = next
        if (nx != null && nx.error() == null) {
            session = nx
            next = null
            nextPlan = null
            s.release()
            resetOutput(nx)
            main.post { listener.onTransition(nx.token) }
        } else {
            val tok = drainToken
            dropAll()
            wantPlay = false
            setFlags(false, false)
            publish()
            main.post { listener.onEnded(tok) }
        }
    }

    /**
     * Up to [max] frames of [s]'s stems, mixed into [dest] from frame [off]. [live] mixes feed the
     * meters and pick up mixer changes; a fading tail keeps the gains it had.
     */
    private fun mixChunk(s: TrackSession, dest: FloatArray, off: Int, max: Int, live: Boolean): Int {
        if (s.finished()) return 0
        var n = max
        for (d in s.decoders) {
            if (d.error != null || d.ring.eos && d.ring.availableFrames == 0) continue
            n = min(n, d.ring.availableFrames)
        }
        if (n <= 0) return 0
        if (live && s.mixVersion != mixVersion) {
            val g = mix
            for (i in s.names.indices) s.target[i] = g[s.names[i]] ?: 1f
            s.mixVersion = mixVersion
        }
        java.util.Arrays.fill(dest, off * 2, (off + n) * 2, 0f)
        val stride = MeterHistory.MAX_STEMS
        val blocks = (n + MeterHistory.BLOCK - 1) / MeterHistory.BLOCK
        if (live) {
            if (meterSums.size < blocks * stride) meterSums = FloatArray(blocks * stride)
            java.util.Arrays.fill(meterSums, 0f)
        }
        for ((i, d) in s.decoders.withIndex()) {
            val got = d.ring.read(tmp, n)
            if (got < n) java.util.Arrays.fill(tmp, got * 2, n * 2, 0f)
            val g0 = s.current[i]
            val g1 = s.target[i]
            s.current[i] = g1
            if (g0 == 0f && g1 == 0f) continue
            val step = (g1 - g0) / n
            var g = g0
            for (b in 0 until blocks) {
                val end = min(n, (b + 1) * MeterHistory.BLOCK)
                var sum = 0f
                for (f in b * MeterHistory.BLOCK until end) {
                    val l = tmp[f * 2] * g
                    val r = tmp[f * 2 + 1] * g
                    dest[(off + f) * 2] += l
                    dest[(off + f) * 2 + 1] += r
                    sum += l * l + r * r
                    g += step
                }
                if (live && i < stride) meterSums[b * stride + i] = sqrt(sum / (2 * (end - b * MeterHistory.BLOCK)))
            }
        }
        if (live) for (b in 0 until blocks) meters.add(s.token, s.framePos + b * MeterHistory.BLOCK, meterSums, b * stride, s.names.size)
        s.framePos += n
        return n
    }

    private fun rampGain(buf: FloatArray, n: Int, from: Float, to: Float) {
        if (from == 1f && to == 1f) return
        val step = (to - from) / n
        var g = from
        for (f in 0 until n) {
            buf[f * 2] *= g
            buf[f * 2 + 1] *= g
            g += step
        }
    }
    private fun ensureTrack(rate: Int) {
        if (track != null && trackRate == rate) return
        track?.let {
            it.removeOnRoutingChangedListener(routing)
            it.release()
        }
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = max(minBuf * 2, rate * 8 * BUFFER_MS / 1000)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        t.addOnRoutingChangedListener(routing, main)
        track = t
        trackRate = rate
        limiter.setSampleRate(rate)
        val routed = t.routedDevice
        main.post { listener.onRouted(routed) }
    }

    /**
     * (frames presented since the last flush, at nanoTime). Timestamps are polled every
     * half second, as AudioTrack recommends, and extrapolated in between.
     */
    private fun presented(): Pair<Long, Long> {
        val now = System.nanoTime()
        val t = track ?: return 0L to now
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            tsValid = false
        } else {
            if (!tsValid || now - tsQueried > 500_000_000L) {
                tsQueried = now
                tsValid = t.getTimestamp(ts) && ts.framePosition in 0..written
                if (tsValid) {
                    tsFrame = ts.framePosition
                    tsNanos = ts.nanoTime
                }
            }
            if (tsValid) return min(tsFrame + (now - tsNanos) * trackRate / 1_000_000_000L, written) to now
        }
        return min(t.playbackHeadPosition.toLong() and 0xFFFFFFFFL, written) to now
    }

    private fun publish() {
        val (p, nanos) = presented()
        while (segments.size > 1 && segments[1].writtenStart <= p) {
            segments.removeFirst()
            val tok = segments.first().token
            main.post { listener.onTransition(tok) }
        }
        val seg = segments.firstOrNull()
        val s = session
        snapshot = if (seg == null) {
            val rate = if (s != null && s.decoders.isNotEmpty()) s.sampleRate else trackRate
            Snapshot(s?.token ?: -1, s?.framePos ?: 0, rate, nanos, false)
        } else {
            val running = playing && drainUntil < 0 || drainUntil >= 0 && p < drainUntil
            Snapshot(seg.token, seg.frameStart + (max(0, p - seg.writtenStart) * seg.ratio).toLong(), seg.rate, nanos, running)
        }
    }

    private fun setFlags(playing: Boolean, buffering: Boolean) {
        if (playing == this.playing && buffering == this.buffering) return
        this.playing = playing
        this.buffering = buffering
        main.post { listener.onEngineState() }
    }

    companion object {
        const val CHUNK = 1024
        /** Per stem, decoded ahead of the mixer. */
        const val RING_SECONDS = 3
        /** Frames each stem must have before (re)starting output. */
        const val PREFILL = 8192
        const val BUFFER_MS = 120
    }
}
