package com.ritim.ritimcraw.engine

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Frame timing analysis (şartname PLAY-13: "Kare zamanlaması analizi.
 * Kaynak kare boşluğu, decoder gecikmesi, görüntülenemeyen kare ve audio
 * underrun sayımları ayrı. Stutter'ın hangi aşamada oluştuğunu anlamak için
 * giriş ve çıktı PTS özeti ver."). Landed 2026-11-23 as two pure pieces:
 *
 *  1. [FrameTiming.analyze] — the INPUT side: what the SOURCE's own
 *     presentation timestamps say (gaps, duplicates/out-of-order, irregular
 *     intervals, measured vs nominal fps, VFR). A stutter that is already
 *     in the source PTS is a SOURCE fact, not a playback bug.
 *  2. [PlaybackTimingCounters] — the OUTPUT side: an accumulator the
 *     playback loop can feed (presented / late / dropped frames, decoder
 *     latency, audio underruns), each counted SEPARATELY as the clause
 *     demands, so a stutter report can say which stage it came from.
 *
 * Both are pure Kotlin (no Android types) and JVM-tested; wiring the
 * counters into EditorPreviewSession/EditorAudio and showing the summary
 * (e.g. in the DEBUG trace or the device report) is the integrator's.
 *
 * HONESTY: every rate is derived from REAL timestamps (the zaman modeli
 * rule: "VFR için kare sayısı × nominal süre hesabı yerine gerçek zaman
 * damgaları kullanılır"); a clip with fewer than two frames has no
 * interval and reports null rates, never a guess.
 */
object FrameTiming {

    /**
     * An interval longer than this multiple of the median interval is a
     * GAP (a missing/dropped source frame); the missing count is
     * round(delta / median) - 1. 1.5 is the midpoint between "one frame"
     * and "two frames" — the honest boundary for "at least one missing".
     */
    const val GAP_FACTOR = 1.5

    /**
     * An interval whose deviation from the median exceeds this fraction
     * (but is not a gap and not a duplicate) is IRREGULAR — jitter that a
     * VFR source shows and a CFR source should not.
     */
    const val IRREGULAR_TOLERANCE = 0.25

    /** The source-side timing report. All durations in microseconds. */
    data class SourceTimingReport(
        val frameCount: Int,
        /** last - first PTS; 0 for a single frame; null for an empty clip. */
        val spanUs: Long?,
        /** The median positive interval, or null with fewer than two frames. */
        val medianIntervalUs: Long?,
        val minIntervalUs: Long?,
        val maxIntervalUs: Long?,
        /** (frames - 1) / span — the mean rate the real span implies. */
        val measuredFps: Double?,
        /** 1e6 / median interval — the rate the typical frame step implies. */
        val medianFps: Double?,
        /** The container's nominal fps as given (null = absent), echoed for the summary. */
        val nominalFps: Double?,
        /** Intervals > GAP_FACTOR x median. */
        val gapCount: Int,
        /** Estimated missing source frames across all gaps. */
        val missingFrames: Int,
        /** Intervals <= 0: repeated or out-of-order PTS (decode order leaking). */
        val nonIncreasingCount: Int,
        /** Positive, non-gap intervals deviating > IRREGULAR_TOLERANCE from the median. */
        val irregularCount: Int,
    ) {
        /** True when the source is variable-frame-rate by its own timestamps. */
        val isVfr: Boolean get() = irregularCount > 0

        /** One-line input-side summary for logs/diagnostics (not localized). */
        fun summary(): String = buildString {
            append("source frames=").append(frameCount)
            if (spanUs != null) append(" span=").append(spanUs).append("us")
            measuredFps?.let { append(" fps=").append(fmt(it)) }
            nominalFps?.let { append(" nominal=").append(fmt(it)) }
            medianIntervalUs?.let { append(" median=").append(it).append("us") }
            append(" gaps=").append(gapCount).append(" missing=").append(missingFrames)
            append(" nonIncreasing=").append(nonIncreasingCount)
            append(" irregular=").append(irregularCount)
            if (isVfr) append(" VFR")
        }
    }

    /** Analyzes the source's presentation timestamps (in source order). */
    fun analyze(ptsUs: LongArray, nominalFps: Double? = null): SourceTimingReport {
        val n = ptsUs.size
        val nominal = nominalFps?.takeIf { it > 0.0 && it.isFinite() }
        if (n < 2) {
            return SourceTimingReport(
                frameCount = n,
                spanUs = if (n == 1) 0L else null,
                medianIntervalUs = null, minIntervalUs = null, maxIntervalUs = null,
                measuredFps = null, medianFps = null, nominalFps = nominal,
                gapCount = 0, missingFrames = 0, nonIncreasingCount = 0, irregularCount = 0,
            )
        }
        val deltas = LongArray(n - 1) { ptsUs[it + 1] - ptsUs[it] }
        val positive = deltas.filter { it > 0 }.sorted()
        val nonIncreasing = deltas.count { it <= 0 }
        val median: Long? = if (positive.isEmpty()) {
            null
        } else if (positive.size % 2 == 1) {
            positive[positive.size / 2]
        } else {
            (positive[positive.size / 2 - 1] + positive[positive.size / 2]) / 2
        }
        var gaps = 0
        var missing = 0
        var irregular = 0
        if (median != null && median > 0) {
            for (d in deltas) {
                if (d <= 0) continue
                if (d > GAP_FACTOR * median) {
                    gaps++
                    missing += ((d.toDouble() / median).roundToLong() - 1).toInt().coerceAtLeast(1)
                } else if (abs(d - median) > IRREGULAR_TOLERANCE * median) {
                    irregular++
                }
            }
        }
        val span = ptsUs[n - 1] - ptsUs[0]
        return SourceTimingReport(
            frameCount = n,
            spanUs = span,
            medianIntervalUs = median,
            minIntervalUs = positive.firstOrNull(),
            maxIntervalUs = positive.lastOrNull(),
            measuredFps = if (span > 0) (n - 1) * 1_000_000.0 / span else null,
            medianFps = median?.takeIf { it > 0 }?.let { 1_000_000.0 / it },
            nominalFps = nominal,
            gapCount = gaps,
            missingFrames = missing,
            nonIncreasingCount = nonIncreasing,
            irregularCount = irregular,
        )
    }

    internal fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.3f", v)
}

/**
 * The OUTPUT-side counters (PLAY-13): what playback actually did, each
 * stage counted separately. Not thread-safe by design — one playback loop
 * owns one instance (the FpsWindow precedent); [snapshot] is a value copy.
 *
 * Feed it from the loop:
 *  - [onPresented] when a frame reaches the screen, with its source PTS and
 *    how late it was against its scheduled time (<= 0 = on time);
 *  - [onDropped] when a frame was decoded but never shown (superseded);
 *  - [onDecodeLatency] with the decode/develop duration of a frame;
 *  - [onAudioUnderrun] when the audio sink starved.
 */
class PlaybackTimingCounters(
    /** A presented frame later than this counts as LATE (a visible hitch). */
    private val lateThresholdUs: Long = 1_000_000L / 60,
) {
    private var presented = 0
    private var late = 0
    private var dropped = 0
    private var underruns = 0
    private var decodeCount = 0
    private var decodeSumUs = 0L
    private var decodeMaxUs = 0L
    private var maxLatenessUs = 0L
    private var firstPtsUs: Long? = null
    private var lastPtsUs: Long? = null
    private var outOfOrder = 0

    fun onPresented(sourcePtsUs: Long, latenessUs: Long) {
        presented++
        if (latenessUs > lateThresholdUs) late++
        if (latenessUs > maxLatenessUs) maxLatenessUs = latenessUs
        val prev = lastPtsUs
        if (prev != null && sourcePtsUs <= prev) outOfOrder++
        if (firstPtsUs == null) firstPtsUs = sourcePtsUs
        lastPtsUs = sourcePtsUs
    }

    fun onDropped() {
        dropped++
    }

    fun onDecodeLatency(us: Long) {
        if (us < 0) return
        decodeCount++
        decodeSumUs += us
        if (us > decodeMaxUs) decodeMaxUs = us
    }

    fun onAudioUnderrun() {
        underruns++
    }

    /** Clears everything (a new play run, or after a seek). */
    fun reset() {
        presented = 0; late = 0; dropped = 0; underruns = 0
        decodeCount = 0; decodeSumUs = 0; decodeMaxUs = 0; maxLatenessUs = 0
        firstPtsUs = null; lastPtsUs = null; outOfOrder = 0
    }

    data class Snapshot(
        val presented: Int,
        val late: Int,
        val dropped: Int,
        val audioUnderruns: Int,
        val decodeMeanUs: Long?,
        val decodeMaxUs: Long?,
        val maxLatenessUs: Long,
        val firstPresentedPtsUs: Long?,
        val lastPresentedPtsUs: Long?,
        /** Presented frames whose source PTS did not advance (shown out of order / repeated). */
        val outOfOrderPresented: Int,
    ) {
        /** One-line output-side summary for logs/diagnostics (not localized). */
        fun summary(): String = buildString {
            append("output presented=").append(presented)
            append(" late=").append(late)
            append(" dropped=").append(dropped)
            append(" audioUnderruns=").append(audioUnderruns)
            decodeMeanUs?.let { append(" decodeMean=").append(it).append("us") }
            decodeMaxUs?.let { append(" decodeMax=").append(it).append("us") }
            append(" maxLate=").append(maxLatenessUs).append("us")
            if (firstPresentedPtsUs != null && lastPresentedPtsUs != null) {
                append(" pts=").append(firstPresentedPtsUs).append("..").append(lastPresentedPtsUs)
            }
            append(" outOfOrder=").append(outOfOrderPresented)
        }

        /**
         * Which stage the stutter most plausibly came from, given the
         * source report (the clause's "hangi aşamada"): SOURCE when the
         * source PTS already has gaps/irregularity, DECODE when frames were
         * late and the mean decode exceeded the frame interval, AUDIO when
         * only the audio sink starved, PRESENT when frames were late or
         * dropped without a slow decode, NONE otherwise. A hint for the
         * report, never an automatic fix.
         */
        fun stutterStage(source: FrameTiming.SourceTimingReport): Stage {
            if (source.gapCount > 0 || source.nonIncreasingCount > 0) return Stage.SOURCE
            val interval = source.medianIntervalUs
            if ((late > 0 || dropped > 0) && interval != null &&
                decodeMeanUs != null && decodeMeanUs > interval
            ) return Stage.DECODE
            if (late > 0 || dropped > 0) return Stage.PRESENT
            if (audioUnderruns > 0) return Stage.AUDIO
            if (source.isVfr) return Stage.SOURCE
            return Stage.NONE
        }
    }

    enum class Stage { NONE, SOURCE, DECODE, PRESENT, AUDIO }

    fun snapshot(): Snapshot = Snapshot(
        presented = presented,
        late = late,
        dropped = dropped,
        audioUnderruns = underruns,
        decodeMeanUs = if (decodeCount > 0) decodeSumUs / decodeCount else null,
        decodeMaxUs = if (decodeCount > 0) decodeMaxUs else null,
        maxLatenessUs = maxLatenessUs,
        firstPresentedPtsUs = firstPtsUs,
        lastPresentedPtsUs = lastPtsUs,
        outOfOrderPresented = outOfOrder,
    )
}
