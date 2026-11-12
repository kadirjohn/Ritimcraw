package com.ritim.ritimcraw.export

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure mapping from a failed photo export to the user-facing message
 * (DESIGN.md Error states: name the stage, the cause and the recovery —
 * never a bare "failed"). Kept free of Android imports so it is
 * JVM-testable.
 *
 * Two kinds of stages appear, mirroring [Mp4ExportErrors]:
 *  - NATIVE stages (int tokens from jni_bridge.cpp): 0 open, 1 frame read,
 *    2 develop — the photo path reuses the same native calls.
 *  - KOTLIN stages (string constants below): the PNG encode and the
 *    staging rename, which this module owns.
 */
object PhotoExportErrors {

    // Kotlin-side stages of the photo path.
    const val STAGE_ENCODE = "png encode"
    const val STAGE_FINALIZE = "finalize"

    /** Cause + recovery wording for a Kotlin-stage failure. */
    fun kotlinMessage(stage: String, cause: String, recovery: String, detail: String? = null): String {
        val base = "Photo export failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}

/**
 * Pure pixel math for the photo path (JVM-testable, no Android imports):
 * the developed frame arrives as row-major RGB888 (see
 * [Mp4Session.renderFrame]) and the PNG writer consumes packed 0xAARRGGBB
 * ints — this object owns the conversion and the orientation rotation.
 */
object PhotoPixels {

    /**
     * Packs one RGB888 pixel into a 0xFFRRGGBB int (opaque alpha): the
     * ARGB_8888 layout Bitmap.copyPixelsFromBuffer-compatible writers
     * expect, byte order independent.
     */
    fun toArgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /**
     * Converts a row-major RGB888 buffer ([width] x [height], 3 bytes per
     * pixel) into a packed 0xFFRRGGBB IntArray, NOT rotated — the identity
     * orientation. Throws IllegalArgumentException when the buffer does not
     * hold exactly width*height*3 bytes.
     */
    fun toArgbInts(rgb: ByteArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) {
            "photo must have positive dimensions, got ${width}x$height"
        }
        require(rgb.size == width * height * 3) {
            "rgb buffer holds ${rgb.size} bytes, expected ${width * height * 3} " +
                "(${width}x${height} RGB888)"
        }
        val out = IntArray(width * height)
        var i = 0
        var o = 0
        while (i < rgb.size) {
            out[o++] = toArgb(rgb[i].toInt() and 0xFF, rgb[i + 1].toInt() and 0xFF, rgb[i + 2].toInt() and 0xFF)
            i += 3
        }
        return out
    }

    /**
     * Rotates packed ARGB ints by the clip's orientation (the native
     * renderFrame returns NOT-rotated frames; the MP4 path lets the muxer
     * hint rotate at display time — a PNG has no hint, so the pixels
     * themselves must be rotated to be correct-side-up everywhere).
     *
     * [orientationDeg] is the source's stored orientation (0/90/180/270,
     * as Mp4SessionInfo.orientationDeg reports; any other value is treated
     * as 0 — never guess a rotation the source did not declare).
     */
    fun rotate(argb: IntArray, width: Int, height: Int, orientationDeg: Int): Rotated {
        require(argb.size == width * height) {
            "argb holds ${argb.size} ints, expected ${width * height} (${width}x${height})"
        }
        return when (((orientationDeg % 360) + 360) % 360) {
            0 -> Rotated(argb, width, height)
            180 -> {
                val out = IntArray(argb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(height - 1 - y) * width + (width - 1 - x)] = argb[y * width + x]
                    }
                }
                Rotated(out, width, height)
            }
            90 -> {
                // A 90-degree turn swaps the axes: the source's last column
                // becomes the output's first row.
                val out = IntArray(argb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = argb[y * width + x]
                    }
                }
                Rotated(out, height, width)
            }
            270 -> {
                val out = IntArray(argb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = argb[y * width + x]
                    }
                }
                Rotated(out, height, width)
            }
            else -> Rotated(argb, width, height)
        }
    }

    /** A rotated buffer with its post-rotation dimensions. */
    data class Rotated(val argb: IntArray, val width: Int, val height: Int)
}

/**
 * The PNG write boundary, as an interface so the photo pipeline is
 * JVM-testable with a fake (the real writer needs android.graphics.Bitmap
 * — a device-gated path; see [BitmapPngWriter] in the ui layer for the
 * adapter).
 */
interface PngWriter {
    /**
     * Writes packed 0xFFRRGGBB pixels ([argb], [width] x [height]) as a
     * PNG at [outPath]. True on success.
     */
    fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean
}

/**
 * The grade-mode decision for the Frame Picker (RAW-11): "use clip grade"
 * exports the frame with the clip's CURRENT grade (snapshotted at queue
 * time, exactly like a video/DNG export), "separate develop" starts a
 * FRESH photo grade — the neutral [GradeK] — leaving the clip's own grade
 * untouched. Pure so the choice is JVM-testable.
 */
object PhotoDevelop {
    /**
     * The snapshot a picker job carries: [clipGrade] when the picker said
     * "use clip grade", neutral when it said "separate develop".
     */
    fun snapshotFor(clipGrade: com.ritim.ritimcraw.data.GradeK, separate: Boolean): com.ritim.ritimcraw.data.GradeK =
        if (separate) com.ritim.ritimcraw.data.GradeK() else clipGrade
}

