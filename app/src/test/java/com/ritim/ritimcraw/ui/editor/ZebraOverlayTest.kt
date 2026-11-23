package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAY-11 zebra decision semantics (pure JVM tests — the composable wiring
 * is the owner's device test):
 *  - the threshold AGREES with the histogram's clip pills (the same
 *    [FrameHistogram.WHITE_CLIP_VALUE] on the same [FrameHistogram.luma]
 *    function — a pixel the pills count as luma-clipped is striped),
 *  - the stripe pattern is the classic 45-degree diagonal with the
 *    documented period,
 *  - the painting pass never modifies its input, stripes only clipped
 *    pixels' stripe positions, leaves everything else verbatim, and
 *    handles the empty cases.
 */
class ZebraOverlayTest {

    // ---- threshold: the same definition as the clip pills ----

    @Test
    fun threshold_agrees_with_histogram_luma_clip() {
        // Direct agreement sweep: for a spread of pixels, the zebra's
        // decision is exactly (FrameHistogram.luma >= WHITE_CLIP_VALUE)
        // — the two features share the one luma function and threshold,
        // so they can never disagree.
        var x = -1071
        var checked = 0
        while (x < 1_000_000) {
            val r = x and 0xFF
            val g = (x * 7) and 0xFF
            val b = (x * 13) and 0xFF
            assertEquals(
                FrameHistogram.luma(r, g, b) >= FrameHistogram.WHITE_CLIP_VALUE,
                ZebraOverlay.isOverThreshold(r, g, b),
            )
            checked++
            x += 9973
        }
        assertTrue(checked > 50)
    }

    @Test
    fun saturated_white_clips_and_mid_gray_does_not() {
        // Pure white: luma 255 >= 253 — striped.
        assertTrue(ZebraOverlay.isOverThreshold(255, 255, 255))
        // Mid-gray: luma ~128 — clean.
        assertFalse(ZebraOverlay.isOverThreshold(128, 128, 128))
        // The boundary: (0, 253, 0) has luma (183 * 253) shr 8 = 180 —
        // under; a real boundary triple is checked in the sweep above.
        assertFalse(ZebraOverlay.isOverThreshold(0, 253, 0))
    }

    @Test
    fun saturated_channel_with_mid_luma_is_not_striped() {
        // PLAY-11's zebra is a LUMA clip indicator (the display-referred
        // signal's clip): a saturated red (255, 0, 0) has luma 53 — the red
        // CHANNEL clips (the histogram's per-channel pills catch it), but
        // the display luma does not, so no zebra. The two features
        // complement, not duplicate.
        assertFalse(ZebraOverlay.isOverThreshold(255, 0, 0))
    }

    // ---- stripe pattern ----

    @Test
    fun stripe_pattern_is_45_degree_diagonal_with_period() {
        val p = ZebraOverlay.STRIPE_PERIOD
        for (x in 0 until p * 2) {
            for (y in 0 until p * 2) {
                // Periodic in both axes (the pattern tiles).
                assertEquals(ZebraOverlay.stripeAt(x, y), ZebraOverlay.stripeAt(x + p * 2, y))
                assertEquals(ZebraOverlay.stripeAt(x, y), ZebraOverlay.stripeAt(x, y + p * 2))
                // One period along x flips the state (the alternation
                // that makes the bands read as stripes).
                assertFalse(
                    ZebraOverlay.stripeAt(x, y) == ZebraOverlay.stripeAt(x + p, y),
                )
                // The 45-degree direction: (x + p, y) and (x, y + p) share
                // the same x + y -> the same state; the diagonal is the
                // invariant direction of the pattern.
                assertEquals(
                    ZebraOverlay.stripeAt(x + p, y),
                    ZebraOverlay.stripeAt(x, y + p),
                )
            }
        }
    }

    // ---- the A3 GPU zebra's decision helper (owner request 2026-11-23,
    // guide §A3): the shader-decision reference the WINDOW_FRAGMENT zebra
    // block's constants flow through — pure, JVM-testable, and in exact
    // agreement with the CPU paint path's own decisions.

    @Test
    fun zebraStriped_agrees_with_the_cpu_decisions_on_the_byte_cube_corners() {
        // The byte-cube corners + the threshold-boundary triples the luma
        // math makes interesting: for every (r, g, b) and every stripe-phase
        // position on the grid, the helper's ONE combined decision is
        // exactly (isOverThreshold && stripeAt) — the GPU block's constants
        // can never drift from the CPU path's two pure decisions.
        val corners = listOf(
            Triple(0, 0, 0),
            Triple(255, 255, 255),
            Triple(255, 0, 0),
            Triple(0, 255, 0),
            Triple(0, 0, 255),
            Triple(252, 255, 252),
            Triple(0, 253, 0),
            Triple(253, 0, 0),
            Triple(0, 0, 253),
        )
        val phases = listOf(0, 6, 11, 12, 13, 24, 25)
        for ((r, g, b) in corners) {
            for (x in phases) {
                for (y in phases) {
                    assertEquals(
                        "($r,$g,$b) at ($x,$y)",
                        ZebraOverlay.isOverThreshold(r, g, b) && ZebraOverlay.stripeAt(x, y),
                        ZebraOverlay.zebraStriped(r, g, b, x, y),
                    )
                }
            }
        }
    }

