package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.export.ExportPreset
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
    fun a_measured_rate_shows_raw_never_snapped_to_a_standard() {
        // TEST CHANGE (K6, plan E1F §1.2/§1.4): the old bde227d test pinned
        // the snap-within-1 % behavior this change REMOVES. The owner's
        // later ruling (2026-11-24): a measured 29.83 reads "29.83", a
        // near-standard 29.826 reads "29.83" too — rounding to 29.97 would
        // lie about what the phone recorded. The measured value is shown
        // at 2 decimals, never snapped.
        assertEquals(29.83, ExportSheetState.displayFps(29.826), 0.0)
        assertEquals(29.83, ExportSheetState.displayFps(29.83), 0.0)
        assertEquals(30.02, ExportSheetState.displayFps(30.02), 0.0)
        assertEquals(30.0, ExportSheetState.displayFps(30.0), 0.0)
        assertEquals(23.98, ExportSheetState.displayFps(23.976), 0.0)
        assertEquals(59.9, ExportSheetState.displayFps(59.9), 0.0)
        assertEquals(24.1, ExportSheetState.displayFps(24.1), 0.0)
        // far from every standard rate: rounded, not snapped
        assertEquals(37.12, ExportSheetState.displayFps(37.1234), 0.0)
        assertEquals("29.83", ExportSheetState.fpsText(29.826))
        assertEquals("29.83", ExportSheetState.fpsText(29.83))
        assertEquals("29.99", ExportSheetState.fpsText(29.99))
        assertEquals("30", ExportSheetState.fpsText(29.999999))
    }

    @Test
    fun the_summary_line_reads_at_a_glance() {
        val subject = ExportSheetModel.Subject(3840, 2160, 29.826, 60.0)
        val plan = ExportSheetModel.plan(ExportSheetModel.defaultSettings(), subject, emptyList())
        val line = ExportSheetState.summaryLine(plan, "523 MB")
        // TEST CHANGE (K6): the measured 29.826 shows raw (29.83), never snapped.
        assertTrue(line, line.startsWith("3840×2160 · 29.83 fps · H.264 8-bit · "))
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
            // TEST CHANGE (K6): the measured 29.826 shows raw (29.83).
            "MP4 · 3840×2160 · 29.83 fps · H.264 8-bit · ≈ 420 MB",
            ExportSheetState.compactSummaryLine(plan, "420 MB"),
        )
        // Unknown length: the size part drops, nothing is guessed.
        assertEquals(
            "MP4 · 3840×2160 · 29.83 fps · H.264 8-bit",
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
        assertEquals(
            listOf("FORMAT", "PRESET", "RESOLUTION", "FRAME_RATE", "CODEC", "BIT_DEPTH"),
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
    fun only_the_clip_derived_rows_are_read_only() {
        val ro = ExportSheetState.Row.entries.filter { ExportSheetState.isReadOnly(it) }
        assertEquals(
            setOf(ExportSheetState.Row.FRAME_RATE, ExportSheetState.Row.AUDIO_FORMAT),
            ro.toSet(),
        )
    }

}
