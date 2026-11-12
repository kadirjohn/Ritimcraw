package com.ritim.ritimcraw.data.timeline

/**
 * Pure timeline edit operations (TL-03..TL-07, TL-17) — the functional
 * core TimelineEdit's transaction objects call into. All functions are
 * pure: they take a TimelineProject (or a clip) and return the edited
 * copy; nothing mutates in place. The transaction layer (TimelineEdit)
 * wraps these with inverse generation for undo; the UI mission calls the
 * transactions, not these directly, except for read-only queries (snap
 * candidates, resolved length) which are safe to call anywhere.
 *
 * SOURCE-LINKED AUDIO (TL-05). A video clip may carry linkedAudioId — the
 * id of its attached audio clip (same source, split from the import).
 * split splits the linked audio at the SAME edit point (frame-precise on
 * the shared timebase), and trim of a video clip trims its linked audio
 * by the same delta so A/V stay in sync. The link is by id, not position,
 * so it survives every edit (the ID STABILITY rule).
 */

object TimelineOps {

    // ---------------------------------------------------------------
    // TL-03: add, move, duplicate, delete
    // ---------------------------------------------------------------

    /**
     * Adds a clip to the project. The clip must already carry its stable
     * id (TimelineIds.newId at the UI's insert point) — the model never
     * mints or regenerates ids.
     */
    fun add(project: TimelineProject, clip: TimelineClip): TimelineProject =
        project.copy(clips = project.clips + clip)

    /**
     * Moves a clip by delta frames on the timeline (same track) or to a
     * different track (trackId change). Timeline placement moves; the
     * source range does NOT (moving a clip on the timeline never changes
     * which part of the source it uses). Id preserved.
     */
    fun move(
        project: TimelineProject,
        clipId: String,
        timelineStart: Double,
        trackId: String? = null,
    ): TimelineProject {
        val clip = project.clip(clipId) ?: return project
        val moved = clip.copy(
            trackId = trackId ?: clip.trackId,
            timelineRange = FrameRange(timelineStart, timelineStart + clip.sourceRange.duration),
        )
        return project.copy(clips = project.clips.map { if (it.id == clipId) moved else it })
    }

    /**
     * Duplicates a clip with a FRESH id (a duplicate is a new object; the
     * original keeps its id — two clips must never share an id). The copy
     * starts at the SAME timeline position (an in-place twin the caller
     * then moves — the model does not guess a drop offset). Linked audio
     * is NOT auto-duplicated (the caller decides whether the duplicate
     * carries a link; the model does not guess a second audio clip into
     * existence), so the copy's linkedAudioId is null.
     */
    fun duplicate(project: TimelineProject, clipId: String, newId: String): TimelineProject {
        val clip = project.clip(clipId) ?: return project
        val copy = clip.copy(id = newId, linkedAudioId = null)
        return project.copy(clips = project.clips + copy)
    }

    /**
     * Deletes a clip by id. Missing id is a no-op (the delete-then-refresh
     * race rule from ClipDeletion). Ids of other clips are untouched.
     */
    fun delete(project: TimelineProject, clipId: String): TimelineProject =
        project.copy(clips = project.clips.filterNot { it.id == clipId })

    /**
     * Adds a track. Track ids are stable strings minted by the caller.
     */
    fun addTrack(project: TimelineProject, track: TimelineTrack): TimelineProject {
        if (project.tracks.any { it.id == track.id }) return project
        return project.copy(tracks = project.tracks + track)
    }

    /**
     * Deletes a track AND its clips (a track delete empties the lane).
     * Clips on other tracks are untouched.
     */
    fun deleteTrack(project: TimelineProject, trackId: String): TimelineProject =
        project.copy(
            tracks = project.tracks.filterNot { it.id == trackId },
            clips = project.clips.filterNot { it.trackId == trackId },
        )

    // ---------------------------------------------------------------
    // TL-04: trim from start and end — the same time contract
    // ---------------------------------------------------------------

