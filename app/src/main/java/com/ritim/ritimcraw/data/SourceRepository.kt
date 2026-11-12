package com.ritim.ritimcraw.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class SourceKind { MCRAW, ENCODED }

/**
 * The live status of a cataloged clip's backing document (spec IN-12 /
 * IN-16), decided by the collection refresh pass:
 *
 *  - [OK] — the document resolves and opens.
 *  - [LOST] — the SAF permission was revoked: the provider answers a
 *    metadata query with SecurityException. The file is probably still
 *    there; re-establishing the grant (pick the clip again) fixes it.
 *  - [GONE] — the document itself no longer resolves (deleted, moved, or
 *    the SSD it lived on is unplugged): the metadata query fails without
 *    a permission cause. Only re-linking to the same bytes (fingerprint
 *    match) or removing the row resolves a GONE clip.
 *
 * Persisted by name; records written before GONE existed (or by an older
 * build encountering the new value) parse through the default in
 * [SourceRepository.parseSources2] — OK, never a crash.
 */
enum class Access { OK, LOST, GONE }

/**
 * One imported clip. URIs are Strings (not android.net.Uri) so the model and
 * the repository logic stay testable on a plain JVM; the UI converts at the
 * edge (see [UriResolver.toAndroidUri]).
 */
data class MediaSource(
    val id: String,
    val uri: String,
    val fingerprint: String,
    val kind: SourceKind,
    val access: Access,
    /**
     * The clip's real file name (owner request 2026-11-11 §2b), from
     * OpenableColumns.DISPLAY_NAME at import. Blank when the resolver could
     * not name it (or the record predates the field) — display falls back to
     * [displayOrId] then, and the first open backfills it (see
     * [SourceRepository.backfillDisplayName]). The id stays
     * fingerprint-derived either way.
     */
    val displayName: String = "",
    /**
     * Clip stats for the format line (Format.formatClipLine). Zero/blank
     * until the engine pass probes the clip on device; the Library row
     * degrades to the kind line while they are unknown.
     */
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Int = 0,
    val cfa: String = "",
    /**
     * The clip's duration from its real frame timestamps (owner request
     * 2026-11-11 section 1): last pts minus first pts. 0 when unknown (or
     * a single-frame clip — the row hides the duration part then). Filled
     * by the thumbnail/stats generation pass; omitted from project.json
     * while 0 so schema-1 files stay byte-identical.
     */
    val durationUs: Long = 0L,
    /**
     * The user's manual rotation delta on top of the container orientation
     * (owner request 2026-11-11 section 5), normalized to 0..359 quarter
     * turns (RotationState.applyManual). 0 = never rotated — the preview,
     * exports and the clip-info sheet all treat the container value as the
     * whole story. Persisted only when non-zero so pre-rotation
     * project.json files stay byte-identical (the durationUs shape).
     */
    val manualRotationDeg: Int = 0,
    /**
     * The id of the imported sidecar .gcsv gyro log attached to this clip
     * (GYRO PHASE 5, stabilization): a GyroStore entry id, blank when no
     * log is attached. The attachment — not the log itself — lives on the
     * clip so one log can serve re-imports of the same clip and the
     * "gyros" library stays clip-agnostic. Persisted only when non-blank
     * so pre-gyro project.json files stay byte-identical (the
     * manualRotationDeg shape).
     */
    val gyroId: String = "",
) {
    /** True when the engine pass has filled in the format-line stats. */
    val hasStats: Boolean
        get() = (width > 0 && height > 0) || frameCount > 0 ||
            cfa.isNotBlank() || durationUs > 0L

    /**
     * What the UI shows as the clip's name (owner request §2b): the real
     * file name when it is known, the id when it is not — never a blank.
     */
    val displayOrId: String
        get() = if (displayName.isBlank()) id else displayName
}

/**
 * The storage operations the repository needs from the platform. The
 * production implementation wraps a ContentResolver; tests inject a fake.
 */
