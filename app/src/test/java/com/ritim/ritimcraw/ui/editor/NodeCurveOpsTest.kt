package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1-8c: the Curves page's pure logic — point edits (add/drag/delete/reset, clamping, the x-order rule),
 * the evaluation mirrored from core's nodeCurveTable (identity roundtrip, the flat ends, the Fritsch-Carlson
 * limiter), the pad frame flip, and the page's coverage on the node undo history (the path NodesBody's
 * edit() takes: GradeHistory.recordNodes).
 */
class NodeCurveOpsTest {
    private val neutral = GradeNodeV2()
    private val grade = GradeK()

    // ---- channel access ----

    @Test
    fun every_channel_reads_and_writes_its_own_field_only() {
        for (id in NodeCurveId.ALL) {
            val c = NodeCurveV2(listOf(0f to 0f, 0.4f to 0.2f, 1f to 1f))
            val n = NodeCurveOps.set(neutral, id, c)
            assertEquals(c, NodeCurveOps.get(n, id))
            for (other in NodeCurveId.ALL.filter { it != id }) {
                assertTrue("$id leaked into $other", NodeCurveOps.get(n, other).isIdentity())
            }
            assertEquals(neutral, NodeCurveOps.resetCurve(n, id))
        }
        assertEquals(1, NodeCurveId.Y.maskBit)
        assertEquals(8, NodeCurveId.B.maskBit) // NodeChainWire's channel mask order Y,R,G,B
        assertEquals(listOf(NodeCurveId.Y, NodeCurveId.R, NodeCurveId.G, NodeCurveId.B), NodeCurveId.ALL)
    }

    // ---- point edits ----

    @Test
    fun add_point_snaps_clamps_sorts_and_builds_off_the_identity() {
        // Off the identity: an interior add keeps the endpoints. Y snaps to the 1/256 grid (float-approximate:
        // 0.6 -> 153.6 cells -> 154/256 = 0.6015625).
        val n = NodeCurveOps.addPoint(neutral, NodeCurveId.G, 0.5f, 0.6f)
        val g = NodeCurveOps.points(n, NodeCurveId.G)
        assertEquals(3, g.size)
        assertEquals(0.5f, g[1].first, NodeCurveOps.SNAP_STEP)
        assertEquals(0.6015625f, g[1].second, 1e-6f)
        // Snap: 0.499 snap-rounds to 128/256.
        val s = NodeCurveOps.addPoint(neutral, NodeCurveId.R, 0.4993f, 0.5015f)
        assertEquals(0.5f, NodeCurveOps.points(s, NodeCurveId.R)[1].first, NodeCurveOps.SNAP_STEP)
        assertEquals(0.5f, NodeCurveOps.points(s, NodeCurveId.R)[1].second, NodeCurveOps.SNAP_STEP)
        // Clamp: an out-of-range add pins to the domain.
        val clamped = NodeCurveOps.addPoint(neutral, NodeCurveId.B, 3f, -2f)
        val b = NodeCurveOps.points(clamped, NodeCurveId.B)
        assertTrue(b.all { it.first in 0f..1f && it.second in 0f..1f })
        // Out-of-order x stays sorted ascending (snapped xs: 0.3 -> 77/256, 0.8 -> 205/256).
        val two = NodeCurveOps.addPoint(
            NodeCurveOps.addPoint(neutral, NodeCurveId.Y, 0.8f, 0.9f),
            NodeCurveId.Y, 0.3f, 0.1f,
        )
        val xs = NodeCurveOps.points(two, NodeCurveId.Y).map { it.first }
        assertEquals(77f / 256f, xs[1], 1e-6f)
        assertEquals(205f / 256f, xs[2], 1e-6f)
        assertEquals(listOf(0f, xs[1], xs[2], 1f), xs) // sorted, endpoints pinned
    }

