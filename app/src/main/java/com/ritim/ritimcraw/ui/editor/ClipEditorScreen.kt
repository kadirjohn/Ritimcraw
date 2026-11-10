package com.ritim.ritimcraw.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.engine.EditorPreviewSession
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.engine.PreviewFrame
import com.ritim.ritimcraw.engine.PreviewRenderer
import com.ritim.ritimcraw.engine.PreviewSessions
import com.ritim.ritimcraw.engine.PreviewTiers
import com.ritim.ritimcraw.engine.SourcePath
import com.ritim.ritimcraw.engine.SourcePathDecision
import com.ritim.ritimcraw.engine.PtsTimeline
import com.ritim.ritimcraw.engine.RenderGate
import com.ritim.ritimcraw.engine.RenderRequest
import com.ritim.ritimcraw.engine.SliderTiers
import com.ritim.ritimcraw.engine.RenderDebounce
import com.ritim.ritimcraw.ui.theme.RitimTheme
import com.ritim.ritimcraw.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Pure preview-area decision logic (JVM-testable without Compose): which
 * state the preview area shows, and whether rendering is possible at all.
 */
object EditorPreviewLogic {

    /**
     * State for the preview area given the render bookkeeping. A failed
     * render keeps the last good frame when there is one (grading continues
     * on the stale frame rather than yanking the image away); with no frame
     * at all the SAME placeholder shows as before this path existed.
     */
    fun stateOf(
        renderRequested: Boolean,
        renderInFlight: Boolean,
        hasFrame: Boolean,
        lastRenderFailed: Boolean,
    ): PreviewState = when {
        renderInFlight -> PreviewState.RENDERING
        hasFrame -> PreviewState.FRAME
        renderRequested && lastRenderFailed -> PreviewState.FAILED
        else -> PreviewState.IDLE
    }

    /**
     * True when the preview path can run: a usable fd AND the native
     * engine present. When false the placeholder shows and the render
     * button stays disabled — the editor is still a working grade panel.
     */
    fun canRender(fd: Int, nativeAvailable: Boolean): Boolean = fd >= 0 && nativeAvailable

    /**
     * True when the clip's CFA-stage toggle rows (Highlight Reconstruction,
     * Keep Vignetting) belong in the Color tab: those toggles drive MCRAW
     * develop stages that have no meaning on decoded YUV frames, so encoded
     * (Mi-Log MP4) clips hide them. Everything else stays — the sliders and
     * As Shot work identically on both paths (the full Grade passes through
     * the seam; developEncoded consumes tempK/tint/exposureEv today and more
     * stages can consume later).
     */
    fun showRawStageToggles(path: SourcePath): Boolean = path == SourcePath.MCRAW
}

enum class PreviewState { IDLE, RENDERING, FRAME, FAILED }

/**
 * Clip editor (DESIGN.md Editor): preview frame on
 * surface-container-lowest with a subtle outline border, Light/Color/Detail
 * tabs, Light tab with Exposure / Highlights / Black Point / White Point /
 * Shadows / Midtones / Contrast, Color tab with Temperature / Tint /
 * Saturation (tone stages COL-11/12/13, end to end) plus the RAW metadata
 * controls (RAW-07/COL-09/RAW-04/RAW-06): As Shot WB reset, Highlight
 * Reconstruction switch, Keep Vignetting switch, Detail tab with Sharpness /
 * Detail sliders (COL-15), the Tonemapping choice Off / ACES / AgX (COL-16)
 * as M3 segmented buttons, and the LUT section (COL-07): the imported .cube
 * library as choice rows plus an Import button and the Strength slider
 * under the selected cube.
 *
 * Preview: the screen renders the CURRENT transport position through the
 * native preview pipeline — the first frame once on open, then after the
 * trailing debounce on every slider edit (RenderDebounce.WINDOW_MS), and
 * continuously during playback through the render gate (one running + one
 * pending render). Degrades gracefully: with no fd ([sourceFd] < 0), no
 * native engine, or a failed render, the SAME placeholder shows as before
 * this path existed — grading still works, never a crash.
 *
 * The chrome uses the fixed neutral scheme (RitimTheme(editor = true)) so it
 * never biases color judgment. Sliders snap to their step grid (0.1 EV
 * exposure, 50 K temperature) and a double tap on a slider row resets that
 * one control to its GradeK default (DESIGN.md Inputs).
 */
