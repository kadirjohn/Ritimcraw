package com.ritim.ritimcraw.ui.library

/**
 * Pure multi-select rules for the Library list (owner finding
 * 2026-11-11 E2): long-press a row to enter selection mode with that row
 * checked, tap rows to toggle them, trash to delete. The composable keeps
 * only a Set&lt;String&gt;; every transition is one of these pure
 * functions, so the selection rules are JVM-testable without Compose.
 *
 * Selection mode is active exactly while the set is non-empty: a
 * long-press on a row toggles it in from an empty set (which starts the
 * mode with that row checked), and the mode exits when the set empties —
 * the last row unchecked, the close button, the dialog's Cancel, or
 * after a delete.
 */
object LibrarySelection {

    /**
     * A row tap while in selection mode (or the long-press that enters
     * it): the row joins the set when absent, leaves it when present.
     * Toggling into an empty set IS "long-press started selection".
     */
    fun toggle(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /**
     * Rows that vanished from the list (deleted, or any future removal
     * path) can never stay selected: the selection is intersected with
     * the live ids on every list change, and a fully stale selection
     * exits selection mode by becoming empty.
     */
    fun prune(selected: Set<String>, liveIds: Set<String>): Set<String> =
        selected intersect liveIds

    /**
     * The system back decision (owner finding 2026-11-11 E6): back while
     * rows are selected exits SELECTION mode (clears the set — the screen
     * stays), never the app. The BackHandler is enabled exactly while the
     * set is non-empty, so this rule only ever runs with an active
     * selection; an empty input is still mapped to empty for totality.
     */
    fun onBack(selected: Set<String>): Set<String> = emptySet()
}
