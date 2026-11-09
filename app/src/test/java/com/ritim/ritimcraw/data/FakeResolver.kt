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
) : UriResolver {

    override fun open(uri: String): InputStream {
        if (revoked) throw SecurityException("permission revoked for $uri")
        return ByteArrayInputStream(data)
    }

    override fun size(uri: String): Long = data.size.toLong()
}
