package com.ritim.ritimcraw.export.hevc

/**
 * THE output-profile contract model — night research 2026-11-16, package B5
 * (the pure HEVC preflight decision model, FIRST deliverable: the profile
 * contracts, before any encoder is touched). The research's rule, adopted
 * verbatim: HEVC 10-bit SDR, HLG and HDR10 are SEPARATE output profiles /
 * experiments — never one "HDR" toggle with hidden behavior — and 10-bit
 * is NOT the same feature as HDR (a 10-bit SDR profile exists, carries no
 * HDR metadata, and gates on nothing but Main10).
 *
 * PURE AND DELIBERATELY ANDROID-FREE. No android.media import, no
 * MediaFormat object: the framework constants are carried as DATA (their
 * exact numeric values, verified against the installed android-34 SDK
 * jar — the minSdk — and the android-36 sources), so the whole contract
 * is JVM-testable and the device leg maps [MediaFormatEntry] to real
 * MediaFormat setInteger/setByteBuffer calls. Nothing imports this model
 * yet — the integrator wires it into the export UI's profile picker and
 * the writer's HEVC format once the device leg confirms support; the
 * writer (MediaCodecMp4Writer) is NOT touched here.
 *
 * THE FOUR PROFILES (DISTINCT VALUES, never aliases):
 *  - [AVC_SDR] — TODAY'S baseline for comparison, byte-for-byte the
 *    contract MediaCodecMp4Writer.videoFormat already sends: video/avc,
 *    AVCProfileHigh, 8-bit COLOR_FormatYUV420Flexible input (or Surface),
 *    BT709 / SMPTE170m limited. It exists in this model so every decision
 *    below can name it as the honest fallback baseline and the tests can
 *    prove the HEVC profiles differ from it field by field.
 *  - [HEVC10_SDR] — 10-bit Main10, Standard Dynamic Range. Bit depth is
 *    10, transfer is still SMPTE170m, gamut still BT709, NO HDR feature
 *    gate, NO HDR static info. This is the precision profile: the 10-bit
 *    ramp of the develop pipeline survives to the bitstream with no PQ/HLG
 *    curve in the way.
 *  - [HLG] — 10-bit Main10, Hybrid Log-Gamma. BT2020 gamut, ARIB STD-B67
 *    transfer (COLOR_TRANSFER_HLG), no per-frame HDR metadata (HLG is
 *    scene-referred; the encoder needs no static-info blob). Gates on the
 *    codec's HLG editing capability — which is API-GATED (see [feature]).
 *  - [HDR10] — 10-bit Main10HDR10, Perceptual Quantizer. BT2020 gamut,
 *    SMPTE ST2084 transfer (COLOR_TRANSFER_ST2084), CTA-861.3 static
 *    metadata via the "hdr-static-info" key. Gates on the codec's HDR
 *    editing capability (FEATURE_HdrEditing, present since API 29's
 *    constant — readable at minSdk 34).
 *
 * EVERY profile carries its FULL MediaFormat contract as data:
 *  - [mime] — the KEY_MIME value ("video/avc" / "video/hevc"; the docs'
 *    MIMETYPE_VIDEO_AVC / MIMETYPE_VIDEO_HEVC constants).
 *  - [profile] / [level] — the "profile" and "level" keys
 *    (MediaFormat.KEY_PROFILE / KEY_LEVEL, integer values from
 *    MediaCodecInfo.CodecProfileLevel: AVCProfileHigh=8,
 *    HEVCProfileMain10=2, HEVCProfileMain10HDR10=4096, Main-tier levels
 *    1024/16384). The docs' rule is carried in the KDoc: KEY_PROFILE must
 *    be set TOGETHER with KEY_LEVEL for encoders, and the profile must be
 *    one the codec lists in its capabilities.
 *  - [colorStandard] / [colorRange] / [colorTransfer] — the
 *    "color-standard", "color-range" and "color-transfer" keys
 *    (MediaFormat.KEY_COLOR_STANDARD / KEY_COLOR_RANGE /
 *    KEY_COLOR_TRANSFER; COLOR_STANDARD_BT709=1 / COLOR_STANDARD_BT2020=6,
 *    COLOR_RANGE_LIMITED=2, COLOR_TRANSFER_SDR_VIDEO=3 (the docs name it
 *    "SDR video", the standard's name is SMPTE 170M) /
 *    COLOR_TRANSFER_ST2084=6 (PQ) / COLOR_TRANSFER_HLG=7 (ARIB STD-B67)).
 *  - [bitDepth] — the SAMPLE depth of the ENCODED stream (8 for AVC SDR,
 *    10 for every HEVC profile here). NOT a MediaFormat key: the docs are
 *    explicit that KEY_COLOR_STANDARD does not select bit depth and there
 *    is no KEY_BIT_DEPTH — the depth is chosen by the PROFILE constant
 *    (Main10 vs Main) and verified by the trial encode decoding the
 *    output back to a 10-bit-capable format.
 *  - [hdrStaticInfo] — the "hdr-static-info" key (MediaFormat.
 *    KEY_HDR_STATIC_INFO, a ByteBuffer holding the CTA-861.3 static
 *    metadata block). Null for every SDR/HLG profile; HDR10 carries the
 *    marker so the device leg knows to build and set the blob.
 *  - [feature] — the codec-capability gate (see [HevcFeatureGate]).
 *
 * THE HONESTY RULES, from the research, enforced by the shape:
 *  - No support is ASSUMED from a phone or GPU name (no "Adreno means
 *    Main10"): [HevcProbeResult] in HevcPreflight.kt carries only PROBED
 *    facts, and the decision consumes those, never the device model.
 *  - The Surface path is the path being extended (RGBA32F develop ->
 *    encoder Surface); a custom GPU YUV shader is NOT required initially
 *    — the encoder's own color conversion may be used, but EGL config /
 *    surface precision / transfer / gamut / range and the encoder
 *    contract must be verified TOGETHER (the trial-encode plan in
 *    HevcPreflight.kt's KDoc).
 *  - COLOR_FormatYUV420Flexible (2135033992) is 8-bit; P010
 *    (COLOR_FormatYUVP010=54, ImageFormat.YCBCR_P010) is 10-bit 4:2:0
 *    semiplanar; the BUFFER alternative is added ONLY if the encoder
 *    truly accepts it — a PROBED fact in [HevcProbeResult.acceptsP010],
 *    never an assumption of this model.
 */
