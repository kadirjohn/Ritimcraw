package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.ui.editor.FrameHistogram.Channel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HistogramDisplay tests: the overlay's display-only pipeline —
 * Gaussian smoothing on a copy, the common 99th-percentile scale
 * across channels, the top-1% spike ignore, and the mode-dependent
 * channel sets — pure JVM, no Compose. The owner's display rules these
 * pin: smoothing NEVER alters the raw counts (clipping indicators read
 * the raw counts), and all channels drawn together share ONE scale.
 */
class HistogramDisplayTest {

    /** Packs 8-bit RGB into 0xFF RR GG BB (the renderer's opaque format). */
    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * A Result with hand-set bins: interior single-bin spikes at bin
     * 200 — red 100, green 50, blue 25, luma empty — the controlled
     * input for the common-scale tests (away from the edges, so the
     * edge-replicate boundary does not amplify any spike).
     */
    private fun spikeResult(): FrameHistogram.Result {
        val red = IntArray(FrameHistogram.BINS)
        val green = IntArray(FrameHistogram.BINS)
        val blue = IntArray(FrameHistogram.BINS)
        red[200] = 100
        green[200] = 50
        blue[200] = 25
        return FrameHistogram.Result(
            redBins = red,
            greenBins = green,
            blueBins = blue,
            lumaBins = IntArray(FrameHistogram.BINS),
            blackClipCounts = IntArray(Channel.entries.size),
            whiteClipCounts = IntArray(Channel.entries.size),
            sampled = 175,
        )
    }

    // ---- smoothing is display-only ----

    @Test
    fun smoothing_spreads_a_spike_but_keeps_the_total() {
        // One bin holds 100 counts. After smoothing the mass has spread
        // over neighboring bins but the total is preserved (normalized
        // kernel) — the curve reads smoother, the data underneath is
        // the same mass.
        val bins = IntArray(256)
        bins[128] = 100
        val smoothed = HistogramDisplay.smooth(bins)
        assertEquals(100f, smoothed.sum(), 0.01f)
        // The spike is gone: the peak is lower than the raw 100...
        assertTrue(smoothed.max() < 100f)
        // ...and the mass has spread to the immediate neighbors.
        assertTrue(smoothed[127] > 0f)
        assertTrue(smoothed[129] > 0f)
        // Bins outside the 13-tap radius stay exactly zero.
        assertEquals(0f, smoothed[0], 0f)
        assertEquals(0f, smoothed[255], 0f)
    }

    @Test
    fun smoothing_never_touches_the_raw_bins() {
        // THE owner rule: display smoothing must not affect the raw
        // counts. compute() runs the whole pipeline on a Result; the
        // Result's arrays must be bitwise unchanged afterwards.
        val pixels = IntArray(500) { i ->
            when {
                i < 100 -> argb(0, 0, 0)            // black clip mass
                i < 400 -> argb(i % 256, 128, 64)    // varied midtones
                else -> argb(255, 255, 255)          // white clip mass
            }
        }
        val result = FrameHistogram.of(pixels)
        val before = result.bins(Channel.RED).copyOf()
        val clipBefore = result.blackClipped(Channel.RED)
        HistogramDisplay.compute(result, HistogramMode.RGB)
        HistogramDisplay.compute(result, HistogramMode.PARADE)
        HistogramDisplay.compute(result, HistogramMode.LUMA)
        assertArrayEquals(before, result.bins(Channel.RED))
        assertEquals(clipBefore, result.blackClipped(Channel.RED))
        // And the clip indicators still fire on the untouched raw data.
        assertTrue(result.isBlackClipped(Channel.RED))
        assertTrue(result.isWhiteClipped(Channel.RED))
    }

    @Test
    fun smoothing_preserves_a_flat_histogram_exactly() {
        // A perfectly flat histogram is the smoothing's fixed point:
        // every smoothed value equals the raw value (edge-replicate
        // keeps the ends flat too).
        val bins = IntArray(256) { 42 }
        val smoothed = HistogramDisplay.smooth(bins)
        for (i in bins.indices) {
            assertEquals(42f, smoothed[i], 0.0001f)
        }
    }

    // ---- common scale across channels ----

