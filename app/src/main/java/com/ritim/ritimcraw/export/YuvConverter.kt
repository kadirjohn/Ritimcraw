package com.ritim.ritimcraw.export

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RGB -> YUV conversion matrix (limited-range 8-bit, studio swing:
 * Y in [16, 235], chroma in [16, 240]).
 *
 * Coefficients are derived from the ITU matrices with R, G, B in [0, 1]:
 *   Y' = Kr*R + (1-Kr-Kb)*G + Kb*B
 *   Y  = 16 + 219 * Y'
 *   Cb = 128 + 224 * (B - Y') / (2 * (1 - Kb))
 *   Cr = 128 + 224 * (R - Y') / (2 * (1 - Kr))
 *
 * BT.601 (Kr 0.299, Kb 0.114) is kept for legacy sources; the MP4 export
 * path encodes SDR Rec.709 (sprint plan Task 5), so [BT709] is the default.
 */
enum class YuvMatrix(
    internal val y: FloatArray,
    internal val cb: FloatArray,
    internal val cr: FloatArray,
) {
    BT601(
        y = floatArrayOf(65.481f, 128.553f, 24.966f),
        cb = floatArrayOf(-37.797f, -74.203f, 112.0f),
        cr = floatArrayOf(112.0f, -93.786f, -18.214f),
    ),
    BT709(
        // 219 * 0.2126 / 0.7152 / 0.0722, then chroma scaled by 224.
        y = floatArrayOf(46.5594f, 156.6288f, 15.8118f),
        cb = floatArrayOf(-25.664f, -86.336f, 112.0f),
        cr = floatArrayOf(112.0f, -101.734f, -10.270f),
    ),
}

/**
 * Pure RGB888 -> YUV 4:2:0 conversion (JVM-testable, no Android imports).
 *
 * Input: packed row-major RGB888 (3 bytes per pixel) — exactly what
 * nativeExportFrame returns. Luma is computed per pixel; each chroma sample
 * comes from the average of the 2x2 RGB block it covers (edge-clamped for odd
 * dimensions, so any size converts without crashing). Averaging the RGB
 * before the matrix is identical to averaging the per-pixel chroma (the
 * matrix is affine and the offsets cancel), and costs one evaluation per
 * sample instead of four.
 *
 * Output layouts (both tightly packed, byte 0 = top-left):
 *   I420: Y plane (w*h), then U plane (cw*ch), then V plane (cw*ch)
 *   NV12: Y plane (w*h), then interleaved UV (cw*ch pairs)
 * where cw = (w+1)/2, ch = (h+1)/2.
 */
object YuvConverter {

    /** Luma clamp for limited range (studio swing never exceeds these). */
    private const val Y_MIN = 16
    private const val Y_MAX = 235
    private const val C_MIN = 16
    private const val C_MAX = 240

    fun toI420(
        rgb: ByteArray,
        width: Int,
        height: Int,
        matrix: YuvMatrix = YuvMatrix.BT709,
    ): ByteArray {
        require(rgb.size >= width * height * 3) { "rgb buffer too small for ${width}x$height" }
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val out = ByteArray(width * height + 2 * cw * ch)

        // Luma: one sample per pixel.
        for (i in 0 until width * height) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            val y = 16f +
                matrix.y[0] * r / 255f +
                matrix.y[1] * g / 255f +
                matrix.y[2] * b / 255f
            out[i] = clamp(y.roundToInt(), Y_MIN, Y_MAX).toByte()
        }

        // Chroma: average of the covered 2x2 RGB block, edge-clamped.
        var uPos = width * height
        var vPos = width * height + cw * ch
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var n = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = min(cx * 2 + dx, width - 1)
                        val y = min(cy * 2 + dy, height - 1)
                        val i = (y * width + x) * 3
                        rSum += rgb[i].toInt() and 0xFF
                        gSum += rgb[i + 1].toInt() and 0xFF
                        bSum += rgb[i + 2].toInt() and 0xFF
                        n++
                    }
                }
                val r = rSum.toFloat() / (n * 255f)
                val g = gSum.toFloat() / (n * 255f)
                val b = bSum.toFloat() / (n * 255f)
                val cb = 128f + matrix.cb[0] * r + matrix.cb[1] * g + matrix.cb[2] * b
                val cr = 128f + matrix.cr[0] * r + matrix.cr[1] * g + matrix.cr[2] * b
                out[uPos++] = clamp(cb.roundToInt(), C_MIN, C_MAX).toByte()
                out[vPos++] = clamp(cr.roundToInt(), C_MIN, C_MAX).toByte()
            }
        }
        return out
    }

    fun toNv12(
        rgb: ByteArray,
        width: Int,
        height: Int,
        matrix: YuvMatrix = YuvMatrix.BT709,
    ): ByteArray {
        require(rgb.size >= width * height * 3) { "rgb buffer too small for ${width}x$height" }
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val out = ByteArray(width * height + 2 * cw * ch)

        for (i in 0 until width * height) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            val y = 16f +
                matrix.y[0] * r / 255f +
                matrix.y[1] * g / 255f +
                matrix.y[2] * b / 255f
            out[i] = clamp(y.roundToInt(), Y_MIN, Y_MAX).toByte()
        }

        var uvPos = width * height
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var n = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val x = min(cx * 2 + dx, width - 1)
                        val y = min(cy * 2 + dy, height - 1)
                        val i = (y * width + x) * 3
                        rSum += rgb[i].toInt() and 0xFF
                        gSum += rgb[i + 1].toInt() and 0xFF
                        bSum += rgb[i + 2].toInt() and 0xFF
                        n++
                    }
                }
                val r = rSum.toFloat() / (n * 255f)
                val g = gSum.toFloat() / (n * 255f)
                val b = bSum.toFloat() / (n * 255f)
                val cb = 128f + matrix.cb[0] * r + matrix.cb[1] * g + matrix.cb[2] * b
                val cr = 128f + matrix.cr[0] * r + matrix.cr[1] * g + matrix.cr[2] * b
                out[uvPos++] = clamp(cb.roundToInt(), C_MIN, C_MAX).toByte()
                out[uvPos++] = clamp(cr.roundToInt(), C_MIN, C_MAX).toByte()
            }
        }
        return out
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
}

