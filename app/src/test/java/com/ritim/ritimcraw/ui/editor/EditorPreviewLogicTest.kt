package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.RenderBackend
import com.ritim.ritimcraw.engine.SourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorPreviewLogic tests: the pure decision matrix for the preview area
 * (which state shows when) and the render-ability gate, JVM-testable without
 * Compose or the native lib.
 */
class EditorPreviewLogicTest {

    // ---- stateOf matrix ----

    @Test
    fun idle_when_nothing_requested() {
        assertEquals(
            PreviewState.IDLE,
            EditorPreviewLogic.stateOf(
                renderRequested = false, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
            ),
        )
    }

    @Test
    fun rendering_wins_over_everything() {
        // A re-render is in flight: show the spinner state even while a good
        // frame or a previous failure is on screen.
        assertEquals(
            PreviewState.RENDERING,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = true,
                hasFrame = true, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun frame_beats_failure() {
        // A failed re-render keeps the last good frame on screen (grading
        // continues on the stale frame rather than yanking the image away).
        assertEquals(
            PreviewState.FRAME,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = true, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun failed_only_when_requested_and_no_frame() {
        assertEquals(
            PreviewState.FAILED,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = false, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun no_frame_no_failure_no_request_is_idle() {
        assertEquals(
            PreviewState.IDLE,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
            ),
        )
    }

    // ---- stateOf matrix: the GPU session-open window (owner finding
    // 2026-11-14 — the seconds-class probe + parity + open + first
    // develop on a fresh 12MP clip read as broken "No preview yet") ----

    @Test
    fun gpu_preparing_without_a_frame_is_preparing() {
        // The open window with nothing on screen yet: PREPARING — the
        // spinner + "Preparing preview…" placeholder, never the idle text.
        assertEquals(
            PreviewState.PREPARING,
            EditorPreviewLogic.stateOf(
                renderRequested = false, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
                gpuPreparing = true,
            ),
        )
    }

    @Test
    fun a_stale_frame_keeps_showing_while_gpu_reprepares() {
        // A frame is on screen: it stays (grading continues on the stale
        // frame) — the preparing window never yanks the image away.
        assertEquals(
            PreviewState.FRAME,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = true, lastRenderFailed = false,
                gpuPreparing = true,
            ),
        )
    }

    @Test
    fun cpu_rendering_wins_over_gpu_preparing() {
        // A CPU render is in flight during the open window: the spinner
        // state shows (the render is the live work, the open is parallel).
        assertEquals(
            PreviewState.RENDERING,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = true,
                hasFrame = false, lastRenderFailed = false,
                gpuPreparing = true,
            ),
        )
    }

    @Test
    fun a_failed_render_still_fails_while_gpu_prepares() {
        // A real render failure is the honest verdict — the preparing
        // window never masks it with a spinner.
        assertEquals(
            PreviewState.FAILED,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = false, lastRenderFailed = true,
                gpuPreparing = true,
            ),
        )
    }

    @Test
    fun idle_stays_idle_without_gpu_preparing() {
        // The defaulted input: every pre-2026-11-14 call site (and the
        // true idle — nothing requested, nothing preparing) keeps IDLE.
        assertEquals(
            PreviewState.IDLE,
            EditorPreviewLogic.stateOf(
                renderRequested = false, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
                gpuPreparing = false,
            ),
        )
    }

    // ---- canRender gate ----

    @Test
    fun negative_fd_cannot_render() {
        assertFalse(EditorPreviewLogic.canRender(fd = -1, nativeAvailable = true))
    }

    @Test
    fun zero_fd_can_render() {
        // fd 0 is a valid POSIX fd; only negatives mean "no source".
        assertTrue(EditorPreviewLogic.canRender(fd = 0, nativeAvailable = true))
    }

    @Test
    fun missing_native_engine_cannot_render() {
        // Placeholder fallback: without the .so the editor stays a working
        // grade panel — the button is disabled, the placeholder shows.
        assertFalse(EditorPreviewLogic.canRender(fd = 5, nativeAvailable = false))
    }

    @Test
    fun fd_plus_engine_can_render() {
        assertTrue(EditorPreviewLogic.canRender(fd = 5, nativeAvailable = true))
    }

    // ---- RAW-stage toggle visibility (encoded clips hide the CFA rows) ----

    @Test
    fun mcraw_shows_raw_stage_toggles() {
        // Highlight Reconstruction / Keep Vignetting drive MCRAW develop
        // stages — they belong on the Color tab for RAW clips.
        assertTrue(EditorPreviewLogic.showRawStageToggles(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_raw_stage_toggles() {
        // Decoded YUV has no CFA stage to reconstruct or vignette: the two
        // RAW rows are meaningless on a Mi-Log MP4 clip. Everything else
        // (sliders, As Shot) stays — the full Grade still passes through.
        assertFalse(EditorPreviewLogic.showRawStageToggles(SourcePath.ENCODED))
    }

    // ---- clip info resolution preference (owner request 2026-11-11 §2) ----

    @Test
    fun session_recorded_resolution_wins() {
        // The session's RECORDED frame size (the first frame's real
        // dimensions from the source) is the clip's resolution; the
        // persisted import stat is only the fallback.
        assertEquals(4096, EditorPreviewLogic.recordedWidth(4096, 3840))
    }

    @Test
    fun persisted_stat_fills_the_gap_when_session_silent() {
        // Session-less editor (no usable fd / failed open): the persisted
        // import stat still shows something instead of 0.
        assertEquals(3840, EditorPreviewLogic.recordedWidth(0, 3840))
    }

    @Test
    fun zero_import_record_is_shown_not_rejected() {
        // An old import that recorded 0x0 shows 0 — the spec forbids
        // demanding a re-import for it; whatever is known is shown.
        assertEquals(0, EditorPreviewLogic.recordedWidth(0, 0))
    }

    // ---- system back destination (owner finding 2026-11-11) ----

    @Test
    fun back_in_fullscreen_exits_fullscreen_first() {
        assertEquals(
            EditorBackDestination.EXIT_FULLSCREEN,
            EditorPreviewLogic.onBack(fullscreen = true),
        )
    }

    @Test
    fun back_outside_fullscreen_leaves_the_editor() {
        // The bug: the BackHandler was enabled only in fullscreen, so a
        // press in the normal editor fell through to the system and
        // exited the app. The handler is now always active; the press
        // returns to the library via onBack.
        assertEquals(
            EditorBackDestination.LEAVE_EDITOR,
            EditorPreviewLogic.onBack(fullscreen = false),
        )
    }

    // ---- fullscreen chrome tap toggle (owner request 2026-11-11d section 16.2) ----

    @Test
    fun clean_tap_toggles_chrome_only_in_fullscreen() {
        // Section 16.2: "A preview tap may toggle control visibility if
        // this can be implemented safely without interfering with current
        // gestures." Only in fullscreen: outside it a preview tap has no
        // job (the panel and nav are structural, not overlays).
        assertTrue(EditorPreviewLogic.togglesFullscreenChrome(inFullscreen = true))
    }

    @Test
    fun preview_tap_outside_fullscreen_does_nothing() {
        // The normal editor keeps its chrome permanently — a stray tap on
        // the footage must not hide the adjustment panel or the nav bar.
        assertFalse(EditorPreviewLogic.togglesFullscreenChrome(inFullscreen = false))
    }

    // ---- Temporal NR group visibility (MCRAW only; DN-05..DN-09) ----

    @Test
    fun mcraw_shows_temporal_nr() {
        // Temporal denoise merges neighboring CFA frames — the group
        // belongs on the Detail tab for RAW clips.
        assertTrue(EditorPreviewLogic.showTemporalNr(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_temporal_nr() {
        // The encoded (Mi-Log MP4) path takes the same Grade but the bridge
        // ignores the temporal fields there: no CFA, no neighbor frames, so
        // the group is hidden rather than showing a dead control.
        assertFalse(EditorPreviewLogic.showTemporalNr(SourcePath.ENCODED))
    }

    // ---- Stabilization group visibility (gyro Phase 5) ----

    @Test
    fun mcraw_shows_stabilization() {
        // The warp consumes the gyro log against the clip's CFA frames —
        // the group belongs on the Detail tab for RAW clips.
        assertTrue(EditorPreviewLogic.showStabilization(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_stabilization() {
        // The encoded (Mi-Log MP4) path has no CFA frames and no gyro
        // anchor: the group is hidden rather than showing a dead control.
        assertFalse(EditorPreviewLogic.showStabilization(SourcePath.ENCODED))
    }

    // ---- Color tools visibility (COL-13: wheel + split tone, MCRAW only) ----

    @Test
    fun mcraw_shows_color_tools() {
        // Wheel (stage 18) and split tone (stage 19) are MCRAW-only
        // tone-band color math — the section belongs on the Color tab for
        // RAW clips.
        assertTrue(EditorPreviewLogic.showColorTools(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_color_tools() {
        // The encoded path takes the same Grade but the bridge ignores the
        // color-package fields there (it grades via wb/temp/tint/exposure):
        // the section is hidden rather than showing dead controls.
        assertFalse(EditorPreviewLogic.showColorTools(SourcePath.ENCODED))
    }

    // ---- sync status verdict (gyro Phase 5: the status line's decision) ----

    @Test
    fun no_gyro_is_no_data_regardless_of_probe_shape() {
        // No sidecar .gcsv attached: the honest no-data state, even if a
        // stale probe result lingers — stabilization stays a no-op.
        assertEquals(
            GyroSyncStatus.NO_DATA,
            EditorPreviewLogic.syncStatus(
                gyroAttached = false, probeFailed = false,
                anchored = true, refined = false,
            ),
        )
    }

    @Test
    fun failed_probe_is_no_data_never_a_fabricated_verdict() {
        // A probe that failed reads as NoData: the attach flow removes the
        // failed entry, so this is the transient between failure and
        // removal — never an invented Anchored/Manual claim.
        assertEquals(
            GyroSyncStatus.NO_DATA,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = true,
                anchored = false, refined = false,
            ),
        )
    }

    @Test
    fun attached_and_anchored_is_anchored() {
        // Both wall-clock anchors present (clip recvd vs log header):
        // Anchored — rendered as an approximate estimate with the probe's
        // error bound as the ± value (the label says so since the
        // 2026-11-12 honesty fix; the verdict enum is unchanged).
        assertEquals(
            GyroSyncStatus.ANCHORED,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false,
                anchored = true, refined = false,
            ),
        )
    }

    @Test
    fun attached_without_anchors_is_manual() {
        // Anchors absent: the trim slider is the user's offset — Manual.
        assertEquals(
            GyroSyncStatus.MANUAL,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false,
                anchored = false, refined = false,
            ),
        )
    }

    // ---- refined verdict (M5 commit b: the optical refinement's success) ----

    @Test
    fun a_successful_refinement_is_refined_even_though_anchor_based() {
        // The optical refinement SUCCEEDED (the probe's syncKind=2): the
        // estimate is still anchor-based, but the bound is now the measured
        // optical curve's — the status line says "Refined (optical ±X ms)"
        // instead of "Approximate". REFINED takes precedence over ANCHORED.
        assertEquals(
            GyroSyncStatus.REFINED,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false,
                anchored = true, refined = true,
            ),
        )
    }

    @Test
    fun a_rejected_refinement_keeps_the_anchored_verdict() {
        // The refinement ran but was REJECTED (too few pairs, no curve): the
        // bridge keeps syncKind=1 and the plan rides the anchor estimate —
        // the status line stays honestly "Approximate", never a "Refined"
        // claim the plan does not back.
        assertEquals(
            GyroSyncStatus.ANCHORED,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false,
                anchored = true, refined = false,
            ),
        )
    }

    @Test
    fun a_refinement_verdict_never_rescues_a_manual_estimate() {
        // refined=true with anchored=false cannot occur (the bridge only
        // refines ANCHORED estimates, and a rejected refine keeps the
        // anchored verdict) — but the pure mapping stays honest anyway:
        // no anchors, no anchored verdict to refine, Manual wins.
        assertEquals(
            GyroSyncStatus.MANUAL,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false,
                anchored = false, refined = true,
            ),
        )
    }

    @Test
    fun a_refinement_verdict_never_rescues_no_data() {
        // The same forward-compat guard at the top of the mapping: no gyro
        // attached beats every downstream verdict, refined included.
        assertEquals(
            GyroSyncStatus.NO_DATA,
            EditorPreviewLogic.syncStatus(
                gyroAttached = false, probeFailed = false,
                anchored = true, refined = true,
            ),
        )
    }

    // ---- sync-kind tokens (M5 commit b: the timing line's contract) ----

    @Test
    fun sync_kind_tokens_match_the_timing_line_spellings() {
        // The EXACT spellings the bridge's append-only
        // ` sync=%s offsetMs=%.3f` tail writes — the owner's ON/OFF A/B
        // greps for "sync=refined" vs "sync=anchored" in logcat, so a
        // silent rename on either side breaks the experiment's readout.
        // The bridge's stabSyncKindName and this mirror must stay frozen
        // together; the JVM locks the Kotlin half.
        assertEquals("manual", EditorPreviewLogic.syncToken(EditorPreviewLogic.SYNC_KIND_MANUAL))
        assertEquals("anchored", EditorPreviewLogic.syncToken(EditorPreviewLogic.SYNC_KIND_ANCHORED))
        assertEquals("refined", EditorPreviewLogic.syncToken(EditorPreviewLogic.SYNC_KIND_REFINED))
    }

    @Test
    fun an_unknown_sync_kind_has_no_token_never_a_guess() {
        // Forward compat: a future bridge verdict kind reads as no token
        // (the status-line decision then keeps the honest anchored/manual
        // verdict from the attach-time probe), never a fabricated
        // spelling.
        assertNull(EditorPreviewLogic.syncToken(3))
        assertNull(EditorPreviewLogic.syncToken(-1))
    }

    // ---- status-line strings (M5 commit b: the verdict → resource pair) ----

    @Test
    fun each_sync_verdict_maps_to_its_own_status_line() {
        // One verdict, one line — the extracted pure mapping the JVM locks
        // so a verdict can never render the wrong line's text: REFINED →
        // "Refined (optical ±%1$d ms)", ANCHORED → "Approximate (anchored
        // ±%1$d ms)", MANUAL → "Manual offset", NO_DATA → "No gyro data".
        // The en/tr resources carry the spellings; this locks the PAIRING.
        assertEquals(
            R.string.editor_stabilization_sync_refined,
            EditorPreviewLogic.syncStatusRes(GyroSyncStatus.REFINED),
        )
        assertEquals(
            R.string.editor_stabilization_sync_anchored,
            EditorPreviewLogic.syncStatusRes(GyroSyncStatus.ANCHORED),
        )
        assertEquals(
            R.string.editor_stabilization_sync_manual,
            EditorPreviewLogic.syncStatusRes(GyroSyncStatus.MANUAL),
        )
        assertEquals(
            R.string.editor_stabilization_sync_no_data,
            EditorPreviewLogic.syncStatusRes(GyroSyncStatus.NO_DATA),
        )
    }

    // ---- effective focal (gyro Phase 5: override vs container) ----

    @Test
    fun focal_override_wins_over_container() {
        // The grade's editable mm field is the user's explicit choice; the
        // container value is only the default it was seeded from.
        assertEquals(50f, EditorPreviewLogic.effectiveFocalMm(50f, 23.95f))
    }

    @Test
    fun container_focal_fills_the_gap_when_no_override() {
        // 0 = "use the container": the clip's own 35mm-equivalent value.
        assertEquals(23.95f, EditorPreviewLogic.effectiveFocalMm(0f, 23.95f))
    }

    @Test
    fun no_focal_anywhere_reads_zero() {
        // Container absent too: 0 — the group shows the honest
        // intrinsics-assumed state and the user must set a value.
        assertEquals(0f, EditorPreviewLogic.effectiveFocalMm(0f, 0f))
    }

    // ---- focal px conversion (gyro Phase 5: the flagged intrinsics math) ----

    @Test
    fun focal_px_is_width_times_mm_over_36() {
        // f_px = width · mm / 36 — the full-frame horizontal convention,
        // mirroring the core's focalLengthPxFromEquivalent exactly.
        assertEquals(1280f * 23.95f / 36f, EditorPreviewLogic.focalLengthPxFromEquivalent(23.95f, 1280))
    }

    @Test
    fun non_positive_focal_refuses_rather_than_fabricates() {
        // A missing mm (or width) yields 0: the caller refuses the warp
        // instead of fabricating an intrinsics-free homography.
        assertEquals(0f, EditorPreviewLogic.focalLengthPxFromEquivalent(0f, 1280))
        assertEquals(0f, EditorPreviewLogic.focalLengthPxFromEquivalent(-5f, 1280))
        assertEquals(0f, EditorPreviewLogic.focalLengthPxFromEquivalent(23.95f, 0))
    }

    // ---- stabilization readiness (gyro Phase 5: the warp's gate) ----

    @Test
    fun ready_needs_toggle_gyro_and_focal() {
        // The honest gate: toggle on, a gyro log attached, and a usable
        // pixel focal (container or override) — all three.
        assertTrue(
            EditorPreviewLogic.stabilizationReady(
                stabilizationOn = true, gyroAttached = true, focalMm = 23.95f,
            ),
        )
    }

    @Test
    fun missing_any_input_is_not_ready() {
        // Any missing input reads as the no-op pass-through; the group
        // still shows with the status line explaining why.
        assertFalse(
            EditorPreviewLogic.stabilizationReady(
                stabilizationOn = false, gyroAttached = true, focalMm = 23.95f,
            ),
        )
        assertFalse(
            EditorPreviewLogic.stabilizationReady(
                stabilizationOn = true, gyroAttached = false, focalMm = 23.95f,
            ),
        )
        assertFalse(
            EditorPreviewLogic.stabilizationReady(
                stabilizationOn = true, gyroAttached = true, focalMm = 0f,
            ),
        )
    }

    // ---- zoom-limit honesty verdict (audit fix 2026-11-12, Fix 5) ----

    @Test
    fun applied_when_limit_covers_the_need() {
        // required <= applied (the clamp was a no-op): the honest readout
        // is the crop actually paid — APPLIED, rendered as "crop 1.05x".
        assertEquals(
            StabZoomVerdict.APPLIED,
            EditorPreviewLogic.stabZoomVerdict(
                statusInt = EditorPreviewLogic.STAB_STATUS_OK,
                requiredZoom = 1.05, appliedZoom = 1.05,
            ),
        )
    }

    @Test
    fun limited_when_required_exceeds_the_clamp() {
        // required > applied EXACTLY when the zoom limit bit (the core
        // computes applied = min(required, limit) from the same doubles):
        // the readout flags the limit, rendered as "limited to 1.10x".
        assertEquals(
            StabZoomVerdict.LIMITED,
            EditorPreviewLogic.stabZoomVerdict(
                statusInt = EditorPreviewLogic.STAB_STATUS_OK,
                requiredZoom = 1.23, appliedZoom = 1.10,
            ),
        )
    }

    @Test
    fun every_warping_status_shows_a_verdict() {
        // The core's stabWarps set: Ok / IntrinsicsAssumed / LowOverlap all
        // run the warp, so all three carry an honest zoom — the assumed-
        // intrinsics and low-overlap paths show the same readout as the
        // clean one (their status lines already flag the degradation).
        for (status in listOf(
            EditorPreviewLogic.STAB_STATUS_OK,
            EditorPreviewLogic.STAB_STATUS_INTRINSICS_ASSUMED,
            EditorPreviewLogic.STAB_STATUS_LOW_OVERLAP,
        )) {
            assertEquals(
                StabZoomVerdict.APPLIED,
                EditorPreviewLogic.stabZoomVerdict(
                    statusInt = status,
                    requiredZoom = 1.05, appliedZoom = 1.05,
                ),
            )
        }
    }

    @Test
    fun refusal_statuses_render_no_readout() {
        // Off / NoImu / Unsynced refuse BEFORE the zoom math: the warp does
        // not run, so no zoom exists to show — null, never a fabricated
        // "crop 1.00x" over an unstabilized frame.
        for (status in listOf(
            EditorPreviewLogic.STAB_STATUS_OFF,
            EditorPreviewLogic.STAB_STATUS_NO_IMU,
            EditorPreviewLogic.STAB_STATUS_UNSYNCED,
        )) {
            assertNull(
                EditorPreviewLogic.stabZoomVerdict(
                    statusInt = status,
                    requiredZoom = 1.23, appliedZoom = 1.10,
                ),
            )
        }
    }

    @Test
    fun unknown_status_is_null_never_a_guess() {
        // Forward compat: a future core status int the mirror has not
        // learned reads as no readout, never a guessed verdict.
        assertNull(
            EditorPreviewLogic.stabZoomVerdict(
                statusInt = 6,
                requiredZoom = 1.05, appliedZoom = 1.05,
            ),
        )
        assertNull(
            EditorPreviewLogic.stabZoomVerdict(
                statusInt = -1,
                requiredZoom = 1.05, appliedZoom = 1.05,
            ),
        )
    }

    // ---- crop overlay (owner request 2026-11-13 §2) ----

    @Test
    fun crop_overlay_visible_on_frame_crop_for_both_clip_kinds() {
        // The overlay is the Crop sub-tab's own surface: FRAME + Crop
        // shows it on an MCRAW (stabilization exists but is NOT the
        // open sub-tab) and on an encoded clip (no stabilization at
        // all) — the sanitize resolves Crop either way.
        assertTrue(
            EditorPreviewLogic.cropOverlayVisible(
                tab = EditorCategories.FRAME,
                subTab = EditorCategories.FRAME_CROP,
                showStabilization = true,
            ),
        )
        assertTrue(
            EditorPreviewLogic.cropOverlayVisible(
                tab = EditorCategories.FRAME,
                subTab = EditorCategories.FRAME_CROP,
                showStabilization = false,
            ),
        )
    }

    @Test
    fun crop_overlay_hidden_on_stabilize_subtab() {
        // FRAME + Stabilize: the stabilization group is the open sub-
        // tab, not the crop — no overlay (and an MCRAW is the only kind
        // that can even be here).
        assertFalse(
            EditorPreviewLogic.cropOverlayVisible(
                tab = EditorCategories.FRAME,
                subTab = EditorCategories.FRAME_STABILIZE,
                showStabilization = true,
            ),
        )
    }

    @Test
    fun crop_overlay_visible_for_stale_saved_stabilize_on_encoded() {
        // The panel's own sanitized dispatch: a grade saved with the
        // Stabilize sub-tab open, reopened on an encoded clip (no
        // stabilization), sanitizes to CROP — the panel shows Crop, so
        // the overlay shows too (visibility can never drift from what
        // the panel dispatches).
        assertTrue(
            EditorPreviewLogic.cropOverlayVisible(
                tab = EditorCategories.FRAME,
                subTab = EditorCategories.FRAME_STABILIZE,
                showStabilization = false,
            ),
        )
    }

    @Test
    fun crop_overlay_hidden_on_every_other_tab() {
        // Only the FRAME tab hosts the overlay; LIGHT/COLOR/DETAIL
        // never show it whatever the sub-tab value.
        for (tab in listOf(
            EditorCategories.LIGHT,
            EditorCategories.COLOR,
            EditorCategories.DETAIL,
        )) {
            assertFalse(
                EditorPreviewLogic.cropOverlayVisible(
                    tab = tab,
                    subTab = EditorCategories.FRAME_CROP,
                    showStabilization = true,
                ),
            )
        }
    }

    @Test
    fun display_grade_neutralizes_the_crop_group_only() {
        // The display render behind the overlay: full rect, FREE
        // aspect, zero straighten — every crop-group field at its
        // default — while every OTHER grade field passes through
        // untouched (the swap is display-only; the real grade keeps
        // its crop).
        val graded = GradeK(
            exposureEv = 1.25f,
            cropAspect = 2,
            cropX = 0.1f,
            cropY = 0.2f,
            cropW = 0.5f,
            cropH = 0.4f,
            straightenDeg = 3.5f,
        )
        val display = EditorPreviewLogic.displayGradeForCropOverlay(graded)
        assertEquals(GradeK().cropAspect, display.cropAspect)
        assertEquals(GradeK().cropX, display.cropX, 0f)
        assertEquals(GradeK().cropY, display.cropY, 0f)
        assertEquals(GradeK().cropW, display.cropW, 0f)
        assertEquals(GradeK().cropH, display.cropH, 0f)
        assertEquals(GradeK().straightenDeg, display.straightenDeg, 0f)
        assertEquals(graded.exposureEv, display.exposureEv, 0f)
        // The input is untouched — copy, never mutate.
        assertEquals(2, graded.cropAspect)
        assertEquals(0.5f, graded.cropW, 0f)
    }

    @Test
    fun display_grade_of_neutral_crop_is_unchanged() {
        // A neutral grade swaps to itself: entering the overlay on an
        // uncropped clip changes nothing about what renders.
        val neutral = GradeK()
        assertEquals(neutral, EditorPreviewLogic.displayGradeForCropOverlay(neutral))
    }

    // ---- GPU preview eligibility (M4 commit b) ----

    @Test
    fun gpu_eligible_for_a_neutral_covered_grade() {
        // The fast path: a neutral grade (every fragment-covered stage at
        // its default) with no warp, no crop, zebra off — the GPU window
        // path may serve.
        assertTrue(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    @Test
    fun gpu_eligible_for_the_fragment_covered_controls() {
        // The controls the GPU session's render signature carries — the
        // five base ones (exposure 14, highlights 15, highlight
        // reconstruction 3, vignetting 2, tonemap 23) plus the ten
        // pointwise stages' raw values since the pointwise port (black
        // point 9, white point 10, shadows 11, midtones 12, contrast 13,
        // temperature 16, tint 17, the color wheel 18, split tone 19,
        // saturation 20) — all fragment-covered, so the GPU path may
        // serve a multi-control grade end to end. This is the
        // eligibility the playback use case rides: grade at HIGH with
        // AgX tonemap, the owner's routine highlights recovery (-55 —
        // the stage-15 shoulder behind uHighlights), and a real
        // multi-control move (shadows + manual temperature + saturation
        // + the color wheel + split tone) all at once.
        assertTrue(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(
                    exposureEv = 0.7f,
                    highlights = -55f,
                    highlightReconstruction = false,
                    keepVignetting = false,
                    tonemap = 1,
                    blackPoint = 5f,
                    whitePoint = -5f,
                    shadows = 55f,
                    midtones = -55f,
                    contrast = 100f,
                    tempK = 6500f,
                    tint = 30f,
                    saturation = -40f,
                    asShot = false,
                    wheelShadowX = 0.25f,
                    wheelShadowY = -0.25f,
                    wheelMidX = 0.5f,
                    wheelMidY = -0.5f,
                    wheelHighX = 0.75f,
                    wheelHighY = -0.75f,
                    splitShadowHue = 30f,
                    splitHighlightHue = 210f,
                    splitBalance = -25f,
                ),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    @Test
    fun zebra_forces_the_cpu_path() {
        // Zebra derives from the PUBLISHED frame; the GPU pack's 256
        // long edge would paint blocky stripes, and with the Image
        // suppressed they would be invisible anyway. Zebra is a display
        // feature — its rule rides the request.
        assertFalse(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = true,
            )
        )
    }

    @Test
    fun an_active_crop_forces_the_cpu_path() {
        // PublishCrop resamples an active crop and the GPU window draw is
        // aspect-fit UNCROPPED — a display mismatch. The same inline test
        // PublishCrop.plan runs (straighten or non-full rect).
        assertFalse(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(cropW = 0.5f, cropH = 1f),
                gyroWarpActive = false,
                cropActive = true,
                zebraOn = false,
            )
        )
    }

    @Test
    fun a_gyro_warp_forces_the_cpu_path() {
        // The GPU window draw is un-warped; an active stabilization warp
        // (the grade's toggle with a resolved gyro path) takes the CPU
        // wholesale.
        assertFalse(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(stabilization = true),
                gyroWarpActive = true,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    @Test
    fun an_uncovered_stage_forces_the_cpu_path() {
        // Stage 8 (spatial noise reduction) is NOT fragment-covered — the
        // export gate's exact rule, mirrored for the preview: the grade
        // takes the CPU path wholesale.
        assertFalse(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(noiseReduction = 25f),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    @Test
    fun an_uncovered_pre_stage_forces_the_cpu_path() {
        // Temporal NR with a strength is a PRE-stage (the export gate's
        // fragmentUncoveredPreStages) — CPU wholesale.
        assertFalse(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(temporalNr = true, temporalStrength = 30f),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    @Test
    fun a_stabilization_toggle_without_a_warp_stays_eligible() {
        // The grade's toggle alone is inert for eligibility — the warp
        // fact (a resolved gyro path) is what the export wiring computes,
        // and the display grade renders un-warped when no log is
        // attached. Mirrors the pump's per-request fact.
        assertTrue(
            EditorPreviewLogic.gpuPreviewEligible(
                grade = GradeK(stabilization = true),
                gyroWarpActive = false,
                cropActive = false,
                zebraOn = false,
            )
        )
    }

    // ---- the preview capability gate (M4 commit c) ----

    /** [contextOk, glesMajor, glesMinor, computeOk, floatFboOk, maxTextureSize]. */
    private fun probePass() = doubleArrayOf(1.0, 3.0, 1.0, 1.0, 1.0, 8192.0)

    /** [ran, maxDiffLsb, mismatchCount, samples] — a parity run inside tolerance. */
    private fun parityPass() = doubleArrayOf(1.0, 1.0, 0.0, 1_000_000.0)

    @Test
    fun gates_assemble_from_a_passing_probe_and_parity() {
        // The M3 export gate's assembly, mirrored: a probe with context +
        // float-FBO green pays the parity, the parity passes inside the
        // 1-LSB tolerance, and the fragment gates allow the GPU path.
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = probePass(),
            parityRaw = parityPass(),
            parityDetail = null,
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.computeOk)
        assertTrue(gates.floatFboOk)
        assertTrue(gates.parityOk)
        assertTrue(gates.fragmentAllowed())
    }

    @Test
    fun a_null_probe_keeps_the_all_false_default_gates() {
        // The seam's honest no-result (unavailable guard, engine down, a
        // JNI failure) never fabricates a gate — the conservative
        // direction: the GPU path stays closed and the fallback line
        // names every gate (no leg ran).
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = null,
            parityRaw = null,
            parityDetail = null,
        )
        assertFalse(gates.fragmentAllowed())
        assertEquals(
            "no-egl-context,no-compute,no-float-fbo,parity-not-verified",
            gates.fallbackReason(),
        )
    }

    @Test
    fun a_red_probe_reports_partial_gates_and_skips_the_parity_verdict() {
        // Context green but float-FBO red: the parity leg was never paid
        // (running it would only produce a predictable failure), so the
        // gates report the probe's own partial verdict — parityOk stays
        // false even though a parity array was passed (a caller that
        // ignored the probe's verdict would be fabricating a run).
        val probeNoFbo = doubleArrayOf(1.0, 3.0, 1.0, 1.0, 0.0, 8192.0)
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = probeNoFbo,
            parityRaw = parityPass(),
            parityDetail = null,
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.computeOk)
        assertFalse(gates.floatFboOk)
        assertFalse(gates.parityOk)
        assertFalse(gates.fragmentAllowed())
    }

    @Test
    fun a_parity_mismatch_fails_the_gate() {
        // A real run with real numbers beyond the tolerance (2 LSB) is a
        // FAIL verdict — parityOk false, the GPU path stays closed. The
        // gates never paper over a measured deviation.
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = probePass(),
            parityRaw = doubleArrayOf(1.0, 2.0, 5000.0, 1_000_000.0),
            parityDetail = null,
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.floatFboOk)
        assertFalse(gates.parityOk)
        assertFalse(gates.fragmentAllowed())
    }

    @Test
    fun a_parity_that_never_ran_fails_the_gate() {
        // ran=false is NOT_RUN (the harness failed before the compare);
        // parityOk false — parity comes only from a real, completed run.
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = probePass(),
            parityRaw = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            parityDetail = "eglCreateContext",
        )
        assertFalse(gates.parityOk)
        assertFalse(gates.fragmentAllowed())
    }

    @Test
    fun a_null_parity_after_a_green_probe_fails_the_gate() {
        // The parity seam's honest null (a JNI failure after a green
        // probe) is NOT a pass — parityOk false, the fallback line names
        // parity-not-verified.
        val gates = EditorPreviewLogic.previewCapabilityGates(
            probeRaw = probePass(),
            parityRaw = null,
            parityDetail = null,
        )
        assertTrue(gates.contextOk)
        assertTrue(gates.floatFboOk)
        assertFalse(gates.parityOk)
        assertEquals(
            "parity-not-verified",
            gates.fragmentFallbackReason(),
        )
    }

    @Test
    fun a_malformed_probe_array_throws_the_contract_drift() {
        // fromArray's require: a contract drift must be loud, never
        // papered over — the screen's gate block catches it into the
        // all-false gates, so the drift costs the GPU path, never the
        // preview.
        try {
            EditorPreviewLogic.previewCapabilityGates(
                probeRaw = doubleArrayOf(1.0, 3.0, 1.0),
                parityRaw = null,
                parityDetail = null,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("6 values"))
        }
    }

    @Test
    fun probe_pays_the_parity_only_on_the_context_and_float_fbo_gates() {
        // The cost-aware order, mirrored: a red probe never pays the
        // parity run. The fragment chain is a DRAW pipeline — compute is
        // NOT in the predicate (a compute-less device still pays).
        assertTrue(EditorPreviewLogic.probePaysParity(probePass()))
        assertFalse(EditorPreviewLogic.probePaysParity(null))
        assertFalse(EditorPreviewLogic.probePaysParity(doubleArrayOf(0.0, 3.0, 1.0, 1.0, 1.0, 8192.0)))
        assertFalse(EditorPreviewLogic.probePaysParity(doubleArrayOf(1.0, 3.0, 1.0, 1.0, 0.0, 8192.0)))
        // Compute red, both real gates green: the parity still runs.
        assertTrue(
            EditorPreviewLogic.probePaysParity(doubleArrayOf(1.0, 3.0, 1.0, 0.0, 1.0, 8192.0))
        )
    }

    @Test
    fun fallback_lines_carry_the_m3_vocabulary() {
        // ONE grep covers the export and the preview logs: the M3
        // developFallbackLine format, verbatim, for the AUTO policy leg
        // and the gate-failure leg.
        assertEquals(
            "render backend fallback: requested=AUTO effective=CPU reason=auto-stays-cpu",
            EditorPreviewLogic.backendFallbackLine(RenderBackend.AUTO, "auto-stays-cpu"),
        )
        assertEquals(
            "render backend fallback: requested=GPU_EXPERIMENTAL effective=CPU " +
                "reason=no-egl-context,no-float-fbo,parity-not-verified",
            EditorPreviewLogic.backendFallbackLine(
                RenderBackend.GPU_EXPERIMENTAL,
                "no-egl-context,no-float-fbo,parity-not-verified",
            ),
        )
    }
}
