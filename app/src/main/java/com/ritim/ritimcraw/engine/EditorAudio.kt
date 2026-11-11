package com.ritim.ritimcraw.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Editor preview audio (owner request 2026-11-11 section 4: "Preview'da
 * ses"). The clip's audio plays during editor playback; pause stops it, a
 * seek drops the old samples and primes the new position (AUD-08's first
 * form), the clip end ends it, and a clip without audio plays silent — the
 * previous behavior. Export is untouched (this file shares nothing with the
 * export pipeline).
 *
 * ## Sync model (v1)
 *
 * Video stays the master: the playback loop keeps picking frames from the
 * wall clock exactly as before (drop-late pacing via PtsTimeline — no
 * backlog, ever). Audio is a follower with its own clock and never gates
 * the video loop:
 *
 *  - At play start the audio opens in PARALLEL with the video loop; the
 *    position it starts from is evaluated when the audio is actually ready
 *    (the position provider runs on the audio thread at start handling),
 *    so a slow audio open does not desynchronize the start.
 *  - From there the AudioTrack's device clock paces the samples (blocking
 *    writes). There is no continuous re-sync in v1: small drift between
 *    the two clocks is acceptable (owner request: "ses ayrı saat sürtsün,
 *    video ona kilitlenmesin").
 *  - Pause stops the sink at the device position. Resume re-primes at the
 *    pause frame's pts — frame-granular, at most one frame's worth of audio
 *    (~33 ms) repeats or skips.
 *  - Seek: the transport pauses playback on a seek (existing behavior), so
 *    the next play start flushes the sink — old queued samples are dropped
 *    — and primes the new position from scratch.
 *  - Clip end stops and flushes the audio tail with it.
 *
 * ## AUD-06: the sample rate comes from the clip
 *
 * Every offset is computed from the clip's own audioInfo() / audio track
 * format (48 kHz is NOT assumed — 44.1 kHz clips address their samples
 * correctly; see [AudioSync.frameOffsetAt] and its tests).
 *
 * ## Layout
 *
 * Pure, JVM-tested: [AudioSink] (the sink seam), [ClipAudioSource] (the
 * clip-audio seam), [AudioSync], [PcmClipAudioSource] (the MCRAW one-shot
 * PCM), [AudioPlaybackCore] (the transition state machine) and
 * [EditorAudioPlayback] (the screen-facing controller). Device-gated thin
 * adapters (the MediaCodecMp4Writer pattern — framework classes cannot run
 * in JVM unit tests, so every decision lives in the JVM-tested core):
 * [AudioTrackSink] and [MediaExtractorAudioSource].
 */

/**
 * The output seam: everything the playback core needs from an audio device.
 * Thin on purpose — open/write/play/pause/flush/release is the whole
 * AudioTrack surface the editor uses, so the core can be tested against a
 * fake and the real [AudioTrackSink] stays a device-gated adapter.
 */
interface AudioSink {

    /**
     * Configures the sink for interleaved 16-bit PCM at
     * [sampleRate]/[channels]. False when the format cannot play (unsupported
     * channel count, no audio device): the caller plays silent — never a
     * failure of playback itself.
     */
    fun open(sampleRate: Int, channels: Int): Boolean

    /** Enqueues [pcm] (interleaved 16-bit); blocks while the device buffer is full — that is the pacing. Returns shorts written, < 0 on failure. */
    fun write(pcm: ShortArray): Int

    /** Starts/continues output at the current position. */
    fun play()

    /** Stops output, keeping the position and any queued samples. */
    fun pause()

    /** Drops the samples still queued (seek: old position's audio goes away). Only effective while paused/stopped. */
    fun flush()

    /** Releases the device. Exactly-once by contract. */
    fun release()
}

/**
 * One clip's audio behind a positionable stream of PCM chunks: [seekTo]
 * repositions (in FRAMES — per-channel samples — not shorts), [readChunk]
 * returns the next up-to-[maxFrames] interleaved frames or null at the end
 * of the clip's audio. All calls arrive on ONE thread (the audio playback
 * thread); [sampleRate]/[channels] are 0-free and come from the clip
 * (AUD-06).
 */
interface ClipAudioSource {

    val sampleRate: Int
    val channels: Int

    /** Positions the stream at [frameOffset] frames into the clip (clamped to [0, end]). */
    fun seekTo(frameOffset: Long)

    /** Next interleaved PCM chunk (at most [maxFrames] frames), or null when the audio is exhausted. Never throws past release: null. */
    fun readChunk(maxFrames: Int): ShortArray?

    /** Releases the source (and any descriptor it owns). Exactly-once by contract. */
    fun release()
}

/**
 * Pure position math for the audio path (JVM-testable, AUD-06: the rate is
 * always the clip's own).
 */
object AudioSync {

    /**
     * Frames (per-channel samples) into the clip's audio at [positionUs]
     * — the transport's position (0 = the clip's first video frame, the
     * same origin the export path aligns audio sample 0 to). Floor: audio
     * never starts AHEAD of the video position. A non-positive rate (clip
     * without audio) addresses the clip start.
     */
    fun frameOffsetAt(positionUs: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || positionUs <= 0L) return 0L
        return positionUs * sampleRate / 1_000_000L
    }
}

