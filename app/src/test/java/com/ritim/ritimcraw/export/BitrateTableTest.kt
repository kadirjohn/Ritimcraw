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

    // ---- ladder up to the encoder's listed ceiling (P2, 2026-11-25) ----

    @Test
    fun the_existing_levels_keep_their_values_to_the_half_megabit() {
        // The presets lean on these: Compatible = STANDARD, Smaller = LOW, Quality = MAX.
        fun hevc(l: BitrateLevel) = BitrateTable.mbps(l, 3840, 2160, 30.0, ExportCodec.HEVC)
        assertEquals(75.0, hevc(BitrateLevel.MAX), 0.0)
        assertEquals(37.5, hevc(BitrateLevel.HIGH), 0.0)
        assertEquals(23.5, hevc(BitrateLevel.STANDARD), 0.0)
        assertEquals(14.0, hevc(BitrateLevel.LOW), 0.0)
        // A ceiling must not move them.
        assertEquals(75.0, BitrateTable.mbps(BitrateLevel.MAX, 3840, 2160, 30.0, ExportCodec.HEVC, ceilingMbps = 160.0), 0.0)
    }

    @Test
    fun a_160_ceiling_over_a_75_max_adds_two_steps_ending_at_the_ceiling() {
        val steps = BitrateTable.extraSteps(75.0, 160.0)
        assertEquals(listOf(BitrateLevel.HIGHER, BitrateLevel.CEILING), steps.map { it.first })
        assertEquals(117.5, steps[0].second, 0.0)
        assertEquals(160.0, steps[1].second, 0.0)
    }

    @Test
    fun steps_never_pass_the_ceiling_and_a_fractional_ceiling_rounds_down() {
        for (c in listOf(100.0, 160.0, 220.0, 161.9, 400.0)) {
            for ((_, v) in BitrateTable.extraSteps(75.0, c)) assertTrue("$v > $c", v <= c)
        }
        assertEquals(161.5, BitrateTable.extraSteps(75.0, 161.9).last().second, 0.0)
    }

    @Test
    fun no_room_above_max_means_no_extra_steps() {
        assertTrue(BitrateTable.extraSteps(75.0, null).isEmpty())
        assertTrue(BitrateTable.extraSteps(75.0, 80.0).isEmpty())
        assertTrue(BitrateTable.extraSteps(75.0, 93.0).isEmpty())
        // Just enough room for the ceiling, not for a middle step.
        assertEquals(listOf(BitrateLevel.CEILING), BitrateTable.extraSteps(75.0, 100.0).map { it.first })
    }

    @Test
    fun the_new_levels_resolve_through_the_table_and_fall_back_to_max_without_a_ceiling() {
        fun at(l: BitrateLevel, c: Double?) = BitrateTable.mbps(l, 3840, 2160, 30.0, ExportCodec.HEVC, c)
        assertEquals(160.0, at(BitrateLevel.CEILING, 160.0), 0.0)
        assertEquals(117.5, at(BitrateLevel.HIGHER, 160.0), 0.0)
        assertEquals(75.0, at(BitrateLevel.CEILING, null), 0.0)
        assertEquals(75.0, at(BitrateLevel.HIGHER, 80.0), 0.0)
    }

    // ---- the Quality level (owner decision 2026-11-25: Quality = 160 Mb/s HEVC) ----

    @Test
    fun the_quality_level_is_160_at_4k30_hevc_and_scales_with_the_picture_below_it() {
        fun q(w: Int, h: Int, fps: Double, ceil: Double? = null) =
            BitrateTable.mbps(BitrateLevel.QUALITY, w, h, fps, ExportCodec.HEVC, ceil)
        assertEquals(160.0, q(3840, 2160, 30.0), 0.0)
        // 1080p30 is a quarter of the pixel rate: the table's own ^0.85 scaling, not 160.
        assertEquals(49.0, q(1920, 1080, 30.0), 0.0)
        assertTrue(q(1920, 1080, 30.0) < q(2560, 1440, 30.0) && q(2560, 1440, 30.0) < q(3840, 2160, 30.0))
    }

    @Test
    fun the_quality_level_never_asks_above_the_encoders_listed_ceiling() {
        // 4K60 would scale to ~288; with the 160 ceiling it reads 160, with none it is the scaled value.
        assertEquals(160.0, BitrateTable.mbps(BitrateLevel.QUALITY, 3840, 2160, 60.0, ExportCodec.HEVC, 160.0), 0.0)
        assertTrue(BitrateTable.mbps(BitrateLevel.QUALITY, 3840, 2160, 60.0, ExportCodec.HEVC, null) > 250.0)
    }
}
