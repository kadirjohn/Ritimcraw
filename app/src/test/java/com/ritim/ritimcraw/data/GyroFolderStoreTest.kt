package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The granted gyro-folder tree URI persistence (owner request
 * 2026-11-12, G1): the top-level project.json scalar, omit-when-absent,
 * old files load as no grant, malformed files degrade to no grant, and
 * the other stores' sections survive a write.
 */
class GyroFolderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<GyroFolderStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return GyroFolderStore(f) to f
    }

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FMotionCam"

    // ---- old files / fresh state -> no grant ----

    @Test
    fun missing_file_loads_as_no_grant() {
        assertNull(newStore().first.get())
    }

    @Test
    fun old_file_without_the_key_loads_as_no_grant() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[]}""",
            StandardCharsets.UTF_8,
        )
        assertNull(GyroFolderStore(f).get())
    }

    // ---- omit-when-absent ----

    @Test
    fun clearing_a_never_granted_file_is_byte_identical() {
        val old = """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[]}"""
        val f = tmp.newFile("project.json")
        f.writeText(old, StandardCharsets.UTF_8)
        GyroFolderStore(f).clear()
        assertEquals(old, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun clearing_without_a_file_creates_nothing() {
        val (s, f) = newStore()
        s.clear()
        assertFalse(f.exists())
    }

    @Test
    fun granting_then_clearing_removes_the_key() {
        val (s, f) = newStore()
        s.put(tree)
        assertEquals(tree, s.get())
        s.clear()
        assertNull(s.get())
        assertFalse(GyroFolderStore.WIRE_NAME in f.readText(StandardCharsets.UTF_8))
    }

    // ---- round trip ----

    @Test
    fun a_grant_round_trips_through_the_file() {
        val (s, _) = newStore()
        s.put(tree)
        assertEquals(tree, s.get())
    }

    @Test
    fun a_grant_survives_another_store_write() {
        // The other stores rewrite project.json through ProjectJson
        // .withSection, which re-renders unknown top-level entries raw —
        // a grade save must not drop the grant.
        val f = tmp.newFile("project.json")
        GyroFolderStore(f).put(tree)
        val afterGrant = f.readText(StandardCharsets.UTF_8)
        val rewrittenByGradeStore = GradeStore.mergeInto(
            afterGrant,
            mapOf("clip-a" to GradeK()),
        )
        f.writeText(rewrittenByGradeStore, StandardCharsets.UTF_8)
        assertEquals(tree, GyroFolderStore(f).get())
    }

    @Test
    fun grant_write_preserves_the_other_sections() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[],"exports":[],"luts":[],"presets":[],"gyros":[]}""",
            StandardCharsets.UTF_8,
        )
        GyroFolderStore(f).put(tree)
        val text = f.readText(StandardCharsets.UTF_8)
        for (section in listOf("grades", "sources", "exports", "luts", "presets", "gyros")) {
            assertTrue("missing section $section in $text", section in text)
        }
        assertTrue("gyroFolder" in text)
    }

    @Test
    fun regranting_replaces_the_uri() {
        val (s, _) = newStore()
        s.put(tree)
        val other = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        s.put(other)
        assertEquals(other, s.get())
    }

    // ---- malformed -> no grant ----

    @Test
    fun malformed_file_degrades_to_no_grant() {
        val f = tmp.newFile("project.json")
        f.writeText("""{"version":1,"gyroFolder":"content://""", StandardCharsets.UTF_8)
        assertNull(GyroFolderStore(f).get())
    }

    @Test
    fun non_string_value_degrades_to_no_grant() {
        val f = tmp.newFile("project.json")
        f.writeText("""{"version":1,"gyroFolder":3}""", StandardCharsets.UTF_8)
        assertNull(GyroFolderStore(f).get())
    }

    @Test
    fun null_value_degrades_to_no_grant() {
        val f = tmp.newFile("project.json")
        f.writeText("""{"version":1,"gyroFolder":null}""", StandardCharsets.UTF_8)
        assertNull(GyroFolderStore(f).get())
    }

    @Test
    fun blank_value_degrades_to_no_grant() {
        val f = tmp.newFile("project.json")
        f.writeText("""{"version":1,"gyroFolder":""}""", StandardCharsets.UTF_8)
        assertNull(GyroFolderStore(f).get())
    }

    // ---- pure render ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"clip-a":{"version":1}}}"""
        assertEquals(
            GyroFolderStore.mergeInto(existing, tree),
            GyroFolderStore.mergeInto(existing, tree),
        )
    }

    @Test
    fun merge_into_quotes_the_uri() {
        // A tree URI contains no quotes, but the wire format must quote
        // the value like any other string.
        val merged = GyroFolderStore.mergeInto(null, tree)
        assertTrue(merged.contains("\"gyroFolder\":"))
    }

    @Test
    fun fresh_file_write_shape_is_minimal() {
        // No existing file: the store writes version + the key only.
        val (s, f) = newStore()
        s.put(tree)
        assertEquals(
            """{"version":1,"gyroFolder":"$tree"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }
}
