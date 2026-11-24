package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine gate (off | image | gyro) and the shared warp resolver (S4).
 * The shipped state has BOTH feature flags false: every case below that
 * names a flag passes it explicitly so the compile-time constants stay
 * untouched.
 */
class WarpResolverTest {

    private val gyroGrade = GradeK(
        stabilization = true,
        stabilizationMode = 1,
        stabilizationStrength = 60f,
        stabilizationZoomLimit = 1.3f,
        stabilizationFocalMm = 24f,
        stabilizationTrimMs = -8f,
    )
    private val imageGrade = gyroGrade.copy(stabilizationEngine = 1)

    @Test
    fun shippedFlagIsOff() {
        assertFalse(com.ritim.ritimcraw.util.FeatureFlags.IMAGE_STABILIZATION_ENABLED)
    }

    // ---- StabilizationGate.engineFor: the whole table ----

    @Test
    fun engineTable() {
        for (img in listOf(false, true)) for (gy in listOf(false, true)) for (ready in listOf(false, true)) for (log in listOf<String?>(null, "/g.gcsv")) {
            fun e(g: GradeK) = StabilizationGate.engineFor(g, log, ready, imageEnabled = img, gyroEnabled = gy)
            // stabilization off: always OFF, whatever the engine value
            assertEquals(StabEngine.OFF, e(GradeK()))
            assertEquals(StabEngine.OFF, e(GradeK(stabilization = false, stabilizationEngine = 1)))
            // engine 1 = image: needs its flag AND a ready analysis; the gyro
            // flag and the log are irrelevant
            assertEquals(if (img && ready) StabEngine.IMAGE else StabEngine.OFF, e(imageGrade))
            // engine 0 / absent = gyro: needs its flag AND a log; image flag
            // and analysis are irrelevant
            assertEquals(if (gy && log != null) StabEngine.GYRO else StabEngine.OFF, e(gyroGrade))
            // any unknown value is NOT image -> the gyro rule (never a guess)
            assertEquals(if (gy && log != null) StabEngine.GYRO else StabEngine.OFF, e(gyroGrade.copy(stabilizationEngine = 7)))
        }
    }

    @Test
    fun gyroEngineMatchesTheOldConditionBitForBit() {
        for (gy in listOf(false, true)) for (log in listOf<String?>(null, "/g.gcsv")) {
            val old = StabilizationGate.active(true, log, gy)
            assertEquals(old, StabilizationGate.engineFor(gyroGrade, log, false, true, gy) == StabEngine.GYRO)
        }
    }

    @Test
    fun analysisMissingIsReportedNotSilentlyIgnored() {
        assertTrue(StabilizationGate.imageAnalysisMissing(imageGrade, imageAnalysisReady = false, imageEnabled = true))
        assertFalse(StabilizationGate.imageAnalysisMissing(imageGrade, imageAnalysisReady = true, imageEnabled = true))
        assertFalse(StabilizationGate.imageAnalysisMissing(imageGrade, imageAnalysisReady = false, imageEnabled = false))
        assertFalse(StabilizationGate.imageAnalysisMissing(gyroGrade, imageAnalysisReady = false, imageEnabled = true))
    }

    @Test
    fun imageArgIsNeverAGyroLog() {
        val arg = WarpResolver.imageArg()
        assertTrue(StabilizationGate.isImageArg(arg))
        assertFalse(StabilizationGate.active(true, arg, enabled = true))
        assertNull(StabilizationGate.pathIfActive(true, arg, enabled = true))
        assertEquals(arg, StabilizationGate.renderPathIfActive(true, arg, imageEnabled = true))
        assertNull(StabilizationGate.renderPathIfActive(true, arg, imageEnabled = false))
        assertNull(StabilizationGate.renderPathIfActive(false, arg, imageEnabled = true))
        // a real gyro path flows through the gyro gate exactly as before
        assertEquals("/g.gcsv", StabilizationGate.renderPathIfActive(true, "/g.gcsv", gyroEnabled = true))
        assertNull(StabilizationGate.renderPathIfActive(true, "/g.gcsv", gyroEnabled = false))
    }

    // ---- WarpResolver.plan / resolve: both flags false == the old dispatch ----

    private class Calls {
        val gyro = ArrayList<WarpResolver.GyroArgs>()
        val image = ArrayList<WarpResolver.ImageArgs>()
        val m10 = floatArrayOf(1f, 2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 1f)
    }

    private fun dispatch(plan: WarpResolver.Plan, c: Calls): FloatArray? = WarpResolver.resolve(
        plan,
        gyro = { a -> c.gyro.add(a); c.m10 },
        image = { a -> c.image.add(a); c.m10 },
    )

