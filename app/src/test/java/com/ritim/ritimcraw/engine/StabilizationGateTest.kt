package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.util.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effective-stabilization gate (owner decision 2026-11-23): flag off
 * forces every path unstabilized without touching the stored grade; flag
 * on is the pre-gate behavior bit for bit.
 */
class StabilizationGateTest {

    private val stabilized = GradeK(
        stabilization = true,
        stabilizationMode = 2,
        stabilizationStrength = 70f,
        stabilizationZoomLimit = 1.3f,
        stabilizationFocalMm = 6.5f,
        stabilizationTrimMs = 12f,
    )

    @Test
    fun shippedFlagIsOff() {
        assertFalse(FeatureFlags.GYRO_STABILIZATION_ENABLED)
        assertFalse(StabilizationGate.gyroWorkAllowed())
    }

    @Test
    fun flagOffForcesEveryDecisionOff() {
        assertFalse(StabilizationGate.active(true, "/g.gcsv", enabled = false))
        assertNull(StabilizationGate.pathIfActive(true, "/g.gcsv", enabled = false))
        assertNull(GradeMapping.stabilizationArgs(stabilized, 1280, "/g.gcsv", enabled = false))
        assertFalse(StabilizationGate.gyroWorkAllowed(enabled = false))
    }

    @Test
    fun flagOnIsTheOldCondition() {
        // grade on + log attached: the old `stabilization && gyroPath != null`.
        assertTrue(StabilizationGate.active(true, "/g.gcsv", enabled = true))
        assertEquals("/g.gcsv", StabilizationGate.pathIfActive(true, "/g.gcsv", enabled = true))
        // every other combination stays off, exactly like before.
        assertFalse(StabilizationGate.active(false, "/g.gcsv", enabled = true))
        assertFalse(StabilizationGate.active(true, null, enabled = true))
        assertNull(StabilizationGate.pathIfActive(false, "/g.gcsv", enabled = true))
        assertTrue(StabilizationGate.gyroWorkAllowed(enabled = true))
    }

    @Test
    fun flagOnStabilizationArgsCarryTheStoredValuesUnchanged() {
        val args = GradeMapping.stabilizationArgs(stabilized, 1280, "/g.gcsv", enabled = true)
        assertNotNull(args)
        assertEquals("/g.gcsv", args!!.gyroPath)
        assertEquals(2, args.mode)
        assertEquals(0.7f, args.strength, 1e-6f)
        assertEquals(1.3f, args.zoomLimit, 0f)
        assertEquals(6.5f, args.focalMm, 0f)
        assertEquals(12f, args.trimMs, 0f)
    }

    @Test
    fun gatingNeverMutatesTheStoredGrade() {
        val before = stabilized.copy()
        GradeMapping.stabilizationArgs(stabilized, 1280, "/g.gcsv", enabled = false)
        GradeMapping.stabilizationArgs(stabilized, 1280, "/g.gcsv", enabled = true)
        assertEquals(before, stabilized)
        assertTrue(stabilized.stabilization)
        assertSame(stabilized, stabilized) // the grade object is only ever read
    }
}
