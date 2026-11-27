package com.ritim.ritimcraw.export

import java.util.Locale

/**
 * Pure planning logic for the ".cube LUT export" of the editor's Look/Node
 * page (E7 core + G5 wiring): the file NAME the SAF save sheet pre-fills,
 * the SIZE estimate shown under the grid choice, the honest refusal cases
 * the UI routes on, and the state -> user-text mapping of the export row.
 * Everything here is JVM-pure (no Android imports), the PtsTimeline /
 * ExportOpen discipline: the device-gated adapter (SAF + the JNI call)
 * lives in the editor screen, this file only decides.
 *
 * WHAT IS EXPORTED (the E7 contract, CubeExport.h): the grade's COLOR
 * chain — the legacy tone stages 9-20, the v2 node chain, and the output
 * (the legacy tonemap+LUT pair, or OpenDRT) — sampled on an n x n x n
 * lattice. NOT carried: geometry, denoise, sharpening (a 3D LUT cannot
 * represent them; the bake zeroes them explicitly).
 *
 * INPUT SPACE (the E7 owner decision, K1-A / COL-07): the lattice input
 * is SCENE-REFERRED LINEAR Rec.709 (the pipeline's own working space after
 * stage 6, the space developCpu hands stage 9). The UI states this
 * honestly ("Input: linear Rec.709") so the cube is never mistaken for a
 * display-encoded video LUT. The Rec709Code flavor exists in the core for
 * consumers that want the display-encoded convention; this plan pins the
 * DEFAULT to the linear space (the core's own default) and does not offer
 * a second input flavor in v1 — one honest product, not two.
 *
 * ORDER (the 23/24 FINDING E7 flagged, G5's product decision): the bake
 * follows the v2 model's documented render order (stages 9-20 -> node
 * chain -> output), which is the GPU pipeline's own pass order. The CPU
 * EXPORT leg runs the 23/24 pair INSIDE developCpu, i.e. BEFORE the
 * node chain — an existing CPU/GPU divergence for a tonemap+nodes grade.
 * Decision (G5, night rule): keep the core as it is — changing the CPU
 * order is a core change behind a parity wall, never a UI wiring's call —
 * and state the order honestly in the UI's note instead of promising
 * either leg's exact pixels. See ORDER_NOTE_KEY.
 */
object CubeExportPlan {

    /** The two offered lattice sizes (the E7 header's expected n values). */
    enum class Grid(val n: Int) {
        N33(33),
        N65(65),
        ;

        companion object {
            /** The DEFAULT the sheet opens with: 65 — the finer grid's
             * interpolation error is ~7x smaller (E7 measured: worst
             * 0.45/255 at 65 vs 3.31/255 at 33) and the file stays ~8 MB,
             * a one-shot save a phone writes in a moment. */
            const val DEFAULT_ORDINAL = 1
        }
    }

    /** The .cube file's extension, with the dot. */
    const val EXTENSION = ".cube"

    /** The core's own grid bound (CubeExport.cpp kMaxCubeN = the parser's). */
    const val MAX_N = 128

    /** The TITLE the bake writes above LUT_3D_SIZE ("" = no TITLE line). */
    fun title(clipName: String): String = clipName.trim()

    /**
     * The SAF save sheet's pre-filled file name: "<clipName> <grid>
     * points.cube" — never blank (a blank name falls back to "grade"), the
     * grid named in plain words so two exports of the same clip at
     * different grids cannot collide in the user's head (or in the
     * picker's overwrite prompt). The extension rides in the contract's
     * mime; the CreateDocument caller passes it separately so the system
     * sheet owns the display.
     */
    fun fileName(clipName: String, grid: Grid): String {
        val base = clipName.trim().ifEmpty { "grade" }
        val safe = base.filterNot { it == '\n' || it == '\r' || it == '/' || it == '\\' }
            .trim().ifEmpty { "grade" }
        return "$safe ${grid.n}${grid.n}${grid.n}${EXTENSION}"
    }

    /**
     * The honest upper bound of the written .cube text for one grid:
     * the writer emits "%.6f %.6f %.6f\n" rows, so a row is at most
     * 3 x ("0.123456" + 1 space) + 1 newline = 8*3 + 1 = 25 bytes...
     * measured conservatively: "0.123456" is 8 chars, plus a space each
     * = 9*3 - 1 (no trailing space) + 1 newline = 27 bytes per row. The
     * header adds LUT_3D_SIZE (<= "LUT_3D_SIZE 128\n" = 15) and an
     * optional TITLE line. This is the number the UI's "about X MB"
     * line shows — an upper bound, honestly labeled as an estimate.
     */
    fun estimatedBytes(grid: Grid, hasTitle: Boolean): Long {
        val rows = grid.n.toLong() * grid.n * grid.n
        val rowBytes = 27L // "0.123456 0.123456 0.123456\n" worst case
        val header = 15L + if (hasTitle) 256L else 0L // TITLE line cap
        return rows * rowBytes + header
    }

    /** The lattice point count (the cube's LUT_3D_SIZE cubed). */
    fun latticePoints(grid: Grid): Long =
        grid.n.toLong() * grid.n * grid.n

    /** True when the chosen grid is one the core accepts (2..128). */
    fun gridSupported(n: Int): Boolean = n in 2..MAX_N

    /**
     * The honest refusal cases the export row routes on. Every case names
     * its recovery so the UI never shows a bare "failed".
     */
    sealed interface Refusal {
        /** The engine is not loaded (the native library missing). */
        object EngineUnavailable : Refusal

        /** The clip's grade chain cannot be encoded for the bake (a
         * node-LUT the wire cannot resolve, a chain past the N1 cap) —
         * the NodeChainWire's own refusal, surfaced as-is. */
        data class ChainRefused(val reason: String) : Refusal

        /** The system save target cannot be opened (SAF stream failure,
         * disk full, the provider vanished). */
        data class WriteFailed(val reason: String) : Refusal

        /** The native bake refused: an invalid grid or a chain past the
         * N1 limits (bakeGradeCube returned the empty table). */
        data class BakeRefused(val reason: String) : Refusal
    }

    /**
     * The export row's state, one per open editor (never persisted: an
     * export is a one-shot action, not a job — no queue, no resume; the
     * user re-taps on failure). The mapping to the visible line lives in
     * [stateLineKey] so the UI strings stay in resources (en+tr).
     */
    sealed interface State {
        /** Idle: nothing running, the last note (if any) stays visible. */
        object Idle : State

        /** Baking + writing (the native call runs on a background
         * dispatcher; the row shows the busy line). */
        object Working : State

        /** Done: the .cube text reached the picked document. */
        data class Done(val bytes: Long) : State

        /** Refused: the honest reason, never a silent nothing. */
        data class Refused(val refusal: Refusal) : State
    }

    /** The state -> (en, tr) string resource pair key. */
    fun stateLineKey(state: State): String = when (state) {
        State.Idle -> "idle"
        State.Working -> "working"
        is State.Done -> "done"
        is State.Refused -> when (state.refusal) {
            Refusal.EngineUnavailable -> "engine"
            is Refusal.ChainRefused -> "chain"
            is Refusal.WriteFailed -> "write"
            is Refusal.BakeRefused -> "bake"
        }
    }

    /** The "about X MB" readout for the size line: "≈ 7.9 MB". */
    fun sizeReadout(grid: Grid, hasTitle: Boolean): String {
        val bytes = estimatedBytes(grid, hasTitle)
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.ROOT, "%.1f MB", mb)
    }
}
