package com.ritim.ritimcraw.engine

/**
 * Pure slider-drag tier state machine (owner request 2026-11-10 section 1:
 * interactive low-latency slider rendering). JVM-testable without Compose:
 * the screen feeds it drag events, it decides WHICH resolution tier each
 * render runs at.
 *
 * Contract:
 * - While a drag is active every render uses the LOW tier — cheap enough
 *   to keep up with the finger (and the render gate coalesces rapid offers
 *   into the newest pending one).
 * - When the drag ends EXACTLY ONE full-quality settle render is emitted;
 *   the coarse drag frame is replaced by the full preview.
 * - Rapid drags cannot queue settles: a new drag start emits a drag-tier
 *   render (the owed settle, if it never ran, was replaced in the gate by
 *   the newer request — newest-wins).
 * - A grade edit outside a drag renders at the full tier (the debounced
 *   editor render); during a drag at the low tier.
 * - Playback stands the drag tier down: play renders at the playback tier.
 * - While the preview is ZOOMED (owner request 2026-11-12 section 3)
 *   every render this object prices stays at the full settle tier
 *   instead of the drag tier: a zoomed preview magnifies the rendered
 *   bitmap, so the 480px drag tier reads soft under the transform. The
 *   screen feeds the flag (isZoomed on the settled pinch transform);
 *   back at 1x the normal policy returns.
 *
 * Tier table (long-edge caps; who renders what):
 *   slider drag (1x)          -> scrubMaxDim (default 480)
 *   slider settle (1x)        -> SETTLE_MAX_DIM (1280)
 *   ANY SliderTiers render
 *   while zoomed              -> SETTLE_MAX_DIM (1280)
 *   playback                  -> PLAYBACK_MAX_DIM (720, outside this object)
 *   pause / clip end          -> PAUSED_MAX_DIM, then SOURCE follow-up
 *   pinch zoom settle         -> PAUSED_MAX_DIM, then SOURCE follow-up
 *                                (the pause path exactly; [owesZoomSettle]
 *                                gates which gestures owe it)
 */
object SliderTiers {

    /** Long-edge cap while the finger is down (drag tier). */
    const val DRAG_MAX_DIM = 480

    /** Long-edge cap for the full-quality settle render after the drag. */
    const val SETTLE_MAX_DIM = PreviewTiers.PAUSED_MAX_DIM

    /** The events the screen can observe, in the order they can occur. */
    sealed interface Event {
        /** The finger touched the slider (or a programmatic drag began). */
        data object DragStarted : Event

        /** The drag ended (finger up / onValueChangeFinished). */
        data object DragEnded : Event

        /** Playback started (drag tier + settle stand down; play renders). */
        data object PlaybackStarted : Event

        /** A grade edit happened (debounced render; tier depends on drag). */
        data object GradeEdited : Event
    }

    /** What to render for an event, at which tier. */
    sealed interface Decision {
        /** Render at the low drag tier (low latency). */
        data class RenderAtDragTier(val maxDim: Int = DRAG_MAX_DIM) : Decision

        /** Render at the full settled tier (the one settle render). */
        data class RenderAtSettleTier(val maxDim: Int = SETTLE_MAX_DIM) : Decision

        /** No render for this event (playback owns the pipeline now). */
        data object NoRender : Decision
    }

    /** One step's outcome: what to render + the new drag state. */
    data class Transition(
        val decision: Decision,
        val dragActive: Boolean,
    )

    /**
     * The tier for a render request given the drag state: drag tier while
     * the finger is down, full tier otherwise. [scrubMaxDim] overrides the
     * drag-tier cap (owner request 2026-11-11b section 3: the user picks
     * the scrub resolution); the default is [DRAG_MAX_DIM], which keeps the
     * no-preference behavior bit-identical to the pre-picker tiers.
     *
     * [zoomed] (owner request 2026-11-12 section 3): while the preview is
     * zoomed every render this prices stays at the settle tier — the
     * magnified bitmap would read soft at the drag tier. It dominates
     * [dragActive] (a mid-drag seek under zoom is still settle-tier) but
     * never the tier of a render OUTSIDE this pricing (pause, playback
     * and the zoom settle itself keep their own tiers). Default false
     * keeps every existing call site bit-identical.
     */
    fun tierFor(
        dragActive: Boolean,
        scrubMaxDim: Int = DRAG_MAX_DIM,
        zoomed: Boolean = false,
    ): Int =
        if (zoomed || !dragActive) SETTLE_MAX_DIM else scrubMaxDim

    /**
     * Transition for one event given whether a drag is active. Pure: the
     * screen owns the boolean and feeds it back as the next call's state.
     * [scrubMaxDim] overrides the drag-tier cap of any drag-tier decision
     * (the user's preview-quality choice); the default keeps the
     * no-preference behavior bit-identical. [zoomed] substitutes the
     * settle tier for every drag-tier decision ([tierFor]'s zoom rule).
     */
    fun onEvent(
        event: Event,
        dragActive: Boolean,
        scrubMaxDim: Int = DRAG_MAX_DIM,
        zoomed: Boolean = false,
    ): Transition {
        val dragTier = if (zoomed) SETTLE_MAX_DIM else scrubMaxDim
        return when (event) {
            Event.DragStarted -> Transition(
                Decision.RenderAtDragTier(dragTier), dragActive = true,
            )
            Event.DragEnded -> Transition(Decision.RenderAtSettleTier(), dragActive = false)
            Event.PlaybackStarted -> Transition(Decision.NoRender, dragActive = false)
            Event.GradeEdited -> Transition(
                if (dragActive) {
                    Decision.RenderAtDragTier(dragTier)
                } else {
                    Decision.RenderAtSettleTier()
                },
                dragActive,
            )
        }
    }

    /**
     * Whether a finished pinch gesture owes the full-quality settle
     * render (owner request 2026-11-12 section 3): ONLY a zoom-CHANGING
     * pinch does — a pan-only gesture (one finger, no zoom change) never
     * re-renders, and neither does a pinch that never crossed the slop
     * gate (a tap with two fingers jittering under slop). Pure: the
     * gesture handler owns the flag and asks on gesture end.
     */
    fun owesZoomSettle(pinchZooming: Boolean, zoomed: Boolean): Boolean =
        pinchZooming && zoomed
}
