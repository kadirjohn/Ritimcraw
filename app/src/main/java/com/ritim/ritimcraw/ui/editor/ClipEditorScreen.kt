package com.ritim.ritimcraw.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.ritim.ritimcraw.engine.PreviewQuality
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Fullscreen chrome tap-toggle (owner request 2026-11-11d §16.2: "A
     * preview tap may toggle control visibility if this can be
     * implemented safely without interfering with current gestures"): a
     * CLEAN quick tap on the preview — lift inside the long-press
     * window, no slop movement, nothing consumed, so it can never fire
     * during the before/after hold (which needs the window to ELAPSE)
     * or a zoom/pan (which moves or consumes) — toggles the fullscreen
     * overlay chrome (transport row, corner chips, histogram overlay).
     * ONLY in fullscreen: outside it a preview tap does nothing — the
     * panel and navigation are structural layout, not overlay chrome,
     * and §16.1's hide affordance is the fullscreen toggle itself. Pure
     * so a JVM test locks the gating.
     */
    fun togglesFullscreenChrome(inFullscreen: Boolean): Boolean = inFullscreen

    /**
     * True when the Temporal NR group (toggle + strength slider) belongs in
     * the Detail tab. Temporal denoise merges neighboring CFA frames
     * (DN-05..DN-09), so it needs the MCRAW clip kind; the encoded (Mi-Log
     * MP4) path takes the same Grade but the bridge ignores the temporal
     * fields there (no CFA, no neighbor frames). A separate named decision
     * from [showRawStageToggles] so the two can diverge if one grows a
     * second supported path.
     */
    fun showTemporalNr(path: SourcePath): Boolean = path == SourcePath.MCRAW

    /**
     * True when the Stabilization group (toggle + mode + strength + zoom +
     * sync status + focal) belongs in the Detail tab. The warp consumes the
     * gyro log against the clip's CFA frames, so it needs the MCRAW clip
     * kind; the encoded (Mi-Log MP4) path takes the same Grade but the
     * bridge ignores the stabilization fields there (no CFA, no gyro
     * anchor). A separate named decision from [showTemporalNr] so the two
     * can diverge (gyro Phase 5).
     */
    fun showStabilization(path: SourcePath): Boolean = path == SourcePath.MCRAW

    /**
     * The sync status line's verdict for the Stabilization group (gyro
     * Phase 5): which of the three states the probe data supports. Pure so
     * a JVM test can lock the mapping — the decision itself reads the
     * probe's raw numbers, never a UI string.
     *
     * [gyroAttached] false = no sidecar .gcsv: NoData — the group shows
     * the honest no-data state and stabilization stays a no-op (never a
     * fabricated verdict). A probe that FAILED (probeFailed) also reads as
     * NoData: the attach flow removes failed entries, so this is the
     * transient between the failure and the removal.
     *
     * [anchored] true = both wall-clock anchors were present (the clip's
     * recvd anchor vs the log's header timestamp): Anchored, with the
     * probe's [errorBoundMs] shown as the ± bound. false = the anchors
     * were absent: Manual — the trim slider is the user's offset.
     */
    fun syncStatus(
        gyroAttached: Boolean,
        probeFailed: Boolean,
        anchored: Boolean,
    ): GyroSyncStatus = when {
        !gyroAttached || probeFailed -> GyroSyncStatus.NO_DATA
        anchored -> GyroSyncStatus.ANCHORED
        else -> GyroSyncStatus.MANUAL
    }

    /**
     * The focal length the stabilization math uses (gyro Phase 5): the
     * grade's override wins when set; otherwise the container's
     * 35mm-equivalent value (0 = container absent too — the group shows
     * the honest intrinsics-assumed state and the user must set a value).
     */
    fun effectiveFocalMm(gradeOverrideMm: Float, containerMm: Float): Float =
        if (gradeOverrideMm > 0f) gradeOverrideMm else containerMm

    /**
     * Pixel focal length from a 35mm-equivalent millimeter value (gyro
     * Phase 5): f_px = width · mm / 36 — the full-frame horizontal
     * convention (36 mm film width), the core's
     * focalLengthPxFromEquivalent mirror. 0 for a non-positive mm or
     * width (the caller refuses the warp rather than fabricating an
     * intrinsics-free homography). Pure so a JVM test locks the flagged
     * conversion (Stabilization.h documents the same formula).
     */
    fun focalLengthPxFromEquivalent(mm35: Float, width: Int): Float =
        if (mm35 <= 0f || width <= 0) 0f else width * mm35 / 36f

    /**
     * True when the stabilization math may run at all (gyro Phase 5): the
     * stage needs the toggle on, a gyro log attached, and a usable pixel
     * focal (container or override). False reads as the no-op pass-through
     * — the group still shows, with the honest status line explaining why.
     */
    fun stabilizationReady(
        stabilizationOn: Boolean,
        gyroAttached: Boolean,
        focalMm: Float,
    ): Boolean = stabilizationOn && gyroAttached && focalMm > 0f
}

/**
 * The Stabilization group's sync-status verdict (gyro Phase 5): the pure
 * result of [EditorPreviewLogic.syncStatus], rendered by the group as the
 * localized status line (Anchored ±N ms / Manual offset / No gyro data).
 */
