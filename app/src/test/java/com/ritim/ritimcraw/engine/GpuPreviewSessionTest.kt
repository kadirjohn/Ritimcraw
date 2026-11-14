package com.ritim.ritimcraw.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GpuPreviewSession tests (M4 commit b), against a fake [GpuPreviewDevelop]:
 * the screen-side lifecycle owner is JVM-testable through the same seam the
 * M3 export pipeline's dispatch rides — the fake records the calls so the
 * tests lock the LATCH rules (open failure stays 0; a null render returns
 * null without throwing; close is idempotent and drains through the seam)
 * without the native lib. Since the gyro warp port the render also threads
 * the warp pair — the per-frame verdict the caller's resolver produced
 * (null folds to warpActive=false + identity M at the caller, never a
 * fabricated warp; false skips the draw entirely, the identity no-draw
 * rule) — locked verbatim in the render test below, same shape as the
 * pointwise and LUT pass-through locks.
 *
 * HONESTY: the real boundary (NativeGpuPreviewDevelop) and the GL session
 * behind it are Android-only — the JVM suite locks the wrapper's contract
 * here and the native seam's unavailable guard + signature in
 * [NativeSourcePreviewSessionTest]. The production render itself is
 * device-test-pending (the parity is already proven; the owner's morning
 * playback run is the proof).
 */
class GpuPreviewSessionTest {

    /**
     * The two spatial-NR stages' raw values ONE render carried — the
     * verbatim pass-through lock's snapshot for the stages-7/8 pair (a
     * swapped or dropped parameter in GpuPreviewSession.render fails the
     * data-class equality with the exact field named).
     */
    private data class NrCall(
        val chromaDenoise: Float,
        val noiseReduction: Float,
    )

    /**
     * The two unsharp stages' raw percents ONE render carried, in the
     * seam's parameter order — the verbatim pass-through lock's snapshot
     * for the stages-21/22 pair (a swapped or dropped parameter in
     * GpuPreviewSession.render fails the data-class equality with the
     * exact field named; NO clamp — negative sharpness is valid
     * softening).
     */
    private data class SharpnessDetailCall(
        val sharpness: Float,
        val detail: Float,
    )

    /**
     * The ten pointwise stages' raw values ONE render carried, in the
     * seam's parameter order — the verbatim pass-through lock's snapshot
     * (a swapped or dropped parameter in GpuPreviewSession.render fails
     * the data-class equality with the exact field named).
     */
    private data class PointwiseCall(
        val blackPoint: Float,
        val whitePoint: Float,
        val shadows: Float,
        val midtones: Float,
        val contrast: Float,
        val tempK: Float,
        val tint: Float,
        val saturation: Float,
        val asShot: Boolean,
        val wheelShadowX: Float,
        val wheelShadowY: Float,
        val wheelMidX: Float,
        val wheelMidY: Float,
        val wheelHighX: Float,
        val wheelHighY: Float,
        val splitShadowHue: Float,
        val splitHighlightHue: Float,
        val splitBalance: Float,
    )

    /**
     * The seven LUT controls ONE render carried, in the seam's parameter
     * order — the verbatim pass-through lock's snapshot for stage 24 (a
     * swapped or dropped parameter in GpuPreviewSession.render fails the
     * data-class equality with the exact field named).
     */
    private data class LutCall(
        val lutPath: String?,
        val lutStrength: Float,
        val lutRole: Int,
        val lutInGamut: Int,
        val lutInTransfer: Int,
        val lutOutGamut: Int,
        val lutOutTransfer: Int,
    )

    /**
     * The warp pair ONE render carried — the verbatim pass-through lock's
     * snapshot for the gyro warp port (a swapped or dropped parameter in
     * GpuPreviewSession.render fails the data-class equality with the
     * exact field named; the nine-float map rides as a List because a
     * FloatArray field would compare references, not contents).
     */
    private data class WarpCall(
        val warpActive: Boolean,
        val warpM: List<Float>,
    )

