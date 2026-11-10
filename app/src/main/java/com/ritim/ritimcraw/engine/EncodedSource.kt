package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.SourceKind

/**
 * Pure mapping from a failed encoded-clip decode to the user-facing message
 * (DESIGN.md Error states: name the stage, the cause and the recovery — never
 * a bare "failed"). Kept free of Android imports so it is JVM-testable.
 *
 * Two kinds of stages appear, mirroring [com.ritim.ritimcraw.export.Mp4ExportErrors]:
 *  - NATIVE stages (int tokens from jni_bridge.cpp): 0 open, 1 frame read,
 *    2 develop — used when the native develop call itself failed.
 *  - KOTLIN stages (string constants): everything the decode pipeline does
 *    on top of the native calls — decoder configure, frame decode.
 */
object EncodedErrors {

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

    /** Kotlin-side stages of the decode pipeline. */
    const val STAGE_CONFIGURE = "configure"
    const val STAGE_DECODE = "decode"

    /** Open failure kinds (EncodedOpenOutcome.Failed.kind). */
    const val OPEN_NO_VIDEO_TRACK = 1
    const val OPEN_UNSUPPORTED_MIME = 2
    const val OPEN_IO = 3

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
            "the frame could not be decoded" to "re-open the clip"
        CODE_UNSUPPORTED ->
            "unsupported clip" to "re-import the clip"
        else ->
            "unknown error" to "re-open the clip"
    }

    /**
     * Message for a NATIVE failure, e.g.
     * "Encoded clip failed (stage: develop): unsupported clip — recovery: re-import the clip [detail]".
     */
    fun message(code: Int, stage: Int, detail: String? = null): String {
        val (cause, recovery) = causeAndRecovery(code)
        return kotlinMessage(stageName(stage), cause, recovery, detail)
    }

    /**
     * Message for a KOTLIN-stage failure, e.g.
     * "Encoded clip failed (stage: configure): the video/avc decoder could not start — recovery: ...".
     */
    fun kotlinMessage(stage: String, cause: String, recovery: String, detail: String? = null): String {
        val base = "Encoded clip failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }

    /** Message for a track-open failure kind reported by the reader. */
    fun openFailure(kind: Int, detail: String? = null): String = when (kind) {
        OPEN_NO_VIDEO_TRACK -> kotlinMessage(
            stageName(STAGE_OPEN), "the clip has no video track", "re-import the clip", detail)
        OPEN_UNSUPPORTED_MIME -> kotlinMessage(
            stageName(STAGE_OPEN), "the video track uses an unsupported format", "re-import the clip", detail)
        OPEN_IO -> kotlinMessage(
            stageName(STAGE_OPEN),
            "the clip could not be read",
            "check the clip still exists, then re-import it",
            detail)
        else -> kotlinMessage(stageName(STAGE_OPEN), "unknown error", "re-import the clip", detail)
    }
}

/** Video track format as the container reports it (MediaExtractor track format). */
data class EncodedVideoFormat(
    /** Container codec mime, e.g. "video/avc" (must be in [EncodedSource.SUPPORTED_VIDEO_MIMES]). */
    val mime: String,
    val width: Int,
    val height: Int,
    /** Rotation hint in degrees (KEY_ROTATION); the develop tail rotates upright. */
    val orientationDeg: Int,
    /** True when the track tags BT.709 (or tags nothing — SDR default). False = BT.601 matrix. */
    val isBt709: Boolean,
    /**
     * The track's codec-specific data (avcC/hvcC → SPS/PPS/... buffers, in
     * container order). A decoder cannot start without it; the pipeline
     * never inspects it — it travels from the reader to the decoder adapter.
     */
    val codecConfig: List<ByteArray> = emptyList(),
)

/**
 * The video track's sample index, in the order the container stores samples
 * (decode order for B-frame video — the state machine reorders to
 * presentation order). Walked once at open; no sample bytes are copied.
 */
