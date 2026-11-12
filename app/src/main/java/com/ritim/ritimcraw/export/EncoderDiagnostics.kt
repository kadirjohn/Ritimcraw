package com.ritim.ritimcraw.export

/**
 * The REAL encoder identity for one export (owner request 2026-11-12
 * §4): what MediaCodec actually created for the job, recorded at
 * configure time and logged under the export tag. Deliberately a pure
 * data class — MediaCodec cannot run in JVM unit tests, so the mapping
 * from the codec instance lives behind a thin device-gated adapter and
 * everything else (the record shape, the log line, the requested-vs-real
 * distinction) is JVM-tested.
 *
 * Requested vs real (owner prompt §4): the requested MIME/profile is what
 * the writer ASKS for (video/avc, High); the recorded name/canonical/
 * hardware/softwareOnly is what ACTUALLY ran. The two are never conflated
 * in the log line — the requested axis is in the writer's format, the
 * real axis is here.
 *
 * The hardware flag is the vendor's DECLARATION (MediaCodecInfo
 * isHardwareAccelerated, API 29+; minSdk 34 so freely readable), not
 * independent proof of dedicated silicon — the log line records it as
 * declared-hardware. isSoftwareOnly is the complementary declaration.
 */
data class EncoderDiagnostics(
    /** The created codec's name (MediaCodec.getName). */
    val name: String,
    /** The codec's canonical name (MediaCodec.getCanonicalName). */
    val canonicalName: String,
    /** MediaCodecInfo.isHardwareAccelerated — the vendor's declaration. */
    val hardwareAccelerated: Boolean,
    /** MediaCodecInfo.isSoftwareOnly — the vendor's declaration. */
    val softwareOnly: Boolean,
) {
    companion object {
        /**
         * The single log-line format every export emits once the encoder is
         * created (owner prompt §4's snippet, adapted to the app's
         * existing "ritim-*" tag convention — the prompt's "riti-export"
         * tag becomes "ritim-export" to match the app's tag style).
         * Pure: the same record always renders the same line.
         *
         *   encoder=OMX.qcom.video.encoder.avc,
         *   canonical=OMX.qcom.video.encoder.avc,
         *   hardware=true, softwareOnly=false, requested=video/avc
         */
        fun logLine(record: EncoderDiagnostics, requestedMime: String): String =
            "encoder=" + record.name +
                ", canonical=" + record.canonicalName +
                ", hardware=" + record.hardwareAccelerated +
                ", softwareOnly=" + record.softwareOnly +
                ", requested=" + requestedMime
    }
}
