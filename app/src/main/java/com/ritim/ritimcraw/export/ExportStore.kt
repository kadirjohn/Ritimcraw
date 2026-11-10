package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Queue of export jobs, persisted in the "exports" section of
 * `files/project.json` (schema 1, same file as GradeStore and
 * SourceRepository; merge-aware writes preserve "grades" and "sources" —
 * see [ProjectJson]).
 *
 * Order is queue order and is preserved across persistence: jobs render and
 * parse back in the same sequence. [add] appends, [update] replaces in place,
 * [clear] empties the queue. DONE/FAILED/CANCELLED jobs stay until cleared
 * (DESIGN.md Export job: "Jobs stay until cleared").
 *
 * Thread-safety mirrors the other stores: callers confine access to one
 * thread; writes are atomic (tmp + rename).
 */
class ExportStore(private val file: File) {

    private val lock = Any()

    fun all(): List<ExportJob> = synchronized(lock) { load() }

    /** Appends a job at the end of the queue and persists. */
    fun add(job: ExportJob) {
        synchronized(lock) {
            val jobs = load().toMutableList()
            jobs.add(job)
            write(jobs)
        }
    }

    /** Replaces the job with the same id, keeping its queue position. */
    fun update(job: ExportJob) {
        synchronized(lock) {
            val jobs = load().toMutableList()
            val i = jobs.indexOfFirst { it.id == job.id }
            if (i >= 0) {
                jobs[i] = job
            } else {
                jobs.add(job)
            }
            write(jobs)
        }
    }

    /** Re-queues a FAILED job with the same snapshot (Retry action). */
    fun retry(jobId: String) {
        synchronized(lock) {
            val jobs = load().toMutableList()
            val i = jobs.indexOfFirst { it.id == jobId }
            if (i >= 0) {
                jobs[i] = jobs[i].requeued()
                write(jobs)
            }
        }
    }

    /** Removes every job from the queue. */
    fun clear() {
        synchronized(lock) { write(emptyList()) }
    }

    // ---- persistence ----

