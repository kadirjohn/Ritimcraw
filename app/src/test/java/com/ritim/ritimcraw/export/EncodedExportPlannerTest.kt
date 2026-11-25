package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.EncodedExportPlanner.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E0b-5: the pure path decision and the GPU memory budget of encoded export. */
class EncodedExportPlannerTest {

    private val heap256 = 256L * 1024 * 1024

    private fun inputs(
        w: Int, h: Int, bits: Int = 8, surface: Boolean = true, oneToOne: Boolean = true,
        chainRefused: Boolean = false, maxTex: Int? = 16384, gpuMem: Long? = null, recovery: Boolean = false,
    ) = EncodedExportPlanner.Inputs(
        width = w, height = h, bitDepth = bits, surfaceCapable = surface, geometryOneToOne = oneToOne,
        chainRefusedByGpu = chainRefused, maxTextureSize = maxTex, gpuMemoryLimitBytes = gpuMem,
        heapLimitBytes = heap256, highlightRecovery = recovery,
    )

    @Test
    fun budget_at_8k_matches_the_formula() {
        val px = 7680L * 4320L
        assertEquals(33_177_600L, px)
        val b = EncodedExportPlanner.gpuBudget(7680, 4320)
        assertEquals(4 * 16 * px, b.chainTextureBytes)              // 2,123,366,400: four RGBA32F textures
        assertEquals(530_841_600L, b.chainTextureBytes / 4)         // one RGBA32F texture is 506 MiB, not 132 MB
        assertEquals(px * 3 / 2, b.yuvPlaneBytes)                   // NV12: 49,766,400
        assertEquals(2 * px, b.cfaTextureBytes)                     // 66,355,200
        assertEquals(0L, b.recoveryPyramidBytes)
        assertEquals(2_239_488_000L, b.total)                       // 2.09 GiB
        assertEquals(16 * px, b.readbackBytes)                      // 530,841,600: deferred on the surface path
        assertEquals(b.total + b.readbackBytes, b.totalWithReadback)
    }

    @Test
    fun budget_grows_for_p010_and_for_highlight_recovery() {
        val eight = EncodedExportPlanner.gpuBudget(7680, 4320, 8)
        val ten = EncodedExportPlanner.gpuBudget(7680, 4320, 10)
        assertEquals(eight.yuvPlaneBytes * 2, ten.yuvPlaneBytes)
        val rec = EncodedExportPlanner.gpuBudget(7680, 4320, 8, highlightRecovery = true)
        // levels 1/4, 1/8, 1/32, 1/128, 1/512 of each edge, 6 layers, 16 bytes
        assertEquals(
            (1920L * 1080 + 960L * 540 + 240L * 135 + 60L * 34 + 15L * 9) * 6 * 16,
            rec.recoveryPyramidBytes,
        )
        assertTrue(rec.total > eight.total)
    }

    @Test
    fun budget_for_4k_is_a_quarter() {
        val b = EncodedExportPlanner.gpuBudget(3840, 2160)
        assertEquals(4L * 16 * 3840 * 2160, b.chainTextureBytes)
    }

    @Test
    fun eight_k_takes_the_encoder_surface_not_the_readback_or_the_cpu() {
        val d = EncodedExportPlanner.decide(inputs(7680, 4320))
        assertEquals(Path.SURFACE, d.path)
        assertNull(d.message)
    }

    @Test
    fun four_k_also_takes_the_surface_when_it_can() {
        assertEquals(Path.SURFACE, EncodedExportPlanner.decide(inputs(3840, 2160)).path)
        assertEquals(Path.SURFACE, EncodedExportPlanner.decide(inputs(3840, 2160, bits = 10)).path)
    }

    @Test
    fun scaled_or_cropped_4k_falls_to_the_gpu_readback_while_it_fits() {
        val d = EncodedExportPlanner.decide(inputs(3840, 2160, oneToOne = false))
        assertEquals(Path.GPU_READBACK, d.path) // 24.9 MB RGB888 <= 48 MB
    }

