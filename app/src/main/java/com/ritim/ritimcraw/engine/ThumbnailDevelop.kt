package com.ritim.ritimcraw.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.PngWriter
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * The LIGHT automatic balance for the MCRAW import thumbnail (owner
 * request 2026-11-14: "Thumbnail'in güzel görünmesi için histogram
 * kontrol edilir, gerekirse highlight kısılıp shadow'lar artırılır —
 * süper dengeleme gerekmez, azıcık yeterli"). Pure Kotlin,
 * JVM-testable: it computes bounded GradeK values from the developed
 * frame's luma histogram; the tone math itself is the EXISTING native
 * stages (15 highlights recovery / 11 shadows lift) applied by the
 * re-develop — no new tone curve is invented here.
 *
 * Thresholds (documented; the identity is the common case):
 *  - HIGH end crowded when > 2% of pixels sit above 0.95 luma (bin
 *    >= 243 of 255): highlights recovery in [-25, -10], linear in the
 *    crowd fraction over [2%, 10%] (just over 2% -> -10, >= 10% ->
 *    -25) — stage 15's s = -highlights/100 therefore stays in
 *    [0.10, 0.25], the brief's small-shoulder range.
 *  - LOW end crowded when > 2% of pixels sit below 0.05 luma (bin
 *    <= 12 of 255): shadows lift in [+10, +25], the same linear map —
 *    stage 11's strength = shadows/100 stays in [0.10, 0.25].
 *  - BOTH ends crowded: both controls apply but each is capped at 20,
 *    below the single-end ±25 maximum — NEVER both extremes (the
 *    brief's bound; a frame that needs both ends moved gets a gentler
 *    touch on each).
 *  - BALANCED (<= 2% on both ends): the neutral grade — the develop
 *    already made is kept, no second pass.
 */
object ThumbnailBalance {

    /** Luma bin (of 255) at/above which a pixel counts as highlight-crowded: 243/255 >= 0.95. */
    const val HIGH_BIN = 243

    /** Luma bin (of 255) at/below which a pixel counts as shadow-crowded: 12/255 < 0.05. */
    const val LOW_BIN = 12

    /** The crowd fraction (of all pixels) above which an end counts as crowded. */
    const val CROWD_FRACTION = 0.02

    /** The crowd fraction at which the control saturates at its bound. */
    const val FULL_CROWD_FRACTION = 0.10

    /** The smallest strength applied when an end is crowded (stage s = 0.10). */
    const val MIN_STRENGTH = 10f

    /** The largest strength applied on one control (the brief's ±25 bound). */
    const val MAX_STRENGTH = 25f

    /** The cap on each control when BOTH ends are crowded (never both extremes). */
    const val BOTH_CROWD_CAP = 20f

    /**
     * The 256-bin BT.709 luma histogram of a developed RGB888 buffer
     * (row-major, 3 bytes per pixel — the native renderFrame layout).
     * Integer luma: y = (2126r + 7152g + 722b) / 10000, 0..255.
     */
    fun lumaHistogram(rgb: ByteArray): IntArray {
        val hist = IntArray(256)
        var i = 0
        while (i + 2 < rgb.size) {
            val y = (2126 * (rgb[i].toInt() and 0xFF) +
                7152 * (rgb[i + 1].toInt() and 0xFF) +
                722 * (rgb[i + 2].toInt() and 0xFF)) / 10000
            hist[y]++
            i += 3
        }
        return hist
    }

    /** The fraction of pixels at/above [HIGH_BIN] (0 for an empty histogram). */
    fun highFraction(hist: IntArray): Double {
        val total = hist.sum()
        if (total == 0) return 0.0
        var crowded = 0L
        for (b in HIGH_BIN..255) crowded += hist[b]
        return crowded.toDouble() / total
    }

    /** The fraction of pixels at/below [LOW_BIN] (0 for an empty histogram). */
    fun lowFraction(hist: IntArray): Double {
        val total = hist.sum()
        if (total == 0) return 0.0
        var crowded = 0L
        for (b in 0..LOW_BIN) crowded += hist[b]
        return crowded.toDouble() / total
    }

    /**
     * The bounded balance decision for a developed frame's luma
     * [hist]: a NEUTRAL grade (all defaults — the identity) when both
     * ends are within [CROWD_FRACTION], else a grade with ONLY
     * highlights (negative) and/or shadows (positive) set, per the
     * object KDoc's bounds.
     */
    fun gradeFor(hist: IntArray): GradeK {
        val high = strength(highFraction(hist))
        val low = strength(lowFraction(hist))
        if (high == 0f && low == 0f) return GradeK()
        var highlights = -high
        var shadows = low
        if (highlights != 0f && shadows != 0f) {
            // Never both extremes: both ends crowded caps each control
            // below the single-end maximum.
            highlights = maxOf(highlights, -BOTH_CROWD_CAP)
            shadows = minOf(shadows, BOTH_CROWD_CAP)
        }
        return GradeK(highlights = highlights, shadows = shadows)
    }

    /**
     * The crowd fraction mapped onto [MIN_STRENGTH, MAX_STRENGTH]; 0 at
     * or under the threshold (the identity), saturated at
     * [FULL_CROWD_FRACTION].
     */
    private fun strength(fraction: Double): Float {
        if (fraction <= CROWD_FRACTION) return 0f
        val t = ((fraction - CROWD_FRACTION) /
            (FULL_CROWD_FRACTION - CROWD_FRACTION)).coerceIn(0.0, 1.0)
        return (MIN_STRENGTH + (MAX_STRENGTH - MIN_STRENGTH) * t).toFloat()
    }
}

/**
 * The icon decode sizing math (pure, JVM-testable): the full-res JPEG
 * master is NEVER decoded at full size into a list row — the canonical
 * power-of-2 inSampleSize picks the decode sample, and the exact-scale
 * decision bounds the persisted icon at the row's target.
 */
object ThumbnailIcon {

    /**
     * BitmapFactory's canonical power-of-2 sample for a [width]x[height]
     * master decoded towards [reqWidth]x[reqHeight]: the largest power
     * of two whose halves still cover the request (both edges). 1 when
     * the master is already within the request — never an upscale.
     */
    fun inSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        while (halfWidth / sample >= reqWidth && halfHeight / sample >= reqHeight) {
            sample *= 2
        }
        return sample
    }

    /**
     * The icon's exact dims for a [width]x[height] decoded master whose
     * long edge must fit [targetDim]: null when it already fits (no
     * scale step), else the long-edge-proportional pair (each >= 1).
     */
    fun iconDims(width: Int, height: Int, targetDim: Int): Pair<Int, Int>? {
        val longEdge = maxOf(width, height)
        if (longEdge <= targetDim) return null
        val w = maxOf(1, (width.toDouble() * targetDim / longEdge).roundToInt())
        val h = maxOf(1, (height.toDouble() * targetDim / longEdge).roundToInt())
        return w to h
    }
}

