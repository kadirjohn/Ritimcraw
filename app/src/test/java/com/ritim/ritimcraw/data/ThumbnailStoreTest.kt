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

    // ---- store: remove (owner finding 2026-11-11 E2) ----

    @Test
    fun remove_deletes_the_persisted_png() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        File(store.targetPath("gone")).writeText("png")
        assertTrue(store.has("gone"))

        store.remove("gone")

        assertFalse(store.has("gone"))
        assertNull(store.fileFor("gone"))
    }

    @Test
    fun remove_is_a_noop_when_no_file_exists() {
        // Deleting a clip that never got its thumbnail must not throw —
        // and must not create anything either.
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.remove("never-existed")
        assertFalse(store.has("never-existed"))
    }

    @Test
    fun remove_never_touches_other_ids_files() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        File(store.targetPath("aa")).writeText("a")
        File(store.targetPath("bb")).writeText("b")

        store.remove("aa")

        assertFalse(store.has("aa"))
        assertTrue(store.has("bb"))
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

    @Test
    fun gone_row_cannot_be_probed() {
        // A GONE row (spec IN-12: the document was deleted/moved) cannot
        // open its clip either: no generation until the re-link lands.
        val s = MediaSource("id", "uri", "fp", SourceKind.MCRAW, Access.GONE)
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

    // ---- folder namespace (owner request 2026-11-15) ----
    //
    // The pre-import previews of NOT-yet-imported folder files: keyed on
    // the DOCUMENT URI (no MediaSource record exists yet), in a SEPARATE
    // subfolder so the id-keyed namespace and this one structurally cannot
    // touch each other.

    @Test
    fun folder_key_is_stable_for_the_same_uri() {
        // Two grants of the same tree spell the same document uri
        // identically: the SAME key both times (decode ONCE, persist).
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        assertEquals(store.folderKeyFor("content://media/external/video/42"), store.folderKeyFor("content://media/external/video/42"))
    }

    @Test
    fun folder_key_is_distinct_per_uri() {
        // Two different documents never collide (the SHA-256 cut).
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        assertFalse(
            store.folderKeyFor("content://x/1").equals(
                store.folderKeyFor("content://x/2"),
            ),
        )
    }

    @Test
    fun folder_key_lives_outside_the_id_space() {
        // The prefix keeps the name out of the hex id space entirely: an
        // id-keyed read can never resolve a folder file and vice versa.
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        val key = store.folderKeyFor("content://x/1")
        assertTrue(key.startsWith("doc-"))
        assertTrue(key.endsWith(".png"))
        // The hashed body is exactly the id width (16 hex chars).
        assertEquals(16, key.length - "doc-".length - ".png".length)
    }

    @Test
    fun written_folder_preview_is_found_by_uri() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureFolderDir()
        val path = store.folderTargetPath("content://x/1")
        File(path).writeText("png-bytes")
        assertTrue(store.folderHas("content://x/1"))
        assertEquals(path, store.folderFileFor("content://x/1")?.absolutePath)
        // The folder dir is a SUBDIR of the store dir: one store, two
        // namespaces.
        assertEquals(store.folderDir, File(path).parentFile)
    }

    @Test
    fun missing_folder_preview_is_null_and_not_has() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        assertNull(store.folderFileFor("content://x/none"))
        assertFalse(store.folderHas("content://x/none"))
    }

    @Test
    fun remove_folder_deletes_only_that_preview() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureFolderDir()
        File(store.folderTargetPath("content://x/1")).writeText("a")
        File(store.folderTargetPath("content://x/2")).writeText("b")

        store.removeFolder("content://x/1")

        assertFalse(store.folderHas("content://x/1"))
        assertTrue(store.folderHas("content://x/2"))
    }

    @Test
    fun remove_folder_is_a_noop_when_none_exists() {
        // Importing a file whose preview never generated must not throw.
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.removeFolder("content://x/never-generated")
        assertFalse(store.folderHas("content://x/never-generated"))
    }

    @Test
    fun library_remove_never_touches_folder_previews() {
        // THE namespace separation, pinned: deleting a LIBRARY clip
        // (ThumbnailStore.remove) must never delete a folder preview —
        // the pre-import preview of a not-yet-imported file survives a
        // library delete of a DIFFERENT clip (and even the same file's
        // future re-import finds it cached).
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        store.ensureFolderDir()
        File(store.targetPath("abc123")).writeText("icon")
        File(store.folderTargetPath("content://x/42")).writeText("preview")

        store.remove("abc123")

        assertFalse(store.has("abc123"))
        assertTrue(store.folderHas("content://x/42"))
    }

    @Test
    fun remove_folder_never_touches_id_files() {
        // The reverse direction: the import cleanup deletes ONLY the
        // document-keyed file — the imported clip's own id-keyed icon
        // (generated by the per-import pipeline) is untouched.
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureDir()
        store.ensureFolderDir()
        File(store.targetPath("abc123")).writeText("icon")
        File(store.folderTargetPath("content://x/42")).writeText("preview")

        store.removeFolder("content://x/42")

        assertTrue(store.has("abc123"))
        assertFalse(store.folderHas("content://x/42"))
    }

    @Test
    fun trim_folder_is_a_noop_at_or_under_the_cap() {
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureFolderDir()
        File(store.folderTargetPath("content://x/1")).writeText("a")
        File(store.folderTargetPath("content://x/2")).writeText("b")

        assertEquals(0, store.trimFolder(keep = 2))
        assertEquals(0, store.trimFolder(keep = 5))
        assertTrue(store.folderHas("content://x/1"))
        assertTrue(store.folderHas("content://x/2"))
    }

    @Test
    fun trim_folder_deletes_oldest_first_past_the_cap() {
        // Un-imported browsing accumulates: the sweep deletes the
        // OLDEST previews past the cap, keeping the newest (the ones the
        // owner most recently looked at).
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        store.ensureFolderDir()
        val old = File(store.folderTargetPath("content://x/old"))
        old.writeText("old")
        val fresh = File(store.folderTargetPath("content://x/fresh"))
        fresh.writeText("fresh")
        // lastModified has second precision on some filesystems; force
        // the ORDER the sweep must respect.
        old.setLastModified(1_000_000L)
        fresh.setLastModified(2_000_000L)

        val deleted = store.trimFolder(keep = 1)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertEquals(1, store.folderDir.listFiles()!!.size)
    }

    @Test
    fun trim_folder_is_a_noop_when_the_dir_is_missing() {
        // A store that never generated a folder preview: the sweep must
        // not throw and must not create the dir.
        val store = ThumbnailStore(File(tmp.root, "thumbs"))
        assertEquals(0, store.trimFolder(keep = 300))
        assertFalse(store.folderDir.exists())
    }
}