/**
 * Destination planes for one I420 frame, the MediaCodec Image geometry
 * (rowStride/pixelStride) generalized — the Kotlin twin of rawedit's
 * YuvPlanes (core/include/rawedit/Yuv.h). Buffers are addressed
 * ABSOLUTELY (position never moves), so the pure helpers below work on
 * any ByteBuffer and the JNI bridge can hand over the encoder Image's
 * direct buffers untouched.
 */
class YuvFramePlanes(
    /** Luma plane buffer (pixelStride is always 1 for YUV 4:2:0 luma). */
    val y: ByteBuffer,
    /** Bytes per luma row (>= width). */
    val yStride: Int,
    /** U plane buffer (position 0 = the plane's first byte). */
    val u: ByteBuffer,
    /** Bytes per U row (>= cw * uPixelStride). */
    val uStride: Int,
    /** Bytes between adjacent U samples (1 planar, 2 semi-planar). */
    val uPixelStride: Int,
    /** V plane buffer (== u + 1 for NV12 interleaved UV). */
    val v: ByteBuffer,
    /** Bytes per V row. */
    val vStride: Int,
    /** Bytes between adjacent V samples. */
    val vPixelStride: Int,
)

/**
 * Pure strided-plane I/O for the YUV step (JVM-testable, no Android
 * imports): the packed <-> plane-laid-out conversions the writer's
 * fallback and the debug dual-run need. All accesses are absolute —
 * [ByteBuffer.position] never moves — so the same helpers work on heap
 * arrays (tests) and the encoder Image's direct buffers (production).
 */
object YuvPlaneIo {

    /**
     * Copies a tightly packed I420 frame ([YuvConverter.toI420] layout)
     * into [planes] honoring its strides — the exact write pattern
     * MediaCodecMp4Writer.copyI420ToImage performed (row-batched Y,
     * per-sample chroma at pixelStride), now pure and shared.
     */
    fun copyPackedToPlanes(i420: ByteArray, width: Int, height: Int, planes: YuvFramePlanes) {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val uOff = width * height
        val vOff = uOff + cw * ch
        // Row-batched luma: one absolute put per row (the fast path the
        // writer always had).
        for (row in 0 until height) {
            planes.y.put(row * planes.yStride, i420, 0 + row * width, width)
        }
        // Chroma: per-sample at pixelStride (planar 1, NV12's 2 — the
        // native twin writes cb/cr pairs directly; this Kotlin fallback
        // handles any layout the same way).
        for (row in 0 until ch) {
            var src = uOff + row * cw
            var dst = row * planes.uStride
            for (col in 0 until cw) {
                planes.u.put(dst, i420[src])
                src++
                dst += planes.uPixelStride
            }
        }
        for (row in 0 until ch) {
            var src = vOff + row * cw
            var dst = row * planes.vStride
            for (col in 0 until cw) {
                planes.v.put(dst, i420[src])
                src++
                dst += planes.vPixelStride
            }
        }
    }

