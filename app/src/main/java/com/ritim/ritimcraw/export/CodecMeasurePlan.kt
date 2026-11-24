package com.ritim.ritimcraw.export

/**
 * The DEBUG codec measurement mode's pure core (docs/E1E_CODEC_MEASURE_PLAN
 * §3, package E1e-a). Pure Kotlin — no android.media import: the
 * MediaFormat key names and the MediaCodecInfo ints are compile-time
 * string/int constants, so they INLINE here exactly the way ExportFormat.kt
 * does, and everything (the matrix, the format values, the summary, the
 * log line) is JVM-tested.
 *
 * WHAT THIS IS: one measurement run short-encodes a synthetic clip per
 * (encoder, size, fps, rate mode, bitrate point) and records configure
 * acceptance, the drained byte count and the realized bitrate — the data
 * that answers the two questions the probe CANNOT: (a) does configure
 * really ACCEPT bitrates above the listed ceiling (the uncapped
 * question; Klipp observed acceptance above 160/220 Mb/s on a 15 Ultra),
 * and (b) how far VBR's realized bitrate drifts from the requested one
 * (the owner's observed +24 %). The probe lists what the encoder
 * CLAIMS; this measures what it DOES.
 *
 * Single source of truth: the per-case MediaFormat VALUES come from
 * [formatValues], which follows [ExportFormats.video]'s exact key rule
 * (same key names, same Int/Float typing, same color tags — a JVM test
 * pins them equal for the same settings). The measurement never builds
 * its own second format; drift is impossible by construction.
 */

/** MediaFormat.KEY_BIT_RATE ("bitrate") — the literal MediaFormat key. */
private const val KEY_BIT_RATE = "bitrate"

/** MediaFormat.KEY_BITRATE_MODE ("bitrate-mode") — the literal MediaFormat key. */
private const val KEY_BITRATE_MODE = "bitrate-mode"

/** MediaFormat.KEY_COLOR_FORMAT ("color-format") — the literal MediaFormat key. */
private const val KEY_COLOR_FORMAT = "color-format"

/** MediaFormat.KEY_FRAME_RATE ("frame-rate") — the literal MediaFormat key. */
private const val KEY_FRAME_RATE = "frame-rate"

/** MediaFormat.KEY_I_FRAME_INTERVAL ("i-frame-interval") — the literal MediaFormat key. */
private const val KEY_I_FRAME_INTERVAL = "i-frame-interval"

/** MediaFormat.KEY_PROFILE ("profile") — the literal MediaFormat key. */
private const val KEY_PROFILE = "profile"

/** MediaFormat.KEY_COLOR_STANDARD ("color-standard") — the literal MediaFormat key. */
private const val KEY_COLOR_STANDARD = "color-standard"

/** MediaFormat.KEY_COLOR_RANGE ("color-range") — the literal MediaFormat key. */
private const val KEY_COLOR_RANGE = "color-range"

/** MediaFormat.KEY_COLOR_TRANSFER ("color-transfer") — the literal MediaFormat key. */
private const val KEY_COLOR_TRANSFER = "color-transfer"

/** MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible (2135033992). */
private const val COLOR_FORMAT_YUV420FLEXIBLE = 2135033992

/** MediaFormat.COLOR_STANDARD_BT709 (1). */
private const val COLOR_STANDARD_BT709 = 1

/** MediaFormat.COLOR_RANGE_LIMITED (2). */
private const val COLOR_RANGE_LIMITED = 2

/** MediaFormat.COLOR_TRANSFER_SDR_VIDEO (3). */
private const val COLOR_TRANSFER_SDR_VIDEO = 3

/**
 * One measurement run: the exact encoder ask. Built by
 * [CodecMeasurePlanner.matrix]; consumed by the device runner
 * (CodecMeasurer) one case per encode.
 *
 * The bitrate axis is the RUN'S OWN: [requestedBps] is what the case asks
 * the encoder for (the ceiling x multiplier rule), NOT what the export
 * plan would clamp it to — the measurement exists precisely to see what
 * configure does with values the plan would have refused to send.
 */
