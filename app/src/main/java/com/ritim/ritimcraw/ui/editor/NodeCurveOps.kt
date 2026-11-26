package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The editable curve channels (F1-8c): Y first, per the design's "Y/R/G/B". */
enum class NodeCurveId(val maskBit: Int) {
    Y(1), R(2), G(4), B(8);

    companion object {
        val ALL: List<NodeCurveId> = entries.toList()
    }
}

/**
 * F1-8c: pure edit logic for the Curves page (docs/F1_8_COLOR_UI_DESIGN.md section 3, "Eğriler" — a Compose
 * Canvas editor over Y/R/G/B), over `GradeNodeV2.curveY/R/G/B`. No Compose, no schema change; every function
 * returns a new node. The stored shape is NodeCurveV2's: flat (x, y) pairs in 0..1, x sorted ascending, the
 * two-point {(0,0),(1,1)} curve the identity (core ColorModel.h NodeCurve, the wire skips identity channels).
 *
 * EVALUATION mirrors core's nodeCurveTable (core/src/ColorModel.cpp) — the Fritsch-Carlson monotone cubic the
 * engine builds its 256-entry tables with — so the line the editor draws is the curve the engine samples:
 * the page pulls the SAME series for its polyline (a 256-sample table like the engine's) through
 * [table256], and [evaluate] interpolates individual points with the same tangents (the interaction
 * highlight). Ends clamp flat: x below the first control point reads the first y, above the last the last y;
 * the result clamps to 0..1 exactly like the core table.
 */
object NodeCurveOps {

    /** The wire's per-curve control point bounds (NodeChainWire's refusal: min 2, max 64). */
    const val MAX_POINTS = 64

    /** The add/drag snap grid: 1/256 keeps every stored point on the 256-entry table's lattice. */
    const val SNAP_STEP = 1f / 256f

    /** The drag-hit radius (in curve units) for "the pointer grabbed an existing point". */
    const val HIT_RADIUS = 0.045f

    fun get(node: GradeNodeV2, id: NodeCurveId): NodeCurveV2 = when (id) {
        NodeCurveId.Y -> node.curveY
        NodeCurveId.R -> node.curveR
        NodeCurveId.G -> node.curveG
        NodeCurveId.B -> node.curveB
    }

    fun set(node: GradeNodeV2, id: NodeCurveId, c: NodeCurveV2): GradeNodeV2 = when (id) {
        NodeCurveId.Y -> node.copy(curveY = c)
        NodeCurveId.R -> node.copy(curveR = c)
        NodeCurveId.G -> node.copy(curveG = c)
        NodeCurveId.B -> node.copy(curveB = c)
    }

    private fun snap(v: Float): Float = (v / SNAP_STEP).roundToInt() * SNAP_STEP + 0f // + 0f: -0.0 -> 0.0

    /** Clamps to 0..1 and snaps to the grid; keeps -0.0 away (+0f). */
    private fun clamp01(v: Float): Float = snap(finiteOrZero(v).coerceIn(0f, 1f)) + 0f

