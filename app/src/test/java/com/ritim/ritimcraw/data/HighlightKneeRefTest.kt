package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.GradeParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * D3 (adaptive Highlights knee): the per-clip luma P85 rides the grade. Unset (0) must be invisible
 * everywhere: no key written, the parcel slot at its 0.0 default; set, it survives persist/reload and
 * crosses the parcel in its own slot.
 */
class HighlightKneeRefTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unset_writes_no_key_and_a_default_grade_file_is_unchanged() {
        assertFalse(GradeStore.renderGrade(GradeK()).contains("highlightKneeRef"))
        val f = File(tmp.root, "project.json")
        GradeStore(f).put("a", GradeK(exposureEv = 0.5f))
        assertFalse(f.readText().contains("highlightKneeRef"))
    }

    @Test fun measured_ref_survives_persist_and_reload_through_the_v2_record() {
        val f = File(tmp.root, "project.json")
        val store = GradeStore(f)
        store.put("a", GradeK(exposureEv = 1.7f, highlightKneeRef = 0.00817f))
        store.putPreservingChain("a", store.get("a").copy(highlights = -40f))
        assertTrue(f.readText().contains("highlightKneeRef"))
        val back = GradeStore(f).get("a")
        assertEquals(0.00817f, back.highlightKneeRef, 0f)
        assertEquals(-40f, back.highlights, 0f)
        assertEquals(1.7f, back.exposureEv, 0f)
    }

    @Test fun v1_json_roundtrip_keeps_the_ref_and_rejects_garbage() {
        val text = GradeStore.renderGrade(GradeK(highlightKneeRef = 0.05f))
        assertTrue(text.contains("\"highlightKneeRef\":"))
        // A non-positive stored value parses back as unmeasured, never as a knee.
        val bad = "{\"version\":1,\"grades\":{\"a\":{\"highlightKneeRef\":-3.0}}}"
        assertEquals(0f, GradeStore.parseGrades(bad)["a"]?.highlightKneeRef ?: 0f, 0f)
    }

    @Test fun the_ref_crosses_the_parcel_in_slot_38_and_default_is_zero() {
        assertEquals(38, GradeParcel.SLOT_HIGHLIGHT_KNEE_REF)
        val p = GradeParcel.encode(GradeK(highlightKneeRef = 0.0123f))
        assertEquals(0.0123f, p[GradeParcel.SLOT_HIGHLIGHT_KNEE_REF], 0f)
        assertEquals(0f, GradeParcel.encode(GradeK())[GradeParcel.SLOT_HIGHLIGHT_KNEE_REF], 0f)
        assertEquals(0.0123f, GradeParcel.decode(p).highlightKneeRef, 0f)
    }
}