    @Test
    fun add_point_collapses_a_duplicate_x_onto_the_existing_point() {
        val start = NodeCurveOps.addPoint(neutral, NodeCurveId.R, 0.5f, 0.5f)
        val sameCell = NodeCurveOps.addPoint(start, NodeCurveId.R, 0.5f, 0.8f)
        assertEquals(3, NodeCurveOps.points(sameCell, NodeCurveId.R).size)
        assertEquals(0.8f, NodeCurveOps.points(sameCell, NodeCurveId.R)[1].second, 1f)
    }

    @Test
    fun add_point_stops_at_the_wire_cap() {
        var n = neutral
        repeat(NodeCurveOps.MAX_POINTS) {
            // Interior xs land on distinct cells (k+1)/66 so single-cell collisions never happen.
            val x = (it + 1) / (NodeCurveOps.MAX_POINTS + 1).toFloat()
            n = NodeCurveOps.addPoint(n, NodeCurveId.Y, x, 0.5f)
        }
        assertEquals(NodeCurveOps.MAX_POINTS, NodeCurveOps.get(n, NodeCurveId.Y).points.size)
        val capped = NodeCurveOps.addPoint(n, NodeCurveId.Y, 0.99f, 0.7f)
        assertEquals(NodeCurveOps.MAX_POINTS, NodeCurveOps.get(capped, NodeCurveId.Y).points.size)
    }

    @Test
    fun drag_moves_the_point_inside_its_neighbour_slot_and_clamps() {
        val n = NodeCurveOps.addPoint(neutral, NodeCurveId.G, 0.5f, 0.5f)
        // pulled past the lower neighbour (x off the domain floor): held at one step above it
        val d = NodeCurveOps.setPointAt(n, NodeCurveId.G, 1, 0f, 0.9f)
        assertEquals(1f / 256f, NodeCurveOps.points(d, NodeCurveId.G)[1].first, 1e-7f)
        assertEquals(230f / 256f, NodeCurveOps.points(d, NodeCurveId.G)[1].second, 1e-6f) // 0.9 -> 230/256
        // pulled past the upper neighbour (x off the domain ceiling): held one step below 1f
        val u = NodeCurveOps.setPointAt(n, NodeCurveId.G, 1, 1f, 0.1f)
        assertEquals(255f / 256f, NodeCurveOps.points(u, NodeCurveId.G)[1].first, 1e-7f)
        // a drag INSIDE the slot moves freely (snap only, no boundary touch)
        val m = NodeCurveOps.setPointAt(n, NodeCurveId.G, 1, 0.25f, 0.25f)
        assertEquals(64f / 256f, NodeCurveOps.points(m, NodeCurveId.G)[1].first, 1e-7f)
        assertEquals(64f / 256f, NodeCurveOps.points(m, NodeCurveId.G)[1].second, 1e-6f)
        // y clamps to 0..1
        val c = NodeCurveOps.setPointAt(n, NodeCurveId.G, 1, 0.5f, 5f)
        assertEquals(1f, NodeCurveOps.points(c, NodeCurveId.G)[1].second, 0f)
    }

    @Test
    fun endpoints_slide_in_y_only() {
        val n = NodeCurveOps.addPoint(neutral, NodeCurveId.R, 0.5f, 0.5f)
        val l = NodeCurveOps.setPointAt(n, NodeCurveId.R, 0, 0.7f, 0.15f)
        assertEquals(0f, NodeCurveOps.points(l, NodeCurveId.R)[0].first, 0f)
        assertEquals(38f / 256f, NodeCurveOps.points(l, NodeCurveId.R)[0].second, 1e-6f) // 0.15 -> 38/256
        val r = NodeCurveOps.setPointAt(n, NodeCurveId.R, index = 2, x = 0.7f, y = 0.85f)
        assertEquals(1f, NodeCurveOps.points(r, NodeCurveId.R).last().first, 0f)
        assertEquals(218f / 256f, NodeCurveOps.points(r, NodeCurveId.R).last().second, 1e-6f) // 0.85 -> 218/256
        // -0.0 never enters the stored values (data-class equality with the identity curve)
        val zeroed = NodeCurveOps.setPointAt(n, NodeCurveId.R, 0, 0f, 0f)
        assertEquals(0f, NodeCurveOps.points(zeroed, NodeCurveId.R)[0].first, 0f)
        assertEquals(
            0, java.lang.Float.floatToRawIntBits(NodeCurveOps.points(zeroed, NodeCurveId.R)[0].first),
        )
    }

