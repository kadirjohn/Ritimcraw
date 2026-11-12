package com.ritim.ritimcraw.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * THE single crop/straighten decision object (RAW-09), the crop
 * counterpart of [RotationState]: every consumer — the preview publish,
 * the MP4 export pixels, the photo export pixels, the DNG source cut —
 * maps the grade's crop rect through THIS object, so the four can never
 * disagree about which region is selected.
 *
 * Coordinate spaces (the one idea to hold):
 *  - The crop rect lives in EFFECTIVE-UPRIGHT space — fractions of the
 *    frame the user SEES (container orientation + manual rotation,
 *    [RotationState.effectiveDeg]). Aspect presets are computed against
 *    these dims, so "16:9" is 16:9 on screen no matter how the clip is
 *    rotated.
 *  - Pixel buffers arrive in OTHER spaces: the preview frame is
 *    CONTAINER-upright (the native develop rotates by the container
 *    value; PreviewSurface turns only the manual delta on top), the
 *    MP4/photo exports rotate to effective FIRST, and the DNG path
 *    holds NOT-rotated source CFA pixels. [mapRect] moves the rect
 *    into whichever space the pixels are in.
 *
 * Straighten: a small-angle (-15..+15 deg) rotation about the crop
 * rect's center with an edge-preserving auto zoom (the spec's
 * "kenarlari koruyan otomatik zoom"). The output pixel d samples the
 * source at R(theta) * (d / zoom) about the rect center — the zoom is
 * the SMALLEST scale-up for which every output corner still samples
 * inside the frame (no empty triangles, no stretch). Because the rect
 * center is fixed under quarter turns and rotations about the same
 * center commute, applying straighten in container space (preview) or
 * effective space (MP4/photo) yields the same image — the preview can
 * crop container-upright pixels and let the surface keep turning the
 * manual delta.
 *
 * Pure math only — no Android imports, JVM-testable.
 */
object CropState {

    /** Straighten slider range: |deg| <= 15 (spec: small corrections). */
    const val STRAIGHTEN_MAX_DEG = 15f

    /** Straighten slider step: half-degree ticks (61 values over the range). */
    const val STRAIGHTEN_STEP_DEG = 0.5f

    /** Crop fraction slider step: 1 percent. */
    const val FRACTION_STEP = 0.01f

    /**
     * Auto-zoom ceiling: a crop rect hugging the frame edge needs an
     * unbounded zoom for any nonzero angle (its rotated corner leaves
     * the frame immediately). The zoom clamps here and the sampler's
     * clamp-to-edge fills the sliver — documented, not silent.
     */
    const val STRAIGHTEN_ZOOM_MAX = 8f

    /** Crop rect as fractions of the frame it lives in: x/y top-left, w/h size. */
    data class RectF(val x: Float, val y: Float, val w: Float, val h: Float)

    /** The neutral rect: the whole frame (all GradeK defaults). */
    val FULL_RECT = RectF(0f, 0f, 1f, 1f)

    /**
     * Aspect presets (UI order: Free, 16:9, 9:16, 4:3, 1:1). FREE keeps
     * the rect independent; the others lock the rect's pixel aspect to
     * the ratio against the effective dims. Stored on the grade as
     * 0..4 (the tonemap Int precedent).
     */
    const val ASPECT_FREE = 0
    const val ASPECT_16_9 = 1
    const val ASPECT_9_16 = 2
    const val ASPECT_4_3 = 3
    const val ASPECT_1_1 = 4

    /** width/height ratio of [preset], or null for FREE (no lock). */
    fun aspectRatio(preset: Int): Float? = when (preset) {
        ASPECT_16_9 -> 16f / 9f
        ASPECT_9_16 -> 9f / 16f
        ASPECT_4_3 -> 4f / 3f
        ASPECT_1_1 -> 1f
        else -> null
    }

    /** True when the rect is exactly the full frame (snapped values compare exactly). */
    fun isFull(rect: RectF): Boolean =
        rect.x == 0f && rect.y == 0f && rect.w == 1f && rect.h == 1f

