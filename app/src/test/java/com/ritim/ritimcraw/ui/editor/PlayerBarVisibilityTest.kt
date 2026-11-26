package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.ui.editor.PlayerBarVisibility.TapResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBarVisibilityTest {

    private fun on(playing: Boolean = true, at: Long = 0): PlayerBarVisibility =
        PlayerBarVisibility().apply {
            setAutoHide(true, at)
            setPlaying(playing, at)
        }

    @Test
    fun setting_off_is_always_visible_and_taps_do_nothing() {
        val v = PlayerBarVisibility()
        v.setPlaying(true, 0)
        assertTrue(v.visible)
        assertNull(v.msUntilHide(0))
        assertFalse(v.tick(10_000))
        assertTrue(v.visible)
        assertEquals(TapResult.NONE, v.onVideoTap(10))
        assertEquals(TapResult.NONE, v.onVideoTap(20))
    }

    @Test
    fun playing_hides_after_delay() {
        val v = on()
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(0))
        assertFalse(v.tick(2999))
        assertTrue(v.visible)
        assertTrue(v.tick(3000))
        assertFalse(v.visible)
        assertNull(v.msUntilHide(3001))
    }

    @Test
    fun paused_hides_after_delay_too() {
        val v = on(playing = false)
        assertEquals(3000L, v.msUntilHide(0))
        assertFalse(v.tick(2999))
        assertTrue(v.tick(3000))
        assertFalse(v.visible)
    }

    @Test
    fun ended_or_stopped_hides_after_delay() {
        val v = on()
        v.setPlaying(false, 5000) // playback reached the end
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(5000))
        assertTrue(v.tick(8000))
        assertFalse(v.visible)
    }

    @Test
    fun pausing_after_hide_shows_bar_and_restarts_timer() {
        val v = on()
        v.tick(3000)
        assertFalse(v.visible)
        v.setPlaying(false, 3500)
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(3500))
    }

    @Test
    fun matrix_every_state_hides_shows_and_double_taps() {
        for (playing in listOf(true, false)) {
            val v = on(playing = playing)
            assertTrue(v.tick(3000))
            assertFalse(v.visible)
            // single tap shows, restarts, never toggles
            assertEquals(TapResult.NONE, v.onVideoTap(4000))
            assertTrue(v.visible)
            assertEquals(3000L, v.msUntilHide(4000))
            // quick second tap toggles playback
            assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(4100))
            // seek / bar touch restarts the timer
            v.onBarInteraction(5000)
            assertFalse(v.tick(7999))
            assertTrue(v.tick(8000))
        }
    }

    @Test
    fun setting_off_matrix_never_hides_in_any_state() {
        for (playing in listOf(true, false)) {
            val v = PlayerBarVisibility()
            v.setPlaying(playing, 0)
            v.onBarInteraction(10)
            assertNull(v.msUntilHide(10))
            assertFalse(v.tick(100_000))
            assertTrue(v.visible)
            assertEquals(TapResult.NONE, v.onVideoTap(200_000))
            assertEquals(TapResult.NONE, v.onVideoTap(200_100))
        }
    }

    @Test
    fun finger_held_on_bar_blocks_hide_and_release_restarts_timer() {
        val v = on(playing = false)
        v.setBarPressed(true, 1000)
        assertNull(v.msUntilHide(1000))
        assertFalse(v.tick(60_000))
        assertTrue(v.visible)
        v.setBarPressed(false, 61_000)
        assertEquals(3000L, v.msUntilHide(61_000))
        assertTrue(v.tick(64_000))
    }

    @Test
    fun tap_while_hidden_and_paused_shows_bar() {
        val v = on(playing = false)
        v.tick(3000)
        assertEquals(TapResult.NONE, v.onVideoTap(9000))
        assertTrue(v.visible)
    }

    @Test
    fun resuming_restarts_timer() {
        val v = on(playing = false)
        v.setPlaying(true, 10_000)
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(10_000))
    }

    @Test
    fun tap_while_hidden_shows_and_restarts_timer() {
        val v = on()
        v.tick(3000)
        assertEquals(TapResult.NONE, v.onVideoTap(5000))
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(5000))
        assertFalse(v.tick(7999))
        assertTrue(v.tick(8000))
    }

    @Test
    fun tap_while_visible_restarts_timer_and_never_pauses() {
        val v = on()
        assertEquals(TapResult.NONE, v.onVideoTap(2000))
        assertEquals(3000L, v.msUntilHide(2000))
        assertFalse(v.tick(4999))
        assertTrue(v.visible)
    }

    @Test
    fun bar_interaction_restarts_timer() {
        val v = on()
        v.onBarInteraction(2500)
        assertFalse(v.tick(5000))
        assertEquals(500L, v.msUntilHide(5000))
        assertTrue(v.tick(5500))
    }

    @Test
    fun double_tap_toggles_playback() {
        val v = on()
        assertEquals(TapResult.NONE, v.onVideoTap(1000))
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(1200))
        assertTrue(v.visible)
    }

    @Test
    fun slow_second_tap_is_not_a_double_tap() {
        val v = on()
        assertEquals(TapResult.NONE, v.onVideoTap(1000))
        assertEquals(TapResult.NONE, v.onVideoTap(1301))
    }

    @Test
    fun triple_tap_does_not_chain_toggles() {
        val v = on()
        v.onVideoTap(1000)
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(1100))
        assertEquals(TapResult.NONE, v.onVideoTap(1200))
    }

    @Test
    fun double_tap_works_while_paused() {
        val v = on(playing = false)
        v.onVideoTap(100)
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(250))
        assertTrue(v.visible)
    }

    @Test
    fun double_tap_that_starts_playback_shows_bar_briefly() {
        val v = on(playing = false)
        v.onVideoTap(100)
        v.onVideoTap(250)
        v.setPlaying(true, 250)
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(250))
    }

    @Test
    fun turning_setting_off_while_hidden_shows_bar() {
        val v = on()
        v.tick(3000)
        assertFalse(v.visible)
        v.setAutoHide(false, 3100)
        assertTrue(v.visible)
        assertNull(v.msUntilHide(3100))
    }

    @Test
    fun turning_setting_on_while_playing_starts_timer() {
        val v = PlayerBarVisibility()
        v.setPlaying(true, 0)
        v.setAutoHide(true, 1000)
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(1000))
    }

    @Test
    fun custom_delay_is_honoured() {
        val v = PlayerBarVisibility(hideDelayMs = 500)
        v.setAutoHide(true, 0)
        v.setPlaying(true, 0)
        assertTrue(v.tick(500))
    }

    // ---- C3 state-table fill: window boundaries, timer restarts, setting toggles ----

    @Test
    fun the_double_tap_window_closes_exactly_at_double_tap_ms() {
        // <= doubleTapMs IS a double tap: taps exactly 300 ms apart toggle.
        val v = on()
        assertEquals(TapResult.NONE, v.onVideoTap(1000))
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(1300)) // exactly the boundary
        // ... and one millisecond later is NOT
        val w = on()
        assertEquals(TapResult.NONE, w.onVideoTap(1000))
        assertEquals(TapResult.NONE, w.onVideoTap(1301))
        // a custom window is honoured at ITS boundary too
        val x = PlayerBarVisibility(doubleTapMs = 100)
        x.setAutoHide(true, 0); x.setPlaying(true, 0)
        assertEquals(TapResult.NONE, x.onVideoTap(0))
        assertEquals(TapResult.TOGGLE_PLAYBACK, x.onVideoTap(100))
    }

    @Test
    fun the_double_tap_window_reopens_after_a_toggle_and_after_a_setting_flip() {
        // A toggle CONSUMES the window (lastTapMs = null): the next tap starts a fresh
        // one — a fourth quick tap toggles again, it never chains a triple.
        val v = on()
        v.onVideoTap(1000)
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(1200))
        assertEquals(TapResult.NONE, v.onVideoTap(1250))
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(1450)) // a new window, a new pair
        // setAutoHide clears the window from BOTH sides (lastTapMs = null on flip)
        val w = on()
        w.onVideoTap(1000)
        w.setAutoHide(false, 1100)
        w.setAutoHide(true, 1150)
        assertEquals(TapResult.NONE, w.onVideoTap(1250)) // not a double tap: the window died with the flip
    }

    @Test
    fun turning_the_setting_on_while_paused_starts_the_timer_too() {
        // 0560028 "also when paused": the setting flip itself is an interaction — the bar
        // shows and the timer runs in EVERY playback state.
        val v = PlayerBarVisibility()
        v.setPlaying(false, 0)
        v.setAutoHide(true, 100)
        assertTrue(v.visible)
        assertEquals(3000L, v.msUntilHide(100))
        assertFalse(v.tick(3099))
        assertTrue(v.visible)
        assertTrue(v.tick(3100))
        assertFalse(v.visible)
    }

    @Test
    fun a_play_pause_flip_restarts_the_timer_from_the_flip_not_the_old_base() {
        // setPlaying at 2000 with a timer pending from 0: the new base is 2000.
        val v = on()
        assertEquals(3000L, v.msUntilHide(0))
        v.setPlaying(true, 2000)
        assertEquals(3000L, v.msUntilHide(2000))
        assertEquals(999L, v.msUntilHide(4001))
        assertFalse(v.tick(4999))
        assertTrue(v.tick(5000))
        assertFalse(v.visible)
        // and the paused flip mirrors it
        v.setPlaying(false, 6000)
        assertTrue(v.visible)
        assertTrue(v.tick(9000))
        assertFalse(v.visible)
    }

    @Test
    fun ms_until_hide_clamps_at_zero_and_reads_null_once_hidden() {
        val v = on()
        // overdue: clamped to 0, never negative
        assertEquals(0L, v.msUntilHide(10_000))
        assertTrue(v.tick(10_000))
        // hidden: no pending timer
        assertNull(v.msUntilHide(10_001))
        assertFalse(v.tick(20_000)) // and tick on a hidden bar changes nothing
        assertFalse(v.visible)
    }

    @Test
    fun a_finger_hold_across_the_due_time_defers_the_hide_indefinitely() {
        // Pressed BEFORE due, held long past it, released: the release restarts the FULL
        // delay, and the hide lands on the new base — never during the hold.
        val v = on()
        v.setBarPressed(true, 1000)
        assertFalse(v.tick(60_000))
        assertFalse(v.tick(600_000))
        assertTrue(v.visible)
        assertNull(v.msUntilHide(600_000)) // held: no pending timer at all
        v.setBarPressed(false, 600_000)
        assertEquals(3000L, v.msUntilHide(600_000))
        assertFalse(v.tick(602_999))
        assertTrue(v.tick(603_000))
        assertFalse(v.visible)
    }

    @Test
    fun a_video_tap_during_a_hold_shows_the_bar_and_the_hold_still_blocks_the_hide() {
        // A tap while the bar is pressed: show() runs, but barPressed still gates the
        // timer — the hide waits for the release.
        val v = on()
        v.setBarPressed(true, 100)
        assertEquals(TapResult.NONE, v.onVideoTap(200))
        assertTrue(v.visible)
        assertFalse(v.tick(5000))
        assertTrue(v.visible)
        v.setBarPressed(false, 5000)
        assertTrue(v.tick(8000))
        assertFalse(v.visible)
    }

    @Test
    fun tap_then_hold_then_two_more_taps_is_a_fresh_window_not_a_toggle() {
        // The full gesture chain: tap (window opens), hold on the bar (interaction, no
        // window change), then two quick taps — the FIRST tap after the hold starts a NEW
        // window (the 300 ms from IT), so the pair toggles on the second. The toggle
        // itself does NOT re-show (the caller's setPlaying does); the timer keeps the
        // LAST interaction base, so the hide lands on it, not on the tap.
        val v = on()
        assertEquals(TapResult.NONE, v.onVideoTap(1000))
        v.setBarPressed(true, 1100)
        v.setBarPressed(false, 4000) // 3000 ms later: the old window (1000+300) is long dead
        assertEquals(TapResult.NONE, v.onVideoTap(4100))
        assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(4300))
        assertTrue(v.visible) // shown by the taps themselves (and setBarPressed)
        assertEquals(2800L, v.msUntilHide(4300)) // base 4100, NOT the toggle's 4300
        assertTrue(v.tick(7100))
        assertFalse(v.visible)
    }

    @Test
    fun a_toggle_followed_by_playback_start_shows_and_restarts_in_both_states() {
        // The state matrix the screen drives: (toggle -> play) and (toggle -> pause)
        // both land visible with a fresh timer.
        for (playing in listOf(true, false)) {
            val v = on(playing = false)
            v.tick(3000) // hidden
            assertEquals(TapResult.NONE, v.onVideoTap(4000))
            assertEquals(TapResult.TOGGLE_PLAYBACK, v.onVideoTap(4100))
            v.setPlaying(playing, 4100)
            assertTrue(v.visible)
            assertEquals(3000L, v.msUntilHide(4100))
            assertFalse(v.tick(7099))
            assertTrue(v.tick(7100))
            assertFalse(v.visible)
        }
    }

    @Test
    fun setting_on_at_time_zero_and_off_at_time_zero_are_both_valid_first_calls() {
        // The screen's first composition can call either order at t=0: no negative
        // timers, no stuck hidden state.
        val a = PlayerBarVisibility()
        a.setPlaying(true, 0)
        a.setAutoHide(true, 0)
        assertTrue(a.visible)
        assertEquals(3000L, a.msUntilHide(0))
        val b = PlayerBarVisibility()
        b.setAutoHide(true, 0)
        b.setPlaying(true, 0)
        assertTrue(b.visible)
        assertEquals(3000L, b.msUntilHide(0))
        val c = PlayerBarVisibility()
        c.setAutoHide(false, 0)
        assertTrue(c.visible)
        assertNull(c.msUntilHide(0))
    }
}
