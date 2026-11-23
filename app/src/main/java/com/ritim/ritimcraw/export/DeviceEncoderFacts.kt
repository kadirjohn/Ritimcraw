package com.ritim.ritimcraw.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.ritim.ritimcraw.engine.CodecProbe

/**
 * The thin device layer under the export plan (E1b): fills [EncoderFacts]
 * LIVE from MediaCodecList - the report half from [CodecProbe] (profiles,
 * color formats, rate modes, bitrate ceiling, max size) plus what only the
 * live codec can answer: a [SizeRateProbe] over
 * VideoCapabilities.areSizeAndRateSupported, the supported frame-rate
 * range, and the HDR/HLG editing features. Device-gated (MediaCodecList is
 * not on the JVM); every decision on top of it is JVM-tested in
 * ExportPlanner. Cached per process: the codec list does not change.
 */
object DeviceEncoderFacts {

    private val cached: List<EncoderFacts> by lazy { load() }

    /** Video encoders for the export MIMEs; empty when the probe fails (the plan then names no encoder). */
    fun all(): List<EncoderFacts> = cached

    private fun load(): List<EncoderFacts> = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val byName = list.codecInfos.filter { it.isEncoder }.associateBy { it.name }
        val wanted = ExportCodec.entries.map { it.mime }.toSet()
        CodecProbe.probe()
            .filter { it.isEncoder == true && it.mime in wanted }
            .mapNotNull { r ->
                val info = byName[r.name] ?: return@mapNotNull null
                val caps = runCatching { info.getCapabilitiesForType(r.mime) }.getOrNull()
                val vc = caps?.videoCapabilities
                val probe = vc?.let { v ->
                    SizeRateProbe { w, h, fps ->
                        // The orientation does not matter to an encoder; some report a
                        // landscape-only box. A rate the encoder does not list at a size
                        // it does make is NOT a refusal here: KEY_FRAME_RATE is a hint and
                        // the plan reports it as RATE_UNLISTED (info), as the legacy
                        // export always encoded such clips at a stated 30.
                        runCatching {
                            v.areSizeAndRateSupported(w, h, fps) || v.areSizeAndRateSupported(h, w, fps) ||
                                v.isSizeSupported(w, h) || v.isSizeSupported(h, w)
                        }.getOrDefault(false)
                    }
                }
                val feats = FEATURES.filter { f -> runCatching { caps?.isFeatureSupported(f) == true }.getOrDefault(false) }
                    .toSet()
                EncoderFacts.fromReport(
                    r,
                    sizeRate = probe,
                    supportedFps = vc?.supportedFrameRates?.let { it.lower.toDouble()..it.upper.toDouble() },
                    features = feats,
                )
            }
    }.getOrDefault(emptyList())

    private val FEATURES = listOf(
        MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing,
        "hlg-editing",
    )
}
