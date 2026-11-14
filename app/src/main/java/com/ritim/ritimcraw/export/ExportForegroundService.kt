package com.ritim.ritimcraw.export

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ritim.ritimcraw.R

/**
 * What the queue tells its host about an export's process priority
 * (owner finding 2026-11-14: Android's OomAdjuster moved the app to the
 * cached state at 11:52 during a 1749-frame export and FROZE the process
 * — "freezing 25368 com.ritim.ritimcraw" — the export died silently; a
 * long export must never die that way).
 *
 * An interface like [ExportPublisher] so the queue stays JVM-testable:
 * while a job is RUNNING the host holds the process at FOREGROUND priority
 * (a dataSync foreground service + a low-importance notification with the
 * job name and the SAME frame counts the UI row shows), and drops that
 * priority the moment the job lands a terminal state. Tests inject a
 * fake; the app resolves the real adapter through [processDefault].
 *
 * Call order (the queue rides its own state transitions, see
 * [ExportQueue.runOne]):
 *  - [start] right after the pick-to-RUNNING flip (never for a job
 *    cancelled/cleared before the start — the transition's early return
 *    never reaches the call),
 *  - [progress] on the queue-owned progress callback, the same frame
 *    counts the UI row shows,
 *  - [stop] right after the terminal write (DONE/CANCELLED/FAILED): the
 *    service NEVER outlives the job.
 */
interface ExportForeground {

    /** Holds foreground priority for [jobId], showing [title] (the job's
     * display-derived file name). */
    fun start(jobId: String, title: String)

    /** Reports frame counts for a started job (ignored for any other). */
    fun progress(jobId: String, framesDone: Int, framesTotal: Int)

    /** Drops the foreground priority held for [jobId] (no-op when it does
     * not hold one for that id — a stop lands exactly once per started
     * job). */
    fun stop(jobId: String)

    companion object {
        /** The no-op default (paths without an app context; tests inject
         * their own fakes). */
        val None: ExportForeground = object : ExportForeground {
            override fun start(jobId: String, title: String) {}
            override fun progress(jobId: String, framesDone: Int, framesTotal: Int) {}
            override fun stop(jobId: String) {}
        }

        /**
         * The queue's constructor default: the REAL adapter when an app
         * context is wired (see [ExportApp]), [None] otherwise (JVM unit
         * tests — no Android framework there, and none may be touched).
         * Resolved lazily per call so simply CONSTRUCTING a queue in a
         * test never initializes any Android class.
         */
        fun processDefault(): ExportForeground = ExportForegroundHost.resolve()
    }
}

/**
 * Pure start/progress/stop state machine for the export foreground
 * notification (JVM-testable via a fake [Sink] and an injected clock —
 * the NativeSource unavailable-guard pattern: every decision here, every
 * framework call behind the sink).
 *
 * Guarantees the service relies on ("never both directions out of
 * order"):
 *  - [progress] for a job that was never [start]ed, or that was already
 *    [stop]ped, is DROPPED — a notification can never appear for a job
 *    that is not RUNNING;
 *  - [stop] lands exactly once per started job (idempotent: a late
 *    second stop is a no-op), and a progress report already past the
 *    guard finishes BEFORE a concurrent stop's sink call — the
 *    notification is removed last, never resurrected;
 *  - a [start] while another job is still started stops the OLD job
 *    first (the serial queue always stops before the next start; this
 *    only matters if a terminal hook was lost to a dying queue) — the
 *    service never outlives its job.
 *
 * Progress is throttled to one sink call per
 * [NOTIFICATION_UPDATE_MIN_INTERVAL_MS] (the UI row's 500ms store-write
 * throttle is finer than a notification needs; the FIRST report after a
 * start always passes so the notification shows real frame counts
 * immediately). The whole filter is sink-side only — the queue still
 * hears every frame.
 *
 * Thread-safe by design: [start]/[stop] arrive on the drain's thread,
 * [progress] on the engine thread. One private lock covers state AND the
 * sink calls, which is what makes the ordering above absolute; the lock
 * is never held by anything but this class (the queue calls the seam
 * OUTSIDE its own controlLock, which must never span a binder call).
 */