data class CodecMeasureCase(
    /** "video/avc" | "video/hevc" (the measurement mimes; DV is v2). */
    val mime: String,
    /** The encoder to create BY NAME (the c2.qti siblings differ); null = by-type. */
    val encoderName: String?,
    /** MediaCodecInfo.CodecProfileLevel profile int, or null = AUTO. */
    val profile: Int?,
    /** 8 or 10. 10 rides the P010 ByteBuffer row (HEVC Main10 only). */
    val bitDepth: Int,
    val width: Int,
    val height: Int,
    /** 30 or 60 (the plan §13.1 two points: 4K30 and 4K60). */
    val fps: Int,
    /** BITRATE_MODE_* int: 2 = CBR, 1 = VBR. */
    val rateMode: Int,
    /** The bitrate the case ASKS for: listed ceiling x the multiplier rule. */
    val requestedBps: Long,
    /** True when requestedBps is above the encoder's LISTED ceiling (the uncapped question). */
    val uncapped: Boolean,
    /** The synthetic clip's pts duration in seconds (default 2.0). */
    val durationSec: Double,
    /** KEY_I_FRAME_INTERVAL seconds (1f — the export default). */
    val keyframeSec: Float,
    /** The synthetic content recipe tag ("ramp+v1") — runs stay reproducible. */
    val content: String,
) {
    /** The case's one-line identity, shared by the log line and the results table. */
    fun tag(): String = "${codecLabel(mime)} $width×$height@$fps ${modeLabel()} ${bitDepth}b"

    /** "CBR" | "VBR" | the raw mode int when unknown. */
    fun modeLabel(): String = when (rateMode) {
        ExportRateMode.CBR.mediaCodecMode -> "CBR"
        ExportRateMode.VBR.mediaCodecMode -> "VBR"
        else -> "mode$rateMode"
    }

    companion object {
        /** "AVC" | "HEVC" | the mime subtype — never parsed from the codec NAME. */
        fun codecLabel(mime: String): String = when (mime) {
            ExportCodec.AVC.mime -> "AVC"
            ExportCodec.HEVC.mime -> "HEVC"
            else -> mime.substringAfter('/').uppercase()
        }
    }
}

/**
 * One run's outcome. A configure refusal is a RESULT, not a failure of the
 * series: the runner logs the row and moves on (the matrix's whole point
 * is mapping WHERE acceptance stops).
 */
data class MeasureResult(
    val c: CodecMeasureCase,
    /** configure+start succeeded (frames were fed). */
    val configureOk: Boolean,
    /** The refusal's exception CLASS NAME (e.g. "IllegalStateException"); null on success. */
    val errorCode: String?,
    /** Total drained output bytes; CODEC_CONFIG bytes excluded (parameter sets are not stream payload). */
    val encodedBytes: Long,
    /** (last frame pts - first frame pts) / 1e6, or null when no frame drained. */
    val ptsDurationSec: Double?,
    /** encodedBytes * 8 / ptsDurationSec (the realized average bitrate); null without duration. */
    val measuredBps: Long?,
    /** Bytes of the BUFFER_FLAG_SYNC_FRAME-flagged blocks. */
    val iFrameBytes: Long,
    /** Count of the BUFFER_FLAG_SYNC_FRAME-flagged blocks. */
    val iFrameCount: Int,
    /** Frames fed into the encoder (the synthetic clip's frame count). */
    val framesIn: Int,
    /** The encoder that really ran (MediaCodec.getName); null when creation failed. */
    val encoder: String?,
    /** The vendor's declared hardware flag; null when the identity read failed. */
    val hardware: Boolean?,
    /** Wall-clock milliseconds of the whole case (configure through release). */
    val wallMs: Long,
) {
    /** requested/measured ratio (null without a measurement) — the VBR-drift fact. */
    val ratio: Double?
        get() = measuredBps?.takeIf { c.requestedBps > 0 }?.let { it / c.requestedBps.toDouble() }
}

/**
 * The whole measurement session's report: the rows plus the ROM build
 * identity the rows were measured on (the same build stays one file; a
 * ROM change writes a new one).
 */
