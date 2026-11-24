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
}
