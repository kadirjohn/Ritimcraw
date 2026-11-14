package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.export.Mp4Session
import com.ritim.ritimcraw.export.Mp4SessionInfo
import com.ritim.ritimcraw.export.PngWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ClipThumbnails MCRAW-path tests (owner request 2026-11-14): the
 * full-res develop through the photo export's session seam, the light
 * balance's second develop, the transient JPEG master (deleted after
 * the icon lands), the icon persisted at the SAME <id>.png name, and the
 * recorded stats from the open. All native/platform pieces are fakes —
 * the JVM never touches the framework (no returnDefaultValues).
 */
class ClipThumbnailsMcrawTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- fakes ------------------------------------------------------------

    /**
     * The fake Mp4Session: frame 0 develops to a mid-gray frame by
     * default; a [crowdedHigh] frame has blown-out pixels (the balance
     * must fire), a [crowdedLow] frame has crushed ones; the recorded
     * stats come from the constructor. Open (and only open) can be
     * overridden per-test through [openResult].
     */
    private open class FakeMp4Session(
        val width: Int = 4,
        val height: Int = 4,
        val frameCount: Int = 10,
        val orientationDeg: Int = 0,
        val crowdedHigh: Boolean = false,
        val crowdedLow: Boolean = false,
    ) : Mp4Session {
        var opened = 0
        var closed = 0
        var renders = 0
        var sawGrades = mutableListOf<GradeK>()
        var failRender = false

        /** When true, [open] reports a failed open (null info). */
        var failOpen = false

        override fun open(fd: Int): Mp4SessionInfo? {
            opened++
            if (failOpen) return null
            return Mp4SessionInfo(1L, frameCount, width, height, 0, orientationDeg)
        }

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?, gyroPath: String?): ByteArray? {
            if (failRender) return null
            renders++
            sawGrades.add(grade)
            val pixels = width * height
            return ByteArray(pixels * 3) { i ->
                // Whole pixels are white/black (luma 255/0) so the
                // balance's crowd fractions actually fire.
                val px = i / 3
                when {
                    crowdedHigh && px % 10 == 0 -> 255.toByte()
                    crowdedLow && px % 10 == 1 -> 0
                    else -> 128.toByte()
                }
            }
        }

        override fun framePts(handle: Long, frameIndex: Int): Long = 1_000_000L * frameIndex
        override fun lastFrameSubStagesMs(): DoubleArray? = null
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray? = null
        override fun close(handle: Long) { closed++ }
        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    /** Records the write args; succeeds/fails on demand. */
    private class FakeJpegWriter : JpegWriter {
        var writes = 0
        var fail = false
        var lastPath: String? = null
        var lastW = 0
        var lastH = 0
        override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
            if (fail) return false
            writes++
            lastPath = outPath
            lastW = width
            lastH = height
            File(outPath).writeText("jpeg")
            return true
        }
    }

    /** Extracts the icon by copying the master; records the args. */
    private class FakeIconExtractor : IconExtractor {
        var extracts = 0
        var fail = false
        var lastMaster: String? = null
        var lastOut: String? = null
        var lastTarget = 0
        override fun extract(masterPath: String, outPath: String, targetDim: Int): Boolean {
            if (fail) return false
            extracts++
            lastMaster = masterPath
            lastOut = outPath
            lastTarget = targetDim
            File(outPath).writeText("icon")
            return true
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "test-mcraw").apply { isDaemon = true } }

    private fun mcrawSource(id: String = "clip1"): MediaSource =
        MediaSource(id = id, uri = "content://x/$id", fingerprint = "f-$id", kind = SourceKind.MCRAW, access = Access.OK)

    private fun store(): ThumbnailStore = ThumbnailStore(tmp.newFolder("thumbs"))

    private fun pass(
        session: FakeMp4Session = FakeMp4Session(),
        jpeg: FakeJpegWriter = FakeJpegWriter(),
        iconX: FakeIconExtractor = FakeIconExtractor(),
        store: ThumbnailStore = store(),
    ): ClipThumbnails = ClipThumbnails(
        store = store,
        pngWriter = pngStub,
        engine = freshEngine(),
        mp4Session = session,
        exportEngine = freshEngine(),
        jpegWriter = jpeg,
        iconExtractor = iconX,
    )

    /** The ENCODED path's writer stub — not exercised by these tests. */
    private val pngStub = object : PngWriter {
        override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean = true
    }

    private var stats: Array<Any?>? = null

    private fun assertStats(w: Int, h: Int, frames: Int, durationUs: Long) {
        assertNotNull(stats)
        assertEquals(w, stats!![0])
        assertEquals(h, stats!![1])
        assertEquals(frames, stats!![2])
        assertEquals(durationUs, stats!![3])
    }

    private fun clip(
        media: MediaSource = mcrawSource(),
        fdProvider: () -> Int = { 42 },
        onStats: (Int, Int, Int, Long) -> Unit = { w, h, f, d -> stats = arrayOf(w, h, f, d) },
    ): ClipThumbnails.PendingClip = ClipThumbnails.PendingClip(
        media = media,
        path = SourcePath.MCRAW,
        fdProvider = fdProvider,
        onStats = onStats,
    )

    // --- the happy path ----------------------------------------------------

    @Test
    fun balanced_frame_single_develop_master_deleted_icon_persists() = runBlocking {
        val session = FakeMp4Session(frameCount = 10)
        val jpeg = FakeJpegWriter()
        val iconX = FakeIconExtractor()
        val st = store()
        pass(session, jpeg, iconX, st).generate(clip())

        // The full-res develop ran once with the NEUTRAL grade (balanced
        // frame: the identity — no second develop).
        assertEquals(1, session.renders)
        assertEquals(1, session.sawGrades.size)
        assertEquals(0f, session.sawGrades[0].highlights, 0f)
        // The session opened and closed exactly once.
        assertEquals(1, session.opened)
        assertEquals(1, session.closed)
        // The JPEG master was written from the developed frame...
        assertEquals(1, jpeg.writes)
        assertEquals(st.masterPath("clip1").absolutePath, jpeg.lastPath)
        // ...the icon was extracted from the master to the <id>.png
        // name (the SAME name the encoded path and every earlier build
        // use — existing icons never re-develop)...
        assertEquals(1, iconX.extracts)
        assertEquals(st.masterPath("clip1").absolutePath, iconX.lastMaster)
        assertEquals(st.targetPath("clip1"), iconX.lastOut)
        // 256px long edge (owner finding 2026-11-14: the old 128px icon
        // was upscaled at ~420dpi display and looked soft).
        assertEquals(256, iconX.lastTarget)
        // ...and the master is DELETED after extraction.
        assertTrue(st.has("clip1"))
        assertFalse(st.masterPath("clip1").exists())
        // The recorded stats landed from the open (10 frames, 0..9 s).
        assertStats(4, 4, 10, 9_000_000L)
    }

    @Test
    fun crowded_high_frame_gets_the_second_balanced_develop() = runBlocking {
        val session = FakeMp4Session(crowdedHigh = true)
        val jpeg = FakeJpegWriter()
        pass(session, jpeg, FakeIconExtractor(), store()).generate(clip())

        // Two develops: the neutral pass, then the balanced re-develop
        // whose highlights is the bounded recovery (negative, within
        // [-25, -10]) — the existing stage-15 math, no new tone curve.
        assertEquals(2, session.renders)
        assertEquals(2, session.sawGrades.size)
        val balanced = session.sawGrades[1]
        assertTrue(balanced.highlights < 0f && balanced.highlights >= -25f)
        assertTrue(balanced.highlights <= -10f)
        assertEquals(0f, balanced.shadows, 0f)
        // The master came from the SECOND (balanced) frame.
        assertEquals(1, jpeg.writes)
    }

    @Test
    fun crowded_low_frame_gets_the_second_balanced_develop() = runBlocking {
        val session = FakeMp4Session(crowdedLow = true)
        pass(session, FakeJpegWriter(), FakeIconExtractor(), store()).generate(clip())

        assertEquals(2, session.renders)
        val balanced = session.sawGrades[1]
        assertTrue(balanced.shadows > 0f && balanced.shadows <= 25f)
        assertTrue(balanced.shadows >= 10f)
        assertEquals(0f, balanced.highlights, 0f)
    }

    // --- stats -------------------------------------------------------------

    @Test
    fun single_frame_clip_hides_its_duration() = runBlocking {
        val session = FakeMp4Session(frameCount = 1)
        pass(session, FakeJpegWriter(), FakeIconExtractor(), store()).generate(clip())
        // frameCount 1: last pts is -1 (never faked), duration 0.
        assertStats(4, 4, 1, 0L)
    }

    @Test
    fun failed_open_still_closes_nothing_and_reports_no_stats() = runBlocking {
        val session = FakeMp4Session().apply { failOpen = true }
        val st = store()
        pass(session, FakeJpegWriter(), FakeIconExtractor(), st).generate(clip())

        // No open handle, no render, no stats callback (the caller keeps
        // its old record), no icon — the next Library open retries.
        assertEquals(1, session.opened)
        assertEquals(0, session.renders)
        assertEquals(0, session.closed)
        assertNull(stats)
        assertFalse(st.has("clip1"))
    }

    @Test
    fun failed_render_still_reports_stats_and_master_never_written() = runBlocking {
        val session = FakeMp4Session(frameCount = 10).apply { failRender = true }
        val jpeg = FakeJpegWriter()
        val st = store()
        pass(session, jpeg, FakeIconExtractor(), st).generate(clip())

        // The open succeeded, so the recorded stats still land (a
        // half-probed clip shows its resolution); the thumbnail retries.
        assertStats(4, 4, 10, 9_000_000L)
        assertEquals(0, jpeg.writes)
        assertFalse(st.has("clip1"))
        assertEquals(1, session.closed)
    }

    @Test
    fun failed_icon_extract_deletes_the_master() = runBlocking {
        val jpeg = FakeJpegWriter()
        val iconX = FakeIconExtractor().apply { fail = true }
        val st = store()
        pass(FakeMp4Session(), jpeg, iconX, st).generate(clip())

        // The icon never landed (no icon this pass — the row shows the
        // placeholder, the next Library open retries), and the master
        // is STILL deleted: it never persists, success or fail.
        assertFalse(st.has("clip1"))
        assertFalse(st.masterPath("clip1").exists())
    }

    @Test
    fun failed_jpeg_write_leaves_no_icon_and_no_master() = runBlocking {
        val jpeg = FakeJpegWriter().apply { fail = true }
        val iconX = FakeIconExtractor()
        val st = store()
        pass(FakeMp4Session(), jpeg, iconX, st).generate(clip())

        // No master write -> no extraction; the master never persists.
        assertEquals(0, iconX.extracts)
        assertFalse(st.masterPath("clip1").exists())
        assertFalse(st.has("clip1"))
    }

    // --- the re-scan gate ---------------------------------------------------

    @Test
    fun existing_icon_is_never_redeveloped() = runBlocking {
        val session = FakeMp4Session()
        val st = store()
        st.ensureDir()
        File(st.targetPath("clip1")).writeText("existing icon")
        pass(session, FakeJpegWriter(), FakeIconExtractor(), st).generate(clip())

        // The gate: a clip with a persisted file is never re-developed
        // (decode ONCE, persist, never redevelop per composition).
        assertEquals(0, session.opened)
        assertEquals(0, session.renders)
        assertNull(stats)
        // The existing icon is untouched.
        assertEquals("existing icon", File(st.targetPath("clip1")).readText())
    }

    @Test
    fun access_lost_clip_is_never_probed() = runBlocking {
        val session = FakeMp4Session()
        val st = store()
        val lost = mcrawSource().copy(access = Access.LOST)
        pass(session, FakeJpegWriter(), FakeIconExtractor(), st).generate(clip(media = lost))
        assertEquals(0, session.opened)
    }

    // --- fd discipline -------------------------------------------------------

    @Test
    fun fd_provider_failure_degrades_to_no_thumbnail() = runBlocking {
        val session = FakeMp4Session()
        val st = store()
        pass(session, FakeJpegWriter(), FakeIconExtractor(), st).generate(
            clip(fdProvider = { -1 })
        )
        // No fd: no open attempt at all, no icon, no stats.
        assertEquals(0, session.opened)
        assertNull(stats)
        assertFalse(st.has("clip1"))
    }

    // --- stale master cleanup ------------------------------------------------

    @Test
    fun stale_master_from_a_crashed_pass_is_gone_after_remove() {
        val st = store()
        st.ensureDir()
        // A crash between master write and icon extraction left this.
        File(st.masterPath("clip1").absolutePath).writeText("stale master")
        st.remove("clip1")
        assertFalse(st.masterPath("clip1").exists())
    }

    @Test
    fun remove_deletes_both_icon_and_master() {
        val st = store()
        st.ensureDir()
        File(st.targetPath("clip1")).writeText("icon")
        File(st.masterPath("clip1").absolutePath).writeText("master")
        st.remove("clip1")
        assertFalse(st.has("clip1"))
        assertFalse(st.masterPath("clip1").exists())
    }

    @Test
    fun rotation_90_writes_upright_master_dims() = runBlocking {
        val session = FakeMp4Session(width = 8, height = 4, orientationDeg = 90)
        val jpeg = FakeJpegWriter()
        pass(session, jpeg, FakeIconExtractor(), store()).generate(clip())

        // The JPEG pixels are upright: 90deg swaps the dims (a JPEG
        // carries no orientation hint, so the pixels must be).
        assertEquals(4, jpeg.lastW)
        assertEquals(8, jpeg.lastH)
        // The recorded stats stay AS RECORDED (the open's dims).
        assertStats(8, 4, 10, 9_000_000L)
    }
}
