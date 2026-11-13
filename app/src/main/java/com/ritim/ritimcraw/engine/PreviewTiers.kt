package com.ritim.ritimcraw.engine

/**
 * Preview resolution tiers (owner request 2026-11-10 section 1): the
 * paused/editor preview runs at the full preview tier; playback runs at a
 * coarser tier so the CPU reference pipeline keeps up with real-time
 * playback. The slider-drag tier lands with the interactive rendering
 * work. Constants, not settings: they are pipeline tuning, not user intent.
 */
object PreviewTiers {

    /** Long-edge cap for the paused/editor preview (full preview quality). */
    const val PAUSED_MAX_DIM = 1280

    /**
     * WHAT a render is FOR, not how big it is (choppy-preview fix
     * 2026-11-13): the class the request was made under, threaded from the
     * editor's request path through [RenderRequest] and the
     * [EditorPreviewSession] seam to the tier gates. The tier ladders
     * (SliderTiers, PreviewQuality) decide resolution; this decides
     * policy. Three classes cover every request the editor makes:
     *
     *  - [DRAG] — interactive renders while the finger is down: slider
     *    drag frames, seek scrub frames. Latency-bound (sub-frame-budget
     *    responsiveness); never pay the temporal merge.
     *  - [SETTLE] — everything parked or inspected: pause, clip end, drag
     *    settle, zoom settle, grade settle, refresh, precision scrub,
     *    before/after hold, first frame, the full-res follow-up. The ONLY
     *    class that may run temporal NR (and only at tier >=
     *    [PAUSED_MAX_DIM], via [temporalNrAppliesAt]).
     *  - [PLAYBACK] — the playback loop's per-frame renders. NEVER runs
     *    temporal NR, at ANY tier (see [temporalNrAppliesAt] for the
     *    measured cause).
     *
     * The default everywhere is [SETTLE]: it is the class of every request
     * that does not name one, which keeps pre-fix call sites (thumbnails,
     * metrics, the timeline screen) bit-identical — and honest, since those
     * renders are all parked-frame inspections.
     */
    enum class RenderClass { DRAG, SETTLE, PLAYBACK }

    /** Long-edge cap while playing (coarser; keeps the CPU develop realtime). */
    const val PLAYBACK_MAX_DIM = 720

    /**
     * No cap: the RECORDED source resolution (owner request 2026-11-11
     * section 2). Both engines already treat a non-positive maxDim as
     * pass-through — the native strideFor skips scaling and the encoded
     * [ArgbScale.fit] returns the frame unscaled — so 0 means "render the
     * frame at its own size". Used for the single paused/clip-end render,
     * never for playback or slider drags.
     */
    const val SOURCE_MAX_DIM = 0

