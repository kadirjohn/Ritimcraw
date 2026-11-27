package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the per-control GPU-vs-CPU sweep (2026-11-23). */
class GradeParitySweepTest {

    @Test
    fun case_list_covers_every_control_at_the_requested_values() {
        val cases = GradeParitySweep.cases()
        // 4 exposure + 11 percent controls x 4 + 3 tonemap + 1 lut + 1 combined + 3 warp
        // + 3 recovery / stage-3 cases + 2 glitter-suppression cases.
        assertEquals(4 + 11 * 4 + 3 + 1 + 1 + 3 + 3 + 2, cases.size)
        val controls = cases.map { it.control }.toSet()
        for (c in listOf(
            "exposure", "highlights", "blackPoint", "whitePoint", "shadows", "midtones",
            "contrast", "saturation", "temp", "tint", "sharpness", "detail", "tonemap", "lut", "combined",
        )) assertTrue(c, c in controls)
        assertEquals(
            listOf(-3f, -1f, 1f, 3f),
            cases.filter { it.control == "exposure" }.map { it.grade.exposureEv },
        )
        val hi = cases.filter { it.control == "highlights" }.map { it.grade.highlights }
        assertEquals(listOf(-100f, -50f, 50f, 100f), hi)
        val temp = cases.first { it.control == "temp" && it.label == "+50" }.grade
        assertEquals(false, temp.asShot)
        assertEquals(7100f, temp.tempK, 0.001f)
    }

