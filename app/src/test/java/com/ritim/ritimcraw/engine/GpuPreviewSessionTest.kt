package com.ritim.ritimcraw.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GpuPreviewSession tests (M4 commit b), against a fake [GpuPreviewDevelop]:
 * the screen-side lifecycle owner is JVM-testable through the same seam the
 * M3 export pipeline's dispatch rides — the fake records the calls so the
 * tests lock the LATCH rules (open failure stays 0; a null render returns
 * null without throwing; close is idempotent and drains through the seam)
 * without the native lib.
 *
 * HONESTY: the real boundary (NativeGpuPreviewDevelop) and the GL session
 * behind it are Android-only — the JVM suite locks the wrapper's contract
 * here and the native seam's unavailable guard + signature in
 * [NativeSourcePreviewSessionTest]. The production render itself is
 * device-test-pending (the parity is already proven; the owner's morning
 * playback run is the proof).
 */
class GpuPreviewSessionTest {

    /** Records every seam call; scripted verdicts per method. */
    private class FakeDevelop(
        var openResult: Long = 1L,
        var renderResult: PreviewFrame? = PreviewFrame(2, 2, IntArray(4)),
        var setSurfaceResult: Boolean = true,
        var openThrows: Boolean = false,
        var renderThrows: Boolean = false,
    ) : GpuPreviewDevelop {
        val opened = mutableListOf<Unit>()
        val probed = mutableListOf<Unit>()
        val parities = mutableListOf<Int>() // the fds the parity leg saw
        val surfaces = mutableListOf<Any?>()
        val rendered = mutableListOf<Long>() // the session handles rendered
        val closed = mutableListOf<Long>()
        var lastDetailValue: String? = null
        var probeResult: DoubleArray? = null
        var parityResult: DoubleArray? = null

        override suspend fun probe(): DoubleArray? {
            probed += Unit
            return probeResult
        }

        override suspend fun fragmentParity(fd: Int): DoubleArray? {
            parities += fd
            return parityResult
        }

        override suspend fun openSession(): Long {
            opened += Unit
            if (openThrows) throw IllegalStateException("seam blew up")
            return openResult
        }

        override suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean {
            surfaces += surface
            return setSurfaceResult
        }

        override suspend fun renderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            maxDim: Int,
            exposureEv: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
        ): PreviewFrame? {
            rendered += sessionHandle
            if (renderThrows) throw IllegalStateException("seam blew up")
            return renderResult
        }

        override fun closeSession(sessionHandle: Long) {
            closed += sessionHandle
        }

        override suspend fun lastDetail(): String? = lastDetailValue
    }

    // ---- open ----

    @Test
    fun open_success_latches_the_handle_and_reports_active() = runBlocking {
        val develop = FakeDevelop(openResult = 7L)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertEquals(7L, session.handle)
        assertTrue(session.active)
    }

    @Test
    fun open_failure_stays_zero_and_reports_inactive() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertEquals(0L, session.handle)
        assertFalse(session.active)
    }

    @Test
    fun open_absorbs_a_seam_throw_and_stays_zero() = runBlocking {
        val develop = FakeDevelop(openThrows = true)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertEquals(0L, session.handle)
        assertFalse(session.active)
    }

    // ---- the surface handoff ----

    @Test
    fun surface_handoff_rides_the_seam_with_the_opened_handle() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        val surface = Any()
        assertTrue(session.onSurfaceChanged(surface))
        // The lifecycle signal reached the seam exactly once, with the
        // surface the holder reported.
        assertEquals(listOf<Any?>(surface), develop.surfaces)
    }

    @Test
    fun surface_handoff_is_a_no_op_before_open() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        // surfaceCreated can fire before the session's open lands (the
        // SurfaceView composes as soon as the path is active): the handoff
        // must refuse cleanly, never hand a 0 handle to the seam.
        assertFalse(session.onSurfaceChanged(Any()))
        assertTrue(develop.surfaces.isEmpty())
    }

    @Test
    fun surface_destroy_still_processes_after_open() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertTrue(session.onSurfaceChanged(null))
        assertEquals(listOf<Any?>(null), develop.surfaces)
    }

    // ---- render ----

    @Test
    fun render_returns_the_seam_frame_with_the_opened_handle() = runBlocking {
        val frame = PreviewFrame(4, 3, IntArray(12))
        val develop = FakeDevelop(openResult = 7L, renderResult = frame)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        val out = session.render(
            clipHandle = 11L, frameIndex = 5, maxDim = 1280,
            exposureEv = 0.5f, highlightReconstruction = true,
            keepVignetting = true, tonemap = 1,
        )
        // The SAME instance flows through — the pump publishes it exactly
        // as the CPU dispatch publishes its own frame.
        assertSame(frame, out)
        // The seam saw the OPENED handle, never the screen's raw one.
        assertEquals(listOf(7L), develop.rendered)
    }

    @Test
    fun render_after_a_failed_open_returns_null_without_touching_the_seam() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                exposureEv = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
            )
        )
        assertTrue(develop.rendered.isEmpty())
    }

    @Test
    fun render_absorbs_a_seam_throw_as_the_null_fallback_verdict() = runBlocking {
        val develop = FakeDevelop(renderThrows = true)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                exposureEv = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
            )
        )
    }

    // ---- close ----

    @Test
    fun close_is_idempotent_and_closes_the_seam_exactly_once() = runBlocking {
        val develop = FakeDevelop(openResult = 7L)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        session.close()
        session.close() // the dispose path may race a fallback path's close
        assertEquals(listOf(7L), develop.closed)
        assertFalse(session.active)
    }

    @Test
    fun close_after_a_failed_open_never_touches_the_seam() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        session.close()
        assertTrue(develop.closed.isEmpty())
    }

    @Test
    fun render_after_close_returns_null_without_touching_the_seam() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        session.close()
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                exposureEv = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
            )
        )
        assertEquals(0, develop.rendered.size)
    }

    // ---- lastDetail ----

    @Test
    fun last_detail_flows_through_for_the_fallback_line() = runBlocking {
        val develop = FakeDevelop().apply { lastDetailValue = "eglCreateWindowSurface" }
        val session = GpuPreviewSession(develop)
        assertEquals("eglCreateWindowSurface", session.lastDetail())
    }

    @Test
    fun last_detail_absorbs_a_seam_throw() = runBlocking {
        val session = GpuPreviewSession(object : GpuPreviewDevelop {
            override suspend fun probe(): DoubleArray? = null
            override suspend fun fragmentParity(fd: Int): DoubleArray? = null
            override suspend fun openSession(): Long = 1L
            override suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean = true
            override suspend fun renderFrame(
                sessionHandle: Long,
                clipHandle: Long,
                frameIndex: Int,
                maxDim: Int,
                exposureEv: Float,
                highlightReconstruction: Boolean,
                keepVignetting: Boolean,
                tonemap: Int,
            ): PreviewFrame? = null
            override fun closeSession(sessionHandle: Long) {}
            override suspend fun lastDetail(): String? = throw IllegalStateException("seam blew up")
        })
        assertNull(session.lastDetail())
    }
}
