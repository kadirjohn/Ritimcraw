package com.ritim.ritimcraw.proxy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.ritim.ritimcraw.engine.ImageStabActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Android side of [ProxyPowerState] — the [com.ritim.ritimcraw.engine.
 * AndroidImageStabSignals] shape, one reading per policy tick:
 *  - thermal: [PowerManager.getThermalHeadroom] (the 10 s forecast; NaN = the
 *    device does not report one = no thermal objection — the same contract
 *    ImageStab reads);
 *  - battery: the sticky ACTION_BATTERY_CHANGED broadcast (percent + charging),
 *    no polling receiver of our own — the sticky broadcast is always current
 *    and reading it costs nothing;
 *  - export: the caller's probe (the export store's RUNNING row — the same
 *    seam ImageStab reads, never a direct dependency on the exporter);
 *  - playing: [ImageStabActivity.playing], the process-wide flag the editor
 *    already maintains while its playback clock runs.
 *
 * Every read is best-effort: a missing service or a failed broadcast leaves
 * the field null (unknown never blocks — the policy's own rule).
 */
class AndroidProxyPowerSource(
    context: Context,
    private val exportingProbe: () -> Boolean,
) {
    private val app = context.applicationContext
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** One reading for a policy tick. */
    fun state(): ProxyPowerState = ProxyPowerState(
        thermalHeadroom = thermalHeadroom(),
        batteryPercent = batteryPercent(),
        charging = isCharging(),
        exportActive = exportingProbe(),
    )

    /** The editor's playback flag (the ImageStab seam — one process-wide truth). */
    fun playing(): Boolean = ImageStabActivity.playing

    private fun thermalHeadroom(): Float? = try {
        power?.getThermalHeadroom(THERMAL_FORECAST_SECONDS)?.takeUnless { it.isNaN() }
    } catch (e: Exception) {
        null
    }

    private fun batteryStatusSticky(): Intent? = try {
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: Exception) {
        null
    }

    private fun batteryPercent(): Int? {
        val status = batteryStatusSticky() ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale)
    }

    private fun isCharging(): Boolean {
        val status = batteryStatusSticky() ?: return false
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private companion object {
        const val THERMAL_FORECAST_SECONDS = 10
    }
}

/**
 * The app-side owner of the proxy queue's runtime pieces (G3 wiring): the P1
 * cache over `filesDir/cache/proxies` ([ProxyDirs]), the E2 state map, the
 * build engine and the foreground seam. Constructed once from the app context
 * (AppNav); the [com.ritim.ritimcraw.engine.PlaybackTrace] convention — a
 * plain object, never compose state.
 *
 * THE DRIVE SHAPE (the [com.ritim.ritimcraw.engine.ImageStabAnalysisService]
 * pattern, adapted to the E2 runner's steps):
 *  - a WORK coroutine runs the builds: it ticks [ProxyRunnerOps] — a START
 *    runs the build synchronously (the engine's own contract), everything
 *    else parks for the next kick;
 *  - a MONITOR coroutine samples the power state every [MONITOR_POLL_MS] and,
 *    while a build runs, applies the policy with the CURRENT decision: an
 *    export start or a thermal/battery PAUSE asks the running build to stop
 *    (PRIORITY cancel — the encoder sees it at its next frame check, the
 *    engine re-queues the job front, no resume); a SLOW sets the pacer's
 *    slice (the output unchanged, only the pace). This is what makes
 *    INTERRUPT reachable while the engine blocks the work thread;
 *  - every terminal state and every enqueue kicks the work loop, so a queue
 *    with more work drains without an external timer.
 */
class ProxyRuntime(
    context: Context,
    private val exportingProbe: () -> Boolean,
    /** How each queued job is BUILT: the encoder resolves the clip's fd and runs. */
    private val encoder: ProxyEncoder,
    /** The clip facts the auto decision needs, resolved per fingerprint. */
    private val clipInfoFor: (String) -> ProxyClipInfo?,
    /** The signature the build runs under + its build spec, resolved per fingerprint. */
    private val jobFor: (String) -> Pair<ProxySignature, ProxyBuildSpec>?,
) {
    val queue = ProxyStateMap()
    val cache: ProxyCache
    val inUse: ProxyInUse = ProxyInUseGlobal
    private val power = AndroidProxyPowerSource(context, exportingProbe)
    private val foreground: ProxyForeground = ProxyForeground.processDefault()
    private val runnerOps = ProxyRunnerOps(queue)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drive = Channel<Unit>(Channel.CONFLATED)
    private val engine: ProxyBuildEngine
    private var workJob: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var slowPace = false
    @Volatile private var running = false

    init {
        val app = context.applicationContext
        cache = ProxyCache(
            storage = ProxyDirs.storage(app.filesDir),
            protectSource = ProxyProtectSource.union(inUse, queue.buildingSource()),
        )
        engine = ProxyBuildEngine(
            queue = queue,
            cache = cache,
            encoder = encoder,
            specFor = { key -> jobFor(key.fingerprint)?.takeIf { it.first.key() == key }?.second },
            pacer = { if (slowPace) Thread.sleep(SLOW_SLICE_MS) },
        )
        // Crash-leftover sweep (plan section 3.3): every *.tmp and every
        // manifest-less .mp4 goes, before any job can run.
        cache.sweep()
    }

    /** The signature a KEY stands for, from the wired [signatureFor]. */
    /**
     * Starts the work + monitor loops (idempotent). Call once, when the app's
     * stores are wired (AppNav); the loops idle at near-zero cost when the
     * queue is empty (the conflated channel parks).
     */
    fun start() {
        if (workJob != null) return
        workJob = scope.launch { workLoop() }
        monitorJob = scope.launch { monitorLoop() }
        drive.trySend(Unit)
    }

    /** Wakes the work loop (a new job, an export ending, a settings change). */
    fun kick() {
        drive.trySend(Unit)
    }

    /**
     * The AUTO enqueue (plan section 6): the clip's facts through the pure
     * decision table; a NO is silent, a YES enqueues the BACK lane. Callers
     * with the user waiting (the editor opening a clip) pass front = true.
     */
    fun maybeQueue(fingerprint: String, front: Boolean = false): Boolean {
        val clip = clipInfoFor(fingerprint) ?: return false
        if (ProxyQueueDecision.decide(clip) == null) return false
        val job = jobFor(fingerprint) ?: return false
        return enqueue(job.first, front)
    }

    /** A direct enqueue for an existing signature. */
    fun enqueue(sig: ProxySignature, front: Boolean = false): Boolean {
        val ok = queue.enqueue(sig.key(), front)
        if (ok) kick()
        return ok
    }

    /** User cancel of one fingerprint's queued/building job (the storage page's button). */
    fun cancel(fingerprint: String) {
        val key = jobFor(fingerprint)?.first?.key() ?: return
        if (queue.state(key) == ProxyState.Building) {
            engine.requestCancel(ProxyBuildEngine.CancelReason.USER)
        } else {
            queue.cancelled(key)
        }
    }

    // ---- playback (plan section 5) ----

    /** One gate per open editor session, keyed by fingerprint. */
    private val gates = HashMap<String, ProxyPlaybackGate>()

    /**
     * The editor session's proxy resolution (plan section 5, the single
     * decision point): the clip's playable proxy through its [ProxyPlaybackGate]
     * — a hit returns a DETACHED read-only fd of the proxy FILE (the session's
     * reader adopts and closes it), a miss returns -1 (open the original).
     * The gate holds the in-use protection for the session's lifetime;
     * [closePlaybackProxy] releases it.
     */
    fun openPlaybackProxy(fingerprint: String): Int {
        val job = jobFor(fingerprint) ?: return -1
        val gate = synchronized(gates) { gates.getOrPut(fingerprint) { ProxyPlaybackGate(cache, inUse) } }
        val entry = gate.open(job.first) ?: return -1
        return try {
            android.os.ParcelFileDescriptor.open(
                entry.file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            ).detachFd()
        } catch (e: Exception) {
            gate.close()
            -1
        }
    }

    /** The editor session closed: release its proxy's in-use protection. */
    fun closePlaybackProxy(fingerprint: String) {
        synchronized(gates) { gates.remove(fingerprint) }?.close()
    }

    // ---- the work loop ----

    private suspend fun workLoop() {
        for (kick in drive) {
            var go = true
            while (go && scope.isActive) {
                val state = power.state()
                val (step, job) = runnerOps.act(state, power.playing())
                when (step) {
                    ProxyRunnerStep.START -> {
                        val start = job as? ProxyJob.Start ?: break
                        runWithForeground(start)
                        // loop again: a terminal state may have re-queued work
                    }
                    else -> go = false // IDLE / HOLD / WAIT_PLAYBACK: park
                }
            }
        }
    }

    private suspend fun runWithForeground(job: ProxyJob.Start) {
        val jobId = job.key.baseName
        running = true
        slowPace = false
        foreground.start(jobId, job.key.fingerprint)
        try {
            engine.run(job)
        } finally {
            running = false
            slowPace = false
            foreground.stop(jobId)
            drive.trySend(Unit)
        }
    }

    // ---- the monitor loop ----

    /**
     * While a build runs: samples the power state every [MONITOR_POLL_MS] and
     * enforces the E2 policy against the CURRENT decision — the only way an
     * export start or a thermal spike can reach a build that blocks the work
     * thread (the ImageStab monitor's shape). Playback never interrupts a
     * running build (the plan's own gate: it only blocks STARTs, which the
     * work loop's next tick sees).
     */
    private suspend fun monitorLoop() {
        while (scope.isActive) {
            delay(MONITOR_POLL_MS)
            if (!running) {
                slowPace = false
                continue
            }
            val state = power.state()
            val decision = ProxyRunPolicy().decide(state)
            when (decision.action) {
                ProxyRunAction.PAUSE ->
                    // export or thermal/battery pause: stop the build at its
                    // next frame check; the engine re-queues it FRONT (the
                    // user-cancel reason is never used here)
                    engine.requestCancel(ProxyBuildEngine.CancelReason.PRIORITY)
                ProxyRunAction.SLOW -> slowPace = true
                ProxyRunAction.RUN -> slowPace = false
            }
            // a stand-down that ended (export finished while parked) needs a
            // kick so the work loop notices
            if (!running) drive.trySend(Unit)
        }
    }

    private companion object {
        /** The monitor's sampling cadence (the ImageStab poll's own 500 ms). */
        const val MONITOR_POLL_MS = 500L

        /** The SLOW pace slice between frames (the E2 report's "batch arası yield"). */
        const val SLOW_SLICE_MS = 250L
    }
}

/** The process-wide "which proxy is playing" set — [ProxyInUse] the editor's session acquire/releases. */
object ProxyInUseGlobal : ProxyInUse()
