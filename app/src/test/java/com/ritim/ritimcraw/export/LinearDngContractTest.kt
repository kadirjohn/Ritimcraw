package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RAW-13 Linear DNG contract tests (B6 audit, 2026-11-16). These pin the
 * KOTLIN-VISIBLE contract of the existing writer — the shapes the JVM can
 * actually observe around the native boundary:
 *   - the LINEAR target takes the renderLinear seam and never the CFA
 *     render, with the exact argument pass-through (fd, frame index,
 *     effective-rotation snapshot, RAW-09 crop fractions, PARTIAL path);
 *   - the linear-only develop stage token in the error vocabulary
 *     (stage 2 exists only on this path — jni_bridge.cpp kStageDevelop);
 *   - the linear arm's failure/cleanup contract (native failure maps
 *     code+stage+detail and leaves no file; the develop-stage failure
 *     names "develop");
 *   - the queue-side contract (Target.LINEAR rides the DNG arm and lands
 *     as <sourceId>-linear.dng — pinned here at the pure-name level; the
 *     full queue dispatch is pinned by ExportQueueTest).
 *
 * SEAM HONESTY — what these tests do NOT cover. The DNG bytes themselves
 * are written natively (writeLinearDng in core/src/DngWriter.cpp,
 * called from jni_bridge.cpp nativeLinearDngExport through
 * NativeSource.jniLinearDngExport); the JNI seam requires
 * librawedit_jni.so and cannot be driven with synthetic inputs in a JVM
 * test (NativeSource.nativeAvailable is false here — see
 * DngExporterTest.unavailable_engine_fails_cleanly_with_recovery). The
 * native byte contract — PhotometricInterpretation 34892, 3x16-bit
 * samples, the kLinearDngStages ImageDescription, BlackLevel 0 /
 * WhiteLevel 65535, ColorMatrix1 kept, AsShotNeutral (1,1,1), ActiveArea
 * and the verbatim pixel round-trip — is pinned by the CORE tests
 * (core/tests/test_dng.cpp linear_dng_roundtrip_tags /
 * linear_dng_rejects_bad_input, doctest, run by tools/build-desktop.sh).
 * It is cited, not faked, here; the full audit of both layers is
 * docs/LINEAR_DNG_GAPS.md.
 */
class LinearDngContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records the renderLinear call and answers a scripted result. */
    private class RecordingLinearRender(
        private val result: Boolean,
        private val failCode: Int = DngExportErrors.CODE_UNSUPPORTED,
        private val failStage: Int = DngExportErrors.STAGE_DEVELOP,
        private val failDetail: String? =
            "linear develop: empty frame (recovery: reopen the clip)",
    ) : DngRender {
        var renderCalls = 0
        var renderLinearCalls = 0
        var lastFd = -1
        var lastFrameIndex = -1
        var lastManualRotationDeg = Int.MIN_VALUE
        var lastCrop: FloatArray? = null
        var lastOutPath: String? = null

        override fun render(
            fd: Int, frameIndex: Int, manualRotationDeg: Int,
            cropX: Float, cropY: Float, cropW: Float, cropH: Float,
            outPath: String,
        ): Boolean {
            renderCalls++
            return false
        }

        override fun renderLinear(
            fd: Int, frameIndex: Int, manualRotationDeg: Int,
            cropX: Float, cropY: Float, cropW: Float, cropH: Float,
            outPath: String,
        ): Boolean {
            renderLinearCalls++
            lastFd = fd
            lastFrameIndex = frameIndex
            lastManualRotationDeg = manualRotationDeg
            lastCrop = floatArrayOf(cropX, cropY, cropW, cropH)
            lastOutPath = outPath
            if (result) File(outPath).writeText("linear-dng-bytes")
            return result
        }

        override fun code(): Int = failCode
        override fun stage(): Int = failStage
        override fun detail(): String? = failDetail
    }

    private fun engine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "linear-contract-test").apply { isDaemon = true }
        }

    private fun linearJob(
        snapshot: GradeK = GradeK(),
        manualRotationDeg: Int = 0,
    ): ExportJob = ExportJob(
        id = "j1", sourceId = "clip-1", snapshot = snapshot,
        target = Target.LINEAR, state = State.QUEUED,
        manualRotationDeg = manualRotationDeg,
    )

    // ---- the linear seam: arguments and routing ----

    @Test
    fun linear_export_passes_every_job_shape_to_the_render_linear_seam() {
        // The Kotlin-visible contract of renderLinear (DngExporter.kt):
        // fd, the job's frame index, the manual-rotation snapshot, the
        // snapshot's RAW-09 crop fractions and the PARTIAL path — all
        // untouched (the bridge owns the effective-space -> source-CFA
        // mapping and the crop-before-develop order).
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1-linear.dng")
            val fake = RecordingLinearRender(result = true)
            val exporter = DngExporter(render = fake, engine = e)
            val job = linearJob(
                snapshot = GradeK(cropX = 0.25f, cropY = 0.1f, cropW = 0.5f, cropH = 0.8f),
                manualRotationDeg = 270,
            ).copy(frameIndex = 5)
            val result = runBlocking { exporter.export(job, 42, out, frameIndex = 5) }
            assertEquals(State.DONE, result.state)
            assertEquals(1, fake.renderLinearCalls)
            assertEquals(0, fake.renderCalls)
            assertEquals(42, fake.lastFd)
            assertEquals(5, fake.lastFrameIndex)
            assertEquals(270, fake.lastManualRotationDeg)
            val crop = fake.lastCrop!!
            assertEquals(0.25f, crop[0])
            assertEquals(0.1f, crop[1])
            assertEquals(0.5f, crop[2])
            assertEquals(0.8f, crop[3])
            // The seam sees the staging path, never the final name.
            assertEquals(out.absolutePath + ".partial", fake.lastOutPath)
            // Finalize made the file visible at the final name only.
            assertEquals("linear-dng-bytes", out.readText())
            assertFalse(ExportStaging.partial(out).exists())
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun linear_export_never_calls_the_cfa_render_even_on_failure() {
        // The honesty invariant of the two-file-contract clause (RAW-13
        // "CFA DNG ve Linear DNG ayrı dosya sözleşmeleridir"): a LINEAR job
        // must not fall back to the CFA render on any path — a failed
        // linear export is FAILED, never silently a different file.
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1-linear.dng")
            val fake = RecordingLinearRender(result = false)
            val exporter = DngExporter(render = fake, engine = e)
            val result = runBlocking { exporter.export(linearJob(), 7, out) }
            assertEquals(State.FAILED, result.state)
            assertEquals(1, fake.renderLinearCalls)
            assertEquals(0, fake.renderCalls)
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }

    // ---- the linear-only develop stage in the error vocabulary ----

    @Test
    fun develop_stage_token_names_the_linear_only_stage() {
        // jni_bridge.cpp kStageDevelop (2) exists only on the linear path
        // (the CFA export never develops); the Kotlin vocabulary must keep
        // naming it so a linear develop failure is user-readable.
        assertEquals("develop", DngExportErrors.stageName(DngExportErrors.STAGE_DEVELOP))
        // The stage tokens are the bridge's exact set — 0..3, nothing else
        // has a name (an out-of-range token is "unknown", never a wrong
        // stage name).
        assertEquals("open", DngExportErrors.stageName(DngExportErrors.STAGE_OPEN))
        assertEquals("frame read", DngExportErrors.stageName(DngExportErrors.STAGE_FRAME_READ))
        assertEquals("dng write", DngExportErrors.stageName(DngExportErrors.STAGE_DNG_WRITE))
        assertEquals("unknown", DngExportErrors.stageName(4))
    }

    @Test
    fun linear_develop_failure_maps_the_native_develop_stage_message() {
        // The bridge's linear develop failure is Unsupported at stage 2
        // ("linear develop: empty frame") — the exporter must surface the
        // stage, the cause and the recovery, and clean the staging file.
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1-linear.dng")
            // A stale partial from a previous attempt must not survive.
            ExportStaging.partial(out).writeText("stale")
            val fake = RecordingLinearRender(
                result = false,
                failCode = DngExportErrors.CODE_UNSUPPORTED,
                failStage = DngExportErrors.STAGE_DEVELOP,
                failDetail = "linear develop: empty frame (recovery: reopen the clip)",
            )
            val exporter = DngExporter(render = fake, engine = e)
            val result = runBlocking { exporter.export(linearJob(), 7, out) }
            assertEquals(State.FAILED, result.state)
            val detail = result.detail!!
            // Stage + cause + recovery + native detail — never a bare failed.
            assertTrue(detail.startsWith("DNG export failed (stage: develop): unsupported clip"))
            assertTrue(detail.contains("recovery: re-import the clip"))
            assertTrue(detail.contains("[linear develop: empty frame (recovery: reopen the clip)]"))
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }

    // ---- the linear arm's shared staging contract ----

    @Test
    fun linear_success_is_visible_only_after_finalize() {
        // The staged linear file exists at the partial path during the
        // render and at the final name after it — the same finalize-only
        // visibility contract as the CFA arm (QA-15's "successful file is
        // visible only after finalize" applied to the linear target).
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1-linear.dng")
            val fake = RecordingLinearRender(result = true)
            val exporter = DngExporter(render = fake, engine = e)
            val result = runBlocking { exporter.export(linearJob(), 7, out) }
            assertEquals(State.DONE, result.state)
            assertEquals(out.name, result.detail)
            assertTrue(out.exists())
            assertFalse(ExportStaging.partial(out).exists())
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun linear_renderer_exception_cleans_up_and_names_the_cause() {
        // The linear arm runs inside the same try/catch: a throwing seam
        // (the native-unavailable IllegalStateException is the real case)
        // is a FAILED job with the cause as the detail, no file left.
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1-linear.dng")
            val throwing = object : DngRender {
                override fun render(
                    fd: Int, frameIndex: Int, manualRotationDeg: Int,
                    cropX: Float, cropY: Float, cropW: Float, cropH: Float,
                    outPath: String,
                ): Boolean = error("cfa render must not be called")

                override fun renderLinear(
                    fd: Int, frameIndex: Int, manualRotationDeg: Int,
                    cropX: Float, cropY: Float, cropW: Float, cropH: Float,
                    outPath: String,
                ): Boolean = throw IllegalStateException("linear decoder blew up")

                override fun code(): Int = 0
                override fun stage(): Int = 0
                override fun detail(): String? = null
            }
            val exporter = DngExporter(render = throwing, engine = e)
            val result = runBlocking { exporter.export(linearJob(), 7, out) }
            assertEquals(State.FAILED, result.state)
            assertEquals("linear decoder blew up", result.detail)
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }

    // ---- queue-side linear contract (pure decisions) ----

    @Test
    fun linear_output_name_is_target_suffixed_dng() {
        // ExportQueue.outputName: a Linear DNG is still a .dng file — the
        // target name ("clip1-linear") disambiguates the CFA file
        // ("clip1-dng"). RAW-13's separate-file-contract clause at the
        // naming level: the two targets never collide.
        assertEquals("clip1-linear.dng", ExportQueue.outputName("clip1", Target.LINEAR))
        assertEquals("clip1-dng.dng", ExportQueue.outputName("clip1", Target.DNG))
        assertFalse(
            ExportQueue.outputName("clip1", Target.LINEAR) ==
                ExportQueue.outputName("clip1", Target.DNG)
        )
    }

    @Test
    fun linear_target_is_a_distinct_target_from_cfa_dng() {
        // The enum-level contract behind the routing: Target.LINEAR is
        // not Target.DNG (the exporter branches on exactly this).
        assertFalse(Target.LINEAR == Target.DNG)
        assertEquals(4, Target.values().size)
        assertTrue(Target.values().contains(Target.LINEAR))
    }
}