interface UriResolver {
    /** Opens the content for reading; throws SecurityException when access was revoked. */
    fun open(uri: String): InputStream

    /** File size in bytes, or -1 when unknown. */
    fun size(uri: String): Long

    /**
     * The document's real file name (owner request 2026-11-11 §2b), from
     * OpenableColumns.DISPLAY_NAME. Null when unknown — the repository
     * falls back to the id and backfills on the next import/open.
     */
    fun displayName(uri: String): String?

    /**
     * The collection refresh's CHEAP probe (spec IN-12): does the document
     * still resolve, and is the app's permission on it still granted?
     * One metadata query — never an open, never a frame decode.
     *
     *  - [DocumentProbe.AVAILABLE]: the provider answered the query — the
     *    document exists and the grant is intact.
     *  - [DocumentProbe.PERMISSION]: the provider threw SecurityException
     *    — the grant was revoked (the file may well still exist).
     *  - [DocumentProbe.GONE]: the query failed without a permission
     *    cause — the document was deleted or moved, or its storage is
     *    unmounted.
     */
    fun probe(uri: String): DocumentProbe
}

/**
 * The probe outcome for [UriResolver.probe]: AVAILABLE / PERMISSION / GONE,
 * mapped onto [Access.OK] / [Access.LOST] / [Access.GONE] by the refresh
 * pass. JVM-testable fakes decide it directly.
 */
enum class DocumentProbe { AVAILABLE, PERMISSION, GONE }

/**
 * The re-link identity check failed (spec IN-16): the picked document's
 * fingerprint does not match the missing source's. The repository changes
 * NOTHING in this case — the UI reports the mismatch honestly and the
 * user can pick again or remove the row.
 */
class RelinkMismatchException(
    val expectedFingerprint: String,
    val actualFingerprint: String,
) : Exception(
    "relink mismatch: expected $expectedFingerprint, picked $actualFingerprint",
)

/**
 * Pure cadence rules for the collection refresh pass (spec IN-12),
 * JVM-testable: WHICH records a Library open probes, so a big library
 * never pays a full sweep on every composition. Two groups:
 *
 *  - DEGRADED rows (LOST/GONE) are probed on EVERY open — they are the
 *    pass's whole purpose, they are few, and their state is cheap to
 *    re-check (and recoverable: a re-grant or a plugged-back SSD flips
 *    them back without any user action).
 *  - OK rows are probed on a ROTATING slice of [okBatch] per open: a
 *    clip deleted between visits surfaces within ceil(n / okBatch)
 *    opens, and the cursor persists across visits (rememberSaveable in
 *    AppNav) so consecutive opens advance through the list instead of
 *    re-probing the same head. Pull-to-refresh (the manual affordance)
 *    sweeps everything at once outside this cadence.
 */
object CollectionRefresh {

    /** How many OK records one Library open probes (the thumbnail pass's
     *  ≤3-per-open precedent, sized up slightly: a probe is one metadata
     *  query, much cheaper than a thumbnail develop). */
    const val OK_BATCH = 5

    /**
     * The ids one bounded pass probes: every degraded record, plus the
     * next [okBatch] OK records starting at [cursor] (wrapping — the
     * rotation visits every record eventually).
     */
    fun batch(sources: List<MediaSource>, cursor: Int, okBatch: Int = OK_BATCH): List<String> {
        if (okBatch <= 0) {
            return sources.filter { it.access != Access.OK }.map { it.id }
        }
        val ids = sources.filter { it.access != Access.OK }.map { it.id }.toMutableList()
        val ok = sources.filter { it.access == Access.OK }
        if (ok.isNotEmpty()) {
            val start = ((cursor % ok.size) + ok.size) % ok.size
            var i = start
            repeat(minOf(okBatch, ok.size)) {
                ids.add(ok[i].id)
                i = (i + 1) % ok.size
            }
        }
        return ids
    }

