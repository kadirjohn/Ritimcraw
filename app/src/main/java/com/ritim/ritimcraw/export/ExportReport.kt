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
