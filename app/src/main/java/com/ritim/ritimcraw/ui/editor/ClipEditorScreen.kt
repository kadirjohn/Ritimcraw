package com.ritim.ritimcraw.ui.editor

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradePreset
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.engine.DisplayRateApply
import com.ritim.ritimcraw.engine.PlaybackTrace
import com.ritim.ritimcraw.engine.PreviewErrors
import com.ritim.ritimcraw.engine.ImageStabActivity
import com.ritim.ritimcraw.engine.ImageStabLoads
import com.ritim.ritimcraw.engine.StabilizationGate
import com.ritim.ritimcraw.engine.WarpResolver
import com.ritim.ritimcraw.engine.EditorAudioPlayback
import com.ritim.ritimcraw.engine.EditorPreviewSession
import com.ritim.ritimcraw.engine.EncodedPreviewSession
import com.ritim.ritimcraw.engine.BackendGates
import com.ritim.ritimcraw.engine.BackendRules
import com.ritim.ritimcraw.engine.GlesParityResult
import com.ritim.ritimcraw.engine.GlesProbeResult
import com.ritim.ritimcraw.engine.GlesShaderSources
import com.ritim.ritimcraw.engine.GradeMapping
import com.ritim.ritimcraw.engine.GradeParcel
import com.ritim.ritimcraw.engine.GradeV2Usage
import com.ritim.ritimcraw.engine.GpuCropMap
import com.ritim.ritimcraw.engine.GpuParityRules
import com.ritim.ritimcraw.engine.GpuPreviewSession
import com.ritim.ritimcraw.engine.NativeGpuPreviewDevelop
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.engine.NodeChainWire
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.engine.PreviewFrame
import com.ritim.ritimcraw.engine.PreviewRenderer
import com.ritim.ritimcraw.engine.PreviewSessions
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.engine.PreviewStatsMode
import com.ritim.ritimcraw.engine.PreviewTiers
import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.engine.SourcePath
import com.ritim.ritimcraw.engine.SourcePathDecision
import com.ritim.ritimcraw.engine.PtsTimeline
import com.ritim.ritimcraw.engine.RenderBackend
import com.ritim.ritimcraw.engine.RenderGate
import com.ritim.ritimcraw.engine.RenderRequest
import com.ritim.ritimcraw.engine.PreviewTiers.RenderClass
import com.ritim.ritimcraw.engine.SliderTiers
import com.ritim.ritimcraw.engine.RenderDebounce
import com.ritim.ritimcraw.engine.RenderTrace
import com.ritim.ritimcraw.engine.keepsLastPublishedFrame
import com.ritim.ritimcraw.engine.statsModeFor
import com.ritim.ritimcraw.ui.editor.PublishCrop
import com.ritim.ritimcraw.ui.components.CompactSegmentedRow
import com.ritim.ritimcraw.ui.theme.RitimTheme
import com.ritim.ritimcraw.ui.theme.Tnum
import com.ritim.ritimcraw.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The scope compute pacing window (owner spec PLAY-10, 2026-11-12 §7:
 * "~10 scope updates/sec during playback, tuned with measured cost"):
 * at most one compute per window, always on the newest published frame
 * (see the scope compute effect). The measurement tooling — the strip's
 * Hz readout and the "ritim-scope" logcat line — exists to validate
 * this number on a device.
 */
private const val ScopeComputePeriodMs = 100L

/**
 * The scope's trailing-1s compute-rate meter — the Hz readout's source
 * (see the scope compute effect). A plain class, no Compose: the screen
 * holds ONE per clip (remember keyed to it) so the history survives the
 * compute effect's restarts — a mode or sampling switch must not zero
 * the readout — while the effect's pacing anchor deliberately DOES
 * reset, so a switch recomputes the current frame immediately.
 */
private class ScopeRateMeter {
    private val computeAt = ArrayDeque<Long>()

    /** Records a compute at [nowMs]; returns the trailing-1s count. */
    fun record(nowMs: Long): Int {
        computeAt.addLast(nowMs)
        return prune(nowMs)
    }

    /** The trailing-1s count without recording (the decay ticker). */
    fun rate(nowMs: Long): Int = prune(nowMs)

    private fun prune(nowMs: Long): Int {
        while (computeAt.isNotEmpty() && nowMs - computeAt.first() > 1_000L) {
            computeAt.removeFirst()
        }
        return computeAt.size
    }
}

/**
 * Pure main-category structure of the editor panel (2026-11-13 editor
 * panel segmentation plan), JVM-testable without Compose: the four
 * category indices the bottom navigation, the landscape rail and the
 * panel region dispatch all key on — ONE definition so the three can
 * never fork. The Color sub-tab rules (Balance/Wheels/Look) live here
 * too; Frame's Crop/Stabilize pair extends this object in the follow-up
 * commit of the plan.
 *
 * The panel's category Int is UI state, not data: the indices are the
 * nav's item order, and the panel region's `when (tab)` uses the same
 * constants. Keeping them here (with a test locking the count and the
 * order) means a future fifth category must touch this object — the
 * nav, the rail and the scroll-state map are then compiler-driven to
 * follow.
 */
object EditorCategories {

    /** Light: exposure and tone sliders. */
    const val LIGHT = 0

    /** Color: WB, wheels, look (sub-tabs arrive with commit b). */
    const val COLOR = 1

    /** Detail: noise reduction and texture. */
    const val DETAIL = 2

    /** Frame: crop and stabilization (sub-tabs arrive with commit c). */
    const val FRAME = 3

    /** The main categories in navigation order; the nav/rail count this. */
    val ALL = listOf(LIGHT, COLOR, DETAIL, FRAME)

    /**
     * True when [category] is one of the four main categories — the
     * panel region's exhaustive dispatch and any future category-source
     * guard (a saveable Int restored after a version change could be
     * anything) validate through this instead of repeating the range.
     */
    fun isMainCategory(category: Int): Boolean = category in ALL

    // ---- Color sub-tabs (plan: SecondaryTabRow, text-first) ----

    /** Balance: WB (As Shot, temp, tint) + saturation. */
    const val COLOR_BALANCE = 0

    /** Wheels: the 3-band color wheel + split tone (MCRAW only). */
    const val COLOR_WHEELS = 1

    /** Look: Tone Mapping (+ AgX Look) and the LUT section. */
    const val COLOR_LOOK = 2

    /** The Color sub-tabs in row order; the SecondaryTabRow counts this. */
    /** Nodes (F1-8a): the v2 node chain editor; both clip kinds (the node chain runs on the CPU oracle and the GPU door). */
    const val COLOR_NODES = 3

    val COLOR_SUBTABS = listOf(COLOR_BALANCE, COLOR_WHEELS, COLOR_LOOK, COLOR_NODES)

    /**
     * The Color sub-tabs that exist for THIS clip: Wheels is MCRAW-only
     * (the showColorTools gate — the encoded path takes the same Grade
     * but ignores the color-package fields, so the wheel would be dead
     * controls), Balance and Look run on both clip kinds. The row
     * composes from this list so the visible tabs are exactly the
     * usable ones, and [sanitizeColorSubTab] keeps the saved selection
     * inside it.
     */
    fun visibleColorSubTabs(showColorTools: Boolean): List<Int> =
        if (showColorTools) COLOR_SUBTABS else listOf(COLOR_BALANCE, COLOR_LOOK, COLOR_NODES)

    /**
     * The sub-tab a restored/persisted Color selection resolves to:
     * itself when it is one of the visible sub-tabs, else the first
     * visible one. A saveable Int can be anything after a version
     * change (or a clip-kind switch that gates Wheels out from under a
     * Wheels selection), and the row's selected index must stay a valid
     * member of the visible list — never an IndexOutOfBounds, never a
     * selected tab the row did not compose.
     */
    fun sanitizeColorSubTab(subTab: Int, showColorTools: Boolean): Int {
        val visible = visibleColorSubTabs(showColorTools)
        return if (subTab in visible) subTab else visible.first()
    }

    // ---- Frame sub-tabs (plan: SecondaryTabRow, text-first) ----

    /** Crop: aspect, straighten, crop fractions (future: rotation). */
    const val FRAME_CROP = 0

    /**
     * Stabilize: toggle, mode, strength, zoom limit, with the
     * gyro/sync/probe details expandable (plan). MCRAW-only — the
     * stabilization group keeps the showStabilization gate it carries
     * today in Detail; the tab follows the group, not the other way
     * around.
     */
    const val FRAME_STABILIZE = 1

    /** The Frame sub-tabs in row order; the SecondaryTabRow counts this. */
    val FRAME_SUBTABS = listOf(FRAME_CROP, FRAME_STABILIZE)

    /**
     * The Frame sub-tabs a clip kind can use: stabilization needs the
     * gyro track only an MCRAW source carries (showStabilization), so
     * the encoded path sees Crop alone — the same drop-the-dead-segment
     * rule as the Color row's Wheels. The screen goes one step further
     * and skips the pinned row entirely when this list has a single
     * entry: a one-tab row is chrome with nothing to choose (the row
     * exists to switch between segments, and there is no switch to
     * make).
     */
    fun visibleFrameSubTabs(showStabilization: Boolean): List<Int> =
        if (showStabilization) FRAME_SUBTABS else listOf(FRAME_CROP)

