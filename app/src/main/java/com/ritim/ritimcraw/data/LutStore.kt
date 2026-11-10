package com.ritim.ritimcraw.data

import java.io.File
import java.security.MessageDigest

/**
 * One imported .cube 3D LUT (owner request 2026-11-10 section 3, COL-07).
 * The id is derived from the file's fingerprint, so re-importing the same
 * cube keeps the same id (the SourceRepository convention). [name] is the
 * display name: the parsed TITLE from the .cube file when present (set
 * after a successful native probe), else the imported file's own fallback.
 */
data class LutInfo(
    val id: String,
    val name: String,
)

/**
 * Project-wide library of imported .cube 3D LUTs, persisted in the "luts"
 * section of `files/project.json` (schema 1, same file as the other stores;
 * merge-aware writes preserve "grades"/"sources"/"exports" — see
 * [ProjectJson]).
 *
 * Import copies the picked SAF document's bytes into the app-private
 * [lutsDir] as `<id>.cube`, so no persistable URI permission is needed —
 * the copy is durable. The id is the fingerprint (SHA-256 of the first
 * 1 MiB plus the file size, the SourceRepository convention) truncated to
 * 16 hex chars; importing the same bytes twice returns the SAME entry
 * (fingerprint dedupe) and never duplicates a file.
 *
 * Selection and Strength are NOT stored here — they live per clip in the
 * grade (GradeK.lutId / GradeK.lutStrength, "grades" section), saved
 * separately per the request. This store is only the library of cubes.
 *
 * Native parsing is deliberately NOT done here: the JVM tests run without
 * librawedit_jni.so. The screen probes the imported file through
 * NativeSource.jniLutProbe after import (validating the .cube and reading
 * its TITLE) and removes the entry when the probe fails; [pathFor] is the
 * pure directory lookup both the preview screen and the MP4 export
 * resolver use to turn a grade's lutId into a file path.
 *
 * Thread-safety mirrors the other stores: callers confine access to one
 * thread; writes are atomic (tmp + rename).
 */
class LutStore(
    private val resolver: UriResolver,
    private val file: File,
    private val lutsDir: File,
) {

    /**
     * Imports the .cube at [uri]: fingerprint dedupe (same bytes = same
     * entry, uri-independent), copy into [lutsDir], persist the "luts"
     * section. Returns the entry. The display name starts as the fallback
     * name; the screen re-probes and calls [setTitle] with the parsed
     * TITLE when the native parse succeeds.
     */
    fun import(uri: String, fallbackName: String): LutInfo {
        val fingerprint = fingerprintOf(uri)
        val existing = all().find { it.id == idFrom(fingerprint) }
        if (existing != null) return existing
        val id = idFrom(fingerprint)
        val target = File(lutsDir, "$id.cube")
        lutsDir.mkdirs()
        resolver.open(uri).use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        val entry = LutInfo(id = id, name = fallbackName.ifBlank { id })
        val luts = load().toMutableList()
        luts.add(entry)
        write(luts)
        return entry
    }

    fun all(): List<LutInfo> = load()

    fun get(id: String): LutInfo? = load().find { it.id == id }

    /** Sets the display name (the parsed TITLE, or a user-facing fallback). */
    fun setTitle(id: String, name: String) {
        val luts = load().toMutableList()
        val i = luts.indexOfFirst { it.id == id }
        if (i >= 0 && luts[i].name != name) {
            luts[i] = luts[i].copy(name = name)
            write(luts)
        }
    }

    /** Removes the entry and deletes the copied cube file. */
    fun remove(id: String) {
        write(load().filterNot { it.id == id })
        File(lutsDir, "$id.cube").delete()
    }

    /**
     * The copied cube's path for a grade's lutId, or null when there is no
     * LUT selected or the copy is gone (a missing file renders as no LUT —
     * the native bridge treats a null/empty path the same way, so the old
     * no-LUT look is preserved rather than failing the render).
     */
    fun pathFor(lutId: String?): String? {
        if (lutId == null) return null
        return File(lutsDir, "$lutId.cube").takeIf { it.exists() }?.path
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

    private fun load(): List<LutInfo> {
        if (!file.exists()) return emptyList()
        return try {
            parseLuts(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(luts: List<LutInfo>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "luts",
            renderedSection = renderLutsArray(luts),
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

        /** Renders the "luts" section: [{ "id": ..., "name": ... }, ...]. */
        internal fun renderLutsArray(luts: List<LutInfo>): String {
            val sb = StringBuilder()
            sb.append('[')
            luts.forEachIndexed { i, l ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(l.id))
                    .append(",\"name\":").append(ProjectJson.quote(l.name))
                    .append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        internal fun parseLuts(text: String): List<LutInfo> {
            val p = JsonParser(text)
            val root = p.parseValue()
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["luts"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                LutInfo(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    name = m["name"] as? String ?: "",
                )
            }
        }
    }
}