    @Test
    fun rgb_mode_scales_all_channels_by_one_denominator() {
        // Red peaks at 100, green at 50, blue at 25 (same bin). With
        // ONE common denominator — the largest 99th-percentile smoothed
        // count among the FILLED channels, red's — the drawn heights
        // keep their true proportions instead of equalizing.
        val display = HistogramDisplay.compute(spikeResult(), HistogramMode.RGB)
        // All four channels are drawn in RGB mode (luma rides along).
        assertEquals(4, display.size)
        val red = display.getValue(Channel.RED)
        val green = display.getValue(Channel.GREEN)
        val blue = display.getValue(Channel.BLUE)
        // The tallest channel touches the top (its peak exceeds the
        // percentile denominator and clamps at 1)...
        assertEquals(1f, red.max(), 0.001f)
        // ...while the others keep their true relative heights — the
        // common scale. Per-channel scaling would push every channel's
        // peak to 1.0 and lie about relative levels.
        assertTrue(green.max() in 0.7f..0.95f)
        assertTrue(blue.max() in 0.3f..0.5f)
        // And at an unclamped bin the proportions are exact: green is
        // half of red, blue a quarter — 50/100 and 25/100.
        assertEquals(red[203] * 0.5f, green[203], 0.001f)
        assertEquals(red[203] * 0.25f, blue[203], 0.001f)
    }

    @Test
    fun luma_mode_normalizes_by_luma_itself() {
        // Luma mode draws ONLY the luma channel, so the luma's own
        // percentile sets the scale (it is the only filled channel).
        val result = FrameHistogram.of(IntArray(100) { argb(255, 0, 0) })
        val display = HistogramDisplay.compute(result, HistogramMode.LUMA)
        assertEquals(1, display.size)
        assertEquals(1f, display.getValue(Channel.LUMA).max(), 0.02f)
    }

    @Test
    fun luma_outline_rides_the_rgb_denominator_and_clamps() {
        // In RGB mode the luma is drawn but NOT filled: its outline
        // rides the RGB channels' denominator. A pure-red frame's luma
        // peak (bin 53, 100 counts) towers over the channels' smoothed
        // percentiles, so the outline clamps flat at the top — never
        // above it, and never rescaling the RGB fills.
        val result = FrameHistogram.of(IntArray(100) { argb(255, 0, 0) })
        val display = HistogramDisplay.compute(result, HistogramMode.RGB)
        val luma = display.getValue(Channel.LUMA)
        assertTrue(luma.max() <= 1f)
        assertEquals(1f, luma.max(), 0.02f)
    }

    // ---- the 99th-percentile spike ignore ----

    @Test
    fun spike_ignore_keeps_the_midtone_mass_readable() {
        // 150 black pixels against 50 midtone pixels: a MAX-based scale
        // would let the black wall's smoothed peak set the top and
        // squash the midtone to ~0.11 of the graph. The 99th-percentile
        // denominator ignores the wall's top bins: the wall still
        // clamps at the top, and the midtone stays readable at ~0.65.
        val pixels = ArrayList<Int>()
        repeat(150) { pixels.add(argb(0, 0, 0)) }
        repeat(50) { pixels.add(argb(128, 128, 128)) }
        val result = FrameHistogram.of(pixels.toIntArray())
        val luma = HistogramDisplay.compute(result, HistogramMode.LUMA)
            .getValue(Channel.LUMA)
        assertEquals(1f, luma[0], 0.001f)
        assertTrue(luma[128] > 0.5f)
    }

    @Test
    fun a_handful_of_clipped_pixels_stay_a_blip() {
        // 199 midtone pixels and ONE black pixel: the midtone mass
        // tops the graph while the single clipped pixel reads as a
        // small blip at the bottom, not a full-height wall.
        val pixels = ArrayList<Int>()
        repeat(199) { pixels.add(argb(128, 128, 128)) }
        pixels.add(argb(0, 0, 0))
        val result = FrameHistogram.of(pixels.toIntArray())
        val luma = HistogramDisplay.compute(result, HistogramMode.LUMA)
            .getValue(Channel.LUMA)
        assertEquals(1f, luma[128], 0.02f)
        assertTrue(luma[0] < 0.05f)
    }

    // ---- mode channel sets ----

    @Test
    fun parade_mode_draws_only_the_three_channels() {
        // Parade = R, G, B side by side; no luma slot is drawn.
        val result = FrameHistogram.of(IntArray(10) { argb(10, 20, 30) })
        val display = HistogramDisplay.compute(result, HistogramMode.PARADE)
        assertEquals(3, display.size)
        assertFalse(display.containsKey(Channel.LUMA))
        assertTrue(display.containsKey(Channel.RED))
        assertTrue(display.containsKey(Channel.GREEN))
        assertTrue(display.containsKey(Channel.BLUE))
    }

