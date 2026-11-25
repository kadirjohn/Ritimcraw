package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionScrubSource tests (F2-C3 wiring): the ScrubFrameSource adapter
 * over the EditorPreviewSession seam — frame-count pass-through, the
 * render-call shape (index, the ATTACHED grade, the tier, DRAG class,
 * the chain wire), out-of-range degradation to null, and the
 * failure-to-null contract (one bad frame never cancels the walk). All
 * JVM through a fake session (the EncodedPreviewSessionTest pattern).
 */
class SessionScrubSourceTest {

    /** A recording fake of the seam: captures requests, serves pixels. */
    private class FakeSession(
        override val frames: Int,
        private val failFor: Set<Int> = emptySet(),
    ) : EditorPreviewSession {
        data class Asked(
            val frameIndex: Int,
            val grade: GradeK,
            val maxDim: Int,
            val renderClass: PreviewTiers.RenderClass,
            val chainWire: FloatArray?,
        )

        val asked = java.util.concurrent.CopyOnWriteArrayList<Asked>()
        var next = 0

        override suspend fun render(
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
            lutPath: String?,
            gyroPath: String?,
            renderClass: PreviewTiers.RenderClass,
            chainWire: FloatArray?,
        ): Result<PreviewFrame> {
            asked += Asked(frameIndex, grade, maxDim, renderClass, chainWire)
            return if (frameIndex in failFor) {
                Result.failure(IllegalStateException("no such frame"))
            } else {
                val v = next++
                // 4x2 frame whose every pixel encodes the index + slot.
                val argb = IntArray(8) { frameIndex * 100 + it * 10 + v }
                Result.success(PreviewFrame(4, 2, argb))
            }
        }

        override suspend fun renderCached(
            frameIndex: Int,
            grade: GradeK,
            maxDim: Int,
            lutPath: String?,
            gyroPath: String?,
            renderClass: PreviewTiers.RenderClass,
            chainWire: FloatArray?,
        ): Result<PreviewFrame> = render(frameIndex, grade, maxDim, lutPath, gyroPath, renderClass, chainWire)

        override suspend fun framePtsUs(index: Int): Long = index * 33_333L

        override suspend fun openAudio(fdProvider: () -> Int): ClipAudioSource? = null

        override fun close() {}
    }

    // ---- frame count pass-through ----

    @Test
    fun frame_count_is_the_sessions() {
        val src = SessionScrubSource(FakeSession(frames = 17), GradeK())
        assertEquals(17, src.frameCount)
    }

    // ---- the render-call shape ----

    @Test
    fun renders_through_the_seam_at_the_tier_with_the_grade() = runBlocking {
        val grade = GradeK(exposureEv = 1.5f)
        val wire = floatArrayOf(1f)
        val session = FakeSession(frames = 10)
        val src = SessionScrubSource(session, grade, lutPath = "a.cube", gyroPath = "g.csv", chainWire = wire)

        val px = src.frameArgb(3, ScrubFramesPolicy.TIER_MAX_DIM)

        assertNotNull(px)
        assertEquals(4, px!!.width)
        assertEquals(2, px.height)
        assertEquals(8, px.argb.size)
        // The rendered frame's OWN pixels ride the ScrubPixels (the
        // session already scaled to the tier).
        assertSame(session.asked[0].grade, grade)
        assertEquals(3, session.asked[0].frameIndex)
        assertEquals(ScrubFramesPolicy.TIER_MAX_DIM, session.asked[0].maxDim)
        // DRAG class: an interactive-path render — the temporal NR gate's
        // rule (never the merge for these).
        assertEquals(PreviewTiers.RenderClass.DRAG, session.asked[0].renderClass)
        assertSame(wire, session.asked[0].chainWire)
    }

    // ---- degradation: null, never a throw ----

