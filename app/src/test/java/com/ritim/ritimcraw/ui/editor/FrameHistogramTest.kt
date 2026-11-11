package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameHistogram tests: 256-bin Rec.709 luma histogram math, clipping
 * counts and the badge threshold, on synthetic ARGB arrays — pure JVM,
 * no Compose, no native lib.
 */
class FrameHistogramTest {

    /** Packs 8-bit RGB into 0xFF RR GG BB (the renderer's opaque format). */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- luma math ----

    @Test
    fun pure_black_lands_in_bin_0() {
        // Integer Rec.709 weights saturate exactly: white -> 255, so the
        // full 8-bit range maps to bins 0..255 with no dead bin at the top.
        val result = FrameHistogram.of(intArrayOf(argb(0, 0, 0)))
        assertEquals(1, result.bins[0])
        assertEquals(1, result.sampled)
    }

    @Test
    fun pure_white_lands_in_bin_255() {
        // (54+183+19) * 255 >> 8 = 255 — the top bin is reachable.
        val result = FrameHistogram.of(intArrayOf(argb(255, 255, 255)))
        assertEquals(1, result.bins[255])
    }

    @Test
    fun mid_gray_lands_in_bin_128() {
        // 128 * 256 >> 8 = 128.
        val result = FrameHistogram.of(intArrayOf(argb(128, 128, 128)))
        assertEquals(1, result.bins[128])
    }

    @Test
    fun rec709_weights_green_dominates() {
        // Same channel value, same luma; a GREEN pixel outranks RED which
        // outranks BLUE — the 0.7152/0.2126/0.0722 weighting.
        val green = FrameHistogram.of(intArrayOf(argb(0, 100, 0))).bins
        val red = FrameHistogram.of(intArrayOf(argb(100, 0, 0))).bins
        val blue = FrameHistogram.of(intArrayOf(argb(0, 0, 100))).bins
        val gBin = green.indexOfFirst { it > 0 }
        val rBin = red.indexOfFirst { it > 0 }
        val bBin = blue.indexOfFirst { it > 0 }
        assertTrue(gBin > rBin)
        assertTrue(rBin > bBin)
    }

    @Test
    fun counts_accumulate_per_bin() {
        // Two identical mid-gray pixels: bin 128 gets 2, everything else 0.
        val result = FrameHistogram.of(
            intArrayOf(argb(128, 128, 128), argb(127, 129, 128))
        )
        assertEquals(2, result.bins[128])
        assertEquals(2, result.sampled)
        assertEquals(0, result.bins[0])
        assertEquals(0, result.bins[255])
    }

    // ---- degenerate input ----

    @Test
    fun null_and_empty_yield_zero_result() {
        // Nothing on screen: all-zero bins, no clip counts, no crash.
        val nullResult = FrameHistogram.of(null)
        assertEquals(0, nullResult.sampled)
        assertEquals(0, nullResult.bins.sum())
        assertFalse(nullResult.isClipped())
        val emptyResult = FrameHistogram.of(IntArray(0))
        assertEquals(0, emptyResult.sampled)
        assertFalse(emptyResult.isClipped())
    }

    // ---- clipping counts ----

    @Test
    fun black_clipping_counts_luma_at_or_below_2() {
        // Luma 0, 1 and 2 clip; luma 3 does not.
        val result = FrameHistogram.of(
            intArrayOf(
                argb(0, 0, 0),           // luma 0 -> clips
                argb(0, 1, 0),           // luma 0 (183*1>>8=0) -> clips
                argb(1, 2, 0),           // luma 1 -> clips
                argb(2, 2, 2),           // luma 2 -> clips
                argb(3, 3, 3),           // luma 3 -> does not
            )
        )
        assertEquals(4, result.blackClipped)
        assertEquals(0, result.whiteClipped)
    }

    @Test
    fun white_clipping_counts_luma_at_or_above_253() {
        // Luma 253, 254 and 255 clip; luma 252 does not.
        val result = FrameHistogram.of(
            intArrayOf(
                argb(253, 253, 253),   // luma 253 -> clips
                argb(254, 254, 254),   // luma 254 -> clips
                argb(255, 255, 255),   // luma 255 -> clips
                argb(252, 252, 252),   // luma 252 -> does not
            )
        )
        assertEquals(3, result.whiteClipped)
        assertEquals(0, result.blackClipped)
    }

