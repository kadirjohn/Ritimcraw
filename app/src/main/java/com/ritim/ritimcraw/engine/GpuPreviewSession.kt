package com.ritim.ritimcraw.engine

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The GPU preview session boundary (M4 commit b), as an interface so the
 * dispatch is JVM-testable with a fake (the [GpuDevelop] precedent from the
 * M3 export pipeline): the editor's render pump catches the seam's
 * IllegalStateException / null verdicts and falls back to the CPU preview
 * wholesale — the CPU path stays the default, exactly as the M3 gate keeps
 * the CPU develop the default.
 *
 * THREAD RULE (the binding one): every native call — probe, parity,
 * open, setSurface, renderFrame, close — is submitted to the SAME
 * single preview engine thread the CPU [PreviewSession] renders
 * through. The bridge's
 * nativePreviewSessionRenderFrame decodes the RAW CFA on the CALLING
 * thread (Source's one-thread rule + the ring buffer), so the GPU render
 * must run where the CPU renders run, and the bridge's thread-local error
 * state (lastDetail) must be read on that same thread. Submission order
 * on the single thread also gives the drained close: a close submitted at
 * dispose runs after every render submitted before it.
 */
interface GpuPreviewDevelop {
    /**
     * The capability probe (the M3 export gate's first leg, mirrored for
     * the preview): the raw [contextOk, glesMajor, glesMinor, computeOk,
     * floatFboOk, maxTextureSize] array, or null on a JNI failure. Runs
     * BEFORE the preview session opens, on the engine thread — the
     * harness brings its own one-shot EGL context up and down inside the
     * call (the PreviewMetrics precedent), so it must not share a thread
     * with a live session's context.
     */
    suspend fun probe(): DoubleArray?

    /**
     * The FRAGMENT develop-parity leg (the M3 export gate's second leg):
     * one GPU-vs-CPU frame through the clip at [fd] (dup'd for the call —
     * the caller's copy stays theirs), case 0 — the developLinear shape
     * the device-PROVEN parity case rides. Raw [ran, maxDiffLsb,
     * mismatchCount, samples], or null on a JNI failure. Runs on the
     * engine thread, before the session opens (same one-shot-context
     * rule as [probe]).
     */
    suspend fun fragmentParity(fd: Int): DoubleArray?

    /**
     * Opens the GPU preview session: a nonzero handle, or 0 on failure
     * ([lastDetail] then names the step — the caller logs ONE fallback
     * line and stays on CPU). Compiles the nine validated
     * single-source-of-truth shader texts (the two vertices, the SPLIT
     * develop pair pre/post, the three spatial-NR fragments, the store
     * pass, and the window pass's WINDOW_FRAGMENT). Runs on the engine
     * thread.
     */
    suspend fun openSession(): Long

    /**
     * Hands the session the SurfaceView's window surface (the
     * SurfaceHolder lifecycle), or destroys the window surface (null).
     * The ANativeWindow reference transfers to the session at call entry,
     * so the caller never releases it. False on failure — the surface
     * callback retries on the next surfaceCreated, or the preview falls
     * back to CPU. Runs on the engine thread.
     */
    suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean

    /**
     * Renders one frame and publishes BOTH the window draw (aspect-fit
     * into the SurfaceView — best-effort, never fails the render) and the
     * stats pack ([w, h, pixels...] at the 256-long-edge tier, the same
     * shape the CPU preview publishes) for Compose's overlays. Null on
     * failure — the session latches unhealthy with the same detail, so
     * the caller latches GPU-off (the M3 per-frame dispatch rule) and
     * falls back to the CPU render for THIS and every later frame. Runs
     * on the engine thread.
     */
    suspend fun renderFrame(
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
    ): PreviewFrame?

    /**
     * The gyro warp resolver (the GPU warp port's commit b): the per-frame
     * plan verdict the render consumes — a 10-element array ([0] active
     * flag, [1..9] the composite M) or null = no verdict (the caller folds
     * null to warpActive=false + identity M, never a fabricated warp).
     * Runs on the engine thread like every render entry. The default body
     * keeps fakes honest: a fake that does not override it never
     * fabricates a warp.
     */
    suspend fun resolveWarp(
        clipHandle: Long,
        gyroPath: String?,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
        frameIndex: Int,
        maxDim: Int,
    ): FloatArray? = null

    /**
     * Closes the session (idempotent; absorbs the unavailable guard and a
     * rejected engine at teardown). Fire-and-forget like
     * [PreviewSession.close]: the close is SUBMITTED to the engine thread
     * from the caller (submission is thread-safe; the JNI close is a
     * range-checked registry reset), so a dispose never blocks the UI
     * thread on pending renders — the single-thread submission order
     * still gives the drained close.
     */
    fun closeSession(sessionHandle: Long)

    /** The bridge's thread-local last-error detail (same thread as the failed call). */
    suspend fun lastDetail(): String?
}

/**
 * The real boundary: the NativeSource wrappers with the validated
 * single-source-of-truth shader texts — the NativeGpuDevelop precedent,
 * bound to the shared preview engine thread.
 */
internal object NativeGpuPreviewDevelop : GpuPreviewDevelop {

    override suspend fun probe(): DoubleArray? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        NativeSource.jniGlesProbe()
                    } catch (t: Throwable) {
                        null // unavailable guard / engine-side failure: honest no-probe
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    override suspend fun fragmentParity(fd: Int): DoubleArray? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        NativeSource.jniGlesFragmentParity(
                            fd,
                            GlesShaderSources.FULLSCREEN_VERTEX,
                            GlesShaderSources.FULLSCREEN_VERTEX_310,
                            GlesShaderSources.DEVELOP_FRAGMENT,
                            GlesShaderSources.STORE_FRAGMENT,
                            // case 0 — the developLinear shape: the
                            // device-PROVEN parity case (the M3 export
                            // gate rides the same one).
                            0,
                        )
                    } catch (t: Throwable) {
                        null // unavailable guard / engine-side failure
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    override suspend fun openSession(): Long = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
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
                    } catch (t: Throwable) {
                        0L // unavailable guard / engine-side failure: stay on CPU
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(0L)
        }
    }

    override suspend fun setSurface(sessionHandle: Long, surface: Any?): Boolean =
        suspendCoroutine { cont ->
            try {
                PreviewRenderer.previewEngine.execute {
                    cont.resume(
                        try {
                            NativeSource.jniPreviewSessionSetSurface(sessionHandle, surface)
                        } catch (t: Throwable) {
                            false // unavailable guard: the destroy signal still no-ops
                        }
                    )
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(false)
            }
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
    ): PreviewFrame? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        renderArrayBlocking(
                            NativeSource.jniPreviewSessionRenderFrame(
                                sessionHandle, clipHandle, frameIndex, maxDim,
                                chromaDenoise, noiseReduction,
                                sharpness, detail,
                                exposureEv, highlights, blackPoint, whitePoint,
                                shadows, midtones, contrast, tempK, tint,
                                saturation, asShot,
                                wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
                                wheelHighX, wheelHighY,
                                splitShadowHue, splitHighlightHue, splitBalance,
                                highlightReconstruction, keepVignetting, tonemap,
                                lutPath, lutStrength, lutRole, lutInGamut,
                                lutInTransfer, lutOutGamut, lutOutTransfer,
                                warpActive, warpM,
                            )
                        )
                    } catch (t: Throwable) {
                        null // unavailable guard / engine-side failure: fall back to CPU
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    override suspend fun resolveWarp(
        clipHandle: Long,
        gyroPath: String?,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
        frameIndex: Int,
        maxDim: Int,
    ): FloatArray? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        NativeSource.jniStabWarpResolve(
                            clipHandle, gyroPath, stabMode, stabStrength,
                            stabZoomLimit, stabFocalMm, stabTrimMs,
                            frameIndex, maxDim,
                        )
                    } catch (t: Throwable) {
                        null // unavailable guard: no verdict, the render skips draw 10
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    override fun closeSession(sessionHandle: Long) {
        if (sessionHandle == 0L) return
        try {
            PreviewRenderer.previewEngine.execute {
                try {
                    NativeSource.jniPreviewSessionClose(sessionHandle)
                } catch (t: Throwable) {
                    // unavailable guard: the bridge slot frees on process exit
                }
            }
        } catch (e: RejectedExecutionException) {
            // engine already down; the bridge slot frees on process exit
        }
    }

    override suspend fun lastDetail(): String? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        NativeSource.previewLastError()
                    } catch (t: Throwable) {
                        null
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    /**
     * Validates the published pack into a [PreviewFrame]; MUST run on the
     * engine thread. The SAME shape the CPU preview publishes ([w, h,
     * pixels...]), so the publish fold downstream is engine-blind — PLUS
     * the trace-dims fix's (2026-11-14) 13-int measurement tail the
     * nativePreviewSessionRenderFrame appends after the pixels:
     * [sourceW, sourceH, developW, developH, surfaceW, surfaceH,
     * statsEnabled, decUs, cfaUs, upUs, devUs, presentUs, statsUs] (the
     * spans in MICROSECONDS; parsed into the PreviewFrame's ms fields).
     * BOTH lengths are valid — the old (2 + w*h) tail-less array parses
     * with the neutral defaults, the new (2 + w*h + 13) one parses the
     * tail — so an old .so against this Kotlin (or vice versa) degrades
     * honestly instead of dropping every frame. Internal, not private:
     * the JVM suite locks this contract without the native lib.
     */
    internal fun renderArrayBlocking(out: IntArray?): PreviewFrame? {
        if (out == null || out.size < 2) return null
        val w = out[0]
        val h = out[1]
        if (w <= 0 || h <= 0) return null
        val pixelCount = w * h
        val oldLength = 2 + pixelCount
        val newLength = oldLength + 13
        if (out.size == oldLength) {
            return PreviewFrame(w, h, out.copyOfRange(2, out.size))
        }
        if (out.size != newLength) return null
        // The measurement tail (GPU window path only): dims the published
        // pack hides, the stats toggle's state, and the six per-stage
        // spans — microseconds to milliseconds.
        val t = out.size - 13
        return PreviewFrame(
            width = w,
            height = h,
            argb = out.copyOfRange(2, t),
            sourceW = out[t],
            sourceH = out[t + 1],
            developW = out[t + 2],
            developH = out[t + 3],
            surfaceW = out[t + 4],
            surfaceH = out[t + 5],
            statsEnabled = out[t + 6] != 0,
            decMs = out[t + 7] / 1000.0,
            cfaMs = out[t + 8] / 1000.0,
            upMs = out[t + 9] / 1000.0,
            devMs = out[t + 10] / 1000.0,
            presentMs = out[t + 11] / 1000.0,
            statsMs = out[t + 12] / 1000.0,
        )
    }
}

