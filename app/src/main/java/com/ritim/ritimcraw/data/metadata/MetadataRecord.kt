package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind

/**
 * IN-13 Stage A — the honest per-source metadata record.
 *
 * Every measured fact about a clip is carried as a value PLUS a status
 * (KNOWN / ESTIMATED / UNKNOWN). The status is the spec's core demand:
 * the editor never fakes a value and never silently estimates — an
 * ESTIMATED value always carries the basis it was estimated from
 * ([Field.basis]), and a value that could not be read from the real
 * container/track is UNKNOWN, not defaulted.
 *
 * The record is PURE DATA plus the status model's own rules. Reading
 * the values (the extraction pass) lives in the extractor; this file is
 * what gets persisted and displayed.
 */

/**
 * The status of one metadata field. [ESTIMATED] requires a stated basis
 * (enforced by [Field]); [UNKNOWN] means the value could not be
 * determined from this source — it is never rendered as a fake default.
 */
enum class FieldStatus { KNOWN, ESTIMATED, UNKNOWN }

/**
 * One metadata field: a nullable value plus its status. A field is
 * "present" when its value is non-null; [FieldStatus.UNKNOWN] fields
 * carry null.
 */
data class Field<T : Any>(
    val value: T?,
    val status: FieldStatus,
    /** How the value was obtained; required when [status] is ESTIMATED. */
    val basis: String? = null,
) {
    init {
        // The status model's two hard rules, enforced at construction:
        // an estimate without a basis is a guess, and a KNOWN/ESTIMATED
        // field never carries a null value.
        require(status != FieldStatus.ESTIMATED || !basis.isNullOrBlank()) {
            "an ESTIMATED field requires a stated basis"
        }
        require(value != null || status == FieldStatus.UNKNOWN) {
            "a null value can only be UNKNOWN"
        }
    }

    companion object {
        fun <T : Any> known(value: T): Field<T> = Field(value, FieldStatus.KNOWN)
        fun <T : Any> unknown(): Field<T> = Field(null, FieldStatus.UNKNOWN)
        fun <T : Any> estimated(value: T, basis: String): Field<T> =
            Field(value, FieldStatus.ESTIMATED, basis)
    }
}

/**
 * The Dolby Vision DIAGNOSIS — a state of the source (what the file IS),
 * never a capability of the device or app:
 *
 *  - [NOT_DV] — the source is known not to be Dolby Vision (a RAW
 *    container, or a read track whose mime is not a DV mime).
 *  - [DV_DETECTED] — the encoded track's mime is a Dolby Vision mime.
 *  - [INSUFFICIENT_DATA] — the track could not be read; no honest claim
 *    either way.
 */
enum class Diagnosis { NOT_DV, DV_DETECTED, INSUFFICIENT_DATA }

/**
 * The LOG profile DIAGNOSIS — the same three-state shape as [Diagnosis]
 * with honest LOG names (a DV-flavored value would read as nonsense
 * here). RAW is not LOG: a RAW container holds linear sensor data, and
 * LOG is a transfer applied when ENCODING video — so a RAW source is
 * [NOT_LOG] as a fact about the format, not a guess.
 */
enum class LogDiagnosis { NOT_LOG, LOG_DETECTED, INSUFFICIENT_DATA }

/**
 * HDR output support — a CAPABILITY (what the device/app CAN do),
 * never inferred from a diagnosis. This is IN-13's explicit distinction:
 * a DV file on a device without DV output is DV_DETECTED with
 * hdrOutputSupport NOT_SUPPORTED, and the reverse pairing exists too;
 * neither enum is ever derived from the other. [UNKNOWN] means the
 * capability has not been probed (or cannot be on this device) — it is
 * not a soft NOT_SUPPORTED.
 */
enum class Capability { SUPPORTED, NOT_SUPPORTED, UNKNOWN }

/**
 * The audio facts of a source, held inside a [Field] so the presence of
 * audio itself is a KNOWN/UNKNOWN fact.
 */