    @Test
    fun out_of_range_index_falls_to_null() = runBlocking {
        // The filler's `idx in 0 until n` already guards, but the adapter
        // owns its own bound too (a defensive miss costs nothing; a crash
        // cancels the walk).
        val src = SessionScrubSource(FakeSession(frames = 10), GradeK())
        assertNull(src.frameArgb(-1, ScrubFramesPolicy.TIER_MAX_DIM))
        assertNull(src.frameArgb(10, ScrubFramesPolicy.TIER_MAX_DIM))
    }

    @Test
    fun a_failed_render_falls_to_null_not_a_throw() = runBlocking {
        // The filler's contract: `?: continue` — one bad frame must never
        // cancel the walk. A render failure lands as null.
        val src = SessionScrubSource(FakeSession(frames = 10, failFor = setOf(4)), GradeK())
        assertNull(src.frameArgb(4, ScrubFramesPolicy.TIER_MAX_DIM))
        // And the NEXT index still serves: the adapter carries no
        // latched failure state.
        assertNotNull(src.frameArgb(5, ScrubFramesPolicy.TIER_MAX_DIM))
    }

    // ---- the tier-mismatch guard: the key and the JPEG must agree ----

    @Test
    fun a_mismatched_tier_request_rends_at_the_policy_tier() = runBlocking {
        // The cache key carries tierMaxDim; a frame requested at another
        // tier would be STORED under a key the drag never reads — the
        // adapter pins every render to the policy tier.
        val session = FakeSession(frames = 10)
        val src = SessionScrubSource(session, GradeK())
        src.frameArgb(2, 999)
        assertEquals(ScrubFramesPolicy.TIER_MAX_DIM, session.asked[0].maxDim)
    }

    // ---- end-to-end with the facade: the walk fills through the adapter ----

    @Test
    fun the_walk_serves_a_hit_through_the_adapter() {
        // The facade + adapter pair, exercised exactly the way the editor
        // wiring drives them: onScrub retargets (a Miss up front — the
        // walk just started), the filler decodes through the adapter on
        // its own thread, and a bounded poll then observes the exact
        // hit the walk stores. Deterministic enough for a smoke of the
        // pair; the walk order itself is ScrubFrameCacheTest's own
        // pinned contract.
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-scrub").apply { isDaemon = true }
        }
        try {
            val src = SessionScrubSource(FakeSession(frames = 100), GradeK())
            val cache = ScrubFrameCache(maxBytes = 1024 * 1024)
            val scrub = ScrubFrames(cache, FakeCodec, ex.asCoroutineDispatcher())
            scrub.attach("clip", src)
            scrub.onScrub(50)
            var hit: ScrubResult.Cached? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (hit == null && System.currentTimeMillis() < deadline) {
                val r = scrub.onScrub(50)
                if (r is ScrubResult.Cached && r.frameIndex == 50) hit = r
                else Thread.sleep(20)
            }
            assertNotNull("the walk never served frame 50", hit)
            // The served pixels decoded from the cache's JPEG bytes (the
            // fake codec's decode shape), one walk-pass through the
            // adapter's session render.
            assertEquals(4, hit!!.pixels.width)
            assertEquals(2, hit.pixels.height)
            scrub.close()
        } finally {
            ex.shutdownNow()
        }
    }

    private fun SessionScrubSource.sessionAsked(index: Int): Boolean = true

    private object FakeCodec : ScrubCodec {
        // A tiny "JPEG" round trip for a FIXED 4x2 frame shape (the fake
        // session's frames): encode packs the first 8 pixels' low bytes,
        // decode reconstructs the 4x2 shape from them.
        override fun encode(argb: IntArray, width: Int, height: Int, quality: Int) =
            ByteArray(8) { argb[it % argb.size].toByte() }

        override fun decode(bytes: ByteArray): ScrubPixels? =
            ScrubPixels(IntArray(8) { i -> (bytes[i % bytes.size].toInt() and 0xFF) }, 4, 2)
    }
}
