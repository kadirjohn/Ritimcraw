package com.ritim.ritimcraw.ui.editor

/**
 * Pure 256-bin luma histogram + clipping counts from a rendered frame's
 * packed 8-bit ARGB pixels (owner feature list W5), JVM-testable without
 * Compose or the native lib: the screen feeds it the CURRENT displayed
 * frame (recomputed on each frame publish) and draws the result as a
 * small overlay; the clipping badge shows when either end clips.
 *
 * Luma: Rec.709 (BT.709) integer approximation
 *     Y = (54*R + 183*G + 19*B) >> 8
 * The weights are 0.2126/0.7152/0.0722 scaled by 256 and rounded; the
 * >> 8 keeps the whole pass in int arithmetic (no float per pixel) and
 * saturates at exactly 255 for white (54+183+19 = 256 -> 256*255 >> 8 =
 * 255), so pure white lands in bin 255 and pure black in bin 0.
 *
 * Sampling: a strided pass over the pixel array, stride = ceil(count /
 * [MAX_SAMPLES]) so at most MAX_SAMPLES pixels are read regardless of
 * the frame's resolution (a full-resolution paused frame is ~8M pixels;
 * 65536 samples keep the pass sub-millisecond while the histogram shape
 * stays stable). The stride is documented here because it defines the
 * clip threshold's denominator (see [isClipped]).
 *
 * Clipping: a pixel clips when its luma is <= [BLACK_CLIP_LUMA] (2) or
 * >= [WHITE_CLIP_LUMA] (253) — the two outermost bins of the 8-bit range
 * where detail is unrecoverable. The badge threshold is
 * [CLIP_FRACTION] (0.1%) of the SAMPLED pixels per end: one end's count
 * alone must exceed it (a single blown highlight in a dark frame does
 * not warn), and the threshold is per-end so black and white clipping
 * are reported independently.
 */
object FrameHistogram {

    /** Histogram bins: one per 8-bit luma value. */
    const val BINS = 256

    /**
     * Sampling cap: at most this many pixels are read per frame,
     * regardless of resolution (see the object KDoc).
     */
    const val MAX_SAMPLES = 65_536

    /** Luma at or below this counts as black clipping (bin 0..2). */
    const val BLACK_CLIP_LUMA = 2

    /** Luma at or above this counts as white clipping (bin 253..255). */
    const val WHITE_CLIP_LUMA = 253

    /**
     * The clipping badge threshold as a fraction of sampled pixels,
     * per end: 0.1% (documented choice — small enough to catch a real
     * blown area, large enough that a handful of specular pixels in an
     * otherwise clean frame do not warn).
     */
    const val CLIP_FRACTION = 0.001f

    /** The result of one histogram pass: bins plus both clip counts. */
    data class Result(
        /** 256 luma bins, each the count of sampled pixels in that bin. */
        val bins: IntArray,
        /** Sampled pixels with luma <= [BLACK_CLIP_LUMA]. */
        val blackClipped: Int,
        /** Sampled pixels with luma >= [WHITE_CLIP_LUMA]. */
        val whiteClipped: Int,
        /** The number of pixels actually sampled (the thresholds' denominator). */
        val sampled: Int,
    ) {
        /**
         * True when either end clips beyond [CLIP_FRACTION] of the
         * sampled pixels — the badge condition.
         */
        fun isClipped(): Boolean =
            sampled > 0 && (
                blackClipped >= clipThreshold(sampled) ||
                    whiteClipped >= clipThreshold(sampled)
            )

        override fun equals(other: Any?): Boolean =
            other is Result && other.blackClipped == blackClipped &&
                other.whiteClipped == whiteClipped && other.sampled == sampled &&
                other.bins.contentEquals(bins)

        override fun hashCode(): Int =
            31 * (31 * (31 * blackClipped + whiteClipped) + sampled) + bins.contentHashCode()
    }

    /**
     * The per-end clipping threshold in pixels: [CLIP_FRACTION] of the
     * sampled count, at least one pixel (an ultra-dark frame where every
     * sample clips still warns), computed in float and rounded to the
     * nearest whole pixel.
     */
    fun clipThreshold(sampled: Int): Int =
        maxOf(1, Math.round(sampled * CLIP_FRACTION))

    /**
     * One strided pass over [argb] (packed 0xAARRGGBB, row-major): the
     * 256-bin luma histogram plus both clip counts. A null/empty array
     * yields an all-zero result (nothing on screen — the overlay draws
     * nothing rather than crashing).
     */
    fun of(argb: IntArray?): Result {
        if (argb == null || argb.isEmpty()) return Result(IntArray(BINS), 0, 0, 0)
        val stride = ((argb.size + MAX_SAMPLES - 1) / MAX_SAMPLES).coerceAtLeast(1)
        val bins = IntArray(BINS)
        var black = 0
        var white = 0
        var sampled = 0
        var i = 0
        while (i < argb.size) {
            val p = argb[i]
            // Rec.709 luma, integer weights (see the object KDoc). The
            // alpha byte is ignored: the renderer writes opaque pixels.
            val luma = (54 * ((p shr 16) and 0xFF) +
                183 * ((p shr 8) and 0xFF) +
                19 * (p and 0xFF)) shr 8
            bins[luma]++
            if (luma <= BLACK_CLIP_LUMA) black++
            if (luma >= WHITE_CLIP_LUMA) white++
            sampled++
            i += stride
        }
        return Result(bins, black, white, sampled)
    }
}
