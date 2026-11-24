package com.ritim.ritimcraw.engine

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface
import com.ritim.ritimcraw.BuildConfig
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Matches the display refresh rate to the clip's frame rate while it PLAYS
 * (P3, 2026-11-25). 24p on a 60 Hz panel shows unevenly (3:2: every other
 * frame stays longer); with an exact multiple (24p -> 120 Hz, 30p -> 60 or
 * 120 Hz) every frame stays equally long. Without an exact multiple (25p on
 * 60/90/120/144 Hz) the rate whose frames come out most evenly is chosen
 * ([unevenness]). Only modes with the SAME resolution are candidates (the
 * switch must not blink the screen on the Snapdragon phones). Released when
 * playback stops: the system chooses again.
 *
 * The pure math ([pick], [unevenness], [modeIdFor]) is ported from Klipp's
 * engine/DisplayRate.kt (same owner's own code; licence is not an issue,
 * plan K6 - see NOTICE). The Android side differs: on API 30+ the preview's
 * Surface asks for the rate itself (Surface.setFrameRate, fixed-source
 * compatibility, seamless-only on 31+), which is what the framework
 * documents for video; only when there is no Surface to ask on (the CPU
 * preview path) or the API is older does the window's
 * preferredDisplayModeId carry the pick.
 */
object DisplayRate {
    /** One display mode, as much as the pick needs. */
    data class Mode(val modeId: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * The one of [rates] (Hz) to show [fps] at: the highest exact multiple;
     * without one, the rate with the most even frames (the higher of two
     * alike); null when there is nothing to choose from.
     */
    fun pick(rates: List<Float>, fps: Double): Float? {
        if (fps < 1.0 || rates.isEmpty()) return null
        rates.filter { r ->
            val k = r / fps
            k >= 0.99 && abs(k - k.roundToInt()) < 0.01
        }.maxOrNull()?.let { return it }
        return rates.sortedDescending().minByOrNull { unevenness(it, fps) }
    }

    /**
     * How unevenly [fps] shows at refresh [rate] (seconds): a frame stays a
     * whole number of refreshes, the one below or the one above its own
     * length; the root mean square of those two errors weighted by how
     * often each occurs. A rate below the frame rate cannot show every
     * frame and counts as one whole frame off.
     */
    fun unevenness(rate: Float, fps: Double): Double {
        val p = 1.0 / fps
        val t = 1.0 / rate
        val k = p / t
        if (k < 0.99) return p
        val lo = floor(k + 1e-9)
        val up = lo + 1
        val f = (k - lo).coerceIn(0.0, 1.0) // share of frames that stay the longer time
        val dLo = p - lo * t
        val dUp = up * t - p
        return sqrt(f * dUp * dUp + (1 - f) * dLo * dLo)
    }

    /** The mode id to ask for among [modes] at the current resolution, or 0 = the system's choice. */
    fun modeIdFor(modes: List<Mode>, currentWidth: Int, currentHeight: Int, fps: Double): Int {
        val same = modes.filter { it.width == currentWidth && it.height == currentHeight }
        val r = pick(same.map { it.refreshRate }, fps) ?: return 0
        return same.first { it.refreshRate == r }.modeId
    }
}

/** The Android side: begin at Play, end at pause/stop/leave. Debug builds log what was applied ("ritim-trace"). */
object DisplayRateApply {
    private const val TAG = "ritim-trace"

    /** Asks for [fps] while playing: the Surface when there is one (API 30+), else the window's mode. */
    fun begin(activity: Activity?, surface: Surface?, fps: Double) {
        if (fps < 1.0) return
        if (surface != null && surface.isValid && Build.VERSION.SDK_INT >= 30) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    surface.setFrameRate(
                        fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                    )
                } else {
                    surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "display-rate begin fps=$fps via=surface")
                return
            } catch (e: Exception) {
                // fall through to the window mode
            }
        }
        applyWindowMode(activity, fps)
    }

    /** Releases the request: the system chooses again. */
    fun end(activity: Activity?, surface: Surface?) {
        if (surface != null && surface.isValid && Build.VERSION.SDK_INT >= 30) {
            try {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (e: Exception) {
                // nothing to release
            }
        }
        val w = activity?.window ?: return
        val attrs = w.attributes
        if (attrs.preferredDisplayModeId != 0) {
            attrs.preferredDisplayModeId = 0
            w.attributes = attrs
            if (BuildConfig.DEBUG) Log.i(TAG, "display-rate end via=mode")
        } else if (BuildConfig.DEBUG) {
            Log.i(TAG, "display-rate end")
        }
    }

    private fun applyWindowMode(activity: Activity?, fps: Double) {
        val act = activity ?: return
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) act.display else act.windowManager.defaultDisplay) ?: return
        val cur = display.mode
        val modes = display.supportedModes.map {
            DisplayRate.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        val id = DisplayRate.modeIdFor(modes, cur.physicalWidth, cur.physicalHeight, fps)
        val w = act.window ?: return
        val attrs = w.attributes
        if (attrs.preferredDisplayModeId != id) {
            attrs.preferredDisplayModeId = id
            w.attributes = attrs
        }
        if (BuildConfig.DEBUG) {
            val hz = modes.firstOrNull { it.modeId == id }?.refreshRate
            Log.i(TAG, "display-rate begin fps=$fps via=mode id=$id hz=$hz")
        }
    }
}