class EncodedSampleIndex(
    val ptsUs: LongArray,
    val sync: BooleanArray,
) {
    init {
        require(ptsUs.size == sync.size) { "pts/sync index mismatch" }
    }

    val frameCount: Int get() = ptsUs.size
}

/** Outcome of opening the container: everything the reader learned, or why it failed. */
sealed class EncodedOpenOutcome {
    data class Opened(
        val format: EncodedVideoFormat,
        val index: EncodedSampleIndex,
        /** 0 when the clip has no audio track. */
        val sampleRate: Int,
        /** 0 when the clip has no audio track. */
        val channels: Int,
    ) : EncodedOpenOutcome()

    data class Failed(
        /** [EncodedErrors.OPEN_NO_VIDEO_TRACK]/[OPEN_UNSUPPORTED_MIME]/[OPEN_IO]. */
        val kind: Int,
        /** Raw adapter detail (mime, exception message); the pipeline maps it. */
        val detail: String? = null,
    ) : EncodedOpenOutcome()
}

/** One compressed video sample handed to the decoder. */
class EncodedSample(
    val bytes: ByteArray,
    val ptsUs: Long,
    /** True for a sync (keyframe) sample. */
    val sync: Boolean,
)

/** One decoded plane: bytes plus the layout needed to address samples in it. */
class YuvPlane(
    val buffer: ByteArray,
    /** First byte of the plane inside [buffer] (usually 0 after an adapter copy). */
    val offset: Int,
    /** Bytes per row (>= the row's sample count; padded strides are fine). */
    val rowStride: Int,
    /** Bytes between two horizontal neighbours (2 for interleaved UV). */
    val pixelStride: Int,
)

/**
 * One decoded frame as the decoder produced it: three planes with their own
 * strides (MediaCodec Image layout). The pipeline converts this to a packed
 * [YuvFrameK] before crossing into the native develop.
 */
class YuvPlanes(
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val y: YuvPlane,
    val u: YuvPlane,
    val v: YuvPlane,
)

/**
 * Kotlin mirror of rawedit::YuvFrame (core/include/rawedit/Transfer.h) — the
 * JNI contract of nativeEncodedDevelop. Layouts:
 *   semiPlanar true  = NV12: y plane + interleaved UV in [u] ([v] unused)
 *   semiPlanar false = I420: y, u, v planes
 * [stride]/[uvStride] are bytes per luma/chroma row (0 = width / width/2).
 */
class YuvFrameK(
    val width: Int,
    val height: Int,
    val semiPlanar: Boolean,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val stride: Int,
    val uvStride: Int,
)

/**
 * The MediaExtractor boundary, as an interface so the decode pipeline is
 * JVM-testable with a fake (the real extractor cannot run on the JVM).
 *
 * Contract: [open] walks the whole video track once (the sample index); the
 * reader is then at END of stream (an extractor sits there after indexing),
 * so the FIRST [readSample] of any decode run must follow a [seekTo].
 * [seekTo] positions at the sync sample at-or-before [ptsUs];
 * [readSample] walks forward from there and returns null at end of stream.
 */
interface EncodedTrackReader {
    /**
     * Opens the clip at [fd]. [fd] must be a FRESH read-only descriptor
     * opened for this source (the per-open fd discipline of the MCRAW paths:
     * a descriptor's file offset moves during extraction, so descriptors are
     * never shared across opens). Ownership passes to the reader — it closes
     * the descriptor in [release]; the caller must not use or close it again.
     */
    fun open(fd: Int): EncodedOpenOutcome

    /** Positions at the sync sample at-or-before [ptsUs]. */
    fun seekTo(ptsUs: Long)

    /** Next compressed sample in container order, or null at end of stream. */
    fun readSample(): EncodedSample?

    /** Releases the extractor (best effort; safe to call twice). */
    fun release()
}

