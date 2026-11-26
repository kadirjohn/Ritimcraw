package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2

/**
 * The node chain's WIRE (color model v2 F1-6): one flat FloatArray
 * carrying the RAW knob set — the rawedit::GradeNode values themselves,
 * NOT the compiled packs — plus the curve control points, crossed as ONE
 * array so a chain edit is one JNI call (the GradeParcel discipline: the
 * parameter packet, built once per grade change).
 *
 * WHY RAW KNOBS AND NOT THE COMPILED PACKS (NodeParcel's shape): the CPU
 * reference (core's applyNodes) and the GPU pipeline BOTH compile through
 * core's own rawedit::compileNode — the single source of truth for the
 * wheel cosines, the primaries solve, the hue matrix and the composite
 * curve tables. NodeParcel's Kotlin compile is the JVM-side seam the
 * sweep harness needed; the PRODUCTION binding crosses the raw knobs and
 * lets core compile, so neither side can drift (the "same numbers" rule
 * test_gpu_nodes_logic pins). The curve tables never ride this wire
 * either: the PARCEL carries the CONTROL POINTS, and core's
 * nodeCurveTable (Fritsch-Carlson monotone cubic) builds the tables —
 * one implementation, three consumers (CPU, GPU atlas, tests).
 *
 * LAYOUT (mirrored in app/src/main/cpp/gles/NodeChainWire.h — the
 * hand-written pair locked by NodeChainWireTest's committed-header
 * cross-check, the NodeParcelTest mechanism; the gen-grade-parcel.py
 * schema owns the 29-slot GRADE parcel only):
 *
 *   [0]      nodeCount n (0..8; the TRIMMED count — GradeV2Usage strips
 *            neutral no-ops first, so the N1 cap is judged on live nodes)
 *   [1]      outputMode (0 = legacy TAIL; 1 = DwgLinear, TAIL's OpenDRT
 *            variant; the usage gate guarantees 0 for LEGACY grades and
 *            the value output.mode declares for NODES grades)
 *   [2]      curveTotal: the number of PAIRS that follow the node records
 *            (the curve block's length contract; 0 when no node carries
 *            a non-identity curve)
 *   per node i (0..n-1), a record of 34 float slots (STRIDE):
 *     +0  enabled (0/1)      +1  mix (0..1)
 *     +2..4   lift x, y, master        +5..7   gamma x, y, master
 *     +8..10  gain x, y, master         +11..13 offset x, y, master
 *     +14 contrast          +15 pivot
 *     +16 sat               +17 hue (degrees)   +18 temp   +19 tint
 *     +20..22 logShadow x, y, master   +23..25 logMid x, y, master
 *     +26..28 logHi x, y, master       +29 logLow  +30 logHigh
 *     +31 hasCurves (0/1 — any of curveY/R/G/B non-identity)
 *     +32 hasLut (0/1 — lutId != null AND lutMix > 0.0001f, core's gate)
 *     +33 lutMix (0..1; the LUT's mix into the node's DI value)
 *     +34 lutHandle (NodeLutRegistry handle, F1-8d; 0 when hasLut = 0)
 *   then the curve block, node by node in chain order, ONLY for nodes
 *     with hasCurves=1:
 *     per node: [channelMask, then per PRESENT channel in Y,R,G,B
 *     order: pointCount, x0, y0, x1, y1, ...] — channelMask is the bit
 *     set of the NON-identity channels (Y=1, R=2, G=4, B=8); identity
 *     channels (the two-point [[0,0],[1,1]] default) SKIP the block
 *     (nodeCurveTable's identity output makes a skipped channel == an
 *     identity one), so [2] counts only the pairs actually carried.
 *   [tail] the OpenDRT output block, 6 slots (the OpenDrtSpec ordinals —
 *          look, tonescale, cwp, cwpLimit, grey, display; IGNORED while
 *          outputMode is 0, always present so the length contract is
 *          one formula).
 *
 * Node LUTs (F1-8d) ride the record as a small HANDLE (+34): the cube is
 * registered natively once (NodeLutHandles / NodeLutRegistry, parsed by
 * core's parseCubeFile) and decodeNodeChainWire turns the handle back into
 * the registry-owned Lut the core GradeNode borrows. A LUT that cannot be
 * resolved (no resolver, missing file, parse failure) is refused honestly
 * by [encode] (it throws), never silently dropped.
 *
 * WIRE RULES (GradeParcel's own): append-only slots, booleans 0.0/1.0,
 * NaN/Infinity refused, the length and [0]/[2] must AGREE (a miscounted
 * wire is a refusal, never a misread).
 */
