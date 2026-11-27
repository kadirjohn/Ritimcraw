package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FrameRateMap tests — plan E1F §6's case list, every expected value
 * derived from the plan's own formulas and hand-checked constants:
 *  1. identity 30->30: i(j)=j, N frames, the exact 30-grid pts
 *  2. 23.976->30: ratio 0.7992, ~20 % duplicates, first frame 0
 *  3. 30->24: drops, monotone, no source frame shown twice
 *  4. 29.83->30 the real measured case: uniform 33523 us pts + one
 *     67046 us gap; the count rule from the span; the uniform model
 *     stays within 1 frame of the nearest-REAL-frame reference (§4.3)
 *  5. single frame (span=0): 1 output frame (the ceil(0)=0 rejection)
 *  6. two frames, short span: floor+1 keeps the last grid point inside
 *  7. trailing fraction: floor+1 vs ceil at the boundaries (§4.2)
 *  8. rational grid monotonicity: 29.97 x 3000 frames, |err| <= 0.5 us
 *  9. timeline parity: TimelineExportPlan.build's sourceFrames == the
 *     map's, on the timeline's own (sourceFps, projectFps) pairs
 * 10. double-rounding collision: the integer-t_j path LOSES to the
 *     linear path the map implements (§4.5)
 */
class FrameRateMapTest {

    // ---- 1. identity 30 -> 30 ----

    @Test
    fun identity_30_to_30_maps_every_frame_once() {
        // 90 source frames, span = 89 exact periods of 1/30 s.
        val span = Math.round(89 * 1_000_000.0 / 30.0)
        val m = FrameRateMap.map(TargetFps.FPS_30, 30.0, 90, span)
        assertEquals(90, m.outputFrames) // floor(span*30/1e6)+1 = 89+1
        assertEquals((0 until 90).toList(), m.sourceFrames)
        assertEquals(0, m.drops)
        assertEquals(0, m.duplicates)
        // pts walk the exact 30-grid from 0.
        assertEquals(0L, m.ptsUs[0])
        assertEquals(33_333L, m.ptsUs[1])
        assertEquals(2_966_667L, m.ptsUs[89])
    }

    // ---- 2. 23.976 -> 30: duplicates ----

    @Test
    fun fps_23_976_to_30_duplicates_about_20_percent() {
        // 240 source frames at 23.976 (the 2997/125 double the timeline
        // test itself uses); span = 239 exact periods.
        val rate = 23.976
        val span = Math.round(239 * 1_000_000.0 / 30.0)
        val m = FrameRateMap.map(TargetFps.FPS_30, rate, 240, span)
        // floor(7_966_667*30/1e6)+1 = 240 grid points over ~239.5 source
        // periods: the 0.7992 ratio duplicates ~20 % of the outputs.
        assertEquals(240, m.outputFrames)
        // Every output frame maps within half a source frame of the
        // real-time position — nearest, monotone.
        var prev = -1
        for (j in m.sourceFrames.indices) {
            // Output j sits at j/30 s; the real-time source position
            // there is t * sourceFps = j * rate / 30 — within half a
            // source frame of the chosen frame (nearest, never a jump).
            val exact = j * rate / 30.0
            assertTrue("j=$j mapped ${m.sourceFrames[j]} vs exact $exact", abs(m.sourceFrames[j] - exact) <= 0.5 + 1e-9)
            assertTrue(m.sourceFrames[j] >= prev)
            prev = m.sourceFrames[j]
        }
        assertEquals(0, m.sourceFrames[0]) // the opening never duplicates
        // A 23.976 source that HOLDS 192 real frames over the same span
        // (the real-clip shape: the frame count covers the span): every
        // source is shown at least once — a denser output NEVER drops.
        val mFull = FrameRateMap.map(TargetFps.FPS_30, rate, 192, span)
        assertEquals(240, mFull.outputFrames)
        assertEquals(0, mFull.drops)
        assertTrue("dups ${mFull.duplicates}", mFull.duplicates in 44..50)
        assertEquals(191, mFull.sourceFrames.max()) // tail clamps to the last real frame
    }

    // ---- 3. 30 -> 24: drops ----

    @Test
    fun fps_30_to_24_drops_a_fifth() {
        // 150 source frames, span = 149 exact periods of 1/30 s.
        val span = Math.round(149 * 1_000_000.0 / 30.0)
        val m = FrameRateMap.map(TargetFps.FPS_24, 30.0, 150, span)
        // floor(4_966_667*24/1e6)+1 = floor(119.2)+1 = 120 output frames.
        assertEquals(120, m.outputFrames)
        // Monotone; no source frame repeats (target < source).
        val seen = HashSet<Int>()
        var prev = -1
        for (f in m.sourceFrames) {
            assertTrue("frame $f repeated", seen.add(f))
            assertTrue(f >= prev); prev = f
        }
        // ~20 % drop: 150 sources, 120 shown — every 5th source skipped
        // (the 1.25 ratio's pattern).
        assertEquals(30, m.drops)
        assertEquals(0, m.duplicates)
    }

