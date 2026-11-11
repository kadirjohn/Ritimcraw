package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure session bookkeeping for the persistent preview session (JVM-testable
 * without the native lib): the fd's ownership rules and the close-exactly-once
 * contract.
 */
object SessionRules {

    /**
     * True when this session may still submit native work: opened and not
     * yet closed. After close() the session is dead forever — the editor
     * opens a NEW session for the next clip rather than reusing one.
     */
    fun isActive(opened: Boolean, closed: Boolean): Boolean = opened && !closed
}

/**
 * A persistent native preview session over ONE clip, opened once for the
 * editor's lifetime (owner request 2026-11-10 §1: keep the decoder open for
 * the whole editor session).
 *
 * The whole point: [open] runs once, then [render] reuses the SAME native
 * Source — no per-render file open, no per-render decoder construction. The
 * C++ side owns its dup'd FILE* and seeks internally, so the fd-offset bug
 * that forced a fresh fd per render (commit ce73b90) does not apply here.
 *
 * Concurrency contract:
 * - Every native call (open, render, close) is submitted to the SINGLE
 *   shared preview engine thread, in submission order. A close submitted at
 *   dispose time therefore runs AFTER all previously submitted renders —
 *   a drained close, never a close racing a render.
 * - [close] closes exactly once (AtomicBoolean). Repeated calls from
 *   onDispose + error paths are safe.
 * - The bridge's nativeCloseSource is slot-safe (range-checked registry),
 *   so closing this session can never affect another session's handle
 *   (owner request: preview/export collision must not cross-close).
 *
 * Implements [EditorPreviewSession] — the seam the editor talks to for BOTH
 * clip kinds — with zero behavior change: the members are the same members
 * the editor already called, now carrying `override`.
 */