    @Test
    fun zebraStriped_agrees_with_the_cpu_decisions_over_the_sample_grid() {
        // The threshold_agrees_with_histogram_luma_clip shape: a
        // deterministic pseudo-random sweep over (r, g, b) triples and
        // (x, y) positions (>1000 samples), asserting the same agreement —
        // the boundary region around luma 252/253 is exercised densely.
        var v = -1071
        var checked = 0
        while (v < 1_000_000) {
            val r = v and 0xFF
            val g = (v * 7) and 0xFF
            val b = (v * 13) and 0xFF
            val x = (v * 31) and 0x7F
            val y = (v * 17) and 0x7F
            assertEquals(
                "($r,$g,$b) at ($x,$y)",
                ZebraOverlay.isOverThreshold(r, g, b) && ZebraOverlay.stripeAt(x, y),
                ZebraOverlay.zebraStriped(r, g, b, x, y),
            )
            checked++
            v += 89
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun zebraStriped_uses_the_single_sourced_constants_not_local_copies() {
        // The helper's DEFAULTS are the single-sourced constants (a call
        // without overrides runs the production decisions), and an override
        // genuinely parameterizes both the threshold AND the stripe period
        // — never a local copy of 253/12.
        assertEquals(
            ZebraOverlay.isOverThreshold(252, 255, 252) && ZebraOverlay.stripeAt(0, 0),
            ZebraOverlay.zebraStriped(252, 255, 252, 0, 0),
        )
        // A custom period changes ONLY the stripe phase math: period 1
        // alternates EVERY pixel (striped <=> (x + y) even), with the
        // SAME luma threshold decisions on every position.
        val over = 254
        val under = 128
        for (x in 0..3) {
            for (y in 0..3) {
                assertEquals(
                    "period-1 at ($x,$y)",
                    (x + y) and 1 == 0,
                    ZebraOverlay.zebraStriped(over, over, over, x, y, period = 1),
                )
                assertFalse(ZebraOverlay.zebraStriped(under, under, under, x, y, period = 1))
                // A threshold ABOVE every luma stripes nothing (luma caps
                // at 255); a threshold of 0 stripes every stripe-phase
                // position regardless of color.
                assertFalse(ZebraOverlay.zebraStriped(255, 255, 255, 0, 0, threshold = 256))
                assertTrue(ZebraOverlay.zebraStriped(0, 0, 0, 0, 0, threshold = 0))
            }
        }
    }

    // ---- the painting pass ----

    @Test
    fun paint_stripes_only_clipped_stripe_positions_and_never_touches_input() {
        // A 4x4 frame: the top two rows a clipped non-white pixel, the
        // bottom two rows mid-gray. Stripe period 12 > 4, so every
        // position in this small frame has the SAME stripe state
        // ((x + y) / 12 == 0 everywhere) — the pass must stripe ALL the
        // clipped pixels and NONE of the gray ones.
        val w = 4
        val h = 4
        // (252, 255, 252): luma = (54*252 + 183*255 + 19*252) shr 8 = 254
        // — clipped, but not pure white, so the dark blend visibly moves
        // every channel.
        val clipped = 0xFFFCFFFC.toInt()
        val gray = 0xFF808080.toInt()
        val input = IntArray(w * h) { i -> if (i < w * 2) clipped else gray }
        val snapshot = input.copyOf()
        val out = ZebraOverlay.paint(input, w, h)!!
        // The input is never modified.
        assertTrue(input.contentEquals(snapshot))
        // The clean rows are verbatim.
        for (i in w * 2 until w * h) {
            assertEquals(gray, out[i])
        }
        // Every clipped pixel was blended toward black by the documented
        // formula: (v * (255 - a)) / 255.
        val a = ZebraOverlay.STRIPE_ALPHA
        val inv = 255 - a
        for (i in 0 until w * 2) {
            val p = out[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            assertEquals((252 * inv) / 255, r)
            assertEquals((255 * inv) / 255, g)
            assertEquals((252 * inv) / 255, b)
        }
    }

    @Test
    fun paint_clear_stripe_positions_copy_verbatim() {
        // Where the stripe pattern is OFF ((x + y) / 12 odd), a clipped
        // pixel stays VERBATIM — the zebra's gaps are the footage's own
        // pixels, not a tint. (13, 0): 13 / 12 = 1 (odd) -> clear.
        val clipped = 0xFFFCFFFC.toInt()
        val wide = IntArray(14) { clipped }
        val out = ZebraOverlay.paint(wide, 14, 1)!!
        // (0, 0) striped -> blended (moved off the original).
        assertFalse(out[0] == clipped)
        // (13, 0) clear -> verbatim.
        assertEquals(clipped, out[13])
        // Pure white in a clear position also stays verbatim.
        val white = 0xFFFFFFFF.toInt()
        val wide2 = IntArray(14) { white }
        val out2 = ZebraOverlay.paint(wide2, 14, 1)!!
        assertEquals(white, out2[13])
    }

    @Test
    fun paint_empty_inputs_return_null() {
        assertNull(ZebraOverlay.paint(null, 4, 4))
        assertNull(ZebraOverlay.paint(IntArray(0), 4, 4))
        assertNull(ZebraOverlay.paint(IntArray(16), 0, 4))
        assertNull(ZebraOverlay.paint(IntArray(16), 4, 0))
    }
}
