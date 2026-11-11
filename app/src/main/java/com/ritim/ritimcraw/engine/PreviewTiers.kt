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
}
