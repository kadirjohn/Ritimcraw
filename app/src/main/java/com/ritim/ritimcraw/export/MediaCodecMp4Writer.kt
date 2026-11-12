package com.ritim.ritimcraw.export

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real [Mp4MediaWriter]: MediaCodec H.264 (video/avc) + AAC
 * (audio/mp4a-latm) into a MediaMuxer MP4. This is the ONLY Android
 * framework class in the MP4 export path — MediaCodec/MediaMuxer cannot
 * run in JVM unit tests (framework stubs throw), so every decision around
 * it lives in the JVM-tested pipeline, and this class stays a thin,
 * device-gated adapter.
 *
 * Encoder choices (sprint plan Task 5, SDR Rec.709 reference export):
 *   video/avc, COLOR_FormatYUV420Flexible via the Image API (plane strides
 *   are the codec's, not ours), ~20 Mbps at 4K (w*h*2.4 bits/s, clamped),
 *   1 s i-frame interval, profile High, SDR Rec.709 + limited range tags;
 *   audio/mp4a-latm AAC-LC at the clip's own rate/channels, 128 kbps.
 *
 * Track setup follows the MediaMuxer contract: tracks are added from each
 * encoder's INFO_OUTPUT_FORMAT_CHANGED (never the input format), the muxer
 * starts only after every expected track is added, and samples produced
 * before that moment are held in [pending] and flushed on start — so no
 * encoded packet is ever dropped or written before the muxer is ready.
 */
internal class MediaCodecMp4Writer : Mp4MediaWriter {

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var width = 0
    private var height = 0
    private var sampleRate = 0
    private var channels = 0

    private var videoTrack = -1
    private var audioTrack = -1
    private var videoTrackAdded = false
    private var audioTrackAdded = false
    private var muxerStarted = false

    /** Encoded packets that arrived before the muxer could start. */
    private val pending = ArrayList<PendingSample>()

    /**
     * Per-export stage-timing sink (owner request 2026-11-13): attached by
     * the pipeline right before the encode loop, same thread. Null (the
     * default) = timing off — writeVideoFrame then runs its two extra
     * System.nanoTime reads per frame and nothing else changes.
     */
    private var timing: ExportStageTiming? = null

    override fun attachTiming(timing: ExportStageTiming?) {
        this.timing = timing
    }

    private class PendingSample(
        val track: Int,
        val bytes: ByteArray,
        val info: MediaCodec.BufferInfo,
    )

    override fun start(
        outPath: String,
        width: Int,
        height: Int,
        orientationDeg: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        this.width = width
        this.height = height
        this.sampleRate = sampleRate
        this.channels = channels

        try {
            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            (muxer as MediaMuxer).setOrientationHint(orientationDeg)
        } catch (e: Exception) {
            release()
            throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_MUX,
                    "the muxer could not open $outPath",
                    "check free space and retry",
                    e.message,
                )
            )
        }

        videoCodec = configure("video/avc", videoFormat(width, height))
        if (sampleRate > 0 && channels > 0) {
            audioCodec = configure("audio/mp4a-latm", audioFormat(sampleRate, channels))
        }
    }

    /**
     * Configures and starts one encoder. A rejected format surfaces as an
     * IllegalStateException naming the stage ("configure") and the codec —
     * the rejected-encoder contract of the pipeline.
     *
     * Once the codec is created, the REAL encoder identity is recorded and
     * logged (owner request 2026-11-12 §4): name, canonical name, the
     * vendor's declared hardware/software flags, plus the requested MIME —
     * requested vs actually-created never conflated (see
     * [EncoderDiagnostics]). The video encoder's line is what the owner
     * greps to answer "which encoder really ran".
     */
    private fun configure(type: String, format: MediaFormat): MediaCodec {
        val codec = try {
            MediaCodec.createEncoderByType(type)
        } catch (e: Exception) {
            throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_CONFIGURE,
                    "no $type encoder is available on this device",
                    "retry the export on a device with a $type encoder",
                    e.message,
                )
            )
        }
        logEncoderIdentity(codec, type)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_CONFIGURE,
                    "$type rejected the ${format.getInteger(MediaFormat.KEY_WIDTH)}x" +
                        "${format.getInteger(MediaFormat.KEY_HEIGHT)} format",
                    "retry the export (the clip may exceed this device's encoder limits)",
                    e.message,
                )
            )
        }
        return codec
    }

    /**
     * Records and logs the created encoder's real identity (owner request
     * 2026-11-12 §4): the vendor's codecInfo name, the canonical name, the
     * declared hardware/software flags — the actually-created encoder, not
     * the MediaCodecList probe. Best-effort: an identity read failure never
     * fails the export (the encode itself is the contract; the log line is
     * diagnostics). minSdk 34 makes the API-29 fields readable everywhere
     * this app runs.
     */
    private fun logEncoderIdentity(codec: MediaCodec, requestedMime: String) {
        try {
            val info = codec.codecInfo
            val record = EncoderDiagnostics(
                name = codec.name,
                canonicalName = codec.canonicalName,
                hardwareAccelerated = info.isHardwareAccelerated,
                softwareOnly = info.isSoftwareOnly,
            )
            Log.i(TAG, EncoderDiagnostics.logLine(record, requestedMime))
        } catch (e: Exception) {
            // Identity is diagnostics, not the contract: log the miss and
            // keep encoding (an export must not fail because a vendor
            // returned an odd codecInfo).
            Log.w(TAG, "encoder identity unavailable: ${e.message}")
        }
    }

    private fun videoFormat(w: Int, h: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // ~20 Mbps at 4K (3840*2160*2.4 = 19.9 Mbps), clamped.
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                (w.toLong() * h * 24 / 10).toInt().coerceIn(2_000_000, 25_000_000),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            // SDR Rec.709 limited range (the YuvConverter's matrix).
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }

    private fun audioFormat(rate: Int, ch: Int): MediaFormat =
        MediaFormat.createAudioFormat(MIME_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

    override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
        val codec = videoCodec ?: throw notStarted()
        val timing = this.timing
        val tWait0 = System.nanoTime()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw encodeFailure("$MIME_AVC returned no input buffer")
        }
        val tCopy0 = System.nanoTime()
        try {
            val image = codec.getInputImage(index)
                ?: throw encodeFailure("$MIME_AVC gave no input Image for this frame")
            copyI420ToImage(i420, image, width, height)
            // The size must carry the frame's filled byte count computed from
            // the same plane strides the copy just used — the codec does NOT
            // infer it from the Image (CTS EncodeDecodeTest pattern; see
            // imageInputSize). Size 0 tells the encoder the buffer is empty.
            val size = imageInputSize(
                width = width,
                height = height,
                yRowStride = image.planes[0].rowStride,
                uRowStride = image.planes[1].rowStride,
                uPixelStride = image.planes[1].pixelStride,
                vRowStride = image.planes[2].rowStride,
                vPixelStride = image.planes[2].pixelStride,
            )
            codec.queueInputBuffer(index, 0, size, ptsUs, 0)
        } catch (e: IllegalStateException) {
            throw e
        }
        val tDrain0 = System.nanoTime()
        drain(codec, isVideo = true, timeoutUs = 0)
        // Stage timing (owner request 2026-11-13): the three writer
        // internals of this frame, reported into the pipeline's per-frame
        // slots (same thread — this call runs inside the pipeline's frame
        // iteration, before its endFrame reads the slots).
        timing?.markWriterStages(
            encWaitNanos = tCopy0 - tWait0,
            planeCopyNanos = tDrain0 - tCopy0,
            drainNanos = System.nanoTime() - tDrain0,
        )
    }

    override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {
        val codec = audioCodec ?: throw notStarted()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw encodeFailure("$MIME_AAC returned no input buffer")
        }
        val buf = codec.getInputBuffer(index)
            ?: throw encodeFailure("$MIME_AAC gave no input buffer for this block")
        buf.clear()
        buf.order(ByteOrder.nativeOrder())
        for (s in pcm) buf.putShort(s)
        codec.queueInputBuffer(index, 0, pcm.size * 2, ptsUs, 0)
        drain(codec, isVideo = false, timeoutUs = 0)
    }

    override fun finish(): Boolean {
        val v = videoCodec
        val a = audioCodec
        if (v != null) signalEos(v)
        if (a != null) signalEos(a)
        v?.let { drainUntilEos(it, isVideo = true) }
        a?.let { drainUntilEos(it, isVideo = false) }
        val m = muxer ?: return false
        if (!muxerStarted) return false
        return try {
            m.stop()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun release() {
        videoCodec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            c.release()
        }
        videoCodec = null
        audioCodec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            c.release()
        }
        audioCodec = null
        muxer?.let { m ->
            try {
                m.release()
            } catch (_: Exception) {
            }
        }
        muxer = null
        pending.clear()
    }

    // ---- internals ----

    private fun signalEos(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /** Drains until this codec's end-of-stream buffer appears (finish path). */
    private fun drainUntilEos(codec: MediaCodec, isVideo: Boolean) {
        val deadline = System.nanoTime() + EOS_DEADLINE_NS
        while (System.nanoTime() < deadline) {
            if (drain(codec, isVideo, timeoutUs = 1_000_000)) return
        }
        throw encodeFailure(
            (if (isVideo) MIME_AVC else MIME_AAC) + " did not finish within the deadline"
        )
    }

    /**
     * Drains whatever output is ready. Returns true when this codec's
     * end-of-stream buffer was seen. Samples that surface before the muxer
     * can start are held in [pending] and flushed the moment it starts.
     */
    private fun drain(codec: MediaCodec, isVideo: Boolean, timeoutUs: Long): Boolean {
        val m = muxer ?: throw notStarted()
        while (true) {
            val info = MediaCodec.BufferInfo()
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val track = m.addTrack(codec.outputFormat)
                if (isVideo) {
                    videoTrack = track
                    videoTrackAdded = true
                } else {
                    audioTrack = track
                    audioTrackAdded = true
                }
                maybeStartMuxer(m)
                continue
            }
            if (index >= 0) {
                var eos = false
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val track = if (isVideo) videoTrack else audioTrack
                        if (muxerStarted && track >= 0) {
                            m.writeSampleData(track, buf, info)
                        } else {
                            // The other track's format has not arrived yet:
                            // hold a COPY of the bytes (the codec buffer dies
                            // at releaseOutputBuffer below) with offset 0.
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            val held = cloneInfo(info)
                            held.set(0, info.size, info.presentationTimeUs, info.flags)
                            pending.add(PendingSample(track, bytes, held))
                        }
                    }
                }
                eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (eos) return true
            }
        }
    }

    private fun maybeStartMuxer(m: MediaMuxer) {
        if (muxerStarted) return
        val audioExpected = audioCodec != null
        if (videoTrackAdded && (!audioExpected || audioTrackAdded)) {
            m.start()
            muxerStarted = true
            // Flush the packets that were produced before the second track's
            // format arrived, in arrival order (per-track pts order).
            for (p in pending) {
                if (p.track >= 0) {
                    m.writeSampleData(p.track, ByteBuffer.wrap(p.bytes), p.info)
                }
            }
            pending.clear()
        }
    }

    /** Copies a packed I420 frame into the codec's Image planes (its strides). */
    private fun copyI420ToImage(i420: ByteArray, image: Image, w: Int, h: Int) {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val yOff = 0
        val uOff = w * h
        val vOff = w * h + cw * ch

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        for (row in 0 until h) {
            yBuf.position(row * yRow)
            yBuf.put(i420, yOff + row * w, w)
        }

        copyChroma(image.planes[1], i420, uOff, cw, ch)
        copyChroma(image.planes[2], i420, vOff, cw, ch)
    }

    private fun copyChroma(
        plane: Image.Plane,
        i420: ByteArray,
        srcOffset: Int,
        cw: Int,
        ch: Int,
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until ch) {
            var src = srcOffset + row * cw
            var dst = row * rowStride
            for (col in 0 until cw) {
                buf.position(dst)
                buf.put(i420[src])
                src++
                dst += pixelStride
            }
        }
    }

    private fun cloneInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo {
        val out = MediaCodec.BufferInfo()
        out.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        return out
    }

    private fun encodeFailure(cause: String): IllegalStateException =
        IllegalStateException(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_ENCODE,
                cause,
                "retry the export (free memory and close other apps first)",
            )
        )

    private fun notStarted(): IllegalStateException =
        IllegalStateException(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_ENCODE,
                "the writer was used before start",
                "retry the export",
            )
        )

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_AAC = "audio/mp4a-latm"
        const val INPUT_TIMEOUT_US = 10_000_000L // 10 s: decode is slow, not stuck
        const val EOS_DEADLINE_NS = 10_000_000_000L // 10 s to drain after EOS
        // Owner request 2026-11-12 §4: the export diagnostics tag. Same
        // "ritim-*" style as ritim-pm/ritim-scope; the prompt's "riti-export"
        // is adapted to the app's tag convention.
        const val TAG = "ritim-export"
    }
}
