package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production GPU develop session's Kotlin seam (M3 commit a + the M5
 * encoder-surface pair).
 *
 * HONESTY: the session itself (GlesDevelopSession.cpp) is Android-only —
 * EGL/GLES headers exist solely in the NDK, so the JVM suite cannot compile
 * or run it. What the JVM CAN lock, and what this file does lock:
 *
 *   1. every wrapper's unavailable guard — on the JVM (and on any build
 *      without the .so) each entry point throws IllegalStateException with
 *      the standard recovery message, never UnsatisfiedLinkError leaking
 *      out of the seam. This is the same contract jniGlesFragmentParity
 *      rides and the same one the commit-b dispatcher will catch;
 *   2. the render signatures carry the fragment-covered grade controls —
 *      the five base ones (exposure, highlights, highlight
 *      reconstruction, vignetting, tonemap) plus the two spatial-NR
 *      stages' raw values since the spatial-NR port (chroma denoise,
 *      noise reduction — stages 7/8, the multi-pass fragment chain) plus
 *      the two unsharp stages' raw values since the sharpness/detail
 *      port (sharpness, detail — stages 21/22, the four-pass unsharp
 *      chain, threaded with NO clamp because the CPU's amount mapping
 *      makes negative values valid softening) plus the ten pointwise
 *      stages' raw values since the pointwise port
 *      (black point, white point, shadows, midtones, contrast,
 *      temperature, tint, saturation, the color wheel, split tone, with
 *      asShot as the 16/17 gate's input) plus — since the stage-24 GPU
 *      port — the seven LUT controls (the resolved .cube path, the [0,1]
 *      strength, and the five interpretation ordinals, -1 = legacy)
 *      beside the plumbing values.
 *      That narrow signature is the second lock against uncovered grades
 *      (the Kotlin coverage gate is the first): a stage the fragment pass
 *      does not cover — temporal NR strength, a tone-band knob — cannot
 *      even be expressed into the session, so widening this signature
 *      must be a deliberate act that edits this test too;
 *   3. the encoder-surface pair's shapes: open carries the fourteen
 *      shader sources (the four the parity harness rides, the M5 blit
 *      source, and — since the sharpness/detail port — the SPLIT
 *      three-way develop pair plus the four unsharp fragments plus
 *      the three NR fragments), and renderFrameToEncoder adds ptsUs (the
 *      encoder Surface's presentation time) and rotationDeg (the frame's
 *      effective orientation — the blit's uRotation fold) to the
 *      readback render's thirty-seven.
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
                GlesShaderSources.DEVELOP_FRAGMENT_PRE,
                GlesShaderSources.DEVELOP_FRAGMENT_POST,
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
                GlesShaderSources.SHARPNESS_H,
                GlesShaderSources.SHARPNESS_V,
                GlesShaderSources.DETAIL_H,
                GlesShaderSources.DETAIL_V,
                GlesShaderSources.NR_CHROMA_H,
                GlesShaderSources.NR_CHROMA_V,
                GlesShaderSources.NR_LUMA,
                GlesShaderSources.STORE_FRAGMENT,
                GlesShaderSources.BLIT_FRAGMENT,
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
            NativeSource.jniDevelopSessionClose(1L)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun set_encoder_surface_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniDevelopSessionSetEncoderSurface(1L, null)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun render_frame_to_encoder_throws_the_unavailable_message_without_the_native_lib() {
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniDevelopSessionRenderFrameToEncoder(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
                ptsUs = 0L,
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
                rotationDeg = 0,
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun set_sync_refine_throws_the_unavailable_message_without_the_native_lib() {
        // The M5 sync-refine toggle's wrapper: unlike the two build gates
        // (nativeSetStabDebugLog / nativeSetExportTiming, companion-init
        // private) this one has a UI caller — the stab sheet's switch —
        // so the unavailable guard is the ONLY thing standing between the
        // switch and an UnsatisfiedLinkError mid-compose. The JVM always
        // runs this path (the .so never loads on the host).
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniSetSyncRefine(true)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    // ---- the signature locks (the second lock against uncovered grades) ---

    private fun method(name: String): java.lang.reflect.Method {
        // Internal members are public in JVM bytecode but MANGLED with a
        // build-variant suffix (e.g. jniDevelopSessionRenderFrame$app_debug),
        // so match the plain name plus the mangling separator — that also
        // disambiguates the M5 pair, where the readback render's name is a
        // strict prefix of jniDevelopSessionRenderFrameToEncoder's: only
        // "jniDevelopSessionRenderFrame$" matches the readback, only
        // "jniDevelopSessionRenderFrameToEncoder$" the encoder blit.
        val methods = NativeSource.Companion::class.java.declaredMethods
        val matches = methods.filter { it.name.startsWith(name + "$") }
        assertEquals("exactly one $name wrapper", 1, matches.size)
        return matches.first()
    }

    @Test
    fun render_carries_exactly_the_fragment_covered_controls() {
        // 37 parameters: sessionHandle, clipHandle, frameIndex (the three
        // plumbing values) + chromaDenoise, noiseReduction (the two
        // spatial-NR stages' raw values since the spatial-NR port — stages
        // 7/8, the multi-pass fragment chain behind uChromaDenoise/
        // uNoiseReduction) + sharpness, detail (the two unsharp stages'
        // raw values since the sharpness/detail port — stages 21/22, the
        // four-pass unsharp chain behind uSharpness/uDetail, threaded
        // VERBATIM with no clamp because the CPU's amount mapping makes
        // negative values valid softening) + exposureEv, highlights,
        // highlightReconstruction, keepVignetting, tonemap (the five base
        // fragment-covered grade knobs) + the ten pointwise stages' raw
        // values since the pointwise port (blackPoint, whitePoint,
        // shadows, midtones, contrast, tempK, tint, saturation, the six
        // color-wheel offsets, the three split-tone values, with asShot as
        // the 16/17 gate's input) + the seven LUT controls since the
        // stage-24 GPU port (lutPath, lutStrength and the five
        // interpretation ordinals — the SAME layout the CPU seam's develop
        // call threads, -1 = legacy). Temporal NR and tone-band controls
        // remain deliberately absent — those stages are NOT
        // fragment-covered, so a grade using any of them must take the CPU
        // path wholesale; adding such a parameter here widens the session's
        // promise and must fail this test until the coverage gate is
        // taught the new stage.
        val render = method("jniDevelopSessionRenderFrame")
        assertEquals(37, render.parameterCount)
    }

    @Test
    fun render_to_encoder_adds_only_the_presentation_time() {
        // 39 parameters: the readback render's thirty-seven (sessionHandle,
        // clipHandle, frameIndex + the two spatial-NR stages' raw values +
        // the two unsharp stages' raw values + the fragment-covered grade
        // controls + the seven LUT controls)
        // plus ptsUs (the encoder Surface's presentation time, which the
        // readback path never needs — the CPU path carries pts on
        // writeVideoFrameRgb) plus rotationDeg (the surface-gpu vertical
        // deliverable: the frame's effective orientation, applied by the
        // blit's uRotation fold — 0/90/180/270, no default value so this
        // reflection lock sees exactly one wrapper; the LUT controls sit
        // BEFORE it, keeping rotationDeg the tail parameter). The blit
        // reuses the SAME develop program family (the NR multi-pass chain,
        // the four-pass unsharp chain, and the LUT 3D-texture sampler
        // included), so the grade controls are the same fragment-covered
        // set; a FORTIETH parameter would widen the encoder blit's promise
        // beyond the covered-grade contract and must fail here.
        val renderToEncoder = method("jniDevelopSessionRenderFrameToEncoder")
        assertEquals(39, renderToEncoder.parameterCount)
    }

    @Test
    fun set_encoder_surface_takes_the_window_and_the_window_only() {
        // 2 parameters: sessionHandle + the Surface as Any? (the
        // JVM-loadability rule — android.view.Surface is not on the JVM
        // classpath). The detach call is the same pair with a null window
        // (a detach always processes, even on a latched-unhealthy session),
        // so no separate parameter can appear here without breaking the
        // mid-stream refusal cascade the dispatcher rides.
        val setEncoderSurface = method("jniDevelopSessionSetEncoderSurface")
        assertEquals(2, setEncoderSurface.parameterCount)
    }

    @Test
    fun open_carries_exactly_the_fourteen_shader_sources() {
        // The session compiles the Kotlin single-source-of-truth texts —
        // the four sources jniGlesFragmentParity rides plus the M5 blit
        // source (the encoder-Surface copy pass), all host-validated by
        // GlesShaderValidator before the device ever sees them. Since the
        // spatial-NR port the fused develop fragment is the SPLIT pair
        // (pre/post) plus the three NR fragments (chroma-h, chroma-v,
        // luma); since the sharpness/detail port the split is the
        // THREE-WAY pair (pre / pointwise post / tail) plus the four
        // unsharp fragments — fourteen sources total. A fifteenth source
        // would be a new program, which is a different session.
        val open = method("jniDevelopSessionOpen")
        assertEquals(14, open.parameterCount)
    }

    @Test
    fun the_fourteen_sources_are_the_validated_single_source_of_truth() {
        // The exact constants open() will be called with pass the host
        // validator under their own kinds — so the text the production
        // session compiles on device is the text the JVM checked. Locked
        // here so the dispatcher cannot quietly swap in an unvalidated
        // variant (the blit source joined the set in M5; the split pair
        // and the three NR fragments joined with the spatial-NR port; the
        // tail and the four unsharp fragments joined with the
        // sharpness/detail port).
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
                GlesShaderSources.BLIT_FRAGMENT,
                GlesShaderValidator.Kind.BLIT_FRAGMENT,
            ),
        )
    }
}
