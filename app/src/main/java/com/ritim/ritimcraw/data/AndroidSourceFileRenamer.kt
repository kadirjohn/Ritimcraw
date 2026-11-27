package com.ritim.ritimcraw.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF implementation of [SourceFileRenamer] (device-gated; the logic it
 * serves is JVM-tested through a fake). Works only for TREE document URIs
 * (the folder import builds those): the parent listing and the rename both
 * ride the folder grant, which must include WRITE. Anything else (a plain
 * picked document, a read-only grant, a provider that refuses) answers
 * null and the caller falls back to the app-only rename.
 */
class AndroidSourceFileRenamer(private val resolver: ContentResolver) : SourceFileRenamer {

    constructor(context: Context) : this(context.contentResolver)

    override fun siblings(uri: String): List<SiblingDoc>? = try {
        val doc = Uri.parse(uri)
        if (doc.pathSegments.firstOrNull() != "tree") {
            null
        } else {
            val parentId = parentDocumentId(DocumentsContract.getDocumentId(doc))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(doc, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val out = mutableListOf<SiblingDoc>()
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out.add(SiblingDoc(name, DocumentsContract.buildDocumentUriUsingTree(doc, id).toString()))
                }
            }
            out
        }
    } catch (e: Exception) {
        null
    }

    override fun rename(uri: String, newName: String): String? = try {
        DocumentsContract.renameDocument(resolver, Uri.parse(uri), newName)?.toString()
    } catch (e: Exception) {
        null
    }

    internal companion object {
        /** "primary:a/b/c.mcraw" -> "primary:a/b"; "primary:c.mcraw" -> "primary:". */
        fun parentDocumentId(documentId: String): String {
            val slash = documentId.lastIndexOf('/')
            if (slash >= 0) return documentId.substring(0, slash)
            val colon = documentId.lastIndexOf(':')
            return if (colon >= 0) documentId.substring(0, colon + 1) else documentId
        }
    }
}
