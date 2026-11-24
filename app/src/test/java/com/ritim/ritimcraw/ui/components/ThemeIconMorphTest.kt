package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeIconMorphTest {
    private val eps = 1e-4f

    @Test fun restLight_isPlainSun() {
        val s = ThemeIconMorph.stateAt(0f)
        assertEquals(1f, s.sunAlpha, eps)
        assertEquals(1f, s.rayExtent, eps)
        assertEquals(1f, s.sunDiscScale, eps)
        assertEquals(0f, s.aAlpha, eps)
        assertEquals(0f, s.moonAlpha, eps)
    }

    @Test fun restSystem_isPlainA() {
        val s = ThemeIconMorph.stateAt(1f)
        assertEquals(1f, s.aAlpha, eps)
        assertEquals(1f, s.aScale, eps)
        assertEquals(0f, s.aRotationDeg, eps)
        assertEquals(0f, s.sunAlpha, eps)
        assertEquals(0f, s.moonAlpha, eps)
    }

    @Test fun restDark_isPlainMoon() {
        val s = ThemeIconMorph.stateAt(2f)
        assertEquals(1f, s.moonAlpha, eps)
        assertEquals(1f, s.moonScale, eps)
        assertEquals(0f, s.moonRotationDeg, eps)
        assertEquals(0f, s.aAlpha, eps)
        assertEquals(0f, s.sunAlpha, eps)
    }

    @Test fun midSegments_blend() {
        val a = ThemeIconMorph.stateAt(0.5f)
        assertTrue(a.rayExtent < 1f)
        assertTrue(a.sunAlpha < 1f && a.aAlpha < 1f)
        val b = ThemeIconMorph.stateAt(1.5f)
        assertTrue(b.moonAlpha < 1f && b.aAlpha < 1f)
    }

    @Test fun raysMonotonicallyRetract() {
        var prev = 2f
        for (i in 0..20) {
            val e = ThemeIconMorph.stateAt(i / 20f).rayExtent
            assertTrue(e <= prev + eps)
            prev = e
        }
    }

    @Test fun clampsOutOfRange() {
        assertEquals(ThemeIconMorph.stateAt(0f), ThemeIconMorph.stateAt(-3f))
        assertEquals(ThemeIconMorph.stateAt(2f), ThemeIconMorph.stateAt(9f))
    }

    @Test fun reversible_pureFunctionOfPosition() {
        assertEquals(ThemeIconMorph.stateAt(0.37f), ThemeIconMorph.stateAt(0.37f))
    }

    @Test fun positionFromThumb_mapsAndClamps() {
        assertEquals(0f, ThemeIconMorph.positionFromThumb(3f, 3f, 54f), eps)
        assertEquals(1f, ThemeIconMorph.positionFromThumb(57f, 3f, 54f), eps)
        assertEquals(2f, ThemeIconMorph.positionFromThumb(111f, 3f, 54f), eps)
        assertEquals(0.5f, ThemeIconMorph.positionFromThumb(30f, 3f, 54f), eps)
        assertEquals(0f, ThemeIconMorph.positionFromThumb(-10f, 3f, 54f), eps)
        assertEquals(2f, ThemeIconMorph.positionFromThumb(500f, 3f, 54f), eps)
    }
}
