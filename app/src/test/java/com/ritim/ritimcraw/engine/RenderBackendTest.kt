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
        // The map is the single UNCONDITIONALLY-uncovered list the line
        // tokens come from. Stages 2, 3, 15 and 23 left it when they were
        // ported into the fragment pass (they are covered now, map-state
        // gate, the stage-15 highlights shoulder and all five tonemap
        // modes included). The ten pointwise grade stages (9-13, 16-20)
        // left it with the pointwise port: each CPU loop's exact math sits
        // behind its raw-value uniform in the shader. Stages 7 and 8 left
        // it with the spatial-NR port: the NR passes carry the CPU's exact
        // chroma two-direction filter (two passes, original luma guide)
        // and the CPU's exact 3x3 luma bilateral behind
        // uChromaDenoise/uNoiseReduction. Stages 21 and 22 (sharpness,
        // detail) left it with the sharpness/detail port — the CPU's exact
        // separable unsharp (H/V blur passes + the high-pass difference
        // fused into the V pass) behind uSharpness/uDetail. THE MAP IS NOW
        // EMPTY: every one of developCpu's 24 stages is fragment-covered,
        // so the map keeps meaning "uncovered, period" and the disjointness
        // lock below holds trivially. The ONLY fallback left anywhere in
        // the develop pipeline is the RUNTIME lutTexturePathOk leg (the
        // lut coverage tests below pin that shape).
        assertEquals(0, BackendRules.FRAGMENT_UNCOVERED_STAGES.size)
        assertTrue(BackendRules.FRAGMENT_UNCOVERED_STAGES.isEmpty())
        // Covered + uncovered = the full 24-stage contract, no overlap.
        assertTrue(
            BackendRules.FRAGMENT_COVERED_STAGES.intersect(
                BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
            ).isEmpty(),
        )
        // The covered set IS the full contract now.
        assertEquals(
            (1..24).toSet(),
            BackendRules.FRAGMENT_COVERED_STAGES,
        )
    }

    @Test
    fun default_grade_covers_the_fragment_path() {
        // The DEFAULT grade (keepVignetting + highlightReconstruction both
        // true) used to force the CPU fallback; stages 2+3 are ported into
        // the fragment pass now, so a default grade with NO other edits
        // has NO active uncovered stage — the pinned new truth this
        // mission exists for (a default-grade GPU request stays on GPU).
        assertTrue(BackendRules.fragmentUncoveredStages(GradeK()).isEmpty())
        assertTrue(BackendRules.fragmentUncoveredPreStages(GradeK()).isEmpty())
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), GradeK(),
                gyroWarpActive = false,
            ),
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
        // Stages 2, 3, 7, 8, 15, 21, 22, 23 and the ten pointwise grade
        // stages (9-13, 16-20) are gone from the list (covered by the
        // fragment path), so their cases are gone with them. The ONLY
        // remaining runtime-conditional activation is stage 24's LUT leg.
        val cases = listOf(
            24 to GradeK(lutId = "l1", lutStrength = 100f),
        )
        cases.forEach { (stage, grade) ->
            val active = BackendRules.fragmentUncoveredStages(grade).map { it.first }
            assertTrue("stage $stage not active for $grade", stage in active)
        }
        // And the fully neutral grade (every uncovered field at its
        // neutral value) stays inactive.
        assertTrue(
            BackendRules.fragmentUncoveredStages(
                GradeK(keepVignetting = false, highlightReconstruction = false),
            ).isEmpty(),
        )
    }

    @Test
    fun pointwise_grades_cover_the_fragment_path() {
        // The ten pointwise grade stages (9-13, 16-20) are ported into the
        // fragment pass (the CPU loops' exact math behind raw-value
        // uniforms, the 16/17 asShot gate hoisted to uWbManual and the 19
        // split gate to uSplitActive), so each control's own grade — the
        // exact shape its parity case measures — is NOT an
        // uncovered-stage fallback anymore: gates green -> the fragment
        // leg runs.
        val cases = listOf(
            "black-point" to GradeK(blackPoint = 5f),
            "white-point" to GradeK(whitePoint = 5f),
            "shadows" to GradeK(shadows = -5f),
            "midtones" to GradeK(midtones = 5f),
            "contrast" to GradeK(contrast = 5f),
            "temperature" to GradeK(asShot = false, tempK = 6500f),
            "tint" to GradeK(asShot = false, tint = 5f),
            "color-wheel" to GradeK(wheelShadowX = 0.2f),
            "split-tone" to GradeK(splitShadowHue = 30f),
            "saturation" to GradeK(saturation = 5f),
        )
        cases.forEach { (name, grade) ->
            assertTrue(
                "$name must be covered",
                BackendRules.fragmentUncoveredStages(grade).isEmpty(),
            )
            assertTrue(BackendRules.fragmentUncoveredPreStages(grade).isEmpty())
            assertEquals(
                "$name must run the fragment leg",
                EffectiveRenderBackend.GLES,
                BackendRules.fragmentEffectiveBackend(
                    RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                    gyroWarpActive = false,
                ),
            )
        }
    }

    @Test
    fun pointwise_tokens_are_gone_from_the_fallback_vocabulary() {
        // Coverage table, vocabulary leg: the ten pointwise stage numbers
        // and their reason TOKENS are gone from the uncovered map, and a
        // gates-red multi-control grade produces a fallback line naming
        // ONLY the gates — no per-control token left to force it.
        val goneKeys = setOf(9, 10, 11, 12, 13, 16, 17, 18, 19, 20)
        goneKeys.forEach { stage ->
            assertTrue(
                "stage $stage must be covered",
                stage in BackendRules.FRAGMENT_COVERED_STAGES,
            )
            assertTrue(
                "stage $stage must be gone from the uncovered map",
                stage !in BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
            )
        }
        val goneTokens = listOf(
            "black-point", "white-point", "shadows", "midtones", "contrast",
            "temperature", "tint", "color-wheel", "split-tone", "saturation",
        )
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(),
            GradeK(
                blackPoint = 5f, whitePoint = 5f, shadows = -5f,
                midtones = 5f, contrast = 5f, asShot = false, tempK = 6500f,
                tint = 5f, wheelShadowX = 0.2f, splitShadowHue = 30f,
                saturation = 5f,
            ),
            gyroWarpActive = false,
        )!!
        goneTokens.forEach { token ->
            assertTrue("$token must be gone: $line", !line.contains(token))
        }
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
    }

    @Test
    fun a_multi_control_grade_stays_gpu_eligible() {
        // The mission's own gate matrix: a grade with several of the ten
        // pointwise controls active at once (the owner's device-test
        // shape — shadows + temperature + saturation, plus the routine
        // highlights recovery on top) is GPU-ELIGIBLE: every active stage
        // is covered, so the fragment leg takes the whole frame. Before
        // the pointwise port this exact grade forced the CPU path three
        // times over.
        val grade = GradeK(
            shadows = -5f,
            asShot = false,
            tempK = 6500f,
            saturation = 5f,
            highlights = -55f,
        )
        assertTrue(
            BackendRules.fragmentUncoveredStages(grade).isEmpty(),
        )
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                gyroWarpActive = false,
            ),
        )
    }

    @Test
    fun tonemap_grade_covers_the_fragment_path() {
        // Stage 23 is ported into the fragment pass (the ACES fit and every
        // AgX mode behind uTonemap), so a tonemap-only grade — the exact
        // shape the parity harness's case 2 measures — is NOT an
        // uncovered-stage fallback anymore: gates green -> the fragment
        // leg runs.
        listOf(1, 2, 3, 4, 5).forEach { mode ->
            val grade = GradeK(tonemap = mode)
            assertTrue(
                "tonemap mode $mode must be covered",
                BackendRules.fragmentUncoveredStages(grade).isEmpty(),
            )
            assertTrue(BackendRules.fragmentUncoveredPreStages(grade).isEmpty())
            assertEquals(
                EffectiveRenderBackend.GLES,
                BackendRules.fragmentEffectiveBackend(
                    RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                    gyroWarpActive = false,
                ),
            )
        }
    }

    @Test
    fun highlights_grade_covers_the_fragment_path() {
        // Stage 15 is ported into the fragment pass (the CPU stage-15
        // shoulder — knee 0.75, the monotone shoulder, the uniform gain —
        // behind uHighlights), so a highlights-only grade — the owner's
        // routine recovery shape — is NOT an uncovered-stage fallback
        // anymore: gates green -> the fragment leg runs. Both directions
        // of the knob (recovery negative, lift positive) must pass.
        listOf(-55f, 55f).forEach { highlights ->
            val grade = GradeK(highlights = highlights)
            assertTrue(
                "highlights $highlights must be covered",
                BackendRules.fragmentUncoveredStages(grade).isEmpty(),
            )
            assertTrue(BackendRules.fragmentUncoveredPreStages(grade).isEmpty())
            assertEquals(
                EffectiveRenderBackend.GLES,
                BackendRules.fragmentEffectiveBackend(
                    RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                    gyroWarpActive = false,
                ),
            )
        }
    }

    @Test
    fun spatial_nr_grades_cover_the_fragment_path() {
        // Stages 7 and 8 are ported into the fragment path (the CPU's exact
        // chroma two-direction filter as TWO passes with the original luma
        // guide preserved, and the CPU's exact 3x3 luma bilateral, behind
        // uChromaDenoise/uNoiseReduction), so an NR grade — the owner's
        // routine shape whose only fallback blockers were these two stages
        // — is NOT an uncovered-stage fallback anymore: gates green -> the
        // fragment leg runs. Each knob alone and both together must pass.
        val cases = listOf(
            "chroma-denoise" to GradeK(chromaDenoise = 25f),
            "noise-reduction" to GradeK(noiseReduction = 25f),
            "both" to GradeK(chromaDenoise = 25f, noiseReduction = 25f),
        )
        cases.forEach { (name, grade) ->
            assertTrue(
                "$name must be covered",
                BackendRules.fragmentUncoveredStages(grade).isEmpty(),
            )
            assertTrue(BackendRules.fragmentUncoveredPreStages(grade).isEmpty())
            assertEquals(
                "$name must run the fragment leg",
                EffectiveRenderBackend.GLES,
                BackendRules.fragmentEffectiveBackend(
                    RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                    gyroWarpActive = false,
                ),
            )
        }
    }

    @Test
    fun spatial_nr_tokens_are_gone_from_the_fallback_vocabulary() {
        // Coverage table, vocabulary leg: the two spatial-NR stage numbers
        // and their reason TOKENS are gone from the uncovered map, and a
        // gates-red NR grade produces a fallback line naming ONLY the
        // gates — no NR token left to force it.
        setOf(7, 8).forEach { stage ->
            assertTrue(
                "stage $stage must be covered",
                stage in BackendRules.FRAGMENT_COVERED_STAGES,
            )
            assertTrue(
                "stage $stage must be gone from the uncovered map",
                stage !in BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
            )
        }
        val goneTokens = listOf("chroma-denoise", "noise-reduction")
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(),
            GradeK(chromaDenoise = 25f, noiseReduction = 25f),
            gyroWarpActive = false,
        )!!
        goneTokens.forEach { token ->
            assertTrue("$token must be gone: $line", !line.contains(token))
        }
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
    }

    @Test
    fun sharpness_detail_grades_cover_the_fragment_path() {
        // Stages 21 and 22 are ported into the fragment path (the CPU's
        // exact separable unsharp — the H/V blur passes with the CPU's
        // radius-specific kernel weights, the high-pass difference and the
        // amount blend fused into the V pass — behind uSharpness/
        // uDetail), so a sharpness/detail grade — the LAST routine-grade
        // GPU blockers — is NOT an uncovered-stage fallback anymore:
        // gates green -> the fragment leg runs. Each knob alone, both
        // together, and BOTH directions of the knobs (the CPU's amount
        // mapping keeps negative raws as valid softening) must pass.
        val cases = listOf(
            "sharpness" to GradeK(sharpness = 5f),
            "sharpness-negative" to GradeK(sharpness = -25f),
            "detail" to GradeK(detail = 5f),
            "detail-negative" to GradeK(detail = -25f),
            "both" to GradeK(sharpness = 5f, detail = 5f),
        )
        cases.forEach { (name, grade) ->
            assertTrue(
                "$name must be covered",
                BackendRules.fragmentUncoveredStages(grade).isEmpty(),
            )
            assertTrue(BackendRules.fragmentUncoveredPreStages(grade).isEmpty())
            assertEquals(
                "$name must run the fragment leg",
                EffectiveRenderBackend.GLES,
                BackendRules.fragmentEffectiveBackend(
                    RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                    gyroWarpActive = false,
                ),
            )
        }
    }

    @Test
    fun sharpness_detail_tokens_are_gone_from_the_fallback_vocabulary() {
        // Coverage table, vocabulary leg: the two unsharp stage numbers
        // and their reason TOKENS are gone from the uncovered map, and a
        // gates-red sharpness/detail grade produces a fallback line naming
        // ONLY the gates — no sharpness/detail token left to force it.
        setOf(21, 22).forEach { stage ->
            assertTrue(
                "stage $stage must be covered",
                stage in BackendRules.FRAGMENT_COVERED_STAGES,
            )
            assertTrue(
                "stage $stage must be gone from the uncovered map",
                stage !in BackendRules.FRAGMENT_UNCOVERED_STAGES.keys,
            )
        }
        val goneTokens = listOf("sharpness", "detail")
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(),
            GradeK(sharpness = 25f, detail = 25f),
            gyroWarpActive = false,
        )!!
        goneTokens.forEach { token ->
            assertTrue("$token must be gone: $line", !line.contains(token))
        }
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
    }

    @Test
    fun as_shot_grade_with_manual_values_still_covers_temp_and_tint() {
        // CPU stages 16-17 are guarded by !asShot: manual temp/tint values
        // stored while asShot=true are inert (Develop.h). Stages 16-17 are
        // covered by the fragment pass now, so the uncovered list never
        // names them either way — the asShot gate lives in the uWbManual
        // hoist (CPU-computed, bound 0 when asShot), which keeps the
        // stored-but-inert values out of the shader exactly as the CPU
        // loop's own guard does.
        val active = BackendRules.fragmentUncoveredStages(
            GradeK(asShot = true, tempK = 6500f, tint = 5f),
        )
        assertTrue(16 !in active.map { it.first })
        assertTrue(17 !in active.map { it.first })
    }

    @Test
    fun lut_strength_zero_is_a_bypass_not_an_active_stage() {
        // lut selected but strength 0: the CPU stage is an exact bypass
        // (Develop.h COL-07), so the rule must not call it active — on
        // EITHER side of the runtime gate (the path being healthy must
        // not turn a bypass into an active stage, and vice versa).
        listOf(false, true).forEach { pathOk ->
            val active = BackendRules.fragmentUncoveredStages(
                GradeK(lutId = "l1", lutStrength = 0f),
                lutTexturePathOk = pathOk,
            )
            assertTrue(24 !in active.map { it.first })
        }
    }

    @Test
    fun lut_grade_falls_back_with_the_token_until_the_texture_path_is_healthy() {
        // Stage 24 is the RUNTIME-CONDITIONAL leave: the shader carries
        // the CPU's exact managed-LUT math (the covered set says so), so
        // a LUT grade is covered ONLY when the device's LUT texture path
        // is healthy. The DEFAULT is the honest false — until the
        // dispatch site passes the real runtime fact, a LUT grade must
        // take the CPU path with the lut token LOUDLY (never a silent
        // GPU run that would drop the look: the JNI seam carries the LUT
        // identity/strength, and commit (a)'s sessions default to the
        // neutral no-LUT shape).
        val lutGrade = GradeK(lutId = "l1", lutStrength = 100f)
        // The static map no longer carries the token — it fires from the
        // runtime leg only.
        assertTrue(24 !in BackendRules.FRAGMENT_UNCOVERED_STAGES.keys)
        assertTrue(24 in BackendRules.FRAGMENT_COVERED_STAGES)
        // Default (path not known healthy): 24 active with the lut token,
        // effective CPU, the fallback line names the token.
        val active = BackendRules.fragmentUncoveredStages(lutGrade)
        assertEquals(listOf(24 to "lut"), active)
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), lutGrade,
                gyroWarpActive = false,
            ),
        )
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), lutGrade,
            gyroWarpActive = false,
        )!!
        assertTrue("lut token missing: $line", line.contains("lut"))
        // Path healthy: 24 covered — no active uncovered stage, the
        // fragment leg takes the frame.
        assertTrue(
            BackendRules.fragmentUncoveredStages(
                lutGrade, lutTexturePathOk = true,
            ).isEmpty(),
        )
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), lutGrade,
                gyroWarpActive = false, lutTexturePathOk = true,
            ),
        )
        assertNull(
            BackendRules.fragmentFallbackLine(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), lutGrade,
                gyroWarpActive = false, lutTexturePathOk = true,
            ),
        )
    }

    @Test
    fun lut_grade_stays_covered_alongside_other_controls_when_the_path_is_healthy() {
        // The owner's device-test shape: a LUT on top of a routine grade
        // (the mission's whole point — LUT was the last routine-grade
        // GPU blocker). With the texture path healthy the whole grade
        // runs the fragment leg; with the default-false path fact the
        // SAME grade falls back with the lut token named.
        val grade = GradeK(
            lutId = "l1",
            lutStrength = 80f,
            exposureEv = 0.5f,
            highlights = -55f,
            saturation = 5f,
            tonemap = 2,
        )
        assertEquals(
            EffectiveRenderBackend.GLES,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                gyroWarpActive = false, lutTexturePathOk = true,
            ),
        )
        assertEquals(
            EffectiveRenderBackend.CPU,
            BackendRules.fragmentEffectiveBackend(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
                gyroWarpActive = false,
            ),
        )
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), grade,
            gyroWarpActive = false,
        )!!
        assertTrue("lut token missing: $line", line.contains("lut"))
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
        // Default grade (stages 2+3 toggles true): GLES too now — both
        // stages are covered, so the default grade no longer forces CPU.
        assertEquals(
            EffectiveRenderBackend.GLES,
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
        // Gates red + a grade with an active uncovered stage: the line
        // names BOTH the missing gates and the active stages — never
        // silent. (The default grade no longer carries stage tokens —
        // stages 2+3 are covered — and chroma-denoise/noise-reduction
        // left the uncovered map with the spatial-NR port, and
        // sharpness/detail left it with the sharpness/detail port, so the
        // stage leg of this test uses the ONE shape that genuinely has an
        // active runtime leg: a LUT grade with the texture path
        // unavailable.)
        val grade = GradeK(lutId = "l1", lutStrength = 100f)
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(), grade,
            gyroWarpActive = false,
        )!!
        assertTrue("requested missing: $line", line.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("effective missing: $line", line.contains("effective=CPU"))
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
        assertTrue("stage reason missing: $line", line.contains("lut"))
    }

    @Test
    fun fragment_fallback_line_names_no_stage_for_the_default_grade() {
        // The pinned new truth: gates green + default grade -> the
        // fragment path runs, no fallback line at all. Gates red +
        // default grade -> the line names ONLY the gates (the grade
        // contributes no stage token anymore).
        assertNull(
            BackendRules.fragmentFallbackLine(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(), GradeK(),
                gyroWarpActive = false,
            ),
        )
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(), GradeK(),
            gyroWarpActive = false,
        )!!
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
        assertTrue("lens-shading must be gone: $line", !line.contains("lens-shading"))
        assertTrue(
            "highlight-reconstruction must be gone: $line",
            !line.contains("highlight-reconstruction"),
        )
    }

    @Test
    fun fragment_fallback_line_names_no_stage_for_a_highlights_grade() {
        // Stage 15 joined the covered set: a highlights-only grade (the
        // owner's routine recovery shape) contributes NO stage token to
        // the fallback line — gates red + highlights grade -> the line
        // names ONLY the gates, the same vocabulary rule the default
        // grade rides.
        assertNull(
            BackendRules.fragmentFallbackLine(
                RenderBackend.GPU_EXPERIMENTAL, fragmentGates(),
                GradeK(highlights = -55f),
                gyroWarpActive = false,
            ),
        )
        val line = BackendRules.fragmentFallbackLine(
            RenderBackend.GPU_EXPERIMENTAL, BackendGates(),
            GradeK(highlights = -55f),
            gyroWarpActive = false,
        )!!
        assertTrue("gate reason missing: $line", line.contains("no-egl-context"))
        assertTrue("highlights must be gone: $line", !line.contains("highlights"))
    }

    @Test
    fun fragment_shading_applies_mirrors_the_cpu_stage2_gate() {
        // The fragment path's stage-2 APPLICATION gate, as a truth table:
        // keepVignetting && the clip's map present. The map state is the
        // caller's fact (the C++ harness reads the clip's RawMeta); the
        // CPU path keeps the same guard inside developCpu.
        assertTrue(
            BackendRules.fragmentShadingApplies(GradeK(), hasShadingMap = true),
        )
        assertTrue(
            BackendRules.fragmentShadingApplies(
                GradeK(keepVignetting = true), hasShadingMap = true,
            ),
        )
        assertTrue(
            "keepVignetting=false must skip stage 2",
            !BackendRules.fragmentShadingApplies(
                GradeK(keepVignetting = false), hasShadingMap = true,
            ),
        )
        assertTrue(
            "no map must skip stage 2",
            !BackendRules.fragmentShadingApplies(
                GradeK(keepVignetting = true), hasShadingMap = false,
            ),
        )
        assertTrue(
            !BackendRules.fragmentShadingApplies(
                GradeK(keepVignetting = false), hasShadingMap = false,
            ),
        )
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
