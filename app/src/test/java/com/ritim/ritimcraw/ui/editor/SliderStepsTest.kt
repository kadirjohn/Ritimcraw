package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slider step and reset tests (DESIGN.md Inputs: 0.1 EV exposure steps, 50 K
 * temperature steps, double-tap resets the control to its default).
 */
class SliderStepsTest {

    // ---- step semantics ----

    @Test
    fun exposure_snaps_to_tenth_ev() {
        val g = SliderSteps.snap(GradeK(exposureEv = 1.234f))
        assertEquals(1.2f, g.exposureEv, 1e-4f)
    }

    @Test
    fun exposure_snaps_negative_values() {
        val g = SliderSteps.snap(GradeK(exposureEv = -0.76f))
        assertEquals(-0.8f, g.exposureEv, 1e-4f)
    }

    @Test
    fun temperature_snaps_to_50k() {
        val g = SliderSteps.snap(GradeK(tempK = 5623f))
        assertEquals(5600f, g.tempK, 1e-3f)
    }

    @Test
    fun temperature_snaps_half_way_up() {
        val g = SliderSteps.snap(GradeK(tempK = 5625.4f))
        assertEquals(5650f, g.tempK, 1e-3f)
    }

    @Test
    fun highlights_and_tint_snap_to_whole_numbers() {
        val g = SliderSteps.snap(GradeK(highlights = 12.7f, tint = -3.2f))
        assertEquals(13f, g.highlights, 1e-4f)
        assertEquals(-3f, g.tint, 1e-4f)
    }

    @Test
    fun tone_controls_snap_to_whole_numbers() {
        val g = SliderSteps.snap(
            GradeK(
                saturation = 33.6f,
                blackPoint = -12.4f,
                whitePoint = 87.5f,
                shadows = -50.7f,
                midtones = 21.2f,
                contrast = -8.6f,
            )
        )
        assertEquals(34f, g.saturation, 1e-4f)
        assertEquals(-12f, g.blackPoint, 1e-4f)
        assertEquals(88f, g.whitePoint, 1e-4f)
        assertEquals(-51f, g.shadows, 1e-4f)
        assertEquals(21f, g.midtones, 1e-4f)
        assertEquals(-9f, g.contrast, 1e-4f)
    }

    @Test
    fun already_snapped_values_stay_put() {
        val g = SliderSteps.snap(
            GradeK(
                exposureEv = 0.5f,
                highlights = -20f,
                tempK = 3200f,
                tint = 7f,
                saturation = 40f,
                blackPoint = -10f,
                whitePoint = 15f,
                shadows = -25f,
                midtones = 5f,
                contrast = 60f,
            )
        )
        assertEquals(0.5f, g.exposureEv, 1e-4f)
        assertEquals(-20f, g.highlights, 1e-4f)
        assertEquals(3200f, g.tempK, 1e-3f)
        assertEquals(7f, g.tint, 1e-4f)
        assertEquals(40f, g.saturation, 1e-4f)
        assertEquals(-10f, g.blackPoint, 1e-4f)
        assertEquals(15f, g.whitePoint, 1e-4f)
        assertEquals(-25f, g.shadows, 1e-4f)
        assertEquals(5f, g.midtones, 1e-4f)
        assertEquals(60f, g.contrast, 1e-4f)
    }

    // ---- double-tap reset ----

