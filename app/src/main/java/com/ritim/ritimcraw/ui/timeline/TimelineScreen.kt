package com.ritim.ritimcraw.ui.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.data.audio.AudioStore
import com.ritim.ritimcraw.data.audio.AudioTrackOps
import com.ritim.ritimcraw.data.metadata.FieldStatus
import com.ritim.ritimcraw.data.metadata.MetadataStore
import com.ritim.ritimcraw.data.timeline.FrameRange
import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineEdit
import com.ritim.ritimcraw.data.timeline.TimelineHistory
import com.ritim.ritimcraw.data.timeline.TimelineIds
import com.ritim.ritimcraw.data.timeline.TimelineOps
import com.ritim.ritimcraw.data.timeline.TimelineOps.TrimEdge
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineStore
import com.ritim.ritimcraw.data.timeline.TrackKind
import com.ritim.ritimcraw.ui.theme.RitimTheme
import com.ritim.ritimcraw.ui.theme.Tnum
import com.ritim.ritimcraw.util.Format

/**
 * The timeline editing screen (Stage C UI, spec TL-03/04/05/07/16/17) —
 * the FIRST consumer of the timeline core model (data/timeline). Full
 * screen, the fixed neutral editor scheme (DESIGN.md: editors never
 * bias color judgment); the app shell hides the bottom nav while this
 * route is open.
 *
 * STATE HOLDER (documented honestly). The screen holds:
 *   - the committed [TimelineProject] in ONE mutableStateOf, replaced
 *     wholesale by every history apply/undo/redo — the model is
 *     immutable data classes, so a swap IS the update. This state is
 *     the single recomposition trigger for the rendered timeline.
 *   - the [TimelineHistory] in a plain remember { } — NOT
 *     rememberSaveable: the history is a session-scoped object whose
 *     own KDoc already fixes the honest post-restart state as "nothing
 *     to undo" (history is session memory; the PROJECT survives
 *     through [TimelineStore]). Rotation never recreates it anyway —
 *     the manifest carries configChanges, so the activity survives
 *     in-place.
 *   - canUndo/canRedo as two mutableStateOf booleans refreshed after
 *     every history mutation: they are plain properties on the history
 *     object, invisible to Compose — the refresh makes the top bar's
 *     undo/redo enabled state reactive with one honest write per edit
 *     (a revision counter would work; two booleans say what they are).
 *   - selection + playhead in rememberSaveable (a String id and a
 *     Double frame — both Bundle-safe) so process death restores them.
 *
 * PERSISTENCE (the honest choice): edits save ON BACK — the system
 * back gesture and the top bar's back button both persist through
 * [TimelineStore.save] before navigating. The store's save is atomic
 * and section-scoped (never clobbers the other project.json sections),
 * and every edit already went through the history, so the project the
 * store receives is the fully-applied one. The trade, documented: a
 * save-on-every-gesture would write project.json on every drop;
 * on-back trades a crash-window (edits since the last back) for zero
 * I/O on the gesture path.
 *
 * SCALE CHOICE (v1, documented): ONE FIXED px-per-frame
 * ([PixelsPerFrame]) — no zoom, no fit-to-width. All mapping goes
 * through [TimelineGeometry]; the content scrolls horizontally when
 * longer than the screen, ruler and lanes sharing one ScrollState so
 * the playhead lines up across rows.
 *
 * PLAYBACK BOUNDARY (documented): this screen has NO playback wiring
 * — playback is the clip editor's domain (the transport lives in
 * ui/editor). The playhead here is an EDIT POINT: scrubbing sets the
 * frame the Split action cuts at (TL-05) and snapping pulls it to
 * clip edges (TL-07). Nothing here starts a preview surface.
 *
 * ADD PATH (v1's honest insert): nothing else in the app seeds the
 * timeline section yet, so this screen owns the minimal insert — the
 * top bar's add action opens a picker of the app's imported sources
 * and appends the picked one to the video track at
 * [TimelineGeometry.appendStart], with a [TimelineIds.newId]-minted
 * id, through a recorded edit. The clip's duration is the source's
 * probed frameCount, or the documented placeholder when the stats
 * pass has not probed it yet.
 *
 * LINKED-AUDIO PAIR (AUD-02's add path): when the source's metadata
 * record says audio is KNOWN-present with a real sample rate, the
 * add creates a LINKED PAIR in ONE transaction ([TimelineEdit.AddClipPair])
 * — the video clip on the video track plus an audio clip (same
 * source, same ranges) on the audio track, the video's
 * linkedAudioId pointing at the audio clip's id. One undo step
 * unwinds the whole pair. The gate is HONEST by construction
 * (AUD-06): anything short of KNOWN-present with a positive rate —
 * no metadata record, UNKNOWN audio, KNOWN-absent, or a zero rate —
 * adds the video clip alone (the current behavior, never a guessed
 * audio clip).
 *
 * TEXT TRACKS: deliberately not rendered in v1 (the mission's carve-
 * out) — no text clip can exist through any current UI, so a TEXT
 * track row would always be an empty lane. The model renders it the
 * day text clips can be created.
 *
 * GESTURE RULE (the mission's "every gesture = ONE TimelineEdit
 * apply"): a drag never touches the history while the finger is down.
 * Each drag frame computes the edit the drop would commit and applies
 * it OUTSIDE the history (TimelineEdit.apply directly — pure, records
 * nothing) as the visual PREVIEW; on drag END the one edit goes
 * through TimelineHistory.apply — one gesture, one recorded
 * transaction, one undo step. A drag cancelled (finger lost, gesture
 * stolen) discards the preview and commits nothing. The preview is
 * exactly what the drop does, by construction: both run the same pure
 * functions on the same inputs, and the drag's base is the COMMITTED
 * project (previews never feed previews), so the commit lands where
 * the preview showed.
 *
 * POINTER DISPATCH (the two-gesture problem): a clip block carries
 * both a body-move drag and two edge-trim drags. The edge zones are
 * composed AFTER the body's pointerInput so they win the dispatch
 * (children before parents in Compose), and every handler takes the
 * clip ID, resolving the CURRENT committed clip per call — a
 * pointerInput(clip.id) closure survives clip moves (same id), and a
 * stale clip instance would drag from the wrong base.
 *
 * @param store the app's TimelineStore (the shared project.json's
 *   "timeline" section — the same file the shell's other stores use).
 * @param sources the app's imported sources (Library), for the add
 *   picker and the clip block labels.
 * @param metadata the app's MetadataStore — the ONLY honest source
 *   of audio presence/rate facts (AUD-06); the add path's pair gate
 *   reads it per source.
 * @param audio the app's AudioStore (the shared project.json's
 *   "audio" section) — the audio model's store; this screen's add
 *   path is its first consumer (later commits hang the AUD-03
 *   settings sheet off it).
 * @param onBack closes the screen (the screen persists first).
 */
