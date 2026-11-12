package com.ritim.ritimcraw.data.timeline

/**
 * Edit transactions for the timeline (TL-07: cut/move/trim/duplicate/
 * delete recorded as transactions; undo restores the old range) and
 * their history. A COMMAND-PATTERN choice, documented: each edit is an
 * immutable [TimelineEdit] transaction object that carries its own
 * parameters, [TimelineEdit.apply] returns the new project, and
 * [TimelineEdit.inverse] — invoked against the project the edit was
 * ABOUT to change (the pre-edit state, captured by the history at
 * apply time) — returns the transaction that undoes it EXACTLY. Undo =
 * apply the inverse; redo = re-apply the original.
 *
 * WHY INVERSES TAKE THE PRE-EDIT PROJECT: an inverse like SplitClip's
 * needs the ORIGINAL clip (its full range, its link) to restore the
 * exact pre-edit state — "iptal eski aralığı geri getirir" (TL-07).
 * Reading that state from the post-edit project would be guesswork;
 * capturing it pre-edit makes every inverse exact by construction.
 * The transaction objects stay small: inverses are built ONCE at
 * apply time and stored by the history, never recomputed.
 *
 * The alternative — snapshot history like ui/editor/GradeHistory —
 * was rejected for the timeline because transactions restore the exact
 * old clip (id AND range — see the split-undo rule below), where a
 * naive snapshot diff would tempt "restore the range but renumber",
 * which the ID STABILITY rule forbids.
 *
 * UNDO CAPACITY (the convention the owner flagged, kept documented):
 * BOUNDED at 100 undo STEPS — [TimelineHistory.MAX_STEPS] = 100, the
 * same 100-undo budget as GradeHistory's 101 states (101 states =
 * seed + 100 steps = 100 servable undos). Here the history stores
 * TRANSACTIONS, not states, so the bound counts steps directly: 100
 * applied transactions are servable, overflow drops the OLDEST (deep
 * history is the least likely to be wanted back; a timeline session
 * never legitimately walks 100 steps into the past). The bound keeps
 * undo memory flat regardless of project size, and undo/redo are O(1)
 * applies on a small model.
 *
 * Undo order = reverse apply order (locked by test): the history is a
 * stack; undo pops the newest, redo re-applies the popped. A new apply
 * clears the redo stack (the classic undo rule).
 */
sealed interface TimelineEdit {

    /** Applies the transaction; returns the SAME project on a no-op. */
    fun apply(project: TimelineProject): TimelineProject

    /**
     * The transaction that undoes this one EXACTLY, built against
     * [project] — the PRE-edit state (the history applies this at
     * record time; direct callers pass the about-to-change project).
     */
    fun inverse(project: TimelineProject): TimelineEdit

    // -------------------------------------------------------------
    // TL-03: add / move / duplicate / delete
    // -------------------------------------------------------------

    /** Adds a clip (minted id comes from the caller, TimelineIds). */
    data class AddClip(val clip: TimelineClip) : TimelineEdit {
        override fun apply(project: TimelineProject) = TimelineOps.add(project, clip)
        override fun inverse(project: TimelineProject) = RemoveClip(clip.id)
    }

    /** Removes a clip by id; missing id is a no-op. */
    data class RemoveClip(val clipId: String) : TimelineEdit {
        override fun apply(project: TimelineProject) = TimelineOps.delete(project, clipId)
        override fun inverse(project: TimelineProject): TimelineEdit {
            val removed = project.clip(clipId) ?: return NoOp
            return AddClip(removed)
        }
    }

    /**
     * Moves a clip to a new timeline start (and optionally track).
     * The inverse returns the clip to its exact old placement — the
     * pre-edit position and track, read from the pre-edit project.
     */
    data class MoveClip(val clipId: String, val newTimelineStart: Double, val newTrackId: String? = null) : TimelineEdit {
        override fun apply(project: TimelineProject) =
            TimelineOps.move(project, clipId, newTimelineStart, newTrackId)

        override fun inverse(project: TimelineProject): TimelineEdit {
            val c = project.clip(clipId) ?: return NoOp
            return MoveClip(clipId, c.timelineRange.start, c.trackId)
        }
    }

    /**
     * Duplicates a clip under a fresh id (caller-minted). The inverse
     * removes the duplicate, leaving the original untouched.
     */
    data class DuplicateClip(val clipId: String, val newId: String) : TimelineEdit {
        override fun apply(project: TimelineProject) = TimelineOps.duplicate(project, clipId, newId)
        override fun inverse(project: TimelineProject) = RemoveClip(newId)
    }

