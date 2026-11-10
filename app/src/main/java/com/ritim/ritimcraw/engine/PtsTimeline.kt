package com.ritim.ritimcraw.engine

/**
 * Pure playback math over the clip's real MCRAW frame timestamps
 * (owner request 2026-11-10 section 1: playback follows the recorded
 * timestamps, not a fixed fps). JVM-testable without the native lib.
 */
object PtsTimeline {

    /**
     * The frame whose ptsUs is closest to [elapsedUs], the wall time since
     * playback started (measured from the PAUSE POSITION's pts, so resuming
     * continues from the recorded timeline). Monotonic scan; ties resolve to
     * the earlier frame. Frames whose ptsUs is 0 (missing metadata) sort as
     * the earliest and are only picked when nothing else is closer.
     */
    fun frameAt(ptsUs: LongArray, elapsedUs: Long): Int {
        if (ptsUs.isEmpty()) return -1
        var best = 0
        var bestDist = Long.MAX_VALUE
        for (i in ptsUs.indices) {
            val d = kotlin.math.abs(ptsUs[i] - elapsedUs)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * Normalized elapsed for playback starting at frame [fromIndex]: the
     * wall clock [nowUs] since playback start, offset onto the recorded
     * timeline so frame fromIndex is the origin.
     */
    fun elapsedAt(ptsUs: LongArray, fromIndex: Int, nowUs: Long): Long {
        if (ptsUs.isEmpty()) return 0L
        val clamped = fromIndex.coerceIn(0, ptsUs.size - 1)
        return ptsUs[clamped] + nowUs
    }

    /**
     * Total duration of the clip's recorded timeline: last frame pts minus
     * first frame pts (0 for a single frame; the transport shows 00:00:00:00).
     */
    fun totalDurationUs(ptsUs: LongArray): Long {
        if (ptsUs.size < 2) return 0L
        val last = ptsUs.last()
        val first = ptsUs.first()
        return if (last > first) last - first else 0L
    }

    /**
     * Elapsed played within the clip for the transport readout: frame
     * [index]'s pts relative to the first frame, clamped to the total.
     */
    fun positionUs(ptsUs: LongArray, index: Int): Long {
        if (ptsUs.isEmpty()) return 0L
        val clamped = index.coerceIn(0, ptsUs.size - 1)
        val rel = ptsUs[clamped] - ptsUs.first()
        return rel.coerceIn(0L, totalDurationUs(ptsUs))
    }

    /**
     * Slider fraction -> frame index for seeking (M3 Slider gives a 0..1
     * fraction; the transport maps it onto the frame grid).
     */
    fun frameForFraction(ptsUs: LongArray, fraction: Float): Int {
        if (ptsUs.isEmpty()) return -1
        val f = fraction.coerceIn(0f, 1f)
        return kotlin.math.round(f * (ptsUs.size - 1).toFloat()).toInt()
    }

    /**
     * Frame index -> slider fraction (inverse of [frameForFraction]).
     */
    fun fractionForFrame(ptsUs: LongArray, index: Int): Float {
        if (ptsUs.size < 2) return 0f
        val clamped = index.coerceIn(0, ptsUs.size - 1)
        return clamped.toFloat() / (ptsUs.size - 1).toFloat()
    }

    /**
     * Delay until the NEXT frame's pts is due, given the current frame
     * [index] and playback-origin data. Negative/zero means the next frame
     * is already late — play it immediately (drop-late semantics: playback
     * never queues a backlog, it always jumps to the frame the wall clock
     * says is current).
     */
    fun delayUntilNextUs(ptsUs: LongArray, index: Int, elapsedUs: Long): Long {
        if (ptsUs.isEmpty() || index < 0 || index + 1 >= ptsUs.size) return -1L
        return ptsUs[index + 1] - elapsedUs
    }
}