@Composable
fun TimelineScreen(
    store: TimelineStore,
    sources: List<MediaSource>,
    metadata: MetadataStore,
    audio: AudioStore,
    onBack: () -> Unit,
) {
    RitimTheme(editor = true) {
        // ---- state holder (see KDoc) ----
        val history = remember(store) { TimelineHistory(store.load()) }
        var project by remember(store) { mutableStateOf(history.project) }
        var canUndo by remember { mutableStateOf(history.canUndo) }
        var canRedo by remember { mutableStateOf(history.canRedo) }
        var selectedClipId by rememberSaveable { mutableStateOf<String?>(null) }
        var playheadFrame by rememberSaveable { mutableStateOf(0.0) }
        var showAddDialog by rememberSaveable { mutableStateOf(false) }
        // The audio settings sheet (AUD-02/AUD-03): true while open.
        // NOT saveable — a process death mid-sheet reopens honestly
        // with no sheet (every action already saved; nothing is lost;
        // the saveable selection makes one tap reopen it).
        var showAudioSheet by remember { mutableStateOf(false) }
        // The CURRENT audio state, reloaded from the store after every
        // audio write so the sheet always renders the record on disk
        // (the sheet's honesty rule). One state write per action.
        var audioProject by remember { mutableStateOf(audio.load()) }

        fun applyEdit(edit: TimelineEdit) {
            project = history.apply(edit)
            canUndo = history.canUndo
            canRedo = history.canRedo
        }

        fun undo() {
            project = history.undo()
            canUndo = history.canUndo
            canRedo = history.canRedo
        }

        fun redo() {
            project = history.redo()
            canUndo = history.canUndo
            canRedo = history.canRedo
        }

        val saveAndClose = {
            store.save(project)
            onBack()
        }
        BackHandler { saveAndClose() }

        fun addClip(source: MediaSource) {
            val trackId = project.tracks
                .firstOrNull { it.kind == TrackKind.VIDEO }?.id ?: return
            val start = TimelineGeometry.appendStart(project, trackId)
            val duration = TimelineGeometry.addDuration(source.frameCount)
            val clip = TimelineClip(
                id = TimelineIds.newId(),
                trackId = trackId,
                sourceId = source.id,
                sourceRange = FrameRange(0.0, duration),
                timelineRange = FrameRange(start, start + duration),
            )
            // The honest audio gate (AUD-06): ONLY a KNOWN-present
            // audio field with a real sample rate mints the pair —
            // never a guess. Everything else adds the video clip
            // alone, exactly as before.
            val audioTrackId = project.tracks
                .firstOrNull { it.kind == TrackKind.AUDIO }?.id
            val audioField = metadata.get(source.id)?.audio
            val audioSummary = audioField?.value
            val hasAudio = audioTrackId != null &&
                audioField?.status == FieldStatus.KNOWN &&
                audioSummary != null &&
                audioSummary.present &&
                audioSummary.sampleRateHz > 0
            if (hasAudio) {
                // The pair (AUD-02's add path): same source, same
                // ranges, fresh id — the video's link is set BY the
                // transaction, one undo step.
                val audioClip = TimelineClip(
                    id = TimelineIds.newId(),
                    trackId = audioTrackId,
                    sourceId = source.id,
                    sourceRange = clip.sourceRange,
                    timelineRange = clip.timelineRange,
                )
                applyEdit(TimelineEdit.AddClipPair(video = clip, audio = audioClip))
            } else {
                applyEdit(TimelineEdit.AddClip(clip))
            }
            selectedClipId = clip.id
        }

        // ---- audio actions (AUD-02/AUD-03, the sheet's appliers) ----
        // Every action is the same honest shape: load the CURRENT
        // record, apply the pure op, save, reload into state — so the
        // sheet always renders what is on disk. These writes are NOT
        // recorded as timeline undo steps (the v1 rule the sheet's
        // KDoc documents): they are explicit state-machine transitions
        // and settings writes into the AudioStore, not timeline edits.
        fun applyAudio(action: (AudioProject) -> AudioProject) {
            val next = action(audio.load())
            audio.save(next)
            audioProject = next
        }

        val density = LocalDensity.current
        val pxPerFrame = with(density) { PixelsPerFrame.toPx() }

        // ---- the drag gesture layer (see KDoc, GESTURE RULE) ----
        // The in-flight gesture's bookkeeping: a plain remembered
        // object mutated in place — it never recomposes anything
        // itself; the PREVIEW state below drives recomposition. The
        // token pins one gesture to one target (clip, or clip+edge):
        // a stray event from a cancelled gesture cannot pollute the
        // active one, and gestureEnd/Cancel check the token before
        // committing.
        val drag = remember { DragGestureState() }
        // The preview: the project as the in-flight gesture would
        // leave it, or null when no gesture is running. Everything
        // renders from displayProject.
        var dragPreview by remember { mutableStateOf<TimelineProject?>(null) }
        // The one edit the drop will commit (computed on the last
        // drag frame; the drop commits it).
        var pendingEdit by remember { mutableStateOf<TimelineEdit?>(null) }

        fun gestureStart(token: String, candidates: List<Double>) {
            if (drag.token != null) return // one gesture at a time
            drag.begin(token, candidates)
        }

        fun moveDrag(clipId: String, dxPx: Float) {
            if (drag.token != "m:$clipId") return
            drag.accumulatedPx += dxPx
            val clip = project.clip(clipId) ?: return
            val delta = TimelineGeometry.dragDeltaFrames(drag.accumulatedPx, pxPerFrame)
            val start = TimelineGeometry.moveDragStart(clip, delta, drag.candidates, SnapThresholdFrames)
            val edit = TimelineGeometry.moveEdit(clip, start)
            pendingEdit = edit
            dragPreview = edit?.apply(project) ?: project
        }

        fun trimDrag(clipId: String, edge: TrimEdge, dxPx: Float) {
            if (drag.token != "t:$clipId:${edge.name}") return
            drag.accumulatedPx += dxPx
            val clip = project.clip(clipId) ?: return
            val delta = TimelineGeometry.dragDeltaFrames(drag.accumulatedPx, pxPerFrame)
            val position = TimelineGeometry.trimDragPosition(clip, edge, delta)
            val edit = TimelineGeometry.trimEdit(clip, edge, position)
            pendingEdit = edit
            dragPreview = edit?.apply(project) ?: project
        }

        /** Drag end: the ONE history apply the gesture costs. */
        fun gestureEnd(token: String) {
            if (drag.token != token) return
            pendingEdit?.let { applyEdit(it) }
            pendingEdit = null
            dragPreview = null
            drag.reset()
        }

        /** Drag cancel: discard the preview, commit nothing. */
        fun gestureCancel(token: String) {
            if (drag.token != token) return
            pendingEdit = null
            dragPreview = null
            drag.reset()
        }

        // Everything renders the DISPLAY project — the preview during a
        // gesture, the committed project otherwise (see GESTURE RULE).
        val displayProject = dragPreview ?: project
        val length = TimelineOps.resolveLength(displayProject)
        val selected = displayProject.clip(selectedClipId ?: "")

        // The audio sheet's join keys, derived from the SELECTION: an
        // audio clip addresses its own settings row; a video clip with a
        // linked audio addresses the pair's audio clip (the same id the
        // video's linkedAudioId points at). Both halves share the
        // sourceId (the pair-add's construction), so the AUD-02 link
        // record's key is the same either way. A video clip with NO
        // linked audio — or a DANGLING link (the audio half was
        // removed individually) — offers nothing audio-shaped: null,
        // no button, honestly.
        val selectedAudioClipId: String? = when {
            selected == null -> null
            selected.trackId == audioTrackIdOf(displayProject) -> selected.id
            else -> selected.linkedAudioId?.let { linkedId ->
                if (displayProject.clip(linkedId) != null) linkedId else null
            }
        }
        val selectedSourceId = selected?.sourceId
        val sourceNames = remember(sources) {
            sources.associate { it.id to it.displayOrId }
        }
        val scroll = rememberScrollState()

        // Scrub (TL-07): the tapped/dragged ruler position becomes the
        // playhead frame, snapped to the project's edit points (clip
        // edges + the axis ends) within the threshold, clamped to the
        // resolved length.
        val scrub: (Float) -> Unit = { xPx ->
            val raw = TimelineGeometry.pxToFrame(xPx, pxPerFrame)
            val snapped = TimelineOps.snap(
                raw,
                TimelineOps.snapCandidates(project, listOf(0.0, length)),
                SnapThresholdFrames,
            )
            playheadFrame = snapped.coerceIn(0.0, length)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = saveAndClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.timeline_back),
                        )
                    }
                    Text(
                        text = stringResource(R.string.timeline_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // The playhead readout: tnum so the digits never
                    // reflow while scrubbing (DESIGN.md Typography).
                    Text(
                        text = stringResource(
                            R.string.timeline_playhead_frame,
                            playheadFrame.toInt(),
                        ),
                        style = MaterialTheme.typography.labelMedium.merge(Tnum),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.timeline_add),
                        )
                    }
                    IconButton(onClick = ::undo, enabled = canUndo) {
                        Icon(
                            painterResource(R.drawable.ic_undo),
                            contentDescription = stringResource(R.string.timeline_undo),
                        )
                    }
                    IconButton(onClick = ::redo, enabled = canRedo) {
                        Icon(
                            painterResource(R.drawable.ic_redo),
                            contentDescription = stringResource(R.string.timeline_redo),
                        )
                    }
                }
                if (displayProject.clips.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.timeline_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        // The pinned track headers, outside the scroll —
                        // the standard editing-timeline layout: headers
                        // stay put while the lanes scroll under them.
                        Column(
                            modifier = Modifier
                                .width(TrackHeaderWidth)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(LaneSpacing),
                        ) {
                            Spacer(Modifier.height(RulerHeight))
                            timelineTracks(displayProject).forEach { track ->
                                TrackHeader(kind = track.kind)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(scroll),
                        ) {
                            val contentWidthDp = with(density) {
                                (TimelineGeometry.frameToPx(length, pxPerFrame) +
                                    ContentTailPx.toPx()).toDp()
                            }
                            Box(
                                modifier = Modifier
                                    .width(contentWidthDp)
                                    .height(IntrinsicSize.Min),
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(LaneSpacing),
                                ) {
                                    TimelineRuler(
                                        lengthFrames = length,
                                        fps = displayProject.output.fps,
                                        pxPerFrame = pxPerFrame,
                                        onScrub = scrub,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(RulerHeight),
                                    )
                                    timelineTracks(displayProject).forEach { track ->
                                        TimelineTrackLane(
                                            clips = TimelineGeometry.trackClips(
                                                displayProject,
                                                track.id,
                                            ),
                                            pxPerFrame = pxPerFrame,
                                            selectedClipId = selectedClipId,
                                            sourceNames = sourceNames,
                                            onSelect = { id ->
                                                selectedClipId =
                                                    if (selectedClipId == id) null else id
                                            },
                                            onMoveStart = { clipId ->
                                                gestureStart(
                                                    "m:$clipId",
                                                    TimelineOps.snapCandidates(project),
                                                )
                                            },
                                            onMoveDrag = ::moveDrag,
                                            onTrimStart = { clipId, edge ->
                                                gestureStart("t:$clipId:${edge.name}", emptyList())
                                            },
                                            onTrimDrag = ::trimDrag,
                                            onGestureEnd = ::gestureEnd,
                                            onGestureCancel = ::gestureCancel,
                                        )
                                    }
                                }
                                // The playhead line, drawn over the rows
                                // with NO gesture handlers of its own —
                                // scrubbing lives on the ruler, so taps
                                // and drags on clips pass straight through
                                // this overlay to the blocks below it.
                                Box(
                                    modifier = Modifier
                                        .offset(
                                            x = with(density) {
                                                TimelineGeometry
                                                    .frameToPx(playheadFrame, pxPerFrame)
                                                    .toDp()
                                            },
                                        )
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                }
                // The selected-clip action row (TL-03/04/05) — the
                // mission's "select clip → operation row": Split cuts
                // at the playhead, Duplicate and Remove act on the
                // selection. Trim and Move are direct gestures on the
                // block. An audio clip (or a video clip with a linked
                // audio) ALSO offers Audio — the settings sheet's
                // entry (AUD-02/AUD-03).
                if (selected != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The audio settings entry: present ONLY for a
                        // selection with an audio half (an audio clip,
                        // or a video clip whose pair carries audio) —
                        // a video-only clip honestly offers nothing
                        // audio-shaped.
                        if (selectedAudioClipId != null && selectedSourceId != null) {
                            OperationButton(
                                label = stringResource(R.string.timeline_audio),
                                icon = {
                                    Icon(
                                        painterResource(R.drawable.ic_track_audio),
                                        contentDescription = null,
                                    )
                                },
                                enabled = true,
                                modifier = Modifier.weight(1f),
                                onClick = { showAudioSheet = true },
                            )
                        }
                        OperationButton(
                            label = stringResource(R.string.timeline_split),
                            icon = {
                                Icon(
                                    painterResource(R.drawable.ic_split),
                                    contentDescription = null,
                                )
                            },
                            enabled = TimelineGeometry.canSplit(selected, playheadFrame),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                // Mint BOTH ids up front (the mission
                                // rule: the transaction layer needs them
                                // before apply; the linked audio's right
                                // piece is addressed by id in the undo).
                                val rightId = TimelineIds.newId()
                                val audioRightId =
                                    selected.linkedAudioId?.let { TimelineIds.newId() }
                                applyEdit(
                                    TimelineEdit.SplitClip(
                                        clipId = selected.id,
                                        frame = playheadFrame,
                                        rightId = rightId,
                                        audioRightId = audioRightId,
                                    ),
                                )
                                // SPLIT SETTINGS INHERITANCE (AUD-03):
                                // the audio right piece inherits the
                                // left piece's settings row — the same
                                // "mevcut ayarlar korunur" rule at split
                                // time. A missing left row means unity
                                // — afterSplit is a no-op, honestly.
                                // NOT an undo step (the audio write
                                // rule); undoing the split removes the
                                // right piece, and its row rides the
                                // removed id harmlessly.
                                //
                                // WHICH pieces exist is the SPLIT's
                                // decision, checked against the
                                // COMMITTED project after the apply —
                                // never assumed from the minted ids: a
                                // VIDEO split maps the edit point onto
                                // the linked audio and may leave it
                                // UNSPLIT (TimelineOps.split's honest
                                // rule), in which case NO audio right
                                // piece exists and minting its row
                                // would be dishonest state; a directly
                                // split AUDIO clip is its own left
                                // piece (the right piece's id is
                                // [rightId]).
                                val splitAudioLeft = selected.linkedAudioId
                                if (splitAudioLeft != null && audioRightId != null) {
                                    // The linked pair split: only if the
                                    // audio right piece REALLY exists.
                                    if (project.clip(audioRightId) != null) {
                                        applyAudio { current ->
                                            AudioTrackOps.afterSplit(current, splitAudioLeft, audioRightId)
                                        }
                                    }
                                } else if (splitAudioLeft == null && audioRightId == null &&
                                    selected.trackId == audioTrackIdOf(project)
                                ) {
                                    // A directly-split AUDIO clip (a
                                    // video-only clip split mints no
                                    // row — its id is not an audio
                                    // clip's): the selected clip IS
                                    // the audio, its left piece keeps
                                    // the id.
                                    applyAudio { current ->
                                        AudioTrackOps.afterSplit(current, selected.id, rightId)
                                    }
                                }
                            },
                        )
                        OperationButton(
                            label = stringResource(R.string.timeline_duplicate),
                            icon = {
                                Icon(
                                    painterResource(R.drawable.ic_duplicate),
                                    contentDescription = null,
                                )
                            },
                            enabled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                applyEdit(
                                    TimelineEdit.DuplicateClip(
                                        selected.id,
                                        TimelineIds.newId(),
                                    ),
                                )
                            },
                        )
                        OperationButton(
                            label = stringResource(R.string.timeline_remove),
                            icon = {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            },
                            enabled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                applyEdit(TimelineEdit.RemoveClip(selected.id))
                                selectedClipId = null
                            },
                        )
                    }
                }
            }
        }
        if (showAddDialog) {
            TimelineAddDialog(
                sources = sources,
                onDismiss = { showAddDialog = false },
                onAdd = { source ->
                    showAddDialog = false
                    addClip(source)
                },
            )
        }
        // The audio settings sheet (AUD-02/AUD-03): opens only for a
        // selection with an audio half. The metadata record's KNOWN
        // sample rate is the sheet's rate gate (AUD-06) — the honest
        // source of the rate fact, the same gate the pair-add reads.
        if (showAudioSheet && selectedAudioClipId != null && selectedSourceId != null) {
            val audioField = metadata.get(selectedSourceId)?.audio
            val audioSummary = audioField?.value
            val knownRate = if (
                audioField?.status == FieldStatus.KNOWN &&
                audioSummary != null &&
                audioSummary.present &&
                audioSummary.sampleRateHz > 0
            ) {
                audioSummary.sampleRateHz
            } else {
                0 // UNKNOWN — the sheet disables its controls honestly.
            }
            AudioSettingsSheet(
                clipId = selectedAudioClipId,
                sourceId = selectedSourceId,
                sampleRateHz = knownRate,
                audioProject = audioProject,
                onSettings = { gainDb, fadeInMs, fadeOutMs ->
                    // The row's rate: an existing row keeps its own
                    // (pass 0 = "keep", the setSettings contract); a
                    // NEW row needs the KNOWN rate — the sheet's
                    // enabled gate already guarantees it is > 0 here.
                    val existingRate = audioProject.clip(selectedAudioClipId)?.sampleRateHz ?: 0
                    applyAudio { current ->
                        AudioTrackOps.setSettings(
                            project = current,
                            clipId = selectedAudioClipId,
                            sourceId = selectedSourceId,
                            sampleRateHz = if (existingRate > 0) 0 else knownRate,
                            gainDb = gainDb,
                            fadeInMs = fadeInMs,
                            fadeOutMs = fadeOutMs,
                        )
                    }
                },
                onDetach = {
                    applyAudio { current ->
                        AudioTrackOps.detach(current, selectedSourceId)
                    }
                },
                onRestore = {
                    applyAudio { current ->
                        AudioTrackOps.restore(current, selectedSourceId)
                    }
                },
                onRemove = {
                    applyAudio { current ->
                        AudioTrackOps.remove(current, selectedSourceId)
                    }
                },
                onDismiss = { showAudioSheet = false },
            )
        }
    }
}

