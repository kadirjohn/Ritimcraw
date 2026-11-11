package com.ritim.ritimcraw.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RotationState (owner request 2026-11-11 section 5): the one rotation
 * source of truth. Every composition rule the four consumers rely on —
 * preview display, MP4 pixels, DNG Orientation tag, photo pixels — is a
 * pure decision tested here.
 */
class RotationStateTest {

    // ---- normalize ----

    @Test
    fun `container 90 with manual 0 is 90`() {
        // The untouched vertical clip: the container sensorOrientation is
        // the whole story, the display adds nothing.
        assertEquals(90, RotationState.effectiveDeg(90, 0))
    }

    @Test
    fun `negative degrees normalize into range`() {
        assertEquals(270, RotationState.normalize(-90))
        assertEquals(0, RotationState.normalize(-360))
        assertEquals(0, RotationState.normalize(360))
        assertEquals(90, RotationState.normalize(450))
    }

    // ---- effective composition (manual wins over container) ----

    @Test
    fun `manual override adds onto the container value`() {
        // A vertical clip (container 90) rotated once CCW by hand: the
        // user's delta composes with — not replaces — the container.
        assertEquals(180, RotationState.effectiveDeg(90, 90))
        assertEquals(0, RotationState.effectiveDeg(90, -90))
        assertEquals(270, RotationState.effectiveDeg(90, 180))
    }

    @Test
    fun `manual delta accumulates but stays normalized`() {
        // Four quarter turns return to the default: the persisted value
        // is 0 again, so project.json drops back to the omitted field.
        var delta = 0
        repeat(4) { delta = RotationState.applyManual(delta, 90) }
        assertEquals(0, delta)
        assertEquals(270, RotationState.applyManual(0, -90))
        assertEquals(90, RotationState.applyManual(180, -90))
    }

    // ---- dimension swap (preview box aspect + MP4 writer dims) ----

    @Test
    fun `quarter turns swap dimensions and half turns do not`() {
        assertTrue(RotationState.swapsDimensions(90))
        assertTrue(RotationState.swapsDimensions(270))
        assertFalse(RotationState.swapsDimensions(0))
        assertFalse(RotationState.swapsDimensions(180))
        // Negative-friendly: a -90 manual delta also swaps.
        assertTrue(RotationState.swapsDimensions(-90))
    }

    @Test
    fun `rotated dims swap on quarter turns only`() {
        assertEquals(RotationState.Dims(1080, 1920), RotationState.rotatedDims(1920, 1080, 90))
        assertEquals(RotationState.Dims(1920, 1080), RotationState.rotatedDims(1920, 1080, 180))
        assertEquals(RotationState.Dims(1080, 1920), RotationState.rotatedDims(1920, 1080, 270))
        assertEquals(RotationState.Dims(1920, 1080), RotationState.rotatedDims(1920, 1080, 0))
        // Unknown dimensions pass through (the ratio chain's unknown rule).
        assertEquals(RotationState.Dims(0, 0), RotationState.rotatedDims(0, 0, 90))
    }

    @Test
    fun `display cover scale is the long-short side ratio on quarter turns`() {
        // §5 preview display: a quarter-turned frame fills its
        // swapped-ratio box exactly when scaled by the ratio between the
        // content's long and short sides; half turns and 0 need no scale.
        assertEquals(16f / 9f, RotationState.quarterTurnDisplayScale(16f / 9f, 90), 0f)
        assertEquals(16f / 9f, RotationState.quarterTurnDisplayScale(9f / 16f, 90), 0f)
        assertEquals(1f, RotationState.quarterTurnDisplayScale(16f / 9f, 0), 0f)
        assertEquals(1f, RotationState.quarterTurnDisplayScale(16f / 9f, 180), 0f)
        // Square content never needs a scale, whatever the delta.
        assertEquals(1f, RotationState.quarterTurnDisplayScale(1f, 90), 0f)
        // Negative deltas are quarter turns too.
        assertEquals(16f / 9f, RotationState.quarterTurnDisplayScale(16f / 9f, -90), 0f)
    }

