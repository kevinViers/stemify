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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.draw.alpha
import com.stemify.mobile.ui.components.trackSwipe
import com.stemify.mobile.ui.components.SwipeToDelete
import com.stemify.mobile.ui.components.SwipeState
import com.stemify.mobile.ui.components.Chip
import com.stemify.mobile.ui.components.Hairline
import com.stemify.mobile.ui.components.IconButton
import com.stemify.mobile.ui.components.IosSlider
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.SheetAction
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.TextButton
import com.stemify.mobile.ui.components.VerticalFader
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
 * - [Sheet]: the card that slides up over the library on a phone.
 * - [Docked]: always on screen beside the stems or library (an unfolded phone). Its mixer and
 *   queue live in the other pane, so the transport has shuffle and repeat instead of the skips.
 * (Tabletop has its own screen, [TabletopPlayer].)
 */
enum class NpLayout { Sheet, Docked }

/** Now Playing's colours: the theme's, or a flat colour from the album art when that's on. */
@Composable
private fun nowPlayingPalette(thumb: String?): com.stemify.mobile.ui.theme.Palette {
    val base = Ios.colors
    val useAlbumColor by App.graph.settings.albumColorNowPlaying.flow.collectAsState()
    val tint = albumColor(thumb, useAlbumColor, base.dark) ?: return base
    val dark = tint.luminance() < 0.5f
    return base.copy(
        bg = tint, fill = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
        label = if (dark) Color.White else Color.Black,
        label2 = if (dark) Color.White.copy(alpha = 0.62f) else Color.Black.copy(alpha = 0.55f),
        label3 = if (dark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.2f),
        fillStrong = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.12f),
        separator = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.1f),
    )
}

/** What's playing, in the seek bar's middle: "6 STEMS · OPUS 256", "ORIGINAL · FLAC". */
@Composable
private fun sourceBadge(track: com.stemify.mobile.plex.Track, source: SourceKind, stems: Int): String? {
    val g = App.graph
    val remote by g.connect.remote.collectAsState()
    val remoteFormat = remote?.let { it.format.collectAsState().value }
    val quality by g.settings.stemQuality.flow.collectAsState()
    return when (source) {
        SourceKind.Stems -> "$stems STEMS · ${remoteFormat?.ifEmpty { null } ?: if (g.stems.effectiveQuality(quality) == StemQuality.Lossless) "FLAC" else "OPUS ${quality.kbps}"}"
        SourceKind.Original -> "ORIGINAL · ${track.codec?.uppercase() ?: ""}".trimEnd(' ', '·')
        SourceKind.None -> null
    }
}

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