enum class HevcProfile(
    /** The MIME type for MediaFormat.KEY_MIME ("video/avc"/"video/hevc"). */
    val mime: String,
    /** The sample bit depth of the ENCODED output stream. */
    val bitDepth: Int,
    /** KEY_PROFILE value (MediaCodecInfo.CodecProfileLevel constant). */
    val profile: Int,
    /** KEY_LEVEL value, set TOGETHER with KEY_PROFILE for encoders. */
    val level: Int,
    /** KEY_COLOR_STANDARD value (BT709=1 / BT2020=6). */
    val colorStandard: Int,
    /** KEY_COLOR_RANGE value (limited=2 everywhere here). */
    val colorRange: Int,
    /** KEY_COLOR_TRANSFER value (SMPTE170m=3 / PQ=6 / HLG=7). */
    val colorTransfer: Int,
    /** The standard's NAME for the transfer, for UI/log lines. */
    val transferName: String,
    /** The standard's NAME for the color standard/primaries. */
    val colorStandardName: String,
    /** Whether KEY_HDR_STATIC_INFO ("hdr-static-info") must be set. */
    val requiresHdrStaticInfo: Boolean,
    /** The codec-capability gate, or null when the profile gates on nothing. */
    val feature: HevcFeatureGate?,
) {
    /**
     * TODAY'S baseline — the exact contract MediaCodecMp4Writer's
     * videoFormat(w, h, surface) sends today (video/avc,
     * AVCProfileHigh=8 with AVCLevel41=4096, BT709 /
     * SMPTE170m limited, 8-bit input via Surface or
     * COLOR_FormatYUV420Flexible). In this model for COMPARISON and as
     * the named baseline of the degradation offer; choosing it is
     * today's behavior, nothing new.
     */
    AVC_SDR(
        mime = "video/avc",
        bitDepth = 8,
        profile = 8,          // AVCProfileHigh
        level = 4096,          // AVCLevel41
        colorStandard = 1,    // COLOR_STANDARD_BT709
        colorRange = 2,        // COLOR_RANGE_LIMITED
        colorTransfer = 3,    // COLOR_TRANSFER_SDR_VIDEO (SMPTE 170M)
        transferName = "SMPTE170m",
        colorStandardName = "BT709",
        requiresHdrStaticInfo = false,
        feature = null,
    ),

    /**
     * 10-bit Main10, Standard Dynamic Range — the precision profile.
     * Same transfer/gamut as today's AVC SDR (SMPTE170m, BT709,
     * limited), but the stream samples at 10 bits: the develop
     * pipeline's 10-bit ramp survives without any HDR curve. Gates on
     * the codec LISTING Main10 (profile=2) in its capabilities — no
     * FEATURE_* gate, no HDR metadata. The docs' Main10 definition:
     * HEVCProfileMain10=2.
     */
    HEVC10_SDR(
        mime = "video/hevc",
        bitDepth = 10,
        profile = 2,          // HEVCProfileMain10
        level = 16384,         // HEVCMainTierLevel5 (4K30 fits)
        colorStandard = 1,    // COLOR_STANDARD_BT709
        colorRange = 2,        // COLOR_RANGE_LIMITED
        colorTransfer = 3,    // COLOR_TRANSFER_SDR_VIDEO (SMPTE 170M)
        transferName = "SMPTE170m",
        colorStandardName = "BT709",
        requiresHdrStaticInfo = false,
        feature = null,
    ),

    /**
     * 10-bit Main10, Hybrid Log-Gamma — ARIB STD-B67 transfer, BT2020
     * gamut, NO HDR static metadata (scene-referred: the HLG signal is
     * display-referred-in-signal, and the docs' HLG-editing feature is
     * explicit that HLG "does not require the generation of HDR metadata
     * and does not use an explicit HDR profile"). Gates on the codec's
     * HLG editing capability — WHICH IS API-GATED: FEATURE_HlgEditing
     * ("hlg-editing") arrived with Android 15 / API 35 (research
     * 2026-11-23, plan 13.2; the local SDK jars are android-34 and
     * android-36, so 35 could not be read off a jar). The feature is
     * queried by its STRING name, never by the compile-time constant,
     * so the code does not depend on the SDK that happens to be
     * installed; an unknown feature name just answers false. The gate
     * carries the required API level as data and the probe reports the
     * device's API level.
     *
     * At API 34 the HLG feature cannot exist, but HLG is still
     * reachable: Main10 + BT.2020 + HLG + limited range encodes
     * directly through MediaCodec. The gate therefore names a
     * [HevcFeatureGate.belowApiFallback] (FEATURE_HdrEditing, API 33);
     * HevcPreflight lets an API-34 device through on that flag and
     * marks the verdict `trialEncodeRequired` (the trial encode itself
     * is package E2).
     */
    HLG(
        mime = "video/hevc",
        bitDepth = 10,
        profile = 2,          // HEVCProfileMain10
        level = 16384,         // HEVCMainTierLevel5
        colorStandard = 6,    // COLOR_STANDARD_BT2020
        colorRange = 2,        // COLOR_RANGE_LIMITED
        colorTransfer = 7,    // COLOR_TRANSFER_HLG (ARIB STD-B67)
        transferName = "HLG",
        colorStandardName = "BT2020",
        requiresHdrStaticInfo = false,
        feature = HevcFeatureGate(
            featureName = "hlg-editing",   // FEATURE_HlgEditing, queried by string
            requiredApiLevel = 35,         // Android 15
            docName = "FEATURE_HlgEditing",
            belowApiFallback = HevcFeatureGate(
                featureName = "hdr-editing",   // FEATURE_HdrEditing
                requiredApiLevel = 33,         // Android 13
                docName = "FEATURE_HdrEditing",
            ),
        ),
    ),

    /**
     * 10-bit Main10HDR10, Perceptual Quantizer — SMPTE ST2084 transfer,
     * BT2020 gamut, CTA-861.3 static metadata via the "hdr-static-info"
     * key (MediaFormat.KEY_HDR_STATIC_INFO: the docs require it for an
     * HDR encode — the encoder's output container/tag contract). Gates
     * on the codec's HDR editing capability, FEATURE_HdrEditing
     * ("hdr-editing", present in the android-34 SDK — readable at
     * minSdk). The docs' own words for the feature: the codec "accepts
     * 10-bit HDR input surface, and it is capable of generating any HDR
     * metadata required from both YUV and RGB input when the metadata
     * is not present", implying RGBA_1010102, P010 and optionally
     * RGBA_FP16 input support.
     */
    HDR10(
        mime = "video/hevc",
        bitDepth = 10,
        profile = 4096,       // HEVCProfileMain10HDR10
        level = 16384,         // HEVCMainTierLevel5
        colorStandard = 6,    // COLOR_STANDARD_BT2020
        colorRange = 2,        // COLOR_RANGE_LIMITED
        colorTransfer = 6,    // COLOR_TRANSFER_ST2084 (PQ)
        transferName = "PQ",
        colorStandardName = "BT2020",
        requiresHdrStaticInfo = true,
        feature = HevcFeatureGate(
            featureName = "hdr-editing",   // FEATURE_HdrEditing
            requiredApiLevel = 29,         // constant exists since API 29
            docName = "FEATURE_HdrEditing",
        ),
    );

    /** True only for the HEVC profiles (the research's separate experiments). */
    val isHevc: Boolean get() = mime == "video/hevc"

    /** True only for the 10-bit HEVC profiles (10-bit != HDR). */
    val isTenBit: Boolean get() = bitDepth >= 10

    /** The profile's human label for the picker and the log lines. */
    val label: String get() = when (this) {
        AVC_SDR -> "AVC 8-bit SDR (current)"
        HEVC10_SDR -> "HEVC 10-bit SDR"
        HLG -> "HEVC HLG"
        HDR10 -> "HEVC HDR10"
    }

    /**
     * The profile's MediaFormat contract as (key, value) pairs, in the
     * order the device leg sets them: mime, profile, level,
     * color-standard, color-range, color-transfer (the same key order
     * MediaCodecMp4Writer.videoFormat uses today, so a diff of the
     * AVC_SDR entry against the writer's format is honest). The
     * bit-rate/frame-rate/i-frame keys are NOT here: they depend on the
     * job's size/rate, not the profile — the writer adds them exactly as
     * it does today.
     */
    fun mediaFormatEntries(): List<MediaFormatEntry> = listOf(
        MediaFormatEntry("mime", mime),
        MediaFormatEntry("profile", profile.toString()),
        MediaFormatEntry("level", level.toString()),
        MediaFormatEntry("color-standard", colorStandard.toString()),
        MediaFormatEntry("color-range", colorRange.toString()),
        MediaFormatEntry("color-transfer", colorTransfer.toString()),
    )
}

