package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.hevc.HevcPreflight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single 8-bit re-plan ([ExportPlanRun.fixToEightBit]) for an HLG request.
 * On a phone whose 10-bit render path is unavailable at export time the writer
 * throws TenBitUnavailable before the first frame; the exporter answers with
 * this fix. An HLG plan is 10-bit by construction, so only dropping HLG itself
 * lets the retry succeed (the first live HLG export failed with "the 10-bit
 * plan reached a writer without the session-owned encoder surface").
 */
class ExportPlanRunHlgFixTest {

    private val SURFACE = 0x7F000789
    private val P010 = 54

    private val facts = listOf(
        EncoderFacts(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true,
            profiles = setOf(1, 2, 4096), colorFormats = setOf(SURFACE, P010),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
            maxWidth = 8192, maxHeight = 8192, features = setOf("hlg-editing"),
            sizeRate = SizeRateProbe { _, _, _ -> true },
        ),
        EncoderFacts(
            "c2.qti.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(SURFACE),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..220_000_000,
            maxWidth = 8192, maxHeight = 4320, sizeRate = SizeRateProbe { _, _, _ -> true },
        ),
    )

    private fun gate(): HevcPreflight.HevcProbeResult =
        HevcProbeSource.fromFacts(facts, 3840, 2160, 30.0, apiLevel = 37)

    private fun hlgRun() = ExportPlanRun(
        ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10, dynamicRange = ExportDynamicRange.HLG),
        { facts },
        ExportContext(tenBitPathAvailable = true, hlgGate = gate()),
    )

    @Test
    fun an_hlg_run_that_lost_the_ten_bit_path_replans_as_sdr_8_bit() {
        val run = hlgRun()
        assertTrue(run.fixToEightBit("the 10-bit plan reached a writer without the session-owned encoder surface"))
        assertEquals(ExportDynamicRange.SDR, run.current.dynamicRange)
        assertEquals(8, run.current.bitDepth)
        // The retry plans from the fixed settings, with the gate still open: it is SDR 8-bit.
        val plan = run.planFor(3840, 2160, 30.0, ExportContext(tenBitPathAvailable = false, hlgGate = gate()))
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertEquals(8, plan.effective.bitDepth)
        assertTrue(plan.ok)
    }

    @Test
    fun both_reasons_stay_on_the_report() {
        val run = hlgRun()
        run.fixToEightBit("no 10-bit render path")
        val plan = run.planFor(3840, 2160, 30.0, ExportContext(tenBitPathAvailable = false, hlgGate = gate()))
        val kinds = plan.issues.map { it.kind }
        assertTrue(kinds.toString(), ExportIssue.Kind.TEN_BIT_UNAVAILABLE in kinds)
        assertTrue(kinds.toString(), ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE in kinds)
    }

    @Test
    fun an_sdr_10_bit_run_keeps_the_old_fix_and_gains_no_hlg_issue() {
        val run = ExportPlanRun(
            ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10),
            { facts },
            ExportContext(tenBitPathAvailable = true),
        )
        assertTrue(run.fixToEightBit("x"))
        assertEquals(ExportDynamicRange.SDR, run.current.dynamicRange)
        assertEquals(8, run.current.bitDepth)
        val plan = run.planFor(3840, 2160, 30.0, ExportContext(tenBitPathAvailable = false))
        assertFalse(plan.issues.any { it.kind == ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE })
    }

    @Test
    fun the_fix_is_still_used_at_most_once() {
        val run = hlgRun()
        assertTrue(run.fixToEightBit("first"))
        assertFalse(run.fixToEightBit("second"))
    }
}
