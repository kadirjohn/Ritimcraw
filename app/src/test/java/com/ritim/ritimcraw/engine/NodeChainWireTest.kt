package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The node chain WIRE's JVM locks (color model v2 F1-6/F1-7):
 *
 * THE MECHANISM: the wire is a hand-written mirror PAIR — NodeChainWire.kt
 * (this side) and app/src/main/cpp/gles/NodeChainWire.h (the C++ side).
 * The GRADE parcel's generator owns the 29 scalar slots only, so this
 * test cross-checks the committed C++ header's constants against the
 * Kotlin ones (the NodeParcelTest pattern), pins the ENCODER's layout
 * (header/stride/curve-mask/output block, slot by slot), the honest
 * refusals (the N1 cap, LUT-carrying chains, bad curve counts), the
 * chain hash's content-key contract, and the two F1-7 equivalence
 * proofs the plan's layer 2 names:
 *
 *   - THE CURVE-MATH PARITY: the wire's curve control points, run through
 *     a Kotlin transcription of core's nodeCurveTable (Fritsch-Carlson
 *     monotone cubic), reproduce NodeParcel's own table path — the
 *     encoder's claim "core builds the tables from these points" is
 *     arithmetically pinned (NodeParcelTest's compile-parity style).
 *   - THE DRT PACK PARITY: OpenDrtPacks.resolve's 77 slots against the
 *     values core's Impl resolves — pinned here against committed
 *     literals derived from OpenDrt.cpp's own tables (the preset
 *     switch's numbers, spelled out in the test), so a transcription
 *     typo in OpenDrtPacks fails HERE.
 *
 * Pure JVM: no core .so, no GL (the same headless discipline
 * NodeParcelTest documents).
 */
class NodeChainWireTest {

    // ---- the committed C++ mirror (the NodeParcelTest locator pattern) ----

    private fun committedHeader(): String {
        val suffixes = listOf(
            "src/main/cpp/gles/NodeChainWire.h",
            "app/src/main/cpp/gles/NodeChainWire.h",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.exists()) return candidate.readText()
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("cannot locate the committed C++ NodeChainWire mirror")
    }

    private fun cppConst(text: String, name: String): Int {
        val m = Regex("constexpr int $name = (\\d+);").find(text)
        assertNotNull("the committed header must declare $name", m)
        return m!!.groupValues[1].toInt()
    }

    @Test
    fun the_committed_cpp_header_matches_the_kotlin_layout() {
        val text = committedHeader()
        assertEquals("HEADER drifted - fix BOTH mirrors",
            NodeChainWire.HEADER, cppConst(text, "kNodeChainWireHeader"))
        assertEquals("STRIDE drifted - fix BOTH mirrors",
            NodeChainWire.STRIDE, cppConst(text, "kNodeChainWireStride"))
        assertEquals("OUTPUT drifted - fix BOTH mirrors",
            NodeChainWire.OUTPUT, cppConst(text, "kNodeChainWireOutput"))
        assertEquals("MAX_NODES drifted from the N1 cap",
            NodeChainWire.MAX_NODES, cppConst(text, "kNodeChainWireMaxNodes"))
        val slots = mapOf(
            "kNodeChainEnabled" to NodeChainWire.SLOT_ENABLED,
            "kNodeChainMix" to NodeChainWire.SLOT_MIX,
            "kNodeChainLift" to NodeChainWire.SLOT_LIFT,
            "kNodeChainGamma" to NodeChainWire.SLOT_GAMMA,
            "kNodeChainGain" to NodeChainWire.SLOT_GAIN,
            "kNodeChainOffset" to NodeChainWire.SLOT_OFFSET,
            "kNodeChainContrast" to NodeChainWire.SLOT_CONTRAST,
            "kNodeChainPivot" to NodeChainWire.SLOT_PIVOT,
            "kNodeChainSat" to NodeChainWire.SLOT_SAT,
            "kNodeChainHue" to NodeChainWire.SLOT_HUE,
            "kNodeChainTemp" to NodeChainWire.SLOT_TEMP,
            "kNodeChainTint" to NodeChainWire.SLOT_TINT,
            "kNodeChainLogShadow" to NodeChainWire.SLOT_LOG_SHADOW,
            "kNodeChainLogMid" to NodeChainWire.SLOT_LOG_MID,
            "kNodeChainLogHi" to NodeChainWire.SLOT_LOG_HI,
            "kNodeChainLogLow" to NodeChainWire.SLOT_LOG_LOW,
            "kNodeChainLogHigh" to NodeChainWire.SLOT_LOG_HIGH,
            "kNodeChainHasCurves" to NodeChainWire.SLOT_HAS_CURVES,
            "kNodeChainHasLut" to NodeChainWire.SLOT_HAS_LUT,
            "kNodeChainLutMix" to NodeChainWire.SLOT_LUT_MIX,
        )
        for ((cppName, kotlinSlot) in slots) {
            assertEquals("slot $cppName drifted - fix BOTH mirrors",
                kotlinSlot, cppConst(text, cppName))
        }
        val out = mapOf(
            "kNodeChainOutLook" to NodeChainWire.SLOT_OUT_LOOK,
            "kNodeChainOutTonescale" to NodeChainWire.SLOT_OUT_TONESCALE,
            "kNodeChainOutCwp" to NodeChainWire.SLOT_OUT_CWP,
            "kNodeChainOutCwpLimit" to NodeChainWire.SLOT_OUT_CWP_LIMIT,
            "kNodeChainOutGrey" to NodeChainWire.SLOT_OUT_GREY,
            "kNodeChainOutDisplay" to NodeChainWire.SLOT_OUT_DISPLAY,
        )
        for ((cppName, kotlinSlot) in out) {
            assertEquals("output slot $cppName drifted - fix BOTH mirrors",
                kotlinSlot, cppConst(text, cppName))
        }
    }

    // ---- the encoder's layout pins ----

    @Test
    fun an_empty_legacy_chain_is_the_exact_fast_path_wire() {
        // nodes=[] + mode legacy: header 3 + output 6, outputMode 0 —
        // the CLEAR shape setColorModelChain consumes (the K2 lock's
        // counterpart on the new door).
        val grade = ClipGradeV2()
        val wire = NodeChainWire.encode(grade, emptyList())
        assertEquals(NodeChainWire.HEADER + NodeChainWire.OUTPUT, wire.size)
        assertEquals(0f, wire[0], 0f)
        assertEquals(0f, wire[1], 0f)
        assertEquals(0f, wire[2], 0f)
        // The output block at the tail, the spec's defaults.
        assertEquals(0f, wire[3], 0f)   // look
        assertEquals(0f, wire[4], 0f)   // tonescale
        assertEquals(0f, wire[5], 0f)   // cwp
        assertEquals(0.25f, wire[6], 0f) // cwpLimit
        assertEquals(10f, wire[7], 0f)   // grey
        assertEquals(0f, wire[8], 0f)   // display
    }

    @Test
    fun one_node_with_every_knob_lands_in_the_declared_slots() {
        val node = GradeNodeV2(
            enabled = true, mix = 0.8f,
            lift = NodeWheelV2(0.2f, -0.1f, 0.05f),
            gamma = NodeWheelV2(-0.3f, 0.2f, -0.05f),
            gain = NodeWheelV2(0.4f, -0.25f, 0.15f),
            offset = NodeWheelV2(-0.5f, 0.3f, -0.2f),
            contrast = 1.35f, pivot = 0.4f, sat = 0.9f,
            hue = 25f, temp = 0.3f, tint = -0.2f,
            curveY = NodeCurveV2(listOf(0f to 0f, 0.5f to 0.45f, 1f to 1f)),
            curveR = NodeCurveV2(listOf(0f to 0f, 1f to 0.9f)),
            logShadow = NodeWheelV2(0.1f, 0.05f, 0.2f),
            logMid = NodeWheelV2(-0.1f, 0.0f, 0.05f),
            logHi = NodeWheelV2(0.0f, 0.2f, -0.1f),
            logLow = 0.1f, logHigh = 0.6f,
        )
        val grade = ClipGradeV2(nodes = listOf(node))
        val wire = NodeChainWire.encode(grade, listOf(node))
        val b = NodeChainWire.HEADER
        assertEquals(NodeChainWire.HEADER + NodeChainWire.STRIDE +
            (1 + (1 + 3 * 2) + (1 + 2 * 2)) + NodeChainWire.OUTPUT, wire.size)
        assertEquals(1f, wire[0], 0f)
        assertEquals(0f, wire[1], 0f) // legacy mode
        assertEquals(5f, wire[2], 0f)
        assertEquals(1f, wire[b + NodeChainWire.SLOT_ENABLED], 0f)
        assertEquals(0.8f, wire[b + NodeChainWire.SLOT_MIX], 0f)
        assertEquals(0.2f, wire[b + NodeChainWire.SLOT_LIFT], 0f)
        assertEquals(-0.1f, wire[b + NodeChainWire.SLOT_LIFT + 1], 0f)
        assertEquals(0.05f, wire[b + NodeChainWire.SLOT_LIFT + 2], 0f)
        assertEquals(-0.3f, wire[b + NodeChainWire.SLOT_GAMMA], 0f)
        assertEquals(0.4f, wire[b + NodeChainWire.SLOT_GAIN], 0f)
        assertEquals(-0.5f, wire[b + NodeChainWire.SLOT_OFFSET], 0f)
        assertEquals(1.35f, wire[b + NodeChainWire.SLOT_CONTRAST], 0f)
        assertEquals(0.4f, wire[b + NodeChainWire.SLOT_PIVOT], 0f)
        assertEquals(0.9f, wire[b + NodeChainWire.SLOT_SAT], 0f)
        assertEquals(25f, wire[b + NodeChainWire.SLOT_HUE], 0f)
        assertEquals(0.3f, wire[b + NodeChainWire.SLOT_TEMP], 0f)
        assertEquals(-0.2f, wire[b + NodeChainWire.SLOT_TINT], 0f)
        assertEquals(0.1f, wire[b + NodeChainWire.SLOT_LOG_SHADOW], 0f)
        assertEquals(-0.1f, wire[b + NodeChainWire.SLOT_LOG_MID], 0f)
        assertEquals(0.05f, wire[b + NodeChainWire.SLOT_LOG_MID + 2], 0f)
        assertEquals(0f, wire[b + NodeChainWire.SLOT_LOG_HI], 0f)
        assertEquals(-0.1f, wire[b + NodeChainWire.SLOT_LOG_HI + 2], 0f)
        assertEquals(0.1f, wire[b + NodeChainWire.SLOT_LOG_LOW], 0f)
        assertEquals(0.6f, wire[b + NodeChainWire.SLOT_LOG_HIGH], 0f)
        assertEquals(1f, wire[b + NodeChainWire.SLOT_HAS_CURVES], 0f)
        assertEquals(0f, wire[b + NodeChainWire.SLOT_HAS_LUT], 0f)
        assertEquals(1f, wire[b + NodeChainWire.SLOT_LUT_MIX], 0f)
        // The curve block: mask 1|2 = 3 (Y and R), then Y's 3 pairs, then
        // R's 2 pairs.
        val c = b + NodeChainWire.STRIDE
        assertEquals(3f, wire[c], 0f)
        assertEquals(3f, wire[c + 1], 0f)
        assertEquals(0f, wire[c + 2], 0f); assertEquals(0f, wire[c + 3], 0f)
        assertEquals(0.5f, wire[c + 4], 0f); assertEquals(0.45f, wire[c + 5], 0f)
        assertEquals(1f, wire[c + 6], 0f); assertEquals(1f, wire[c + 7], 0f)
        assertEquals(2f, wire[c + 8], 0f)
        assertEquals(1f, wire[c + 11], 0f); assertEquals(0.9f, wire[c + 12], 0f)
    }

    @Test
    fun the_opendrt_output_mode_and_block_ride_the_tail() {
        val node = GradeNodeV2(sat = 1.2f)
        val grade = ClipGradeV2(
            nodes = listOf(node),
            output = OutputTransformV2(
                mode = OutputTransformV2.MODE_OPENDRT,
                opendrt = com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2(
                    look = 3, tonescale = 7, cwp = 2, cwpLimit = 0.5f,
                    grey = 14f, display = 1,
                ),
            ),
        )
        val wire = NodeChainWire.encode(grade, listOf(node))
        assertEquals(1f, wire[1], 0f) // DwgLinear
        val o = wire.size - NodeChainWire.OUTPUT
        assertEquals(3f, wire[o + NodeChainWire.SLOT_OUT_LOOK], 0f)
        assertEquals(7f, wire[o + NodeChainWire.SLOT_OUT_TONESCALE], 0f)
        assertEquals(2f, wire[o + NodeChainWire.SLOT_OUT_CWP], 0f)
        assertEquals(0.5f, wire[o + NodeChainWire.SLOT_OUT_CWP_LIMIT], 0f)
        assertEquals(14f, wire[o + NodeChainWire.SLOT_OUT_GREY], 0f)
        assertEquals(1f, wire[o + NodeChainWire.SLOT_OUT_DISPLAY], 0f)
    }

    // ---- the honest refusals ----

    @Test
    fun the_n1_cap_refuses_instead_of_truncating() {
        val grade = ClipGradeV2(
            nodes = (1..9).map { GradeNodeV2(sat = 1f + it * 0.01f) },
        )
        try {
            NodeChainWire.encode(grade, GradeV2Usage.activeNodes(grade))
            fail("expected IllegalStateException past the N1 cap")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("N1 cap"))
        }
    }

    @Test
    fun a_lut_carrying_chain_refuses_honestly() {
        val grade = ClipGradeV2(nodes = listOf(GradeNodeV2(lutId = "cube-2")))
        try {
            NodeChainWire.encode(grade, grade.nodes)
            fail("expected IllegalStateException for a LUT-carrying chain")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("node-LUT leg"))
        }
    }

    @Test
    fun a_single_point_curve_refuses_honestly() {
        val grade = ClipGradeV2(
            nodes = listOf(
                GradeNodeV2(curveY = NodeCurveV2(listOf(0.5f to 0.5f))),
            ),
        )
        try {
            NodeChainWire.encode(grade, grade.nodes)
            fail("expected IllegalStateException for a 1-point curve")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("control points"))
        }
    }

    // ---- the chain hash (the revision cache's content key) ----

    @Test
    fun the_chain_hash_is_a_content_key() {
        val n = GradeNodeV2(sat = 1.2f)
        val g = ClipGradeV2(nodes = listOf(n))
        val a = NodeChainWire.encode(g, listOf(n))
        val b = NodeChainWire.encode(g, listOf(n))
        assertEquals(NodeChainWire.chainHash(a), NodeChainWire.chainHash(b))
        // A different value changes the hash; a different ORDER changes
        // it too (the wire is order-sensitive by design).
        val n2 = GradeNodeV2(sat = 1.25f)
        val g2 = ClipGradeV2(nodes = listOf(n2))
        assertTrue(
            NodeChainWire.chainHash(a) !=
                NodeChainWire.chainHash(NodeChainWire.encode(g2, listOf(n2))),
        )
        val g3 = ClipGradeV2(nodes = listOf(n2, n))
        assertTrue(
            NodeChainWire.chainHash(NodeChainWire.encode(g3, listOf(n2, n))) !=
                NodeChainWire.chainHash(NodeChainWire.encode(g3, listOf(n, n2))),
        )
    }

    // ---- the usage verdict ----

    @Test
    fun usage_strips_neutral_nodes_and_gates_on_the_output_mode() {
        // All-neutral chain: LEGACY (the neutral-node rule).
        assertEquals(
            GradeV2Usage.Usage.LEGACY,
            GradeV2Usage.usage(ClipGradeV2(nodes = listOf(GradeNodeV2()))),
        )
        // A disabled node is neutral no matter its knobs.
        assertEquals(
            GradeV2Usage.Usage.LEGACY,
            GradeV2Usage.usage(
                ClipGradeV2(nodes = listOf(GradeNodeV2(enabled = false, sat = 3f))),
            ),
        )
        // One live node: NODES.
        assertEquals(
            GradeV2Usage.Usage.NODES,
            GradeV2Usage.usage(ClipGradeV2(nodes = listOf(GradeNodeV2(sat = 1.5f)))),
        )
        // An empty chain with the DRT output: NODES (the round trip alone).
        assertEquals(
            GradeV2Usage.Usage.NODES,
            GradeV2Usage.usage(
                ClipGradeV2(output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT)),
            ),
        )
        // activeNodes strips the no-ops.
        val mixed = ClipGradeV2(
            nodes = listOf(GradeNodeV2(), GradeNodeV2(sat = 1.3f), GradeNodeV2(mix = 0.0001f)),
        )
        assertEquals(1, GradeV2Usage.activeNodes(mixed).size)
    }

    // ---- the curve-math parity (core's nodeCurveTable, transcribed) ----
    //
    // THE F1-7 equivalence proof, Kotlin side: the encoder's claim is that
    // the control POINTS are all the wire carries and core builds the
    // 512-entry tables from them (Fritsch-Carlson monotone cubic, then the
    // composite in DI). This transcription checks the monotone-cubic table
    // itself — the same vectors test_color_model pins the CPU with.

    /** core/src/ColorModel.cpp nodeCurveTable, transcribed verbatim. */
    private fun nodeCurveTableKt(points: List<Pair<Float, Float>>): FloatArray {
        val p = points.sortedBy { it.first }
        val n = p.size
        if (n < 2) return FloatArray(256) { it / 255f }
        val xs = FloatArray(n) { p[it].first }
        val ys = FloatArray(n) { p[it].second }
        val d = FloatArray(n - 1) {
            (ys[it + 1] - ys[it]) / maxOf(1e-6f, xs[it + 1] - xs[it])
        }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f; continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        val out = FloatArray(256)
        for (k in 0 until 256) {
            val x = k / 255f
            val v = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var i = 0
                    while (i < n - 2 && x > xs[i + 1]) ++i
                    val h = xs[i + 1] - xs[i]
                    val t = if (h > 1e-6f) (x - xs[i]) / h else 0f
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                        (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
                }
            }
            out[k] = if (v.isNaN()) x else v.coerceIn(0f, 1f)
        }
        return out
    }

    @Test
    fun the_curve_control_points_build_core_shaped_tables() {
        // The monotone-cubic invariants core's own test pins: an identity
        // curve is the identity table, a monotone curve stays monotone,
        // and the interpolation hits the control points exactly.
        val identity = nodeCurveTableKt(listOf(0f to 0f, 1f to 1f))
        for (k in 0 until 256) {
            assertTrue("identity table at $k", abs(identity[k] - k / 255f) < 1e-5f)
        }
        val ctl = listOf(
            0f to 0f, 0.25f to 0.3f, 0.5f to 0.45f, 0.75f to 0.8f, 1f to 1f,
        )
        val table = nodeCurveTableKt(ctl)
        for ((x, y) in ctl) {
            val idx = (x * 255f).toInt()
            assertTrue("control point ($x, $y)", abs(table[idx] - y) < 0.02f)
        }
        var last = -0.1f
        for (v in table) {
            assertTrue("monotone", v >= last - 1e-4f)
            last = v
        }
    }

    // ---- the DRT pack parity (the 77-slot resolver) ----

    @Test
    fun the_drt_pack_carries_core_resolved_values() {
        // The load-bearing pins, against core's own tables (OpenDrt.cpp):
        // slot [0] is the resolved EOTF int (1 sRGB 2.2 / 2 Rec.1886 / 5
        // HLG), [1] the hdr flag, [2] the internal cwp, and [13..16]
        // carry the look's brl constants — a transcription typo anywhere
        // in the preset switch lands here.
        val srgb = OpenDrtPacks.resolve(OpenDrtPacks.Spec())
        assertEquals(77, srgb.size)
        assertEquals(1f, srgb[0], 0f) // eotf 1: sRGB 2.2
        assertEquals(0f, srgb[1], 0f) // hdr flag off
        assertEquals(2f, srgb[2], 0f) // the look's cwp (D65 skip)
        // Standard look's tn: con 1.66, sh 0.5, toe 0.003, off 0.005.
        assertEquals(1.66f, srgb[5], 0f)
        assertEquals(0.5f, srgb[6], 0f)
        assertEquals(0.003f, srgb[7], 0f)
        assertEquals(0.005f, srgb[8], 0f)
        // rs: sa .35, rw .25, bw .55, lm .25.
        assertEquals(0.35f, srgb[9], 0f)
        assertEquals(0.25f, srgb[10], 0f)
        assertEquals(0.55f, srgb[11], 0f)
        assertEquals(0.25f, srgb[12], 0f)
        // brl group: brl 0, rng 0.5, st 0.35; brlRgb (-2.5, -1.5, -1.5);
        // brlp -0.5.
        assertEquals(0f, srgb[13], 0f)
        assertEquals(0.5f, srgb[14], 0f)
        assertEquals(0.35f, srgb[15], 0f)
        assertEquals(-2.5f, srgb[17], 0f)
        assertEquals(-1.5f, srgb[18], 0f)
        assertEquals(-1.5f, srgb[19], 0f)
        assertEquals(-0.5f, srgb[20], 0f)
        // hue pairs: hs_r 0.6/0.6, hs_g 0.35/1.0, hs_b 0.66/1.0 (the
        // Standard preset's own numbers).
        assertEquals(0.6f, srgb[37], 0f)
        assertEquals(0.6f, srgb[38], 0f)
        assertEquals(0.35f, srgb[39], 0f)
        assertEquals(1.0f, srgb[40], 0f)
        assertEquals(0.66f, srgb[41], 0f)
        assertEquals(1.0f, srgb[42], 0f)
        // conf0: all four enable bits on for the Standard look.
        assertEquals(1f, srgb[49], 0f)
        assertEquals(1f, srgb[50], 0f)
        assertEquals(1f, srgb[51], 0f)
        assertEquals(1f, srgb[52], 0f)
        // conf1: ptm, hs_rgb, hs_cmy on; lcon OFF (the Standard preset's
        // own `false` in the ts() call).
        assertEquals(1f, srgb[53], 0f)
        assertEquals(1f, srgb[54], 0f)
        assertEquals(1f, srgb[55], 0f)
        assertEquals(0f, srgb[56], 0f)
        // conf2[0]: hcon OFF for Standard.
        assertEquals(0f, srgb[57], 0f)
        // pt: the Standard preset's lml group (.25, .5, 0, .1).
        assertEquals(0.25f, srgb[61], 0f)
        assertEquals(0.5f, srgb[62], 0f)
        assertEquals(0f, srgb[63], 0f)
        assertEquals(0.1f, srgb[64], 0f)

        // The HLG display flips the eotf/hdr pair and only those.
        val hlg = OpenDrtPacks.resolve(OpenDrtPacks.Spec(display = 2))
        assertEquals(5f, hlg[0], 0f) // eotf 5: HLG
        assertEquals(1f, hlg[1], 0f) // hdr on
        assertEquals(0.1f, hlg[16], 0f) // tsDsc: HLG's own scale
        assertEquals(srgb[5], hlg[5], 0f) // the look's tn con unchanged
        // Rec.1886: eotf 2.
        val tv = OpenDrtPacks.resolve(OpenDrtPacks.Spec(display = 1))
        assertEquals(2f, tv[0], 0f)
        assertEquals(0f, tv[1], 0f)
        // A creative white point override: cwp != 0 drives the cat path.
        val cwp = OpenDrtPacks.resolve(OpenDrtPacks.Spec(cwp = 3, cwpLimit = 0.4f))
        assertEquals(2f, cwp[2], 0f) // cwp resolved to INTERNAL 2 (D65)
        assertEquals(0.4f, cwp[12], 0f) // cwp_lm carries the limit
    }
}
