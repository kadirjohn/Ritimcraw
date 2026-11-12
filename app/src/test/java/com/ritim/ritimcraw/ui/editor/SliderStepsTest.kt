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
                sharpness = 30f,
                detail = -15f,
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
        assertEquals(30f, g.sharpness, 1e-4f)
        assertEquals(-15f, g.detail, 1e-4f)
    }

    @Test
    fun sharpness_and_detail_snap_to_whole_numbers() {
        // COL-15: the Detail tab sliders are integer steps like the tone
        // controls (the native pipeline maps [-100, 100] -> amount).
        val g = SliderSteps.snap(GradeK(sharpness = 42.7f, detail = -8.3f))
        assertEquals(43f, g.sharpness, 1e-4f)
        assertEquals(-8f, g.detail, 1e-4f)
    }

    @Test
    fun denoise_percents_snap_to_whole_numbers() {
        // DN-01..08: both denoise sliders are percent 0..100 with integer
        // steps, like LUT Strength (the native stages take 0..100 and clamp
        // themselves).
        val g = SliderSteps.snap(GradeK(noiseReduction = 47.6f, chromaDenoise = 82.3f))
        assertEquals(48f, g.noiseReduction, 1e-4f)
        assertEquals(82f, g.chromaDenoise, 1e-4f)
    }

    @Test
    fun temporal_strength_snaps_to_whole_numbers() {
        // DN-05..DN-09: the Temporal NR strength slider is an integer 0..100
        // step like the other amount sliders.
        val g = SliderSteps.snap(GradeK(temporalNr = true, temporalStrength = 61.4f))
        assertEquals(61f, g.temporalStrength, 1e-4f)
        assertEquals(true, g.temporalNr) // the toggle is not a slider axis
    }

    @Test
    fun stabilization_strength_snaps_to_whole_numbers() {
        // Gyro Phase 5: the stabilization strength slider is an integer
        // 0..100 percent like the other amount sliders.
        val g = SliderSteps.snap(GradeK(stabilization = true, stabilizationStrength = 68.6f))
        assertEquals(69f, g.stabilizationStrength, 1e-4f)
        assertEquals(true, g.stabilization) // the toggle is not a slider axis
    }

    @Test
    fun stabilization_zoom_limit_snaps_to_0_05() {
        // Gyro Phase 5: the zoom limit slider is 1.0..1.5 at 0.05 steps.
        val g = SliderSteps.snap(GradeK(stabilization = true, stabilizationZoomLimit = 1.234f))
        assertEquals(1.25f, g.stabilizationZoomLimit, 1e-4f)
    }

    @Test
    fun stabilization_trim_ms_snaps_to_10ms() {
        // Gyro Phase 5: the sync trim slider is ±500 ms at 10 ms steps.
        val g = SliderSteps.snap(GradeK(stabilization = true, stabilizationTrimMs = -123f))
        assertEquals(-120f, g.stabilizationTrimMs, 1e-4f)
    }

    @Test
    fun stabilization_focal_mm_snaps_to_half_mm() {
        // Gyro Phase 5: the focal override slider snaps to half-mm steps.
        val g = SliderSteps.snap(GradeK(stabilization = true, stabilizationFocalMm = 23.7f))
        assertEquals(23.5f, g.stabilizationFocalMm, 1e-4f)
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

    @Test
    fun double_tap_resets_sharpness_and_detail_only() {
        // COL-15: each Detail slider resets independently — the tapped
        // control goes neutral, tonemap and every other axis stay put.
        val current = GradeK(
            exposureEv = 1f, sharpness = 55f, detail = -30f, tonemap = 2,
        )

        val sharpness = SliderSteps.resetToDefault(SliderSteps.Axis.SHARPNESS, current)
        assertEquals(0f, sharpness.sharpness, 0f)
        assertEquals(-30f, sharpness.detail, 0f)
        assertEquals(2, sharpness.tonemap)
        assertEquals(1f, sharpness.exposureEv, 0f)

        val detail = SliderSteps.resetToDefault(SliderSteps.Axis.DETAIL, current)
        assertEquals(0f, detail.detail, 0f)
        assertEquals(55f, detail.sharpness, 0f)
        assertEquals(2, detail.tonemap)
    }

    @Test
    fun double_tap_resets_denoise_controls_only() {
        // DN-01..08: each denoise slider resets independently to 0 (neutral
        // = the native stage skipped, the old look) — the tapped control goes
        // neutral, the OTHER denoise control and every other axis stay put.
        val current = GradeK(
            exposureEv = 1f, sharpness = 55f,
            noiseReduction = 70f, chromaDenoise = 35f,
        )

        val nr = SliderSteps.resetToDefault(SliderSteps.Axis.NOISE_REDUCTION, current)
        assertEquals(0f, nr.noiseReduction, 0f)
        assertEquals(35f, nr.chromaDenoise, 0f)
        assertEquals(55f, nr.sharpness, 0f)
        assertEquals(1f, nr.exposureEv, 0f)

        val cd = SliderSteps.resetToDefault(SliderSteps.Axis.CHROMA_DENOISE, current)
        assertEquals(0f, cd.chromaDenoise, 0f)
        assertEquals(70f, cd.noiseReduction, 0f)
        assertEquals(55f, cd.sharpness, 0f)
    }

    @Test
    fun double_tap_resets_temporal_strength_only() {
        // DN-05..DN-09: the strength slider resets to the GradeK default (0)
        // — NOT to the switch-on seed; the toggle and every other axis stay.
        val current = GradeK(
            exposureEv = 1f, sharpness = 55f, detail = -30f, tonemap = 2,
            temporalNr = true, temporalStrength = 80f,
        )

        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.TEMPORAL_STRENGTH, current)
        assertEquals(0f, reset.temporalStrength, 0f)
        assertEquals(true, reset.temporalNr)
        assertEquals(55f, reset.sharpness, 0f)
        assertEquals(2, reset.tonemap)
        assertEquals(1f, reset.exposureEv, 0f)
    }

    @Test
    fun double_tap_resets_each_stabilization_slider_only() {
        // Gyro Phase 5: each stabilization slider resets independently to
        // its GradeK default (50 / 1.1 / 0 / 0 — strength default 50 since
        // the 2026-11-12 tau redesign) — the tapped control goes to
        // its default, the OTHER stabilization controls, the toggle and
        // the mode stay put.
        val current = GradeK(
            exposureEv = 1f, sharpness = 55f,
            stabilization = true, stabilizationMode = 2,
            stabilizationStrength = 40f, stabilizationZoomLimit = 1.4f,
            stabilizationTrimMs = 80f, stabilizationFocalMm = 35f,
        )

        val strength = SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_STRENGTH, current)
        assertEquals(50f, strength.stabilizationStrength, 0f)
        assertEquals(1.4f, strength.stabilizationZoomLimit, 0f)
        assertEquals(2, strength.stabilizationMode)

        val zoom = SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_ZOOM_LIMIT, current)
        assertEquals(1.1f, zoom.stabilizationZoomLimit, 0f)
        assertEquals(40f, zoom.stabilizationStrength, 0f)
        assertEquals(80f, zoom.stabilizationTrimMs, 0f)

        val trim = SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_TRIM_MS, current)
        assertEquals(0f, trim.stabilizationTrimMs, 0f)
        assertEquals(35f, trim.stabilizationFocalMm, 0f)
        assertEquals(true, trim.stabilization)

        val focal = SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_FOCAL_MM, current)
        assertEquals(0f, focal.stabilizationFocalMm, 0f)
        assertEquals(80f, focal.stabilizationTrimMs, 0f)
        assertEquals(1f, focal.exposureEv, 0f)
    }

    // ---- Crop + straighten (RAW-09) ----

    @Test
    fun crop_axes_snap_to_the_grid_in_snap() {
        // The whole-grade snap covers the crop fields: fractions to the
        // 1 percent grid, straighten to the half-degree grid.
        val g = SliderSteps.snap(
            GradeK(cropX = 0.123f, cropY = 0.456f, cropW = 0.789f, cropH = 0.654f, straightenDeg = 3.24f)
        )
        assertEquals(0.12f, g.cropX, 1e-4f)
        assertEquals(0.46f, g.cropY, 1e-4f)
        assertEquals(0.79f, g.cropW, 1e-4f)
        assertEquals(0.65f, g.cropH, 1e-4f)
        // 3.24 is nearer 3.0 than 3.5 on the half-degree grid.
        assertEquals(3.0f, g.straightenDeg, 1e-4f)
    }

    @Test
    fun double_tap_resets_each_crop_slider_only() {
        // RAW-09: the tapped crop slider goes to its GradeK default
        // (0/0/1/1, 0 deg), every other axis stays. The W/H resets also
        // zero the matching origin — w = 1 with x = 0.3 would be an
        // invalid rect (x + w = 1.3), so "full width" pins the left
        // edge to the frame's.
        val current = GradeK(
            exposureEv = 1f, sharpness = 55f, tonemap = 2,
            cropAspect = 3,
            cropX = 0.3f, cropY = 0.2f, cropW = 0.5f, cropH = 0.6f,
            straightenDeg = 4.5f,
        )

        val x = SliderSteps.resetToDefault(SliderSteps.Axis.CROP_X, current)
        assertEquals(0f, x.cropX, 0f)
        assertEquals(0.5f, x.cropW, 0f)
        assertEquals(4.5f, x.straightenDeg, 0f)
        assertEquals(3, x.cropAspect)

        val y = SliderSteps.resetToDefault(SliderSteps.Axis.CROP_Y, current)
        assertEquals(0f, y.cropY, 0f)
        // cropX keeps its stored 0.3 — the Y reset touches only cropY.
        assertEquals(0.3f, y.cropX, 0f)
        assertEquals(0.6f, y.cropH, 0f)

        val w = SliderSteps.resetToDefault(SliderSteps.Axis.CROP_W, current)
        assertEquals(1f, w.cropW, 0f)
        assertEquals(0f, w.cropX, 0f)
        assertEquals(0.6f, w.cropH, 0f)
        assertEquals(0.2f, w.cropY, 0f)

        val h = SliderSteps.resetToDefault(SliderSteps.Axis.CROP_H, current)
        assertEquals(1f, h.cropH, 0f)
        assertEquals(0f, h.cropY, 0f)
        assertEquals(0.5f, h.cropW, 0f)
        assertEquals(0.3f, h.cropX, 0f)

        val straighten = SliderSteps.resetToDefault(SliderSteps.Axis.STRAIGHTEN, current)
        assertEquals(0f, straighten.straightenDeg, 0f)
        assertEquals(0.3f, straighten.cropX, 0f)
        assertEquals(3, straighten.cropAspect)
    }

    @Test
    fun applyCropAxis_translates_x_without_resizing() {
        // X is TRANSLATION: the position moves, the size never does —
        // a plain clampRect on the moved rect would shrink W when X
        // pushes past the right edge.
        val grade = GradeK(cropX = 0.1f, cropY = 0.1f, cropW = 0.5f, cropH = 0.5f)
        val moved = SliderSteps.applyCropAxis(
            SliderSteps.Axis.CROP_X, 0.9f, grade, 1920, 1080,
        )
        // Snapped to 0.9, then coerced into the honest range 0..1 - w:
        // the thumb never offers a position the policy clamps away.
        assertEquals(0.5f, moved.cropW, 1e-6f)
        assertEquals(0.5f, moved.cropH, 1e-6f)
        assertEquals(0.5f, moved.cropX, 1e-6f)
        assertEquals(0.1f, moved.cropY, 1e-6f)
    }

    @Test
    fun applyCropAxis_locks_height_under_a_preset_when_width_moves() {
        // 16:9 on 1920x1080: width to half the frame -> height follows
        // through the lock chain (pixel h = 540 = half the frame).
        val grade = GradeK(cropAspect = 1, cropX = 0.25f, cropY = 0.25f, cropW = 0.5f, cropH = 0.9f)
        val moved = SliderSteps.applyCropAxis(
            SliderSteps.Axis.CROP_W, 0.5f, grade, 1920, 1080,
        )
        assertEquals(0.5f, moved.cropW, 1e-6f)
        assertEquals(0.5f, moved.cropH, 1e-6f)
    }

    @Test
    fun applyCropAxis_locks_width_under_a_preset_when_height_moves() {
        // The height-axis twin: 16:9 on 1920x1080, height to half ->
        // width follows (pixel w = 960 = half the frame width).
        val grade = GradeK(cropAspect = 1, cropX = 0.25f, cropY = 0.25f, cropW = 0.9f, cropH = 0.5f)
        val moved = SliderSteps.applyCropAxis(
            SliderSteps.Axis.CROP_H, 0.5f, grade, 1920, 1080,
        )
        assertEquals(0.5f, moved.cropH, 1e-6f)
        assertEquals(0.5f, moved.cropW, 1e-6f)
    }

    @Test
    fun applyCropAxis_clamps_width_in_free_aspect() {
        // FREE: no lock runs; W grows only to the remaining extent at
        // the origin (x = 0.3 -> w <= 0.7).
        val grade = GradeK(cropX = 0.3f, cropY = 0.2f, cropW = 0.5f, cropH = 0.5f)
        val moved = SliderSteps.applyCropAxis(
            SliderSteps.Axis.CROP_W, 0.95f, grade, 1920, 1080,
        )
        assertEquals(0.7f, moved.cropW, 1e-6f)
        assertEquals(0.3f, moved.cropX, 1e-6f)
        assertEquals(0.5f, moved.cropH, 1e-6f)
    }

    @Test
    fun applyCropAxis_snaps_and_clamps_straighten() {
        // The dial snaps to half-degrees and clamps to ±15.
        val grade = GradeK(straightenDeg = 0f)
        val up = SliderSteps.applyCropAxis(
            SliderSteps.Axis.STRAIGHTEN, 16.2f, grade, 1920, 1080,
        )
        assertEquals(15f, up.straightenDeg, 1e-6f)
        val down = SliderSteps.applyCropAxis(
            SliderSteps.Axis.STRAIGHTEN, -16.2f, grade, 1920, 1080,
        )
        assertEquals(-15f, down.straightenDeg, 1e-6f)
        val mid = SliderSteps.applyCropAxis(
            SliderSteps.Axis.STRAIGHTEN, 3.24f, grade, 1920, 1080,
        )
        // 3.24 is nearer 3.0 than 3.5 on the half-degree grid.
        assertEquals(3.0f, mid.straightenDeg, 1e-6f)
    }

    @Test
    fun applyCropAxis_normalizes_an_invalid_stored_rect() {
        // A hand-edited JSON grade can hold an invalid rect; the chain
        // normalizes it through clampRect first. clampRect is
        // ORIGIN-PRIORITY: x keeps its clamped 0.9 and the width
        // shrinks to the remaining extent (w = 1 - x = 0.1), the
        // committed clampRect semantics — then the Y drag moves in
        // the normalized rect's honest range.
        val grade = GradeK(cropX = 0.9f, cropY = 0.1f, cropW = 0.5f, cropH = 0.5f)
        val moved = SliderSteps.applyCropAxis(
            SliderSteps.Axis.CROP_Y, 0.2f, grade, 1920, 1080,
        )
        assertEquals(0.9f, moved.cropX, 1e-6f)
        assertEquals(0.1f, moved.cropW, 1e-6f)
        assertEquals(0.2f, moved.cropY, 1e-6f)
        assertEquals(0.5f, moved.cropH, 1e-6f)
    }

    @Test
    fun cropAxisRange_spans_the_honest_translation_room() {
        // X spans 0 .. 1 - w (the thumb never offers an off-frame
        // position), floored at one tick for the full rect.
        val grade = GradeK(cropX = 0.2f, cropY = 0.1f, cropW = 0.5f, cropH = 0.6f)
        val x = SliderSteps.cropAxisRange(
            SliderSteps.Axis.CROP_X, grade, 1920, 1080,
        )
        assertEquals(0f, x.start, 1e-6f)
        assertEquals(0.5f, x.endInclusive, 1e-6f)
    }

    @Test
    fun cropAxisRange_w_under_preset_spans_the_preset_extent() {
        // 16:9 on 1920x1080: the widest on-ratio rect is the full width,
        // so W spans 0.05..1 — beyond it the lock would cap anyway.
        val grade = GradeK(cropAspect = 1, cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f)
        val w = SliderSteps.cropAxisRange(
            SliderSteps.Axis.CROP_W, grade, 1920, 1080,
        )
        assertEquals(0.05f, w.start, 1e-6f)
        assertEquals(1f, w.endInclusive, 1e-6f)
    }

    @Test
    fun cropAxisRange_h_under_preset_spans_the_preset_extent() {
        // 16:9 on a SQUARE frame: the tallest on-ratio rect is
        // h = 9/16 of the frame (aspectRect's centered full-width
        // rect). On a 16:9 frame the preset IS the full frame — the
        // W test above covers that case.
        val grade = GradeK(cropAspect = 1, cropX = 0f, cropY = 0f, cropW = 1f, cropH = 1f)
        val h = SliderSteps.cropAxisRange(
            SliderSteps.Axis.CROP_H, grade, 1000, 1000,
        )
        assertEquals(0.05f, h.start, 1e-6f)
        assertEquals(0.5625f, h.endInclusive, 1e-6f)
    }

    @Test
    fun cropAxisRange_unknown_dims_degrade_to_free_extent() {
        // 0x0 before the first publish: the W/H ranges degrade to the
        // FREE extents, matching the locks' own guard.
        val grade = GradeK(cropAspect = 1, cropX = 0.2f, cropY = 0.1f, cropW = 0.5f, cropH = 0.6f)
        val w = SliderSteps.cropAxisRange(
            SliderSteps.Axis.CROP_W, grade, 0, 0,
        )
        assertEquals(0.05f, w.start, 1e-6f)
        assertEquals(0.8f, w.endInclusive, 1e-6f)
    }

    @Test
    fun cropAxisRange_straighten_is_the_constant_band() {
        val grade = GradeK()
        val r = SliderSteps.cropAxisRange(
            SliderSteps.Axis.STRAIGHTEN, grade, 1920, 1080,
        )
        assertEquals(-15f, r.start, 1e-6f)
        assertEquals(15f, r.endInclusive, 1e-6f)
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
