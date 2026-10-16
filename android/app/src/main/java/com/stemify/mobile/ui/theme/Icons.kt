package com.stemify.mobile.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SF Symbols-flavoured glyphs on a 24-unit grid. Each layer is a path that's either
 * filled (stroke = 0) or stroked with round caps, like SF's regular weight.
 */
object Sym {
    private class Layer(val d: String, val stroke: Float = 0f)

    private fun icon(name: String, vararg layers: Layer): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (l in layers) {
            val nodes = PathParser().parsePathString(l.d).toNodes()
            if (l.stroke > 0f) {
                b.addPath(nodes, fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = l.stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            } else {
                b.addPath(nodes, fill = SolidColor(Color.Black))
            }
        }
        return b.build()
    }

    private fun f(d: String) = Layer(d)
    private fun s(d: String, w: Float = 1.9f) = Layer(d, w)
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val play by lazy { icon("play", f("M7.5 4.9v14.2c0 .82.9 1.32 1.6.88l11-7.1a1.05 1.05 0 0 0 0-1.76l-11-7.1c-.7-.44-1.6.06-1.6.88z")) }
    val pause by lazy {
        icon("pause", f("M6.2 4.5h3.1c.45 0 .8.35.8.8v13.4c0 .45-.35.8-.8.8H6.2a.8.8 0 0 1-.8-.8V5.3c0-.45.35-.8.8-.8zm8.5 0h3.1c.45 0 .8.35.8.8v13.4c0 .45-.35.8-.8.8h-3.1a.8.8 0 0 1-.8-.8V5.3c0-.45.35-.8.8-.8z"))
    }
    val forward by lazy {
        icon("forward", f("M1.6 6.8v10.4c0 .8.88 1.28 1.55.84l7.95-5.2a1 1 0 0 0 0-1.68L3.15 5.96c-.67-.44-1.55.04-1.55.84zm11 0v10.4c0 .8.88 1.28 1.55.84l7.95-5.2a1 1 0 0 0 0-1.68l-7.95-5.2c-.67-.44-1.55.04-1.55.84z"))
    }
    val backward by lazy {
        icon("backward", f("M22.4 6.8v10.4c0 .8-.88 1.28-1.55.84l-7.95-5.2a1 1 0 0 1 0-1.68l7.95-5.2c.67-.44 1.55.04 1.55.84zm-11 0v10.4c0 .8-.88 1.28-1.55.84l-7.95-5.2a1 1 0 0 1 0-1.68l7.95-5.2c.67-.44 1.55.04 1.55.84z"))
    }
    /** A circular arrow round a "10": skip back / ahead ten seconds. */
    val gobackward10 by lazy {
        icon("gobackward.10", s("M12 5a8 8 0 1 1-7.6 5.5M14 2.5L11.5 5 14 7.5", 1.8f), s(TEN, 1.6f))
    }
    val goforward10 by lazy {
        icon("goforward.10", s("M12 5a8 8 0 1 0 7.6 5.5M10 2.5L12.5 5 10 7.5", 1.8f), s(TEN, 1.6f))
    }
    private const val TEN = "M8.9 11.2l1.3-.9v6.2M14.3 10.3c-1 0-1.65 1.25-1.65 3.1s.65 3.1 1.65 3.1 1.65-1.25 1.65-3.1-.65-3.1-1.65-3.1z"
    val chevronRight by lazy { icon("chevron.right", s("M9 4.5l7.5 7.5L9 19.5", 2.4f)) }
    val chevronLeft by lazy { icon("chevron.left", s("M15.5 4l-8 8 8 8", 2.6f)) }
    val search by lazy { icon("magnifyingglass", s("${circle(10.5f, 10.5f, 6.75f)}M15.6 15.6l5 5", 2.2f)) }
    val note by lazy { icon("music.note", s("${circle(6.5f, 17.5f, 3f)}M9.5 17.5V4.2l9 -1.7v12.6${circle(15.5f, 14.8f, 3f)}M9.5 8.2l9-1.7", 1.9f)) }
    val noteList by lazy {
        icon("music.note.list", s("M3 6h11M3 11h11M3 16h6", 2f), s("M18.5 17.2V5l3-.8", 1.9f), f(circle(16.4f, 17.6f, 2.4f)))
    }
    val library by lazy {
        icon("square.stack", s("M6.5 3.8h11M4.5 6.8h15", 1.8f),
            f("M4.2 9.6h15.6c.94 0 1.7.76 1.7 1.7v7.5c0 .94-.76 1.7-1.7 1.7H4.2c-.94 0-1.7-.76-1.7-1.7v-7.5c0-.94.76-1.7 1.7-1.7z"))
    }
    val sliders by lazy {
        icon("slider.vertical.3", s("M6 3.5v17M12 3.5v17M18 3.5v17", 1.8f),
            f(circle(6f, 14.5f, 2.6f)), f(circle(12f, 8f, 2.6f)), f(circle(18f, 16f, 2.6f)))
    }
    val gear by lazy {
        icon("gearshape", f("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"))
    }
    val ellipsis by lazy { icon("ellipsis", f(circle(5f, 12f, 1.9f)), f(circle(12f, 12f, 1.9f)), f(circle(19f, 12f, 1.9f))) }
    val plus by lazy { icon("plus", s("M12 5v14M5 12h14", 2.2f)) }
    val check by lazy { icon("checkmark", s("M5 12.5l4.5 4.5L19 7.5", 2.4f)) }
    val xmark by lazy { icon("xmark", s("M6.5 6.5l11 11M17.5 6.5l-11 11", 2.2f)) }
    val shuffle by lazy {
        icon("shuffle", s("M3 7h3.2c2.4 0 3.8 1.3 5.3 5s2.9 5 5.3 5H20.5M3 17h3.2c1.2 0 2.1-.35 2.9-1.1M14.3 8.1c.8-.75 1.7-1.1 2.9-1.1H20.5M18 4.5L20.5 7 18 9.5M18 14.5l2.5 2.5-2.5 2.5", 1.9f))
    }
    val repeat by lazy {
        icon("repeat", s("M4 11.5V9.5A3.5 3.5 0 0 1 7.5 6H20M17.2 3.2L20 6l-2.8 2.8M20 12.5v2A3.5 3.5 0 0 1 16.5 18H4M6.8 20.8L4 18l2.8-2.8", 1.9f))
    }
    val repeatOne by lazy {
        icon("repeat.1", s("M4 11.5V9.5A3.5 3.5 0 0 1 7.5 6H20M17.2 3.2L20 6l-2.8 2.8M20 12.5v2A3.5 3.5 0 0 1 16.5 18H4M6.8 20.8L4 18l2.8-2.8", 1.9f),
            s("M11 10.6l1.4-.9v5", 1.7f))
    }
    val speaker by lazy { icon("speaker.fill", f("M3.2 9.2h3.1L11 5.3c.4-.33 1-.05 1 .47v12.46c0 .52-.6.8-1 .47l-4.7-3.9H3.2a.7.7 0 0 1-.7-.7V9.9c0-.39.31-.7.7-.7z")) }
    val speakerWave by lazy {
        icon("speaker.wave.3.fill", f("M2.2 9.2h3.1L10 5.3c.4-.33 1-.05 1 .47v12.46c0 .52-.6.8-1 .47l-4.7-3.9H2.2a.7.7 0 0 1-.7-.7V9.9c0-.39.31-.7.7-.7z"),
            s("M14 9.3a3.8 3.8 0 0 1 0 5.4M16.6 6.8a7.4 7.4 0 0 1 0 10.4M19.2 4.3a11 11 0 0 1 0 15.4", 1.7f))
    }
    val speakerSlash by lazy {
        icon("speaker.slash.fill", f("M2.2 9.2h3.1L10 5.3c.4-.33 1-.05 1 .47v12.46c0 .52-.6.8-1 .47l-4.7-3.9H2.2a.7.7 0 0 1-.7-.7V9.9c0-.39.31-.7.7-.7z"),
            s("M15 9.5l5 5M20 9.5l-5 5", 1.9f))
    }
    val download by lazy { icon("arrow.down.circle", s("${circle(12f, 12f, 9.25f)}M12 7.5v8.5M8.5 12.6L12 16l3.5-3.4", 1.8f)) }
    val queue by lazy {
        icon("list.bullet", s("M8.5 6h12M8.5 12h12M8.5 18h12", 1.9f), f(circle(4f, 6f, 1.5f)), f(circle(4f, 12f, 1.5f)), f(circle(4f, 18f, 1.5f)))
    }
    val person by lazy { icon("person", s("${circle(12f, 7.8f, 4f)}M4.5 20.5c0-3.7 3.4-6.2 7.5-6.2s7.5 2.5 7.5 6.2", 1.9f)) }
    val album by lazy {
        icon("square.stack", s("M6 3.8h12", 1.8f),
            f("M5 6.8h14c.94 0 1.7.76 1.7 1.7v10.8c0 .94-.76 1.7-1.7 1.7H5c-.94 0-1.7-.76-1.7-1.7V8.5c0-.94.76-1.7 1.7-1.7z"))
    }
    val waveform by lazy { icon("waveform", s("M3 10.5v3M6.6 7v10M10.2 4.5v15M13.8 8.5v7M17.4 6v12M21 10v4", 2f)) }
    val headphones by lazy {
        icon("headphones", s("M4 15.5v-3.2a8 8 0 0 1 16 0v3.2", 1.9f),
            f("M3.5 14.2h2.6c.66 0 1.2.54 1.2 1.2v4.2c0 .66-.54 1.2-1.2 1.2H4.9c-.77 0-1.4-.63-1.4-1.4v-5.2zM20.5 14.2h-2.6c-.66 0-1.2.54-1.2 1.2v4.2c0 .66.54 1.2 1.2 1.2h1.2c.77 0 1.4-.63 1.4-1.4v-5.2z"))
    }
    val phone by lazy { icon("iphone", s("M8.2 2.8h7.6c1.1 0 2 .9 2 2v14.4c0 1.1-.9 2-2 2H8.2c-1.1 0-2-.9-2-2V4.8c0-1.1.9-2 2-2zM10.6 5.6h2.8", 1.8f)) }
    val usb by lazy { icon("cable.connector", s("M8.5 3.5h7v6.5h-7zM12 10v10.5M10.5 6h.01M13.5 6h.01", 1.8f)) }
    val server by lazy {
        icon("server.rack", s("M4.5 4.5h15c.55 0 1 .45 1 1v3.5c0 .55-.45 1-1 1h-15a1 1 0 0 1-1-1V5.5c0-.55.45-1 1-1zM4.5 14h15c.55 0 1 .45 1 1v3.5c0 .55-.45 1-1 1h-15a1 1 0 0 1-1-1V15c0-.55.45-1 1-1z", 1.8f),
            f(circle(7f, 7.25f, 1.1f)), f(circle(7f, 16.75f, 1.1f)))
    }
    val refresh by lazy { icon("arrow.clockwise", s("M19.5 12.5A7.5 7.5 0 1 1 17 6.8M19.5 3.5v4.4h-4.4", 2f)) }
    val grip by lazy { icon("line.3.horizontal", s("M4 7.5h16M4 12h16M4 16.5h16", 1.8f)) }
    val info by lazy { icon("info.circle", s("${circle(12f, 12f, 9.25f)}M12 11v6M12 7.6v.01", 2f)) }
    val warning by lazy { icon("exclamationmark.triangle", s("M10.3 4.2L2.8 17.6c-.75 1.33.21 2.9 1.7 2.9h15c1.49 0 2.45-1.57 1.7-2.9L13.7 4.2a1.95 1.95 0 0 0-3.4 0zM12 9.5v4.5M12 17.2v.01", 1.8f)) }
    val paintbrush by lazy { icon("paintbrush", s("M14.5 4.5l5 5-7.8 7.8-5-5zM6.7 12.3c-2.3.3-3.2 2.5-3.2 4.2 0 1.3-.5 2.4-1 3 3.7.6 7.4-.3 7.7-3.7", 1.8f)) }
    val gauge by lazy { icon("gauge", s("M4.2 17.5a9 9 0 1 1 15.6 0M12 13l4-5", 1.9f), f(circle(12f, 13.4f, 1.7f))) }
    val externalDrive by lazy { icon("internaldrive", s("M3.5 13l2.4-7.2c.27-.8 1-1.3 1.85-1.3h8.5c.84 0 1.58.5 1.85 1.3L20.5 13v4.5c0 1.1-.9 2-2 2h-13c-1.1 0-2-.9-2-2zM3.5 13h17M16.5 16.3h.01", 1.8f)) }
    val airplay by lazy {
        icon("airplayaudio", s("M6.3 17.4a8 8 0 1 1 11.4 0M8.9 14.9a4.4 4.4 0 1 1 6.2 0", 1.8f), f("M12 14.2l4.5 6.3h-9z"))
    }
    val playNext by lazy { icon("text.insert", s("M4 6.5h9M4 11.5h9M4 16.5h6", 1.9f), f("M15.5 13.6v6.8c0 .5.56.8.98.52l5.1-3.4a.62.62 0 0 0 0-1.04l-5.1-3.4c-.42-.28-.98.02-.98.52z")) }
    val addQueue by lazy { icon("text.append", s("M4 6.5h16M4 11.5h16M4 16.5h9M17.5 14v6M14.5 17h6", 1.9f)) }
}

@Composable
fun Icon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
