package com.ritim.ritimcraw.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.AndroidUriResolver
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.ui.editor.ClipEditorScreen
import com.ritim.ritimcraw.ui.exports.ExportsScreen
import com.ritim.ritimcraw.ui.library.LibraryScreen
import com.ritim.ritimcraw.ui.projects.ProjectsScreen
import com.ritim.ritimcraw.ui.settings.SettingsScreen
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
    var sources by remember { mutableStateOf(stores.sources.all()) }
    var exportJobs by remember { mutableStateOf(stores.exports.all()) }
    var destination by rememberSaveable { mutableStateOf(Destination.LIBRARY) }
    var editorRoute by rememberSaveable { mutableStateOf<EditorRoute?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
                    ClipEditorScreen(
                        sourceId = r.sourceId,
                        onBack = { editorRoute = null },
                        initialGrade = stores.grades.get(r.sourceId),
                        onGradeChange = { g -> stores.grades.put(r.sourceId, g) },
                    )
                }
            } else {
                Crossfade(targetState = destination, animationSpec = tween(220), label = "browse") { d ->
                    when (d) {
                        Destination.LIBRARY -> LibraryScreen(
                            sources = sources,
                            onOpenClip = { sourceId -> editorRoute = EditorRoute(sourceId) },
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
                            },
                            onClear = {
                                stores.exports.clear()
                                exportJobs = stores.exports.all()
                            },
                        )
                        Destination.SETTINGS -> SettingsScreen()
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