    /** Records every seam call; scripted verdicts per method. */
    private class FakeDevelop(
        var openResult: Long = 1L,
        var renderResult: PreviewFrame? = PreviewFrame(2, 2, IntArray(4)),
        var setSurfaceResult: Boolean = true,
        var openThrows: Boolean = false,
        var renderThrows: Boolean = false,
    ) : GpuPreviewDevelop {
        val opened = mutableListOf<Unit>()
        val probed = mutableListOf<Unit>()
        val parities = mutableListOf<Int>() // the fds the parity leg saw
        val surfaces = mutableListOf<Any?>()
        val rendered = mutableListOf<Long>() // the session handles rendered
        val renderedHighlights = mutableListOf<Float>() // grade.highlights per render
        val renderedNr = mutableListOf<NrCall>() // the two spatial-NR stages per render
        val renderedSharpnessDetail = mutableListOf<SharpnessDetailCall>() // the two unsharp stages per render
        val renderedPointwise = mutableListOf<PointwiseCall>() // the ten stages per render
        val renderedLut = mutableListOf<LutCall>() // the seven LUT controls per render
        val renderedWarp = mutableListOf<WarpCall>() // the warp pair per render
        val closed = mutableListOf<Long>()
        var lastDetailValue: String? = null
        var probeResult: DoubleArray? = null
        var parityResult: DoubleArray? = null

        override suspend fun probe(): DoubleArray? {
            probed += Unit
            return probeResult
        }

        override suspend fun fragmentParity(fd: Int): DoubleArray? {
            parities += fd
            return parityResult
        }

        override suspend fun openSession(): Long {
            opened += Unit
            if (openThrows) throw IllegalStateException("seam blew up")
            return openResult
        }

        override suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean {
            surfaces += surface
            return setSurfaceResult
        }

        override suspend fun renderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            maxDim: Int,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
            warpActive: Boolean,
            warpM: FloatArray,
        ): PreviewFrame? {
            rendered += sessionHandle
            renderedHighlights += highlights
            renderedNr += NrCall(chromaDenoise, noiseReduction)
            renderedSharpnessDetail += SharpnessDetailCall(sharpness, detail)
            renderedPointwise += PointwiseCall(
                blackPoint, whitePoint, shadows, midtones, contrast,
                tempK, tint, saturation, asShot,
                wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
                wheelHighX, wheelHighY,
                splitShadowHue, splitHighlightHue, splitBalance,
            )
            renderedLut += LutCall(
                lutPath, lutStrength, lutRole,
                lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
            )
            renderedWarp += WarpCall(warpActive, warpM.toList())
            if (renderThrows) throw IllegalStateException("seam blew up")
            return renderResult
        }

        override fun closeSession(sessionHandle: Long) {
            closed += sessionHandle
        }

