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
    override val width: Int,
    override val height: Int,
    private val engine: ExecutorService,
    override val rawWhiteLevel: Int,
) : EditorPreviewSession {

    override val frames: Int get() = frameCount

    /**
     * Renders frame [frameIndex] with [grade], long edge scaled to fit
     * [maxDim]. [lutPath] is the selected .cube's path (null = no LUT —
     * stage 24 bypassed, the old look; COL-07). [gyroPath] is the attached
     * gyro log's path (null = no warp — gyro Phase 5; the stabilization
     * gate is [GradeMapping.stabilizationArgs], applied at this render's
     * tier). Suspends until done; resumes with the frame or a failure
     * carrying stage + cause + recovery. A closed session fails cleanly
     * instead of touching the dead handle.
     */
    override suspend fun render(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String?,
        gyroPath: String?,
    ): Result<PreviewFrame> {
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
                            renderBlocking(frameIndex, grade, maxDim, lutPath, gyroPath)
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
    private fun renderBlocking(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String? = null,
        gyroPath: String? = null,
    ): Result<PreviewFrame> {
        val args = GradeMapping.toNative(grade)
        // DN-08 tier policy: temporal NR only at the settle/paused tier —
        // the drag (480) and playback (720) tiers skip the full-res merge.
        val temporal = args.copy(
            temporalNr = args.temporalNr && PreviewTiers.temporalNrAppliesAt(maxDim),
        )
        // Gyro Phase 5: the stabilization gate at this render's tier (null
        // stab = no warp — off, tier skip, or no attached log).
        val stab = GradeMapping.stabilizationArgs(grade, maxDim, gyroPath)
        return renderArray(
            NativeSource.jniPreviewRender(
                handle, frameIndex,
                args.exposureEv, args.highlights, args.tempK, args.tint,
                args.saturation, args.blackPoint, args.whitePoint,
                args.shadows, args.midtones, args.contrast,
                args.asShot, args.highlightReconstruction, args.keepVignetting,
                args.sharpness, args.detail, args.tonemap,
                lutPath, args.lutStrength,
                args.noiseReduction, args.chromaDenoise,
                temporal.temporalNr, temporal.temporalStrength,
                // COL-13 color package: after temporalStrength, before gyro.
                args.wheelShadowX, args.wheelShadowY,
                args.wheelMidX, args.wheelMidY,
                args.wheelHighX, args.wheelHighY,
                args.splitShadowHue, args.splitHighlightHue, args.splitBalance,
                stab?.gyroPath, stab?.mode ?: 0, stab?.strength ?: 0f,
                stab?.zoomLimit ?: 0f, stab?.focalMm ?: 0f, stab?.trimMs ?: 0f,
                maxDim,
            )
        )
    }

    /**
     * Interactive slider render: same contract as [render] (including
     * [lutPath], COL-07), but the bridge reuses the session's cached CFA
     * when [frameIndex] is the frame already decoded — a grade-only change
     * skips the decode entirely (owner request 2026-11-10 section 1).
     * Meant for the low-latency drag tier; the full-quality settle render
     * can keep using [render].
     */
    override suspend fun renderCached(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String?,
        gyroPath: String?,
    ): Result<PreviewFrame> {
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
                            // Gyro Phase 5: the stabilization gate at this
                            // render's tier (null stab = no warp).
                            val stab = GradeMapping.stabilizationArgs(grade, maxDim, gyroPath)
                            renderArray(
                                NativeSource.jniPreviewSessionRenderCached(
                                    handle, frameIndex,
                                    args.exposureEv, args.highlights, args.tempK, args.tint,
                                    args.saturation, args.blackPoint, args.whitePoint,
                                    args.shadows, args.midtones, args.contrast,
                                    args.asShot, args.highlightReconstruction, args.keepVignetting,
                                    args.sharpness, args.detail, args.tonemap,
                                    lutPath, args.lutStrength,
                                    args.noiseReduction, args.chromaDenoise,
                                    temporal.temporalNr, temporal.temporalStrength,
                                    // COL-13 color package: after temporalStrength, before gyro.
                                    args.wheelShadowX, args.wheelShadowY,
                                    args.wheelMidX, args.wheelMidY,
                                    args.wheelHighX, args.wheelHighY,
                                    args.splitShadowHue, args.splitHighlightHue, args.splitBalance,
                                    stab?.gyroPath, stab?.mode ?: 0, stab?.strength ?: 0f,
                                    stab?.zoomLimit ?: 0f, stab?.focalMm ?: 0f, stab?.trimMs ?: 0f,
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

    /**
     * PRE-DEVELOP RAW CFA histogram stats of frame [frameIndex] (feature
     * N6): the sensor-domain histogram of the pristine CFA, reusing the
     * session's interactive CFA cache (the bridge's nativeRawHistogram
     * mirrors renderCached's cache discipline — the frame the editor is
     * looking at is served from the cache or the temporal ring, never a
     * second decode). Runs on the SAME engine thread as the renders
     * (thread-local error state + one native session), so a stats call
     * never races a render. The stats do not move with grade sliders;
     * they change only with the frame. A closed session fails cleanly.
     */
    override suspend fun rawHistogram(frameIndex: Int): Result<RawHistogram> {
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
                    val outcome: Result<RawHistogram> = try {
                        if (closed.get()) {
                            // Closed while queued: never touch the dead handle.
                            Result.failure(
                                PreviewRenderer.Companion.PreviewRenderException(
                                    "Preview failed (stage: open): session closed — " +
                                        "recovery: reopen the clip"
                                )
                            )
                        } else {
                            rawHistogramBlocking(frameIndex)
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

    /**
     * Blocking RAW stats call; MUST run on the engine thread (thread-local
     * error state). The error mapping mirrors renderArray: a null buffer
     * reads the bridge's stage + cause + recovery, a malformed buffer
     * (version/length drift) is a failure — never fabricated stats.
     */
    private fun rawHistogramBlocking(frameIndex: Int): Result<RawHistogram> {
        val out = NativeSource.jniRawHistogram(handle, frameIndex)
        if (out == null) {
            val code = NativeSource.jniPreviewErrorCode()
            val stage = NativeSource.jniPreviewErrorStage()
            val detail = NativeSource.previewLastError()
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    PreviewErrors.message(code, stage, detail)
                )
            )
        }
        val stats = RawHistogram.fromJni(out)
        if (stats == null) {
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "RAW histogram failed (stage: develop): malformed stats buffer — " +
                        "recovery: reopen the clip"
                )
            )
        }
        if (stats.frameIndex != frameIndex) {
            // The stale guard's last line of defense: the bridge echoed a
            // different frame than asked — surface it, never draw it.
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "RAW histogram failed (stage: develop): stale frame stats — " +
                        "recovery: reopen the clip"
                )
            )
        }
        return Result.success(stats)
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

    /**
     * The stabilization zoom verdict (audit fix 2026-11-12, Fix 5): the
     * native probe over this session's handle, confined to the engine
     * thread (thread-local gyro cache + error state — the bridge comment's
     * binding rule) exactly like framePtsUs. Null is the honest no-verdict
     * (dead handle / no log / parse failure), never a fabricated zoom; a
     * closed session's handle is dead, so the same null covers it.
     */
    override suspend fun stabZoomProbe(
        gyroPath: String,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
    ): DoubleArray? = suspendCoroutine { cont ->
        try {
            engine.execute {
                cont.resume(
                    try {
                        if (closed.get()) {
                            null // closed while queued: never touch the dead handle
                        } else {
                            NativeSource.jniStabZoomProbe(
                                handle, gyroPath, stabMode, stabStrength,
                                stabZoomLimit, stabFocalMm, stabTrimMs,
                            )
                        }
                    } catch (t: Throwable) {
                        null // engine-side failure: no verdict, never fabricated
                    }
                )
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(null)
        }
    }

    /**
     * The MCRAW clip's audio for editor playback (owner request
     * 2026-11-11 section 4): the native readAudio one-shot (whole-clip
     * interleaved 16-bit PCM — the core/Source.h contract), read ONCE on
     * the engine thread (thread-local error state; the read is one-shot,
     * never per-chunk) and handed to the pure [PcmClipAudioSource], which
     * slices chunks and seeks in plain memory afterwards. [fdProvider] is
     * ignored — the handle already owns the dup'd descriptor.
     *
     * Null (never a throw) when the clip has no audio — audioInfo 0/0 or
     * an empty PCM — or the session is closed: silent playback, the
     * previous behavior. The AUD-06 rule holds: the sample rate travels
     * with the PCM, it is never assumed.
     */
    override suspend fun openAudio(fdProvider: () -> Int): ClipAudioSource? {
        if (!SessionRules.isActive(opened = true, closed = closed.get())) return null
        return suspendCoroutine { cont ->
            try {
                engine.execute {
                    val source: ClipAudioSource? = try {
                        if (closed.get()) {
                            null
                        } else {
                            pcmAudioSource()
                        }
                    } catch (t: Throwable) {
                        null
                    }
                    cont.resume(source)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(null)
            }
        }
    }

    /** Blocking PCM read; MUST run on the engine thread. */
    private fun pcmAudioSource(): ClipAudioSource? {
        val info = NativeSource.jniAudioInfo(handle)
        if (info == null || info.size < 2 || info[0] <= 0 || info[1] <= 0) return null
        val pcm = NativeSource.jniReadAudio(handle) ?: return null
        if (pcm.isEmpty()) return null
        return PcmClipAudioSource(info[0], info[1], pcm)
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
            if (info == null || info.size < 7 || info[0] == 0) {
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
            // info[2]/info[3] are the first frame's RECORDED dimensions (the
            // clip's source resolution, owner request 2026-11-11 section 2) —
            // kept here instead of dropped at the open boundary. info[6] is
            // the container RAW white level (section 3: the clip info sheet's
            // "RAW white level" row; the per-frame dynamic levels ride
            // FrameInfo into the render/DNG calls instead).
            return Result.success(
                PreviewSession(
                    info[0].toLong(),
                    info[1],
                    info[2],
                    info[3],
                    engine,
                    info[6],
                )
            )
        }
    }
}