    @Test
    fun warp_cases_carry_a_matrix_and_a_non_neutral_grade() {
        val warp = GradeParitySweep.cases().filter { it.warpM != null }
        assertEquals(3, warp.size)
        val id = warp.single { it.isWarpIdentity }
        assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), id.warpM!!.toList())
        assertEquals(1f, id.grade.exposureEv, 0f)
        val rot = warp.filter { it.isWarpReal }
        assertEquals(2, rot.size)
        for (c in rot) {
            assertEquals(9, c.warpM!!.size)
            assertTrue(c.warpM!![1] != 0f)  // a real rotation, not identity
            assertTrue(c.grade.exposureEv != 0f)
        }
        // No non-warp case carries a matrix.
        assertTrue(GradeParitySweep.cases().filter { it.warpM == null }.none { it.isWarpIdentity || it.isWarpReal })
    }

    @Test
    fun compare_reports_max_mismatch_and_over1_over_unsigned_bytes() {
        val cpu = byteArrayOf(0, 10, 100, 200.toByte(), 255.toByte(), 5)
        val gpu = byteArrayOf(0, 11, 98, 200.toByte(), 250.toByte(), 5)
        val s = GradeParitySweep.compare(gpu, cpu)
        assertEquals(6L, s.samples)
        assertEquals(3L, s.mismatch)
        assertEquals(2L, s.over1)
        assertEquals(5, s.maxDiffLsb)
        assertEquals(50.0, s.mismatchPct, 1e-9)
    }

    @Test
    fun inert_flag_marks_a_control_that_moves_cpu_but_not_gpu() {
        val c = GradeParitySweep.Case("highlights", "-100", com.ritim.ritimcraw.data.GradeK())
        val s = GradeParitySweep.Stats(100, 40, 80, 80, 0.0)
        assertTrue(GradeParitySweep.line(c, s, 35.0, 0.0).endsWith("INERT_ON_GPU"))
        assertTrue(!GradeParitySweep.line(c, s, 35.0, 30.0).contains("INERT_ON_GPU"))
    }

    // ---- the A2 crop sweep (owner request 2026-11-23, guide A2) ----

    @Test
    fun `crop case list covers the rects and the angle extremes`() {
        // Rects (a centered 16:9, an off-center, the 5 percent floor) x
        // angles {0, +0.5, -0.5, +7, -7, +15, -15} — the plan's device
        // grid, incl. the ±15 extremes and the 5% floor.
        val cases = GradeParitySweep.cropCases()
        assertEquals(3 * 7, cases.size)
        val angles = cases.map { it.deg }.toSet()
        for (a in listOf(0f, 0.5f, -0.5f, 7f, -7f, 15f, -15f)) {
            assertTrue("deg $a", a in angles)
        }
        // The centered 16:9 case: the standard rect.
        val center = cases.filter { it.x == 0.125f && it.y == 0.25f }
        assertEquals(7, center.size)
        // The floor case: the 5 percent rect.
        val floor = cases.filter { it.w == 0.05f && it.h == 0.05f }
        assertTrue(floor.isNotEmpty())
        // The off-center case: no edge alignment.
        val off = cases.filter { it.x == 0.4f && it.y == 0.35f }
        assertTrue(off.isNotEmpty())
        // Every case's manual delta is 0 (the clip's real container
        // orientation exercises uOrientation natively).
        assertTrue(cases.all { it.manualDeltaDeg == 0 })
    }

    @Test
    fun `argbBytes converts an ARGB int pack to RGB bytes`() {
        // The GPU leg parses ARGB ints ([w, h, 0xFFRRGGBB...]); the CPU
        // legs parse the same shape. The compare runs over RGB bytes
        // (three channels per pixel, the grade sweep's own convention).
        val argb = intArrayOf(
            (0xFF shl 24) or (18 shl 16) or (52 shl 8) or 240,  // r g b
            (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 128,
        )
        val bytes = GradeParitySweep.argbBytes(argb)
        assertEquals(6, bytes.size)
        assertEquals(18, bytes[0].toInt() and 0xFF)
        assertEquals(52, bytes[1].toInt() and 0xFF)
        assertEquals(240, bytes[2].toInt() and 0xFF)
        assertEquals(255, bytes[3].toInt() and 0xFF)
        assertEquals(0, bytes[4].toInt() and 0xFF)
        assertEquals(128, bytes[5].toInt() and 0xFF)
    }

    @Test
    fun `crop line carries rect deg and the lsb numbers`() {
        val line = GradeParitySweep.cropLine(
            x = 0.125f, y = 0.25f, w = 0.75f, h = 0.5f, deg = 7f,
            s = GradeParitySweep.Stats(samples = 552960L, maxDiffLsb = 2, mismatch = 1234L, over1 = 0L, meanSigned = 0.25),
        )
        assertTrue(line, line.startsWith("crop-sweep rect=0.125,0.250,0.750,0.500 deg=7.0:"))
        assertTrue(line, line.contains("maxDiffLsb=2"))
        assertTrue(line, line.contains("samples=552960"))
    }

    @Test
    fun `crop summary line names the worst case`() {
        val summary = GradeParitySweep.cropSummaryLine(
            lines = listOf(
                Pair(GradeParitySweep.CropCase(0.125f, 0.25f, 0.75f, 0.5f, 7f), GradeParitySweep.Stats(100, 2, 1, 0, 0.0)),
                Pair(GradeParitySweep.CropCase(0.4f, 0.35f, 0.45f, 0.4f, -15f), GradeParitySweep.Stats(100, 4, 2, 1, 0.0)),
            ),
            skipped = 1,
        )
        assertTrue(summary, summary.startsWith("crop-sweep summary:"))
        assertTrue(summary, summary.contains("worstMaxDiffLsb=4"))
        assertTrue(summary, summary.contains("skipped=1"))
    }

    // ---- the v2 chain sweep (color model v2 F1-7, plan §10 layer 4) ----

    @Test
    fun v2_case_grid_covers_the_plan_layer4_list() {
        val cases = GradeParitySweep.v2Cases()
        // nodes-primary x3 + nodes-log x2 + nodes-curves + nodes-hue +
        // nodes-temp + nodes-chain + drt-sdr + drt-hlg + nodes+drt.
        assertEquals(12, cases.size)
        val controls = cases.map { it.control }.toSet()
        for (c in listOf(
            "nodes-primary", "nodes-log", "nodes-curves", "nodes-hue",
            "nodes-temp", "nodes-chain", "drt-sdr", "drt-hlg", "nodes+drt",
        )) assertTrue(c, c in controls)
    }

    @Test
    fun v2_cases_carry_the_chain_wire_and_the_drt_pack_only_when_declared() {
        for (c in GradeParitySweep.v2Cases()) {
            // Every case's wire is the encoder's own shape for its grade.
            assertTrue(c.chainWire.isNotEmpty())
            assertTrue(NodeChainWire.chainHash(c.chainWire) != 0L)
            if (c.control.startsWith("drt") || c.control.contains("+drt")) {
                assertEquals(1f, c.chainWire[1], 0f) // outputMode DwgLinear
                assertNotNull(c.drtUniforms)
                assertEquals(OpenDrtPacks.SLOT_COUNT, c.drtUniforms!!.size)
            } else {
                assertEquals(0f, c.chainWire[1], 0f) // outputMode legacy
                assertNull(c.drtUniforms)
            }
        }
    }

    @Test
    fun v2_cases_never_carry_node_luts_and_stay_under_the_n1_cap() {
        for (c in GradeParitySweep.v2Cases()) {
            // [0] = the trimmed nodeCount; the grid never exceeds N1 and
            // (the encoder's own LUT refusal implies) never carries one.
            assertTrue(c.chainWire[0] <= NodeChainWire.MAX_NODES)
            assertTrue(c.chainWire[0] >= 0f)
        }
    }

    @Test
    fun frame_digest_is_the_ieee_crc32() {
        // The standard check vectors (IEEE 802.3, reflected 0xEDB88320):
        // "" -> 0, "123456789" -> 0xCBF43926 — core's crc32Ieee's own
        // polynomial; the sweep's karne line and the owner's before/after
        // greps ride this exact function.
        assertEquals(0L, GradeParitySweep.frameDigest(ByteArray(0)))
        assertEquals(0xCBF43926L,
            GradeParitySweep.frameDigest("123456789".toByteArray(Charsets.US_ASCII)))
        // And the empty array's identity is NOT the zero byte's.
        assertEquals(0xD202EF8DL, GradeParitySweep.frameDigest(byteArrayOf(0)))
    }

    @Test
    fun v2_line_and_summary_formats() {
        val c = GradeParitySweep.V2Case(
            "nodes-primary", "lift", FloatArray(9), null, com.ritim.ritimcraw.data.GradeK(),
        )
        val line = GradeParitySweep.v2Line(c, GradeParitySweep.Stats(1, 10, 0, 0, 0.0))
        assertTrue(line.startsWith("v2-sweep nodes-primary lift:"))
        assertTrue(line.contains("maxDiffLsb=10"))
        val summary = GradeParitySweep.v2SummaryLine(
            listOf(c to GradeParitySweep.Stats(1, 10, 0, 0, 0.0)), 3,
        )
        assertTrue(summary.startsWith("v2-sweep summary: cases=1 skipped=3"))
        assertTrue(summary.contains("(nodes-primary lift)"))
    }

    @Test
    fun the_diagnostic_crop_cases_vary_one_thing_and_use_whole_pixels() {
        val d = GradeParitySweep.cropDiagnosticCases()
        assertEquals(8, d.size)
        // D1: on the 256x144 preview the rect lands on whole pixels, so any 0 deg mismatch
        // on it cannot be rounding.
        val d1 = d[0]
        assertEquals(0f, d1.deg, 0f)
        assertEquals(104f, d1.x * 256f, 1e-3f)
        assertEquals(45f, d1.y * 144f, 1e-3f)
        assertEquals(120f, d1.w * 256f, 1e-3f)
        assertEquals(64f, d1.h * 144f, 1e-3f)
        // D2: only x moved, by half a pixel.
        assertEquals(0.5f, (d[1].x - d1.x) * 256f, 1e-3f)
        assertEquals(d1.y, d[1].y, 0f); assertEquals(d1.w, d[1].w, 0f); assertEquals(d1.h, d[1].h, 0f)
        // D3: the same rect, only the angle differs and grows.
        assertEquals(listOf(0.05f, 0.1f, 0.25f, 0.5f), d.subList(2, 6).map { it.deg })
        assertTrue(d.subList(2, 6).all { it.x == d1.x && it.w == d1.w && it.y == d1.y && it.h == d1.h })
        // D4: the whole frame.
        assertTrue(d.subList(6, 8).all { it.x == 0f && it.y == 0f && it.w == 1f && it.h == 1f })
        // None of them repeats a standard case.
        assertTrue(d.none { it in GradeParitySweep.cropCases() })
    }

    @Test
    fun the_outlier_split_counts_big_diffs_and_how_many_hug_the_border() {
        val w = 6
        val h = 6
        val a = ByteArray(w * h * 3)
        val b = ByteArray(w * h * 3)
        fun set(x: Int, y: Int, d: Int) { b[(y * w + x) * 3 + 1] = d.toByte() }
        set(0, 0, 10)  // corner: over 2, in the band
        set(3, 3, 10)  // interior: over 2, not in the band
        set(2, 2, 2)   // exactly 2: not an outlier
        val (total, inBand) = GradeParitySweep.outlierSplit(a, b, w, h)
        assertEquals(2, total)
        assertEquals(1, inBand)
    }
}
