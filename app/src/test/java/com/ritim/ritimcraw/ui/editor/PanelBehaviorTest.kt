package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's pure open/unfold decisions (owner device feedback
 * 2026-11-13): the fresh-open default is the LARGEST state, and an
 * upward release from the collapsed strip re-targets the component's
 * own settle animation toward EXPANDED. JVM-headless — the wiring that
 * feeds these rules is owner-verified visually.
 */
class PanelBehaviorTest {

    // ---- fresh-open default ----

    @Test
    fun panel_opens_at_the_largest_state() {
        // "default en yüksek boyda açılsın": the editor opens ready to
        // grade, the user collapses when they want the preview back.
        assertEquals(PanelState.EXPANDED, PanelBehavior.DEFAULT_OPEN)
    }

    // ---- swipe-up target ----

    @Test
    fun swipe_up_targets_expanded_from_every_state() {
        // The full unfold from COLLAPSED is the owner's ask; from
        // NORMAL/EXPANDED an up-release already heads to the top
        // anchor, so the same answer keeps one rule for every state.
        assertEquals(PanelState.EXPANDED, PanelBehavior.swipeUpTarget(PanelState.COLLAPSED))
        assertEquals(PanelState.EXPANDED, PanelBehavior.swipeUpTarget(PanelState.NORMAL))
        assertEquals(PanelState.EXPANDED, PanelBehavior.swipeUpTarget(PanelState.EXPANDED))
    }

    // ---- the redirect rule (settled -> settlingTo) ----

    @Test
    fun an_up_release_from_collapsed_is_retargeted() {
        // The component's settle can only pick the ADJACENT anchor, so
        // from COLLAPSED an up-release animates toward NORMAL — the one
        // gap between the component and the owner's full-unfold ask.
        assertTrue(PanelBehavior.swipeUpRetarget(PanelState.COLLAPSED, PanelState.NORMAL))
    }

    @Test
    fun an_abandoned_nudge_back_to_collapsed_is_never_retargeted() {
        // A release below settle's own positional threshold falls back
        // to COLLAPSED — an abandoned gesture stays honest.
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.COLLAPSED, PanelState.COLLAPSED))
    }

    @Test
    fun the_redirect_animation_itself_never_retargets() {
        // After the redirect the panel animates COLLAPSED -> EXPANDED;
        // the rule must read false for its own animation or the
        // observer would loop.
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.COLLAPSED, PanelState.EXPANDED))
    }

    @Test
    fun releases_from_open_states_are_untouched() {
        // Up from NORMAL already targets EXPANDED (the adjacent anchor);
        // every downward settle keeps the component's own behavior —
        // the states themselves are good, only the COLLAPSED default
        // changes.
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.NORMAL, PanelState.EXPANDED))
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.NORMAL, PanelState.NORMAL))
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.NORMAL, PanelState.COLLAPSED))
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.EXPANDED, PanelState.NORMAL))
        assertFalse(PanelBehavior.swipeUpRetarget(PanelState.EXPANDED, PanelState.COLLAPSED))
    }
}
