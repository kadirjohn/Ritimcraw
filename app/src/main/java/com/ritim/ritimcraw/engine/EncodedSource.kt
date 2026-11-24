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
    /**
     * What the track's pixels are (E0): bit depth, range, matrix, transfer,
     * primaries - the one value preview AND export hand to the develop.
     * Defaults to the pre-E0 assumption for fakes and legacy call sites.
     */
    val color: EncodedColorSpec = EncodedColorSpec.legacy8bit(),
    /**
     * The track's codec-specific data (avcC/hvcC → SPS/PPS/... buffers, in
     * container order). A decoder cannot start without it; the pipeline
     * never inspects it — it travels from the reader to the decoder adapter.
     */
    val codecConfig: List<ByteArray> = emptyList(),
) {
    /** True unless the track is BT.601 (kept for the UI's format line). */
    val isBt709: Boolean get() = color.profile.matrix != com.ritim.ritimcraw.data.color.SourceMatrix.BT601
}

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
    /**
     * Bits per sample the decoder produced: 8 (one byte per sample) or 10
     * (P010: one 16-bit little-endian word per sample, value in the high
     * bits; the planes' pixelStride is 2/4/4). The decoder adapter derives
     * it from the Image itself, never from the container's claim.
     */
    val bitDepth: Int = 8,
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
    /** 8, or 10 = 16-bit little-endian MSB-aligned words (strides are then still BYTES per row). */
    val bitDepth: Int = 8,
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

    /** E0b-4b: hands a frame's plane arrays back once nothing reads them (a pooling decoder reuses them; default: GC). */
    fun recycle(planes: YuvPlanes) {}

    /**
     * E0b-4b: [dequeueFrame] that may skip the pixel copy ([copyPixels] false = only the pts matters: a frame
     * before the target that is decoded and discarded). A skipped frame comes back with EMPTY planes. Default:
     * the plain dequeue (fakes keep working).
     */
    fun dequeueFrame(timeoutUs: Long, copyPixels: Boolean): YuvPlanes? = dequeueFrame(timeoutUs)
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
     * Mi-Log decode -> WB/temp/tint/exposure -> denoise stages 7/8
     * (DN-01..08, the same kernels as the RAW path; 0 = skipped) ->
     * sharpness/detail/tonemap, the shared COL-15/COL-16 stages, then the
     * optional stage-22 LUT, COL-07) -> Rec.709 -> ARGB, rotated upright by
     * [orientationDeg]. [lutPath] is the selected .cube's path (null = no
     * LUT, the old look). Returns [width, height, then w*h ARGB_8888 ints]
     * (the preview contract) or null on failure. [color] is the clip's
     * declared color (E0): range expansion, matrix, transfer decode and the
     * gamut step all follow it, at the frame's own bit depth.
     */
    fun develop(
        frame: YuvFrameK,
        color: EncodedColorSpec,
        orientationDeg: Int,
        grade: GradeK,
        lutPath: String? = null,
        /** F1-6 (plan §8/K11): the v2 node chain's wire; null = the legacy fast path. */
        chainWire: FloatArray? = null,
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

    /**
     * E0b-4b: the GPU door's frame, WITHOUT the per-pixel repack: the luma plane is used in place (its own
     * row stride) and the interleaved CbCr plane is rebuilt with ONE bulk copy, because the adapter copies
     * the codec's Image planes separately (the U plane ends one chroma word short of the last Cr, which the V
     * plane carries). Null when the layout is not NV12/P010-shaped (planar I420, NV21, short planes): the
     * caller then takes the repack + CPU path.
     */
    fun toGpuFrame(p: YuvPlanes): YuvFrameK? {
        val w = p.width
        val h = p.height
        if (w <= 0 || h <= 0) return null
        val bps = if (p.bitDepth > 8) 2 else 1
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        if (p.y.pixelStride != bps || p.y.offset != 0) return null
        // The pooled adapter's frames: ONE interleaved chroma array, U and V views of it (no copy at all).
        if (p.u.buffer === p.v.buffer && p.u.offset == 0 && p.v.offset == bps && p.u.pixelStride == 2 * bps &&
            p.u.rowStride % (2 * bps) == 0 && p.y.rowStride % bps == 0
        ) {
            val cs = p.u.rowStride
            val yn = (h - 1) * p.y.rowStride + w * bps
            val cn = (ch - 1) * cs + cw * 2 * bps
            if (p.y.buffer.size < yn || p.u.buffer.size < cn) return null
            return YuvFrameK(
                w, h, semiPlanar = true, p.y.buffer, p.u.buffer, ByteArray(0),
                stride = p.y.rowStride, uvStride = cs, bitDepth = if (bps == 2) 10 else 8,
            )
        }
        if (p.u.pixelStride != 2 * bps || p.v.pixelStride != 2 * bps) return null
        if (p.u.offset != 0 || p.v.offset != 0 || p.u.rowStride != p.v.rowStride) return null
        val yNeed = (h - 1) * p.y.rowStride + w * bps
        val cStride = p.u.rowStride
        val cNeed = (ch - 1) * cStride + cw * 2 * bps
        if (p.y.buffer.size < yNeed || cStride % (2 * bps) != 0) return null
        if (p.y.rowStride % bps != 0) return null
        // The V plane must be the U plane shifted by one component (NV12/P010, not NV21).
        if (p.u.buffer.size < bps + 1 || p.v.buffer.size < 1 || p.u.buffer[bps] != p.v.buffer[0]) return null
        if (p.u.buffer.size < cNeed - bps || p.v.buffer.size < bps) return null
        val c = ByteArray(cNeed)
        System.arraycopy(p.u.buffer, 0, c, 0, minOf(p.u.buffer.size, cNeed))
        System.arraycopy(p.v.buffer, p.v.buffer.size - bps, c, cNeed - bps, bps)
        return YuvFrameK(
            w, h, semiPlanar = true, p.y.buffer, c, ByteArray(0),
            stride = p.y.rowStride, uvStride = cStride, bitDepth = if (bps == 2) 10 else 8,
        )
    }

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

        // E0: 10-bit planes carry a 16-bit little-endian word per sample (P010);
        // the pack keeps both bytes of every sample, the develop reads the
        // high bits. 8-bit is the old one-byte copy, bit-identical.
        val bps = if (p.bitDepth > 8) 2 else 1
        if (bps == 2) {
            require(p.y.buffer.size >= needRow(p.y, h, w) + 1) { "y plane too small for ${w}x$h (16-bit)" }
            require(p.u.buffer.size >= needRow(p.u, ch, cw) + 1) { "u plane too small for ${w}x$h (16-bit)" }
            require(p.v.buffer.size >= needRow(p.v, ch, cw) + 1) { "v plane too small for ${w}x$h (16-bit)" }
        }
        val y = ByteArray(w * h * bps)
        for (r in 0 until h) {
            for (c in 0 until w) {
                val src = p.y.offset + r * p.y.rowStride + c * p.y.pixelStride
                val dst = (r * w + c) * bps
                y[dst] = p.y.buffer[src]
                if (bps == 2) y[dst + 1] = p.y.buffer[src + 1]
            }
        }
        val uv = ByteArray(2 * cw * ch * bps)
        for (r in 0 until ch) {
            for (c in 0 until cw) {
                val dst = (r * cw + c) * 2 * bps
                val su = p.u.offset + r * p.u.rowStride + c * p.u.pixelStride
                val sv = p.v.offset + r * p.v.rowStride + c * p.v.pixelStride
                uv[dst] = p.u.buffer[su]
                uv[dst + bps] = p.v.buffer[sv]
                if (bps == 2) {
                    uv[dst + 1] = p.u.buffer[su + 1]
                    uv[dst + bps + 1] = p.v.buffer[sv + 1]
                }
            }
        }
        // Packed NV12/P010: one luma row = w*bps bytes, one chroma row = 2*cw*bps bytes.
        return YuvFrameK(
            w, h, semiPlanar = true, y, uv, ByteArray(0),
            stride = w * bps, uvStride = 2 * cw * bps, bitDepth = if (bps == 2) 10 else 8,
        )
    }
}

