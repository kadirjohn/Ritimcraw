package com.ritim.ritimcraw.engine

/**
 * One summary line per playback run (P3, 2026-11-25): how smooth the editor
 * preview really was, measured with an INJECTED clock so it is JVM-testable.
 * The wall-clock playback loop picks the frame whose pts is closest to the
 * elapsed time (drop-late), so the story has four numbers:
 *
 *  - picked:  frames the loop chose to render (index changed);
 *  - dropped: frame indices the loop skipped over (a tick that jumped by
 *             more than one frame; the wall clock was ahead of the pipeline);
 *  - late:    picks whose due time had already passed by more than one frame
 *             period when the loop got to them;
 *  - shown:   playback renders that actually completed and were published,
 *             with the gap between consecutive published frames as p50/p95
 *             (the owner's "does it stutter" number: a 30 fps clip wants
 *             ~33 ms with a tight p95).
 *
 * Output is one line under the stable "ritim-trace" tag (see
 * [com.ritim.ritimcraw.engine.RenderTrace]); units are in the line.
 */
class PlaybackTrace(private val periodUs: Long) {
    private var picked = 0
    private var dropped = 0
    private var late = 0
    private var lastIdx = -1
    private var lastPublishUs = -1L
    private val gapsUs = ArrayList<Long>()

    /** The loop picked frame [idx] at wall [nowUs]; [dueUs] = when the clip's own timeline says it was due. */
    fun onPick(idx: Int, nowUs: Long, dueUs: Long) {
        if (lastIdx >= 0 && idx > lastIdx + 1) dropped += idx - lastIdx - 1
        if (nowUs - dueUs > periodUs) late++
        picked++
        lastIdx = idx
    }

    /** A playback render finished and its frame went on screen at [nowUs]. */
    fun onPublished(nowUs: Long) {
        if (lastPublishUs >= 0) gapsUs += nowUs - lastPublishUs
        lastPublishUs = nowUs
    }

    val shown: Int get() = if (lastPublishUs >= 0) gapsUs.size + 1 else 0
    val droppedCount: Int get() = dropped
    val lateCount: Int get() = late
    val pickedCount: Int get() = picked

    /** Nearest-rank percentile of the published-frame gaps in ms (0.0 when there are none). */
    fun gapPercentileMs(p: Double): Double {
        if (gapsUs.isEmpty()) return 0.0
        val s = gapsUs.sorted()
        val rank = Math.ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1] / 1000.0
    }

    /** The one summary line; [periodUs] is shown so the reader can compare the gaps to it. */
    fun summary(clip: String, tierMaxDim: Int): String = String.format(
        java.util.Locale.ROOT,
        "playback clip=%s tier=%d period=%.1fms picked=%d shown=%d late=%d dropped=%d gap_p50=%.1fms gap_p95=%.1fms",
        clip, tierMaxDim, periodUs / 1000.0, picked, shown, late, dropped,
        gapPercentileMs(50.0), gapPercentileMs(95.0),
    )
}