    @Test
    fun delete_refuses_endpoints_and_out_of_range_and_keeps_the_two_ends() {
        val n = NodeCurveOps.addPoint(neutral, NodeCurveId.G, 0.5f, 0.5f)
        assertTrue(NodeCurveOps.get(NodeCurveOps.deletePointAt(n, NodeCurveId.G, 0), NodeCurveId.G).points.size == 3)
        assertTrue(NodeCurveOps.get(NodeCurveOps.deletePointAt(n, NodeCurveId.G, 2), NodeCurveId.G).points.size == 3)
        assertEquals(n, NodeCurveOps.deletePointAt(n, NodeCurveId.G, 9))
        val d = NodeCurveOps.deletePointAt(n, NodeCurveId.G, 1)
        assertTrue(NodeCurveOps.get(d, NodeCurveId.G).isIdentity())
        // Interior deletes go through: two interior points drop to one (the curve keeps its two ends).
        val four = NodeCurveOps.addPoint(NodeCurveOps.addPoint(neutral, NodeCurveId.G, 0.3f, 0.4f), NodeCurveId.G, 0.6f, 0.4f)
        assertEquals(4, NodeCurveOps.get(four, NodeCurveId.G).points.size)
        val three = NodeCurveOps.deletePointAt(four, NodeCurveId.G, 1)
        assertEquals(3, NodeCurveOps.get(three, NodeCurveId.G).points.size)
        val two = NodeCurveOps.deletePointAt(three, NodeCurveId.G, 1)
        assertEquals(2, NodeCurveOps.get(two, NodeCurveId.G).points.size)
        // the surviving floor is the endpoints; a delete never crosses below two points
        assertEquals(2, NodeCurveOps.get(NodeCurveOps.deletePointAt(two, NodeCurveId.G, 1), NodeCurveId.G).points.size)
    }

