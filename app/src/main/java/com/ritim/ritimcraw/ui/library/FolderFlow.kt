package com.ritim.ritimcraw.ui.library

/**
 * The remembered-folder flow's pure decisions (owner request
 * 2026-11-16): the toolbar's folder button routes to the browser or the
 * tree picker, and a folder CHANGE decides which old grant to release.
 * The composables keep only the launchers; every decision is one of
 * these pure functions, so the flow's rules are JVM-testable without
 * Compose (the [LibrarySelection] shape).
 *
 * The flow itself (the owner's words): a granted folder is REMEMBERED
 * across restarts; the folder button opens the browser on it DIRECTLY —
 * no picker, no re-asking; the browser's change-folder button is the
 * only path to a different folder; only a first-time user (no
 * remembered folder) sees the picker.
 */
object FolderFlow {

    /**
     * The toolbar's folder button decision: a remembered folder opens
     * the browser on it DIRECTLY (no picker); null routes to the tree
     * picker — the first-time flow. A blank remembered value (a store
     * the user hand-edited) degrades to the picker rather than opening
     * the browser on an unreadable tree — belt-and-braces over the
     * store's own blank filter.
     */
    fun browserTree(remembered: String?): String? =
        remembered?.takeIf { it.isNotBlank() }

    /**
     * Which OLD tree grant a folder change may release — or null when
     * nothing should be released (the caller skips the release call
     * entirely).
     *
     * The guard has two branches, both load-bearing:
     *  - Re-picking the SAME tree releases nothing: the pick refreshed
     *    the grant the browser is already enumerating, and a
     *    release-then-retake gap could drop it if the retake ever
     *    failed.
     *  - The old tree that is ALSO the gyro folder is NEVER released:
     *    the import folder and the gyro folder are SEPARATE stores with
     *    different lifecycles, but on this device they are often the
     *    SAME tree (one MotionCam folder holds both the videos and
     *    their .gcsv logs) — and a persistable grant is ONE entry per
     *    URI per app, so releasing the import side would silently kill
     *    the gyro auto-match's access. The gyro store keeps its say.
     */
    fun grantToRelease(old: String?, new: String, gyroFolder: String?): String? =
        old?.takeIf { it != new && it != gyroFolder }
}
