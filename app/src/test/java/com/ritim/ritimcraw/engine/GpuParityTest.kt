package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GpuParity decision-rule tests (owner request 2026-11-12, deliverable C):
 * the JNI array contracts, the pass/fail thresholds, the
 * probe->BackendGates mapping (parity never comes from a probe), and the
 * Measure log-line formats. The C++ harness only reports numbers; THIS
 * file owns the verdict, so the rules are JVM-tested. Pure JVM — no
 * Android classes (the JNI seam returns plain DoubleArrays).
 */
class GpuParityTest {

    // ---- JNI array contracts ----

    @Test
    fun probe_from_array_parses_the_full_contract() {
        val r = GlesProbeResult.fromArray(
            doubleArrayOf(1.0, 3.0, 2.0, 1.0, 1.0, 16384.0),
        )
        assertTrue(r.contextOk)
        assertEquals(3, r.glesMajor)
        assertEquals(2, r.glesMinor)
        assertTrue(r.computeOk)
        assertTrue(r.floatFboOk)
        assertEquals(16384, r.maxTextureSize)
    }

    @Test
    fun probe_from_array_rejects_a_drifted_contract() {
        // A size change in the bridge must be loud in the tests, never
        // papered over by silent index math.
        val wrong = doubleArrayOf(1.0, 3.0, 2.0, 1.0, 1.0)
        try {
            GlesProbeResult.fromArray(wrong)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("6"))
        }
    }

    @Test
    fun parity_from_array_parses_the_full_contract() {
        val r = GlesParityResult.fromArray(doubleArrayOf(1.0, 1.0, 12.0, 24883200.0))
        assertTrue(r.ran)
        assertEquals(1, r.maxDiffLsb)
        assertEquals(12L, r.mismatchCount)
        assertEquals(24883200L, r.samples)
    }

    @Test
    fun parity_from_array_rejects_a_drifted_contract() {
        try {
            GlesParityResult.fromArray(doubleArrayOf(1.0, 1.0, 12.0))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("4"))
        }
    }

    // ---- verdict rules ----

    // ---- runtime verdict (the editor/export open gate; the mismatch SHARE is not a runtime condition) ----

    @Test
    fun runtime_gate_passes_one_lsb_flips_even_at_a_high_mismatch_share_but_the_suite_verdict_still_fails_them() {
        // The DCG-mode clip on 2026-11-25: 121946 of 12582912 samples (0.97%), all 1 LSB.
        val p = GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 121946, samples = 12582912)
        val rt = GpuParityRules.runtimeVerdict(p)
        assertEquals(ParityVerdict.Status.PASS, rt.status)
        assertTrue(rt.reason.contains("121946/12582912"))
        assertEquals(ParityVerdict.Status.FAIL, GpuParityRules.verdict(p).status)
        assertTrue(GpuParityRules.gateDetailLine(true, p, null)!!.startsWith("parity gate: PASS"))
    }

    @Test
    fun runtime_gate_still_fails_two_lsb_and_not_run() {
        assertEquals(
            ParityVerdict.Status.FAIL,
            GpuParityRules.runtimeVerdict(GlesParityResult(true, 2, 1, 1000)).status,
        )
        assertEquals(
            ParityVerdict.Status.NOT_RUN,
            GpuParityRules.runtimeVerdict(GlesParityResult(false, 0, 0, 0), "boom").status,
        )
        // A strict-passing run logs nothing extra.
        assertEquals(null, GpuParityRules.gateDetailLine(true, GlesParityResult(true, 1, 0, 1000), null))
    }

    @Test
    fun a_run_that_did_not_run_is_not_run_with_the_error_detail() {
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = false, maxDiffLsb = 0, mismatchCount = 0, samples = 0),
            errorDetail = "shader-compile: syntax error",
        )
        assertEquals(ParityVerdict.Status.NOT_RUN, v.status)
        assertFalse(v.pass)
        assertEquals("shader-compile: syntax error", v.reason)
    }

    @Test
    fun not_run_without_detail_names_the_stage() {
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = false, maxDiffLsb = 0, mismatchCount = 0, samples = 0),
        )
        assertEquals(ParityVerdict.Status.NOT_RUN, v.status)
        assertEquals("gpu stage failed", v.reason)
    }

    @Test
    fun one_lsb_and_a_tiny_share_of_mismatches_passes() {
        // Driver float noise: <= 1 LSB and <= 0.01% of samples.
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 2488, samples = 24883200),
        )
        assertEquals(ParityVerdict.Status.PASS, v.status)
        assertTrue(v.pass)
    }

    @Test
    fun zero_mismatches_passes() {
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = true, maxDiffLsb = 0, mismatchCount = 0, samples = 24883200),
        )
        assertEquals(ParityVerdict.Status.PASS, v.status)
    }

    @Test
    fun two_lsbs_fails_even_with_zero_mismatches() {
        // A single sample off by 2 LSB: beyond driver float noise.
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = true, maxDiffLsb = 2, mismatchCount = 1, samples = 24883200),
        )
        assertEquals(ParityVerdict.Status.FAIL, v.status)
        assertTrue(v.reason.contains("maxDiffLsb 2"))
    }

    @Test
    fun a_border_only_deviation_fails_the_share() {
        // ~0.2% of a 4K frame mismatching at 1 LSB: the borders-only
        // systematic deviation the threshold exists to catch.
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 49766, samples = 24883200),
        )
        assertEquals(ParityVerdict.Status.FAIL, v.status)
        assertTrue(v.reason.contains("mismatch 49766"))
    }

    // ---- probe -> BackendGates mapping (§14 honesty) ----

    @Test
    fun gates_from_probe_never_claims_parity() {
        // A capability probe says NOTHING about parity: parityOk comes
        // only from a real parity run's verdict. This is the BackendGates
        // contract (RenderBackendTest locks the gate math).
        val gates = GpuParityRules.gatesFrom(
            GlesProbeResult(true, 3, 2, true, true, 16384),
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.computeOk)
        assertTrue(gates.floatFboOk)
        assertFalse(gates.parityOk)
        assertFalse(gates.gpuAllowed())
        assertEquals("parity-not-verified", gates.fallbackReason())
    }

    @Test
    fun gates_from_a_no_context_probe_stay_all_false() {
        val gates = GpuParityRules.gatesFrom(
            GlesProbeResult(false, 0, 0, false, false, 0),
        )
        assertEquals(
            "no-egl-context,no-compute,no-float-fbo,parity-not-verified",
            gates.fallbackReason(),
        )
    }

    // ---- Measure log-line formats (the owner greps these) ----

    @Test
    fun probe_line_carries_every_capability() {
        val line = GpuParityRules.probeLine(
            GlesProbeResult(true, 3, 2, true, true, 16384),
        )
        assertEquals(
            "probe: context=1 gles=3.2 compute=1 floatFbo=1 maxTextureSize=16384",
            line,
        )
    }

    @Test
    fun probe_line_reads_honestly_when_nothing_came_up() {
        val line = GpuParityRules.probeLine(
            GlesProbeResult(false, 0, 0, false, false, 0),
        )
        assertEquals(
            "probe: context=0 gles=0.0 compute=0 floatFbo=0 maxTextureSize=0",
            line,
        )
    }

    @Test
    fun parity_line_carries_the_raw_numbers() {
        val line = GpuParityRules.parityLine(
            GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 12, samples = 1000),
        )
        assertEquals("parity: ran=1 maxDiffLsb=1 mismatch=12 samples=1000", line)
    }

    @Test
    fun verdict_line_names_the_status_and_reason() {
        val line = GpuParityRules.verdictLine(
            ParityVerdict(ParityVerdict.Status.FAIL, "maxDiffLsb 2 > 1"),
        )
        assertEquals("verdict: FAIL (maxDiffLsb 2 > 1)", line)
    }

    @Test
    fun skipped_parity_line_names_the_reason() {
        assertEquals(
            "parity: skipped (no gles context)",
            GpuParityRules.skippedParityLine("no gles context"),
        )
    }

    // ---- fragment develop-parity leg (deliverable D) ----

    @Test
    fun fragment_parity_line_carries_the_raw_numbers_and_the_leg_label() {
        // Same numbers, same verdict rules as the compute leg — but the
        // "fragment" label lets the owner tell the two legs apart in one
        // Measure block (a compute-less device can pass this leg while
        // the compute leg reads "skipped").
        val line = GpuParityRules.fragmentParityLine(
            GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 12, samples = 1000),
        )
        assertEquals(
            "fragment parity: ran=1 maxDiffLsb=1 mismatch=12 samples=1000",
            line,
        )
    }

    @Test
    fun fragment_parity_uses_the_same_verdict_rules_as_compute() {
        // One rule set for both legs: the fragment harness reports raw
        // numbers, THIS verdict applies, and the thresholds are the
        // documented 1 LSB / 0.01% pair.
        val v = GpuParityRules.verdict(
            GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 2488, samples = 24883200),
        )
        assertEquals(ParityVerdict.Status.PASS, v.status)
    }

    @Test
    fun skipped_fragment_line_names_the_reason() {
        assertEquals(
            "fragment parity: skipped (no EXT_color_buffer_float (RGBA32F not renderable))",
            GpuParityRules.skippedFragmentLine(
                "no EXT_color_buffer_float (RGBA32F not renderable)",
            ),
        )
    }

    @Test
    fun a_compute_less_probe_still_supports_the_fragment_leg() {
        // The fragment chain is a draw, not a compute dispatch: its gate
        // is contextOk + floatFboOk, NEVER computeOk. A compute-less
        // ES 3.1 device (floatFbo yes, compute no) can still pass the
        // fragment leg — the compute leg reads "skipped" in the same
        // block, honestly.
        val gates = GpuParityRules.gatesFrom(
            GlesProbeResult(contextOk = true, glesMajor = 3, glesMinor = 1, computeOk = false, floatFboOk = true, maxTextureSize = 16384),
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.floatFboOk)
        assertFalse(gates.computeOk)
        assertFalse(gates.parityOk)
    }

    @Test
    fun block_wraps_the_lines_with_the_gles_markers() {
        val block = GpuParityRules.block(listOf("probe: context=1", "verdict: PASS (ok)"))
        assertEquals(
            "=== ritim gles (ritim-pm) ===\nprobe: context=1\nverdict: PASS (ok)\n=== end gles ===",
            block,
        )
    }

    // ---- the fallback companion line (the parity-fix observability) ----

    @Test
    fun gate_detail_line_is_null_when_the_leg_was_never_paid() {
        // A red probe (or a clip fd that never opened) never fabricates a
        // measurement: the fallback line's tokens already name the red
        // gate, and no companion line rides beside them.
        assertNull(
            GpuParityRules.gateDetailLine(
                parityPaid = false,
                parity = null,
                errorDetail = null,
            ),
        )
        assertNull(
            GpuParityRules.gateDetailLine(
                parityPaid = false,
                parity = GlesParityResult(ran = false, maxDiffLsb = 0, mismatchCount = 0, samples = 0),
                errorDetail = "link: develop",
            ),
        )
    }

    @Test
    fun gate_detail_line_names_the_gl_step_for_a_run_that_refused() {
        // NOT_RUN: the harness's own error detail (nativeLastError) names
        // the GL step that refused — exactly what the owner's re-test
        // needs to classify the latch.
        assertEquals(
            "parity gate: NOT_RUN (link: develop)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = GlesParityResult(ran = false, maxDiffLsb = 0, mismatchCount = 0, samples = 0),
                errorDetail = "link: develop",
            ),
        )
    }

    @Test
    fun gate_detail_line_names_the_stage_when_a_refused_run_left_no_detail() {
        assertEquals(
            "parity gate: NOT_RUN (gpu stage failed)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = GlesParityResult(ran = false, maxDiffLsb = 0, mismatchCount = 0, samples = 0),
                errorDetail = null,
            ),
        )
    }

    @Test
    fun gate_detail_line_carries_the_threshold_numbers_for_a_failed_run() {
        // FAIL: the thresholds' own numbers — the verdict's reason plus the
        // raw ran/maxDiffLsb/mismatch/samples the harness reported.
        assertEquals(
            "parity gate: FAIL (maxDiffLsb 2 > 1; " +
                "ran=1 maxDiffLsb=2 mismatch=1 samples=24883200)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = GlesParityResult(ran = true, maxDiffLsb = 2, mismatchCount = 1, samples = 24883200),
                errorDetail = null,
            ),
        )
    }

    @Test
    fun gate_detail_line_reports_a_share_above_the_suite_threshold_as_informational_pass() {
        // Owner decision 2026-11-26: the runtime gate checks maxDiffLsb only; the share stays in the suites.
        assertEquals(
            "parity gate: PASS (runtime gate: maxDiffLsb 1 <= 1; mismatch 49766/24883200 (0.2000%); " +
                "above the suite's 0.01% share: informational)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 49766, samples = 24883200),
                errorDetail = null,
            ),
        )
    }

    @Test
    fun gate_detail_line_is_null_when_the_verdict_passed() {
        // A green gate logs no fallback at all — no companion line rides
        // beside nothing.
        assertNull(
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = GlesParityResult(ran = true, maxDiffLsb = 1, mismatchCount = 2488, samples = 24883200),
                errorDetail = null,
            ),
        )
    }

    @Test
    fun gate_detail_line_names_the_seam_error_when_the_call_returned_no_array() {
        // A null array is the seam's own clip-level failure (open/decode):
        // the "jni failure" vocabulary the Measure leg already logs, with
        // the same error detail.
        assertEquals(
            "parity gate: jni failure (gles fragment parity: IoError — frame 0 decode)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = null,
                errorDetail = "gles fragment parity: IoError — frame 0 decode",
            ),
        )
        assertEquals(
            "parity gate: jni failure (no detail)",
            GpuParityRules.gateDetailLine(
                parityPaid = true,
                parity = null,
                errorDetail = null,
            ),
        )
    }
}