/** v1's fixed scale (see TimelineScreen KDoc, SCALE CHOICE). */
private val PixelsPerFrame = 4.dp

/** Snap pull distance in frames (TL-07) — one screen constant. */
private const val SnapThresholdFrames = 2.0

/** The ruler band's height. */
private val RulerHeight = 32.dp

/** One track lane's height. */
private val LaneHeight = 56.dp

/** One clip block's height. */
private val ClipHeight = 48.dp

/**
 * The trim edge strips' width (TL-04): the invisible grab area at a
 * block's left/right edge. Wide enough to grab with a finger, narrow
 * enough that the block's body (the move drag) stays the easy default.
 */
private val TrimZoneWidth = 16.dp

/** The pinned track header column's width. */
private val TrackHeaderWidth = 88.dp

/** Vertical gap between the ruler and the lanes (and between lanes). */
private val LaneSpacing = 4.dp

/** Empty scroll tail past the timeline end, so the last clip breathes. */
private val ContentTailPx = 64.dp

/**
 * The ruler's minimum on-screen major spacing: labels are full
 * timecodes (labelSmall, tnum), so majors stay at least this far apart
 * or the labels collide.
 */
private const val MajorLabelSpacingPx = 96f

/**
 * The VIDEO and AUDIO tracks in stacking order — v1's rendered rows
 * (TEXT is the documented carve-out, see TimelineScreen KDoc).
 */
