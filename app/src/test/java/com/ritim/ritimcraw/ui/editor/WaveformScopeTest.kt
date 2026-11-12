package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.ui.editor.FrameHistogram.Channel
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WaveformScope tests: the position × level density math, the Rec.709
 * coded luma, the sampling tiers' position preservation, the mode's
 * channel selection, and the display render's intensity/geometry — on
 * synthetic ARGB arrays, pure JVM, no Compose, no native lib.
 */
class WaveformScopeTest {

    /** Packs 8-bit RGB into 0xFF RR GG BB (the renderer's opaque format). */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * A frame of [width] × [height] where pixel (x, y) has R = x mod 256,
     * G = y mod 256, B = (x + y) mod 256 — every pixel's position is
     * recoverable from its color, so position tests can assert exact
     * grid cells.
     */
    private fun gradientFrame(width: Int, height: Int): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            argb(x % 256, y % 256, (x + y) % 256)
        }

    // ---- input guards ----

    @Test
    fun null_frame_yields_empty_result() {
        val result = WaveformScope.of(null, 0, 0, WaveformMode.LUMA, WaveformScope.Sampling.MEDIUM)
        assertTrue(result.isEmpty)
        assertNull(result.luma)
        assertEquals(WaveformScope.Result.EMPTY, result)
    }

    @Test
    fun degenerate_dimensions_yield_empty_result() {
        val result = WaveformScope.of(intArrayOf(argb(0, 0, 0)), 0, 1, WaveformMode.LUMA, WaveformScope.Sampling.MEDIUM)
        assertTrue(result.isEmpty)
    }

    // ---- luma mode: one grid, coded signal luma ----

    @Test
    fun luma_mode_computes_only_the_luma_grid() {
        val result = WaveformScope.of(
            gradientFrame(64, 64), 64, 64,
            WaveformMode.LUMA, WaveformScope.Sampling.HIGH,
        )
        assertNull(result.red)
        assertNull(result.green)
        assertNull(result.blue)
        assertTrue(result.luma!!.all { it.toInt() >= 0 })
    }

    @Test
    fun pure_white_lands_at_level_255() {
        // One white pixel at x=0: luma (54+183+19)*255 >> 8 = 255, in the
        // leftmost column bin.
        val result = WaveformScope.of(intArrayOf(argb(255, 255, 255)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        assertEquals(1, result.luma!![255 * WaveformScope.COLUMNS + 0].toInt())
    }

    @Test
    fun pure_black_lands_at_level_0() {
        val result = WaveformScope.of(intArrayOf(argb(0, 0, 0)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        assertEquals(1, result.luma!![0].toInt())
    }

    @Test
    fun mid_gray_lands_at_level_128() {
        // 128 * 256 >> 8 = 128 — the coded signal level of an 8-bit
        // mid-gray, NOT the linear luminance (spec §5).
        val result = WaveformScope.of(intArrayOf(argb(128, 128, 128)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        assertEquals(1, result.luma!![128 * WaveformScope.COLUMNS + 0].toInt())
    }

    @Test
    fun luma_uses_rec709_weights_not_channel_average() {
        // (54*255 + 183*0 + 19*0) >> 8 = 53 — pure red's coded luma.
        val result = WaveformScope.of(intArrayOf(argb(255, 0, 0)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        assertEquals(1, result.luma!![53 * WaveformScope.COLUMNS + 0].toInt())
    }

    // ---- position preservation (spec §3: X axis = image position) ----

    @Test
    fun horizontal_position_preserved_left_and_right() {
        // A 256-wide frame: the leftmost pixel's column bin is 0, the
        // rightmost's is 255. Left half black, right half white.
        val width = 256
        val pixels = IntArray(width) { x -> if (x < width / 2) argb(0, 0, 0) else argb(255, 255, 255) }
        val result = WaveformScope.of(pixels, width, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        // Column 0 holds the black pixel at level 0.
        assertEquals(1, result.luma!![0 * WaveformScope.COLUMNS + 0].toInt())
        // Column 255 holds the white pixel at level 255.
        assertEquals(1, result.luma!![255 * WaveformScope.COLUMNS + 255].toInt())
    }

    @Test
    fun position_preserved_when_width_is_not_a_multiple_of_columns() {
        // 100-wide frame: x=0 → column 0, x=99 → column 99*256/100 = 253.
        val width = 100
        val pixels = IntArray(width) { x -> if (x == 0) argb(0, 0, 0) else argb(8, 8, 8) }
        val result = WaveformScope.of(pixels, width, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        // With colStride 1 every pixel is sampled: column 0 has the black
        // pixel, and column 253 has the x=99 pixel (level 8).
        assertEquals(1, result.luma!![0 * WaveformScope.COLUMNS + 0].toInt())
        assertEquals(1, result.luma!![8 * WaveformScope.COLUMNS + 253].toInt())
    }

    @Test
    fun sampling_cap_respected_by_row_stride() {
        // A tall 256-wide frame (256 columns, 2048 rows) at LOW (16384):
        // targetRows = 64, rowStride = 2048/64 = 32 → 64 sampled rows ×
        // 256 columns = 16384 samples exactly.
        val width = 256
        val height = 2048
        val result = WaveformScope.of(
            gradientFrame(width, height), width, height,
            WaveformMode.LUMA, WaveformScope.Sampling.LOW,
        )
        assertEquals(16384, result.sampled)
    }

    @Test
    fun sampling_spreads_across_full_horizontal_extent() {
        // The position-preserving walk must not leave vertical stripes:
        // a frame where every pixel is the same mid-gray produces counts
        // in EVERY column bin at LOW quality on a wide frame (a flat
        // stride would sample only some columns and leave others empty).
        val width = 1920
        val height = 1080
        val pixels = IntArray(width * height) { argb(128, 128, 128) }
        val result = WaveformScope.of(pixels, width, height, WaveformMode.LUMA, WaveformScope.Sampling.LOW)
        val grid = result.luma!!
        for (column in 0 until WaveformScope.COLUMNS) {
            assertTrue(
                "column $column empty",
                grid[128 * WaveformScope.COLUMNS + column].toInt() > 0,
            )
        }
    }

    @Test
    fun all_tiers_sample_at_most_their_cap() {
        val width = 3840
        val height = 2160
        val pixels = IntArray(width * height) { argb(64, 128, 192) }
        for (tier in WaveformScope.Sampling.entries) {
            val result = WaveformScope.of(pixels, width, height, WaveformMode.LUMA, tier)
            assertTrue("tier $tier sampled ${result.sampled}", result.sampled <= tier.maxSamples)
        }
    }

    // ---- overlay / parade: three channel grids ----

    @Test
    fun overlay_mode_computes_three_channel_grids() {
        val result = WaveformScope.of(
            gradientFrame(64, 64), 64, 64,
            WaveformMode.OVERLAY, WaveformScope.Sampling.MEDIUM,
        )
        assertNull(result.luma)
        assertFalse(result.isEmpty)
        assertTrue(result.red!!.all { it.toInt() >= 0 })
        assertTrue(result.green!!.all { it.toInt() >= 0 })
        assertTrue(result.blue!!.all { it.toInt() >= 0 })
    }

    @Test
    fun channel_grids_index_each_channel_own_value() {
        // One saturated-red pixel: red grid at level 255, green and blue
        // at level 0.
        val result = WaveformScope.of(intArrayOf(argb(255, 0, 0)), 1, 1, WaveformMode.OVERLAY, WaveformScope.Sampling.HIGH)
        assertEquals(1, result.red!![255 * WaveformScope.COLUMNS + 0].toInt())
        assertEquals(1, result.green!![0 * WaveformScope.COLUMNS + 0].toInt())
        assertEquals(1, result.blue!![0 * WaveformScope.COLUMNS + 0].toInt())
    }

    @Test
    fun parade_mode_computes_the_same_three_grids() {
        val result = WaveformScope.of(
            gradientFrame(64, 64), 64, 64,
            WaveformMode.PARADE, WaveformScope.Sampling.MEDIUM,
        )
        assertNull(result.luma)
        assertFalse(result.isEmpty)
        assertTrue(result.red != null && result.green != null && result.blue != null)
    }

    // ---- total density conservation ----

    @Test
    fun luma_grid_total_equals_sampled() {
        // Every sampled pixel lands in exactly one luma cell: the grid's
        // total is the sampled count.
        val result = WaveformScope.of(
            gradientFrame(320, 240), 320, 240,
            WaveformMode.LUMA, WaveformScope.Sampling.MEDIUM,
        )
        val total = result.luma!!.sumOf { it.toInt() }
        assertEquals(result.sampled, total)
    }

    @Test
    fun overlay_grid_totals_equal_sampled() {
        val result = WaveformScope.of(
            gradientFrame(320, 240), 320, 240,
            WaveformMode.OVERLAY, WaveformScope.Sampling.MEDIUM,
        )
        assertEquals(result.sampled, result.red!!.sumOf { it.toInt() })
        assertEquals(result.sampled, result.green!!.sumOf { it.toInt() })
        assertEquals(result.sampled, result.blue!!.sumOf { it.toInt() })
    }

    // ---- display render ----

    @Test
    fun empty_result_renders_fully_transparent() {
        val rendered = WaveformDisplay.render(WaveformScope.Result.EMPTY, WaveformMode.LUMA)
        assertEquals(WaveformScope.COLUMNS, rendered.width)
        assertEquals(WaveformScope.LEVELS, rendered.height)
        assertTrue(rendered.pixels.all { it == 0 })
    }

    @Test
    fun luma_render_geometry_and_white_color() {
        // One white pixel at level 128, column 0: bitmap row = 255-128 =
        // 127, column 0, neutral white premultiplied (alpha = r = g = b).
        val result = WaveformScope.of(intArrayOf(argb(128, 128, 128)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH)
        val rendered = WaveformDisplay.render(result, WaveformMode.LUMA)
        assertEquals(WaveformScope.COLUMNS, rendered.width)
        assertEquals(WaveformScope.LEVELS, rendered.height)
        val px = rendered.pixels[127 * WaveformScope.COLUMNS + 0]
        val a = (px shr 24) and 0xFF
        assertTrue(a > 0)
        assertEquals(a, (px shr 16) and 0xFF)
        assertEquals(a, (px shr 8) and 0xFF)
        assertEquals(a, px and 0xFF)
    }

    @Test
    fun render_y_axis_rises_upward() {
        // A black pixel lands on the BOTTOM row (row 255); a white pixel
        // on the TOP row (row 0) — the waveform convention.
        val black = WaveformDisplay.render(
            WaveformScope.of(intArrayOf(argb(0, 0, 0)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH),
            WaveformMode.LUMA,
        )
        assertTrue(black.pixels[255 * WaveformScope.COLUMNS] != 0)
        val white = WaveformDisplay.render(
            WaveformScope.of(intArrayOf(argb(255, 255, 255)), 1, 1, WaveformMode.LUMA, WaveformScope.Sampling.HIGH),
            WaveformMode.LUMA,
        )
        assertTrue(white.pixels[0] != 0)
    }

    @Test
    fun intensity_is_sqrt_scaled_and_monotone() {
        assertEquals(0, WaveformDisplay.intensity(0, 100))
        assertEquals(0, WaveformDisplay.intensity(5, 0))
        assertEquals(255, WaveformDisplay.intensity(100, 100))
        // sqrt: a quarter of peak density reads at half intensity.
        assertEquals(127, WaveformDisplay.intensity(25, 100))
        // Monotone in the count.
        assertTrue(
            WaveformDisplay.intensity(10, 100) < WaveformDisplay.intensity(20, 100),
        )
    }

    @Test
    fun overlay_render_converges_to_white_where_channels_overlap() {
        // A neutral gray pixel (128,128,128): all three channel traces
        // overlap at level 128, so the overlay pixel's R=G=B (additive
        // light converging to white).
        val result = WaveformScope.of(intArrayOf(argb(128, 128, 128)), 1, 1, WaveformMode.OVERLAY, WaveformScope.Sampling.HIGH)
        val rendered = WaveformDisplay.render(result, WaveformMode.OVERLAY)
        val px = rendered.pixels[(WaveformScope.LEVELS - 1 - 128) * WaveformScope.COLUMNS]
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
        assertTrue(r > 0)
    }

    @Test
    fun parade_render_geometry_and_channel_colors() {
        // Parade: three cells of 85 columns (256/3 floor), each level row
        // carries its channel's identity color (premultiplied).
        val result = WaveformScope.of(intArrayOf(argb(255, 0, 0)), 1, 1, WaveformMode.PARADE, WaveformScope.Sampling.HIGH)
        val rendered = WaveformDisplay.render(result, WaveformMode.PARADE)
        assertEquals(WaveformDisplay.PARADE_CELL_COLUMNS * 3, rendered.width)
        assertEquals(WaveformScope.LEVELS, rendered.height)
        // The red pixel: level 255 → row 0, in the RED cell (cell 0).
        val px = rendered.pixels[0 * (WaveformDisplay.PARADE_CELL_COLUMNS * 3) + 0]
        val a = (px shr 24) and 0xFF
        assertTrue(a > 0)
        // Red channel's identity color dominates: r > g and r > b.
        assertTrue((px shr 16) and 0xFF > (px shr 8) and 0xFF)
        assertTrue((px shr 16) and 0xFF > px and 0xFF)
    }

    @Test
    fun parade_max_pools_grid_columns_into_cells() {
        // The red channel of a 256-wide gradient: cell column j covers
        // grid columns j*256/85 .. — the densest trace survives the
        // pooling (a cell never shows an averaged-away spike).
        val width = 256
        val height = 1
        val pixels = IntArray(width) { x -> argb(x, 0, 0) } // R rises left→right
        val result = WaveformScope.of(pixels, width, height, WaveformMode.PARADE, WaveformScope.Sampling.HIGH)
        val rendered = WaveformDisplay.render(result, WaveformMode.PARADE)
        // Level 255 (row 0): only x=255 reached R=255, which max-pools
        // into the last cell column of the red cell.
        val row = 0
        var found = false
        for (j in 0 until WaveformDisplay.PARADE_CELL_COLUMNS) {
            if (rendered.pixels[row * rendered.width + j] != 0) found = true
        }
        assertTrue(found)
    }

    // ---- spec §8: reference derivation ----

    /**
     * The mid-gray reference is DERIVED, never hardcoded: 18% scene
     * linear through the Rec.709 camera OETF lands near 40.9% coded
     * signal (8-bit bin 104) — not at 18% screen level, the spec §8
     * trap.
     */
    @Test
    fun mid_gray_reference_is_derived_not_linear() {
        val v = WaveformScope.midGraySignal()
        assertEquals(0.409f, v, 0.005f)
        assertFalse(v in 0.17f..0.19f)
        assertEquals(104, (v * 255f).roundToInt())
    }

    // ---- mode channel sets ----

    @Test
    fun mode_channel_sets() {
        assertEquals(listOf(Channel.LUMA), WaveformMode.LUMA.channels)
        assertEquals(
            listOf(Channel.RED, Channel.GREEN, Channel.BLUE),
            WaveformMode.OVERLAY.channels,
        )
        assertEquals(WaveformMode.OVERLAY.channels, WaveformMode.PARADE.channels)
    }
}
