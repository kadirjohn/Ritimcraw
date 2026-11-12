package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.engine.NativeSource
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure mapping from a failed MP4 export to the user-facing message
 * (DESIGN.md Error states: name the stage, the cause and the recovery —
 * never a bare "failed"). Kept free of Android imports so it is
 * JVM-testable.
 *
 * Two kinds of stages appear:
 *  - NATIVE stages (int tokens from jni_bridge.cpp, same contract as the
 *    preview/DNG paths): 0 open, 1 frame read, 2 develop.
 *  - KOTLIN stages (string constants below): everything the pipeline does
 *    on top of the native calls — encoder configure, encode, mux, finalize.
 */
object Mp4ExportErrors {

    // rawedit::ErrorCode (core/include/rawedit/Result.h) — native failures.
    const val CODE_OK = 0
    const val CODE_TRUNCATED = 1
    const val CODE_BAD_HEADER = 2
    const val CODE_IO = 3
    const val CODE_UNSUPPORTED = 4

    // Native stage tokens (jni_bridge.cpp kStageOpen/kStageFrameRead/kStageDevelop).
    const val STAGE_OPEN = 0
    const val STAGE_FRAME_READ = 1
    const val STAGE_DEVELOP = 2

    /** Kotlin-side stages of the export pipeline. */
    const val STAGE_CONFIGURE = "configure"
    const val STAGE_ENCODE = "encode"
    const val STAGE_MUX = "muxer"
    const val STAGE_FINALIZE = "finalize"

    /** Native stage name shown in the message. */
    fun stageName(stage: Int): String = when (stage) {
        STAGE_OPEN -> "open"
        STAGE_FRAME_READ -> "frame read"
        STAGE_DEVELOP -> "develop"
        else -> "unknown"
    }

    /** Cause + recovery wording per rawedit::ErrorCode (native failures). */
    fun causeAndRecovery(code: Int): Pair<String, String> = when (code) {
        CODE_TRUNCATED ->
            "truncated clip" to "re-import the clip"
        CODE_BAD_HEADER ->
            "not a supported clip" to "re-import the clip"
        CODE_IO ->
            "the clip or the output could not be read or written" to
                "check free space and the clip, then retry"
        CODE_UNSUPPORTED ->
            "unsupported clip" to "re-import the clip"
        else ->
            "unknown error" to "retry the export"
    }

    /**
     * Message for a NATIVE failure, e.g.
     * "MP4 export failed (stage: frame read): truncated clip — recovery: re-import the clip [detail]".
     */
    fun message(code: Int, stage: Int, detail: String? = null): String {
        val (cause, recovery) = causeAndRecovery(code)
        return kotlinMessage(stageName(stage), cause, recovery, detail)
    }

