package com.ritim.ritimcraw.export.hevc

/**
 * THE pure HEVC preflight decision — night research 2026-11-16, package
 * B5 (the decision half of the model; the profile contracts live in
 * HevcProfile.kt). Given a PROBE RESULT SET (the device facts, gathered
 * by the device leg) and the user's REQUESTED profile, produce the
 * verdict: SUPPORTED, REFUSED (with the failed stage and the codec
 * name — the honesty rule), or the refusal's DEGRADED_OFFER (the
 * explicit "AVC 8-bit is available, choose" state — never a silent
 * fallback).
 *
 * PURE AND DELIBERATELY ANDROID-FREE, like the probe precedents
 * (CodecProbe's probe/format split, ExportResolution's CodecSupport
 * seam): [HevcProbeResult] is the pure data class the device leg fills
 * from MediaCodecList / MediaCodecInfo on the device, and [decide] is
 * the pure decision consuming it. Nothing here queries a device — the
 * model carries NO assumed support (the research's rule: no support is
 * inferred from the phone/Adreno NAME; everything arrives as a probed
 * fact or the verdict refuses).
 *
 * THE DECISION STAGES (in order; the FIRST failure names itself):
 *
 * 1. CODEC — does an encoder for the request's MIME exist (video/hevc
 *    for the HEVC profiles, video/avc for the baseline)? The probe
 *    carries the fact and the codec's NAME. No encoder -> REFUSED with
 *    [HevcRefusalStage.CODEC_LIST] and codec name "none". The
 *    stage/codec pair is the honesty rule's shape: the refusal says
 *    WHAT failed and WHICH codec, never a bare "no".
 *
 * 2. PROFILE — does the chosen encoder's capabilities LIST the profile
 *    the user picked (profileLevels: Main10=2 for HEVC10_SDR and HLG;
 *    Main10HDR10=4096 for HDR10)? An unlisted profile -> REFUSED with
 *    [HevcRefusalStage.PROFILE_LEVEL] and the codec name. If the user
 *    chose Main10 and it is unavailable, the verdict is REFUSED — NEVER
 *    a silent AVC/8-bit substitution (the research's explicit rule);
 *    the degradation to AVC_SDR happens ONLY as the user's explicit
 *    choice through the refusal's DEGRADED_OFFER.
 *
 * 3. FEATURE GATE — for HDR10 (FEATURE_HdrEditing) and HLG
 *    (FEATURE_HlgEditing): (a) the API GUARD — the gate's
 *    [HevcFeatureGate.requiredApiLevel] against the device's API level
 *    in the probe; FEATURE_HlgEditing's constant does not exist at
 *    minSdk 34, so at API 34 the HLG verdict is REFUSED with
 *    [HevcRefusalStage.FEATURE_API] (the device leg must NOT query the
 *    feature by name there — the model refuses before any query); and
 *    (b) the PROBED feature — does the codec's capabilities list the
 *    gate's featureName (isFeatureSupported)? Absent -> REFUSED with
 *    [HevcRefusalStage.FEATURE_QUERY] and the codec name.
 *
 * 4. SIZE/RATE — does the codec's video capabilities accept the job's
 *    width x height at the frame rate (areSizeAndRateSupported — the
 *    same seam ExportResolution.CodecSupport and EditorTopBar's
 *    deviceCodecSupport use)? Refused -> REFUSED with
 *    [HevcRefusalStage.SIZE_RATE] and the codec name. Portrait 4K
 *    (2160x3840) is the case the research named: the probe must test the
 *    TRANSPOSED dims, and the trial encode exercises portrait too.
 *
 * THE INPUT PATH is a CONSEQUENCE, never a refusal stage. The SURFACE
 * path (RGBA32F develop -> encoder Surface, the path being extended) is
 * the PRIMARY of every SUPPORTED verdict; the BUFFER path (P010,
 * COLOR_FormatYUVP010=54 — 10-bit 4:2:0 semiplanar) is an ALTERNATIVE
 * the verdict appends ONLY when [HevcProbeResult.acceptsP010] says the
 * encoder truly accepts it — a PROBED fact, never an assumption. P010
 * ABSENT is NOT a refusal: the verdict stays SUPPORTED with the Surface
 * path alone (COLOR_FormatYUV420Flexible, 2135033992, is 8-bit and
 * CANNOT carry the 10-bit stream's samples through buffers, so the
 * Surface path is the one that must work; a Surface-path failure is
 * discovered by the trial encode — the device leg's short encode — not
 * pre-guessed here). P010 is never offered for the 8-bit AVC baseline.
 *
 * THE DEGRADED_OFFER STATE. When the requested profile is refused and
 * the probe names an AVC encoder ([HevcProbeResult.avcEncoderName]), the
 * Refused verdict carries the explicit offer: "AVC 8-bit available,
 * choose" — the USER decides; the model NEVER silently encodes AVC when
 * Main10 was requested. The offer names the refused profile, the
 * baseline ([HevcProfile.AVC_SDR]) and the AVC encoder's probed name —
 * one honest sentence, no hidden behavior. A refusal WITHOUT an offer
 * is the honest "nothing to fall back to" case (no AVC encoder, or the
 * request WAS the baseline).
 *
 * THE TRIAL-ENCODE INTERFACE PLAN (the device leg's contract — the
 * research's sequence, as the interface the device leg implements; the
 * plan is NAMED here, EncoderDiagnostics is NOT edited):
 *
 * The probe answers "does the device claim it"; the TRIAL ENCODE answers
 * "does it actually do it". Sequence (one trial per requested profile,
 * short — a handful of frames, not a clip):
 *
 *   1. CODEC LIST — MediaCodecList(REGULAR_CODECS), encoders only,
 *      video/hevc: the codec's name/canonical/hardware flags go to the
 *      EXTENDED EncoderDiagnostics record (below). The probe result
 *      set's fields come from THIS pass.
 *   2. FULL FORMAT — build the MediaFormat from the profile's
 *      [HevcProfile.mediaFormatEntries] + the job's width/height,
 *      "bitrate", "frame-rate", "i-frame-interval", and
 *      "color-format" (COLOR_FormatSurface=2130708361 for the Surface
 *      path; COLOR_FormatYUVP010=54 only when the probe said the encoder
 *      accepts it), plus "hdr-static-info" (CTA-861.3 blob) for HDR10.
 *      KEY_PROFILE and KEY_LEVEL are set TOGETHER (the docs' encoder
 *      rule). KEY_COLOR_TRANSFER_REQUEST is NOT used on the encode
 *      path (it is a tone-map request key whose support is echoed in
 *      the input format after configure); the profile's "color-transfer"
 *      IS the contract.
 *   3. CONFIGURE + START — configure(codec, format, surface-for-Surface-
 *      path) then start(); a configure/start failure is a REFUSED
 *      trial with the stage recorded (the SurfaceInputStep vocabulary
 *      the writer already uses — CODEC_CONFIG, CODEC_START — plus the
 *      HEVC trial stages named in [HevcRefusalStage]).
 *   4. SHORT ENCODE — N frames of a KNOWN pattern (the 10-bit ramp: a
 *      horizontal gradient whose adjacent steps differ by exactly one
 *      10-bit code value — the banding-free proof), presented through
 *      the EGL window surface on the encoder's input Surface with
 *      eglPresentationTimeANDROID stamps (the writer's §10 contract),
 *      then signalEndOfInputStream; for the BUFFER path, the same ramp
 *      packed into P010 (16-bit little-endian words, the 10 data bits in
 *      the MSBs, lower 6 bits zero — the ImageFormat.YCBCR_P010 doc).
 *   5. DECODE BACK — decode the encoder's OUTPUT bitstream with a
 *      video/hevc decoder into a 10-bit-capable output
 *      (ImageFormat.YCBCR_P010 / getOutputImage) and compare: the
 *      PROFILE on the OUTPUT (the decoder's output format must report
 *      the Main10/Main10HDR10 profile and the bitstream's VUI must carry
 *      the profile's color-standard/range/transfer — the tags ON THE
 *      OUTPUT, not the request), the RAMP comparison (adjacent 10-bit
 *      steps must come back DISTINCT — an 8-bit round-trip collapses
 *      them, which is the 10-bit proof), and the SIZE (the output
 *      format's width/height must equal the requested dims — portrait
 *      4K 2160x3840 included, the research's portrait case).
 *   6. EDGE CASES from the research's Main10 test list: PORTRAIT 4K
 *      (transposed dims through the whole trial), SINGLE-FRAME EOS
 *      (one frame then end-of-stream must produce exactly one encoded
 *      frame — the last-frame-lost failure mode), and AUDIO/PTS (the
 *      trial runs video-only, but the PTS stamps must come back
 *      monotonic in the output — the muxer's contract; the audio leg is
 *      the writer's existing AAC path, unchanged).
 *
 * ENCODERDIAGNOSTICS EXTENSION (NAMED, not edited — EncoderDiagnostics
 * today logs identity only: name, canonical, hardware, softwareOnly,
 * requested). The device leg's record EXTENDS the shape with the
 * capability + trial fields, as a SIBLING data class (the existing
 * EncoderDiagnostics.logLine stays byte-identical — the writer's
 * existing line must not change):
 *
 *   data class HevcEncoderDiagnostics(
 *       val identity: EncoderDiagnostics,          // the existing record
 *       val listedProfiles: List<Int>,            // profileLevels' profiles
 *       val acceptsP010: Boolean,                 // color formats probed
 *       val hdrEditingSupported: Boolean,         // FEATURE_HdrEditing
 *       val hlgEditingSupported: Boolean,         // FEATURE_HlgEditing (API 36+ only)
 *       val sizeRateAccepted: Boolean,            // areSizeAndRateSupported at the job's dims
 *       val trial: HevcTrialResult?,             // null until the trial ran
 *   )
 *   data class HevcTrialResult(
 *       val profileRequested: HevcProfile,
 *       val configured: Boolean,                  // configure+start ok
 *       val framesIn: Int, val framesOut: Int,   // single-frame EOS check
 *       val outputProfile: Int,                  // decoder-reported profile
 *       val outputBitDepth: Int,                 // from the decode-back
 *       val rampStepsDistinct: Boolean,          // the 10-bit proof
 *       val outputColorTagsMatch: Boolean,       // standard/range/transfer on the OUTPUT
 *       val outputDimsMatch: Boolean,             // incl. portrait 4K
 *       val ptsMonotonic: Boolean,
 *   )
 *
 * The device leg logs it under the writer's existing "ritim-export" tag
 * as one line per trial (the EncoderDiagnostics.logLine precedent,
 * extended — the existing line stays untouched).
 *
 * WHERE THE EGL PRECISION QUESTION LIVES (the integrator's decision,
 * cited not solved here): the writer's EGL session today configures
 * RGBA8888 8-bit attribs (GlesPipeline.cpp EglSession::open, the
 * configAttribs at :176-188: EGL_RED/GREEN/BLUE/ALPHA_SIZE 8,
 * EGL_OPENGL_ES3_BIT, pbuffer(+window) bits, EGL_RECORDABLE_ANDROID
 * for MediaCodec input surfaces), while the develop FBO is RGBA32F
 * (GlesPipeline.cpp:832-851, requiring EXT_color_buffer_float at :724).
 * The research's rule: the Surface export path's END-TO-END precision
 * is the thing to extend (RGBA32F develop -> encoder Surface) — do NOT
 * design around RGBA32F -> RGB888 -> 10-bit repack. The encoder's own
 * color conversion may be used on the Surface path (a custom GPU YUV
 * shader is NOT required initially), but EGL config / surface precision
 * / transfer / gamut / range and the encoder contract must be verified
 * TOGETHER — the trial encode's step 5 is exactly that verification.
 * The concrete decision — which EGL surface config feeds a 10-bit
 * encoder Surface (today's RGBA8888 window surface with the encoder
 * converting, vs a 10-bit EGL config such as RGBA1010102) — belongs to
 * the device leg with the writer's EGL session, and is recorded here as
 * the integrator's first decision.
 */
