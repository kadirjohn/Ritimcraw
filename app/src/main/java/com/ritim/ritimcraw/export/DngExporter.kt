package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.NativeSource
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure mapping from a failed native DNG export call to the user-facing
 * message (DESIGN.md Error states: name the stage, the cause and the
 * recovery — never a bare "failed"). Kept free of Android imports so it is
 * JVM-testable; the bridge reports code + stage + detail, this object turns
 * them into one message string.
 *
 * Contract with app/src/main/cpp/jni_bridge.cpp:
 *   code  = rawedit::ErrorCode (0 ok, 1 truncated, 2 badHeader, 3 ioError,
 *           4 unsupported)
 *   stage = pipeline stage token (0 open, 1 frame read, 3 dng write)
 */
object DngExportErrors {

    // rawedit::ErrorCode (core/include/rawedit/Result.h).
    const val CODE_OK = 0
    const val CODE_TRUNCATED = 1
    const val CODE_BAD_HEADER = 2
    const val CODE_IO = 3
    const val CODE_UNSUPPORTED = 4

    // Stage tokens (jni_bridge.cpp kStageOpen/kStageFrameRead/kStageDngWrite).
    const val STAGE_OPEN = 0
    const val STAGE_FRAME_READ = 1
    const val STAGE_DNG_WRITE = 3

    /** Stage name shown in the message ("open", "frame read", "dng write"). */
    fun stageName(stage: Int): String = when (stage) {
        STAGE_OPEN -> "open"
        STAGE_FRAME_READ -> "frame read"
        STAGE_DNG_WRITE -> "dng write"
        else -> "unknown"
    }

    /**
     * Cause + recovery wording per rawedit::ErrorCode. The recovery always
     * names a user action (DESIGN.md: every error names the recovery).
     */
    fun causeAndRecovery(code: Int): Pair<String, String> = when (code) {
        CODE_TRUNCATED ->
            "truncated clip" to "re-import the clip"
        CODE_BAD_HEADER ->
            "not a supported clip" to "re-import the clip"
        CODE_IO ->
            "the file could not be read or written" to
                "check free space and the clip, then retry"
        CODE_UNSUPPORTED ->
            "unsupported clip" to "re-import the clip"
        else ->
            "unknown error" to "retry the export"
    }

