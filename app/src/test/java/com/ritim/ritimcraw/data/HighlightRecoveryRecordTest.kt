package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.grade2.toClipGradeV2
import com.ritim.ritimcraw.data.grade2.toGradeKLenient
import com.ritim.ritimcraw.engine.GradeParcel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Highlight recovery: off by default, written only when on, absent reads as off, survives the v2 record and the parcel. */
class HighlightRecoveryRecordTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun new_grades_have_recovery_off() {
        assertFalse(GradeK().highlightRecovery)
    }

    @Test
    fun a_record_without_the_field_reads_off_and_is_written_back_without_it() {
        val f = File(tmp.root, "project.json")
        f.writeText("{\"version\":1,\"grades\":{\"a\":{\"version\":1,\"exposureEv\":0.5,\"highlights\":-40.0}}}")
        val store = GradeStore(f)
        assertFalse(store.get("a").highlightRecovery)
        store.put("a", store.get("a"))
        assertFalse(f.readText().contains("highlightRecovery"))
    }

    @Test
    fun recovery_on_round_trips_through_the_store() {
        val f = File(tmp.root, "project.json")
        GradeStore(f).put("b", GradeK(highlightRecovery = true))
        assertTrue(f.readText().contains("\"highlightRecovery\":true"))
        assertTrue(GradeStore(f).get("b").highlightRecovery)
    }

    @Test
    fun recovery_survives_the_v2_record_and_the_parcel() {
        val g = GradeK(exposureEv = 0.5f, highlightRecovery = true)
        assertTrue(g.toClipGradeV2().toGradeKLenient().highlightRecovery)
        assertFalse(GradeK().toClipGradeV2().toGradeKLenient().highlightRecovery)
        val parcel = GradeParcel.encode(g)
        assertEquals(1.0f, parcel[GradeParcel.SLOT_HIGHLIGHT_RECOVERY])
        assertTrue(GradeParcel.decode(parcel).highlightRecovery)
        assertEquals(0.0f, GradeParcel.encode(GradeK())[GradeParcel.SLOT_HIGHLIGHT_RECOVERY])
    }
}
