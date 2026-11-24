package com.ritim.ritimcraw.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SlotColorJourneyTest {
    private val inactive = Color(0xFF49454F)
    private val accent = Color(0xFF6750A4)
    private val arrival = Color(0xFFFFFFFF)

    private fun near(a: Color, b: Color, eps: Float = 0.01f) {
        assertTrue(
            "$a vs $b",
            abs(a.red - b.red) < eps && abs(a.green - b.green) < eps &&
                abs(a.blue - b.blue) < eps && abs(a.alpha - b.alpha) < eps,
        )
    }

    private fun at(d: Float) = SlotColorJourney.colorAt(d, inactive, accent, arrival)

    @Test fun restEqualsInactive() {
        assertEquals(inactive, at(1f))
        assertEquals(inactive, at(5f))
    }

    @Test fun arrivalEqualsThumbGlyph() {
        assertEquals(arrival, at(ThemeIconMorph.NEAR))
        assertEquals(arrival, at(0f))
    }

    @Test fun passesThroughAccent() {
        val d = (0..1000).map { it / 1000f }
            .minBy { abs(SlotColorJourney.progress(it) - SlotColorJourney.ACCENT_AT) }
        near(at(d), accent, 0.02f)
    }

    @Test fun continuousJourney() {
        var prev = at(1f)
        var d = 1f
        while (d >= 0f) {
            val c = at(d)
            assertTrue(
                abs(c.red - prev.red) < 0.03f && abs(c.green - prev.green) < 0.03f &&
                    abs(c.blue - prev.blue) < 0.03f,
            )
            prev = c
            d -= 0.002f
        }
    }

    @Test fun progressMonotonic() {
        var prev = -1f
        var d = 1.2f
        while (d >= 0f) {
            val p = SlotColorJourney.progress(d)
            assertTrue(p >= prev - 1e-6f)
            prev = p
            d -= 0.01f
        }
    }

    @Test fun symmetricAndClamped() {
        for (d in listOf(0.2f, 0.5f, 0.8f)) assertEquals(at(d), at(-d))
        assertEquals(at(1f), at(3f))
        assertEquals(inactive, at(Float.NaN))
    }

    @Test fun lerpEndpointsAndClamp() {
        val a = Color(0xFF123456)
        val b = Color(0xFFABCDEF)
        assertEquals(a, Oklab.lerp(a, b, 0f))
        assertEquals(b, Oklab.lerp(a, b, 1f))
        assertEquals(a, Oklab.lerp(a, b, -3f))
        assertEquals(b, Oklab.lerp(a, b, 9f))
    }

    @Test fun roundTrip() {
        for (c in listOf(Color(0xFF123456), Color(0xFFABCDEF), Color(0xFFFF0000), Color(0xFF808080))) {
            near(Oklab.toColor(Oklab.fromColor(c)), c, 0.002f)
        }
    }

    @Test fun blackWhite() {
        val black = Oklab.fromColor(Color.Black)
        val white = Oklab.fromColor(Color.White)
        assertEquals(0f, black[0], 0.001f)
        assertEquals(1f, white[0], 0.001f)
        assertEquals(0f, white[1], 0.001f)
        assertEquals(0f, white[2], 0.001f)
        val mid = Oklab.lerp(Color.Black, Color.White, 0.5f)
        assertEquals(mid.red, mid.green, 0.002f)
        assertEquals(mid.green, mid.blue, 0.002f)
        assertTrue(mid.red > 0.3f && mid.red < 0.5f)
    }

    @Test fun equalColoursNoNaN() {
        val c = Color(0xFF336699)
        for (t in listOf(0.1f, 0.5f, 0.9f)) {
            val r = Oklab.lerp(c, c, t)
            assertEquals(c, r)
            assertFalse(r.red.isNaN())
        }
        assertFalse(Oklab.lerp(Color.Black, Color.Black, 0.5f).red.isNaN())
    }
}
