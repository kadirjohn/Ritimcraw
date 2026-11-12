package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TimelineStore tests (TL-16): round-trip model -> JSON -> model,
 * ID persistence across reopen (TL-03's reopen half), unknown-field
 * tolerance (the migration stance), concurrent-section preservation
 * (a timeline write does not clobber grades/sources/exports/luts),
 * and write atomicity (a failed write leaves the old file intact).
 */
class TimelineStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun projectFile(): File {
        val dir = tmp.newFolder("files")
        return File(dir, "project.json")
    }

    private fun sampleProject(): TimelineProject = TimelineProject(
        output = TimelineOutput(
            width = 3840,
            height = 2160,
            fps = 24,
            orientationDeg = 90,
            colorSpace = "rec2020",
            transfer = "hlg",
            hdr = true,
        ),
        tracks = listOf(
            TimelineTrack("v", TrackKind.VIDEO, 0),
            TimelineTrack("a", TrackKind.AUDIO, 1),
            TimelineTrack("x", TrackKind.TEXT, 2),
        ),
        clips = listOf(
            TimelineClip(
                id = "c-1a",
                trackId = "v",
                sourceId = "src-abc",
                sourceRange = FrameRange(12.0, 102.0),
                timelineRange = FrameRange(0.0, 90.0),
                colorDefinition = ClipColorDefinition(encoding = "mi-log", colorSpace = "bt2020"),
                linkedAudioId = "a-2b",
            ),
            TimelineClip(
                id = "a-2b",
                trackId = "a",
                sourceId = "src-abc",
                sourceRange = FrameRange(12.0, 102.0),
                timelineRange = FrameRange(0.0, 90.0),
            ),
            TimelineClip(
                id = "x-3c",
                trackId = "x",
                sourceId = null,
                sourceRange = FrameRange(0.0, 40.0),
                timelineRange = FrameRange(10.0, 50.0),
                text = "Title card",
            ),
        ),
        projectEnd = 300.0,
        backgroundDuration = 150.0,
    )

    @Test
    fun round_trip_model_json_model_equality() {
        val file = projectFile()
        val original = sampleProject()
        TimelineStore(file).save(original)

        // Reload through a NEW store (simulating app close + reopen).
        val reloaded = TimelineStore(file).load()
        assertEquals(original, reloaded)
    }

    @Test
    fun ids_persist_verbatim_across_reopen() {
        val file = projectFile()
        val original = sampleProject()
        TimelineStore(file).save(original)

        val reloaded = TimelineStore(file).load()
        // TL-03's reopen half: the same ids, in the same clips.
        assertEquals(listOf("c-1a", "a-2b", "x-3c"), reloaded.clips.map { it.id })
        assertEquals(listOf("v", "a", "x"), reloaded.tracks.map { it.id })
        // The link survived by id, not position.
        assertEquals("a-2b", reloaded.clip("c-1a")!!.linkedAudioId)
    }

    @Test
    fun empty_project_round_trips() {
        val file = projectFile()
        val empty = TimelineStore.defaultProject()
        TimelineStore(file).save(empty)
        assertEquals(empty, TimelineStore(file).load())
    }

    @Test
    fun missing_section_loads_default_project() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // A project.json written by the four original stores (no
        // "timeline" section yet): the timeline loads as a fresh
        // default project, and the file keeps its sections.
        file.writeText(
            """{"version":1,"grades":{"c1":{"version":1}},"sources":[]}""",
            Charsets.UTF_8,
        )
        val loaded = TimelineStore(file).load()
        assertEquals(TimelineStore.defaultProject(), loaded)
    }

    @Test
    fun timeline_write_preserves_all_four_other_sections() {
        val file = projectFile()
        // Seed the file with grades (the other real stores follow the
        // same merge, one representative + the raw check below covers
        // all sections at once).
        GradeStore(file).put("clip-a", GradeK(exposureEv = 1.5f))
        // Hand-write sources/exports/luts sections alongside.
        val seeded = file.readText(Charsets.UTF_8)
            .removeSuffix("}")
            .replaceFirst("{", "{")
        file.writeText(
            seeded +
                ""","sources":[{"id":"s1","uri":"content://x","fingerprint":"f","kind":"MCRAW","access":"OK"}]""" +
                ""","exports":[{"id":"e1","sourceId":"s1"}]""" +
                ""","luts":[{"id":"l1","name":"Teal","fileName":"teal.cube"}]""" +
                "}",
            Charsets.UTF_8,
        )

        TimelineStore(file).save(sampleProject())

        // All four other sections survive the timeline write.
        val text = file.readText(Charsets.UTF_8)
        val reloaded = TimelineStore(file).load()
        assertEquals(sampleProject(), reloaded)
        assertEquals(1.5f, GradeStore(file).get("clip-a").exposureEv, 0f)
        assertTrue(text.contains("\"sources\":[{\"id\":\"s1\""))
        assertTrue(text.contains("\"exports\":[{\"id\":\"e1\""))
        assertTrue(text.contains("\"luts\":[{\"id\":\"l1\""))
        // And the section order is deterministic: timeline renders last.
        assertTrue(text.indexOf("\"timeline\":") > text.indexOf("\"luts\":"))
    }

    @Test
    fun unknown_fields_are_tolerated() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // A file written by a NEWER build: unknown keys inside the
        // timeline section and inside a clip must not break parsing
        // (forward tolerance — the migration stance).
        file.writeText(
            """
            {"version":1,"timeline":{
              "version":2,
              "futureField":{"nested":true},
              "tracks":[{"id":"v","kind":"VIDEO","index":0,"futureTrackField":9}],
              "clips":[{"id":"c1","trackId":"v","sourceId":"s","sourceStart":0.0,"sourceEnd":50.0,
                        "timelineStart":0.0,"timelineEnd":50.0,"futureClipField":"x"}],
              "output":{"width":1920,"height":1080,"fps":30,"futureOutputField":1},
              "projectEnd":120.0}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = TimelineStore(file).load()
        assertEquals(listOf("c1"), loaded.clips.map { it.id })
        assertEquals(50.0, loaded.clip("c1")!!.sourceRange.end, 0.0)
        assertEquals(120.0, loaded.projectEnd!!, 0.0)
    }

    @Test
    fun absent_fields_fall_back_to_defaults() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // An older v1 file predating the optional fields: output absent,
        // projectEnd/backgroundDuration absent, clip color fields absent.
        file.writeText(
            """
            {"version":1,"timeline":{
              "version":1,
              "tracks":[{"id":"v","kind":"VIDEO","index":0}],
              "clips":[{"id":"c1","trackId":"v","sourceId":"s","sourceStart":0.0,"sourceEnd":50.0,"timelineStart":0.0,"timelineEnd":50.0}],
              "output":{"width":1920,"height":1080,"fps":30}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = TimelineStore(file).load()
        val c = loaded.clip("c1")!!
        assertNull(c.colorDefinition.encoding)
        assertNull(c.linkedAudioId)
        assertEquals("", c.text)
        assertNull(loaded.projectEnd)
        assertNull(loaded.backgroundDuration)
        // Output falls back per-field: absent orientationDeg -> 0 etc.
        assertEquals(0, loaded.output.orientationDeg)
        assertEquals("rec709", loaded.output.colorSpace)
        assertEquals(false, loaded.output.hdr)
    }

    @Test
    fun corrupt_section_loads_default_and_keeps_file() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        val loaded = TimelineStore(file).load()
        assertEquals(TimelineStore.defaultProject(), loaded)
        // The corrupt raw file is untouched on disk.
        assertEquals("{ not json", file.readText(Charsets.UTF_8))
    }

    @Test
    fun failed_write_leaves_old_file_intact() {
        val file = projectFile()
        val store = TimelineStore(file)
        store.save(sampleProject())
        val before = file.readText(Charsets.UTF_8)

        // Simulate a crash-mid-write-style failure: the directory becomes
        // unwritable, so the .tmp write (the first disk touch of the
        // atomic sequence) fails BEFORE any rename — the old file must
        // survive untouched. (Making the file itself read-only would NOT
        // block the rename: rename needs directory write permission, not
        // file write permission.)
        val dir = file.parentFile!!
        assertTrue(dir.setWritable(false))
        try {
            val failed = runCatching { store.save(TimelineStore.defaultProject()) }
            assertTrue(failed.isFailure)
            assertEquals(before, file.readText(Charsets.UTF_8))
        } finally {
            dir.setWritable(true)
        }
        // The old project still loads.
        assertEquals(sampleProject(), TimelineStore(file).load())
    }

    @Test
    fun render_is_deterministic_across_saves() {
        val file = projectFile()
        val store = TimelineStore(file)
        store.save(sampleProject())
        val first = file.readText(Charsets.UTF_8)
        store.save(sampleProject())
        assertEquals(first, file.readText(Charsets.UTF_8))
    }

    @Test
    fun clip_with_mismatched_durations_is_skipped_not_fatal() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        // One corrupt row (source and timeline durations disagree) must
        // not take the whole project down: the row is skipped.
        file.writeText(
            """
            {"version":1,"timeline":{
              "version":1,
              "tracks":[{"id":"v","kind":"VIDEO","index":0}],
              "clips":[
                {"id":"bad","trackId":"v","sourceId":"s","sourceStart":0.0,"sourceEnd":50.0,"timelineStart":0.0,"timelineEnd":70.0},
                {"id":"good","trackId":"v","sourceId":"s","sourceStart":0.0,"sourceEnd":50.0,"timelineStart":0.0,"timelineEnd":50.0}
              ]}}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val loaded = TimelineStore(file).load()
        assertEquals(listOf("good"), loaded.clips.map { it.id })
    }

    @Test
    fun parse_helper_rejects_trailing_content() {
        var threw = false
        try {
            TimelineStore.parseTimeline("""{"version":1} trailing""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun mixed_project_color_definitions_round_trip() {
        val file = projectFile()
        val mixed = TimelineProject(
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip(
                    id = "raw", trackId = "v", sourceId = "m1",
                    sourceRange = FrameRange(0.0, 50.0),
                    timelineRange = FrameRange(0.0, 50.0),
                    colorDefinition = ClipColorDefinition(encoding = "mcraw-raw"),
                ),
                TimelineClip(
                    id = "milog", trackId = "v", sourceId = "m2",
                    sourceRange = FrameRange(0.0, 50.0),
                    timelineRange = FrameRange(50.0, 100.0),
                    colorDefinition = ClipColorDefinition(encoding = "mi-log", colorSpace = "bt2020"),
                ),
                TimelineClip(
                    id = "apv", trackId = "v", sourceId = "m3",
                    sourceRange = FrameRange(0.0, 50.0),
                    timelineRange = FrameRange(100.0, 150.0),
                    colorDefinition = ClipColorDefinition(encoding = "apv", transfer = "hlg"),
                ),
            ),
        )
        TimelineStore(file).save(mixed)
        val reloaded = TimelineStore(file).load()
        assertEquals(mixed, reloaded)
        assertEquals("mi-log", reloaded.clip("milog")!!.colorDefinition.encoding)
        assertEquals("hlg", reloaded.clip("apv")!!.colorDefinition.transfer)
        assertNotNull(reloaded.clip("raw"))
    }
}
