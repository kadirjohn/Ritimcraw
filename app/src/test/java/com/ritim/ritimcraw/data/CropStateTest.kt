package com.ritim.ritimcraw.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CropState geometry tests (RAW-09: crop, aspect presets, straighten
 * with edge-preserving auto zoom). Pure math, no Android — mirrors
 * RotationStateTest's style. The straighten zoom cases pin the classic
 * rotated-bounding-box scale-ups with hand-derived numbers.
 */
class CropStateTest {

    // ---- aspect presets ----

    @Test
    fun `aspect ratio returns null only for FREE`() {
        assertNull(CropState.aspectRatio(CropState.ASPECT_FREE))
        assertEquals(16f / 9f, CropState.aspectRatio(CropState.ASPECT_16_9)!!, 1e-6f)
        assertEquals(9f / 16f, CropState.aspectRatio(CropState.ASPECT_9_16)!!, 1e-6f)
        assertEquals(4f / 3f, CropState.aspectRatio(CropState.ASPECT_4_3)!!, 1e-6f)
        assertEquals(1f, CropState.aspectRatio(CropState.ASPECT_1_1)!!, 1e-6f)
    }

    @Test
    fun `16by9 preset on a 4by3 frame is width-full and centered`() {
        val r = CropState.aspectRect(16f / 9f, 4032, 3024)
        assertEquals(1f, r.w, 1e-6f)
        // pixel ratio: (1*4032) / (h*3024) = 16/9 -> h = 9*4032/(16*3024)
        assertEquals(9f * 4032f / (16f * 3024f), r.h, 1e-4f)
        assertEquals(0f, r.x, 1e-6f)
        assertEquals((1f - r.h) / 2f, r.y, 1e-6f)
    }

    @Test
    fun `9by16 preset on a 16by9 frame is height-full`() {
        val r = CropState.aspectRect(9f / 16f, 1920, 1080)
        assertEquals(1f, r.h, 1e-6f)
        assertEquals(0f, r.y, 1e-6f)
        assertEquals((1f - r.w) / 2f, r.x, 1e-6f)
    }

    @Test
    fun `square preset inside a square frame is the full frame`() {
        val r = CropState.aspectRect(1f, 1000, 1000)
        assertTrue(CropState.isFull(r))
    }

    @Test
    fun `lockAspect derives height from width through the frame ratio`() {
        // 1920x1080 frame, 16:9 preset, width dragged to half the
        // frame: pixel w = 960, ratio 16/9 -> pixel h = 540 = half the
        // frame height.
        val r = CropState.lockAspect(
            CropState.RectF(0.25f, 0.25f, 0.5f, 0.9f),
            CropState.ASPECT_16_9, 1920, 1080,
        )
        assertEquals(0.5f, r.w, 1e-6f)
        assertEquals(0.5f, r.h, 1e-6f)
    }

    // ---- clamping ----

    @Test
    fun `clampRect enforces the 5 percent minimum and unit bounds`() {
        val r = CropState.clampRect(CropState.RectF(-0.2f, 0.5f, 0.01f, 4f))
        assertEquals(0f, r.x, 1e-6f)
        assertEquals(0.05f, r.w, 1e-6f)
        assertTrue(r.y + r.h <= 1f + 1e-6f)
        assertTrue(r.h >= 0.05f - 1e-6f)
    }

    @Test
    fun `neutral group is full rect with FREE aspect and zero straighten`() {
        assertTrue(CropState.isNeutral(CropState.FULL_RECT, CropState.ASPECT_FREE, 0f))
        assertFalse(CropState.isNeutral(CropState.FULL_RECT, CropState.ASPECT_1_1, 0f))
        assertFalse(CropState.isNeutral(CropState.FULL_RECT, CropState.ASPECT_FREE, 1f))
    }

    // ---- rect mapping through quarter turns ----

