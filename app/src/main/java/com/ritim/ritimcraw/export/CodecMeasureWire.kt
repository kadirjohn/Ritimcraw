package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson

/**
 * The measurement report's JSON (plan §5) — the E1c/E1e-b data source
 * file `files/codec-measure/<fingerprint-hash>.json`, and the share/
 * copy payload of the debug screen.
 *
 * STABLE FORMAT, v: 1 (the ExportReportWire pattern): render/parse are
 * pure; a file this build cannot read (a newer version) parses to null,
 * NEVER a guess — the measurement is data for E1c's size-prediction fix
 * and a silently misread row would poison that estimate.
 *
 * One file per ROM fingerprint; a new run on the same build REPLACES it
 * (plan §5: no history, no cache expiry — a ROM change changes the
 * fingerprint and starts a new file).
 */
object CodecMeasureWire {
    const val VERSION = 1

    fun render(r: CodecMeasureReport): String {
        val sb = StringBuilder("{\"v\":").append(VERSION)
        sb.append(",\"fingerprint\":").append(ProjectJson.quote(r.fingerprint))
        sb.append(",\"model\":").append(ProjectJson.quote(r.model))
        sb.append(",\"incremental\":").append(ProjectJson.quote(r.incremental))
        sb.append(",\"startedMs\":").append(r.startedMs)
        sb.append(",\"rows\":[")
        r.rows.forEachIndexed { i, row ->
            if (i > 0) sb.append(',')
            renderRow(sb, row)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun renderRow(sb: StringBuilder, r: MeasureResult) {
        sb.append("{\"mime\":").append(ProjectJson.quote(r.c.mime))
        if (r.c.encoderName != null) sb.append(",\"encoderName\":").append(ProjectJson.quote(r.c.encoderName))
        sb.append(",\"profile\":")
        if (r.c.profile == null) sb.append("null") else sb.append(r.c.profile)
        sb.append(",\"bitDepth\":").append(r.c.bitDepth)
        sb.append(",\"width\":").append(r.c.width).append(",\"height\":").append(r.c.height)
        sb.append(",\"fps\":").append(r.c.fps)
        sb.append(",\"rateMode\":").append(r.c.rateMode)
        sb.append(",\"requestedBps\":").append(r.c.requestedBps)
        sb.append(",\"uncapped\":").append(r.c.uncapped)
        sb.append(",\"durationSec\":").append(r.c.durationSec)
        sb.append(",\"keyframeSec\":").append(r.c.keyframeSec)
        sb.append(",\"content\":").append(ProjectJson.quote(r.c.content))
        sb.append(",\"configureOk\":").append(r.configureOk)
        sb.append(",\"errorCode\":").append(if (r.errorCode == null) "null" else ProjectJson.quote(r.errorCode))
        sb.append(",\"encodedBytes\":").append(r.encodedBytes)
        sb.append(",\"ptsDurationSec\":")
        if (r.ptsDurationSec == null) sb.append("null") else sb.append(r.ptsDurationSec)
        sb.append(",\"measuredBps\":")
        if (r.measuredBps == null) sb.append("null") else sb.append(r.measuredBps)
        sb.append(",\"iFrameBytes\":").append(r.iFrameBytes)
        sb.append(",\"iFrameCount\":").append(r.iFrameCount)
        sb.append(",\"framesIn\":").append(r.framesIn)
        sb.append(",\"encoder\":").append(if (r.encoder == null) "null" else ProjectJson.quote(r.encoder))
        sb.append(",\"hardware\":")
        if (r.hardware == null) sb.append("null") else sb.append(r.hardware)
        sb.append(",\"wallMs\":").append(r.wallMs)
        sb.append('}')
    }

    /** [text] is the whole file; null for anything this build cannot read verbatim. */
    fun parse(text: String): CodecMeasureReport? = try {
        val p = JsonParser(text)
        val root = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("trailing content")
        parseValue(root)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** [raw] is the already-parsed JSON value (the JsonParser root); null when unreadable. */
    fun parseValue(raw: Any?): CodecMeasureReport? {
        val m = raw as? Map<*, *> ?: return null
        if ((m["v"] as? Double)?.toInt() != VERSION) return null
        val fingerprint = m["fingerprint"] as? String ?: return null
        val model = m["model"] as? String ?: return null
        val incremental = m["incremental"] as? String ?: return null
        val startedMs = (m["startedMs"] as? Double)?.toLong() ?: return null
        val rowsRaw = m["rows"] as? List<*> ?: return null
        val rows = ArrayList<MeasureResult>(rowsRaw.size)
        for (item in rowsRaw) {
            val rm = item as? Map<*, *> ?: return null
            val case = CodecMeasureCase(
                mime = rm["mime"] as? String ?: return null,
                encoderName = rm["encoderName"] as? String,
                profile = (rm["profile"] as? Double)?.toInt(),
                bitDepth = (rm["bitDepth"] as? Double)?.toInt() ?: return null,
                width = (rm["width"] as? Double)?.toInt() ?: return null,
                height = (rm["height"] as? Double)?.toInt() ?: return null,
                fps = (rm["fps"] as? Double)?.toInt() ?: return null,
                rateMode = (rm["rateMode"] as? Double)?.toInt() ?: return null,
                requestedBps = (rm["requestedBps"] as? Double)?.toLong() ?: return null,
                uncapped = rm["uncapped"] as? Boolean ?: false,
                durationSec = rm["durationSec"] as? Double ?: return null,
                keyframeSec = (rm["keyframeSec"] as? Double)?.toFloat() ?: return null,
                content = rm["content"] as? String ?: return null,
            )
            val measuredBps = (rm["measuredBps"] as? Double)?.toLong()
            rows += MeasureResult(
                c = case,
                configureOk = rm["configureOk"] as? Boolean ?: false,
                errorCode = rm["errorCode"] as? String,
                encodedBytes = (rm["encodedBytes"] as? Double)?.toLong() ?: 0L,
                ptsDurationSec = rm["ptsDurationSec"] as? Double,
                measuredBps = measuredBps,
                iFrameBytes = (rm["iFrameBytes"] as? Double)?.toLong() ?: 0L,
                iFrameCount = (rm["iFrameCount"] as? Double)?.toInt() ?: 0,
                framesIn = (rm["framesIn"] as? Double)?.toInt() ?: 0,
                encoder = rm["encoder"] as? String,
                hardware = rm["hardware"] as? Boolean,
                wallMs = (rm["wallMs"] as? Double)?.toLong() ?: 0L,
            )
        }
        return CodecMeasureReport(fingerprint, model, incremental, startedMs, rows)
    }

    /** The stored file name: the fingerprint's SHA-256 hex (a stable, filesystem-safe key). */
    fun fileName(fingerprint: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".json"
    }
}
