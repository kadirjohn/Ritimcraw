package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Render-backend axis tests (owner request 2026-11-12, deliverable B): the
 * requested enum's wire contract, the effective-backend decision rules,
 * and the fallback-reason/fallback-line honesty rules (§14). Pure JVM —
 * no Android framework types anywhere in the decision path.
 */
class RenderBackendTest {

    // ---- wire contract ----

    @Test
    fun default_is_cpu() {
        assertEquals(RenderBackend.CPU, RenderBackend.DEFAULT)
    }

    @Test
    fun from_wire_parses_every_level() {
        RenderBackend.entries.forEach { level ->
            assertEquals(level, RenderBackend.fromWire(level.name))
        }
    }

    @Test
    fun from_wire_null_blank_and_unknown_resolve_to_default() {
        assertEquals(RenderBackend.DEFAULT, RenderBackend.fromWire(null))
        assertEquals(RenderBackend.DEFAULT, RenderBackend.fromWire(""))
        assertEquals(RenderBackend.DEFAULT, RenderBackend.fromWire(" "))
        // A future build's value this one does not know: opens at the
        // default, never crashes.
        assertEquals(RenderBackend.DEFAULT, RenderBackend.fromWire("GLES2"))
        assertEquals(RenderBackend.DEFAULT, RenderBackend.fromWire("auto"))
    }

    @Test
    fun wire_name_is_the_store_key() {
        assertEquals("renderBackend", RenderBackend.WIRE_NAME)
    }

    // ---- effective-backend decision rules (§14) ----