object NodeChainWire {

    const val HEADER = 3
    const val STRIDE = 35
    const val OUTPUT = 6

    // Per-node record slots (STRIDE of them; NodeChainWire.h mirrors).
    const val SLOT_ENABLED = 0
    const val SLOT_MIX = 1
    const val SLOT_LIFT = 2
    const val SLOT_GAMMA = 5
    const val SLOT_GAIN = 8
    const val SLOT_OFFSET = 11
    const val SLOT_CONTRAST = 14
    const val SLOT_PIVOT = 15
    const val SLOT_SAT = 16
    const val SLOT_HUE = 17
    const val SLOT_TEMP = 18
    const val SLOT_TINT = 19
    const val SLOT_LOG_SHADOW = 20
    const val SLOT_LOG_MID = 23
    const val SLOT_LOG_HI = 26
    const val SLOT_LOG_LOW = 29
    const val SLOT_LOG_HIGH = 30
    const val SLOT_HAS_CURVES = 31
    const val SLOT_HAS_LUT = 32
    const val SLOT_LUT_MIX = 33
    const val SLOT_LUT_HANDLE = 34

    // The output block's slots (HEADER + n * STRIDE + curve block).
    const val SLOT_OUT_LOOK = 0
    const val SLOT_OUT_TONESCALE = 1
    const val SLOT_OUT_CWP = 2
    const val SLOT_OUT_CWP_LIMIT = 3
    const val SLOT_OUT_GREY = 4
    const val SLOT_OUT_DISPLAY = 5

    /** The N1 caps (rawedit kMaxNodes; the LUT cap rides the refused leg). */
    const val MAX_NODES = 8