data class CodecMeasureReport(
    /** Build.FINGERPRINT — the primary ROM identity key. */
    val fingerprint: String,
    /** Build.MODEL. */
    val model: String,
    /** Build.VERSION.INCREMENTAL — carries the eng.dino-style build tag the fingerprint hides. */
    val incremental: String,
    /** When this session ran (epoch ms) — one file per fingerprint, replaced per run. */
    val startedMs: Long,
    val rows: List<MeasureResult>,
) {
    val durationMs: Long get() = rows.maxOfOrNull { it.wallMs } ?: 0L
}

/** The matrix knobs (plan §2 defaults; the plan's §8 decisions all sit in constants here). */
data class MeasureOptions(
    /** The synthetic clip's pts duration per run (plan §8.2: 2 s). */
    val durationSec: Double = DEFAULT_DURATION_SEC,
    /** The fixed cooldown between runs, for thermals (plan §8.6: 1500 ms). */
    val cooldownMs: Long = INTER_RUN_COOLDOWN_MS,
    /** Include the 10-bit P010 group (HEVC Main10 only; drops automatically when unsupported). */
    val tenBit: Boolean = true,
    /** I-frame interval seconds — the export default (the measurement compares like for like). */
    val keyframeSec: Float = 1f,
    /** The synthetic content recipe; [CodecMeasureCase.content] writes it into every row. */
    val content: String = CONTENT_RECIPE,
) {
    companion object {
        /** Plan §8.2: 2 s — long enough that the I-frame burst does not dominate. */
        const val DEFAULT_DURATION_SEC = 2.0

        /** Plan §8.6: 1500 ms fixed wait between runs (thermals, cancel checked between runs). */
        const val INTER_RUN_COOLDOWN_MS = 1500L

        /**
         * Plan §2 content recipe: a frame-stepped diagonal luma ramp (the
         * encoder must spend bits per frame — flat grey would collapse
         * VBR to its floor) seeded with 16x16 noise blocks.
         */
        const val CONTENT_RECIPE = "ramp+v1"
    }
}

object CodecMeasurePlanner {

    /**
     * The uncapped probe points (plan §2/§8.3): below-ceiling 0.875x (the
     * control row), the listed ceiling itself, and the two above-ceiling
     * points 1.25x/1.5x — the uncapped question's data. HEVC 160 -> 140 /
     * 160 / 200 / 240; AVC 220 -> 192.5 / 220 / 275 / 330 Mb/s.
     */
    val BITRATE_MULTIPLIERS = doubleArrayOf(0.875, 1.0, 1.25, 1.5)

    /** The two measurement points (plan §13.1): 4K30 and 4K60. */
    val SIZES = listOf(
        3840 to 30,
        3840 to 60,
    )

    /** The measured rate modes, in run order. (CQ never enters: the .cq encoder is 512x512-only.) */
    val RATE_MODES = listOf(ExportRateMode.CBR, ExportRateMode.VBR)

