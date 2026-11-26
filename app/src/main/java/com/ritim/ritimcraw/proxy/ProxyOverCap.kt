package com.ritim.ritimcraw.proxy

/**
 * How many bytes the cache is above [capBytes]; never negative. Negative inputs are treated as 0
 * so the subtraction cannot overflow. Pure; the UI formats the number (no strings here).
 */
fun overCapBytes(totalBytes: Long, capBytes: Long): Long =
    (totalBytes.coerceAtLeast(0L) - capBytes.coerceAtLeast(0L)).coerceAtLeast(0L)

/**
 * The three numbers the P3 "X GB over the cap" line needs (owner decision 2026-11-26):
 * [totalBytes] = [ProxyCache.totalBytes], [capBytes] = the current cap, [protectedBytes] = bytes of
 * proxies that eviction must skip (playing / being rebuilt). Formatter-free.
 */
data class ProxyCapUsage(val totalBytes: Long, val capBytes: Long, val protectedBytes: Long) {
    /** Bytes above the cap (0 when within it). */
    val overCapBytes: Long get() = overCapBytes(totalBytes, capBytes)

    /** True when the UI should show the "over the cap" line. */
    val isOverCap: Boolean get() = overCapBytes > 0L

    /**
     * Part of the overage that cannot be evicted right now (it is held by protected proxies);
     * it goes away after playback ends. Bounded by [overCapBytes] and by [protectedBytes].
     */
    val overCapBecauseProtectedBytes: Long
        get() = minOf(overCapBytes, protectedBytes.coerceAtLeast(0L))
}
