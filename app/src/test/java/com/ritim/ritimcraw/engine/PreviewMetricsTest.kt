package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FpsWindow (fake clock) + MetricsSummary tests for the measurement harness
 * (owner request 2026-11-10 section 1). No real clock, no Android classes —
 * every timestamp is fed in, so the window math is exact.
 */
class PreviewMetricsTest {

    // ---- fps window math ----

    @Test
    fun empty_window_counts_zero_and_zero_fps() {
        val w = FpsWindow(windowMs = 5000)
        assertEquals(0, w.count(nowMs = 10_000))
        assertEquals(0.0, w.fps(nowMs = 10_000), 0.0)
    }

    @Test
    fun frames_inside_window_are_counted() {
        val w = FpsWindow(windowMs = 5000)
        w.onFrame(10_000)
        w.onFrame(10_100)
        w.onFrame(10_200)
        assertEquals(3, w.count(nowMs = 12_000))
    }

    @Test
    fun stamps_older_than_window_drop_off() {
        val w = FpsWindow(windowMs = 5000)
        w.onFrame(10_000)
        w.onFrame(11_000)
        w.onFrame(14_000)
        // At now=15_000 the cutoff is 10_000: the stamp exactly AT the
        // cutoff stays, older ones drop.
        assertEquals(3, w.count(nowMs = 15_000))
        assertEquals(2, w.count(nowMs = 15_001))
    }

    @Test
    fun fps_is_intervals_over_observed_span() {
        val w = FpsWindow(windowMs = 5000)
        // 3 frames over 1000 ms = 2 intervals / 1 s = 2.0 fps.
        w.onFrame(10_000)
        w.onFrame(10_500)
        w.onFrame(11_000)
        assertEquals(2.0, w.fps(nowMs = 11_000), 1e-9)
    }

    @Test
    fun single_frame_has_no_span_so_zero_fps() {
        val w = FpsWindow(windowMs = 5000)
        w.onFrame(10_000)
        assertEquals(0.0, w.fps(nowMs = 10_500), 0.0)
    }

    @Test
    fun zero_span_never_divides_by_zero() {
        val w = FpsWindow(windowMs = 5000)
        w.onFrame(10_000)
        w.onFrame(10_000)
        assertEquals(0.0, w.fps(nowMs = 10_000), 0.0)
    }

    @Test
    fun window_keeps_only_the_last_5000ms_of_a_long_run() {
        val w = FpsWindow(windowMs = 5000)
        // 30 fps for 10 s: the fps at the end reflects only the LAST window.
        var t = 0L
        while (t < 10_000) {
            w.onFrame(t)
            t += 33
        }
        assertEquals(30.0, w.fps(nowMs = t), 2.0)
    }

    @Test
    fun reset_clears_recorded_frames() {
        val w = FpsWindow(windowMs = 5000)
        w.onFrame(10_000)
        w.reset()
        assertEquals(0, w.count(nowMs = 10_000))
    }

    // ---- harness constants ----

    @Test
    fun playback_window_is_five_seconds() {
        assertEquals(5000L, PreviewMetrics.PLAYBACK_WINDOW_MS)
    }

    @Test
    fun harness_tag_is_ritim_pm() {
        assertEquals("ritim-pm", PreviewMetrics.TAG)
    }

    // ---- summary block ----

    @Test
    fun summary_block_lists_every_leg() {
        val block = MetricsSummary.block(
            openMs = 120,
            firstFrameMs = 340,
            sliderLatenciesMs = listOf(41, 39),
            playbackFrames = 150,
            playbackWindowMs = 5000,
            playbackFps = 29.96,
        )
        assertTrue(block.contains("ritim-pm"))
        assertTrue(block.contains("session open: 120 ms"))
        assertTrue(block.contains("first frame, open -> published: 340 ms"))
        assertTrue(block.contains("2x cached 480px: 41 ms 39 ms"))
        assertTrue(block.contains("playback: 150 frames in 5000 ms = 30.0 fps @ 720px"))
        assertTrue(block.contains("=== end preview metrics ==="))
    }

    @Test
    fun summary_block_handles_empty_slider_leg() {
        val block = MetricsSummary.block(
            openMs = 120,
            firstFrameMs = 340,
            sliderLatenciesMs = emptyList(),
            playbackFrames = 0,
            playbackWindowMs = 5000,
            playbackFps = 0.0,
        )
        // A clip that failed every slider render still formats cleanly.
        assertTrue(block.contains("0x cached 480px: "))
        assertTrue(block.contains("playback: 0 frames in 5000 ms = 0.0 fps @ 720px"))
    }
}
