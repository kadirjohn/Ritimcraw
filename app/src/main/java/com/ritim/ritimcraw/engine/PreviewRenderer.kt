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
        val saturation: Float,
        val blackPoint: Float,
        val whitePoint: Float,
        val shadows: Float,
        val midtones: Float,
        val contrast: Float,
        val asShot: Boolean,
        val highlightReconstruction: Boolean,
        val keepVignetting: Boolean,
        val sharpness: Float,
        val detail: Float,
        val tonemap: Int,
        val lutStrength: Float,
        val noiseReduction: Float,
        val chromaDenoise: Float,
        val temporalNr: Boolean,
        val temporalStrength: Float,
    )

    fun toNative(grade: GradeK): NativeArgs = NativeArgs(
        exposureEv = grade.exposureEv,
        highlights = grade.highlights,
        tempK = grade.tempK,
        tint = grade.tint,
        saturation = grade.saturation,
        blackPoint = grade.blackPoint,
        whitePoint = grade.whitePoint,
        shadows = grade.shadows,
        midtones = grade.midtones,
        contrast = grade.contrast,
        asShot = grade.asShot,
        highlightReconstruction = grade.highlightReconstruction,
        keepVignetting = grade.keepVignetting,
        sharpness = grade.sharpness,
        detail = grade.detail,
        tonemap = grade.tonemap,
        // COL-07: the UI percent 0..100 becomes rawedit's 0..1 blend here,
        // at the Kotlin boundary — every JNI call site passes native scale.
        lutStrength = grade.lutStrength / 100f,
        // DN-01..08: both denoise controls are ALREADY at rawedit's native
        // 0..100 scale in GradeK (unlike lutStrength) — pass through; the
        // core clamps [0,100] itself.
        noiseReduction = grade.noiseReduction,
        chromaDenoise = grade.chromaDenoise,
        temporalNr = grade.temporalNr,
        temporalStrength = grade.temporalStrength,
    )

    /**
     * The gyro stabilization arguments for one render (gyro Phase 5): the
     * six flat JNI params the bridge appends after temporalStrength. This
     * is the ONE testable gate: gyroPath is null — the no-warp default —
     * when stabilization is off in the grade, when the tier skips the warp
     * ([PreviewTiers.stabilizationAppliesAt], the temporal NR precedent),
     * or when no gyro log is attached ([gyroPath] null). When it runs, the
     * strength maps GradeK's 0..100 percent to rawedit's 0..1 here (the
     * lutStrength precedent); the mode/zoomLimit/focalMm/trimMs pass
     * through raw (the C++ side owns mode ints, the focal override 0 =
     * use-container contract, and the trim composition).
     */
    data class StabilizationArgs(
        val gyroPath: String?,
        val mode: Int,
        val strength: Float,
        val zoomLimit: Float,
        val focalMm: Float,
        val trimMs: Float,
    )

    fun stabilizationArgs(
        grade: GradeK,
        maxDim: Int,
        gyroPath: String?,
    ): StabilizationArgs? {
        if (!grade.stabilization) return null
        if (gyroPath == null) return null
        if (!PreviewTiers.stabilizationAppliesAt(maxDim)) return null
        return StabilizationArgs(
            gyroPath = gyroPath,
            mode = grade.stabilizationMode,
            strength = grade.stabilizationStrength / 100f,
            zoomLimit = grade.stabilizationZoomLimit,
            focalMm = grade.stabilizationFocalMm,
            trimMs = grade.stabilizationTrimMs,
        )
    }
}

/**
 * Pure render-scheduling decisions for the editor's debounced preview, kept
 * free of coroutines so they are unit-testable (kotlinx-coroutines-test is
 * not in the offline cache; the screen wires these into LaunchedEffect +
 * delay, which already gives trailing-edge debounce semantics).
 */
object RenderDebounce {

