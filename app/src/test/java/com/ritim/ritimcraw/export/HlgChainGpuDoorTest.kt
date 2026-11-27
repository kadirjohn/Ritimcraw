package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.RenderBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The G4 chain-to-GPU develop door ([ExportPipeline]'s initNodes +
 * setColorModelChain leg): a chain-carrying GPU export hands the chain
 * to the develop session BEFORE the first frame, and a session that
 * refuses the chain falls to the CPU develop with ONE honest line (the
 * export never dies on the seam). Every seam is a FAKE; the GPU's real
 * DRT-TAIL bake stays "doğrulanmadı" until the owner's morning run.
 */
class HlgChainGpuDoorTest {

    private class FakeSession : Mp4Session {
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val rendered = ArrayList<Int>()
        override fun open(fd: Int): Mp4SessionInfo? =
            Mp4SessionInfo(1L, 2, 4, 4, 0, 90)
        override fun renderFrame(
            handle: Long, frameIndex: Int, grade: GradeK,
            lutPath: String?, gyroPath: String?, chainWire: FloatArray?,
        ): ByteArray? {
            rendered.add(frameIndex)
            return ByteArray(48) { 128.toByte() }
        }
        override fun framePts(handle: Long, frameIndex: Int): Long = frameIndex * 33_333L
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray = ShortArray(0)
        override fun close(handle: Long) { closed.incrementAndGet() }
        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    private class Writer : Mp4MediaWriter {
        var started = false
        override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
            started = true
            File(outPath).writeBytes(ByteArray(64))
        }
        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
        override fun finish(): Boolean = true
        override fun release() {}
    }

    /** A GPU seam whose chain door the test controls. */
    private class FakeGpu(
        private val sessionOk: Boolean,
        private val chainOk: Boolean,
    ) : GpuDevelop {
        var initNodesCalls = 0
        var chainCalls = ArrayList<FloatArray?>()
        override fun probe(): DoubleArray? = doubleArrayOf(1.0, 3.0, 2.0, 1.0, 1.0, 4096.0)
        override fun fragmentParity(fd: Int): DoubleArray? = doubleArrayOf(1.0, 1.0, 398.0, 24_800_000.0)
        override fun openSession(): Long = if (sessionOk) 5L else 0L
        override fun closeSession(sessionHandle: Long) {}
        override fun renderFrameParcel(
            sessionHandle: Long, clipHandle: Long, frameIndex: Int, gradeParcel: FloatArray,
            lutPath: String?, lutStrength: Float, lutRole: Int, lutInGamut: Int, lutInTransfer: Int,
            lutOutGamut: Int, lutOutTransfer: Int, warpActive: Boolean, warpM: FloatArray,
        ): ByteArray? = ByteArray(4 * 4 * 3) { 64.toByte() }
        override fun renderFrameToEncoderParcel(
            sessionHandle: Long, clipHandle: Long, frameIndex: Int, ptsUs: Long, gradeParcel: FloatArray,
            lutPath: String?, lutStrength: Float, lutRole: Int, lutInGamut: Int, lutInTransfer: Int,
            lutOutGamut: Int, lutOutTransfer: Int, warpActive: Boolean, warpM: FloatArray,
            rotationDeg: Int,
        ): Boolean = false
        override fun lastDetail(): String? = "the fake session's own reason"
        override fun initNodes(sessionHandle: Long): IntArray? {
            initNodesCalls++
            return if (chainOk) intArrayOf(1, 64, 16) else intArrayOf(0, 0, 0)
        }
        override fun setColorModelChain(sessionHandle: Long, chainWire: FloatArray, drtUniforms: FloatArray?): Boolean {
            chainCalls.add(chainWire)
            return chainOk
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "test-chain").apply { isDaemon = true } }

    /** The editor's OpenDRT wire (outputMode 1, display 0), the fold's input. */
    private fun wire(): FloatArray {
        val grade = com.ritim.ritimcraw.data.grade2.ClipGradeV2().copy(
            output = com.ritim.ritimcraw.data.grade2.OutputTransformV2(
                mode = com.ritim.ritimcraw.data.grade2.OutputTransformV2.MODE_OPENDRT,
            ),
        )
        return com.ritim.ritimcraw.engine.NodeChainWire.encode(grade, emptyList())
    }

    @Test
    fun a_gpu_export_hands_the_chain_to_the_develop_session_before_frame_one() {
        val gpu = FakeGpu(sessionOk = true, chainOk = true)
        val session = FakeSession()
        val lines = ArrayList<String>()
        // No planRun (a pre-E1 job): the plan is legacy 8-bit, but the
        // chain still rides — the door is orthogonal to the plan.
        ExportPipeline(
            session, Writer(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
            chainWire = wire(),
        ).run(7, GradeK(), "/tmp/chain-door.partial", chainWire = wire())
        assertEquals(1, gpu.initNodesCalls)
        assertEquals(1, gpu.chainCalls.size)
        // The chain arrived with the wire the fold did NOT touch (this
        // plan is legacy: forceHlg comes from the plan, which is SDR
        // 8-bit — the SAME instance, the K2 lock).
        assertTrue(gpu.chainCalls.single()!!.contentEquals(wire()))
        // No fallback line: the door accepted the chain.
        assertTrue(lines.none { it.startsWith("develop fallback") || it.contains("chain fallback") })
    }

    @Test
    fun a_session_that_refuses_the_chain_falls_to_the_cpu_develop_with_one_line() {
        val gpu = FakeGpu(sessionOk = true, chainOk = false)
        val session = FakeSession()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, Writer(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
            chainWire = wire(),
        ).run(7, GradeK(), "/tmp/chain-refused.partial", chainWire = wire())
        // The CPU session rendered the frames (the honest fallback, the
        // export never dies on the seam).
        assertEquals(listOf(0, 1), session.rendered)
        // ONE honest fallback line names the reason (the developFallback
        // format: "render backend fallback: ... reason=...").
        val fallback = lines.filter { it.startsWith("render backend fallback") }
        assertEquals(lines.joinToString("|"), 1, fallback.size)
        assertTrue(fallback.single().contains("the fake session's own reason"))
    }

    @Test
    fun a_chainless_gpu_export_never_touches_the_chain_door() {
        val gpu = FakeGpu(sessionOk = true, chainOk = true)
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(), Writer(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
            chainWire = null,
        ).run(7, GradeK(), "/tmp/no-chain.partial")
        assertEquals(0, gpu.initNodesCalls)
        assertEquals(0, gpu.chainCalls.size)
    }
}
