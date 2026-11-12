package com.ritim.ritimcraw.engine

/**
 * PRE-DEVELOP RAW CFA histogram statistics of ONE frame (feature N6): the
 * sensor-domain histogram of the pristine CFA, mapped from the JNI
 * bridge's compact jintArray. Pure and JVM-testable without the native
 * lib — [fromJni] is the single construction path the session uses, and
 * it never fabricates: a wrong layout version or length is rejected with
 * null (the caller reports a failure instead of guessing fields).
 *
 * PRE-DEVELOP semantics (the domain contract): these stats describe the
 * RAW sensor data BEFORE the develop pipeline. They do NOT move with
 * exposure/WB/LUT/grade sliders; they change only with the frame. RAW
 * clipping here is SENSOR-domain clipping — CFA sites at or beyond the
 * RAW white/black levels — and is never compared with, converted to, or
 * presented as the processed image's clipping (the owner's two-domain
 * rule: each domain gets its own honest indicators).
 *
 * Channels: the CFA's three site colors (R, G, B). The G histogram
 * covers BOTH green sites of the 2x2 cell (they share one curve, the
 * same convention the develop stage's per-slot normalization feeds);
 * R and B cover their single site each. Bins are the develop stage's
 * normalization: a site at RAW value v with per-slot black b and white
 * w lands in bin floor(clamp((v - b) / (w - b)) * 256) — the same
 * mapping the develop stage applies, so a sample the develop would
 * normalize to X appears in the bin that corresponds to X.
 *
 * Clipping: the counts are EXACT sensor clips (sites at or above the
 * white level actually used / at or below the per-slot black level
 * actually used), not bin-edge approximations. The indicator
 * conditions apply the same 0.1%-of-samples discipline as
 * [com.ritim.ritimcraw.ui.editor.FrameHistogram]'s processed-image
 * indicators — the thresholds are intentionally identical, but the
 * DOMAINS never mix: a RAW clip never lights the processed "Clipped"
 * badge and a processed clip never lights the RAW badge.
 */
