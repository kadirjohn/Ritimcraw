package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The editor's preview session seam: ONE clip open for the editor's whole
 * lifetime, renders and timestamps through it, a drained close — whatever
 * engine backs the clip. Two implementations:
 *  - [PreviewSession] (MCRAW): the persistent native session, including the
 *    CFA-cached interactive path ([PreviewSession.renderCached]).
 *  - [EncodedPreviewSession] (Mi-Log MP4): [EncodedSource] under the same
 *    single-engine-thread discipline; its renderCached maps onto a plain
 *    render (the encoded engine has no cached re-develop — every render
 *    decodes — so no cache is invented here).
 *
 * The seam is deliberately this narrow: render / renderCached / framePtsUs /
 * frames / close is everything the editor's render pump, transport and
 * playback loop need, so the screen never learns which engine it talks to
 * and both clip kinds get the identical transport.
 */
interface EditorPreviewSession : AutoCloseable {

    /** Frames in the clip (always > 0 after a successful open). */
    val frames: Int

    /**
     * Renders frame [frameIndex] with [grade], long edge scaled to fit
     * [maxDim]. [lutPath] is the selected .cube's path (null = no LUT —
     * stage 20 bypassed, the old look; COL-07). Suspends until done;
     * resumes with the frame or a failure carrying stage + cause +
     * recovery.
     */
    suspend fun render(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String? = null,
    ): Result<PreviewFrame>

    /**
     * Interactive slider render: the MCRAW session reuses its cached CFA
     * when the frame is unchanged (a grade-only change skips the decode);
     * the encoded path has no such stage and decodes like [render].
     * [lutPath] as in [render] (COL-07).
     */
    suspend fun renderCached(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String? = null,
    ): Result<PreviewFrame>

    /** ptsUs of frame [index] for the transport's timeline; -1 when unavailable. */
    suspend fun framePtsUs(index: Int): Long
}

/**
 * The seam's open-time factory: routes a clip to its engine by [SourcePath]
 * and encapsulates the one real asymmetry between the paths — fd ownership.
 * The MCRAW session dup's the descriptor (the caller keeps its copy, so the
 * factory closes it after open, success or failure); [EncodedSource] adopts
 * it (the reader owns the descriptor from open on, success or failure, so
 * the factory must NOT close it). Callers hand a [fdProvider] and never
 * touch a descriptor themselves.
 */
object PreviewSessions {

    suspend fun open(
        path: SourcePath,
        fdProvider: () -> Int,
        engine: ExecutorService,
        newEncodedSource: () -> EncodedSource = { EncodedSource() },
    ): Result<EditorPreviewSession> {
        val fd = fdProvider()
        if (fd < 0) {
            return Result.failure(
                PreviewRenderer.Companion.PreviewRenderException(
                    "Preview failed (stage: open): source unavailable — recovery: reopen the clip"
                )
            )
        }
        return when (path) {
            // The native side dup's the fd on success and never takes it over
            // on failure: the caller's copy is redundant either way, so the
            // factory closes it — the discipline the editor used to apply
            // inline, now owned by the seam.
            SourcePath.MCRAW ->
                PreviewSession.open(fd, engine)
                    .also { PreviewRenderer.closeFdQuietly(fd) }
            // EncodedSource.open hands the descriptor to its reader
            // (adoptFd): the source owns and closes it on both success and
            // failure. Closing it here would cut a live session off its
            // file — nothing may touch the fd after this call.
            SourcePath.ENCODED ->
                EncodedPreviewSession.open(fd, engine, newEncodedSource)
        }
    }
}

/**
 * Pure ARGB downscaler for the encoded preview tiers: [EncodedSource]
 * develops FULL-resolution frames (its contract has no maxDim), while the
 * editor caps the long edge per tier (SliderTiers / PreviewTiers) exactly
 * like the native MCRAW pipeline does internally. Box-average — each
 * output pixel is the mean of its source box — keeps solid areas exact and
 * halves aliasing on edges; no frame is ever upscaled. JVM-testable.
 */
object ArgbScale {

    /**
     * Scales [src] ([width] x [height] packed ARGB_8888) so the long edge
     * fits [maxDim]. A frame already within [maxDim] — or a non-positive
     * [maxDim] — passes through at its own size.
     */
    fun fit(src: IntArray, width: Int, height: Int, maxDim: Int): PreviewFrame {
        require(width > 0 && height > 0) { "empty frame (${width}x$height)" }
        require(src.size == width * height) { "buffer ${src.size} != ${width}x$height" }
        val longEdge = maxOf(width, height)
        if (maxDim <= 0 || longEdge <= maxDim) {
            return PreviewFrame(width, height, src.copyOf())
        }
        val outW: Int
        val outH: Int
        if (width >= height) {
            outW = maxDim
            outH = maxOf(1, Math.round(height.toDouble() * maxDim / width).toInt())
        } else {
            outH = maxDim
            outW = maxOf(1, Math.round(width.toDouble() * maxDim / height).toInt())
        }
        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val y0 = oy * height / outH
            val y1 = maxOf(y0 + 1, (oy + 1) * height / outH)
            for (ox in 0 until outW) {
                val x0 = ox * width / outW
                val x1 = maxOf(x0 + 1, (ox + 1) * width / outW)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val p = src[row + x]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        n++
                    }
                }
                out[oy * outW + ox] =
                    ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or
                        ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return PreviewFrame(outW, outH, out)
    }
}

