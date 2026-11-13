package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LutInterpK tests (owner request 2026-11-13, the managed LUT model):
 *  - ordinal pinning: every enum's value is the C++ enum's ordinal (the
 *    ints cross the JNI seam with no lookup table — a reordering on
 *    either side must fail HERE, not silently mis-render);
 *  - parse fallbacks: absent/partial/unknown fields fall back to the
 *    legacy default (the migration rule for pre-managed records);
 *  - render/parse round trips;
 *  - signatures: any field change = a different signature (the JNI
 *    binding cache key).
 */
class LutInterpTest {

    // ---- ordinal pinning (core/include/rawedit/Color.h order) ----

    @Test
    fun gamut_ordinals_match_the_cpp_enum() {
        // GamutId { Rec709, Rec2020, DaVinciWideGamut, Acescg,
        // ArriWideGamut3, SGamut3Cine, CinemaGamut, AppleWideGamut,
        // VGamut } — Rec709/sRGB is ONE identity (same primaries); the
        // commit (e) camera gamuts APPEND; F-Gamut is deliberately
        // absent (no pinned Fujifilm gamut dataset).
        assertEquals(0, LutGamut.Rec709.value)
        assertEquals(1, LutGamut.Rec2020.value)
        assertEquals(2, LutGamut.DaVinciWideGamut.value)
        assertEquals(3, LutGamut.Acescg.value)
        assertEquals(4, LutGamut.ArriWideGamut3.value)
        assertEquals(5, LutGamut.SGamut3Cine.value)
        assertEquals(6, LutGamut.CinemaGamut.value)
        assertEquals(7, LutGamut.AppleWideGamut.value)
        assertEquals(8, LutGamut.VGamut.value)
    }

    @Test
    fun transfer_ordinals_match_the_cpp_enum() {
        // TransferId { Linear, Srgb, Rec709OETF, Gamma24,
        // DaVinciIntermediate, Acescct, ArriLogC3, SonyLog3, CanonLog3,
        // AppleLog, VLog, FujifilmFLog, Cineon } — the Rec.709 OETF is
        // the app's EXISTING linearToRec709 curve; Gamma24 is a separate
        // curve, NOT a rename; the commit (e) camera curves APPEND; one
        // AppleLog entry serves Apple Log and Apple Log 2 (the gamut
        // distinguishes them).
        assertEquals(0, LutTransfer.Linear.value)
        assertEquals(1, LutTransfer.Srgb.value)
        assertEquals(2, LutTransfer.Rec709OETF.value)
        assertEquals(3, LutTransfer.Gamma24.value)
        assertEquals(4, LutTransfer.DaVinciIntermediate.value)
        assertEquals(5, LutTransfer.Acescct.value)
        assertEquals(6, LutTransfer.ArriLogC3.value)
        assertEquals(7, LutTransfer.SonyLog3.value)
        assertEquals(8, LutTransfer.CanonLog3.value)
        assertEquals(9, LutTransfer.AppleLog.value)
        assertEquals(10, LutTransfer.VLog.value)
        assertEquals(11, LutTransfer.FujifilmFLog.value)
        assertEquals(12, LutTransfer.Cineon.value)
    }

    @Test
    fun role_ordinals_match_the_cpp_enum() {
        // LutRole { LegacyLinear709, DisplayLook, SceneLook,
        // SceneToDisplay }.
        assertEquals(0, LutRole.LegacyLinear709.value)
        assertEquals(1, LutRole.DisplayLook.value)
        assertEquals(2, LutRole.SceneLook.value)
        assertEquals(3, LutRole.SceneToDisplay.value)
    }

    // ---- parse fallbacks (the migration rule) ----

    @Test
    fun parse_null_and_non_object_is_null() {
        // A grade written before lutInterp carries no object: null (the
        // legacy stage-24 path keyed on lutId alone).
        assertNull(LutInterpK.parse(null))
        assertNull(LutInterpK.parse("not-a-map"))
    }

    @Test
    fun parse_empty_object_is_the_legacy_default() {
        // Every field falls back: Rec.709/Linear in and out,
        // LegacyLinear709 — a pre-managed record with an empty interp
        // object develops exactly as the old stage-24 path.
        assertEquals(LutInterpK.legacy(), LutInterpK.parse(emptyMap<String, Any?>()))
    }

