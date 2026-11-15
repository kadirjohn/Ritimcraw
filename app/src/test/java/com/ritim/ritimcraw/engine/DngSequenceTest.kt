package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure DNG-sequence model's tests (night research 2026-11-16, B4):
 * numeric ordering traps, gap/duplicate policy, the fps/pts grid's exact
 * rounding rule (23.976 and 29.97 — the drop-frame-adjacent rates), WAV
 * binding, and the manifest codec. Pure JVM tests — no device, no
 * native lib, no file IO.
 */
class DngSequenceTest {

    // ---- 1. the name rule ----

    @Test
    fun frame_number_parses_the_zero_padded_convention() {
        assertEquals(123L, DngSequence.frameNumberOf("clip_000123.dng"))
        assertEquals(1L, DngSequence.frameNumberOf("clip_1.dng"))
    }

    @Test
    fun frame_number_parses_the_frame_word_convention() {
        assertEquals(12L, DngSequence.frameNumberOf("frame0012.dng"))
        assertEquals(12L, DngSequence.frameNumberOf("frame_000012.dng"))
    }

    @Test
    fun frame_number_parses_the_plain_number_convention() {
        assertEquals(42L, DngSequence.frameNumberOf("IMG-0042.dng"))
        assertEquals(123L, DngSequence.frameNumberOf("000123.dng"))
        assertEquals(7L, DngSequence.frameNumberOf("7.dng"))
    }

    @Test
    fun frame_number_is_extension_agnostic() {
        // Uppercase extension, no extension at all, and a leading-dot
        // name (no extension by the GyroMatcher.baseName rule) all parse.
        assertEquals(1L, DngSequence.frameNumberOf("clip_1.DNG"))
        assertEquals(1L, DngSequence.frameNumberOf("clip_1"))
        assertNull(DngSequence.frameNumberOf(".dng"))
    }

    @Test
    fun frame_number_refuses_names_without_a_trailing_digit_run() {
        assertNull(DngSequence.frameNumberOf("sunset.dng"))
        // Digits that are not trailing do not make a slot.
        assertNull(DngSequence.frameNumberOf("2026_clip.dng"))
        assertNull(DngSequence.frameNumberOf(""))
    }

    @Test
    fun frame_number_refuses_a_run_that_overflows_long() {
        // Long.MAX_VALUE parses; one past it is unparsed, not crashed.
        assertEquals(Long.MAX_VALUE, DngSequence.frameNumberOf("9223372036854775807.dng"))
        assertNull(DngSequence.frameNumberOf("9223372036854775808.dng"))
    }

    @Test
    fun stem_strips_the_digit_run_and_separators() {
        assertEquals("clip", DngSequence.stemOf("clip_000123.dng"))
        assertEquals("IMG", DngSequence.stemOf("IMG-0042.dng"))
        assertEquals("frame", DngSequence.stemOf("frame0012.dng"))
        assertEquals("clip", DngSequence.stemOf("clip--0001.dng"))
        assertEquals("clip", DngSequence.stemOf("clip_000123"))
    }

    @Test
    fun stem_is_empty_when_the_name_carries_no_identity() {
        // Pure-digit names and slot-less names have nothing to bind.
        assertEquals("", DngSequence.stemOf("000123.dng"))
        assertEquals("", DngSequence.stemOf("sunset.dng"))
    }

    // ---- 2. ordering, unparsed, patterns, sequence shape ----

    @Test
    fun ordering_is_numeric_never_lexicographic() {
        // The trap: lexicographic order plays frame_10 BEFORE frame_2.
        val built = DngSequence.build(listOf("frame_10.dng", "frame_2.dng"))
        assertEquals(2, built.frameCount)
        assertEquals(2L, built.frames[0].frameNumber)
        assertEquals("frame_2.dng", built.frames[0].fileName)
        assertEquals(10L, built.frames[1].frameNumber)
        assertEquals("frame_10.dng", built.frames[1].fileName)
    }

