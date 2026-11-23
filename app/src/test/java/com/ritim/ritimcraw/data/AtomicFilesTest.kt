package com.ritim.ritimcraw.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesNewFileAndLeavesNoTempBehind() {
        val f = File(tmp.root, "project.json")
        AtomicFiles.writeText(f, "{\"version\":1}")
        assertEquals("{\"version\":1}", f.readText())
        assertFalse(File(tmp.root, "project.json.tmp").exists())
    }

    @Test
    fun replacesExistingFileWholly() {
        val f = tmp.newFile("project.json")
        f.writeText("old old old old old old")
        AtomicFiles.writeText(f, "new")
        assertEquals("new", f.readText())
        assertFalse(File(tmp.root, "project.json.tmp").exists())
    }

    @Test
    fun createsMissingParentDirectories() {
        val f = File(tmp.root, "a/b/project.json")
        AtomicFiles.writeText(f, "x")
        assertEquals("x", f.readText())
    }

    @Test
    fun failedPublishKeepsTheTargetAndCleansTheTemp() {
        // Target is a NON-EMPTY DIRECTORY: the atomic rename cannot replace
        // it, so the publish fails. No torn write, no non-atomic fallback.
        val target = tmp.newFolder("project.json")
        File(target, "keep").writeText("untouched")
        try {
            AtomicFiles.writeText(target, "new")
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(target.isDirectory)
        assertEquals("untouched", File(target, "keep").readText())
        assertFalse(File(tmp.root, "project.json.tmp").exists())
    }
}
