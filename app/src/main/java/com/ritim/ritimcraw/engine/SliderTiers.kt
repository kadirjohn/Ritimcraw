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
     */
    fun tierFor(dragActive: Boolean, scrubMaxDim: Int = DRAG_MAX_DIM): Int =
        if (dragActive) scrubMaxDim else SETTLE_MAX_DIM

    /**
     * Transition for one event given whether a drag is active. Pure: the
     * screen owns the boolean and feeds it back as the next call's state.
     * [scrubMaxDim] overrides the drag-tier cap of any drag-tier decision
     * (the user's preview-quality choice); the default keeps the
     * no-preference behavior bit-identical.
     */
    fun onEvent(
        event: Event,
        dragActive: Boolean,
        scrubMaxDim: Int = DRAG_MAX_DIM,
    ): Transition = when (event) {
        Event.DragStarted -> Transition(
            Decision.RenderAtDragTier(scrubMaxDim), dragActive = true,
        )
        Event.DragEnded -> Transition(Decision.RenderAtSettleTier(), dragActive = false)
        Event.PlaybackStarted -> Transition(Decision.NoRender, dragActive = false)
        Event.GradeEdited -> Transition(
            if (dragActive) {
                Decision.RenderAtDragTier(scrubMaxDim)
            } else {
                Decision.RenderAtSettleTier()
            },
            dragActive,
        )
    }
}
