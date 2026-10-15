package com.stemroller.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stemroller.mobile.App
import com.stemroller.mobile.engine.EQ_BANDS
import com.stemroller.mobile.engine.EQ_SLOPES
import com.stemroller.mobile.engine.EqChannel
import com.stemroller.mobile.engine.EqFilter
import com.stemroller.mobile.engine.EqMath
import com.stemroller.mobile.engine.FilterType
import com.stemroller.mobile.eq.ApoConfig
import com.stemroller.mobile.eq.EqController
import com.stemroller.mobile.ui.components.Cell
import com.stemroller.mobile.ui.components.IconButton
import com.stemroller.mobile.ui.components.IosSlider
import com.stemroller.mobile.ui.components.IosSwitch
import com.stemroller.mobile.ui.components.LocalOverlays
import com.stemroller.mobile.ui.components.Page
import com.stemroller.mobile.ui.components.SegmentedControl
import com.stemroller.mobile.ui.components.SheetAction
import com.stemroller.mobile.ui.components.section
import com.stemroller.mobile.ui.nav.LocalNav
import com.stemroller.mobile.ui.nav.Route
import com.stemroller.mobile.ui.theme.Ios
import com.stemroller.mobile.ui.theme.Sym
import com.stemroller.mobile.ui.theme.Txt
import com.stemroller.mobile.ui.theme.Type
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

// ---- formatting and slider mappings --------------------------------------------------------------

private const val F_LO = 20.0
private const val F_HI = 20000.0
private val RIGHT = Color(0xFFFF9500)

fun hz(f: Float) = if (f >= 1000f) String.format(Locale.ROOT, if (f >= 10000f) "%.1f kHz" else "%.2f kHz", f / 1000f).replace(".00 ", " ").replace(".0 ", " ")
    else "${f.toInt()} Hz"

private fun signedDb(v: Float) = (if (v > 0.005f) "+" else if (v < -0.005f) "−" else "") + String.format(Locale.ROOT, "%.1f dB", abs(v))

private fun freqToPos(f: Float) = (ln(f / F_LO) / ln(F_HI / F_LO)).toFloat().coerceIn(0f, 1f)
private fun posToFreq(p: Float) = (F_LO * (F_HI / F_LO).pow(p.toDouble())).toFloat()
private fun qToPos(q: Float) = (ln(q / EqController.Q_MIN) / ln(EqController.Q_MAX / EqController.Q_MIN)).coerceIn(0f, 1f)
private fun posToQ(p: Float) = EqController.Q_MIN * (EqController.Q_MAX / EqController.Q_MIN).pow(p)

/** Rounds a dragged frequency to what a person would type: 3 significant figures. */
private fun tidyFreq(f: Float): Float {
    val mag = 10.0.pow(kotlin.math.floor(log10(f.toDouble())) - 2).toFloat()
    return (Math.round(f / mag) * mag)
}

private fun summary(f: EqFilter) = buildList {
    if (f.type.hasGain) add(signedDb(f.gain))
    if (f.type.hasSlope) add("${f.slope} dB/oct")
    if (!f.type.hasSlope || f.slope == 12) add("Q " + String.format(Locale.ROOT, "%.2f", f.q))
    if (f.channel != EqChannel.Both) add(f.channel.label)
}.joinToString(" · ")

/** Sensible starting points, audible but mild, for a new filter of each type. */
private fun newFilter(type: FilterType) = EqFilter(EqController.newFilterId(), type).let {
    when (type) {
        FilterType.Peak -> it.copy(freq = 1000f, gain = 3f, q = 1.41f)
        FilterType.LowShelf -> it.copy(freq = 105f, gain = 3f, q = 0.71f)
        FilterType.HighShelf -> it.copy(freq = 8000f, gain = 3f, q = 0.71f)
        FilterType.LowPass -> it.copy(freq = 16000f, q = 0.71f)
        FilterType.HighPass -> it.copy(freq = 30f, q = 0.71f, slope = 24)
        FilterType.BandPass -> it.copy(freq = 1000f, q = 0.71f)
        FilterType.Notch -> it.copy(freq = 60f, q = 4f)
        FilterType.AllPass -> it.copy(freq = 1000f, q = 0.71f)
    }
}

