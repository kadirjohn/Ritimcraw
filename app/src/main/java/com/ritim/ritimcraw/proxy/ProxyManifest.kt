package com.ritim.ritimcraw.proxy

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson

/** Parsed content of a `<fingerprint>-<sigHash>.json` manifest. */
data class ProxyManifest(
    val fingerprint: String,
    val sigHash: String,
    val videoBytes: Long,
    val durationUs: Long,
    val frameCount: Long,
    val createdAtMs: Long,
    /** Full canonical signature text, when the build began from a [ProxySignature]. */
    val signature: String?,
)

/**
 * The proxy manifest is JSON (decision 2026-11-26, docs/PROXY_PLAN.md (b)), written with the same
 * in-repo helpers as `project.json` ([ProjectJson.quote] to write, [JsonParser] to read).
 * Numbers are JSON numbers (exact for every value below 2^53, far above any byte size or
 * epoch-ms). Reading is tolerant: any damaged, truncated or foreign text yields null, never an
 * exception, and the cache then treats the entry as absent (sweep removes it).
 */
object ProxyManifestCodec {
    const val VERSION = 1

    fun render(m: ProxyManifest): String = buildString {
        append("{\"v\":").append(VERSION)
        append(",\"fingerprint\":").append(ProjectJson.quote(m.fingerprint))
        append(",\"sigHash\":").append(ProjectJson.quote(m.sigHash))
        append(",\"videoBytes\":").append(m.videoBytes)
        append(",\"durationUs\":").append(m.durationUs)
        append(",\"frameCount\":").append(m.frameCount)
        append(",\"createdAtMs\":").append(m.createdAtMs)
        if (m.signature != null) append(",\"sig\":").append(ProjectJson.quote(m.signature))
        append("}\n")
    }

    fun parse(bytes: ByteArray): ProxyManifest? = try {
        val p = JsonParser(String(bytes, Charsets.UTF_8))
        val root = p.parseValue() as? Map<*, *>
        if (root == null || !p.atEnd()) null else from(root)
    } catch (_: Exception) {
        null // truncated / garbage / bad escape / index errors alike
    }

    private fun from(r: Map<*, *>): ProxyManifest? {
        if (num(r["v"]) != VERSION.toLong()) return null
        return ProxyManifest(
            fingerprint = r["fingerprint"] as? String ?: return null,
            sigHash = r["sigHash"] as? String ?: return null,
            videoBytes = num(r["videoBytes"]) ?: return null,
            durationUs = num(r["durationUs"]) ?: -1,
            frameCount = num(r["frameCount"]) ?: -1,
            createdAtMs = num(r["createdAtMs"]) ?: 0,
            signature = r["sig"] as? String,
        )
    }

    private fun num(v: Any?): Long? {
        val d = (v as? Double) ?: return null
        return if (d.isFinite() && d == Math.rint(d)) d.toLong() else null
    }
}