    /**
     * Encodes one NODES-usage grade's chain wire. [nodes] must be the
     * TRIMMED chain ([GradeV2Usage.activeNodes]) and the grade must be
     * NODES usage — the callers route on the usage verdict first; this
     * encoder is the NODES branch's packer and refuses (never truncates)
     * anything it cannot carry honestly:
     *   - a chain past the N1 cap;
     *   - a node whose LUT cannot be resolved to a native handle (F1-8d).
     */
    fun encode(grade: ClipGradeV2, nodes: List<GradeNodeV2>): FloatArray {
        if (nodes.size > MAX_NODES) {
            refuse("node chain wire: ${nodes.size} nodes exceed the N1 cap $MAX_NODES (recovery: the editor caps the chain at 8)")
        }
        val lutHandles = IntArray(nodes.size)
        nodes.forEachIndexed { i, n ->
            val id = n.lutId
            if (id != null && n.lutMix > 0.0001f) {
                val h = NodeLutHandles.handleFor(id)
                if (h <= 0) {
                    refuse("node chain wire: node $i names LUT $id which could not be loaded (recovery: re-import the .cube file or clear the node's LUT)")
                }
                lutHandles[i] = h
            }
        }
        // The curve block: per node with hasCurves, [mask, per-present-
        // channel [count, pairs...]] in Y,R,G,B order; identity channels
        // skip (core's nodeCurveTable is the identity over 0..1 for them
        // either way).
        data class Chan(val maskBit: Int, val label: String, val curve: NodeCurveV2)
        val curveChannels = ArrayList<List<Chan>>(nodes.size)
        var pairTotal = 0
        for (n in nodes) {
            val chans = listOf(
                Chan(1, "curveY", n.curveY), Chan(2, "curveR", n.curveR),
                Chan(4, "curveG", n.curveG), Chan(8, "curveB", n.curveB),
            ).filter { !it.curve.isIdentity() }
            curveChannels += chans
            for (c in chans) {
                if (c.curve.points.size < 2 || c.curve.points.size > 64) {
                    refuse("node chain wire: curve ${c.label} has ${c.curve.points.size} control points (min 2, max 64)")
                }
                pairTotal += c.curve.points.size
            }
        }
        // Only nodes WITH a non-identity curve carry a block (the writer below skips the rest): counting the mask
        // slot for every node over-allocated one float per curveless node and the native decoder refused the wire
        // ("1 float(s) left before the output block"), so a chain of plain nodes never reached the GPU.
        val curveSlots = curveChannels.filter { it.isNotEmpty() }.sumOf { chans ->
            1 + chans.sumOf { c -> 1 + c.curve.points.size * 2 }
        }
        val out = FloatArray(HEADER + nodes.size * STRIDE + curveSlots + OUTPUT)
        out[0] = nodes.size.toFloat()
        out[1] = if (grade.output.mode == OutputTransformV2.MODE_OPENDRT) 1.0f else 0.0f
        out[2] = pairTotal.toFloat()
        nodes.forEachIndexed { i, n ->
            val b = HEADER + i * STRIDE
            out[b + SLOT_ENABLED] = if (n.enabled) 1.0f else 0.0f
            out[b + SLOT_MIX] = n.mix
            fun wheel(slot: Int, w: NodeWheelV2) {
                out[b + slot] = w.x; out[b + slot + 1] = w.y; out[b + slot + 2] = w.master
            }
            wheel(SLOT_LIFT, n.lift)
            wheel(SLOT_GAMMA, n.gamma)
            wheel(SLOT_GAIN, n.gain)
            wheel(SLOT_OFFSET, n.offset)
            out[b + SLOT_CONTRAST] = n.contrast
            out[b + SLOT_PIVOT] = n.pivot
            out[b + SLOT_SAT] = n.sat
            out[b + SLOT_HUE] = n.hue
            out[b + SLOT_TEMP] = n.temp
            out[b + SLOT_TINT] = n.tint
            wheel(SLOT_LOG_SHADOW, n.logShadow)
            wheel(SLOT_LOG_MID, n.logMid)
            wheel(SLOT_LOG_HI, n.logHi)
            out[b + SLOT_LOG_LOW] = n.logLow
            out[b + SLOT_LOG_HIGH] = n.logHigh
            out[b + SLOT_HAS_CURVES] = if (n.curveY.isIdentity() && n.curveR.isIdentity() &&
                n.curveG.isIdentity() && n.curveB.isIdentity()
            ) 0.0f else 1.0f
            out[b + SLOT_HAS_LUT] = if (lutHandles[i] > 0) 1.0f else 0.0f
            out[b + SLOT_LUT_MIX] = n.lutMix.coerceIn(0f, 1f)
            out[b + SLOT_LUT_HANDLE] = lutHandles[i].toFloat()
        }
        // The curve block, then the output tail.
        var p = HEADER + nodes.size * STRIDE
        for (chans in curveChannels) {
            if (chans.isEmpty()) continue // hasCurves=0 nodes carry nothing
            out[p++] = chans.sumOf { n -> n.maskBit }.toFloat()
            for (c in chans) {
                out[p++] = c.curve.points.size.toFloat()
                for ((x, y) in c.curve.points) {
                    out[p++] = x
                    out[p++] = y
                }
            }
        }
        val o = HEADER + nodes.size * STRIDE + curveSlots
        val drt = grade.output.opendrt
        out[o + SLOT_OUT_LOOK] = drt.look.toFloat()
        out[o + SLOT_OUT_TONESCALE] = drt.tonescale.toFloat()
        out[o + SLOT_OUT_CWP] = drt.cwp.toFloat()
        out[o + SLOT_OUT_CWP_LIMIT] = drt.cwpLimit
        out[o + SLOT_OUT_GREY] = drt.grey
        out[o + SLOT_OUT_DISPLAY] = drt.display.toFloat()
        return out
    }

    /**
     * The chain hash: the FNV-1a content key the GPU seam threads as the
     * revision cache's key (setColorModelChain's chainHash — "the same
     * values in the same order hash the same"). Computed over the WIRE
     * ITSELF (the floats' bit patterns), so trimmed-chain equality is
     * wire equality and the cache's contract is exactly the encode's.
     */
    fun chainHash(wire: FloatArray): Long {
        var h = 1469598103934665603UL
        for (f in wire) {
            val bits = f.toRawBits()
            for (i in 0 until 4) {
                h = h xor (((bits ushr (8 * i)) and 0xFF).toULong())
                h *= 1099511628211UL
            }
        }
        return h.toLong()
    }

    private fun refuse(message: String): Nothing = throw IllegalStateException(message)
}
