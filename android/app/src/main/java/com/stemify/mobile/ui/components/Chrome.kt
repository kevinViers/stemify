package com.stemify.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.stemify.mobile.App
import com.stemify.mobile.ui.nav.LocalEntry
import com.stemify.mobile.ui.nav.LocalNav
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlin.math.ceil

/** Height of the tab bar plus mini player, so lists can scroll clear of them. */
val LocalBottomInset = compositionLocalOf { 0.dp }

const val MARGIN = 20

/**
 * A navigation-controller page: large title that collapses into the bar as you scroll,
 * back button titled after the previous page, bar background only once content is under it.
 */
@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    largeTitle: Boolean = true,
    state: LazyListState = rememberLazyListState(),
    trailing: @Composable RowScope.() -> Unit = {},
    leading: (@Composable RowScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val c = Ios.colors
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val idx = nav.entries.indexOf(entry)
    val backTitle = if (idx > 0) nav.entries[idx - 1].route.title else null
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val collapsePx = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(state, largeTitle) {
        derivedStateOf { !largeTitle || state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > collapsePx }
    }
    val under by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 } }
    val pastHeader by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    val bg = if (grouped) c.groupedBg else c.bg

    Box(modifier.fillMaxSize().background(bg)) {
        // Settings-style (grouped) pages are laid out to fit the screen; they only scroll if
        // something (a big font size, say) makes them overflow.
        val overflows by remember(state) { derivedStateOf { state.canScrollForward || state.canScrollBackward } }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !grouped || overflows,
            contentPadding = PaddingValues(top = top + 44.dp, bottom = LocalBottomInset.current + 28.dp),
        ) {
            if (largeTitle) {
                item(key = "__largeTitle") {
                    Box(Modifier.fillMaxWidth().height(60.dp).padding(start = MARGIN.dp, end = MARGIN.dp, bottom = 8.dp), contentAlignment = Alignment.BottomStart) {
                        Txt(title, Type.largeTitle)
                    }
                }
            }
            content()
        }
        NavBar(
            title = title,
            showTitle = if (largeTitle) collapsed else pastHeader,
            showBar = if (largeTitle) collapsed else under,
            backTitle = backTitle,
            onBack = { nav.pop() },
            leading = leading,
            trailing = trailing,
            bg = bg,
            top = top,
        )
        overlay()
    }
}

@Composable
fun NavBar(
    title: String,
    showTitle: Boolean,
    showBar: Boolean,
    backTitle: String?,
    onBack: () -> Unit,
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit,
    bg: Color,
    top: Dp,
) {
    val c = Ios.colors
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, tween(160), label = "title")
    val barAlpha by animateFloatAsState(if (showBar) 1f else 0f, tween(160), label = "bar")
    val bar = c.bar
    Box(
        Modifier.fillMaxWidth().height(top + 44.dp).drawBehind {
            drawRect(bg)
            drawRect(bar.copy(alpha = barAlpha))
            drawLine(c.barBorder.copy(alpha = barAlpha), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        },
    ) {
        Txt(
            title, Type.headline,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 11.dp).widthIn(max = 220.dp).graphicsLayer { alpha = titleAlpha },
            align = TextAlign.Center,
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (backTitle != null) BackButton(backTitle, onBack) else leading?.invoke(this)
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
fun BackButton(title: String, onBack: () -> Unit) {
    val c = Ios.colors
    Row(Modifier.height(44.dp).pressFade(onClick = onBack).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.chevronLeft, c.accent, size = 24.dp)
        Txt(if (title.length > 14) "Back" else title, Type.body, color = c.accent)
    }
}

// ---- inset grouped lists ----------------------------------------------------------------------

/** One UITableView inset-grouped section; [rows] are drawn as one rounded card. */
fun LazyListScope.section(
    key: String,
    header: String? = null,
    footer: String? = null,
    separatorInset: Dp = 16.dp,
    rows: List<@Composable () -> Unit>,
) {
    item(key = "$key.header") {
        if (header != null) {
            Txt(header.uppercase(), Type.footnote, Modifier.padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 6.dp), color = Ios.colors.label2)
        } else {
            Spacer(Modifier.height(18.dp))
        }
    }
    items(rows.size, key = { "$key.$it" }) { i ->
        val last = i == rows.lastIndex
        val shape: Shape = when {
            rows.size == 1 -> RoundedCornerShape(12.dp)
            i == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            last -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            else -> RoundedCornerShape(0.dp)
        }
        Box(Modifier.padding(horizontal = 16.dp).clip(shape).background(Ios.colors.cell)) {
            rows[i]()
            if (!last) Hairline(Modifier.align(Alignment.BottomStart).padding(start = separatorInset))
        }
    }
    if (footer != null) {
        item(key = "$key.footer") {
            Txt(footer, Type.footnote, Modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp), color = Ios.colors.label2, maxLines = 6)
        }
    }
}

