package com.ritim.ritimcraw.export

import kotlin.math.roundToInt

/** The world facts a plan resolves against (everything except the user's settings). */
data class ExportInput(
    /** The ENCODE size: the resolved output size, even-snapped, rotation applied (what the encoder sees). */
    val width: Int,
    val height: Int,
    /** Single clip: the source fps. Timeline: the project fps (TimelineOutput.fps). Never a setting. */
    val fps: Double,
)

/** App-level capabilities that are not encoder facts. */
data class ExportContext(
    /** True once a 10-bit render path exists and is usable for this job (package E1d). False in E1a-c. */
    val tenBitPathAvailable: Boolean = false,
)

/**
 * Something the user should know about the plan. [fix] is the settings
 * that would avoid it (offered as a button); [fixResolution] the largest
 * encode size that would (size issues). Kind + [args] are localized by the
 * UI; this layer never builds user-visible prose.
 */
data class ExportIssue(
    val kind: Kind,
    val severity: Severity,
    val args: List<String> = emptyList(),
    val fix: ExportSettings? = null,
    val fixResolution: Pair<Int, Int>? = null,
) {
    enum class Severity { INFO, WARNING, ERROR }

    enum class Kind {
        /** No video encoder exists for any acceptable codec: the export cannot run (ERROR). */
        NO_ENCODER,

        /** The requested codec has no encoder at all on this device: the plan uses AVC. */
        CODEC_FALLBACK,

        /** The requested codec cannot make this size/depth/mode, another can: the plan uses it. */
        CODEC_SWITCHED,

        /** No encoder makes this size (ERROR); [fixResolution] says what would fit. */
        SIZE_UNSUPPORTED,

        /** The device does not say whether this size works: the encode decides. */
        SIZE_UNKNOWN,

        /** The encoder does not list this frame rate at this size; it may pick another. */
        RATE_UNLISTED,

        /** 10-bit wanted, no encoder takes it: the file is 8-bit. */
        TEN_BIT_UNSUPPORTED,

        /** 10-bit wanted, the encoder could, but no 10-bit render path is available: the file is 8-bit. */
        TEN_BIT_UNAVAILABLE,

        /** HLG/HDR10/DV wanted: not available yet, the file is SDR. */
        DYNAMIC_RANGE_UNAVAILABLE,

        /** The encoder does not offer this rate mode: the plan uses another. */
        RATE_MODE_UNSUPPORTED,

        /** The asked bitrate is above the encoder's listed ceiling and is lowered to it. */
        BITRATE_ABOVE_CEILING,

        /** Uncapped: the bitrate passes the listed ceiling; the encoder may refuse or clamp it. */
        BITRATE_UNCAPPED_RISK,

        /** The asked bitrate is below the encoder's floor and is raised to it. */
        BITRATE_BELOW_FLOOR,

        /** The listed profile is not offered by the encoder: AUTO is used. */
        PROFILE_UNSUPPORTED,

        /** The chosen encoder is software: expect a slow export. */
        ENCODER_SOFTWARE,
    }
}

/**
 * The resolved export (plan 7.5): what will REALLY be produced, plus the
 * issues explaining every difference from the request. Pure; built by
 * [ExportPlanner.resolve]. [encoderName] null = "let MediaCodec choose by
 * MIME" (the legacy behavior, and the plan when the device has no facts).
 */
