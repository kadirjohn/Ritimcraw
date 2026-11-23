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

    @Test
    fun `lockAspect shifts position instead of breaking the ratio at the bottom edge`() {
        // 9:16 preset on a 1920x1080 frame, width full: the derived
        // height is 3 * 16/9 > 1 — the cap branch runs (full height,
        // derived width). But a rect low in the frame with a derived
        // height that overflows the BOTTOM must slide up, not shrink:
        // y = 0.8 with h = 0.3 would end at 1.1 — the shift pins y at
        // 1 - h = 0.7 with the ratio intact.
        val r = CropState.lockAspect(
            CropState.RectF(0.3f, 0.8f, 0.5f, 0.2f),
            CropState.ASPECT_16_9, 1920, 1080,
        )
        // h = w * frameRatio / ratio = 0.5 * (16/9)/(16/9) = 0.5.
        assertEquals(0.5f, r.h, 1e-6f)
        assertEquals(0.5f, r.w, 1e-6f)
        // The shift: y gives way so the rect stays inside the frame.
        assertEquals(0.5f, r.y, 1e-6f)
        assertEquals(0.3f, r.x, 1e-6f)
    }

    @Test
    fun `lockAspectHeight derives width from height and shifts at the right edge`() {
        // The height-axis twin: 16:9 on 1920x1080, height dragged to
        // half the frame -> pixel h = 540, ratio 16/9 -> pixel w = 960
        // = half the frame width.
        val r = CropState.lockAspectHeight(
            CropState.RectF(0.25f, 0.25f, 0.9f, 0.5f),
            CropState.ASPECT_16_9, 1920, 1080,
        )
        assertEquals(0.5f, r.h, 1e-6f)
        assertEquals(0.5f, r.w, 1e-6f)
        // A derived width that overflows the right edge at the current
        // x shifts the rect LEFT, position giving way before the ratio:
        // x = 0.8, w = 0.5 would end at 1.3 — the shift pins x at 0.5.
        val shifted = CropState.lockAspectHeight(
            CropState.RectF(0.8f, 0.2f, 0.1f, 0.5f),
            CropState.ASPECT_16_9, 1920, 1080,
        )
        assertEquals(0.5f, shifted.w, 1e-6f)
        assertEquals(0.5f, shifted.x, 1e-6f)
        assertEquals(0.2f, shifted.y, 1e-6f)
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
    fun `cropSourcePosition is byte-identical with applyToArgb's loop math`() {
        // A2 GPU crop/straighten (owner request 2026-11-23, guide A2): the
        // per-pixel inverse map is EXTRACTED from applyToArgb's loop into a
        // public pure function so GpuCropMap (the GPU mirror) and the parity
        // test can hold it against the SAME numbers. This pin recomputes
        // the OLD private math verbatim (the loop body before the
        // extraction) for the plan's own anchor case — 1920x1080, rect
        // (0.25, 0.25, 0.5, 0.5), deg 7 — and asserts the exposed function
        // returns exactly those positions at every output pixel: the CPU
        // pixels must not change by one bit.
        val w = 1920
        val h = 1080
        val r = CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f)
        val deg = 7f
        // The old private math, verbatim (the pre-extraction loop body).
        val zoom = CropState.straightenZoom(w, h, r, deg)
        val cx = (r.x + r.w / 2f) * w
        val cy = (r.y + r.h / 2f) * h
        val theta = deg.toDouble() * PI / 180.0
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        val out = CropState.outputDims(w, h, r)
        for (oy in 0 until out.height step 13) {
            val dy = (oy + 0.5f) - out.height / 2f
            for (ox in 0 until out.width step 17) {
                val dx = (ox + 0.5f) - out.width / 2f
                val expectSx = cx + (dx * c + dy * s) / zoom
                val expectSy = cy + (-dx * s + dy * c) / zoom
                val p = CropState.cropSourcePosition(ox, oy, out.width, out.height, r, deg, w, h)
                assertEquals(expectSx, p[0], 0.0f)
                assertEquals(expectSy, p[1], 0.0f)
            }
        }
    }

    @Test
    fun `cropSourcePosition at zero degrees degenerates to integer-center positions`() {
        // deg=0 keeps zoom 1 and cos/sin 1/0: every output pixel's source
        // position lands on a pixel CENTER (ox+0.5 offset by the rect's
        // own start), so the bilinear weights are exactly 0 — the CPU's
        // deg-0 arm is a pure row copy and the GPU's unified map
        // degenerates to it (the plan's degeneration pin).
        val w = 640
        val h = 360
        val r = CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f)
        val out = CropState.outputDims(w, h, r)
        for (oy in 0 until out.height) {
            for (ox in 0 until out.width) {
                val p = CropState.cropSourcePosition(ox, oy, out.width, out.height, r, 0f, w, h)
                // Integer centre: x.5 in both axes (the CPU samples that
                // texel exactly; the fractional weight is 0).
                assertEquals(0.5f, p[0] % 1f, 1e-4f)
                assertEquals(0.5f, p[1] % 1f, 1e-4f)
            }
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

    // ---- overlay handles (owner request 2026-11-13 §2) ----
    //
    // The hit-test and drag tests use a 1000x1000 video rect, so a
    // pointer pixel is exactly 0.1 percent and every expected number
    // is hand-derived in percents; the preset drag tests use a square
    // 1000x1000 FRAME too, where an on-ratio rect has w/h == ratio in
    // fraction space (frameRatio 1).

    @Test
    fun `handleAt finds all 8 handles at their points`() {
        val rect = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // rect -> pixels (x0,y0)=(200,200), (x1,y1)=(700,700),
        // midpoints mx=my=450.
        assertEquals(
            CropState.Handle.TOP_LEFT,
            CropState.handleAt(rect, 200f, 200f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.TOP_RIGHT,
            CropState.handleAt(rect, 700f, 200f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.BOTTOM_LEFT,
            CropState.handleAt(rect, 200f, 700f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.BOTTOM_RIGHT,
            CropState.handleAt(rect, 700f, 700f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.TOP,
            CropState.handleAt(rect, 450f, 200f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.BOTTOM,
            CropState.handleAt(rect, 450f, 700f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.LEFT,
            CropState.handleAt(rect, 200f, 450f, 1000f, 1000f, 24f),
        )
        assertEquals(
            CropState.Handle.RIGHT,
            CropState.handleAt(rect, 700f, 450f, 1000f, 1000f, 24f),
        )
    }

    @Test
    fun `handleAt grows each handle point into its tolerance square`() {
        val rect = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // The square reaches 24px out in every direction.
        assertEquals(
            CropState.Handle.TOP_LEFT,
            CropState.handleAt(rect, 176f, 176f, 1000f, 1000f, 24f),
        )
        // One pixel past the square is nobody's handle (the LEFT
        // midpoint sits 250px away — no fallback edge catches it).
        assertNull(CropState.handleAt(rect, 175f, 200f, 1000f, 1000f, 24f))
        // The rect center and the far corner are outside every square.
        assertNull(CropState.handleAt(rect, 450f, 450f, 1000f, 1000f, 24f))
        assertNull(CropState.handleAt(rect, 990f, 990f, 1000f, 1000f, 24f))
    }

    @Test
    fun `handleAt lets the corner win over the edge it sits on`() {
        // A small rect (400..600) with a 60px tolerance: the point
        // (450,400) is inside BOTH the TOP_LEFT corner square and the
        // TOP midpoint square — the corner is tested first and wins,
        // the documented tie-break for overlapping touch areas.
        val rect = CropState.RectF(0.4f, 0.4f, 0.2f, 0.2f)
        assertEquals(
            CropState.Handle.TOP_LEFT,
            CropState.handleAt(rect, 450f, 400f, 1000f, 1000f, 60f),
        )
    }

    @Test
    fun `handleAt returns null for degenerate video rects`() {
        assertNull(
            CropState.handleAt(CropState.FULL_RECT, 500f, 500f, 0f, 1000f, 24f),
        )
        assertNull(
            CropState.handleAt(CropState.FULL_RECT, 500f, 500f, 1000f, 0f, 24f),
        )
    }

    @Test
    fun `free edge drags move one axis and pin the opposite edge`() {
        val base = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // RIGHT +100px: w 0.5 -> 0.6, the LEFT edge stays at 0.2.
        var r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 100f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.2f, r.x, 1e-6f)
        assertEquals(0.6f, r.w, 1e-6f)
        assertEquals(0.2f, r.y, 1e-6f)
        assertEquals(0.5f, r.h, 1e-6f)
        // LEFT -100px: w 0.5 -> 0.6, the RIGHT edge stays at 0.7.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.LEFT, -100f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.1f, r.x, 1e-6f)
        assertEquals(0.6f, r.w, 1e-6f)
        assertEquals(0.7f, r.x + r.w, 1e-6f)
        assertEquals(0.5f, r.h, 1e-6f)
        // BOTTOM +100px: h 0.5 -> 0.6, the TOP edge stays at 0.2.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.BOTTOM, 0f, 100f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.2f, r.y, 1e-6f)
        assertEquals(0.6f, r.h, 1e-6f)
        assertEquals(0.5f, r.w, 1e-6f)
        // TOP -100px: h 0.5 -> 0.6, the BOTTOM edge stays at 0.7.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.TOP, 0f, -100f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.1f, r.y, 1e-6f)
        assertEquals(0.6f, r.h, 1e-6f)
        assertEquals(0.7f, r.y + r.h, 1e-6f)
        assertEquals(0.5f, r.w, 1e-6f)
    }

    @Test
    fun `free corner drags move both axes independently`() {
        val base = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // TOP_RIGHT (+100, -100)px: w and h both 0.5 -> 0.6; the
        // anchored LEFT edge stays 0.2, the anchored BOTTOM edge
        // stays 0.7.
        val r = CropState.applyHandleDrag(
            base, CropState.Handle.TOP_RIGHT, 100f, -100f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.2f, r.x, 1e-6f)
        assertEquals(0.6f, r.w, 1e-6f)
        assertEquals(0.1f, r.y, 1e-6f)
        assertEquals(0.6f, r.h, 1e-6f)
        assertEquals(0.7f, r.y + r.h, 1e-6f)
    }

    @Test
    fun `edge drags clamp at the 5 percent floor and the frame bounds`() {
        val base = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // LEFT dragged +600px: the new left edge wants 0.8, but the
        // anchored right edge (0.7) minus the floor caps it at 0.65 ->
        // w floors at 0.05.
        var r = CropState.applyHandleDrag(
            base, CropState.Handle.LEFT, 600f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.65f, r.x, 1e-6f)
        assertEquals(CropState.MIN_FRACTION, r.w, 1e-6f)
        // LEFT dragged -300px: the left edge wants -0.1 and clamps at
        // the frame bound 0 -> w grows to 0.7.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.LEFT, -300f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0f, r.x, 1e-6f)
        assertEquals(0.7f, r.w, 1e-6f)
        // RIGHT dragged +600px: the right edge wants 1.3 and clamps
        // at the frame bound 1 -> w grows to 0.8.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 600f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0.8f, r.w, 1e-6f)
        assertEquals(1f, r.x + r.w, 1e-6f)
    }

    @Test
    fun `preset edge drags run the slider lock chains`() {
        // 16:9 on a square frame: an on-ratio rect has w/h = 16/9 in
        // fraction space (0.4 / 0.225).
        val base = CropState.RectF(0.2f, 0.2f, 0.4f, 0.225f)
        // RIGHT +100px: the width-led lock derives h = 0.5 * 9/16 =
        // 0.28125 — the SAME chain the W slider runs, so the overlay
        // and the slider agree on the same width by construction.
        var r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 100f, 0f, 1000f, 1000f,
            CropState.ASPECT_16_9, 1000, 1000,
        )
        assertEquals(0.5f, r.w, 1e-6f)
        assertEquals(0.28125f, r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
        assertEquals(0.2f, r.y, 1e-6f)
        // Cross-check: identical to lockAspect on the same width —
        // the W slider's own chain, verbatim.
        val sliderChain = CropState.lockAspect(
            base.copy(w = 0.5f), CropState.ASPECT_16_9, 1000, 1000,
        )
        assertEquals(sliderChain.x, r.x, 1e-6f)
        assertEquals(sliderChain.y, r.y, 1e-6f)
        assertEquals(sliderChain.w, r.w, 1e-6f)
        assertEquals(sliderChain.h, r.h, 1e-6f)
        // TOP -100px: the height-led lock (the H slider's chain)
        // derives w = 0.325 * 16/9 = 0.5777..., and the re-anchor
        // pins the BOTTOM edge at its start line 0.425.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.TOP, 0f, -100f, 1000f, 1000f,
            CropState.ASPECT_16_9, 1000, 1000,
        )
        assertEquals(0.325f, r.h, 1e-6f)
        assertEquals(0.325f * 16f / 9f, r.w, 1e-6f)
        assertEquals(0.425f, r.y + r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
        // 4:3 BOTTOM +100px from an on-ratio 0.4x0.3 rect: the
        // height-led lock derives w = 0.4 * 4/3 = 0.5333...
        r = CropState.applyHandleDrag(
            CropState.RectF(0.2f, 0.2f, 0.4f, 0.3f),
            CropState.Handle.BOTTOM, 0f, 100f, 1000f, 1000f,
            CropState.ASPECT_4_3, 1000, 1000,
        )
        assertEquals(0.4f, r.h, 1e-6f)
        assertEquals(0.4f * 4f / 3f, r.w, 1e-6f)
        assertEquals(0.2f, r.y, 1e-6f)
    }

    @Test
    fun `preset corner drags follow the on-ratio ray from the anchored corner`() {
        // 1:1 on a square frame: the ray is the diagonal, k = 1.
        // A PURE-VERTICAL drag still resizes: the projection of
        // (0, +0.1) onto the diagonal advances the corner by 0.05
        // along each axis -> w = h = 0.45.
        var r = CropState.applyHandleDrag(
            CropState.RectF(0.2f, 0.2f, 0.4f, 0.4f),
            CropState.Handle.BOTTOM_RIGHT, 0f, 100f, 1000f, 1000f,
            CropState.ASPECT_1_1, 1000, 1000,
        )
        assertEquals(0.45f, r.w, 1e-6f)
        assertEquals(0.45f, r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
        assertEquals(0.2f, r.y, 1e-6f)
        // 16:9 on a square frame: k = frameRatio / ratio = 9/16; a
        // pure-HORIZONTAL +100px drag projects onto the ray
        // (1, 9/16): t = fx / (1 + k^2), expected w follows the same
        // formula the implementation runs (the straighten-zoom
        // tests' derive-through-the-formula style), and the lock
        // keeps the result on-ratio.
        val k = 9f / 16f
        val expectedW = 0.4f + 0.1f / (1f + k * k)
        r = CropState.applyHandleDrag(
            CropState.RectF(0.2f, 0.2f, 0.4f, 0.225f),
            CropState.Handle.BOTTOM_RIGHT, 100f, 0f, 1000f, 1000f,
            CropState.ASPECT_16_9, 1000, 1000,
        )
        assertEquals(expectedW, r.w, 1e-6f)
        assertEquals(expectedW * 9f / 16f, r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
        assertEquals(0.2f, r.y, 1e-6f)
    }

    @Test
    fun `release snap quantizes the leading axis to the 1 percent grid`() {
        val base = CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f)
        // Continuous drag frames keep the raw pointer fraction...
        var r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 33f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000, snap = false,
        )
        assertEquals(0.533f, r.w, 1e-6f)
        // ...and release snaps it to the grid (0.533 -> 0.53).
        r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 33f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000, snap = true,
        )
        assertEquals(0.53f, r.w, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
        // Rounding: 0.567 lands on the 57th tick, not the 56th.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.RIGHT, 67f, 0f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000, snap = true,
        )
        assertEquals(0.57f, r.w, 1e-6f)
        // FREE corners snap BOTH axes, and the re-anchor keeps the
        // opposite corner pinned: TOP_LEFT (-33, -33) -> w = h = 0.53
        // at (0.17, 0.17), the bottom-right corner staying at 0.7.
        r = CropState.applyHandleDrag(
            base, CropState.Handle.TOP_LEFT, -33f, -33f, 1000f, 1000f,
            CropState.ASPECT_FREE, 1000, 1000, snap = true,
        )
        assertEquals(0.53f, r.w, 1e-6f)
        assertEquals(0.53f, r.h, 1e-6f)
        assertEquals(0.17f, r.x, 1e-6f)
        assertEquals(0.17f, r.y, 1e-6f)
        assertEquals(0.7f, r.x + r.w, 1e-6f)
        assertEquals(0.7f, r.y + r.h, 1e-6f)
    }

    @Test
    fun `release snap under a preset snaps the leader and derives the follower on-ratio`() {
        // 16:9 on a square frame, RIGHT +33px: the width snaps to
        // 0.43 and the lock DERIVES h = 0.43 * 9/16 = 0.241875 —
        // deliberately NOT on the 1 percent grid, the sliders' own
        // "leading axis snapped, derived axis on-ratio" settle.
        val r = CropState.applyHandleDrag(
            CropState.RectF(0.2f, 0.2f, 0.4f, 0.225f),
            CropState.Handle.RIGHT, 33f, 0f, 1000f, 1000f,
            CropState.ASPECT_16_9, 1000, 1000, snap = true,
        )
        assertEquals(0.43f, r.w, 1e-6f)
        assertEquals(0.43f * 9f / 16f, r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
    }

    @Test
    fun `unknown frame dims degrade a preset drag to free behavior`() {
        // frameW/frameH 0 (before the first publish): the locks'
        // own guard — the drag still works, unconstrained.
        val r = CropState.applyHandleDrag(
            CropState.RectF(0.2f, 0.2f, 0.5f, 0.5f),
            CropState.Handle.RIGHT, 100f, 0f, 1000f, 1000f,
            CropState.ASPECT_16_9, 0, 0,
        )
        assertEquals(0.6f, r.w, 1e-6f)
        assertEquals(0.5f, r.h, 1e-6f)
        assertEquals(0.2f, r.x, 1e-6f)
    }

    @Test
    fun `degenerate video rects clamp and return the start rect`() {
        val r = CropState.applyHandleDrag(
            CropState.RectF(-0.1f, 0f, 2f, 0.01f),
            CropState.Handle.RIGHT, 100f, 100f, 0f, 0f,
            CropState.ASPECT_FREE, 1000, 1000,
        )
        assertEquals(0f, r.x, 1e-6f)
        assertEquals(1f, r.w, 1e-6f)
        assertEquals(CropState.MIN_FRACTION, r.h, 1e-6f)
    }
}
