package com.ritim.ritimcraw.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradePreset
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.engine.EditorAudioPlayback
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
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * The clip info sheet's resolution axis (owner request 2026-11-11
     * section 2): the session-recorded value wins when the open carried it
     * (the first frame's real dimensions); otherwise the persisted import
     * stat — even a 0x0 import record is shown as-is, never a re-import
     * demand. Pure so a JVM test can lock the preference order.
     */
    fun recordedWidth(sessionWidth: Int, persistedWidth: Int): Int =
        if (sessionWidth > 0) sessionWidth else persistedWidth

    /**
     * Where ONE system back press lands while the editor is open (owner
     * finding 2026-11-11: back exited the app from the normal editor —
     * the BackHandler was enabled only in fullscreen). Fullscreen pops
     * first; outside fullscreen the press leaves the editor (the
     * screen's onBack returns to the library). Pure so a JVM test can
     * lock the chain; the screen's BackHandler is always active and
     * branches on this decision.
     */
    fun onBack(fullscreen: Boolean): EditorBackDestination =
        if (fullscreen) EditorBackDestination.EXIT_FULLSCREEN
        else EditorBackDestination.LEAVE_EDITOR
}

enum class PreviewState { IDLE, RENDERING, FRAME, FAILED }

/**
 * Where one system back press lands while the editor is open: the pure
 * result of [EditorPreviewLogic.onBack], branched on by the screen's
 * always-active BackHandler.
 */
