package com.ritim.ritimcraw.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRulesEncodedTest {

    @Test
    fun uhd_and_smaller_take_the_develop() {
        assertTrue(ThumbnailRules.encodedDevelopFits(3840, 2160))
        assertTrue(ThumbnailRules.encodedDevelopFits(2160, 3840))
        assertTrue(ThumbnailRules.encodedDevelopFits(1920, 1080))
        assertTrue(ThumbnailRules.encodedDevelopFits(4096, 2160))
    }

    @Test
    fun eight_k_takes_the_scaled_frame() {
        assertFalse(ThumbnailRules.encodedDevelopFits(7680, 4320))
        assertFalse(ThumbnailRules.encodedDevelopFits(5120, 2880))
    }

    @Test
    fun unknown_dimensions_try_the_develop() {
        assertTrue(ThumbnailRules.encodedDevelopFits(0, 0))
    }
}
