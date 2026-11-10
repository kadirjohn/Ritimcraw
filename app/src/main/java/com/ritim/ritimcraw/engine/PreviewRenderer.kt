package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * One rendered preview frame: dimensions plus packed 8-bit ARGB pixels
 * (0xAARRGGBB, row-major), ready for Bitmap.setPixels.
 */
data class PreviewFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PreviewFrame && other.width == width && other.height == height &&
            other.argb.contentEquals(argb)

    override fun hashCode(): Int = 31 * (31 * width + height) + argb.contentHashCode()
}

/**
 * Pure GradeK -> native argument mapping (the field-for-field contract with
 * rawedit::Grade in core/include/rawedit/Develop.h). Separated from the
 * renderer so the mapping is JVM-testable without the native lib.
 */
object GradeMapping {

    data class NativeArgs(
        val exposureEv: Float,
        val highlights: Float,
        val tempK: Float,
        val tint: Float,
    )

    fun toNative(grade: GradeK): NativeArgs = NativeArgs(
        exposureEv = grade.exposureEv,
        highlights = grade.highlights,
        tempK = grade.tempK,
        tint = grade.tint,
    )
}

/**
 * Pure render-scheduling decisions for the editor's debounced preview, kept
 * free of coroutines so they are unit-testable (kotlinx-coroutines-test is
 * not in the offline cache; the screen wires these into LaunchedEffect +
 * delay, which already gives trailing-edge debounce semantics).
 */
object RenderDebounce {

    /** Slider-settle window before a re-render (DESIGN.md preview re-render). */
    const val WINDOW_MS = 300L

    /**
     * True when a render whose previous request came at [lastRequestMs]
     * may run now ([nowMs]): the first-ever request always renders, later
     * ones wait for the trailing edge of [windowMs].
     */
    fun shouldRender(lastRequestMs: Long?, nowMs: Long, windowMs: Long = WINDOW_MS): Boolean =
        lastRequestMs == null || nowMs - lastRequestMs >= windowMs

    /**
     * True when a finished render ([sequence]) is still the newest request
     * ([latestSequence]); stale results are dropped so an old frame can
     * never overwrite a newer one.
     */
    fun isFresh(sequence: Long, latestSequence: Long): Boolean = sequence >= latestSequence
}

/**
 * Renders preview frames from an opened clip through the native preview
 * pipeline (decode -> developThumbnail -> linearToRec709 -> ARGB).
 *
 * All native calls run on ONE shared engine thread (one motioncam decoder
 * instance is not safe for concurrent use, and the bridge's error state is
 * thread-local), so at most one decode is ever in flight no matter how fast
 * the user drags sliders. The suspend [render] entry point confines the
 * whole open -> render -> close sequence to that thread and resumes the
 * caller from it; the editor never touches a handle directly.
 *
 * JVM unit tests run with [NativeSource.nativeAvailable] == false, where
 * open fails cleanly with the unavailable message — no .so needed.
 */
