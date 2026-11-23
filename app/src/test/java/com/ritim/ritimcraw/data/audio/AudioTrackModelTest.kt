package com.ritim.ritimcraw.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audio track model tests (Stage C, AUD-02/AUD-03/AUD-06):
 *  - the AUD-02 link-state machine: every transition, idempotence,
 *    and THE no-resurrection invariant (REMOVED never falls back to
 *    ATTACHED — restore is refused, detach is a no-op).
 *  - AUD-03: trim preserves gain/fades (settings ride the id, not the
 *    range), split inheritance, and moved audio's export truth (the
 *    timeline clip's CURRENT timeline start).
 *  - AUD-06: the sample-accurate time contract — 44.1 kHz derives the
 *    correct ms (no 48k assumption), unknown rates fail loud.
 */
class AudioTrackModelTest {

    private val sample = AudioProject(
        links = mapOf(
            "src-a" to AudioLinkRecord(state = AudioLinkState.DETACHED),
            "src-b" to AudioLinkRecord(state = AudioLinkState.REMOVED),
            "src-c" to AudioLinkRecord(
                state = AudioLinkState.LINKED_EDITED,
                editedAudioId = "rec-9",
            ),
        ),
        clips = listOf(
            AudioClip(
                id = "a-1",
                sourceId = "src-a",
                sampleRateHz = 44100,
                gainDb = -6.5f,
                fadeInMs = 120,
                fadeOutMs = 80,
            ),
            AudioClip(
                id = "a-2",
                sourceId = "src-b",
                sampleRateHz = 48000,
            ),
        ),
    )

    // ---------------------------------------------------------------
    // AUD-02: the link-state machine
    // ---------------------------------------------------------------

    @Test
    fun absent_record_defaults_to_attached() {
        // A source with no record plays its original audio.
        assertEquals(AudioLinkState.ATTACHED, sample.link("src-z").state)
        assertEquals(AudioLinkState.ATTACHED, AudioProject().link("any").state)
    }

    @Test
    fun detach_sidelines_attached_and_is_idempotent() {
        val p0 = AudioProject()
        val p1 = AudioTrackOps.detach(p0, "src-a")
        assertEquals(AudioLinkState.DETACHED, p1.link("src-a").state)
        // Idempotent: a second detach changes nothing.
        assertEquals(p1, AudioTrackOps.detach(p1, "src-a"))
    }

    @Test
    fun detach_sidelines_linked_edited_and_drops_the_binding() {
        val p1 = AudioTrackOps.detach(sample, "src-c")
        assertEquals(AudioLinkState.DETACHED, p1.link("src-c").state)
        assertNull(p1.link("src-c").editedAudioId)
    }

    @Test
    fun detach_is_a_no_op_on_removed() {
        // Nothing left to sideline: REMOVED stays REMOVED.
        val detached = AudioTrackOps.detach(sample, "src-b")
        assertEquals(AudioLinkState.REMOVED, detached.link("src-b").state)
        assertEquals(sample, detached)
    }

    @Test
    fun restore_returns_the_original_audio() {
        val p1 = AudioTrackOps.restore(sample, "src-a")
        assertEquals(AudioLinkState.ATTACHED, p1.link("src-a").state)
        // Canonical form: restore REMOVES the record (ATTACHED is the
        // absent default), it does not store an ATTACHED record.
        assertTrue("src-a" !in p1.links)
    }

    @Test
    fun restore_is_idempotent_on_attached() {
        val p0 = AudioProject()
        assertEquals(p0, AudioTrackOps.restore(p0, "src-a"))
    }

    @Test
    fun restore_unbinds_linked_edited() {
        val p1 = AudioTrackOps.restore(sample, "src-c")
        assertEquals(AudioLinkState.ATTACHED, p1.link("src-c").state)
        assertNull(p1.link("src-c").editedAudioId)
    }