/**
 * E0b-4b: the GPU develop door for the encoded PREVIEW. Same contract as [EncodedDeveloper.develop]'s return
 * ([w, h, ARGB...], here of the REDUCED frame) but null means "take the CPU path", never an error.
 * One instance per clip, used from the preview engine thread only (it owns a GLES context).
 */
interface EncodedGpuDeveloper {
    fun available(): Boolean
    fun develop(frame: YuvFrameK, color: EncodedColorSpec, grade: GradeK, reduce: Int): IntArray?
    fun close()
}

/** The real door: the develop session's YUV entry (nativeDevelopSessionRenderYuvParcel). Opens lazily, once. */
internal class NativeEncodedGpuDeveloper : EncodedGpuDeveloper {
    private var session = 0L
    private var dead = false

    override fun available(): Boolean = !dead && NativeSource.nativeAvailable

    private fun ensure(): Boolean {
        if (session > 0) return true
        if (dead) return false
        session = try { com.ritim.ritimcraw.export.NativeGpuDevelop.openSession() } catch (_: Throwable) { 0L }
        if (session <= 0) { dead = true; session = 0 }
        return session > 0
    }

    override fun develop(frame: YuvFrameK, color: EncodedColorSpec, grade: GradeK, reduce: Int): IntArray? {
        if (!ensure()) return null
        val rgb = try {
            NativeSource.jniDevelopSessionRenderYuvParcel(
                session, frame.y, frame.u, frame.width, frame.height, frame.stride, frame.uvStride,
                frame.bitDepth, color.fullRange, color.nativeMatrix, color.nativeTransfer, color.nativePrimaries,
                reduce, GradeParcel.encode(grade),
            )
        } catch (_: Throwable) { null } ?: return null
        val w = (frame.width + reduce - 1) / reduce
        val h = (frame.height + reduce - 1) / reduce
        if (rgb.size != w * h * 3) return null
        val out = IntArray(2 + w * h)
        out[0] = w; out[1] = h
        var i = 0
        for (k in 0 until w * h) {
            out[2 + k] = (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or
                ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
            i += 3
        }
        return out
    }

    override fun close() {
        if (session > 0) { NativeSource.jniDevelopSessionClose(session); session = 0 }
    }
}

/** The real develop boundary: JNI into librawedit_jni.so. */
internal object NativeEncodedDeveloper : EncodedDeveloper {