@Composable
fun ClipEditorScreen(
    sourceId: String,
    onBack: () -> Unit,
    initialGrade: GradeK = GradeK(),
    onGradeChange: (GradeK) -> Unit = {},
    onExport: (Target) -> Unit = {},
    sourceFd: Int = -1,
    sourceFdProvider: () -> Int = { -1 },
    accessLost: Boolean = false,
    // Persisted import stats (engine pass at import time); 0 when unknown.
    // Only the preview surface's placeholder shape reads them — the ratio
    // fallback chain lives in PreviewSurface.kt.
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    // Which engine opens the clip: the stored kind is authoritative for
    // ENCODED clips; the mime rescues pre-detection imports (a video/mp4
    // mime routes to the encoded path — SourcePathDecision.decide). Defaults
    // keep every existing MCRAW caller byte-identical.
    sourceKind: SourceKind = SourceKind.MCRAW,
    sourceMime: String? = null,
    // ---- LUT wiring (COL-07) ----
    // The imported cube library (LutStore.all(): id + display name). The
    // screen owns no store; AppNav passes the list and refreshes it after
    // every import/remove so the rows stay current.
    lutEntries: List<LutInfo> = emptyList(),
    // Imports the picked SAF document (AppNav: LutStore.import + the native
    // probe + the TITLE rename). Returns the entry on success or the failure
    // for the local error line; never throws.
    onImportLut: (uri: String, fallbackName: String) -> Result<LutInfo> =
        { _, _ -> Result.failure(IllegalStateException()) },
    // Removes a cube from the library (AppNav: LutStore.remove + refresh).
    onRemoveLut: (LutInfo) -> Unit = {},
    // The grade's lutId -> the copied cube's path (AppNav: LutStore.pathFor).
    // Null = no LUT: stage 20 is bypassed and the old look is preserved.
    lutPathFor: (String?) -> String? = { null },
) {
    RitimTheme(editor = true) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var grade by remember(sourceId) { mutableStateOf(initialGrade) }

        // ---- Preview + transport bookkeeping (all keyed to the clip) ----
        var frame by remember(sourceId) { mutableStateOf<PreviewFrame?>(null) }
        var lastRenderFailed by remember(sourceId) { mutableStateOf(false) }
        var errorText by remember(sourceId) { mutableStateOf<String?>(null) }
        var renderInFlight by remember(sourceId) { mutableStateOf(false) }
        // renderSeq is the request counter: every offered render bumps it and
        // carries its value as its sequence. The pump publishes results
        // monotonically (see below), so an old frame can never overwrite a
        // newer one — and a render that finishes after a clip switch never
        // publishes, because the whole bookkeeping block is keyed to the clip.
        var renderSeq by remember(sourceId) { mutableStateOf(0L) }
        // The persistent preview session: opened once when the editor opens
        // with a usable fd, closed exactly once on dispose (all engine calls
        // funnel through the single preview engine thread, so the dispose
        // close runs after every render submitted before it — a drained
        // close). Null while opening / when the session path failed (the
        // per-render fallback below covers that). The type is the seam
        // (EditorPreviewSession): MCRAW and encoded clips open through the
        // SAME interface, so the pump/transport below never branch on kind.
        var session by remember(sourceId) { mutableStateOf<EditorPreviewSession?>(null) }
        var sessionRender by remember(sourceId) { mutableStateOf(false) }

        // Transport state: play/pause, the current frame shown by the
        // transport (slider position + playback cursor), and the clip's real
        // MCRAW timestamps fetched once after the session opens.
        var isPlaying by remember(sourceId) { mutableStateOf(false) }
        var positionFrame by remember(sourceId) { mutableStateOf(0) }
        var ptsUs by remember(sourceId) { mutableStateOf<LongArray?>(null) }
        // Slider-drag tier state (SliderTiers machine): while the finger is
        // down, renders use the low drag tier through the session's CACHED
        // path; the drag end emits exactly one full-quality settle render.
        var dragActive by remember(sourceId) { mutableStateOf(false) }

        // ---- LUT import (COL-07) ----
        // The import error is LOCAL to the LUT section: errorText drives the
        // preview FAILED state (EditorPreviewLogic.stateOf), so a failed
        // import must not yank the preview placeholder.
        var lutImportError by remember(sourceId) { mutableStateOf<String?>(null) }
        // SAF picker: any mime — the native probe validates the .cube, and a
        // non-cube pick fails the probe with a clear message instead of being
        // blocked at the picker (the same permissive shape as the clip
        // import).
        val lutPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?: uri.lastPathSegment
                ?: ""
            // AppNav does LutStore.import + the native probe + the TITLE
            // rename; the failure message lands in the local error line.
            onImportLut(uri.toString(), fallbackName).fold(
                onSuccess = { lutImportError = null },
                onFailure = { e -> lutImportError = e.message ?: "LUT import failed" },
            )
        }

        // The render pipeline's pending slot (owner request §1: at most one
        // running and one pending render; a new request replaces the pending
        // one). The running slot is the render pump effect below.
        val gate = remember(sourceId) { RenderGate() }
        // The pump's wake signal. CONFLATED: kicks arriving while the pump
        // is busy coalesce into one — the pump drains the gate anyway, and a
        // kick never cancels a render in flight (that would leave its native
        // task running on the engine thread with no consumer = backlog).
        val pumpKick = remember(sourceId) { Channel<Unit>(Channel.CONFLATED) }

        val nativeAvailable = NativeSource.nativeAvailable
        val canRender = EditorPreviewLogic.canRender(sourceFd, nativeAvailable)
        // Which engine owns this clip — decided before any fd is taken (the
        // decision is pure: stored kind + container mime; both arrive
        // remember(sourceId)-keyed from AppNav, so the path is stable for
        // the clip's whole editor lifetime).
        val sourcePath = SourcePathDecision.decide(sourceKind, sourceMime)

        /** Offers a render to the pipeline (replaces any pending one). */
        fun requestRender(frameIndex: Int, g: GradeK, maxDim: Int, cached: Boolean = false) {
            renderSeq += 1
            gate.offer(RenderRequest(frameIndex, g, maxDim, renderSeq, cached))
            pumpKick.trySend(Unit)
        }

        /** Applies one SliderTiers transition and renders per its decision. */
        fun applyTierEvent(event: SliderTiers.Event) {
            val t = SliderTiers.onEvent(event, dragActive)
            dragActive = t.dragActive
            when (val d = t.decision) {
                is SliderTiers.Decision.RenderAtDragTier ->
                    requestRender(positionFrame, grade, d.maxDim, cached = true)
                is SliderTiers.Decision.RenderAtSettleTier ->
                    requestRender(positionFrame, grade, d.maxDim)
                SliderTiers.Decision.NoRender -> Unit
            }
        }

        // Session open: once, when the editor opens with a usable fd. The
        // seam routes by sourcePath: MCRAW opens the native session, encoded
        // clips open the EncodedSource session — the fd-ownership rules are
        // the factory's business (MCRAW dup's, encoded adopts), never the
        // screen's. Both keep the decoder open for the whole editor
        // lifetime; when the open fails, sessionRender stays false and the
        // per-render fallback (MCRAW-only, below) takes over.
        LaunchedEffect(sourceFd, nativeAvailable, sourcePath) {
            if (!canRender) return@LaunchedEffect
            val opened = PreviewSessions.open(
                path = sourcePath,
                fdProvider = sourceFdProvider,
                engine = PreviewRenderer.previewEngine,
            )
            val s = opened.getOrNull()
            if (s != null) {
                session = s
                sessionRender = true
                // Real timestamps for the transport (fetched once on the
                // engine thread; ptsUs[i] is frame i's pts) — identical
                // for both paths: EncodedSource gives ptsUs per frame.
                ptsUs = LongArray(s.frames) { i -> s.framePtsUs(i.toInt()) }
                // First frame auto-visible (owner request §1).
                requestRender(0, grade, PreviewTiers.PAUSED_MAX_DIM)
                positionFrame = 0
            } else {
                errorText = opened.exceptionOrNull()?.message
                lastRenderFailed = true
            }
        }

        // Session close: exactly once, when the editor leaves composition
        // (route change / dispose). Drained: the close is submitted to the
        // same single engine thread, so it runs after all pending renders.
        DisposableEffect(sourceId) {
            onDispose {
                session?.close()
            }
        }

        // Slider edits re-render after the trailing debounce window (the
        // grade is applied to the CURRENT transport position). The tier
        // follows the drag state: mid-drag pauses re-render at the low drag
        // tier through the cached path (the frame is unchanged, only the
        // grade moved); settled edits use the full tier. Continuous drags
        // are fed by the immediate GradeEdited path below — this effect
        // restarts on every grade step, so its trailing edge only fires
        // when the finger pauses.
        LaunchedEffect(grade, sourceFd) {
            if (!canRender || renderSeq == 0L) return@LaunchedEffect
            delay(RenderDebounce.WINDOW_MS)
            requestRender(
                positionFrame, grade,
                SliderTiers.tierFor(dragActive),
                cached = dragActive,
            )
        }

        // THE RENDER PUMP: the single running slot, alive for the whole clip.
        // One loop: await a kick, drain the pending slot ONE render at a
        // time, publish, repeat. The pump is never restarted (a kick while it
        // is busy coalesces), so a render in flight is never cancelled — at
        // most one native render runs on the engine and one waits pending,
        // exactly the owner-request §1 pipeline shape. A new request offered
        // while a render runs replaces the pending one; the running one's
        // result is stale-dropped when it lands.
        LaunchedEffect(sourceId) {
            // Monotonic publication: the pump is serial and the gate is
            // newest-wins, so polled requests arrive in strictly increasing
            // sequence order — publish anything newer than the last one that
            // published. (Dropping anything not-the-newest, as the old
            // parallel-effect design required, would freeze playback: offers
            // legitimately outpace renders there, and each completed frame
            // must show while the newer one is already pending.)
            var lastPublished = 0L
            for (kick in pumpKick) {
                while (true) {
                    val req = gate.poll() ?: break
                    renderInFlight = true
                    val s = session
                    // COL-07: the request's grade carries the selected LUT id;
                    // the resolved path travels with the render (null = no
                    // LUT, the old look). Resolved per request so an import
                    // or remove mid-session is picked up on the next render.
                    val lutPath = lutPathFor(req.grade.lutId)
                    val result = if (sessionRender && s != null) {
                        if (req.cached) {
                            // Interactive drag render: the bridge reuses the
                            // cached CFA when the frame is unchanged.
                            s.renderCached(req.frameIndex, req.grade, req.maxDim, lutPath)
                        } else {
                            s.render(req.frameIndex, req.grade, req.maxDim, lutPath)
                        }
                    } else {
                        // No session. The per-render fallback is the MCRAW
                        // engine's path (PreviewRenderer.render opens the
                        // clip through the native MCRAW pipeline); it cannot
                        // read an encoded container, so an encoded clip
                        // without a session fails with the same
                        // stage/cause/recovery shape instead of feeding an
                        // MP4 to the RAW engine.
                        if (sourcePath == SourcePath.ENCODED) {
                            Result.failure(IllegalStateException(
                                "Preview failed (stage: open): encoded clip session unavailable — recovery: reopen the clip"
                            ))
                        } else {
                            val fdForRender = sourceFdProvider()
                            if (fdForRender < 0) {
                                Result.failure(IllegalStateException(
                                    "Preview failed (stage: open): source unavailable — recovery: reopen the clip"
                                ))
                            } else {
                                PreviewRenderer.render(
                                    sourceFd = fdForRender,
                                    frameIndex = req.frameIndex,
                                    grade = req.grade,
                                    maxDim = req.maxDim,
                                    lutPath = lutPath,
                                )
                            }
                        }
                    }
                    if (RenderDebounce.isFresh(req.sequence, lastPublished)) {
                        result.fold(
                            onSuccess = { f ->
                                frame = f
                                lastRenderFailed = false
                                errorText = null
                            },
                            onFailure = { e ->
                                lastRenderFailed = true
                                errorText = e.message
                            },
                        )
                        lastPublished = req.sequence
                    }
                    renderInFlight = false
                }
            }
        }

        // PLAYBACK LOOP: paced by the clip's REAL MCRAW timestamps. Each
        // tick picks the frame whose pts is closest to the wall-clock
        // elapsed time (drop-late: never a backlog — the wall clock decides
        // which frame is current). Renders go through the same render gate
        // (one running + one pending), at the coarser playback tier. Stops
        // at the last frame and resets the play state (owner request §1).
        LaunchedEffect(isPlaying, ptsUs) {
            val pts = ptsUs ?: return@LaunchedEffect
            if (!isPlaying || pts.isEmpty()) return@LaunchedEffect
            val startFrame = positionFrame
            val startUs = System.nanoTime() / 1000
            var lastRendered = -1
            while (isPlaying) {
                val elapsed = PtsTimeline.elapsedAt(pts, startFrame, System.nanoTime() / 1000 - startUs)
                val idx = PtsTimeline.frameAt(pts, elapsed)
                if (idx >= 0 && idx != lastRendered) {
                    positionFrame = idx
                    requestRender(idx, grade, PreviewTiers.PLAYBACK_MAX_DIM)
                    lastRendered = idx
                }
                if (idx >= pts.size - 1) {
                    // Clip end: stop, keep the last frame on screen, reset
                    // the play state (a new Play resumes from the start).
                    isPlaying = false
                    positionFrame = pts.size - 1
                    break
                }
                val nextDelay = PtsTimeline.delayUntilNextUs(pts, idx, elapsed)
                if (nextDelay > 0) delay(nextDelay / 1000) else delay(1)
            }
        }

        // ---- Transport helpers (pure logic lives in PtsTimeline) ----
        val pts = ptsUs
        val totalUs = pts?.let { PtsTimeline.totalDurationUs(it) } ?: 0L
        val elapsedUs = pts?.let { PtsTimeline.positionUs(it, positionFrame) } ?: 0L
        val seekFraction = pts?.let { PtsTimeline.fractionForFrame(it, positionFrame) } ?: 0f

        // Preview state: computed ONCE here (top bar refresh control and
        // the PreviewSurface share it).
        val previewState = EditorPreviewLogic.stateOf(
            renderRequested = renderSeq > 0L,
            renderInFlight = renderInFlight,
            hasFrame = frame != null,
            lastRenderFailed = lastRenderFailed,
        )

        // Clip facts for the top bar's info sheet (review phase 1: name,
        // resolution, duration, frame count, FPS when derivable). FPS comes
        // from the real timestamps: frames / duration.
        val clipInfo = ClipInfo(
            name = sourceId,
            width = sourceWidth,
            height = sourceHeight,
            totalUs = totalUs,
            frameCount = pts?.size ?: 0,
            fps = if (pts != null && totalUs > 0L) (pts.size.toFloat() / (totalUs / 1_000_000f)) else 0f,
        )

        // Neutral root surface (design review 2026-11-10, maroon bug): the
        // editor's own Column painted NO background, so AppNav's Scaffold
        // surface (dynamic wallpaper color) showed through. The editor now
        // paints its own fixed-neutral surface INSIDE RitimTheme(editor =
        // true); inset ownership stays in AppNav.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(Modifier.fillMaxSize()) {
            // Fixed top bar (design review "Onerilen ilk yerlesim"): back +
            // single-line name + one Export icon (opens the export sheet)
            // + "..." (opens the clip-info sheet).
            EditorTopBar(
                sourceId = sourceId,
                onBack = onBack,
                onExport = onExport,
                exportEnabled = sourceFd >= 0,
                clipInfo = clipInfo,
            )
            // Bounded preview region (design review: the frame never shifts
            // when the tab content changes): the preview is capped at
            // EditorLayout.PREVIEW_HEIGHT_FRACTION of the available height
            // instead of taking the flex space, so the frame's position is
            // deterministic across tabs. PreviewSurface still shapes itself
            // by the clip's ratio (ratio chain preserved) and centers
            // inside the bounded Box.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = EditorLayout.previewMaxHeight()),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSurface(
                    frame = frame,
                    state = previewState,
                    errorText = errorText,
                    accessLost = accessLost,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Small corner refresh control (design review: the manual
                // render leaves the prime area): status from the existing
                // PreviewState data, tap = the old Render-preview button's
                // onClick (full paused tier at the current position).
                PreviewRefreshControl(
                    state = previewState,
                    canRender = canRender,
                    onRefresh = {
                        requestRender(positionFrame, grade, PreviewTiers.PAUSED_MAX_DIM)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
            // Transport row (owner request 2026-11-10 §1, MotionCam Pro
            // reference): compact Play/Pause + draggable time bar + elapsed /
            // total timecode directly under the preview. Loading and error
            // states stay readable in the preview area above; the transport
            // is disabled until the session opens (no timestamps yet).
            TransportRow(
                isPlaying = isPlaying,
                enabled = pts != null && pts.size > 1,
                elapsedUs = elapsedUs,
                totalUs = totalUs,
                fraction = seekFraction,
                onPlayPause = {
                    if (isPlaying) {
                        // Pause keeps the position (owner request §1).
                        isPlaying = false
                    } else {
                        // Resume from the selected position; at the very end,
                        // a fresh play restarts from the first frame.
                        if (pts != null && positionFrame >= pts.size - 1) {
                            positionFrame = 0
                        }
                        isPlaying = true
                        applyTierEvent(SliderTiers.Event.PlaybackStarted)
                    }
                },
                onSeekFraction = { fraction ->
                    val p = pts ?: return@TransportRow
                    // Dragging shows the selected frame immediately (low
                    // drag tier via the cached path) and playback resumes
                    // from there (owner request §1).
                    val idx = PtsTimeline.frameForFraction(p, fraction)
                    if (idx >= 0) {
                        positionFrame = idx
                        isPlaying = false
                        requestRender(idx, grade, SliderTiers.tierFor(dragActive), cached = true)
                    }
                },
                onSeekStarted = { applyTierEvent(SliderTiers.Event.DragStarted) },
                onSeekFinished = { applyTierEvent(SliderTiers.Event.DragEnded) },
            )
            // Tabs: Light / Color / Detail (independent of the panel —
            // design review: the tab row sits between transport and panel).
            TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.editor_tab_light)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.editor_tab_color)) },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(stringResource(R.string.editor_tab_detail)) },
                )
            }
            // Settings panel — the ONLY region that scrolls (design review
            // "Onerilen ilk yerlesim": sabit ust bar + sinirlandirilmis
            // onizleme + kayan ayar paneli). The panel takes the flex
            // space left by the bounded preview and scrolls inside it, so
            // short screens + 1.3x text never squeeze the preview.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                when (tab) {
                    0 -> LightPanel(
                        grade = grade,
                        onChange = { g ->
                            grade = g
                            onGradeChange(g)
                            // Mid-drag grade steps render IMMEDIATELY at the
                            // low tier through the cached path (the debounce
                            // effect restarts on every step, so its trailing
                            // edge only covers a finger PAUSE mid-drag; a
                            // settled edit — double-tap reset, tap on the
                            // track — settles through the debounce alone).
                            if (dragActive) {
                                applyTierEvent(SliderTiers.Event.GradeEdited)
                            }
                        },
                        onDragActiveChange = { active ->
                            applyTierEvent(
                                if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                            )
                        },
                    )
                    1 -> ColorPanel(
                        grade = grade,
                        // The CFA-stage toggles (Highlight Reconstruction, Keep
                        // Vignetting) exist only on the MCRAW path; encoded
                        // clips keep As Shot + every slider (pure decision in
                        // EditorPreviewLogic.showRawStageToggles).
                        showRawStageToggles = EditorPreviewLogic.showRawStageToggles(sourcePath),
                        onChange = { g ->
                            grade = g
                            onGradeChange(g)
                            // Same tier wiring as LightPanel: the machine is
                            // shared, so which tab the drag started on does
                            // not matter.
                            if (dragActive) {
                                applyTierEvent(SliderTiers.Event.GradeEdited)
                            }
                        },
                        onDragActiveChange = { active ->
                            applyTierEvent(
                                if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                            )
                        },
                        // RAW toggles (As Shot, the reconstruction/vignetting
                        // switches) are not drags: they settle IMMEDIATELY at
                        // the full tier (SliderTiers.GradeEdited with no drag
                        // active), not through the debounce.
                        onToggleChange = { g ->
                            grade = g
                            onGradeChange(g)
                            applyTierEvent(SliderTiers.Event.GradeEdited)
                        },
                    )
                    else -> DetailPanel(
                        grade = grade,
                        onChange = { g ->
                            grade = g
                            onGradeChange(g)
                            // Same tier wiring as the other tabs: mid-drag
                            // grade steps render immediately at the low tier
                            // through the cached path.
                            if (dragActive) {
                                applyTierEvent(SliderTiers.Event.GradeEdited)
                            }
                        },
                        onDragActiveChange = { active ->
                            applyTierEvent(
                                if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                            )
                        },
                        // The tonemapping choice is not a drag: it settles
                        // IMMEDIATELY at the full tier (GradeEdited with no
                        // drag active), like the RAW toggles.
                        onTonemapChange = { g ->
                            grade = g
                            onGradeChange(g)
                            applyTierEvent(SliderTiers.Event.GradeEdited)
                        },
                        // ---- LUT section wiring (COL-07) ----
                        // Selection/Strength are grade edits like the
                        // tonemap choice: not drags, so they settle
                        // IMMEDIATELY at the full tier. The import picker
                        // and the library rows are not grade edits at all.
                        lutEntries = lutEntries,
                        lutImportError = lutImportError,
                        onImportLut = { lutPicker.launch(arrayOf("*/*")) },
                        onRemoveLut = onRemoveLut,
                        onLutSelect = { g ->
                            grade = g
                            onGradeChange(g)
                            applyTierEvent(SliderTiers.Event.GradeEdited)
                        },
                    )
                }
                }
            }
        }
    }
    }
}


