package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.RawHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope clip readout: the measured share behind the clip pills is
 * read from the pipeline's own raw counts, lit only at the existing
 * 0.1% threshold, and reports the worst channel the view draws.
 */
class ScopeClipReadoutTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun clean_frame_has_no_readout() {
        val result = FrameHistogram.of(IntArray(1000) { argb(128, 128, 128) })
        assertFalse(ScopeClipReadout.of(result, HistogramMode.RGB).any)
    }

    @Test
    fun empty_result_has_no_readout() {
        assertFalse(ScopeClipReadout.of(FrameHistogram.of(null), HistogramMode.RGB).any)
    }

    @Test
    fun highlight_share_is_the_worst_drawn_channel() {
        // 1000 px: 50 with red blown (2 of them fully white), rest mid-gray.
        val px = IntArray(1000) { argb(128, 128, 128) }
        for (i in 0 until 50) px[i] = argb(255, 128, 128)
        px[50] = argb(255, 255, 255)
        px[51] = argb(255, 255, 255)
        val result = FrameHistogram.of(px)
        val rgb = ScopeClipReadout.of(result, HistogramMode.RGB)
        assertEquals(5.2f, rgb.highlightPercent!!, 0.01f)
        assertNull(rgb.shadowPercent)
        assertTrue(0 in rgb.highlightChannels)
        // Luma mode only reads the luma channel: 2 px of 1000 = 0.2%.
        val luma = ScopeClipReadout.of(result, HistogramMode.LUMA)
        assertEquals(0.2f, luma.highlightPercent!!, 0.01f)
        assertEquals(listOf(FrameHistogram.Channel.LUMA.ordinal), luma.highlightChannels)
    }

    @Test
    fun raw_readout_uses_per_channel_sample_counts() {
        val raw = RawHistogram(
            frameIndex = 0, width = 10, height = 10,
            redBins = IntArray(RawHistogram.BINS), greenBins = IntArray(RawHistogram.BINS),
            blueBins = IntArray(RawHistogram.BINS),
            whiteClips = intArrayOf(10, 0, 0), blackClips = IntArray(3),
            samples = intArrayOf(100, 200, 100),
            blackUsed = IntArray(4), whiteUsed = 1023,
        )
        val readout = ScopeClipReadout.ofRaw(raw)
        assertEquals(10f, readout.highlightPercent!!, 0.001f)
        assertEquals(listOf(0), readout.highlightChannels)
        assertFalse(ScopeClipReadout.ofRaw(null).any)
    }

    @Test
    fun format_is_compact() {
        assertEquals("<0.1%", ScopeClipReadout.format(0.05f))
        assertEquals("0.4%", ScopeClipReadout.format(0.4f))
        assertEquals("9.9%", ScopeClipReadout.format(9.94f))
        assertEquals("12%", ScopeClipReadout.format(12.3f))
    }
}
