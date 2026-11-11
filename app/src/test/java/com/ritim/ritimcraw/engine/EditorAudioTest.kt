package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorAudio tests, all JVM (owner request 2026-11-11 section 4): the pure
 * audio path against fakes — [AudioSync] position math (AUD-06: the clip's
 * OWN sample rate, 44.1 kHz included), [PcmClipAudioSource] slicing, the
 * [AudioPlaybackCore] transitions (start re-prime, pause, stop, end of
 * audio, failed write/open, exactly-once release) and the
 * [EditorAudioPlayback] controller on its real audio thread. No Android
 * framework class is touched: [AudioTrackSink] and [MediaExtractorAudioSource]
 * are the device-gated adapters (the MediaCodecMp4Writer pattern) and are
 * exercised on device only.
 */
class EditorAudioTest {

    // ---- AudioSync (AUD-06: the rate is always the clip's own) ----

    @Test
    fun offset_maps_position_to_frames_at_the_clips_rate() {
        assertEquals(48_000L, AudioSync.frameOffsetAt(1_000_000, 48_000))
        assertEquals(24_000L, AudioSync.frameOffsetAt(500_000, 48_000))
    }

    @Test
    fun offset_uses_44_1_khz_when_the_clip_says_so() {
        // AUD-06: 48 kHz is never assumed — a 44.1 kHz clip addresses its
        // own samples (1s = 44_100 frames, NOT 48_000).
        assertEquals(44_100L, AudioSync.frameOffsetAt(1_000_000, 44_100))
        assertEquals(44_585L, AudioSync.frameOffsetAt(1_011_000, 44_100))
    }

    @Test
    fun offset_floors_so_audio_never_starts_ahead_of_video() {
        // 3_333 us at 48 kHz = 159.984 frames -> 159 (floor).
        assertEquals(159L, AudioSync.frameOffsetAt(3_333, 48_000))
        // 1_000 us at 44.1 kHz = 44.1 frames -> 44.
        assertEquals(44L, AudioSync.frameOffsetAt(1_000, 44_100))
    }

    @Test
    fun non_positive_rate_or_position_addresses_the_clip_start() {
        assertEquals(0L, AudioSync.frameOffsetAt(1_000_000, 0))
        assertEquals(0L, AudioSync.frameOffsetAt(1_000_000, -1))
        assertEquals(0L, AudioSync.frameOffsetAt(0, 48_000))
        assertEquals(0L, AudioSync.frameOffsetAt(-5, 48_000))
    }

    // ---- PcmClipAudioSource (the MCRAW one-shot PCM) ----

    @Test
    fun read_chunk_slices_interleaved_frames_in_order() {
        val source = PcmClipAudioSource(48_000, 2, shortArrayOf(0, 100, 1, 101, 2, 102, 3, 103))

        assertEquals(listOf<Short>(0, 100, 1, 101), source.readChunk(2)?.toList())
        assertEquals(listOf<Short>(2, 102, 3, 103), source.readChunk(2)?.toList())
        assertNull(source.readChunk(2))
    }

    @Test
    fun the_last_chunk_is_short_not_null() {
        val source = PcmClipAudioSource(48_000, 1, ShortArray(6) { it.toShort() })

        assertEquals(4, source.readChunk(4)?.size)
        assertEquals(listOf<Short>(4, 5), source.readChunk(4)?.toList())
        assertNull(source.readChunk(4))
    }

    @Test
    fun seek_clamps_into_the_clip_both_ways() {
        val source = PcmClipAudioSource(48_000, 1, ShortArray(10) { it.toShort() })

        source.seekTo(100) // beyond the end
        assertNull(source.readChunk(4))
        source.seekTo(-5) // before the start
        assertEquals(listOf<Short>(0, 1, 2, 3), source.readChunk(4)?.toList())
        source.seekTo(3)
        assertEquals(listOf<Short>(3, 4), source.readChunk(2)?.toList())
    }

