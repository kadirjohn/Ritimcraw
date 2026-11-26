package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Fake folder: name -> uri, uri = "content://dir/<name>". Records every call. */
private class FakeRenamer(names: List<String>) : SourceFileRenamer {
    val files = names.toMutableList()
    val calls = mutableListOf<String>()
    var listable = true
    var failOn: Set<String> = emptySet() // target names whose rename fails

    fun uriOf(name: String) = "content://dir/$name"

    override fun siblings(uri: String): List<SiblingDoc>? =
        if (!listable) null else files.map { SiblingDoc(it, uriOf(it)) }

    override fun rename(uri: String, newName: String): String? {
        calls += "$uri->$newName"
        if (newName in failOn) return null
        val old = uri.removePrefix("content://dir/")
        val i = files.indexOf(old)
        if (i < 0 || files.any { it.equals(newName, true) && it != old }) return null
        files[i] = newName
        return uriOf(newName)
    }
}

class SourceFileRenameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val orig = "261125_194553_VIDEO_24mm.mcraw"
    private val gcsv = "261125_194553_VIDEO_24mm.gcsv"
    private val oldUri = "content://dir/$orig"

    private fun project() = File(tmp.root, "project.json")

    private fun repo(renamer: FakeRenamer, file: File = project()): SourceRepository {
        val resolver = object : UriResolver {
            override fun open(uri: String) = "clip".byteInputStream()
            override fun size(uri: String) = 4L
            override fun displayName(uri: String): String? =
                renamer.files.firstOrNull { renamer.uriOf(it) == uri }
            override fun probe(uri: String) = DocumentProbe.AVAILABLE
        }
        return SourceRepository(resolver, file)
    }

    @Test
    fun success_renames_main_and_sidecar_together() {
        val r = FakeRenamer(listOf(orig, gcsv, "other.mcraw"))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        val res = repo.renameWithFile(id, "Sunset", r)
        assertEquals(RenameStatus.FILE_RENAMED, res.status)
        assertEquals(listOf("Sunset.mcraw", "Sunset.gcsv", "other.mcraw"), r.files)
        assertEquals("content://dir/Sunset.mcraw", repo.get(id)?.uri)
        assertEquals("Sunset", repo.get(id)?.displayName)
        assertEquals(orig, repo.get(id)?.originalName)
    }

    @Test
    fun typed_extension_is_not_doubled() {
        val r = FakeRenamer(listOf(orig))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        repo.renameWithFile(id, "Sunset.MCRAW", r)
        assertEquals(listOf("Sunset.MCRAW"), r.files)
    }

    @Test
    fun clip_without_sidecar_renames_only_the_main_file() {
        val r = FakeRenamer(listOf(orig))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        assertEquals(RenameStatus.FILE_RENAMED, repo.renameWithFile(id, "A", r).status)
        assertEquals(1, r.calls.size)
    }

    @Test
    fun sidecar_failure_rolls_the_main_rename_back() {
        val r = FakeRenamer(listOf(orig, gcsv))
        r.failOn = setOf("Sunset.gcsv")
        val repo = repo(r)
        val id = repo.import(oldUri).id
        val res = repo.renameWithFile(id, "Sunset", r)
        assertEquals(RenameStatus.REFUSED_SIDECAR_FAILED, res.status)
        assertEquals(listOf(orig, gcsv), r.files)
        assertEquals(oldUri, repo.get(id)?.uri)
        assertEquals(orig, repo.get(id)?.displayName)
        assertEquals("content://dir/Sunset.mcraw->$orig", r.calls.last())
    }

    @Test
    fun failed_rollback_follows_the_moved_file_instead_of_orphaning_it() {
        val r = FakeRenamer(listOf(orig, gcsv))
        r.failOn = setOf("Sunset.gcsv", orig)
        val repo = repo(r)
        val id = repo.import(oldUri).id
        val res = repo.renameWithFile(id, "Sunset", r)
        assertEquals(RenameStatus.PARTIAL, res.status)
        assertEquals("content://dir/Sunset.mcraw", repo.get(id)?.uri)
    }

    @Test
    fun existing_target_refuses_and_touches_nothing() {
        val r = FakeRenamer(listOf(orig, gcsv, "sunset.MCRAW"))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        val res = repo.renameWithFile(id, "Sunset", r)
        assertEquals(RenameStatus.REFUSED_EXISTS, res.status)
        assertTrue(r.calls.isEmpty())
        assertEquals(orig, repo.get(id)?.displayName)
    }

    @Test
    fun existing_sidecar_target_refuses_before_any_rename() {
        val r = FakeRenamer(listOf(orig, gcsv, "Sunset.gcsv"))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        assertEquals(RenameStatus.REFUSED_EXISTS, repo.renameWithFile(id, "Sunset", r).status)
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun ambiguous_sidecar_refuses() {
        val r = FakeRenamer(listOf(orig, gcsv, gcsv.replace("gcsv", "GCSV")))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        assertEquals(
            RenameStatus.REFUSED_SIDECAR_AMBIGUOUS,
            repo.renameWithFile(id, "Sunset", r).status,
        )
        assertTrue(r.calls.isEmpty())
    }

    @Test
    fun invalid_names_refuse() {
        val r = FakeRenamer(listOf(orig))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        for (bad in listOf("a/b", "a:b", "  ", "x?", ".hidden", "trail.", "a\\b", "n".repeat(300))) {
            assertEquals(bad, RenameStatus.REFUSED_INVALID, repo.renameWithFile(id, bad, r).status)
        }
        assertTrue(r.calls.isEmpty())
        assertNull(SourceFileRename.targetFileName("a|b", orig))
    }

    @Test
    fun unlistable_folder_falls_back_to_app_only() {
        val r = FakeRenamer(listOf(orig, gcsv))
        r.listable = false
        val repo = repo(r)
        val id = repo.import(oldUri).id
        val res = repo.renameWithFile(id, "Sunset", r)
        assertEquals(RenameStatus.APP_ONLY, res.status)
        assertEquals("Sunset", repo.get(id)?.displayName)
        assertEquals(oldUri, repo.get(id)?.uri)
        assertEquals(orig, repo.get(id)?.originalName)
        assertEquals(listOf(orig, gcsv), r.files)
    }

    @Test
    fun original_name_survives_two_file_renames() {
        val r = FakeRenamer(listOf(orig, gcsv))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        repo.renameWithFile(id, "One", r)
        repo.renameWithFile(id, "Two", r)
        assertEquals(listOf("Two.mcraw", "Two.gcsv"), r.files)
        assertEquals(orig, repo.get(id)?.originalName)
    }

    @Test
    fun rename_keeps_the_grade_and_other_sections_and_the_id() {
        val r = FakeRenamer(listOf(orig, gcsv))
        val f = project()
        val repo = repo(r, f)
        val id = repo.import(oldUri).id
        // A grades section keyed by the source id (GradeStore's shape),
        // written next to "sources" in the same project.json.
        val withGrade = f.readText().replaceFirst(
            "{", "{\"grades\":{\"$id\":{\"exposure\":1.5}},",
        )
        f.writeText(withGrade)
        repo.renameWithFile(id, "Sunset", r)
        val text = f.readText()
        assertTrue(text, text.contains("\"grades\":{\"$id\":{\"exposure\":1.5}}"))
        assertEquals(id, repo.all().single().id)
        assertEquals("content://dir/Sunset.mcraw", repo.get(id)?.uri)
        // No temp leftovers: the single store file is the only artifact.
        assertEquals(listOf("project.json"), tmp.root.list()!!.sorted().filter { it.startsWith("project") })
    }

    @Test
    fun unchanged_name_does_nothing() {
        val r = FakeRenamer(listOf(orig))
        val repo = repo(r)
        val id = repo.import(oldUri).id
        assertEquals(RenameStatus.UNCHANGED, repo.renameWithFile(id, orig, r).status)
        assertTrue(r.calls.isEmpty())
    }
}