private fun timelineTracks(project: TimelineProject) =
    project.tracks
        .filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO }
        .sortedBy { it.index }

/** The audio track's id, or null when the project has no audio track. */
private fun audioTrackIdOf(project: TimelineProject): String? =
    project.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.id

// ---------------------------------------------------------------------------
// Ruler (TL-17 + TL-07 scrub)
// ---------------------------------------------------------------------------

/**
 * The frame ruler: major ticks with TIMECODE labels + minor ticks,
 * spanning 0..resolveLength (TL-17) — the model's own length rule,
 * never a UI guess. One Canvas paints every tick and label (a long
 * timeline has hundreds of ticks — one draw pass, not hundreds of
 * layout nodes). Tapping or dragging the ruler scrubs the playhead;
 * the scrub consumes the gesture, so dragging the ruler never
 * scrolls the timeline.
 */
@Composable
private fun TimelineRuler(
    lengthFrames: Double,
    fps: Int,
    pxPerFrame: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val majorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minorColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.merge(Tnum)
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier
            .pointerInput(lengthFrames, pxPerFrame) {
                detectTapGestures { offset -> onScrub(offset.x) }
            }
            .pointerInput(lengthFrames, pxPerFrame) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onScrub(change.position.x)
                }
            },
    ) {
        val step = TimelineGeometry.majorStepFor(pxPerFrame, MajorLabelSpacingPx)
        val ticks = TimelineGeometry.rulerTicks(lengthFrames, step)
        val tickBottom = size.height * 0.55f
        ticks.forEach { tick ->
            val x = TimelineGeometry.frameToPx(tick.frame, pxPerFrame)
            drawLine(
                color = if (tick.major) majorColor else minorColor,
                start = Offset(x, if (tick.major) 0f else size.height * 0.25f),
                end = Offset(x, tickBottom),
                strokeWidth = if (tick.major) 2f else 1f,
                cap = StrokeCap.Round,
            )
        }
        ticks.filter { it.major }.forEach { tick ->
            // tnum timecode labels (DESIGN.md Typography): fixed-width
            // fields from the house Format, so digits never reflow.
            val us = frameToUs(tick.frame, fps)
            val label = textMeasurer.measure(Format.formatTimecode(us, fps), labelStyle)
            val x = TimelineGeometry.frameToPx(tick.frame, pxPerFrame)
            drawText(
                label,
                color = labelColor,
                topLeft = Offset(x - label.size.width / 2f, tickBottom),
            )
        }
    }
}

