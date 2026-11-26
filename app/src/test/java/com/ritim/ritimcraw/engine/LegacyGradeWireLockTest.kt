package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE K2 REGRESSION PROOF (color model v2 F1-6, plan §6.3/§10 layer 2):
 * today's legacy-mode output — GradeK -> GradeMapping -> the 29-slot
 * parcel, and GradeK -> the wire renderer — PINNED as literal bytes for a
 * fixed grade fixture, so every v2 door added after this test must keep
 * legacy grades byte-identical (the "pixels/parcels byte-match today's
 * main" proof the F1-6 mission names; pixels themselves are only
 * provable device-side, but the PARCEL is the renderer's whole parameter
 * packet — GradeParcel's own "parity of the parcel is parity of the
 * render" contract, and GoldenV1MigrationParityTest already pins the
 * v1-parse vs migrate-parse equality; THIS test pins the ABSOLUTE bytes
 * against drift in the mapping itself).
 *
 * The fixture is the golden migration's own "full-grade" shape (every
 * control set, a managed LUT interpretation applied by value, the image
 * stab engine) so a neutral-only grade can never mask a field-order or
 * offset bug, plus the neural default (the never-graded clip's grade).
 */
class LegacyGradeWireLockTest {

    /**
     * The BIT-exact float compare (JUnit 4.13.2's assertArrayEquals
     * overload set has no delta-less FloatArray form): every slot must
     * carry the same raw bits — exactly what "byte-identical parcel"
     * means.
     */
    private fun assertParcelEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals("parcel length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(
                "parcel slot $i (expected ${expected[i]}, got ${actual[i]})",
                expected[i].toRawBits(),
                actual[i].toRawBits(),
            )
        }
    }


    /** The full-coverage fixture: every scalar control non-neutral. */
    private val fullGrade = GradeK(
        exposureEv = 0.8f,
        highlights = -30f,
        tempK = 6100f,
        tint = 12f,
        saturation = 22f,
        blackPoint = 6f,
        whitePoint = -4f,
        shadows = 18f,
        midtones = -9f,
        contrast = 14f,
        asShot = false,
        highlightReconstruction = false,
        keepVignetting = false,
        sharpness = 31f,
        detail = -17f,
        tonemap = 3,
        lutId = "cube-44",
        lutStrength = 65f,
        noiseReduction = 40f,
        chromaDenoise = 25f,
        temporalNr = true,
        temporalStrength = 70f,
        stabilization = true,
        stabilizationMode = 2,
        stabilizationStrength = 80f,
        stabilizationZoomLimit = 1.4f,
        stabilizationTrimMs = 12.5f,
        stabilizationFocalMm = 24f,
        stabilizationEngine = 1,
        cropAspect = 1,
        cropX = 0.1f,
        cropY = 0.2f,
        cropW = 0.7f,
        cropH = 0.6f,
        straightenDeg = 3.5f,
        wheelShadowX = 0.11f,
        wheelShadowY = -0.22f,
        wheelMidX = -0.33f,
        wheelMidY = 0.44f,
        wheelHighX = 0.55f,
        wheelHighY = 0.66f,
        splitShadowHue = 210f,
        splitHighlightHue = 45f,
        splitBalance = -25f,
        lutInterp = LutInterpK(
            input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneToDisplay,
        ),
    )

    /**
     * The parcel: today's 39 slots (0..29 unchanged since the recovery
     * append, the D1 rawLevels octet at [30..37], D3 highlightKneeRef at [38]), byte for byte. The expected array is
     * hand-written FIRST (the values ARE the fixture's fields in the
     * SLOT_* order GradeParcel documents: chromaDenoise, noiseReduction,
     * temporalNr, temporalStrength, sharpness, detail, exposureEv,
     * highlights, blackPoint, whitePoint, shadows, midtones, contrast,
     * tempK, tint, saturation, asShot, wheel..., split..., the two RAW
     * toggles, tonemap, highlightRecovery, then the rawLevels octet) — a
     * drifted mapping, a reordered slot or an accidentally renumbered
     * constant fails here and nowhere else. The D1 octet's every slot is
     * the -1 sentinel (this fixture carries no override): the appended
     * slots must NOT change what a legacy grade renders.
     */
    @Test
    fun the_full_grade_fixture_encodes_to_todays_exact_parcel() {
        val parcel = GradeParcel.encode(fullGrade)
        assertEquals(GradeParcel.SLOT_COUNT, parcel.size)
        assertParcelEquals(
            floatArrayOf(
                // [0] chromaDenoise  [1] noiseReduction
                25.0f, 40.0f,
                // [2] temporalNr (0/1)  [3] temporalStrength
                1.0f, 70.0f,
                // [4] sharpness  [5] detail
                31.0f, -17.0f,
                // [6] exposureEv  [7] highlights
                0.8f, -30.0f,
                // [8] blackPoint  [9] whitePoint
                6.0f, -4.0f,
                // [10] shadows  [11] midtones
                18.0f, -9.0f,
                // [12] contrast  [13] tempK  [14] tint  [15] saturation
                14.0f, 6100.0f, 12.0f, 22.0f,
                // [16] asShot (false — this fixture drives manual WB)
                0.0f,
                // [17..22] the color wheel
                0.11f, -0.22f, -0.33f, 0.44f, 0.55f, 0.66f,
                // [23..25] split-tone
                210.0f, 45.0f, -25.0f,
                // [26] highlightReconstruction  [27] keepVignetting
                0.0f, 0.0f,
                // [28] tonemap  [29] highlightRecovery
                3.0f, 0.0f,
                // [30..37] the D1 rawLevels octet (appended, all sentinels —
                // this legacy fixture sets no override)
                -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
                // [38] highlightKneeRef (D3, appended; 0 = unmeasured = the legacy knee 0.18 —
                // golden change documented in the D3 commit: one new slot, default 0.0)
                0.0f,
            ),
            parcel,
        )
        // And every slot finite (the decode's own rule).
        parcel.forEach { assertTrue(it.isFinite()) }
    }

    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)

    /**
     * The neutral default's parcel: all zeros/off except tempK 5600 (the
     * asShot-neutral default) and the D1 octet's sentinels — the
     * never-graded clip rides today's exact shape; K2's hidden trap was
     * "a neutral record silently changing pixels", so the NEUTRAL bytes
     * are at least as load-bearing as the full ones.
     */
    @Test
    fun the_neutral_grade_encodes_to_todays_exact_parcel() {
        assertParcelEquals(
            floatArrayOf(
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 5600.0f, 0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                -1.0f, -1.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                0.0f, // [29] highlightRecovery
                // [30..37] the D1 rawLevels octet: appended, all sentinels
                // (metadata semantics — the default grade is bit-identical
                // to the pre-D1 pipeline)
                -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
                // [38] highlightKneeRef (D3, appended; 0 = unmeasured = the legacy knee 0.18 —
                // golden change documented in the D3 commit: one new slot, default 0.0)
                0.0f,
            ),
            GradeParcel.encode(GradeK()),
        )
    }

    /**
     * GradeMapping.toNative's field-for-field contract (theJNI argument
     * twin of the parcel): pinned WITH the lutStrength 0..100 -> 0..1
     * fold — the one non-identity mapping, the classic drift point.
     */
    @Test
    fun the_full_grade_maps_to_todays_exact_native_args() {
        val a = GradeMapping.toNative(fullGrade)
        assertEquals(0.8f, a.exposureEv, 0f)
        assertEquals(-30f, a.highlights, 0f)
        assertEquals(6100f, a.tempK, 0f)
        assertEquals(12f, a.tint, 0f)
        assertEquals(22f, a.saturation, 0f)
        assertEquals(6f, a.blackPoint, 0f)
        assertEquals(-4f, a.whitePoint, 0f)
        assertEquals(18f, a.shadows, 0f)
        assertEquals(-9f, a.midtones, 0f)
        assertEquals(14f, a.contrast, 0f)
        assertEquals(false, a.asShot)
        assertEquals(false, a.highlightReconstruction)
        assertEquals(false, a.keepVignetting)
        assertEquals(31f, a.sharpness, 0f)
        assertEquals(-17f, a.detail, 0f)
        assertEquals(3, a.tonemap)
        assertEquals(0.65f, a.lutStrength, 0f) // 65% -> 0.65
        assertEquals(40f, a.noiseReduction, 0f)
        assertEquals(25f, a.chromaDenoise, 0f)
        assertEquals(true, a.temporalNr)
        assertEquals(70f, a.temporalStrength, 0f)
        assertEquals(0.11f, a.wheelShadowX, 0f)
        assertEquals(-0.22f, a.wheelShadowY, 0f)
        assertEquals(-0.33f, a.wheelMidX, 0f)
        assertEquals(0.44f, a.wheelMidY, 0f)
        assertEquals(0.55f, a.wheelHighX, 0f)
        assertEquals(0.66f, a.wheelHighY, 0f)
        assertEquals(210f, a.splitShadowHue, 0f)
        assertEquals(45f, a.splitHighlightHue, 0f)
        assertEquals(-25f, a.splitBalance, 0f)
    }

    /**
     * The wire record the store writes for the SAME fixture (the v2 twin,
     * F1-3's renderer): pinned as bytes so a v2 door can never change what
     * a legacy save writes (byte-stability is the migration proof's own
     * currency; golden_v2_project.json pins the FILE, this pins the
     * single record).
     */
    @Test
    fun the_full_grade_renders_to_todays_exact_wire_record() {
        val wire = GradeStore.renderGradeWire(fullGrade)
        assertEquals(
            "{\"v2\":2" +
                ",\"develop\":{\"exposureEv\":0.8,\"tempK\":6100.0,\"tint\":12.0," +
                "\"asShot\":false,\"highlightReconstruction\":false," +
                "\"keepVignetting\":false,\"noiseReduction\":40.0," +
                "\"chromaDenoise\":25.0,\"temporalNr\":true," +
                "\"temporalStrength\":70.0,\"sharpness\":31.0,\"detail\":-17.0}" +
                ",\"legacy\":{\"highlights\":-30.0,\"blackPoint\":6.0," +
                "\"whitePoint\":-4.0,\"shadows\":18.0,\"midtones\":-9.0," +
                "\"contrast\":14.0,\"saturation\":22.0" +
                ",\"wheelShadowX\":0.11,\"wheelShadowY\":-0.22" +
                ",\"wheelMidX\":-0.33,\"wheelMidY\":0.44" +
                ",\"wheelHighX\":0.55,\"wheelHighY\":0.66" +
                ",\"splitShadowHue\":210.0,\"splitHighlightHue\":45.0," +
                "\"splitBalance\":-25.0}" +
                ",\"nodes\":[]" +
                ",\"output\":{\"mode\":\"legacy\",\"legacy\":{\"tonemap\":3," +
                "\"lutId\":\"cube-44\",\"lutStrength\":65.0" +
                ",\"lutInterp\":{\"v\":1,\"role\":3,\"in\":{\"g\":2,\"t\":4}," +
                "\"out\":{\"g\":0,\"t\":2}}}," +
                "\"opendrt\":{\"look\":0,\"tonescale\":0,\"cwp\":0," +
                "\"cwpLimit\":0.25,\"grey\":10.0,\"display\":0}}" +
                ",\"transform\":{\"cropAspect\":1,\"cropX\":0.1,\"cropY\":0.2," +
                "\"cropW\":0.7,\"cropH\":0.6,\"straightenDeg\":3.5" +
                ",\"stab\":{\"engine\":1,\"mode\":2,\"strength\":80.0," +
                "\"zoomLimit\":1.4,\"trimMs\":12.5,\"focalMm\":24.0}}}",
            wire,
        )
    }

    /**
     * The adapter's round-trip leg (K2's other direction): today's wire
     * record parses back to the SAME GradeK — the same fixture, both
     * ways, so the byte pin above cannot rot into a new-format fait
     * accompli.
     */
    @Test
    fun the_wire_record_round_trips_the_fixture() {
        val wire = GradeStore.renderGradeWire(fullGrade)
        val parsed = com.ritim.ritimcraw.data.JsonParser(wire).parseValue() as Map<*, *>
        val back = GradeStore.mapOfGrade(parsed)!!
        assertEquals(fullGrade.copy(version = GradeK.SCHEMA_VERSION), back)
        assertParcelEquals(GradeParcel.encode(fullGrade), GradeParcel.encode(back))
    }
}
