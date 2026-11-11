package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.PtsTimeline
import com.ritim.ritimcraw.util.Format
import kotlin.math.roundToInt

/**
 * Pure precision frame-scrub decisions (owner request 2026-11-11 section 2),
 * JVM-testable without Compose: a press-and-hold on the transport's seek bar
 * enters PRECISION FRAME MODE — the bar stops mapping drag distance onto
 * TIME (normal mode: ~1 cm of drag seeks ~3 s) and instead steps the
 * position FRAME BY FRAME, so the user can park on an exact frame
 * (the owner's example: ~1 cm of drag ≈ 30 frames). Releasing the finger
 * keeps the selected frame (pause behavior; the screen then applies the
 * existing progressive full-res settle).
 *
 * The transport is FRAME-INDEXED (positionFrame + the clip's real ptsUs
 * array, PtsTimeline), so precision stepping never needs a frame rate: a
 * step is exactly one index. The frame rate only feeds the READOUT's
 * timecode (Format.formatTimecode falls back to 24 fps when fps <= 0, so a
 * metadata-less clip degrades gracefully); on a variable-frame-rate clip the
 * timecode uses the frame's REAL pts (exact), only the :FF field is nominal
 * at the average rate.
 *
 * The screen owns every state write; this object only decides. The gesture
 * wiring (pointer input) lives in the transport composable, the renders go
 * through the screen's existing render gate (newest-wins pending slot +
 * monotonic publication), so precision scrubbing cannot corrupt the
 * drag-tier/settle pipeline — any newer request (a seek, a slider settle)
 * supersedes a scrub render by sequence.
 */
object PrecisionScrub {

    /**
     * Drag sensitivity: how many frames one centimeter of physical drag
     * steps (the owner's example: ~1 cm ≈ 30 frames). Stated in dp —
     * Android defines 160 dp = 1 inch = 2.54 cm, so 1 cm ≈ 62.992 dp and
     * one frame ≈ 2.1 dp of drag at 30 frames/cm.
     */
    const val FRAMES_PER_CM = 30f

    /** Android's density-independent definition of one centimeter (160 dp = 1 in = 2.54 cm). */
    const val DP_PER_CM = 160f / 2.54f

    /** Drag distance (dp) that steps one frame — the reciprocal sensitivity. */
    val DP_PER_FRAME: Float = DP_PER_CM / FRAMES_PER_CM

    /**
     * What a long-press on the seek bar does. Pure: the screen branches on
     * this and owns the actual state writes.
     */
    sealed interface Engage {

        /**
         * Pause the wall clock (playback was running) and enter precision
         * mode at the current frame — the same pause entry the transport's
         * pause button uses, so the progressive full-res settle applies.
         */
        data object PauseAndEnter : Engage

        /** Enter precision mode; playback was already paused. */
        data object Enter : Engage

        /**
         * No precision mode: no timeline to step on (transport disabled,
         * single-frame clip) or the render path unavailable.
         */
        data object Reject : Engage
    }

    /**
     * True when a press-and-hold may enter precision mode: a real timeline
     * to step on (enabled transport with at least 2 frames) and the render
     * path runnable. Works PAUSED and PLAYING — during playback the entry
     * pauses the wall clock first ([Engage.PauseAndEnter]), exactly like
     * the before/after hold.
     */
    fun canEnter(enabled: Boolean, frameCount: Int, canRender: Boolean): Boolean =
        enabled && frameCount > 1 && canRender

    /**
     * The transition for a long-press on the seek bar given the current
     * state: rejected (no timeline / no engine), enter-while-playing (pause
     * the wall clock, then engage), or enter-while-paused (just engage).
     */
    fun onEngage(
        isPlaying: Boolean,
        enabled: Boolean,
        frameCount: Int,
        canRender: Boolean,
    ): Engage = when {
        !canEnter(enabled, frameCount, canRender) -> Engage.Reject
        isPlaying -> Engage.PauseAndEnter
        else -> Engage.Enter
    }

    /**
     * The frame a drag of [draggedDp] dp (accumulated since the gesture
     * started, signed — positive drags move forward) steps to, starting
     * from [startFrame]. Quantized to WHOLE frames (the position can never
     * land between frames): the accumulated dp maps onto the frame ladder
     * by round-to-nearest, so the first step commits at half a
     * DP_PER_FRAME of drag and sub-half wiggles hold the frame.
     * Clamped to the timeline's ends.
     */
    fun frameForDrag(startFrame: Int, draggedDp: Float, frameCount: Int): Int {
        if (frameCount <= 1) return startFrame.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        // Accumulate in frames (Float) then quantize once: repeated small
        // drags across events sum to the same target as one big drag — the
        // caller passes the TOTAL dp since gesture start, never per-event
        // deltas, so no drift can accumulate.
        val target = startFrame + (draggedDp / DP_PER_FRAME).roundToInt()
        return target.coerceIn(0, frameCount - 1)
    }

    /**
     * The TIMECODE half of the readout shown while precision mode is active
     * (the composable joins it with the 1-BASED frame number through the
     * localized string, the frame picker's precedent — never a hardcoded
     * template). The timecode uses the frame's REAL pts (exact on VFR
     * clips); [fps] only shapes the :FF field, rounded to the nearest
     * integer rate (29.97 plays as a 30 field, the closest nominal), and a
     * non-positive fps falls back to Format's 24 (a metadata-less clip
     * still shows a stable readout).
     */
    fun readoutTimecode(frameIndex: Int, ptsUs: LongArray, fps: Float): String {
        val clamped = frameIndex.coerceIn(0, (ptsUs.size - 1).coerceAtLeast(0))
        val positionUs = PtsTimeline.positionUs(ptsUs, clamped)
        val safeFps = if (fps <= 0f) Format.DEFAULT_FPS else fps.roundToInt()
        return Format.formatTimecode(positionUs, safeFps)
    }

    /**
     * What a precision-mode RELEASE does. The owner's spec: release KEEPS
     * the selected frame — playback stays paused on it (pause behavior),
     * and the screen applies the existing progressive full-res settle
     * (fast 1280 px frame first, then the source-resolution follow-up).
     * One decision because there is nothing to restore: unlike the
     * before/after hold, precision mode never swaps the grade or resumes
     * playback — the exit is simply "end the mode, settle the frame".
     */
    fun onRelease(): Release = Release.KeepFrameAndSettle

    /** Release decision: stay paused on the selected frame, settle it to full res. */
    sealed interface Release {
        data object KeepFrameAndSettle : Release
    }
}
