package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScrubFramesPolicy tests (F2-C3 wiring): the pure editor-side decisions
 * around the ScrubFrames facade — engage gating, the forward-or-same
 * serve rule, the drag-end settle and the playback stand-down. JVM-only;
 * the facade itself is covered by ScrubFrameCacheTest.
 */
class ScrubFramesPolicyTest {

    // ---- engage: only a real timeline may ask the cache ----

    @Test
    fun engage_requires_a_session() {
        assertFalse(ScrubFramesPolicy.engage(hasSession = false, frameCount = 300))
    }

    @Test
    fun engage_rejects_single_frame_clip() {
        // A still photo: no scrubbing to cache for.
        assertFalse(ScrubFramesPolicy.engage(hasSession = true, frameCount = 1))
    }

    @Test
    fun engage_accepts_a_real_timeline() {
        assertTrue(ScrubFramesPolicy.engage(hasSession = true, frameCount = 2))
        assertTrue(ScrubFramesPolicy.engage(hasSession = true, frameCount = 3000))
    }

    // ---- serve: forward-or-same only, the seek bar never rewinds ----

    @Test
    fun exact_hit_serves() {
        assertTrue(ScrubFramesPolicy.shouldServe(requestedIndex = 40, servedIndex = 40))
    }

    @Test
    fun forward_hit_within_tolerance_serves() {
        // The facade's nearest() already bounded the distance by its
        // tolerance; this only vets the direction.
        assertTrue(ScrubFramesPolicy.shouldServe(requestedIndex = 40, servedIndex = 43))
    }

    @Test
    fun behind_hit_never_serves() {
        // A behind hit would rewind the picture under a forward drag —
        // the monotonic seek bar must never show one (the fall-back to
        // the real render keeps the picture honest).
        assertFalse(ScrubFramesPolicy.shouldServe(requestedIndex = 40, servedIndex = 39))
        assertFalse(ScrubFramesPolicy.shouldServe(requestedIndex = 40, servedIndex = 37))
    }

    @Test
    fun boundary_indices() {
        // Frame 0: any valid hit is forward-or-same by definition.
        assertTrue(ScrubFramesPolicy.shouldServe(requestedIndex = 0, servedIndex = 0))
        // The last frame: a forward hit cannot exist, only exact serves.
        assertTrue(ScrubFramesPolicy.shouldServe(requestedIndex = 99, servedIndex = 99))
        assertFalse(ScrubFramesPolicy.shouldServe(requestedIndex = 99, servedIndex = 98))
    }

    // ---- settle + playback ----

    @Test
    fun drag_end_settles_the_real_frame() {
        // The wiring contract: the scrub frame NEVER survives the drag —
        // the park render's settle replaces it (the pre-existing drag-end
        // settle path, unchanged by the wiring).
        assertEquals(
            ScrubFramesPolicy.Settle.RenderRealFrame,
            ScrubFramesPolicy.settleAfterDrag(),
        )
    }

    @Test
    fun playback_stands_the_walk_down() {
        assertTrue(ScrubFramesPolicy.standsDown(isPlaying = true))
        assertFalse(ScrubFramesPolicy.standsDown(isPlaying = false))
    }

    // ---- the tier constant: the facade + the adapter must agree ----

    @Test
    fun tier_matches_the_facade_default() {
        // ScrubFrames' own default tier (320) is what the wiring builds
        // both the facade and the SessionScrubSource with — the cache key
        // carries tierMaxDim, so a mismatch would fill a tier the drag
        // never reads.
        assertEquals(320, ScrubFramesPolicy.TIER_MAX_DIM)
    }
}
