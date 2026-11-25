package com.ritim.ritimcraw.ui.editor

/**
 * Counts what ONE panel animation window costs the preview pipeline
 * (B13 panel-jank diagnosis, owner report: the editor is janky while the
 * adjustment panel moves DOWN). The window is EXACTLY the motion window
 * the `freezeSurfaceSize` mechanism covers (ClipEditorScreen composes
 * `freezeSurfaceSize = panelDrag.isAnimationRunning`), so the counts and
 * the freeze share one definition of "during the panel animation":
 *
 *  - renders:  preview render requests offered while the window is open
 *    (every path funnels through the screen's requestRender — its own
 *    doc: "ONE point covers them all" — plus the pump's follow-up
 *    re-offer, the one documented render that does not pass through it);
 *  - resizes:  SurfaceView surfaceChanged callbacks that fired while the
 *    window is open. The freeze should hold these at ZERO —
 *    setFixedSize pins the buffers for the whole animation and ONE
 *    layout-sized surfaceChanged follows after the panel settles
 *    (PreviewSurface's window-surface contract). A nonzero count means
 *    the freeze failed and every resize reallocated the surface's
 *    buffers mid-animation: the downward-jank suspect in its own words.
 *
 * Pure class, NO Compose and NO Android imports (the [PlaybackTrace]
 * precedent): the clock is INJECTED, the summary line is RETURNED, and
 * the screen decides whether to log it — so every rule here is
 * JVM-testable headless and the wiring stays a handful of one-line
 * calls. The counts are plain ints on a non-state object: no
 * recomposition, no invalidation, no layout effect — the wiring is
 * debug-only anyway (every hook site reads RenderTrace.enabled =
 * BuildConfig.DEBUG, the audit-M0 gate), so in release the object is
 * never heard from.
 *
 * THE LINE (stable grep format, the ritim-trace family):
 *   panel anim from=EXPANDED to=COLLAPSED dur_ms=231 renders=4 resizes=0
 * One line per animation END — that is the throttle: a settle takes
 * [PanelSheet.SNAP_DURATION_MS]-scale time, so consecutive gestures
 * cannot flood logcat, and the counts read as a burst summary rather
 * than a per-frame firehose.
 */
class PanelAnimTrace {

    /** True between onAnimStart and its matching onAnimEnd. */
    private var open = false
    private var fromState: PanelState? = null
    private var openedAtMs = 0L
    private var renderRequests = 0
    private var surfaceResizes = 0

    /** The panel started animating from [state] at [nowMs]; the counts reset (a fresh burst). */
    fun onAnimStart(state: PanelState, nowMs: Long) {
        open = true
        fromState = state
        openedAtMs = nowMs
        renderRequests = 0
        surfaceResizes = 0
    }

    /**
     * A preview render request was offered. Counted only while the
     * window is open — the steady-state renders between animations are
     * none of this diagnosis's business and stay uncounted.
     */
    fun onRenderRequest() {
        if (open) renderRequests++
    }

    /**
     * The SurfaceView reported a surfaceChanged callback. Counted only
     * while the window is open; the post-settle resize lands AFTER the
     * window closes by design (the freeze releases with the animation),
     * so "resizes=0" in the line plus the per-event
     * `surfaceChanged WxH` ritim-trace line right AFTER it is the honest
     * full story.
     */
    fun onSurfaceResize() {
        if (open) surfaceResizes++
    }

    /**
     * The panel animation ended at [nowMs] settling on [state]. Closes
     * the window and returns the summary line — or null when no window
     * was open (the observer fires once with false at composition: a
     * line for a burst that never happened would be noise).
     */
    fun onAnimEnd(state: PanelState, nowMs: Long): String? {
        if (!open) return null
        open = false
        val from = fromState
        val durMs = (nowMs - openedAtMs).coerceAtLeast(0)
        return "panel anim from=$from to=$state dur_ms=$durMs " +
            "renders=$renderRequests resizes=$surfaceResizes"
    }
}
