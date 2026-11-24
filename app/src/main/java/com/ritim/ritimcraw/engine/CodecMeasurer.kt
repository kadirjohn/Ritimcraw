package com.ritim.ritimcraw.engine

import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.export.CodecMeasureCase
import com.ritim.ritimcraw.export.CodecMeasureReport
import com.ritim.ritimcraw.export.CodecMeasureStore
import com.ritim.ritimcraw.export.DeviceEncoderFacts
import com.ritim.ritimcraw.export.MeasureOptions
import com.ritim.ritimcraw.export.MeasureResult
import com.ritim.ritimcraw.export.CodecMeasurePlanner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The DEBUG codec measurement runner (docs/E1E_CODEC_MEASURE_PLAN §4,
 * package E1e-b): REAL short encodes on the device's REAL encoders —
 * the answer to the two questions the probe's LIST cannot settle:
 * (a) does configure actually ACCEPT bitrates above the listed 160/220
 * Mb/s ceiling (the uncapped question), and (b) how far does VBR's
 * realized bitrate drift from the requested one (the owner's +24 %).
 *
 * THE SHAPE (the writer's own configure -> feed -> drain chain, no
 * muxer, no file): per case, create the codec BY NAME (c2.qti siblings
 * differ), configure from the planner's pure format VALUES (the single
 * format authority is ExportFormats' key rule; CodecMeasurePlanner
 * builds the values the writer's toMediaFormat copies verbatim), feed
 * durationSec x fps synthetic frames through the INPUT IMAGE API (the
 * writer's ByteBuffer plane write — no Surface, P010 rows write P010
 * planes), drain ALL output into counters, release. One log line per
 * run under "ritim-codec-measure".
 *
 * NO FILE IS WRITTEN for the encode itself: the bitrate measurement
 * counts the drained BYTES (BUFFER_FLAG_CODEC_CONFIG excluded —
 * parameter sets are not stream payload); the mp4 exists only in the
 * real export path. The REPORT (JSON) is the only output, stored in
 * files/codec-measure (never MediaStore, no permissions).
 *
 * Device-gated by nature: MediaCodec does not exist on the JVM. Every
 * decision around it (the matrix, the format values, the summary, the
 * line format) is the JVM-tested CodecMeasurePlanner/CodecMeasureWire;
 * this file is the thin Android adapter, the ExportAbSweep split.
 */
object CodecMeasurer {

    /** The plan §4.6 log tag — the device protocol greps this. */
    const val TAG = "ritim-codec-measure"

    /** Debug builds only — nothing here runs or logs in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /** One measurement at a time; the settings gate enforces it with this flag. */
    private val running = AtomicBoolean(false)

    /** The single measurement thread (the ExportEngine pattern): runs are serial, daemon. */
    private val engine: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "rawedit-codec-measure").apply { isDaemon = true } }
    }

    /**
     * The session's live progress, observed by the UI (Compose collects
     * this on main; the runner thread only WRITES the flow — MutableStateFlow
     * is thread-safe, so the background thread needs no handler hop).
     * 0/0 = idle; n/total while a session runs.
     */
    private val progressState = kotlinx.coroutines.flow.MutableStateFlow(0 to 0)

    /** The live session progress as a cold-read pair (done, total). */
    val progress: kotlinx.coroutines.flow.StateFlow<Pair<Int, Int>> = progressState

    /**
     * The report the LAST COMPLETED session persisted (null before the
     * first run). Written on the runner thread; the UI reads through the
     * flow API or the store — both thread-safe.
     */
    private val lastReportState = kotlinx.coroutines.flow.MutableStateFlow<CodecMeasureReport?>(null)

    /** The last persisted report as a StateFlow (null = never run). */
    val lastReport: kotlinx.coroutines.flow.StateFlow<CodecMeasureReport?> = lastReportState

    /** False while a measurement is in flight; a second tap is a no-op. */
    fun isRunning(): Boolean = running.get()

    /**
     * THE ROM BUILD IDENTITY (plan §5) — the report's primary key.
     * Build.FINGERPRINT carries the HyperOS identity; the INCREMENTAL
     * carries the eng.dino-style build tag the fingerprint hides, so
     * the row writes BOTH (each stays its own field; neither is derived
     * from the other).
     */
    data class BuildIdentity(
        val fingerprint: String,
        val model: String,
        val incremental: String,
    ) {
        companion object {
            fun read(): BuildIdentity = BuildIdentity(
                fingerprint = Build.FINGERPRINT ?: "",
                model = Build.MODEL ?: "",
                incremental = Build.VERSION.INCREMENTAL ?: "",
            )
        }
    }

    /**
     * Starts a full measurement session in the background and returns
     * immediately. Serial + one-at-a-time; false when disabled or a
     * session is already running (the caller shows that honestly).
     * Progress and the finished report land in [progress]/[lastReport]
     * (the runner thread only writes thread-safe flows — the UI collects
     * them on main).
     */
    fun start(
        identity: BuildIdentity,
        store: CodecMeasureStore,
        opts: MeasureOptions = MeasureOptions(),
    ): Boolean {
        if (!enabled || !running.compareAndSet(false, true)) return false
        progressState.value = 0 to 0
        engine.execute {
            val report = try {
                runSession(identity, store, opts) { done, total, _ ->
                    progressState.value = done to total
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "measurement session aborted: ${e.message}")
                null
            } finally {
                running.set(false)
            }
            lastReportState.value = report
            progressState.value = 0 to 0
        }
        return true
    }

    /** Requests a cancel; the runner stops at the NEXT case boundary and keeps what it measured. */
    fun cancel() {
        cancelled.set(true)
    }

    private val cancelled = AtomicBoolean(false)

    private fun runSession(
        identity: BuildIdentity,
        store: CodecMeasureStore,
        opts: MeasureOptions,
        onProgress: (Int, Int, String) -> Unit,
    ): CodecMeasureReport? {
        cancelled.set(false)
        // The matrix is derived LIVE from the device's encoder facts
        // (plan §3.1): every row exists because THIS device lists the
        // encoder, the ceiling, the mode. An empty list is a no-op with
        // the reason logged (not an error; the device said nothing).
        val m = CodecMeasurePlanner.matrix(DeviceEncoderFacts.all(), opts)
        for (note in m.notes) android.util.Log.i(TAG, "matrix: $note")
        if (m.cases.isEmpty()) {
            android.util.Log.w(TAG, "matrix is empty: no measurable encoder on this device")
            return null
        }
        val startedMs = System.currentTimeMillis()
        val rows = ArrayList<MeasureResult>(m.cases.size)
        for ((i, c) in m.cases.withIndex()) {
            if (cancelled.get()) {
                android.util.Log.i(TAG, "cancelled at case ${i}/${m.cases.size}; keeping ${rows.size} rows")
                break
            }
            if (i > 0) {
                try {
                    Thread.sleep(opts.cooldownMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val r = runCase(c)
            rows += r
            android.util.Log.i(TAG, CodecMeasurePlanner.line(r))
            onProgress(i + 1, m.cases.size, c.tag())
        }
        val report = CodecMeasureReport(
            fingerprint = identity.fingerprint,
            model = identity.model,
            incremental = identity.incremental,
            startedMs = startedMs,
            rows = rows,
        )
        store.save(report)
        android.util.Log.i(TAG, "session done: ${rows.size} runs, report persisted")
        return report
    }

    /**
     * One case, start to release. A configure refusal is a RESULT (the
     * row records it and the series continues — plan §2.1); every other
     * failure also lands in the row, never an exception that stops the
     * matrix (only the session-level catch sees those, e.g. an OOM).
     *
     * The feed/drain shape is the writer's own: dequeue input, write the
     * codec's Image planes at ITS strides, queue with pts i*1e6/fps;
     * drain non-blocking while frames remain, EOS once, then block until
     * the EOS buffer arrives (the last frames sit in the codec until
     * then). CODEC_CONFIG bytes never enter the counters.
     */
    internal fun runCase(c: CodecMeasureCase): MeasureResult {
        val started = System.currentTimeMillis()
        var codec: MediaCodec? = null
        var encoder: String? = null
        var hardware: Boolean? = null
        var configureOk = false
        var errorCode: String? = null
        val frames = (c.durationSec * c.fps).roundToInt()
        var encodedBytes = 0L
        var iFrameBytes = 0L
        var iFrameCount = 0
        var firstPts = -1L
        var lastPts = -1L
        var framesIn = 0
        try {
            codec = create(c)
            encoder = runCatching { codec.name }.getOrNull()
            hardware = runCatching { codec.codecInfo.isHardwareAccelerated }.getOrNull()
            codec.configure(mediaFormat(c), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            configureOk = true
            codec.start()
            val synth = SyntheticFrame(c)
            var fed = 0
            var eosSent = false
            var eosSeen = false
            val info = MediaCodec.BufferInfo()
            while (!eosSeen) {
                // Feed first: the encoder turns frames into output while
                // the loop below drains them.
                if (fed < frames) {
                    val idx = codec.dequeueInputBuffer(250_000)
                    if (idx >= 0) {
                        val image = codec.getInputImage(idx)
                        if (image != null) {
                            synth.write(fed, image)
                            val size = com.ritim.ritimcraw.export.imageInputSize(
                                c.width, c.height,
                                image.planes[0].rowStride,
                                image.planes[1].rowStride, image.planes[1].pixelStride,
                                image.planes[2].rowStride, image.planes[2].pixelStride,
                            )
                            codec.queueInputBuffer(idx, 0, size, fed * 1_000_000L / c.fps, 0)
                            framesIn++
                        } else {
                            // No Image for this buffer: queue it empty
                            // rather than leak the slot (size 0 = no
                            // samples; the encoder sees a skipped frame).
                            codec.queueInputBuffer(idx, 0, 0, fed * 1_000_000L / c.fps, 0)
                        }
                        fed++
                    }
                } else if (!eosSent) {
                    // Every frame is in: ONE EOS, then only drain remains.
                    val idx = codec.dequeueInputBuffer(250_000)
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
                // Drain whatever is ready; after EOS block up to the
                // deadline so the tail cannot spin hot.
                val timeoutUs = if (eosSent) 1_000_000L else 0L
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, timeoutUs)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (out >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            encodedBytes += info.size
                            if (info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0) {
                                iFrameBytes += info.size
                                iFrameCount++
                            }
                            if (info.presentationTimeUs >= 0) {
                                if (firstPts < 0 || info.presentationTimeUs < firstPts) firstPts = info.presentationTimeUs
                                if (info.presentationTimeUs > lastPts) lastPts = info.presentationTimeUs
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                    }
                }
            }
        } catch (e: Exception) {
            errorCode = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
            android.util.Log.w(TAG, "case ${c.tag()} failed: ${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        val ptsDurationSec = if (firstPts >= 0 && lastPts > firstPts) (lastPts - firstPts) / 1_000_000.0 else null
        return MeasureResult(
            c = c,
            configureOk = configureOk,
            errorCode = errorCode,
            encodedBytes = encodedBytes,
            ptsDurationSec = ptsDurationSec,
            measuredBps = ptsDurationSec?.takeIf { it > 0 }?.let { (encodedBytes * 8 / it).toLong() },
            iFrameBytes = iFrameBytes,
            iFrameCount = iFrameCount,
            framesIn = framesIn,
            encoder = encoder,
            hardware = hardware,
            wallMs = System.currentTimeMillis() - started,
        )
    }

    /** By NAME when the case names one (the c2.qti siblings differ), else by MIME. */
    private fun create(c: CodecMeasureCase): MediaCodec =
        if (c.encoderName != null) MediaCodec.createByCodecName(c.encoderName)
        else MediaCodec.createEncoderByType(c.mime)

    /**
     * The case's real MediaFormat, from the planner's pure VALUES (the
     * writer's own toMediaFormat rule: Int keys setInteger, Float keys
     * setFloat — one format authority, no second rule to drift).
     */
    internal fun mediaFormat(c: CodecMeasureCase): MediaFormat {
        val f = MediaFormat.createVideoFormat(c.mime, c.width, c.height)
        for ((k, v) in CodecMeasurePlanner.formatValues(c)) {
            when (v) {
                is Int -> f.setInteger(k, v)
                is Long -> f.setInteger(k, v.toInt())
                is Float -> f.setFloat(k, v)
                else -> throw IllegalStateException("format key $k has an unsupported value type")
            }
        }
        return f
    }

    /**
     * The case's synthetic frames (plan §2 content recipe "ramp+v1"): a
     * stepped diagonal luma ramp — the encoder must spend bits per frame
     * (a flat grey would collapse VBR to its floor and understate the
     * drift the mode exists to measure) — with per-frame 16x16 noise
     * blocks. Deterministic: the same case always produces the same
     * bytes, so runs stay reproducible (the content tag rides the row).
     *
     * Writes straight into the codec's Image planes at ITS strides (the
     * writer's copyI420ToImage pattern): no packed intermediate, no
     * stride guess.
     */
    internal class SyntheticFrame(private val c: CodecMeasureCase) {

        /**
         * Writes frame [i]'s planes into [image] (the codec's own strides).
         * The 10-bit rows configure P010, which MediaCodec reports on the
         * Image as format 54 — the case's bit depth AND the image format
         * both select the plane writer; a mismatch (8-bit case, P010 image)
         * is impossible by construction but picked honestly from the IMAGE.
         */
        fun write(i: Int, image: Image) {
            if (c.bitDepth == 10 && image.format == P010_IMAGE_FORMAT) {
                writeP010(i, image)
            } else {
                writeYuv420(i, image)
            }
        }

        /** 8-bit 4:2:0: one byte per sample at the plane's own strides. */
        private fun writeYuv420(i: Int, image: Image) {
            val w = c.width
            val h = c.height
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yRow = yPlane.rowStride
            val step = rampStep(i)
            for (row in 0 until h) {
                yBuf.position(row * yRow)
                for (col in 0 until w) {
                    yBuf.put(luma(row, col, step).toByte())
                }
            }
            for (p in 1..2) {
                val plane = image.planes[p]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val center = CHROMA_CENTER + step / 8
                for (row in 0 until ch) {
                    var dst = row * rowStride
                    for (col in 0 until cw) {
                        buf.position(dst)
                        buf.put(center.toByte())
                        dst += pixelStride
                    }
                }
            }
        }

        /**
         * 10-bit P010 (HEVC Main10 rows): 16-bit little-endian words, the
         * 10 data bits in the MSBs, lower 6 zero (the ImageFormat.YCBCR_P010
         * doc; the 10-bit value's *4 shift = <<2 does exactly that).
         */
        private fun writeP010(i: Int, image: Image) {
            val w = c.width
            val h = c.height
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val step = rampStep(i)
            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yRow = yPlane.rowStride
            for (row in 0 until h) {
                yBuf.position(row * yRow)
                for (col in 0 until w) {
                    yBuf.putShort((luma(row, col, step) * 4).toShort())
                }
            }
            // P010 chroma: ONE semiplanar 16-bit plane, Cb/Cr interleaved
            // (pixelStride = 2 words); the value writes Cb then Cr of each
            // sample pair at dst and dst+2.
            val cPlane = image.planes[1]
            val cBuf = cPlane.buffer
            val cRow = cPlane.rowStride
            val center = ((CHROMA_CENTER + step / 8) * 4).toShort()
            for (row in 0 until ch) {
                var dst = row * cRow
                for (col in 0 until cw) {
                    cBuf.position(dst)
                    cBuf.putShort(center) // Cb
                    cBuf.position(dst + 2)
                    cBuf.putShort(center) // Cr
                    dst += 4
                }
            }
        }

        /**
         * The ramp step for frame [i]: walks 16->235 over the clip, then
         * wraps — a per-frame luminance CHANGE, so every frame forces
         * fresh bits (flat content would make VBR rings near-lossless).
         */
        private fun rampStep(i: Int): Int = (i * 13) % (235 - 16)

        /** One luma sample: a diagonal ramp (row+col sweeps the frame) + 16x16 noise blocks. */
        private fun luma(row: Int, col: Int, step: Int): Int {
            val ramp = (16 + step + ((row + col) * 3 / 128)) % (235 - 16)
            val blockNoise = ((row / 16 + col / 16 + step) % 7) * 3
            return (ramp + blockNoise).coerceIn(16, 235)
        }

        private companion object {
            /** ImageFormat.YCBCR_P010's int (54): the format MediaCodec reports on a P010 input Image. */
            const val P010_IMAGE_FORMAT = 54

            /** BT.709 limited chroma neutral (128 in 8-bit). */
            const val CHROMA_CENTER = 128
        }
    }
}
