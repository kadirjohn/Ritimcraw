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
            "uniform int uHasShading;",
            "uniform highp sampler2D uShading;",
            "uniform ivec2 uShadingSize;",
            "uniform int uHr;",
            "uniform vec3 uHrGain;",
            "uniform vec3 uWb;",
            "uniform mat3 uCam2rgb;",
            "uniform int uHasProfile;",
            "uniform float uExposure;",
            "uniform float uHighlights;",
            "uniform int uTonemap;",
            "uniform int uOetf;",
        ).forEach { u ->
            assertTrue("missing uniform: $u", src.contains(u))
        }
    }

    // ---- stages 2+3 (the default grade's own stages) are locked to the
    // CPU kernels they mirror (rawedit::sampleShading + the stage-3 cell
    // pass, core/src/Develop.cpp) ----

    @Test
    fun develop_fragment_carries_the_stage2_shading_bilinear() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The CPU sampleShading bilinear, verbatim: clamped normalized
        // coordinates, edge-clamped x1/y1, the two-lerp tail.
        assertTrue(
            "clamped fx missing",
            src.contains("min(max(u, 0.0), 1.0) * (float(uShadingSize.x) - 1.0)"),
        )
        assertTrue(
            "clamped fy missing",
            src.contains("min(max(v, 0.0), 1.0) * (float(uShadingSize.y) - 1.0)"),
        )
        assertTrue(
            "x1 edge clamp missing",
            src.contains("int x1 = min(x0 + 1, uShadingSize.x - 1);"),
        )
        assertTrue(
            "y1 edge clamp missing",
            src.contains("int y1 = min(y0 + 1, uShadingSize.y - 1);"),
        )
        assertTrue(
            "bilinear tail missing",
            src.contains("return a + (b - a) * ty;"),
        )
        // The stage-2 loop: the gain multiplies the post-stage-1 value,
        // with the CPU's du/dv normalized sensor coordinates and the
        // cellSlot plane order.
        assertTrue(
            "stage-2 gain multiply missing",
            src.contains("v *= sampleShading(float(p.x) * du, float(p.y) * dv, slot);"),
        )
        assertTrue(
            "du guard missing",
            src.contains("float du = uSize.x > 1 ? 1.0 / float(uSize.x - 1) : 0.0;"),
        )
        assertTrue(
            "dv guard missing",
            src.contains("float dv = uSize.y > 1 ? 1.0 / float(uSize.y - 1) : 0.0;"),
        )
    }

    @Test
    fun develop_fragment_carries_the_stage3_cell_pass() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The CPU stage-3 cell pass, verbatim: the toggle gate, the
        // per-color clipped criterion, the ceiling level, the unclipped
        // passthrough, and the cap.
        assertTrue(
            "stage-3 gate missing",
            src.contains("if (uHr != 1) return normAt(p);"),
        )
        assertTrue(
            "clipped criterion missing",
            src.contains("if (v >= 1.0) clipped[color] = true;"),
        )
        assertTrue(
            "ceiling level missing",
            src.contains("float level = minV[c] * uHrGain[c];"),
        )
        assertTrue(
            "unclipped passthrough missing",
            src.contains("if (v < 1.0) return v;"),
        )
        assertTrue(
            "cap missing",
            src.contains("float cap = ceiling / uHrGain[siteAt(p)];"),
        )
        assertTrue(
            "cap application missing",
            src.contains("return v > cap ? cap : v;"),
        )
    }

    @Test
    fun develop_fragment_demosaic_reads_the_post_stage3_value() {
        // The demosaic must read hrAt (post-stage-3), not stage1: the
        // CPU demosaics the capped norm buffer. All three read sites.
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        assertTrue("own site wrong", src.contains("px[t] = hrAt(g);"))
        assertTrue("neighbor accumulation wrong", src.contains("sum += hrAt(np);"))
        assertTrue(
            "n==0 fallback wrong",
            src.contains("px[t] = n > 0 ? sum / float(n) : hrAt(g);"),
        )
    }

    // ---- stage 23 (tonemap) is locked to the CPU math it mirrors
    // (rawedit::tonemapFrameImpl, core/src/Develop.cpp): the ACES fit, the
    // AgX encode window, the look table, the two evaluation orders and the
    // clamp-then-pow tail ----

    @Test
    fun develop_fragment_carries_the_cpu_aces_fit() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The Narkowicz 2015 fit verbatim: the five constants, the
        // rational function with the den>0 guard, the [0,1] clamp.
        assertTrue(
            "ACES constants missing",
            src.contains("const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;"),
        )
        assertTrue(
            "ACES numerator missing",
            src.contains("float num = x * (a * x + b);"),
        )
        assertTrue(
            "ACES denominator missing",
            src.contains("float den = x * (c * x + d) + e;"),
        )
        assertTrue(
            "ACES den>0 guard missing",
            src.contains("float y = den > 0.0 ? num / den : 0.0;"),
        )
        assertTrue(
            "ACES clamp missing",
            src.contains("return min(max(y, 0.0), 1.0);"),
        )
    }

    @Test
    fun develop_fragment_carries_the_cpu_agx_encode() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The 6th-order contrast polynomial and the encode window, both
        // verbatim: the v<=0 log2 guard, the EV clamps, the [0,1] clamp of
        // the contrast result BEFORE any pow (the polynomial dips to
        // -0.00232 at t=0; the Punchy pow must never see a negative base).
        assertTrue(
            "contrast polynomial head missing",
            src.contains("15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4"),
        )
        assertTrue(
            "contrast polynomial tail missing",
            src.contains("- 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;"),
        )
        assertTrue(
            "log2 guard missing",
            src.contains("if (v <= 0.0) return 0.0;"),
        )
        assertTrue(
            "EV window floor missing",
            src.contains("const float minEv = -12.47393;"),
        )
        assertTrue(
            "EV window ceiling missing",
            src.contains("const float maxEv = 4.026069;"),
        )
        assertTrue(
            "pre-pow clamp missing",
            src.contains("return min(max(agxContrastApprox(t), 0.0), 1.0);"),
        )
    }

    @Test
    fun develop_fragment_carries_the_cpu_agx_matrices_and_looks() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The inset/outset rows and the look table, verbatim: Golden's
        // slope, the sat table, Greyscale's BT.2020 luma weights, Punchy's
        // power.
        assertTrue(
            "inset row 0 missing",
            src.contains("const vec3 kAgxInsetR = vec3(0.856627153315983, 0.0951212405381588, 0.0482516061458583);"),
        )
        assertTrue(
            "inset row 1 missing",
            src.contains("const vec3 kAgxInsetG = vec3(0.137318972929847, 0.761241990602591, 0.101439036467562);"),
        )
        assertTrue(
            "inset row 2 missing",
            src.contains("const vec3 kAgxInsetB = vec3(0.11189821299995, 0.0767994186031903, 0.811302368396859);"),
        )
        assertTrue(
            "outset row 0 missing",
            src.contains("const vec3 kAgxOutsetR = vec3(1.1271005818144366432, -0.1106066430966032116, -0.016493938717834568157);"),
        )
        assertTrue(
            "outset row 1 missing",
            src.contains("const vec3 kAgxOutsetG = vec3(-0.14132976349843826565, 1.1578237022162717623, -0.01649393871783425265);"),
        )
        assertTrue(
            "outset row 2 missing",
            src.contains("const vec3 kAgxOutsetB = vec3(-0.14132976349843824772, -0.11060664309660291788, 1.2519364065950402828);"),
        )
        assertTrue(
            "Golden slope missing",
            src.contains("vec3(1.0, 0.9, 0.5),"),
        )
        assertTrue(
            "sat table missing",
            src.contains("const float kAgxSat[4] = float[4](1.4, 1.3, 0.0, 1.0);"),
        )
        assertTrue(
            "Greyscale BT.2020 luma weights missing",
            src.contains("vec3(0.2589235355689848, 0.6104985346066525, 0.13057792982436284),"),
        )
        assertTrue(
            "Punchy power missing",
            src.contains("vec3(1.35, 1.35, 1.35),"),
        )
    }

    @Test
    fun develop_fragment_carries_the_two_look_orders_and_the_tail() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // Punchy's legacy order: the luma line must PRECEDE the pow line
        // (luma from PRE-power sigmoid values). The ASC CDL order: the
        // slope+power line first, then the luma of the powered values.
        // The tail: clamp BEFORE the pow-2.2 (NaN guard).
        val punchyLuma = src.indexOf("float luma = dot(kAgxLumaW[look], v);")
        val punchyPow = src.indexOf("v = luma + kAgxSat[look] * (pow(v, kAgxPower[look]) - luma);")
        assertTrue(
            "Punchy legacy order broken (luma must precede the pow)",
            punchyLuma in 0..punchyPow,
        )
        val cdlPow = src.indexOf("v = pow(v * kAgxSlope[look], kAgxPower[look]);")
        val cdlLuma = src.indexOf("float luma = dot(kAgxLumaW[look], v);", cdlPow + 1)
        assertTrue(
            "ASC CDL order broken (luma of the powered values must follow the pow)",
            cdlPow >= 0 && cdlLuma > cdlPow,
        )
        assertTrue(
            "Punchy branch gate missing",
            src.contains("if (uTonemap == 2) {"),
        )
        assertTrue(
            "outset clamp missing",
            src.contains("o = clamp(o, 0.0, 1.0);"),
        )
        assertTrue(
            "gamma-2.2 tail missing",
            src.contains("return pow(o, vec3(2.2));"),
        )
    }

    @Test
    fun develop_fragment_applies_tonemap_between_exposure_and_oetf() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The CPU stage order 14 -> 15 -> 23 -> OETF: the highlights and
        // tonemap applications must sit between the exposure multiply and
        // the OETF block, highlights first (the CPU stage order).
        val exposure = src.indexOf("c *= uExposure;")
        val highlights = src.indexOf("if (uHighlights != 0.0) {")
        val tonemap = src.indexOf("if (uTonemap != 0) {")
        val oetf = src.indexOf("if (uOetf == 1) {")
        assertTrue(
            "highlights application point wrong (must sit between exposure and tonemap)",
            exposure in 0..highlights && highlights in 0..tonemap,
        )
        assertTrue(
            "tonemap application point wrong (must sit between highlights and OETF)",
            tonemap in 0..oetf,
        )
    }

    // ---- stage 15 (highlights) is locked to the CPU math it mirrors
    // (the stage-15 shoulder, core/src/Develop.cpp): the knee, the
    // monotone shoulder on the max channel, the blend and the uniform
    // per-pixel gain ----

    @Test
    fun develop_fragment_carries_the_cpu_stage15_shoulder() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        // The CPU stage-15 shoulder, verbatim: the gate (0.0 = the CPU's
        // own `if (grade.highlights != 0.0f)` skip), the knee, the
        // strength SIGN (recovery -> +, lift -> -), the max-channel test,
        // the monotone shoulder, the blend and the uniform gain.
        assertTrue(
            "stage-15 gate missing",
            src.contains("if (uHighlights != 0.0) {"),
        )
        assertTrue(
            "knee missing",
            src.contains("const float knee = 0.75;"),
        )
        assertTrue(
            "span missing",
            src.contains("const float span = 1.0 - knee;"),
        )
        assertTrue(
            "strength sign missing (recovery -> +, lift -> -)",
            src.contains("float s = -uHighlights / 100.0;"),
        )
        assertTrue(
            "max channel missing",
            src.contains("float mx = max(c.r, max(c.g, c.b));"),
        )
        assertTrue(
            "knee test missing",
            src.contains("if (mx > knee) {"),
        )
        assertTrue(
            "monotone shoulder missing",
            src.contains("float shoulder = knee + span * (t / (1.0 + t));"),
        )
        assertTrue(
            "blend missing",
            src.contains("float newMx = mx + s * (shoulder - mx);"),
        )
        assertTrue(
            "gain missing",
            src.contains("float gain = mx > 0.0 ? newMx / mx : 1.0;"),
        )
        assertTrue(
            "gain application missing",
            src.contains("c *= gain;"),
        )
    }

    @Test
    fun a_renamed_shading_uniform_is_reported() {
        val renamed = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("uniform int uHasShading;", "uniform int uHasLensShading;")
        assertTrue(
            GlesShaderValidator.problems(renamed, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("uHasShading") },
        )
    }

    @Test
    fun a_broken_shading_bilinear_is_reported() {
        // The two-lerp tail is the CPU's exact interpolation; a
        // nearest-neighbor rewrite would silently change every gain.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("return a + (b - a) * ty;", "return a;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("shading bilinear tail") },
        )
    }

    @Test
    fun a_wrong_stage3_cap_is_reported() {
        // cap = ceiling / gain[site] is the CPU's pre-WB inverse; a
        // direct ceiling cap would double-apply the WB gain.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("float cap = ceiling / uHrGain[siteAt(p)];", "float cap = ceiling;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("stage-3 cap") },
        )
    }

    @Test
    fun a_stage3_gate_removed_is_reported() {
        // grade.highlightReconstruction false must skip the whole stage
        // (RAW-04): losing the gate would cap frames the grade asked to
        // keep raw.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("if (uHr != 1) return normAt(p);", "if (uHr < 0) return normAt(p);")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("stage-3 gate") },
        )
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
    fun a_wrong_aces_constant_is_reported() {
        // One ACES coefficient off by a digit: the fit curve silently
        // changes everywhere.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;",
                     "const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.15;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("ACES fit constants") },
        )
    }

    @Test
    fun a_missing_aces_den_guard_is_reported() {
        // The den>0 guard keeps the rational function defined at x=0;
        // dropping it can produce NaN/Inf at the black point.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("float y = den > 0.0 ? num / den : 0.0;", "float y = num / den;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("ACES den>0 guard") },
        )
    }

    @Test
    fun a_wrong_agx_ev_window_is_reported() {
        // The EV window defines the encode's dynamic range; a shifted
        // ceiling changes where highlights roll off.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("const float maxEv = 4.026069;", "const float maxEv = 4.026070;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("AgX EV window ceiling") },
        )
    }

    @Test
    fun a_missing_agx_prepow_clamp_is_reported() {
        // The polynomial dips to -0.00232 at t=0; without the [0,1]
        // clamp the Punchy pow() sees a negative base (NaN).
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("return min(max(agxContrastApprox(t), 0.0), 1.0);",
                     "return agxContrastApprox(t);")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("clamp before any pow") },
        )
    }

    @Test
    fun a_wrong_greyscale_luma_weights_are_reported() {
        // Greyscale's luma weights are BT.2020, not the Rec.709 triple
        // every other look uses; swapping them changes the grey axis.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("vec3(0.2589235355689848, 0.6104985346066525, 0.13057792982436284),",
                     "vec3(0.2126, 0.7152, 0.0722),")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("BT.2020 luma weights") },
        )
    }

    @Test
    fun a_punchy_order_swap_is_reported() {
        // Swap the Punchy branch to the ASC CDL order: the validator must
        // reject the evaluation-order change (byte-identical old grades).
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val legacy = "float luma = dot(kAgxLumaW[look], v);\n" +
            "        v = luma + kAgxSat[look] * (pow(v, kAgxPower[look]) - luma);"
        val swapped = "v = pow(v * kAgxSlope[look], kAgxPower[look]);\n" +
            "        float luma = dot(kAgxLumaW[look], v);\n" +
            "        v = luma + kAgxSat[look] * (v - luma);"
        val mutated = src.replace(legacy, swapped)
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != src,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("Punchy legacy order is broken") },
        )
    }

    @Test
    fun a_missing_agx_gamma_tail_is_reported() {
        // The pow(o, 2.2) tail is the look's output encoding; dropping it
        // double-encodes on the OETF side (a dark, contrasty render).
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("return pow(o, vec3(2.2));", "return o;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("gamma-2.2 tail") },
        )
    }

    @Test
    fun a_tonemap_local_with_const_initializer_is_reported() {
        // GLSL ES 3.00 const requires a constant-expression initializer;
        // the CPU's read-only const does not carry. A reintroduced `const`
        // on a parameter/uniform-derived local is a device compile error.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("float luma = dot(kAgxLumaW[look], v);",
                     "const float luma = dot(kAgxLumaW[look], v);")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("non-constant") },
        )
    }

    @Test
    fun a_tonemap_applied_after_the_oetf_is_reported() {
        // Tonemap after the OETF double-encodes: the CPU stage order is
        // 14 -> 23 -> OETF, never 14 -> OETF -> 23. The swap moves the
        // whole tonemap block past the whole OETF block, byte-identical
        // bodies, only the order changes.
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val tonemap = "if (uTonemap != 0) {\n" +
            "        c = tonemapPixel(c);\n" +
            "    }"
        val oetf = "if (uOetf == 1) {\n" +
            "        c = vec3(linearToRec709(c.r), linearToRec709(c.g), linearToRec709(c.b));\n" +
            "    }"
        val original = tonemap + "\n    " + oetf
        val reordered = oetf + "\n    " + tonemap
        assertTrue(
            "mutation setup wrong: the blocks are not adjacent as shipped",
            src.contains(original),
        )
        val mutated = src.replace(original, reordered)
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != src,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("application point is wrong") },
        )
    }

    // ---- stage-15 (highlights) mutations: the shoulder's every constant
    // and the application point are load-bearing — a rewrite that changes
    // any of them changes the owner's routine recovery shape ---

    @Test
    fun a_stage15_strength_sign_flip_is_reported() {
        // s = -uHighlights / 100.0 is the CPU's exact sign convention
        // (recovery -> +, lift -> -). Flipping it turns recovery into lift
        // and back — byte-identical source shape, opposite grade.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("float s = -uHighlights / 100.0;",
                     "float s = uHighlights / 100.0;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DEVELOP_FRAGMENT,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("stage-15 strength") },
        )
    }

    @Test
    fun a_stage15_gate_removed_is_reported() {
        // Dropping the `!= 0.0` gate (running the shoulder unconditionally)
        // changes nothing at uHighlights=0 numerically EXCEPT through the
        // mx > knee branch — but it deletes the CPU's own skip, and a
        // rewired gate (`!= 1.0` etc.) would leave the default grade
        // shouldered. The validator locks the gate text.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("if (uHighlights != 0.0) {", "if (uHighlights != 1.0) {")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("stage-15 gate") },
        )
    }

    @Test
    fun a_stage15_local_with_const_initializer_is_reported() {
        // Same GLSL ES 3.00 rule the tonemap locals ride: a reintroduced
        // `const` on the uniform-derived strength is a device compile error.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT
            .replace("float s = -uHighlights / 100.0;",
                     "const float s = -uHighlights / 100.0;")
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("non-constant") },
        )
    }

    @Test
    fun a_highlights_block_moved_after_tonemap_is_reported() {
        // The CPU stage order is 14 -> 15 -> 23 -> OETF. Moving the
        // highlights block AFTER the tonemap block reorders the shoulder
        // across the tonemap — a different image, not a refactor. The
        // blocks are adjacent as shipped (highlights then tonemap), so the
        // swap is byte-identical bodies, only the order changes.
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val highlights = "if (uHighlights != 0.0) {"
        val tonemap = "if (uTonemap != 0) {"
        val hIdx = src.indexOf(highlights)
        val tIdx = src.indexOf(tonemap)
        assertTrue("mutation setup wrong: highlights block missing", hIdx >= 0)
        assertTrue("mutation setup wrong: tonemap block missing", tIdx > hIdx)
        val hBlock = src.substring(hIdx, tIdx)
        val tStart = tIdx
        val tEnd = src.indexOf("if (uOetf == 1) {")
        assertTrue("mutation setup wrong: OETF block missing", tEnd > tStart)
        val tBlock = src.substring(tStart, tEnd)
        val original = hBlock + tBlock
        val reordered = tBlock + hBlock
        val mutated = src.replace(original, reordered)
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != src,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT)
                .any { it.contains("application point is wrong") },
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

    @Test
    fun a_missing_blit_rotation_fold_is_reported() {
        // The surface-gpu rotation deliverable: without the fold a 90/270
        // frame blits sideways (the session would hand the shader a
        // rotation it cannot apply). Mutations: drop the uniform, and
        // drop the fold while leaving a decoy uniform behind — each must
        // be caught by its own check.
        val noUniform = GlesShaderSources.BLIT_FRAGMENT
            .replace("uniform int uRotation;", "")
        assertTrue(
            GlesShaderValidator.problems(noUniform, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("uRotation") },
        )
        val noFold = GlesShaderSources.BLIT_FRAGMENT
            .replace("uRotation == 90", "uRotation == 1")
        assertTrue(
            GlesShaderValidator.problems(noFold, GlesShaderValidator.Kind.BLIT_FRAGMENT)
                .any { it.contains("uRotation == 90") },
        )
    }

    // ---- stage-15 host numeric parity: the CPU loop's Kotlin
    // transcription vs the shipped GLSL block's Kotlin transcription,
    // compared after the readback quantize (the toByte tail: clamp
    // [0,1], then v*255+0.5). HONESTY: this locks that the two shipped
    // TEXTS evaluate identically over the shoulder's every branch — it
    // is NOT a device parity claim (no GLSL compiler runs here; the
    // device harness's cases still ride uHighlights=0.0, and the owner's
    // covered-grade export is the production proof) ----

    private fun cpuStage15(
        r: Float,
        g: Float,
        b: Float,
        highlights: Float,
    ): Triple<Float, Float, Float> {
        // core/src/Develop.cpp stage 15, transcribed verbatim (the
        // byte-exact definition the GLSL matches).
        if (highlights != 0.0f) {
            val knee = 0.75f
            val span = 1.0f - knee
            val s = -highlights / 100.0f // recovery -> +1, lift -> -1
            val mx = maxOf(r, maxOf(g, b))
            if (mx <= knee) return Triple(r, g, b)
            val over = mx - knee
            val t = over / span // 0..1+ above the knee
            // Monotone shoulder: bounded, order-preserving for all t.
            val shoulder = knee + span * (t / (1.0f + t))
            // Blend original -> shoulder by strength (s in [-1, 1]).
            val newMx = mx + s * (shoulder - mx)
            val gain = if (mx > 0.0f) newMx / mx else 1.0f
            return Triple(r * gain, g * gain, b * gain)
        }
        return Triple(r, g, b)
    }

    private fun glslStage15(
        r: Float,
        g: Float,
        b: Float,
        uHighlights: Float,
    ): Triple<Float, Float, Float> {
        // The shipped GLSL block, transcribed verbatim (the uHighlights
        // != 0.0 gate, the mx > knee branch — the CPU skip's complement).
        if (uHighlights != 0.0f) {
            val knee = 0.75f
            val span = 1.0f - knee
            val s = -uHighlights / 100.0f
            val mx = maxOf(r, maxOf(g, b))
            if (mx > knee) {
                val over = mx - knee
                val t = over / span
                val shoulder = knee + span * (t / (1.0f + t))
                val newMx = mx + s * (shoulder - mx)
                val gain = if (mx > 0.0f) newMx / mx else 1.0f
                return Triple(r * gain, g * gain, b * gain)
            }
        }
        return Triple(r, g, b)
    }

    private fun toByte(v: Float): Int = (v.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()

    @Test
    fun stage15_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // Representative pixels — every branch of the shoulder: mx==0
        // (the gain guard), mx<knee (skip), mx==knee (skip, the boundary),
        // slightly>knee, mx==1, mx>1 (post-exposure headroom), a neutral
        // above the knee (neutrals stay neutral — the uniform gain), a
        // saturated single channel, a near-tie max and a lone red above
        // the knee.
        val pixels = listOf(
            Triple(0.0f, 0.0f, 0.0f),
            Triple(0.5f, 0.25f, 0.1f),
            Triple(0.75f, 0.3f, 0.6f),
            Triple(0.76f, 0.4f, 0.2f),
            Triple(1.0f, 0.9f, 0.85f),
            Triple(1.2f, 0.7f, 0.3f),
            Triple(0.9f, 0.9f, 0.9f),
            Triple(0.3f, 1.1f, 0.05f),
            Triple(0.8f, 0.8000001f, 0.1f),
            Triple(0.85f, 0.0f, 0.0f),
        )
        // recovery -55, lift +55, and 0 — the fast path: both
        // transcriptions skip the whole stage, so the bytes are
        // identical to the pre-stage-15 render (the unset uniform's 0.0
        // default is exactly the CPU's own skip).
        listOf(-55f, 55f, 0f).forEach { highlights ->
            var mismatches = 0
            var maxLsb = 0
            pixels.forEach { (r, g, b) ->
                val cpu = cpuStage15(r, g, b, highlights)
                val glsl = glslStage15(r, g, b, highlights)
                val cpuBytes = intArrayOf(
                    toByte(cpu.first), toByte(cpu.second), toByte(cpu.third),
                )
                val glslBytes = intArrayOf(
                    toByte(glsl.first), toByte(glsl.second), toByte(glsl.third),
                )
                for (c in 0..2) {
                    val lsb = Math.abs(cpuBytes[c] - glslBytes[c])
                    if (lsb > 0) mismatches++
                    if (lsb > maxLsb) maxLsb = lsb
                }
            }
            assertEquals("highlights $highlights: mismatched bytes", 0, mismatches)
            assertEquals("highlights $highlights: max LSB", 0, maxLsb)
        }
    }
}