data class AudioSummary(
    val present: Boolean,
    val codec: String,
    val channels: Int,
    val sampleRateHz: Int,
)

/**
 * The rich per-source metadata record (IN-13). Joined to the
 * MediaSource by [sourceId]; persisted in project.json's "metadata"
 * section (MetadataStore).
 *
 * FIELD SOURCES (what extraction fills each from — see the extractor
 * for the device-gated reads):
 *  - durationUs: last pts - first pts, from real frame timestamps
 *    (MCRAW: nativeFramePtsUs; encoded: the sample index walk).
 *  - frameCount: MCRAW container frame count / encoded sample count;
 *    [frameCountEstimated] marks a derived count (e.g. duration x fps).
 *  - fpsNominal: a header-declared capture rate. MCRAW containers
 *    carry NO nominal-fps key (verified against real recordings), so
 *    this stays UNKNOWN for MCRAW; encoded tracks may carry
 *    KEY_CAPTURE_RATE / KEY_FRAME_RATE.
 *  - fpsMeasured: (frameCount - 1) / timestamp span, from the real
 *    frame timestamps.
 *  - camera: MCRAW container identity keys (uniqueCameraModel with an
 *    honest fallback chain); encoded files carry none.
 *  - lens: MCRAW focalLengths[0] + apertures[0]; the container has no
 *    lens-name key.
 *  - resolution / bitDepth: frame geometry (MCRAW stores 16-bit
 *    samples; encoded is typically 8-bit).
 *  - primaries / transfer / matrix / range: encoded tracks tag these
 *    (MediaFormat COLOR_* keys); MCRAW is RAW — no transfer semantics.
 *  - audio: MCRAW extraData.audioSampleRate / audioChannels; encoded
 *    audio track MediaFormat.
 *  - fileSize: the SAF document size.
 *  - fileIdentity: SourceRepository's fingerprint (SHA-256 of the first
 *    1 MiB plus size) — reused, never re-derived.
 *  - dolbyVision / logProfile: the pure [DiagnosisRules].
 *  - hdrOutputSupport: a capability, probed separately from any
 *    diagnosis.
 */
data class MetadataRecord(
    val sourceId: String,
    val kind: SourceKind,
    val durationUs: Field<Long>,
    val frameCount: Field<Int>,
    /** Marks a frame count that was derived, not read. */
    val frameCountEstimated: Boolean = false,
    val fpsNominal: Field<Double>,
    val fpsMeasured: Field<Double>,
    val camera: Field<String>,
    val lens: Field<String>,
    val resolution: Field<String>,
    val bitDepth: Field<Int>,
    val primaries: Field<String>,
    val transfer: Field<String>,
    val matrix: Field<String>,
    val range: Field<String>,
    val audio: Field<AudioSummary>,
    val fileSize: Field<Long>,
    val fileIdentity: Field<String>,
    val dolbyVision: Diagnosis = Diagnosis.INSUFFICIENT_DATA,
    val logProfile: LogDiagnosis = LogDiagnosis.INSUFFICIENT_DATA,
    val hdrOutputSupport: Capability = Capability.UNKNOWN,
) {
    init {
        // An estimated flag on a non-estimated count would be a silent
        // guess — the flag may only mark an ESTIMATED field.
        require(!frameCountEstimated || frameCount.status == FieldStatus.ESTIMATED) {
            "frameCountEstimated requires the frame count to be ESTIMATED"
        }
    }

    private val allFields: List<Field<*>> get() = listOf(
        durationUs, frameCount, fpsNominal, fpsMeasured, camera, lens,
        resolution, bitDepth, primaries, transfer, matrix, range, audio,
        fileSize, fileIdentity,
    )

    /** True when the record holds no KNOWN or ESTIMATED field at all. */
    val isEmpty: Boolean
        get() = allFields.all { it.status == FieldStatus.UNKNOWN }

    /** True when the record holds at least one known or estimated field. */
    val hasKnownFields: Boolean get() = !isEmpty
}