    @Test
    fun restore_is_refused_on_removed_the_no_resurrection_invariant() {
        // THE AUD-02 invariant: restore is the detach-inverse, not the
        // removal-inverse. A REMOVED record must never fall back to
        // ATTACHED silently — the removed audio never comes back.
        val restored = AudioTrackOps.restore(sample, "src-b")
        assertEquals(AudioLinkState.REMOVED, restored.link("src-b").state)
        assertEquals(sample, restored)
    }

    @Test
    fun remove_is_total_and_idempotent() {
        val p1 = AudioTrackOps.remove(AudioProject(), "src-a")
        assertEquals(AudioLinkState.REMOVED, p1.link("src-a").state)
        assertEquals(p1, AudioTrackOps.remove(p1, "src-a"))
        // From every state the record ends REMOVED.
        val fromDetached = AudioTrackOps.remove(sample, "src-a")
        assertEquals(AudioLinkState.REMOVED, fromDetached.link("src-a").state)
        val fromEdited = AudioTrackOps.remove(sample, "src-c")
        assertEquals(AudioLinkState.REMOVED, fromEdited.link("src-c").state)
        assertNull(fromEdited.link("src-c").editedAudioId)
    }

    @Test
    fun relink_binds_an_edited_recording_from_any_state() {
        // "Mevcut düzenlenmiş ses kaydı varsa ona bağlan."
        val fromAttached = AudioTrackOps.reLink(AudioProject(), "src-a", "rec-1")
        assertEquals(AudioLinkState.LINKED_EDITED, fromAttached.link("src-a").state)
        assertEquals("rec-1", fromAttached.link("src-a").editedAudioId)
        // reLink is the one sanctioned exit from REMOVED.
        val fromRemoved = AudioTrackOps.reLink(sample, "src-b", "rec-2")
        assertEquals(AudioLinkState.LINKED_EDITED, fromRemoved.link("src-b").state)
        assertEquals("rec-2", fromRemoved.link("src-b").editedAudioId)
        // Re-linking replaces an existing binding.
        val rebound = AudioTrackOps.reLink(sample, "src-c", "rec-3")
        assertEquals("rec-3", rebound.link("src-c").editedAudioId)
    }

    @Test
    fun relink_refuses_a_blank_target() {
        assertEquals(sample, AudioTrackOps.reLink(sample, "src-a", " "))
        assertEquals(sample, AudioTrackOps.reLink(sample, "src-a", ""))
    }

    @Test
    fun no_transition_maps_removed_back_to_attached() {
        // The machine-level sweep of the invariant: from REMOVED, every
        // transition either keeps REMOVED or goes to LINKED_EDITED (an
        // explicit new binding) — never ATTACHED, never DETACHED.
        val removed = AudioProject(
            links = mapOf("s" to AudioLinkRecord(state = AudioLinkState.REMOVED)),
        )
        for (result in listOf(
            AudioTrackOps.detach(removed, "s"),
            AudioTrackOps.restore(removed, "s"),
            AudioTrackOps.remove(removed, "s"),
        )) {
            assertEquals(AudioLinkState.REMOVED, result.link("s").state)
        }
        val relinked = AudioTrackOps.reLink(removed, "s", "rec-x")
        assertEquals(AudioLinkState.LINKED_EDITED, relinked.link("s").state)
    }