    @Test
    fun read_after_release_is_null_and_release_is_idempotent() {
        val source = PcmClipAudioSource(48_000, 1, ShortArray(8))

        source.release()
        assertNull(source.readChunk(4))
        source.release() // exactly-once contract: a second call is harmless
    }

    // ---- AudioPlaybackCore (the transition state machine) ----

    @Test
    fun start_opens_once_and_primes_with_pause_flush_play() {
        val sink = FakeSink()
        val source = RecordingSource(PcmClipAudioSource(48_000, 2, stereoPcm(4_096)))
        val core = AudioPlaybackCore(sink, source)

        core.start(250_000)

        assertEquals(listOf("open(48000,2)", "pause", "flush", "play"), sink.calls)
        assertEquals(listOf(12_000L), source.seeks) // 250 ms at 48 kHz
        assertTrue(core.playing)

        // Pause + resume: the sink is NOT reopened; the same re-prime runs.
        core.pause()
        core.start(500_000)
        assertEquals(1, sink.calls.count { it.startsWith("open") })
        assertEquals(listOf(12_000L, 24_000L), source.seeks)
    }

    @Test
    fun start_addresses_the_clips_own_sample_rate_aud06() {
        val sink = FakeSink()
        val source = RecordingSource(PcmClipAudioSource(44_100, 2, stereoPcm(50_000)))
        val core = AudioPlaybackCore(sink, source)

        core.start(1_000_000)

        assertEquals("open(44100,2)", sink.calls.first())
        assertEquals(44_100L, source.seeks.single()) // NOT 48_000 (AUD-06)
    }

    @Test
    fun resume_and_seek_read_from_the_new_position_not_the_old_queue() {
        // AUD-08's first form: a re-start drops what was still queued for the
        // OLD position and reads from the NEW one — the first chunk after
        // the second start carries the new offset's frame tag.
        val sink = FakeSink()
        val source = RecordingSource(PcmClipAudioSource(48_000, 2, frameTaggedPcm(20_000)))
        val core = AudioPlaybackCore(sink, source)

        core.start(100_000) // 4_800 frames in
        assertTrue(core.pump())
        assertEquals(4_800, source.chunks.last()[0].toInt())

        core.start(200_000) // seek: flush + re-prime at 9_600
        assertTrue(sink.calls.count { it == "flush" } >= 2)
        assertTrue(core.pump())
        assertEquals(9_600, source.chunks.last()[0].toInt())
    }

    @Test
    fun pause_stops_output_and_the_pump_goes_idle() {
        val sink = FakeSink()
        val core = AudioPlaybackCore(sink, EndlessSource())

        core.start(0)
        assertTrue(core.pump())
        core.pause()

        assertFalse(core.playing)
        assertEquals("pause", sink.calls.last())
        assertFalse(core.pump()) // idle: no write after pause
        assertEquals("pause", sink.calls.last())
    }

    @Test
    fun stop_ends_the_clip_dropping_the_queued_tail() {
        val sink = FakeSink()
        val core = AudioPlaybackCore(sink, EndlessSource())

        core.start(0)
        core.pump()
        core.stop()

        assertFalse(core.playing)
        assertTrue(sink.calls.contains("flush"))
        assertEquals("flush", sink.calls.last())
    }

    @Test
    fun end_of_audio_degrades_to_silence() {
        val sink = FakeSink()
        val source = RecordingSource(PcmClipAudioSource(48_000, 2, stereoPcm(2_048)))
        val core = AudioPlaybackCore(sink, source)

        core.start(0)
        assertTrue(core.pump()) // 1_024 frames
        assertTrue(core.pump()) // 1_024 frames — exhausted
        assertFalse(core.pump()) // end of the clip's audio: silence, no crash

        assertFalse(core.playing)
        assertEquals("pause", sink.calls.last())
    }

