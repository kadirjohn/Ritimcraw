package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export row model tests (owner request 2026-11-11d §5): the structured
 * job row's pure decisions — the source name with its id fallback, the
 * output file name (captured name, legacy fallback), and the DONE-only
 * openable rule. All values come from the job's existing fields.
 */
class ExportRowModelTest {

    private fun job(
        state: State = State.QUEUED,
        target: Target = Target.DNG,
        outputName: String? = null,
    ) = ExportJob(
        id = "job-1",
        sourceId = "abc123",
        snapshot = com.ritim.ritimcraw.data.GradeK(),
        target = target,
        state = state,
        outputName = outputName,
    )

    // ---- sourceName ----

    @Test
    fun source_name_uses_the_display_name_when_known() {
        assertEquals(
            "261109_172607_VIDEO_99mm.mcraw",
            ExportRowModel.sourceName(
                job(),
                mapOf("abc123" to "261109_172607_VIDEO_99mm.mcraw"),
            ),
        )
    }

    @Test
    fun source_name_falls_back_to_the_id_for_unknown_sources() {
        // A deleted clip's rows keep their id headline — never blank.
        assertEquals("abc123", ExportRowModel.sourceName(job(), emptyMap()))
    }

    // ---- outputFileName ----

    @Test
    fun output_file_name_is_the_captured_display_derived_name() {
        assertEquals(
            "261109_172607_VIDEO_99mm.dng",
            ExportRowModel.outputFileName(
                job(outputName = "261109_172607_VIDEO_99mm.dng"),
            ),
        )
    }

    @Test
    fun output_file_name_falls_back_to_the_legacy_name() {
        // Rows queued before §2b have no outputName: the legacy
        // <sourceId>-<target> name keeps them openable.
        assertEquals("abc123-dng.dng", ExportRowModel.outputFileName(job()))
    }

    @Test
    fun output_file_name_extension_follows_the_target() {
        assertEquals(
            "clip.mp4",
            ExportRowModel.outputFileName(
                job(target = Target.MP4, outputName = "clip.mp4"),
            ),
        )
    }

    // ---- isOpenable ----

    @Test
    fun done_rows_open_on_tap() {
        assertTrue(ExportRowModel.isOpenable(State.DONE))
    }

    @Test
    fun only_done_rows_open() {
        // QUEUED/RUNNING/CANCELLED rows are not tap targets; FAILED rows
        // carry Retry instead.
        assertFalse(ExportRowModel.isOpenable(State.QUEUED))
        assertFalse(ExportRowModel.isOpenable(State.RUNNING))
        assertFalse(ExportRowModel.isOpenable(State.FAILED))
        assertFalse(ExportRowModel.isOpenable(State.CANCELLED))
    }
}