    /**
     * The sub-tab a restored/persisted Frame selection resolves to:
     * itself when the clip kind still shows it, else the first visible
     * (Crop on the encoded path). A saveable Int can be anything after
     * a version change; the row's selected index must stay a valid
     * member of the composed list — never an IndexOutOfBounds, never a
     * selected tab the row did not compose. The Color sanitizer's rule
     * with Frame's gate.
     */
    fun sanitizeFrameSubTab(subTab: Int, showStabilization: Boolean): Int {
        val visible = visibleFrameSubTabs(showStabilization)
        return if (subTab in visible) subTab else visible.first()
    }
}

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
     *
     * gpuPreparing (owner finding 2026-11-14) covers the GPU session-open
     * window: true from the session-open request until its first frame or
     * its fallback verdict. On a fresh 12MP clip that window is
     * seconds-class (probe + parity + open + first develop), and before
     * this input it read as IDLE — the "No preview yet" text over a
     * spinner, which reads broken when the editor is in fact preparing.
     * PREPARING shows only when there is no frame to keep (a stale frame
     * keeps showing); a CPU render in flight and a real render failure
     * both outrank it — the existing states keep their behavior
     * byte-identical.
     */
    fun stateOf(
        renderRequested: Boolean,
        renderInFlight: Boolean,
        hasFrame: Boolean,
        lastRenderFailed: Boolean,
        gpuPreparing: Boolean = false,
    ): PreviewState = when {
        renderInFlight -> PreviewState.RENDERING
        hasFrame -> PreviewState.FRAME
        renderRequested && lastRenderFailed -> PreviewState.FAILED
        gpuPreparing -> PreviewState.PREPARING
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
     * True when the color wheel + split tone section (COL-13) belongs in
     * the Color tab. Both stages (18 wheel, 19 split tone) are MCRAW-only
     * tone-band color math: the encoded (Mi-Log MP4) path takes the same
     * Grade but the bridge ignores the color-package fields there (the
     * encoded grades via its wb/temp/tint/exposure loop), so the section
     * is hidden rather than showing dead controls. A separate named
     * decision from [showTemporalNr] so the two can diverge if the
     * encoded path ever grows its own wheel stages.
     */
    fun showColorTools(path: SourcePath): Boolean = path == SourcePath.MCRAW

    /**
     * True when the preview's crop overlay (owner request 2026-11-13 §2)
     * should compose: the Frame tab is the active tab AND its sanitized
     * sub-tab is Crop. Pure so a JVM test can lock the gate — the screen
     * reads the SAME sanitized value the FramePanel call site reads, so
     * the overlay and the panel body can never disagree about which
     * segment is open (a stale saved Stabilize selection on an encoded
     * clip resolves to Crop here too, exactly like the panel).
     */
    fun cropOverlayVisible(tab: Int, subTab: Int, showStabilization: Boolean): Boolean =
        tab == EditorCategories.FRAME &&
            EditorCategories.sanitizeFrameSubTab(subTab, showStabilization) ==
                EditorCategories.FRAME_CROP

    /**
     * The display grade the preview renders while the crop overlay is
     * active (owner request 2026-11-13 §2): the REAL grade with the crop
     * group neutralized — full rect, FREE aspect, zero straighten. The
     * publish fold crops before display, so an honest overlay needs the
     * UNCROPPED image behind it; the overlay itself draws the REAL
     * grade's rect, and leaving the sub-tab restores the normal render
     * (the grade itself is never touched — display-only, by
     * construction). Straighten is neutralized too: an axis-aligned
     * rect over straightened (auto-zoomed) content would sit in a
     * different pixel space than the rect it edits.
     */
    fun displayGradeForCropOverlay(grade: GradeK): GradeK = grade.copy(
        cropAspect = GradeK().cropAspect,
        cropX = GradeK().cropX,
        cropY = GradeK().cropY,
        cropW = GradeK().cropW,
        cropH = GradeK().cropH,
        straightenDeg = GradeK().straightenDeg,
    )

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
     * recvd anchor vs the log's header timestamp): Anchored, rendered
     * honestly as an APPROXIMATE estimate with the probe's [errorBoundMs]
     * as the ± bound (the anchor method is coarse; the receive lag is
     * unobservable — Sync.h). false = the anchors were absent: Manual —
     * the trim slider is the user's offset.
     *
     * [refined] (M5 commit b) true = the plan's estimate went through the
     * optical refinement and it SUCCEEDED (the probe's syncKind=2 — a
     * rejected refine keeps the anchored verdict, syncKind=1). REQUIRES
     * [anchored]: the refinement upgrades the anchored estimate (the
     * bridge only ever refines an Anchored confidence), so the refined
     * verdict only shows on top of the anchor foundation — a refined
     * flag without anchors is stale and ignored, never a verdict rescue.
     * The pure mapping's precedence: NO_DATA beats everything, REFINED
     * beats ANCHORED (both anchored-gated), ANCHORED beats MANUAL.
     */
    fun syncStatus(
        gyroAttached: Boolean,
        probeFailed: Boolean,
        anchored: Boolean,
        refined: Boolean,
    ): GyroSyncStatus = when {
        !gyroAttached || probeFailed -> GyroSyncStatus.NO_DATA
        anchored && refined -> GyroSyncStatus.REFINED
        anchored -> GyroSyncStatus.ANCHORED
        else -> GyroSyncStatus.MANUAL
    }

    /**
     * The status line's string resource for a sync verdict (M5 commit b
     * extracted this mapping so the JVM can lock it): REFINED and ANCHORED
     * carry the ± bound as the one format arg; MANUAL and NO_DATA carry
     * none. The composable supplies the args (the refined bound comes from
     * the probe's extended [5], the anchored bound from the attach-time
     * probe's [2]); this function owns ONLY the verdict → resource pair,
     * so a verdict can never render the wrong line's text.
     */
    fun syncStatusRes(status: GyroSyncStatus): Int = when (status) {
        GyroSyncStatus.REFINED -> R.string.editor_stabilization_sync_refined
        GyroSyncStatus.ANCHORED -> R.string.editor_stabilization_sync_anchored
        GyroSyncStatus.MANUAL -> R.string.editor_stabilization_sync_manual
        GyroSyncStatus.NO_DATA -> R.string.editor_stabilization_sync_no_data
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

    /**
     * Whether the GPU preview path may serve ONE render request (M4
     * commit b), as a pure decision the render pump applies per request.
     * The fragment pass only takes a frame when EVERY stage active in the
     * request's grade is covered — the export gate's rule
     * ([BackendRules.fragmentUncoveredStages] /
     * [fragmentUncoveredPreStages]), mirrored here for the preview, so an
     * uncovered grade takes the CPU path wholesale in the editor exactly
     * like it does in the export.
     *
     * The gyro warp contributes NO rule since the gyro-warp port: the
     * warp pass is unconditionally fragment-covered, the pump resolves
     * the per-frame plan at the tier's maxDim and threads the verdict
     * into the GPU render, and warpActive=false skips the draw entirely
     * (the identity no-draw rule). A gyro-stabilized grade stays
     * GPU-eligible like any other covered grade.
     *
     * The temporal NR contributes NO rule since the temporal hybrid:
     * the bridge's own temporalMergedCfa runs the merge before the CFA
     * reduction, so a temporal-active grade rides the GPU preview like
     * any other covered grade — with the DN-08 tier fold threaded at
     * the render call (the CPU preview's own gate, mirrored), so
     * playback and drag renders skip the merge and the settle render
     * lands it.
     *
     * Since A2 (owner request 2026-11-23, guide A2) an ACTIVE crop/
     * straighten is GPU-ELIGIBLE: the window pass maps the crop +
     * straighten itself (WINDOW_FRAGMENT's uCrop* uniforms — the
     * mapping single-sourced in CropState/GpuCropMap.requestCrop, the
     * manual delta mapRect'ed before the seam), the aspect-fit rect and
     * the stats dims use the CROPPED output dims (the scopes analyse the
     * cropped region, the CPU path's own shape), and the publish fold
     * serves the published pack AS-IS (PublishCrop must not crop it
     * again — guide A2). The pre-A2 rule (any active crop forces the
     * whole preview to the CPU, because the GPU window drew uncropped)
     * is gone. Crop-overlay mode stays exactly as it was already:
     * [displayGradeForCropOverlay] swaps in the crop-neutral display
     * grade, so overlay frames render uncropped and the handles stay
     * aligned with the uncropped window (the guide's crop-overlay rule,
     * unchanged by A2).
     *
     * Zebra contributes NO rule since the A3 port (owner request
     * 2026-11-23, guide §A3): the GPU window pass paints the stripes
     * itself (WINDOW_FRAGMENT's uZebra* uniforms — the values
     * single-sourced from ZebraOverlay/FrameHistogram across the seam),
     * the stats pack stays clean (the session's draw-3 uZebraOn=0 pin),
     * and the CPU backend keeps ZebraOverlay.paint for its own path. The
     * pre-A3 rule (zebra forced the CPU path because zebraFrame derived
     * from the blocky 256-px pack) is gone — a zebra-on request rides
     * the GPU like any covered grade, at window resolution.
     *
     * [lutTexturePathOk] (commit b) is the probe-derived runtime fact for
     * stage 24's runtime leg — the RGBA16F 3D-texture path is GLES 3.0
     * core, so the fact is contextOk && glesMajor >= 3. The pump latches
     * it once at the capability gate and hands every request the same
     * latched value; false (the default, also a device with no GLES 3)
     * keeps a LUT-active grade on the CPU path — the same honest
     * fallback the export gate rides.
     *
     * Pure and JVM-testable; the pump supplies the per-request facts and
     * latches GPU-off when the session itself fails (the M3 per-frame
     * dispatch rule — that latch lives in the pump, not here).
     */
    fun gpuPreviewEligible(
        grade: GradeK,
        lutTexturePathOk: Boolean = false,
    ): Boolean {
        // A2 (2026-11-23, guide A2): the crop no longer forces CPU — the
        // window pass maps it (the KDoc block above). A3 (2026-11-23,
        // guide A3): the zebra no longer forces CPU either — the window
        // pass paints the stripes (the KDoc block above).
        if (BackendRules.fragmentUncoveredStages(grade, lutTexturePathOk).isNotEmpty()) return false
        if (BackendRules.fragmentUncoveredPreStages(grade).isNotEmpty()) return false
        return true
    }

    // ---- The preview capability gate (M4 commit c) ----

    /**
     * Whether the probe's verdicts justify paying the parity leg (the M3
     * export gate's order, mirrored for the preview): the parity run is
     * paid only when the probe created a context AND the float-FBO
     * extension is present — a red gate never pays the parity (it would
     * only produce a predictable failure). A compute-less device still
     * pays: the fragment chain is a DRAW pipeline and needs no compute.
     *
     * Pure; a malformed probe array throws (the fromArray contract — a
     * contract drift must be loud, never papered over) and the caller's
     * gate block catches it into the honest all-false gates.
     */
    fun probePaysParity(probeRaw: DoubleArray?): Boolean {
        if (probeRaw == null) return false
        val probe = GlesProbeResult.fromArray(probeRaw)
        return probe.contextOk && probe.floatFboOk
    }

    /**
     * The preview capability gate's assembly (M4 commit c) — the M3
     * export gate (Mp4Exporter's develop dispatch), mirrored for the
     * editor's session open as a pure function so the JVM suite locks
     * the exact assembly without a device. The screen supplies the RAW
     * seam arrays ([probeRaw] from GpuPreviewDevelop.probe, [parityRaw]
     * from GpuPreviewDevelop.fragmentParity — null is the seam's honest
     * no-result: unavailable guard, engine down, or a JNI failure) and
     * the parity detail for the NOT_RUN verdict's reason.
     *
     * The M3 assembly, verbatim in shape:
     * - probe null -> the all-false default gates (the conservative
     *   direction; the M3 catch shape).
     * - a probe that ran but missed the context/float-FBO gates reports
     *   its own partial gates — the parity leg was never paid, so
     *   parityOk stays false.
     * - a probe with both gates green assembles the FULL gates:
     *   parityOk filled DIRECTLY from [GpuParityRules.verdict] — NEVER
     *   GpuParityRules.gatesFrom, which keeps parityOk false on purpose
     *   (parity comes only from a real run).
     * - a malformed array throws (the fromArray contract: loud, never
     *   papered over); the caller's gate block catches Throwable into
     *   the all-false gates, so a contract drift costs the GPU path,
     *   never the preview.
     */
    fun previewCapabilityGates(
        probeRaw: DoubleArray?,
        parityRaw: DoubleArray?,
        parityDetail: String?,
    ): BackendGates {
        if (probeRaw == null) return BackendGates()
        val probe = GlesProbeResult.fromArray(probeRaw)
        if (!probe.contextOk || !probe.floatFboOk) {
            // Parity is skipped when the context/float-FBO gates are red
            // (running it would only produce a predictable failure) —
            // parityOk stays false (the M3 shape).
            return BackendGates(
                contextOk = probe.contextOk,
                computeOk = probe.computeOk,
                floatFboOk = probe.floatFboOk,
            )
        }
        val parity = parityRaw?.let { GlesParityResult.fromArray(it) }
        return BackendGates(
            contextOk = true,
            computeOk = probe.computeOk,
            floatFboOk = true,
            parityOk = parity != null &&
                GpuParityRules.runtimeVerdict(parity, parityDetail).pass,
        )
    }

    /**
     * The fallback line the preview logs when the GPU path is not taken —
     * the M3 export's developFallbackLine format, verbatim, so ONE grep
     * covers the export and the preview logs. Pure; the JVM tests pin the
     * exact strings (the AUTO policy leg and the gate-failure leg).
     */
    fun backendFallbackLine(requested: RenderBackend, reason: String): String =
        "render backend fallback: requested=${requested.name} " +
            "effective=CPU reason=$reason"

    // ---- Zoom-limit honesty readout (audit fix 2026-11-12, Fix 5) ----

    // The native zoom probe's statusInt values: the core's StabilizationStatus
    // enum order (Stabilization.h), mirrored here because the probe ships
    // them as plain ints. The warp set is Ok / IntrinsicsAssumed / LowOverlap
    // (the bridge's stabWarps); Off / NoImu / Unsynced refuse and carry no
    // honest zoom to show.
    const val STAB_STATUS_OK = 0
    const val STAB_STATUS_OFF = 1
    const val STAB_STATUS_NO_IMU = 2
    const val STAB_STATUS_UNSYNCED = 3
    const val STAB_STATUS_LOW_OVERLAP = 4
    const val STAB_STATUS_INTRINSICS_ASSUMED = 5

    // The M5 sync verdict's kind ints (the probe's extended [3]): the
    // bridge's stabSyncKindName order, mirrored here for the same reason
    // STAB_STATUS_* is mirrored — the probe ships plain doubles and the
    // status-line decision must never guess at a magic number.
    const val SYNC_KIND_MANUAL = 0
    const val SYNC_KIND_ANCHORED = 1
    const val SYNC_KIND_REFINED = 2

    /**
     * The sync-kind token of the "ritim-stab-timing" line (M5 commit b):
     * the EXACT spellings the append-only ` sync=%s offsetMs=%.3f` tail
     * writes — the tokens the owner's ON/OFF A/B greps for (sync=refined
     * vs sync=anchored). Pure Kotlin mirror of the bridge's
     * stabSyncKindName so the JVM can lock the contract: [kind] is the
     * probe's [3] value; null = unknown kind (forward compat — a future
     * bridge verdict reads as no token rather than a guess, and the test
     * keeps the three spellings frozen against a silent rename).
     */
    fun syncToken(kind: Int): String? = when (kind) {
        SYNC_KIND_MANUAL -> "manual"
        SYNC_KIND_ANCHORED -> "anchored"
        SYNC_KIND_REFINED -> "refined"
        else -> null
    }

    /**
     * The zoom-limit honesty verdict (audit fix 2026-11-12, Fix 5): which
     * readout the probe's clip-wide numbers support — the APPLIED crop
     * when the user's limit covers the need, or LIMITED when the plan's
     * required zoom was clamped down to it. Pure so a JVM test locks the
     * mapping; the decision reads the probe's raw numbers, never a UI
     * string.
     *
     * [requiredZoom] is the plan's unclamped static-crop need and
     * [appliedZoom] its value after the zoomLimit clamp (the core computes
     * applied = min(required, limit), so required > applied EXACTLY when
     * the limit bit — no epsilon needed, both sides are the same doubles
     * the probe shipped). The readout shows [appliedZoom] either way: that
     * is the crop the frames actually pay.
     *
     * Null = no verdict: the status is one of the refusal set (Off / NoImu
     * / Unsynced — the warp does not run, so no zoom is shown, never a
     * fabricated "crop 1.00x") or an unknown status int (forward compat —
     * a future core status reads as no readout until this mirror learns
     * it, never a guess).
     */
    fun stabZoomVerdict(
        statusInt: Int,
        requiredZoom: Double,
        appliedZoom: Double,
    ): StabZoomVerdict? = when (statusInt) {
        STAB_STATUS_OK, STAB_STATUS_INTRINSICS_ASSUMED, STAB_STATUS_LOW_OVERLAP ->
            if (requiredZoom > appliedZoom) StabZoomVerdict.LIMITED
            else StabZoomVerdict.APPLIED
        else -> null
    }
}

/**
 * The Stabilization group's sync-status verdict (gyro Phase 5): the pure
 * result of [EditorPreviewLogic.syncStatus], rendered by the group as the
 * localized status line (Refined (optical ±X ms) / Approximate (anchored
 * ±N ms) / Manual offset / No gyro data). REFINED (M5 commit b) is the
 * optical refinement's success verdict — the same anchor-based estimate,
 * now with the measured curve's bound.
 */
enum class GyroSyncStatus { ANCHORED, REFINED, MANUAL, NO_DATA }

/**
 * The Stabilization group's zoom-limit honesty verdict (audit fix
 * 2026-11-12, Fix 5): the pure result of
 * [EditorPreviewLogic.stabZoomVerdict], rendered by the group as the
 * zoom readout beside the sync status line — APPLIED shows the effective
 * crop ("crop 1.05x"), LIMITED shows the limit-clamped crop ("limited to
 * 1.10x") so the user SEES when their zoom limit is cutting the warp's
 * reach. Null from the pure function renders no readout at all (refusal
 * statuses warp nothing — an invented "crop 1.00x" would lie).
 */
enum class StabZoomVerdict { APPLIED, LIMITED }

/**
 * The preview area's state (the pure result of
 * [EditorPreviewLogic.stateOf]): IDLE (nothing requested — the "No preview
 * yet" placeholder), RENDERING (a CPU render in flight — the spinner),
 * FRAME (an image on screen), FAILED (a render failed with nothing to
 * show — the error text), PREPARING (the GPU session-open window before
 * its first frame — spinner + "Preparing preview…", never the idle text).
 */
enum class PreviewState { IDLE, RENDERING, FRAME, FAILED, PREPARING }

/**
 * Where one system back press lands while the editor is open: the pure
 * result of [EditorPreviewLogic.onBack], branched on by the screen's
 * always-active BackHandler.
 */
enum class EditorBackDestination { EXIT_FULLSCREEN, LEAVE_EDITOR }

/**
 * Clip editor (DESIGN.md Editor): preview frame on
 * surface-container-lowest with a subtle outline border, Light/Color/
 * Detail/Frame main tabs (the four-category structure of the 2026-11-13
 * editor panel segmentation plan) — Light tab with Exposure / Highlights /
 * Black Point / White Point / Shadows / Midtones / Contrast, Color tab
 * behind its Balance | Wheels | Look sub-tab row (Balance: Temperature /
 * Tint / Saturation (tone stages COL-11/12/13, end to end) plus the RAW
 * metadata controls (RAW-07/COL-09/RAW-04/RAW-06): As Shot WB reset,
 * Highlight Reconstruction switch, Keep Vignetting switch; Wheels —
 * MCRAW-only, the encoded path drops the tab: the three-band wheel pad
 * with Shadows / Midtones / Highlights tabs, split shadow/highlight hues
 * and the Balance slider (COL-13); Look: the Tonemapping choice Off /
 * ACES / AgX (COL-16) as M3 segmented buttons plus the AgX look, and the
 * LUT section (COL-07) as its own separate section: the imported .cube
 * library as choice rows plus an Import button and the Strength slider
 * under the selected cube), Detail tab with two section headers — Noise
 * Reduction (luma NR, chroma denoise, and on MCRAW clips the Temporal NR
 * toggle with its Strength slider (DN-05..DN-09)) and Texture (Sharpness
 * / Detail sliders (COL-15)), and the Frame tab behind its Crop |
 * Stabilize sub-tab row (Crop: aspect, straighten and the rect sliders
 * (RAW-09); Stabilize — MCRAW-only: the gyro warp group with its
 * expandable gyro/sync/probe details section).
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
    // F1-6 (color model v2): the clip's schema-2 grade record reader. The
    // editor's own state stays GradeK (every legacy control edits through
    // it); the RENDER sites ask this seam for the clip's CURRENT v2
    // record so a NODES/DRT grade's chain rides the request. Default
    // null keeps every existing caller working (a v1-grade project: the
    // null chainWire is the legacy fast path, bit-identical — K2).
    gradeV2For: () -> com.ritim.ritimcraw.data.grade2.ClipGradeV2? = { null },
    /** F1-8a: the clip's stored node chain, and the write of an edited chain (persist; the screen re-renders). */
    nodesFor: () -> List<com.ritim.ritimcraw.data.grade2.GradeNodeV2> = { emptyList() },
    onNodesChange: (List<com.ritim.ritimcraw.data.grade2.GradeNodeV2>) -> Unit = {},
    // (Export resolution, owner request 2026-11-13) widened by the
    // resolution pair the export sheet's chip selected: null pair =
    // Original = the source's effective size, exactly what every export
    // before the picker produced. DNG/LINEAR taps pass nulls (no
    // resolution seam); the MP4 tap carries the selected bound.
    onExport: (Target, Int?, Int?, com.ritim.ritimcraw.export.ExportSettings?) -> Unit = { _, _, _, _ -> },
    // (E1c) the device's last measured export speed, for the export sheet's time estimate.
    exportRate: com.ritim.ritimcraw.export.ExportSheetModel.Rate? = null,
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
    // Imports the picked SAF document under the user-confirmed name and
    // interpretation (AppNav: LutStore.import + the native probe as
    // VALIDATION — the confirmed name stands, the pre-dialog TITLE rename
    // is superseded by the sheet's Name field). Returns the entry on
    // success or the failure for the local error line; never throws.
    onImportLut: (uri: String, name: String, interp: LutInterpK) -> Result<LutInfo> =
        { _, _, _ -> Result.failure(IllegalStateException()) },
    // "Edit interpretation" on a library record (AppNav: setTitle when the
    // confirmed name differs, then LutStore.bindInterp — a NEW binding
    // revision on the same blob, never a mutation, never a re-import).
    onEditLutInterp: (lut: LutInfo, name: String, interp: LutInterpK) -> Unit =
        { _, _, _ -> },
    // Removes ONE binding — the record — from the library (AppNav:
    // LutStore.removeBinding; the copied cube file goes only when it was
    // the blob's last record).
    onRemoveLut: (LutInfo) -> Unit = {},
    // The grade's lutId -> the copied cube's path (AppNav: LutStore.pathFor).
    // Null = no LUT: stage 24 is bypassed and the old look is preserved.
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
    // AUD-02: the source's CURRENT audio link state (AppNav reads it from
    // the AudioStore per clip — AudioProject.link's absent-record default
    // is ATTACHED). Gates the audio transport: only ATTACHED starts the
    // audio; DETACHED/REMOVED/LINKED_EDITED play silent, with the state
    // shown honestly in the transport row. The default keeps every
    // existing caller byte-identical (audio always on, no chip).
    audioLinkState: AudioLinkState = AudioLinkState.ATTACHED,
    // The preview render backend setting (M4 commit b): GPU_EXPERIMENTAL
    // routes eligible render requests through the GPU preview session
    // (the SurfaceView window draw + the published stats pack); CPU stays
    // the default and every ineligible request — an uncovered grade, an
    // encoded clip, a failed session — takes the existing CPU path
    // byte-identical (since A2 an active crop and since A3 zebra-on are
    // eligible; the window pass maps/paints them). AUTO is a commit-c
    // concern (the capability gate); until then it reads as CPU here.
    renderBackend: RenderBackend = RenderBackend.CPU,
    // A1 async stats (owner request 2026-11-23, guide A1): the debug
    // Settings row's persisted stats-readback state (stores.previewStats),
    // read fresh at editor composition — the Settings screen is a
    // different screen, so the value is snapshotted here rather than
    // observed. The NATIVE gPreviewStatsEnabled atomic remains the
    // authoritative fold either way (the render door folds it); this
    // parameter only keeps the Kotlin-side statsModeFor decision honest.
    // Default true keeps every other caller byte-identical.
    previewStatsEnabled: Boolean = true,
) {
    RitimTheme(editor = true) {
        var tab by rememberSaveable { mutableStateOf(0) }
        // ---- Draggable panel state (owner request 2026-11-11d §10-11) ----
        // The panel's vertical state is saveable (§11.6: survives
        // configuration changes like the tab does) and the drag state is
        // remembered ONCE with the restored value as its initial anchor
        // target — a rotation keeps the panel exactly where it was, and
        // the offset is never recreated (§18: no reset, no reprocessing).
        // The FRESH-OPEN default is the LARGEST state (owner feedback
        // 2026-11-13 "default en yüksek boyda açılsın" — PanelBehavior.
        // DEFAULT_OPEN, JVM-tested); a restored saveable keeps whatever
        // the user last settled on, as before.
        // First open only (a saved state wins): portrait result opens NORMAL, landscape EXPANDED.
        var panelState by rememberSaveable {
            mutableStateOf(PanelBehavior.defaultOpenFor(sourceWidth, sourceHeight, manualRotationDeg))
        }
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
        // The Frame tab's own scroll state (2026-11-13 segmentation plan):
        // the category-level fallback the tab uses when it has NO sub-tab
        // row (the encoded path's Crop-only body) — on the MCRAW path the
        // activePanelScrollState resolution prefers the ACTIVE sub-tab's
        // state (frameSubTabScrollStates below), so Crop and Stabilize each
        // keep their own offset like Balance/Wheels/Look do.
        val frameScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val categoryScrollStates = mapOf(
            EditorCategories.LIGHT to lightScroll,
            EditorCategories.COLOR to colorScroll,
            EditorCategories.DETAIL to detailScroll,
            EditorCategories.FRAME to frameScroll,
        )
        // ---- Per-main-tab sub-tab state (2026-11-13 segmentation plan) ----
        // Each main tab remembers its last sub-tab (plan: "each main tab
        // remembers its last sub-tab, rememberSaveable, category-keyed"),
        // and each sub-tab BODY gets its own scroll state — the
        // per-category rule carried one level down: switching sub-tab
        // restores that sub-tab's own offset, and switching back to the
        // main tab later preserves it (panel close/open included — the
        // states live at screen level, so the landscape panel-hide,
        // which removes the sheet from composition entirely, keeps them
        // too). Lived at SCREEN level, not inside the panels: only one
        // panel composes at a time, so a rememberSaveable inside
        // ColorPanel would reset on every main-tab switch.
        var colorSubTab by rememberSaveable { mutableStateOf(EditorCategories.COLOR_BALANCE) }
        val balanceScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val wheelsScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val lookScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val nodesScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val colorSubTabScrollStates = mapOf(
            EditorCategories.COLOR_BALANCE to balanceScroll,
            EditorCategories.COLOR_WHEELS to wheelsScroll,
            EditorCategories.COLOR_LOOK to lookScroll,
            EditorCategories.COLOR_NODES to nodesScroll,
        )
        // The Frame tab's own sub-tab state, same shape as Color's: the
        // tab remembers its last segment (Crop | Stabilize) and each body
        // keeps its own scroll offset. frameScroll above stays the
        // category entry for symmetry with colorScroll — it is what a
        // hypothetical sub-tab-less Frame would scroll with, and the map
        // never throws on the FRAME key.
        var frameSubTab by rememberSaveable { mutableStateOf(EditorCategories.FRAME_CROP) }
        val frameCropScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val frameStabilizeScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        val frameSubTabScrollStates = mapOf(
            EditorCategories.FRAME_CROP to frameCropScroll,
            EditorCategories.FRAME_STABILIZE to frameStabilizeScroll,
        )
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        // The measured heights the anchor math needs (§11.3: "adapt to
        // the measured device size"). NONE of these depends on the
        // panel's own offset, so the anchors can never churn mid-drag
        // (the preview Box takes the flex space between them — it
        // flexes as the panel grows/shrinks, §11.7):
        //   free region = column height - top bar (56dp constant) -
        //                 transport height - nav height - scope strip
        //                 (the last one only while the strip is open)
        // The top bar is a fixed 56dp Row (EditorTopBar), so its px value
        // is a density conversion, not a measurement. Transport and nav
        // are measured via onSizeChanged because their heights vary with
        // font scale and precision-mode readout.
        var editorColumnHeight by remember { mutableStateOf(0) }
        var transportHeight by remember { mutableStateOf(0) }
        var navHeight by remember { mutableStateOf(0) }
        // AUD-02 honesty row height: measured ONLY while the row composes
        // (non-ATTACHED sources), zeroed otherwise — the scope strip's
        // zeroing rule (leaving composition fires no onSizeChanged, so a
        // hidden row must actively reset its measurement). ATTACHED clips
        // never compose the row, so their anchor math is untouched.
        var audioStateRowHeight by remember { mutableStateOf(0) }
        val audioStateRowHeightPx =
            if (audioLinkState != AudioLinkState.ATTACHED) audioStateRowHeight else 0
        // The preview region's measured width (owner spec 2026-11-12
        // section 2, the preview-claim math): the width a width-bound
        // video would fill. The preview Box is fillMaxWidth inside the
        // fixed-width editor column, so the value never depends on the
        // panel's own offset — the claim can never churn mid-drag.
        var previewRegionWidth by remember { mutableStateOf(0) }
        // ---- Scope strip state (owner spec PLAY-10, 2026-11-12 §1-2) ----
        // The ONE scope area is a strip below the transport, above the
        // panel (never an overlay on the image), carrying a Histogram |
        // Waveform content selector. The presentation is now a TRI-STATE
        // (owner request 2026-11-12 §2): OFF / STRIP / MINI — the open
        // flag became ScopePresentation, and every transition runs
        // through the pure ScopePresentationRules (JVM-tested): tap the
        // graph -> MINI, tap the mini -> STRIP, corner toggle OFF ->
        // STRIP and STRIP/MINI -> OFF. The presentation, the content
        // and the waveform mode are saveable like the tab and fullscreen
        // flags, so a rotation or process death keeps the user's scope
        // exactly as they left it; the results are keyed to the clip (a
        // switch starts empty). The presentation is NOT clip-keyed —
        // the strip's precedent (content/modes/sampling survive a clip
        // switch), while the RESULTS are clip-keyed, so a persisted MINI
        // never draws a previous clip's data. First open shows
        // Waveform · Luma (spec §1: "ilk açılışta Luma seçilsin"); the
        // histogram content's mode (RGB / Luma / Parade / RAW, feature
        // N6) is saveable here too — the RAW fetch is keyed to it. The
        // compute itself lives in the scope compute effect below the
        // render pump (spec §7).
        var scopePresentation by rememberSaveable { mutableStateOf(ScopePresentation.OFF) }
        var scopeContent by rememberSaveable { mutableStateOf(ScopeContent.WAVEFORM) }
        var waveformModeOrdinal by rememberSaveable { mutableStateOf(WaveformMode.LUMA.ordinal) }
        // The histogram content's own mode (feature N6): RGB / Luma /
        // Parade / RAW. Saveable like the waveform mode — and lifted
        // to the screen for the same reason (the waveform's
        // precedent): the RAW mode's PRE-DEVELOP sensor-domain stats
        // are fetched by the scope compute effect below, which keys
        // on the mode, so the choice must live where the compute is
        // keyed. The RAW stats themselves are keyed to the clip like
        // the processed histogram (a switch starts empty).
        var histogramModeOrdinal by rememberSaveable {
            mutableStateOf(HistogramMode.RGB.ordinal)
        }
        // The RAW mode's presentation (owner answer #6): COMBINED or
        // PARADE. Saveable like the mode; the scope compute effect
        // deliberately does NOT key on it — both shapes draw the SAME
        // fetched rawHistogram (display-only, no new decode), so a
        // toggle re-renders instantly without re-fetching.
        var rawHistogramPresentationOrdinal by rememberSaveable {
            mutableStateOf(RawHistogramPresentation.COMBINED.ordinal)
        }
        var rawHistogram by remember(sourceId) { mutableStateOf<RawHistogram?>(null) }
        // Sampling quality (spec §6): scope-ONLY resolution — it changes
        // how many pixels each waveform pass samples, never the source /
        // preview / export pipeline. Saveable like the mode; keyed into
        // the compute effect so a change while paused recomputes the
        // CURRENT frame at the new quality immediately.
        var scopeSamplingOrdinal by rememberSaveable {
            mutableStateOf(WaveformScope.Sampling.MEDIUM.ordinal)
        }
        // The mid-gray reference toggle (spec §8): saveable like the
        // other strip choices. The AVAILABILITY gate is derived live at
        // the call site from the grade (see the scopeRegion): the
        // reference's derivation (18% scene linear through the Rec.709
        // OETF ≈ 41% coded signal) is valid only while the analyzed
        // signal's luma path is the bare OETF — a tonemap curve
        // (ACES/AgX) or a LUT rewrites the transform and the line would
        // lie, so the toggle disappears instead (no fabricated fixed
        // line over a signal it does not describe). The contrast-pivot
        // reference (spec §8's other half) is kept absent: its
        // application lives in the prebuilt native engine, invisible
        // source — no reliable mapping exists, and the spec says keep
        // it off and record the gap.
        var scopeMidGrayShown by rememberSaveable { mutableStateOf(false) }
        var histogram by remember(sourceId) { mutableStateOf(FrameHistogram.of(null)) }
        var waveformRendered by remember(sourceId) {
            mutableStateOf<WaveformDisplay.Rendered?>(null)
        }
        // ---- Zebra state (owner spec PLAY-11) ----
        // The toggle flag is saveable like the scope's (a rotation or
        // process death keeps the user's choice), OFF by default (a
        // preview-affecting tool is opt-in). The striped DISPLAY frame is
        // keyed to the clip like `frame` — a switch starts clean. The
        // paint effect below derives it off the UI thread from the
        // published frame; the scope compute keeps analyzing the CLEAN
        // frame either way (the zebra never contaminates the scope).
        var zebraOn by rememberSaveable { mutableStateOf(false) }
        var zebraFrame by remember(sourceId) { mutableStateOf<PreviewFrame?>(null) }
        // Measurement (spec §7: "~10 scope updates/sec during playback,
        // tuned with measured cost"): every compute records into the
        // clip's ScopeRateMeter and logs one line to logcat tag
        // "ritim-scope"; the trailing-1s count lands in scopeHz (read
        // by the strip's label band in Tnum). The meter is remembered
        // per clip so its history survives the compute effect's
        // restarts; scopeHz itself is not saveable — a fresh session
        // measures fresh.
        val scopeMeter = remember(sourceId) { ScopeRateMeter() }
        var scopeHz by remember { mutableIntStateOf(0) }
        // The strip's measured height for the anchor math — measured via
        // onSizeChanged like the transport (its chrome varies with font
        // scale), never assumed. The strip is simply NOT composed when
        // closed, so the value is read ONLY while it is open (the gate
        // below): a closed strip contributes nothing and closing returns
        // its space to the free region (spec §2: "kapatıldığında
        // kapladığı alan geri kazanılsın") — the anchors re-measure and
        // the panel grows back.
        var scopeStripHeight by remember { mutableStateOf(0) }
        // MINI contributes no strip row (it lives inside the preview),
        // so the height counts ONLY while the full strip composes.
        val scopeStripHeightPx =
            if (scopePresentation == ScopePresentation.STRIP) scopeStripHeight else 0
        val topBarHeightPx = with(density) { EditorTopBarHeight.toPx() }
        // The collapsed anchor: the trimmed handle strip's height
        // (§11.3 state A; owner add-on 2026-11-12: the visible strip is
        // 36dp now, the 48dp touch target overhangs below it).
        val collapsedHeightPx = with(density) { PanelCollapsedHeight.roundToPx() }
        // The free region for the panel + preview together (§11.3): the
        // column minus the fixed rows above and below the flex area —
        // the open scope strip is one of those fixed rows in the
        // portrait column. (In the landscape workspace the strip lives
        // inside the preview column and flexes the preview instead; the
        // draggable sheet never composes there, so this region only
        // ever bounds the portrait sheet's anchors.)
        val freeRegionPx = (editorColumnHeight - topBarHeightPx.toInt() -
            transportHeight - navHeight - scopeStripHeightPx -
            audioStateRowHeightPx).coerceAtLeast(0)
        // The panel anchors update one block below the frame declaration:
        // the 2026-11-12 preview-claim cap (owner layout-refactor spec
        // section 2) needs the clip's display ratio — the rendered frame's
        // dims — which is declared further down with the render
        // bookkeeping.
        // The saveable mirror: settledValue is the state the panel has
        // settled on (drag end / animation end), so the saveable keeps
        // exactly what the user last saw (§11.6).
        LaunchedEffect(panelDrag) {
            snapshotFlow { panelDrag.settledValue }
                .collect { settled -> panelState = settled }
        }
        // ---- Swipe-up unfold redirect (owner feedback 2026-11-13) ----
        // The component's own settle can only ever target the ADJACENT
        // anchor, so an up-release from COLLAPSED animates to NORMAL —
        // never the EXPANDED the owner asked for. This observer watches
        // the settle's LIVE target (targetValue while animating) and,
        // when the pure PanelBehavior rule fires (COLLAPSED settling UP
        // to NORMAL), re-targets the state's OWN animateTo: the public
        // extension cancels the settle at the same mutation priority,
        // restarts from the live offset with the settle's velocity and
        // runs the same snapAnimationSpec — one continuous physics
        // motion to EXPANDED, never a hand-rolled spring. The category
        // tap's programmatic open to NORMAL (§10.3) is excluded by the
        // flag below; every downward settle is untouched (the rule only
        // matches COLLAPSED->NORMAL).
        var panelOpenByTap by remember { mutableStateOf(false) }
        LaunchedEffect(panelDrag) {
            snapshotFlow {
                Pair(
                    panelDrag.isAnimationRunning,
                    if (panelDrag.isAnimationRunning) {
                        Pair(panelDrag.settledValue, panelDrag.targetValue)
                    } else {
                        null
                    },
                )
            }
                .collect { animating ->
                    val live = animating.second ?: return@collect
                    if (panelOpenByTap) {
                        panelOpenByTap = false
                    } else if (PanelBehavior.swipeUpRetarget(live.first, live.second)) {
                        scope.launch { panelDrag.animateTo(PanelBehavior.swipeUpTarget(live.first)) }
                    }
                }
        }
        var grade by remember(sourceId) { mutableStateOf(initialGrade) }

        // F1-6: the v2 record reader, mirrored per composition (the
        // gyroPathFor rememberUpdatedState precedent — the requestRender
        // closure below outlives recompositions of the param).
        val currentGradeV2 by rememberUpdatedState(gradeV2For)
        var nodes by remember(sourceId) { mutableStateOf(nodesFor()) }
        var selectedNode by rememberSaveable(sourceId) { mutableStateOf(0) }

        // ---- Grade undo/redo history (feature N7) ----
        // Per-open-editor, keyed to the clip like every other editor
        // state: a clip switch starts a FRESH history seeded with the
        // clip's initial grade (the stack's bottom — undo returns null
        // there). In memory only, deliberately: process death empties it
        // while GradeStore keeps the grade itself, so the honest
        // post-death state is "nothing to undo" (GradeHistory KDoc).
        val gradeHistory = remember(sourceId) { GradeHistory(initialGrade, nodes) }

        // ---- Preview + transport bookkeeping (all keyed to the clip) ----
        var frame by remember(sourceId) { mutableStateOf<PreviewFrame?>(null) }
        // RAW-09: the UNCROPPED effective-upright dims (container dims
        // turned by the manual rotation). The publish fold sets `frame`
        // to the CROPPED buffer, so its dims shrink once a crop is
        // active — the aspect presets must math against THIS pair or
        // "16:9" would be computed off the already-cropped rect. Set in
        // the publish onSuccess BEFORE the crop fold; 0x0 before the
        // first publish (CropState.aspectRect guards to the full rect,
        // the documented degradation). A degenerate placeholder publish
        // (both sides <= 1, the stats-off 1x1 — see the fold) never
        // updates it, keeping the last real pair instead.
        var effectiveDims by remember(sourceId) {
            mutableStateOf(RotationState.Dims(0, 0))
        }
        // The index of the frame LAST PUBLISHED by the render pump
        // (feature N6): the pump's publish swaps `frame` and this
        // index together, so the scope compute's RAW fetch asks for
        // stats of exactly the frame on screen. -1 until the first
        // publish (no RAW fetch before a frame exists). Keyed to the
        // clip like `frame` — a switch resets both.
        var renderedFrameIndex by remember(sourceId) { mutableStateOf(-1) }
        // ---- Preview claim (owner layout-refactor spec 2026-11-12, §2) ----
        // The root-cause fix's input: the display ratio the preview surface
        // will shape itself by — the SAME chain the surface uses
        // (rendered frame dims > persisted import stats > 16:9), then the
        // manual-rotation swap — so the claim and the surface always
        // agree on the clip's shape. Computed from the same values the
        // surface reads, never from a separate measurement of the surface
        // itself (that would race the first frame).
        val previewDisplayRatio = PreviewSurfaceRatio.withManualRotation(
            PreviewSurfaceRatio.of(
                frameWidth = frame?.width ?: 0,
                frameHeight = frame?.height ?: 0,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            ),
            manualRotationDeg,
        )
        // The height a width-bound clip needs in the free region: the full
        // width's aspect height when it fits (landscape footage), else 0
        // (a taller-than-region clip can never be width-bound, so there is
        // no width to preserve — the legacy flex behavior applies).
        val previewClaimPx = PanelSheet.previewClaimPx(
            regionWidthPx = previewRegionWidth,
            regionHeightPx = freeRegionPx,
            ratio = previewDisplayRatio,
        )
        // The minimum usable panel height in px (three 48dp slider rows +
        // the 48dp handle target): the claim cap may never squeeze the
        // panel below it.
        val minPanelHeightPx = with(density) { PanelSheet.MIN_PANEL_HEIGHT.roundToPx() }
        // Anchors update whenever the measured region, the claim or the
        // floor changes (first composition, rotation, font-scale change, a
        // taller/shorter clip's first frame, opening/closing the scope
        // strip). updateAnchors snaps synchronously to the CURRENT target,
        // so the offset lands on the restored state's height on the first
        // frame and follows the region on re-measures — never a
        // re-animation from zero. The claim keys the effect so a clip
        // whose first frame reveals a different shape than the persisted
        // stats adapts the anchors ONCE (a single predictable resize, the
        // spec's allowed reason), not continuously.
        LaunchedEffect(freeRegionPx, collapsedHeightPx, previewClaimPx, minPanelHeightPx) {
            if (freeRegionPx > 0) {
                panelDrag.updateAnchors(
                    DraggableAnchors {
                        PanelSheet.anchorHeights(
                            freeRegionPx,
                            collapsedHeightPx,
                            previewClaimPx,
                            minPanelHeightPx,
                        ).forEach { (state, h) ->
                            state.at(h)
                        }
                    },
                    panelState,
                )
            }
        }
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
        // ---- GPU preview session (M4 commit b) ----
        // One GPU session per editor open, keyed to the clip like the CPU
        // session. Opened only when the CPU session holds a native clip
        // handle (the GPU dispatch needs it to decode the CFA), closed
        // exactly once at dispose — a drained close on the same single
        // preview engine thread. gpuActive is the LATCH (the M3 per-frame
        // dispatch rule): one failed render (null verdict) or a failed
        // open flips it off for the clip's whole editor lifetime and the
        // pump takes the CPU path wholesale from then on. gpuWindowLive
        // tells PreviewSurface the GPU window path is serving the display
        // (suppress the Compose Image — the SurfaceView draws BEHIND the
        // Compose window); it follows gpuActive AND a live session AND a
        // handed-off surface, so a surface loss (backgrounded app) drops
        // back to the Image exactly when the window stops serving.
        var gpuSession by remember(sourceId) { mutableStateOf<GpuPreviewSession?>(null) }
        var gpuActive by remember(sourceId) { mutableStateOf(false) }
        // F1-8e: bumped (on the main thread) once per OpenDRT table the native async bake published; the
        // effect below turns each bump into exactly ONE redraw (the old table kept showing until then).
        var drtSwapTick by remember(sourceId) { mutableIntStateOf(0) }
        // FULL preview quality is GPU-only (PreviewQuality.effectiveFor): it
        // renders at the recorded resolution only while the GPU preview is
        // the renderer; CPU/AUTO or a session not up yet fall back to HIGH.
        // Since A3 (owner request 2026-11-23, guide A3) zebra is no
        // exception: the GPU window paints the stripes, so FULL quality is
        // allowed whenever the GPU window serves, zebra included.
        val previewQualityEffective = PreviewQuality.effectiveFor(
            previewQuality,
            fullResAllowed = gpuActive &&
                renderBackend == RenderBackend.GPU_EXPERIMENTAL,
        )
        var gpuWindowLive by remember(sourceId) { mutableStateOf(false) }
        // The acknowledged EGL attachment, set after the SurfaceHolder
        // lifecycle handoff completes: gpuWindowLive is servedByGpuWindow
        // AND this, so the Image
        // suppresses ONLY when the GPU window is really drawing (a served
        // frame AND a live surface) — never black-holes the preview on a
        // surface that died before its first frame.
        var gpuSurfaceLive by remember(sourceId) { mutableStateOf(false) }
        var gpuSurfaceGeneration by remember(sourceId) { mutableStateOf(0L) }
        // The GPU session-open window's honesty flag (owner finding
        // 2026-11-14): true from the session-open request (the capability
        // gate's start) until its verdict — the first frame published by
        // the pump, or a fallback (red gates, failed open, the render
        // latch). stateOf maps it to PREPARING when no frame exists yet,
        // so the placeholder says "Preparing preview…" with the spinner
        // instead of the idle "No preview yet" over the seconds-class
        // open on a fresh 12MP clip.
        var gpuPreparing by remember(sourceId) { mutableStateOf(false) }
        // Stage 24's runtime fact (commit b): whether the device's GLES
        // context carries the RGBA16F 3D-texture LUT path (GLES 3.0 core).
        // Latched ONCE by the capability gate from the same probe that
        // feeds the gates — false until then, and false on any probe
        // failure (null, malformed, seam throw): a LUT-active grade then
        // rides the CPU preview path, the same honest fallback the export
        // gate rides. Keyed on sourceId like its GPU siblings: a fresh
        // clip re-derives it at its own capability gate.
        var gpuLutTexturePathOk by remember(sourceId) { mutableStateOf(false) }
        // Editor audio (owner request 2026-11-11 section 4): one controller per
        // clip, released at dispose. Audio follows the transport: play starts
        // it at the current position, pause/seek stop it, the clip end ends
        // it; a clip without audio plays silent (openAudio returns null).
        val audio = remember(sourceId) { EditorAudioPlayback() }

        // Transport state: play/pause, the current frame shown by the
        // transport (slider position + playback cursor), and the clip's real
        // MCRAW timestamps fetched once after the session opens.
        var isPlaying by remember(sourceId) { mutableStateOf(false) }
        // P3: shared between the playback loop and the render publish site (a
        // plain array, not state: it must never recompose anything).
        val playbackTraceHolder = remember(sourceId) { arrayOfNulls<PlaybackTrace>(1) }
        // The short message shown instead of the raw allocator text when a frame is too large to preview.
        val previewTooLargeText = androidx.compose.ui.res.stringResource(R.string.preview_error_too_large)
        // P3: the live preview Surface (GPU path only), for the display-rate request.
        var displaySurface by remember { mutableStateOf<android.view.Surface?>(null) }
        var positionFrame by remember(sourceId) { mutableStateOf(0) }
        var ptsUs by remember(sourceId) { mutableStateOf<LongArray?>(null) }
        // Slider-drag tier state (SliderTiers machine): while the finger is
        // down, renders use the low drag tier through the session's CACHED
        // path; the drag end emits exactly one full-quality settle render.
        var dragActive by remember(sourceId) { mutableStateOf(false) }
        // Zoomed-preview tier policy (owner request 2026-11-12 section 3):
        // while the pinch transform is zoomed, every SliderTiers-priced
        // render stays at the full settle tier (the magnified bitmap
        // would read soft at the 480px drag tier). SAVEABLE and keyed on
        // the clip — the exact lifecycle of the transform it mirrors
        // (PreviewSurface's zoomPan is rememberSaveable(clipKey)): a
        // fresh clip starts at 1x (the transform resets on clipKey
        // change and no settle callback fires, so this resets with it),
        // and a configuration change mid-zoom keeps BOTH the transform
        // and this flag, so the tier policy survives rotation intact.
        var previewZoomed by rememberSaveable(sourceId) { mutableStateOf(false) }
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
        // Landscape panel hide/reopen (owner request 2026-11-11d section
        // 17.3: "an obvious affordance for hiding the adjustment area" +
        // "collapsing the adjustment area may expand the preview
        // horizontally"): in the two-column workspace the right column
        // carries a hide button; hidden, the preview column widens to the
        // FULL width (OrientationLayout.previewWidthFraction) and a reopen
        // chip appears on the preview's right edge — the affordance stays
        // discoverable and easy to restore (the same scrimmed IconButton
        // group as the fullscreen/histogram corner controls). Saveable so a
        // rotation keeps the panel where the user left it (section 18: no
        // reset on orientation switch); the PORTRAIT sheet has its own
        // collapse affordance (the drag handle) and never reads this
        // state — the two layouts' panel states are independent.
        var panelVisible by rememberSaveable { mutableStateOf(true) }
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

        // ---- LUT import (COL-07, managed interpretations 2026-11-13) ----
        // The import error is LOCAL to the LUT section: errorText drives the
        // preview FAILED state (EditorPreviewLogic.stateOf), so a failed
        // import must not yank the preview placeholder.
        var lutImportError by remember(sourceId) { mutableStateOf<String?>(null) }
        // The sheet's pending pick (owner request 2026-11-13): the SAF
        // picker no longer imports immediately — it ARMS the
        // interpretation sheet with the picked uri + the file's fallback
        // name, and the import happens only on the sheet's Confirm (Name +
        // Preset + gamuts + transfers + role). Cancel/dismiss leaves NO
        // record: nothing is copied, nothing persisted. Saveable like
        // framePickerOpen so a rotation mid-dialog keeps the pick armed.
        var pendingLutUri by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingLutName by rememberSaveable { mutableStateOf("") }
        // The record being re-interpreted ("Edit interpretation"): the
        // sheet opens with this record's own name + interpretation and
        // Confirm calls onEditLutInterp — a NEW binding revision, never a
        // mutation of the selected record. Plain remember (LutInfo is not
        // Bundle-saveable): a configuration change mid-edit dismisses the
        // sheet with cancel semantics — no record touched, the user
        // reopens it.
        var editingLut by remember { mutableStateOf<LutInfo?>(null) }
        // SAF picker: any mime — the native probe validates the .cube, and a
        // non-cube pick fails the probe with a clear message instead of being
        // blocked at the picker (the same permissive shape as the clip
        // import).
        val lutPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            pendingLutName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?: uri.lastPathSegment
                ?: ""
            pendingLutUri = uri.toString()
        }

        // ---- Gyro attach (gyro Phase 5) ----
        // Same shape as the LUT import: the error is LOCAL to the
        // Stabilization group (never the preview's error surface), and the
        // SAF picker takes any mime — the native sync probe validates the
        // .gcsv, and a non-gcsv pick fails the probe with the recovery
        // message instead of being blocked at the picker.
        var gyroImportError by remember(sourceId) { mutableStateOf<String?>(null) }
        // The zoom-limit honesty readout's data (audit fix 2026-11-12, Fix
        // 5): the native probe's [requiredZoom, appliedZoom, statusInt] for
        // the CURRENT grade + attached log, or null = no verdict (the
        // group renders no readout rather than fabricating a zoom). Filled
        // by the probe effect below; keyed to the clip like every other
        // editor state.
        var stabZoom by remember(sourceId) { mutableStateOf<DoubleArray?>(null) }
        // Optical sync refinement (M5 commit b): a RUNTIME user toggle,
        // deliberately NOT a grade field (GradeK is frozen — the grade
        // persists per clip, but the refine verdict is an engine-wide
        // experiment until the owner's on-device A/B proves it; the
        // default-off global in the bridge keeps every path honest).
        // rememberSaveable so a rotate keeps the switch where the owner
        // left it; the setter rides the guarded engine seam and the probe
        // effect re-runs on the flip (the cache invalidation re-builds
        // the plan under the new flag).
        // Round 3 (2026-11-23): ON by default — the anchored offset alone was
        // measured 83-135 ms off on the owner's device, which makes the
        // warp amplify shake; the optical refine fixed it on every clip.
        var syncRefine by rememberSaveable(sourceId) { mutableStateOf(true) }
        LaunchedEffect(syncRefine) {
            // The seam throws only without the .so; the editor already
            // gates every native call on nativeAvailable, and the stab
            // sheet's toggle is only visible when a session exists.
            if (NativeSource.nativeAvailable) {
                NativeSource.jniSetSyncRefine(syncRefine)
            }
            else {
                syncRefine = false
            }
        }
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
        // ---- Crop overlay mode (owner request 2026-11-13 §2) ----
        // Derived, not remembered: the value is a pure function of tab +
        // frameSubTab + the clip kind's stabilization gate (+ fullscreen
        // + zoom), so it can never drift from the panel's own sanitized
        // dispatch. While true, EVERY render the screen requests carries
        // the crop-neutral display grade (requestRender swaps it in), the
        // overlay composes over the preview, and handle drags write the
        // REAL grade through the same slider route the panel's sliders
        // use. Derived state also means the funnels below (plain local
        // functions, rebuilt every recomposition) always read the
        // CURRENT value at call time.
        //
        // SUSPENSIONS — both are view-the-result modes, and both flip
        // the SAME derived flag so the flip effect below re-renders the
        // transition in both directions:
        //  - FULLSCREEN hides the panel; cropping blind is pointless.
        //  - PINCH-ZOOM: the zoom/pan transform lives INSIDE
        //    PreviewSurface (its own rememberSaveable zoomPan — the
        //    screen sees only the settled previewZoomed boolean), while
        //    the overlay's anchor Box measures the UNZOOMED video rect.
        //    Over zoomed content the overlay's rect would sit at unzoomed
        //    fractions — a rect that LIES about the content under it —
        //    and handle hit-testing would map pointer px to the wrong
        //    fraction space. Making the overlay zoom-aware would mean
        //    breaking the surface's encapsulation (out of scope here),
        //    so zoom suspends the mode instead: pinch in and the real
        //    crop renders (inspect the result), pinch back to 1x and the
        //    overlay returns. Pan only exists while zoomed, so it never
        //    coexists with the overlay; at 1x the preview's gestures
        //    (pinch-start, hold, chip taps) all keep flowing — the
        //    overlay's pointerInput leaves non-handle events unconsumed.
        val cropOverlayActive by remember {
            derivedStateOf {
                !fullscreen && !previewZoomed &&
                    EditorPreviewLogic.cropOverlayVisible(
                        tab, frameSubTab,
                        EditorPreviewLogic.showStabilization(sourcePath),
                    )
            }
        }

        /**
         * Offers a render to the pipeline (replaces any pending one).
         * [followUpMaxDim] (progressive pause settle, owner request
         * 2026-11-11 section 2): after THIS render publishes, the pump
         * offers one more render of the same frame/grade at that tier —
         * only when the pending slot is empty, so anything the user did
         * in between still wins. [renderClass] (choppy-preview fix
         * 2026-11-13) tells the seam's temporal NR gate WHAT this render
         * is for: the playback loop passes PLAYBACK (the fix — playback
         * never runs the ~85-107 ms merge, whatever tier the quality
         * setting gives it; at HIGH that tier is 1280 and the old
         * tier-only gate turned NR on, ~3.65 renders/sec measured),
         * slider/seek drags pass DRAG, everything else stays SETTLE (the
         * default — pause, clip end, zoom settle, refresh, precision
         * scrub, holds: the parked frames that can afford the merge).
         */
        fun requestRender(
            frameIndex: Int,
            g: GradeK,
            maxDim: Int,
            cached: Boolean = false,
            followUpMaxDim: Int? = null,
            renderClass: RenderClass = RenderClass.SETTLE,
        ) {
            // Crop overlay mode (owner request 2026-11-13 §2): every
            // render while the Crop sub-tab is open renders the
            // crop-neutral display grade — the publish fold crops
            // before display, so the overlay needs the UNCROPPED image
            // behind it (the overlay draws the REAL grade's rect). The
            // swap is display-only: `grade` itself is untouched, and
            // the followUp re-offer inside the pump re-uses req.grade,
            // so the progressive settle inherits the same display
            // grade. Every render path funnels here (tier events, the
            // grade debounce, playback, the hold, the flip effect
            // below), so ONE point covers them all.
            val renderGrade =
                if (cropOverlayActive) EditorPreviewLogic.displayGradeForCropOverlay(g) else g
            // F1-6 (color model v2): the request's chain wire — null for
            // every LEGACY grade (the K2 fast path, bit-identical); the
            // TRIMMED chain's wire for a NODES/DRT grade. The reader seam
            // runs per request, so an edit mid-session is picked up on
            // the very next render (the lutPathFor precedent). A LUT-
            // carrying node chain (the unwired follow-up) folds to null
            // HERE — the CPU reference renders those through the legacy
            // develop and the render stays honest rather than refusing
            // the whole preview.
            val chainWire = runCatching {
                val v2 = currentGradeV2()
                val nodes = v2?.let { GradeV2Usage.activeNodes(it) } ?: return@runCatching null
                if (nodes.isEmpty() &&
                    v2.output.mode != com.ritim.ritimcraw.data.grade2.OutputTransformV2.MODE_OPENDRT
                ) {
                    return@runCatching null
                }
                NodeChainWire.encode(v2, nodes)
            }.getOrNull()
            renderSeq += 1
            gate.offer(
                RenderRequest(frameIndex, renderGrade, maxDim, renderSeq, cached, followUpMaxDim, renderClass, chainWire)
            )
            pumpKick.trySend(Unit)
        }

        /**
         * Applies one SliderTiers transition and renders per its decision.
         * The drag-tier cap is the user's scrub dim (preview-quality
         * setting, owner request 2026-11-11b section 3); the settle tier
         * stays the fixed full preview tier.
         */
        fun applyTierEvent(event: SliderTiers.Event) {
            val t = SliderTiers.onEvent(
                event, dragActive, previewQualityEffective.scrubMaxDim,
                zoomed = previewZoomed,
            )
            dragActive = t.dragActive
            when (val d = t.decision) {
                is SliderTiers.Decision.RenderAtDragTier ->
                    // DRAG class even when the tier is the 1280 settle tier
                    // (a zoomed drag): the class, not the tier size, feeds
                    // the temporal NR gate — an interactive render never
                    // pays the ~85-107 ms merge.
                    requestRender(positionFrame, grade, d.maxDim, cached = true, renderClass = RenderClass.DRAG)
                is SliderTiers.Decision.RenderAtSettleTier ->
                    requestRender(positionFrame, grade, d.maxDim)
                SliderTiers.Decision.NoRender -> Unit
            }
        }

        /**
         * The single recording apply path for user grade edits (feature
         * N7): writes the grade state, persists through onGradeChange
         * (the AppNav funnel), records into the history, and settles
         * the render tier. Slider-path edits (drag frames and tap-to-
         * position jumps alike — both arrive through a panel's onChange
         * route) record with the SLIDER_GESTURE cause, so consecutive
         * frames of one gesture coalesce into ONE undo step, closed by
         * gradeHistory.settle() on drag end; discrete edits (toggles,
         * the tonemap/LUT choices, paste/preset applies, double-tap
         * resets) pass null and never coalesce.
         */
        fun applyGradeEdit(g: GradeK, cause: Any? = null) {
            grade = g
            onGradeChange(g)
            gradeHistory.record(g, cause)
            if (cause == null) {
                // Discrete edits settle immediately at the full tier
                // (GradeEdited with no drag active), like the panel
                // toggles always have.
                applyTierEvent(SliderTiers.Event.GradeEdited)
            } else if (dragActive) {
                // Mid-drag frames render immediately at the low tier
                // through the cached path.
                applyTierEvent(SliderTiers.Event.GradeEdited)
            }
        }

        // A node slider drag fires many lists; the history merges them into one step and this job closes the
        // step once the edits pause (the node sliders report no drag end of their own).
        var nodeSettleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        /**
         * F1-8a: a node-chain edit. The chain persists first (the render sites read it per request through
         * gradeV2For), then the current frame re-renders at the paused tier with the source-size follow-up.
         * On the undo history: a list with the same nodes (enabled flags) as before is a value edit (slider
         * drag, rename, reset) and merges into one step under NODE_GESTURE; add, remove, duplicate, reorder and
         * enable/disable are discrete steps.
         */
        fun applyNodesEdit(list: List<com.ritim.ritimcraw.data.grade2.GradeNodeV2>) {
            val before = nodes
            val valueEdit = before.size == list.size && before.indices.all { before[it].enabled == list[it].enabled }
            nodes = list
            onNodesChange(list)
            gradeHistory.recordNodes(list, if (valueEdit) GradeHistory.NODE_GESTURE else null)
            if (valueEdit) {
                nodeSettleJob?.cancel()
                nodeSettleJob = scope.launch {
                    kotlinx.coroutines.delay(600)
                    gradeHistory.settle()
                }
            } else {
                gradeHistory.settle()
            }
            requestRender(
                positionFrame, grade, PreviewTiers.PAUSED_MAX_DIM,
                cached = true, followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
            )
        }

        /** Applies an undo/redo snapshot (grade AND node chain) without recording. */
        fun applyRestoredSnapshot(snap: GradeHistory.Snapshot) {
            nodeSettleJob?.cancel()
            grade = snap.grade
            onGradeChange(snap.grade)
            if (snap.nodes != nodes) {
                nodes = snap.nodes
                onNodesChange(snap.nodes)
                selectedNode = selectedNode.coerceIn(0, (snap.nodes.size - 1).coerceAtLeast(0))
                requestRender(
                    positionFrame, grade, PreviewTiers.PAUSED_MAX_DIM,
                    cached = true, followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                )
            }
        }

        /**
         * Applies an undo/redo result WITHOUT recording (feature N7):
         * undo must never clear the redo stack. Persistence and the
         * live preview follow through the same onGradeChange +
         * debounce-effect path every other grade write takes.
         */
        fun applyRestoredGrade(g: GradeK) {
            grade = g
            onGradeChange(g)
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

        // GPU preview session open (M4 commits b+c): only when the backend
        // setting asks for GPU_EXPERIMENTAL AND the CPU session opened WITH
        // a native clip handle (the GPU dispatch decodes the CFA through
        // that handle; an encoded clip reports 0 and stays CPU). Keyed to
        // the clip like the CPU session above; runs AFTER it in the same
        // composition so the handle exists. Commit c adds the capability
        // gate BEFORE the open — the M3 export gate (probe + case-0
        // fragment parity), mirrored for the preview: a request that
        // cannot honestly serve on GPU never opens the session, with ONE
        // fallback line in the M3 vocabulary. AUTO stays CPU until the
        // owner's device verdict moves the picker (the honest policy
        // line, never silent). A failed open (handle 0) latches the path
        // off for the clip's lifetime — ONE honest fallback line in the
        // error text (the M3 session-open pattern), never a silent
        // stay-on-CPU.
        LaunchedEffect(session, renderBackend) {
            val s = session
            if (renderBackend != RenderBackend.GPU_EXPERIMENTAL) {
                // AUTO stays CPU for the preview until the owner's device
                // verdict moves the picker — the honest policy line (the
                // M3 export's AUTO shape), on logcat because it is normal
                // operation, not a failure: the error text renders ONLY in
                // the FAILED state and AUTO-on-CPU is a healthy preview.
                if (renderBackend == RenderBackend.AUTO) {
                    Log.d(
                        RenderTrace.TAG,
                        EditorPreviewLogic.backendFallbackLine(
                            RenderBackend.AUTO, "auto-stays-cpu",
                        ),
                    )
                }
                return@LaunchedEffect
            }
            // E0b-4b follow-up (2026-11-25): an ENCODED clip reports no native clip handle but takes the GPU
            // window path through the preview session's YUV door (EncodedPreviewSession.gpuInput); only a clip
            // with neither stays CPU.
            val encodedSession = s as? EncodedPreviewSession
            if (s == null || (s.nativeClipHandle == 0L && encodedSession == null)) return@LaunchedEffect
            // One session per editor open: a renderBackend toggle away and
            // back re-runs this effect, and a second open would leak the
            // first session's bridge slot until process exit.
            if (gpuSession != null) return@LaunchedEffect
            // The open window starts HERE (every guard passed, the
            // capability gate is about to run): the placeholder turns
            // PREPARING for its whole seconds-class duration on a fresh
            // 12MP clip, and every early return above (AUTO stays CPU,
            // no native handle, session already open) never arms it.
            gpuPreparing = true
            // ---- The capability gate (M4 commit c) ----
            // The M3 export gate's order, mirrored: probe -> (context +
            // float-FBO green) -> ONE case-0 fragment parity run (the
            // developLinear shape the device PROVED at 1 LSB) -> the
            // BackendGates verdict. Both legs ride the preview engine
            // thread BEFORE the session opens (the harness brings its own
            // one-shot EGL context up and down inside the call — running
            // the parity after the open would put two contexts on one
            // thread). The parity leg needs the clip's fd: the harness
            // DUPs it natively, so this copy closes here either way. A
            // red gate (or a seam that never answered) never opens the
            // session: ONE fallback line names the missing gates, never a
            // fabricated measurement — a null probe names all of them
            // (no leg ran), and the line's reason tokens come from the
            // gates the assembly reported, never gatesFrom.
            var gates = BackendGates()
            var parityGateDetail: String? = null
            val parityFd = sourceFdProvider()
            try {
                val develop = NativeGpuPreviewDevelop
                val probeRaw = develop.probe()
                // Stage 24's runtime leg (commit b): the SAME probe answers
                // whether the RGBA16F 3D-texture LUT path is available
                // (GLES 3.0 core → contextOk && glesMajor >= 3). Latched
                // ONCE per editor open and handed to every eligibility
                // check below; a null or malformed probe keeps it false —
                // a LUT-active grade then rides the CPU path, never a
                // fabricated healthy path.
                gpuLutTexturePathOk = try {
                    probeRaw != null &&
                        GlesProbeResult.fromArray(probeRaw).let {
                            it.contextOk && it.glesMajor >= 3
                        }
                } catch (t: Throwable) {
                    false
                }
                // The parity leg is paid only when the probe's verdicts
                // justify it AND the clip's fd opened — a doomed run is
                // waste; the honest null keeps parityOk false either way.
                val parityFdOpened = parityFd >= 0 && encodedSession == null
                val parityPaid = parityFdOpened &&
                    EditorPreviewLogic.probePaysParity(probeRaw)
                val parityRaw = if (parityPaid) {
                    develop.fragmentParity(parityFd)
                } else {
                    null
                }
                val parityDetail = develop.lastDetail()
                gates = EditorPreviewLogic.previewCapabilityGates(
                    probeRaw = probeRaw,
                    parityRaw = parityRaw,
                    parityDetail = parityDetail,
                )
                if (encodedSession != null) {
                    // The case-0 fragment parity leg decodes an MCRAW clip's CFA through its fd; the encoded
                    // door's parity against the CPU oracle is covered by the device parity suite
                    // (EncodedYuvPassParityTest: chain, LUT, nodes, tone bridge, window door), so the gates
                    // come from the capability probe alone (context + float FBO) and parity counts as met.
                    gates = runCatching {
                        val pr = GlesProbeResult.fromArray(probeRaw!!)
                        BackendGates(
                            contextOk = pr.contextOk, computeOk = pr.computeOk,
                            floatFboOk = pr.floatFboOk, parityOk = true,
                        )
                    }.getOrDefault(BackendGates())
                }
                // The parity verdict's own detail (the parity-fix
                // observability): previewCapabilityGates keeps ONLY .pass
                // (its JVM-pinned shape stays untouched), so the harness's
                // why is computed HERE at the dispatch site — the same
                // verdict call, same inputs — and logged beside the
                // fallback line when the gates are red. Null when the leg
                // was never paid or the verdict passed.
                parityGateDetail = GpuParityRules.gateDetailLine(
                    parityPaid = parityPaid,
                    parity = parityRaw?.let { GlesParityResult.fromArray(it) },
                    errorDetail = parityDetail,
                )
                // A green gate that exceeded the suite's strict mismatch share still logs its numbers.
                if (gates.parityOk) parityGateDetail?.let { Log.d(RenderTrace.TAG, it) }
            } catch (t: Throwable) {
                // A malformed seam array (the fromArray contract: loud,
                // never papered over) costs the GPU path, never the
                // preview — the honest all-false gates.
                gates = BackendGates()
                gpuLutTexturePathOk = false
            } finally {
                PreviewRenderer.closeFdQuietly(parityFd)
            }
            if (!gates.fragmentAllowed()) {
                // Non-null exactly when the fragment gates are red — this
                // branch; the elvis arm is the invariant's belt-and-braces.
                val reason = gates.fragmentFallbackReason()
                    ?: "no-egl-context,no-float-fbo,parity-not-verified"
                Log.d(
                    RenderTrace.TAG,
                    EditorPreviewLogic.backendFallbackLine(
                        RenderBackend.GPU_EXPERIMENTAL, reason,
                    ),
                )
                // The parity gate's companion line (the parity-fix
                // observability): the verdict's own detail beside the
                // fallback line — the GL step + error for a run that
                // refused, the thresholds' numbers for a run that failed
                // them, or the seam's own error when the call returned no
                // array. Null when the parity leg was never paid (the
                // fallback line's tokens already say which gate was red) or
                // the verdict passed (no fallback happened).
                parityGateDetail?.let { Log.d(RenderTrace.TAG, it) }
                errorText = "GPU preview unavailable: $reason — recovery: the CPU preview path stays the default"
                gpuPreparing = false
                return@LaunchedEffect
            }
            val gpu = GpuPreviewSession(develop = NativeGpuPreviewDevelop)
            val opened = gpu.open()
            if (opened) {
                gpuSession = gpu
                gpuActive = true
                // F1-6 (color model v2): compile the two v2 programs
                // (NODES + the TAIL's OpenDRT variant) through the
                // ADDITIVE init door RIGHT AFTER the open — a session
                // that never runs nodes keeps the exact pre-F1-5 behavior
                // (the pipeline's own contract), and the limits the init
                // queried ride the trace when debug is on. A compile
                // failure does NOT latch the session: the v2 doors refuse
                // honestly per request and every legacy grade keeps the
                // GPU path (the programs the legacy chain needs are the
                // session's own twelve).
                runCatching {
                    NativeSource.jniPreviewSessionInitNodes(
                        gpu.handle,
                        GlesShaderSources.NODES,
                        GlesShaderSources.FULLSCREEN_VERTEX,
                        GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT,
                    )
                }
                runCatching {
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    NativeSource.jniPreviewSessionSetDrtReadyListener(
                        gpu.handle,
                        Runnable { mainHandler.post { drtSwapTick += 1 } },
                    )
                }
                // The open SUCCEEDED but the window is not done: the first
                // pump render (or its failure latch) clears the flag —
                // until then the placeholder keeps saying PREPARING.
            } else {
                val detail = gpu.lastDetail() ?: "gpu preview session open failed"
                errorText = "GPU preview unavailable: $detail — recovery: the CPU preview path stays the default"
                gpuPreparing = false
                gpu.close()
            }
        }

        // F1-8e: one redraw per published OpenDRT table (a tick of 0 is the initial state, not a swap).
        LaunchedEffect(drtSwapTick) {
            if (drtSwapTick > 0 && gpuSession != null && session != null) {
                requestRender(positionFrame, grade, PreviewTiers.PAUSED_MAX_DIM)
            }
        }

        // Session close: exactly once, when the editor leaves composition
        // (route change / dispose). Drained: the close is submitted to the
        // same single engine thread, so it runs after all pending renders.
        DisposableEffect(sourceId) {
            onDispose {
                gpuSession?.handle?.let { h ->
                    if (h != 0L) runCatching { NativeSource.jniPreviewSessionSetDrtReadyListener(h, null) }
                }
                session?.close()
                gpuSession?.close()
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
                SliderTiers.tierFor(
                    dragActive, previewQualityEffective.scrubMaxDim,
                    zoomed = previewZoomed,
                ),
                cached = dragActive,
                renderClass = if (dragActive) RenderClass.DRAG else RenderClass.SETTLE,
            )
        }

        // Quarter-turn re-fold (owner request 2026-11-13 §1): rotation is
        // applied in the PUBLISH fold (effectiveDims + the crop's space
        // mapping), never in the native render — so a rotation change
        // with no other trigger would leave the published frame and the
        // aspect-preset dims in the OLD space. This effect re-renders the
        // CURRENT position on every manual-rotation change: a discrete
        // tap (never mid-drag), so the settle tier; the frame itself is
        // unchanged (the native pixels do not depend on the rotation),
        // so the cached CFA path serves it. The renderSeq guard skips
        // the initial composition (the first-render effect owns that
        // one) — and a rotation tapped before the session opens is
        // picked up by that first render, whose fold reads the mirrored
        // value fresh.
        LaunchedEffect(manualRotationDeg) {
            if (!canRender || renderSeq == 0L) return@LaunchedEffect
            requestRender(
                positionFrame, grade,
                SliderTiers.tierFor(
                    dragActive, previewQualityEffective.scrubMaxDim,
                    zoomed = previewZoomed,
                ),
                cached = true,
            )
        }

        // Crop-overlay flip (owner request 2026-11-13 §2): entering the
        // Crop sub-tab must show the UNCROPPED image (the display-grade
        // swap inside requestRender), leaving must restore the real
        // crop — and nothing else keys on the sub-tab, so this effect is
        // the only trigger. A discrete transition (never mid-drag: the
        // sub-tab cannot change while a handle drag owns the gesture),
        // so the settle tier; the frame index is unchanged, so the
        // cached CFA path serves it — one develop per flip, like the
        // quarter-turn re-fold above. The renderSeq guard skips the
        // initial composition (the first-render effect owns that one).
        LaunchedEffect(cropOverlayActive, sourceId) {
            if (!canRender || renderSeq == 0L) return@LaunchedEffect
            requestRender(
                positionFrame, grade,
                SliderTiers.tierFor(
                    dragActive, previewQualityEffective.scrubMaxDim,
                    zoomed = previewZoomed,
                ),
                cached = true,
            )
        }

        // Zoom-limit honesty probe (audit fix 2026-11-12, Fix 5): one
        // native plan evaluation per stab-grade change — the clip-wide
        // required/applied zoom does not depend on the frame or the
        // preview tier (the crop math is uniform-scale invariant, so one
        // probe covers every tier), so the readout updates when the user
        // edits the stab sliders/attach, not per render. The gate is the
        // SAME one the renders run (GradeMapping.stabilizationArgs —
        // toggle + attached log + tier pass; the tier is irrelevant since
        // Fix 1 but the call stays identical so the probe can never
        // disagree with the render gate), and the probe itself runs on the
        // engine thread through the session seam (the bridge's thread
        // contract). Null lands as "no readout" — never a fabricated zoom.
        // The stab grade fields + gyroId + session all key the effect: an
        // edit restarts it, a detach clears the readout, and the session
        // arriving is what makes the probe possible at all.
        val currentGyroPathForProbe by rememberUpdatedState(gyroPathFor)
        LaunchedEffect(
            grade.stabilization, grade.stabilizationMode,
            grade.stabilizationStrength, grade.stabilizationZoomLimit,
            grade.stabilizationFocalMm, grade.stabilizationTrimMs,
            gyroId, session, syncRefine,
        ) {
            val s = session ?: return@LaunchedEffect
            if (!grade.stabilization || gyroId.isBlank() ||
                grade.stabilizationEngine == StabilizationGate.ENGINE_IMAGE
            ) {
                // (the zoom probe is gyro-only; the image engine's probe is S5's)
                stabZoom = null
                return@LaunchedEffect
            }
            val gyroPath = currentGyroPathForProbe(gyroId)
            // The identical mapping the renders use: null = the render
            // gate refuses (no log path), so no honest verdict exists.
            val stab = GradeMapping.stabilizationArgs(
                grade, PreviewTiers.PAUSED_MAX_DIM, gyroPath,
            ) ?: run {
                stabZoom = null
                return@LaunchedEffect
            }
            val probePath = stab.gyroPath
            if (probePath == null) {
                // Unreachable by the guard above (stabilizationArgs copies
                // its non-null gyroPath in) — but the rule is absolute:
                // no path, no verdict, never a fabricated zoom.
                stabZoom = null
                return@LaunchedEffect
            }
            stabZoom = s.stabZoomProbe(
                gyroPath = probePath,
                stabMode = stab.mode,
                stabStrength = stab.strength,
                stabZoomLimit = stab.zoomLimit,
                stabFocalMm = stab.focalMm,
                stabTrimMs = stab.trimMs,
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
        // Manual rotation (owner request 2026-11-13 §1): the publish fold
        // reads it for effectiveDims and the crop's space mapping, so it
        // is mirrored the same way — a quarter-turn tapped mid-session
        // must re-crop in the NEW space, not the launch-time one.
        val currentManualRotationDeg by rememberUpdatedState(manualRotationDeg)
        // GPU preview mirrors (M4 commit b): the pump's LaunchedEffect
        // captures its body once, so the backend setting and the zebra
        // display flag are mirrored per recomposition exactly like the
        // gyro pair above — a settings change or a zebra toggle
        // mid-session is picked up on the next render without restarting
        // the pump.
        val currentRenderBackend by rememberUpdatedState(renderBackend)
        val currentZebraOn by rememberUpdatedState(zebraOn)

        LaunchedEffect(sourceId) {
            // Monotonic publication: the pump is serial and the gate is
            // newest-wins, so polled requests arrive in strictly increasing
            // sequence order — publish anything newer than the last one that
            // published. (Dropping anything not-the-newest, as the old
            // parallel-effect design required, would freeze playback: offers
            // legitimately outpace renders there, and each completed frame
            // must show while the newer one is already pending.)
            var lastPublished = 0L
            // DEBUG (2026-11-23): last grade summary logged by the GPU arm,
            // so ritim-trace gets one "gpu grade" line per grade CHANGE.
            var lastGpuGradeLogged: GradeK? = null
            for (kick in pumpKick) {
                while (true) {
                    val req = gate.poll() ?: break
                    renderInFlight = true
                    // HARDENING (the recorded pump hazard, 2026-11-15, latent —
                    // no known log has hit it): a throw from the body below used
                    // to land TWO failures at once — renderInFlight stayed true
                    // past the request (the reset was plain, not in a finally:
                    // stateOf reports RENDERING forever, the eternal spinner) and
                    // the exception unwound the whole pump (the LaunchedEffect
                    // dies; every later request queues unrendered for the
                    // session). The guard: the finally ALWAYS clears the latch —
                    // cancellation and Error propagation included (an Error still
                    // unwinds the pump, honestly, but never leaves the spinner);
                    // CancellationException is rethrown (structured concurrency:
                    // the sourceId change / composition exit must still cancel
                    // the pump, and the remember(sourceId) reset covers the fresh
                    // session's flag); every OTHER exception is recorded through
                    // the same failure surfaces the fold's onFailure arm uses and
                    // the loop CONTINUES — the failed sequence published nothing
                    // (monotonic publication is safe) and its full-res follow-up
                    // below was never queued (it would fail the same way).
                    try {
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
                        // S4: the SAME slot also carries the image engine
                        // (WarpResolver.stabPathArg): the image argument when
                        // the grade selects it and its analysis is loaded in
                        // this clip's handle, null when it asks for the image
                        // engine and it is not ready (never the gyro log),
                        // else the attached gyro path untouched.
                        val gyroPath = WarpResolver.stabPathArg(
                            req.grade,
                            currentGyroId.takeIf { it.isNotBlank() }?.let(currentGyroPathFor),
                            imageAnalysisReady = ImageStabLoads.isLoaded(s?.nativeClipHandle ?: 0L),
                        )
                        // Render trace (audit M0, 2026-11-13): wall time of the
                        // render call itself (the PreviewMetrics precedent), so
                        // the trace's ms= carries the develop pipeline the
                        // audit's host harness measured — NOT the UI crop, which
                        // is a publish-space resample the host numbers never
                        // included.
                        val renderStartedMs = System.currentTimeMillis()
                        // ---- GPU preview dispatch (M4 commit b) ----
                        // The M3 export per-frame dispatch pattern, mirrored
                        // for the preview: when the GPU path is active (the
                        // setting asks, a live GPU session exists, and THIS
                        // request is eligible — every active stage covered;
                        // since A2 (2026-11-23, guide A2) an active crop/
                        // straighten is eligible too (the window pass maps
                        // it); since A3 (2026-11-23, guide A3) zebra-on is
                        // eligible too (the window pass PAINTS the stripes —
                        // the render call threads the four single-sourced
                        // knobs); the gyro warp stopped blocking
                        // eligibility with
                        // its GPU port — the pass is unconditionally fragment-
                        // covered), try the GPU session first. A
                        // null verdict (render failure OR malformed pack)
                        // LATCHES the path off for the clip's lifetime (the
                        // session latches unhealthy on its side too — the M3
                        // rule: never retry a dead session per frame) and THIS
                        // request falls back to the CPU render below, so the
                        // user never sees a dropped frame. Every other path —
                        // CPU requested, encoded clip, failed open, an
                        // ineligible grade — never reaches here: byte-for-
                        // byte the existing CPU dispatch.
                        var gpuServed = false
                        var result: Result<PreviewFrame>? = null
                        val gpu = gpuSession
                        // The A2 crop verdict for this request (2026-11-23,
                        // guide A2): computed inside the GPU arm (the
                        // eligibility gate passed), read by the publish fold
                        // BELOW — a GPU-served frame with an active crop
                        // skips PublishCrop (the pack is already cropped)
                        // and must not shrink effectiveDims (the uncropped
                        // pair governs). False when the GPU arm never ran
                        // (servedByGpuWindow is false too, so the fold reads
                        // the CPU path verbatim).
                        var gpuCropParams: FloatArray? = null
                        // F1-6: set true when the v2 chain door refused
                        // THIS request (the unwired node-LUT leg, a decode
                        // failure) — the GPU arm then skips and the CPU
                        // render serves the request honestly.
                        var gpuChainRefused = false
                        var gpuCropActive = false
                        // gpuSurfaceLive is read at request time exactly like
                        // session/gpuSession above (snapshot state reads inside
                        // the coroutine see the current value): the surface
                        // creates ASYNC after the session opens, so the first
                        // requests take the CPU arm and the Image serves the
                        // full-res frame — then surfaceCreated flips this true
                        // and the GPU arm takes over. A surface death
                        // (backgrounded app) flips it false mid-session: the
                        // window can no longer serve, so the Image must serve —
                        // and the Image needs the FULL-res frame, which only the
                        // CPU render produces (the GPU publish is the 256
                        // stats pack — right for the scope strip, blocky through
                        // the Image). The session stays open across a surface
                        // death (the destroy signal processes; surfaceCreated
                        // re-hands it on resume), so the GPU path returns
                        // without re-opening.
                        // E0b-4b follow-up: the ENCODED GPU window arm. The decoded planes go through the preview
                        // session's YUV door (chain, LUT, node chain with the Highlights/Shadows bridge, crop, zebra,
                        // stats) and present on the SurfaceView; the frame is never read back into a bitmap. Any
                        // refusal (no GPU-eligible plane layout, an OpenDRT chain, a refused chain door, a failed
                        // render) leaves `result` null and the CPU/bitmap dispatch below serves the request.
                        // A manual rotation stays on the bitmap path: the window pass does not fold it for an
                        // encoded frame (device check 2026-11-25: the landscape frame showed as a thin letterboxed
                        // strip inside the rotated, portrait-shaped box), while the bitmap path rotates correctly.
                        if (s is EncodedPreviewSession && gpuActive && gpu != null && gpuSurfaceLive &&
                            currentRenderBackend == RenderBackend.GPU_EXPERIMENTAL &&
                            ((currentManualRotationDeg % 360) + 360) % 360 == 0
                        ) {
                            val encIn = s.gpuInput(req.frameIndex, req.grade, req.chainWire, req.maxDim)
                            if (encIn != null) {
                                try {
                                    gpuCropParams = GpuCropMap.requestCrop(req.grade, currentManualRotationDeg)
                                    gpuCropActive = gpuCropParams != null
                                    val chainOk = runCatching {
                                        NativeSource.jniPreviewSessionSetColorModelChain(
                                            gpu.handle,
                                            encIn.chainWire ?: NodeChainWire.encode(ClipGradeV2(), emptyList()),
                                            null,
                                        )
                                    }.getOrDefault(false)
                                    if (RenderTrace.enabled) {
                                        Log.i(
                                            RenderTrace.TAG,
                                            "encoded window arm: reqWire=${req.chainWire?.get(0)?.toInt() ?: -1} " +
                                                "sentWire=${encIn.chainWire?.get(0)?.toInt() ?: -1} chainOk=$chainOk",
                                        )
                                    }
                                    if (chainOk) {
                                        val encStats = statsModeFor(
                                            scopeVisible = scopePresentation != ScopePresentation.OFF,
                                            renderClass = req.renderClass,
                                            toggle = previewStatsEnabled,
                                        )
                                        val encFrame = gpu.renderEncoded(
                                            yuv = encIn.frame,
                                            color = encIn.color,
                                            reduce = encIn.reduce,
                                            gradeParcel = GradeParcel.encode(req.grade),
                                            lutPath = lutPath,
                                            lutStrength = req.grade.lutStrength / 100f,
                                            lutRole = req.grade.lutInterp?.role?.value ?: -1,
                                            lutInGamut = req.grade.lutInterp?.input?.gamut?.value ?: -1,
                                            lutInTransfer = req.grade.lutInterp?.input?.transfer?.value ?: -1,
                                            lutOutGamut = req.grade.lutInterp?.output?.gamut?.value ?: -1,
                                            lutOutTransfer = req.grade.lutInterp?.output?.transfer?.value ?: -1,
                                            statsMode = encStats,
                                            cropActive = gpuCropParams != null,
                                            cropParams = gpuCropParams ?: GpuCropMap.NEUTRAL_CROP_PARAMS,
                                            zebraOn = currentZebraOn,
                                            zebraThreshold = FrameHistogram.WHITE_CLIP_VALUE,
                                            zebraPeriod = ZebraOverlay.STRIPE_PERIOD,
                                            zebraAlpha = ZebraOverlay.STRIPE_ALPHA / 255f,
                                        )
                                        if (RenderTrace.enabled) {
                                            Log.i(RenderTrace.TAG, "encoded window arm: rendered=${encFrame != null} reduce=${encIn.reduce}")
                                        }
                                        if (encFrame != null) {
                                            result = Result.success(encFrame)
                                            gpuServed = true
                                        }
                                    }
                                } finally {
                                    s.releaseGpuInput(encIn)
                                }
                                if (!gpuServed) {
                                    gpuCropParams = null
                                    gpuCropActive = false
                                }
                            }
                        }
                        if (gpuActive && gpu != null && s != null && s !is EncodedPreviewSession && gpuSurfaceLive &&
                            currentRenderBackend == RenderBackend.GPU_EXPERIMENTAL &&
                            EditorPreviewLogic.gpuPreviewEligible(
                                grade = req.grade,
                                lutTexturePathOk = gpuLutTexturePathOk,
                            )
                        ) {
                            // Gyro warp (the GPU port's commit b): resolve the
                            // frame's plan at the tier's maxDim — the SAME six
                            // stab values the CPU preview threads, with the
                            // export dispatch's gating (gyroPath and strength
                            // only when stabilization is on AND a log is
                            // attached). Null (no verdict — no log, dead
                            // handle, parse failure) folds to inactive + the
                            // identity M, and the render then skips draw 10
                            // entirely: the pass's absence IS the identity.
                            // S4: the engine decision + fold live in
                            // WarpResolver (shared with the export); the gyro
                            // door receives exactly the values spelled out
                            // here before.
                            val warpVerdict = WarpResolver.verdict(
                                WarpResolver.resolve(
                                    WarpResolver.plan(req.grade, gyroPath),
                                    gyro = { a ->
                                        gpu.resolveWarp(
                                            clipHandle = s.nativeClipHandle,
                                            gyroPath = a.gyroPath,
                                            stabMode = a.mode,
                                            stabStrength = a.strength,
                                            stabZoomLimit = a.zoomLimit,
                                            stabFocalMm = a.focalMm,
                                            stabTrimMs = a.trimMs,
                                            frameIndex = req.frameIndex,
                                            maxDim = req.maxDim,
                                        )
                                    },
                                    image = { a ->
                                        gpu.resolveImageWarp(
                                            clipHandle = s.nativeClipHandle,
                                            stabMode = a.mode,
                                            stabStrength = a.strength,
                                            stabZoomLimit = a.zoomLimit,
                                            rangeStart = a.rangeStart,
                                            rangeEnd = a.rangeEnd,
                                            frameIndex = req.frameIndex,
                                            maxDim = req.maxDim,
                                        )
                                    },
                                ),
                            )
                            val warpActive = warpVerdict.active
                            val warpM = warpVerdict.m
                            // The A2 crop verdict (owner request 2026-11-23,
                            // guide A2): the mapRect'ed parcel for THIS
                            // request's DISPLAY grade through the manual
                            // rotation delta (currentManualRotationDeg —
                            // the rememberUpdatedState read the fold below
                            // also uses). Null = the neutral crop group; a
                            // non-null parcel rides the seam LAST and the
                            // native planCrop owns the dims-dependent
                            // folds. The hoisted pair feeds the publish fold
                            // below (the declarations live at the dispatch
                            // prelude — the fold is outside this block).
                            gpuCropParams = GpuCropMap.requestCrop(
                                req.grade, currentManualRotationDeg,
                            )
                            gpuCropActive = gpuCropParams != null
                            // F1-6 (color model v2): the grade's chain rides
                            // the session's setColorModelChain door BEFORE the
                            // render — the state is LIVE for the session's
                            // NEXT renderFrame (the F1-5 contract). Null wire
                            // = the CLEAR (the plain v1 chain, the K2 lock);
                            // a NODES/DRT wire carries the OpenDRT pack when
                            // the output declares it. A refusal (the unwired
                            // node-LUT leg, a decode failure) folds this arm
                            // OFF for the request — the CPU render below
                            // serves it honestly instead.
                            if (gpu.handle == 0L) {
                                // Unreachable in this block (the arm only
                                // runs with a live handle); the belt for a
                                // future refactor of the dispatch's guards.
                            } else {
                                var chainDoorOk = true
                                if (req.chainWire != null) {
                                    // The DRT pack: resolved ONCE per request
                                    // when the output declares OpenDRT (the
                                    // pre-compiled-packs rule; the 77-slot
                                    // resolver mirrors core's Impl).
                                    val v2 = currentGradeV2()
                                    val v2NeedsDrt = v2 != null &&
                                        v2.output.mode ==
                                        com.ritim.ritimcraw.data.grade2.OutputTransformV2.MODE_OPENDRT
                                    val drtPack = if (v2NeedsDrt && v2 != null) {
                                        runCatching {
                                            com.ritim.ritimcraw.engine.OpenDrtPacks.resolve(
                                                com.ritim.ritimcraw.engine.OpenDrtPacks.Spec(
                                                    look = v2.output.opendrt.look,
                                                    tonescale = v2.output.opendrt.tonescale,
                                                    cwp = v2.output.opendrt.cwp,
                                                    cwpLimit = v2.output.opendrt.cwpLimit,
                                                    grey = v2.output.opendrt.grey,
                                                    display = v2.output.opendrt.display,
                                                ),
                                            )
                                        }.getOrNull()
                                    } else {
                                        null
                                    }
                                    // The OpenDRT tail draw fails on this device (gl error 0x0502 in the tail-drt
                                    // draw, first v2-sweep run 2026-11-25) and the failure latches the GPU
                                    // preview unhealthy: until it is fixed an OpenDRT grade renders on the CPU
                                    // arm (which is what happened silently before the NODES programs compiled).
                                    if (v2NeedsDrt) {
                                        chainDoorOk = false
                                    } else if (v2NeedsDrt && drtPack == null) {
                                        chainDoorOk = false
                                    } else {
                                        chainDoorOk = runCatching {
                                            NativeSource.jniPreviewSessionSetColorModelChain(
                                                gpu.handle,
                                                req.chainWire,
                                                drtPack,
                                            )
                                        }.getOrDefault(false)
                                    }
                                } else {
                                    // The CLEAR: nodeCount 0 + mode 0 — the
                                    // plain v1 chain again (the K2 lock's
                                    // shape). Cheap and idempotent.
                                    chainDoorOk = runCatching {
                                        NativeSource.jniPreviewSessionSetColorModelChain(
                                            gpu.handle,
                                            NodeChainWire.encode(ClipGradeV2(), emptyList()),
                                            null,
                                        )
                                    }.getOrDefault(false)
                                }
                                if (!chainDoorOk) {
                                    // The honest fallback: this request takes
                                    // the CPU arm below (the M3 per-frame
                                    // dispatch rule; the GPU arm itself stays
                                    // latched ON — the next request retries
                                    // the door, a transient decode refuses
                                    // only its own frame).
                                    gpuChainRefused = true
                                }
                            }
                            // GRADE PARCEL (commit b): the grade crosses as ONE
                            // flat FloatArray, encoded HERE once per request —
                            // AFTER the temporalNr tier fold (the DN-08 gate the
                            // flat call threaded below), so the parcel carries
                            // the EFFECTIVE value. The bridge decodes it once
                            // per call; the 29 field arguments died with the
                            // flat door.
                            // DEBUG trace (2026-11-23, owner report: GPU preview
                            // shows no change on slider moves): the grade this
                            // arm hands to the GPU, once per change. No-op in
                            // release (RenderTrace.enabled = BuildConfig.DEBUG).
                            if (RenderTrace.enabled && req.grade != lastGpuGradeLogged) {
                                lastGpuGradeLogged = req.grade
                                RenderTrace.logGpuGrade(
                                    req.grade, req.frameIndex, req.maxDim, req.sequence,
                                )
                            }
                            // A1 async stats (owner request 2026-11-23, guide
                            // A1(a)): the per-request stats readback mode,
                            // from the Task-1 pure fold. scopePresentation
                            // != OFF covers the strip AND the mini — the
                            // only consumers of the published pixels (the
                            // scope compute reads `frame`, both shapes draw
                            // from it); since A3 (guide A3) the zebra rides
                            // the GPU too — the stats pack stays CLEAN
                            // (the session's draw-3 uZebraOn=0 pin), so
                            // zebras never contaminate the mode's consumers.
                            // No scope visible
                            // = OFF = the stats stage is skipped entirely
                            // (the honest 1x1 placeholder path); PLAYBACK
                            // with a scope = ASYNC (the fence ring);
                            // settle/drag with a scope = SYNC (the paused
                            // edit's scopes stay exact).
                            val statsMode = statsModeFor(
                                scopeVisible = scopePresentation != ScopePresentation.OFF,
                                renderClass = req.renderClass,
                                toggle = previewStatsEnabled,
                            )
                            // F1-6: a refused chain door skips the GPU render
                            // for THIS request — the CPU dispatch below
                            // serves it (the honest fold; the GPU arm stays
                            // latched for the next request).
                            val gpuFrame = if (gpuChainRefused) {
                                null
                            } else {
                                gpu.render(
                                clipHandle = s.nativeClipHandle,
                                frameIndex = req.frameIndex,
                                maxDim = req.maxDim,
                                gradeParcel = GradeParcel.encode(
                                    req.grade.copy(
                                        // The temporal pair rides the hybrid's routing
                                        // verbatim: the bridge's own temporalMergedCfa
                                        // consumes them to produce the CFA this render
                                        // uploads — BEFORE the CFA reduction, so the
                                        // reduced mosaic samples the MERGED CFA (the
                                        // SAME merge the CPU preview rides, the same
                                        // per-Entry caches). The DN-08 fold is the
                                        // CPU preview's own gate, mirrored here: the
                                        // merge is tier-INDEPENDENT (~85-107 ms at
                                        // full res), so playback and drag renders skip
                                        // it and the settle render lands the effect —
                                        // the RenderTrace's tnr= verdict computes this
                                        // same fold, so the trace stays honest.
                                        temporalNr = req.grade.temporalNr &&
                                            PreviewTiers.temporalNrAppliesAt(
                                                req.maxDim, req.renderClass,
                                            ),
                                    ),
                                ),
                                // Stage 24 (commit b): the LUT thread rides the
                                // render seam exactly like the CPU preview — the
                                // resolved path, the [0,1] strength, and the
                                // five interpretation ordinals (-1 = legacy).
                                lutPath = lutPath,
                                lutStrength = req.grade.lutStrength / 100f,
                                lutRole = req.grade.lutInterp?.role?.value ?: -1,
                                lutInGamut = req.grade.lutInterp?.input?.gamut?.value ?: -1,
                                lutInTransfer = req.grade.lutInterp?.input?.transfer?.value ?: -1,
                                lutOutGamut = req.grade.lutInterp?.output?.gamut?.value ?: -1,
                                lutOutTransfer = req.grade.lutInterp?.output?.transfer?.value ?: -1,
                                // Gyro warp: the resolved verdict rides the render
                                // seam (warpActive=false skips draw 10 — the
                                // identity no-draw rule).
                                warpActive = warpActive,
                                warpM = warpM,
                                // A1: the per-request stats readback mode
                                // (the demand x class x toggle fold above).
                                statsMode = statsMode,
                                // The A2 crop pair (owner request 2026-11-23,
                                // guide A2): the request builder's verdict for
                                // THIS frame. req.grade here is the DISPLAY
                                // grade — while the crop overlay is open it is
                                // already crop-neutral via
                                // displayGradeForCropOverlay, so overlay frames
                                // render uncropped and the handles stay aligned
                                // with the uncropped window (the guide's
                                // crop-overlay rule, unchanged). Null = the
                                // neutral crop group (the same inline test
                                // PublishCrop.plan runs): cropOn false + the
                                // all-neutral parcel (the JNI seam takes a
                                // non-null array), the shader's identity path.
                                cropActive = gpuCropParams != null,
                                cropParams = gpuCropParams ?: GpuCropMap.NEUTRAL_CROP_PARAMS,
                                // The A3 zebra quad (owner request
                                // 2026-11-23, guide A3): the four single-
                                // sourced knobs — the toggle flag the
                                // pump mirrors, and the three constants
                                // referenced ONLY via ZebraOverlay /
                                // FrameHistogram (253/12/200 appear
                                // nowhere else in the GPU path). The
                                // window draw paints the stripes; the
                                // stats draw pins uZebraOn=0 native-side
                                // (CLEAN scopes).
                                zebraOn = currentZebraOn,
                                zebraThreshold = FrameHistogram.WHITE_CLIP_VALUE,
                                zebraPeriod = ZebraOverlay.STRIPE_PERIOD,
                                zebraAlpha = ZebraOverlay.STRIPE_ALPHA / 255f,
                            )
                            }
                            if (gpuFrame != null) {
                                result = Result.success(gpuFrame)
                                gpuServed = true
                            } else if (gpuChainRefused) {
                                // The chain door refused THIS request: no
                                // latch, no error line — the CPU dispatch
                                // below serves the frame (the honest fold;
                                // the NEXT request retries the door).
                            } else {
                                // The latch: one failure turns the path off for
                                // the clip's lifetime, with the honest detail
                                // line (the M3 fallback-line pattern). The
                                // window stops serving with the latch — and
                                // the preparing window ends with the verdict.
                                gpuActive = false
                                gpuWindowLive = false
                                gpuPreparing = false
                                val detail = gpu.lastDetail() ?: "gpu preview render failed"
                                errorText = "GPU preview fallback: $detail — recovery: the CPU preview path stays the default"
                                Log.d(RenderTrace.TAG, "GPU preview render fallback: $detail")
                            }
                        }
                        if (result == null) {
                            // The CPU dispatch, VERBATIM the pre-GPU code: the GPU
                            // path never reaches here when it served (result set
                            // above), so the CPU path stays byte-identical for
                            // every request it handles.
                            result = if (sessionRender && s != null) {
                                if (req.cached) {
                                    // Interactive drag render: the bridge reuses the
                                    // cached CFA when the frame is unchanged.
                                    s.renderCached(
                                        req.frameIndex, req.grade, req.maxDim,
                                        lutPath, gyroPath, req.renderClass,
                                        // F1-6: the v2 chain's wire (null = the
                                        // legacy fast path, byte-identical).
                                        req.chainWire,
                                    )
                                } else {
                                    s.render(
                                        req.frameIndex, req.grade, req.maxDim,
                                        lutPath, gyroPath, req.renderClass,
                                        req.chainWire,
                                    )
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
                                            renderClass = req.renderClass,
                                        )
                                    }
                                }
                            }
                        }
                        val renderMs = System.currentTimeMillis() - renderStartedMs
                        // The served verdict: the GPU window path served THIS
                        // frame (the SurfaceView draws it; the Compose Image
                        // suppresses) exactly when the GPU arm produced the
                        // result. Computed BEFORE the fold so the publish
                        // block reads it once; a stale publish (isFresh guard
                        // below) never touches it.
                        val servedByGpuWindow = gpuServed
                        if (RenderDebounce.isFresh(req.sequence, lastPublished)) {
                            result!!.fold(
                                onSuccess = { f ->
                                    // The window verdict, published in the SAME
                                    // state batch as the frame (Compose batches
                                    // the two writes): the SurfaceView takes
                                    // the display exactly when the GPU arm
                                    // served THIS frame AND the holder reported
                                    // a live surface. A CPU fallback publish
                                    // flips it back to false, so the Image
                                    // returns the moment the window path stops
                                    // serving — no black hole, no blocky flash
                                    // of the 256 stats pack through the Image.
                                    gpuWindowLive = servedByGpuWindow && gpuSurfaceLive
                                    // The first frame lands: the preparing
                                    // window is over (the frame itself shows
                                    // from here — FRAME outranks PREPARING,
                                    // this clear is the belt-and-braces end
                                    // of the flag's lifecycle).
                                    gpuPreparing = false
                                    // The uncropped effective dims, captured
                                    // BEFORE the crop fold (see the state
                                    // declaration): the aspect presets math
                                    // against this pair, never the cropped
                                    // `frame` dims. A degenerate placeholder
                                    // (both sides <= 1 — the GPU path's
                                    // stats-off 1x1 publish) carries NO shape:
                                    // skip the update and keep the previous
                                    // pair (0x0 pre-first-publish, else the
                                    // last real dims) — the same
                                    // PreviewSurfaceRatio.carriesShape rule
                                    // the ratio chain uses, so the crop
                                    // overlay, the aspect presets and the
                                    // slider ranges never math against 1x1
                                    // (a 16:9 preset on a 4:3 clip would
                                    // otherwise compute h=0.5625, not 0.75).
                                    // A2 (2026-11-23, guide A2): a GPU-served
                                    // frame with an ACTIVE crop must not
                                    // shrink the pair either — its stats
                                    // pack is already CROPPED (the window
                                    // pass maps the crop), so f's dims are
                                    // the CROPPED dims, and the aspect
                                    // presets + the crop overlay keep
                                    // mathing against the UNCROPPED dims.
                                    // The last uncropped pair governs:
                                    // overlay frames (the crop-neutral
                                    // display grade updates as today), prior
                                    // frames, or the documented 0x0
                                    // pre-first-publish degradation. The CPU
                                    // path still captures f pre-crop below,
                                    // as it always did.
                                    if (!servedByGpuWindow || !gpuCropActive) {
                                        if (PreviewSurfaceRatio.carriesShape(f.width, f.height)) {
                                            effectiveDims = RotationState.rotatedDims(
                                                f.width, f.height, currentManualRotationDeg,
                                            )
                                        }
                                    }
                                    // A1 async stats (owner request 2026-11-23,
                                    // guide A1(b).3): an ASYNC frame that
                                    // published the 1x1 miss marker carries NO
                                    // pack — the fence for frame N-1 had not
                                    // signaled yet. KEEP the last published
                                    // frame state (the Compose `frame`, and
                                    // with it the scope compute and the RAW
                                    // histogram index) so the scopes simply
                                    // hold one frame longer — the guide's
                                    // documented one-frame lag, "the Kotlin
                                    // side keeps the last scopes". Everything
                                    // else (the window verdict, the dims, the
                                    // trace line) still publishes: the render
                                    // DID complete.
                                    val keepLastStats = servedByGpuWindow &&
                                        keepsLastPublishedFrame(f.statsMode, f.width, f.height)
                                    if (!keepLastStats) {
                                    // A2 (owner request 2026-11-23, guide A2):
                                    // when the GPU WINDOW served this frame,
                                    // the published pack is ALREADY cropped
                                    // by the window pass — both shapes are
                                    // publish-ready: a cropped/straightened
                                    // request's pack IS the cropped region
                                    // (the stats dims are the cropped dims),
                                    // and a crop-neutral request's pack is
                                    // today's uncropped shape. PublishCrop
                                    // must not crop it again (guide A2's
                                    // own words: resampling the 256-pack
                                    // would be blocky AND duplicated) —
                                    // serve the pack DIRECTLY.
                                    if (servedByGpuWindow) {
                                        frame = f
                                    } else {
                                    // Crop + straighten (RAW-09), OFF the
                                    // main thread (audit fix 2026-11-13): the
                                    // published preview is the GRADED frame
                                    // cut to the crop rect, in the same
                                    // effective space the user sees. The
                                    // native develop renders
                                    // CONTAINER-upright pixels and the
                                    // surface turns only the manual delta,
                                    // so the rect is mapped through the
                                    // delta first. PublishCrop plans the
                                    // fold: the NEUTRAL crop group publishes
                                    // inline (the input buffer by
                                    // reference — no dispatcher hop, the
                                    // pre-fix fast path), an ACTIVE
                                    // crop/straighten resamples on
                                    // Dispatchers.Default and publishes
                                    // under the same generation guard. The
                                    // pump AWAITS the resample, so it stays
                                    // serial: one render, one crop, one
                                    // publish, in sequence order. The CPU
                                    // path keeps this block VERBATIM —
                                    // its own tests stay green untouched.
                                    when (val plan = PublishCrop.plan(
                                        frame = f,
                                        grade = req.grade,
                                        spaceDeltaDeg = currentManualRotationDeg,
                                        sequence = req.sequence,
                                        frameIndex = req.frameIndex,
                                    )) {
                                        is PublishCrop.Plan.PublishInline -> frame = plan.frame
                                        is PublishCrop.Plan.ResampleOffMain -> {
                                            val cropped = PublishCrop.resample(plan.request)
                                            if (PublishCrop.mayPublish(
                                                    plan.request.sequence, lastPublished,
                                                )
                                            ) {
                                                frame = cropped
                                            }
                                        }
                                    }
                                    }
                                    // The rendered frame's index, published
                                    // beside the frame (feature N6): the
                                    // scope compute's RAW fetch asks for
                                    // stats of THIS frame, never guessing
                                    // the index from transport state. An
                                    // async miss keeps the LAST index too
                                    // (inside the same keep-last guard) —
                                    // the RAW fetch stays keyed to the pack
                                    // the scopes still show.
                                    renderedFrameIndex = req.frameIndex
                                    }
                                    // The production render trace line (audit
                                    // M0): ONE line per COMPLETED render, at the
                                    // completion point. out= is the developed
                                    // buffer's own dims (f, pre-crop — the truth
                                    // the native strideFor produced, so a 720
                                    // request on a 4K clip logs 640x360); tnr
                                    // carries the tier gate's DECISION for this
                                    // render (class + tier, the M0 SOURCE fix
                                    // visible in the trace), not the grade's
                                    // toggle; stab only when a gyro log was
                                    // actually attached and carried. M4 commit c:
                                    // backend= names the pipeline that ACTUALLY
                                    // served — gles when the GPU window path
                                    // produced this frame, cpu otherwise (the
                                    // served verdict captured before this fold).
                                    // Debug builds only — no-op in release.
                                    RenderTrace.log(
                                        RenderTrace.Record(
                                            sequence = req.sequence,
                                            frameIndex = req.frameIndex,
                                            renderClass = req.renderClass,
                                            requestedMaxDim = req.maxDim,
                                            outW = f.width,
                                            outH = f.height,
                                            renderMs = renderMs,
                                            grade = req.grade,
                                            temporalNrApplied = req.grade.temporalNr &&
                                                PreviewTiers.temporalNrAppliesAt(
                                                    req.maxDim, req.renderClass,
                                                ),
                                            stabilizationApplied = StabilizationGate.appliedFor(req.grade.stabilization, gyroPath),
                                            servedByGpu = servedByGpuWindow,
                                            // The GPU-path measurement fields
                                            // (trace-dims fix 2026-11-14): out=
                                            // is the published stats pack's
                                            // dims on these frames — these ride
                                            // the PreviewFrame's tail (the
                                            // renderArrayBlocking parse) and
                                            // carry the real dims + per-stage
                                            // spans. Neutral defaults on the
                                            // CPU path (f is the 3-arg form),
                                            // so its line stays byte-identical.
                                            sourceW = f.sourceW,
                                            sourceH = f.sourceH,
                                            developW = f.developW,
                                            developH = f.developH,
                                            surfaceW = f.surfaceW,
                                            surfaceH = f.surfaceH,
                                            statsMode = f.statsMode,
                                            decMs = f.decMs,
                                            cfaMs = f.cfaMs,
                                            upMs = f.upMs,
                                            devMs = f.devMs,
                                            presentMs = f.presentMs,
                                            statsMs = f.statsMs,
                                        )
                                    )
                                    lastRenderFailed = false
                                    errorText = null
                                },
                                onFailure = { e ->
                                    lastRenderFailed = true
                                    errorText = if (PreviewErrors.isOutOfMemory(e)) previewTooLargeText else e.message
                                },
                            )
                            if (BuildConfig.DEBUG && req.renderClass == RenderClass.PLAYBACK && !lastRenderFailed) {
                                playbackTraceHolder[0]?.onPublished(System.nanoTime() / 1000)
                            }
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
                                        renderClass = req.renderClass,
                                        // The follow-up must carry the SAME node chain: without it the full-size
                                        // settle frame rendered chainless and replaced the graded intermediate
                                        // (found with the first editable node chain, 2026-11-25; MCRAW too).
                                        chainWire = req.chainWire,
                                    )
                                )
                            ) {
                                pumpKick.trySend(Unit)
                            }
                        }
                    } catch (c: CancellationException) {
                        // Structured concurrency: a cancelled render (sourceId
                        // change, composition exit) propagates — never recorded
                        // as a failure, never swallowed.
                        throw c
                    } catch (t: Throwable) {
                        // Error subclasses deliberately rethrow (the one
                        // realistic thrower is an OOM from the resample at
                        // 12MP): a pump that hammers an OOM-ing bitmap path
                        // request after request is worse than a dead one, so
                        // an Error unwinds the pump honestly — the finally
                        // below still clears the latch, never the spinner.
                        if (t is Error) throw t
                        // One bad request must not kill the pump: the same
                        // failure surfaces the fold's onFailure arm uses
                        // (the stage/cause/recovery shape the other failure
                        // paths word alike), then the while-loop continues
                        // to the next request.
                        lastRenderFailed = true
                        errorText = "Preview failed (stage: render): " +
                            (t.message ?: t.javaClass.simpleName) +
                            " — recovery: reopen the clip"
                    } finally {
                        // The latch never outlives the request: the normal
                        // end, the catches above, cancellation, and the Error
                        // subclasses they deliberately rethrow all land
                        // here.
                        renderInFlight = false
                    }
                }
            }
        }

        // SCOPE COMPUTE (owner spec PLAY-10, 2026-11-12 §7): the ONE place
        // scope results are derived — strip-gated, OFF the render pump and
        // OFF the UI thread. The pump's publish only swaps `frame`; this
        // effect observes it, so the pump stays a pure render pipeline.
        // Gated on the strip's own state:
        // - CLOSED -> return BEFORE snapshotFlow: `frame` is never even
        //   observed, so a closed scope does ZERO per-frame work (spec:
        //   "Scope kapalıyken hesabı durdur").
        // - The keys are (open, content, waveform mode, sampling, clip):
        //   a mode, content or sampling switch while paused RESTARTS the
        //   effect and snapshotFlow re-emits the CURRENT frame — the
        //   scope matches the last frame immediately, no re-render
        //   needed (spec: after pause/seek/grade change/reset the scope
        //   matches the frame).
        // - collectLatest = latest-wins (spec: "yeni kare gelirse eski
        //   hesap iptal"): a frame published while a compute still runs
        //   cancels it; computes are never queued and results never land
        //   out of publication order.
        // - THROTTLE-LATEST PACING (spec §7: "~10 scope updates/sec during
        //   playback, tuned with measured cost"): each block first waits
        //   out the remainder of the [ScopeComputePeriodMs] window since
        //   the last COMPLETED compute. A newer frame during the wait
        //   cancels the block — killing only a delay — and the new block
        //   re-arms against the same window anchor. So during playback
        //   at most ~10 computes/sec run, ALWAYS on the newest frame,
        //   while the first change after a quiet period (pause, seek,
        //   grade change) computes immediately. Deliberately NOT the
        //   sample() operator: the newest frame must never be dropped,
        //   and the window must anchor to the last compute, not to
        //   collection start.
        // Content, mode and sampling are captured at launch (not re-read
        // mid-flow): this effect instance IS those key values — a switch
        // restarts it, so a mid-flight block can never compute a
        // half-switched combination. The histogram is computed whenever
        // the strip is open (sub-millisecond: strided to
        // FrameHistogram.MAX_SAMPLES), so the Histogram | Waveform
        // selector is a pure UI switch; the waveform pass runs only for
        // the WAVEFORM content, at the user's sampling quality (spec §6:
        // scope-ONLY resolution — the source/preview/export pipeline is
        // untouched). The RAW fetch (feature N6) runs only for the
        // HISTOGRAM content in the RAW mode — it is a native engine
        // round-trip, not a sub-millisecond pass, so it is gated on the
        // combination that draws it, never run speculatively.
        // MEASUREMENT TOOLING (spec §7 "tuned with measured cost"): every
        // compute logs one line to logcat tag "ritim-scope" (mode,
        // sampling, frame size, elapsed ms) and records into the
        // screen's ScopeRateMeter — the trailing-1s count the strip
        // renders as its Hz readout (the meter outlives this effect's
        // restarts; the decay ticker below drains the readout when
        // computes stop). The device pass confirms the pacing and the
        // per-compute cost from either.
        // A3 (2026-11-23, guide §A3): with zebra ON the published pack is
        // STILL the clean frame — the session's draw-3 uZebraOn=0 pin keeps
        // the stripes out of the stats pixels — so NO code change here.
        LaunchedEffect(
            scopePresentation, scopeContent, histogramModeOrdinal,
            waveformModeOrdinal, scopeSamplingOrdinal, sourceId,
        ) {
            // MINI computes too (owner request §2: the mini shows live
            // data) — only OFF stops the work.
            if (scopePresentation == ScopePresentation.OFF) return@LaunchedEffect
            val content = scopeContent
            val histMode = HistogramMode.entries[
                histogramModeOrdinal.coerceIn(0, HistogramMode.entries.lastIndex)
            ]
            val mode = WaveformMode.entries[
                waveformModeOrdinal.coerceIn(0, WaveformMode.entries.lastIndex)
            ]
            val sampling = WaveformScope.Sampling.entries[
                scopeSamplingOrdinal.coerceIn(0, WaveformScope.Sampling.entries.lastIndex)
            ]
            // The pacing anchor: 0 at launch, so the first compute after
            // a (re)start — open, mode, content or sampling switch —
            // runs immediately (the scope matches the current frame
            // right away); only then does the window arm.
            var lastComputeAt = 0L
            snapshotFlow { frame }.collectLatest { f ->
                val since = SystemClock.elapsedRealtime() - lastComputeAt
                if (since < ScopeComputePeriodMs) {
                    delay(ScopeComputePeriodMs - since)
                }
                val startedAt = SystemClock.elapsedRealtime()
                if (f == null) {
                    histogram = FrameHistogram.of(null)
                    waveformRendered = null
                    rawHistogram = null
                } else {
                    histogram = withContext(Dispatchers.Default) {
                        FrameHistogram.of(f.argb)
                    }
                    if (content == ScopeContent.WAVEFORM) {
                        waveformRendered = withContext(Dispatchers.Default) {
                            WaveformDisplay.render(
                                WaveformScope.of(f.argb, f.width, f.height, mode, sampling),
                                mode,
                            )
                        }
                    }
                    if (content == ScopeContent.HISTOGRAM && histMode == HistogramMode.RAW) {
                        // Feature N6: the PRE-DEVELOP sensor-domain
                        // stats, fetched through the session seam (the
                        // same engine thread as the renders; the bridge
                        // reuses the interactive CFA cache — never a
                        // second decode). The index asked is the pump's
                        // OWN publish (renderedFrameIndex), never
                        // transport state. The stale-guard at the
                        // write: collectLatest's cancellation is
                        // cooperative — a fetch whose coroutine was
                        // cancelled still resumes INTO this block, so
                        // the frame index is re-checked before the
                        // stats are shown. A result that lands for a
                        // frame the screen has already moved past is
                        // dropped, never drawn. A failure (or no
                        // session — an encoded clip) leaves the stats
                        // null: the RAW mode shows its honest
                        // unavailability notice, never a fabricated
                        // curve and never the processed histogram in
                        // the sensor domain's place.
                        val asked = renderedFrameIndex
                        if (asked >= 0) {
                            val s = session
                            val outcome = s?.rawHistogram(asked)
                            // The fetch is a suspendCoroutine over the
                            // engine executor — cancellation lands at
                            // the NEXT check, this one: a fetch that
                            // was in flight when a newer frame
                            // published ends HERE (like withContext
                            // ends the waveform's stale block), before
                            // any write and before the Hz record.
                            currentCoroutineContext().ensureActive()
                            // Last line of defense: never draw stats
                            // for a frame the screen has moved past.
                            if (renderedFrameIndex == asked) {
                                rawHistogram = outcome?.getOrNull()
                            }
                        } else {
                            rawHistogram = null
                        }
                    }
                }
                lastComputeAt = SystemClock.elapsedRealtime()
                scopeHz = scopeMeter.record(lastComputeAt)
                Log.d(
                    "ritim-scope",
                    "compute mode=${mode.name} sampling=${sampling.name} " +
                        "frame=${f?.width ?: 0}x${f?.height ?: 0} " +
                        "elapsedMs=${lastComputeAt - startedAt}",
                )
            }
        }

        // The Hz readout's decay: a paused scope must not freeze at its
        // last playback rate. While the strip is open, a slow ticker
        // re-reads the meter — its trailing-1s prune drops computes as
        // they age out, so the readout falls to 0 about a second after
        // the last compute. Cancellation is the off switch: closing the
        // strip or leaving the screen cancels the delay, and the loop
        // with it (the strip is not composed while closed, so the
        // frozen value is never shown).
        LaunchedEffect(scopePresentation) {
            if (scopePresentation == ScopePresentation.OFF) return@LaunchedEffect
            while (true) {
                scopeHz = scopeMeter.rate(SystemClock.elapsedRealtime())
                delay(500)
            }
        }

        // ZEBRA PAINT (owner spec PLAY-11): derives the striped DISPLAY
        // copy of the published frame, OFF the UI thread (the scope
        // compute's pattern). Gated on the toggle — OFF does ZERO
        // per-frame work (the same rule as a closed scope). Keyed to
        // (zebraOn, clip): collectLatest = latest-wins, so a frame
        // published while a paint still runs cancels it; paints are never
        // queued and a striped copy never lands out of publication order.
        // The paint reads the CLEAN published frame and never touches it
        // (ZebraOverlay.paint copies), so the scope compute — which
        // observes the same snapshotFlow — is unaffected either way.
        //
        // A3 (owner request 2026-11-23, guide §A3): while the GPU window
        // serves, the stripes are ALREADY in the displayed pixels — the
        // window pass paints them (WINDOW_FRAGMENT's uZebra* block, crisp
        // at window resolution). The derive is SKIPPED (zebraFrame kept
        // null) for as long as gpuWindowLive holds: the SurfaceView shows
        // the stripes and the Compose Image is suppressed anyway, so a
        // striped 256-px pack copy would be wasted per-frame CPU paint
        // work AND blocky if it ever reached the Image. gpuWindowLive is
        // read INSIDE the snapshotFlow pair (the publish fold writes
        // frame + gpuWindowLive in the SAME snapshot batch), so every
        // transition re-emits atomically: a GPU-live publish derives
        // null, a surface death or CPU fallback repaints the CURRENT
        // frame immediately, and the pure CPU path (window never live)
        // runs today's derive verbatim on every frame.
        LaunchedEffect(zebraOn, sourceId) {
            if (!zebraOn) {
                zebraFrame = null
                return@LaunchedEffect
            }
            snapshotFlow { frame to gpuWindowLive }.collectLatest { (f, gpuLive) ->
                zebraFrame = if (f == null || gpuLive) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        val painted = ZebraOverlay.paint(f.argb, f.width, f.height)
                        if (painted == null) null else PreviewFrame(f.width, f.height, painted)
                    }
                }
            }
        }

        // PLAYBACK LOOP: paced by the clip's REAL MCRAW timestamps. Each
        // tick picks the frame whose pts is closest to the wall-clock
        // elapsed time (drop-late: never a backlog — the wall clock decides
        // which frame is current). Renders go through the same render gate
        // (one running + one pending), at the coarser playback tier. Stops
        // at the last frame and resets the play state (owner request §1).
        // The image-stabilization analysis stands down while this plays (S4).
        DisposableEffect(isPlaying) {
            ImageStabActivity.playing = isPlaying
            onDispose { ImageStabActivity.playing = false }
        }
        // P3 (2026-11-25): one "ritim-trace" summary line per playback run
        // (picked / shown / late / dropped, published-frame gap p50/p95).
        // The holder is shared with the publish site below (a plain array,
        // not state: it must never recompose anything).
        LaunchedEffect(isPlaying, ptsUs) {
            val pts = ptsUs ?: return@LaunchedEffect
            if (!isPlaying || pts.isEmpty()) return@LaunchedEffect
            val startFrame = positionFrame
            val startUs = System.nanoTime() / 1000
            var lastRendered = -1
            val periodUs = if (pts.size > 1) ((pts.last() - pts.first()) / (pts.size - 1)).coerceAtLeast(1L) else 33_333L
            val trace = PlaybackTrace(periodUs)
            if (BuildConfig.DEBUG) playbackTraceHolder[0] = trace
            try {
            while (isPlaying) {
                val elapsed = PtsTimeline.elapsedAt(pts, startFrame, System.nanoTime() / 1000 - startUs)
                val idx = PtsTimeline.frameAt(pts, elapsed)
                if (idx >= 0 && idx != lastRendered) {
                    if (BuildConfig.DEBUG) trace.onPick(idx, elapsed, pts[idx])
                    positionFrame = idx
                    // Playback tier = the user's preview-quality choice
                    // (owner request 2026-11-11b section 3); the default
                    // equals the old fixed PLAYBACK_MAX_DIM. PLAYBACK class
                    // (choppy-preview fix 2026-11-13): the render's class,
                    // not its tier size, feeds the temporal NR gate — at
                    // the HIGH quality setting the tier is 1280 and the old
                    // tier-only gate turned the ~85-107 ms merge on for
                    // every playback frame (~3.65 renders/sec measured);
                    // playback NEVER runs the merge now, the settle render
                    // on pause still does.
                    requestRender(idx, grade, previewQualityEffective.playbackMaxDim, renderClass = RenderClass.PLAYBACK)
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
            } finally {
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("ritim-trace", trace.summary(sourceId.toString(), previewQualityEffective.playbackMaxDim))
                    playbackTraceHolder[0] = null
                }
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
        // AUD-02 gate: only an ATTACHED source plays its audio — DETACHED,
        // REMOVED, and LINKED_EDITED stay silent (the same honesty rule the
        // export applies; LINKED_EDITED's edited recording is not played
        // here — that needs the edited-audio playback, a later milestone).
        // The pause() on the non-ATTACHED path is a no-op when the thread
        // never started, and a real pause when the state changed mid-play.
        LaunchedEffect(isPlaying, audioLinkState) {
            if (isPlaying && audioLinkState == AudioLinkState.ATTACHED) {
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
            gpuPreparing = gpuPreparing,
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
            fps = if (pts != null) PtsTimeline.measuredFps(pts) else 0f,
            // RAW rows (owner request 2026-11-11 section 2/3): MCRAW-only.
            // The sample format is the container's 16-bit sample storage
            // (raw16) — never presented as a sensor bit-depth claim. The white
            // level is the container fallback calibration the session carried
            // from the open; 0 = unknown and the row hides (encoded clips
            // stay 0 on both counts).
            rawSampleFormat = if (sourcePath == SourcePath.MCRAW) "raw16" else null,
            rawWhiteLevel = session?.rawWhiteLevel ?: 0,
        )

        // P3 (2026-11-25): match the display refresh rate to the clip while it
        // PLAYS; released the moment it pauses/stops/leaves (the system picks
        // again). Surface.setFrameRate on API 30+, the window's mode otherwise.
        val displayActivity = run {
            var c: android.content.Context? = androidx.compose.ui.platform.LocalContext.current
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            c as? android.app.Activity
        }
        DisposableEffect(isPlaying, displaySurface, clipInfo.fps) {
            val surf = displaySurface
            // Captured NOW: isPlaying is a delegated state, and the dispose
            // lambda would read its value at dispose time (already false when
            // the run is being torn down BECAUSE playback stopped), so the
            // release was silently skipped (device check 2026-11-25: begin
            // logged, end never).
            val requested = isPlaying && clipInfo.fps > 0f
            if (requested) {
                DisplayRateApply.begin(displayActivity, surf, clipInfo.fps.toDouble())
            }
            onDispose { if (requested) DisplayRateApply.end(displayActivity, surf) }
        }

        // ---- Landscape workspace decision (owner request 2026-11-11d
        // sections 17.1-17.2) ----
        // The pure decisions live in OrientationLayout (JVM-tested):
        // landscape + wide enough engages the two-column workspace, and the
        // split is the responsive 30-40% panel band — never one hardcoded
        // fraction. LocalConfiguration's dp values track the window, and
        // the editor consults them on every recomposition, so the workspace
        // re-splits responsively at any rotation or resize. Fullscreen
        // (section 16) is always the single immersive column, portrait or
        // landscape.
        val configuration = LocalConfiguration.current
        val landscapeTwoColumn = !fullscreen && OrientationLayout.isTwoColumn(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
        )

        // ---- Shared workspace regions ----
        // The preview, transport and panel content compose through these
        // shared lambdas so the portrait single column (section 11) and
        // the landscape two-column Row (section 17.1) lay out the very same
        // composables: a rotation re-lays-out, it never re-creates — the
        // frame, playback position, grade, per-category scroll states and
        // the render session all live in the remember blocks these lambdas
        // capture (section 18).
        val previewRegion: @Composable ColumnScope.() -> Unit = {
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
                    .weight(1f)
                    // The claim math's width input (owner layout-refactor
                    // spec 2026-11-12 §2): the full width a width-bound
                    // video would fill. Measured here — not read from the
                    // surface — so the claim is available before the first
                    // frame and never races the surface's own sizing.
                    .onSizeChanged { previewRegionWidth = it.width },
                contentAlignment = Alignment.Center,
            ) {
                // PLAY-11 zebra: the display swaps to the striped copy
                // while the toggle is on (the paint effect derives it
                // from this same `frame`); the scope compute keeps the
                // clean frame. Since A3 (guide §A3) zebraFrame stays NULL
                // while the GPU window serves (the window pass paints the
                // stripes), so this swap line falls through to the clean
                // frame — nothing blocky ever reaches the Image. One swap
                // line — the whole feature lives in ZebraOverlay + the
                // paint effect + the corner toggle (+ the window pass's
                // zebra block on the GPU path).
                PreviewSurface(
                    frame = if (zebraOn) zebraFrame ?: frame else frame,
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
                    // Zoom settle (owner request 2026-11-12 section 3):
                    // a zoom-changing pinch just ended. The flag follows
                    // the settled transform (back at 1x it drops and the
                    // normal tier policy returns); a gesture that ends
                    // ZOOMED additionally owes the full-quality re-render
                    // — the PAUSE path exactly (SliderTiers.owesZoomSettle
                    // is the pure decision; the callback's own firing
                    // condition supplies its pinchZooming=true premise).
                    // cached: the frame on screen is the same index (the
                    // pinch never moved the position), so the decode is
                    // reused and the settle costs one develop, like pause.
                    onZoomSettled = { settled ->
                        previewZoomed = ZoomPan.isZoomed(settled.zoom)
                        if (SliderTiers.owesZoomSettle(
                                pinchZooming = true,
                                zoomed = previewZoomed,
                            )
                        ) {
                            requestRender(
                                positionFrame, grade,
                                PreviewTiers.PAUSED_MAX_DIM, cached = true,
                                followUpMaxDim = PreviewTiers.SOURCE_MAX_DIM,
                            )
                        }
                    },
                    // ---- GPU window path (M4 commit b) ----
                    // The SurfaceView composes whenever the GPU path is
                    // active (so surfaceCreated fires before the first GPU
                    // render); the Image suppresses only when the window
                    // is really serving (a served frame AND a live
                    // surface). The callback is the SurfaceHolder lifecycle
                    // → the session's window surface: created hands the
                    // Surface over (the session takes ownership at call
                    // entry), destroyed hands null (the destroy signal
                    // always processes). The suspend handoff rides the
                    // screen's scope — the callback is on the UI thread and
                    // must never block on the engine.
                    gpuWindowActive = gpuActive,
                    gpuWindowServing = gpuWindowLive,
                    freezeSurfaceSize = panelDrag.isAnimationRunning,
                    // DEBUG path badge (2026-11-23): reuses the served
                    // verdict the trace's backend= field reads.
                    debugPathBadge = if (RenderTrace.enabled) {
                        // "GPU" = presented through the GPU window; "GPU+bmp" = developed on the GPU
                        // (encoded clips) and presented as a bitmap; "CPU" = developed on the CPU.
                        if (gpuWindowLive) "GPU"
                        else if (com.ritim.ritimcraw.engine.EncodedFrameTrace.gpuDeveloped) "GPU+bmp"
                        else "CPU"
                    } else {
                        null
                    },
                    onGpuSurfaceChanged = { surface ->
                        // Holder liveness alone does not mean EGL accepted
                        // the window. Never hide the CPU image until the
                        // handoff has actually succeeded. Ignore an old
                        // attach completing after a destroy/replacement.
                        displaySurface = surface as? android.view.Surface
                        val generation = ++gpuSurfaceGeneration
                        gpuSurfaceLive = false
                        gpuWindowLive = false
                        scope.launch {
                            val gpu = gpuSession
                            val attached = gpu?.onSurfaceChanged(surface) == true
                            if (generation != gpuSurfaceGeneration) return@launch
                            gpuSurfaceLive = surface != null && attached
                            if (surface != null && !attached) {
                                gpuActive = false
                                gpuPreparing = false
                                val detail = gpu?.lastDetail() ?: "window attachment failed"
                                errorText = "GPU preview fallback: $detail — recovery: the CPU preview path stays the default"
                                Log.d(RenderTrace.TAG, "GPU preview surface fallback: $detail")
                            }
                            // A paused preview also needs a new frame when
                            // its surface appears, disappears or is replaced.
                            if (canRender) {
                                requestRender(
                                    positionFrame, grade,
                                    if (isPlaying) previewQualityEffective.playbackMaxDim
                                    else PreviewTiers.PAUSED_MAX_DIM,
                                )
                            }
                        }
                    },
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
                // Corner controls anchored to the video's REAL rect (owner
                // notes 1+2, 2026-11-12): this anchor Box measures with the
                // SAME previewBounds sizer the surface above uses — same
                // incoming constraints, same
                // PreviewSurfaceRatio.claimedSize call, same ratio chain —
                // so it takes exactly the video rect's size and the
                // wrapper's contentAlignment places it exactly where the
                // video is. The controls' align() below therefore resolves
                // against the video's real corners, never the wrapper's: a
                // height-bounded (narrow) video gets its controls ON its
                // corners instead of floating in the letterbox (note 1's
                // histogram-and-two-controls finding), and the refresh
                // chip rides the video's own BottomEnd corner — the note-2
                // position re-check: with the section-2 fix the preview is
                // wider at every panel state, and the chip now covers the
                // least image possible instead of drifting over the frame
                // from a wrapper-anchored spot. The anchor Box carries no
                // pointer handlers, so the preview's gestures (zoom/pan,
                // before/after hold, the fullscreen chrome tap) flow through
                // it untouched; only the controls themselves consume,
                // exactly as before.
                Box(Modifier.previewBounds(previewDisplayRatio)) {
                    // THE CROP OVERLAY (owner request 2026-11-13 §2): the
                    // handles-on-preview crop, composed inside this anchor
                    // Box so its coordinate space IS the video rect (the
                    // Box measures exactly the video's bounds — the same
                    // previewBounds sizer the surface uses). While the
                    // Crop sub-tab is open the preview renders the
                    // crop-neutral display grade (requestRender's swap),
                    // so the overlay draws the REAL grade's rect over the
                    // UNCROPPED image; leaving the sub-tab (or entering
                    // fullscreen) drops the overlay and the flip effect
                    // restores the real crop. FIRST CHILD, UNDER the
                    // corner controls: the overlay's pointerInput consumes
                    // ONLY handle grabs (everywhere else the event keeps
                    // flowing — the anchor-Box precedent), but its touch
                    // surface covers the whole video rect, so the refresh
                    // chip / scope / zebra / fullscreen buttons must stay
                    // ON TOP to keep their taps (their clickables consume
                    // the down, so the overlay's unconsumed-down hit test
                    // never fires under them; the corner handles stay
                    // grabbable through the rest of their 48dp touch
                    // squares, and any crop smaller than full moves its
                    // corners away from the chips entirely).
                    if (cropOverlayActive) {
                        CropOverlay(
                            rect = CropState.RectF(
                                grade.cropX, grade.cropY,
                                grade.cropW, grade.cropH,
                            ),
                            frameW = effectiveDims.width,
                            frameH = effectiveDims.height,
                            preset = grade.cropAspect,
                            onDragStart = {
                                applyTierEvent(SliderTiers.Event.DragStarted)
                            },
                            onDragFrame = { r ->
                                applyGradeEdit(
                                    grade.copy(
                                        cropX = r.x, cropY = r.y,
                                        cropW = r.w, cropH = r.h,
                                    ),
                                    GradeHistory.SLIDER_GESTURE,
                                )
                            },
                            onDragEnd = { r ->
                                applyGradeEdit(
                                    grade.copy(
                                        cropX = r.x, cropY = r.y,
                                        cropW = r.w, cropH = r.h,
                                    ),
                                    GradeHistory.SLIDER_GESTURE,
                                )
                                applyTierEvent(SliderTiers.Event.DragEnded)
                                gradeHistory.settle()
                            },
                        )
                    }
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
                        // Scope toggle (owner spec PLAY-10, 2026-11-12 §2 +
                        // the manager's design decision): the mirror of the
                        // fullscreen TopEnd toggle — the preview's corner icon
                        // buttons read as one group. Opens/closes the SCOPE
                        // STRIP below the transport (never an overlay on the
                        // image); the strip's own selector picks Histogram or
                        // Waveform. Same 48dp IconButton target, vendored
                        // waveform-trace glyph (material-icons-core 1.7.3 has
                        // no waveform icon), same translucent scrim as the
                        // fullscreen and refresh controls.
                        // TopStart corner stack (spec 2026-11-12 §9-J, no
                        // hardcoded positional dims): the scope and zebra
                        // toggles compose in ONE vertically-stacked column
                        // at the video's real TopStart corner — the zebra
                        // toggle sits BELOW the scope toggle by the stack's
                        // own geometry (the toggle's measured height + the
                        // 8dp gap), never a magic 64dp offset that assumed
                        // the toggle above it was exactly 48dp tall. Both
                        // keep their 48dp IconButton targets and the same
                        // 8dp corner inset as every preview corner control.
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        ) {
                            ScopeToggleButton(
                                // The two-state icon keeps one meaning
                                // whatever the shape: show/hide the scope.
                                // The tri-state transitions live in the
                                // pure rules — this button never cycles
                                // secretly (MINI -> OFF, not MINI ->
                                // STRIP: a cycle would hide the third
                                // state behind a two-state glyph).
                                visible = scopePresentation != ScopePresentation.OFF,
                                onToggle = {
                                    scopePresentation =
                                        ScopePresentationRules.onCornerToggle(scopePresentation)
                                },
                            )
                            // Zebra toggle (owner spec PLAY-11): a peer of
                            // the scope toggle in the same scrimmed corner
                            // group — the clip warning belongs beside the
                            // analysis tools. Same 48dp IconButton target,
                            // vendored diagonal-stripes glyph, same
                            // translucent scrim as every preview corner
                            // control. The 8dp gap is Arrangement-agnostic
                            // (a plain Column stacks edge to edge; the
                            // spacer keeps the two 48dp targets from
                            // touching without hardcoding any position).
                            Spacer(Modifier.height(8.dp))
                            ZebraToggleButton(
                                on = zebraOn,
                                onToggle = { zebraOn = !zebraOn },
                            )
                        }
                        // THE MINI SCOPE OVERLAY (owner request 2026-11-12
                        // §2): the strip shrunk into the preview's BottomStart
                        // corner — the only VIDEO corner no other control
                        // owns (TopStart = this scope toggle, TopEnd =
                        // fullscreen, BottomEnd = refresh; the landscape
                        // panel-reopen chip is CenterEnd but stays on the
                        // WRAPPER — it marks the hidden panel's edge, not a
                        // video corner). Composed ONLY in MINI, inside
                        // the same corner-control gate (hidden with the
                        // fullscreen chrome, like every preview corner
                        // control), reading the same screen state and the
                        // same computed results the strip reads — display-
                        // only by construction. Its tap is the only gesture
                        // it consumes; the preview's gestures flow around
                        // its bounds.
                        if (scopePresentation == ScopePresentation.MINI) {
                            ScopeMiniOverlay(
                                content = scopeContent,
                                histogram = histogram,
                                rawHistogram = rawHistogram,
                                histogramModeOrdinal = histogramModeOrdinal,
                                rawPresentationOrdinal = rawHistogramPresentationOrdinal,
                                waveform = waveformRendered,
                                waveformModeOrdinal = waveformModeOrdinal,
                                midGrayAvailable = grade.tonemap == 0 && grade.lutId == null,
                                midGrayShown = scopeMidGrayShown,
                                statsLive = frame?.let {
                                    PreviewSurfaceRatio.carriesShape(it.width, it.height)
                                } ?: true,
                                onMiniTap = {
                                    scopePresentation =
                                        ScopePresentationRules.onMiniTap(scopePresentation)
                                },
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
                    // the click path are unchanged. OUTSIDE the chrome gate
                    // above: it is the exit path from fullscreen, so it can
                    // never hide with the chrome.
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
                // Panel reopen affordance (owner request 2026-11-11d section
                // 17.3: the hide must stay "discoverable and easy to
                // restore"): in the landscape two-column workspace with the
                // right column hidden, a scrimmed chip on the preview's right
                // edge brings the adjustment panel back. Same scrimmed
                // IconButton group as the corner controls — one control
                // language; CenterEnd so it sits where the panel's edge was.
                if (landscapeTwoColumn && !panelVisible) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(8.dp),
                    ) {
                        IconButton(
                            onClick = { panelVisible = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_panel_show),
                                contentDescription = stringResource(R.string.editor_panel_show),
                            )
                        }
                    }
                }
            }
        }

        val transportRegion: @Composable () -> Unit = {
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
                            SliderTiers.tierFor(
                                dragActive, previewQualityEffective.scrubMaxDim,
                                zoomed = previewZoomed,
                            ),
                            cached = true,
                            renderClass = if (dragActive) RenderClass.DRAG else RenderClass.SETTLE,
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
            // AUD-02 honesty row: a non-ATTACHED source shows its state
            // under the transport (the user sees WHY the preview is silent
            // — DESIGN.md's honest-state language, minimal). ATTACHED (the
            // default) composes NOTHING, so the default layout is
            // byte-identical and the panel anchor math never shifts for
            // an attached clip. The onSizeChanged feeds the anchor math
            // (§11.3) with the same measured-not-assumed rule as the
            // transport row above.
            if (audioLinkState != AudioLinkState.ATTACHED) {
                Text(
                    text = stringResource(audioLinkStateLabel(audioLinkState)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { audioStateRowHeight = it.height }
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            }
        }

        // The scope strip region (owner spec PLAY-10, 2026-11-12 §2): below
        // the transport, above the panel — in BOTH layouts (the landscape
        // branch places it inside the preview column; the portrait branch
        // stacks it in the main column). Never in fullscreen: the immersive
        // preview owns the screen and the strip returns with the workspace
        // (the open flag survives, so exiting fullscreen restores it).
        // onSizeChanged feeds the anchor math (§11.3): the strip's height
        // varies with font scale, so it is measured, never assumed. The
        // zeroing effect: leaving composition fires NO onSizeChanged, so a
        // closed/hidden strip must actively reset the measurement —
        // otherwise a stale height would keep shrinking the free region
        // after close. Closing returns the space (spec §2: closing
        // "kapatıldığında kapladığı alan geri kazanılsın"): the gates below
        // keep scopeStripHeightPx at 0 whenever the strip is not composed,
        // the anchors re-measure to the taller free region and the panel
        // grows back.
        val scopeRegion: @Composable () -> Unit = {
            // The honesty gate, derived LIVE from the grade: the mid-gray
            // line is drawable only while the transform is the bare
            // Rec.709 OETF (tonemap off, no LUT) — the moment a curve or
            // cube rewrites it, the reference vanishes instead of lying.
            // Derived ONCE for both scope shapes (the strip below and
            // the mini inside the preview both read it).
            val midGrayAvailable = grade.tonemap == 0 && grade.lutId == null
            // The GPU path's stats-off placeholder (1x1) carries no pixels
            // for the scopes; null (no frame yet) is not that case.
            val scopeStatsLive = frame?.let {
                PreviewSurfaceRatio.carriesShape(it.width, it.height)
            } ?: true
            // THE STRIP: only in STRIP and never in fullscreen (the
            // immersive preview owns the screen; the strip returns with
            // the workspace — the state survives, so exiting fullscreen
            // restores it). The graph-tap shrink rides the strip's own
            // graph node via the pure rules.
            if (scopePresentation == ScopePresentation.STRIP && !fullscreen) {
                ScopeStrip(
                    content = scopeContent,
                    onContentChange = { scopeContent = it },
                    onClose = {
                        scopePresentation = ScopePresentationRules.onClose(scopePresentation)
                    },
                    histogram = histogram,
                    rawHistogram = rawHistogram,
                    histogramModeOrdinal = histogramModeOrdinal,
                    onHistogramModeChange = { histogramModeOrdinal = it },
                    rawPresentationOrdinal = rawHistogramPresentationOrdinal,
                    onRawPresentationChange = { rawHistogramPresentationOrdinal = it },
                    waveform = waveformRendered,
                    waveformModeOrdinal = waveformModeOrdinal,
                    onWaveformModeChange = { waveformModeOrdinal = it },
                    samplingOrdinal = scopeSamplingOrdinal,
                    onSamplingChange = { scopeSamplingOrdinal = it },
                    hz = scopeHz,
                    midGrayAvailable = midGrayAvailable,
                    midGrayShown = scopeMidGrayShown,
                    onMidGrayChange = { scopeMidGrayShown = it },
                    onGraphTap = {
                        scopePresentation =
                            ScopePresentationRules.onGraphTap(scopePresentation)
                    },
                    modifier = Modifier.onSizeChanged { scopeStripHeight = it.height },
                    // Landscape two-column: one control row + a shorter
                    // graph, so the strip never pushes the preview away.
                    compact = landscapeTwoColumn,
                    statsLive = scopeStatsLive,
                )
            }
        }
        LaunchedEffect(scopePresentation, fullscreen) {
            if (scopePresentation != ScopePresentation.STRIP || fullscreen) {
                scopeStripHeight = 0
            }
        }

        // The scroll state of the body the panel is CURRENTLY showing
        // (2026-11-13 segmentation plan): the main category's own state
        // on a plain tab, or the ACTIVE SUB-TAB's state when the
        // category has segments — only the selected body scrolls, so
        // Balance/Wheels/Look and Crop/Stabilize each keep their own
        // offset. Resolved HERE (not inside EditorPanelSheet) because
        // both the landscape and the portrait call sites need it and
        // the sanitizers need the screen's showColorTools /
        // showStabilization decisions; the sheet stays segment-agnostic
        // and just scrolls the state it is handed.
        val activePanelScrollState =
            if (tab == EditorCategories.COLOR) {
                colorSubTabScrollStates.getValue(
                    EditorCategories.sanitizeColorSubTab(
                        colorSubTab,
                        EditorPreviewLogic.showColorTools(sourcePath),
                    ),
                )
            } else if (tab == EditorCategories.FRAME) {
                frameSubTabScrollStates.getValue(
                    EditorCategories.sanitizeFrameSubTab(
                        frameSubTab,
                        EditorPreviewLogic.showStabilization(sourcePath),
                    ),
                )
            } else {
                categoryScrollStates.getValue(tab)
            }

        // The pinned sub-tab row of the main tab the panel is CURRENTLY
        // showing (segmentation plan: "sub-tab row fixed at panel top;
        // only the selected body scrolls"), or null when the tab has no
        // segments (Light, Detail). Resolved HERE — like the scroll
        // state — so the portrait and the landscape call sites hand the
        // sheet the SAME slot and the sheet stays segment-agnostic; both
        // read the sanitized selection, so the row and the body can
        // never disagree about which segment is open. The Frame row
        // composes nothing on the encoded path (Crop is the only
        // segment there — see FrameSubTabRow), so the body stands alone
        // exactly like Detail's.
        val pinnedSubTabRow: (@Composable () -> Unit)? = when (tab) {
            EditorCategories.COLOR -> {
                {
                    ColorSubTabRow(
                        subTab = EditorCategories.sanitizeColorSubTab(
                            colorSubTab,
                            EditorPreviewLogic.showColorTools(sourcePath),
                        ),
                        showColorTools = EditorPreviewLogic.showColorTools(sourcePath),
                        onSubTabChange = { colorSubTab = it },
                    )
                }
            }
            EditorCategories.FRAME -> {
                {
                    FrameSubTabRow(
                        subTab = EditorCategories.sanitizeFrameSubTab(
                            frameSubTab,
                            EditorPreviewLogic.showStabilization(sourcePath),
                        ),
                        showStabilization = EditorPreviewLogic.showStabilization(sourcePath),
                        onSubTabChange = { frameSubTab = it },
                    )
                }
            }
            else -> null
        }

        // Slider double-tap: the thumb resets every slider as ONE undo step; both
        // resets collapse the double-tap's own tap-to-position jumps (GradeHistory).
        val sliderResetActions = remember(gradeHistory) {
            SliderResetActions(
                resetAll = { applyGradeEdit(SliderSteps.resetAll(grade)) },
                gestureBase = { gradeHistory.markGestureBase() },
                resetScope = { block -> gradeHistory.resetScope(block) },
            )
        }
        val panelRegion: @Composable () -> Unit = {
          androidx.compose.runtime.CompositionLocalProvider(
            LocalSliderResetActions provides sliderResetActions,
          ) {
            when (tab) {
                EditorCategories.LIGHT -> LightPanel(
                    grade = grade,
                    onChange = { g ->
                        // Slider-path edit: records with the drag cause so
                        // one finger drag is ONE undo step (feature N7);
                        // the tier wiring inside applyGradeEdit matches the
                        // old inline code exactly (mid-drag frames render
                        // immediately at the low tier through the cached
                        // path; the debounce effect's trailing edge only
                        // covers a finger PAUSE mid-drag).
                        applyGradeEdit(g, GradeHistory.SLIDER_GESTURE)
                    },
                    onDragActiveChange = { active ->
                        applyTierEvent(
                            if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                        )
                        // Drag end closes the history's open entry, so
                        // the NEXT gesture pushes a fresh undo step
                        // (feature N7; a tap is a zero-length drag, its
                        // Start+Stop pair closes the entry it opened).
                        if (!active) gradeHistory.settle()
                    },
                    // Double-tap resets are discrete steps (feature N7):
                    // their own undo entry, never coalesced into a drag.
                    onReset = { g ->
                        applyGradeEdit(g)
                    },
                    showRecovery = EditorPreviewLogic.showRawStageToggles(sourcePath),
                )
                EditorCategories.COLOR -> ColorPanel(
                    grade = grade,
                    // The sanitized saved sub-tab (plan: each main tab
                    // remembers its last sub-tab): the pinned row and this
                    // body read the same value, so they can never disagree
                    // about which segment is open — and a Wheels selection
                    // saved on an MCRAW clip falls back to Balance when the
                    // same editor opens an encoded clip (the gate is pure,
                    // JVM-tested in EditorCategoriesTest).
                    subTab = EditorCategories.sanitizeColorSubTab(
                        colorSubTab,
                        EditorPreviewLogic.showColorTools(sourcePath),
                    ),
                    // The CFA-stage toggles (Highlight Reconstruction, Keep
                    // Vignetting) exist only on the MCRAW path; encoded
                    // clips keep As Shot + every slider (pure decision in
                    // EditorPreviewLogic.showRawStageToggles).
                    showRawStageToggles = EditorPreviewLogic.showRawStageToggles(sourcePath),
                    // The color wheel + split tone section (COL-13) is
                    // MCRAW-only too, for the same honesty reason: the
                    // encoded path takes the same Grade but the bridge
                    // ignores the color-package fields there, so the
                    // section would be dead controls (pure decision in
                    // EditorPreviewLogic.showColorTools).
                    showColorTools = EditorPreviewLogic.showColorTools(sourcePath),
                    nodes = nodes,
                    selectedNode = selectedNode,
                    onSelectNode = { selectedNode = it },
                    onNodesChange = { applyNodesEdit(it) },
                    onChange = { g ->
                        // Same slider-path recording as LightPanel: the
                        // machine is shared, so which tab the drag
                        // started on does not matter.
                        applyGradeEdit(g, GradeHistory.SLIDER_GESTURE)
                    },
                    onDragActiveChange = { active ->
                        applyTierEvent(
                            if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                        )
                        if (!active) gradeHistory.settle()
                    },
                    // RAW toggles (As Shot, the reconstruction/vignetting
                    // switches) are not drags: they settle IMMEDIATELY at
                    // the full tier (SliderTiers.GradeEdited with no drag
                    // active), not through the debounce — and they record
                    // as DISCRETE history steps (null cause, feature N7).
                    onToggleChange = { g ->
                        applyGradeEdit(g)
                    },
                    // Double-tap resets are discrete steps (feature N7):
                    // their own undo entry, never coalesced into a drag.
                    onReset = { g ->
                        applyGradeEdit(g)
                    },
                    // ---- Look body wiring (COL-16/COL-07, moved from
                    // DetailPanel with the routes untouched) ----
                    lutEntries = lutEntries,
                    lutImportError = lutImportError,
                    onImportLut = { lutPicker.launch(arrayOf("*/*")) },
                    onRemoveLut = onRemoveLut,
                    // "Edit interpretation" arms the sheet with the record;
                    // Confirm runs AppNav's bindInterp (a NEW revision).
                    onEditLut = { editingLut = it },
                    onLutSelect = { g ->
                        // A LUT selection is a discrete history step (null
                        // cause, feature N7).
                        applyGradeEdit(g)
                    },
                    onTonemapChange = { g ->
                        // The tonemapping choice is not a drag: it settles
                        // IMMEDIATELY at the full tier (GradeEdited with no
                        // drag active), like the RAW toggles — a discrete
                        // history step (null cause, feature N7).
                        applyGradeEdit(g)
                    },
                )
                EditorCategories.DETAIL -> DetailPanel(
                    grade = grade,
                    // Temporal NR merges neighboring CFA frames, so the
                    // group shows only on MCRAW clips (pure decision in
                    // EditorPreviewLogic.showTemporalNr; the encoded path
                    // takes-and-ignores the temporal fields).
                    showTemporalNr = EditorPreviewLogic.showTemporalNr(sourcePath),
                    onChange = { g ->
                        // Same slider-path recording as the other tabs:
                        // mid-drag grade steps render immediately at the
                        // low tier through the cached path.
                        applyGradeEdit(g, GradeHistory.SLIDER_GESTURE)
                    },
                    onDragActiveChange = { active ->
                        applyTierEvent(
                            if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                        )
                        if (!active) gradeHistory.settle()
                    },
                    // The Temporal NR switch is not a drag either: it
                    // settles IMMEDIATELY at the full tier, like the
                    // RAW toggles — a discrete
                    // history step (null cause, feature N7).
                    onToggleChange = { g ->
                        applyGradeEdit(g)
                    },
                    // Double-tap resets are discrete steps (feature N7):
                    // their own undo entry, never coalesced into a drag.
                    onReset = { g ->
                        applyGradeEdit(g)
                    },
                )
                EditorCategories.FRAME -> FramePanel(
                    grade = grade,
                    // The sanitized saved sub-tab (plan: each main tab
                    // remembers its last sub-tab): the pinned row and
                    // this body read the same value, so they can never
                    // disagree about which segment is open — and a
                    // Stabilize selection saved on an MCRAW clip falls
                    // back to Crop when the same editor opens an encoded
                    // clip (the gate is pure, JVM-tested in
                    // EditorCategoriesTest).
                    subTab = EditorCategories.sanitizeFrameSubTab(
                        frameSubTab,
                        EditorPreviewLogic.showStabilization(sourcePath),
                    ),
                    // ---- Stabilization wiring (gyro Phase 5, moved from
                    // DetailPanel with the routes untouched) ----
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
                    stabZoom = stabZoom,
                    // The sync-refine switch (M5 commit b) is a discrete
                    // edit like the other toggles — it settles immediately
                    // and the probe effect re-runs on the flip (the plan
                    // cache invalidation re-builds under the new flag).
                    syncRefine = syncRefine,
                    onSyncRefineChange = { enabled -> syncRefine = enabled },
                    gyroName = gyroNameFor(gyroId),
                    containerFocalMm = containerFocalMm,
                    gyroImportError = gyroImportError,
                    onAttachGyro = { gyroPicker.launch(arrayOf("*/*")) },
                    onRemoveGyro = onRemoveGyro,
                    // ---- Crop + straighten wiring (RAW-09, moved from
                    // DetailPanel with the routes untouched) ----
                    // The UNCROPPED effective dims from the publish fold:
                    // presets and honest W/H ranges math against this
                    // pair, never the cropped `frame` dims.
                    cropFrameW = effectiveDims.width,
                    cropFrameH = effectiveDims.height,
                    onChange = { g ->
                        // Same slider-path recording as the other tabs:
                        // mid-drag grade steps render immediately at the
                        // low tier through the cached path.
                        applyGradeEdit(g, GradeHistory.SLIDER_GESTURE)
                    },
                    onDragActiveChange = { active ->
                        applyTierEvent(
                            if (active) SliderTiers.Event.DragStarted else SliderTiers.Event.DragEnded
                        )
                        if (!active) gradeHistory.settle()
                    },
                    // The strength/zoom/trim/focal sliders drag through
                    // onChange/onDragActiveChange above; the switch, the
                    // mode and the gyro attach/remove choices are not
                    // drags: they settle IMMEDIATELY at the full tier,
                    // like the RAW toggles — discrete history steps
                    // (null cause, feature N7).
                    onToggleChange = { g ->
                        applyGradeEdit(g)
                    },
                    // The aspect choice and the group reset are not
                    // drags either: same immediate full-tier settle,
                    // discrete history steps (null cause, feature N7).
                    onCropEdit = { g ->
                        applyGradeEdit(g)
                    },
                    // Quarter-turn rotate (owner request 2026-11-13 §1):
                    // the screen's existing route — AppNav's
                    // RotationState.applyManual + setManualRotation
                    // persistence. MediaSource state, not grade state:
                    // no gradeHistory involvement, one discrete persisted
                    // edit per tap.
                    onRotate = onRotate,
                )
            }
          }
        }

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
                    exportRate = exportRate,
                    exportEnabled = sourceFd >= 0,
                    encodedSource = sourcePath == SourcePath.ENCODED,
                    clipInfo = clipInfo,
                    // The export sheet's ladder composes the manual
                    // rotation (the exporter's own chain) so its chips
                    // show the size the encode will really produce.
                    manualRotationDeg = manualRotationDeg,
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
                        // Paste/preset applies are DISCRETE history steps
                        // (null cause, feature N7): one apply, one undo.
                        applyGradeEdit(g)
                    },
                    // ---- Grade undo/redo (feature N7) ----
                    // The buttons are gated by the history's snapshot
                    // reads (reactive); the callbacks apply the popped
                    // grade WITHOUT recording, so undo never clears the
                    // redo stack. Persistence and the live preview follow
                    // through the same onGradeChange path every grade
                    // write takes.
                    canUndo = gradeHistory.canUndo,
                    canRedo = gradeHistory.canRedo,
                    onUndo = {
                        gradeHistory.undoSnapshot()?.let { applyRestoredSnapshot(it) }
                    },
                    onRedo = {
                        gradeHistory.redoSnapshot()?.let { applyRestoredSnapshot(it) }
                    },
                    presets = presets,
                    onSavePreset = onSavePreset,
                    onRemovePreset = onRemovePreset,
                )
            }
            // ---- Workspace branch (owner request 2026-11-11d sections 11
            // vs 17.1-17.2, category rail owner layout-refactor spec
            // 2026-11-12 section 5) ----
            // Portrait — and every fullscreen (section 16), whatever the
            // orientation — keeps the single column: the flexing preview
            // over the transport, the draggable sheet below (section 11).
            // Landscape two-column: the preview + transport stack in the
            // LEFT column (section 17.2: the transport sits under the
            // preview), the adjustment panel becomes the full-height middle
            // column (section 17.1's responsive 30-40% band, independently
            // scrollable), and the Light/Color/Detail/Frame navigation
            // moves to the compact vertical FAR-RIGHT rail (layout-refactor spec
            // section 5 — it REVERSES the old section 17.1 note: the
            // bottom bar is portrait-only now, never a wide landscape
            // bottom row). Both navs read the same shared `tab`.
            if (landscapeTwoColumn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // LEFT column: preview over transport. The width is
                    // OrientationLayout's responsive preview share — the
                    // complement of the panel band (60-70%), or the FULL
                    // width while the panel is hidden (section 17.3: the
                    // preview expands horizontally, never vertically, in
                    // the landscape workspace).
                    Column(
                        Modifier
                            .weight(
                                OrientationLayout.previewWidthFraction(
                                    panelVisible = panelVisible,
                                    widthDp = configuration.screenWidthDp,
                                    heightDp = configuration.screenHeightDp,
                                ),
                            )
                            .fillMaxHeight(),
                    ) {
                        previewRegion()
                        transportRegion()
                        // The scope strip stays with the preview it
                        // describes (the workspace's preview column), the
                        // panel column keeps its full height.
                        scopeRegion()
                    }
                    // RIGHT column: the adjustment panel, full height and
                    // independently scrollable (section 17.2) — the same
                    // sheet composable in landscape mode: no drag handle
                    // (section 17.3 does not force the portrait's vertical
                    // panel dragging here), the same resolved scroll state,
                    // the same panel content. The header row
                    // carries section 17.3's hide affordance.
                    if (panelVisible) {
                        EditorPanelSheet(
                            dragState = panelDrag,
                            scrollState = activePanelScrollState,
                            modifier = Modifier
                                .weight(
                                    OrientationLayout.panelWidthFraction(
                                        configuration.screenWidthDp,
                                    ),
                                )
                                .fillMaxHeight(),
                            landscape = true,
                            header = {
                                IconButton(
                                    onClick = { panelVisible = false },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_panel_hide),
                                        contentDescription = stringResource(R.string.editor_panel_hide),
                                    )
                                }
                            },
                            // The active tab's pinned sub-tab row
                            // (segmentation plan): fixed at the panel top
                            // in the landscape column too — the row is
                            // layout-agnostic, only the sheet's own
                            // chrome differs. The slot is resolved once
                            // (with the scroll state) so both call sites
                            // hand the sheet the same row.
                            pinnedContent = pinnedSubTabRow,
                        ) {
                            panelRegion()
                        }
                    }
                    // FAR-RIGHT column: the compact vertical category
                    // rail (layout-refactor spec 2026-11-12 section 5 —
                    // it REVERSES the old section 17.1 note that kept
                    // the bottom bar in landscape). The rail is the
                    // ONLY navigation in the two-column workspace, so
                    // it composes in EVERY panel state — including
                    // while the panel is hidden — exactly like the
                    // portrait bar it replaces (navigation stays
                    // reachable, section 10.1's contract carried into
                    // landscape). A tap while the panel is hidden
                    // reopens it: the landscape analog of section 10.3's
                    // collapsed-tap rule (selecting a category reveals
                    // the controls). The width is OrientationLayout's
                    // measured band value — NEVER a hardcoded dp — and
                    // it is a FIXED column, so the weighted preview and
                    // panel columns to its left keep sharing the
                    // remaining space in the same 60-70/30-40 band.
                    EditorCategoryRail(
                        selected = tab,
                        onCategoryTap = { category ->
                            tab = category
                            panelVisible = true
                        },
                        modifier = Modifier
                            .width(
                                OrientationLayout.railWidthDp(
                                    configuration.screenWidthDp,
                                    configuration.screenHeightDp,
                                ).dp,
                            )
                            .fillMaxHeight(),
                    )
                }
            } else {
                previewRegion()
                transportRegion()
                // The scope strip: below the preview and transport, above
                // the draggable panel (spec §2's portrait placement).
                scopeRegion()
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
            // takes the flex space instead. In the landscape two-column
            // workspace (sections 17.1-17.2) the panel is the RIGHT column
            // composed in the Row above, so this draggable sheet only
            // composes in the single column.
            if (!fullscreen && !landscapeTwoColumn) {
                EditorPanelSheet(
                    dragState = panelDrag,
                    scrollState = activePanelScrollState,
                    // NaN guard: the offset is uninitialized until the
                    // first updateAnchors lands (getOffset returns NaN
                    // before then), so the first frame composes the
                    // sheet at the collapsed-strip height and the
                    // anchors effect snaps it to the restored state
                    // synchronously on the same first layout pass
                    // (the trimmed 36dp strip, owner add-on
                    // 2026-11-12).
                    // The offset is read in the LAYOUT phase (not composition), so an animating
                    // panel re-measures its own subtree per frame instead of recomposing the screen.
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val h = if (panelDrag.anchors.size == 0) PanelCollapsedHeight.roundToPx()
                            else panelDrag.requireOffset().roundToInt().coerceAtLeast(0)
                            val hh = h.coerceIn(constraints.minHeight, constraints.maxHeight.coerceAtLeast(constraints.minHeight))
                            val placeable = measurable.measure(constraints.copy(minHeight = hh, maxHeight = hh))
                            layout(placeable.width, hh) { placeable.place(0, 0) }
                        },
                    // The active tab's pinned sub-tab row (segmentation
                    // plan): fixed at the panel top, above the scrolling
                    // body — the row's natural height comes out of the
                    // scroll region's share inside the sheet, NEVER out of
                    // the panel's, so switching between main tabs with and
                    // without a sub-tab row cannot move the panel height
                    // (the stable-geometry invariant). Clipped away in the
                    // collapsed strip together with the body below it. The
                    // slot is resolved once (with the scroll state) so
                    // both call sites hand the sheet the same row.
                    pinnedContent = pinnedSubTabRow,
                ) {
                    panelRegion()
                }
            }
            // Fixed Light/Color/Detail/Frame bottom navigation (owner
            // request 2026-11-11d §10 + the 2026-11-13 segmentation
            // plan): OUTSIDE the draggable panel, always the
            // editor's LAST row, so it stays visible and tappable in
            // every panel state. The current category stays selected
            // while collapsed (§10.3); a tap while collapsed opens the
            // panel to its normal editing position (§10.3), a tap while
            // open only switches the category — the panel height never
            // moves on a category switch. The pure decisions live in
            // PanelSheet (panelAfterCategoryTap) and EditorPreviewLogic
            // nothing here re-derives. Hidden in fullscreen (owner
            // request section 2). PORTRAIT-ONLY since the layout-refactor
            // spec (2026-11-12 section 5): in the two-column workspace
            // the same categories moved to the FAR-RIGHT vertical rail,
            // and BOTH navs read this same shared `tab` (section 6) so
            // the selection survives every rotation. navHeight goes
            // stale in landscape (the bar leaves the composition, so
            // onSizeChanged never fires there) — harmless: the ONLY
            // consumer of navHeight is the portrait anchor math for the
            // draggable sheet, which never composes in the two-column
            // workspace. The retained value is already correct anyway:
            // the M3 bar's height is a fixed token (80dp), so it does
            // not depend on orientation, and the bar's onSizeChanged
            // re-fires on re-entry into the composition.
            if (!fullscreen && !landscapeTwoColumn) {
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
                            // §10.3's open stays at NORMAL: flagged so the
                            // swipe-up redirect observer above never
                            // re-targets this PROGRAMMATIC open toward
                            // EXPANDED (the flag is consumed by the very
                            // next animation the observer sees).
                            panelOpenByTap = true
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

    // ---- LUT interpretation sheet (owner request 2026-11-13) ----
    // Two open paths, one sheet. IMPORT: the SAF picker armed it with the
    // picked uri + fallback name; Confirm runs the REAL import (AppNav:
    // LutStore.import + the native probe as validation) under the
    // confirmed Name + interpretation — and clears the armed pick, so a
    // second Confirm can never double-import. EDIT: a library row's "Edit
    // interpretation" armed it with the record; Confirm hands the new name
    // + interpretation to onEditLutInterp (AppNav: bindInterp — a NEW
    // binding revision, never a mutation). Both paths' Cancel/dismiss
    // leave NO record and clear the armed state.
    if (pendingLutUri != null) {
        LutInterpSheet(
            initialName = pendingLutName,
            // The sheet opens on the LEGACY preset — the tap-through
            // default that keeps the exact previous pipeline for a user
            // who confirms without engaging with the fields.
            initial = LutInterpK.legacy(),
            onConfirm = { name, interp ->
                val uri = pendingLutUri
                pendingLutUri = null
                if (uri != null) {
                    onImportLut(uri, name, interp).fold(
                        onSuccess = { lutImportError = null },
                        onFailure = { e ->
                            lutImportError = e.message ?: "LUT import failed"
                        },
                    )
                }
            },
            onDismiss = { pendingLutUri = null },
        )
    }
    editingLut?.let { editing ->
        LutInterpSheet(
            initialName = editing.name,
            initial = editing.interp,
            onConfirm = { name, interp ->
                editingLut = null
                onEditLutInterp(editing, name, interp)
            },
            onDismiss = { editingLut = null },
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


/**
 * The LUT interpretation sheet (owner request 2026-11-13): the dialog
 * behind BOTH the import flow and "Edit interpretation" on a library
 * record. PresetNameDialog style — a ModalBottomSheet with a titleSmall
 * title — plus the four single-choice row groups the managed model
 * needs.
 *
 * TWO SELECTABLE WINDOWS (owner device feedback 2026-11-13: "source ve
 * transform iki farklı window olarak sunulmalı" — the one long list
 * overwhelmed the device, so the option-heavy operation splits into a
 * "Source | Transform" segmented toggle INSIDE this one sheet; the house
 * pattern, never two stacked dialogs). The pure split is
 * [LutInterpLayout]: SOURCE carries the preset suggestion plus the
 * input encoding (what the cube READS); TRANSFORM carries the output
 * encoding plus the role (what the cube DOES). The name field, the
 * honesty hint and the Confirm/Cancel row belong to NEITHER window —
 * they frame the whole record and stay above/below the toggle, visible
 * in both.
 *
 * HONESTY CONTRACT: the app does NOT detect a cube's color encoding, and
 * this sheet never claims it did. The preset row is a SUGGESTION that
 * fills the fields (the common real-world authoring spaces); the hint
 * line says so in plain words. The user CONFIRMS the interpretation;
 * Legacy leaves the exact pre-managed pipeline (the stage-24 path,
 * byte-identical).
 *
 * The preset row and the four field rows are one control seen two ways:
 * picking a preset OVERWRITES the fields with its interpretation, and
 * editing any field snaps the preset row to the matching name (or
 * Custom when nothing matches) — [LutInterpPresets.matchingPreset]. A
 * hand-built interpretation that happens to equal a preset shows that
 * preset's name, never a false "Custom". The preset seeds BOTH windows'
 * fields from its Source home, so the windows are two views of ONE
 * interpretation, never two separate edits.
 *
 * CANCEL LEAVES NO RECORD: the sheet only hands [onConfirm] the finished
 * name and interpretation on the Confirm button; dismissing (scrim,
 * back, or Cancel) calls nothing — the caller's store is untouched,
 * exactly like the preset-name dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LutInterpSheet(
    initialName: String,
    initial: LutInterpK,
    onConfirm: (name: String, interp: LutInterpK) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var interp by remember { mutableStateOf(initial) }
    // The selected window (owner feedback 2026-11-13): the sheet opens on
    // SOURCE — the preset suggestion's home — and the toggle switches the
    // field rows below; the shared rows (name, hint, Confirm/Cancel) never
    // move.
    var window by remember { mutableStateOf(LutInterpLayout.INITIAL_WINDOW) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Each window is still far taller than the viewport (five preset
        // rows or a full gamut+transfer ladder), so the whole body scrolls
        // as one column.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        Text(
            stringResource(R.string.editor_lut_interp_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // The display name (import: the picked file's fallback; edit: the
        // record's current name). The user's text is stored/displayed
        // verbatim — never localized (the PresetNameDialog rule). Shared:
        // the record's identity, visible in both windows.
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.editor_lut_interp_name_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // The honesty hint: the app cannot detect the encoding; the user
        // confirms. Shared: it frames every choice in BOTH windows.
        Text(
            stringResource(R.string.editor_lut_interp_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // ---- The window toggle (owner feedback 2026-11-13): one
        // SingleChoiceSegmentedButtonRow, one label per button (the E3
        // lesson — a SegmentedButton centers all its content at the same
        // spot, so a button carries ONLY its label). The display order is
        // the pipeline's own order: Source | Transform.
        CompactSegmentedRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            LutInterpLayout.WINDOW_ORDER.forEachIndexed { index, w ->
                Segment(
                    selected = window == w,
                    onClick = { window = w },
                    label = stringResource(lutWindowLabel(w)),
                )
            }
        }
        // ---- SOURCE window: the preset suggestion + the input encoding
        // (what the cube READS). The pure placement is
        // LutInterpLayout.hasPresetRow/hasInputRows.
        if (LutInterpLayout.hasPresetRow(window)) {
            // Preset: one row, five choices. Picking one OVERWRITES the
            // fields (both windows) with the preset's interpretation; the
            // row's own selection is the matching-name rule (a hand-edited
            // field set that equals a preset shows that preset).
            Text(
                stringResource(R.string.editor_lut_interp_preset),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LutInterpPreset.values().forEach { p ->
                LutInterpChoiceRow(
                    label = stringResource(lutPresetLabel(p)),
                    selected = LutInterpPresets.matchingPreset(interp) == p,
                    onClick = {
                        // Custom starts from the legacy defaults: every
                        // field is then the user's explicit pick.
                        interp = LutInterpPresets.interpOf(p)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (LutInterpLayout.hasInputRows(window)) {
            // Input encoding: gamut rows then transfer rows.
            Text(
                stringResource(R.string.editor_lut_interp_input),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LutGamut.values().forEach { g ->
                LutInterpChoiceRow(
                    label = stringResource(lutGamutLabel(g)),
                    selected = interp.input.gamut == g,
                    onClick = { interp = interp.copy(input = interp.input.copy(gamut = g)) },
                )
            }
            LutTransfer.values().forEach { t ->
                LutInterpChoiceRow(
                    label = stringResource(lutTransferLabel(t)),
                    selected = interp.input.transfer == t,
                    onClick = { interp = interp.copy(input = interp.input.copy(transfer = t)) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        // ---- TRANSFORM window: the output encoding + the role (what the
        // cube DOES). The pure placement is
        // LutInterpLayout.hasOutputRows/hasRoleRow.
        if (LutInterpLayout.hasOutputRows(window)) {
            // Output encoding: gamut rows then transfer rows.
            Text(
                stringResource(R.string.editor_lut_interp_output),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LutGamut.values().forEach { g ->
                LutInterpChoiceRow(
                    label = stringResource(lutGamutLabel(g)),
                    selected = interp.output.gamut == g,
                    onClick = { interp = interp.copy(output = interp.output.copy(gamut = g)) },
                )
            }
            LutTransfer.values().forEach { t ->
                LutInterpChoiceRow(
                    label = stringResource(lutTransferLabel(t)),
                    selected = interp.output.transfer == t,
                    onClick = { interp = interp.copy(output = interp.output.copy(transfer = t)) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (LutInterpLayout.hasRoleRow(window)) {
            // Role: where the pipeline applies the cube.
            Text(
                stringResource(R.string.editor_lut_interp_role),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LutRole.values().forEach { r ->
                LutInterpChoiceRow(
                    label = stringResource(lutRoleLabel(r)),
                    selected = interp.role == r,
                    onClick = { interp = interp.copy(role = r) },
                )
            }
        }
        // Confirm / Cancel: the preset-name dialog's row (Cancel left,
        // Confirm right, 8dp apart). Shared — the buttons commit the
        // WHOLE interpretation across both windows, so they stay put
        // under either one. Cancel and dismiss call nothing — cancel
        // leaves no record.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_lut_interp_cancel))
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onConfirm(name.trim(), interp) }) {
                Text(stringResource(R.string.editor_lut_interp_confirm))
            }
        }
        Spacer(Modifier.height(16.dp))
        }
    }
}

/** The toggle label for [w] — "Source" / "Transform" (owner 2026-11-13). */
private fun lutWindowLabel(w: LutInterpWindow): Int = when (w) {
    LutInterpWindow.SOURCE -> R.string.editor_lut_interp_window_source
    LutInterpWindow.TRANSFORM -> R.string.editor_lut_interp_window_transform
}

/**
 * One radio row of the interpretation sheet: the LutRow shape (48dp
 * minimum, RadioButton left, label right) with the label left-aligned in
 * the remaining space.
 */
@Composable
private fun LutInterpChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
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
        // Radio rows in this sheet are 48dp minimum (DESIGN.md Inputs);
        // the trailing padding keeps the label off the screen edge.
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 16.dp),
        )
    }
}

