package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSettingsTest {

    // ---- presets <-> CUSTOM ----

    @Test
    fun defaults_are_the_compatible_preset_except_audio() {
        // COMPATIBLE carries 192 kbps; the bare default keeps the legacy 128.
        assertEquals(ExportPreset.CUSTOM, ExportPreset.of(ExportSettings()))
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(ExportPreset.COMPATIBLE.apply(ExportSettings())))
    }

    @Test
    fun every_preset_round_trips_through_apply_and_of() {
        for (p in listOf(ExportPreset.COMPATIBLE, ExportPreset.SMALLER, ExportPreset.QUALITY)) {
            assertEquals(p, ExportPreset.of(p.apply(ExportSettings())))
            // apply is independent of the starting settings
            assertEquals(p.apply(ExportSettings()), p.apply(ExportSettings(bitDepth = 10, customMbps = 5.0, uncapped = true)))
        }
    }

    @Test
    fun the_presets_mean_what_the_plan_says() {
        val c = ExportPreset.COMPATIBLE.apply(ExportSettings())
        assertEquals(ExportCodec.AVC, c.codec); assertEquals(8, c.bitDepth); assertEquals(BitrateLevel.STANDARD, c.bitrateLevel)
        val s = ExportPreset.SMALLER.apply(ExportSettings())
        assertEquals(ExportCodec.HEVC, s.codec); assertEquals(8, s.bitDepth); assertEquals(BitrateLevel.LOW, s.bitrateLevel)
        val q = ExportPreset.QUALITY.apply(ExportSettings())
        assertEquals(ExportCodec.HEVC, q.codec); assertEquals(BitrateLevel.MAX, q.bitrateLevel)
        // E1b/c: QUALITY is 8-bit until the 10-bit path (E1d) exists.
        assertEquals(8, q.bitDepth)
    }

    @Test
    fun changing_any_single_field_turns_a_preset_into_custom() {
        val base = ExportPreset.QUALITY.apply(ExportSettings())
        val changed = listOf(
            base.copy(codec = ExportCodec.AVC),
            base.copy(bitDepth = 10),
            base.copy(dynamicRange = ExportDynamicRange.HLG),
            base.copy(rateMode = ExportRateMode.CBR),
            base.copy(bitrateLevel = BitrateLevel.HIGH),
            base.copy(customMbps = 50.0),
            base.copy(uncapped = true),
            base.copy(keyframe = KeyframeInterval.TWO),
            base.copy(profile = 1),
            base.copy(audioKbps = 128),
        )
        for (s in changed) assertEquals("$s", ExportPreset.CUSTOM, ExportPreset.of(s))
        assertEquals(ExportPreset.QUALITY, ExportPreset.of(base))
    }

    @Test
    fun without_hevc_the_hevc_presets_fall_back_to_avc() {
        val s = ExportPreset.SMALLER.apply(ExportSettings(), hevcAvailable = false)
        assertEquals(ExportCodec.AVC, s.codec)
        assertEquals(ExportPreset.SMALLER, ExportPreset.of(s, hevcAvailable = false))
        // and with HEVC available that same AVC setting is not SMALLER any more
        assertEquals(ExportPreset.CUSTOM, ExportPreset.of(s, hevcAvailable = true))
    }

    @Test
    fun custom_apply_changes_nothing() {
        val s = ExportSettings(bitrateLevel = BitrateLevel.CUSTOM, customMbps = 33.0)
        assertEquals(s, ExportPreset.CUSTOM.apply(s))
    }

    // ---- wire ----

    private fun parseWire(text: String): ExportSettingsWire.Parsed =
        ExportSettingsWire.parse(JsonParser(text).parseValue())

    @Test
    fun wire_round_trips_every_field() {
        val s = ExportSettings(
            codec = ExportCodec.HEVC, bitDepth = 10, dynamicRange = ExportDynamicRange.HLG,
            rateMode = ExportRateMode.CBR, bitrateLevel = BitrateLevel.CUSTOM, customMbps = 62.5,
            uncapped = true, keyframe = KeyframeInterval.ALL_INTRA, profile = 2, audioKbps = 320,
        )
        val text = ExportSettingsWire.render(s)
        assertTrue(text, text.startsWith("{\"v\":1,"))
        assertEquals(ExportSettingsWire.Parsed.Ok(s), parseWire(text))
        assertEquals(ExportSettingsWire.Parsed.Ok(ExportSettings()), parseWire(ExportSettingsWire.render(ExportSettings())))
    }

    @Test
    fun wire_omits_absent_optionals() {
        val text = ExportSettingsWire.render(ExportSettings())
        assertFalse(text, text.contains("customMbps"))
        assertFalse(text, text.contains("profile"))
    }

    @Test
    fun wire_never_guesses_a_newer_or_unknown_value() {
        fun unsupported(text: String) = parseWire(text) is ExportSettingsWire.Parsed.Unsupported
        val ok = ExportSettingsWire.render(ExportSettings())
        assertTrue(unsupported(ok.replace("\"v\":1", "\"v\":2")))
        assertTrue(unsupported(ok.replace("\"AVC\"", "\"AV1\"")))
        assertTrue(unsupported(ok.replace("\"VBR\"", "\"ABR\"")))
        assertTrue(unsupported("{\"codec\":\"AVC\"}")) // no version
        assertTrue(unsupported("\"not an object\""))
    }
}
