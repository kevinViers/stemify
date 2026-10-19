package com.stemify.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.stemify.mobile.App
import com.stemify.mobile.engine.MeterHistory
import com.stemify.mobile.ui.nav.Route
import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.playback.MixPresets
import com.stemify.mobile.playback.QueueItem
import com.stemify.mobile.playback.RepeatMode
import com.stemify.mobile.playback.SourceKind
import com.stemify.mobile.playback.StemSetting
import com.stemify.mobile.playback.Stems
import com.stemify.mobile.ui.components.Artwork
import com.stemify.mobile.ui.components.Chip
import com.stemify.mobile.ui.components.Hairline
import com.stemify.mobile.ui.components.IconButton
import com.stemify.mobile.ui.components.IosSlider
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.TextButton
import com.stemify.mobile.ui.components.pressFade
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.LocalPalette
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class NpPanel { Art, Lyrics, Mixer, Queue }

/**
 * A solid colour from the album art, pushed dark (or light for the White theme) so text
 * stays readable. One flat colour: no gradients.
 */
@Composable
private fun albumColor(thumb: String?, enabled: Boolean, dark: Boolean): Color? {
    val ctx = LocalContext.current
    val c by produceState<Color?>(null, thumb, enabled, dark) {
        value = null
        if (!enabled) return@produceState
        val url = App.graph.plex.artUrl(thumb, 120) ?: return@produceState
        val res = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).allowHardware(false).build())
        val bmp: Bitmap = (res as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        val rgb = withContext(Dispatchers.Default) {
            val p = Palette.from(bmp).generate()
            (p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.dominantSwatch)?.rgb
        } ?: return@produceState
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(rgb, hsv)
        if (dark) {
            hsv[1] = min(hsv[1], 0.65f)
            hsv[2] = hsv[2].coerceIn(0.18f, 0.30f)
        } else {
            hsv[1] = min(hsv[1], 0.22f)
            hsv[2] = 0.96f
        }
        value = Color(android.graphics.Color.HSVToColor(hsv))
    }
    return c
}