class RawHistogram(
    /** The frame the stats describe (the stale guard's key). */
    val frameIndex: Int,
    /** The frame's CFA width in sites. */
    val width: Int,
    /** The frame's CFA height in sites. */
    val height: Int,
    /** 256 red-site bins (the develop normalization, see the class KDoc). */
    val redBins: IntArray,
    /** 256 green-site bins (BOTH green sites of the cell). */
    val greenBins: IntArray,
    /** 256 blue-site bins. */
    val blueBins: IntArray,
    /**
     * RAW white-clip counts indexed by [Channel.ordinal]: sites at or
     * above the white level actually used (sensor domain).
     */
    val whiteClips: IntArray,
    /**
     * RAW black-clip counts indexed by [Channel.ordinal]: sites at or
     * below the per-slot black level actually used (sensor domain).
     */
    val blackClips: IntArray,
    /** Sites counted per channel (the indicator thresholds' denominator). */
    val samples: IntArray,
    /**
     * The per-slot black levels ACTUALLY used (the frame's dynamic
     * levels when it carried them, the container's otherwise — the
     * develop stage's all-or-nothing fallback, resolved), in cell-slot
     * order (r, g1, g2, b). Reported so the UI can label the domain
     * honestly instead of guessing which levels produced the bins.
     */
    val blackUsed: IntArray,
    /** The white level ACTUALLY used (see [blackUsed]). */
    val whiteUsed: Int,
) {

    /** The three CFA site colors; the ordinal doubles as the JNI channel index. */
    enum class Channel { RED, GREEN, BLUE }

    /** The 256-bin histogram of [channel]. */
    fun bins(channel: Channel): IntArray = when (channel) {
        Channel.RED -> redBins
        Channel.GREEN -> greenBins
        Channel.BLUE -> blueBins
    }

    /** [channel]'s exact RAW white-clip count (sensor domain). */
    fun whiteClipped(channel: Channel): Int = whiteClips[channel.ordinal]

    /** [channel]'s exact RAW black-clip count (sensor domain). */
    fun blackClipped(channel: Channel): Int = blackClips[channel.ordinal]

    /**
     * [channel]'s highlight end clips in the SENSOR domain beyond
     * [CLIP_FRACTION] of its samples — the RAW indicator condition,
     * per channel per end (the same 0.1% discipline as the processed
     * histogram's indicators; never mixed with them).
     */
    fun isWhiteClipped(channel: Channel): Boolean =
        samples[channel.ordinal] > 0 &&
            whiteClips[channel.ordinal] >= clipThreshold(samples[channel.ordinal])

    /**
     * [channel]'s shadow end clips in the SENSOR domain beyond
     * [CLIP_FRACTION] of its samples — see [isWhiteClipped].
     */
    fun isBlackClipped(channel: Channel): Boolean =
        samples[channel.ordinal] > 0 &&
            blackClips[channel.ordinal] >= clipThreshold(samples[channel.ordinal])

    /**
     * True when ANY channel clips beyond [CLIP_FRACTION] on either end
     * — the RAW badge condition (sensor domain only; the processed
     * image's badge is computed from its own histogram, never from
     * these counts).
     */
    fun isClipped(): Boolean =
        Channel.entries.any { isWhiteClipped(it) || isBlackClipped(it) }

    override fun equals(other: Any?): Boolean =
        other is RawHistogram && other.frameIndex == frameIndex &&
            other.width == width && other.height == height &&
            other.whiteUsed == whiteUsed &&
            other.redBins.contentEquals(redBins) &&
            other.greenBins.contentEquals(greenBins) &&
            other.blueBins.contentEquals(blueBins) &&
            other.whiteClips.contentEquals(whiteClips) &&
            other.blackClips.contentEquals(blackClips) &&
            other.samples.contentEquals(samples) &&
            other.blackUsed.contentEquals(blackUsed)

    override fun hashCode(): Int {
        var hash = frameIndex
        hash = 31 * hash + width
        hash = 31 * hash + height
        hash = 31 * hash + whiteUsed
        hash = 31 * hash + redBins.contentHashCode()
        hash = 31 * hash + greenBins.contentHashCode()
        hash = 31 * hash + blueBins.contentHashCode()
        hash = 31 * hash + whiteClips.contentHashCode()
        hash = 31 * hash + blackClips.contentHashCode()
        hash = 31 * hash + samples.contentHashCode()
        hash = 31 * hash + blackUsed.contentHashCode()
        return hash
    }

    companion object {

        /** Histogram bins: one per develop-normalized value, per channel. */
        const val BINS = 256

        /**
         * The RAW clipping indicator threshold as a fraction of the
         * channel's sites, per end: 0.1% — the same documented choice
         * as the processed histogram's FrameHistogram.CLIP_FRACTION
         * (the two disciplines are intentionally identical; the
         * domains they measure never mix).
         */
        const val CLIP_FRACTION = 0.001f

        /**
         * The jintArray layout version [fromJni] accepts. A mismatch
         * (the bridge and this mapper drifted apart) is rejected with
         * null — never guess a field layout.
         */
        const val JNI_VERSION = 1

        /**
         * The jintArray length [fromJni] accepts (the bridge's packed
         * layout, see nativeRawHistogram in the bridge for the C++
         * side):
         *  * `[0]` layout version ([JNI_VERSION])
         *  * `[1]` frame index
         *  * `[2]`, `[3]` CFA width, height (sites)
         *  * `[4 .. 771]` bins, channel-major: `[4 + ch*256 + b]`
         *  * `[772 .. 774]` RAW white-clip counts (ch 0..2)
         *  * `[775 .. 777]` RAW black-clip counts (ch 0..2)
         *  * `[778 .. 780]` samples per channel (G = both green sites)
         *  * `[781 .. 784]` black levels actually used, cell-slot order
         *  * `[785]` white level actually used
         *  * `[786]` reserved (0)
         */
        const val JNI_LENGTH = 787

        /**
         * The per-end RAW clipping threshold in sites: [CLIP_FRACTION]
         * of the channel's samples, at least one site, rounded to the
         * nearest whole site — the same formula as the processed
         * histogram's FrameHistogram.clipThreshold.
         */
        fun clipThreshold(samples: Int): Int =
            maxOf(1, Math.round(samples * CLIP_FRACTION))

        /**
         * Maps the bridge's packed stats array to a [RawHistogram].
         * PURE: copies every field out of [a], never fabricates — a
         * wrong length or layout version returns null and the caller
         * reports a failure (a drifted contract is surfaced, never
         * papered over with guessed fields).
         */
        fun fromJni(a: IntArray): RawHistogram? {
            if (a.size != JNI_LENGTH) return null
            if (a[0] != JNI_VERSION) return null
            // The trailer starts where the bins end: 4 header + 3*256
            // bins = 772. Derived here the same way the bridge derives
            // it, so the two stay in lockstep.
            val t = 4 + 3 * BINS
            return RawHistogram(
                frameIndex = a[1],
                width = a[2],
                height = a[3],
                redBins = a.copyOfRange(4, 4 + BINS),
                greenBins = a.copyOfRange(4 + BINS, 4 + 2 * BINS),
                blueBins = a.copyOfRange(4 + 2 * BINS, t),
                whiteClips = a.copyOfRange(t, t + 3),
                blackClips = a.copyOfRange(t + 3, t + 6),
                samples = a.copyOfRange(t + 6, t + 9),
                blackUsed = a.copyOfRange(t + 9, t + 13),
                whiteUsed = a[t + 13],
            )
        }
    }
}
