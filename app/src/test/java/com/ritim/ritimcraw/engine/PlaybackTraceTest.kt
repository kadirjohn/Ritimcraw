package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTraceTest {
    private val period = 33_333L

    @Test
    fun a_smooth_run_has_no_late_no_dropped_and_tight_gaps() {
        val t = PlaybackTrace(period)
        for (i in 0 until 10) {
            val due = i * period
            t.onPick(i, due + 1_000, due)
            t.onPublished(due + 8_000)
        }
        assertEquals(10, t.pickedCount)
        assertEquals(10, t.shown)
        assertEquals(0, t.lateCount)
        assertEquals(0, t.droppedCount)
        assertEquals(33.3, t.gapPercentileMs(50.0), 0.1)
        assertEquals(33.3, t.gapPercentileMs(95.0), 0.1)
    }

    @Test
    fun a_skipped_index_counts_as_dropped_and_a_slow_pick_as_late() {
        val t = PlaybackTrace(period)
        t.onPick(0, 0, 0)
        t.onPick(3, 100_000, 100_000) // frames 1 and 2 were skipped
        t.onPick(4, 200_000, 133_333) // due 133 ms, picked at 200 ms: 66 ms behind, more than a period
        assertEquals(2, t.droppedCount)
        assertEquals(1, t.lateCount)
        assertEquals(3, t.pickedCount)
    }

    @Test
    fun percentiles_use_nearest_rank_and_a_stutter_shows_in_p95() {
        val t = PlaybackTrace(period)
        var now = 0L
        t.onPublished(now)
        repeat(19) { now += 33_000; t.onPublished(now) }
        now += 150_000; t.onPublished(now) // one 150 ms hitch in 20 gaps
        assertEquals(33.0, t.gapPercentileMs(50.0), 0.01)
        assertEquals(33.0, t.gapPercentileMs(95.0), 0.01) // rank 19 of 20 is still a normal gap
        assertEquals(150.0, t.gapPercentileMs(100.0), 0.01)
    }

    @Test
    fun an_empty_run_is_all_zero_and_summary_is_one_stable_line() {
        val t = PlaybackTrace(period)
        assertEquals(0, t.shown)
        assertEquals(0.0, t.gapPercentileMs(95.0), 0.0)
        val s = t.summary("c1", 1280)
        assertTrue(s, s.startsWith("playback clip=c1 tier=1280 period=33.3ms picked=0 shown=0 late=0 dropped=0"))
        assertTrue(s, !s.contains('\n'))
    }
}
