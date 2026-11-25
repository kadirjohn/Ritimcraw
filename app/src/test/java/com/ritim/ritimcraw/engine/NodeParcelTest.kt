package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The NodeParcel locks (color model v2 F1-5).
 *
 * THE MECHANISM: the node chain's parcel is a hand-written mirror PAIR —
 * NodeParcel.kt (this side) and app/src/main/cpp/gles/NodeParcel.h (the
 * C++ side). The GRADE parcel's generator owns only the 30 scalar grade
 * slots (the inventory §4.1: the generator is out of scope for F1-5), so
 * this test cross-checks the committed C++ header's constants against the
 * Kotlin ones, the GradeParcelTest mechanism.
 *
 * WHAT THIS FILE LOCKS:
 *   - the layout mirrors: HEADER/STRIDE/OUTPUT, the per-node slot offsets
 *     and the N1 caps, all read from the committed C++ header by name;
 *   - the compile-parity vectors: NodeParcel.compileNode's outputs against
 *     rawedit::ColorModel's own numbers (the closed-form values
 *     test_color_model pins the CPU to — the same wheel/primaries/hue/WB
 *     math, derived independently in Kotlin and compared);
 *   - the round trips: encode -> validate for empty chains, full chains,
 *     LUT-carrying chains; the wire-order spot locks;
 *   - the guards: the honest refusals (too many nodes, too many LUTs, a
 *     boolean outside 0/1, a lutIndex out of range, non-finite slots, a
 *     length that disagrees with nodeCount).
 *
 * Pure JVM: no core .so, no GL. The headless GLSL-parity proof of the
 * shader MATH is the desktop suite (core/tests/test_gpu_nodes_logic.cpp);
 * this file proves the SEAM.
 */
class NodeParcelTest {

    // ---- the committed C++ mirror (GradeParcelTest's locator pattern) ----

