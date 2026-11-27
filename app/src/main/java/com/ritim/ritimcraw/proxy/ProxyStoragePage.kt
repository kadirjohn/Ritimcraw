package com.ritim.ritimcraw.proxy

/**
 * The P4 storage page's pure state (docs/PROXY_PLAN.md section 6 + the owner's
 * 2026-11-26 decisions): what the page shows, assembled from the P1 cache and
 * the E2 queue so the UI has ONE call and never recomputes a byte or a state
 * itself. Pure: numbers and states in; the formatted text is the UI layer's
 * (this package keeps no strings, the E2 feed's own rule).
 */
data class ProxyStorageState(
    /** [ProxyCapUsage]: total/cap/protected bytes and the honest overage. */
    val usage: ProxyCapUsage,
    /** Per-clip committed proxy bytes, largest first (the page's rows). */
    val perClip: List<ProxyClipUsage>,
    /** Fingerprints with a job queued right now (the honest "queued" line). */
    val queued: Set<String>,
    /** Fingerprints with a build running right now ("building"). */
    val building: Set<String>,
    /** Fingerprints that failed this session, with the queue's honest reasons. */
    val failed: Map<String, String>,
)

/** One clip's proxy usage (the page's per-clip row). */
data class ProxyClipUsage(
    val fingerprint: String,
    val bytes: Long,
)

/**
 * Assembles the page's state from the P1 cache + the E2 queue (all the pieces
 * the G3 brief's "depolama sayfası durum fonksiyonları (saf, JVM testli)" names).
 * The UI calls it on a refresh — the storage page is not a live stream.
 */
object ProxyStorageFeed {

    fun state(
        cache: ProxyCache,
        queue: ProxyStateMap,
        capBytes: Long,
        inUse: ProxyProtectSource,
        building: ProxyProtectSource,
    ): ProxyStorageState {
        val usage = ProxyCapFeed.usage(cache, capBytes, inUse, building)
        // per-clip bytes: every committed signature of one fingerprint summed
        // (the cache's own bytesForClip), largest clip first
        val perClip = cache.entries()
            .map { it.key.fingerprint }
            .distinct()
            .map { fp -> ProxyClipUsage(fp, cache.bytesForClip(fp)) }
            .sortedByDescending { it.bytes }
        return ProxyStorageState(
            usage = usage,
            perClip = perClip,
            queued = queue.queued().map { it.fingerprint }.toSet(),
            building = building.protectedKeys().map { it.fingerprint }.toSet(),
            failed = queue.failures().entries.associate { it.key.fingerprint to it.value },
        )
    }
}
