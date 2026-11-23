package com.ritim.ritimcraw.export

import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * The encoder format of one export as plain data (plan 7.5, package E1b).
 * [values] maps a MediaFormat key to its Int or Float; the writer copies it
 * into a real MediaFormat ([MediaCodecMp4Writer]). Keeping the decision as
 * a Map built by [ExportFormats] makes "which key did the choice set" a
 * JVM test: MediaFormat itself cannot run off-device (framework stubs
 * throw), but the key names and MediaCodecInfo ints are compile-time
 * constants, so they inline and this file runs on the JVM.
 */
data class FormatSpec(
    val mime: String,
    /** Video: the encode size. Audio: 0 (the rate/channels are in [values]). */
    val width: Int,
    val height: Int,
    val values: Map<String, Number>,
) {
    fun int(key: String): Int? = values[key] as? Int
    fun float(key: String): Float? = values[key] as? Float
}

object ExportFormats {

    /**
     * The video MediaFormat for [plan]. [surface] true = COLOR_FormatSurface
     * (frames arrive through the encoder's input Surface), false = the
     * YUV420 flexible Image API.
     *
     * A legacy plan (a job queued before E1, [ExportPlan.isLegacy]) emits
     * exactly the keys the writer always set (no KEY_BITRATE_MODE), so old
     * jobs produce the same encoder configuration as before.
     *
     * Colors: SDR is BT.709 limited range with the SDR transfer (the
     * YuvConverter's matrix). HLG/HDR10/DV are not selectable yet; the
     * planner resolves them to SDR, so this builder only ever sees SDR.
     */
    fun video(plan: ExportPlan, surface: Boolean): FormatSpec {
        // 10-bit frames exist only on the GPU encoder-surface path: the CPU
        // develop output is 8-bit RGB888, so a 10-bit CPU format would be a lie.
        require(!(plan.bitDepth == 10 && !surface)) { "10-bit needs the encoder-Surface input" }
        val v = LinkedHashMap<String, Number>()
        v[MediaFormat.KEY_COLOR_FORMAT] = if (surface) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        } else {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
        v[MediaFormat.KEY_BIT_RATE] = plan.bitrateBps
        if (!plan.isLegacy) {
            v[MediaFormat.KEY_BITRATE_MODE] = plan.effective.rateMode.mediaCodecMode
            if (plan.effective.rateMode == ExportRateMode.CQ) v[MediaFormat.KEY_QUALITY] = CQ_QUALITY
        }
        v[MediaFormat.KEY_FRAME_RATE] = frameRateValue(plan.fps)
        v[MediaFormat.KEY_I_FRAME_INTERVAL] = keyframeValue(plan.keyframeIntervalSec)
        plan.profile?.let { v[MediaFormat.KEY_PROFILE] = it }
        v[MediaFormat.KEY_COLOR_STANDARD] = MediaFormat.COLOR_STANDARD_BT709
        v[MediaFormat.KEY_COLOR_RANGE] = MediaFormat.COLOR_RANGE_LIMITED
        v[MediaFormat.KEY_COLOR_TRANSFER] = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        return FormatSpec(plan.mime, plan.width, plan.height, v)
    }

    /** The AAC-LC format: the clip's own rate/channels, the bitrate from the setting. */
    fun audio(sampleRate: Int, channels: Int, kbps: Int): FormatSpec =
        FormatSpec(
            MIME_AAC, 0, 0,
            linkedMapOf(
                MediaFormat.KEY_SAMPLE_RATE to sampleRate,
                MediaFormat.KEY_CHANNEL_COUNT to channels,
                MediaFormat.KEY_AAC_PROFILE to MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                MediaFormat.KEY_BIT_RATE to kbps * 1000,
                MediaFormat.KEY_MAX_INPUT_SIZE to 64 * 1024,
            ),
        )

    /** A whole frame rate is an Int (what the app always set), a fractional one a Float. */
    fun frameRateValue(fps: Double): Number {
        val r = Math.round(fps)
        return if (kotlin.math.abs(fps - r) < 1e-6) r.toInt() else fps.toFloat()
    }

    /** KEY_I_FRAME_INTERVAL: 0 = all-intra, a whole second count is an Int, a fraction a Float. */
    fun keyframeValue(sec: Float): Number =
        if (sec == Math.floor(sec.toDouble()).toFloat()) sec.toInt() else sec

    const val MIME_AAC = "audio/mp4a-latm"

    /** Only a CQ encoder gets it; E1c does not offer CQ, so this is the documented default. */
    private const val CQ_QUALITY = 80
}