    // -------------------------------------------------------------
    // TL-04/TL-05: trim and split
    // -------------------------------------------------------------

    /**
     * Trims a clip edge (TL-04: both edges, one time contract). The
     * inverse restores the exact old edge position — "iptal eski
     * aralığı geri getirir". The linked audio follows the trim (the
     * ops' TL-05 rule), and follows the undo the same way: the inverse
     * restores the old position, dragging the audio with it.
     */
    data class TrimClip(
        val clipId: String,
        val edge: TimelineOps.TrimEdge,
        val newPosition: Double,
    ) : TimelineEdit {
        override fun apply(project: TimelineProject): TimelineProject =
            TimelineOps.trim(project, clipId, edge, newPosition)
                ?: project // rejected trim (emptying/extending): no-op

        override fun inverse(project: TimelineProject): TimelineEdit {
            val c = project.clip(clipId) ?: return NoOp
            val oldPosition = when (edge) {
                TimelineOps.TrimEdge.START -> c.sourceRange.start
                TimelineOps.TrimEdge.END -> c.sourceRange.end
            }
            return RestoreTrim(clipId, edge, oldPosition)
        }
    }

    /**
     * The trim undo: restores an edge to its pre-trim position through
     * TimelineOps.trimRestore (the shrink-only guard does not apply to
     * an undo — restoring EXTENDS the clip back, which is the point).
     * Internal to TrimClip.inverse — a user never constructs one.
     */
    data class RestoreTrim(
        val clipId: String,
        val edge: TimelineOps.TrimEdge,
        val oldPosition: Double,
    ) : TimelineEdit {
        override fun apply(project: TimelineProject): TimelineProject =
            TimelineOps.trimRestore(project, clipId, edge, oldPosition) ?: project

        override fun inverse(project: TimelineProject): TimelineEdit {
            // Redo of a trim-undo: re-trim to the position the trim
            // moved it to. The history never calls this on RestoreTrim
            // directly; redo re-applies the STORED original TrimClip.
            return NoOp
        }
    }

    /**
     * Splits a clip at the playhead (TL-05). The right piece gets the
     * caller-minted [rightId]; a linked audio's right piece gets
     * [audioRightId] (also caller-minted, so undo can address it).
     * The UNDO rule: the inverse removes the right pieces AND restores
     * the left piece to the exact ORIGINAL range — the pre-split clip
     * comes back with its own id, its own range and its own link, not
     * just the range numbers. The pre-edit project supplies that
     * original clip, which is why the inverse takes it.
     */
    data class SplitClip(
        val clipId: String,
        val frame: Double,
        val rightId: String,
        val audioRightId: String? = null,
    ) : TimelineEdit {
        override fun apply(project: TimelineProject): TimelineProject =
            TimelineOps.split(project, clipId, frame, rightId, audioRightId) ?: project

        override fun inverse(project: TimelineProject): TimelineEdit {
            val original = project.clip(clipId) ?: return NoOp
            val originalAudioId = original.linkedAudioId
            val originalAudio = originalAudioId?.let { project.clip(it) }
            return RestoreSplit(
                original = original,
                rightId = rightId,
                originalAudio = originalAudio,
                audioRightId = audioRightId,
            )
        }
    }

    /**
     * The split undo: put the ORIGINAL clip back exactly (id + range +
     * link) and remove the split's right pieces. Internal to
     * SplitClip.inverse — a user never constructs one. Redo re-splits
     * the restored clip at the same frame (derivable from the stored
     * transaction), so undo-then-redo round-trips.
     */
    data class RestoreSplit(
        private val original: TimelineClip,
        private val rightId: String,
        private val originalAudio: TimelineClip?,
        private val audioRightId: String?,
    ) : TimelineEdit {
        override fun apply(project: TimelineProject): TimelineProject {
            var clips = project.clips
                .filterNot { it.id == rightId || (audioRightId != null && it.id == audioRightId) }
                .map { if (it.id == original.id) original else it }
            if (originalAudio != null && audioRightId != null) {
                clips = clips.map { if (it.id == originalAudio.id) originalAudio else it }
            }
            return project.copy(clips = clips)
        }

        override fun inverse(project: TimelineProject): TimelineEdit {
            // Redo of a split-undo: re-split the restored original at
            // the frame it was split at (the frame is where the right
            // piece STARTED — recoverable from the redo application).
            // The history never calls this on RestoreSplit directly;
            // redo re-applies the STORED original SplitClip instead.
            return NoOp
        }
    }

