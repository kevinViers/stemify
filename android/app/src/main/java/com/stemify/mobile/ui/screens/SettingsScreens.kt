package com.stemify.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stemify.mobile.App
import com.stemify.mobile.core.Accent
import com.stemify.mobile.core.StemQuality
import com.stemify.mobile.core.ThemeMode
import com.stemify.mobile.plex.MusicSection
import com.stemify.mobile.stems.StemServerStatus
import com.stemify.mobile.ui.components.Cell
import com.stemify.mobile.ui.components.IosSwitch
import com.stemify.mobile.ui.components.LocalOverlays
import com.stemify.mobile.ui.components.Page
import com.stemify.mobile.ui.components.SegmentedControl
import com.stemify.mobile.ui.components.SettingsIcon
import com.stemify.mobile.ui.components.Spinner
import com.stemify.mobile.ui.components.section
import com.stemify.mobile.ui.components.tappable
import com.stemify.mobile.ui.nav.LocalNav
import com.stemify.mobile.ui.nav.Route
import com.stemify.mobile.ui.nav.rememberInEntry
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import com.stemify.mobile.ui.theme.palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun gb(bytes: Long) = if (bytes < 1e9) "%.0f MB".format(bytes / 1e6) else "%.1f GB".format(bytes / 1e9)

@Composable
fun SettingsScreen() {
    val g = App.graph
    val s = g.settings
    val nav = LocalNav.current
    val user by s.plexUser.flow.collectAsState()
    val token by s.plexToken.flow.collectAsState()
    val sectionTitle by s.musicSectionTitle.flow.collectAsState()
    val status by g.stems.status.collectAsState()
    val quality by s.stemQuality.flow.collectAsState()
    val keepMix by s.keepStemMix.flow.collectAsState()
    val onlineLyrics by s.onlineLyrics.flow.collectAsState()
    val wifi by s.downloadsWifiOnly.flow.collectAsState()
    val theme by s.theme.flow.collectAsState()
    val hz by s.highRefreshRate.flow.collectAsState()
    val usage by g.cache.usage.collectAsState()
    val eqState by g.eq.state.collectAsState()
    val eqProfile by g.eq.active.collectAsState()
    val view = LocalView.current
    val mode = view.display?.mode

    // Everything on one screen: no footers but the last, one line each.
    Page("Settings", grouped = true) {
        section("servers", header = "Servers", separatorInset = 60.dp, rows = listOf(
            { Cell("Plex", icon = { SettingsIcon(Sym.server, Color(0xFFE5A00D)) }, value = if (token.isEmpty()) "Not Signed In" else sectionTitle.ifEmpty { user }, chevron = true) { nav.push(Route.PlexAccount) } },
            { Cell("Stem Server", icon = { SettingsIcon(Sym.waveform, Color(0xFFAF52DE)) }, chevron = true, value = when (val st = status) {
                is StemServerStatus.Online -> "${st.tracks} songs"
                is StemServerStatus.Offline -> "Offline"
                StemServerStatus.Unknown -> "…"
            }) { nav.push(Route.StemServer) } },
        ))
        section("sound", header = "Sound", separatorInset = 60.dp, rows = listOf(
            { Cell("Equalizer", icon = { SettingsIcon(Sym.sliders, Color(0xFF34C759)) }, value = if (!eqState.enabled) "Off" else eqProfile?.name ?: "On", chevron = true) { nav.push(Route.Eq) } },
            { Cell("Stem Quality", icon = { SettingsIcon(Sym.gauge, Color(0xFFFF2D55)) }, value = quality.label, chevron = true) { nav.push(Route.Quality) } },
            { Cell("Keep Stem Mix Between Songs", icon = { SettingsIcon(Sym.speakerWave, Color(0xFF5856D6)) }, trailing = { IosSwitch(keepMix, { s.keepStemMix.value = it }) }) },
            { Cell("Search LRCLIB for Lyrics", icon = { SettingsIcon(Sym.quote, Color(0xFFFF9500)) }, trailing = { IosSwitch(onlineLyrics, { s.onlineLyrics.value = it }) }) },
        ))
        section("storage", header = "Storage", separatorInset = 60.dp, rows = listOf(
            { Cell("Cache & Downloads", icon = { SettingsIcon(Sym.externalDrive, Color(0xFF8E8E93)) }, value = gb(usage.cachedBytes), chevron = true) { nav.push(Route.Storage) } },
            { Cell("Download on Wi-Fi Only", icon = { SettingsIcon(Sym.download, Color(0xFF30B0C7)) }, trailing = { IosSwitch(wifi, { s.downloadsWifiOnly.value = it }) }) },
        ))
        section("display", header = "Display", separatorInset = 60.dp,
            footer = "Stemify 1.1 · stems by stemd on your server" + (mode?.let { " · ${"%.0f".format(it.refreshRate)} Hz" } ?: ""),
            rows = listOf(
                { Cell("Appearance", icon = { SettingsIcon(Sym.paintbrush, Color(0xFF007AFF)) }, value = theme.label, chevron = true) { nav.push(Route.Appearance) } },
                { Cell("High Refresh Rate", icon = { SettingsIcon(Sym.phone, Color(0xFFFF9500)) }, trailing = { IosSwitch(hz, { s.highRefreshRate.value = it }) }) },
            ))
    }
}