internal class ExportForegroundController(
    private val sink: Sink,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExportForeground {

    /** The framework edge: post/withdraw the actual notification. */
    interface Sink {
        /** The job went RUNNING: take foreground priority for it. */
        fun onStarted(jobId: String, title: String)

        /** A throttled frame-count update for the started job (the same
         * numbers the UI row shows; [percent] is the bar, computed here). */
        fun onProgress(
            jobId: String,
            title: String,
            framesDone: Int,
            framesTotal: Int,
            percent: Int,
            indeterminate: Boolean,
        )

        /** The job landed terminal: drop the foreground priority. */
        fun onStopped(jobId: String)
    }

    private val lock = Any()

    /** The started job's id, or null while no job holds the priority. */
    private var startedId: String? = null

    /** The started job's notification title (captured at start). */
    private var title: String = ""

    /** Clock of the last progress sink call; -1 = "next report passes". */
    private var lastNotifyMs = -1L

    override fun start(jobId: String, title: String) {
        synchronized(lock) {
            val previous = startedId
            if (previous != null && previous != jobId) {
                // Defensive only — the serial queue stops a job before it
                // starts the next — but the service must NEVER outlive its
                // job, so a lost terminal hook resolves here.
                sink.onStopped(previous)
            }
            startedId = jobId
            this.title = title
            lastNotifyMs = -1L
            sink.onStarted(jobId, title)
        }
    }

    override fun progress(jobId: String, framesDone: Int, framesTotal: Int) {
        synchronized(lock) {
            if (jobId != startedId) return // not RUNNING: dropped
            val now = clock()
            if (lastNotifyMs >= 0 &&
                now - lastNotifyMs < NOTIFICATION_UPDATE_MIN_INTERVAL_MS
            ) {
                return
            }
            lastNotifyMs = now
            val indeterminate = framesTotal <= 0
            val percent = if (indeterminate) 0 else framesDone * 100 / framesTotal
            sink.onProgress(jobId, title, framesDone, framesTotal, percent, indeterminate)
        }
    }

    override fun stop(jobId: String) {
        synchronized(lock) {
            if (jobId != startedId) return // never started / already stopped
            startedId = null
            sink.onStopped(jobId)
        }
    }

    companion object {
        /**
         * Minimum milliseconds between two notification updates of one job
         * (the brief's ">=1s"; the UI's 500ms store-write throttle is
         * finer than a notification needs — see
         * [ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS]).
         */
        const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 1000L
    }
}

/**
 * The export foreground service (owner finding 2026-11-14): holds the
 * process at foreground priority while an export is RUNNING so Android's
 * OomAdjuster can never move it to the cached state and freeze it
 * mid-render. A deliberately THIN service — every start/progress/stop
 * decision lives in [ExportForegroundController] (JVM-tested) and the
 * notification building is pure — this class only:
 *  - starts foreground IMMEDIATELY in [onStartCommand] (dataSync type,
 *    the honest type: an export writes its output file for the job's
 *    duration) with the job's name and an indeterminate bar until the
 *    first frame count arrives,
 *  - stops (stopForeground REMOVE + stopSelf) on the stop action the
 *    sink sends when the job leaves RUNNING — the service NEVER
 *    outlives the job,
 *  - returns START_NOT_STICKY: a killed process's export is dead
 *    regardless (the queue recovers its RUNNING rows to FAILED on the
 *    next drain), and a sticky restart would show a phantom notification
 *    for a job that no longer runs.
 *
 * The notification channel is IMPORTANCE_LOW (silent, no heads-up): the
 * user is already in the app; the notification exists for the PROCESS
 * PRIORITY, not to nag. Tapping it simply reopens the app.
 */
class ExportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // The job left RUNNING (DONE/CANCELLED/FAILED): drop the
                // priority and the notification, and go away.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification(
                            this,
                            title,
                            framesDone = null,
                            framesTotal = null,
                            percent = 0,
                            indeterminate = true,
                        ),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } catch (e: Exception) {
                    // Only reachable if the system rejected the start
                    // (background-start restrictions). The export then runs
                    // exactly as it did before this service existed — no
                    // priority, and never a crash of the export itself.
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {

        /** Stable notification id: the queue is serial, one export at a
         * time, so one id covers every job without collisions. */
        const val NOTIFICATION_ID = 1

        /** The channel the export notification lives in (IMPORTANCE_LOW —
         * silent; the notification is the visible side of the process
         * priority, not a nag). */
        private const val CHANNEL_ID = "export"

        const val ACTION_START =
            "com.ritim.ritimcraw.export.ExportForegroundService.START"
        const val ACTION_STOP =
            "com.ritim.ritimcraw.export.ExportForegroundService.STOP"

        /** Intent extras: the job id (log legibility only) + the job's
         * display-derived file name (the notification title — the same
         * name the UI row's file carries). */
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_TITLE = "title"

        /**
         * Creates the notification channel (idempotent, best-effort — the
         * MediaStorePublisher contract: a framework refusal must never
         * crash the export path). Called from BOTH the service's start
         * path and the sink's construction so a progress update can
         * never race the channel into nonexistence — a notify() for a
         * missing channel is silently dropped.
         */
        fun ensureChannel(context: Context) {
            try {
                val manager =
                    context.getSystemService(NotificationManager::class.java) ?: return
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.exports_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            } catch (e: Exception) {
                // Never propagate: without the channel the notification is
                // dropped, but the foreground priority itself is unaffected.
            }
        }

        /**
         * Builds the export notification. The shared shape for the start
         * (indeterminate, "Export running") and every progress update
         * (the SAME frame counts the UI row shows — the shared
         * exports_job_running_frames string — with the percentage as the
         * bar).
         */
        fun notification(
            context: Context,
            title: String,
            framesDone: Int?,
            framesTotal: Int?,
            percent: Int,
            indeterminate: Boolean,
        ): Notification {
            val text =
                if (framesDone != null && framesTotal != null && framesTotal > 0) {
                    context.getString(R.string.exports_job_running_frames, framesDone, framesTotal)
                } else {
                    context.getString(R.string.exports_notification_running)
                }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                )
            // Tapping the notification reopens the app (the Exports tab
            // is where the full progress lives); no custom intent — the
            // launcher intent is exactly right.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
            return builder.build()
        }
    }
}