    // -------------------------------------------------------------
    // Settings (TL-01/TL-02)
    // -------------------------------------------------------------

    /** Replaces the output settings; the inverse restores the old ones. */
    data class SetOutput(val output: TimelineOutput) : TimelineEdit {
        override fun apply(project: TimelineProject) = project.copy(output = output)
        override fun inverse(project: TimelineProject) = SetOutput(project.output)
    }

    /** Replaces a clip's per-clip color definition (TL-02). */
    data class SetClipColor(val clipId: String, val colorDefinition: ClipColorDefinition) : TimelineEdit {
        override fun apply(project: TimelineProject): TimelineProject {
            val c = project.clip(clipId) ?: return project
            return project.copy(
                clips = project.clips.map { if (it.id == clipId) c.copy(colorDefinition = colorDefinition) else it },
            )
        }

        override fun inverse(project: TimelineProject): TimelineEdit {
            val c = project.clip(clipId) ?: return NoOp
            return SetClipColor(clipId, c.colorDefinition)
        }
    }

    /** The null transaction: an inverse with nothing to undo. */
    data object NoOp : TimelineEdit {
        override fun apply(project: TimelineProject) = project
        override fun inverse(project: TimelineProject) = NoOp
    }
}

/**
 * The bounded transaction history (TL-07): apply records, undo applies
 * the recorded inverse, redo re-applies the popped ORIGINAL transaction
 * (inverses' inverses are never recomputed — the stored original is
 * the redo). Pure Kotlin — the UI mission wraps it in Compose state if
 * it wants reactive canUndo/canRedo; the semantics are locked here
 * headless.
 *
 * NOT persisted (the GradeHistory reasoning, kept as the documented
 * choice): history is session memory. The PROJECT survives through
 * TimelineStore, so the honest post-restart state is "nothing to
 * undo" — never a stale stack rehydrated against a project that has
 * since moved. TL-16's "düzenleme geçmişi" (edit history) is honored
 * by the transaction recording itself: within a session every cut/
 * move/trim is a recorded, undoable transaction; cross-session undo
 * is out of scope for v1 (the store's forward-tolerance reserves
 * room for a future history section if ever wanted).
 */
class TimelineHistory(initial: TimelineProject) {

    companion object {
        /**
         * Capacity: 100 undo STEPS (the convention documented in the
         * TimelineEdit KDoc — the GradeHistory 101-states/100-undos
         * budget, counted here in transactions). Overflow drops the
         * OLDEST step.
         */
        const val MAX_STEPS = 100
    }

    private data class Step(val edit: TimelineEdit, val inverse: TimelineEdit)

    private val steps = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var current: TimelineProject = initial

    /** The project after all applied (not undone) transactions. */
    val project: TimelineProject get() = current

    val canUndo: Boolean get() = steps.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Applies [edit] to the current project and records it. The
     * inverse is built against the PRE-edit project (see the
     * TimelineEdit KDoc) — captured here, before the apply. A no-op
     * apply (rejected trim, missing clip) is not recorded and does
     * NOT clear the redo stack (nothing changed). A real apply
     * clears the redo stack (the classic undo rule). Overflow drops
     * the OLDEST step.
     */
    fun apply(edit: TimelineEdit): TimelineProject {
        val before = current
        val applied = edit.apply(before)
        if (applied === before) return current
        redoStack.clear()
        steps.addLast(Step(edit, edit.inverse(before)))
        while (steps.size > MAX_STEPS) steps.removeFirst()
        current = applied
        return current
    }

    /**
     * Undoes the newest recorded transaction: applies its stored
     * inverse and moves the step to the redo stack. Returns the
     * project (unchanged when nothing is recorded).
     */
    fun undo(): TimelineProject {
        val step = steps.removeLastOrNull() ?: return current
        current = step.inverse.apply(current)
        redoStack.addLast(step)
        return current
    }

    /**
     * Redoes the newest undone transaction: re-applies the stored
     * ORIGINAL transaction (not the inverse's inverse — the original
     * is exact by definition). Returns the project (unchanged when
     * nothing to redo).
     */
    fun redo(): TimelineProject {
        val step = redoStack.removeLastOrNull() ?: return current
        current = step.edit.apply(current)
        steps.addLast(step)
        while (steps.size > MAX_STEPS) steps.removeFirst()
        return current
    }
}
