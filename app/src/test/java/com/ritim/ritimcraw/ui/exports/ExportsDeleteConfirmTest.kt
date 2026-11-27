package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExportsDeleteConfirm tests (owner request 2026-11-27: the swipe
 * delete must ask before it deletes). The pure state machine's every
 * transition: arm (only terminal rows, only on a qualifying swipe),
 * confirm (the still-deletable re-check at the button), cancel
 * (nothing deletes, nothing stays armed), and the dialog's context
 * line (the DONE row names its file, the failed/cancelled row its
 * clip). JVM-only, no Compose — the [ExportsSelectionTest] shape.
 *
 * M8 (owner request 2026-11-27, the same pass: "delete from disk
 * box'u da olsun"): the checkbox rules are here too — and the
 * visibility rule (only a DONE row with a published file shows the
 * checkbox at all).
 *
 * M10 (owner request 2026-11-27, the same day: "user'ın tıkladığı en
 * son seçenek saklanmalı, eğer delete from the disk seçerse diğer
 * silme isteklerinde de bu seçili gelmeli"): the ARM rule changed from
 * reset-to-unchecked to the REMEMBERED value — the persisted store
 * value routes through [ExportsDeleteConfirm.armCheckbox] unchanged.
 * The old tests pinned the M8 reset (every arm false); the owner's
 * request replaced that behavior, so the tests now pin the pass-through
 * (the store's own tests pin the default-false first-dialog side).
 */
class ExportsDeleteConfirmTest {

    private fun job(
        id: String,
        state: State,
        // The display-derived name captured at enqueue (fileName's
        // backing field); null falls back to the legacy derived name.
        outputName: String? = null,
        // The published media-store URI (M8): a DONE row's file target.
        publishedUri: String? = null,
    ) = ExportJob(
        id = id,
        sourceId = "clip-$id",
        snapshot = GradeK(),
        target = Target.MP4,
        state = state,
        outputName = outputName,
        publishedUri = publishedUri,
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

    // ---- the delete-from-disk checkbox (M8, owner request 2026-11-27) ----

    @Test
    fun the_checkbox_opens_with_the_remembered_choice() {
        // M10 (owner request 2026-11-27: "user'ın tıkladığı en son seçenek
        // saklanmalı, eğer delete from the disk seçerse diğer silme
        // isteklerinde de bu seçili gelmeli"): the arm routes the PERSISTED
        // value through unchanged — a remembered true opens the next
        // dialog checked, a remembered false (also the first dialog's
        // store default, the M8 safe start) opens it unchecked. The old
        // M8 rule (every arm resets to false) was replaced by this
        // pass-through at the owner's own request.
        assertEquals(true, ExportsDeleteConfirm.armCheckbox(persisted = true))
        assertEquals(false, ExportsDeleteConfirm.armCheckbox(persisted = false))
    }

    @Test
    fun the_checkbox_shows_for_a_done_row_with_a_published_file() {
        assertTrue(
            ExportsDeleteConfirm.showsDiskCheckbox(
                job("a", State.DONE, publishedUri = "content://media/external/video/media/42"),
            )
        )
    }

    @Test
    fun the_checkbox_hides_for_a_failed_or_cancelled_row() {
        // Nothing was written/published: a checkbox that does nothing
        // would be a lie, so it does not compose.
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("f", State.FAILED)))
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("c", State.CANCELLED)))
    }

    @Test
    fun the_checkbox_hides_for_a_done_row_whose_publish_failed() {
        // A null or blank URI is no file on disk (the publish failed — the
        // row opens the internal copy, which the record-only delete
        // leaves): nothing to offer the checkbox.
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("n", State.DONE)))
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("b", State.DONE, publishedUri = "")))
    }

    @Test
    fun a_live_row_never_shows_the_checkbox() {
        // A live row never arms the dialog at all (arm's own rule); the
        // visibility rule repeats it for defense in depth.
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("q", State.QUEUED, publishedUri = "content://media/1")))
        assertFalse(ExportsDeleteConfirm.showsDiskCheckbox(job("r", State.RUNNING, publishedUri = "content://media/1")))
    }
}
