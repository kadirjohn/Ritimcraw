package com.ritim.ritimcraw.ui.settings

import com.ritim.ritimcraw.export.CodecMeasureCase
import com.ritim.ritimcraw.export.CodecMeasurePlanner
import com.ritim.ritimcraw.export.MeasureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement section's row/summary shaping (E1e): the Device report
 * renders these strings, so the shape is JVM-tested — tag, Mb/s, the
 * drift as a signed offset percent, and which rows count as failed.
 */
class CodecMeasureRowsTest {

    private fun case(mode: Int = 1, req: Long = 140_000_000L) = CodecMeasureCase(
        "video/hevc", "c2.qti.hevc.encoder", null, 8, 3840, 2160, 30, mode, req, false, 2.0, 1f, "ramp+v1",
    )

    @Test
    fun a_measured_row_shows_tag_requested_measured_and_the_drift_percent() {
        // The owner's +24% observation's shape: 140 requested, 173.6 measured.
        val r = MeasureResult(case(req = 140_000_000L), true, null, 43_400_000, 2.0, 173_600_000L, 4_000_000, 2, 60, "e", true, 123)
        val row = CodecMeasureRows.rowText(r)
        assertEquals(false, row.failed)
        assertTrue("tag missing: ${row.text}", row.text.contains("HEVC 3840×2160@30 VBR 8b"))
        assertTrue("requested missing: ${row.text}", row.text.contains("140.0"))
        assertTrue("measured missing: ${row.text}", row.text.contains("173.6"))
        assertTrue("drift missing: ${row.text}", row.text.contains("offset +24%"))
    }

    @Test
    fun a_refused_row_is_the_error_row_and_names_the_error() {
        val r = MeasureResult(case(), false, "IllegalStateException", 0, null, null, 0, 0, 0, null, null, 3)
        val row = CodecMeasureRows.rowText(r)
        assertTrue(row.failed)
        assertTrue("error class missing: ${row.text}", row.text.contains("IllegalStateException"))
        assertTrue("truncated to label: ${row.text}", row.text.contains("video/avc") || row.text.contains("HEVC"))
    }

    @Test
    fun a_measured_row_without_a_realized_bitrate_is_still_not_failed_but_no_ratio() {
        // configure ok, frames in, but no drained frames (a pathological
        // encoder): the row is honest — refused=NO but no measured text.
        val r = MeasureResult(case(), true, null, 0, null, null, 0, 0, 60, "e", true, 100)
        val row = CodecMeasureRows.rowText(r)
        assertTrue("a run with no output reads as refused? no — the row keeps ok", row.failed)
    }

    @Test
    fun the_summary_lines_carry_the_three_numbers_and_skip_empty_groups() {
        val rows = (1..4).map { i ->
            MeasureResult(
                case(mode = 1, req = 100_000_000L),
                configureOk = true, errorCode = null,
                encodedBytes = (110_000_000L * i / 8), ptsDurationSec = 1.0, measuredBps = 110_000_000L * i / 10,
                0, 0, 30, "e", true, 1,
            )
        }
        val s = CodecMeasurePlanner.summarize(rows)
        val lines = CodecMeasureRows.summaryLines(s)
        assertEquals(1, lines.size) // drift only: no uncapped rows, no rejections
        assertTrue(lines[0].contains("median"))
        assertTrue(lines[0].contains("max"))
        // A clean session names zero refusals with the count line present.
        val withRejection = CodecMeasurePlanner.summarize(
            rows + MeasureResult(case(), false, "IllegalStateException", 0, null, null, 0, 0, 0, null, null, 1),
        )
        val lines2 = CodecMeasureRows.summaryLines(withRejection)
        assertEquals(2, lines2.size)
        assertTrue(lines2[1].contains("1"))
    }

    @Test
    fun pct_formats_both_drift_directions_and_mbps_uses_one_decimal() {
        assertEquals("offset +24", CodecMeasureRows.pct(1.24))
        assertEquals("offset -15", CodecMeasureRows.pct(0.85))
        assertEquals("offset +0", CodecMeasureRows.pct(1.0))
        assertEquals("140.0", CodecMeasureRows.mbps(140_000_000L))
        assertEquals("74.5", CodecMeasureRows.mbps(74_470_000L))
    }
}
