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
        // like InBufX would still contain the full layout string as a
        // substring only if the layout survived — here it does not).
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

    // ---- the SSBO binding contract (audit fix 2026-11-13) ----
    // GLSL ES 3.10 layout(std430, binding=N) is the ONLY binding
    // mechanism the harness relies on: glShaderStorageBlockBinding is
    // desktop GL 4.2+, not an ES command, so the old eglGetProcAddress
    // lookup could only ever return null. These tests lock the new
    // declarations in and reject the old plain-buffer form.

    @Test
    fun shipped_source_declares_std430_and_explicit_bindings() {
        val src = GlesShaderSources.DEMOSAIC_COMPUTE
        assertTrue(
            "InBuf must declare std430 + binding 0",
            src.contains("layout(std430, binding = 0) readonly buffer InBuf"),
        )
        assertTrue(
            "OutBuf must declare std430 + binding 1",
            src.contains("layout(std430, binding = 1) writeonly buffer OutBuf"),
        )
    }

    @Test
    fun plain_buffer_declarations_are_rejected() {
        // The OLD form (pre-audit): plain `buffer` with no std430 and no
        // binding — relies on default packing while the C++ side assumes
        // tight float arrays, and leaves the block-to-buffer attachment
        // undefined. The validator must reject it.
        val oldForm = GlesShaderSources.DEMOSAIC_COMPUTE
            .replace(
                "layout(std430, binding = 0) readonly buffer InBuf",
                "buffer InBuf",
            )
            .replace(
                "layout(std430, binding = 1) writeonly buffer OutBuf",
                "buffer OutBuf",
            )
        val problems = GlesShaderValidator.problems(oldForm)
        assertTrue(
            "the plain InBuf declaration must be reported",
            problems.any { it.contains("layout(std430, binding = 0) readonly buffer InBuf") },
        )
        assertTrue(
            "the plain OutBuf declaration must be reported",
            problems.any { it.contains("layout(std430, binding = 1) writeonly buffer OutBuf") },
        )
    }

    @Test
    fun missing_std430_is_rejected_even_with_binding() {
        // binding without std430: the attachment point is right but the
        // packing guarantee is gone — still a contract violation.
        val noStd430 = GlesShaderSources.DEMOSAIC_COMPUTE
            .replace("layout(std430, binding = 0)", "layout(binding = 0)")
            .replace("layout(std430, binding = 1)", "layout(binding = 1)")
        assertTrue(
            GlesShaderValidator.problems(noStd430).any { it.contains("std430") },
        )
    }

    @Test
    fun swapped_bindings_are_rejected() {
        // InBuf at 1 / OutBuf at 0 would attach the buffers the wrong way
        // round at the harness's glBindBufferBase calls — the validator
        // must catch the swap, not just the absence.
        val swapped = GlesShaderSources.DEMOSAIC_COMPUTE
            .replace("layout(std430, binding = 0) readonly buffer InBuf", "layout(std430, binding = 1) readonly buffer InBuf")
            .replace("layout(std430, binding = 1) writeonly buffer OutBuf", "layout(std430, binding = 0) writeonly buffer OutBuf")
        val problems = GlesShaderValidator.problems(swapped)
        assertTrue(problems.any { it.contains("binding = 0) readonly buffer InBuf") })
        assertTrue(problems.any { it.contains("binding = 1) writeonly buffer OutBuf") })
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

    // ====================================================================
    // Deliverable D: the fragment chain (fullscreen vertex, fused develop
    // fragment, SSBO readback fragment). Same honesty as above: syntax +
    // contract on the exact device text; the real compile is
    // device-test-pending.
    // ====================================================================

    // ---- the shipped sources pass their own kinds ----

    @Test
    fun shipped_fullscreen_vertex_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX,
            ),
        )
    }

    @Test
    fun shipped_develop_fragment_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DEVELOP_FRAGMENT,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT,
            ),
        )
    }

    @Test
    fun shipped_store_fragment_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.STORE_FRAGMENT,
                GlesShaderValidator.Kind.STORE_FRAGMENT,
            ),
        )
    }

    @Test
    fun shipped_fullscreen_vertex_310_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.FULLSCREEN_VERTEX_310,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX_310,
            ),
        )
    }

    @Test
    fun the_two_vertex_sources_cannot_drift() {
        // The store program links a 310-es vertex (GLSL ES forbids mixing
        // #version lines in one program); the body past the version line
        // must be VERBATIM the develop program's 300-es vertex body — one
        // shared body const composes both, and this test locks that.
        val v300 = GlesShaderSources.FULLSCREEN_VERTEX
        val v310 = GlesShaderSources.FULLSCREEN_VERTEX_310
        assertTrue(v300.startsWith("#version 300 es\n"))
        assertTrue(v310.startsWith("#version 310 es\n"))
        assertEquals(
            v300.removePrefix("#version 300 es\n"),
            v310.removePrefix("#version 310 es\n"),
        )
    }

    @Test
    fun every_shipped_source_passes_validate_with_its_kind() {
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.STORE_FRAGMENT,
                GlesShaderValidator.Kind.STORE_FRAGMENT,
            ),
        )
    }

    // ---- the wrong-kind version is reported ----

    @Test
    fun a_310_source_checked_as_fragment_300_is_reported() {
        // The compute source under the fragment-chain kind: the version
        // check must fire (310 es is not the 300 es this kind requires).
        val problems = GlesShaderValidator.problems(
            GlesShaderSources.DEMOSAIC_COMPUTE,
            GlesShaderValidator.Kind.DEVELOP_FRAGMENT,
        )
        assertTrue(problems.any { it.contains("#version 300 es") })
    }

    @Test
    fun a_300_source_checked_as_310_is_reported() {
        val problems = GlesShaderValidator.problems(
            GlesShaderSources.DEVELOP_FRAGMENT,
            GlesShaderValidator.Kind.STORE_FRAGMENT,
        )
        assertTrue(problems.any { it.contains("#version 310 es") })
    }

    // ---- glReadPixels is banned in every kind (baseline invariant) ----

    @Test
    fun glreadpixels_is_reported_in_every_kind() {
        val withRead = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("vec3 c = vec3(px[0], px[1], px[2]) * uWb;", "vec3 c = vec3(px[0], px[1], px[2]) * uWb; glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, c);")
        listOf(
            GlesShaderValidator.Kind.COMPUTE to withRead,
            GlesShaderValidator.Kind.DEVELOP_FRAGMENT to withRead,
            GlesShaderValidator.Kind.STORE_FRAGMENT to withRead,
        ).forEach { (kind, src) ->
            assertTrue(
                "kind=$kind",
                GlesShaderValidator.problems(src, kind).any { it.contains("glReadPixels is banned") },
            )
        }
    }

    // ---- the develop fragment is locked to the CPU stages it mirrors ----
    // (rawedit::developCpu / demosaicBilinear / linearToRec709,
    // core/src/Develop.cpp + core/src/Transfer.cpp).

    @Test
    fun develop_fragment_carries_the_cpu_kernel_offsets_in_order() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        assertTrue("kDx missing/wrong: $src", src.contains("int[8](-1, 1, 0, 0, -1, -1, 1, 1)"))
        assertTrue("kDy missing/wrong: $src", src.contains("int[8](0, 0, -1, 1, -1, 1, -1, 1)"))
    }

    @Test
    fun develop_fragment_clamps_before_the_site_test() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val clampIdx = src.indexOf("min(max(g.x + kDx[k], 0), maxI)")
        val testIdx = src.indexOf("if (siteAt(np) == t)")
        assertTrue("clamp-first missing", clampIdx >= 0)
        assertTrue("site test missing", testIdx >= 0)
        assertTrue("clamp must precede the site test", clampIdx < testIdx)
    }

    @Test
    fun develop_fragment_carries_the_stage1_slot_order_and_clamp() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        assertTrue(
            "cellSlot order missing",
            src.contains("int slot = (p.y & 1) * 2 + (p.x & 1);"),
        )
        assertTrue("stage-1 clamp missing", src.contains("clamp(v, 0.0, 1.0)"))
    }

    @Test
    fun develop_fragment_carries_the_cpu_oetf_constants() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        assertTrue(
            "linear segment missing",
            src.contains("if (lin < 0.018) return 4.5 * lin;"),
        )
        assertTrue(
            "power segment missing",
            src.contains("1.099 * pow(lin, 0.45) - 0.099"),
        )
    }

    @Test
    fun develop_fragment_declares_every_harness_uniform() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        listOf(
            "uniform highp usampler2D uCfa;",
            "uniform ivec2 uSize;",
            "uniform ivec4 uCell;",
            "uniform vec4 uBlack;",
            "uniform vec4 uRange;",
            "uniform vec3 uWb;",
            "uniform mat3 uCam2rgb;",
            "uniform int uHasProfile;",
            "uniform float uExposure;",
            "uniform int uOetf;",
        ).forEach { u ->
            assertTrue("missing uniform: $u", src.contains(u))
        }
    }

    // ---- each new check catches its own defect ----

    @Test
    fun a_renamed_develop_uniform_is_reported() {
        val renamed = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("uniform vec4 uBlack;", "uniform vec4 uBlackLevel;")
        assertTrue(
            GlesShaderValidator.problems(renamed, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("uBlack") },
        )
    }

    @Test
    fun a_wrong_kernel_offset_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("int[8](-1, 1, 0, 0, -1, -1, 1, 1)", "int[8](-1, 1, 0, 0, -1, -1, 1, 2)")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("kDx") },
        )
    }

    @Test
    fun a_wrap_first_demosaic_is_reported() {
        // Swap the clamp and the site test: the validator must reject the
        // order change (border pixels would diverge from the CPU kernel).
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val clamp = "int nx = min(max(g.x + kDx[k], 0), maxI);"
        val wrapped = src.replace(clamp, "ivec2 np0 = ivec2(g.x + kDx[k], g.y + kDy[k]);")
        assertTrue(
            GlesShaderValidator.problems(wrapped, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("clamp must precede the site test") },
        )
    }

    @Test
    fun a_wrong_oetf_constant_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("1.099 * pow(lin, 0.45) - 0.099", "1.099 * pow(lin, 0.45) - 0.1")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("OETF power segment") },
        )
    }

    @Test
    fun a_missing_store_binding_is_reported() {
        val oldForm = GlesShaderSources.STORE_FRAGMENT
            .replace(
                "layout(std430, binding = 0) writeonly buffer OutBuf",
                "buffer OutBuf",
            )
        assertTrue(
            GlesShaderValidator.problems(oldForm, GlesShaderValidator.Kind.STORE_FRAGMENT)
                .any { it.contains("layout(std430, binding = 0) writeonly buffer OutBuf") },
        )
    }

    @Test
    fun a_vertex_uniform_is_reported() {
        // The harness sets NO vertex uniforms; one appearing is a drift.
        val withUniform = GlesShaderSources.FULLSCREEN_VERTEX
            .replace("precision highp float;", "precision highp float;\nuniform vec2 uHalfViewport;")
        assertTrue(
            GlesShaderValidator.problems(withUniform, GlesShaderValidator.Kind.FULLSCREEN_VERTEX)
                .any { it.contains("uniform") },
        )
    }

    @Test
    fun a_missing_store_color_output_is_reported() {
        // The throwaway oCol write keeps the readback pass legal on
        // drivers that dislike zero-output fragment shaders — dropping it
        // must be caught (the SSBO alone is not a legal fragment output on
        // every ES driver).
        val noOcol = GlesShaderSources.STORE_FRAGMENT
            .replace("out vec4 oCol;\n", "")
            .replace("    oCol = vec4(c, 1.0);\n", "")
        assertTrue(
            GlesShaderValidator.problems(noOcol, GlesShaderValidator.Kind.STORE_FRAGMENT)
                .any { it.contains("oCol") },
        )
    }

    @Test
    fun a_300_vertex_checked_as_310_is_reported() {
        // The store program MUST link the 310-es vertex (GLSL ES forbids
        // mixed #version lines); passing the 300-es one by mistake is a
        // link error on the device — the validator catches it host-side.
        val problems = GlesShaderValidator.problems(
            GlesShaderSources.FULLSCREEN_VERTEX,
            GlesShaderValidator.Kind.FULLSCREEN_VERTEX_310,
        )
        assertTrue(problems.any { it.contains("#version 310 es") })
    }

    // ---- BLIT_FRAGMENT (GPU deliverable F: the encoder-Surface blit) ----

    @Test
    fun shipped_blit_fragment_passes_every_check() {
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.BLIT_FRAGMENT,
                GlesShaderValidator.Kind.BLIT_FRAGMENT,
            ),
        )
    }

    @Test
    fun blit_fragment_is_in_the_every_shipped_source_matrix() {
        // every_shipped_source_passes_validate_with_its_kind is the matrix
        // that keeps a NEW kind honest — this asserts the blit is actually
        // IN it (a silent matrix omission would leave the kind unchecked).
        val problems = GlesShaderValidator.problems(
            GlesShaderSources.BLIT_FRAGMENT,
            GlesShaderValidator.Kind.BLIT_FRAGMENT,
        )
        assertTrue(problems.isEmpty())
    }

    @Test
    fun a_missing_y_flip_is_reported() {
        // The ONE line that keeps the encode right-side up: without the
        // flip every encoded frame is upside down (the encoder reads the
        // Surface buffer top-row-first while GL's row 0 is the render
        // target's bottom row).
        val noFlip = GlesShaderSources.BLIT_FRAGMENT
            .replace("uSize.y - 1 - g.y", "g.y")
        assertTrue(
            GlesShaderValidator.problems(noFlip, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("Y flip") || it.contains("uSize.y - 1 - g.y") },
        )
    }

    @Test
    fun a_renamed_blit_uniform_is_reported() {
        val renamed = GlesShaderSources.BLIT_FRAGMENT
            .replace("uniform highp sampler2D uSrc;", "uniform highp sampler2D uTex;")
            .replace("texelFetch(uSrc, src, 0)", "texelFetch(uTex, src, 0)")
        assertTrue(
            GlesShaderValidator.problems(renamed, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("uSrc") },
        )
    }

    @Test
    fun a_missing_blit_texel_fetch_is_reported() {
        // texelFetch — the integer-coordinate fetch with NO sampling
        // filters — is the readback contract; a smoothstep-ish
        // texture() call would silently filter.
        val noFetch = GlesShaderSources.BLIT_FRAGMENT
            .replace("texelFetch(uSrc, src, 0)", "texture(uSrc, vec2(src))")
        assertTrue(
            GlesShaderValidator.problems(noFetch, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("texelFetch") },
        )
    }

    @Test
    fun a_missing_blit_size_uniform_is_reported() {
        val noSize = GlesShaderSources.BLIT_FRAGMENT
            .replace("uniform ivec2 uSize;", "")
            .replace("uSize.y - 1 - g.y", "1023 - g.y")
        assertTrue(
            GlesShaderValidator.problems(noSize, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("uSize") },
        )
    }
}
