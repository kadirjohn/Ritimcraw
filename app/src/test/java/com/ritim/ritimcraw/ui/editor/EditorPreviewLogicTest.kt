package com.ritim.ritimcraw.ui.editor

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

    // ---- sync status verdict (gyro Phase 5: the status line's decision) ----

    @Test
    fun no_gyro_is_no_data_regardless_of_probe_shape() {
        // No sidecar .gcsv attached: the honest no-data state, even if a
        // stale probe result lingers — stabilization stays a no-op.
        assertEquals(
            GyroSyncStatus.NO_DATA,
            EditorPreviewLogic.syncStatus(
                gyroAttached = false, probeFailed = false, anchored = true,
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
                gyroAttached = true, probeFailed = true, anchored = false,
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
                gyroAttached = true, probeFailed = false, anchored = true,
            ),
        )
    }

    @Test
    fun attached_without_anchors_is_manual() {
        // Anchors absent: the trim slider is the user's offset — Manual.
        assertEquals(
            GyroSyncStatus.MANUAL,
            EditorPreviewLogic.syncStatus(
                gyroAttached = true, probeFailed = false, anchored = false,
            ),
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
}
