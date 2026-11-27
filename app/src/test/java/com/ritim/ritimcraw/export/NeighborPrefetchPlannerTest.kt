package com.ritim.ritimcraw.export

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The neighbor-frame prefetch planner (night I4) — fake-decoder tests,
 * no Android, no threads, no clocks. The planner is a pure decision
 * function; these tests drive it exactly the way the future wiring will
 * (one [NeighborPrefetchPlanner.planWindow] per merge window, one
 * [NeighborPrefetchPlanner.onDecodeFinished] per completed decode) and
 * pin its decisions:
 *
 *  - request order (center before its own neighbors),
 *  - no double decode (cache hit / in-flight satisfy a want),
 *  - the cache bound and LRU eviction under window sliding,
 *  - cancellation without leaks on seek (queue rebuilt, census exact),
 *  - the concurrency cap (never exceeded, refills on finish),
 *  - determinism (same seed -> same behavior).
 *
 * Every expected value below is traced by hand from the contract in
 * [NeighborPrefetchPlanner]. The "decode log" mirrors what the wiring
 * would feed a real decoder: frames are appended when the planner hands
 * out a start, in start order.
 */
class NeighborPrefetchPlannerTest {

    /** Frames the fake decoder has been told to start, in start order. */
    private val decodeLog = ArrayList<Int>()

    /** Frames the fake decoder finished (handed back to the planner). */
    private val finishLog = ArrayList<Int>()

    /** Frames the planner evicted, in eviction order. */
    private val evictionLog = ArrayList<Int>()

    private fun newPlanner(
        frames: Int = 100,
        capacity: Int = NeighborPrefetchPlanner.DEFAULT_CACHE_CAPACITY,
        concurrent: Int = NeighborPrefetchPlanner.DEFAULT_MAX_CONCURRENT,
    ): NeighborPrefetchPlanner {
        decodeLog.clear()
        finishLog.clear()
        evictionLog.clear()
        return NeighborPrefetchPlanner(
            frameCount = frames,
            cacheCapacity = capacity,
            maxConcurrent = concurrent,
            onEvict = { evictionLog.add(it) },
        )
    }

    /** The wiring starts what the planner hands out. */
    private fun start(starts: List<Int>) {
        decodeLog.addAll(starts)
    }

    /** The decoder finished [frame]; the planner's refill starts are logged. */
    private fun finish(planner: NeighborPrefetchPlanner, frame: Int): List<Int> {
        finishLog.add(frame)
        val starts = planner.onDecodeFinished(frame)
        decodeLog.addAll(starts)
        return starts
    }

    /**
     * Completes every outstanding job for the current window, one at a
     * time, oldest start first — the merge waits for all of its frames.
     */
    private fun settle(planner: NeighborPrefetchPlanner) {
        while (planner.inFlightCount() > 0) {
            finish(planner, planner.inFlightFrames().first())
        }
    }

    /** Plans [center], starts what it says, settles, and returns the plan. */
    private fun window(planner: NeighborPrefetchPlanner, center: Int):
        NeighborPrefetchPlanner.WindowPlan {
        val plan = planner.planWindow(center)
        start(plan.startDecoding)
        settle(planner)
        return plan
    }

    // ------------------------------------------------------------------
    // request order
    // ------------------------------------------------------------------

    @Test
    fun first_window_starts_the_center_not_its_neighbors() {
        // Cold start at center 10 under the single-decoder cap: only the
        // CENTER starts — the merge blocks on it, so it must never queue
        // behind its own neighbors. PREV and NEXT stay queued, in that
        // priority order.
        val planner = newPlanner()
        val plan = planner.planWindow(10)
        assertEquals(listOf(10), plan.startDecoding)
        assertEquals(emptyList<Int>(), plan.fromCache)
        assertEquals(emptyList<Int>(), plan.decoding)
        assertEquals(2, plan.queuedCount)
    }

