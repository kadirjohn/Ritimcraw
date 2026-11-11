package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-selected preview quality (owner request 2026-11-11b section 3):
 * each level's documented dims, and — the hard requirement — the DEFAULT
 * level reproducing TODAY'S fixed tier values exactly, so an editor opened
 * with no saved preference renders the identical requests it rendered
 * before the picker existed.
 */
class PreviewQualityTest {

    // ---- each level maps to the documented dims ----

    @Test
    fun low_maps_both_dims_to_480() {
        assertEquals(480, PreviewQuality.LOW.playbackMaxDim)
        assertEquals(480, PreviewQuality.LOW.scrubMaxDim)
    }

    @Test
    fun balanced_maps_playback_720_scrub_480() {
        assertEquals(720, PreviewQuality.BALANCED.playbackMaxDim)
        assertEquals(480, PreviewQuality.BALANCED.scrubMaxDim)
    }

    @Test
    fun high_maps_playback_1280_scrub_720() {
        assertEquals(1280, PreviewQuality.HIGH.playbackMaxDim)
        assertEquals(720, PreviewQuality.HIGH.scrubMaxDim)
    }

    // ---- DEFAULT is bit-identical to today's fixed tiers ----

    @Test
    fun default_is_balanced() {
        assertEquals(PreviewQuality.BALANCED, PreviewQuality.DEFAULT)
    }

    @Test
    fun default_pins_todays_exact_tier_values() {
        // The hard requirement: the default level's dims ARE today's
        // constants, by reference — not restated numbers that could drift.
        assertEquals(
            PreviewTiers.PLAYBACK_MAX_DIM,
            PreviewQuality.DEFAULT.playbackMaxDim,
        )
        assertEquals(
            SliderTiers.DRAG_MAX_DIM,
            PreviewQuality.DEFAULT.scrubMaxDim,
        )
        // And today's values, pinned so a constant change is a conscious
        // one reviewed against this test.
        assertEquals(720, PreviewQuality.DEFAULT.playbackMaxDim)
        assertEquals(480, PreviewQuality.DEFAULT.scrubMaxDim)
    }

    @Test
    fun every_level_scrub_not_above_playback() {
        // Scrub stays at or below playback: the finger must never be
        // answered SLOWER than the wall clock.
        for (q in PreviewQuality.entries) {
            assertTrue(q.scrubMaxDim <= q.playbackMaxDim)
        }
    }

    // ---- what no level touches: pause/clip-end settle semantics ----

    @Test
    fun no_level_changes_the_paused_and_source_tiers() {
        // The picker owns ONLY playback + scrub. The paused tier (1280,
        // every paused render) and the source tier (0 = no cap, the
        // pause/clip-end progressive settle) are fixed constants outside
        // the enum — pinned here so wiring them in later is a conscious
        // change, not drift.
        for (q in PreviewQuality.entries) {
            assertTrue(q.playbackMaxDim != PreviewTiers.PAUSED_MAX_DIM || q.scrubMaxDim != PreviewTiers.PAUSED_MAX_DIM)
        }
        assertEquals(1280, PreviewTiers.PAUSED_MAX_DIM)
        assertEquals(0, PreviewTiers.SOURCE_MAX_DIM)
        assertEquals(1280, SliderTiers.SETTLE_MAX_DIM)
    }

    // ---- wire parsing: unknown/missing -> default ----

    @Test
    fun from_wire_round_trips_every_level() {
        for (q in PreviewQuality.entries) {
            assertEquals(q, PreviewQuality.fromWire(q.name))
        }
    }

    @Test
    fun from_wire_null_or_blank_resolves_to_default() {
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire(null))
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire(""))
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire("   "))
    }

    @Test
    fun from_wire_unknown_value_resolves_to_default() {
        // A value written by a future build this one does not know: the
        // editor still opens and renders at the default, never crashes.
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire("ULTRA"))
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire("balanced "))
        assertEquals(PreviewQuality.DEFAULT, PreviewQuality.fromWire("720"))
    }
}