    @Test
    fun ordering_ignores_padding_width() {
        // 1-digit and 6-digit pads of the same sequence sort by number.
        val built = DngSequence.build(
            listOf("clip_000010.dng", "clip_2.dng", "clip_000001.dng"),
        )
        assertEquals(
            listOf(1L, 2L, 10L),
            built.frames.map { it.frameNumber },
        )
    }

    @Test
    fun unparsed_names_are_reported_never_silently_skipped() {
        val built = DngSequence.build(
            listOf("clip_1.dng", "sunset.dng", "2026_clip.dng", "clip_2.dng"),
        )
        assertEquals(2, built.frameCount)
        assertEquals(listOf("sunset.dng", "2026_clip.dng"), built.unparsed)
    }

    @Test
    fun patterns_keep_separators_and_count_frames() {
        // "clip_" and "clip" are different conventions, honestly grouped.
        val built = DngSequence.build(
            listOf("clip_1.dng", "clip_2.dng", "clip-3.dng", "frame_4.dng"),
        )
        assertEquals(
            listOf(DngPattern("clip_", 2), DngPattern("clip-", 1), DngPattern("frame_", 1)),
            built.patterns,
        )
    }

    @Test
    fun sequence_shape_needs_two_frames_of_one_pattern() {
        // IN-09: distinguish a sequence from independent photos BY NAME.
        assertTrue(
            DngSequence.build(listOf("clip_1.dng", "clip_2.dng")).isSequenceShaped,
        )
        // One frame is not a sequence.
        assertTrue(!DngSequence.build(listOf("clip_1.dng")).isSequenceShaped)
        // Two conventions in one folder are not one sequence.
        assertTrue(
            !DngSequence.build(listOf("a_1.dng", "b_2.dng")).isSequenceShaped,
        )
        // A gappy sequence is still a sequence (the gap is reported).
        assertTrue(
            DngSequence.build(listOf("clip_1.dng", "clip_5.dng")).isSequenceShaped,
        )
        // Unparsed siblings do not break the shape (they are reported).
        assertTrue(
            DngSequence.build(listOf("clip_1.dng", "clip_2.dng", "sunset.dng")).isSequenceShaped,
        )
    }

    @Test
    fun empty_folder_builds_an_empty_sequence() {
        val built = DngSequence.build(emptyList())
        assertEquals(0, built.frameCount)
        assertNull(built.firstFrameNumber)
        assertNull(built.lastFrameNumber)
        assertTrue(built.gaps.isEmpty())
        assertTrue(!built.isSequenceShaped)
    }

    // ---- 3. the gap policy: reported, never a silent skip ----

    @Test
    fun gaps_are_reported_as_runs() {
        // Frames 1,2,5,9: missing runs 3..4 and 6..8.
        val built = DngSequence.build(
            listOf("clip_9.dng", "clip_1.dng", "clip_5.dng", "clip_2.dng"),
        )
        assertEquals(listOf(DngGap(3, 4), DngGap(6, 8)), built.gaps)
        assertEquals(5L, built.missingFrameCount)
        assertEquals(1L, built.firstFrameNumber)
        assertEquals(9L, built.lastFrameNumber)
    }

    @Test
    fun a_contiguous_run_has_no_gaps() {
        val built = DngSequence.build(
            (5L..9L).map { "clip_%04d.dng".format(it) },
        )
        assertTrue(built.gaps.isEmpty())
        assertEquals(0L, built.missingFrameCount)
    }

    @Test
    fun a_single_frame_or_empty_folder_has_no_gaps() {
        assertTrue(DngSequence.build(listOf("clip_42.dng")).gaps.isEmpty())
        assertTrue(DngSequence.build(emptyList()).gaps.isEmpty())
    }

