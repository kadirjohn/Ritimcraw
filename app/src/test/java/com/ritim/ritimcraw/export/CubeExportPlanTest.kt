package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NodeChainWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The .cube export's pure planning logic (G5 over the E7 core): the file
 * name the SAF sheet pre-fills, the size estimate shown under the grid
 * choice, the refusal routing, the state -> line mapping, and the GRADE
 * + WIRE composition the bake sends (bakeGrade / bakeChainWire). All
 * cases are JVM-traced by hand; no Android, no native.
 */
class CubeExportPlanTest {

    // ---- Grid ----

    @Test
    fun `the offered grids are exactly 33 and 65`() {
        assertEquals(listOf(33, 65), CubeExportPlan.Grid.entries.map { it.n })
    }

    @Test
    fun `the default grid is 65 the finer lattice`() {
        // E7 measured: worst interpolation error 0.45/255 at 65 vs
        // 3.31/255 at 33 — the finer grid is the honest default.
        assertEquals(65, CubeExportPlan.Grid.entries[CubeExportPlan.Grid.DEFAULT_ORDINAL].n)
    }

    @Test
    fun `grid_supported accepts the core range and refuses the rest`() {
        // The core's own bound (CubeExport.cpp kMaxCubeN): 2..128.
        assertTrue(CubeExportPlan.gridSupported(2))
        assertTrue(CubeExportPlan.gridSupported(33))
        assertTrue(CubeExportPlan.gridSupported(65))
        assertTrue(CubeExportPlan.gridSupported(128))
        assertEquals(false, CubeExportPlan.gridSupported(1))
        assertEquals(false, CubeExportPlan.gridSupported(0))
        assertEquals(false, CubeExportPlan.gridSupported(129))
        assertEquals(false, CubeExportPlan.gridSupported(-33))
    }

    @Test
    fun `extension is exactly cube`() {
        assertEquals(".cube", CubeExportPlan.EXTENSION)
    }

    // ---- Size estimate ----

    @Test
    fun `lattice points are n cubed`() {
        assertEquals(33L * 33 * 33, CubeExportPlan.latticePoints(CubeExportPlan.Grid.N33))
        assertEquals(65L * 65 * 65, CubeExportPlan.latticePoints(CubeExportPlan.Grid.N65))
    }

    @Test
    fun `estimated bytes bound the writer output per row count`() {
        // The writer emits "%.6f %.6f %.6f\n" — the widest value is
        // "0.123456" (8 chars) + a space each, so a row is 27 bytes worst
        // case; the header adds the LUT_3D_SIZE line and, with a title,
        // the capped TITLE line.
        val rows33 = 33L * 33 * 33
        val rows65 = 65L * 65 * 65
        assertEquals(rows33 * 27 + 15, CubeExportPlan.estimatedBytes(CubeExportPlan.Grid.N33, false))
        assertEquals(rows65 * 27 + 15, CubeExportPlan.estimatedBytes(CubeExportPlan.Grid.N65, false))
        assertEquals(rows65 * 27 + 15 + 256, CubeExportPlan.estimatedBytes(CubeExportPlan.Grid.N65, true))
    }

    @Test
    fun `the size readout shows megabytes with one decimal`() {
        assertEquals(
            String.format(java.util.Locale.ROOT, "%.1f MB", (65.0 * 65 * 65 * 27 + 15) / (1024.0 * 1024.0)),
            CubeExportPlan.sizeReadout(CubeExportPlan.Grid.N65, false),
        )
        // The 33 grid is ~1 MB — the readout never shows a bare "1 MB" for
        // a value the user compares against their free space.
        assertTrue(CubeExportPlan.sizeReadout(CubeExportPlan.Grid.N33, false).endsWith(" MB"))
    }

    // ---- Title ----

    @Test
    fun `title is the trimmed clip name`() {
        assertEquals("Sunset", CubeExportPlan.title("  Sunset  "))
        assertEquals("", CubeExportPlan.title("   "))
    }

    // ---- State routing ----

    @Test
    fun `state line keys are stable and distinct per case`() {
        // The UI maps each key to an (en, tr) string resource; the keys are
        // the wire between this pure file and the resources, so they are
        // locked here.
        assertEquals("idle", CubeExportPlan.stateLineKey(CubeExportPlan.State.Idle))
        assertEquals("working", CubeExportPlan.stateLineKey(CubeExportPlan.State.Working))
        assertEquals("done", CubeExportPlan.stateLineKey(CubeExportPlan.State.Done(123L)))
        assertEquals("engine", CubeExportPlan.stateLineKey(
            CubeExportPlan.State.Refused(CubeExportPlan.Refusal.EngineUnavailable)))
        assertEquals("chain", CubeExportPlan.stateLineKey(
            CubeExportPlan.State.Refused(CubeExportPlan.Refusal.ChainRefused("node LUT missing"))))
        assertEquals("write", CubeExportPlan.stateLineKey(
            CubeExportPlan.State.Refused(CubeExportPlan.Refusal.WriteFailed("disk full"))))
        assertEquals("bake", CubeExportPlan.stateLineKey(
            CubeExportPlan.State.Refused(CubeExportPlan.Refusal.BakeRefused("n=1"))))
    }

    @Test
    fun `refusals carry their reason verbatim`() {
        assertEquals("node 2 names LUT x", (CubeExportPlan.Refusal.ChainRefused("node 2 names LUT x") as CubeExportPlan.Refusal.ChainRefused).reason)
        assertEquals("provider vanished", (CubeExportPlan.Refusal.WriteFailed("provider vanished") as CubeExportPlan.Refusal.WriteFailed).reason)
        assertEquals("grid 1", (CubeExportPlan.Refusal.BakeRefused("grid 1") as CubeExportPlan.Refusal.BakeRefused).reason)
    }

