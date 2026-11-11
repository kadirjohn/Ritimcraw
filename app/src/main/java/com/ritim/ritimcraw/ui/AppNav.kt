package com.ritim.ritimcraw.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.AndroidUriResolver
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.PreviewQualityStore
import com.ritim.ritimcraw.data.LutStore
import com.ritim.ritimcraw.data.GyroStore
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.PresetStore
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.engine.ClipThumbnails
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.engine.PreviewMetrics
import com.ritim.ritimcraw.engine.SourcePathDecision
import com.ritim.ritimcraw.export.BitmapPngWriter
import com.ritim.ritimcraw.export.DngExporter
import com.ritim.ritimcraw.export.ExportIo
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportOpen
import com.ritim.ritimcraw.export.ExportQueue
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.MediaPublish
import com.ritim.ritimcraw.export.MediaStorePublisher
import com.ritim.ritimcraw.export.Mp4Exporter
import com.ritim.ritimcraw.export.PhotoDevelop
import com.ritim.ritimcraw.export.PhotoExporter
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.ui.editor.ClipEditorScreen
import com.ritim.ritimcraw.ui.exports.ExportsScreen
import com.ritim.ritimcraw.ui.library.LibraryScreen
import com.ritim.ritimcraw.ui.projects.ProjectsScreen
import com.ritim.ritimcraw.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
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
    // §4.5 icon consistency: proper outlined/filled glyph pairs per
    // destination. Vendored vector drawables (res/drawable/ic_nav_*),
    // because material-icons-extended is NOT in the offline cache and the
    // cached core set had no fitting glyphs (PlayArrow/ExitToApp were
    // stand-ins). Filled variant for the selected item, outlined for the
    // rest — the stock M3 NavigationBar pattern.
    val outlinedRes: Int,
    val filledRes: Int,
) {
    LIBRARY(
        R.string.nav_library,
        R.drawable.ic_nav_library_outlined,
        R.drawable.ic_nav_library_filled,
    ),
    PROJECTS(
        R.string.nav_projects,
        R.drawable.ic_nav_projects_outlined,
        R.drawable.ic_nav_projects_filled,
    ),
    EXPORTS(
        R.string.nav_exports,
        R.drawable.ic_nav_exports_outlined,
        R.drawable.ic_nav_exports_filled,
    ),
    SETTINGS(
        R.string.nav_settings,
        R.drawable.ic_nav_settings_outlined,
        R.drawable.ic_nav_settings_filled,
    ),
}

/** editor/{sourceId} back stack entry. */
data class EditorRoute(val sourceId: String)

