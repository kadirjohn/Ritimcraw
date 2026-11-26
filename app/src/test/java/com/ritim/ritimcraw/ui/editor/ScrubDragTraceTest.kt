package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScrubDragTrace tests, all JVM (F2-C3 device diagnosis, 2026-11-26):
 * the pure counter behind the debug ritim-trace "scrub drag" line —
 * whether the cached-frame path engages during a seek drag, at what
 * ask rate, and how long the release-to-full-frame settle takes. The
 * pinned expectations the owner reads on the device: hit > 0 proves
 * engagement; requested/dur_ms is the sweep rate; settle_ms is the
 * parked frame's full render latency; served_min/max name the frames
 * the cache actually swapped in.
 */
class ScrubDragTraceTest {

    // ---- the window: one gesture, one line ----

    @Test
    fun a_drag_with_hits_and_misses_formats_one_summary_line() {
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 1_000)
        t.onHit(10, 320, 180)   // small frame served
        t.onMiss(11)            // cache empty here: full render expected
        t.onHit(12, 320, 240)
        t.onDragEnd(nowMs = 2_243, frameIndex = 12)
        assertEquals(
            "scrub drag dur_ms=1243 requested=3 hit=2 miss=1 " +
                "served_min=320x180 served_max=320x240 settle_ms=87",
            t.onSettlePublished(nowMs = 2_330, frameIndex = 12),
        )
    }

    @Test
    fun an_all_miss_drag_still_engaged_the_render_fallback_and_shows_zero_sizes() {
        // The honest cold-cache line: the cache was asked, nothing was
        // served (the existing drag-tier renders answered every ask),
        // so hit=0 and the served fields read 0x0 — never fabricated.
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onMiss(4)
        t.onMiss(5)
        t.onDragEnd(nowMs = 500, frameIndex = 5)
        assertEquals(
            "scrub drag dur_ms=500 requested=2 hit=0 miss=2 " +
                "served_min=0x0 served_max=0x0 settle_ms=40",
            t.onSettlePublished(nowMs = 540, frameIndex = 5),
        )
    }

    // ---- the math: durations, counts, min/max sizes ----

    @Test
    fun requested_is_the_distinct_sweep_and_hit_plus_miss_equals_it() {
        // A sweep 20..30 (11 asks) with hits on the evens: every counted
        // ask lands in exactly one bucket, repeats count nowhere.
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        for (i in 20..30) {
            if (i % 2 == 0) t.onHit(i, 320, 180) else t.onMiss(i)
            // The finger wiggling inside the same frame's range re-asks:
            repeat(3) { if (i % 2 == 0) t.onHit(i, 320, 180) else t.onMiss(i) }
        }
        t.onDragEnd(nowMs = 1_000, frameIndex = 30)
        val line = t.onSettlePublished(nowMs = 1_200, frameIndex = 30)!!
        assertTrue(line, line.contains("requested=11 "))
        assertTrue(line, line.contains("hit=6 miss=5"))
    }

    @Test
    fun served_sizes_track_min_and_max_pixel_area() {
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onHit(0, 320, 240)   // 76800 px - the largest
        t.onHit(1, 256, 144)   // 36864 px - the smallest
        t.onHit(2, 300, 200)   // 60000 px - between
        t.onDragEnd(nowMs = 10, frameIndex = 2)
        assertEquals(
            "scrub drag dur_ms=10 requested=3 hit=3 miss=0 " +
                "served_min=256x144 served_max=320x240 settle_ms=15",
            t.onSettlePublished(nowMs = 25, frameIndex = 2),
        )
    }

    @Test
    fun a_clock_that_went_backwards_clamps_durations_to_zero() {
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 2_000)
        t.onHit(1, 320, 180)
        t.onDragEnd(nowMs = 1_999, frameIndex = 1)
        assertEquals(
            "scrub drag dur_ms=0 requested=1 hit=1 miss=0 " +
                "served_min=320x180 served_max=320x180 settle_ms=0",
            t.onSettlePublished(nowMs = 1_990, frameIndex = 1),
        )
        // And the window closed: a second settle has nothing to close.
        assertNull(t.onSettlePublished(nowMs = 5_000, frameIndex = 1))
    }

    // ---- the off-state / no-window rules ----

    @Test
    fun asks_without_an_open_window_are_free() {
        // The hook sites call these on every seek step; with no drag in
        // flight (and in release the sites never even reach the object)
        // the calls must be pure no-ops that leave no window behind.
        val t = ScrubDragTrace()
        t.onHit(5, 320, 180)
        t.onMiss(6)
        t.onDragEnd(nowMs = 100, frameIndex = 6)
        assertNull(t.onSettlePublished(nowMs = 200, frameIndex = 6))
        t.onDragStart(nowMs = 300)
        t.onHit(9, 320, 180)
        t.onDragEnd(nowMs = 350, frameIndex = 9)
        assertEquals(
            "scrub drag dur_ms=50 requested=1 hit=1 miss=0 " +
                "served_min=320x180 served_max=320x180 settle_ms=5",
            t.onSettlePublished(nowMs = 355, frameIndex = 9),
        )
    }

    @Test
    fun a_drag_that_asked_nothing_produces_no_line() {
        // A precision-scrub gesture rides the same slider and never
        // reaches the cache path; a requested=0 line would be noise.
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onDragEnd(nowMs = 100, frameIndex = 7)
        assertNull(t.onSettlePublished(nowMs = 150, frameIndex = 7))
        // Window closed by the null return: not stuck open.
        assertNull(t.onSettlePublished(nowMs = 400, frameIndex = 7))
    }

    @Test
    fun a_settle_of_a_different_frame_never_closes_the_window() {
        // The render gate coalesces: a leftover settle of an older
        // request may land after the release. Only the RELEASED
        // frame's settle is this gesture's story.
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onHit(3, 320, 180)
        t.onDragEnd(nowMs = 100, frameIndex = 9)
        assertNull(t.onSettlePublished(nowMs = 150, frameIndex = 3))
        assertEquals(
            "scrub drag dur_ms=100 requested=1 hit=1 miss=0 " +
                "served_min=320x180 served_max=320x180 settle_ms=60",
            t.onSettlePublished(nowMs = 160, frameIndex = 9),
        )
    }

    @Test
    fun a_new_drag_start_resets_the_counts_of_the_previous_gesture() {
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onHit(1, 320, 180)
        t.onMiss(2)
        t.onDragEnd(nowMs = 100, frameIndex = 2)
        t.onDragStart(nowMs = 1_000)   // interrupted: previous line lost
        t.onHit(5, 320, 180)
        t.onDragEnd(nowMs = 1_250, frameIndex = 5)
        assertEquals(
            "scrub drag dur_ms=250 requested=1 hit=1 miss=0 " +
                "served_min=320x180 served_max=320x180 settle_ms=10",
            t.onSettlePublished(nowMs = 1_260, frameIndex = 5),
        )
    }

    // ---- the release boundary: asks after the lift count nowhere ----

    @Test
    fun asks_after_release_are_not_this_gestures() {
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onHit(1, 320, 180)
        t.onDragEnd(nowMs = 100, frameIndex = 1)
        // A late cache ask racing the release: not the burst's.
        t.onHit(2, 320, 180)
        t.onMiss(3)
        assertEquals(
            "scrub drag dur_ms=100 requested=1 hit=1 miss=0 " +
                "served_min=320x180 served_max=320x180 settle_ms=50",
            t.onSettlePublished(nowMs = 150, frameIndex = 1),
        )
    }

    @Test
    fun sweep_backwards_counts_frames_not_steps() {
        // The seek bar maps monotonically, but a thumb dragged back
        // re-asks indices downward: every distinct consecutive ask
        // counts, repeats never.
        val t = ScrubDragTrace()
        t.onDragStart(nowMs = 0)
        t.onHit(30, 320, 180)
        t.onMiss(29)
        t.onHit(28, 320, 180)
        t.onHit(28, 320, 180)   // repeat: free
        t.onDragEnd(nowMs = 400, frameIndex = 28)
        assertEquals(
            "scrub drag dur_ms=400 requested=3 hit=2 miss=1 " +
                "served_min=320x180 served_max=320x180 settle_ms=60",
            t.onSettlePublished(nowMs = 460, frameIndex = 28),
        )
    }
}
