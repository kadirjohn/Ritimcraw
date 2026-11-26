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
}
