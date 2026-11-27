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
        /**
         * The source's REAL frame count (E1f-2): the frame-nearest count
         * rule needs it — the output frame count is floor(span * rate) + 1
         * against the SOURCE's frames clamped, never a wall-time guess.
         * 0 = unknown: [converted] then refuses to convert (the honest
         * "cannot compute" — never a guessed count).
         */
        val frames: Int = 0,
    )

    /** How fast this device exported last time: output pixels per wall-clock second. */
    data class Rate(val pixelsPerSecond: Double)

    /**
     * The fps a plan resolves at (E1f-2, plan §3.4): a ladder target's
     * EXACT rational rate when converting — the bitrate table and the
     * encoder capability queries model the stream the encoder will really
     * produce — else the subject's own (measured, source) fps. Pure: the
     * sheet, the summary and the encode all read the SAME value.
     */
    fun planFps(subject: Subject, target: TargetFps): Double =
        if (target == TargetFps.SOURCE) subject.fps else target.num.toDouble() / target.den

    /**
     * The OUTPUT subject of a conversion (E1f-2, the worker note's
     * framesTotal/durationSec rule): the §4.2 count rule over the source's
     * real frames and measured span, on the production mapping itself
     * (FrameRateConvertPlan — no inline copy). The returned subject carries
     * the TARGET's exact fps and the OUTPUT's true wall duration (the last
     * grid point + one target period, the durationSec rule the exporter
     * reports), so the size estimate and the time estimate answer the
     * movie the encode will really make. [target] SOURCE or an unknown
     * frame count (frames 0) returns [subject] UNTOUCHED — never a guessed
     * conversion. A zero span is NOT a refusal: it is the honest single
     * frame clip (one output frame, one target period — the §4.2 rule
     * floor+1, where ceil's 0 would drop the only frame).
     */
    fun converted(subject: Subject, target: TargetFps): Subject {
        if (target == TargetFps.SOURCE) return subject
        if (subject.frames <= 0 || subject.fps <= 0.0) return subject
        val spanUs = Math.round(subject.durationSec * 1_000_000.0)
        val plan = FrameRateConvertPlan.plan(
            FrameRateConvertPlan.Source(
                fps = subject.fps,
                frames = subject.frames,
                spanUs = spanUs,
            ),
            target,
        )
        val targetFps = target.num.toDouble() / target.den
        val durationSec = plan.mapping.ptsUs.last() / 1_000_000.0 + 1.0 / targetFps
        return subject.copy(fps = targetFps, durationSec = durationSec)
    }

    /** The plan for [settings]; a device with no facts plans by MIME (no encoder named). */
    fun plan(
        settings: ExportSettings,
        subject: Subject,
        facts: List<EncoderFacts>,
        context: ExportContext = ExportContext(),
    ): ExportPlan {
        val input = ExportInput(subject.width, subject.height, planFps(subject, settings.targetFps))
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

    /**
     * The 10-bit path of THIS export (E1d), from the facts the sheet can know
     * before the export: the device offers an RGBA1010102 EGL config
     * ([probeOk]), the size is the original (no scale), nothing is cropped
     * or straightened, and every grade stage runs on the GPU. The exporter
     * re-checks all of it at run time and re-plans 8-bit, with the reason on
     * the report, if any of it fails there.
     */
    fun tenBitContext(
        probeOk: Boolean,
        originalSize: Boolean,
        cropFull: Boolean,
        straightenZero: Boolean,
        gradeOnGpu: Boolean,
    ): ExportContext = ExportContext(
        tenBitPathAvailable = probeOk && originalSize && cropFull && straightenZero && gradeOnGpu,
    )

    /**
     * The bitrate levels the sheet lists (pure): LOW..MAX always, HIGHER and
     * CEILING only when [plan]'s encoder lists a ceiling with room above MAX
     * ([BitrateTable.extraSteps]), then CUSTOM. The level currently chosen is
     * always listed so a saved pick never vanishes from its own picker.
     */
    fun offeredBitrateLevels(plan: ExportPlan, facts: List<EncoderFacts>, current: BitrateLevel): List<BitrateLevel> {
        val ceilMbps = facts.firstOrNull { it.name == plan.encoderName }
            ?.bitrateRangeBps?.last?.let { it / 1_000_000.0 }
        val max = BitrateTable.mbps(BitrateLevel.MAX, plan.width, plan.height, plan.fps, ExportCodec.entries.first { it.mime == plan.mime })
        val extra = BitrateTable.extraSteps(max, ceilMbps).map { it.first }
        return BitrateLevel.entries.filter {
            when (it) {
                BitrateLevel.HIGHER, BitrateLevel.CEILING -> it in extra || it == current
                // QUALITY belongs to the preset: listed only while it is the current choice.
                BitrateLevel.QUALITY -> it == current
                else -> true
            }
        }
    }

    /** True when an HEVC Main10 video encoder (10-bit input) is on the device; unknown facts = assumed. */
    fun tenBitEncoderAvailable(facts: List<EncoderFacts>): Boolean =
        facts.isEmpty() || facts.any {
            it.mime == ExportCodec.HEVC.mime && it.role == EncoderFacts.Role.GENERAL &&
                it.acceptsTenBitInput() && it.supportsTenBitProfile(it.mime)
        }

    /** Settings with [preset] applied; AVC when the device has no HEVC encoder, 10-bit QUALITY when [tenBit]. */
    fun applyPreset(
        current: ExportSettings,
        preset: ExportPreset,
        facts: List<EncoderFacts>,
        tenBit: Boolean = false,
    ): ExportSettings = preset.apply(current, hevcAvailable(facts), tenBit && tenBitEncoderAvailable(facts))

    /** What a first-time sheet shows: the Compatible preset. */
    fun defaultSettings(): ExportSettings = ExportPreset.COMPATIBLE.apply(ExportSettings())
}
