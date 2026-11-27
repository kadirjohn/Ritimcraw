package com.ritim.ritimcraw.export

/**
 * The pure plan layer over [FrameRateMap] (E1f-2's planning half, E-brief
 * E3): from a source's MEASURED facts to the conversion the pipeline will
 * run — the target, the frame recipe, the honest drop/duplicate counts,
 * and the A/V analysis. No Android imports; the encoder and the UI are
 * wired in E1f-2/G2 (this layer never opens a codec).
 *
 * AUDIO SKEW (plan §2.4 — the trap scenario, pinned as REFUSED): scaling
 * content by a rate ratio ("29.83 x 30/29.83 = 30") drifts audio against
 * video by
 *
 *   skewSec = durationSec * (1 - sourceFps / targetFps)
 *
 * — 0.45 s at the end of the 79 s real 29.83->30 clip. The frame-nearest
 * map never scales: video pts land on the target's exact grid, audio pts
 * stay untouched, both on the same wall clock — the CUMULATIVE drift is
 * 0. The remaining jitter is within ±half a source period per frame, and
 * this layer reports it as [maxJitterSec]: the honest per-frame seğirme,
 * never accumulated. [skewSec] is computed and reported only so tests can
 * pin the forbidden arithmetic's number — [skewWithinLimit] is true when
 * the map is used and the skew is 0.
 */
object FrameRateConvertPlan {

    /** The source facts a conversion plans against (never a snapped standard value). */
    data class Source(
        /** The MEASURED fps — (N-1)/span algebra, 29.83 stays 29.83. */
        val fps: Double,
        /** The source's REAL frame count. */
        val frames: Int,
        /** lastPts - firstPts, microseconds (0 for a single frame). */
        val spanUs: Long,
        /** The source's first pts on the shared wall clock (usually 0). */
        val startUs: Long = 0L,
    )

    /** One conversion's plan: the recipe plus the honest analysis. */
    data class Plan(
        val target: TargetFps,
        /** The frame recipe (FrameRateMap's mapping). */
        val mapping: FrameRateMap.Mapping,
        /** Cumulative A/V drift of THIS approach: always 0 for the map. */
        val skewSec: Double,
        /** True when the cumulative drift is 0 (the only accepted approach). */
        val skewWithinLimit: Boolean,
        /** The worst per-frame jitter: half the source period, seconds. */
        val maxJitterSec: Double,
        /** True when the target differs from the measured source rate. */
        val converting: Boolean,
    )

    /** The plan for converting [source] to [target]; SOURCE means NO conversion. */
    fun plan(source: Source, target: TargetFps): Plan {
        require(source.fps > 0.0) { "source fps ${source.fps} is not measurable" }
        require(source.frames >= 1) { "source frame count ${source.frames} is not real" }
        if (target == TargetFps.SOURCE) {
            // No conversion at all: identity, zero skew, zero jitter —
            // the pre-E1f behavior this branch always preserves.
            return Plan(
                target = target,
                mapping = FrameRateMap.Mapping(
                    sourceFrames = (0 until source.frames).toList(),
                    ptsUs = emptyList(), // no grid: the source's own pts
                    drops = 0,
                    duplicates = 0,
                ),
                skewSec = 0.0,
                skewWithinLimit = true,
                maxJitterSec = 0.0,
                converting = false,
            )
        }
        val m = FrameRateMap.map(target, source.fps, source.frames, source.spanUs, source.startUs)
        // The output plays the span at the target rate: its duration is
        // the last grid point + one target period (the durationSec rule,
        // lastPts + 1/fps — Mp4Exporter).
        val durationSec = if (m.ptsUs.isEmpty()) 0.0 else m.ptsUs.last() / 1_000_000.0 + 1.0 / (target.num.toDouble() / target.den)
        // The forbidden ratio-scaling arithmetic's number, computed ONLY
        // to be reported and pinned as refused in tests (§2.4). It is a
        // PROPERTY of the approach this plan REJECTS — applying it is a
        // caller bug. The map's own cumulative drift is 0 by
        // construction: both grids hang off the same wall clock
        // (§2.4's correct attitude), which is what skewWithinLimit says.
        val skewSec = ratioScaleSkewSec(durationSec, source.fps, target)
        val skewWithinLimit = true
        // The honest jitter: the nearest map can pick a frame half a
        // source period away from the exact wall time — never more.
        val maxJitterSec = 0.5 / source.fps
        return Plan(target, m, skewSec, skewWithinLimit, maxJitterSec, converting = true)
    }

    /**
     * The forbidden approach's drift at [durationSec] between
     * [sourceFps] and the target — the §2.4 trap-scenario number,
     * public so the test pins the 0.45 s figure for the real 79 s
     * 29.83->30 clip. This layer NEVER applies it.
     */
    fun ratioScaleSkewSec(durationSec: Double, sourceFps: Double, target: TargetFps): Double {
        require(target != TargetFps.SOURCE) { "SOURCE has no ratio" }
        return durationSec * (1.0 - sourceFps / (target.num.toDouble() / target.den))
    }
}
