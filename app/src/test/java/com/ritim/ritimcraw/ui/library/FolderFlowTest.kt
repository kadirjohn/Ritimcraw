package com.ritim.ritimcraw.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remembered-folder flow's pure decisions (owner request
 * 2026-11-16): the folder button routes remembered -> browser, null ->
 * picker, and a folder change releases ONLY the old import grant —
 * never the gyro folder's tree, never a re-picked same tree.
 */
class FolderFlowTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMotionCam"
    private val other = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    // ---- the toolbar button's routing ----

    @Test
    fun a_remembered_folder_opens_the_browser_directly() {
        assertEquals(tree, FolderFlow.browserTree(tree))
    }

    @Test
    fun no_remembered_folder_routes_to_the_picker() {
        assertNull(FolderFlow.browserTree(null))
    }

    @Test
    fun a_blank_remembered_folder_degrades_to_the_picker() {
        assertNull(FolderFlow.browserTree(""))
    }

    @Test
    fun a_blank_remembered_folder_degrades_to_the_picker_on_blank_variant() {
        assertNull(FolderFlow.browserTree("   "))
    }

    // ---- the change-folder release guard ----

    @Test
    fun a_change_releases_the_old_import_grant() {
        assertEquals(tree, FolderFlow.grantToRelease(tree, other, null))
    }

    @Test
    fun a_first_pick_releases_nothing() {
        assertNull(FolderFlow.grantToRelease(null, tree, null))
    }

    @Test
    fun repicking_the_same_tree_releases_nothing() {
        assertNull(FolderFlow.grantToRelease(tree, tree, null))
    }

    @Test
    fun the_gyro_folder_tree_is_never_released() {
        // The import and gyro folders are often the SAME tree on this
        // device (one MotionCam folder): the persistable grant is one
        // entry per URI per app, so the import side's change must not
        // kill the gyro auto-match's access.
        assertNull(FolderFlow.grantToRelease(tree, other, tree))
    }

    @Test
    fun the_gyro_guard_applies_only_to_the_matching_tree() {
        // A gyro folder on a DIFFERENT tree does not shield the old
        // import tree: the release is safe there.
        assertEquals(tree, FolderFlow.grantToRelease(tree, other, other))
    }
}
