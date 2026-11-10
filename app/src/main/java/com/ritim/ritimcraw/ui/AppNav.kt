package com.ritim.ritimcraw.ui

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.AndroidUriResolver
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.engine.PreviewMetrics
import com.ritim.ritimcraw.export.DngExporter
import com.ritim.ritimcraw.export.ExportIo
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportQueue
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.MediaStorePublisher
import com.ritim.ritimcraw.export.Mp4Exporter
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.ui.editor.ClipEditorScreen
import com.ritim.ritimcraw.ui.exports.ExportsScreen
import com.ritim.ritimcraw.ui.library.LibraryScreen
import com.ritim.ritimcraw.ui.projects.ProjectsScreen
import com.ritim.ritimcraw.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.io.File

/**
 * Four browse destinations (sprint plan Task 3 routes: library, projects,
 * exports, settings) plus editor/{sourceId}.
 *
 * Deviation from the plan's "navigation-compose" wording: the offline Gradle
 * cache has no navigation-compose artifact wired into this module, so
 * navigation is plain Kotlin state — a selected-destination enum + an editor
 * back stack entry — with Crossfade transitions. Recorded in the task report.
 */
enum class Destination(
    val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
) {
    // material-icons-extended is NOT in the offline cache, so the nav icons
    // are drawn from the cached core set: PlayArrow (library) and ExitToApp
    // (exports) stand in for VideoLibrary/Output. Recorded in the task report.
    LIBRARY(R.string.nav_library, Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
    PROJECTS(R.string.nav_projects, Icons.AutoMirrored.Outlined.List, Icons.AutoMirrored.Filled.List),
    EXPORTS(R.string.nav_exports, Icons.AutoMirrored.Outlined.ExitToApp, Icons.AutoMirrored.Filled.ExitToApp),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** editor/{sourceId} back stack entry. */
data class EditorRoute(val sourceId: String)

/**
 * App shell wiring: owns the stores (files/project.json), the source list,
 * and the current destination. Import is a placeholder action in this build —
 * the SAF picker lands with the device pass (sprint plan Task 4).
 */
@Composable
fun AppNav() {
    val context = LocalContext.current
    val stores = remember {
        val project = File(context.filesDir, "project.json")
        Stores(
            sources = SourceRepository(AndroidUriResolver(context), project),
            grades = GradeStore(project),
            exports = ExportStore(project),
        )
    }
    // The serial queue runner: one job at a time, DNG via DngExporter, MP4
    // via Mp4Exporter. The output lands in the app's export directory.
    val exportDir = remember { File(context.filesDir, "exports").apply { mkdirs() } }
    val queue = remember {
        ExportQueue(
            store = stores.exports,
            dng = DngExporter(),
            mp4 = Mp4Exporter(),
            io = object : ExportIo {
                override fun sourceFd(job: ExportJob): Int {
                    // The editor's open read-only fd is per-screen; the queue
                    // opens its own through the resolver (same pattern as the
                    // preview path).
                    val source = stores.sources.get(job.sourceId) ?: return -1
                    return try {
                        val p = context.contentResolver
                            .openFileDescriptor(Uri.parse(source.uri), "r")
                        p?.detachFd() ?: -1 // the runner owns the fd's lifetime
                    } catch (e: Exception) {
                        -1
                    }
                }

                override fun closeFd(fd: Int) {
                    // The runner hands the fd back after the job; the native
                    // side dup'ed it on open, so closing the original here
                    // cannot cut the export short — it only stops the leak.
                    // adoptFd takes ownership of the raw int fd (no dup) and
                    // close() closes it.
                    if (fd >= 0) {
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                    }
                }

                override fun outputFile(job: ExportJob, target: Target): File =
                    File(exportDir, ExportQueue.outputName(job.sourceId, target))
            },
            // Finished exports are also published to the system media store
            // (Movies/ritiMCRAW for MP4, Pictures/ritiMCRAW for DNG): the
            // internal copy stays the source of truth, the publish is
            // best-effort, and a publish failure never fails the job.
            publisher = MediaStorePublisher(context),
        )
    }
    // Queue bookkeeping: a monotonically increasing kick counter — every
    // enqueue/retry bumps it and the runner effect drains the queue.
    var queueKick by remember { mutableStateOf(0) }
    var sources by remember { mutableStateOf(stores.sources.all()) }
    var exportJobs by remember { mutableStateOf(stores.exports.all()) }
    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    // EditorRoute is a data class — rememberSaveable cannot put it in a
    // Bundle (owner finding 2026-11-10: backgrounding the app with the
    // editor open crashed saving state). Only the sourceId string is saved;
    // the route object is derived from it.
    var editorSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val editorRoute: EditorRoute? = editorSourceId?.let { EditorRoute(it) }
    // Debug-only measurement harness state (PreviewMetrics): one run at a
    // time, kicked from Settings' Measure action.
    var measuring by remember { mutableStateOf(false) }
    var measureKick by remember { mutableStateOf(0) }

    // Enqueue feedback (owner finding 2026-11-10: Export MP4 gave NO
    // visible response — the job silently appeared only in the Exports
    // tab). The snackbar host lives on THIS Scaffold; the shell shows it
    // over whatever screen is composed, editor included.
    val exportSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val exportQueuedText = stringResource(R.string.export_queued_snackbar)
    fun enqueueFeedback() {
        // SHORT (not LONG): confirmation, not an error the user must read.
        scope.launch { exportSnackbar.showSnackbar(exportQueuedText, duration = SnackbarDuration.Short) }
    }

    // Process-start recovery (owner finding 2026-11-10): a crash can leave
    // jobs persisted as RUNNING — a dead process's export. Mark them FAILED
    // with the interrupted cause BEFORE the UI shows "Exporting…" forever;
    // runAll() also recovers, but it only runs after an enqueue kick.
    LaunchedEffect(Unit) {
        if (queue.recoverInterrupted() > 0) {
            exportJobs = stores.exports.all()
        }
    }

    // The runner: drains every QUEUED job serially, then refreshes the UI
    // list. Runs whenever something was enqueued or retried.
    LaunchedEffect(queueKick) {
        if (queueKick > 0) {
            queue.runAll()
            exportJobs = stores.exports.all()
        }
    }

    // The measurement harness run: opens the FIRST imported clip read-only
    // and hands the fd to PreviewMetrics (which closes it). The summary
    // block lands in logcat under "ritim-pm"; the run is serial (the
    // button disables itself while measuring).
    LaunchedEffect(measureKick) {
        if (measureKick == 0) return@LaunchedEffect
        val first = sources.firstOrNull { it.access == Access.OK }
        if (first == null) {
            measuring = false
            return@LaunchedEffect
        }
        try {
            val fd = context.contentResolver
                .openFileDescriptor(Uri.parse(first.uri), "r")
                ?.detachFd() ?: -1
            if (fd >= 0) PreviewMetrics.measure(fd)
        } catch (e: Exception) {
            // The harness logs its own failures; a lost grant degrades to
            // "no run", never a crash.
        } finally {
            measuring = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // The editor has no snackbar host of its own (its chrome is another
        // worker's file), so the app shell hoists one: the enqueue feedback
        // ("Export queued — see Exports") shows over the editor too. The
        // editor's own Scaffold sits inside; only one host is visible at a
        // time (the inner host renders when its screen is composed).
        snackbarHost = { SnackbarHost(exportSnackbar) },
        bottomBar = {
            // Editors are full screen without nav (DESIGN.md Layout).
            if (editorRoute == null) {
                NavigationBar {
                    Destination.entries.forEach { d ->
                        val selected = destination == d
                        NavigationBarItem(
                            selected = selected,
                            onClick = { destination = d },
                            icon = {
                                Icon(
                                    // Filled variant for the selected item (DESIGN.md Icons).
                                    imageVector = if (selected) d.filled else d.outlined,
                                    contentDescription = stringResource(d.labelRes),
                                )
                            },
                            label = { Text(stringResource(d.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val route = editorRoute
            if (route != null) {
                // Fade between browse and editor (DESIGN.md Motion).
                Crossfade(targetState = route, animationSpec = tween(220), label = "editor") { r ->
                    // Open the clip read-only for the preview pipeline:
                    // ContentResolver.openFileDescriptor(uri, "r"). The
                    // source file is NEVER written (non-negotiable). The
                    // long-lived pfd is only the editor's availability
                    // signal (canRender); every RENDER opens its own fresh
                    // descriptor via sourceFdProvider, because dup() shares
                    // the file offset — reusing one fd across renders
                    // leaves it mid-file and the next open reads a header
                    // from the middle of the clip ("Invalid container
                    // version"). A revoked grant or a moved file yields
                    // accessLost — the editor degrades to its access-lost
                    // state (existing pattern), never a crash.
                    var pfd by remember(r.sourceId) {
                        mutableStateOf<ParcelFileDescriptor?>(null)
                    }
                    var sourceFd by remember(r.sourceId) { mutableStateOf(-1) }
                    var accessLost by remember(r.sourceId) { mutableStateOf(false) }
                    val sourceUri by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.uri)
                    }
                    // Fresh read-only descriptor per render; PreviewRenderer
                    // closes it in a finally. -1 (open failed) degrades to
                    // the placeholder error, never a crash.
                    val sourceFdProvider: () -> Int = remember(r.sourceId) {
                        {
                            if (sourceUri == null) {
                                -1
                            } else {
                                try {
                                    context.contentResolver
                                        .openFileDescriptor(Uri.parse(sourceUri), "r")
                                        ?.detachFd() ?: -1
                                } catch (e: Exception) {
                                    -1
                                }
                            }
                        }
                    }
                    LaunchedEffect(r.sourceId) {
                        val source = stores.sources.get(r.sourceId)
                        if (source == null) {
                            sourceFd = -1
                            accessLost = false
                        } else {
                            try {
                                val p = context.contentResolver
                                    .openFileDescriptor(Uri.parse(source.uri), "r")
                                if (p == null) {
                                    accessLost = true
                                } else {
                                    pfd = p
                                    sourceFd = p.fd
                                    accessLost = false
                                }
                            } catch (e: Exception) {
                                accessLost = true
                            }
                        }
                    }
                    DisposableEffect(r.sourceId) {
                        onDispose { runCatching { pfd?.close() } }
                    }
                    ClipEditorScreen(
                        sourceId = r.sourceId,
                        onBack = { editorSourceId = null },
                        initialGrade = stores.grades.get(r.sourceId),
                        onGradeChange = { g -> stores.grades.put(r.sourceId, g) },
                        onExport = { target ->
                            // Snapshot the CURRENT grade at queue time (the
                            // spec: a later edit must not change an in-flight
                            // export) and enqueue; the runner picks it up.
                            stores.exports.add(
                                ExportJob(
                                    id = "job-" + System.nanoTime(),
                                    sourceId = r.sourceId,
                                    snapshot = stores.grades.get(r.sourceId),
                                    target = target,
                                    state = State.QUEUED,
                                )
                            )
                            exportJobs = stores.exports.all()
                            queueKick += 1
                            enqueueFeedback()
                        },
                        sourceFd = sourceFd,
                        sourceFdProvider = sourceFdProvider,
                        accessLost = accessLost,
                    )
                }
            } else {
                Crossfade(targetState = destination, animationSpec = tween(220), label = "browse") { d ->
                    when (d) {
                        Destination.LIBRARY -> LibraryScreen(
                            sources = sources,
                            onOpenClip = { sourceId -> editorSourceId = sourceId },
                            onImport = { uri ->
                                // SAF pick landed: import through the repository
                                // (fingerprint dedupe keeps ids and grades stable).
                                try {
                                    val imported = stores.sources.import(uri)
                                    sources = stores.sources.all()
                                    Result.success(imported)
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            },
                            onGrantAccess = { sources = stores.sources.refreshAccess() },
                        )
                        Destination.PROJECTS -> ProjectsScreen()
                        Destination.EXPORTS -> ExportsScreen(
                            jobs = exportJobs,
                            onRetry = { job ->
                                // Retry re-queues with the same snapshot.
                                stores.exports.retry(job.id)
                                exportJobs = stores.exports.all()
                                queueKick += 1
                                enqueueFeedback()
                            },
                            onClear = {
                                stores.exports.clear()
                                exportJobs = stores.exports.all()
                            },
                        )
                        Destination.SETTINGS -> SettingsScreen(
                            onMeasure = if (BuildConfig.DEBUG) {
                                {
                                    if (!measuring) {
                                        measuring = true
                                        measureKick += 1
                                    }
                                }
                            } else {
                                null
                            },
                            measuring = measuring,
                        )
                    }
                }
            }
        }
    }
}

private class Stores(
    val sources: SourceRepository,
    val grades: GradeStore,
    val exports: ExportStore,
)
