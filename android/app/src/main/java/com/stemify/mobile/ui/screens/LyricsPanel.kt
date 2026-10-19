package com.stemify.mobile.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stemify.mobile.App
import com.stemify.mobile.lyrics.Karaoke
import com.stemify.mobile.lyrics.LyricLine
import com.stemify.mobile.lyrics.Lyrics
import com.stemify.mobile.playback.SourceKind
import com.stemify.mobile.playback.StemSetting
import com.stemify.mobile.plex.Track
import com.stemify.mobile.ui.components.Chip
import com.stemify.mobile.ui.components.EmptyState
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data class Done(val lyrics: Lyrics?) : LyricsLoad
}

/** Lines switch a touch early, so the eye is there when the voice is. */
private const val LEAD_MS = 150L

/** After a manual scroll, the lyrics stay put this long before following the song again. */
private const val FOLLOW_AFTER_MS = 4000L

/** Guide vocals: the singer about 14 dB down, there to follow but not to lean on. */
private const val GUIDE_LEVEL = 0.36f

private val LyricStyle get() = Type.title2.copy(fontSize = 27.sp, lineHeight = 34.sp)

/**
 * Lyrics for the playing song, karaoke style: synced lyrics follow along word by word and a
 * tap on a line jumps there; Vocals On / Guide / Off sets the vocal stem for singing along.
 */
@Composable
internal fun LyricsPanel(track: Track, position: State<Long>, names: List<String>, mix: Map<String, StemSetting>, source: SourceKind, playing: Boolean) {
    val g = App.graph
    val c = Ios.colors
    var attempt by remember(track.ratingKey) { mutableIntStateOf(0) }
    val online by g.settings.onlineLyrics.flow.collectAsState()
    val load by produceState<LyricsLoad>(LyricsLoad.Loading, track.ratingKey, attempt, online) {
        value = LyricsLoad.Loading
        value = try {
            LyricsLoad.Done(g.lyrics.get(track, refresh = attempt > 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsLoad.Done(null)
        }
    }

    // Singing along shouldn't need a tap every 30 seconds to keep the screen on.
    val view = LocalView.current
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        if (source == SourceKind.Stems && "vocals" in names) VocalsRow(names, mix)
        when (val l = load) {
            LyricsLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 26.dp, color = c.label2) }
            is LyricsLoad.Done -> {
                val ly = l.lyrics
                when {
                    ly == null -> EmptyState(
                        Sym.quote, "No Lyrics",
                        if (online) "Neither Plex nor LRCLIB has lyrics for this song." else "Plex has no lyrics for this song. Turn on Search LRCLIB for Lyrics in Settings to look online too.",
                        action = "Try Again",
                    ) { attempt++ }
                    ly.instrumental -> EmptyState(Sym.note, "Instrumental", "There are no words in this one.")
                    else -> LyricLines(ly, position)
                }
            }
        }
    }
}

/** Vocals On / Guide / Off, from the lyrics. Clears solos so the choice is what you hear. */
@Composable
private fun VocalsRow(names: List<String>, mix: Map<String, StemSetting>) {
    val pm = App.graph.playback
    val c = Ios.colors
    val v = mix["vocals"] ?: StemSetting()
    val othersSolo = names.any { it != "vocals" && mix[it]?.solo == true }
    val mode = when {
        v.muted || v.level <= 0f || othersSolo && !v.solo -> 2
        v.level < StemSetting.UNITY - 0.05f -> 1
        else -> 0
    }
    fun set(s: StemSetting) = pm.applyMix(mix.mapValues { it.value.copy(solo = false) } + ("vocals" to s))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt("Vocals", Type.footnote, Modifier.padding(end = 4.dp), color = c.label2)
        Chip("On", mode == 0, onClick = { set(StemSetting()) })
        Chip("Guide", mode == 1, onClick = { set(StemSetting(level = GUIDE_LEVEL)) })
        Chip("Off", mode == 2, onClick = { set(StemSetting(muted = true)) })
    }
}

@Composable
private fun LyricLines(ly: Lyrics, position: State<Long>) {
    val pm = App.graph.playback
    val c = Ios.colors
    val lines = ly.lines
    val list = rememberLazyListState()
    val current by remember(ly) { derivedStateOf { if (ly.synced) Karaoke.lineAt(lines, position.value + LEAD_MS) else -1 } }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    // When following the song resumes after a manual scroll (uptime ms); 0 is now.
    var followAt by remember(ly) { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) {
        if (dragged) followAt = Long.MAX_VALUE
        else if (followAt == Long.MAX_VALUE) followAt = SystemClock.uptimeMillis() + FOLLOW_AFTER_MS
    }
    if (ly.synced) {
        LaunchedEffect(current, followAt) {
            if (dragged) return@LaunchedEffect
            val wait = followAt - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            list.animateScrollToItem(current.coerceAtLeast(0))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The current line sits about a third of the way down, with room to scroll the last one up there.
        val pad = PaddingValues(top = if (ly.synced) maxHeight * 0.28f else 12.dp, bottom = if (ly.synced) maxHeight * 0.6f else 24.dp)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = pad) {
            items(lines.size) { i ->
                val l = lines[i]
                val seek = if (ly.synced) Modifier.tappable { followAt = 0L; pm.seekTo(l.ms) } else Modifier
                Box(seek.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (l.text.isEmpty()) 4.dp else 9.dp)) {
                    LyricRow(lines, i, i == current, i < current, ly.synced, position)
                }
            }
            item("credit") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                    ly.credit?.let { Txt(it, Type.caption1, color = c.label2, maxLines = 4) }
                    Txt(
                        "Lyrics from ${ly.source}" + if (ly.synced) "" else " · not time-synced",
                        Type.caption1, Modifier.padding(top = 4.dp), color = c.label2,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricRow(lines: List<LyricLine>, i: Int, isCurrent: Boolean, past: Boolean, synced: Boolean, position: State<Long>) {
    val c = Ios.colors
    val l = lines[i]
    val target = when {
        !synced -> 0.92f
        isCurrent -> 1f
        past -> 0.32f
        else -> 0.42f
    }
    val alpha by animateFloatAsState(target, tween(220), label = "line")
    if (l.text.isEmpty()) {
        // A break between verses.
        Txt("♪", Type.title3, color = c.label.copy(alpha = if (isCurrent) 0.9f else 0.3f))
        return
    }
    if (!isCurrent) {
        BasicText(l.text, style = LyricStyle.copy(color = c.label.copy(alpha = alpha)))
        return
    }
    // The line being sung fills in word by word as it goes; only this row redraws every frame.
    val dim = c.label.copy(alpha = 0.42f)
    val sung = Karaoke.sung(lines, i, position.value)
    val n = sung.toInt().coerceIn(0, l.text.length)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.label)) { append(l.text.substring(0, n)) }
        if (n < l.text.length) {
            withStyle(SpanStyle(color = lerp(dim, c.label, sung - n))) { append(l.text[n]) }
            withStyle(SpanStyle(color = dim)) { append(l.text.substring(n + 1)) }
        }
    }
    BasicText(text, style = LyricStyle.copy(color = Color.Unspecified))
}
