package com.ritim.ritimcraw.engine

/**
 * Pure naming/formatting for the device report's codec capabilities
 * (plan 13.1). JVM-testable: the platform values are kept as literals
 * (the DeviceReportGrouping precedent), each cross-checked against the
 * android-36 SDK constants. Unknown values are shown raw (hex), never
 * guessed: the report records what the vendor declares, honestly.
 */
object CodecNames {

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_DV = "video/dolby-vision"
    const val MIME_AV1 = "video/av01"
    const val MIME_APV = "video/apv"

    /** BITRATE_MODE_CQ=0, VBR=1, CBR=2, CBR_FD=3, in the order the report lists them. */
    val BITRATE_MODES = listOf(2, 1, 0, 3)

    fun bitrateModeName(mode: Int): String = when (mode) {
        0 -> "CQ"
        1 -> "VBR"
        2 -> "CBR"
        3 -> "CBR_FD"
        else -> "mode $mode"
    }

    fun profileName(mime: String, profile: Int): String = when (mime) {
        MIME_AVC -> when (profile) {
            1 -> "Baseline"; 2 -> "Main"; 4 -> "Extended"; 8 -> "High"
            16 -> "High10"; 32 -> "High422"; 64 -> "High444"
            65536 -> "ConstrainedBaseline"; 524288 -> "ConstrainedHigh"
            else -> raw(profile)
        }
        MIME_HEVC -> when (profile) {
            1 -> "Main"; 2 -> "Main10"; 4 -> "MainStill"
            4096 -> "Main10HDR10"; 8192 -> "Main10HDR10Plus"
            else -> raw(profile)
        }
        MIME_AV1 -> when (profile) {
            1 -> "Main8"; 2 -> "Main10"
            4096 -> "Main10HDR10"; 8192 -> "Main10HDR10Plus"
            else -> raw(profile)
        }
        MIME_APV -> when (profile) {
            1 -> "422-10"; 4096 -> "422-10HDR10"; 8192 -> "422-10HDR10Plus"
            else -> raw(profile)
        }
        MIME_DV -> when (profile) {
            1 -> "Dvav.Per"; 2 -> "Dvav.Pen"; 4 -> "Dvhe.Der"; 8 -> "Dvhe.Den"
            16 -> "Dvhe.Dtr"; 32 -> "Dvhe.Stn"; 64 -> "Dvhe.Dth"; 128 -> "Dvhe.Dtb"
            256 -> "Dvhe.St (8.4)"; 512 -> "Dvav.Se"; 1024 -> "Dvav.110"
            else -> raw(profile)
        }
        else -> raw(profile)
    }

    private val AVC_LEVELS = mapOf(
        1 to "1", 2 to "1b", 4 to "1.1", 8 to "1.2", 16 to "1.3", 32 to "2",
        64 to "2.1", 128 to "2.2", 256 to "3", 512 to "3.1", 1024 to "3.2",
        2048 to "4", 4096 to "4.1", 8192 to "4.2", 16384 to "5", 32768 to "5.1",
        65536 to "5.2", 131072 to "6", 262144 to "6.1", 524288 to "6.2",
    )

    // HEVC levels are bit flags: Main tier then High tier, per level.
    private val HEVC_MAIN = mapOf(
        1 to "1", 4 to "2", 16 to "2.1", 64 to "3", 256 to "3.1", 1024 to "4",
        4096 to "4.1", 16384 to "5", 65536 to "5.1", 262144 to "5.2",
        1048576 to "6", 4194304 to "6.1", 16777216 to "6.2",
    )
    private val HEVC_HIGH = mapOf(
        2 to "1", 8 to "2", 32 to "2.1", 128 to "3", 512 to "3.1", 2048 to "4",
        8192 to "4.1", 32768 to "5", 131072 to "5.1", 524288 to "5.2",
        2097152 to "6", 8388608 to "6.1", 33554432 to "6.2",
    )

    /** AVC/HEVC levels by name ("5.1", "5 High"); everything else raw hex. */
    fun levelName(mime: String, level: Int): String = when (mime) {
        MIME_AVC -> AVC_LEVELS[level] ?: raw(level)
        MIME_HEVC -> HEVC_MAIN[level] ?: HEVC_HIGH[level]?.let { "$it High" } ?: raw(level)
        else -> raw(level)
    }

    /**
     * One "Profile@level" entry per profile, at the HIGHEST level the
     * codec declares for it (levels are monotone flags/ints per profile),
     * in the declared profile order. Empty list -> empty list.
     */
    fun describeProfileLevels(mime: String, pls: List<ProfileLevel>): List<String> =
        pls.groupBy { it.profile }
            .map { (profile, group) ->
                profileName(mime, profile) + "@" + levelName(mime, group.maxOf { it.level })
            }

    /** COLOR_Format* ints: the ones this app cares about by name, the rest raw hex. */
    fun colorFormatName(format: Int): String = when (format) {
        0x7F000789 -> "Surface"
        0x7F420888 -> "YUV420Flexible"
        0x7F422888 -> "YUV422Flexible"
        0x7F36A888 -> "RGBAFlexible"
        0x7F00AAA2 -> "RGBA1010102"
        0x7F000F16 -> "RGBAFloat16"
        54 -> "P010"
        60 -> "P210"
        21 -> "NV12"
        19 -> "I420"
        else -> raw(format)
    }

    fun describeColorFormats(formats: List<Int>): List<String> =
        formats.distinct().map { colorFormatName(it) }

    /** "160 Mbps" / "800 kbps"; whole numbers drop the decimals, others keep one. */
    fun formatBitrate(bps: Int): String {
        val mbps = bps / 1_000_000.0
        return when {
            bps >= 1_000_000 && bps % 1_000_000 == 0 -> "${bps / 1_000_000} Mbps"
            bps >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f Mbps", mbps)
            else -> "${bps / 1000} kbps"
        }
    }

    private fun raw(v: Int): String = "0x" + Integer.toHexString(v)
}
