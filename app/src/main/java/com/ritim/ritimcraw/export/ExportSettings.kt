package com.ritim.ritimcraw.export

/**
 * The user-facing MP4 export choices (plan 7.3, package E1a). Pure Kotlin:
 * no Android types, so the plan, the presets and the wire format are all
 * JVM-tested.
 *
 * What is NOT a setting here, on purpose (owner decisions 2026-11-23):
 *  - frame rate: single-clip exports keep the source's own pts, timeline
 *    exports use the project fps. The plan takes the fps as INPUT
 *    ([ExportInput.fps]); a picker plus frame drop/duplicate is a later
 *    package.
 *  - resolution: the job's resolutionW/H bound (ExportResolution) already
 *    carries it; the plan receives the resolved encode size.
 *  - audio sample rate and channel count: they stay "source" until the
 *    resampler/downmix of package E5.
 */
enum class ExportCodec(val mime: String, val relativeBitrate: Double) {
    /** H.264. The reference for the bitrate table. */
    AVC("video/avc", 1.0),

    /** HEVC: the same quality at roughly 60 % of AVC's bitrate. */
    HEVC("video/hevc", 0.6),
    // Later packages append AV1, APV, DV (E3) and PRORES (E4) here: the
    // enum is persisted by NAME, so new constants are additive.
}

/**
 * The dynamic range of the OUTPUT. HLG, HDR10 and DV exist as values so the
 * model has room for E2/E3, but they are not selectable in E1: the plan
 * resolves them to SDR with an issue ([ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE]).
 */
enum class ExportDynamicRange { SDR, HLG, HDR10, DV }

/** Rate control. [mediaCodecMode] is MediaCodecInfo.EncoderCapabilities' BITRATE_MODE_* value. */
enum class ExportRateMode(val mediaCodecMode: Int) {
    CBR(2),
    VBR(1),

    /** Only for an encoder that offers CQ at the video's size (the 15 Ultra's .cq encoder does not: 512x512). */
    CQ(0),
}

/** Symbolic bitrate level; [factor] scales the table (Klipp ExportPresets). CUSTOM uses [ExportSettings.customMbps]. */
enum class BitrateLevel(val factor: Double) {
    LOW(0.45),
    STANDARD(0.75),
    HIGH(1.2),
    MAX(2.4),
    /**
     * The Quality preset's own level (owner decision 2026-11-25: Quality is
     * 160 Mb/s for HEVC): the table's pixel-rate scaling with a factor that
     * lands on 160 Mb/s for 4K30 HEVC (5.13 x the 4K30 base of 31.19), about
     * 49 Mb/s at 1080p30, capped at the chosen encoder's listed ceiling (so
     * 4K60 reads 160 too, never above what the encoder lists). It is only
     * ever set by the preset; the bitrate sub-page lists it only while it is
     * the current choice. MAX and the ladder keep their old values.
     */
    QUALITY(5.13),
    /**
     * Ladder steps above MAX, up to the chosen encoder's listed ceiling
     * (P2, 2026-11-25). They have no fixed factor: their value is a function
     * of MAX's table value and the ceiling ([BitrateTable.extraSteps]); an
     * encoder with no listed ceiling resolves them as MAX and the sheet does
     * not offer them. HIGHER sits between MAX and the ceiling, CEILING is the
     * ceiling itself.
     */
    HIGHER(0.0),
    CEILING(0.0),
    CUSTOM(0.0),
}

/** The I-frame interval. [seconds] is MediaFormat's KEY_I_FRAME_INTERVAL; 0 = every frame is a sync frame. */
enum class KeyframeInterval(val seconds: Float?) {
    /** The encoder default the app always used: one second. */
    AUTO(null),
    HALF(0.5f),
    ONE(1f),
    TWO(2f),
    FIVE(5f),
    ALL_INTRA(0f),
}

