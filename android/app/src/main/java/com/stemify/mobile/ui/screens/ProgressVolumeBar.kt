package com.stemify.mobile.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.engine.masterTaper
import com.stemify.mobile.engine.stemTaper
import com.stemify.mobile.playback.StemSetting
import com.stemify.mobile.playback.Waveform
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Playback position, refreshed every display frame while playing (165 Hz on the OnePlus 15).
 * Read it inside draw lambdas so only drawing, not composition, follows it.
 */
@Composable
fun rememberPlaybackPosition(): State<Long> {
    val pm = App.graph.playback
    val pos = remember { mutableLongStateOf(pm.positionMs()) }
    val playing by pm.playWhenReady.collectAsState()
    val current by pm.current.collectAsState()
    LaunchedEffect(playing, current) {
        while (true) {
            if (playing) withFrameNanos { } else delay(200)
            pos.longValue = pm.positionMs()
        }
    }
    return pos
}

/** 0..1 loudness per waveform bucket for the current mix: stems weighted by their faders. */
fun envelope(w: Waveform?, names: List<String>, mix: Map<String, StemSetting>): FloatArray? {
    if (w == null) return null
    w.stems?.let { stems ->
        val anySolo = names.any { mix[it]?.solo == true }
        val gains = stems.keys.associateWith { n ->
            val s = mix[n] ?: StemSetting()
            if (anySolo && !s.solo || s.muted) 0f else stemTaper(s.level)
        }
        // Scaled to the full mix's range, so muting a stem still shows as quieter bars.
        return shape(mixDb(stems, gains), mixDb(stems, null))
    }
    w.loudnessDb?.let { db -> return shape(db, db) }
    return null
}

private fun mixDb(stems: Map<String, FloatArray>, gains: Map<String, Float>?): FloatArray {
    val n = stems.values.maxOf { it.size }
    return FloatArray(n) { i ->
        var e = 0f
        for ((name, arr) in stems) {
            val v = arr.getOrElse(i) { 0f } * (gains?.get(name) ?: 1f)
            e += v * v
        }
        20f * log10(sqrt(e) + 1e-6f)
    }
}

/**
 * Loudness to bar height, relative to the song itself: its loudest moments (99th percentile)
 * fill the bar and its quiet tenth sits near the bottom, over 6 to 24 dB, with a curve that
 * pulls the middle down. Modern masters barely move in absolute terms (a few dB from verse to
 * chorus), so an absolute scale draws nearly every song as a solid block.
 */
private fun shape(db: FloatArray, ref: FloatArray): FloatArray {
    val audible = ref.filter { it > -60f }.ifEmpty { ref.toList() }.sorted()
    if (audible.isEmpty()) return FloatArray(db.size) { 0.28f }
    fun pct(p: Float) = audible[(p * audible.size).toInt().coerceAtMost(audible.lastIndex)]
    val hi = pct(0.99f)
    val span = (hi - pct(0.10f)).coerceIn(6f, 24f)
    val lo = hi - span
    return FloatArray(db.size) { 0.05f + 0.95f * ((db[it] - lo) / span).coerceIn(0f, 1f).pow(1.8f) }
}

/**
 * Plexamp's seek bar: the track's loudness as bars, played part in accent. The bars'
 * height follows the master volume, so the volume is drawn over the progress.
 * Drag sideways to seek, up/down to change volume, tap to jump.
 */