    /**
     * Message for a KOTLIN-stage failure, e.g.
     * "MP4 export failed (stage: configure): video/avc rejected the format — recovery: ...".
     */
    fun kotlinMessage(stage: String, cause: String, recovery: String, detail: String? = null): String {
        val base = "MP4 export failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}

/** Session info from nativePreviewOpen: [handle, frameCount, w, h, cfa, orientation]. */
data class Mp4SessionInfo(
    val handle: Long,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val cfa: Int,
    val orientationDeg: Int,
)

/**
 * The native MP4 export boundary, as an interface so the pipeline is
 * JVM-testable with a fake (the real calls need librawedit_jni.so).
 *
 * Contract with app/src/main/cpp/jni_bridge.cpp: every method must run on
 * ONE thread (the shared export engine) — the decoder is not
 * concurrency-safe and the bridge error state is thread-local.
 */
interface Mp4Session {
    /** Opens the clip; null on failure (then [code]/[stage]/[detail] say why). */
    fun open(fd: Int): Mp4SessionInfo?

    /**
     * Develops frame [frameIndex] at FULL resolution with [grade]:
     * developCpu -> linearToRec709 -> clamp -> 8-bit RGB888 (row-major,
     * NOT rotated — orientation travels as the muxer hint). [lutPath] is
     * the job snapshot's selected .cube path (null = no LUT, the old
     * look; COL-07) — the SAME stage-22 apply as the preview, so the
     * owner's preview-vs-export acceptance check compares like with like.
     * The snapshot's denoise percents (DN-01..08) apply with the same
     * native 0..100 scale as the preview. [gyroPath] is the attached gyro
     * log's path (gyro Phase 5): when the snapshot has stabilization on
     * AND a log is attached, the frame is WARPED at full resolution
     * before the RGB888 pack — the same per-frame warp the paused
     * preview shows, so the exported clip matches the preview. Export is
     * NOT tier-gated (the temporal NR precedent): the export can afford
     * the measured full-res warp cost offline. Null gyroPath (or
     * stabilization off) = no warp. Null on failure (read the error
     * accessors from the same thread).
     */
    fun renderFrame(
        handle: Long,
        frameIndex: Int,
        grade: GradeK,
        lutPath: String? = null,
        gyroPath: String? = null,
    ): ByteArray?

    /** ptsUs of frame [frameIndex] (-1 on a bad handle). */
    fun framePts(handle: Long, frameIndex: Int): Long

    /** [sampleRate, channels], or null/0-0 when the clip has no audio. */
    fun audioInfo(handle: Long): IntArray?

    /** The whole clip's PCM (interleaved), or null on failure. */
    fun readAudio(handle: Long): ShortArray?

    /** Closes the session (best effort; safe to call twice). */
    fun close(handle: Long)

    /** rawedit::ErrorCode of the last failed call. */
    fun code(): Int

    /** Native stage token of the last failed call. */
    fun stage(): Int

    /** Bridge detail of the last failed call. */
    fun detail(): String?
}

/** The real boundary: JNI calls into librawedit_jni.so. */
internal object NativeMp4Session : Mp4Session {

    private fun requireNative() {
        if (!NativeSource.nativeAvailable) {
            // No NDK build: fail cleanly with the unavailable message
            // instead of UnsatisfiedLinkError. The exporter's catch-all
            // turns this into a FAILED job.
            throw IllegalStateException(NativeSource.nativeUnavailableMessage)
        }
    }

    override fun open(fd: Int): Mp4SessionInfo? {
        requireNative()
        val r = NativeSource.jniPreviewOpen(fd) ?: return null
        return Mp4SessionInfo(
            handle = r[0].toLong(),
            frameCount = r[1],
            width = r[2],
            height = r[3],
            cfa = r[4],
            orientationDeg = r[5],
        )
    }

    override fun renderFrame(
        handle: Long,
        frameIndex: Int,
        grade: GradeK,
        lutPath: String?,
        gyroPath: String?,
    ): ByteArray? {
        requireNative()
        // Gyro Phase 5: export is NOT tier-gated (the temporal NR precedent
        // — PreviewTiers gates the PREVIEW, never the export), so this is
        // its own on+path gate rather than GradeMapping.stabilizationArgs
        // (which carries PreviewTiers.stabilizationAppliesAt — a preview
        // policy the export must not depend on, even though the audit fix
        // of 2026-11-12 made that gate pass at every tier). Strength maps
        // 0..100 -> 0..1 exactly like lutStrength; mode/zoomLimit/focalMm/
        // trimMs pass through raw (the C++ side owns their composition).
        val stab = if (grade.stabilization && gyroPath != null) {
            grade.stabilizationStrength / 100f
        } else {
            0f
        }
        return NativeSource.jniExportFrame(
            handle, frameIndex,
            grade.exposureEv, grade.highlights, grade.tempK, grade.tint,
            grade.saturation, grade.blackPoint, grade.whitePoint,
            grade.shadows, grade.midtones, grade.contrast,
            grade.asShot, grade.highlightReconstruction, grade.keepVignetting,
            grade.sharpness, grade.detail, grade.tonemap,
            // COL-07: percent 0..100 -> rawedit's 0..1 blend, same mapping
            // as GradeMapping.toNative (every JNI call site passes 0..1).
            lutPath, grade.lutStrength / 100f,
            // DN-01..08: native 0..100 scale already — pass through (the
            // core clamps), same as GradeMapping.toNative.
            grade.noiseReduction, grade.chromaDenoise,
            grade.temporalNr, grade.temporalStrength,
            // COL-13: the color package — six wheel offsets + three
            // split-tone scalars, native scale already (same pass-through
            // as GradeMapping.toNative). The SAME stages 18/19 run here as
            // in the preview: identical math, identical order — export
            // parity by construction.
            grade.wheelShadowX, grade.wheelShadowY,
            grade.wheelMidX, grade.wheelMidY,
            grade.wheelHighX, grade.wheelHighY,
            grade.splitShadowHue, grade.splitHighlightHue, grade.splitBalance,
            // Gyro Phase 5: null gyroPath (or stabilization off) = the
            // bridge's no-warp default (null path -> resolveStabilization
            // returns the default StabInput -> applyStabilization no-ops).
            if (grade.stabilization && gyroPath != null) gyroPath else null,
            grade.stabilizationMode,
            stab,
            grade.stabilizationZoomLimit,
            grade.stabilizationFocalMm,
            grade.stabilizationTrimMs,
        )
    }

    override fun framePts(handle: Long, frameIndex: Int): Long =
        NativeSource.jniFramePtsUs(handle, frameIndex)

    override fun audioInfo(handle: Long): IntArray? = NativeSource.jniAudioInfo(handle)

    override fun readAudio(handle: Long): ShortArray? = NativeSource.jniReadAudio(handle)

    override fun close(handle: Long) {
        NativeSource.jniCloseSource(handle)
    }

    override fun code(): Int = NativeSource.jniPreviewErrorCode()

    override fun stage(): Int = NativeSource.jniPreviewErrorStage()

    override fun detail(): String? = NativeSource.previewLastError()
}

/**
 * The encoder + muxer boundary (MediaCodec + MediaMuxer in the real app),
 * as an interface so the pipeline is JVM-testable with a fake codec.
 *
 * One writer per export. [start] configures and starts everything (it
 * throws when the encoder rejects the format — that is the
 * rejected-encoder case); the write methods feed frames/blocks in
 * presentation order; [finish] drains and stops the muxer; [release] is
 * always called, success or failure.
 */
interface Mp4MediaWriter {
    /**
     * Configures both encoders and the muxer for [outPath]. [sampleRate]/
     * [channels] 0 = clip has no audio (video track only). Throws
     * IllegalStateException naming the stage ("configure") and the codec
     * when something rejects the format.
     */
    fun start(
        outPath: String,
        width: Int,
        height: Int,
        orientationDeg: Int,
        sampleRate: Int,
        channels: Int,
    )

    /** Feeds one I420 frame (YuvConverter.toI420 layout) at [ptsUs]. */
    fun writeVideoFrame(ptsUs: Long, i420: ByteArray)

    /** Feeds one block of interleaved PCM frames at [ptsUs]. */
    fun writeAudioBlock(ptsUs: Long, pcm: ShortArray)

    /** Drains the encoders and stops the muxer. True when the file is complete. */
    fun finish(): Boolean

    /** Releases everything (best effort; safe to call twice). */
    fun release()

    /**
     * Optional per-export stage-timing sink (owner request 2026-11-13,
     * export stage timing): the pipeline attaches its [ExportStageTiming]
     * right before the encode loop, and a writer that measures its
     * internals reports them per frame through
     * [ExportStageTiming.markWriterStages] (same thread — writeVideoFrame
     * runs inside the pipeline's loop iteration). Default no-op: the JVM
     * fakes and any timing-unaware writer keep compiling and behave
     * identically; a null argument turns the writer's timing off.
     */
    fun attachTiming(timing: ExportStageTiming?) {}
}

/** AAC input: one block = 1024 PCM frames per channel (the AAC frame size). */
internal const val AAC_BLOCK_FRAMES = 1024

/**
 * The byte count one queued video input buffer holds after an Image's planes
 * are filled — the `size` argument of `MediaCodec.queueInputBuffer` on the
 * Image input path (owner request 2026-11-11 §1, "the MP4 really decodes").
 *
 * THE BUG this replaces: the writer used to call
 * `queueInputBuffer(index, 0, 0, ptsUs, 0)` — size 0 — believing the codec
 * would "read the planes it handed out". It does not: `queueInputBuffer`'s
 * `size` is "the number of bytes of valid input data" (MediaCodec javadoc)
 * and travels to the codec's native layer with no Java-side validation. A
 * size of 0 tells the encoder the buffer is EMPTY, so real devices drop or
 * corrupt the frame — a DONE row with an MP4 that ffprobe cannot fully
 * decode.
 *
 * THE CTS PATTERN this follows (Android's compatibility suite, which every
 * shipped encoder must pass):
 *  - EncodeDecodeTest (tests/tests/media/src/android/media/cts, Android 6.0
 *    CTS): "the size of a frame of video data, in the formats we handle, is
 *    stride*sliceHeight for Y, and (stride/2)*(sliceHeight/2) for each of
 *    the Cb and Cr channels" — with tight strides that algebra is
 *    `mWidth * mHeight * 3 / 2`, and the test queues exactly that:
 *    `encoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, 0)`.
 *  - CodecEncoderTestBase (modern CTS, tests/media/common): the Image input
 *    path fills the planes via `fillImage(img)` and then queues
 *    `size = mBytesPerSample * mWidth * mHeight * 3 / 2` with offset 0.
 * Both compute the FILLED byte count from the frame geometry — never 0.
 * (The EOS buffer is the one exception: size 0 together with
 * BUFFER_FLAG_END_OF_STREAM — which the writer's signalEos already does.)
 *
 * GEOMETRY: one plane's filled extent is the bytes up to its last sample,
 *
 *     1 + (rows - 1) * rowStride + (cols - 1) * pixelStride
 *
 * — exactly how the framework's own `MediaCodec.MediaImage` constructor
 * limits each plane's buffer slice (SDK sources:
 * `buffer.limit(buffer.position() + bytesPerSample + (rows/vert - 1) *
 * rowInc + (cols/horiz - 1) * colInc)`), and what `Image.Plane.getBuffer`'s
 * javadoc guarantees ("each plane is only guaranteed to contain data up to
 * the last pixel in the last row").
 *
 * The buffer's valid byte count is the MAXIMUM end offset across the three
 * planes, NOT the sum of their extents: in semiplanar layouts (NV12 — most
 * hardware encoders) U and V share one interleaved plane, so summing
 * double-counts the chroma and can report more bytes than the codec's
 * buffer even holds. Plane placement follows the standard YUV 4:2:0 layout
 * the codec's own buffer is built on: Y at offset 0, chroma after the luma
 * block; in planar layouts V follows U's whole block, in semiplanar layouts
 * V starts one sample into the shared plane. On tight strides the maximum
 * collapses to CTS's `width * height * 3 / 2` for BOTH layouts; padded
 * strides grow it to the true filled extent, which can never exceed the
 * buffer because the codec sized that buffer for exactly these strides.
 *
 * Pure geometry — no Android imports, JVM-testable. The writer hands the
 * live `Image`'s strides to this function right before queueing.
 */
fun imageInputSize(
    width: Int,
    height: Int,
    yRowStride: Int,
    uRowStride: Int,
    uPixelStride: Int,
    vRowStride: Int,
    vPixelStride: Int,
): Int {
    require(width > 0 && height > 0) { "image must have positive dimensions, got ${width}x$height" }
    // YUV 4:2:0: luma is full-size, chroma planes are half (rounded up on
    // odd dimensions — the same cw/ch MediaCodecMp4Writer.copyI420ToImage
    // fills and MediaImage's /2 subsampling crops to).
    val cw = (width + 1) / 2
    val ch = (height + 1) / 2
    require(yRowStride >= width) { "luma row stride $yRowStride cannot hold a $width-wide row" }
    require(uRowStride >= cw * uPixelStride) { "u row stride $uRowStride cannot hold $cw samples at pixel stride $uPixelStride" }
    require(vRowStride >= cw * vPixelStride) { "v row stride $vRowStride cannot hold $cw samples at pixel stride $vPixelStride" }

    // Filled extent of one chroma plane: bytes up to its last sample — the
    // same arithmetic MediaImage uses to limit each plane's slice.
    fun extent(rowStride: Int, pixelStride: Int) = 1 + (ch - 1) * rowStride + (cw - 1) * pixelStride

    // Y sits at offset 0; the chroma planes follow the luma block (the
    // standard layout the codec's buffer is built on).
    val chromaBase = height * yRowStride
    // Planar: V follows U's whole block. Semiplanar: V starts one byte into
    // the plane U shares.
    val vStart = if (uPixelStride == 1) chromaBase + ch * uRowStride else chromaBase + 1

    return maxOf(
        width + (height - 1) * yRowStride, // luma end
        chromaBase + extent(uRowStride, uPixelStride),
        vStart + extent(vRowStride, vPixelStride),
    )
}

/**
 * Per-export stage timing (owner request 2026-11-13, "export stage
 * timing"): a pure accumulator + formatter, JVM-testable end to end — the
 * only impure inputs are System.nanoTime at the call sites and the
 * INJECTED log sink, so this class carries no Android imports.
 *
 * BOUNDARY HONESTY: the "native" stage times ONE JNI call,
 * Mp4Session.renderFrame. Inside that single call the bridge runs the
 * MCRAW decode (+ temporal NR when on) + the 24-stage developCpu + the
 * gyro warp (when on) + the Rec.709 OETF + the RGB888 pack — and
 * jni_bridge.cpp is outside this mission's file boundary, so those
 * sub-stages are NOT separable here. The call is timed as one number and
 * the boundary is documented, never guessed. The separable stages are
 * the Kotlin ones (rotate / crop / yuv) and the writer internals
 * (encoder input wait / plane copy / drain-mux, reported by the writer
 * through [markWriterStages]).
 *
 * OUTPUT — all through the injected sink, one line each (never one line
 * per frame):
 *  - a per-frame SAMPLE line at frames 1, 30, 60, 90 (the first frame +
 *    steady-state checkpoints),
 *  - one STAGE AVERAGES line over the whole export,
 *  - one EXPORT SUMMARY line (source -> upright -> encoded dims, active
 *    effect groups, frame count, total encode time).
 *
 * COST: two LongArrays per export, ~8 System.nanoTime reads per frame,
 * strings built only at the sample checkpoints and at close — no
 * hot-loop allocations. A null sink (timing off) is never even
 * constructed by the pipeline, so timing-off exports collect nothing
 * and are byte-identical.
 */
class ExportStageTiming(private val log: ((String) -> Unit)?) {

    /** Clears the per-frame slots at the top of a video frame iteration. */
    fun startFrame() {
        for (i in frameNanos.indices) frameNanos[i] = 0
        frameWallStartNanos = System.nanoTime()
    }

    /** Adds [nanos] to the frame's [stage] slot (each call site times its own block). */
    fun mark(stage: Int, nanos: Long) {
        if (stage in 0 until STAGE_COUNT) frameNanos[stage] += nanos
    }

    /**
     * Writer-internal stages, reported by the writer at the END of its
     * writeVideoFrame (same thread — the call runs inside the pipeline's
     * frame iteration, so the slots are filled before [endFrame] reads
     * them). A writer that does not measure (the JVM fakes, any
     * timing-unaware writer) simply never calls this and the slots stay
     * at honest zeros.
     */
    fun markWriterStages(encWaitNanos: Long, planeCopyNanos: Long, drainNanos: Long) {
        frameNanos[STAGE_ENC_WAIT] += encWaitNanos
        frameNanos[STAGE_PLANE_COPY] += planeCopyNanos
        frameNanos[STAGE_DRAIN_MUX] += drainNanos
    }

    /** Closes one video frame: accumulates, emits a sample line at checkpoints. */
    fun endFrame() {
        for (i in 0 until STAGE_COUNT) sums[i] += frameNanos[i]
        frames++
        if (isSampleFrame(frames)) log?.invoke(sampleLine())
    }

    /**
     * Emits the per-export lines (call once, after the last frame): stage
     * averages over [frames] frames, then the one-line EXPORT SUMMARY
     * with the three dimension pairs ([sourceW]x[sourceH] as decoded,
     * [rotatedW]x[rotatedH] after the upright rotation, [encodedW]x
     * [encodedH] after the crop — what the encoder received), the active
     * effect groups, the frame count and the whole-encode [encodeNanos].
     */
    fun close(
        sourceW: Int,
        sourceH: Int,
        rotatedW: Int,
        rotatedH: Int,
        encodedW: Int,
        encodedH: Int,
        grade: GradeK,
        gyroActive: Boolean,
        encodeNanos: Long,
    ) {
        val sink = log ?: return
        if (frames > 0) {
            sink(
                "export stages avg over $frames frames: " +
                    stagePairs { sums[it] / frames.toDouble() } + " total=" +
                    ms(encodeNanos / frames.toDouble()) + "ms"
            )
        }
        sink(
            "EXPORT SUMMARY: source ${sourceW}x$sourceH -> upright ${rotatedW}x$rotatedH" +
                " -> encoded ${encodedW}x$encodedH, effects " +
                effects(grade, gyroActive) + ", $frames frames, " +
                ms(encodeNanos.toDouble()) + " ms"
        )
    }

    // ---- internals ----

    private val frameNanos = LongArray(STAGE_COUNT)
    private val sums = LongArray(STAGE_COUNT)
    private var frames = 0
    private var frameWallStartNanos = 0L

    private fun isSampleFrame(f: Int): Boolean =
        f == SAMPLE_FRAMES[0] || f == SAMPLE_FRAMES[1] ||
            f == SAMPLE_FRAMES[2] || f == SAMPLE_FRAMES[3]

    /** "native=45.2ms rotate=1.1ms ... drain_mux=0.8ms" for the given per-stage source. */
    private fun stagePairs(nanos: (Int) -> Double): String {
        val sb = StringBuilder(96)
        for (s in 0 until STAGE_COUNT) {
            if (s > 0) sb.append(' ')
            sb.append(stageName(s)).append('=').append(ms(nanos(s))).append("ms")
        }
        return sb.toString()
    }

    /** The per-frame sample line: stages + the frame's true wall time. */
    private fun sampleLine(): String =
        "export frame $frames: " + stagePairs { frameNanos[it].toDouble() } +
            " total=" + ms((System.nanoTime() - frameWallStartNanos).toDouble()) + "ms"

    private fun ms(nanos: Double): String = String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0)

    companion object {
        /** The export diagnostics tag (MediaCodecMp4Writer logs here too). */
        const val TAG = "ritim-export"

        /** renderFrame: ONE JNI call — decode + temporal NR + develop + gyro warp + OETF + pack (see class KDoc). */
        const val STAGE_NATIVE = 0
        /** Kotlin upright rotation (RotationState.rotateRgb888). */
        const val STAGE_ROTATE = 1
        /** Kotlin crop/straighten (CropState.applyToRgb). */
        const val STAGE_CROP = 2
        /** Kotlin I420 conversion (YuvConverter.toI420). */
        const val STAGE_YUV = 3
        /** Writer: the dequeueInputBuffer wait for a free encoder input buffer. */
        const val STAGE_ENC_WAIT = 4
        /** Writer: getInputImage + copyI420ToImage + imageInputSize + queueInputBuffer. */
        const val STAGE_PLANE_COPY = 5
        /** Writer: drain — encoded packets out of the codec into the muxer. */
        const val STAGE_DRAIN_MUX = 6
        const val STAGE_COUNT = 7

        /** Per-frame sample checkpoints: the first frame + steady-state points. */
        val SAMPLE_FRAMES = intArrayOf(1, 30, 60, 90)

        fun stageName(stage: Int): String = when (stage) {
            STAGE_NATIVE -> "native"
            STAGE_ROTATE -> "rotate"
            STAGE_CROP -> "crop"
            STAGE_YUV -> "yuv"
            STAGE_ENC_WAIT -> "enc_wait"
            STAGE_PLANE_COPY -> "plane_copy"
            STAGE_DRAIN_MUX -> "drain_mux"
            else -> "stage$stage"
        }

        /**
         * Active-effect tokens for the summary line: the effect groups
         * whose per-frame cost the timing is meant to attribute — spatial
         * NR (luma or chroma percent), temporal NR, gyro stabilization
         * (on AND a log attached — [gyroActive] is the pipeline's own
         * gate), tonemap, LUT. A neutral grade is "none", never an empty
         * list.
         */
        fun effects(grade: GradeK, gyroActive: Boolean): String {
            val parts = ArrayList<String>(5)
            if (grade.noiseReduction > 0f || grade.chromaDenoise > 0f) parts.add("nr")
            if (grade.temporalNr) parts.add("temporal")
            if (gyroActive) parts.add("gyro")
            if (grade.tonemap != 0) parts.add("tonemap")
            if (grade.lutId != null) parts.add("lut")
            return if (parts.isEmpty()) "none" else parts.joinToString("+")
        }
    }
}

/**
 * The MP4 export pipeline state machine (JVM-testable over fake
 * [Mp4Session]/[Mp4MediaWriter]; no Android imports):
 *
 *   open -> (read timeline) -> configure -> encode loop -> mux -> finalize
 *
 * Each phase advances the stage; a failure throws IllegalStateException
 * with the full stage + cause + recovery message (DESIGN.md Error states),
 * so the caller never sees a bare "failed". The finalize/rename decision
 * lives in the exporter, not here.
 */
class ExportPipeline(
    private val session: Mp4Session,
    private val writer: Mp4MediaWriter,
    /**
     * Optional per-export stage-timing log sink (owner request
     * 2026-11-13): non-null constructs one [ExportStageTiming] for the
     * encode, attaches it to the writer, and emits the sample/average/
     * summary lines through it. Null (the default) = timing fully off —
     * nothing is constructed, nothing is collected, the encoded bytes
     * are identical (see ExportStageTiming).
     */
    private val stageLog: ((String) -> Unit)? = null,
) {

    /**
     * Runs the whole encode for the clip at [sourceFd] with [grade] into
     * [outPath] (the caller's staging path). [lutPath] is the job
     * snapshot's selected .cube path (null = no LUT, the old look;
     * COL-07) — resolved ONCE by the exporter and applied to every frame,
     * so the whole export is one consistent look. [gyroPath] is the
     * attached gyro log's path (gyro Phase 5) — resolved ONCE by the
     * exporter and applied to every frame: when the snapshot has
     * stabilization on AND a log is attached, every frame is warped at
     * full resolution before the RGB888 pack (the same per-frame warp the
     * paused preview shows; export is not tier-gated). Throws
     * IllegalStateException with a complete message on any failure.
     *
     * [onProgress] hears (videoFramesDone, totalVideoFrames) — reported
     * once with 0/total right after open, then after every encoded VIDEO
     * frame (audio blocks do not move the counter; the user thinks in
     * frames). The queue throttles what reaches the store.
     */
    fun run(
        sourceFd: Int,
        grade: GradeK,
        outPath: String,
        lutPath: String? = null,
        gyroPath: String? = null,
        manualRotationDeg: Int = 0,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ) {
        val info = session.open(sourceFd)
            ?: throw IllegalStateException(
                Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
            )
        try {
            encode(info, grade, outPath, lutPath, gyroPath, manualRotationDeg, onProgress)
        } finally {
            session.close(info.handle)
        }
    }

    private fun encode(
        info: Mp4SessionInfo,
        grade: GradeK,
        outPath: String,
        lutPath: String?,
        gyroPath: String?,
        manualRotationDeg: Int,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)?,
    ) {
        // 1. Timeline: video pts from the source, normalized to 0.
        val rawPts = ArrayList<Long>(info.frameCount)
        for (i in 0 until info.frameCount) {
            rawPts.add(session.framePts(info.handle, i))
        }
        if (rawPts.isEmpty()) {
            throw IllegalStateException(
                Mp4ExportErrors.message(
                    Mp4ExportErrors.CODE_UNSUPPORTED, Mp4ExportErrors.STAGE_OPEN,
                    "open: the clip holds no video frames"
                )
            )
        }
        val videoPts = PtsTimeline.videoPts(rawPts)

        // 2. Audio description + PCM (absent audio = video-only file).
        var sampleRate = 0
        var channels = 0
        var blocks: List<ShortArray> = emptyList()
        var audioPts: List<Long> = emptyList()
        val audio = session.audioInfo(info.handle)
        if (audio != null && audio.size >= 2 && audio[0] > 0 && audio[1] > 0) {
            sampleRate = audio[0]
            channels = audio[1]
            val pcm = session.readAudio(info.handle)
                ?: throw IllegalStateException(
                    Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
                )
            blocks = chunk(pcm, channels)
            audioPts = (0 until blocks.size).map {
                PtsTimeline.audioBlockPts(it, AAC_BLOCK_FRAMES, sampleRate)
            }
        }

        // 3. Configure encoders + muxer (throws on a rejected format). A
        // throw here must still release the writer (the muxer may exist).
        // Rotation (owner request 2026-11-11 section 5): frames are
        // rotated UPRIGHT in the pixels below, so the encoder is
        // configured with the POST-rotation dimensions and NO muxer
        // orientation hint — a hint would make players rotate upright
        // pixels a second time. The effective orientation composes the
        // session's container value with the job's manual delta through
        // the one rotation source of truth.
        val effectiveDeg = RotationState.effectiveDeg(info.orientationDeg, manualRotationDeg)
        val outDims = RotationState.rotatedDims(info.width, info.height, effectiveDeg)
        // Crop + straighten (RAW-09): the writer's dims are the rotated
        // dims cut to the crop rect (even-snapped — the encoder wants
        // even YUV sizes; a neutral crop passes the rotated dims
        // through unchanged, byte-identical to the pre-crop stream).
        val cropRect = CropState.RectF(
            grade.cropX, grade.cropY, grade.cropW, grade.cropH,
        )
        val cropOutDims = CropState.outputDims(outDims.width, outDims.height, cropRect, even = true)
        try {
            writer.start(outPath, cropOutDims.width, cropOutDims.height, 0, sampleRate, channels)
        } catch (e: Throwable) {
            writer.release()
            throw e
        }
        try {
            // 4. Encode loop, in presentation order across both tracks.
            // Progress counts VIDEO frames only (the frame counter the user
            // sees); it starts once the encoders are actually configured —
            // a rejected configure would otherwise show 0/N "progress".
            // Stage timing (owner request 2026-11-13): one sink per export,
            // attached to the writer BEFORE the loop so its internals are
            // attributed to the right frame; null = timing off entirely.
            val timing = if (stageLog != null) ExportStageTiming(stageLog) else null
            timing?.let { writer.attachTiming(it) }
            val tEncode0 = System.nanoTime()
            onProgress?.invoke(0, videoPts.size)
            var done = 0
            for (s in PtsTimeline.interleave(videoPts, audioPts)) {
                when (s.track) {
                    PtsTimeline.Sample.Track.VIDEO -> {
                        timing?.startFrame()
                        val t0 = System.nanoTime()
                        val rgb = session.renderFrame(info.handle, s.frameIndex, grade, lutPath, gyroPath)
                            ?: throw IllegalStateException(
                                Mp4ExportErrors.message(
                                    session.code(), session.stage(), session.detail()
                                )
                            )
                        timing?.mark(ExportStageTiming.STAGE_NATIVE, System.nanoTime() - t0)
                        // Section 5: renderFrame returns NOT-rotated RGB888;
                        // rotating by the EFFECTIVE orientation makes the
                        // pixels themselves upright (the encoder wants
                        // upright video — players that ignore rotation
                        // metadata still show it right), then the I420
                        // conversion runs on the rotated buffer/dims.
                        // RAW-09: the crop (and straighten) applies on the
                        // upright pixels — same space the rect lives in.
                        // Neutral crop returns the upright buffer by
                        // reference (no copy, no visible change).
                        val t1 = System.nanoTime()
                        val upright = RotationState.rotateRgb888(rgb, info.width, info.height, effectiveDeg)
                        timing?.mark(ExportStageTiming.STAGE_ROTATE, System.nanoTime() - t1)
                        val t2 = System.nanoTime()
                        val cropped = CropState.applyToRgb(
                            rgb = upright.rgb,
                            width = upright.width,
                            height = upright.height,
                            rect = cropRect,
                            straightenDeg = grade.straightenDeg,
                            spaceDeltaDeg = 0,
                            even = true,
                        )
                        timing?.mark(ExportStageTiming.STAGE_CROP, System.nanoTime() - t2)
                        val t3 = System.nanoTime()
                        val i420 = YuvConverter.toI420(cropped.rgb, cropped.width, cropped.height)
                        timing?.mark(ExportStageTiming.STAGE_YUV, System.nanoTime() - t3)
                        // The writer's own stages (enc_wait / plane_copy /
                        // drain_mux) are marked INTO this timing object while
                        // writeVideoFrame runs, before endFrame reads them.
                        writer.writeVideoFrame(s.ptsUs, i420)
                        timing?.endFrame()
                        done++
                        onProgress?.invoke(done, videoPts.size)
                    }
                    PtsTimeline.Sample.Track.AUDIO ->
                        writer.writeAudioBlock(s.ptsUs, blocks[s.frameIndex])
                }
            }
            // 5. Drain + stop the muxer.
            if (!writer.finish()) {
                throw IllegalStateException(
                    Mp4ExportErrors.kotlinMessage(
                        Mp4ExportErrors.STAGE_MUX,
                        "the movie could not be finished",
                        "retry the export",
                    )
                )
            }
            timing?.close(
                sourceW = info.width,
                sourceH = info.height,
                rotatedW = outDims.width,
                rotatedH = outDims.height,
                encodedW = cropOutDims.width,
                encodedH = cropOutDims.height,
                grade = grade,
                gyroActive = grade.stabilization && gyroPath != null,
                encodeNanos = System.nanoTime() - tEncode0,
            )
        } finally {
            writer.release()
        }
    }