    /**
     * The measurement matrix (plan §3.1): for every GENERAL-role encoder
     * (c2.qti's .cq/.hdr siblings are NOT the general video encoder), both
     * sizes, both rate modes (dropped when the encoder does not list the
     * mode), and the four ceiling-multiplier points. Rows DROP honestly:
     * an encoder without a listed ceiling is skipped (returns it in
     * [Matrix.notes]), a 10-bit row needs P010 input AND the Main10
     * profile.
     *
     * A FUZZ-GUARDED device list is passed to the caller as-is: no encoder
     * facts (probe failed) yields an empty case list, the run is a no-op.
     */
    fun matrix(encoders: List<EncoderFacts>, opts: MeasureOptions = MeasureOptions()): Matrix {
        val notes = ArrayList<String>()
        val cases = ArrayList<CodecMeasureCase>()
        for (codec in listOf(ExportCodec.AVC, ExportCodec.HEVC)) {
            val general = encoders.filter { it.mime == codec.mime && it.role == EncoderFacts.Role.GENERAL }
            if (general.isEmpty()) {
                notes += "${CodecMeasureCase.codecLabel(codec.mime)}: no general encoder listed"
                continue
            }
            // The matrix measures ONE encoder per codec — the export
            // plan's own pick (highest listed ceiling wins). Siblings
            // differ (c2.qti.avc vs OMX.qcom) and the export path is the
            // contract the measurement feeds.
            val enc = general.sortedWith(
                compareByDescending<EncoderFacts> { it.bitrateRangeBps?.last ?: 0 },
            ).first()
            val ceiling = enc.bitrateRangeBps?.last?.toLong()?.takeIf { it > 0 }
            if (ceiling == null) {
                notes += "${enc.name}: no listed ceiling, skipped"
                continue
            }
            for ((w, fps) in SIZES) {
                if (enc.sizeSupport(w, 2160, fps.toDouble()) == Support.NO) {
                    notes += "${enc.name}: $w×2160@$fps size refused, rows dropped"
                    continue
                }
                for (mode in RATE_MODES) {
                    if (!enc.supportsRateMode(mode)) {
                        notes += "${enc.name}: ${mode.name} not listed, mode dropped"
                        continue
                    }
                    for (m in BITRATE_MULTIPLIERS) {
                        val bps = bitratePoint(ceiling, m)
                        cases += CodecMeasureCase(
                            codec.mime, enc.name, profileFor(codec, 8), 8,
                            w, 2160, fps, mode.mediaCodecMode,
                            bps, bps > ceiling,
                            opts.durationSec, opts.keyframeSec, opts.content,
                        )
                    }
                }
            }
            // 10-bit: P010 input + a listed ten-bit profile, never a stretch
            // (the row must measure a depth the encoder truly takes).
            if (opts.tenBit && codec == ExportCodec.HEVC &&
                EncoderFacts.tenBitProfile(ExportCodec.HEVC.mime)?.let { it in enc.profiles } == true &&
                enc.acceptsTenBitInput()
            ) {
                for ((w, fps) in SIZES) {
                    for (mode in RATE_MODES) {
                        if (!enc.supportsRateMode(mode)) continue
                        for (m in BITRATE_MULTIPLIERS) {
                            val bps = bitratePoint(ceiling, m)
                            cases += CodecMeasureCase(
                                ExportCodec.HEVC.mime, enc.name, EncoderFacts.tenBitProfile(ExportCodec.HEVC.mime), 10,
                                w, 2160, fps, mode.mediaCodecMode,
                                bps, bps > ceiling,
                                opts.durationSec, opts.keyframeSec, opts.content,
                            )
                        }
                    }
                }
            }
        }
        return Matrix(cases, notes)
    }

    /** The encoder's listed ceiling x the multiplier, rounded to whole bits per second. */
    fun bitratePoint(ceilingBps: Long, multiplier: Double): Long {
        require(ceilingBps > 0) { "ceiling must be positive" }
        require(multiplier > 0) { "multiplier must be positive" }
        return Math.round(ceilingBps * multiplier)
    }

    /**
     * 10-bit rows carry the Main10 profile; AVC carries High (the export
     * plan's own default). HEVC 8-bit keeps AUTO (what ExportPlan does
     * for HEVC when the profile is unset).
     */
    private fun profileFor(codec: ExportCodec, bitDepth: Int): Int? = when {
        bitDepth == 10 -> EncoderFacts.tenBitProfile(codec.mime)
        codec == ExportCodec.AVC -> ExportPlan.AVC_PROFILE_HIGH
        else -> null
    }

    /**
     * The case's MediaFormat VALUES, following [ExportFormats.video]'s
     * exact key rule (same key names, same Int/Float typing, BT.709
     * limited SDR). The runner copies this Map into a real MediaFormat —
     * the single format authority stays [ExportFormats]; a JVM test
     * pins the two equal for the same settings.
     */
    fun formatValues(c: CodecMeasureCase): Map<String, Number> {
        val v = LinkedHashMap<String, Number>()
        v[KEY_COLOR_FORMAT] = COLOR_FORMAT_YUV420FLEXIBLE
        v[KEY_BIT_RATE] = c.requestedBps
        v[KEY_BITRATE_MODE] = c.rateMode
        v[KEY_FRAME_RATE] = c.fps
        v[KEY_I_FRAME_INTERVAL] = c.keyframeSec
        c.profile?.let { v[KEY_PROFILE] = it }
        v[KEY_COLOR_STANDARD] = COLOR_STANDARD_BT709
        v[KEY_COLOR_RANGE] = COLOR_RANGE_LIMITED
        v[KEY_COLOR_TRANSFER] = COLOR_TRANSFER_SDR_VIDEO
        return v
    }