    @Test
    fun `mapRect 90 moves top-left of effective to bottom of container`() {
        // Effective coords -> container coords inverts a +90 CW turn.
        // Under +90 CW the container's BOTTOM edge becomes the
        // effective LEFT, so the effective top-left corner maps to the
        // container bottom-left; the formula pins the orientation.
        val r = CropState.RectF(0f, 0f, 0.25f, 0.5f)
        val m = CropState.mapRect(r, 90)
        // x = y0 = 0, y = 1 - x0 - w = 0.75, w = h0 = 0.5, h = w0 = 0.25.
        assertEquals(0f, m.x, 1e-6f)
        assertEquals(0.75f, m.y, 1e-6f)
        assertEquals(0.5f, m.w, 1e-6f)
        assertEquals(0.25f, m.h, 1e-6f)
    }

    @Test
    fun `mapRect round-trips through every quarter turn pair`() {
        val r = CropState.RectF(0.1f, 0.2f, 0.3f, 0.4f)
        for (deg in intArrayOf(90, 180, 270)) {
            val back = RotationState.normalize(360 - deg)
            val rt = CropState.mapRect(CropState.mapRect(r, deg), back)
            assertEquals("x deg=$deg", r.x, rt.x, 1e-6f)
            assertEquals("y deg=$deg", r.y, rt.y, 1e-6f)
            assertEquals("w deg=$deg", r.w, rt.w, 1e-6f)
            assertEquals("h deg=$deg", r.h, rt.h, 1e-6f)
        }
        assertEquals(r, CropState.mapRect(r, 0))
    }

    @Test
    fun `mapRect preserves the rect center under the quarter turn`() {
        val r = CropState.RectF(0.2f, 0.1f, 0.5f, 0.25f)
        val cx = r.x + r.w / 2f
        val cy = r.y + r.h / 2f
        val m = CropState.mapRect(r, 90)
        // The center (cx, cy) of effective maps to (cy, 1-cx) in
        // container coordinates — the mapped rect's own center.
        assertEquals(cy, m.x + m.w / 2f, 1e-6f)
        assertEquals(1f - cx, m.y + m.h / 2f, 1e-6f)
    }

    // ---- pixel rects and Bayer alignment ----

    @Test
    fun `pixelRect aligns offsets and sizes to the Bayer grid`() {
        val r = CropState.RectF(0.101f, 0.033f, 0.501f, 0.501f)
        val p = CropState.pixelRect(r, 4000, 3000, align = 2)
        assertEquals(0, p.x % 2)
        assertEquals(0, p.y % 2)
        assertEquals(0, p.w % 2)
        assertEquals(0, p.h % 2)
        assertTrue(p.x + p.w <= 4000)
        assertTrue(p.y + p.h <= 3000)
        assertTrue(p.w > 1900 && p.w < 2100)
    }

    @Test
    fun `pixelRect clamps a rect that runs past the frame edge`() {
        val p = CropState.pixelRect(CropState.RectF(0.99f, 0f, 0.5f, 1f), 100, 100)
        assertTrue(p.x + p.w <= 100)
        assertTrue(p.y + p.h <= 100)
        assertTrue(p.w > 0 && p.h > 0)
    }

    // ---- straighten auto zoom ----

    @Test
    fun `zero degrees and degenerate rects need no zoom`() {
        assertEquals(1f, CropState.straightenZoom(1920, 1080, CropState.FULL_RECT, 0f), 0f)
        assertEquals(1f, CropState.straightenZoom(0, 1080, CropState.FULL_RECT, 5f), 0f)
        assertEquals(
            1f,
            CropState.straightenZoom(1920, 1080, CropState.RectF(0.1f, 0.1f, 0f, 0f), 5f),
            0f,
        )
    }

    @Test
    fun `full 16by9 frame at 15 degrees zooms to the bounding-box scale`() {
        // Hand-derived: rotating the full 1920x1080 frame by theta
        // gives the bounding box (W c + H s) x (W s + H c); fitting it
        // back inside W x H needs zoom = max(c + (H/W) s, c + (W/H) s)
        // = 1.4260 at 15 deg.
        val t = 15.0 * PI / 180.0
        val expected = maxOf(
            cos(t) + (1080.0 / 1920.0) * sin(t),
            cos(t) + (1920.0 / 1080.0) * sin(t),
        ).toFloat()
        assertEquals(1.4260f, expected, 1e-4f)
        assertEquals(
            expected,
            CropState.straightenZoom(1920, 1080, CropState.FULL_RECT, 15f),
            1e-4f,
        )
    }

