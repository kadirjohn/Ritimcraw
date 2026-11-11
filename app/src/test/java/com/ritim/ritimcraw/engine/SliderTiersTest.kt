package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slider-drag tier state machine (owner request 2026-11-10 section 1):
 * during a drag only the low tier, after the drag end exactly one full
 * settle render, rapid drags coalesce, playback stands the drag tier down.
 */
class SliderTiersTest {

    // ---- during-drag: low tier only ----

    @Test
    fun drag_start_emits_low_tier_and_activates_drag() {
        val t = SliderTiers.onEvent(SliderTiers.Event.DragStarted, dragActive = false)
        assertTrue(t.dragActive)
        val d = t.decision as SliderTiers.Decision.RenderAtDragTier
        assertEquals(SliderTiers.DRAG_MAX_DIM, d.maxDim)
    }

    @Test
    fun grade_edit_during_drag_uses_low_tier() {
        val t = SliderTiers.onEvent(SliderTiers.Event.GradeEdited, dragActive = true)
        assertTrue(t.dragActive)
        assertTrue(t.decision is SliderTiers.Decision.RenderAtDragTier)
    }

    @Test
    fun tier_for_tracks_drag_state() {
        assertEquals(SliderTiers.DRAG_MAX_DIM, SliderTiers.tierFor(dragActive = true))
        assertEquals(SliderTiers.SETTLE_MAX_DIM, SliderTiers.tierFor(dragActive = false))
    }

    // ---- drag end: exactly one full settle render ----

    @Test
    fun drag_end_emits_exactly_one_full_tier_render() {
        val t = SliderTiers.onEvent(SliderTiers.Event.DragEnded, dragActive = true)
        assertFalse(t.dragActive)
        val d = t.decision as SliderTiers.Decision.RenderAtSettleTier
        assertEquals(SliderTiers.SETTLE_MAX_DIM, d.maxDim)
        assertEquals(SliderTiers.SETTLE_MAX_DIM, PreviewTiers.PAUSED_MAX_DIM)
    }

    @Test
    fun grade_edit_after_drag_uses_full_tier() {
        val t = SliderTiers.onEvent(SliderTiers.Event.GradeEdited, dragActive = false)
        assertFalse(t.dragActive)
        assertTrue(t.decision is SliderTiers.Decision.RenderAtSettleTier)
    }

    // ---- rapid drags coalesce ----

    @Test
    fun rapid_drag_restart_replaces_the_owed_settle_with_a_drag_render() {
        // Drag, end (settle owed), drag again before the settle ran: the
        // machine's newest decision is a drag-tier render — the gate's
        // newest-wins slot means the settle never queues behind it.
        var state = SliderTiers.onEvent(SliderTiers.Event.DragStarted, dragActive = false)
        state = SliderTiers.onEvent(SliderTiers.Event.DragEnded, state.dragActive)
        val restart = SliderTiers.onEvent(SliderTiers.Event.DragStarted, state.dragActive)
        assertTrue(restart.dragActive)
        assertTrue(restart.decision is SliderTiers.Decision.RenderAtDragTier)
    }

    @Test
    fun repeated_drag_end_does_not_stack_settles() {
        // Two DragEnded in a row (double-finish edge): each emits ONE
        // settle decision, and the render gate coalesces both offers into
        // the newest pending — at most one full render executes.
        var t = SliderTiers.onEvent(SliderTiers.Event.DragEnded, dragActive = true)
        assertTrue(t.decision is SliderTiers.Decision.RenderAtSettleTier)
        t = SliderTiers.onEvent(SliderTiers.Event.DragEnded, t.dragActive)
        // Still a settle-tier decision (idempotent), still exactly one
        // pending slot in the gate.
        assertTrue(t.decision is SliderTiers.Decision.RenderAtSettleTier)
    }

    // ---- playback stands the drag tier down ----

    @Test
    fun playback_started_deactivates_drag_and_emits_no_render() {
        val t = SliderTiers.onEvent(SliderTiers.Event.PlaybackStarted, dragActive = true)
        assertFalse(t.dragActive)
        assertTrue(t.decision is SliderTiers.Decision.NoRender)
    }

    // ---- full-res source tier (owner request 2026-11-11 section 2) ----

    @Test
    fun source_max_dim_is_the_no_cap_sentinel() {
        // 0 = "no cap": the native strideFor and the encoded ArgbScale.fit
        // both pass a frame through untouched at a non-positive maxDim, so
        // the pause/clip-end full-res render needs no new scaling path —
        // only a tier constant that means "recorded resolution". The drag
        // and settle tiers stay capped (playback and sliders never pay
        // the full-res cost).
        assertEquals(0, PreviewTiers.SOURCE_MAX_DIM)
        assertTrue(SliderTiers.DRAG_MAX_DIM > 0)
        assertTrue(SliderTiers.SETTLE_MAX_DIM > 0)
        assertTrue(PreviewTiers.PLAYBACK_MAX_DIM > 0)
    }
}