    // ---- 4. the real 29.83 case, one 67 ms gap (plan §4.3/§4.6) ----

    @Test
    fun fps_29_83_to_30_stays_within_one_frame_of_the_real_nearest() {
        // Uniform 33523 us (the owner's measured median), the single
        // 67046 us gap after frame 60 — the real clip's shape.
        val pts = LongArray(120) { i ->
            val base = i * 33_523L
            base + if (i > 60) 33_523L else 0L // one doubled interval
        }
        val span = pts.last() - pts.first()
        val rate = (pts.size - 1) * 1_000_000.0 / span // measured, gap included
        val m = FrameRateMap.map(TargetFps.FPS_30, rate, 120, span)
        // The count rule from the span, not the source count.
        assertEquals(Math.floor(span * 30.0 / 1_000_000.0).toInt() + 1, m.outputFrames)
        // Monotone; the tail never leaves the source's real bounds.
        var prev = -1
        for (f in m.sourceFrames) {
            assertTrue(f >= prev); prev = f
            assertTrue("frame $f past the bounds", f in 0..119)
        }
        assertEquals(0L, m.ptsUs[0])
        assertEquals(33_333L, m.ptsUs[1]) // the output grid is the exact 30-grid
        // Plan §4.3's error bound: the uniform model's pick stays within
        // 1 frame of the nearest-REAL-frame reference (model B, argmin
        // over the clip's real pts) everywhere — the gap's whole cost.
        for (j in m.sourceFrames.indices) {
            val t = (pts.first() + m.ptsUs[j]).toDouble()
            val argmin = pts.indices.minByOrNull { abs(pts[it] - t) }!!
            assertTrue(
                "j=$j uniform ${m.sourceFrames[j]} vs argmin $argmin",
                abs(m.sourceFrames[j] - argmin) <= 1,
            )
        }
    }

    // ---- 5. single frame (span = 0): the ceil rejection ----

    @Test
    fun a_single_frame_clip_maps_to_one_frame() {
        // span=0: floor(0)+1 = 1 output frame (ceil(0) = 0 would DROP the
        // only frame — the §4.2 correction pinned).
        val m = FrameRateMap.map(TargetFps.FPS_60, 29.83, 1, 0L)
        assertEquals(1, m.outputFrames)
        assertEquals(listOf(0), m.sourceFrames)
        assertEquals(listOf(0L), m.ptsUs)
        assertEquals(0, m.drops); assertEquals(0, m.duplicates)
    }

    // ---- 6. two frames, short fractional span: no short tail frame ----

    @Test
    fun a_short_span_keeps_the_last_grid_point_inside() {
        // A span of 1.5 target periods: floor(1.5)+1 = 2 output frames
        // (j=0 at 0, j=1 at one period); the 2-period grid point falls
        // PAST the span and is never added — no short tail frame (§4.2).
        val period = 1_000_000L / 24 // 41666 us
        val m = FrameRateMap.map(TargetFps.FPS_24, 30.0, 100, 3 * period / 2)
        assertEquals(2, m.outputFrames)
        assertEquals(0L, m.ptsUs[0])
        assertEquals(41_667L, m.ptsUs[1]) // round(1e6/24) = 41667
        assertTrue(m.ptsUs.last() <= 3 * period / 2)
    }

    // ---- 7. trailing fraction: floor+1 vs ceil at the boundaries ----

    @Test
    fun trailing_fraction_pins_the_count_rule_against_ceil() {
        // 59.94: a span of 1000 periods + half a period keeps the last
        // point INSIDE the span; ceil would add one point PAST it.
        val span = Math.round(1000 * 1_000_000.0 * 1001 / 60000.0) + 8_341
        val m = FrameRateMap.map(TargetFps.FPS_59_94, 60.0, 1200, span)
        assertEquals(1001, m.outputFrames)
        assertTrue("last pts ${m.ptsUs.last()} past span $span", m.ptsUs.last() <= span)
        // At the WHOLE-number boundary (§4.2's own example) ceil DROPS a
        // frame and floor+1 does not: 89 exact 25ths of a second.
        val whole = 89L * 1_000_000L / 25 // 3_560_000, exactly 89 periods
        val floorPct = Math.floor(whole * 25.0 / 1_000_000.0).toInt() + 1
        assertEquals(90, floorPct)
        assertEquals(89, Math.ceil(whole * 25.0 / 1_000_000.0).toInt()) // ceil's fault, pinned
        assertEquals(90, FrameRateMap.map(TargetFps.FPS_25, 30.0, 90, whole).outputFrames)
    }

