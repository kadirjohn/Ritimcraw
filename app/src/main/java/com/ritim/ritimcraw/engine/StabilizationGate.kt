package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.util.FeatureFlags

/**
 * The ONE "effective stabilization" decision (owner request 2026-11-23,
 * plan section 3.1). Every preview and export site that used to spell
 * `grade.stabilization && gyroPath != null` asks here instead, so the
 * [FeatureFlags.GYRO_STABILIZATION_ENABLED] switch forces stabilization
 * off in exactly one place. The stored grade is never modified: with the
 * flag on, [active] is the old condition bit for bit.
 *
 * [enabled] is a parameter (defaulting to the flag) so the JVM tests can
 * exercise both states without touching the compile-time constant.
 */
object StabilizationGate {

    /** True when this render/export warps: flag on AND grade on AND a log attached. */
    fun active(
        stabilization: Boolean,
        gyroPath: String?,
        enabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): Boolean = enabled && stabilization && gyroPath != null

    /** The gyro path to hand to native: null (the no-warp default) unless [active]. */
    fun pathIfActive(
        stabilization: Boolean,
        gyroPath: String?,
        enabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): String? = if (active(stabilization, gyroPath, enabled)) gyroPath else null

    /**
     * Import-time and probe-time gate: with the flag off no gyro log is
     * matched (GyroMatcher), probed (jniGyroSyncProbe) or refined natively.
     */
    fun gyroWorkAllowed(enabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED): Boolean = enabled
}
