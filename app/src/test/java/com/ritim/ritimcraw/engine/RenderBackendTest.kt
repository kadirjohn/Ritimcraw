package com.ritim.ritimcraw.engine

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
}
