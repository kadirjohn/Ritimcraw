package com.ritim.ritimcraw.export

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.OutputStream

/**
 * The SAF folder adapter of a CinemaDNG sequence export (night E-F,
 * docs/CINEMADNG_EXPORT_PLAN.md — the owner decision: the output folder
 * is the user's SAF pick, ACTION_OPEN_DOCUMENT_TREE with
 * takePersistableUriPermission, written through ContentResolver/
 * DocumentsContract — never a direct file path).
 *
 * This is the THIN Android adapter over [CinemaDngIo]: every method maps
 * onto one DocumentsContract call, no policy lives here. The policy
 * (naming, cancel, partial results, the storage gate) is the exporter's
 * pure code; the folder only does what SAF does:
 *  - [openChildWriteFd]: create the child document (a MIME that is not
 *    application/x-dng anywhere standard; the generic
 *    application/octet-stream keeps every file manager honest) and open
 *    it "w";
 *  - [deleteChild]: delete a half-written frame's document (the failed
 *    frame only — the WRITTEN frames are the exporter's to keep);
 *  - [freeBytes]: DocumentsContract's summary, -1 when unknown (the plan
 *    handles it: an unknown free space skips the lock, never guesses).
 *
 * A subfolder is created for the sequence's files (the owner's "seçilen
 * klasörde alt klasör" convention follows MotionCam's own layout — one
 * folder per export, so two exports never mix their frame numbers; the
 * name is the export's prefix + a short job id fragment).
 */
class CinemaDngFolder(
    private val context: Context,
    private val treeUri: String,
) {

    private val resolver = context.contentResolver
    private val tree = Uri.parse(treeUri)

    /**
     * Creates the export's own subfolder inside the granted tree (one
     * export, one folder — two sequences never mix frame numbers) and
     * returns ITS document id, or null when SAF refused (the caller
     * fails honestly before any frame is written). [name] must already
     * be the cleaned, unique-ified folder name.
     */
    fun ensureSubfolder(name: String): String? {
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
        return try {
            val childUri = DocumentsContract.createDocument(
                resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, name,
            ) ?: return existingChildId(dir, name)
            DocumentsContract.getDocumentId(childUri)
        } catch (e: Exception) {
            existingChildId(dir, name)
        }
    }

    /** The existing child's document id (a re-export into the same folder). */
    private fun existingChildId(dir: Uri, name: String): String? = try {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(dir))
        resolver.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameCol) == name) return c.getString(idCol)
            }
            null
        } ?: null
    } catch (e: Exception) {
        null
    }

    /**
     * The whole adapter's seam: the subfolder's document id, set by the
     * queue runner before the export starts ([ensureSubfolder]). An
     * empty string means "write into the tree root" (a fallback the
     * caller decides on, never a hidden policy here).
     */
    @Volatile
    var subfolderDocId: String? = null

    /** Creates the child document [name] in the subfolder and returns its WRITE fd, or -1. */
    fun openChildWriteFd(name: String): Int {
        val pfd = openChildPfd(name, "w") ?: return -1
        return pfd.detachFd()
    }

    /** Opens the child document [name] as an OutputStream, or null. */
    fun openChildStream(name: String): OutputStream? =
        openChildPfd(name, "w")?.let { pfd ->
            try {
                java.io.FileOutputStream(pfd.fileDescriptor)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                null
            }
        }

    private fun openChildPfd(name: String, mode: String): ParcelFileDescriptor? {
        val parent = subfolderDocId ?: DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        return try {
            val doc = DocumentsContract.createDocument(
                resolver, dir, "application/octet-stream", name)
                ?: return null
            resolver.openFileDescriptor(doc, mode)
        } catch (e: Exception) {
            null
        }
    }

    /** Closes an fd [openChildWriteFd] returned (no-op for -1). */
    fun closeFd(fd: Int) {
        if (fd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
    }

    /**
     * Deletes the child document [name] from the subfolder. Best-effort:
     * false never fails the job (a stale half-written frame is visible
     * to the user, who can delete it — the honest outcome, never a
     * hidden retry loop).
     */
    fun deleteChild(name: String): Boolean {
        return try {
            val parent = subfolderDocId ?: DocumentsContract.getTreeDocumentId(tree)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(
                kids,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(nameCol) == name) {
                        val docId = c.getString(idCol)
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        return DocumentsContract.deleteDocument(resolver, doc)
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The folder's free space in bytes, or -1 when it cannot be measured
     * (SAF has no portable free-space API for arbitrary providers; the
     * primary-volume case resolves through the document's file path, a
     * removable/roamable provider stays unknown). An unknown free space
     * is HONEST: the sheet shows no lock, the plan's gate is skipped
     * (never a guessed verdict), and the mid-write exhaustion path is
     * the backstop.
     *
     * Implementation: query the tree document with the RAW file-name
     * column (the deprecated-but-present DATA column carries the real
     * file-system path on primary volumes; DocumentsContract exposes
     * nothing portable, so a provider without one is simply unknown).
     */
    fun freeBytes(): Long {
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree))
            var path: String? = null
            resolver.query(doc, arrayOf("_data"), null, null, null)?.use { d ->
                val col = d.getColumnIndex("_data")
                if (col >= 0 && d.moveToFirst() && !d.isNull(col)) {
                    path = d.getString(col)
                }
            }
            val p = path ?: return -1L
            val stat = android.os.StatFs(p)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }
}
