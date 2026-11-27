package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.ProjectJson

/**
 * What an MP4 export REALLY produced (plan 7.5, E1b), persisted on the DONE
 * job (`"report"`) so the exports screen can show requested vs real: the
 * encoder that ran, the final effective settings, the bitrate the file
 * actually has (file size / duration) beside the one asked for, and the
 * plan steps (retries, fixes) that led there. Informational: a row whose
 * report cannot be read simply has none.
 *
 * Written only for jobs that carry [ExportSettings]; pre-E1 jobs keep no
 * report and stay byte-identical.
 */
data class ExportReport(
    /** MediaCodec's created encoder name; null when the identity read failed. */
    val encoder: String?,
    val hardware: Boolean?,
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitDepth: Int,
    val rateMode: String,
    val profile: Int?,
    val keyframeSec: Float,
    /** The video bitrate the plan asked the encoder for. */
    val requestedBps: Int,
    val audioKbps: Int,
    val fileBytes: Long?,
    val durationSec: Double?,
    /** Plan trail: attempts, rejections, the fix. */
    val steps: List<String>,
    /** "KIND:arg,arg" per plan issue (localized by the UI). */
    val issues: List<String>,
    /** Wall-clock milliseconds the export took (feeds the next sheet's time estimate); null = unknown. */
    val wallMs: Long? = null,
    /**
     * (E5, night D2) The loudness the export REALLY produced (the chain's
     * final measurement, not the requested target), when the
     * normalization ran; null when it did not (no target in the
     * settings, no audio track, or an honestly skipped measurement).
     * Omitted from the wire when null — a pre-E5 report row parses
     * exactly as it always did.
     */
    val audioLufs: Double? = null,
    /** [audioLufs]'s true-peak pair in dBTP; null together with it. */
    val audioDbtp: Double? = null,
    /**
     * (E5, night G1) The gain the loudness chain applied, in dB; null
     * when the chain did not run. A chain that skipped honestly (too
     * short, silent) reports 0 with its reason in [steps] — the gain is
     * never invented. Omitted from the wire when null.
     */
    val audioGainDb: Double? = null,
    /**
     * (E5-B, night G1) The output audio sample rate in Hz the export
     * really encoded — the clip's own rate, or the reshaped choice. Null
     * on a video-only export and on every pre-G1 report row (omit-when-
     * absent: an old row parses exactly as it always did).
     */
    val audioSampleRateHz: Int? = null,
    /** [audioSampleRateHz]'s channel pair; null together with it. */
    val audioChannels: Int? = null,
    /**
     * (E5, night G1) Why the loudness chain did not change the audio,
     * when a target was set but the chain skipped honestly: "too-short"
     * (<400 ms — the gating blocks cannot measure it) or "silent"
     * (unmeasurable). Null when the chain ran or no target was set.
     * Omitted from the wire when null.
     */
    val audioSkipped: String? = null,
) {
    /** The whole file's real bit rate (video + audio + container), or null without size/duration. */
    val actualTotalBps: Long?
        get() = if (fileBytes != null && durationSec != null && durationSec > 0.0) {
            (fileBytes * 8.0 / durationSec).toLong()
        } else {
            null
        }

    /** The video's share of [actualTotalBps] (the audio bitrate subtracted), never below 0. */
    val actualVideoBps: Long?
        get() = actualTotalBps?.let { (it - audioKbps * 1000L).coerceAtLeast(0L) }

    companion object {
        fun from(plan: ExportPlan, run: ExportPlanRun, fileBytes: Long?, wallMs: Long? = null): ExportReport = ExportReport(
            encoder = run.encoder?.name ?: plan.encoderName,
            hardware = run.encoder?.hardwareAccelerated ?: plan.encoderHardware,
            mime = plan.mime,
            width = plan.width,
            height = plan.height,
            fps = plan.fps,
            bitDepth = plan.bitDepth,
            rateMode = plan.effective.rateMode.name,
            profile = plan.profile,
            keyframeSec = plan.keyframeIntervalSec,
            requestedBps = plan.bitrateBps,
            audioKbps = plan.audioKbps,
            fileBytes = fileBytes,
            durationSec = run.durationSec.takeIf { it > 0.0 },
            steps = run.steps.takeLast(MAX_STEPS),
            issues = plan.issues.map { it.kind.name + ":" + it.args.joinToString(",") },
            wallMs = wallMs,
            audioLufs = run.audioMeasuredLufs,
            audioDbtp = run.audioMeasuredDbtp,
            audioGainDb = run.audioAppliedGainDb,
            audioSampleRateHz = run.audioOutSampleRateHz,
            audioChannels = run.audioOutChannels,
            audioSkipped = run.audioSkippedReason,
        )

        const val MAX_STEPS = 12
    }
}

