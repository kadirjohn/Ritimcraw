package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ExportDelete rule tests (owner request 2026-11-27, M8: "delete from disk
 * box'u da olsun ve bunu işaretlerse diskten de silsin"). The rule is pure —
 * the resolver delete is a device-gated adapter in AppNav, so every branch
 * of "which files does a checked box remove" is JVM-test:
 *  - only the ids already deleting, only DONE rows, only non-blank URIs;
 *  - a FAILED/CANCELLED row contributes nothing (it wrote nothing);
 *  - a DONE row whose publish failed (null URI) contributes nothing — the
 *    honest skip, never a guessed path;
 *  - a live id can never reach a disk target (the re-check at the rule
 *    itself, the store's live-guard is the second gate);
 *  - the internal candidates are the open path's own pair (per-job
 *    directory first, legacy flat second);
 *  - the checkbox default is UNCHECKED and the confirm-press re-check
 *    drops a row that turned live between the check and the press.
 */
class ExportDeleteTest {

    private fun job(
        id: String,
        state: State,
        publishedUri: String? = null,
    ) = ExportJob(
        id = id,
        sourceId = "clip-$id",
        snapshot = GradeK(),
        target = Target.MP4,
        state = state,
        publishedUri = publishedUri,
    )

    private val uri = "content://media/external/video/media/42"

    // ---- diskTargets: which rows have files to remove ----

    @Test
    fun checked_delete_routes_a_done_row_with_a_published_uri() {
        val jobs = listOf(job("a", State.DONE, uri))
        assertEquals(listOf("a"), ExportDelete.diskTargets(jobs, setOf("a")).map { it.id })
    }

    @Test
    fun only_the_ids_being_deleted_route() {
        // The checkbox widens THE delete at hand, never the whole list: a
        // second DONE row outside the id set keeps its file.
        val jobs = listOf(
            job("a", State.DONE, uri),
            job("b", State.DONE, "content://media/external/video/media/43"),
        )
        assertEquals(listOf("a"), ExportDelete.diskTargets(jobs, setOf("a")).map { it.id })
    }

    @Test
    fun a_failed_or_cancelled_row_has_no_disk_target() {
        // A FAILED/CANCELLED row wrote nothing and published nothing: it
        // contributes nothing even when its id is in the set.
        val jobs = listOf(
            job("f", State.FAILED, uri),
            job("c", State.CANCELLED, uri),
        )
        assertTrue(ExportDelete.diskTargets(jobs, setOf("f", "c")).isEmpty())
    }

    @Test
    fun a_null_or_blank_published_uri_is_no_target() {
        // A DONE row whose publish failed (null URI) has no media-store row;
        // a blank string is the same honesty (defensive against a corrupt
        // row). Nothing is guessed — the row is silently skipped.
        val jobs = listOf(
            job("n", State.DONE, null),
            job("b", State.DONE, ""),
            job("w", State.DONE, "   "),
        )
        assertTrue(ExportDelete.diskTargets(jobs, setOf("n", "b", "w")).isEmpty())
    }

    @Test
    fun a_live_id_never_reaches_a_disk_target() {
        // The re-check at the rule itself: a row that turned live between
        // the checkbox and the press (a Retry racing the dialog) keeps its
        // file — its fresh run needs to publish it. The store's live-guard
        // drops the RECORD; this rule must never drop the FILE.
        val jobs = listOf(
            job("q", State.QUEUED, uri),
            job("r", State.RUNNING, uri),
        )
        assertTrue(ExportDelete.diskTargets(jobs, setOf("q", "r")).isEmpty())
    }

    @Test
    fun an_empty_id_set_targets_nothing() {
        assertTrue(ExportDelete.diskTargets(listOf(job("a", State.DONE, uri)), emptySet()).isEmpty())
    }

    // ---- internalTargets: the open path's own candidate pair ----

    @Test
    fun internal_targets_are_the_open_path_candidates() {
        // The deleted record must never keep opening a file the dialog said
        // left: the SAME pair ExportOpen's adapter offers (the per-job
        // directory first, the legacy flat name second), so both layouts'
        // copies leave.
        val job = job("a", State.DONE, uri).copy(outputName = "Sunset.mp4")
        val exportDir = File("/data/user/0/com.ritim.ritimcraw/files/exports")
        assertEquals(
            listOf(
                File(File(exportDir, "a"), "Sunset.mp4"),
                File(exportDir, "Sunset.mp4"),
            ),
            ExportDelete.internalTargets(job, exportDir),
        )
    }

    @Test
    fun internal_targets_use_the_legacy_name_when_no_output_name() {
        // A row queued before §2b: the <sourceId>-<target> legacy name, the
        // same fallback fileName resolves.
        val job = job("a", State.DONE, uri)
        val exportDir = File("/exports")
        assertEquals(
            listOf(
                File(File(exportDir, "a"), "clip-a-mp4.mp4"),
                File(exportDir, "clip-a-mp4.mp4"),
            ),
            ExportDelete.internalTargets(job, exportDir),
        )
    }

    // ---- the checkbox state rules ----

    @Test
    fun the_checkbox_default_is_unchecked() {
        // The safe side (owner request): the box is the user's opt-in, never
        // the dialog's assumption.
        assertFalse(ExportDelete.DEFAULT_DELETE_FROM_DISK)
    }

    @Test
    fun from_disk_rechecks_the_row_at_the_confirm_press() {
        val jobs = listOf(job("a", State.DONE, uri))
        assertTrue(ExportDelete.fromDisk("a", jobs, checked = true))
    }

    @Test
    fun from_disk_is_false_when_the_box_is_unchecked() {
        val jobs = listOf(job("a", State.DONE, uri))
        assertFalse(ExportDelete.fromDisk("a", jobs, checked = false))
    }

    @Test
    fun from_disk_is_false_when_nothing_is_armed() {
        val jobs = listOf(job("a", State.DONE, uri))
        assertFalse(ExportDelete.fromDisk(null, jobs, checked = true))
    }

    @Test
    fun from_disk_is_false_when_the_row_has_no_disk_target() {
        // A FAILED row armed with the box checked (the box composes only
        // for a DONE+URI row, but the rule re-checks anyway): no target,
        // no disk delete.
        val jobs = listOf(job("f", State.FAILED))
        assertFalse(ExportDelete.fromDisk("f", jobs, checked = true))
    }

    @Test
    fun from_disk_drops_a_row_that_turned_live_before_the_press() {
        // The arm-time DONE row was retried: RUNNING at the press. The file
        // stays — its fresh run needs it.
        val jobs = listOf(job("a", State.RUNNING, uri))
        assertFalse(ExportDelete.fromDisk("a", jobs, checked = true))
    }
}
