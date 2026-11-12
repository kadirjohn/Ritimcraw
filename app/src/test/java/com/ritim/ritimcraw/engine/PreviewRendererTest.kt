package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            asShot = false, highlightReconstruction = true, keepVignetting = false,
            sharpness = 25f, detail = -10f, tonemap = 1,
            lutStrength = 75f,
            noiseReduction = 40f, chromaDenoise = 65f,
            temporalNr = true, temporalStrength = 62.5f,
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
        assertEquals(false, args.asShot)
        assertEquals(true, args.highlightReconstruction)
        assertEquals(false, args.keepVignetting)
        assertEquals(25f, args.sharpness, 0f)
        assertEquals(-10f, args.detail, 0f)
        assertEquals(1, args.tonemap)
        // COL-07: the UI percent maps to rawedit's 0..1 blend here.
        assertEquals(0.75f, args.lutStrength, 0f)
        // DN-01..08: the denoise percents pass through at the native 0..100
        // scale — NO /100 here (unlike lutStrength); the core clamps itself.
        assertEquals(40f, args.noiseReduction, 0f)
        assertEquals(65f, args.chromaDenoise, 0f)
    }

    @Test
    fun lut_strength_percent_maps_to_native_blend() {
        // COL-07: GradeK stores a UI percent 0..100; the JNI boundary passes
        // rawedit's 0..1 blend (lutStrength = lut(rgb)*s + rgb*(1-s)). The
        // mapping happens HERE (GradeMapping.toNative for the preview, the
        // same /100 inline at the two export-path call sites), so every JNI
        // call site passes 0..1.
        assertEquals(0.5f, GradeMapping.toNative(GradeK(lutStrength = 50f)).lutStrength, 0f)
        assertEquals(0f, GradeMapping.toNative(GradeK(lutStrength = 0f)).lutStrength, 0f)
        assertEquals(1f, GradeMapping.toNative(GradeK(lutStrength = 100f)).lutStrength, 0f)
        // The GradeK default (100 = full LUT) maps to the full blend.
        assertEquals(1f, GradeMapping.toNative(GradeK()).lutStrength, 0f)
    }

    @Test
    fun denoise_percents_pass_through_at_native_scale() {
        // DN-01..08: GradeK stores UI percents 0..100 and rawedit's Grade
        // uses the SAME 0..100 scale for both denoise fields (unlike
        // lutStrength's 0..1 blend). The mapping is a pure pass-through —
        // the core clamps [0,100] — so the slider value the user sees is
        // exactly the strength the native stage gets.
        assertEquals(50f, GradeMapping.toNative(GradeK(noiseReduction = 50f)).noiseReduction, 0f)
        assertEquals(0f, GradeMapping.toNative(GradeK(noiseReduction = 0f)).noiseReduction, 0f)
        assertEquals(100f, GradeMapping.toNative(GradeK(noiseReduction = 100f)).noiseReduction, 0f)
        assertEquals(50f, GradeMapping.toNative(GradeK(chromaDenoise = 50f)).chromaDenoise, 0f)
        assertEquals(0f, GradeMapping.toNative(GradeK(chromaDenoise = 0f)).chromaDenoise, 0f)
        assertEquals(100f, GradeMapping.toNative(GradeK(chromaDenoise = 100f)).chromaDenoise, 0f)
        // The GradeK defaults (0 = the stage skipped) map to neutral — the
        // pre-DN look (locked at the C++ level by the golden bit-identity
        // test).
        val defaults = GradeMapping.toNative(GradeK())
        assertEquals(0f, defaults.noiseReduction, 0f)
        assertEquals(0f, defaults.chromaDenoise, 0f)
        // Temporal NR defaults to OFF/0 (DN-05..DN-09): a default grade never
        // runs the temporal stage, so the default look is unchanged and old
        // projects keep their exact old look.
        assertEquals(false, defaults.temporalNr)
        assertEquals(0f, defaults.temporalStrength, 0f)
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
        // The three RAW metadata toggles default to true — exactly the
        // pre-toggle pipeline, so the default look is unchanged (RAW-07/
        // COL-09/RAW-04/RAW-06 binding requirement).
        assertEquals(true, args.asShot)
        assertEquals(true, args.highlightReconstruction)
        assertEquals(true, args.keepVignetting)
        // The detail/tonemap fields default to neutral — sharpness 0,
        // detail 0, tonemap 0 = off. All three are pipeline no-ops, so a
        // default grade keeps the exact pre-COL-15/16 look (locked at the
        // C++ level by the golden bit-identity test).
        assertEquals(0f, args.sharpness, 0f)
        assertEquals(0f, args.detail, 0f)
        assertEquals(0, args.tonemap)
        // The denoise fields default to 0 = both native stages 7/8 skipped —
        // the default grade keeps the exact pre-DN-01..08 look (locked at the
        // C++ level by the golden bit-identity test).
        assertEquals(0f, args.noiseReduction, 0f)
        assertEquals(0f, args.chromaDenoise, 0f)
        // Temporal NR defaults to OFF/0 (DN-05..DN-09): a default grade never
        // runs the temporal stage, so the default look is unchanged and old
        // projects keep their exact old look.
        assertEquals(false, args.temporalNr)
        assertEquals(0f, args.temporalStrength, 0f)
    }

    @Test
    fun grade_mapping_survives_round_trip() {
        val grade = GradeK(
            exposureEv = -2.5f, highlights = 99f, tempK = 9000f, tint = -12f,
            saturation = -80f, blackPoint = 45f, whitePoint = -70f,
            shadows = 90f, midtones = -33f, contrast = 100f,
            asShot = false, highlightReconstruction = false, keepVignetting = true,
            sharpness = -45f, detail = 67f, tonemap = 2,
            lutStrength = 80f,
            noiseReduction = 33f, chromaDenoise = 71f,
            temporalNr = true, temporalStrength = 100f,
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
            asShot = args.asShot,
            highlightReconstruction = args.highlightReconstruction,
            keepVignetting = args.keepVignetting,
            sharpness = args.sharpness,
            detail = args.detail,
            tonemap = args.tonemap,
            lutStrength = args.lutStrength * 100f,
            // Denoise is already at the UI percent scale on the native side
            // — no *100 on the way back.
            noiseReduction = args.noiseReduction,
            chromaDenoise = args.chromaDenoise,
            temporalNr = args.temporalNr,
            temporalStrength = args.temporalStrength,
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
        assertEquals(grade.asShot, back.asShot)
        assertEquals(grade.highlightReconstruction, back.highlightReconstruction)
        assertEquals(grade.keepVignetting, back.keepVignetting)
        assertEquals(grade.sharpness, back.sharpness, 0f)
        assertEquals(grade.detail, back.detail, 0f)
        assertEquals(grade.tonemap, back.tonemap)
        // The percent travels through the mapping and back (the UI-side
        // value; the /100 blend is the native side of the same boundary).
        assertEquals(grade.lutStrength, back.lutStrength, 0f)
        // DN: the percents pass through unchanged in both directions.
        assertEquals(grade.noiseReduction, back.noiseReduction, 0f)
        assertEquals(grade.chromaDenoise, back.chromaDenoise, 0f)
        assertEquals(grade.temporalNr, back.temporalNr)
        assertEquals(grade.temporalStrength, back.temporalStrength, 0f)
    }

    @Test
    fun color_package_passes_through_at_native_scale() {
        // COL-13: the nine color-package fields are ALREADY at rawedit's
        // native scale in GradeK (like noiseReduction, unlike lutStrength) —
        // the mapping is a pure pass-through, so the wheel offsets, hues and
        // balance the user sets are exactly what the native stages 18/19
        // receive, at every JNI seam (preview, cached, MP4 export, encoded —
        // the parity contract).
        val grade = GradeK(
            wheelShadowX = 0.35f, wheelShadowY = -0.6f,
            wheelMidX = -0.9f, wheelMidY = 0.25f,
            wheelHighX = 0.7f, wheelHighY = 0.85f,
            splitShadowHue = 210f, splitHighlightHue = 45f,
            splitBalance = -30f,
        )
        val args = GradeMapping.toNative(grade)
        assertEquals(0.35f, args.wheelShadowX, 0f)
        assertEquals(-0.6f, args.wheelShadowY, 0f)
        assertEquals(-0.9f, args.wheelMidX, 0f)
        assertEquals(0.25f, args.wheelMidY, 0f)
        assertEquals(0.7f, args.wheelHighX, 0f)
        assertEquals(0.85f, args.wheelHighY, 0f)
        assertEquals(210f, args.splitShadowHue, 0f)
        assertEquals(45f, args.splitHighlightHue, 0f)
        assertEquals(-30f, args.splitBalance, 0f)
    }

    @Test
    fun color_package_defaults_are_neutral() {
        // The GradeK defaults (offsets 0, hues -1 = off, balance 0) map to the
        // neutral center: both native stages 18/19 are skipped by their
        // guards, so a default grade keeps the exact pre-COL-13 look (locked
        // at the C++ level by the bit-identity tests).
        val args = GradeMapping.toNative(GradeK())
        assertEquals(0f, args.wheelShadowX, 0f)
        assertEquals(0f, args.wheelShadowY, 0f)
        assertEquals(0f, args.wheelMidX, 0f)
        assertEquals(0f, args.wheelMidY, 0f)
        assertEquals(0f, args.wheelHighX, 0f)
        assertEquals(0f, args.wheelHighY, 0f)
        assertEquals(-1f, args.splitShadowHue, 0f)
        assertEquals(-1f, args.splitHighlightHue, 0f)
        assertEquals(0f, args.splitBalance, 0f)
    }

    // ---- stabilizationArgs (gyro Phase 5: the ONE testable preview gate) ----

    @Test
    fun stab_gate_off_grade_is_null() {
        // Stabilization off in the grade = no warp, whatever else is true
        // (the group's no-data state and the off toggle both land here).
        assertNull(
            GradeMapping.stabilizationArgs(
                GradeK(stabilization = false),
                maxDim = PreviewTiers.PAUSED_MAX_DIM,
                gyroPath = "/data/gyros/log.gcsv",
            )
        )
    }

    @Test
    fun stab_gate_no_attached_log_is_null() {
        // ON in the grade but no .gcsv attached: the honest no-warp
        // default (never a fabricated warp from a missing file).
        assertNull(
            GradeMapping.stabilizationArgs(
                GradeK(stabilization = true),
                maxDim = PreviewTiers.PAUSED_MAX_DIM,
                gyroPath = null,
            )
        )
    }

    @Test
    fun stab_gate_applies_at_every_tier() {
        // Audit fix 2026-11-12: stabilization warps at EVERY tier — playback
        // (720), drag (480) and the SOURCE_MAX_DIM (0) follow-up included.
        // The old tier skip made playback/drag disagree with the paused
        // frame and made the full-res follow-up REPLACE the stabilized
        // paused frame with an unstabilized one (a visible settle jump).
        // The warp's cost scales with the buffer (~1-5 ms at the preview
        // tiers, measured in test_warp's tier harness), so every tier can
        // afford it — unlike temporal NR, which keeps its tier gate.
        val on = GradeK(stabilization = true)
        assertNotNull(GradeMapping.stabilizationArgs(on, PreviewTiers.PLAYBACK_MAX_DIM, "/g.gcsv"))
        assertNotNull(GradeMapping.stabilizationArgs(on, SliderTiers.DRAG_MAX_DIM, "/g.gcsv"))
        assertNotNull(GradeMapping.stabilizationArgs(on, PreviewTiers.SOURCE_MAX_DIM, "/g.gcsv"))
        assertNotNull(GradeMapping.stabilizationArgs(on, PreviewTiers.PAUSED_MAX_DIM, "/g.gcsv"))
    }

    @Test
    fun stab_gate_on_with_log_maps_all_six_params() {
        // ON + attached log + paused tier: the six flat JNI params, with
        // the strength percent 0..100 -> rawedit's 0..1 (the lutStrength
        // precedent) and mode/zoomLimit/focalMm/trimMs passing through raw
        // (the C++ side owns their composition).
        val args = GradeMapping.stabilizationArgs(
            GradeK(
                stabilization = true,
                stabilizationMode = 2,
                stabilizationStrength = 70f,
                stabilizationZoomLimit = 1.2f,
                stabilizationFocalMm = 24f,
                stabilizationTrimMs = -120.5f,
            ),
            maxDim = PreviewTiers.PAUSED_MAX_DIM,
            gyroPath = "/data/gyros/log.gcsv",
        )
        assertNotNull(args)
        assertEquals("/data/gyros/log.gcsv", args!!.gyroPath)
        assertEquals(2, args.mode)
        assertEquals(0.7f, args.strength, 0f)
        assertEquals(1.2f, args.zoomLimit, 0f)
        assertEquals(24f, args.focalMm, 0f)
        assertEquals(-120.5f, args.trimMs, 0f)
    }

    @Test
    fun stab_gate_strength_percent_maps_to_unit_scale() {
        // The full 0..100 percent range lands on 0..1 exactly (three
        // points lock the linear map; the C++ side clamps the rest).
        assertEquals(0f, stabStrengthOf(0f), 0f)
        assertEquals(0.5f, stabStrengthOf(50f), 0f)
        assertEquals(1f, stabStrengthOf(100f), 0f)
    }

    /** strength of [percent] through the gate at the paused tier. */
    private fun stabStrengthOf(percent: Float): Float =
        GradeMapping.stabilizationArgs(
            GradeK(stabilization = true, stabilizationStrength = percent),
            maxDim = PreviewTiers.PAUSED_MAX_DIM,
            gyroPath = "/g.gcsv",
        )!!.strength

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

    @Test
    fun full_res_pause_render_publishes_then_a_later_seek_wins() {
        // Owner request 2026-11-11 section 2: pausing issues ONE full-res
        // render (SOURCE_MAX_DIM); a seek AFTER the pause issues its own
        // request at a LATER sequence. The pump publishes in sequence
        // order, so the pause frame lands first and the seek frame lands
        // last — the seek wins, structurally, the same way any newer
        // request beats an older one. This locks the ordering in.
        val pauseSequence = 7L
        val seekSequence = 8L
        // The pause render is fresh when it lands (nothing newer yet)...
        assertTrue(RenderDebounce.isFresh(pauseSequence, latestSequence = pauseSequence))
        // ...and the later seek is fresh against the pause's published
        // sequence, so it publishes AFTER the pause frame — seek wins.
        assertTrue(RenderDebounce.isFresh(seekSequence, latestSequence = pauseSequence))
        // The reverse never holds: the pause's result is stale once the
        // seek has published.
        assertFalse(RenderDebounce.isFresh(pauseSequence, latestSequence = seekSequence))
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
