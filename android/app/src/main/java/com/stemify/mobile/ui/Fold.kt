package com.stemify.mobile.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Where the window's hinge is, in the app's own coordinates (the activity is edge to edge, so
 * window pixels are root pixels). Only one of [verticalAt] / [horizontalAt] is set.
 *
 * - [verticalAt]: a fold running top to bottom, as on an unfolded book-style phone held upright.
 * - [horizontalAt] with [halfOpen]: tabletop, the phone on its side and partly closed.
 */
data class Fold(val verticalAt: Dp? = null, val horizontalAt: Dp? = null, val halfOpen: Boolean = false) {
    val tabletop get() = horizontalAt != null && halfOpen

    companion object { val None = Fold() }
}

@Composable
fun rememberFold(): Fold {
    val activity = LocalActivity.current ?: return Fold.None
    val density = LocalDensity.current
    val flow = remember(activity, density) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return@map Fold.None
            val half = f.state == FoldingFeature.State.HALF_OPENED
            with(density) {
                if (f.orientation == FoldingFeature.Orientation.VERTICAL) Fold(verticalAt = f.bounds.exactCenterX().toDp(), halfOpen = half)
                else Fold(horizontalAt = f.bounds.exactCenterY().toDp(), halfOpen = half)
            }
        }
    }
    val fold by flow.collectAsState(Fold.None)
    return fold
}

/** Windows at least this wide (an unfolded phone, a tablet) dock Now Playing beside the library. */
val WideWindow = 600.dp
