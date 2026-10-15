package com.stemify.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stemify.mobile.App
import com.stemify.mobile.engine.EQ_BANDS
import com.stemify.mobile.engine.EQ_LABELS
import com.stemify.mobile.engine.EQ_MAX_DB
import com.stemify.mobile.engine.EqMath
import com.stemify.mobile.eq.DeviceKind
import com.stemify.mobile.eq.EQ_PRESETS
import com.stemify.mobile.eq.EqProfile
import com.stemify.mobile.eq.OutputDevice
import com.stemify.mobile.ui.components.Cell
import com.stemify.mobile.ui.components.IosSwitch
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.SegmentedControl
import com.stemify.mobile.ui.components.SettingsIcon
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.VerticalBandSlider
import com.stemify.mobile.ui.components.section
import com.stemify.mobile.ui.nav.LocalNav
import com.stemify.mobile.ui.nav.Route
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlin.math.abs

fun deviceIcon(d: OutputDevice): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when (d.kind) {
    DeviceKind.Speaker -> Sym.phone to androidx.compose.ui.graphics.Color(0xFF8E8E93)
    DeviceKind.Bluetooth -> Sym.headphones to androidx.compose.ui.graphics.Color(0xFF007AFF)
    DeviceKind.Wired -> Sym.headphones to androidx.compose.ui.graphics.Color(0xFF34C759)
    DeviceKind.Usb -> Sym.usb to androidx.compose.ui.graphics.Color(0xFFFF9500)
    DeviceKind.Hdmi -> Sym.airplay to androidx.compose.ui.graphics.Color(0xFF5856D6)
    DeviceKind.Other -> Sym.speakerWave to androidx.compose.ui.graphics.Color(0xFF8E8E93)
}

private fun db(v: Float) = (if (v > 0) "+" else if (v < 0) "−" else "") + "%.1f".format(abs(v))

@Composable
fun EqScreen() {
    val eq = App.graph.eq
    val c = Ios.colors
    val overlays = LocalOverlays.current
    val state by eq.state.collectAsState()
    val device by eq.device.collectAsState()
    val active by eq.active.collectAsState()
    val linked = state.profiles.firstOrNull { device in it.devices }
    val profile = active
    val nav = LocalNav.current
    val chain = remember(profile) { profile?.chain.orEmpty() }
    val preamp = remember(profile, state.antiClip) {
        if (profile == null) 0f else profile.preamp + if (state.antiClip) EqMath.autoPreampDb(chain, profile.preamp) else 0f
    }

    fun profileMenu(p: EqProfile) {
        overlays.actions(p.name, buildList {
            if (device !in p.devices) add(SheetAction("Use for ${device.label}") { eq.link(p.id, device, true) })
            else add(SheetAction("Unlink from ${device.label}") { eq.link(p.id, device, false) })
            if (state.defaultId != p.id) add(SheetAction("Make Default") { eq.setDefault(p.id) })
            add(SheetAction("Rename…") { overlays.prompt("Rename Profile", null, p.name, "Save") { eq.rename(p.id, it) } })
            add(SheetAction("Duplicate") { eq.create("${p.name} Copy", p) })
            if (state.profiles.size > 1) add(SheetAction("Delete Profile", destructive = true) {
                overlays.confirm("Delete “${p.name}”?", null, "Delete") { eq.delete(p.id) }
            })
        })
    }

    fun profilesSheet() {
        overlays.actions("Profiles", buildList {
            state.profiles.forEach { p ->
                val links = p.devices.joinToString { it.label }
                add(SheetAction((if (active?.id == p.id) "✓ " else "") + p.name + if (links.isNotEmpty()) " ($links)" else "") { eq.select(p.id) })
            }
            add(SheetAction("New Profile…") {
                overlays.prompt("New EQ Profile", "Starts from the current curve.", "", "Create", "e.g. Sony WH-1000XM5") { name ->
                    val p = eq.create(name, profile)
                    eq.link(p.id, device, true)
                }
            })
            profile?.let { p -> add(SheetAction("Manage “${p.name}”…") { profileMenu(p) }) }
        })
    }

    // One screen, no scrolling: profile management and presets live in sheets.
    Page("Equalizer", grouped = true, largeTitle = false) {
        section("enabled", rows = listOf(
            { Cell("Equalizer", icon = { SettingsIcon(Sym.sliders, c.accent) }, trailing = { IosSwitch(state.enabled, eq::setEnabled) }) },
            { Cell("Anti-Clipping Preamp", trailing = { IosSwitch(state.antiClip, eq::setAntiClip) }) },
        ))

        val (icon, tint) = deviceIcon(device)
        section("device", header = "Output", separatorInset = 60.dp, rows = listOf(
            { Cell(device.label, icon = { SettingsIcon(icon, tint) }, value = linked?.name ?: "Default") {
                overlays.actions("Profile for ${device.label}", listOf(SheetAction("Use Default") {
                    state.profiles.filter { device in it.devices }.forEach { eq.link(it.id, device, false) }
                }) + state.profiles.map { p -> SheetAction(p.name) { eq.link(p.id, device, true) } })
            } },
            {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedControl(listOf("Automatic", "Manual"), if (state.automatic) 0 else 1, { eq.setAutomatic(it == 0) })
                }
            },
        ))

        if (profile != null) {
            section("editor", header = "${profile.name}${if (state.automatic) "" else " (manual)"}", rows = listOf {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    EqCurve(chain, preamp, state.enabled, Modifier.fillMaxWidth().height(64.dp), range = EQ_MAX_DB + 3f, bandMarkers = true)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        profile.gains.forEachIndexed { i, g ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp)) {
                                Txt(db(g), Type.caption2.copy(fontFeatureSettings = "tnum"), color = if (g == 0f) c.label2 else c.accent, align = TextAlign.Center)
                                Spacer(Modifier.height(2.dp))
                                VerticalBandSlider(g / EQ_MAX_DB, { eq.setGain(profile.id, i, it * EQ_MAX_DB) }, Modifier.height(124.dp))
                                Spacer(Modifier.height(2.dp))
                                Txt(EQ_LABELS[i], Type.caption2, color = c.label2, align = TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Preamp", Type.footnote, Modifier.weight(1f), color = c.label2)
                        Txt(if (preamp != 0f) "${db(preamp)} dB" else "off", Type.footnote.copy(fontFeatureSettings = "tnum"), color = c.label2)
                    }
                }
            })
        }

        section("profiles", rows = listOf(
            { Cell("Profile", value = profile?.name ?: "—", chevron = true, onLongClick = { profile?.let { profileMenu(it) } }) { profilesSheet() } },
            { Cell("Parametric Filters", value = profile?.filters?.let { f -> if (f.isEmpty()) "None" else "${f.count { it.on }} on" } ?: "—", chevron = true) {
                profile?.let { nav.push(Route.EqFilters(it.id)) }
            } },
            { Cell("Presets", value = "Flat, Bass…", chevron = true) {
                val p = profile ?: return@Cell
                overlays.actions("Load Preset", EQ_PRESETS.map { (name, gains) -> SheetAction(name) { eq.setGains(p.id, gains) } })
            } },
        ))
    }
}
