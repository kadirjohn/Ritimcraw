package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExportsSelection tests (owner request 2026-11-26: "select videos"):
 * the pure multi-select rules for the Exports list — toggle/prune/back
 * (the LibrarySelection precedent's shape) plus the delete-set routing
 * with its live-job exclusion. JVM-only, no Compose.
 */
class ExportsSelectionTest {

    private fun job(
        id: String,
        state: State,
        target: Target = Target.MP4,
    ) = ExportJob(
        id = id,
        sourceId = "clip-$id",
        snapshot = GradeK(),
        target = target,
        state = state,
    )

    // ---- toggle ----

    @Test
    fun toggle_adds_an_absent_id_and_removes_a_present_one() {
        assertEquals(setOf("a"), ExportsSelection.toggle(emptySet(), "a"))
        assertEquals(emptySet<String>(), ExportsSelection.toggle(setOf("a"), "a"))
        assertEquals(setOf("a", "b"), ExportsSelection.toggle(setOf("a"), "b"))
    }

    @Test
    fun toggle_does_not_mutate_the_input_set() {
        val input = setOf("a")
        ExportsSelection.toggle(input, "b")
        assertEquals(setOf("a"), input)
    }

    // ---- prune ----

    @Test
    fun prune_drops_ids_the_list_no_longer_has() {
        // A deleted/vanished row can never stay selected; a fully stale
        // selection exits selection mode by becoming empty.
        assertEquals(setOf("a"), ExportsSelection.prune(setOf("a", "gone"), setOf("a", "b")))
        assertEquals(emptySet<String>(), ExportsSelection.prune(setOf("gone"), setOf("a")))
    }

    @Test
    fun prune_keeps_the_selection_when_every_id_is_live() {
        assertEquals(setOf("a", "b"), ExportsSelection.prune(setOf("a", "b"), setOf("a", "b", "c")))
    }

    // ---- deleteIds (the live-job exclusion at the delete) ----

    @Test
    fun delete_ids_route_only_the_checked_terminal_rows() {
        val jobs = listOf(
            job("done-1", State.DONE),
            job("failed-1", State.FAILED),
            job("cancelled-1", State.CANCELLED),
        )

        assertEquals(
            setOf("done-1", "cancelled-1"),
            ExportsSelection.deleteIds(setOf("done-1", "cancelled-1"), jobs),
        )
    }

    @Test
    fun delete_ids_exclude_live_rows_even_when_checked() {
        // The race gate: a checked row that turned live between the check
        // and the delete tap (a retry re-queued it) drops out here — the
        // store's own live-guard is the second gate.
        val jobs = listOf(
            job("queued-1", State.QUEUED),
            job("running-1", State.RUNNING),
            job("done-1", State.DONE),
        )

        assertEquals(
            setOf("done-1"),
            ExportsSelection.deleteIds(setOf("queued-1", "running-1", "done-1"), jobs),
        )
    }

    @Test
    fun delete_ids_is_empty_when_no_checked_row_is_deletable() {
        val jobs = listOf(
            job("queued-1", State.QUEUED),
            job("running-1", State.RUNNING),
        )

        assertEquals(
            emptySet<String>(),
            ExportsSelection.deleteIds(setOf("queued-1", "running-1"), jobs),
        )
    }

    @Test
    fun delete_ids_ignores_checked_ids_the_list_no_longer_has() {
        val jobs = listOf(job("done-1", State.DONE))

        assertEquals(
            setOf("done-1"),
            ExportsSelection.deleteIds(setOf("done-1", "vanished"), jobs),
        )
    }

    // ---- onBack ----

    @Test
    fun back_clears_the_selection() {
        // Back while rows are checked exits SELECTION mode — the screen
        // stays.
        assertEquals(emptySet<String>(), ExportsSelection.onBack(setOf("a", "b")))
        assertEquals(emptySet<String>(), ExportsSelection.onBack(emptySet()))
    }

    // ---- the selection-mode lifecycle with the row states ----

    @Test
    fun a_fully_stale_selection_exits_selection_mode_by_becoming_empty() {
        // The composable prunes on every jobs change; the mode is active
        // exactly while the set is non-empty, so deleting every selected
        // row (or the list emptying) ends the mode with no extra flag.
        var selected = ExportsSelection.toggle(emptySet(), "done-1")
        selected = ExportsSelection.toggle(selected, "done-2")
        assertTrue(selected.isNotEmpty())

        val afterDeleteAll = ExportsSelection.prune(selected, emptySet())
        assertTrue(afterDeleteAll.isEmpty())
    }
}