/**
 * Exports one developed frame of a clip as a PNG photo (RAW-11 Frame
 * Picker): the native full-resolution develop (the same renderFrame seam
 * the MP4 export uses — developCpu -> linearToRec709 -> 8-bit RGB888) of
 * the job's frame, converted to ARGB, rotated to the clip's orientation
 * (a PNG carries no orientation hint, so the pixels must be), and written
 * as a lossless PNG.
 *
 * The whole pipeline runs on the shared export engine thread
 * ([ExportEngine]) — one motioncam decoder is not concurrency-safe and
 * the bridge's error state is thread-local. One job at a time; the queue
 * runner serializes jobs before they reach this thread.
 *
 * Staging per the spec: the PNG writes to `out.partial`; only a finished
 * export is renamed to [out]. A failure deletes the partial and leaves no
 * file at all.
 */
class PhotoExporter(
    private val session: Mp4Session = NativeMp4Session,
    private val pngWriter: PngWriter,
    private val engine: ExecutorService = ExportEngine.thread,
    private val lutPathFor: (String?) -> String? = { null },
) {

    /**
     * Exports frame [job.frameIndex] of the clip at [sourceFd] as a PNG at
     * [out], applying the job's grade snapshot — including its LUT
     * selection (COL-07), resolved ONCE here, the same stage-22 apply as
     * the preview and the MP4 export, so all three compare like with like.
     * Returns the updated job: DONE on success (detail = output file
     * name), FAILED with a stage + cause + recovery detail on failure.
     * Never throws; a failure leaves neither [out] nor its partial behind.
     *
     * [onProgress] hears the frame counts (a photo export is one frame:
     * 0/1 before the develop, 1/1 after finalize). The queue owns the
     * throttled persistence.
     */
    suspend fun export(
        job: ExportJob,
        sourceFd: Int,
        out: File,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val lutPath = lutPathFor(job.snapshot.lutId)
        val partial = ExportStaging.partial(out)
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        val info = session.open(sourceFd)
                            ?: throw IllegalStateException(
                                Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
                            )
                        try {
                            onProgress?.invoke(0, 1)
                            val rgb = session.renderFrame(info.handle, job.frameIndex, job.snapshot, lutPath)
                                ?: throw IllegalStateException(
                                    Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
                                )
                            val developed = PhotoPixels.rotate(
                                PhotoPixels.toArgbInts(rgb, info.width, info.height),
                                info.width,
                                info.height,
                                // Section 5: the PNG pixels rotate by the
                                // EFFECTIVE orientation (container + the
                                // job's manual delta snapshot, composed
                                // through the one rotation source of
                                // truth) — a PNG carries no orientation
                                // hint, so the pixels must be upright.
                                com.ritim.ritimcraw.data.RotationState.effectiveDeg(
                                    info.orientationDeg,
                                    job.manualRotationDeg,
                                ),
                            )
                            // RAW-09: the crop/straighten cut runs on the
                            // already-upright pixels (the effective rotation
                            // above composed container + manual delta), so
                            // the fraction rect needs no further space
                            // mapping — spaceDeltaDeg is 0 here, unlike the
                            // preview fold which still holds the container
                            // orientation.
                            val cropped = com.ritim.ritimcraw.data.CropState.applyToArgb(
                                argb = developed.argb,
                                width = developed.width,
                                height = developed.height,
                                rect = com.ritim.ritimcraw.data.CropState.RectF(
                                    job.snapshot.cropX,
                                    job.snapshot.cropY,
                                    job.snapshot.cropW,
                                    job.snapshot.cropH,
                                ),
                                straightenDeg = job.snapshot.straightenDeg,
                                spaceDeltaDeg = 0,
                            )
                            if (!pngWriter.write(cropped.argb, cropped.width, cropped.height, partial.absolutePath)) {
                                throw IllegalStateException(
                                    PhotoExportErrors.kotlinMessage(
                                        PhotoExportErrors.STAGE_ENCODE,
                                        "the PNG could not be written",
                                        "check free space and retry",
                                    )
                                )
                            }
                            if (ExportStaging.finalize(partial, out)) {
                                onProgress?.invoke(1, 1)
                                Result.success(Unit)
                            } else {
                                ExportStaging.discard(partial)
                                Result.failure<Unit>(
                                    IllegalStateException(
                                        PhotoExportErrors.kotlinMessage(
                                            PhotoExportErrors.STAGE_FINALIZE,
                                            "the finished photo could not be moved",
                                            "check free space and retry",
                                        )
                                    )
                                )
                            }
                        } finally {
                            session.close(info.handle)
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
                        IllegalStateException(
                            PhotoExportErrors.kotlinMessage(
                                "open",
                                "export engine unavailable",
                                "retry the export",
                            )
                        )
                    )
                )
            }
        }
        return outcome.fold(
            onSuccess = { job.copy(state = State.DONE, detail = out.name) },
            onFailure = { e -> job.copy(state = State.FAILED, detail = e.message) },
        )
    }
}

/**
 * The real [PngWriter]: a thin, device-gated adapter around
 * android.graphics.Bitmap (the ONLY Android-framework class in the photo
 * path — Bitmap cannot run in JVM unit tests, so every decision around it
 * lives in the JVM-tested [PhotoPixels] and [PhotoExporter]; this class
 * only packs and compresses, like [MediaCodecMp4Writer] for video).
 *
 * PNG via Bitmap.compress (CompressFormat.PNG): lossless — the developed
 * frame's 8-bit RGB never goes through a lossy stage on its way to a
 * photo, and it needs no new dependency (the platform encoder). The
 * bitmap is created from the already-rotated ints, so no EXIF or
 * orientation hint is needed — the pixels are correct-side-up as written
 * (PNG carries no orientation metadata; a photo that depends on a viewer
 * honoring a hint would open sideways in any viewer that ignores it).
 */
internal class BitmapPngWriter : PngWriter {

    override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        return try {
            FileOutputStream(outPath).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
    }
}
