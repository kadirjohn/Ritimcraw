package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * ColorWheelMath tests (COL-13): the pad's pure geometry and the display
 * mapping the disc and split-tone tracks render — the UI contract locked
 * against the native stage-18/19 math it quotes (Develop.cpp: the wheel
 * gains 2^(0.5x) / 2^(0.5y) / 2^(-0.5x), the split hue windows a raised
 * cosine per channel 120 degrees apart with unit circular average).
 * JVM-testable without Compose: the object is pure.
 */
class ColorWheelMathTest {

    // ---- offsetFromPointer: pointer -> unit-disc offset ----

    @Test
    fun center_pointer_is_zero_offset() {
        val (x, y) = ColorWheelMath.offsetFromPointer(100f, 100f, 200f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun quadrant_pointer_maps_to_unit_disc() {
        // A pointer at (150, 100) in a 200px disc: half-way right on x,
        // centered on y -> (0.5, 0).
        val (x, y) = ColorWheelMath.offsetFromPointer(150f, 100f, 200f)
        assertEquals(0.5f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun corner_pointer_clamps_to_rim() {
        // (200, 200) in a 200px disc is the corner: r = sqrt(2) > 1, so the
        // offset clamps to the rim direction (the 45-degree diagonal).
        val (x, y) = ColorWheelMath.offsetFromPointer(200f, 200f, 200f)
        assertEquals(1f / sqrt(2f), x, 1e-4f)
        assertEquals(1f / sqrt(2f), y, 1e-4f)
        assertEquals(1f, hypot(x, y), 1e-4f)
    }

    @Test
    fun far_outside_pointer_holds_rim_direction() {
        // Way outside the disc: the offset stays ON the rim in the pointer's
        // direction — never an out-of-range stored offset.
        val (x, y) = ColorWheelMath.offsetFromPointer(1000f, 100f, 200f)
        assertEquals(1f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun degenerate_diameter_is_neutral() {
        // A zero-measured first pass must not divide by zero: neutral.
        val (x, y) = ColorWheelMath.offsetFromPointer(50f, 50f, 0f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    // ---- gainsAt: the stage-18 exponents ----

    @Test
    fun center_gains_are_one() {
        // The spec's "merkez nötrdür": the center is neutral — gains 1.
        val g = ColorWheelMath.gainsAt(0f, 0f)
        assertEquals(1f, g[0], 1e-5f)
        assertEquals(1f, g[1], 1e-5f)
        assertEquals(1f, g[2], 1e-5f)
    }

    @Test
    fun full_warm_deflection_is_half_stop() {
        // x = 1: red up half a stop, blue down half a stop, green untouched
        // — the native stage-18 contract (STOPS_PER_UNIT = 0.5).
        val g = ColorWheelMath.gainsAt(1f, 0f)
        assertEquals(sqrt(2f), g[0], 1e-4f)
        assertEquals(1f, g[1], 1e-4f)
        assertEquals(1f / sqrt(2f), g[2], 1e-4f)
    }

    @Test
    fun x_axis_is_temp_idiom_y_axis_is_tint_idiom() {
        // Right (x > 0) warms: red up, blue down. Up (y > 0) greens. The
        // disc reads in the engine's directions exactly. At x = 0 the
        // tint idiom moves ONLY green: red and blue stay exactly 1.
        val warm = ColorWheelMath.gainsAt(0.5f, 0f)
        assertTrue(warm[0] > 1f && warm[2] < 1f)
        val green = ColorWheelMath.gainsAt(0f, 0.5f)
        assertTrue(green[1] > 1f)
        assertEquals(1f, green[0], 1e-5f)
        assertEquals(1f, green[2], 1e-5f)
    }

    @Test
    fun gains_are_symmetric_around_center() {
        // +x and -x swap red/blue exactly (the temp idiom is
        // antisymmetric); +y and -y make green exact reciprocals.
        val plus = ColorWheelMath.gainsAt(0.3f, 0.2f)
        val minus = ColorWheelMath.gainsAt(-0.3f, -0.2f)
        assertEquals(plus[0], minus[2], 1e-5f)
        assertEquals(plus[2], minus[0], 1e-5f)
        assertEquals(plus[1], 1f / minus[1], 1e-5f)
    }

    // ---- discColor: the display mapping ----

    // Compose's Color float constructor quantizes to the 8-bit sRGB grid:
    // 0.5 reads back as 128/255 = 0.5019608. Display-range assertions use
    // a full step (1/255) tolerance — exact at the grid, honest about the
    // constructor's quantization, immune to the boundary's float error.
    private val displayTol = 1f / 255f

    @Test
    fun disc_center_is_neutral_gray() {
        // The spec's "merkez nötrdür": the center renders neutral gray —
        // equal channels, exactly the scheme's neutral chrome, never a
        // brightness change the wheel never makes.
        val c = ColorWheelMath.discColor(0f, 0f)
        assertEquals(0.5f, c.red, displayTol)
        assertEquals(0.5f, c.green, displayTol)
        assertEquals(0.5f, c.blue, displayTol)
    }

    @Test
    fun disc_rim_extreme_dominates() {
        // At the warm extreme the red channel maxes at 1: the rim reads as
        // full-strength warm, blue at half the dominant — the muted push
        // the engine actually applies.
        val c = ColorWheelMath.discColor(1f, 0f)
        assertEquals(1f, c.red, displayTol)
        assertEquals(0.5f, c.blue, displayTol)
        assertTrue(c.green < 1f && c.green > 0.5f)
    }

    // ---- splitHueGains: the stage-19 windows ----

    @Test
    fun red_hue_peaks_red_window() {
        // 0 degrees: red window fully open (2), green/blue at 0.5 — the
        // unit-average property (off channels drop equally to half).
        val g = ColorWheelMath.splitHueGains(0f)
        assertEquals(2f, g[0], 1e-4f)
        assertEquals(0.5f, g[1], 1e-4f)
        assertEquals(0.5f, g[2], 1e-4f)
    }

    @Test
    fun green_hue_peaks_green_window() {
        val g = ColorWheelMath.splitHueGains(120f)
        assertEquals(0.5f, g[0], 1e-4f)
        assertEquals(2f, g[1], 1e-4f)
        assertEquals(0.5f, g[2], 1e-4f)
    }

    @Test
    fun blue_hue_peaks_blue_window() {
        val g = ColorWheelMath.splitHueGains(240f)
        assertEquals(0.5f, g[0], 1e-4f)
        assertEquals(0.5f, g[1], 1e-4f)
        assertEquals(2f, g[2], 1e-4f)
    }

    @Test
    fun hue_windows_have_unit_circular_average() {
        // The engine's rescale: the circular average of each window triple
        // is 1 — the average over the whole circle, the property that keeps
        // split tone a bounded push (off channels drop EQUALLY to 0.5).
        var sum = 0f
        for (deg in 0 until 360 step 5) {
            val g = ColorWheelMath.splitHueGains(deg.toFloat())
            sum += (g[0] + g[1] + g[2]) / 3f
        }
        val samples = 72
        assertEquals(1f, sum / samples, 1e-3f)
    }

    @Test
    fun hue_wraps_circularly() {
        // 359 degrees is 1 degree BELOW red on the circle, not 359 away:
        // the circular distance folds it to the same window shape as -1
        // (the gains are 360-periodic). The red channel is symmetric
        // around 0 — 359 and 1 both sit 1 degree from the red peak.
        val a = ColorWheelMath.splitHueGains(359f)
        val b = ColorWheelMath.splitHueGains(-1f)
        assertEquals(a[0], b[0], 1e-4f)
        assertEquals(a[1], b[1], 1e-4f)
        assertEquals(a[2], b[2], 1e-4f)
        assertEquals(a[0], ColorWheelMath.splitHueGains(1f)[0], 1e-4f)
    }

    // ---- splitHueColor + hueTrackGradient: the display mapping ----

    @Test
    fun split_color_max_normalizes() {
        // The dominant channel lands at 1, the off windows at 0.25 (half
        // of the 0.5 gain share): the swatch IS the muted tint the engine
        // pushes.
        val red = ColorWheelMath.splitHueColor(0f)
        assertEquals(1f, red.red, displayTol)
        assertEquals(0.25f, red.green, displayTol)
        assertEquals(0.25f, red.blue, displayTol)
    }

    @Test
    fun hue_track_gradient_spans_the_circle() {
        // The track samples the full circle: first sample red-windowed
        // (hue 0), a quarter in green, and the list is long enough to
        // read as a smooth sweep on the 4dp track line.
        val track = ColorWheelMath.hueTrackGradient()
        assertEquals(24, track.size)
        assertEquals(1f, track[0].red, 1e-3f)
        val quarter = track[track.size / 4]
        assertTrue(quarter.green > quarter.red)
        assertTrue(quarter.green > quarter.blue)
    }

    @Test
    fun hue_track_gradient_first_and_last_wrap_smoothly() {
        // Hue 0 (red) and hue 360·(23/24) are neighbors on the circle: the
        // track's ends sit close in color space, so the sweep reads as a
        // continuous hue circle (no jump at the seam).
        val track = ColorWheelMath.hueTrackGradient()
        val first = track.first()
        val last = track.last()
        assertTrue(kotlin.math.abs(first.red - last.red) < 0.5f)
        assertTrue(kotlin.math.abs(first.green - last.green) < 0.5f)
        assertTrue(kotlin.math.abs(first.blue - last.blue) < 0.5f)
    }
}
