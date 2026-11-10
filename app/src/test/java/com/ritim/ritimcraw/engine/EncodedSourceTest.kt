package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * EncodedSource state-machine tests (sprint plan Task 6, the
 * device-independent half). The real MediaExtractor/MediaCodec adapters
 * cannot run in JVM tests (framework stubs throw), so everything runs against
 * fakes of the three boundaries. The fakes emulate exactly the MediaCodec
 * contract the pipeline relies on: output in PRESENTATION order, the newest
 * decoded frame held until the next sample or end of stream (the reorder
 * buffer — one frame of latency), and an extractor that opens at END of
 * stream after indexing.
 */
class EncodedSourceTest {

    // ---- open ----

    @Test
    fun open_reports_track_summary_and_passes_format_through() {
        val reader = FakeReader(samples(longArrayOf(0, 40000, 80000)))
        val decoder = FakeDecoder()
        val source = EncodedSource(reader, decoder, FakeDeveloper())

        val info = source.open(7)

        assertEquals("video/avc", info.mime)
        assertEquals(4, info.width)
        assertEquals(4, info.height)
        assertEquals(3, info.frameCount)
        assertEquals(90, info.orientationDeg)
        assertTrue(info.isBt709)
        assertEquals(48000, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(listOf(7), reader.opens) // fd ownership passed to the reader
        assertEquals(1, decoder.configured.size)
        assertSame(reader.formatForTest, decoder.configured[0]) // untouched, incl. codecConfig
    }

    @Test
    fun open_maps_presentation_order_from_container_order() {
        // Container (decode) order I0, P2, B1: presentation order is 0, 1, 2.
        val reader = FakeReader(
            samples(
                ptsUs = longArrayOf(0, 66666, 33333),
                syncAt = intArrayOf(0),
            )
        )
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())

        source.open(3)

        assertEquals(3, source.frameCount)
        assertEquals(0L, source.ptsUs(0))
        assertEquals(33333L, source.ptsUs(1))
        assertEquals(66666L, source.ptsUs(2))
    }

