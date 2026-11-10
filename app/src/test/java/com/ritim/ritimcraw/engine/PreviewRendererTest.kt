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
        val grade = GradeK(
            exposureEv = 1.2f, highlights = -30f, tempK = 4300f, tint = 7f,
            saturation = 40f, blackPoint = -25f, whitePoint = 60f,
            shadows = -35f, midtones = 12f, contrast = 55f,
        )
        val args = GradeMapping.toNative(grade)
        assertEquals(1.2f, args.exposureEv, 0f)
        assertEquals(-30f, args.highlights, 0f)
        assertEquals(4300f, args.tempK, 0f)
        assertEquals(7f, args.tint, 0f)
        assertEquals(40f, args.saturation, 0f)
        assertEquals(-25f, args.blackPoint, 0f)
        assertEquals(60f, args.whitePoint, 0f)
        assertEquals(-35f, args.shadows, 0f)
        assertEquals(12f, args.midtones, 0f)
        assertEquals(55f, args.contrast, 0f)
    }

    @Test
    fun grade_mapping_defaults_match_gradek_defaults() {
        val args = GradeMapping.toNative(GradeK())
        assertEquals(0f, args.exposureEv, 0f)
        assertEquals(0f, args.highlights, 0f)
        assertEquals(5600f, args.tempK, 0f)
        assertEquals(0f, args.tint, 0f)
        // The six tone/color controls default to neutral — a default grade
        // is a no-op against the ungraded pipeline (locked at the C++ level
        // by the golden bit-identity test).
        assertEquals(0f, args.saturation, 0f)
        assertEquals(0f, args.blackPoint, 0f)
        assertEquals(0f, args.whitePoint, 0f)
        assertEquals(0f, args.shadows, 0f)
        assertEquals(0f, args.midtones, 0f)
        assertEquals(0f, args.contrast, 0f)
    }

    @Test
    fun grade_mapping_survives_round_trip() {
        val grade = GradeK(
            exposureEv = -2.5f, highlights = 99f, tempK = 9000f, tint = -12f,
            saturation = -80f, blackPoint = 45f, whitePoint = -70f,
            shadows = 90f, midtones = -33f, contrast = 100f,
        )
        val args = GradeMapping.toNative(grade)
        val back = GradeK(
            exposureEv = args.exposureEv,
            highlights = args.highlights,
            tempK = args.tempK,
            tint = args.tint,
            saturation = args.saturation,
            blackPoint = args.blackPoint,
            whitePoint = args.whitePoint,
            shadows = args.shadows,
            midtones = args.midtones,
            contrast = args.contrast,
        )
        assertEquals(grade.exposureEv, back.exposureEv, 0f)
        assertEquals(grade.highlights, back.highlights, 0f)
        assertEquals(grade.tempK, back.tempK, 0f)
        assertEquals(grade.tint, back.tint, 0f)
        assertEquals(grade.saturation, back.saturation, 0f)
        assertEquals(grade.blackPoint, back.blackPoint, 0f)
        assertEquals(grade.whitePoint, back.whitePoint, 0f)
        assertEquals(grade.shadows, back.shadows, 0f)
        assertEquals(grade.midtones, back.midtones, 0f)
        assertEquals(grade.contrast, back.contrast, 0f)
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
            RenderDebounce.shouldRender(lastRequestMs = 10_000, nowMs = 10_100),
        )
    }

    @Test
    fun request_at_window_edge_renders() {
        assertTrue(
            RenderDebounce.shouldRender(lastRequestMs = 10_000, nowMs = 10_120),
        )
    }

    @Test
    fun window_is_120ms() {
        // 300 -> 120 (owner request 2026-11-10 section 1): the interactive
        // slider path renders low-latency during drags, so the debounced
        // settle can afford a shorter window.
        assertEquals(120L, RenderDebounce.WINDOW_MS)
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