/**
 * [ClipAudioSource] over the MCRAW one-shot whole-clip PCM (the native
 * readAudio contract: all audio chunks concatenated, interleaved per
 * channel, 16-bit). The session reads the array once on the preview engine
 * thread and hands it to this pure, array-slicing source — seeks and chunks
 * afterwards are plain memory moves on the audio thread. JVM-testable.
 */
class PcmClipAudioSource(
    override val sampleRate: Int,
    override val channels: Int,
    private val pcm: ShortArray,
) : ClipAudioSource {

    private var cursor = 0
    private var released = false

    override fun seekTo(frameOffset: Long) {
        if (channels <= 0) return
        // Frames -> interleaved shorts; clamped to the clip both ways.
        cursor = (frameOffset * channels).coerceIn(0L, pcm.size.toLong()).toInt()
    }

    override fun readChunk(maxFrames: Int): ShortArray? {
        if (released || channels <= 0 || maxFrames <= 0 || cursor >= pcm.size) return null
        val frames = minOf(maxFrames, (pcm.size - cursor) / channels)
        if (frames <= 0) return null
        val out = pcm.copyOfRange(cursor, cursor + frames * channels)
        cursor += frames * channels
        return out
    }

    override fun release() {
        released = true
    }
}

/**
 * The audio transition state machine (JVM-tested with fakes): the sink and
 * source seams above driven through play/pause/stop/release. NOT
 * thread-safe by design — every call arrives on the audio playback thread
 * ([EditorAudioPlayback]'s single thread), the same one-thread discipline
 * as the preview engine.
 *
 * Transitions (owner request 2026-11-11 section 4):
 *  - [start] re-primes: whatever is still queued from an earlier position
 *    is flushed away (AUD-08's first form — old samples dropped), the
 *    source repositions via the clip's own sample rate, and playback
 *    continues from the new position. Used for play, resume and seek alike.
 *  - [pause] stops output without dropping anything.
 *  - [stop] (clip end) stops output AND drops the queued tail.
 *  - [pump] streams one chunk; the caller loops it while it returns true.
 *    End of the clip's audio (or any source failure) degrades to silence —
 *    audio is best-effort and never fails the video path.
 *  - [release] is exactly-once and ends everything: no write, seek or start
 *    happens after it.
 */