object ExportReportWire {
    fun render(r: ExportReport): String {
        val sb = StringBuilder("{\"v\":1")
        if (r.encoder != null) sb.append(",\"encoder\":").append(ProjectJson.quote(r.encoder))
        if (r.hardware != null) sb.append(",\"hardware\":").append(r.hardware)
        sb.append(",\"mime\":").append(ProjectJson.quote(r.mime))
            .append(",\"width\":").append(r.width).append(",\"height\":").append(r.height)
            // E1 resilience: the Double fields guard with num() — a
            // non-finite value (a hand-edited file's 1e999, a zero-
            // duration division) renders as 0, never as a bare NaN/
            // Infinity token this build's own parser would reject.
            .append(",\"fps\":").append(num(r.fps))
            .append(",\"bitDepth\":").append(r.bitDepth)
            .append(",\"rateMode\":").append(ProjectJson.quote(r.rateMode))
        if (r.profile != null) sb.append(",\"profile\":").append(r.profile)
        sb.append(",\"keyframeSec\":").append(num(r.keyframeSec))
            .append(",\"requestedBps\":").append(r.requestedBps)
            .append(",\"audioKbps\":").append(r.audioKbps)
        if (r.fileBytes != null) sb.append(",\"fileBytes\":").append(r.fileBytes)
        if (r.durationSec != null) sb.append(",\"durationSec\":").append(num(r.durationSec))
        if (r.wallMs != null) sb.append(",\"wallMs\":").append(r.wallMs)
        // (E5, night D2) The measured loudness pair, omit-when-absent: a
        // report row without them parses exactly as it always did.
        if (r.audioLufs != null) sb.append(",\"audioLufs\":").append(r.audioLufs)
        if (r.audioDbtp != null) sb.append(",\"audioDbtp\":").append(r.audioDbtp)
        // (E5, night G1) The chain's applied gain and the honest skip
        // reason, omit-when-absent — a pre-G1 row parses exactly as it
        // always did.
        if (r.audioGainDb != null) sb.append(",\"audioGainDb\":").append(r.audioGainDb)
        if (r.audioSkipped != null) sb.append(",\"audioSkipped\":").append(ProjectJson.quote(r.audioSkipped))
        // (E5-B, night G1) The output audio shape the export really
        // encoded, omit-when-absent (video-only exports and old rows).
        if (r.audioSampleRateHz != null) sb.append(",\"audioSampleRateHz\":").append(r.audioSampleRateHz)
        if (r.audioChannels != null) sb.append(",\"audioChannels\":").append(r.audioChannels)
        sb.append(",\"steps\":").append(strings(r.steps)).append(",\"issues\":").append(strings(r.issues)).append('}')
        return sb.toString()
    }

    /** E1 resilience: 0 for a non-finite value (finite values unchanged). */
    private fun num(v: Double): String = if (v.isFinite()) v.toString() else "0"

    /** E1 resilience: 0 for a non-finite value (finite values unchanged). */
    private fun num(v: Float): String = if (v.isFinite()) v.toString() else "0"

    private fun strings(l: List<String>) = l.joinToString(",", "[", "]") { ProjectJson.quote(it) }

    /** [raw] is the parsed JSON value of the `report` key; null for anything unreadable. */
    fun parse(raw: Any?): ExportReport? {
        val m = raw as? Map<*, *> ?: return null
        if ((m["v"] as? Double)?.toInt() != 1) return null
        fun d(k: String) = m[k] as? Double
        fun strs(k: String) = (m[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return ExportReport(
            encoder = m["encoder"] as? String,
            hardware = m["hardware"] as? Boolean,
            mime = m["mime"] as? String ?: return null,
            width = d("width")?.toInt() ?: return null,
            height = d("height")?.toInt() ?: return null,
            fps = d("fps") ?: return null,
            bitDepth = d("bitDepth")?.toInt() ?: return null,
            rateMode = m["rateMode"] as? String ?: return null,
            profile = d("profile")?.toInt(),
            keyframeSec = d("keyframeSec")?.toFloat() ?: return null,
            requestedBps = d("requestedBps")?.toInt() ?: return null,
            audioKbps = d("audioKbps")?.toInt() ?: return null,
            fileBytes = d("fileBytes")?.toLong(),
            durationSec = d("durationSec"),
            steps = strs("steps"),
            issues = strs("issues"),
            wallMs = d("wallMs")?.toLong(),
            audioLufs = d("audioLufs"),
            audioDbtp = d("audioDbtp"),
            audioGainDb = d("audioGainDb"),
            audioSkipped = m["audioSkipped"] as? String,
            audioSampleRateHz = d("audioSampleRateHz")?.toInt(),
            audioChannels = d("audioChannels")?.toInt(),
        )
    }
}

/** Plain-number formatting shared by the report lines and the export sheet (Locale.ROOT, no prose). */
object ExportText {
    fun codec(mime: String): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/av01" -> "AV1"
        "video/apv" -> "APV"
        else -> mime
    }

    /** The dynamic range's short row label ("SDR"/"HLG"; proper names, not localized). */
    fun dynamicRange(range: com.ritim.ritimcraw.export.ExportDynamicRange): String = when (range) {
        com.ritim.ritimcraw.export.ExportDynamicRange.SDR -> "SDR"
        com.ritim.ritimcraw.export.ExportDynamicRange.HLG -> "HLG"
        com.ritim.ritimcraw.export.ExportDynamicRange.HDR10 -> "HDR10"
        com.ritim.ritimcraw.export.ExportDynamicRange.DV -> "DV"
    }

    /** 29.97 -> "29.97", 30.0 -> "30". */
    fun fps(fps: Double): String =
        if (kotlin.math.abs(fps - Math.round(fps)) < 1e-6) Math.round(fps).toString()
        else java.lang.String.format(java.util.Locale.ROOT, "%.3f", fps).trimEnd('0')

    fun mbps(bps: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%.1f", bps / 1_000_000.0)

    fun megabytes(bytes: Long): String {
        val mb = bytes / 1_048_576.0
        return if (mb >= 100) java.lang.String.format(java.util.Locale.ROOT, "%.0f MB", mb)
        else java.lang.String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    }

    /** True when the report's trail shows the export retried with a fixed plan. */
    fun wasFixed(r: ExportReport): Boolean = r.steps.any { it.startsWith("fix:") }

    /** True when 10-bit was wanted but the file is 8-bit (the report's issues say why). */
    fun tenBitDropped(r: ExportReport): Boolean = r.issues.any { it.startsWith("TEN_BIT_") }

    /** True when the Surface path fell back to the CPU path. */
    fun fellBackToCpu(r: ExportReport): Boolean = r.steps.any { it.startsWith("surface->cpu") }
}
