package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.util.FeatureFlags

/**
 * The ONE per-frame warp decision + fold, shared by the preview
 * (ClipEditorScreen -> GpuPreviewSession) and the export (Mp4Exporter),
 * docs/IMAGE_STAB_PLAN.md section 3.5. Before S4 the same six-value
 * gyro gating was spelled out in two places and both were locked to
 * `gyroPath`; now the engine choice (off | image | gyro, see
 * [StabilizationGate.engineFor]) is made here once.
 *
 * Both engines answer in the SAME layout: `[active, M x 9]`, M the
 * row-major output->source matrix the GPU warp pass consumes (the shader and
 * ChainSlots are unchanged) — null = no verdict, folded to inactive +
 * identity by [verdict], never a fabricated warp.
 *
 * The stored grade is never modified. With both feature flags false (the
 * shipped state) every plan is either the old gated gyro call or None, so
 * the dispatch is the pre-S4 behavior value for value (WarpResolverTest).
 */
object WarpResolver {

    /** Image engine arguments; [rangeEnd] < 0 = through the last frame. */
    data class ImageArgs(
        val mode: Int,
        val strength: Float,
        val zoomLimit: Float,
        val rangeStart: Int,
        val rangeEnd: Int,
    )

    /** Gyro engine arguments, exactly what the pre-S4 call sites passed. */
    data class GyroArgs(
        val gyroPath: String?,
        val mode: Int,
        val strength: Float,
        val zoomLimit: Float,
        val focalMm: Float,
        val trimMs: Float,
    )

    sealed interface Plan {
        /** No warp, no native call. */
        data object None : Plan
        data class Gyro(val args: GyroArgs) : Plan
        data class Image(val args: ImageArgs) : Plan
    }

    /** What the render consumes: whether draw 10 runs and its matrix. */
    class Verdict(val active: Boolean, val m: FloatArray)

    /** The image engine's argument for the render calls' `gyroPath` slot (whole clip by default). */
    fun imageArg(rangeStart: Int = 0, rangeEnd: Int = -1): String =
        "${StabilizationGate.IMAGE_ARG_PREFIX}$rangeStart:$rangeEnd"

    /**
     * The `gyroPath`-slot value a caller hands down to every render and
     * export call: the image argument when the image engine is selected
     * AND ready, null when the grade asks for the image engine and it is
     * not (never the gyro log: an image-engine grade must not fall back to
     * gyro), else the attached gyro path untouched.
     */
    fun stabPathArg(
        grade: GradeK,
        gyroPath: String?,
        imageAnalysisReady: Boolean,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
        gyroEnabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): String? {
        if (!grade.stabilization || grade.stabilizationEngine != StabilizationGate.ENGINE_IMAGE) {
            return gyroPath
        }
        return when (
            StabilizationGate.engineFor(
                grade, gyroPath, imageAnalysisReady, imageEnabled, gyroEnabled,
            )
        ) {
            StabEngine.IMAGE -> imageArg()
            else -> null
        }
    }

    /** The plan for one frame, from the grade and the [stabPathArg] value. */
    fun plan(
        grade: GradeK,
        stabPath: String?,
        imageEnabled: Boolean = FeatureFlags.IMAGE_STABILIZATION_ENABLED,
        gyroEnabled: Boolean = FeatureFlags.GYRO_STABILIZATION_ENABLED,
    ): Plan {
        if (StabilizationGate.isImageArg(stabPath)) {
            if (!imageEnabled || !grade.stabilization) return Plan.None
            val range = parseImageArg(stabPath!!) ?: return Plan.None
            return Plan.Image(
                ImageArgs(
                    mode = grade.stabilizationMode,
                    strength = grade.stabilizationStrength / 100f,
                    zoomLimit = grade.stabilizationZoomLimit,
                    rangeStart = range.first,
                    rangeEnd = range.second,
                ),
            )
        }
        // A grade on the image engine whose path is not the image argument
        // never reaches the gyro engine (stabPathArg returned null for it).
        if (grade.stabilization && grade.stabilizationEngine == StabilizationGate.ENGINE_IMAGE) {
            return Plan.None
        }
        val on = StabilizationGate.active(grade.stabilization, stabPath, gyroEnabled)
        return Plan.Gyro(
            GyroArgs(
                gyroPath = if (on) stabPath else null,
                mode = grade.stabilizationMode,
                strength = if (on) grade.stabilizationStrength / 100f else 0f,
                zoomLimit = grade.stabilizationZoomLimit,
                focalMm = grade.stabilizationFocalMm,
                trimMs = grade.stabilizationTrimMs,
            ),
        )
    }

    /** Dispatches [plan] to the matching native door; the doors may suspend (inline). */
    inline fun resolve(
        plan: Plan,
        gyro: (GyroArgs) -> FloatArray?,
        image: (ImageArgs) -> FloatArray?,
    ): FloatArray? = when (plan) {
        Plan.None -> null
        is Plan.Gyro -> gyro(plan.args)
        is Plan.Image -> image(plan.args)
    }

    /** null / short = inactive + identity (the pass's absence IS the identity). */
    fun verdict(raw: FloatArray?): Verdict {
        val active = raw != null && raw[0] != 0.0f
        val m = if (raw != null && raw.size >= 10) {
            raw.copyOfRange(1, 10)
        } else {
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        return Verdict(active, m)
    }

    private fun parseImageArg(arg: String): Pair<Int, Int>? {
        val parts = arg.removePrefix(StabilizationGate.IMAGE_ARG_PREFIX).split(':')
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val b = parts.getOrNull(1)?.toIntOrNull() ?: -1
        return a to b
    }
}