    @Test
    fun scaled_8k_has_no_cpu_path_and_gets_a_short_message_not_an_oom() {
        val d = EncodedExportPlanner.decide(inputs(7680, 4320, oneToOne = false))
        assertEquals(Path.REFUSED, d.path)
        val m = d.message!!
        assertTrue(m.contains("7680x4320"))
        assertTrue(m.length < 250)
        assertFalse(m.contains("OutOfMemory"))
    }

    @Test
    fun no_gpu_session_small_clip_uses_the_cpu_oracle_and_big_one_is_refused() {
        assertEquals(Path.CPU_ORACLE, EncodedExportPlanner.decide(inputs(1920, 1080, surface = false)).path)
        assertEquals(Path.CPU_ORACLE, EncodedExportPlanner.decide(inputs(3840, 2160, surface = false)).path)
        assertEquals(Path.REFUSED, EncodedExportPlanner.decide(inputs(7680, 4320, surface = false)).path)
    }

    @Test
    fun an_opendrt_chain_leaves_the_gpu_for_every_size() {
        assertEquals(Path.CPU_ORACLE, EncodedExportPlanner.decide(inputs(3840, 2160, chainRefused = true)).path)
        assertEquals(Path.REFUSED, EncodedExportPlanner.decide(inputs(7680, 4320, chainRefused = true)).path)
    }

    @Test
    fun a_texture_limit_below_the_clip_edge_refuses_the_gpu() {
        assertEquals(Path.REFUSED, EncodedExportPlanner.decide(inputs(7680, 4320, maxTex = 4096)).path)
        assertEquals(Path.SURFACE, EncodedExportPlanner.decide(inputs(7680, 4320, maxTex = 7680)).path)
        // unknown limit: the ES 3.1 working floor (8192) is assumed, 8K passes
        assertTrue(EncodedExportPlanner.fitsTexture(7680, 4320, null))
        assertFalse(EncodedExportPlanner.fitsTexture(8192 + 1, 100, null))
    }

    @Test
    fun a_known_gpu_memory_limit_below_the_budget_refuses_the_surface() {
        val tooSmall = 1024L * 1024 * 1024
        assertEquals(Path.REFUSED, EncodedExportPlanner.decide(inputs(7680, 4320, gpuMem = tooSmall)).path)
        assertEquals(Path.SURFACE, EncodedExportPlanner.decide(inputs(7680, 4320, gpuMem = 3L * 1024 * 1024 * 1024)).path)
        // 4K fits the same 1 GiB (560 MB)
        assertEquals(Path.SURFACE, EncodedExportPlanner.decide(inputs(3840, 2160, gpuMem = tooSmall)).path)
    }

    @Test
    fun cpu_oracle_sizes_match_the_allocations() {
        val px = 33_177_600L
        // planes (1.5 px) + ARGB IntArray (4 px) + RGB888 (3 px)
        assertEquals(px + px / 2 + 4 * px + 3 * px, EncodedExportPlanner.cpuOracleBytes(7680, 4320))
        assertTrue(EncodedExportPlanner.cpuOracleFits(3840, 2160, 8, heap256))
        assertTrue(EncodedExportPlanner.cpuOracleFits(3840, 2160, 10, heap256))
        assertFalse(EncodedExportPlanner.cpuOracleFits(7680, 4320, 8, heap256))
    }

    @Test
    fun cpu_fallback_refusal_is_null_when_it_fits_and_short_when_it_does_not() {
        assertNull(EncodedExportPlanner.cpuFallbackRefusal(1920, 1080, 8, heap256))
        val m = EncodedExportPlanner.cpuFallbackRefusal(7680, 4320, 8, heap256)
        assertNotNull(m)
        assertTrue(m!!.contains("7680x4320"))
        assertTrue(m.length < 250)
    }
}
