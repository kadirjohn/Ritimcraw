package com.ritim.ritimcraw.export

/**
 * Pure PTS math for the MP4 export (JVM-testable, no Android imports).
 *
 * The clip's frame timestamps come from the native source in microseconds
 * (nativeFramePtsUs; strictly increasing per the real-clip test). This class
 * turns them into the encoder presentation timestamps the muxer writes:
 *
 *  - Video: the FIRST frame's pts is normalized to 0 (players and the spec
 *    expect the movie to start at t=0; the clip's wall-clock origin carries
 *    no meaning after export). Every later frame keeps its relative offset:
 *    pts[i] = rawPts[i] - rawPts[0]. Monotonicity is guaranteed by the
 *    source's strictly-increasing pts; if a clip ever violates it, the
 *    timeline clamps to the previous frame + 1 us so the muxer never sees a
 *    non-monotonic pts (MediaMuxer throws on that).
 *
 *  - Audio: the clip's PCM start is aligned with the video start (both are
 *    normalized by the same first video pts), so audio sample k lands at
 *    (k * 1e6 / sampleRate) us. A/V stay in sync because both clocks hang
 *    off the same origin.
 *
 *  - Interleaving: the muxer wants samples roughly in presentation order.
 *    [interleave] merges the video pts list and the audio sample-block pts
 *    list into one ordering, tagged by track, that the pipeline walks in
 *    order.
 */
object PtsTimeline {

    /** A muxer submission: which track and at which encoder pts (us). */
    data class Sample(val track: Track, val ptsUs: Long, val frameIndex: Int = -1) {
        enum class Track { VIDEO, AUDIO }
    }

    /**
     * Frame pts (us, strictly increasing from the source) -> encoder pts,
     * normalized so the first frame is 0 and monotonicity holds.
     */
    fun videoPts(framePtsUs: List<Long>): List<Long> {
        if (framePtsUs.isEmpty()) return emptyList()
        val origin = framePtsUs.first()
        val out = ArrayList<Long>(framePtsUs.size)
        var prev = -1L
        for (raw in framePtsUs) {
            val p = raw - origin
            // Never hand the muxer a non-monotonic pts; the source is
            // strictly increasing, this is a defensive clamp.
            val clamped = if (p <= prev) prev + 1 else p
            out.add(clamped)
            prev = clamped
        }
        return out
    }

    /**
     * Encoder pts (us) of audio sample block [blockIndex] (one AAC packet
     * worth of samples), given the source's first video pts as the shared
     * origin. Sample k of the clip lands at k * 1e6 / sampleRate us past
     * the origin.
     */
    fun audioBlockPts(blockIndex: Int, samplesPerBlock: Int, sampleRate: Int): Long =
        blockIndex.toLong() * samplesPerBlock * 1_000_000L / sampleRate

    /**
     * Presentation-ordered merge of video frames and audio blocks, tagged
     * by track, so the pipeline can submit samples in one pass. First pts
     * of each list must already be normalized (video via [videoPts], audio
     * via [audioBlockPts] with the same origin).
     */
    fun interleave(
        videoPts: List<Long>,
        audioBlockPts: List<Long>,
    ): List<Sample> {
        data class Indexed(val pts: Long, val i: Int, val audio: Boolean)

        val events = ArrayList<Indexed>(videoPts.size + audioBlockPts.size)
        videoPts.forEachIndexed { i, p -> events.add(Indexed(p, i, false)) }
        audioBlockPts.forEachIndexed { i, p -> events.add(Indexed(p, i, true)) }
        // Stable sort keeps the source order of equal pts: video first (the
        // first video frame opens the movie).
        events.sortWith(compareBy({ it.pts }, { it.audio }))
        return events.map {
            if (it.audio) {
                Sample(Sample.Track.AUDIO, it.pts, it.i)
            } else {
                Sample(Sample.Track.VIDEO, it.pts, it.i)
            }
        }
    }
}