/**
 * The GPU preview session's screen-side lifecycle owner: one session per
 * editor open, opened only when the CPU session holds a native clip handle
 * (the GPU dispatch needs it), closed exactly once at dispose — a drained
 * close on the same single engine thread. The render dispatch itself stays
 * in the screen's pump (the eligibility decision is per-request and reads
 * the request's grade); this class owns ONLY the handle's lifetime and the
 * window-surface handoff, so the pump never learns the JNI seam.
 *
 * The [closed] latch mirrors [PreviewSession.close]: a close submitted at
 * dispose runs after every render submitted before it (single-thread
 * submission order), and a render submitted after close finds the handle
 * dead — the bridge's range-checked registry makes that a clean null, which
 * the pump treats as the M3-style CPU fallback.
 */
class GpuPreviewSession(
    private val develop: GpuPreviewDevelop,
) {
    private val closed = AtomicBoolean(false)

    /** The session handle: 0 = not opened / open failed (stay on CPU). */
    var handle: Long = 0L
        private set

    /** True when open() returned a live handle and close() has not run. */
    val active: Boolean get() = handle != 0L && !closed.get()

    /**
     * Opens the GPU session. Fails soft (handle stays 0, the caller logs
     * ONE fallback line from [lastDetail] and stays on CPU) — never throws.
     */
    suspend fun open(): Boolean {
        if (closed.get()) return false
        val h = try {
            develop.openSession()
        } catch (t: Throwable) {
            0L
        }
        handle = h
        return h != 0L
    }

    /**
     * The SurfaceHolder lifecycle: surfaceCreated → the holder's Surface;
     * surfaceDestroyed → null (the destroy signal ALWAYS processes, even
     * when a render latched the session unhealthy — a dead surface is the
     * UI's lifecycle signal, never a render failure). Best-effort: false
     * never fails the preview, the next surfaceCreated retries.
     */
    suspend fun onSurfaceChanged(surface: Any?): Boolean {
        val h = handle
        if (h == 0L) return false
        return try {
            develop.setSurface(h, surface)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Renders one frame through the GPU session; null = the pump falls
     * back to the CPU render for this request (and latches GPU-off, the
     * M3 per-frame dispatch rule). Never throws.
     */
    suspend fun render(
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
        val h = handle
        if (h == 0L || closed.get()) return null
        return try {
            develop.renderFrame(
                h, clipHandle, frameIndex, maxDim,
                chromaDenoise, noiseReduction,
                sharpness, detail,
                exposureEv, highlights, blackPoint, whitePoint,
                shadows, midtones, contrast, tempK, tint,
                saturation, asShot,
                wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
                wheelHighX, wheelHighY,
                splitShadowHue, splitHighlightHue, splitBalance,
                highlightReconstruction, keepVignetting, tonemap,
                lutPath, lutStrength, lutRole, lutInGamut,
                lutInTransfer, lutOutGamut, lutOutTransfer,
                warpActive, warpM,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Resolves the frame's gyro warp plan on the engine thread (the GPU
     * warp port's commit b): the verdict the [render] above consumes. Null
     * (no verdict — no log, dead handle, parse failure, or the unavailable
     * guard) folds to warpActive=false + identity M at the CALLER, never a
     * fabricated warp; the render then skips draw 10 entirely (the
     * identity no-draw rule). Never throws.
     */
    suspend fun resolveWarp(
        clipHandle: Long,
        gyroPath: String?,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
        frameIndex: Int,
        maxDim: Int,
    ): FloatArray? = try {
        develop.resolveWarp(
            clipHandle, gyroPath, stabMode, stabStrength,
            stabZoomLimit, stabFocalMm, stabTrimMs, frameIndex, maxDim,
        )
    } catch (t: Throwable) {
        null
    }

    /** The bridge's last-error detail, read on the engine thread (thread-local). */
    suspend fun lastDetail(): String? = try {
        develop.lastDetail()
    } catch (t: Throwable) {
        null
    }

    /**
     * Closes exactly once (AtomicBoolean, the PreviewSession.close rule);
     * idempotent and safe from onDispose + error paths alike. Drained: the
     * close is submitted to the same single engine thread, so it runs
     * after every render submitted before it. Absorbs the unavailable
     * guard (no .so at teardown) and a rejected engine.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            val h = handle
            if (h == 0L) return
            try {
                develop.closeSession(h)
            } catch (t: Throwable) {
                // engine already down; the bridge slot frees on process exit
            }
        }
    }
}
