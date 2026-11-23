package com.ritim.ritimcraw.engine

/**
 * The GPU preview's per-request stats readback mode (owner request
 * 2026-11-23, guide A1 — "scope istatistiği takılması, en büyük kazanç").
 *
 * WHY A MODE: draw 3 in GlesPreviewSession.cpp maps the stats SSBO with
 * glMapBufferRange in the SAME frame it was drawn, forcing the CPU to wait
 * for the whole GPU frame (78.5 ms median with scopes ON vs ~18 ms OFF —
 * the measured baseline, guide §3). The fix is demand-driven: the mode is
 * computed per request from whether any consumer of the published pixels
 * is on screen, and PLAYBACK renders read the pack back one frame late
 * through a fence ring instead of blocking (guide A1(b)).
 *
 * The three values — the SAME vocabulary the native StatsRing.h enum
 * StatsReadbackMode carries (the jint that crosses the JNI seam):
 *  - [OFF] — no scope visible (or the master kill switch said no): draw 3
 *    and the readback are skipped entirely and the honest 1x1 placeholder
 *    publishes (the PreviewSurfaceRatio.carriesShape path, commit f7cf7db).
 *  - [SYNC] — settle/drag/first frame/after a mode or dims switch: today's
 *    blocking readback, so a paused edit's scopes show the EXACT frame.
 *  - [ASYNC] — playback: the pack for frame N-1 publishes when its fence
 *    is already signaled (never blocks); on a miss NO pack publishes and
 *    Kotlin keeps the last scopes (the guide's documented one-frame lag).
 */
enum class PreviewStatsMode(val native: Int) {
    OFF(0),
    SYNC(1),
    ASYNC(2),
    ;

    companion object {
        /**
         * The native-int mapper (the StatsRing.h clampStatsMode twin): 0/1/2
         * map directly; ANYTHING else folds to [SYNC] — the safe default and
         * today's behavior. This also makes an OLD .so's 0/1 boolean tail
         * degrade honestly: 1 reads as the sync readback it actually ran.
         */
        fun fromNative(value: Int): PreviewStatsMode = when (value) {
            OFF.native -> OFF
            ASYNC.native -> ASYNC
            else -> SYNC
        }
    }
}

/**
 * The per-request mode decision (owner request 2026-11-23, guide A1(a)):
 * the master kill switch wins FIRST (a disabled toggle forces OFF for every
 * combination — release builds keep the native default path), then demand
 * (no consumer of the published pixels on screen = OFF — the stats stage
 * costs nothing when nothing reads it), then the render class: PLAYBACK
 * renders read back asynchronously (the fence ring; a one-frame-late scope
 * is invisible at playback rate), everything else — SETTLE, DRAG — reads
 * synchronously so a paused edit's scopes match the frame on screen.
 *
 * Pure; the JVM suite locks the truth table. [toggle] is the debug
 * Settings row's persisted state (stores.previewStats) — the NATIVE
 * gPreviewStatsEnabled atomic remains the authoritative fold either way
 * (jni_bridge.cpp: effective mode = toggle ? statsMode : off).
 */
fun statsModeFor(
    scopeVisible: Boolean,
    renderClass: PreviewTiers.RenderClass,
    toggle: Boolean,
): PreviewStatsMode {
    if (!toggle) return PreviewStatsMode.OFF
    if (!scopeVisible) return PreviewStatsMode.OFF
    return if (renderClass == PreviewTiers.RenderClass.PLAYBACK) {
        PreviewStatsMode.ASYNC
    } else {
        PreviewStatsMode.SYNC
    }
}

/**
 * True when a published GPU frame is the ASYNC MISS marker — mode [ASYNC]
 * and a degenerate pack (both sides <= 1) — and the editor must therefore
 * KEEP the last published frame state (the scopes, the histogram, the RAW
 * fetch index) instead of overwriting it with the 1x1 placeholder
 * (guide A1(b).3: "the Kotlin side keeps the last scopes").
 *
 * This is the SAME predicate as [com.ritim.ritimcraw.ui.editor.PreviewSurfaceRatio.carriesShape]
 * (width > 0 && height > 0 && (width > 1 || height > 1), negated for the
 * degenerate case), inlined here because the engine package must not import
 * ui — the two must stay in sync by test (PreviewStatsModeTest).
 */
fun keepsLastPublishedFrame(mode: PreviewStatsMode, width: Int, height: Int): Boolean =
    mode == PreviewStatsMode.ASYNC && width <= 1 && height <= 1