    // ---- DNG Orientation tag (JVM mirror of DngWriter tiffOrientation) ----

    @Test
    fun `dng orientation tag mapping matches the tiff spec`() {
        assertEquals(1, RotationState.dngOrientationTag(0))
        assertEquals(6, RotationState.dngOrientationTag(90))
        assertEquals(3, RotationState.dngOrientationTag(180))
        assertEquals(8, RotationState.dngOrientationTag(270))
        // Non-quantized values fall to the identity, same as the writer.
        assertEquals(1, RotationState.dngOrientationTag(45))
        // Effective composition feeds the tag directly.
        assertEquals(3, RotationState.dngOrientationTag(RotationState.effectiveDeg(90, 90)))
    }

    // ---- RGB888 rotation (the MP4 export's upright pixels) ----

    @Test
    fun `rgb 90 rotation swaps axes and moves pixels correctly`() {
        // 2x2 image with distinct pixel colors:
        //   (0,0)=R  (1,0)=G
        //   (0,1)=B  (1,1)=W
        // A 90-degree CW turn maps (x, y) -> out row x, column (h-1-y):
        // the last column becomes the first row.
        val rgb = byteArrayOf(
            0x10, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x00, 0x00, 0x10, 0x10, 0x10, 0x10,
        )
        val rotated = RotationState.rotateRgb888(rgb, 2, 2, 90)
        assertEquals(2, rotated.width)
        assertEquals(2, rotated.height)
        // Expected 90-CW layout:
        //   B R
        //   W G
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x10, 0x10, 0x00, 0x00,
                0x10, 0x10, 0x10, 0x00, 0x10, 0x00,
            ),
            rotated.rgb,
        )
    }

    @Test
    fun `rgb 180 rotation keeps dims and reverses order`() {
        val rgb = byteArrayOf(
            0x10, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x00, 0x00, 0x10, 0x10, 0x10, 0x10,
        )
        val rotated = RotationState.rotateRgb888(rgb, 2, 2, 180)
        assertEquals(2, rotated.width)
        assertEquals(2, rotated.height)
        assertArrayEquals(
            byteArrayOf(
                0x10, 0x10, 0x10, 0x00, 0x00, 0x10,
                0x00, 0x10, 0x00, 0x10, 0x00, 0x00,
            ),
            rotated.rgb,
        )
    }

    @Test
    fun `rgb non-quantized degrees are identity`() {
        val rgb = ByteArray(6) { it.toByte() }
        val rotated = RotationState.rotateRgb888(rgb, 2, 1, 45)
        assertEquals(2, rotated.width)
        assertEquals(1, rotated.height)
        // Identity returns the SAME buffer (no copy when nothing moves).
        assertTrue(rotated.rgb === rgb)
    }

    @Test
    fun `four quarter turns restore the original pixels`() {
        val rgb = ByteArray(12) { (it * 17).toByte() }
        var cur = RotationState.rotateRgb888(rgb, 2, 2, 90)
        cur = RotationState.rotateRgb888(cur.rgb, cur.width, cur.height, 90)
        cur = RotationState.rotateRgb888(cur.rgb, cur.width, cur.height, 90)
        cur = RotationState.rotateRgb888(cur.rgb, cur.width, cur.height, 90)
        assertArrayEquals(rgb, cur.rgb)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rgb rotation rejects a short buffer`() {
        RotationState.rotateRgb888(ByteArray(5), 2, 1, 90)
    }

    // ---- encoded-source rule ----

    @Test
    fun `encoded clips compose the same way from their rotation hint`() {
        // EncodedVideoFormat.rotationDeg (KEY_ROTATION) enters the same
        // function as MCRAW's sensorOrientation: one composition rule for
        // both kinds, the mission's single-source requirement.
        assertEquals(180, RotationState.effectiveDeg(90, 90))
        assertEquals(270, RotationState.effectiveDeg(270, 0))
        assertEquals(0, RotationState.effectiveDeg(270, 90))
    }
}