/** The preset label for [p]. */
private fun lutPresetLabel(p: LutInterpPreset): Int = when (p) {
    LutInterpPreset.SRGB_CREATIVE -> R.string.editor_lut_preset_srgb_creative
    LutInterpPreset.REC709_CREATIVE -> R.string.editor_lut_preset_rec709_creative
    LutInterpPreset.DWG_TO_REC709 -> R.string.editor_lut_preset_dwg_to_rec709
    LutInterpPreset.CUSTOM -> R.string.editor_lut_preset_custom
    LutInterpPreset.LEGACY -> R.string.editor_lut_preset_legacy
}

/** The gamut label for [g] — Rec.709/sRGB presented as one identity. */
private fun lutGamutLabel(g: LutGamut): Int = when (g) {
    LutGamut.Rec709 -> R.string.editor_lut_gamut_rec709
    LutGamut.Rec2020 -> R.string.editor_lut_gamut_rec2020
    LutGamut.DaVinciWideGamut -> R.string.editor_lut_gamut_dwg
    LutGamut.Acescg -> R.string.editor_lut_gamut_acescg
    LutGamut.ArriWideGamut3 -> R.string.editor_lut_gamut_awg3
    LutGamut.SGamut3Cine -> R.string.editor_lut_gamut_sg3cine
    LutGamut.CinemaGamut -> R.string.editor_lut_gamut_cinema_gamut
    LutGamut.AppleWideGamut -> R.string.editor_lut_gamut_apple_wide_gamut
    LutGamut.VGamut -> R.string.editor_lut_gamut_vgamut
}

