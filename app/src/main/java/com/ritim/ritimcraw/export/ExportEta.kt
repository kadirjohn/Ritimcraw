package com.ritim.ritimcraw.export

/**
 * Remaining-time estimator for one RUNNING export row (owner request
 * 2026-11-11 §1: the displayed ETA used to jump wildly).
 *
 * WHY THE OLD NUMBER JUMPED (Format.etaSeconds, removed): it was a
 * cumulative average anchored at the job's start,
 *     etaMs = remaining * elapsedMs / framesDone,
 * recomputed by the UI with the wall clock on every recomposition:
 *  - the average included the FIRST frame's decoder/codec-setup cost,
 *    which is many times the steady-state frame cost, so the early ETA
 *    was wildly pessimistic and then drifted down all export long;
 *  - between two progress writes (throttled to >=500 ms apart) the frame
 *    count stood still while the wall clock advanced, so the recomputed
 *    estimate counted UP between writes and snapped back down when the
 *    next write landed — the number visibly oscillated;
 *  - a cumulative average is pulled by the whole history: it barely
 *    responds to a genuine late slowdown (hundreds of earlier steady
 *    frames dilute it) while a single early outlier moves it a lot.
 *
 * THE REPLACEMENT: an exponential moving average of the observed frame
 * completion rate (frames per ms), fed by successive (framesDone, now)
 * observations — in the UI, one observation per persisted progress write,
 * which is also the display cadence, so the number only moves when the
 * frame count moves.
 *
 *  - SEED: no ETA at all until [SEED_FRAMES] frames have completed after
 *    the estimator attached (before that the row shows frames only, no
 *    time — the owner complained about bad early numbers). The seed rate
 *    is the average over that window. The window starts at the FIRST
 *    observation (frame 1 already completed), so the first frame's setup
 *    cost is outside it: the seed measures steady state.
 *  - EMA: after the seed, each observation blends the instantaneous rate
 *    of the span it covers: rate = (1-[ALPHA])*rate + [ALPHA]*dFrames/dMs.
 *  - MONOTONE-ISH HOLD: the displayed estimate never rises off a single
 *    observation. One update can slow the EMA by at most 1-[ALPHA] = 20%,
 *    and a rise is displayed only when the EMA has slowed by MORE than
 *    [SLOWDOWN_HOLD] = 20% since the last displayed change — a bound a
 *    single observation can never cross (it lands exactly AT it). So one
 *    slow frame or stall leaves the number untouched, while two or more
 *    consecutive slow observations push the EMA past the bound and the
 *    now-real increase is shown. Decreases are always shown immediately.
 *    [ALPHA] and [SLOWDOWN_HOLD] are COUPLED: alpha must stay
 *    <= 1 - slowdown-hold, or a single observation could raise the
 *    displayed estimate.
 *
 * The estimator is display-side state: it attaches when the row first
 * composes and re-seeds if the counts reset (a retry) or the row is
 * rebuilt after the screen was left (a fresh estimator shows frames only
 * for its first [SEED_FRAMES] frames again). [observe] is idempotent for
 * an unchanged frame count, so it is safe to call on every recomposition.
 */
class ExportEta(private val clock: () -> Long = System::currentTimeMillis) {

    /** Frame count of the first observation (the seed window's anchor). */
    private var firstFrames = -1
    private var firstMs = 0L

    /** Frame count / time of the most recent observation. */
    private var lastFrames = -1
    private var lastMs = 0L

    /** EMA of the completion rate, frames per ms; valid once [seeded]. */
    private var ratePerMs = 0.0
    private var seeded = false

    /** Last displayed estimate (the hold) and the EMA that produced it. */
    private var shownSeconds: Long? = null
    private var shownRatePerMs = 0.0

    /**
     * One observation of the row's frame counts. Returns the seconds to
     * display now (whole seconds), or null when no estimate should show:
     * the seed window is not complete yet, no frames remain (the
     * FINALIZING state — the caller shows "Finishing file…"), or the
     * counts went backwards (a retry) and the estimator re-seeded.
     */
    fun observe(framesDone: Int, framesTotal: Int): Long? {
        val now = clock()
        // Counts went backwards: a retry restarted the job. Start over —
        // a stale rate from the previous run must not leak into the new
        // one (the counts and the clock span no longer line up).
        if (lastFrames >= 0 && framesDone < lastFrames) {
            firstFrames = -1
            lastFrames = -1
            seeded = false
            shownSeconds = null
            shownRatePerMs = 0.0
        }
        if (lastFrames < 0) {
            // First observation: the anchor. No rate yet.
            firstFrames = framesDone
            firstMs = now
            lastFrames = framesDone
            lastMs = now
            return null
        }
        if (framesDone == lastFrames) {
            // No new frames: the last decision stands. This is what keeps
            // the number still between progress writes (the old estimate
            // recomputed from the wall clock and counted up).
            return held(framesDone, framesTotal)
        }
        val deltaFrames = framesDone - lastFrames
        val deltaMs = now - lastMs
        lastFrames = framesDone
        if (deltaMs <= 0) {
            // No measurable time passed (two writes in one clock tick):
            // keep the OLD timestamp so the next observation measures the
            // whole span, and stand pat.
            return held(framesDone, framesTotal)
        }
        lastMs = now
        if (!seeded) {
            if (framesDone - firstFrames < SEED_FRAMES || now - firstMs <= 0) {
                // Still inside the seed window: frames only, no time.
                return null
            }
            // The seed rate: the average over the whole window, which
            // starts AFTER the first frame completed — steady state, not
            // the first frame's setup cost.
            ratePerMs =
                (framesDone - firstFrames).toDouble() / (now - firstMs).toDouble()
            seeded = true
        } else {
            val instant = deltaFrames.toDouble() / deltaMs.toDouble()
            ratePerMs = (1.0 - ALPHA) * ratePerMs + ALPHA * instant
        }
        return display(framesDone, framesTotal)
    }

    /** The held decision for an observation with no new frames. */
    private fun held(framesDone: Int, framesTotal: Int): Long? {
        if (framesTotal - framesDone <= 0) return null
        return shownSeconds
    }

    /** The display decision: the raw EMA estimate under the hold policy. */
    private fun display(framesDone: Int, framesTotal: Int): Long? {
        val remaining = framesTotal - framesDone
        if (remaining <= 0) return null // finalizing: no ETA, never negative
        val rawSeconds = (remaining / ratePerMs / 1000.0).toLong()
        val shown = shownSeconds
        if (shown != null &&
            rawSeconds > shown &&
            ratePerMs >= shownRatePerMs * (1.0 - SLOWDOWN_HOLD)
        ) {
            // The EMA slowed by at most the hold bound since the last
            // displayed change: treat it as noise, keep the lower number.
            return shown
        }
        shownSeconds = rawSeconds
        shownRatePerMs = ratePerMs
        return rawSeconds
    }

    companion object {
        /** Frames observed after attaching before any ETA shows. */
        const val SEED_FRAMES = 5

        /**
         * EMA weight of the newest observation. Coupled to
         * [SLOWDOWN_HOLD]: one update can slow the EMA by at most
         * 1 - ALPHA, which must not exceed the hold bound, or a single
         * slow observation could raise the displayed estimate.
         */
        const val ALPHA = 0.2

        /**
         * A rising estimate is displayed only after the EMA slowed by
         * MORE than this fraction since the last displayed change; a
         * slowdown within the bound is treated as noise and held.
         */
        const val SLOWDOWN_HOLD = 0.2
    }
}
