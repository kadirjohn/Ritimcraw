package com.ritim.ritimcraw.proxy

/**
 * Where the LRU gets its "do not evict" keys from (decision 2026-11-26, docs/PROXY_PLAN.md (e)).
 * Eviction must spare proxies in use (playing) and being rebuilt. The cache only asks this
 * interface; phase P3 plugs the real playback / job-runner sources in, without touching the cache.
 * Implementations must be cheap and thread-safe: [protectedKeys] is called under the cache lock
 * and must not call back into the cache.
 */
fun interface ProxyProtectSource {
    fun protectedKeys(): Set<ProxyKey>

    companion object {
        /** Protects nothing. */
        val NONE: ProxyProtectSource = ProxyProtectSource { emptySet() }

        /** Union of several sources, evaluated at call time. */
        fun union(vararg sources: ProxyProtectSource): ProxyProtectSource =
            ProxyProtectSource { sources.flatMapTo(HashSet()) { it.protectedKeys() } }
    }
}

/**
 * Reference-counted "in use" set: a playback session [acquire]s the proxy it opened and
 * [release]s it on close (two sessions on one proxy need two releases). Pure and thread-safe;
 * this is the ready-made source P3 wires to the preview session lifecycle.
 */
class ProxyInUse : ProxyProtectSource {
    private val lock = Any()
    private val counts = HashMap<ProxyKey, Int>()

    fun acquire(key: ProxyKey) = synchronized(lock) { counts[key] = (counts[key] ?: 0) + 1 }

    fun release(key: ProxyKey) = synchronized(lock) {
        val n = counts[key] ?: return@synchronized
        if (n <= 1) counts.remove(key) else counts[key] = n - 1
    }

    fun isInUse(key: ProxyKey): Boolean = synchronized(lock) { key in counts }

    override fun protectedKeys(): Set<ProxyKey> = synchronized(lock) { counts.keys.toSet() }
}
