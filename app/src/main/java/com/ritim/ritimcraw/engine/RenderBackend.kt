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
     * The stage-coverage set of the FRAGMENT develop path (deliverable D,
     * GlesShaderSources.DEVELOP_FRAGMENT_PRE/_POST plus the spatial-NR
     * passes NR_CHROMA_H/NR_CHROMA_V/NR_LUMA): the per-pixel pure stages
     * of the split draw chain — normalization (1), lens shading (2),
     * highlight reconstruction (3), demosaic (4), WB (5), color transform
     * (6) in the PRE pass; the spatial noise reduction (7 chroma — the
     * CPU's luma-guided two-direction filter as TWO passes, horizontal
     * then vertical, the vertical pass keeping the ORIGINAL luma guide;
     * 8 luma — the CPU's true 3x3 bilateral, one pass, center + 8
     * neighbors) in their own NR passes at the CPU's exact stage position
     * (after 5-6, before 9+); and the ten pointwise grade stages (9 black
     * point, 10 white point, 11 shadows, 12 midtones, 13 contrast,
     * 16 temperature, 17 tint, 18 color wheel, 19 split tone, 20
     * saturation), exposure (14), highlights (15), tonemapping (23) and
     * the OETF tail in the POST pass. Each of the ten pointwise stages is
     * the CPU loop's exact math behind a raw-value uniform (0.0 = the
     * CPU's own skip), with the 16/17 asShot gate hoisted to the
     * CPU-computed uWbManual and the 19 split gate to uSplitActive; a
     * multi-control grade therefore stays on GPU instead of falling back
     * per control. Stages 7 and 8 are the CPU's exact spatial filters
     * behind their raw-value uniforms (uChromaDenoise, uNoiseReduction —
     * 0.0 = the CPU's own skip, with the CPU's own w/h guards mirrored
     * CPU-side where the dims are known), ported verbatim and NOT
     * approximated (no generic 3x3, no collapsed bilateral). Stages 2 and
     * 3 are the DEFAULT grade's own toggles (keepVignetting and
     * highlightReconstruction both default true), so porting them is what
     * lets a default-grade GPU request actually stay on GPU; stage 23 is
     * the ACES fit plus the full AgX family (modes 1-5, the CPU's exact
     * math behind uTonemap); stage 15 is the highlights shoulder (knee
     * 0.75, the monotone shoulder and the uniform gain, the CPU's exact
     * math behind uHighlights — 0.0 is the CPU's own skip). Stage 24
     * (LUT) joined with the LUT port — CONDITIONALLY at runtime, the
     * one stage in the contract whose coverage rides a runtime fact:
     * the TAIL pass carries the CPU's exact role/transfer contract
     * (applyManagedLutStage's per-role order, the managed
     * encode/sample/decode chain) behind uLutStrength, sampling the
     * parsed .cube through a 3D RGBA16F texture (hardware trilinear,
     * the CPU's texel-center mapping), so a LUT grade is covered ONLY
     * when the device's LUT texture path is healthy —
     * [fragmentUncoveredStages]' lutTexturePathOk carries that runtime
     * fact, and while it is false the grade falls back with the lut
     * token (the honest gate, never a silent skip: the LUT port's own
     * bounded-error rule). Stages 21 and 22 (sharpness, detail) joined
     * with the sharpness/detail port: the four unsharp passes carry
     * the CPU's exact unsharpLumaImpl (radius 1.5 and 0.75, the H+V
     * blur pairs, the original-luma guide, the amount mapping with
     * NEGATIVE softening valid) behind uSharpness/uDetail, so an active
     * sharpness/detail grade no longer forces the CPU path — the
     * develop pipeline is 100% fragment-covered modulo the ONE runtime
     * leg. Nothing else in the 24-stage CPU contract falls back to CPU
     * when active (see [fragmentUncoveredStages]).
     */
    val FRAGMENT_COVERED_STAGES: Set<Int> =
        setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

    /**
     * The fragment path's honest uncovered list, as stage number ->
     * short reason token used in the fallback line (deliverable D's
     * no-silent-skip rule: an active effect the GPU does not cover makes
     * the whole frame take the CPU path, and the line says which stage
     * forced it). Mirrors GlesShaderSources' "Stages NOT covered" doc
     * and the CPU guards in core/src/Develop.cpp exactly — the same
     * field != neutral / toggle checks the CPU runs. Stages 2 and 3
     * left this list when they were ported into the fragment pass: the
     * shader applies stage 2 only when the uniform gate says the clip
     * has a map (the CPU's own guard, computed CPU-side — see
     * [fragmentShadingApplies]) and stage 3 when the grade toggles it,
     * so coverage no longer depends on the clip's metadata shape.
     * Stage 23 left it with the tonemap port: the shader carries the
     * ACES fit and every AgX mode (1-5) behind uTonemap, the CPU's
     * exact math, so an active tonemap grade no longer forces the CPU
     * path. Stage 15 left it with the highlights port: the shader
     * carries the CPU stage-15 shoulder (knee 0.75, the monotone
     * shoulder, the uniform gain) behind uHighlights, so an active
     * highlights grade no longer forces the CPU path. The ten pointwise
     * grade stages (9-13, 16-20) left it with the pointwise port: the
     * shader carries each CPU loop verbatim behind its raw-value
     * uniform, so a multi-control grade no longer forces the CPU path
     * per control. Stages 7 and 8 left it with the spatial-NR port:
     * the NR passes carry the CPU's exact chroma two-direction filter
     * (two passes, original luma guide preserved) and the CPU's exact
     * 3x3 luma bilateral behind uChromaDenoise/uNoiseReduction, so an
     * active chroma-denoise/noise-reduction grade no longer forces the
     * CPU path. Stages 21 and 22 (sharpness, detail) left it with the
     * sharpness/detail port: the four unsharp passes carry the CPU's
     * exact unsharpLumaImpl behind uSharpness/uDetail (the raw values,
     * NO clamp — negative softening valid), so an active sharpness/
     * detail grade no longer forces the CPU path. Stage 24 (LUT) left
     * it with the LUT port — the ONLY conditional leave in the
     * contract: the shader carries the CPU's exact managed-LUT math,
     * so 24 is covered whenever the device's LUT texture path is
     * healthy, and the lut TOKEN lives in
     * [fragmentUncoveredStages]' runtime leg (lutTexturePathOk), NOT
     * in this map — the map stays the unconditionally-uncovered list
     * so the covered/uncovered disjointness lock keeps its exact
     * meaning. When the path is unavailable (format/filter), the
     * session latches unhealthy and the job falls back with the lut
     * token — loudly, never a silent drop of the look. THE MAP IS NOW
     * EMPTY: every stage in developCpu's 24-stage contract is covered
     * by the fragment path (the sharpness/detail port closed the last
     * unconditional gaps), so the ONLY fallback left anywhere in the
     * develop pipeline is the LUT runtime leg.
     */
    val FRAGMENT_UNCOVERED_STAGES: Map<Int, String> = linkedMapOf()

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
     * Stages 2, 3, 7, 8, 15, 21, 22, 23 and the ten pointwise grade stages
     * (9-13, 16-20) are no longer listed: all of them are covered by the
     * fragment path. Stage 2's map-state guard moved into the shader's
     * uniform (uHasShading — the CPU-side gate computed where the map
     * state is known; see [fragmentShadingApplies]), stage 3's toggle
     * moved into uHr, stage 15's strength moved into uHighlights (the
     * CPU's exact shoulder math), and stage 23's mode moved into uTonemap
     * (the ACES fit and every AgX look, the CPU's exact math). The ten
     * pointwise stages moved with the pointwise port: each CPU loop's
     * exact math sits behind its raw-value uniform (the 16/17 asShot
     * half hoisted to uWbManual, the 19 split gate to uSplitActive), so
     * an active black-point/white-point/shadows/midtones/contrast/
     * temperature/tint/color-wheel/split-tone/saturation grade is NOT
     * an uncovered-stage fallback anymore. Stages 7 and 8 moved with the
     * spatial-NR port: the NR passes carry the CPU's exact chroma
     * two-direction filter (two passes, the vertical pass reading the
     * ORIGINAL pre-NR luma as its guide) and the CPU's exact 3x3 luma
     * bilateral behind uChromaDenoise/uNoiseReduction (the CPU's own
     * 0.0-skip and w/h guards mirrored), so an active chroma-denoise/
     * noise-reduction grade is NOT an uncovered-stage fallback anymore.
     * Stages 21 and 22 moved with the sharpness/detail port: the four
     * unsharp passes carry the CPU's exact unsharpLumaImpl (radius 1.5
     * and 0.75, the H+V blur pairs, the original-luma guide, the amount
     * mapping with NEGATIVE softening valid) behind uSharpness/uDetail,
     * so an active sharpness/detail grade is NOT an uncovered-stage
     * fallback anymore — the develop pipeline is 100% fragment-covered
     * modulo the ONE runtime leg.
     * Stage 24 (LUT) is the RUNTIME-CONDITIONAL shape: the TAIL pass
     * carries the CPU's exact managed-LUT contract, so an active LUT
     * grade is covered ONLY when the device's LUT texture path is
     * healthy — [lutTexturePathOk] is the caller's runtime fact (the
     * session's 3D RGBA16F texture path check), DEFAULT FALSE so a LUT
     * grade keeps taking the CPU path with the honest lut token until
     * the dispatch site passes the real fact. That default is the
     * no-silent-skip rule in action: the JNI seam carries the LUT
     * identity/strength, and a GPU request that could not apply the
     * LUT must fall back loudly, never drop the look silently.
     */
    fun fragmentUncoveredStages(
        grade: GradeK,
        lutTexturePathOk: Boolean = false,
    ): List<Pair<Int, String>> {
        val active = mutableListOf<Pair<Int, String>>()
        if (grade.lutId != null && grade.lutStrength > 0f && !lutTexturePathOk) {
            active += 24 to "lut"
        }
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
     * [lutTexturePathOk] rides through to [fragmentUncoveredStages]: the
     * runtime fact that the device's LUT texture path is healthy, with
     * the same honest default-false contract (a LUT grade stays on CPU
     * until the dispatch site knows the path is real).
     */
    fun fragmentEffectiveBackend(
        requested: RenderBackend,
        gates: BackendGates,
        grade: GradeK,
        gyroWarpActive: Boolean,
        lutTexturePathOk: Boolean = false,
    ): EffectiveRenderBackend {
        if (requested == RenderBackend.CPU) return EffectiveRenderBackend.CPU
        if (!gates.fragmentAllowed()) return EffectiveRenderBackend.CPU
        if (fragmentUncoveredStages(grade, lutTexturePathOk).isNotEmpty()) {
            return EffectiveRenderBackend.CPU
        }
        if (fragmentUncoveredPreStages(grade).isNotEmpty()) return EffectiveRenderBackend.CPU
        if (gyroWarpActive) return EffectiveRenderBackend.CPU
        return EffectiveRenderBackend.GLES
    }

    /**
     * The fragment leg's honest fallback line: null when the fragment path
     * runs (or CPU was requested). The reason names the missing fragment
     * gates OR the active uncovered stages — whichever forced the CPU
     * path, never silently. Format matches [fallbackLine] so the export
     * log reads uniformly. [lutTexturePathOk] rides through with the same
     * default-false contract as [fragmentEffectiveBackend], so a LUT
     * grade's fallback line names the lut token until the runtime fact
     * says the texture path is healthy.
     */
    fun fragmentFallbackLine(
        requested: RenderBackend,
        gates: BackendGates,
        grade: GradeK,
        gyroWarpActive: Boolean,
        lutTexturePathOk: Boolean = false,
    ): String? {
        if (requested == RenderBackend.CPU) return null
        if (fragmentEffectiveBackend(requested, gates, grade, gyroWarpActive, lutTexturePathOk) ==
            EffectiveRenderBackend.GLES
        ) {
            return null
        }
        val reasons = mutableListOf<String>()
        gates.fragmentFallbackReason()?.let { reasons += it }
        reasons += fragmentUncoveredStages(grade, lutTexturePathOk).map { it.second }
        reasons += fragmentUncoveredPreStages(grade)
        if (gyroWarpActive) reasons += "gyro-warp"
        return "render backend fallback: requested=${requested.name} " +
            "effective=CPU reason=${reasons.joinToString(",")}"
    }
}