data class ExportPlan(
    val requested: ExportSettings,
    /** The settings after every fallback: what the writer is configured from. */
    val effective: ExportSettings,
    val encoderName: String?,
    val encoderHardware: Boolean?,
    val mime: String,
    /** KEY_PROFILE, or null = do not set. */
    val profile: Int?,
    val bitDepth: Int,
    val bitrateBps: Int,
    /** KEY_I_FRAME_INTERVAL in seconds; 0 = all-intra. */
    val keyframeIntervalSec: Float,
    /** KEY_FRAME_RATE: the real fps (single clip: the source's, timeline: the project's). */
    val fps: Double,
    val width: Int,
    val height: Int,
    val audioKbps: Int,
    val issues: List<ExportIssue>,
    /**
     * True for [legacy]: a job queued before E1. The format builder then
     * emits exactly the keys the writer always set (no KEY_BITRATE_MODE).
     */
    val isLegacy: Boolean = false,
) {
    val ok: Boolean get() = issues.none { it.severity == ExportIssue.Severity.ERROR }

    /** True when the plan changed something the user asked for. */
    val differsFromRequest: Boolean get() = requested != effective

    /** The ERROR issue as one plain sentence for the failure detail (the UI localizes the same kinds). */
    fun errorMessage(): String {
        val i = issues.firstOrNull { it.severity == ExportIssue.Severity.ERROR }
        val cause = when (i?.kind) {
            ExportIssue.Kind.SIZE_UNSUPPORTED ->
                "no ${i.args.getOrNull(0)} encoder on this device makes ${i.args.getOrNull(1)}" +
                    (i.fixResolution?.let { " (up to ${it.first}x${it.second} fits)" } ?: "")
            ExportIssue.Kind.NO_ENCODER -> "no ${i.args.getOrNull(0)} video encoder fits these settings on this device"
            else -> "the export settings cannot be encoded on this device"
        }
        return Mp4ExportErrors.kotlinMessage(
            Mp4ExportErrors.STAGE_CONFIGURE, cause, "pick another codec or a smaller resolution in the export options",
        )
    }

    /** Rough output size: (video + audio) bits per second over [durationSec]. */
    fun estimatedBytes(durationSec: Double): Long =
        (((bitrateBps + audioKbps * 1000L) * durationSec) / 8.0).toLong()

    companion object {
        /**
         * The plan of a job WITHOUT settings (queued before E1): AVC 8-bit,
         * High profile, the old bitrate formula, KEY_FRAME_RATE 30, one-second
         * I-frames, AAC 128, encoder chosen by MIME. Old jobs resolve to
         * exactly what they always produced.
         */
        fun legacy(width: Int, height: Int): ExportPlan = ExportPlan(
            requested = ExportSettings(),
            effective = ExportSettings(),
            encoderName = null,
            encoderHardware = null,
            mime = ExportCodec.AVC.mime,
            profile = AVC_PROFILE_HIGH,
            bitDepth = 8,
            bitrateBps = BitrateTable.legacyBitsPerSecond(width, height),
            keyframeIntervalSec = 1f,
            fps = 30.0,
            width = width,
            height = height,
            audioKbps = 128,
            issues = emptyList(),
            isLegacy = true,
        )

        const val AVC_PROFILE_HIGH = 8
    }
}

object ExportPlanner {

    /** Why an encoder was skipped; the first failing check per encoder. */
    enum class Rejection { NOT_VIDEO_ROLE, TEN_BIT, RATE_MODE, SIZE }

    private data class Pick(
        val encoder: EncoderFacts?,
        val sizeSupport: Support,
        val rejections: Map<Rejection, Int>,
        val anyForMime: Boolean,
    )

    /** SDR video encoders only: .cq and .hdr variants are never chosen here (they are not general video encoders). */
    private fun select(
        all: List<EncoderFacts>,
        codec: ExportCodec,
        bitDepth: Int,
        mode: ExportRateMode,
        input: ExportInput,
    ): Pick {
        val forMime = all.filter { it.mime == codec.mime }
        val rejections = HashMap<Rejection, Int>()
        val passing = ArrayList<Pair<EncoderFacts, Support>>()
        for (e in forMime) {
            if (e.role != EncoderFacts.Role.GENERAL) {
                rejections.merge(Rejection.NOT_VIDEO_ROLE, 1, Int::plus); continue
            }
            if (bitDepth == 10 && !(e.acceptsTenBitInput() && e.supportsTenBitProfile(codec.mime))) {
                rejections.merge(Rejection.TEN_BIT, 1, Int::plus); continue
            }
            if (!e.supportsRateMode(mode)) {
                rejections.merge(Rejection.RATE_MODE, 1, Int::plus); continue
            }
            val size = e.sizeSupport(input.width, input.height, input.fps)
            if (size == Support.NO) {
                rejections.merge(Rejection.SIZE, 1, Int::plus); continue
            }
            passing += e to size
        }
        // Best first: a verified size, hardware, Codec2 over OMX, the higher ceiling, then the name (stable).
        val best = passing.sortedWith(
            compareBy<Pair<EncoderFacts, Support>>(
                { if (it.second == Support.YES) 0 else 1 },
                { if (it.first.hardware) 0 else 1 },
                { if (it.first.isCodec2) 0 else 1 },
                { -(it.first.bitrateRangeBps?.last ?: 0) },
                { it.first.name },
            )
        ).firstOrNull()
        return Pick(best?.first, best?.second ?: Support.NO, rejections, forMime.isNotEmpty())
    }

    private fun altMode(m: ExportRateMode): ExportRateMode =
        if (m == ExportRateMode.CBR) ExportRateMode.VBR else ExportRateMode.CBR

    fun resolve(
        settings: ExportSettings,
        input: ExportInput,
        encoders: List<EncoderFacts>,
        context: ExportContext = ExportContext(),
        /** Encoder names the plan must not pick (a retry after one refused the format). */
        exclude: Set<String> = emptySet(),
    ): ExportPlan {
        return resolveAgainst(settings, input, encoders.filter { it.name !in exclude }, context)
    }

