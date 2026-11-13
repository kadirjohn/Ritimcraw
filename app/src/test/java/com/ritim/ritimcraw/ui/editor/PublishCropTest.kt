package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.PreviewFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PublishCrop tests, all JVM (audit fix 2026-11-13): the publish fold's
 * crop wrapper — the plan split (neutral publishes inline, active goes
 * off-main), the immutable snapshot, the resample's pixel-equivalence
 * with the inline CropState.applyToArgb call it replaced, and the
 * generation guard at the publish door. The pure crop/straighten rules
 * themselves are CropStateTest's; these cover the wrapper semantics.
 * No Android framework class is touched.
 */
class PublishCropTest {

    /** A tiny 8x6 graded frame; pixel i is packed (i, i, i). */
    private fun frame(w: Int = 8, h: Int = 6): PreviewFrame =
        PreviewFrame(w, h, IntArray(w * h) { 0xFF000000.toInt() or it })

    // ---- plan: the arm split ----

    @Test
    fun neutral_crop_publishes_inline_with_no_new_allocation() {
        // The pre-fix fast path, kept: a full rect with no straighten is
        // a no-op in every rotation space, so the fold publishes the
        // render's own buffer by reference and never leaves main.
        val f = frame()
        val plan = PublishCrop.plan(
            f, GradeK(), spaceDeltaDeg = 90, sequence = 3L, frameIndex = 7,
        )
        assertTrue(plan is PublishCrop.Plan.PublishInline)
        assertSame(f, (plan as PublishCrop.Plan.PublishInline).frame)
    }

    @Test
    fun straighten_alone_goes_off_main_even_with_a_full_rect() {
        // A straighten angle is per-pixel bilinear work on ANY rect —
        // the audit's main-thread cost — so it resamples off main.
        val plan = PublishCrop.plan(
            frame(), GradeK(straightenDeg = 2.5f),
            spaceDeltaDeg = 0, sequence = 3L, frameIndex = 7,
        )
        assertTrue(plan is PublishCrop.Plan.ResampleOffMain)
    }

    @Test
    fun cropped_rect_goes_off_main_even_without_straighten() {
        // An active rect is a fresh-buffer row copy — also main-thread
        // work worth moving, even with no straighten.
        val plan = PublishCrop.plan(
            frame(), GradeK(cropX = 0.1f, cropY = 0.1f, cropW = 0.5f, cropH = 0.5f),
            spaceDeltaDeg = 0, sequence = 3L, frameIndex = 7,
        )
        assertTrue(plan is PublishCrop.Plan.ResampleOffMain)
    }

    @Test
    fun snapshot_is_immutable_and_carries_the_request_identity() {
        // The resample's inputs are a value snapshot: the rect is a
        // copy, the identity (sequence/frame) travels with the pixels so
        // the generation guard can judge the result when it lands.
        val g = GradeK(cropX = 0.25f, cropY = 0.25f, cropW = 0.5f, cropH = 0.5f)
        val plan = PublishCrop.plan(
            frame(), g, spaceDeltaDeg = 180, sequence = 11L, frameIndex = 4,
        ) as PublishCrop.Plan.ResampleOffMain
        val r = plan.request
        assertEquals(11L, r.sequence)
        assertEquals(4, r.frameIndex)
        assertEquals(180, r.spaceDeltaDeg)
        assertEquals(CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f), r.rect)
        // The grade mutating afterwards cannot reach the snapshot.
        assertEquals(0.25f, r.rect.x)
    }

    // ---- resample: pixel equivalence with the inline call ----

    @Test
    fun resample_matches_the_inline_crop_state_call_pixel_for_pixel() {
        // The off-main path must produce EXACTLY the pixels the inline
        // CropState.applyToArgb call produced before the fix — same
        // math object, same inputs — so the preview cannot disagree
        // with the export pixels.
        val f = frame(12, 9)
        val g = GradeK(
            cropX = 0.15f, cropY = 0.1f, cropW = 0.6f, cropH = 0.7f,
            straightenDeg = 3.5f,
        )
        val plan = PublishCrop.plan(
            f, g, spaceDeltaDeg = 90, sequence = 5L, frameIndex = 2,
        ) as PublishCrop.Plan.ResampleOffMain
        val offMain = runBlocking { PublishCrop.resample(plan.request) }
        val inline = CropState.applyToArgb(
            argb = f.argb, width = f.width, height = f.height,
            rect = CropState.RectF(g.cropX, g.cropY, g.cropW, g.cropH),
            straightenDeg = g.straightenDeg, spaceDeltaDeg = 90,
        )
        assertEquals(inline.width, offMain.width)
        assertEquals(inline.height, offMain.height)
        assertTrue(offMain.argb.contentEquals(inline.argb))
        // The result is a fresh buffer — the render's own buffer is
        // never handed to the publish mutated or aliased.
        assertNotSame(f.argb, offMain.argb)
    }

    @Test
    fun resample_of_a_cropped_rect_cuts_the_expected_region() {
        // Sanity of the wrapper plumbing through a deterministic crop:
        // the left half of an 8x6 gradient, no straighten.
        val f = frame(8, 6)
        val g = GradeK(cropX = 0f, cropY = 0f, cropW = 0.5f, cropH = 1f)
        val plan = PublishCrop.plan(
            f, g, spaceDeltaDeg = 0, sequence = 1L, frameIndex = 0,
        ) as PublishCrop.Plan.ResampleOffMain
        val out = runBlocking { PublishCrop.resample(plan.request) }
        assertEquals(4, out.width)
        assertEquals(6, out.height)
        // Row-major: out[y][x] is the source's left-half pixel.
        for (y in 0 until 6) {
            for (x in 0 until 4) {
                assertEquals(f.argb[y * 8 + x], out.argb[y * 4 + x])
            }
        }
    }

    // ---- mayPublish: the generation guard ----

    @Test
    fun a_fresh_sequence_may_publish() {
        // The serial pump: nothing newer published since the request.
        assertTrue(PublishCrop.mayPublish(sequence = 7L, lastPublished = 6L))
        assertTrue(PublishCrop.mayPublish(sequence = 7L, lastPublished = 7L))
    }

    @Test
    fun a_stale_sequence_may_never_publish() {
        // THE invariant the guard pins: a crop result that lands for a
        // sequence the publication has already moved past (a newer
        // render published while the resample ran) can never overwrite
        // the newer frame — it is dropped, exactly like a stale render.
        assertFalse(PublishCrop.mayPublish(sequence = 6L, lastPublished = 7L))
        assertFalse(PublishCrop.mayPublish(sequence = 1L, lastPublished = 9L))
    }
}
