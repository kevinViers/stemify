package com.stemify.mobile.connect

import android.os.SystemClock
import com.stemify.mobile.core.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Connect conversation: a JSON object per line over a TCP or Bluetooth socket's streams.
 * Reading and writing happen on IO threads; [onMessage] and [onClosed] run on the main thread,
 * as does everything that calls in.
 */
class Link(
    private val input: InputStream,
    private val output: OutputStream,
    private val socket: Closeable,
    val transport: Transport,
    private val scope: CoroutineScope,
    /** Controllers ping; receivers answer. */
    private val ping: Boolean,
) {
    var onMessage: (JsonObject) -> Unit = {}
    var onClosed: () -> Unit = {}
    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val open = AtomicBoolean(true)
    @Volatile private var heard = SystemClock.elapsedRealtime()
    val isOpen get() = open.get()

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    heard = SystemClock.elapsedRealtime()
                    if (line.isBlank()) continue
                    val msg = runCatching { AppJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    when (msg.str("t")) {
                        "ping" -> send(buildJsonObject { put("t", "pong") })
                        "pong" -> {}
                        else -> withContext(Dispatchers.Main) { if (isOpen) onMessage(msg) }
                    }
                }
            } catch (_: IOException) {
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { close() }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                for (line in outbox) {
                    output.write(line.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { socket.close() }
                withContext(NonCancellable + Dispatchers.Main) { close() }
            }
        }
        scope.launch {
            while (isOpen) {
                delay(Wire.PING_EVERY_MS)
                if (SystemClock.elapsedRealtime() - heard > Wire.SILENCE_LIMIT_MS) close()
                else if (ping) send(buildJsonObject { put("t", "ping") })
            }
        }
    }

    fun send(msg: JsonObject) {
        if (isOpen) outbox.trySend(msg.toString() + "\n")
    }

    /** Says why first, when given a reason; whatever's queued still goes out before the socket closes. */
    fun close(reason: String? = null) {
        if (reason != null && isOpen) send(buildJsonObject { put("t", "bye"); put("reason", reason) })
        if (!open.compareAndSet(true, false)) return
        outbox.close()
        onClosed()
    }
}
