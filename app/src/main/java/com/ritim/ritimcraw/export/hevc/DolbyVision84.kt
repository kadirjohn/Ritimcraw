package com.ritim.ritimcraw.export.hevc

/**
 * Dolby Vision 8.4 — the capability/decision table and the
 * configuration model, package E3 (night 2026-11-27, plan §13.3 and
 * §7.6.5). PURE Kotlin, Android-free like [HevcProfile]: the framework
 * constants arrive as DATA (every numeric value in this file was
 * verified against the installed android-34 AND android-36 jars with
 * javap — see the report), so the whole decision is JVM-testable and
 * the device leg turns [Dv84Configuration.mediaFormatEntries] into
 * real MediaFormat calls.
 *
 * WHAT DV 8.4 IS (the profile's own shape): Dolby Vision profile 8,
 * track-compatibility 4 — an **HLG base layer** carried in an HEVC
 * Main10 stream (mime `video/dolby-vision`, profile `DvheSt`), where
 * the ENCODER generates the dynamic metadata (the RPU) from the frames
 * itself (plan §7.6.5: "Encoder RPU'yu karelerden kendisi üretir").
 * Players without Dolby Vision play the file as plain HLG. RPU
 * GENERATION IS OUT OF SCOPE HERE (the brief's own line): this model
 * carries the capability decision and the encoder configuration, no
 * bitstream is built.
 *
 * THE DECISION (the brief's table: "encoder var mı → DV 8.4 / HLG /
 * SDR geri dönüş"). The stages follow [HevcPreflight]'s shape and
 * vocabulary deliberately — DV's verdicts name the SAME refusal
 * stages, carry the SAME honest one-sentence degraded offer, and the
 * FALLBACK CHAIN is DV 8.4 → HLG → SDR:
 *
 *  1. CODEC — does a `video/dolby-vision` encoder exist (probed by
 *     name; the 15 Ultra's is `c2.qti.dv.encoder`, plan §13.13)?
 *     Absent → REFUSED, fallback HLG if the HLG gate's verdict is
 *     Supported, else SDR.
 *  2. PROFILE — does the DV encoder's capabilities list `DvheSt`
 *     (MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt =
 *     256)? Unlisted → REFUSED (stage PROFILE_LEVEL), fallback HLG.
 *  3. LEVEL — does it list the job's level (resolved from the job's
 *     size + frame rate, plan §13.3's UHD rows: 24 fps → 06, 30 fps →
 *     07, 60 fps → 09)? Unlisted → REFUSED (stage PROFILE_LEVEL, the
 *     level rides the same stage), fallback HLG.
 *  4. SIZE/RATE — did the encoder accept the job's (transposed for
 *     portrait) size at the frame rate? Refused → REFUSED (stage
 *     SIZE_RATE), fallback HLG.
 *
 * THE FALLBACK IS THE USER'S CHOICE, never a silent substitution (the
 * E1 rule HevcPreflight already carries): [Dv84Verdict.Refused.fallback]
 * NAMES the range the export degrades to (HLG or SDR) and the verdict
 * carries [HevcPreflight.HevcVerdict.DegradedOffer]'s shape — the
 * plan's §7.5 example sentence is exactly this case: "Bu telefonda
 * Dolby Vision encoder'ı yok, HLG olarak export edilecek."
 *
 * WHAT IS NOT VERIFIED (the honesty table): a real `video/dolby-vision`
 * encode (the 15 Ultra's DV encoder is documented in plan §13.13's
 * device dump, but nothing here has run one), the muxer's `dvcC` box
 * (MediaMuxer writes it from API 33+ — Klipp's own comment, referenced
 * not verified), ffprobe's profile/level/compat-id lines, RPU contents.
 * "Resmî belgeden alıntı, uygulanışı doğrulandı" applies to the level
 * table and the constants; a real encode is DOĞRULANMADI.
 */
object DolbyVision84 {

