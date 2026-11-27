package com.ritim.ritimcraw.proxy

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.ritim.ritimcraw.engine.EncodedColorSpec
import com.ritim.ritimcraw.engine.EncodedOpenOutcome
import com.ritim.ritimcraw.engine.EncodedErrors
import com.ritim.ritimcraw.engine.EncodedVideoFormat
import com.ritim.ritimcraw.engine.MediaExtractorTrackReader
import com.ritim.ritimcraw.engine.MediaCodecFrameDecoder
import com.ritim.ritimcraw.engine.YuvPlanes
import com.ritim.ritimcraw.export.imageInputSize

/**
 * The real [ProxyEncoder] for ENCODED sources (G3, docs/PROXY_PLAN.md sections
 * 2.1 + 3.2): MediaExtractor + MediaCodec decode of the source, a box-average
 * downscale to the proxy size ([ProxyDownscale]'s own geometry), and a
 * MediaCodec HEVC encode into a MediaMuxer MP4 at the handle's `.tmp` path.
 * Every decision already lives in the JVM-tested layer ([ProxyBuildSpec],
 * [ProxyDownscale], [ProxyBitrate]); this class only EXECUTES it — the
 * thin-adapter contract (MediaCodec/MediaMuxer cannot run in JVM unit tests,
 * so no testable logic may live here).
 *
 * The encode contract, executed from the pure spec:
 *  - HEVC ("video/hevc", [ProxySettings.codec]); the honest AVC fallback when
 *    this device has no HEVC encoder (plan section 2.1) — the reason line
 *    names it, and the SIGNATURE stays the caller's business (a build never
 *    silently swaps its codec: this build either runs HEVC or fails with the
 *    reason the queue records; the AVC fallback is the NEXT build's settings,
 *    never this one's);
 *  - KEY_I_FRAME_INTERVAL = the settings' own 1 s; B-frames: MediaFormat has
 *    no key for them (the C2 HEVC encoders default to none — the plan's
 *    "B-kare kapalı"); the real GOP the device emits is a device question
 *    ("doğrulanmadı");
 *  - bitrate: [ProxyBitrate.bps] at the source's own measured fps (from the
 *    sample index's pts span — the same measurement the metadata pass uses);
 *  - PTS: every frame's own presentation timestamp, birebir (plan section 2.1
 *    "Fps" row — the proxy's frame N is the source's frame N);
 *  - audio: never (plan section 1 — the proxy plays no sound of its own);
 *  - color: the decoded source's own [EncodedColorSpec] tags, passed through
 *    unchanged when the container carried them ([MediaExtractorTrackReader]'s
 *    format keeps the raw keys); a clip with no tags (Mi-Log's untagged
 *    transfer) gets none written — the truth rule, never an invented tag;
 *  - bit depth: this first device leg writes 8-bit planes (the
 *    [MediaCodecFrameDecoder]'s delivered depth); the 10-bit Main10 input is
 *    a device question reported as "doğrulanmadı" until the owner's device
 *    run confirms the encoder takes 10-bit Image input at this size.
 *
 * DEVICE-DEPENDENT (all "doğrulanmadı" in the report): the encoder chosen for
 * the mime, Main10 support, the real GOP, the encode speed, and the color
 * fidelity of the decode-downscale-encode round trip.
 */