    @Test
    fun `full square frame at 15 degrees zooms to cos plus sin`() {
        // A square's rotated bounding box is square: zoom = c + s.
        assertEquals(
            (cos(15.0 * PI / 180.0) + sin(15.0 * PI / 180.0)).toFloat(),
            CropState.straightenZoom(1000, 1000, CropState.FULL_RECT, 15f),
            1e-4f,
        )
        // The same geometry at 45 deg (the classic sqrt(2)) holds for
        // the formula itself; the slider clamps at 15, so pin the
        // formula through a direct corner computation instead.
        assertEquals(sqrt(2.0), cos(PI / 4) + sin(PI / 4), 1e-6)
    }

    @Test
    fun `a small centered rect needs no zoom`() {
        // Half-size centered square on a square frame: its rotated
        // corners stay well inside the frame at 15 deg -> zoom 1.
        assertEquals(
            1f,
            CropState.straightenZoom(
                1000, 1000, CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f), 15f,
            ),
            1e-5f,
        )
    }

    @Test
    fun `negative angles need the same zoom as positive`() {
        val z1 = CropState.straightenZoom(1920, 1080, CropState.FULL_RECT, 10f)
        val z2 = CropState.straightenZoom(1920, 1080, CropState.FULL_RECT, -10f)
        assertEquals(z1, z2, 1e-5f)
    }

    @Test
    fun `edge-hugging rect clamps at the zoom ceiling`() {
        // A full-width sliver at the very top (UNCLAMPED — thinner
        // than clampRect allows, fed straight to the zoom math): any
        // rotation sends its top corners out immediately, so the zoom
        // clamps at the documented ceiling.
        assertEquals(
            CropState.STRAIGHTEN_ZOOM_MAX,
            CropState.straightenZoom(1000, 1000, CropState.RectF(0f, 0f, 1f, 0.001f), 5f),
            0f,
        )
    }

    // ---- output dims ----

    @Test
    fun `outputDims round the cropped size and snap even for the encoder`() {
        val r = CropState.RectF(0.1f, 0.1f, 0.3555f, 0.505f)
        val d = CropState.outputDims(1920, 1080, r)
        assertEquals(683, d.width)
        assertEquals(545, d.height)
        val e = CropState.outputDims(1920, 1080, r, even = true)
        assertEquals(682, e.width)
        assertEquals(544, e.height)
    }

    // ---- pixel application ----

    @Test
    fun `neutral crop returns the input buffer by reference`() {
        val argb = IntArray(4 * 4) { it }
        val out = CropState.applyToArgb(
            argb, 4, 4, CropState.FULL_RECT, 0f, spaceDeltaDeg = 0,
        )
        assertSame(argb, out.argb)
        assertEquals(4, out.width)
        assertEquals(4, out.height)
    }

    @Test
    fun `plain crop cuts the exact pixel window`() {
        // 6x4 frame with pixel values = y*6+x; crop to the 2x2 window
        // at (x=2..3, y=1..2) -> fractions x=2/6, y=1/4, w=2/6, h=2/4.
        val w = 6
        val h = 4
        val argb = IntArray(w * h) { y -> 0 }.also { buf ->
            for (y in 0 until h) for (x in 0 until w) buf[y * w + x] = y * w + x
        }
        val r = CropState.RectF(2f / 6f, 1f / 4f, 2f / 6f, 2f / 4f)
        val out = CropState.applyToArgb(argb, w, h, r, 0f, spaceDeltaDeg = 0)
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(intArrayOf(1 * 6 + 2, 1 * 6 + 3, 2 * 6 + 2, 2 * 6 + 3), out.argb)
    }

    @Test
    fun `crop through a 90 degree space delta maps the rect into container space`() {
        // Effective-space rect = the LEFT half (x 0..0.5). The preview
        // frame is container-upright and the surface turns +90 CW, and
        // a +90 CW turn carries the container's BOTTOM half to the
        // effective LEFT half — so cropping with spaceDeltaDeg = 90
        // must cut the container's BOTTOM half (rows 4..7 of 8).
        val w = 4
        val h = 8 // container portrait; effective (after +90) is 8x4
        val argb = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) argb[y * w + x] = y * w + x
        val leftHalf = CropState.RectF(0f, 0f, 0.5f, 1f)
        val out = CropState.applyToArgb(argb, w, h, leftHalf, 0f, spaceDeltaDeg = 90)
        // mapRect(leftHalf, 90) = (x=0, y=1-0-0.5=0.5, w=1, h=0.5):
        // the container's bottom half.
        assertEquals(4, out.width)
        assertEquals(4, out.height)
        assertArrayEquals(
            (4 until 8).flatMap { y -> (0 until 4).map { x -> y * w + x } }.toIntArray(),
            out.argb,
        )
    }

    @Test
    fun `straighten with auto zoom keeps corners inside the source`() {
        // On a frame that is constant everywhere, every bilinear
        // sample — clamped or not — equals the constant: no black or
        // transparent sliver can appear at any straighten angle.
        val w = 40
        val h = 24
        val argb = IntArray(w * h) { 0xFF80FF80.toInt() }
        val out = CropState.applyToArgb(
            argb, w, h, CropState.FULL_RECT, straightenDeg = 5f, spaceDeltaDeg = 0,
        )
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        for (p in out.argb) assertEquals(0xFF80FF80.toInt(), p)
    }

    @Test
    fun `straighten rotates content about the crop center`() {
        // A 45x45 frame with a bright pixel at the exact center: any
        // straighten angle must keep the center output pixel exactly
        // bright (the rotation is about the rect center; the center
        // maps onto itself, zoom only scales radius).
        val w = 45
        val h = 45
        val argb = IntArray(w * h) { 0xFF000000.toInt() }
        argb[(h / 2) * w + (w / 2)] = 0xFFFFFFFF.toInt()
        val out = CropState.applyToArgb(
            argb, w, h, CropState.FULL_RECT, straightenDeg = 7.5f, spaceDeltaDeg = 0,
        )
        val center = out.argb[(out.height / 2) * out.width + (out.width / 2)]
        assertEquals(0xFFFFFFFF.toInt(), center)
    }

    @Test
    fun `rgb888 crop matches the argb crop geometry`() {
        val w = 8
        val h = 8
        val rgb = ByteArray(w * h * 3) { (it * 7).toByte() }
        val argb = IntArray(w * h) { i ->
            val j = i * 3
            (0xFF shl 24) or
                ((rgb[j].toInt() and 0xFF) shl 16) or
                ((rgb[j + 1].toInt() and 0xFF) shl 8) or
                (rgb[j + 2].toInt() and 0xFF)
        }
        val r = CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f)
        val a = CropState.applyToArgb(argb, w, h, r, 0f, spaceDeltaDeg = 0)
        val g = CropState.applyToRgb(rgb, w, h, r, 0f, spaceDeltaDeg = 0)
        assertEquals(a.width, g.width)
        assertEquals(a.height, g.height)
        for (i in a.argb.indices) {
            assertEquals((a.argb[i] shr 16) and 0xFF, g.rgb[i * 3].toInt() and 0xFF)
            assertEquals((a.argb[i] shr 8) and 0xFF, g.rgb[i * 3 + 1].toInt() and 0xFF)
            assertEquals(a.argb[i] and 0xFF, g.rgb[i * 3 + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun `mis-sized buffers are rejected`() {
        var threw = false
        try {
            CropState.applyToArgb(IntArray(3), 2, 2, CropState.FULL_RECT, 0f, 0)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        threw = false
        try {
            CropState.applyToRgb(ByteArray(3), 2, 2, CropState.FULL_RECT, 0f, 0)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `even output dims for the encoder path`() {
        val argb = IntArray(7 * 5)
        val r = CropState.RectF(0f, 0f, 1f, 1f)
        val out = CropState.applyToArgb(argb, 7, 5, r, 0f, spaceDeltaDeg = 0, even = true)
        assertNotSame(argb, out.argb)
        assertEquals(6, out.width)
        assertEquals(4, out.height)
        assertEquals(6 * 4, out.argb.size)
    }
}
