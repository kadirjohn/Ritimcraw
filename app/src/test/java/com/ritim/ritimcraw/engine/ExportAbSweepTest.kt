package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parts of the export A/B regression harness (guide §A7,
 * 2026-11-23): the 4-case list, the first/middle/near-end frame
 * indices, the EXPORT_MISMATCH flag rule at both threshold
 * boundaries, and the two log line formats.
 */
class ExportAbSweepTest {

    @Test
    fun cases_are_the_four_fixed_axes_in_order() {
        val cases = ExportAbSweep.cases()
        assertEquals(listOf("neutral", "grade", "stab", "grade-stab"), cases.map { it.id })
        assertEquals(false, cases[0].stabilization)
        assertEquals(false, cases[1].stabilization)
        assertEquals(true, cases[2].stabilization)
        assertEquals(true, cases[3].stabilization)
    }

    @Test
    fun neutral_case_is_the_all_default_grade() {
        val neutral = ExportAbSweep.cases().first { it.id == "neutral" }
        assertEquals(GradeK(), neutral.grade)
    }

    @Test
    fun grade_only_case_pins_every_grade_field_with_stabilization_off() {
        val grade = ExportAbSweep.cases().first { it.id == "grade" }.grade
        assertEquals(1f, grade.exposureEv, 0f)
        assertEquals(-50f, grade.highlights, 0f)
        assertEquals(50f, grade.shadows, 0f)
        assertEquals(50f, grade.contrast, 0f)
        assertEquals(50f, grade.saturation, 0f)
        assertFalse(grade.stabilization)
        // The stab sub-values stay inert while the flag is off.
        assertEquals(GradeK().stabilizationMode, grade.stabilizationMode)
        assertEquals(GradeK().stabilizationStrength, grade.stabilizationStrength, 0f)
        assertEquals(GradeK().stabilizationZoomLimit, grade.stabilizationZoomLimit, 0f)
        assertEquals(GradeK().stabilizationTrimMs, grade.stabilizationTrimMs, 0f)
        assertEquals(GradeK().stabilizationFocalMm, grade.stabilizationFocalMm, 0f)
    }

    @Test
    fun stab_only_case_is_default_grade_plus_stabilization_defaults() {
        val stab = ExportAbSweep.cases().first { it.id == "stab" }.grade
        assertTrue(stab.stabilization)
        // No grade control moved: the pure stabilization axis.
        assertEquals(0f, stab.exposureEv, 0f)
        assertEquals(0f, stab.highlights, 0f)
        assertEquals(0f, stab.shadows, 0f)
        assertEquals(0f, stab.contrast, 0f)
        assertEquals(0f, stab.saturation, 0f)
        // The UI defaults ride along (strength 50, zoom 1.25, Smooth, no trim).
        assertEquals(0, stab.stabilizationMode)
        assertEquals(50f, stab.stabilizationStrength, 0f)
        assertEquals(1.25f, stab.stabilizationZoomLimit, 0f)
        assertEquals(0f, stab.stabilizationTrimMs, 0f)
        assertEquals(0f, stab.stabilizationFocalMm, 0f)
    }

    @Test
    fun grade_stab_case_is_the_grade_grade_with_stabilization_on() {
        val cases = ExportAbSweep.cases()
        val gradeOnly = cases.first { it.id == "grade" }.grade
        val both = cases.first { it.id == "grade-stab" }.grade
        assertEquals(gradeOnly.copy(stabilization = true), both)
        assertTrue(both.stabilization)
    }

    @Test
    fun frame_indices_first_middle_near_end_deduped() {
        assertEquals(emptyList<Int>(), ExportAbSweep.frameIndices(0))
        assertEquals(emptyList<Int>(), ExportAbSweep.frameIndices(-3))
        assertEquals(listOf(0), ExportAbSweep.frameIndices(1))
        assertEquals(listOf(0, 1), ExportAbSweep.frameIndices(2))
        assertEquals(listOf(0, 3, 6), ExportAbSweep.frameIndices(7))
        assertEquals(listOf(0, 4, 7), ExportAbSweep.frameIndices(8))
        // Deduped + order-preserving on any count.
        for (n in 0..12) {
            val idx = ExportAbSweep.frameIndices(n)
            assertEquals(idx.size, idx.distinct().size)
            assertEquals(idx, idx.sorted())
            assertTrue(idx.all { it in 0 until maxOf(n, 1) || (n <= 0 && idx.isEmpty()) })
        }
    }

    private fun stats(samples: Long, mismatch: Long, maxDiffLsb: Int): GradeParitySweep.Stats =
        GradeParitySweep.Stats(samples = samples, mismatch = mismatch, maxDiffLsb = maxDiffLsb, over1 = 0L, meanSigned = 0.0)

