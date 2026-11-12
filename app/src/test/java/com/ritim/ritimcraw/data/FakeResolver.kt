package com.ritim.ritimcraw.data

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory [UriResolver] for JVM tests, with revocable access and a
 * per-uri existence model:
 *  - [revoked] makes open() throw SecurityException like a revoked SAF
 *    permission.
 *  - [gone] uris behave like deleted/moved documents: open() throws
 *    FileNotFoundException and probe() reports GONE (spec IN-12).
 *  - [probes] overrides the probe answer per uri when set (the refresh
 *    tests decide the three outcomes directly).
 */
class FakeResolver(
    var data: ByteArray = ByteArray(0),
    var revoked: Boolean = false,
    /** Per-uri display names (owner request §2b); unlisted uris return null. */
    var names: Map<String, String> = emptyMap(),
    /** Uris whose document no longer resolves (deleted/moved). */
    var gone: Set<String> = emptySet(),
    /** Explicit probe answers; when a uri is absent, derived from the
     *  revoked/gone model. */
    var probes: Map<String, DocumentProbe> = emptyMap(),
    /** Per-uri content blobs; a uri listed here serves ITS bytes (tests
     *  modeling several distinct files), unlisted uris serve [data]. */
    var blobs: Map<String, ByteArray> = emptyMap(),
) : UriResolver {

    private fun blob(uri: String): ByteArray = blobs[uri] ?: data

    override fun open(uri: String): InputStream {
        if (revoked) throw SecurityException("permission revoked for $uri")
        if (uri in gone) throw java.io.FileNotFoundException("no document $uri")
        return ByteArrayInputStream(blob(uri))
    }

    override fun size(uri: String): Long =
        if (uri in gone) -1L else blob(uri).size.toLong()

    override fun displayName(uri: String): String? = names[uri]

    override fun probe(uri: String): DocumentProbe = probes[uri] ?: when {
        revoked -> DocumentProbe.PERMISSION
        uri in gone -> DocumentProbe.GONE
        else -> DocumentProbe.AVAILABLE
    }
}
