package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * A [UriResolver] keyed by uri (the shared [FakeResolver] returns ONE blob
 * for every uri, which cannot exercise fingerprint dedupe): each uri maps to
 * its own bytes, so two different .gcsv logs import as two entries and the
 * same log re-imported under another uri dedupes to one. Named distinctly
 * from LutStoreTest's file-private twin (a same-name top-level class in one
 * package redeclares even when both are file-private).
 */
private class KeyedGyroResolver(
    private val blobs: Map<String, ByteArray>,
) : UriResolver {
    override fun open(uri: String): InputStream =
        ByteArrayInputStream(blobs[uri] ?: ByteArray(0))

    override fun size(uri: String): Long = (blobs[uri] ?: ByteArray(0)).size.toLong()

    // Gyro imports get their name from the picker-side fallback, so the
    // resolver never needs to resolve a document name here.
    override fun displayName(uri: String): String? = null

    // The collection refresh never probes gyro blobs: any answer works.
    override fun probe(uri: String): DocumentProbe = DocumentProbe.AVAILABLE
}

/**
 * GyroStore tests (GYRO PHASE 5, device-independent half):
 *  - import persists the entry AND copies the .gcsv into the app-private dir.
 *  - fingerprint dedupe: the same bytes under another uri = the same id, one
 *    entry, one file.
 *  - pathFor: null for no attachment, null for an unknown id, a real path for
 *    an imported log.
 *  - merge-aware writes: the "gyros" section survives a grades write and vice
 *    versa.
 *  - remove drops the entry and deletes the copied file.
 */
class GyroStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Parenthesized: without them "a" + "b".toByteArray() would be
    // String.plus(ByteArray) — a String, not the bytes the fixture needs.
    private val gcsvA = (
        "GYROFLOW IMU LOG\ntimestamp,1690000000000\nns,gyro-s,accel-s\n" +
            "0,0.001,0,0.001,0,0,9.81,0\n10000000,0.001,0,0.001,0,0,9.81,0\n"
        ).toByteArray()
    private val gcsvB = (
        "GYROFLOW IMU LOG\ntimestamp,1690001000000\nns,gyro-s,accel-s\n" +
            "0,0.002,0,0.002,0,0,9.81,0\n10000000,0.002,0,0.002,0,0,9.81,0\n"
        ).toByteArray()

    private fun gyrosDir(): File = File(tmp.root, "gyros")

    private fun store(resolver: UriResolver): GyroStore =
        GyroStore(resolver, projectFile(), gyrosDir())

    private fun projectFile(): File = File(tmp.root, "project.json")

    @Test
    fun import_persists_entry_and_copies_gcsv() {
        val s = store(KeyedGyroResolver(mapOf("content://gyros/a" to gcsvA)))

        val imported = s.import("content://gyros/a", "004150 sidecar")

        // The entry is persisted...
        val all = GyroStore(KeyedGyroResolver(mapOf()), projectFile(), gyrosDir()).all()
        assertEquals(listOf(imported), all)
        assertEquals("004150 sidecar", all.single().name)
        // ...and the .gcsv bytes were copied app-private as <id>.gcsv.
        val copy = File(File(tmp.root, "gyros"), "${imported.id}.gcsv")
        assertTrue(copy.exists())
        assertEquals(gcsvA.size.toLong(), copy.length())
        // The stored name is the fallback when it is not blank.
        assertEquals("004150 sidecar", imported.name)
    }

    @Test
    fun blank_fallback_name_falls_back_to_id() {
        val s = store(KeyedGyroResolver(mapOf("content://gyros/a" to gcsvA)))

        val imported = s.import("content://gyros/a", "")

        assertEquals(imported.id, imported.name)
    }

    @Test
    fun same_bytes_under_another_uri_dedupes_to_one_entry() {
        // Fingerprint dedupe (the SourceRepository convention): the id comes
        // from the CONTENT, so the same log picked from another location is
        // the same gyro — one entry, one file, a stable id for attachments.
        val s = store(
            KeyedGyroResolver(
                mapOf(
                    "content://gyros/a" to gcsvA,
                    "content://other/a-copy" to gcsvA,
                )
            )
        )

        val first = s.import("content://gyros/a", "First")
        val second = s.import("content://other/a-copy", "Second")

        assertEquals(first.id, second.id)
        assertEquals(1, s.all().size)
        // The dedupe hit keeps the FIRST entry's name.
        assertEquals("First", s.all().single().name)
        assertEquals(1, gyrosDir().listFiles()!!.size)
    }

    @Test
    fun different_bytes_import_as_separate_entries() {
        val s = store(
            KeyedGyroResolver(
                mapOf(
                    "content://gyros/a" to gcsvA,
                    "content://gyros/b" to gcsvB,
                )
            )
        )

        val a = s.import("content://gyros/a", "A")
        val b = s.import("content://gyros/b", "B")

        assertTrue(a.id != b.id)
        assertEquals(listOf("A", "B"), s.all().map { it.name })
    }

    @Test
    fun path_for_resolves_null_unknown_and_imported() {
        val s = store(KeyedGyroResolver(mapOf("content://gyros/a" to gcsvA)))

        // No attachment: null (stabilization stays a no-op).
        assertNull(s.pathFor(null))
        // Unknown id (a source whose log was removed): null, so the editor
        // degrades to no-gyro instead of failing.
        assertNull(s.pathFor("0000000000000000"))

        val imported = s.import("content://gyros/a", "A")
        val path = s.pathFor(imported.id)
        assertNotNull(path)
        assertTrue(File(path!!).exists())
        assertEquals(imported.id, File(path).nameWithoutExtension)
    }

    @Test
    fun gyros_section_survives_a_grades_write() {
        // GyroStore and GradeStore share project.json: a grade write (the
        // per-clip stabilization fields) must not clobber the gyro library,
        // and a gyros write must not clobber the grades.
        val s = store(KeyedGyroResolver(mapOf("content://gyros/a" to gcsvA)))
        val imported = s.import("content://gyros/a", "A")

        val grades = GradeStore(projectFile())
        grades.put("clip-1", GradeK(stabilization = true, stabilizationStrength = 70f))

        // The library survived the grade write...
        assertEquals(listOf(imported), GyroStore(KeyedGyroResolver(mapOf()), projectFile(), gyrosDir()).all())
        // ...and the grade survived the gyros write.
        val g = GradeStore(projectFile()).get("clip-1")
        assertEquals(true, g.stabilization)
        assertEquals(70f, g.stabilizationStrength, 0f)
    }

    @Test
    fun remove_drops_entry_and_deletes_gcsv_file() {
        val s = store(KeyedGyroResolver(mapOf("content://gyros/a" to gcsvA)))
        val imported = s.import("content://gyros/a", "A")
        val copy = File(File(tmp.root, "gyros"), "${imported.id}.gcsv")
        assertTrue(copy.exists())

        s.remove(imported.id)

        assertTrue(s.all().isEmpty())
        assertTrue(!copy.exists())
        // pathFor now degrades to no-gyro for sources still holding the id.
        assertNull(s.pathFor(imported.id))
        assertTrue(GyroStore(KeyedGyroResolver(mapOf()), projectFile(), gyrosDir()).all().isEmpty())
    }
}