@Composable
fun ProgressVolumeBar(
    position: State<Long>,
    durationMs: Long,
    envelope: FloatArray?,
    master: Float,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    played: Color,
    unplayed: Color,
    centerLabel: String?,
    modifier: Modifier = Modifier,
) {
    val c = Ios.colors
    val haptic = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var adjusting by remember { mutableStateOf(false) }
    val vol by rememberUpdatedState(master)
    val dur by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val volume by rememberUpdatedState(onVolume)
    val volScale by animateFloatAsState(0.14f + 0.86f * masterTaper(master).let { sqrt(sqrt(it)) }, tween(90), label = "vol")
    val guides by animateFloatAsState(if (adjusting) 1f else 0f, tween(150), label = "guides")
    val shown by remember { derivedStateOf { if (scrubFraction >= 0f) (scrubFraction * dur).toLong() else position.value } }
    val shownSecond by remember { derivedStateOf { shown / 1000 } }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var axis = 0 // 1 = seek, 2 = volume
                    var total = Offset.Zero
                    val startVol = vol
                    var lastStep = (startVol * 20).toInt()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            when (axis) {
                                0 -> seek((ch.position.x / size.width).coerceIn(0f, 1f).times(dur).toLong())
                                1 -> seek((scrubFraction * dur).toLong())
                            }
                            break
                        }
                        total += ch.positionChange()
                        if (axis == 0 && (abs(total.x) > slop || abs(total.y) > slop)) {
                            axis = if (abs(total.x) >= abs(total.y)) 1 else 2
                            if (axis == 2) adjusting = true
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        when (axis) {
                            1 -> scrubFraction = (ch.position.x / size.width).coerceIn(0f, 1f)
                            2 -> {
                                val v = (startVol - total.y / (size.height * 4.5f)).coerceIn(0f, 1f)
                                val step = (v * 20).toInt()
                                if (step != lastStep) {
                                    lastStep = step
                                    if (v == 0f || v == 1f) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                volume(v)
                            }
                        }
                        if (axis != 0) ch.consume()
                    }
                    scrubFraction = -1f
                    adjusting = false
                }
            },
        ) {
            val env = envelope
            val barW = 2.7.dp.toPx()
            val pitch = 4.6.dp.toPx()
            val count = max(8, (size.width / pitch).toInt())
            val gap = (size.width - count * barW) / (count - 1)
            val maxH = size.height
            val minH = barW
            val frac = if (dur > 0) (if (scrubFraction >= 0f) scrubFraction else position.value.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val cy = size.height / 2
            if (guides > 0f) {
                val g = (maxH * volScale) / 2
                val col = c.label2.copy(alpha = 0.5f * guides)
                drawLine(col, Offset(0f, cy - g), Offset(size.width, cy - g), 1.dp.toPx())
                drawLine(col, Offset(0f, cy + g), Offset(size.width, cy + g), 1.dp.toPx())
            }
            for (i in 0 until count) {
                val a = if (env == null || env.isEmpty()) 0.28f else {
                    val from = i * env.size / count
                    val to = max(from + 1, (i + 1) * env.size / count)
                    var m = 0f
                    for (k in from until to.coerceAtMost(env.size)) m = max(m, env[k])
                    m
                }
                val h = minH + (maxH - minH) * a * volScale
                val x = i * (barW + gap)
                val isPlayed = (x + barW / 2) / size.width <= frac
                drawRoundRect(if (isPlayed) played else unplayed, Offset(x, cy - h / 2), Size(barW, h), CornerRadius(barW / 2))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(18.dp)) {
            val tnum = Type.caption1.copy(fontFeatureSettings = "tnum")
            Txt(formatDuration(shownSecond * 1000), tnum, Modifier.align(Alignment.CenterStart), color = c.label2, weight = FontWeight.Medium)
            Txt("-" + formatDuration(max(0, dur - shownSecond * 1000)), tnum, Modifier.align(Alignment.CenterEnd), color = c.label2, weight = FontWeight.Medium)
            Row(Modifier.align(Alignment.Center).graphicsLayer { alpha = if (adjusting) 1f else 0.95f }, verticalAlignment = Alignment.CenterVertically) {
                if (adjusting) {
                    Icon(if (master <= 0.001f) Sym.speakerSlash else Sym.speakerWave, c.label2, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Txt("${(master * 100).toInt()}%", tnum, color = c.label2, weight = FontWeight.SemiBold)
                } else if (centerLabel != null) {
                    Txt(centerLabel, Type.caption2, color = c.label2, weight = FontWeight.Bold)
                }
            }
        }
    }
}