    @Test
    fun queue_priority_is_center_then_prev_then_next() {
        // With three free slots the same window starts all three frames —
        // in priority order CENTER, PREV, NEXT, not in index order.
        val planner = newPlanner(concurrent = 3)
        val plan = planner.planWindow(10)
        assertEquals(listOf(10, 9, 11), plan.startDecoding)
        assertEquals(0, plan.queuedCount)
    }

    @Test
    fun edge_window_omits_out_of_range_frames() {
        // Center 0 has no PREV; the clip's last center has no NEXT. The
        // planner never queues a frame the clip does not have. The first
        // window's jobs settle before the second edge is planned (with 3
        // slots the first window would otherwise still hold two of them).
        val planner = newPlanner(frames = 12, concurrent = 3)
        val first = planner.planWindow(0)
        assertEquals(listOf(0, 1), first.startDecoding)
        start(first.startDecoding)
        settle(planner)
        assertEquals(listOf(11, 10), planner.planWindow(11).startDecoding)
    }

    @Test
    fun re_planning_the_same_window_is_idempotent() {
        // A re-rendered window (same center) must not requeue or restart
        // anything: what is in flight shows as "decoding", the backlog
        // keeps its size, and nothing new starts.
        val planner = newPlanner()
        planner.planWindow(10)
        val again = planner.planWindow(10)
        assertEquals(emptyList<Int>(), again.startDecoding)
        assertEquals(listOf(10), again.decoding)
        assertEquals(2, again.queuedCount)
    }

    // ------------------------------------------------------------------
    // no double decode
    // ------------------------------------------------------------------

    @Test
    fun sequential_walk_decodes_each_frame_exactly_once() {
        // THE amortization promise (core Temporal.h: "sequential playback
        // amortizes to ~1 decode/frame"): a one-frame-at-a-time walk asks
        // for each frame twice — as NEXT of window k, then as PREV of
        // window k+1 — and the second ask is a cache hit. Across centers
        // 1..49 of a 50-frame clip the decode log is 0..49, each frame
        // EXACTLY once: 50 decodes, not the ~100 a cache-less bridge
        // would do.
        val planner = newPlanner(frames = 50)
        for (center in 1 until 50) {
            window(planner, center)
        }
        assertEquals(50, decodeLog.size)
        assertEquals((0 until 50).toList(), decodeLog.sorted())
        assertEquals(
            "no frame decoded twice",
            0,
            decodeLog.groupBy { it }.count { it.value.size > 1 },
        )
    }

    @Test
    fun cached_frame_is_not_decoded_again() {
        // After window 10 completes, window 11 finds its PREV (frame 10)
        // in the cache: a hit, never a second decode.
        val planner = newPlanner()
        window(planner, 10)
        val next = window(planner, 11)
        assertTrue(next.fromCache.contains(10))
        assertTrue(next.fromCache.contains(11))
        assertFalse(next.startDecoding.contains(10))
        assertEquals(1, decodeLog.count { it == 10 })
    }

    @Test
    fun in_flight_frame_is_not_started_again() {
        // The NEXT queued behind the cap becomes the CENTER of the next
        // window while the old center is still decoding: the window
        // report shows the running job as "decoding" (no duplicate start),
        // and when the slot frees, the NEW CENTER starts — priority holds
        // across the slide.
        val planner = newPlanner()
        val first = planner.planWindow(10) // 10 in flight, {9, 11} queued
        start(first.startDecoding)
        val slid = planner.planWindow(11) // slide before finishing 10
        assertEquals(emptyList<Int>(), slid.startDecoding) // cap: 10 occupies the slot
        assertEquals(listOf(10), slid.decoding)
        val refilled = finish(planner, 10)
        assertEquals(listOf(11), refilled) // new center, not its NEXT
        assertEquals(1, decodeLog.count { it == 10 })
        assertEquals(1, decodeLog.count { it == 11 })
    }

    // ------------------------------------------------------------------
    // cache bound + LRU under window sliding
    // ------------------------------------------------------------------

