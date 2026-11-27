package com.ritim.ritimcraw.proxy

/**
 * The "playable" transition rule (docs/PROXY_PLAN.md section 5, the E2 brief's item 3) as a
 * pure decision: WHEN may a playback session switch from the original file to a proxy that
 * has just become available?
 *
 * Two gates, both mandatory:
 *
 * 1. COMPLETENESS: the proxy must resolve through [ProxyCache.playable] — only a fully
 *    committed (post-rename: the manifest is published LAST, section 3.3) and size-validated
 *    proxy ever resolves. A half proxy NEVER plays: `playable` returns null for a `.tmp`,
 *    for a crash leftover, for a torn manifest, for a size mismatch. This class adds no second
 *    completeness rule of its own — it REFUSES to invent one — it just refuses to switch
 *    unless [playable] said yes (the caller passes the entry it got).
 *
 * 2. TIMING: the session switches at a KEYFRAME BOUNDARY of the playback timeline — never in
 *    the middle of a running playback (a live source swap mid-play risks a glitch; Klipp's
 *    lesson, plan section 5 "Geçiş"). Concretely the switch happens at the next natural
 *    boundary event the player reports:
 *      - the session is not playing (paused / scrubbing / just opened) -> switch NOW;
 *      - playback just crossed a frame whose PTS is a multiple of the proxy's key-frame
 *        interval (the writer's GOP: [ProxySettings.keyFrameIntervalSec], default 1 s) ->
 *        switch at that frame, the decode of the proxy can start exactly on its keyframe;
 *      - otherwise -> keep the original, ask again at the next boundary.
 *
 * The decision is pure data in / decision out; the wired player performs the actual swap.
 */
data class ProxySwitchRequest(
    /** The entry [ProxyCache.playable] returned, or null when it did not resolve. */
    val playableEntry: ProxyEntry?,
    /** The proxy's key-frame interval, [ProxySettings.keyFrameIntervalSec] seconds. */
    val keyFrameIntervalSec: Int,
    /** True while the playback clock is running (playing = the swap would be visible live). */
    val playing: Boolean,
    /**
     * The PTS (us) of the frame the player most recently presented; the boundary math is
     * done on the PROXY's own grid (its PTS timeline is identical to the original's, plan
     * section 5 "Zaman"). Negative or zero = "no frame presented yet".
     */
    val lastPresentedPtsUs: Long,
)

enum class ProxySwitchDecision { SWITCH_NOW, SWITCH_AT_BOUNDARY, STAY_ON_ORIGINAL }

object ProxySwitch {

    /**
     * The completeness gate alone: only a proxy that IS a resolved [ProxyEntry] may ever be
     * a switch target. Everything half (null) keeps the session on the original.
     */
    fun isPlayable(entry: ProxyEntry?): Boolean = entry != null

    fun decide(request: ProxySwitchRequest): ProxySwitchDecision {
        // Gate 1 — half proxies never play; a missing entry means "no proxy yet", not "switch anyway".
        if (!isPlayable(request.playableEntry)) return ProxySwitchDecision.STAY_ON_ORIGINAL

        // Gate 2 — timing. Not playing: the next frame comes from the proxy, switch now.
        if (!request.playing) return ProxySwitchDecision.SWITCH_NOW

        // Playing: only at a key-frame boundary of the proxy's own GOP grid.
        val intervalSec = request.keyFrameIntervalSec
        if (intervalSec <= 0) return ProxySwitchDecision.SWITCH_AT_BOUNDARY
        if (request.lastPresentedPtsUs < 0L) return ProxySwitchDecision.STAY_ON_ORIGINAL
        val intervalUs = intervalSec * 1_000_000L
        val atBoundary = request.lastPresentedPtsUs % intervalUs == 0L
        return if (atBoundary) ProxySwitchDecision.SWITCH_AT_BOUNDARY else ProxySwitchDecision.STAY_ON_ORIGINAL
    }

    /**
     * Convenience for the wired player: the distance (us) to the next key-frame boundary after
     * [ptsUs] on the GOP grid (never negative). At exactly a boundary the next one is a full
     * interval away — a presented frame AT the boundary is itself the switch point.
     */
    fun nextBoundaryAfter(ptsUs: Long, intervalSec: Int): Long {
        require(intervalSec > 0) { "intervalSec must be > 0" }
        if (ptsUs < 0L) return 0L
        val intervalUs = intervalSec * 1_000_000L
        val rem = ptsUs % intervalUs
        return if (rem == 0L) intervalUs else intervalUs - rem
    }
}
