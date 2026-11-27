package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ExportsDeleteConfirm tests (owner request 2026-11-27: the swipe
 * delete must ask before it deletes). The pure state machine's every
 * transition: arm (only terminal rows, only on a qualifying swipe),
 * confirm (the still-deletable re-check at the button), cancel
 * (nothing deletes, nothing stays armed), and the dialog's context
 * line (the DONE row names its file, the failed/cancelled row its
 * clip). JVM-only, no Compose — the [ExportsSelectionTest] shape.
 */
class ExportsDeleteConfirmTest {

    private fun job(
        id: String,
        state: State,
        // The display-derived name captured at enqueue (fileName's
        // backing field); null falls back to the legacy derived name.
        outputName: String? = null,
    ) = ExportJob(
        id = id,
        sourceId = "clip-$id",
        snapshot = GradeK(),
        target = Target.MP4,
        state = state,
        outputName = outputName,
    )

    // ---- arm ----

    @Test
    fun arm_holds_the_swiped_terminal_row() {
        assertEquals("a", ExportsDeleteConfirm.arm(null, job("a", State.DONE)))
        assertEquals("b", ExportsDeleteConfirm.arm(null, job("b", State.FAILED)))
        assertEquals("c", ExportsDeleteConfirm.arm(null, job("c", State.CANCELLED)))
    }

    @Test
    fun arm_never_holds_a_live_row() {
        // A live row (QUEUED/RUNNING) can only be cancelled; deleting
        // one would orphan the encoder. The swipe on it arms nothing —
        // the existing pending id stays untouched (no accidental drop
        // of a DIFFERENT row's armed dialog).
        assertEquals(null, ExportsDeleteConfirm.arm(null, job("q", State.QUEUED)))
        assertEquals(null, ExportsDeleteConfirm.arm(null, job("r", State.RUNNING)))
        assertEquals("a", ExportsDeleteConfirm.arm("a", job("r", State.RUNNING)))
    }

    // ---- confirm ----

    @Test
    fun confirm_routes_the_armed_id() {
        val jobs = listOf(job("a", State.DONE), job("b", State.DONE))
        assertEquals(setOf("a"), ExportsDeleteConfirm.confirmIds("a", jobs))
    }

    @Test
    fun confirm_on_nothing_armed_deletes_nothing() {
        assertEquals(emptySet<String>(), ExportsDeleteConfirm.confirmIds(null, listOf(job("a", State.DONE))))
    }

    @Test
    fun confirm_drops_an_id_that_turned_live_between_swipe_and_button() {
        // The arm-time terminal row was retried: it is RUNNING at the
        // dialog's Delete press. Nothing routes — the store's delete is
        // never even asked (its own live-guard is the second gate).
        val jobs = listOf(job("a", State.RUNNING))
        assertEquals(emptySet<String>(), ExportsDeleteConfirm.confirmIds("a", jobs))
    }

    @Test
    fun confirm_drops_an_id_that_vanished_from_the_list() {
        // The row vanished (another delete path raced): nothing routes.
        assertEquals(emptySet<String>(), ExportsDeleteConfirm.confirmIds("gone", listOf(job("a", State.DONE))))
    }

    // ---- cancel ----

    @Test
    fun cancel_clears_the_armed_state() {
        assertNull(ExportsDeleteConfirm.cancel("a"))
        assertNull(ExportsDeleteConfirm.cancel(null))
    }

    // ---- the dialog's context line ----

    @Test
    fun a_done_row_names_its_output_file() {
        assertEquals(
            "clip.mp4",
            ExportsDeleteConfirm.subjectLine(
                job("a", State.DONE, outputName = "clip.mp4"),
                sourceNames = mapOf("clip-a" to "Clip"),
            ),
        )
    }

    @Test
    fun a_failed_row_names_its_source_clip() {
        // Nothing was written; the subject is the clip it came from.
        assertEquals(
            "My clip",
            ExportsDeleteConfirm.subjectLine(
                job("a", State.FAILED),
                sourceNames = mapOf("clip-a" to "My clip"),
            ),
        )
    }

    @Test
    fun a_cancelled_row_names_its_source_clip_with_the_id_fallback() {
        assertEquals(
            "clip-a",
            ExportsDeleteConfirm.subjectLine(
                job("a", State.CANCELLED),
                sourceNames = emptyMap(),
            ),
        )
    }
}