    @Test
    fun flagsOffGyroGradeDispatchesTheOldValues() {
        // What Mp4Exporter / ClipEditorScreen threaded before S4, value for value.
        val c = Calls()
        // gyro on-path: flag on + log -> path, strength /100, the raw rest
        val on = WarpResolver.plan(gyroGrade, "/g.gcsv", imageEnabled = false, gyroEnabled = true)
        dispatch(on, c)
        assertEquals(
            WarpResolver.GyroArgs("/g.gcsv", 1, 0.6f, 1.3f, 24f, -8f),
            c.gyro.single(),
        )
        // shipped state (gyro flag false): the old code still CALLED native
        // with a null path + zero strength (the bridge answers no verdict)
        c.gyro.clear()
        dispatch(WarpResolver.plan(gyroGrade, "/g.gcsv", imageEnabled = false, gyroEnabled = false), c)
        assertEquals(WarpResolver.GyroArgs(null, 1, 0f, 1.3f, 24f, -8f), c.gyro.single())
        assertTrue(c.image.isEmpty())
        // stabilization off: same null/zero fold
        c.gyro.clear()
        dispatch(WarpResolver.plan(GradeK(), "/g.gcsv", gyroEnabled = true), c)
        assertEquals(null, c.gyro.single().gyroPath)
        assertEquals(0f, c.gyro.single().strength, 0f)
    }

    @Test
    fun flagsOffImageGradeNeverWarpsAndNeverTouchesTheGyro() {
        val c = Calls()
        // the caller's slot value for an image grade with the flag off is null
        val slot = WarpResolver.stabPathArg(imageGrade, "/g.gcsv", imageAnalysisReady = true, imageEnabled = false, gyroEnabled = true)
        assertNull(slot)
        val plan = WarpResolver.plan(imageGrade, slot, imageEnabled = false, gyroEnabled = true)
        assertEquals(WarpResolver.Plan.None, plan)
        assertNull(dispatch(plan, c))
        assertTrue(c.gyro.isEmpty() && c.image.isEmpty())
        // even handed the raw gyro path, an image-engine grade is None
        assertEquals(WarpResolver.Plan.None, WarpResolver.plan(imageGrade, "/g.gcsv", imageEnabled = false, gyroEnabled = true))
    }

    @Test
    fun imageEngineDispatchesTheImageDoorWithTheStoredValues() {
        val c = Calls()
        val slot = WarpResolver.stabPathArg(imageGrade, null, imageAnalysisReady = true, imageEnabled = true)
        assertEquals(WarpResolver.imageArg(), slot)
        dispatch(WarpResolver.plan(imageGrade, slot, imageEnabled = true), c)
        assertEquals(WarpResolver.ImageArgs(1, 0.6f, 1.3f, 0, -1), c.image.single())
        assertTrue(c.gyro.isEmpty())
        // not ready -> null slot -> None (never a gyro fallback)
        assertNull(WarpResolver.stabPathArg(imageGrade, "/g.gcsv", imageAnalysisReady = false, imageEnabled = true, gyroEnabled = true))
    }

    @Test
    fun gyroGradePassesItsPathThroughTheSlotUntouched() {
        assertEquals("/g.gcsv", WarpResolver.stabPathArg(gyroGrade, "/g.gcsv", true, true, true))
        assertNull(WarpResolver.stabPathArg(gyroGrade, null, true, true, true))
        assertEquals("/g.gcsv", WarpResolver.stabPathArg(GradeK(), "/g.gcsv", false, false, false))
    }

    // ---- verdict fold: null / short = inactive + identity ----

    @Test
    fun verdictFold() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertFalse(WarpResolver.verdict(null).active)
        assertArrayEquals(id, WarpResolver.verdict(null).m, 0f)
        // the old inline fold: active from [0] alone, M identity when short
        assertTrue(WarpResolver.verdict(floatArrayOf(1f)).active)
        assertArrayEquals(id, WarpResolver.verdict(floatArrayOf(1f)).m, 0f)
        val v = WarpResolver.verdict(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f))
        assertTrue(v.active)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), v.m, 0f)
        assertFalse(WarpResolver.verdict(floatArrayOf(0f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)).active)
    }

    // ---- GradeMapping.stabilizationArgs: the CPU render args ----

    @Test
    fun cpuArgsForTheImageArgument() {
        val arg = WarpResolver.imageArg()
        val a = GradeMapping.stabilizationArgs(imageGrade, 1280, arg, enabled = false, imageEnabled = true)
        assertEquals(arg, a!!.gyroPath)
        assertEquals(1, a.mode)
        assertEquals(0.6f, a.strength, 1e-6f)
        assertEquals(1.3f, a.zoomLimit, 0f)
        assertEquals(0f, a.focalMm, 0f) // gyro-only values are inert
        assertEquals(0f, a.trimMs, 0f)
        assertNull(GradeMapping.stabilizationArgs(imageGrade, 1280, arg, enabled = true, imageEnabled = false))
        // an image-engine grade never becomes gyro args, even with a real path
        assertNull(GradeMapping.stabilizationArgs(imageGrade, 1280, "/g.gcsv", enabled = true, imageEnabled = true))
        // gyro grades are unchanged
        assertEquals("/g.gcsv", GradeMapping.stabilizationArgs(gyroGrade, 1280, "/g.gcsv", enabled = true)!!.gyroPath)
    }
}
