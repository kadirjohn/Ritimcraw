package com.ritim.ritimcraw.proxy

/**
 * The wired playback side of the switch rule (docs/PROXY_PLAN.md section 5,
 * the G3 brief's item 2): ONE place a playback session asks "which file do I
 * open, and when may I swap to the proxy that just finished?" — the E2 pure
 * [ProxySwitch] decision applied to the real cache, as a small stateful
 * holder the session owns.
 *
 * The session's lifecycle with the gate:
 *  1. [open] at session start: resolve the signature's entry through
 *     [ProxyCache.playable] — a hit means the session opens the PROXY file,
 *     a miss means the original (the plan's single decision point: "oturum
 *     açılırken ProxyCache.playable varsa ... açılır"). The session records
 *     WHICH file it opened so it can [acquire] the in-use protection and
 *     answer "am I on the proxy?" honestly.
 *  2. [maybeSwitch] whenever a build the session cares about lands terminal
 *     (the runtime's kick): the E2 rule decides — not playing: switch NOW
 *     (the session reopens); playing: only at the proxy's own GOP boundary
 *     ([ProxySettings.keyFrameIntervalSec]); mid-GOP: stay on the original.
 *  3. [close] at session close: release the in-use protection.
 *
 * PURE over its inputs (the cache, the switch rule, the in-use set): the
 * actual reopen/swap is the CALLER's — the gate only decides and tracks, so
 * the whole contract is JVM-testable with the real P1 cache (the E2
 * ProxySwitchTest already covers the rule itself; here the wiring is tested:
 * the playable resolution, the in-use acquire/release, the never-half truth).
 */
class ProxyPlaybackGate(
    private val cache: ProxyCache,
    private val inUse: ProxyInUse,
    /**
     * The proxy feature flag (FeatureFlags.proxyEnabled): while false the gate
     * answers "original" to everything, so proxies that were generated earlier
     * are NOT used either (owner rule: flag off = consistent, no hidden state).
     */
    private val enabled: () -> Boolean = { true },
) {
    /**
     * A build for this session's clip landed while the session was running on
     * the original. The session does NOT swap now (never mid-playback, never
     * under the user's eyes): it reopens on the proxy at the NEXT seek/play
     * ([takePendingSwitch]).
     */
    @Volatile var switchPending: Boolean = false
        private set

    /** The runtime's terminal-Ready kick for this clip: mark the screen state. */
    fun onBuildLanded() {
        if (!enabled() || signature == null || onProxy) return
        switchPending = true
    }

    /**
     * Called by the screen at the next seek/play after [onBuildLanded]: the
     * switch TARGET (the screen reopens on it and reports [swapped]) or null
     * when nothing is pending / the proxy is gone. [maybeSwitch] is used with
     * playing = false: a seek/play trigger is the boundary (the old session is
     * closed and a new one opened, not live-swapped).
     */
    fun takePendingSwitch(): ProxyEntry? {
        if (!switchPending) return null
        switchPending = false
        return maybeSwitch(playing = false, lastPresentedPtsUs = 0)
    }

    /** The signature this session's clip would build under. */
    private var signature: ProxySignature? = null

    /** The entry the session OPENED (the proxy file), or null = the original. */
    private var openedEntry: ProxyEntry? = null

    /** True while the session is ON the proxy file. */
    val onProxy: Boolean get() = openedEntry != null

    /** The playable proxy entry the session should OPEN, or null = open the original. */
    fun resolve(sig: ProxySignature): ProxyEntry? = if (enabled()) cache.playable(sig) else null

    /**
     * The session's open decision: the playable entry (the session acquires
     * the in-use protection NOW and opens the proxy file) or null (the
     * original; nothing is acquired).
     */
    fun open(sig: ProxySignature): ProxyEntry? {
        close()
        signature = sig
        if (!enabled()) return null
        val entry = cache.playable(sig) ?: return null
        openedEntry = entry
        inUse.acquire(entry.key)
        return entry
    }

    /**
     * The swap decision after a build landed: the E2 [ProxySwitch.decide]
     * over the session's own state. [playing] = the editor's playback clock;
     * [lastPresentedPtsUs] = the frame the player last presented. The returned
     * entry is the switch TARGET (null = the decision said stay/switch to
     * nothing): the caller reopens through it and reports back with [swapped].
     */
    fun maybeSwitch(playing: Boolean, lastPresentedPtsUs: Long): ProxyEntry? {
        if (!enabled()) return null
        val sig = signature ?: return null
        val entry = cache.playable(sig) ?: return null
        if (entry.key == openedEntry?.key) return null // already on it
        val interval = sig.settings.keyFrameIntervalSec
        return when (ProxySwitch.decide(
            ProxySwitchRequest(entry, interval, playing, lastPresentedPtsUs),
        )) {
            ProxySwitchDecision.SWITCH_NOW, ProxySwitchDecision.SWITCH_AT_BOUNDARY -> entry
            ProxySwitchDecision.STAY_ON_ORIGINAL -> null
        }
    }

    /** The caller swapped to [entry] (the reopen succeeded): move the protection. */
    fun swapped(entry: ProxyEntry) {
        openedEntry?.let { inUse.release(it.key) }
        openedEntry = entry
        switchPending = false
        inUse.acquire(entry.key)
    }

    /** Session close: release the in-use protection (idempotent). */
    fun close() {
        openedEntry?.let { inUse.release(it.key) }
        openedEntry = null
        signature = null
        switchPending = false
    }
}
