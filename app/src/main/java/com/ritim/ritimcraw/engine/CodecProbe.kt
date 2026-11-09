package com.ritim.ritimcraw.engine

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * One codec as the device report shows it (sprint plan Task 6). Built from
 * MediaCodecList; tests construct these directly with fake values.
 *
 * [maxSize] is the largest supported frame size as "WxH" ("" when the codec
 * reports no video size). [main10Ok] is null when the question does not apply
 * (decoders, or profiles without 10-bit support information).
 */
data class CodecReport(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val profiles: List<String>,
    val maxSize: String,
    val main10Ok: Boolean?,
    val detail: String? = null,
)

/**
 * Device codec probe (sprint plan Task 6). [probe] walks MediaCodecList and
 * maps every AVC/HEVC encoder+decoder into a [CodecReport]; it is
 * device-gated by nature (an emulator/phone returns real codecs, the JVM
 * returns nothing because MediaCodecList is not on the JVM classpath).
 *
 * The mapping and formatting logic lives in pure functions ([fromCodecInfo],
 * [formatReport], [toDeviceReportJson]) so it is unit-testable on a plain
 * JVM with fake values — no MediaCodecList in tests.
 */
object CodecProbe {

    /** Mimes the report cares about (sprint: H.264 MP4 export, Mi-Log HEVC). */
    val REPORT_MIMES = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

    /** Runs on a device only; empty when no matching codec is present. */
    fun probe(): List<CodecReport> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos
            .filter { info -> info.supportedTypes.any { it in REPORT_MIMES } }
            .map { info -> fromCodecInfo(info) }
    }

    // ---- pure mapping (JVM-testable) ----

    /**
     * Maps one MediaCodecInfo into the report shape. Pure aside from the
     * [info] reads; unit tests use [fakeReport]/direct construction instead.
     */
    fun fromCodecInfo(info: Any): CodecReport {
        // The real type is android.media.MediaCodecInfo, referenced through
        // Any so this function is callable from JVM tests with a stub.
        val m = info as? Map<*, *>
        if (m != null) {
            @Suppress("UNCHECKED_CAST")
            return fromStub(
                name = m["name"] as String,
                mime = m["mime"] as String,
                hardware = m["hardware"] as Boolean,
                profiles = (m["profiles"] as? List<String>) ?: emptyList(),
                maxWidth = (m["maxWidth"] as? Int) ?: 0,
                maxHeight = (m["maxHeight"] as? Int) ?: 0,
                main10Ok = m["main10Ok"] as? Boolean,
                detail = m["detail"] as? String,
            )
        }
        // Device path: reflection-free cast; compiled only against the real
        // class on Android (see probe()).
        return mapAndroidInfo(info as android.media.MediaCodecInfo)
    }

    private fun fromStub(
        name: String,
        mime: String,
        hardware: Boolean,
        profiles: List<String>,
        maxWidth: Int,
        maxHeight: Int,
        main10Ok: Boolean?,
        detail: String?,
    ): CodecReport = CodecReport(
        name = name,
        mime = mime,
        hardware = hardware,
        profiles = profiles,
        maxSize = if (maxWidth > 0 && maxHeight > 0) "${maxWidth}x$maxHeight" else "",
        main10Ok = main10Ok,
        detail = detail,
    )

    private fun mapAndroidInfo(info: android.media.MediaCodecInfo): CodecReport {
        val type = info.supportedTypes.firstOrNull { it in REPORT_MIMES }
            ?: info.supportedTypes.firstOrNull()
            ?: ""
        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
        val profiles = caps?.profileLevels?.map { pl -> pl.profile.toString() }?.distinct() ?: emptyList()
        val width = caps?.videoCapabilities?.supportedWidths?.upper ?: 0
        val height = caps?.videoCapabilities?.supportedHeights?.upper ?: 0
        return CodecReport(
            name = info.name,
            mime = type,
            // MediaCodecInfo has no isHardware() on every API level; the
            // canonical heuristic is the OMX.google/c2.android prefix.
            hardware = !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android."),
            profiles = profiles,
            maxSize = if (width > 0 && height > 0) "${width}x$height" else "",
            // Main10 support: HEVC profile 2 (Main10) present.
            main10Ok = if (type == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                caps?.profileLevels?.any { pl -> pl.profile == 2 } == true
            } else {
                null
            },
            detail = null,
        )
    }

    // ---- pure formatting (JVM-testable) ----

    /**
     * One report line for the Settings device report, e.g.
     * "c2.qti.avc.encoder — H.264 encoder, hardware — profiles: 1, 8192x4320 — 10-bit: yes".
     * [detail], when present, appends a parenthetical.
     */
    fun formatReport(report: CodecReport): String {
        val kind = when {
            report.mime.endsWith("avc") -> "H.264"
            report.mime.endsWith("hevc") -> "HEVC"
            else -> report.mime
        }
        val hw = if (report.hardware) "hardware" else "software"
        val sb = StringBuilder()
        sb.append(report.name).append(" — ").append(kind).append(", ").append(hw)
        if (report.profiles.isNotEmpty()) {
            sb.append(" — profiles: ").append(report.profiles.joinToString("/"))
        }
        if (report.maxSize.isNotEmpty()) {
            sb.append(" — ").append(report.maxSize)
        }
        if (report.main10Ok != null) {
            sb.append(" — 10-bit: ").append(if (report.main10Ok) "yes" else "no")
        }
        if (report.detail != null) {
            sb.append(" (").append(report.detail).append(')')
        }
        return sb.toString()
    }

    /**
     * Whole-report JSON for the device report file (device pass artifact):
     * {"codecs":[{"name":...,"mime":...,"hardware":...,"profiles":[...],
     *  "maxSize":...,"main10Ok":...,"detail":...}, ...]}
     */
    fun toDeviceReportJson(reports: List<CodecReport>): String {
        val sb = StringBuilder()
        sb.append("{\"codecs\":[")
        reports.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
                .append("\"name\":\"").append(escape(r.name)).append('"')
                .append(",\"mime\":\"").append(escape(r.mime)).append('"')
                .append(",\"hardware\":").append(r.hardware)
                .append(",\"profiles\":[")
            r.profiles.forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append('"').append(escape(p)).append('"')
            }
            sb.append(']')
                .append(",\"maxSize\":\"").append(escape(r.maxSize)).append('"')
                .append(",\"main10Ok\":")
                .append(
                    when (r.main10Ok) {
                        null -> "null"
                        else -> r.main10Ok.toString()
                    }
                )
                .append(",\"detail\":")
                .append(if (r.detail == null) "null" else "\"${escape(r.detail)}\"")
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