    /**
     * TRIM, the TL-04 contract in one function: both edges use the same
     * time contract, so ONE function trims either edge on the SAME
     * numbers. [edge] picks the edge; [newPosition] is the edge's new
     * position on the project frame grid — for START it is the new first
     * frame USED in the source (and the new timeline start), for END it
     * is the new exclusive last frame.
     *
     * The invariant kept: sourceRange and timelineRange keep EQUAL
     * duration, and the untouched edge stays fixed. Trimming start by d
     * moves BOTH sourceRange.start and timelineRange.start by +d (the
     * source skips its first d frames; the timeline slot shrinks by d).
     * Trimming end by d moves BOTH sourceRange.end and timelineRange.end
     * by -d. No unit conversion anywhere — the timebase contract.
     *
     * The linked audio clip (linkedAudioId) is trimmed by the SAME delta
     * at the same edge so A/V stay in sync (TL-05's link rule).
     *
     * Returns null when the clip is missing, the trim would empty or
     * invert the clip, or the new position is outside the clip's current
     * range (trims only shrink; extending is a different operation).
     */
    fun trim(
        project: TimelineProject,
        clipId: String,
        edge: TrimEdge,
        newPosition: Double,
    ): TimelineProject? {
        val clip = project.clip(clipId) ?: return null
        val range = clip.sourceRange
        // Both edges share ONE validity rule (the TL-04 contract, made
        // symmetric): the new edge position must be STRICTLY inside the
        // current range — at either edge the clip would be empty, outside
        // it the trim would extend or invert. Trims only shrink.
        if (newPosition <= range.start || newPosition >= range.end) return null
        val trimmed: TimelineClip = when (edge) {
            TrimEdge.START -> clip.copy(
                sourceRange = FrameRange(newPosition, range.end),
                timelineRange = FrameRange(clip.timelineRange.start + (newPosition - range.start), clip.timelineRange.end),
            )
            TrimEdge.END -> clip.copy(
                sourceRange = FrameRange(range.start, newPosition),
                timelineRange = FrameRange(clip.timelineRange.start, clip.timelineRange.end - (range.end - newPosition)),
            )
        }
        var clips = project.clips.map { if (it.id == clipId) trimmed else it }
        // Linked audio follows the same edit (TL-05): same edge, same
        // delta, so the attached audio stays in sync with the video.
        clip.linkedAudioId?.let { audioId ->
            val audio = clips.firstOrNull { it.id == audioId }
            if (audio != null) {
                val audioTrimmed = trimClipOnly(audio, edge, deltaFor(edge, range, newPosition))
                clips = clips.map { if (it.id == audio.id) audioTrimmed else it }
            }
        }
        return project.copy(clips = clips)
    }

    /** Which edge a trim acts on. */
    enum class TrimEdge { START, END }

    /**
     * The UNDO path for trim: restores an edge to its pre-trim position,
     * bypassing the shrink-only guard (an undo legitimately EXTENDS the
     * clip back). Same dual-edge contract as [trim] — both ranges move
     * by the same delta — and the linked audio follows the same way.
     * Internal: only TimelineEdit.TrimClip's inverse builds one.
     */
    internal fun trimRestore(
        project: TimelineProject,
        clipId: String,
        edge: TrimEdge,
        oldPosition: Double,
    ): TimelineProject? {
        val clip = project.clip(clipId) ?: return null
        val s = clip.sourceRange
        // The restore delta: how far the edge moves back. The linked
        // audio follows by the SAME delta (the forward trim's rule,
        // mirrored) — one number, both clips, no separate inversion.
        val delta = when (edge) {
            TrimEdge.START -> oldPosition - s.start // restoring start: +d
            TrimEdge.END -> oldPosition - s.end // restoring end: +d
        }
        val restored: TimelineClip = when (edge) {
            TrimEdge.START -> clip.copy(
                sourceRange = FrameRange(s.start + delta, s.end),
                timelineRange = FrameRange(clip.timelineRange.start + delta, clip.timelineRange.end),
            )
            TrimEdge.END -> clip.copy(
                sourceRange = FrameRange(s.start, s.end + delta),
                timelineRange = FrameRange(clip.timelineRange.start, clip.timelineRange.end + delta),
            )
        }
        var clips = project.clips.map { if (it.id == clipId) restored else it }
        clip.linkedAudioId?.let { audioId ->
            val audio = clips.firstOrNull { it.id == audioId }
            if (audio != null) {
                clips = clips.map { if (it.id == audioId) trimClipOnly(audio, edge, delta) else it }
            }
        }
        return project.copy(clips = clips)
    }

    private fun deltaFor(edge: TrimEdge, old: FrameRange, newPosition: Double): Double =
        when (edge) {
            TrimEdge.START -> newPosition - old.start
            TrimEdge.END -> newPosition - old.end
        }