    @Test
    fun cpu_requested_is_always_cpu() {
        // CPU is the reference path and is never gated — even with every
        // gate green, requesting CPU runs CPU.
        val allGreen = BackendGates(
            contextOk = true, computeOk = true, floatFboOk = true, parityOk = true,
        )
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.effectiveBackend(RenderBackend.CPU, allGreen),
        )
    }

    @Test
    fun auto_and_gpu_run_gles_only_when_every_gate_passes() {
        val allGreen = BackendGates(
            contextOk = true, computeOk = true, floatFboOk = true, parityOk = true,
        )
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.effectiveBackend(RenderBackend.AUTO, allGreen),
        )
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.effectiveBackend(RenderBackend.GPU_EXPERIMENTAL, allGreen),
        )
    }

    @Test
    fun auto_falls_back_to_cpu_when_any_gate_fails() {
        // Each failing gate alone forces the CPU fallback (all-false is
        // the honest JVM default: a probe that never ran).
        val combos = listOf(
            BackendGates(contextOk = false, computeOk = true, floatFboOk = true, parityOk = true),
            BackendGates(contextOk = true, computeOk = false, floatFboOk = true, parityOk = true),
            BackendGates(contextOk = true, computeOk = true, floatFboOk = false, parityOk = true),
            BackendGates(contextOk = true, computeOk = true, floatFboOk = true, parityOk = false),
        )
        combos.forEach { gates ->
            assertEquals(
                "gates=$gates",
                EffectiveRenderBackend.CPU,
                BackendRules.effectiveBackend(RenderBackend.AUTO, gates),
            )
        }
    }

    @Test
    fun gpu_experimental_falls_back_to_cpu_with_the_same_gate() {
        // AUTO and GPU_EXPERIMENTAL share the gate; the difference is
        // intent, not rule (§14: GPU requests fall back and report
        // honestly rather than failing).
        val noContext = BackendGates(contextOk = false)
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.effectiveBackend(RenderBackend.GPU_EXPERIMENTAL, noContext),
        )
    }

    @Test
    fun default_gates_keep_everything_on_cpu() {
        // The all-false default is the "probe never ran" state: nothing
        // runs GPU until a real device probe (and parity gate) says so.
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.effectiveBackend(RenderBackend.AUTO, BackendGates()),
        )
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.effectiveBackend(RenderBackend.GPU_EXPERIMENTAL, BackendGates()),
        )
    }

    // ---- fallback honesty (§14: logged, never silent) ----

    @Test
    fun fallback_reason_lists_every_missing_gate() {
        val none = BackendGates()
        assertEquals(
            "no-egl-context,no-compute,no-float-fbo,parity-not-verified",
            none.fallbackReason(),
        )
    }

    @Test
    fun fallback_reason_is_null_when_gpu_allowed() {
        val allGreen = BackendGates(
            contextOk = true, computeOk = true, floatFboOk = true, parityOk = true,
        )
        assertNull(allGreen.fallbackReason())
    }

    @Test
    fun fallback_reason_names_only_the_missing_gates() {
        val onlyParity = BackendGates(
            contextOk = true, computeOk = true, floatFboOk = true, parityOk = false,
        )
        assertEquals("parity-not-verified", onlyParity.fallbackReason())
    }

    @Test
    fun fallback_line_is_null_for_cpu_and_for_running_gpu() {
        val allGreen = BackendGates(
            contextOk = true, computeOk = true, floatFboOk = true, parityOk = true,
        )
        // CPU requested: no fallback happened.
        assertNull(BackendRules.fallbackLine(RenderBackend.CPU, BackendGates()))
        // GPU paths that actually got the GPU: nothing to report.
        assertNull(BackendRules.fallbackLine(RenderBackend.AUTO, allGreen))
        assertNull(BackendRules.fallbackLine(RenderBackend.GPU_EXPERIMENTAL, allGreen))
    }

    @Test
    fun fallback_line_carries_requested_effective_and_reason() {
        val line = BackendRules.fallbackLine(RenderBackend.GPU_EXPERIMENTAL, BackendGates())
        assertTrue("requested missing: $line", line!!.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("effective missing: $line", line.contains("effective=CPU"))
        assertTrue("reason missing: $line", line.contains("reason=no-egl-context"))
    }

    @Test
    fun gpu_allowed_requires_all_four_gates() {
        assertTrue(BackendGates(true, true, true, true).gpuAllowed())
        // Each single failure flips the whole decision.
        listOf(
            BackendGates(false, true, true, true),
            BackendGates(true, false, true, true),
            BackendGates(true, true, false, true),
            BackendGates(true, true, true, false),
        ).forEach { gates ->
            assertTrue("gates=$gates", !gates.gpuAllowed())
        }
    }

    // ---- fragment leg (deliverable D): gate + stage coverage ----

    /** Every fragment gate green, compute deliberately absent. */
    private fun fragmentGates() = BackendGates(
        contextOk = true, computeOk = false, floatFboOk = true, parityOk = true,
    )

    /** A grade with every uncovered stage off (the fragment-covered shape). */
    private fun coveredGrade() = GradeK(
        keepVignetting = false,
        highlightReconstruction = false,
    )

    @Test
    fun fragment_allowed_needs_no_compute() {
        // The fragment chain is a draw pipeline: context + float FBO +
        // parity only. A compute-less ES 3.1 device can still run it.
        assertTrue(fragmentGates().fragmentAllowed())
    }

    @Test
    fun fragment_allowed_requires_context_float_fbo_and_parity() {
        listOf(
            BackendGates(contextOk = false, computeOk = false, floatFboOk = true, parityOk = true),
            BackendGates(contextOk = true, computeOk = false, floatFboOk = false, parityOk = true),
            BackendGates(contextOk = true, computeOk = false, floatFboOk = true, parityOk = false),
        ).forEach { gates ->
            assertTrue("gates=$gates", !gates.fragmentAllowed())
        }
    }

    @Test
    fun fragment_fallback_reason_lists_only_fragment_gates() {
        // All-false gates: the fragment reason names its own three, never
        // no-compute (compute is not a fragment gate).
        assertEquals(
            "no-egl-context,no-float-fbo,parity-not-verified",
            BackendGates().fragmentFallbackReason(),
        )
        assertNull(fragmentGates().fragmentFallbackReason())
    }

    @Test
    fun fragment_uncovered_stages_match_the_cpu_guards() {
        // The map is the single uncovered list the line tokens come from.
        assertEquals(19, BackendRules.FRAGMENT_UNCOVERED_STAGES.size)
        assertEquals(
            setOf(2, 3, 7, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24),
            BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
        )
        // Covered + uncovered = the full 24-stage contract, no overlap.
        assertTrue(
            BackendRules.FRAGMENT_COVERED_STAGES.intersect(
                BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
            ).isEmpty(),
        )
    }

    @Test
    fun default_grade_has_active_uncovered_stages() {
        // The DEFAULT grade runs stages 2+3 (both toggles default true),
        // so the fragment leg honestly falls back to CPU — the mission's
        // own pinned example of the no-silent-skip rule.
        val active = BackendRules.fragmentUncoveredStages(GradeK())
        assertEquals(
            listOf(2 to "lens-shading", 3 to "highlight-reconstruction"),
            active,
        )
    }

    @Test
    fun covered_grade_has_no_uncovered_stages() {
        assertTrue(BackendRules.fragmentUncoveredStages(coveredGrade()).isEmpty())
        assertTrue(BackendRules.fragmentUncoveredPreStages(coveredGrade()).isEmpty())
    }

    @Test
    fun every_uncovered_stage_guard_mirrors_the_cpu() {
        // One grade per stage: the field that activates CPU stage N must
        // activate the Kotlin rule's stage N (same guard, same number).
        val cases = listOf(
            2 to GradeK(keepVignetting = true),
            3 to GradeK(highlightReconstruction = true),
            7 to GradeK(chromaDenoise = 25f),
            8 to GradeK(noiseReduction = 25f),
            9 to GradeK(blackPoint = 5f),
            10 to GradeK(whitePoint = 5f),
            11 to GradeK(shadows = 5f),
            12 to GradeK(midtones = 5f),
            13 to GradeK(contrast = 5f),
            15 to GradeK(highlights = 5f),
            16 to GradeK(asShot = false, tempK = 6500f),
            17 to GradeK(asShot = false, tint = 5f),
            18 to GradeK(wheelShadowX = 0.2f),
            19 to GradeK(splitShadowHue = 30f),
            20 to GradeK(saturation = 5f),
            21 to GradeK(sharpness = 5f),
            22 to GradeK(detail = 5f),
            23 to GradeK(tonemap = 1),
            24 to GradeK(lutId = "l1", lutStrength = 100f),
        )
        cases.forEach { (stage, grade) ->
            val active = BackendRules.fragmentUncoveredStages(grade).map { it.first }
            assertTrue("stage $stage not active for $grade", stage in active)
        }
        // And the neutral twins of the toggle stages stay inactive.
        assertTrue(
            BackendRules.fragmentUncoveredStages(
                GradeK(keepVignetting = false, highlightReconstruction = false),
            ).isEmpty(),
        )
    }

    @Test
    fun as_shot_grade_with_manual_values_still_covers_temp_and_tint() {
        // CPU stages 16-17 are guarded by !asShot: manual temp/tint values
        // stored while asShot=true are inert (Develop.h), so the Kotlin
        // rule must NOT treat them as active.
        val active = BackendRules.fragmentUncoveredStages(
            GradeK(asShot = true, tempK = 6500f, tint = 5f),
        )
        assertTrue(16 !in active.map { it.first })
        assertTrue(17 !in active.map { it.first })
    }

    @Test
    fun lut_strength_zero_is_a_bypass_not_an_active_stage() {
        // lut selected but strength 0: the CPU stage is an exact bypass
        // (Develop.h COL-07), so the rule must not call it active.
        val active = BackendRules.fragmentUncoveredStages(
            GradeK(lutId = "l1", lutStrength = 0f),
        )
        assertTrue(24 !in active.map { it.first })
    }

    @Test
    fun temporal_nr_counts_as_uncovered_pre_stage() {
        val active = BackendRules.fragmentUncoveredPreStages(
            GradeK(temporalNr = true, temporalStrength = 30f),
        )
        assertEquals(listOf("temporal-nr"), active)
        // Toggle off (the default): inert, not active.
        assertTrue(
            BackendRules.fragmentUncoveredPreStages(
                GradeK(temporalNr = false, temporalStrength = 30f),
            ).isEmpty(),
        )
    }

    @Test
    fun fragment_effective_backend_gles_only_when_gates_and_coverage_align() {
        // Gates green + every uncovered stage off: the fragment leg runs.
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), coveredGrade(),
                gyroWarpActive = false,
            ),
        )
        // Default grade (stages 2+3 active): CPU, honestly.
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), GradeK(),
                gyroWarpActive = false,
            ),
        )
        // Gates red: CPU even for a covered grade.
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, BackendGates(), coveredGrade(),
                gyroWarpActive = false,
            ),
        )
        // CPU requested: always CPU, never gated.
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.CPU, fragmentGates(), coveredGrade(),
                gyroWarpActive = false,
            ),
        )
        // Gyro warp attached: CPU (the warp is not in the fragment pass).
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), coveredGrade(),
                gyroWarpActive = true,
            ),
        )
    }

    @Test
    fun fragment_fallback_line_names_gates_and_stages_honestly() {
        // Gates red + default grade: the line names BOTH the missing gates
        // and the active uncovered stages — never silent.
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(), GradeK(),
            gyroWarpActive = false,
        )!!
        assertTrue("requested missing: $line", line.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("effective missing: $line", line.contains("effective=CPU"))
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
        assertTrue("stage reason missing: $line", line.contains("lens-shading"))
        assertTrue("stage reason missing: $line", line.contains("highlight-reconstruction"))
    }

    @Test
    fun fragment_fallback_line_names_gyro_warp_when_attached() {
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), coveredGrade(),
            gyroWarpActive = true,
        )!!
        assertTrue("gyro-warp missing: $line", line.contains("gyro-warp"))
    }

    @Test
    fun fragment_fallback_line_is_null_for_cpu_and_for_running_fragment() {
        // CPU requested: no fallback happened.
        assertNull(
            BackendRules.fragmentFallbackLine(
                RenderBackend.CPU, BackendGates(), GradeK(),
                gyroWarpActive = false,
            ),
        )
        // Fragment path actually running: nothing to report.
        assertNull(
            BackendRules.fragmentFallbackLine(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), coveredGrade(),
                gyroWarpActive = false,
            ),
        )
    }

    @Test
    fun fragment_rules_never_weaken_the_locked_compute_gate() {
        // The compute leg's four-gate math is LOCKED: the fragment additions
        // must not change it. Same inputs, same answers as before (d).
        assertTrue(BackendGates(true, true, true, true).gpuAllowed())
        assertTrue(!BackendGates(true, false, true, true).gpuAllowed())
        assertEquals(
            "no-egl-context,no-compute,no-float-fbo,parity-not-verified",
            BackendGates().fallbackReason(),
        )
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.effectiveBackend(RenderBackend.AUTO, BackendGates()),
        )
    }
}
