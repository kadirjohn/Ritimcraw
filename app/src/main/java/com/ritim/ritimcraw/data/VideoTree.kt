package com.ritim.ritimcraw.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The SAF tree query for the folder import (owner request 2026-11-15):
 * the device-gated half that turns a granted folder tree URI into the
 * video files the import browser offers, plus the PURE rules the browser
 * rides — the supported-extension filter, the kind an import of each name
 * takes, and the already-imported classification that dims picked-before
 * files.
 *
 * DELIBERATELY RAW DocumentsContract, not androidx.documentfile (the
 * [GyroTree] precedent): the browser needs only ONE child query (names +
 * document ids), and DocumentFile would add a dependency for per-file
 * object churn this flow never uses.
 *
 * SINGLE-LEVEL ONLY (the [GyroTree] contract): the query lists the
 * granted folder's DIRECT children. MotionCam writes flat sibling pairs
 * (`name.mcraw` + `name.gcsv`), so recursion would only slow the query.
 * Videos inside subfolders are not offered — pick the folder that holds
 * them.
 *
 * The supported set is the app's REAL import surface, read from the
 * import path: .mcraw is the native format (the default kind); .dng and
 * .milog are the named-but-future formats the Library chips already
 * reserve (their importers are sprint 2+ — the files land through the
 * same MCRAW path today, and an unsupported one fails honestly at open);
 * .mp4 and .mov are the encoded containers the ENCODED path opens
 * (SourcePathDecision's video/mp4 rescue). Anything else in the folder
 * is skipped.
 *
 * Device-gated: SAF queries need a real ContentResolver, so the query
 * itself carries NO JVM tests — the owner exercises it on device (grant
 * the folder, watch the grid). The pure rules live in
 * [isSupportedVideoName], [kindFor], [documentIdOf] and [isImported]
 * (JVM-tested).
 */
object VideoTree {

    /** One supported video child of the granted tree: its display name + document URI. */
    data class VideoDocument(val displayName: String, val uri: String)

    /**
     * The video file extensions the import browser offers, lowercase —
     * matched case-insensitively through [isSupportedVideoName].
     */
    val SUPPORTED_VIDEO_EXTENSIONS: Set<String> =
        setOf("mcraw", "dng", "milog", "mp4", "mov")

    /** The encoded containers — an import of one of these takes [SourceKind.ENCODED]. */
    private val ENCODED_EXTENSIONS: Set<String> = setOf("mp4", "mov")

    /**
     * Pure: does this display name carry a supported video extension?
     * The extension matches case-insensitively (SAF names arrive as the
     * provider spelled them); a name with no dot, or a trailing one, is
     * not a video.
     */
    fun isSupportedVideoName(name: String): Boolean {
        val cut = name.lastIndexOf('.')
        if (cut < 0) return false
        return name.substring(cut + 1).lowercase() in SUPPORTED_VIDEO_EXTENSIONS
    }

    /**
     * Pure: which engine path an import of this name takes. The encoded
     * containers route to [SourceKind.ENCODED] — the honest kind up front,
     * instead of relying on the video/mp4 mime rescue at open time; every
     * other supported name takes [SourceKind.MCRAW], the single-file
     * picker's default.
     */
    fun kindFor(name: String): SourceKind {
        val cut = name.lastIndexOf('.')
        if (cut < 0) return SourceKind.MCRAW
        return if (name.substring(cut + 1).lowercase() in ENCODED_EXTENSIONS) {
            SourceKind.ENCODED
        } else {
            SourceKind.MCRAW
        }
    }

    /**
     * Enumerates the granted folder's direct supported-video children
     * (the [GyroTree.listGcsv] child-query shape). Non-video siblings
     * are skipped at the query loop, so a mixed folder (the .gcsv pairs
     * sit here too) never yields a log or junk document.
     *
     * Throws (SecurityException and friends) when the grant was dropped
     * or the provider is unreachable — callers catch and degrade, the
     * same contract as the gyro folder query.
     */
    fun listVideos(context: Context, treeUri: String): List<VideoDocument> {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val out = mutableListOf<VideoDocument>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (!isSupportedVideoName(name)) continue
                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                out.add(VideoDocument(displayName = name, uri = documentUri.toString()))
            }
        }
        return out
    }

    /**
     * Pure: the document id of a document URI — its LAST path segment,
     * percent-decoded (DocumentsContract builds document URIs with
     * encoded segments; getPathSegments decodes them the same way). The
     * same file picked through ACTION_OPEN_DOCUMENT and enumerated
     * through a tree grant carries the SAME document id in both URIs
     * (`primary:Documents/MotionCam/clip.mcraw`), which is exactly what
     * [isImported] compares. Null when the string carries no path.
     *
     * The decode is per-%XX-byte, not full UTF-8: a multi-byte name
     * decodes differently than Uri.decode would spell it — harmless,
     * because BOTH sides of the comparison go through this same decoder,
     * so equal ids stay equal and unequal stay unequal.
     */
    fun documentIdOf(uri: String): String? {
        val noQuery = uri.substringBefore('?')
        val schemeCut = noQuery.indexOf("://")
        val path = if (schemeCut >= 0) {
            val afterScheme = noQuery.substring(schemeCut + 3)
            val slash = afterScheme.indexOf('/')
            if (slash < 0) return null else afterScheme.substring(slash + 1)
        } else {
            noQuery.removePrefix("/")
        }
        if (path.isBlank()) return null
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return percentDecode(last)
    }

    /**
     * Pure: has this document already been imported? The CHEAP check the
     * browser dims on — fingerprinting every folder file would read a MiB
     * each, so the comparison is same-uri / same-document-id: a source
     * matches when its uri string is the same, or when the two document
     * ids match (the same file picked through a different SAF entry
     * point — a tree-built URI the earlier OpenDocument pick never
     * spelled). A dedupe that slips past this (same bytes, different
     * file) is still honest: [SourceRepository.import] returns the
     * existing record and the browser refreshes.
     */
    fun isImported(documentUri: String, sources: List<MediaSource>): Boolean {
        val documentId = documentIdOf(documentUri)
        return sources.any { s ->
            s.uri == documentUri ||
                (documentId != null && documentIdOf(s.uri) == documentId)
        }
    }

    /** Decodes %XX escapes; a malformed escape passes through as-is. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