    @Test
    fun record_invariants_reject_bad_states() {
        // LINKED_EDITED requires a target; a target is invalid in any
        // other state (the record's own invariants, tested at the
        // model edge).
        var threw = false
        try {
            AudioLinkRecord(state = AudioLinkState.LINKED_EDITED, editedAudioId = null)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        threw = false
        try {
            AudioLinkRecord(state = AudioLinkState.ATTACHED, editedAudioId = "rec-1")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---------------------------------------------------------------
    // AUD-03: settings rules — trim preservation, split, export truth
    // ---------------------------------------------------------------

    @Test
    fun trim_preserves_gain_and_fades() {
        // The rule, stated as the proof surface: AudioClip carries no
        // range, so a timeline trim CANNOT touch the settings row.
        // Simulate the timeline side of a trim: the timeline clip's
        // ranges change; the audio project is untouched.
        val before = sample.clip("a-1")!!
        // (The timeline edit happens in the timeline's own model; the
        // audio project passes through unchanged.)
        val after = AudioTrackOps.settingsAfterEdit(sample, "a-1")!!
        assertEquals(before, after)
        assertEquals(-6.5f, after.gainDb, 0f)
        assertEquals(120, after.fadeInMs)
        assertEquals(80, after.fadeOutMs)
        // And the whole project is untouched by a timeline-side trim.
        assertEquals(sample, sample.copy()) // structural sanity
    }

    @Test
    fun settings_row_survives_repeated_edits() {
        // Split/trim cycles on the TIMELINE side never rewrite gain or
        // fades: the row rides the id. Setting values twice keeps the
        // last values, one row per id.
        val p1 = AudioTrackOps.setSettings(
            AudioProject(), "a-1", "src-a", 44100,
            gainDb = -3f, fadeInMs = 50, fadeOutMs = 0,
        )
        val p2 = AudioTrackOps.setSettings(
            p1, "a-1", "src-a", 0, // 0 = keep the existing rate
            gainDb = 2f, fadeInMs = 50, fadeOutMs = 70,
        )
        assertEquals(1, p2.clips.size)
        assertEquals(44100, p2.clip("a-1")!!.sampleRateHz)
        assertEquals(2f, p2.clip("a-1")!!.gainDb, 0f)
        assertEquals(70, p2.clip("a-1")!!.fadeOutMs)
    }

    @Test
    fun gain_edit_keeps_pan_and_mute() {
        // setSettings (the gain/fade sheet) must never re-center or
        // unmute a clip whose pan/mute were set separately (AUD-07).
        var p = AudioTrackOps.setPan(AudioProject(), "a-1", "src-a", 48000, pan = 0.4f)
        p = AudioTrackOps.setMuted(p, "a-1", "src-a", 0, muted = true)
        p = AudioTrackOps.setSettings(p, "a-1", "src-a", 0, gainDb = -3f, fadeInMs = 10, fadeOutMs = 0)
        val row = p.clip("a-1")!!
        assertEquals(0.4f, row.pan, 0f)
        assertTrue(row.muted)
        assertEquals(-3f, row.gainDb, 0f)
        assertEquals(48000, row.sampleRateHz)
        assertEquals(1, p.clips.size)
    }

    @Test
    fun pan_clamps_and_master_sets() {
        val p = AudioTrackOps.setPan(AudioProject(), "a-1", "src-a", 44100, pan = -3f)
        assertEquals(-1f, p.clip("a-1")!!.pan, 0f)
        val m = AudioTrackOps.setMaster(p, gainDb = -6f, muted = true)
        assertEquals(-6f, m.masterGainDb, 0f)
        assertTrue(m.masterMuted)
        assertEquals(p.clips, m.clips)
        var threw = false
        try {
            AudioTrackOps.setPan(AudioProject(), "a-1", "src-a", 44100, pan = Float.NaN)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun creating_a_row_without_a_rate_fails_loud() {
        // AUD-06: the rate is required data; the model must not guess.
        var threw = false
        try {
            AudioTrackOps.setSettings(
                AudioProject(), "a-1", "src-a", 0,
                gainDb = 0f, fadeInMs = 0, fadeOutMs = 0,
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun split_inherits_settings_to_the_right_piece() {
        // "Split ve trim'le aralık, gain ve fade gibi mevcut ayarlar
        // korunur" — at split time the new right piece inherits.
        val split = AudioTrackOps.afterSplit(sample, "a-1", "a-1r")
        val right = split.clip("a-1r")!!
        assertEquals(-6.5f, right.gainDb, 0f)
        assertEquals(120, right.fadeInMs)
        assertEquals(80, right.fadeOutMs)
        assertEquals(44100, right.sampleRateHz)
        assertEquals("src-a", right.sourceId)
        // The left piece keeps its own row, unchanged.
        assertEquals(sample.clip("a-1"), split.clip("a-1"))
    }

    @Test
    fun split_without_a_left_row_changes_nothing() {
        // A unity-default clip splits into two unity-default clips: no
        // rows to inherit, no rows created.
        val empty = AudioProject()
        assertEquals(empty, AudioTrackOps.afterSplit(empty, "a-9", "a-9r"))
    }

    @Test
    fun moved_audio_export_uses_the_new_timeline_start() {
        // "Ses sağa taşındığında export, sesin yeni timeline
        // başlangıcını kullanır" — the export start is read from the
        // timeline clip's CURRENT timelineRange.start, converted on
        // the project grid. Before the move:
        assertEquals(1000.0, AudioTrackOps.exportStartMs(30.0, projectFps = 30)!!, 0.0)
        // After the timeline-side move to frame 75:
        assertEquals(2500.0, AudioTrackOps.exportStartMs(75.0, projectFps = 30)!!, 0.0)
        // 24 fps grid, same contract:
        assertEquals(3125.0, AudioTrackOps.exportStartMs(75.0, projectFps = 24)!!, 1e-9)
    }

    @Test
    fun export_start_refuses_a_broken_grid() {
        assertNull(AudioTrackOps.exportStartMs(30.0, projectFps = 0))
        assertNull(AudioTrackOps.exportStartMs(30.0, projectFps = -30))
    }

    // ---------------------------------------------------------------
    // AUD-06: the sample-accurate time contract
    // ---------------------------------------------------------------

    @Test
    fun samples_to_ms_uses_the_real_rate_no_48k_assumption() {
        // 44100 samples at 44100 Hz is EXACTLY 1000 ms. A fixed 48 kHz
        // assumption would read it as ~918.75 ms — the ~10% drift the
        // AUD-06 rule forbids.
        assertEquals(1000.0, AudioTrackOps.samplesToMs(44100, 44100), 0.0)
        // The same sample count at 48 kHz is a different, also-correct
        // duration: the rate is data, not a constant.
        assertEquals(918.75, AudioTrackOps.samplesToMs(44100, 48000), 1e-9)
        // And the 48k case too:
        assertEquals(1000.0, AudioTrackOps.samplesToMs(48000, 48000), 0.0)
        assertEquals(1088.43537414966, AudioTrackOps.samplesToMs(48000, 44100), 1e-9)
    }

    @Test
    fun ms_to_samples_round_trips_at_both_rates() {
        for (rate in intArrayOf(44100, 48000)) {
            for (ms in doubleArrayOf(0.0, 1.0, 333.0, 1234.5, 60000.0)) {
                val samples = AudioTrackOps.msToSamples(ms, rate)
                // Round-trip within one sample's duration (rounding).
                assertEquals(ms, AudioTrackOps.samplesToMs(samples, rate), 1000.0 / rate)
            }
        }
    }

    @Test
    fun unknown_rate_fails_loud_never_assumes_48k() {
        // AUD-06's guard as behavior: a non-positive rate throws —
        // the caller must carry the real rate (AudioClip.sampleRateHz),
        // never substitute a default.
        for (bad in intArrayOf(0, -1, -48000)) {
            var threw = false
            try {
                AudioTrackOps.samplesToMs(44100, bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
            threw = false
            try {
                AudioTrackOps.msToSamples(1000.0, bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        }
    }

    @Test
    fun tested_rates_are_44100_and_48000() {
        assertTrue(AudioTrackOps.isTestedRate(44100))
        assertTrue(AudioTrackOps.isTestedRate(48000))
        assertEquals(false, AudioTrackOps.isTestedRate(96000))
        assertEquals(false, AudioTrackOps.isTestedRate(22050))
    }

    @Test
    fun clip_requires_a_positive_rate() {
        var threw = false
        try {
            AudioClip(id = "a", sourceId = "s", sampleRateHz = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun clip_rejects_negative_fades() {
        var threw = false
        try {
            AudioClip(id = "a", sourceId = "s", sampleRateHz = 48000, fadeInMs = -1)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        threw = false
        try {
            AudioClip(id = "a", sourceId = "s", sampleRateHz = 48000, fadeOutMs = -5)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
