package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameTiming / PlaybackTimingCounters tests (PLAY-13, 2026-11-23): CFR
 * clean, gaps with missing-frame estimate, duplicates/out-of-order, VFR
 * jitter, the <2-frame honesty rule, separate output counters and the
 * stutter-stage hint.
 */
class FrameTimingTest {

    private fun cfr(n: Int, stepUs: Long, start: Long = 0L) = LongArray(n) { start + it * stepUs }

    @Test
    fun clean_cfr_source_reports_no_issues() {
        val r = FrameTiming.analyze(cfr(31, 33_333L), nominalFps = 30.0)
        assertEquals(31, r.frameCount)
        assertEquals(30 * 33_333L, r.spanUs)
        assertEquals(33_333L, r.medianIntervalUs)
        assertEquals(30.0, r.measuredFps!!, 0.01)
        assertEquals(30.0, r.medianFps!!, 0.01)
        assertEquals(0, r.gapCount)
        assertEquals(0, r.irregularCount)
        assertEquals(0, r.nonIncreasingCount)
        assertFalse(r.isVfr)
        assertTrue(r.summary().startsWith("source frames=31 span=999990us fps=30.000 nominal=30.000"))
    }

    @Test
    fun gaps_count_missing_frames() {
        // 24 fps with one 1-frame hole and one 3-frame hole.
        val step = 41_667L
        val pts = longArrayOf(0, step, 3 * step, 4 * step, 8 * step, 9 * step)
        val r = FrameTiming.analyze(pts)
        assertEquals(2, r.gapCount)
        assertEquals(1 + 3, r.missingFrames)
        assertEquals(step, r.medianIntervalUs)
        assertEquals(4 * step, r.maxIntervalUs)
    }

    @Test
    fun duplicate_and_backwards_pts_are_counted_separately() {
        val pts = longArrayOf(0, 33_333, 33_333, 66_666, 50_000, 100_000)
        val r = FrameTiming.analyze(pts)
        assertEquals(2, r.nonIncreasingCount)
    }

    @Test
    fun vfr_jitter_is_irregular_not_gaps() {
        // Alternating 30/40 ms steps (a phone VFR pattern): median 35 ms,
        // every step deviates ~14 % -> regular; widen one to 25 ms -> irregular.
        val pts = longArrayOf(0, 30_000, 70_000, 100_000, 140_000, 165_000, 205_000)
        val r = FrameTiming.analyze(pts)
        assertEquals(0, r.gapCount)
        assertEquals(1, r.irregularCount)
        assertTrue(r.isVfr)
        assertTrue(r.summary().endsWith(" VFR"))
    }

    @Test
    fun fewer_than_two_frames_has_no_rate() {
        val one = FrameTiming.analyze(longArrayOf(5_000))
        assertEquals(0L, one.spanUs)
        assertNull(one.measuredFps)
        assertNull(one.medianIntervalUs)
        val none = FrameTiming.analyze(LongArray(0), nominalFps = -1.0)
        assertNull(none.spanUs)
        assertNull(none.nominalFps) // a nonsense nominal is not echoed
    }

    @Test
    fun output_counters_are_separate_per_stage() {
        val c = PlaybackTimingCounters(lateThresholdUs = 10_000)
        c.onPresented(0, 0)
        c.onPresented(33_333, 25_000) // late
        c.onPresented(33_333, 0)      // repeated pts
        c.onDropped()
        c.onDecodeLatency(20_000)
        c.onDecodeLatency(40_000)
        c.onDecodeLatency(-5)         // ignored
        c.onAudioUnderrun()
        val s = c.snapshot()
        assertEquals(3, s.presented)
        assertEquals(1, s.late)
        assertEquals(1, s.dropped)
        assertEquals(1, s.audioUnderruns)
        assertEquals(30_000L, s.decodeMeanUs)
        assertEquals(40_000L, s.decodeMaxUs)
        assertEquals(25_000L, s.maxLatenessUs)
        assertEquals(1, s.outOfOrderPresented)
        assertEquals(
            "output presented=3 late=1 dropped=1 audioUnderruns=1 decodeMean=30000us " +
                "decodeMax=40000us maxLate=25000us pts=0..33333 outOfOrder=1",
            s.summary(),
        )
        c.reset()
        assertEquals(0, c.snapshot().presented)
        assertNull(c.snapshot().decodeMeanUs)
    }

    @Test
    fun stutter_stage_hint_names_the_stage() {
        val clean = FrameTiming.analyze(cfr(10, 33_333L))
        val gappy = FrameTiming.analyze(longArrayOf(0, 33_333, 133_333, 166_666))

        val quiet = PlaybackTimingCounters().snapshot()
        assertEquals(PlaybackTimingCounters.Stage.NONE, quiet.stutterStage(clean))
        assertEquals(PlaybackTimingCounters.Stage.SOURCE, quiet.stutterStage(gappy))

        val slowDecode = PlaybackTimingCounters(lateThresholdUs = 1).apply {
            onPresented(0, 50_000)
            onDecodeLatency(60_000)
        }.snapshot()
        assertEquals(PlaybackTimingCounters.Stage.DECODE, slowDecode.stutterStage(clean))

        val presentOnly = PlaybackTimingCounters().apply {
            onDropped()
            onDecodeLatency(5_000)
        }.snapshot()
        assertEquals(PlaybackTimingCounters.Stage.PRESENT, presentOnly.stutterStage(clean))

        val audioOnly = PlaybackTimingCounters().apply { onAudioUnderrun() }.snapshot()
        assertEquals(PlaybackTimingCounters.Stage.AUDIO, audioOnly.stutterStage(clean))
    }
}
