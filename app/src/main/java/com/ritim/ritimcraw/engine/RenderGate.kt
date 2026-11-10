package com.ritim.ritimcraw.engine

import java.util.concurrent.atomic.AtomicReference

/**
 * The pending slot of the editor's render pipeline (owner request
 * 2026-11-10 section 1): at most ONE running and ONE pending render
 * request. The running slot is the editor's single render pump coroutine
 * (one pump = one render executing at a time); THIS class is the pending
 * slot — a new request replaces the stored one (newest wins). JVM-testable
 * without coroutines or the native lib.
 */
class RenderGate {

    /** True when a request waits in the pending slot. */
    val hasPending: Boolean get() = pending.get() != null

    private val pending = AtomicReference<RenderRequest?>(null)

    /**
     * Stores [request] as the pending render, REPLACING any previously
     * pending one (a new request takes the pending slot; the running render
     * is never interrupted — its result is stale-dropped when it lands).
     * Returns the request that was replaced, or null when the slot was
     * empty.
     */
    fun offer(request: RenderRequest): RenderRequest? =
        pending.getAndSet(request)

    /**
     * Takes the pending request (the slot empties). The pump calls this when
     * it is ready to start the next render.
     */
    fun poll(): RenderRequest? = pending.getAndSet(null)

    /** Drops a pending request without running it (e.g. the clip switched). */
    fun clear() = pending.set(null)
}

/** One queued render: which frame, with which grade, at which resolution tier. */
data class RenderRequest(
    val frameIndex: Int,
    val grade: com.ritim.ritimcraw.data.GradeK,
    val maxDim: Int,
    /** Monotonic sequence for the stale-drop once the result lands. */
    val sequence: Long,
)