    @Test
    fun is_mismatch_flags_the_boundaries() {
        // (0.5%, 2) is under both thresholds: NOT a mismatch.
        assertFalse(ExportAbSweep.isMismatch(stats(1000, 5, 2)))
        // 0.51% strictly above 0.5%: a mismatch.
        assertTrue(ExportAbSweep.isMismatch(stats(10000, 51, 1)))
        // maxDiffLsb 3 strictly above 2: a mismatch even with tiny mismatch%.
        assertTrue(ExportAbSweep.isMismatch(stats(1000, 1, 3)))
        // Under both: not a mismatch.
        assertFalse(ExportAbSweep.isMismatch(stats(1000, 1, 1)))
    }

    @Test
    fun ab_line_format_and_flag_suffix() {
        val case = ExportAbSweep.cases().first { it.id == "neutral" }
        val ok = ExportAbSweep.abLine(case, 7, stats(1000, 1, 1))
        assertTrue(ok, ok.startsWith("export-ab neutral frame=7:"))
        assertTrue(ok, ok.contains("maxDiffLsb=1"))
        assertTrue(ok, ok.contains("mismatch=0.100%"))
        assertTrue(ok, ok.contains("samples=1000"))
        assertFalse(ok, ok.contains("EXPORT_MISMATCH"))
        val bad = ExportAbSweep.abLine(case, 0, stats(1000, 60, 4))
        assertTrue(bad, bad.startsWith("export-ab neutral frame=0:"))
        assertTrue(bad, bad.contains("maxDiffLsb=4"))
        assertTrue(bad, bad.endsWith(" EXPORT_MISMATCH"))
        // The over1 share also prints (%.3f).
        val withOver1 = ExportAbSweep.abLine(case, 2, GradeParitySweep.Stats(1000, 60, 4, 900, 0.0))
        assertTrue(withOver1.contains("over1=90.000%"))
    }

    @Test
    fun summary_line_reports_counts_worst_and_took_ms() {
        val cases = ExportAbSweep.cases()
        // Summary over rows: (case id, stats) pairs — real case ids, so
        // the worst-owner wiring is proven against the pinned list.
        assertTrue(cases[1].id == "grade")
        val rows = listOf(
            "neutral" to stats(1000, 5, 1),
            "grade" to stats(500, 2, 5),
            "grade-stab" to stats(500, 1, 2),
        )
        val line = ExportAbSweep.summaryLine(rows, skipped = 1, flagged = 0, tookMs = 4321)
        assertTrue(line, line.startsWith("export-ab summary:"))
        assertTrue(line, line.contains("cases=3"))
        assertTrue(line, line.contains("skipped=1"))
        assertTrue(line, line.contains("flagged=0"))
        assertTrue(line, line.contains("worstMaxDiffLsb=5 (grade)"))
        assertTrue(line, line.contains("tookMs=4321"))
        // The per-case worst owner is the max by maxDiffLsb.
        val swapped = ExportAbSweep.summaryLine(rows.asReversed(), skipped = 0, flagged = 1, tookMs = 10)
        assertTrue(swapped.contains("worstMaxDiffLsb=5 (grade)"))
        assertTrue(swapped.contains("flagged=1"))
    }

    // ---- gyro paused (2026-11-23) ----

    @Test
    fun paused_skips_exactly_the_stabilization_cases() {
        val skipped = ExportAbSweep.cases().filter { ExportAbSweep.pausedSkip(it, gyroAllowed = false) }
        assertEquals(listOf("stab", "grade-stab"), skipped.map { it.id })
    }

    @Test
    fun gyro_allowed_skips_nothing_so_the_old_four_cases_run_again() {
        assertTrue(ExportAbSweep.cases().none { ExportAbSweep.pausedSkip(it, gyroAllowed = true) })
    }

    @Test
    fun shipped_flag_off_means_stab_cases_are_paused_by_default() {
        val stab = ExportAbSweep.cases().first { it.id == "stab" }
        assertTrue(ExportAbSweep.pausedSkip(stab))
        assertFalse(ExportAbSweep.pausedSkip(ExportAbSweep.cases().first { it.id == "grade" }))
    }

    @Test
    fun paused_line_says_skipped_and_never_carries_the_mismatch_flag() {
        val c = ExportAbSweep.cases().first { it.id == "grade-stab" }
        val line = ExportAbSweep.pausedLine(c, 7)
        assertEquals("export-ab grade-stab frame=7 SKIPPED (gyro paused)", line)
        assertFalse(line.contains("EXPORT_MISMATCH"))
    }
}
