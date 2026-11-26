package com.ritim.ritimcraw.ui.editor

/**
 * Counts what ONE seek-bar drag asked of the scrub-frames cache (F2-C3
 * device diagnosis, owner report 2026-11-26: on device only the
 * `ritim-trace` render lines exist — the small cached frames a drag
 * serves leave NO trace, so whether the feature engages at all and at
 * what frames/sec cannot be measured). The window is the seek-drag
 * gesture: it opens at the slider's DragStarted and closes at the
 * drag-end SETTLE publish — the render that replaces the parked frame
 * (ScrubFramesPolicy.settleAfterDrag: the scrub frame never survives
 * the drag). That close is also the throttle, the PanelAnimTrace
 * convention: ONE summary line per completed gesture, nothing per
 * frame, so even a rapid drag-dance cannot flood logcat.
 *
 * The asks are DEDUPED by consecutive frame index: a micro-move inside
 * one frame's fraction range re-asks the index just asked, and neither
 * the ask nor its outcome counts anywhere — the picture did not
 * change. requested therefore reads the distinct consecutive frames
 * the drag swept, and hit + miss == requested is structural: both
 * resolve through the same dedupe gate, every counted ask landing in
 * exactly one of them.
 *
 *  - hit:  the facade served a forward-or-same cached frame
 *    (ScrubFramesPolicy.shouldServe) — the small frame swapped in and
 *    the real render was SKIPPED for that step;
 *  - miss: a facade Miss, or a behind hit the monotonic seek bar
 *    must not serve — both fall back to the existing drag-tier
 *    render, whose own `class=drag` ritim-trace lines carry each
 *    fallback render's cost.
 *
 * Pure class, NO Compose and NO Android imports (the PanelAnimTrace
 * precedent): the clock is INJECTED (a nowMs per window edge), the
 * summary line is RETURNED, and the screen decides whether to log it.
 * The counts are plain ints on a non-state object: no recomposition,
 * no invalidation. Every hook site reads RenderTrace.enabled (=
 * BuildConfig.DEBUG) AND the screen's scrub holder != null BEFORE
 * touching this object — the holder exists only when the scrubFrames
 * setting is on, so with the setting OFF the object is never touched
 * at all (no line, no code-path change, output and timing identical),
 * and in release the compile-time false dead-codes every hook the
 * same way the existing RenderTrace call sites are dead.
 *
 * THE LINE (stable grep format, the ritim-trace family):
 *   scrub drag dur_ms=1243 requested=42 hit=17 miss=25
 *   served_min=320x180 served_max=320x240 settle_ms=87
 *
 *  - dur_ms:     drag length, DragStarted -> finger lift;
 *  - requested:  distinct consecutive frames asked of the cache;
 *  - hit / miss: the ask split — hit > 0 is the engagement verdict;
 *  - served_min / served_max: smallest / largest frame actually
 *                served, as WxH (0x0 when nothing was served — the
 *                cached path never engaged for this gesture);
 *  - settle_ms:  finger lift -> the drag-end settle render's PUBLISH
 *                (the parked frame's real 1280-tier frame on screen;
 *                the release-to-full-frame latency).
 *
 * Device readout:
 *   adb logcat -s ritim-trace       (live)
 *   adb logcat -d -s ritim-trace    (dump after a run)
 * The sweep rate is requested / dur_ms (the example: 42 asks over
 * 1243 ms is ~34 frames/s); the served rate is hit / dur_ms. A warm
 * cache reads miss near zero once the background walk has filled
 * around the drag; a cold first drag reads mostly miss until the
 * walk lands.
 */
class ScrubDragTrace {

    /** True between onDragStart and the settle publish that closes the window. */
    private var open = false

    /** True between onDragEnd and the settle publish (asks stop counting). */
    private var awaitingSettle = false
    private var startedAtMs = 0L
    private var releasedAtMs = 0L

    /** The frame the release parked — the settle must render THIS frame. */
    private var releasedFrameIndex = -1
    private var lastAskedFrameIndex = Int.MIN_VALUE
    private var requested = 0
    private var hits = 0
    private var misses = 0
    private var minServedPx = 0
    private var minServedW = 0
    private var minServedH = 0
    private var maxServedPx = 0
    private var maxServedW = 0
    private var maxServedH = 0

    /**
     * The seek drag started at [nowMs]; the counts reset (a fresh
     * burst). An interrupted gesture's line is deliberately lost here
     * — a new start resets, exactly the PanelAnimTrace convention.
     */
    fun onDragStart(nowMs: Long) {
        open = true
        awaitingSettle = false
        startedAtMs = nowMs
        requested = 0
        hits = 0
        misses = 0
        minServedPx = 0
        minServedW = 0
        minServedH = 0
        maxServedPx = 0
        maxServedW = 0
        maxServedH = 0
        releasedFrameIndex = -1
        lastAskedFrameIndex = Int.MIN_VALUE
    }

    /**
     * A cache ask for [frameIndex] resolved to a SERVED frame
     * ([width]x[height]). A re-ask of the index just asked (a
     * micro-move inside one frame's range) counts nowhere.
     */
    fun onHit(frameIndex: Int, width: Int, height: Int) {
        if (!countAsk(frameIndex)) return
        hits++
        val px = width * height
        if (px > 0) {
            if (minServedPx == 0 || px < minServedPx) {
                minServedPx = px
                minServedW = width
                minServedH = height
            }
            if (px > maxServedPx) {
                maxServedPx = px
                maxServedW = width
                maxServedH = height
            }
        }
    }

    /**
     * A cache ask for [frameIndex] fell back to the real render (a
     * facade Miss or a behind hit). Same dedupe rule as [onHit].
     */
    fun onMiss(frameIndex: Int) {
        if (!countAsk(frameIndex)) return
        misses++
    }

    /**
     * The finger lifted at [nowMs], parking [frameIndex]. The window
     * stays open awaiting the settle publish — the gesture's story
     * ends when the parked frame's real render lands on screen.
     */
    fun onDragEnd(nowMs: Long, frameIndex: Int) {
        if (!open) return
        releasedAtMs = nowMs
        releasedFrameIndex = frameIndex
        awaitingSettle = true
    }

    /**
     * A successful SETTLE render of [frameIndex] published at [nowMs].
     * When it is the released frame, the window closes and the summary
     * line returns — or null when no window was open, the render is a
     * superseded leftover (frame mismatch), or the gesture asked the
     * cache nothing (a precision-scrub gesture rides the same slider;
     * a requested=0 line would be noise).
     */
    fun onSettlePublished(nowMs: Long, frameIndex: Int): String? {
        if (!open || !awaitingSettle || frameIndex != releasedFrameIndex) return null
        open = false
        awaitingSettle = false
        if (requested == 0) return null
        val durMs = (releasedAtMs - startedAtMs).coerceAtLeast(0)
        val settleMs = (nowMs - releasedAtMs).coerceAtLeast(0)
        return "scrub drag dur_ms=$durMs requested=$requested hit=$hits " +
            "miss=$misses served_min=${minServedW}x${minServedH} " +
            "served_max=${maxServedW}x${maxServedH} settle_ms=$settleMs"
    }

    /**
     * The dedupe gate: a NEW frame ask (window open, not yet released,
     * index differs from the previous ask) counts once; repeats are
     * free. Returns whether the ask was counted.
     */
    private fun countAsk(frameIndex: Int): Boolean {
        if (!open || awaitingSettle || frameIndex == lastAskedFrameIndex) return false
        lastAskedFrameIndex = frameIndex
        requested++
        return true
    }
}
