package com.ritim.ritimcraw.export

import java.util.LinkedHashMap

/**
 * PURE-LOGIC prefetch planner for neighbor-frame decode (night I4). Not
 * wired into any exporter: nothing in production calls it, and wiring it
 * into the real decoder/ExportEngine is a separate, later job. This file
 * therefore changes no existing behavior.
 *
 * WHY IT EXISTS. The temporal NR merge consumes a window of raw frames —
 * CENTER + PREV + NEXT (core `Temporal.h`: "center frame + up to two
 * neighbors"; the JNI bridge owns "a small ring cache of decoded neighbor
 * CFAs keyed by frame index, so sequential playback amortizes to ~1
 * decode/frame"). Every one of those frames must be DECODED
 * (`Source::readFrame`), and on device that decode — not the merge — is
 * suspected to be the bulk of export wall time (desktop could not measure
 * it: decode cost is device-specific, and D4 measured the MERGE only).
 * Decoding the next window's frames while the current window's merge runs
 * is the obvious way to reclaim decoder-idle time — but only if something
 * decides, ahead of time, WHAT to decode, what to keep, and what to drop.
 * That decision is this class. NO SPEED CLAIM IS MADE OR MEASURED HERE:
 * any real gain can only be measured on device after the wiring lands.
 *
 * THE CONTRACT (a decision function, not a runner — no threads, no
 * coroutines, no clocks, no MediaCodec):
 *
 *  - [planWindow] is called once per merge window with its center. It
 *    returns a [WindowPlan]: which of the window's frames are already
 *    decoded (cache hits — a frame is never decoded twice while it is
 *    resident), which are currently decoding (the running job satisfies
 *    them — no duplicate decode is started), which the caller must start
 *    decoding now, and how many are still queued behind the concurrency
 *    cap.
 *
 *  - The queue is REBUILT from the new window on every [planWindow]:
 *    frames the new window does not want never re-enter it. That is the
 *    cancellation. A SEEK (a jump, not a ±1 slide) therefore drops the
 *    entire old backlog — a frame no window wants is never decoded — and
 *    a slide keeps exactly the lookahead that survives (the NEXT of
 *    window k is the CENTER of window k+1).
 *
 *  - Jobs already STARTED are never cancelled: the single native decoder
 *    cannot be interrupted mid-decode (see `ExportEngine`), so an
 *    abandoned in-flight job runs to completion and its frame lands in
 *    the cache, to be evicted later if nobody wants it. A seek can thus
 *    delay the new window's center by at most [maxConcurrent] stale
 *    decodes — under the DEFAULT of 1, one decode. The in-flight census
 *    ([inFlightCount]) stays exact through seeks and cancellations.
 *
 *  - At most [maxConcurrent] decode jobs may be in flight; the cap is
 *    absolute — the planner never hands out more starts than it allows,
 *    not even to a window's own center (a blocked window waits for the
 *    stale job to finish; the alternative, exceeding the cap, would
 *    break the single-decoder guarantee the cap exists to encode).
 *
 *  - Decoded frames live in a bounded LRU cache keyed by frame index.
 *    Recency = window use: a frame the current window reads — even as a
 *    cache hit — is touched, so what falls out of the sliding window's
 *    reach is what gets evicted ("LRU + window sliding"). An evicted
 *    frame a later window wants again is an honest miss: it is decoded
 *    again, never served stale.
 *
 *  - [frameCount] (the CLIP's frame count, which the real wiring always
 *    knows) is the validity fence: out-of-range neighbors are skipped,
 *    and an out-of-range center is a caller bug, rejected — the planner
 *    never guesses whether an index exists.
 *
 * WHAT IS INTENTIONALLY ABSENT: the real decoder's threading, error
 * paths, memory budgeting and buffer ownership all belong to the wiring
 * job. The planner is deterministic — driven entirely by [planWindow]
 * and [onDecodeFinished] calls — which is what lets the JVM tests (a
 * fake decoder) pin its exact behavior.
 */
