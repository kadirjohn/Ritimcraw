package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncoderDiagnostics tests (owner request 2026-11-12 §4): the log-line
 * format the export emits once the real encoder is created — the §4
 * snippet's fields (name, canonical, declared hardware/software) plus the
 * requested MIME, never conflating requested with actually-created.
 * Pure JVM; no MediaCodec anywhere (the writer's read is device-gated).
 */
class EncoderDiagnosticsTest {

    @Test
    fun log_line_carries_every_real_identity_field() {
        val record = EncoderDiagnostics(
            name = "OMX.qcom.video.encoder.avc",
            canonicalName = "OMX.qcom.video.encoder.avc",
            hardwareAccelerated = true,
            softwareOnly = false,
        )
        val line = EncoderDiagnostics.logLine(record, "video/avc")
        assertTrue("name missing: $line", line.contains("encoder=OMX.qcom.video.encoder.avc"))
        assertTrue("canonical missing: $line", line.contains("canonical=OMX.qcom.video.encoder.avc"))
        assertTrue("hardware missing: $line", line.contains("hardware=true"))
        assertTrue("softwareOnly missing: $line", line.contains("softwareOnly=false"))
        assertTrue("requested missing: $line", line.contains("requested=video/avc"))
    }

    @Test
    fun log_line_separates_requested_from_real() {
        // The requested MIME travels as its own field: a c2.android
        // software encoder created for video/avc reads as the real
        // encoder, with the request still visible.
        val record = EncoderDiagnostics(
            name = "c2.android.avc.encoder",
            canonicalName = "OMX.google.h264.encoder",
            hardwareAccelerated = false,
            softwareOnly = true,
        )
        val line = EncoderDiagnostics.logLine(record, "video/avc")
        assertTrue(line.contains("encoder=c2.android.avc.encoder"))
        assertTrue(line.contains("canonical=OMX.google.h264.encoder"))
        assertTrue(line.contains("hardware=false"))
        assertTrue(line.contains("softwareOnly=true"))
        assertTrue(line.contains("requested=video/avc"))
    }

    @Test
    fun log_line_is_deterministic() {
        val record = EncoderDiagnostics(
            name = "c2.qti.avc.encoder",
            canonicalName = "c2.qti.avc.encoder",
            hardwareAccelerated = true,
            softwareOnly = false,
        )
        assertEquals(
            EncoderDiagnostics.logLine(record, "video/avc"),
            EncoderDiagnostics.logLine(record, "video/avc"),
        )
    }

    @Test
    fun log_line_exact_shape() {
        val record = EncoderDiagnostics(
            name = "c2.qti.avc.encoder",
            canonicalName = "c2.qti.avc.encoder",
            hardwareAccelerated = true,
            softwareOnly = false,
        )
        assertEquals(
            "encoder=c2.qti.avc.encoder, canonical=c2.qti.avc.encoder, " +
                "hardware=true, softwareOnly=false, requested=video/avc",
            EncoderDiagnostics.logLine(record, "video/avc"),
        )
    }
}
