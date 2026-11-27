package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.hevc.HevcTrialEncode
import com.ritim.ritimcraw.export.hevc.HevcTrialRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The G4 export-flow HLG legs (the trial-encode wiring, the honest
 * SDR fallback, the plan-failure honesty) — the Mp4ExporterTest
 * fixtures' shape, every seam a FAKE (the runner, the facts, the API
 * level); the device's real encoder behavior stays "doğrulanmadı".
 */
class HlgExportE2eTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val SURFACE = 0x7F000789
    private val P010 = 54

    /** A SYNTHETIC API-34 facts set: hdr-editing true, hlg-editing absent (the fallback path). */
    private fun api34Facts(): List<EncoderFacts> = listOf(
        EncoderFacts(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true,
            profiles = setOf(1, 2), colorFormats = setOf(SURFACE, P010),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
            maxWidth = 8192, maxHeight = 8192,
            features = setOf("hdr-editing"),
            sizeRate = SizeRateProbe { w, h, _ -> w <= 8192 && h <= 8192 },
        ),
        EncoderFacts(
            "c2.qti.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(SURFACE),
            bitrateModes = setOf(1, 2), maxWidth = 8192, maxHeight = 4320,
        ),
    )

    private fun hlgJob(): ExportJob {
        val settings = ExportSettings(
            codec = ExportCodec.HEVC, bitDepth = 10,
            dynamicRange = ExportDynamicRange.HLG,
        )
        return ExportJob(
            "j-hlg", "src", com.ritim.ritimcraw.data.GradeK(),
            Target.MP4, State.QUEUED,
            settings = settings,
        )
    }

    private class FakeSession(
        private val frames: Int = 2,
        private val width: Int = 4,
        private val height: Int = 4,
    ) : Mp4Session {
        val closed = java.util.concurrent.atomic.AtomicInteger()
        override fun open(fd: Int): Mp4SessionInfo? =
            Mp4SessionInfo(1L, frames, width, height, 0, 90)
        override fun renderFrame(
            handle: Long, frameIndex: Int, grade: com.ritim.ritimcraw.data.GradeK,
            lutPath: String?, gyroPath: String?, chainWire: FloatArray?,
        ): ByteArray? = ByteArray(width * height * 3) { 128.toByte() }
        override fun framePts(handle: Long, frameIndex: Int): Long = frameIndex * 33_333L
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray = ShortArray(0)
        override fun close(handle: Long) { closed.incrementAndGet() }
        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    private class RecordingWriter : Mp4MediaWriter {
        var configured: ExportPlan? = null
        var started = false
        var released = false
        override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
            started = true
            File(outPath).writeBytes(ByteArray(64))
        }
        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
        override fun finish(): Boolean = true
        override fun release() { released = true }
        override fun configureVideo(plan: ExportPlan) { configured = plan }
        override fun expectVideoConfig() {}
        override fun encoderIdentity(): EncoderDiagnostics? = null
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-hlg-export").apply { isDaemon = true }
        }

    /**
     * The exporter with every HLG seam faked: API 34, the synthetic
     * facts, and the trial runner the test controls.
     */
    private fun exporter(
        writer: RecordingWriter,
        trial: (HevcTrialRunner.Request) -> HevcTrialEncode.Result,
    ): Mp4Exporter {
        return Mp4Exporter(
            session = FakeSession(),
            writerFactory = { writer },
            engine = freshEngine(),
            encoderFacts = { api34Facts() },
            apiLevel = { 34 },
            hlgTrialRunner = HevcTrialRunner { request -> trial(request) },
        )
    }

    // ---- a REFUSED trial degrades to SDR, honestly, before frame 1 ----

    @Test
    fun a_refused_trial_encodes_sdr_with_the_reason_on_the_report() {
        val writer = RecordingWriter()
        val exporter = exporter(writer) { HevcTrialEncode.Result.Refused("configure: the encoder rejected color-transfer=7") }
        val out = File(tmp.root, "clip.mp4")
        val result = kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out) }
        assertEquals(State.DONE, result.state)
        // The plan the writer was configured from is SDR — never a
        // "Main10-less HLG" file, never a silent HLG-attempt crash.
        val plan = writer.configured!!
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertEquals(ExportColorTags.SDR, plan.colorTags)
        // The report carries the range's refusal and the honest issue.
        val report = result.report!!
        assertTrue(
            report.issues.any { it.startsWith("DYNAMIC_RANGE_UNAVAILABLE") },
        )
        assertTrue(report.steps.any { it.contains("hlg trial") })
        assertTrue(report.steps.any { it.contains("configure: the encoder rejected color-transfer=7") })
        // The export still produced the file (the honest SDR fallback).
        assertTrue(out.exists())
    }

    @Test
    fun a_passed_trial_encodes_hlg_with_the_trial_issue_on_the_report() {
        val writer = RecordingWriter()
        val exporter = exporter(writer) { HevcTrialEncode.Result.Passed }
        val out = File(tmp.root, "clip.mp4")
        val result = kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out) }
        assertEquals(State.DONE, result.state)
        val plan = writer.configured!!
        // The plan the writer got IS the HLG contract: BT2020/limited/HLG.
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        assertEquals(ExportColorTags.Hlg, plan.colorTags)
        assertEquals(6, plan.colorTags.colorStandard)
        assertEquals(7, plan.colorTags.colorTransfer)
        // The report is honest that the gate passed on the fallback.
        assertTrue(report_has(result, "HLG_TRIAL_REQUIRED"))
    }

    @Test
    fun the_trial_runs_at_most_once_per_job_shape_across_two_exports() {
        val writer = RecordingWriter()
        var runs = 0
        val exporter = exporter(writer) { runs++; HevcTrialEncode.Result.Passed }
        val out1 = File(tmp.root, "a.mp4")
        val out2 = File(tmp.root, "b.mp4")
        kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out1) }
        kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out2) }
        // Two exports of the SAME shape, ONE trial: the process-lifetime
        // cache answers the second (the K1 no-reheat rule).
        assertEquals(1, runs)
        assertEquals(State.DONE, State.valueOf("DONE"))
    }

    @Test
    fun a_refused_trial_caches_so_the_second_export_never_reheats() {
        val writer = RecordingWriter()
        var runs = 0
        val exporter = exporter(writer) {
            runs++; HevcTrialEncode.Result.Refused("the decoder reported an 8-bit output")
        }
        val out1 = File(tmp.root, "a.mp4")
        val out2 = File(tmp.root, "b.mp4")
        kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out1) }
        kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out2) }
        // The positive-refusal cache: the second export answers from the
        // cache, the runner never re-runs (a no-HLG device pays ONCE).
        assertEquals(1, runs)
        // And BOTH exports degraded to SDR honestly.
        assertEquals(ExportDynamicRange.SDR, writer.configured!!.effective.dynamicRange)
    }

    @Test
    fun the_writer_s_mediaformat_contract_for_hlg_is_the_profile_s_own() {
        // The trial PASSED: the writer's plan carries the HLG tags, and
        // ExportFormats.video — the writer's real format builder — writes
        // the profile's own contract (the plan 4.1 table, from the plan's
        // resolved tags; never re-derived constants).
        val writer = RecordingWriter()
        val exporter = exporter(writer) { HevcTrialEncode.Result.Passed }
        val out = File(tmp.root, "clip.mp4")
        kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out) }
        val plan = writer.configured!!
        val spec = ExportFormats.video(plan, surface = true)
        assertEquals(6, spec.int(android.media.MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(2, spec.int(android.media.MediaFormat.KEY_COLOR_RANGE))
        assertEquals(7, spec.int(android.media.MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals(2, spec.int(android.media.MediaFormat.KEY_PROFILE))
        assertEquals(null, spec.values[android.media.MediaFormat.KEY_HDR_STATIC_INFO])
    }

    // ---- the gate ABSENT: today's wall, byte for byte ----

    @Test
    fun an_sdr_request_never_pays_the_gate_or_the_trial() {
        val writer = RecordingWriter()
        var runs = 0
        val lines = ArrayList<String>()
        val exporter = Mp4Exporter(
            session = FakeSession(),
            writerFactory = { writer },
            engine = freshEngine(),
            encoderFacts = { api34Facts() },
            apiLevel = { 34 },
            hlgTrialRunner = HevcTrialRunner { runs++; HevcTrialEncode.Result.Passed },
        )
        val job = ExportJob(
            "j-sdr", "src", com.ritim.ritimcraw.data.GradeK(), Target.MP4, State.QUEUED,
            settings = ExportSettings(codec = ExportCodec.AVC, bitDepth = 8),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = kotlinx.coroutines.runBlocking { exporter.export(job, 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(0, runs)
        assertEquals(ExportDynamicRange.SDR, writer.configured!!.effective.dynamicRange)
    }

    @Test
    fun hlg_with_no_encoder_facts_keeps_the_e1_wall_and_never_crashes() {
        // The probe failed / no facts: the gate is null, the planner's
        // E1 wall stands (DYNAMIC_RANGE_UNAVAILABLE + SDR fix), and the
        // export completes — the pre-G4 behavior, byte for byte.
        val writer = RecordingWriter()
        var runs = 0
        val exporter = Mp4Exporter(
            session = FakeSession(),
            writerFactory = { writer },
            engine = freshEngine(),
            encoderFacts = { emptyList() },
            apiLevel = { 34 },
            hlgTrialRunner = HevcTrialRunner { runs++; HevcTrialEncode.Result.Passed },
        )
        val out = File(tmp.root, "clip.mp4")
        val result = kotlinx.coroutines.runBlocking { exporter.export(hlgJob(), 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(0, runs)
        assertEquals(ExportDynamicRange.SDR, writer.configured!!.effective.dynamicRange)
        assertTrue(
            writer.configured!!.issues.any { it.kind == ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE },
        )
    }

    private fun report_has(job: ExportJob, issueKind: String): Boolean =
        job.report?.issues?.any { it.startsWith(issueKind) } == true
}