/**
 * The real [ExportForegroundController.Sink]: every framework call the
 * controller's decisions ride on (the MediaStorePublisher shape — a thin,
 * device-gated adapter, all decisions already made above it).
 *
 *  - [onStarted]: startForegroundService with the job description (job id
 *    + title extras) — the service takes foreground priority immediately
 *    in its own onStartCommand.
 *  - [onProgress]: NotificationManager.notify with the SAME notification
 *    id the service's startForeground used — the update replaces the
 *    initial notification in place. Skipped (never an exception) when
 *    notifications are disabled or POST_NOTIFICATIONS was not granted:
 *    the notification is only the VISIBLE side of the priority — the
 *    foreground service holds the process alive either way. The runtime
 *    grant request is UI work, deliberately not part of this change.
 *  - [onStopped]: the stop action — the service stopForeground(REMOVE)s
 *    and stopSelf()s. A plain startService (not the foreground variant)
 *    because the process holds the foreground service at this moment, and
 *    the stop action must never (re)start foreground.
 */
internal class ExportNotificationSink(
    private val context: Context,
) : ExportForegroundController.Sink {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        // Before any notify() can race the service's own start path.
        ExportForegroundService.ensureChannel(context)
    }

    override fun onStarted(jobId: String, title: String) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ExportForegroundService::class.java)
                    .setAction(ExportForegroundService.ACTION_START)
                    .putExtra(ExportForegroundService.EXTRA_JOB_ID, jobId)
                    .putExtra(ExportForegroundService.EXTRA_TITLE, title),
            )
        } catch (e: Exception) {
            // A background-start restriction (foreground service start
            // limits, FGS type restrictions) must never crash the drain:
            // the export simply runs without the extra priority, exactly
            // as it did before this service existed.
        }
    }

    override fun onProgress(
        jobId: String,
        title: String,
        framesDone: Int,
        framesTotal: Int,
        percent: Int,
        indeterminate: Boolean,
    ) {
        if (!notifications.areNotificationsEnabled()) return
        try {
            notifications.notify(
                ExportForegroundService.NOTIFICATION_ID,
                ExportForegroundService.notification(
                    context,
                    title,
                    framesDone,
                    framesTotal,
                    percent,
                    indeterminate,
                ),
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-flight: the foreground
            // priority still holds — nothing to recover.
        }
    }

    override fun onStopped(jobId: String) {
        try {
            context.startService(
                Intent(context, ExportForegroundService::class.java)
                    .setAction(ExportForegroundService.ACTION_STOP)
                    .putExtra(ExportForegroundService.EXTRA_JOB_ID, jobId),
            )
        } catch (e: Exception) {
            // A background-start restriction (or any system refusal) on
            // the STOP delivery must never crash the drain. The service
            // is already on its way out: it stops itself as soon as the
            // system delivers any next start, and a dataSync FGS left
            // running is killed by the system's own timeout — the export
            // itself is DONE/CANCELLED/FAILED at this point regardless.
        }
    }
}

/**
 * Process-wide holder for the app context the export foreground adapter
 * needs. The queue's construction site (the UI layer) must not have to
 * wire Android context into it, so [ExportApp] installs the application
 * context here once at process start, and
 * [ExportForeground.processDefault] resolves the real adapter through it
 * — null (JVM unit tests) resolves to [ExportForeground.None] without
 * touching any Android class.
 */
private object ExportForegroundHost {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun resolve(): ExportForeground =
        appContext
            ?.let { ExportForegroundController(ExportNotificationSink(it)) }
            ?: ExportForeground.None
}

/**
 * The app's [Application] (manifest android:name): exists for exactly one
 * wiring — handing [ExportForegroundHost] the application context at
 * process start, BEFORE any queue is constructed, so the export queue's
 * foreground seam resolves to the real adapter without the UI layer
 * passing a Context around (the queue stays context-free, the way
 * MediaStorePublisher keeps the resolver out of the queue).
 */
class ExportApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ExportForegroundHost.install(this)
    }
}