    /** MediaFormat's MIMETYPE_VIDEO_DOLBY_VISION value (android-34/36 jars, verified). */
    const val MIME = "video/dolby-vision"

    /** MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt (android-34/36 jars, verified). */
    const val PROFILE_DVHE_ST = 256

    /** MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24 = DV level 06 (jar, verified). */
    const val LEVEL_UHD24 = 32

    /** MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30 = DV level 07 (jar, verified). */
    const val LEVEL_UHD30 = 64

    /** MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60 = DV level 09 (jar, verified). */
    const val LEVEL_UHD60 = 256

    /** DolbyVisionLevelFhd24 (jar, verified). */
    const val LEVEL_FHD24 = 4

    /** DolbyVisionLevelFhd30 (jar, verified). */
    const val LEVEL_FHD30 = 8

    /** DolbyVisionLevelFhd60 (jar, verified). */
    const val LEVEL_FHD60 = 16

    /**
     * The High-tier bitrate ceiling plan §13.3 names for DV levels 07/09
     * (UHD 30/60): 130 Mb/s. Carried as DATA for the bitrate picker's
     * warning; the export plan's own bitrate logic is a later package.
     */
    const val HIGH_TIER_CEILING_MBPS = 130.0

    /**
     * One DV level: the Android constant's value plus the profile's own
     * level NAME (06/07/09 are plan §13.3's "Dolby Vision level" names,
     * the numbers the ffprobe line reports).
     */
    data class DvLevel(val code: Int, val name: String)

    /** The level table's own rows (plan §13.3): UHD and FHD, 24/30/60 fps. */
    val UHD_LEVELS = mapOf(
        "24" to DvLevel(LEVEL_UHD24, "06"),
        "30" to DvLevel(LEVEL_UHD30, "07"),
        "60" to DvLevel(LEVEL_UHD60, "09"),
    )

    val FHD_LEVELS = mapOf(
        "24" to DvLevel(LEVEL_FHD24, "HD24"),
        "30" to DvLevel(LEVEL_FHD30, "HD30"),
        "60" to DvLevel(LEVEL_FHD60, "HD60"),
    )

    /**
     * Resolve the job's DV level from its size and frame rate (plan
     * §13.3's own rows, the table the level constant is chosen from).
     * UHD class: up to 3840x2160 (either orientation, 4K portrait
     * included); FHD class: up to 1920x1080. The fps bands: <= 24 the
     * 06 row, 25..30 the 07 row, 31..60 the 09 row. Anything else —
     * a size above UHD, or > 60 fps — answers NULL: "reported, never
     * guessed" (the model does not invent a level row the plan did not
     * quote; the caller reports the refusal honestly).
     */
    fun levelFor(width: Int, height: Int, fps: Double): DvLevel? {
        require(width > 0 && height > 0) { "a level needs a size, got ${width}x$height" }
        require(fps > 0.0) { "a level needs a frame rate, got $fps" }
        val long = maxOf(width, height)
        val short = minOf(width, height)
        // The class order is most-constrained FIRST: FHD (1920x1080)
        // also fits the UHD box, but it is an FHD job with FHD rows.
        val table = when {
            long <= 1920 && short <= 1080 -> FHD_LEVELS
            long <= 3840 && short <= 2160 -> UHD_LEVELS
            else -> return null
        }
        val band = when {
            fps <= 24.0 -> "24"
            fps <= 30.0 -> "30"
            fps <= 60.0 -> "60"
            else -> return null
        }
        return table[band]
    }