/**
 * The MediaCodec boundary, as an interface so the decode pipeline is
 * JVM-testable with a fake (the real codec cannot run on the JVM).
 *
 * Contract: [configure] starts the decoder for the track's mime/size (null on
 * success, a raw failure detail otherwise — the pipeline maps it to a full
 * message); [queueSample]/[dequeueFrame] are the decode loop;
 * [dequeueFrame] returns frames in PRESENTATION order carrying the pts of the
 * sample that produced them (MediaCodec's video-decoder contract) — the
 * pipeline verifies that against the track index; [flush] drops all pending
 * input/output (after a seek).
 */
interface EncodedFrameDecoder {
    /** Configures + starts; null on success, a raw failure detail otherwise. */
    fun configure(format: EncodedVideoFormat): String?

    /** Drops all pending samples/frames (the codec re-syncs at the next keyframe). */
    fun flush()

    /** Queues one compressed sample. Throws only on unexpected codec errors. */
    fun queueSample(sample: EncodedSample)

    /** Signals end of stream; the decoder drains its remaining frames after this. */
    fun queueEos()

    /**
     * Next decoded frame in presentation order, or null when none is ready
     * within [timeoutUs] (0 = do not wait). The pipeline passes 0 while
     * samples remain to feed and a real timeout while draining end of stream.
     */
    fun dequeueFrame(timeoutUs: Long): YuvPlanes?

    /** Releases the codec (best effort; safe to call twice). */
    fun release()
}

/**
 * The native develop boundary (nativeEncodedDevelop in jni_bridge.cpp), as an
 * interface so the pipeline is JVM-testable with a fake. Same accessor pattern
 * as Mp4Session: a null return means failure, then [code]/[stage]/[detail]
 * say why (read them from the same thread).
 */
interface EncodedDeveloper {
    /**
     * Develops one packed frame: developEncoded (limited-range YUV -> RGB ->
     * Mi-Log decode -> WB/temp/tint/exposure -> sharpness/detail/tonemap, the
     * shared COL-15/COL-16 stages, then the optional stage-20 LUT, COL-07)
     * -> Rec.709 -> ARGB, rotated upright by [orientationDeg]. [lutPath] is
     * the selected .cube's path (null = no LUT, the old look). Returns
     * [width, height, then w*h ARGB_8888 ints] (the preview contract) or
     * null on failure.
     */
    fun develop(
        frame: YuvFrameK,
        isBt709: Boolean,
        orientationDeg: Int,
        grade: GradeK,
        lutPath: String? = null,
    ): IntArray?

    /** rawedit::ErrorCode of the last failed develop (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported). */
    fun code(): Int

    /** Native stage token of the last failed develop (0 open, 1 frame read, 2 develop). */
    fun stage(): Int

    /** Bridge detail of the last failed develop. */
    fun detail(): String?
}

/**
 * Pure decoded-planes -> packed NV12 conversion (JVM-testable, no Android
 * imports). The decoder hands out planes with their own padded strides and
 * (for NV12/NV21 sources) interleaved chroma; the develop stage wants one
 * tightly packed NV12 frame, so this is the single place the layout is
 * normalized. Chroma covers the usual 2x2 luma block; odd dimensions round
 * the chroma grid UP (cw = (w+1)/2), matching COLOR_FormatYUV420Flexible.
 */
object EncodedYuv {