    /** NaN / +-Inf from a bad pointer event become 0 (same guard as NodeWheelOps), never an exception. */
    private fun finiteOrZero(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v

    // ---- point edits (the design's acceptance: "nokta ekle/sürükle/sil") ----

    /** Points as-is (already sorted by the setters below); identity when the node's default. */
    fun points(node: GradeNodeV2, id: NodeCurveId): List<Pair<Float, Float>> = get(node, id).points

    /**
     * Adds a control point (a tap that is not on an existing point). Duplicate x collapses onto the
     * existing point (the caller drags from there), an out-of-order x is still sorted in; at the cap the
     * curve returns unchanged (the caller can show the 64 limit).
     */
    fun addPoint(node: GradeNodeV2, id: NodeCurveId, x: Float, y: Float): GradeNodeV2 {
        val c = get(node, id)
        val i = c.points.indexOfFirst { abs(it.first - x) < SNAP_STEP / 2f }
        if (i >= 0) {
            // Same lattice cell: move that point instead of stacking two on one x.
            return setPointAt(node, id, i, x, y)
        }
        if (c.points.size >= MAX_POINTS) return node
        val cx = clamp01(x)
        val cy = clamp01(y)
        val pts = (c.points + (cx to cy)).sortedBy { it.first }
        return set(node, id, NodeCurveV2(pts))
    }

    /**
     * Drags point [index] to (x, y) — clamped 0..1, snapped, and NOT allowed past its neighbours (a crossing
     * would reorder the curve; the drag stops one lattice step short instead, the classic curve-editor rule).
     * Endpoints CAN move in y (a lifted black/white point is a legitimate grade) but not in x: the curve keeps
     * its full 0..1 domain, so the engine's flat-end clamps stay reachable.
     */
    fun setPointAt(node: GradeNodeV2, id: NodeCurveId, index: Int, x: Float, y: Float): GradeNodeV2 {
        val c = get(node, id)
        if (index !in c.points.indices) return node
        val pts = c.points.toMutableList()
        val last = pts.size - 1
        when (index) {
            0 -> pts[0] = 0f to clamp01(y)
            last -> pts[last] = 1f to clamp01(y)
            else -> {
                // Snap FIRST, then the neighbour floor/ceiling (values already on the lattice, so the
                // boundary never needs a re-snap and float drift cannot cross the neighbours).
                val lo = pts[index - 1].first + SNAP_STEP
                val hi = pts[index + 1].first - SNAP_STEP
                val cx = if (hi < lo) {
                    // A degenerate slot (neighbours on adjacent lattice cells): pin exactly between them.
                    (pts[index - 1].first + pts[index + 1].first) / 2f
                } else {
                    snap(finiteOrZero(x).coerceIn(0f, 1f)).coerceIn(lo, hi)
                }
                pts[index] = cx + 0f to clamp01(y)
            }
        }
        return set(node, id, NodeCurveV2(pts.toList()))
    }

    /** The nearest point to (x, y) within [HIT_RADIUS], for tap/drag hit-testing; null when none. */
    fun hitTest(node: GradeNodeV2, id: NodeCurveId, x: Float, y: Float): Int {
        val pts = get(node, id).points
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in pts.indices) {
            val d = dist(pts[i], x, y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (bestD <= HIT_RADIUS) best else -1
    }

    // ---- drag gestures (the page's pointer wiring, pure so the wiring is testable) ----

    /**
     * One drag gesture's captured start: the node the grabbed [index] resolves against (-1 = nothing
     * grabbed), and whether the start ADDED a point (the page publishes [node] immediately so the new dot
     * is visible before the first move arrives).
     *
     * The start SNAPSHOT is the add-drag fix: Compose dispatches onDragStart and the first onDrag in the
     * SAME pointer event (foundation's DragGestureDetector), before any recomposition could refresh a
     * rememberUpdatedState node — a move that resolved its index against the PRE-add node hit the wrong
     * point (index 1 of the identity two-point curve is the (1,1) endpoint, so a mid-line add-drag dragged
     * the white point down to the finger and never showed the new dot). [dragTo] applies every move of the
     * gesture to [node], so the index stays valid for the whole gesture.
     */
    data class CurveDragStart(val node: GradeNodeV2, val index: Int, val addedPoint: Boolean)

    /**
     * Begins a drag at curve position (x, y): a start ON an existing point (within [HIT_RADIUS]) grabs it;
     * a start OFF every point ADDS one there and grabs it (the design's "boş alanda başlayan sürükleme
     * nokta ekler"). A near-duplicate x collapses onto the existing point (addPoint's own rule); at the
     * point cap the add refuses and a touch near an existing point still grabs it (nothing is published —
     * addedPoint false), a touch far from every point grabs nothing (index -1, [dragTo] a no-op).
     */
    fun beginDrag(node: GradeNodeV2, id: NodeCurveId, x: Float, y: Float): CurveDragStart {
        val hit = hitTest(node, id, x, y)
        if (hit >= 0) return CurveDragStart(node, hit, addedPoint = false)
        val grown = addPoint(node, id, x, y)
        val at = points(grown, id).indexOfFirst { dist(it, x, y) < 3f * SNAP_STEP }
        return CurveDragStart(grown, at, addedPoint = grown != node)
    }

    /** One drag move: the grabbed point to (x, y), applied to the START's snapshot (see [CurveDragStart]). */
    fun dragTo(start: CurveDragStart, id: NodeCurveId, x: Float, y: Float): GradeNodeV2 =
        if (start.index < 0) start.node else setPointAt(start.node, id, start.index, x, y)

    /**
     * Deletes point [index]. An endpoint deletion is refused (the identity curbs stay; pulling (0,0) or
     * (1,1) off would strand the flat-end clamps); a curve that would drop below two points keeps the two
     * endpoints (the last interior delete restores the identity shape when nothing else remains).
     */
    fun deletePointAt(node: GradeNodeV2, id: NodeCurveId, index: Int): GradeNodeV2 {
        val c = get(node, id)
        if (index !in c.points.indices) return node
        if (index == 0 || index == c.points.size - 1) return node
        return set(node, id, NodeCurveV2(c.points.filterIndexed { i, _ -> i != index }))
    }

    /** The reset button: this channel back to the identity. */
    fun resetCurve(node: GradeNodeV2, id: NodeCurveId): GradeNodeV2 = set(node, id, NodeCurveV2())

    // ---- evaluation (core nodeCurveTable, mirrored) ----

    /**
     * The Fritsch-Carlson monotone-cubic tangents, exactly core's derivation (ColorModel.cpp nodeCurveTable):
     * secants, zeroed at sign changes, then the 3-limit limiter. A curve below two points is identity (the
     * table's own n<2 branch); evaluation is the caller's.
     */
    fun tangents(pts: List<Pair<Float, Float>>): FloatArray? {
        val p = pts.sortedBy { it.first }
        val n = p.size
        if (n < 2) return null
        val xs = FloatArray(n) { p[it].first }
        val ys = FloatArray(n) { p[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / max(1e-6f, xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /**
     * The curve's value at x — core's per-sample rule: flat y0 before the first control point, flat yN after
     * the last, the monotone cubic Hermite between, NaN-safe to identity, clamped 0..1.
     */
    fun evaluate(pts: List<Pair<Float, Float>>, x: Float): Float {
        val p = pts.sortedBy { it.first }
        val n = p.size
        if (n < 2) return x.coerceIn(0f, 1f)
        if (x <= p[0].first) return p[0].second.coerceIn(0f, 1f)
        if (x >= p[n - 1].first) return p[n - 1].second.coerceIn(0f, 1f)
        var i = 0
        while (i < n - 2 && x > p[i + 1].first) ++i
        val m = tangents(p)!!
        val h = p[i + 1].first - p[i].first
        val t = if (h > 1e-6f) (x - p[i].first) / h else 0f
        val t2 = t * t
        val t3 = t2 * t
        val v = (2 * t3 - 3 * t2 + 1) * p[i].second +
            (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * p[i + 1].second +
            (t3 - t2) * h * m[i + 1]
        return if (v.isNaN()) x.coerceIn(0f, 1f) else v.coerceIn(0f, 1f)
    }

    /**
     * The 256-entry sample table the page's polyline draws — the engine's own nodeCurveTable resolution, so
     * the editor shows the curve the render applies (not a smoother idealization of it). Identity in gives
     * the identity line.
     */
    fun table256(pts: List<Pair<Float, Float>>): FloatArray = FloatArray(256) { k ->
        evaluate(pts, k / 255f)
    }

    // ---- pad frame (the page draws y DOWN; the curve stores y UP) ----

    /**
     * Pad y (0 top .. 1 bottom) to curve y (0 bottom .. 1 top): one flip. Pure so both directions are
     * tested; the roundtrip is the identity.
     */
    fun padToCurveY(padY: Float): Float = 1f - padY + 0f

    /** Curve y (0 bottom .. 1 top) to pad y (0 top .. 1 bottom). */
    fun curveToPadY(y: Float): Float = 1f - y + 0f

    /**
     * A pointer position in curve units from a pad-sized view: (w, h) the drawable pixel size, insetPx the
     * inset the curve's frame keeps inside the canvas (thumb room), y flipped to up. Pure so the gesture
     * math and the draw share one frame; the page remembers the size it drew with.
     */
    fun pointerToCurve(px: Float, py: Float, w: Float, h: Float, insetPx: Float): Pair<Float, Float> {
        val r = plotRect(w, h, insetPx)
        val x = ((px - r.left) / r.width).coerceIn(0f, 1f)
        val y = padToCurveY(((py - r.top) / r.height).coerceIn(0f, 1f))
        return x to y
    }

    /** The plot's inner rectangle in canvas pixels (y down). Width and height are always >= 1. */
    data class PlotRect(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * The ONE plot frame the draw and the gestures share. The inset is clamped so it never eats more
     * than half of the smaller side, and the inner size never drops below 1px: a collapsed or not yet
     * measured canvas (w or h of 0, or smaller than 2 * inset) yields a tiny positive rect, never a
     * negative one (a negative height flips the drawn identity line to descending).
     */
    fun plotRect(w: Float, h: Float, insetPx: Float): PlotRect {
        val maxIns = ((minOf(w, h) - 1f) / 2f).coerceAtLeast(0f)
        val ins = insetPx.coerceIn(0f, maxIns)
        return PlotRect(ins, ins, (w - 2 * ins).coerceAtLeast(1f), (h - 2 * ins).coerceAtLeast(1f))
    }

    private fun dist(p: Pair<Float, Float>, x: Float, y: Float): Float {
        val dx = p.first - x
        val dy = p.second - y
        return sqrt(dx * dx + dy * dy)
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