/** [mixerOn] / [onMixer]: docked, whether the stems pane is showing, and its toggle. */
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    dragModifier: Modifier,
    layout: NpLayout = NpLayout.Sheet,
    mixerOn: Boolean = false,
    onMixer: () -> Unit = {},
) {
    val docked = layout != NpLayout.Sheet
    val g = App.graph
    val pm = rememberControls()
    val remote by g.connect.remote.collectAsState()
    val connState by g.connect.state.collectAsState()
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
    val shuffle by pm.shuffle.collectAsState()
    val repeat by pm.repeat.collectAsState()
    var panel by remember { mutableStateOf(NpPanel.Art) }
    val position = rememberPlaybackPosition()
    val track = item?.track

    val c = nowPlayingPalette(track?.thumb)
    // Android back: lyrics, mixer or queue go back to the art before anything else closes.
    androidx.activity.compose.BackHandler(enabled = panel != NpPanel.Art) { panel = NpPanel.Art }
    val env = remember(waveform, names, mix) { envelope(waveform, names, mix) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = if (docked) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp else 0.dp

    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides c) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(bottom = bottom + 8.dp)) {
            // Grabber; dragging here (or on the art) pulls the sheet down.
            if (docked) Spacer(Modifier.height(top))
            else Box(dragModifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp, 5.dp).clip(CircleShape).background(c.label3).tappable(onClick = onClose))
            }
            if (track == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Txt("Nothing Playing", Type.title3, color = c.label2) }
                return@Column
            }
            AnimatedContent(panel, Modifier.weight(1f), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "panel") { p ->
                when (p) {
                    NpPanel.Art -> ArtPanel(track.thumb, playing, dragModifier, if (layout == NpLayout.Docked) 18.dp else 28.dp)
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
            Column(Modifier.padding(horizontal = if (layout == NpLayout.Docked) 18.dp else 28.dp)) {
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
                val badge = sourceBadge(track, source, names.size)
                ProgressVolumeBar(
                    position, duration, env, master, onSeek = pm::seekTo, onVolume = pm::setMaster,
                    played = c.label, unplayed = c.label3, centerLabel = badge,
                )
                // Transport, with 10-second skips outside the track buttons (docked: shuffle and repeat).
                if (layout == NpLayout.Docked) Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ModeButton(Sym.shuffle, shuffle, pm::toggleShuffle)
                    IconButton(Sym.backward, pm::previous, tint = c.label, size = 36.dp, box = 52.dp)
                    Box(Modifier.size(66.dp).pressFade(onClick = pm::togglePlay), contentAlignment = Alignment.Center) {
                        if (buffering && playing) Spinner(size = 32.dp, color = c.label)
                        else Icon(if (playing) Sym.pause else Sym.play, c.label, size = 50.dp)
                    }
                    IconButton(Sym.forward, pm::next, tint = c.label, size = 36.dp, box = 52.dp)
                    ModeButton(if (repeat == RepeatMode.One) Sym.repeatOne else Sym.repeat, repeat != RepeatMode.Off, pm::cycleRepeat)
                } else Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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
                // Docked: the stems pane's toggle, output route, Play On, lyrics.
                Row(Modifier.fillMaxWidth().height(if (layout == NpLayout.Docked) 56.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (layout == NpLayout.Docked) PanelToggle(Sym.sliders, mixerOn, big = true, onClick = onMixer)
                    else {
                        PanelToggle(Sym.quote, panel == NpPanel.Lyrics) { panel = if (panel == NpPanel.Lyrics) NpPanel.Art else NpPanel.Lyrics }
                        Spacer(Modifier.width(4.dp))
                        PanelToggle(Sym.sliders, panel == NpPanel.Mixer) { panel = if (panel == NpPanel.Mixer) NpPanel.Art else NpPanel.Mixer }
                    }
                    val rp = remote
                    val remoteEq = rp != null && "eq" in rp.caps
                    Row(
                        Modifier.weight(1f).pressFade { if (rp != null && !remoteEq) overlays.showDevices() else { onClose(); app.showInSettings(Route.Eq) } },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (rp != null) Sym.airplay else deviceIcon(device).first, if (rp != null) c.accent else c.label2, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        val p = profile
                        val eqLabel = when {
                            !eqState.enabled || p == null -> ""
                            p.name.equals(device.label, ignoreCase = true) -> " · EQ"
                            else -> " · ${p.name}"
                        }
                        val label = when {
                            rp == null -> device.label + eqLabel
                            remoteEq -> "${rp.device.name} · ${device.label}$eqLabel"
                            else -> rp.device.name
                        }
                        Txt(label, Type.footnote, color = if (rp != null) c.accent else c.label2, weight = FontWeight.Medium)
                    }
                    // Play On (Stemify Connect); with the queue, it balances the two buttons on the left.
                    PanelToggle(Sym.airplay, rp != null || connState != com.stemify.mobile.connect.Connect.State.Idle) { overlays.showDevices() }
                    Spacer(Modifier.width(4.dp))
                    if (layout == NpLayout.Docked) PanelToggle(Sym.mic, panel == NpPanel.Lyrics) { panel = if (panel == NpPanel.Lyrics) NpPanel.Art else NpPanel.Lyrics }
                    else PanelToggle(Sym.queue, panel == NpPanel.Queue) { panel = if (panel == NpPanel.Queue) NpPanel.Art else NpPanel.Queue }
                }
            }
        }
    }
}

