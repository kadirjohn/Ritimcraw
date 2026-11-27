package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.export.ExportPreset
import com.ritim.ritimcraw.export.ExportSettings
import com.ritim.ritimcraw.export.ExportRateMode
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.ExportSheetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export sheet's pure display rules (E1c-polish). */
class ExportSheetStateTest {

    private fun ladder(w: Int, h: Int) =
        ExportResolution.ladder(RotationState.Dims(w, h), 30.0, null)

    @Test
    fun a_uhd_source_lists_original_and_no_second_uhd() {
        val opts = ExportSheetState.resolutionOptions(ladder(3840, 2160))
        assertEquals(
            listOf(ExportResolution.Kind.ORIGINAL, ExportResolution.Kind.FHD, ExportResolution.Kind.HD),
            opts.map { it.kind },
        )
    }

    @Test
    fun a_dci_source_keeps_uhd_because_it_is_a_different_size() {
        val opts = ExportSheetState.resolutionOptions(ladder(4096, 2160))
        assertTrue(opts.any { it.kind == ExportResolution.Kind.UHD })
    }

    @Test
    fun a_fhd_source_drops_fhd_and_keeps_the_other_entries() {
        val opts = ExportSheetState.resolutionOptions(ladder(1920, 1080))
        assertEquals(
            listOf(ExportResolution.Kind.ORIGINAL, ExportResolution.Kind.UHD, ExportResolution.Kind.HD),
            opts.map { it.kind },
        )
    }

    @Test
    fun a_measured_rate_shows_as_the_nearest_standard_one() {
        assertEquals(29.97, ExportSheetState.displayFps(29.826), 0.0)
        assertEquals(30.0, ExportSheetState.displayFps(30.02), 0.0)
        assertEquals(23.976, ExportSheetState.displayFps(23.9), 0.0)
        assertEquals(59.94, ExportSheetState.displayFps(59.9), 0.0)
        assertEquals(24.0, ExportSheetState.displayFps(24.1), 0.0)
        // far from every standard rate: rounded, not snapped
        assertEquals(37.12, ExportSheetState.displayFps(37.1234), 0.0)
        assertEquals("29.97", ExportSheetState.fpsText(29.826))
        assertEquals("30", ExportSheetState.fpsText(29.99))
    }

    @Test
    fun the_summary_line_reads_at_a_glance() {
        val subject = ExportSheetModel.Subject(3840, 2160, 29.826, 60.0)
        val plan = ExportSheetModel.plan(ExportSheetModel.defaultSettings(), subject, emptyList())
        val line = ExportSheetState.summaryLine(plan, "523 MB")
        assertTrue(line, line.startsWith("3840×2160 · 29.97 fps · H.264 8-bit · "))
        assertTrue(line, line.contains(" Mb/s"))
        assertTrue(line, line.endsWith(" · ~523 MB"))
        assertFalse(ExportSheetState.summaryLine(plan, null).contains("~"))
    }