/** Frame number → microseconds on the project grid (the ruler's labels). */
private fun frameToUs(frame: Double, fps: Int): Long {
    val safeFps = if (fps <= 0) Format.DEFAULT_FPS else fps
    return (frame * 1_000_000.0 / safeFps).toLong()
}

// ---------------------------------------------------------------------------
// Track header + lane + clip blocks (TL-03)
// ---------------------------------------------------------------------------

/** The pinned track header: kind icon + label, fixed size. */
@Composable
private fun TrackHeader(kind: TrackKind) {
    val (iconRes, labelRes) = when (kind) {
        TrackKind.VIDEO -> R.drawable.ic_track_video to R.string.timeline_track_video
        TrackKind.AUDIO -> R.drawable.ic_track_audio to R.string.timeline_track_audio
        TrackKind.TEXT -> R.drawable.ic_track_video to R.string.timeline_track_video
    }
    Row(
        modifier = Modifier
            .width(TrackHeaderWidth)
            .height(LaneHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(labelRes),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One track's scrolling lane: the clip blocks positioned by
 * timelineRange.start, sized by the range duration, at the fixed
 * scale. The lane itself has no gesture handlers — drags on empty lane
 * space fall through to the horizontal scroll.
 */
@Composable
private fun TimelineTrackLane(
    clips: List<TimelineClip>,
    pxPerFrame: Float,
    selectedClipId: String?,
    sourceNames: Map<String, String>,
    onSelect: (String) -> Unit,
    onMoveStart: (String) -> Unit,
    onMoveDrag: (String, Float) -> Unit,
    onTrimStart: (String, TrimEdge) -> Unit,
    onTrimDrag: (String, TrimEdge, Float) -> Unit,
    onGestureEnd: (String) -> Unit,
    onGestureCancel: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LaneHeight),
    ) {
        clips.forEach { clip ->
            ClipBlock(
                clip = clip,
                pxPerFrame = pxPerFrame,
                selected = clip.id == selectedClipId,
                // Known source → its display name; a source since
                // removed from the Library → its raw id (honest);
                // no source (a text clip one day) → the clip's id.
                label = clip.sourceId
                    ?.let { sourceNames[it] ?: it }
                    ?: clip.id,
                onSelect = onSelect,
                onMoveStart = onMoveStart,
                onMoveDrag = onMoveDrag,
                onTrimStart = onTrimStart,
                onTrimDrag = onTrimDrag,
                onGestureEnd = onGestureEnd,
                onGestureCancel = onGestureCancel,
            )
        }
    }
}

/**
 * One clip block: positioned by timelineRange.start, sized by the
 * range duration, the source's display name truncating inside. Tap
 * toggles selection; the selected block gets the visible selected
 * state (primaryContainer fill + primary border). Gestures (see
 * TimelineScreen KDoc, GESTURE RULE + POINTER DISPATCH): dragging the
 * body moves the clip (TL-03); the 16dp edge strips trim that edge
 * (TL-04) — they are composed AFTER the body's handlers so they win
 * the pointer dispatch, and every handler passes the clip ID, which
 * the screen resolves against the committed project per call.
 */
@Composable
private fun ClipBlock(
    clip: TimelineClip,
    pxPerFrame: Float,
    selected: Boolean,
    label: String,
    onSelect: (String) -> Unit,
    onMoveStart: (String) -> Unit,
    onMoveDrag: (String, Float) -> Unit,
    onTrimStart: (String, TrimEdge) -> Unit,
    onTrimDrag: (String, TrimEdge, Float) -> Unit,
    onGestureEnd: (String) -> Unit,
    onGestureCancel: (String) -> Unit,
) {
    val density = LocalDensity.current
    val startDp = with(density) {
        TimelineGeometry.frameToPx(clip.timelineRange.start, pxPerFrame).toDp()
    }
    val widthDp = with(density) {
        TimelineGeometry.frameToPx(clip.timelineRange.duration, pxPerFrame).toDp()
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .offset(x = startDp, y = (LaneHeight - ClipHeight) / 2)
            .size(width = widthDp, height = ClipHeight)
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .pointerInput(clip.id) {
                detectTapGestures { onSelect(clip.id) }
            }
            .pointerInput(clip.id) {
                detectDragGestures(
                    onDragStart = { onMoveStart(clip.id) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onMoveDrag(clip.id, dragAmount.x)
                    },
                    onDragEnd = { onGestureEnd("m:${clip.id}") },
                    onDragCancel = { onGestureCancel("m:${clip.id}") },
                )
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 6.dp),
        )
        // The trim edge strips (TL-04): transparent grab areas at the
        // block's left and right, composed AFTER the body's handlers so
        // they win the pointer dispatch — an edge drag never becomes a
        // move.
        TrimZone(
            edge = TrimEdge.START,
            modifier = Modifier.align(Alignment.CenterStart),
            onTrimStart = { onTrimStart(clip.id, TrimEdge.START) },
            onTrimDrag = { dxPx -> onTrimDrag(clip.id, TrimEdge.START, dxPx) },
            onGestureEnd = { onGestureEnd("t:${clip.id}:${TrimEdge.START.name}") },
            onGestureCancel = { onGestureCancel("t:${clip.id}:${TrimEdge.START.name}") },
        )
        TrimZone(
            edge = TrimEdge.END,
            modifier = Modifier.align(Alignment.CenterEnd),
            onTrimStart = { onTrimStart(clip.id, TrimEdge.END) },
            onTrimDrag = { dxPx -> onTrimDrag(clip.id, TrimEdge.END, dxPx) },
            onGestureEnd = { onGestureEnd("t:${clip.id}:${TrimEdge.END.name}") },
            onGestureCancel = { onGestureCancel("t:${clip.id}:${TrimEdge.END.name}") },
        )
    }
}