    @Test
    fun parse_partial_object_falls_back_per_field() {
        // Only the role is specified: gamuts and transfers stay legacy.
        val parsed = LutInterpK.parse(
            mapOf("role" to 2.0),
        )
        assertEquals(LutRole.SceneLook, parsed!!.role)
        assertEquals(LutGamut.Rec709, parsed.input.gamut)
        assertEquals(LutTransfer.Linear, parsed.input.transfer)
        assertEquals(LutGamut.Rec709, parsed.output.gamut)
        assertEquals(LutTransfer.Linear, parsed.output.transfer)
    }

    @Test
    fun parse_unknown_ordinal_falls_back_to_legacy_not_crash() {
        // A future C++ enum extension must never wedge an older UI into
        // a wrong-but-silent role: unknown ordinals fall back to the
        // legacy default per field. The ordinals here must stay past the
        // LAST real member (gamut 8 V-Gamut, transfer 12 Cineon after
        // commit (e)) — 42/1234/99/-1 were chosen once and 7 later
        // became AppleWideGamut, which is exactly the scenario this test
        // guards against, so the probes now sit far past the ends.
        val parsed = LutInterpK.parse(
            mapOf(
                "role" to 99.0,
                "in" to mapOf("g" to 42.0, "t" to -1.0),
                "out" to mapOf("g" to 1234.0, "t" to 1234.0),
            ),
        )
        assertEquals(LutInterpK.legacy(), parsed)
    }

    @Test
    fun legacy_is_the_all_defaults_interpretation() {
        val legacy = LutInterpK.legacy()
        assertEquals(LutRole.LegacyLinear709, legacy.role)
        assertEquals(LutGamut.Rec709, legacy.input.gamut)
        assertEquals(LutTransfer.Linear, legacy.input.transfer)
        assertEquals(LutGamut.Rec709, legacy.output.gamut)
        assertEquals(LutTransfer.Linear, legacy.output.transfer)
        // The defaults constructor IS the legacy interpretation.
        assertEquals(LutInterpK(), legacy)
    }

    // ---- render/parse round trips ----

    @Test
    fun render_parse_round_trips_every_field() {
        val interp = LutInterpK(
            input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneToDisplay,
        )
        // Render then parse through the same JSON machinery the stores
        // use (JsonParser on the rendered object).
        val json = "{\"interp\":${interp.render()}}"
        val parsed = LutInterpK.parse(JsonParser(json).parseValue().let { (it as Map<*, *>)["interp"] })
        assertEquals(interp, parsed)
    }

    @Test
    fun render_parse_round_trips_the_legacy_default() {
        val legacy = LutInterpK.legacy()
        val json = "{\"interp\":${legacy.render()}}"
        val parsed = LutInterpK.parse(JsonParser(json).parseValue().let { (it as Map<*, *>)["interp"] })
        assertEquals(legacy, parsed)
    }

    @Test
    fun render_includes_the_schema_version() {
        // A non-null interpretation always renders v:1 so a future curve
        // migration can tell which schema wrote it.
        assertTrue(LutInterpK.legacy().render().contains("\"v\":1"))
        assertTrue(
            LutInterpK(role = LutRole.DisplayLook).render()
                .contains("\"v\":1"),
        )
    }

    // ---- signature (the JNI binding cache key) ----

    @Test
    fun signature_is_stable_and_field_sensitive() {
        val a = LutInterpK(
            input = LutEncodingK(LutGamut.Rec2020, LutTransfer.Linear),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneToDisplay,
        )
        // Same interpretation: same signature.
        assertEquals(a.signature(), a.copy().signature())
        // ANY field change: a different signature (a recompile).
        assertTrue(a.signature() != a.copy(role = LutRole.SceneLook).signature())
        assertTrue(
            a.signature() != a.copy(input = a.input.copy(transfer = LutTransfer.Srgb)).signature(),
        )
        assertTrue(
            a.signature() != a.copy(output = a.output.copy(gamut = LutGamut.Rec2020)).signature(),
        )
    }

    @Test
    fun signature_pins_the_exact_format() {
        // The bridge's resolveBinding keys on the ordinal tuple, not on
        // this string — but the string pins the format so an accidental
        // change to the cache key is caught here.
        assertEquals(
            "0|0.0>0.0",
            LutInterpK.legacy().signature(),
        )
        assertEquals(
            "3|2.4>0.2",
            LutInterpK(
                input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
                output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
                role = LutRole.SceneToDisplay,
            ).signature(),
        )
    }
}