/** A table cell: optional icon, title/subtitle, grey value, then chevron / check / custom accessory. */
@Composable
fun Cell(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: (@Composable () -> Unit)? = null,
    titleColor: Color = Ios.colors.label,
    chevron: Boolean = false,
    checked: Boolean = false,
    centered: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = if (subtitle != null) 56.dp else 44.dp)
            .background(if (pressed && onClick != null) c.cellPressed else Color.Transparent)
            .then(if (onClick != null) Modifier.tappable(onLongClick = onLongClick, interaction = src, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(14.dp))
        }
        Column(if (centered) Modifier else Modifier.weight(1f)) {
            Txt(title, Type.body, color = titleColor)
            if (subtitle != null) Txt(subtitle, Type.footnote, color = c.label2, maxLines = 2)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Txt(value, Type.body, color = c.label2, modifier = Modifier.widthIn(max = 200.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (checked) {
            Spacer(Modifier.width(8.dp))
            Icon(Sym.check, c.accent, size = 18.dp)
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/** Settings.app-style coloured rounded-square icon. */
@Composable
fun SettingsIcon(vector: ImageVector, bg: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(vector, Color.White, size = 19.dp)
    }
}

/** Section title with an optional accent action, as in Apple Music ("Recently Added"). */
@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = MARGIN.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(title, Type.title2, Modifier.weight(1f))
        if (action != null) TextButton(action, onAction)
    }
}

@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSubmit: () -> Unit = {},
) {
    val c = Ios.colors
    val focus = LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(11.dp)).background(c.fill).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.search, c.label2, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Txt(placeholder, Type.body, color = c.label2)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = Type.body.copy(color = c.label), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(c.label3).tappable { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(Sym.xmark, c.fill, size = 12.dp)
            }
        }
    }
}

// ---- artwork, tiles, rows ------------------------------------------------------------------------

/** Plex artwork through the photo transcoder, sized to the device's pixels. */
@Composable
fun Artwork(
    thumb: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    circle: Boolean = false,
    placeholder: ImageVector = Sym.note,
) {
    val c = Ios.colors
    val density = LocalDensity.current.density
    // Round the request up so nearby sizes share cache entries.
    val px = (ceil(size.value * density / 120f) * 120).toInt().coerceIn(120, 1440)
    val url = remember(thumb, px) { App.graph.plex.artUrl(thumb, px) }
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    Box(
        modifier.size(size).clip(shape).background(c.placeholder).border(0.5.dp, c.separator.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, c.label3, size = size * 0.38f)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, c.label3, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Txt(title, Type.title3, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Txt(message, Type.subhead, color = c.label2, align = TextAlign.Center, maxLines = 6)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.clip(CircleShape).background(c.accent).pressFade(onClick = onAction).padding(horizontal = 20.dp, vertical = 10.dp)) {
                Txt(action, Type.headline, color = Color.White, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { Spinner(size = 26.dp) }
}

@Composable
fun ErrorRow(message: String, onRetry: () -> Unit) {
    if (!App.graph.plex.signedIn) {
        val nav = LocalNav.current
        EmptyState(Sym.server, "Not Connected", "Sign in to your Plex server to see your music here.", "Connect") {
            nav.push(com.stemify.mobile.ui.nav.Route.PlexAccount)
        }
    } else {
        EmptyState(Sym.warning, "Couldn't Load", message, "Try Again", onRetry)
    }
}