/** The transfer label for [t] — honest names, per the mission rules. */
private fun lutTransferLabel(t: LutTransfer): Int = when (t) {
    LutTransfer.Linear -> R.string.editor_lut_transfer_linear
    LutTransfer.Srgb -> R.string.editor_lut_transfer_srgb
    LutTransfer.Rec709OETF -> R.string.editor_lut_transfer_rec709_oetf
    LutTransfer.Gamma24 -> R.string.editor_lut_transfer_gamma24
    LutTransfer.DaVinciIntermediate -> R.string.editor_lut_transfer_di
    LutTransfer.Acescct -> R.string.editor_lut_transfer_acescct
    LutTransfer.ArriLogC3 -> R.string.editor_lut_transfer_logc3
    LutTransfer.SonyLog3 -> R.string.editor_lut_transfer_slog3
    LutTransfer.CanonLog3 -> R.string.editor_lut_transfer_clog3
    LutTransfer.AppleLog -> R.string.editor_lut_transfer_apple_log
    LutTransfer.VLog -> R.string.editor_lut_transfer_vlog
    LutTransfer.FujifilmFLog -> R.string.editor_lut_transfer_flog
    LutTransfer.Cineon -> R.string.editor_lut_transfer_cineon
}

/** The role label for [r]. */
private fun lutRoleLabel(r: LutRole): Int = when (r) {
    LutRole.LegacyLinear709 -> R.string.editor_lut_role_legacy
    LutRole.DisplayLook -> R.string.editor_lut_role_display
    LutRole.SceneLook -> R.string.editor_lut_role_scene
    LutRole.SceneToDisplay -> R.string.editor_lut_role_scene_to_display
}

