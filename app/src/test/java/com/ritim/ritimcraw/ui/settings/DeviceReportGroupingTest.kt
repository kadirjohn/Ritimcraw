package com.ritim.ritimcraw.ui.settings

import com.ritim.ritimcraw.engine.CodecReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceReportGrouping pure-logic tests (owner spec 2026-11-14,
 * device_report_screen): the spec demands the report's correctness NOT
 * depend on seeing the UI — the codec label from the MIME, the kind from
 * the platform's component-name grammars (Codec2 suffix, OMX name
 * segment), the (codec, kind) grouping/sort, the header format and the
 * copy text are pure functions
 * ([DeviceReportGrouping]) and are pinned here with fake CodecReports
 * (the same construction CodecProbe's own tests use — no MediaCodecList
 * on the JVM).
 */
class DeviceReportGroupingTest {

    private fun report(
        name: String,
        mime: String,
        hardware: Boolean = true,
    ) = CodecReport(
        name = name,
        mime = mime,
        hardware = hardware,
        profiles = emptyList(),
        maxSize = "1920x1080",
        main10Ok = null,
    )

    // ---- codecLabel: the label comes from the MIME, never the name ----

    @Test
    fun codecLabel_mapsAvcAndHevcMimesToTheirShortLabels() {
        assertEquals("AVC", DeviceReportGrouping.codecLabel("video/avc"))
        assertEquals("HEVC", DeviceReportGrouping.codecLabel("video/hevc"))
    }

    @Test
    fun codecLabel_ignoresTheCodecNameEntirely() {
        // The label is a function of the MIME ONLY: the signature takes
        // no name, so no vendor name can move the label (spec: use the
        // MIME, not the names). A c2 HEVC encoder whose name contains
        // "avc" still labels HEVC.
        assertEquals("HEVC", DeviceReportGrouping.codecLabel("video/hevc"))
        assertEquals("AVC", DeviceReportGrouping.codecLabel("video/avc"))
    }

    @Test
    fun codecLabel_uppercasesTheSubtypeOfOtherMimes() {
        assertEquals("AV01", DeviceReportGrouping.codecLabel("video/av01"))
        assertEquals("DOLBY-VISION", DeviceReportGrouping.codecLabel("video/dolby-vision"))
    }

    @Test
    fun codecLabel_handlesAMalformedMimeHonestly() {
        // No slash -> substringAfter returns the whole string; the label
        // is an honest uppercased fallback, never a crash.
        assertEquals("VIDEO", DeviceReportGrouping.codecLabel("video"))
        assertEquals("", DeviceReportGrouping.codecLabel(""))
    }

    // ---- isEncoder: the kind from the canonical name suffix ----