    /** Trims one clip (no linked-audio follow) by a delta at an edge. */
    private fun trimClipOnly(clip: TimelineClip, edge: TrimEdge, delta: Double): TimelineClip {
        val s = clip.sourceRange
        val t = clip.timelineRange
        return when (edge) {
            TrimEdge.START -> clip.copy(
                sourceRange = FrameRange(s.start + delta, s.end),
                timelineRange = FrameRange(t.start + delta, t.end),
            )
            TrimEdge.END -> clip.copy(
                sourceRange = FrameRange(s.start, s.end + delta),
                timelineRange = FrameRange(t.start, t.end + delta),
            )
        }
    }

    // ---------------------------------------------------------------
    // TL-05: frame-precise split at the playhead
    // ---------------------------------------------------------------

    /**
     * Splits a clip at [frame] (the playhead, project frame grid) into a
     * LEFT and a RIGHT piece. Frame precision (TL-05, "kare
     * hassasiyetinde"): the split lands on the frame grid, and the two
     * pieces' source ranges REJOIN to EXACTLY the old range —
     * left.sourceRange ∪ right.sourceRange == old.sourceRange with no
     * gap and no overlap, at frame precision (locked by the rejoin test).
     *
     * The LEFT piece keeps the clip's id (the clip continues; the right
     * piece is the new one) — the convention that keeps grades and links
     * anchored to the surviving left piece, documented so the UI shows
     * "split" as one clip becoming two, not a delete+add. The right piece
     * gets [rightId] (minted by the caller, so the caller can pre-book
     * the id for its own bookkeeping before applying — and so the
     * transaction layer knows the id for undo).
     *
     * Linked audio (linkedAudioId) splits at the SAME edit point (TL-05):
     * the audio's own playhead-relative position is the video's frame -
     * video.timelineRange.start + audio.timelineRange.start (the shared
     * timebase), and the audio's left piece keeps the audio clip's id.
     * The new audio right piece gets [audioRightId] when supplied (the
     * transaction layer supplies it so undo can address the piece), else
     * a freshly minted id. The new pieces carry the new cross-link
     * (left.video.linkedAudioId = audio left id, right.video.linkedAudioId
     * = audio right id).
     *
     * Returns the split result, or null when the clip is missing or the
     * frame is not strictly inside the clip (edges and outside are
     * no-ops — a split at the boundary produces nothing to split). A
     * linked audio whose mapped edit point falls outside the audio clip
     * is left UNSPLIT (the video still splits; the link survives on the
     * piece that covers it — mismatched A/V ranges are the caller's data
     * problem, not a reason to refuse the video edit).
     */
    fun split(
        project: TimelineProject,
        clipId: String,
        frame: Double,
        rightId: String,
        audioRightId: String? = null,
    ): TimelineProject? {
        val clip = project.clip(clipId) ?: return null
        val t = clip.timelineRange
        if (frame <= t.start || frame >= t.end) return null
        val splitPair = splitClip(clip, frame, rightId)
        var clips = ArrayList<TimelineClip>(project.clips.size + 1)
        // Replace the original with left, insert right after it.
        for (c in project.clips) {
            if (c.id == clipId) {
                clips.add(splitPair.left)
                clips.add(splitPair.right)
            } else {
                clips.add(c)
            }
        }
        // Linked audio splits at the same edit point (TL-05).
        clip.linkedAudioId?.let { audioId ->
            val audio = clips.firstOrNull { it.id == audioId }
            if (audio != null) {
                val audioFrame = frame - clip.timelineRange.start + audio.timelineRange.start
                if (audioFrame > audio.timelineRange.start && audioFrame < audio.timelineRange.end) {
                    val audioPair = splitClip(audio, audioFrame, audioRightId ?: TimelineIds.newId())
                    val idx = clips.indexOfFirst { it.id == audioId }
                    clips[idx] = audioPair.left
                    clips.add(idx + 1, audioPair.right)
                    // Re-link: each video piece to its audio piece.
                    clips = clips.map { c ->
                        when (c.id) {
                            splitPair.left.id -> c.copy(linkedAudioId = audioPair.left.id)
                            splitPair.right.id -> c.copy(linkedAudioId = audioPair.right.id)
                            else -> c
                        }
                    }.let { ArrayList(it) }
                }
            }
        }
        return project.copy(clips = clips)
    }

    /**
     * The result of a split: the two pieces. [rejoinedRange] is the
     * union range the pieces recombine into — equals the original clip's
     * source range exactly (the TL-05 rejoin rule).
     */
    data class SplitResult(val left: TimelineClip, val right: TimelineClip) {
        val rejoinedRange: FrameRange
            get() = FrameRange(left.sourceRange.start, right.sourceRange.end)
    }

