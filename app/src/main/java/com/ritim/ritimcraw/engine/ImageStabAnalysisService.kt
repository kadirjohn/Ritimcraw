package com.ritim.ritimcraw.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Why a running analysis stands down (the Paused status carries it). */
enum class ImageStabPause { EXPORT, PLAYBACK, THERMAL }

/** One clip's analysis state (the S5 card's state machine reads this). */
sealed interface ImageStabStatus {
    /** No analysis. [hasPartial]: a checkpoint exists and a run resumes from it. */
    data class Idle(val hasPartial: Boolean = false) : ImageStabStatus
    data object Queued : ImageStabStatus
    data class Running(val done: Long, val total: Long) : ImageStabStatus
    data class Paused(val reason: ImageStabPause, val done: Long, val total: Long) : ImageStabStatus
    data object Done : ImageStabStatus
    data class Failed(val code: Int) : ImageStabStatus
}

/**
 * The pure run/stand-down decision (owner decision 2026-11-23, plan section
 * 6 item 9): the analysis pauses while an export runs or the editor plays,
 * and stops while the device's thermal headroom is high; it resumes from
 * its checkpoint. [headroom] is PowerManager.getThermalHeadroom (1.0 = the
 * throttling threshold); null = unknown = no thermal objection. The
 * thermal limit has hysteresis so a reading hovering at the edge does not
 * flap between runs.
 */
object ImageStabRunPolicy {
    const val THERMAL_PAUSE_AT = 0.85f
    const val THERMAL_RESUME_BELOW = 0.70f

    /** null = run; otherwise the reason to stand down (export > playback > thermal). */
    fun decide(
        exporting: Boolean,
        playing: Boolean,
        headroom: Float?,
        thermalPaused: Boolean,
    ): ImageStabPause? = when {
        exporting -> ImageStabPause.EXPORT
        playing -> ImageStabPause.PLAYBACK
        headroom != null && !headroom.isNaN() &&
            (headroom >= THERMAL_PAUSE_AT || (thermalPaused && headroom >= THERMAL_RESUME_BELOW)) ->
            ImageStabPause.THERMAL
        else -> null
    }
}

/** The live signals the policy reads. */
interface ImageStabSignals {
    fun exporting(): Boolean
    fun playing(): Boolean
    fun thermalHeadroom(): Float?
}

/** The native analysis door, as an interface so the service is JVM-testable. */
interface ImageStabBackend {
    fun jobNew(): Int
    fun jobFree(job: Int)
    fun jobCancel(job: Int)
    fun jobProgress(job: Int): LongArray?

    /** Blocking. 0 Done, 1 Cancelled (checkpoint kept), 2 Failed, 3 I/O failure. */
    fun analyze(fd: Int, finalFile: String, job: Int, threads: Int): Int
}

internal object NativeImageStabBackend : ImageStabBackend {
    override fun jobNew(): Int = NativeSource.jniImageStabJobNew()
    override fun jobFree(job: Int) = NativeSource.jniImageStabJobFree(job)
    override fun jobCancel(job: Int) = NativeSource.jniImageStabJobCancel(job)
    override fun jobProgress(job: Int): LongArray? = NativeSource.jniImageStabJobProgress(job)
    override fun analyze(fd: Int, finalFile: String, job: Int, threads: Int): Int =
        NativeSource.jniImageStabAnalyze(fd, finalFile, job, threads)
}

/** Opens (and closes) a descriptor on the clip; the native side dup()s it. */
class ImageStabFdSource(val open: () -> Int, val close: (Int) -> Unit)

/**
 * Whole-clip image-stabilization analysis, run in the app's coroutines (no
 * foreground service: plan section 6 item 9). ONE analysis at a time, each
 * using two threads (decode on a native producer thread, tracking on the
 * IO thread that calls in; the result is bit-identical to one thread). It
 * stands down while [ImageStabSignals] say export / playback / thermal,
 * keeps the native checkpoint, and resumes from it. The analysis file is
 * keyed by fingerprint + analysis version ([ImageStabCache]); changing the
 * grade never re-runs it.
 *
 * [request] is the editor's (low priority, pausable); [ensure] is the
 * export pre-stage's (urgent: ignores the policy, because the export
 * itself is what is waiting).
 */