/** Light tab: Exposure / Highlights / tone controls (DESIGN.md Editor). */
@Composable
private fun LightPanel(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GradeSlider(
            label = stringResource(R.string.editor_slider_exposure),
            value = grade.exposureEv,
            valueRange = -5f..5f,
            step = SliderSteps.EXPOSURE_STEP,
            valueLabel = Format.formatEv(grade.exposureEv),
            onChange = { v -> onChange(grade.copy(exposureEv = SliderSteps.snapExposure(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.EXPOSURE, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_highlights),
            value = grade.highlights,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.highlights.toInt()),
            onChange = { v -> onChange(grade.copy(highlights = SliderSteps.snapHighlights(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.HIGHLIGHTS, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_black_point),
            value = grade.blackPoint,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.blackPoint.toInt()),
            onChange = { v -> onChange(grade.copy(blackPoint = SliderSteps.snapBlackPoint(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.BLACK_POINT, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_white_point),
            value = grade.whitePoint,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.whitePoint.toInt()),
            onChange = { v -> onChange(grade.copy(whitePoint = SliderSteps.snapWhitePoint(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.WHITE_POINT, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_shadows),
            value = grade.shadows,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.shadows.toInt()),
            onChange = { v -> onChange(grade.copy(shadows = SliderSteps.snapShadows(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.SHADOWS, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_midtones),
            value = grade.midtones,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.midtones.toInt()),
            onChange = { v -> onChange(grade.copy(midtones = SliderSteps.snapMidtones(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.MIDTONES, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_contrast),
            value = grade.contrast,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.contrast.toInt()),
            onChange = { v -> onChange(grade.copy(contrast = SliderSteps.snapContrast(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.CONTRAST, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
    }
}

/**
 * Color tab: white balance (As Shot reset + Temperature / Tint), the RAW
 * stage toggles, then Saturation. Temperature and Tint moved here from the
 * Light tab when Saturation landed (spec COL-12: white balance and
 * saturation are the color controls; the Light tab is tone).
 *
 * RAW-07/COL-09/RAW-04/RAW-06 semantics:
 * - As Shot (reset action): tempK -> 5600, tint -> 0, asShot -> true. The
 *   manual values stay stored in tempK/tint whenever asShot is toggled off
 *   again, so nothing is ever lost.
 * - Temperature/Tint slider edits set asShot = false: touching a manual WB
 *   control means manual WB is what the user wants to see.
 * - Highlight Reconstruction and Keep Vignetting are M3 Switch rows (48dp
 *   touch targets, DESIGN.md Inputs): they toggle native develop stages, not
 *   tone — the Highlights TONE slider stays independent (stage 13 vs stage 3).
 *   CFA-stage rows show only when [showRawStageToggles] is true (MCRAW);
 *   encoded (Mi-Log MP4) clips decode YUV with no CFA stage, so they get the
 *   same white balance + saturation controls without the two RAW toggles.
 */
@Composable
private fun ColorPanel(
    grade: GradeK,
    showRawStageToggles: Boolean,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No header text: the Temperature GradeSlider directly below
            // already carries the "Temperature" label.
            FilledTonalButton(
                onClick = {
                    // As Shot: back to the camera's recorded WB. The manual
                    // sliders go neutral and the WB source flips to as-shot;
                    // the recorded WB travels in the metadata (the native
                    // pipeline divides by asShotNeutral), so neutral manual
                    // values + asShot = true IS the camera's white balance.
                    onToggleChange(
                        grade.copy(tempK = GradeK().tempK, tint = GradeK().tint, asShot = true)
                    )
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(stringResource(R.string.editor_as_shot))
            }
        }
        GradeSlider(
            label = stringResource(R.string.editor_slider_temperature),
            value = grade.tempK,
            valueRange = 2000f..50000f,
            step = SliderSteps.TEMP_STEP_K,
            valueLabel = Format.formatKelvin(grade.tempK.toInt()),
            onChange = { v ->
                // Manual temperature edit: manual WB wins (RAW-07).
                onChange(
                    grade.copy(
                        tempK = SliderSteps.snapTemperature(v),
                        asShot = false,
                    )
                )
            },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.TEMPERATURE, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = CompactSliderDefaults.TEMPERATURE_GRADIENT,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_tint),
            value = grade.tint,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.tint.toInt()),
            onChange = { v ->
                // Manual tint edit: manual WB wins (RAW-07).
                onChange(
                    grade.copy(
                        tint = SliderSteps.snapTint(v),
                        asShot = false,
                    )
                )
            },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.TINT, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = CompactSliderDefaults.TINT_GRADIENT,
        )
        // CFA-stage toggles: MCRAW only (encoded clips decode YUV — there is
        // no CFA stage to reconstruct or vignette; the decision is pure and
        // JVM-tested).
        if (showRawStageToggles) {
            RawToggleRow(
                label = stringResource(R.string.editor_highlight_reconstruction),
                checked = grade.highlightReconstruction,
                onCheckedChange = { checked ->
                    onToggleChange(grade.copy(highlightReconstruction = checked))
                },
            )
            RawToggleRow(
                label = stringResource(R.string.editor_keep_vignetting),
                checked = grade.keepVignetting,
                onCheckedChange = { checked ->
                    onToggleChange(grade.copy(keepVignetting = checked))
                },
            )
        }
        GradeSlider(
            label = stringResource(R.string.editor_slider_saturation),
            value = grade.saturation,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.saturation.toInt()),
            onChange = { v -> onChange(grade.copy(saturation = SliderSteps.snapSaturation(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.SATURATION, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = CompactSliderDefaults.SATURATION_GRADIENT,
        )
    }
}

/**
 * Detail tab (COL-15/COL-16): Sharpness (edge sharpness, unsharp radius 1.5)
 * and Detail (fine-texture contrast, unsharp radius 0.75) sliders, then the
 * Tonemapping choice — Off / ACES / AgX M3 segmented buttons (DESIGN.md
 * Inputs: "segmented buttons for codec and scope choice" — the same single
 * choice pattern). All three controls run on BOTH clip kinds: the native
 * develop pipeline and developEncoded share the same stages, so encoded
 * (Mi-Log MP4) clips get the same Detail tab with nothing hidden.
 *
 * Tonemap values (rawedit::Grade): 0 = off, 1 = ACES (Narkowicz 2015 fit),
 * 2 = AgX (2022 Filmic AgX essential fit). The grade stores the choice, so
 * the used transform version travels with the saved grade (COL-16's
 * versioning requirement at this package's scope).
 *
 * The LUT section (COL-07) closes the tab: the imported cube library as
 * radio rows (None + one per imported cube), an Import button, the Strength
 * slider under the selected cube and a Remove action. Selection and
 * Strength are grade edits (onLutSelect, the immediate settle path like
 * the tonemap choice); import/remove are library operations (the screen's
 * AppNav wiring owns the store).
 */
@Composable
private fun DetailPanel(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onTonemapChange: (GradeK) -> Unit = {},
    lutEntries: List<LutInfo> = emptyList(),
    lutImportError: String? = null,
    onImportLut: () -> Unit = {},
    onRemoveLut: (LutInfo) -> Unit = {},
    onLutSelect: (GradeK) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GradeSlider(
            label = stringResource(R.string.editor_slider_sharpness),
            value = grade.sharpness,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.sharpness.toInt()),
            onChange = { v -> onChange(grade.copy(sharpness = SliderSteps.snapSharpness(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.SHARPNESS, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_detail),
            value = grade.detail,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.detail.toInt()),
            onChange = { v -> onChange(grade.copy(detail = SliderSteps.snapDetail(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.DETAIL, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        // Tonemapping: one row, one choice (Off / ACES / AgX). The label
        // row mirrors the slider rows so the three tabs read as one panel.
        Text(
            stringResource(R.string.editor_tonemapping),
            style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                TonemapOption(R.string.editor_tonemap_off, 0),
                TonemapOption(R.string.editor_tonemap_aces, 1),
                TonemapOption(R.string.editor_tonemap_agx, 2),
            )
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = grade.tonemap == option.value,
                    onClick = { onTonemapChange(grade.copy(tonemap = option.value)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                ) {
                    Text(stringResource(option.labelRes))
                }
            }
        }
        // ---- LUT section (COL-07) ----
        // One choice row per cube plus None: the grade stores the id, the
        // store resolves the path at render time, so a removed cube degrades
        // to no-LUT (pathFor returns null) instead of breaking the render.
        Text(
            stringResource(R.string.editor_lut),
            style = MaterialTheme.typography.labelLarge,
        )
        if (lutEntries.isEmpty()) {
            // No cubes yet: one hint line instead of a bare None row.
            Text(
                stringResource(R.string.editor_lut_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // None row first: deselecting is the removal of the selection
            // (lutId = null), not a grade reset — Strength keeps its value
            // so re-selecting a cube restores the blend the user set.
            LutRow(
                label = stringResource(R.string.editor_lut_none),
                selected = grade.lutId == null,
                onClick = { onLutSelect(grade.copy(lutId = null)) },
            )
            lutEntries.forEach { lut ->
                LutRow(
                    label = lut.name,
                    selected = grade.lutId == lut.id,
                    onClick = { onLutSelect(grade.copy(lutId = lut.id)) },
                    onRemove = { onRemoveLut(lut) },
                )
            }
        }
        // Import: tonal button (secondary action, DESIGN.md), full-width so
        // the row reads as part of the section, not a floating chip.
        FilledTonalButton(
            onClick = onImportLut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.editor_lut_import))
        }
        // Import failure lands here — local to the section, never in the
        // preview's error surface (lutImportError is its own state).
        if (lutImportError != null) {
            Text(
                lutImportError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Strength only under a selected cube (COL-07: "Seçim ve Strength
        // ayrı kaydedilsin" — the two controls, one visible at a time the
        // selection makes it meaningful). Percent 0..100, whole steps, the
        // GradeK default 100 = full LUT.
        if (grade.lutId != null) {
            GradeSlider(
                label = stringResource(R.string.editor_lut_strength),
                value = grade.lutStrength,
                valueRange = 0f..100f,
                step = SliderSteps.UNIT_STEP,
                valueLabel = "${grade.lutStrength.toInt()} %",
                onChange = { v ->
                    onChange(grade.copy(lutStrength = SliderSteps.snapLutStrength(v)))
                },
                onDoubleTapReset = {
                    onChange(SliderSteps.resetToDefault(SliderSteps.Axis.LUT_STRENGTH, grade))
                },
                onDragActiveChange = onDragActiveChange,
            )
        }
    }
}

/** One tonemapping segmented-button entry: label + rawedit::Grade value. */
private data class TonemapOption(
    val labelRes: Int,
    val value: Int,
)

/**
 * One LUT choice row (COL-07): radio semantics — the whole row is one
 * selectable control at the 48dp minimum (DESIGN.md Inputs), the radio
 * glyph mirrors selection, and a cube row additionally carries a Remove
 * text action (library operation, distinct from deselecting).
 */
@Composable
private fun LutRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(stringResource(R.string.editor_lut_remove))
            }
        }
    }
}

/**
 * One RAW stage toggle: label left, M3 Switch right, whole row tappable at
 * the 48dp minimum (DESIGN.md Inputs; `toggleable` merges the switch's
 * semantics onto the row so TalkBack reads it as one switch).
 */
@Composable
private fun RawToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 16.dp),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
