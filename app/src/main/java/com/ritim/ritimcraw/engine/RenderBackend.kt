package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK

/**
 * User-requested image-processing backend (owner request 2026-11-12,
 * deliverable B): what the SETTINGS picker stores and what an export job
 * snapshots. This is the REQUESTED axis only — the EFFECTIVE backend that
 * actually runs is a separate, reported quantity ([EffectiveRenderBackend])
 * computed from the requested choice plus the runtime capability probe and
 * the parity gate. Requested and effective are NEVER conflated (owner
 * prompt §4: "the setting alone is not the feature").
 *
 * Orthogonal to the video encoder choice (owner prompt §14, binding):
 * selecting CPU here must NOT prevent the hardware video encoder from being
 * used — the render backend decides where PIXELS are computed, the encoder
 * axis decides which MediaCodec instance compresses them, and neither reads
 * the other.
 *
 * Semantics of each choice (owner prompt §14):
 * - CPU — the current reference path, unchanged. The safe default until
 *   device acceptance of the GPU path.
 * - AUTO — GPU only when the capability probe AND the parity gate pass;
 *   otherwise CPU with a logged fallback reason.
 * - GPU_EXPERIMENTAL — request GPU; when unavailable, fall back to CPU and
 *   report the effective backend honestly (the job detail says what ran,
 *   never silently what was asked).
 *
 * DEFAULT is CPU: the GPU path is experimental and unproven on a device,
 * so a fresh install runs the reference pipeline exactly as before
 * (locked by RenderBackendTest).
 */
enum class RenderBackend {
    CPU,
    AUTO,
    GPU_EXPERIMENTAL;