    private fun splitClip(clip: TimelineClip, frame: Double, rightId: String): SplitResult {
        val s = clip.sourceRange
        val t = clip.timelineRange
        // Offset of the split inside the clip — identical in both grids
        // (the timebase contract), so one offset splits both ranges.
        val offset = frame - t.start
        val left = clip.copy(
            sourceRange = FrameRange(s.start, s.start + offset),
            timelineRange = FrameRange(t.start, frame),
        )
        val right = TimelineClip(
            id = rightId,
            trackId = clip.trackId,
            sourceId = clip.sourceId,
            sourceRange = FrameRange(s.start + offset, s.end),
            timelineRange = FrameRange(frame, t.end),
            colorDefinition = clip.colorDefinition,
            text = clip.text,
            linkedAudioId = clip.linkedAudioId,
        )
        return SplitResult(left, right)
    }

    // ---------------------------------------------------------------
    // TL-07: snap — clip edges, playhead, defined edit points
    // ---------------------------------------------------------------

    /**
     * Snaps [value] to the nearest snap point among the candidates
     * (TL-07): clip edges (every clip's timelineRange start and end),
     * the playhead, and other DEFINED edit points (markers — any
     * frame-line value the caller considers an edit point; the model
     * takes them as an argument so it stays UI-agnostic).
     *
     * Pure: returns the snapped value, or [value] unchanged when nothing
     * is within [threshold] frames. No hidden global snap config — the
     * caller passes the candidates and the threshold.
     */
    fun snap(value: Double, candidates: List<Double>, threshold: Double): Double {
        var best = value
        var bestDist = threshold
        for (c in candidates) {
            val d = kotlin.math.abs(c - value)
            if (d < bestDist || (d == bestDist && c < best)) {
                best = c
                bestDist = d
            }
        }
        return best
    }

    /**
     * All snap candidates of a project (TL-07): every clip edge, plus
     * [extra] (the playhead and markers the caller defines). Sorted.
     */
    fun snapCandidates(project: TimelineProject, extra: List<Double> = emptyList()): List<Double> {
        val pts = ArrayList<Double>(project.clips.size * 2 + extra.size)
        for (c in project.clips) {
            pts.add(c.timelineRange.start)
            pts.add(c.timelineRange.end)
        }
        pts.addAll(extra)
        return pts.sorted()
    }

    // ---------------------------------------------------------------
    // TL-17: timeline length resolution
    // ---------------------------------------------------------------

    /**
     * Resolves the timeline's length (TL-17): max of the last AUDIBLE
     * audio end, the last video end, the last TITLE end, and the open
     * project end. "Son duyulabilir ses, video, title ve açık proje
     * bitişi" — every content class contributes its own last end, and
     * the longest wins.
     *
     * The video-early-end rule: when the last video ends before the
     * resolved length, the project's BACKGROUND definition runs to that
     * length ("video erken biterse projenin arka plan tanımı ... kullanılır").
     * When NO background is defined, the audio (or title) duration is
     * used as-is — the timeline simply ends where the audio ends.
     *
     * Returns 0 for an empty project with no project end and no
     * background (nothing to show, length zero).
     */
    fun resolveLength(project: TimelineProject): Double {
        val lastVideoEnd = project.clips
            .filter { project.track(it.trackId)?.kind == TrackKind.VIDEO }
            .maxOfOrNull { it.timelineRange.end } ?: 0.0
        val lastAudioEnd = project.clips
            .filter { project.track(it.trackId)?.kind == TrackKind.AUDIO }
            .maxOfOrNull { it.timelineRange.end } ?: 0.0
        val lastTextEnd = project.clips
            .filter { project.track(it.trackId)?.kind == TrackKind.TEXT }
            .maxOfOrNull { it.timelineRange.end } ?: 0.0
        val contentEnd = maxOf(lastVideoEnd, lastAudioEnd, lastTextEnd)
        val projectEnd = project.projectEnd ?: 0.0
        val length = maxOf(contentEnd, projectEnd)
        // Video-early-end: with a background DEFINED, the background
        // duration is the floor for the video realm — the resolved
        // length covers the background definition's duration when the
        // video content ends earlier. Without one, audio/title rules.
        val bg = project.backgroundDuration
        return if (bg != null && bg > length) bg else length
    }
}