    override fun develop(
        frame: YuvFrameK,
        color: EncodedColorSpec,
        orientationDeg: Int,
        grade: GradeK,
        lutPath: String?,
        chainWire: FloatArray?,
    ): IntArray? {
        if (!NativeSource.nativeAvailable) {
            // No NDK build: fail cleanly with the unavailable message instead
            // of UnsatisfiedLinkError (same behavior as the MCRAW paths).
            throw IllegalStateException(NativeSource.nativeUnavailableMessage)
        }
        val tDev = System.nanoTime()
        val result = NativeSource.jniEncodedDevelop(
            frame.y, frame.u, frame.v,
            frame.width, frame.height, frame.stride, frame.uvStride,
            frame.semiPlanar, color.nativeMatrix != 0, orientationDeg,
            frame.bitDepth, color.fullRange, color.nativeMatrix, color.nativeTransfer,
            color.nativePrimaries,
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
            // Signature parity with the CFA paths; the encoded (Mi-Log) path
            // has no CFA and no neighbor frames, so the bridge ignores the
            // temporal fields here (DN-05..DN-09 apply only to MCRAW clips).
            grade.temporalNr, grade.temporalStrength,
            // COL-13: same parity-travel-and-ignore contract — the wheel and
            // split-tone fields ride the signature but the encoded path's
            // grade is its own WB/exposure loop (see Transfer.cpp: the tone
            // band stages 9-20 stay MCRAW-only).
            grade.wheelShadowX, grade.wheelShadowY,
            grade.wheelMidX, grade.wheelMidY,
            grade.wheelHighX, grade.wheelHighY,
            grade.splitShadowHue, grade.splitHighlightHue, grade.splitBalance,
            // Managed LUT interpretation (owner request 2026-11-13): the
            // grade's binding BY VALUE; null = the legacy stage-24 path
            // (all five -1, byte-identical).
            grade.lutInterp?.role?.value ?: -1,
            grade.lutInterp?.input?.gamut?.value ?: -1,
            grade.lutInterp?.input?.transfer?.value ?: -1,
            grade.lutInterp?.output?.gamut?.value ?: -1,
            grade.lutInterp?.output?.transfer?.value ?: -1,
            // F1-6 (K11): the v2 chain's wire; null = the legacy fast path.
            chainWire,
        )
        EncodedFrameTrace.logFrame(
            frame.width, frame.height, frame.bitDepth, System.nanoTime() - tDev, result != null,
        )
        return result
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
    /** The track's declared color (E0); the same value preview and export develop with. */
    val color: EncodedColorSpec = EncodedColorSpec.legacy8bit(),
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
    /** E0b-4b: the GPU develop for PREVIEW renders (null = CPU only). Falls back to [developer] per frame. */
    private val gpuDeveloper: EncodedGpuDeveloper? = NativeEncodedGpuDeveloper(),
    /**
     * E0b-4b: decode on a helper thread, a couple of frames AHEAD of the render thread (the preview's
     * sequential playback). Off by default (the export reads frames strictly in order, and the JVM tests'
     * fakes assume one thread); the preview session turns it on.
     */
    private val readAhead: Boolean = false,
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
                color = opened.format.color,
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
    fun renderFrame(
        frameIndex: Int,
        grade: GradeK,
        lutPath: String? = null,
        /**
         * true (the preview) = rotated upright by the container hint; false
         * (the export) = the coded orientation - the export pipeline rotates
         * pixels itself from [EncodedClipInfo.orientationDeg] and the
         * manual delta, exactly like the MCRAW session.
         */
        upright: Boolean = true,
        /** F1-6 (plan §8/K11): the v2 node chain's wire; null = the legacy fast path. */
        chainWire: FloatArray? = null,
        /**
         * E0b-4b: the preview's long-edge budget. > 0 lets the GPU path reduce first and develop the
         * REDUCED frame (the CPU path always develops full resolution); 0 = full resolution (CPU).
         */
        maxDim: Int = 0,
    ): IntArray {
        val fmt = requireOpen()
        require(frameIndex in pts.indices) { "frameIndex $frameIndex outside 0..${pts.size - 1}" }
        val gpu = gpuDeveloper
        // The GPU door covers the preview's common shape; nodes, a LUT, a rotated clip and a manual
        // orientation go to the CPU oracle (honest fallback, noted in the trace).
        val gpuEligible = gpu != null && maxDim > 0 && chainWire == null && lutPath == null &&
            upright && fmt.orientationDeg == 0 && grade.lutInterp == null && gpu.available()
        val tDecode = System.nanoTime()
        val planes = if (readAhead) fetchAhead(frameIndex) else decodePlanes(frameIndex)
        val decodeWallNs = System.nanoTime() - tDecode
        if (gpuEligible) {
            val color = fmt.color.withDecodedBitDepth(planes.bitDepth)
            val tGpu = System.nanoTime()
            val g = EncodedYuv.toGpuFrame(planes)
            val reduce = maxOf(1, (maxOf(planes.width, planes.height) + maxDim - 1) / maxDim).coerceAtMost(8)
            val tDev = System.nanoTime()
            val out = if (g != null) gpu!!.develop(g, color, grade, reduce) else null
            val now = System.nanoTime()
            EncodedFrameTrace.logPath(
                if (out != null) "gpu" else "cpu-fallback", planes.width, planes.height, planes.bitDepth, reduce,
                now - tGpu, decodeWallNs, EncodedFrameTrace.copyNanos.getAndSet(0), tDev - tGpu, now - tDev,
                lastDecodedCount,
            )
            if (out != null) { decoder.recycle(planes); return out }
        }
        val frame = EncodedYuv.toNv12(planes)
        decoder.recycle(planes)
        // The frame's decoded depth wins over the container's claim: a
        // decoder that returned 8-bit for a 10-bit track is developed (and
        // reported) as 8-bit, never mis-read as 10.
        val color = fmt.color.withDecodedBitDepth(frame.bitDepth)
        val argb = developer.develop(frame, color, if (upright) fmt.orientationDeg else 0, grade, lutPath, chainWire)
            ?: throw IllegalStateException(
                EncodedErrors.message(developer.code(), developer.stage(), developer.detail())
            )
        return argb
    }

    override fun close() {
        if (closed) return
        closed = true
        stopAhead()
        try { gpuDeveloper?.close() } catch (_: Throwable) {}
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

    private fun decode(target: Int): YuvFrameK =
        EncodedYuv.toNv12(if (readAhead) fetchAhead(target) else decodePlanes(target))

    // ---- E0b-4b: decode-ahead (readAhead = true) ----
    //
    // ONE helper thread owns the reader, the decoder and every decode-state field (decodePlanes and what it
    // touches); the render thread only talks to it through [aheadLock]. The helper decodes the frame the
    // render thread asked for (frames before it are dequeued WITHOUT a pixel copy), then keeps decoding the
    // following frames into a small queue so a sequential playback finds its next frame already waiting
    // and the decode overlaps the GPU develop of the previous one. A request that is not in the queue
    // clears it and is decoded from the current position (same-GOP skip, or a seek: decodePlanes decides).
    // The queue depth is bounded by memory (a 4K P010 frame is 25 MB, an 8K one 50 MB, heap limit 256 MB).

    private val aheadLock = Object()
    private var aheadThread: Thread? = null
    private var aheadStop = false
    private var aheadWant = -1            // newest requested frame (render thread writes)
    private var aheadServed = -1          // the request the render thread already took from the queue
    private var aheadNext = -1            // next frame the helper will produce ahead of the request
    private var aheadFailure: Throwable? = null
    private var aheadFailedFor = -1
    private val aheadReady = ArrayList<Pair<Int, YuvPlanes>>()
    private var aheadDepth = 2

    private fun fetchAhead(target: Int): YuvPlanes {
        synchronized(aheadLock) {
            if (aheadThread == null) {
                val t = Thread({ aheadLoop() }, "encoded-decode-ahead")
                t.isDaemon = true
                aheadThread = t
                t.start()
            }
            aheadWant = target
            aheadServed = -1
            aheadFailure = null
            aheadFailedFor = -1
            aheadLock.notifyAll()
            val deadline = System.nanoTime() + AHEAD_TIMEOUT_NS
            while (true) {
                aheadFailure?.let { throw it }
                val at = aheadReady.indexOfFirst { it.first == target }
                if (at >= 0) {
                    val hit = aheadReady[at].second
                    // Older frames the playback skipped over go back to the pool; newer ones stay queued.
                    for (i in at - 1 downTo 0) decoder.recycle(aheadReady.removeAt(i).second)
                    aheadReady.removeAt(0)
                    aheadServed = target
                    aheadLock.notifyAll()
                    return hit
                }
                val left = deadline - System.nanoTime()
                if (left <= 0) throw decodeFailure("the decode-ahead made no progress on frame $target")
                aheadLock.wait(left / 1_000_000L, (left % 1_000_000L).toInt())
            }
        }
    }

    private fun aheadLoop() {
        // The helper competes with the render thread and the UI for the big cores; a late decoded frame is
        // a dropped frame, so it runs at display priority (best effort: the JVM tests have no Process).
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) } catch (_: Throwable) {}
        while (true) {
            val goal: Int
            synchronized(aheadLock) {
                while (true) {
                    if (aheadStop) return
                    val w = aheadWant
                    if (w >= 0 && aheadFailedFor != w) {
                        // Frames older than the request are useless to a forward playback.
                        while (aheadReady.isNotEmpty() && aheadReady[0].first < w) decoder.recycle(aheadReady.removeAt(0).second)
                        if (aheadServed != w && aheadReady.none { it.first == w }) {
                            // Not queued: stale newer frames (a backward jump) go too, then decode the request.
                            if (aheadReady.isNotEmpty() && aheadReady[0].first > w) {
                                for (e in aheadReady) decoder.recycle(e.second)
                                aheadReady.clear()
                            }
                            goal = w; break
                        }
                        if (aheadReady.size < aheadDepth && aheadNext in 0 until pts.size && aheadNext > w) {
                            goal = aheadNext; break
                        }
                    }
                    aheadLock.wait()
                }
            }
            try {
                val tP = System.nanoTime()
                val planes = decodePlanes(goal)
                EncodedFrameTrace.logAhead(goal, aheadWant, lastDecodedCount, System.nanoTime() - tP)
                synchronized(aheadLock) {
                    if (aheadStop) { decoder.recycle(planes); return }
                    if (aheadWant > goal) {
                        decoder.recycle(planes) // the request moved past this frame while it decoded
                    } else {
                        aheadReady.add(goal to planes)
                        aheadReady.sortBy { it.first }
                    }
                    aheadNext = goal + 1
                    aheadDepth = aheadDepthFor(planes)
                    aheadLock.notifyAll()
                }
            } catch (t: Throwable) {
                synchronized(aheadLock) {
                    aheadFailure = t
                    aheadFailedFor = goal
                    aheadNext = -1
                    aheadLock.notifyAll()
                    // Park until the render thread asks for something else.
                    while (!aheadStop && aheadWant == aheadFailedFor) aheadLock.wait()
                    aheadFailedFor = -1
                }
            }
        }
    }

