package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodecProbe formatting/mapping tests (sprint plan Task 6, device-independent
 * half): fake CodecReport values in, formatting + JSON shape out. No
 * MediaCodecList anywhere in these tests — the probe() walk itself is
 * device-gated.
 */
class CodecProbeTest {

    private val avcHw = CodecReport(
        name = "c2.qti.avc.encoder",
        mime = "video/avc",
        hardware = true,
        profiles = listOf("1", "2"),
        maxSize = "3840x2160",
        main10Ok = null,
    )

    private val hevcSw = CodecReport(
        name = "OMX.google.hevc.decoder",
        mime = "video/hevc",
        hardware = false,
        profiles = listOf("1"),
        maxSize = "",
        main10Ok = false,
        detail = "software fallback",
    )

    // ---- formatReport ----

    @Test
    fun format_report_names_codec_kind_and_hardware() {
        val line = CodecProbe.formatReport(avcHw)
        assertTrue(line.startsWith("c2.qti.avc.encoder — H.264, hardware"))
    }

    @Test
    fun format_report_lists_profiles_and_max_size() {
        val line = CodecProbe.formatReport(avcHw)
        // profiles joined with '/', max size after an em dash
        assertTrue("profiles missing: $line", line.contains("profiles: 1/2"))
        assertTrue("max size missing: $line", line.contains("3840x2160"))
    }

    @Test
    fun format_report_omits_empty_sections() {
        val line = CodecProbe.formatReport(
            CodecReport(
                name = "bare",
                mime = "video/avc",
                hardware = true,
                profiles = emptyList(),
                maxSize = "",
                main10Ok = null,
            )
        )
        assertEquals("bare — H.264, hardware", line)
    }

    @Test
    fun format_report_shows_main10_and_detail_when_present() {
        val line = CodecProbe.formatReport(hevcSw)
        assertTrue("main10 missing: $line", line.contains("10-bit: no"))
        assertTrue("detail missing: $line", line.contains("(software fallback)"))
    }

    @Test
    fun format_report_says_yes_for_main10() {
        val line = CodecProbe.formatReport(avcHw.copy(main10Ok = true))
        assertTrue(line.contains("10-bit: yes"))
    }

    @Test
    fun format_report_maps_unknown_mime_verbatim() {
        val line = CodecProbe.formatReport(
            CodecReport("x", mime = "video/x-vnd.on2.vp9", hardware = false, profiles = emptyList(), maxSize = "", main10Ok = null)
        )
        assertTrue(line.contains("video/x-vnd.on2.vp9"))
    }

    // ---- toDeviceReportJson ----

    @Test
    fun json_has_codecs_array_with_all_fields() {
        val json = CodecProbe.toDeviceReportJson(listOf(avcHw))
        assertTrue(json.startsWith("{\"codecs\":["))
        assertTrue(json.endsWith("]}"))
        assertTrue(json.contains("\"name\":\"c2.qti.avc.encoder\""))
        assertTrue(json.contains("\"mime\":\"video/avc\""))
        assertTrue(json.contains("\"hardware\":true"))
        assertTrue(json.contains("\"profiles\":[\"1\",\"2\"]"))
        assertTrue(json.contains("\"maxSize\":\"3840x2160\""))
        assertTrue(json.contains("\"main10Ok\":null"))
        assertTrue(json.contains("\"detail\":null"))
    }

    @Test
    fun json_separates_multiple_reports_with_commas() {
        val json = CodecProbe.toDeviceReportJson(listOf(avcHw, hevcSw))
        assertTrue(json.contains("},{"))
        // both names present, in order
        val avcIdx = json.indexOf("c2.qti.avc.encoder")
        val hevcIdx = json.indexOf("OMX.google.hevc.decoder")
        assertTrue(avcIdx in 0 until hevcIdx)
    }

    @Test
    fun json_renders_empty_list_and_booleans() {
        assertEquals("{\"codecs\":[]}", CodecProbe.toDeviceReportJson(emptyList()))
        val json = CodecProbe.toDeviceReportJson(listOf(hevcSw))
        assertTrue(json.contains("\"hardware\":false"))
        assertTrue(json.contains("\"main10Ok\":false"))
        assertTrue(json.contains("\"detail\":\"software fallback\""))
    }

    @Test
    fun json_escapes_quotes_and_backslashes() {
        val tricky = CodecReport(
            name = "we\"ird\\codec",
            mime = "video/avc",
            hardware = true,
            profiles = listOf("p\"1"),
            maxSize = "",
            main10Ok = null,
        )
        val json = CodecProbe.toDeviceReportJson(listOf(tricky))
        assertTrue(json.contains("\"name\":\"we\\\"ird\\\\codec\""))
        assertTrue(json.contains("\"profiles\":[\"p\\\"1\"]"))
    }

    // ---- fromCodecInfo (pure mapping over a stub) ----

    @Test
    fun from_stub_maps_max_size_and_passes_fields_through() {
        val stub = mapOf(
            "name" to "stub.encoder",
            "mime" to "video/hevc",
            "hardware" to true,
            "profiles" to listOf("1", "2"),
            "maxWidth" to 4096,
            "maxHeight" to 2160,
            "main10Ok" to true,
            "detail" to null,
        )
        val report = CodecProbe.fromCodecInfo(stub)
        assertEquals("stub.encoder", report.name)
        assertEquals("video/hevc", report.mime)
        assertTrue(report.hardware)
        assertEquals(listOf("1", "2"), report.profiles)
        assertEquals("4096x2160", report.maxSize)
        assertEquals(true, report.main10Ok)
    }

    @Test
    fun from_stub_empty_size_maps_to_empty_string() {
        val stub = mapOf(
            "name" to "s",
            "mime" to "video/avc",
            "hardware" to false,
            "maxWidth" to 0,
            "maxHeight" to 0,
        )
        val report = CodecProbe.fromCodecInfo(stub)
        assertEquals("", report.maxSize)
        assertFalse(report.hardware)
    }
}
