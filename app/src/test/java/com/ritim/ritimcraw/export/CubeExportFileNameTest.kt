package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The save sheet's suggested name: clip name, local date-time, .cube. */
class CubeExportFileNameTest {

    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private val now: Instant =
        ZonedDateTime.of(2026, 11, 27, 16, 22, 41, 0, zone).toInstant()
    private val stamp = "_2026-11-27_1622.cube"

    private fun name(clip: String) = CubeExportPlan.suggestedFileName(clip, now, zone)

    @Test
    fun `mcraw extension is stripped`() {
        assertEquals("yavas_pan$stamp", name("yavas_pan.mcraw"))
    }

    @Test
    fun `an inner dot is kept`() {
        assertEquals("clip.v2$stamp", name("clip.v2.mcraw"))
    }

    @Test
    fun `only the last known extension is stripped`() {
        assertEquals("a.mov$stamp", name("a.mov.mp4"))
        assertEquals("a$stamp", name("a.mov"))
        assertEquals("a$stamp", name("a.MP4"))
        assertEquals("clip.v2$stamp", name("clip.v2"))
    }

    @Test
    fun `unsafe characters become underscores`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j$stamp", name("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("a_b$stamp", name("a\nb"))
        assertEquals("a_b$stamp", name("a\u0000b"))
    }

    @Test
    fun `whitespace collapses and is trimmed`() {
        assertEquals("my_clip_4$stamp", name("  my   clip 4  "))
    }

    @Test
    fun `empty or blank names fall back to lut`() {
        assertEquals("lut$stamp", name(""))
        assertEquals("lut$stamp", name("   "))
        assertEquals("lut$stamp", name(".mcraw"))
        assertEquals("lut$stamp", name("/"))
    }

    @Test
    fun `date time is zero padded and uses the given zone`() {
        val early = ZonedDateTime.of(2026, 1, 5, 7, 3, 0, 0, zone).toInstant()
        assertEquals(
            "clip_2026-01-05_0703.cube",
            CubeExportPlan.suggestedFileName("clip.mcraw", early, zone),
        )
        assertEquals(
            "clip_2026-01-05_0403.cube",
            CubeExportPlan.suggestedFileName("clip", early, ZoneOffset.UTC),
        )
    }

    @Test
    fun `result always ends with cube and has no grid size`() {
        for (n in listOf("", "x", "yavas_pan.mcraw", "///")) {
            assertTrue(name(n).endsWith(".cube"))
        }
        assertEquals("yavas_pan_2026-11-27_1622.cube", name("yavas_pan.mcraw"))
    }
}