    @Test
    fun a_gap_does_not_consume_timeline_time() {
        // The explicit CFR policy: frames PRESENT play back-to-back. With
        // 1,2,5,9 at 24 fps the third frame (slot 5) presents at index 2's
        // pts — never at slot 5's wall-clock position.
        val built = DngSequence.build(listOf("clip_1.dng", "clip_2.dng", "clip_5.dng", "clip_9.dng"))
        val tl = DngSequence.timeline(built, fps = 24.0)
        assertEquals(listOf(0L, 41_666L, 83_333L, 125_000L), tl.ptsUs)
    }

    // ---- 4. the duplicate policy: a hard error naming the clash ----

    @Test
    fun a_duplicate_slot_rejects_the_build() {
        try {
            DngSequence.build(listOf("clip_000012.dng", "clip_12.dng"))
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("ordering", e.stage)
            assertTrue(e.message!!.contains("duplicate frame slot 12 (2 files)"))
            assertTrue(e.message!!.contains("recovery: remove or rename"))
            assertTrue(e.message!!.contains("clip_000012.dng"))
            assertTrue(e.message!!.contains("clip_12.dng"))
            // Typed clash list: the caller's UI lists files without parsing text.
            assertEquals(1, e.duplicateSlots.size)
            assertEquals(12L, e.duplicateSlots[0].frameNumber)
            assertEquals(listOf("clip_000012.dng", "clip_12.dng"), e.duplicateSlots[0].fileNames)
        }
    }

    @Test
    fun duplicate_slots_keep_input_order_within_the_slot() {
        try {
            DngSequence.build(listOf("clip_12.dng", "clip_0005.dng", "clip_5.dng"))
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            // Slot 5 clashes (stable sort keeps input order); slot 12 is alone.
            assertEquals(1, e.duplicateSlots.size)
            assertEquals(listOf("clip_0005.dng", "clip_5.dng"), e.duplicateSlots[0].fileNames)
        }
    }

    @Test
    fun every_duplicate_slot_is_reported() {
        try {
            DngSequence.build(
                listOf("a_1.dng", "a_1.dng", "a_2.dng", "a_2.dng", "a_2.dng"),
            )
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals(2, e.duplicateSlots.size)
            assertEquals(1L, e.duplicateSlots[0].frameNumber)
            assertEquals(2, e.duplicateSlots[0].fileNames.size)
            assertEquals(2L, e.duplicateSlots[1].frameNumber)
            assertEquals(3, e.duplicateSlots[1].fileNames.size)
            assertTrue(e.message!!.contains("(+1 more duplicate slots)"))
        }
    }

    @Test
    fun a_long_clash_lists_three_names_then_overflows() {
        try {
            DngSequence.build(
                listOf("a_1.dng", "b_1.dng", "c_1.dng", "d_1.dng"),
            )
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertTrue(e.message!!.contains("a_1.dng, b_1.dng, c_1.dng, +1 more"))
        }
    }

    // ---- 5. the fps/pts grid ----

    @Test
    fun pts_at_24fps_lands_exactly_on_whole_seconds() {
        // The documented rounding rule: pts(i) = (i * 1e6 / fps).toLong().
        // Frame 24 is exactly 1,000,000 us — a uniform 41,666-us step grid
        // would have drifted to 999,984.
        val tl = DngSequence.timelineOf(frameCount = 25, fps = 24.0)
        assertEquals(0L, tl.ptsUs[0])
        assertEquals(41_666L, tl.ptsUs[1])
        assertEquals(500_000L, tl.ptsUs[12])
        assertEquals(1_000_000L, tl.ptsUs[24])
        assertEquals(24.0, tl.fps, 0.0)
    }

    @Test
    fun pts_at_23_976_uses_floor_of_the_quotient() {
        // 1e6/23.976 = 41,708.375 -> 41,708; frame 24 = 1,001,001.001 -> 1,001,001.
        val tl = DngSequence.timelineOf(frameCount = 25, fps = 23.976)
        assertEquals(41_708L, tl.ptsUs[1])
        assertEquals(83_416L, tl.ptsUs[2])
        assertEquals(1_001_001L, tl.ptsUs[24])
    }