/**
 * One trim edge strip (see ClipBlock): a transparent drag area pinned
 * to the block's start/end edge. [TrimZoneWidth] wide — a touch
 * target within the 48dp block height.
 */
@Composable
private fun TrimZone(
    edge: TrimEdge,
    modifier: Modifier = Modifier,
    onTrimStart: () -> Unit,
    onTrimDrag: (Float) -> Unit,
    onGestureEnd: () -> Unit,
    onGestureCancel: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = TrimZoneWidth, height = ClipHeight)
            .pointerInput(edge) {
                detectDragGestures(
                    onDragStart = { onTrimStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onTrimDrag(dragAmount.x)
                    },
                    onDragEnd = { onGestureEnd() },
                    onDragCancel = { onGestureCancel() },
                )
            },
    )
}

// ---------------------------------------------------------------------------
// Operation row (TL-03/04/05)
// ---------------------------------------------------------------------------

/**
 * One operation button: a 48dp IconButton with the action's icon and
 * label stacked — the row's enabled state comes from the pure rules
 * (TimelineGeometry), so a refused action (playhead outside the clip)
 * never looks tappable.
 */
@Composable
private fun OperationButton(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Add dialog (v1's honest insert, see TimelineScreen KDoc)
// ---------------------------------------------------------------------------

/**
 * The add picker: the app's readable imported sources, one row each;
 * picking one appends it to the video track. Empty list = the honest
 * empty text pointing at the Library.
 */
@Composable
private fun TimelineAddDialog(
    sources: List<MediaSource>,
    onDismiss: () -> Unit,
    onAdd: (MediaSource) -> Unit,
) {
    val addable = sources.filter { it.access == Access.OK }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_add_title)) },
        text = {
            if (addable.isEmpty()) {
                Text(
                    stringResource(R.string.timeline_add_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    addable.forEach { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onAdd(source) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = source.displayOrId,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.timeline_add_cancel))
            }
        },
    )
}

/**
 * The in-flight gesture's bookkeeping (see TimelineScreen KDoc,
 * GESTURE RULE): which gesture owns the pointer (the token — one
 * gesture at a time), the accumulated drag px so far, and the snap
 * candidates frozen at gesture start (the PRE-DRAG committed
 * project's edit points, so the snap targets stay stable through the
 * gesture). A plain class, NOT Compose state: it is mutated inside
 * pointer handlers where recomposition must not read it mid-gesture —
 * the preview [TimelineProject] state drives all recomposition.
 */
private class DragGestureState {
    /** "m:<clipId>" or "t:<clipId>:<EDGE>" — null when no gesture runs. */
    var token: String? = null

    /** Total drag px accumulated since gesture start. */
    var accumulatedPx: Float = 0f

    /** Snap candidates frozen at gesture start (TL-07). */
    var candidates: List<Double> = emptyList()

    fun begin(token: String, candidates: List<Double>) {
        this.token = token
        this.candidates = candidates
        accumulatedPx = 0f
    }

    fun reset() {
        token = null
        accumulatedPx = 0f
        candidates = emptyList()
    }
}