data class ExportSettings(
    val codec: ExportCodec = ExportCodec.AVC,
    /** 8 or 10. 10 needs the encoder AND a 10-bit render path ([ExportContext.tenBitPathAvailable]). */
    val bitDepth: Int = 8,
    val dynamicRange: ExportDynamicRange = ExportDynamicRange.SDR,
    val rateMode: ExportRateMode = ExportRateMode.VBR,
    val bitrateLevel: BitrateLevel = BitrateLevel.STANDARD,
    /** Used only when [bitrateLevel] is CUSTOM, in megabits per second. */
    val customMbps: Double? = null,
    /** True = the custom value passes the encoder's listed ceiling unchanged (the UI warns). */
    val uncapped: Boolean = false,
    val keyframe: KeyframeInterval = KeyframeInterval.AUTO,
    /** MediaCodecInfo.CodecProfileLevel profile int, or null = AUTO. */
    val profile: Int? = null,
    /** AAC bitrate: 128, 192, 256 or 320. */
    val audioKbps: Int = 128,
    /**
     * (E5, night D2) The loudness normalization target in LUFS — the
     * two-pass measure->gain->verify chain runs over the clip's PCM
     * before the AAC blocks are cut. NULL (the default) = OFF: today's
     * behavior, bit-identical (the chain never runs; the settings row
     * reads "Off"). The SOCIAL preset sets −14; the sheet's target row
     * also offers −16 and −23.
     */
    val loudnessTargetLufs: Float? = null,
    /**
     * (E5, night D2) The true-peak ceiling in dBTP the normalization
     * keeps (the plan §4.1 rule: a target the ceiling cannot reach
     * sacrifices loudness, never the peak). Only meaningful while
     * [loudnessTargetLufs] is set. Default −1 (the plan's Social row).
     */
    val truePeakDbtp: Float = -1f,
    /**
     * (E5, night D2) The Social preset's vertical OUTPUT bound, in the
     * EXPORT SETTINGS (not the job's [ExportJob.resolutionW] pair): the
     * preset is "−14 LUFS, −1 dBTP, vertical 1080×1920", so the preset's
     * identity needs the pair to travel with the settings. Null (the
     * default) = no bound carried — the sheet's own ladder choice keeps
     * deciding the encode size exactly as before, and a job without a
     * settings pair exports at its own bound (or Original). This field
     * is APPLIED only by the sheet's UI wiring (the target row); until
     * that lands nothing writes a non-null value here, so no queued job
     * can silently change size.
     */
    val resolutionW: Int? = null,
    /** [resolutionW]'s pair; both null or both set, never half a pair. */
    val resolutionH: Int? = null,
    /**
     * (E5-B, night G1) The OUTPUT audio sample rate in Hz — the clip's
     * PCM is resampled (core/src/Resample.cpp, the E5-B linear
     * interpolator) before the AAC blocks are cut. NULL (the default) =
     * SOURCE: the clip's own rate carries through unchanged and the
     * resampler NEVER runs (bit-identical to every export before this
     * field). The UI offers 44100 / 48000 beside Source. Only meaningful
     * on a job with an audio track; a video-only export ignores it.
     */
    val audioSampleRateHz: Int? = null,
    /**
     * (E5-B, night G1) The OUTPUT channel count — the clip's PCM is
     * remixed through the E5-B BS.775 core (stereo->mono [k, k] with
     * k = 1/sqrt(2) ~ -3.01 dB; mono->stereo [1; 1] dual mono, the
     * deliberate +3 dB) before the AAC blocks are cut. NULL (the
     * default) = SOURCE: the clip's own channel count carries through
     * and the remix NEVER runs (bit-identical). The UI offers Mono /
     * Stereo beside Source.
     */
    val audioChannels: Int? = null,
) {
    companion object {
        val AUDIO_KBPS = listOf(128, 192, 256, 320)

        /** (E5-B, night G1) The sample-rate choices the sheet offers. */
        val AUDIO_SAMPLE_RATES = listOf(44100, 48000)

        /** (E5-B, night G1) The channel choices the sheet offers. */
        val AUDIO_CHANNEL_COUNTS = listOf(1, 2)
    }
}

/** The one-tap presets (Klipp ExportPreset). Any field that differs makes the settings [CUSTOM]. */
enum class ExportPreset {
    /** H.264, 8-bit, the standard level: plays everywhere. */
    COMPATIBLE,

    /** HEVC where the device has it, the LOW level, 8-bit. */
    SMALLER,

    /**
     * HEVC, the QUALITY level (160 Mb/s at 4K30, owner decision 2026-11-25;
     * MAX when the device has no HEVC encoder and the preset falls back to
     * AVC); 10-bit (Main10) where the device and the render path allow it
     * (E1d: the GPU encoder-surface path only), else 8-bit.
     */
    QUALITY,

    /**
     * (E5, night D2) The plan's Social row (docs/E5_AUDIO_PORT_PLAN.md §4.2
     * / §5): AVC 8-bit (plays everywhere a social app shares to), the
     * STANDARD level, 192 kbps AAC, and the loudness target −14 LUFS-I
     * with a −1 dBTP true-peak ceiling, vertical 1080×1920. The target
     * wording is the APP's own ("optimized for social video"), never a
     * platform compliance claim (the plan §5 naming rule).
     */
    SOCIAL,

    CUSTOM;

