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
    /** The proxy flag (FeatureFlags.proxyEnabled); off = generated proxies are not played either. */
    private val playbackEnabled: () -> Boolean = { true },
    /** A build for this fingerprint reached Ready (the screen marks its switch-pending state). */
    private val onProxyReady: (fingerprint: String) -> Unit = {},
    /** The proxy status of this fingerprint may have changed (queue, progress, terminal): the UI re-reads [statusOf]. */
    private val onStatusChanged: (fingerprint: String) -> Unit = {},
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
    private val filesDir: java.io.File = context.applicationContext.filesDir
    private val percentByFp = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var currentFp: String? = null
    private val progressLog = ProxyProgressLogThrottle(10)
    private var lastNotifiedPercent = -1

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
            onProgress = { done, total -> onBuildProgress(done, total) },
            onTerminal = { key, state, _ ->
                logTerminal(key, state)
                if (state == ProxyState.Ready) {
                    synchronized(gates) { gates[key.fingerprint] }?.onBuildLanded()
                    onProxyReady(key.fingerprint)
                }
                percentByFp.remove(key.fingerprint)
                onStatusChanged(key.fingerprint)
            },
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
        ProxyLog.d("enqueue ${sig.sourceFingerprint.take(8)} front=$front accepted=$ok queue=${queue.queued().size}")
        if (ok) {
            onStatusChanged(sig.sourceFingerprint)
            kick()
        }
        return ok
    }

    /** The clip's measured facts (null while unknown), for the mode decision. */
    fun clipInfoOf(fingerprint: String): ProxyClipInfo? = clipInfoFor(fingerprint)

    /** True when a proxy is built, queued or building for the clip (not Failed/Cancelled/None). */
    fun isBuiltOrQueued(fingerprint: String): Boolean = when (statusOf(fingerprint)) {
        ProxyStatus.None, ProxyStatus.Failed -> false
        else -> true
    }

    /** The clip's proxy status for the UI (library row, editor). Cheap: queue lookup + one manifest check. */
    fun statusOf(fingerprint: String): ProxyStatus {
        val sig = jobFor(fingerprint)?.first ?: return ProxyStatus.None
        val key = sig.key()
        val state = queue.state(key)
        val hasPlayable = state != ProxyState.Building && state != ProxyState.Queued && cache.peekPlayable(key) != null
        return ProxyStatusMapper.of(state, hasPlayable, percentByFp[fingerprint])
    }

    /**
     * Manual "Create proxy" (any clip kind, MCRAW included: the opt-in route; the auto
     * rule does not apply). The user is waiting, so it goes to the FRONT lane, and a
     * clip that failed this session may be retried. The caller hides it while the mode is Off.
     */
    fun createManual(fingerprint: String): Boolean {
        if (!playbackEnabled()) return false
        val job = jobFor(fingerprint) ?: return false
        queue.forgiveFailures(job.first.key())
        return enqueue(job.first, front = true)
    }

    /** Manual "Delete proxy": stops a queued/building job and removes the built file(s). */
    fun deleteProxy(fingerprint: String) {
        cancel(fingerprint)
        val removed = cache.clearClip(fingerprint)
        jobFor(fingerprint)?.first?.key()?.let { queue.reset(it) }
        ProxyLog.d("delete ${fingerprint.take(8)} removedEntries=$removed total=${cache.totalBytes()}B dir=${ProxyDirs.cacheDir(filesDir)}")
        onStatusChanged(fingerprint)
    }

    /** Mode switched to Off: drop every queued job and stop the running build (built files stay, unused). */
    fun stopAll() {
        val queued = queue.queued()
        queued.forEach { queue.cancelled(it) }
        if (running) engine.requestCancel(ProxyBuildEngine.CancelReason.USER)
        ProxyLog.d("stopAll cancelledQueued=${queued.size} runningStopped=$running")
        queued.forEach { onStatusChanged(it.fingerprint) }
    }

    private fun onBuildProgress(done: Long, total: Long) {
        val fp = currentFp ?: return
        val pct = ProxyStatusMapper.percent(done, total)
        percentByFp[fp] = pct
        if (pct != lastNotifiedPercent) {
            lastNotifiedPercent = pct
            onStatusChanged(fp)
        }
        if (progressLog.shouldLog(pct)) ProxyLog.d("progress ${fp.take(8)} $pct% ($done/$total frames)")
    }

    private fun logTerminal(key: ProxyKey, state: ProxyState) {
        val fp = key.fingerprint.take(8)
        when (state) {
            ProxyState.Ready ->
                ProxyLog.d("ready $fp clipBytes=${cache.bytesForClip(key.fingerprint)} total=${cache.totalBytes()}B dir=${ProxyDirs.cacheDir(filesDir)}")
            ProxyState.Failed ->
                ProxyLog.d("failed $fp reason=${queue.failureReason(key)} dir=${ProxyDirs.cacheDir(filesDir)}")
            else -> ProxyLog.d("terminal $fp state=$state")
        }
    }

    /** User cancel of one fingerprint's queued/building job (the storage page's button). */
    fun cancel(fingerprint: String) {
        val key = jobFor(fingerprint)?.first?.key() ?: return
        if (queue.state(key) == ProxyState.Building) {
            engine.requestCancel(ProxyBuildEngine.CancelReason.USER)
        } else {
            queue.cancelled(key)
            onStatusChanged(fingerprint)
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
        if (!playbackEnabled()) {
            ProxyLog.d("playback ${fingerprint.take(8)} source=original (proxy mode off)")
            return -1
        }
        val job = jobFor(fingerprint) ?: return -1
        val gate = synchronized(gates) { gates.getOrPut(fingerprint) { ProxyPlaybackGate(cache, inUse, playbackEnabled) } }
        val entry = gate.open(job.first)
        if (entry == null) {
            ProxyLog.d("playback ${fingerprint.take(8)} source=original (no playable proxy)")
            return -1
        }
        ProxyLog.d("playback ${fingerprint.take(8)} source=proxy file=${entry.file.name}")
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

    /**
     * The in-session switch (called at the next seek/play after a build landed):
     * a detached fd of the proxy via the gate's [ProxyPlaybackGate.takePendingSwitch]
     * (maybeSwitch), or -1 = stay on the current file. The caller reopens its
     * session on the fd; the gate's in-use protection moves with [ProxyPlaybackGate.swapped].
     */
    fun switchPlaybackProxy(fingerprint: String): Int {
        val gate = synchronized(gates) { gates[fingerprint] } ?: return -1
        val entry = gate.takePendingSwitch() ?: return -1
        return try {
            val fd = android.os.ParcelFileDescriptor.open(
                entry.file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            ).detachFd()
            gate.swapped(entry)
            ProxyLog.d("playback ${fingerprint.take(8)} switched original->proxy file=${entry.file.name}")
            fd
        } catch (e: Exception) {
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
                // Mode Off: nothing starts (a queue left over from before the switch just parks).
                if (!playbackEnabled()) break
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
        currentFp = job.key.fingerprint
        lastNotifiedPercent = -1
        progressLog.reset()
        ProxyLog.d("build start ${job.key.fingerprint.take(8)} queueLeft=${queue.queued().size}")
        onStatusChanged(job.key.fingerprint)
        try {
            engine.run(job)
        } finally {
            currentFp = null
            percentByFp.remove(job.key.fingerprint)
            // an interrupted (re-queued) build never reaches onTerminal: refresh its status here
            onStatusChanged(job.key.fingerprint)
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
