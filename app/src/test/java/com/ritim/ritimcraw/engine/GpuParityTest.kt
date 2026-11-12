package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun block_wraps_the_lines_with_the_gles_markers() {
        val block = GpuParityRules.block(listOf("probe: context=1", "verdict: PASS (ok)"))
        assertEquals(
            "=== ritim gles (ritim-pm) ===\nprobe: context=1\nverdict: PASS (ok)\n=== end gles ===",
            block,
        )
    }
}