object HevcPreflight {

    /**
     * The device facts — PURE DATA the device leg fills from
     * MediaCodecList/MediaCodecInfo; every field is a PROBED fact, none
     * an assumption (no support inferred from a phone/GPU name). JVM
     * tests build SYNTHETIC instances (clearly labeled there); the
     * device leg builds the real one.
     *
     * @property hevcEncoderName the chosen HEVC encoder's MediaCodec
     * NAME (null = no video/hevc encoder on the device).
     * @property listedProfiles the HEVC encoder's profileLevels profiles
     * (MediaCodecInfo.CodecProfileLevel.profile values — 2 for Main10,
     * 4096 for Main10HDR10).
     * @property acceptsP010 PROBED: does the HEVC encoder's color-format
     * list contain COLOR_FormatYUVP010 (54)? The BUFFER alternative is
     * added ONLY if this is true — never assumed.
     * @property sizeRateAccepted PROBED: did the encoder's video
     * capabilities accept the job's width x height at the frame rate
     * (areSizeAndRateSupported, the TRANSPOSED dims for portrait)?
     * @property deviceApiLevel the device's Build.VERSION.SDK_INT — the
     * API guard data for the version-gated features.
     * @property hdrEditingSupported PROBED: does the HEVC encoder's
     * capabilities list "hdr-editing" (FEATURE_HdrEditing)? Only
     * meaningful for HDR10.
     * @property hlgEditingSupported PROBED at API 36+: does the HEVC
     * encoder's capabilities list "hlg-editing" (FEATURE_HlgEditing)?
     * At API 34/35 the constant does not exist — the device leg reports
     * false and the model's API gate refuses HLG before any query.
     * @property avcEncoderName the AVC encoder's MediaCodec NAME (null =
     * no video/avc encoder) — the degradation offer's fact and the
     * AVC_SDR baseline's codec.
     */
    data class HevcProbeResult(
        val hevcEncoderName: String?,
        val listedProfiles: List<Int>,
        val acceptsP010: Boolean,
        val sizeRateAccepted: Boolean,
        val deviceApiLevel: Int,
        val hdrEditingSupported: Boolean,
        val hlgEditingSupported: Boolean,
        val avcEncoderName: String?,
    ) {
        /** The degradation offer's fact, derived: an AVC encoder exists. */
        val avcAvailable: Boolean get() = avcEncoderName != null
    }

