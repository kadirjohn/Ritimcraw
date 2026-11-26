package com.ritim.ritimcraw.proxy

/** What kind of source a proxy is made from. */
enum class ProxySourceKind { ENCODED, MCRAW }

/** The minimum a policy decision needs to know about a clip. Pure data. */
data class ProxyClipInfo(
    val kind: ProxySourceKind,
    val width: Int,
    val height: Int,
    val fps: Double,
)

/**
 * Default cache cap: 20 GiB (20 * 2^30 = 21_474_836_480 bytes). Owner-approved 2026-11-26
 * ("the 20 GB cap is APPROVED"); the binary reading is used because filesystems and
 * `StatFs` report binary sizes. Single source of truth for [ProxyPolicy] and [ProxyCache].
 */
const val PROXY_CAP_DEFAULT_BYTES: Long = 20L shl 30

/**
 * Owner-approved defaults (docs/PROXY_PLAN.md, decisions of 2026-11-25):
 * automatic proxy for 8K and 4K60 encoded clips, MCRAW proxy opt-in, 20 GB cap.
 */
data class ProxyPolicy(
    val autoEncoded: Boolean = true,
    val autoMcraw: Boolean = false,
    val capBytes: Long = PROXY_CAP_DEFAULT_BYTES,
) {
    /** Pure decision: should a proxy be queued automatically for [clip]. */
    fun shouldAutoGenerate(clip: ProxyClipInfo): Boolean = when (clip.kind) {
        ProxySourceKind.MCRAW -> autoMcraw
        ProxySourceKind.ENCODED -> autoEncoded && (is8k(clip) || is4k60(clip))
    }

    private fun is8k(c: ProxyClipInfo): Boolean =
        maxOf(c.width, c.height) >= LONG_SIDE_8K || minOf(c.width, c.height) >= SHORT_SIDE_8K

    private fun is4k60(c: ProxyClipInfo): Boolean =
        maxOf(c.width, c.height) >= LONG_SIDE_4K && c.fps > FPS_HIGH_ABOVE

    companion object {
        // Format class definitions (UHD/8K frame sizes, "60p" means above 50 fps as in the plan), not tuned numbers.
        const val LONG_SIDE_8K = 7680
        const val SHORT_SIDE_8K = 4320
        const val LONG_SIDE_4K = 3840
        const val FPS_HIGH_ABOVE = 50.0
    }
}

/** Convenience top-level form of [ProxyPolicy.shouldAutoGenerate]. */
fun shouldAutoGenerate(clip: ProxyClipInfo, policy: ProxyPolicy = ProxyPolicy()): Boolean =
    policy.shouldAutoGenerate(clip)
