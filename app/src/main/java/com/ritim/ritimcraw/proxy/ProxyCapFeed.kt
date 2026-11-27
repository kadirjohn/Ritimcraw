package com.ritim.ritimcraw.proxy

/**
 * The "X GB over the cap" data feed (docs/PROXY_PLAN.md owner decision 2026-11-26, item 2:
 * "Buna ek olarak toplam cap'i aşarken UI 'X GB cap üstünde' gösterir" — the P4 storage page
 * line), assembled from the P1 pieces so the wired UI has ONE call. Pure: numbers in,
 * [ProxyCapUsage] out; no formatting, no strings, no units here (the UI formats).
 *
 * What it adds over the bare [ProxyCapUsage] numbers:
 *  - it pulls [totalBytes] from the cache and [protectedBytes] from the protect set itself
 *    (the P1 interface [ProxyProtectSource]: the playing/in-use keys + the keys being rebuilt),
 *    so the feed and the LRU eviction can never disagree about what is protected;
 *  - it keeps an honest [overCapBytes] even when the protected set alone exceeds the cap
 *    (eviction skips protected entries, so the overage stays until playback ends — reported,
 *    never hidden);
 *  - [isUsable] flags the degenerate cases (null cache would be a wiring bug; the wired side
 *    asserts it in debug).
 */
object ProxyCapFeed {

    /**
     * Assemble the P4 line's values. [capBytes] is the CURRENT cap (a user setting can change
     * it at any moment; the cache default lives in [PROXY_CAP_DEFAULT_BYTES]).
     * [inUse] and [building] are the ready-made P1 sources: the playback sessions' acquire/release
     * set and [ProxyStateMap.buildingSource].
     */
    fun usage(
        cache: ProxyCache,
        capBytes: Long,
        inUse: ProxyProtectSource,
        building: ProxyProtectSource,
    ): ProxyCapUsage {
        val total = cache.totalBytes()
        val protectedKeys = ProxyProtectSource.union(inUse, building).protectedKeys()
        val protectedBytes = protectedKeys.sumOf { k -> cache.peekPlayable(k)?.bytes ?: 0L }
        return ProxyCapUsage(totalBytes = total, capBytes = capBytes, protectedBytes = protectedBytes)
    }
}
