package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.MediaSource
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The timeline EXPORT PLAN (the timeline's first export): the pure,
 * JVM-testable walk that turns a [TimelineProject] into the exact
 * frame-by-frame recipe an MP4 encode follows — which source frame to
 * decode for each output frame, where the black gaps are, and what was
 * honestly skipped. The plan is DATA: [com.ritim.ritimcraw.export]'s
 * timeline exporter consumes it; nothing here opens a file, touches a
 * codec, or imports Android.
 *
 * TIMEBASE. The project grid is the timebase (TimelineModel's contract):
 * every range value is a frame number in the PROJECT's frame grid. A
 * clip's clip-local output frame j therefore sits at project position
 * sourceRange.start + j, and the SOURCE frame it must show is
 *
 *   sourceFrame(j) = round((sourceRange.start + j) * sourceFps / projectFps)
 *
 * clamped to [0, realFrameCount - 1]. This is FRAME-NEAREST MAPPING, not
 * resampling (v1, documented): at equal rates it is the identity, at
 * 24 fps in a 30 fps project the nearest source frame repeats where the
 * output grid is denser (output frame 30 -> source frame 24 — one real
 * second either way), and no interpolation or blend ever runs. Real
 * time is preserved because both grids are converted through the same
 * two rates; only rounding, never resampling, decides the frame.
 *
 * HONESTY GATES (the metadata rule — never guess):
 *  - [fpsMeasured] is the ONLY source of a source's frame rate. A null
 *    (UNKNOWN) or non-positive answer SKIPS the clip with
 *    [ClipSkip.Reason.FPS_UNKNOWN] — the plan never substitutes the
 *    project rate or any other guess.
 *  - A source missing from [sources] (not in the Library) SKIPS the clip
 *    with [ClipSkip.Reason.SOURCE_MISSING].
 *  - A null or non-positive real frame count SKIPS the clip with
 *    [ClipSkip.Reason.FRAME_COUNT_UNKNOWN] — without it the clamp below
 *    would be a guess about the source's bounds.
 * A SKIPPED clip does not shrink the movie: its timeline span renders
 * as BLACK frames (the walk fills it like any gap) and the reason is
 * recorded in [skips] for the exporter's stage log. The export completes
 * honestly rather than failing whole.
 *
 * WALK RULES (v1, locked by tests):
 *  - Only VIDEO-track clips render, in timeline order (sorted by
 *    timelineRange.start).
 *  - Output positions snap to whole frames with round(); the total
 *    output length is ceil([TimelineOps.resolveLength]) — the TL-17
 *    resolver is the length authority, so an audio or title tail beyond
 *    the last video frame renders as trailing black (the video-early-end
 *    rule with no background definition).
 *  - Gaps between clips (and a skipped clip's whole span) are
 *    [Entry.Black] runs; consecutive black spans merge.
 *  - Overlapping clips are not supported: the cursor never moves back,
 *    so a clip whose rounded start is behind the cursor starts at the
 *    cursor and its overlapped head is dropped (the earlier clip wins).
 *  - A zero-duration clip contributes nothing (no entry, no skip).
 *  - Output dimensions are EVEN-SNAPPED (encoders want even YUV sizes):
 *    width and height round down to their largest even value.
 *
 * AUDIO: v1 exports the VIDEO track only. [audioSkipped] is true when
 * the project holds any AUDIO-track clip — the plan-level marker that
 * tells the exporter (and the user, through its stage log) the audio
 * was consciously left out of this export, not lost. Mixing placed PCM
 * at per-clip rates/gains/fades is a later milestone (AUD-05/07).
 */
data class TimelineExportPlan(
    /** The output frame grid's fps (TimelineOutput.fps, the timebase). */
    val fps: Int,
    /** Even-snapped output width (TimelineOutput.width rounded down). */
    val width: Int,
    /** Even-snapped output height (TimelineOutput.height rounded down). */
    val height: Int,
    /** Total output frames: ceil(resolveLength) — the movie's exact length. */
    val totalFrames: Int,
    /**
     * The frame recipe in OUTPUT ORDER: contiguous, starting at 0,
     * covering every frame of [0, totalFrames) exactly once.
     */
    val entries: List<Entry>,
    /** Clips that render as black, with the honest reason each was skipped. */
    val skips: List<ClipSkip>,
    /** True when the project holds AUDIO-track clips v1 does not mux. */
    val audioSkipped: Boolean,
) {
    /**
     * One run of output frames: a clip's decoded frames, or a black gap.
     * Entries are contiguous — each starts where the previous ended.
     */
    sealed interface Entry {
        /** First output frame of the run (inclusive). */
        val outputStart: Int

        /** Frames in the run; [outputEnd] is exclusive. */
        val frameCount: Int
        val outputEnd: Int get() = outputStart + frameCount

        /**
         * A video clip's span: output frame outputStart + i shows source
         * frame [sourceFrames][i] (the frame-nearest mapping above,
         * already rounded and clamped — the renderer decodes, never
         * computes).
         */
        data class Clip(
            /** The timeline clip's stable id (joins back to the project). */
            val clipId: String,
            /** The MediaSource id whose frames render here. */
            val sourceId: String,
            override val outputStart: Int,
            /** Decoded source frame index per output frame, in order. */
            val sourceFrames: List<Int>,
        ) : Entry {
            override val frameCount: Int get() = sourceFrames.size
        }

        /** A black gap: every frame in the run renders black. */
        data class Black(
            override val outputStart: Int,
            override val frameCount: Int,
        ) : Entry
    }

    /** A clip the plan refused to guess about, with the honest reason. */
    data class ClipSkip(
        /** The timeline clip's stable id. */
        val clipId: String,
        /** The clip's source id, or null for a clip with no source. */
        val sourceId: String?,
        val reason: Reason,
    ) {
        enum class Reason {
            /** fpsMeasured is UNKNOWN (or non-positive) — never guessed. */
            FPS_UNKNOWN,

            /** The source is not in the Library (the sources map). */
            SOURCE_MISSING,

            /** The real frame count is unknown — clamping would guess. */
            FRAME_COUNT_UNKNOWN,
        }
    }

    companion object {

        /**
         * Builds the export plan for [project]'s VIDEO track.
         *
         * @param sources the Library's sources by id — presence here is
         *   the SOURCE_MISSING gate; the exporter later re-resolves uris
         *   from the same map.
         * @param fpsMeasured the ONLY honest source rate: returns the
         *   source's measured fps (KNOWN or ESTIMATED both count) or
         *   null when UNKNOWN — a null skips the clip, never a guess.
         * @param frameCount the source's REAL frame count (metadata /
         *   probe), or null when unknown — the mapping clamps to it.
         */
        fun build(
            project: TimelineProject,
            sources: Map<String, MediaSource>,
            fpsMeasured: (String) -> Double?,
            frameCount: (String) -> Int?,
        ): TimelineExportPlan {
            val projectFps = project.output.fps
            require(projectFps > 0) { "project fps $projectFps is not a usable timebase" }
            val width = (project.output.width / 2) * 2
            val height = (project.output.height / 2) * 2
            require(width >= 2 && height >= 2) {
                "output ${project.output.width}x${project.output.height} is not a usable video size"
            }

            val totalFrames = ceil(TimelineOps.resolveLength(project)).toInt().coerceAtLeast(0)
            val videoTrackIds = project.tracks
                .filter { it.kind == TrackKind.VIDEO }
                .map { it.id }
                .toSet()
            val audioTrackIds = project.tracks
                .filter { it.kind == TrackKind.AUDIO }
                .map { it.id }
                .toSet()
            val audioSkipped = project.clips.any { it.trackId in audioTrackIds }

            // The walk: video clips in timeline order, one cursor over the
            // output grid. Skipped clips never advance the cursor — their
            // span fills black exactly like a gap (the walk's next clip or
            // the tail fill covers it).
            val entries = ArrayList<Entry>(project.clips.size + 4)
            val skips = ArrayList<ClipSkip>()
            var cursor = 0
            for (clip in project.clips
                .filter { it.trackId in videoTrackIds }
                .sortedBy { it.timelineRange.start }) {
                val clipOutStart = clip.timelineRange.start.roundToInt()
                val clipOutEnd = clip.timelineRange.end.roundToInt()
                if (clipOutEnd <= clipOutStart) continue // zero-duration: nothing

                val gate = gateFor(clip, sources, fpsMeasured, frameCount)
                if (gate.reason != null) {
                    skips.add(ClipSkip(clip.id, clip.sourceId, gate.reason))
                    continue
                }

                val outStart = maxOf(cursor, clipOutStart)
                val outEnd = minOf(clipOutEnd, totalFrames)
                if (outEnd <= outStart) continue // overlapped away, or past the end
                if (outStart > cursor) {
                    entries.add(Entry.Black(cursor, outStart - cursor))
                }

                // The frame-nearest mapping (see the file KDoc): clip-local
                // output frame j sits at sourceRange.start + j on the project
                // grid; the source frame there is round(pos * fps ratio),
                // clamped to the source's real bounds.
                val sourceFps = gate.fps
                val realFrames = gate.frames
                // The gate validated sourceId non-null and in the Library.
                val j0 = outStart - clipOutStart
                val sourceFrames = ArrayList<Int>(outEnd - outStart)
                for (i in 0 until (outEnd - outStart)) {
                    val pos = clip.sourceRange.start + (j0 + i)
                    val f = (pos * sourceFps / projectFps).roundToInt()
                    sourceFrames.add(f.coerceIn(0, realFrames - 1))
                }
                entries.add(Entry.Clip(clip.id, gate.sourceId, outStart, sourceFrames))
                cursor = outEnd
            }
            // The tail: black to the resolved length (an audio/title tail,
            // a skipped last clip, or an explicit project end).
            if (cursor < totalFrames) {
                entries.add(Entry.Black(cursor, totalFrames - cursor))
            }

            return TimelineExportPlan(
                fps = projectFps,
                width = width,
                height = height,
                totalFrames = totalFrames,
                entries = entries,
                skips = skips,
                audioSkipped = audioSkipped,
            )
        }

        /**
         * The honest gates' verdict: a non-null [reason] skips the clip;
         * a null reason means it renders, with [sourceId]/[fps]/[frames]
         * the non-null gate answers the mapping uses (never re-asked).
         */
        private class Gate(
            val reason: ClipSkip.Reason?,
            val sourceId: String = "",
            val fps: Double = 0.0,
            val frames: Int = 0,
        )

        /**
         * The honest gates in order (see the file KDoc): no source id, no
         * Library entry, no measured fps, no real frame count — each is a
         * skip with its reason, never a guess.
         */
        private fun gateFor(
            clip: TimelineClip,
            sources: Map<String, MediaSource>,
            fpsMeasured: (String) -> Double?,
            frameCount: (String) -> Int?,
        ): Gate {
            val sid = clip.sourceId ?: return Gate(ClipSkip.Reason.SOURCE_MISSING)
            if (sid !in sources) return Gate(ClipSkip.Reason.SOURCE_MISSING)
            val fps = fpsMeasured(sid)
            if (fps == null || fps <= 0.0) return Gate(ClipSkip.Reason.FPS_UNKNOWN)
            val frames = frameCount(sid)
            if (frames == null || frames <= 0) return Gate(ClipSkip.Reason.FRAME_COUNT_UNKNOWN)
            return Gate(null, sid, fps, frames)
        }
    }
}
