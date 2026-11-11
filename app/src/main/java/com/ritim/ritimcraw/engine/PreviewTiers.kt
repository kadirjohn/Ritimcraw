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
}
