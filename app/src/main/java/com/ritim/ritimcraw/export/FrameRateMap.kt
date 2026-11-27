package com.ritim.ritimcraw.export

import kotlin.math.roundToInt

/**
 * The frame-nearest fps conversion (E1f-1, plan E1F §4) — PURE mapping
 * math, no Android imports, JVM-testable in the PtsTimeline pattern.
 *
 * MODEL (plan §2.1, option (a)): output frame j sits on the TARGET's
 * constant grid at wall time t_j = j * den / num seconds and shows the
 * SOURCE frame nearest to t_j:
 *
 *   i(j) = round( (startUs + j * 1e6 * den / num) * sourceFps / 1e6 )
 *        = round( (startUs + t_j_us) * sourceFps / 1e6 )   // t_j rational, never a pre-rounded int
 *
 * clamped to [0, sourceFrames - 1]. Target < source drops frames, target
 * > source duplicates them, equal rates are the identity (in the mapping;
 * the pts still land on the exact grid). Real time is preserved because
 * BOTH grids are measured on the same wall clock — only rounding, never
 * resampling, decides the frame (the timeline walk's own rule,
 * TimelineExport: round(pos * sourceFps / projectFps) — plan §4.1 pins the
 * algebraic parity as a test).
 *
 * THE ONE FORBIDDEN ARITHMETIC (plan §2.4): scaling content by a rate
 * RATIO (source frame j at output j, "29.83 x 30/29.83 = 30") is NOT what
 * this object does. That drifts audio against video by
 * duration * (1 - sourceFps/targetFps) — 0.45 s at the end of the 79 s
 * 29.83 clip. The conversion is a VIDEO SAMPLING map; wall-clock pts are
 * preserved and audio pts are never touched (E1f-2's contract).
 *
 * COUNT RULE (plan §4.2, the brief's ceil CORRECTED): with
 * spanUs = lastPts - firstPts (the n-1 intervals),
 *
 *   lastJ = floor( spanUs * num / (den * 1e6) );  outputFrames = lastJ + 1
 *
 * ceil is WRONG at the whole-number boundary: at identity 30->30 with
 * span=(N-1)/30 s, ceil(span*30) = N-1 drops the last source frame; and
 * at span=0 (a single frame) ceil(0) = 0 drops the ONLY frame. floor+1
 * gives N at the exact boundary, 1 for a single frame, and never adds a
 * short tail frame past the span.
 *
 * GAPPIE SOURCES (plan §4.3): the map counts the source UNIFORM at its
 * measured rate ((N-1)/span — the ExportFps.fromPts / PtsTimeline
 * .measuredFps algebra; never a snapped standard value). A real 67 ms gap
 * inside an otherwise-CFR clip holds one source frame for ~2 output
 * frames around the gap (a held frame, not a drift) and the tail clamps
 * to N-1.
 */
object FrameRateMap {

    /** One conversion: the source's measured facts against a chosen target. */
    data class Mapping(
        /** Output frame j's source frame index, in order (rounded, clamped). */
        val sourceFrames: List<Int>,
        /** The output grid pts in us: pts_j = round(j * 1e6 * den / num) + startUs. */
        val ptsUs: List<Long>,
        /** Source frames the mapping never shows (target < source). */
        val drops: Int,
        /** Extra showings beyond one per source frame (target > source). */
        val duplicates: Int,
    ) {
        val outputFrames: Int get() = sourceFrames.size
    }

    /**
     * The conversion of a source measured at [sourceFps] with
     * [sourceFrames] real frames and a [spanUs] presentation span
     * (lastPts - firstPts) onto [target]. [TargetFps.SOURCE] here is a
     * caller bug (requirement failure): SOURCE means "no conversion", the
     * caller must not map. [startUs] is the source's first pts on the
     * shared wall clock (0 for single-clip exports; kept for the
     * timeline-parity algebra).
     */
    fun map(
        target: TargetFps,
        sourceFps: Double,
        sourceFrames: Int,
        spanUs: Long,
        startUs: Long = 0L,
    ): Mapping {
        require(target != TargetFps.SOURCE) { "SOURCE means no conversion; the caller must not map" }
        require(sourceFps > 0.0) { "source fps $sourceFps is not measurable" }
        require(sourceFrames >= 1) { "source frame count $sourceFrames is not real" }
        require(spanUs >= 0L) { "span $spanUs us is negative" }

        // The count rule (plan §4.2): the last grid point NOT past the span.
        val lastJ = Math.floor(spanUs.toDouble() * target.num / (target.den * 1_000_000.0)).toLong().coerceAtLeast(0L).toInt()

        val src = ArrayList<Int>(lastJ.toInt() + 1)
        val pts = ArrayList<Long>(lastJ.toInt() + 1)
        for (j in 0..lastJ) {
            pts += gridPtsUs(j, target, startUs)
            // One rounding through the EXACT rational t_j — a pre-rounded
            // integer t_j is forbidden (plan §4.5: the two-step path picks
            // a different frame for some j at the 29.97 target).
            val tUs = j.toDouble() * 1_000_000.0 * target.den / target.num
            val i = ((startUs.toDouble() + tUs) * sourceFps / 1_000_000.0).roundToInt()
            src += i.coerceIn(0, sourceFrames - 1)
        }

        // Drop/duplicate bookkeeping over the chosen sequence: a source
        // frame never shown is a drop; every showing after a frame's
        // first within its own run is a duplicate.
        val shown = HashSet<Int>()
        var dups = 0
        var prev = Int.MIN_VALUE
        var runLen = 0
        for (f in src) {
            shown += f
            if (f == prev) {
                runLen++
                if (runLen > 1) dups++
            } else {
                prev = f
                runLen = 1
            }
        }
        val drops = sourceFrames - shown.size
        return Mapping(src, pts, drops, dups)
    }

    /**
     * The target grid pts of output frame [j] (us): ONE rounding of the
     * exact rational, round(j * 1e6 * den / num), + [startUs]. Integer
     * arithmetic with the .5-up tie rule (numerator + num/2 / num), so a
     * 3000-frame 29.97 grid walks in 33366/33367 us steps with |error| <
     * 0.5 us and never drifts. [startUs] shifts the whole grid onto the
     * shared wall clock (0 for single-clip exports).
     */
    fun gridPtsUs(j: Int, target: TargetFps, startUs: Long = 0L): Long {
        val numer = j.toLong() * 1_000_000L * target.den
        return (numer + target.num / 2L) / target.num + startUs
    }
}
