package com.ritim.ritimcraw.proxy

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
 * What the proxy runner tells its host about the build's process priority —
 * the [com.ritim.ritimcraw.export.ExportForeground] shape, copied VERBATIM for
 * the proxy queue (the owner finding 2026-11-14 is the proxy build's risk too:
 * a minutes-long background encode must never be frozen mid-write — a frozen
 * build leaves a `.tmp`, which the never-half rule turns into wasted work, and
 * the queue would restart it from scratch anyway).
 *
 * While a build runs the host holds the process at FOREGROUND priority (a
 * dataSync foreground service + a low-importance notification with the SAME
 * frame counts the storage page shows) and drops that priority the moment the
 * build lands a terminal state. Tests inject a fake; the app resolves the real
 * adapter through [processDefault].
 */
interface ProxyForeground {

    /** Holds foreground priority for [jobId], showing [title] (the clip's name). */
    fun start(jobId: String, title: String)

    /** Reports frame counts for the started job (ignored for any other). */
    fun progress(jobId: String, framesDone: Long, framesTotal: Long)

    /** Drops the foreground priority held for [jobId] (idempotent). */
    fun stop(jobId: String)

    companion object {
        val None: ProxyForeground = object : ProxyForeground {
            override fun start(jobId: String, title: String) {}
            override fun progress(jobId: String, framesDone: Long, framesTotal: Long) {}
            override fun stop(jobId: String) {}
        }

        /** The queue's constructor default: the real adapter when an app context is wired. */
        fun processDefault(): ProxyForeground = ProxyForegroundHost.resolve()
    }
}

/**
 * The pure start/progress/stop state machine for the proxy notification —
 * the [com.ritim.ritimcraw.export.ExportForegroundController] contract, copied
 * for the proxy queue with long frame counts (the E2/G3 progress shape):
 *  - a [progress] for a job that was never started or was already stopped is
 *    DROPPED — the notification can never outlive its build;
 *  - [stop] lands exactly once per started job; a [start] while another job
 *    still holds the priority stops the OLD one first (the service never
 *    outlives the queue);
 *  - progress is throttled to one sink call per [NOTIFICATION_UPDATE_MIN_INTERVAL_MS]
 *    (the export controller's own 1 s — the first report after a start always
 *    passes so the counts are real immediately).
 * Thread-safe: one lock covers state AND sink calls.
 */