    /** The stage that failed — the honesty rule's first half. */
    enum class HevcRefusalStage(val token: String) {
        /** No encoder for the request's MIME on the device. */
        CODEC_LIST("codec-list"),

        /** The encoder does not list the requested profile. */
        PROFILE_LEVEL("profile-level"),

        /** The profile's FEATURE gate needs an API the device lacks. */
        FEATURE_API("feature-api"),

        /** The encoder's capabilities lack the profile's FEATURE. */
        FEATURE_QUERY("feature-query"),

        /** The encoder refused the job's size/rate. */
        SIZE_RATE("size-rate"),
    }

    /** How frames reach the encoder (the SUPPORTED verdict's paths). */
    enum class HevcInputPath(val token: String) {
        /** RGBA32F develop -> encoder input Surface (the path extended). */
        SURFACE("surface"),

        /** P010 buffers — the probed ALTERNATIVE, never the primary. */
        P010_BUFFERS("p010-buffers"),
    }

    /**
     * The verdict: SUPPORTED (with the input paths, primary first), or
     * REFUSED (with the stage + codec name + the explicit degraded
     * offer when an AVC encoder exists). Sealed: the caller must handle
     * every case; there is no hidden default behavior and no third
     * state — the "degraded" outcome is the OFFER carried by a refusal,
     * because the user's requested profile was NOT delivered.
     */
    sealed class HevcVerdict {