    fun toNv12(p: YuvPlanes): YuvFrameK {
        val w = p.width
        val h = p.height
        require(w > 0 && h > 0) { "empty frame (${w}x$h)" }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2

        // Safe-indexing preconditions: the last addressed byte of each plane
        // must exist. A short plane is an adapter bug, not a clip error.
        fun needRow(plane: YuvPlane, rows: Int, cols: Int): Int =
            plane.offset + (rows - 1) * plane.rowStride + (cols - 1) * plane.pixelStride + 1
        require(p.y.buffer.size >= needRow(p.y, h, w)) { "y plane too small for ${w}x$h" }
        require(p.u.buffer.size >= needRow(p.u, ch, cw)) { "u plane too small for ${w}x$h" }
        require(p.v.buffer.size >= needRow(p.v, ch, cw)) { "v plane too small for ${w}x$h" }

        val y = ByteArray(w * h)
        for (r in 0 until h) {
            for (c in 0 until w) {
                y[r * w + c] = p.y.buffer[p.y.offset + r * p.y.rowStride + c * p.y.pixelStride]
            }
        }
        val uv = ByteArray(2 * cw * ch)
        for (r in 0 until ch) {
            for (c in 0 until cw) {
                val dst = (r * cw + c) * 2
                uv[dst] = p.u.buffer[p.u.offset + r * p.u.rowStride + c * p.u.pixelStride]
                uv[dst + 1] = p.v.buffer[p.v.offset + r * p.v.rowStride + c * p.v.pixelStride]
            }
        }
        // Packed NV12: one luma row = w bytes, one chroma row = 2*cw bytes.
        return YuvFrameK(w, h, semiPlanar = true, y, uv, ByteArray(0), stride = w, uvStride = 2 * cw)
    }
}

/** The real develop boundary: JNI into librawedit_jni.so. */
internal object NativeEncodedDeveloper : EncodedDeveloper {

    override fun develop(
        frame: YuvFrameK,
        isBt709: Boolean,
        orientationDeg: Int,
        grade: GradeK,
        lutPath: String?,
    ): IntArray? {
        if (!NativeSource.nativeAvailable) {
            // No NDK build: fail cleanly with the unavailable message instead
            // of UnsatisfiedLinkError (same behavior as the MCRAW paths).
            throw IllegalStateException(NativeSource.nativeUnavailableMessage)
        }
        return NativeSource.jniEncodedDevelop(
            frame.y, frame.u, frame.v,
            frame.width, frame.height, frame.stride, frame.uvStride,
            frame.semiPlanar, isBt709, orientationDeg,
            grade.exposureEv, grade.highlights, grade.tempK, grade.tint,
            grade.saturation, grade.blackPoint, grade.whitePoint,
            grade.shadows, grade.midtones, grade.contrast,
            grade.asShot, grade.highlightReconstruction, grade.keepVignetting,
            grade.sharpness, grade.detail, grade.tonemap,
            // COL-07: percent 0..100 -> rawedit's 0..1 blend, same mapping
            // as GradeMapping.toNative (every JNI call site passes 0..1).
            lutPath, grade.lutStrength / 100f,
        )
    }

    override fun code(): Int = NativeSource.jniPreviewErrorCode()

    override fun stage(): Int = NativeSource.jniPreviewErrorStage()

    override fun detail(): String? = NativeSource.previewLastError()
}

/** Track summary returned by [EncodedSource.open] (the UI's format line). */
data class EncodedClipInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val orientationDeg: Int,
    val isBt709: Boolean,
    /** 0 when the clip has no audio track. */
    val sampleRate: Int,
    /** 0 when the clip has no audio track. */
    val channels: Int,
)

/**
 * The encoded (Mi-Log MP4) clip decode state machine — sprint plan Task 6,
 * the device-independent half. Open -> seek -> decode frame -> convert ->
 * develop, over the three boundaries above. Pure JVM code: the real
 * MediaExtractor/MediaCodec adapters ([MediaExtractorTrackReader],
 * [MediaCodecFrameDecoder]) are thin device-gated classes, and every decision
 * around them lives here, JVM-tested with fakes (the Mp4Session/Mp4MediaWriter
 * pattern of the export path).
 *
 * Threading: one EncodedSource per clip, all calls from ONE engine thread (the
 * same contract as the MCRAW preview session — a decoder is not
 * concurrency-safe and the bridge's error state is thread-local).
 *
 * Frame addressing: open() walks the track's sample index (per-sample pts +
 * sync flags, in container order) and reorders it to PRESENTATION order —
 * MP4 stores B-frame video in decode order, while the decoder emits in
 * presentation order. Frame i is the i-th frame in presentation order. Every
 * decoded output is verified against the index by pts, so a seek that lands
 * somewhere else or a decoder that reorders fails cleanly instead of showing
 * the wrong frame (closed-GOP streams: a keyframe's GOP decodes after it, so
 * its frames follow it in presentation order too).
 *
 * Seeking: a request that does not continue the current decode position
 * seeks to the keyframe at-or-before the target and decodes forward,
 * discarding the frames before the target; sequential playback (frame i+1
 * after i) never seeks at all.
 */