@Composable
private fun PanelToggle(icon: ImageVector, on: Boolean, big: Boolean = false, onClick: () -> Unit) {
    val c = Ios.colors
    Box(
        Modifier.size(if (big) 52.dp else 40.dp).clip(RoundedCornerShape(if (big) 13.dp else 10.dp))
            .background(if (on) c.label else Color.Transparent).pressFade(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (on) c.bg else c.label2, size = if (big) 26.dp else 22.dp)
    }
}

/** Shuffle or repeat in the transport: bright with a dot under it when on, like iOS. */
@Composable
private fun ModeButton(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    Box(Modifier.size(40.dp).pressFade(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, if (on) c.label else c.label2, size = 24.dp)
        if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).clip(CircleShape).background(c.label))
    }
}

/** Big art that eases back when paused, like Apple Music. */
@Composable
private fun ArtPanel(thumb: String?, playing: Boolean, dragModifier: Modifier, pad: Dp = 28.dp) {
    val scale by animateFloatAsState(if (playing) 1f else 0.82f, spring(dampingRatio = 0.62f, stiffness = 260f), label = "art")
    BoxWithConstraints(dragModifier.fillMaxSize().padding(horizontal = pad, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth.value, maxHeight.value).dp
        val pm = rememberControls()
        Box(Modifier.trackSwipe(pm::next, pm::previous).graphicsLayer { scaleX = scale; scaleY = scale }) {
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
    val pm = rememberControls()
    val c = Ios.colors
    val levels = rememberStemLevels(names)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        if (source == SourceKind.Original) NoStemsNote()
        else PresetChips(names, mix, Modifier.padding(vertical = 8.dp))
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
private fun NoStemsNote() {
    val c = Ios.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.fill).padding(14.dp)) {
        Txt("No stems for this song yet, so you're hearing the original file. stemd on your server separates new music on its own; once this one's done, the app picks it up within a minute and plays it with stems next time.",
            Type.footnote, color = c.label2, maxLines = 5)
    }
}

@Composable
private fun PresetChips(names: List<String>, mix: Map<String, StemSetting>, modifier: Modifier = Modifier, large: Boolean = false) {
    val pm = rememberControls()
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = MixPresets.normalized(mix)
        MixPresets.ALL.forEach { p ->
            val target = p.build(names) ?: return@forEach
            Chip(p.label, MixPresets.normalized(target) == current, onClick = { pm.applyMix(target) }, large = large)
        }
    }
}

/**
 * Live meters, every display frame, showing the block that's audible now: quick attack,
 * ~130 ms fall, timed in seconds so it looks the same at 60 and 165 Hz.
 */
@Composable
private fun rememberStemLevels(names: List<String>): List<Float> {
    val pm = rememberControls()
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(names, pm) {
        val raw = FloatArray(MeterHistory.MAX_STEMS)
        val shown = FloatArray(names.size)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val fall = if (last == 0L) 0f else exp(-(now - last) / 1e9f / 0.13f)
            last = now
            val live = pm.levelsNow(raw)
            for (i in shown.indices) shown[i] = max(if (live) raw.getOrElse(i) { 0f } else 0f, shown[i] * fall)
            if (levels.size != shown.size) { levels.clear(); levels.addAll(shown.toList()) }
            else for (i in shown.indices) if (levels[i] != shown[i]) levels[i] = shown[i]
        }
    }
    return levels
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
private fun ToggleBox(text: String, on: Boolean, onColor: Color, onClick: () -> Unit, big: Boolean = false) {
    val c = Ios.colors
    Box(
        Modifier.size(if (big) 32.dp else 30.dp, if (big) 30.dp else 26.dp).clip(RoundedCornerShape(7.dp)).background(if (on) onColor else c.fill).pressFade(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, Type.footnote, color = if (on) Color.Black else c.label2, weight = FontWeight.Bold)
    }
}

// ---- queue ---------------------------------------------------------------------------------------------

/**
 * The queue as Apple Music shows it: History (what's played) above the song that's playing, then
 * Playing Next. On a phone it opens scrolled to the current song, so History is a scroll up.
 * [leading]: rows above all of it in the same list (the stems on an unfolded phone), in which case
 * History starts folded away behind its header instead. Rows swipe left to come off the queue.
 */
