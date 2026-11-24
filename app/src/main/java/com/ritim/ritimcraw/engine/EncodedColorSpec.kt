package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.color.ProfileProvenance
import com.ritim.ritimcraw.data.color.SourceMatrix
import com.ritim.ritimcraw.data.color.SourcePrimaries
import com.ritim.ritimcraw.data.color.SourceProfile
import com.ritim.ritimcraw.data.color.SourceRange
import com.ritim.ritimcraw.data.color.SourceTransfer

/**
 * What an encoded clip's pixels ARE, as one value carried from the container
 * to the develop (E0): bit depth, YCbCr range, YCbCr matrix, transfer and
 * primaries. PREVIEW and EXPORT both get it from [fromMediaFormat] (one
 * mapping, one place), so the two can never disagree about a clip - which
 * was the pre-E0 bug: the develop assumed 8-bit limited BT.601/709 for every
 * clip, while the Mi-Log fixture is HEVC Main10 / P010 / FULL range / BT.2020
 * NCL / transfer 254.
 *
 * Pure (no Android imports): MediaFormat's integer constants are passed in
 * by the thin adapter (MediaExtractorTrackReader) and named below, so the
 * whole mapping is JVM-tested. It is built ON the B1 [SourceProfile] model
 * (docs/COLOR_MODEL_V2_PLAN.md section 8): [profile] is the source truth,
 * the native ints below are its projection into core's vocabulary.
 *
 * Decisions (each pinned by a test):
 *  - range: COLOR_RANGE_FULL -> Full; LIMITED or untagged -> Limited (the
 *    H.264/HEVC convention).
 *  - matrix: BT2020 -> BT2020 NCL; BT601_PAL/NTSC -> BT601; BT709 or
 *    untagged -> BT709 (the pre-E0 default).
 *  - primaries: BT2020 -> BT2020 (converted to the Rec.709 working space in
 *    linear light by core); everything else is already the working space
 *    (BT.601/SMPTE-C primaries are approximated by 709 - core has no such
 *    gamut and the difference is small; stated, not hidden).
 *  - transfer: SDR_VIDEO -> Rec.709 OETF; LINEAR -> Linear; ST2084/HLG ->
 *    a named HDR transfer that core cannot decode yet, approximated by the
 *    Rec.709 inverse and REPORTED in [approximations]; UNSPECIFIED or absent
 *    -> Mi-Log, the app's encoded default (the pre-E0 behavior for every
 *    clip). Android's MediaFormat has no constant for the Mi-Log code (254
 *    in the HEVC VUI) and reports it as unspecified, so "untagged" is the
 *    only place Mi-Log can come from; its provenance stays UNKNOWN (null).
 *  - bit depth: the DECODED depth wins ([withDecodedBitDepth]); the
 *    container/profile only says what to ask the decoder for
 *    ([declaredBitDepth]).
 */
