package com.ritim.ritimcraw.data.timeline

/**
 * The timeline EDITING model (Stage C, spec TL-01..TL-17) — the pure data
 * a timeline project is cut from. NOT the export PTS mapping: export/
 * PtsTimeline.kt turns one clip's real frame timestamps into encoder
 * presentation timestamps for the MP4 muxer; THIS model is the editing
 * surface (tracks, clips, ranges, edit points) the timeline UI edits and
 * TimelineStore persists into project.json's "timeline" section. The two
 * never share a name: PtsTimeline is per-clip export math, this is the
 * project editing model (TimelineProject / TimelineClip / TimelineTrack).
 *
 * Everything here is pure Kotlin — immutable data classes and pure
 * functions, no Android imports — so the whole model is JVM-testable
 * headless (the store and the edit transactions build on it).
 *
 * TIMEBASE CONTRACT (TL-04, the load-bearing rule). Every time value in
 * this model is a FRAME NUMBER in the PROJECT's frame grid, as a Double:
 *   - projectTimelineRange uses the grid directly;
 *   - sourceRange uses the SAME grid, expressed at the SOURCE's frame
 *     rate converted into project frames — the source's own frame k is
 *     at source-frame k * (sourceFps / projectFps) on the project grid.
 * The contract's point: trim from EITHER edge is the same operation on
 * the same numbers. The source range and the timeline placement share
 * one timebase, so "trim the start" moves both sourceRange.start and
 * timelineRange.start by the same delta, and "trim the end" moves both
 * ends. Nothing converts between units on the trim path — the UI hands
 * frame-grid values in, the model moves both edges by them. (The
 * decode-window optimization — resolving audio and video only in the
 * needed range — happens later, on top of this range model.)
 *
 * Frame precision: values are Doubles but EDITS land on whole frames —
 * splitAt is frame-precise (TL-05, "kare hassasiyetinde"), and the split
 * rejoin test locks that left.range + right.range == the old range
 * EXACTLY, at frame precision, with no sub-frame remainder.
 *
 * ID STABILITY (TL-03). Track and clip ids are opaque Strings, minted
 * once and PERSISTED — never derived from position, order or content.
 * A clip keeps its id through every edit (move, trim, split keeps the
 * left piece's id — documented on split) and through close + reopen
 * (the store round-trips ids verbatim). Position-derived ids would
 * break "kimlikler edit ve yeniden açılışta korunur" the first time a
 * clip is moved; opaque minted ids keep it true by construction.
 * Minting is TimelineIds.newId() — a monotonic counter + time seed,
 * documented there; the model itself never mints.
 */

/** Timeline track kinds: video, audio, and independent text (TL-03). */
enum class TrackKind { VIDEO, AUDIO, TEXT }

/** One track. Ids are stable strings (see the file KDoc, ID STABILITY). */
data class TimelineTrack(
    val id: String,
    val kind: TrackKind,
    /** 0-based stacking order; the timeline UI lays tracks out by this. */
    val index: Int = 0,
)

/**
 * Project output settings (TL-01): resolution, aspect, orientation, fps,
 * color output and HDR. The source's SUGGESTED values seed these at
 * creation; every field is user-editable and persisted by TimelineStore.
 * Defaults here are neutral (1080p SDR Rec.709 30fps) so an empty model
 * is a valid project; "kaynağın önerdiği değerler" arrive from the caller
 * (the UI) at creation, not baked into the model.
 */
data class TimelineOutput(
    val width: Int = 1920,
    val height: Int = 1080,
    /** Frames per second of the PROJECT frame grid (the timebase). */
    val fps: Int = 30,
    /** 0 = landscape, 90 = portrait, 180/270 = the flips. */
    val orientationDeg: Int = 0,
    /** Output color space: "rec709", "display-p3", "rec2020". */
    val colorSpace: String = "rec709",
    /** Output transfer: "srgb", "hlg", "pq", "sdr-video". */
    val transfer: String = "sdr-video",
    /** HDR output toggle (HLG/PQ); false = SDR. */
    val hdr: Boolean = false,
) {
    companion object {
        val SUPPORTED_COLOR_SPACES = listOf("rec709", "display-p3", "rec2020")
        val SUPPORTED_TRANSFERS = listOf("srgb", "hlg", "pq", "sdr-video")
    }
}

/**
 * Per-clip source color definition (TL-02, mixed projects). A project can
 * mix MCRAW, Mi-Log and APV sources; each clip carries its own source
 * color definition and the project carries ONE common output target
 * (TimelineOutput). RAW and encoded sources get the same edit operations
 * (the model is source-kind-agnostic — sourceId is opaque; only this
 * definition tells the render path how to interpret the source).
 * null fields mean "use the project defaults" — an all-RAW project never
 * sets these, keeping its persisted shape minimal.
 */
