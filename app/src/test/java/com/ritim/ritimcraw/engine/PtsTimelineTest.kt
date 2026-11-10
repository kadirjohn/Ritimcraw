package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playback math over real MCRAW timestamps (owner request 2026-11-10 §1):
 * pts->frame picking, seek fraction mapping, and next-frame delay. Pure
 * JVM tests — no native lib, no coroutines.
 */
class PtsTimelineTest {

    // 24 fps grid: pts in microseconds, frame i at i * 41666us.
    private val fps24 = LongArray(240) { it * 41_666L }

    // ---- frameAt: closest-frame picking ----

    @Test
    fun frame_at_picks_exact_match() {
        assertEquals(5, PtsTimeline.frameAt(fps24, 5 * 41_666L))
    }

    @Test
    fun frame_at_picks_closest_frame_mid_interval() {
        // Just past the midpoint between frames 5 and 6 -> frame 6 wins.
        assertEquals(6, PtsTimeline.frameAt(fps24, 5 * 41_666L + 20_834L))
        // Just before the midpoint -> frame 5 wins.
        assertEquals(5, PtsTimeline.frameAt(fps24, 5 * 41_666L + 20_832L))
    }

    @Test
    fun frame_at_ties_resolve_to_earlier_frame() {
        // Exactly halfway between frames 0 and 1: distance equal, earlier wins.
        assertEquals(0, PtsTimeline.frameAt(longArrayOf(0L, 41_666L), 20_833L))
    }

    @Test
    fun frame_at_before_first_frame_picks_first() {
        assertEquals(0, PtsTimeline.frameAt(fps24, -1_000_000L))
    }

    @Test
    fun frame_at_after_last_frame_picks_last() {
        assertEquals(fps24.size - 1, PtsTimeline.frameAt(fps24, Long.MAX_VALUE / 2))
    }

    @Test
    fun frame_at_empty_timeline_returns_minus_one() {
        assertEquals(-1, PtsTimeline.frameAt(LongArray(0), 0L))
    }

    // ---- elapsedAt: wall-clock offset onto the recorded timeline ----

    @Test
    fun elapsed_at_maps_wall_clock_onto_recorded_timeline() {
        // Playback started at frame 10; 1s of wall time later the timeline
        // cursor sits at frame 10's pts + 1s.
        val expected = fps24[10] + 1_000_000L
        assertEquals(expected, PtsTimeline.elapsedAt(fps24, 10, 1_000_000L))
    }

    @Test
    fun elapsed_at_clamps_out_of_range_start() {
        assertEquals(fps24.last(), PtsTimeline.elapsedAt(fps24, 999, 0L))
        assertEquals(fps24.first(), PtsTimeline.elapsedAt(fps24, -5, 0L))
    }

    // ---- totalDurationUs / positionUs: transport readout ----

    @Test
    fun total_duration_is_last_minus_first() {
        assertEquals(41_666L, PtsTimeline.totalDurationUs(longArrayOf(0L, 41_666L)))
    }

    @Test
    fun total_duration_single_frame_is_zero() {
        assertEquals(0L, PtsTimeline.totalDurationUs(longArrayOf(123L)))
        assertEquals(0L, PtsTimeline.totalDurationUs(LongArray(0)))
    }

    @Test
    fun position_us_is_relative_to_first_frame() {
        assertEquals(2 * 41_666L, PtsTimeline.positionUs(fps24, 2))
    }

    @Test
    fun position_us_clamps_to_total() {
        assertEquals(239 * 41_666L, PtsTimeline.positionUs(fps24, 10_000))
        assertEquals(0L, PtsTimeline.positionUs(fps24, -1))
    }

    // ---- frameForFraction / fractionForFrame: seek mapping ----

    @Test
    fun seek_fraction_maps_onto_frame_grid() {
        assertEquals(0, PtsTimeline.frameForFraction(fps24, 0f))
        assertEquals(fps24.size - 1, PtsTimeline.frameForFraction(fps24, 1f))
        // Half of a 240-frame clip -> frame 119.5 -> rounds to 120.
        assertEquals(120, PtsTimeline.frameForFraction(fps24, 0.5f))
    }

    @Test
    fun seek_fraction_round_trips_with_fraction_for_frame() {
        for (i in listOf(0, 1, 60, 119, 120, 238, 239)) {
            val f = PtsTimeline.fractionForFrame(fps24, i)
            assertEquals(i, PtsTimeline.frameForFraction(fps24, f))
        }
    }

    @Test
    fun seek_fraction_clamps_out_of_range() {
        assertEquals(0, PtsTimeline.frameForFraction(fps24, -0.5f))
        assertEquals(fps24.size - 1, PtsTimeline.frameForFraction(fps24, 1.5f))
    }

    @Test
    fun fraction_for_frame_edges() {
        assertEquals(0f, PtsTimeline.fractionForFrame(fps24, 0), 0f)
        assertEquals(1f, PtsTimeline.fractionForFrame(fps24, fps24.size - 1), 0f)
        // Single frame / empty: slider parks at 0.
        assertEquals(0f, PtsTimeline.fractionForFrame(longArrayOf(7L), 0), 0f)
        assertEquals(0f, PtsTimeline.fractionForFrame(LongArray(0), 3), 0f)
    }

    @Test
    fun seek_on_empty_timeline_returns_minus_one() {
        assertEquals(-1, PtsTimeline.frameForFraction(LongArray(0), 0.5f))
    }

    // ---- delayUntilNextUs: pacing ----

    @Test
    fun delay_until_next_is_frame_gap_minus_elapsed() {
        // At frame 5 with the cursor exactly on its pts: next frame due in
        // one frame gap.
        assertEquals(41_666L, PtsTimeline.delayUntilNextUs(fps24, 5, fps24[5]))
    }

    @Test
    fun delay_until_next_negative_when_next_already_late() {
        // Cursor 2 frame gaps ahead: the next frame is late (drop-late:
        // play immediately, never a backlog).
        assertEquals(-41_666L, PtsTimeline.delayUntilNextUs(fps24, 5, fps24[5] + 2 * 41_666L))
    }

    @Test
    fun delay_until_next_at_clip_end_is_minus_one() {
        assertEquals(-1L, PtsTimeline.delayUntilNextUs(fps24, fps24.size - 1, fps24.last()))
        assertEquals(-1L, PtsTimeline.delayUntilNextUs(LongArray(0), 0, 0L))
    }
}
