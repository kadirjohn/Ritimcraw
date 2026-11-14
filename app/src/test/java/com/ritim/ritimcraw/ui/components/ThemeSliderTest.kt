package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThemeSlider pure-math tests (owner spec 2026-11-14, theme_slider): the
 * spec demands the slider's correctness NOT depend on seeing the UI —
 * the slot-index formula, the thumb-offset clamp, the resting offsets
 * and the commit rule are pure px functions ([ThemeSliderMath]) and
 * are pinned here.
 *
 * The functions are density-agnostic (px floats in, px floats out —
 * the composable converts dp through LocalDensity), so the tests use
 * the spec's dp numbers AS px (1dp = 1px "density") for readability
 * and a second, scaled set (2x density) to prove nothing assumes
 * 1dp = 1px. Spec geometry: track inset 3dp, slot width 54dp, thumb
 * width 54dp (half = 27dp), 3 slots (0 = Light, 1 = System, 2 = Dark).
 */
class ThemeSliderTest {

    // ---- The spec's dp numbers as px (readable 1:1 case) ----
    private val inset = 3f
    private val slot = 54f
    private val thumb = 54f

    // ---- slotIndex: index = clamp(floor((fingerX - 3dp) / 54dp), 0, 2) ----

    @Test
    fun slotIndex_mapsTrackLeftEdgeToSlotZero() {
        assertEquals(0, ThemeSliderMath.slotIndex(3f, inset, slot))
    }

    @Test
    fun slotIndex_slotBoundariesBelongToTheRightSlot() {
        // x = 57dp is the first x of slot 1 (3 + 54); 111dp starts slot 2.
        assertEquals(0, ThemeSliderMath.slotIndex(56.9f, inset, slot))
        assertEquals(1, ThemeSliderMath.slotIndex(57f, inset, slot))
        assertEquals(1, ThemeSliderMath.slotIndex(110.9f, inset, slot))
        assertEquals(2, ThemeSliderMath.slotIndex(111f, inset, slot))
    }

    @Test
    fun slotIndex_clampsOutsideTheTrack() {
        // Left of the track (including a negative x) and past the right
        // edge both clamp instead of returning -1 / 3.
        assertEquals(0, ThemeSliderMath.slotIndex(0f, inset, slot))
        assertEquals(0, ThemeSliderMath.slotIndex(-40f, inset, slot))
        assertEquals(2, ThemeSliderMath.slotIndex(168f, inset, slot))
        assertEquals(2, ThemeSliderMath.slotIndex(500f, inset, slot))
    }

    // ---- thumbOffsetPx: clamp(fingerX - 27dp, 3dp, 3dp + 2*54dp) ----

    @Test
    fun thumbOffset_followsFingerOneToOneInsideTheTrack() {
        // Not clamped: the thumb's left edge is the finger minus half
        // the thumb (27dp) — a 1dp finger move is a 1dp thumb move.
        assertEquals(33f, ThemeSliderMath.thumbOffsetPx(60f, inset, slot, thumb))
        assertEquals(84f, ThemeSliderMath.thumbOffsetPx(111f, inset, slot, thumb))
    }

    @Test
    fun thumbOffset_clampsToTheTrackEdges() {
        // Min 3dp (thumb's left edge at the inset), max 3 + 2*54 = 111dp.
        assertEquals(3f, ThemeSliderMath.thumbOffsetPx(0f, inset, slot, thumb))
        assertEquals(3f, ThemeSliderMath.thumbOffsetPx(30f, inset, slot, thumb))
        assertEquals(111f, ThemeSliderMath.thumbOffsetPx(138f, inset, slot, thumb))
        assertEquals(111f, ThemeSliderMath.thumbOffsetPx(168f, inset, slot, thumb))
    }

    // ---- restingOffsetPx: resting x = 3dp + index * 54dp ----

    @Test
    fun restingOffset_placesTheThumbOnEachSlot() {
        assertEquals(3f, ThemeSliderMath.restingOffsetPx(0, inset, slot))
        assertEquals(57f, ThemeSliderMath.restingOffsetPx(1, inset, slot))
        assertEquals(111f, ThemeSliderMath.restingOffsetPx(2, inset, slot))
    }

