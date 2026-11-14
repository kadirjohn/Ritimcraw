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
 * THREAD RULE (the binding one): every native call — open, setSurface,
 * renderFrame, close — is submitted to the SAME single preview engine
 * thread the CPU [PreviewSession] renders through. The bridge's
 * nativePreviewSessionRenderFrame decodes the RAW CFA on the CALLING
 * thread (Source's one-thread rule + the ring buffer), so the GPU render
 * must run where the CPU renders run, and the bridge's thread-local error
 * state (lastDetail) must be read on that same thread. Submission order
 * on the single thread also gives the drained close: a close submitted at
 * dispose runs after every render submitted before it.
 */
interface GpuPreviewDevelop {
    /**
     * Opens the GPU preview session: a nonzero handle, or 0 on failure
     * ([lastDetail] then names the step — the caller logs ONE fallback
     * line and stays on CPU). Compiles the five validated
     * single-source-of-truth shader texts (the four the M3 develop session
     * rides PLUS the window pass's WINDOW_FRAGMENT). Runs on the engine
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
        exposureEv: Float,
        highlightReconstruction: Boolean,
        keepVignetting: Boolean,
        tonemap: Int,
    ): PreviewFrame?

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

    override suspend fun openSession(): Long = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        NativeSource.jniPreviewSessionOpen(
                            GlesShaderSources.FULLSCREEN_VERTEX,
                            GlesShaderSources.FULLSCREEN_VERTEX_310,
                            GlesShaderSources.DEVELOP_FRAGMENT,
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
        exposureEv: Float,
        highlightReconstruction: Boolean,
        keepVignetting: Boolean,
        tonemap: Int,
    ): PreviewFrame? = suspendCoroutine { cont ->
        try {
            PreviewRenderer.previewEngine.execute {
                cont.resume(
                    try {
                        renderArrayBlocking(
                            NativeSource.jniPreviewSessionRenderFrame(
                                sessionHandle, clipHandle, frameIndex, maxDim,
                                exposureEv, highlightReconstruction, keepVignetting,
                                tonemap,
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
     * pixels...]), so the publish fold downstream is engine-blind.
     */
    private fun renderArrayBlocking(out: IntArray?): PreviewFrame? {
        if (out == null || out.size < 2) return null
        val w = out[0]
        val h = out[1]
        if (w <= 0 || h <= 0 || out.size != 2 + w * h) return null
        return PreviewFrame(w, h, out.copyOfRange(2, out.size))
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
        exposureEv: Float,
        highlightReconstruction: Boolean,
        keepVignetting: Boolean,
        tonemap: Int,
    ): PreviewFrame? {
        val h = handle
        if (h == 0L || closed.get()) return null
        return try {
            develop.renderFrame(
                h, clipHandle, frameIndex, maxDim,
                exposureEv, highlightReconstruction, keepVignetting, tonemap,
            )
        } catch (t: Throwable) {
            null
        }
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