/** A Settings-style row with an inline text field on the right. */
@Composable
private fun FieldCell(label: String, value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Uri) {
    val c = Ios.colors
    Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Type.body, Modifier.width(96.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Txt(placeholder, Type.body, color = c.label3, align = TextAlign.End)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = Type.body.copy(color = c.label2, textAlign = TextAlign.End),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private class PinState {
    var code by mutableStateOf<String?>(null)
    var id by mutableStateOf(0L)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun PlexAccountScreen() {
    val g = App.graph
    val s = g.settings
    val c = Ios.colors
    val ctx = LocalContext.current
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val token by s.plexToken.flow.collectAsState()
    val user by s.plexUser.flow.collectAsState()
    val section by s.musicSection.flow.collectAsState()
    var url by rememberInEntry("url") { mutableStateOf(s.plexUrl.value) }
    var manual by rememberInEntry("manual") { mutableStateOf("") }
    val pin = rememberInEntry("pin") { PinState() }
    var sections by rememberInEntry("sections") { mutableStateOf<List<MusicSection>?>(null) }
    var testing by remember { mutableStateOf(false) }

    suspend fun finish(newToken: String) {
        s.plexUrl.value = url.trim().trimEnd('/')
        s.plexToken.value = newToken
        s.plexUser.value = runCatching { g.plex.user(newToken).let { it.title ?: it.username ?: "Plex user" } }.getOrDefault("Plex user")
        s.plexMachineId.value = runCatching { g.plex.machineId() }.getOrDefault("")
        sections = runCatching { g.plex.musicSections() }.getOrNull()
        sections?.singleOrNull()?.let { s.musicSection.value = it.key; s.musicSectionTitle.value = it.title }
        g.stems.refresh()
    }

    LaunchedEffect(token) {
        if (token.isNotEmpty() && sections == null) sections = runCatching { g.plex.musicSections() }.getOrNull()
    }
    // Poll plex.tv while a PIN is showing.
    LaunchedEffect(pin.id) {
        if (pin.id == 0L) return@LaunchedEffect
        repeat(300) {
            delay(2000)
            val p = runCatching { g.plex.checkPin(pin.id) }.getOrNull()
            val t = p?.authToken
            if (!t.isNullOrEmpty()) {
                pin.code = null
                pin.id = 0
                finish(t)
                overlays.hud("Signed In")
                return@LaunchedEffect
            }
        }
        pin.code = null
        pin.id = 0
        pin.error = "The code expired. Try again."
    }

    Page("Plex", grouped = true) {
        section("server", header = "Server", footer = "Your server's LAN address (port 32400).", rows = listOf(
            { FieldCell("Address", url, { url = it }, "http://192.168.86.2:32400") },
            {
                Cell(if (testing) "Testing…" else "Test Connection", titleColor = c.accent) {
                    testing = true
                    scope.launch {
                        val ok = runCatching { g.plex.probe(url, token) }.getOrDefault(false)
                        testing = false
                        if (ok) {
                            s.plexUrl.value = url.trim().trimEnd('/')
                            overlays.hud("Connected")
                        } else overlays.hud(if (token.isEmpty()) "Sign in first" else "Couldn't reach Plex at that address", Sym.warning)
                    }
                }
            },
        ))
        if (token.isEmpty()) {
            section("signin", header = "Sign In", footer = pin.error ?: if (pin.code == null) "Enter the code at plex.tv/link while signed in to Plex." else null, rows = listOfNotNull(
                {
                    Cell(if (pin.code == null) "Sign In with plex.tv" else "Get a New Code", titleColor = c.accent) {
                        pin.error = null
                        scope.launch {
                            runCatching { g.plex.createPin() }
                                .onSuccess { pin.code = it.code; pin.id = it.id }
                                .onFailure { pin.error = "Couldn't reach plex.tv: ${it.message}" }
                        }
                    }
                },
                if (pin.code != null) ({
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(pin.code!!, Type.largeTitle.copy(fontSize = 44.sp, letterSpacing = 8.sp), weight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Txt("Waiting for plex.tv/link…", Type.footnote, color = c.label2)
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.clip(CircleShape).background(c.accent).tappable {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plex.tv/link")))
                        }.padding(horizontal = 18.dp, vertical = 9.dp)) { Txt("Open plex.tv/link", Type.headline, color = Color.White) }
                    }
                }) else null,
            ))
            section("token", header = "Or Paste an X-Plex-Token", rows = listOf(
                { FieldCell("Token", manual, { manual = it }, "Required", secret = true, keyboard = KeyboardType.Password) },
                {
                    Cell("Use Token", titleColor = if (manual.isBlank()) c.label3 else c.accent) {
                        if (manual.isNotBlank()) scope.launch {
                            if (g.plex.probe(url, manual.trim())) finish(manual.trim()) else overlays.hud("That token didn't work on this server", Sym.warning)
                        }
                    }
                },
            ))
        } else {
            section("account", header = "Account", rows = listOf { Cell(user.ifEmpty { "Signed In" }, icon = { SettingsIcon(Sym.person, Color(0xFFE5A00D)) }) })
            val list = sections
            section("library", header = "Music Library", rows = when {
                list == null -> listOf { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() } }
                list.isEmpty() -> listOf { Cell("No music libraries found") }
                else -> list.map { sec ->
                    { Cell(sec.title, subtitle = sec.locations.joinToString(), checked = sec.key == section) {
                        s.musicSection.value = sec.key
                        s.musicSectionTitle.value = sec.title
                    } }
                }
            })
            section("signout", rows = listOf {
                Cell("Sign Out", titleColor = c.red, centered = true) {
                    overlays.confirm("Sign Out of Plex?", "Downloads stay on this phone.", "Sign Out") {
                        s.plexToken.value = ""
                        s.plexUser.value = ""
                        s.musicSection.value = ""
                        s.musicSectionTitle.value = ""
                        sections = null
                    }
                }
            })
        }
    }
}

@Composable
fun StemServerScreen() {
    val g = App.graph
    val s = g.settings
    val c = Ios.colors
    val overlays = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val status by g.stems.status.collectAsState()
    var url by rememberInEntry("url") { mutableStateOf(s.stemUrl.value) }
    var token by rememberInEntry("token") { mutableStateOf(s.stemToken.value) }
    var busy by remember { mutableStateOf(false) }
    Page("Stem Server", grouped = true) {
        section("addr", header = "stemserve", footer = "stemserve shares the Stems folder that stemd fills (port 8765). The app checks it for new stems every minute.", rows = listOf(
            { FieldCell("Address", url, { url = it }, "http://192.168.86.2:8765") },
            { FieldCell("Token", token, { token = it }, "Optional", secret = true, keyboard = KeyboardType.Password) },
            {
                Cell(if (busy) "Connecting…" else "Save & Connect", titleColor = c.accent) {
                    s.stemUrl.value = url.trim().trimEnd('/')
                    s.stemToken.value = token.trim()
                    busy = true
                    scope.launch {
                        g.stems.refresh(force = true)
                        busy = false
                        overlays.hud(if (g.stems.status.value is StemServerStatus.Online) "Connected" else "Couldn't connect", if (g.stems.status.value is StemServerStatus.Online) Sym.check else Sym.warning)
                    }
                }
            },
        ))
        section("status", header = "Status", rows = listOf<@Composable () -> Unit>(
            { Cell("Connection", value = when (val st = status) {
                is StemServerStatus.Online -> "Online"
                is StemServerStatus.Offline -> "Offline"
                StemServerStatus.Unknown -> "Checking"
            }) },
            { Cell("Songs with Stems", value = when (val st = status) {
                is StemServerStatus.Online -> "${st.tracks}"
                is StemServerStatus.Offline -> "${st.cachedTracks} (cached)"
                StemServerStatus.Unknown -> "—"
            }) },
            { Cell("Opus Transcoding", value = (status as? StemServerStatus.Online)?.let { if (it.transcode) "Available" else "No ffmpeg" } ?: "—") },
        ) + listOfNotNull((status as? StemServerStatus.Offline)?.let { st -> { Cell("Error", subtitle = st.reason) } }))
    }
}

@Composable
fun QualityScreen() {
    val s = App.graph.settings
    val q by s.stemQuality.flow.collectAsState()
    Page("Stem Quality", grouped = true) {
        section("q", footer = "For streaming and new downloads. Opus is made once on the server and kept there; 256 kbps is about an eighth of the FLAC size.", rows =
            StemQuality.entries.map { e -> { Cell(e.label, subtitle = e.detail, checked = q == e) { s.stemQuality.value = e } } })
    }
}

@Composable
fun StorageScreen() {
    val g = App.graph
    val app = LocalApp.current
    val s = g.settings
    val c = Ios.colors
    val nav = LocalNav.current
    val overlays = LocalOverlays.current
    val usage by g.cache.usage.collectAsState()
    val limit by s.cacheLimitGb.flow.collectAsState()
    Page("Storage", grouped = true) {
        section("usage", header = "On This Phone", rows = listOf(
            { Cell("Downloads", value = gb(usage.pinnedBytes), chevron = true) { app.selectTab(Tab.Downloads) } },
            { Cell("Streaming Cache", value = gb(usage.cachedBytes - usage.pinnedBytes)) },
        ))
        val limits = listOf(2, 5, 10, 20, 50, 100)
        section("limit", header = "Streaming Cache Limit", footer = "Streamed songs stay for replay and offline use, oldest first out. Downloads don't count.", rows = listOf {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                SegmentedControl(limits.map { "$it GB" }, limits.indexOf(limit).coerceAtLeast(0), { s.cacheLimitGb.value = limits[it] })
            }
        })
        section("clear", rows = listOf {
            Cell("Clear Streaming Cache", titleColor = c.red, centered = true) {
                overlays.confirm("Clear Cache?", "Downloads are kept.", "Clear") { g.cache.clearUnpinned() }
            }
        })
    }
}

