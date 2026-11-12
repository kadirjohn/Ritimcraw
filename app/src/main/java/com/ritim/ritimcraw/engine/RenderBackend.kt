package com.ritim.ritimcraw.engine

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
}