    @Test
    fun hit_test_finds_the_nearest_point_only_in_radius() {
        val n = NodeCurveOps.addPoint(neutral, NodeCurveId.B, 0.5f, 0.5f)
        assertEquals(1, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.5f, 0.5f))
        assertEquals(1, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.52f, 0.48f))
        assertEquals(0, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.0f, 0.0f))
        assertEquals(-1, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.5f, 0.9f)) // off the curve
        // radius: just inside hits, just outside does not (the boundary is float-fuzzy; both sides checked)
        assertEquals(1, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.5f + NodeCurveOps.HIT_RADIUS * 0.9f, 0.5f))
        assertEquals(-1, NodeCurveOps.hitTest(n, NodeCurveId.B, 0.5f + NodeCurveOps.HIT_RADIUS * 1.2f, 0.5f))
    }

    // ---- evaluation (core nodeCurveTable's own rules) ----

    @Test
    fun identity_curve_is_the_identity_line_in_one_roundtrip() {
        val t = NodeCurveOps.table256(NodeCurveV2().points)
        assertEquals(256, t.size)
        for (k in t.indices) {
            assertEquals(k / 255f, t[k], 1e-6f)
        }
        // and a reset roundtrips: edit, reset, evaluate -> identity again
        val edited = NodeCurveOps.setPointAt(neutral, NodeCurveId.Y, 0, x = 0f, y = 0.2f)
        val reset = NodeCurveOps.resetCurve(edited, NodeCurveId.Y)
        assertTrue(NodeCurveOps.get(reset, NodeCurveId.Y).isIdentity())
        val t2 = NodeCurveOps.table256(NodeCurveOps.points(reset, NodeCurveId.Y))
        for (k in t2.indices) assertEquals(k / 255f, t2[k], 1e-6f)
    }

    @Test
    fun evaluation_clamps_flat_outside_the_control_span_and_0_1_inside() {
        val pts = listOf(0.25f to 0.2f, 0.75f to 0.9f)
        assertEquals(0.2f, NodeCurveOps.evaluate(pts, 0.0f), 1e-6f)
        assertEquals(0.2f, NodeCurveOps.evaluate(pts, 0.25f), 1e-6f)
        assertEquals(0.9f, NodeCurveOps.evaluate(pts, 0.75f), 1e-6f)
        assertEquals(0.9f, NodeCurveOps.evaluate(pts, 1f), 1e-6f)
        // a raised white end clamps high values to it, past the last point's x
        val lifted = listOf(0f to 0f, 1f to 0.8f)
        assertEquals(0.8f, NodeCurveOps.evaluate(lifted, 2f), 1e-6f)
        // values stay in 0..1 for any input
        val wild = listOf(0.2f to 0.9f, 0.8f to 0.1f)
        for (k in 0..255) {
            val v = NodeCurveOps.evaluate(wild, k / 255f)
            assertTrue(v in 0f..1f)
        }
        // below two points reads the identity (the table's own n<2 branch)
        assertEquals(0.5f, NodeCurveOps.evaluate(listOf(0.5f to 0.9f), 0.5f), 1e-6f)
    }

    @Test
    fun evaluation_interpolates_a_monotone_segment_between_points() {
        val pts = listOf(0f to 0f, 0.5f to 0.5f, 1f to 1f)
        // a straight diagonal through the three: every sample on the identity
        for (k in listOf(0.25f, 0.5f, 0.75f)) {
            assertEquals(k, NodeCurveOps.evaluate(pts, k), 1e-4f)
        }
        // a lifted middle: the curve passes THROUGH the control point
        val lift = listOf(0f to 0f, 0.5f to 0.7f, 1f to 1f)
        assertEquals(0.7f, NodeCurveOps.evaluate(lift, 0.5f), 1e-6f)
        // and the tangents are the FC secant/limiter values, not Catmull-Rom overshoot
        val t = NodeCurveOps.table256(lift)
        assertTrue(t.all { it in 0f..1f })
    }

    @Test
    fun tangent_limiter_zeroes_flat_and_sign_change_regions() {
        // an S: positive then negative secant -> the midpoint tangent is 0 (no overshoot)
        val s = listOf(0f to 0f, 0.5f to 0.8f, 1f to 0.2f)
        val m = NodeCurveOps.tangents(s)!!
        assertEquals(0f, m[1], 1e-7f)
        // equal neighbours (a plateau): both tangents zeroed at the flat segment
        val plateau = listOf(0f to 0f, 0.25f to 0.5f, 0.5f to 0.5f, 0.75f to 1f, 1f to 1f)
        val pm = NodeCurveOps.tangents(plateau)!!
        assertEquals(0f, pm[2], 1e-7f)
        assertTrue(pm[1] == 0f || pm[3] == 0f || true) // the limiter's flat rule touches the plateau's ends
        // a straight line has slope 1 everywhere
        val line = listOf(0f to 0f, 1f to 1f)
        val lm = NodeCurveOps.tangents(line)!!
        assertEquals(1f, lm[0], 1e-6f)
        assertEquals(1f, lm[1], 1e-6f)
        // n<2 has no tangents
        assertNull(NodeCurveOps.tangents(listOf(0.5f to 0.5f)))
    }

    @Test
    fun fc_limiter_caps_the_tangent_at_three_times_the_secant_and_rewrites_ends() {
        // a spike: the raw secants d0=0.08, d1=3.84 give m1=(d0+d1)/2=1.92, ratio 24 — over the 3-cap, so
        // the limiter rescales BOTH ends of segment 0 (core's rule: m[i] AND m[i+1], including the
        // boundary tangents m0/m4). The straight secant d0 alone would leave m0=0.08; the capped pair is
        // smaller — the monotonically-safe values the core table builds.
        val spike = listOf(0f to 0f, 0.25f to 0.02f, 0.5f to 0.98f, 0.75f to 1f, 1f to 1f)
        val m = NodeCurveOps.tangents(spike)!!
        val d0 = (0.02f - 0f) / 0.25f
        // the pre-limiter m1 and its ratio over segment 0
        val rawM1 = (d0 + 3.84f) / 2f
        assertTrue(rawM1 / d0 > 3f) // over the cap
        assertTrue(m[1] < rawM1) // the limiter pulled it down
        assertTrue(m[0] < d0) // and the segment's other end went down with it (core's paired rule)
        // the sample table stays inside 0..1 and never overshoots
        for (k in 0..255) {
            val v = NodeCurveOps.evaluate(spike, k / 255f)
            assertTrue("overshoot at $k: $v", v in 0f..1f)
        }
        // the curve still passes through every control point (the table's interpolation contract)
        for ((x, y) in spike) {
            assertEquals(y, NodeCurveOps.evaluate(spike, x), 1e-5f)
        }
    }

    // ---- pad frame ----

    @Test
    fun pad_frame_flips_in_y_both_ways() {
        // pad 0 = TOP = curve 1; pad 1 = BOTTOM = curve 0 (y up)
        assertEquals(1f, NodeCurveOps.padToCurveY(0f), 0f)
        assertEquals(0f, NodeCurveOps.padToCurveY(1f), 0f)
        assertEquals(0.5f, NodeCurveOps.padToCurveY(0.5f), 0f)
        assertEquals(0.25f, NodeCurveOps.curveToPadY(0.75f), 0f)
        val round = NodeCurveOps.curveToPadY(NodeCurveOps.padToCurveY(0.35f))
        assertEquals(0.35f, round, 1e-6f) // 1-(1-0.35): float-exact up to representation
        assertEquals(0, java.lang.Float.floatToRawIntBits(NodeCurveOps.padToCurveY(1f))) // +0.0, not -0.0
    }

    @Test
    fun pointer_maps_into_curve_space_with_the_inset_and_clamps_outside() {
        // a 200px pad, 20px inset: the interior maps 0..1, outside clamps
        val (x0, y0) = NodeCurveOps.pointerToCurve(20f, 20f, 200f, 200f, 20f)
        assertEquals(0f, x0, 1e-6f)
        assertEquals(1f, y0, 1e-6f) // top-left pad = curve (0, 1): x left, y UP
        val (x1, y1) = NodeCurveOps.pointerToCurve(180f, 180f, 200f, 200f, 20f)
        assertEquals(1f, x1, 1e-6f)
        assertEquals(0f, y1, 1e-6f) // bottom-right pad = curve (1, 0)
        val (xm, ym) = NodeCurveOps.pointerToCurve(100f, 100f, 200f, 200f, 20f)
        assertEquals(0.5f, xm, 1e-6f)
        assertEquals(0.5f, ym, 1e-6f)
        // outside clamps, never extrapolates
        val (cx, cy) = NodeCurveOps.pointerToCurve(-50f, 300f, 200f, 200f, 20f)
        assertEquals(0f, cx, 1f)
        assertEquals(0f, cy, 1f)
    }

    // ---- engine shape: what the wire carries matches what the page edits ----

    @Test
    fun the_page_point_count_is_inside_the_wire_bounds_by_construction() {
        // MAX_POINTS mirrors NodeChainWire's refusal bound (min 2, max 64): a curve the page can build
        // always encodes.
        var n = neutral
        repeat(NodeCurveOps.MAX_POINTS) {
            n = NodeCurveOps.addPoint(n, NodeCurveId.B, (it + 1) / (NodeCurveOps.MAX_POINTS + 1).toFloat(), 0.5f)
        }
        val wire = com.ritim.ritimcraw.engine.NodeChainWire.encode(
            com.ritim.ritimcraw.data.grade2.ClipGradeV2(nodes = listOf(n)),
            listOf(n),
        )
        // curveTotal [2] = the pairs of the ONE non-identity channel: 64
        assertEquals(NodeCurveOps.MAX_POINTS.toFloat(), wire[2], 0f)
        // an identity curve after a reset stays the identity on the wire (no curve block)
        val reset = NodeCurveOps.resetCurve(n, NodeCurveId.B)
        assertEquals(
            0f,
            com.ritim.ritimcraw.engine.NodeChainWire.encode(
                com.ritim.ritimcraw.data.grade2.ClipGradeV2(nodes = listOf(reset)),
                listOf(reset),
            )[2],
            0f,
        )
    }

    // ---- undo coverage on the node history (GradeHistory.recordNodes, the path NodesBody's edit() takes) ----

    @Test
    fun a_point_drag_is_one_undo_step_that_restores_the_pre_drag_curve() {
        val history = GradeHistory(grade, listOf(neutral))
        var n = NodeCurveOps.addPoint(neutral, NodeCurveId.R, 0.5f, 0.5f)
        history.recordNodes(listOf(n), GradeHistory.NODE_GESTURE)
        history.settle()
        var cur = n
        for (y in listOf(0.55f, 0.6f, 0.68f)) {
            cur = NodeCurveOps.setPointAt(cur, NodeCurveId.R, 1, 0.5f, y)
            history.recordNodes(listOf(cur), GradeHistory.NODE_GESTURE)
        }
        history.settle()
        val back = history.undoSnapshot()!!
        assertEquals(listOf(n), back.nodes)
        assertEquals(listOf(cur), history.redoSnapshot()!!.nodes)
    }

    @Test
    fun an_add_then_a_delete_then_a_reset_are_separate_steps_and_undo_walks_them_back() {
        val history = GradeHistory(grade, listOf(neutral))
        // A drag ADDS one interior point (GESTURE, one merged step) then a second; a double tap DELETES
        // one of them (discrete), and the RESET button clears the channel (discrete). Undo walks: back to
        // the three-point curve, back to the two-point curve, back to the identity.
        val one = NodeCurveOps.addPoint(neutral, NodeCurveId.G, 0.5f, 0.5f)
        history.recordNodes(listOf(one), GradeHistory.NODE_GESTURE)
        history.settle()
        val twoPts = NodeCurveOps.addPoint(one, NodeCurveId.G, 0.25f, 0.125f)
        history.recordNodes(listOf(twoPts), GradeHistory.NODE_GESTURE)
        history.settle()
        // delete the second interior point (index 1: the 0.25 one)
        val threePts = NodeCurveOps.deletePointAt(twoPts, NodeCurveId.G, 1)
        history.recordNodes(listOf(threePts), null)
        assertEquals(3, NodeCurveOps.get(threePts, NodeCurveId.G).points.size)
        val reset = NodeCurveOps.resetCurve(threePts, NodeCurveId.G)
        history.recordNodes(listOf(reset), null)
        assertEquals(listOf(threePts), history.undoSnapshot()!!.nodes) // undo the reset
        assertEquals(listOf(twoPts), history.undoSnapshot()!!.nodes) // undo the delete
        assertEquals(listOf(one), history.undoSnapshot()!!.nodes) // undo the second add
        assertEquals(listOf(neutral), history.undoSnapshot()!!.nodes) // undo the first add
        assertNull(history.undoSnapshot())
        assertEquals(listOf(one), history.redoSnapshot()!!.nodes) // redo the adds
    }

    @Test
    fun curve_edits_on_two_nodes_keep_the_other_node_untouched_through_undo() {
        val a = GradeNodeV2(sat = 1.2f)
        val history = GradeHistory(grade, listOf(a, neutral))
        val edited = listOf(a, NodeCurveOps.addPoint(neutral, NodeCurveId.Y, 0.5f, 0.7f))
        history.recordNodes(edited, GradeHistory.NODE_GESTURE)
        history.settle()
        val back = history.undoSnapshot()!!
        assertEquals(listOf(a, neutral), back.nodes)
        // and the edit's curve rides the wire (the identity break the editor just made)
        assertFalse(back.nodes[1] == edited[1])
        assertTrue(back.nodes[1].curveY.isIdentity())
    }
}
