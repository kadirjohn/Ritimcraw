package com.ritim.ritimcraw.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I7 (H brief, 2026-11-27; research-recovery-glsl-twin branch, on top of D8's
 * research-recovery-joint-estimate — RESEARCH BRANCH, never merged to main by the
 * night agent): the TEXT-parity lock between the RECOVERY_COMPOSITE GLSL source
 * (GlesShaderSources, the single source of truth the device compiles) and the C++
 * desktop twin of it (core/tests/recovery_glsl_twin.hpp — the float32 twin whose
 * numerical comparison against the CPU oracle lives in
 * core/tests/test_recovery_glsl_twin.cpp).
 *
 * The GlesShaderValidator pins each pass's contract STRINGS (one line each); this
 * test goes further for the joint-estimate formula specifically: it extracts the
 * formula lines from BOTH sources and compares them as normalized token sequences
 * — numeric literals bit-for-bit, `std::log`/`std::exp`/`std::max`/`std::min`
 * mapped to the GLSL built-ins they model, the float suffix `f` folded off NUMERIC
 * literals only (never off identifiers: `cf` stays `cf`, `if` stays `if`), the
 * twin-only pass-through arguments (the shader's uniforms) folded away, and the
 * twin's brace initializer `Vec2{...}` mapped onto the GLSL constructor
 * `vec2(...)`. A silent edit of either copy — the GLSL text or the C++ twin —
 * fails here loudly before the device parity sweep can misattribute a formula
 * drift to driver rounding.
 *
 * Two shape families are compared by CONTENT extraction instead of raw token
 * parity, because the two LANGUAGES spell the same value differently and the
 * spelling is not the invariant: the array constructors `float[2](...)` /
 * `int[2](...)` (GLSL) vs aggregate initializers `{...}` (C++), and the
 * `ratioForW(g, c1, r)` call's coordinate argument (the shader's gl_FragCoord
 * pixel vs the twin's explicit int(x), int(y) pair). What must be identical there
 * is the ELEMENT LIST and the ARGUMENT TAIL — those are extracted and token-
 * compared directly.
 *
 * Pure JVM: no GL, no core .so, no Android classes — the same headless discipline
 * as GlesShaderValidatorTest (the only IO is reading the committed twin header,
 * the NodeChainWireTest locator pattern).
 */
class GlesRecoveryTwinTextTest {

    // ---- the committed C++ twin (the NodeChainWireTest locator pattern) ----

    private fun committedTwinHeader(): String {
        val suffixes = listOf(
            "core/tests/recovery_glsl_twin.hpp",
            "../core/tests/recovery_glsl_twin.hpp",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 10) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.exists()) return candidate.readText()
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the committed recovery GLSL twin header; tried " +
                candidates.joinToString(", ") { it.absolutePath } +
                " (working dir " + File(".").absolutePath + ")",
        )
    }

    /**
     * The formula lines of the joint estimate, as the GLSL source spells them
     * (GlesShaderSources.RECOVERY_COMPOSITE, the joint block of commit 2b2da0dc).
     * Each entry: the exact line the shader carries. If the shader text changes,
     * this list must change with it — that is the point of the lock.
     */
    private val glslJointLines = listOf(
        "int c1 = cf[0] ? 0 : 1;",
        "int c2 = cf[2] ? 2 : 1;",
        "if (r1.x > 0.0 && r2.x > 0.0 && r12.x > 0.0) {",
        "float w1 = 1.0 / r1.y, w2 = 1.0 / r2.y, w3 = 1.0 / r12.y;",
        "float D = w1 * w2 + w1 * w3 + w2 * w3;",
        "float a1 = log(vr * r1.x);",
        "float a2 = log(vr * r2.x);",
        "float a12 = log(r12.x);",
        "float x1 = (w1 * (w2 + w3) * a1 + w2 * w3 * (a2 + a12)) / D;",
        "float x2 = (w1 * w3 * (a1 - a12) + w2 * (w1 + w3) * a2) / D;",
        "jointE = vec2(exp(x1), exp(x2));",
        "float e = max(v[c], est[k]);",
        "if (v[c] > 0.0) e = min(e, 4.0 * v[c]);",
    )

    /** The same lines as the C++ twin spells them (recovery_glsl_twin.hpp). */
    private val cppJointLines = listOf(
        "const int c1 = cf[0] ? 0 : 1;",
        "const int c2 = cf[2] ? 2 : 1;",
        "if (r1.x > 0.0f && r2.x > 0.0f && r12.x > 0.0f) {",
        "const float w1 = 1.0f / r1.y, w2 = 1.0f / r2.y, w3 = 1.0f / r12.y;",
        "const float D = w1 * w2 + w1 * w3 + w2 * w3;",
        "const float a1 = std::log(vr * r1.x);",
        "const float a2 = std::log(vr * r2.x);",
        "const float a12 = std::log(r12.x);",
        "const float x1 = (w1 * (w2 + w3) * a1 + w2 * w3 * (a2 + a12)) / D;",
        "const float x2 = (w1 * w3 * (a1 - a12) + w2 * (w1 + w3) * a2) / D;",
        "jointE = Vec2{std::exp(x1), std::exp(x2)};",
        "float e = std::max(v[c], est[k]);",
        "if (v[c] > 0.0f) e = std::min(e, 4.0f * v[c]);",
    )

    /**
     * The three ratioForW calls of the joint block: the call ARGUMENT TAIL (the
     * channel pair) must be identical; the leading coordinate argument is a
     * language-shaped difference (GLSL's pixel `g` from gl_FragCoord vs the twin's
     * explicit int(x), int(y) + uniform pass-through).
     */
    private val glslRatioCalls = listOf(
        "vec2 r1 = ratioForW(g, c1, r);",
        "vec2 r2 = ratioForW(g, c2, r);",
        "vec2 r12 = ratioForW(g, c1, c2);",
    )

    private val cppRatioCalls = listOf(
        "const Vec2 r1 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c1, r);",
        "const Vec2 r2 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c2, r);",
        "const Vec2 r12 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c1, c2);",
    )

    /**
     * The array-constructor lines (est/chan): the ELEMENT LIST must be identical;
     * GLSL spells it `float[2](...)`/`int[2](...)`, C++ `{...}`.
     */
    private val glslCtorLines = listOf(
        "float est[2] = float[2](jointE.x, jointE.y);",
        "int chan[2] = int[2](cf[0] ? 0 : 1, cf[2] ? 2 : 1);",
    )

    private val cppCtorLines = listOf(
        "const float est[2] = {jointE.x, jointE.y};",
        "const int chan[2] = {cf[0] ? 0 : 1, cf[2] ? 2 : 1};",
    )

    /** The scale-threshold ladder of ratioForW, GLSL spelling (the support constants). */
    private val glslScaleLines = listOf(
        "if (s.x >= 4.05 && s.z > 0.0) return vec2(s.y / s.z, 9.0);",
        "if (s.x >= 28.8 && s.z > 0.0) return vec2(s.y / s.z, 8.0);",
        "if (s.x >= 460.8 && s.z > 0.0) return vec2(s.y / s.z, 32.0);",
        "if (s.x >= 7372.8 && s.z > 0.0) return vec2(s.y / s.z, 128.0);",
        "if (s.x >= 117964.8 && s.z > 0.0) return vec2(s.y / s.z, 512.0);",
        "return vec2(-1.0, 0.0);",
    )

    /** The same ladder as the C++ twin spells it. */
    private val cppScaleLines = listOf(
        "if (s.x >= 4.05f && s.z > 0.0f) return Vec2{s.y / s.z, 9.0f};",
        "if (s.x >= 28.8f && s.z > 0.0f) return Vec2{s.y / s.z, 8.0f};",
        "if (s.x >= 460.8f && s.z > 0.0f) return Vec2{s.y / s.z, 32.0f};",
        "if (s.x >= 7372.8f && s.z > 0.0f) return Vec2{s.y / s.z, 128.0f};",
        "if (s.x >= 117964.8f && s.z > 0.0f) return Vec2{s.y / s.z, 512.0f};",
        "return Vec2{-1.0f, 0.0f};",
    )

    // ---- the normalizer ----

    /**
     * Maps one C++ twin token to the GLSL spelling of the same value so the two
     * sequences compare equal:
     *  - NUMERIC literals only lose their float suffix (`0.0f` -> `0.0`;
     *    identifiers never do — `cf` stays `cf`, `if` stays `if`);
     *  - `std::log`/`std::exp`/`std::max`/`std::min` fold to the GLSL built-ins
     *    they model (`log`/`exp`/`max`/`min`);
     *  - the twin's `Vec2`/`Vec3` struct types fold to `vec2`/`vec3`;
     *  - the C++ `const` qualifier (a spelling difference only) folds away.
     */
    private fun normalizeCppToken(t: String): String {
        var s = t
        if (s.startsWith("std::")) s = s.removePrefix("std::")
        if (s == "Vec2") s = "vec2"
        if (s == "Vec3") s = "vec3"
        if (s == "const") return ""  // a C++ spelling qualifier, no GLSL counterpart
        val numeric = Regex("""[0-9]+(\.[0-9]*)?([eE][+-]?[0-9]+)?f""")
        if (numeric.matches(s)) s = s.removeSuffix("f")
        return s
    }

    /** GLSL token spelling as-is (already the reference form). */
    private fun normalizeGlslToken(t: String): String = t

    /**
     * C++ pre-tokenization folds (string level, before the token split):
     *  1. the twin's uniform pass-through arguments fold away
     *     (`ratioForW(py, img, bitsArr, w, h, int(x), int(y), REST)` ->
     *     `ratioForW(REST)` — the shader reads those from uniforms);
     *  2. the twin's brace initializer maps onto the GLSL constructor:
     *     `Vec2{` -> `vec2(` and the initializer's closing `}` -> `)`. Safe here:
     *     these formula lines hold no nested blocks, the initializer's `}` is the
     *     line's only `}` (the joint block's own braces are not on these lines).
     */
    private fun cppFold(line: String): String {
        var l = line.trim()
        l = l.replace(
            Regex("""ratioForW\(py, img, bitsArr, w, h, int\(x\), int\(y\), (.*?)\)"""),
            "ratioForW($1)",
        )
        val braceInit = Regex("""Vec([23])\{""")
        if (braceInit.containsMatchIn(l)) {
            l = l.replace(braceInit, "vec$1(")
            val close = l.lastIndexOf('}')
            if (close >= 0) l = l.substring(0, close) + ")" + l.substring(close + 1)
        }
        return l
    }

    /**
     * The token sequence one formula line normalizes to. Splits on identifiers /
     * numeric literals / single-character structural punctuation, folds the
     * twin-only spellings (see [cppFold]), and drops empty tokens (the folded
     * `const`).
     */
    private fun tokens(line: String, cpp: Boolean): List<String> {
        val l = if (cpp) cppFold(line) else line.trim()
        val raw = Regex(
            """[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)?""" +
                """|[0-9]+\.?[0-9]*(?:[eE][+-]?[0-9]+)?f?""" +
                """|[{}()\[\],;?:<>+\-*/%&|!=]""",
        ).findAll(l).map { it.value }.toList()
        return raw.map { if (cpp) normalizeCppToken(it) else normalizeGlslToken(it) }
            .filter { it.isNotEmpty() }
    }

    /**
     * The ELEMENT LIST of an initializer/constructor line: the content between
     * the opening bracket (GLSL's `T[n](` or C++'s `= {`) and its closing partner.
     */
    private fun elementList(line: String, cpp: Boolean): String {
        val l = line.trim()
        return if (cpp) {
            val open = l.indexOf('{')
            val close = l.lastIndexOf('}')
            require(open >= 0 && close > open) { "not a brace initializer: $l" }
            l.substring(open + 1, close)
        } else {
            val m = Regex("""\[(\d+)\]\(""").find(l)
            requireNotNull(m) { "not an array constructor: $l" }
            val open = m.range.last + 1
            val close = l.lastIndexOf(')')
            require(close > open) { "unbalanced constructor: $l" }
            l.substring(open, close)
        }
    }

    /** The ratioForW call's ARGUMENT TAIL: everything after the leading coordinate. */
    private fun ratioCallTail(line: String, cpp: Boolean): String {
        val l = if (cpp) cppFold(line) else line.trim()
        // both spellings after the fold: ratioForW(g, c1, r) / ratioForW(c1, r)
        val open = l.indexOf("ratioForW(")
        require(open >= 0) { "not a ratioForW call: $l" }
        val close = l.indexOf(')', open)
        require(close > open) { "unbalanced call: $l" }
        val args = l.substring(open + "ratioForW(".length, close)
        return if (cpp) args else args.substringAfter(", ")  // drop the GLSL's `g, `
    }

    @Test
    fun the_glsl_joint_block_and_the_cpp_twin_carry_the_same_formula_lines() {
        val glsl = GlesShaderSources.RECOVERY_COMPOSITE
        val cpp = committedTwinHeader()
        assertEquals(
            "the two formula-line tables must stay the same length (one entry per shared line)",
            glslJointLines.size, cppJointLines.size,
        )
        for (i in glslJointLines.indices) {
            val g = glslJointLines[i]
            val c = cppJointLines[i]
            assertTrue("the GLSL source must carry its joint line verbatim: $g", glsl.contains(g))
            assertTrue("the C++ twin header must carry its joint line verbatim: $c", cpp.contains(c))
            assertEquals(
                "formula line $i must be the same token sequence in the GLSL and the C++ twin " +
                    "(GLSL: $g / C++: $c)",
                tokens(g, cpp = false),
                tokens(c, cpp = true),
            )
        }
    }

    @Test
    fun the_glsl_ratio_calls_and_the_cpp_twin_share_the_argument_tails() {
        val glsl = GlesShaderSources.RECOVERY_COMPOSITE
        val cpp = committedTwinHeader()
        for (i in glslRatioCalls.indices) {
            val g = glslRatioCalls[i]
            val c = cppRatioCalls[i]
            assertTrue(glsl.contains(g))
            assertTrue(cpp.contains(c))
            assertEquals(
                "ratioForW call $i must pass the same channel pair (GLSL: $g / C++: $c)",
                tokens(ratioCallTail(g, cpp = false), cpp = false),
                tokens(ratioCallTail(c, cpp = true), cpp = true),
            )
        }
    }

    @Test
    fun the_glsl_array_constructors_and_the_cpp_aggregates_share_the_element_lists() {
        val glsl = GlesShaderSources.RECOVERY_COMPOSITE
        val cpp = committedTwinHeader()
        for (i in glslCtorLines.indices) {
            val g = glslCtorLines[i]
            val c = cppCtorLines[i]
            assertTrue(glsl.contains(g))
            assertTrue(cpp.contains(c))
            assertEquals(
                "initializer line $i must carry the same element list (GLSL: $g / C++: $c)",
                tokens(elementList(g, cpp = false), cpp = false),
                tokens(elementList(c, cpp = true), cpp = true),
            )
        }
    }

    @Test
    fun the_glsl_scale_ladder_and_the_cpp_twin_carry_the_same_constants() {
        val glsl = GlesShaderSources.RECOVERY_COMPOSITE
        val cpp = committedTwinHeader()
        for (i in glslScaleLines.indices) {
            val g = glslScaleLines[i]
            val c = cppScaleLines[i]
            assertTrue("the GLSL source must carry its scale line verbatim: $g", glsl.contains(g))
            assertTrue("the C++ twin header must carry its scale line verbatim: $c", cpp.contains(c))
            assertEquals(
                "scale line $i (the support constants are bit-exact) — GLSL: $g / C++: $c",
                tokens(g, cpp = false),
                tokens(c, cpp = true),
            )
        }
    }

    @Test
    fun the_twin_test_fixtures_pin_the_reference_channel_rule_and_the_pair_gate() {
        // The shared CONTROL lines the numerical twin's branch logic depends on:
        // the reference-channel rule, the pairTerm gate and the K = 4 headroom.
        // These are pinned by GlesShaderValidator's contract strings too; here they
        // are pinned against the committed twin header as raw contains-locks (the
        // two sources spell the vector swizzles differently, so the token mapper
        // does not apply — the CONSTANTS are what must be bit-equal).
        val glsl = GlesShaderSources.RECOVERY_COMPOSITE
        val cpp = committedTwinHeader()
        assertTrue(glsl.contains("bool ok = (bits & mc) == 0 && (bits & mr) == 0 && vr > 1e-4 && vc >= 0.0;"))
        assertTrue(cpp.contains("(bits & mc) == 0 && (bits & mr) == 0 && vr > 1e-4f && vc >= 0.0f"))
        assertTrue(glsl.contains("e = min(e, 4.0 * v[c]);"))
        assertTrue(cpp.contains("e = std::min(e, 4.0f * v[c]);"))
        assertTrue(glsl.contains("if (!cf[1]) r = 1;"))
        assertTrue(cpp.contains("if (!cf[1]) r = 1;"))
        assertTrue(glsl.contains("else r = (v.r >= v.b) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);"))
        assertTrue(cpp.contains("else r = (v[0] >= v[2]) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);"))
    }

    @Test
    fun a_silent_edit_of_either_copy_fails_the_lock_loudly() {
        // Proof the lock fires: a mutated C++ weight (1.0 -> 1.5 on w1) must no
        // longer equal the GLSL token sequence; and a mutated GLSL constant
        // (the headroom K 4.0 -> 5.0) must differ from the twin's too. This is
        // the failure mode the lock exists for — a nightly edit of one copy only.
        val glslW = "float w1 = 1.0 / r1.y, w2 = 1.0 / r2.y, w3 = 1.0 / r12.y;"
        val cppMutated = "const float w1 = 1.5f / r1.y, w2 = 1.0f / r2.y, w3 = 1.0f / r12.y;"
        assertTrue(
            "the weight mutation must break the token parity",
            tokens(glslW, cpp = false) != tokens(cppMutated, cpp = true),
        )
        val glslHead = "if (v[c] > 0.0) e = min(e, 4.0 * v[c]);"
        val cppHead = "if (v[c] > 0.0f) e = std::min(e, 4.0f * v[c]);"
        val glslMutated = glslHead.replace("4.0 * v[c]", "5.0 * v[c]")
        assertTrue(glslMutated != glslHead)
        assertTrue(
            "the headroom-constant mutation must break the token parity",
            tokens(glslMutated, cpp = false) != tokens(cppHead, cpp = true),
        )
        // Sanity of the normalizer itself: an identifier ending in `f` must NOT be
        // suffix-stripped (a broken normalizer would turn `cf` into `c`, `if`
        // into `i`, and the line pairs above would compare garbage).
        assertEquals(
            listOf("if", "(", "cf", "[", "1", "]", "?", "0", ":", "1"),
            tokens("if (cf[1] ? 0 : 1", cpp = false),
        )
    }
}