/**
 * [EditorPreviewSession] over an [EncodedSource]: the Mi-Log MP4 engine
 * under the seam. One source per session, every call submitted to the SAME
 * single preview engine thread as the MCRAW session (EncodedSource's
 * contract: one decoder instance, one thread), so submission order is
 * execution order and [close] — submitted last — is drained.
 *
 * Failures never throw to the caller: EncodedSource's
 * IllegalStateExceptions already carry stage + cause + recovery and land
 * as Result failures, so the editor's existing error surface shows them
 * unchanged.
 */
class EncodedPreviewSession private constructor(
    private val source: EncodedSource,
    override val frames: Int,
    private val engine: ExecutorService,
) : EditorPreviewSession {

    private val closed = AtomicBoolean(false)

    override suspend fun render(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String?,
    ): Result<PreviewFrame> = renderOnEngine(frameIndex, grade, maxDim, lutPath)

    /**
     * The encoded engine has no cached re-develop stage: its decode
     * repositions whenever a request does not continue the current position —
     * including a same-frame re-render — so every render decodes. The
     * interactive drag path therefore maps 1:1 onto [render]; no caching is
     * invented for an engine that lacks it.
     */
    override suspend fun renderCached(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String?,
    ): Result<PreviewFrame> = renderOnEngine(frameIndex, grade, maxDim, lutPath)

    private suspend fun renderOnEngine(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String? = null,
    ): Result<PreviewFrame> {
        if (closed.get()) {
            return Result.failure(sessionClosed())
        }
        if (frameIndex !in 0 until frames) {
            return Result.failure(
                IllegalArgumentException(
                    "frameIndex $frameIndex out of range (frameCount=$frames)"
                )
            )
        }
        return suspendCoroutine { cont ->
            try {
                engine.execute {
                    val outcome: Result<PreviewFrame> = try {
                        if (closed.get()) {
                            // Closed while queued: never touch the dead source.
                            Result.failure(sessionClosed())
                        } else {
                            frameOf(source.renderFrame(frameIndex, grade, lutPath), maxDim)
                        }
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(Result.failure(engineUnavailable()))
            }
        }
    }

    /**
     * ptsUs of frame [index] (presentation order). Runs on the engine
     * thread; -1 on any failure (throw-free, like the MCRAW session's
     * framePtsUs contract).
     */
    override suspend fun framePtsUs(index: Int): Long = suspendCoroutine { cont ->
        try {
            engine.execute {
                cont.resume(try { source.ptsUs(index) } catch (t: Throwable) { -1L })
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(-1L)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                // Submission order on the single engine thread = execution
                // order: the close runs after every render submitted before
                // it (a drained close), then releases the decoder, the
                // extractor and the adopted fd (EncodedSource.close).
                engine.execute { source.close() }
            } catch (e: RejectedExecutionException) {
                // Engine already down; the kernel reclaims the fd on exit.
            }
        }
    }

    /** Renders the develop tail's [w, h, pixels...] buffer at the tier. */
    private fun frameOf(out: IntArray, maxDim: Int): Result<PreviewFrame> {
        if (out.size < 2) {
            return Result.failure(malformed())
        }
        val w = out[0]
        val h = out[1]
        if (w <= 0 || h <= 0 || out.size != 2 + w * h) {
            return Result.failure(malformed())
        }
        return Result.success(ArgbScale.fit(out.copyOfRange(2, out.size), w, h, maxDim))
    }

    companion object {

        /**
         * Opens an [EncodedPreviewSession] for fd on [engine]. The fd is
         * ADOPTED by the source's reader (ParcelFileDescriptor.adoptFd): the
         * source owns and closes it on both success and failure — a failed
         * open releases both boundaries — so the caller must never close it
         * after this call. Fails (never throws) with the state machine's
         * stage + cause + recovery message.
         *
         * No nativeAvailable early check, unlike PreviewSession.open: the
         * encoded OPEN is pure framework work (extractor + codec); the
         * native lib only gates the develop stage, which fails cleanly
         * per-render with the unavailable message (NativeEncodedDeveloper).
         * The editor's canRender gate already refuses to open without the
         * lib, and JVM tests exercise this whole path with fakes.
         */
        suspend fun open(
            fd: Int,
            engine: ExecutorService,
            newSource: () -> EncodedSource = { EncodedSource() },
        ): Result<EncodedPreviewSession> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val outcome: Result<EncodedPreviewSession> = try {
                        val source = newSource()
                        val info = source.open(fd)
                        Result.success(EncodedPreviewSession(source, info.frameCount, engine))
                    } catch (t: Throwable) {
                        // A failed open owns nothing: EncodedSource already
                        // released both boundaries (and the adopted fd).
                        Result.failure(t)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(Result.failure(engineUnavailable()))
            }
        }

        private fun sessionClosed(): PreviewRenderer.Companion.PreviewRenderException =
            PreviewRenderer.Companion.PreviewRenderException(
                "Preview failed (stage: open): session closed — recovery: reopen the clip"
            )

        private fun engineUnavailable(): PreviewRenderer.Companion.PreviewRenderException =
            PreviewRenderer.Companion.PreviewRenderException(
                "Preview failed (stage: open): preview engine unavailable — recovery: reopen the clip"
            )

        private fun malformed(): PreviewRenderer.Companion.PreviewRenderException =
            PreviewRenderer.Companion.PreviewRenderException(
                "Preview render failed (stage: develop): malformed frame buffer — recovery: reopen the clip"
            )
    }
}
