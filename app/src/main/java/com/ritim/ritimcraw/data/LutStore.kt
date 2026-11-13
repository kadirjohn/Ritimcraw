package com.ritim.ritimcraw.data

import java.io.File
import java.security.MessageDigest

/**
 * One imported .cube 3D LUT binding (owner request 2026-11-10 section 3,
 * COL-07; managed interpretations 2026-11-13). The id is derived from the
 * file's fingerprint, so re-importing the same cube keeps the same id (the
 * SourceRepository convention). [name] is the display name: the parsed
 * TITLE from the .cube file when present (set after a successful native
 * probe), else the imported file's own fallback.
 *
 * A record is an IMMUTABLE binding: the blob (id) plus ONE interpretation
 * ([interp]). "Editing the interpretation" never mutates a record — it
 * creates a NEW record with the same blob and the new interpretation
 * ([LutStore.addBinding]); two interpretations of the same blob coexist
 * as two records sharing one copied file. A grade references the binding
 * as the PAIR (GradeK.lutId = the blob, GradeK.lutInterp = the
 * interpretation by value); a grade with a null lutInterp is a legacy
 * selection that develops through the COL-07 stage-24 path exactly as
 * before.
 */
data class LutInfo(
    val id: String,
    val name: String,
    /** The managed interpretation this record binds to the blob. */
    val interp: LutInterpK = LutInterpK.legacy(),
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
     *
     * The record binds [interp] (default: the legacy interpretation, so
     * pre-managed call sites keep compiling and behave exactly as before).
     * Dedupe is on the (blob, interpretation) PAIR: importing the same
     * bytes with the same interpretation returns the existing record;
     * importing them with a DIFFERENT interpretation creates a second
     * record on the same blob (see [addBinding]) — the first is never
     * removed or mutated.
     */
    fun import(uri: String, fallbackName: String, interp: LutInterpK = LutInterpK.legacy()): LutInfo {
        val fingerprint = fingerprintOf(uri)
        return addBinding(idFrom(fingerprint), fallbackName, uri, interp)
    }

    /**
     * The "edit interpretation" path on a library record: binds [interp]
     * to the ALREADY-IMPORTED blob [id] without re-importing — no SAF
     * uri, the copied cube in [lutsDir] is reused. Creates a NEW record
     * (never mutates), deduped on the (id, interp) pair; the new record
     * inherits the blob's existing display name.
     */
    fun bindInterp(id: String, interp: LutInterpK, fallbackName: String = id): LutInfo =
        addBinding(id, fallbackName, null, interp)

    /**
     * Binds [interp] to the blob [id] — the "edit interpretation" path:
     * creates a NEW record (never mutates an existing one), deduped on
     * the (id, interp) pair — binding an interpretation that already
     * exists on the blob returns that record unchanged. The blob file is
     * copied from [uri] only when this is the blob's first record; a
     * second interpretation on the same blob shares the existing copy.
     */
    private fun addBinding(
        id: String,
        fallbackName: String,
        uri: String?,
        interp: LutInterpK,
    ): LutInfo {
        val luts = load().toMutableList()
        val existing = luts.find { it.id == id && it.interp == interp }
        if (existing != null) return existing
        val target = File(lutsDir, "$id.cube")
        // First record on this blob: copy the bytes (import). bindInterp
        // (null uri) reuses the existing copy; if that copy is missing
        // the binding is still created, and pathFor's null keeps the
        // render the same as no LUT rather than failing it.
        if (!target.exists() && uri != null) {
            lutsDir.mkdirs()
            resolver.open(uri).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val existingName = luts.find { it.id == id }?.name
        val entry = LutInfo(
            id = id,
            name = (existingName ?: fallbackName.ifBlank { id }),
            interp = interp,
        )
        luts.add(entry)
        write(luts)
        return entry
    }

    fun all(): List<LutInfo> = load()

    fun get(id: String): LutInfo? = load().find { it.id == id }

    /**
     * Sets the display name (the parsed TITLE, or a user-facing fallback)
     * on EVERY record sharing the blob [id] — the name belongs to the
     * cube, not to one interpretation of it.
     */
    fun setTitle(id: String, name: String) {
        val luts = load().toMutableList()
        var changed = false
        for (i in luts.indices) {
            if (luts[i].id == id && luts[i].name != name) {
                luts[i] = luts[i].copy(name = name)
                changed = true
            }
        }
        if (changed) write(luts)
    }

    /**
     * Removes EVERY binding on the blob [id] and deletes the copied cube
     * file — the pre-managed behavior, kept for the existing call sites
     * (the list screen's delete). Per-record removal is [removeBinding].
     */
    fun remove(id: String) {
        write(load().filterNot { it.id == id })
        File(lutsDir, "$id.cube").delete()
    }

    /**
     * Removes ONE binding — the (id, interp) record — leaving any other
     * interpretation of the same blob untouched. The copied cube file is
     * deleted only when this was the blob's last record: two
     * interpretations share one file, so removing one keeps the other
     * usable.
     */
    fun removeBinding(id: String, interp: LutInterpK) {
        val luts = load()
        write(luts.filterNot { it.id == id && it.interp == interp })
        if (luts.count { it.id == id } == 1) {
            File(lutsDir, "$id.cube").delete()
        }
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

        /**
         * Renders the "luts" section: [{ "id": ..., "name": ..., "interp": {...} }, ...].
         * The interpretation object is omit-when-legacy — a record bound
         * to the legacy interpretation writes NONE of the interp keys, so
         * files this build writes for a pre-managed library stay
         * byte-identical to the old format.
         */
        internal fun renderLutsArray(luts: List<LutInfo>): String {
            val sb = StringBuilder()
            sb.append('[')
            luts.forEachIndexed { i, l ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(l.id))
                    .append(",\"name\":").append(ProjectJson.quote(l.name))
                if (l.interp != LutInterpK.legacy()) {
                    sb.append(",\"interp\":").append(l.interp.render())
                }
                sb.append('}')
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
                    // Records written before the managed model are
                    // {id, name} only: absent interp parses as the legacy
                    // interpretation — the migration rule. A record with
                    // a partial interp object still develops as legacy
                    // for anything unspecified (LutInterpK.parse falls
                    // back per field).
                    interp = LutInterpK.parse(m["interp"]) ?: LutInterpK.legacy(),
                )
            }
        }
    }
}
