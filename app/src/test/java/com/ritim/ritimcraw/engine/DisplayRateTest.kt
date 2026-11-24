package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRateTest {
    private val rates = listOf(60f, 90f, 120f, 144f)

    @Test
    fun an_exact_multiple_wins_and_the_highest_one_is_taken() {
        assertEquals(120f, DisplayRate.pick(listOf(60f, 120f), 24.0)!!, 0f) // 24p -> 120 (5x), 60 is 2.5x
        assertEquals(120f, DisplayRate.pick(listOf(60f, 120f), 30.0)!!, 0f) // 30p -> 60 or 120: the higher
        assertEquals(60f, DisplayRate.pick(listOf(60f), 30.0)!!, 0f)
        assertEquals(144f, DisplayRate.pick(rates, 24.0)!!, 0f) // 120 (5x) and 144 (6x) are both exact: the higher
        assertEquals(120f, DisplayRate.pick(rates, 60.0)!!, 0f)
    }

    @Test
    fun ntsc_rates_count_as_multiples_within_one_percent() {
        // 29.97 on 60 Hz: 60/29.97 = 2.002, an exact multiple for the pick
        assertEquals(60f, DisplayRate.pick(listOf(60f), 29.97)!!, 0f)
    }

    @Test
    fun without_a_multiple_the_most_even_rate_is_chosen() {
        // 25p: none of 60/90/120/144 is a multiple (2.4, 3.6, 4.8, 5.76)
        assertEquals(144f, DisplayRate.pick(rates, 25.0)!!, 0f)
        assertTrue(DisplayRate.unevenness(144f, 25.0) < DisplayRate.unevenness(120f, 25.0))
        assertTrue(DisplayRate.unevenness(120f, 25.0) < DisplayRate.unevenness(90f, 25.0))
        assertTrue(DisplayRate.unevenness(90f, 25.0) < DisplayRate.unevenness(60f, 25.0))
    }

    @Test
    fun a_multiple_has_zero_unevenness_and_a_too_slow_rate_costs_a_whole_frame() {
        assertEquals(0.0, DisplayRate.unevenness(120f, 24.0), 1e-9)
        assertEquals(1.0 / 30.0, DisplayRate.unevenness(24f, 30.0), 1e-12)
    }

    @Test
    fun nothing_to_choose_gives_null() {
        assertNull(DisplayRate.pick(emptyList(), 30.0))
        assertNull(DisplayRate.pick(rates, 0.5))
    }

    @Test
    fun only_modes_at_the_current_resolution_are_candidates() {
        val modes = listOf(
            DisplayRate.Mode(1, 1080, 2400, 60f),
            DisplayRate.Mode(2, 1080, 2400, 120f),
            DisplayRate.Mode(3, 1440, 3200, 144f), // another resolution: never picked
        )
        assertEquals(2, DisplayRate.modeIdFor(modes, 1080, 2400, 30.0))
        assertEquals(2, DisplayRate.modeIdFor(modes, 1080, 2400, 24.0))
        assertEquals(0, DisplayRate.modeIdFor(modes, 720, 1600, 30.0)) // no mode at that size: the system's
    }

    @Test
    fun the_request_is_off_by_default_and_the_flags_only_matter_in_the_way_they_say() {
        assertEquals(false, DisplayRateApply.ENABLED_BY_DEFAULT)
        assertEquals(false, DisplayRate.shouldRequest(false, false, false))
        assertEquals(true, DisplayRate.shouldRequest(false, true, false))   // debug enable flag
        assertEquals(false, DisplayRate.shouldRequest(false, true, true))   // disable wins
        assertEquals(true, DisplayRate.shouldRequest(true, false, false))   // a future default-on
        assertEquals(false, DisplayRate.shouldRequest(true, false, true))
    }
}
