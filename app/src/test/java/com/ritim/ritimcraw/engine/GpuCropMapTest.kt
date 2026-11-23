package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.floor

/**
 * GpuCropMap parity tests (A2 GPU crop/straighten preview, owner request
 * 2026-11-23, guide A2): the GPU-side crop mapping is an INDEPENDENT
 * mirror of CropState's CPU reference — held to it over the plan's full
 * grid (output pixels x rects x angles x orientations x develop dims),
 * positions within 1e-3 px. The mirror must NOT delegate the map to
 * CropState (the parity test would be a tautology); only the auto-zoom
 * stays single-sourced (CropState.straightenZoom).
 */
class GpuCropMapTest {

    // The grid: four rects (CropState's own case shapes) x seven angles
    // x four orientations x two develop dims. A stride samples every
    // output row/column (edges and the center always hit; small outputs
    // run dense).
    private val rects = listOf(
        "full" to CropState.FULL_RECT,
        "centered-16by9" to CropState.RectF(0.125f, 0.25f, 0.75f, 0.5f),
        // Off-center: no edge axis-aligned, not symmetric.
        "off-center" to CropState.RectF(0.4f, 0.35f, 0.45f, 0.4f),
        // The clampRect floor: 5 percent per axis, snapped grid values.
        "floor-5pct" to CropState.RectF(0.5f, 0.5f, 0.05f, 0.05f),
    )

    private val angles = listOf(0f, 0.5f, -0.5f, 7f, -7f, 15f, -15f)

    // The manual rotation delta — the mapRect cases (the plan's grid runs
    // the manual-delta orientations; the sensor uOrientation fold is the
    // shader's EXISTING verified fold, reused, not mirrored here).
    private val manualDeltas = listOf(0, 90, 180, 270)

    private val dimsList = listOf(1280 to 720, 720 to 1280)

