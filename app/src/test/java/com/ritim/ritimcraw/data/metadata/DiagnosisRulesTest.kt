package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IN-13 Stage A tests: the pure DV/LOG diagnosis rules, fed fake
 * observed facts (the device-gated extractor derives the booleans from
 * the real track format). Every branch is honest by construction —
 * unreadable tracks are INSUFFICIENT_DATA, never a silent NOT_*.
 */
class DiagnosisRulesTest {

    // ---- Dolby Vision ----

    @Test
    fun mcraw_is_never_dv() {
        // RAW containers are MotionCam's own format — a fact, not a guess.
        assertEquals(
            Diagnosis.NOT_DV,
            DiagnosisRules.dolbyVision(SourceKind.MCRAW, trackRead = false, trackIsDolbyVision = false),
        )
        assertEquals(
            Diagnosis.NOT_DV,
            DiagnosisRules.dolbyVision(SourceKind.MCRAW, trackRead = true, trackIsDolbyVision = true),
        )
    }

    @Test
    fun dv_mime_is_detected() {
        assertEquals(
            Diagnosis.DV_DETECTED,
            DiagnosisRules.dolbyVision(SourceKind.ENCODED, trackRead = true, trackIsDolbyVision = true),
        )
    }

    @Test
    fun read_non_dv_track_is_not_dv() {
        // A track whose mime is not a DV mime (the real MP4 fixture: HEVC,
        // no Dolby Vision boxes) is honestly NOT_DV.
        assertEquals(
            Diagnosis.NOT_DV,
            DiagnosisRules.dolbyVision(SourceKind.ENCODED, trackRead = true, trackIsDolbyVision = false),
        )
    }

    @Test
    fun unreadable_track_is_insufficient() {
        assertEquals(
            Diagnosis.INSUFFICIENT_DATA,
            DiagnosisRules.dolbyVision(SourceKind.ENCODED, trackRead = false, trackIsDolbyVision = false),
        )
        // Even a stale true cannot claim detection without a read track.
        assertEquals(
            Diagnosis.INSUFFICIENT_DATA,
            DiagnosisRules.dolbyVision(SourceKind.ENCODED, trackRead = false, trackIsDolbyVision = true),
        )
    }

    // ---- LOG profile ----

    @Test
    fun raw_is_not_log() {
        // RAW is linear sensor data; LOG is a transfer applied when
        // ENCODING video. NOT_LOG is a fact about the format.
        assertEquals(
            LogDiagnosis.NOT_LOG,
            DiagnosisRules.logProfile(SourceKind.MCRAW, trackRead = false, encodedClassifiedLog = false),
        )
        assertEquals(
            LogDiagnosis.NOT_LOG,
            DiagnosisRules.logProfile(SourceKind.MCRAW, trackRead = true, encodedClassifiedLog = true),
        )
    }

    @Test
    fun classified_log_is_detected() {
        // The engine's existing Mi-Log classification is the LOG signal.
        assertEquals(
            LogDiagnosis.LOG_DETECTED,
            DiagnosisRules.logProfile(SourceKind.ENCODED, trackRead = true, encodedClassifiedLog = true),
        )
    }

    @Test
    fun unclassified_track_is_insufficient_not_not_log() {
        // A read track that was not classified LOG is INSUFFICIENT_DATA:
        // claiming NOT_LOG would mean we KNOW it is not log-encoded when
        // we merely could not tell (the real MP4 fixture's transfer is
        // untagged — the Mi-Log transfer is applied at develop time).
        assertEquals(
            LogDiagnosis.INSUFFICIENT_DATA,
            DiagnosisRules.logProfile(SourceKind.ENCODED, trackRead = true, encodedClassifiedLog = false),
        )
    }

    @Test
    fun unreadable_track_is_insufficient_for_log() {
        assertEquals(
            LogDiagnosis.INSUFFICIENT_DATA,
            DiagnosisRules.logProfile(SourceKind.ENCODED, trackRead = false, encodedClassifiedLog = false),
        )
        assertEquals(
            LogDiagnosis.INSUFFICIENT_DATA,
            DiagnosisRules.logProfile(SourceKind.ENCODED, trackRead = false, encodedClassifiedLog = true),
        )
    }

    // ---- the diagnosis-vs-capability distinction ----

    @Test
    fun a_dv_diagnosis_is_never_a_capability() {
        // IN-13's explicit demand: the state and the ability are different
        // kinds. A DV file on a device without DV output is DV_DETECTED
        // with hdrOutputSupport NOT_SUPPORTED — the record models both
        // independently, and no rule derives one from the other.
        val dvOnSdrDevice = MetadataRecord(
            sourceId = "src-dv",
            kind = SourceKind.ENCODED,
            durationUs = Field.unknown(),
            frameCount = Field.unknown(),
            fpsNominal = Field.unknown(),
            fpsMeasured = Field.unknown(),
            camera = Field.unknown(),
            lens = Field.unknown(),
            resolution = Field.unknown(),
            bitDepth = Field.unknown(),
            primaries = Field.unknown(),
            transfer = Field.unknown(),
            matrix = Field.unknown(),
            range = Field.unknown(),
            audio = Field.unknown(),
            fileSize = Field.unknown(),
            fileIdentity = Field.unknown(),
            dolbyVision = Diagnosis.DV_DETECTED,
            hdrOutputSupport = Capability.NOT_SUPPORTED,
        )
        assertEquals(Diagnosis.DV_DETECTED, dvOnSdrDevice.dolbyVision)
        assertEquals(Capability.NOT_SUPPORTED, dvOnSdrDevice.hdrOutputSupport)
    }

    @Test
    fun sdr_file_gains_no_capability_from_its_diagnosis() {
        // The reverse pairing: NOT_DV with an HDR-capable device. Neither
        // enum is derived from the other.
        val sdrOnHdrDevice = MetadataRecord(
            sourceId = "src-sdr",
            kind = SourceKind.MCRAW,
            durationUs = Field.unknown(),
            frameCount = Field.unknown(),
            fpsNominal = Field.unknown(),
            fpsMeasured = Field.unknown(),
            camera = Field.unknown(),
            lens = Field.unknown(),
            resolution = Field.unknown(),
            bitDepth = Field.unknown(),
            primaries = Field.unknown(),
            transfer = Field.unknown(),
            matrix = Field.unknown(),
            range = Field.unknown(),
            audio = Field.unknown(),
            fileSize = Field.unknown(),
            fileIdentity = Field.unknown(),
            dolbyVision = Diagnosis.NOT_DV,
            logProfile = LogDiagnosis.NOT_LOG,
            hdrOutputSupport = Capability.SUPPORTED,
        )
        assertEquals(Diagnosis.NOT_DV, sdrOnHdrDevice.dolbyVision)
        assertEquals(Capability.SUPPORTED, sdrOnHdrDevice.hdrOutputSupport)
    }
}