class AudioPlaybackCore(
    private val sink: AudioSink,
    private val source: ClipAudioSource,
) {

    var playing = false
        private set
    private var sinkReady = false
    private var broken = false
    private var released = false

    /** Frames per write: ~21 ms at 48 kHz — small enough that a command lands between chunks. */
    var chunkFrames: Int = CHUNK_FRAMES

    /**
     * (Re-)starts audio at [fromPositionUs] — the transport position (0 =
     * clip start). Drops any still-queued samples and re-primes at the new
     * position. Silent no-op after [release] or a failed sink open.
     */
    fun start(fromPositionUs: Long) {
        if (released || broken) return
        if (!sinkReady) {
            sinkReady = sink.open(source.sampleRate, source.channels)
            if (!sinkReady) {
                broken = true
                return
            }
        }
        // Re-prime (AUD-08's first form): flush only takes effect while
        // paused/stopped, so pause -> flush -> seek -> play, uniformly for
        // first start, resume and seek.
        sink.pause()
        sink.flush()
        source.seekTo(AudioSync.frameOffsetAt(fromPositionUs, source.sampleRate))
        sink.play()
        playing = true
    }

    /** Stops output, keeping the position and the queued samples (pause: "ses dursun"). */
    fun pause() {
        if (!playing) return
        playing = false
        sink.pause()
    }

    /** Clip end: stop AND drop the queued tail — the audio ends with the video. */
    fun stop() {
        if (released || !sinkReady) return
        playing = false
        sink.pause()
        sink.flush()
    }

    /** Releases the source and the sink. Exactly-once; everything else is a no-op afterwards. */
    fun release() {
        if (released) return
        released = true
        playing = false
        source.release()
        sink.release()
    }

    /**
     * Streams the next chunk. False when idle/ended; the caller keeps
     * pumping while true. End of the clip's audio, a source failure or a
     * failed write degrades to silence (paused sink, no throw).
     */
    fun pump(): Boolean {
        if (released || broken || !playing) return false
        val chunk = try {
            source.readChunk(chunkFrames)
        } catch (t: Throwable) {
            null
        }
        if (chunk == null || chunk.isEmpty()) {
            // The clip's audio is exhausted (audio shorter than video is
            // fine) or the source failed: silence, never a crash.
            playing = false
            sink.pause()
            return false
        }
        if (sink.write(chunk) < 0) {
            playing = false
            sink.pause()
            return false
        }
        return true
    }

    companion object {
        const val CHUNK_FRAMES = 1024
    }
}

/**
 * The screen-facing audio controller (owner request 2026-11-11 section 4).
 * Main-thread API — the same thread discipline as the rest of the editor —
 * with all sink/source work serialized onto ONE daemon audio thread
 * ("rawedit-editor-audio"): the blocking AudioTrack write paces the stream
 * there, and pause/seek/stop land between chunks (bounded by one chunk's
 * write, ~21 ms).
 *
 * Lifecycle: one controller per clip (the screen remembers it by sourceId),
 * created lazily, released exactly once at dispose. The clip's audio source
 * is opened ONCE per session (the MCRAW one-shot PCM read happens on the
 * preview engine thread inside the session's openAudio and is cached
 * there) and reused across play/pause cycles.
 *
 * The position a start resumes from is a PROVIDER, evaluated on the audio
 * thread when the start is actually handled — so a slow audio open (first
 * play: the PCM read queues behind renders on the preview engine) still
 * begins in sync with wherever the wall clock is at that moment, and the
 * video loop is never gated by it.
 */
class EditorAudioPlayback(
    private val newSink: () -> AudioSink = ::AudioTrackSink,
) {

    private val commands = LinkedBlockingQueue<Command>()
    private val released = AtomicBoolean(false)
    private val threadStarted = AtomicBoolean(false)

    // Main-thread-confined: the cached clip audio (null = not opened yet /
    // the clip has none — silent playback).
    private var source: ClipAudioSource? = null

    /**
     * Starts (or resumes) audio for [session]'s clip at the position
     * [positionUs] returns when the audio thread handles this start.
     * Never throws and never fails playback: no session, no audio, a
     * failed open or a released controller all read as silence.
     */
    suspend fun start(
        session: EditorPreviewSession?,
        fdProvider: () -> Int,
        positionUs: () -> Long,
    ) {
        if (released.get() || session == null) return
        var src = source
        if (src == null) {
            // The MCRAW path reads the whole-clip PCM here (suspends on the
            // preview engine); a cancelled play start (clip switch) must
            // not enqueue its Start afterwards.
            src = runCatching { session.openAudio(fdProvider) }.getOrNull() ?: return
            if (released.get() || !currentCoroutineContext().isActive) {
                // Dispose or cancellation raced the open: nobody will
                // consume this source.
                src.release()
                return
            }
            source = src
        }
        ensureThread()
        commands.add(Command.Start(src, positionUs))
    }

    /** Stops output, keeping the position (pause). */
    fun pause() {
        if (threadStarted.get()) commands.add(Command.Pause)
    }

    /** Clip end: stop and drop the queued tail. */
    fun stop() {
        if (threadStarted.get()) commands.add(Command.Stop)
    }

    /** Releases the audio thread, the sink and the clip audio source. Exactly-once. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            ensureThread()
            commands.add(Command.Release)
        }
    }

    private fun ensureThread() {
        if (threadStarted.compareAndSet(false, true)) {
            Thread({ loop() }, "rawedit-editor-audio").apply { isDaemon = true }.start()
        }
    }

    /**
     * The audio thread: handle one command, then stream one chunk while
     * playing. The blocking sink write is the pacing (no sleep needed);
     * while idle the loop parks on the queue (100 ms poll, near-zero CPU).
     * Any unexpected failure releases the boundaries and ends the thread —
     * silence, never a crash of the editor.
     */
    private fun loop() {
        var core: AudioPlaybackCore? = null
        try {
            while (true) {
                val playing = core?.playing == true
                when (val cmd = commands.poll(if (playing) 0L else IDLE_POLL_MS, TimeUnit.MILLISECONDS)) {
                    is Command.Start -> {
                        if (core == null) core = AudioPlaybackCore(newSink(), cmd.source)
                        core.start(cmd.positionUs())
                    }
                    Command.Pause -> core?.pause()
                    Command.Stop -> core?.stop()
                    Command.Release -> {
                        core?.release()
                        return
                    }
                    null -> {}
                }
                val c = core ?: continue
                if (c.playing) c.pump()
            }
        } catch (t: Throwable) {
            runCatching { core?.release() }
        }
    }

    private sealed class Command {
        class Start(val source: ClipAudioSource, val positionUs: () -> Long) : Command()
        object Pause : Command()
        object Stop : Command()
        object Release : Command()
    }

    private companion object {
        const val IDLE_POLL_MS = 100L
    }
}