    private fun committedHeader(): String {
        val suffixes = listOf(
            "src/main/cpp/gles/NodeParcel.h",
            "app/src/main/cpp/gles/NodeParcel.h",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.exists()) {
                    return candidate.readText()
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the committed C++ NodeParcel mirror; tried " +
                candidates.joinToString(", ") { it.absolutePath } +
                " (working dir " + File(".").absolutePath + ")",
        )
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
            NodeParcel.HEADER, cppConst(text, "kNodeParcelHeader"))
        assertEquals("STRIDE drifted - fix BOTH mirrors",
            NodeParcel.STRIDE, cppConst(text, "kNodeParcelStride"))
        assertEquals("OUTPUT drifted - fix BOTH mirrors",
            NodeParcel.OUTPUT, cppConst(text, "kNodeParcelOutputHeader"))
        assertEquals("MAX_NODES drifted from the N1 cap",
            NodeParcel.MAX_NODES, cppConst(text, "kNodeParcelMaxNodes"))
        assertEquals("MAX_NODE_LUTS drifted from the N1 cap",
            NodeParcel.MAX_NODE_LUTS, cppConst(text, "kNodeParcelMaxNodeLuts"))
        // The per-node slot offsets, by name, one for one.
        val slots = mapOf(
            "kNodeParcelEnabled" to NodeParcel.SLOT_ENABLED,
            "kNodeParcelMix" to NodeParcel.SLOT_MIX,
            "kNodeParcelLift" to NodeParcel.SLOT_LIFT,
            "kNodeParcelGammaExp" to NodeParcel.SLOT_GAMMA_EXP,
            "kNodeParcelGain" to NodeParcel.SLOT_GAIN,
            "kNodeParcelOffset" to NodeParcel.SLOT_OFFSET,
            "kNodeParcelYSide" to NodeParcel.SLOT_Y_SIDE,
            "kNodeParcelContrast" to NodeParcel.SLOT_CONTRAST,
            "kNodeParcelPivot" to NodeParcel.SLOT_PIVOT,
            "kNodeParcelWb" to NodeParcel.SLOT_WB,
            "kNodeParcelHueMat" to NodeParcel.SLOT_HUE_MAT,
            "kNodeParcelSat" to NodeParcel.SLOT_SAT,
            "kNodeParcelHasCurves" to NodeParcel.SLOT_HAS_CURVES,
            "kNodeParcelHasLut" to NodeParcel.SLOT_HAS_LUT,
            "kNodeParcelLutIndex" to NodeParcel.SLOT_LUT_INDEX,
            "kNodeParcelLutMix" to NodeParcel.SLOT_LUT_MIX,
        )
        for ((cppName, kotlinSlot) in slots) {
            assertEquals("slot $cppName drifted - fix BOTH mirrors",
                kotlinSlot, cppConst(text, cppName))
        }
        // Also the typo'd C++ constant must not exist (a clean rename).
        assertTrue(
            "the C++ mirror carries the stale kNodeParceEnabled typo",
            !text.contains("kNodeParceEnabled"),
        )
    }

    // ---- the compile-parity vectors (rawedit::ColorModel's own numbers) ----
    //
    // These are the closed-form values test_color_model.cpp derives for the
    // CPU applyNodeDi vectors, recomputed through NodeParcel.compileNode so
    // the Kotlin seam is pinned to the same math (the mission's "parity of
    // param mapping vs ColorModel CPU constants").

    @Test
    fun wheelRgb_matches_the_cpu_trigram_formula() {
        // ColorModel.cpp:44-55 verbatim: the 120-apart cosines with the
        // 2/3 magnitude scale. A pure 3-o'clock puck is (2/3, -1/3, -1/3).
        val out = FloatArray(3)
        NodeParcel.wheelRgb(1.0f, 0.0f, out)
        assertEquals(2.0f / 3.0f, out[0], 1e-6f)
        assertEquals(-1.0f / 3.0f, out[1], 1e-6f)
        assertEquals(-1.0f / 3.0f, out[2], 1e-6f)
        // A tiny puck is the zero trigram (the m < 1e-6 gate).
        NodeParcel.wheelRgb(1e-7f, 0.0f, out)
        assertEquals(0.0f, out[0], 0f)
        assertEquals(0.0f, out[1], 0f)
        assertEquals(0.0f, out[2], 0f)
        // A clamped magnitude 1 puck at 12 o'clock: (0, 2/3, 0) shifted by
        // the cos phases.
        NodeParcel.wheelRgb(0.0f, 5.0f, out)  // magnitude clamps to 1
        val th = PI.toFloat() / 2.0f
        assertEquals(2.0f / 3.0f * cos(th), out[0], 1e-6f)
        assertEquals(2.0f / 3.0f * cos(th - 2.0f * PI.toFloat() / 3.0f), out[1], 1e-6f)
        assertEquals(2.0f / 3.0f * cos(th + 2.0f * PI.toFloat() / 3.0f), out[2], 1e-6f)
    }

    @Test
    fun primaries_solve_matches_the_cpu_closed_form() {
        // ColorModel.cpp:57-64 verbatim, spot-pinned:
        // lift 0, gain 2, gamma 0 -> d = 1, black 0, slope 2, expo 1.
        var p = NodeParcel.primaries(0.0f, 2.0f, 0.0f)
        assertEquals(0.0f, p[0], 0f)
        assertEquals(2.0f, p[1], 0f)
        assertEquals(1.0f, p[2], 0f)
        // gamma > 0: expo = 1/(1+gamma).
        p = NodeParcel.primaries(0.0f, 1.0f, 3.0f)
        assertEquals(0.25f, p[2], 1e-6f)
        // gamma < 0: expo = 1 - gamma.
        p = NodeParcel.primaries(0.0f, 1.0f, -0.5f)
        assertEquals(1.5f, p[2], 1e-6f)
        // the d clamp: lift 0.9 gain 0.2 -> d = max(0.91, 0.05) = 0.91.
        p = NodeParcel.primaries(0.9f, 0.2f, 0.0f)
        val d = kotlin.math.max(1.0f + 0.9f * (0.2f - 1.0f), 0.05f)
        assertEquals(0.2f * 0.9f / d, p[0], 1e-6f)
        assertEquals(0.2f * (1.0f - 0.9f) / d, p[1], 1e-6f)
    }

    @Test
    fun compileNode_gain_master_matches_the_cpu_gain_vector() {
        // test_color_model's gain vector: gain.master = 0.5 -> slope 1.5,
        // so DI 0.2 -> 0.3, 0.4 -> 0.6, 0.6 -> 0.9 (the values are the CPU's
        // own closed form; the Kotlin seam must agree).
        val n = NodeParcel.compileNode(gainMaster = 0.5f)
        assertEquals(1.5f, n.gain[0], 1e-5f)
        assertEquals(1.5f, n.gain[1], 1e-5f)
        assertEquals(1.5f, n.gain[2], 1e-5f)
        assertEquals(0.0f, n.lift[0], 1e-6f)
        // offset.master = 0.2 -> +0.25 * 0.2 = 0.05.
        val o = NodeParcel.compileNode(offsetMaster = 0.2f)
        assertEquals(0.05f, o.offset[0], 1e-6f)
        // The default pivot is the DI code of 18% grey (kDiMidGray).
        assertEquals(NodeParcel.DI_MID_GRAY, o.pivot, 0f)
    }

    @Test
    fun compileNode_wb_temp_tint_match_the_cpu_vectors() {
        // ColorModel.cpp:129-131: wb = (1 + 0.18*temp, 1 - 0.12*tint, 1 - 0.18*temp).
        val t = NodeParcel.compileNode(temp = 0.5f)
        assertEquals(1.09f, t.wb[0], 1e-5f)
        assertEquals(1.0f, t.wb[1], 1e-6f)
        assertEquals(0.91f, t.wb[2], 1e-5f)
        val ti = NodeParcel.compileNode(tint = 0.5f)
        assertEquals(0.94f, ti.wb[1], 1e-5f)
    }

    @Test
    fun compileNode_hue_matrix_is_the_cpu_rotation() {
        // hue 120 degrees about the grey axis cycles R->G->B->R (the CPU's
        // own closed-form vector); the matrix must match the C++ layout
        // element for element: [cs + (1-cs)/3, (1-cs)/3 - sn*sqrt(1/3), ...]
        val n = NodeParcel.compileNode(hue = 120.0f)
        val a = 120.0f * PI.toFloat() / 180.0f
        val cs = cos(a)
        val sn = kotlin.math.sin(a)
        val k = 1.0f / 3.0f
        val sq = kotlin.math.sqrt(k)
        val expected = floatArrayOf(
            cs + (1 - cs) * k, k * (1 - cs) - sq * sn, k * (1 - cs) + sq * sn,
            k * (1 - cs) + sq * sn, cs + k * (1 - cs), k * (1 - cs) - sq * sn,
            k * (1 - cs) - sq * sn, k * (1 - cs) + sq * sn, cs + k * (1 - cs),
        )
        for (i in 0..8) {
            assertEquals("hueMat[$i]", expected[i], n.hueMat[i], 1e-6f)
        }
        // 360 degrees is the identity to float (the CPU test pins the same).
        val id = NodeParcel.compileNode(hue = 360.0f)
        for (i in 0..8) {
            val want = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)[i]
            assertTrue("hue 360 must be the identity, hueMat[$i] = ${id.hueMat[i]}",
                abs(id.hueMat[i] - want) < 2e-7f)
        }
    }

    // ---- the round trips + the wire order ----

    @Test
    fun empty_chain_encodes_the_header_plus_output_block() {
        // nodes = [] + legacy output: count 0, mode 0, and the output
        // block still rides (the DRT-with-empty-chain shape is count 0 +
        // mode 1 — the conversion-only pass).
        val p = NodeParcel.encode(emptyList(), NodeParcel.OutputTransform(mode = 0))
        assertEquals(NodeParcel.HEADER + NodeParcel.OUTPUT, p.size)
        assertEquals(0.0f, p[0], 0f)
        assertEquals(0.0f, p[1], 0f)
        NodeParcel.validate(p)
        val drt = NodeParcel.encode(emptyList(), NodeParcel.OutputTransform(mode = 1, look = 2, eotf = 2))
        assertEquals(NodeParcel.HEADER + NodeParcel.OUTPUT, drt.size)
        assertEquals(1.0f, drt[1], 0f)
        NodeParcel.validate(drt)
        assertEquals(2.0f, drt[2 + 0], 0f)   // look rides the output block
    }

    @Test
    fun full_chain_spot_locks_the_wire_order() {
        // Two nodes with distinctive values + a DRT output: every slot the
        // fold reads is asserted at the promoted offset.
        val n0 = NodeParcel.compileNode(gainMaster = 0.5f, sat = 0.0f)
        val n1 = NodeParcel.CompiledNode(
            enabled = false,
            mix = 0.5f,
            hasLut = true,
            lutIndex = 2,
            lutMix = 0.25f,
            hasCurves = true,
            sat = 1.7f,
        )
        val out = NodeParcel.OutputTransform(mode = 1, look = 3, tonescale = 5, cwp = 1, cwpLimit = 0.4f, grey = 18.0f, eotf = 1)
        val p = NodeParcel.encode(listOf(n0, n1), out)
        assertEquals(NodeParcel.HEADER + 2 * NodeParcel.STRIDE + NodeParcel.OUTPUT, p.size)

        assertEquals(2.0f, p[0], 0f)           // nodeCount
        assertEquals(1.0f, p[1], 0f)           // outputMode
        val b0 = NodeParcel.HEADER
        assertEquals(1.0f, p[b0 + NodeParcel.SLOT_ENABLED], 0f)
        assertEquals(1.5f, p[b0 + NodeParcel.SLOT_GAIN], 1e-5f)
        assertEquals(0.0f, p[b0 + NodeParcel.SLOT_SAT], 0f)
        assertEquals(0.0f, p[b0 + NodeParcel.SLOT_HAS_LUT], 0f)
        assertEquals(-1.0f, p[b0 + NodeParcel.SLOT_LUT_INDEX], 0f)
        val b1 = NodeParcel.HEADER + NodeParcel.STRIDE
        assertEquals(0.0f, p[b1 + NodeParcel.SLOT_ENABLED], 0f)
        assertEquals(0.5f, p[b1 + NodeParcel.SLOT_MIX], 0f)
        assertEquals(1.0f, p[b1 + NodeParcel.SLOT_HAS_CURVES], 0f)
        assertEquals(1.0f, p[b1 + NodeParcel.SLOT_HAS_LUT], 0f)
        assertEquals(2.0f, p[b1 + NodeParcel.SLOT_LUT_INDEX], 0f)
        assertEquals(0.25f, p[b1 + NodeParcel.SLOT_LUT_MIX], 0f)
        // The hueMat rides row-major starting at SLOT_HUE_MAT.
        assertEquals(1.0f, p[b0 + NodeParcel.SLOT_HUE_MAT], 0f)
        assertEquals(0.0f, p[b0 + NodeParcel.SLOT_HUE_MAT + 1], 0f)
        // The output block rides after the LAST node record.
        val o = NodeParcel.HEADER + 2 * NodeParcel.STRIDE
        assertEquals(3.0f, p[o + NodeParcel.SLOT_OUT_LOOK], 0f)
        assertEquals(5.0f, p[o + NodeParcel.SLOT_OUT_TONESCALE], 0f)
        assertEquals(1.0f, p[o + NodeParcel.SLOT_OUT_CWP], 0f)
        assertEquals(0.4f, p[o + NodeParcel.SLOT_OUT_CWP_LIMIT], 0f)
        assertEquals(18.0f, p[o + NodeParcel.SLOT_OUT_GREY], 0f)
        assertEquals(1.0f, p[o + NodeParcel.SLOT_OUT_EOTF], 0f)
        NodeParcel.validate(p)
    }

    @Test
    fun eight_node_chain_fits_and_nine_refuses() {
        val nodes = (0 until 8).map { NodeParcel.compileNode(contrast = 1.0f + it * 0.1f) }
        val p = NodeParcel.encode(nodes, NodeParcel.OutputTransform())
        assertEquals(NodeParcel.HEADER + 8 * NodeParcel.STRIDE + NodeParcel.OUTPUT, p.size)
        NodeParcel.validate(p)
        // The ninth uniform's lutIndex refusal rides the encode path.
        val nine = nodes + NodeParcel.compileNode()
        try {
            NodeParcel.encode(nine, NodeParcel.OutputTransform())
            throw AssertionError("expected a 9-node refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("exceed the N1 cap"))
        }
    }

    @Test
    fun four_node_luts_fit_and_five_refuse() {
        val withLut = { i: Int -> NodeParcel.CompiledNode(hasLut = true, lutIndex = i) }
        val ok = NodeParcel.encode((0 until 4).map(withLut), NodeParcel.OutputTransform())
        NodeParcel.validate(ok)
        val five = (0 until 5).map(withLut)
        try {
            NodeParcel.validate(NodeParcel.encode(five, NodeParcel.OutputTransform()))
            throw AssertionError("expected a 5-LUT refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("exceed the N1 cap"))
        }
    }

    // ---- the guards ----

    @Test
    fun boolean_slots_refuse_non_binary_values_and_validation_names_them() {
        // A hand-corrupted parcel: node 0's enabled = 0.37. The ENCODER
        // cannot produce it (it only writes 0/1); the VALIDATOR is the
        // second lock (the C++ decodeNodeParcel's own check, mirrored).
        val p = NodeParcel.encode(listOf(NodeParcel.compileNode()), NodeParcel.OutputTransform())
        p[NodeParcel.HEADER + NodeParcel.SLOT_ENABLED] = 0.37f
        try {
            NodeParcel.validate(p)
            throw AssertionError("expected a boolean refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("must be 0.0 or 1.0"))
            assertTrue(expected.message!!.contains("0.37"))
            assertTrue(expected.message!!.contains("enabled"))
        }
    }

    @Test
    fun lut_index_outside_the_table_slots_refuses() {
        val p = NodeParcel.encode(
            listOf(NodeParcel.CompiledNode(hasLut = true, lutIndex = 7)),
            NodeParcel.OutputTransform(),
        )
        try {
            NodeParcel.validate(p)
            throw AssertionError("expected a lutIndex refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("lutIndex"))
        }
    }

    @Test
    fun non_finite_slots_refuse() {
        val p = NodeParcel.encode(listOf(NodeParcel.compileNode()), NodeParcel.OutputTransform())
        p[NodeParcel.HEADER + NodeParcel.SLOT_CONTRAST] = Float.NaN
        try {
            NodeParcel.validate(p)
            throw AssertionError("expected a NaN refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not finite"))
        }
    }

    @Test
    fun a_length_disagreeing_with_node_count_refuses() {
        // Truncating a valid parcel: the decode must refuse, never read
        // the tail as a partial node (the C++ decodeNodeParcel's own
        // "length does not match nodeCount" refusal, mirrored).
        val p = NodeParcel.encode(
            listOf(NodeParcel.compileNode(), NodeParcel.compileNode()),
            NodeParcel.OutputTransform(),
        )
        val truncated = p.copyOf(p.size - NodeParcel.STRIDE / 2)
        try {
            NodeParcel.validate(truncated)
            throw AssertionError("expected a length refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not match nodeCount"))
        }
    }

    @Test
    fun mix_and_lutMix_cross_clamped() {
        // The mix pair clamps to [0,1] at ENCODE (the CPU compile()'s own
        // clamp, carried): a negative mix and a >1 lutMix both normalize.
        val p = NodeParcel.encode(
            listOf(NodeParcel.CompiledNode(mix = -0.7f, hasLut = true, lutIndex = 0, lutMix = 3.0f)),
            NodeParcel.OutputTransform(),
        )
        val b = NodeParcel.HEADER
        assertEquals(0.0f, p[b + NodeParcel.SLOT_MIX], 0f)
        assertEquals(1.0f, p[b + NodeParcel.SLOT_LUT_MIX], 0f)
        NodeParcel.validate(p)
    }
}
