package com.ritim.ritimcraw.export

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.EncodedSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E0 device acceptance (no UI, no user data). The host copies a FIXTURE clip
 * into the app's cache dir (adb push + run-as cp) as e0-fixture.mp4; this
 * test then
 *  1. decodes + develops frame 0 through the real MediaCodec/JNI path and
 *     writes the 8-bit RGB (cache/e0-frame0.rgb) so the host can compare it
 *     with the desktop develop of the same frame (the CPU P010 oracle);
 *  2. exports the whole clip through Mp4Exporter (isEncoded) into
 *     cache/e0-out.mp4; the host ffprobes it (pix_fmt, color tags, duration,
 *     audio).
 * Everything is logged under the tag "ritim-e0". Skipped when the fixture is
 * absent, so a normal androidTest run is unaffected.
 */
@RunWith(AndroidJUnit4::class)
class EncodedExportDeviceTest {

    private val tag = "ritim-e0"

    private fun fixture(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, "e0-fixture.mp4")
    }

    @Test
    fun frame0_develops_through_the_real_decoder() {
        val f = fixture()
        assumeTrue("fixture not provided", f.exists())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val source = EncodedSource()
        try {
            val info = source.open(pfd.detachFd())
            Log.i(tag, "open: ${info.mime} ${info.width}x${info.height} frames=${info.frameCount} color=${info.color.describe()}")
            val t0 = System.nanoTime()
            val argb = source.renderFrame(0, GradeK(), null, upright = false)
            val ms = (System.nanoTime() - t0) / 1e6
            Log.i(tag, "frame0 decode+develop ${"%.0f".format(ms)} ms (first frame incl. codec warm-up)")
            val t1 = System.nanoTime()
            source.renderFrame(1, GradeK(), null, upright = false)
            Log.i(tag, "frame1 decode+develop ${"%.0f".format((System.nanoTime() - t1) / 1e6)} ms")
            val rgb = EncodedMp4Session.argbToRgb888(argb, info.width, info.height)
            File(ctx.cacheDir, "e0-frame0.rgb").writeBytes(rgb)
            Log.i(tag, "wrote e0-frame0.rgb ${rgb.size} bytes")
            assertEquals(info.width * info.height * 3, rgb.size)
        } finally {
            source.close()
        }
    }

    @Test
    fun the_whole_clip_exports_with_audio() {
        val f = fixture()
        assumeTrue("fixture not provided", f.exists())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "e0-out.mp4")
        out.delete()
        val job = ExportJob(
            id = "e0-device", sourceId = "e0-fixture", snapshot = GradeK(),
            target = Target.MP4, state = State.QUEUED,
            settings = ExportSheetModel.defaultSettings(),
        )
        val exporter = Mp4Exporter(
            isEncoded = { true },
            stageLog = { line -> Log.i(tag, line) },
        )
        val pfd = ParcelFileDescriptor.open(fixture(), ParcelFileDescriptor.MODE_READ_ONLY)
        var last = 0
        val t0 = System.nanoTime()
        val done = runBlocking {
            exporter.export(job, pfd.fd, out) { d, total ->
                if (d - last >= 30 || d == total) {
                    last = d
                    Log.i(tag, "progress $d/$total after ${(System.nanoTime() - t0) / 1_000_000_000}s")
                }
            }
        }
        pfd.close()
        Log.i(tag, "export state=${done.state} detail=${done.detail} bytes=${out.length()} " +
            "wall=${(System.nanoTime() - t0) / 1_000_000_000}s")
        done.report?.let { Log.i(tag, "report: $it") }
        assertTrue("export failed: ${done.detail}", done.state == State.DONE)
        assertTrue(out.length() > 0)
    }
}
