package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorCategories tests: the editor panel's structure per the 2026-11-13
 * editor panel segmentation plan — the four main-category indices the
 * bottom navigation, the landscape rail, the panel region dispatch and
 * the per-category scroll map all key on, and the Color tab's
 * Balance | Wheels | Look sub-tab list with its gate and sanitizer. Locking
 * the counts and the orders here means a future category or sub-tab change
 * must touch the pure object, and the nav/rail/dispatch/row follow it.
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

    // ---- the Color sub-tabs (segmentation plan: Balance | Wheels | Look) ----

    @Test
    fun color_subtabs_in_row_order() {
        // The Color tab's three segments in SecondaryTabRow order; the
        // pinned row composes from this list, so the order here IS the
        // order on screen.
        assertEquals(
            listOf(0, 1, 2),
            EditorCategories.COLOR_SUBTABS,
        )
    }

    @Test
    fun visible_color_subtabs_drop_wheels_on_the_encoded_path() {
        // Wheels is MCRAW-only (the showColorTools gate): the encoded
        // path takes the same Grade but ignores the color-package
        // fields, so the wheel would be dead controls there. The row
        // composes exactly the usable tabs.
        assertEquals(
            EditorCategories.COLOR_SUBTABS,
            EditorCategories.visibleColorSubTabs(showColorTools = true),
        )
        assertEquals(
            listOf(EditorCategories.COLOR_BALANCE, EditorCategories.COLOR_LOOK),
            EditorCategories.visibleColorSubTabs(showColorTools = false),
        )
    }

    @Test
    fun sanitize_keeps_a_valid_selection() {
        // Every visible sub-tab sanitizes to itself — the saved
        // selection survives a reopen unchanged on its own clip kind.
        for (subTab in EditorCategories.visibleColorSubTabs(showColorTools = true)) {
            assertEquals(subTab, EditorCategories.sanitizeColorSubTab(subTab, showColorTools = true))
        }
        for (subTab in EditorCategories.visibleColorSubTabs(showColorTools = false)) {
            assertEquals(subTab, EditorCategories.sanitizeColorSubTab(subTab, showColorTools = false))
        }
    }

    @Test
    fun sanitize_falls_back_to_balance_when_wheels_meets_the_encoded_path() {
        // The plan's cross-clip case: a Wheels selection saved on an
        // MCRAW clip falls back to Balance when the same editor opens
        // an encoded clip — the gate dropped the tab from under the
        // saved selection, and the row/body must never show a selected
        // tab the row did not compose.
        assertEquals(
            EditorCategories.COLOR_BALANCE,
            EditorCategories.sanitizeColorSubTab(
                EditorCategories.COLOR_WHEELS,
                showColorTools = false,
            ),
        )
    }

    @Test
    fun sanitize_falls_back_to_first_visible_on_garbage_ints() {
        // A saveable Int restored after a version change could be
        // anything: the sanitizer resolves to the first visible sub-tab
        // (Balance on both clip kinds), never an IndexOutOfBounds, never
        // a selected index outside the composed list.
        for (garbage in listOf(-1, 3, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(
                EditorCategories.COLOR_BALANCE,
                EditorCategories.sanitizeColorSubTab(garbage, showColorTools = true),
            )
            assertEquals(
                EditorCategories.COLOR_BALANCE,
                EditorCategories.sanitizeColorSubTab(garbage, showColorTools = false),
            )
        }
    }
}
