package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EncodedPreviewSession + PreviewSessions (the seam) tests, all JVM: the
 * session wraps a REAL EncodedSource over fake reader/decoder/developer
 * boundaries (the EncodedSourceTest pattern), so the whole seam — open
 * routing, grade pass-through, pts mapping, closed-session refusal, close
 * discipline — runs without Android or the native lib.
 *
 * The MCRAW branch of PreviewSessions.open delegates to PreviewSession.open,
 * which gates on the native lib; without it the branch fails cleanly with the
 * unavailable message — exactly what its test asserts (the same device gate
 * the editor's canRender applies).
 */
class EncodedPreviewSessionTest {

    private val engine: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "test-preview-engine").apply { isDaemon = true }
    }

    @After
    fun tearDown() {
        engine.shutdownNow()
    }

    // ---- open ----

    @Test
    fun open_success_exposes_clip_frame_count() = runBlocking {
        val fake = fakeSource(pts = longArrayOf(0, 33_333, 66_666))
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake })
            .getOrThrow() as EditorPreviewSession

        assertEquals(3, session.frames)
        session.close()
    }

    @Test
    fun open_failure_resumes_with_the_state_machine_message() = runBlocking {
        // A reader that refuses the open: EncodedSource.open throws its
        // stage+cause+recovery IllegalStateException; the session must land
        // it as a Result failure, never rethrow.
        val fake = EncodedSource(
            reader = FakeReader(
                samples = samples(longArrayOf(0)),
                openOutcome = EncodedOpenOutcome.Failed(
                    kind = EncodedErrors.OPEN_NO_VIDEO_TRACK,
                ),
            ),
            decoder = FakeDecoder(),
        )
        val outcome = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake })

        assertTrue(outcome.isFailure)
        val msg = outcome.exceptionOrNull()?.message
        assertNotNull(msg)
        assertTrue(msg!!, msg.startsWith("Encoded clip failed (stage: open)"))
        assertTrue(msg, msg.contains("recovery:"))
    }

    @Test
    fun open_runs_on_the_engine_thread() = runBlocking {
        val opener = AtomicReference<String?>(null)
        val fake = EncodedSource(
            reader = ThreadNameReader { opener.set(Thread.currentThread().name) },
            decoder = FakeDecoder(),
        )
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()
        session.close()

        assertEquals("test-preview-engine", opener.get())
    }

    // ---- render ----

    @Test
    fun render_publishes_the_developed_frame_and_passes_the_grade_through() = runBlocking {
        val developer = FakeDeveloper(result = frame(4, 4) { 0xFF804020.toInt() })
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()
        val grade = GradeK(tempK = 6500f, tint = 5f, exposureEv = 0.7f)

        val rendered = session.render(0, grade, maxDim = 1280).getOrThrow()

        assertEquals(4, rendered.width)
        assertEquals(4, rendered.height)
        assertEquals(0xFF804020.toInt(), rendered.argb[0])
        // The FULL Grade object reaches the developer unchanged (developEncoded
        // consumes tempK/tint/exposureEv today; other stages may consume later).
        assertEquals(listOf(grade), developer.grades)
        session.close()
    }

    @Test
    fun render_scales_the_long_edge_to_the_tier() = runBlocking {
        // A 64x32 develop output at a 16 tier: the encoded engine develops
        // FULL resolution (no maxDim in its contract), the session scales
        // to the tier like the native MCRAW pipeline does internally.
        val developer = FakeDeveloper(result = frame(64, 32) { 0xFF00FF00.toInt() })
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        val rendered = session.render(0, GradeK(), maxDim = 16).getOrThrow()

        assertEquals(16, rendered.width)
        assertEquals(8, rendered.height)
        assertEquals(16 * 8, rendered.argb.size)
        session.close()
    }

    @Test
    fun render_never_upscales_a_small_frame() = runBlocking {
        val developer = FakeDeveloper(result = frame(4, 4) { 0xFF102030.toInt() })
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        val rendered = session.render(0, GradeK(), maxDim = 1280).getOrThrow()

        assertEquals(4, rendered.width)
        assertEquals(4, rendered.height)
        session.close()
    }

    @Test
    fun render_out_of_range_fails_without_touching_the_engine() = runBlocking {
        val developer = FakeDeveloper(result = frame(4, 4) { 0 })
        val fake = fakeSource(pts = longArrayOf(0, 33_333), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        val outcome = session.render(5, GradeK(), maxDim = 1280)

        assertTrue(outcome.isFailure)
        assertEquals(0, developer.grades.size)
        session.close()
    }

    @Test
    fun render_failure_carries_the_state_machine_message() = runBlocking {
        // develop returns null: renderFrame throws the develop-stage message;
        // the session lands it as a failure, message intact.
        val developer = FakeDeveloper(
            result = null,
            errorCode = EncodedErrors.CODE_IO,
            errorStage = EncodedErrors.STAGE_DEVELOP,
        )
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        val outcome = session.render(0, GradeK(), maxDim = 1280)

        assertTrue(outcome.isFailure)
        assertEquals(
            "Encoded clip failed (stage: develop): the frame could not be decoded — " +
                "recovery: re-open the clip",
            outcome.exceptionOrNull()?.message,
        )
        session.close()
    }

    // ---- renderCached maps onto render (no invented caching) ----

    @Test
    fun render_cached_decodes_like_render_on_the_encoded_path() = runBlocking {
        // EncodedSource has no cached re-develop: its decode repositions
        // whenever a request does not continue the current position —
        // including a same-frame re-render — so renderCached MUST behave
        // exactly like render (both hit the developer; no cache invented).
        val developer = FakeDeveloper(result = frame(4, 4) { 0xFF010101.toInt() })
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()
        val grade = GradeK(tempK = 7000f)

        val first = session.render(0, grade, maxDim = 1280).getOrThrow()
        val second = session.renderCached(0, grade, maxDim = 1280).getOrThrow()

        assertEquals(first, second)
        // Both renders developed: the second was NOT served from any cache.
        assertEquals(2, developer.grades.size)
        session.close()
    }

    // ---- framePtsUs ----

    @Test
    fun frame_pts_maps_the_presentation_index() = runBlocking {
        val fake = fakeSource(pts = longArrayOf(0, 33_333, 66_666))
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        assertEquals(33_333L, session.framePtsUs(1))
        session.close()
    }

    @Test
    fun frame_pts_out_of_range_is_minus_one() = runBlocking {
        // Throw-free like the MCRAW session's contract: a bad index maps to
        // -1, and ptsUs[i] = -1 entries degrade the transport gracefully.
        val fake = fakeSource(pts = longArrayOf(0, 33_333))
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        assertEquals(-1L, session.framePtsUs(9))
        session.close()
    }

    // ---- close discipline ----

    @Test
    fun closed_session_render_fails_without_touching_the_source() = runBlocking {
        val developer = FakeDeveloper(result = frame(4, 4) { 0 })
        val fake = fakeSource(pts = longArrayOf(0), developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()
        session.close()

        val outcome = session.render(0, GradeK(), maxDim = 1280)

        assertTrue(outcome.isFailure)
        assertEquals(
            "Preview failed (stage: open): session closed — recovery: reopen the clip",
            outcome.exceptionOrNull()?.message,
        )
        assertEquals(0, developer.grades.size)
    }

    @Test
    fun close_is_idempotent_and_releases_the_boundaries_once() = runBlocking {
        val reader = FakeReader(samples(longArrayOf(0)))
        val decoder = FakeDecoder()
        val developer = FakeDeveloper(result = frame(4, 4) { 0xFF010101.toInt() })
        val fake = EncodedSource(reader = reader, decoder = decoder, developer = developer)
        val session = EncodedPreviewSession.open(fd = 7, engine, newSource = { fake }).getOrThrow()

        val rendered = session.render(0, GradeK(), maxDim = 1280)
        session.close()
        session.close()
        // close() only QUEUES source.close() on the engine; a fence task
        // submitted after it (single-thread engine) proves the close ran.
        engine.submit {}.get()

        assertNotNull(rendered.getOrNull())
        assertTrue(reader.released)
        assertTrue(decoder.released)
    }

    // ---- PreviewSessions factory: routing + fd ownership ----

    @Test
    fun factory_negative_fd_fails_with_the_open_error() = runBlocking {
        var calls = 0
        val outcome = PreviewSessions.open(
            path = SourcePath.ENCODED,
            fdProvider = { calls++; -1 },
            engine = engine,
        )

        assertTrue(outcome.isFailure)
        assertEquals(
            "Preview failed (stage: open): source unavailable — recovery: reopen the clip",
            outcome.exceptionOrNull()?.message,
        )
        assertEquals(1, calls)
    }

    @Test
    fun factory_encoded_path_opens_the_encoded_session() = runBlocking {
        val developer = FakeDeveloper(result = frame(4, 4) { 0xFF010101.toInt() })
        val fake = fakeSource(pts = longArrayOf(0, 33_333), developer = developer)
        val outcome = PreviewSessions.open(
            path = SourcePath.ENCODED,
            fdProvider = { 7 },
            engine = engine,
            newEncodedSource = { fake },
        )

        val session = outcome.getOrThrow()
        assertEquals(2, session.frames)
        session.close()
    }

    @Test
    fun factory_mcraw_path_fails_cleanly_without_the_native_lib() = runBlocking {
        // The MCRAW branch delegates to PreviewSession.open, which gates on
        // the native lib (absent on the JVM): the factory must surface that
        // failure without throwing — the same gate the editor's canRender
        // applies on device.
        val outcome = PreviewSessions.open(
            path = SourcePath.MCRAW,
            fdProvider = { 7 },
            engine = engine,
        )

        assertTrue(outcome.isFailure)
        assertEquals(
            NativeSource.nativeUnavailableMessage,
            outcome.exceptionOrNull()?.message,
        )
    }

    // ---- fakes (the EncodedSourceTest patterns, minimal for these paths) ----

    /** A reader that reports an index and lands seeks at the first sync sample. */
    private class FakeReader(
        private val samples: List<EncodedSample>,
        private val openOutcome: EncodedOpenOutcome? = null,
    ) : EncodedTrackReader {

        var released = false
        private var cursor = -1

        override fun open(fd: Int): EncodedOpenOutcome {
            if (openOutcome != null) return openOutcome
            cursor = samples.size
            return EncodedOpenOutcome.Opened(
                format = testFormat(),
                index = EncodedSampleIndex(
                    ptsUs = LongArray(samples.size) { samples[it].ptsUs },
                    sync = BooleanArray(samples.size) { samples[it].sync },
                ),
                sampleRate = 48_000,
                channels = 2,
            )
        }

        override fun seekTo(ptsUs: Long) {
            check(cursor >= 0) { "open the clip first" }
            cursor = samples
                .indexOfFirst { it.ptsUs >= ptsUs }
                .let { if (it < 0) samples.size else it }
        }

        override fun readSample(): EncodedSample? =
            if (cursor < 0 || cursor >= samples.size) null else samples[cursor++]

        override fun release() {
            released = true
        }
    }

    /** A decoder that emits each queued sample immediately, in queue order. */
    private class FakeDecoder : EncodedFrameDecoder {

        var released = false
        private val pending = ArrayDeque<EncodedSample>()

        override fun configure(format: EncodedVideoFormat): String? = null

        override fun flush() {
            pending.clear()
        }

        override fun queueSample(sample: EncodedSample) {
            pending.addLast(sample)
        }

        override fun queueEos() {}

        override fun dequeueFrame(timeoutUs: Long): YuvPlanes? {
            val s = pending.removeFirstOrNull() ?: return null
            return planes(s.ptsUs)
        }

        override fun release() {
            released = true
        }

        private fun planes(ptsUs: Long): YuvPlanes =
            YuvPlanes(
                ptsUs = ptsUs,
                width = 4,
                height = 4,
                y = YuvPlane(ByteArray(16), 0, 4, 1),
                u = YuvPlane(ByteArray(4), 0, 2, 1),
                v = YuvPlane(ByteArray(4), 0, 2, 1),
            )
    }

    /** The develop boundary, recording what it was handed. */
    private class FakeDeveloper(
        var result: IntArray? = null,
        var errorCode: Int = 0,
        var errorStage: Int = 0,
    ) : EncodedDeveloper {

        val frames = ArrayList<YuvFrameK>()
        val grades = ArrayList<GradeK>()

        override fun develop(
            frame: YuvFrameK,
            isBt709: Boolean,
            orientationDeg: Int,
            grade: GradeK,
        ): IntArray? {
            frames.add(frame)
            grades.add(grade)
            return result
        }

        override fun code(): Int = errorCode

        override fun stage(): Int = errorStage

        override fun detail(): String? = null
    }

    /** A reader whose open records the calling thread's name (thread checks). */
    private class ThreadNameReader(
        private val onOpen: (String) -> Unit,
    ) : EncodedTrackReader {

        override fun open(fd: Int): EncodedOpenOutcome {
            onOpen(Thread.currentThread().name)
            return EncodedOpenOutcome.Opened(
                format = testFormat(),
                index = EncodedSampleIndex(longArrayOf(0), booleanArrayOf(true)),
                sampleRate = 48_000,
                channels = 2,
            )
        }

        override fun seekTo(ptsUs: Long) {}

        override fun readSample(): EncodedSample? = null

        override fun release() {}
    }

    // ---- helpers ----

    private fun fakeSource(
        pts: LongArray,
        developer: EncodedDeveloper = FakeDeveloper(),
    ): EncodedSource = EncodedSource(
        reader = FakeReader(samples(pts)),
        decoder = FakeDecoder(),
        developer = developer,
    )

    /** The develop tail's [w, h, pixels...] contract. */
    private fun frame(w: Int, h: Int, pixel: (Int) -> Int): IntArray =
        intArrayOf(w, h) + IntArray(w * h) { pixel(it) }

    private fun samples(
        ptsUs: LongArray,
        syncAt: IntArray = intArrayOf(0),
    ): List<EncodedSample> = ptsUs.mapIndexed { i, pts ->
        EncodedSample(ByteArray(4) { it.toByte() }, pts, i in syncAt)
    }
}

/** Shared clip format for the fakes (nested classes reach file-private decls). */
private fun testFormat(): EncodedVideoFormat = EncodedVideoFormat(
    mime = "video/avc",
    width = 4,
    height = 4,
    orientationDeg = 0,
    isBt709 = true,
    codecConfig = listOf(byteArrayOf(1, 2)),
)

/**
 * ArgbScale tests: the pure tier downscaler for the encoded path (the
 * native MCRAW pipeline scales internally; the encoded engine develops full
 * resolution, so the session scales in Kotlin).
 */
class ArgbScaleTest {

    @Test
    fun within_maxdim_passes_through_at_own_size() {
        val src = IntArray(6) { 0xFF0000FF.toInt() }
        val out = ArgbScale.fit(src, width = 3, height = 2, maxDim = 1280)

        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertEquals(src.toList(), out.argb.toList())
    }

    @Test
    fun non_positive_maxdim_passes_through() {
        val src = IntArray(6) { 0xFF0000FF.toInt() }
        val out = ArgbScale.fit(src, width = 3, height = 2, maxDim = 0)

        assertEquals(3, out.width)
        assertEquals(2, out.height)
    }

    @Test
    fun exact_half_scale_averages_each_box() {
        // 2x2 -> 1x1: the output pixel is the per-channel integer mean of
        // all four pixels (r = (16+32+48+64)/4 = 40 = 0x28).
        val src = intArrayOf(
            0xFF100000.toInt(), 0xFF200000.toInt(),
            0xFF300000.toInt(), 0xFF400000.toInt(),
        )
        val out = ArgbScale.fit(src, width = 2, height = 2, maxDim = 1)

        assertEquals(1, out.width)
        assertEquals(1, out.height)
        assertEquals(0xFF280000.toInt(), out.argb[0])
    }

    @Test
    fun solid_frame_stays_solid_at_any_scale() {
        val src = IntArray(12 * 8) { 0xFF3C7BAA.toInt() }
        val out = ArgbScale.fit(src, width = 12, height = 8, maxDim = 4)

        assertEquals(4, out.width)
        assertEquals(3, out.height)
        assertTrue(out.argb.all { it == 0xFF3C7BAA.toInt() })
    }

    @Test
    fun portrait_caps_the_height_as_the_long_edge() {
        val src = IntArray(8 * 16) { 0xFF010101.toInt() }
        val out = ArgbScale.fit(src, width = 8, height = 16, maxDim = 4)

        assertEquals(4, out.height)
        assertEquals(2, out.width)
    }

    @Test
    fun mismatched_buffer_is_rejected() {
        var threw = false
        try {
            ArgbScale.fit(IntArray(5), width = 3, height = 2, maxDim = 1280)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