enum class EditorBackDestination { EXIT_FULLSCREEN, LEAVE_EDITOR }

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
 *
 * Over the preview (owner feature list W5): a toggleable 256-bin luma
 * histogram with a clipping badge — recomputed on every frame publish
 * from the same frame the preview shows — and the before/after
 * press-hold: hold the frame area to swap to the neutral grade,
 * release to return.
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
    // The top bar's clip name (owner request 2026-11-11 §2b): the clip's
    // real file name, id fallback resolved by the caller (never blank).
    // Defaults to the id so every existing caller is unchanged.
    sourceName: String? = null,
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
    // ---- Frame Picker (RAW-11) ----
    // Develops the transport's CURRENT frame as a photo. The screen owns
    // the transport position, so the picker sheet lives here (opened from
    // the clip-info sheet's "Develop frame" row); the two actions hand the
    // 0-BASED frame index and the grade mode to the caller, which enqueues
    // the photo job: separate = false develops the frame with the clip's
    // CURRENT grade snapshot, separate = true starts a FRESH neutral
    // develop (the clip's own grade is untouched). Default no-op keeps
    // every existing caller working.
    onDevelopPhoto: (frameIndex: Int, separate: Boolean) -> Unit = { _, _ -> },
    // ---- Presets (owner request 2026-11-11 §4) ----
    // The saved named grades (AppNav: PresetStore.all(), refreshed after
    // every save/delete). The screen owns no store; the clip-info sheet
    // shows the rows. Defaults keep every existing caller working.
    presets: List<GradePreset> = emptyList(),
    // Saves the clip's CURRENT grade under a name (AppNav:
    // PresetStore.put + refresh).
    onSavePreset: (name: String) -> Unit = {},
    // Removes a preset by id (AppNav: PresetStore.remove + refresh).
    onRemovePreset: (id: String) -> Unit = {},
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
        // Editor audio (owner request 2026-11-11 section 4): one controller per
        // clip, released at dispose. Audio follows the transport: play starts
        // it at the current position, pause/seek stop it, the clip end ends
        // it; a clip without audio plays silent (openAudio returns null).
        val audio = remember(sourceId) { EditorAudioPlayback() }

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
        // Fullscreen preview (owner request 2026-11-11 section 2): the SAME
        // editor session — only the layout changes (top bar / tabs / panel
        // hidden, the preview takes the flex space, the transport stays).
        // Saveable so it survives configuration changes like the tab does;
        // the system back chain pops fullscreen FIRST, then the editor —
        // never a double-back to leave the clip.
        var fullscreen by rememberSaveable { mutableStateOf(false) }
        // The system back chain (owner finding 2026-11-11: back exited the
        // app from the normal editor): the editor's BackHandler is ALWAYS
        // active while the editor is open — enabled-only-in-fullscreen let
        // the press fall through to the system and kill the app. One press
        // pops fullscreen first (the pure decision in
        // EditorPreviewLogic.onBack); outside fullscreen it leaves the
        // editor (onBack returns to the library). The ModalBottomSheets
        // compose after this handler, so an open sheet still wins the
        // press and dismisses first.
        BackHandler {
            when (EditorPreviewLogic.onBack(fullscreen)) {
                EditorBackDestination.EXIT_FULLSCREEN -> fullscreen = false
                EditorBackDestination.LEAVE_EDITOR -> onBack()
            }
        }
        // Frame Picker sheet (RAW-11): opened from the clip-info sheet's
        // "Develop frame" row. Saveable like the tab/fullscreen flags so a
        // configuration change (rotation) does not drop a half-made choice.
        var framePickerOpen by rememberSaveable { mutableStateOf(false) }

        // Before/after press-hold (owner feature list W5): while held, the
        // preview renders the CURRENT frame with the neutral/original grade;
        // release re-renders with the actual grade. Both renders go through
        // the render gate and the pump below, exactly like every other
        // request. heldRenderIssued tracks whether THIS hold actually issued
        // a render (an already-neutral grade skips both renders);
        // holdPausedPlayback tracks whether THIS hold paused playback
        // (owner request 2026-11-11: a hold while playing pauses the wall
        // clock and the release resumes from the held position — the
        // pure transitions live in BeforeAfter.onHoldStart/onHoldRelease).
        // The startHold helper is defined next to requestRender below.
        var heldRenderIssued by remember(sourceId) { mutableStateOf(false) }
        var holdPausedPlayback by remember(sourceId) { mutableStateOf(false) }

        // Histogram overlay (owner feature list W5): toggleable 256-bin
        // luma histogram + clipping badge over the preview. histogramVisible
        // is saveable so it survives configuration changes like the tab and
        // fullscreen do; the histogram itself is recomputed inside the
        // render pump's publish — the one place the on-screen frame is
        // swapped — so the overlay always shows the frame the preview
        // currently shows, and toggling on is instant (the result is
        // already current). Keyed to the clip: a clip switch starts empty.
        var histogramVisible by rememberSaveable { mutableStateOf(false) }
        var histogram by remember(sourceId) {
            mutableStateOf(FrameHistogram.of(null))
        }

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

        /**
         * Offers a render to the pipeline (replaces any pending one).
         * [followUpMaxDim] (progressive pause settle, owner request
         * 2026-11-11 section 2): after THIS render publishes, the pump
         * offers one more render of the same frame/grade at that tier —
         * only when the pending slot is empty, so anything the user did
         * in between still wins.
         */
        fun requestRender(
            frameIndex: Int,
            g: GradeK,
            maxDim: Int,
            cached: Boolean = false,
            followUpMaxDim: Int? = null,
        ) {
            renderSeq += 1
            gate.offer(RenderRequest(frameIndex, g, maxDim, renderSeq, cached, followUpMaxDim))
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

        /**
         * Before/after press-hold (owner feature list W5): press renders the
         * current frame with the neutral/original grade (the CACHED path —
         * the frame on screen was just rendered at the same index, so the
         * decode is reused and the swap costs one develop, no new decode);
         * release re-renders with the actual grade, also cached. Both go
         * through the render gate like every other request: newest-wins
         * pending slot + monotonic publication mean a newer request (a
         * seek, a slider settle, playback started mid-hold) supersedes the
         * hold render by sequence and the release render restores the grade
         * — the drag-tier/settle pipeline cannot be corrupted.
         *
         * During playback too (owner request 2026-11-11): a hold while
         * playing PAUSES the wall clock first (isPlaying = false — the
         * playback loop and the audio transport both key on it, so video
         * and sound stop cleanly at the current position; the loop is not
         * fought frame-by-frame), then renders the neutral frame at the
         * paused tier. Release (or a drag-cancel: a moving finger is a
         * seek, not a compare) re-renders the graded frame and RESUMES
         * playback from the held position — the same play-from-position
         * logic as the transport's play button, minus the restart-at-end
         * branch (the hold never advances the position, so it cannot be
         * at the clip's end when it started from a playing frame). An
         * already-neutral grade skips both renders AND the pause (holding
         * would show the exact frame the loop is already showing).
         * The transitions are pure (BeforeAfter.onHoldStart/onHoldRelease,
         * JVM-tested); this helper only writes the states they decide.
         */
        fun startHold(pressed: Boolean) {
            if (pressed) {
                when (BeforeAfter.onHoldStart(isPlaying, frame != null, canRender, grade)) {
                    BeforeAfter.HoldStart.NoHold -> return
                    BeforeAfter.HoldStart.PauseAndShowBefore -> {
                        holdPausedPlayback = true
                        isPlaying = false
                    }
                    BeforeAfter.HoldStart.ShowBefore -> holdPausedPlayback = false
                }
                heldRenderIssued = true
                requestRender(
                    positionFrame, BeforeAfter.holdGrade(),
                    PreviewTiers.PAUSED_MAX_DIM, cached = true,
                )
            } else {
                when (
                    BeforeAfter.onHoldRelease(holdPausedPlayback, heldRenderIssued)
                ) {
                    BeforeAfter.HoldRelease.Nothing -> return
                    BeforeAfter.HoldRelease.ShowAfterAndResume -> {
                        heldRenderIssued = false
                        holdPausedPlayback = false
                        requestRender(
                            positionFrame, grade,
                            PreviewTiers.PAUSED_MAX_DIM, cached = true,
                        )
                        // Resume from the held position (the existing
                        // play-from-position logic; PlaybackStarted also
                        // stands the drag tier down, like the play button).
                        isPlaying = true
                        applyTierEvent(SliderTiers.Event.PlaybackStarted)
                    }
                    BeforeAfter.HoldRelease.ShowAfter -> {
                        heldRenderIssued = false
                        holdPausedPlayback = false
                        requestRender(
                            positionFrame, grade,
                            PreviewTiers.PAUSED_MAX_DIM, cached = true,
                        )
                    }
                }
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
                audio.release()
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
        // result is stale-dropped when it lands. A request with a
        // followUpMaxDim (progressive pause settle) queues its full-res
        // follow-up AFTER publishing — through offerIfEmpty, so a request
        // that arrived meanwhile stays pending and wins.
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
                                // Histogram recompute (owner feature list W5):
                                // publish is the ONE place the on-screen frame
                                // is swapped, so the pass runs here — the
                                // overlay can never show a histogram for a
                                // frame the preview is not showing. Strided
                                // to at most FrameHistogram.MAX_SAMPLES pixels
                                // (sub-millisecond even at source resolution),
                                // on the pump coroutine like the publish
                                // itself; a hidden overlay still recomputes
                                // (cheap) so toggling on is instant.
                                histogram = FrameHistogram.of(f.argb)
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
                    // Progressive pause settle (owner request 2026-11-11
                    // section 2): the pause/clip-end render published its
                    // fast intermediate tier — now queue the full-res
                    // follow-up. offerIfEmpty: a request that arrived while
                    // the intermediate rendered (seek, slider settle, a new
                    // pause) stays pending and wins; the follow-up is
                    // dropped, superseded by that newer sequence. The
                    // follow-up bumps its OWN sequence, so it publishes
                    // after the intermediate by monotonic publication, and a
                    // render offered while IT runs replaces it in the slot
                    // exactly like any other request.
                    if (req.followUpMaxDim != null) {
                        renderSeq += 1
                        if (gate.offerIfEmpty(
                                RenderRequest(
                                    req.frameIndex, req.grade, req.followUpMaxDim,
                                    renderSeq, cached = true,
                                )
                            )
                        ) {
                            pumpKick.trySend(Unit)
                        }
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
                    // The audio ends with the clip (owner request section 4):
                    // stop drops the still-queued tail after the last frame.
                    // One full-resolution render of the final frame (owner
                    // request section 2: pause and clip end both land on the
                    // source resolution — the playback tier's coarse frame
                    // is replaced, and a seek afterwards wins by sequence).
                    audio.stop()
                    isPlaying = false
                    positionFrame = pts.size - 1
                    requestRender(
                        pts.size - 1, grade,
                        PreviewTiers.PAUSED_MAX_DIM, cached = true,
                        followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                    )
                    break
                }
                val nextDelay = PtsTimeline.delayUntilNextUs(pts, idx, elapsed)
                if (nextDelay > 0) delay(nextDelay / 1000) else delay(1)
            }
        }

        // AUDIO TRANSPORT (owner request 2026-11-11 section 4): audio follows
        // the play state — started in parallel with the playback loop (never
        // blocking the video path), paused whenever playback stops (pause
        // button, seek, clip end), re-primed at the current position on every
        // start (AUD-08's first form: old queued samples are dropped). The
        // position provider runs on the audio thread at start handling, so a
        // slow first open (the MCRAW whole-clip PCM read) still begins in
        // sync.
        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                audio.start(session, sourceFdProvider) {
                    val p = ptsUs ?: return@start 0L
                    PtsTimeline.positionUs(p, positionFrame)
                }
            } else {
                audio.pause()
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
        // from the real timestamps: frames / duration. The name is the
        // clip's display name (§2b), id fallback — never blank.
        //
        // Resolution prefers the session's RECORDED frame size (owner
        // request 2026-11-11 section 2: the first frame's dimensions straight
        // from the source, kept through the open boundary); the persisted
        // import stats are the fallback for a session-less editor (no usable
        // fd / failed open) and for old imports that recorded 0x0 — a 0x0
        // record never demands a re-import, whatever is known is shown.
        val clipInfo = ClipInfo(
            name = sourceName ?: sourceId,
            width = EditorPreviewLogic.recordedWidth(session?.width ?: 0, sourceWidth),
            height = EditorPreviewLogic.recordedWidth(session?.height ?: 0, sourceHeight),
            totalUs = totalUs,
            frameCount = pts?.size ?: 0,
            fps = if (pts != null && totalUs > 0L) (pts.size.toFloat() / (totalUs / 1_000_000f)) else 0f,
            // RAW rows (owner request 2026-11-11 section 2/3): MCRAW-only.
            // The sample format is the container's 16-bit sample storage
            // (raw16) — never presented as a sensor bit-depth claim. The white
            // level is the container fallback calibration the session carried
            // from the open; 0 = unknown and the row hides (encoded clips
            // stay 0 on both counts).
            rawSampleFormat = if (sourcePath == SourcePath.MCRAW) "raw16" else null,
            rawWhiteLevel = session?.rawWhiteLevel ?: 0,
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
            // + "..." (opens the clip-info sheet). The name shown is the
            // clip's display name (§2b), id fallback — resolved by the
            // caller, never blank. Hidden in fullscreen (owner request
            // section 2): the preview owns the screen; the system back
            // chain (BackHandler) leaves fullscreen first.
            if (!fullscreen) {
                EditorTopBar(
                    sourceId = sourceName ?: sourceId,
                    onBack = onBack,
                    onExport = onExport,
                    exportEnabled = sourceFd >= 0,
                    clipInfo = clipInfo,
                    // The info sheet's "Develop frame" row opens the
                    // screen's own picker sheet (RAW-11): the transport
                    // position lives here, not in the top bar.
                    onDevelopFrame = { framePickerOpen = true },
                    // ---- Copy/Paste settings + Presets (§4) ----
                    // The sheet reads the CURRENT grade (what Copy copies
                    // and Save-as-preset snapshots) and applies grades
                    // through the screen's own grade-write path — the
                    // same onGradeChange a slider move uses, so the
                    // sliders, preview and persistence all behave
                    // identically. The clipboard itself is GradeClipboard
                    // (app-wide, in-memory); canPaste gates the row.
                    grade = grade,
                    canPaste = GradeClipboard.hasContent(),
                    onCopySettings = { GradeClipboard.copy(grade) },
                    onApplyGrade = { g ->
                        grade = g
                        onGradeChange(g)
                    },
                    presets = presets,
                    onSavePreset = onSavePreset,
                    onRemovePreset = onRemovePreset,
                )
            }
            // Bounded preview region (design review: the frame never shifts
            // when the tab content changes): the preview is capped at
            // EditorLayout.PREVIEW_HEIGHT_FRACTION of the available height
            // instead of taking the flex space, so the frame's position is
            // deterministic across tabs. PreviewSurface still shapes itself
            // by the clip's ratio (ratio chain preserved) and centers
            // inside the bounded Box. In fullscreen the same Box takes the
            // flex space instead (weight) with no cap — same session, same
            // ratio chain, only the layout changes.
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (fullscreen) Modifier.weight(1f) else Modifier.heightIn(max = EditorLayout.previewMaxHeight())),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSurface(
                    frame = frame,
                    state = previewState,
                    errorText = errorText,
                    accessLost = accessLost,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    // The before/after press-hold gesture rides the surface's
                    // modifier (owner feature list W5): press-and-hold on the
                    // frame area swaps to the neutral grade, release returns
                    // to the current grade. The press must SURVIVE the
                    // platform long-press window to engage — a quick release,
                    // a drag beyond the touch slop, or another handler
                    // consuming the stream cancels — which is also what keeps
                    // quick taps on the frame and on the corner controls
                    // (refresh chip, fullscreen button — they overlap the
                    // surface's bounds) from flashing the original. Manual
                    // long-press detection instead of detectTapGestures:
                    // its onPress block is CANCELLED when onLongPress fires,
                    // so it cannot bridge engage-on-long-press to
                    // release-on-lift; withTimeoutOrNull drives the window
                    // without needing move events (a perfectly still finger
                    // still engages), the same shape Compose's own
                    // awaitLongPressOrCancellation uses. Attached here in
                    // the editor, so PreviewSurface stays a pure display
                    // component and both layouts (normal and fullscreen)
                    // get the feature through this one call site.
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // The long-press window: true when the press
                                // ended early (lift, drag, or consumed),
                                // null when the window elapsed with the
                                // finger still down.
                                val endedEarly = withTimeoutOrNull(longPressTimeout) {
                                    var lifted = false
                                    while (!lifted) {
                                        val event = awaitPointerEvent()
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed ||
                                            event.changes.any { it.isConsumed } ||
                                            (change.position - down.position).getDistance() > slop
                                        ) {
                                            lifted = true
                                        }
                                    }
                                    true
                                }
                                // Null = the window elapsed with the finger
                                // still down (long press confirmed); non-null
                                // = the press ended early, so this gesture is
                                // a tap/drag, not a hold.
                                if (endedEarly != null) return@awaitEachGesture
                                // Long press confirmed: show the original.
                                startHold(true)
                                // The hold ends when the finger lifts, the
                                // stream is consumed, or the finger MOVES
                                // past the touch slop (owner request
                                // 2026-11-11: a drag starting during a hold
                                // cancels it — a moving finger is a seek
                                // gesture, not a compare; the release path
                                // is shared, so the cancel restores the
                                // grade and resumes playback exactly like
                                // a lift).
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed ||
                                        event.changes.any { it.isConsumed } ||
                                        (change.position - down.position).getDistance() > slop
                                    ) {
                                        break
                                    }
                                }
                                startHold(false)
                            }
                        },
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
                // Histogram toggle (owner feature list W5): the mirror of
                // the fullscreen TopEnd toggle — the preview's corner icon
                // buttons read as one group, and the bar_chart glyph says
                // what it does. Same 48dp IconButton target, vendored
                // Material Symbols glyph (material-icons-core 1.7.3 has
                // no BarChart).
                HistogramToggleButton(
                    visible = histogramVisible,
                    onToggle = { histogramVisible = !histogramVisible },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
                // The overlay itself (BottomStart, mirroring the refresh
                // chip's BottomEnd): visible only when toggled on, drawn
                // from the recompute above — the same frame the preview
                // shows, so the histogram and the picture never disagree.
                if (histogramVisible) {
                    HistogramOverlay(
                        result = histogram,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
                // Fullscreen toggle (owner request 2026-11-11 section 2):
                // the SAME editor session — the toggle only changes the
                // layout, so the clip stays open, the transport keeps its
                // position and playback continues unharmed. Vendored
                // Material Symbols glyphs (material-icons-core 1.7.3 has
                // no Fullscreen); the IconButton carries the 48dp target.
                // Scrim behind the glyph (owner finding 2026-11-11): over
                // overexposed near-white footage the white icon vanished
                // into the frame. Every preview corner control carries the
                // same translucent surfaceContainerLowest scrim (roles,
                // never hex — DESIGN.md; the histogram overlay's backdrop
                // is the precedent) at the theme's small shape, so the
                // glyph reads over any frame. The Surface wraps, not
                // replaces, the IconButton — the 48dp touch target and
                // the click path are unchanged.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    IconButton(
                        onClick = { fullscreen = !fullscreen },
                    ) {
                    Icon(
                        painter = painterResource(
                            if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
                        ),
                        contentDescription = stringResource(
                            if (fullscreen) R.string.editor_fullscreen_exit else R.string.editor_fullscreen
                        ),
                    )
                    }
                }
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
                        // Pause keeps the position (owner request §1) and
                        // lands on the source resolution (section 2) —
                        // progressively: the fast 1280px tier sharpens the
                        // coarse playback frame first (~9x cheaper than the
                        // full-res develop), then the full-res follow-up
                        // replaces it. A later seek still wins by render
                        // sequence (offerIfEmpty never displaces it).
                        isPlaying = false
                        requestRender(
                            positionFrame, grade,
                            PreviewTiers.PAUSED_MAX_DIM, cached = true,
                            followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                        )
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
            // Hidden in fullscreen (owner request section 2).
            if (!fullscreen) {
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
            }
            // Settings panel — the ONLY region that scrolls (design review
            // "Onerilen ilk yerlesim": sabit ust bar + sinirlandirilmis
            // onizleme + kayan ayar paneli). The panel takes the flex
            // space left by the bounded preview and scrolls inside it, so
            // short screens + 1.3x text never squeeze the preview. Hidden
            // in fullscreen (owner request section 2): the preview takes
            // the flex space instead.
            if (!fullscreen) {
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

    // ---- Frame Picker sheet (RAW-11) ----
    // Opened from the clip-info sheet's "Develop frame" row, composed at
    // the screen root so it overlays the whole editor (the same placement
    // as the top bar's own export/info sheets). The sheet shows the
    // transport's CURRENT frame — a 1-BASED display number (frame 1 is the
    // first frame; the job itself carries the 0-based index, which is what
    // the native renderFrame takes) — plus its timecode, and offers the
    // two develop modes: the clip's current grade, or a fresh neutral
    // develop that leaves the clip's grade untouched. Each tap dismisses
    // the sheet first, then hands the choice to the caller's enqueue.
    if (framePickerOpen) {
        FramePickerSheet(
            frameIndex = positionFrame,
            elapsedUs = elapsedUs,
            fps = clipInfo.fps,
            onDevelopPhoto = { frameIndex, separate ->
                framePickerOpen = false
                onDevelopPhoto(frameIndex, separate)
            },
            onDismiss = { framePickerOpen = false },
        )
    }
    }
}