    @Test
    fun open_failure_no_video_track_releases_boundaries() {
        val reader = FakeReader(
            samples = emptyList(),
            openOutcome = EncodedOpenOutcome.Failed(EncodedErrors.OPEN_NO_VIDEO_TRACK),
        )
        val decoder = FakeDecoder()
        val source = EncodedSource(reader, decoder, FakeDeveloper())

        try {
            source.open(3)
            fail("open must fail without a video track")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: open): the clip has no video track — " +
                    "recovery: re-import the clip",
                e.message,
            )
        }
        assertTrue(reader.released)
        assertTrue(decoder.released)
    }

    @Test
    fun open_failure_unsupported_mime_message() {
        val reader = FakeReader(
            samples = emptyList(),
            openOutcome = EncodedOpenOutcome.Failed(
                EncodedErrors.OPEN_UNSUPPORTED_MIME, "mime video/mp4v-es"
            ),
        )
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())

        try {
            source.open(3)
            fail("open must fail on an unsupported mime")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: open): the video track uses an unsupported " +
                    "format — recovery: re-import the clip [mime video/mp4v-es]",
                e.message,
            )
        }
    }

    @Test
    fun open_failure_io_message() {
        val reader = FakeReader(
            samples = emptyList(),
            openOutcome = EncodedOpenOutcome.Failed(EncodedErrors.OPEN_IO, "setDataSource: boom"),
        )
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())

        try {
            source.open(3)
            fail("open must fail on an extractor io error")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: open): the clip could not be read — " +
                    "recovery: check the clip still exists, then re-import it " +
                    "[setDataSource: boom]",
                e.message,
            )
        }
    }

    @Test
    fun open_with_zero_frames_fails() {
        val reader = FakeReader(samples = emptyList()) // valid open, empty video track
        val decoder = FakeDecoder()
        val source = EncodedSource(reader, decoder, FakeDeveloper())

        try {
            source.open(3)
            fail("open must fail on an empty video track")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: open): the clip holds no video frames — " +
                    "recovery: re-import the clip",
                e.message,
            )
        }
        assertTrue(reader.released)
        assertTrue(decoder.released)
    }

    @Test
    fun configure_failure_names_decoder_and_releases() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder(configureError = "configure(video/avc): no codec")
        val source = EncodedSource(reader, decoder, FakeDeveloper())

        try {
            source.open(3)
            fail("open must fail when the decoder cannot start")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: configure): the video/avc decoder could not " +
                    "start — recovery: retry on a device with a video/avc decoder " +
                    "[configure(video/avc): no codec]",
                e.message,
            )
        }
        assertTrue(reader.released)
        assertTrue(decoder.released)
    }

    @Test
    fun open_twice_fails_without_releasing() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder()
        val developer = FakeDeveloper()
        val source = EncodedSource(reader, decoder, developer)
        source.open(3)

        try {
            source.open(4)
            fail("a second open must fail")
        } catch (e: IllegalStateException) {
            assertEquals("open can be called once; use a new EncodedSource", e.message)
        }
        // The failed second open must not tear down the still-open source.
        assertFalse(reader.released)
        assertFalse(decoder.released)
        assertSame(developer.result, source.renderFrame(0, GradeK()))
    }

    // ---- decode: frame ordering and seeking ----

    @Test
    fun sequential_renders_never_reseek() {
        val pts = longArrayOf(0, 40000, 80000, 120000)
        val reader = FakeReader(samples(pts))
        val decoder = FakeDecoder()
        val developer = FakeDeveloper()
        val source = EncodedSource(reader, decoder, developer)
        source.open(3)

        source.renderFrame(0, GradeK())
        source.renderFrame(1, GradeK())
        source.renderFrame(2, GradeK())

        // One seek for three sequential frames: the initial reposition (the
        // reader opens at end of stream); then the decode position continues
        // forward frame by frame.
        assertEquals(listOf(0L), reader.seeks)
        assertEquals(1, decoder.flushes)
        assertEquals(listOf(0L, 40000L, 80000L), decoder.emittedPts)
        assertEquals(3, developer.frames.size)
        for (i in 0 until 3) {
            assertEquals(marker(pts[i]).toByte(), developer.frames[i].y[0])
        }
    }

    @Test
    fun jump_seeks_to_keyframe_at_or_before() {
        // Keyframes at frames 0 and 3; frames 3..5 form the second GOP.
        val pts = LongArray(6) { it * 10000L }
        val reader = FakeReader(samples(pts, syncAt = intArrayOf(0, 3)))
        val decoder = FakeDecoder()
        val developer = FakeDeveloper()
        val source = EncodedSource(reader, decoder, developer)
        source.open(3)

        source.renderFrame(5, GradeK())
        assertEquals(listOf(30000L), reader.seeks) // keyframe at-or-before frame 5
        // Frames 3 and 4 are decoded on the way and discarded.
        assertEquals(listOf(30000L, 40000L, 50000L), decoder.emittedPts)
        assertEquals(marker(50000).toByte(), developer.frames[0].y[0])

        // A jump back onto the keyframe itself re-seeks (and works).
        source.renderFrame(3, GradeK())
        assertEquals(listOf(30000L, 30000L), reader.seeks)
        assertEquals(2, decoder.flushes)
        assertEquals(marker(30000).toByte(), developer.frames[1].y[0])
    }

    @Test
    fun render_after_end_of_stream_repositions() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder()
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        source.renderFrame(1, GradeK()) // drains through end of stream
        source.renderFrame(0, GradeK()) // must re-seek, not continue a dead stream

        assertEquals(listOf(0L, 0L), reader.seeks)
        assertEquals(2, decoder.flushes)
    }

    @Test
    fun bframe_container_order_renders_presentation_order() {
        // Decode order I0, P2, B1 (container); the decoder emits 0, 1, 2.
        val reader = FakeReader(
            samples(longArrayOf(0, 66666, 33333), syncAt = intArrayOf(0))
        )
        val decoder = FakeDecoder()
        val developer = FakeDeveloper()
        val source = EncodedSource(reader, decoder, developer)
        source.open(3)

        source.renderFrame(1, GradeK()) // the B-frame between the keyframe and P2

        assertEquals(listOf(0L, 33333L), decoder.emittedPts)
        assertEquals(marker(33333).toByte(), developer.frames[0].y[0])
    }

    // ---- decode: error paths ----

    @Test
    fun stale_output_after_flush_is_rejected() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder(staleAfterFlush = true)
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("a stale post-flush frame must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the decoder produced an unexpected " +
                    "frame — recovery: re-open the clip [decoder pts 12345]",
                e.message,
            )
        }
    }

    @Test
    fun decoder_pts_mismatch_is_rejected() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder(ptsOffset = 1000)
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("a decoder output off the index must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the decoder produced an unexpected " +
                    "frame — recovery: re-open the clip [decoder pts 1000]",
                e.message,
            )
        }
    }

    @Test
    fun clip_ending_before_target_fails() {
        val reader = FakeReader(samples(longArrayOf(0, 10000, 20000)))
        val decoder = FakeDecoder(maxOutputs = 2) // drops the last frame
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(2, GradeK())
            fail("a decoder that stops early must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the clip ended before frame 2 — " +
                    "recovery: re-open the clip",
                e.message,
            )
        }
    }

    @Test
    fun stuck_decoder_fails() {
        // More samples than the pipeline's slack: a decoder that never emits
        // must fail as stuck, not loop forever.
        val reader = FakeReader(samples(LongArray(40) { it * 1000L }))
        val decoder = FakeDecoder(maxOutputs = 0)
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("a decoder that never emits must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the decoder made no progress on " +
                    "frame 0 — recovery: re-open the clip",
                e.message,
            )
        }
    }

    @Test
    fun index_mismatch_fails() {
        val reader = FakeReader(
            samples(longArrayOf(0, 40000)),
            badFirstSamplePts = 999999, // the device seek landed off the index
        )
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("a sample off the index must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the clip index does not match the " +
                    "video track — recovery: re-open the clip [sample pts 999999]",
                e.message,
            )
        }
    }

    @Test
    fun seek_landing_after_target_fails() {
        val reader = FakeReader(
            samples(longArrayOf(0, 10000, 20000, 30000)),
            seekLandsAtLast = true, // the device seek overshot the target
        )
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(1, GradeK())
            fail("a seek past the target must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the decoder is positioned after " +
                    "frame 1 — recovery: re-open the clip [sample pts 30000]",
                e.message,
            )
        }
    }

    @Test
    fun develop_failure_maps_native_code_and_stage() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val developer = FakeDeveloper(
            result = null,
            errorCode = EncodedErrors.CODE_UNSUPPORTED,
            errorStage = EncodedErrors.STAGE_DEVELOP,
            errorDetail = "mi-log tail",
        )
        val source = EncodedSource(reader, FakeDecoder(), developer)
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("a failed develop must fail the render")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: develop): unsupported clip — " +
                    "recovery: re-import the clip [mi-log tail]",
                e.message,
            )
        }
    }

    @Test
    fun decoder_throw_wrapped_as_decode_failure() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val decoder = FakeDecoder(queueThrows = RuntimeException("codec died"))
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("an adapter crash must surface as a decode failure")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the decoder rejected a frame — " +
                    "recovery: re-open the clip [codec died]",
                e.message,
            )
        }
    }

    @Test
    fun reader_throw_wrapped_as_decode_failure() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)), readThrows = RuntimeException("io error"))
        val source = EncodedSource(reader, FakeDecoder(), FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(0, GradeK())
            fail("an adapter crash must surface as a decode failure")
        } catch (e: IllegalStateException) {
            assertEquals(
                "Encoded clip failed (stage: decode): the clip could not be read — " +
                    "recovery: re-open the clip [io error]",
                e.message,
            )
        }
    }

    // ---- render inputs and lifecycle ----

    @Test
    fun render_passes_grade_flags_and_orientation_to_developer() {
        val reader = FakeReader(samples(longArrayOf(0, 40000)))
        val developer = FakeDeveloper()
        val source = EncodedSource(reader, FakeDecoder(), developer)
        source.open(3)
        val grade = GradeK(exposureEv = 1.5f, tempK = 6500f, tint = 0.5f)

        val rendered = source.renderFrame(0, grade)

        assertSame(grade, developer.grades.single())
        assertEquals(listOf(true), developer.bt709Flags)
        assertEquals(listOf(90), developer.orientations)
        assertSame(developer.result, rendered)
        // The frame crossed into the developer as packed NV12.
        val frame = developer.frames.single()
        assertTrue(frame.semiPlanar)
        assertEquals(4, frame.stride)
        assertEquals(4, frame.uvStride) // 2 * cw = 2 * 2
        assertEquals(16, frame.y.size)
        assertTrue(frame.v.isEmpty())
    }

    @Test
    fun render_out_of_range_fails() {
        val source = EncodedSource(FakeReader(samples(longArrayOf(0, 40000))), FakeDecoder(), FakeDeveloper())
        source.open(3)

        try {
            source.renderFrame(99, GradeK())
            fail("an out-of-range frame index must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("frameIndex 99 outside 0..1", e.message)
        }
    }

    @Test
    fun render_before_open_fails() {
        val source = EncodedSource(FakeReader(samples(longArrayOf(0))), FakeDecoder(), FakeDeveloper())

        try {
            source.renderFrame(0, GradeK())
            fail("a render before open must fail")
        } catch (e: IllegalStateException) {
            assertEquals("open the clip first", e.message)
        }
    }

    @Test
    fun render_after_close_fails() {
        val source = EncodedSource(FakeReader(samples(longArrayOf(0))), FakeDecoder(), FakeDeveloper())
        source.open(3)
        source.close()

        try {
            source.renderFrame(0, GradeK())
            fail("a render after close must fail")
        } catch (e: IllegalStateException) {
            assertEquals("EncodedSource is closed", e.message)
        }
    }

    @Test
    fun close_releases_boundaries_and_is_idempotent() {
        val reader = FakeReader(samples(longArrayOf(0)))
        val decoder = FakeDecoder()
        val source = EncodedSource(reader, decoder, FakeDeveloper())
        source.open(3)

        source.close()
        source.close() // second close must be a no-op

        assertTrue(reader.released)
        assertTrue(decoder.released)
    }

    // ---- EncodedYuv.toNv12 ----

    @Test
    fun toNv12_packs_planes_with_strides() {
        // 2x2 frame with padded strides and non-zero offsets: the packed output
        // must address samples through rowStride/pixelStride, not [row*w+col].
        val p = YuvPlanes(
            ptsUs = 5,
            width = 2,
            height = 2,
            y = YuvPlane(byteArrayOf(10, 11, 99, 12, 13, 99), 0, 3, 1),
            u = YuvPlane(byteArrayOf(7, 21, 9), 1, 2, 2),
            v = YuvPlane(byteArrayOf(5, 8), 0, 1, 1),
        )

        val f = EncodedYuv.toNv12(p)

        assertEquals(2, f.width)
        assertEquals(2, f.height)
        assertTrue(f.semiPlanar)
        assertArrayEquals(byteArrayOf(10, 11, 12, 13), f.y)
        assertArrayEquals(byteArrayOf(21, 5), f.u) // U then V interleaved
        assertTrue(f.v.isEmpty())
        assertEquals(2, f.stride)
        assertEquals(2, f.uvStride)
    }

    @Test
    fun toNv12_handles_odd_dimensions() {
        // 3x3: the chroma grid rounds up to 2x2.
        val p = YuvPlanes(
            ptsUs = 0,
            width = 3,
            height = 3,
            y = YuvPlane(ByteArray(12) { it.toByte() }, 0, 4, 1), // rows at 0,4,8
            u = YuvPlane(ByteArray(8) { it.toByte() }, 0, 4, 2), // samples at r*4+2c
            v = YuvPlane(ByteArray(4) { it.toByte() }, 0, 2, 1), // samples at r*2+c
        )

        val f = EncodedYuv.toNv12(p)

        assertArrayEquals(byteArrayOf(0, 1, 2, 4, 5, 6, 8, 9, 10), f.y)
        // u[r][c] = r*4+2c, v[r][c] = r*2+c, interleaved row-major.
        assertArrayEquals(byteArrayOf(0, 0, 2, 1, 4, 2, 6, 3), f.u)
        assertEquals(3, f.stride)
        assertEquals(4, f.uvStride) // 2 * cw = 2 * 2
    }

    @Test
    fun toNv12_rejects_short_planes() {
        val shortY = YuvPlanes(
            ptsUs = 0, width = 2, height = 2,
            y = YuvPlane(ByteArray(4), 0, 3, 1), // needs 5 bytes
            u = YuvPlane(ByteArray(1), 0, 1, 1),
            v = YuvPlane(ByteArray(1), 0, 1, 1),
        )
        try {
            EncodedYuv.toNv12(shortY)
            fail("a short y plane must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("y plane too small for 2x2", e.message)
        }

        val shortU = YuvPlanes(
            ptsUs = 0, width = 2, height = 2,
            y = YuvPlane(ByteArray(4), 0, 2, 1),
            u = YuvPlane(ByteArray(0), 0, 1, 1), // needs 1 byte
            v = YuvPlane(ByteArray(1), 0, 1, 1),
        )
        try {
            EncodedYuv.toNv12(shortU)
            fail("a short u plane must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("u plane too small for 2x2", e.message)
        }
    }

    @Test
    fun toNv12_rejects_empty_frame() {
        val p = YuvPlanes(
            ptsUs = 0, width = 0, height = 2,
            y = YuvPlane(ByteArray(0), 0, 0, 1),
            u = YuvPlane(ByteArray(0), 0, 0, 1),
            v = YuvPlane(ByteArray(0), 0, 0, 1),
        )
        try {
            EncodedYuv.toNv12(p)
            fail("an empty frame must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("empty frame (0x2)", e.message)
        }
    }

    // ---- small contracts ----

    @Test
    fun sample_index_rejects_mismatched_arrays() {
        try {
            EncodedSampleIndex(LongArray(2), BooleanArray(1))
            fail("mismatched index arrays must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("pts/sync index mismatch", e.message)
        }
    }

    @Test
    fun native_developer_without_native_lib_fails_cleanly() {
        // No librawedit_jni.so in JVM tests: the develop boundary must fail
        // with the unavailable message, never UnsatisfiedLinkError.
        val frame = YuvFrameK(1, 1, true, byteArrayOf(0), byteArrayOf(0), ByteArray(0), 1, 1)
        try {
            NativeEncodedDeveloper.develop(frame, true, 0, GradeK())
            fail("develop must fail without the native library")
        } catch (e: IllegalStateException) {
            assertEquals(NativeSource.nativeUnavailableMessage, e.message)
        }
    }

    // ---- SourcePathDecision (the factory decision) ----

    @Test
    fun source_path_encoded_kind_is_authoritative() {
        assertEquals(SourcePath.ENCODED, SourcePathDecision.decide(SourceKind.ENCODED, null))
        assertEquals(SourcePath.ENCODED, SourcePathDecision.decide(SourceKind.ENCODED, "video/mp4"))
        assertEquals(SourcePath.ENCODED, SourcePathDecision.decide(SourceKind.ENCODED, "application/octet-stream"))
    }

    @Test
    fun source_path_mp4_mime_is_a_rescue_for_misclassified_clips() {
        assertEquals(SourcePath.ENCODED, SourcePathDecision.decide(SourceKind.MCRAW, "video/mp4"))
        // Trim + case insensitive.
        assertEquals(SourcePath.ENCODED, SourcePathDecision.decide(SourceKind.MCRAW, "  VIDEO/MP4  "))
    }

    @Test
    fun source_path_otherwise_mcraw() {
        assertEquals(SourcePath.MCRAW, SourcePathDecision.decide(SourceKind.MCRAW, null))
        assertEquals(SourcePath.MCRAW, SourcePathDecision.decide(SourceKind.MCRAW, ""))
        assertEquals(SourcePath.MCRAW, SourcePathDecision.decide(SourceKind.MCRAW, "video/x-matroska"))
        assertEquals(SourcePath.MCRAW, SourcePathDecision.decide(SourceKind.MCRAW, "image/dng"))
    }

    // ---- EncodedErrors message shapes ----

    @Test
    fun encoded_errors_message_shapes() {
        assertEquals("open", EncodedErrors.stageName(EncodedErrors.STAGE_OPEN))
        assertEquals("frame read", EncodedErrors.stageName(EncodedErrors.STAGE_FRAME_READ))
        assertEquals("develop", EncodedErrors.stageName(EncodedErrors.STAGE_DEVELOP))
        assertEquals("unknown", EncodedErrors.stageName(99))

        assertEquals(
            "Encoded clip failed (stage: frame read): truncated clip — recovery: re-import the clip",
            EncodedErrors.message(EncodedErrors.CODE_TRUNCATED, EncodedErrors.STAGE_FRAME_READ),
        )
        assertEquals(
            "Encoded clip failed (stage: open): not a supported clip — recovery: re-import the clip [hdr]",
            EncodedErrors.message(EncodedErrors.CODE_BAD_HEADER, EncodedErrors.STAGE_OPEN, "hdr"),
        )
        assertEquals(
            "Encoded clip failed (stage: develop): the frame could not be decoded — " +
                "recovery: re-open the clip",
            EncodedErrors.message(EncodedErrors.CODE_IO, EncodedErrors.STAGE_DEVELOP),
        )
        assertEquals(
            "Encoded clip failed (stage: develop): unknown error — recovery: re-open the clip",
            EncodedErrors.message(99, EncodedErrors.STAGE_DEVELOP),
        )
        // A blank detail never leaves a dangling bracket.
        assertEquals(
            "Encoded clip failed (stage: decode): cause — recovery: re-open the clip",
            EncodedErrors.kotlinMessage("decode", "cause", "re-open the clip", "  "),
        )
    }

    // ---- fakes ----

    /**
     * The MediaExtractor boundary. Emulates the reader contract: open walks
     * the whole track (the index) and leaves the reader at END of stream, so
     * the first readSample of any run must follow a seekTo; seekTo lands on
     * the sync sample at-or-before the requested pts (the pipeline always
     * seeks to an exact keyframe pts).
     */
    private class FakeReader(
        private val samples: List<EncodedSample>,
        private val format: EncodedVideoFormat = testFormat(),
        private val openOutcome: EncodedOpenOutcome? = null,
        private val sampleRate: Int = 48_000,
        private val channels: Int = 2,
        private val seekLandsAtLast: Boolean = false,
        private val badFirstSamplePts: Long? = null,
        private val readThrows: RuntimeException? = null,
    ) : EncodedTrackReader {

        val opens = ArrayList<Int>()
        val seeks = ArrayList<Long>()
        var released = false
        var formatForTest: EncodedVideoFormat = format
            private set
        private var cursor = -1 // -1 = not opened
        private var firstAfterSeek = false

        override fun open(fd: Int): EncodedOpenOutcome {
            opens.add(fd)
            formatForTest = format
            if (openOutcome != null) return openOutcome
            cursor = samples.size // end of stream after indexing (the contract)
            return EncodedOpenOutcome.Opened(
                format = format,
                index = EncodedSampleIndex(
                    ptsUs = LongArray(samples.size) { samples[it].ptsUs },
                    sync = BooleanArray(samples.size) { samples[it].sync },
                ),
                sampleRate = sampleRate,
                channels = channels,
            )
        }

        override fun seekTo(ptsUs: Long) {
            check(cursor >= 0) { "open the clip first" }
            seeks.add(ptsUs)
            cursor = if (seekLandsAtLast) {
                samples.size - 1
            } else {
                var best = -1
                for (i in samples.indices) {
                    val s = samples[i]
                    if (s.sync && s.ptsUs <= ptsUs && (best < 0 || s.ptsUs > samples[best].ptsUs)) {
                        best = i
                    }
                }
                if (best >= 0) best else 0
            }
            firstAfterSeek = true
        }

        override fun readSample(): EncodedSample? {
            readThrows?.let { throw it }
            if (cursor < 0 || cursor >= samples.size) return null
            val s = samples[cursor]
            cursor++
            if (firstAfterSeek) {
                firstAfterSeek = false
                if (badFirstSamplePts != null) {
                    return EncodedSample(s.bytes, badFirstSamplePts, s.sync)
                }
            }
            return s
        }

        override fun release() {
            released = true
        }
    }

    /**
     * The MediaCodec boundary. Emulates the decoder contract: output in
     * PRESENTATION order (min pts first) with the producing sample's pts,
     * holding the newest decoded frame until the next sample or end of stream
     * (the reorder buffer). Misbehavior knobs cover the pipeline's error
     * paths: [staleAfterFlush] (a frame left over after flush), [ptsOffset]
     * (output off the index), [maxOutputs] (a decoder that stops early or
     * never emits), [queueThrows] (a codec crash).
     */
    private class FakeDecoder(
        var configureError: String? = null,
        var maxOutputs: Int = Int.MAX_VALUE,
        private var staleAfterFlush: Boolean = false,
        private var ptsOffset: Long = 0,
        private var queueThrows: RuntimeException? = null,
    ) : EncodedFrameDecoder {

        val configured = ArrayList<EncodedVideoFormat>()
        val emittedPts = ArrayList<Long>()
        var flushes = 0
        var released = false
        private val pending = ArrayList<EncodedSample>()
        private var eos = false
        private var outputs = 0
        private var stalePending = false

        override fun configure(format: EncodedVideoFormat): String? {
            configured.add(format)
            return configureError
        }

        override fun flush() {
            pending.clear()
            eos = false
            flushes++
            stalePending = staleAfterFlush
        }

        override fun queueSample(sample: EncodedSample) {
            queueThrows?.let { throw it }
            pending.add(sample)
        }

        override fun queueEos() {
            eos = true
        }

        override fun dequeueFrame(timeoutUs: Long): YuvPlanes? {
            if (stalePending) {
                stalePending = false
                emittedPts.add(12345L)
                return planes(12345)
            }
            if (outputs >= maxOutputs || pending.isEmpty()) return null
            // Hold the max-pts frame (reorder buffer) unless draining EOS.
            var held = -1
            if (!eos) {
                held = 0
                for (i in pending.indices) {
                    if (pending[i].ptsUs > pending[held].ptsUs) held = i
                }
            }
            var pick = -1
            for (i in pending.indices) {
                if (i == held) continue
                if (pick < 0 || pending[i].ptsUs < pending[pick].ptsUs) pick = i
            }
            if (pick < 0) return null
            val s = pending.removeAt(pick)
            outputs++
            val pts = s.ptsUs + ptsOffset
            emittedPts.add(pts)
            return planes(pts)
        }

        override fun release() {
            released = true
        }

        /** A 4x4 frame whose plane bytes encode the pts (the developed frame is identifiable). */
        private fun planes(ptsUs: Long): YuvPlanes {
            val m = marker(ptsUs)
            return YuvPlanes(
                ptsUs = ptsUs,
                width = 4,
                height = 4,
                y = YuvPlane(ByteArray(16) { m.toByte() }, 0, 4, 1),
                u = YuvPlane(ByteArray(4) { ((m + 1) % 251).toByte() }, 0, 2, 1),
                v = YuvPlane(ByteArray(4) { ((m + 2) % 251).toByte() }, 0, 2, 1),
            )
        }
    }

    /** The native develop boundary, recording everything it was handed. */
    private class FakeDeveloper(
        var result: IntArray? = intArrayOf(4, 4, Int.MIN_VALUE),
        var errorCode: Int = 0,
        var errorStage: Int = 0,
        var errorDetail: String? = null,
    ) : EncodedDeveloper {

        val frames = ArrayList<YuvFrameK>()
        val grades = ArrayList<GradeK>()
        val bt709Flags = ArrayList<Boolean>()
        val orientations = ArrayList<Int>()

        override fun develop(
            frame: YuvFrameK,
            isBt709: Boolean,
            orientationDeg: Int,
            grade: GradeK,
        ): IntArray? {
            frames.add(frame)
            grades.add(grade)
            bt709Flags.add(isBt709)
            orientations.add(orientationDeg)
            return result
        }

        override fun code(): Int = errorCode

        override fun stage(): Int = errorStage

        override fun detail(): String? = errorDetail
    }
}

// ---- file-level helpers ----

/** The track format the fakes report (matches the fake decoder's 4x4 frames). */
private fun testFormat(): EncodedVideoFormat = EncodedVideoFormat(
    mime = "video/avc",
    width = 4,
    height = 4,
    orientationDeg = 90,
    isBt709 = true,
    codecConfig = listOf(byteArrayOf(1, 2), byteArrayOf(3)),
)

/** A per-frame marker: distinct for every pts a test uses, carried in the plane bytes. */
private fun marker(ptsUs: Long): Int = (ptsUs % 251).toInt()

/** Samples in container order, sync at the given indices (default: first only). */
private fun samples(
    ptsUs: LongArray,
    syncAt: IntArray = intArrayOf(0),
): List<EncodedSample> = ptsUs.mapIndexed { i, pts ->
    EncodedSample(ByteArray(4) { it.toByte() }, pts, i in syncAt)
}