    /**
     * Reads [planes] back into the tightly packed I420 layout — the
     * inverse of [copyPackedToPlanes] (the debug dual-run's readback:
     * what the encoder memory actually holds). Absolute gets.
     */
    fun readPlanesToPacked(width: Int, height: Int, planes: YuvFramePlanes): ByteArray {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val out = ByteArray(width * height + 2 * cw * ch)
        for (row in 0 until height) {
            planes.y.get(row * planes.yStride, out, 0 + row * width, width)
        }
        val uOff = width * height
        val vOff = uOff + cw * ch
        for (row in 0 until ch) {
            var dst = uOff + row * cw
            var src = row * planes.uStride
            for (col in 0 until cw) {
                out[dst] = planes.u.get(src)
                dst++
                src += planes.uPixelStride
            }
        }
        for (row in 0 until ch) {
            var dst = vOff + row * cw
            var src = row * planes.vStride
            for (col in 0 until cw) {
                out[dst] = planes.v.get(src)
                dst++
                src += planes.vPixelStride
            }
        }
        return out
    }
}

/**
 * The pipeline's RGB888 -> I420 conversion seam: the pipeline calls through
 * this so the encode loop stays JVM-testable while production dispatches to
 * the native twin (Mp4Exporter's NativeRgbToI420). The result carries the
 * bytes' ORIGIN (native vs Kotlin) so the pipeline's debug dual-run knows
 * when it has a native frame to compare against — without sniffing the
 * implementation. The BYTES are identical either way; that is the seam's
 * whole contract (core/src/Yuv.cpp).
 *
 * [convertToPlanes] is the direct-to-plane form (commit (c)): when the
 * writer offers the encoder Image's plane buffers, the conversion writes
 * them directly — no packed intermediate, no second copy. Null = the
 * caller must take the packed fallback, which produces the SAME bytes
 * through [YuvPlaneIo]'s strided copy. The default implementation is
 * exactly that packed path (packed convert + copy), so a JVM fake or any
 * converter that only knows packed keeps working and never returns null.
 */
fun interface RgbToI420 {
    /** Converts packed row-major RGB888 to tightly packed I420 (toI420 layout). */
    fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result

    /**
     * Converts straight into [planes] (the encoder Image's buffers,
     * honored at their strides). Null = the caller must take the packed
     * path; non-null = [planes] hold the frame and the result's
     * [RgbToI420PlanesResult.fromNative] tells the dual-run whether
     * there is native output to compare. The default runs the packed
     * conversion and copies it into the planes — never null,
     * byte-identical, the guaranteed fallback.
     */
    fun convertToPlanes(
        rgb: ByteArray,
        width: Int,
        height: Int,
        planes: YuvFramePlanes,
    ): RgbToI420PlanesResult? {
        val packed = convert(rgb, width, height)
        YuvPlaneIo.copyPackedToPlanes(packed.i420, width, height, planes)
        return RgbToI420PlanesResult(fromNative = packed.fromNative)
    }
}

/** One conversion outcome: the bytes plus where they came from. */
class RgbToI420Result(
    /** The tightly packed I420 frame (YuvConverter.toI420 layout). */
    val i420: ByteArray,
    /** True when [i420] came from the native twin, false from Kotlin. */
    val fromNative: Boolean,
)

/** The direct-to-plane outcome: origin only — the bytes live in the planes. */
class RgbToI420PlanesResult(
    /** True when the planes were written by the native twin. */
    val fromNative: Boolean,
)

/**
 * Pure decision object for the debug dual-run (the byte-exactness
 * affordance that ships in debug builds): WHEN to run both paths and WHAT
 * to log. No Android imports — fully JVM-testable.
 *
 * The rule: dual-run the FIRST frame produced by the native path of an
 * export. One frame is enough to prove the two implementations agree (the
 * cross-implementation oracle already proved byte-exactness across sizes
 * and matrices; this is the on-device confirmation), and re-running the
 * Kotlin conversion per frame would burn the exact per-frame cost this
 * mission removes. A Kotlin fallback frame never dual-runs — there is no
 * native output to compare against.
 */
object RgbToI420Verify {

    /**
     * Whether this frame should be dual-run. True exactly once per export:
     * on the first frame that came from the native path ([fromNative] true,
     * [nativeFramesSeen] 0 — the count BEFORE this frame).
     */
    fun shouldDualRun(nativeFramesSeen: Int, fromNative: Boolean): Boolean =
        fromNative && nativeFramesSeen == 0

    /**
     * The line logged after a dual-run, or null when nothing should be
     * logged (the sizes differ — a bug that cannot happen: both paths
     * produce i420PackedSize bytes — but a missing line is better than a
     * crash mid-export).
     */
    fun line(
        width: Int,
        height: Int,
        native: ByteArray,
        kotlin: ByteArray,
    ): String? {
        if (native.size != kotlin.size) return null
        var mismatch = -1
        for (i in native.indices) {
            if (native[i] != kotlin[i]) {
                mismatch = i
                break
            }
        }
        return if (mismatch < 0) {
            "rgbToI420 dual-run ${width}x$height: native == kotlin (${native.size} bytes)"
        } else {
            "rgbToI420 dual-run ${width}x$height: MISMATCH at byte $mismatch of ${native.size} " +
                "(native=${native[mismatch]} kotlin=${kotlin[mismatch]})"
        }
    }
}