    @Test
    fun cache_never_exceeds_capacity_on_a_long_walk() {
        // A full walk with capacity 4 over a 50-frame clip: the resident
        // set is checked after every finish; it can be under, never over.
        val capacity = 4
        val planner = newPlanner(frames = 50, capacity = capacity)
        for (center in 1 until 50) {
            val plan = planner.planWindow(center)
            start(plan.startDecoding)
            settle(planner)
            assertTrue(
                "capacity blown: ${planner.cachedFrames()}",
                planner.cachedFrames().size <= capacity,
            )
        }
    }

    @Test
    fun lru_evicts_exactly_the_frames_that_left_the_window() {
        // Capacity 4, slide +1: the window keeps {c-1, c, c+1}; one
        // earlier frame rides along. At every settle(c) from c=3 on, the
        // overflow evicts c-3 — precisely the frame that slipped out of
        // the window's reach — never a window member. Traced by hand:
        // settle(1) -> {1,0,2}; settle(2) -> {0,1,2,3}; settle(3) inserts
        // 4 and evicts 0; ... settle(48) inserts 49 and evicts 45.
        val planner = newPlanner(frames = 50, capacity = 4)
        for (center in 1 until 50) {
            window(planner, center)
        }
        assertEquals((0..45).toList(), evictionLog)
        assertEquals(listOf(46, 47, 48, 49), planner.cachedFrames())
    }

    @Test
    fun cache_hits_keep_the_working_window_most_recent() {
        // The merge's own inputs must not evict while they are window
        // members: window 10 reads 9 and 10 as HITS (from window 9), the
        // touches make them recent, so when 11 lands the resident order
        // is exactly the sliding window plus the frame leaving reach.
        val planner = newPlanner(frames = 40, capacity = 4)
        window(planner, 9)
        window(planner, 10)
        assertEquals(listOf(8, 9, 10, 11), planner.cachedFrames())
    }

    @Test
    fun an_evicted_frame_decodes_again_when_reached() {
        // An honest miss, never a stale serve: a far seek evicts the old
        // neighborhood; walking back to it re-decodes the frames (the
        // decode log shows the repeat), and the planner reports them as
        // starts, not as cache hits.
        val planner = newPlanner(frames = 30, capacity = 4)
        window(planner, 5)
        window(planner, 6)
        window(planner, 7)
        assertTrue(planner.isCached(7))
        window(planner, 25) // far seek: evicts the old neighborhood
        window(planner, 26)
        assertFalse(planner.isCached(5))
        assertFalse(planner.isCached(6))
        val back = planner.planWindow(6)
        assertEquals(emptyList<Int>(), back.fromCache) // everything re-decodes
        start(back.startDecoding)
        settle(planner)
        assertEquals(2, decodeLog.count { it == 6 }) // once before, once after
    }

    // ------------------------------------------------------------------
    // seek: cancellation without leaks
    // ------------------------------------------------------------------

    @Test
    fun seek_cancels_the_abandoned_window_backlog() {
        // Window 10 under cap 1: 10 runs, {9, 11} queued. A JUMP to 60
        // makes both pointless — the queue is rebuilt around {59, 60, 61}
        // and neither 9 nor 11 is EVER decoded. The full decode log of
        // the whole interaction is [10, 60, 59, 61].
        val planner = newPlanner()
        val first = planner.planWindow(10)
        start(first.startDecoding) // 10
        val afterSeek = planner.planWindow(60)
        assertEquals(emptyList<Int>(), afterSeek.startDecoding) // 10 holds the slot
        assertEquals(3, afterSeek.queuedCount)
        start(afterSeek.startDecoding)
        settle(planner)
        assertEquals(listOf(10, 60, 59, 61), decodeLog)
        assertFalse(decodeLog.contains(9))
        assertFalse(decodeLog.contains(11))
    }

