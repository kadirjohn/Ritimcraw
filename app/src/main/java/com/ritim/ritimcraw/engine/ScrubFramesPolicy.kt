package com.ritim.ritimcraw.engine

/**
 * Pure scrub-frames wiring decisions (F2-C3, the [ScrubFrames] facade's
 * editor contract), JVM-testable without Compose or the native lib — the
 * SliderTiers/PrecisionScrub pattern: the screen owns every state write;
 * this object only decides. The facade's documented intended use:
 *
 * ```
 * when (val r = scrub.onScrub(index)) {
 *     is ScrubResult.Cached -> draw(r.pixels)      // small cached frame
 *     ScrubResult.Miss -> keep last picture        // never black
 * }
 * scrub.onRelease(index)                           // caller renders the FULL frame
 * ```
 *
 * WHAT THE WIRING ADDS AROUND THE FACADE (each a pure decision here):
 *
 * - ENGAGE (seek drag started): the cached-frame path may serve the drag
 *   only when the real pipeline is present (a session, a timeline with
 *   more than one frame) — the transport's own `enabled` gate. A Miss
 *   keeps today's behavior (the seek-drag render at the low drag tier),
 *   so the feature degrades to exactly the pre-wiring editor when any
 *   input is missing: engage decides whether to ASK the cache, never
 *   whether the drag works.
 *
 * - SERVE (a cache hit while dragging): [shouldServe] — a hit within the
 *   facade's [com.ritim.ritimcraw.engine.ScrubFrames]'s tolerance is
 *   served INSTEAD OF the drag-tier render. An exact or within-tolerance
 *   hit answers the finger immediately (the whole point: no decode wait);
 *   the serve must also be a FORWARD-or-same index only
 *   ([shouldServeForward]) never showing a frame BEHIND the requested
 *   one — a behind hit would rewind the picture under a forward drag
 *   (the nearest-match fallback the Klipp reference allows on its
 *   timeline, but the editor's seek bar maps monotonically, so a behind
 *   frame reads as a lie). Behind hits fall back to the render path.
 *
 * - SETTLE (drag ended / finger rests): the facade retargets and the
 *   screen renders the REAL frame through the existing settle pipeline
 *   (the drag-end event already emits exactly one full settle render —
 *   unchanged). No decision needed beyond the facade's [onRelease]; kept
 *   here as [settleAfterDrag] so the test pins the wiring contract: the
 *   scrub frame NEVER survives the drag (the parked frame's real render
 *   replaces it).
 *
 * - PLAYBACK: stands the scrub path down completely (the clip's decoder
 *   is the thing being played; Klipp pauses its scrub-frame builder for
 *   exactly this reason). [standsDown] is the pure form.
 */
object ScrubFramesPolicy {

    /**
     * The scrub cache's preview tier: the long-edge cap of the small
     * JPEG'd frames (the [ScrubFrames] facade's default 320, Klipp's
     * SIZE). Constant, not a setting: it is pipeline tuning (Same rule
     * as the SliderTiers tiers).
     */
    const val TIER_MAX_DIM = 320

    /** True when the cached-frame path may serve a drag started here. */
    fun engage(
        hasSession: Boolean,
        frameCount: Int,
    ): Boolean = hasSession && frameCount > 1

    /**
     * True when a cache hit (the index the facade actually served) should
     * REPLACE the drag-tier render for the requested index. Ties in
     * distance are facade-side (its `nearest`); this only vets the hit
     * the facade already chose: it must be FORWARD-or-same
     * ([shouldServeForward]) — a behind hit reads as a lie under the
     * monotonic seek bar, so the screen falls back to the real render.
     */
    fun shouldServe(
        requestedIndex: Int,
        servedIndex: Int,
    ): Boolean = shouldServeForward(requestedIndex, servedIndex)

    /**
     * True when [servedIndex] may show while [requestedIndex] was asked
     * for: same or forward only. The tolerance (how far forward) is the
     * facade's own [com.ritim.ritimcraw.engine.ScrubFrames] tolerance —
     * `nearest` never returns a hit outside it.
     */
    fun shouldServeForward(requestedIndex: Int, servedIndex: Int): Boolean =
        servedIndex >= requestedIndex

    /** Drag ended: the scrub frame never survives (the settle render replaces it). */
    fun settleAfterDrag(): Settle = Settle.RenderRealFrame

    /** Playback stands the scrub path down (the decoder is busy). */
    fun standsDown(isPlaying: Boolean): Boolean = isPlaying

    sealed interface Settle {
        /** The parked frame renders through the existing drag-end settle path. */
        data object RenderRealFrame : Settle
    }
}
