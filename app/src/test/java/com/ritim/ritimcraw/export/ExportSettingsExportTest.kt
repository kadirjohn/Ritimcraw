package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/** The settings path of the MP4 exporter: plan -> writer, the single plan-fix retry, the report (E1b). */
class ExportSettingsExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Session(private val frames: Int = 4) : Mp4Session {
        override fun open(fd: Int) = Mp4SessionInfo(1L, frames, 4, 4, 0, 0)
        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?, gyroPath: String?, chainWire: FloatArray?) =
            ByteArray(4 * 4 * 3) { 100 }
        override fun framePts(handle: Long, frameIndex: Int) = frameIndex * 41_708L // 23.976 fps
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long) = ShortArray(0)
        override fun close(handle: Long) {}
        override fun code() = 0
        override fun stage() = 0
        override fun detail(): String? = null
    }

    /** Records what the pipeline configures; rejects the first [rejects] starts with a typed refusal. */
    private class Writer(private val log: MutableList<Writer>, private val rejects: () -> Boolean) : Mp4MediaWriter {
        var plan: ExportPlan? = null
        var expected = false
        var started = false
        var released = false
        var out: String? = null
        override fun configureVideo(plan: ExportPlan) { this.plan = plan }
        override fun expectVideoConfig() { expected = true }
        override fun encoderIdentity() = EncoderDiagnostics(plan?.encoderName ?: "x", "x", true, false)
        override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
            log += this
            if (plan != null && rejects()) throw VideoConfigRejected("MP4 export failed (stage: configure): ${plan!!.mime} rejected the format")
            started = true; out = outPath
        }
        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
        override fun finish(): Boolean { File(out!!).writeBytes(ByteArray(1000)); return true }
        override fun release() { released = true }
    }

    private val facts = listOf(
        EncoderFacts("c2.a.hevc", "video/hevc", true, profiles = setOf(1, 2), bitrateModes = setOf(1, 2), maxWidth = 8192, maxHeight = 8192),
        EncoderFacts("c2.b.hevc", "video/hevc", true, profiles = setOf(1, 2), bitrateModes = setOf(1, 2), maxWidth = 8192, maxHeight = 8192),
        EncoderFacts("c2.a.avc", "video/avc", true, profiles = setOf(1, 2, 8), bitrateModes = setOf(1, 2), maxWidth = 8192, maxHeight = 8192),
    )

    private fun job(s: ExportSettings?) = ExportJob("j", "src", GradeK(), Target.MP4, State.QUEUED, settings = s)

    private fun exporter(writers: MutableList<Writer>, rejects: () -> Boolean, lines: MutableList<String> = ArrayList()) = Mp4Exporter(
        session = Session(),
        writerFactory = { Writer(writers, rejects) },
        engine = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } },
        encoderFacts = { facts },
        planLog = { lines += it },
    )

    @Test
    fun a_settings_job_configures_the_writer_from_the_plan_with_the_clips_fps() {
        val writers = ArrayList<Writer>()
        val out = File(tmp.root, "a.mp4")
        val r = runBlocking { exporter(writers, { false }).export(job(ExportSettings(codec = ExportCodec.HEVC)), 7, out) }
        assertEquals(State.DONE, r.state)
        val w = writers.single()
        assertTrue(w.expected)
        assertEquals("video/hevc", w.plan!!.mime)
        assertEquals("c2.a.hevc", w.plan!!.encoderName)
        assertEquals(23.976, w.plan!!.fps, 0.0)
        // the report: effective plan + the real bitrate from file size / duration
        val rep = r.report!!
        assertEquals("c2.a.hevc", rep.encoder)
        assertEquals(1000L, rep.fileBytes)
        assertTrue(rep.actualTotalBps!! > 0)
        assertEquals(w.plan!!.bitrateBps, rep.requestedBps)
        assertTrue(rep.steps.any { it.startsWith("plan:") })
    }

    @Test
    fun a_pre_e1_job_never_gets_a_plan_or_a_report() {
        val writers = ArrayList<Writer>()
        val out = File(tmp.root, "b.mp4")
        val r = runBlocking { exporter(writers, { false }).export(job(null), 7, out) }
        assertEquals(State.DONE, r.state)
        assertNull(writers.single().plan)
        assertFalse(writers.single().expected)
        assertNull(r.report)
    }

    @Test
    fun a_refused_plan_is_fixed_once_with_another_encoder_and_the_trail_says_so() {
        val writers = ArrayList<Writer>()
        val lines = ArrayList<String>()
        var n = 0
        val out = File(tmp.root, "c.mp4")
        val r = runBlocking { exporter(writers, { n++ == 0 }, lines).export(job(ExportSettings(codec = ExportCodec.HEVC)), 7, out) }
        assertEquals(State.DONE, r.state)
        assertEquals(2, writers.size)
        assertEquals("c2.a.hevc", writers[0].plan!!.encoderName)
        assertEquals("c2.b.hevc", writers[1].plan!!.encoderName)
        assertTrue(writers[0].released)
        assertEquals("c2.b.hevc", r.report!!.encoder)
        assertTrue(r.report!!.steps.any { it.startsWith("rejected:") })
        assertTrue(r.report!!.steps.any { it.startsWith("fix:") })
        assertTrue(lines.any { it.startsWith("fix:") }) // diagnostics log got every step
        assertFalse(File(out.parentFile, "c.mp4.partial").exists())
    }

    @Test
    fun a_second_refusal_fails_with_the_encoders_reason_and_no_third_attempt() {
        val writers = ArrayList<Writer>()
        val out = File(tmp.root, "d.mp4")
        val r = runBlocking { exporter(writers, { true }).export(job(ExportSettings(codec = ExportCodec.HEVC)), 7, out) }
        assertEquals(State.FAILED, r.state)
        assertTrue(r.detail!!.contains("rejected the format"))
        assertEquals(2, writers.size) // first + at most ONE fix
        assertFalse(out.exists())
        assertNull(r.report)
    }

    @Test
    fun an_unresolvable_plan_fails_before_any_encoder_is_created() {
        val writers = ArrayList<Writer>()
        val noHevc = Mp4Exporter(
            session = Session(), writerFactory = { Writer(ArrayList()) { false }.also { writers += it } },
            engine = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } },
            encoderFacts = { listOf(EncoderFacts("c2.tiny.avc", "video/avc", true, maxWidth = 2, maxHeight = 2)) },
            planLog = {},
        )
        // 4x4 does not fit a 2x2 encoder: SIZE_UNSUPPORTED, an ERROR plan
        val r = runBlocking { noHevc.export(job(ExportSettings()), 7, File(tmp.root, "e.mp4")) }
        assertEquals(State.FAILED, r.state)
        assertTrue(r.detail!!.contains("configure"))
        assertTrue(writers.single().released)
        assertFalse(writers.single().started)
    }

    @Test
    fun the_fix_does_not_exist_when_nothing_can_change() {
        val run = ExportPlanRun(ExportSettings(), { emptyList() })
        run.planFor(1920, 1080, 30.0) // no device facts: encoder chosen by MIME
        assertFalse(run.fixAfterRejection("rejected"))
        assertTrue(run.steps.any { it.startsWith("no fix") })
    }

    @Test
    fun a_device_without_facts_plans_by_mime_and_names_no_encoder() {
        val run = ExportPlanRun(ExportSettings(codec = ExportCodec.HEVC), { throw IllegalStateException("probe failed") })
        val p = run.planFor(1920, 1080, 30.0)
        assertTrue(p.ok)
        assertNull(p.encoderName)
        assertEquals("video/hevc", p.mime)
    }

    @Test
    fun the_report_survives_the_store_round_trip() {
        val rep = ExportReport(
            "c2.a.hevc", true, "video/hevc", 1920, 1080, 29.97, 8, "VBR", null, 1f, 6_000_000, 192,
            123_456L, 10.5, listOf("plan: x", "fix: y"), listOf("RATE_UNLISTED:29.97"),
        )
        val job = job(ExportSettings()).copy(state = State.DONE, report = rep)
        val parsed = ExportStore.parseExports("{\"exports\":" + ExportStore.renderExports(listOf(job)) + "}").single()
        assertEquals(rep, parsed.report)
        assertNotNull(parsed.settings)
        // and a job without one stays byte-identical (no "report" key)
        assertFalse(ExportStore.renderExports(listOf(job(null))).contains("report"))
    }

    @Test
    fun the_measured_loudness_pair_rides_the_report_and_omits_when_absent() {
        // (E5, night D2) A chain that ran leaves its honest measurement on
        // the report; the wire carries the pair and omits it when null —
        // a pre-E5 report row parses exactly as it always did.
        val with = ExportReport(
            "c2.a.hevc", true, "video/hevc", 1080, 1920, 30.0, 8, "VBR", null, 1f, 6_000_000, 192,
            123_456L, 10.5, listOf("plan: x"), emptyList(),
            wallMs = 8_000L, audioLufs = -14.02, audioDbtp = -1.01,
        )
        val job = job(ExportSettings()).copy(state = State.DONE, report = with)
        val back = ExportStore.parseExports("{\"exports\":" + ExportStore.renderExports(listOf(job)) + "}").single()
        assertEquals(with, back.report)
        assertEquals(-14.02, back.report!!.audioLufs!!, 1e-9)
        assertEquals(-1.01, back.report!!.audioDbtp!!, 1e-9)
        // The wire carries the pair only when measured.
        val text = ExportStore.renderExports(listOf(job))
        assertTrue(text, text.contains("\"audioLufs\":-14.02"))
        assertTrue(text, text.contains("\"audioDbtp\":-1.01"))
        // Without the pair, nothing is written (byte-identical old rows).
        val without = with.copy(audioLufs = null, audioDbtp = null)
        val plainText = ExportStore.renderExports(
            listOf(job(ExportSettings()).copy(state = State.DONE, report = without)),
        )
        assertFalse(plainText, plainText.contains("audioLufs"))
        assertFalse(plainText, plainText.contains("audioDbtp"))
    }

    @Test
    fun the_g1_audio_report_fields_ride_the_report_and_omit_when_absent() {
        // (E5/E5-B, night G1) The applied gain, the honest skip reason and
        // the output audio shape ride the report the same way — and omit
        // when null, so every pre-G1 report row parses exactly as it
        // always did.
        val with = ExportReport(
            "c2.a.hevc", true, "video/hevc", 1080, 1920, 30.0, 8, "VBR", null, 1f, 6_000_000, 192,
            123_456L, 10.5, listOf("plan: x"), emptyList(),
            audioLufs = -14.02, audioDbtp = -1.01, audioGainDb = 5.98,
            audioSampleRateHz = 44100, audioChannels = 1, audioSkipped = null,
        )
        val job = job(ExportSettings()).copy(state = State.DONE, report = with)
        val back = ExportStore.parseExports("{\"exports\":" + ExportStore.renderExports(listOf(job)) + "}").single()
        assertEquals(with, back.report)
        assertEquals(5.98, back.report!!.audioGainDb!!, 1e-9)
        assertEquals(44100, back.report!!.audioSampleRateHz)
        assertEquals(1, back.report!!.audioChannels)
        val text = ExportStore.renderExports(listOf(job))
        assertTrue(text, text.contains("\"audioGainDb\":5.98"))
        assertTrue(text, text.contains("\"audioSampleRateHz\":44100"))
        assertTrue(text, text.contains("\"audioChannels\":1"))
        // The skip reason is a quoted string when present.
        val skipped = with.copy(
            audioLufs = null, audioDbtp = null, audioGainDb = 0.0, audioSkipped = "too-short",
        )
        val skippedJob = job(ExportSettings()).copy(state = State.DONE, report = skipped)
        val skippedBack = ExportStore.parseExports(
            "{\"exports\":" + ExportStore.renderExports(listOf(skippedJob)) + "}",
        ).single()
        assertEquals("too-short", skippedBack.report!!.audioSkipped)
        assertTrue(
            ExportStore.renderExports(listOf(skippedJob)).contains("\"audioSkipped\":\"too-short\""),
        )
        // Without them, nothing is written (byte-identical old rows).
        val without = with.copy(
            audioLufs = null, audioDbtp = null, audioGainDb = null,
            audioSampleRateHz = null, audioChannels = null, audioSkipped = null,
        )
        val plainText = ExportStore.renderExports(
            listOf(job(ExportSettings()).copy(state = State.DONE, report = without)),
        )
        assertFalse(plainText, plainText.contains("audioGainDb"))
        assertFalse(plainText, plainText.contains("audioSkipped"))
        assertFalse(plainText, plainText.contains("audioSampleRateHz"))
        assertFalse(plainText, plainText.contains("audioChannels"))
    }
}