    /** True when the whole crop group is neutral (full rect, FREE aspect, 0 deg). */
    fun isNeutral(rect: RectF, aspect: Int, straightenDeg: Float): Boolean =
        isFull(rect) && aspect == ASPECT_FREE && straightenDeg == 0f

    /**
     * Clamps a rect to a valid sub-rect of the unit square: edges
     * inside [0, 1] and a 5 percent minimum size per axis, so the
     * sliders can never collapse the frame to nothing or push it out.
     */
    fun clampRect(rect: RectF): RectF {
        val minSize = 0.05f
        val x = rect.x.coerceIn(0f, 1f - minSize)
        val y = rect.y.coerceIn(0f, 1f - minSize)
        val w = rect.w.coerceIn(minSize, 1f - x)
        val h = rect.h.coerceIn(minSize, 1f - y)
        return RectF(x, y, w, h)
    }

    /**
     * The largest CENTERED rect of aspect [ratio] (w/h) inside a
     * [frameW] x [frameH] frame, as fractions of that frame. A preset
     * replaces the whole rect (the whole point of a preset); the free
     * sliders move it afterwards.
     */
    fun aspectRect(ratio: Float, frameW: Int, frameH: Int): RectF {
        if (frameW <= 0 || frameH <= 0 || ratio <= 0f) return FULL_RECT
        val frameRatio = frameW.toFloat() / frameH.toFloat()
        val w: Float
        val h: Float
        if (ratio >= frameRatio) {
            w = 1f
            h = (frameRatio / ratio).coerceAtMost(1f)
        } else {
            w = (ratio / frameRatio).coerceAtMost(1f)
            h = 1f
        }
        return clampRect(RectF((1f - w) / 2f, (1f - h) / 2f, w, h))
    }

    /**
     * Keeps a rect on its preset's ratio after a slider moved one
     * axis: height follows width (both converted through the frame's
     * own aspect, since the rect lives in fraction space). FREE
     * returns the rect clamped only.
     */
    fun lockAspect(rect: RectF, preset: Int, frameW: Int, frameH: Int): RectF {
        val ratio = aspectRatio(preset) ?: return clampRect(rect)
        if (frameW <= 0 || frameH <= 0) return clampRect(rect)
        val frameRatio = frameW.toFloat() / frameH.toFloat()
        // pixel aspect = (rect.w * frameW) / (rect.h * frameH) == ratio
        var h = rect.w * frameRatio / ratio
        if (h > 1f) {
            // Width cannot cover a height over the frame: cap at the
            // full height and derive the width that keeps the ratio.
            val w = ratio / frameRatio
            return clampRect(RectF(rect.x, rect.y, w, 1f))
        }
        return clampRect(RectF(rect.x, rect.y, rect.w, h))
    }

    /**
     * Maps an EFFECTIVE-upright rect into the space of pixels rotated
     * the OTHER way by [deg] — i.e. inverts a [deg]-CW rotation:
     *   deg = manualDeltaDeg  -> CONTAINER space (the preview frame)
     *   deg = effectiveDeg    -> SOURCE space (the DNG's unrotated CFA)
     * Quarter turns swap w/h; the half turn mirrors both axes; 0 is
     * identity. The rect CENTER maps to the same physical point under
     * every case (straighten commutes with these turns).
     */
    fun mapRect(rect: RectF, deg: Int): RectF = when (RotationState.normalize(deg)) {
        90 -> RectF(rect.y, 1f - rect.x - rect.w, rect.h, rect.w)
        180 -> RectF(1f - rect.x - rect.w, 1f - rect.y - rect.h, rect.w, rect.h)
        270 -> RectF(1f - rect.y - rect.h, rect.x, rect.h, rect.w)
        else -> rect
    }

