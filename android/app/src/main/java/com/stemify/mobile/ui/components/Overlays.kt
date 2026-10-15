package com.stemify.mobile.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SheetAction(val title: String, val destructive: Boolean = false, val onClick: () -> Unit)

class AlertSpec(
    val title: String,
    val message: String? = null,
    val confirm: String = "OK",
    val cancel: String? = "Cancel",
    val destructive: Boolean = false,
    val field: String? = null,
    val placeholder: String = "",
    val onConfirm: (String) -> Unit,
)

class HudSpec(val text: String, val icon: ImageVector?, val id: Long)

/** App-wide modal UI: action sheets, alerts and the HUD. */
@Stable
class Overlays {
    var sheet by mutableStateOf<Pair<String?, List<SheetAction>>?>(null)
    var alert by mutableStateOf<AlertSpec?>(null)
    var hud by mutableStateOf<HudSpec?>(null)
    private var hudId = 0L

    fun actions(title: String?, actions: List<SheetAction>) {
        sheet = title to actions
    }

    fun prompt(title: String, message: String?, initial: String, confirm: String, placeholder: String = "", onConfirm: (String) -> Unit) {
        alert = AlertSpec(title, message, confirm, field = initial, placeholder = placeholder, onConfirm = onConfirm)
    }

    fun confirm(title: String, message: String?, confirm: String, destructive: Boolean = true, onConfirm: () -> Unit) {
        alert = AlertSpec(title, message, confirm, destructive = destructive, onConfirm = { onConfirm() })
    }

    fun hud(text: String, icon: ImageVector? = Sym.check) {
        hud = HudSpec(text, icon, ++hudId)
    }
}

val LocalOverlays = staticCompositionLocalOf { Overlays() }

@Composable
fun OverlayHost(o: Overlays) {
    o.sheet?.let { (title, actions) -> ActionSheet(title, actions) { o.sheet = null } }
    o.alert?.let { spec -> Alert(spec) { o.alert = null } }
    o.hud?.let { h -> Hud(h) { if (o.hud?.id == h.id) o.hud = null } }
}

/** UIAlertController(.actionSheet): grouped buttons over a scrim, Cancel apart. */
@Composable
private fun ActionSheet(title: String?, actions: List<SheetAction>, dismiss: () -> Unit) {
    val c = Ios.colors
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
    fun close(then: (() -> Unit)? = null) = scope.launch {
        shown.animateTo(0f, tween(180))
        dismiss()
        then?.invoke()
    }
    PredictiveBackHandler { events ->
        try {
            events.collect { shown.snapTo(1f - it.progress * 0.5f) }
            close()
        } catch (e: CancellationException) {
            scope.launch { shown.animateTo(1f) }
            throw e
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(c.scrim).tappable { close() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)
                .graphicsLayer { translationY = (1f - shown.value) * size.height * 1.1f },
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.elevated).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                if (title != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Txt(title, Type.footnote, color = c.label2, maxLines = 2, align = TextAlign.Center, weight = FontWeight.SemiBold)
                    }
                    Hairline()
                }
                actions.forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    SheetButton(a.title, if (a.destructive) c.red else c.accent, bold = false) { close(a.onClick) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.elevated)) {
                SheetButton("Cancel", c.accent, bold = true) { close() }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, color: Color, bold: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.fillMaxWidth().height(57.dp).background(if (pressed) c.cellPressed else Color.Transparent)
            .tappable(interaction = src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.body.copy(fontSize = Type.title3.fontSize), color = color, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** UIAlertController(.alert), optionally with one text field. */
@Composable
private fun Alert(spec: AlertSpec, dismiss: () -> Unit) {
    val c = Ios.colors
    val shown = remember { Animatable(0f) }
    var text by remember(spec) { mutableStateOf(spec.field ?: "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(spec) {
        shown.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f))
        if (spec.field != null) runCatching { focus.requestFocus() }
    }
    PredictiveBackHandler { events ->
        events.collect { }
        dismiss()
    }
    fun confirm() {
        dismiss()
        spec.onConfirm(text.trim())
    }
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(c.scrim).tappable { })
        Column(
            Modifier.width(280.dp).graphicsLayer {
                alpha = shown.value
                val sc = 1.15f - 0.15f * shown.value
                scaleX = sc; scaleY = sc
            }.clip(RoundedCornerShape(14.dp)).background(c.elevated),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(spec.title, Type.headline, maxLines = 3, align = TextAlign.Center)
                spec.message?.let {
                    Spacer(Modifier.height(4.dp))
                    Txt(it, Type.footnote, maxLines = 8, align = TextAlign.Center)
                }
                if (spec.field != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (c.dark) c.fill else Color.White)
                            .border(0.5.dp, c.separator, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
                    ) {
                        if (text.isEmpty()) Txt(spec.placeholder, Type.subhead, color = c.label3)
                        BasicTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            textStyle = Type.subhead.copy(color = c.label),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) confirm() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
            }
            Hairline()
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.Center) {
                spec.cancel?.let { label ->
                    AlertButton(label, c.accent, false, Modifier.weight(1f)) { dismiss() }
                    Box(Modifier.width(0.5.dp).height(44.dp).background(c.separator))
                }
                AlertButton(spec.confirm, if (spec.destructive) c.red else c.accent, true, Modifier.weight(1f),
                    enabled = spec.field == null || text.isNotBlank()) { confirm() }
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, color: Color, bold: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.height(44.dp).background(if (pressed) c.cellPressed else Color.Transparent).tappable(enabled, interaction = src, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.body, color = if (enabled) color else c.label3, weight = if (bold) FontWeight.SemiBold else null)
    }
}

/** The square "Added to Library" style HUD. */
@Composable
private fun Hud(h: HudSpec, done: () -> Unit) {
    val c = Ios.colors
    val a = remember(h.id) { Animatable(0f) }
    LaunchedEffect(h.id) {
        a.animateTo(1f, tween(150))
        delay(if (h.text.length > 40) 2800 else 1400)
        a.animateTo(0f, tween(250))
        done()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(min = 150.dp, max = 270.dp).graphicsLayer { alpha = a.value; val s = 0.9f + 0.1f * a.value; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(16.dp)).background(if (c.dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA))
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            h.icon?.let {
                Icon(it, c.label2, size = 44.dp)
                Spacer(Modifier.height(10.dp))
            }
            Txt(h.text, Type.headline, color = c.label2, maxLines = 4, align = TextAlign.Center)
        }
    }
}
