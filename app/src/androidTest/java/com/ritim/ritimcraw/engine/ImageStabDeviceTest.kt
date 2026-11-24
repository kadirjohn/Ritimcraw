package com.ritim.ritimcraw.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S4 device acceptance, UI-less (no user data): drives the JNI image-stab
 * doors on a FIXTURE clip passed as `-e fixture <path>`. Writes only into the
 * app's cache dir (cache/imagestab_s4test), never the user's project files.
 * Log tag ritim-s4. Run:
 *   am instrument -w -e fixture /data/local/tmp/ritim_s4fx/yavas_pan.mcraw \
 *     -e class com.ritim.ritimcraw.engine.ImageStabDeviceTest \
 *     com.ritim.ritimcraw.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ImageStabDeviceTest {

    private val tag = "ritim-s4"

    private fun fixture(): File {
        val args = InstrumentationRegistry.getArguments()
        val p = args.getString("fixture")
        assumeTrue("no -e fixture given", p != null)
        val f = File(p!!)
        assumeTrue("fixture missing: $p", f.isFile)
        return f
    }

    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, "imagestab_s4test").apply { deleteRecursively(); mkdirs() }
    }

    private fun analyze(clip: File, out: File, threads: Int, cancelAfterMs: Long = -1): Int {
        val job = NativeSource.jniImageStabJobNew()
        assertTrue(job >= 0)
        val pfd = ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            var canceller: Thread? = null
            if (cancelAfterMs >= 0) {
                canceller = Thread {
                    Thread.sleep(cancelAfterMs)
                    NativeSource.jniImageStabJobCancel(job)
                }.also { it.start() }
            }
            val rc = NativeSource.jniImageStabAnalyze(pfd.fd, out.absolutePath, job, threads)
            canceller?.join()
            val p = NativeSource.jniImageStabJobProgress(job)
            Log.i(tag, "analyze threads=$threads cancelAfter=$cancelAfterMs rc=$rc progress=${p?.toList()}")
            return rc
        } finally {
            pfd.close()
            NativeSource.jniImageStabJobFree(job)
        }
    }

    @Test
    fun analyze_resume_load_resolve_and_cpu_warp() {
        val clip = fixture()
        val dir = outDir()

        // 1. two-thread analysis, timed
        val two = File(dir, "two.ristab")
        val t0 = System.nanoTime()
        assertEquals(0, analyze(clip, two, threads = 2))
        val twoMs = (System.nanoTime() - t0) / 1e6
        assertTrue(two.isFile)
        assertTrue(!File(two.path + ".part").exists())

        // 2. one thread: bit-identical file, and its time (the 2-thread win)
        val one = File(dir, "one.ristab")
        val t1 = System.nanoTime()
        assertEquals(0, analyze(clip, one, threads = 1))
        val oneMs = (System.nanoTime() - t1) / 1e6
        assertTrue("1-thread and 2-thread analyses differ", one.readBytes().contentEquals(two.readBytes()))
        Log.i(tag, "TIMING 2-thread=${"%.0f".format(twoMs)} ms, 1-thread=${"%.0f".format(oneMs)} ms, bytes=${two.length()}")

        // 3. cancel mid-run keeps a checkpoint; the resumed run equals the full run
        val res = File(dir, "resume.ristab")
        val rc = analyze(clip, res, threads = 2, cancelAfterMs = (twoMs / 3).toLong())
        assertEquals("cancel must report Cancelled", 1, rc)
        assertTrue("no final file after a cancel", !res.exists())
        assertTrue("checkpoint kept", File(res.path + ".part").isFile)
        assertEquals(0, analyze(clip, res, threads = 2))
        assertTrue("resumed run differs from the full run", res.readBytes().contentEquals(two.readBytes()))
        assertTrue(!File(res.path + ".part").exists())

        // 4. load into a clip handle + resolve
        val pfd = ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY)
        val open = NativeSource.jniPreviewOpen(pfd.fd)
        assertNotNull(open)
        val handle = open!![0].toLong()
        val frames = open[1]
        try {
            assertEquals("not-this-clip / missing", 2, NativeSource.jniImageStabLoad(handle, File(dir, "nope").path))
            assertEquals(0, NativeSource.jniImageStabLoad(handle, two.absolutePath))
            var active = 0
            var nonFinite = 0
            var maxScale = 1.0f
            for (i in 0 until frames) {
                val v = NativeSource.jniImageStabWarpResolve(handle, 0, 0.5f, 1.15f, 0, -1, i, 0)
                assertNotNull("frame $i", v)
                assertEquals(10, v!!.size)
                if (v.any { !it.isFinite() }) nonFinite++
                if (v[0] != 0f) active++
                maxScale = maxOf(maxScale, 1f / v[1].coerceAtLeast(1e-3f))
            }
            Log.i(tag, "resolve: frames=$frames active=$active nonFinite=$nonFinite maxScale~$maxScale")
            assertEquals(0, nonFinite)
            // preview tier answers too (and the same plan: cache hit)
            val prev = NativeSource.jniImageStabWarpResolve(handle, 0, 0.5f, 1.15f, 0, -1, frames / 2, 1280)
            assertNotNull(prev)
            // out of range / bad handle: no verdict
            assertEquals(null, NativeSource.jniImageStabWarpResolve(handle, 0, 0.5f, 1.15f, 0, -1, frames + 5, 0))
            assertEquals(null, NativeSource.jniImageStabWarpResolve(0L, 0, 0.5f, 1.15f, 0, -1, 0, 0))
            val probe = NativeSource.jniImageStabProbe(handle, 0, 0.5f, 1.15f, 0, -1)
            assertNotNull(probe)
            Log.i(tag, "probe: ${probe!!.joinToString { "%.4f".format(it) }}")
            assertEquals(frames.toDouble(), probe[0], 0.0)
            assertEquals("no invalid sample frames", 0.0, probe[6], 0.0)

            // 5. the CPU reference path through the image argument
            val grade = GradeK(stabilization = true, stabilizationEngine = 1, stabilizationStrength = 100f, stabilizationZoomLimit = 1.15f)
            val mid = frames / 2
            fun render(path: String?): ByteArray? = NativeSource.jniExportFrameParcel(
                handle, mid, GradeParcel.encode(grade), null, 1f, path,
                0, if (path != null) 1f else 0f, 1.15f, 0f, 0f, -1, -1, -1, -1, -1,
            )
            val base = render(null)
            val warped = render(WarpResolver.imageArg())
            assertNotNull(base)
            assertNotNull(warped)
            assertEquals(base!!.size, warped!!.size)
            val verdict = NativeSource.jniImageStabWarpResolve(handle, 0, 1f, 1.15f, 0, -1, mid, 0)!!
            val differs = !base.contentEquals(warped)
            Log.i(tag, "cpu warp: frame=$mid resolverActive=${verdict[0]} outputDiffers=$differs")
            assertEquals("CPU output must differ from the baseline exactly when the resolver says active",
                verdict[0] != 0f, differs)
        } finally {
            NativeSource.jniCloseSource(handle)
            pfd.close()
        }
    }
}
