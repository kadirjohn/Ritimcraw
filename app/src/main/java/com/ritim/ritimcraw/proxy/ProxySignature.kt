package com.ritim.ritimcraw.proxy

import java.security.MessageDigest

/** Bump when the writer, downscale or proxy transfer changes: every older proxy becomes stale. */
const val PROXY_ENCODER_VERSION = 1

/**
 * Hex characters of the signature hash in a file name: 16 = 64 bits (decision 2026-11-26,
 * docs/PROXY_PLAN.md "Decisions 2026-11-26" (d): collision odds ~1e-16 per clip).
 */
const val PROXY_SIG_HASH_HEX_CHARS = 16

/**
 * Encode settings that are part of the signature. The proxy is always 10-bit HEVC
 * (also for 8-bit sources), so [bitDepth] and [codec] default to that. [bitrateClass] is an
 * opaque label (the actual bitrate numbers are measured on the device later and injected by the writer).
 */
data class ProxySettings(
    val shortSide: Int = 1080,
    val codec: String = "video/hevc",
    val bitDepth: Int = 10,
    val bitrateClass: String = "default",
    val keyFrameIntervalSec: Int = 1,
)

/** Identity of one cache entry: filename-safe, stable. */
data class ProxyKey(val fingerprint: String, val sigHash: String) {
    init {
        require(SAFE.matches(fingerprint)) { "fingerprint must be [A-Za-z0-9]+: $fingerprint" }
        require(SAFE.matches(sigHash)) { "sigHash must be [A-Za-z0-9]+: $sigHash" }
    }

    /** Base file name, "<fingerprint>-<sigHash>". */
    val baseName: String get() = "$fingerprint-$sigHash"

    private companion object { val SAFE = Regex("[A-Za-z0-9]+") }
}

/**
 * Everything that decides whether a proxy file is still valid. If any field changes, the proxy is stale.
 * [colorSpecKey] is the source EncodedColorSpec key (matrix/range/transfer/primaries/bit).
 * [headroomAffine] is the plan's "h" flag (Klipp-style affine headroom fallback), off by default:
 * the Headroom rule is to write codes with the source range/matrix and no rescale and no clamp.
 */
data class ProxySignature(
    val sourceFingerprint: String,
    val sourceSize: Long,
    val sourceModifiedMs: Long,
    val settings: ProxySettings = ProxySettings(),
    val colorSpecKey: String,
    val kind: ProxySourceKind,
    val mcrawTransferVersion: Int = 0,
    val encoderVersion: Int = PROXY_ENCODER_VERSION,
    val headroomAffine: Boolean = false,
) {
    /** Canonical, order-fixed text form. Same fields -> same string, always. */
    fun canonical(): String = listOf(
        "v1",
        "kind=${kind.name}",
        "fp=$sourceFingerprint",
        "size=$sourceSize",
        "mtime=$sourceModifiedMs",
        "short=${settings.shortSide}",
        "codec=${settings.codec}",
        "bits=${settings.bitDepth}",
        "br=${settings.bitrateClass}",
        "kfi=${settings.keyFrameIntervalSec}",
        "color=$colorSpecKey",
        "xfer=$mcrawTransferVersion",
        "enc=$encoderVersion",
        "h=${if (headroomAffine) 1 else 0}",
    ).joinToString("|")

    /** First [PROXY_SIG_HASH_HEX_CHARS] hex chars of SHA-256 over [canonical]. */
    fun hash(): String {
        val d = MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray(Charsets.UTF_8))
        return d.take(PROXY_SIG_HASH_HEX_CHARS / 2).joinToString("") { "%02x".format(it) }
    }

    fun key(): ProxyKey = ProxyKey(sourceFingerprint, hash())
}
