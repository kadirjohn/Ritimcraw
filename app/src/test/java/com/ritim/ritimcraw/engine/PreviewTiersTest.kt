package com.ritim.ritimcraw.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreviewTiers tests: the temporal NR tier policy (DN-08 graceful
 * degradation). The constants themselves are exercised by every render-path
 * test; this suite locks the policy decision — temporal NR applies at the
 * settle/paused tier and above, and is skipped at the interactive drag and
 * playback tiers.
 */
class PreviewTiersTest {

    // ---- temporalNrAppliesAt (DN-08 tier policy) ----

    @Test
    fun temporal_applies_at_paused_tier() {
        // The settle/paused render (1280) is the one that can afford the
        // full-res merge (~85-107 ms measured on the 4K fixture).
        assertTrue(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PAUSED_MAX_DIM))
    }

    @Test
    fun temporal_skipped_at_playback_tier() {
        // Playback (720) targets ~15 fps: the merge alone would eat the
        // whole 66 ms/frame budget. Playback shows the pristine center.
        assertFalse(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PLAYBACK_MAX_DIM))
    }

    @Test
    fun temporal_skipped_at_drag_tier() {
        // The slider-drag tier (480) renders per drag step: a full-res
        // merge per step would stall the finger. The settle render applies
        // the effect.
        assertFalse(PreviewTiers.temporalNrAppliesAt(SliderTiers.DRAG_MAX_DIM))
    }

    @Test
    fun temporal_applies_above_the_paused_tier() {
        // Any caller above the paused tier (e.g. a future full-res preview)
        // keeps temporal NR — the gate floors, it does not cap.
        assertTrue(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PAUSED_MAX_DIM + 1))
        assertTrue(PreviewTiers.temporalNrAppliesAt(3840))
    }
}