/**
 * The JPEG master write boundary (the [PngWriter] precedent, JPEG
 * instead): an interface so the thumbnail pipeline is JVM-testable with
 * a fake — the real writer needs android.graphics.Bitmap (a
 * device-gated path, exactly like the photo export's PNG adapter).
 */
interface JpegWriter {

    /**
     * Writes packed 0xFFRRGGBB pixels ([argb], [width] x [height]) as a
     * JPEG at [outPath]. True on success.
     */
    fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean
}

/**
 * The icon extraction boundary: decode the JPEG master scaled down
 * (inSampleSize — never the full-res bitmap into a row) and persist the
 * small icon file. An interface for the same JVM-test reason as
 * [JpegWriter].
 */
interface IconExtractor {

    /**
     * Extracts the icon (long edge <= [targetDim]) from the JPEG master
     * at [masterPath] into [outPath]. True on success.
     */
    fun extract(masterPath: String, outPath: String, targetDim: Int): Boolean
}

/**
 * The real [JpegWriter]: the BitmapPngWriter precedent, JPEG instead —
 * the platform encoder at quality ~90 (the owner's "JPG olarak
 * kaydedilir"), the ONLY Android-framework class on the master side of
 * the MCRAW thumbnail path. Device-gated like every Bitmap adapter;
 * every decision around it lives in the JVM-tested pure objects above.
 */
internal class BitmapJpegWriter(private val quality: Int = 90) : JpegWriter {

    override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        return try {
            FileOutputStream(outPath).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        } catch (e: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * The real [IconExtractor]: two-pass BitmapFactory decode of the JPEG
 * master (bounds first, then the sampled decode — the full-res bitmap
 * is never materialized into a row), the exact scale to the icon
 * target, and the icon persisted through the SAME [PngWriter] the
 * encoded path uses. The icon file keeps the `<id>.png` name every
 * build has persisted, so icons that already exist are never
 * re-developed.
 */
internal class BitmapIconExtractor(private val png: PngWriter) : IconExtractor {

    override fun extract(masterPath: String, outPath: String, targetDim: Int): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(masterPath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ThumbnailIcon.inSampleSize(
                    bounds.outWidth, bounds.outHeight, targetDim, targetDim,
                )
            }
            val decoded = BitmapFactory.decodeFile(masterPath, opts) ?: return false
            try {
                val dims = ThumbnailIcon.iconDims(decoded.width, decoded.height, targetDim)
                val icon = if (dims != null) {
                    Bitmap.createScaledBitmap(decoded, dims.first, dims.second, true)
                } else {
                    decoded
                }
                try {
                    val pixels = IntArray(icon.width * icon.height)
                    icon.getPixels(pixels, 0, icon.width, 0, 0, icon.width, icon.height)
                    png.write(pixels, icon.width, icon.height, outPath)
                } finally {
                    if (icon !== decoded) icon.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }
}
