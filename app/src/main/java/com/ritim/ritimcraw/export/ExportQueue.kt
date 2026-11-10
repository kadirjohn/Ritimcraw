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
) {

    /** The next job to run, or null when no job is QUEUED. */
    fun nextJob(): ExportJob? = store.all().firstOrNull { it.state == State.QUEUED }

    /**
     * Runs every currently-QUEUED job, one at a time, until the queue holds
     * no QUEUED job. Returns the number of jobs run (any outcome). A failed
     * job does not stop the loop — the next queued job still runs.
     */
    suspend fun runAll(): Int {
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
        // RUNNING first: the UI shows "Exporting…" while this runs.
        store.update(job.copy(state = State.RUNNING, detail = null))

        // The runner owns the source fd's lifetime: acquire once, close in
        // finally (the native side dups it per open, so closing after the
        // export is safe — one fd per job, never leaked).
        val fd = io.sourceFd(job)
        val result = try {
            when (job.target) {
                Target.DNG ->
                    dng.export(job, fd, io.outputFile(job, Target.DNG))
                Target.MP4 ->
                    mp4.export(job, fd, io.outputFile(job, Target.MP4))
            }
        } catch (e: Throwable) {
            // An exporter must never throw, but the queue must survive one
            // that does: FAILED with the cause, keep the queue moving.
            job.copy(state = State.FAILED, detail = e.message ?: "unknown failure")
        } finally {
            io.closeFd(fd)
        }

        // The job may have been retried/cleared while it ran: write by id
        // so the store keeps the freshest state for this id.
        store.update(result)
    }

    companion object {

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
