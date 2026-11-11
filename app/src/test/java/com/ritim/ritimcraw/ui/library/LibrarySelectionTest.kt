package com.ritim.ritimcraw.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LibrarySelection tests (owner findings 2026-11-11 E2 + E6): the pure
 * long-press/tap toggle, stale-id prune, and system-back rules behind
 * the Library's selection mode.
 */
class LibrarySelectionTest {

    // ---- toggle (long-press enters; taps add/remove rows) ----

    @Test
    fun long_press_into_an_empty_set_checks_that_row() {
        // Toggle into empty IS the long-press that starts selection mode.
        assertEquals(setOf("a"), LibrarySelection.toggle(emptySet(), "a"))
    }

    @Test
    fun tap_adds_more_rows_for_multi_select() {
        val two = LibrarySelection.toggle(LibrarySelection.toggle(emptySet(), "a"), "b")
        assertEquals(setOf("a", "b"), two)
    }

    @Test
    fun tap_on_a_checked_row_unchecks_it() {
        val toggled = LibrarySelection.toggle(setOf("a", "b"), "a")
        assertEquals(setOf("b"), toggled)
    }

    @Test
    fun unchecking_the_last_row_exits_selection_mode() {
        // Selection mode is active exactly while the set is non-empty.
        assertTrue(LibrarySelection.toggle(setOf("a"), "a").isEmpty())
    }

    // ---- prune (deleted rows can never stay selected) ----

    @Test
    fun prune_drops_ids_the_list_no_longer_has() {
        val pruned = LibrarySelection.prune(
            selected = setOf("a", "b", "c"),
            liveIds = setOf("a", "c", "d"),
        )
        assertEquals(setOf("a", "c"), pruned)
    }

    @Test
    fun prune_of_a_fully_stale_selection_exits_selection_mode() {
        val pruned = LibrarySelection.prune(
            selected = setOf("x"),
            liveIds = setOf("a", "b"),
        )
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun prune_keeps_a_live_selection_untouched() {
        val selected = setOf("a", "b")
        assertEquals(selected, LibrarySelection.prune(selected, setOf("a", "b", "c")))
    }

    // ---- onBack (owner finding E6: back closes selection, not the app) ----

    @Test
    fun back_with_rows_selected_exits_selection_mode() {
        // Back clears the set — selection mode ends, the screen stays.
        assertTrue(LibrarySelection.onBack(setOf("a", "b")).isEmpty())
    }

    @Test
    fun back_with_no_selection_stays_empty() {
        // The handler is disabled with no selection; the rule is total.
        assertTrue(LibrarySelection.onBack(emptySet()).isEmpty())
    }
}
