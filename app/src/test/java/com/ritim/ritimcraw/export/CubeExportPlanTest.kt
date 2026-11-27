package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The .cube export's pure planning logic (G5 over the E7 core): the file
 * name the SAF sheet pre-fills, the size estimate shown under the grid
 * choice, the refusal routing, and the state -> line mapping. All cases
 * are JVM-traced by hand; no Android, no native.
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

    // ---- File name ----

    @Test
    fun `file name carries the clip name and the grid`() {
        assertEquals("Sunset 656565.cube", CubeExportPlan.fileName("Sunset", CubeExportPlan.Grid.N65))
        assertEquals("Sunset 333333.cube", CubeExportPlan.fileName("Sunset", CubeExportPlan.Grid.N33))
    }

    @Test
    fun `a blank clip name falls back to grade never a bare extension`() {
        assertEquals("grade 656565.cube", CubeExportPlan.fileName("", CubeExportPlan.Grid.N65))
        assertEquals("grade 656565.cube", CubeExportPlan.fileName("   ", CubeExportPlan.Grid.N65))
    }

    @Test
    fun `file name strips line breaks and path separators`() {
        // A clip name cannot legally carry a newline, but a synced or
        // imported name is data — the plan never writes a byte the file
        // system would refuse or a line the .cube parser would split.
        assertEquals("clip 333333.cube", CubeExportPlan.fileName("cl\nip", CubeExportPlan.Grid.N33))
        assertEquals("clip 333333.cube", CubeExportPlan.fileName("cl/ip", CubeExportPlan.Grid.N33))
        assertEquals("clip 333333.cube", CubeExportPlan.fileName("cl\\ip", CubeExportPlan.Grid.N33))
        assertEquals("clip 333333.cube", CubeExportPlan.fileName("cl\rip", CubeExportPlan.Grid.N33))
    }

    @Test
    fun `file name keeps inner spaces and the extension is exactly cube`() {
        assertEquals("My clip 4 656565.cube", CubeExportPlan.fileName(" My clip 4 ", CubeExportPlan.Grid.N65))
        assertEquals(".cube", CubeExportPlan.EXTENSION)
    }

    @Test
    fun `a name of only separators falls back to grade`() {
        // After the strip the base is empty — the same honest fallback,
        // never a file the picker would refuse to show.
        assertEquals("grade 333333.cube", CubeExportPlan.fileName("/", CubeExportPlan.Grid.N33))
        assertEquals("grade 333333.cube", CubeExportPlan.fileName("\\", CubeExportPlan.Grid.N33))
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
}
