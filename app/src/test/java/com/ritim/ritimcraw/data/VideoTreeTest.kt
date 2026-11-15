package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of [VideoTree]: the supported-extension filter, the kind
 * routing, and the already-imported classification the browser dims on.
 * The SAF query itself is device-gated (exercised by the owner on device
 * — grant the folder, watch the grid), the [GyroTreeTest] precedent.
 */
class VideoTreeTest {

    // ---- isSupportedVideoName ----

    @Test
    fun supported_extensions_pass_case_insensitively() {
        // The owner's real folder: MotionCam pairs spell .mcraw lowercase,
        // but SAF names arrive as the provider spelled them.
        listOf(
            "clip.mcraw", "clip.MCRAW", "clip.McRaw",
            "frame.dng", "frame.DNG",
            "log.milog", "log.MILOG",
            "video.mp4", "video.MP4",
            "video.mov", "video.MOV",
        ).forEach { name ->
            assertTrue("$name should pass", VideoTree.isSupportedVideoName(name))
        }
    }

    @Test
    fun non_video_names_are_rejected() {
        // The folder's real junk: gyro logs, sidecars, no extension, a
        // trailing dot. A file literally named ".mcraw" PASSES, the same
        // way GyroTree's endsWith(".gcsv") accepts ".gcsv" — the dot is
        // not special to the rule.
        listOf(
            "clip.gcsv", "clip.txt", "clip.jpg", "clip.MCRAW.bak",
            "clip", "clip.", "clip.mcrawx", "clip.mcraw ",
        ).forEach { name ->
            assertFalse("'$name' should be rejected", VideoTree.isSupportedVideoName(name))
        }
    }

    // ---- kindFor ----

    @Test
    fun encoded_containers_route_to_the_encoded_kind() {
        // The honest kind up front, instead of the video/mp4 mime rescue
        // at open time (SourcePathDecision).
        assertEquals(SourceKind.ENCODED, VideoTree.kindFor("video.mp4"))
        assertEquals(SourceKind.ENCODED, VideoTree.kindFor("video.MOV"))
    }

    @Test
    fun raw_and_future_formats_route_to_mcraw() {
        // The single-file picker's default kind — .dng/.milog importers
        // land sprint 2+; the files take the same MCRAW path today.
        assertEquals(SourceKind.MCRAW, VideoTree.kindFor("clip.mcraw"))
        assertEquals(SourceKind.MCRAW, VideoTree.kindFor("frame.dng"))
        assertEquals(SourceKind.MCRAW, VideoTree.kindFor("log.milog"))
        assertEquals(SourceKind.MCRAW, VideoTree.kindFor("no-extension"))
    }

    // ---- documentIdOf ----

    @Test
    fun tree_built_document_uri_yields_its_document_id() {
        // The URI shape DocumentsContract.buildDocumentUriUsingTree spells:
        // .../tree/<encoded tree id>/document/<encoded document id>.
        assertEquals(
            "primary:Documents/MotionCam/clip.mcraw",
            VideoTree.documentIdOf(
                "content://com.android.externalstorage.documents/tree/" +
                    "primary%3ADocuments%2FMotionCam/document/" +
                    "primary%3ADocuments%2FMotionCam%2Fclip.mcraw",
            ),
        )
    }

    @Test
    fun a_plain_open_document_pick_yields_the_same_id() {
        // The same file picked earlier through ACTION_OPEN_DOCUMENT: the
        // document URI carries no tree segment, but its LAST segment is
        // the same document id — the other side of the comparison
        // [VideoTree.isImported] makes.
        assertEquals(
            "primary:Documents/MotionCam/clip.mcraw",
            VideoTree.documentIdOf(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3ADocuments%2FMotionCam%2Fclip.mcraw",
            ),
        )
        // A different authority's document ids parse through too — the
        // comparison never assumes the externalstorage provider.
        assertEquals("35", VideoTree.documentIdOf(
            "content://com.android.providers.downloads.documents/document/35",
        ))
    }

    @Test
    fun percent_decoding_handles_the_real_escapes() {
        // Colons and slashes arrive %3A / %2F; a raw (unencoded) segment
        // passes through unchanged — both sides of a comparison go through
        // the same decoder, so equal ids stay equal.
        assertEquals("primary:Documents", VideoTree.documentIdOf(
            "content://x/tree/t/document/primary%3ADocuments",
        ))
        assertEquals("primary:Documents", VideoTree.documentIdOf(
            "content://x/tree/t/document/primary:Documents",
        ))
    }

    @Test
    fun unparseable_uris_yield_null() {
        // The browser never crashes on a URI shape it did not build.
        assertNull(VideoTree.documentIdOf("content://authority"))
        assertNull(VideoTree.documentIdOf("content://authority/"))
        assertNull(VideoTree.documentIdOf(""))
    }

    // ---- isImported ----

    private fun source(uri: String) = MediaSource(
        id = "a",
        uri = uri,
        fingerprint = "fp-a",
        kind = SourceKind.MCRAW,
        access = Access.OK,
    )

    @Test
    fun the_exact_same_uri_marks_imported() {
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocuments%2FMotionCam/document/" +
            "primary%3ADocuments%2FMotionCam%2Fclip.mcraw"
        assertTrue(VideoTree.isImported(uri, listOf(source(uri))))
    }

    @Test
    fun the_same_document_id_through_a_different_uri_shape_marks_imported() {
        // The real dedupe case: the file was imported earlier through a
        // plain ACTION_OPEN_DOCUMENT pick (no tree segment), and now the
        // folder browser enumerates it through the granted tree — the uri
        // strings differ, the document ids match.
        val picked = "content://com.android.externalstorage.documents/document/" +
            "primary%3ADocuments%2FMotionCam%2Fclip.mcraw"
        val viaTree = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocuments%2FMotionCam/document/" +
            "primary%3ADocuments%2FMotionCam%2Fclip.mcraw"
        assertTrue(VideoTree.isImported(viaTree, listOf(source(picked))))
    }

    @Test
    fun different_files_stay_not_imported() {
        val sources = listOf(
            source(
                "content://com.android.externalstorage.documents/tree/" +
                    "primary%3ADocuments%2FMotionCam/document/" +
                    "primary%3ADocuments%2FMotionCam%2Fclip.mcraw",
            ),
        )
        val other = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocuments%2FMotionCam/document/" +
            "primary%3ADocuments%2FMotionCam%2Fother.mp4"
        assertFalse(VideoTree.isImported(other, sources))
    }

    @Test
    fun an_empty_library_marks_nothing_imported() {
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocuments%2FMotionCam/document/" +
            "primary%3ADocuments%2FMotionCam%2Fclip.mcraw"
        assertFalse(VideoTree.isImported(uri, emptyList()))
    }
}