    /** Splits interleaved PCM into AAC-sized blocks (last one may be short). */
    private fun chunk(pcm: ShortArray, channels: Int): List<ShortArray> {
        val frame = channels * AAC_BLOCK_FRAMES
        val out = ArrayList<ShortArray>((pcm.size + frame - 1) / frame)
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frame, pcm.size)
            out.add(pcm.copyOfRange(i, end))
            i = end
        }
        return out
    }
}

/**
 * Exports a clip as an SDR H.264/AAC MP4 through the native decode path
 * (full-res developCpu per frame -> RGB888 -> I420 -> MediaCodec video/avc;
 * clip PCM -> audio/mp4a-latm; MediaMuxer). Rotation (owner request
 * 2026-11-11 section 5): every frame is rotated UPRIGHT in the pixels by
 * the clip's effective orientation (container + manual delta,
 * RotationState) and the muxer hint is dropped — the encoder receives
 * post-rotation dimensions, so even players that ignore rotation
 * metadata show the video right-side up.
 *
 * The whole pipeline runs on the shared export engine thread
 * ([ExportEngine]) — one motioncam decoder is not concurrency-safe and the
 * bridge's error state is thread-local. One job at a time.
 *
 * Staging per the spec: the muxer writes to `out.partial`; only a finished
 * export is renamed to [out]. A failure deletes the partial and leaves no
 * file at all.
 */
