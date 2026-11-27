package com.ritim.ritimcraw.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Pure helpers for import-time .gcsv gravity orientation resolution
 * (owner decision 2026-11-27: trust sidecar .gcsv accelerometer gravity
 * first; fall back to MotionCam's per-frame screen-orientation code when
 * no usable .gcsv decision is available).
 *
 * Completely independent of [com.ritim.ritimcraw.util.FeatureFlags.GYRO_STABILIZATION_ENABLED]:
 * pausing gyro stabilization must never disable orientation detection.
 */
object GcsvOrientation {

    /**
     * Header + ~2 seconds of 470 Hz IMU rows (~60 KB): plenty to cover the
     * native gravity estimator's 1-second window without loading multi-MB
     * logs or cutting a CSV row mid-line.
     */
    const val DEFAULT_MAX_LINES = 1000

    /**
     * Unique, non-blank granted SAF tree URIs to search for a sibling .gcsv
     * sidecar, preferring [importFolder] (always granted in folder-import
     * workflows even while gyro stabilization is paused) and falling back to
     * [gyroFolder] if different.
     */
    fun candidateFolders(importFolder: String?, gyroFolder: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (!importFolder.isNullOrBlank()) out.add(importFolder)
        if (!gyroFolder.isNullOrBlank()) out.add(gyroFolder)
        return out.toList()
    }

    /**
     * Reads at most [maxLines] complete lines from [input] as UTF-8 text.
     * Stops cleanly on line boundaries so `parseGcsv` never sees a truncated
     * trailing row.
     */
    fun readPrefix(input: InputStream, maxLines: Int = DEFAULT_MAX_LINES): String {
        if (maxLines <= 0) return ""
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val sb = StringBuilder()
        var count = 0
        while (count < maxLines) {
            val line = reader.readLine() ?: break
            sb.append(line).append('\n')
            count++
        }
        return sb.toString()
    }

    /**
     * Given the clip's current [currentManualRotationDeg] and the native
     * [gravityDelta] (0/90/180/270, or -1 when gravity could not decide),
     * returns the manual rotation to persist — or `null` when nothing should
     * change:
     *  - never overrides an existing non-zero user rotation;
     *  - negative or non-quarter deltas fall back to the clip's metadata
     *    screen-orientation code (return `null`);
     *  - delta 0 means gravity already agrees with the metadata fallback
     *    (return `null`, no store write or thumbnail invalidation needed).
     */
    fun decideManualRotation(currentManualRotationDeg: Int, gravityDelta: Int): Int? {
        if (RotationState.normalize(currentManualRotationDeg) != 0) return null
        if (gravityDelta < 0 || gravityDelta % 90 != 0) return null
        val normalized = RotationState.normalize(gravityDelta)
        return if (normalized == 0) null else normalized
    }
}