    @Test
    fun a_failed_write_degrades_to_silence() {
        val sink = FakeSink().apply { writeResult = -1 }
        val core = AudioPlaybackCore(sink, EndlessSource())

        core.start(0)
        assertFalse(core.pump())

        assertFalse(core.playing)
        assertEquals("pause", sink.calls.last())
    }

    @Test
    fun a_sink_that_cannot_open_breaks_into_silence() {
        val sink = FakeSink().apply { openResult = false }
        val core = AudioPlaybackCore(sink, EndlessSource())

        core.start(0)

        assertFalse(core.playing)
        assertFalse(sink.calls.contains("play"))
        core.start(0) // broken: a later start is a silent no-op
        assertFalse(core.pump())
        assertEquals(1, sink.calls.count { it.startsWith("open") })
    }

    @Test
    fun release_is_exactly_once_and_gates_every_later_call() {
        val sink = FakeSink()
        val source = RecordingSource(PcmClipAudioSource(48_000, 2, stereoPcm(4_096)))
        val core = AudioPlaybackCore(sink, source)

        core.start(0)
        core.release()
        core.release()

        assertEquals(1, sink.calls.count { it == "release" })
        assertTrue(source.released)

        // After release nothing reaches the boundaries: start/pump/stop are
        // all silent no-ops (no write, no play, no second open).
        val callsAtRelease = sink.calls.toList()
        core.start(0)
        core.pump()
        core.stop()
        assertEquals(callsAtRelease, sink.calls)
        assertFalse(core.playing)
    }

    // ---- EditorAudioPlayback (the controller, real audio thread) ----

    @Test
    fun controller_starts_pauses_and_releases_on_the_audio_thread() = runBlocking {
        val sink = FakeSink()
        val source = EndlessSource()
        val controller = EditorAudioPlayback(newSink = { sink })

        controller.start(FakeAudioSession(source), fdProvider = { -1 }, positionUs = { 0L })
        awaitTrue { "play" in sink.calls }

        controller.pause()
        awaitTrue { sink.calls.last() == "pause" }
        assertFalse(coreBusy(sink)) // no write lands after the pause

        controller.release()
        awaitTrue { source.released && sink.calls.last() == "release" }
        // No write after release (the thread ended on the Release command).
        assertTrue(sink.calls.lastIndexOf("write") < sink.calls.indexOf("release"))
    }

    @Test
    fun controller_opens_the_clip_audio_once_across_play_pause_cycles() = runBlocking {
        val sink = FakeSink()
        val session = FakeAudioSession(EndlessSource())
        val controller = EditorAudioPlayback(newSink = { sink })

        controller.start(session, fdProvider = { -1 }, positionUs = { 0L })
        awaitTrue { "play" in sink.calls }
        controller.pause()
        awaitTrue { sink.calls.last() == "pause" }

        // Resume: the cached source is reused — openAudio runs ONCE.
        controller.start(session, fdProvider = { -1 }, positionUs = { 0L })
        awaitTrue { sink.calls.count { it == "play" } == 2 }

        controller.release()
        awaitTrue { sink.calls.last() == "release" }
        assertEquals(1, session.openAudioCalls)
    }

    @Test
    fun a_clip_without_audio_never_starts_the_audio_thread() = runBlocking {
        var sinkCreated = false
        val controller = EditorAudioPlayback(newSink = { sinkCreated = true; FakeSink() })

        controller.start(FakeAudioSession(null), fdProvider = { -1 }, positionUs = { 0L })
        Thread.sleep(250) // no command was ever enqueued: nothing to await

        assertFalse(sinkCreated)
        controller.release()
    }

    @Test
    fun release_before_any_start_is_silent_forever() = runBlocking {
        var sinkCreated = false
        val controller = EditorAudioPlayback(newSink = { sinkCreated = true; FakeSink() })

        controller.release()
        controller.start(
            FakeAudioSession(EndlessSource()),
            fdProvider = { -1 },
            positionUs = { 0L },
        )
        Thread.sleep(250) // the Release was handled and the thread ended

        assertFalse(sinkCreated)
    }

