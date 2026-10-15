package com.stemify.mobile.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.stemify.mobile.R
import com.stemify.mobile.core.Accent
import com.stemify.mobile.core.ThemeMode
import kotlin.math.exp

/** iOS semantic colours, flattened to solid values for each scheme. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val groupedBg: Color,
    val cell: Color,
    val cellPressed: Color,
    val elevated: Color,
    val fill: Color,
    val fillStrong: Color,
    val label: Color,
    val label2: Color,
    val label3: Color,
    val separator: Color,
    val bar: Color,
    val barBorder: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    val switchOff: Color,
    val scrim: Color,
    val placeholder: Color,
    val thumbBorder: Color,
    val tabInactive: Color,
)

fun palette(mode: ThemeMode, accent: Accent): Palette = when (mode) {
    ThemeMode.Black -> Palette(
        dark = true, bg = Color(0xFF000000), groupedBg = Color(0xFF000000), cell = Color(0xFF1C1C1E),
        cellPressed = Color(0xFF2C2C2E), elevated = Color(0xFF1C1C1E), fill = Color(0xFF1C1C1E),
        fillStrong = Color(0xFF3A3A3C), label = Color.White, label2 = Color(0xFF8D8D93), label3 = Color(0xFF48484A),
        separator = Color(0xFF2C2C2E), bar = Color(0xFF000000), barBorder = Color(0xFF262628),
        accent = Color(accent.dark), red = Color(0xFFFF453A), green = Color(0xFF30D158), switchOff = Color(0xFF39393D),
        scrim = Color(0x99000000), placeholder = Color(0xFF1C1C1E), thumbBorder = Color(0x00000000), tabInactive = Color(0xFF757575),
    )
    ThemeMode.Dark -> Palette(
        dark = true, bg = Color(0xFF1C1C1E), groupedBg = Color(0xFF1C1C1E), cell = Color(0xFF2C2C2E),
        cellPressed = Color(0xFF3A3A3C), elevated = Color(0xFF2C2C2E), fill = Color(0xFF2C2C2E),
        fillStrong = Color(0xFF48484A), label = Color.White, label2 = Color(0xFF98989D), label3 = Color(0xFF5A5A5E),
        separator = Color(0xFF3D3D40), bar = Color(0xFF1C1C1E), barBorder = Color(0xFF38383A),
        accent = Color(accent.dark), red = Color(0xFFFF453A), green = Color(0xFF30D158), switchOff = Color(0xFF48484A),
        scrim = Color(0x99000000), placeholder = Color(0xFF3A3A3C), thumbBorder = Color(0x00000000), tabInactive = Color(0xFF808084),
    )
    ThemeMode.White -> Palette(
        dark = false, bg = Color(0xFFFFFFFF), groupedBg = Color(0xFFF2F2F7), cell = Color(0xFFFFFFFF),
        cellPressed = Color(0xFFD1D1D6), elevated = Color(0xFFFFFFFF), fill = Color(0xFFEFEFF0),
        fillStrong = Color(0xFFE3E3E8), label = Color.Black, label2 = Color(0xFF8A8A8E), label3 = Color(0xFFC4C4C7),
        separator = Color(0xFFD8D8DC), bar = Color(0xFFF9F9F9), barBorder = Color(0xFFD1D1D6),
        accent = Color(accent.light), red = Color(0xFFFF3B30), green = Color(0xFF34C759), switchOff = Color(0xFFE9E9EA),
        scrim = Color(0x55000000), placeholder = Color(0xFFE5E5EA), thumbBorder = Color(0xFFD1D1D6), tabInactive = Color(0xFF999999),
    )
}

@OptIn(ExperimentalTextApi::class)
private fun inter(opsz: Float) = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(
            R.font.inter,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz)),
        )
    },
)

/** Inter stands in for SF Pro: the text cut below 20 sp, the display cut above. */
val InterText = inter(14f)
val InterDisplay = inter(32f)

/** Inter's recommended tracking for a size, like SF's size-specific tracking. */
private fun tracking(size: Float): TextUnit = (size * (-0.0223f + 0.185f * exp(-0.1745f * size))).sp

private fun style(size: Float, line: Float, weight: Int) = TextStyle(
    fontFamily = if (size >= 20f) InterDisplay else InterText,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking(size),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** The iOS text styles, in points-as-sp (a OnePlus 15 is about as wide as an iPhone Pro Max). */
object Type {
    val largeTitle = style(34f, 41f, 700)
    val title2 = style(22f, 28f, 700)
    val title3 = style(20f, 25f, 600)
    val headline = style(17f, 22f, 600)
    val body = style(17f, 22f, 400)
    val subhead = style(15f, 20f, 400)
    val footnote = style(13f, 18f, 400)
    val caption1 = style(12f, 16f, 400)
    val caption2 = style(11f, 13f, 400)
}

val LocalPalette = staticCompositionLocalOf { palette(ThemeMode.Black, Accent.Music) }

object Ios {
    val colors: Palette @Composable get() = LocalPalette.current
}

@Composable
fun IosTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val p = remember(mode, accent) { palette(mode, accent) }
    CompositionLocalProvider(LocalPalette provides p, content = content)
}

/** BasicText with iOS defaults: label colour, single line, tail ellipsis. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ios.colors.label,
    maxLines: Int = 1,
    align: TextAlign? = null,
    weight: FontWeight? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified, fontWeight = weight ?: style.fontWeight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