    /**
     * Slider-settle window before a re-render (owner request 2026-11-10
     * section 1: 300 ms felt sluggish; interactive drags now render at the
     * low tier directly, so the debounced full render follows quickly).
     */
    const val WINDOW_MS = 120L

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
    val width: Int,
    val height: Int,
    private val engine: ExecutorService,
) : AutoCloseable {

    val frames: Int get() = frameCount

    /**
     * Renders frame [frameIndex] with [grade] scaled so the long edge fits
     * [maxDim]. [lutPath] is the selected .cube's path (null = no LUT —
     * stage 22 bypassed, the old look; COL-07). [gyroPath] is the attached
     * gyro log's path (null = no warp — gyro Phase 5; the stabilization
     * gate is [GradeMapping.stabilizationArgs], applied here at this
     * render's tier). MUST run on the renderer's engine thread (the
     * bridge's error state is thread-local) — the suspend [render]
     * companion function does that for callers.
     */
    internal fun renderBlocking(
        frameIndex: Int,
        grade: GradeK,
        maxDim: Int,
        lutPath: String? = null,
        gyroPath: String? = null,
    ): Result<PreviewFrame> {
        if (frameIndex < 0 || (frameCount > 0 && frameIndex >= frameCount)) {
            return Result.failure(
                IllegalArgumentException(
                    "frameIndex $frameIndex out of range (frameCount=$frameCount)"
                )
            )
        }
        val args = GradeMapping.toNative(grade)
        // DN-08 tier policy: temporal NR only at the settle/paused tier —
        // the drag (480) and playback (720) tiers skip the full-res merge.
        val temporal = args.copy(
            temporalNr = args.temporalNr && PreviewTiers.temporalNrAppliesAt(maxDim),
        )
        // Gyro Phase 5: the stabilization gate at this render's tier (null
        // stab = no warp — off, tier skip, or no attached log).
        val stab = GradeMapping.stabilizationArgs(grade, maxDim, gyroPath)
        val out = NativeSource.jniPreviewRender(
            handle, frameIndex,
            args.exposureEv, args.highlights, args.tempK, args.tint,
            args.saturation, args.blackPoint, args.whitePoint,
            args.shadows, args.midtones, args.contrast,
            args.asShot, args.highlightReconstruction, args.keepVignetting,
            args.sharpness, args.detail, args.tonemap,
            lutPath, args.lutStrength,
            args.noiseReduction, args.chromaDenoise,
            temporal.temporalNr, temporal.temporalStrength,
            stab?.gyroPath, stab?.mode ?: 0, stab?.strength ?: 0f,
            stab?.zoomLimit ?: 0f, stab?.focalMm ?: 0f, stab?.trimMs ?: 0f,
            maxDim,
        )
        if (out == null || out.size < 2) {
            val code = NativeSource.jniPreviewErrorCode()
            val stage = NativeSource.jniPreviewErrorStage()
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
                engine.execute { NativeSource.jniCloseSource(handle) }
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
         * The single preview engine thread, shared by the persistent
         * PreviewSession path and the per-render fallback so all preview
         * native calls stay confined to one thread (submission order there
         * is execution order, which the session's drained close relies on).
         * Daemon: it must not keep the process alive.
         */
        internal val previewEngine: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "rawedit-preview").apply { isDaemon = true }
            }
        }

        /**
         * Maps a nativePreviewOpen result ([handle, frameCount, w, h, cfa,
         * orient, containerWhite] or null) to a renderer or a failure with
         * the bridge's thread-local code + stage + detail. MUST be called on
         * the engine thread that made the native call (thread-local error
         * state), and never re-submits to it (that would deadlock a single
         * thread). The renderer is per-render fallback only — the white
         * level (element 7) belongs to the session and is not read here.
         */
        private fun openResult(info: IntArray?, engine: ExecutorService): Result<PreviewRenderer> {
            if (info == null || info.size < 7 || info[0] == 0) {
                val code = NativeSource.jniPreviewErrorCode()
                val stage = NativeSource.jniPreviewErrorStage()
                val detail = NativeSource.previewLastError()
                return Result.failure(
                    PreviewRenderException(PreviewErrors.message(code, stage, detail))
                )
            }
            // frameCount == 0 is rejected by the bridge, so info[1] > 0.
            // info[2]/info[3] are the first frame's RECORDED dimensions (the
            // clip's source resolution, owner request 2026-11-11 section 2) —
            // kept here instead of dropped at the open boundary.
            return Result.success(
                PreviewRenderer(
                    info[0].toLong(),
                    info[1],
                    info[2],
                    info[3],
                    engine,
                )
            )
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
                    engine.submit<IntArray?> { NativeSource.jniPreviewOpen(fd) }
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
         * to [maxDim]. [lutPath] is the selected .cube's path (null = no
         * LUT, the old look; COL-07). [gyroPath] is the attached gyro log's
         * path (null = no warp — gyro Phase 5). The whole open -> render ->
         * close sequence runs on the shared preview engine thread and the
         * continuation resumes from there — the caller never blocks its own
         * dispatcher.
         *
         * [sourceFd] must be a FRESH descriptor opened for this render and
         * is closed here (always, success or failure). Sharing one fd
         * across renders is broken by design: the C++ side dup()s it, but a
         * dup SHARES the file offset, so the first open's reads leave the
         * caller's descriptor mid-file and the next render reads a header
         * from the middle of the clip ("Invalid container version"). The
         * editor therefore opens a new read-only descriptor per render and
         * hands it here; nothing else may touch it afterwards.
         */
        suspend fun render(
            sourceFd: Int,
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
            lutPath: String? = null,
            gyroPath: String? = null,
        ): Result<PreviewFrame> = suspendCoroutine { cont ->
            try {
                previewEngine.execute {
                    val outcome = try {
                        if (!NativeSource.nativeAvailable) {
                            closeFdQuietly(sourceFd)
                            Result.failure<PreviewFrame>(
                                PreviewRenderException(NativeSource.nativeUnavailableMessage)
                            )
                        } else {
                            // Direct call (not open()): we are ON the engine
                            // thread, so submitting to it would deadlock.
                            openResult(NativeSource.jniPreviewOpen(sourceFd), previewEngine).fold(
                                onSuccess = { r ->
                                    val res = r.renderBlocking(frameIndex, grade, maxDim, lutPath, gyroPath)
                                    r.close()
                                    res
                                },
                                onFailure = { Result.failure<PreviewFrame>(it) },
                            )
                        }
                    } catch (e: Throwable) {
                        Result.failure<PreviewFrame>(e)
                    } finally {
                        closeFdQuietly(sourceFd)
                    }
                    cont.resume(outcome)
                }
            } catch (e: RejectedExecutionException) {
                closeFdQuietly(sourceFd)
                cont.resume(Result.failure(PreviewRenderException("preview engine unavailable")))
            }
        }

        /** Closes a raw fd without throwing (adopts then closes it). */
        internal fun closeFdQuietly(fd: Int) {
            if (fd >= 0) {
                try {
                    android.os.ParcelFileDescriptor.adoptFd(fd).close()
                } catch (ignored: Throwable) {
                    // best effort; the kernel reclaims it on process exit
                }
            }
        }
    }
}
