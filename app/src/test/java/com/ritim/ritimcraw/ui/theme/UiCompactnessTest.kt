package com.ritim.ritimcraw.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class UiCompactnessTest {

    @Test
    fun owners_560_density_phone_renders_a_400dp_layout() {
        // 1080px / 3.5 = 308dp shortest side -> 0.77 scale -> 400dp.
        val scale = UiCompactness.scaleFor(308)
        assertEquals(0.77f, scale, 0.001f)
        assertEquals(400f, 308 / scale, 0.5f)
    }

    @Test
    fun normal_phones_and_tablets_are_unchanged() {
        assertEquals(1f, UiCompactness.scaleFor(400), 0f)
        assertEquals(1f, UiCompactness.scaleFor(411), 0f)
        assertEquals(1f, UiCompactness.scaleFor(800), 0f)
    }

    @Test
    fun tiny_windows_clamp_at_the_legibility_floor() {
        assertEquals(UiCompactness.MIN_SCALE, UiCompactness.scaleFor(200), 0f)
    }

    @Test
    fun unknown_size_is_identity() {
        assertEquals(1f, UiCompactness.scaleFor(0), 0f)
    }
}
