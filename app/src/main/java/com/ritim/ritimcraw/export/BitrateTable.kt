package com.ritim.ritimcraw.export

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Video bitrate per level (plan 7.3, the Klipp ExportPresets.mbps rule):
 * H.264 1080p30 STANDARD is about 12 Mbps (YouTube's upload guidance),
 * scaled by pixels per second to the power 0.85 (bitrate grows slower than
 * pixel rate), times the level factor and the codec factor (HEVC 0.6).
 * Rounded to half a megabit.
 */
object BitrateTable {

    private const val REFERENCE_PIXEL_RATE = 1920.0 * 1080 * 30
    private const val REFERENCE_MBPS = 16.0 // x STANDARD's 0.75 = 12 Mbps at 1080p30
    const val MIN_MBPS = 0.5
    const val MAX_MBPS = 400.0

    fun mbps(level: BitrateLevel, width: Int, height: Int, fps: Double, codec: ExportCodec): Double {
        require(level != BitrateLevel.CUSTOM) { "CUSTOM has no table value" }
        val px = width.toDouble() * height * fps / REFERENCE_PIXEL_RATE
        val v = REFERENCE_MBPS * px.pow(0.85) * level.factor * codec.relativeBitrate
        return ((v * 2).roundToLong() / 2.0).coerceIn(MIN_MBPS, MAX_MBPS)
    }

    /**
     * What an export WITHOUT [ExportSettings] encodes (every job queued
     * before E1): w*h*2.4 bits, clamped to 2..25 Mbps, in bits per second.
     * Kept here so old jobs and the new format builder share one source.
     */
    fun legacyBitsPerSecond(width: Int, height: Int): Int =
        (width.toLong() * height * 24 / 10).toInt().coerceIn(2_000_000, 25_000_000)
}
