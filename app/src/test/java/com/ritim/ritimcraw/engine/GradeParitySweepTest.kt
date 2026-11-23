package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the per-control GPU-vs-CPU sweep (2026-11-23). */
class GradeParitySweepTest {

    @Test
    fun case_list_covers_every_control_at_the_requested_values() {
        val cases = GradeParitySweep.cases()
        // 4 exposure + 11 percent controls x 4 + 1 combined.
        assertEquals(4 + 11 * 4 + 1, cases.size)
        val controls = cases.map { it.control }.toSet()
        for (c in listOf(
            "exposure", "highlights", "blackPoint", "whitePoint", "shadows", "midtones",
            "contrast", "saturation", "temp", "tint", "sharpness", "detail", "combined",
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
}
