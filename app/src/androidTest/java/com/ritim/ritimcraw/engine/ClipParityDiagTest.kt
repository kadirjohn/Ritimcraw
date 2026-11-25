package com.ritim.ritimcraw.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.NativeGpuDevelop
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPU vs CPU parity of ONE real clip frame under a handful of grades, to find which stage splits them
 * (the editor's parity gate fell back to CPU on a DCG-mode clip: black 1023, white 16383). Logs one
 * "ritim-parity" line per case: direction GPU minus CPU, unit 8-bit RGB888 codes.
 * Args: -e clip <path> -e frames 0,1,2
 */
class ClipParityDiagTest {
    private val tag = "ritim-parity"

    @Test
    fun parity_by_stage_on_one_clip() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("clip")!!
        val frames = (args.getString("frames") ?: "0").split(",").map { it.trim().toInt() }
        assertTrue("native engine", NativeSource.nativeAvailable)
        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        val info = NativeSource.jniPreviewOpen(fd)
        assertNotNull("clip open failed: ${NativeSource.previewLastError()}", info)
        val clip = info!![0].toLong()
        val session = NativeGpuDevelop.openSession()
        assertTrue(session != 0L)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun gpu(g: GradeK, f: Int) = NativeGpuDevelop.renderFrameParcel(
            session, clip, f, GradeParcel.encode(g), null, 0f, -1, -1, -1, -1, -1, false, identity,
        )
        fun cpu(g: GradeK, f: Int) = NativeSource.jniExportFrameParcel(
            clip, f, GradeParcel.encode(g), null, 0f, null, 0, 0f, 0f, 0f, 0f, -1, -1, -1, -1, -1,
        )
        try {
            val cases = listOf(
                "default" to GradeK(),
                "hr-off" to GradeK(highlightReconstruction = false),
                "vignette-off" to GradeK(keepVignetting = false),
                "hr-off+vignette-off" to GradeK(highlightReconstruction = false, keepVignetting = false),
                "+3ev" to GradeK(exposureEv = 3f),
                "hr-off+3ev" to GradeK(highlightReconstruction = false, exposureEv = 3f),
                "highlights-100" to GradeK(highlights = -100f),
            )
            for (f in frames) for ((name, g) in cases) {
                val gg = gpu(g, f)
                val cc = cpu(g, f)
                assertNotNull("gpu $name f$f: ${NativeSource.previewLastError()}", gg)
                assertNotNull("cpu $name f$f", cc)
                val s = GradeParitySweep.compare(gg!!, cc!!)
                Log.i(tag, "clip-diag frame=$f case=$name: maxDiffLsb=${s.maxDiffLsb} mismatch=%.4f%% over1=%.4f%% meanSigned=%.4f samples=${s.samples}"
                    .format(s.mismatchPct, s.over1Pct, s.meanSigned))
            }
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(clip)
        }
    }

    /** The editor's open-time gate (case 1) and its A5 histograms on the given clip(s): -e clips a.mcraw,b.mcraw */
    @Test
    fun the_editor_parity_gate_on_clips() {
        val args = InstrumentationRegistry.getArguments()
        for (path in args.getString("clips")!!.split(",")) {
            for (case in 0..1) {
                val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                val txt = NativeSource.jniGlesFragmentParityDiag(
                    fd, GlesShaderSources.FULLSCREEN_VERTEX, GlesShaderSources.FULLSCREEN_VERTEX_310,
                    GlesShaderSources.DEVELOP_FRAGMENT, GlesShaderSources.STORE_FRAGMENT, case,
                )
                Log.i(tag, "gate-diag clip=${File(path).name} case=$case:\n$txt")
            }
        }
    }
}
