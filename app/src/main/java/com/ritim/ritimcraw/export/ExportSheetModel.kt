package com.ritim.ritimcraw.export

/**
 * The export sheet's decisions as pure functions (plan 7.5, E1c): what the
 * "what will really be produced" line says, the size and time estimates,
 * which choices the device offers. The sheet composable only renders it, so
 * every number the user sees is JVM-tested.
 */
object ExportSheetModel {

    /** The encode the sheet previews: the resolved size, the clip's fps and length. */
    data class Subject(
        val width: Int,
        val height: Int,
        val fps: Double,
        val durationSec: Double,
    )

    /** How fast this device exported last time: output pixels per wall-clock second. */
    data class Rate(val pixelsPerSecond: Double)

    /** The plan for [settings]; a device with no facts plans by MIME (no encoder named). */
    fun plan(
        settings: ExportSettings,
        subject: Subject,
        facts: List<EncoderFacts>,
        context: ExportContext = ExportContext(),
    ): ExportPlan {
        val input = ExportInput(subject.width, subject.height, subject.fps)
        return if (facts.isEmpty()) {
            ExportPlanner.resolveWithoutFacts(settings, input, context)
        } else {
            ExportPlanner.resolve(settings, input, facts, context)
        }
    }

    /** True when an HEVC video encoder is on the device (unknown facts = assumed, the encode decides). */
    fun hevcAvailable(facts: List<EncoderFacts>): Boolean =
        facts.isEmpty() || facts.any { it.mime == ExportCodec.HEVC.mime && it.role == EncoderFacts.Role.GENERAL }

    /**
     * The measured rate of a finished export, or null when the report cannot
     * give one (no wall time, no duration, nothing encoded).
     */
    fun rateOf(r: ExportReport?): Rate? {
        val wall = r?.wallMs ?: return null
        val dur = r.durationSec ?: return null
        if (wall <= 0L || dur <= 0.0) return null
        val pixels = r.width.toDouble() * r.height * r.fps * dur
        return Rate(pixels / (wall / 1000.0))
    }

    /** The newest measured rate among finished MP4 exports (the list is queue order, newest last). */
    fun latestRate(jobs: List<ExportJob>): Rate? =
        jobs.asReversed().firstNotNullOfOrNull { j ->
            if (j.state == State.DONE && j.target == Target.MP4) rateOf(j.report) else null
        }

    /**
     * Seconds the export will take at [rate], or null without a measured
     * rate (the sheet then says it has no estimate yet - never a guess).
     */
    fun estimatedSeconds(subject: Subject, rate: Rate?): Double? {
        if (rate == null || rate.pixelsPerSecond <= 0.0) return null
        val pixels = subject.width.toDouble() * subject.height * subject.fps * subject.durationSec
        return pixels / rate.pixelsPerSecond
    }

    /** The profile ints the sheet offers for [codec] (null = AUTO is always first). */
    fun profileChoices(codec: ExportCodec): List<Int?> = when (codec) {
        ExportCodec.AVC -> listOf(null, 2, 8)
        ExportCodec.HEVC -> listOf(null, 1)
    }

    /** Short name of a profile int for [codec] (not localized: proper names). */
    fun profileName(codec: ExportCodec, profile: Int): String = when (codec) {
        ExportCodec.AVC -> when (profile) {
            1 -> "Baseline"; 2 -> "Main"; 8 -> "High"; 16 -> "High 10"; else -> "#$profile"
        }
        ExportCodec.HEVC -> when (profile) {
            1 -> "Main"; 2 -> "Main 10"; else -> "#$profile"
        }
    }

    /** Settings with [preset] applied; AVC when the device has no HEVC encoder. */
    fun applyPreset(current: ExportSettings, preset: ExportPreset, facts: List<EncoderFacts>): ExportSettings =
        preset.apply(current, hevcAvailable(facts))

    /** What a first-time sheet shows: the Compatible preset. */
    fun defaultSettings(): ExportSettings = ExportPreset.COMPATIBLE.apply(ExportSettings())
}
