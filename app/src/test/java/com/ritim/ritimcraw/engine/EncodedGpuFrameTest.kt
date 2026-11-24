package com.ritim.ritimcraw.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** E0b-4b: [EncodedYuv.toGpuFrame], the repack-free plane hand-off to the GPU door. */
class EncodedGpuFrameTest {

    // 4x4 NV12: Y rows padded to stride 6, chroma 2x2 samples (Cb,Cr) padded to stride 6. The adapter copies
    // the codec's U plane (Cb0 Cr0 Cb1 .. ends at the last Cb) and V plane (Cr0 Cb1 .. ends at the last Cr).
    private fun nv12(): YuvPlanes {
        val y = ByteArray(6 * 3 + 4) { (it + 1).toByte() }
        val chroma = byteArrayOf(10, 11, 12, 13, 0, 0, 20, 21, 22, 23, 0, 0) // rows: Cb0 Cr0 Cb1 Cr1 pad pad
        val u = chroma.copyOfRange(0, chroma.size - 1 - 2 - 0).let { chroma.copyOfRange(0, 9) } // ends at Cb1 of row 1
        val v = chroma.copyOfRange(1, 10)                                                        // ends at Cr1 of row 1
        return YuvPlanes(
            ptsUs = 0, width = 4, height = 4,
            y = YuvPlane(y, 0, 6, 1), u = YuvPlane(u, 0, 6, 2), v = YuvPlane(v, 0, 6, 2),
        )
    }

    @Test
    fun nv12_chroma_is_rebuilt_interleaved_with_the_last_cr() {
        val f = assertNotNull(EncodedYuv.toGpuFrame(nv12())).let { EncodedYuv.toGpuFrame(nv12())!! }
        assertEquals(6, f.stride)
        assertEquals(6, f.uvStride)
        assertEquals(8, f.bitDepth)
        // 2 chroma rows: (2-1)*6 + 2*2*1 = 10 bytes, the last byte being the V plane's final Cr.
        assertEquals(10, f.u.size)
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 0, 0, 20, 21, 22, 23), f.u)
        // Luma is the decoder's own array, used in place.
        assertEquals(nv12().y.buffer.size, f.y.size)
    }

    @Test
    fun layouts_the_gpu_door_cannot_take_return_null() {
        val p = nv12()
        // Planar I420 (chroma pixelStride 1) is not NV12-shaped.
        val planar = YuvPlanes(0, 4, 4, p.y, YuvPlane(p.u.buffer, 0, 3, 1), YuvPlane(p.v.buffer, 0, 3, 1))
        assertNull(EncodedYuv.toGpuFrame(planar))
        // NV21 (V first): the V plane is not the U plane shifted by one component.
        val nv21 = YuvPlanes(0, 4, 4, p.y, p.v, p.u)
        assertNull(EncodedYuv.toGpuFrame(nv21))
        // A short luma plane is refused instead of read past its end.
        val short = YuvPlanes(0, 4, 4, YuvPlane(ByteArray(10), 0, 6, 1), p.u, p.v)
        assertNull(EncodedYuv.toGpuFrame(short))
    }
}
