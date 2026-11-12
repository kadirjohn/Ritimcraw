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
     * Temporal NR tier policy (DN-08 graceful degradation): the temporal
     * merge runs on the FULL-RES CFA before the develop tail, so its cost
     * does not shrink with maxDim. Measured 2026-11-11 on the 4K fixture
     * (261110_004208_VIDEO_24mm.mcraw, desktop -O2, strength 100, both
     * neighbors): proxy 0.8-2.2 ms + match 7.9-12.1 ms + merge 76.7-93.1 ms
     * ≈ 85-107 ms per frame — far over the ~66 ms/frame playback budget
     * and unusable as a per-step drag cost. Policy: the settle/paused tier
     * and export apply temporal NR (a paused frame can afford ~100 ms);
     * the drag (480) and playback (720) tiers skip it — a skipped render
     * shows the pristine center, and the effect lands on the settle render.
     * Export (nativeExportFrame, full-res developCpu) is not gated here and
     * always applies temporal at full quality.
     */
    fun temporalNrAppliesAt(maxDim: Int): Boolean = maxDim >= PAUSED_MAX_DIM

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