        /**
         * The requested profile is probed-supported. [paths] lists the
         * viable input paths, PRIMARY FIRST: the Surface path is always
         * there (the path being extended; the trial encode verifies it
         * end to end), and the P010 buffer path is appended only when
         * the encoder was PROBED to accept it. It is never empty and
         * never contains P010 for the 8-bit AVC baseline.
         */
        data class Supported(
            val profile: HevcProfile,
            val codecName: String,
            val paths: List<HevcInputPath>,
        ) : HevcVerdict()

        /**
         * The requested profile is refused. [stage] + [codecName] is
         * the honesty rule (what failed, which codec);
         * [degradedOffer] is non-null exactly when the probe named an
         * AVC encoder and the request was not the baseline itself — the
         * EXPLICIT offer ("AVC 8-bit available, choose"), the user's
         * choice, never a silent substitution.
         */
        data class Refused(
            val profile: HevcProfile,
            val stage: HevcRefusalStage,
            val codecName: String,
            val reason: String,
            val degradedOffer: DegradedOffer?,
        ) : HevcVerdict()

        /**
         * The EXPLICIT DEGRADED_OFFER state: the user's requested
         * profile was refused, an AVC 8-bit baseline exists, and the
         * USER must choose — the model never silently encodes AVC when
         * Main10 was requested. Carried inside a Refused verdict as the
         * offer (a refusal WITHOUT an offer is the honest "nothing to
         * fall back to" case).
         */
        data class DegradedOffer(
            val refusedProfile: HevcProfile,
            val baseline: HevcProfile,
            val baselineCodecName: String,
        ) {
            /** The one honest sentence the UI shows — no hidden behavior. */
            val offerText: String =
                "${refusedProfile.label} is not available on this device. " +
                    "${baseline.label} is available" +
                    " ($baselineCodecName). Choose: export as ${baseline.label} " +
                    "or keep ${refusedProfile.label} (refused) — never a silent fallback."
        }
    }