    /**
     * The device facts for the DV decision — PURE DATA the device leg
     * fills from MediaCodecList/MediaCodecInfo, the same rule as
     * [HevcPreflight.HevcProbeResult]: every field is a PROBED fact
     * (never inferred from a phone name), and JVM tests build
     * SYNTHETIC instances (clearly labeled there). The 15 Ultra's real
     * row (plan §13.13's dump) is one such instance: the DV encoder
     * exists, lists DvheSt up to level 8k60, accepts ABGR2101010/P010.
     */
    data class DvProbeResult(
        /** The DV encoder's MediaCodec name (null = none on the device). */
        val dvEncoderName: String?,
        /** PROBED: does its capabilities list DvheSt (profile 256)? */
        val listsDvheSt: Boolean,
        /** The DV encoder's profileLevels' LEVEL values (MediaCodec ints). */
        val listedLevels: Set<Int>,
        /** PROBED: does it accept 10-bit input (P010 / RGBA1010102)? */
        val acceptsTenBitInput: Boolean,
        /** PROBED: did it accept the job's (transposed) size at the rate? */
        val sizeRateAccepted: Boolean,
    )

    /**
     * The verdict — the same sealed shape as [HevcPreflight.HevcVerdict]
     * (Supported / Refused with the stage, the codec, the honest
     * sentence), extended by the DV-specific FALLBACK the brief names:
     * the refused DV request degrades to HLG (when the HLG gate
     * verdict is Supported) or SDR — as the USER'S choice, never a
     * silent substitution.
     */
    sealed class DvVerdict {

        /** The configuration the device leg needs (see [configuration]). */
        data class Supported(
            val codecName: String,
            val level: DvLevel,
        ) : DvVerdict()

        /** The refusal: what failed, which codec, and the fallback to choose. */
        data class Refused(
            val stage: HevcPreflight.HevcRefusalStage,
            val codecName: String,
            val reason: String,
            /** DV 8.4's fallback: HLG when its gate verdict says Supported, else SDR. */
            val fallback: DvFallback,
        ) : DvVerdict() {
            /**
             * The explicit fallback a refusal offers the user — the
             * [HevcPreflight.HevcVerdict.DegradedOffer] rule: the USER
             * chooses, never a silent substitution.
             */
            data class DvFallback(val range: FallbackRange) {
                /** The one honest sentence (the plan §7.5 example's shape). */
                val offerText: String = "Dolby Vision 8.4 is not available on this device. " +
                    "Choose: export as ${range.label} — never a silent fallback."
            }
        }
    }

    /** The fallback ranges the DV refusal offers, most-capable first. */
    enum class FallbackRange(val label: String) {
        /** The HLG gate said Supported: HLG keeps the HDR dynamic range. */
        HLG("HLG"),

        /** Nothing HDR is available: today's baseline. */
        SDR("SDR"),
    }

    /**
     * THE decision. Pure: the same probe + the same HLG verdict give
     * the same DV verdict. The HLG gate's [verdict] arrives as an
     * [HevcPreflight.HevcVerdict] because DV 8.4's fallback IS the
     * HLG export path E2-a already gates — the two models share the
     * decision seam deliberately (the brief: "HevcPreflight.decide +
     * HevcTrialCache ile UYUMLU ekleme").
     *
     * [jobWidth]/[jobHeight]/[jobFps] are the job's encode size and
     * frame rate (the same ExportInput facts the level table consumes).
     */
    fun decide(probe: DvProbeResult, verdict: HevcPreflight.HevcVerdict, jobWidth: Int, jobHeight: Int, jobFps: Double): DvVerdict {
        val hlgSupported = verdict is HevcPreflight.HevcVerdict.Supported
        val fallback = DvVerdict.Refused.DvFallback(
            if (hlgSupported) FallbackRange.HLG else FallbackRange.SDR,
        )
        fun refuse(stage: HevcPreflight.HevcRefusalStage, codec: String, reason: String): DvVerdict.Refused =
            DvVerdict.Refused(stage, codec, reason, fallback)

        // Stage 1: the codec list (the probed DV encoder).
        val codec = probe.dvEncoderName
            ?: return refuse(
                HevcPreflight.HevcRefusalStage.CODEC_LIST,
                "none",
                "no $MIME encoder is available on this device",
            )

        // Stage 2: the profile (DvheSt is THE 8.4 profile).
        if (!probe.listsDvheSt) {
            return refuse(
                HevcPreflight.HevcRefusalStage.PROFILE_LEVEL,
                codec,
                "$codec does not list the DV 8.4 profile DvheSt ($PROFILE_DVHE_ST)",
            )
        }

        // Stage 3: the level (the job's size + rate against the table).
        val level = levelFor(jobWidth, jobHeight, jobFps)
            ?: return refuse(
                HevcPreflight.HevcRefusalStage.PROFILE_LEVEL,
                codec,
                "no Dolby Vision level row for ${jobWidth}x$jobHeight @ ${jobFps}fps " +
                    "(plan 13.3 carries UHD/FHD up to 60fps); refusing rather than guessing",
            )
        if (level.code !in probe.listedLevels) {
            return refuse(
                HevcPreflight.HevcRefusalStage.PROFILE_LEVEL,
                codec,
                "$codec lists DvheSt but not level ${level.name} (${level.code})",
            )
        }

        // Stage 4: size/rate (the probe already used the job's dims,
        // transposed for portrait — the same seam as HevcPreflight).
        if (!probe.sizeRateAccepted) {
            return refuse(
                HevcPreflight.HevcRefusalStage.SIZE_RATE,
                codec,
                "$codec does not accept the requested size/rate",
            )
        }

        // All stages passed: SUPPORTED.
        return DvVerdict.Supported(codecName = codec, level = level)
    }

