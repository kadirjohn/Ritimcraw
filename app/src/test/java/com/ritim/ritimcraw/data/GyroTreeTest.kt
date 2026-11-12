package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of [GyroTree]: the tree document id -> friendly label
 * rule the Settings row shows. The SAF query itself is device-gated
 * (exercised by the owner on device — grant the folder, import a clip,
 * watch the attach).
 */
class GyroTreeTest {

    @Test
    fun primary_volume_label_is_the_path() {
        // The owner's real grant: Documents/MotionCam.
        assertEquals(
            "Documents/MotionCam",
            GyroTree.labelFromDocumentId("primary:Documents/MotionCam"),
        )
    }

    @Test
    fun removable_volume_with_colons_cuts_at_the_last_colon() {
        // SD cards: the volume name itself contains a colon
        // (removable:1234-5678:DCIM) — the path is after the LAST one.
        assertEquals(
            "DCIM/MotionCam",
            GyroTree.labelFromDocumentId("removable:1234-5678:DCIM/MotionCam"),
        )
    }

    @Test
    fun root_tree_id_labels_as_the_root() {
        // Granting the whole primary volume: the path part is empty, so
        // the id itself is the label.
        assertEquals("primary", GyroTree.labelFromDocumentId("primary"))
    }

    @Test
    fun blank_path_is_null() {
        assertNull(GyroTree.labelFromDocumentId("primary:"))
    }
}
