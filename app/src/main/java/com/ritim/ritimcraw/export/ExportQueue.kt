package com.ritim.ritimcraw.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial export queue runner (sprint plan Task 5 queue wiring).
 *
 * One job at a time, in queue order (the spec: exports are queued and run
 * serially — one motioncam decoder, one encoder session, one output file
 * being written at any moment). Jobs are read from the [ExportStore];
 * QUEUED jobs advance QUEUED -> RUNNING -> DONE/FAILED, and the runner
 * stops while the queue's head is not QUEUED (a RUNNING job set elsewhere
 * is someone else's; a FAILED/DONE job is terminal until cleared or
 * retried).
 *
 * Pure queue decisions + a thin runner loop, separated so the queue logic
 * is JVM-testable without Android:
 *  - [nextJob]: which queued job runs next (queue order, skip non-QUEUED).
 *  - [runAll]: the loop — for each QUEUED job: mark RUNNING, dispatch to
 *    the right exporter (DNG via [dng], MP4 via [mp4]), write the result
 *    back. A failure marks FAILED with the exporter's detail; the loop
 *    CONTINUES to the next job (one bad clip must not wedge the queue).
 *
 * The exporters are injected (the real ones need the native engine and
 * MediaCodec — device-gated; tests use fakes). The output file naming and
 * the source fd are supplied by the caller through [ExportIo]: the host
 * opens the SAF fd read-only and hands over the raw fd; the runner owns
 * its lifetime from there and closes it after the job (the native side
 * dups the fd per open, so closing the original is safe). The runner never
 * touches content resolvers itself. Finished exports are ALSO published to
 * the system media store through the injected [ExportPublisher] —
 * best-effort, after the terminal write, never failing the job.
 *
 * LIVE STATUS (owner finding 2026-11-11): the queue used to be visible
 * only when [runAll] returned — a RUNNING job showed "Queued" on screen
 * until the whole drain finished. The queue now bumps [revisions] on every
 * store write it makes (RUNNING start, each throttled progress write, the
 * terminal write); the UI collects that flow and re-reads the store, so
 * the persisted store stays the single source of truth and the flow is
 * only a "something changed" signal (it carries the write count, not job
 * data — the UI never trusts it over the store).
 *
 * KICK SAFETY: a kick used to RESTART the drain — a second enqueue while
 * one ran cancelled the in-flight runAll, and the restarted drain's
 * [recoverInterrupted] marked the LIVE RUNNING job FAILED. The guard in
 * [runAll] makes a kick while a drain is active a NO-OP: the active drain
 * loops until no job is QUEUED, so it picks up the new job itself; the
 * second call simply returns without touching RUNNING rows.
 */