@Composable
fun NowPlayingScreen(onClose: () -> Unit, dragModifier: Modifier) {
    val g = App.graph
    val pm = g.playback
    val base = Ios.colors
    val overlays = LocalOverlays.current
    val app = LocalApp.current
    val menu = rememberMediaMenu()
    val item by pm.current.collectAsState()
    val playing by pm.playWhenReady.collectAsState()
    val buffering by pm.buffering.collectAsState()
    val duration by pm.durationMs.collectAsState()
    val waveform by pm.waveform.collectAsState()
    val names by pm.stemNames.collectAsState()
    val mix by pm.mix.collectAsState()
    val master by pm.master.collectAsState()
    val source by pm.source.collectAsState()
    val device by g.eq.device.collectAsState()
    val profile by g.eq.active.collectAsState()
    val eqState by g.eq.state.collectAsState()
    val useAlbumColor by g.settings.albumColorNowPlaying.flow.collectAsState()
    val quality by g.settings.stemQuality.flow.collectAsState()
    var panel by remember { mutableStateOf(NpPanel.Art) }
    val position = rememberPlaybackPosition()
    val track = item?.track

    val tint = albumColor(track?.thumb, useAlbumColor, base.dark)
    val dark = tint?.let { it.luminance() < 0.5f } ?: base.dark
    val c = if (tint == null) base else base.copy(
        bg = tint, fill = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
        label = if (dark) Color.White else Color.Black,
        label2 = if (dark) Color.White.copy(alpha = 0.62f) else Color.Black.copy(alpha = 0.55f),
        label3 = if (dark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.2f),
        fillStrong = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.12f),
        separator = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.1f),
    )
    val env = remember(waveform, names, mix) { envelope(waveform, names, mix) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides c) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(bottom = bottom + 8.dp)) {
            // Grabber; dragging here (or on the art) pulls the sheet down.
            Box(dragModifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp, 5.dp).clip(CircleShape).background(c.label3).tappable(onClick = onClose))
            }
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Txt("Nothing Playing", Type.title3, color = c.label2) }
                return@Column
            }
            AnimatedContent(panel, Modifier.weight(1f), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "panel") { p ->
                when (p) {
                    NpPanel.Art -> ArtPanel(track.thumb, playing, dragModifier)
                    NpPanel.Lyrics -> Column {
                        MiniHeader(track.thumb, track.title, track.artist) { menu.show(track) }
                        LyricsPanel(track, position, names, mix, source, playing)
                    }
                    NpPanel.Mixer -> Column {
                        MiniHeader(track.thumb, track.title, track.artist) { menu.show(track) }
                        MixerPanel(names, mix, source, master)
                    }
                    NpPanel.Queue -> Column {
                        MiniHeader(track.thumb, track.title, track.artist) { menu.show(track) }
                        QueuePanel()
                    }
                }
            }
            Column(Modifier.padding(horizontal = 28.dp)) {
                if (panel == NpPanel.Art) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(track.title, Type.title3)
                            Txt(track.artist, Type.title3, color = c.label2, weight = FontWeight.Normal, modifier = Modifier.tappable {
                                track.artistKey?.let { k -> app.showInLibrary(com.stemify.mobile.ui.nav.Route.ArtistPage(com.stemify.mobile.plex.Artist(k, track.artist, track.artist))) }
                            })
                        }
                        Box(Modifier.size(32.dp).clip(CircleShape).background(c.fill).pressFade { menu.show(track) }, contentAlignment = Alignment.Center) {
                            Icon(Sym.ellipsis, c.label, size = 18.dp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val badge = when (source) {
                    SourceKind.Stems -> "${names.size} STEMS · ${if (g.stems.effectiveQuality(quality) == StemQuality.Lossless) "FLAC" else "OPUS ${quality.kbps}"}"
                    SourceKind.Original -> "ORIGINAL · ${track.codec?.uppercase() ?: ""}".trimEnd(' ', '·')
                    SourceKind.None -> null
                }
                ProgressVolumeBar(
                    position, duration, env, master, onSeek = pm::seekTo, onVolume = pm::setMaster,
                    played = c.label, unplayed = c.label3, centerLabel = badge,
                )
                // Transport, with 10-second skips outside the track buttons.
                Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Sym.gobackward10, { pm.skipBy(-10_000) }, tint = c.label, size = 30.dp, box = 52.dp)
                    IconButton(Sym.backward, pm::previous, tint = c.label, size = 38.dp, box = 64.dp)
                    Box(Modifier.size(80.dp).pressFade(onClick = pm::togglePlay), contentAlignment = Alignment.Center) {
                        if (buffering && playing) Spinner(size = 34.dp, color = c.label)
                        else Icon(if (playing) Sym.pause else Sym.play, c.label, size = 52.dp)
                    }
                    IconButton(Sym.forward, pm::next, tint = c.label, size = 38.dp, box = 64.dp)
                    IconButton(Sym.goforward10, { pm.skipBy(10_000) }, tint = c.label, size = 30.dp, box = 52.dp)
                }
                // Bottom row: lyrics and stems, output route, queue.
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelToggle(Sym.quote, panel == NpPanel.Lyrics) { panel = if (panel == NpPanel.Lyrics) NpPanel.Art else NpPanel.Lyrics }
                    Spacer(Modifier.width(4.dp))
                    PanelToggle(Sym.sliders, panel == NpPanel.Mixer) { panel = if (panel == NpPanel.Mixer) NpPanel.Art else NpPanel.Mixer }
                    Row(
                        Modifier.weight(1f).pressFade { onClose(); app.showInSettings(Route.Eq) },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(deviceIcon(device).first, c.label2, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        val p = profile
                        val eqLabel = when {
                            !eqState.enabled || p == null -> ""
                            p.name.equals(device.label, ignoreCase = true) -> " · EQ"
                            else -> " · ${p.name}"
                        }
                        Txt(device.label + eqLabel, Type.footnote, color = c.label2, weight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(44.dp)) // balances the two buttons on the left, so the route stays centred
                    PanelToggle(Sym.queue, panel == NpPanel.Queue) { panel = if (panel == NpPanel.Queue) NpPanel.Art else NpPanel.Queue }
                }
            }
        }
    }
}

@Composable
private fun PanelToggle(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) c.label else Color.Transparent).pressFade(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (on) c.bg else c.label2, size = 22.dp)
    }
}

/** Big art that eases back when paused, like Apple Music. */
@Composable
private fun ArtPanel(thumb: String?, playing: Boolean, dragModifier: Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else 0.82f, spring(dampingRatio = 0.62f, stiffness = 260f), label = "art")
    BoxWithConstraints(dragModifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth.value, maxHeight.value).dp
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
            Artwork(thumb, side, corner = 12.dp, placeholder = Sym.note)
        }
    }
}

@Composable
private fun MiniHeader(thumb: String?, title: String, artist: String, onMore: () -> Unit) {
    val c = Ios.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(thumb, 64.dp, corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Type.headline)
            Txt(artist, Type.subhead, color = c.label2)
        }
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.fill).pressFade(onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(Sym.ellipsis, c.label, size = 18.dp)
        }
    }
}