    @Test
    fun seek_keeps_the_in_flight_census_exact() {
        // The stale in-flight job (the pre-seek center) is never
        // cancelled — the single decoder cannot be interrupted — so it
        // delays the new window's center by at most one decode, then
        // finishes into the cache and frees its slot. Afterwards every
        // job ever started has been finished: no slot leaks, nothing lost.
        val planner = newPlanner()
        val first = planner.planWindow(10)
        start(first.startDecoding)
        planner.planWindow(60) // the seek
        assertEquals(listOf(10), planner.inFlightFrames())
        val blocked = planner.planWindow(60)
        assertEquals(emptyList<Int>(), blocked.startDecoding) // slot still held
        assertEquals(listOf(10), planner.inFlightFrames())
        val refilled = finish(planner, 10) // stale job completes; the
        // refill starts it hands back are already logged by finish().
        assertEquals(listOf(60), refilled) // new center takes the slot
        settle(planner)
        assertEquals(0, planner.inFlightCount())
        assertEquals(0, planner.queuedCount())
        // Every started job finished exactly once: starts == finishes.
        assertEquals(decodeLog.size, finishLog.size)
        assertEquals(decodeLog.sorted(), finishLog.sorted())
    }

    @Test
    fun a_small_backward_scrub_is_served_from_cache() {
        // Scrubbing back inside capacity is a pure cache hit: no decode,
        // no eviction — the frames never left the window's reach. The
        // merge can re-run window 9 for free after window 10.
        val planner = newPlanner(capacity = 4)
        window(planner, 10) // decodes 10, 9, 11
        window(planner, 9) // scrub back one: 8 decodes, 9/10/11 are hits
        val scrub = planner.planWindow(9)
        assertTrue(scrub.fromCache.contains(9))
        assertTrue(scrub.fromCache.contains(10))
        assertEquals(1, decodeLog.count { it == 9 })
        assertEquals(1, decodeLog.count { it == 10 })
        assertEquals(1, decodeLog.count { it == 8 })
    }

    @Test
    fun finishing_a_frame_that_never_started_fails_fast() {
        // The planner is the only source of start decisions; a finish for
        // anything else is a wiring bug and must not corrupt the census.
        val planner = newPlanner()
        planner.planWindow(10) // 10 started, 9 and 11 queued
        try {
            planner.onDecodeFinished(9) // queued, never started
            fail("expected IllegalStateException for finishing a queued frame")
        } catch (expected: IllegalStateException) {
        }
        // The census is untouched: 10 is still the one job in flight.
        assertEquals(1, planner.inFlightCount())
    }

    // ------------------------------------------------------------------
    // random seek sequence, deterministic seed
    // ------------------------------------------------------------------

    /**
     * One mixed run: 400 steps, 40% seeks (jump anywhere in 1..58), 60%
     * one-frame slides, with a random number of finishes between plans so
     * windows interleave with in-flight decodes. Returns the full
     * behavior trace (every plan + every start/finish/evict, in order).
     * After EVERY step the invariants hold: the cap, "a resident or
     * in-flight frame never starts", and "every started job is either
     * finished or still in flight" (the no-leak census).
     */
    private fun randomRun(seed: Long, frames: Int, capacity: Int, concurrent: Int):
        List<String> {
        val planner = newPlanner(frames = frames, capacity = capacity, concurrent = concurrent)
        val trace = ArrayList<String>(600)
        val random = Random(seed)
        var lastCenter = 1
        repeat(400) {
            val center = if (random.nextInt(100) < 40) {
                (lastCenter + random.nextInt(41) - 20).coerceIn(1, frames - 2)
            } else {
                (lastCenter + 1).coerceIn(1, frames - 2)
            }
            lastCenter = center
            // The state the new plan must respect: nothing resident or in
            // flight may ever be started again.
            val residentBefore = planner.cachedFrames() + planner.inFlightFrames()
            val plan = planner.planWindow(center)
            trace.add(
                "plan($center): hit=${plan.fromCache} decoding=${plan.decoding} " +
                    "start=${plan.startDecoding} queued=${plan.queuedCount}",
            )
            assertTrue(
                "double start: start=${plan.startDecoding} resident=$residentBefore",
                plan.startDecoding.none { it in residentBefore },
            )
            assertTrue(
                "cap exceeded: ${planner.inFlightFrames()} > $concurrent",
                planner.inFlightCount() <= concurrent,
            )
            assertTrue(
                "cache over capacity: ${planner.cachedFrames()}",
                planner.cachedFrames().size <= capacity,
            )
            start(plan.startDecoding)
            repeat(random.nextInt(3)) {
                if (planner.inFlightCount() > 0) {
                    val frame = planner.inFlightFrames().first()
                    val residentNow = planner.cachedFrames() + planner.inFlightFrames()
                    val refill = finish(planner, frame)
                    trace.add("finish($frame): start=$refill")
                    assertTrue(
                        "double start on refill: $refill vs $residentNow",
                        refill.none { it in residentNow },
                    )
                    assertTrue(
                        "cap exceeded on refill: ${planner.inFlightFrames()}",
                        planner.inFlightCount() <= concurrent,
                    )
                }
            }
        }
        // The census at any observation point: every job ever started is
        // finished or in flight. Checked here at the end of the run, and
        // implicitly at every step (starts only ever grow by planner
        // decisions, finishes only by explicit finish calls).
        assertEquals(
            "started jobs leaked: $decodeLog vs $finishLog",
            decodeLog.size,
            finishLog.size + planner.inFlightCount(),
        )
        return trace
    }