/**
 * The real [AudioSink]: a thin AudioTrack adapter (device-gated — framework
 * classes cannot run in JVM unit tests; every decision lives in the
 * JVM-tested [AudioPlaybackCore]). MODE_STREAM, 16-bit PCM, mono/stereo via
 * the proper channel mask, buffer at least the device's minimum — playback
 * paces through the blocking write. [release] is exactly-once.
 */
internal class AudioTrackSink : AudioSink {

    private var track: AudioTrack? = null
    private var released = false

    override fun open(sampleRate: Int, channels: Int): Boolean {
        if (released || sampleRate <= 0 || channels <= 0) return false
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            // > 2 channels: the editor has no mixdown (v1) — silent rather
            // than a wrong-channel playback.
            else -> return false
        }
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return false
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBytes * 2)
                .build()
        } catch (e: Exception) {
            return false
        }
        track = t
        return true
    }

    override fun write(pcm: ShortArray): Int {
        val t = track ?: return -1
        return try {
            t.write(pcm, 0, pcm.size)
        } catch (e: IllegalStateException) {
            -1
        }
    }

    override fun play() {
        runCatching { track?.play() }
    }

    override fun pause() {
        runCatching { track?.pause() }
    }

    override fun flush() {
        runCatching { track?.flush() }
    }

    override fun release() {
        if (released) return
        released = true
        track?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
    }
}

/**
 * The encoded (Mi-Log MP4) clip's [ClipAudioSource] (device-gated, the
 * MediaExtractorTrackReader/MediaCodecFrameDecoder pattern): a SECOND
 * MediaExtractor over a fresh descriptor (the session's video reader owns
 * its own fd and extractor position) feeding an AAC MediaCodec, decoded
 * forward from each seek as interleaved 16-bit PCM chunks.
 *
 * Timeline: the audio track's pts 0 is the clip start — the same origin the
 * export path writes audio with — so a seek maps the transport position to
 * the audio track's own us (frameOffset * 1e6 / sampleRate). All framework
 * work happens lazily on the audio thread that calls this source; any
 * failure (no audio track after all, codec start failure, mid-decode error)
 * degrades to silence — the source reports end-of-stream and playback
 * continues without audio.
 */