    // ---- 8. rational grid monotonicity: 29.97 x 3000 (§4.5/§6 v8) ----

    @Test
    fun ntsc_grid_is_monotone_and_within_half_a_us() {
        var prev = -1L
        for (j in 0 until 3000) {
            val p = FrameRateMap.gridPtsUs(j, TargetFps.FPS_29_97)
            if (j > 0) {
                assertTrue("pts not monotone at j=$j", p > prev)
                val step = p - prev
                assertTrue("step $step at j=$j", step == 33_366L || step == 33_367L)
            }
            // |pts_j - j*1001e6/30000| <= 0.5 us (one rounding).
            val exact = j * 1_001_000.0 / 30.0
            assertTrue("j=$j err ${abs(p - exact)}", abs(p - exact) <= 0.5 + 1e-9)
            prev = p
        }
    }

    // ---- 9. timeline parity (§4.1/§6 v9): TimelineExport's algebra ----

    @Test
    fun the_map_matches_the_timeline_walks_algebra() {
        // The timeline walk (TimelineExport.kt:221-223) maps
        //   sourceFrame(j) = round((start + j) * sourceFps / projectFps)
        // with roundToInt (half-up). At startUs=0 the map's
        //   round(j * 1e6 * den/num * sourceFps / 1e6)
        // is the SAME algebra — pinned here on the timeline's own case
        // pairs (project fps is an Int there; all pairs below are
        // tie-free for j < 240, where an exact .5 would reduce to float
        // order — the map's linear path is the authority there, §4.5).
        val pairs = listOf(24.0 to 30, 29.97 to 30, 23.976 to 30, 30.0 to 25, 30.0 to 50, 60.0 to 30)
        for ((sourceFps, projectFps) in pairs) {
            val target = TargetFps.entries.first { it != TargetFps.SOURCE && it.num.toDouble() / it.den == projectFps.toDouble() }
            val n = 240
            val span = Math.round((n - 1) * 1_000_000.0 * target.den / target.num)
            val m = FrameRateMap.map(target, sourceFps, n, span)
            for (j in m.sourceFrames.indices) {
                val timeline = (j * sourceFps / projectFps).roundToInt().coerceIn(0, n - 1)
                assertEquals("sourceFps=$sourceFps projectFps=$projectFps j=$j", timeline, m.sourceFrames[j])
            }
        }
    }

    // ---- 10. double-rounding collision (§4.5): the linear path wins ----

    @Test
    fun the_linear_path_beats_the_integer_t_path_where_they_differ() {
        // §4.5 forbids reducing t_j to integer us before the source-frame
        // rounding. Find j's at the 29.97 target where the two paths pick
        // DIFFERENT frames, and pin that the map implements the LINEAR
        // path (the plan's mandated implementation) — never the integer
        // path's answer.
        val target = TargetFps.FPS_29_97
        val sourceFps = 30.0
        val span = Math.round(3000 * 1_000_000.0 * target.den / target.num)
        val m = FrameRateMap.map(target, sourceFps, 5000, span)
        var collisions = 0
        for (j in 0 until 3000) {
            val linear = (j * 1_000_000.0 * target.den / target.num * sourceFps / 1_000_000.0).roundToInt()
            val integerT = kotlin.math.round(j * 1_000_000.0 * target.den / target.num)
                .let { (it * sourceFps / 1_000_000.0).roundToInt() }
            if (linear != integerT) {
                collisions++
                // The map's answer IS the linear path's, at every j —
                // the integer path's frame is never adopted.
                assertEquals("j=$j", linear.coerceIn(0, 4999), m.sourceFrames[j])
                assertTrue("j=$j linear $linear == integer $integerT", linear != integerT)
            } else {
                assertEquals("j=$j", linear.coerceIn(0, 4999), m.sourceFrames[j])
            }
        }
        assertTrue("expected at least one collision in 3000 frames, got $collisions", collisions >= 1)
    }

    // ---- honesty: SOURCE and bad inputs ----

    @Test(expected = IllegalArgumentException::class)
    fun mapping_source_is_a_caller_bug() {
        FrameRateMap.map(TargetFps.SOURCE, 30.0, 10, 100_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_non_positive_source_fps_is_refused() {
        FrameRateMap.map(TargetFps.FPS_30, 0.0, 10, 100_000L)
    }

    @Test
    fun the_tail_clamps_to_the_last_real_frame() {
        // A fractional span whose last grid point maps past the source's
        // real last frame: clamped, never past the bounds (§4.4).
        val m = FrameRateMap.map(TargetFps.FPS_50, 60.0, 10, 500_000L)
        assertEquals(9, m.sourceFrames.max()) // 10 real frames: last index 9
        assertTrue(m.sourceFrames.all { it in 0..9 })
        assertEquals(9, m.sourceFrames.last()) // the clamped tail itself
    }
}
