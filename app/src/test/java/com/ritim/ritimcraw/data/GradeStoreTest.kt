package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GradeStore tests (sprint plan Task 4):
 *  - grades_do_not_leak_between_clips: a grade set on clip A never appears on
 *    clip B, including after persist + reload (QA-05).
 */
class GradeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun grades_do_not_leak_between_clips() {
        val file = projectFile()
        val store = GradeStore(file)

        val clipA = "clip-a"
        val clipB = "clip-b"
        store.put(clipA, GradeK(exposureEv = 1f))
        store.put(clipB, GradeK(tempK = 3200f))

        // Reload from disk: a NEW store over the same file (simulating app
        // close + reopen), not the in-memory instance.
        val reloaded = GradeStore(file)
        assertEquals(1f, reloaded.get(clipA).exposureEv, 0f)
        assertEquals(5600f, reloaded.get(clipA).tempK, 0f) // A keeps default temp
        assertEquals(0f, reloaded.get(clipB).exposureEv, 0f) // B keeps default EV
        assertEquals(3200f, reloaded.get(clipB).tempK, 0f)
    }

    @Test
    fun put_persists_every_write() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(exposureEv = 0.5f))
        // Written immediately (no flush/close step).
        assertTrue(file.exists())
        assertEquals(0.5f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    @Test
    fun write_survives_sources_section_in_same_file() {
        // GradeStore and SourceRepository share project.json; a grade write
        // must not clobber the sources section (and vice versa).
        val file = projectFile()
        val sources = SourceRepository(FakeResolver(), file)
        val imported = sources.import("content://clip/1")

        val grades = GradeStore(file)
        grades.put(imported.id, GradeK(exposureEv = 2f))

        assertEquals(1, SourceRepository(FakeResolver(), file).all().size)
        assertEquals(2f, GradeStore(file).get(imported.id).exposureEv, 0f)
    }

    @Test
    fun remove_deletes_grade() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(highlights = -20f))
        store.remove("c1")
        assertEquals(0f, GradeStore(file).get("c1").highlights, 0f)
    }

    @Test
    fun corrupt_store_falls_back_to_defaults() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        assertEquals(0f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
