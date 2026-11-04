package com.stemify.mobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.connect.Connect
import com.stemify.mobile.connect.NearbyDevice
import com.stemify.mobile.connect.Transport
import com.stemify.mobile.playback.Controls
import com.stemify.mobile.ui.components.AlertSpec
import com.stemify.mobile.ui.components.Cell
import com.stemify.mobile.ui.components.Hairline
import com.stemify.mobile.ui.components.IosSwitch
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.SettingsIcon
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.section
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.launch

/** The player the screens drive: this phone's, or the nearby device's being controlled. */
@Composable
fun rememberControls(): Controls {
    val remote by App.graph.connect.remote.collectAsState()
    return remote ?: App.graph.playback
}

fun nearbyIcon(d: NearbyDevice): ImageVector = if (d.app == "android") Sym.phone else Sym.desktop

/** "Play On": this phone and every Stemify nearby (same Wi-Fi, or paired over Bluetooth). */
@Composable
fun DevicesSheet(dismiss: () -> Unit) {
    val g = App.graph
    val cn = g.connect
    val c = Ios.colors
    val devices by cn.devices.collectAsState()
    val target by cn.target.collectAsState()
    val state by cn.state.collectAsState()
    val btScanning by cn.btScanning.collectAsState()
    val local by g.playback.current.collectAsState()
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        cn.scan(true)
        onDispose { cn.scan(false) }
    }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 500f)) }
    fun close(then: (() -> Unit)? = null) {
        scope.launch {
            shown.animateTo(0f, tween(180))
            dismiss()
            then?.invoke()
        }
    }
    BackHandler { close() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(c.scrim).tappable { close() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
                .graphicsLayer { translationY = (1f - shown.value) * size.height * 1.1f }
                .clip(RoundedCornerShape(14.dp)).background(c.elevated).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Txt("Play On", Type.headline, Modifier.align(Alignment.Center))
                Txt("Done", Type.body, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).tappable { close() },
                    color = c.accent, weight = FontWeight.SemiBold)
            }
            Hairline()
            DeviceRow(Sym.phone, "This Phone", cn.identity.name, checked = state == Connect.State.Idle) { cn.leave() }
            devices.forEach { d ->
                val cur = target?.id == d.id
                val detail = when {
                    cur && state == Connect.State.Connecting -> "Connecting…"
                    cur && state == Connect.State.Waiting -> "Allow this phone on ${d.name} to finish"
                    else -> "${d.appLabel} · ${if (d.transport == Transport.Bt) "Bluetooth" else "Wi-Fi"}"
                }
                DeviceRow(
                    nearbyIcon(d), d.name, detail,
                    checked = cur && state == Connect.State.Connected,
                    busy = cur && (state == Connect.State.Connecting || state == Connect.State.Waiting),
                ) { if (!cur) cn.connectTo(d) }
            }
            val playing = local
            if (state == Connect.State.Connected && playing != null) {
                DeviceRow(Sym.airplay, "Move “${playing.track.title}” Here", "Plays this phone's queue on ${target?.name ?: "it"}, from where it is now",
                    accent = true) { cn.transfer(); close() }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (btScanning || devices.isEmpty()) {
                    Spinner(size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Txt(
                    when {
                        devices.isEmpty() -> "Looking for Stemify on your Wi-Fi and on paired Bluetooth devices. Open Stemify on the other device; it needs “Allow Control from Nearby Devices” on (it is by default)."
                        btScanning -> "Looking for more devices…"
                        else -> "Everything here plays on the device you pick: the mixer, queue, EQ and every Play button."
                    },
                    Type.footnote, color = c.label2, maxLines = 5,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean = false, busy: Boolean = false,
                      accent: Boolean = false, onClick: () -> Unit) {
    val c = Ios.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lit = checked || accent
    Column(Modifier.fillMaxWidth().background(if (pressed) c.cellPressed else Color.Transparent).tappable(interaction = src, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (lit) c.accent else c.fill), contentAlignment = Alignment.Center) {
                Icon(icon, if (lit) Color.White else c.label2, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, Type.body, color = if (lit) c.accent else c.label, weight = if (checked) FontWeight.SemiBold else null)
                Txt(subtitle, Type.footnote, color = c.label2, maxLines = 2)
            }
            when {
                busy -> Spinner(size = 18.dp)
                checked -> Icon(Sym.check, c.accent, size = 18.dp)
            }
        }
        Hairline(Modifier.padding(start = 64.dp))
    }
}

/** Asks about each Wi-Fi device that wants to control this phone (Bluetooth ones are trusted). */
@Composable
fun ConnectApprovals() {
    val receiver = App.graph.connect.receiver
    val overlays = LocalOverlays.current
    val pending by receiver.pending.collectAsState()
    val who = pending.firstOrNull()
    LaunchedEffect(who?.id) {
        if (who == null) {
            if (overlays.alert?.title?.startsWith("Allow “") == true) overlays.alert = null
            return@LaunchedEffect
        }
        overlays.alert = AlertSpec(
            title = "Allow “${who.name}” to control Stemify?",
            message = "It's on your Wi-Fi and wants to play, pause, mix and queue music on this phone. You won't be asked again.",
            confirm = "Allow",
            cancel = "Don't Allow",
            onCancel = { receiver.deny(who.id) },
            onConfirm = { receiver.approve(who.id) },
        )
    }
}

@Composable
fun NearbyScreen() {
    val g = App.graph
    val cn = g.connect
    val s = g.settings
    val overlays = LocalOverlays.current
    val enabled by s.connectEnabled.flow.collectAsState()
    val bt by s.connectBluetooth.flow.collectAsState()
    val target by cn.target.collectAsState()
    val state by cn.state.collectAsState()
    val controllers by cn.receiver.controllers.collectAsState()
    val allowed by cn.receiver.allowed.collectAsState()
    Page("Nearby Devices", grouped = true) {
        section("play", header = "Control Another Device", separatorInset = 60.dp,
            footer = "Phones and computers running Stemify on the same Wi-Fi, or paired with this phone over Bluetooth. While one is picked, every screen here plays, mixes and queues music on it.",
            rows = listOf {
                Cell("Play On", icon = { SettingsIcon(Sym.airplay, Color(0xFF007AFF)) }, chevron = true,
                    value = when (state) {
                        Connect.State.Connected -> target?.name ?: ""
                        Connect.State.Idle -> "This Phone"
                        else -> "Connecting…"
                    }) { overlays.showDevices() }
            })
        section("this", header = "This Phone",
            footer = "Other devices find it as “${cn.identity.name}”. The first time a device on your Wi-Fi tries, you're asked here; Bluetooth devices are trusted once paired, and need the Nearby devices permission.",
            rows = buildList<@Composable () -> Unit> {
                add({ Cell("Allow Control from Nearby Devices", trailing = { IosSwitch(enabled, { s.connectEnabled.value = it }) }) })
                if (enabled) add({
                    Cell("Over Bluetooth", trailing = {
                        IosSwitch(bt, { on ->
                            s.connectBluetooth.value = on
                            if (on && !cn.bluetooth.permitted()) cn.askBluetoothPermission?.invoke()
                        })
                    })
                })
            })
        if (controllers.isNotEmpty()) section("now", header = "Controlling This Phone Now", rows = controllers.map { ctl ->
            { Cell(ctl.peer.name, value = if (ctl.transport == Transport.Bt) "Bluetooth" else "Wi-Fi") }
        })
        if (allowed.isNotEmpty()) section("allowed", header = "Allowed on Wi-Fi", footer = "Remove a device to be asked again next time it connects.",
            rows = allowed.entries.sortedBy { it.value.lowercase() }.map { (id, name) ->
                {
                    Cell(name) {
                        overlays.confirm("Remove $name?", "It will have to ask again to control this phone.", "Remove") { cn.receiver.forget(id) }
                    }
                }
            })
    }
}