class EncodedSource(
    private val reader: EncodedTrackReader = MediaExtractorTrackReader(),
    private val decoder: EncodedFrameDecoder = MediaCodecFrameDecoder(),
    private val developer: EncodedDeveloper = NativeEncodedDeveloper,
) : AutoCloseable {

    private var format: EncodedVideoFormat? = null
    private var pts = LongArray(0)
    private var sync = BooleanArray(0)
    private var sampleRate = 0
    private var channels = 0

    /** Presentation index of the frame the NEXT decoder output will be. */
    private var nextOutput = 0
    /** Presentation index inferred from the first sample after a seek (-1 = unknown). */
    private var landed = -1
    private var eosQueued = false
    /**
     * False until the first reposition: the reader opens at END of stream
     * (an extractor sits there after indexing), so even frame 0 needs a seek
     * before its first readSample.
     */
    private var positioned = false
    private var closed = false

    val frameCount: Int get() = pts.size

    /** ptsUs of frame [frameIndex] (presentation order). */
    fun ptsUs(frameIndex: Int): Long {
        require(frameIndex in pts.indices) { "frameIndex $frameIndex outside 0..${pts.size - 1}" }
        return pts[frameIndex]
    }

    /**
     * Opens the clip at [fd] (a FRESH read-only descriptor, owned by the
     * source from here on) and starts the decoder. Returns the track
     * summary; throws IllegalStateException with a complete stage + cause +
     * recovery message on any failure, having released both boundaries (a
     * failed open leaves nothing behind). One open per EncodedSource.
     */
    fun open(fd: Int): EncodedClipInfo {
        check(!closed && format == null) { "open can be called once; use a new EncodedSource" }
        try {
            val opened = when (val o = reader.open(fd)) {
                is EncodedOpenOutcome.Opened -> o
                is EncodedOpenOutcome.Failed ->
                    throw IllegalStateException(EncodedErrors.openFailure(o.kind, o.detail))
            }
            if (opened.index.frameCount == 0) {
                throw IllegalStateException(
                    EncodedErrors.kotlinMessage(
                        EncodedErrors.stageName(EncodedErrors.STAGE_OPEN),
                        "the clip holds no video frames",
                        "re-import the clip",
                    )
                )
            }
            val cfg = decoder.configure(opened.format)
            if (cfg != null) {
                throw IllegalStateException(
                    EncodedErrors.kotlinMessage(
                        EncodedErrors.STAGE_CONFIGURE,
                        "the ${opened.format.mime} decoder could not start",
                        "retry on a device with a ${opened.format.mime} decoder",
                        cfg,
                    )
                )
            }
            val ordered = presentationOrder(opened.index)
            pts = ordered.first
            sync = ordered.second
            sampleRate = opened.sampleRate
            channels = opened.channels
            format = opened.format
            nextOutput = 0
            landed = -1
            eosQueued = false
            positioned = false
            return EncodedClipInfo(
                mime = opened.format.mime,
                width = opened.format.width,
                height = opened.format.height,
                frameCount = pts.size,
                orientationDeg = opened.format.orientationDeg,
                isBt709 = opened.format.isBt709,
                sampleRate = sampleRate,
                channels = channels,
            )
        } catch (e: Throwable) {
            // A failed open owns nothing: both boundaries go away so the
            // caller never has to close a source that never opened.
            decoder.release()
            reader.release()
            closed = true
            throw e
        }
    }

    /**
     * Decodes frame [frameIndex] and develops it with [grade] into the
     * preview ARGB contract ([width, height, then w*h ARGB_8888 ints],
     * rotated upright). [lutPath] is the selected .cube's path (null = no
     * LUT, the old look; COL-07). Throws IllegalStateException with a
     * complete message on any failure. Sequential requests never re-seek;
     * a jump seeks to the keyframe at-or-before the target.
     */
    fun renderFrame(frameIndex: Int, grade: GradeK, lutPath: String? = null): IntArray {
        val fmt = requireOpen()
        require(frameIndex in pts.indices) { "frameIndex $frameIndex outside 0..${pts.size - 1}" }
        val frame = decode(frameIndex)
        val argb = developer.develop(frame, fmt.isBt709, fmt.orientationDeg, grade, lutPath)
            ?: throw IllegalStateException(
                EncodedErrors.message(developer.code(), developer.stage(), developer.detail())
            )
        return argb
    }

    override fun close() {
        if (closed) return
        closed = true
        decoder.release()
        reader.release()
        format = null
    }

    // ---- decode state machine ----

    /**
     * Presentation-ordered index: the container stores B-frame video in
     * decode order; a stable sort by pts gives the order the decoder emits
     * (closed GOPs: every frame of a GOP decodes after its keyframe, so its
     * pts follows). For B-frame-free clips this is the identity.
     */
    private fun presentationOrder(index: EncodedSampleIndex): Pair<LongArray, BooleanArray> {
        val n = index.ptsUs.size
        val order = IntArray(n) { it }.sortedBy { index.ptsUs[it] } // stable
        val p = LongArray(n) { index.ptsUs[order[it]] }
        val s = BooleanArray(n) { index.sync[order[it]] }
        return p to s
    }

    private fun decode(target: Int): YuvFrameK {
        // Position the stream: continue when the request follows the current
        // decode position; otherwise (first read after open — the reader
        // opens at end of stream — a jump, or the previous decode consumed
        // end-of-stream) seek to the keyframe at-or-before the target.
        if (!positioned || nextOutput != target || eosQueued) reposition(target)

        var samples = 0
        var eosPolls = 0
        while (true) {
            // While samples remain to feed, poll without waiting (the next
            // queueSample moves the codec); while draining end of stream,
            // give the codec a real slice of time per poll.
            val out = dequeue(if (eosQueued) EOS_POLL_TIMEOUT_US else 0)
            if (out != null) {
                eosPolls = 0
                if (landed < 0 || nextOutput >= pts.size || out.ptsUs != pts[nextOutput]) {
                    throw decodeFailure(
                        "the decoder produced an unexpected frame",
                        "decoder pts ${out.ptsUs}",
                    )
                }
                if (nextOutput == target) {
                    nextOutput++
                    return EncodedYuv.toNv12(out)
                }
                nextOutput++ // a frame before the target: decoded and discarded
                continue
            }
            if (eosQueued) {
                // End of stream: the decoder may still hold buffered frames;
                // a bounded number of empty polls decides "drained".
                if (++eosPolls > EOS_POLLS) {
                    throw decodeFailure("the clip ended before frame $target")
                }
                continue
            }
            val sample = readNext()
            if (sample == null) {
                queueEos()
                eosQueued = true
                continue
            }
            if (landed < 0) {
                // Infer the decode position from the first sample after the
                // seek (the extractor lands on the keyframe; this also catches
                // a device that seeks elsewhere).
                landed = indexOfPts(sample.ptsUs)
                if (landed < 0) {
                    throw decodeFailure(
                        "the clip index does not match the video track",
                        "sample pts ${sample.ptsUs}",
                    )
                }
                if (landed > target) {
                    throw decodeFailure(
                        "the decoder is positioned after frame $target",
                        "sample pts ${sample.ptsUs}",
                    )
                }
                nextOutput = landed
            }
            queue(sample)
            if (++samples > (target - landed) + SAMPLE_SLACK) {
                // Fed every frame up to the target plus the codec's pipeline
                // depth and still no output: the decoder is stuck.
                throw decodeFailure("the decoder made no progress on frame $target")
            }
        }
    }

    private fun reposition(target: Int) {
        val keyframe = syncIndexAtOrBefore(target)
        val seekPts = pts[if (keyframe >= 0) keyframe else 0]
        reader.seekTo(seekPts)
        decoder.flush()
        eosQueued = false
        landed = -1
        nextOutput = if (keyframe >= 0) keyframe else 0
        positioned = true
    }

    /** Greatest sync index at-or-before [target], or -1 when there is none. */
    private fun syncIndexAtOrBefore(target: Int): Int {
        for (i in minOf(target, sync.size - 1) downTo 0) {
            if (sync[i]) return i
        }
        return -1
    }

    private fun indexOfPts(ptsUs: Long): Int {
        for (i in pts.indices) if (pts[i] == ptsUs) return i
        return -1
    }

    private fun requireOpen(): EncodedVideoFormat {
        check(!closed) { "EncodedSource is closed" }
        return checkNotNull(format) { "open the clip first" }
    }

    // Adapter calls: an unexpected throw from the thin adapters is wrapped
    // with the decode stage (never a bare stack trace); the pipeline's own
    // IllegalStateExceptions propagate unchanged.
    private fun dequeue(timeoutUs: Long): YuvPlanes? = try {
        decoder.dequeueFrame(timeoutUs)
    } catch (e: Throwable) {
        throw decodeFailure("the decoder failed", e.message)
    }

    private fun readNext(): EncodedSample? = try {
        reader.readSample()
    } catch (e: Throwable) {
        throw decodeFailure("the clip could not be read", e.message)
    }

    private fun queue(sample: EncodedSample) = try {
        decoder.queueSample(sample)
    } catch (e: Throwable) {
        throw decodeFailure("the decoder rejected a frame", e.message)
    }

    private fun queueEos() = try {
        decoder.queueEos()
    } catch (e: Throwable) {
        throw decodeFailure("the decoder rejected the end of stream", e.message)
    }

    private fun decodeFailure(cause: String, detail: String? = null): IllegalStateException =
        IllegalStateException(
            EncodedErrors.kotlinMessage(EncodedErrors.STAGE_DECODE, cause, "re-open the clip", detail)
        )

    companion object {
        /** Video codecs the decode path supports (the adapter maps others to OPEN_UNSUPPORTED_MIME). */
        val SUPPORTED_VIDEO_MIMES: Set<String> = setOf("video/avc", "video/hevc")

        /** A decoder may buffer frames; this many extra samples without output = stuck. */
        private const val SAMPLE_SLACK = 32

        /** Empty dequeue polls tolerated after end-of-stream before "truncated". */
        private const val EOS_POLLS = 64

        /** Per-poll codec wait while draining end of stream (a real timeout, not a spin). */
        private const val EOS_POLL_TIMEOUT_US = 50_000L
    }
}

/** Which engine path a source takes. */
enum class SourcePath { MCRAW, ENCODED }

/**
 * Pure decision: which engine opens a given source — the MCRAW session
 * (nativePreviewOpen) or [EncodedSource]. The stored kind is authoritative
 * for ENCODED clips; the mime is a rescue for clips imported before the
 * import pass could detect the container (import defaulted every clip to
 * MCRAW): a video/mp4 mime routes to the encoded path, which either opens it
 * or fails cleanly at the extractor — an MCRAW mislabeled video/mp4 by SAF
 * still reports "the clip could not be read", never a crash.
 */
object SourcePathDecision {

    /** Container mime that routes to the encoded path. */
    const val MP4_MIME = "video/mp4"

    fun decide(kind: SourceKind, mime: String?): SourcePath = when {
        kind == SourceKind.ENCODED -> SourcePath.ENCODED
        mime == null -> SourcePath.MCRAW
        mime.trim().equals(MP4_MIME, ignoreCase = true) -> SourcePath.ENCODED
        else -> SourcePath.MCRAW
    }
}