    @Test
    fun random_seek_sequence_same_seed_same_behavior() {
        // Determinism: the same seed drives the same call sequence, and
        // the planner is a pure function of those calls — the two traces
        // (plans, starts, finishes, in order) are identical.
        val a = randomRun(seed = 20261127L, frames = 60, capacity = 5, concurrent = 1)
        val b = randomRun(seed = 20261127L, frames = 60, capacity = 5, concurrent = 1)
        assertEquals(a, b)
    }

    @Test
    fun random_seek_sequence_respects_every_invariant() {
        // The invariants are asserted inside the run on every step; this
        // pins the run at a second seed (and a second shape: cap 3) so
        // both configurations of the walk stay covered. A different seed
        // exercises a different walk than the determinism pair.
        val cap1 = randomRun(seed = 42L, frames = 60, capacity = 5, concurrent = 1)
        val cap3 = randomRun(seed = 99L, frames = 60, capacity = 8, concurrent = 3)
        val other = randomRun(seed = 42L, frames = 60, capacity = 5, concurrent = 1)
        assertEquals(cap1, other) // same seed still reproduces
        assertTrue(
            "a different seed or cap should exercise a different walk",
            cap1 != cap3 || randomRun(43L, 60, 5, 1) != cap1,
        )
    }

    // ------------------------------------------------------------------
    // concurrency cap N
    // ------------------------------------------------------------------

    @Test
    fun cap_n_is_never_exceeded_across_windows_and_seeks() {
        // Cap 3 over a mixed walk: after every plan AND every finish the
        // in-flight count is at most 3 — the handout itself never
        // crosses the cap, whatever the queue wants.
        val concurrent = 3
        val planner = newPlanner(frames = 60, capacity = 6, concurrent = concurrent)
        val random = Random(7L)
        var lastCenter = 1
        repeat(300) {
            val center = (lastCenter + (if (random.nextBoolean()) 1 else random.nextInt(15) - 7))
                .coerceIn(1, 58)
            lastCenter = center
            val plan = planner.planWindow(center)
            assertTrue(planner.inFlightCount() <= concurrent)
            start(plan.startDecoding)
            if (planner.inFlightCount() > 0 && random.nextBoolean()) {
                finish(planner, planner.inFlightFrames().first())
            }
            assertTrue(planner.inFlightCount() <= concurrent)
        }
        settle(planner)
        assertEquals(0, planner.inFlightCount())
        assertTrue(planner.cachedFrames().size <= 6)
    }

