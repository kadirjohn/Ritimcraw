package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * FrameRateConvertPlan tests (E-brief E3): the pure plan layer over
 * FrameRateMap — the drop/duplicate recipe, the §2.4 A/V skew analysis
 * and the fractional rates 23.976/29.97, all on the plan §6 case shapes.
 */
class FrameRateConvertPlanTest {

    /** The map's own cumulative drift is 0 (never the ratio number). */
    private fun assertZeroDrift(p: FrameRateConvertPlan.Plan) {
        assertTrue("skewWithinLimit is the map's zero-drift contract", p.skewWithinLimit)
    }

    // ---- SOURCE = no conversion ----

    @Test
    fun source_means_no_conversion_identity() {
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 29.83, frames = 30, spanUs = 974_611L),
            TargetFps.SOURCE,
        )
        assertTrue(!p.converting)
        assertEquals((0 until 30).toList(), p.mapping.sourceFrames)
        assertEquals(0.0, p.skewSec, 0.0)
        assertEquals(0.0, p.maxJitterSec, 0.0)
        assertEquals(0, p.mapping.drops)
        assertEquals(0, p.mapping.duplicates)
    }

    // ---- the §2.4 trap scenario: the forbidden ratio scaling's number ----

    @Test
    fun the_ratio_scale_skew_of_the_real_79s_clip_is_about_0_45s() {
        // Plan §2.4 / the E1c report's trap scenario: "29.83 x 30/29.83"
        // drifts the end of a 79 s clip by ~0.45 s (0.00567 x 79). The
        // number is computed and PINNED as REFUSED — the map's own
        // approach never applies it.
        val skew = FrameRateConvertPlan.ratioScaleSkewSec(79.0, 29.83, TargetFps.FPS_30)
        assertEquals(0.4474, skew, 0.001)
        assertTrue("the pinned figure is ~0.45 s", skew in 0.44..0.46)
        // The plan carries the refused number as INFORMATION and the
        // zero-drift verdict as the contract (§2.4's correct attitude).
        val span = Math.round(79.0 * 1_000_000.0)
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 29.83, frames = 2357, spanUs = span),
            TargetFps.FPS_30,
        )
        assertTrue(p.skewSec in 0.44..0.46) // the reported trap figure
        assertZeroDrift(p) // the approach's own verdict: zero cumulative
    }

    @Test
    fun the_maps_jitter_is_half_a_source_period_never_accumulating() {
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 29.83, frames = 2357, spanUs = 79_000_000L),
            TargetFps.FPS_30,
        )
        // ±half a source period, per frame — the §2.4 honest bound.
        assertEquals(0.5 / 29.83, p.maxJitterSec, 1e-12)
        assertTrue(p.maxJitterSec < 0.017)
        assertZeroDrift(p)
    }

    // ---- fractional rates: 23.976 and the exact 29.97 rational ----

    @Test
    fun fractional_23_976_to_30_plans_the_duplication_recipe() {
        val span = Math.round(239 * 1_000_000.0 / 30.0)
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 23.976, frames = 192, spanUs = span),
            TargetFps.FPS_30,
        )
        assertTrue(p.converting)
        assertEquals(240, p.mapping.outputFrames)
        assertTrue(p.mapping.duplicates in 44..50)
        assertEquals(0, p.mapping.drops)
        // The output pts walk the EXACT 30-grid; the drift verdict is 0.
        assertEquals(0L, p.mapping.ptsUs[0])
        assertEquals(33_333L, p.mapping.ptsUs[1])
        assertZeroDrift(p)
    }

    @Test
    fun ntsc_29_97_target_carries_the_exact_rational_grid() {
        // The target is 30000/1001 exactly: the grid steps are
        // 33366/33367 us and the label's 29.97 decimal never enters.
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 30.0, frames = 3000, spanUs = 99_900_000L),
            TargetFps.FPS_29_97,
        )
        assertTrue(p.converting)
        val steps = p.mapping.ptsUs.zipWithNext { a, b -> b - a }.toSet()
        assertTrue("steps $steps", steps.all { it == 33_366L || it == 33_367L })
        assertZeroDrift(p)
    }

    @Test
    fun a_single_frame_source_plans_one_output_frame() {
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 29.83, frames = 1, spanUs = 0L),
            TargetFps.FPS_60,
        )
        assertEquals(1, p.mapping.outputFrames)
        assertEquals(listOf(0), p.mapping.sourceFrames)
    }

    @Test
    fun the_recipe_is_monotone_and_within_bounds_on_every_ladder_target() {
        val src = FrameRateConvertPlan.Source(fps = 29.83, frames = 900, spanUs = 30_138_914L)
        for (t in TargetFps.entries.filter { it != TargetFps.SOURCE }) {
            val p = FrameRateConvertPlan.plan(src, t)
            var prev = -1
            for (f in p.mapping.sourceFrames) {
                assertTrue("$t: frame $f out of bounds", f in 0..899)
                assertTrue("$t: not monotone at $f", f >= prev)
                prev = f
            }
            assertZeroDrift(p)
        }
    }

    // ---- honesty: bad inputs refused ----

    @Test(expected = IllegalArgumentException::class)
    fun a_non_positive_source_fps_is_refused() {
        FrameRateConvertPlan.plan(FrameRateConvertPlan.Source(0.0, 10, 1_000_000L), TargetFps.FPS_30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ratio_skew_of_source_is_a_caller_bug() {
        FrameRateConvertPlan.ratioScaleSkewSec(79.0, 29.83, TargetFps.SOURCE)
    }

    @Test
    fun the_duration_rule_matches_the_last_pts_plus_one_period() {
        // run.durationSec = lastPts/1e6 + 1/fps (Mp4Exporter's own rule):
        // the plan's output span is the last grid point + one target
        // period — the recipe never adds a short tail frame (§4.2).
        val p = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(fps = 30.0, frames = 90, spanUs = Math.round(89 * 1_000_000.0 / 30.0)),
            TargetFps.FPS_25,
        )
        // floor(2_966_667*25/1e6) = floor(74.17) = 74 -> 75 output frames;
        // the last grid point sits inside the span, no short tail frame.
        assertEquals(75, p.mapping.outputFrames)
    }
}
