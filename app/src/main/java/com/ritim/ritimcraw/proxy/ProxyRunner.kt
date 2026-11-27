package com.ritim.ritimcraw.proxy

/**
 * The runner's step for the current moment, derived from the queue + the power policy.
 * The wired phase-P2 runner turns each of these into its own coroutine moves; nothing here
 * starts anything by itself (no threads, no Android).
 */
enum class ProxyRunnerStep {
    /** No queued job and nothing building: nothing to do. */
    IDLE,
    /** Pop [ProxyStateMap.next] and start building it. */
    START,
    /** A job is building and nothing stands in its way: keep going at full speed. */
    CONTINUE,
    /** Building under SLOW: keep going but yield between batches (the output is unchanged). */
    YIELD,
    /** Interrupt the current build: it is cancelled (no resume) and re-queued front, its `.tmp`
     * is dropped; when the policy allows again the build restarts from scratch (plan section 3.3). */
    INTERRUPT,
    /** The queue has work but the policy says pause: stand down now, keep the queue as it is. */
    HOLD,
    /** Do not start new builds while playback is running (plan section 4: playback > proxy). */
    WAIT_PLAYBACK,
}

/**
 * The pure priority gate of the background queue (docs/PROXY_PLAN.md section 4):
 * **active export > playback > proxy**.
 *
 *  - an active export wins over everything: a running proxy build is INTERRUPTed (its work
 *    restarts later, nothing half is ever played) and nothing new starts (the queue HOLDS
 *    until the export ends);
 *  - while the editor plays, no NEW build starts (WAIT_PLAYBACK); an already running build is
 *    left alone if the power policy allows it — the plan's own gate is "start none while
 *    playing" (ImageStabActivity.playing), it does not demand killing a running one. When the
 *    power policy pauses a running build, that wins (INTERRUPT);
 *  - otherwise the [ProxyRunPolicy] thermal/battery decision maps to CONTINUE / YIELD / INTERRUPT
 *    for a running job, and START / HOLD for an idle runner with a queued job.
 */
class ProxyRunner(
    private val queue: ProxyStateMap,
    private val policy: ProxyRunPolicy = ProxyRunPolicy(),
) {
    /**
     * One decision for this instant. [playing] = the editor's playback clock is running
     * (the same signal ImageStab reads). Pure: reads the queue and the power state only.
     */
    fun step(power: ProxyPowerState, playing: Boolean): ProxyRunnerStep {
        val building = queue.hasBuilding()
        // Priority 1: an active export wins over everything. A live build is interrupted (its
        // half work is never played, it restarts later); with nothing building the queue is
        // simply held until the export ends — no orphan INTERRUPT without a build to kill.
        if (building && power.exportActive) return ProxyRunnerStep.INTERRUPT
        if (building) {
            val decision = policy.decide(power)
            return when (decision.action) {
                ProxyRunAction.RUN -> ProxyRunnerStep.CONTINUE
                ProxyRunAction.SLOW -> ProxyRunnerStep.YIELD
                ProxyRunAction.PAUSE -> ProxyRunnerStep.INTERRUPT
            }
        }
        // Priority 2: playback beats STARTING new proxy work (plan section 4). An entirely
        // empty queue stays IDLE (nothing to hold, the wired runner should not wake for it).
        if (!queue.hasQueued()) return ProxyRunnerStep.IDLE
        if (power.exportActive) return ProxyRunnerStep.HOLD
        if (playing) return ProxyRunnerStep.WAIT_PLAYBACK
        if (queue.isPaused()) return ProxyRunnerStep.HOLD
        return when (policy.decide(power).action) {
            ProxyRunAction.RUN, ProxyRunAction.SLOW -> ProxyRunnerStep.START
            ProxyRunAction.PAUSE -> ProxyRunnerStep.HOLD
        }
    }
}

/** One queue entry the runner can act on right now, after the priority gate said GO. */
sealed interface ProxyJob {
    val key: ProxyKey

    /** A queued key the runner may pop. */
    data class Start(override val key: ProxyKey) : ProxyJob
    /** The key that was building; the runner cancels its handle and re-queues it. */
    data class Interrupt(override val key: ProxyKey) : ProxyJob
}

/**
 * The runner's per-tick act, queue in hand: the step AND, when it names a key, that key.
 * The wired phase-P2 loop calls this, then performs the move (start the writer coroutine /
 * cancel the handle and requeue). Popping happens HERE, so a START is never lost between the
 * decision and the act.
 */
class ProxyRunnerOps(
    private val queue: ProxyStateMap,
    private val policy: ProxyRunPolicy = ProxyRunPolicy(),
) {
    fun act(power: ProxyPowerState, playing: Boolean): Pair<ProxyRunnerStep, ProxyJob?> {
        val step = ProxyRunner(queue, policy).step(power, playing)
        return when (step) {
            ProxyRunnerStep.START -> {
                val k = queue.next() ?: return Pair(ProxyRunnerStep.IDLE, null)
                Pair(step, ProxyJob.Start(k))
            }
            ProxyRunnerStep.INTERRUPT -> {
                val k = queue.currentlyBuilding() ?: return Pair(ProxyRunnerStep.IDLE, null)
                Pair(step, ProxyJob.Interrupt(k))
            }
            else -> Pair(step, null)
        }
    }
}

/** The queue query helpers the runner needs, added to the P1 queue as extension functions (additive). */
fun ProxyStateMap.hasBuilding(): Boolean = buildingSource().protectedKeys().isNotEmpty()
fun ProxyStateMap.hasQueued(): Boolean = queued().isNotEmpty()
fun ProxyStateMap.currentlyBuilding(): ProxyKey? = buildingSource().protectedKeys().firstOrNull()
