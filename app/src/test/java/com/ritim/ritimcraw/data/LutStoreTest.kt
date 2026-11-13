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

        // No selection: null (stage 24 bypassed — the old look).
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

    // ---- managed interpretations (owner request 2026-11-13) ----

    /** A display-look interpretation: rendered linear Rec.709 in/out. */
    private val displayLook = LutInterpK(
        input = LutEncodingK(LutGamut.Rec709, LutTransfer.Linear),
        output = LutEncodingK(LutGamut.Rec709, LutTransfer.Linear),
        role = LutRole.DisplayLook,
    )

    /** A scene-to-display conversion: DWG/Intermediate -> Rec.709 OETF. */
    private val sceneToDisplay = LutInterpK(
        input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
        output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
        role = LutRole.SceneToDisplay,
    )

    @Test
    fun same_blob_two_interpretations_coexist_as_two_records() {
        // The immutable-binding rule: importing the same bytes with a
        // DIFFERENT interpretation never removes or mutates the first —
        // two records, one shared cube file.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))

        val first = s.import("content://luts/a", "A", displayLook)
        val second = s.import("content://luts/a", "A", sceneToDisplay)

        assertEquals(first.id, second.id)
        assertEquals(2, s.all().size)
        assertEquals(setOf(displayLook, sceneToDisplay), s.all().map { it.interp }.toSet())
        // ONE file for the blob, shared by both records.
        assertEquals(1, lutsDir().listFiles()!!.size)
        // Re-importing the SAME interpretation dedupes (no third record).
        val again = s.import("content://luts/a", "A", sceneToDisplay)
        assertEquals(second, again)
        assertEquals(2, s.all().size)
    }

    @Test
    fun legacy_import_binds_the_legacy_interpretation() {
        // The default keeps pre-managed call sites byte-identical: an
        // import without an interpretation is a LegacyLinear709 record.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))

        val imported = s.import("content://luts/a", "A")

        assertEquals(LutInterpK.legacy(), imported.interp)
        assertEquals(LutRole.LegacyLinear709, imported.interp.role)
    }

    @Test
    fun legacy_record_renders_without_the_interp_keys() {
        // Byte-identity: a legacy record writes NONE of the interp keys,
        // so a pre-managed library file stays byte-identical.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        s.import("content://luts/a", "A")

        val rendered = projectFile().readText(Charsets.UTF_8)
        assertTrue(!rendered.contains("interp"))
        assertTrue(!rendered.contains("\"role\""))
    }

    @Test
    fun bind_interp_creates_a_new_revision_never_mutates() {
        // The "edit interpretation" path: binding a new interpretation to
        // an ALREADY-IMPORTED blob creates a NEW record — the old one is
        // untouched (grades holding it keep rendering exactly as before).
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val original = s.import("content://luts/a", "My Look", displayLook)

        val revision = s.bindInterp(original.id, sceneToDisplay)

        assertEquals(original.id, revision.id)
        assertEquals(sceneToDisplay, revision.interp)
        // The new record inherits the blob's existing display name.
        assertEquals("My Look", revision.name)
        // BOTH records exist; the original is unchanged.
        assertEquals(2, s.all().size)
        assertEquals(displayLook, s.all().first { it.interp == displayLook }.interp)
        // Dedupe: binding an interpretation that already exists on the
        // blob returns that record unchanged (no third record).
        val sameAgain = s.bindInterp(original.id, sceneToDisplay)
        assertEquals(revision, sameAgain)
        assertEquals(2, s.all().size)
        // No re-import: the shared file is still there, still one copy.
        assertEquals(1, lutsDir().listFiles()!!.size)
    }

    @Test
    fun pre_managed_records_migrate_to_legacy_interpretation() {
        // The migration rule: a "luts" section written before the managed
        // model is [{id, name}] only — every record parses as
        // LegacyLinear709 and renders back WITHOUT the interp keys.
        val id = "abcdef0123456789"
        projectFile().writeText(
            "{\"version\":1,\"luts\":[{\"id\":\"$id\",\"name\":\"Old Import\"}]}",
            Charsets.UTF_8,
        )

        val s = store(UriKeyedResolver(mapOf()))
        val all = s.all()

        assertEquals(1, all.size)
        assertEquals(id, all.single().id)
        assertEquals("Old Import", all.single().name)
        assertEquals(LutInterpK.legacy(), all.single().interp)
        // The migration is render-stable: rewriting the store keeps the
        // file byte-identical (omit-when-legacy).
        val before = projectFile().readText(Charsets.UTF_8)
        s.setTitle(id, "Old Import")
        assertEquals(before, projectFile().readText(Charsets.UTF_8))
    }

    @Test
    fun remove_binding_drops_one_record_and_keeps_the_shared_file() {
        // Two interpretations share one cube file: removing ONE keeps the
        // other usable (the file survives); removing the LAST one deletes
        // the file.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val blob = s.import("content://luts/a", "A", displayLook)
        s.bindInterp(blob.id, sceneToDisplay)
        val copy = File(lutsDir(), "${blob.id}.cube")
        assertTrue(copy.exists())

        s.removeBinding(blob.id, sceneToDisplay)

        assertEquals(listOf(displayLook), s.all().map { it.interp })
        assertTrue(copy.exists())

        s.removeBinding(blob.id, displayLook)

        assertTrue(s.all().isEmpty())
        assertTrue(!copy.exists())
    }

    @Test
    fun remove_blob_drops_every_binding_and_the_file() {
        // The legacy remove (the list screen's delete): EVERY record on
        // the blob goes, file too — pre-managed behavior preserved.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val blob = s.import("content://luts/a", "A", displayLook)
        s.bindInterp(blob.id, sceneToDisplay)

        s.remove(blob.id)

        assertTrue(s.all().isEmpty())
        assertTrue(!File(lutsDir(), "${blob.id}.cube").exists())
    }

    @Test
    fun set_title_renames_every_record_on_the_blob() {
        // The name belongs to the cube, not to one interpretation: the
        // probe's TITLE rename touches every record sharing the blob.
        val s = store(UriKeyedResolver(mapOf("content://luts/a" to cubeA)))
        val blob = s.import("content://luts/a", "fallback", displayLook)
        s.bindInterp(blob.id, sceneToDisplay)

        s.setTitle(blob.id, "Parsed Title")

        assertEquals(listOf("Parsed Title", "Parsed Title"), s.all().map { it.name })
    }
}
