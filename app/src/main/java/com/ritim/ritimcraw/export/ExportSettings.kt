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
) {
    companion object {
        val AUDIO_KBPS = listOf(128, 192, 256, 320)
    }
}

/** The one-tap presets (Klipp ExportPreset). Any field that differs makes the settings [CUSTOM]. */
enum class ExportPreset {
    /** H.264, 8-bit, the standard level: plays everywhere. */
    COMPATIBLE,

    /** HEVC where the device has it, the LOW level, 8-bit. */
    SMALLER,

    /**
     * HEVC, the MAX level. 8-bit until package E1d lands the 10-bit path;
     * then it becomes 10-bit where the device and the render path allow it.
     */
    QUALITY,

    CUSTOM;

    /** [s] with this preset applied (unchanged for CUSTOM); [hevcAvailable] false falls back to AVC. */
    fun apply(s: ExportSettings, hevcAvailable: Boolean = true): ExportSettings {
        val hevc = if (hevcAvailable) ExportCodec.HEVC else ExportCodec.AVC
        val base = s.copy(
            dynamicRange = ExportDynamicRange.SDR,
            rateMode = ExportRateMode.VBR,
            customMbps = null,
            uncapped = false,
            keyframe = KeyframeInterval.AUTO,
            profile = null,
        )
        return when (this) {
            COMPATIBLE -> base.copy(codec = ExportCodec.AVC, bitDepth = 8, bitrateLevel = BitrateLevel.STANDARD, audioKbps = 192)
            SMALLER -> base.copy(codec = hevc, bitDepth = 8, bitrateLevel = BitrateLevel.LOW, audioKbps = 128)
            QUALITY -> base.copy(codec = hevc, bitDepth = 8, bitrateLevel = BitrateLevel.MAX, audioKbps = 320)
            CUSTOM -> s
        }
    }

    companion object {
        /** The preset [s] is exactly, or CUSTOM. */
        fun of(s: ExportSettings, hevcAvailable: Boolean = true): ExportPreset =
            entries.firstOrNull { it != CUSTOM && it.apply(s, hevcAvailable) == s } ?: CUSTOM
    }
}

/**
 * The persisted form of [ExportSettings] (ExportJob.settings), one nested
 * object `"settings":{"v":1,...}` written only when the job has settings.
 * Enums travel by NAME. A reader that meets a version or a value it does
 * not know reports [Parsed.Unsupported]: the caller must NOT fall back to
 * the legacy behavior silently (honest-about-the-device).
 */
object ExportSettingsWire {
    const val VERSION = 1

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
        sb.append(",\"audioKbps\":").append(s.audioKbps).append('}')
        return sb.toString()
    }

    /** [raw] is the parsed JSON value of the `settings` key (a Map from JsonParser). */
    fun parse(raw: Any?): Parsed {
        val m = raw as? Map<*, *> ?: return Parsed.Unsupported("settings is not an object")
        val v = (m["v"] as? Double)?.toInt() ?: return Parsed.Unsupported("settings has no version")
        if (v != VERSION) return Parsed.Unsupported("settings version $v (this app reads $VERSION)")
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
            )
        )
    }
}
