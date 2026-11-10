package com.ritim.ritimcraw.export

import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
 * touches content resolvers itself.
 */
class ExportQueue(
    private val store: ExportStore,
    private val dng: DngExporter,
    private val mp4: Mp4Exporter,
    private val io: ExportIo,
    private val progressClock: () -> Long = System::currentTimeMillis,
) {

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
                )
            )
            recovered++
        }
        return recovered
    }

    /**
     * Runs every currently-QUEUED job, one at a time, until the queue holds
     * no QUEUED job. Returns the number of jobs run (any outcome). A failed
     * job does not stop the loop — the next queued job still runs. Stale
     * RUNNING rows (a previous process died mid-export) are recovered to
     * FAILED first.
     */
    suspend fun runAll(): Int {
        recoverInterrupted()
        var ran = 0
        while (true) {
            val job = nextJob() ?: break
            runOne(job)
            ran++
        }
        return ran
    }

    /** Runs one job end to end and writes the outcome back to the store. */
    internal suspend fun runOne(job: ExportJob) {
        // RUNNING first: the UI shows "Exporting…" while this runs. Progress
        // counts reset per run (a retry restarts from frame 0).
        store.update(
            job.copy(state = State.RUNNING, detail = null, framesDone = null, framesTotal = null)
        )

        // Progress callback the queue owns: the exporter reports frame
        // counts; writes are THROTTLED (project.json is rewritten on every
        // store write, so a per-frame write would hammer it — see
        // [ProgressWriter]) and the terminal write below always lands.
        val progress = ProgressWriter(store, job, progressClock)

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
        // job carries null counts (progress only shows while RUNNING).
        store.update(result.copy(framesDone = null, framesTotal = null))
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
         * partial-free output).
         */
        fun outputName(sourceId: String, target: Target): String =
            sourceId + "-" + target.name.lowercase() + "." + when (target) {
                Target.DNG -> "dng"
                Target.MP4 -> "mp4"
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
 */
internal class ProgressWriter(
    private val store: ExportStore,
    private val job: ExportJob,
    private val clock: () -> Long,
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