class NeighborPrefetchPlanner(
    /** The clip's frame count; the validity fence for window members. */
    private val frameCount: Int,
    /** Bounded LRU capacity of decoded frames. */
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
    /** Hard, absolute cap on decode jobs in flight at once. */
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /** Invoked once per evicted frame (the wiring frees buffers here). */
    private val onEvict: (frame: Int) -> Unit = {},
) {
    init {
        require(frameCount > 0) { "frameCount must be positive, was $frameCount" }
        require(cacheCapacity > 0) { "cacheCapacity must be positive, was $cacheCapacity" }
        require(maxConcurrent > 0) { "maxConcurrent must be positive, was $maxConcurrent" }
    }

    /**
     * The decision for one window. The three frame lists together cover
     * every in-range frame of {center-1, center, center+1}, in request
     * priority order: CENTER first (the merge blocks on it, so it must
     * never queue behind its own neighbors), then PREV, then NEXT.
     */
    data class WindowPlan(
        val center: Int,
        /** Window frames already decoded — read them from the cache. */
        val fromCache: List<Int>,
        /** Window frames currently decoding — the running job satisfies them. */
        val decoding: List<Int>,
        /** Frames the caller must start decoding now (never past the cap). */
        val startDecoding: List<Int>,
        /** Window frames queued but not started (waiting for a free slot). */
        val queuedCount: Int,
    )

    /**
     * The current window's backlog: wanted frames not yet started. Head =
     * highest priority. [planWindow] rebuilds it from the window's wants,
     * which is what cancels the abandoned neighborhood's frames.
     */
    private val pending = ArrayDeque<Int>()

    /** Decode jobs handed out, not finished (insertion order = start order). */
    private val inFlight = LinkedHashMap<Int, Unit>()

    /** Decoded frames; access-ordered (first = least recently used). */
    private val lru = LinkedHashMap<Int, Unit>(0, 0.75f, true)

    /** Lifetime eviction count (survives [reset]; a diagnostic, not state). */
    var evictions: Int = 0
        private set

    /** The merge wants the window around [center]. See the class contract. */
    fun planWindow(center: Int): WindowPlan {
        require(center in 0 until frameCount) {
            "center $center outside the clip's 0 until $frameCount frames"
        }
        val fromCache = ArrayList<Int>(3)
        val decoding = ArrayList<Int>(2)
        val misses = ArrayList<Int>(3)
        for (frame in intArrayOf(center, center - 1, center + 1)) {
            if (frame < 0 || frame >= frameCount) {
                continue // out of range: never queued, never decoded
            }
            when {
                lru.containsKey(frame) -> fromCache.add(frame)
                inFlight.containsKey(frame) -> decoding.add(frame)
                else -> misses.add(frame)
            }
        }
        // Recency = window use. Touch the hits in REVERSE request priority
        // (next, then prev, then center) so the CENTER — the frame the
        // merge is producing right now — ends MOST recently used and the
        // eviction candidate is always a frame that left the window's
        // reach, never a window member.
        for (i in fromCache.indices.reversed()) {
            lru[fromCache[i]] = Unit
        }
        // Rebuild the queue from THIS window's misses, in request
        // priority: entries the new window abandoned never re-enter —
        // the rebuild IS the cancellation — and the center queues ahead
        // of its own neighbors.
        pending.clear()
        for (frame in misses) {
            pending.addLast(frame)
        }
        val starts = drain()
        return WindowPlan(
            center = center,
            fromCache = fromCache,
            decoding = decoding,
            startDecoding = starts,
            queuedCount = pending.size,
        )
    }

    /**
     * The decode of [frame] finished: it leaves the in-flight census and
     * enters the LRU cache (evicting the least-recently-used frame past
     * capacity, reported via [onEvict]). Returns the frames to start
     * now — the freed slot is offered to the queue head, under the cap.
     * Only a frame this planner started may be finished (the planner is
     * the only source of start decisions; a finish for anything else is
     * a wiring bug and fails fast here).
     */
    fun onDecodeFinished(frame: Int): List<Int> {
        check(frame in inFlight) { "finished a frame that was not started: $frame" }
        inFlight.remove(frame)
        lru[frame] = Unit
        while (lru.size > cacheCapacity) {
            val eldest = lru.keys.iterator().next()
            lru.remove(eldest)
            evictions++
            onEvict(eldest)
        }
        return drain()
    }

    /** Hands queued frames to free slots, never past [maxConcurrent]. */
    private fun drain(): List<Int> {
        val starts = ArrayList<Int>(maxConcurrent)
        while (pending.isNotEmpty() && inFlight.size < maxConcurrent) {
            val frame = pending.removeFirst()
            // Invariant guard, unreachable by construction (the queue is
            // rebuilt from the window's missing frames, and only drain
            // moves a frame from queue to in-flight): if a queued frame
            // ever were already satisfied, dropping it here is the no-leak
            // behavior — it must not consume a slot or a decode.
            if (frame in inFlight || lru.containsKey(frame)) continue
            inFlight[frame] = Unit
            starts.add(frame)
        }
        return starts
    }

    /** Decode jobs in flight right now (never above [maxConcurrent]). */
    fun inFlightCount(): Int = inFlight.size

    /** Queued, not-started jobs (all belong to the most recent window). */
    fun queuedCount(): Int = pending.size

    /** Decoded frames, least- to most-recently used. */
    fun cachedFrames(): List<Int> = lru.keys.toList()

    /** Frames currently in flight, in start order. */
    fun inFlightFrames(): List<Int> = inFlight.keys.toList()

    /** True when [frame] is decoded and resident. */
    fun isCached(frame: Int): Boolean = lru.containsKey(frame)

    /**
     * Drops all state: queue, in-flight census, cache, and rejects nothing
     * about a later [planWindow] (the first window after a reset is cold).
     * The wiring calls this when a clip closes or the export is cancelled
     * outright; tearing down the real decoder is the caller's job.
     */
    fun reset() {
        pending.clear()
        inFlight.clear()
        lru.clear()
    }

    companion object {
        /**
         * Frames the cache keeps by default: the working window (prev,
         * center, next = 3) plus lookahead slack for the walk. Three is
         * the theoretical minimum for a pure forward walk (the window's
         * three frames plus the incoming NEXT — with exactly 3, the frame
         * leaving reach evicts precisely as the incoming NEXT arrives);
         * the default adds slack so small backward jitter (a re-rendered
         * window, a ±2 scrub) stays cache hits. Matches the core
         * ring-cache intent ("a small ring cache ... so sequential
         * playback amortizes to ~1 decode/frame").
         */
        const val DEFAULT_CACHE_CAPACITY = 6

        /**
         * One decoder: `ExportEngine` runs every native call on a single
         * thread ("One motioncam decoder is not concurrency-safe"), so
         * decode jobs execute one at a time. The cap still matters
         * because the START decision — what the single worker does next
         * and how much may sit started-but-unfinished — is a real
         * decision the tests pin; a future multi-decoder setup changes
         * the parameter, not this class.
         */
        const val DEFAULT_MAX_CONCURRENT = 1
    }
}
