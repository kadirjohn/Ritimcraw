package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editor fixed-region layout math tests (design review 2026-11-10
 * "Onerilen ilk yerlesim"): the preview cap keeps the frame deterministic
 * across tabs, and the compact slider's bipolar fill-from-zero track
 * computes its fractions purely (the composable half only draws them).
 */
class EditorLayoutTest {

    // ---- preview height cap ----

    @Test
    fun preview_height_is_the_38_percent_draft_cap() {
        assertEquals(380, EditorLayout.previewHeightPx(1000))
    }

    @Test
    fun preview_height_never_exceeds_the_available_height() {
        // Short screen: the cap is a fraction of a SMALLER pool, so it can
        // never exceed the pool itself — but the rule is coerced anyway.
        assertEquals(0, EditorLayout.previewHeightPx(0))
        assertEquals(0, EditorLayout.previewHeightPx(1))
    }

    @Test
    fun preview_height_rejects_negative_available_height() {
        val error = runCatching { EditorLayout.previewHeightPx(-10) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // ---- fractionOf ----

    @Test
    fun fraction_of_midpoint_is_half() {
        assertEquals(0.5f, EditorLayout.fractionOf(0f, -100f..100f), 1e-4f)
    }

    @Test
    fun fraction_saturates_at_the_endpoints() {
        assertEquals(0f, EditorLayout.fractionOf(-500f, -100f..100f), 1e-4f)
        assertEquals(1f, EditorLayout.fractionOf(500f, -100f..100f), 1e-4f)
    }

    @Test
    fun fraction_of_unipolar_midpoint_is_half() {
        assertEquals(0.5f, EditorLayout.fractionOf(26000f, 2000f..50000f), 1e-4f)
    }

    @Test
    fun fraction_of_degenerate_range_is_zero() {
        assertEquals(0f, EditorLayout.fractionOf(42f, 5f..5f), 1e-4f)
        assertEquals(0f, EditorLayout.fractionOf(42f, 10f..5f), 1e-4f)
    }

    // ---- bipolarFill ----

    @Test
    fun negative_value_fills_left_of_zero() {
        val fill = EditorLayout.bipolarFill(-50f, -100f..100f)
        assertEquals(0.25f, fill.start, 1e-4f)
        assertEquals(0.5f, fill.endInclusive, 1e-4f)
    }

    @Test
    fun positive_value_fills_right_of_zero() {
        val fill = EditorLayout.bipolarFill(50f, -100f..100f)
        assertEquals(0.5f, fill.start, 1e-4f)
        assertEquals(0.75f, fill.endInclusive, 1e-4f)
    }

    @Test
    fun zero_value_collapses_the_fill() {
        val fill = EditorLayout.bipolarFill(0f, -100f..100f)
        assertEquals(0.5f, fill.start, 1e-4f)
        assertEquals(0.5f, fill.endInclusive, 1e-4f)
    }

    @Test
    fun unipolar_range_fills_from_the_start() {
        // Temperature (2000..50000 K) has no zero inside the range: the
        // fill is the plain min-to-value segment, so the slider always
        // shows an active fill.
        val fill = EditorLayout.bipolarFill(26000f, 2000f..50000f)
        assertEquals(0f, fill.start, 1e-4f)
        assertEquals(0.5f, fill.endInclusive, 1e-4f)
    }

    @Test
    fun saturated_values_keep_a_visible_fill() {
        // Dragged past an endpoint (Compose clamps, but the math is pure):
        // the fill saturates to a full half, never an inverted segment.
        val fill = EditorLayout.bipolarFill(-500f, -100f..100f)
        assertEquals(0f, fill.start, 1e-4f)
        assertEquals(0.5f, fill.endInclusive, 1e-4f)
    }
}
