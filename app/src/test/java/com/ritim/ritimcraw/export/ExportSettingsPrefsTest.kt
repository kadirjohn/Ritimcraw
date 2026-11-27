package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The remembered export settings reopen a sheet without the target frame
 * rate: it is a per-clip choice (a remembered 30 made a 60 fps clip's sheet
 * suggest 30 fps).
 */
class ExportSettingsPrefsTest {

    @Test
    fun a_remembered_target_fps_does_not_carry_to_the_next_clip() {
        val remembered = ExportSettings(targetFps = TargetFps.FPS_30)
        assertEquals(TargetFps.SOURCE, ExportSettingsPrefs.forNewSheet(remembered).targetFps)
    }

    @Test
    fun every_other_remembered_setting_is_kept() {
        val remembered = ExportSettings(
            codec = ExportCodec.HEVC, bitDepth = 10, targetFps = TargetFps.FPS_25,
            loudnessTargetLufs = -14f, audioKbps = 192,
        )
        val reopened = ExportSettingsPrefs.forNewSheet(remembered)
        assertEquals(remembered.copy(targetFps = TargetFps.SOURCE), reopened)
        assertEquals(ExportCodec.HEVC, reopened.codec)
        assertEquals(10, reopened.bitDepth)
        assertEquals(-14f, reopened.loudnessTargetLufs)
        assertEquals(192, reopened.audioKbps)
    }

    @Test
    fun a_source_target_stays_source_and_the_default_is_unchanged() {
        assertEquals(TargetFps.SOURCE, ExportSettingsPrefs.forNewSheet(ExportSettings()).targetFps)
        assertEquals(ExportSettings(), ExportSettingsPrefs.forNewSheet(ExportSettings()))
    }
}
