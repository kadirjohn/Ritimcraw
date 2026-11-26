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

    // ---- isCancellable (owner request 2026-11-13) ----

    @Test
    fun queued_and_running_rows_offer_cancel() {
        // The two live states only: a QUEUED job never starts, a RUNNING
        // one stops at the next frame boundary.
        assertTrue(ExportRowModel.isCancellable(State.QUEUED))
        assertTrue(ExportRowModel.isCancellable(State.RUNNING))
    }

    @Test
    fun terminal_rows_never_offer_cancel() {
        // DONE/FAILED/CANCELLED have nothing to cancel — the queue's
        // cancel() is a no-op on them by the same rule.
        assertFalse(ExportRowModel.isCancellable(State.DONE))
        assertFalse(ExportRowModel.isCancellable(State.FAILED))
        assertFalse(ExportRowModel.isCancellable(State.CANCELLED))
    }

    // ---- isDeletable (owner request 2026-11-26: individual rows) ----

    @Test
    fun terminal_rows_are_deletable() {
        // DONE/FAILED/CANCELLED are history: swipe and select-mode delete
        // remove them (the record only, like clear-all).
        assertTrue(ExportRowModel.isDeletable(State.DONE))
        assertTrue(ExportRowModel.isDeletable(State.FAILED))
        assertTrue(ExportRowModel.isDeletable(State.CANCELLED))
    }

    @Test
    fun the_two_live_states_are_never_deletable() {
        // A live job can only be cancelled — deleting one would orphan
        // the encoder the queue still drives. The swipe never confirms
        // and the store's delete repeats the rule at its write.
        assertFalse(ExportRowModel.isDeletable(State.QUEUED))
        assertFalse(ExportRowModel.isDeletable(State.RUNNING))
    }

    @Test
    fun deletable_is_the_exact_complement_of_cancellable() {
        // The two rules partition the state space: every state offers
        // exactly one of the two actions.
        for (state in State.values()) {
            assertTrue(
                "state ${state.name} must be exactly one of cancellable/deletable",
                ExportRowModel.isCancellable(state) != ExportRowModel.isDeletable(state),
            )
        }
    }

    private fun report(mime: String, steps: List<String> = emptyList()) =
        com.ritim.ritimcraw.export.ExportReport(
            "enc", true, mime, 1920, 1080, 30.0, 10, "VBR", null, 1f, 1, 128, 1, 10.0,
            steps, emptyList(),
        )

    private fun mp4(report: com.ritim.ritimcraw.export.ExportReport?) =
        job(State.DONE, Target.MP4).copy(report = report)

    @Test
    fun codec_label_names_the_real_codec() {
        assertEquals("HEVC", ExportRowModel.codecLabel(mp4(report("video/hevc"))))
        assertEquals("H.264", ExportRowModel.codecLabel(mp4(report("video/avc"))))
        assertEquals("AV1", ExportRowModel.codecLabel(mp4(report("video/av01"))))
    }

    @Test
    fun codec_label_is_null_when_unknown() {
        assertEquals(null, ExportRowModel.codecLabel(mp4(null)))
        assertEquals(null, ExportRowModel.codecLabel(mp4(report("video/x-weird"))))
        // Non-MP4 targets never name a video codec.
        assertEquals(null, ExportRowModel.codecLabel(job(State.DONE, Target.DNG).copy(report = report("video/hevc"))))
    }

    @Test
    fun thumb_source_follows_the_target() {
        assertEquals(ExportRowModel.ThumbSource.OUTPUT_VIDEO, ExportRowModel.thumbSource(job(target = Target.MP4)))
        assertEquals(ExportRowModel.ThumbSource.SOURCE_STORE, ExportRowModel.thumbSource(job(target = Target.DNG)))
        assertEquals(ExportRowModel.ThumbSource.SOURCE_STORE, ExportRowModel.thumbSource(job(target = Target.PHOTO)))
        assertEquals(ExportRowModel.ThumbSource.SOURCE_STORE, ExportRowModel.thumbSource(job(target = Target.LINEAR)))
    }

    @Test
    fun detail_lines_hold_encoder_and_notes_only() {
        assertEquals(listOf(ExportRowModel.DetailLine.ENCODER), ExportRowModel.detailLines(report("video/hevc")))
        assertEquals(
            listOf(ExportRowModel.DetailLine.ENCODER, ExportRowModel.DetailLine.FIXED, ExportRowModel.DetailLine.CPU),
            ExportRowModel.detailLines(report("video/hevc", listOf("fix:x", "surface->cpu"))),
        )
    }
}
