package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State

/**
 * The swipe-to-delete confirmation state machine (owner request
 * 2026-11-27, the M3 polish pass: "sayfalar arası kaydırma yaparken
 * exports kısmında yanlışlıkla video silinebiliyor, silmeden önce pop
 * up çıkıp onay sorulmalı"). A swipe on a terminal Exports row no
 * longer deletes at the dismiss threshold: it ARMS a pending delete,
 * the confirm dialog asks, and only the dialog's Delete button lands
 * in the store's delete. The gesture is a two-axis surface — the same
 * horizontal drag both turns the page and dismisses a row — so the
 * swipe that flips the tab is exactly the swipe that must NOT delete
 * without asking; the dialog is the owner's own chosen guard, not a
 * second lock on top of one.
 *
 * PURE STATE, the [ExportsSelection] precedent's shape: the composable
 * keeps only the pending job id (null = nothing armed); every
 * transition below is a pure function, so the flow is JVM-testable
 * without Compose:
 *
 *  - arm: the row's [SwipeToDismissBox][androidx.compose.material3.SwipeToDismissBox]
 *    settled into a dismissed anchor after the threshold — the delete
 *    is VETOED at that settle (the state's confirmValueChange returns
 *    false), which rolls the row back per material3 1.3.0's own
 *    AnchoredDraggableState.settle, and this id becomes the armed
 *    dialog's subject;
 *  - confirm: the dialog's Delete button — the armed id routes to the
 *    store's delete and the pending state clears;
 *  - cancel (or dismiss-anywhere): nothing deletes and the pending
 *    state clears; the row has already rolled back visually by the
 *    veto itself, so the composable needs no extra reset call for it.
 *
 * LIVE-GUARD TWICE (the store's own rule is the second gate): arming
 * repeats [ExportRowModel.isDeletable] — only TERMINAL rows
 * (DONE/FAILED/CANCELLED) can ever be armed — and [confirmIds] routes
 * only ids that are STILL deletable at the button press: a row that
 * turned live between the arm and the confirm (a Retry racing the
 * swipe) drops out here, exactly like [ExportsSelection.deleteIds].
 */
object ExportsDeleteConfirm {

    /**
     * The id a completed swipe arms for confirmation, or null when no
     * delete is pending. Arming is honest about the row's state: a live
     * row (QUEUED/RUNNING) can only be cancelled (deleting one would
     * orphan the encoder), so it never arms.
     */
    fun arm(pendingId: String?, job: ExportJob): String? =
        if (ExportRowModel.isDeletable(job.state)) job.id else pendingId

    /**
     * The dialog's confirm button: the ids the store's delete receives.
     * The armed id only routes while the row is STILL deletable at the
     * press — the same re-check [ExportsSelection.deleteIds] does for
     * the selection path, so a Retry that re-queued the row between the
     * swipe and the button deletes nothing here (the store's own
     * live-guard repeats the rule at its write).
     */
    fun confirmIds(pendingId: String?, jobs: List<ExportJob>): Set<String> =
        if (pendingId == null) {
            emptySet()
        } else {
            jobs.filterTo(HashSet()) { it.id == pendingId && ExportRowModel.isDeletable(it.state) }
                .mapTo(HashSet()) { it.id }
        }

    /**
     * The cancel/dismiss outcome: nothing deletes, the armed state
     * clears. The row itself already rolled back at the veto — the
     * swipe's visual reset is material3's own rollback animation, not
     * this function's business.
     */
    fun cancel(pendingId: String?): String? = null

    /**
     * The dialog's context line (owner request: the dialog should name
     * WHAT leaves): the output file name for a DONE row (the file the
     * user would lose sight of), the source's display name otherwise —
     * a FAILED/CANCELLED row wrote nothing, so its subject is the clip
     * it came from. The map and fallback are the row's own headline
     * rule ([ExportRowModel.sourceName]) for the non-DONE states.
     */
    fun subjectLine(job: ExportJob, sourceNames: Map<String, String>): String =
        if (job.state == State.DONE) {
            job.fileName
        } else {
            ExportRowModel.sourceName(job, sourceNames)
        }
}