/**
 * One (key, value) pair of a profile's MediaFormat contract — the pure
 * shape the device leg turns into MediaFormat.setInteger /
 * setByteBuffer calls. The key strings are the Android docs' EXACT
 * MediaFormat key names (KEY_PROFILE="profile", KEY_LEVEL="level",
 * KEY_COLOR_STANDARD="color-standard", KEY_COLOR_RANGE="color-range",
 * KEY_COLOR_TRANSFER="color-transfer", KEY_MIME="mime",
 * KEY_HDR_STATIC_INFO="hdr-static-info", KEY_COLOR_FORMAT="color-format",
 * KEY_BIT_RATE="bitrate", KEY_FRAME_RATE="frame-rate",
 * KEY_I_FRAME_INTERVAL="i-frame-interval"), carried as strings so this
 * file stays Android-free and the tests assert the exact names.
 */
data class MediaFormatEntry(val key: String, val value: String)

/**
 * The codec-capability gate one profile carries as DATA — the research's
 * rule that FEATURE_HdrEditing (and the version-gated
 * FEATURE_HlgEditing) must be QUERIED, and that an API-35+ feature must
 * not be called unguarded on minSdk 34. The gate names the feature and
 * the API level its CONSTANT exists at; the device leg consults
 * [HevcProbeResult] (the PROBED fact: does the codec's capabilities list
 * the feature, at THIS device's API level) and the decision in
 * HevcPreflight refuses the profile when the gate is not satisfied.
 *
 * These are MediaCodecInfo.CodecCapabilities features (queried via
 * getCapabilitiesForType(...).isFeatureSupported(name)) — NOT
 * PackageManager system features; the docs' KDoc for both says "video
 * encoder only".
 */
data class HevcFeatureGate(
    /** The capability name to query (the docs' FEATURE_* string value). */
    val featureName: String,
    /** The API level the FEATURE constant exists at (the guard data). */
    val requiredApiLevel: Int,
    /** The docs' constant name, for KDoc/log lines. */
    val docName: String,
    /**
     * The gate to ask INSTEAD when the device is below [requiredApiLevel]
     * (null = no such path: refuse on the API gate). HLG uses it for the
     * API 34 path (plan 13.2): FEATURE_HdrEditing (API 33) stands in for
     * the missing FEATURE_HlgEditing; the verdict then carries
     * `trialEncodeRequired`, because that flag is not HLG-specific and
     * only a real trial encode (package E2) proves HLG output.
     */
    val belowApiFallback: HevcFeatureGate? = null,
)