internal class ProxyForegroundController(
    private val sink: Sink,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProxyForeground {

    interface Sink {
        fun onStarted(jobId: String, title: String)
        fun onProgress(jobId: String, title: String, framesDone: Long, framesTotal: Long, percent: Int, indeterminate: Boolean)
        fun onStopped(jobId: String)
    }

    private val lock = Any()
    private var startedId: String? = null
    private var title: String = ""
    private var lastNotifyMs = -1L

    override fun start(jobId: String, title: String) {
        synchronized(lock) {
            val previous = startedId
            if (previous != null && previous != jobId) sink.onStopped(previous)
            startedId = jobId
            this.title = title
            lastNotifyMs = -1L
            sink.onStarted(jobId, title)
        }
    }

    override fun progress(jobId: String, framesDone: Long, framesTotal: Long) {
        synchronized(lock) {
            if (jobId != startedId) return
            val now = clock()
            if (lastNotifyMs >= 0 && now - lastNotifyMs < NOTIFICATION_UPDATE_MIN_INTERVAL_MS) return
            lastNotifyMs = now
            val indeterminate = framesTotal <= 0
            val percent = if (indeterminate) 0 else ((framesDone * 100) / framesTotal).toInt()
            sink.onProgress(jobId, title, framesDone, framesTotal, percent, indeterminate)
        }
    }

    override fun stop(jobId: String) {
        synchronized(lock) {
            if (jobId != startedId) return
            startedId = null
            sink.onStopped(jobId)
        }
    }

    companion object {
        const val NOTIFICATION_UPDATE_MIN_INTERVAL_MS = 1000L
    }
}

/**
 * The proxy foreground service — [com.ritim.ritimcraw.export.
 * ExportForegroundService] with the proxy's own channel and strings: a THIN
 * service (every decision lives in the JVM-tested controller), foreground
 * IMMEDIATELY in onStartCommand (dataSync type), stop-on-action, never
 * sticky, START_NOT_STICKY for the same reason the export service has it: a
 * killed proxy build is dead and the queue re-queues it on the next session.
 */
class ProxyForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification(this, title, framesDone = null, framesTotal = null, percent = 0, indeterminate = true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } catch (e: Exception) {
                    // A system refusal must never crash the build: it simply
                    // runs without the extra priority (the export service's
                    // own contract).
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        /** A different id from the export service's 1: both can run at once. */
        const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "proxy"

        const val ACTION_START = "com.ritim.ritimcraw.proxy.ProxyForegroundService.START"
        const val ACTION_STOP = "com.ritim.ritimcraw.proxy.ProxyForegroundService.STOP"
        const val EXTRA_JOB_ID = "jobId"
        const val EXTRA_TITLE = "title"

        fun ensureChannel(context: Context) {
            try {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.proxy_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            } catch (e: Exception) {
                // Never propagate (the export service's contract).
            }
        }

        fun notification(
            context: Context,
            title: String,
            framesDone: Long?,
            framesTotal: Long?,
            percent: Int,
            indeterminate: Boolean,
        ): Notification {
            val text =
                if (framesDone != null && framesTotal != null && framesTotal > 0) {
                    context.getString(R.string.proxy_notification_frames, framesDone, framesTotal)
                } else {
                    context.getString(R.string.proxy_notification_running)
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
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                )
            }
            return builder.build()
        }
    }
}

/**
 * The real [ProxyForegroundController.Sink] — the [com.ritim.ritimcraw.export.
 * ExportNotificationSink] shape: startForegroundService with the job extras,
 * NotificationManager.notify with the same id the service used, a plain
 * startService for the stop. Every framework refusal is swallowed (the
 * notification is the visible side of the priority, never the build's contract).
 */
internal class ProxyNotificationSink(private val context: Context) : ProxyForegroundController.Sink {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        ProxyForegroundService.ensureChannel(context)
    }

    override fun onStarted(jobId: String, title: String) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyForegroundService::class.java)
                    .setAction(ProxyForegroundService.ACTION_START)
                    .putExtra(ProxyForegroundService.EXTRA_JOB_ID, jobId)
                    .putExtra(ProxyForegroundService.EXTRA_TITLE, title),
            )
        } catch (e: Exception) {
            // Background-start restriction: the build runs without the priority.
        }
    }

    override fun onProgress(
        jobId: String,
        title: String,
        framesDone: Long,
        framesTotal: Long,
        percent: Int,
        indeterminate: Boolean,
    ) {
        if (!notifications.areNotificationsEnabled()) return
        try {
            notifications.notify(
                ProxyForegroundService.NOTIFICATION_ID,
                ProxyForegroundService.notification(context, title, framesDone, framesTotal, percent, indeterminate),
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-flight: the priority still holds.
        }
    }

    override fun onStopped(jobId: String) {
        try {
            context.startService(
                Intent(context, ProxyForegroundService::class.java)
                    .setAction(ProxyForegroundService.ACTION_STOP)
                    .putExtra(ProxyForegroundService.EXTRA_JOB_ID, jobId),
            )
        } catch (e: Exception) {
            // The service is on its way out regardless.
        }
    }
}

/** Process-wide holder for the app context (the ExportForegroundHost shape). */
private object ProxyForegroundHost {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun resolve(): ProxyForeground =
        appContext
            ?.let { ProxyForegroundController(ProxyNotificationSink(it)) }
            ?: ProxyForeground.None
}
