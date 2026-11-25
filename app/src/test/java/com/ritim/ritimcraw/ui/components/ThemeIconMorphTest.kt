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

    // ---- track slot icons ----

    private fun assertRest(s: ThemeSlotIconState) {
        assertEquals(1f, s.alpha, eps)
        assertEquals(1f, s.scale, eps)
        assertEquals(0f, s.translateXFrac, eps)
        assertEquals(0f, s.translateYFrac, eps)
        assertEquals(0f, s.rotationDeg, eps)
        assertEquals(1f, s.stretchY, eps)
        assertEquals(0f, s.brighten, eps)
    }

    @Test fun slotInactiveRest_isTodaysPlainIcon() {
        for (slot in 0..2) for (pos in 0..2) {
            if (pos != slot) assertRest(ThemeIconMorph.slotState(slot, pos.toFloat()))
        }
    }

    @Test fun slotActiveRest_isHiddenUnderThumb() {
        for (slot in 0..2) {
            val s = ThemeIconMorph.slotState(slot, slot.toFloat())
            assertEquals(0f, s.alpha, eps)
            assertEquals(1f, s.scale, eps)
            assertEquals(0f, s.rotationDeg, eps)
        }
    }

    @Test fun slotApproachRamps_areMonotonic() {
        for (slot in 0..2) {
            val dir = if (slot == 2) -1f else 1f
            var prev = ThemeIconMorph.slotState(slot, slot + dir)
            for (i in 1..50) {
                val d = 1f - (1f - ThemeIconMorph.NEAR) * i / 50f
                val s = ThemeIconMorph.slotState(slot, slot + dir * d)
                assertTrue(s.brighten >= prev.brighten - eps)
                assertTrue(s.scale >= prev.scale - eps)
                assertTrue(s.translateYFrac <= prev.translateYFrac + eps)
                assertTrue(s.stretchY >= prev.stretchY - eps)
                prev = s
            }
            assertEquals(1f, prev.brighten, eps)
        }
    }

    @Test fun slotState_isSymmetricAroundSlot() {
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.8f)) {
            val a = ThemeIconMorph.slotState(1, 1f + x)
            val b = ThemeIconMorph.slotState(1, 1f - x)
            assertEquals(a.alpha, b.alpha, eps)
            assertEquals(a.scale, b.scale, eps)
            assertEquals(a.translateYFrac, b.translateYFrac, eps)
            assertEquals(a.stretchY, b.stretchY, eps)
            assertEquals(a.brighten, b.brighten, eps)
            assertEquals(a.translateXFrac, -b.translateXFrac, eps)
            assertEquals(a.rotationDeg, -b.rotationDeg, eps)
        }
    }

    @Test fun slotState_clamps() {
        for (slot in 0..2) {
            assertEquals(ThemeIconMorph.slotState(slot, 0f), ThemeIconMorph.slotState(slot, -4f))
            assertEquals(ThemeIconMorph.slotState(slot, 2f), ThemeIconMorph.slotState(slot, 7f))
        }
        assertEquals(ThemeIconMorph.slotState(0, 0.5f), ThemeIconMorph.slotState(-3, 0.5f))
    }

    @Test fun slotHandOff_noDoubleImage() {
        for (slot in 0..2) {
            for (d in listOf(0f, 0.05f, ThemeIconMorph.HANDOFF)) {
                for (sign in listOf(-1f, 1f)) {
                    val p = (slot + sign * d).coerceIn(0f, 2f)
                    assertEquals(0f, ThemeIconMorph.slotState(slot, p).alpha, eps)
                }
            }
        }
        // The thumb glyph is (nearly) fully in by the time the slot icon is gone.
        assertTrue(ThemeIconMorph.stateAt(0.1f).sunAlpha > 0.9f)
        assertTrue(ThemeIconMorph.stateAt(0.9f).aAlpha > 0.9f)
        assertTrue(ThemeIconMorph.stateAt(1.1f).aAlpha > 0.9f)
        assertTrue(ThemeIconMorph.stateAt(1.9f).moonAlpha > 0.9f)
    }

    @Test fun slotState_isPure() {
        assertEquals(ThemeIconMorph.slotState(2, 1.37f), ThemeIconMorph.slotState(2, 1.37f))
    }

    @Test fun textGlyph_restNearAndHandoff() {
        val rest = ThemeIconMorph.textGlyphState(1f)
        assertEquals(1f, rest.alpha, eps)
        assertEquals(1f, rest.scale, eps)
        assertEquals(0f, rest.brighten, eps)
        assertEquals(0f, ThemeIconMorph.textGlyphState(0f).alpha, eps)
        val near = ThemeIconMorph.textGlyphState(ThemeIconMorph.NEAR)
        assertEquals(1f, near.alpha, eps)
        assertEquals(1f, near.brighten, eps)
        assertEquals(0f, near.rotationDeg, eps)
        assertEquals(
            ThemeIconMorph.textGlyphState(-0.5f).scale,
            ThemeIconMorph.textGlyphState(0.5f).scale, eps,
        )
    }
}