class ExportQueue(
    private val store: ExportStore,
    private val dng: DngExporter,
    private val mp4: Mp4Exporter,
    private val io: ExportIo,
    private val publisher: ExportPublisher = ExportPublisher.None,
    private val progressClock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Store-write revision counter (live status signal): bumped on every
     * write the queue makes. The UI collects this and re-reads the store —
     * the flow is conflation-safe (a coalesced bump only means "re-read now")
     * and never carries job data itself.
     */
    private val revisions = MutableStateFlow(0)

    /** Read-only [revisions] for the UI to collect. */
    fun revisionFlow(): StateFlow<Int> = revisions

    /** Bumps [revisions] — every store write the queue makes calls this. */
    private fun publishRevision() {
        revisions.value = revisions.value + 1
    }

    /** The next job to run, or null when no job is QUEUED. */
    fun nextJob(): ExportJob? = store.all().firstOrNull { it.state == State.QUEUED }

    /**
     * Marks every persisted RUNNING job FAILED (owner finding 2026-11-10:
     * a crash killed the process with MP4 jobs RUNNING; on restart the
     * runner skips non-QUEUED jobs, so those rows displayed "Exporting…"
     * forever). A RUNNING row on drain can only be a dead process's — this
     * runner writes RUNNING and the terminal state inside one [runOne]
     * call, so a live job is never left RUNNING between drains. Idempotent;
     * returns how many rows were recovered.
     *
     * LIVE-SAFETY: [runAll] only calls this when NO drain is active (the
     * guard below), so a RUNNING row recovered here can never be the
     * active drain's own job.
     */
    fun recoverInterrupted(): Int {
        var recovered = 0
        while (true) {
            val stale = store.all().firstOrNull { it.state == State.RUNNING } ?: break
            store.update(
                stale.copy(
                    state = State.FAILED,
                    detail = ExportQueue.INTERRUPTED_DETAIL,
                    framesDone = null,
                    framesTotal = null,
                    startedAtMs = null,
                )
            )
            publishRevision()
            recovered++
        }
        return recovered
    }

    /** True while a [runAll] drain is active (the kick guard). */
    private val draining = AtomicBoolean(false)

    /**
     * Runs every currently-QUEUED job, one at a time, until the queue holds
     * no QUEUED job. Returns the number of jobs run (any outcome). A failed
     * job does not stop the loop — the next queued job still runs. Stale
     * RUNNING rows (a previous process died mid-export) are recovered to
     * FAILED first.
     *
     * KICK SAFETY (owner finding 2026-11-11): only ONE drain runs at a
     * time. A second call while one is active returns 0 immediately — it
     * does NOT cancel the active drain and does NOT recoverInterrupted
     * (which would mark the LIVE RUNNING job FAILED). The active drain
     * loops until no job is QUEUED, so it picks up anything enqueued
     * after it started by itself.
     */
    suspend fun runAll(): Int {
        if (!draining.compareAndSet(false, true)) {
            return 0 // a drain is already active; it will pick up new work
        }
        try {
            recoverInterrupted()
            var ran = 0
            while (true) {
                val job = nextJob() ?: break
                runOne(job)
                ran++
            }
            return ran
        } finally {
            draining.set(false)
        }
    }

    /** Runs one job end to end and writes the outcome back to the store. */
    internal suspend fun runOne(job: ExportJob) {
        // RUNNING first: the UI shows "Exporting…" while this runs. Progress
        // counts reset per run (a retry restarts from frame 0). startedAtMs
        // anchors the UI's linear ETA estimate (owner request §1). The
        // ProgressWriter is built from THIS row (not the QUEUED input) so
        // every throttled progress write carries the anchor — building it
        // from the input would null startedAtMs on the first report.
        val running = job.copy(
            state = State.RUNNING,
            detail = null,
            framesDone = null,
            framesTotal = null,
            startedAtMs = progressClock(),
        )
        store.update(running)
        publishRevision()

        // Progress callback the queue owns: the exporter reports frame
        // counts; writes are THROTTLED (project.json is rewritten on every
        // store write, so a per-frame write would hammer it — see
        // [ProgressWriter]) and the terminal write below always lands.
        val progress = ProgressWriter(store, running, progressClock, ::publishRevision)

        // The runner owns the source fd's lifetime: acquire once, close in
        // finally (the native side dups it per open, so closing after the
        // export is safe — one fd per job, never leaked).
        val fd = io.sourceFd(job)
        val result = try {
            when (job.target) {
                Target.DNG ->
                    dng.export(job, fd, io.outputFile(job, Target.DNG)) { done, total ->
                        progress.report(done, total)
                    }
                Target.MP4 ->
                    mp4.export(job, fd, io.outputFile(job, Target.MP4)) { done, total ->
                        progress.report(done, total)
                    }
            }
        } catch (e: Throwable) {
            // An exporter must never throw, but the queue must survive one
            // that does: FAILED with the cause, keep the queue moving.
            job.copy(
                state = State.FAILED,
                detail = e.message ?: "unknown failure",
                framesDone = null,
                framesTotal = null,
            )
        } finally {
            io.closeFd(fd)
        }

        // The job may have been retried/cleared while it ran: write by id
        // so the store keeps the freshest state for this id. The terminal
        // job carries null counts (progress only shows while RUNNING) and
        // no start time (the job is over; a DONE row never shows an ETA).
        store.update(
            result.copy(framesDone = null, framesTotal = null, startedAtMs = null)
        )
        publishRevision()

        // A finished export is ALSO published to the system media store
        // (owner finding 2026-11-10: exports were invisible outside the
        // app). Best-effort and AFTER the terminal write: the internal
        // copy is the source of truth, a publish failure never fails or
        // wedges the job, and the queue keeps moving either way.
        if (result.state == State.DONE) {
            try {
                val uri = publisher.publish(result, io.outputFile(result, result.target))
                if (uri != null) {
                    // DONE rows open on tap (owner request 2026-11-11 §1):
                    // store the published row's URI on the job — but only if
                    // the row is STILL a DONE row. Re-reading by id guards
                    // the clear-while-publishing race: a cleared queue must
                    // not resurrect the row (update() would re-add it), and
                    // a row that moved on keeps its fresh state. The current
                    // row is the base, so only the URI is stamped on.
                    val current = store.all().firstOrNull { it.id == result.id }
                    if (current != null && current.state == State.DONE) {
                        store.update(current.copy(publishedUri = uri))
                        publishRevision()
                    }
                }
            } catch (e: Throwable) {
                // Never propagate: publishing is additive.
            }
        }
    }

    companion object {

        /**
         * Failure detail for a stale RUNNING row (a dead process's export):
         * cause + recovery, per DESIGN.md error wording.
         */
        const val INTERRUPTED_DETAIL =
            "interrupted — the app closed during this export — " +
                "recovery: Retry re-queues the same grade snapshot"

        /**
         * Minimum milliseconds between two progress writes of one job (see
         * [ProgressWriter]): every store write rewrites project.json, so
         * per-frame writes would hammer it. The final progress write of a
         * job bypasses the throttle (and the terminal state write always
         * lands), so the persisted count never stalls mid-throttle.
         */
        const val PROGRESS_WRITE_MIN_INTERVAL_MS = 500L

        /**
         * Pure output-name decision (JVM-testable): the export lands in the
         * app's export directory as `<sourceId>-<target>.<ext>` — lowercase,
         * stable across retries (the same job retried overwrites its own
         * partial-free output). Since §2b this is the FALLBACK name, used by
         * rows queued before display names existed and by clips the resolver
         * could not name — see [displayOutputName].
         */
        fun outputName(sourceId: String, target: Target): String =
            sourceId + "-" + target.name.lowercase() + "." + when (target) {
                Target.DNG -> "dng"
                Target.MP4 -> "mp4"
            }

        /**
         * Characters that never belong in a file name (owner request §2b:
         * invalid characters are cleaned from the display name): path
         * separators and the characters MediaStore/file systems on the
         * device treat as hostile. Control characters are cleaned by the
         * code range check, not this string.
         */
        private const val INVALID_NAME_CHARS = "\\/:*?\"<>|"

        /**
         * Byte budget for the display-derived base name: the base plus the
         * target extension must stay under the 255-byte file-name limit even
         * for multi-byte UTF-8 names.
         */
        private const val MAX_NAME_BASE_BYTES = 200

        /**
         * Pure display-derived output-name decision (owner request
         * 2026-11-11 §2b: the export's file name comes from the clip's real
         * file name, not the fingerprint id). The display name is cleaned —
         * its OWN extension is swapped for the target's ("Sunset.raw" ->
         * "Sunset.dng"), invalid filename characters are removed, and the
         * result is trimmed — and returned, or NULL when nothing usable
         * remains (blank/whitespace-only display name): the caller then
         * leaves [ExportJob.outputName] unset and the job falls back to the
         * legacy [outputName].
         *
         * Same-named jobs are disambiguated by JOB IDENTITY, not by the
         * name: the internal copy lives in the per-job directory
         * files/exports/&lt;jobId&gt;/, and a MediaStore DISPLAY_NAME collision
         * is auto-uniquified by the platform with a suffix — the DONE row
         * opens via the job's own [ExportJob.publishedUri], so a suffixed
         * rename never misroutes an open.
         */
        fun displayOutputName(display: String, target: Target): String? {
            // Swap the source's extension for the target's: the output wears
            // the format it is written in, not the format it came from.
            val base = display.substringBeforeLast('.', display)
            val sb = StringBuilder(base.length)
            for (c in base) {
                when {
                    c.code < 0x20 -> {} // control characters: removed
                    INVALID_NAME_CHARS.indexOf(c) >= 0 -> {} // hostile: removed
                    else -> sb.append(c)
                }
            }
            var cleaned = sb.toString().trim(' ', '.')
            // Keep the base inside a sane byte budget (multi-byte UTF-8
            // names): the base + extension must fit a file-system name.
            // Code-point-wise, never char-wise: a 4-byte character is a
            // surrogate PAIR of chars, and cutting between the two halves
            // would write a broken name.
            val capped = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < cleaned.length) {
                val cp = cleaned.codePointAt(i)
                val n = when (cp) {
                    in 0..0x7F -> 1
                    in 0x80..0x7FF -> 2
                    in 0x800..0xFFFF -> 3
                    else -> 4
                }
                if (bytes + n > MAX_NAME_BASE_BYTES) break
                bytes += n
                capped.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            cleaned = capped.toString()
            if (cleaned.isBlank()) return null
            return cleaned + "." + when (target) {
                Target.DNG -> "dng"
                Target.MP4 -> "mp4"
            }
        }
    }
}

