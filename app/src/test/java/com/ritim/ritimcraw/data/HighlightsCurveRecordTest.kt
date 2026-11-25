package com.ritim.ritimcraw.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Old records keep the legacy stage-15 knee; new grades take v1; the curve survives a round trip. */
class HighlightsCurveRecordTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun new_grades_default_to_v1() {
        assertEquals(HIGHLIGHTS_CURVE_V1, GradeK().highlightsCurve)
    }

    @Test
    fun a_record_without_the_field_reads_as_legacy_and_is_written_back_without_it() {
        val f = File(tmp.root, "project.json")
        f.writeText("{\"version\":1,\"grades\":{\"a\":{\"version\":1,\"exposureEv\":0.5,\"highlights\":-40.0}}}")
        val store = GradeStore(f)
        assertEquals(HIGHLIGHTS_CURVE_LEGACY, store.get("a").highlightsCurve)
        store.put("a", store.get("a"))
        assertFalse(f.readText().contains("highlightsCurve"))
        assertEquals(HIGHLIGHTS_CURVE_LEGACY, GradeStore(f).get("a").highlightsCurve)
    }

    @Test
    fun v1_curve_round_trips() {
        val f = File(tmp.root, "project.json")
        GradeStore(f).put("b", GradeK(highlights = -30f, highlightsCurve = HIGHLIGHTS_CURVE_V1))
        assertTrue(f.readText().contains("\"highlightsCurve\":1"))
        assertEquals(HIGHLIGHTS_CURVE_V1, GradeStore(f).get("b").highlightsCurve)
    }
}
