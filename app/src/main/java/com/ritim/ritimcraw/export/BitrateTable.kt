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

    /**
     * The value of [level] for this picture. [ceilingMbps] is the chosen
     * encoder's listed bitrate ceiling (null = unknown): only HIGHER and
     * CEILING need it; without it (or when the ceiling leaves no room above
     * MAX, see [extraSteps]) they resolve as MAX.
     */
    fun mbps(
        level: BitrateLevel, width: Int, height: Int, fps: Double, codec: ExportCodec,
        ceilingMbps: Double? = null,
    ): Double {
        require(level != BitrateLevel.CUSTOM) { "CUSTOM has no table value" }
        if (level == BitrateLevel.HIGHER || level == BitrateLevel.CEILING) {
            val max = mbps(BitrateLevel.MAX, width, height, fps, codec)
            return extraSteps(max, ceilingMbps).firstOrNull { it.first == level }?.second ?: max
        }
        val px = width.toDouble() * height * fps / REFERENCE_PIXEL_RATE
        var v = REFERENCE_MBPS * px.pow(0.85) * level.factor * codec.relativeBitrate
        // QUALITY never asks for more than the encoder lists (4K60 would scale past it).
        if (level == BitrateLevel.QUALITY && ceilingMbps != null) v = minOf(v, kotlin.math.floor(ceilingMbps * 2) / 2.0)
        return ((v * 2).roundToLong() / 2.0).coerceIn(MIN_MBPS, MAX_MBPS)
    }

    /** A step is worth offering only when it is at least this much above the one below it. */
    private const val MIN_STEP_GAIN = 1.25

    /**
     * The ladder steps above MAX, up to the encoder's listed ceiling (pure).
     * [maxMbps] is MAX's table value, [ceilingMbps] the listed ceiling (null or
     * not above MAX by [MIN_STEP_GAIN] = no extra steps). HIGHER is the middle
     * of MAX and the ceiling (rounded to half a megabit) and is dropped when
     * it would sit within [MIN_STEP_GAIN] of MAX or within 5% of the ceiling;
     * CEILING is the ceiling itself. Never above the ceiling: anything past it
     * needs "uncapped" on a Custom value.
     */
    fun extraSteps(maxMbps: Double, ceilingMbps: Double?): List<Pair<BitrateLevel, Double>> {
        if (ceilingMbps == null || ceilingMbps < maxMbps * MIN_STEP_GAIN) return emptyList()
        val ceil = kotlin.math.floor(ceilingMbps * 2) / 2.0 // floor: a step must never exceed the listed ceiling
        val mid = (((maxMbps + ceilingMbps) / 2.0) * 2).roundToLong() / 2.0
        return buildList {
            if (mid >= maxMbps * MIN_STEP_GAIN && mid <= ceil * 0.95) add(BitrateLevel.HIGHER to mid)
            add(BitrateLevel.CEILING to ceil)
        }
    }

    /**
     * What an export WITHOUT [ExportSettings] encodes (every job queued
     * before E1): w*h*2.4 bits, clamped to 2..25 Mbps, in bits per second.
     * Kept here so old jobs and the new format builder share one source.
     */
    fun legacyBitsPerSecond(width: Int, height: Int): Int =
        (width.toLong() * height * 24 / 10).toInt().coerceIn(2_000_000, 25_000_000)
}
