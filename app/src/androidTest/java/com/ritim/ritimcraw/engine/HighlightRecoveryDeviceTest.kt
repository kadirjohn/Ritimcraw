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
 * Highlight recovery, GPU vs the CPU oracle, on REAL clipped MCRAW frames (docs/HIGHLIGHT_RECOVERY_PLAN.md,
 * M3). Develops the frame through both doors (the GPU develop session and the CPU export door, the same
 * parcel) and logs one "ritim-parity" line per case:
 *   recovery-dev frame=N case=...: maxDiffLsb mismatch% over1% cpuMoved% gpuMoved% (direction: GPU minus CPU,
 *   unit: 8-bit code values of the RGB888 render; moved% = share of bytes that differ from the same door's
 *   recovery-off render).
 * Instrumentation args: -e clip <path> (default: the 261112_220209 night clip), -e frames 0,900.
 */
class HighlightRecoveryDeviceTest {
    private val tag = "ritim-parity"

    @Test
    fun recovery_matches_the_cpu_oracle_on_real_clipped_frames() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("clip") ?: "/sdcard/Documents/MotionCam/261112_220209_VIDEO_99mm.mcraw"
        val frames = (args.getString("frames") ?: "0,900").split(",").map { it.trim().toInt() }
        assertTrue("native engine", NativeSource.nativeAvailable)
        val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val fd = pfd.detachFd()
        val info = NativeSource.jniPreviewOpen(fd)
        assertNotNull("clip open failed: ${NativeSource.previewLastError()}", info)
        assertTrue(info!![0] != 0)
        val clip = info[0].toLong()
        val session = NativeGpuDevelop.openSession()
        assertTrue("gpu session: ${NativeSource.previewLastError()}", session != 0L)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun gpu(g: GradeK, f: Int) = NativeGpuDevelop.renderFrameParcel(
            session, clip, f, GradeParcel.encode(g), null, 0f, -1, -1, -1, -1, -1, false, identity,
        )
        fun cpu(g: GradeK, f: Int) = NativeSource.jniExportFrameParcel(
            clip, f, GradeParcel.encode(g), null, 0f, null, 0, 0f, 0f, 0f, 0f, -1, -1, -1, -1, -1,
        )
        try {
            val cases = listOf(
                "neutral-hr-off" to GradeK(highlightReconstruction = false),
                "neutral-2ev" to GradeK(exposureEv = -2f),
                "recovery" to GradeK(highlightRecovery = true),
                "recovery-2ev" to GradeK(highlightRecovery = true, exposureEv = -2f),
                "recovery-hl-100" to GradeK(highlightRecovery = true, highlights = -100f),
            )
            for (f in frames) {
                val offGpu = gpu(GradeK(), f)
                val offCpu = cpu(GradeK(), f)
                assertNotNull("off render frame $f", offGpu)
                assertNotNull("off render frame $f", offCpu)
                val base = GradeParitySweep.compare(offGpu!!, offCpu!!)
                Log.i(tag, "recovery-dev frame=$f case=neutral-default: maxDiffLsb=${base.maxDiffLsb} mismatch=%.4f%% over1=%.4f%%"
                    .format(base.mismatchPct, base.over1Pct))
                for ((name, g) in cases) {
                    val offG2 = if (g.exposureEv == 0f && g.highlights == 0f) offGpu else gpu(g.copy(highlightRecovery = false), f)!!
                    val offC2 = if (g.exposureEv == 0f && g.highlights == 0f) offCpu else cpu(g.copy(highlightRecovery = false), f)!!
                    val t0 = System.nanoTime()
                    val gg = gpu(g, f)
                    val t1 = System.nanoTime()
                    val cc = cpu(g, f)
                    assertNotNull("gpu $name frame $f: ${NativeSource.previewLastError()}", gg)
                    assertNotNull("cpu $name frame $f", cc)
                    val s = GradeParitySweep.compare(gg!!, cc!!)
                    Log.i(tag, "recovery-dev frame=$f case=$name: maxDiffLsb=${s.maxDiffLsb} mismatch=%.4f%% over1=%.4f%% meanSigned=%.4f cpuMoved=%.3f%% gpuMoved=%.3f%% samples=${s.samples} gpuMs=%.1f"
                        .format(s.mismatchPct, s.over1Pct, s.meanSigned,
                            GradeParitySweep.movedPct(cc, offC2), GradeParitySweep.movedPct(gg, offG2), (t1 - t0) / 1e6))
                }
            }
            // Sustained GPU timing (the GPU clocks down between the bursty parity renders): back-to-back renders
            // of the same frame; with `setprop debug.ritim.rectime 1` the pipeline logs each pass's GPU wall time.
            val reps = (args.getString("reps") ?: "0").toInt()
            if (reps > 0) {
                for ((name, g) in listOf(
                    "timing-neutral-hr-off" to GradeK(highlightReconstruction = false),
                    "timing-neutral" to GradeK(),
                    "timing-recovery" to GradeK(highlightRecovery = true),
                )) {
                    Log.i(tag, "recovery-dev timing begin $name")
                    repeat(reps) { gpu(g, frames[0]) }
                    Log.i(tag, "recovery-dev timing end $name")
                }
            }
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(clip)
        }
    }
}
