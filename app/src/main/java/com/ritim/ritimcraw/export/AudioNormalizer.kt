package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.NativeSource

/**
 * The E5 loudness normalization seam (night D2, docs/E5_AUDIO_PORT_PLAN.md
 * §4.1-§4.2): the exporter calls through this at the audio-preparation
 * point so the JVM tests can inject a fake and prove the OFF path never
 * runs it. The DEFAULT is the native chain (core/src/AudioNormalize.cpp
 * through NativeSource's JNI bridge): measure -> gain -> true-peak
 * ceiling -> verify, at most 2 iterations.
 *
 * The ceiling is D1's look-ahead Limiter (latency compensated), gain
 * capped at +12 dB; the report says when the target was not reached.
 */
fun interface AudioNormalizer {

    /** What the chain did: the honest report, as measured. */
    data class Report(
        /** Integrated loudness of the INPUT, LUFS; null when unmeasurable. */
        val measuredLufs: Double?,
        /** True peak of the INPUT, dBTP; null when silent. */
        val measuredDbtp: Double?,
        /** Integrated loudness AFTER the applied gain, LUFS; null when unmeasurable. */
        val finalLufs: Double?,
        /** True peak AFTER the applied gain, dBTP; null when silent. */
        val finalDbtp: Double?,
        /** The gain the chain applied, dB (0 when it skipped honestly). */
        val appliedGainDb: Double,
        /** Gain application passes: 1-2, or 0 when skipped. */
        val iterations: Int,
        /** 0 = gain applied, 1 = too short, 2 = silent / gated-invalid. */
        val skipped: Int,
        /** 0 = target reached, 1 = gain capped at +12 dB, 2 = the ceiling limited it. */
        val shortfall: Int = 0,
        /** True when the measured final true peak is above the ceiling. */
        val ceilingExceeded: Boolean = false,
    )

    /** The normalized PCM plus the honest report. */
    data class Result(val pcm: ShortArray, val report: Report)

    /**
     * Normalizes [pcm] (interleaved 16-bit, [sampleRate] Hz,
     * [channels] channels) toward [targetLufs] LUFS under a
     * [tpDbtp] dBTP ceiling. The returned PCM keeps the input's layout
     * and length, so the block/pts math never changes.
     */
    fun normalize(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        targetLufs: Float,
        tpDbtp: Float,
    ): Result
}

/** The honest skip codes, mirrored from rawedit::NormalizeSkipped. */
internal object AudioNormalizeSkips {
    const val NONE = 0
    const val TOO_SHORT = 1
    const val SILENT = 2
}

/** The target-not-reached codes, mirrored from rawedit::NormalizeShortfall. */
internal object AudioNormalizeShortfalls {
    const val NONE = 0
    const val GAIN_CAPPED = 1
    const val CEILING = 2

    /** The report vocabulary: "gain-cap" / "ceiling"; null when reached. */
    fun name(code: Int): String? = when (code) {
        GAIN_CAPPED -> "gain-cap"
        CEILING -> "ceiling"
        else -> null
    }
}

/**
 * The production seam: the native chain through NativeSource's JNI
 * wrappers. On a null (malformed request) the export FAILS loudly with
 * the bridge's own error detail — never a silent un-normalized copy,
 * which would lie about the "−14 LUFS" the settings promised.
 */
internal object NativeAudioNormalizer : AudioNormalizer {

    override fun normalize(
        pcm: ShortArray,
        sampleRate: Int,
        channels: Int,
        targetLufs: Float,
        tpDbtp: Float,
    ): AudioNormalizer.Result {
        val out = NativeSource.jniAudioNormalize(pcm, sampleRate, channels, targetLufs, tpDbtp)
            ?: throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_ENCODE,
                    "the loudness normalization could not run on this clip",
                    "export again, or turn the loudness target off",
                    NativeSource.previewLastError(),
                )
            )
        val raw = NativeSource.jniAudioNormalizeReport()
            ?: throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_ENCODE,
                    "the loudness measurement could not be read back",
                    "export again, or turn the loudness target off",
                )
            )
        val report = AudioNormalizer.Report(
            measuredLufs = finiteOrNull(raw, 0),
            measuredDbtp = finiteOrNull(raw, 1),
            finalLufs = finiteOrNull(raw, 2),
            finalDbtp = finiteOrNull(raw, 3),
            appliedGainDb = raw.getOrElse(4) { 0.0 },
            iterations = raw.getOrElse(5) { 0.0 }.toInt(),
            skipped = raw.getOrElse(6) { 0.0 }.toInt(),
            shortfall = raw.getOrElse(7) { 0.0 }.toInt(),
            ceilingExceeded = raw.getOrElse(8) { 0.0 } != 0.0,
        )
        return AudioNormalizer.Result(out, report)
    }

    /** The honest -inf of an unmeasurable value stays null in the report. */
    private fun finiteOrNull(a: DoubleArray, i: Int): Double? {
        val v = a.getOrElse(i) { return null }
        return if (v.isFinite()) v else null
    }
}
