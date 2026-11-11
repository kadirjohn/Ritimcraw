package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ThumbnailStats tests (owner request 2026-11-11 section 1): the duration
 * from the RECORDED first/last pts — the same rule the editor's transport
 * uses (last - first), 0 for a single frame or missing pts (-1), never a
 * faked value.
 */
class ThumbnailStatsTest {

    @Test
    fun duration_is_last_minus_first_pts() {
        // 12.5 s of recorded timeline.
        assertEquals(12_500_000L, ThumbnailStats.durationUs(100L, 12_500_100L))
    }

    @Test
    fun duration_zero_for_a_single_frame() {
        // One frame: no span (the row hides the duration part).
        assertEquals(0L, ThumbnailStats.durationUs(100L, 100L))
    }

    @Test
    fun duration_zero_when_pts_missing() {
        // framePtsUs reports -1 when unavailable: never a negative or
        // garbage duration.
        assertEquals(0L, ThumbnailStats.durationUs(-1L, 12_500_100L))
        assertEquals(0L, ThumbnailStats.durationUs(100L, -1L))
        assertEquals(0L, ThumbnailStats.durationUs(-1L, -1L))
    }

    @Test
    fun duration_zero_for_non_increasing_pts() {
        // A corrupt timeline (last before first) is 0, never negative.
        assertEquals(0L, ThumbnailStats.durationUs(200L, 100L))
    }
}
