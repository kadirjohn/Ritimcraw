package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExposureKeyframes tests (AE-01 / AE-05 / AE-09, 2026-11-23): neutral
 * empty lane, hold before/after, instant steps, smoothstep ramps with
 * clamping, move/edit/remove keep order and ids, JSON round trip with
 * row tolerance, and the totalEV composition with the correction bound.
 */
class ExposureKeyframesTest {

    private val instant = ExposureKeyframe.Transition.Instant
    private fun smooth(us: Long) = ExposureKeyframe.Transition.Smooth(us)

    private val lane = ExposureKeyframes()
        .add(ExposureKeyframe("a", 1_000_000, 0f))
        .add(ExposureKeyframe("b", 3_000_000, 1f, instant))
        .add(ExposureKeyframe("c", 5_000_000, -1f, smooth(1_000_000)))

    @Test
    fun empty_lane_is_neutral() {
        assertEquals(0f, ExposureKeyframes().evAt(123), 0f)
        assertTrue(ExposureKeyframes().isEmpty)
    }

    @Test
    fun values_hold_before_first_and_after_last() {
        assertEquals(0f, lane.evAt(0), 0f)
        assertEquals(-1f, lane.evAt(9_000_000), 0f)
    }

    @Test
    fun instant_steps_exactly_at_the_key() {
        assertEquals(0f, lane.evAt(2_999_999), 0f)
        assertEquals(1f, lane.evAt(3_000_000), 0f)
    }

    @Test
    fun smooth_ramps_with_smoothstep_ending_at_the_key() {
        // c ramps 1 -> -1 over [4s, 5s]; midpoint = 0, ends exact.
        assertEquals(1f, lane.evAt(3_999_999), 1e-4f)
        assertEquals(0f, lane.evAt(4_500_000), 1e-5f)
        assertEquals(-1f, lane.evAt(5_000_000), 0f)
        // smoothstep(0.25) = 0.15625 -> 1 - 2*0.15625
        assertEquals(0.6875f, lane.evAt(4_250_000), 1e-5f)
    }

    @Test
    fun ramp_longer_than_the_gap_clamps_to_the_gap() {
        val k = ExposureKeyframes()
            .add(ExposureKeyframe("a", 0, 0f))
            .add(ExposureKeyframe("b", 1_000_000, 2f, smooth(10_000_000)))
        assertEquals(1f, k.evAt(500_000), 1e-5f) // ramp spans the whole gap
    }

    @Test
    fun move_edit_remove_keep_order_and_ids() {
        val moved = lane.move("a", 6_000_000)
        assertEquals(listOf("b", "c", "a"), moved.keys.map { it.id })
        assertEquals(listOf("b", "a", "c"), lane.setEv("a", 2f).move("a", 4_000_000).keys.map { it.id })
        assertEquals(2f, lane.setEv("a", 2f).keys.first().ev, 0f)
        assertEquals(listOf("a", "c"), lane.remove("b").keys.map { it.id })
        assertSame(lane, lane.move("nope", 1))
        val inst = lane.setTransition("c", instant)
        assertEquals(1f, inst.evAt(4_999_999), 0f)
        var threw = false
        try {
            lane.add(ExposureKeyframe("a", 0, 0f))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun json_round_trip_and_row_tolerance() {
        val json = ExposureKeyframes.render(lane)
        assertEquals(
            "[{\"id\":\"a\",\"t\":1000000,\"ev\":0.0},{\"id\":\"b\",\"t\":3000000,\"ev\":1.0}," +
                "{\"id\":\"c\",\"t\":5000000,\"ev\":-1.0,\"smoothUs\":1000000}]",
            json,
        )
        assertEquals(lane, ExposureKeyframes.parse(json))
        val messy = "[{\"id\":\"x\",\"t\":5,\"ev\":0.5},{\"t\":1,\"ev\":1},{\"id\":\"x\",\"t\":9,\"ev\":2}," +
            "{\"id\":\"y\",\"t\":2,\"ev\":1,\"smoothUs\":-3},{\"id\":\"z\",\"t\":0,\"ev\":-0.5}]"
        val parsed = ExposureKeyframes.parse(messy)
        assertEquals(listOf("z", "x"), parsed.keys.map { it.id })
        assertEquals(ExposureKeyframes(), ExposureKeyframes.parse("[]"))
    }

    @Test
    fun total_ev_composes_and_bounds_only_the_correction() {
        assertEquals(1.5f, ExposureComposition.totalEv(1f, 0.25f, 0.25f), 0f)
        // AE-05 max EV bounds auto+keyframe, never the user's base slider.
        assertEquals(3f + 1f, ExposureComposition.totalEv(3f, 2f, 1f, maxAbsEv = 1f), 0f)
        assertEquals(-2f - 0.5f, ExposureComposition.totalEv(-2f, -4f, 0f, maxAbsEv = 0.5f), 0f)
        assertEquals(0.7f, ExposureComposition.totalEv(0.7f), 0f)
    }
}