    @Test
    fun pts_at_29_97_uses_floor_of_the_quotient() {
        // 1e6/29.97 = 33,366.700 -> 33,366; frame 30 = 1,001,001.001 -> 1,001,001.
        val tl = DngSequence.timelineOf(frameCount = 31, fps = 29.97)
        assertEquals(33_366L, tl.ptsUs[1])
        assertEquals(1_001_001L, tl.ptsUs[30])
    }

    @Test
    fun pts_at_the_exact_rational_rate_matches_the_rule() {
        // The drop-frame-adjacent rate written exactly (24000/1001): the
        // rule is the same floor-of-quotient — the grid equals the direct
        // expression and stays monotonic. The EXACT rational puts frame 24
        // at 1,001,000 us (24 * 1001 * 1e6 / 24000); the double-rounded
        // rate lands within 1 us of that. Note the two rate SPELLINGS
        // differ by ~2 us per second of timeline — which is why the
        // manifest records the rate it measured, never a re-rounded one.
        val fps = 24_000.0 / 1001.0
        val tl = DngSequence.timelineOf(frameCount = 25, fps = fps)
        for (i in 0 until 25) {
            assertEquals((i * 1_000_000.0 / fps).toLong(), tl.ptsUs[i])
        }
        for (i in 1 until 25) {
            assertTrue("pts must strictly increase", tl.ptsUs[i] > tl.ptsUs[i - 1])
        }
        assertTrue(Math.abs(tl.ptsUs[24] - 1_001_000L) <= 1L)
    }

    @Test
    fun pts_grid_is_strictly_increasing_at_drop_frame_rates() {
        for (fps in listOf(23.976, 29.97, 59.94)) {
            val tl = DngSequence.timelineOf(frameCount = 2400, fps = fps)
            for (i in 1 until tl.ptsUs.size) {
                assertTrue("fps=$fps not increasing at $i", tl.ptsUs[i] > tl.ptsUs[i - 1])
            }
        }
    }

