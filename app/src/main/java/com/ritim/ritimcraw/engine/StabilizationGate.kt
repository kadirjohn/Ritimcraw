package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
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
/** The engine that stabilizes a render/export, or [OFF] (docs/IMAGE_STAB_PLAN.md section 3.7). */
enum class StabEngine { OFF, IMAGE, GYRO }

object StabilizationGate {

    /** [GradeK.stabilizationEngine] value of the image engine. */
    const val ENGINE_IMAGE = 1

    /**
     * Off | image | gyro for one render or export (S4). The ONE rule:
     *  - stabilization off in the grade -> OFF;
     *  - `stabilizationEngine == 1` -> IMAGE when
     *    [FeatureFlags.IMAGE_STABILIZATION_ENABLED] AND the clip's analysis
     *    is ready ([imageAnalysisReady]), else OFF;
     *  - ANY other value, including 0 and the value of a record that
     *    predates the field (the key is absent -> 0): GYRO when
     *    [FeatureFlags.GYRO_STABILIZATION_ENABLED] AND a gyro log is
     *    attached, else OFF.
     * "No engine field means gyro" is what keeps every old project resolving
     * exactly as it did: the stored grade is never rewritten, and with the
     * gyro flag on the old [active] condition returns bit for bit.
     * Both flags are parameters so the JVM tests exercise every state
     * without touching the compile-time constants.
     */
    fun engineFor(
        grade: GradeK,
        gyroPath: String?,
        imageAnalysisReady: Boolean,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
        gyroEnabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): StabEngine = when {
        !grade.stabilization -> StabEngine.OFF
        grade.stabilizationEngine == ENGINE_IMAGE ->
            if (imageEnabled && imageAnalysisReady) StabEngine.IMAGE else StabEngine.OFF
        else -> if (active(true, gyroPath, gyroEnabled)) StabEngine.GYRO else StabEngine.OFF
    }

    /**
     * True when the grade asks for the image engine and the flag allows it
     * but the analysis is not ready: the UI must say so (analysisMissing)
     * and an export must run the analysis first or fail loudly — never
     * silently render unstabilized.
     */
    fun imageAnalysisMissing(
        grade: GradeK,
        imageAnalysisReady: Boolean,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
    ): Boolean = imageEnabled && grade.stabilization &&
        grade.stabilizationEngine == ENGINE_IMAGE && !imageAnalysisReady

    /** True when this render/export warps: flag on AND grade on AND a log attached. */
    fun active(
        stabilization: Boolean,
        gyroPath: String?,
        enabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): Boolean = enabled && stabilization && gyroPath != null && !isImageArg(gyroPath)

    /**
     * The image engine travels through the render calls' existing
     * `gyroPath` string argument as `ristab-image:<rangeStart>:<rangeEnd>`
     * (see WarpResolver and the bridge's kImageStabPrefix): the ten CPU JNI
     * entries keep their signatures. It is NEVER a gyro log, so the gyro
     * gate above refuses it.
     */
    const val IMAGE_ARG_PREFIX = "ristab-image:"

    fun isImageArg(path: String?): Boolean = path != null && path.startsWith(IMAGE_ARG_PREFIX)

    /**
     * The `gyroPath` argument a CPU render/export call hands to native:
     * the gyro path when the gyro gate is on, the image argument when
     * [gyroPath] IS the image argument and the image flag is on, else null
     * (the no-warp default).
     */
    fun renderPathIfActive(
        stabilization: Boolean,
        gyroPath: String?,
        gyroEnabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
    ): String? = when {
        !stabilization -> null
        isImageArg(gyroPath) -> if (imageEnabled) gyroPath else null
        else -> pathIfActive(stabilization, gyroPath, gyroEnabled)
    }

    /** True when either engine warps this render (the "stabilization applied" readout). */
    fun appliedFor(
        stabilization: Boolean,
        gyroPath: String?,
        gyroEnabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
    ): Boolean = renderPathIfActive(stabilization, gyroPath, gyroEnabled, imageEnabled) != null

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