    @Test
    fun empty_result_yields_all_zero_display_values() {
        // Nothing on screen: the overlay draws flat zero curves, no
        // NaN, no division by zero (the denominator floor is 1).
        val result = FrameHistogram.of(null)
        for (mode in HistogramMode.entries) {
            val display = HistogramDisplay.compute(result, mode)
            for (values in display.values) {
                for (v in values) {
                    assertEquals(0f, v, 0f)
                }
            }
        }
    }

    // ---- percentile99 ----

    @Test
    fun percentile99_ignores_the_top_one_percent() {
        // 256 values: the 99th percentile is sorted index 252 — the
        // top 3 values (253, 254, 255) are ignored.
        val values = FloatArray(256) { it.toFloat() }
        assertEquals(252f, HistogramDisplay.percentile99(values), 0f)
    }

    // ---- the RAW mode's display pipeline (feature N6) ----

    /** A RawHistogram with hand-set bins: spikes at bin 200, away from the edges. */
    private fun spikeRaw(): RawHistogram {
        val red = IntArray(RawHistogram.BINS)
        val green = IntArray(RawHistogram.BINS)
        val blue = IntArray(RawHistogram.BINS)
        red[200] = 100
        green[200] = 50
        blue[200] = 25
        return RawHistogram(
            frameIndex = 0, width = 4032, height = 3024,
            redBins = red, greenBins = green, blueBins = blue,
            whiteClips = IntArray(3), blackClips = IntArray(3),
            samples = intArrayOf(100, 50, 25),
            blackUsed = IntArray(4), whiteUsed = 1023,
        )
    }

    @Test
    fun raw_mode_scales_all_three_channels_by_one_denominator() {
        // The sensor domain gets the SAME common-scale discipline as
        // the processed RGB mode (red 100, green 50, blue 25 in one
        // bin): the tallest channel clamps at 1, the others keep their
        // true relative heights — per-channel scaling would lie about
        // relative CFA levels.
        val display = HistogramDisplay.computeRaw(spikeRaw())
        assertEquals(3, display.size)
        val red = display.getValue(RawHistogram.Channel.RED)
        val green = display.getValue(RawHistogram.Channel.GREEN)
        val blue = display.getValue(RawHistogram.Channel.BLUE)
        assertEquals(1f, red.max(), 0.001f)
        assertEquals(red[203] * 0.5f, green[203], 0.001f)
        assertEquals(red[203] * 0.25f, blue[203], 0.001f)
    }

    @Test
    fun raw_mode_smoothing_never_touches_the_sensor_counts() {
        // The owner's raw-counts-stay-raw rule, sensor domain edition:
        // computeRaw smooths a COPY; the bins and clip counts must be
        // bitwise unchanged afterwards, so the RAW clip indicators
        // (which read the exact sensor counts) can never be created or
        // hidden by the display smoothing.
        val raw = spikeRaw()
        val binsBefore = raw.bins(RawHistogram.Channel.RED).copyOf()
        val clipsBefore = raw.whiteClipped(RawHistogram.Channel.RED)
        HistogramDisplay.computeRaw(raw)
        assertArrayEquals(binsBefore, raw.bins(RawHistogram.Channel.RED))
        assertEquals(clipsBefore, raw.whiteClipped(RawHistogram.Channel.RED))
    }

    @Test
    fun raw_mode_unavailable_is_an_empty_map_never_a_fabricated_curve() {
        // Null stats (an encoded clip has no sensor data, or the fetch
        // failed) map to an EMPTY display — the view shows its honest
        // unavailability notice. The pipeline never invents a shape.
        assertTrue(HistogramDisplay.computeRaw(null).isEmpty())
    }

    @Test
    fun raw_mode_empty_bins_yield_all_zero_display_values() {
        // A degenerate all-zero sensor histogram draws flat zero
        // curves, no NaN, no division by zero (the denominator floor).
        val zero = RawHistogram(
            frameIndex = 0, width = 4032, height = 3024,
            redBins = IntArray(RawHistogram.BINS),
            greenBins = IntArray(RawHistogram.BINS),
            blueBins = IntArray(RawHistogram.BINS),
            whiteClips = IntArray(3), blackClips = IntArray(3),
            samples = IntArray(3), blackUsed = IntArray(4), whiteUsed = 1023,
        )
        val display = HistogramDisplay.computeRaw(zero)
        for (values in display.values) {
            for (v in values) {
                assertEquals(0f, v, 0f)
            }
        }
    }
}
