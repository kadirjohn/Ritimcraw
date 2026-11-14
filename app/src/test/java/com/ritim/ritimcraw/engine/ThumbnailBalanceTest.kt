package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThumbnailBalance tests (owner request 2026-11-14): the LIGHT
 * histogram balance of the full-res MCRAW import thumbnail — the
 * identity for a balanced frame, bounded highlights recovery for a
 * high-crowded one, bounded shadows lift for a low-crowded one, both
 * (capped below the single-end bound) for a both-crowded one. The tone
 * math itself is the existing native stage-15/11; this heuristic only
 * picks the bounded GradeK values.
 */
class ThumbnailBalanceTest {

    /** A histogram with [high] of pixels at 255 and [low] at 0, rest mid. */
    private fun hist(total: Int = 10_000, high: Int = 0, low: Int = 0): IntArray {
        val h = IntArray(256)
        h[255] = high
        h[0] = low
        h[128] = total - high - low
        return h
    }

    // --- luma histogram ------------------------------------------------

    @Test
    fun luma_histogram_counts_every_pixel_once() {
        // 2 pixels: white (255) and black (0) — BT.709 integer luma.
        val rgb = ByteArray(6)
        rgb[0] = 255.toByte(); rgb[1] = 255.toByte(); rgb[2] = 255.toByte()
        rgb[3] = 0; rgb[4] = 0; rgb[5] = 0
        val h = ThumbnailBalance.lumaHistogram(rgb)
        assertEquals(2, h.sum())
        assertEquals(1, h[255])
        assertEquals(1, h[0])
    }

    @Test
    fun luma_histogram_of_mid_gray_lands_mid_bin() {
        // 128,128,128 -> (2126+7152+722)*128/10000 = 1281 -> bin 128.
        val rgb = ByteArray(3) { 128.toByte() }
        val h = ThumbnailBalance.lumaHistogram(rgb)
        assertEquals(1, h[128])
    }

    // --- the identity ---------------------------------------------------

    @Test
    fun balanced_frame_is_the_identity() {
        // 1% high, 1% low: both under the 2% crowd threshold — no
        // second develop, the neutral frame is kept as-is.
        val grade = ThumbnailBalance.gradeFor(hist(high = 100, low = 100))
        assertEquals(0f, grade.highlights, 0f)
        assertEquals(0f, grade.shadows, 0f)
    }

    @Test
    fun crowd_threshold_is_inclusive_identity() {
        // Exactly AT the 2% threshold: still the identity (crowding is
        // strictly greater-than).
        val grade = ThumbnailBalance.gradeFor(hist(high = 200))
        assertEquals(0f, grade.highlights, 0f)
    }

    @Test
    fun empty_histogram_is_the_identity() {
        // A degenerate histogram never invents a balance.
        val grade = ThumbnailBalance.gradeFor(IntArray(256))
        assertEquals(0f, grade.highlights, 0f)
        assertEquals(0f, grade.shadows, 0f)
    }

    // --- high-crowd: bounded highlights recovery ------------------------

    @Test
    fun high_crowd_pulls_highlights_down_bounded() {
        // 5% of pixels blown out: recovery between -10 and -25, negative.
        val grade = ThumbnailBalance.gradeFor(hist(high = 500))
        assertTrue("highlights must be negative, was ${grade.highlights}", grade.highlights < 0f)
        assertTrue("highlights must be >= -25, was ${grade.highlights}", grade.highlights >= -25f)
        assertTrue("highlights must be <= -10, was ${grade.highlights}", grade.highlights <= -10f)
        assertEquals(0f, grade.shadows, 0f)
    }

    @Test
    fun slight_high_crowd_is_the_minimum_strength() {
        // Just over the threshold: the gentlest touch, -10 (stage-15
        // s = 0.10 — the brief's small-shoulder range).
        val grade = ThumbnailBalance.gradeFor(hist(high = 201))
        assertEquals(-10f, grade.highlights, 0.05f)
    }

    @Test
    fun full_high_crowd_saturates_at_the_bound() {
        // >= 10% blown out: the maximum, -25 (s = 0.25), never beyond.
        val grade = ThumbnailBalance.gradeFor(hist(high = 1_000))
        assertEquals(-25f, grade.highlights, 0.01f)
        // Far beyond saturation: still -25 — the bound is hard.
        val grade2 = ThumbnailBalance.gradeFor(hist(high = 5_000))
        assertEquals(-25f, grade2.highlights, 0f)
    }

    // --- low-crowd: bounded shadows lift ---------------------------------