/**
 * Light tab: Exposure / Highlights / tone controls (DESIGN.md Editor).
 *
 * [onReset] carries the double-tap resets SEPARATELY from [onChange]
 * (feature N7): a reset is a discrete grade edit — its own undo step,
 * never coalesced into the surrounding slider gestures — while onChange
 * stays the slider path (drag frames and tap-to-position jumps, one
 * gesture = one undo step).
 */
@Composable
private fun LightPanel(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
    // The Recovery button (MCRAW sources only: EditorPreviewLogic.showRawStageToggles).
    showRecovery: Boolean = false,
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.EXPOSURE, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.HIGHLIGHTS, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        if (showRecovery) {
            // Highlight Recovery: toggles ONLY GradeK.highlightRecovery, as one discrete undo step (the onReset
            // route); it never touches the Highlights slider above, and the second press only switches it off.
            val on = grade.highlightRecovery
            val desc = stringResource(R.string.editor_highlight_recovery_desc)
            val content: @Composable RowScope.() -> Unit = {
                Icon(
                    painter = painterResource(R.drawable.ic_highlight_recovery),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_highlight_recovery))
            }
            val buttonModifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = desc
                    stateDescription = if (on) "on" else "off"
                }
            val toggle = { onReset(grade.copy(highlightRecovery = !grade.highlightRecovery)) }
            if (on) {
                FilledTonalButton(onClick = toggle, modifier = buttonModifier, content = content)
            } else {
                OutlinedButton(onClick = toggle, modifier = buttonModifier, content = content)
            }
        }
        GradeSlider(
            label = stringResource(R.string.editor_slider_black_point),
            value = grade.blackPoint,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.blackPoint.toInt()),
            onChange = { v -> onChange(grade.copy(blackPoint = SliderSteps.snapBlackPoint(v))) },
            onDoubleTapReset = {
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.BLACK_POINT, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.WHITE_POINT, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SHADOWS, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.MIDTONES, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.CONTRAST, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
    }
}

