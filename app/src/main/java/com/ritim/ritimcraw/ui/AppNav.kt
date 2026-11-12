package com.ritim.ritimcraw.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.AndroidUriResolver
import com.ritim.ritimcraw.data.CollectionRefresh
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.GyroFolderStore
import com.ritim.ritimcraw.data.GyroMatcher
import com.ritim.ritimcraw.data.GyroStore
import com.ritim.ritimcraw.data.GyroTree
import com.ritim.ritimcraw.data.PreviewQualityStore
import com.ritim.ritimcraw.data.RenderBackendStore
import com.ritim.ritimcraw.data.LutStore
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
import com.ritim.ritimcraw.export.ExportStageTiming
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
    // §4.5 icon consistency: outlined/filled glyph pairs per destination,
    // drawn from the cached core Material set. The owner chose these glyphs
    // deliberately (2026-11-12 request) — including the stock Settings gear
    // with its center detail — over the vendored ic_nav_* pair from 1ca091d.
    // Filled variant for the selected item, outlined for the rest — the
    // stock M3 NavigationBar pattern.
    val outlined: ImageVector,
    val filled: ImageVector,
) {
    LIBRARY(R.string.nav_library, Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
    PROJECTS(R.string.nav_projects, Icons.AutoMirrored.Outlined.List, Icons.AutoMirrored.Filled.List),
    EXPORTS(R.string.nav_exports, Icons.AutoMirrored.Outlined.ExitToApp, Icons.AutoMirrored.Filled.ExitToApp),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
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
            // App-level render-backend choice (owner request 2026-11-12,
            // deliverable B): same shared project.json, own top-level
            // "renderBackend" scalar, omit-when-default — the
            // PreviewQualityStore pattern.
            renderBackend = RenderBackendStore(project),
            // The gyro log copies live app-private (files/gyros): the
            // attach flow copies the SAF bytes in (gyro Phase 5), so no
            // persistable URI grant is needed and the logs survive
            // restarts — the same shape as the cube copies above.
            gyros = GyroStore(
                AndroidUriResolver(context),
                project,
                File(context.filesDir, "gyros").apply { mkdirs() },
            ),
            // The granted gyro-folder tree URI (owner request 2026-11-12,
            // G1): the auto-match queries this tree's .gcsv children on
            // every video import. Same shared project.json, own
            // top-level "gyroFolder" scalar, omit-when-absent — the
            // PreviewQualityStore pattern.
            gyroFolder = GyroFolderStore(project),
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
            // preview the owner compares it against. Gyro Phase 5: the same
            // one-shot resolution for the attached gyro log (the job's
            // sourceId -> the source's gyroId -> the copied .gcsv's path,
            // null = no warp) — the export warps every frame at full
            // resolution when the snapshot has stabilization on and a log
            // is attached, matching the paused preview.
            mp4 = Mp4Exporter(
                lutPathFor = { id -> stores.luts.pathFor(id) },
                gyroPathFor = { sourceId -> stores.gyros.pathFor(stores.sources.get(sourceId)?.gyroId) },
                // Export stage timing (owner request 2026-11-13): one
                // sample/average/summary line set per export under the
                // app's export-diagnostics tag, DEBUG builds only — the
                // release build never constructs the sink, so timing is
                // fully off there (byte-identical output, zero cost).
                stageLog = if (BuildConfig.DEBUG) {
                    { line -> Log.i(ExportStageTiming.TAG, line) }
                } else {
                    null
                },
            ),
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
    // The app-level render-backend choice (owner request 2026-11-12 §B):
    // the REQUESTED backend, persisted + refreshed exactly like the
    // preview-quality scalar above. The EFFECTIVE backend is computed per
    // render/export from the runtime gates (BackendRules) — this state is
    // only what the user asked for. Export jobs snapshot it at job start
    // (§14: an ongoing export never changes backend mid-run).
    var renderBackend by remember { mutableStateOf(stores.renderBackend.get()) }
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
    // Collection refresh (spec IN-12): the rotating cursor for the bounded
    // per-open pass — every Library open probes ALL degraded rows plus
    // CollectionRefresh.OK_BATCH healthy ones starting at this position,
    // so repeated opens sweep the whole catalog without a per-open spike
    // (ceil(n/5) opens cover it; the cursor wraps). rememberSaveable keeps
    // the position across config changes; a fresh process starts at 0.
    // The pull-to-refresh full sweep does NOT advance it (it probes
    // everything anyway).
    var refreshCursor by rememberSaveable { mutableStateOf(0) }
    // The pull-to-refresh indicator: true while the manual full sweep
    // runs (the pass is synchronous on the main dispatcher like every
    // other store call, so the flag brackets it for the indicator).
    var libraryRefreshing by remember { mutableStateOf(false) }

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

    // ---- Gyro .gcsv auto-match on import (owner request 2026-11-12, G1) ----
    // The granted gyro folder (Documents/MotionCam on the owner's
    // device) is queried for .gcsv logs after every video import; a
    // same-named log attaches through the SAME path as the editor's
    // manual attach (GyroStore.import -> jniGyroSyncProbe -> setGyro),
    // with the same probe-failure cleanup. The pure matching rule is
    // GyroMatcher (JVM-tested); the tree query is GyroTree
    // (device-gated, exercised by the owner on device).
    var gyroFolder by remember { mutableStateOf(stores.gyroFolder.get()) }
    var gyroAmbiguous by remember { mutableStateOf<Pair<MediaSource, List<GyroTree.GcsvDocument>>?>(null) }
    // The one-time folder offer: shown at most once per session, only
    // after an import that found no granted folder. rememberSaveable
    // keeps the dismissal across config changes; a fresh app start may
    // ask again (deliberate — the feature is invisible until granted).
    var gyroFolderHintShown by rememberSaveable { mutableStateOf(false) }
    val gyroAttachedText = stringResource(R.string.gyro_auto_attached)
    val importDoneText = stringResource(R.string.library_import_done)
    val gyroImportedWithText = stringResource(R.string.library_import_done_with_gyro)
    val gyroFolderHintText = stringResource(R.string.gyro_folder_hint_snackbar)
    val gyroFolderPickText = stringResource(R.string.gyro_folder_hint_action)

    // The SAF tree picker (ACTION_OPEN_DOCUMENT_TREE): persists the
    // grant like the app's other SAF picks, then remembers the tree in
    // GyroFolderStore. Used by the Settings row and the import-time
    // hint's action.
    val gyroFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user backed out
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Non-fatal: the query will fail and the import flow degrades
            // to silent no-attach, the same contract as a lost clip grant.
        }
        stores.gyroFolder.put(uri.toString())
        gyroFolder = uri.toString()
    }

    /**
     * Attaches [gcsvUri] to [source] through the exact editor attach
     * path (gyro Phase 5): GyroStore.import (fingerprint dedupe + app-
     * private copy) -> jniGyroSyncProbe on the clip's fd -> setGyro. A
     * probe failure removes the imported entry again (the library never
     * holds an unusable log) and reports nothing — the auto-match must
     * never fail an import that succeeded. Returns true when attached.
     */
    fun autoAttachGyro(source: MediaSource, gcsvUri: String, fallbackName: String): Boolean {
        return try {
            val imported = stores.gyros.import(gcsvUri, fallbackName)
            val path = stores.gyros.pathFor(imported.id)
            if (path == null) {
                stores.gyros.remove(imported.id)
                false
            } else {
                val fd = try {
                    context.contentResolver
                        .openFileDescriptor(Uri.parse(source.uri), "r")
                        ?.detachFd() ?: -1
                } catch (e: Exception) {
                    -1
                }
                if (fd < 0) {
                    stores.gyros.remove(imported.id)
                    false
                } else {
                    try {
                        val probe = NativeSource.jniGyroSyncProbe(fd, path)
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                        if (probe == null || probe.size < 5) {
                            stores.gyros.remove(imported.id)
                            false
                        } else {
                            val updated = stores.sources.setGyro(source.id, imported.id)
                            updated != null
                        }
                    } catch (e: Exception) {
                        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                        stores.gyros.remove(imported.id)
                        false
                    }
                }
            }
        } catch (e: Exception) {
            // A failed tree read, a vanished document, an import error:
            // the auto-match is best-effort and never blocks the import.
            false
        }
    }

    /**
     * Runs after EVERY successful video import: no granted folder and
     * the hint not yet shown -> offer the folder pick once (a
     * dismissible snackbar action, never a blocking dialog); folder
     * granted -> query its .gcsv children and match. One match
     * auto-attaches (the caller folds the outcome into the import
     * toast); two or more open the ambiguity dialog; zero stays
     * silent (the rule is exact-or-nothing — a wrong .gcsv silently
     * stabilizes with wrong data, so attaching nothing is the right
     * outcome).
     *
     * Returns whether a gyro log ended up attached — the import
     * announcement (owner request 2026-11-12: system toasts, ONE
     * combined message) reads it to pick "Video imported · with gyro
     * data" over the plain "Video imported". An ALREADY-attached clip
     * (a re-import of a clip the editor attached one to) reports
     * true: the auto-match never overwrites an existing attachment
     * and the hint never fires for it, but the data IS on the clip.
     */
    fun autoMatchGyro(source: MediaSource): Boolean {
        if (source.gyroId.isNotBlank()) return true
        val folder = gyroFolder
        if (folder == null) {
            if (!gyroFolderHintShown) {
                gyroFolderHintShown = true
                scope.launch {
                    if (
                        exportSnackbar.showSnackbar(
                            gyroFolderHintText,
                            actionLabel = gyroFolderPickText,
                            duration = SnackbarDuration.Long,
                        ) == SnackbarResult.ActionPerformed
                    ) {
                        gyroFolderPicker.launch(null)
                    }
                }
            }
            return false
        }
        val logs = try {
            GyroTree.listGcsv(context, folder)
        } catch (e: Exception) {
            // Dropped grant or unreachable provider: degrade to silent
            // no-attach (the same contract as a lost clip grant).
            return false
        }
        when (val m = GyroMatcher.match(source.displayName, logs.map { it.displayName })) {
            is GyroMatcher.Match.One -> {
                val doc = logs.first { it.displayName == m.name }
                if (autoAttachGyro(source, doc.uri, doc.displayName)) {
                    sources = stores.sources.all()
                    return true
                }
            }
            is GyroMatcher.Match.Ambiguous ->
                gyroAmbiguous = source to logs.filter { it.displayName in m.names }
            GyroMatcher.Match.None -> Unit // silent: no log matches this clip
        }
        return false
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
            // RAW-13: a Linear DNG is a DNG file for the viewer question too
            // — no raw-DNG reader on the device gets the same Share option.
            if (job.target == Target.DNG || job.target == Target.LINEAR) {
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
    // button disables itself while measuring). After the preview legs, the
    // GLES leg (deliverable C) opens its OWN fd: capability probe +
    // single-frame demosaic parity, the owner's one-tap device entry
    // point for the GPU groundwork (device-test-pending).
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
            val glesFd = context.contentResolver
                .openFileDescriptor(Uri.parse(first.uri), "r")
                ?.detachFd() ?: -1
            if (glesFd >= 0) PreviewMetrics.measureGles(glesFd)
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
                                    // the owner-chosen core Material glyph pair,
                                    // tinted by NavigationBar's own Monet
                                    // selected/unselected colors.
                                    if (selected) d.filled else d.outlined,
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
                        // Gyro Phase 5: the pump resolves the attached log's
                        // path per render request through this (null = no
                        // warp) — the same keying as the export arm above.
                        gyroPathFor = { id ->
                            if (id.isBlank()) null else stores.gyros.pathFor(id)
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
                                // develop (owner request 2026-11-11 §1). The gyro
                                // auto-match (G1) runs after the import succeeds;
                                // it is best-effort and never fails the import.
                                try {
                                    val imported = stores.sources.import(uri)
                                    sources = stores.sources.all()
                                    scope.launch { generateThumbnail(imported) }
                                    // The auto-match runs AFTER the import
                                    // returns: the import announcement never
                                    // waits on the tree query + copy + probe.
                                    // The scope is the main dispatcher, so
                                    // the store access stays single-threaded
                                    // like every other store call.
                                    //
                                    // Import success is a system TOAST, not
                                    // a snackbar (owner request 2026-11-12):
                                    // a plain confirmation needs no tap and
                                    // no layout space, and firing it HERE —
                                    // after the auto-match resolves — lets
                                    // ONE combined message name the gyro
                                    // data when it attached. The attach
                                    // state is known at toast time in this
                                    // flow; it is threaded out of
                                    // autoMatchGyro's return.
                                    scope.launch {
                                        val withGyro = autoMatchGyro(imported)
                                        Toast.makeText(
                                            context,
                                            if (withGyro) gyroImportedWithText else importDoneText,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    Result.success(imported)
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            },
                            // Re-link (spec IN-16): the degraded row's SAF pick
                            // lands here — the fingerprint-checked relink
                            // rebinds the SAME id (grade, gyro, stats and
                            // export history all survive), and a mismatch is
                            // the honest error (the record changes nothing).
                            // The refreshed list state updates the row in
                            // place; the thumbnail the row never could
                            // generate while missing retries through the
                            // backfill on this same composition.
                            onRelink = { sourceId, uri ->
                                try {
                                    val relinked = stores.sources.relink(sourceId, uri)
                                    sources = stores.sources.all()
                                    scope.launch { generateThumbnail(relinked) }
                                    Result.success(relinked)
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            },
                            // Manual full sweep (spec IN-12): pull-to-refresh
                            // re-probes the WHOLE catalog — one cheap metadata
                            // query per record, never an open. The cursor
                            // stays put (the sweep covers everything).
                            onRefresh = {
                                libraryRefreshing = true
                                sources = stores.sources.refreshStatus()
                                libraryRefreshing = false
                            },
                            refreshing = libraryRefreshing,
                            // Bounded per-open pass (spec IN-12): all degraded
                            // rows (few, cheap, recoverable — a re-granted or
                            // re-linked clip returns to OK on the next open)
                            // plus the rotating healthy slice. The cursor
                            // advances by the slice actually taken.
                            onOpenRefresh = {
                                val batch = CollectionRefresh.batch(sources, refreshCursor)
                                if (batch.isNotEmpty()) {
                                    stores.sources.refreshStatus(batch)
                                    sources = stores.sources.all()
                                    refreshCursor = CollectionRefresh.nextCursor(
                                        sources, refreshCursor,
                                    )
                                }
                            },
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
                            // Cancel (owner request 2026-11-13): the queue
                            // flips the row and stops the job itself — a
                            // QUEUED job never starts, a RUNNING one stops
                            // at the next frame boundary. No kick needed:
                            // an active drain re-reads the store itself, and
                            // the revision flow refreshes the rows.
                            onCancel = { job ->
                                queue.cancel(job.id)
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
                            // The quality row persists immediately; the
                            // editor picks the new level up on its next
                            // render request (owner request 2026-11-11b §3).
                            previewQuality = previewQuality,
                            onPreviewQualityChange = { level ->
                                stores.previewQuality.put(level)
                                previewQuality = level
                            },
                            // The backend row persists immediately (owner
                            // request 2026-11-12 §B): the REQUESTED axis.
                            // The next render/export job reads the current
                            // value and computes the effective backend from
                            // the runtime gates — a change applies to the
                            // next job, never to one already running (§14).
                            renderBackend = renderBackend,
                            onRenderBackendChange = { backend ->
                                stores.renderBackend.put(backend)
                                renderBackend = backend
                            },
                            // ---- Gyro folder grant (G1) ----
                            // The durable entry point for the grant: the
                            // row shows the current folder (or the not-set
                            // wording) and re-picks on tap. This row is
                            // what the import-time hint's action opens
                            // too — one grant, one place to change it.
                            gyroFolderLabel = gyroFolder?.let { GyroTree.folderLabel(it) },
                            onPickGyroFolder = { gyroFolderPicker.launch(null) },
                        )
                    }
                }
            }
            // The ambiguity picker (G1): two or more .gcsv logs in the
            // granted folder carry the imported clip's base name — the
            // matcher never guesses, the user picks. Each candidate row
            // attaches through the same auto-attach path (probe cleanup
            // included); Cancel attaches nothing.
            gyroAmbiguous?.let { (source, candidates) ->
                AlertDialog(
                    onDismissRequest = { gyroAmbiguous = null },
                    title = { Text(stringResource(R.string.gyro_ambiguous_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.gyro_ambiguous_body))
                            candidates.forEach { doc ->
                                ListItem(
                                    headlineContent = { Text(doc.displayName) },
                                    modifier = Modifier.clickable {
                                        gyroAmbiguous = null
                                        if (autoAttachGyro(source, doc.uri, doc.displayName)) {
                                            sources = stores.sources.all()
                                            // A plain confirmation toast, not a
                                            // snackbar (owner request 2026-11-12):
                                            // the dialog already carried the
                                            // decision; this only announces the
                                            // outcome — no tap needed, no layout
                                            // space taken.
                                            Toast.makeText(
                                                context,
                                                gyroAttachedText,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { gyroAmbiguous = null },
                        ) {
                            Text(stringResource(R.string.gyro_ambiguous_cancel))
                        }
                    },
                )
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
    // App-level render-backend choice (owner request 2026-11-12 §B):
    // same shared project.json, own top-level "renderBackend" scalar.
    val renderBackend: RenderBackendStore,
    // Gyro log copies (gyro Phase 5): same shared project.json, own
    // "gyros" section (GyroStore) — the attach flow copies the picked
    // .gcsv's bytes app-private, so no persistable URI grant is needed
    // and the log survives restarts, exactly like the cube copies.
    val gyros: GyroStore,
    // The granted gyro-folder tree URI (owner request 2026-11-12, G1):
    // same shared project.json, own top-level "gyroFolder" scalar.
    val gyroFolder: GyroFolderStore,
)
