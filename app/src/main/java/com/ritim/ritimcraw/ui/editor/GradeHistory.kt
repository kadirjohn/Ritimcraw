package com.ritim.ritimcraw.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ritim.ritimcraw.data.GradeK

/**
 * The grade undo/redo history (feature N7): a bounded stack of immutable
 * [GradeK] snapshots for ONE editor open, per clip. The screen holds it in
 * remember(sourceId) like every other per-clip editor state, so a clip
 * switch starts a fresh history, and it is seeded with the initial grade —
 * the stack's BOTTOM: undo returns null there and the affordance disables.
 *
 * Deliberately NOT persisted (a documented choice, the [GradeClipboard]
 * reasoning, not an oversight): the history is editor-session memory.
 * Process death empties it — the grade itself survives through GradeStore,
 * so the honest post-death state is "nothing to undo", never a stale stack
 * rehydrated against a grade that has since moved. Cross-session undo is
 * out of scope.
 *
 * COALESCING — one finger drag is one undo step. A slider drag fires many
 * onGradeChange frames; recording each would make undo crawl frame by
 * frame. [record] takes a cause: consecutive records sharing the same
 * non-null cause REPLACE the open top instead of pushing, and [settle]
 * closes the top so the NEXT gesture pushes. The screen records
 * slider-path edits (drag frames and tap-to-position jumps alike — both
 * arrive through a panel's onChange route) with [SLIDER_GESTURE] and
 * settles on drag end; discrete edits (toggles, the tonemap/LUT choices,
 * paste/preset applies, double-tap resets) record with a null cause,
 * which never coalesces — each is its own step. The drag-end settle is
 * what keeps two consecutive drags two steps, and it makes the slider's
 * first-frame race harmless: the first frame of a drag can run before the
 * interaction collector reports DragStarted, but the cause is the ROUTE,
 * not the drag flag, so that frame pushes into one open entry either way
 * and the following frames replace it.
 *
 * Semantics (locked by GradeHistoryTest):
 *  - [record] of a grade equal to the current top is a no-op — and a
 *    no-op does NOT clear the redo stack (nothing changed).
 *  - [record] with a non-null cause equal the top's cause while the top
 *    is open replaces the top (one gesture, one entry). Any other record
 *    pushes, and a push clears the redo stack (the classic undo rule).
 *  - [undo] returns the grade to apply, or null at the bottom; a null at
 *    the bottom leaves the redo stack untouched.
 *  - [redo] returns the re-applied grade, or null with nothing to redo.
 *  - Undo/redo do not record, and the entry they move is SETTLED — the
 *    next slider gesture after a redo pushes, it never welds onto the
 *    redone entry.
 *  - Overflow drops the OLDEST entry (the bottom): at [MAX_ENTRIES] the
 *    initial grade can fall off, and undo bottoms out at the oldest
 *    surviving state.
 *
 * [canUndo]/[canRedo] are Compose snapshot reads (the entry lists are
 * snapshot state), so the top-bar buttons enable and disable reactively
 * without the screen keeping mirrors in sync. The class is otherwise
 * plain Kotlin and JVM-tested headless.
 */
class GradeHistory(initial: GradeK) {

    companion object {
        /**
         * The cause token for slider-path edits: every grade change that
         * arrives through a panel's onChange route (a drag's frames AND a
         * tap-to-position jump — a tap is a zero-length drag). Discrete
         * edits pass a null cause and never coalesce.
         */
        val SLIDER_GESTURE = Any()

        /**
         * Capacity: 101 entries INCLUDING the initial bottom state — the
         * convention the owner flagged and this constant states precisely:
         * [MAX_ENTRIES] counts STATES, not undo STEPS. 101 states = the
         * seed plus 100 recorded steps = 100 servable undos (the owner-
         * requested budget). Undo bottoms out one short of the bottom's
         * neighbor: undo returns the grade to APPLY, and the initial state
         * is a state to BE, not a step to take — so 101 states serve
         * exactly 100 undos, never 101. Memory stays trivial: 101 GradeK
         * snapshots is tens of KB, next to one decoded preview frame.
         * Overflow drops the OLDEST entry (the bottom): undo is about
         * recent context, and the deepest states of a long session are the
         * least likely to be wanted back.
         */
        const val MAX_ENTRIES = 101
    }

    /**
     * One history step: the grade, the cause it was recorded with, and
     * whether the gesture that recorded it is still in flight (open entries
     * coalesce; settled entries never do). Immutable — the stack is
     * copy-on-write so the snapshot state never shares a mutated entry.
     */
    private data class Entry(
        val grade: GradeK,
        val cause: Any?,
        val open: Boolean,
    )

    private var entries by mutableStateOf(listOf(Entry(initial, cause = null, open = false)))
    private var redoEntries by mutableStateOf(listOf<Entry>())

    /** Whether an undo would produce a grade (UI gating). */
    val canUndo: Boolean get() = entries.size > 1

    /** Whether a redo would produce a grade (UI gating). */
    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    /**
     * Records one grade edit. A grade equal to the top is a no-op (the
     * redo stack survives — nothing changed). A non-null cause equal to
     * the open top's cause replaces the top: the gesture is still in
     * flight, so the step's endpoint moves. Anything else pushes a new
     * entry — clearing the redo stack.
     */
    fun record(grade: GradeK, cause: Any? = null) {
        val top = entries.lastOrNull()
        when {
            top != null && top.grade == grade -> return
            cause != null && top != null && top.cause == cause && top.open -> {
                entries = entries.dropLast(1) + Entry(grade, cause, open = true)
            }
            else -> {
                if (redoEntries.isNotEmpty()) redoEntries = emptyList()
                entries = (entries + Entry(grade, cause, open = cause != null))
                    .takeLast(MAX_ENTRIES)
            }
        }
    }

    /**
     * Closes the top entry: the gesture that opened it is complete, so the
     * next slider-path record pushes a NEW step instead of replacing. The
     * screen calls this on drag end — including a tap's zero-length drag,
     * which is correct: a tap is one discrete step. A no-op on an empty or
     * already-settled stack.
     */
    fun settle() {
        val top = entries.lastOrNull() ?: return
        if (top.open) {
            entries = entries.dropLast(1) + top.copy(open = false)
        }
    }

    /**
     * The grade one step back, for the caller to apply; null at the bottom
     * (the initial grade is a state to BE, not a step to take). The popped
     * entry moves to the redo stack, settled.
     */
    fun undo(): GradeK? {
        if (entries.size <= 1) return null
        val popped = entries.last()
        entries = entries.dropLast(1)
        redoEntries = redoEntries + popped.copy(open = false)
        return entries.last().grade
    }

    /**
     * The grade one step forward, for the caller to apply; null with
     * nothing to redo. The restored entry is settled, so the next gesture
     * after a redo pushes a fresh step.
     */
    fun redo(): GradeK? {
        val popped = redoEntries.lastOrNull() ?: return null
        redoEntries = redoEntries.dropLast(1)
        entries = entries + popped.copy(open = false)
        return popped.grade
    }
}