/**
 * Color tab (2026-11-13 editor panel segmentation plan): three sub-tab
 * bodies — Balance (white balance: As Shot reset + Temperature / Tint +
 * Saturation, with the CFA-stage toggles in an advanced RAW / Source
 * section at the BOTTOM), Wheels (the color wheel + split tone section,
 * COL-13), Look (Tone Mapping + LUT, moved from the Detail tab). The
 * sub-tab ROW itself is NOT composed here: the screen supplies it through
 * EditorPanelSheet's pinnedContent slot (plan: "sub-tab row fixed at
 * panel top; only the selected body scrolls"), so this panel only renders
 * the SELECTED body. [subTab] is the screen's saved selection, already
 * sanitized through [EditorCategories.sanitizeColorSubTab] — the row and
 * the body can never disagree about which segment is open.
 *
 * Temperature and Tint landed here from the Light tab when Saturation
 * arrived (spec COL-12: white balance and saturation are the color
 * controls; the Light tab is tone).
 *
 * RAW-07/COL-09/RAW-04/RAW-06 semantics (unchanged by the regrouping):
 * - As Shot (reset action): tempK -> 5600, tint -> 0, asShot -> true. The
 *   manual values stay stored in tempK/tint whenever asShot is toggled off
 *   again, so nothing is ever lost.
 * - Temperature/Tint slider edits set asShot = false: touching a manual WB
 *   control means manual WB is what the user wants to see.
 * - Highlight Reconstruction and Keep Vignetting are M3 Switch rows (48dp
 *   touch targets, DESIGN.md Inputs): they toggle native develop stages, not
 *   tone — the Highlights TONE slider stays independent (stage 13 vs stage 3).
 *   The plan moves them OUT of the main flow into the RAW / Source section
 *   at the bottom of Balance: CFA is source-decode config, not a WB-family
 *   control. CFA-stage rows still show only when [showRawStageToggles] is
 *   true (MCRAW); encoded (Mi-Log MP4) clips decode YUV with no CFA stage,
 *   so they get the same Balance body without the section.
 * - The Wheels body shows only when [showColorTools] is true (MCRAW): the
 *   encoded path takes the same Grade but the bridge ignores the
 *   color-package fields there, so the wheel would be dead controls —
 *   the screen's visibleColorSubTabs keeps the Wheels tab itself out of
 *   the row on that path. The section's own contract (band tabs, disc
 *   colors = the stage-18 gain math, split hue on/off derived from
 *   hue -1) is documented on [ColorWheelSection].
 * - The Look body (Tone Mapping + LUT) is plain regrouping: the tonemap
 *   choice, AgX look, LUT selection and LUT Strength keep the exact
 *   semantics and settle paths they had in [DetailPanel] (COL-16/COL-07).
 */