enum class GyroSyncStatus { ANCHORED, MANUAL, NO_DATA }

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
 * as M3 segmented buttons, and — on MCRAW clips — the Temporal NR toggle
 * with its Strength slider (DN-05..DN-09).
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
 * Over the preview (owner feature list W5): a toggleable 256-bin RGB +
 * luma histogram (compact combined view, tap to expand with RGB / Luma
 * / Parade modes and shadow-highlight clipping indicators) — recomputed
 * on every frame publish from the same frame the preview shows — and
 * the before/after press-hold: hold the frame area to swap to the
 * neutral grade, release to return.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // ---- Preview quality (owner request 2026-11-11b section 3) ----
    // The user's playback/scrub resolution choice (app-level,
    // PreviewQualityStore). The screen owns no store: AppNav reads the
    // persisted level and hands it in; the DEFAULT keeps every caller
    // that does not pass one bit-identical to the pre-picker tiers.
    previewQuality: PreviewQuality = PreviewQuality.DEFAULT,
    // ---- Manual rotation (owner request 2026-11-11 §5) ----
    // The clip's persisted manual rotation delta (0/90/180/270). No local
    // state: the persisted MediaSource record IS the state (AppNav reads
    // it per clip and refreshes after each tap), so a configuration change
    // or a clip switch shows the stored value with nothing extra to save.
    manualRotationDeg: Int = 0,
    // Rotates by ±90 (the clip-info sheet's two rows; AppNav persists
    // through SourceRepository.setManualRotation, normalized 0..359).
    onRotate: (byDeg: Int) -> Unit = {},
    // ---- Stabilization wiring (gyro Phase 5) ----
    // The clip's attached gyro log id (MediaSource.gyroId, AppNav reads the
    // persisted record per clip and refreshes after attach/remove). Blank =
    // none: the group shows the honest no-data status and the warp stays a
    // no-op. The name line resolves the id through the store.
    gyroId: String = "",
    gyroNameFor: (String) -> String? = { null },
    // The attached gyro log's id -> its copied .gcsv path (AppNav:
    // GyroStore.pathFor). Null = no log: the render warp is a no-op. The
    // pump resolves it PER REQUEST next to lutPathFor, so an attach or
    // detach mid-session is picked up on the very next render without
    // restarting the pump (the LaunchedEffect(sourceId) lives longer than
    // a single attachment; the id is mirrored through rememberUpdatedState
    // for exactly that reason — the lutPathFor precedent).
    gyroPathFor: (String) -> String? = { null },
    // The sidecar .gcsv's probe result (AppNav: jniGyroSyncProbe on the
    // clip's fd + the copied log's path — [anchored, offsetMs, boundMs,
    // focalMm, samples]). Null = no log attached or the probe failed; the
    // group maps it to the status line (pure EditorPreviewLogic.syncStatus).
    // The container focal is the probe's focalMm (the clip's own
    // 35mm-equivalent value, 0 = absent).
    gyroSync: DoubleArray? = null,
    containerFocalMm: Float = 0f,
    // Imports the picked SAF .gcsv (AppNav: GyroStore.import + the native
    // sync probe + gyroId persist). Returns the probe on success or the
    // failure for the group's local error line; never throws.
    onAttachGyro: (uri: String, fallbackName: String) -> Result<DoubleArray> =
        { _, _ -> Result.failure(IllegalStateException()) },
    // Detaches the log (AppNav: setGyro(id, "") + refresh).
    onRemoveGyro: () -> Unit = {},
) {
    RitimTheme(editor = true) {
        var tab by rememberSaveable { mutableStateOf(0) }
        // ---- Draggable panel state (owner request 2026-11-11d §10-11) ----
        // The panel's vertical state is saveable (§11.6: survives
        // configuration changes like the tab does) and the drag state is
        // remembered ONCE with the restored value as its initial anchor
        // target — a rotation keeps the panel exactly where it was, and
        // the offset is never recreated (§18: no reset, no reprocessing).
        var panelState by rememberSaveable { mutableStateOf(PanelState.NORMAL) }
        val panelDrag = rememberPanelDragState(panelState)
        // Per-category scroll positions (§11.6 "relevant scroll position"):
        // one ScrollState per category, remembered SAVEABLY (ScrollState's
        // own Saver) so the offsets also survive process death and
        // configuration change. The panel sheet picks its list's state by
        // the active tab, so switching category restores that category's
        // own offset instead of inheriting the previous one's, and each
        // category keeps its position while the user edits another.
        val lightScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val colorScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val detailScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val categoryScrollStates = mapOf(0 to lightScroll, 1 to colorScroll, 2 to detailScroll)
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        // The measured heights the anchor math needs (§11.3: "adapt to
        // the measured device size"). NONE of these depends on the
        // panel's own offset, so the anchors can never churn mid-drag
        // (the preview Box takes the flex space between them — it
        // flexes as the panel grows/shrinks, §11.7):
        //   free region = column height - top bar (56dp constant) -
        //                 transport height - nav height
        // The top bar is a fixed 56dp Row (EditorTopBar), so its px value
        // is a density conversion, not a measurement. Transport and nav
        // are measured via onSizeChanged because their heights vary with
        // font scale and precision-mode readout.
        var editorColumnHeight by remember { mutableStateOf(0) }
        var transportHeight by remember { mutableStateOf(0) }
        var navHeight by remember { mutableStateOf(0) }
        val topBarHeightPx = with(density) { EditorTopBarHeight.toPx() }
        // The collapsed anchor: the handle strip's height (§11.3 state A).
        val collapsedHeightPx = with(density) { PanelHandleHeight.roundToPx() }
        // The free region for the panel + preview together (§11.3): the
        // column minus the fixed rows above and below the flex area.
        val freeRegionPx = (editorColumnHeight - topBarHeightPx.toInt() -
            transportHeight - navHeight).coerceAtLeast(0)
        // Anchors update whenever the measured region changes (first
        // composition, rotation, font-scale change). updateAnchors snaps
        // synchronously to the CURRENT target, so the offset lands on the
        // restored state's height on the first frame and follows the
        // region on re-measures — never a re-animation from zero.
        LaunchedEffect(freeRegionPx, collapsedHeightPx) {
            if (freeRegionPx > 0) {
                panelDrag.updateAnchors(
                    DraggableAnchors {
                        PanelSheet.anchorHeights(freeRegionPx, collapsedHeightPx).forEach { (state, h) ->
                            state.at(h)
                        }
                    },
                    panelState,
                )
            }
        }
        // The saveable mirror: settledValue is the state the panel has
        // settled on (drag end / animation end), so the saveable keeps
        // exactly what the user last saw (§11.6).
        LaunchedEffect(panelDrag) {
            snapshotFlow { panelDrag.settledValue }
                .collect { settled -> panelState = settled }
        }
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
        // Fullscreen overlay chrome (§16.2): the transport row, the corner
        // chips (refresh, histogram toggle) and the histogram overlay hide
        // behind a clean quick tap on the preview — §16.2's optional "A
        // preview tap may toggle control visibility", classified inside the
        // SAME gesture window as the before/after hold (a clean tap lifts
        // BEFORE the long-press window elapses; the hold needs it to elapse
        // — the two can never both fire on one gesture). Saveable so a
        // rotation keeps the chrome where it was; re-entering fullscreen
        // always shows the chrome again (a fresh entry never inherits a
        // hidden state). The exit toggle itself stays visible while hidden
        // chrome is on (the scrimmed TopEnd button is §16.2's retained
        // Exit-fullscreen control; hiding it would strand the user).
        var fullscreenChromeVisible by rememberSaveable { mutableStateOf(true) }
        LaunchedEffect(fullscreen) {
            if (fullscreen) fullscreenChromeVisible = true
        }
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

        // Precision frame scrub (owner request 2026-11-11 section 2): a
        // press-and-hold on the seek bar enters precision frame mode and
        // the same physical drag then steps WHOLE frames instead of
        // seeking by time. Pure decisions in PrecisionScrub, the gesture
        // in EditorTransport (precisionScrubGesture); this screen only
        // owns the state: active flag + the frame the gesture started
        // from (the gesture reports the TOTAL dp since it engaged, so the
        // mapping is anchored once). Transient like dragActive — a clip
        // switch resets it with the rest of the transport.
        var precisionActive by remember(sourceId) { mutableStateOf(false) }
        var precisionStartFrame by remember(sourceId) { mutableStateOf(0) }

        // Histogram overlay (owner feature list W5): toggleable 256-bin
        // RGB + luma histogram with clipping indicators over the preview.
        // histogramVisible is saveable so it survives configuration
        // changes like the tab and fullscreen do; the histogram itself is
        // recomputed inside the render pump's publish — the one place the
        // on-screen frame is swapped — so the overlay always shows the
        // frame the preview currently shows, and toggling on is instant
        // (the result is already current). Keyed to the clip: a clip
        // switch starts empty.
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

        // ---- Gyro attach (gyro Phase 5) ----
        // Same shape as the LUT import: the error is LOCAL to the
        // Stabilization group (never the preview's error surface), and the
        // SAF picker takes any mime — the native sync probe validates the
        // .gcsv, and a non-gcsv pick fails the probe with the recovery
        // message instead of being blocked at the picker.
        var gyroImportError by remember(sourceId) { mutableStateOf<String?>(null) }
        val gyroPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?: uri.lastPathSegment
                ?: ""
            // AppNav does GyroStore.import + jniGyroSyncProbe + the gyroId
            // persist; the failure message lands in the group's error line.
            onAttachGyro(uri.toString(), fallbackName).fold(
                onSuccess = { gyroImportError = null },
                onFailure = { e -> gyroImportError = e.message ?: "gyro import failed" },
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

        /**
         * Applies one SliderTiers transition and renders per its decision.
         * The drag-tier cap is the user's scrub dim (preview-quality
         * setting, owner request 2026-11-11b section 3); the settle tier
         * stays the fixed full preview tier.
         */
        fun applyTierEvent(event: SliderTiers.Event) {
            val t = SliderTiers.onEvent(event, dragActive, previewQuality.scrubMaxDim)
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
                SliderTiers.tierFor(dragActive, previewQuality.scrubMaxDim),
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
        // Gyro Phase 5: the pump's LaunchedEffect(sourceId) captures its
        // body ONCE at launch and outlives any single attachment, so the
        // CURRENT gyro id + path resolver are mirrored per recomposition
        // here (in the composable body) — an attach or detach mid-session
        // is picked up on the next render without restarting the pump.
        val currentGyroPathFor by rememberUpdatedState(gyroPathFor)
        val currentGyroId by rememberUpdatedState(gyroId)

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
                    // Gyro Phase 5: same per-request resolution for the
                    // attached gyro log (null = no warp). The tier gate
                    // itself lives at the seam (GradeMapping.stabilizationArgs
                    // -> PreviewTiers.stabilizationAppliesAt), so the pump
                    // stays policy-free and every arm just carries the path.
                    val gyroPath = currentGyroId.takeIf { it.isNotBlank() }?.let(currentGyroPathFor)
                    val result = if (sessionRender && s != null) {
                        if (req.cached) {
                            // Interactive drag render: the bridge reuses the
                            // cached CFA when the frame is unchanged.
                            s.renderCached(req.frameIndex, req.grade, req.maxDim, lutPath, gyroPath)
                        } else {
                            s.render(req.frameIndex, req.grade, req.maxDim, lutPath, gyroPath)
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
                                    gyroPath = gyroPath,
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
                                // frame the preview is not showing. The pump
                                // is a main-dispatcher LaunchedEffect and the
                                // render resumes back on it, so the pass is
                                // dispatched to Default for the four-channel
                                // scan (still sub-millisecond: strided to at
                                // most FrameHistogram.MAX_SAMPLES pixels) —
                                // the UI thread only performs the state write.
                                // Assignment ordering is unchanged: inside the
                                // isFresh guard, a stale frame's histogram can
                                // never overwrite a newer one's. A hidden
                                // overlay still recomputes (cheap) so toggling
                                // on is instant.
                                histogram = withContext(Dispatchers.Default) {
                                    FrameHistogram.of(f.argb)
                                }
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
                    // Playback tier = the user's preview-quality choice
                    // (owner request 2026-11-11b section 3); the default
                    // equals the old fixed PLAYBACK_MAX_DIM.
                    requestRender(idx, grade, previewQuality.playbackMaxDim)
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
            // Manual rotation (§5): the persisted delta; the sheet's
            // rotation row shows it and the two rotate actions tap it.
            manualRotationDeg = manualRotationDeg,
        )

        // Neutral root surface (design review 2026-11-10, maroon bug): the
        // editor's own Column painted NO background, so AppNav's Scaffold
        // surface (dynamic wallpaper color) showed through. The editor now
        // paints its own fixed-neutral surface INSIDE RitimTheme(editor =
        // true); inset ownership stays in AppNav. Fullscreen darkens the
        // root to surfaceDim (§16.2 "Use a neutral near-black background"):
        // the same 0e0e0e the preview surface itself paints, so the
        // letterbox around a centered frame reads as one seamless near-
        // black stage (roles, never hex — DESIGN.md).
        Surface(
            color = if (fullscreen) {
                MaterialTheme.colorScheme.surfaceDim
            } else {
                MaterialTheme.colorScheme.surface
            },
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                // The panel anchor math measures THIS column (the free
                // region is what remains after the fixed rows; see the
                // wiring block above). onSizeChanged — not a layout
                // callback into composition — so the measurement can
                // never feed back into the same frame's layout.
                .onSizeChanged { editorColumnHeight = it.height },
        ) {
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
                    // Manual rotation (§5): the sheet's rotate rows.
                    onRotate = onRotate,
                )
            }
            // Flexing preview region (owner request 2026-11-11d §11.7):
            // the preview takes whatever the panel leaves — collapsed
            // panel gives it the most space, expanded the least — and
            // in fullscreen it takes the whole flex space (the panel and
            // navigation are hidden). PreviewSurface still shapes itself
            // by the clip's ratio (ratio chain preserved) and centers
            // inside the flex Box. The frame's position is still
            // deterministic while grading: the panel height only moves
            // on a handle drag, never on a tab/category switch.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                PreviewSurface(
                    frame = frame,
                    state = previewState,
                    errorText = errorText,
                    accessLost = accessLost,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    // Manual rotation (§5): the display turns ONLY this
                    // delta — the native develop already renders
                    // container-upright pixels, so there is never a double
                    // rotation.
                    manualRotationDeg = manualRotationDeg,
                    // §6 zoom/pan: the clip identity keys the transform
                    // state (reset on clip change, saveable across
                    // configuration changes) — one pass-through line,
                    // the whole feature lives in PreviewSurface/ZoomPan.
                    clipKey = sourceId,
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
                        .pointerInput(fullscreen) {
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // The long-press window: true when the press
                                // ended early (lift, drag, or consumed),
                                // null when the window elapsed with the
                                // finger still down. The early-end REASON is
                                // kept: a CLEAN lift (still pressed at lift,
                                // no slop, nothing consumed) is a quick tap
                                // — §16.2's preview-tap chrome toggle in
                                // fullscreen; anything else (slop, a
                                // consumed stream — zoom/pan, a chip) is a
                                // drag or a control touch and never toggles.
                                var cleanTap = false
                                val endedEarly = withTimeoutOrNull(longPressTimeout) {
                                    var lifted = false
                                    while (!lifted) {
                                        val event = awaitPointerEvent()
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) {
                                            // Lift inside the window: a tap
                                            // only if nothing moved or was
                                            // consumed on the way.
                                            cleanTap = change != null &&
                                                !event.changes.any { it.isConsumed } &&
                                                (change.position - down.position).getDistance() <= slop
                                            lifted = true
                                        } else if (event.changes.any { it.isConsumed } ||
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
                                // a tap/drag, not a hold. A clean tap in
                                // fullscreen toggles the overlay chrome
                                // (pure gate in EditorPreviewLogic).
                                if (endedEarly != null) {
                                    if (cleanTap && EditorPreviewLogic.togglesFullscreenChrome(fullscreen)) {
                                        fullscreenChromeVisible = !fullscreenChromeVisible
                                    }
                                    return@awaitEachGesture
                                }
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
                // Hidden with the fullscreen chrome (§16.2: minimal
                // overlays; the tap toggle brings it back).
                if (!fullscreen || fullscreenChromeVisible) {
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
            // The modifier measures the row for the panel anchor math
            // (§11.3) — its height varies with font scale and the
            // precision-mode readout, so it is measured, not assumed.
            // Fullscreen keeps the transport (§16.2 retains play/pause and
            // seeking) but hides it with the overlay chrome on a preview
            // tap; a hidden transport never contributes to the anchor math
            // (transportHeight stays at its last measured value only while
            // the panel is hidden too — both are outside this branch).
            if (!fullscreen || fullscreenChromeVisible) {
            TransportRow(
                modifier = Modifier
                    .onSizeChanged { transportHeight = it.height },
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
                        requestRender(
                            idx, grade,
                            SliderTiers.tierFor(dragActive, previewQuality.scrubMaxDim),
                            cached = true,
                        )
                    }
                },
                onSeekStarted = { applyTierEvent(SliderTiers.Event.DragStarted) },
                onSeekFinished = { applyTierEvent(SliderTiers.Event.DragEnded) },
                // Adaptive readout format (owner request 2026-11-11b
                // section 7): the transport pair's field count follows the
                // clip's TOTAL (2/3/4 fields), so the salise field only
                // appears when the clip runs a minute or longer. The real
                // clip fps feeds the frames field (the previous pair used
                // Format's 24 default; the precision readout already used
                // the real rate — now the pair matches it).
                fps = clipInfo.fps,
                // Precision frame scrub (owner request 2026-11-11 section
                // 2): while active, the readout line above the bar shows
                // the exact 1-based frame number + its timecode (the
                // frame picker's string precedent). The timecode now uses
                // the ADAPTIVE format (owner request 2026-11-11b section
                // 7: the precision readout matches the transport pair) —
                // same elapsed microseconds and same nominal fps as the
                // pair, so the two readouts agree on every shared field.
                // PrecisionScrub.readoutTimecode (PrecisionScrub.kt, fixed
                // 4-field, outside this change's file footprint) stays the
                // pure fixed-format helper; the FORMAT CALL here is the
                // only precision-readout change.
                precisionReadout = if (precisionActive && pts != null) {
                    stringResource(
                        R.string.editor_precision_frame,
                        positionFrame + 1,
                        Format.adaptiveTimecode(
                            elapsedUs,
                            Format.nominalFps(clipInfo.fps),
                            Format.fieldsFor(totalUs),
                        ),
                    )
                } else {
                    null
                },
                onPrecisionEngage = {
                    // The pure engage decision owns the branch; this
                    // lambda only writes the states it decides.
                    when (
                        PrecisionScrub.onEngage(
                            isPlaying = isPlaying,
                            enabled = pts != null && pts.size > 1,
                            frameCount = pts?.size ?: 0,
                            canRender = canRender,
                        )
                    ) {
                        PrecisionScrub.Engage.Reject -> false
                        PrecisionScrub.Engage.PauseAndEnter -> {
                            // Pause the wall clock and settle progressively
                            // — the pause button's exact path, so a hold
                            // during playback behaves like pausing (the
                            // follow-up only survives as the newest request,
                            // which the first scrub step naturally is).
                            isPlaying = false
                            requestRender(
                                positionFrame, grade,
                                PreviewTiers.PAUSED_MAX_DIM, cached = true,
                                followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                            )
                            precisionActive = true
                            precisionStartFrame = positionFrame
                            true
                        }
                        PrecisionScrub.Engage.Enter -> {
                            // Already paused: the current frame is on
                            // screen; only the mapping anchors here.
                            precisionActive = true
                            precisionStartFrame = positionFrame
                            true
                        }
                    }
                },
                onPrecisionDragDp = { totalDp ->
                    val p = pts ?: return@TransportRow
                    // Quantized whole frames (round-to-nearest): sub-half
                    // wiggles hold the frame, and an unchanged frame skips
                    // the render entirely. Every step renders the paused
                    // tier through the cached path; the render gate
                    // coalesces rapid steps into the newest pending one.
                    val frame = PrecisionScrub.frameForDrag(precisionStartFrame, totalDp, p.size)
                    if (frame != positionFrame) {
                        positionFrame = frame
                        requestRender(
                            frame, grade,
                            PreviewTiers.PAUSED_MAX_DIM, cached = true,
                        )
                    }
                },
                onPrecisionRelease = {
                    // The owner's spec: release KEEPS the parked frame
                    // (pause behavior) and settles it progressively — the
                    // pause button's path. The slider's press cancel still
                    // emits its own DragEnded settle (1280, same as every
                    // drag release today); whichever request is newest
                    // when the pump drains wins, so the frame is correct
                    // either way and the SOURCE follow-up lands whenever
                    // this release is the last request.
                    if (precisionActive) {
                        precisionActive = false
                        requestRender(
                            positionFrame, grade,
                            PreviewTiers.PAUSED_MAX_DIM, cached = true,
                            followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                        )
                    }
                },
            )
            }
            // Draggable, collapsible adjustment panel (owner request
            // 2026-11-11d §11): the bottom-anchored sheet whose height IS
            // the drag offset, settling between the three PanelSheet
            // anchors. The handle strip at its top is the ONLY node with
            // the panel drag modifier (§11.5 gesture isolation: sliders,
            // the scrolling list, the timeline and the navigation sit on
            // other nodes). The category content is ALWAYS composed —
            // clipped, not removed, while collapsed — so the active
            // category, every adjustment value, the per-category scroll
            // position, playback and the render session all survive
            // collapse/expand by construction (§10.3, §11.6, §18).
            // Hidden in fullscreen (owner request section 2): the preview
            // takes the flex space instead.
            if (!fullscreen) {
                EditorPanelSheet(
                    dragState = panelDrag,
                    category = tab,
                    scrollStates = categoryScrollStates,
                    // NaN guard: the offset is uninitialized until the
                    // first updateAnchors lands (getOffset returns NaN
                    // before then), so the first frame composes the
                    // sheet at the collapsed-strip height and the
                    // anchors effect snaps it to the restored state
                    // synchronously on the same first layout pass.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            if (panelDrag.anchors.size == 0) PanelHandleHeight
                            else with(density) { panelDrag.offset.toDp() },
                        ),
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
                        // Temporal NR merges neighboring CFA frames, so the
                        // group shows only on MCRAW clips (pure decision in
                        // EditorPreviewLogic.showTemporalNr; the encoded path
                        // takes-and-ignores the temporal fields).
                        showTemporalNr = EditorPreviewLogic.showTemporalNr(sourcePath),
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
                        // The Temporal NR switch is not a drag either: it
                        // settles IMMEDIATELY at the full tier, like the
                        // RAW toggles and the tonemapping choice.
                        onToggleChange = { g ->
                            grade = g
                            onGradeChange(g)
                            applyTierEvent(SliderTiers.Event.GradeEdited)
                        },
                        // ---- Stabilization wiring (gyro Phase 5) ----
                        // The group shows on MCRAW only (pure decision in
                        // EditorPreviewLogic.showStabilization). The switch
                        // and the mode choice settle IMMEDIATELY at the full
                        // tier (not drags), like every other toggle; the
                        // strength/zoom/trim/focal sliders are drags through
                        // onChange/onDragActiveChange like every other
                        // slider.
                        showStabilization = EditorPreviewLogic.showStabilization(sourcePath),
                        gyroAttached = gyroId.isNotBlank(),
                        gyroSync = gyroSync,
                        gyroName = gyroNameFor(gyroId),
                        containerFocalMm = containerFocalMm,
                        gyroImportError = gyroImportError,
                        onAttachGyro = { gyroPicker.launch(arrayOf("*/*")) },
                        onRemoveGyro = onRemoveGyro,
                    )
                }
                }
            }
            // Fixed Light/Color/Detail bottom navigation (owner request
            // 2026-11-11d §10): OUTSIDE the draggable panel, always the
            // editor's LAST row, so it stays visible and tappable in
            // every panel state. The current category stays selected
            // while collapsed (§10.3); a tap while collapsed opens the
            // panel to its normal editing position (§10.3), a tap while
            // open only switches the category — the panel height never
            // moves on a category switch. The pure decisions live in
            // PanelSheet (panelAfterCategoryTap) and EditorPreviewLogic
            // nothing here re-derives. Hidden in fullscreen (owner
            // request section 2).
            if (!fullscreen) {
                EditorBottomNav(
                    selected = tab,
                    onCategoryTap = { category ->
                        tab = category
                        // targetValue (not the saveable mirror): a tap
                        // mid-settle reads the panel's live heading, so
                        // a panel still animating toward COLLAPSED opens
                        // instead of staying shut (§10.3 "if a user
                        // selects a category while the panel is
                        // collapsed, open the panel to its normal
                        // editing position").
                        val target = PanelSheet.panelAfterCategoryTap(panelDrag.targetValue)
                        if (target != panelDrag.targetValue) {
                            scope.launch { panelDrag.animateTo(target) }
                        }
                    },
                    modifier = Modifier
                        .onSizeChanged { navHeight = it.height },
                )
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
            totalUs = totalUs,
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
    totalUs: Long,
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
        // pipeline takes. The timecode matches the transport strip's
        // ADAPTIVE format (owner request 2026-11-11b section 7: both
        // readouts agree on every shared field) — same elapsed
        // microseconds, same nominal fps (falls back to 24 for an
        // underivable rate), same 2/3/4 field count from the clip's
        // total. [totalUs] is passed in (the sheet's caller owns it).
        Text(
            text = stringResource(
                R.string.editor_frame_picker_frame,
                frameIndex + 1,
                Format.adaptiveTimecode(
                    elapsedUs,
                    Format.nominalFps(fps),
                    Format.fieldsFor(totalUs),
                ),
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
 * Temporal NR (DN-05..DN-09): an M3 Switch row plus a 0..100 Strength
 * slider, shown only when [showTemporalNr] is true (MCRAW). The temporal
 * stage merges the neighboring CFA frames into the center frame before
 * develop, motion-compensated and confidence-weighted; the toggle settles
 * at the full tier immediately (not a drag), the strength slider is a drag
 * like every other slider. Encoded (Mi-Log MP4) clips never see the group —
 * the bridge takes-and-ignores the temporal fields on that path.
 * Stabilization (gyro Phase 5): on MCRAW clips an Off/On switch, the Mode
 * choice (Smooth / Lock First Frame / Horizon Lock), Strength and Zoom limit
 * sliders, the sync status line with the gyro attach/remove actions, the
 * ±500 ms sync trim and the focal-length control — the honest no-data
 * state when no .gcsv is attached, never a fabricated verdict.
 */
@Composable
private fun DetailPanel(
    grade: GradeK,
    showTemporalNr: Boolean,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onTonemapChange: (GradeK) -> Unit = {},
    lutEntries: List<LutInfo> = emptyList(),
    lutImportError: String? = null,
    onImportLut: () -> Unit = {},
    onRemoveLut: (LutInfo) -> Unit = {},
    onLutSelect: (GradeK) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    // ---- Stabilization group (gyro Phase 5) ----
    showStabilization: Boolean = false,
    gyroAttached: Boolean = false,
    gyroSync: DoubleArray? = null,
    gyroName: String? = null,
    containerFocalMm: Float = 0f,
    gyroImportError: String? = null,
    onAttachGyro: () -> Unit = {},
    onRemoveGyro: () -> Unit = {},
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
            valueLabel = Format.formatPercent(grade.noiseReduction.toInt()),
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
            valueLabel = Format.formatPercent(grade.chromaDenoise.toInt()),
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
                valueLabel = Format.formatPercent(grade.lutStrength.toInt()),
                onChange = { v ->
                    onChange(grade.copy(lutStrength = SliderSteps.snapLutStrength(v)))
                },
                onDoubleTapReset = {
                    onChange(SliderSteps.resetToDefault(SliderSteps.Axis.LUT_STRENGTH, grade))
                },
                onDragActiveChange = onDragActiveChange,
            )
        }
        // Temporal NR: MCRAW only. The switch settles immediately at the
        // full tier ([onToggleChange]); the strength slider below it is a
        // drag like every other slider ([onChange] + [onDragActiveChange]).
        if (showTemporalNr) {
            RawToggleRow(
                label = stringResource(R.string.editor_temporal_nr),
                checked = grade.temporalNr,
                onCheckedChange = { checked ->
                    // First enable seeds the midpoint strength: the stage is
                    // a no-op at 0, so a toggle that visibly changes nothing
                    // would read as broken. A remembered strength (the user
                    // dialed one earlier, then switched off) survives the
                    // off/on cycle untouched.
                    onToggleChange(
                        grade.copy(
                            temporalNr = checked,
                            temporalStrength = if (checked && grade.temporalStrength <= 0f) {
                                SliderSteps.TEMPORAL_STRENGTH_DEFAULT
                            } else {
                                grade.temporalStrength
                            },
                        )
                    )
                },
            )
            // The strength slider only makes sense while the stage is on;
            // hiding it keeps the off state visually final (and the grade
            // keeps its strength value for when the user re-enables).
            if (grade.temporalNr) {
                GradeSlider(
                    label = stringResource(R.string.editor_slider_temporal_strength),
                    value = grade.temporalStrength,
                    valueRange = 0f..100f,
                    step = SliderSteps.UNIT_STEP,
                    valueLabel = Format.formatUnsigned(grade.temporalStrength.toInt()),
                    onChange = { v ->
                        onChange(grade.copy(temporalStrength = SliderSteps.snapTemporalStrength(v)))
                    },
                    onDoubleTapReset = {
                        onChange(SliderSteps.resetToDefault(SliderSteps.Axis.TEMPORAL_STRENGTH, grade))
                    },
                    onDragActiveChange = onDragActiveChange,
                )
            }
        }
        // ---- Stabilization group (gyro Phase 5) ----
        // MCRAW only (showStabilization): the warp consumes the gyro log
        // against the clip's CFA frames. The switch settles immediately at
        // the full tier ([onToggleChange]); every slider below is a drag
        // ([onChange] + [onDragActiveChange]) like the rest of the panel.
        // The group ALWAYS shows its honest state: no log attached = the
        // no-data status line and an attach action, never a fabricated
        // verdict — the warp itself stays a no-op until a log exists.
        if (showStabilization) {
            // The probe's numbers drive the status line (pure decision in
            // EditorPreviewLogic.syncStatus): Anchored ±bound / Manual
            // offset / No gyro data. gyroSync holds [anchored, offsetMs,
            // boundMs, focalMm, samples] from the attach-time probe.
            val sync = EditorPreviewLogic.syncStatus(
                gyroAttached = gyroAttached,
                probeFailed = gyroSync == null,
                anchored = gyroSync != null && gyroSync[0] > 0.0,
            )
            val statusText = when (sync) {
                GyroSyncStatus.ANCHORED -> stringResource(
                    R.string.editor_stabilization_sync_anchored,
                    gyroSync?.get(2)?.toInt() ?: 0,
                )
                GyroSyncStatus.MANUAL ->
                    stringResource(R.string.editor_stabilization_sync_manual)
                GyroSyncStatus.NO_DATA ->
                    stringResource(R.string.editor_stabilization_sync_no_data)
            }
            Text(
                stringResource(R.string.editor_stabilization),
                style = MaterialTheme.typography.labelLarge,
            )
            RawToggleRow(
                label = stringResource(R.string.editor_stabilization),
                checked = grade.stabilization,
                onCheckedChange = { checked ->
                    // First enable seeds the default strength, same
                    // reasoning as temporal NR: the stage is a no-op at 0,
                    // so a toggle that visibly changes nothing would read
                    // as broken. A remembered strength survives the
                    // off/on cycle untouched.
                    onToggleChange(
                        grade.copy(
                            stabilization = checked,
                            stabilizationStrength = if (checked && grade.stabilizationStrength <= 0f) {
                                SliderSteps.STABILIZATION_STRENGTH_DEFAULT
                            } else {
                                grade.stabilizationStrength
                            },
                        )
                    )
                },
            )
            if (grade.stabilization) {
                // Mode: Smooth (0) / Lock First Frame (1) / Horizon Lock
                // (2) — the GradeK ints, segmented like the tonemap choice.
                // Not a drag: settles immediately at the full tier.
                Text(
                    stringResource(R.string.editor_stabilization_mode),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        StabModeOption(R.string.editor_stabilization_mode_smooth, 0),
                        StabModeOption(R.string.editor_stabilization_mode_lock_first_frame, 1),
                        StabModeOption(R.string.editor_stabilization_mode_horizon_lock, 2),
                    )
                    modes.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = grade.stabilizationMode == option.value,
                            onClick = {
                                onToggleChange(grade.copy(stabilizationMode = option.value))
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = modes.size,
                            ),
                        ) {
                            Text(stringResource(option.labelRes))
                        }
                    }
                }
                // Strength 0..100 (default 70): the smoothing amount.
                GradeSlider(
                    label = stringResource(R.string.editor_slider_stabilization_strength),
                    value = grade.stabilizationStrength,
                    valueRange = 0f..100f,
                    step = SliderSteps.UNIT_STEP,
                    valueLabel = Format.formatPercent(grade.stabilizationStrength.toInt()),
                    onChange = { v ->
                        onChange(
                            grade.copy(
                                stabilizationStrength = SliderSteps.snapStabilizationStrength(v),
                            )
                        )
                    },
                    onDoubleTapReset = {
                        onChange(
                            SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_STRENGTH, grade)
                        )
                    },
                    onDragActiveChange = onDragActiveChange,
                )
                // Zoom limit 1.0..1.5 (default 1.1): the auto-crop ceiling
                // that hides the warped-in border.
                GradeSlider(
                    label = stringResource(R.string.editor_slider_stabilization_zoom_limit),
                    value = grade.stabilizationZoomLimit,
                    valueRange = 1.0f..1.5f,
                    step = SliderSteps.STABILIZATION_ZOOM_STEP,
                    valueLabel = Format.formatZoom(grade.stabilizationZoomLimit),
                    onChange = { v ->
                        onChange(
                            grade.copy(
                                stabilizationZoomLimit = SliderSteps.snapStabilizationZoomLimit(v),
                            )
                        )
                    },
                    onDoubleTapReset = {
                        onChange(
                            SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_ZOOM_LIMIT, grade)
                        )
                    },
                    onDragActiveChange = onDragActiveChange,
                )
                // Sync status line + the gyro log row: the honest verdict
                // (never fabricated) with the attach/remove actions.
                Text(
                    stringResource(R.string.editor_stabilization_sync_status, statusText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (gyroAttached) {
                    if (gyroName != null) {
                        Text(
                            stringResource(R.string.editor_stabilization_gyro_name, gyroName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onRemoveGyro) {
                        Text(stringResource(R.string.editor_stabilization_remove_gyro))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAttachGyro,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.editor_stabilization_attach_gyro))
                    }
                }
                if (gyroImportError != null) {
                    Text(
                        gyroImportError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Sync offset trim ±500 ms (10 ms steps): the manual sync
                // control. The trim ADDS to the probe's offset (keeps the
                // Anchored verdict, widens the bound by |trim| — the core's
                // trimOffsetMs). Shown once a log is attached: without a
                // log there is no offset to trim.
                if (gyroAttached) {
                    GradeSlider(
                        label = stringResource(R.string.editor_slider_stabilization_trim),
                        value = grade.stabilizationTrimMs,
                        valueRange = -500f..500f,
                        step = SliderSteps.STABILIZATION_TRIM_STEP_MS,
                        valueLabel = Format.formatSignedMs(grade.stabilizationTrimMs.toInt()),
                        onChange = { v ->
                            onChange(
                                grade.copy(stabilizationTrimMs = SliderSteps.snapStabilizationTrimMs(v))
                            )
                        },
                        onDoubleTapReset = {
                            onChange(
                                SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_TRIM_MS, grade)
                            )
                        },
                        onDragActiveChange = onDragActiveChange,
                    )
                }
                // Focal length (35mm equiv): the container's readout as the
                // reference line, the editable override below it. The pixel
                // focal is NOT derivable from the container alone (no
                // sensor pitch), so the honest "intrinsics assumed" status
                // shows whenever the warp runs on this conversion.
                if (containerFocalMm > 0f) {
                    Text(
                        stringResource(
                            R.string.editor_stabilization_focal_from_container,
                            Format.formatMm(containerFocalMm),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GradeSlider(
                    label = stringResource(R.string.editor_slider_stabilization_focal),
                    // 0 = use the container: the slider shows the effective
                    // value (container when unset), so the row never reads
                    // as a dead 0.
                    value = EditorPreviewLogic.effectiveFocalMm(
                        grade.stabilizationFocalMm, containerFocalMm,
                    ).coerceIn(10f, 200f),
                    valueRange = 10f..200f,
                    step = SliderSteps.STABILIZATION_FOCAL_STEP_MM,
                    valueLabel = Format.formatMm(
                        EditorPreviewLogic.effectiveFocalMm(
                            grade.stabilizationFocalMm, containerFocalMm,
                        ).coerceIn(10f, 200f),
                    ),
                    onChange = { v ->
                        onChange(
                            grade.copy(
                                stabilizationFocalMm = SliderSteps.snapStabilizationFocalMm(v),
                            )
                        )
                    },
                    onDoubleTapReset = {
                        onChange(
                            SliderSteps.resetToDefault(SliderSteps.Axis.STABILIZATION_FOCAL_MM, grade)
                        )
                    },
                    onDragActiveChange = onDragActiveChange,
                )
                Text(
                    stringResource(R.string.editor_stabilization_intrinsics_assumed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
 * One stabilization mode segmented-button entry (gyro Phase 5): label +
 * the GradeK mode int (0 Smooth, 1 Lock First Frame, 2 Horizon Lock).
 */
private data class StabModeOption(
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
