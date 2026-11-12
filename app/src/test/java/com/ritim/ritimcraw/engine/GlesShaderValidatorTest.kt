package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GlesShaderValidator tests (owner request 2026-11-12, deliverable C).
 * HONESTY: these are SYNTAX + CONTRACT checks on the exact text the
 * device will compile — this host has no GLSL compiler, so no compile
 * claim is made; the real compile is device-test-pending (the owner's
 * Measure run). What these tests DO lock: the shipped source passes
 * every check, and each check individually catches the defect it exists
 * for. Pure JVM.
 */
class GlesShaderValidatorTest {

    // ---- the shipped source ----

    @Test
    fun shipped_demosaic_source_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(GlesShaderSources.DEMOSAIC_COMPUTE),
        )
    }

    @Test
    fun validate_is_true_for_the_shipped_source() {
        assertTrue(GlesShaderValidator.validate(GlesShaderSources.DEMOSAIC_COMPUTE))
    }

    // ---- the shader text is locked to the CPU kernel it mirrors ----
    // Text-level checks (honest: the algorithm itself is verified on the
    // device by the parity harness; these catch a silent edit of either
    // copy). rawedit::demosaicBilinear (core/src/Develop.cpp):
    // kDx={-1,1,0,0,-1,-1,1,1}, kDy={0,0,-1,1,-1,1,-1,1}, clamp the
    // neighbor coordinates FIRST, own site passes through, n==0 falls
    // back to the center sample.

    @Test
    fun shipped_source_carries_the_cpu_kernel_offsets_in_order() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE
        assertTrue("kDx missing/wrong: $src", src.contains("int[8](-1, 1, 0, 0, -1, -1, 1, 1)"))
        assertTrue("kDy missing/wrong: $src", src.contains("int[8](0, 0, -1, 1, -1, 1, -1, 1)"))
    }

    @Test
    fun shipped_source_clamps_before_the_site_test() {
        // The CPU order: min(max(...)) FIRST, then siteAt(np) == t. The
        // text must keep that order (a wrap-first shader would diverge at
        // every border pixel).
        val src = GlesShaderSources.DEMOSAIC_COMPUTE
        val clampIdx = src.indexOf("min(max(g.x + kDx[k], 0), maxI)")
        val testIdx = src.indexOf("if (siteAt(np) == t)")
        assertTrue("clamp-first missing", clampIdx >= 0)
        assertTrue("site test missing", testIdx >= 0)
        assertTrue("clamp must precede the site test", clampIdx < testIdx)
    }

    @Test
    fun shipped_source_passes_own_site_through_and_falls_back_at_n_zero() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE
        assertTrue("own-site passthrough missing", src.contains("if (t == ownSite)"))
        assertTrue("neighbor accumulation missing", src.contains("sum += cfaAt(np);"))
        assertTrue("n==0 fallback missing", src.contains("px[t] = n > 0 ? sum / float(n) : cfaAt(g);"))
        // Every cfa sample access must go through the SAME accessor — a
        // rename that leaves one call site behind (e.g. at(np)) would be an
        // undefined function on the device and no host check would fire.
        assertTrue("stale accessor call remains", !src.contains(" at("))
    }

    // ---- each check catches its own defect ----

    @Test
    fun wrong_version_is_reported() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE.replace("#version 310 es", "#version 300 es")
        assertTrue(GlesShaderValidator.problems(src).any { it.contains("#version 310 es") })
    }

    @Test
    fun missing_precision_is_reported() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE.replace("precision highp float;", "")
        assertTrue(GlesShaderValidator.problems(src).any { it.contains("precision highp float") })
    }

    @Test
    fun missing_local_size_is_reported() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE.replace(
            "layout(local_size_x = 8, local_size_y = 8) in;",
            "layout(local_size_x = 4, local_size_y = 4) in;",
        )
        assertTrue(
            GlesShaderValidator.problems(src).any { it.contains("local_size") },
        )
    }

    @Test
    fun missing_ssbo_blocks_and_uniforms_are_reported() {
        // Renames pick names that do NOT contain the originals, so the
        // check fires on the genuinely missing block (a superset rename
        // like InBufX would still contain "buffer InBuf" as a substring).
        val renamed = GlesShaderSources.DEMOSAIC_COMPUTE
            .replace("buffer InBuf", "buffer InputBuf")
            .replace("buffer OutBuf", "buffer OutputBuf")
            .replace("uniform ivec2 uSize;", "uniform ivec2 uDim;")
            .replace("uniform ivec4 uCell;", "uniform ivec4 uQuad;")
        val problems = GlesShaderValidator.problems(renamed)
        assertTrue(problems.any { it.contains("InBuf") })
        assertTrue(problems.any { it.contains("OutBuf") })
        assertTrue(problems.any { it.contains("uSize") })
        assertTrue(problems.any { it.contains("uCell") })
    }

    @Test
    fun unbalanced_braces_are_reported() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE.replace("{ float cfa[]; };", "{ float cfa[]; ")
        assertTrue(GlesShaderValidator.problems(src).any { it.contains("unbalanced") })
    }

    @Test
    fun non_ascii_is_reported() {
        // An editor paste of a unicode dash would otherwise surface only
        // as an opaque device compile error.
        val src = GlesShaderSources.DEMOSAIC_COMPUTE.replace("n > 0", "n > 0—")
        assertTrue(GlesShaderValidator.problems(src).any { it.contains("non-ascii") })
    }

    @Test
    fun empty_source_is_reported() {
        assertEquals(listOf("empty source"), GlesShaderValidator.problems(""))
    }

    // ---- comment stripping ----

    @Test
    fun strip_comments_removes_line_and_block_comments_but_keeps_code() {
        val src = "int a; // line comment with ( unbalanced [ junk\n/* block ( { */ int b;\n"
        val stripped = GlesShaderValidator.stripComments(src)
        assertTrue(stripped.contains("int a;"))
        assertTrue(stripped.contains("int b;"))
        assertTrue(!stripped.contains("line comment"))
        assertTrue(!stripped.contains("block"))
    }

    @Test
    fun comments_do_not_hide_deliberately_unbalanced_code() {
        // The balance check runs on the STRIPPED text: an unbalanced
        // brace inside a comment must not be reported...
        val commented = "void f() { /* ) ] } */ }"
        assertTrue(GlesShaderValidator.problems(commented).none { it.contains("unbalanced") })
        // ...and unbalanced CODE next to a comment must be.
        val broken = "void f() { // (\n"
        assertTrue(GlesShaderValidator.problems(broken).any { it.contains("unbalanced") })
    }

    @Test
    fun block_comment_at_end_of_source_does_not_loop() {
        val src = "int a; /* unterminated"
        // Terminates and reports the imbalance the truncation causes.
        GlesShaderValidator.problems(src)
    }
}
