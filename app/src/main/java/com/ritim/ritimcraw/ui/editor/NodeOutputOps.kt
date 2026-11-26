package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import java.util.Locale
import kotlin.math.roundToInt

/**
 * F1-8d: pure edit logic for the Output card (docs/F1_8_COLOR_UI_DESIGN.md section 3, "Output kartı"),
 * over [OutputTransformV2] (the v2 record's `output` group). No Compose, no schema change; every function
 * returns a new [OutputTransformV2].
 *
 * THE MODE SEGMENT keeps BOTH branches verbatim: the legacy tonemap+LUT block and the OpenDRT params each
 * ride the record untouched through a switch (the design's "v1 projeler hiç değişmez" — a v1-grade record's
 * legacy block is sacred, and a drt-tuned record that switches to legacy keeps its drt params for the
 * switch back; only `mode` routes). The wire's outputMode reads mode alone (NodeChainWire [1]).
 *
 * THE OPENDRT PARAMETER RANGES mirror core's OpenDrt.h bounds (and Klipp's own clamps, the sibling app):
 *   look 0..6 (Standard, Arriba, Sylvan, Colorful, Aery, Dystopic, Umbra),
 *   tonescale 0..13 (0 = from the look; Klipp's TONESCALE list),
 *   cwp 0..6 (0 = look's; D93, D75, D65, D60, D55, D50),
 *   cwpLimit 0..1 (0.25 grid),
 *   grey 3..25 nits (0.5 steps — a display-referred mid-grey knob deserves finer than 1 nit),
 *   display 0..2 (sRGB 2.2 / Rec.1886 2.4 / HLG 1000).
 * The page clamps BEFORE the record, so the wire (and OpenDrtPacks.resolve, and the C++ OpenDrtSpec) only
 * ever sees in-range values — the UI's bounds and the engine's bounds agree by construction, and an
 * out-of-range value from a hand-edited file degrades to the nearest legal one on the first touch.
 */
object NodeOutputOps {

    // ---- the bounds (core include/rawedit/OpenDrt.h) ----

    const val LOOK_MAX = 6
    const val TONESCALE_MAX = 13
    const val CWP_MAX = 6
    const val DISPLAY_MAX = 2

    /** Mid-grey range in nits (OpenDrt.h: grey is "3..25"). */
    const val GREY_MIN = 3f
    const val GREY_MAX = 25f

    /** The grey slider's step: half-nit — fine enough for a display-referred knob, coarse enough to drag. */
    const val GREY_STEP = 0.5f

    /** The cwp limit's grid (0..1 in quarter steps). */
    const val CWP_LIMIT_STEP = 0.25f

    // ---- the mode segment ----

    /** Switches the output mode, both branches verbatim (see the class doc). Anything but "opendrt" is legacy. */
    fun setMode(o: OutputTransformV2, mode: String): OutputTransformV2 =
        o.copy(
            mode = if (mode == OutputTransformV2.MODE_OPENDRT) OutputTransformV2.MODE_OPENDRT
            else OutputTransformV2.MODE_LEGACY,
        )

    // ---- the OpenDRT setters (each clamps to its own range and touches nothing else) ----

    private fun drt(o: OutputTransformV2, p: OpenDrtParamsV2): OutputTransformV2 =
        o.copy(opendrt = p)

    fun setLook(o: OutputTransformV2, look: Int): OutputTransformV2 =
        drt(o, o.opendrt.copy(look = look.coerceIn(0, LOOK_MAX)))

    fun setTonescale(o: OutputTransformV2, tonescale: Int): OutputTransformV2 =
        drt(o, o.opendrt.copy(tonescale = tonescale.coerceIn(0, TONESCALE_MAX)))

    fun setCwp(o: OutputTransformV2, cwp: Int): OutputTransformV2 =
        drt(o, o.opendrt.copy(cwp = cwp.coerceIn(0, CWP_MAX)))

    fun setCwpLimit(o: OutputTransformV2, limit: Float): OutputTransformV2 {
        val snapped = (limit / CWP_LIMIT_STEP).roundToInt() * CWP_LIMIT_STEP + 0f
        return drt(o, o.opendrt.copy(cwpLimit = snapped.coerceIn(0f, 1f)))
    }

    fun setGrey(o: OutputTransformV2, grey: Float): OutputTransformV2 {
        val snapped = (grey / GREY_STEP).roundToInt() * GREY_STEP + 0f // + 0f: -0.0 -> 0.0
        return drt(o, o.opendrt.copy(grey = snapped.coerceIn(GREY_MIN, GREY_MAX)))
    }

    fun setDisplay(o: OutputTransformV2, display: Int): OutputTransformV2 =
        drt(o, o.opendrt.copy(display = display.coerceIn(0, DISPLAY_MAX)))

    /**
     * F1-8f undo grouping: true when [after] differs from [before] only in the OpenDRT slider fields (look,
     * tonescale, cwp, cwpLimit, grey) with the mode, display and legacy branch unchanged: those frames of one
     * drag merge into one history step. A mode or display switch is a discrete step.
     */
    fun isValueEdit(before: OutputTransformV2, after: OutputTransformV2): Boolean =
        before.mode == after.mode && before.legacy == after.legacy &&
            before.opendrt.display == after.opendrt.display

    // ---- readouts ----

    /** The grey readout, e.g. "10.0 nit"; locale-independent (Locale.ROOT, the project's own rule). */
    fun greyReadout(grey: Float): String =
        String.format(Locale.ROOT, "%.1f nit", grey)
}