class ImageStabAnalysisService(
    private val cache: ImageStabCache,
    private val backend: ImageStabBackend,
    private val signals: ImageStabSignals,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 500L,
    private val threads: Int = 2,
) : com.ritim.ritimcraw.data.ImageStabCleanup {
    private val statuses = MutableStateFlow<Map<String, ImageStabStatus>>(emptyMap())
    private val runLock = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val userCancelled = ConcurrentHashMap.newKeySet<String>()
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    /** The current state: Done when a complete file exists, else the live status. */
    fun status(fingerprint: String): ImageStabStatus = statuses.value[fingerprint] ?: initial(fingerprint)

    fun statusFlow(fingerprint: String): Flow<ImageStabStatus> =
        statuses.map { it[fingerprint] ?: initial(fingerprint) }.distinctUntilChanged()

    private fun initial(fingerprint: String): ImageStabStatus =
        if (cache.isComplete(fingerprint)) ImageStabStatus.Done
        else ImageStabStatus.Idle(cache.hasPartial(fingerprint))

    private fun set(fingerprint: String, s: ImageStabStatus) {
        statuses.value = statuses.value + (fingerprint to s)
    }

    /**
     * Starts (or joins) the analysis of [fingerprint] in the background and
     * returns immediately. A complete analysis is Done at once.
     */
    fun request(fingerprint: String, fd: ImageStabFdSource): Job? = launchRun(fingerprint, fd, urgent = false)

    /**
     * The export pre-stage: runs (or joins) the analysis ignoring the
     * pause policy and returns true when a complete analysis exists
     * afterwards. False = failed/cancelled (the export must fail loudly).
     */
    suspend fun ensure(fingerprint: String, fd: ImageStabFdSource): Boolean {
        launchRun(fingerprint, fd, urgent = true)?.join()
        return cache.isComplete(fingerprint)
    }

    /** User cancel: the native run stops at the next frame and keeps its checkpoint. */
    fun cancel(fingerprint: String) {
        userCancelled.add(fingerprint)
        running[fingerprint]?.cancel(CancellationException("image stabilization analysis cancelled"))
    }

    /**
     * The clip is gone: stop any run of [fingerprint] and make sure none of
     * its files outlive it (a run still stopping would otherwise write one
     * last checkpoint after the delete). Safe when nothing runs.
     */
    override fun forget(fingerprint: String) {
        val job = running[fingerprint]
        if (job != null && job.isActive) {
            deleted.add(fingerprint)
            cancel(fingerprint)
        } else {
            cache.delete(fingerprint)
        }
        set(fingerprint, ImageStabStatus.Idle())
    }

    /** Loads the clip's complete analysis into [handle] through [loader] (engine thread). */
    suspend fun attach(
        handle: Long,
        fingerprint: String,
        loader: suspend (handle: Long, path: String) -> Int,
    ): Boolean {
        if (!cache.isComplete(fingerprint)) return false
        val ok = loader(handle, cache.fileFor(fingerprint).absolutePath) == 0
        if (ok) ImageStabLoads.markLoaded(handle)
        return ok
    }

    /** Suspends until the status of [fingerprint] is terminal (Done / Failed / Idle). */
    suspend fun awaitTerminal(fingerprint: String): ImageStabStatus =
        statusFlow(fingerprint).first {
            it is ImageStabStatus.Done || it is ImageStabStatus.Failed || it is ImageStabStatus.Idle
        }

    /**
     * The path of [fingerprint]'s COMPLETE analysis file, or null when
     * none exists (the export pre-stage's answer after [ensure]: the
     * same cache that ran the analysis owns the name).
     */
    fun analysisPath(fingerprint: String): String? =
        cache.fileFor(fingerprint).takeIf { cache.isComplete(fingerprint) }?.absolutePath

    private fun launchRun(fingerprint: String, fd: ImageStabFdSource, urgent: Boolean): Job? {
        if (cache.isComplete(fingerprint)) {
            set(fingerprint, ImageStabStatus.Done)
            return null
        }
        synchronized(running) {
            running[fingerprint]?.let { if (it.isActive) return it }
            userCancelled.remove(fingerprint)
            set(fingerprint, ImageStabStatus.Queued)
            val job = scope.launch {
                try {
                    runLock.withLock { runOne(fingerprint, fd, urgent) }
                } catch (e: CancellationException) {
                    set(fingerprint, ImageStabStatus.Idle(cache.hasPartial(fingerprint)))
                    throw e
                } finally {
                    running.remove(fingerprint)
                    if (deleted.remove(fingerprint)) cache.delete(fingerprint)
                }
            }
            running[fingerprint] = job
            return job
        }
    }

    private suspend fun runOne(fingerprint: String, src: ImageStabFdSource, urgent: Boolean) {
        var thermalPaused = false
        lastProgress = 0L to 0L
        while (true) {
            // Stand-down gate (the editor's analysis only).
            while (!urgent) {
                val why = ImageStabRunPolicy.decide(
                    signals.exporting(), signals.playing(), signals.thermalHeadroom(), thermalPaused,
                )
                thermalPaused = why == ImageStabPause.THERMAL
                if (why == null) break
                val p = lastProgress
                set(fingerprint, ImageStabStatus.Paused(why, p.first, p.second))
                delay(pollMs)
            }
            val job = backend.jobNew()
            if (job < 0) {
                set(fingerprint, ImageStabStatus.Failed(CODE_NO_JOB_SLOT))
                return
            }
            val fd = src.open()
            if (fd < 0) {
                backend.jobFree(job)
                set(fingerprint, ImageStabStatus.Failed(CODE_OPEN))
                return
            }
            set(fingerprint, ImageStabStatus.Running(lastProgress.first, lastProgress.second))
            var stoodDown = false
            val result: Int
            try {
                result = coroutineScope {
                    val monitor = launch {
                        while (isActive) {
                            delay(pollMs)
                            backend.jobProgress(job)?.let {
                                lastProgress = it[0] to it[1]
                                set(fingerprint, ImageStabStatus.Running(it[0], it[1]))
                            }
                            if (!urgent && ImageStabRunPolicy.decide(
                                    signals.exporting(), signals.playing(),
                                    signals.thermalHeadroom(), thermalPaused,
                                ) != null
                            ) {
                                stoodDown = true
                                backend.jobCancel(job)
                                return@launch
                            }
                        }
                    }
                    val work = async(ioDispatcher) {
                        backend.analyze(fd, cache.targetPath(fingerprint), job, threads)
                    }
                    try {
                        val r = work.await()
                        monitor.cancel()
                        r
                    } catch (e: CancellationException) {
                        // Native analyze is blocking: ask it to stop, let it
                        // checkpoint, THEN propagate.
                        backend.jobCancel(job)
                        work.join()
                        throw e
                    }
                }
            } finally {
                src.close(fd)
                backend.jobFree(job)
            }
            when (result) {
                0 -> {
                    lastProgress = 0L to 0L
                    set(fingerprint, ImageStabStatus.Done)
                    return
                }
                1 -> {
                    if (stoodDown && !userCancelled.contains(fingerprint)) continue // resume from the checkpoint
                    set(fingerprint, ImageStabStatus.Idle(cache.hasPartial(fingerprint)))
                    return
                }
                else -> {
                    set(fingerprint, ImageStabStatus.Failed(result))
                    return
                }
            }
        }
    }

    @Volatile private var lastProgress: Pair<Long, Long> = 0L to 0L

    companion object {
        const val CODE_NO_JOB_SLOT = 100
        const val CODE_OPEN = 101
    }
}