    /**
     * For a device that gave NO encoder facts (the probe failed): resolves
     * against one nameless stand-in per codec and leaves encoderName null =
     * "MediaCodec chooses by MIME" (the legacy behavior). Nothing is claimed
     * about the device; the real encoder is recorded when it is created.
     * (An EMPTY list passed to [resolve] stays NO_ENCODER: that is a device
     * that really has none.)
     */
    fun resolveWithoutFacts(settings: ExportSettings, input: ExportInput, context: ExportContext = ExportContext()): ExportPlan =
        resolveAgainst(
            settings, input, ExportCodec.entries.map { EncoderFacts("", it.mime, hardware = true) }, context,
        ).let { it.copy(encoderName = null, encoderHardware = null) }

    private fun resolveAgainst(
        settings: ExportSettings,
        input: ExportInput,
        encoders: List<EncoderFacts>,
        context: ExportContext,
    ): ExportPlan {
        val issues = ArrayList<ExportIssue>()
        var eff = settings
        fun warn(kind: ExportIssue.Kind, fix: ExportSettings?, vararg args: String) {
            issues += ExportIssue(kind, ExportIssue.Severity.WARNING, args.toList(), fix)
        }

        // 1. Dynamic range: only SDR is selectable in E1.
        if (eff.dynamicRange != ExportDynamicRange.SDR) {
            val fix = eff.copy(dynamicRange = ExportDynamicRange.SDR)
            warn(ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE, fix, eff.dynamicRange.name)
            eff = fix
        }

        // 2. Bit depth: 10 only with a 10-bit render path; anything but 8/10 is 8.
        if (eff.bitDepth != 10 && eff.bitDepth != 8) eff = eff.copy(bitDepth = 8)
        if (eff.bitDepth == 10 && !context.tenBitPathAvailable) {
            val fix = eff.copy(bitDepth = 8)
            warn(ExportIssue.Kind.TEN_BIT_UNAVAILABLE, fix)
            eff = fix
        }

        // 3. Encoder: requested codec, then progressively relaxed. Every relaxation is an issue.
        var pick = select(encoders, eff.codec, eff.bitDepth, eff.rateMode, input)
        if (pick.encoder == null && eff.bitDepth == 10) {
            val p8 = select(encoders, eff.codec, 8, eff.rateMode, input)
            if (p8.encoder != null) {
                val fix = eff.copy(bitDepth = 8)
                warn(ExportIssue.Kind.TEN_BIT_UNSUPPORTED, fix)
                eff = fix; pick = p8
            }
        }
        if (pick.encoder == null) {
            val other = altMode(eff.rateMode)
            val pm = select(encoders, eff.codec, eff.bitDepth, other, input)
            if (pm.encoder != null) {
                val fix = eff.copy(rateMode = other)
                warn(ExportIssue.Kind.RATE_MODE_UNSUPPORTED, fix, eff.rateMode.name, other.name)
                eff = fix; pick = pm
            }
        }
        if (pick.encoder == null && eff.codec != ExportCodec.AVC) {
            val pa = select(encoders, ExportCodec.AVC, 8, eff.rateMode, input)
                .takeIf { it.encoder != null } ?: select(encoders, ExportCodec.AVC, 8, altMode(eff.rateMode), input)
            if (pa.encoder != null) {
                val fix = eff.copy(codec = ExportCodec.AVC, bitDepth = 8)
                val kind = if (pick.anyForMime) ExportIssue.Kind.CODEC_SWITCHED else ExportIssue.Kind.CODEC_FALLBACK
                warn(kind, fix, eff.codec.name, ExportCodec.AVC.name)
                eff = if (pa.encoder.supportsRateMode(eff.rateMode)) fix else fix.copy(rateMode = altMode(eff.rateMode))
                pick = pa
            }
        }
        val encoder = pick.encoder
        if (encoder == null) {
            // Nothing fits. Name the real reason; size gets a resolution fix.
            val sizeBlocked = (pick.rejections[Rejection.SIZE] ?: 0) > 0
            val fixRes = if (sizeBlocked) fitResolution(encoders, eff.codec, input) else null
            issues += ExportIssue(
                if (sizeBlocked) ExportIssue.Kind.SIZE_UNSUPPORTED else ExportIssue.Kind.NO_ENCODER,
                ExportIssue.Severity.ERROR,
                listOf(eff.codec.name, "${input.width}x${input.height}"),
                fix = null,
                fixResolution = fixRes,
            )
        }

        // 4. Profile.
        var profile: Int? = when {
            eff.profile != null -> eff.profile
            // 10-bit first: AVC 10-bit is High10, not High (E1d).
            eff.bitDepth == 10 -> EncoderFacts.tenBitProfile(eff.codec.mime)
            eff.codec == ExportCodec.AVC -> ExportPlan.AVC_PROFILE_HIGH
            else -> null
        }
        if (eff.profile != null && encoder != null && encoder.profiles.isNotEmpty() && eff.profile !in encoder.profiles) {
            val fix = eff.copy(profile = null)
            warn(ExportIssue.Kind.PROFILE_UNSUPPORTED, fix, eff.profile.toString())
            eff = fix
            profile = if (eff.codec == ExportCodec.AVC) ExportPlan.AVC_PROFILE_HIGH else null
        }

        // 5. Bitrate.
        var mbps = if (eff.bitrateLevel == BitrateLevel.CUSTOM) {
            (eff.customMbps ?: BitrateTable.mbps(BitrateLevel.STANDARD, input.width, input.height, input.fps, eff.codec))
                .coerceAtLeast(BitrateTable.MIN_MBPS)
        } else {
            BitrateTable.mbps(eff.bitrateLevel, input.width, input.height, input.fps, eff.codec)
        }
        val range = encoder?.bitrateRangeBps
        if (range != null) {
            val ceilMbps = range.last / 1_000_000.0
            val floorMbps = range.first / 1_000_000.0
            if (mbps > ceilMbps) {
                if (eff.uncapped && eff.bitrateLevel == BitrateLevel.CUSTOM) {
                    issues += ExportIssue(
                        ExportIssue.Kind.BITRATE_UNCAPPED_RISK, ExportIssue.Severity.WARNING,
                        listOf(fmt(mbps), fmt(ceilMbps)), eff.copy(uncapped = false, customMbps = ceilMbps),
                    )
                } else {
                    val fix = eff.copy(
                        bitrateLevel = BitrateLevel.CUSTOM, customMbps = ceilMbps, uncapped = false,
                    )
                    warn(ExportIssue.Kind.BITRATE_ABOVE_CEILING, fix, fmt(mbps), fmt(ceilMbps))
                    mbps = ceilMbps
                }
            } else if (mbps < floorMbps) {
                warn(ExportIssue.Kind.BITRATE_BELOW_FLOOR, null, fmt(mbps), fmt(floorMbps))
                mbps = floorMbps
            }
        }

        // 6. Advisories on the chosen encoder.
        if (encoder != null) {
            if (!encoder.hardware) issues += ExportIssue(ExportIssue.Kind.ENCODER_SOFTWARE, ExportIssue.Severity.WARNING, listOf(encoder.name))
            if (pick.sizeSupport == Support.UNKNOWN) issues += ExportIssue(ExportIssue.Kind.SIZE_UNKNOWN, ExportIssue.Severity.INFO, listOf("${input.width}x${input.height}"))
            encoder.supportedFps?.let { r ->
                if (input.fps !in r) issues += ExportIssue(ExportIssue.Kind.RATE_UNLISTED, ExportIssue.Severity.INFO, listOf(fmt(input.fps)))
            }
        }

        val audio = ExportSettings.AUDIO_KBPS.minByOrNull { kotlin.math.abs(it - eff.audioKbps) } ?: 128
        return ExportPlan(
            requested = settings,
            effective = eff.copy(audioKbps = audio),
            encoderName = encoder?.name,
            encoderHardware = encoder?.hardware,
            mime = eff.codec.mime,
            profile = profile,
            bitDepth = eff.bitDepth,
            bitrateBps = (mbps * 1_000_000.0).roundToInt(),
            keyframeIntervalSec = eff.keyframe.seconds ?: 1f,
            fps = input.fps,
            width = input.width,
            height = input.height,
            audioKbps = audio,
            issues = issues,
        )
    }

    /** The largest even size keeping the aspect that the best encoder for [codec] lists, or null. */
    private fun fitResolution(all: List<EncoderFacts>, codec: ExportCodec, input: ExportInput): Pair<Int, Int>? {
        val e = all.filter { it.mime == codec.mime && it.role == EncoderFacts.Role.GENERAL && it.maxWidth > 0 }
            .maxByOrNull { it.maxWidth.toLong() * it.maxHeight } ?: return null
        val portrait = input.height > input.width
        val boxW = if (portrait) e.maxHeight else e.maxWidth
        val boxH = if (portrait) e.maxWidth else e.maxHeight
        val scale = minOf(boxW.toDouble() / input.width, boxH.toDouble() / input.height)
        if (scale >= 1.0) return null
        val w = (input.width * scale).toInt() and 1.inv()
        val h = (input.height * scale).toInt() and 1.inv()
        return if (w > 0 && h > 0) w to h else null
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}