// ---- response curve ------------------------------------------------------------------------------

/**
 * The EQ's response, log frequency across, dB up. Left and right are drawn separately when
 * some filter applies to only one of them (right in orange). [nodes] are drawn as numbered
 * handles; [highlight] gets its own curve in the accent colour.
 */
@Composable
fun EqCurve(
    chain: List<EqFilter>,
    preampDb: Float,
    enabled: Boolean,
    modifier: Modifier,
    range: Float = 18f,
    bandMarkers: Boolean = false,
    nodes: List<EqFilter> = emptyList(),
    highlight: EqFilter? = null,
    labels: Boolean = false,
) {
    val c = Ios.colors
    val freqs = remember { EqMath.logFreqs(200, F_LO, F_HI) }
    val left = remember(chain) { EqMath.response(chain, freqs, true) }
    val right = remember(chain) { if (chain.any { it.channel != EqChannel.Both }) EqMath.response(chain, freqs, false) else null }
    val own = remember(highlight) { highlight?.let { EqMath.response(listOf(it), freqs) } }
    val text = rememberTextMeasurer()
    val labelStyle = Type.caption2.copy(color = c.label3)
    Canvas(modifier) {
        fun y(db: Double) = (size.height / 2 - (db / range) * (size.height / 2)).toFloat()
        fun x(f: Double) = (ln(f / F_LO) / ln(F_HI / F_LO) * size.width).toFloat()
        val step = 6.0
        var d = step
        while (d < range) {
            drawLine(c.separator, Offset(0f, y(d)), Offset(size.width, y(d)), 1f)
            drawLine(c.separator, Offset(0f, y(-d)), Offset(size.width, y(-d)), 1f)
            d += step
        }
        drawLine(c.label3, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.5f)
        val grid = if (bandMarkers) EQ_BANDS.map { it.toDouble() } else listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
        for (f in grid) drawLine(c.separator, Offset(x(f), 0f), Offset(x(f), size.height), 1f)
        if (labels) {
            for ((f, l) in listOf(100.0 to "100", 1000.0 to "1k", 10000.0 to "10k")) {
                drawText(text, l, Offset(x(f) + 3.dp.toPx(), size.height - 14.dp.toPx()), labelStyle)
            }
            drawText(text, "+${range.toInt()}", Offset(3.dp.toPx(), 1.dp.toPx()), labelStyle)
            drawText(text, "−${range.toInt()}", Offset(3.dp.toPx(), size.height - 14.dp.toPx()), labelStyle)
        }
        val main = if (enabled) c.accent else c.label3
        if (own != null) curve(own, ::y, if (enabled) main.copy(alpha = 0.35f) else c.label3, 1.5.dp.toPx(), fill = y(0.0))
        curve(left, ::y, if (highlight != null) c.label else main, 2.5.dp.toPx())
        right?.let { curve(it, ::y, if (enabled) RIGHT else c.label3, 2.dp.toPx()) }
        if (preampDb < 0f && highlight == null) {
            val net = DoubleArray(left.size) { left[it] + preampDb }
            curve(net, ::y, c.label2, 1.2.dp.toPx(), dashed = true)
        }
        for ((i, f) in nodes.withIndex()) {
            val sel = f.id == highlight?.id
            val cx = x(f.freq.toDouble().coerceIn(F_LO, F_HI))
            val cy = if (f.type.hasGain) y(f.gain.toDouble().coerceIn(-range.toDouble(), range.toDouble())) else y(0.0)
            val r = (if (sel) 11 else 9).dp.toPx()
            val col = when {
                !f.on -> c.label3
                f.channel == EqChannel.Right -> RIGHT
                else -> main
            }
            drawCircle(if (sel) Color.White else col, r, Offset(cx, cy))
            if (sel) drawCircle(col, r, Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
            val label = text.measure("${i + 1}", Type.caption2.copy(color = if (sel) col else Color.White, fontWeight = FontWeight.Bold))
            drawText(label, topLeft = Offset(cx - label.size.width / 2f, cy - label.size.height / 2f))
        }
    }
}

private fun DrawScope.curve(v: DoubleArray, y: (Double) -> Float, color: Color, width: Float, dashed: Boolean = false, fill: Float? = null) {
    val path = Path()
    v.forEachIndexed { i, d ->
        val x = i / (v.size - 1f) * size.width
        if (i == 0) path.moveTo(x, y(d)) else path.lineTo(x, y(d))
    }
    if (fill != null) {
        val area = Path().apply { addPath(path); lineTo(size.width, fill); lineTo(0f, fill); close() }
        drawPath(area, color.copy(alpha = color.alpha * 0.4f))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
}

// ---- filter list ---------------------------------------------------------------------------------

@Composable
fun EqFiltersScreen(profileId: String) {
    val eq = App.graph.eq
    val c = Ios.colors
    val nav = LocalNav.current
    val overlays = LocalOverlays.current
    val context = LocalContext.current
    val state by eq.state.collectAsState()
    val profile = state.profiles.firstOrNull { it.id == profileId }
    LaunchedEffect(profile == null) { if (profile == null) nav.pop() }
    if (profile == null) return
    val chain = remember(profile) { profile.chain }
    val preamp = remember(profile, state.antiClip) { profile.preamp + if (state.antiClip) EqMath.autoPreampDb(chain, profile.preamp) else 0f }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    fun add() {
        overlays.actions("Add Filter", FilterType.entries.map { t ->
            SheetAction(t.label) {
                val f = newFilter(t)
                eq.putFilter(profileId, f)
                nav.push(Route.EqFilterPage(profileId, f.id))
            }
        })
    }

    fun import() {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val parsed = ApoConfig.parse(text)
        if (parsed == null) {
            overlays.hud("No Equalizer APO filters on the clipboard", Sym.info)
            return
        }
        val what = listOfNotNull(
            "${parsed.filters.size} filter${if (parsed.filters.size == 1) "" else "s"}".takeIf { parsed.filters.isNotEmpty() },
            parsed.preamp?.let { "preamp ${signedDb(it)}" },
        ).joinToString(" and ")
        val skipped = if (parsed.skipped > 0) " ${parsed.skipped} line${if (parsed.skipped == 1) "" else "s"} it doesn't understand (GraphicEQ, Delay…) will be skipped." else ""
        overlays.confirm("Replace Filters?", "Loads $what into “${profile.name}”, replacing its ${profile.filters.size} filter${if (profile.filters.size == 1) "" else "s"}.$skipped", "Replace", destructive = false) {
            eq.setFilters(profileId, parsed.filters, parsed.preamp)
            overlays.hud("Imported $what")
        }
    }

    Page("Filters", grouped = true, largeTitle = false, trailing = {
        IconButton(Sym.ellipsis, {
            overlays.actions("Equalizer APO / AutoEQ", buildList {
                add(SheetAction("Paste APO Config") { import() })
                add(SheetAction("Copy as APO Config") {
                    clipboard.setPrimaryClip(ClipData.newPlainText("${profile.name} EQ", ApoConfig.format(profile)))
                    overlays.hud("Copied")
                })
                if (profile.filters.isNotEmpty()) add(SheetAction("Remove All Filters", destructive = true) {
                    overlays.confirm("Remove All Filters?", "The graphic EQ bands stay as they are.", "Remove All") { eq.setFilters(profileId, emptyList(), null) }
                })
            })
        })
        IconButton(Sym.plus, { add() })
    }) {
        section("curve", header = profile.name, footer = "Tap a numbered handle to edit that filter. Right-channel filters are orange. Paste an AutoEQ ParametricEQ.txt (from the ••• menu) to load a headphone correction.", rows = listOf {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                val nodes = profile.filters
                val tap by rememberUpdatedState { o: Offset, w: Float, h: Float ->
                    val hit = nodes.withIndex().minByOrNull { (_, f) ->
                        val dx = freqToPos(f.freq) * w - o.x
                        val dy = (if (f.type.hasGain) h / 2 - f.gain / 18f * h / 2 else h / 2) - o.y
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val f = hit.value
                        val dx = freqToPos(f.freq) * w - o.x
                        if (abs(dx) < 40f * context.resources.displayMetrics.density) nav.push(Route.EqFilterPage(profileId, f.id))
                    }
                }
                EqCurve(chain, preamp, state.enabled, Modifier.fillMaxWidth().height(170.dp).pointerInput(Unit) {
                    detectTapGestures { o -> tap(o, size.width.toFloat(), size.height.toFloat()) }
                }, nodes = nodes, labels = true)
            }
        })

        section("preamp", header = "Preamp", footer = if (state.antiClip) "Anti-clipping adds ${signedDb(preamp - profile.preamp)} on top, so the total is ${signedDb(preamp)}." else null, rows = listOf {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IosSlider((profile.preamp + EqController.PREAMP_RANGE) / (2 * EqController.PREAMP_RANGE), { v ->
                    eq.setPreamp(profileId, Math.round((v * 2 - 1) * EqController.PREAMP_RANGE * 10) / 10f)
                }, Modifier.weight(1f), detent = 0.5f)
                ValueLabel(signedDb(profile.preamp), 76.dp) {
                    overlays.prompt("Preamp", "In dB, −24 to +24.", String.format(Locale.ROOT, "%.1f", profile.preamp), "Set") { s ->
                        s.replace(',', '.').replace('−', '-').toFloatOrNull()?.let { eq.setPreamp(profileId, it) }
                    }
                }
            }
        })

        section("list", header = "Filters", rows = profile.filters.mapIndexed<EqFilter, @Composable () -> Unit> { i, f ->
            {
                Cell("${i + 1}. ${f.type.label} · ${hz(f.freq)}", subtitle = summary(f).ifEmpty { null },
                    titleColor = if (f.on) c.label else c.label2, trailing = { IosSwitch(f.on, { on -> eq.putFilter(profileId, f.copy(on = on)) }) },
                    onLongClick = {
                        overlays.actions("${f.type.label} · ${hz(f.freq)}", listOf(
                            SheetAction("Duplicate") { eq.putFilter(profileId, f.copy(id = EqController.newFilterId())) },
                            SheetAction("Delete", destructive = true) { eq.removeFilter(profileId, f.id) },
                        ))
                    }) { nav.push(Route.EqFilterPage(profileId, f.id)) }
            }
        } + listOf<@Composable () -> Unit> { Cell("Add Filter…", titleColor = c.accent) { add() } })
    }
}

