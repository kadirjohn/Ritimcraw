package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.export.ExportJob

/**
 * Pure multi-select rules for the Exports list (owner request
 * 2026-11-26: "select videos" — a Select icon in the header opens
 * selection mode; tap rows to check them, trash deletes the checked
 * set). The composable keeps only a Set&lt;String&gt;; every transition is
 * one of these pure functions, so the selection rules are
 * JVM-testable without Compose — the [LibrarySelection] precedent's
 * shape (the Library's long-press model adapted to the Exports
 * header-button entry).
 *
 * LIVE-JOB EXCLUSION: only TERMINAL rows ([ExportRowModel.isDeletable]
 * — DONE/FAILED/CANCELLED) can ever be selected — the composable never
 * offers the checkbox on a live row — and [deleteIds] routes only
 * terminal ids: a live row can only be cancelled, never deleted
 * (deleting one would orphan the encoder the queue still drives).
 *
 * Selection mode is active exactly while the set is non-empty: the
 * header's Select button enters the mode with the first checked row,
 * and the mode exits when the set empties — the last row unchecked,
 * the close (X) button, system back, or after a delete.
 */
object ExportsSelection {

    /**
     * A row tap while in selection mode: the row joins the set when
     * absent, leaves it when present — the [LibrarySelection.toggle]
     * rule. Toggling into an empty set IS "the Select mode began"
     * (the header button enters with this call on its first row).
     */
    fun toggle(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /**
     * Rows that vanished from the list (deleted, or any future
     * removal path) can never stay selected: the selection is
     * intersected with the live ids on every list change — the
     * [LibrarySelection.prune] rule.
     */
    fun prune(selected: Set<String>, liveIds: Set<String>): Set<String> =
        selected intersect liveIds

    /**
     * The ids a delete-selected action routes to the store: the
     * checked set filtered to the rows that are STILL deletable at the
     * tap — terminal rows only. A row that turned live between the
     * check and the tap (a retry re-queued it) drops out here and the
     * store's own live-guard ([ExportStore.delete]) is the second
     * lock-side gate.
     */
    fun deleteIds(selected: Set<String>, jobs: List<ExportJob>): Set<String> =
        jobs.filterTo(HashSet()) { it.id in selected && ExportRowModel.isDeletable(it.state) }
            .mapTo(HashSet()) { it.id }

    /**
     * The system back decision: back while rows are selected exits
     * SELECTION mode (clears the set — the screen stays), never the
     * app — the [LibrarySelection.onBack] rule.
     */
    fun onBack(selected: Set<String>): Set<String> = emptySet()
}