internal class MediaExtractorAudioSource(
    private val fd: Int,
    override val sampleRate: Int,
    override val channels: Int,
) : ClipAudioSource {

    private var extractor: MediaExtractor? = null
    private var pfd: ParcelFileDescriptor? = null
    private var codec: MediaCodec? = null
    private var opened = false
    private var released = false
    private var inputEos = false
    private var eos = false
    private var remainder: ShortArray? = null

    private fun ensureOpen() {
        if (opened || released) return
        opened = true
        var ex: MediaExtractor? = null
        var descriptor: ParcelFileDescriptor? = null
        var c: MediaCodec? = null
        try {
            descriptor = ParcelFileDescriptor.adoptFd(fd)
            val e = MediaExtractor().also { it.setDataSource(descriptor.fileDescriptor) }
            ex = e
            var audio = -1
            for (i in 0 until e.trackCount) {
                val f = e.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audio = i
                    break
                }
            }
            if (audio < 0) {
                // No audio track after all: silence, and the boundaries go
                // away now (readChunk's null path needs no more cleanup).
                e.release()
                descriptor.close()
                return
            }
            val format = e.getTrackFormat(audio)
            e.selectTrack(audio)
            c = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME) ?: return
            )
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            extractor = e
            pfd = descriptor
        } catch (t: Throwable) {
            // Failed open: release quietly and report end-of-stream.
            runCatching { c?.stop() }
            runCatching { c?.release() }
            runCatching { ex?.release() }
            runCatching { descriptor?.close() }
            codec = null
            extractor = null
            pfd = null
        }
    }

    override fun seekTo(frameOffset: Long) {
        ensureOpen()
        val ex = extractor ?: return
        val c = codec ?: return
        remainder = null
        inputEos = false
        eos = false
        c.flush()
        val targetUs = if (sampleRate > 0) frameOffset * 1_000_000L / sampleRate else 0L
        ex.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    override fun readChunk(maxFrames: Int): ShortArray? {
        ensureOpen()
        val ex = extractor ?: return null
        val c = codec ?: return null
        // Leftover PCM from the previous decode first.
        remainder?.let { left ->
            remainder = null
            return split(left, maxFrames)
        }
        if (eos || maxFrames <= 0) return null
        val info = MediaCodec.BufferInfo()
        var tries = 0
        while (tries < READ_TRIES) {
            tries++
            // 1. Decoded PCM.
            val out = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (out >= 0) {
                val pcm = try {
                    outputShorts(c, out, info)
                } finally {
                    c.releaseOutputBuffer(out, false)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                if (pcm.isNotEmpty()) return split(pcm, maxFrames)
                continue
            }
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            // 2. Feed the next AAC packet.
            val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: continue
                buf.clear()
                val size = ex.sampleSize
                if (!inputEos && size > 0 && size <= buf.capacity() && ex.sampleTime >= 0) {
                    val n = ex.readSampleData(buf, 0)
                    if (n == size.toInt()) {
                        c.queueInputBuffer(inIdx, 0, size.toInt(), ex.sampleTime, 0)
                        ex.advance()
                        continue
                    }
                }
                // Extractor dry (or a malformed sample): end of input. The
                // decoder still drains its buffered PCM on later calls.
                inputEos = true
                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
        // Bounded: a stalled decode reads as end-of-stream (silence).
        return null
    }

    override fun release() {
        if (released) return
        released = true
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        extractor?.let { runCatching { it.release() } }
        pfd?.let { runCatching { it.close() } }
        if (!opened) {
            // Never even opened: the adopted descriptor is still ours alone.
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
        }
        codec = null
        extractor = null
        pfd = null
    }

    /** Serves at most [maxFrames] frames of [pcm], stashing the rest for the next call. */
    private fun split(pcm: ShortArray, maxFrames: Int): ShortArray {
        val take = minOf(pcm.size, maxFrames * channels)
        if (take < pcm.size) remainder = pcm.copyOfRange(take, pcm.size)
        return if (take == pcm.size) pcm else pcm.copyOfRange(0, take)
    }

    /** The output buffer as 16-bit PCM shorts (little-endian on the wire, assembled explicitly). */
    private fun outputShorts(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ShortArray {
        if (info.size < 2) return ShortArray(0)
        val buf = c.getOutputBuffer(index) ?: return ShortArray(0)
        val n = info.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val lo = buf.get(info.offset + i * 2).toInt() and 0xFF
            val hi = buf.get(info.offset + i * 2 + 1).toInt()
            out[i] = (lo or (hi shl 8)).toShort()
        }
        return out
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val READ_TRIES = 40
    }
}