@Composable
private fun ColorPanel(
    grade: GradeK,
    subTab: Int,
    showRawStageToggles: Boolean,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    // Double-tap resets ride their own route (feature N7): discrete undo
    // steps, never coalesced into the slider gestures (see LightPanel).
    onReset: (GradeK) -> Unit = {},
    // ---- Color wheel + split tone section (COL-13) ----
    // MCRAW only (the pure decision showColorTools): the encoded path
    // takes the same Grade but ignores the color-package fields, so the
    // section would be dead controls there.
    showColorTools: Boolean = false,
    // ---- Nodes body (F1-8a) ----
    nodes: List<com.ritim.ritimcraw.data.grade2.GradeNodeV2> = emptyList(),
    selectedNode: Int = 0,
    onSelectNode: (Int) -> Unit = {},
    onNodesChange: (List<com.ritim.ritimcraw.data.grade2.GradeNodeV2>) -> Unit = {},
    // ---- Look body: LUT section (COL-07), moved from DetailPanel ----
    lutEntries: List<LutInfo> = emptyList(),
    lutImportError: String? = null,
    onImportLut: () -> Unit = {},
    onRemoveLut: (LutInfo) -> Unit = {},
    // "Edit interpretation" on a record row: arms the interpretation sheet
    // with that record (the screen owns the sheet; AppNav's bindInterp
    // runs on Confirm).
    onEditLut: (LutInfo) -> Unit = {},
    // LUT selection and the tonemap/AgX choices are grade edits on the
    // DISCRETE settle path (immediate full tier, their own undo step) —
    // the exact routes they had in DetailPanel.
    onLutSelect: (GradeK) -> Unit = {},
    onTonemapChange: (GradeK) -> Unit = {},
) {
    when (subTab) {
        // Balance: the WB family (plan: "Balance: WB, temp, tint,
        // saturation") plus the advanced RAW / Source section at the
        // bottom (plan: CFA is source-decode config, not a WB-family
        // control — moved out of the main flow).
        EditorCategories.COLOR_BALANCE -> ColorBalanceBody(
            grade = grade,
            showRawStageToggles = showRawStageToggles,
            onChange = onChange,
            onDragActiveChange = onDragActiveChange,
            onToggleChange = onToggleChange,
            onReset = onReset,
        )
        // Wheels: the wheel + split tone section as its own body. The
        // gate is double-armed (the row hides the tab on the encoded
        // path AND the body guards) — the guard keeps the panel honest
        // even if a stale saved sub-tab ever reached it unsanitized.
        EditorCategories.COLOR_WHEELS -> if (showColorTools) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ColorWheelSection(
                    grade = grade,
                    onChange = onChange,
                    onDragActiveChange = onDragActiveChange,
                    onToggleChange = onToggleChange,
                    onReset = onReset,
                )
            }
        }
        // Look: Tone Mapping (+ AgX Look) and the LUT section as two
        // SEPARATE sections (plan) — both moved from DetailPanel with
        // semantics untouched.
        EditorCategories.COLOR_NODES -> NodesBody(
            nodes = nodes,
            selected = selectedNode,
            onSelect = onSelectNode,
            onNodesChange = onNodesChange,
            onDragActiveChange = onDragActiveChange,
        )
        EditorCategories.COLOR_LOOK -> ColorLookBody(
            grade = grade,
            onChange = onChange,
            onDragActiveChange = onDragActiveChange,
            onTonemapChange = onTonemapChange,
            onReset = onReset,
            lutEntries = lutEntries,
            lutImportError = lutImportError,
            onImportLut = onImportLut,
            onRemoveLut = onRemoveLut,
            onEditLut = onEditLut,
            onLutSelect = onLutSelect,
        )
    }
}

/**
 * The Balance sub-tab body (2026-11-13 segmentation plan): the WB family —
 * As Shot reset + Temperature / Tint + Saturation — with the CFA-stage
 * toggles demoted to an advanced RAW / Source section at the BOTTOM (plan:
 * CFA is source-decode config, not a WB-family control). The section header
 * separates them from the WB controls so the main flow reads as one family.
 */
@Composable
private fun ColorBalanceBody(
    grade: GradeK,
    showRawStageToggles: Boolean,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.TEMPERATURE, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.TINT, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = CompactSliderDefaults.TINT_GRADIENT,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_saturation),
            value = grade.saturation,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.saturation.toInt()),
            onChange = { v -> onChange(grade.copy(saturation = SliderSteps.snapSaturation(v))) },
            onDoubleTapReset = {
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SATURATION, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = CompactSliderDefaults.SATURATION_GRADIENT,
        )
        // ---- Advanced RAW / Source section (segmentation plan) ----
        // The CFA-stage toggles demoted out of the main flow (plan: CFA
        // is source-decode config, not a WB-family control), kept at the
        // BOTTOM of Balance under their own header so they stay findable
        // without leading the color work. MCRAW only (encoded clips
        // decode YUV — there is no CFA stage to reconstruct or vignette;
        // the decision is pure and JVM-tested).
        if (showRawStageToggles) {
            Text(
                stringResource(R.string.editor_color_raw_source),
                style = MaterialTheme.typography.labelLarge,
            )
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
    }
}

/**
 * The Look sub-tab body (2026-11-13 segmentation plan): Tone Mapping and
 * the LUT as two SEPARATE sections, moved from [DetailPanel] with their
 * semantics and settle paths untouched (COL-16/COL-07). The tonemap
 * choice and the LUT selection are DISCRETE edits (immediate full-tier
 * settle, their own undo steps); LUT Strength is a drag like every other
 * slider.
 */
@Composable
private fun ColorLookBody(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onTonemapChange: (GradeK) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
    lutEntries: List<LutInfo> = emptyList(),
    lutImportError: String? = null,
    onImportLut: () -> Unit = {},
    onRemoveLut: (LutInfo) -> Unit = {},
    onEditLut: (LutInfo) -> Unit = {},
    onLutSelect: (GradeK) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- Tone Mapping section (moved from DetailPanel) ----
        // Tonemapping: one row, one choice (Off / ACES / AgX). The label
        // row mirrors the slider rows so the sections read as one panel.
        Text(
            stringResource(R.string.editor_tonemapping),
            style = MaterialTheme.typography.labelLarge,
        )
        CompactSegmentedRow(Modifier.fillMaxWidth()) {
            val options = listOf(
                TonemapOption(R.string.editor_tonemap_off, 0),
                TonemapOption(R.string.editor_tonemap_aces, 1),
                TonemapOption(R.string.editor_tonemap_agx, 2),
            )
            options.forEachIndexed { index, option ->
                Segment(
                    // The AgX tab (2) is selected for ANY AgX look (2-5);
                    // re-tapping it keeps the chosen variant instead of
                    // snapping back to Punchy.
                    selected = if (option.value >= 2) grade.tonemap >= 2
                               else grade.tonemap == option.value,
                    onClick = {
                        val value = if (option.value >= 2 && grade.tonemap >= 2) grade.tonemap
                                    else option.value
                        if (value != grade.tonemap)
                            onTonemapChange(grade.copy(tonemap = value))
                    },
                    label = stringResource(option.labelRes),
                )
            }
        }
        // AgX look (COL-16 follow-up): a second row ONLY while AgX is
        // active — Punchy (2) / Golden (3) / Greyscale (4) / Base (5), the
        // rawedit::Grade Ints from the core look table. Same discrete
        // settle path as the row above (not a drag). Look names stay
        // untranslated everywhere: they are the upstream AgX look names,
        // like ACES/AgX themselves.
        if (grade.tonemap >= 2) {
            Text(
                stringResource(R.string.editor_tonemap_agx_look),
                style = MaterialTheme.typography.labelLarge,
            )
            CompactSegmentedRow(Modifier.fillMaxWidth()) {
                val looks = listOf(
                    TonemapOption(R.string.editor_tonemap_agx_punchy, 2),
                    TonemapOption(R.string.editor_tonemap_agx_golden, 3),
                    TonemapOption(R.string.editor_tonemap_agx_greyscale, 4),
                    TonemapOption(R.string.editor_tonemap_agx_base, 5),
                )
                looks.forEachIndexed { index, option ->
                    Segment(
                        selected = grade.tonemap == option.value,
                        onClick = {
                            onTonemapChange(grade.copy(tonemap = option.value))
                        },
                        label = stringResource(option.labelRes),
                    )
                }
            }
        }
        // ---- LUT section (COL-07, moved from DetailPanel) ----
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
            // (lutId AND lutInterp = null), not a grade reset — Strength
            // keeps its value so re-selecting a cube restores the blend
            // the user set. A null lutInterp is the legacy selection: the
            // grade takes the byte-identical stage-24 path and persists
            // WITHOUT the lutInterp key.
            LutRow(
                label = stringResource(R.string.editor_lut_none),
                selected = grade.lutId == null,
                onClick = {
                    onLutSelect(grade.copy(lutId = null, lutInterp = null))
                },
            )
            lutEntries.forEach { lut ->
                LutRow(
                    label = lut.name,
                    // The record row's input -> output summary (owner
                    // request 2026-11-13): technical color-science
                    // names, never localized — the same convention as
                    // the AgX look names.
                    summary = LutInterpPresets.summaryOf(lut.interp),
                    selected = grade.lutId == lut.id,
                    onClick = {
                        // The grade carries the interpretation BY VALUE
                        // (null for a legacy record = the stage-24 path).
                        onLutSelect(
                            grade.copy(
                                lutId = lut.id,
                                lutInterp = LutInterpPresets.gradeInterpOf(lut),
                            )
                        )
                    },
                    onRemove = { onRemoveLut(lut) },
                    onEdit = { onEditLut(lut) },
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
                    onReset(SliderSteps.resetToDefault(SliderSteps.Axis.LUT_STRENGTH, grade))
                },
                onDragActiveChange = onDragActiveChange,
            )
        }
    }
}

/**
 * The Color tab's pinned sub-tab row (2026-11-13 editor panel segmentation
 * plan): a text-first M3 [SecondaryTabRow] — Balance / Wheels / Look —
 * composed through EditorPanelSheet's pinnedContent slot, so it stays at
 * the panel top while the selected body scrolls under it (plan: "sub-tab
 * row fixed at panel top; only the selected body scrolls vertically").
 *
 * [subTab] is the screen's saved selection, SANITIZED against the gate
 * before the call: the row's selected index is a member of the composed
 * list by construction ([EditorCategories.visibleColorSubTabs] drops
 * Wheels on the encoded path), so the row, the body and the saved state
 * can never disagree. A tap writes the choice back through
 * [onSubTabChange] — UI-only state, never a grade edit.
 *
 * Text-only M3 [Tab]s (plan: "text-first labels") are 48dp tall — the
 * DESIGN.md touch-target floor holds without extra padding. The row keeps
 * SecondaryTabRow's stock colors: the secondary token container reads as
 * part of the sheet's surface, and the 3dp primary underline marks the
 * open segment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSubTabRow(
    subTab: Int,
    showColorTools: Boolean,
    onSubTabChange: (Int) -> Unit,
) {
    val visible = EditorCategories.visibleColorSubTabs(showColorTools)
    SecondaryTabRow(
        selectedTabIndex = visible.indexOf(subTab),
    ) {
        visible.forEach { candidate ->
            val labelRes = when (candidate) {
                EditorCategories.COLOR_BALANCE -> R.string.editor_color_subtab_balance
                EditorCategories.COLOR_WHEELS -> R.string.editor_color_subtab_wheels
                EditorCategories.COLOR_NODES -> R.string.editor_color_subtab_nodes
                else -> R.string.editor_color_subtab_look
            }
            Tab(
                selected = candidate == subTab,
                onClick = { onSubTabChange(candidate) },
                text = { Text(stringResource(labelRes)) },
            )
        }
    }
}

/**
 * The color wheel + split tone section (COL-13): the three-band wheel pad
 * (Shadows / Midtones / Highlights tabs — stage 18's tonal bands, the same
 * band anchors the Light tab's sliders use) with the offset readout, then
 * the split tone group (stage 19: the switch, shadow/highlight hue
 * sliders, the Balance slider).
 *
 * Band tabs: one segmented row above the pad; the pad edits the ACTIVE
 * band's (x, y) offset. The band choice is UI-only state (which wheel is
 * "open", like a tool selection) — it never touches the grade, so it is
 * rememberSaveable, not a GradeK field: reopening the editor restores the
 * last-open band but the grade is untouched.
 *
 * The wheel drag is a slider-path drag ([onChange] + [onDragActiveChange]
 * — low tier while the finger is down, settle on lift); the double-tap
 * center reset is a DISCRETE edit ([onReset], its own undo step, feature
 * N7). The split tone sliders are GradeSlider rows like every other
 * slider; their track gradient is the engine's own hue-window sweep
 * ([ColorWheelMath.hueTrackGradient] — the same raised-cosine windows the
 * stage applies, sampled across the 0..359 track), so the slider's color
 * story is the math's story, the TEMPERATURE_GRADIENT precedent.
 *
 * Split tone on/off is DERIVED from the hue values (hue -1 = off, the
 * GradeK neutral): one switch for the whole split group mirrors "either
 * hue >= 0" — the native splitActive is exactly that OR — and the first
 * enable seeds both hues to 0 (red, the temporal-NR seeding precedent: a
 * toggle that visibly changed nothing would read as broken). Turning the
 * switch off writes -1 to BOTH hues (the stored neutral), so the switch
 * can never disagree with the stored state; the switch settles at the
 * full tier immediately through [onToggleChange] like every other toggle.
 * The hue sliders show only while the group is active — the -1 neutral
 * never reaches a 0..359 track (the M3 Slider cannot render outside its
 * range, and a thumb parked at 0 would read as a red grade it does not
 * have). The Balance slider stays visible either way: balance has no off
 * state in the engine (it only shapes the crossover when a hue is
 * active), and hiding it would hide where its reset lives.
 */
@Composable
private fun ColorWheelSection(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
) {
    // The active band: UI-only state (see KDoc), saved across
    // configuration changes but never persisted into the grade.
    var band by rememberSaveable { mutableStateOf(WheelBand.MIDTONES) }
    val (bandX, bandY) = when (band) {
        WheelBand.SHADOWS -> grade.wheelShadowX to grade.wheelShadowY
        WheelBand.MIDTONES -> grade.wheelMidX to grade.wheelMidY
        WheelBand.HIGHLIGHTS -> grade.wheelHighX to grade.wheelHighY
    }
    Text(
        stringResource(R.string.editor_color_wheel),
        style = MaterialTheme.typography.labelLarge,
    )
    CompactSegmentedRow(Modifier.fillMaxWidth()) {
        val bands = listOf(
            WheelBandOption(R.string.editor_wheel_band_shadows, WheelBand.SHADOWS),
            WheelBandOption(R.string.editor_wheel_band_midtones, WheelBand.MIDTONES),
            WheelBandOption(R.string.editor_wheel_band_highlights, WheelBand.HIGHLIGHTS),
        )
        bands.forEachIndexed { index, option ->
            Segment(
                selected = band == option.value,
                onClick = { band = option.value },
                label = stringResource(option.labelRes),
            )
        }
    }
    ColorWheelPad(
        x = bandX,
        y = bandY,
        onDragChange = { nx, ny ->
            onChange(
                when (band) {
                    WheelBand.SHADOWS -> grade.copy(wheelShadowX = nx, wheelShadowY = ny)
                    WheelBand.MIDTONES -> grade.copy(wheelMidX = nx, wheelMidY = ny)
                    WheelBand.HIGHLIGHTS -> grade.copy(wheelHighX = nx, wheelHighY = ny)
                }
            )
        },
        onDragActiveChange = onDragActiveChange,
        onDoubleTapReset = {
            // Center reset: the ACTIVE band's offset to (0, 0) — the
            // spec's "merkez nötrdür" made actionable. One discrete edit,
            // its own undo step.
            onReset(
                when (band) {
                    WheelBand.SHADOWS ->
                        grade.copy(wheelShadowX = 0f, wheelShadowY = 0f)
                    WheelBand.MIDTONES ->
                        grade.copy(wheelMidX = 0f, wheelMidY = 0f)
                    WheelBand.HIGHLIGHTS ->
                        grade.copy(wheelHighX = 0f, wheelHighY = 0f)
                }
            )
        },
        contentDescription = stringResource(R.string.editor_color_wheel),
    )
    // Offset readout: the active band's (x, y) with the neutral word when
    // centered — "Nötr" reads the spec contract, "+0.35 / -0.10" reads the
    // engine's actual exponents' inputs. tnum via Format (two decimals,
    // explicit sign — the formatEv precedent).
    Text(
        if (bandX == 0f && bandY == 0f) {
            stringResource(R.string.editor_wheel_neutral)
        } else {
            stringResource(
                R.string.editor_wheel_offset_readout,
                Format.formatWheelOffset(bandX),
                Format.formatWheelOffset(bandY),
            )
        },
        style = MaterialTheme.typography.labelLarge.merge(Tnum),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    // ---- Split tone (stage 19) ----
    // The switch row carries the group label (the Temporal NR precedent:
    // no separate header above a switch group).
    val splitActive = grade.splitShadowHue >= 0f || grade.splitHighlightHue >= 0f
    RawToggleRow(
        label = stringResource(R.string.editor_split_tone),
        checked = splitActive,
        onCheckedChange = { checked ->
            if (checked) {
                // First enable seeds BOTH hues to 0 (red) — the temporal-NR
                // seeding precedent: a toggle that visibly changed nothing
                // would read as broken, and 0/0 is the minimal active pair.
                onToggleChange(grade.copy(splitShadowHue = 0f, splitHighlightHue = 0f))
            } else {
                // Off writes the GradeK neutral to both hues — the switch
                // can never disagree with the stored state.
                onToggleChange(
                    grade.copy(
                        splitShadowHue = GradeK().splitShadowHue,
                        splitHighlightHue = GradeK().splitHighlightHue,
                    )
                )
            }
        },
    )
    if (splitActive) {
        // The hue track: the engine's own hue-window sweep, so each track
        // position reads as the hue it selects (the TEMPERATURE_GRADIENT
        // precedent; muted pastels by the unit-average windows themselves).
        val hueTrack = remember { ColorWheelMath.hueTrackGradient() }
        GradeSlider(
            label = stringResource(R.string.editor_slider_split_shadow_hue),
            value = grade.splitShadowHue,
            valueRange = 0f..359f,
            step = SliderSteps.SPLIT_HUE_STEP_DEG,
            valueLabel = Format.formatHueDeg(grade.splitShadowHue.toInt()),
            onChange = { v ->
                onChange(grade.copy(splitShadowHue = SliderSteps.snapSplitHue(v)))
            },
            onDoubleTapReset = {
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SPLIT_SHADOW_HUE, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = hueTrack,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_split_highlight_hue),
            value = grade.splitHighlightHue,
            valueRange = 0f..359f,
            step = SliderSteps.SPLIT_HUE_STEP_DEG,
            valueLabel = Format.formatHueDeg(grade.splitHighlightHue.toInt()),
            onChange = { v ->
                onChange(grade.copy(splitHighlightHue = SliderSteps.snapSplitHue(v)))
            },
            onDoubleTapReset = {
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SPLIT_HIGHLIGHT_HUE, grade))
            },
            onDragActiveChange = onDragActiveChange,
            trackGradient = hueTrack,
        )
    }
    // Balance: -100..100, positive widens the SHADOW side (the native
    // crossover m = 0.18 * 2^(balance/100) grows with balance — the
    // Lightroom convention; the slider label direction matches). Visible
    // even when the group is off: balance has no off state in the engine
    // (it only shapes the crossover when a hue is active) and hiding it
    // would hide where its reset lives.
    GradeSlider(
        label = stringResource(R.string.editor_slider_split_balance),
        value = grade.splitBalance,
        valueRange = -100f..100f,
        step = SliderSteps.SPLIT_BALANCE_STEP,
        valueLabel = Format.formatSigned(grade.splitBalance.toInt()),
        onChange = { v ->
            onChange(grade.copy(splitBalance = SliderSteps.snapSplitBalance(v)))
        },
        onDoubleTapReset = {
            onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SPLIT_BALANCE, grade))
        },
        onDragActiveChange = onDragActiveChange,
    )
}

/** The wheel's three tonal bands (COL-13, stage 18): the pad's tabs. */
private enum class WheelBand { SHADOWS, MIDTONES, HIGHLIGHTS }

/**
 * One wheel band segmented-button entry (COL-13): label + the band the
 * pad edits when selected.
 */
private data class WheelBandOption(
    val labelRes: Int,
    val value: WheelBand,
)

/**
 * Detail tab (COL-15/COL-16) under the plan's two section headers
 * (2026-11-13 editor panel segmentation plan: "NO sub-tabs yet — two
 * section headers"): Noise Reduction over the denoise family — Noise
 * Reduction and Chroma Denoise (DN-01..08 spatial denoise, percent
 * 0..100, 0 = the stage skipped — the old look) plus the Temporal NR
 * group (DN-05..DN-09, same header: temporal is the third denoise
 * control) — and Texture over Sharpness (edge sharpness, unsharp radius
 * 1.5) and Detail (fine-texture contrast, unsharp radius 0.75). The
 * denoise pair leads because native stages 7/8 run BEFORE
 * sharpness/detail (21/22): sliders read in pipeline order, and
 * denoising first is also what makes the sharpeners amplify signal,
 * not noise. All four spatial controls run on BOTH clip kinds: the
 * native develop pipeline and developEncoded share the same stages, so
 * encoded (Mi-Log MP4) clips get the same Detail tab with nothing
 * hidden.
 *
 * Temporal NR: an M3 Switch row plus a 0..100 Strength slider, shown
 * only when [showTemporalNr] is true (MCRAW). The temporal stage merges
 * the neighboring CFA frames into the center frame before develop,
 * motion-compensated and confidence-weighted; the toggle settles at
 * the full tier immediately (not a drag), the strength slider is a drag
 * like every other slider. Encoded (Mi-Log MP4) clips never see the
 * group — the bridge takes-and-ignores the temporal fields on that
 * path.
 *
 * The 2026-11-13 plan moved the Tonemapping choice, the AgX look row
 * and the LUT section to the Color tab's Look sub-tab
 * ([ColorLookBody]) and the stabilization + crop groups to the Frame
 * tab's sub-tabs ([FramePanel]) — semantics unchanged in both homes.
 * This tab grows sub-tabs only when temporal NR controls grow (plan).
 */
@Composable
private fun DetailPanel(
    grade: GradeK,
    showTemporalNr: Boolean,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    // Double-tap resets ride their own route (feature N7): discrete undo
    // steps, never coalesced into the slider gestures (see LightPanel).
    onReset: (GradeK) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The plan's first section header: the denoise family (spatial
        // pair + temporal group) under one label, so the tab reads as two
        // families instead of one flat slider list.
        Text(
            stringResource(R.string.editor_detail_section_noise_reduction),
            style = MaterialTheme.typography.labelLarge,
        )
        // DN-01..08: both denoise controls first, in native pipeline order
        // (stage 7 chroma, stage 8 luma — before sharpness/detail 21/22).
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.NOISE_REDUCTION, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.CHROMA_DENOISE, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
        // Temporal NR: MCRAW only, and part of the Noise Reduction family
        // (the plan's first header groups it with the spatial pair — it is
        // the third denoise control). The switch settles immediately at the
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
                        onReset(SliderSteps.resetToDefault(SliderSteps.Axis.TEMPORAL_STRENGTH, grade))
                    },
                    onDragActiveChange = onDragActiveChange,
                )
            }
        }
        // The plan's second section header: the sharpeners. The temporal
        // group above stays under Noise Reduction — it is the third
        // denoise control, not a texture control.
        Text(
            stringResource(R.string.editor_detail_section_texture),
            style = MaterialTheme.typography.labelLarge,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_sharpness),
            value = grade.sharpness,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.sharpness.toInt()),
            onChange = { v -> onChange(grade.copy(sharpness = SliderSteps.snapSharpness(v))) },
            onDoubleTapReset = {
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.SHARPNESS, grade))
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
                onReset(SliderSteps.resetToDefault(SliderSteps.Axis.DETAIL, grade))
            },
            onDragActiveChange = onDragActiveChange,
        )
    }
}

/**
 * The Frame tab's body (2026-11-13 editor panel segmentation plan): the
 * crop/straighten and stabilization groups moved here from
 * [DetailPanel] — frame controls, not detail controls — behind the
 * tab's Crop | Stabilize sub-tabs ([FrameSubTabRow], pinned by the
 * screen). Semantics are UNCHANGED from their DetailPanel home: the
 * same GradeK fields, the same pure SliderSteps/CropState math, the
 * same settle routes (sliders drag through [onChange] +
 * [onDragActiveChange]; the toggle, the mode choice, the aspect choice
 * and the group resets are discrete edits through [onToggleChange] /
 * [onCropEdit]; double-tap resets ride [onReset]).
 *
 * The Stabilize gate is double-armed exactly like the Color row's
 * Wheels: the row drops the tab on the encoded path
 * ([EditorCategories.visibleFrameSubTabs]) AND this dispatch guards
 * with [showStabilization], so a stale unsanitized sub-tab can never
 * compose a dead body. Crop runs on BOTH clip kinds — the rect is
 * grade state the export path applies to any clip.
 */
