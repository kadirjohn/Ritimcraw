package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LutInterpPresets tests (owner request 2026-11-13, the interpretation
 * sheet's pure decisions):
 *  - interpOf: each named preset fills the exact fields the spec names;
 *    Custom and Legacy both seed the legacy defaults (the known-safe
 *    starting point);
 *  - matchingPreset: the inverse — a preset's interpretation snaps back
 *    to that preset, a hand-built set is Custom, and legacy is Legacy;
 *  - gradeInterpOf: the grade-selection rule — a legacy record selects as
 *    null (the byte-identical stage-24 path), a managed record carries
 *    its interpretation BY VALUE;
 *  - summaryOf: the record row's input -> output summary format.
 */
class LutInterpPresetsTest {

    // ---- interpOf: the preset -> interpretation mapping ----

    @Test
    fun srgb_creative_is_srgb_in_and_out_after_the_render() {
        val interp = LutInterpPresets.interpOf(LutInterpPreset.SRGB_CREATIVE)
        assertEquals(LutEncodingK(LutGamut.Rec709, LutTransfer.Srgb), interp.input)
        assertEquals(LutEncodingK(LutGamut.Rec709, LutTransfer.Srgb), interp.output)
        assertEquals(LutRole.DisplayLook, interp.role)
    }

    @Test
    fun rec709_creative_is_the_app_display_curve_in_and_out() {
        val interp = LutInterpPresets.interpOf(LutInterpPreset.REC709_CREATIVE)
        assertEquals(LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF), interp.input)
        assertEquals(LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF), interp.output)
        assertEquals(LutRole.DisplayLook, interp.role)
    }

    @Test
    fun dwg_to_rec709_is_a_scene_to_display_conversion() {
        val interp = LutInterpPresets.interpOf(LutInterpPreset.DWG_TO_REC709)
        assertEquals(
            LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
            interp.input,
        )
        assertEquals(LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF), interp.output)
        assertEquals(LutRole.SceneToDisplay, interp.role)
    }

    @Test
    fun custom_and_legacy_seed_the_legacy_defaults() {
        // Custom starts from the known-safe point: every field is then the
        // user's explicit pick. Legacy IS the exact previous pipeline.
        assertEquals(LutInterpK.legacy(), LutInterpPresets.interpOf(LutInterpPreset.CUSTOM))
        assertEquals(LutInterpK.legacy(), LutInterpPresets.interpOf(LutInterpPreset.LEGACY))
    }

    // ---- matchingPreset: the interpretation -> preset inverse ----

    @Test
    fun every_named_presets_interpretation_snaps_back_to_it() {
        // The sheet's duality: a hand-built field set that happens to
        // equal a preset shows that preset's name, never a false Custom.
        assertEquals(
            LutInterpPreset.SRGB_CREATIVE,
            LutInterpPresets.matchingPreset(
                LutInterpPresets.interpOf(LutInterpPreset.SRGB_CREATIVE),
            ),
        )
        assertEquals(
            LutInterpPreset.REC709_CREATIVE,
            LutInterpPresets.matchingPreset(
                LutInterpPresets.interpOf(LutInterpPreset.REC709_CREATIVE),
            ),
        )
        assertEquals(
            LutInterpPreset.DWG_TO_REC709,
            LutInterpPresets.matchingPreset(
                LutInterpPresets.interpOf(LutInterpPreset.DWG_TO_REC709),
            ),
        )
    }

    @Test
    fun legacy_interpretation_matches_the_legacy_preset() {
        assertEquals(
            LutInterpPreset.LEGACY,
            LutInterpPresets.matchingPreset(LutInterpK.legacy()),
        )
    }

    @Test
    fun a_hand_built_interpretation_is_custom() {
        // One field off the sRGB preset: Custom, never the preset's name
        // (the honest row — the sheet never claims a match it cannot see).
        val handBuilt = LutInterpPresets.interpOf(LutInterpPreset.SRGB_CREATIVE)
            .copy(role = LutRole.SceneLook)
        assertEquals(LutInterpPreset.CUSTOM, LutInterpPresets.matchingPreset(handBuilt))
    }

    // ---- gradeInterpOf: the grade-selection rule ----

    @Test
    fun a_legacy_record_selects_as_null_the_stage24_path() {
        // Null lutInterp = the byte-identical stage-24 path (all five
        // JNI binding ints -1) and a grade that persists WITHOUT the
        // lutInterp key — a legacy selection renders and rewrites exactly
        // as before the managed model.
        assertNull(LutInterpPresets.gradeInterpOf(LutInfo(id = "a", name = "A")))
    }

    @Test
    fun a_managed_record_selects_its_interpretation_by_value() {
        val interp = LutInterpPresets.interpOf(LutInterpPreset.DWG_TO_REC709)
        assertEquals(
            interp,
            LutInterpPresets.gradeInterpOf(LutInfo(id = "a", name = "A", interp = interp)),
        )
    }

    // ---- summaryOf: the record row's input -> output summary ----

    @Test
    fun legacy_summarizes_as_the_stage24_path() {
        assertEquals(
            "Legacy (linear Rec.709)",
            LutInterpPresets.summaryOf(LutInterpK.legacy()),
        )
    }

    @Test
    fun a_managed_record_summarizes_input_arrow_output() {
        // "DWG DaVinci Int. → Rec.709 Rec.709 OETF": the technical names
        // the user needs to match against the LUT vendor's docs, never
        // localized, and the Rec.709 OETF keeps its own name (never
        // "gamma 2.4").
        val interp = LutInterpPresets.interpOf(LutInterpPreset.DWG_TO_REC709)
        assertEquals(
            "DWG DaVinci Int. → Rec.709 Rec.709 OETF",
            LutInterpPresets.summaryOf(interp),
        )
    }

    @Test
    fun the_srgb_preset_summarizes_with_its_own_transfer_names() {
        val interp = LutInterpPresets.interpOf(LutInterpPreset.SRGB_CREATIVE)
        assertEquals(
            "Rec.709 sRGB → Rec.709 sRGB",
            LutInterpPresets.summaryOf(interp),
        )
    }
}