    @Test
    fun changed_settings_match_no_preset_and_so_get_the_custom_label() {
        val s = ExportSheetModel.defaultSettings()
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(s))
        assertEquals(ExportPreset.CUSTOM, ExportPreset.of(s.copy(rateMode = ExportRateMode.CBR)))
    }

    @Test
    fun the_compact_summary_leads_with_the_format_and_reads_the_plan() {
        val subject = ExportSheetModel.Subject(3840, 2160, 29.826, 60.0)
        val plan = ExportSheetModel.plan(ExportSheetModel.defaultSettings(), subject, emptyList())
        assertEquals(
            "MP4 · 3840×2160 · 29.97 fps · H.264 8-bit · ≈ 420 MB",
            ExportSheetState.compactSummaryLine(plan, "420 MB"),
        )
        // Unknown length: the size part drops, nothing is guessed.
        assertEquals(
            "MP4 · 3840×2160 · 29.97 fps · H.264 8-bit",
            ExportSheetState.compactSummaryLine(plan, null),
        )
    }

    @Test
    fun the_bitrate_value_is_the_plans_not_the_requests() {
        val subject = ExportSheetModel.Subject(1920, 1080, 30.0, 10.0)
        val plan = ExportSheetModel.plan(ExportSheetModel.defaultSettings(), subject, emptyList())
        assertEquals(
            com.ritim.ritimcraw.export.ExportText.mbps(plan.bitrateBps.toLong()) + " Mb/s",
            ExportSheetState.bitrateValue(plan),
        )
    }

    @Test
    fun an_encoded_clip_has_no_format_row_and_keeps_every_other_row() {
        val raw = ExportSheetState.mainRows(encodedSource = false)
        val enc = ExportSheetState.mainRows(encodedSource = true)
        assertEquals(ExportSheetState.Row.FORMAT, raw.first())
        assertFalse(ExportSheetState.Row.FORMAT in enc)
        assertEquals(raw.drop(1), enc)
        // Bit depth stays visible (disabled in the UI, with its reason).
        assertTrue(ExportSheetState.Row.BIT_DEPTH in enc)
    }

    @Test
    fun the_main_and_advanced_rows_cover_every_option_once_in_the_spec_order() {
        // G4: DYNAMIC_RANGE joins the main rows (after BIT_DEPTH — the
        // range rides the codec/depth pair). The advanced set is unchanged.
        assertEquals(
            listOf("FORMAT", "PRESET", "RESOLUTION", "FRAME_RATE", "CODEC", "BIT_DEPTH", "DYNAMIC_RANGE"),
            ExportSheetState.mainRows(false).map { it.name },
        )
        assertEquals(
            listOf("BITRATE", "RATE_MODE", "KEYFRAME", "PROFILE", "AUDIO", "AUDIO_FORMAT"),
            ExportSheetState.advancedRows().map { it.name },
        )
        val all = ExportSheetState.mainRows(false) + ExportSheetState.advancedRows()
        assertEquals(ExportSheetState.Row.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun an_encoded_clip_still_lists_the_dynamic_range_row() {
        // G4: the row is always shown — disabled with the reason when the
        // gate cannot be verified (the bit-depth row's own rule).
        val enc = ExportSheetState.mainRows(true)
        assertTrue(ExportSheetState.Row.DYNAMIC_RANGE in enc)
    }

    // ---- hlgSelectable (G4): the DYNAMIC_RANGE row's pure gate state ----

    @Test
    fun hlg_is_selectable_only_when_the_plan_resolved_hlg_without_the_wall() {
        val hlg = ExportSettings(
            codec = com.ritim.ritimcraw.export.ExportCodec.HEVC, bitDepth = 10,
            dynamicRange = com.ritim.ritimcraw.export.ExportDynamicRange.HLG,
        )
        val facts = listOf(
            com.ritim.ritimcraw.export.EncoderFacts(
                "c2.qti.hevc.encoder", "video/hevc", hardware = true,
                profiles = setOf(1, 2), colorFormats = setOf(0x7F000789, 54),
                bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
                maxWidth = 8192, maxHeight = 8192,
                features = setOf("hlg-editing"),
            ),
        )
        val subject = ExportSheetModel.Subject(width = 3840, height = 2160, fps = 30.0, durationSec = 10.0)
        val gate = com.ritim.ritimcraw.export.HevcProbeSource.fromFacts(facts, 3840, 2160, 30.0, 37)
        // Open: the plan resolved HLG with no wall issue.
        val open = ExportSheetModel.plan(
            hlg, subject, facts,
            com.ritim.ritimcraw.export.ExportContext(tenBitPathAvailable = true, hlgGate = gate),
        )
        assertTrue(ExportSheetState.hlgSelectable(open))
    }

    @Test
    fun hlg_is_not_selectable_when_the_wall_degraded_the_plan() {
        val hlg = ExportSettings(
            codec = com.ritim.ritimcraw.export.ExportCodec.HEVC, bitDepth = 10,
            dynamicRange = com.ritim.ritimcraw.export.ExportDynamicRange.HLG,
        )
        val subject = ExportSheetModel.Subject(width = 3840, height = 2160, fps = 30.0, durationSec = 10.0)
        // No gate: the E1 wall degrades HLG to SDR (DYNAMIC_RANGE_UNAVAILABLE).
        val walled = ExportSheetModel.plan(hlg, subject, emptyList())
        assertFalse(ExportSheetState.hlgSelectable(walled))
        // A null plan (no subject yet) is closed too — never a guess.
        assertFalse(ExportSheetState.hlgSelectable(null))
    }

    @Test
    fun only_the_clip_derived_rows_are_read_only() {
        val ro = ExportSheetState.Row.entries.filter { ExportSheetState.isReadOnly(it) }
        assertEquals(
            setOf(ExportSheetState.Row.FRAME_RATE, ExportSheetState.Row.AUDIO_FORMAT),
            ro.toSet(),
        )
    }
}