    /** Queue depth for the frame size just produced: the three memory tiers, decided by the plane bytes. */
    private fun aheadDepthFor(p: YuvPlanes): Int {
        val bytes = p.y.buffer.size.toLong() + p.u.buffer.size + (if (p.v.buffer === p.u.buffer) 0 else p.v.buffer.size)
        return queueDepthForPlaneBytes(bytes)
    }

    private fun stopAhead() {
        val t: Thread?
        synchronized(aheadLock) {
            aheadStop = true
            t = aheadThread
            aheadLock.notifyAll()
        }
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(AHEAD_JOIN_MS) } catch (_: InterruptedException) {}
        }
    }

    /** Frames the decoder emitted for the LAST render (the skipped ones plus the target), for the trace. */
    @Volatile private var lastDecodedCount = 0

    private fun decodePlanes(target: Int): YuvPlanes {
        lastDecodedCount = 0
        // Position the stream: continue when the request follows the current
        // decode position; otherwise (first read after open — the reader
        // opens at end of stream — a jump, or the previous decode consumed
        // end-of-stream) seek to the keyframe at-or-before the target.
        // E0b-4b: a FORWARD jump inside the current GOP needs no seek: the decoder is already past the
        // keyframe that [target] depends on, so it just keeps decoding (the frames in between are discarded
        // without a pixel copy). The old rule re-seeked on ANY non-sequential request, so every skipped frame
        // of a playback re-decoded the whole GOP from its keyframe (15-52 frames per render on the 4K60 clip).
        val sameGopForward = positioned && !eosQueued && landed >= 0 && nextOutput <= target &&
            syncIndexAtOrBefore(target) <= nextOutput
        if (!positioned || eosQueued || (nextOutput != target && !sameGopForward)) reposition(target)

        var samples = 0
        var eosPolls = 0
        while (true) {
            // While samples remain to feed, poll without waiting (the next
            // queueSample moves the codec); while draining end of stream,
            // give the codec a real slice of time per poll.
            // A frame known to precede the target is decoded and discarded: skip its pixel copy (the
            // position is only known once the first sample after a seek has landed).
            val out = dequeue(if (eosQueued) EOS_POLL_TIMEOUT_US else 0, copyPixels = landed < 0 || nextOutput >= target)
            if (out != null) {
                eosPolls = 0
                lastDecodedCount++
                if (landed < 0 || nextOutput >= pts.size || out.ptsUs != pts[nextOutput]) {
                    throw decodeFailure(
                        "the decoder produced an unexpected frame",
                        "decoder pts ${out.ptsUs}",
                    )
                }
                if (nextOutput == target) {
                    nextOutput++
                    return out
                }
                nextOutput++ // a frame before the target: decoded and discarded
                if (out.y.buffer.isNotEmpty()) decoder.recycle(out)
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
    private fun dequeue(timeoutUs: Long, copyPixels: Boolean = true): YuvPlanes? = try {
        decoder.dequeueFrame(timeoutUs, copyPixels)
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
        private const val AHEAD_TIMEOUT_NS = 30_000_000_000L
        private const val AHEAD_JOIN_MS = 3_000L
        /** A decoded frame above this many plane bytes (8K) is only queued one deep: heap limit 256 MB. */
        private const val AHEAD_BIG_FRAME_BYTES = 40L * 1024 * 1024
        /**
         * 261124-tmp: a decoded frame above this many plane bytes but at-or-below [AHEAD_BIG_FRAME_BYTES]
         * (4K 10-bit P010, measured 31.6 MB/frame on the Mi-Log clip) is queued TWO deep; at-or-below it
         * (4K 8-bit ~12.7 MB, small frames) THREE. Eight-K-class frames stay one deep. Heap limit is 256 MB,
         * so the tiers are 50 / 63 / <54 MB of queued planes.
         */
        private const val AHEAD_HALF_FRAME_BYTES = 18L * 1024 * 1024

        /**
         * 261124-tmp: the ready-queue depth per frame size, a pure companion function (no instance state)
         * so tests pin the admission policy directly. Boundaries are strictly-greater, mirroring the
         * big-frame rule: above 40 MB (8K, ~50 MB measured) -> 1; above 18 MB (4K P010 31.6 MB) -> 2;
         * otherwise (4K 8-bit 12.7 MB, small) -> 3.
         */
        internal fun queueDepthForPlaneBytes(bytes: Long): Int = when {
            bytes > AHEAD_BIG_FRAME_BYTES -> 1
            bytes > AHEAD_HALF_FRAME_BYTES -> 2
            else -> 3
        }

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


/**
 * Debug-only (ritim-trace) per-frame breakdown of the encoded CPU preview: the time the
 * decoder wait took (includes seek/GOP catch-up decodes since the last developed frame),
 * the plane copy (Image -> JVM arrays), and the native developEncoded call, plus the bytes
 * moved. One line per developed frame; release builds do nothing.
 */
internal object EncodedFrameTrace {
    val decodeNanos = java.util.concurrent.atomic.AtomicLong(0)
    val copyNanos = java.util.concurrent.atomic.AtomicLong(0)
    val planeBytes = java.util.concurrent.atomic.AtomicLong(0)

    fun logAhead(goal: Int, want: Int, decoded: Int, nanos: Long) {
        if (!com.ritim.ritimcraw.BuildConfig.DEBUG) return
        runCatching { android.util.Log.i("ritim-trace", String.format(java.util.Locale.ROOT, "ahead-produce goal=%d want=%d decoded=%d took=%.1fms", goal, want, decoded, nanos / 1e6)) }
    }

    /** Which develop path took the frame (E0b-4b): "gpu" or "cpu-fallback"; the CPU-only case logs via [logFrame]. */
    fun logPath(path: String, w: Int, h: Int, bits: Int, reduce: Int, nanos: Long,
                decodeWallNs: Long = 0, adapterCopyNs: Long = 0, packNs: Long = 0, gpuNs: Long = 0, decoded: Int = 0) {
        if (!com.ritim.ritimcraw.BuildConfig.DEBUG) return
        runCatching {
        android.util.Log.i(
            "ritim-trace",
            String.format(java.util.Locale.ROOT, "encoded-path %s src=%dx%d bits=%d reduce=%d develop=%.1fms decodeWait=%.1fms (adapterCopy=%.1fms) pack=%.1fms gpuJni=%.1fms decoded=%d", path, w, h, bits, reduce, nanos / 1e6, decodeWallNs / 1e6, adapterCopyNs / 1e6, packNs / 1e6, gpuNs / 1e6, decoded),
        )
        }
    }

    fun logFrame(w: Int, h: Int, bits: Int, developNanos: Long, ok: Boolean) {
        if (!com.ritim.ritimcraw.BuildConfig.DEBUG) return
        val dec = decodeNanos.getAndSet(0)
        val cp = copyNanos.getAndSet(0)
        android.util.Log.i(
            "ritim-trace",
            String.format(
                java.util.Locale.ROOT,
                "encoded-frame %dx%d %d-bit decode=%.1fms copy=%.1fms develop=%.1fms planes=%.1fMB ok=%b",
                w, h, bits, dec / 1e6, cp / 1e6, developNanos / 1e6, planeBytes.get() / 1048576.0, ok,
            ),
        )
    }
}