    @Test
    fun double_tap_resets_exposure_only() {
        val current = GradeK(exposureEv = 2.5f, highlights = 30f, tempK = 4300f, tint = 5f)
        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.EXPOSURE, current)
        assertEquals(0f, reset.exposureEv, 0f)
        assertEquals(30f, reset.highlights, 0f)
        assertEquals(4300f, reset.tempK, 0f)
        assertEquals(5f, reset.tint, 0f)
    }

    @Test
    fun double_tap_resets_temperature_only() {
        val current = GradeK(exposureEv = -1f, highlights = 30f, tempK = 4300f, tint = 5f)
        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.TEMPERATURE, current)
        assertEquals(5600f, reset.tempK, 0f)
        assertEquals(-1f, reset.exposureEv, 0f)
        assertEquals(30f, reset.highlights, 0f)
        assertEquals(5f, reset.tint, 0f)
    }

    @Test
    fun double_tap_resets_highlights_only() {
        val current = GradeK(exposureEv = -1f, highlights = 30f, tempK = 4300f, tint = 5f)
        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.HIGHLIGHTS, current)
        assertEquals(0f, reset.highlights, 0f)
        assertEquals(-1f, reset.exposureEv, 0f)
        assertEquals(4300f, reset.tempK, 0f)
        assertEquals(5f, reset.tint, 0f)
    }

    @Test
    fun double_tap_resets_tint_only() {
        val current = GradeK(exposureEv = -1f, highlights = 30f, tempK = 4300f, tint = 5f)
        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.TINT, current)
        assertEquals(0f, reset.tint, 0f)
        assertEquals(-1f, reset.exposureEv, 0f)
        assertEquals(30f, reset.highlights, 0f)
        assertEquals(4300f, reset.tempK, 0f)
    }

    @Test
    fun reset_matches_gradek_defaults() {
        val defaults = GradeK()
        val reset = SliderSteps.resetToDefault(
            SliderSteps.Axis.EXPOSURE,
            GradeK(exposureEv = 3f, highlights = 99f, tempK = 9000f, tint = -99f),
        )
        assertEquals(defaults.exposureEv, reset.exposureEv, 0f)
    }

    @Test
    fun double_tap_resets_each_tone_control_only() {
        // One shared fixture, one axis per assertion: the reset must touch
        // ONLY the tapped control (every other axis keeps its value).
        val current = GradeK(
            exposureEv = -1f, highlights = 30f, tempK = 4300f, tint = 5f,
            saturation = 66f, blackPoint = -40f, whitePoint = 25f,
            shadows = -15f, midtones = 10f, contrast = 80f,
        )

        val sat = SliderSteps.resetToDefault(SliderSteps.Axis.SATURATION, current)
        assertEquals(0f, sat.saturation, 0f)
        assertEquals(-1f, sat.exposureEv, 0f)
        assertEquals(-40f, sat.blackPoint, 0f)

        val black = SliderSteps.resetToDefault(SliderSteps.Axis.BLACK_POINT, current)
        assertEquals(0f, black.blackPoint, 0f)
        assertEquals(66f, black.saturation, 0f)
        assertEquals(25f, black.whitePoint, 0f)

        val white = SliderSteps.resetToDefault(SliderSteps.Axis.WHITE_POINT, current)
        assertEquals(0f, white.whitePoint, 0f)
        assertEquals(-15f, white.shadows, 0f)
        assertEquals(80f, white.contrast, 0f)

        val shadows = SliderSteps.resetToDefault(SliderSteps.Axis.SHADOWS, current)
        assertEquals(0f, shadows.shadows, 0f)
        assertEquals(10f, shadows.midtones, 0f)

        val mid = SliderSteps.resetToDefault(SliderSteps.Axis.MIDTONES, current)
        assertEquals(0f, mid.midtones, 0f)
        assertEquals(80f, mid.contrast, 0f)

        val contrast = SliderSteps.resetToDefault(SliderSteps.Axis.CONTRAST, current)
        assertEquals(0f, contrast.contrast, 0f)
        assertEquals(-15f, contrast.shadows, 0f)
    }

    // ---- Compose steps argument ----

    @Test
    fun exposure_steps_covers_the_0_1_grid() {
        // -5..5 at 0.1 = 100 intervals -> 99 snap points between endpoints.
        assertEquals(99, SliderSteps.composeSteps(-5f..5f, SliderSteps.EXPOSURE_STEP))
    }

    @Test
    fun temperature_steps_covers_the_50k_grid() {
        // 2000..50000 at 50 K = 960 intervals -> 959 snap points.
        assertEquals(959, SliderSteps.composeSteps(2000f..50000f, SliderSteps.TEMP_STEP_K))
    }

    @Test
    fun unit_steps_cover_the_whole_number_grid() {
        // -100..100 at 1 = 200 intervals -> 199 snap points.
        assertEquals(199, SliderSteps.composeSteps(-100f..100f, SliderSteps.UNIT_STEP))
    }

    @Test
    fun non_positive_step_means_continuous_slider() {
        assertEquals(0, SliderSteps.composeSteps(0f..1f, 0f))
    }

    @Test
    fun range_smaller_than_step_is_continuous() {
        assertEquals(0, SliderSteps.composeSteps(0f..0.05f, 0.1f))
    }
}