class PreviewSession private constructor(
    private val handle: Long,
    val frameCount: Int,
    private val engine: ExecutorService,
) : EditorPreviewSession {

    override val frames: Int get() = frameCount

    /**
     * Renders frame [frameIndex] with [grade], long edge scaled to fit
     * [maxDim]. Suspends until done; resumes with the frame or a failure
     * carrying stage + cause + recovery. A closed session fails cleanly
     * instead of touching the dead handle.
     */
    override suspend fun render(frameIndex: Int, grade: GradeK, maxDim: Int): Result<PreviewFrame> {
        if (!SessionRules.isActive(opened = true, closed = closed.get())) {
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "Preview failed (stage: open): session closed — recovery: reopen the clip"
                )
            )
        }
        if (frameIndex < 0 || (frameCount > 0 && frameIndex >= frameCount)) {
            return Result.failure(
                IllegalArgumentException(
                    "frameIndex $frameIndex out of range (frameCount=$frameCount)"
                )
            )
        }
        return suspendCoroutine { cont ->
            try {
                engine.execute {
                    val outcome: Result<PreviewFrame> = try {
                        if (closed.get()) {
                            // Closed while queued: never touch the dead handle.
                            Result.failure(
                                PreviewRenderer.Companion.PreviewRenderException(
                                    "Preview failed (stage: open): session closed — " +
                                        "recovery: reopen the clip"
                                )
                            )
                        } else {
                            renderBlocking(frameIndex, grade, maxDim)
                        }
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(
                    Result.failure(
                        PreviewRenderer.Companion.PreviewRenderException(
                            "Preview failed (stage: open): preview engine unavailable — " +
                                "recovery: reopen the clip"
                        )
                    )
                )
            }
        }
    }

    /** Blocking render; MUST run on the engine thread (thread-local error state). */
    private fun renderBlocking(frameIndex: Int, grade: GradeK, maxDim: Int): Result<PreviewFrame> {
        val args = GradeMapping.toNative(grade)
        // DN-08 tier policy: temporal NR only at the settle/paused tier —
        // the drag (480) and playback (720) tiers skip the full-res merge.
        val temporal = args.copy(
            temporalNr = args.temporalNr && PreviewTiers.temporalNrAppliesAt(maxDim),
        )
        return renderArray(
            NativeSource.jniPreviewRender(
                handle, frameIndex,
                temporal.exposureEv, temporal.highlights, temporal.tempK, temporal.tint,
                temporal.saturation, temporal.blackPoint, temporal.whitePoint,
                temporal.shadows, temporal.midtones, temporal.contrast,
                temporal.asShot, temporal.highlightReconstruction, temporal.keepVignetting,
                temporal.sharpness, temporal.detail, temporal.tonemap,
                temporal.temporalNr, temporal.temporalStrength,
                maxDim,
            )
        )
    }

    /**
     * Interactive slider render: same contract as [render], but the bridge
     * reuses the session's cached CFA when [frameIndex] is the frame already
     * decoded — a grade-only change skips the decode entirely (owner request
     * 2026-11-10 section 1). Meant for the low-latency drag tier; the
     * full-quality settle render can keep using [render].
     */
    override suspend fun renderCached(frameIndex: Int, grade: GradeK, maxDim: Int): Result<PreviewFrame> {
        if (!SessionRules.isActive(opened = true, closed = closed.get())) {
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "Preview failed (stage: open): session closed — recovery: reopen the clip"
                )
            )
        }
        if (frameIndex < 0 || (frameCount > 0 && frameIndex >= frameCount)) {
            return Result.failure(
                IllegalArgumentException(
                    "frameIndex $frameIndex out of range (frameCount=$frameCount)"
                )
            )
        }
        return suspendCoroutine { cont ->
            try {
                engine.execute {
                    val outcome: Result<PreviewFrame> = try {
                        if (closed.get()) {
                            Result.failure(
                                PreviewRenderer.Companion.PreviewRenderException(
                                    "Preview failed (stage: open): session closed — " +
                                        "recovery: reopen the clip"
                                )
                            )
                        } else {
                            val args = GradeMapping.toNative(grade)
                            // DN-08 tier policy: temporal NR only at the
                            // settle/paused tier — the drag-tier cached path
                            // (480) skips the full-res merge.
                            val temporal = args.copy(
                                temporalNr = args.temporalNr && PreviewTiers.temporalNrAppliesAt(maxDim),
                            )
                            renderArray(
                                NativeSource.jniPreviewSessionRenderCached(
                                    handle, frameIndex,
                                    temporal.exposureEv, temporal.highlights, temporal.tempK, temporal.tint,
                                    temporal.saturation, temporal.blackPoint, temporal.whitePoint,
                                    temporal.shadows, temporal.midtones, temporal.contrast,
                                    temporal.asShot, temporal.highlightReconstruction, temporal.keepVignetting,
                                    temporal.sharpness, temporal.detail, temporal.tonemap,
                                    temporal.temporalNr, temporal.temporalStrength,
                                    maxDim,
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(
                    Result.failure(
                        PreviewRenderer.Companion.PreviewRenderException(
                            "Preview failed (stage: open): preview engine unavailable — " +
                                "recovery: reopen the clip"
                        )
                    )
                )
            }
        }
    }

    /** Blocking cached render; MUST run on the engine thread. */
    private fun renderArray(out: IntArray?): Result<PreviewFrame> {
        if (out == null || out.size < 2) {
            val code = NativeSource.jniPreviewErrorCode()
            val stage = NativeSource.jniPreviewErrorStage()
            val detail = NativeSource.previewLastError()
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    PreviewErrors.message(code, stage, detail)
                )
            )
        }
        val w = out[0]
        val h = out[1]
        if (w <= 0 || h <= 0 || out.size != 2 + w * h) {
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "Preview render failed (stage: develop): malformed frame buffer — " +
                        "recovery: reopen the clip"
                )
            )
        }
        return Result.success(PreviewFrame(w, h, out.copyOfRange(2, out.size)))
    }

    /**
     * ptsUs of frame [index] (for the transport's timeline). Runs on the
     * engine thread; -1 on a bad handle (frameInfo is throw-free).
     */
    override suspend fun framePtsUs(index: Int): Long = suspendCoroutine { cont ->
        try {
            engine.execute {
                cont.resume(NativeSource.jniFramePtsUs(handle, index))
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(-1L)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                // Submission order on the single engine thread = drained
                // close: runs after every render submitted before it.
                engine.execute { NativeSource.jniCloseSource(handle) }
            } catch (e: RejectedExecutionException) {
                // Engine already down; the bridge slot frees on process exit.
            }
        }
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {

        /**
         * Opens a persistent preview session for fd on [engine]. The fd is
         * dup'd by the native side and remains valid for the session's whole
         * lifetime — the caller keeps ownership of the descriptor it passed
         * (the editor's long-lived pfd) and closes it independently.
         * Fails (never throws) with stage + cause + recovery when the native
         * engine is absent or the clip cannot be opened.
         */
        suspend fun open(fd: Int, engine: ExecutorService): Result<PreviewSession> {
            if (!NativeSource.nativeAvailable) {
                return Result.failure(
                    PreviewRenderer.Companion.PreviewRenderException(
                        NativeSource.nativeUnavailableMessage
                    )
                )
            }
            return suspendCoroutine { cont ->
                try {
                    engine.execute {
                        val outcome: Result<PreviewSession> = try {
                            openBlocking(fd, engine)
                        } catch (t: Throwable) {
                            Result.failure(t)
                        }
                        cont.resume(outcome)
                    }
                } catch (e: RejectedExecutionException) {
                    cont.resume(
                        Result.failure(
                            PreviewRenderer.Companion.PreviewRenderException(
                                "Preview failed (stage: open): preview engine unavailable — " +
                                    "recovery: reopen the clip"
                            )
                        )
                    )
                }
            }
        }

        /**
         * Blocking open; MUST run on the engine thread (thread-local error
         * state). Never re-submits to it (would deadlock a single thread).
         */
        private fun openBlocking(fd: Int, engine: ExecutorService): Result<PreviewSession> {
            val info = NativeSource.jniPreviewOpen(fd)
            if (info == null || info.size < 6 || info[0] == 0) {
                val code = NativeSource.jniPreviewErrorCode()
                val stage = NativeSource.jniPreviewErrorStage()
                val detail = NativeSource.previewLastError()
                return Result.failure(
                    PreviewRenderer.Companion.PreviewRenderException(
                        PreviewErrors.message(code, stage, detail)
                    )
                )
            }
            // frameCount == 0 is rejected by the bridge, so info[1] > 0.
            return Result.success(PreviewSession(info[0].toLong(), info[1], engine))
        }
    }
}
