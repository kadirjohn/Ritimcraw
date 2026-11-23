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
 *   2. the render signature carries the grade as ONE flat parcel —
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
 *      that edits the schema and this test too. The setSurface parameter
 *      is Any? on purpose — the JVM's declaredMethods reflection resolves
 *      parameter types, and an android.view.Surface type would fail class
 *      resolution here (NoClassDefFoundError): the JVM-loadability rule.
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
    fun render_frame_parcel_throws_the_unavailable_message_without_the_native_lib() {
        // GRADE PARCEL (commit a + b): the preview render door's guard —
        // the ONLY render wrapper since the flat door died. The wrapper
        // throws BEFORE the parcel is ever read, so the dummy parcel is an
        // all-zero FloatArray(SLOT_COUNT) — the guard fires on
        // nativeAvailable alone. The pump folds Throwable to a null frame
        // (the CPU fallback), so the guard is what stands between a no-.so
        // build and an UnsatisfiedLinkError mid-pump.
        if (NativeSource.nativeAvailable) return
        try {
            NativeSource.jniPreviewSessionRenderFrameParcel(
                sessionHandle = 1L,
                clipHandle = 1L,
                frameIndex = 0,
                maxDim = 1280,
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
                statsMode = PreviewStatsMode.SYNC.native,
                cropActive = false,
                cropParams = GpuCropMap.NEUTRAL_CROP_PARAMS,
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
        // future mangling rule cannot silently break the lookup. The prefix
        // is name + "$" (the mangling delimiter), NOT the bare name: the
        // GRADE PARCEL twins (commit a) once sat beside the flat wrappers,
        // and a bare-name prefix would have matched BOTH — the "exactly
        // one" assertion would have seen two. The develop-session test's
        // helper already used this convention.
        val methods = NativeSource.Companion::class.java.declaredMethods
        val matches = methods.filter { it.name.startsWith(name + "$") }
        assertEquals("exactly one $name wrapper", 1, matches.size)
        return matches.first()
    }

    @Test
    fun render_parcel_carries_exactly_the_parcel_shape() {
        // GRADE PARCEL (commit a + b): the preview render door's signature
        // lock — 17 parameters: sessionHandle, clipHandle, frameIndex,
        // maxDim (the four plumbing values) + gradeParcel (the ONE flat
        // FloatArray carrying all 29 scalar grade fields — the layout is
        // GENERATED from tools/gen-grade-parcel.py's schema, both sides of
        // the seam; GradeParcelTest cross-checks the committed C++ header's
        // constexpr slots against the Kotlin offsets) + the seven LUT
        // controls (lutPath, lutStrength, the five ordinals — per-job,
        // documented OUT of the parcel) + warpActive, warpM (the
        // caller-resolved per-frame verdict) + statsMode (A1 async stats,
        // 2026-11-23: the per-request readback mode's native int)
        // + cropActive, cropParams (A2 GPU crop/straighten, 2026-11-23:
        // APPEND-ONLY after statsMode — the pair rides LAST; the params
        // are the mapRect'ed rect fractions + the raw straighten angle,
        // GpuCropMap.requestCrop's parcel). Commit (b): the flat
        // 42-parameter door died with its wrapper — the parcel is the ONLY
        // render door, so this lock is re-counted DOWN from the flat 42.
        val render = method("jniPreviewSessionRenderFrameParcel")
        assertEquals(17, render.parameterCount)
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
    fun window_probe_gate_is_the_private_unmangled_one_boolean_external() {
        // The window probe gate's signature + naming lock (white-window
        // round 2, 2026-11-15): ONE Boolean, the nativeSetStabDebugLog
        // companion-init pattern — a PRIVATE external with NO wrapper
        // (the companion init is the only caller). THE NAMING RULE is
        // the lock: private declarations do NOT get the $app_debug
        // friend-module suffix, so the JVM name is the PLAIN name that
        // matches the JNI symbol in jni_bridge.cpp in every build type —
        // the exact-name lookup below finds it, while the internal
        // wrappers' prefix+"$" lookup above would find nothing. Making
        // the declaration non-private would mangle it
        // (nativeSetPreviewWindowProbe$app_debug), the lookup would find
        // zero methods and this test would fail — the same failure the
        // device would show as an UnsatisfiedLinkError at library load.
        val probe = NativeSource.Companion::class.java.declaredMethods
            .single { it.name == "nativeSetPreviewWindowProbe" }
        assertEquals(1, probe.parameterCount)
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
