package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK

/**
 * [ScrubFrameSource] over the editor's OPEN [EditorPreviewSession] — the
 * engine-side half of the scrub-frames wiring (F2-C3, the branch's
 * [ScrubFrames] facade): the background filler asks THIS adapter for a
 * small frame of any index and it renders through the SAME session seam
 * every editor render already uses, at the scrub tier
 * ([ScrubFramesPolicy.TIER_MAX_DIM], a tiny 320px long edge so the walk
 * stays cheap), then keeps the frame's OWN pixels (the session already
 * scales to maxDim — [ArgbScale.fit] for the encoded path, strideFor for
 * MCRAW). No new decoder is opened and no second engine is involved: one
 * session, one engine thread, exactly the seam contract.
 *
 * Renders with the grade AT ATTACH TIME (the editor re-attaches whenever
 * the settled grade changes, so the walk always carries the clip's
 * CURRENT grade — the cache key holds only sourceId/frameIndex/tier per
 * the facade's design, so a grade change must invalidate via re-attach +
 * [ScrubFrames.release], which the screen's attach seam does).
 *
 * renderClass is [PreviewTiers.RenderClass.DRAG]: a filler frame is an
 * interactive-path render (the temporal NR gate's rule — the ~85-107 ms
 * merge never runs for these), and it must NEVER displace honest classing.
 *
 * Failures degrade to null ([ScrubFrameSource]'s "cannot be produced"):
 * the filler skips that index and keeps walking (its `?: continue`
 * contract), so one bad frame never cancels the walk.
 */
class SessionScrubSource(
    private val session: EditorPreviewSession,
    private val grade: GradeK,
    private val lutPath: String? = null,
    private val gyroPath: String? = null,
    private val chainWire: FloatArray? = null,
    private val tierMaxDim: Int = ScrubFramesPolicy.TIER_MAX_DIM,
) : ScrubFrameSource {

    override val frameCount: Int get() = session.frames

    override suspend fun frameArgb(index: Int, maxDim: Int): ScrubPixels? {
        if (index !in 0 until frameCount) return null
        if (maxDim != tierMaxDim) {
            // The filler's tier is the policy's tier; a mismatched request
            // would key the cache at a tier the walk never fills. Render
            // anyway (the caller asked), but at the policy tier so the
            // cached JPEG matches its key.
            return frameArgb(index, tierMaxDim)
        }
        val frame = session.render(
            index, grade, maxDim,
            lutPath, gyroPath,
            PreviewTiers.RenderClass.DRAG,
            chainWire,
        ).getOrNull() ?: return null
        return ScrubPixels(frame.argb, frame.width, frame.height)
    }
}
