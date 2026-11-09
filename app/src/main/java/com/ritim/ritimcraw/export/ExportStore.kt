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
                )
            }
        }

        private fun parseSnapshot(v: Any?): GradeK {
            val m = v as? Map<*, *> ?: return GradeK()
            return GradeK(
                version = (m["version"] as? Double)?.toInt() ?: GradeK.SCHEMA_VERSION,
                exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                tempK = (m["tempK"] as? Double)?.toFloat() ?: 5600f,
                tint = (m["tint"] as? Double)?.toFloat() ?: 0f,
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
