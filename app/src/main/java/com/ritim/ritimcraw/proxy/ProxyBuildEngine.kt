package com.ritim.ritimcraw.proxy

/**
 * The encoder boundary of one proxy build, as an interface so the build engine
 * is JVM-testable with a fake (the EncodedDeveloper/Mp4MediaWriter pattern:
 * MediaCodec cannot run on the JVM, so every decision around it lives in the
 * engine and the real adapter is a thin, device-gated class).
 *
 * Contract of ONE build ([run]):
 *  - the engine hands over the build's [ProxyWriteHandle] — the encoder writes
 *    the WHOLE proxy video to [ProxyWriteHandle.videoFile] (a `.tmp` path the
 *    cache owns), every frame, in the source's own pts order (plan section 2.1
 *    "PTS'ler orijinalle birebir"), audio never;
 *  - progress lands in [onProgress] as (framesDone, framesTotal) — the same
 *    counts the notification and the storage page show;
 *  - the encoder checks [isCancelled] between frames: a true means ABORT the
 *    write and return [ProxyEncodeResult.Cancelled] — the partial `.tmp` is
 *    the engine's to drop, never to commit;
 *  - when the policy said SLOW, the engine invokes [pacer] between frames:
 *    the pacer sleeps a slice on the wired side and is a no-op in tests — the
 *    OUTPUT is unchanged, only the pace slows;
 *  - the return is [ProxyEncodeResult]: [ProxyEncodeResult.Ok] when the LAST
 *    [onProgress] reported every frame written, Cancelled, or Failed with the
 *    honest reason (which path, which encoder — plan section 4).
 */
interface ProxyEncoder {
    fun run(
        spec: ProxyBuildSpec,
        handle: ProxyWriteHandle,
        onProgress: (framesDone: Long, framesTotal: Long) -> Unit,
        isCancelled: () -> Boolean,
        pacer: () -> Unit,
    ): ProxyEncodeResult
}

/** How one [ProxyEncoder.run] ended. */
sealed interface ProxyEncodeResult {
    data object Ok : ProxyEncodeResult
    data object Cancelled : ProxyEncodeResult
    data class Failed(val reason: String) : ProxyEncodeResult
}

/**
 * The phase-P2/P3 build engine (docs/PROXY_PLAN.md sections 3.3 + 4): turns ONE
 * [ProxyJob.Start] the E2 runner popped into a complete proxy on disk — or an
 * honest terminal state. The runner ([ProxyRunnerOps]) stays the pure priority
 * gate; this class is the WRITE side executor around the P1 cache's atomic
 * contract:
 *
 *  begin(spec.signature) -> encoder fills the handle's `.tmp` -> VALIDATE ->
 *  commit(durationUs, frameCount) — and the cache publishes (manifest last).
 *
 * The never-half rule needs nothing extra here BECAUSE the P1 protocol is the
 * gate: every non-Ok outcome calls [ProxyWriteHandle.abort] (the `.tmp`s are
 * deleted) and the engine adds its own validation — a build whose last progress
 * is not the spec's full frame count commits NOTHING ([ProxyBuildSpec.frameCount],
 * plan section 3.3's "kare sayısı = beklenen" re-open check, applied at commit
 * time), so a stalled codec that reports Ok cannot publish a short file.
 *
 * Interruption is the repo's ImageStabAnalysisService shape: the wired side runs
 * [run] on its worker and a monitor calls [requestCancel] from another thread
 * (the thermal/battery ladder, an export that started, or the user). The encoder
 * sees the flag between frames; the engine then routes the cancelled job by the
 * reason: PRIORITY (export/thermal/battery) re-queues it FRONT (no resume,
 * section 3.3 — it restarts from scratch when the policy allows), USER takes it
 * out of the queue entirely (state Cancelled, the failure count untouched).
 *
 * Single build at a time by contract (the runner pops one); thread-safe only on
 * the cancel flag, exactly as much as the monitor needs.
 */
