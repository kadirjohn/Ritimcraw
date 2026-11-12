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
 * its own bytes, so two different .cube files import as two entries and the
 * same file re-imported under another uri dedupes to one.
 */
private class UriKeyedResolver(
    private val blobs: Map<String, ByteArray>,
) : UriResolver {
    override fun open(uri: String): InputStream =
        ByteArrayInputStream(blobs[uri] ?: ByteArray(0))

    override fun size(uri: String): Long = (blobs[uri] ?: ByteArray(0)).size.toLong()

    // LUT imports get their title from the picker-side fallback name, so
    // the resolver never needs to resolve a document name here.
    override fun displayName(uri: String): String? = null

    // The collection refresh never probes cube files: any answer works.
    override fun probe(uri: String): DocumentProbe = DocumentProbe.AVAILABLE
}

/**
 * LutStore tests (COL-07, device-independent half):
 *  - import persists the entry AND copies the cube into the app-private dir.
 *  - fingerprint dedupe: the same bytes under another uri = the same id, one
 *    entry, one file.
 *  - pathFor: null for no selection, null for an unknown id, a real path for
 *    an imported cube.
 *  - merge-aware writes: the "luts" section survives a grades write and vice
 *    versa.
 *  - setTitle / remove round-trip through project.json.
 */
class LutStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cubeA = "TITLE cube A\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n".toByteArray()
    private val cubeB = "TITLE cube B\nLUT_3D_SIZE 2\n0 0 0\n0.5 0 0\n0 0.5 0\n0.5 0.5 0\n0 0 0.5\n0.5 0 0.5\n0 0.5 0.5\n0.5 0.5 0.5\n".toByteArray()

    private fun lutsDir(): File = File(tmp.root, "luts")

    private fun store(resolver: UriResolver): LutStore =
        LutStore(resolver, projectFile(), lutsDir())

    private fun projectFile(): File = File(tmp.root, "project.json")

    @Test
    fun import_persists_entry_and_copies_cube() {
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))

        val imported = s.import("content://luts/a", "My Teal Look")

        // The entry is persisted...
        val all = LutStore(UriKeyedResolver(mapOf()), projectFile(), lutsDir()).all()
        assertEquals(listOf(imported), all)
        assertEquals("My Teal Look", all.single().name)
        // ...and the cube bytes were copied app-private as <id>.cube.
        val copy = File(File(tmp.root, "luts"), "${imported.id}.cube")
        assertTrue(copy.exists())
        assertEquals(cubeA.size.toLong(), copy.length())
        // The stored name is the fallback when it is not blank.
        assertEquals("My Teal Look", imported.name)
    }

    @Test
    fun blank_fallback_name_falls_back_to_id() {
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))

        val imported = s.import("content://luts/a", "")

        assertEquals(imported.id, imported.name)
    }

    @Test
    fun same_bytes_under_another_uri_dedupes_to_one_entry() {
        // Fingerprint dedupe (the SourceRepository convention): the id comes
        // from the CONTENT, so the same cube picked from another location is
        // the same LUT — one entry, one file, a stable id for saved grades.
        val s = store(
            UriKeyedResolver(
                mapOf(
                    "content://luts/a" to cubeA,
                    "content://other/a-copy" to cubeA,
                )
            )
        )

        val first = s.import("content://luts/a", "First")
        val second = s.import("content://other/a-copy", "Second")

        assertEquals(first.id, second.id)
        assertEquals(1, s.all().size)
        // The dedupe hit keeps the FIRST entry's name.
        assertEquals("First", s.all().single().name)
        assertEquals(1, lutsDir().listFiles()!!.size)
    }

    @Test
    fun different_bytes_import_as_separate_entries() {
        val s = store(
            UriKeyedResolver(
                mapOf(
                    "content://luts/a" to cubeA,
                    "content://luts/b" to cubeB,
                )
            )
        )

        val a = s.import("content://luts/a", "A")
        val b = s.import("content://luts/b", "B")

        assertTrue(a.id != b.id)
        assertEquals(listOf("A", "B"), s.all().map { it.name })
    }

    @Test
    fun path_for_resolves_null_unknown_and_imported() {
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))

        // No selection: null (stage 20 bypassed — the old look).
        assertNull(s.pathFor(null))
        // Unknown id (a grade whose cube was removed): null, so the render
        // degrades to no-LUT instead of failing.
        assertNull(s.pathFor("0000000000000000"))

        val imported = s.import("content://luts/a", "A")
        val path = s.pathFor(imported.id)
        assertNotNull(path)
        assertTrue(File(path!!).exists())
        assertEquals(imported.id, File(path).nameWithoutExtension)
    }

    @Test
    fun luts_section_survives_a_grades_write() {
        // LutStore and GradeStore share project.json: a grade write (the
        // per-clip selection + Strength) must not clobber the cube library,
        // and a luts write must not clobber the grades.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val imported = s.import("content://luts/a", "A")

        val grades = GradeStore(projectFile())
        grades.put("clip-1", GradeK(exposureEv = 1f, lutId = imported.id, lutStrength = 40f))

        // The library survived the grade write...
        assertEquals(listOf(imported), LutStore(UriKeyedResolver(mapOf()), projectFile(), lutsDir()).all())
        // ...and the grade survived the luts write.
        val g = GradeStore(projectFile()).get("clip-1")
        assertEquals(imported.id, g.lutId)
        assertEquals(40f, g.lutStrength, 0f)
    }

    @Test
    fun set_title_persists_and_skips_no_op_writes() {
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val imported = s.import("content://luts/a", "fallback")

        // The native probe's TITLE rename.
        s.setTitle(imported.id, "Parsed Title")
        assertEquals("Parsed Title", s.get(imported.id)?.name)
        assertEquals("Parsed Title", LutStore(UriKeyedResolver(mapOf()), projectFile(), lutsDir()).all().single().name)

        // Setting the SAME name must not rewrite (the no-op guard).
        val before = projectFile().readText(Charsets.UTF_8)
        s.setTitle(imported.id, "Parsed Title")
        assertEquals(before, projectFile().readText(Charsets.UTF_8))
    }

    @Test
    fun remove_drops_entry_and_deletes_cube_file() {
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val imported = s.import("content://luts/a", "A")
        val copy = File(File(tmp.root, "luts"), "${imported.id}.cube")
        assertTrue(copy.exists())

        s.remove(imported.id)

        assertTrue(s.all().isEmpty())
        assertTrue(!copy.exists())
        // pathFor now degrades to no-LUT for grades still holding the id.
        assertNull(s.pathFor(imported.id))
        assertTrue(LutStore(UriKeyedResolver(mapOf()), projectFile(), lutsDir()).all().isEmpty())
    }
}
