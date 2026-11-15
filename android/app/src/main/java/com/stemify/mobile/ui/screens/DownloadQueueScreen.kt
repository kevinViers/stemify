package com.stemify.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.cache.DownloadRecord
import com.stemify.mobile.ui.components.Artwork
import com.stemify.mobile.ui.components.Cell
import com.stemify.mobile.ui.components.EmptyState
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.section
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.delay

/** What's downloading, what's next and what failed, with live progress and speed. */
@Composable
fun DownloadQueueScreen() {
    val g = App.graph
    val c = Ios.colors
    val overlays = LocalOverlays.current
    val records by g.downloads.records.collectAsState()
    val progress by g.downloads.progress.collectAsState()
    val active by g.downloads.active.collectAsState()
    val finished by g.downloads.finished.collectAsState()
    val wifiOnly by g.settings.downloadsWifiOnly.flow.collectAsState()
    val unmetered by g.net.unmetered.collectAsState()
    val online by g.net.online.collectAsState()

    // Speed over the last few seconds, sampled once a second.
    var speed by remember { mutableLongStateOf(0L) }
    var fetched by remember { mutableLongStateOf(g.downloads.bytesFetched) }
    val start = remember { g.downloads.bytesFetched }
    LaunchedEffect(Unit) {
        val window = ArrayDeque<Long>()
        while (true) {
            val now = g.downloads.bytesFetched
            window.addLast(now)
            if (window.size > 4) window.removeFirst()
            speed = (window.last() - window.first()) / (window.size - 1).coerceAtLeast(1)
            fetched = now
            delay(1000)
        }
    }

    val running = active.filter { it in progress }.mapNotNull { records[it] }
    val waiting = progress.keys.filter { it !in active }.mapNotNull { records[it] }
    val failed = records.values.filter { it.error != null }.sortedBy { it.added }
    val left = progress.size
    val batch = finished + left
    val overall = if (batch == 0) 1f else (finished + progress.values.sum()) / batch

    Page("Download Queue", grouped = true) {
        if (left == 0 && failed.isEmpty()) {
            item("empty") { EmptyState(Sym.download, "Nothing Downloading", "Songs you download wait here until they're on the phone.") }
            return@Page
        }
        val status = when {
            left == 0 -> "All done" + if (failed.isNotEmpty()) ", ${failed.size} failed" else ""
            !online -> "Waiting for a connection"
            wifiOnly && !unmetered -> "Waiting for Wi-Fi"
            else -> "Downloading"
        }
        section("status", footer = if (left > 0 && wifiOnly && !unmetered) "Download on Wi-Fi Only is on (Settings). The queue carries on when the phone joins Wi-Fi." else null, rows = listOf {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(status, Type.headline, Modifier.weight(1f))
                    if (left > 0) Txt("${(overall * 100).toInt()}%", Type.headline, color = c.label2)
                }
                Spacer(Modifier.height(10.dp))
                Bar(overall, height = 6)
                Spacer(Modifier.height(8.dp))
                val facts = buildList {
                    if (left > 0) add("$left of $batch song${if (batch == 1) "" else "s"} left")
                    if (left > 0 && active.isNotEmpty()) add(rate(speed))
                    if (fetched > start) add("${size(fetched - start)} downloaded")
                }
                Txt(facts.joinToString(" · ").ifEmpty { " " }, Type.subhead, color = c.label2)
            }
        })
        if (running.isNotEmpty()) {
            section("now", header = "Downloading", separatorInset = 72.dp, rows = running.map { r ->
                { QueueRow(r, progress[r.track.ratingKey] ?: 0f) }
            })
        }
        if (waiting.isNotEmpty()) {
            section("next", header = "Up Next · ${waiting.size}", separatorInset = 72.dp, rows = waiting.map { r ->
                { QueueRow(r, null) }
            })
        }
        if (failed.isNotEmpty()) {
            section("failed", header = "Failed · ${failed.size}", separatorInset = 72.dp, rows = failed.map<DownloadRecord, @Composable () -> Unit> { r ->
                { QueueRow(r, null, error = r.error) }
            } + listOf<@Composable () -> Unit> {
                Cell("Retry Failed", titleColor = c.accent, centered = true) {
                    g.downloads.retryFailed()
                    overlays.hud("Retrying")
                }
            })
        }
    }
}

@Composable
private fun QueueRow(r: DownloadRecord, fraction: Float?, error: String? = null) {
    val c = Ios.colors
    val t = r.track
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.thumb, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(t.title, Type.body, maxLines = 1)
            Txt(error ?: listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · "), Type.subhead,
                color = if (error != null) c.red else c.label2, maxLines = 1)
            if (fraction != null) {
                Spacer(Modifier.height(6.dp))
                Bar(fraction, height = 3)
            }
        }
        if (fraction != null) {
            Spacer(Modifier.width(12.dp))
            Txt("${(fraction * 100).toInt()}%", Type.subhead, color = c.label2)
        }
    }
}

@Composable
private fun Bar(fraction: Float, height: Int) {
    val c = Ios.colors
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp)).background(c.fillStrong)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(height.dp)).background(c.accent))
    }
}

private fun rate(bytesPerSecond: Long) = when {
    bytesPerSecond <= 0 -> "Starting…"
    bytesPerSecond < 1_000_000 -> "%.0f KB/s".format(bytesPerSecond / 1e3)
    else -> "%.1f MB/s".format(bytesPerSecond / 1e6)
}

private fun size(bytes: Long) = if (bytes < 1e9) "%.0f MB".format(bytes / 1e6) else "%.2f GB".format(bytes / 1e9)