/** The FileProvider authority for opening finished exports (manifest). */
private const val EXPORT_FILE_PROVIDER_AUTHORITY = "com.ritim.ritimcraw.fileprovider"

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
            // The cube copies live app-private (files/luts): the import copies
            // the SAF bytes in, so no persistable URI grant is needed and the
            // library survives restarts (COL-07).
            luts = LutStore(
                AndroidUriResolver(context),
                project,
                File(context.filesDir, "luts").apply { mkdirs() },
            ),
            presets = PresetStore(project),
            // App-level preview quality (owner request 2026-11-11b
            // section 3): same shared project.json, own top-level
            // "previewQuality" scalar, omit-when-default.
            previewQuality = PreviewQualityStore(project),
            // The gyro log copies live app-private (files/gyros): the
            // attach flow copies the SAF bytes in (gyro Phase 5), so no
            // persistable URI grant is needed and the logs survive
            // restarts — the same shape as the cube copies above.
            gyros = GyroStore(
                AndroidUriResolver(context),
                project,
                File(context.filesDir, "gyros").apply { mkdirs() },
            ),
        )
    }
    // The serial queue runner: one job at a time, DNG via DngExporter, MP4
    // via Mp4Exporter. The output lands in the app's export directory.
    val exportDir = remember { File(context.filesDir, "exports").apply { mkdirs() } }
    val queue = remember {
        ExportQueue(
            store = stores.exports,
            dng = DngExporter(),
            // COL-07: the exporter resolves the job snapshot's lutId to the
            // copied cube's path ONCE per export (null = no LUT — the old
            // look), so every frame runs the same stage-20 apply as the
            // preview the owner compares it against.
            mp4 = Mp4Exporter(lutPathFor = { id -> stores.luts.pathFor(id) }),
            // RAW-11 Frame Picker: the photo arm — a picked frame developed
            // at full resolution into a PNG. Same LUT resolution as the MP4
            // arm (the snapshot's lutId -> the copied cube's path, once per
            // job), the same shared export engine thread, and the thin
            // Android PNG adapter (Bitmap.compress, lossless — see
            // PhotoExporter).
            photo = PhotoExporter(
                pngWriter = BitmapPngWriter(),
                lutPathFor = { id -> stores.luts.pathFor(id) },
            ),
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

                override fun outputFile(job: ExportJob, target: Target): File {
                    // Per-job output directory (owner request 2026-11-11 §1:
                    // match the output to the job id): an older DONE row can
                    // never open a later job's file, because a later job of
                    // the same clip writes its own directory. Retry keeps
                    // the same id and overwrites only its own output. The
                    // file name is the job's own (§2b: display-derived when
                    // captured at enqueue, legacy otherwise) — job.fileName
                    // is the single source for every path that touches it.
                    val dir = File(exportDir, job.id)
                    dir.mkdirs()
                    return File(dir, job.fileName)
                }
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
    // The cube library list (COL-07): plain state refreshed after every
    // import/remove so the editor's LUT rows stay current.
    var lutEntries by remember { mutableStateOf(stores.luts.all()) }
    // The saved presets (owner request 2026-11-11 §4): plain state
    // refreshed after every save/delete so the clip-info sheet's rows
    // stay current — the same pattern as the LUT list.
    var presetEntries by remember { mutableStateOf(stores.presets.all()) }
    // The app-level preview-quality choice (owner request 2026-11-11b §3):
    // plain state read once at composition and refreshed on every change,
    // the same pattern as the preset list. The editor reads the CURRENT
    // value on each render request, so a change in Settings applies to the
    // next clip opened — and to the current one after recomposition.
    var previewQuality by remember { mutableStateOf(stores.previewQuality.get()) }
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

    // Library thumbnails + recorded stats (owner request 2026-11-11 §1):
    // one small PNG per clip id under files/thumbnails/, developed ONCE
    // from frame 0 with the neutral grade (the owner accepts the dark
    // look), plus the recorded dims/duration backfilled into the record.
    // The PNG writer is the same thin Android adapter the photo export
    // uses (Bitmap.compress); the generation pass runs entirely on the
    // shared preview engine thread through the editor's session seam.
    val thumbnailDir = remember { File(context.filesDir, "thumbnails").apply { mkdirs() } }
    val thumbnailStore = remember { ThumbnailStore(thumbnailDir) }
    val thumbnails = remember {
        ClipThumbnails(
            store = thumbnailStore,
            pngWriter = BitmapPngWriter(),
        )
    }
    // How many missing thumbnails one Library open generates (bounded, so
    // a huge library opens instantly and fills in while the user looks).
    val thumbnailBatchLimit = 3

    // Generates ONE clip's thumbnail + stats off the UI path and refreshes
    // the list when the record changed (the stats backfill rewrites the
    // persisted record, so the row's resolution/duration survive restarts).
    suspend fun generateThumbnail(source: MediaSource) {
        val uri = source.uri
        val kind = source.kind
        val mime = try {
            context.contentResolver.getType(Uri.parse(uri))
        } catch (e: Exception) {
            null
        }
        thumbnails.generate(
            ClipThumbnails.PendingClip(
                media = source,
                path = SourcePathDecision.decide(kind, mime),
                fdProvider = {
                    try {
                        context.contentResolver
                            .openFileDescriptor(Uri.parse(uri), "r")
                            ?.detachFd() ?: -1
                    } catch (e: Exception) {
                        -1
                    }
                },
                onStats = { w, h, frames, durationUs ->
                    val updated = stores.sources.backfillStats(
                        source.id, w, h, frames, "", durationUs,
                    )
                    if (updated != null && updated != source) {
                        sources = stores.sources.all()
                    }
                },
            )
        )
    }

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

    // DONE rows open on tap (owner request 2026-11-11 §1). The DECISION is
    // ExportOpen (JVM-tested): the job's own media-store row first, the
    // app's own copy through FileProvider when the publish failed, nothing
    // when neither exists. This adapter only dispatches intents — the
    // device-gated half, like MediaStorePublisher.
    val openMissingText = stringResource(R.string.exports_open_missing_body)
    val openDngNoViewerText = stringResource(R.string.exports_open_dng_body)
    val openShareText = stringResource(R.string.exports_open_share)

    fun shareExport(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, openShareText))
        } catch (e: Exception) {
            scope.launch {
                exportSnackbar.showSnackbar(openMissingText, duration = SnackbarDuration.Long)
            }
        }
    }

    fun viewExport(uri: Uri, mime: String, job: ExportJob) {
        // MP4 -> the system default player; DNG -> whatever reads raw DNGs.
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            if (job.target == Target.DNG) {
                // No DNG viewer on the device (owner request §1): an
                // understandable message plus a Share option.
                scope.launch {
                    if (exportSnackbar.showSnackbar(
                            openDngNoViewerText,
                            actionLabel = openShareText,
                            duration = SnackbarDuration.Long,
                        ) == SnackbarResult.ActionPerformed
                    ) {
                        shareExport(uri, mime)
                    }
                }
            } else {
                scope.launch {
                    exportSnackbar.showSnackbar(openMissingText, duration = SnackbarDuration.Long)
                }
            }
        } catch (e: Exception) {
            scope.launch {
                exportSnackbar.showSnackbar(openMissingText, duration = SnackbarDuration.Long)
            }
        }
    }

    fun openExport(job: ExportJob) {
        val name = job.fileName
        val open = ExportOpen.open(
            job,
            listOf(
                // The per-job directory (current layout), then the legacy
                // flat layout for rows written before it. Both candidates
                // use the job's own name (§2b: display-derived when the job
                // captured one, the legacy <sourceId>-<target> otherwise).
                File(File(exportDir, job.id), name),
                File(exportDir, name),
            ),
        )
        val mime = MediaPublish.placement(job.sourceId, job.target, job.outputName).mime
        when (open) {
            is ExportOpen.Open.Store -> viewExport(Uri.parse(open.uri), mime, job)
            is ExportOpen.Open.Internal -> {
                // Temporary content:// window on the app's own copy
                // (FLAG_GRANT_READ_URI_PERMISSION on the intent): the file
                // never leaves files/exports.
                val shared = try {
                    FileProvider.getUriForFile(context, EXPORT_FILE_PROVIDER_AUTHORITY, open.file)
                } catch (e: IllegalArgumentException) {
                    null // outside the declared roots: treat as unopenable
                }
                if (shared != null) {
                    viewExport(shared, mime, job)
                } else {
                    scope.launch {
                        exportSnackbar.showSnackbar(openMissingText, duration = SnackbarDuration.Long)
                    }
                }
            }
            ExportOpen.Open.None ->
                scope.launch {
                    exportSnackbar.showSnackbar(openMissingText, duration = SnackbarDuration.Long)
                }
        }
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

    // LIVE STATUS (owner request 2026-11-11 §1): the queue bumps a revision
    // counter on EVERY store write it makes (RUNNING start, each throttled
    // progress write, the terminal write). Collecting it and re-reading the
    // store refreshes the Exports list WHILE a job runs — "Queued" alone no
    // longer has to mean "it started". The flow is a change signal only:
    // the persisted store stays the single source of truth, so a conflated
    // bump just means "re-read now" and the data itself is never stale.
    LaunchedEffect(queue) {
        queue.revisionFlow().collect { exportJobs = stores.exports.all() }
    }

    // The runner: drains every QUEUED job serially, one at a time. Kicks
    // (enqueue/retry) arrive through snapshotFlow, so THIS EFFECT NEVER
    // RESTARTS — a LaunchedEffect(queueKick) restart would cancel the
    // in-flight drain mid-job (the exporter's engine work would be stranded
    // between the RUNNING and terminal writes). Kicks are collected
    // SEQUENTIALLY: a kick that arrives while a drain runs waits until the
    // drain returns, and the drain's own loop (it runs until no job is
    // QUEUED) has usually consumed the new job already; runAll's kick guard
    // makes a concurrent call a no-op anyway. The initial value (0) is
    // skipped.
    LaunchedEffect(Unit) {
        snapshotFlow { queueKick }.collect {
            if (it > 0) {
                queue.runAll()
            }
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
                                    // Filled variant for the selected item (DESIGN.md Icons):
                                    // the vendored glyph pair, tinted by
                                    // NavigationBar's own Monet
                                    // selected/unselected colors.
                                    painterResource(
                                        if (selected) d.filledRes else d.outlinedRes
                                    ),
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
                    // The clip's display name for the top bar (owner request
                    // §2b): the real file name when known, the id otherwise.
                    // Keyed to the clip and refreshed by the backfill below.
                    var sourceName by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.displayOrId ?: r.sourceId)
                    }
                    // Persisted import stats shape the preview placeholder
                    // before the first rendered frame (PreviewSurface's ratio
                    // fallback chain); 0/0 when the engine pass never probed.
                    var sourceDims by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.let { it.width to it.height } ?: (0 to 0))
                    }
                    // Manual rotation delta (owner request 2026-11-11 §5):
                    // clip-keyed read of the persisted record; each rotate
                    // tap (the clip-info sheet's rows) accumulates the delta
                    // through RotationState.applyManual, persists via
                    // setManualRotation (normalized absolute, omit-when-
                    // default in project.json) and refreshes this state so
                    // the preview, the sheet's rotation row and both export
                    // enqueues all see the same value.
                    var manualRotation by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.manualRotationDeg ?: 0)
                    }
                    // Gyro log attachment (gyro Phase 5): clip-keyed read of
                    // the persisted MediaSource.gyroId, refreshed after each
                    // attach/remove — the same shape as manualRotation. The
                    // probe result is keyed to the SAME id (a re-attach
                    // resets it); null = no log / failed probe, which the
                    // group maps to the honest no-data status.
                    var gyroId by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.gyroId ?: "")
                    }
                    var gyroSync by remember(r.sourceId, gyroId) {
                        mutableStateOf<DoubleArray?>(null)
                    }
                    val sourceUri by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.uri)
                    }
                    // Which engine opens the clip: the stored kind is
                    // authoritative; the container mime (ContentResolver) is
                    // the rescue for clips imported before detection landed.
                    // A null mime (resolver failed / non-content URI) falls
                    // back to the stored kind — SourcePathDecision handles it.
                    val sourceKind by remember(r.sourceId) {
                        mutableStateOf(stores.sources.get(r.sourceId)?.kind ?: SourceKind.MCRAW)
                    }
                    val sourceMime: String? by remember(r.sourceId) {
                        mutableStateOf(
                            sourceUri?.let { uri ->
                                try {
                                    context.contentResolver.getType(Uri.parse(uri))
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        )
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
                    // Gyro sync probe (gyro Phase 5): runs once per (clip,
                    // log) pair, off the composition — it opens a throwaway
                    // Source on the clip's fd (the same sourceFdProvider the
                    // preview uses) and reads the copied .gcsv's path. A
                    // failure (bad .gcsv, no native lib) leaves gyroSync
                    // null — the NO_DATA verdict, never a fabricated one.
                    // The attach/remove flows below also write gyroSync
                    // directly (they probe synchronously), so this effect
                    // only needs to cover the clip-open re-probe.
                    LaunchedEffect(r.sourceId, gyroId) {
                        if (gyroId.isBlank()) {
                            gyroSync = null
                        } else {
                            val path = stores.gyros.pathFor(gyroId)
                            if (path == null) {
                                gyroSync = null
                            } else {
                                val fd = sourceFdProvider()
                                gyroSync = if (fd < 0) {
                                    null
                                } else {
                                    try {
                                        val probe = NativeSource.jniGyroSyncProbe(fd, path)
                                        // The runner owns the fd's lifetime:
                                        // close it here (the native side
                                        // dup'ed it on open).
                                        runCatching {
                                            ParcelFileDescriptor.adoptFd(fd).close()
                                        }
                                        probe
                                    } catch (e: Exception) {
                                        runCatching {
                                            ParcelFileDescriptor.adoptFd(fd).close()
                                        }
                                        null
                                    }
                                }
                            }
                        }
                    }
                    LaunchedEffect(r.sourceId) {
                        val source = stores.sources.get(r.sourceId)
                        if (source == null) {
                            sourceFd = -1
                            accessLost = false
                            sourceDims = 0 to 0
                        } else {
                            sourceDims = source.width to source.height
                            // First-open backfill (owner request §2b): a
                            // record imported before display names existed
                            // shows the id until the resolver can name it —
                            // fill it in now and refresh the top bar.
                            val filled = stores.sources.backfillDisplayName(r.sourceId)
                            if (filled != null && filled.displayName.isNotBlank()) {
                                sourceName = filled.displayOrId
                                sources = stores.sources.all()
                            }
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
                            // The output name is captured from the clip's
                            // display name (owner request §2b: real file
                            // name, cleaned; null when the resolver could
                            // not name it — the job then uses the legacy
                            // <sourceId>-<target> name).
                            val display = stores.sources.get(r.sourceId)?.displayName
                            stores.exports.add(
                                ExportJob(
                                    id = "job-" + System.nanoTime(),
                                    sourceId = r.sourceId,
                                    snapshot = stores.grades.get(r.sourceId),
                                    target = target,
                                    state = State.QUEUED,
                                    // The RAW display name, not displayOrId: an
                                    // unnamed clip must fall to the legacy
                                    // <sourceId>-<target> name, not capture
                                    // "<id>.dng".
                                    outputName = display?.takeIf { it.isNotBlank() }
                                        ?.let { ExportQueue.displayOutputName(it, target) },
                                    // Manual rotation snapshot at queue time
                                    // (§5, the grade-snapshot rule): a later
                                    // rotate tap must not change an in-flight
                                    // export.
                                    manualRotationDeg = manualRotation,
                                )
                            )
                            exportJobs = stores.exports.all()
                            queueKick += 1
                            enqueueFeedback()
                        },
                        // ---- Frame Picker (RAW-11) ----
                        // Develops the transport's CURRENT frame as a photo
                        // (PNG) through the SAME serial queue. The grade
                        // snapshot is taken at queue time, exactly like
                        // onExport: separate = false carries the clip's
                        // CURRENT grade (the photo matches the preview),
                        // separate = true carries a FRESH neutral grade
                        // (the clip's own grade is untouched). The frame
                        // index is the editor's 0-based transport position.
                        onDevelopPhoto = { frameIndex, separate ->
                            val display = stores.sources.get(r.sourceId)?.displayName
                            stores.exports.add(
                                ExportJob(
                                    id = "job-" + System.nanoTime(),
                                    sourceId = r.sourceId,
                                    snapshot = PhotoDevelop.snapshotFor(
                                        stores.grades.get(r.sourceId),
                                        separate,
                                    ),
                                    target = Target.PHOTO,
                                    state = State.QUEUED,
                                    frameIndex = frameIndex,
                                    outputName = display?.takeIf { it.isNotBlank() }
                                        ?.let { ExportQueue.displayOutputName(it, Target.PHOTO) },
                                    // Manual rotation snapshot at queue time
                                    // (§5, the grade-snapshot rule).
                                    manualRotationDeg = manualRotation,
                                )
                            )
                            exportJobs = stores.exports.all()
                            queueKick += 1
                            enqueueFeedback()
                        },
                        sourceFd = sourceFd,
                        sourceFdProvider = sourceFdProvider,
                        accessLost = accessLost,
                        sourceWidth = sourceDims.first,
                        sourceHeight = sourceDims.second,
                        sourceKind = sourceKind,
                        sourceMime = sourceMime,
                        // The top bar's clip name (§2b): display name with
                        // the id as fallback — displayOrId never blank.
                        sourceName = sourceName,
                        // ---- LUT wiring (COL-07) ----
                        lutEntries = lutEntries,
                        // Import = copy + native probe + TITLE rename. A
                        // probe failure (bad .cube, or no native lib) removes
                        // the entry again so the library never holds an
                        // unapplicable cube; the message reaches the
                        // editor's local error line.
                        onImportLut = { uri, fallbackName ->
                            try {
                                val imported = stores.luts.import(uri, fallbackName)
                                val path = stores.luts.pathFor(imported.id)
                                if (path == null) {
                                    stores.luts.remove(imported.id)
                                    lutEntries = stores.luts.all()
                                    Result.failure(
                                        IllegalStateException(
                                            context.getString(R.string.editor_lut_import_failed)
                                        )
                                    )
                                } else {
                                    try {
                                        val title = NativeSource.jniLutProbe(path)
                                        if (title.isNullOrBlank()) {
                                            stores.luts.setTitle(imported.id, imported.name)
                                        } else {
                                            stores.luts.setTitle(imported.id, title)
                                        }
                                        lutEntries = stores.luts.all()
                                        Result.success(imported)
                                    } catch (e: Exception) {
                                        // The cube does not parse: drop it —
                                        // an unapplicable LUT must not stay
                                        // selectable.
                                        stores.luts.remove(imported.id)
                                        lutEntries = stores.luts.all()
                                        Result.failure(e)
                                    }
                                }
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        },
                        onRemoveLut = { lut ->
                            stores.luts.remove(lut.id)
                            lutEntries = stores.luts.all()
                        },
                        lutPathFor = { id -> stores.luts.pathFor(id) },
                        // ---- Presets (owner request 2026-11-11 §4) ----
                        // The rows live in the clip-info sheet; AppNav owns
                        // the store. Save snapshots the clip's CURRENT grade
                        // (the same store-backed read the export snapshot
                        // uses), delete removes by id; both refresh the
                        // list state so the sheet stays current.
                        presets = presetEntries,
                        onSavePreset = { name ->
                            stores.presets.put(name, stores.grades.get(r.sourceId))
                            presetEntries = stores.presets.all()
                        },
                        onRemovePreset = { id ->
                            stores.presets.remove(id)
                            presetEntries = stores.presets.all()
                        },
                        // ---- Preview quality (owner request
                        // 2026-11-11b §3) ----
                        // The CURRENT app-level choice: playback + scrub
                        // renders read it per request; pause/clip-end
                        // settle semantics are unchanged inside the editor.
                        previewQuality = previewQuality,
                        // ---- Manual rotation (owner request 2026-11-11 §5) ----
                        // The editor displays the persisted delta; a rotate
                        // tap accumulates ±90 through the pure
                        // RotationState.applyManual, persists the normalized
                        // absolute value (per clip, omit-when-default), and
                        // refreshes the clip-keyed state. Exports snapshot it
                        // at enqueue above.
                        manualRotationDeg = manualRotation,
                        onRotate = { byDeg ->
                            val next = com.ritim.ritimcraw.data.RotationState.applyManual(
                                manualRotation, byDeg,
                            )
                            val updated = stores.sources.setManualRotation(r.sourceId, next)
                            if (updated != null) {
                                manualRotation = updated.manualRotationDeg
                            }
                        },
                        // ---- Stabilization wiring (gyro Phase 5) ----
                        // The persisted attachment drives the group's
                        // status; the probe's numbers arrive through
                        // gyroSync above. Attach = copy + probe + persist:
                        // a probe failure removes the entry again so the
                        // library never holds an unusable log (the same
                        // rule as a bad .cube), and the message reaches
                        // the group's local error line.
                        gyroId = gyroId,
                        gyroNameFor = { id ->
                            if (id.isBlank()) null else stores.gyros.get(id)?.name
                        },
                        gyroSync = gyroSync,
                        containerFocalMm = (gyroSync?.get(3) ?: 0.0).toFloat(),
                        onAttachGyro = { uri, fallbackName ->
                            try {
                                val imported = stores.gyros.import(uri, fallbackName)
                                val path = stores.gyros.pathFor(imported.id)
                                if (path == null) {
                                    stores.gyros.remove(imported.id)
                                    Result.failure(
                                        IllegalStateException(
                                            context.getString(
                                                R.string.editor_stabilization_gyro_import_failed
                                            )
                                        )
                                    )
                                } else {
                                    val fd = sourceFdProvider()
                                    if (fd < 0) {
                                        stores.gyros.remove(imported.id)
                                        Result.failure(
                                            IllegalStateException(
                                                context.getString(
                                                    R.string.editor_stabilization_gyro_import_failed
                                                )
                                            )
                                        )
                                    } else {
                                        try {
                                            val probe = NativeSource.jniGyroSyncProbe(fd, path)
                                            runCatching {
                                                ParcelFileDescriptor.adoptFd(fd).close()
                                            }
                                            if (probe == null || probe.size < 5) {
                                                // The log does not parse or
                                                // the anchors are absent:
                                                // drop it — an unusable log
                                                // must not stay attached.
                                                stores.gyros.remove(imported.id)
                                                Result.failure(
                                                    IllegalStateException(
                                                        context.getString(
                                                            R.string.editor_stabilization_gyro_import_failed
                                                        )
                                                    )
                                                )
                                            } else {
                                                val updated =
                                                    stores.sources.setGyro(r.sourceId, imported.id)
                                                if (updated != null) {
                                                    gyroId = updated.gyroId
                                                    gyroSync = probe
                                                }
                                                Result.success(probe)
                                            }
                                        } catch (e: Exception) {
                                            runCatching {
                                                ParcelFileDescriptor.adoptFd(fd).close()
                                            }
                                            stores.gyros.remove(imported.id)
                                            Result.failure(e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        },
                        onRemoveGyro = {
                            val updated = stores.sources.setGyro(r.sourceId, "")
                            if (updated != null) {
                                gyroId = updated.gyroId
                                gyroSync = null
                            }
                        },
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
                                // (fingerprint dedupe keeps ids and grades stable),
                                // then generate the thumbnail + recorded stats
                                // asynchronously — the import never blocks on the
                                // develop (owner request 2026-11-11 §1).
                                try {
                                    val imported = stores.sources.import(uri)
                                    sources = stores.sources.all()
                                    scope.launch { generateThumbnail(imported) }
                                    Result.success(imported)
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            },
                            // Revoked-access rows retry their thumbnail after the
                            // grant returns: the backfill below picks them up on
                            // the next Library composition too.
                            onGrantAccess = { sources = stores.sources.refreshAccess() },
                            thumbnailAt = { id ->
                                // The persisted PNG's path, or null: the row
                                // shows the placeholder while missing.
                                thumbnailStore.fileFor(id)?.absolutePath
                            },
                            thumbnailBatchLimit = thumbnailBatchLimit,
                            onGenerateThumbnails = { pending ->
                                // Bounded, cancellable backfill for records
                                // imported before this pass existed: the Library
                                // screen hands over the pending ids in list
                                // order, this runner generates them one by one
                                // (each on the preview engine thread) and stops
                                // when the composable leaves composition —
                                // cancelling the coroutine cancels the pass.
                                scope.launch {
                                    for (id in pending) {
                                        val source = stores.sources.get(id) ?: continue
                                        generateThumbnail(source)
                                    }
                                }
                            },
                            // E2 delete: the ONE composition of the store
                            // removes (ClipDeletion documents the scope) —
                            // record + grade + thumbnail PNG, never the
                            // user's original SAF file, never export
                            // history. Refresh through the same state the
                            // import path uses, so the list (and the
                            // empty-library state after deleting
                            // everything) updates in place.
                            onDeleteClips = { ids ->
                                com.ritim.ritimcraw.data.ClipDeletion.delete(
                                    ids = ids,
                                    sources = stores.sources,
                                    grades = stores.grades,
                                    thumbnails = thumbnailStore,
                                )
                                sources = stores.sources.all()
                            },
                        )
                        Destination.PROJECTS -> ProjectsScreen()
                        Destination.EXPORTS -> ExportsScreen(
                            jobs = exportJobs,
                            // Rows show the clip's display name (§2b); the
                            // id is the fallback for sources whose name the
                            // resolver could not read.
                            sourceNames = sources.associate { it.id to it.displayOrId },
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
                            onOpen = ::openExport,
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
                            // The quality row persists immediately; the
                            // editor picks the new level up on its next
                            // render request (owner request 2026-11-11b §3).
                            previewQuality = previewQuality,
                            onPreviewQualityChange = { level ->
                                stores.previewQuality.put(level)
                                previewQuality = level
                            },
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
    val luts: LutStore,
    // Named grade presets (owner request 2026-11-11 §4): same shared
    // project.json, own "presets" section (PresetStore).
    val presets: PresetStore,
    // App-level preview quality (owner request 2026-11-11b §3): same
    // shared project.json, own top-level "previewQuality" scalar.
    val previewQuality: PreviewQualityStore,
    // Gyro log copies (gyro Phase 5): same shared project.json, own
    // "gyros" section (GyroStore) — the attach flow copies the picked
    // .gcsv's bytes app-private, so no persistable URI grant is needed
    // and the log survives restarts, exactly like the cube copies.
    val gyros: GyroStore,
)