    // ---- fakes ----

    /**
     * Records every sink call in order; open/write results are steerable.
     * [calls] returns an immutable SNAPSHOT — the audio thread appends while
     * the test thread reads, so iteration must never touch the live list.
     */
    private class FakeSink : AudioSink {
        private val log = ArrayList<String>()
        val calls: List<String> get() = synchronized(log) { log.toList() }
        var openResult = true
        var writeResult = 1

        override fun open(sampleRate: Int, channels: Int): Boolean {
            synchronized(log) { log.add("open($sampleRate,$channels)") }
            return openResult
        }

        override fun write(pcm: ShortArray): Int {
            synchronized(log) { log.add("write(${pcm.size})") }
            return writeResult
        }

        override fun play() {
            synchronized(log) { log.add("play") }
        }

        override fun pause() {
            synchronized(log) { log.add("pause") }
        }

        override fun flush() {
            synchronized(log) { log.add("flush") }
        }

        override fun release() {
            synchronized(log) { log.add("release") }
        }
    }

    /** Never-ending silence: controller tests need a stream that outlives them. */
    private class EndlessSource : ClipAudioSource {
        override val sampleRate = 48_000
        override val channels = 2
        var released = false

        override fun seekTo(frameOffset: Long) {}

        override fun readChunk(maxFrames: Int): ShortArray? =
            if (released || maxFrames <= 0) null else ShortArray(maxFrames * channels)

        override fun release() {
            released = true
        }
    }

    /** Delegates to a real source, recording seeks, chunks and the release. */
    private class RecordingSource(private val inner: ClipAudioSource) : ClipAudioSource {
        val seeks = ArrayList<Long>()
        val chunks = ArrayList<ShortArray>()
        var released = false

        override val sampleRate: Int get() = inner.sampleRate
        override val channels: Int get() = inner.channels

        override fun seekTo(frameOffset: Long) {
            seeks.add(frameOffset)
            inner.seekTo(frameOffset)
        }

        override fun readChunk(maxFrames: Int): ShortArray? {
            if (released) return null
            val chunk = inner.readChunk(maxFrames) ?: return null
            chunks.add(chunk)
            return chunk
        }

        override fun release() {
            released = true
            inner.release()
        }
    }

    /** The seam fake: openAudio hands out the given source, the rest is unused. */
    private class FakeAudioSession(private val audio: ClipAudioSource?) : EditorPreviewSession {
        var openAudioCalls = 0
        override val frames: Int = 1

        override suspend fun render(
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
            lutPath: String?,
        ): Result<PreviewFrame> = Result.failure(IllegalStateException("unused"))

        override suspend fun renderCached(
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
            lutPath: String?,
        ): Result<PreviewFrame> = Result.failure(IllegalStateException("unused"))

        override suspend fun framePtsUs(index: Int): Long = 0L

        override suspend fun openAudio(fdProvider: () -> Int): ClipAudioSource? {
            openAudioCalls++
            return audio
        }

        override fun close() {}
    }

    // ---- helpers ----

    /**
     * Bounded wait for the audio thread to land the expected state (the
     * controller tests): polls every 10 ms, fails loudly after the timeout.
     */
    private fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("audio thread did not reach the expected state in ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    /** True while the core is still writing chunks (a pause may race one write). */
    private fun coreBusy(sink: FakeSink): Boolean {
        val lastWrite = sink.calls.lastIndexOf("write")
        val lastPause = sink.calls.lastIndexOf("pause")
        return lastWrite > lastPause
    }

    /** Stereo PCM where frame i carries [i, i] — a chunk's first short identifies its position. */
    private fun frameTaggedPcm(frames: Int): ShortArray =
        ShortArray(frames * 2) { (it / 2).toShort() }

    /** Plain stereo zero PCM of [frames] frames. */
    private fun stereoPcm(frames: Int): ShortArray = ShortArray(frames * 2)
}