@Composable
fun AppearanceScreen() {
    val s = App.graph.settings
    val c = Ios.colors
    val theme by s.theme.flow.collectAsState()
    val accent by s.accent.flow.collectAsState()
    val albumColor by s.albumColorNowPlaying.flow.collectAsState()
    Page("Appearance", grouped = true) {
        section("theme", header = "Theme", footer = "Black is true black for OLED. No gradients anywhere, by design.", rows = listOf(
            {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ThemeMode.entries.forEach { m ->
                        val p = palette(m, accent)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.tappable { s.theme.value = m }) {
                            Box(
                                Modifier.size(78.dp, 140.dp).clip(RoundedCornerShape(12.dp)).background(p.groupedBg)
                                    .border(if (m == theme) 3.dp else 1.dp, if (m == theme) c.accent else c.separator, RoundedCornerShape(12.dp)).padding(8.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(40.dp, 6.dp).clip(CircleShape).background(p.label))
                                    repeat(3) { Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(4.dp)).background(p.cell)) }
                                    Box(Modifier.size(24.dp, 6.dp).clip(CircleShape).background(p.accent))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Txt(m.label, Type.subhead, weight = if (m == theme) FontWeight.SemiBold else null)
                        }
                    }
                }
            },
        ))
        section("accent", header = "Accent", rows = listOf {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Accent.entries.forEach { a ->
                    val col = Color(if (c.dark) a.dark else a.light)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(col).tappable { s.accent.value = a }
                            .border(if (a == accent) 3.dp else 0.dp, if (a == accent) c.label else Color.Transparent, CircleShape),
                    )
                }
            }
        })
        section("np", header = "Now Playing", footer = "Paints the Now Playing screen in one solid colour taken from the album art.", rows = listOf {
            Cell("Album Colour Background", trailing = { IosSwitch(albumColor, { s.albumColorNowPlaying.value = it }) })
        })
    }
}