    @Test
    fun cap_one_never_has_two_in_flight() {
        // The single-decoder default (ExportEngine: one native call at a
        // time), asserted continuously over a mixed walk: at most one
        // job in flight at every observation.
        val planner = newPlanner() // frames=100, cap=1, capacity=6
        val random = Random(11L)
        var lastCenter = 1
        repeat(200) {
            val center = (lastCenter + (if (random.nextInt(100) < 70) 1 else random.nextInt(21) - 10))
                .coerceIn(1, 98)
            lastCenter = center
            val plan = planner.planWindow(center)
            assertTrue(planner.inFlightCount() <= 1)
            start(plan.startDecoding)
            if (planner.inFlightCount() == 1) {
                finish(planner, planner.inFlightFrames().first())
            }
            assertTrue(planner.inFlightCount() <= 1)
        }
    }

    @Test
    fun queued_jobs_start_only_when_a_slot_frees() {
        // Cap 1: window 10 starts only the center; the backlog starts
        // only inside finish (the freed slot), in queue priority order
        // (PREV before NEXT), never on a re-plan.
        val planner = newPlanner()
        val first = planner.planWindow(10)
        assertEquals(listOf(10), first.startDecoding)
        assertEquals(emptyList<Int>(), planner.planWindow(10).startDecoding)
        start(first.startDecoding)
        assertEquals(listOf(9), finish(planner, 10))
        assertEquals(listOf(11), finish(planner, 9))
        assertEquals(emptyList<Int>(), planner.planWindow(10).startDecoding)
        assertEquals(listOf(10, 9, 11), decodeLog)
    }

    // ------------------------------------------------------------------
    // reset
    // ------------------------------------------------------------------

    @Test
    fun reset_drops_all_state_but_keeps_the_eviction_diagnostic() {
        // After a clip close (reset) the next window is COLD: no hits, no
        // in-flight, no queue. The eviction counter is a lifetime
        // diagnostic and survives (it reports, it does not decide).
        val planner = newPlanner(frames = 10, capacity = 2)
        window(planner, 5)
        window(planner, 6)
        window(planner, 7)
        assertTrue(planner.evictions > 0)
        val before = planner.evictions
        planner.reset()
        assertEquals(0, planner.inFlightCount())
        assertEquals(0, planner.queuedCount())
        assertEquals(emptyList<Int>(), planner.cachedFrames())
        val cold = planner.planWindow(3)
        assertEquals(emptyList<Int>(), cold.fromCache)
        assertEquals(emptyList<Int>(), cold.decoding)
        assertEquals(before, planner.evictions)
    }

    // ------------------------------------------------------------------
    // constructor and caller contracts
    // ------------------------------------------------------------------

    @Test
    fun constructor_rejects_nonpositive_parameters() {
        try {
            NeighborPrefetchPlanner(frameCount = 0)
            fail("expected IllegalArgumentException for frameCount 0")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            NeighborPrefetchPlanner(frameCount = 10, cacheCapacity = 0)
            fail("expected IllegalArgumentException for capacity 0")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            NeighborPrefetchPlanner(frameCount = 10, maxConcurrent = 0)
            fail("expected IllegalArgumentException for concurrency 0")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun out_of_range_center_is_rejected_not_guessed() {
        // The planner never guesses whether an index exists: a center
        // outside the clip is a caller bug and fails fast. (In-range
        // centers with out-of-range NEIGHBORS are fine — the neighbor is
        // simply omitted.)
        val planner = newPlanner(frames = 12)
        try {
            planner.planWindow(12)
            fail("expected IllegalArgumentException for center == frameCount")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            planner.planWindow(-1)
            fail("expected IllegalArgumentException for negative center")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun single_frame_clip_plans_only_center_zero() {
        // The degenerate clip: the window is just frame 0; nothing out of
        // range is ever queued, and the plan settles to one decode.
        val planner = newPlanner(frames = 1)
        val plan = planner.planWindow(0)
        assertEquals(listOf(0), plan.startDecoding)
        start(plan.startDecoding)
        settle(planner)
        assertEquals(listOf(0), decodeLog)
        assertEquals(listOf(0), planner.cachedFrames())
    }
}