class Mp4Exporter(
    private val session: Mp4Session = NativeMp4Session,
    private val writerFactory: () -> Mp4MediaWriter = { MediaCodecMp4Writer() },
    private val engine: ExecutorService = ExportEngine.thread,
    private val lutPathFor: (String?) -> String? = { null },
    private val gyroPathFor: (String) -> String? = { null },
    /**
     * Per-export stage-timing log sink (owner request 2026-11-13): each
     * line the timing emits lands here (the app wires it to Log.i under
     * the "ritim-export" tag, DEBUG builds only — see [ExportStageTiming]
     * for the line formats). Null (the default) = timing off: nothing is
     * collected, the encoded bytes are identical.
     */
    private val stageLog: ((String) -> Unit)? = null,
) {

    /**
     * Exports the clip at [sourceFd] as an MP4 at [out], applying the
     * job's grade snapshot — including its LUT selection (COL-07):
     * [lutPathFor] turns the snapshot's lutId into the copied cube's path
     * (null = no LUT), resolved ONCE here so every frame of the export
     * runs the same stage-22 apply as the preview. Gyro Phase 5:
     * [gyroPathFor] turns the job's sourceId into the attached gyro log's
     * path (null = no warp), resolved ONCE here — when the snapshot has
     * stabilization on AND a log is attached, every frame is warped at
     * full resolution, the same per-frame warp the paused preview shows.
     * DNG and photo exports never reach this seam (no gyro warp there —
     * the documented skip; a gyro-stabilized DNG would be a raw
     * resample, a different project). Returns the updated job: DONE on
     * success (detail = output file name), FAILED with a stage + cause +
     * recovery detail on failure. Never throws; a failure leaves neither
     * [out] nor its partial behind.
     *
     * [onProgress] hears (videoFramesDone, totalVideoFrames) per frame; the
     * queue owns the throttled persistence (see ExportQueue.ProgressWriter).
     */
    suspend fun export(
        job: ExportJob,
        sourceFd: Int,
        out: File,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val lutPath = lutPathFor(job.snapshot.lutId)
        val gyroPath = gyroPathFor(job.sourceId)
        val partial = ExportStaging.partial(out)
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        ExportPipeline(session, writerFactory(), stageLog).run(
                            sourceFd, job.snapshot, partial.absolutePath, lutPath, gyroPath,
                            job.manualRotationDeg, onProgress
                        )
                        if (ExportStaging.finalize(partial, out)) {
                            Result.success(Unit)
                        } else {
                            ExportStaging.discard(partial)
                            Result.failure<Unit>(
                                IllegalStateException(
                                    Mp4ExportErrors.kotlinMessage(
                                        Mp4ExportErrors.STAGE_FINALIZE,
                                        "the finished movie could not be moved",
                                        "check free space and retry",
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
                        IllegalStateException(
                            Mp4ExportErrors.kotlinMessage(
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