    @Test
    fun restingOffset_clampsAnOutOfRangeIndex() {
        assertEquals(3f, ThemeSliderMath.restingOffsetPx(-1, inset, slot))
        assertEquals(111f, ThemeSliderMath.restingOffsetPx(3, inset, slot))
    }

    // ---- The tap invariant: a tap on a slot's center is a no-move ----

    @Test
    fun tapOnSlotCenterLeavesThumbAtThatSlotRestingOffset() {
        // A tap is a down+up at the same place: the offset the finger
        // produces at a slot's CENTER equals the slot's resting offset,
        // so the settle animation has zero distance and the tap selects
        // the slot through the drag path (spec gesture).
        for (index in 0..2) {
            val centerX = inset + index * slot + thumb / 2f
            val offset = ThemeSliderMath.thumbOffsetPx(centerX, inset, slot, thumb)
            assertEquals(
                "tap at the center of slot $index must not move the thumb",
                ThemeSliderMath.restingOffsetPx(index, inset, slot),
                offset,
            )
            assertEquals(index, ThemeSliderMath.slotIndex(centerX, inset, slot))
        }
    }

    @Test
    fun thumbCenterStaysInsideTheSlotsWhileClamped() {
        // Even at the clamp extremes the thumb's CENTER remains over
        // slot 0 / slot 2 (never over the track's 3dp inset margin).
        val minCenter = ThemeSliderMath.thumbOffsetPx(0f, inset, slot, thumb) + thumb / 2f
        assertEquals(30f, minCenter)
        assertEquals(0, ThemeSliderMath.slotIndex(minCenter, inset, slot))
        val maxCenter = ThemeSliderMath.thumbOffsetPx(168f, inset, slot, thumb) + thumb / 2f
        assertEquals(138f, maxCenter)
        assertEquals(2, ThemeSliderMath.slotIndex(maxCenter, inset, slot))
    }

    // ---- shouldCommit: exactly once, on release, only if changed ----

    @Test
    fun shouldCommit_isFalseWhenTheIndexDidNotChange() {
        assertFalse(ThemeSliderMath.shouldCommit(0, 0))
        assertFalse(ThemeSliderMath.shouldCommit(1, 1))
        assertFalse(ThemeSliderMath.shouldCommit(2, 2))
    }

    @Test
    fun shouldCommit_isTrueOnlyWhenTheIndexChanged() {
        assertTrue(ThemeSliderMath.shouldCommit(0, 1))
        assertTrue(ThemeSliderMath.shouldCommit(1, 0))
        assertTrue(ThemeSliderMath.shouldCommit(1, 2))
        assertTrue(ThemeSliderMath.shouldCommit(2, 0))
    }

    // ---- Scaled density (2x): nothing may assume 1dp = 1px ----

    @Test
    fun mathIsDensityAgnostic_atTwoXDensity() {
        // 2x density: inset 6px, slot 108px, thumb 108px (half = 54px).
        val i2 = 6f
        val s2 = 108f
        val t2 = 108f
        // Slot boundaries double.
        assertEquals(0, ThemeSliderMath.slotIndex(i2 + s2 - 0.1f, i2, s2))
        assertEquals(1, ThemeSliderMath.slotIndex(i2 + s2, i2, s2))
        assertEquals(2, ThemeSliderMath.slotIndex(i2 + 2 * s2, i2, s2))
        // Clamps double: min 6px, max 6 + 216 = 222px.
        assertEquals(6f, ThemeSliderMath.thumbOffsetPx(0f, i2, s2, t2))
        assertEquals(222f, ThemeSliderMath.thumbOffsetPx(336f, i2, s2, t2))
        // Resting offsets double.
        assertEquals(6f, ThemeSliderMath.restingOffsetPx(0, i2, s2))
        assertEquals(114f, ThemeSliderMath.restingOffsetPx(1, i2, s2))
        assertEquals(222f, ThemeSliderMath.restingOffsetPx(2, i2, s2))
    }

    @Test
    fun tapInvariantHoldsAtTwoXDensity() {
        val i2 = 6f
        val s2 = 108f
        val t2 = 108f
        for (index in 0..2) {
            val centerX = i2 + index * s2 + t2 / 2f
            assertEquals(
                ThemeSliderMath.restingOffsetPx(index, i2, s2),
                ThemeSliderMath.thumbOffsetPx(centerX, i2, s2, t2),
            )
        }
    }
}
