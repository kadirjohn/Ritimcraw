package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NativeSource
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
 * DNG export tests. The pure pieces (DngExportErrors, ExportStaging) and the
 * export flow around a FAKE renderer are JVM-testable without
 * librawedit_jni.so — the real native call is device-gated. In JVM tests
 * NativeSource.nativeAvailable is false, so the unavailable-engine fallback
 * is exercised by the real-boundary test too.
 */
class DngExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- DngExportErrors mapping ----

    @Test
    fun message_names_stage_cause_recovery() {
        val m = DngExportErrors.message(
            DngExportErrors.CODE_TRUNCATED, DngExportErrors.STAGE_FRAME_READ
        )
        assertTrue(m.startsWith("DNG export failed (stage: frame read): truncated clip"))
        assertTrue(m.contains("recovery: re-import the clip"))
    }

    @Test
    fun message_appends_native_detail() {
        val m = DngExportErrors.message(
            DngExportErrors.CODE_IO, DngExportErrors.STAGE_DNG_WRITE, "dng write: ioError — disk full"
        )
        assertTrue(m.endsWith("[dng write: ioError — disk full]"))
    }

    @Test
    fun every_stage_token_has_a_name() {
        assertEquals("open", DngExportErrors.stageName(DngExportErrors.STAGE_OPEN))
        assertEquals("frame read", DngExportErrors.stageName(DngExportErrors.STAGE_FRAME_READ))
        assertEquals("dng write", DngExportErrors.stageName(DngExportErrors.STAGE_DNG_WRITE))
        assertEquals("unknown", DngExportErrors.stageName(99))
    }

    @Test
    fun every_code_has_cause_and_recovery() {
        for (code in 0..4) {
            val (cause, recovery) = DngExportErrors.causeAndRecovery(code)
            assertTrue(cause.isNotBlank())
            assertTrue(recovery.isNotBlank())
        }
        // Unknown code still names a recovery, never a bare "failed".
        val (cause, recovery) = DngExportErrors.causeAndRecovery(42)
        assertTrue(cause.isNotBlank())
        assertTrue(recovery.isNotBlank())
    }

    @Test
    fun io_recovery_mentions_free_space() {
        val (_, recovery) = DngExportErrors.causeAndRecovery(DngExportErrors.CODE_IO)
        assertTrue(recovery.contains("free space"))
    }

    // ---- ExportStaging ----

    @Test
    fun partial_lives_beside_out() {
        val out = tmp.newFile("clip.dng")
        val partial = ExportStaging.partial(out)
        assertEquals(out.parentFile, partial.parentFile)
        assertEquals("clip.dng.partial", partial.name)
    }

    @Test
    fun finalize_renames_partial_into_out() {
        val out = File(tmp.root, "clip.dng")
        val partial = ExportStaging.partial(out)
        partial.writeText("bytes")
        assertTrue(ExportStaging.finalize(partial, out))
        assertTrue(out.exists())
        assertFalse(partial.exists())
        assertEquals("bytes", out.readText())
    }

    @Test
    fun finalize_replaces_existing_out() {
        val out = File(tmp.root, "clip.dng")
        out.writeText("stale")
        val partial = ExportStaging.partial(out)
        partial.writeText("fresh")
        assertTrue(ExportStaging.finalize(partial, out))
        assertEquals("fresh", out.readText())
    }

    @Test
    fun finalize_fails_without_partial() {
        val out = File(tmp.root, "clip.dng")
        assertFalse(ExportStaging.finalize(File(tmp.root, "missing.partial"), out))
        assertFalse(out.exists())
    }

    @Test
    fun discard_removes_partial() {
        val out = File(tmp.root, "clip.dng")
        val partial = ExportStaging.partial(out)
        partial.writeText("half")
        ExportStaging.discard(partial)
        assertFalse(partial.exists())
    }

    @Test
    fun clean_requires_both_absent() {
        val out = File(tmp.root, "clip.dng")
        assertTrue(ExportStaging.clean(out))
        ExportStaging.partial(out).writeText("x")
        assertFalse(ExportStaging.clean(out))
        ExportStaging.discard(ExportStaging.partial(out))
        out.writeText("x")
        assertFalse(ExportStaging.clean(out))
    }

    // ---- export flow with a fake renderer ----

    /** Fake native boundary: writes the partial on success, or reports a canned error. */
    private class FakeDngRender(
        private val behavior: Behavior,
    ) : DngRender {
        enum class Behavior { OK, NATIVE_FAIL, THROW }

        var lastOutPath: String? = null
        var lastFrameIndex: Int = -1
        var lastManualRotationDeg: Int = Int.MIN_VALUE

        override fun render(fd: Int, frameIndex: Int, manualRotationDeg: Int, outPath: String): Boolean {
            lastOutPath = outPath
            lastFrameIndex = frameIndex
            lastManualRotationDeg = manualRotationDeg
            return when (behavior) {
                Behavior.OK -> {
                    File(outPath).writeText("dng-bytes")
                    true
                }
                Behavior.NATIVE_FAIL -> false
                Behavior.THROW -> throw IllegalStateException("decoder blew up")
            }
        }

        override fun code(): Int = DngExportErrors.CODE_TRUNCATED
        override fun stage(): Int = DngExportErrors.STAGE_FRAME_READ
        override fun detail(): String? = "frame read: truncated — short file"
    }

    private fun engine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-export").apply { isDaemon = true }
        }

    private fun job(): ExportJob = ExportJob(
        id = "j1", sourceId = "clip-1", snapshot = GradeK(),
        target = Target.DNG, state = State.QUEUED,
    )

    @Test
    fun success_renames_partial_and_marks_done() {
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            val fake = FakeDngRender(FakeDngRender.Behavior.OK)
            val exporter = DngExporter(render = fake, engine = e)
            val result = runBlocking { exporter.export(job(), 7, out, frameIndex = 3) }
            assertEquals(State.DONE, result.state)
            assertEquals("clip-1.dng", result.detail)
            assertTrue(out.exists())
            assertEquals("dng-bytes", out.readText())
            assertFalse(ExportStaging.partial(out).exists())
            // The renderer saw the partial path, never the final name, and
            // the requested frame index.
            assertEquals(out.absolutePath + ".partial", fake.lastOutPath)
            assertEquals(3, fake.lastFrameIndex)
            // Manual rotation (§5): the default-0 job (a never-rotated clip)
            // passes 0 through to the native boundary.
            assertEquals(0, fake.lastManualRotationDeg)
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun rotated_job_passes_manual_delta_to_the_native_boundary() {
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            val fake = FakeDngRender(FakeDngRender.Behavior.OK)
            val exporter = DngExporter(render = fake, engine = e)
            val rotated = job().copy(manualRotationDeg = 270)
            val result = runBlocking { exporter.export(rotated, 7, out) }
            assertEquals(State.DONE, result.state)
            // §5: the job's snapshot delta reaches the bridge untouched;
            // the bridge adds it to the container orientation before the
            // write so the DNG Orientation tag carries the effective value.
            assertEquals(270, fake.lastManualRotationDeg)
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun native_failure_maps_code_stage_detail_and_cleans_up() {
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            // A leftover partial from a previous attempt must not survive a
            // failed export either.
            ExportStaging.partial(out).writeText("stale")
            val exporter = DngExporter(render = FakeDngRender(FakeDngRender.Behavior.NATIVE_FAIL), engine = e)
            val result = runBlocking { exporter.export(job(), 7, out) }
            assertEquals(State.FAILED, result.state)
            val detail = result.detail!!
            assertTrue(detail.startsWith("DNG export failed (stage: frame read): truncated clip"))
            assertTrue(detail.contains("[frame read: truncated — short file]"))
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun renderer_exception_cleans_up_and_names_cause() {
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            val exporter = DngExporter(render = FakeDngRender(FakeDngRender.Behavior.THROW), engine = e)
            val result = runBlocking { exporter.export(job(), 7, out) }
            assertEquals(State.FAILED, result.state)
            assertEquals("decoder blew up", result.detail)
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun finalize_failure_discards_partial_and_leaves_no_file() {
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            // Make the final name undeletable + unrenamable: a non-empty
            // directory. finalize() deletes out first, which fails on a
            // non-empty dir, so the rename cannot land.
            out.mkdirs()
            File(out, "child").writeText("x")
            val exporter = DngExporter(
                render = object : DngRender {
                    override fun render(fd: Int, frameIndex: Int, manualRotationDeg: Int, outPath: String): Boolean {
                        File(outPath).writeText("dng")
                        return true
                    }
                    override fun code(): Int = 0
                    override fun stage(): Int = 0
                    override fun detail(): String? = null
                },
                engine = e,
            )
            val result = runBlocking { exporter.export(job(), 7, out) }
            assertEquals(State.FAILED, result.state)
            assertTrue(result.detail!!.contains("finalize"))
            // The partial was discarded even though the render succeeded.
            assertFalse(File(out.absolutePath + ".partial").exists())
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun unavailable_engine_fails_cleanly_with_recovery() {
        // JVM tests run with NativeSource.nativeAvailable == false, so the
        // real boundary is never touched: the export fails with the
        // unavailable message, never throws, and leaves no file.
        val e = engine()
        try {
            val out = File(tmp.root, "clip-1.dng")
            val exporter = DngExporter(engine = e)
            val result = runBlocking { exporter.export(job(), 7, out) }
            assertEquals(State.FAILED, result.state)
            assertEquals(NativeSource.nativeUnavailableMessage, result.detail)
            assertTrue(ExportStaging.clean(out))
        } finally {
            e.shutdown()
        }
    }
}
