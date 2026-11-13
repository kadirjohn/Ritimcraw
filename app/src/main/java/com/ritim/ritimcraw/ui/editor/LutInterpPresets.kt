package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer

/**
 * The interpretation sheet's named presets (owner request 2026-11-13).
 * A preset is a SUGGESTION that fills the sheet's fields — never a claim
 * about the file: the app does NOT detect a cube's color encoding, and
 * the UI never says it did. The user confirms the interpretation; the
 * preset just starts from the common real-world authoring spaces.
 */
enum class LutInterpPreset {
    /** A creative look authored in sRGB: sRGB in and out, after the render. */
    SRGB_CREATIVE,

    /** A creative look authored around the Rec.709 OETF: the app's own
     * display curve in and out, after the render. */
    REC709_CREATIVE,

    /** A scene-to-display conversion (e.g. DWG/Intermediate -> Rec.709):
     * entered before the render; ACES/AgX applies to the base only. */
    DWG_TO_REC709,

    /** Everything picked by hand; no named starting point. */
    CUSTOM,

    /** The COL-07 stage-24 path, byte-identical — the pre-managed
     * behavior, and the sheet's tap-through default so a user who does
     * not engage with the dialog keeps the exact old pipeline. */
    LEGACY,
}

/**
 * The pure decisions behind the interpretation sheet (owner request
 * 2026-11-13): preset -> interpretation, interpretation -> preset, and
 * the grade-selection rule. JVM-tested; no Compose, no store access.
 */
object LutInterpPresets {

    /**
     * The interpretation a preset fills the sheet with. CUSTOM seeds the
     * legacy defaults so a hand-built interpretation starts from the
     * known-safe point and every field is then the user's explicit pick.
     */
    fun interpOf(preset: LutInterpPreset): LutInterpK = when (preset) {
        LutInterpPreset.SRGB_CREATIVE -> LutInterpK(
            input = LutEncodingK(LutGamut.Rec709, LutTransfer.Srgb),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Srgb),
            role = LutRole.DisplayLook,
        )
        LutInterpPreset.REC709_CREATIVE -> LutInterpK(
            input = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.DisplayLook,
        )
        LutInterpPreset.DWG_TO_REC709 -> LutInterpK(
            input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneToDisplay,
        )
        LutInterpPreset.CUSTOM -> LutInterpK.legacy()
        LutInterpPreset.LEGACY -> LutInterpK.legacy()
    }

    /**
     * The preset an interpretation matches — the inverse of [interpOf]
     * for the named presets, so the sheet's preset row snaps to the
     * matching name when a hand-edited field set happens to equal one.
     * Anything else is CUSTOM.
     */
    fun matchingPreset(interp: LutInterpK): LutInterpPreset = when (interp) {
        interpOf(LutInterpPreset.SRGB_CREATIVE) -> LutInterpPreset.SRGB_CREATIVE
        interpOf(LutInterpPreset.REC709_CREATIVE) -> LutInterpPreset.REC709_CREATIVE
        interpOf(LutInterpPreset.DWG_TO_REC709) -> LutInterpPreset.DWG_TO_REC709
        LutInterpK.legacy() -> LutInterpPreset.LEGACY
        else -> LutInterpPreset.CUSTOM
    }

    /**
     * The interpretation a grade SELECTION of [lut] carries by value:
     * the record's interpretation — or null for a legacy record, so the
     * grade takes the byte-identical stage-24 path (all five JNI binding
     * ints -1) and persists WITHOUT the lutInterp key: a legacy selection
     * renders and rewrites exactly as it did before the managed model.
     */
    fun gradeInterpOf(lut: LutInfo): LutInterpK? =
        lut.interp.takeIf { it != LutInterpK.legacy() }

    /**
     * The record row's input -> output summary (owner request
     * 2026-11-13: "list rows show input->output summary"). Technical
     * color-science names, NOT localized — the same convention as the
     * AgX look names (upstream vocabulary the user needs to match
     * against the LUT vendor's docs). The legacy record summarizes as
     * the stage-24 path it takes.
     */
    fun summaryOf(interp: LutInterpK): String {
        if (interp == LutInterpK.legacy()) return "Legacy (linear Rec.709)"
        return "${gamutShort(interp.input.gamut)} ${transferShort(interp.input.transfer)}" +
            " → ${gamutShort(interp.output.gamut)} ${transferShort(interp.output.transfer)}"
    }

    private fun gamutShort(g: LutGamut): String = when (g) {
        // Rec.709 and sRGB share primaries — ONE row name, honestly.
        LutGamut.Rec709 -> "Rec.709"
        LutGamut.Rec2020 -> "Rec.2020"
        LutGamut.DaVinciWideGamut -> "DWG"
    }

    private fun transferShort(t: LutTransfer): String = when (t) {
        // The Rec.709 OETF keeps its OWN name — never "gamma 2.4" (the
        // mission's never-rename rule); Gamma 2.4 is a separate entry.
        LutTransfer.Linear -> "Linear"
        LutTransfer.Srgb -> "sRGB"
        LutTransfer.Rec709OETF -> "Rec.709 OETF"
        LutTransfer.Gamma24 -> "Gamma 2.4"
        LutTransfer.DaVinciIntermediate -> "DaVinci Int."
    }
}
