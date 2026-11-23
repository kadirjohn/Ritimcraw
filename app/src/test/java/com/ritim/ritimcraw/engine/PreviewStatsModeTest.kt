package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreviewStatsMode tests (owner request 2026-11-23, guide A1): the per-request
 * mode decision and the async-miss marker predicate, both pure, locked here
 * the way the RenderTrace line format is locked — the truth table is the
 * guide's A1(a)+A1(b) demand-vs-class fold, and native int mapping must
 * match StatsRing.h's StatsReadbackMode on the other side of the JNI seam.
 */
class PreviewStatsModeTest {

    // ---- the truth table: toggle x scopeVisible x renderClass ----

    @Test
    fun `toggle off forces OFF for every combination - the master kill switch wins first`() {
        for (scopeVisible in listOf(false, true)) {
            for (cls in PreviewTiers.RenderClass.entries) {
                assertEquals(
                    PreviewStatsMode.OFF,
                    statsModeFor(scopeVisible, cls, toggle = false),
                )
            }
        }
    }

    @Test
    fun `toggle on with no scope visible is OFF - no consumer, no cost`() {
        for (cls in PreviewTiers.RenderClass.entries) {
            assertEquals(
                PreviewStatsMode.OFF,
                statsModeFor(scopeVisible = false, renderClass = cls, toggle = true),
            )
        }
    }

    @Test
    fun `toggle on, scope visible, PLAYBACK is ASYNC - the fence ring`() {
        assertEquals(
            PreviewStatsMode.ASYNC,
            statsModeFor(scopeVisible = true, renderClass = PreviewTiers.RenderClass.PLAYBACK, toggle = true),
        )
    }

    @Test
    fun `toggle on, scope visible, SETTLE is SYNC - the paused edit is exact`() {
        assertEquals(
            PreviewStatsMode.SYNC,
            statsModeFor(scopeVisible = true, renderClass = PreviewTiers.RenderClass.SETTLE, toggle = true),
        )
    }

    @Test
    fun `toggle on, scope visible, DRAG is SYNC - drag renders stay exact`() {
        assertEquals(
            PreviewStatsMode.SYNC,
            statsModeFor(scopeVisible = true, renderClass = PreviewTiers.RenderClass.DRAG, toggle = true),
        )
    }

    // ---- keepsLastPublishedFrame: the async-miss marker ----

    @Test
    fun `ASYNC with a degenerate pack keeps the last published frame`() {
        // The 1x1 async miss: the pack for frame N-1 was not ready, the
        // native side published the honest 1x1 placeholder, and the editor
        // must keep the last scopes instead of overwriting them.
        assertTrue(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 1, 1))
        assertTrue(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 0, 1))
        assertTrue(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 1, 0))
        assertTrue(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 0, 0))
    }

    @Test
    fun `ASYNC with real dims is a real pack - publish it`() {
        assertFalse(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 256, 144))
        assertFalse(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 1, 2))
        assertFalse(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, 2, 1))
    }

    @Test
    fun `SYNC and OFF placeholder packs publish - only ASYNC keeps the last frame`() {
        assertFalse(keepsLastPublishedFrame(PreviewStatsMode.SYNC, 1, 1))
        assertFalse(keepsLastPublishedFrame(PreviewStatsMode.OFF, 1, 1))
        // OFF's 1x1 is the honest no-scopes placeholder: the editor's
        // carriesShape-tier consumers already no-op on it, but the scope
        // compute must see the state change (the scopes close), so it is
        // NOT a keep-last marker.
    }

    @Test
    fun `the predicate matches PreviewSurfaceRatio carriesShape negated`() {
        // The same predicate, inlined because engine must not import ui
        // (PreviewStatsMode's KDoc): a degenerate pair is BOTH sides <= 1.
        // Locks the sync-by-test rule between the two copies.
        val degeneratePairs = listOf(0 to 0, 1 to 1, 0 to 1, 1 to 0)
        val shapePairs = listOf(2 to 1, 1 to 2, 256 to 144, 1000 to 1000)
        for ((w, h) in degeneratePairs) {
            assertTrue(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, w, h))
        }
        for ((w, h) in shapePairs) {
            assertFalse(keepsLastPublishedFrame(PreviewStatsMode.ASYNC, w, h))
        }
    }

    // ---- the native int mapping (the JNI seam's vocabulary) ----

    @Test
    fun `the mode ints pin the StatsRing StatsReadbackMode values`() {
        assertEquals(0, PreviewStatsMode.OFF.native)
        assertEquals(1, PreviewStatsMode.SYNC.native)
        assertEquals(2, PreviewStatsMode.ASYNC.native)
    }

    @Test
    fun `fromNative maps 0-1-2 directly`() {
        assertEquals(PreviewStatsMode.OFF, PreviewStatsMode.fromNative(0))
        assertEquals(PreviewStatsMode.SYNC, PreviewStatsMode.fromNative(1))
        assertEquals(PreviewStatsMode.ASYNC, PreviewStatsMode.fromNative(2))
    }

    @Test
    fun `fromNative folds everything outside the legal range to SYNC`() {
        // Out-of-range jints fold to the safe default; an OLD .so's 0/1
        // boolean tail also degrades honestly (1 reads as the sync
        // readback it actually ran).
        assertEquals(PreviewStatsMode.SYNC, PreviewStatsMode.fromNative(-1))
        assertEquals(PreviewStatsMode.SYNC, PreviewStatsMode.fromNative(3))
        assertEquals(PreviewStatsMode.SYNC, PreviewStatsMode.fromNative(Int.MIN_VALUE))
        assertEquals(PreviewStatsMode.SYNC, PreviewStatsMode.fromNative(Int.MAX_VALUE))
    }

    @Test
    fun `fromNative is the inverse of native on the legal range`() {
        for (m in PreviewStatsMode.entries) {
            assertEquals(m, PreviewStatsMode.fromNative(m.native))
        }
    }
}