    /**
     * One-line message naming stage + cause + recovery, e.g.
     * "DNG export failed (stage: frame read): truncated clip — recovery: re-import the clip".
     * The native detail (when present) rides along in brackets so the
     * device pass can see exactly what the bridge reported.
     */
    fun message(code: Int, stage: Int, detail: String? = null): String {
        val (cause, recovery) = causeAndRecovery(code)
        val base =
            "DNG export failed (stage: ${stageName(stage)}): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}

/**
 * The native DNG export boundary, as an interface so the staging/rename/
 * cleanup logic around it is JVM-testable with a fake (the real call needs
 * librawedit_jni.so — a device-gated path).
 */
interface DngRender {
    /**
     * Decodes frame [frameIndex] from fd and writes a CFA DNG at [outPath].
     * Returns null on failure; [code]/[stage]/[detail] then say why (the
     * same thread-local contract as the preview path).
     */
    fun render(fd: Int, frameIndex: Int, outPath: String): Boolean

    /** rawedit::ErrorCode of the last failed call (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported). */
    fun code(): Int

    /** Pipeline stage of the last failed call (0 open, 1 frame read, 3 dng write). */
    fun stage(): Int

    /** Bridge detail of the last failed call. */
    fun detail(): String?
}

/** The real boundary: JNI calls into librawedit_jni.so. */
internal object NativeDngRender : DngRender {
    override fun render(fd: Int, frameIndex: Int, outPath: String): Boolean {
        if (!NativeSource.nativeAvailable) {
            // The lib is absent (no NDK build): fail cleanly with the
            // unavailable message instead of UnsatisfiedLinkError. The
            // exporter's catch-all turns this into a FAILED job.
            throw IllegalStateException(NativeSource.nativeUnavailableMessage)
        }
        return NativeSource.jniDngExport(fd, frameIndex, outPath)
    }

    override fun code(): Int = NativeSource.jniPreviewErrorCode()

    override fun stage(): Int = NativeSource.jniPreviewErrorStage()

    override fun detail(): String? = NativeSource.previewLastError()
}

/**
 * Staging-file decisions for an export (JVM-testable without any native
 * code): where the partial file lives, and what happens to it on success
 * vs failure. The spec: a successful file is only visible after finalize —
 * everything writes to `out.partial` first, then a rename makes it visible.
 */
object ExportStaging {
    /** The in-progress file next to [out] (never the final name). */
    fun partial(out: File): File = File(out.parentFile, out.name + ".partial")

    /**
     * Finalizes a finished partial: rename into the final name. True when the
     * final file exists afterwards.
     */
    fun finalize(partial: File, out: File): Boolean {
        if (!partial.exists()) return false
        out.delete()
        return partial.renameTo(out)
    }

    /** Deletes the partial (failure path): no half-written file is left behind. */
    fun discard(partial: File) {
        partial.delete()
    }

    /** True when neither the final nor the partial exists (clean-state check). */
    fun clean(out: File): Boolean = !out.exists() && !partial(out).exists()
}

/**
 * Exports one frame of a clip as a CFA DNG through the native path
 * (decode frame -> writeCfaDng to the staging path -> rename into place).
 *
 * The [export] call runs on the shared engine executor thread ([engine]) —
 * one motioncam decoder is not concurrency-safe and the bridge's error
 * state is thread-local, so the whole native sequence is confined there,
 * like the preview path. The grade snapshot of the job is NOT applied: a
 * CFA DNG is the raw mosaic (the DNG carries the raw data; grading belongs
 * to the DNG reader).
 *
 * Staging per the spec: everything writes to `out.partial` (app cache);
 * only a successful render is renamed to [out], so a successful file is
 * only visible after finalize. A failure deletes the partial and leaves
 * no file at all.
 */
class DngExporter(
    private val render: DngRender = NativeDngRender,
    private val engine: ExecutorService = DngExporter.exportEngine,
) {

    /**
     * Exports frame [frameIndex] of the clip at [sourceFd] as a DNG at
     * [out]. Returns the updated job: DONE on success (detail = output file
     * name), FAILED with a stage + cause + recovery detail on failure. Never
     * throws; a failure leaves neither [out] nor its partial behind.
     *
     * [onProgress] hears the frame counts (always out of 1 — a DNG export is
     * one frame: 0/1 before the render, 1/1 after finalize). The queue owns
     * the throttled persistence.
     */
    suspend fun export(
        job: ExportJob,
        sourceFd: Int,
        out: File,
        frameIndex: Int = 0,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val partial = ExportStaging.partial(out)
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        onProgress?.invoke(0, 1)
                        if (render.render(sourceFd, frameIndex, partial.absolutePath)) {
                            if (ExportStaging.finalize(partial, out)) {
                                onProgress?.invoke(1, 1)
                                Result.success(Unit)
                            } else {
                                ExportStaging.discard(partial)
                                Result.failure<Unit>(
                                    IllegalStateException(
                                        "DNG export failed (stage: finalize): " +
                                            "the staged file could not be moved — " +
                                            "recovery: check free space and retry"
                                    )
                                )
                            }
                        } else {
                            // Native failure: map code + stage + detail.
                            ExportStaging.discard(partial)
                            Result.failure<Unit>(
                                IllegalStateException(
                                    DngExportErrors.message(
                                        render.code(), render.stage(), render.detail()
                                    )
                                )
                            )
                        }
                    } catch (e: Throwable) {
                        ExportStaging.discard(partial)
                        Result.failure<Unit>(e)
                    }
                    cont.resume(res)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(
                    Result.failure<Unit>(
                        IllegalStateException("DNG export failed (stage: open): export engine unavailable — recovery: retry the export")
                    )
                )
            }
        }
        // Placeholder to keep the coroutine body shape; the real resume is above.
        return outcome.fold(
            onSuccess = { job.copy(state = State.DONE, detail = out.name) },
            onFailure = { e -> job.copy(state = State.FAILED, detail = e.message) },
        )
    }

    companion object {
        /**
         * The single export engine thread, shared by every exporter instance
         * (the preview path's pattern): daemon so it never keeps the process
         * alive. One job runs at a time — the queue runner serializes jobs
         * before they ever reach this thread.
         */
        private val exportEngine: ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "rawedit-export").apply { isDaemon = true }
            }
        }
    }
}
