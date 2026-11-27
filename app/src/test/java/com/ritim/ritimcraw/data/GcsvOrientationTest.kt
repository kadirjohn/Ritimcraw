package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class GcsvOrientationTest {

    @Test
    fun candidateFolders_dedupes_and_prefers_import_folder() {
        assertEquals(
            listOf("content://import"),
            GcsvOrientation.candidateFolders("content://import", "content://import"),
        )
        assertEquals(
            listOf("content://import", "content://gyro"),
            GcsvOrientation.candidateFolders("content://import", "content://gyro"),
        )
        assertEquals(
            listOf("content://gyro"),
            GcsvOrientation.candidateFolders(null, "content://gyro"),
        )
        assertEquals(
            emptyList<String>(),
            GcsvOrientation.candidateFolders("", "   "),
        )
    }

    @Test
    fun readPrefix_caps_at_complete_lines() {
        val text = (1..20).joinToString("\n") { "line$it" } + "\n"
        val prefix = GcsvOrientation.readPrefix(
            ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
            maxLines = 5,
        )
        assertEquals("line1\nline2\nline3\nline4\nline5\n", prefix)
    }

    @Test
    fun readPrefix_returns_whole_short_stream() {
        val text = "GYROFLOW IMU LOG\nversion,1.2\n"
        val prefix = GcsvOrientation.readPrefix(
            ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
            maxLines = 100,
        )
        assertEquals(text, prefix)
    }

    @Test
    fun decideManualRotation_applies_nonzero_quarter_delta_on_fresh_clip() {
        assertEquals(270, GcsvOrientation.decideManualRotation(0, 270))
        assertEquals(90, GcsvOrientation.decideManualRotation(0, 90))
        assertEquals(180, GcsvOrientation.decideManualRotation(0, 180))
    }

    @Test
    fun decideManualRotation_keeps_fallback_when_undecidable_or_already_aligned() {
        assertNull(GcsvOrientation.decideManualRotation(0, -1))
        assertNull(GcsvOrientation.decideManualRotation(0, 0))
        assertNull(GcsvOrientation.decideManualRotation(0, 360))
        assertNull(GcsvOrientation.decideManualRotation(0, 45))
    }

    @Test
    fun decideManualRotation_never_overrides_existing_user_rotation() {
        assertNull(GcsvOrientation.decideManualRotation(90, 270))
        assertNull(GcsvOrientation.decideManualRotation(180, 90))
        assertNull(GcsvOrientation.decideManualRotation(270, 180))
    }

    @Test
    fun previewSurfaceDisplayRatio_composes_container_and_manual_on_gpu_1x1_placeholder() {
        // DIKEY (4096x2304 raw, container=90, manual=0) with GPU 1x1 placeholder -> portrait 9:16
        val dikey = com.ritim.ritimcraw.ui.editor.PreviewSurfaceRatio.displayRatio(
            frameWidth = 1, frameHeight = 1,
            sourceWidth = 4096, sourceHeight = 2304,
            containerOrientationDeg = 90, manualRotationDeg = 0,
            frameAlreadyEffective = true,
        )
        assertEquals(2304f / 4096f, dikey, 1e-4f)

        // Locked-rotation landscape (4096x2304 raw, container=90, manual=270 -> effective=0) -> landscape 16:9
        val landCode0 = com.ritim.ritimcraw.ui.editor.PreviewSurfaceRatio.displayRatio(
            frameWidth = 1, frameHeight = 1,
            sourceWidth = 4096, sourceHeight = 2304,
            containerOrientationDeg = 90, manualRotationDeg = 270,
            frameAlreadyEffective = true,
        )
        assertEquals(4096f / 2304f, landCode0, 1e-4f)

        // GPU stats frame (already effective 256x144) is NOT swapped a second time by manual=270
        val gpuStats = com.ritim.ritimcraw.ui.editor.PreviewSurfaceRatio.displayRatio(
            frameWidth = 256, frameHeight = 144,
            sourceWidth = 4096, sourceHeight = 2304,
            containerOrientationDeg = 90, manualRotationDeg = 270,
            frameAlreadyEffective = true,
        )
        assertEquals(256f / 144f, gpuStats, 1e-4f)

        // CPU frame (container-upright 144x256) IS swapped by manual=270 -> landscape 16:9
        val cpuFrame = com.ritim.ritimcraw.ui.editor.PreviewSurfaceRatio.displayRatio(
            frameWidth = 144, frameHeight = 256,
            sourceWidth = 4096, sourceHeight = 2304,
            containerOrientationDeg = 90, manualRotationDeg = 270,
            frameAlreadyEffective = false,
        )
        assertEquals(256f / 144f, cpuFrame, 1e-4f)
    }
}
