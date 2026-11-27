package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.grade2.toClipGradeV2
import com.ritim.ritimcraw.data.grade2.toGradeKLenient
import com.ritim.ritimcraw.engine.GradeParcel
import com.ritim.ritimcraw.ui.editor.GradeHistory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Glitter suppression: off by default, written only when on, absent reads as off, survives the v2 record, the parcel and undo. */
class GlitterSuppressRecordTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun new_grades_have_it_off() {
        assertFalse(GradeK().glitterSuppress)
    }

    @Test
    fun a_record_without_the_field_reads_off_and_is_written_back_without_it() {
        val f = File(tmp.root, "project.json")
        f.writeText("{\"version\":1,\"grades\":{\"a\":{\"version\":1,\"exposureEv\":0.5,\"highlights\":-40.0}}}")
        val store = GradeStore(f)
        assertFalse(store.get("a").glitterSuppress)
        store.put("a", store.get("a"))
        assertFalse(f.readText().contains("glitterSuppress"))
    }

    @Test
    fun on_round_trips_through_the_store() {
        val f = File(tmp.root, "project.json")
        GradeStore(f).put("b", GradeK(glitterSuppress = true))
        assertTrue(f.readText().contains("\"glitterSuppress\":true"))
        assertTrue(GradeStore(f).get("b").glitterSuppress)
    }

    @Test
    fun it_survives_the_v2_record_and_the_parcel() {
        val g = GradeK(exposureEv = 0.5f, glitterSuppress = true)
        assertTrue(g.toClipGradeV2().toGradeKLenient().glitterSuppress)
        assertFalse(GradeK().toClipGradeV2().toGradeKLenient().glitterSuppress)
        val parcel = GradeParcel.encode(g)
        assertEquals(1.0f, parcel[GradeParcel.SLOT_GLITTER_SUPPRESS])
        assertEquals(GradeParcel.SLOT_COUNT - 1, GradeParcel.SLOT_GLITTER_SUPPRESS) // appended at the END
        assertTrue(GradeParcel.decode(parcel).glitterSuppress)
        assertEquals(0.0f, GradeParcel.encode(GradeK())[GradeParcel.SLOT_GLITTER_SUPPRESS])
    }

    @Test
    fun toggling_it_is_one_undo_step() {
        val h = GradeHistory(GradeK())
        h.record(GradeK(glitterSuppress = true), null)
        assertTrue(h.canUndo)
        assertFalse(h.undo()!!.glitterSuppress)
        assertTrue(h.redo()!!.glitterSuppress)
    }
}
