package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorCategories tests: the editor panel's main-category structure
 * (2026-11-13 editor panel segmentation plan) — the four indices the
 * bottom navigation, the landscape rail, the panel region dispatch and
 * the per-category scroll map all key on. Locking the count and the
 * order here means a future category change must touch the pure object,
 * and the nav/rail/dispatch follow it.
 */
class EditorCategoriesTest {

    // ---- the four main categories, in navigation order ----

    @Test
    fun four_categories_in_navigation_order() {
        // The panel's main tabs are Light, Color, Detail, Frame — the
        // segmentation plan's structure. The order is the nav's item
        // order; the panel region dispatch and the scroll-state map use
        // the same constants.
        assertEquals(
            listOf(0, 1, 2, 3),
            EditorCategories.ALL,
        )
    }

    @Test
    fun frame_is_fourth_not_a_renumbering() {
        // The plan ADDS the Frame tab; the existing three keep their
        // indices so the saveable `tab` Int (a persisted selection) and
        // every existing wiring keep meaning what they meant.
        assertEquals(0, EditorCategories.LIGHT)
        assertEquals(1, EditorCategories.COLOR)
        assertEquals(2, EditorCategories.DETAIL)
        assertEquals(3, EditorCategories.FRAME)
    }

    // ---- the membership guard ----

    @Test
    fun is_main_category_accepts_exactly_the_four() {
        for (category in EditorCategories.ALL) {
            assertTrue(EditorCategories.isMainCategory(category))
        }
    }

    @Test
    fun is_main_category_rejects_everything_else() {
        // A saveable Int restored after a version change (or a future
        // category removed) could be anything: the guard reads false,
        // never an index error or a silent misdispatch.
        for (category in listOf(-1, 4, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertFalse(EditorCategories.isMainCategory(category))
        }
    }
}
