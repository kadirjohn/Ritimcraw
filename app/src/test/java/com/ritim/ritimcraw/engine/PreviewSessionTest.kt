package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SessionRules + PreviewSession tests. NativeSource.nativeAvailable is false
 * in JVM unit tests, so open must fail cleanly with the unavailable message
 * (never touching the executor), and a closed session must refuse renders
 * without touching the dead handle.
 */
class PreviewSessionTest {

    // ---- SessionRules.isActive ----

    @Test
    fun active_when_opened_and_not_closed() {
        assertTrue(SessionRules.isActive(opened = true, closed = false))
    }

    @Test
    fun inactive_when_not_yet_opened() {
        assertFalse(SessionRules.isActive(opened = false, closed = false))
    }

    @Test
    fun inactive_forever_after_close() {
        // A closed session is dead forever: the editor opens a NEW session
        // for the next clip rather than reusing one.
        assertFalse(SessionRules.isActive(opened = true, closed = true))
    }

    // ---- open without the native lib ----

    @Test
    fun open_fails_cleanly_without_native_lib() {
        val engine = Executors.newSingleThreadExecutor()
        try {
            val result = runBlocking { PreviewSession.open(3, engine) }
            assertTrue(result.isFailure)
            assertEquals(
                NativeSource.nativeUnavailableMessage,
                result.exceptionOrNull()?.message,
            )
        } finally {
            engine.shutdown()
        }
    }

    // ---- closed session refuses renders ----

    @Test
    fun closed_session_render_fails_without_touching_the_handle() {
        // A session that is closed must fail cleanly with the closed-session
        // message rather than calling into the dead handle.
        val engine = Executors.newSingleThreadExecutor()
        try {
            val session = newSession(handle = 1L, frames = 10, engine = engine)
            session.close()
            // The engine is never asked to run anything for the render: the
            // closed check precedes the submit.
            val result = runBlocking { session.render(0, GradeK(), 1280) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("session closed") == true)
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun close_is_idempotent() {
        val engine = Executors.newSingleThreadExecutor()
        try {
            val session = newSession(handle = 1L, frames = 10, engine = engine)
            session.close()
            session.close() // second close must be a no-op, never a throw
        } finally {
            engine.shutdown()
        }
    }

    /** Constructs a session via reflection (private ctor; no native lib in JVM tests). */
    private fun newSession(handle: Long, frames: Int, engine: ExecutorService): PreviewSession {
        val ctor = PreviewSession::class.java.getDeclaredConstructor(
            Long::class.java, Int::class.java, ExecutorService::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(handle, frames, engine)
    }
}
