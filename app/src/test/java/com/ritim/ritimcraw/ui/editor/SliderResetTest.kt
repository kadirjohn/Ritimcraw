package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slider double-tap reset: pure reset logic, history steps, thumb hit test. */
class SliderResetTest {

    private val dirty = GradeK(exposureEv = 1.5f, whitePoint = 30f, contrast = 20f, sharpness = 40f)

    @Test
    fun reset_single_touches_only_that_axis() {
        val r = SliderSteps.resetToDefault(SliderSteps.Axis.WHITE_POINT, dirty)
        assertEquals(GradeK().whitePoint, r.whitePoint, 0f)
        assertEquals(1.5f, r.exposureEv, 0f)
        assertEquals(20f, r.contrast, 0f)
    }

    @Test
    fun reset_all_defaults_every_slider_axis() {
        val r = SliderSteps.resetAll(dirty)
        assertEquals(GradeK(), r)
    }

    @Test
    fun reset_all_keeps_non_slider_fields() {
        val g = dirty.copy(asShot = !GradeK().asShot)
        assertEquals(g.asShot, SliderSteps.resetAll(g).asShot)
    }

    @Test
    fun reset_is_one_undo_step_and_redo_works() {
        val h = GradeHistory(GradeK())
        h.record(dirty, GradeHistory.SLIDER_GESTURE)
        h.settle()
        val reset = SliderSteps.resetAll(dirty)
        h.record(reset)
        assertEquals(dirty, h.undo())
        assertEquals(reset, h.redo())
    }

    @Test
    fun reset_not_coalesced_into_open_slider_gesture() {
        val h = GradeHistory(GradeK())
        h.record(dirty, GradeHistory.SLIDER_GESTURE) // drag still open
        h.record(GradeK())                            // reset, null cause
        assertEquals(dirty, h.undo())
        assertEquals(GradeK(), h.undo())
        assertNull(h.undo())
    }

    @Test
    fun double_tap_reset_collapses_the_tap_jumps_so_undo_is_exact() {
        val h = GradeHistory(dirty)
        h.markGestureBase()
        // tap 1 and tap 2 each nudge the slider (tap-to-position)
        h.record(dirty.copy(whitePoint = 31f), GradeHistory.SLIDER_GESTURE)
        h.settle()
        h.record(dirty.copy(whitePoint = 32f), GradeHistory.SLIDER_GESTURE)
        h.settle()
        val reset = SliderSteps.resetToDefault(SliderSteps.Axis.WHITE_POINT, dirty.copy(whitePoint = 32f))
        h.resetScope { h.record(reset) }
        assertEquals(dirty, h.undo())
        assertFalse(h.canUndo)
        assertEquals(reset, h.redo())
    }

    @Test
    fun reset_all_undo_restores_exact_pre_reset_values() {
        val h = GradeHistory(GradeK())
        h.record(dirty, GradeHistory.SLIDER_GESTURE)
        h.settle()
        h.markGestureBase()
        h.record(dirty.copy(exposureEv = 1.6f), GradeHistory.SLIDER_GESTURE)
        h.settle()
        h.resetScope { h.record(SliderSteps.resetAll(dirty.copy(exposureEv = 1.6f))) }
        assertEquals(dirty, h.undo())
        assertEquals(GradeK(), h.undo())
        assertNull(h.undo())
    }

    @Test
    fun collapse_never_swallows_an_earlier_step() {
        val h = GradeHistory(GradeK())
        h.record(dirty, GradeHistory.SLIDER_GESTURE)
        h.settle()
        h.markGestureBase()
        h.resetScope { h.record(GradeK()) }
        assertEquals(dirty, h.undo())
        assertEquals(GradeK(), h.undo())
    }

    @Test
    fun stale_mark_is_ignored_when_stack_changed_by_a_discrete_edit() {
        val h = GradeHistory(GradeK())
        h.markGestureBase()
        val toggled = GradeK(exposureEv = 2f)
        h.record(toggled) // discrete, null cause
        h.resetScope { h.record(dirty) }
        assertEquals(toggled, h.undo())
    }

    @Test
    fun thumb_hit_geometry() {
        fun hit(x: Float, y: Float, frac: Float, rtl: Boolean = false) =
            CompactSliderDefaults.thumbHit(x, y, 1000f, 144f, frac, rtl, 24f, 72f, 72f, 36f)
        // fraction 0.5 -> centre x 500, centre y 72
        assertTrue(hit(500f, 72f, 0.5f))
        assertTrue(hit(560f, 90f, 0.5f))
        assertFalse(hit(700f, 72f, 0.5f))
        assertFalse(hit(500f, 10f, 0.5f)) // label band
        assertTrue(hit(24f, 72f, 0f))
        assertTrue(hit(976f, 72f, 0f, rtl = true))
    }

    @Test
    fun double_tap_after_an_open_tap_entry_undoes_to_the_exact_previous_value() {
        // Device bug: a tap-to-position leaves its entry OPEN (no drag Stop),
        // so the double-tap's nudge replaced it and undo skipped a step.
        val a = GradeK()
        val d = GradeK(exposureEv = 2.5f)
        val nudged = GradeK(exposureEv = -4.4f)
        val h = GradeHistory(a)
        h.record(d, GradeHistory.SLIDER_GESTURE) // open
        h.markGestureBase()
        h.record(nudged, GradeHistory.SLIDER_GESTURE)
        h.settle()
        h.resetScope { h.record(a) }
        assertEquals(d, h.undo())
        assertEquals(a, h.undo())
        assertNull(h.undo())
    }

    @Test
    fun label_zone_never_overlaps_track_or_thumb_in_overlay_geometry() {
        assertTrue(CompactSliderDefaults.labelZone(5f, 60f))
        assertFalse(CompactSliderDefaults.labelZone(60f, 60f))
        assertFalse(CompactSliderDefaults.labelZone(5f, 0f)) // unmeasured
        // Overlay only while the label line is <= 22dp: the strip ends above the 22dp track top.
        val trackTopDp = (CompactSliderDefaults.ROW_HEIGHT.value - CompactSliderDefaults.TRACK_HEIGHT.value) / 2f
        assertTrue(CompactSliderDefaults.OVERLAY_LABEL_LINE_HEIGHT_MAX_SP <= trackTopDp)
        // The strip stays inside the slider's 48dp touch slot (floor kept).
        assertTrue(CompactSliderDefaults.OVERLAY_LABEL_LINE_HEIGHT_MAX_SP < CompactSliderDefaults.ROW_HEIGHT.value)
    }
}
