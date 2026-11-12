package com.ritim.ritimcraw.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The SAF tree query for the gyro auto-match (owner request 2026-11-12,
 * G1): the device-gated half that turns a granted folder tree URI into
 * the .gcsv display names + document URIs [GyroMatcher] matches against.
 *
 * DELIBERATELY RAW DocumentsContract, not androidx.documentfile: the
 * matcher needs only ONE child query (names + document ids), and
 * DocumentFile would add a dependency for per-file object churn this
 * flow never uses.
 *
 * SINGLE-LEVEL ONLY: the query lists the granted folder's DIRECT
 * children. MotionCam writes flat sibling pairs (the owner's
 * Documents/MotionCam holds ~15 `name.gcsv` + `name.mcraw` pairs), so
 * recursion would only slow the query and widen the ambiguity surface.
 * .gcsv files inside subfolders are not matched — pick the folder that
 * holds the pairs.
 *
 * Device-gated: SAF queries need a real ContentResolver, so this object
 * carries NO JVM tests — the owner exercises it on device (grant the
 * folder, import a clip, watch the attach). The pure naming rules live
 * in [GyroMatcher] and [labelFromDocumentId] (JVM-tested).
 */
object GyroTree {

    /** One .gcsv child of the granted tree: its display name + document URI. */
    data class GcsvDocument(val displayName: String, val uri: String)

    /**
     * Enumerates the granted folder's direct .gcsv children (extension
     * matched case-insensitively). Non-.gcsv siblings are skipped at the
     * query loop so a mixed folder (the .mcraw pairs sit here too) never
     * yields a video or junk document.
     *
     * Throws (SecurityException and friends) when the grant was dropped
     * or the provider is unreachable — callers catch and degrade to a
     * silent no-attach, the same contract as a lost clip grant.
     */
    fun listGcsv(context: Context, treeUri: String): List<GcsvDocument> {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val out = mutableListOf<GcsvDocument>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (!name.endsWith(".gcsv", ignoreCase = true)) continue
                val documentUri =
                    DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                out.add(GcsvDocument(displayName = name, uri = documentUri.toString()))
            }
        }
        return out
    }

    /**
     * A friendly label for the granted tree (the Settings row shows it):
     * `primary:Documents/MotionCam` -> `Documents/MotionCam`. Returns
     * null for an unparseable URI (the row falls back to "not set"
     * wording rather than showing an opaque content:// string).
     */
    fun folderLabel(treeUri: String): String? = try {
        labelFromDocumentId(DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))
    } catch (e: Exception) {
        null
    }

    /**
     * The label rule, pure: the tree document id is
     * `<volume>:<path>` (`primary:Documents/MotionCam`,
     * `removable:1234-5678:DCIM/MotionCam`) — the path after the LAST
     * colon is the human-readable part. A blank result is null.
     */
    fun labelFromDocumentId(documentId: String): String? {
        val cut = documentId.lastIndexOf(':')
        val label = if (cut >= 0) documentId.substring(cut + 1) else documentId
        return label.takeIf { it.isNotBlank() }
    }
}