    /**
     * [s] with this preset applied (unchanged for CUSTOM); [hevcAvailable]
     * false falls back to AVC; [tenBitAvailable] true lets QUALITY be 10-bit
     * (HEVC only - without HEVC there is no Main10 encoder to ask for).
     */
    fun apply(s: ExportSettings, hevcAvailable: Boolean = true, tenBitAvailable: Boolean = false): ExportSettings {
        val hevc = if (hevcAvailable) ExportCodec.HEVC else ExportCodec.AVC
        // The base resets everything a preset OWNS: the video shape (range/
        // rate/custom/uncapped/keyframe/profile), the audio shape, and the
        // E5 loudness + resolution pair — so every preset application
        // starts from the same neutral base (a preset never inherits a
        // stale loudness target or bound from whatever was set before).
        val base = s.copy(
            dynamicRange = ExportDynamicRange.SDR,
            rateMode = ExportRateMode.VBR,
            customMbps = null,
            uncapped = false,
            keyframe = KeyframeInterval.AUTO,
            profile = null,
            loudnessTargetLufs = null,
            truePeakDbtp = -1f,
            resolutionW = null,
            resolutionH = null,
            audioSampleRateHz = null,
            audioChannels = null,
        )
        return when (this) {
            COMPATIBLE -> base.copy(codec = ExportCodec.AVC, bitDepth = 8, bitrateLevel = BitrateLevel.STANDARD, audioKbps = 192)
            SMALLER -> base.copy(codec = hevc, bitDepth = 8, bitrateLevel = BitrateLevel.LOW, audioKbps = 128)
            QUALITY -> base.copy(
                codec = hevc,
                bitDepth = if (tenBitAvailable && hevcAvailable) 10 else 8,
                bitrateLevel = if (hevcAvailable) BitrateLevel.QUALITY else BitrateLevel.MAX,
                audioKbps = 320,
            )
            SOCIAL -> base.copy(
                codec = ExportCodec.AVC,
                bitDepth = 8,
                bitrateLevel = BitrateLevel.STANDARD,
                audioKbps = 192,
                loudnessTargetLufs = -14f,
                truePeakDbtp = -1f,
                resolutionW = 1080,
                resolutionH = 1920,
            )
            CUSTOM -> s
        }
    }

    companion object {
        /**
         * The preset [s] is exactly, or CUSTOM. A preset's 10-bit and 8-bit
         * variants are the SAME preset: when the 10-bit path stops being
         * available (the resolution changed), QUALITY must not turn Custom -
         * the plan reports the 8-bit result instead.
         */
        fun of(s: ExportSettings, hevcAvailable: Boolean = true): ExportPreset =
            entries.firstOrNull {
                it != CUSTOM && (it.apply(s, hevcAvailable, true) == s || it.apply(s, hevcAvailable, false) == s)
            } ?: CUSTOM
    }
}

/**
 * The persisted form of [ExportSettings] (ExportJob.settings), one nested
 * object `"settings":{"v":3,...}` written only when the job has settings.
 * Enums travel by NAME. A reader that meets a version or a value it does
 * not know reports [Parsed.Unsupported]: the caller must NOT fall back to
 * the legacy behavior silently (honest-about-the-device).
 *
 * VERSION 2 (E5, night D2) appended `loudnessTargetLufs` (absent = null =
 * OFF, exactly the v1 behavior), `truePeakDbtp` (absent = −1) and the
 * `resolutionW`/`resolutionH` pair (absent = null = the sheet's own ladder
 * decides, exactly the v1 behavior). A v1 row parses with those defaults,
 * so every pre-v2 job keeps its exact old settings and old behavior.
 *
 * VERSION 3 (E5-B, night G1) appends two more optional fields —
 * `audioSampleRateHz` and `audioChannels` (both absent = null = SOURCE:
 * the clip's own rate and channels carry through and the resampler/remix
 * NEVER runs, exactly the v1/v2 behavior). v1 and v2 rows keep parsing
 * with those defaults; a version NEWER than this build still refuses
 * (reported, never guessed).
 */
object ExportSettingsWire {
    const val VERSION = 3
    private const val READS = 3

    sealed class Parsed {
        data class Ok(val settings: ExportSettings) : Parsed()
        data class Unsupported(val reason: String) : Parsed()
    }

