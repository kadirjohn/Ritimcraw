package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production GPU develop session's Kotlin seam (M3 commit a).
 *
 * HONESTY: the session itself (GlesDevelopSession.cpp) is Android-only —
 * EGL/GLES headers exist solely in the NDK, so the JVM suite cannot compile
 * or run it. What the JVM CAN lock, and what this file does lock:
 *
 *   1. the wrapper trio's unavailable guard — on the JVM (and on any build
 *      without the .so) every entry point throws IllegalStateException with
 *      the standard recovery message, never UnsatisfiedLinkError leaking
 *      out of the seam. This is the same contract jniGlesFragmentParity
 *      rides and the same one the commit-b dispatcher will catch;
 *   2. the render signature carries EXACTLY the four covered grade controls
 *      (exposure, highlight reconstruction, vignetting, tonemap) beside the
 *      three handles/indices. That narrow signature is the second lock
 *      against uncovered grades (the Kotlin coverage gate is the first): a
 *      stage the fragment pass does not cover — temporal NR strength, a
 *      tone-band knob, a LUT path — cannot even be expressed into the
 *      session, so widening this signature must be a deliberate act that
 *      edits this test too.
 *
 * The math the session runs is the math the parity harness already proved
 * on device; the production render itself is device-test-pending (the
 * owner's covered-grade GPU export is the proof).
 */
class NativeSourceDevelopSessionTest {

    // ---- the unavailable guard (the JVM always runs these paths) ----------

    @Test
    fun open_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return // device/instrumented run
        try {
            NativeSource.jniDevelopSessionOpen(
                GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderSources.FULLSCREEN_VERTEX_310,
                GlesShaderSources.DEVELOP_FRAGMENT,
                GlesShaderSources.STORE_FRAGMENT,
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
            NativeSource.jniDevelopSessionRenderFrame(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
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
            NativeSource.jniDevelopSessionClose(1L)
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
        // 7 parameters: sessionHandle, clipHandle, frameIndex (the three
        // plumbing values) + exposureEv, highlightReconstruction,
        // keepVignetting, tonemap (the four fragment-covered grade knobs).
        // Temporal NR, tone-band, sharpening, detail and LUT controls are
        // deliberately absent — those stages are NOT fragment-covered, so a
        // grade using any of them must take the CPU path wholesale; adding
        // such a parameter here widens the session's promise and must fail
        // this test until the coverage gate is taught the new stage.
        val render = method("jniDevelopSessionRenderFrame")
        assertEquals(7, render.parameterCount)
    }

    @Test
    fun open_carries_exactly_the_four_shader_sources() {
        // The session compiles the Kotlin single-source-of-truth texts —
        // the same four sources jniGlesFragmentParity rides, host-validated
        // by GlesShaderValidator before the device ever sees them. A fifth
        // source would be a new program, which is a different session.
        val open = method("jniDevelopSessionOpen")
        assertEquals(4, open.parameterCount)
    }

    @Test
    fun the_four_sources_are_the_validated_single_source_of_truth() {
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
    }
}
