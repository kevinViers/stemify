package com.stemify.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tap target without a ripple. iOS dims or highlights instead; [pressed] reports it. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tappable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    interaction: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val src = interaction ?: remember { MutableInteractionSource() }
    combinedClickable(src, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
}

/** Like [tappable] but fades to 35% while pressed, as iOS buttons do. */
fun Modifier.pressFade(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) 0.35f else 1f, tween(if (pressed) 0 else 220), label = "press")
    this.alpha(if (enabled) a else 0.35f).tappable(enabled, onLongClick, src, onClick)
}

@Composable
fun IconButton(
    vector: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ios.colors.accent,
    size: Dp = 24.dp,
    box: Dp = 44.dp,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    Box(modifier.size(box).pressFade(enabled, onLongClick, onClick), contentAlignment = Alignment.Center) {
        Icon(vector, tint, size = size)
    }
}

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bold: Boolean = false, color: Color = Ios.colors.accent, enabled: Boolean = true) {
    Box(modifier.height(44.dp).pressFade(enabled, onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Txt(text, Type.body, color = color, weight = if (bold) FontWeight.SemiBold else null)
    }
}

/** UISwitch: 51x31, green when on. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    val track by animateColorAsState(if (checked) c.green else c.switchOff, tween(200), label = "track")
    val x by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 600f), label = "thumb")
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .tappable { haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); onChange(!checked) },
    ) {
        Box(
            Modifier.padding(2.dp).offset { IntOffset((x * 20.dp.toPx()).roundToInt(), 0) }
                .size(27.dp).clip(CircleShape).background(Color.White)
                .border(0.5.dp, c.thumbBorder, CircleShape),
        )
    }
}

/** UISegmentedControl with a sliding selection. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    val pos by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.85f, stiffness = 500f), label = "seg")
    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(c.fill).padding(2.dp)) {
        val segW = maxWidth / options.size
        Box(
            Modifier.offset { IntOffset((segW.toPx() * pos).roundToInt(), 0) }.width(segW).fillMaxHeight()
                .clip(RoundedCornerShape(7.dp)).background(if (c.dark) c.fillStrong else Color.White)
                .border(0.5.dp, if (c.dark) Color.Transparent else c.separator, RoundedCornerShape(7.dp)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().tappable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Txt(label, Type.footnote, weight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * UISlider: 4 dp track, white 28 dp thumb. [detent] snaps near a value (with a haptic tick),
 * e.g. unity gain on stem faders.
 */
@Composable
fun IosSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Ios.colors.accent,
    track: Color = Ios.colors.fillStrong,
    detent: Float? = null,
    enabled: Boolean = true,
    thumb: Dp = 28.dp,
    onRelease: (() -> Unit)? = null,
) {
    val c = Ios.colors
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Box(
        modifier.height(maxOf(thumb, 28.dp) + 8.dp).fillMaxWidth().alpha(if (enabled) 1f else 0.4f)
            .pointerInput(enabled, detent) {
                if (!enabled) return@pointerInput
                val t = thumb.toPx()
                fun valueAt(x: Float) = ((x - t / 2) / (size.width - t)).coerceIn(0f, 1f)
                fun snap(v: Float): Float {
                    if (detent == null) return v
                    return if (abs(v - detent) < 0.025f) detent else v
                }
                var start = 0f
                var acc = 0f
                detectDragGestures(
                    onDragStart = { start = current; acc = 0f },
                    onDragEnd = { onRelease?.invoke() },
                    onDragCancel = { onRelease?.invoke() },
                ) { change, drag ->
                    change.consume()
                    acc += drag.x
                    val before = current
                    val v = snap((start + acc / (size.width - t)).coerceIn(0f, 1f))
                    if (detent != null && v == detent && before != detent) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    change(v)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    val t = thumb.toPx()
                    change(((o.x - t / 2) / (size.width - t)).coerceIn(0f, 1f))
                    onRelease?.invoke()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxWidth().height(thumb)) {
            val t = thumb.toPx()
            val h = 4.dp.toPx()
            val y = size.height / 2
            val x = t / 2 + (size.width - t) * value.coerceIn(0f, 1f)
            drawRoundRect(track, Offset(t / 2, y - h / 2), Size(size.width - t, h), CornerRadius(h / 2))
            drawRoundRect(fill, Offset(t / 2, y - h / 2), Size(x - t / 2, h), CornerRadius(h / 2))
            if (detent != null) {
                val dx = t / 2 + (size.width - t) * detent
                drawLine(c.label3, Offset(dx, y - 7.dp.toPx()), Offset(dx, y + 7.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            }
            if (c.thumbBorder.alpha > 0f) drawCircle(c.thumbBorder, t / 2 + with(density) { 0.5.dp.toPx() }, Offset(x, y))
            drawCircle(Color.White, t / 2, Offset(x, y))
        }
    }
}

/** A vertical UISlider for the EQ: centre-detented, value in -1..1. */
@Composable
fun VerticalBandSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, fill: Color = Ios.colors.accent) {
    val c = Ios.colors
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.fillMaxHeight().width(32.dp)
            .pointerInput(Unit) {
                val t = 24.dp.toPx()
                var start = 0f
                var acc = 0f
                detectDragGestures(onDragStart = { start = current; acc = 0f }) { ch, drag ->
                    ch.consume()
                    acc += drag.y
                    val before = current
                    var v = (start - 2f * acc / (size.height - t)).coerceIn(-1f, 1f)
                    if (abs(v) < 0.04f) v = 0f
                    if (v == 0f && before != 0f) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    change(v)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { change(0f) }) },
    ) {
        val t = 24.dp.toPx()
        val w = 4.dp.toPx()
        val x = size.width / 2
        val top = t / 2
        val bottom = size.height - t / 2
        val mid = (top + bottom) / 2
        val y = mid - (bottom - top) / 2 * value
        drawRoundRect(c.fillStrong, Offset(x - w / 2, top), Size(w, bottom - top), CornerRadius(w / 2))
        drawRoundRect(fill, Offset(x - w / 2, minOf(y, mid)), Size(w, abs(y - mid)), CornerRadius(w / 2))
        drawLine(c.label3, Offset(x - 8.dp.toPx(), mid), Offset(x + 8.dp.toPx(), mid), 1.dp.toPx())
        if (c.thumbBorder.alpha > 0f) drawCircle(c.thumbBorder, t / 2 + 0.5.dp.toPx(), Offset(x, y))
        drawCircle(Color.White, t / 2, Offset(x, y))
    }
}

/** Apple Music's Play / Shuffle buttons: filled rounded rect, accent glyph and label. */
@Composable
fun PillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(12.dp)).background(c.fill).pressFade(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, c.accent, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Txt(text, Type.headline, color = c.accent)
    }
}