    /**
     * The DV 8.4 ENCODER CONFIGURATION — the pure contract the device
     * leg turns into MediaFormat calls. The base layer IS HLG: BT.2020
     * (6), HLG transfer (7, ARIB STD-B67), limited range (2), 10-bit
     * samples, in the `video/dolby-vision` MIME with the DvheSt profile
     * and the job's level. The color values are [HevcProfile.HLG]'s
     * own (never re-derived): DV 8.4's base layer must not drift from
     * the HLG profile E2-a already contracts.
     */
    data class Dv84Configuration(
        val codecName: String,
        val level: DvLevel,
    ) {
        val mime: String get() = MIME
        val bitDepth: Int get() = 10
        val profile: Int get() = PROFILE_DVHE_ST
        val levelCode: Int get() = level.code
        val colorStandard: Int get() = HevcProfile.HLG.colorStandard   // BT2020 (6)
        val colorRange: Int get() = HevcProfile.HLG.colorRange         // limited (2)
        val colorTransfer: Int get() = HevcProfile.HLG.colorTransfer  // HLG (7)

        /**
         * The MediaFormat contract as (key, value) pairs, in the SAME
         * key order [HevcProfile.mediaFormatEntries] uses (mime,
         * profile, level, color-standard, color-range, color-transfer)
         * — so a diff against the HLG profile's entries is honest and
         * the device leg sets them with the same code.
         */
        fun mediaFormatEntries(): List<MediaFormatEntry> = listOf(
            MediaFormatEntry("mime", mime),
            MediaFormatEntry("profile", profile.toString()),
            MediaFormatEntry("level", levelCode.toString()),
            MediaFormatEntry("color-standard", colorStandard.toString()),
            MediaFormatEntry("color-range", colorRange.toString()),
            MediaFormatEntry("color-transfer", colorTransfer.toString()),
        )

        /**
         * The trial-cache key COMPONENT LIST (the same components
         * [HevcTrialKey]'s shape composes — the brief's
         * "HevcTrialCache ile UYUMLU" rule): the format contract + the
         * encode size + the API level + the codec name + the build ID.
         * Rendered as one stable string the device leg caches a DV
         * trial verdict under (the HevcTrialCache pattern: both a pass
         * and a refusal cache; the key moves when any component moves).
         */
        fun trialKeyComponents(width: Int, height: Int, apiLevel: Int, buildId: String): String =
            mediaFormatEntries().joinToString(",") { "${it.key}=${it.value}" } +
                "|${width}x${height}|api=$apiLevel|codec=$codecName|build=$buildId"
    }
}
