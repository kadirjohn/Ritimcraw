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
 *   2. the render signatures carry the grade as ONE flat parcel —
 *      GRADE PARCEL (commit b): the 29 fragment-covered scalar grade
 *      fields (the five base ones plus the spatial-NR pair, the temporal
 *      pair, the unsharp pair, the ten pointwise stages' values, the
 *      color wheel, the split-tone values) cross as one FloatArray whose
 *      layout is GENERATED from tools/gen-grade-parcel.py's schema on
 *      BOTH sides of the seam (GradeParcelTest cross-checks the committed
 *      C++ header against the Kotlin offsets — the stale-mirror catch).
 *      The temporal pair rides the parcel like every other scalar and
 *      dies at the bridge, consumed by the bridge's own temporalMergedCfa
 *      BEFORE the CFA reduction, so the reduced mosaic samples the MERGED
 *      CFA — routing, not porting; the merge math stays core/Temporal.cpp's,
 *      frozen, and the session request itself never sees the pair. The
 *      parcel shape is the second lock against uncovered grades (the
 *      Kotlin coverage gate is the first): a grade field the render path
 *      does not cover cannot be expressed into the session without a
 *      schema row + a regenerate, so widening it must be a deliberate act
 *      that edits the schema and this test too;
 *   3. the encoder-surface pair's shapes: open carries the fifteen
 *      shader sources (the four the parity harness rides, the M5 blit
 *      source, the SPLIT three-way develop pair plus the four unsharp
 *      fragments plus the three NR fragments, and — since the gyro warp
 *      port — the warp fragment), and the encoder parcel door adds
 *      ptsUs (the encoder Surface's presentation time) and rotationDeg
 *      (the frame's effective orientation — the blit's uRotation fold)
 *      to the readback parcel's thirteen — the flat forty-one and
 *      forty-three parameter doors died with commit (b).
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
                GlesShaderSources.WARP,
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
    fun render_frame_parcel_throws_the_unavailable_message_without_the_native_lib() {
        // GRADE PARCEL (commit a + b): the readback render door's guard —
        // the ONLY readback wrapper since the flat door died. The wrapper
        // throws BEFORE the parcel is ever read, so the dummy parcel is an
        // all-zero FloatArray(SLOT_COUNT) — the guard fires on
        // nativeAvailable alone. The export dispatch folds Throwable to
        // its refusal path, so the guard is what stands between a no-.so
        // build and an UnsatisfiedLinkError mid-export.
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniDevelopSessionRenderFrameParcel(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
                gradeParcel = FloatArray(GradeParcel.SLOT_COUNT),
                lutPath = null,
                lutStrength = 0.0f,
                lutRole = -1,
                lutInGamut = -1,
                lutInTransfer = -1,
                lutOutGamut = -1,
                lutOutTransfer = -1,
                warpActive = false,
                warpM = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    @Test
    fun render_frame_to_encoder_parcel_throws_the_unavailable_message_without_the_native_lib() {
        // GRADE PARCEL (commit a + b): the encoder render door's guard —
        // the ONLY encoder wrapper since the flat door died. Same shape
        // as the readback twin's guard plus ptsUs and rotationDeg, the
        // two plumbing values the encoder blit adds.
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniDevelopSessionRenderFrameToEncoderParcel(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
                ptsUs = 0L,
                gradeParcel = FloatArray(GradeParcel.SLOT_COUNT),
                lutPath = null,
                lutStrength = 0.0f,
                lutRole = -1,
                lutInGamut = -1,
                lutInTransfer = -1,
                lutOutGamut = -1,
                lutOutTransfer = -1,
                warpActive = false,
                warpM = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
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

    @Test
    fun stab_warp_resolve_throws_the_unavailable_message_without_the_native_lib() {
        // The gyro warp resolver's wrapper (the GPU warp port's commit b):
        // the export dispatch catches the guard's IllegalStateException
        // and folds it to the inactive verdict, and the preview pump's
        // resolveWarp catches Throwable — so the guard is what stands
        // between a no-.so build and an UnsatisfiedLinkError mid-render.
        // The jniSetSyncRefine precedent: a wrapper with a runtime caller
        // carries its guard test, and the JVM always runs this path.
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniStabWarpResolve(
                handle = 1L,
                gyroPath = null,
                stabMode = 0,
                stabStrength = 0.0f,
                stabZoomLimit = 1.0f,
                stabFocalMm = 0.0f,
                stabTrimMs = 0.0f,
                frameIndex = 0,
                maxDim = 0,
            )
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, expected.message)
        }
    }

    // ---- the signature locks (the second lock against uncovered grades) ---

    private fun method(name: String): java.lang.reflect.Method {
        // Internal members are public in JVM bytecode but MANGLED with a
        // build-variant suffix (e.g.
        // jniDevelopSessionRenderFrameParcel$app_debug), so match the plain
        // name plus the mangling separator — that also disambiguates the
        // pair, where the readback render's name is NOT a strict prefix of
        // the encoder's but the convention still guards a future bare-name
        // collision: "jniDevelopSessionRenderFrameParcel$" matches only the
        // readback, "jniDevelopSessionRenderFrameToEncoderParcel$" only the
        // encoder blit. The GRADE PARCEL twins (commit a) once sat beside
        // the flat wrappers, and a bare-name prefix would have matched BOTH
        // — the "exactly one" assertion would have seen two.
        val methods = NativeSource.Companion::class.java.declaredMethods
        val matches = methods.filter { it.name.startsWith(name + "$") }
        assertEquals("exactly one $name wrapper", 1, matches.size)
        return matches.first()
    }

    @Test
    fun render_parcel_carries_exactly_the_parcel_shape() {
        // GRADE PARCEL (commit a + b): the readback render door's signature
        // lock — 13 parameters: sessionHandle, clipHandle, frameIndex (the
        // three plumbing values) + gradeParcel (the ONE flat FloatArray
        // carrying all 30 scalar grade fields — the layout is GENERATED
        // from tools/gen-grade-parcel.py's schema, the single source of
        // truth for both sides of the seam; GradeParcelTest cross-checks
        // the committed C++ header's constexpr slots against the Kotlin
        // offsets) + the seven LUT controls (lutPath, lutStrength, the
        // five ordinals — per-job, documented OUT of the parcel) +
        // warpActive, warpM (the caller-resolved per-frame verdict). The
        // temporal pair rides the parcel; the bridge consumes it at
        // temporalMergedCfa. Commit (b): the flat 41-parameter door died
        // with its wrapper — the parcel is the ONLY readback door, so
        // this lock is re-counted DOWN from the flat 41.
        val render = method("jniDevelopSessionRenderFrameParcel")
        assertEquals(13, render.parameterCount)
    }

    @Test
    fun render_to_encoder_parcel_carries_exactly_the_parcel_shape() {
        // GRADE PARCEL (commit a + b): the encoder render door's signature
        // lock — 15 parameters: the readback parcel door's thirteen plus
        // ptsUs (the encoder presentation time) and rotationDeg (the
        // blit's uRotation fold input, the tail parameter — the flat
        // precedent). Commit (b): the flat 43-parameter door died with
        // its wrapper — the parcel is the ONLY encoder door, so this
        // lock is re-counted DOWN from the flat 43.
        val renderToEncoder = method("jniDevelopSessionRenderFrameToEncoderParcel")
        assertEquals(15, renderToEncoder.parameterCount)
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
    fun open_carries_exactly_the_fifteen_shader_sources() {
        // The session compiles the Kotlin single-source-of-truth texts —
        // the four sources jniGlesFragmentParity rides plus the M5 blit
        // source (the encoder-Surface copy pass), all host-validated by
        // GlesShaderValidator before the device ever sees them. Since the
        // spatial-NR port the fused develop fragment is the SPLIT pair
        // (pre/post) plus the three NR fragments (chroma-h, chroma-v,
        // luma); since the sharpness/detail port the split is the
        // THREE-WAY pair (pre / pointwise post / tail) plus the four
        // unsharp fragments; since the gyro warp port the warp fragment
        // joins between the unsharp passes and the tail — fifteen
        // sources total. A sixteenth source would be a new program,
        // which is a different session.
        val open = method("jniDevelopSessionOpen")
        assertEquals(15, open.parameterCount)
    }

    @Test
    fun the_fifteen_sources_are_the_validated_single_source_of_truth() {
        // The exact constants open() will be called with pass the host
        // validator under their own kinds — so the text the production
        // session compiles on device is the text the JVM checked. Locked
        // here so the dispatcher cannot quietly swap in an unvalidated
        // variant (the blit source joined the set in M5; the split pair
        // and the three NR fragments joined with the spatial-NR port; the
        // tail and the four unsharp fragments joined with the
        // sharpness/detail port; the warp fragment joined with the gyro
        // warp port).
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
                GlesShaderSources.BLIT_FRAGMENT,
                GlesShaderValidator.Kind.BLIT_FRAGMENT,
            ),
        )
    }
}
