package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scope presentation tri-state (owner request 2026-11-12 §2): every
 * transition is a pure rule in ScopePresentationRules — strip-tap ->
 * mini, mini-tap -> strip, corner button OFF -> STRIP, corner button
 * STRIP/MINI -> OFF (the two-state toggle never secretly cycles), and
 * the strip's close button always closes. The state persists across a
 * clip change by DESIGN (rememberSaveable, not clip-keyed — the
 * strip's own precedent: content/modes/sampling all survive a clip
 * switch); only the computed RESULTS are clip-keyed, so a persisted
 * MINI never draws a previous clip's data.
 */
class ScopePresentationTest {

    // ---- strip graph tap -> mini ----

    @Test
    fun graph_tap_in_strip_shrinks_to_mini() {
        assertEquals(
            ScopePresentation.MINI,
            ScopePresentationRules.onGraphTap(ScopePresentation.STRIP),
        )
    }

    @Test
    fun graph_tap_outside_strip_keeps_state() {
        // OFF has no graph; MINI's own tap expands (onMiniTap), never
        // re-shrinks. The rule is total: an impossible tap is identity.
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onGraphTap(ScopePresentation.OFF),
        )
        assertEquals(
            ScopePresentation.MINI,
            ScopePresentationRules.onGraphTap(ScopePresentation.MINI),
        )
    }

    // ---- mini tap -> strip ----

    @Test
    fun mini_tap_expands_the_strip() {
        assertEquals(
            ScopePresentation.STRIP,
            ScopePresentationRules.onMiniTap(ScopePresentation.MINI),
        )
    }

    @Test
    fun mini_tap_outside_mini_keeps_state() {
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onMiniTap(ScopePresentation.OFF),
        )
        assertEquals(
            ScopePresentation.STRIP,
            ScopePresentationRules.onMiniTap(ScopePresentation.STRIP),
        )
    }

    // ---- corner toggle: the third way in/out ----

    @Test
    fun corner_toggle_opens_the_strip_from_off() {
        assertEquals(
            ScopePresentation.STRIP,
            ScopePresentationRules.onCornerToggle(ScopePresentation.OFF),
        )
    }

    @Test
    fun corner_toggle_closes_the_strip() {
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onCornerToggle(ScopePresentation.STRIP),
        )
    }

    @Test
    fun corner_toggle_closes_the_mini_too() {
        // The documented choice: the two-state icon means show/hide —
        // from MINI it hides the scope entirely, it never secretly
        // expands the strip (a cycle would hide the third state behind
        // a two-state glyph).
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onCornerToggle(ScopePresentation.MINI),
        )
    }

    // ---- strip's own close button ----

    @Test
    fun close_button_closes_from_any_open_shape() {
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onClose(ScopePresentation.STRIP),
        )
        assertEquals(
            ScopePresentation.OFF,
            ScopePresentationRules.onClose(ScopePresentation.MINI),
        )
    }
}