    private fun load(): List<ExportJob> {
        if (!file.exists()) return emptyList()
        return try {
            parseExports(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; the queue restarts empty.
            emptyList()
        }
    }

    private fun write(jobs: List<ExportJob>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "exports",
            renderedSection = renderExports(jobs),
            version = SCHEMA_VERSION,
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(merged, StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Rename can fail across filesystems; fall back to copy+delete.
            file.writeText(merged, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        // ---- writer ----

        internal fun renderExports(jobs: List<ExportJob>): String {
            val sb = StringBuilder()
            sb.append('[')
            jobs.forEachIndexed { i, j ->
                if (i > 0) sb.append(',')
                sb.append('{')
                    .append("\"id\":").append(ProjectJson.quote(j.id))
                    .append(",\"sourceId\":").append(ProjectJson.quote(j.sourceId))
                    .append(",\"snapshot\":").append(renderSnapshot(j.snapshot))
                    .append(",\"target\":\"").append(j.target.name).append('"')
                    .append(",\"state\":\"").append(j.state.name).append('"')
                if (j.detail != null) {
                    sb.append(",\"detail\":").append(ProjectJson.quote(j.detail))
                }
                if (j.framesDone != null && j.framesTotal != null) {
                    sb.append(",\"framesDone\":").append(j.framesDone)
                    sb.append(",\"framesTotal\":").append(j.framesTotal)
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        private fun renderSnapshot(g: GradeK): String {
            val sb = StringBuilder()
            sb.append('{')
                .append("\"version\":").append(g.version)
                .append(",\"exposureEv\":").append(num(g.exposureEv))
                .append(",\"highlights\":").append(num(g.highlights))
                .append(",\"tempK\":").append(num(g.tempK))
                .append(",\"tint\":").append(num(g.tint))
                .append(",\"saturation\":").append(num(g.saturation))
                .append(",\"blackPoint\":").append(num(g.blackPoint))
                .append(",\"whitePoint\":").append(num(g.whitePoint))
                .append(",\"shadows\":").append(num(g.shadows))
                .append(",\"midtones\":").append(num(g.midtones))
                .append(",\"contrast\":").append(num(g.contrast))
                .append(",\"asShot\":").append(g.asShot)
                .append(",\"highlightReconstruction\":").append(g.highlightReconstruction)
                .append(",\"keepVignetting\":").append(g.keepVignetting)
                .append(",\"sharpness\":").append(num(g.sharpness))
                .append(",\"detail\":").append(num(g.detail))
                .append(",\"tonemap\":").append(g.tonemap)
                // COL-07: the export snapshot carries the LUT choice and
                // Strength too, so a queued job exports what the user saw.
                .append(",\"lutId\":").append(if (g.lutId == null) "null" else ProjectJson.quote(g.lutId))
                .append(",\"lutStrength\":").append(num(g.lutStrength))
                .append('}')
            return sb.toString()
        }

        private fun num(v: Float): String = v.toString()

        // ---- reader ----

        internal fun parseExports(text: String): List<ExportJob> {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["exports"] as? List<*> ?: return emptyList()
            return arr.mapIndexedNotNull { idx, item ->
                val m = item as? Map<*, *> ?: return@mapIndexedNotNull null
                ExportJob(
                    id = m["id"] as? String ?: return@mapIndexedNotNull null,
                    sourceId = m["sourceId"] as? String ?: return@mapIndexedNotNull null,
                    snapshot = parseSnapshot(m["snapshot"]),
                    target = enumOrNullOr(idx, m["target"], Target.DNG),
                    state = enumOrNullOr(idx, m["state"], State.QUEUED),
                    detail = m["detail"] as? String,
                    framesDone = (m["framesDone"] as? Double)?.toInt(),
                    framesTotal = (m["framesTotal"] as? Double)?.toInt(),
                )
            }
        }

        private fun parseSnapshot(v: Any?): GradeK {
            val m = v as? Map<*, *> ?: return GradeK()
            // Snapshots written before the tone controls lack the six float
            // fields; the neutral fallbacks keep the old look. Snapshots
            // written before the RAW toggles lack the three booleans: the two
            // stage toggles fall back to true (pre-toggle behavior), and
            // asShot is DERIVED from temp/tint neutrality so a manually
            // white-balanced old snapshot keeps its exact old look.
            val parsedTempK = (m["tempK"] as? Double)?.toFloat() ?: 5600f
            val parsedTint = (m["tint"] as? Double)?.toFloat() ?: 0f
            return GradeK(
                version = (m["version"] as? Double)?.toInt() ?: GradeK.SCHEMA_VERSION,
                exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                tempK = parsedTempK,
                tint = parsedTint,
                saturation = (m["saturation"] as? Double)?.toFloat() ?: 0f,
                blackPoint = (m["blackPoint"] as? Double)?.toFloat() ?: 0f,
                whitePoint = (m["whitePoint"] as? Double)?.toFloat() ?: 0f,
                shadows = (m["shadows"] as? Double)?.toFloat() ?: 0f,
                midtones = (m["midtones"] as? Double)?.toFloat() ?: 0f,
                contrast = (m["contrast"] as? Double)?.toFloat() ?: 0f,
                asShot = (m["asShot"] as? Boolean)
                    ?: (parsedTempK == 5600f && parsedTint == 0f),
                highlightReconstruction = m["highlightReconstruction"] as? Boolean ?: true,
                keepVignetting = m["keepVignetting"] as? Boolean ?: true,
                // Detail/tonemap (COL-15/COL-16) postdate older snapshots:
                // fall back to neutral (0), which the native pipeline
                // treats as a no-op — the old look is preserved exactly.
                sharpness = (m["sharpness"] as? Double)?.toFloat() ?: 0f,
                detail = (m["detail"] as? Double)?.toFloat() ?: 0f,
                tonemap = (m["tonemap"] as? Double)?.toInt() ?: 0,
                // LUT fields (COL-07) postdate older snapshots: absent or
                // JSON-null lutId parses as null (no LUT = stage 20
                // bypassed), absent lutStrength as 100 — the old look is
                // preserved exactly.
                lutId = m["lutId"] as? String,
                lutStrength = (m["lutStrength"] as? Double)?.toFloat() ?: 100f,
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNullOr(
            idx: Int,
            v: Any?,
            fallback: E,
        ): E =
            runCatching { enumValueOf<E>(v as String) }.getOrElse { fallback }
    }
}