class ProxyBuildEngine(
    private val queue: ProxyStateMap,
    private val cache: ProxyCache,
    private val encoder: ProxyEncoder,
    /** How the engine learns each job's build spec; a null answer fails the job honestly. */
    private val specFor: (ProxyKey) -> ProxyBuildSpec?,
    /** The SLOW pace between frames (the wired side sleeps; the default no-op keeps tests fast). */
    private val pacer: () -> Unit = {},
    /** One frame-count report of the running build (the notification + storage page feed). */
    private val onProgress: (framesDone: Long, framesTotal: Long) -> Unit = { _, _ -> },
    /** Called after every terminal state change, for status refresh. */
    private val onTerminal: (key: ProxyKey, state: ProxyState, frames: Long) -> Unit = { _, _, _ -> },
) {

    /** Why a running build was asked to stop — it decides the job's fate after the abort. */
    enum class CancelReason { USER, PRIORITY }

    @Volatile private var cancelRequested: CancelReason? = null

    /** The monitor thread's door: ask the running build to stop at its next frame check. */
    fun requestCancel(reason: CancelReason) {
        cancelRequested = reason
    }

    /**
     * Runs ONE whole build SYNCHRONOUSLY (the wired worker thread). The queue
     * already holds the key as Building ([ProxyStateMap.next] flipped it when
     * the runner popped the job). Returns the terminal state it recorded.
     */
    fun run(job: ProxyJob.Start): ProxyState {
        val key = job.key
        val spec = specFor(key)
        if (spec == null) {
            queue.failed(key, "no build spec for the clip (source facts or settings missing)")
            onTerminal(key, ProxyState.Failed, 0L)
            return ProxyState.Failed
        }
        val handle = try {
            cache.begin(spec.signature)
        } catch (e: Exception) {
            queue.failed(key, "the proxy cache could not open a write handle: ${e.message}")
            onTerminal(key, ProxyState.Failed, 0L)
            return ProxyState.Failed
        }
        cancelRequested = null
        var framesWritten = 0L
        val result = try {
            encoder.run(
                spec = spec,
                handle = handle,
                onProgress = { done, total ->
                    framesWritten = done
                    onProgress(done, total)
                },
                isCancelled = { cancelRequested != null },
                pacer = pacer,
            )
        } catch (e: Exception) {
            ProxyEncodeResult.Failed("the encoder threw: ${e.message}")
        }
        return when (result) {
            is ProxyEncodeResult.Ok -> commit(key, handle, spec, framesWritten)
            is ProxyEncodeResult.Cancelled -> abortCancelled(key, handle)
            is ProxyEncodeResult.Failed -> {
                handle.abort()
                queue.failed(key, result.reason)
                onTerminal(key, ProxyState.Failed, framesWritten)
                ProxyState.Failed
            }
        }
    }

    /** The commit gate: frame count first, then the cache's own atomic publish. */
    private fun commit(
        key: ProxyKey,
        handle: ProxyWriteHandle,
        spec: ProxyBuildSpec,
        framesWritten: Long,
    ): ProxyState {
        if (framesWritten != spec.frameCount) {
            handle.abort()
            queue.failed(
                key,
                "the encode ended after $framesWritten of ${spec.frameCount} frames (short build)",
            )
            onTerminal(key, ProxyState.Failed, framesWritten)
            return ProxyState.Failed
        }
        val entry = handle.commit(durationUs = spec.durationUs, frameCount = framesWritten)
        return if (entry == null) {
            queue.failed(
                key,
                "the commit was refused (empty output, over the cap, or a torn manifest)",
            )
            onTerminal(key, ProxyState.Failed, framesWritten)
            ProxyState.Failed
        } else {
            queue.ready(key)
            onTerminal(key, ProxyState.Ready, framesWritten)
            ProxyState.Ready
        }
    }

    /** A cancelled build: partial dropped; the reason routes the job (front vs out). */
    private fun abortCancelled(key: ProxyKey, handle: ProxyWriteHandle): ProxyState {
        handle.abort()
        val reason = cancelRequested
        cancelRequested = null
        return if (reason == CancelReason.USER) {
            queue.cancelled(key)
            onTerminal(key, ProxyState.Cancelled, 0L)
            ProxyState.Cancelled
        } else {
            // Priority interrupt (export started / thermal / battery): the job
            // returns to the FRONT of the queue — no resume (section 3.3), it
            // restarts from scratch when the policy allows again.
            queue.requeue(key)
            ProxyState.Queued
        }
    }
}
