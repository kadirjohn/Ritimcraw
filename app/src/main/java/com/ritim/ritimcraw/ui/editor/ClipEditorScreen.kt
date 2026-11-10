package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
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
import com.ritim.ritimcraw.ui.theme.Tnum
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
 * Detail sliders (COL-15) and the Tonemapping choice Off / ACES / AgX
 * (COL-16) as M3 segmented buttons.
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
@OptIn(ExperimentalLayoutApi::class)
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
    // The top bar's clip name (owner request 2026-11-11 §2b): the clip's
    // real file name, id fallback resolved by the caller (never blank).
    // Defaults to the id so every existing caller is unchanged.
    sourceName: String? = null,
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
                    val result = if (sessionRender && s != null) {
                        if (req.cached) {
                            // Interactive drag render: the bridge reuses the
                            // cached CFA when the frame is unchanged.
                            s.renderCached(req.frameIndex, req.grade, req.maxDim)
                        } else {
                            s.render(req.frameIndex, req.grade, req.maxDim)
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

        Column(Modifier.fillMaxSize()) {
            // Top bar: back + clip id.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.editor_back),
                    )
                }
                Text(
                    // The clip's display name (§2b), id fallback — the caller
                    // resolves it; here it is only ever a label.
                    text = sourceName ?: sourceId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            // Preview frame: shaped like the clip itself (PreviewSurface;
            // owner finding 2026-11-10 — fixed box out, clip aspect ratio
            // in). The weighted Box leaves the leftover vertical space when
            // the surface bounds by height (very tall clips); the surface
            // centers inside it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSurface(
                    frame = frame,
                    state = EditorPreviewLogic.stateOf(
                        renderRequested = renderSeq > 0L,
                        renderInFlight = renderInFlight,
                        hasFrame = frame != null,
                        lastRenderFailed = lastRenderFailed,
                    ),
                    errorText = errorText,
                    accessLost = accessLost,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    modifier = Modifier.fillMaxWidth(),
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
            // Render affordance (DESIGN.md: tonal button for a secondary
            // action) + the Export actions (filled button for the one
            // primary action DNG, tonal for the secondary MP4). Export is
            // offered whenever the clip is loaded — the job snapshots the
            // CURRENT grade, so a queue-time snapshot of the user's edit is
            // what gets exported; the queue runs it when the engine is
            // available. Disabled without a fd (nothing to export).
            // FlowRow: three buttons do not fit one narrow row without
            // wrapping text vertically (seen on a 1080p phone) — let them
            // flow onto a second line instead.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        // Manual re-render of the current transport position
                        // at the full paused tier.
                        requestRender(positionFrame, grade, PreviewTiers.PAUSED_MAX_DIM)
                    },
                    enabled = canRender,
                ) {
                    Text(stringResource(R.string.editor_render_preview))
                }
                // Export: DNG filled (primary), MP4 tonal (secondary).
                Button(
                    onClick = { onExport(Target.DNG) },
                    enabled = sourceFd >= 0,
                ) {
                    Text(stringResource(R.string.editor_export_dng))
                }
                FilledTonalButton(
                    onClick = { onExport(Target.MP4) },
                    enabled = sourceFd >= 0,
                ) {
                    Text(stringResource(R.string.editor_export_mp4))
                }
            }
            // Tabs: Light / Color / Detail.
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
            // Panel with sliders (Light and Color tabs complete; Detail deferred).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
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
                    )
                }
            }
        }
    }
}

/**
 * Compact transport row under the preview (owner request 2026-11-10 §1):
 * Play/Pause icon button (48dp touch target), draggable seek slider, and
 * elapsed / total timecode in tabular figures so the strip never reflows
 * while playing. The Pause glyph is a vendored vector (material-icons-core
 * 1.7.3 has no Pause); Play uses the core PlayArrow.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    enabled: Boolean,
    elapsedUs: Long,
    totalUs: Long,
    fraction: Float,
    onPlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekStarted: () -> Unit = {},
    onSeekFinished: () -> Unit = {},
) {
    val seekDescription = stringResource(R.string.editor_seek)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onPlayPause, enabled = enabled) {
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.editor_pause),
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.editor_play),
                )
            }
        }
        // M3's Slider has no onValueChangeStarted; finger-down/up come from
        // the interaction stream. A tap is a zero-length drag: Start + Stop
        // still fire, so a tap seeks and settles through the same path.
        val seekInteraction = remember { MutableInteractionSource() }
        LaunchedEffect(seekInteraction) {
            seekInteraction.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> onSeekStarted()
                    is DragInteraction.Stop, is DragInteraction.Cancel -> onSeekFinished()
                }
            }
        }
        Slider(
            value = fraction,
            onValueChange = onSeekFraction,
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..1f,
            enabled = enabled,
            interactionSource = seekInteraction,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = seekDescription },
        )
        Text(
            text = Format.formatTimecodePair(elapsedUs, totalUs),
            style = MaterialTheme.typography.labelMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 */
@Composable
private fun DetailPanel(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onTonemapChange: (GradeK) -> Unit = {},
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
    }
}

/** One tonemapping segmented-button entry: label + rawedit::Grade value. */
private data class TonemapOption(
    val labelRes: Int,
    val value: Int,
)

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

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueLabel: String,
    onChange: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Double tap anywhere on the slider row resets this control to
            // its GradeK default (DESIGN.md Inputs: "double tap resets").
            .pointerInput(label) {
                detectTapGestures(onDoubleTap = { onDoubleTapReset() })
            },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            // tnum for EV / Kelvin / signed values (DESIGN.md Tabular numbers).
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge.merge(Tnum),
            )
        }
        // Finger down/up on a GRADE slider drives the same tier machine as
        // the transport: while the finger is down, re-renders run at the low
        // drag tier through the session's CACHED path — the frame is
        // unchanged, only the grade moved, so the decode is skipped entirely
        // (the whole point of the interactive path). A tap is a zero-length
        // drag: Start + Stop both fire, so a tap settles to full quality too.
        val gradeInteraction = remember { MutableInteractionSource() }
        LaunchedEffect(gradeInteraction) {
            gradeInteraction.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> onDragActiveChange(true)
                    is DragInteraction.Stop, is DragInteraction.Cancel -> onDragActiveChange(false)
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = SliderSteps.composeSteps(valueRange, step),
            interactionSource = gradeInteraction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
