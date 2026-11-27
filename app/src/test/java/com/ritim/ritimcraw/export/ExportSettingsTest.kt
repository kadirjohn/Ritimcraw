package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        for (p in listOf(ExportPreset.COMPATIBLE, ExportPreset.SMALLER, ExportPreset.QUALITY, ExportPreset.SOCIAL)) {
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
        assertEquals(ExportCodec.HEVC, q.codec); assertEquals(BitrateLevel.QUALITY, q.bitrateLevel)
        // Without a HEVC encoder the preset falls back to AVC at the old MAX level (160 Mb/s is a HEVC figure).
        assertEquals(BitrateLevel.MAX, ExportPreset.QUALITY.apply(ExportSettings(), hevcAvailable = false).bitrateLevel)
        // E1b/c: QUALITY is 8-bit until the 10-bit path (E1d) exists.
        assertEquals(8, q.bitDepth)
    }

    @Test
    fun changing_any_single_field_turns_a_preset_into_custom() {
        val base = ExportPreset.QUALITY.apply(ExportSettings())
        val changed = listOf(
            base.copy(codec = ExportCodec.AVC),
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
        // E1d: Quality's 10-bit and 8-bit variants are the same preset (the 10-bit path is per export)
        assertEquals(ExportPreset.QUALITY, ExportPreset.of(base.copy(bitDepth = 10)))
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

    // ---- the SOCIAL preset (E5, night D2) ----

    @Test
    fun social_preset_is_the_plans_social_row() {
        val s = ExportPreset.SOCIAL.apply(ExportSettings())
        // The plan's Social row (docs/E5_AUDIO_PORT_PLAN.md §4.2): −14
        // LUFS-I target, −1 dBTP ceiling, vertical 1080×1920 — on the
        // play-everywhere AVC 8-bit STANDARD shape with 192 kbps AAC.
        assertEquals(-14f, s.loudnessTargetLufs)
        assertEquals(-1f, s.truePeakDbtp)
        assertEquals(1080, s.resolutionW)
        assertEquals(1920, s.resolutionH)
        assertEquals(ExportCodec.AVC, s.codec)
        assertEquals(8, s.bitDepth)
        assertEquals(BitrateLevel.STANDARD, s.bitrateLevel)
        assertEquals(192, s.audioKbps)
    }

    @Test
    fun changing_any_social_field_drops_it_to_custom() {
        val base = ExportPreset.SOCIAL.apply(ExportSettings())
        val changed = listOf(
            base.copy(loudnessTargetLufs = null),
            base.copy(loudnessTargetLufs = -16f),
            base.copy(truePeakDbtp = -2f),
            base.copy(resolutionW = null, resolutionH = null),
            base.copy(resolutionH = 1080),
            base.copy(codec = ExportCodec.HEVC),
            base.copy(audioKbps = 128),
        )
        for (s in changed) assertEquals("$s", ExportPreset.CUSTOM, ExportPreset.of(s))
        // and the untouched preset is itself
        assertEquals(ExportPreset.SOCIAL, ExportPreset.of(base))
    }

    @Test
    fun applying_any_other_preset_clears_the_loudness_target() {
        // A preset application never inherits a stale target: the base
        // resets the E5 fields, so COMPATIBLE after SOCIAL is honestly
        // loudness-OFF (and the pair with it).
        val social = ExportPreset.SOCIAL.apply(ExportSettings())
        val compatible = ExportPreset.COMPATIBLE.apply(social)
        assertNull(compatible.loudnessTargetLufs)
        assertNull(compatible.resolutionW)
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(compatible))
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
            loudnessTargetLufs = -14f, truePeakDbtp = -1.5f, resolutionW = 1080, resolutionH = 1920,
            targetFps = TargetFps.FPS_29_97,
            audioSampleRateHz = 48000, audioChannels = 1,
        )
        val text = ExportSettingsWire.render(s)
        assertTrue(text, text.startsWith("{\"v\":3,"))
        assertEquals(ExportSettingsWire.Parsed.Ok(s), parseWire(text))
        assertEquals(ExportSettingsWire.Parsed.Ok(ExportSettings()), parseWire(ExportSettingsWire.render(ExportSettings())))
    }

    @Test
    fun wire_omits_absent_optionals() {
        val text = ExportSettingsWire.render(ExportSettings())
        assertFalse(text, text.contains("customMbps"))
        assertFalse(text, text.contains("profile"))
        // v2's fields are omit-when-default too: OFF loudness, the −1
        // ceiling default and a null resolution pair render nothing, so a
        // default row keeps its v1 shape (plus the version bump).
        assertFalse(text, text.contains("loudnessTargetLufs"))
        assertFalse(text, text.contains("truePeakDbtp"))
        assertFalse(text, text.contains("resolutionW"))
        // v3's fields are omit-when-default too: SOURCE = no conversion
        // and SOURCE rate/channels render nothing, so a default row keeps
        // its v1 shape (plus the version bump).
        assertFalse(text, text.contains("targetFps"))
        assertFalse(text, text.contains("audioSampleRateHz"))
        assertFalse(text, text.contains("audioChannels"))
    }

    @Test
    fun wire_never_guesses_a_newer_or_unknown_value() {
        fun unsupported(text: String) = parseWire(text) is ExportSettingsWire.Parsed.Unsupported
        val ok = ExportSettingsWire.render(ExportSettings())
        // A version this build cannot read refuses — v3 is now US, so the
        // unknown-version case is v4.
        assertTrue(unsupported(ok.replace("\"v\":3", "\"v\":4")))
        assertTrue(unsupported(ok.replace("\"AVC\"", "\"AV1\"")))
        assertTrue(unsupported(ok.replace("\"VBR\"", "\"ABR\"")))
        assertTrue(unsupported("{\"codec\":\"AVC\"}")) // no version
        assertTrue(unsupported("\"not an object\""))
        // An unknown target fps name is a refused VALUE, never a silent
        // SOURCE fall (the honest rule, plan E1F §3.2).
        assertTrue(unsupported(ok.replace("\"keyframe\":\"AUTO\"", "\"keyframe\":\"AUTO\",\"targetFps\":\"FPS_23_976\"")))
    }

    // ---- TargetFps (E1f-1) ----

    @Test
    fun the_ntsc_targets_are_the_exact_rationals() {
        // 29.97 is a LABEL: the rate is exactly 30000/1001 (and 59.94 =
        // 60000/1001); the grid math uses num/den, never the decimal.
        assertEquals(30000, TargetFps.FPS_29_97.num)
        assertEquals(1001, TargetFps.FPS_29_97.den)
        assertEquals(60000, TargetFps.FPS_59_94.num)
        assertEquals(1001, TargetFps.FPS_59_94.den)
        assertEquals("29.97", TargetFps.FPS_29_97.label)
        assertEquals("59.94", TargetFps.FPS_59_94.label)
        assertEquals("Source", TargetFps.SOURCE.label)
    }

    @Test
    fun presets_never_touch_the_target_fps() {
        // Plan §3.1: preset applications leave targetFps alone — a
        // QUALITY export of a 30-target row keeps the target. Because
        // apply() never resets the field, a target the user picked
        // survives every preset application: the identity comparison
        // treats the target as OUTSIDE the preset's meaning, exactly the
        // plan's "presets do not touch this field" rule.
        val s = ExportPreset.QUALITY.apply(ExportSettings(targetFps = TargetFps.FPS_24))
        assertEquals(TargetFps.FPS_24, s.targetFps)
        assertEquals(ExportPreset.QUALITY, ExportPreset.of(s))
        // and the preset comparison is target-blind: the same target on
        // the COMPATIBLE shape stays COMPATIBLE, and changing it changes
        // no preset identity at all.
        val base = ExportPreset.COMPATIBLE.apply(ExportSettings(targetFps = TargetFps.FPS_25))
        assertEquals(TargetFps.FPS_25, base.targetFps)
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(base))
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(base.copy(targetFps = TargetFps.FPS_60)))
    }

    // ---- wire v1/v2 <-> v3 (E5 + E1f, integrated chain) ----

    @Test
    fun wire_v1_rows_parse_with_the_v1_behavior() {
        // A row this app's v1 build wrote (no loudness, no ceiling, no
        // resolution pair, no target fps): parses back as EXACTLY the v1
        // settings — the loudness chain stays OFF, the ladder keeps
        // deciding the size, and the fps conversion stays OFF (plan E1F
        // §3.2: the v1 reader's behavior, never a silent fall).
        val v1 = "{\"v\":1,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":192}"
        val parsed = parseWire(v1)
        assertTrue(parsed is ExportSettingsWire.Parsed.Ok)
        val s = (parsed as ExportSettingsWire.Parsed.Ok).settings
        assertEquals(ExportSettings(codec = ExportCodec.AVC, audioKbps = 192), s)
        assertNull(s.loudnessTargetLufs)
        assertEquals(-1f, s.truePeakDbtp)
        assertNull(s.resolutionW)
        assertNull(s.resolutionH)
        assertEquals(TargetFps.SOURCE, s.targetFps)
    }

    @Test
    fun wire_v2_round_trips_the_loudness_fields_and_the_pair() {
        val s = ExportSettings(loudnessTargetLufs = -16f, truePeakDbtp = -2f, resolutionW = 1080, resolutionH = 1920)
        val back = parseWire(ExportSettingsWire.render(s))
        assertEquals(ExportSettingsWire.Parsed.Ok(s), back)
        // A ceiling without a target round-trips too (inert, but kept).
        val inert = ExportSettings(truePeakDbtp = -2f)
        assertEquals(ExportSettingsWire.Parsed.Ok(inert), parseWire(ExportSettingsWire.render(inert)))
        // A half pair is honest about what it holds: the wire is lossless
        // (each side travels on its own), so nothing is guessed away.
        val half = ExportSettings(resolutionW = 1080)
        assertEquals(ExportSettingsWire.Parsed.Ok(half), parseWire(ExportSettingsWire.render(half)))
    }

    @Test
    fun wire_v2_defaults_are_off_not_zero() {
        // A hand-written v2 row without the loudness key parses as null
        // (OFF) — never 0 LUFS, never a guessed target. A v2 row never had
        // a targetFps either: it reads as SOURCE, its own meaning.
        val v2 = "{\"v\":2,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":128}"
        assertEquals(ExportSettingsWire.Parsed.Ok(ExportSettings()), parseWire(v2))
    }

    @Test
    fun wire_v3_round_trips_every_ladder_target() {
        // Every ladder value travels by NAME and comes back exactly; the
        // NTSC pair carries the exact rational, never the 29.97 decimal.
        for (t in listOf(TargetFps.FPS_24, TargetFps.FPS_25, TargetFps.FPS_29_97, TargetFps.FPS_30, TargetFps.FPS_50, TargetFps.FPS_59_94, TargetFps.FPS_60)) {
            val s = ExportSettings(targetFps = t)
            assertEquals("targetFps=$t", ExportSettingsWire.Parsed.Ok(s), parseWire(ExportSettingsWire.render(s)))
        }
    }

    @Test
    fun wire_v3_round_trips_loudness_and_target_fps_together() {
        // The v3 row carries BOTH branches' fields at once: the loudness
        // chain and the fps conversion are independent settings, and one
        // render keeps them both lossless.
        val s = ExportSettings(
            loudnessTargetLufs = -14f, truePeakDbtp = -1.5f,
            resolutionW = 1080, resolutionH = 1920,
            targetFps = TargetFps.FPS_29_97,
        )
        assertEquals(ExportSettingsWire.Parsed.Ok(s), parseWire(ExportSettingsWire.render(s)))
    }

    // ---- wire v3 (E5-B, night G1: sample rate + channels) ----

    @Test
    fun wire_v3_round_trips_the_audio_shape_fields() {
        val s = ExportSettings(audioSampleRateHz = 44100, audioChannels = 2)
        assertEquals(ExportSettingsWire.Parsed.Ok(s), parseWire(ExportSettingsWire.render(s)))
        // Each side travels on its own — a half choice is honest about
        // what it holds (the resolution pair's rule).
        val rateOnly = ExportSettings(audioSampleRateHz = 48000)
        assertEquals(ExportSettingsWire.Parsed.Ok(rateOnly), parseWire(ExportSettingsWire.render(rateOnly)))
        val chOnly = ExportSettings(audioChannels = 1)
        assertEquals(ExportSettingsWire.Parsed.Ok(chOnly), parseWire(ExportSettingsWire.render(chOnly)))
    }

    @Test
    fun wire_v3_round_trips_target_fps_and_audio_shape_together() {
        // The merged v3 row carries BOTH v3 branches' fields at once: the
        // fps conversion and the audio shape are independent settings,
        // and one render keeps them both lossless.
        val s = ExportSettings(
            targetFps = TargetFps.FPS_29_97,
            audioSampleRateHz = 44100, audioChannels = 1,
        )
        assertEquals(ExportSettingsWire.Parsed.Ok(s), parseWire(ExportSettingsWire.render(s)))
    }

    @Test
    fun wire_v2_rows_parse_with_the_v2_behavior() {
        // A row this app's v2 build wrote (loudness fields, resolution
        // pair, but NO audio shape keys): parses back with SOURCE rate
        // and channels — the v2 behavior exactly (the resampler/remix
        // never runs for it).
        val v2 = "{\"v\":2,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":192," +
            "\"loudnessTargetLufs\":-16.0,\"truePeakDbtp\":-1.0," +
            "\"resolutionW\":1080,\"resolutionH\":1920}"
        val parsed = parseWire(v2)
        assertTrue(parsed is ExportSettingsWire.Parsed.Ok)
        val s = (parsed as ExportSettingsWire.Parsed.Ok).settings
        assertEquals(-16f, s.loudnessTargetLufs)
        assertEquals(-1f, s.truePeakDbtp)
        assertEquals(1080, s.resolutionW)
        assertEquals(1920, s.resolutionH)
        assertNull(s.audioSampleRateHz)
        assertNull(s.audioChannels)
    }

    @Test
    fun wire_v1_rows_parse_with_source_audio_shape() {
        // The v1 row test's row again, now asserting the G1 fields too:
        // absent = SOURCE, never a guessed number.
        val v1 = "{\"v\":1,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":192}"
        val parsed = parseWire(v1)
        val s = (parsed as ExportSettingsWire.Parsed.Ok).settings
        assertNull(s.audioSampleRateHz)
        assertNull(s.audioChannels)
    }

    @Test
    fun applying_any_preset_clears_the_audio_shape() {
        // A preset never inherits a stale rate/channel choice: the base
        // resets the E5-B fields, so COMPATIBLE after a 44.1 kHz mono
        // pick is honestly SOURCE (and the preset row reads itself).
        val shaped = ExportSettings(audioSampleRateHz = 44100, audioChannels = 1)
        val compatible = ExportPreset.COMPATIBLE.apply(shaped)
        assertNull(compatible.audioSampleRateHz)
        assertNull(compatible.audioChannels)
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(compatible))
        // and changing one of the fields a preset owns still drops it
        val social = ExportPreset.SOCIAL.apply(ExportSettings())
        assertEquals(ExportPreset.CUSTOM, ExportPreset.of(social.copy(audioChannels = 2)))
    }
}