    companion object {
        /**
         * The persisted value's wire name in files/project.json. A top-level
         * SCALAR (not a section), written by RenderBackendStore with
         * omit-when-default so files that never chose stay byte-identical
         * (the PreviewQualityStore pattern).
         */
        const val WIRE_NAME = "renderBackend"

        /**
         * The backend an editor/export with no saved preference uses. CPU:
         * the GPU path is not device-accepted yet, so the default changes
         * nothing until the owner moves the picker.
         */
        val DEFAULT: RenderBackend = CPU

        /**
         * Parses a persisted wire value: null/blank/unknown strings resolve
         * to [DEFAULT] (an old file predating the setting, or a future
         * build's value this one does not know — the editor still opens
         * and runs at the default, never crashes on it). Same contract as
         * [PreviewQuality.fromWire].
         */
        fun fromWire(value: String?): RenderBackend =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * The backend that actually ran (or will run) for one render/export job —
 * the EFFECTIVE axis of owner prompt §4, reported honestly and never
 * silently. Computed by [BackendRules.effectiveBackend] from the requested
 * backend plus the runtime gates; an export job snapshots it at job start
 * and never changes mid-run on a global setting change (owner prompt §14).
 */
enum class EffectiveRenderBackend {
    CPU,
    GLES,
    HYBRID;
}

/**
 * The runtime gates that decide the effective backend (owner prompt §4/§14).
 * Deliberately a small pure data class: the device-only EGL probe fills it
 * (deliverable C's capability probe), the JVM tests exercise the decision
 * rules, and no Android framework type leaks into the decision logic.
 *
 * All gates default to the "GPU not allowed" side: a probe that never ran
 * (all-false, the JVM/honest default) keeps every requested backend on the
 * CPU reference path — the conservative direction (owner prompt §14: CPU
 * stays the safe default until device acceptance).
 */
data class BackendGates(
    /** GLES 3.1 context created successfully on the render thread. */
    val contextOk: Boolean = false,
    /** Compute shaders available (GLES 3.1 mandatory feature on device). */
    val computeOk: Boolean = false,
    /** EXT_color_buffer_float present (float FBOs renderable). */
    val floatFboOk: Boolean = false,
    /** The single-frame GPU-vs-CPU parity gate passed on this device. */
    val parityOk: Boolean = false,
) {
    /**
     * True when every gate the GPU path needs is green. Pure: the same
     * gates always decide the same way, JVM-testable without a device.
     */
    fun gpuAllowed(): Boolean = contextOk && computeOk && floatFboOk && parityOk

    /**
     * The logged reason when the GPU path is not taken — owner prompt §14
     * requires AUTO/GPU fallbacks to be LOGGED, not silent. Pure.
     */
    fun fallbackReason(): String? {
        if (gpuAllowed()) return null
        val missing = mutableListOf<String>()
        if (!contextOk) missing += "no-egl-context"
        if (!computeOk) missing += "no-compute"
        if (!floatFboOk) missing += "no-float-fbo"
        if (!parityOk) missing += "parity-not-verified"
        return missing.joinToString(",")
    }

    /**
     * True when every gate the FRAGMENT develop path needs is green
     * (deliverable D, owner request 2026-11-12 §D). The fragment chain is a
     * DRAW pipeline (ES 3.00 develop pass + ES 3.10 store pass), so it needs
     * NO compute: a compute-less ES 3.1 device can still run it. The gates
     * are context + float FBO (the RGBA32F attachment needs
     * EXT_color_buffer_float) + the fragment parity verdict. Deliberately a
     * SEPARATE predicate from [gpuAllowed] — the compute leg's four-gate
     * math stays untouched (locked by RenderBackendTest); this is the
     * fragment leg's own honest gate, ADDED alongside it.
     */
    fun fragmentAllowed(): Boolean = contextOk && floatFboOk && parityOk

    /**
     * The logged reason when the FRAGMENT path is not taken — mirrors
     * [fallbackReason] for the fragment leg's own gate set: only the gates
     * the fragment path needs are listed, and only when missing. Pure.
     */
    fun fragmentFallbackReason(): String? {
        if (fragmentAllowed()) return null
        val missing = mutableListOf<String>()
        if (!contextOk) missing += "no-egl-context"
        if (!floatFboOk) missing += "no-float-fbo"
        if (!parityOk) missing += "parity-not-verified"
        return missing.joinToString(",")
    }
}

/**
 * Pure decision rules for requested -> effective backend (owner prompt
 * §14). No Android framework types; JVM-testable. The rules are the single
 * definition used by both the preview path and the export job snapshot.
 */
object BackendRules {

    /**
     * The effective backend for a requested choice under [gates].
     *
     * - CPU: always CPU (the reference path; never gated).
     * - AUTO: GLES when every gate passes, else CPU — the fallback reason
     *   is the caller's to log via [BackendGates.fallbackReason].
     * - GPU_EXPERIMENTAL: GLES when every gate passes, else CPU with the
     *   fallback reported honestly (the user asked for GPU; the job detail
     *   must say CPU actually ran).
     *
     * HYBRID is a legal effective value for future split stages (some
     * stages GPU, some CPU) but no current rule produces it — it exists so
     * the axis is ready and so diagnostics never have to lie.
     */
    fun effectiveBackend(
        requested: RenderBackend,
        gates: BackendGates,
    ): EffectiveRenderBackend {
        if (requested == RenderBackend.CPU) return EffectiveRenderBackend.CPU
        // AUTO and GPU_EXPERIMENTAL share the gate: both request the GPU
        // path and fall back to CPU when the device is not ready. The
        // difference is intent (who asked for what), not the rule.
        return if (gates.gpuAllowed()) {
            EffectiveRenderBackend.GLES
        } else {
            EffectiveRenderBackend.CPU
        }
    }

    /**
     * The honest fallback line for a requested backend that did not get
     * the GPU path: null when the GPU path runs (or CPU was requested and
     * CPU ran — no fallback happened). The caller logs this under the
     * export tag so owner prompt §14's "logged CPU fallback with reason"
     * holds for both AUTO and GPU_EXPERIMENTAL.
     */
    fun fallbackLine(requested: RenderBackend, gates: BackendGates): String? {
        if (requested == RenderBackend.CPU) return null
        val reason = gates.fallbackReason() ?: return null
        return "render backend fallback: requested=${requested.name} " +
            "effective=CPU reason=$reason"
    }

    /**
     * The stage-coverage set of the FRAGMENT develop pass (deliverable D,
     * GlesShaderSources.DEVELOP_FRAGMENT): the per-pixel pure stages fused
     * into the one ES 3.00 draw — normalization (1), lens shading (2),
     * highlight reconstruction (3), demosaic (4), WB (5), color transform
     * (6), exposure (14), tonemapping (23) and the OETF tail. Stages 2 and
     * 3 are the DEFAULT grade's own toggles (keepVignetting and
     * highlightReconstruction both default true), so porting them is what
     * lets a default-grade GPU request actually stay on GPU; stage 23 is
     * the ACES fit plus the full AgX family (modes 1-5, the CPU's exact
     * math behind uTonemap). Everything else in the 24-stage CPU contract
     * falls back to CPU when active (see [fragmentUncoveredStages]).
     */
    val FRAGMENT_COVERED_STAGES: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 14, 23)

    /**
     * The fragment path's honest uncovered list, as stage number ->
     * short reason token used in the fallback line (deliverable D's
     * no-silent-skip rule: an active effect the GPU does not cover makes
     * the whole frame take the CPU path, and the line says which stage
     * forced it). Mirrors GlesShaderSources.DEVELOP_FRAGMENT's "Stages
     * NOT covered" doc and the CPU guards in core/src/Develop.cpp
     * exactly — the same field != neutral / toggle checks the CPU runs.
     * Stages 2 and 3 left this list when they were ported into the
     * fragment pass: the shader applies stage 2 only when the uniform
     * gate says the clip has a map (the CPU's own guard, computed
     * CPU-side — see [fragmentShadingApplies]) and stage 3 when the
     * grade toggles it, so coverage no longer depends on the clip's
     * metadata shape. Stage 23 left it with the tonemap port: the shader
     * carries the ACES fit and every AgX mode (1-5) behind uTonemap,
     * the CPU's exact math, so an active tonemap grade no longer forces
     * the CPU path.
     */
    val FRAGMENT_UNCOVERED_STAGES: Map<Int, String> = linkedMapOf(
        7 to "chroma-denoise",        // grade.chromaDenoise != 0
        8 to "noise-reduction",       // grade.noiseReduction != 0
        9 to "black-point",           // grade.blackPoint != 0
        10 to "white-point",          // grade.whitePoint != 0
        11 to "shadows",              // grade.shadows != 0
        12 to "midtones",             // grade.midtones != 0
        13 to "contrast",             // grade.contrast != 0
        15 to "highlights",           // grade.highlights != 0
        16 to "temperature",          // !grade.asShot && grade.tempK != 5600
        17 to "tint",                 // !grade.asShot && grade.tint != 0
        18 to "color-wheel",          // any wheel offset != 0
        19 to "split-tone",           // splitShadowHue/HighlightHue >= 0
        20 to "saturation",           // grade.saturation != 0
        21 to "sharpness",            // grade.sharpness != 0
        22 to "detail",               // grade.detail != 0
        24 to "lut",                  // lut selected && grade.lutStrength > 0
    )

    /**
     * The uncovered work that runs OUTSIDE developCpu's own stage list but
     * still changes pixels before the encode: the temporal NR (applied by
     * the JNI bridge to the CFA frames BEFORE developCpu) and the gyro
     * warp. Same no-silent-skip rule: active -> CPU, with the token in
     * the line.
     */
    val FRAGMENT_UNCOVERED_PRE_STAGES: Set<String> = setOf(
        "temporal-nr",    // grade.temporalNr && grade.temporalStrength > 0
        "gyro-warp",      // a gyro path is attached to the export job
    )

    /**
     * The uncovered stages ACTIVE in one grade, as (stage, token) pairs —
     * pure, honest, and mirroring the CPU guards exactly (owner prompt
     * deliverable D: no active effect is silently skipped; the GPU path
     * only takes a frame when EVERY active stage is covered).
     *
     * Stages 2, 3 and 23 are no longer listed: all three are covered by the
     * fragment pass. Stage 2's map-state guard moved into the shader's
     * uniform (uHasShading — the CPU-side gate computed where the map
     * state is known; see [fragmentShadingApplies]), stage 3's toggle
     * moved into uHr, and stage 23's mode moved into uTonemap (the ACES
     * fit and every AgX look, the CPU's exact math). A
     * keepVignetting/highlightReconstruction/tonemap grade is therefore
     * NOT an uncovered-stage fallback anymore.
     */
    fun fragmentUncoveredStages(grade: GradeK): List<Pair<Int, String>> {
        val active = mutableListOf<Pair<Int, String>>()
        if (grade.chromaDenoise != 0f) active += 7 to "chroma-denoise"
        if (grade.noiseReduction != 0f) active += 8 to "noise-reduction"
        if (grade.blackPoint != 0f) active += 9 to "black-point"
        if (grade.whitePoint != 0f) active += 10 to "white-point"
        if (grade.shadows != 0f) active += 11 to "shadows"
        if (grade.midtones != 0f) active += 12 to "midtones"
        if (grade.contrast != 0f) active += 13 to "contrast"
        if (grade.highlights != 0f) active += 15 to "highlights"
        if (!grade.asShot && grade.tempK != 5600f) active += 16 to "temperature"
        if (!grade.asShot && grade.tint != 0f) active += 17 to "tint"
        if (grade.wheelShadowX != 0f || grade.wheelShadowY != 0f ||
            grade.wheelMidX != 0f || grade.wheelMidY != 0f ||
            grade.wheelHighX != 0f || grade.wheelHighY != 0f
        ) {
            active += 18 to "color-wheel"
        }
        if (grade.splitShadowHue >= 0f || grade.splitHighlightHue >= 0f) {
            active += 19 to "split-tone"
        }
        if (grade.saturation != 0f) active += 20 to "saturation"
        if (grade.sharpness != 0f) active += 21 to "sharpness"
        if (grade.detail != 0f) active += 22 to "detail"
        if (grade.lutId != null && grade.lutStrength > 0f) active += 24 to "lut"
        return active
    }

    /**
     * The uncovered pre-develop work active in one grade (temporal NR; the
     * gyro warp is a job-level fact the caller adds — see the export
     * wiring). Pure.
     */
    fun fragmentUncoveredPreStages(grade: GradeK): List<String> {
        val active = mutableListOf<String>()
        if (grade.temporalNr && grade.temporalStrength > 0f) active += "temporal-nr"
        return active
    }

    /**
     * Whether the FRAGMENT path actually applies stage 2 (lens shading)
     * for this grade on this clip: the CPU's own stage-2 gate
     * (grade.keepVignetting && the clip's shading map non-empty), with
     * the map state supplied by the caller — the fragment path KNOWS it
     * (the C++ harness reads the clip's RawMeta directly), while the CPU
     * path keeps its own identical guard inside developCpu. Pure.
     *
     * The asymmetry is deliberate and honest: this helper does NOT decide
     * coverage (stage 2 is covered either way — the shader handles the
     * no-map case through uHasShading = 0); it decides whether the
     * uniform gate tells the shader to multiply the gains in. The old
     * conservative Kotlin guess (treat keepVignetting=true as an
     * uncovered stage because the map state was unknown) is gone because
     * the stage is covered; what replaced it is exact.
     */
    fun fragmentShadingApplies(grade: GradeK, hasShadingMap: Boolean): Boolean =
        grade.keepVignetting && hasShadingMap

    /**
     * The effective backend for the FRAGMENT develop leg of one export:
     * the fragment pass only takes the frame when the fragment gates pass
     * AND every stage active in the grade is covered — otherwise CPU, with
     * the honest line from [fragmentFallbackLine]. ADDS alongside
     * [effectiveBackend] (the compute-leg rule stays untouched).
     */
    fun fragmentEffectiveBackend(
        requested: RenderBackend,
        gates: BackendGates,
        grade: GradeK,
        gyroWarpActive: Boolean,
    ): EffectiveRenderBackend {
        if (requested == RenderBackend.CPU) return EffectiveRenderBackend.CPU
        if (!gates.fragmentAllowed()) return EffectiveRenderBackend.CPU
        if (fragmentUncoveredStages(grade).isNotEmpty()) return EffectiveRenderBackend.CPU
        if (fragmentUncoveredPreStages(grade).isNotEmpty()) return EffectiveRenderBackend.CPU
        if (gyroWarpActive) return EffectiveRenderBackend.CPU
        return EffectiveRenderBackend.GLES
    }

    /**
     * The fragment leg's honest fallback line: null when the fragment path
     * runs (or CPU was requested). The reason names the missing fragment
     * gates OR the active uncovered stages — whichever forced the CPU
     * path, never silently. Format matches [fallbackLine] so the export
     * log reads uniformly.
     */
    fun fragmentFallbackLine(
        requested: RenderBackend,
        gates: BackendGates,
        grade: GradeK,
        gyroWarpActive: Boolean,
    ): String? {
        if (requested == RenderBackend.CPU) return null
        if (fragmentEffectiveBackend(requested, gates, grade, gyroWarpActive) ==
            EffectiveRenderBackend.GLES
        ) {
            return null
        }
        val reasons = mutableListOf<String>()
        gates.fragmentFallbackReason()?.let { reasons += it }
        reasons += fragmentUncoveredStages(grade).map { it.second }
        reasons += fragmentUncoveredPreStages(grade)
        if (gyroWarpActive) reasons += "gyro-warp"
        return "render backend fallback: requested=${requested.name} " +
            "effective=CPU reason=${reasons.joinToString(",")}"
    }
}
