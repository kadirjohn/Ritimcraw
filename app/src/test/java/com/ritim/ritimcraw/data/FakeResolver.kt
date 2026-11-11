package com.ritim.ritimcraw.data

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory [UriResolver] for JVM tests, with revocable access: [revoked]
 * makes open() throw SecurityException like a revoked SAF permission.
 */
class FakeResolver(
    var data: ByteArray = ByteArray(0),
    var revoked: Boolean = false,
    /** Per-uri display names (owner request §2b); unlisted uris return null. */
    var names: Map<String, String> = emptyMap(),
) : UriResolver {

    override fun open(uri: String): InputStream {
        if (revoked) throw SecurityException("permission revoked for $uri")
        return ByteArrayInputStream(data)
    }

    override fun size(uri: String): Long = data.size.toLong()

    override fun displayName(uri: String): String? = names[uri]
}