    /**
     * THE decision. Pure: the same probe facts + the same request
     * always produce the same verdict. Stages in order — codec list,
     * profile, feature gate (API first, then the probed feature),
     * size/rate; the input paths are a CONSEQUENCE (Surface primary;
     * P010 buffers appended only when probed), never a refusal stage.
     * A refusal carries the explicit DEGRADED_OFFER when an AVC
     * encoder exists. The requested profile being AVC_SDR is today's
     * behavior: supported as-is when the probe names an AVC encoder,
     * refused honestly when it names none.
     */
    fun decide(requested: HevcProfile, probe: HevcProbeResult): HevcVerdict {
        // Stage 1: the codec list. The HEVC profiles ask the HEVC fact;
        // the AVC baseline asks the AVC fact (today's behavior).
        val codecName = if (requested.isHevc) probe.hevcEncoderName else probe.avcEncoderName
        if (codecName == null) {
            return refused(
                requested, probe,
                HevcRefusalStage.CODEC_LIST,
                "none",
                "no ${requested.mime} encoder is available on this device",
            )
        }

        // Stage 2: the profile. The probe lists the HEVC encoder's
        // profiles; an AVC request does not consult them (today's
        // AVCProfileHigh contract is the writer's, unchanged).
        if (requested.isHevc && requested.profile !in probe.listedProfiles) {
            return refused(
                requested, probe,
                HevcRefusalStage.PROFILE_LEVEL,
                codecName,
                "$codecName does not list ${requested.label}'s profile " +
                    "(${requested.profile}); listed: ${probe.listedProfiles.sorted()}",
            )
        }

        // Stage 3: the feature gate — API guard FIRST (an API-35+
        // feature must not be queried unguarded on minSdk 34), then the
        // probed feature. AVC_SDR and HEVC10_SDR gate on nothing.
        val gate = requested.feature
        if (gate != null) {
            if (probe.deviceApiLevel < gate.requiredApiLevel) {
                return refused(
                    requested, probe,
                    HevcRefusalStage.FEATURE_API,
                    codecName,
                    "${requested.label} requires ${gate.docName} " +
                        "(API ${gate.requiredApiLevel}+); device is API " +
                        "${probe.deviceApiLevel} — the feature is not " +
                        "queried on this device",
                )
            }
            val supported = when (gate.docName) {
                "FEATURE_HdrEditing" -> probe.hdrEditingSupported
                "FEATURE_HlgEditing" -> probe.hlgEditingSupported
                else -> false
            }
            if (!supported) {
                return refused(
                    requested, probe,
                    HevcRefusalStage.FEATURE_QUERY,
                    codecName,
                    "$codecName does not support ${gate.docName} " +
                        "(\"${gate.featureName}\") at API ${probe.deviceApiLevel}",
                )
            }
        }

        // Stage 4: size/rate (the probe already used the job's dims —
        // transposed for portrait; the trial encode re-verifies).
        if (!probe.sizeRateAccepted) {
            return refused(
                requested, probe,
                HevcRefusalStage.SIZE_RATE,
                codecName,
                "$codecName does not accept the requested size/rate",
            )
        }

        // All stages passed: SUPPORTED, with the input paths a probed
        // CONSEQUENCE — the Surface path is the primary (the path being
        // extended); P010 buffers are the ALTERNATIVE, appended only
        // when probed, and never offered for the 8-bit baseline.
        val paths = buildList {
            add(HevcInputPath.SURFACE)
            if (requested.isHevc && requested.isTenBit && probe.acceptsP010) {
                add(HevcInputPath.P010_BUFFERS)
            }
        }
        return HevcVerdict.Supported(
            profile = requested,
            codecName = codecName,
            paths = paths,
        )
    }

    /**
     * One refusal, with the offer rule applied: a refused request
     * carries the explicit DEGRADED_OFFER exactly when the probe named
     * an AVC encoder AND the request was not the baseline itself (a
     * refused AVC request has nothing to degrade to; a device with no
     * AVC encoder has nothing to offer). The offer names the probed AVC
     * encoder — the user chooses, NEVER a silent AVC/8-bit fallback.
     */
    private fun refused(
        requested: HevcProfile,
        probe: HevcProbeResult,
        stage: HevcRefusalStage,
        codec: String,
        reason: String,
    ): HevcVerdict.Refused =
        HevcVerdict.Refused(
            profile = requested,
            stage = stage,
            codecName = codec,
            reason = reason,
            degradedOffer = if (probe.avcAvailable && requested != HevcProfile.AVC_SDR) {
                HevcVerdict.DegradedOffer(
                    refusedProfile = requested,
                    baseline = HevcProfile.AVC_SDR,
                    baselineCodecName = probe.avcEncoderName ?: "video/avc",
                )
            } else {
                null
            },
        )
}