    @Test
    fun isEncoder_derivesKindFromTheCanonicalNameSuffix() {
        // Codec2 grammar: the kind is the final segment.
        assertTrue(DeviceReportGrouping.isEncoder("c2.qti.avc.encoder"))
        assertFalse(DeviceReportGrouping.isEncoder("c2.qti.avc.decoder"))
        // OMX grammar: the kind is a NAME SEGMENT (OMX.vendor.video.
        // encoder.avc) — the suffix-only reading would misclassify every
        // OMX encoder as a decoder; this pins the segment rule.
        assertTrue(DeviceReportGrouping.isEncoder("OMX.qcom.video.encoder.avc"))
        assertFalse(DeviceReportGrouping.isEncoder("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun isEncoder_matchesTheKindMarkersCaseInsensitively() {
        // Some vendors ship mixed-case OMX names; the marker match is
        // case-insensitive and no real codec name contains ".encoder."
        // as anything but the kind.
        assertTrue(DeviceReportGrouping.isEncoder("OMX.MTK.VIDEO.ENCODER.AVC"))
        assertFalse(DeviceReportGrouping.isEncoder("OMX.MTK.VIDEO.DECODER.AVC"))
    }

    @Test
    fun isEncoder_countsANonCanonicalNameAsDecoder() {
        // No MediaCodecList name exists without a .encoder/.decoder
        // suffix; one anyway groups as a decoder (documented fallback).
        assertFalse(DeviceReportGrouping.isEncoder("c2.qti.avc.low_latency"))
        assertFalse(DeviceReportGrouping.isEncoder("some.custom.thing"))
    }

    // ---- group: (codec, kind) grouping, sorted, encounter order kept ----

    @Test
    fun group_sortsByCodecThenKindWithDecodersFirst() {
        val groups = DeviceReportGrouping.group(
            listOf(
                report("OMX.qcom.video.encoder.hevc", "video/hevc"),
                report("c2.qti.avc.decoder", "video/avc"),
                report("OMX.qcom.video.encoder.avc", "video/avc"),
                report("c2.qti.hevc.decoder", "video/hevc"),
            ),
        )
        assertEquals(
            listOf("AVC" to false, "AVC" to true, "HEVC" to false, "HEVC" to true),
            groups.map { it.label to it.encoders },
        )
    }

    @Test
    fun group_mergesCodecsOfTheSameCodecAndKind() {
        val groups = DeviceReportGrouping.group(
            listOf(
                report("c2.qti.avc.decoder", "video/avc"),
                report("OMX.qcom.video.decoder.avc", "video/avc"),
                report("c2.google.avc.decoder", "video/avc"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().codecs.size)
    }

    @Test
    fun group_preservesTheProbeEncounterOrderWithinAGroup() {
        val first = report("c2.qti.avc.decoder", "video/avc")
        val second = report("OMX.qcom.video.decoder.avc", "video/avc")
        val groups = DeviceReportGrouping.group(listOf(first, second))
        assertEquals(listOf(first, second), groups.single().codecs)
    }

    @Test
    fun group_keepsHardwareAndSoftwareCodecsInTheSameGroup() {
        // The HW/SW split is a pill, not a group: both stay under
        // "AVC · Decoders" (spec groups by codec and kind only).
        val hw = report("c2.qti.avc.decoder", "video/avc", hardware = true)
        val sw = report("c2.google.avc.decoder", "video/avc", hardware = false)
        val groups = DeviceReportGrouping.group(listOf(hw, sw))
        assertEquals(1, groups.size)
        assertEquals(listOf(hw, sw), groups.single().codecs)
    }

    @Test
    fun group_returnsAnEmptyListForAnEmptyProbe() {
        assertTrue(DeviceReportGrouping.group(emptyList()).isEmpty())
    }

    // ---- headerText / plainText: the header format and the copy text ----

    @Test
    fun headerText_formatsTheLocalizedGroupFormats() {
        val decoders = "%1\$s · Decoders"
        val encoders = "%1\$s · Encoders"
        val avcDecoders = CodecGroup("AVC", encoders = false, codecs = emptyList())
        val hevcEncoders = CodecGroup("HEVC", encoders = true, codecs = emptyList())
        assertEquals("AVC · Decoders", DeviceReportGrouping.headerText(avcDecoders, decoders, encoders))
        assertEquals(
            "HEVC · Encoders",
            DeviceReportGrouping.headerText(hevcEncoders, decoders, encoders),
        )
    }

    @Test
    fun plainText_listsEveryCodecNameUnderItsHeaderOnePerLine() {
        val decoders = "%1\$s · Decoders"
        val encoders = "%1\$s · Encoders"
        val groups = DeviceReportGrouping.group(
            listOf(
                report("c2.qti.avc.decoder", "video/avc"),
                report("OMX.qcom.video.decoder.avc", "video/avc"),
                report("c2.qti.avc.encoder", "video/avc"),
            ),
        )
        assertEquals(
            "AVC · Decoders\n" +
                "c2.qti.avc.decoder\n" +
                "OMX.qcom.video.decoder.avc\n" +
                "AVC · Encoders\n" +
                "c2.qti.avc.encoder",
            DeviceReportGrouping.plainText(groups, decoders, encoders),
        )
    }

    @Test
    fun plainText_isEmptyForAnEmptyProbe() {
        assertEquals(
            "",
            DeviceReportGrouping.plainText(emptyList(), "%1\$s · Decoders", "%1\$s · Encoders"),
        )
    }
}
