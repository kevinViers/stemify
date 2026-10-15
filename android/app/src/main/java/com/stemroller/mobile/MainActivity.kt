package com.stemroller.mobile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.stemroller.mobile.core.ThemeMode
import com.stemroller.mobile.playback.PlaybackService
import com.stemroller.mobile.ui.StemApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null
    private val openNowPlaying = mutableIntStateOf(0)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = App.graph
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(g.settings.theme.flow, g.settings.highRefreshRate.flow) { t, hz -> t to hz }.collect { (theme, hz) ->
                    val light = theme == ThemeMode.White
                    enableEdgeToEdge(
                        statusBarStyle = if (light) SystemBarStyle.light(0, 0) else SystemBarStyle.dark(0),
                        navigationBarStyle = if (light) SystemBarStyle.light(0, 0) else SystemBarStyle.dark(0),
                    )
                    window.isNavigationBarContrastEnforced = false
                    applyRefreshRate(hz)
                }
            }
        }
        if (intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true) openNowPlaying.intValue++
        setContent { StemApp(openNowPlaying.intValue) }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NOW_PLAYING, false)) openNowPlaying.intValue++
    }

    /**
     * Asks for the panel's fastest mode at its full resolution: on a OnePlus 15 that's
     * 1272x2772 at 165 Hz. Compose then renders at whatever vsync that gives us.
     */
    private fun applyRefreshRate(high: Boolean) {
        val d = display ?: return
        val modes = d.supportedModes
        val maxPixels = modes.maxOf { it.physicalWidth * it.physicalHeight }
        val best = modes.filter { it.physicalWidth * it.physicalHeight == maxPixels }.maxByOrNull { it.refreshRate }
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (high && best != null) best.modeId else 0
            preferredRefreshRate = if (high && best != null) best.refreshRate else 0f
        }
        if (Build.VERSION.SDK_INT >= 35) {
            // An explicit rate, not CATEGORY_HIGH: OxygenOS maps the "high" category to 90 Hz.
            window.decorView.requestedFrameRate = if (high && best != null) best.refreshRate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            window.frameRateBoostOnTouchEnabled = high
        }
    }

    override fun onStart() {
        super.onStart()
        // A connected controller keeps the session service bound, so playback starts it in the foreground.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        App.graph.eq.refreshRoute()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    companion object {
        const val EXTRA_NOW_PLAYING = "now_playing"
    }
}
