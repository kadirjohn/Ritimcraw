package com.ritim.ritimcraw.engine

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The device-side [ScrubFrames] assembly (F2-C3 wiring): the facade over
 * the Android JPEG codec and the filler's own background thread — the
 * Klipp reference's shapes ("scrub-build" at MIN_PRIORITY, the frame
 * under the finger never waits behind the builder), carried into this
 * app's engine package with no Android UI types:
 *
 *  - the filler walks ONE single-thread executor at MIN_PRIORITY (a
 *    daemon thread, so it never holds process exit), taking its frames
 *    from [SessionScrubSource] — every render still goes through the
 *    editor's single preview engine thread, so the walk can never run a
 *    second decoder or race the render pump;
 *  - the cache is the facade's default in-memory LRU; the budget stays
 *    the facade's own (120 frames at 320px, JPEG q70 — the same numbers
 *    the branch's commit pinned for the Klipp-parity shape).
 *
 * [run] closes BOTH halves at [close]: the facade's [ScrubFrames.close]
 * (cancels the walk, clears the cache) AND the executor's dispatcher
 * ([kotlinx.coroutines.ExecutorCoroutineDispatcher.close] shuts the
 * thread down) — a per-editor-open instance must leave nothing behind
 * (the facade alone leaves the idle walk thread alive for the process).
 *
 * One instance per EDITOR OPEN (the screen keys it to the clip like the
 * session, beside session.close() in the dispose).
 *
 * Not touched by JVM tests (the thread); the facade, the policy and the
 * adapter are the tested surface.
 */
class ScrubFramesAndroid private constructor(
    val scrub: ScrubFrames,
    private val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
) : AutoCloseable {

    override fun close() {
        scrub.close()
        dispatcher.close()
    }

    companion object {
        private val threadCount = AtomicInteger(0)

        /** The in-memory cache's byte budget (see [create]). */
        const val MAX_CACHE_BYTES = 32L * 1024 * 1024

        /**
         * A ready facade with the Android codec and the background walk
         * thread. The caller owns the returned holder and MUST [close] it.
         */
        fun create(): ScrubFramesAndroid {
            val ex: ExecutorService = Executors.newSingleThreadExecutor { r ->
                Thread(r, "scrub-frames-${threadCount.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            val dispatcher = ex.asCoroutineDispatcher()
            return ScrubFramesAndroid(
                ScrubFrames(
                    cache = ScrubFrameCache(maxBytes = MAX_CACHE_BYTES),
                    codec = AndroidScrubCodec(),
                    dispatcher = dispatcher,
                    tierMaxDim = ScrubFramesPolicy.TIER_MAX_DIM,
                ),
                dispatcher,
            )
        }
    }
}

