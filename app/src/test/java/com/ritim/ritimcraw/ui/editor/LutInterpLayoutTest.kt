package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interpretation sheet's pure window-layout rules (owner device
 * feedback 2026-11-13: source and transform presented as two selectable
 * windows inside one sheet). JVM-headless — the composable that renders
 * the windows is owner-verified visually.
 */
class LutInterpLayoutTest {

    // ---- the two windows, in display order ----

    @Test
    fun windows_display_source_then_transform() {
        // "Source | Transform": the toggle's display order is the
        // pipeline's own order — the cube reads its source first.
        assertEquals(
            listOf(LutInterpWindow.SOURCE, LutInterpWindow.TRANSFORM),
            LutInterpLayout.WINDOW_ORDER,
        )
    }

    @Test
    fun the_sheet_opens_on_source() {
        // The preset suggestion (the common starting point) lives in
        // SOURCE, so that is the window a first-time user needs first.
        assertEquals(LutInterpWindow.SOURCE, LutInterpLayout.INITIAL_WINDOW)
    }

    // ---- SOURCE: the preset + the input encoding ----

    @Test
    fun source_window_carries_the_preset_and_input_rows() {
        // "What the cube READS": the preset suggestion plus the input
        // gamut/transfer rows.
        assertTrue(LutInterpLayout.hasPresetRow(LutInterpWindow.SOURCE))
        assertTrue(LutInterpLayout.hasInputRows(LutInterpWindow.SOURCE))
        assertFalse(LutInterpLayout.hasOutputRows(LutInterpWindow.SOURCE))
        assertFalse(LutInterpLayout.hasRoleRow(LutInterpWindow.SOURCE))
    }

    // ---- TRANSFORM: the output encoding + the role ----

    @Test
    fun transform_window_carries_the_output_and_role_rows() {
        // "What the cube DOES": the output gamut/transfer rows plus the
        // role that decides where the pipeline applies it.
        assertTrue(LutInterpLayout.hasOutputRows(LutInterpWindow.TRANSFORM))
        assertTrue(LutInterpLayout.hasRoleRow(LutInterpWindow.TRANSFORM))
        assertFalse(LutInterpLayout.hasPresetRow(LutInterpWindow.TRANSFORM))
        assertFalse(LutInterpLayout.hasInputRows(LutInterpWindow.TRANSFORM))
    }

    // ---- the split is a partition: every field has exactly one home ----

    @Test
    fun every_managed_field_has_exactly_one_window() {
        // The five field groups (preset, input, output, role) each
        // appear in EXACTLY one window — nothing is lost in the split,
        // nothing is duplicated across windows.
        for (window in LutInterpWindow.values()) {
            val homes = listOf(
                LutInterpLayout.hasPresetRow(window),
                LutInterpLayout.hasInputRows(window),
                LutInterpLayout.hasOutputRows(window),
                LutInterpLayout.hasRoleRow(window),
            )
            assertTrue(homes.contains(true))
        }
        // Across both windows, each group appears exactly once.
        for (group in listOf(
            LutInterpLayout::hasPresetRow,
            LutInterpLayout::hasInputRows,
            LutInterpLayout::hasOutputRows,
            LutInterpLayout::hasRoleRow,
        )) {
            assertEquals(1, LutInterpWindow.values().count { group(it) })
        }
    }
}
