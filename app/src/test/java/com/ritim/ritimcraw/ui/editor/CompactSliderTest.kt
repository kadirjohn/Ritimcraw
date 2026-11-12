package com.ritim.ritimcraw.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact slider row tests (owner request 2026-11-12: "the vertical
 * spacing between the sliders is too large — compact, not cramped"): the
 * overlaid row is exactly the 48dp touch floor (DESIGN.md Touch targets),
 * never under it, and the label overlays only while its scaled line
 * height fits the slot's whitespace budget — large font scales fall back
 * to the stacked geometry instead of crowding the thumb band.
 */
class CompactSliderTest {

    // ---- row height (the touch floor) ----

    @Test
    fun overlaid_row_is_exactly_the_48dp_touch_floor() {
        assertEquals(48f, CompactSliderDefaults.ROW_HEIGHT.value, 0f)
    }

    @Test
    fun row_height_never_drops_below_the_touch_floor() {
        // "48dp total touch target per slider row is the floor — don't go
        // under it" (coordinator relay, 2026-11-12).
        assertTrue(CompactSliderDefaults.ROW_HEIGHT >= 48.dp)
    }

    // ---- overlay decision ----

    @Test
    fun default_label_large_overlays_at_font_scale_1() {
        // labelLarge is 20sp/20sp (Type.kt): 20 * 1.0 = 20 <= 22 budget.
        assertTrue(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 20f, fontScale = 1.0f))
    }

    @Test
    fun overlay_survives_font_scale_1_1() {
        // 20sp * 1.1 = 22sp: still inside the budget.
        assertTrue(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 20f, fontScale = 1.1f))
    }

    @Test
    fun font_scale_1_2_stacks_the_label_above_the_slider() {
        // 20sp * 1.2 = 24sp: over the 22sp budget — the descender tail
        // would reach the track line, so the row falls back to the
        // taller, uncrowded stacked geometry.
        assertFalse(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 20f, fontScale = 1.2f))
    }

    @Test
    fun large_font_scale_always_stacks() {
        assertFalse(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 20f, fontScale = 2.0f))
    }

    @Test
    fun unspecified_line_height_stacks_as_the_safe_fallback() {
        // TextUnit.Unspecified.value is NaN; NaN comparisons are false, so
        // an unstyled label never overlays on an accident of arithmetic.
        assertFalse(CompactSliderDefaults.overlayLabel(labelLineHeightSp = Float.NaN, fontScale = 1.0f))
    }

    @Test
    fun overlay_budget_admits_the_exact_boundary() {
        assertTrue(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 22f, fontScale = 1.0f))
        assertFalse(CompactSliderDefaults.overlayLabel(labelLineHeightSp = 22.1f, fontScale = 1.0f))
    }

    // ---- track geometry unchanged (visual style stays modern) ----

    @Test
    fun track_stays_thin_and_thumb_stays_in_the_spec_band() {
        // Owner 2026-11-12: "the sliders must stay modern... current
        // visual style unchanged" — the thin line and the 16-20dp visible
        // thumb (owner request 2026-11-11d section 12.1) carry over.
        assertEquals(4f, CompactSliderDefaults.TRACK_HEIGHT.value, 0f)
        assertEquals(16f, CompactSliderDefaults.THUMB_SIZE.width.value, 0f)
        assertEquals(16f, CompactSliderDefaults.THUMB_SIZE.height.value, 0f)
    }
}