data class EncodedColorSpec(
    val profile: SourceProfile,
    /** What the decoder is asked for: 8 or 10 (from the track profile). */
    val declaredBitDepth: Int,
    /** Honest notes where the pipeline approximates (HDR transfer, 601 primaries...). */
    val approximations: List<String> = emptyList(),
) {
    val bitDepth: Int get() = profile.bitDepth
    val fullRange: Boolean get() = profile.range == SourceRange.Full

    /** core YuvMatrix ordinal: 0 BT601, 1 BT709, 2 BT2020. */
    val nativeMatrix: Int
        get() = when (profile.matrix) {
            SourceMatrix.BT601 -> 0
            SourceMatrix.BT709 -> 1
            SourceMatrix.BT2020 -> 2
        }

    /** core EncodedTransfer ordinal: 0 MiLog, 1 Rec709, 2 Srgb, 3 Linear. */
    val nativeTransfer: Int
        get() = when (profile.transfer) {
            SourceTransfer.MiLog -> 0
            SourceTransfer.Rec709OETF -> 1
            SourceTransfer.Srgb -> 2
            SourceTransfer.Linear -> 3
            // HDR curves: approximated (see [approximations]).
            SourceTransfer.Hlg, SourceTransfer.Pq -> 1
        }

    /** core EncodedPrimaries ordinal: 0 BT709 (the working space), 1 BT2020. */
    val nativePrimaries: Int
        get() = if (profile.primaries == SourcePrimaries.BT2020) 1 else 0

    /** The same spec with the depth the decoder ACTUALLY produced (8 or 10). */
    fun withDecodedBitDepth(bits: Int): EncodedColorSpec {
        val depth = if (bits > 8) 10 else 8
        if (depth == profile.bitDepth) return this
        val extra = if (depth < declaredBitDepth) {
            approximations + "decoder returned ${depth}-bit for a ${declaredBitDepth}-bit track"
        } else {
            approximations
        }
        return copy(
            profile = profile.copy(bitDepth = depth, bitDepthProvenance = ProfileProvenance.Probed),
            approximations = extra,
        )
    }

    /** One log/report line: `10-bit full BT2020 MiLog primaries=BT2020`. */
    fun describe(): String = buildString {
        append(profile.bitDepth).append("-bit ")
        append(if (fullRange) "full" else "limited").append(' ')
        append(profile.matrix.name).append(' ')
        append(profile.transfer.name).append(" primaries=").append(profile.primaries.name)
        if (approximations.isNotEmpty()) append(" approx=").append(approximations.joinToString("|"))
    }

    companion object {
        // MediaFormat constants (android.media.MediaFormat), by value so the
        // mapping stays free of Android imports.
        const val COLOR_RANGE_FULL = 1
        const val COLOR_RANGE_LIMITED = 2
        const val COLOR_STANDARD_BT709 = 1
        const val COLOR_STANDARD_BT601_PAL = 2
        const val COLOR_STANDARD_BT601_NTSC = 4
        const val COLOR_STANDARD_BT2020 = 6
        const val COLOR_TRANSFER_LINEAR = 1
        const val COLOR_TRANSFER_SDR_VIDEO = 3
        const val COLOR_TRANSFER_ST2084 = 6
        const val COLOR_TRANSFER_HLG = 7

        // MediaCodecInfo.CodecProfileLevel 10-bit profiles, by value.
        private const val HEVC_MAIN10 = 0x2
        private const val HEVC_MAIN10_HDR10 = 0x1000
        private const val HEVC_MAIN10_HDR10_PLUS = 0x2000
        private const val AVC_HIGH10 = 0x10
        private const val AV1_MAIN10 = 0x2
        private const val AV1_MAIN10_HDR10 = 0x1000
        private const val AV1_MAIN10_HDR10_PLUS = 0x2000
        private const val VP9_PROFILE2 = 0x4
        private const val VP9_PROFILE2_HDR = 0x1000
        private const val VP9_PROFILE2_HDR10_PLUS = 0x2000

        /** The depth the track's codec profile asks for (8 when no profile is declared). */
        fun declaredBitDepth(mime: String, codecProfile: Int?): Int {
            val p = codecProfile ?: return 8
            return when (mime) {
                "video/hevc" -> if (p == HEVC_MAIN10 || p == HEVC_MAIN10_HDR10 || p == HEVC_MAIN10_HDR10_PLUS) 10 else 8
                "video/avc" -> if (p == AVC_HIGH10) 10 else 8
                "video/av01" -> if (p == AV1_MAIN10 || p == AV1_MAIN10_HDR10 || p == AV1_MAIN10_HDR10_PLUS) 10 else 8
                "video/x-vnd.on2.vp9" -> if (p == VP9_PROFILE2 || p == VP9_PROFILE2_HDR || p == VP9_PROFILE2_HDR10_PLUS) 10 else 8
                else -> 8
            }
        }

        /**
         * The container's declared color facts -> a spec. Every argument is
         * the raw MediaFormat value or null when the key is absent.
         */
        fun fromMediaFormat(
            mime: String,
            colorStandard: Int?,
            colorRange: Int?,
            colorTransfer: Int?,
            codecProfile: Int?,
        ): EncodedColorSpec {
            val notes = ArrayList<String>()

            val matrix: SourceMatrix
            val primaries: SourcePrimaries
            when (colorStandard) {
                COLOR_STANDARD_BT2020 -> { matrix = SourceMatrix.BT2020; primaries = SourcePrimaries.BT2020 }
                COLOR_STANDARD_BT601_PAL, COLOR_STANDARD_BT601_NTSC -> {
                    matrix = SourceMatrix.BT601; primaries = SourcePrimaries.BT709
                    notes += "bt601 primaries approximated by bt709"
                }
                else -> { matrix = SourceMatrix.BT709; primaries = SourcePrimaries.BT709 }
            }
            val standardKnown = colorStandard == COLOR_STANDARD_BT2020 ||
                colorStandard == COLOR_STANDARD_BT601_PAL ||
                colorStandard == COLOR_STANDARD_BT601_NTSC ||
                colorStandard == COLOR_STANDARD_BT709

            val range = if (colorRange == COLOR_RANGE_FULL) SourceRange.Full else SourceRange.Limited
            val rangeKnown = colorRange == COLOR_RANGE_FULL || colorRange == COLOR_RANGE_LIMITED

            val transfer: SourceTransfer
            var transferKnown = true
            when (colorTransfer) {
                COLOR_TRANSFER_SDR_VIDEO -> transfer = SourceTransfer.Rec709OETF
                COLOR_TRANSFER_LINEAR -> transfer = SourceTransfer.Linear
                COLOR_TRANSFER_ST2084 -> {
                    transfer = SourceTransfer.Pq
                    notes += "pq decoded with the rec709 inverse (no HDR decode yet)"
                }
                COLOR_TRANSFER_HLG -> {
                    transfer = SourceTransfer.Hlg
                    notes += "hlg decoded with the rec709 inverse (no HDR decode yet)"
                }
                else -> { transfer = SourceTransfer.MiLog; transferKnown = false }
            }

            val declared = declaredBitDepth(mime, codecProfile)
            val container = ProfileProvenance.Container
            return EncodedColorSpec(
                profile = SourceProfile(
                    primaries = primaries,
                    transfer = transfer,
                    matrix = matrix,
                    range = range,
                    bitDepth = declared,
                    primariesProvenance = if (standardKnown) container else null,
                    transferProvenance = if (transferKnown) container else null,
                    matrixProvenance = if (standardKnown) container else null,
                    rangeProvenance = if (rangeKnown) container else null,
                    bitDepthProvenance = if (codecProfile != null) container else null,
                ),
                declaredBitDepth = declared,
                approximations = notes,
            )
        }

        /** The pre-E0 assumption, for fakes and legacy call sites: 8-bit limited BT.709 Mi-Log. */
        fun legacy8bit(): EncodedColorSpec = EncodedColorSpec(
            profile = SourceProfile.assumedEncoded(),
            declaredBitDepth = 8,
        )
    }
}
