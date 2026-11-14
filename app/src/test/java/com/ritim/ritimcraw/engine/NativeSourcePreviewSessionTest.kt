package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPU preview session's Kotlin seam (M4 commit a).
 *
 * HONESTY: the session itself (GlesPreviewSession.cpp) is Android-only —
 * EGL/GLES headers exist solely in the NDK, so the JVM suite cannot compile
 * or run it. What the JVM CAN lock, and what this file does lock:
 *
 *   1. the wrapper quartet's unavailable guard — on the JVM (and on any build
 *      without the .so) every entry point throws IllegalStateException with
 *      the standard recovery message, never UnsatisfiedLinkError leaking
 *      out of the seam. This is the same contract the M3 seam rides and the
 *      same one the commit-b/c dispatcher will catch;
 *   2. the render signature carries EXACTLY the four covered grade controls
 *      beside the four plumbing values (session handle, clip handle, frame
 *      index, max dim). That narrow signature is the second lock against
 *      uncovered grades (the Kotlin coverage gate is the first): a stage
 *      the fragment pass does not cover cannot even be expressed into the
 *      session, so widening this signature must be a deliberate act that
 *      edits this test too. The setSurface parameter is Any? on purpose —
 *      the JVM's declaredMethods reflection resolves parameter types, and
 *      an android.view.Surface type would fail class resolution here
 *      (NoClassDefFoundError): the JVM-loadability rule.
 *
 * The math the session runs is the math the parity harness already proved
 * on device; the production render itself is device-test-pending (the
 * owner's morning playback run is the proof).
 */
class NativeSourcePreviewSessionTest {

    // ---- the unavailable guard (the JVM always runs these paths) ----------

    @Test
    fun open_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return // device/instrumented run
        try {
            NativeSource.jniPreviewSessionOpen(
                GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderSources.FULLSCREEN_VERTEX_310,
                GlesShaderSources.DEVELOP_FRAGMENT,
                GlesShaderSources.STORE_FRAGMENT,
                GlesShaderSources.WINDOW_FRAGMENT,
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun set_surface_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniPreviewSessionSetSurface(
                sessionHandle = 1L,
                surface = null,
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun render_frame_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniPreviewSessionRenderFrame(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
                maxDim = 1280,
                exposureEv = 0.0f,
                highlightReconstruction = true,
                keepVignetting = true,
                tonemap = 0,
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun close_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniPreviewSessionClose(1L)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    // ---- the signature locks (the second lock against uncovered grades) ---

    private fun method(name: String): java.lang.reflect.Method {
        // Internal members are public in JVM bytecode; match by prefix so a
        // future mangling rule cannot silently break the lookup.
        val methods = NativeSource.Companion::class.java.declaredMethods
        val matches = methods.filter { it.name.startsWith(name) }
        assertEquals("exactly one $name wrapper", 1, matches.size)
        return matches.first()
    }

    @Test
    fun render_carries_exactly_the_four_covered_controls() {
        // 8 parameters: sessionHandle, clipHandle, frameIndex, maxDim (the
        // four plumbing values) + exposureEv, highlightReconstruction,
        // keepVignetting, tonemap (the four fragment-covered grade knobs).
        // Temporal NR, tone-band, sharpening, detail and LUT controls are
        // deliberately absent — those stages are NOT fragment-covered, so a
        // grade using any of them must take the CPU path wholesale; adding
        // such a parameter here widens the session's promise and must fail
        // this test until the coverage gate is taught the new stage.
        val render = method("jniPreviewSessionRenderFrame")
        assertEquals(8, render.parameterCount)
    }

    @Test
    fun open_carries_exactly_the_five_shader_sources() {
        // The session compiles the Kotlin single-source-of-truth texts —
        // the same four sources the M3 seam rides PLUS the window pass's
        // own WINDOW_FRAGMENT (the aspect-fit letterbox draw), host-validated
        // by GlesShaderValidator before the device ever sees them. A sixth
        // source would be a new program, which is a different session.
        val open = method("jniPreviewSessionOpen")
        assertEquals(5, open.parameterCount)
    }

    @Test
    fun set_surface_carries_the_handle_and_the_surface() {
        // 2 parameters: the session handle + the surface (null = destroy).
        // The surface is Any? — the JVM-loadability rule (see the class
        // KDoc): the reflection this test suite runs over declaredMethods
        // resolves parameter types, and an android.view.Surface parameter
        // would fail class resolution on the JVM.
        val setSurface = method("jniPreviewSessionSetSurface")
        assertEquals(2, setSurface.parameterCount)
    }

    @Test
    fun the_five_sources_are_the_validated_single_source_of_truth() {
        // The exact constants open() will be called with (commit b wires
        // them) pass the host validator under their own kinds — so the text
        // the production session compiles on device is the text the JVM
        // checked. Locked here so commit b cannot quietly swap in an
        // unvalidated variant.
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.FULLSCREEN_VERTEX_310,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX_310,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.STORE_FRAGMENT,
                GlesShaderValidator.Kind.STORE_FRAGMENT,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.WINDOW_FRAGMENT,
                GlesShaderValidator.Kind.WINDOW_FRAGMENT,
            ),
        )
    }
}
