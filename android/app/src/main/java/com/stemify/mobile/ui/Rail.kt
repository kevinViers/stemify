package com.stemify.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.stemify.mobile.ui.components.pressFade
import com.stemify.mobile.ui.screens.Tab
import com.stemify.mobile.ui.theme.Icon
import com.stemify.mobile.ui.theme.Ios
import com.stemify.mobile.ui.theme.Sym
import com.stemify.mobile.ui.theme.Txt
import com.stemify.mobile.ui.theme.Type
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.Library -> Sym.library
    Tab.Playlists -> Sym.noteList
    Tab.Search -> Sym.search
    Tab.Downloads -> Sym.download
    Tab.Settings -> Sym.gear
}

/** Unfolded, the rail's clock stands in for the status bar, which hides (a swipe brings it back). */
@Composable
fun HideStatusBar(hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(hidden) {
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) ctl.hide(WindowInsetsCompat.Type.statusBars()) else ctl.show(WindowInsetsCompat.Type.statusBars())
        onDispose { if (hidden) ctl.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/**
 * The unfolded phone's tabs, down the right edge like an iPad sidebar: the time and battery up
 * top, then Playing (the stems and queue), the five tabs, and Swap (which side Now Playing is on).
 */
@Composable
fun SideRail(modifier: Modifier, playing: Boolean, tab: Tab, onPlaying: () -> Unit, onTab: (Tab) -> Unit, onSwap: () -> Unit) {
    val c = Ios.colors
    val top = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier.padding(top = top + 8.dp, bottom = bottom + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailStatus()
        Spacer(Modifier.height(10.dp))
        RailItem(Sym.waveform, "Playing", playing, onPlaying)
        Tab.entries.forEach { t -> RailItem(tabIcon(t), t.label, !playing && tab == t) { onTab(t) } }
        Box(Modifier.padding(vertical = 8.dp).size(30.dp, 1.dp).background(c.separator))
        RailItem(Sym.swap, "Swap", false, onSwap)
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ios.colors
    val col = if (on) c.accent else c.tabInactive
    Column(
        Modifier.width(66.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) c.accent.copy(alpha = 0.16f) else Color.Transparent)
            .pressFade(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, col, size = 26.dp)
        Spacer(Modifier.height(3.dp))
        Txt(label, Type.caption2.copy(fontSize = 10.5.sp), color = col, weight = FontWeight.Medium)
    }
}

private data class Status(val time: String, val battery: Float, val charging: Boolean, val wifi: Boolean)

private fun readStatus(ctx: Context): Status {
    val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toFloat() / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1f
    val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val wifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    return Status(SimpleDateFormat(pattern, Locale.getDefault()).format(Date()), level, plugged, wifi)
}

/** The time, and Wi-Fi inside a ring that's the battery level (green while charging). */
@Composable
private fun RailStatus() {
    val c = Ios.colors
    val ctx = LocalContext.current
    val st by produceState(readStatus(ctx), ctx) {
        while (true) {
            value = readStatus(ctx)
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    Txt(st.time, Type.headline.copy(fontSize = 19.sp, fontFeatureSettings = "tnum"), weight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    val ring: Dp = 46.dp
    Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ring)) {
            val w = 3.2.dp.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(c.fill, 0f, 360f, false, tl, arcSize, style = Stroke(w))
            if (st.battery >= 0f) {
                val col = if (st.charging) Color(0xFF34C759) else if (st.battery <= 0.2f) c.red else c.label
                drawArc(col, -90f, 360f * st.battery, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Icon(Sym.wifi, if (st.wifi) c.label else c.label3, size = 22.dp)
    }
}