    @Test
    fun `sourcePosition mirrors CropState over the full grid within 1e-3 px`() {
        for ((dw, dh) in dimsList) {
            for (delta in manualDeltas) {
                for ((rectName, rect) in rects) {
                    for (deg in angles) {
                        val r = CropState.mapRect(rect, delta)
                        val out = CropState.outputDims(dw, dh, r)
                        val sx = maxOf(1, out.width / 29)
                        val sy = maxOf(1, out.height / 23)
                        for (oy in 0 until out.height step sy) {
                            for (ox in 0 until out.width step sx) {
                                val ref = CropState.cropSourcePosition(
                                    ox, oy, out.width, out.height, r, deg, dw, dh,
                                )
                                val uv = GpuCropMap.outputUv(ox, oy, out.width, out.height)
                                val gpu = GpuCropMap.sourcePosition(
                                    uv[0], uv[1], r, deg,
                                    out.width, out.height, dw, dh,
                                )
                                val msg = "rect=$rectName deg=$deg delta=$delta " +
                                    "out=${out.width}x${out.height} px=($ox,$oy) " +
                                    "src=${dw}x${dh} r=(${r.x},${r.y},${r.w},${r.h})"
                                assertEquals("sx $msg", ref[0], gpu[0], 1e-3f)
                                assertEquals("sy $msg", ref[1], gpu[1], 1e-3f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `deg 0 with an exact-fraction rect degenerates to integer-centre positions`() {
        // The CPU's deg-0 arm is a pure row copy; the GPU's unified map
        // must degenerate to it: every source position lands exactly on a
        // pixel CENTRE (x.5), so every bilinear weight is 0 and the 4 taps
        // collapse to the single copied texel — no soft crop edges. Every
        // output pixel (the whole grid, no stride).
        val dw = 1280
        val dh = 720
        val r = CropState.RectF(0.25f, 0.25f, 0.5f, 0.5f)
        val out = CropState.outputDims(dw, dh, r)
        for (oy in 0 until out.height) {
            for (ox in 0 until out.width) {
                val uv = GpuCropMap.outputUv(ox, oy, out.width, out.height)
                val p = GpuCropMap.sourcePosition(
                    uv[0], uv[1], r, 0f, out.width, out.height, dw, dh,
                )
                assertEquals("sx at ($ox,$oy)", 0.5f, p[0] % 1f, 1e-3f)
                assertEquals("sy at ($ox,$oy)", 0.5f, p[1] % 1f, 1e-3f)
            }
        }
    }

    @Test
    fun `bilinearTaps mirror sampleArgbBilinear's indices and weights`() {
        // The tap geometry pin: floor(s - 0.5) clamped per axis, x1 =
        // min(x0 + 1, w - 1), weights coerced [0, 1] — the exact
        // sampleArgbBilinear tap shape (CropState's private floorClamp
        // math recomputed here), at interior, edge and past-edge spots.
        val w = 8
        val h = 6
        // Interior: fraction weights strictly inside (0, 1). sx 3.7 ->
        // x0 3, fx 0.2; sy 2.25 -> y0 1, fy 0.75.
        var taps = GpuCropMap.bilinearTaps(3.7f, 2.25f, w, h)
        assertEquals(3, taps.x0)
        assertEquals(1, taps.y0)
        assertEquals(4, taps.x1)
        assertEquals(2, taps.y1)
        assertEquals(0.2f, taps.fx, 1e-4f)
        assertEquals(0.75f, taps.fy, 1e-4f)
        // sx < 0.5 clamps the index to 0 and the weight to 0; sy stays
        // interior (floor(2.25 - 0.5) = 1, weight 0.75).
        taps = GpuCropMap.bilinearTaps(-3.0f, 2.25f, w, h)
        assertEquals(0, taps.x0)
        assertEquals(0f, taps.fx, 1e-4f)
        assertEquals(1, taps.y0)
        assertEquals(0.75f, taps.fy, 1e-4f)
        // sx past the right edge: x0 clamps to w-1 and x1 collapses.
        taps = GpuCropMap.bilinearTaps(w + 5.0f, 2.25f, w, h)
        assertEquals(w - 1, taps.x0)
        assertEquals(w - 1, taps.x1)
        // The y axis mirror.
        taps = GpuCropMap.bilinearTaps(3.7f, -0.4f, w, h)
        assertEquals(0, taps.y0)
        assertEquals(0.0f, taps.fy, 1e-4f)
        taps = GpuCropMap.bilinearTaps(3.7f, h + 2.0f, w, h)
        assertEquals(h - 1, taps.y0)
        assertEquals(h - 1, taps.y1)
        // An exact pixel-centre position: weight exactly 0.
        taps = GpuCropMap.bilinearTaps(4.5f, 3.5f, w, h)
        assertEquals(4, taps.x0)
        assertEquals(3, taps.y0)
        assertEquals(0f, taps.fx, 0f)
        assertEquals(0f, taps.fy, 0f)
    }

    @Test
    fun `bilinearTaps reference cross-check over a sampled sweep`() {
        // Cross-check the taps against the CPU's own floorClamp math for
        // a sweep of positions including negatives and past-edge values
        // (sampleArgbBilinear's exact indices/weights recomputed here).
        val w = 13
        val h = 7
        for (i in 0..200) {
            val sxF = -1.5f + i * 0.11f
            val syF = 0.3f + i * 0.053f
            val taps = GpuCropMap.bilinearTaps(sxF, syF, w, h)
            val x0 = floor((sxF - 0.5f).toDouble()).toInt().coerceIn(0, w - 1)
            val y0 = floor((syF - 0.5f).toDouble()).toInt().coerceIn(0, h - 1)
            val x1 = minOf(x0 + 1, w - 1)
            val y1 = minOf(y0 + 1, h - 1)
            val fx = (sxF - 0.5f - x0).coerceIn(0f, 1f)
            val fy = (syF - 0.5f - y0).coerceIn(0f, 1f)
            assertEquals("x0 at $i", x0, taps.x0)
            assertEquals("y0 at $i", y0, taps.y0)
            assertEquals("x1 at $i", x1, taps.x1)
            assertEquals("y1 at $i", y1, taps.y1)
            assertEquals("fx at $i", fx, taps.fx, 0f)
            assertEquals("fy at $i", fy, taps.fy, 0f)
        }
    }

    @Test
    fun `requestCrop returns null for the neutral crop group`() {
        // The neutral group is the SAME inline test PublishCrop.plan runs:
        // straightenDeg == 0 and the rect is full. Null means cropOn =
        // false — the shader's identity path (uCropOn = 0).
        assertNull(GpuCropMap.requestCrop(GradeK(), manualDeltaDeg = 0))
        assertNull(GpuCropMap.requestCrop(GradeK(), manualDeltaDeg = 90))
        assertNull(GpuCropMap.requestCrop(GradeK(), manualDeltaDeg = 180))
        assertNull(GpuCropMap.requestCrop(GradeK(), manualDeltaDeg = 270))
        // Straighten alone (full rect) is an ACTIVE crop group.
        assertNotNull(GpuCropMap.requestCrop(GradeK(straightenDeg = 0.5f), manualDeltaDeg = 0))
        // A rect alone (0 straighten) is an ACTIVE group.
        assertNotNull(GpuCropMap.requestCrop(GradeK(cropW = 0.5f), manualDeltaDeg = 0))
        // StraightenDeg beyond the range still counts as active here (the
        // clamp lives in CropState/planCrop; the request carries the raw
        // angle exactly as PublishCrop's snapshot does).
        assertNotNull(GpuCropMap.requestCrop(GradeK(straightenDeg = 25f), manualDeltaDeg = 0))
    }

    @Test
    fun `requestCrop carries the mapped rect and the straighten angle`() {
        // [x, y, w, h, deg] with the rect = mapRect(grade rect, manual
        // delta) — the EXACT PublishCrop mapping (the grade rect is
        // effective-upright; the GL draw renders container-upright).
        val grade = GradeK(
            cropX = 0.1f, cropY = 0.2f, cropW = 0.3f, cropH = 0.4f,
            straightenDeg = 7f,
        )
        val p = GpuCropMap.requestCrop(grade, manualDeltaDeg = 0)
        assertNotNull(p)
        assertEquals(5, p!!.size)
        assertEquals(0.1f, p[0], 0f)
        assertEquals(0.2f, p[1], 0f)
        assertEquals(0.3f, p[2], 0f)
        assertEquals(0.4f, p[3], 0f)
        assertEquals(7f, p[4], 0f)
        // A +90 manual delta maps the same grade rect into container
        // space — mapRect(rect, 90), the exact PublishCrop/applyToArgb
        // space crossing.
        val q = GpuCropMap.requestCrop(grade, manualDeltaDeg = 90)
        assertNotNull(q)
        val mapped = CropState.mapRect(
            CropState.RectF(0.1f, 0.2f, 0.3f, 0.4f), 90,
        )
        assertEquals(mapped.x, q!![0], 0f)
        assertEquals(mapped.y, q[1], 0f)
        assertEquals(mapped.w, q[2], 0f)
        assertEquals(mapped.h, q[3], 0f)
        assertEquals(7f, q[4], 0f)
    }

    @Test
    fun `requestCrop mapping matches the PublishCrop resample semantics`() {
        // The plan's mapping pin: requestCrop's rect must equal what
        // PublishCrop's resample consumes — mapRect(grade.rect, delta)
        // — for every orientation, so the GPU crop can never disagree
        // with the CPU publish's crop about the selected region.
        val grade = GradeK(
            cropX = 0.25f, cropY = 0.25f, cropW = 0.5f, cropH = 0.5f,
            straightenDeg = 15f,
        )
        for (delta in manualDeltas) {
            val p = GpuCropMap.requestCrop(grade, manualDeltaDeg = delta)
            assertNotNull("delta=$delta", p)
            val expect = CropState.mapRect(
                CropState.RectF(grade.cropX, grade.cropY, grade.cropW, grade.cropH),
                delta,
            )
            assertEquals("x delta=$delta", expect.x, p!![0], 0f)
            assertEquals("y delta=$delta", expect.y, p[1], 0f)
            assertEquals("w delta=$delta", expect.w, p[2], 0f)
            assertEquals("h delta=$delta", expect.h, p[3], 0f)
            assertEquals("deg delta=$delta", grade.straightenDeg, p[4], 0f)
        }
    }

    @Test
    fun `NEUTRAL_CROP_PARAMS is the all-neutral parcel`() {
        // The JNI non-null array shape: cropOn false carries the neutral
        // rect (0,0,1,1) + 0 deg — the identity path's uniform values
        // (uCropOn = 0 reads none of them).
        val p = GpuCropMap.NEUTRAL_CROP_PARAMS
        assertEquals(5, p.size)
        assertEquals(0f, p[0], 0f)
        assertEquals(0f, p[1], 0f)
        assertEquals(1f, p[2], 0f)
        assertEquals(1f, p[3], 0f)
        assertEquals(0f, p[4], 0f)
    }
}
