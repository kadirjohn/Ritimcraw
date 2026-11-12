package com.ritim.ritimcraw.ui.editor

/**
 * Pure 256-bin RGB + luma histograms and per-slot clipping counts from a
 * rendered frame's packed 8-bit ARGB pixels (owner feature list W5; the
 * RGB extension of the owner's 2026-11-11 histogram spec), JVM-testable
 * without Compose or the native lib: the screen feeds it the CURRENT
 * displayed frame (recomputed on each frame publish) and the overlay
 * draws the result; the clipping indicators show when any channel or
 * the luma clips beyond the documented threshold.
 *
 * ONE strided pass produces FOUR histograms — red, green, blue and the
 * Rec.709 luma — plus per-slot clip counts, all from the SAME sampled
 * pixels, so the four curves always describe the same frame.
 *
 * Channels: each channel's histogram indexes that channel's OWN 8-bit
 * value (red bins by R, green by G, blue by B) — the classic RGB
 * histogram shape where a neutral gray frame makes all four curves
 * identical.
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
 * clip threshold's denominator (see [Result.isClipped]).
 *
 * Clipping: a slot (channel or luma) clips when its value is <=
 * [BLACK_CLIP_VALUE] (2) or >= [WHITE_CLIP_VALUE] (253) — the same two
 * outermost-bins thresholds for the three channels AND the luma, the
 * 8-bit range ends where detail is unrecoverable. Per-slot thresholds
 * matter because a channel can clip while the luma does not: a
 * saturated red (255, 0, 0) blows the red channel at 255 while its
 * luma is only 54*255 >> 8 = 53. (The reverse is impossible — the
 * integer luma can only reach an end when some channel does — but the
 * counts are kept per slot anyway so the indicators can name the exact
 * channel(s).) The badge threshold is [CLIP_FRACTION] (0.1%) of the
 * SAMPLED pixels per end per slot: one slot's count alone must exceed
 * it, evaluated per end so shadow and highlight clipping are reported
 * independently.
 */
object FrameHistogram {

    /** Histogram bins: one per 8-bit value, per channel. */
    const val BINS = 256

    /**
     * Sampling cap: at most this many pixels are read per frame,
     * regardless of resolution (see the object KDoc).
     */
    const val MAX_SAMPLES = 65_536

    /** Channel value or luma at or below this counts as black clipping. */
    const val BLACK_CLIP_VALUE = 2

    /** Channel value or luma at or above this counts as white clipping. */
    const val WHITE_CLIP_VALUE = 253

    /**
     * The clipping indicator threshold as a fraction of sampled pixels,
     * per end per slot: 0.1% (documented choice — small enough to catch a
     * real blown area, large enough that a handful of specular pixels in
     * an otherwise clean frame do not warn).
     */
    const val CLIP_FRACTION = 0.001f

    /**
     * The four histogram slots: the three color channels plus the luma
     * curve. The ordinal doubles as the clip-count arrays' index (see
     * [Result]); RGB order matches the parade view and the overlay's
     * channel colors.
     */
    enum class Channel { RED, GREEN, BLUE, LUMA }