    // ---- Bake grade + wire composition (the encoded tone split) ----

    /**
     * The RED audit's lock (2026-11-27): on an ENCODED clip the two GPU
     * doors zero highlights/shadows BEFORE the chain (jni_bridge.cpp
     * 4423/5353) and the effect rides the node chain's log wheels
     * (EncodedToneBridge, owner decision 2026-11-25). The bake must copy
     * that shape EXACTLY: parcel zeros (so the bake core's unconditional
     * applyToneColorStagesCpu cannot run stage 15/11, CubeExport.cpp:109)
     * + the bridge on the wire with the ORIGINAL slider values. A bake
     * that keeps the values in the parcel runs stage 15/11 AND the log
     * wheel — the slider twice, a cube unlike anything the user sees.
     */
    private fun nonNeutralGrade(): GradeK = GradeK(
        exposureEv = 0.3f,
        highlights = -50f,
        shadows = 25f,
        tempK = 6500f,
        blackPoint = -10f,
        whitePoint = 5f,
        midtones = 15f,
        contrast = 20f,
        saturation = -30f,
        wheelShadowX = 0.1f, wheelShadowY = -0.1f,
        wheelMidX = 0.05f, wheelMidY = 0.02f,
        wheelHighX = -0.07f, wheelHighY = 0.03f,
        splitShadowHue = 200f, splitHighlightHue = 40f, splitBalance = 30f,
        sharpness = 40f, noiseReduction = 20f,
    )

    @Test
    fun `encoded bake grade zeros every tone-band field including highlights and shadows`() {
        val g = nonNeutralGrade()
        val baked = CubeExportPlan.bakeGrade(g, encoded = true)
        // The two sliders: zeroed in the parcel because their effect is
        // carried by the WIRE (bakeChainWire), not because it is dropped.
        assertEquals(0f, baked.highlights, 0f)
        assertEquals(0f, baked.shadows, 0f)
        // The rest of the 9-20 tone-band block, the GPU door's own zero
        // set (jni_bridge.cpp 4423/5353).
        assertEquals(0f, baked.blackPoint, 0f)
        assertEquals(0f, baked.whitePoint, 0f)
        assertEquals(0f, baked.midtones, 0f)
        assertEquals(0f, baked.contrast, 0f)
        assertEquals(0f, baked.saturation, 0f)
        assertEquals(0f, baked.wheelShadowX, 0f)
        assertEquals(0f, baked.wheelShadowY, 0f)
        assertEquals(0f, baked.wheelMidX, 0f)
        assertEquals(0f, baked.wheelMidY, 0f)
        assertEquals(0f, baked.wheelHighX, 0f)
        assertEquals(0f, baked.wheelHighY, 0f)
        assertEquals(-1f, baked.splitShadowHue, 0f)
        assertEquals(-1f, baked.splitHighlightHue, 0f)
        assertEquals(0f, baked.splitBalance, 0f)
    }

    @Test
    fun `encoded bake grade keeps the fields the encoded path does render`() {
        // WB + exposure run per-pixel on the encoded path (Transfer.cpp),
        // and the parcel slots outside 9-20 are the bake core's own inputs:
        // neutralizing them would silently change the cube.
        val baked = CubeExportPlan.bakeGrade(nonNeutralGrade(), encoded = true)
        assertEquals(0.3f, baked.exposureEv, 0f)
        assertEquals(6500f, baked.tempK, 0f)
        assertEquals(40f, baked.sharpness, 0f)
        assertEquals(20f, baked.noiseReduction, 0f)
    }

    @Test
    fun `mcraw bake grade is the grade verbatim`() {
        val g = nonNeutralGrade()
        // MCRAW's render IS the full 9-20 chain, so its cube samples the
        // grade as-is — same instance, not an equal copy.
        assertSame(g, CubeExportPlan.bakeGrade(g, encoded = false))
    }

    @Test
    fun `encoded bake wire carries the original sliders through the tone bridge`() {
        // The bridge's input is the ORIGINAL grade (the render site's own
        // argument, EncodedSource.renderFrame), NOT bakeGrade's zeroed
        // copy — with the zeroed copy the wire would drop the effect the
        // parcel also dropped, and the sliders would vanish from the cube.
        val g = nonNeutralGrade()
        val out = CubeExportPlan.bakeChainWire(g, encoded = true, rawWire = null)!!
        assertEquals(1f, out[0]) // one synthetic node appended
        val hi = out[NodeChainWire.HEADER + NodeChainWire.SLOT_LOG_HI + 2]
        val sh = out[NodeChainWire.HEADER + NodeChainWire.SLOT_LOG_SHADOW + 2]
        assertEquals(-0.5f, hi, 1e-6f) // highlights -50 -> master -0.5
        assertEquals(0.25f, sh, 1e-6f) // shadows +25 -> master +0.25
    }

    @Test
    fun `encoded bake wire passes a zero-slider grade through untouched`() {
        // Both sliders at 0: the bridge's own fast path, the wire the
        // chain encoder produced (here null = the legacy fast path).
        assertNull(CubeExportPlan.bakeChainWire(GradeK(), encoded = true, rawWire = null))
    }

    @Test
    fun `mcraw bake wire is the raw wire untouched`() {
        // MCRAW's sliders ride the tone-band stages in the parcel — the
        // bridge is encoded-only, so its wire must pass through as-is.
        val w = FloatArray(NodeChainWire.HEADER)
        assertSame(w, CubeExportPlan.bakeChainWire(nonNeutralGrade(), encoded = false, rawWire = w))
    }
}