/** Capsule chip, accent-filled when selected. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.colors
    Box(
        modifier.height(32.dp).clip(CircleShape).background(if (selected) c.accent else c.fill)
            .pressFade(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.footnote, color = if (selected) Color.White else c.label, weight = FontWeight.SemiBold)
    }
}

/** Small capsule tag, e.g. "STEMS" next to a track. */
@Composable
fun Tag(text: String, color: Color = Ios.colors.label2, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.caption2.copy(letterSpacing = Type.caption2.letterSpacing), color = color, weight = FontWeight.Bold)
    }
}

/** UIActivityIndicatorView: eight spokes stepping round. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = Ios.colors.label2) {
    val t = rememberInfiniteTransition(label = "spin")
    val step by t.animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "step")
    Canvas(modifier.size(size)) {
        val s = step.toInt()
        val r0 = this.size.minDimension * 0.22f
        val r1 = this.size.minDimension * 0.46f
        val w = this.size.minDimension * 0.09f
        for (i in 0 until 8) {
            val a = 1f - ((s - i + 8) % 8) / 8f
            rotate(i * 45f) {
                drawLine(color.copy(alpha = color.alpha * (0.25f + 0.75f * a)), Offset(center.x, center.y - r0), Offset(center.x, center.y - r1), w, StrokeCap.Round)
            }
        }
    }
}

/** Downloaded marker: filled circle with a down arrow, as in Apple Music's lists. */
@Composable
fun DownloadedMark(modifier: Modifier = Modifier, color: Color = Ios.colors.label2, size: Dp = 14.dp) {
    val bg = Ios.colors.bg
    Canvas(modifier.size(size)) {
        drawCircle(color)
        val w = this.size.width
        val sw = w * 0.12f
        drawLine(bg, Offset(w / 2, w * 0.26f), Offset(w / 2, w * 0.7f), sw, StrokeCap.Round)
        drawLine(bg, Offset(w * 0.32f, w * 0.52f), Offset(w / 2, w * 0.7f), sw, StrokeCap.Round)
        drawLine(bg, Offset(w * 0.68f, w * 0.52f), Offset(w / 2, w * 0.7f), sw, StrokeCap.Round)
    }
}

/** Download progress ring. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = Ios.colors.accent) {
    val track = Ios.colors.fillStrong
    Canvas(modifier.size(size)) {
        val sw = 2.dp.toPx()
        drawCircle(track, this.size.minDimension / 2 - sw / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(sw / 2, sw / 2),
            Size(this.size.width - sw, this.size.height - sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Animated "now playing" bars used in place of a track number. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Ios.colors.accent) {
    val t = rememberInfiniteTransition(label = "bars")
    val phase by t.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Canvas(modifier.size(16.dp)) {
        val w = size.width / 4.5f
        for (i in 0 until 3) {
            val h = if (playing) (0.35f + 0.65f * abs(kotlin.math.sin(phase + i * 1.9f))) else 0.3f + i * 0.15f
            val bh = size.height * h
            drawRoundRect(color, Offset(i * w * 1.6f + w * 0.3f, size.height - bh), Size(w, bh), CornerRadius(w / 3))
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ios.colors.separator) {
    val px = with(LocalDensity.current) { 1f / density }
    Box(modifier.fillMaxWidth().height(px.dp).background(color))
}

@Composable
fun Chevron(modifier: Modifier = Modifier) = Icon(Sym.chevronRight, Ios.colors.label3, modifier, size = 14.dp)