data class ClipColorDefinition(
    /** Source log encoding: "mcraw-raw", "mi-log", "apv", "sdr". */
    val encoding: String? = null,
    /** Source color space, e.g. "bt2020" for Mi-Log; null = project default. */
    val colorSpace: String? = null,
    /** Source transfer expected in the file, e.g. "hlg" for APV; null = project default. */
    val transfer: String? = null,
)

/**
 * A frame-grid range [start, end) — HALF-OPEN: end is exclusive, so a
 * clip's duration is end - start and adjacent clips share an edge value
 * without overlap. Both edges live on the SAME timebase (the contract in
 * the file KDoc), which is what makes trim-from-either-edge one rule.
 */
data class FrameRange(val start: Double, val end: Double) {
    val duration: Double get() = end - start

    init {
        require(end >= start) { "range end $end before start $start" }
    }
}

/**
 * One clip: a media source (or a text block) placed on a track. For
 * media clips sourceRange is the range used INSIDE the source (project-
 * grid units — the timebase contract), timelineRange is where it plays
 * on the timeline; the two always have the SAME duration (trim moves both
 * edges by the same delta, split splits both at the same frame). For text
 * clips sourceRange is the full text duration domain (0..duration) and
 * timelineRange places it; text has no source media to interpret.
 */
data class TimelineClip(
    val id: String,
    val trackId: String,
    /** MediaSource id, or null for an independent text placement (TL-03). */
    val sourceId: String? = null,
    /** The range used inside the source (project frame grid; timebase contract). */
    val sourceRange: FrameRange,
    /** Where the clip plays on the timeline (project frame grid). */
    val timelineRange: FrameRange,
    /** Per-clip source color definition for mixed projects (TL-02). */
    val colorDefinition: ClipColorDefinition = ClipColorDefinition(),
    /** Text content for TEXT clips (sourceId null); blank for media clips. */
    val text: String = "",
    /** Linked audio clip id when this video clip has attached audio (TL-05). */
    val linkedAudioId: String? = null,
) {
    init {
        require(sourceRange.duration == timelineRange.duration) {
            "source range duration ${sourceRange.duration} != timeline duration ${timelineRange.duration}"
        }
    }
}

/**
 * The timeline project: settings (TL-01/TL-02), tracks, clips. Immutable —
 * every edit returns a new TimelineProject (TimelineOps / TimelineEdit).
 * clips is the ordered clip list; the UI groups by trackId and sorts by
 * timelineRange.start for display.
 */
data class TimelineProject(
    val output: TimelineOutput = TimelineOutput(),
    val tracks: List<TimelineTrack> = List(3) { i ->
        TimelineTrack(id = "t$i", kind = TrackKind.entries[i % 3], index = i)
    },
    val clips: List<TimelineClip> = emptyList(),
    /**
     * The open project's explicit end (TL-17): the user's declared project
     * end, if any. The length resolver takes max(lastAudioEnd, lastVideoEnd,
     * lastTitleEnd, projectEnd) — an explicitly-set end can extend the
     * timeline beyond the content (the "açık proje bitişi" term).
     */
    val projectEnd: Double? = null,
    /**
     * Background definition for the video-early-end rule (TL-17): when the
     * last video ends before the resolved audio/title length, the background
     * runs to that length; this is its definition (color/title card). null
     * duration = no background definition = the audio (or title) rule wins.
     */
    val backgroundDuration: Double? = null,
) {
    /** Clip lookup by stable id — edits address clips by id, never position. */
    fun clip(id: String): TimelineClip? = clips.firstOrNull { it.id == id }

    fun track(id: String): TimelineTrack? = tracks.firstOrNull { it.id == id }
}

/**
 * Mints stable opaque ids for tracks and clips. Monotonic counter + time
 * seed: unique within a session, unique across sessions (the time seed),
 * short and human-scannable in project.json, and NEVER position-derived
 * (the ID STABILITY rule). The STORE persists ids verbatim, so reopen
 * finds the same ids; the counter is not global state — it is a helper
 * for callers that mint ids (the UI at insert time), and the model treats
 * ids as opaque.
 */
object TimelineIds {
    private val counter = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

    /** A fresh clip/track id, e.g. "c-4711e6" (counter + time seed). */
    fun newId(prefix: String = "c"): String {
        val n = counter.incrementAndGet()
        val seed = System.nanoTime()
        return "$prefix-${n.toString(36)}${seed.toString(36).take(4)}"
    }
}
