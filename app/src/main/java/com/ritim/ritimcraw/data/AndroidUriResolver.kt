package com.ritim.ritimcraw.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Production [UriResolver] over ContentResolver. This is the only file that
 * touches android.net.Uri; the model stays String-based so the repository
 * logic is JVM-testable.
 */
class AndroidUriResolver(
    private val resolver: ContentResolver,
) : UriResolver {

    constructor(context: Context) : this(context.contentResolver)

    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri))
            ?: throw SecurityException("cannot open $uri")

    override fun size(uri: String): Long {
        resolver.query(
            Uri.parse(uri),
            arrayOf(OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
        }
        return -1L
    }

    companion object {
        fun of(context: Context): AndroidUriResolver = AndroidUriResolver(context.contentResolver)

        fun toAndroidUri(uri: String): Uri = Uri.parse(uri)
    }
}
