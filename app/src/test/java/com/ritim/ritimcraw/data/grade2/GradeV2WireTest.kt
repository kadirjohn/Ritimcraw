package com.ritim.ritimcraw.data.grade2

import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema-2 wire (F1-3): one render, one parse, both directions, plus
 * the omission rules that keep records byte-stable.
 *
 * THE MIGRATED-RECORD CONTRACT (K2/K3): a v1 grade's v2 twin must parse
 * back to the SAME GradeK through the adapter — proven here on every field,
 * not by rendering but by the round trip the whole file goes through.
 */
class GradeV2WireTest {

    private fun parseRecord(text: String): ClipGradeV2 {
        val v = JsonParser(text).parseValue()
        @Suppress("UNCHECKED_CAST")
        return ClipGradeV2.parse(v as Map<String, Any?>)
    }

    // ---- round trips ----

    @Test
    fun neutral_grade_round_trips() {
        val g = ClipGradeV2()
        val parsed = parseRecord(GradeV2Fiber.render(g))
        assertEquals(g, parsed)
    }

    @Test
    fun every_group_round_trips() {
        val g = ClipGradeV2(
            develop = DevelopV2(
                exposureEv = 0.8f, tempK = 4600f, tint = 3f, asShot = false,
                highlightReconstruction = false, keepVignetting = false,
                noiseReduction = 40f, chromaDenoise = 25f, temporalNr = true,
                temporalStrength = 60f, sharpness = 12f, detail = -5f,
            ),
            legacy = LegacyControlsV2(
                highlights = -25f, blackPoint = -6f, whitePoint = 4f, shadows = -2f,
                midtones = 9f, contrast = 15f, saturation = 10f,
                wheelShadowX = 0.3f, wheelShadowY = -0.2f, wheelMidX = -0.5f,
                wheelMidY = 0.1f, wheelHighX = 0.7f, wheelHighY = 0.8f,
                splitShadowHue = 210f, splitHighlightHue = 45f, splitBalance = -30f,
            ),
            nodes = listOf(
                GradeNodeV2(
                    enabled = false, lift = NodeWheelV2(0.2f, -0.1f, 0.05f),
                    gamma = NodeWheelV2(-0.3f, 0.2f, -0.1f),
                    gain = NodeWheelV2(0.4f, 0.5f, 0.2f),
                    offset = NodeWheelV2(-0.2f, 0.1f, 0.3f),
                    contrast = 1.4f, pivot = 0.3f, sat = 1.2f, hue = 15f,
                    temp = 0.3f, tint = -0.2f,
                    curveY = NodeCurveV2(listOf(0f to 0.05f, 0.5f to 0.6f, 1f to 1f)),
                    curveR = NodeCurveV2(listOf(0f to 0f, 1f to 0.9f)),
                    logShadow = NodeWheelV2(0.1f, 0f, 0f),
                    logMid = NodeWheelV2(0f, 0.2f, 0f),
                    logHi = NodeWheelV2(0f, 0f, -0.1f),
                    logLow = 0.1f, logHigh = 0.6f, mix = 0.5f,
                    lutId = "cube-abc", lutMix = 0.25f,
                ),
            ),
            output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT,
                legacy = LegacyOutputV2(tonemap = 2, lutId = "x", lutStrength = 40f),
                opendrt = OpenDrtParamsV2(look = 3, tonescale = 5, cwp = 2, cwpLimit = 0.5f,
                    grey = 12f, display = 2)),
            transform = TransformV2(
                cropAspect = 1, cropX = 0.1f, cropY = 0.2f, cropW = 0.5f, cropH = 0.7f,
                straightenDeg = 7.5f,
                stab = StabV2(engine = 1, mode = 2, strength = 85f, zoomLimit = 1.15f,
                    trimMs = -40f, focalMm = 24f),
            ),
        )
        val parsed = parseRecord(GradeV2Fiber.render(g))
        assertEquals(g, parsed)
    }

    @Test
    fun curve_points_survive_with_four_decimals_and_identity_detection() {
        val withCurve = GradeNodeV2(
            curveB = NodeCurveV2(listOf(0f to 0f, 0.25f to 0.3f, 1f to 1f)),
        )
        val parsed = parseRecord(GradeV2Fiber.render(ClipGradeV2(nodes = listOf(withCurve))))
        assertEquals(withCurve.curveB, parsed.nodes.single().curveB)
        assertTrue(parsed.nodes.single().curveY.isIdentity())
        assertTrue(parsed.nodes.single().curveR.isIdentity())
        assertTrue(parsed.nodes.single().curveG.isIdentity())
    }

    // ---- omission rules (byte-stability) ----

    @Test
    fun neutral_crop_writes_no_crop_keys_and_off_stab_writes_no_group() {
        val r = GradeV2Fiber.render(ClipGradeV2())
        assertTrue(!r.contains("cropAspect"))
        assertTrue(!r.contains("stab"))
        // An empty transform group still renders (the shape is fixed).
        assertTrue(r.contains("\"transform\":{}"))
    }

    @Test
    fun a_set_crop_writes_all_six_keys_a_set_stab_writes_the_group_with_engine() {
        val r = GradeV2Fiber.render(
            ClipGradeV2(
                transform = TransformV2(cropAspect = 2, cropX = 0.1f,
                    stab = StabV2(engine = 0)),
            ),
        )
        for (key in listOf("cropAspect", "cropX", "cropY", "cropW", "cropH", "straightenDeg")) {
            assertTrue("missing $key", r.contains("\"$key\":"))
        }
        // K10: the engine is EXPLICIT (0 = gyro, still written — a v2 reader
        // never re-derives the absent-key default), and all sub values ride.
        assertTrue(r.contains("\"stab\":{\"engine\":0,\"mode\":0,\"strength\":50.0," +
            "\"zoomLimit\":1.25,\"trimMs\":0.0,\"focalMm\":0.0}"))
    }

    @Test
    fun a_set_crop_with_off_stab_writes_crop_but_no_stab() {
        val r = GradeV2Fiber.render(
            ClipGradeV2(transform = TransformV2(cropAspect = 3)),
        )
        assertTrue(r.contains("\"cropAspect\":3"))
        assertTrue(!r.contains("stab"))
    }

    // ---- the adapter (K2): the v2 twin reads as the same GradeK ----

    @Test
    fun v1_grade_its_v2_twin_and_the_adapter_agree_field_by_field() {
        val v1 = com.ritim.ritimcraw.data.GradeK(
            exposureEv = 0.8f, highlights = -25f, tempK = 4600f, tint = 3f,
            saturation = 10f, blackPoint = -5f, whitePoint = 4f, shadows = -2f,
            midtones = 1f, contrast = 8f, asShot = false,
            highlightReconstruction = false, keepVignetting = false,
            sharpness = 15f, detail = -3f, tonemap = 1,
            lutId = "abc123", lutStrength = 40f,
            noiseReduction = 30f, chromaDenoise = 55f, temporalNr = true,
            temporalStrength = 35f, stabilization = true, stabilizationMode = 2,
            stabilizationStrength = 85f, stabilizationZoomLimit = 1.15f,
            stabilizationTrimMs = -40f, stabilizationFocalMm = 24f,
            stabilizationEngine = 1, cropAspect = 1, cropX = 0.25f, cropY = 0.1f,
            cropW = 0.5f, cropH = 0.6f, straightenDeg = 7.5f,
            wheelShadowX = 0.3f, wheelShadowY = -0.2f, wheelMidX = -0.8f,
            wheelMidY = 0.15f, wheelHighX = 0.6f, wheelHighY = 0.9f,
            splitShadowHue = 210f, splitHighlightHue = 45f, splitBalance = -30f,
        )
        // The wire round trip: v1 -> its v2 twin -> render -> parse -> adapter.
        val wire = GradeV2Fiber.render(v1.toClipGradeV2())
        val back = parseRecord(wire).toGradeK()
        assertEquals(v1.copy(version = com.ritim.ritimcraw.data.GradeK.SCHEMA_VERSION), back)
    }

    @Test
    fun nodes_or_opendrt_records_refuse_the_adapter_honestly() {
        assertNull(ClipGradeV2(nodes = listOf(GradeNodeV2())).toGradeK())
        assertNull(
            ClipGradeV2(output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT)).toGradeK(),
        )
    }

    @Test
    fun the_neutral_record_is_the_neutral_gradek() {
        assertEquals(com.ritim.ritimcraw.data.GradeK(), ClipGradeV2().toGradeK())
    }

    // ---- the shared parser router ----

    @Test
    fun mapOfGrade_dispatches_on_the_v2_marker() {
        // A v2 record parses through the adapter.
        val v2record = JsonParser(GradeV2Fiber.render(ClipGradeV2(develop = DevelopV2(exposureEv = 1.5f))))
            .parseValue()
        @Suppress("UNCHECKED_CAST")
        val grade = GradeStore.mapOfGrade(v2record as Map<String, Any?>)!!
        assertEquals(1.5f, grade.exposureEv, 0f)
        // A flat v1 record parses through the old path.
        val v1 = mapOf<String, Any?>("exposureEv" to 0.75)
        assertEquals(0.75f, GradeStore.mapOfGrade(v1)!!.exposureEv, 0f)
    }
}
