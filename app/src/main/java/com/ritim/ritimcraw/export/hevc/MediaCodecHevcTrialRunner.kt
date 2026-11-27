package com.ritim.ritimcraw.export.hevc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * THE device leg of the trial encode (G4): a REAL short MediaCodec encode
 * that answers "does this encoder actually make HLG" — the API-34
 * fallback path's one-time proof. DEVICE-GATED by nature (MediaCodec
 * stubs throw on the JVM); the decision layer above it is what the JVM
 * tests exercise with a fake, and every "passed on the real device" claim
 * this class can make is stamped NOT VERIFIED OFF-DEVICE until the owner's
 * morning run.
 *
 * THE SHAPE (the task's own scope: "deneme kodlaması MediaCodec encoder
 * yapılandır + surface'siz kısa test" — a BUFFER-path short encode, not
 * the Surface path the real export rides): configure + start + a handful
 * of P010 ramp frames queued as INPUT BUFFERS (no EGL, no Surface, no
 * window — the trial isolates the ENCODER's HLG contract, not the GPU
 * leg), signalEndOfInputStream, drain the bitstream, then RE-DECODE one
 * frame and read the OUTPUT's tags. The ramp is the 10-bit proof pattern
 * the preflight KDoc names: adjacent steps one 10-bit code apart, so an
 * 8-bit round-trip collapses them (the decode-back check).
 *
 * HONESTY RULES:
 *  - EVERY failure is a [HevcTrialEncode.Result.Refused] with the stage's
 *    own sentence; nothing throws, nothing silently passes.
 *  - The verdict reads the DECODER's output format (the profile int and
 *    the color tags the bitstream actually carries), never the request.
 *  - The frames are SYNTHETIC (the 10-bit ramp); no user data is read.
 *  - The whole trial is bounded: [FRAME_COUNT] frames, one drain deadline,
 *    everything released on every path.
 */
class MediaCodecHevcTrialRunner : HevcTrialRunner {

    override fun run(request: HevcTrialRunner.Request): HevcTrialEncode.Result {
        val format = trialFormat(request)
        val encoder = try {
            val c = if (request.encoderName != null) {
                MediaCodec.createByCodecName(request.encoderName)
            } else {
                MediaCodec.createEncoderByType(request.profile.mime)
            }
            c
        } catch (e: Exception) {
            return HevcTrialEncode.Result.Refused("codec-create: no ${request.profile.mime} encoder (${e.message ?: "no detail"})")
        }
        try {
            return encodeAndVerify(encoder, format, request)
        } finally {
            runCatching { encoder.release() }
        }
    }