@Composable
private fun ValueLabel(text: String, width: Dp, onClick: () -> Unit) {
    val c = Ios.colors
    Box(Modifier.width(width).padding(start = 10.dp).pointerInput(Unit) { detectTapGestures { onClick() } }, contentAlignment = Alignment.CenterEnd) {
        Txt(text, Type.subhead.copy(fontFeatureSettings = "tnum"), color = c.accent)
    }
}

// ---- one filter ----------------------------------------------------------------------------------

@Composable
fun EqFilterScreen(profileId: String, filterId: String) {
    val eq = App.graph.eq
    val c = Ios.colors
    val nav = LocalNav.current
    val overlays = LocalOverlays.current
    val state by eq.state.collectAsState()
    val profile = state.profiles.firstOrNull { it.id == profileId }
    val filter = profile?.filters?.firstOrNull { it.id == filterId }
    LaunchedEffect(filter == null) { if (filter == null) nav.pop() }
    if (profile == null || filter == null) return
    val f: EqFilter = filter
    val number = profile.filters.indexOf(f) + 1
    val chain = remember(profile) { profile.chain }
    val current by rememberUpdatedState(f)
    fun put(nf: EqFilter) = eq.putFilter(profileId, nf)
    fun number(title: String, hint: String, initial: Float, set: (Float) -> Unit) =
        overlays.prompt(title, hint, String.format(Locale.ROOT, "%.2f", initial).trimEnd('0').trimEnd('.'), "Set") { s ->
            s.replace(',', '.').replace('−', '-').filter { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull()?.let(set)
        }

    Page("$number. ${f.type.label}", grouped = true, largeTitle = false, trailing = {
        IconButton(Sym.ellipsis, {
            overlays.actions("${f.type.label} · ${hz(f.freq)}", listOf(
                SheetAction("Duplicate") {
                    val copy = f.copy(id = EqController.newFilterId())
                    put(copy)
                    nav.push(Route.EqFilterPage(profileId, copy.id))
                },
                SheetAction("Delete Filter", destructive = true) { eq.removeFilter(profileId, f.id) },
            ))
        })
    }) {
        section("curve", footer = if (f.type.hasGain) "Drag the handle: sideways for frequency, up and down for gain." else "Drag the handle sideways to change the frequency.", rows = listOf {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                EqCurve(chain, 0f, state.enabled && f.on, Modifier.fillMaxWidth().height(190.dp).pointerInput(Unit) {
                    var startPos = Offset.Zero
                    var acc = Offset.Zero
                    detectDragGestures(onDragStart = { o -> startPos = o; acc = Offset.Zero }) { ch, drag ->
                        ch.consume()
                        acc += drag
                        val cur = current
                        val p = ((startPos.x + acc.x) / size.width).coerceIn(0f, 1f)
                        val gain = if (cur.type.hasGain) (18f * (1f - 2f * (startPos.y + acc.y) / size.height)).coerceIn(-18f, 18f) else cur.gain
                        put(cur.copy(freq = tidyFreq(posToFreq(p)), gain = Math.round(gain * 10) / 10f))
                    }
                }, nodes = profile.filters, highlight = f, labels = true)
            }
        })

        section("type", rows = listOf(
            { Cell("Type", value = f.type.label, chevron = true) {
                overlays.actions("Filter Type", FilterType.entries.map { t ->
                    SheetAction((if (t == f.type) "✓ " else "") + t.label) {
                        put(f.copy(type = t, q = if (t == FilterType.Notch && f.type != FilterType.Notch) 4f else f.q))
                    }
                })
            } },
            { Cell("On", trailing = { IosSwitch(f.on, { put(f.copy(on = it)) }) }) },
        ))

        val rows = buildList<@Composable () -> Unit> {
            add {
                ParamRow("Frequency", hz(f.freq), { number("Frequency", "In Hz, 10 to 22000.", f.freq) { v -> put(f.copy(freq = v)) } }) {
                    IosSlider(freqToPos(f.freq), { put(f.copy(freq = tidyFreq(posToFreq(it)))) })
                }
            }
            if (f.type.hasGain) add {
                ParamRow("Gain", signedDb(f.gain), { number("Gain", "In dB, −24 to +24.", f.gain) { v -> put(f.copy(gain = v)) } }) {
                    IosSlider((f.gain + EqController.FILTER_GAIN) / (2 * EqController.FILTER_GAIN), {
                        put(f.copy(gain = Math.round((it * 2 - 1) * EqController.FILTER_GAIN * 10) / 10f))
                    }, detent = 0.5f)
                }
            }
            if (!f.type.hasSlope || f.slope == 12) add {
                ParamRow("Q", String.format(Locale.ROOT, "%.2f", f.q), { number("Q", "0.1 (broad) to 20 (narrow). One octave is 1.41.", f.q) { v -> put(f.copy(q = v)) } }) {
                    IosSlider(qToPos(f.q), { put(f.copy(q = Math.round(posToQ(it) * 100) / 100f)) })
                }
            }
            if (f.type.hasSlope) add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Txt("Slope (dB per octave)", Type.subhead, color = c.label2)
                    SegmentedControl(EQ_SLOPES.map { it.toString() }, EQ_SLOPES.indexOf(f.slope).coerceAtLeast(0), { put(f.copy(slope = EQ_SLOPES[it])) },
                        Modifier.padding(top = 8.dp))
                }
            }
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Txt("Channel", Type.subhead, color = c.label2)
                    SegmentedControl(EqChannel.entries.map { it.label }, f.channel.ordinal, { put(f.copy(channel = EqChannel.entries[it])) },
                        Modifier.padding(top = 8.dp))
                }
            }
        }
        section("params", footer = when {
            f.type.hasSlope && f.slope != 12 -> "Steeper than 12 dB/oct is a Butterworth response, so there's no Q."
            f.type == FilterType.AllPass -> "An all-pass changes phase around its frequency, not level, so the curve stays flat."
            else -> "Tap a value to type it."
        }, rows = rows)
    }
}

@Composable
private fun ParamRow(title: String, value: String, onValue: () -> Unit, slider: @Composable () -> Unit) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Type.subhead, Modifier.weight(1f), color = c.label2)
            ValueLabel(value, 110.dp, onValue)
        }
        slider()
    }
}
