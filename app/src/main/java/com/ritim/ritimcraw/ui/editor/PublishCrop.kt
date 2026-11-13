package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.PreviewFrame
import com.ritim.ritimcraw.engine.RenderDebounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The publish fold's crop step, OFF the main thread (audit fix
 * 2026-11-13): the editor's render pump is a main-dispatcher coroutine,
 * and the fold used to run [CropState.applyToArgb] inline — an ACTIVE
 * crop's row copies (a fresh output buffer per publish) and a
 * straighten's per-pixel bilinear loop were main-thread work between
 * the completed render and the frame swap. This object owns that step
 * for the screen, JVM-testable without Compose (the BeforeAfter /
 * PrecisionScrub precedent — the screen owns every state write, this
 * only decides and resamples):
 *
 *  - [plan] snapshots a completed render's crop inputs into an
 *    immutable [Request] and decides the arm: the NEUTRAL crop group
 *    (no straighten, full rect) publishes INLINE — no dispatcher hop,
 *    no allocation, exactly the pre-fix fast path — while an ACTIVE
 *    crop/straighten goes to [resample].
 *  - [resample] runs the pixel math on [Dispatchers.Default] — the
 *    screen's own off-main pattern (the scope compute's withContext),
 *    no new concurrency: the fold AWAITS the result, so the pump stays
 *    serial (one render, one crop, one publish, in sequence order).
 *  - [mayPublish] is the generation guard at the publish door: a crop
 *    result may swap the frame only while its sequence is still the
 *    newest publication — a stale crop result can never overwrite a
 *    newer frame. Today the serial-await pump cannot produce a stale
 *    result (the guard is the invariant pinned at the door,
 *    JVM-tested here); any future shape where the resample outlives
 *    the fold inherits the drop rule for free.
 *
 * The pixel math itself is [CropState.applyToArgb] — the single
 * crop/straighten decision object every consumer maps through, so the
 * off-main path cannot disagree with the export pixels. The pure
 * crop/straighten rules are tested in CropStateTest; the tests here
 * cover the wrapper: the plan split, the snapshot, the resample's
 * pixel-equivalence with the inline call, and the ordering/generation
 * semantics.
 */
object PublishCrop {

    /**
     * An immutable snapshot of ONE completed render's crop inputs: the
     * render's ARGB buffer (each render allocates a fresh buffer; it is
     * never mutated after the render returns), its dimensions, the
     * grade's crop rect (a value copy) and straighten angle, the manual
     * rotation delta the rect maps through, and the request's identity
     * (sequence, frame index) for the generation guard and the publish.
     */
    data class Request(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val rect: CropState.RectF,
        val straightenDeg: Float,
        val spaceDeltaDeg: Int,
        val sequence: Long,
        val frameIndex: Int,
    )

    /** What the publish fold does with a completed render. */
    sealed interface Plan {

        /**
         * Neutral crop group (no straighten, full rect): publish the
         * render as-is, inline — [CropState.applyToArgb] would return
         * the input buffer by reference, so there is nothing to
         * resample and no reason to leave the main thread.
         */
        data class PublishInline(val frame: PreviewFrame) : Plan

        /**
         * Active crop/straighten: run [resample] with the immutable
         * snapshot and publish its result.
         */
        data class ResampleOffMain(val request: Request) : Plan
    }

    /**
     * Decides the fold's arm for a completed render. The inline test is
     * exactly [CropState.applyToArgb]'s own fast-path condition seen
     * from the grade side: a full rect maps to a full rect under every
     * quarter turn ([CropState.mapRect]), so `no straighten + full
     * rect` is a no-op in every rotation space and the pre-fix publish
     * (the input buffer, untouched) is the correct result.
     */
    fun plan(
        frame: PreviewFrame,
        grade: GradeK,
        spaceDeltaDeg: Int,
        sequence: Long,
        frameIndex: Int,
    ): Plan {
        val rect = CropState.RectF(grade.cropX, grade.cropY, grade.cropW, grade.cropH)
        if (grade.straightenDeg == 0f && CropState.isFull(rect)) {
            return Plan.PublishInline(frame)
        }
        return Plan.ResampleOffMain(
            Request(
                argb = frame.argb,
                width = frame.width,
                height = frame.height,
                rect = rect,
                straightenDeg = grade.straightenDeg,
                spaceDeltaDeg = spaceDeltaDeg,
                sequence = sequence,
                frameIndex = frameIndex,
            ),
        )
    }

    /**
     * Runs the crop/straighten resample on [Dispatchers.Default] and
     * returns the cropped frame. Pure pixel math over the immutable
     * snapshot: [CropState.applyToArgb] allocates its own output
     * buffer, so the result is exclusive to this call and the caller's
     * publish is a plain state write once the await resumes.
     */
    suspend fun resample(request: Request): PreviewFrame = withContext(Dispatchers.Default) {
        val cropped = CropState.applyToArgb(
            argb = request.argb,
            width = request.width,
            height = request.height,
            rect = request.rect,
            straightenDeg = request.straightenDeg,
            spaceDeltaDeg = request.spaceDeltaDeg,
        )
        PreviewFrame(cropped.width, cropped.height, cropped.argb)
    }

    /**
     * The generation guard: a crop that completed for [sequence] may
     * publish only while it is still the newest publication
     * ([lastPublished]) — [RenderDebounce.isFresh]'s monotonic rule,
     * the same one that gates the render result itself, applied at the
     * crop's publish door.
     */
    fun mayPublish(sequence: Long, lastPublished: Long): Boolean =
        RenderDebounce.isFresh(sequence, lastPublished)
}
