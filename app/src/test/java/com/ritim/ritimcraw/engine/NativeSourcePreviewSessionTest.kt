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
 *   2. the render signature carries the fragment-covered grade controls —
 *      the five base ones (exposure, highlights, highlight
 *      reconstruction, vignetting, tonemap — highlights joined with the
 *      stage-15 port) plus the two spatial-NR stages' raw values since
 *      the spatial-NR port (chroma denoise, noise reduction — stages 7/8,
 *      the multi-pass fragment chain) plus the two unsharp stages' raw
 *      values since the sharpness/detail port (sharpness, detail —
 *      stages 21/22, the four-pass unsharp chain, threaded with NO clamp
 *      because the CPU's amount mapping makes negative values valid
 *      softening) plus the ten pointwise stages' raw
 *      values since the pointwise port (black point, white point,
 *      shadows, midtones, contrast, temperature, tint, saturation, the
 *      color wheel, split tone, with asShot as the 16/17 gate's input)
 *      plus — since the stage-24 GPU port — the seven LUT controls (the
 *      resolved .cube path, the [0,1] strength, and the five
 *      interpretation ordinals, -1 = legacy) beside the four plumbing
 *      values (session handle, clip handle, frame index, max dim). That
 *      narrow signature is the second lock against
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
                GlesShaderSources.DEVELOP_FRAGMENT_PRE,
                GlesShaderSources.DEVELOP_FRAGMENT_POST,
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
                GlesShaderSources.SHARPNESS_H,
                GlesShaderSources.SHARPNESS_V,
                GlesShaderSources.DETAIL_H,
                GlesShaderSources.DETAIL_V,
                GlesShaderSources.WARP,
                GlesShaderSources.NR_CHROMA_H,
                GlesShaderSources.NR_CHROMA_V,
                GlesShaderSources.NR_LUMA,
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
                chromaDenoise = 0.0f,
                noiseReduction = 0.0f,
                sharpness = 0.0f,
                detail = 0.0f,
                exposureEv = 0.0f,
                highlights = 0.0f,
                blackPoint = 0.0f,
                whitePoint = 0.0f,
                shadows = 0.0f,
                midtones = 0.0f,
                contrast = 0.0f,
                tempK = 5600.0f,
                tint = 0.0f,
                saturation = 0.0f,
                asShot = true,
                wheelShadowX = 0.0f,
                wheelShadowY = 0.0f,
                wheelMidX = 0.0f,
                wheelMidY = 0.0f,
                wheelHighX = 0.0f,
                wheelHighY = 0.0f,
                splitShadowHue = -1.0f,
                splitHighlightHue = -1.0f,
                splitBalance = 0.0f,
                highlightReconstruction = true,
                keepVignetting = true,
                tonemap = 0,
                lutPath = null,
                lutStrength = 0.0f,
                lutRole = -1,
                lutInGamut = -1,
                lutInTransfer = -1,
                lutOutGamut = -1,
                lutOutTransfer = -1,
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

    @Test
    fun set_preview_stats_enabled_throws_the_unavailable_message_without_the_native_lib() {
        // The trace-dims fix's A/B toggle rides the jniSetSyncRefine
        // runtime-toggle pattern: on the JVM the guard throws the
        // standard recovery message, never UnsatisfiedLinkError.
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniSetPreviewStatsEnabled(enabled = false)
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
    fun render_carries_exactly_the_fragment_covered_controls() {
        // 38 parameters: sessionHandle, clipHandle, frameIndex, maxDim (the
        // four plumbing values) + chromaDenoise, noiseReduction (the two
        // spatial-NR stages' raw values since the spatial-NR port — stages
        // 7/8, the multi-pass fragment chain behind uChromaDenoise/
        // uNoiseReduction) + sharpness, detail (the two unsharp stages'
        // raw values since the sharpness/detail port — stages 21/22, the
        // four-pass unsharp chain behind uSharpness/uDetail, threaded
        // VERBATIM with no clamp because the CPU's amount mapping makes
        // negative values valid softening) + exposureEv, highlights,
        // highlightReconstruction, keepVignetting, tonemap (the five base
        // fragment-covered grade knobs — highlights joined with the
        // stage-15 port) + the ten pointwise stages' raw values since the
        // pointwise port (blackPoint, whitePoint, shadows, midtones,
        // contrast, tempK, tint, saturation, the six color-wheel offsets,
        // the three split-tone values, with asShot as the 16/17 gate's
        // input) + the seven LUT controls since the stage-24 GPU port
        // (lutPath, lutStrength and the five interpretation ordinals — the
        // SAME layout the CPU seam threads, -1 = legacy). Temporal NR and
        // tone-band controls are deliberately absent — those stages are
        // NOT fragment-covered, so a grade using any of them must take the
        // CPU path wholesale; adding such a parameter here widens the
        // session's promise and must fail this test until the coverage
        // gate is taught the new stage.
        val render = method("jniPreviewSessionRenderFrame")
        assertEquals(38, render.parameterCount)
    }

    @Test
    fun open_carries_exactly_the_fifteen_shader_sources() {
        // The session compiles the Kotlin single-source-of-truth texts —
        // the same four sources the M3 seam rides PLUS the window pass's
        // own WINDOW_FRAGMENT (the aspect-fit letterbox draw), host-validated
        // by GlesShaderValidator before the device ever sees them. Since
        // the spatial-NR port the fused develop fragment is the SPLIT pair
        // (pre/post) plus the three NR fragments (chroma-h, chroma-v,
        // luma); since the sharpness/detail port the split is the
        // THREE-WAY pair (pre / pointwise post / tail) plus the four
        // unsharp fragments; since the gyro warp port the warp fragment
        // joins between the unsharp passes and the tail — fifteen
        // sources total. A sixteenth source would be a new program,
        // which is a different session.
        val open = method("jniPreviewSessionOpen")
        assertEquals(15, open.parameterCount)
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
    fun set_preview_stats_enabled_carries_exactly_the_one_boolean() {
        // The stats-readback toggle's signature lock (the trace-dims fix,
        // 2026-11-14): ONE Boolean. The toggle is the A/B instrument for
        // the synchronous readback's cost — widening it (e.g. a pack-dims
        // parameter) changes the measurement contract and must be a
        // deliberate act that edits this test too.
        val setStats = method("jniSetPreviewStatsEnabled")
        assertEquals(1, setStats.parameterCount)
    }

    @Test
    fun the_fifteen_sources_are_the_validated_single_source_of_truth() {
        // The exact constants open() will be called with (commit b wires
        // them) pass the host validator under their own kinds — so the text
        // the production session compiles on device is the text the JVM
        // checked. Locked here so commit b cannot quietly swap in an
        // unvalidated variant (the split pair and the three NR fragments
        // joined the set with the spatial-NR port; the tail and the four
        // unsharp fragments joined with the sharpness/detail port; the warp
        // fragment joined with the gyro warp port).
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
                GlesShaderSources.DEVELOP_FRAGMENT_PRE,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_PRE,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT_POST,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_TAIL,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.SHARPNESS_H,
                GlesShaderValidator.Kind.SHARPNESS_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.SHARPNESS_V,
                GlesShaderValidator.Kind.SHARPNESS_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DETAIL_H,
                GlesShaderValidator.Kind.DETAIL_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DETAIL_V,
                GlesShaderValidator.Kind.DETAIL_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.WARP,
                GlesShaderValidator.Kind.WARP,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_CHROMA_H,
                GlesShaderValidator.Kind.NR_CHROMA_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_CHROMA_V,
                GlesShaderValidator.Kind.NR_CHROMA_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_LUMA,
                GlesShaderValidator.Kind.NR_LUMA,
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
