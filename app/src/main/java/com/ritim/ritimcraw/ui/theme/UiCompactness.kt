package com.ritim.ritimcraw.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * THE APP'S ONE COMPACTNESS TOKEN (owner feedback 2026-11-23: "items feel
 * too big"). The owner's phone reports density 560 on a 1080px-wide panel,
 * so the app only gets ~308dp of width where a typical phone has ~400dp —
 * every Material dimension (48dp rows, 16dp gutters, 14sp labels) lands
 * ~30% larger on screen than the spec intends, and labels truncate
 * ("Histogra", "T / Li").
 *
 * Instead of re-tuning hundreds of call sites, the whole tree renders
 * against a slightly smaller [Density] whenever the window's SHORTEST side
 * is narrower than [TARGET_SHORT_SIDE_DP]: the layout then sees a ~400dp
 * phone, which is what every screen was designed for. The system display
 * density is never touched.
 *
 * - Shortest side, not width: landscape gets the same scale as portrait,
 *   so rotating never re-sizes the chrome.
 * - Clamped at [MIN_SCALE]: a pathological tiny window never shrinks text
 *   into illegibility. Tablets (shortest side >= target) get 1.0 — no change.
 * - fontScale is preserved, so the user's accessibility text size still
 *   applies on top.
 * - Touch targets: Material's 48dp minimum is still honored in the scaled
 *   space; at the owner's 560-density panel (~395 physical ppi) a 48dp
 *   target is ~8mm, inside the 7-10mm physical guidance.
 *
 * [LocalConfiguration] is re-provided with the scaled dp sizes so code
 * that sizes layouts from `screenWidthDp` (the editor's orientation
 * split) agrees with the px math done through [LocalDensity].
 */
object UiCompactness {
    /** The dp width every phone layout in the app was designed against. */
    const val TARGET_SHORT_SIDE_DP = 400f

    /** Never shrink further than this (illegibility floor). */
    const val MIN_SCALE = 0.75f

    /** The density multiplier for a window whose shortest side is [shortSideDp]. */
    fun scaleFor(shortSideDp: Int): Float {
        if (shortSideDp <= 0) return 1f
        return (shortSideDp / TARGET_SHORT_SIDE_DP).coerceIn(MIN_SCALE, 1f)
    }
}

/**
 * Wraps [content] in the compact density (see [UiCompactness]). A no-op on
 * windows already at or above the target width.
 */
@Composable
fun CompactDensity(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val base = LocalDensity.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val scale = UiCompactness.scaleFor(shortSide)
    if (scale >= 1f) {
        content()
        return
    }
    val density = remember(base, scale) { Density(base.density * scale, base.fontScale) }
    val scaledConfiguration = remember(configuration, scale) {
        Configuration(configuration).apply {
            screenWidthDp = (configuration.screenWidthDp / scale).toInt()
            screenHeightDp = (configuration.screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (configuration.smallestScreenWidthDp / scale).toInt()
            densityDpi = (configuration.densityDpi * scale).toInt()
        }
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalConfiguration provides scaledConfiguration,
        content = content,
    )
}