@Composable
private fun QueuePanel(bottomPad: Dp = 0.dp, leading: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null) {
    val pm = rememberControls()
    val c = Ios.colors
    val overlays = LocalOverlays.current
    val queue by pm.queue.collectAsState()
    val index by pm.index.collectAsState()
    val shuffle by pm.shuffle.collectAsState()
    val repeat by pm.repeat.collectAsState()
    val history = remember(queue, index) { queue.take(index.coerceAtLeast(0)) }
    val current = queue.getOrNull(index)
    val upcoming = remember(queue, index) { queue.drop(index + 1) }
    var showHistory by remember { mutableStateOf(leading == null) }
    var dragUid by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    // Phone: start at the current song (History header + its rows sit above it).
    val list = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = if (leading == null && history.isNotEmpty()) 1 + history.size else 0,
    )
    fun at(uid: Long) = pm.queue.value.indexOfFirst { it.uid == uid }
    fun remove(uid: Long) { at(uid).takeIf { it >= 0 }?.let(pm::removeAt) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPad)) {
        leading?.invoke(this)
        if (history.isNotEmpty()) {
            item("history") {
                Row(
                    Modifier.fillMaxWidth().then(if (leading != null) Modifier.tappable { showHistory = !showHistory } else Modifier)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Txt("History", Type.headline)
                        Txt(songs(history.size), Type.footnote, color = c.label2)
                    }
                    if (leading != null) {
                        Icon(Sym.chevronRight, c.label2, size = 16.dp, modifier = Modifier.graphicsLayer { rotationZ = if (showHistory) 90f else 0f })
                    }
                }
            }
            if (showHistory) items(history.size, key = { "h" + history[it].uid }) { i ->
                val q = history[i]
                SwipeToDelete(remember(q.uid) { SwipeState() }, onDelete = { remove(q.uid) }, Modifier.animateItem(), label = "Remove", rowColor = c.bg) {
                    QueueRow(q, Modifier.alpha(0.75f).tappable(onLongClick = {
                        overlays.actions(q.track.title, listOf(
                            SheetAction("Play Next") { at(q.uid).takeIf { it >= 0 }?.let { pm.move(it, pm.index.value) } },
                            SheetAction("Remove from Queue", destructive = true) { remove(q.uid) },
                        ))
                    }) { at(q.uid).takeIf { it >= 0 }?.let { pm.skipTo(it) } })
                }
            }
        }
        if (current != null && (history.isNotEmpty() || leading == null)) item("now") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Txt("Now Playing", Type.footnote, Modifier.padding(horizontal = 24.dp), color = c.label2, weight = FontWeight.SemiBold)
                QueueRow(current, Modifier, playing = true)
            }
        }
        stickyHeader("next") {
            Row(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Playing Next", Type.headline)
                    Txt(songs(upcoming.size), Type.footnote, color = c.label2)
                }
                QueueToggle(Sym.shuffle, shuffle, pm::toggleShuffle)
                Spacer(Modifier.width(8.dp))
                QueueToggle(if (repeat == RepeatMode.One) Sym.repeatOne else Sym.repeat, repeat != RepeatMode.Off, pm::cycleRepeat)
                if (upcoming.isNotEmpty()) TextButton("Clear", { pm.clearUpNext() }, Modifier.padding(start = 6.dp), color = c.label2)
            }
        }
        items(upcoming.size, key = { upcoming[it].uid }) { i ->
            val q: QueueItem = upcoming[i]
            val dragging = dragUid == q.uid
            SwipeToDelete(
                remember(q.uid) { SwipeState() }, onDelete = { remove(q.uid) },
                Modifier.zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .animateItem(placementSpec = if (dragging) null else spring(stiffness = 900f)),
                enabled = !dragging, label = "Remove", rowColor = if (dragging) c.fill else c.bg,
            ) {
                QueueRow(q, Modifier.tappable(onLongClick = {
                    overlays.actions(q.track.title, listOf(
                        SheetAction("Play Next") { at(q.uid).takeIf { it >= 0 }?.let { pm.move(it, pm.index.value + 1) } },
                        SheetAction("Remove from Queue", destructive = true) { remove(q.uid) },
                    ))
                }) { at(q.uid).takeIf { it >= 0 }?.let { pm.skipTo(it) } }) {
                    Box(Modifier.size(44.dp).pointerInput(q.uid) {
                        detectDragGestures(
                            onDragStart = { dragUid = q.uid; dragOffset = 0f },
                            onDragEnd = { dragUid = null; dragOffset = 0f },
                            onDragCancel = { dragUid = null; dragOffset = 0f },
                        ) { ch, d ->
                            ch.consume()
                            dragOffset += d.y
                            val qi = at(q.uid)
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
        }
        if (upcoming.isEmpty()) item("empty") {
            Txt("Nothing queued. Use Play Next or Add to Queue on any song.", Type.subhead, Modifier.padding(24.dp), color = c.label2, maxLines = 3)
        }
    }
}

@Composable
private fun QueueRow(q: QueueItem, modifier: Modifier, playing: Boolean = false, trailing: @Composable () -> Unit = {}) {
    val c = Ios.colors
    Row(modifier.fillMaxWidth().height(60.dp).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(q.track.thumb, 44.dp, corner = 5.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(q.track.title, Type.body, color = if (playing) c.accent else c.label, weight = if (playing) FontWeight.SemiBold else null)
            Txt(q.track.artist, Type.subhead, color = c.label2)
        }
        if (playing) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Sym.waveform, c.accent, size = 20.dp) }
        trailing()
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
    val pm = rememberControls()
    val remote by App.graph.connect.remote.collectAsState()
    val c = Ios.colors
    val item by pm.current.collectAsState()
    val playing by pm.playWhenReady.collectAsState()
    val buffering by pm.buffering.collectAsState()
    val duration by pm.durationMs.collectAsState()
    val position = rememberPlaybackPosition()
    val t = item?.track ?: return
    Box(dragModifier.fillMaxWidth().height(64.dp).background(c.bar).tappable(onClick = onOpen)) {
        Hairline(Modifier.align(Alignment.TopStart), c.barBorder)
        Row(Modifier.fillMaxSize().trackSwipe(pm::next, pm::previous).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.thumb, 46.dp, corner = 6.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(t.title, Type.subhead, weight = FontWeight.Medium)
                val rp = remote
                if (rp != null) Txt("Playing on ${rp.device.name}", Type.footnote, color = c.accent)
                else Txt(t.artist, Type.footnote, color = c.label2)
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

// ---- stems and queue, beside Now Playing on an unfolded phone -------------------------------------------

/** The unfolded phone's Playing pane: every stem on one line each, then Playing Next. */
@Composable
fun StemsPane(bottomPad: Dp) {
    val pm = rememberControls()
    val c = Ios.colors
    val item by pm.current.collectAsState()
    val names by pm.stemNames.collectAsState()
    val mix by pm.mix.collectAsState()
    val source by pm.source.collectAsState()
    val levels = rememberStemLevels(names)
    QueuePanel(bottomPad) {
        item("stems") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Txt("Stems", Type.title3, Modifier.padding(top = 8.dp, bottom = 12.dp))
                when {
                    item == null -> Txt("Play something and its stems show up here.", Type.subhead, color = c.label2, maxLines = 3)
                    source == SourceKind.Original -> NoStemsNote()
                    else -> PresetChips(names, mix, Modifier.padding(bottom = 10.dp), large = true)
                }
                names.forEachIndexed { i, name ->
                    val st = mix[name] ?: StemSetting()
                    StemRow(name, st, levels.getOrElse(i) { 0f }, enabled = source == SourceKind.Stems,
                        onLevel = { pm.setStemLevel(name, it) }, onMute = { pm.toggleMute(name) }, onSolo = { pm.toggleSolo(name) })
                }
                Hairline(Modifier.padding(top = 10.dp, bottom = 14.dp), c.separator)
            }
        }
    }
}

/** One stem on one line: name, fader with its live level under it, percentage, mute, solo. */
@Composable
private fun StemRow(name: String, s: StemSetting, level: Float, enabled: Boolean, onLevel: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit) {
    val c = Ios.colors
    val color = Color(Stems.color(name))
    Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.muted) c.label3 else color))
        Spacer(Modifier.width(8.dp))
        Txt(Stems.label(name), Type.headline, Modifier.width(60.dp), color = if (s.muted) c.label2 else c.label)
        Column(Modifier.weight(1f).padding(top = 6.dp)) {
            IosSlider(s.level, onLevel, fill = if (s.muted) c.label3 else color, detent = StemSetting.UNITY, enabled = enabled, thumb = 26.dp)
            Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 2.dp)) {
                val w = size.width * min(1f, max(0f, (20f * kotlin.math.log10(level + 1e-5f) + 48f) / 48f))
                drawRoundRect(c.fill, Offset.Zero, size, CornerRadius(size.height / 2))
                drawRoundRect(color, Offset.Zero, Size(w, size.height), CornerRadius(size.height / 2))
            }
        }
        val pct = (com.stemify.mobile.engine.stemTaper(s.level) * 100).toInt()
        Txt("$pct%", Type.footnote.copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.End), Modifier.width(42.dp), color = c.label2)
        Spacer(Modifier.width(6.dp))
        if (enabled) {
            ToggleBox("M", s.muted, c.red, onMute, big = true)
            Spacer(Modifier.width(5.dp))
            ToggleBox("S", s.solo, Color(0xFFFFCC00), onSolo, big = true)
        }
    }
}