    @Test
    fun pts_at_the_one_million_ceiling_advances_one_us_per_frame() {
        // MAX_FPS is exactly the rate whose interval is 1 us — the last
        // rate where the integer grid still strictly increases.
        val tl = DngSequence.timelineOf(frameCount = 10, fps = DngSequence.MAX_FPS)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), tl.ptsUs)
    }

    @Test
    fun duration_is_last_pts_minus_first() {
        // The PtsTimeline.totalDurationUs rule, called not forked.
        val tl = DngSequence.timelineOf(frameCount = 25, fps = 24.0)
        assertEquals(1_000_000L, tl.durationUs)
        assertEquals(0L, DngSequence.timelineOf(1, 24.0).durationUs)
        assertEquals(0L, DngSequence.timelineOf(0, 24.0).durationUs)
    }

    @Test
    fun pts_array_is_the_longarray_the_engine_functions_take() {
        val tl = DngSequence.timelineOf(frameCount = 3, fps = 30.0)
        // Element-wise: assertEquals on a LongArray would compare identity.
        org.junit.Assert.assertArrayEquals(longArrayOf(0L, 33_333L, 66_666L), tl.toPtsUsArray())
    }

    @Test
    fun a_negative_frame_count_is_a_programming_error() {
        try {
            DngSequence.timelineOf(frameCount = -1, fps = 24.0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(">= 0"))
        }
    }

    // ---- 6. the fps rejection rules ----

    @Test
    fun a_nan_fps_is_rejected_with_stage_timeline() {
        try {
            DngSequence.timelineOf(10, Double.NaN)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("timeline", e.stage)
            assertTrue(e.message!!.contains("not a usable rate"))
            assertTrue(e.message!!.contains("recovery:"))
        }
    }

    @Test
    fun an_infinite_fps_is_rejected() {
        for (fps in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            try {
                DngSequence.timelineOf(10, fps)
                org.junit.Assert.fail("expected DngSequenceException")
            } catch (e: DngSequenceException) {
                assertEquals("timeline", e.stage)
            }
        }
    }

    @Test
    fun a_zero_fps_is_rejected_never_assumed() {
        // The manifest's undeclared rate is 0.0 — importing without a
        // rate must fail honestly, not play at a guessed speed.
        try {
            DngSequence.timelineOf(10, 0.0)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("timeline", e.stage)
            assertTrue(e.message!!.contains("greater than zero"))
        }
    }

    @Test
    fun a_negative_fps_is_rejected() {
        try {
            DngSequence.timelineOf(10, -30.0)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("timeline", e.stage)
            assertTrue(e.message!!.contains("greater than zero"))
        }
    }

    @Test
    fun a_fps_above_the_ceiling_is_rejected() {
        // Above one frame per microsecond the integer pts grid cannot
        // strictly increase (two frames would share a microsecond).
        try {
            DngSequence.timelineOf(10, DngSequence.MAX_FPS + 0.5)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("timeline", e.stage)
            assertTrue(e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun a_directly_constructed_timeline_enforces_its_own_invariant() {
        try {
            DngFrameTimeline(fps = 0.0, ptsUs = listOf(0L))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fps must be finite"))
        }
    }

    // ---- 7. the wav binding: exact-or-nothing ----

    @Test
    fun one_same_stem_wav_binds() {
        assertEquals(
            WavBinding.One("clip.wav"),
            DngSequence.bindWav("clip", listOf("clip.wav", "other.wav", "x.dng")),
        )
    }

    @Test
    fun the_binding_is_case_insensitive_and_returns_the_name_as_given() {
        assertEquals(
            WavBinding.One("CLIP.WAV"),
            DngSequence.bindWav("clip", listOf("CLIP.WAV")),
        )
    }

    @Test
    fun zero_candidates_bind_nothing() {
        assertEquals(
            WavBinding.None,
            DngSequence.bindWav("clip", listOf("other.wav", "clip2.wav")),
        )
    }

    @Test
    fun two_spellings_of_one_stem_are_ambiguous_never_guessed() {
        assertEquals(
            WavBinding.Ambiguous(listOf("clip.wav", "Clip.WAV")),
            DngSequence.bindWav("clip", listOf("clip.wav", "Clip.WAV", "x.wav")),
        )
    }

    @Test
    fun non_wav_files_never_bind() {
        assertEquals(
            WavBinding.None,
            DngSequence.bindWav("clip", listOf("clip.mp4", "clip.dng", "clip.wav.txt")),
        )
    }

    @Test
    fun an_empty_stem_never_binds() {
        // Pure-digit frame names carry no bindable identity.
        assertEquals(WavBinding.None, DngSequence.bindWav("", listOf("000123.wav")))
    }

    @Test
    fun wav_stem_comes_from_the_single_pattern() {
        assertEquals(
            "clip",
            DngSequence.build(listOf("clip_0001.dng", "clip_0002.dng")).wavStem(),
        )
        assertEquals("frame", DngSequence.build(listOf("frame0012.dng")).wavStem())
        // No single pattern -> no stem -> the caller falls back to a manifest.
        assertNull(DngSequence.build(listOf("a_1.dng", "b_2.dng")).wavStem())
        assertNull(DngSequence.build(emptyList()).wavStem())
        // A pure-digit pattern yields an empty stem (never binds).
        assertEquals("", DngSequence.build(listOf("000123.dng")).wavStem())
    }

    @Test
    fun a_declared_wav_binds_by_exact_string() {
        // The manifest is machine-written next to the frames: the
        // recorded string IS the binding, case-sensitive.
        assertEquals(
            WavBinding.One("clip.wav"),
            DngSequence.bindDeclaredWav("clip.wav", listOf("clip.wav", "other.wav")),
        )
        assertEquals(
            WavBinding.None,
            DngSequence.bindDeclaredWav("clip.wav", listOf("Clip.WAV")),
        )
        assertEquals(
            WavBinding.None,
            DngSequence.bindDeclaredWav("clip.wav", listOf("other.wav")),
        )
        assertEquals(
            WavBinding.None,
            DngSequence.bindDeclaredWav("", listOf("clip.wav")),
        )
    }

    // ---- 8. the wav sanity: QA-12's shape ----

    @Test
    fun audio_longer_than_video_is_legal() {
        // QA-12: audio past the video's end plays to the end and trims.
        val s = DngSequence.checkWav(videoDurationUs = 1_000_000, wavDurationUs = 1_200_000, wavSampleRateHz = 48_000)
        assertTrue(s is WavSanity.Ok)
        assertEquals(1_200_000L, s.wavDurationUs)
    }

    @Test
    fun audio_equal_to_video_is_legal() {
        assertTrue(
            DngSequence.checkWav(1_000_000, 1_000_000, 48_000) is WavSanity.Ok,
        )
    }

    @Test
    fun audio_shorter_than_video_is_a_warning_with_the_shortfall() {
        val s = DngSequence.checkWav(1_000_000, 800_000, 48_000)
        assertTrue(s is WavSanity.AudioShorter)
        assertEquals(200_000L, (s as WavSanity.AudioShorter).shortByUs)
    }

    @Test
    fun a_zero_sample_rate_is_rejected_never_assumed() {
        try {
            DngSequence.checkWav(1_000_000, 1_000_000, 0)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("audio", e.stage)
            assertTrue(e.message!!.contains("sample rate 0"))
        }
    }

    @Test
    fun negative_durations_are_rejected() {
        try {
            DngSequence.checkWav(-1L, 1_000_000, 48_000)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("audio", e.stage)
        }
        try {
            DngSequence.checkWav(1_000_000, -5L, 48_000)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("audio", e.stage)
        }
    }

    @Test
    fun wav_duration_is_exact_integer_math_divide_last() {
        // The export PtsTimeline.audioBlockPts rule: multiply first,
        // divide once — no per-sample drift.
        assertEquals(100_000_000L, DngSequence.wavDurationUs(4_800_000, 48_000))
        assertEquals(10_000L, DngSequence.wavDurationUs(441, 44_100))
        // Truncation: one 44.1 kHz sample is 22.67 us of audio.
        assertEquals(22L, DngSequence.wavDurationUs(1, 44_100))
        assertEquals(0L, DngSequence.wavDurationUs(0, 48_000))
    }

    @Test
    fun wav_duration_rejects_unusable_inputs() {
        try {
            DngSequence.wavDurationUs(100, 0)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("audio", e.stage)
        }
        try {
            DngSequence.wavDurationUs(-1, 48_000)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("audio", e.stage)
        }
    }

    // ---- 9. the manifest codec ----

    @Test
    fun a_minimal_manifest_renders_as_version_only() {
        assertEquals("""{"version":1}""", DngSequence.renderManifest(DngSequenceManifest()))
    }

    @Test
    fun a_full_manifest_renders_every_declared_field_in_order() {
        assertEquals(
            """{"version":1,"stem":"clip","fps":23.976,"startFrame":1,"frameCount":240,"wav":"clip.wav","wavSampleRateHz":48000}""",
            DngSequence.renderManifest(
                DngSequenceManifest(
                    stem = "clip",
                    fps = 23.976,
                    startFrame = 1,
                    frameCount = 240,
                    wav = "clip.wav",
                    wavSampleRateHz = 48_000,
                ),
            ),
        )
    }

    @Test
    fun an_integral_fps_renders_without_a_trailing_zero() {
        assertEquals(
            """{"version":1,"fps":24}""",
            DngSequence.renderManifest(DngSequenceManifest(fps = 24.0)),
        )
    }

    @Test
    fun an_unusable_fps_renders_as_undeclared() {
        // NaN and infinity are omitted: "NaN" would not even be valid JSON,
        // and an unusable rate is as good as no rate.
        val text = DngSequence.renderManifest(DngSequenceManifest(fps = Double.NaN))
        assertTrue(!text.contains("fps"))
        val textInf = DngSequence.renderManifest(DngSequenceManifest(fps = Double.POSITIVE_INFINITY))
        assertTrue(!textInf.contains("fps"))
    }

    @Test
    fun a_full_manifest_round_trips() {
        val m = DngSequenceManifest(
            stem = "clip",
            fps = 23.976,
            startFrame = 1,
            frameCount = 240,
            wav = "clip.wav",
            wavSampleRateHz = 48_000,
        )
        assertEquals(m, DngSequence.parseManifest(DngSequence.renderManifest(m)))
    }

    @Test
    fun a_fractional_fps_round_trips_exactly() {
        // Double.toString is round-trip exact: even 24000/1001 survives.
        val m = DngSequenceManifest(fps = 24_000.0 / 1001.0)
        assertEquals(m.fps, DngSequence.parseManifest(DngSequence.renderManifest(m)).fps, 0.0)
    }

    @Test
    fun quoted_and_escaped_strings_round_trip() {
        val m = DngSequenceManifest(stem = "a\"b\\c", wav = "clip's.wav")
        assertEquals(m, DngSequence.parseManifest(DngSequence.renderManifest(m)))
    }

    @Test
    fun the_parse_is_forward_tolerant() {
        // Unknown fields (nested, arrays) are ignored; absent fields
        // fall back to undeclared defaults.
        assertEquals(
            DngSequenceManifest(fps = 30.0, stem = "x"),
            DngSequence.parseManifest("""{"version":1,"futureField":{"a":[1,2]},"fps":30.0,"stem":"x"}"""),
        )
        assertEquals(
            DngSequenceManifest(),
            DngSequence.parseManifest("""{"version":1}"""),
        )
    }

    @Test
    fun wrong_typed_fields_read_as_undeclared() {
        assertEquals(
            DngSequenceManifest(),
            DngSequence.parseManifest("""{"version":1,"fps":"fast","stem":7,"wav":false}"""),
        )
    }

    @Test
    fun an_unknown_future_version_still_parses() {
        val m = DngSequence.parseManifest("""{"version":99,"stem":"x","fps":24.0}""")
        assertEquals(99, m.version)
        assertEquals("x", m.stem)
        assertEquals(24.0, m.fps, 0.0)
    }

    @Test
    fun a_missing_version_reads_as_one() {
        assertEquals(1, DngSequence.parseManifest("""{"stem":"x"}""").version)
    }

    @Test
    fun unicode_escapes_parse() {
        assertEquals(
            "café.wav",
            DngSequence.parseManifest("""{"version":1,"wav":"café.wav"}""").wav,
        )
    }

    @Test
    fun corrupt_input_throws_and_the_caller_degrades() {
        // The store contract: corrupt JSON is IllegalArgumentException
        // (NOT a policy rejection) — the import degrades to manifest-less.
        for (bad in listOf(
                "not json",
                """{"version":1}x""",
                """[1]""",
                """{"stem":"unterminated""",
                """{"stem":"bad\q"}""",
                """{"stem"}""",
                """{"stem":"a" "b"}""",
            )) {
            try {
                DngSequence.parseManifest(bad)
                org.junit.Assert.fail("expected IllegalArgumentException for $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(true)
            }
        }
    }

    @Test
    fun the_manifest_declared_fps_drives_the_grid() {
        // End to end in the pure layer: parse a manifest, build the grid
        // from its declared rate, reject the undeclared one.
        val declared = DngSequence.parseManifest("""{"version":1,"fps":24.0,"frameCount":25}""")
        val tl = DngSequence.timelineOf(declared.frameCount.toInt(), declared.fps)
        assertEquals(1_000_000L, tl.ptsUs[24])
        val undeclared = DngSequence.parseManifest("""{"version":1}""")
        try {
            DngSequence.timelineOf(10, undeclared.fps)
            org.junit.Assert.fail("expected DngSequenceException")
        } catch (e: DngSequenceException) {
            assertEquals("timeline", e.stage)
        }
    }
}
