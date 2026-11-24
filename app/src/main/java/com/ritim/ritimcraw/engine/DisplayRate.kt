package com.ritim.ritimcraw.engine

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Surface
import com.ritim.ritimcraw.BuildConfig
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Matches the display refresh rate to the clip's frame rate while it PLAYS (P3, 2026-11-25).
 *
 * The problem is arithmetic: a frame can only stay on screen for a whole number of refresh
 * intervals. When the refresh rate is a whole multiple of the frame rate (24p on 120 Hz = 5,
 * 30p on 60 Hz = 2) every frame is held equally long; otherwise some frames are held one
 * interval longer than others (24p on 60 Hz alternates 2 and 3: the 3:2 judder). [pick] takes the
 * highest whole multiple among the rates the screen offers, and when there is none the rate whose
 * hold times deviate least from the true frame period ([unevenness]). Only modes at the CURRENT
 * resolution are candidates (a resolution switch can blink the screen). The request is released
 * when playback stops.
 *
 * Written from the arithmetic above, not copied from another code base (Klipp is GPL-3.0-only;
 * this project is Apache-2.0): the structure and names here are this project's own.
 *
 * On API 30+ the preview's Surface asks for the rate itself (Surface.setFrameRate, fixed-source
 * compatibility, seamless-only on 31+); only when there is no Surface (the CPU preview path) or the
 * API is older does the window's preferredDisplayModeId carry the pick. OFF by default, see
 * [DisplayRateApply.ENABLED_BY_DEFAULT].
 */
object DisplayRate {
    /** One display mode, as much as the pick needs. */
    data class Mode(val modeId: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** How close to a whole number the rate/fps ratio must be to count as a whole multiple. */
    private const val WHOLE_MULTIPLE_SLACK = 0.01

    /** A rate below this ratio to the frame rate cannot show every frame at all. */
    private const val MIN_USEFUL_RATIO = 0.99

    /** True when [refreshHz] is (within [WHOLE_MULTIPLE_SLACK]) a whole multiple, at least 1x, of [fps]. */
    private fun isWholeMultiple(refreshHz: Float, fps: Double): Boolean {
        val ratio = refreshHz / fps
        return ratio >= MIN_USEFUL_RATIO && abs(ratio - Math.rint(ratio)) < WHOLE_MULTIPLE_SLACK
    }

    /**
     * The refresh rate (Hz) to play [fps] at, or null when there is nothing to choose from: the
     * highest whole multiple; without one, the rate with the least [unevenness] (the higher of two
     * equally even rates).
     */
    fun pick(rates: List<Float>, fps: Double): Float? {
        if (fps < 1.0 || rates.isEmpty()) return null
        val whole = rates.filter { isWholeMultiple(it, fps) }
        if (whole.isNotEmpty()) return whole.max()
        // Highest first, so minBy keeps the higher rate when two are equally even.
        return rates.sortedDescending().minByOrNull { unevenness(it, fps) }
    }

    /**
     * How far (in seconds, root-mean-square) the hold times of [fps] on a [refreshHz] screen sit
     * from the true frame period. A frame is held for the whole number of refresh intervals just
     * below or just above its length: the share of frames held longer is the fractional part of
     * (period / interval), and each hold is early or late by the gap to the true period. 0 for a
     * whole multiple. A rate too low to show every frame costs one whole frame period.
     */
    fun unevenness(refreshHz: Float, fps: Double): Double {
        val framePeriod = 1.0 / fps
        val interval = 1.0 / refreshHz
        val intervalsPerFrame = framePeriod / interval
        if (intervalsPerFrame < MIN_USEFUL_RATIO) return framePeriod
        val shortHold = floor(intervalsPerFrame + 1e-9)
        val longShare = (intervalsPerFrame - shortHold).coerceIn(0.0, 1.0)
        val earlyBy = framePeriod - shortHold * interval
        val lateBy = (shortHold + 1.0) * interval - framePeriod
        return sqrt(longShare * lateBy * lateBy + (1.0 - longShare) * earlyBy * earlyBy)
    }

    /** Whether to ask the display for the clip's rate: the default or the debug enable flag, unless disabled. */
    fun shouldRequest(enabledByDefault: Boolean, enableFlag: Boolean, disableFlag: Boolean): Boolean =
        (enabledByDefault || enableFlag) && !disableFlag

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

    /**
     * Off by default (owner decision 2026-11-25). Device A/B on one 4K30 MCRAW clip at the
     * 1280 tier, four interleaved runs each: request on 8.9% dropped frames / p95 frame gap
     * 61.0 ms, request off 6.8% / 59.4 ms, i.e. no measurable benefit; the stutter there is
     * render time against the frame budget, not the vsync pattern. The code stays for other clips.
     */
    const val ENABLED_BY_DEFAULT = false

    /** Asks for [fps] while playing: the Surface when there is one (API 30+), else the window's mode. */
    fun begin(activity: Activity?, surface: Surface?, fps: Double) {
        if (fps < 1.0) return
        // OFF by default (owner decision 2026-11-25: the device A/B showed no benefit, see
        // ENABLED_BY_DEFAULT). Debug builds can still switch it for an experiment:
        //   run-as <pkg> touch files/debug-display-rate      -> on
        //   run-as <pkg> touch files/debug-no-display-rate   -> off (wins)
        val enableFlag = BuildConfig.DEBUG && activity != null &&
            java.io.File(activity.filesDir, "debug-display-rate").exists()
        val disableFlag = BuildConfig.DEBUG && activity != null &&
            java.io.File(activity.filesDir, "debug-no-display-rate").exists()
        if (!DisplayRate.shouldRequest(ENABLED_BY_DEFAULT, enableFlag, disableFlag)) {
            if (BuildConfig.DEBUG) Log.i(TAG, "display-rate not requested (default off, flags enable=$enableFlag disable=$disableFlag)")
            return
        }
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