    /**
     * The row's one log line (plan §4.6) — every run writes one line, the
     * device protocol greps `ritim-codec-measure`:
     *
     *   run: HEVC 3840×2160@30 VBR 8b req=140000000 ok=1 bytes=... measured=... i=.../... enc=... wall=...ms
     */
    fun line(r: MeasureResult): String {
        val sb = StringBuilder("run: ")
        sb.append(r.c.tag())
        sb.append(" req=").append(r.c.requestedBps)
        sb.append(" ok=").append(if (r.configureOk) 1 else 0)
        if (r.errorCode != null) sb.append(" err=").append(r.errorCode)
        sb.append(" bytes=").append(r.encodedBytes)
        if (r.ptsDurationSec != null) sb.append(" dur=").append(r.ptsDurationSec)
        if (r.measuredBps != null) sb.append(" measured=").append(r.measuredBps)
        sb.append(" i=").append(r.iFrameBytes).append('/').append(r.iFrameCount)
        sb.append(" frames=").append(r.framesIn)
        r.encoder?.let { sb.append(" enc=").append(it) }
        r.hardware?.let { sb.append(" hw=").append(it) }
        sb.append(" wall=").append(r.wallMs).append("ms")
        return sb.toString()
    }

    /**
     * The session summary (plan §3.4): the VBR drift (which CBR rows never
     * enter — the owner's +24 % observation is a VBR fact), the max
     * measured/requested, and the configure-refusal count — the numbers
     * the top of the results table shows.
     */
    fun summarize(rows: List<MeasureResult>): MeasureSummary {
        val ok = rows.filter { it.configureOk }
        val measured = ok.filter { (it.measuredBps ?: 0L) > 0L }
        val vbr = ok.filter {
            it.c.rateMode == ExportRateMode.VBR.mediaCodecMode && it.measuredBps != null && it.c.requestedBps > 0
        }
        val cbr = ok.filter {
            it.c.rateMode == ExportRateMode.CBR.mediaCodecMode && it.measuredBps != null && it.c.requestedBps > 0
        }
        fun ratios(rs: List<MeasureResult>) = rs.mapNotNull { it.ratio }
        val vbrRatios = ratios(vbr)
        val cbrRatios = ratios(cbr)
        return MeasureSummary(
            runs = rows.size,
            configuredOk = ok.size,
            configureRejections = rows.size - ok.size,
            measuredRuns = measured.size,
            vbrDriftMedian = median(vbrRatios),
            vbrDriftMax = vbrRatios.maxOrNull(),
            cbrDriftMedian = median(cbrRatios),
            cbrDriftMax = cbrRatios.maxOrNull(),
            measuredMaxBps = measured.maxOfOrNull { it.measuredBps ?: 0L },
            uncappedAccepted = ok.count { it.c.uncapped },
            uncappedTried = rows.count { it.c.uncapped },
        )
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** The matrix plus why rows dropped (the honest "no listed ceiling" notes). */
    data class Matrix(val cases: List<CodecMeasureCase>, val notes: List<String>)
}

/**
 * The numbers the results table leads with. All drift values are
 * measured/requested RATIOS (1.24 = +24 %); null when the group has no
 * measured run.
 */
data class MeasureSummary(
    val runs: Int,
    val configuredOk: Int,
    val configureRejections: Int,
    val measuredRuns: Int,
    /** Median measured/requested over VBR rows (the E1c scale-factor candidate). */
    val vbrDriftMedian: Double?,
    val vbrDriftMax: Double?,
    /** CBR drift is tracked too (a CBR row that drifts is an encoder bug, worth seeing). */
    val cbrDriftMedian: Double?,
    val cbrDriftMax: Double?,
    val measuredMaxBps: Long?,
    /** Above-ceiling rows configure ACCEPTED — the uncapped question's answer count. */
    val uncappedAccepted: Int,
    /** Above-ceiling rows tried. */
    val uncappedTried: Int,
)
