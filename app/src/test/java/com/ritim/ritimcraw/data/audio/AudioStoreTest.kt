package com.ritim.ritimcraw.data.audio

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AudioStore tests (AUD-02/AUD-03 persistence): round-trip model ->
 * JSON -> model, the AUD-02 no-resurrection persistence invariant (a
 * REMOVED record survives save/load verbatim — an old project never
 * brings removed audio back), canonical-form rendering (ATTACHED
 * records are absent), six-section coexistence (an audio write does
 * not clobber grades/sources/exports/luts/timeline and vice versa),
 * unknown-field tolerance, corrupt-section fallback, and write
 * atomicity (a failed write leaves the old file intact).
 */
class AudioStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun projectFile(): File {
        val dir = tmp.newFolder("files")
        return File(dir, "project.json")
    }

    private fun sampleAudio(): AudioProject = AudioProject(
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

    @Test
    fun pan_mute_and_master_round_trip_and_omit_when_default() {
        // AUD-07 (TIMELINE_AUDIO_PLAN §6.3 item 4): pan/mute on the row
        // and the master state persist; defaults write no key so older
        // files stay byte-identical.
        val file = projectFile()
        val store = AudioStore(file)
        val p = AudioProject(
            clips = listOf(
                AudioClip(id = "a-1", sourceId = "s", sampleRateHz = 48000, pan = -0.25f, muted = true),
                AudioClip(id = "a-2", sourceId = "s", sampleRateHz = 48000),
            ),
            masterGainDb = -4.5f,
            masterMuted = true,
        )
        store.save(p)
        val back = AudioStore(file).load()
        assertEquals(p, back)
        val text = file.readText()
        assertTrue(text.contains("\"pan\":-0.25"))
        assertTrue(text.contains("\"muted\":true"))
        assertTrue(text.contains("\"master\":{\"gainDb\":-4.5,\"muted\":true}"))
        val a2 = text.substringAfter("\"id\":\"a-2\"").substringBefore('}')
        assertTrue(!a2.contains("pan") && !a2.contains("muted"))

        // Back to defaults -> no master key at all, no pan/muted keys.
        store.save(AudioProject(clips = listOf(AudioClip("a-1", "s", 48000))))
        val plain = file.readText()
        assertTrue(!plain.contains("master") && !plain.contains("pan") && !plain.contains("muted"))
    }

    @Test
    fun old_audio_rows_load_center_unmuted_and_bad_pan_clamps() {
        // A pre-field row (no pan/muted) loads as center/unmuted; a
        // hand-edited out-of-range pan clamps instead of dropping the row.
        val file = projectFile()
        file.writeText(
            "{\"version\":1,\"audio\":{\"version\":1,\"links\":[],\"clips\":[" +
                "{\"id\":\"old\",\"sourceId\":\"s\",\"sampleRateHz\":44100,\"gainDb\":-2.0}," +
                "{\"id\":\"wild\",\"sourceId\":\"s\",\"sampleRateHz\":44100,\"pan\":7.0}]}}"
        )
        val p = AudioStore(file).load()
        assertEquals(0f, p.clip("old")!!.pan, 0f)
        assertEquals(false, p.clip("old")!!.muted)
        assertEquals(-2f, p.clip("old")!!.gainDb, 0f)
        assertEquals(1f, p.clip("wild")!!.pan, 0f)
        assertEquals(0f, p.masterGainDb, 0f)
        assertEquals(false, p.masterMuted)
    }

    @Test
    fun round_trip_model_json_model_equality() {
        val file = projectFile()
        val original = sampleAudio()
        AudioStore(file).save(original)

        // Reload through a NEW store (simulating app close + reopen).
        assertEquals(original, AudioStore(file).load())
    }

    @Test
    fun removed_record_survives_reopen_the_no_resurrection_invariant() {
        // AUD-02's persistence half: "eski proje açıldığında kaldırılmış
        // sesin geri gelmesini önleyen açık durum kaydı" — the explicit
        // REMOVED record round-trips verbatim, so an old project NEVER
        // falls back to the absent-record ATTACHED default.
        val file = projectFile()
        val removed = AudioTrackOps.remove(AudioProject(), "src-b")
        AudioStore(file).save(removed)

        val reloaded = AudioStore(file).load()
        assertEquals(AudioLinkState.REMOVED, reloaded.link("src-b").state)
        // And it stays removed through a second save/load cycle.
        AudioStore(file).save(reloaded)
        assertEquals(AudioLinkState.REMOVED, AudioStore(file).load().link("src-b").state)
        // The whole machine holds after reopen: restore is still
        // refused on the persisted record.
        assertEquals(reloaded, AudioTrackOps.restore(reloaded, "src-b"))
    }

    @Test
    fun detached_and_linked_edited_records_survive_reopen() {
        val file = projectFile()
        AudioStore(file).save(sampleAudio())

        val reloaded = AudioStore(file).load()
        assertEquals(AudioLinkState.DETACHED, reloaded.link("src-a").state)
        assertEquals(AudioLinkState.LINKED_EDITED, reloaded.link("src-c").state)
        assertEquals("rec-9", reloaded.link("src-c").editedAudioId)
    }

    @Test
    fun attached_records_render_absent_canonical_form() {
        val file = projectFile()
        // An explicit ATTACHED record is the absent default: it does
        // not render, and it normalizes away on load.
        val explicit = AudioProject(
            links = mapOf("src-x" to AudioLinkRecord(state = AudioLinkState.ATTACHED)),
        )
        AudioStore(file).save(explicit)

        assertTrue("src-x" !in AudioStore(file).load().links)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(!text.contains("\"src-x\""))
        assertTrue(!text.contains("ATTACHED"))
        // And the absent default round-trips as empty.
        assertEquals(AudioProject(), AudioStore(file).load())
    }

    @Test
    fun unity_clip_fields_render_omitted() {
        val file = projectFile()
        AudioStore(file).save(AudioProject(clips = listOf(
            AudioClip(id = "a-1", sourceId = "s", sampleRateHz = 48000),
        )))
        val text = file.readText(Charsets.UTF_8)
        // gainDb/fadeInMs/fadeOutMs omitted at their defaults; the
        // REQUIRED sample rate always renders.
        assertTrue(!text.contains("gainDb"))
        assertTrue(!text.contains("fadeInMs"))
        assertTrue(!text.contains("fadeOutMs"))
        assertTrue(text.contains("\"sampleRateHz\":48000"))
    }

    @Test
    fun missing_section_loads_default_empty_project() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // A project.json written by the five earlier stores (no
        // "audio" section yet): the audio state loads as empty, and
        // the file keeps its sections.
        file.writeText(
            """{"version":1,"grades":{"c1":{"version":1}},"sources":[]}""",
            Charsets.UTF_8,
        )
        assertEquals(AudioProject(), AudioStore(file).load())
        assertTrue(file.readText(Charsets.UTF_8).contains("\"grades\""))
    }

    @Test
    fun audio_write_preserves_all_five_other_sections() {
        val file = projectFile()
        // Seed grades through its real store, then hand-write
        // sources/exports/luts/timeline sections alongside.
        GradeStore(file).put("clip-a", GradeK(exposureEv = 1.5f))
        val seeded = file.readText(Charsets.UTF_8).removeSuffix("}")
        file.writeText(
            seeded +
                ""","sources":[{"id":"s1","uri":"content://x","fingerprint":"f","kind":"MCRAW","access":"OK"}]""" +
                ""","exports":[{"id":"e1","sourceId":"s1"}]""" +
                ""","luts":[{"id":"l1","name":"Teal","fileName":"teal.cube"}]""" +
                ""","timeline":{"version":1,"tracks":[{"id":"v","kind":"VIDEO","index":0}],"clips":[],""" +
                """"output":{"width":1920,"height":1080,"fps":30}}""" +
                "}",
            Charsets.UTF_8,
        )

        AudioStore(file).save(sampleAudio())

        // All five other sections survive the audio write.
        val text = file.readText(Charsets.UTF_8)
        assertEquals(sampleAudio(), AudioStore(file).load())
        assertEquals(1.5f, GradeStore(file).get("clip-a").exposureEv, 0f)
        assertTrue(text.contains("\"sources\":[{\"id\":\"s1\""))
        assertTrue(text.contains("\"exports\":[{\"id\":\"e1\""))
        assertTrue(text.contains("\"luts\":[{\"id\":\"l1\""))
        assertTrue(text.contains("\"timeline\":{\"version\":1"))
        // And the section order is deterministic: audio renders last.
        assertTrue(text.indexOf("\"audio\":") > text.indexOf("\"timeline\":"))
    }

    @Test
    fun timeline_write_does_not_clobber_audio_and_vice_versa() {
        // The six-section coexistence core: the REAL timeline store's
        // write re-renders only "timeline" and keeps "audio" raw — the
        // audio state survives a timeline save untouched, and the
        // audio state's REMOVED record survives it too (the invariant).
        val file = projectFile()
        val audioStore = AudioStore(file)
        audioStore.save(AudioTrackOps.remove(AudioProject(), "src-b"))
        val afterAudioSave = file.readText(Charsets.UTF_8)
        assertTrue(afterAudioSave.contains("\"audio\":{\"version\":1"))

        // A REAL timeline store save over the same file.
        val timelineStore = com.ritim.ritimcraw.data.timeline.TimelineStore(file)
        timelineStore.save(
            com.ritim.ritimcraw.data.timeline.TimelineProject(
                tracks = listOf(
                    com.ritim.ritimcraw.data.timeline.TimelineTrack(
                        "v", com.ritim.ritimcraw.data.timeline.TrackKind.VIDEO, 0,
                    ),
                ),
                clips = listOf(
                    com.ritim.ritimcraw.data.timeline.TimelineClip(
                        id = "c-1",
                        trackId = "v",
                        sourceId = "src-b",
                        sourceRange = com.ritim.ritimcraw.data.timeline.FrameRange(0.0, 50.0),
                        timelineRange = com.ritim.ritimcraw.data.timeline.FrameRange(0.0, 50.0),
                    ),
                ),
            ),
        )

        // The audio section survived the timeline write.
        assertTrue(file.readText(Charsets.UTF_8).contains("\"audio\":{\"version\":1"))
        assertEquals(AudioLinkState.REMOVED, audioStore.load().link("src-b").state)
        // And the timeline round-trips too — both sections coexist.
        assertEquals(1, timelineStore.load().clips.size)
        // A subsequent AUDIO save keeps the timeline section as well.
        audioStore.save(AudioTrackOps.detach(audioStore.load(), "src-a"))
        assertTrue(file.readText(Charsets.UTF_8).contains("\"timeline\":"))
        assertEquals(1, timelineStore.load().clips.size)
        assertEquals(AudioLinkState.DETACHED, audioStore.load().link("src-a").state)
    }

    @Test
    fun unknown_fields_are_tolerated() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // A file written by a NEWER build: unknown keys inside the
        // audio section, a link row and a clip row must not break
        // parsing (forward tolerance — the migration stance).
        file.writeText(
            """
            {"version":1,"audio":{
              "version":2,
              "futureField":{"nested":true},
              "links":[{"sourceId":"s","state":"REMOVED","futureLinkField":9}],
              "clips":[{"id":"a-1","sourceId":"s","sampleRateHz":44100,
                        "gainDb":-3.0,"fadeInMs":50,"futureClipField":"x"}]}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = AudioStore(file).load()
        assertEquals(AudioLinkState.REMOVED, loaded.link("s").state)
        assertEquals(-3f, loaded.clip("a-1")!!.gainDb, 0f)
        assertEquals(50, loaded.clip("a-1")!!.fadeInMs)
        assertEquals(44100, loaded.clip("a-1")!!.sampleRateHz)
    }

    @Test
    fun absent_fields_fall_back_to_defaults() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // An older v1 file predating the optional fields: a clip row
        // with only the required keys.
        file.writeText(
            """
            {"version":1,"audio":{
              "version":1,
              "clips":[{"id":"a-1","sourceId":"s","sampleRateHz":48000}]}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val c = AudioStore(file).load().clip("a-1")!!
        assertEquals(0f, c.gainDb, 0f)
        assertEquals(0, c.fadeInMs)
        assertEquals(0, c.fadeOutMs)
    }

    @Test
    fun corrupt_section_loads_default_and_keeps_file() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        assertEquals(AudioProject(), AudioStore(file).load())
        // The corrupt raw file is untouched on disk.
        assertEquals("{ not json", file.readText(Charsets.UTF_8))
    }

    @Test
    fun bad_rows_are_skipped_not_fatal() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // One bad link row (unknown state) and one bad clip row
        // (non-positive rate) must not take the project down.
        file.writeText(
            """
            {"version":1,"audio":{
              "version":1,
              "links":[
                {"sourceId":"bad","state":"FUTURE_STATE"},
                {"sourceId":"good","state":"DETACHED"},
                {"sourceId":"bad2","state":"LINKED_EDITED"}
              ],
              "clips":[
                {"id":"bad","sourceId":"s","sampleRateHz":0},
                {"id":"good","sourceId":"s","sampleRateHz":44100}
              ]}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = AudioStore(file).load()
        assertEquals(AudioLinkState.DETACHED, loaded.link("good").state)
        assertEquals(AudioLinkState.ATTACHED, loaded.link("bad").state)
        assertEquals(AudioLinkState.ATTACHED, loaded.link("bad2").state)
        assertNotNull(loaded.clip("good"))
        assertEquals(null, loaded.clip("bad"))
    }

    @Test
    fun failed_write_leaves_old_file_intact() {
        val file = projectFile()
        val store = AudioStore(file)
        store.save(sampleAudio())
        val before = file.readText(Charsets.UTF_8)

        // Simulate a crash-mid-write-style failure: the directory
        // becomes unwritable, so the .tmp write (the first disk touch
        // of the atomic sequence) fails BEFORE any rename — the old
        // file must survive untouched. (Making the file itself
        // read-only would NOT block the rename: rename needs
        // directory write permission, not file write permission.)
        val dir = file.parentFile!!
        assertTrue(dir.setWritable(false))
        try {
            val failed = runCatching { store.save(AudioProject()) }
            assertTrue(failed.isFailure)
            assertEquals(before, file.readText(Charsets.UTF_8))
        } finally {
            dir.setWritable(true)
        }
        // The old audio state still loads.
        assertEquals(sampleAudio(), AudioStore(file).load())
    }

    @Test
    fun render_is_deterministic_across_saves() {
        val file = projectFile()
        val store = AudioStore(file)
        store.save(sampleAudio())
        val first = file.readText(Charsets.UTF_8)
        store.save(sampleAudio())
        assertEquals(first, file.readText(Charsets.UTF_8))
    }

    @Test
    fun parse_helper_rejects_trailing_content() {
        var threw = false
        try {
            AudioStore.parseAudio("""{"version":1} trailing""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun floats_round_trip_through_json_numbers() {
        // gainDb is a Float rendered as a JSON number and parsed back
        // as Double -> Float: the round trip must be exact for the
        // values the UI slider produces.
        val file = projectFile()
        val values = floatArrayOf(-6.5f, -12f, 0.1f, 3.75f, 24f)
        val clips = values.mapIndexed { i, g ->
            AudioClip(id = "a-$i", sourceId = "s", sampleRateHz = 44100, gainDb = g)
        }
        AudioStore(file).save(AudioProject(clips = clips))
        val reloaded = AudioStore(file).load()
        values.forEachIndexed { i, g ->
            assertEquals(g, reloaded.clip("a-$i")!!.gainDb, 0f)
        }
    }
}
