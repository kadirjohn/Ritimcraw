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
     * Stabilization warp tier policy (gyro Phase 5): the CPU bilinear warp
     * runs on the DEVELOPED thumbnail, so its cost scales with maxDim.
     * Measured 2026-11-11 (commit b's warpFrame doctest, desktop -O2):
     * 5.01187 ms/frame at 1280x720 — comfortably inside the paused tier's
     * budget and the ~15 ms/frame playback budget, but the warp is only
     * MEANINGFUL at the settle/paused tier: during playback and slider
     * drags the editor shows the unstabilized frame and the effect lands
     * on the settle render (the temporal NR precedent — one policy, one
     * place). [SOURCE_MAX_DIM] (0) FAILS the gate, exactly like temporal
     * NR: the single paused follow-up render at the recorded resolution
     * skips the warp too. Export is NOT gated here (the temporal NR
     * precedent): nativeExportFrame always applies the warp at full
     * resolution when the grade has stabilization on and a gyro log is
     * attached — that is where the full-quality stabilized frame comes
     * from.
     */
    fun stabilizationAppliesAt(maxDim: Int): Boolean = maxDim >= PAUSED_MAX_DIM
}