    fun render(s: ExportSettings): String {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(VERSION)
            .append(",\"codec\":\"").append(s.codec.name).append('"')
            .append(",\"bitDepth\":").append(s.bitDepth)
            .append(",\"dynamicRange\":\"").append(s.dynamicRange.name).append('"')
            .append(",\"rateMode\":\"").append(s.rateMode.name).append('"')
            .append(",\"bitrateLevel\":\"").append(s.bitrateLevel.name).append('"')
        if (s.customMbps != null) sb.append(",\"customMbps\":").append(s.customMbps)
        sb.append(",\"uncapped\":").append(s.uncapped)
            .append(",\"keyframe\":\"").append(s.keyframe.name).append('"')
        if (s.profile != null) sb.append(",\"profile\":").append(s.profile)
        sb.append(",\"audioKbps\":").append(s.audioKbps)
        // v2 fields, omit-when-default (v1 rows keep their exact bytes):
        // loudnessTargetLufs null = OFF; truePeakDbtp −1 = the default;
        // the resolution pair null = the sheet's own ladder.
        if (s.loudnessTargetLufs != null) {
            sb.append(",\"loudnessTargetLufs\":").append(s.loudnessTargetLufs)
            sb.append(",\"truePeakDbtp\":").append(s.truePeakDbtp)
        } else if (s.truePeakDbtp != -1f) {
            // A ceiling without a target is inert, but it is still the
            // user's value: keep it so the round trip is lossless.
            sb.append(",\"truePeakDbtp\":").append(s.truePeakDbtp)
        }
        if (s.resolutionW != null) sb.append(",\"resolutionW\":").append(s.resolutionW)
        if (s.resolutionH != null) sb.append(",\"resolutionH\":").append(s.resolutionH)
        // v3 fields, omit-when-default (v1/v2 rows keep their exact bytes):
        // a null rate or channel count = SOURCE, never a guessed number.
        if (s.audioSampleRateHz != null) sb.append(",\"audioSampleRateHz\":").append(s.audioSampleRateHz)
        if (s.audioChannels != null) sb.append(",\"audioChannels\":").append(s.audioChannels)
        sb.append('}')
        return sb.toString()
    }

    /** [raw] is the parsed JSON value of the `settings` key (a Map from JsonParser). */
    fun parse(raw: Any?): Parsed {
        val m = raw as? Map<*, *> ?: return Parsed.Unsupported("settings is not an object")
        val v = (m["v"] as? Double)?.toInt() ?: return Parsed.Unsupported("settings has no version")
        if (v > READS) return Parsed.Unsupported("settings version $v (this app reads $READS)")
        fun <E : Enum<E>> enumOf(key: String, all: List<E>): E? =
            (m[key] as? String)?.let { w -> all.firstOrNull { it.name == w } }
        val codec = enumOf("codec", ExportCodec.entries) ?: return Parsed.Unsupported("unknown codec ${m["codec"]}")
        val range = enumOf("dynamicRange", ExportDynamicRange.entries)
            ?: return Parsed.Unsupported("unknown dynamic range ${m["dynamicRange"]}")
        val rate = enumOf("rateMode", ExportRateMode.entries)
            ?: return Parsed.Unsupported("unknown rate mode ${m["rateMode"]}")
        val level = enumOf("bitrateLevel", BitrateLevel.entries)
            ?: return Parsed.Unsupported("unknown bitrate level ${m["bitrateLevel"]}")
        val key = enumOf("keyframe", KeyframeInterval.entries)
            ?: return Parsed.Unsupported("unknown keyframe interval ${m["keyframe"]}")
        val depth = (m["bitDepth"] as? Double)?.toInt() ?: return Parsed.Unsupported("no bit depth")
        val audio = (m["audioKbps"] as? Double)?.toInt() ?: return Parsed.Unsupported("no audio bitrate")
        // v2's optional fields: absent (a v1 row, or a v2 row with the
        // defaults) parses as the v1-behavior defaults — a v1 row reads
        // as EXACTLY what it always meant.
        val loudness = (m["loudnessTargetLufs"] as? Double)?.toFloat()
        val tp = (m["truePeakDbtp"] as? Double)?.toFloat() ?: -1f
        val resW = (m["resolutionW"] as? Double)?.toInt()
        val resH = (m["resolutionH"] as? Double)?.toInt()
        // v3's optional fields: absent (a v1/v2 row, or a v3 row with the
        // defaults) parses as SOURCE — the v1/v2 behavior exactly.
        val audioRate = (m["audioSampleRateHz"] as? Double)?.toInt()
        val audioCh = (m["audioChannels"] as? Double)?.toInt()
        return Parsed.Ok(
            ExportSettings(
                codec = codec,
                bitDepth = depth,
                dynamicRange = range,
                rateMode = rate,
                bitrateLevel = level,
                customMbps = m["customMbps"] as? Double,
                uncapped = m["uncapped"] as? Boolean ?: false,
                keyframe = key,
                profile = (m["profile"] as? Double)?.toInt(),
                audioKbps = audio,
                loudnessTargetLufs = loudness,
                truePeakDbtp = tp,
                resolutionW = resW,
                resolutionH = resH,
                audioSampleRateHz = audioRate,
                audioChannels = audioCh,
            )
        )
    }
}