    /** Integer pixel rect: x/y top-left, w/h size, all >= 0. */
    data class RectI(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * The rect as pixels of a [width] x [height] buffer, snapped to an
     * [align]-pixel grid (the DNG CFA cut aligns 2: even offsets and
     * sizes keep the Bayer pattern intact) and clamped inside the
     * frame.
     */
    fun pixelRect(rect: RectF, width: Int, height: Int, align: Int = 1): RectI {
        if (width <= 0 || height <= 0) return RectI(0, 0, 0, 0)
        val a = max(1, align)
        var x = (rect.x * width).roundToInt().coerceIn(0, width - 1)
        var y = (rect.y * height).roundToInt().coerceIn(0, height - 1)
        var w = (rect.w * width).roundToInt()
        var h = (rect.h * height).roundToInt()
        x -= x % a
        y -= y % a
        w = max(w - w % a, a)
        h = max(h - h % a, a)
        if (x + w > width) x = max(width - w, 0)
        if (y + h > height) y = max(height - h, 0)
        w = min(w, width - x)
        h = min(h, height - y)
        return RectI(x, y, w, h)
    }

    /**
     * The smallest zoom >= 1 for which every corner of the crop rect,
     * mapped back through the straighten sampler
     * (source offset = R(theta) * corner / zoom), still lands inside
     * the [width] x [height] frame. Each corner axis gives one LINEAR
     * LOWER bound on zoom; the max of those wins.
     *
     * Degenerate cases: 0 deg or a non-positive rect returns 1; the
     * full frame at [deg] returns exactly the classic inscribed-rect
     * scale-up max(cos + (W/H) sin, cos + (H/W) sin) (JVM-tested with
     * hand-derived numbers, e.g. 1920x1080 at 15 deg -> 1.4260; a
     * square at 45 deg -> sqrt(2)).
     */
    fun straightenZoom(width: Int, height: Int, rect: RectF, straightenDeg: Float): Float {
        val deg = straightenDeg.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG)
        if (width <= 0 || height <= 0 || deg == 0f) return 1f
        val cx = (rect.x + rect.w / 2f) * width
        val cy = (rect.y + rect.h / 2f) * height
        val ax = rect.w * width / 2f
        val ay = rect.h * height / 2f
        if (ax <= 0f || ay <= 0f) return 1f
        val theta = deg.toDouble() * PI / 180.0
        val c = cos(theta)
        val s = sin(theta)
        var zoom = 1f
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                val dx = ax * sx
                val dy = ay * sy
                // R(theta) * (dx, dy): where this output corner samples.
                val tx = dx * c - dy * s
                val ty = dx * s + dy * c
                if (tx > 0) zoom = max(zoom, (tx / (width - cx)).toFloat())
                if (tx < 0) zoom = max(zoom, (-tx / cx).toFloat())
                if (ty > 0) zoom = max(zoom, (ty / (height - cy)).toFloat())
                if (ty < 0) zoom = max(zoom, (-ty / cy).toFloat())
            }
        }
        return zoom.coerceIn(1f, STRAIGHTEN_ZOOM_MAX)
    }

    /**
     * Output dimensions for a rect over a [width] x [height] frame —
     * the cropped frame's own dims, minimum 1px per side. Pass
     * [even] = true for the MP4 encoder path (even YUV sizes, min 2).
     */
    fun outputDims(
        width: Int,
        height: Int,
        rect: RectF,
        even: Boolean = false,
    ): RotationState.Dims {
        var w = max((rect.w * width).roundToInt(), 1)
        var h = max((rect.h * height).roundToInt(), 1)
        if (even) {
            w = snapEven(w)
            h = snapEven(h)
        }
        return RotationState.Dims(w, h)
    }

    /** Snaps a dimension to even (MediaCodec wants even YUV sizes), min 2. */
    fun snapEven(v: Int): Int = max(if (v % 2 != 0) v - 1 else v, 2)

    /** A cropped+straightened ARGB buffer with its output dimensions. */
    data class ArgbCrop(val argb: IntArray, val width: Int, val height: Int)

    /** A cropped+straightened RGB888 buffer with its output dimensions. */
    data class RgbCrop(val rgb: ByteArray, val width: Int, val height: Int)

    /**
     * Applies the crop (and straighten, when nonzero) to a row-major
     * ARGB buffer whose pixels live [spaceDeltaDeg] counter-clockwise
     * of effective space — the preview passes its manual rotation
     * delta (its frame is container-upright and the surface turns the
     * delta back), the photo export passes 0 (it rotates to effective
     * first). [even] snaps the output dims even for the MP4 encoder.
     *
     * Neutral fast path returns the input array by reference: callers
     * keep their publish fold allocation-free until the user actually
     * crops. Straighten samples bilinearly with clamp-to-edge.
     */
    fun applyToArgb(
        argb: IntArray,
        width: Int,
        height: Int,
        rect: RectF,
        straightenDeg: Float,
        spaceDeltaDeg: Int,
        even: Boolean = false,
    ): ArgbCrop {
        require(width > 0 && height > 0) {
            "frame must have positive dimensions, got ${width}x$height"
        }
        require(argb.size == width * height) {
            "argb buffer holds ${argb.size} ints, expected ${width * height}"
        }
        val r = mapRect(rect, spaceDeltaDeg)
        val deg = straightenDeg.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG)
        val out = outputDims(width, height, r, even)
        if (deg == 0f && isFull(r) && out.width == width && out.height == height) {
            return ArgbCrop(argb, width, height)
        }
        if (deg == 0f) {
            val px = pixelRect(r, width, height)
            val x0 = minOf(px.x, width - out.width)
            val dst = IntArray(out.width * out.height)
            for (y in 0 until out.height) {
                val srcRow = (px.y + y).coerceAtMost(px.y + px.h - 1)
                System.arraycopy(argb, srcRow * width + x0, dst, y * out.width, out.width)
            }
            return ArgbCrop(dst, out.width, out.height)
        }
        val zoom = straightenZoom(width, height, r, deg)
        val cx = (r.x + r.w / 2f) * width
        val cy = (r.y + r.h / 2f) * height
        val theta = deg.toDouble() * PI / 180.0
        // Float trig: half-degree steps over ±15° lose nothing, and the
        // hot loops stay in float math.
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        val dst = IntArray(out.width * out.height)
        for (oy in 0 until out.height) {
            val dy = (oy + 0.5f) - out.height / 2f
            for (ox in 0 until out.width) {
                val dx = (ox + 0.5f) - out.width / 2f
                // Inverse map: source offset = R(-theta) * d / zoom —
                // positive degrees rotate the CONTENT clockwise, the
                // same sense as RotationState's CW-positive convention.
                val sx = cx + (dx * c + dy * s) / zoom
                val sy = cy + (-dx * s + dy * c) / zoom
                dst[oy * out.width + ox] = sampleArgbBilinear(argb, width, height, sx, sy)
            }
        }
        return ArgbCrop(dst, out.width, out.height)
    }

    /**
     * The RGB888 counterpart of [applyToArgb] for the MP4 export path
     * (the buffer is already rotated to effective space, so the
     * default [spaceDeltaDeg] = 0 applies) and any other row-major
     * 3-bytes-per-pixel buffer.
     */
    fun applyToRgb(
        rgb: ByteArray,
        width: Int,
        height: Int,
        rect: RectF,
        straightenDeg: Float,
        spaceDeltaDeg: Int = 0,
        even: Boolean = false,
    ): RgbCrop {
        require(width > 0 && height > 0) {
            "frame must have positive dimensions, got ${width}x$height"
        }
        require(rgb.size == width * height * 3) {
            "rgb buffer holds ${rgb.size} bytes, expected ${width * height * 3} " +
                "(${width}x${height} RGB888)"
        }
        val r = mapRect(rect, spaceDeltaDeg)
        val deg = straightenDeg.coerceIn(-STRAIGHTEN_MAX_DEG, STRAIGHTEN_MAX_DEG)
        val out = outputDims(width, height, r, even)
        if (deg == 0f && isFull(r) && out.width == width && out.height == height) {
            return RgbCrop(rgb, width, height)
        }
        if (deg == 0f) {
            val px = pixelRect(r, width, height)
            val x0 = minOf(px.x, width - out.width)
            val dst = ByteArray(out.width * out.height * 3)
            for (y in 0 until out.height) {
                val srcRow = (px.y + y).coerceAtMost(px.y + px.h - 1)
                System.arraycopy(
                    rgb, (srcRow * width + x0) * 3,
                    dst, y * out.width * 3, out.width * 3,
                )
            }
            return RgbCrop(dst, out.width, out.height)
        }
        val zoom = straightenZoom(width, height, r, deg)
        val cx = (r.x + r.w / 2f) * width
        val cy = (r.y + r.h / 2f) * height
        val theta = deg.toDouble() * PI / 180.0
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()
        val dst = ByteArray(out.width * out.height * 3)
        for (oy in 0 until out.height) {
            val dy = (oy + 0.5f) - out.height / 2f
            for (ox in 0 until out.width) {
                val dx = (ox + 0.5f) - out.width / 2f
                // Same inverse map as applyToArgb (content turns CW for
                // positive degrees).
                val sx = cx + (dx * c + dy * s) / zoom
                val sy = cy + (-dx * s + dy * c) / zoom
                sampleRgbBilinear(rgb, width, height, sx, sy, dst, (oy * out.width + ox) * 3)
            }
        }
        return RgbCrop(dst, out.width, out.height)
    }

    /** Bilinear ARGB sample with clamp-to-edge (straighten edge slivers). */
    private fun sampleArgbBilinear(
        argb: IntArray,
        width: Int,
        height: Int,
        sx: Float,
        sy: Float,
    ): Int {
        val x0 = floorClamp(sx - 0.5f, width)
        val y0 = floorClamp(sy - 0.5f, height)
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = (sx - 0.5f - x0).coerceIn(0f, 1f)
        val fy = (sy - 0.5f - y0).coerceIn(0f, 1f)
        val p00 = argb[y0 * width + x0]
        val p10 = argb[y0 * width + x1]
        val p01 = argb[y1 * width + x0]
        val p11 = argb[y1 * width + x1]
        val a = bilerp((p00 ushr 24) and 0xFF, (p10 ushr 24) and 0xFF, (p01 ushr 24) and 0xFF, (p11 ushr 24) and 0xFF, fx, fy)
        val r = bilerp((p00 ushr 16) and 0xFF, (p10 ushr 16) and 0xFF, (p01 ushr 16) and 0xFF, (p11 ushr 16) and 0xFF, fx, fy)
        val g = bilerp((p00 ushr 8) and 0xFF, (p10 ushr 8) and 0xFF, (p01 ushr 8) and 0xFF, (p11 ushr 8) and 0xFF, fx, fy)
        val b = bilerp(p00 and 0xFF, p10 and 0xFF, p01 and 0xFF, p11 and 0xFF, fx, fy)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Bilinear RGB888 sample into [dst] at [offset], clamp-to-edge. */
    private fun sampleRgbBilinear(
        rgb: ByteArray,
        width: Int,
        height: Int,
        sx: Float,
        sy: Float,
        dst: ByteArray,
        offset: Int,
    ) {
        val x0 = floorClamp(sx - 0.5f, width)
        val y0 = floorClamp(sy - 0.5f, height)
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = (sx - 0.5f - x0).coerceIn(0f, 1f)
        val fy = (sy - 0.5f - y0).coerceIn(0f, 1f)
        for (ch in 0 until 3) {
            val i00 = (y0 * width + x0) * 3 + ch
            val i10 = (y0 * width + x1) * 3 + ch
            val i01 = (y1 * width + x0) * 3 + ch
            val i11 = (y1 * width + x1) * 3 + ch
            val v = bilerp(
                rgb[i00].toInt() and 0xFF, rgb[i10].toInt() and 0xFF,
                rgb[i01].toInt() and 0xFF, rgb[i11].toInt() and 0xFF,
                fx, fy,
            )
            dst[offset + ch] = v.toByte()
        }
    }

    private fun floorClamp(v: Float, limit: Int): Int =
        kotlin.math.floor(v.toDouble()).toInt().coerceIn(0, limit - 1)

    private fun bilerp(v00: Int, v10: Int, v01: Int, v11: Int, fx: Float, fy: Float): Int {
        val top = v00 + (v10 - v00) * fx
        val bottom = v01 + (v11 - v01) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }
}
