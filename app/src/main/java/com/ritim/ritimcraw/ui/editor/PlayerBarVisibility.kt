package com.ritim.ritimcraw.ui.editor

/**
 * Pure state machine for the editor's auto-hiding media player bar (owner
 * request 2026-11-26). No Android, no clock of its own: every call takes
 * the current time in ms so JVM tests drive it deterministically.
 *
 * Rules:
 * - setting OFF: the bar is always visible, taps never toggle anything.
 * - setting ON + PAUSED: always visible (scrubbing needs it).
 * - setting ON + PLAYING: visible for [hideDelayMs] after the last
 *   interaction (play start, video tap, any touch on the bar), then hidden.
 * - a video tap while PLAYING shows the bar and restarts the timer; it never
 *   pauses. A second tap within [doubleTapMs] is a double tap: the result
 *   asks the caller to toggle play/pause (and the bar shows briefly).
 */
class PlayerBarVisibility(
    private val hideDelayMs: Long = DEFAULT_HIDE_DELAY_MS,
    private val doubleTapMs: Long = DEFAULT_DOUBLE_TAP_MS,
) {
    /** What the caller must do after a video tap. */
    enum class TapResult { NONE, TOGGLE_PLAYBACK }

    private var autoHide = false
    private var playing = false
    private var shown = true
    private var lastInteractionMs = 0L
    private var lastTapMs: Long? = null

    /** True when the bar must be composed/shown right now. */
    val visible: Boolean get() = !autoHide || !playing || shown

    fun setAutoHide(enabled: Boolean, nowMs: Long) {
        autoHide = enabled
        lastTapMs = null
        show(nowMs)
    }

    /** Playback started/stopped. Either way the bar shows and the timer restarts. */
    fun setPlaying(isPlaying: Boolean, nowMs: Long) {
        playing = isPlaying
        show(nowMs)
    }

    /** A touch on the bar itself (slider drag, buttons): keep it visible, restart the timer. */
    fun onBarInteraction(nowMs: Long) = show(nowMs)

    /** A clean single tap on the video surface. */
    fun onVideoTap(nowMs: Long): TapResult {
        if (!autoHide) return TapResult.NONE
        val previous = lastTapMs
        if (previous != null && nowMs - previous <= doubleTapMs) {
            lastTapMs = null
            return TapResult.TOGGLE_PLAYBACK
        }
        lastTapMs = nowMs
        show(nowMs)
        return TapResult.NONE
    }

    /** Milliseconds until the bar should hide, or null when no timer is pending. */
    fun msUntilHide(nowMs: Long): Long? {
        if (!autoHide || !playing || !shown) return null
        return (lastInteractionMs + hideDelayMs - nowMs).coerceAtLeast(0L)
    }

    /** Timer expiry: hides when due. Returns true if the visibility changed. */
    fun tick(nowMs: Long): Boolean {
        val due = msUntilHide(nowMs) ?: return false
        if (due > 0L) return false
        shown = false
        return true
    }

    private fun show(nowMs: Long) {
        shown = true
        lastInteractionMs = nowMs
    }

    companion object {
        const val DEFAULT_HIDE_DELAY_MS = 3000L
        const val DEFAULT_DOUBLE_TAP_MS = 300L
    }
}
