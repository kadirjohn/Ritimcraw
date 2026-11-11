package com.ritim.ritimcraw.data

import java.io.File
import java.security.MessageDigest

/**
 * One imported sidecar .gcsv gyro log (GYRO PHASE 5, stabilization).
 * The id is derived from the file's fingerprint, so re-importing the same
 * log keeps the same id (the SourceRepository convention). [name] is the
 * display name: the picker-side fallback (the picked document's own name).
 */
data class GyroInfo(
    val id: String,
    val name: String,
)

/**
 * Project-wide library of imported .gcsv gyro logs, persisted in the "gyros"
 * section of `files/project.json` (schema 1, same file as the other stores;
 * merge-aware writes preserve "grades"/"sources"/"exports"/"luts" — see
 * [ProjectJson]).
 *
 * Import copies the picked SAF document's bytes into the app-private
 * [gyrosDir] as `<id>.gcsv`, so no persistable URI permission is needed —
 * the copy is durable (SAF URIs are not stable across reboots, and the
 * native parseGcsv needs a real filesystem path). The id is the fingerprint
 * (SHA-256 of the first 1 MiB plus the file size, the SourceRepository
 * convention) truncated to 16 hex chars; importing the same bytes twice
 * returns the SAME entry (fingerprint dedupe) and never duplicates a file.
 *
 * The per-clip attachment lives in the source record
 * (MediaSource.gyroId, "sources" section), not here — this store is only
 * the library of logs. Sync/validity is deliberately NOT checked here: the
 * JVM tests run without librawedit_jni.so. The screen probes the imported
 * file through NativeSource.jniGyroSyncProbe after import (validating the
 * .gcsv and reading the anchored-sync estimate for the clip) and removes
 * the entry when the probe fails; [pathFor] is the pure directory lookup
 * the editor uses to turn a source's gyroId into a file path.
 *
 * Thread-safety mirrors the other stores: callers confine access to one
 * thread; writes are atomic (tmp + rename).
 */
class GyroStore(
    private val resolver: UriResolver,
    private val file: File,
    private val gyrosDir: File,
) {

    /**
     * Imports the .gcsv at [uri]: fingerprint dedupe (same bytes = same
     * entry, uri-independent), copy into [gyrosDir], persist the "gyros"
     * section. Returns the entry.
     */
    fun import(uri: String, fallbackName: String): GyroInfo {
        val fingerprint = fingerprintOf(uri)
        val existing = all().find { it.id == idFrom(fingerprint) }
        if (existing != null) return existing
        val id = idFrom(fingerprint)
        val target = File(gyrosDir, "$id.gcsv")
        gyrosDir.mkdirs()
        resolver.open(uri).use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        val entry = GyroInfo(id = id, name = fallbackName.ifBlank { id })
        val gyros = load().toMutableList()
        gyros.add(entry)
        write(gyros)
        return entry
    }

    fun all(): List<GyroInfo> = load()

    fun get(id: String): GyroInfo? = load().find { it.id == id }

    /** Removes the entry and deletes the copied .gcsv file. */
    fun remove(id: String) {
        write(load().filterNot { it.id == id })
        File(gyrosDir, "$id.gcsv").delete()
    }

    /**
     * The copied .gcsv's path for a source's gyroId, or null when no gyro
     * is attached or the copy is gone (a missing file reads as no gyro —
     * stabilization degrades to a no-op rather than failing the clip).
     */
    fun pathFor(gyroId: String?): String? {
        if (gyroId == null) return null
        return File(gyrosDir, "$gyroId.gcsv").takeIf { it.exists() }?.path
    }

    // ---- fingerprint (SourceRepository convention) ----

    private fun fingerprintOf(uri: String): String {
        val head = ByteArray(FINGERPRINT_BYTES)
        var read = 0
        resolver.open(uri).use { input ->
            while (read < FINGERPRINT_BYTES) {
                val n = input.read(head, read, FINGERPRINT_BYTES - read)
                if (n < 0) break
                read += n
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(head, 0, read)
        md.update(resolver.size(uri).toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun idFrom(fingerprint: String): String = fingerprint.take(ID_HEX_CHARS)

    // ---- persistence (project.json, schema 1) ----

    private fun load(): List<GyroInfo> {
        if (!file.exists()) return emptyList()
        return try {
            parseGyros(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(gyros: List<GyroInfo>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "gyros",
            renderedSection = renderGyrosArray(gyros),
            version = SCHEMA_VERSION,
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(merged, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(merged, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FINGERPRINT_BYTES = 1024 * 1024
        const val ID_HEX_CHARS = 16

        /** Renders the "gyros" section: [{ "id": ..., "name": ... }, ...]. */
        internal fun renderGyrosArray(gyros: List<GyroInfo>): String {
            val sb = StringBuilder()
            sb.append('[')
            gyros.forEachIndexed { i, g ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(g.id))
                    .append(",\"name\":").append(ProjectJson.quote(g.name))
                    .append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        internal fun parseGyros(text: String): List<GyroInfo> {
            val p = JsonParser(text)
            val root = p.parseValue()
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["gyros"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                GyroInfo(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    name = m["name"] as? String ?: "",
                )
            }
        }
    }
}
