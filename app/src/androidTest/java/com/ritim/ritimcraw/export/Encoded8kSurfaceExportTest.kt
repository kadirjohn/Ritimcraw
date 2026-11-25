package com.ritim.ritimcraw.export

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.RenderBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E0b-5 device acceptance: an 8K ENCODED clip exports through the GPU -> encoder-Surface door (no full-frame RGB,
 * no CPU oracle). NOT run by the author (no device access); the coordinator runs it. Skipped when the fixture is
 * absent, so a normal androidTest run is unaffected.
 *
 * Setup (coordinator):
 *   adb push VID_20261124_175209_8K.mp4 /data/local/tmp/e0-fixture-8k.mp4
 *   adb shell run-as com.ritim.ritimcraw cp /data/local/tmp/e0-fixture-8k.mp4 cache/e0-fixture-8k.mp4
 *   (the package may carry a debug suffix; use the app's actual applicationId)
 * Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.ritim.ritimcraw.export.Encoded8kSurfaceExportTest
 * Read: adb logcat -s ritim-e8k ritim-export ritim-trace
 *
 * What it does: exports the first FRAMES (default 12) frames of the clip at Original size (no crop/scale) through
 * ExportPipeline with the real EncodedMp4Session and MediaCodecMp4Writer, then checks the log and the file.
 */
@RunWith(AndroidJUnit4::class)
class Encoded8kSurfaceExportTest {

    private val tag = "ritim-e8k"
    private val frames = 12

    private fun fixture(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, "e0-fixture-8k.mp4")
    }

    /** The real encoded session, capped to the first [frames] frames (the pipeline reads frameCount from open). */
    private class Capped(private val inner: EncodedMp4Session, private val cap: Int) : Mp4Session by inner {
        override fun open(fd: Int): Mp4SessionInfo? = inner.open(fd)?.let { it.copy(frameCount = minOf(it.frameCount, cap)) }
    }

    @Test
    fun the_first_frames_of_the_8k_clip_export_through_the_encoder_surface() {
        val f = fixture()
        assumeTrue("8K fixture not provided", f.exists())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "e0-8k-surface-out.mp4")
        out.delete()
        val lines = ArrayList<String>()
        val log: (String) -> Unit = { line -> lines.add(line); Log.i(tag, line) }
        val session = Capped(EncodedMp4Session(log = log), frames)
        val settings = ExportSheetModel.defaultSettings()
        val run = ExportPlanRun(settings, { DeviceEncoderFacts.all() }, ExportContext(), log)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val t0 = System.nanoTime()
        try {
            ExportPipeline(
                session, MediaCodecMp4Writer(), log,
                renderBackend = RenderBackend.CPU, // encoded sources are CPU-requested; the surface door is the session's own
                audioAttached = false,
                planRun = run,
            ).run(pfd.fd, GradeK(), out.absolutePath, onProgress = { d, total -> log("progress $d/$total") })
        } finally {
            pfd.close()
        }
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "8k surface export: $frames frames in $wallMs ms, file ${out.length()} bytes")
        // 1. the path the planner chose and the one the pipeline ended on
        assertTrue("planner must pick the surface path: $lines", lines.any { it.contains("encoded export path: surface") })
        assertTrue("encoder-input must be surface-gpu: $lines", lines.any { it.contains("encoder-input=surface-gpu") })
        // 2. no CPU oracle, no RGB readback
        assertTrue("no CPU fallback may be logged: $lines", lines.none { it.contains("encoder surface fallback") })
        assertTrue("no CPU develop path line: $lines", lines.none { it.contains("encoded export develop path: cpu") })
        // 3. a real file came out (the host ffprobes it: 7680x4320, $frames frames)
        assertTrue(out.length() > 0)
        assertEquals(frames, lines.count { it.startsWith("progress ") && !it.startsWith("progress 0/") })
    }
}