    /**
     * The next pass's cursor: advanced by the number of OK records this
     * pass probed, wrapping back to 0. Passed through AppNav as plain
     * rememberSaveable state; any value is safe (batch normalizes).
     */
    fun nextCursor(sources: List<MediaSource>, cursor: Int, okBatch: Int = OK_BATCH): Int {
        val okCount = sources.count { it.access == Access.OK }
        if (okCount == 0) return 0
        return (cursor + minOf(okBatch, okCount)) % okCount
    }
}

/**
 * Library of imported clips, persisted in `files/project.json` (schema 1,
 * same file the GradeStore lives in; kept in a distinct JSON section so the
 * two stores can split later without a migration).
 *
 * The clip id is derived from the fingerprint (SHA-256 of the first 1 MiB
 * plus the file size), so re-importing the same file keeps the same id and
 * its grade (sprint plan Task 4: reimport_same_file_keeps_id).
 *
 * refreshStatus() probes each record's document cheaply (one metadata
 * query — never an open) and marks a revoked permission Access.LOST or a
 * deleted/moved document Access.GONE without dropping the record (spec
 * IN-12/IN-14 / QA-23: the row stays, with a Grant access / Re-link
 * action — never a crash). relink() re-binds a degraded record to a
 * freshly picked document ONLY on a fingerprint match (spec IN-16).
 */
