package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.ProjectJson
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.timeline.FrameRange
import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineOutput
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineStore
import com.ritim.ritimcraw.data.timeline.TimelineTrack
import com.ritim.ritimcraw.data.timeline.TrackKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Timeline MP4 export tests (TL-EX): the plan-walking exporter around FAKE
 * session/writer is JVM-testable without librawedit_jni.so and without
 * MediaCodec (framework stubs throw in JVM tests) — the real encode is
 * device-gated. The plan itself (TimelineExportPlan) has its own suite;
 * these tests pin the WALK: open-once, black gaps, the output pts grid,
 * frame reuse, the honest-completion failure semantics, and the snapshot
 * plumbing (TimelineRef capture/parse, ExportStore round-trip, RgbScale).
 */
class TimelineMp4ExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- fakes (the Mp4ExporterTest patterns) ----

    /** A fake session: solid-color frames, per-source geometry. */
    private open class FakeSession(
        private val width: Int = 4,
        private val height: Int = 4,
        private val orientationDeg: Int = 0,
    ) : Mp4Session {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val rendered = ArrayList<Pair<String, Int>>()

        /** Fds whose open fails (the exporter's fd seam hands these). */
        var failingFds: Set<Int> = emptySet()

        override fun open(fd: Int): Mp4SessionInfo? {
            opened.incrementAndGet()
            if (fd in failingFds) return null
            return Mp4SessionInfo(
                handle = fd.toLong(),
                frameCount = 8,
                width = width,
                height = height,
                cfa = 0,
                orientationDeg = orientationDeg,
            )
        }

        override fun renderFrame(
            handle: Long,
            frameIndex: Int,
            grade: GradeK,
            lutPath: String?,
            gyroPath: String?,
        ): ByteArray? {
            if (handle <= 0L) return null
            rendered.add(handle.toString() to frameIndex)
            return ByteArray(width * height * 3) { 128.toByte() }
        }

        override fun framePts(handle: Long, frameIndex: Int): Long = frameIndex * 33_333L
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray? = null
        override fun close(handle: Long) {
            closed.incrementAndGet()
        }

        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    /** A fake writer: records frames/pts, writes the staged file on finish. */
    private class FakeWriter(
        private val finishOk: Boolean = true,
        private val throwOnStart: Boolean = false,
    ) : Mp4MediaWriter {
        var started = false
        var released = false
        var videoFrames = 0
        val sawPts = ArrayList<Long>()
        val frames = ArrayList<ByteArray>()
        var startArgs: Array<Any>? = null
        var outPath: String? = null

        override fun start(
            outPath: String,
            width: Int,
            height: Int,
            orientationDeg: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            if (throwOnStart) {
                throw IllegalStateException(
                    Mp4ExportErrors.kotlinMessage(
                        Mp4ExportErrors.STAGE_CONFIGURE,
                        "video/avc rejected the ${width}x$height format",
                        "retry the export",
                    )
                )
            }
            started = true
            this.outPath = outPath
            startArgs = arrayOf(outPath, width, height, orientationDeg, sampleRate, channels)
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
            videoFrames++
            sawPts.add(ptsUs)
            frames.add(i420.copyOf())
        }

        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}

        override fun finish(): Boolean {
            if (finishOk) {
                File(outPath!!).writeBytes(ByteArray(64))
            }
            return finishOk
        }

        override fun release() {
            released = true
        }
    }

    /** An executor that rejects everything (a shut-down engine). */
    private class RejectedExecutor : ExecutorService by Executors.newSingleThreadExecutor() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("engine shut down")
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-timeline-export").apply { isDaemon = true }
        }

    // ---- fixtures ----

    private fun source(id: String): MediaSource =
        MediaSource(
            id = id,
            uri = "content://x/$id",
            fingerprint = "fp-$id",
            kind = SourceKind.MCRAW,
            access = Access.OK,
        )

    /**
     * A one-video-track project: clip "a" (source "s1") at 0..3, a two-frame
     * gap, clip "b" (source "s2") at 5..8 — 8 output frames at 30 fps, with a
     * tiny output size so the scale path is cheap.
     */
    private fun twoClipProject(): TimelineProject =
        TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip("a", "v", "s1", FrameRange(0.0, 3.0), FrameRange(0.0, 3.0)),
                TimelineClip("b", "v", "s2", FrameRange(0.0, 3.0), FrameRange(5.0, 8.0)),
            ),
        )

    private fun timelineJob(project: TimelineProject, name: String = "My Timeline"): ExportJob =
        ExportJob(
            id = "j1",
            sourceId = "timeline",
            snapshot = GradeK(),
            target = Target.MP4,
            state = State.QUEUED,
            timeline = TimelineRef.capture(name, project),
        )

    /** The exporter under test, wired to fakes. s1 gets fd 10, s2 fd 11. */
    private fun exporter(
        session: FakeSession,
        writer: FakeWriter,
        sources: Map<String, MediaSource> = mapOf("s1" to source("s1"), "s2" to source("s2")),
        fps: Map<String, Double> = mapOf("s1" to 30.0, "s2" to 30.0),
        frames: Map<String, Int> = mapOf("s1" to 8, "s2" to 8),
        stageLog: ((String) -> Unit)? = null,
        engine: ExecutorService = freshEngine(),
        converter: RgbToI420 = NativeRgbToI420,
    ): TimelineMp4Exporter {
        val fdOf = sources.keys.withIndex().associate { (i, id) -> id to 10 + i }
        return TimelineMp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = engine,
            sourceFdFor = { id -> fdOf[id] ?: -1 },
            sources = { sources },
            fpsMeasured = { id -> fps[id] },
            frameCount = { id -> frames[id] },
            gradeFor = { GradeK() },
            stageLog = stageLog,
            converter = converter,
        )
    }

    // ---- the walk: happy path ----

    @Test
    fun export_walks_clip_gap_clip_in_order() {
        val session = FakeSession()
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(session, writer, stageLog = lines::add)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.DONE, result.state)
        assertEquals("timeline.mp4", result.detail)
        assertTrue(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
        // 8 frames total: 3 clip a, 2 black gap, 3 clip b — the movie keeps
        // the plan's exact length, nothing skipped, nothing lost.
        assertEquals(8, writer.videoFrames)
        assertEquals(6, session.rendered.size) // 3 + 3 real renders
        // Each source opened exactly once (open-once) and closed once.
        assertEquals(2, session.opened.get())
        assertEquals(2, session.closed.get())
        // The writer was told: the STAGED path, even-snapped plan dims, no
        // orientation hint (pixels pre-rotated per source), video-only 0/0.
        val args = writer.startArgs!!
        assertEquals(File(out.parentFile, out.name + ".partial").absolutePath, args[0])
        assertEquals(4, args[1])
        assertEquals(4, args[2])
        assertEquals(0, args[3])
        assertEquals(0, args[4])
        assertEquals(0, args[5])
        // The plan landed in the stage log.
        assertTrue(lines.any { it.contains("timeline plan") && it.contains("frames=8") })
    }

    @Test
    fun export_pts_follow_the_output_grid() {
        // Frame i lands at i * 1_000_000 / 30 us — the OUTPUT grid's pts
        // (the movie's timebase is the project's, not any source's).
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        assertEquals((0 until 8).map { it * 1_000_000L / 30 }, writer.sawPts)
    }

    @Test
    fun export_gap_frames_are_limited_range_black() {
        // Output frames 3 and 4 are the gap: exactly blackI420's recipe
        // (Y=16, U=V=128) — the same bytes a skipped clip renders.
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        val black = TimelineMp4Exporter.blackI420(4, 4)
        assertTrue(writer.frames[3].contentEquals(black))
        assertTrue(writer.frames[4].contentEquals(black))
        // And the clip frames are NOT black (the session's 128-gray RGB
        // converts to a different Y than 16).
        assertFalse(writer.frames[0].contentEquals(black))
    }

    @Test
    fun export_repeats_a_source_frame_without_redecoding() {
        // A 24 fps source in a 30 fps project: the frame-nearest mapping
        // maps output 0..4 to source frames round(j*24/30) = 0,1,2,2,3 —
        // frame 2 REPEATS. The repeat must reuse the decoded bytes: one
        // render per DISTINCT frame, not per output frame.
        val project = TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip("a", "v", "s1", FrameRange(0.0, 5.0), FrameRange(0.0, 5.0)),
            ),
        )
        val session = FakeSession()
        val writer = FakeWriter()
        val e = exporter(session, writer, fps = mapOf("s1" to 24.0))
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(project), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(5, writer.videoFrames)
        // Four DISTINCT source frames (0,1,2,3) — the repeated frame 2 was
        // reused, never re-rendered.
        assertEquals(listOf(0, 1, 2, 3).map { "10" to it }, session.rendered)
    }

    @Test
    fun export_opens_a_source_placed_twice_only_once() {
        // The SAME source placed as two clips: one open, one close, both
        // spans rendered through the one session.
        val project = TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip("a", "v", "s1", FrameRange(0.0, 2.0), FrameRange(0.0, 2.0)),
                TimelineClip("b", "v", "s1", FrameRange(2.0, 4.0), FrameRange(3.0, 5.0)),
            ),
        )
        val session = FakeSession()
        val writer = FakeWriter()
        val e = exporter(session, writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(project), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(1, session.opened.get())
        assertEquals(1, session.closed.get())
        assertEquals(5, writer.videoFrames) // 2 + 1 gap + 2
    }

    // ---- the walk: honest-completion failure semantics ----

    @Test
    fun export_failed_open_renders_the_entry_black_and_completes() {
        // s2 fails to open: its entry's frames render BLACK (never skipped
        // — the movie keeps its length), the failure lands in the stage
        // log, and the export COMPLETES DONE.
        val session = FakeSession().apply { failingFds = setOf(11) }
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(session, writer, stageLog = lines::add)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(8, writer.videoFrames) // length preserved
        val black = TimelineMp4Exporter.blackI420(4, 4)
        // s2's span (output frames 5..7) is black; s1's is not.
        assertTrue(writer.frames[5].contentEquals(black))
        assertTrue(writer.frames[6].contentEquals(black))
        assertTrue(writer.frames[7].contentEquals(black))
        assertFalse(writer.frames[0].contentEquals(black))
        // The failure is in the stage log with the source named.
        assertTrue(lines.any { it.contains("s2") && it.contains("black") })
        // Only s1's REAL handle was closed (s2's -1 sentinel never reaches
        // native close).
        assertEquals(1, session.closed.get())
    }

    @Test
    fun export_failed_open_is_remembered_not_retried_per_entry() {
        // A source that failed to open does not retry for its next entry:
        // the -1 sentinel reads back as null (the sentinel pins), the
        // whole span renders black, and the export completes.
        val project = TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip("a", "v", "s2", FrameRange(0.0, 2.0), FrameRange(0.0, 2.0)),
                TimelineClip("b", "v", "s2", FrameRange(0.0, 2.0), FrameRange(3.0, 5.0)),
            ),
        )
        val session = FakeSession().apply { failingFds = setOf(11) }
        val writer = FakeWriter()
        val e = exporter(session, writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(project), out) }

        assertEquals(State.DONE, result.state)
        // One open attempt TOTAL: the failure was cached; the second entry
        // never re-opened.
        assertEquals(1, session.opened.get())
        // Nothing was closed — the only cache entry is the -1 sentinel,
        // and sentinels are never handed to native close.
        assertEquals(0, session.closed.get())
        // 2 black + 1 gap + 2 black: the whole movie is black.
        assertEquals(5, writer.videoFrames)
        val black = TimelineMp4Exporter.blackI420(4, 4)
        assertTrue(writer.frames.all { it.contentEquals(black) })
    }

    @Test
    fun export_mid_render_failure_renders_that_frame_black_and_continues() {
        // One frame of s1 fails to decode: that frame is black, the rest
        // of the walk continues, the export completes DONE.
        val session = object : FakeSession() {
            override fun renderFrame(
                handle: Long,
                frameIndex: Int,
                grade: GradeK,
                lutPath: String?,
                gyroPath: String?,
            ): ByteArray? {
                if (frameIndex == 1) return null // clip a's second frame fails
                return super.renderFrame(handle, frameIndex, grade, lutPath, gyroPath)
            }
        }
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(session, writer, stageLog = lines::add)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(8, writer.videoFrames)
        val black = TimelineMp4Exporter.blackI420(4, 4)
        // Output frame 1 (clip a's second frame) is black; its neighbors
        // are not.
        assertTrue(writer.frames[1].contentEquals(black))
        assertFalse(writer.frames[0].contentEquals(black))
        assertFalse(writer.frames[2].contentEquals(black))
        // The stage log names the failed frame.
        assertTrue(lines.any { it.contains("frame=1") && it.contains("black") })
    }

    // ---- skipped clips: the plan's gates render black too ----

    @Test
    fun export_skipped_clip_span_renders_black_and_logs_the_reason() {
        // fps UNKNOWN for s2: the plan skips clip b (FPS_UNKNOWN), its
        // span renders black, the skip lands in the stage log, DONE.
        val session = FakeSession()
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(
            session, writer,
            fps = mapOf("s1" to 30.0), // s2 answered UNKNOWN
            stageLog = lines::add,
        )
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(8, writer.videoFrames)
        // Clip b's span (5..7) AND the gap (3..4) are black; clip a is not.
        val black = TimelineMp4Exporter.blackI420(4, 4)
        for (i in 3..7) {
            assertTrue("frame $i should be black", writer.frames[i].contentEquals(black))
        }
        assertFalse(writer.frames[0].contentEquals(black))
        // The skip is in the stage log with the clip and the reason.
        assertTrue(lines.any { it.contains("FPS_UNKNOWN") && it.contains("clip=b") })
        // s2 was never opened (the plan skipped it before any open).
        assertEquals(1, session.opened.get())
    }

    @Test
    fun export_audio_clips_log_the_audio_skipped_marker() {
        // A project with an AUDIO-track clip: the plan's audioSkipped is
        // true and the exporter logs the honest marker — v1 muxes video.
        val project = TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(
                TimelineTrack("v", TrackKind.VIDEO, 0),
                TimelineTrack("a", TrackKind.AUDIO, 1),
            ),
            clips = listOf(
                TimelineClip("va", "v", "s1", FrameRange(0.0, 2.0), FrameRange(0.0, 2.0)),
                TimelineClip("aa", "a", "s2", FrameRange(0.0, 2.0), FrameRange(0.0, 2.0)),
            ),
        )
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(FakeSession(), writer, stageLog = lines::add)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(project), out) }

        assertEquals(State.DONE, result.state)
        assertEquals(2, writer.videoFrames) // the video clip only
        assertTrue(lines.any { it.contains("audio") && it.contains("skipped") })
    }

    // ---- the plan's honesty gates at run time ----

    @Test
    fun export_zero_frame_plan_fails_with_stage_plan() {
        // No video clips: the plan resolves to zero frames — FAILED with
        // the plan stage and a recovery, never a zero-frame movie.
        val project = TimelineProject(
            output = TimelineOutput(width = 4, height = 4, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = emptyList(),
        )
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(project), out) }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("stage: plan"))
        assertTrue(result.detail!!.contains("zero frames"))
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    @Test
    fun export_job_without_timeline_fails_with_stage_plan() {
        // A timeline dispatch reached the exporter with no snapshot (a
        // degraded row): FAILED honestly, never a guess.
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking {
            e.export(ExportJob("j1", "timeline", GradeK(), Target.MP4, State.QUEUED), out)
        }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("stage: plan"))
        assertTrue(result.detail!!.contains("no timeline snapshot"))
        assertFalse(out.exists())
    }

    @Test
    fun export_malformed_snapshot_fails_with_stage_plan() {
        // The wrapped section is garbage: parseTimeline throws, the
        // exporter maps it to the plan stage with the parse detail.
        val job = timelineJob(twoClipProject()).copy(
            timeline = TimelineRef(name = "x", projectJson = "{\"timeline\":not-json"),
        )
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(job, out) }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("stage: plan"))
        assertTrue(result.detail!!.contains("could not be read"))
    }

    // ---- output failures fail the job (the honest-completion boundary) ----

    @Test
    fun export_writer_start_failure_fails_the_job() {
        // The encoder rejects the format: FAILED with the writer's stage
        // message, no output, no partial — and no session was ever opened
        // (start precedes the walk).
        val session = FakeSession()
        val writer = FakeWriter(throwOnStart = true)
        val e = exporter(session, writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("video/avc"))
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
        assertTrue(writer.released)
        assertEquals(0, session.opened.get())
        assertEquals(0, session.closed.get())
    }

    @Test
    fun export_mux_failure_fails_the_job_and_cleans_up() {
        val writer = FakeWriter(finishOk = false)
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("muxer"))
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
        assertTrue(writer.released)
    }

    @Test
    fun unavailable_engine_fails_cleanly() {
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer, engine = RejectedExecutor())
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }

        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("export engine unavailable"))
        assertFalse(out.exists())
    }

    // ---- progress ----

    @Test
    fun export_reports_progress_per_frame_to_the_plan_total() {
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        val reports = ArrayList<Pair<Int, Int>>()
        runBlocking {
            e.export(timelineJob(twoClipProject()), out) { done, total ->
                reports.add(done to total)
            }
        }

        // (0, total) up front, then one report per frame: done marches
        // 0..8 with the SAME total — the plan's totalFrames.
        assertEquals((0..8).toList(), reports.map { it.first })
        assertTrue(reports.all { it.second == 8 })
    }

    // ---- RgbToI420 seam: the native YUV step's dispatch + debug dual-run ----

    @Test
    fun rgb_to_i420_seam_default_is_the_native_dispatcher() {
        // The exporter's default converter is the native dispatch (the
        // per-clip export's seam, now shared by the timeline walk). On the
        // JVM the library is unavailable, so the dispatch falls back to the
        // Kotlin toI420 — and the writer still receives the exact
        // YuvConverter bytes (the fallback wiring's proof).
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        // 4x4 output, 4x4 source, deg 0: identity scale, so the converter
        // saw exactly the session's 128-gray frame.
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.frames[0].contentEquals(expected))
    }

    @Test
    fun rgb_to_i420_injected_converter_feeds_the_writer() {
        // A fake converter proves the walk calls THROUGH the seam and feeds
        // the writer what the seam returned (not a re-conversion): one call
        // per rendered clip frame, zero for the black gap.
        var calls = 0
        val marker = ByteArray(4 * 4 + 2 * 2 * 2) { 7.toByte() }
        val converter = RgbToI420 { _, _, _ ->
            calls++
            RgbToI420Result(marker, fromNative = false)
        }
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer, converter = converter)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        // 6 rendered clip frames (3 + 3), the 2 gap frames are blackI420
        // and never reach the seam.
        assertEquals(6, calls)
        assertTrue(writer.frames[0].contentEquals(marker))
        assertFalse(writer.frames[3].contentEquals(marker)) // the gap stays black
    }

    @Test
    fun rgb_to_i420_walk_dual_run_logs_through_the_stage_sink() {
        // A converter that claims native origin drives the walk's dual-run:
        // the FIRST frame logs the comparison line through the stage sink,
        // later frames do not (the per-clip pipeline's affordance, now on
        // the timeline walk too).
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = true)
        }
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(FakeSession(), writer, stageLog = lines::add, converter = converter)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        // Exactly ONE dual-run line for the whole export.
        assertEquals(1, lines.count { it.startsWith("rgbToI420 dual-run") })
        val dual = lines.first { it.startsWith("rgbToI420 dual-run") }
        assertTrue(dual.contains("native == kotlin"))
    }

    @Test
    fun rgb_to_i420_walk_dual_run_skipped_for_kotlin_frames() {
        // fromNative false (the JVM fallback, any pure fake): no dual-run
        // line, even with the sink wired.
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = false)
        }
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        val e = exporter(FakeSession(), writer, stageLog = lines::add, converter = converter)
        val out = File(tmp.root, "timeline.mp4")
        runBlocking { e.export(timelineJob(twoClipProject()), out) }
        assertEquals(0, lines.count { it.startsWith("rgbToI420 dual-run") })
    }

    @Test
    fun rgb_to_i420_walk_dual_run_skipped_without_sink() {
        // A null stage sink (release builds, logging off): the dual-run
        // never runs — the guard is the sink itself, so release exports
        // pay zero.
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = true)
        }
        val writer = FakeWriter()
        val e = exporter(FakeSession(), writer, converter = converter)
        val out = File(tmp.root, "timeline.mp4")
        val result = runBlocking { e.export(timelineJob(twoClipProject()), out) }
        assertEquals(State.DONE, result.state) // the export ran, conversion included
        assertEquals(8, writer.videoFrames)
    }

    // ---- blackI420 ----

    @Test
    fun black_i420_is_limited_range_black() {
        // 4x4: 16 bytes of Y=16, then U and V planes of 128.
        val black = TimelineMp4Exporter.blackI420(4, 4)
        assertEquals(4 * 4 + 2 * 2 * 2, black.size)
        assertTrue(black.copyOfRange(0, 16).all { it == 16.toByte() })
        assertTrue(black.copyOfRange(16, 24).all { it == 128.toByte() })
        // Odd dims: 3x2 -> Y 6, cw 2, ch 1 -> U 2, V 2.
        val odd = TimelineMp4Exporter.blackI420(3, 2)
        assertEquals(3 * 2 + 2 * 2 * 1, odd.size)
        assertTrue(odd.copyOfRange(0, 6).all { it == 16.toByte() })
        assertTrue(odd.copyOfRange(6, 10).all { it == 128.toByte() })
    }

    // ---- TimelineRef: capture + row-tolerant parse ----

    @Test
    fun ref_capture_wraps_the_rendered_section_as_a_document() {
        val project = twoClipProject()
        val ref = TimelineRef.capture("My Timeline", project)
        assertEquals("My Timeline", ref.name)
        // The wrap is parseable by parseTimeline (the whole-doc reader).
        val parsed = TimelineStore.parseTimeline(ref.projectJson)
        assertEquals(2, parsed.clips.size)
        // And it round-trips through parseOrNull (the store row shape).
        val back = TimelineRef.parseOrNull(
            "{\"name\":\"My Timeline\",\"projectJson\":" +
                ProjectJson.quote(ref.projectJson) + "}",
        )
        assertNotNull(back)
        assertEquals(ref.projectJson, back!!.projectJson)
    }

    @Test
    fun ref_parse_is_row_tolerant_never_throws() {
        // The store's row loop must never see an exception from here:
        // null/blank/malformed/missing-field/wrong-type all yield null.
        assertNull(TimelineRef.parseOrNull(null))
        assertNull(TimelineRef.parseOrNull(""))
        assertNull(TimelineRef.parseOrNull("   "))
        assertNull(TimelineRef.parseOrNull("not json at all"))
        assertNull(TimelineRef.parseOrNull("{\"name\":\"x\"}")) // no projectJson
        assertNull(TimelineRef.parseOrNull("{\"projectJson\":\"x\"}")) // no name
        assertNull(TimelineRef.parseOrNull("[1,2,3]")) // not an object
        assertNull(TimelineRef.parseOrNull("{\"name\":3,\"projectJson\":\"x\"}")) // wrong types
        // A valid row still parses.
        val back = TimelineRef.parseOrNull("{\"name\":\"n\",\"projectJson\":\"{}\"}")
        assertNotNull(back)
        assertEquals("n", back!!.name)
        assertEquals("{}", back.projectJson)
    }

    // ---- ExportStore: the timeline row round-trips, old rows stay null ----

    @Test
    fun store_round_trips_a_timeline_job_and_keeps_per_clip_rows_null() {
        val file = File(tmp.root, "project.json")
        val store = ExportStore(file)
        store.add(timelineJob(twoClipProject(), name = "Sokak"))

        val loaded = store.all()
        assertEquals(1, loaded.size)
        val back = loaded[0].timeline
        assertNotNull(back)
        assertEquals("Sokak", back!!.name)
        // The snapshot survived: parse it back to a project.
        assertEquals(2, TimelineStore.parseTimeline(back.projectJson).clips.size)

        // A per-clip job in the same queue keeps timeline null (the
        // omit-when-null render: the row simply lacks the field).
        store.add(ExportJob("j2", "s1", GradeK(), Target.DNG, State.QUEUED))
        val both = store.all()
        assertEquals(2, both.size)
        assertEquals("j1", both[0].id)
        assertEquals("j2", both[1].id)
        assertNotNull(both[0].timeline)
        assertNull(both[1].timeline)
    }

    @Test
    fun store_row_with_degraded_timeline_keeps_the_rest_of_the_queue() {
        // The row-tolerance pin: a timeline field parseOrNull REJECTS (a
        // parseable object missing projectJson) degrades this ONE row to a
        // null timeline — never a throw out of the row loop, which
        // load()'s catch-all would answer by emptying the WHOLE queue.
        val file = File(tmp.root, "project.json")
        file.parentFile?.mkdirs()
        fun row(j: ExportJob): String =
            ExportStore.renderExports(listOf(j)).removeSurrounding("[", "]")
        val g1 = ExportJob("g1", "s1", GradeK(), Target.DNG, State.QUEUED)
        val g2 = ExportJob("g2", "s2", GradeK(), Target.MP4, State.QUEUED)
        file.writeText(
            "{\"exports\":[" +
                row(g1) + "," +
                "{\"id\":\"bad\",\"sourceId\":\"s1\",\"snapshot\":{}," +
                "\"target\":\"DNG\",\"state\":\"QUEUED\"," +
                "\"timeline\":{\"name\":\"broken\"}}" + // no projectJson
                "," + row(g2) +
                "]}",
        )

        val loaded = ExportStore(file).all()
        // All three rows survived; the degraded one parsed with a null
        // timeline (the exporter fails such a job honestly at run).
        assertEquals(3, loaded.size)
        assertEquals("g1", loaded[0].id)
        assertEquals("bad", loaded[1].id)
        assertNull(loaded[1].timeline)
        assertEquals("g2", loaded[2].id)
        assertNull(loaded[2].timeline)
    }

    // ---- RgbScale: the frame-size reconciliation ----

    @Test
    fun scale_identity_returns_the_input_by_reference() {
        val rgb = ByteArray(4 * 4 * 3) { 9.toByte() }
        assertSame(rgb, RgbScale.aspectFill(rgb, 4, 4, 4, 4))
    }

    @Test
    fun scale_upscale_preserves_solid_color_exactly() {
        // A solid color scales to the same solid color at any size — the
        // bilinear blend of equal values is the value.
        val rgb = ByteArray(2 * 2 * 3) { 200.toByte() }
        val out = RgbScale.aspectFill(rgb, 2, 2, 4, 4)
        assertEquals(4 * 4 * 3, out.size)
        assertTrue(out.all { it == 200.toByte() })
    }

    @Test
    fun scale_aspect_fill_center_crops_the_wider_axis() {
        // 4x2 source into 2x2 output: the cover scale is max(2/4, 2/2)=1,
        // so the crop is horizontal (cropX 1, cropY 0) — the CENTER
        // columns survive, the edges do not. A left-half-red /
        // right-half-blue 4x2 image therefore yields a 2x2 whose left
        // column is pure red and right column pure blue.
        val rgb = ByteArray(4 * 2 * 3)
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                val i = (y * 4 + x) * 3
                if (x < 2) {
                    rgb[i] = 255.toByte(); rgb[i + 1] = 0; rgb[i + 2] = 0
                } else {
                    rgb[i] = 0; rgb[i + 1] = 0; rgb[i + 2] = 255.toByte()
                }
            }
        }
        val out = RgbScale.aspectFill(rgb, 4, 2, 2, 2)
        // Pixel (0,0): R of the red sample; pixel (1,0): B of the blue one.
        assertEquals(255, out[0].toInt() and 0xFF)
        assertEquals(0, out[2].toInt() and 0xFF)
        assertEquals(0, out[3].toInt() and 0xFF)
        assertEquals(255, out[5].toInt() and 0xFF)
    }

    @Test
    fun scale_covers_the_output_never_stretches() {
        // A 4x1 gradient into 2x2: the cover scale is max(0.5, 2) = 2 —
        // the WIDTH is cropped to the center (cropX 1.5), the single
        // source row is replicated to both output rows (no stretch, no
        // invented rows). The center-of-pixel mapping is deterministic:
        // output R channel = 75, 105 in both rows.
        val rgb = ByteArray(4 * 1 * 3)
        for (x in 0 until 4) {
            rgb[x * 3] = (x * 60).toByte() // R: 0, 60, 120, 180
        }
        val out = RgbScale.aspectFill(rgb, 4, 1, 2, 2)
        assertEquals(2 * 2 * 3, out.size)
        val expectedR = listOf(75, 105, 75, 105) // rows agree, center blend
        for (p in 0 until 4) {
            assertEquals(expectedR[p], out[p * 3].toInt() and 0xFF)
        }
    }

    @Test
    fun scale_rejects_empty_input_or_output() {
        try {
            RgbScale.aspectFill(ByteArray(0), 0, 4, 4, 4)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty"))
        }
        try {
            RgbScale.aspectFill(ByteArray(12), 2, 2, 0, 4)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    // ---- RgbScale.aspectFit: the export resolution picker's scale ----

    @Test
    fun fit_identity_returns_the_input_by_reference() {
        val rgb = ByteArray(4 * 4 * 3) { 9.toByte() }
        assertSame(rgb, RgbScale.aspectFit(rgb, 4, 4, 4, 4))
    }

    @Test
    fun fit_exact_match_downscale_preserves_solid_color() {
        // The honest export path: the bound resolves to the source's OWN
        // aspect BEFORE the scale (ExportResolution.outputSize), so the
        // fit is EXACT — no bars, every output pixel a source sample.
        val rgb = ByteArray(4 * 4 * 3) { 200.toByte() }
        val out = RgbScale.aspectFit(rgb, 4, 4, 2, 2)
        assertEquals(2 * 2 * 3, out.size)
        assertTrue(out.all { it == 200.toByte() })
    }

    @Test
    fun fit_letterboxes_the_mismatched_axis_with_symmetric_black_bars() {
        // A 2x2 source into a 2x4 box whose aspect does NOT match: the
        // fit scale is min(2/2, 4/2) = 1, the image lands 2x2 centered
        // (barY 1), and the top/bottom rows are BLACK BARS (zero-filled)
        // — nothing crops, nothing stretches. The SIZE decision never
        // makes a FRAMING decision, and the bars are the honest
        // fallback, not a center-crop.
        val rgb = ByteArray(2 * 2 * 3) { 200.toByte() }
        val out = RgbScale.aspectFit(rgb, 2, 2, 2, 4)
        assertEquals(2 * 4 * 3, out.size)
        // Rows 0 and 3: pure black bars, symmetric top and bottom.
        for (p in 0 until 2 * 3) {
            assertEquals(0, out[p].toInt() and 0xFF)
        }
        for (p in 6 * 3 until 8 * 3) {
            assertEquals(0, out[p].toInt() and 0xFF)
        }
        // Rows 1 and 2: the source's solid color, at scale 1.
        for (p in 2 * 3 until 6 * 3) {
            assertEquals(200, out[p].toInt() and 0xFF)
        }
    }

    @Test
    fun fit_keeps_both_edges_never_center_crops() {
        // The 4:3 rule's byte-level proof: a left-half-red /
        // right-half-blue 4x2 source fit into 2x2 keeps BOTH halves —
        // output x=0 samples source columns 0-1, x=1 samples columns
        // 2-3, the FULL width participates (scaled, never cropped). The
        // leftover height is a bar, not a framing decision.
        val rgb = ByteArray(4 * 2 * 3)
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                val i = (y * 4 + x) * 3
                if (x < 2) {
                    rgb[i] = 255.toByte(); rgb[i + 1] = 0; rgb[i + 2] = 0
                } else {
                    rgb[i] = 0; rgb[i + 1] = 0; rgb[i + 2] = 255.toByte()
                }
            }
        }
        // Fit scale min(2/4, 2/2) = 0.5: the image lands 2x1 in row 0,
        // row 1 is the black bar. Row 0: left pixel red, right blue.
        val out = RgbScale.aspectFit(rgb, 4, 2, 2, 2)
        assertEquals(2 * 2 * 3, out.size)
        assertEquals(255, out[0].toInt() and 0xFF)
        assertEquals(0, out[2].toInt() and 0xFF)
        assertEquals(0, out[3].toInt() and 0xFF)
        assertEquals(255, out[5].toInt() and 0xFF)
        // The bar row is black.
        for (p in 2 * 3 until 4 * 3) {
            assertEquals(0, out[p].toInt() and 0xFF)
        }
    }
}