    /** The trial's MediaFormat: the profile's contract at the job's shape, buffer-input. */
    private fun trialFormat(request: HevcTrialRunner.Request): MediaFormat {
        val f = MediaFormat.createVideoFormat(request.profile.mime, request.width, request.height)
        for (e in request.profile.mediaFormatEntries()) {
            when (e.key) {
                "mime" -> { /* createVideoFormat set it */ }
                else -> f.setInteger(e.key, e.value.toInt())
            }
        }
        f.setInteger(MediaFormat.KEY_BIT_RATE, request.bitrateBps)
        f.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            Math.round(request.fps).toInt().coerceAtLeast(1),
        )
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0) // all-intra: fewest frames to prove
        f.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
        )
        return f
    }

    /**
     * Configure + start + ramp + drain + decode-back. Every step's
     * failure names its stage (the SurfaceInputStep vocabulary's shape).
     */
    private fun encodeAndVerify(
        encoder: MediaCodec,
        format: MediaFormat,
        request: HevcTrialRunner.Request,
    ): HevcTrialEncode.Result {
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            return HevcTrialEncode.Result.Refused("configure: ${request.profile.mime} rejected the HLG format (${e.message ?: "no detail"})")
        }
        try {
            encoder.start()
        } catch (e: Exception) {
            return HevcTrialEncode.Result.Refused("start: the trial encoder did not start (${e.message ?: "no detail"})")
        }

        // The ramp frames as P010 (16-bit words, 10 data bits in the MSBs).
        val ramp = RampP010.rampFrame(request.width, request.height)
        val bitstream = ArrayList<Pair<ByteArray, Long>>()
        var framesIn = 0
        try {
            var pts = 0L
            while (framesIn < FRAME_COUNT) {
                val index = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US.toLong())
                if (index < 0) {
                    return HevcTrialEncode.Result.Refused("input: the trial encoder gave no input buffer within the deadline")
                }
                val buf = encoder.getInputBuffer(index) ?: return HevcTrialEncode.Result.Refused("input: the trial encoder gave no input buffer")
                buf.clear()
                buf.order(ByteOrder.nativeOrder())
                buf.put(ramp)
                encoder.queueInputBuffer(index, 0, ramp.size, pts, 0)
                framesIn++
                pts += 1_000_000L / Math.round(request.fps).coerceAtLeast(1)
                drain(encoder, bitstream, expectEos = false)
            }
            val eos = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US.toLong())
            if (eos >= 0) {
                encoder.queueInputBuffer(eos, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val drainedEos = drainUntilEos(encoder, bitstream)
            if (!drainedEos) {
                return HevcTrialEncode.Result.Refused("drain: the trial encode did not finish within the deadline")
            }
        } catch (e: IllegalStateException) {
            return HevcTrialEncode.Result.Refused("encode: the trial encode failed mid-stream (${e.message ?: "no detail"})")
        }
        if (bitstream.isEmpty()) {
            return HevcTrialEncode.Result.Refused("drain: the trial encode produced no bitstream")
        }
        return decodeBackVerify(bitstream, request)
    }

    /** Drains whatever is ready into [out]; true on EOS. */
    private fun drain(encoder: MediaCodec, out: ArrayList<Pair<ByteArray, Long>>, expectEos: Boolean): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!expectEos) return false
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            }
            if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = encoder.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        out.add(bytes to info.presentationTimeUs)
                    }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (eos) return true
            }
        }
    }

    private fun drainUntilEos(encoder: MediaCodec, out: ArrayList<Pair<ByteArray, Long>>): Boolean {
        val deadline = System.nanoTime() + DRAIN_DEADLINE_NS
        val info = MediaCodec.BufferInfo()
        while (System.nanoTime() < deadline) {
            val index = encoder.dequeueOutputBuffer(info, 1_000_000L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = encoder.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        out.add(bytes to info.presentationTimeUs)
                    }
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(index, false)
                if (eos) return true
            }
        }
        return false
    }

    /**
     * The decode-back: the OUTPUT's own tags decide. The decoder's output
     * format carries the profile int; the (API-24+) output format carries
     * the color-standard/range/transfer the BITSTREAM's VUI held — a
     * decoder that hands back SDR tags (BT709/170m) means the encoder
     * dropped the HLG contract, and the trial refuses honestly.
     */
    private fun decodeBackVerify(
        bitstream: ArrayList<Pair<ByteArray, Long>>,
        request: HevcTrialRunner.Request,
    ): HevcTrialEncode.Result {
        val decoder = try {
            MediaCodec.createDecoderByType(request.profile.mime)
        } catch (e: Exception) {
            // No decoder: the trial cannot PROVE HLG, and an unproven
            // gate must not silently pass — refuse with the reason.
            return HevcTrialEncode.Result.Refused("decode: no ${request.profile.mime} decoder to verify the trial output (${e.message ?: "no detail"})")
        }
        try {
            try {
                decoder.configure(
                    MediaFormat.createVideoFormat(request.profile.mime, request.width, request.height),
                    null,
                    null,
                    0,
                )
                decoder.start()
            } catch (e: Exception) {
                return HevcTrialEncode.Result.Refused("decode: the trial decoder did not start (${e.message ?: "no detail"})")
            }
            // Feed the bitstream; capture the OUTPUT format (the tags).
            var queued = 0
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var decoded = 0
            outer@ while (decoded < FRAME_COUNT && queued < bitstream.size) {
                val inIndex = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US.toLong())
                if (inIndex >= 0) {
                    val (bytes, pts) = bitstream[queued]
                    val buf = decoder.getInputBuffer(inIndex)
                    if (buf != null) {
                        buf.clear()
                        buf.put(bytes)
                        decoder.queueInputBuffer(
                            inIndex, 0, bytes.size, pts,
                            if (queued == 0) MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0,
                        )
                    }
                    queued++
                }
                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = decoder.outputFormat
                        continue
                    }
                    if (outIndex >= 0) {
                        decoder.releaseOutputBuffer(outIndex, false)
                        decoded++
                    }
                }
                if (outFormat != null && decoded > 0) break@outer
            }
            // Drain once more for the format if the loop exited early.
            while (outFormat == null) {
                val outIndex = decoder.dequeueOutputBuffer(info, 1_000_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = decoder.outputFormat
                    continue
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex >= 0) decoder.releaseOutputBuffer(outIndex, false)
            }
            val f = outFormat
                ?: return HevcTrialEncode.Result.Refused("decode: the trial output format never arrived")
            // The OUTPUT's tags, read as the decoder reported them.
            val tags = ColorTags.of(f)
            val profileOut = runCatching { f.getInteger("profile") }.getOrDefault(-1)
            Log.i(
                TAG,
                "hlg trial decode-back: profile=$profileOut standard=${tags.standard} range=${tags.range} transfer=${tags.transfer}",
            )
            if (tags.transfer != request.profile.colorTransfer) {
                return HevcTrialEncode.Result.Refused(
                    "decode: the bitstream's transfer is ${tags.transfer}, not the requested ${request.profile.colorTransfer} " +
                        "(the encoder dropped the HLG contract)",
                )
            }
            if (tags.standard != request.profile.colorStandard) {
                return HevcTrialEncode.Result.Refused(
                    "decode: the bitstream's color standard is ${tags.standard}, not the requested ${request.profile.colorStandard}",
                )
            }
            return HevcTrialEncode.Result.Passed
        } finally {
            runCatching { decoder.release() }
        }
    }

    /** The decode-back's read of a MediaFormat's color fields (absent = -1). */
    data class ColorTags(val standard: Int, val range: Int, val transfer: Int) {
        companion object {
            fun of(f: MediaFormat): ColorTags = ColorTags(
                runCatching { f.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrDefault(-1),
                runCatching { f.getInteger(MediaFormat.KEY_COLOR_RANGE) }.getOrDefault(-1),
                runCatching { f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(-1),
            )
        }
    }

    private companion object {
        const val TAG = "ritim-export"
        const val FRAME_COUNT = 4
        const val INPUT_TIMEOUT_US = 500_000
        const val DRAIN_DEADLINE_NS = 2_000_000_000L
    }
}

/** The SYNTHETIC 10-bit ramp as one P010 frame (no user data is read). */
internal object RampP010 {
    /**
     * One P010 frame of the horizontal ramp: [width] luminance steps one
     * 10-bit code apart (the banding-free pattern), the chroma planes at
     * the neutral midpoint. 16-bit words, the 10 data bits in the MSBs
     * (the ImageFormat.YCBCR_P010 packing the preflight KDoc names).
     */
    fun rampFrame(width: Int, height: Int): ByteArray {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val luma = ShortArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // The 10-bit code 64..940 (limited range), ramped by x.
                val code = (64 + (876 * x) / width.coerceAtLeast(1)).coerceIn(64, 940)
                luma[y * width + x] = (code shl 6).toShort()
            }
        }
        val chroma = ShortArray(cw * 2 * ch) // interleaved U,V rows
        val neutral = (512 shl 6).toShort()
        java.util.Arrays.fill(chroma, neutral)
        val out = ByteArray((luma.size + chroma.size) * 2)
        val buf = java.nio.ByteBuffer.wrap(out).order(ByteOrder.nativeOrder())
        for (s in luma) buf.putShort(s)
        for (s in chroma) buf.putShort(s)
        return out
    }
}
