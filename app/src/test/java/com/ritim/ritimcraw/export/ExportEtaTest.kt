package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remaining-time estimator (owner request 2026-11-11 §1: the ETA used
 * to jump wildly) — fake-clock tests, no Android.
 *
 * The fake clock drives the estimator exactly the way the UI does: one
 * [ExportEta.observe] call per persisted progress write (throttled to
 * >=500 ms apart in production), starting at the exporters' first report
 * (0, total). Expected values are traced by hand and chosen to sit away
 * from whole-second rounding boundaries, so the assertions are stable.
 */
class ExportEtaTest {

    private var clockNow = 0L

    /** Steps the fake clock and observes; returns the seconds to display. */
    private fun ExportEta.tick(framesDone: Int, framesTotal: Int, stepMs: Long): Long? {
        clockNow += stepMs
        return observe(framesDone, framesTotal)
    }

    private fun newEta(): ExportEta = ExportEta { clockNow }

    // ---- seed gate ----

    @Test
    fun no_eta_before_the_seed_frames() {
        // The seed window is 5 frames after the estimator attaches: before
        // that the row shows frames only, no time (the owner complained
        // about bad early numbers). The first estimate appears on the
        // observation that completes the window.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 500))
        assertNull(eta.tick(2, 310, 500))
        assertNull(eta.tick(3, 310, 500))
        assertNull(eta.tick(4, 310, 500))
        // Window complete: 5 frames in 2,500 ms -> 305 remaining
        // -> 152.5 s -> 152.
        assertEquals(152L, eta.tick(5, 310, 500))
    }

    @Test
    fun seed_averages_the_setup_cost_across_the_window() {
        // The first frame pays the decode/codec setup (here 2 s, 20x the
        // steady 100 ms): inside a 5-frame window it is averaged 1-in-5
        // instead of dominating a one-frame sample. Window: 2,400 ms for
        // 5 frames -> 305 remaining -> 146.4 s -> 146 (a one-frame sample
        // would have claimed ~610 s).
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 2_000))
        assertNull(eta.tick(2, 310, 100))
        assertNull(eta.tick(3, 310, 100))
        assertNull(eta.tick(4, 310, 100))
        assertEquals(146L, eta.tick(5, 310, 100))
    }

    // ---- convergence ----

    @Test
    fun converges_on_a_constant_rate() {
        // 1 frame per 120 ms steady: the ETA tracks the true remaining
        // time (frames * 120 ms) all export long. 10 frames remain at
        // the last observation -> true ETA 1.2 s -> 1.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        var last: Long? = null
        for (frames in 1..300) {
            last = eta.tick(frames, 310, 120)
        }
        assertEquals(1L, last)
    }

    // ---- stability: a single slow frame ----

    @Test
    fun a_single_10x_slow_frame_does_not_move_the_displayed_eta() {
        // Steady 1 frame/100 ms, then ONE frame takes 1 s (10x slow — a
        // VFR keyframe or a scheduling stall), then steady again. The EMA
        // takes alpha=0.2 of that span's rate and the hold keeps the
        // display from rising on it: one observation can slow the EMA by
        // at most (1-alpha) = 20%, exactly the hold bound — a rise shows
        // only when the EMA slowed by MORE than that. The displayed
        // number does not move; the EMA recovers on the next steady
        // observation. (Bound check: a hypothetical 15% rise would still
        // be far larger than the 0% this produces.)
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 100))
        assertNull(eta.tick(2, 310, 100))
        assertNull(eta.tick(3, 310, 100))
        assertNull(eta.tick(4, 310, 100))
        val before = eta.tick(5, 310, 100)!!
        val after = eta.tick(6, 310, 1_000)!!
        assertEquals(before, after)
        val recovered = eta.tick(7, 310, 100)!!
        assertEquals(before, recovered)
    }

    // ---- stability: no wall-clock creep between writes ----

    @Test
    fun no_eta_creep_between_progress_writes() {
        // The old UI recomputed from the wall clock: between two throttled
        // writes the frames stood still while the clock advanced, so the
        // number counted UP and snapped back. The estimator is now
        // idempotent for an unchanged count: the held number stands,
        // however late the clock is.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 500))
        assertNull(eta.tick(2, 310, 500))
        assertNull(eta.tick(3, 310, 500))
        assertNull(eta.tick(4, 310, 500))
        val shown = eta.tick(5, 310, 500)
        assertEquals(shown, eta.tick(5, 310, 10_000))
        assertEquals(shown, eta.tick(5, 310, 60_000))
    }

    // ---- genuine rate change ----

    @Test
    fun recovers_when_the_rate_genuinely_changes() {
        // A steady 1 frame/100 ms run, then the export REALLY slows to
        // 1 frame/300 ms (thermal throttle). Two slow observations push
        // the EMA past the 20% hold bound, the now-real increase is
        // shown, and the estimate converges onto the new rate: at frame
        // 155 the true remaining time is 155 * 300 ms = 46.5 s, and the
        // displayed estimate says 46 — it rose from the 25 s the fast
        // rate implied, so the hold released and the number tracks the
        // new reality instead of pretending the old rate still holds.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        for (frames in 1..60) {
            eta.tick(frames, 310, 100)
        }
        var slowShown: Long? = null
        for (frames in 61..155) {
            slowShown = eta.tick(frames, 310, 300)
        }
        assertEquals(46L, slowShown)
    }

    // ---- finalizing ----

    @Test
    fun finalizing_state_returns_null_not_zero() {
        // All frames done (the mux/finalize stage runs): no ETA — the
        // caller shows "Finishing file…". Past-total (a late report)
        // also null, never a negative number.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 500))
        assertNull(eta.tick(2, 310, 500))
        assertNull(eta.tick(3, 310, 500))
        assertNull(eta.tick(4, 310, 500))
        assertEquals(152L, eta.tick(5, 310, 500))
        assertNull(eta.tick(310, 310, 500))
        assertNull(eta.tick(320, 310, 500))
    }

    // ---- retry ----

    @Test
    fun a_retry_reseeds_the_estimator() {
        // The counts went backwards (a retry restarts from frame 0): the
        // stale rate from the previous run must not leak — the estimator
        // resets and re-seeds before showing a number again.
        val eta = newEta()
        assertNull(eta.observe(0, 310))
        assertNull(eta.tick(1, 310, 500))
        assertNull(eta.tick(2, 310, 500))
        assertNull(eta.tick(3, 310, 500))
        assertNull(eta.tick(4, 310, 500))
        assertEquals(152L, eta.tick(5, 310, 500))
        // The retry: counts reset to 0.
        assertNull(eta.tick(0, 310, 500))
        // Re-seeded: frames only until the window completes again.
        assertNull(eta.tick(1, 310, 500))
        assertNull(eta.tick(2, 310, 500))
        assertNull(eta.tick(3, 310, 500))
        assertNull(eta.tick(4, 310, 500))
        assertEquals(152L, eta.tick(5, 310, 500))
    }
}
