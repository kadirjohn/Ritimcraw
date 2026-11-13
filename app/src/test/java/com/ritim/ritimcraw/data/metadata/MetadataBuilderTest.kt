package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN-13 Stage A tests: the pure builder — observed facts -> the honest
 * record. Every KNOWN/ESTIMATED/UNKNOWN verdict lives in MetadataBuilder,
 * so these tests ARE the spec for it: an estimate always carries its
 * basis, an unreadable fact stays UNKNOWN, and the diagnosis/capability
 * wiring follows DiagnosisRules.
 *
 * The DV mime constant is a Java compile-time constant inlined into the
 * extractor's companion initializer, so reading it here loads no
 * framework class (the equality assert would catch any regression).
 */
class MetadataBuilderTest {

    // ---- measured fps / duration ----

    @Test
    fun measured_fps_is_estimated_with_basis() {
        // 30 frames spanning ~1 s: 29 intervals / 966657 us = 30.0 fps.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(frameCount = 30, firstPtsUs = 0, lastPtsUs = 966_657),
        )
        assertEquals(FieldStatus.KNOWN, r.durationUs.status)
        assertEquals(966_657L, r.durationUs.value)
        assertEquals(FieldStatus.ESTIMATED, r.fpsMeasured.status)
        assertEquals(30.0, r.fpsMeasured.value!!, 0.01)
        assertTrue(r.fpsMeasured.basis!!.contains("(frames - 1)"))
    }

    @Test
    fun fps_needs_the_interval_count_a_single_frame_has_no_rate() {
        // One frame: the span is a real, measurable 0 — but a rate needs
        // intervals, and one frame has none.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(frameCount = 1, firstPtsUs = 0, lastPtsUs = 0),
        )
        assertEquals(FieldStatus.KNOWN, r.durationUs.status)
        assertEquals(0L, r.durationUs.value)
        assertEquals(FieldStatus.UNKNOWN, r.fpsMeasured.status)
    }

    @Test
    fun unknown_frame_count_never_fabricates_a_rate() {
        // Both timestamps read, count missing: any interval count would
        // be a guess — the rate stays UNKNOWN, the duration stays KNOWN.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(firstPtsUs = 0, lastPtsUs = 1_000_000),
        )
        assertEquals(FieldStatus.KNOWN, r.durationUs.status)
        assertEquals(FieldStatus.UNKNOWN, r.fpsMeasured.status)
    }

    @Test
    fun unreadable_timestamps_leave_duration_and_fps_unknown() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(frameCount = 30))
        assertEquals(FieldStatus.UNKNOWN, r.durationUs.status)
        assertEquals(FieldStatus.UNKNOWN, r.fpsMeasured.status)
    }

    // ---- the MCRAW record's honest gaps ----

    @Test
    fun mcraw_holds_no_invented_fields() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts())
        // No facts at all: every field UNKNOWN, nothing faked.
        assertTrue(r.isEmpty)
        assertEquals(FieldStatus.UNKNOWN, r.fpsNominal.status)
        assertEquals(FieldStatus.UNKNOWN, r.primaries.status)
        assertEquals(FieldStatus.UNKNOWN, r.transfer.status)
        assertEquals(FieldStatus.UNKNOWN, r.matrix.status)
        assertEquals(FieldStatus.UNKNOWN, r.range.status)
        assertEquals(FieldStatus.UNKNOWN, r.camera.status)
        assertEquals(FieldStatus.UNKNOWN, r.lens.status)
        assertEquals(FieldStatus.UNKNOWN, r.audio.status)
        assertEquals(FieldStatus.UNKNOWN, r.fileSize.status)
        assertEquals(FieldStatus.UNKNOWN, r.fileIdentity.status)
        // RAW is not DV and not LOG — facts about the format.
        assertEquals(Diagnosis.NOT_DV, r.dolbyVision)
        assertEquals(LogDiagnosis.NOT_LOG, r.logProfile)
        // A capability, never inferred from a diagnosis.
        assertEquals(Capability.UNKNOWN, r.hdrOutputSupport)
        assertFalse(r.frameCountEstimated)
    }

    @Test
    fun fps_nominal_is_unknown_even_for_a_read_mcraw() {
        // The container carries NO nominal-fps key (verified against real
        // recordings) — a fully-read clip still keeps fpsNominal UNKNOWN.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(frameCount = 30, firstPtsUs = 0, lastPtsUs = 966_657),
        )
        assertEquals(FieldStatus.UNKNOWN, r.fpsNominal.status)
        assertTrue(r.hasKnownFields)
    }

    @Test
    fun mcraw_geometry_and_depth_are_known_from_the_container() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(frameCount = 3, width = 3840, height = 2160, bitDepth = 16))
        assertEquals(FieldStatus.KNOWN, r.resolution.status)
        assertEquals("3840x2160", r.resolution.value)
        assertEquals(FieldStatus.KNOWN, r.bitDepth.status)
        assertEquals(16, r.bitDepth.value)
        assertEquals(FieldStatus.KNOWN, r.frameCount.status)
        assertEquals(3, r.frameCount.value)
    }

    // ---- the camera fallback chain ----

    @Test
    fun camera_prefers_unique_camera_model() {
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(
                identity = ContainerIdentityFacts(
                    uniqueCameraModel = "Pixel 9 Pro",
                    deviceModel = "25010pn30g",
                ),
            ),
        )
        assertEquals(FieldStatus.KNOWN, r.camera.status)
        assertEquals("Pixel 9 Pro", r.camera.value)
    }

    @Test
    fun camera_falls_back_to_device_model() {
        // uniqueCameraModel is empty on the owner's Xiaomi — the device
        // model is the honest next link, never a guess.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(
                identity = ContainerIdentityFacts(
                    uniqueCameraModel = "",
                    deviceModel = "25010pn30g",
                ),
            ),
        )
        assertEquals(FieldStatus.KNOWN, r.camera.status)
        assertEquals("25010pn30g", r.camera.value)
    }

    @Test
    fun camera_falls_back_to_build_identity() {
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(
                identity = ContainerIdentityFacts(
                    manufacturer = "Xiaomi",
                    buildModel = "25010PN30G",
                ),
            ),
        )
        assertEquals(FieldStatus.KNOWN, r.camera.status)
        assertEquals("Xiaomi 25010PN30G", r.camera.value)
    }

    @Test
    fun camera_stays_unknown_when_identity_keys_are_all_absent() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(identity = ContainerIdentityFacts()))
        assertEquals(FieldStatus.UNKNOWN, r.camera.status)
    }

    @Test
    fun camera_is_unknown_when_the_identity_probe_failed() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(identity = null))
        assertEquals(FieldStatus.UNKNOWN, r.camera.status)
        assertEquals(FieldStatus.UNKNOWN, r.lens.status)
    }

    // ---- the lens string ----

    @Test
    fun lens_joins_focal_and_aperture() {
        // The real fixture's keys: focal 23.95 mm, aperture 1.63.
        val r = MetadataBuilder.mcraw(
            "src-1",
            McrawFacts(
                identity = ContainerIdentityFacts(
                    apertureMm = 1.63,
                    focalLengthMm = 23.95,
                ),
            ),
        )
        assertEquals(FieldStatus.KNOWN, r.lens.status)
        assertEquals("24 mm f/1.6", r.lens.value)
    }

    @Test
    fun lens_reports_what_exists() {
        val focalOnly = MetadataBuilder.mcraw(
            "src-1", McrawFacts(identity = ContainerIdentityFacts(focalLengthMm = 23.95)),
        )
        assertEquals("24 mm", focalOnly.lens.value)
        val apertureOnly = MetadataBuilder.mcraw(
            "src-1", McrawFacts(identity = ContainerIdentityFacts(apertureMm = 1.63)),
        )
        assertEquals("f/1.6", apertureOnly.lens.value)
    }

    // ---- MCRAW audio ----

    @Test
    fun mcraw_audio_absent_is_a_known_fact() {
        // 0 = the container says the clip HAS no audio.
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(audioSampleRateHz = 0, audioChannels = 0))
        assertEquals(FieldStatus.KNOWN, r.audio.status)
        assertFalse(r.audio.value!!.present)
    }

    @Test
    fun mcraw_audio_present_is_known_pcm() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts(audioSampleRateHz = 48_000, audioChannels = 2))
        assertEquals(FieldStatus.KNOWN, r.audio.status)
        val a = r.audio.value!!
        assertTrue(a.present)
        assertEquals("pcm", a.codec)
        assertEquals(48_000, a.sampleRateHz)
        assertEquals(2, a.channels)
    }

    @Test
    fun mcraw_audio_unread_stays_unknown() {
        val r = MetadataBuilder.mcraw("src-1", McrawFacts())
        assertEquals(FieldStatus.UNKNOWN, r.audio.status)
    }

    // ---- the encoded record ----

    @Test
    fun encoded_dv_mime_is_detected() {
        // The platform constant is the inlined literal — the assert pins
        // the comparison target so a rename cannot slip past.
        assertEquals("video/dolby-vision", MetadataExtractor.DOLBY_VISION_MIME)
        val r = MetadataBuilder.encoded(
            "src-2",
            EncodedFacts(mime = MetadataExtractor.DOLBY_VISION_MIME),
        )
        assertEquals(Diagnosis.DV_DETECTED, r.dolbyVision)
        // The DV diagnosis says nothing about output support.
        assertEquals(Capability.UNKNOWN, r.hdrOutputSupport)
    }

    @Test
    fun encoded_read_track_is_not_dv_and_is_classified_log() {
        // The real MP4 fixture: HEVC, no DV boxes. A read track IS the
        // app's classified-LOG signal (the develop stage applies the
        // Mi-Log decode to every encoded clip).
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc"))
        assertEquals(Diagnosis.NOT_DV, r.dolbyVision)
        assertEquals(LogDiagnosis.LOG_DETECTED, r.logProfile)
    }

    @Test
    fun encoded_unreadable_track_is_insufficient_data() {
        val r = MetadataBuilder.encoded("src-2", EncodedFacts())
        assertEquals(Diagnosis.INSUFFICIENT_DATA, r.dolbyVision)
        assertEquals(LogDiagnosis.INSUFFICIENT_DATA, r.logProfile)
        assertTrue(r.isEmpty)
    }

    @Test
    fun encoded_color_tags_map_to_honest_names() {
        val r = MetadataBuilder.encoded(
            "src-2",
            EncodedFacts(
                mime = "video/hevc",
                colorStandard = 6,
                colorTransfer = 6,
                colorRange = 2,
            ),
        )
        assertEquals("bt2020", r.primaries.value)
        assertEquals("bt2020", r.matrix.value)
        assertEquals("st2084", r.transfer.value)
        assertEquals("limited", r.range.value)
    }

    @Test
    fun encoded_untagged_color_stays_unknown_never_defaulted() {
        // The decode path's isBt709 default is a DECODE decision; this
        // record REPORTS the container, and the real fixture's transfer
        // is untagged.
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc"))
        assertEquals(FieldStatus.UNKNOWN, r.primaries.status)
        assertEquals(FieldStatus.UNKNOWN, r.transfer.status)
        assertEquals(FieldStatus.UNKNOWN, r.matrix.status)
        assertEquals(FieldStatus.UNKNOWN, r.range.status)
    }

    @Test
    fun encoded_unrecognized_color_int_stays_known_with_raw_value() {
        // The container DID tag it; the name is this app's vocabulary
        // limit, not the file's.
        val r = MetadataBuilder.encoded(
            "src-2",
            EncodedFacts(mime = "video/hevc", colorStandard = 99, colorTransfer = 42),
        )
        assertEquals(FieldStatus.KNOWN, r.primaries.status)
        assertEquals("color-standard-99", r.primaries.value)
        assertEquals("color-transfer-42", r.transfer.value)
    }

    @Test
    fun encoded_audio_absent_is_known_not_unknown() {
        // A scanned container with NO audio track is a KNOWN present=false
        // — an MP4 without audio must not read as "unknown".
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc", audioTrackPresent = false))
        assertEquals(FieldStatus.KNOWN, r.audio.status)
        assertFalse(r.audio.value!!.present)
    }

    @Test
    fun encoded_failed_audio_scan_stays_unknown() {
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc", audioTrackPresent = null))
        assertEquals(FieldStatus.UNKNOWN, r.audio.status)
    }

    @Test
    fun encoded_audio_present_is_known() {
        val r = MetadataBuilder.encoded(
            "src-2",
            EncodedFacts(
                mime = "video/hevc",
                audioTrackPresent = true,
                audioMime = "audio/mp4a-latm",
                audioSampleRateHz = 48_000,
                audioChannels = 2,
            ),
        )
        assertEquals(FieldStatus.KNOWN, r.audio.status)
        val a = r.audio.value!!
        assertTrue(a.present)
        assertEquals("audio/mp4a-latm", a.codec)
        assertEquals(48_000, a.sampleRateHz)
        assertEquals(2, a.channels)
    }

    @Test
    fun encoded_audio_track_without_keys_stays_unknown() {
        // The scan saw an audio track but its format carried no mime —
        // the codec is not guessed.
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc", audioTrackPresent = true))
        assertEquals(FieldStatus.UNKNOWN, r.audio.status)
    }

    @Test
    fun encoded_nominal_fps_passes_through() {
        val tagged = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc", fpsNominal = 30.0))
        assertEquals(FieldStatus.KNOWN, tagged.fpsNominal.status)
        assertEquals(30.0, tagged.fpsNominal.value!!, 0.0)
        val untagged = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc"))
        assertEquals(FieldStatus.UNKNOWN, untagged.fpsNominal.status)
    }

    @Test
    fun encoded_holds_no_camera_or_depth_claims() {
        // Encoded containers carry no camera identity and declare no
        // sample depth — both stay UNKNOWN, never "8-bit" by assumption.
        val r = MetadataBuilder.encoded("src-2", EncodedFacts(mime = "video/hevc", width = 3840, height = 2160))
        assertEquals(FieldStatus.UNKNOWN, r.camera.status)
        assertEquals(FieldStatus.UNKNOWN, r.lens.status)
        assertEquals(FieldStatus.UNKNOWN, r.bitDepth.status)
        assertEquals("3840x2160", r.resolution.value)
        assertEquals(SourceKind.ENCODED, r.kind)
    }

    @Test
    fun encoded_measured_fps_uses_the_sample_count() {
        val r = MetadataBuilder.encoded(
            "src-2",
            EncodedFacts(
                mime = "video/hevc",
                frameCount = 60,
                firstPtsUs = 0,
                lastPtsUs = 2_000_000,
            ),
        )
        assertEquals(FieldStatus.ESTIMATED, r.fpsMeasured.status)
        assertEquals(29.5, r.fpsMeasured.value!!, 0.01)
        assertEquals(2_000_000L, r.durationUs.value)
    }
}
