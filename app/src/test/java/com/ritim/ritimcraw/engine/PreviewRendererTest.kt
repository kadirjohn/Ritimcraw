package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Grade mapping + render scheduling tests for the preview path. The pure
 * pieces (GradeMapping, RenderDebounce) and the unavailable-engine fallback
 * are JVM-testable without librawedit_jni.so — NativeSource.nativeAvailable
 * is false in unit tests, so open() must fail cleanly with the unavailable
 * message.
 */
class PreviewRendererTest {

    // ---- GradeK -> native args ----

    @Test
    fun grade_mapping_is_field_for_field() {
        val grade = GradeK(exposureEv = 1.2f, highlights = -30f, tempK = 4300f, tint = 7f)
        val args = GradeMapping.toNative(grade)
        assertEquals(1.2f, args.exposureEv, 0f)
        assertEquals(-30f, args.highlights, 0f)
        assertEquals(4300f, args.tempK, 0f)
        assertEquals(7f, args.tint, 0f)
    }

    @Test
    fun grade_mapping_defaults_match_gradek_defaults() {
        val args = GradeMapping.toNative(GradeK())
        assertEquals(0f, args.exposureEv, 0f)
        assertEquals(0f, args.highlights, 0f)
        assertEquals(5600f, args.tempK, 0f)
        assertEquals(0f, args.tint, 0f)
    }

    @Test
    fun grade_mapping_survives_round_trip() {
        val grade = GradeK(exposureEv = -2.5f, highlights = 99f, tempK = 9000f, tint = -12f)
        val args = GradeMapping.toNative(grade)
        val back = GradeK(
            exposureEv = args.exposureEv,
            highlights = args.highlights,
            tempK = args.tempK,
            tint = args.tint,
        )
        assertEquals(grade.exposureEv, back.exposureEv, 0f)
        assertEquals(grade.highlights, back.highlights, 0f)
        assertEquals(grade.tempK, back.tempK, 0f)
        assertEquals(grade.tint, back.tint, 0f)
    }

    // ---- debounce window ----

    @Test
    fun first_request_renders_immediately() {
        // No previous request: always render.
        assertTrue(RenderDebounce.shouldRender(lastRequestMs = null, nowMs = 10_000))
    }

    @Test
    fun request_inside_window_waits() {
        assertFalse(
            RenderDebounce.shouldRender(lastRequestMs = 10_000, nowMs = 10_200),
        )
    }

    @Test
    fun request_at_window_edge_renders() {
        assertTrue(
            RenderDebounce.shouldRender(lastRequestMs = 10_000, nowMs = 10_300),
        )
    }

    @Test
    fun window_is_300ms() {
        assertEquals(300L, RenderDebounce.WINDOW_MS)
    }

    // ---- stale-result drop ----

    @Test
    fun result_of_latest_sequence_is_fresh() {
        assertTrue(RenderDebounce.isFresh(sequence = 5, latestSequence = 5))
    }

    @Test
    fun stale_result_is_dropped() {
        // A render that started before a newer request must not publish.
        assertFalse(RenderDebounce.isFresh(sequence = 4, latestSequence = 5))
    }

    @Test
    fun future_sequence_is_fresh() {
        // Defensive: a sequence newer than the latest can only mean the
        // counter moved — never drop it.
        assertTrue(RenderDebounce.isFresh(sequence = 6, latestSequence = 5))
    }

    // ---- unavailable engine fallback ----

    @Test
    fun open_fails_cleanly_without_native_lib() {
        // JVM unit tests run with NativeSource.nativeAvailable == false
        // (no librawedit_jni.so on the classpath); open must fail with the
        // unavailable message, never throw and never touch the executor.
        val engine = Executors.newSingleThreadExecutor()
        try {
            val result = PreviewRenderer.open(3, engine)
            assertTrue(result.isFailure)
            assertEquals(
                NativeSource.nativeUnavailableMessage,
                result.exceptionOrNull()?.message,
            )
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun open_failure_is_preview_render_exception() {
        val engine = Executors.newSingleThreadExecutor()
        try {
            val result = PreviewRenderer.open(-1, engine)
            assertTrue(
                result.exceptionOrNull() is PreviewRenderer.Companion.PreviewRenderException,
            )
        } finally {
            engine.shutdown()
        }
    }
}
