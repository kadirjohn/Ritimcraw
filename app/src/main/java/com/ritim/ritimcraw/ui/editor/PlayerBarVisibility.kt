package com.ritim.ritimcraw.ui.editor

/**
 * Pure state machine for the editor's auto-hiding media player bar (owner
 * request 2026-11-26). No Android, no clock of its own: every call takes
 * the current time in ms so JVM tests drive it deterministically.
 *
 * Rules:
 * - setting OFF: the bar is always visible, taps never toggle anything.
 * - setting ON: visible for [hideDelayMs] after the last interaction
 *   (play/pause change, video tap, any touch on the bar), then hidden, in
 *   EVERY playback state (playing, paused, stopped, at the end). While a
 *   finger is down on the bar the timer is held.
 * - a video tap TOGGLES the bar (owner request 2026-11-27): hidden bar
 *   shows, a VISIBLE bar hides immediately — the same tap that opens it
 *   closes it again, no waiting for the timeout. A second tap within
 *   [doubleTapMs] of the first is a double tap: the result asks the caller
 *   to toggle play/pause (and the bar shows briefly). The close consumes
 *   the double-tap window: a close-then-quick-tap reopens as a fresh
 *   single tap, never a playback toggle.
 */
class PlayerBarVisibility(
    private val hideDelayMs: Long = DEFAULT_HIDE_DELAY_MS,
    private val doubleTapMs: Long = DEFAULT_DOUBLE_TAP_MS,
) {
    /** What the caller must do after a video tap. */
    enum class TapResult { NONE, TOGGLE_PLAYBACK, HIDE }

    private var autoHide = false
    private var shown = true
    private var barPressed = false
    private var lastInteractionMs = 0L
    private var lastTapMs: Long? = null

    /** True when the bar must be composed/shown right now. */
    val visible: Boolean get() = !autoHide || shown

    fun setAutoHide(enabled: Boolean, nowMs: Long) {
        autoHide = enabled
        lastTapMs = null
        show(nowMs)
    }

    /** Playback started/stopped. Either way the bar shows and the timer restarts. */
    fun setPlaying(@Suppress("UNUSED_PARAMETER") isPlaying: Boolean, nowMs: Long) {
        show(nowMs)
    }

    /** A touch on the bar itself (slider drag, buttons): keep it visible, restart the timer. */
    fun onBarInteraction(nowMs: Long) = show(nowMs)

    /** Finger down/up on the bar. Held: never hides; release restarts the timer. */
    fun setBarPressed(pressed: Boolean, nowMs: Long) {
        barPressed = pressed
        show(nowMs)
    }

    /** A clean single tap on the video surface. */
    fun onVideoTap(nowMs: Long): TapResult {
        if (!autoHide) return TapResult.NONE
        val previous = lastTapMs
        if (previous != null && nowMs - previous <= doubleTapMs) {
            lastTapMs = null
            return TapResult.TOGGLE_PLAYBACK
        }
        // A tap on a VISIBLE bar closes it immediately (the toggle's close
        // half): the pending hide timer dies with it (msUntilHide reads
        // null once hidden). The close also consumes the double-tap
        // window, so a quick reopen tap is a fresh single tap. A finger
        // held on the bar keeps it up: the video tap can never rip the
        // bar out from under a live press.
        if (shown && !barPressed) {
            lastTapMs = null
            shown = false
            return TapResult.HIDE
        }
        lastTapMs = nowMs
        show(nowMs)
        return TapResult.NONE
    }

    /** Milliseconds until the bar should hide, or null when no timer is pending. */
    fun msUntilHide(nowMs: Long): Long? {
        if (!autoHide || !shown || barPressed) return null
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
