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
 * - a video tap NEVER hides the bar directly (owner device report
 *   2026-11-27: the instant close ate the double tap — the bar flashed
 *   closed-and-open instead of toggling playback). A tap on a VISIBLE bar
 *   arms a DELAYED close: the bar stays up through the double-tap window
 *   [doubleTapMs] so a quick second tap lands as the pair —
 *   TOGGLE_PLAYBACK, bar stays, full timer. If no second tap arrives, the
 *   timer hides the bar when the window expires: the close is always the
 *   tick's job, and [msUntilHide] returns that shortened deadline. A tap
 *   on a HIDDEN bar shows it with the full [hideDelayMs] timer.
 * - the window dies with the pair: a toggle never chains onto the next
 *   tap, and the armed close never leaks its window past the expiry — a
 *   reopen after the close is a fresh single tap, never a playback
 *   toggle. Any touch on the bar cancels an armed close (a finger on the
 *   bar means "keep it up").
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

    /**
     * Deadline (ms) an armed close is waiting for, or null. Set when a tap
     * lands on the visible bar: the hide itself waits so a second tap
     * inside the double-tap window can still pair as TOGGLE_PLAYBACK.
     */
    private var closeDeadlineMs: Long? = null

    /** True when the bar must be composed/shown right now. */
    val visible: Boolean get() = !autoHide || shown

    fun setAutoHide(enabled: Boolean, nowMs: Long) {
        autoHide = enabled
        lastTapMs = null
        show(nowMs)
    }

    /**
     * Arms the close: the hide lands at the double-tap deadline, not now.
     * The tap window stays OPEN through the wait — the second tap of the
     * pair must land as TOGGLE_PLAYBACK (owner device report 2026-11-27:
     * the old instant close consumed the window and ate the double tap).
     */
    private fun armClose(nowMs: Long) {
        lastTapMs = nowMs
        closeDeadlineMs = nowMs + doubleTapMs
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
            // The pair: playback toggle. The armed close (if any) dies —
            // the bar stays up with a full fresh timer.
            lastTapMs = null
            closeDeadlineMs = null
            show(nowMs)
            return TapResult.TOGGLE_PLAYBACK
        }
        // A tap on a VISIBLE bar does NOT hide it outright (owner device
        // report 2026-11-27: the instant close ate the double tap). It
        // arms a delayed close at the double-tap deadline: the bar stays
        // visible through the window so a quick second tap pairs above;
        // if it never comes, the tick hides the bar at the deadline. A
        // finger held on the bar cancels the arm (the hold owns the bar).
        if (shown && !barPressed) {
            armClose(nowMs)
            return TapResult.HIDE
        }
        lastTapMs = nowMs
        show(nowMs)
        return TapResult.NONE
    }

    /**
     * Milliseconds until the bar should hide, or null when no timer is
     * pending. An armed close shortens the deadline to the double-tap
     * deadline — the caller's plain delay loop needs no new wiring.
     */
    fun msUntilHide(nowMs: Long): Long? {
        if (!autoHide || !shown || barPressed) return null
        val deadline = closeDeadlineMs ?: (lastInteractionMs + hideDelayMs)
        return (deadline - nowMs).coerceAtLeast(0L)
    }

    /** Timer expiry: hides when due. Returns true if the visibility changed. */
    fun tick(nowMs: Long): Boolean {
        val due = msUntilHide(nowMs) ?: return false
        if (due > 0L) return false
        shown = false
        // The armed close fired: its window is spent, a reopen tap is a
        // fresh single tap, never a playback toggle.
        closeDeadlineMs = null
        return true
    }

    private fun show(nowMs: Long) {
        shown = true
        lastInteractionMs = nowMs
        // Showing means keeping the bar up: any pending close dies with it
        // (a pair, a bar touch, playback, the setting flip).
        closeDeadlineMs = null
    }

    companion object {
        const val DEFAULT_HIDE_DELAY_MS = 3000L
        const val DEFAULT_DOUBLE_TAP_MS = 300L
    }
}