    /** The result of one pass: four 256-bin histograms plus clip counts. */
    data class Result(
        /** 256 red bins, each the count of sampled pixels with that R value. */
        val redBins: IntArray,
        /** 256 green bins, each the count of sampled pixels with that G value. */
        val greenBins: IntArray,
        /** 256 blue bins, each the count of sampled pixels with that B value. */
        val blueBins: IntArray,
        /** 256 luma bins, each the count of sampled pixels with that Rec.709 luma. */
        val lumaBins: IntArray,
        /** Black-clip counts indexed by [Channel.ordinal]: [red, green, blue, luma]. */
        val blackClipCounts: IntArray,
        /** White-clip counts indexed by [Channel.ordinal]: [red, green, blue, luma]. */
        val whiteClipCounts: IntArray,
        /** The number of pixels actually sampled (the thresholds' denominator). */
        val sampled: Int,
    ) {
        /** The 256-bin histogram of [channel]. */
        fun bins(channel: Channel): IntArray = when (channel) {
            Channel.RED -> redBins
            Channel.GREEN -> greenBins
            Channel.BLUE -> blueBins
            Channel.LUMA -> lumaBins
        }

        /** Sampled pixels of [channel] at or below [BLACK_CLIP_VALUE]. */
        fun blackClipped(channel: Channel): Int = blackClipCounts[channel.ordinal]

        /** Sampled pixels of [channel] at or above [WHITE_CLIP_VALUE]. */
        fun whiteClipped(channel: Channel): Int = whiteClipCounts[channel.ordinal]

        /**
         * [channel]'s shadow end clips beyond [CLIP_FRACTION] of the
         * sampled pixels — the indicator condition, per slot per end.
         */
        fun isBlackClipped(channel: Channel): Boolean =
            sampled > 0 && blackClipCounts[channel.ordinal] >= clipThreshold(sampled)

        /**
         * [channel]'s highlight end clips beyond [CLIP_FRACTION] of the
         * sampled pixels — the indicator condition, per slot per end.
         */
        fun isWhiteClipped(channel: Channel): Boolean =
            sampled > 0 && whiteClipCounts[channel.ordinal] >= clipThreshold(sampled)

        /**
         * True when ANY slot (R, G, B or luma) clips beyond [CLIP_FRACTION]
         * on either end — the badge condition. Channel clipping counts
         * even when the luma alone stays clean: a saturated red frame's
         * luma sits mid-scale, but its red channel is fully blown.
         */
        fun isClipped(): Boolean =
            sampled > 0 && Channel.entries.any { isBlackClipped(it) || isWhiteClipped(it) }

        override fun equals(other: Any?): Boolean =
            other is Result && other.sampled == sampled &&
                other.blackClipCounts.contentEquals(blackClipCounts) &&
                other.whiteClipCounts.contentEquals(whiteClipCounts) &&
                other.redBins.contentEquals(redBins) &&
                other.greenBins.contentEquals(greenBins) &&
                other.blueBins.contentEquals(blueBins) &&
                other.lumaBins.contentEquals(lumaBins)

        override fun hashCode(): Int {
            var hash = sampled
            hash = 31 * hash + blackClipCounts.contentHashCode()
            hash = 31 * hash + whiteClipCounts.contentHashCode()
            hash = 31 * hash + redBins.contentHashCode()
            hash = 31 * hash + greenBins.contentHashCode()
            hash = 31 * hash + blueBins.contentHashCode()
            hash = 31 * hash + lumaBins.contentHashCode()
            return hash
        }
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
     * The Rec.709 luma of one 8-bit pixel channel triple — the shared
     * definition behind the luma histogram, the LUMA clip counts and the
     * zebra overlay's per-pixel clip decision (PLAY-11: the zebra and the
     * clip pills must agree, so there is exactly ONE luma function, here).
     * Integer weights (see the object KDoc): 0.2126/0.7152/0.0722 scaled by
     * 256 and rounded, `shr 8` to stay in int arithmetic.
     */
    fun luma(r: Int, g: Int, b: Int): Int = (54 * r + 183 * g + 19 * b) shr 8

    /**
     * One strided pass over [argb] (packed 0xAARRGGBB, row-major): the
     * four 256-bin histograms plus per-slot clip counts, all from the
     * same sampled pixels. A null/empty array yields an all-zero result
     * (nothing on screen — the overlay draws nothing rather than
     * crashing).
     */
    fun of(argb: IntArray?): Result {
        if (argb == null || argb.isEmpty()) {
            return Result(
                IntArray(BINS), IntArray(BINS), IntArray(BINS), IntArray(BINS),
                IntArray(Channel.entries.size), IntArray(Channel.entries.size), 0,
            )
        }
        val stride = ((argb.size + MAX_SAMPLES - 1) / MAX_SAMPLES).coerceAtLeast(1)
        val red = IntArray(BINS)
        val green = IntArray(BINS)
        val blue = IntArray(BINS)
        val luma = IntArray(BINS)
        val black = IntArray(Channel.entries.size)
        val white = IntArray(Channel.entries.size)
        var sampled = 0
        var i = 0
        while (i < argb.size) {
            val p = argb[i]
            // The alpha byte is ignored: the renderer writes opaque pixels.
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            red[r]++
            green[g]++
            blue[b]++
            if (r <= BLACK_CLIP_VALUE) black[Channel.RED.ordinal]++
            if (g <= BLACK_CLIP_VALUE) black[Channel.GREEN.ordinal]++
            if (b <= BLACK_CLIP_VALUE) black[Channel.BLUE.ordinal]++
            if (r >= WHITE_CLIP_VALUE) white[Channel.RED.ordinal]++
            if (g >= WHITE_CLIP_VALUE) white[Channel.GREEN.ordinal]++
            if (b >= WHITE_CLIP_VALUE) white[Channel.BLUE.ordinal]++
            // Rec.709 luma — the shared definition ([luma]), so the
            // histogram's luma curve, the clip counts and the zebra
            // overlay's decision can never drift apart.
            val y = luma(r, g, b)
            luma[y]++
            if (y <= BLACK_CLIP_VALUE) black[Channel.LUMA.ordinal]++
            if (y >= WHITE_CLIP_VALUE) white[Channel.LUMA.ordinal]++
            sampled++
            i += stride
        }
        return Result(red, green, blue, luma, black, white, sampled)
    }
}