// ---- stem mixer ----------------------------------------------------------------------------------------

@Composable
private fun MixerPanel(names: List<String>, mix: Map<String, StemSetting>, source: SourceKind, master: Float) {
    val pm = App.graph.playback
    val c = Ios.colors
    // Live meters, every display frame, showing the block that's audible now: quick attack,
    // ~130 ms fall, timed in seconds so it looks the same at 60 and 165 Hz.
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(names) {
        val raw = FloatArray(MeterHistory.MAX_STEMS)
        val shown = FloatArray(names.size)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val fall = if (last == 0L) 0f else exp(-(now - last) / 1e9f / 0.13f)
            last = now
            val live = pm.engine.levelsNow(raw)
            for (i in shown.indices) shown[i] = max(if (live) raw.getOrElse(i) { 0f } else 0f, shown[i] * fall)
            if (levels.size != shown.size) { levels.clear(); levels.addAll(shown.toList()) }
            else for (i in shown.indices) if (levels[i] != shown[i]) levels[i] = shown[i]
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        if (source == SourceKind.Original) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.fill).padding(14.dp)) {
                Txt("No stems for this song yet, so you're hearing the original file. stemd on your server separates new music on its own; once this one's done, the app picks it up within a minute and plays it with stems next time.",
                    Type.footnote, color = c.label2, maxLines = 5)
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = MixPresets.normalized(mix)
                MixPresets.ALL.forEach { p ->
                    val target = p.build(names) ?: return@forEach
                    Chip(p.label, MixPresets.normalized(target) == current, onClick = { pm.applyMix(target) })
                }
            }
        }
        names.forEachIndexed { i, name ->
            val s = mix[name] ?: StemSetting()
            StemFader(name, s, levels.getOrElse(i) { 0f }, enabled = source == SourceKind.Stems,
                onLevel = { pm.setStemLevel(name, it) }, onMute = { pm.toggleMute(name) }, onSolo = { pm.toggleSolo(name) })
        }
        Hairline(Modifier.padding(vertical = 10.dp), c.separator)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Master", Type.headline, Modifier.weight(1f))
            Txt("${(master * 100).toInt()}%", Type.footnote.copy(fontFeatureSettings = "tnum"), color = c.label2)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.speaker, c.label2, size = 16.dp)
            IosSlider(master, pm::setMaster, Modifier.weight(1f).padding(horizontal = 8.dp), fill = c.label)
            Icon(Sym.speakerWave, c.label2, size = 18.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StemFader(name: String, s: StemSetting, level: Float, enabled: Boolean, onLevel: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit) {
    val c = Ios.colors
    val color = Color(Stems.color(name))
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.muted) c.label3 else color))
            Spacer(Modifier.width(8.dp))
            Txt(Stems.label(name), Type.headline, Modifier.weight(1f), color = if (s.muted) c.label2 else c.label)
            val pct = (com.stemify.mobile.engine.stemTaper(s.level) * 100).toInt()
            Txt("$pct%", Type.footnote.copy(fontFeatureSettings = "tnum"), color = c.label2, modifier = Modifier.padding(end = 10.dp))
            if (enabled) {
                ToggleBox("M", s.muted, c.red, onMute)
                Spacer(Modifier.width(6.dp))
                ToggleBox("S", s.solo, Color(0xFFFFCC00), onSolo)
            }
        }
        IosSlider(s.level, onLevel, fill = if (s.muted) c.label3 else color, detent = StemSetting.UNITY, enabled = enabled, thumb = 24.dp)
        // Live level under the fader.
        Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 12.dp)) {
            val w = size.width * min(1f, max(0f, (20f * kotlin.math.log10(level + 1e-5f) + 48f) / 48f))
            drawRoundRect(c.fill, Offset.Zero, size, CornerRadius(size.height / 2))
            drawRoundRect(color, Offset.Zero, Size(w, size.height), CornerRadius(size.height / 2))
        }
    }
}

@Composable
private fun ToggleBox(text: String, on: Boolean, onColor: Color, onClick: () -> Unit) {
    val c = Ios.colors
    Box(
        Modifier.size(30.dp, 26.dp).clip(RoundedCornerShape(7.dp)).background(if (on) onColor else c.fill).pressFade(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.footnote, color = if (on) Color.Black else c.label2, weight = FontWeight.Bold)
    }
}

// ---- queue ---------------------------------------------------------------------------------------------

