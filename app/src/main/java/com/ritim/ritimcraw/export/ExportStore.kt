package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectFile
import com.ritim.ritimcraw.data.ProjectJson
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.RenderBackend
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
        // AtomicFiles via the single write gate (plan §6.5/F1-2): tmp +
        // fsync + atomic rename, and an observable no-op when the process
        // is read-only (a newer build's project file).
        ProjectFile.writeTextIfWritable(file, merged)
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
                // publishedUri (DONE rows open on tap, owner request §1):
                // omit-when-null for the same reason — files written before
                // this field stay byte-identical and old rows parse with a
                // null URI (the open path then falls back to the job's own
                // output file).
                if (j.publishedUri != null) {
                    sb.append(",\"publishedUri\":").append(ProjectJson.quote(j.publishedUri))
                }
                // outputName (display-derived export names, owner request
                // 2026-11-11 §2b): omit-when-null — files written before the
                // field stay byte-identical, and old rows parse with a null
                // name (the job then falls back to the legacy
                // <sourceId>-<target> name in ExportJob.fileName).
                if (j.outputName != null) {
                    sb.append(",\"outputName\":").append(ProjectJson.quote(j.outputName))
                }
                // frameIndex (RAW-11 Frame Picker): omit-when-default — a
                // zero index is the pre-picker norm (every legacy job and a
                // picker job on frame 0), so files written before the field
                // stay byte-identical and old rows parse with 0 (frame 0,
                // exactly what they always exported).
                if (j.frameIndex != 0) {
                    sb.append(",\"frameIndex\":").append(j.frameIndex)
                }
                // manualRotationDeg (owner request 2026-11-11 section 5):
                // omit-when-default — a never-rotated clip is the norm, so
                // files written before the field stay byte-identical and
                // old rows parse with 0 (no manual delta, exactly what
                // they always exported).
                if (j.manualRotationDeg != 0) {
                    sb.append(",\"manualRotationDeg\":").append(j.manualRotationDeg)
                }
                // renderBackend (GPU deliverable F, owner request
                // 2026-11-12 §10): omit-when-default — CPU is the norm for
                // every job queued before the GPU setting existed, so files
                // written before the field stay byte-identical and old rows
                // parse with CPU (the reference path, exactly what they
                // always ran).
                if (j.renderBackend != RenderBackend.DEFAULT) {
                    sb.append(",\"renderBackend\":\"").append(j.renderBackend.name).append('"')
                }
                // timeline (the timeline export): omit-when-null — every
                // per-clip job keeps the field absent, so files written
                // before the timeline export stay byte-identical and old
                // rows parse with null (a per-clip job, exactly what they
                // always were). The snapshot itself is a JSON STRING
                // (ProjectJson.quote carries the wrapped section).
                if (j.timeline != null) {
                    sb.append(",\"timeline\":").append(renderTimelineRef(j.timeline))
                }
                // resolutionW/H (export resolution, owner request
                // 2026-11-13): omit-when-null — Original (the source's
                // effective size) is the norm for every job queued before
                // the resolution picker, so files written before the
                // field stay byte-identical and old rows parse as null
                // (exactly what they always exported). The pair is
                // written together or not at all.
                if (j.resolutionW != null && j.resolutionH != null) {
                    sb.append(",\"resolutionW\":").append(j.resolutionW)
                    sb.append(",\"resolutionH\":").append(j.resolutionH)
                }
                // audioLink (QA-15 / EX-11 audio-choice snapshot):
                // omit-when-null — rows queued before the snapshot, and
                // every non-MP4 job, keep the field absent so files written
                // before it stay byte-identical; old rows parse as null
                // (the exporter's live-lookup fallback, their old behavior).
                if (j.audioLink != null) {
                    sb.append(",\"audioLink\":\"").append(j.audioLink.name).append('"')
                }
                // settings (E1 export choices): omit-when-null - every job
                // queued before E1 keeps the field absent, so files written
                // before it stay byte-identical and old rows parse as null
                // (today's fixed AVC behavior, exactly what they always ran).
                if (j.settings != null) {
                    sb.append(",\"settings\":").append(ExportSettingsWire.render(j.settings))
                }
                // report (E1b): omit-when-null, like settings - written only
                // for a DONE job that carried settings.
                if (j.report != null) {
                    sb.append(",\"report\":").append(ExportReportWire.render(j.report))
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /** The timeline snapshot row: name + the wrapped section, both quoted strings. */
        private fun renderTimelineRef(t: TimelineRef): String =
            "{\"name\":" + ProjectJson.quote(t.name) +
                ",\"projectJson\":" + ProjectJson.quote(t.projectJson) + "}"

        /**
         * The export snapshot's grade record: THE GradeStore WIRE renderer
         * (F1-3), never a local copy — the snapshot's wire format IS the
         * per-clip grade's wire format (the v2 twin; one render, one parse,
         * no drift). Old v1 rows already on disk parse unchanged through
         * the shared version-aware reader.
         */
        private fun renderSnapshot(g: GradeK): String = GradeStore.renderGradeWire(g)

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
                // settings (E1): absent = a pre-E1 row, null = the legacy
                // behavior. A PRESENT value this build cannot read (a newer
                // version, an unknown enum) must never fall back to the
                // legacy export silently: the row is kept but a job that
                // would still run is FAILED with the reason, so the user
                // re-queues it instead of getting a different file.
                val settingsParsed = if (m.containsKey("settings")) ExportSettingsWire.parse(m["settings"]) else null
                val unreadable = settingsParsed as? ExportSettingsWire.Parsed.Unsupported
                val parsedState = enumOrNullOr(idx, m["state"], State.QUEUED)
                val runnable = parsedState == State.QUEUED || parsedState == State.RUNNING
                ExportJob(
                    id = m["id"] as? String ?: return@mapIndexedNotNull null,
                    sourceId = m["sourceId"] as? String ?: return@mapIndexedNotNull null,
                    snapshot = parseSnapshot(m["snapshot"]),
                    // F1-6: the snapshot's v2 chain wire (in-memory only;
                    // null = the legacy fast path).
                    snapshotChainWire = parseSnapshotChain(m["snapshot"]),
                    target = enumOrNullOr(idx, m["target"], Target.DNG),
                    state = if (unreadable != null && runnable) State.FAILED else parsedState,
                    detail = if (unreadable != null && runnable) {
                        "Export settings could not be read (${unreadable.reason}) - recovery: queue the export again"
                    } else {
                        m["detail"] as? String
                    },
                    framesDone = (m["framesDone"] as? Double)?.toInt(),
                    framesTotal = (m["framesTotal"] as? Double)?.toInt(),
                    publishedUri = m["publishedUri"] as? String,
                    // Absent in rows queued before §2b: a null name falls
                    // back to the legacy <sourceId>-<target> file name.
                    outputName = m["outputName"] as? String,
                    // frameIndex (RAW-11 Frame Picker): absent in rows
                    // queued before the picker — 0 keeps the old behavior
                    // (frame 0) for every legacy row.
                    frameIndex = (m["frameIndex"] as? Double)?.toInt() ?: 0,
                    // manualRotationDeg (section 5): absent in rows queued
                    // before manual rotation — 0 keeps the old behavior
                    // (container orientation alone) for every legacy row.
                    manualRotationDeg = (m["manualRotationDeg"] as? Double)?.toInt() ?: 0,
                    // renderBackend (GPU deliverable F): absent in rows
                    // queued before the GPU setting — CPU keeps the old
                    // behavior (the reference path) for every legacy row;
                    // an unknown future value parses as CPU too
                    // (RenderBackend.fromWire).
                    renderBackend = RenderBackend.fromWire(m["renderBackend"] as? String),
                    // timeline (the timeline export): absent in every
                    // per-clip row — null keeps the old behavior. The
                    // parse is ROW-TOLERANT (TimelineRef.parseOrNull
                    // never throws): a malformed snapshot degrades this
                    // ONE row to a null instead of throwing out of the
                    // loop, because load()'s catch-all would otherwise
                    // empty the whole queue over one bad row. The
                    // exporter fails such a job honestly at run.
                    timeline = if (m["timeline"] == null) {
                        null
                    } else {
                        TimelineRef.parseOrNull(ProjectJson.renderRaw(m["timeline"]))
                    },
                    // resolutionW/H (export resolution): absent in rows
                    // queued before the picker — null keeps the old
                    // behavior (the source's effective size) for every
                    // legacy row. A half pair degrades to null rather
                    // than a guessed bound (the honesty rule: never
                    // guess); such rows fail their own gate at export.
                    resolutionW = (m["resolutionW"] as? Double)?.toInt(),
                    resolutionH = (m["resolutionH"] as? Double)?.toInt(),
                    // audioLink (QA-15 audio-choice snapshot): absent in
                    // rows queued before the snapshot — null keeps the old
                    // live-lookup behavior. An unknown future value also
                    // parses as null (never a guessed state: the live
                    // lookup is the honest fallback).
                    audioLink = (m["audioLink"] as? String)?.let { w ->
                        AudioLinkState.entries.firstOrNull { it.name == w }
                    },
                    settings = (settingsParsed as? ExportSettingsWire.Parsed.Ok)?.settings,
                    report = ExportReportWire.parse(m["report"]),
                )
            }
        }

        /**
         * The snapshot's grade record: the shared version-aware parser
         * ([GradeStore.mapOfGrade] — a v2 record through the F1-3 adapter,
         * a flat v1 record through the per-field fallbacks). NULL never
         * happens for a queued row this build understands; a record that
         * declares node/DRT usage decode refusal falls back to the neutral
         * grade honestly (the exporter's run-time gates pick it up row by
         * row, the settings-unreadable precedent).
         */
        private fun parseSnapshot(v: Any?): GradeK {
            val m = v as? Map<*, *> ?: return GradeK()
            return GradeStore.mapOfGrade(m) ?: GradeK()
        }

        /**
         * F1-6 (color model v2): the snapshot's v2 chain wire — the
         * record's OWN node chain/DRT output, decoded when the snapshot
         * declares one (a LEGACY record parses to null, the fast path).
         * The adapter's honest refusal ([GradeStore.mapOfGrade] null for
         * a nodes/DRT record) meant the GradeK snapshot alone could not
         * carry it; the wire rides the row IN MEMORY (never persisted —
         * `snapshot` IS the persisted record) so the exporter renders the
         * grade the user actually queued instead of the silent neutral
         * fallback the pre-F1-6 parse produced.
         */
        private fun parseSnapshotChain(v: Any?): FloatArray? {
            val m = v as? Map<*, *> ?: return null
            if (!m.containsKey("v2")) return null
            val grade = try {
                com.ritim.ritimcraw.data.grade2.ClipGradeV2.parse(m)
            } catch (t: Throwable) {
                return null
            }
            val nodes = com.ritim.ritimcraw.engine.GradeV2Usage.activeNodes(grade)
            if (nodes.isEmpty() &&
                grade.output.mode != com.ritim.ritimcraw.data.grade2.OutputTransformV2.MODE_OPENDRT
            ) {
                return null
            }
            // The node-LUT leg is the unwired follow-up: the wire refuses
            // it, and the export falls back to the neutral-snapshot
            // behavior it had before F1-6 rather than failing the job
            // (the LUT-carrying node grades are not reachable from the
            // UI until F1-8; the follow-up wires both together).
            return try {
                com.ritim.ritimcraw.engine.NodeChainWire.encode(grade, nodes)
            } catch (e: IllegalStateException) {
                null
            }
        }

        private inline fun <reified E : Enum<E>> enumOrNullOr(
            idx: Int,
            v: Any?,
            fallback: E,
        ): E =
            runCatching { enumValueOf<E>(v as String) }.getOrElse { fallback }
    }
}
