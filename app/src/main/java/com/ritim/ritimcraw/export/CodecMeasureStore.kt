package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.AtomicFiles
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The measurement report's on-disk home (plan §5):
 * `files/codec-measure/<fingerprint-hash>.json` — deliberately NOT in
 * project.json (debug measurement data never touches the project file).
 *
 * One file per ROM fingerprint; a new run on the same build REPLACES the
 * old one (plan §5: no run history, no cache expiry — a ROM change
 * changes the fingerprint and therefore the file name, so old data stays
 * under its own key). Writes are the store pattern: tmp + fsync + atomic
 * rename, an interrupted run never tears the previous file.
 *
 * Debug-only by call site (the Settings debug section and the measurement
 * runner are the only creators); the store itself carries no UI.
 */
class CodecMeasureStore(private val dir: File) {

    /** The latest report on THIS build (Build fingerprint), or null when never run. */
    fun latest(fingerprint: String): CodecMeasureReport? {
        val f = File(dir, CodecMeasureWire.fileName(fingerprint))
        if (!f.exists()) return null
        return runCatching { CodecMeasureWire.parse(f.readText(StandardCharsets.UTF_8)) }.getOrNull()
    }

    /**
     * Persists [report] as this fingerprint's one file (replacing any
     * previous run's). A partial table is honest data (plan §4.5: a
     * cancelled run keeps what it measured) — the caller decides what a
     * run holds, the store only writes it durably.
     */
    fun save(report: CodecMeasureReport) {
        dir.mkdirs()
        val f = File(dir, CodecMeasureWire.fileName(report.fingerprint))
        AtomicFiles.writeText(f, CodecMeasureWire.render(report))
    }

    /** The whole JSON as one string (the debug screen's copy/share payload). */
    fun readRaw(fingerprint: String): String? {
        val f = File(dir, CodecMeasureWire.fileName(fingerprint))
        return if (f.exists()) runCatching { f.readText(StandardCharsets.UTF_8) }.getOrNull() else null
    }
}
