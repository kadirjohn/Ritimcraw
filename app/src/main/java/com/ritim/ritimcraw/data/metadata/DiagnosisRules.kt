package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind

/**
 * The pure IN-13 diagnosis rules: Dolby Vision and LOG profile, decided
 * from the same inputs the extraction pass gathers. PURE functions — no
 * platform types, no MediaFormat — so they run in JVM unit tests and the
 * device-gated extractor just feeds them observed booleans.
 *
 * THE SPEC'S DISTINCTION (IN-13): a DV/LOG DIAGNOSIS is a STATE of the
 * source — what the file IS. HDR OUTPUT SUPPORT is a CAPABILITY — what
 * the device/app CAN do with it. The two live in different enums
 * ([Diagnosis]/[LogDiagnosis] vs [Capability]) and neither is ever
 * inferred from the other: a DV file on a device without DV output is
 * DV_DETECTED with hdrOutputSupport NOT_SUPPORTED, and an SDR file never
 * gains a capability from its diagnosis.
 */
object DiagnosisRules {

    /**
     * Dolby Vision diagnosis from the observed track facts.
     *
     * RULES (honest by construction):
     *  - MCRAW (RAW) files are never DV: the container is MotionCam's
     *    own RAW format — [Diagnosis.NOT_DV] is a fact about the
     *    format, not a guess.
     *  - The track could not be read → [Diagnosis.INSUFFICIENT_DATA] —
     *    never a silent NOT_DV.
     *  - The track's mime is a Dolby Vision mime →
     *    [Diagnosis.DV_DETECTED].
     *  - The track was read and is not DV-typed → [Diagnosis.NOT_DV].
     *
     * The mime comparison itself is done by the caller against the
     * platform constant (MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) —
     * this rule takes the boolean the caller derived, so it stays
     * JVM-pure.
     *
     * @param kind the source kind (MCRAW is RAW, never DV)
     * @param trackRead whether an encoded video track's format was read
     * @param trackIsDolbyVision whether that track's mime is a DV mime
     */
    fun dolbyVision(
        kind: SourceKind,
        trackRead: Boolean,
        trackIsDolbyVision: Boolean,
    ): Diagnosis = when {
        kind == SourceKind.MCRAW -> Diagnosis.NOT_DV
        !trackRead -> Diagnosis.INSUFFICIENT_DATA
        trackIsDolbyVision -> Diagnosis.DV_DETECTED
        else -> Diagnosis.NOT_DV
    }

    /**
     * LOG profile diagnosis.
     *
     * RULES:
     *  - MCRAW is RAW sensor data, NOT a LOG encoding. RAW is not LOG:
     *    the container holds linear sensor samples and LOG is a
     *    transfer applied when ENCODING video, so [LogDiagnosis.NOT_LOG]
     *    is a fact about the format, not a guess.
     *  - No track facts at all → [LogDiagnosis.INSUFFICIENT_DATA] —
     *    no honest claim either way.
     *  - The encoded path's existing classification identified a LOG
     *    profile (this app's Mi-Log HEVC files — the develop stage
     *    applies the Mi-Log decode) → [LogDiagnosis.LOG_DETECTED].
     *  - A track was read but not classified LOG →
     *    [LogDiagnosis.INSUFFICIENT_DATA]: claiming NOT_LOG would mean
     *    we KNOW the file is not log-encoded, when we merely could not
     *    tell (the container tags carry no LOG signal — the real MP4
     *    fixture's transfer is untagged).
     *
     * @param kind the source kind
     * @param trackRead whether an encoded track was read at all
     * @param encodedClassifiedLog whether the encoded path's existing
     *   classification identified a LOG profile (Mi-Log)
     */
    fun logProfile(
        kind: SourceKind,
        trackRead: Boolean,
        encodedClassifiedLog: Boolean,
    ): LogDiagnosis = when {
        kind == SourceKind.MCRAW -> LogDiagnosis.NOT_LOG
        !trackRead -> LogDiagnosis.INSUFFICIENT_DATA
        encodedClassifiedLog -> LogDiagnosis.LOG_DETECTED
        else -> LogDiagnosis.INSUFFICIENT_DATA
    }
}