// ---- tabletop: a half-open phone on its side ------------------------------------------------------------

/**
 * Tabletop: the art and title (or lyrics, or the queue) above the hinge at [foldAt]; below it, the
 * stems as a row of upright faders beside a compact transport and the seek bar.
 */
@Composable
fun TabletopPlayer(foldAt: Dp) {
    val g = App.graph
    val pm = rememberControls()
    val menu = rememberMediaMenu()
    val overlays = LocalOverlays.current
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
    val remote by g.connect.remote.collectAsState()
    val connState by g.connect.state.collectAsState()
    var panel by remember { mutableStateOf(NpPanel.Art) }
    val position = rememberPlaybackPosition()
    val levels = rememberStemLevels(names)
    val track = item?.track ?: return
    val c = nowPlayingPalette(track.thumb)
    androidx.activity.compose.BackHandler(enabled = panel != NpPanel.Art) { panel = NpPanel.Art }
    val env = remember(waveform, names, mix) { envelope(waveform, names, mix) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides c) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            // Above the fold.
            Box(Modifier.fillMaxWidth().height(foldAt).padding(top = statusTop)) {
                AnimatedContent(panel, Modifier.fillMaxSize(), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "top") { p ->
                    when (p) {
                        NpPanel.Lyrics -> Column {
                            MiniHeader(track.thumb, track.title, track.artist) { menu.show(track) }
                            LyricsPanel(track, position, names, mix, source, playing)
                        }
                        NpPanel.Queue -> QueuePanel()
                        else -> Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            BoxWithConstraints(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                                val side = maxHeight
                                val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.62f, stiffness = 260f), label = "art")
                                Box(Modifier.trackSwipe(pm::next, pm::previous).graphicsLayer { scaleX = scale; scaleY = scale }) {
                                    Artwork(track.thumb, side, corner = 12.dp, placeholder = Sym.note)
                                }
                            }
                            Spacer(Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(track.title, Type.title2, maxLines = 2)
                                Txt(track.artist, Type.title3, color = c.label2, weight = FontWeight.Normal)
                                Spacer(Modifier.height(4.dp))
                                Txt(track.album, Type.subhead, color = c.label2)
                            }
                            Box(Modifier.size(34.dp).clip(CircleShape).background(c.fill).pressFade { menu.show(track) }, contentAlignment = Alignment.Center) {
                                Icon(Sym.ellipsis, c.label, size = 18.dp)
                            }
                        }
                    }
                }
            }
            // Below the fold: stems on the left, the transport on the right.
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 24.dp, top = 16.dp, bottom = bottom + 6.dp)) {
                Box(Modifier.weight(1.5f).fillMaxHeight()) {
                    if (source == SourceKind.Original) Box(Modifier.align(Alignment.Center)) { NoStemsNote() }
                    else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        names.forEachIndexed { i, name ->
                            val st = mix[name] ?: StemSetting()
                            val db = 20f * kotlin.math.log10(levels.getOrElse(i) { 0f } + 1e-5f)
                            UprightStem(name, st, ((db + 48f) / 48f).coerceIn(0f, 1f), enabled = source == SourceKind.Stems,
                                onLevel = { pm.setStemLevel(name, it) }, onMute = { pm.toggleMute(name) }, onSolo = { pm.toggleSolo(name) })
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    ProgressVolumeBar(
                        position, duration, env, master, onSeek = pm::seekTo, onVolume = pm::setMaster,
                        played = c.label, unplayed = c.label3, centerLabel = sourceBadge(track, source, names.size),
                    )
                    Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Sym.gobackward10, { pm.skipBy(-10_000) }, tint = c.label, size = 26.dp, box = 44.dp)
                        IconButton(Sym.backward, pm::previous, tint = c.label, size = 32.dp, box = 50.dp)
                        Box(Modifier.size(62.dp).pressFade(onClick = pm::togglePlay), contentAlignment = Alignment.Center) {
                            if (buffering && playing) Spinner(size = 28.dp, color = c.label)
                            else Icon(if (playing) Sym.pause else Sym.play, c.label, size = 44.dp)
                        }
                        IconButton(Sym.forward, pm::next, tint = c.label, size = 32.dp, box = 50.dp)
                        IconButton(Sym.goforward10, { pm.skipBy(10_000) }, tint = c.label, size = 26.dp, box = 44.dp)
                    }
                    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        PanelToggle(Sym.quote, panel == NpPanel.Lyrics) { panel = if (panel == NpPanel.Lyrics) NpPanel.Art else NpPanel.Lyrics }
                        val rp = remote
                        Row(Modifier.weight(1f).pressFade { overlays.showDevices() }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (rp != null) Sym.airplay else deviceIcon(device).first, if (rp != null) c.accent else c.label2, size = 15.dp)
                            Spacer(Modifier.width(6.dp))
                            Txt(rp?.device?.name ?: device.label, Type.footnote, color = if (rp != null) c.accent else c.label2, weight = FontWeight.Medium)
                        }
                        PanelToggle(Sym.airplay, rp != null || connState != com.stemify.mobile.connect.Connect.State.Idle) { overlays.showDevices() }
                        Spacer(Modifier.width(4.dp))
                        PanelToggle(Sym.queue, panel == NpPanel.Queue) { panel = if (panel == NpPanel.Queue) NpPanel.Art else NpPanel.Queue }
                    }
                }
            }
        }
    }
}

/** One stem standing up: its percentage, fader with live level, mute and solo, and its name. */
@Composable
private fun UprightStem(name: String, s: StemSetting, level: Float, enabled: Boolean, onLevel: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit) {
    val c = Ios.colors
    val color = Color(Stems.color(name))
    Column(Modifier.fillMaxHeight().width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val pct = (com.stemify.mobile.engine.stemTaper(s.level) * 100).toInt()
        Txt("$pct%", Type.caption1.copy(fontFeatureSettings = "tnum"), color = c.label2)
        VerticalFader(s.level, onLevel, Modifier.weight(1f).padding(vertical = 4.dp), fill = if (s.muted) c.label3 else color,
            detent = StemSetting.UNITY, level = level, enabled = enabled)
        if (enabled) Row {
            ToggleBox("M", s.muted, c.red, onMute)
            Spacer(Modifier.width(4.dp))
            ToggleBox("S", s.solo, Color(0xFFFFCC00), onSolo)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.muted) c.label3 else color))
            Spacer(Modifier.width(4.dp))
            Txt(Stems.label(name), Type.footnote, color = if (s.muted) c.label2 else c.label, weight = FontWeight.SemiBold)
        }
    }
}