@Composable
private fun QueuePanel() {
    val pm = App.graph.playback
    val c = Ios.colors
    val overlays = LocalOverlays.current
    val queue by pm.queue.collectAsState()
    val index by pm.index.collectAsState()
    val shuffle by pm.shuffle.collectAsState()
    val repeat by pm.repeat.collectAsState()
    val upcoming = remember(queue, index) { queue.drop(index + 1) }
    var dragUid by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Playing Next", Type.headline)
                Txt(songs(upcoming.size), Type.footnote, color = c.label2)
            }
            QueueToggle(Sym.shuffle, shuffle, pm::toggleShuffle)
            Spacer(Modifier.width(8.dp))
            QueueToggle(if (repeat == RepeatMode.One) Sym.repeatOne else Sym.repeat, repeat != RepeatMode.Off, pm::cycleRepeat)
            if (upcoming.isNotEmpty()) TextButton("Clear", { pm.clearUpNext() }, Modifier.padding(start = 6.dp), color = c.label2)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(upcoming.size, key = { upcoming[it].uid }) { i ->
                val q: QueueItem = upcoming[i]
                val dragging = dragUid == q.uid
                Row(
                    Modifier.fillMaxWidth().height(60.dp).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .background(if (dragging) c.fill else Color.Transparent)
                        .tappable(onLongClick = {
                            overlays.actions(q.track.title, listOf(
                                SheetAction("Play Next") { pm.move(index + 1 + i, index + 1) },
                                SheetAction("Remove from Queue", destructive = true) { pm.removeAt(index + 1 + i) },
                            ))
                        }) { pm.skipTo(index + 1 + i) }
                        .padding(start = 24.dp, end = 8.dp)
                        .animateItem(placementSpec = if (dragging) null else spring(stiffness = 900f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(q.track.thumb, 44.dp, corner = 5.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(q.track.title, Type.body)
                        Txt(q.track.artist, Type.subhead, color = c.label2)
                    }
                    Box(Modifier.size(44.dp).pointerInput(q.uid) {
                        detectDragGestures(
                            onDragStart = { dragUid = q.uid; dragOffset = 0f },
                            onDragEnd = { dragUid = null; dragOffset = 0f },
                            onDragCancel = { dragUid = null; dragOffset = 0f },
                        ) { ch, d ->
                            ch.consume()
                            dragOffset += d.y
                            val qi = pm.queue.value.indexOfFirst { it.uid == q.uid }
                            val first = pm.index.value + 1
                            if (dragOffset > rowPx / 2 && qi < pm.queue.value.lastIndex) {
                                pm.move(qi, qi + 1); dragOffset -= rowPx
                            } else if (dragOffset < -rowPx / 2 && qi > first) {
                                pm.move(qi, qi - 1); dragOffset += rowPx
                            }
                        }
                    }, contentAlignment = Alignment.Center) { Icon(Sym.grip, c.label3, size = 22.dp) }
                }
            }
            if (upcoming.isEmpty()) item("empty") {
                Txt("Nothing queued. Use Play Next or Add to Queue on any song.", Type.subhead, Modifier.padding(24.dp), color = c.label2, maxLines = 3)
            }
        }
    }
}

@Composable
private fun QueueToggle(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    Box(
        Modifier.size(36.dp, 30.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.label else c.fill).pressFade(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (on) c.bg else c.label2, size = 18.dp)
    }
}

// ---- mini player ----------------------------------------------------------------------------------------

@Composable
fun MiniPlayer(onOpen: () -> Unit, dragModifier: Modifier) {
    val pm = App.graph.playback
    val c = Ios.colors
    val item by pm.current.collectAsState()
    val playing by pm.playWhenReady.collectAsState()
    val buffering by pm.buffering.collectAsState()
    val duration by pm.durationMs.collectAsState()
    val position = rememberPlaybackPosition()
    val t = item?.track ?: return
    Box(dragModifier.fillMaxWidth().height(64.dp).background(c.bar).tappable(onClick = onOpen)) {
        Hairline(Modifier.align(Alignment.TopStart), c.barBorder)
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.thumb, 46.dp, corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(t.title, Type.subhead, weight = FontWeight.Medium)
                Txt(t.artist, Type.footnote, color = c.label2)
            }
            Box(Modifier.size(48.dp).pressFade(onClick = pm::togglePlay), contentAlignment = Alignment.Center) {
                if (buffering && playing) Spinner(size = 22.dp, color = c.label) else Icon(if (playing) Sym.pause else Sym.play, c.label, size = 28.dp)
            }
            IconButton(Sym.forward, pm::next, tint = c.label, size = 28.dp, box = 48.dp)
        }
        // Hairline progress along the bottom edge.
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)) {
            val f = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawRect(c.accent, Offset.Zero, Size(size.width * f, size.height))
        }
    }
}
