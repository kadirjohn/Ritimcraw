package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ThumbnailStore + ThumbnailRules tests (owner request 2026-11-11 section
 * 1): the persisted-PNG-per-id store (persist/load/missing) and the pure
 * backfill decisions (which records still need generation, the bounded
 * batch).
 */
class ThumbnailStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- store: persist/load/missing ----

    @Test
    fun missing_thumbnail_yields_null_and_not_has() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        assertNull(store.fileFor("abc123"))
        assertFalse(store.has("abc123"))
    }

    @Test
    fun written_thumbnail_is_found_by_id() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        val path = store.targetPath("abc123")
        File(path).writeText("png-bytes")
        assertTrue(store.has("abc123"))
        assertEquals(path, store.fileFor("abc123")?.absolutePath)
    }

    @Test
    fun ids_never_collide_in_the_dir() {
        // The id is the file name: two different clips map to two
        // different files (the fingerprint-derived id is unique).
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        File(store.targetPath("aa")).writeText("a")
        File(store.targetPath("bb")).writeText("b")
        assertTrue(store.has("aa"))
        assertTrue(store.has("bb"))
        assertFalse(store.has("cc"))
    }

    // ---- rules: needsThumbnail ----

    @Test
    fun needs_thumbnail_when_no_file_and_access_ok() {
        val s = MediaSource("id", "uri", "fp", SourceKind.MCRAW, Access.OK)
        assertTrue(ThumbnailRules.needsThumbnail(s, hasFile = false))
    }

    @Test
    fun never_redevelops_when_the_file_exists() {
        // Decode ONCE and persist: an existing PNG skips the pass.
        val s = MediaSource("id", "uri", "fp", SourceKind.MCRAW, Access.OK)
        assertFalse(ThumbnailRules.needsThumbnail(s, hasFile = true))
    }

    @Test
    fun lost_access_cannot_be_probed() {
        // A LOST row cannot open its clip: no generation until the grant
        // returns (the next Library open retries).
        val s = MediaSource("id", "uri", "fp", SourceKind.MCRAW, Access.LOST)
        assertFalse(ThumbnailRules.needsThumbnail(s, hasFile = false))
    }

    // ---- rules: batch (bounded backfill) ----

    @Test
    fun batch_takes_the_first_n_in_list_order() {
        val pending = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c"), ThumbnailRules.batch(pending, limit = 3))
    }

    @Test
    fun batch_passes_everything_when_under_the_limit() {
        val pending = listOf("a", "b")
        assertEquals(listOf("a", "b"), ThumbnailRules.batch(pending, limit = 3))
    }

    @Test
    fun batch_is_empty_for_a_non_positive_limit() {
        assertTrue(ThumbnailRules.batch(listOf("a", "b"), limit = 0).isEmpty())
    }
}