    // ---- stride sampling ----

    @Test
    fun stride_caps_samples_at_max() {
        // A frame larger than MAX_SAMPLES is sampled at a stride so the
        // pass reads at most MAX_SAMPLES pixels — every stride-th pixel,
        // which preserves the histogram shape on synthetic ramps.
        val size = FrameHistogram.MAX_SAMPLES * 4
        val pixels = IntArray(size) { i -> argb(i and 0xFF, i and 0xFF, i and 0xFF) }
        val result = FrameHistogram.of(pixels)
        assertEquals(FrameHistogram.MAX_SAMPLES, result.sampled)
        // The stride is 4: pixels 0, 4, 8, ... The ramp value wraps mod
        // 256, so the sampled luma cycles 0, 4, ..., 252 exactly 1024
        // times over the array: each reachable bin holds 1024 counts and
        // the bins between them stay empty.
        assertEquals(1024, result.bins[0])
        assertEquals(1024, result.bins[4])
        assertEquals(1024, result.bins[252])
        assertEquals(0, result.bins[1])
        assertEquals(0, result.bins[2])
    }

    @Test
    fun small_frames_sample_every_pixel() {
        // Below the cap, stride is 1: the full frame is read.
        val pixels = IntArray(100) { argb(64, 64, 64) }
        val result = FrameHistogram.of(pixels)
        assertEquals(100, result.sampled)
        assertEquals(100, result.bins[64])
    }

    // ---- badge threshold ----

    @Test
    fun threshold_is_one_tenth_percent_of_samples() {
        // 1000 samples -> 0.1% = 1 pixel (rounded, minimum 1).
        assertEquals(1, FrameHistogram.clipThreshold(1000))
        // 100 samples -> 0.1% = 0.1 -> rounds to 0 but clamps to 1.
        assertEquals(1, FrameHistogram.clipThreshold(100))
        // 100,000 samples -> 100 pixels.
        assertEquals(100, FrameHistogram.clipThreshold(100_000))
    }

    @Test
    fun badge_needs_threshold_pixels_on_one_end() {
        // 1000 samples: one end needs >= 1 clipped pixel to warn.
        val clean = IntArray(1000) { argb(128, 128, 128) }
        assertFalse(FrameHistogram.of(clean).isClipped())

        val oneBlack = clean.copyOf().also { it[0] = argb(0, 0, 0) }
        assertTrue(FrameHistogram.of(oneBlack).isClipped())

        // Below the threshold on BOTH ends separately: no badge.
        val halfBlack = IntArray(1000) { i ->
            if (i < 500) argb(0, 0, 0) else argb(128, 128, 128)
        }
        // 500 black-clipped of 1000 samples: 50% >> 0.1% -> badge.
        assertTrue(FrameHistogram.of(halfBlack).isClipped())
    }

    @Test
    fun badge_is_per_end_not_combined() {
        // 2000 samples -> per-end threshold 2. One black + one white
        // pixel: their SUM (2) would satisfy a combined 0.1% rule, but
        // each END sits at 1, below its own threshold — the per-end
        // rule refuses the badge a combined rule would raise.
        val split = IntArray(2000) { argb(128, 128, 128) }
        split[0] = argb(0, 0, 0)
        split[1] = argb(255, 255, 255)
        val splitResult = FrameHistogram.of(split)
        assertEquals(1, splitResult.blackClipped)
        assertEquals(1, splitResult.whiteClipped)
        assertFalse(splitResult.isClipped())
        // Two black pixels and no white: the black end ALONE meets the
        // threshold — one end suffices without help from the other.
        val blackOnly = IntArray(2000) { argb(128, 128, 128) }
        blackOnly[0] = argb(0, 0, 0)
        blackOnly[1] = argb(0, 0, 0)
        val blackOnlyResult = FrameHistogram.of(blackOnly)
        assertEquals(2, blackOnlyResult.blackClipped)
        assertEquals(0, blackOnlyResult.whiteClipped)
        assertTrue(blackOnlyResult.isClipped())
    }
}
