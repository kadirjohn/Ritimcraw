package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.LutInterpK
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
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /** The timeline snapshot row: name + the wrapped section, both quoted strings. */
        private fun renderTimelineRef(t: TimelineRef): String =
            "{\"name\":" + ProjectJson.quote(t.name) +
                ",\"projectJson\":" + ProjectJson.quote(t.projectJson) + "}"

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
                // DN-01..08: the denoise percents travel with the snapshot
                // for the same reason — the job exports what the user saw.
                .append(",\"noiseReduction\":").append(num(g.noiseReduction))
                .append(",\"chromaDenoise\":").append(num(g.chromaDenoise))
                .append(",\"temporalNr\":").append(g.temporalNr)
                .append(",\"temporalStrength\":").append(num(g.temporalStrength))
            // Stabilization (gyro Phase 5): omit-when-off, matching
            // GradeStore.renderGrade — a queued job with stabilization off
            // writes none of the keys, so old rows stay byte-identical and
            // parse with the off defaults (the job exports what the user
            // saw: no warp).
            if (g.stabilization) {
                sb.append(",\"stabilization\":").append(g.stabilization)
                    .append(",\"stabilizationMode\":").append(g.stabilizationMode)
                    .append(",\"stabilizationStrength\":").append(num(g.stabilizationStrength))
                    .append(",\"stabilizationZoomLimit\":").append(num(g.stabilizationZoomLimit))
                    .append(",\"stabilizationTrimMs\":").append(num(g.stabilizationTrimMs))
                    .append(",\"stabilizationFocalMm\":").append(num(g.stabilizationFocalMm))
            }
            // Managed LUT interpretation (owner request 2026-11-13):
            // omit-when-null — a queued job with no managed interpretation
            // writes none of the keys, so old rows stay byte-identical and
            // parse as the legacy path. A non-null interpretation is written
            // BY VALUE at enqueue: the job then exports with exactly the
            // interpretation the user saw, and a retry (requeued() keeps
            // the same snapshot) is invariant even if the library record
            // is later removed or edited (records are immutable; an edit
            // creates a new revision).
            if (g.lutInterp != null) {
                sb.append(",\"lutInterp\":").append(g.lutInterp.render())
            }
            sb.append('}')
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
                // JSON-null lutId parses as null (no LUT = stage 24
                // bypassed), absent lutStrength as 100 — the old look is
                // preserved exactly.
                lutId = m["lutId"] as? String,
                lutStrength = (m["lutStrength"] as? Double)?.toFloat() ?: 100f,
                // Denoise fields (DN-01..08) postdate older snapshots:
                // absent parses as 0 (both stages skipped) — the old look
                // is preserved exactly.
                noiseReduction = (m["noiseReduction"] as? Double)?.toFloat() ?: 0f,
                chromaDenoise = (m["chromaDenoise"] as? Double)?.toFloat() ?: 0f,
                // Temporal NR (DN-05..DN-09) postdates older snapshots: fall
                // back to off/0 so a queued job never gains temporal NR
                // behind the user's back; the old look is preserved exactly.
                temporalNr = m["temporalNr"] as? Boolean ?: false,
                temporalStrength = (m["temporalStrength"] as? Double)?.toFloat() ?: 0f,
                // Stabilization (gyro Phase 5) postdates older snapshots:
                // absent parses as the off defaults — a queued job never
                // gains a warp behind the user's back; the old look is
                // preserved exactly.
                stabilization = m["stabilization"] as? Boolean ?: false,
                stabilizationMode = (m["stabilizationMode"] as? Double)?.toInt() ?: 0,
                stabilizationStrength = (m["stabilizationStrength"] as? Double)?.toFloat() ?: 50f,
                stabilizationZoomLimit = (m["stabilizationZoomLimit"] as? Double)?.toFloat() ?: 1.25f,
                stabilizationTrimMs = (m["stabilizationTrimMs"] as? Double)?.toFloat() ?: 0f,
                stabilizationFocalMm = (m["stabilizationFocalMm"] as? Double)?.toFloat() ?: 0f,
                // Managed LUT interpretation postdates older snapshots:
                // absent or JSON-null parses as null (the legacy stage-24
                // path keyed on lutId alone) — the old look is preserved
                // exactly.
                lutInterp = LutInterpK.parse(m["lutInterp"]),
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
