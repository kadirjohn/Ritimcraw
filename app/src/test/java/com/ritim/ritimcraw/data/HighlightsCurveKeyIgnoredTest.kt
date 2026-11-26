package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.grade2.DevelopV2
import com.ritim.ritimcraw.data.grade2.GradeV2Migration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The highlights curve is not versioned: the stage-15 knee is 0.18 for every record. A stored
 * "highlightsCurve" key (written by the short-lived versioning) is ignored on read and never written.
 */
class HighlightsCurveKeyIgnoredTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun a_stored_curve_key_is_ignored_on_read_and_never_written_back() {
        for (curve in listOf("0", "1")) {
            val f = File(tmp.newFolder(), "project.json")
            f.writeText(
                "{\"version\":1,\"grades\":{\"a\":{\"version\":1,\"exposureEv\":0.5,\"highlights\":-40.0," +
                    "\"highlightsCurve\":$curve}}}",
            )
            val store = GradeStore(f)
            assertEquals(GradeK(exposureEv = 0.5f, highlights = -40f), store.get("a"))
            store.put("a", store.get("a"))
            assertFalse(f.readText().contains("highlightsCurve"))
            assertEquals(GradeK(exposureEv = 0.5f, highlights = -40f), GradeStore(f).get("a"))
        }
    }

    @Test
    fun the_migration_drops_the_key_and_a_v2_develop_block_ignores_it() {
        @Suppress("UNCHECKED_CAST")
        val v2 = GradeV2Migration.convertGradeCarrier(
            linkedMapOf<String, Any?>("version" to 1.0, "highlights" to -40.0, "highlightsCurve" to 0.0),
        ) as Map<String, Any?>
        assertFalse(v2.toString().contains("highlightsCurve"))

        val develop = DevelopV2.parse(mapOf("exposureEv" to 0.5, "highlightsCurve" to 0.0))
        assertEquals(DevelopV2(exposureEv = 0.5f), develop)
    }
}