/**
 * The Frame Picker sheet (RAW-11): "Develop frame" over the transport's
 * current position. ExportSheet style — a ModalBottomSheet with a
 * titleSmall title, the frame + timecode readout (tnum so the digits stay
 * tabular, same as the transport strip), and one clickable ListItem per
 * develop mode. The two modes:
 *
 *  - "Photo: use clip grade" — develops this frame with the clip's CURRENT
 *    grade snapshot (exactly what the preview shows).
 *  - "Photo: separate develop" — starts a FRESH neutral grade for this
 *    frame; the clip's own grade is untouched (the job carries its own
 *    snapshot, so later clip edits never change the photo's develop).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FramePickerSheet(
    frameIndex: Int,
    elapsedUs: Long,
    fps: Float,
    onDevelopPhoto: (frameIndex: Int, separate: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_frame_picker_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Frame number + timecode: the display number is 1-BASED (frame 1
        // is the first frame — the count a photographer reads off a
        // clip), while the callback hands the 0-BASED index the render
        // pipeline takes. The timecode matches the transport strip
        // (same elapsed microseconds; fps derives from the real
        // timestamps, and formatTimecode falls back to 24 for an
        // underivable rate — a 0-fps clip still shows a sane clock).
        Text(
            text = stringResource(
                R.string.editor_frame_picker_frame,
                frameIndex + 1,
                Format.formatTimecode(elapsedUs, fps.toInt()),
            ),
            style = MaterialTheme.typography.labelMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_frame_picker_photo_clip)) },
            supportingContent = {
                Text(stringResource(R.string.editor_frame_picker_photo_clip_detail))
            },
            modifier = Modifier.clickable { onDevelopPhoto(frameIndex, false) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_frame_picker_photo_separate)) },
            supportingContent = {
                Text(stringResource(R.string.editor_frame_picker_photo_separate_detail))
            },
            modifier = Modifier.clickable { onDevelopPhoto(frameIndex, true) },
        )
        Spacer(Modifier.height(16.dp))
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
 * Detail tab (COL-15/COL-16), in order: Noise Reduction and Chroma Denoise
 * (DN-01..08 spatial denoise, percent 0..100, 0 = the stage skipped — the
 * old look), then Sharpness (edge sharpness, unsharp radius 1.5) and Detail
 * (fine-texture contrast, unsharp radius 0.75) sliders, then the Tonemapping
 * choice — Off / ACES / AgX M3 segmented buttons (DESIGN.md Inputs:
 * "segmented buttons for codec and scope choice" — the same single choice
 * pattern). The denoise pair leads because native stages 7/8 run BEFORE
 * sharpness/detail (19/20): sliders read in pipeline order, and denoising
 * first is also what makes the sharpeners amplify signal, not noise. All
 * five controls run on BOTH clip kinds: the native develop pipeline and
 * developEncoded share the same stages, so encoded (Mi-Log MP4) clips get
 * the same Detail tab with nothing hidden.
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
        // DN-01..08: both denoise controls first, in native pipeline order
        // (stage 7 chroma, stage 8 luma — before sharpness/detail 19/20).
        // Percent 0..100 like LUT Strength, whole steps, default 0 = the
        // stage skipped (bit-identical to the pre-DN look).
        GradeSlider(
            label = stringResource(R.string.editor_slider_noise_reduction),
            value = grade.noiseReduction,
            valueRange = 0f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = "${grade.noiseReduction.toInt()} %",
            onChange = { v ->
                onChange(grade.copy(noiseReduction = SliderSteps.snapNoiseReduction(v)))
            },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.NOISE_REDUCTION, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_chroma_denoise),
            value = grade.chromaDenoise,
            valueRange = 0f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = "${grade.chromaDenoise.toInt()} %",
            onChange = { v ->
                onChange(grade.copy(chromaDenoise = SliderSteps.snapChromaDenoise(v)))
            },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.CHROMA_DENOISE, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
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