class SourceRepository(
    private val resolver: UriResolver,
    private val store: File,
) {

    fun import(uri: String, kind: SourceKind = SourceKind.MCRAW): MediaSource {
        val fingerprint = fingerprintOf(uri)
        // The clip's real file name (owner request 2026-11-11 §2b): null when
        // the resolver cannot name it — the id is the display fallback.
        val name = resolver.displayName(uri)
        val sources = load().toMutableList()
        val existing = sources.find { it.fingerprint == fingerprint }
        if (existing != null) {
            // Same file: keep the id (and its grade); refresh the uri in case
            // the picker returned a different document id for the same bytes.
            // A fresh display name backfills records imported before §2b.
            val updated = existing.copy(
                uri = uri,
                access = Access.OK,
                displayName = name ?: existing.displayName,
            )
            sources[sources.indexOf(existing)] = updated
            write(sources)
            return updated
        }
        val source = MediaSource(
            id = idFrom(fingerprint),
            uri = uri,
            fingerprint = fingerprint,
            kind = kind,
            access = Access.OK,
            displayName = name ?: "",
        )
        sources.add(source)
        write(sources)
        return source
    }

    fun all(): List<MediaSource> = load()

    fun get(id: String): MediaSource? = load().find { it.id == id }

    /**
     * Backfills the display name of a record imported before §2b (owner
     * request: old records show the id until the first open fills it in).
     * Returns the updated source, or the unchanged one when the record is
     * missing, already named, or the resolver still cannot name it.
     */
    fun backfillDisplayName(id: String): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = sources[index]
        if (current.displayName.isNotBlank()) return current
        val name = try {
            resolver.displayName(current.uri)
        } catch (e: Exception) {
            null
        }
        if (name.isNullOrBlank()) return current
        val updated = current.copy(displayName = name)
        sources[index] = updated
        write(sources)
        return updated
    }

    /**
     * Backfills the recorded stats of a record the generation pass probed
     * (owner request 2026-11-11 section 1: the Library row's resolution and
     * duration come from the clip's RECORDED frame data, never faked). No-op
     * when the record is missing, or when every probed value is already
     * present — a re-probe must not overwrite a filled record. Returns the
     * updated source, or the unchanged one.
     */
    fun backfillStats(
        id: String,
        width: Int,
        height: Int,
        frameCount: Int,
        cfa: String,
        durationUs: Long,
    ): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = sources[index]
        val updated = current.copy(
            width = if (width > 0) width else current.width,
            height = if (height > 0) height else current.height,
            frameCount = if (frameCount > 0) frameCount else current.frameCount,
            cfa = if (cfa.isNotBlank()) cfa else current.cfa,
            durationUs = if (durationUs > 0L) durationUs else current.durationUs,
        )
        if (updated == current) return current
        sources[index] = updated
        write(sources)
        return updated
    }

    /**
     * Re-checks the live status of the collection (spec IN-12) through the
     * CHEAP probe — one metadata query per source, never an open or a
     * frame decode (the thumbnail pass keeps the open-based precedent;
     * this pass must stay bounded and cheap for big libraries).
     *
     * A revoked permission marks [Access.LOST]; a deleted/moved document
     * marks [Access.GONE] — the two are kept distinct because they have
     * different recoveries (re-grant vs re-link). Both directions
     * recover: a re-granted or re-linked clip returns to OK, and a GONE
     * clip the provider re-resolves (SSD plugged back in) does too.
     *
     * [ids] bounds the pass: only the named records are probed (the
     * Library's per-open batch passes its rotating slice; null = all).
     * Returns the probed records with their updated status.
     */
    fun refreshStatus(ids: List<String>? = null): List<MediaSource> {
        val sources = load().toMutableList()
        val targets = if (ids == null) sources.indices.toList() else
            sources.indices.filter { sources[it].id in ids }
        for (i in targets) {
            val s = sources[i]
            val access = when (resolver.probe(s.uri)) {
                DocumentProbe.AVAILABLE -> Access.OK
                DocumentProbe.PERMISSION -> Access.LOST
                DocumentProbe.GONE -> Access.GONE
            }
            if (access != s.access) {
                sources[i] = s.copy(access = access)
            }
        }
        write(sources)
        return targets.map { sources[it] }
    }

    /**
     * Re-binds a degraded (LOST/GONE) source to a newly picked document
     * (spec IN-16): the picked file's fingerprint — the SAME identity the
     * import path trusts, SHA-256 of the first 1 MiB plus the size — is
     * compared against the missing source's recorded one. A MATCH rebinds
     * the uri and returns the updated source: same id, so the grade,
     * stats, gyro attachment and exports all survive (the whole point of
     * re-linking rather than re-importing). A MISMATCH throws
     * [RelinkMismatchException] and changes NOTHING — the catalog never
     * silently rebinds to a different file.
     *
     * Duration/frames are NOT re-verified here beyond the fingerprint:
     * the record's stats, gyro sync and exports were all derived from
     * these exact bytes at import time, so byte-identity (prefix hash +
     * exact size) is the identity contract — anything cheaper would be
     * weaker, anything more would re-open the clip this pass must not.
     */
    fun relink(id: String, uri: String): MediaSource {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) throw IllegalArgumentException("no source $id")
        val current = sources[index]
        val fingerprint = fingerprintOf(uri)
        if (fingerprint != current.fingerprint) {
            throw RelinkMismatchException(current.fingerprint, fingerprint)
        }
        // Same bytes: rebind the uri (the document id differs after a
        // move), clear the degraded status, and backfill a display name
        // the old location could not provide — exactly the import path's
        // existing-record branch.
        val updated = current.copy(
            uri = uri,
            access = Access.OK,
            displayName = resolver.displayName(uri) ?: current.displayName,
        )
        sources[index] = updated
        write(sources)
        return updated
    }

    fun remove(id: String) {
        write(load().filterNot { it.id == id })
    }

    /**
     * Sets the clip's manual rotation delta (owner request 2026-11-11
     * section 5): the value is normalized through RotationState BEFORE it
     * is stored, so the record can never hold 360 or -90. Returns the
     * updated source, or null when the record is missing (the rotate rows
     * hide for an unknown clip; the editor never rotates a phantom).
     */
    fun setManualRotation(id: String, rotationDeg: Int): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = sources[index].copy(
            manualRotationDeg = RotationState.applyManual(rotationDeg, 0)
        )
        sources[index] = updated
        write(sources)
        return updated
    }

    /**
     * Attaches (or detaches, [gyroId] = "") the imported sidecar .gcsv gyro
     * log (GYRO PHASE 5): the GyroStore entry id is stored on the clip after
     * the editor's sync probe validated the log against THIS clip. A null
     * return means the record is missing (the editor never attaches a gyro
     * to a phantom clip). No-op writes are skipped so re-attaching the same
     * log does not rewrite project.json.
     */
    fun setGyro(id: String, gyroId: String): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = sources[index]
        if (current.gyroId == gyroId) return current
        val updated = current.copy(gyroId = gyroId)
        sources[index] = updated
        write(sources)
        return updated
    }

    // ---- fingerprint ----

    /**
     * SHA-256 of the first 1 MiB plus the file size, hex. Reading only the
     * first MiB keeps import fast on multi-GB clips; the size disambiguates
     * files that share a MiB prefix.
     */
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

    internal fun idFrom(fingerprint: String): String = fingerprint.take(ID_HEX_CHARS)

    // ---- persistence (project.json, schema 1) ----

    private fun load(): List<MediaSource> {
        if (!store.exists()) return emptyList()
        return try {
            parseSources2(store.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(sources: List<MediaSource>) {
        store.parentFile?.mkdirs()
        val text = store.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        val merged = mergeInto(text, sources)
        val tmp = File(store.parentFile, store.name + ".tmp")
        tmp.writeText(merged, Charsets.UTF_8)
        if (!tmp.renameTo(store)) {
            store.writeText(merged, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FINGERPRINT_BYTES = 1024 * 1024
        const val ID_HEX_CHARS = 16

        /**
         * Renders the whole project.json with the given sources, preserving any
         * "grades" and "exports" sections already in [existingText]
         * (GradeStore and ExportStore write the same file; see
         * [ProjectJson.withSection]).
         */
        internal fun mergeInto(existingText: String?, sources: List<MediaSource>): String {
            return ProjectJson.withSection(
                existingText = existingText,
                section = "sources",
                renderedSection = renderSourcesArray(sources),
                version = SCHEMA_VERSION,
            )
        }

        internal fun renderSourcesArray(sources: List<MediaSource>): String {
            val sb = StringBuilder()
            sb.append('[')
            sources.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(jsonString(s.id))
                    .append(",\"uri\":").append(jsonString(s.uri))
                    .append(",\"fingerprint\":").append(jsonString(s.fingerprint))
                    .append(",\"kind\":\"").append(s.kind.name).append('"')
                    .append(",\"access\":\"").append(s.access.name).append('"')
                if (s.displayName.isNotBlank()) {
                    // The real file name (owner request §2b), omitted when
                    // blank so schema-1 files written before the field stay
                    // byte-identical (the id is the display fallback).
                    sb.append(",\"displayName\":").append(jsonString(s.displayName))
                }
                if (s.hasStats) {
                    // Format-line stats (filled by the engine pass); omitted
                    // entirely while unknown so older project.json files
                    // stay byte-identical.
                    sb.append(",\"width\":").append(s.width)
                        .append(",\"height\":").append(s.height)
                        .append(",\"frameCount\":").append(s.frameCount)
                        .append(",\"cfa\":").append(jsonString(s.cfa))
                }
                if (s.durationUs > 0L) {
                    // Duration from the real timestamps (owner request
                    // 2026-11-11 section 1); omitted while 0 so schema-1
                    // files stay byte-identical.
                    sb.append(",\"durationUs\":").append(s.durationUs)
                }
                if (s.manualRotationDeg != 0) {
                    // Manual rotation delta (owner request 2026-11-11
                    // section 5); omitted while 0 (never rotated) so
                    // pre-rotation files stay byte-identical.
                    sb.append(",\"manualRotationDeg\":").append(s.manualRotationDeg)
                }
                if (s.gyroId.isNotBlank()) {
                    // Attached sidecar gyro log (GYRO PHASE 5); omitted while
                    // blank (never attached) so pre-gyro files stay
                    // byte-identical.
                    sb.append(",\"gyroId\":").append(jsonString(s.gyroId))
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /** Re-renders a parsed grades object without interpreting it (kept for
         *  direct callers/tests; ProjectJson.renderRaw is the shared path). */
        private fun renderRawGrades(grades: Map<*, *>): String {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            for ((k, v) in grades) {
                if (!first) sb.append(',')
                first = false
                sb.append(jsonString(k.toString())).append(':')
                val m = v as? Map<*, *>
                if (m == null) {
                    sb.append("null")
                } else {
                    sb.append('{')
                    var f2 = true
                    for ((gk, gv) in m) {
                        if (!f2) sb.append(',')
                        f2 = false
                        sb.append(jsonString(gk.toString())).append(':')
                        when (gv) {
                            null -> sb.append("null")
                            is String -> sb.append(jsonString(gv))
                            is Boolean -> sb.append(gv)
                            is Int -> sb.append(gv)
                            is Long -> sb.append(gv)
                            is Double ->
                                if (gv == gv.toLong().toDouble()) sb.append(gv.toLong())
                                else sb.append(gv)
                            else -> sb.append(jsonString(gv.toString()))
                        }
                    }
                    sb.append('}')
                }
            }
            sb.append('}')
            return sb.toString()
        }

        internal fun parseSources2(text: String): List<MediaSource> {
            val p = JsonParser2(text)
            val root = p.parseValue()
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["sources"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                MediaSource(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    uri = m["uri"] as? String ?: return@mapNotNull null,
                    fingerprint = m["fingerprint"] as? String ?: "",
                    kind = runCatching { SourceKind.valueOf(m["kind"] as? String ?: "") }
                        .getOrDefault(SourceKind.MCRAW),
                    access = runCatching { Access.valueOf(m["access"] as? String ?: "") }
                        .getOrDefault(Access.OK),
                    // Absent in records written before §2b: blank display
                    // name, id shown until the first open backfills it.
                    displayName = m["displayName"] as? String ?: "",
                    // Stats are absent in schema-1 files written before the
                    // engine pass; JsonParser2 yields Double for numbers.
                    width = (m["width"] as? Double)?.toInt() ?: 0,
                    height = (m["height"] as? Double)?.toInt() ?: 0,
                    frameCount = (m["frameCount"] as? Double)?.toInt() ?: 0,
                    cfa = m["cfa"] as? String ?: "",
                    // Absent in records written before the thumbnail pass;
                    // 0 = unknown, the duration part of the row hides.
                    durationUs = (m["durationUs"] as? Double)?.toLong() ?: 0L,
                    // Absent in records written before the manual-rotation
                    // feature; 0 = never rotated (the container orientation
                    // alone decides the display).
                    manualRotationDeg = (m["manualRotationDeg"] as? Double)?.toInt() ?: 0,
                    // Absent in records written before GYRO PHASE 5; blank =
                    // no sidecar gyro log attached (stabilization stays a
                    // no-op).
                    gyroId = m["gyroId"] as? String ?: "",
                )
            }
        }

        private fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

/** Minimal JSON parser for the sources section (values: object/array/string/number/bool/null). */
internal class JsonParser2(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
    }

    fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) throw IllegalArgumentException("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else ->
                if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("unexpected char at $i")
        }
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
        i += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        i++
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected : at $i")
            i++
            out[key] = parseValue()
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == '}' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or } at $i")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return out }
        while (true) {
            out.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or ] at $i")
            }
        }
    }

    private fun parseString(): String {
        if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw IllegalArgumentException("unterminated string")
            when (val c = s[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape at $i")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
    }

    private fun parseNumber(): Double {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return s.substring(start, i).toDouble()
    }
}