/**
 * Throttled progress persistence for one running job (JVM-testable via an
 * injected clock). Exporters report every frame; this class decides which
 * reports become store writes:
 *  - the FIRST report always writes (the RUNNING row gains its frame counts
 *    immediately, so the UI can show "0/310 frames" rather than a bare
 *    "Exporting…"),
 *  - later reports write only after [ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS]
 *    since the last WRITE (not the last report),
 *  - the job's terminal write (done by the queue) always lands and clears
 *    the counts.
 *
 * The filter is write-side only — the exporter still hears every frame; the
 * persisted value may lag the real count by up to one throttle window.
 * Every write also bumps the queue's revision signal ([onWrite]) so the
 * UI re-reads the store live while the job runs.
 */
internal class ProgressWriter(
    private val store: ExportStore,
    private val job: ExportJob,
    private val clock: () -> Long,
    private val onWrite: () -> Unit = {},
) {
    private var lastWriteMs = -1L

    /** True when the report became a store write (tests assert on this). */
    fun report(framesDone: Int, framesTotal: Int): Boolean {
        val now = clock()
        if (lastWriteMs >= 0 &&
            now - lastWriteMs < ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS
        ) {
            return false
        }
        lastWriteMs = now
        store.update(
            job.copy(state = State.RUNNING, framesDone = framesDone, framesTotal = framesTotal)
        )
        onWrite()
        return true
    }
}

/**
 * What the queue runner needs from its host: the source fd (SAF, opened
 * read-only by the host and handed over raw) and the output file location.
 * An interface so tests supply fakes and the app wires the real resolver.
 */
interface ExportIo {
    /** A read-only fd for the job's source clip, or -1 when unavailable. */
    fun sourceFd(job: ExportJob): Int

    /** Closes an fd [sourceFd] returned (no-op for -1). */
    fun closeFd(fd: Int)

    /** The output file the exporter writes (staged as .partial first). */
    fun outputFile(job: ExportJob, target: Target): File
}
