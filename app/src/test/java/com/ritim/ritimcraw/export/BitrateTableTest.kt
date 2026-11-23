package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateTableTest {

    @Test
    fun avc_1080p30_standard_is_the_12_mbps_anchor() {
        assertEquals(12.0, BitrateTable.mbps(BitrateLevel.STANDARD, 1920, 1080, 30.0, ExportCodec.AVC), 0.0)
    }

    @Test
    fun levels_scale_the_anchor_by_their_factor() {
        fun at(l: BitrateLevel) = BitrateTable.mbps(l, 1920, 1080, 30.0, ExportCodec.AVC)
        assertEquals(7.0, at(BitrateLevel.LOW), 0.0)       // 16*0.45 = 7.2 -> 7.0
        assertEquals(19.0, at(BitrateLevel.HIGH), 0.0)     // 16*1.2 = 19.2 -> 19.0
        assertEquals(38.5, at(BitrateLevel.MAX), 0.0)      // 16*2.4 = 38.4 -> 38.5
        assertTrue(at(BitrateLevel.LOW) < at(BitrateLevel.STANDARD))
        assertTrue(at(BitrateLevel.STANDARD) < at(BitrateLevel.HIGH))
        assertTrue(at(BitrateLevel.HIGH) < at(BitrateLevel.MAX))
    }

    @Test
    fun hevc_is_sixty_percent_of_avc() {
        // 16 * 0.75 * 0.6 = 7.2 -> 7.0
        assertEquals(7.0, BitrateTable.mbps(BitrateLevel.STANDARD, 1920, 1080, 30.0, ExportCodec.HEVC), 0.0)
        assertEquals(4.5, BitrateTable.mbps(BitrateLevel.LOW, 1920, 1080, 30.0, ExportCodec.HEVC), 0.0)
    }

    @Test
    fun bitrate_follows_pixel_rate_sublinearly() {
        // 4K30 is 4x the pixel rate of 1080p30; 4^0.85 = 3.249x, not 4x.
        assertEquals(39.0, BitrateTable.mbps(BitrateLevel.STANDARD, 3840, 2160, 30.0, ExportCodec.AVC), 0.0)
        // the fps matters: 4K60 STANDARD > 4K30 STANDARD
        assertTrue(
            BitrateTable.mbps(BitrateLevel.STANDARD, 3840, 2160, 60.0, ExportCodec.AVC) >
                BitrateTable.mbps(BitrateLevel.STANDARD, 3840, 2160, 30.0, ExportCodec.AVC)
        )
    }

    @Test
    fun result_is_clamped_and_half_megabit_rounded() {
        assertEquals(0.5, BitrateTable.mbps(BitrateLevel.LOW, 64, 64, 1.0, ExportCodec.HEVC), 0.0)
        val v = BitrateTable.mbps(BitrateLevel.MAX, 3840, 2160, 60.0, ExportCodec.AVC)
        assertEquals(0.0, (v * 2) % 1.0, 0.0)
        assertEquals(225.0, v, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun custom_has_no_table_value() {
        BitrateTable.mbps(BitrateLevel.CUSTOM, 1920, 1080, 30.0, ExportCodec.AVC)
    }

    @Test
    fun legacy_formula_is_the_old_writer_bitrate() {
        // 4K: 3840*2160*2.4 = 19.9 Mbps; tiny clips floor at 2; huge clips cap at 25.
        assertEquals(19_906_560, BitrateTable.legacyBitsPerSecond(3840, 2160))
        assertEquals(2_000_000, BitrateTable.legacyBitsPerSecond(320, 240))
        assertEquals(25_000_000, BitrateTable.legacyBitsPerSecond(7680, 4320))
    }
}
