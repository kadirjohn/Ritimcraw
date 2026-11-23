package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.CodecReport

/** yes / no / the device does not say. */
enum class Support { YES, NO, UNKNOWN }

/** Asks the live encoder: does it accept this size at this rate (VideoCapabilities.areSizeAndRateSupported)? */
fun interface SizeRateProbe {
    fun supports(width: Int, height: Int, fps: Double): Boolean
}

/**
 * What the export plan knows about ONE video encoder, by name (plan 7.5).
 * A plain class, not a data class: [sizeRate] is a live probe. Built from
 * the CodecProbe report plus the thin device layer ([fromReport]); tests
 * build it directly.
 *
 * Empty sets mean "the device did not say" (never "no").
 */
class EncoderFacts(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val profiles: Set<Int> = emptySet(),
    val colorFormats: Set<Int> = emptySet(),
    /** MediaCodecInfo.EncoderCapabilities BITRATE_MODE_* values the encoder supports. */
    val bitrateModes: Set<Int> = emptySet(),
    val bitrateRangeBps: IntRange? = null,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val supportedFps: ClosedFloatingPointRange<Double>? = null,
    val features: Set<String> = emptySet(),
    val sizeRate: SizeRateProbe? = null,
) {
    /** Which job an encoder is for: the general video encoder, a CQ-only still/thumbnail one, or the HDR-editing variant. */
    enum class Role { GENERAL, CQ_ONLY, HDR_VARIANT }

    /**
     * Told apart by name AND capability (owner 2026-11-23): the 15 Ultra's
     * `c2.qti.hevc.encoder.cq` is CQ at up to 512x512 (thumbnails) and
     * `.hdr` is the HDR-editing variant limited to 4096x2176; neither is
     * the general SDR video encoder.
     */
    val role: Role
        get() = when {
            name.endsWith(".cq", ignoreCase = true) || bitrateModes == setOf(ExportRateMode.CQ.mediaCodecMode) -> Role.CQ_ONLY
            name.endsWith(".hdr", ignoreCase = true) -> Role.HDR_VARIANT
            else -> Role.GENERAL
        }

    val isCodec2: Boolean get() = name.startsWith("c2.", ignoreCase = true)

    /** The probe's verdict when there is one, else the listed maximum (either orientation), else UNKNOWN. */
    fun sizeSupport(width: Int, height: Int, fps: Double): Support {
        sizeRate?.let { return if (it.supports(width, height, fps)) Support.YES else Support.NO }
        if (maxWidth > 0 && maxHeight > 0) {
            val fits = (width <= maxWidth && height <= maxHeight) || (width <= maxHeight && height <= maxWidth)
            return if (fits) Support.YES else Support.NO
        }
        return Support.UNKNOWN
    }

    fun supportsRateMode(mode: ExportRateMode): Boolean =
        bitrateModes.isEmpty() || mode.mediaCodecMode in bitrateModes

    /** 10-bit input: P010 or RGBA1010102 listed (unknown = assumed possible; the trial encode decides). */
    fun acceptsTenBitInput(): Boolean =
        colorFormats.isEmpty() || P010 in colorFormats || RGBA1010102 in colorFormats

    fun supportsTenBitProfile(mime: String): Boolean =
        profiles.isEmpty() || tenBitProfile(mime)?.let { it in profiles } == true

    companion object {
        const val P010 = 54
        const val RGBA1010102 = 0x7F00AAA2

        /** HEVC Main10 = 2, AVC High10 = 16 (MediaCodecInfo.CodecProfileLevel). */
        fun tenBitProfile(mime: String): Int? = when (mime) {
            "video/hevc" -> 2
            "video/avc" -> 16
            else -> null
        }

        /**
         * The report half (CodecProbe, no live probe): the device layer adds
         * [sizeRate], supportedFps and features. Reports of decoders are rejected.
         */
        fun fromReport(r: CodecReport, sizeRate: SizeRateProbe? = null, supportedFps: ClosedFloatingPointRange<Double>? = null, features: Set<String> = emptySet()): EncoderFacts? {
            if (r.isEncoder == false) return null
            val (w, h) = r.maxSize.split('x').let { p ->
                (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            return EncoderFacts(
                name = r.name,
                mime = r.mime,
                hardware = r.hardware,
                profiles = r.profileLevels.map { it.profile }.toSet(),
                colorFormats = r.colorFormats.toSet(),
                bitrateModes = r.bitrateModes.toSet(),
                bitrateRangeBps = r.bitrateUpper?.takeIf { it > 0 }?.let { 1..it },
                maxWidth = w,
                maxHeight = h,
                supportedFps = supportedFps,
                features = features,
                sizeRate = sizeRate,
            )
        }
    }
}