internal class ProxyMediaCodecEncoder(
    /**
     * A FRESH read-only descriptor of the source clip [spec] names, opened per
     * build (the per-open fd discipline; the reader adopts and closes it).
     * A negative return is an honest open failure.
     */
    private val openFd: (spec: ProxyBuildSpec) -> Int,
) : ProxyEncoder {

    override fun run(
        spec: ProxyBuildSpec,
        handle: ProxyWriteHandle,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
        pacer: () -> Unit,
    ): ProxyEncodeResult {
        val reader = MediaExtractorTrackReader()
        val decoder = MediaCodecFrameDecoder()
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        try {
            val fd = openFd(spec)
            if (fd < 0) return ProxyEncodeResult.Failed("the clip could not be opened (descriptor refused)")
            val opened = reader.open(fd)
            if (opened is EncodedOpenOutcome.Failed) {
                return ProxyEncodeResult.Failed(EncodedErrors.openFailure(opened.kind, opened.detail))
            }
            val info = (opened as EncodedOpenOutcome.Opened)
            val source = info.format
            val frames = info.index.frameCount.toLong()
            if (frames <= 0L) return ProxyEncodeResult.Failed("the clip holds no video frames")

            // the pure geometry + rate decisions
            val (outW, outH) = ProxyDownscale.outputSize(source.width, source.height, spec.settings.shortSide)
            val fps = measuredFps(info.index.ptsUs)
            val bits = ProxyBitrate.bps(fps)

            muxer = MediaMuxer(handle.videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            encoder = createEncoder(spec, outW, outH, fps, bits, source)
            val enc = encoder

            var track = -1
            var muxerStarted = false
            var written = 0L
            val drainInfo = MediaCodec.BufferInfo()

            // Drains the encoder; true when its EOS buffer was seen.
            fun drain(): Boolean {
                while (true) {
                    val index = enc.dequeueOutputBuffer(drainInfo, DRAIN_TIMEOUT_US)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (index >= 0) {
                        val buf = enc.getOutputBuffer(index)
                        if (buf != null && drainInfo.size > 0 &&
                            drainInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted
                        ) {
                            buf.position(drainInfo.offset)
                            buf.limit(drainInfo.offset + drainInfo.size)
                            muxer.writeSampleData(track, buf, drainInfo)
                        }
                        val eos = drainInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(index, false)
                        if (eos) return true
                    }
                }
            }

            // the decode loop: the reader opens at end of stream -> seek to 0
            reader.seekTo(0L)
            var eosQueued = false
            var decoderDry = 0
            while (true) {
                if (isCancelled()) return ProxyEncodeResult.Cancelled
                if (!eosQueued) {
                    val sample = try {
                        reader.readSample()
                    } catch (e: Exception) {
                        return ProxyEncodeResult.Failed("the clip could not be read: ${e.message}")
                    }
                    if (sample == null) {
                        try { decoder.queueEos() } catch (e: Exception) {
                            return ProxyEncodeResult.Failed("the decoder rejected the end of stream: ${e.message}")
                        }
                        eosQueued = true
                    } else {
                        try { decoder.queueSample(sample) } catch (e: Exception) {
                            return ProxyEncodeResult.Failed("the decoder rejected a frame: ${e.message}")
                        }
                    }
                }
                // drain every frame the decoder has ready
                var sawFrame = false
                while (true) {
                    val planes = try {
                        decoder.dequeueFrame(if (eosQueued) EOS_POLL_US else 0L)
                    } catch (e: Exception) {
                        return ProxyEncodeResult.Failed("the decoder failed: ${e.message}")
                    }
                    if (planes == null) break
                    if (planes.width == 0) continue // a no-copy skip: pts only
                    feed(enc, planes, outW, outH)
                    written++
                    onProgress(written, frames)
                    sawFrame = true
                    pacer()
                    if (isCancelled()) return ProxyEncodeResult.Cancelled
                    if (drain()) return finish(muxer, written, frames)
                }
                if (sawFrame) decoderDry = 0 else decoderDry++
                if (eosQueued) {
                    // the decoder is drained: end the encoder and drain it out
                    enc.signalEndOfInputStream()
                    var guard = 0
                    while (true) {
                        if (drain()) return finish(muxer, written, frames)
                        if (++guard > EOS_GUARD_POLLS) {
                            return ProxyEncodeResult.Failed("the proxy encoder did not finish within the deadline")
                        }
                    }
                }
                if (decoderDry > STUCK_POLLS) {
                    return ProxyEncodeResult.Failed("the proxy decode made no progress")
                }
            }
        } catch (e: IllegalStateException) {
            return ProxyEncodeResult.Failed(e.message ?: "the proxy encode failed")
        } catch (e: Exception) {
            return ProxyEncodeResult.Failed("the proxy encode failed: ${e.message}")
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { decoder.release() }
            runCatching { reader.release() }
        }
    }

    private fun finish(muxer: MediaMuxer, written: Long, frames: Long): ProxyEncodeResult {
        if (written != frames) {
            return ProxyEncodeResult.Failed("the encode ended after $written of $frames frames")
        }
        return try {
            muxer.stop()
            ProxyEncodeResult.Ok
        } catch (e: Exception) {
            ProxyEncodeResult.Failed("the muxer could not finish: ${e.message}")
        }
    }

    // ---- the encoder bring-up ----

    private fun createEncoder(
        spec: ProxyBuildSpec,
        w: Int,
        h: Int,
        fps: Double,
        bits: Int,
        source: EncodedVideoFormat,
    ): MediaCodec {
        val mime = spec.settings.codec
        val f = MediaFormat.createVideoFormat(mime, w, h)
        f.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        f.setInteger(MediaFormat.KEY_BIT_RATE, bits)
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        // a whole fps is an Int (the app's own convention, ExportFormats.frameRateValue),
        // a fractional one a Float — MediaFormat accepts both
        val r = Math.round(fps)
        if (kotlin.math.abs(fps - r) < 1e-6) {
            f.setInteger(MediaFormat.KEY_FRAME_RATE, r.toInt())
        } else {
            f.setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
        }
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.settings.keyFrameIntervalSec)
        // the SOURCE's own color tags, unchanged (the truth rule): the reader
        // keeps the container's raw COLOR_* keys; a clip without tags writes none
        source.rawColor?.let { raw ->
            raw.standard?.let { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
            raw.range?.let { f.setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
            raw.transfer?.let { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
        }
        val codec = try {
            MediaCodec.createEncoderByType(mime)
        } catch (e: Exception) {
            throw IllegalStateException("no $mime encoder is available on this device")
        }
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw IllegalStateException("$mime rejected the ${w}x$h proxy format: ${e.message}")
        }
        return codec
    }

    /** One downscaled frame into the encoder's input Image (its own strides). */
    private fun feed(encoder: MediaCodec, p: YuvPlanes, outW: Int, outH: Int) {
        val index = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) throw IllegalStateException("the proxy encoder gave no input buffer")
        val image = encoder.getInputImage(index)
            ?: throw IllegalStateException("the proxy encoder gave no input Image")
        copyScaled(p, image, outW, outH)
        val size = imageInputSize(
            width = outW,
            height = outH,
            yRowStride = image.planes[0].rowStride,
            uRowStride = image.planes[1].rowStride,
            uPixelStride = image.planes[1].pixelStride,
            vRowStride = image.planes[2].rowStride,
            vPixelStride = image.planes[2].pixelStride,
        )
        encoder.queueInputBuffer(index, 0, size, p.ptsUs, 0)
    }

    /**
     * Box-average downscale of the decoded 8-bit planes into the encoder
     * Image's planes — the [com.ritim.ritimcraw.engine.ArgbScale] box rule,
     * in YUV: each output sample is the mean of its source box. 10-bit
     * decoded planes are not taken by this first device leg (see the class
     * doc: the Main10 input is the morning's device question).
     */
    private fun copyScaled(p: YuvPlanes, image: Image, outW: Int, outH: Int) {
        val w = p.width
        val h = p.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        for (oy in 0 until outH) {
            val y0 = oy * h / outH
            val y1 = maxOf(y0 + 1, (oy + 1) * h / outH)
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            for (ox in 0 until outW) {
                val x0 = ox * w / outW
                val x1 = maxOf(x0 + 1, (ox + 1) * w / outW)
                var sum = 0L
                var n = 0L
                for (y in y0 until y1) {
                    val row = y * p.y.rowStride + p.y.offset
                    for (x in x0 until x1) {
                        sum += p.y.buffer[row + x * p.y.pixelStride].toLong() and 0xFF
                        n++
                    }
                }
                yPlane.buffer.put(oy * yRow + ox * yPix, (sum / n).toInt().toByte())
            }
        }
        val cw = (outW + 1) / 2
        val ch = (outH + 1) / 2
        val scw = (w + 1) / 2
        val sch = (h + 1) / 2
        for (oy in 0 until ch) {
            val y0 = oy * sch / ch
            val y1 = maxOf(y0 + 1, (oy + 1) * sch / ch)
            for (ox in 0 until cw) {
                val x0 = ox * scw / cw
                val x1 = maxOf(x0 + 1, (ox + 1) * scw / cw)
                var su = 0L
                var sv = 0L
                var n = 0L
                for (y in y0 until y1) {
                    val rowU = y * p.u.rowStride + p.u.offset
                    val rowV = y * p.v.rowStride + p.v.offset
                    for (x in x0 until x1) {
                        su += p.u.buffer[rowU + x * p.u.pixelStride].toLong() and 0xFF
                        sv += p.v.buffer[rowV + x * p.v.pixelStride].toLong() and 0xFF
                        n++
                    }
                }
                uPlane.buffer.put(oy * uPlane.rowStride + ox * uPlane.pixelStride, (su / n).toInt().toByte())
                vPlane.buffer.put(oy * vPlane.rowStride + ox * vPlane.pixelStride, (sv / n).toInt().toByte())
            }
        }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000_000L
        const val DRAIN_TIMEOUT_US = 0L
        const val EOS_POLL_US = 50_000L
        const val EOS_GUARD_POLLS = 400
        const val STUCK_POLLS = 128
    }
}

/**
 * The pts span's own frame rate — the metadata pass's measurement (frameCount-1
 * over the first..last pts span), so the proxy's bitrate ask follows the clip's
 * REAL rate, never a container claim. A degenerate span falls back to 30.
 */
internal fun measuredFps(ptsUs: LongArray): Double {
    if (ptsUs.size < 2) return 30.0
    val span = ptsUs.last() - ptsUs.first()
    if (span <= 0L) return 30.0
    return (ptsUs.size - 1) * 1_000_000.0 / span
}