    @Test
    fun low_crowd_lifts_shadows_bounded() {
        // 5% of pixels crushed to black: lift between +10 and +25.
        val grade = ThumbnailBalance.gradeFor(hist(low = 500))
        assertTrue("shadows must be positive, was ${grade.shadows}", grade.shadows > 0f)
        assertTrue("shadows must be <= 25, was ${grade.shadows}", grade.shadows <= 25f)
        assertTrue("shadows must be >= 10, was ${grade.shadows}", grade.shadows >= 10f)
        assertEquals(0f, grade.highlights, 0f)
    }

    @Test
    fun full_low_crowd_saturates_at_the_bound() {
        val grade = ThumbnailBalance.gradeFor(hist(low = 1_000))
        assertEquals(25f, grade.shadows, 0.01f)
    }

    // --- both crowds: never both extremes --------------------------------

    @Test
    fun both_crowds_capped_below_the_single_end_bound() {
        // Both ends fully crowded: both controls apply, but each capped
        // at 20 — BELOW the ±25 single-end bound (the brief's "never
        // both extremes").
        val grade = ThumbnailBalance.gradeFor(hist(high = 1_000, low = 1_000))
        assertEquals(-20f, grade.highlights, 0.01f)
        assertEquals(20f, grade.shadows, 0.01f)
    }

    @Test
    fun both_crowds_scale_gently() {
        // Slight crowding on both ends: still the linear map, and the
        // cap does not clamp a small value.
        val grade = ThumbnailBalance.gradeFor(hist(high = 300, low = 300))
        assertTrue(grade.highlights < 0f && grade.highlights > -20f)
        assertTrue(grade.shadows > 0f && grade.shadows < 20f)
    }

    // --- fractions -------------------------------------------------------

    @Test
    fun high_and_low_fractions_count_only_their_ends() {
        val h = hist(total = 10_000, high = 250, low = 250)
        assertEquals(0.025, ThumbnailBalance.highFraction(h), 1e-9)
        assertEquals(0.025, ThumbnailBalance.lowFraction(h), 1e-9)
    }

    @Test
    fun high_bin_boundary_counts_bin_243_onward() {
        // Bin 243 (0.95 luma) is the FIRST crowded bin; 242 is not.
        val h = IntArray(256)
        h[243] = 10
        h[242] = 10
        assertEquals(10.0 / 20, ThumbnailBalance.highFraction(h), 1e-9)
    }
}

/**
 * ThumbnailIcon tests: the pure decode-sizing math — the full-res JPEG
 * master is NEVER decoded whole into a list row (inSampleSize picks the
 * sample), and the icon's exact dims keep the long edge at the target
 * (256px — the row shows 76dp at ~420dpi, ~200px physical, so the icon
 * scales DOWN on display; owner finding 2026-11-14: the old 128px icon
 * was upscaled and looked soft).
 */
class ThumbnailIconTest {

    // --- inSampleSize ----------------------------------------------------

    @Test
    fun in_sample_size_is_one_when_master_fits() {
        // A 256x192 master for a 256px icon: no subsampling.
        assertEquals(1, ThumbnailIcon.inSampleSize(256, 192, 256, 256))
    }

    @Test
    fun in_sample_size_picks_power_of_two() {
        // 4000x3000 master towards 256x256: the largest power of 2 whose
        // halves still cover the request: 1500/2^3 = 187 < 256, so 8.
        assertEquals(8, ThumbnailIcon.inSampleSize(4000, 3000, 256, 256))
    }

    @Test
    fun in_sample_size_never_upscales() {
        // A master SMALLER than the request: sample stays 1.
        assertEquals(1, ThumbnailIcon.inSampleSize(64, 64, 256, 256))
    }

    @Test
    fun in_sample_size_is_power_of_two() {
        // 4032x3024 (12MP) towards 256: 2016/8 = 252 < 256 -> 8.
        val s = ThumbnailIcon.inSampleSize(4032, 3024, 256, 256)
        assertEquals(8, s)
        assertEquals(0, s and (s - 1)) // power of two
    }

    // --- icon dims -------------------------------------------------------

    @Test
    fun icon_dims_null_when_already_within_target() {
        assertNull(ThumbnailIcon.iconDims(256, 192, 256))
        assertNull(ThumbnailIcon.iconDims(192, 256, 256))
    }

    @Test
    fun icon_dims_scale_the_long_edge_to_target() {
        // 504x378 (4000x3000 at sample 8) -> long edge 504 -> 256.
        val dims = ThumbnailIcon.iconDims(504, 378, 256)
        assertNotNull(dims)
        assertEquals(256, dims!!.first)
        assertEquals(192, dims.second)
    }

    @Test
    fun icon_dims_never_zero() {
        // A degenerate 1x10000 master: the short edge clamps to >= 1.
        val dims = ThumbnailIcon.iconDims(1, 10_000, 256)
        assertNotNull(dims)
        assertTrue(dims!!.first >= 1)
        assertEquals(256, dims.second)
    }
}