    /**
     * Temporal NR render-class policy (DN-08 graceful degradation, fixed
     * 2026-11-13 to gate on the render CLASS, not just the tier size): the
     * temporal merge runs on the FULL-RES CFA before the develop tail, so
     * its cost does not shrink with maxDim. Measured 2026-11-11 on the 4K
     * fixture (261110_004208_VIDEO_24mm.mcraw, desktop -O2, strength 100,
     * both neighbors): proxy 0.8-2.2 ms + match 7.9-12.1 ms + merge
     * 76.7-93.1 ms ≈ 85-107 ms per frame — tier-INDEPENDENT, and far over
     * the ~66 ms/frame playback budget and unusable as a per-step drag
     * cost.
     *
     * THE BUG THIS GATE SHAPE FIXED (measured on the owner's device,
     * 2026-11-13, 37 s window at the HIGH preview-quality setting): the
     * old tier-only gate (maxDim >= PAUSED_MAX_DIM) silently turned
     * temporal NR ON for PLAYBACK renders whenever the playback tier
     * reached 1280 — which PreviewQuality.HIGH does (playbackMaxDim =
     * 1280). Result: 135 settle-class-rate renders, ~3.65 renders/sec
     * (~274 ms/frame) while the position advanced +8 frames per render —
     * visibly choppy playback. The tier ladder predates the HIGH quality
     * level; when HIGH made playback 1280-capable, the tier-size gate
     * could no longer tell a playback render from a settle render.
     *
     * Policy after the fix: only [RenderClass.SETTLE] renders may run the
     * merge, and only at tier >= [PAUSED_MAX_DIM] (today's settle
     * behavior, unchanged: 1280 pause/clip-end/zoom settle and any
     * full-res tier keep it; the drag tier and the 0-dim SOURCE follow-up
     * stay below the floor exactly as before). [RenderClass.PLAYBACK]
     * NEVER runs temporal NR at any tier — a skipped render shows the
     * pristine center, and the effect lands on the settle render when the
     * user pauses. [RenderClass.DRAG] never runs it (it already could not
     * at 480; a ZOOMED drag priced at the 1280 settle tier keeps the DRAG
     * class and stays honest — interactive renders never pay the merge).
     *
     * HONEST BUDGET NOTE (no auto-downscaling, the quality ladder is
     * untouched): with NR off, a 1280-tier playback render still pays the
     * stabilization warp (~5.3 ms measured at 1280x720) plus the develop
     * tail on top of decode — on some content that will not hold 30 fps
     * even after this fix. That is the tier's real cost, reported here
     * instead of papered over; the owner's re-run at HIGH quality is the
     * device proof, and dropping the tier is the user's call (BALANCED
     * plays at 720), not this gate's.
     *
     * Export (nativeExportFrame, full-res developCpu) is not gated here
     * and always applies temporal at full quality.
     */
    fun temporalNrAppliesAt(maxDim: Int, renderClass: RenderClass): Boolean =
        renderClass == RenderClass.SETTLE && maxDim >= PAUSED_MAX_DIM

    /**
     * Stabilization warp tier policy (gyro Phase 5, audit fix 2026-11-12):
     * the warp applies at EVERY preview tier — drag (480), playback (720),
     * paused (1280) and the [SOURCE_MAX_DIM] (0) full-res follow-up —
     * whenever the grade has stabilization on and a gyro log is attached.
     * The per-render cost is plan + warp, both scaling with the developed
     * buffer; measured 2026-11-12 on the 004150 fixture through the real
     * pipeline (test_warp's tier harness, desktop -O2):
     *   drag 480x270      plan 0.24 + warp 0.72  ≈ 0.96 ms/frame
     *   playback 640x360  plan 0.22 + warp 1.68  ≈ 1.90 ms/frame
     *   paused 1280x720   plan 0.18 + warp 5.12  ≈ 5.30 ms/frame
     *   source 3840x2160  plan 0.20 + warp 45.37  ≈ 45.6 ms/frame
     * The drag and playback budgets easily absorb ~1-2 ms (the drag tier
     * targets sub-50 ms and playback ~66 ms/frame), and the paused tier's
     * ~100 ms budget absorbs 5.3 ms.
     *
     * WHY EVERY TIER (the audit finding this reverses): the old gate
     * (maxDim >= PAUSED) skipped playback, drags AND the full-res
     * follow-up — so a paused clip showed a STABILIZED 1280 frame that was
     * then REPLACED by an UNSTABILIZED full-res frame (a visible jump at
     * the settle), and playback disagreed with the paused frame about
     * what stabilization does. Unlike temporal NR (whose full-res merge
     * cost is tier-INDEPENDENT at ~85-107 ms), the warp's cost shrinks
     * with the buffer, so showing the stabilized frame at every tier is
     * affordable AND it is the only honest preview: what the user sees at
     * every tier is what the export will produce (up to resolution).
     * Temporal NR keeps its own gate ([temporalNrAppliesAt]) — only the
     * stabilization gate changed. Export is NOT gated here: the export
     * path has its own on+path gate and always warps at full resolution.
     */
    fun stabilizationAppliesAt(maxDim: Int): Boolean = true
}