@Composable
private fun FramePanel(
    grade: GradeK,
    subTab: Int,
    // ---- Stabilization body (gyro Phase 5, moved from DetailPanel) ----
    showStabilization: Boolean,
    gyroAttached: Boolean,
    // The attach-time probe's [anchored, offsetMs, boundMs, focalMm,
    // samples] — drives the sync status line (pure
    // EditorPreviewLogic.syncStatus).
    gyroSync: DoubleArray?,
    // The zoom-limit honesty readout (audit fix 2026-11-12, Fix 5): the
    // native probe's [requiredZoom, appliedZoom, statusInt] over the
    // CURRENT grade + attached log, or null = no verdict (the body
    // renders no readout rather than fabricating a zoom — the pure
    // EditorPreviewLogic.stabZoomVerdict decides what shows). M5 commit b
    // extends the array to [.., syncKind, syncOffsetMs, syncBoundMs] —
    // size >= 3 readers keep working.
    stabZoom: DoubleArray?,
    // Optical sync refinement (M5 commit b): the RUNTIME user toggle's
    // state + change handler, passed through to the stab body's switch
    // (UI-level rememberSaveable in ClipEditorScreen — deliberately NOT a
    // grade field; the bridge's default-off global keeps every path
    // honest until the owner's on-device A/B).
    syncRefine: Boolean,
    onSyncRefineChange: (Boolean) -> Unit,
    gyroName: String?,
    containerFocalMm: Float,
    gyroImportError: String?,
    onAttachGyro: () -> Unit,
    onRemoveGyro: () -> Unit,
    // ---- Crop body (RAW-09, moved from DetailPanel) ----
    // The UNCROPPED effective-upright dims (the publish fold's capture):
    // aspect presets and the honest W/H slider ranges math against this
    // pair. 0x0 before the first publish — CropState guards to the full
    // rect, the documented degradation.
    cropFrameW: Int,
    cropFrameH: Int,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    // Double-tap resets ride their own route (feature N7): discrete undo
    // steps, never coalesced into the slider gestures (see LightPanel).
    onReset: (GradeK) -> Unit = {},
    // Aspect choice and the group reset are discrete edits (like the
    // RAW toggles): they settle IMMEDIATELY at the full tier and record
    // as their own undo step, never coalesced into a slider gesture.
    onCropEdit: (GradeK) -> Unit = {},
    // Quarter-turn rotate (owner request 2026-11-13 §1): the Crop body's
    // button pair, driving the SAME AppNav route the clip-info sheet's
    // rows drive.
    onRotate: (byDeg: Int) -> Unit = {},
) {
    when (subTab) {
        EditorCategories.FRAME_CROP -> FrameCropBody(
            grade = grade,
            cropFrameW = cropFrameW,
            cropFrameH = cropFrameH,
            onChange = onChange,
            onDragActiveChange = onDragActiveChange,
            onReset = onReset,
            onCropEdit = onCropEdit,
            onRotate = onRotate,
        )
        // The gate is double-armed (row + this guard — see KDoc): the
        // body keeps the panel honest even if a stale saved sub-tab
        // ever reached it unsanitized.
        EditorCategories.FRAME_STABILIZE -> if (showStabilization) {
            FrameStabilizeBody(
                grade = grade,
                gyroAttached = gyroAttached,
                gyroSync = gyroSync,
                stabZoom = stabZoom,
                syncRefine = syncRefine,
                onSyncRefineChange = onSyncRefineChange,
                gyroName = gyroName,
                containerFocalMm = containerFocalMm,
                gyroImportError = gyroImportError,
                onAttachGyro = onAttachGyro,
                onRemoveGyro = onRemoveGyro,
                onChange = onChange,
                onDragActiveChange = onDragActiveChange,
                onToggleChange = onToggleChange,
                onReset = onReset,
            )
        }
    }
}

/**
 * The Frame tab's pinned sub-tab row (2026-11-13 segmentation plan):
 * stock M3 [SecondaryTabRow] with text-only tabs (plan: "text-first
 * labels; icons optional support"), the same construction as
 * [ColorSubTabRow]. The row composes ONLY when the clip kind offers a
 * real choice: on the encoded path [showStabilization] is false, Crop
 * is the only segment, and a one-tab row is chrome with nothing to
 * choose — the row composes nothing and the Crop body stands alone
 * exactly like Detail's (the screen's pinned slot stays non-null; an
 * empty composable takes no height).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FrameSubTabRow(
    subTab: Int,
    showStabilization: Boolean,
    onSubTabChange: (Int) -> Unit,
) {
    val visible = EditorCategories.visibleFrameSubTabs(showStabilization)
    if (visible.size < 2) {
        return
    }
    SecondaryTabRow(
        selectedTabIndex = visible.indexOf(subTab),
    ) {
        visible.forEach { candidate ->
            val labelRes = when (candidate) {
                EditorCategories.FRAME_CROP -> R.string.editor_frame_subtab_crop
                else -> R.string.editor_frame_subtab_stabilize
            }
            Tab(
                selected = candidate == subTab,
                onClick = { onSubTabChange(candidate) },
                text = { Text(stringResource(labelRes)) },
            )
        }
    }
}

/**
 * The Crop sub-tab body (RAW-09, moved from DetailPanel verbatim): the
 * quarter-turn rotate pair (owner request 2026-11-13 §1 — the SAME
 * AppNav route the clip-info sheet's rows drive; MediaSource state, so
 * each tap is one discrete persisted edit, outside gradeHistory), the
 * Aspect choice (Free / 16:9 / 9:16 / 4:3 / 1:1 — a preset replaces the
 * rect with the largest centered on-ratio rect, FREE keeps it), the
 * Straighten dial (±15°, half-degree ticks, edge-preserving auto zoom),
 * the four rect sliders (Left/Top translate, Width/Height resize —
 * under a preset the perpendicular axis follows through the lock chain,
 * position giving way before the ratio), and the group reset. The old
 * group's "Crop" header is GONE here: the sub-tab labels the body (the
 * Balance/Wheels/Look bodies do not repeat their names either); the
 * Aspect label stays — it names the choice row inside the body. All
 * rect math runs through the pure SliderSteps.applyCropAxis against the
 * UNCROPPED effective dims, so the panel and the render pump can never
 * disagree about which region is selected.
 */
@Composable
private fun FrameCropBody(
    grade: GradeK,
    cropFrameW: Int,
    cropFrameH: Int,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
    onCropEdit: (GradeK) -> Unit = {},
    // Quarter-turn rotate (owner request 2026-11-13 §1): the SAME route
    // the clip-info sheet's rows drive — RotationState.applyManual in
    // AppNav. Manual rotation is MediaSource state, NOT grade state, so
    // it lives outside gradeHistory undo: each tap is one discrete
    // persisted edit (the quarter-turn accumulation itself is
    // JVM-tested in RotationStateTest).
    onRotate: (byDeg: Int) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Quarter-turn pair: two 48dp IconButtons (stock M3 touch
        // target), the vendored official Material Symbols Rounded
        // glyphs (ic_undo precedent — material-icons-core 1.7.3 ships
        // no rotate pair). A compact row above the aspect choice; the
        // icons are self-describing, contentDescriptions reuse the
        // info sheet's rotate strings.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { onRotate(-90) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_rotate_left),
                    contentDescription = stringResource(R.string.editor_rotate_left),
                )
            }
            IconButton(onClick = { onRotate(90) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_rotate_right),
                    contentDescription = stringResource(R.string.editor_rotate_right),
                )
            }
        }
        // Aspect: one choice row (Free / 16:9 / 9:16 / 4:3 / 1:1). A preset
        // REPLACES the rect with the largest centered on-ratio rect
        // (CropState.aspectRect); FREE keeps the rect as it is.
        Text(
            stringResource(R.string.editor_crop_aspect),
            style = MaterialTheme.typography.labelLarge,
        )
        CompactSegmentedRow(Modifier.fillMaxWidth()) {
            val aspects = listOf(
                CropAspectOption(R.string.editor_crop_aspect_free, CropState.ASPECT_FREE),
                CropAspectOption(R.string.editor_crop_aspect_16_9, CropState.ASPECT_16_9),
                CropAspectOption(R.string.editor_crop_aspect_9_16, CropState.ASPECT_9_16),
                CropAspectOption(R.string.editor_crop_aspect_4_3, CropState.ASPECT_4_3),
                CropAspectOption(R.string.editor_crop_aspect_1_1, CropState.ASPECT_1_1),
            )
            aspects.forEachIndexed { index, option ->
                Segment(
                    selected = grade.cropAspect == option.value,
                    onClick = {
                        if (option.value == CropState.ASPECT_FREE) {
                            onCropEdit(grade.copy(cropAspect = option.value))
                        } else {
                            val rect = CropState.aspectRect(
                                CropState.aspectRatio(option.value)!!,
                                cropFrameW,
                                cropFrameH,
                            )
                            onCropEdit(
                                grade.copy(
                                    cropAspect = option.value,
                                    cropX = rect.x,
                                    cropY = rect.y,
                                    cropW = rect.w,
                                    cropH = rect.h,
                                )
                            )
                        }
                    },
                    label = stringResource(option.labelRes),
                )
            }
        }
        // Straighten ±15° (half-degree ticks): the small-angle horizon
        // correction with the edge-preserving auto zoom (CropState's
        // sampler). A drag; the double-tap reset is a discrete step.
        GradeSlider(
            label = stringResource(R.string.editor_slider_straighten),
            value = grade.straightenDeg.coerceIn(
                -CropState.STRAIGHTEN_MAX_DEG, CropState.STRAIGHTEN_MAX_DEG,
            ),
            valueRange = SliderSteps.cropAxisRange(
                SliderSteps.Axis.STRAIGHTEN, grade, cropFrameW, cropFrameH,
            ),
            step = SliderSteps.STRAIGHTEN_STEP_DEG,
            valueLabel = Format.formatDeg(grade.straightenDeg),
            onChange = { v ->
                onChange(
                    SliderSteps.applyCropAxis(
                        SliderSteps.Axis.STRAIGHTEN, v, grade, cropFrameW, cropFrameH,
                    )
                )
            },
            onDoubleTapReset = {
                onReset(
                    SliderSteps.resetToDefault(SliderSteps.Axis.STRAIGHTEN, grade)
                )
            },
            onDragActiveChange = onDragActiveChange,
        )
        // The four rect sliders, each with its honest range (the thumb
        // never offers a position the applyCropAxis policy would clamp
        // away — X/Y translate up to the opposite edge, W/H grow up to
        // the preset's own extent). The displayed value is coerced into
        // that range: a stale stored grade (hand-edited JSON) or the
        // 0x0-dims degradation can hold an out-of-range value, and a
        // M3 Slider with value outside valueRange would throw.
        GradeSlider(
            label = stringResource(R.string.editor_slider_crop_left),
            value = grade.cropX.coerceIn(
                0f, (1f - grade.cropW).coerceAtLeast(0f),
            ),
            valueRange = SliderSteps.cropAxisRange(
                SliderSteps.Axis.CROP_X, grade, cropFrameW, cropFrameH,
            ),
            step = SliderSteps.CROP_FRACTION_STEP,
            valueLabel = Format.formatFractionPercent(grade.cropX),
            onChange = { v ->
                onChange(
                    SliderSteps.applyCropAxis(
                        SliderSteps.Axis.CROP_X, v, grade, cropFrameW, cropFrameH,
                    )
                )
            },
            onDoubleTapReset = {
                onReset(
                    SliderSteps.resetToDefault(SliderSteps.Axis.CROP_X, grade)
                )
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_crop_top),
            value = grade.cropY.coerceIn(
                0f, (1f - grade.cropH).coerceAtLeast(0f),
            ),
            valueRange = SliderSteps.cropAxisRange(
                SliderSteps.Axis.CROP_Y, grade, cropFrameW, cropFrameH,
            ),
            step = SliderSteps.CROP_FRACTION_STEP,
            valueLabel = Format.formatFractionPercent(grade.cropY),
            onChange = { v ->
                onChange(
                    SliderSteps.applyCropAxis(
                        SliderSteps.Axis.CROP_Y, v, grade, cropFrameW, cropFrameH,
                    )
                )
            },
            onDoubleTapReset = {
                onReset(
                    SliderSteps.resetToDefault(SliderSteps.Axis.CROP_Y, grade)
                )
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_crop_width),
            value = grade.cropW.coerceIn(CropState.MIN_FRACTION, 1f),
            valueRange = SliderSteps.cropAxisRange(
                SliderSteps.Axis.CROP_W, grade, cropFrameW, cropFrameH,
            ),
            step = SliderSteps.CROP_FRACTION_STEP,
            valueLabel = Format.formatFractionPercent(grade.cropW),
            onChange = { v ->
                onChange(
                    SliderSteps.applyCropAxis(
                        SliderSteps.Axis.CROP_W, v, grade, cropFrameW, cropFrameH,
                    )
                )
            },
            onDoubleTapReset = {
                onReset(
                    SliderSteps.resetToDefault(SliderSteps.Axis.CROP_W, grade)
                )
            },
            onDragActiveChange = onDragActiveChange,
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_crop_height),
            value = grade.cropH.coerceIn(CropState.MIN_FRACTION, 1f),
            valueRange = SliderSteps.cropAxisRange(
                SliderSteps.Axis.CROP_H, grade, cropFrameW, cropFrameH,
            ),
            step = SliderSteps.CROP_FRACTION_STEP,
            valueLabel = Format.formatFractionPercent(grade.cropH),
            onChange = { v ->
                onChange(
                    SliderSteps.applyCropAxis(
                        SliderSteps.Axis.CROP_H, v, grade, cropFrameW, cropFrameH,
                    )
                )
            },
            onDoubleTapReset = {
                onReset(
                    SliderSteps.resetToDefault(SliderSteps.Axis.CROP_H, grade)
                )
            },
            onDragActiveChange = onDragActiveChange,
        )
        // The group reset: the full rect, FREE aspect, zero straighten —
        // one discrete edit, its own undo step (GradeK's neutral crop
        // group; the DNG export dims return to the source orientation).
        FilledTonalButton(
            onClick = {
                onCropEdit(
                    grade.copy(
                        cropAspect = GradeK().cropAspect,
                        cropX = GradeK().cropX,
                        cropY = GradeK().cropY,
                        cropW = GradeK().cropW,
                        cropH = GradeK().cropH,
                        straightenDeg = GradeK().straightenDeg,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.editor_crop_reset))
        }
    }
}

/**
 * The Stabilize sub-tab body (gyro Phase 5, moved from DetailPanel):
 * the main flow carries the controls that shape the warp — the Off/On
 * switch (first enable seeds the default strength, the temporal-NR
 * precedent), the Mode choice (Smooth / Lock First Frame / Horizon
 * Lock), Strength and Zoom limit — plus the group's HONEST state per
 * its standing contract: the sync status line (the probe's verdict,
 * never fabricated — no log attached shows the no-data line and the
 * attach action) and the gyro attach/remove actions with the import
 * error line. The old group's standalone "Stabilization" header is GONE
 * here for the same reason as Crop's: the sub-tab labels the body, and
 * the toggle row already carries the name.
 *
 * The probe/gyro DETAILS collapse behind the plan's expandable advanced
 * section (plan: "gyro/sync/probe details as an expandable advanced
 * section"): the zoom-limit honesty readout (the probe's clip-wide
 * numbers — it moved out from under the slider it describes because the
 * plan classifies it a probe detail), the attached log's file name, the
 * ±500 ms sync trim (attached only) and the focal-length control with
 * the container reference line and the "intrinsics assumed" status. The
 * affordance is the scope/zebra "Show X / Hide X" text idiom; the open
 * state is UI-only rememberSaveable (which details are revealed, never
 * a grade value — the wheel band choice precedent) and starts collapsed
 * on every fresh visit of the body.
 */
@Composable
private fun FrameStabilizeBody(
    grade: GradeK,
    gyroAttached: Boolean,
    gyroSync: DoubleArray?,
    stabZoom: DoubleArray?,
    syncRefine: Boolean,
    onSyncRefineChange: (Boolean) -> Unit,
    gyroName: String?,
    containerFocalMm: Float,
    gyroImportError: String?,
    onAttachGyro: () -> Unit,
    onRemoveGyro: () -> Unit,
    onChange: (GradeK) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onToggleChange: (GradeK) -> Unit = {},
    onReset: (GradeK) -> Unit = {},
) {
    // Gyro mode is paused (owner decision 2026-11-23,
    // FeatureFlags.GYRO_STABILIZATION_ENABLED): the tab stays, the toggle
    // is shown disabled and OFF (the stored grade is untouched; the
    // render gate forces stabilization off), controls are hidden, and the
    // reason is stated. The image-based mode (plan 5b) will live here.
    if (!StabilizationGate.gyroWorkAllowed()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RawToggleRow(
                label = stringResource(R.string.editor_stabilization),
                checked = false,
                onCheckedChange = {},
                enabled = false,
            )
            Text(
                stringResource(R.string.editor_stabilization_gyro_paused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The probe's numbers drive the status line (pure decision in
        // EditorPreviewLogic.syncStatus): Refined (optical ±bound) /
        // Approximate (anchored ±bound) / Manual offset / No gyro data.
        // "Approximate" because the anchor offset is a coarse wall-clock
        // estimate (Sync.h: the receive lag is unobservable); "Refined"
        // when the M5 optical refinement ran and SUCCEEDED — the probe's
        // extended [3] syncKind reads 2 (a rejected refine keeps 1 and the
        // anchored verdict, honestly). gyroSync holds [anchored, offsetMs,
        // boundMs, focalMm, samples] from the attach-time probe; stabZoom
        // now carries [requiredZoom, appliedZoom, statusInt, syncKind,
        // syncOffsetMs, syncBoundMs] from the settle-time plan probe.
        val sync = EditorPreviewLogic.syncStatus(
            gyroAttached = gyroAttached,
            probeFailed = gyroSync == null,
            anchored = gyroSync != null && gyroSync[0] > 0.0,
            refined = stabZoom != null && stabZoom.size >= 6 &&
                stabZoom[3].toInt() == EditorPreviewLogic.SYNC_KIND_REFINED,
        )
        // The verdict → resource pair lives in the pure syncStatusRes (the
        // JVM locks it); the bound is the only verdict-dependent arg — the
        // refined line reads the plan probe's extended [5] (the measured
        // optical curve's bound; the attach-time probe's [2] is the
        // fallback when the plan probe has not answered yet), the anchored
        // line the attach-time [2].
        val boundMs = when (sync) {
            GyroSyncStatus.REFINED ->
                stabZoom?.get(5)?.toInt() ?: gyroSync?.get(2)?.toInt() ?: 0
            GyroSyncStatus.ANCHORED -> gyroSync?.get(2)?.toInt() ?: 0
            else -> 0
        }
        val statusText =
            if (sync == GyroSyncStatus.REFINED || sync == GyroSyncStatus.ANCHORED) {
                stringResource(EditorPreviewLogic.syncStatusRes(sync), boundMs)
            } else {
                stringResource(EditorPreviewLogic.syncStatusRes(sync))
            }
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
            // (2) — the GradeK ints, segmented like the aspect choice.
            // Not a drag: settles immediately at the full tier.
            Text(
                stringResource(R.string.editor_stabilization_mode),
                style = MaterialTheme.typography.labelLarge,
            )
            CompactSegmentedRow(Modifier.fillMaxWidth()) {
                val modes = listOf(
                    StabModeOption(R.string.editor_stabilization_mode_smooth, 0),
                    StabModeOption(R.string.editor_stabilization_mode_lock_first_frame, 1),
                    StabModeOption(R.string.editor_stabilization_mode_horizon_lock, 2),
                )
                modes.forEachIndexed { index, option ->
                    Segment(
                        selected = grade.stabilizationMode == option.value,
                        onClick = {
                            onToggleChange(grade.copy(stabilizationMode = option.value))
                        },
                        label = stringResource(option.labelRes),
                    )
                }
            }
            // Strength 0..100 (default 50, audit fix 2026-11-12): the smoothing amount.
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
                    onReset(
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
                    onReset(
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
            // Optical sync refinement switch (M5 commit b): shown only
            // with a gyro attached — the refine only ever runs on an
            // anchored estimate, so without a log the switch would be a
            // lie about what it controls. The status line above stays the
            // verdict source: flipping this ON shows "Refined (optical
            // ±X ms)" ONLY when the probe reports syncKind=2 (a rejected
            // refine keeps the honest "Approximate" line). The toggle
            // itself is the RawToggleRow idiom the stabilization master
            // switch rides above.
            if (gyroAttached) {
                RawToggleRow(
                    label = stringResource(R.string.editor_stabilization_sync_refine_toggle),
                    checked = syncRefine,
                    onCheckedChange = onSyncRefineChange,
                )
            }
            if (gyroAttached) {
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
            // ---- The expandable gyro/sync/probe section (plan) ----
            // UI-only state (the wheel band choice precedent): which
            // details are revealed, never a grade value. Collapsed by
            // default on every fresh visit of the body.
            var advancedOpen by rememberSaveable { mutableStateOf(false) }
            TextButton(
                onClick = { advancedOpen = !advancedOpen },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (advancedOpen) {
                            R.string.editor_stabilization_advanced_hide
                        } else {
                            R.string.editor_stabilization_advanced_show
                        }
                    )
                )
            }
            if (advancedOpen) {
                // The zoom-limit honesty readout (audit fix 2026-11-12,
                // Fix 5): the probe's clip-wide numbers — APPLIED shows
                // the effective crop the frames actually pay ("Crop
                // 1.05x"), LIMITED shows the limit-clamped crop ("Limited
                // to 1.10x") so the user SEES when the limit cuts the
                // warp's reach. Both show the APPLIED zoom (probe[1]):
                // that is the crop the frames pay. Null (refusal
                // statuses / no verdict / short probe) renders nothing —
                // never a fabricated zoom.
                val zoomReadout: Pair<StabZoomVerdict, Float>? =
                    stabZoom?.takeIf { it.size >= 3 }?.let { probe ->
                        EditorPreviewLogic.stabZoomVerdict(
                            statusInt = probe[2].toInt(),
                            requiredZoom = probe[0],
                            appliedZoom = probe[1],
                        )?.let { verdict -> verdict to probe[1].toFloat() }
                    }
                when (val readout = zoomReadout) {
                    null -> Unit
                    else -> Text(
                        stringResource(
                            if (readout.first == StabZoomVerdict.APPLIED) {
                                R.string.editor_stabilization_zoom_applied
                            } else {
                                R.string.editor_stabilization_zoom_limited
                            },
                            Format.formatZoom(readout.second),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gyroAttached && gyroName != null) {
                    Text(
                        stringResource(R.string.editor_stabilization_gyro_name, gyroName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            onReset(
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
                        onReset(
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
 * One aspect preset segmented-button entry (RAW-09): label + the GradeK
 * aspect int (0 Free, 1 16:9, 2 9:16, 3 4:3, 4 1:1 — CropState.ASPECT_*).
 */
private data class CropAspectOption(
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
    summary: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
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
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
            )
            if (summary != null) {
                // The record's input -> output summary: bodySmall on the
                // variant color so it reads as the row's caption, not a
                // second choice.
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (onEdit != null) {
            TextButton(
                onClick = onEdit,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(stringResource(R.string.editor_lut_edit_interp))
            }
        }
        if (onRemove != null) {
            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(stringResource(R.string.editor_lut_remove))
            }
        }
        // Keep the row's text off the screen edge (the sheet's radio-row
        // convention).
        Spacer(Modifier.width(8.dp))
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
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
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
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * THE CROP OVERLAY (owner request 2026-11-13 §2): handles-on-preview
 * cropping, composed as the FIRST child of the preview's anchor Box so
 * its coordinate space IS the video rect (the Box measures exactly the
 * video's bounds with the same previewBounds sizer the surface uses —
 * pointer pixels map 1:1 onto [CropState.handleAt]'s width/height).
 *
 * ONE SOURCE OF TRUTH: the overlay never owns crop state. It draws the
 * REAL grade's cropX/Y/W/H fractions and writes drags back through the
 * caller's callbacks — the screen routes those through the SAME
 * applyGradeEdit + SLIDER_GESTURE + settle pipeline the panel's four
 * fraction sliders use, so the sliders and the handles are two views of
 * one state and can never disagree. One handle drag is ONE undo step
 * (frames coalesce on the open SLIDER_GESTURE entry; the end callback's
 * gradeHistory.settle closes it), exactly like one slider drag.
 *
 * GESTURE ARBITRATION (the contract the preview relies on): the
 * overlay's touch surface covers the WHOLE video rect, but its
 * pointerInput consumes ONLY handle grabs. A down outside every handle's
 * touch square returns from the gesture block WITHOUT consuming, so the
 * event keeps flowing to the surface's zoom/pan handler and the hold
 * handler below it (awaitEachGesture's inter-gesture drain awaits the
 * Final pass without consuming — verified against the Compose 1.7.3
 * source — so the early return cannot starve them). The corner chips
 * (refresh/scope/zebra/fullscreen) are LATER siblings of the overlay:
 * their clickables consume the down before the overlay's Main-pass hit
 * test ever sees it, so they stay tappable on top of the overlay's
 * surface; a crop smaller than full moves its corner handles away from
 * the chips entirely.
 *
 * TOUCH VS VISUAL: each of the 8 handles is a 48dp TOUCH square
 * (DESIGN.md touch targets; the hit tolerance passed to handleAt is
 * 24dp so each square spans 48dp) but a much smaller drawn circle —
 * the touch target is generous where the finger aims, the visual stays
 * light. The drawn handle rides INSIDE the rect's corner/edge-midpoint
 * squares, so the visuals never sit outside the crop.
 *
 * SNAP SEMANTICS (the sliders' own): drag frames are continuous raw
 * fractions (snap = false — the slider's live drag values); release
 * quantizes to the 1 percent grid (snap = true — the slider's settle
 * semantics, CropState.FRACTION_STEP). The end callback fires on lift
 * AND on cancellation (a second finger stealing the stream to pinch:
 * the surface's pinch branch consumes everything on takeover, which
 * cancels this block mid-drag) — both are the gesture's end, and both
 * settle the same way.
 */
@Composable
private fun CropOverlay(
    rect: CropState.RectF,
    frameW: Int,
    frameH: Int,
    preset: Int,
    onDragStart: () -> Unit,
    onDragFrame: (CropState.RectF) -> Unit,
    onDragEnd: (CropState.RectF) -> Unit,
) {
    // rememberUpdatedState mirrors: the pointerInput below captures
    // these ONCE (its key is Unit), so every volatile input — the grade
    // fractions, the preset, the effective frame dims (swapped by a
    // quarter-turn rotate), the callbacks — reads through a State that
    // always holds the CURRENT value. The same pattern the render pump
    // uses for its own one-time captures.
    val currentRect by rememberUpdatedState(rect)
    val currentFrameW by rememberUpdatedState(frameW)
    val currentFrameH by rememberUpdatedState(frameH)
    val currentPreset by rememberUpdatedState(preset)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragFrame by rememberUpdatedState(onDragFrame)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val handleDescription = stringResource(R.string.editor_crop_overlay)
    // Color roles read in COMPOSITION (the draw lambda below is not a
    // composable scope): the scrim role dimmed to the corner controls'
    // own translucency (their surfaceContainerLowest.copy(alpha =
    // 0.6f) precedent — a full-opacity scrim would hide the discarded
    // region entirely, and judging a crop needs to see what is being
    // thrown away), primary for the border and the handles. Roles,
    // never hex (DESIGN.md).
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    val primaryColor = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = handleDescription }
            // ONE pointerInput for the whole overlay: the per-gesture
            // hit test below decides whether this surface owns the
            // stream (a handle grab) or passes it through untouched.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // Half of 48dp: each handle's touch square spans
                    // 48dp (DESIGN.md touch), the drawn circle is far
                    // smaller (see the drawing below).
                    val tolerance = 24.dp.toPx()
                    val handle = CropState.handleAt(
                        currentRect,
                        down.position.x, down.position.y,
                        w, h, tolerance,
                    )
                    // Not a handle grab: return WITHOUT consuming.
                    // The down keeps flowing to the surface's zoom/pan
                    // handler and the hold handler (pinch-zoom, pan,
                    // double-tap, hold all keep working), and
                    // awaitEachGesture's own inter-gesture drain
                    // awaits the Final pass WITHOUT consuming, so this
                    // early return cannot starve them of events.
                    if (handle == null) return@awaitEachGesture
                    // Handle grab: this surface owns the gesture. The
                    // consumed down cancels the hold window (a handle
                    // grab is not a before/after compare) and makes the
                    // hold handler's cleanTap false (a grab is not a
                    // fullscreen-chrome tap either).
                    down.consume()
                    currentOnDragStart()
                    // The drag base: the rect AS GRABBED — every frame
                    // applies the CUMULATIVE pointer delta to THIS
                    // rect (applyHandleDrag's contract), never a
                    // delta-on-delta chain that would drift.
                    val base = currentRect
                    val startX = down.position.x
                    val startY = down.position.y
                    var lastDx = 0f
                    var lastDy = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change =
                                event.changes.firstOrNull { it.id == down.id }
                            // The dragged finger lifted: the gesture's
                            // natural end.
                            if (change == null || !change.pressed) break
                            // A second finger over the video joins: the
                            // pinch takes over (the surface's pinch
                            // branch consumes everything from this
                            // event on). End the drag HERE, through the
                            // same settle path as a lift — one finger
                            // drags handles, two fingers pinch, never
                            // both at once.
                            if (event.changes.count { it.pressed } >= 2) break
                            lastDx = change.position.x - startX
                            lastDy = change.position.y - startY
                            change.consume()
                            // Continuous while dragging (snap = false):
                            // raw fractions under the finger, exactly
                            // the slider's live drag values.
                            currentOnDragFrame(
                                CropState.applyHandleDrag(
                                    base, handle, lastDx, lastDy,
                                    w, h, currentPreset,
                                    currentFrameW, currentFrameH,
                                    snap = false,
                                ),
                            )
                        }
                    } finally {
                        // EVERY end settles identically — lift, the
                        // second-finger steal above, or coroutine
                        // cancellation (the overlay leaving
                        // composition mid-drag: a sub-tab switch under
                        // another finger). The release snaps to the 1
                        // percent grid (snap = true, the sliders' own
                        // settle semantics), and the end callback runs
                        // the screen's DragEnded + history-settle
                        // route, so the open SLIDER_GESTURE entry can
                        // never leak into the next gesture's undo step.
                        currentOnDragEnd(
                            CropState.applyHandleDrag(
                                base, handle, lastDx, lastDy,
                                w, h, currentPreset,
                                currentFrameW, currentFrameH,
                                snap = true,
                            ),
                        )
                    }
                }
            },
    ) {
        // The video rect's pixel size — the overlay's whole coordinate
        // space (the anchor Box IS the video rect).
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val x0 = rect.x * w
        val y0 = rect.y * h
        val x1 = (rect.x + rect.w) * w
        val y1 = (rect.y + rect.h) * h
        // Dim scrim OUTSIDE the rect (four bands), never inside: the
        // kept region stays fully legible for judging the crop, and
        // the discarded region stays faintly visible for the same
        // reason.
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, 0f),
            size = Size(w, y0),
        )
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, y1),
            size = Size(w, h - y1),
        )
        drawRect(
            color = scrimColor,
            topLeft = Offset(0f, y0),
            size = Size(x0, y1 - y0),
        )
        drawRect(
            color = scrimColor,
            topLeft = Offset(x1, y0),
            size = Size(w - x1, y1 - y0),
        )
        // Thin border on the rect.
        drawRect(
            color = primaryColor,
            topLeft = Offset(x0, y0),
            size = Size(x1 - x0, y1 - y0),
            style = Stroke(width = 2.dp.toPx()),
        )
        // The 8 handles: corners + edge midpoints, drawn as small
        // primary circles well inside their 48dp touch squares.
        val handleRadius = 6.dp.toPx()
        val points = listOf(
            Offset(x0, y0), Offset(x1, y0), // top-left, top-right
            Offset(x0, y1), Offset(x1, y1), // bottom-left, bottom-right
            Offset((x0 + x1) / 2f, y0), // top edge midpoint
            Offset((x0 + x1) / 2f, y1), // bottom edge midpoint
            Offset(x0, (y0 + y1) / 2f), // left edge midpoint
            Offset(x1, (y0 + y1) / 2f), // right edge midpoint
        )
        for (p in points) {
            drawCircle(color = primaryColor, radius = handleRadius, center = p)
        }
    }
}

/**
 * AUD-02's link-state label for the editor's honesty row: the same
 * timeline_audio_state_* strings the audio settings sheet shows, so the
 * two surfaces name a state identically (AudioSettingsSheet keeps its own
 * private twin — the sheet and the editor each stay self-contained).
 */
private fun audioLinkStateLabel(state: AudioLinkState): Int = when (state) {
    AudioLinkState.ATTACHED -> R.string.timeline_audio_state_attached
    AudioLinkState.DETACHED -> R.string.timeline_audio_state_detached
    AudioLinkState.REMOVED -> R.string.timeline_audio_state_removed
    AudioLinkState.LINKED_EDITED -> R.string.timeline_audio_state_linked_edited
}
