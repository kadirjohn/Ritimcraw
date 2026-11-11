package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK

/**
 * Copy/Paste settings (owner request 2026-11-11 section 4): the in-memory
 * clipboard that carries one copied grade between clips.
 *
 * Deliberately NOT persisted: the clipboard is process memory, cleared by
 * process death (the OS reclaiming the app) exactly like a desktop
 * clipboard does not survive a reboot. A paste after process death finds
 * the clipboard empty and the "Paste settings" row hides — the safe,
 * honest state, no stale grade silently applied. This is a documented
 * choice, not an oversight: a persisted clipboard would be a preset with
 * worse UX (unnamed, unmanaged, invisible in the list).
 *
 * Semantics (locked by GradeClipboardTest):
 *  - [copy] overwrites whatever was held before (one slot, latest wins).
 *  - [paste] returns the held grade, or null when nothing was copied —
 *    the caller hides/disables the paste affordance on null.
 *  - [hasContent] mirrors paste != null for UI gating without consuming.
 *
 * The LUT selection travels with the grade (GradeK.lutId): pasting onto a
 * clip whose LUT library lacks that id keeps the id verbatim — the grade
 * renders as no-LUT until that cube is (re-)imported (LutStore.pathFor
 * returns null for a missing file, which the native bridge treats as no
 * LUT), and re-importing the same cube restores the look because the
 * fingerprint id is deterministic. Never a crash, never a silent rewrite.
 *
 * A plain singleton object: the clipboard is app-wide by design (copy on
 * one clip, paste on another — crossing screens is the whole point), and
 * all state is a single nullable val behind copy/paste, which is atomic
 * enough for the UI thread that confines it.
 */
object GradeClipboard {

    @Volatile
    private var held: GradeK? = null

    /** Copies the grade; overwrites any previous copy (one slot). */
    fun copy(grade: GradeK) {
        held = grade
    }

    /**
     * The copied grade, or null when nothing is held. Does not clear the
     * clipboard — a copied grade can be pasted onto any number of clips.
     */
    fun paste(): GradeK? = held

    /** Whether a paste would produce a grade (UI gating). */
    fun hasContent(): Boolean = held != null

    /** Test/process-death hook: clears the slot. Production never calls it. */
    fun clear() {
        held = null
    }
}
