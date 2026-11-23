package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTextTest {
    private fun report(bytes: Long?, dur: Double?, steps: List<String> = emptyList()) = ExportReport(
        "enc", true, "video/hevc", 1920, 1080, 29.97, 8, "VBR", null, 1f, 8_000_000, 128, bytes, dur, steps, emptyList(),
    )

    @Test
    fun formats_are_plain_and_locale_independent() {
        assertEquals("HEVC", ExportText.codec("video/hevc"))
        assertEquals("H.264", ExportText.codec("video/avc"))
        assertEquals("30", ExportText.fps(30.0))
        assertEquals("29.97", ExportText.fps(29.97))
        assertEquals("23.976", ExportText.fps(23.976))
        assertEquals("8.5", ExportText.mbps(8_500_000))
        assertEquals("1.0 MB", ExportText.megabytes(1_048_576))
        assertEquals("120 MB", ExportText.megabytes(120L * 1_048_576))
    }

    @Test
    fun real_bitrate_is_file_size_over_duration_minus_audio() {
        // 10 MB over 10 s = 8_388_608 bps total; audio 128 kbps comes off the video share
        val r = report(10L * 1_048_576, 10.0)
        assertEquals(8_388_608L, r.actualTotalBps)
        assertEquals(8_388_608L - 128_000L, r.actualVideoBps)
        assertNull(report(null, 10.0).actualVideoBps)
        assertNull(report(100, 0.0).actualTotalBps)
    }

    @Test
    fun the_trail_tells_whether_a_fix_or_a_cpu_fallback_happened() {
        assertFalse(ExportText.wasFixed(report(1, 1.0)))
        assertTrue(ExportText.wasFixed(report(1, 1.0, listOf("plan: a", "fix: b"))))
        assertTrue(ExportText.fellBackToCpu(report(1, 1.0, listOf("surface->cpu: x"))))
    }
}
