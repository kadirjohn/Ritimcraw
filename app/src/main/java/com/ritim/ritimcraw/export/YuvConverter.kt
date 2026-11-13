package com.ritim.ritimcraw.export

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
 * The pipeline's RGB888 -> I420 conversion seam: the pipeline calls through
 * this so the encode loop stays JVM-testable while production dispatches to
 * the native twin (Mp4Exporter's NativeRgbToI420). The result carries the
 * bytes' ORIGIN (native vs Kotlin) so the pipeline's debug dual-run knows
 * when it has a native frame to compare against — without sniffing the
 * implementation. The BYTES are identical either way; that is the seam's
 * whole contract (core/src/Yuv.cpp).
 */
fun interface RgbToI420 {
    /** Converts packed row-major RGB888 to tightly packed I420 (toI420 layout). */
    fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result
}

/** One conversion outcome: the bytes plus where they came from. */
class RgbToI420Result(
    /** The tightly packed I420 frame (YuvConverter.toI420 layout). */
    val i420: ByteArray,
    /** True when [i420] came from the native twin, false from Kotlin. */
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
