package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NativeSource
import java.io.File
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
     * NOT rotated — orientation travels as the muxer hint). Null on
     * failure (read the error accessors from the same thread).
     */
    fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK): ByteArray?

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

    override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK): ByteArray? {
        requireNative()
        return NativeSource.jniExportFrame(
            handle, frameIndex,
            grade.exposureEv, grade.highlights, grade.tempK, grade.tint,
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
}

/** AAC input: one block = 1024 PCM frames per channel (the AAC frame size). */
internal const val AAC_BLOCK_FRAMES = 1024

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
) {

    /**
     * Runs the whole encode for the clip at [sourceFd] with [grade] into
     * [outPath] (the caller's staging path). Throws IllegalStateException
     * with a complete message on any failure.
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
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ) {
        val info = session.open(sourceFd)
            ?: throw IllegalStateException(
                Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
            )
        try {
            encode(info, grade, outPath, onProgress)
        } finally {
            session.close(info.handle)
        }
    }

    private fun encode(
        info: Mp4SessionInfo,
        grade: GradeK,
        outPath: String,
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
        try {
            writer.start(outPath, info.width, info.height, info.orientationDeg, sampleRate, channels)
        } catch (e: Throwable) {
            writer.release()
            throw e
        }
        try {
            // 4. Encode loop, in presentation order across both tracks.
            // Progress counts VIDEO frames only (the frame counter the user
            // sees); it starts once the encoders are actually configured —
            // a rejected configure would otherwise show 0/N "progress".
            onProgress?.invoke(0, videoPts.size)
            var done = 0
            for (s in PtsTimeline.interleave(videoPts, audioPts)) {
                when (s.track) {
                    PtsTimeline.Sample.Track.VIDEO -> {
                        val rgb = session.renderFrame(info.handle, s.frameIndex, grade)
                            ?: throw IllegalStateException(
                                Mp4ExportErrors.message(
                                    session.code(), session.stage(), session.detail()
                                )
                            )
                        writer.writeVideoFrame(
                            s.ptsUs,
                            YuvConverter.toI420(rgb, info.width, info.height),
                        )
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
 * clip PCM -> audio/mp4a-latm; MediaMuxer with the clip's orientation
 * hint — frames are NOT rotated in the pixels, players rotate at display
 * time).
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
) {

    /**
     * Exports the clip at [sourceFd] as an MP4 at [out], applying the
     * job's grade snapshot. Returns the updated job: DONE on success
     * (detail = output file name), FAILED with a stage + cause + recovery
     * detail on failure. Never throws; a failure leaves neither [out] nor
     * its partial behind.
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
        val partial = ExportStaging.partial(out)
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        ExportPipeline(session, writerFactory()).run(
                            sourceFd, job.snapshot, partial.absolutePath, onProgress
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
