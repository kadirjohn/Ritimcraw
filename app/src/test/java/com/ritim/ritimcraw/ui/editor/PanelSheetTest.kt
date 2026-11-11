package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Panel state machine tests (owner request 2026-11-11d sections 10-11): the
 * three anchors adapt to the measured region and stay ordered on every
 * device, the snap thresholds are the Material sheet conventions, and a
 * category tap while collapsed reopens to normal (section 10.3).
 */
class PanelSheetTest {

    // ---- anchor heights ----

    @Test
    fun anchors_are_ordered_collapsed_normal_expanded() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun normal_is_the_44_percent_draft_share() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(440f, anchors.getValue(PanelState.NORMAL), 0.5f)
    }

    @Test
    fun expanded_is_the_72_percent_share() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(720f, anchors.getValue(PanelState.EXPANDED), 0.5f)
    }

    @Test
    fun collapsed_anchor_is_the_handle_strip() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(48f, anchors.getValue(PanelState.COLLAPSED), 0f)
    }

    @Test
    fun anchors_never_exceed_the_region() {
        // A tall handle strip near the region size: every anchor is coerced
        // into the region and stays ordered (degenerate but never broken).
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 100, collapsedHeightPx = 90)
        assertTrue(anchors.getValue(PanelState.EXPANDED) <= 100f)
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
    }

    @Test
    fun degenerate_zero_region_collapses_all_anchors_to_zero() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 0, collapsedHeightPx = 48)
        assertEquals(0f, anchors.getValue(PanelState.COLLAPSED), 0f)
        assertEquals(0f, anchors.getValue(PanelState.NORMAL), 0f)
        assertEquals(0f, anchors.getValue(PanelState.EXPANDED), 0f)
    }

    @Test
    fun tiny_region_still_yields_ordered_anchors() {
        // 30 px region with a 48 px strip: the strip is coerced in and the
        // fractions still produce ordered, in-region anchors.
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 30, collapsedHeightPx = 48)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= 30f)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun anchor_heights_reject_negative_inputs() {
        assertTrue(
            runCatching { PanelSheet.anchorHeights(-1, 48) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { PanelSheet.anchorHeights(1000, -1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    // ---- snap thresholds ----

    @Test
    fun positional_threshold_is_half_the_inter_anchor_distance() {
        assertEquals(50f, PanelSheet.positionalThreshold(100f), 1e-4f)
        assertEquals(0f, PanelSheet.positionalThreshold(0f), 1e-4f)
    }

    @Test
    fun snap_duration_is_inside_the_spec_band() {
        // Section 11.4: "approximately 180-250ms".
        assertTrue(PanelSheet.SNAP_DURATION_MS in 180..250)
    }

    // ---- category tap decision (section 10.3) ----

    @Test
    fun category_tap_while_collapsed_reopens_to_normal() {
        assertEquals(PanelState.NORMAL, PanelSheet.panelAfterCategoryTap(PanelState.COLLAPSED))
    }

    @Test
    fun category_tap_while_open_never_moves_the_panel() {
        assertEquals(PanelState.NORMAL, PanelSheet.panelAfterCategoryTap(PanelState.NORMAL))
        assertEquals(PanelState.EXPANDED, PanelSheet.panelAfterCategoryTap(PanelState.EXPANDED))
    }
}