class PreviewRenderer private constructor(
    private val handle: Long,
    private val frameCount: Int,
    private val engine: ExecutorService,
) : AutoCloseable {

    val frames: Int get() = frameCount

    /**
     * Renders frame [frameIndex] with [grade] scaled so the long edge fits
     * [maxDim]. MUST run on the renderer's engine thread (the bridge's
     * error state is thread-local) — the suspend [render] companion
     * function does that for callers.
     */
    internal fun renderBlocking(frameIndex: Int, grade: GradeK, maxDim: Int): Result<PreviewFrame> {
        if (frameIndex < 0 || (frameCount > 0 && frameIndex >= frameCount)) {
            return Result.failure(
                IllegalArgumentException(
                    "frameIndex $frameIndex out of range (frameCount=$frameCount)"
                )
            )
        }
        val args = GradeMapping.toNative(grade)
        val out = NativeSource.nativePreviewRender(
            handle, frameIndex,
            args.exposureEv, args.highlights, args.tempK, args.tint,
            maxDim,
        )
        if (out == null || out.size < 2) {
            val code = NativeSource.nativePreviewErrorCode()
            val stage = NativeSource.nativePreviewErrorStage()
            val detail = NativeSource.previewLastError()
            return Result.failure(PreviewRenderException(PreviewErrors.message(code, stage, detail)))
        }
        val w = out[0]
        val h = out[1]
        if (w <= 0 || h <= 0 || out.size != 2 + w * h) {
            return Result.failure(
                PreviewRenderException(
                    "Preview render failed (stage: develop): malformed frame buffer — " +
                        "recovery: reopen the clip"
                )
            )
        }
        return Result.success(PreviewFrame(w, h, out.copyOfRange(2, out.size)))
    }

    override fun close() {
        if (!closed) {
            closed = true
            try {
                engine.execute { NativeSource.nativeCloseSource(handle) }
            } catch (e: RejectedExecutionException) {
                // Engine already down; the bridge slot frees on process exit.
            }
        }
    }

    @Volatile
    private var closed = false

    companion object {

        /** Failure carrying the stage + cause + recovery message. */
        class PreviewRenderException(message: String) : Exception(message)

        /**
         * The single preview engine thread. Daemon: it must not keep the
         * process alive, and it is shared (not per-render) so at most one
         * frame decode is in flight at a time.
         */
        private val previewEngine: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "rawedit-preview").apply { isDaemon = true }
            }
        }

        /**
         * Maps a nativePreviewOpen result ([handle, frameCount, w, h, cfa,
         * orient] or null) to a renderer or a failure with the bridge's
         * thread-local code + stage + detail. MUST be called on the engine
         * thread that made the native call (thread-local error state), and
         * never re-submits to it (that would deadlock a single thread).
         */
        private fun openResult(info: IntArray?, engine: ExecutorService): Result<PreviewRenderer> {
            if (info == null || info.size < 6 || info[0] == 0) {
                val code = NativeSource.nativePreviewErrorCode()
                val stage = NativeSource.nativePreviewErrorStage()
                val detail = NativeSource.previewLastError()
                return Result.failure(
                    PreviewRenderException(PreviewErrors.message(code, stage, detail))
                )
            }
            // frameCount == 0 is rejected by the bridge, so info[1] > 0.
            return Result.success(PreviewRenderer(info[0].toLong(), info[1], engine))
        }

        /**
         * Opens a preview renderer for fd on [engine]. Fails (never throws)
         * with the stage + cause + recovery message when the native engine
         * is absent or the clip cannot be opened. JVM tests pass their own
         * executor; with [NativeSource.nativeAvailable] == false the
         * executor is never touched.
         */
        internal fun open(fd: Int, engine: ExecutorService): Result<PreviewRenderer> {
            if (!NativeSource.nativeAvailable) {
                return Result.failure(
                    PreviewRenderException(NativeSource.nativeUnavailableMessage)
                )
            }
            return try {
                val opened: java.util.concurrent.Future<IntArray?> =
                    engine.submit<IntArray?> { NativeSource.nativePreviewOpen(fd) }
                openResult(opened.get(), engine)
            } catch (e: RejectedExecutionException) {
                Result.failure(PreviewRenderException("preview engine unavailable"))
            } catch (e: java.util.concurrent.ExecutionException) {
                Result.failure(
                    PreviewRenderException(
                        PreviewErrors.message(
                            PreviewErrors.CODE_IO, PreviewErrors.STAGE_OPEN, e.cause?.message
                        )
                    )
                )
            }
        }

        /**
         * Suspends until frame [frameIndex] is rendered with [grade] scaled
         * to [maxDim]. The whole open -> render -> close sequence runs on
         * the shared preview engine thread and the continuation resumes from
         * there — the caller never blocks its own dispatcher.
         *
         * One clip open per call keeps the fd lifecycle trivial; the open is
         * header/index-only (no frame decode). Fails cleanly (never throws)
         * with the unavailable message when the native lib is absent — the
         * editor shows the placeholder.
         */
        suspend fun render(
            sourceFd: Int,
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
        ): Result<PreviewFrame> = suspendCoroutine { cont ->
            try {
                previewEngine.execute {
                    val outcome = try {
                        if (!NativeSource.nativeAvailable) {
                            Result.failure<PreviewFrame>(
                                PreviewRenderException(NativeSource.nativeUnavailableMessage)
                            )
                        } else {
                            // Direct call (not open()): we are ON the engine
                            // thread, so submitting to it would deadlock.
                            openResult(NativeSource.nativePreviewOpen(sourceFd), previewEngine).fold(
                                onSuccess = { r ->
                                    val res = r.renderBlocking(frameIndex, grade, maxDim)
                                    r.close()
                                    res
                                },
                                onFailure = { Result.failure<PreviewFrame>(it) },
                            )
                        }
                    } catch (e: Throwable) {
                        Result.failure<PreviewFrame>(e)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(Result.failure(PreviewRenderException("preview engine unavailable")))
            }
        }
    }
}