        override suspend fun lastDetail(): String? = lastDetailValue
    }

    // ---- open ----

    @Test
    fun open_success_latches_the_handle_and_reports_active() = runBlocking {
        val develop = FakeDevelop(openResult = 7L)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertEquals(7L, session.handle)
        assertTrue(session.active)
    }

    @Test
    fun open_failure_stays_zero_and_reports_inactive() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertEquals(0L, session.handle)
        assertFalse(session.active)
    }

    @Test
    fun open_absorbs_a_seam_throw_and_stays_zero() = runBlocking {
        val develop = FakeDevelop(openThrows = true)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertEquals(0L, session.handle)
        assertFalse(session.active)
    }

    // ---- the surface handoff ----

    @Test
    fun surface_handoff_rides_the_seam_with_the_opened_handle() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        val surface = Any()
        assertTrue(session.onSurfaceChanged(surface))
        // The lifecycle signal reached the seam exactly once, with the
        // surface the holder reported.
        assertEquals(listOf<Any?>(surface), develop.surfaces)
    }

    @Test
    fun surface_handoff_is_a_no_op_before_open() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        // surfaceCreated can fire before the session's open lands (the
        // SurfaceView composes as soon as the path is active): the handoff
        // must refuse cleanly, never hand a 0 handle to the seam.
        assertFalse(session.onSurfaceChanged(Any()))
        assertTrue(develop.surfaces.isEmpty())
    }

    @Test
    fun surface_destroy_still_processes_after_open() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertTrue(session.onSurfaceChanged(null))
        assertEquals(listOf<Any?>(null), develop.surfaces)
    }

    // ---- render ----

    @Test
    fun render_returns_the_seam_frame_with_the_opened_handle() = runBlocking {
        val frame = PreviewFrame(4, 3, IntArray(12))
        val develop = FakeDevelop(openResult = 7L, renderResult = frame)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        val out = session.render(
            clipHandle = 11L, frameIndex = 5, maxDim = 1280,
            chromaDenoise = 25f, noiseReduction = 40f,
            sharpness = 35f, detail = -15f,
            exposureEv = 0.5f, highlights = -55f, highlightReconstruction = true,
            keepVignetting = true, tonemap = 1,
            blackPoint = 5f, whitePoint = -5f,
            shadows = 55f, midtones = -55f, contrast = 100f,
            tempK = 6500f, tint = 30f, saturation = -40f,
            asShot = false,
            wheelShadowX = 0.25f, wheelShadowY = -0.25f,
            wheelMidX = 0.5f, wheelMidY = -0.5f,
            wheelHighX = 0.75f, wheelHighY = -0.75f,
            splitShadowHue = 30f, splitHighlightHue = 210f, splitBalance = -25f,
            lutPath = "/data/luts/milog.cube", lutStrength = 0.85f,
            lutRole = 3, lutInGamut = 8, lutInTransfer = 12,
            lutOutGamut = 0, lutOutTransfer = 1,
            warpActive = true, warpM = floatArrayOf(
                0.5f, 0.25f, -1.5f,
                2.5f, 0.75f, 3.25f,
                -0.5f, 1.5f, 0.9f,
            ),
        )
        // The SAME instance flows through — the pump publishes it exactly
        // as the CPU dispatch publishes its own frame.
        assertSame(frame, out)
        // The seam saw the OPENED handle, never the screen's raw one.
        assertEquals(listOf(7L), develop.rendered)
        // The stage-15 control rides the seam verbatim — the raw
        // grade.highlights the shader's uHighlights bind needs (the
        // export-side FakeGpuDevelop pass-through precedent).
        assertEquals(listOf(-55f), develop.renderedHighlights)
        // The two spatial-NR stages ride the seam verbatim too — the raw
        // grade values the multi-pass fragment chain's uChromaDenoise/
        // uNoiseReduction binds need (the stages-7/8 port's own
        // pass-through lock, same shape as the pointwise one below).
        assertEquals(listOf(NrCall(chromaDenoise = 25f, noiseReduction = 40f)), develop.renderedNr)
        // The two unsharp stages ride the seam verbatim too — the raw
        // grade percents the four-pass unsharp chain's uSharpness/uDetail
        // binds need, the NEGATIVE detail carrying through unclamped
        // (softening is valid — the CPU's own amount mapping).
        assertEquals(
            listOf(SharpnessDetailCall(sharpness = 35f, detail = -15f)),
            develop.renderedSharpnessDetail,
        )
        // The ten pointwise stages ride the seam verbatim too, each raw
        // grade value in the established order — a swapped pair or a
        // dropped stage fails this equality with the field named.
        assertEquals(
            listOf(
                PointwiseCall(
                    blackPoint = 5f, whitePoint = -5f,
                    shadows = 55f, midtones = -55f, contrast = 100f,
                    tempK = 6500f, tint = 30f, saturation = -40f,
                    asShot = false,
                    wheelShadowX = 0.25f, wheelShadowY = -0.25f,
                    wheelMidX = 0.5f, wheelMidY = -0.5f,
                    wheelHighX = 0.75f, wheelHighY = -0.75f,
                    splitShadowHue = 30f, splitHighlightHue = 210f,
                    splitBalance = -25f,
                )
            ),
            develop.renderedPointwise,
        )
        // The seven LUT controls ride the seam verbatim too — the resolved
        // path, the [0,1] strength, and the five interpretation ordinals,
        // each in the established order (a swapped pair or a dropped
        // ordinal fails this equality with the field named).
        assertEquals(
            listOf(
                LutCall(
                    lutPath = "/data/luts/milog.cube", lutStrength = 0.85f,
                    lutRole = 3, lutInGamut = 8, lutInTransfer = 12,
                    lutOutGamut = 0, lutOutTransfer = 1,
                )
            ),
            develop.renderedLut,
        )
        // The warp pair rides the seam verbatim too — the per-frame
        // verdict the caller's resolver produced (the active flag locks
        // the draw, the nine floats lock the composite inverse-sampling
        // map; this render locks the ACTIVE pass-through — false would
        // skip draw 10 entirely, the identity no-draw rule).
        assertEquals(
            listOf(
                WarpCall(
                    warpActive = true,
                    warpM = listOf(
                        0.5f, 0.25f, -1.5f,
                        2.5f, 0.75f, 3.25f,
                        -0.5f, 1.5f, 0.9f,
                    ),
                )
            ),
            develop.renderedWarp,
        )
    }

    @Test
    fun render_after_a_failed_open_returns_null_without_touching_the_seam() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                chromaDenoise = 0f, noiseReduction = 0f,
                sharpness = 0f, detail = 0f,
                exposureEv = 0f, highlights = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
                blackPoint = 0f, whitePoint = 0f,
                shadows = 0f, midtones = 0f, contrast = 0f,
                tempK = 5600f, tint = 0f, saturation = 0f,
                asShot = true,
                wheelShadowX = 0f, wheelShadowY = 0f,
                wheelMidX = 0f, wheelMidY = 0f,
                wheelHighX = 0f, wheelHighY = 0f,
                splitShadowHue = -1f, splitHighlightHue = -1f, splitBalance = 0f,
                lutPath = null, lutStrength = 0f,
                lutRole = -1, lutInGamut = -1, lutInTransfer = -1,
                lutOutGamut = -1, lutOutTransfer = -1,
                warpActive = false, warpM = floatArrayOf(
                    1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
                ),
            )
        )
        assertTrue(develop.rendered.isEmpty())
    }

    @Test
    fun render_absorbs_a_seam_throw_as_the_null_fallback_verdict() = runBlocking {
        val develop = FakeDevelop(renderThrows = true)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                chromaDenoise = 0f, noiseReduction = 0f,
                sharpness = 0f, detail = 0f,
                exposureEv = 0f, highlights = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
                blackPoint = 0f, whitePoint = 0f,
                shadows = 0f, midtones = 0f, contrast = 0f,
                tempK = 5600f, tint = 0f, saturation = 0f,
                asShot = true,
                wheelShadowX = 0f, wheelShadowY = 0f,
                wheelMidX = 0f, wheelMidY = 0f,
                wheelHighX = 0f, wheelHighY = 0f,
                splitShadowHue = -1f, splitHighlightHue = -1f, splitBalance = 0f,
                lutPath = null, lutStrength = 0f,
                lutRole = -1, lutInGamut = -1, lutInTransfer = -1,
                lutOutGamut = -1, lutOutTransfer = -1,
                warpActive = false, warpM = floatArrayOf(
                    1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
                ),
            )
        )
    }

    // ---- close ----

    @Test
    fun close_is_idempotent_and_closes_the_seam_exactly_once() = runBlocking {
        val develop = FakeDevelop(openResult = 7L)
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        session.close()
        session.close() // the dispose path may race a fallback path's close
        assertEquals(listOf(7L), develop.closed)
        assertFalse(session.active)
    }

    @Test
    fun close_after_a_failed_open_never_touches_the_seam() = runBlocking {
        val develop = FakeDevelop(openResult = 0L)
        val session = GpuPreviewSession(develop)
        assertFalse(session.open())
        session.close()
        assertTrue(develop.closed.isEmpty())
    }

    @Test
    fun render_after_close_returns_null_without_touching_the_seam() = runBlocking {
        val develop = FakeDevelop()
        val session = GpuPreviewSession(develop)
        assertTrue(session.open())
        session.close()
        assertNull(
            session.render(
                clipHandle = 11L, frameIndex = 0, maxDim = 1280,
                chromaDenoise = 0f, noiseReduction = 0f,
                sharpness = 0f, detail = 0f,
                exposureEv = 0f, highlights = 0f, highlightReconstruction = true,
                keepVignetting = true, tonemap = 0,
                blackPoint = 0f, whitePoint = 0f,
                shadows = 0f, midtones = 0f, contrast = 0f,
                tempK = 5600f, tint = 0f, saturation = 0f,
                asShot = true,
                wheelShadowX = 0f, wheelShadowY = 0f,
                wheelMidX = 0f, wheelMidY = 0f,
                wheelHighX = 0f, wheelHighY = 0f,
                splitShadowHue = -1f, splitHighlightHue = -1f, splitBalance = 0f,
                lutPath = null, lutStrength = 0f,
                lutRole = -1, lutInGamut = -1, lutInTransfer = -1,
                lutOutGamut = -1, lutOutTransfer = -1,
                warpActive = false, warpM = floatArrayOf(
                    1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
                ),
            )
        )
        assertEquals(0, develop.rendered.size)
    }

    // ---- lastDetail ----

    @Test
    fun last_detail_flows_through_for_the_fallback_line() = runBlocking {
        val develop = FakeDevelop().apply { lastDetailValue = "eglCreateWindowSurface" }
        val session = GpuPreviewSession(develop)
        assertEquals("eglCreateWindowSurface", session.lastDetail())
    }

    @Test
    fun last_detail_absorbs_a_seam_throw() = runBlocking {
        val session = GpuPreviewSession(object : GpuPreviewDevelop {
            override suspend fun probe(): DoubleArray? = null
            override suspend fun fragmentParity(fd: Int): DoubleArray? = null
            override suspend fun openSession(): Long = 1L
            override suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean = true
            override suspend fun renderFrame(
                sessionHandle: Long,
                clipHandle: Long,
                frameIndex: Int,
                maxDim: Int,
                chromaDenoise: Float,
                noiseReduction: Float,
                sharpness: Float,
                detail: Float,
                exposureEv: Float,
                highlights: Float,
                blackPoint: Float,
                whitePoint: Float,
                shadows: Float,
                midtones: Float,
                contrast: Float,
                tempK: Float,
                tint: Float,
                saturation: Float,
                asShot: Boolean,
                wheelShadowX: Float,
                wheelShadowY: Float,
                wheelMidX: Float,
                wheelMidY: Float,
                wheelHighX: Float,
                wheelHighY: Float,
                splitShadowHue: Float,
                splitHighlightHue: Float,
                splitBalance: Float,
                highlightReconstruction: Boolean,
                keepVignetting: Boolean,
                tonemap: Int,
                lutPath: String?,
                lutStrength: Float,
                lutRole: Int,
                lutInGamut: Int,
                lutInTransfer: Int,
                lutOutGamut: Int,
                lutOutTransfer: Int,
                warpActive: Boolean,
                warpM: FloatArray,
            ): PreviewFrame? = null
            override fun closeSession(sessionHandle: Long) {}
            override suspend fun lastDetail(): String? = throw IllegalStateException("seam blew up")
        })
        assertNull(session.lastDetail())
    }

    // ---- the array contract (the trace-dims fix's tail, 2026-11-14) ------

    /**
     * The tail layout the JNI entry appends after the pixels (13 ints):
     * [sourceW, sourceH, developW, developH, surfaceW, surfaceH,
     * statsEnabled, decUs, cfaUs, upUs, devUs, presentUs, statsUs] — the
     * spans in MICROSECONDS, parsed into the PreviewFrame's ms fields.
     */
    private fun tail(
        sourceW: Int = 4032, sourceH: Int = 3024,
        developW: Int = 1280, developH: Int = 960,
        surfaceW: Int = 1080, surfaceH: Int = 810,
        statsEnabled: Int = 1,
        decUs: Int = 18400, cfaUs: Int = 2700, upUs: Int = 1200,
        devUs: Int = 3900, presentUs: Int = 1100, statsUs: Int = 800,
    ) = intArrayOf(
        sourceW, sourceH, developW, developH, surfaceW, surfaceH,
        statsEnabled, decUs, cfaUs, upUs, devUs, presentUs, statsUs,
    )

    @Test
    fun old_tailless_array_parses_with_the_neutral_defaults() {
        // BACK-COMPAT: the pre-fix shape (2 + w*h, no tail) is still a
        // valid array — an old .so against this Kotlin degrades to
        // neutral measurement defaults instead of dropping every frame.
        // The parser lives on the develop object (the real boundary's
        // own seam), not the lifecycle owner.
        val out = NativeGpuPreviewDevelop.renderArrayBlocking(intArrayOf(2, 2, 0, 0, 0, 0))
        assertEquals(PreviewFrame(2, 2, IntArray(4)), out)
        assertEquals(0, out?.sourceW)
        assertEquals(0, out?.developH)
        assertEquals(0.0, out?.decMs)
        assertEquals(0.0, out?.statsMs)
        assertTrue(out?.statsEnabled == true)
    }

    @Test
    fun new_tailed_array_parses_the_measurement_tail() {
        // The GPU window path's real shape: 2 + w*h + 13. The dims and
        // the toggle ride verbatim; the microsecond spans divide into
        // the ms fields (18400us -> 18.4ms).
        val out = NativeGpuPreviewDevelop.renderArrayBlocking(
            intArrayOf(2, 2, 0, 0, 0, 0) + tail()
        )
        assertEquals(PreviewFrame(2, 2, IntArray(4)), out)
        assertEquals(4032, out?.sourceW)
        assertEquals(3024, out?.sourceH)
        assertEquals(1280, out?.developW)
        assertEquals(960, out?.developH)
        assertEquals(1080, out?.surfaceW)
        assertEquals(810, out?.surfaceH)
        assertEquals(true, out?.statsEnabled)
        val frame = out!!
        assertEquals(18.4, frame.decMs, 1e-9)
        assertEquals(2.7, frame.cfaMs, 1e-9)
        assertEquals(1.2, frame.upMs, 1e-9)
        assertEquals(3.9, frame.devMs, 1e-9)
        assertEquals(1.1, frame.presentMs, 1e-9)
        assertEquals(0.8, frame.statsMs, 1e-9)
    }

    @Test
    fun tailed_array_with_stats_off_parses_the_toggle() {
        // The A/B leg: statsEnabled=0 in the tail (the toggle skipped
        // draw 3 + the readback) reads false on the frame — the trace
        // line then prints stats=off.
        val out = NativeGpuPreviewDevelop.renderArrayBlocking(
            intArrayOf(1, 1, 0) + tail(statsEnabled = 0, statsUs = 0)
        )
        assertEquals(false, out?.statsEnabled)
        assertEquals(0.0, out?.statsMs)
    }

    @Test
    fun wrong_length_arrays_stay_null_verdicts() {
        // The validation contract: neither the old nor the new length
        // means a corrupt array — the null verdict (the CPU fallback).
        assertNull(NativeGpuPreviewDevelop.renderArrayBlocking(null))
        assertNull(NativeGpuPreviewDevelop.renderArrayBlocking(intArrayOf(2)))
        assertNull(NativeGpuPreviewDevelop.renderArrayBlocking(intArrayOf(2, 2, 0, 0, 0))) // short pixels
        assertNull(NativeGpuPreviewDevelop.renderArrayBlocking(intArrayOf(0, 2, 0, 0))) // bad dims
        assertNull(NativeGpuPreviewDevelop.renderArrayBlocking(intArrayOf(2, 2, 0, 0, 0, 0, 1))) // 14 tail
    }

    @Test
    fun the_pixels_stay_the_identity_for_the_publish_fold() {
        // The custom equals/hashCode deliberately IGNORE the measurement
        // fields (dims+pixels identity, the publish-fold contract): the
        // tailed frame equals the tailless one with the same pixels, and
        // two tailed frames with different timings but equal pixels are
        // the SAME published frame.
        val tailed = NativeGpuPreviewDevelop.renderArrayBlocking(
            intArrayOf(2, 2, 0, 0, 0, 0) + tail()
        )
        assertEquals(PreviewFrame(2, 2, IntArray(4)), tailed)
        assertEquals(PreviewFrame(2, 2, IntArray(4)).hashCode(), tailed?.hashCode())
        val otherTimings = NativeGpuPreviewDevelop.renderArrayBlocking(
            intArrayOf(2, 2, 0, 0, 0, 0) + tail(decUs = 1, statsUs = 2)
        )
        assertEquals(tailed, otherTimings)
    }
}
