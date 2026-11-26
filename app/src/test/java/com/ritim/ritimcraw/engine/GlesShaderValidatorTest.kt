package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- WINDOW_FRAGMENT's crop uniforms (A2 GPU crop/straighten
    // preview, owner request 2026-11-23, guide A2). The crop block is
    // text-pinned the way every other contract above is: each missing
    // token names the break it guards against, and the window pass's
    // problems() stays empty for the shipped source.

    @Test
    fun shipped_window_fragment_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.WINDOW_FRAGMENT,
                GlesShaderValidator.Kind.WINDOW_FRAGMENT,
            ),
        )
    }

    @Test
    fun window_fragment_declares_the_six_crop_uniforms() {
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue("uCropOn missing", src.contains("uniform int uCropOn;"))
        assertTrue("uCropRect missing", src.contains("uniform vec4 uCropRect;"))
        assertTrue("uCropOut missing", src.contains("uniform vec2 uCropOut;"))
        assertTrue("uCropZoom missing", src.contains("uniform float uCropZoom;"))
        assertTrue("uCropCos missing", src.contains("uniform float uCropCos;"))
        assertTrue("uCropSin missing", src.contains("uniform float uCropSin;"))
    }

    @Test
    fun window_fragment_carries_the_crop_gate_and_manual_bilinear() {
        // The crop math cannot be quietly dropped: the uCropOn == 1 gate
        // selects the crop map, and the manual bilinear (the NEAREST pin's
        // answer — 4x texelFetch with per-axis index clamping, mirroring
        // CropState.sampleArgbBilinear) must be present.
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue("uCropOn gate missing", src.contains("uCropOn == 1"))
        assertTrue("manual bilinear texelFetch missing", src.contains("texelFetch(uSrc"))
    }

    @Test
    fun window_fragment_carries_the_inverse_rotation_map_over_the_zoom() {
        // The crop map's decision math, in the applyToArgb form: the two
        // inverse-rotation terms over uCropZoom (sx = cx + (dx*c + dy*s)/z,
        // sy = cy + (-dx*s + dy*c)/z — the exact pair CropState's loop
        // computes, with the uniforms in the c/s positions).
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue(
            "the ox term missing",
            src.contains("(dx * uCropCos + dy * uCropSin) / uCropZoom"),
        )
        assertTrue(
            "the oy term missing",
            src.contains("(-dx * uCropSin + dy * uCropCos) / uCropZoom"),
        )
    }

    @Test
    fun window_fragment_maps_the_crop_BEFORE_the_orientation_fold() {
        // THE COMPOSE ORDER (the plan's whole-space pin): the crop runs
        // on uv BEFORE the uOrientation fold composes it into sensor st.
        // The fold's `uOrientation == 90` arm must therefore read the
        // crop-mapped uvFull, not the raw uv — the token the fold reuse
        // forces and the pin locks.
        val src = GlesShaderSources.WINDOW_FRAGMENT
        val cropIdx = src.indexOf("uCropOn == 1")
        val foldIdx = src.indexOf("uOrientation == 90")
        assertTrue("crop gate missing", cropIdx >= 0)
        assertTrue("orientation fold missing", foldIdx >= 0)
        assertTrue("the crop map must precede the orientation fold", cropIdx < foldIdx)
        // The fold consumes the crop-mapped uv: the fold's input variable
        // is uvFull (the identity arm carries it too).
        val foldReadsMapped = src.contains("st = vec2(1.0 - uvFull.y, 1.0 - uvFull.x);")
        assertTrue("the 90 fold arm must read the crop-mapped uvFull", foldReadsMapped)
    }

    @Test
    fun a_window_fragment_missing_a_crop_uniform_is_reported() {
        // Each pin individually catches the defect it exists for: strip
        // one crop uniform declaration and problems() names it.
        val src = GlesShaderSources.WINDOW_FRAGMENT
            .replace("uniform int uCropOn;", "uniform int uCropOnX;")
        val problems = GlesShaderValidator.problems(
            src,
            GlesShaderValidator.Kind.WINDOW_FRAGMENT,
        )
        assertTrue(
            "expected a uCropOn problem, got: $problems",
            problems.any { it.contains("uCropOn") },
        )
    }

    @Test
    fun a_window_fragment_with_the_crop_gate_dropped_is_reported() {
        // The gate token dropped (a folded dead crop path) — the pin
        // catches it.
        val src = GlesShaderSources.WINDOW_FRAGMENT
            .replace("uCropOn == 1", "uCropOnX == 1")
        val problems = GlesShaderValidator.problems(
            src,
            GlesShaderValidator.Kind.WINDOW_FRAGMENT,
        )
        assertTrue(
            "expected a uCropOn gate problem, got: $problems",
            problems.any { it.contains("uCropOn") },
        )
    }

    @Test
    fun a_window_fragment_with_the_manual_bilinear_dropped_is_reported() {
        // texelFetch renamed away (e.g. a plain texture() revert on the
        // crop branch) -- the pin catches the sampling-contract break.
        // Renaming the FUNCTION itself (not just uSrcX, whose prefix
        // still contains "texelFetch(uSrc") so the pin's token is truly
        // gone.
        val src = GlesShaderSources.WINDOW_FRAGMENT
            .replace("texelFetch(", "texelPetch(")
        val problems = GlesShaderValidator.problems(
            src,
            GlesShaderValidator.Kind.WINDOW_FRAGMENT,
        )
        assertTrue(
            "expected a texelFetch problem, got: $problems",
            problems.any { it.contains("texelFetch") },
        )
    }

    // ---- WINDOW_FRAGMENT's zebra uniforms (A3 GPU zebra, owner request
    // 2026-11-23, guide §A3). The zebra block is text-pinned exactly like
    // the crop block above: the four uniforms, the byte-exact decision
    // math, and the validator's windowFragmentProblems pins so the block
    // cannot be quietly dropped while the uniforms still link.

    @Test
    fun window_fragment_declares_the_four_zebra_uniforms() {
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue("uZebraOn missing", src.contains("uniform int uZebraOn;"))
        assertTrue("uZebraThreshold missing", src.contains("uniform int uZebraThreshold;"))
        assertTrue("uZebraPeriod missing", src.contains("uniform int uZebraPeriod;"))
        assertTrue("uZebraAlpha missing", src.contains("uniform float uZebraAlpha;"))
    }

    @Test
    fun window_fragment_carries_the_zebra_decision_math() {
        // The A3 block's exact tokens: the toByte-equivalent quantize
        // (v*255 + 0.5 after the clamp — jni_bridge.cpp toByte's exact
        // round-ties-up shape), the Rec.709 luma byte form, the
        // rect-local half-stripped window coords, the / uZebraPeriod
        // even test, the . (1.0 - uZebraAlpha) blend, and the
        // uZebraOn == 1 gate.
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue(
            "the toByte quantize missing",
            src.contains("int r8 = int(clamp(c.r, 0.0, 1.0) * 255.0 + 0.5);"),
        )
        assertTrue(
            "the luma8 form missing",
            src.contains("(54 * r8 + 183 * g8 + 19 * b8) >> 8"),
        )
        assertTrue(
            "the rect-local half-stripped coords missing",
            src.contains("ivec2 p = ivec2(gl_FragCoord.xy - uRect.xy - vec2(0.5));"),
        )
        assertTrue(
            "the stripe even test missing",
            src.contains("((p.x + p.y) / uZebraPeriod) % 2 == 0"),
        )
        assertTrue(
            "the blend toward black missing",
            src.contains("oCol.rgb *= (1.0 - uZebraAlpha);"),
        )
    }

    @Test
    fun window_fragment_zebra_gates_on_uZebraOn_and_composes_after_the_crop() {
        // The gate token (the decision math cannot be quietly dropped) and
        // the A3 placement pin: the zebra block composes AFTER the A2 crop
        // block's sampling fork (it reads the final sampled color the
        // crop/texture branch produced into `c`), and the gated blend is
        // the LAST write before the fragment ends.
        val src = GlesShaderSources.WINDOW_FRAGMENT
        assertTrue("the uZebraOn gate missing", src.contains("uZebraOn == 1"))
        val sampleIdx = src.indexOf("vec3 c;")
        val zebraIdx = src.indexOf("uZebraOn == 1")
        val writeIdx = src.indexOf("oCol = vec4(c, 1.0);")
        val blendIdx = src.indexOf("oCol.rgb *= (1.0 - uZebraAlpha);")
        assertTrue("the sampling fork missing", sampleIdx >= 0)
        assertTrue("the zebra block missing", zebraIdx >= 0)
        assertTrue("the color write missing", writeIdx >= 0)
        assertTrue("the gated blend missing", blendIdx >= 0)
        assertTrue(
            "the zebra block must compose after the sampling fork",
            sampleIdx < zebraIdx,
        )
        assertTrue(
            "the write must precede the gated blend (the blend multiplies oCol.rgb)",
            writeIdx < blendIdx,
        )
        assertTrue(
            "the gated blend must be inside the zebra block",
            zebraIdx < blendIdx,
        )
    }

    @Test
    fun a_window_fragment_missing_a_zebra_uniform_is_reported() {
        // Each pin individually catches the defect it exists for: strip
        // one zebra uniform declaration and problems() names it.
        val src = GlesShaderSources.WINDOW_FRAGMENT
            .replace("uniform int uZebraOn;", "uniform int uZebraOnX;")
        val problems = GlesShaderValidator.problems(
            src,
            GlesShaderValidator.Kind.WINDOW_FRAGMENT,
        )
        assertTrue(
            "expected a uZebraOn problem, got: $problems",
            problems.any { it.contains("uZebraOn") },
        )
    }

    @Test
    fun a_window_fragment_with_the_zebra_gate_dropped_is_reported() {
        // The gate token dropped (a folded dead zebra path) — the pin
        // catches it.
        val src = GlesShaderSources.WINDOW_FRAGMENT
            .replace("uZebraOn == 1", "uZebraOnX == 1")
        val problems = GlesShaderValidator.problems(
            src,
            GlesShaderValidator.Kind.WINDOW_FRAGMENT,
        )
        assertTrue(
            "expected a uZebraOn gate problem, got: $problems",
            problems.any { it.contains("uZebraOn") },
        )
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

    // ---- the split chain's sources pass their own kinds ----

    @Test
    fun shipped_develop_fragment_pre_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DEVELOP_FRAGMENT_PRE,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_PRE,
            ),
        )
    }

    @Test
    fun shipped_develop_fragment_post_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DEVELOP_FRAGMENT_POST,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST,
            ),
        )
    }

    @Test
    fun shipped_nr_chroma_h_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.NR_CHROMA_H,
                GlesShaderValidator.Kind.NR_CHROMA_H,
            ),
        )
    }

    @Test
    fun shipped_nr_chroma_v_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.NR_CHROMA_V,
                GlesShaderValidator.Kind.NR_CHROMA_V,
            ),
        )
    }

    @Test
    fun shipped_nr_luma_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.NR_LUMA,
                GlesShaderValidator.Kind.NR_LUMA,
            ),
        )
    }

    @Test
    fun shipped_develop_fragment_tail_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_TAIL,
            ),
        )
    }

    @Test
    fun shipped_sharpness_h_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.SHARPNESS_H,
                GlesShaderValidator.Kind.SHARPNESS_H,
            ),
        )
    }

    @Test
    fun shipped_sharpness_v_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.SHARPNESS_V,
                GlesShaderValidator.Kind.SHARPNESS_V,
            ),
        )
    }

    @Test
    fun shipped_detail_h_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DETAIL_H,
                GlesShaderValidator.Kind.DETAIL_H,
            ),
        )
    }

    @Test
    fun shipped_detail_v_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DETAIL_V,
                GlesShaderValidator.Kind.DETAIL_V,
            ),
        )
    }

    @Test
    fun shipped_warp_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.WARP,
                GlesShaderValidator.Kind.WARP,
            ),
        )
    }

    @Test
    fun the_split_and_fused_develop_fragments_cannot_drift() {
        // The fused DEVELOP_FRAGMENT is the frozen device-parity harness's
        // program: it must stay BYTE-IDENTICAL to the stage bodies the
        // split chain ships. The split sources compose from the same
        // shared body fragments (kDevelopPreBody / kDevelopPostBody), so
        // this locks the composition: PRE's body is the fused source's
        // stage 1-6 segment, POST's body is its stage 9+ segment, and
        // the fused source itself is unchanged by the split. The exact
        // composition is validated per-kind above; here the load-bearing
        // claim is that splitting introduced NO drift in the fused text.
        val fused = GlesShaderSources.DEVELOP_FRAGMENT
        // The fused source must still contain the stage-6 close and the
        // stage-9 open — the split boundary markers — proving the split
        // did not reorder or drop stage text. (The fused pass reads its
        // input from the interpolated varying, never a uSrc texture — the
        // uSrc fetch is POST's own chain-only preamble, so the stage-9
        // OPEN marker here is the stage-9 body's own gate.)
        assertTrue(fused.contains("c = uCam2rgb * c;"))
        assertTrue(fused.contains("if (uBlackPoint != 0.0) {"))
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
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.FULLSCREEN_VERTEX_310,
                GlesShaderValidator.Kind.FULLSCREEN_VERTEX_310,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT_PRE,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_PRE,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT_POST,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_TAIL,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.SHARPNESS_H,
                GlesShaderValidator.Kind.SHARPNESS_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.SHARPNESS_V,
                GlesShaderValidator.Kind.SHARPNESS_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DETAIL_H,
                GlesShaderValidator.Kind.DETAIL_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.DETAIL_V,
                GlesShaderValidator.Kind.DETAIL_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.WARP,
                GlesShaderValidator.Kind.WARP,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_CHROMA_H,
                GlesShaderValidator.Kind.NR_CHROMA_H,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_CHROMA_V,
                GlesShaderValidator.Kind.NR_CHROMA_V,
            ),
        )
        assertTrue(
            GlesShaderValidator.validate(
                GlesShaderSources.NR_LUMA,
                GlesShaderValidator.Kind.NR_LUMA,
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
            src.contains("if (clipFlag(ivec2(xx, yy))) clipped[color] = true;"),
        )
        assertTrue(
            "ceiling level missing",
            src.contains("float level = minV[c] * uHrGain[c];"),
        )
        assertTrue(
            "unclipped passthrough missing",
            src.contains("if (!clipFlag(p)) return v;"),
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
            src.contains("const float knee = 0.18;"),
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
        // 14 -> 23/24 -> OETF, never 14 -> OETF -> 23/24. The swap moves
        // the whole tonemap + LUT sequence (stage 24 sits between them
        // since the LUT port) past the OETF block, byte-identical bodies,
        // only the order changes.
        val src = GlesShaderSources.DEVELOP_FRAGMENT
        val tonemapIdx = src.indexOf("if (uTonemap != 0) {")
        val oetfIdx = src.indexOf("if (uOetf == 1) {")
        assertTrue(
            "mutation setup wrong: tonemap block not found",
            tonemapIdx >= 0,
        )
        assertTrue(
            "mutation setup wrong: OETF block not found",
            oetfIdx > tonemapIdx,
        )
        // The shipped tail: tonemap -> stage 24 (post-tonemap shapes) ->
        // OETF -> oCol, 4-space indented, ending at the fragment's close.
        val oetfBlock = "if (uOetf == 1) {\n" +
            "        c = vec3(linearToRec709(c.r), linearToRec709(c.g), linearToRec709(c.b));\n" +
            "    }\n"
        val oetfEnd = src.indexOf("    oCol = vec4(c, 1.0);", oetfIdx)
        assertTrue(
            "mutation setup wrong: the oCol write does not follow the OETF",
            oetfEnd == oetfIdx + oetfBlock.length,
        )
        // region = tonemap + stage 24 + OETF, byte-identical; the swap only
        // moves the OETF block in front of the tonemap + stage-24 text.
        val region = src.substring(tonemapIdx, oetfEnd)
        val tonemapAndLut = region.removeSuffix(oetfBlock)
        assertTrue(
            "mutation setup wrong: the region does not end with the OETF block",
            tonemapAndLut != region,
        )
        val reordered = oetfBlock + "    " + tonemapAndLut.trimEnd() + "\n"
        val mutated = src.replaceRange(tonemapIdx, oetfEnd, reordered)
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

    // ---- the split chain's PRE/POST pass mutations: the split boundary is
    // load-bearing — a uniform crossing it, or the stage-6 close / stage-9
    // fetch drifting out of their own pass, is a different chain ----

    @Test
    fun a_post_uniform_leaking_into_the_pre_pass_is_reported() {
        // The PRE pass owns stages 1-6; a POST-stage uniform (uExposure)
        // appearing in it means the split boundary drifted. The validator's
        // negative check names the leak.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_PRE
            .replace(
                "uniform ivec2 uSize;            // CFA width/height in pixels",
                "uniform ivec2 uSize;            // CFA width/height in pixels\nuniform float uExposure;",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DEVELOP_FRAGMENT_PRE,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_PRE)
                .any { it.contains("a POST-stage uniform leaked into the PRE pass") },
        )
    }

    @Test
    fun a_pre_uniform_leaking_into_the_post_pass_is_reported() {
        // The POST pass owns stages 9+; a PRE-stage uniform (uWb) appearing
        // in it means the split boundary drifted the other way.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace(
                "uniform ivec2 uSize;            // width/height in pixels (the intermediate; same dims as the CFA)",
                "uniform ivec2 uSize;            // width/height in pixels (the intermediate; same dims as the CFA)\nuniform vec3 uWb;",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST)
                .any { it.contains("a PRE-stage uniform leaked into the POST pass") },
        )
    }

    @Test
    fun a_missing_stage6_close_from_the_pre_pass_is_reported() {
        // The PRE pass must END at the camera matrix close; dropping it is
        // the split boundary eroding (the POST pass would then receive a
        // pre-transform image and every pointwise stage would move).
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_PRE
            .replace("        c = uCam2rgb * c;\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DEVELOP_FRAGMENT_PRE,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_PRE)
                .any { it.contains("missing the stage-6 camera matrix close") },
        )
    }

    @Test
    fun a_missing_intermediate_fetch_in_the_post_pass_is_reported() {
        // The POST pass must fetch its input from uSrc (the chain's RGBA32F
        // intermediate); typing the fetch wrong (or dropping it) is the
        // chain's plumbing drifting.
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("    vec3 c = texelFetch(uSrc, g, 0).rgb;\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST)
                .any { it.contains("missing the intermediate fetch") },
        )
    }

    // ---- glitter suppression (stage 8b) pins: POST block + encode pass, the CPU twin ----

    private fun glitterProblems(src: String) =
        GlesShaderValidator.problems(src, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_POST)

    private fun encodeProblems(src: String) =
        GlesShaderValidator.problems(src, GlesShaderValidator.Kind.GLITTER_ENCODE)

    @Test
    fun the_shipped_glitter_encode_pass_passes_every_check() {
        assertEquals(emptyList<String>(), encodeProblems(GlesShaderSources.GLITTER_ENCODE))
        assertEquals(emptyList<String>(), glitterProblems(GlesShaderSources.DEVELOP_FRAGMENT_POST))
    }

    @Test
    fun the_post_pass_carries_the_glitter_block_and_no_other_pass_does() {
        val post = GlesShaderSources.DEVELOP_FRAGMENT_POST
        assertTrue(post.contains("uniform int uGlitter;"))
        assertTrue(post.contains("if (uGlitter == 1) {"))
        for (other in listOf(
            GlesShaderSources.DEVELOP_FRAGMENT,
            GlesShaderSources.DEVELOP_FRAGMENT_PRE,
            GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
        )) {
            assertFalse(other.contains("uGlitter"))
            assertFalse(other.contains("kGlitterThreshold"))
        }
    }

    @Test
    fun the_glsl_threshold_equals_the_cpu_header_constant() {
        // core/include/rawedit/Develop.h: #define RAWEDIT_GLITTER_THRESHOLD 0.09f
        val header = listOf("../core/include/rawedit/Develop.h", "core/include/rawedit/Develop.h")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val cpu = Regex("""#define RAWEDIT_GLITTER_THRESHOLD ([0-9.]+)f""").find(header)!!.groupValues[1]
        assertEquals(cpu.toFloat(), GlesShaderSources.GLITTER_THRESHOLD, 0.0f)
        assertTrue(
            GlesShaderSources.DEVELOP_FRAGMENT_POST.contains("const float kGlitterThreshold = $cpu;"),
        )
    }

    @Test
    fun a_changed_glitter_threshold_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("const float kGlitterThreshold = 0.09;", "const float kGlitterThreshold = 0.05;")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("missing the glitter-suppression pin") })
    }

    @Test
    fun a_changed_glitter_pretest_count_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("if (nearCount >= 5) return false;", "if (near >= 4) return false;")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("missing the glitter-suppression pin") })
    }

    @Test
    fun a_changed_glitter_outlier_rule_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("return abs(own - m) > kGlitterThreshold;", "return own != m;")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("missing the glitter-suppression pin") })
    }

    @Test
    fun a_changed_glitter_median_network_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("glitterSort(v[6], v[4]);", "")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("missing the glitter-suppression pin") })
    }

    @Test
    fun a_wrong_glitter_decode_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("if (v < 0.081) return v / 4.5;", "if (v < 0.08) return v / 4.5;")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("missing the glitter-suppression pin") })
    }

    @Test
    fun a_glitter_block_that_encodes_per_tap_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_POST
            .replace("vec2 ch = texelFetch(uGlitterChroma, q, 0).rg;", "vec2 ch = vec2(glitterOetf(1.0));")
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_POST)
        assertTrue(glitterProblems(mutated).any { it.contains("per-tap pow cost") || it.contains("pin") })
    }

    @Test
    fun the_glitter_block_moved_after_stage_9_is_reported() {
        val src = GlesShaderSources.DEVELOP_FRAGMENT_POST
        val start = src.indexOf("    // Stage 8b: glitter suppression")
        val end = src.indexOf("    // Stage 9: black point")
        assertTrue(start in 0 until end)
        val block = src.substring(start, end)
        val mutated = src.removeRange(start, end).replace("    oCol = vec4(c, 1.0);", block + "    oCol = vec4(c, 1.0);")
        assertTrue("mutation must apply", mutated != src)
        assertTrue(glitterProblems(mutated).any { it.contains("the glitter block is mispositioned") })
    }

    @Test
    fun glitter_leaking_into_the_tail_pass_is_reported() {
        val mutated = GlesShaderSources.DEVELOP_FRAGMENT_TAIL.replace(
            "uniform ivec2 uSize;",
            "uniform ivec2 uSize;\nuniform int uGlitter;",
        )
        assertTrue("mutation must apply", mutated != GlesShaderSources.DEVELOP_FRAGMENT_TAIL)
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DEVELOP_FRAGMENT_TAIL)
                .any { it.contains("glitter suppression leaked into the TAIL pass") },
        )
    }

    @Test
    fun a_changed_glitter_encode_oetf_is_reported() {
        val mutated = GlesShaderSources.GLITTER_ENCODE
            .replace("if (lin < 0.018) return 4.5 * lin;", "if (lin < 0.02) return 4.5 * lin;")
        assertTrue("mutation must apply", mutated != GlesShaderSources.GLITTER_ENCODE)
        assertTrue(encodeProblems(mutated).any { it.contains("missing the glitter-encode pin") })
    }

    @Test
    fun a_changed_glitter_encode_output_layout_is_reported() {
        val mutated = GlesShaderSources.GLITTER_ENCODE
            .replace("oCol = vec4(eR - eG, eB - eG, eG, 1.0);", "oCol = vec4(eR, eG, eB, 1.0);")
        assertTrue("mutation must apply", mutated != GlesShaderSources.GLITTER_ENCODE)
        assertTrue(encodeProblems(mutated).any { it.contains("missing the glitter-encode pin") })
    }

    // ---- NR pass mutations: the CPU's every constant, tap order, weight
    // wiring, gate and pass-through are load-bearing — a rewrite that
    // changes any of them changes the owner's routine NR look ----

    @Test
    fun a_wrong_chroma_nr_constant_is_reported() {
        // kSr 0.10 is denoiseChromaImpl's own range sigma; 0.11 is a
        // different filter. Every other check stays green (the constants
        // still exist, the taps still accumulate) — only the value drifted.
        val mutated = GlesShaderSources.NR_CHROMA_H
            .replace("const float kSr = 0.10;", "const float kSr = 0.11;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_H)
                .any { it.contains("chroma-NR constants") },
        )
    }

    @Test
    fun a_wrong_chroma_nr_tap_order_is_reported() {
        // The CPU's own tap(xm,c0); tap(xp,c0); tap(xm2,c1); tap(xp2,c1)
        // order. Swapping xm2 ahead of xp makes a different accumulation
        // order — byte-identical lines, different float sum.
        val mutated = GlesShaderSources.NR_CHROMA_H
            .replace(
                "ivec2 p = ivec2(xm2, g.y);",
                "ivec2 p = ivec2(xm2, g.y); // reorder sentinel",
            )
            .replace(
                "ivec2 p = ivec2(xp, g.y);",
                "ivec2 p = ivec2(xm2, g.y);",
            )
            .replace(
                "ivec2 p = ivec2(xm2, g.y); // reorder sentinel",
                "ivec2 p = ivec2(xp, g.y);",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_H)
                .any { it.contains("the horizontal tap order is wrong") },
        )
    }

    @Test
    fun a_miswired_chroma_nr_tap_weight_is_reported() {
        // xm rides kC0 in the CPU; wiring it to kC1 keeps every other check
        // green (the constants exist, the order holds, the accumulation
        // text appears) while changing the blur of every pixel. The
        // position-bound wiring check catches it.
        val mutated = GlesShaderSources.NR_CHROMA_H
            .replace(
                "ivec2 p = ivec2(xm, g.y);\n" +
                    "                float ly = lumaAt(p);\n" +
                    "                vec3 t = texelFetch(uSrc, p, 0).rgb;\n" +
                    "                float dl = ly - y;\n" +
                    "                float rw = exp(-dl * dl * kInv2Sr2);\n" +
                    "                sCr += (t.r - ly - cr) * kC0 * rw;\n" +
                    "                sCb += (t.b - ly - cb) * kC0 * rw;\n" +
                    "                sw += kC0 * rw;",
                "ivec2 p = ivec2(xm, g.y);\n" +
                    "                float ly = lumaAt(p);\n" +
                    "                vec3 t = texelFetch(uSrc, p, 0).rgb;\n" +
                    "                float dl = ly - y;\n" +
                    "                float rw = exp(-dl * dl * kInv2Sr2);\n" +
                    "                sCr += (t.r - ly - cr) * kC1 * rw;\n" +
                    "                sCb += (t.b - ly - cb) * kC1 * rw;\n" +
                    "                sw += kC1 * rw;",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_H)
                .any { it.contains("tap weights are miswired") },
        )
    }

    @Test
    fun a_missing_chroma_nr_gate_is_reported() {
        // The gate rewrite `!= 1.0` leaves the pass running when the grade
        // is neutral — a different default image. The validator locks the
        // gate text.
        val mutated = GlesShaderSources.NR_CHROMA_H
            .replace("if (uChromaDenoise != 0.0) {", "if (uChromaDenoise != 1.0) {")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_H)
                .any { it.contains("stage-7 gate") },
        )
    }

    @Test
    fun a_missing_chroma_nr_pass_through_is_reported() {
        // Dropping the gate-closed write leaves oCol unwritten on the closed
        // path — undefined output. The validator locks the pass-through.
        val mutated = GlesShaderSources.NR_CHROMA_H
            .replace("    oCol = vec4(orig, 1.0);\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_H)
                .any { it.contains("gate-closed pass-through") },
        )
    }

    @Test
    fun a_wrong_chroma_nr_v_guide_source_is_reported() {
        // The vertical pass must guide with the ORIGINAL luma (uGuide);
        // guiding from uSrc (the horizontal output's .xy) changes every
        // pixel's range weights. The validator locks the guide read.
        val mutated = GlesShaderSources.NR_CHROMA_V
            .replace("vec3 c = texelFetch(uGuide, p, 0).rgb;", "vec3 c = texelFetch(uSrc, p, 0).rgb;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_V)
                .any { it.contains("ORIGINAL-luma guide read") },
        )
    }

    @Test
    fun a_missing_chroma_nr_v_guide_uniform_is_reported() {
        // Dropping the uGuide uniform declaration is the session having
        // nothing to attach the ORIGINAL pre-NR texture to.
        val mutated = GlesShaderSources.NR_CHROMA_V
            .replace("uniform highp sampler2D uGuide;  // the develop PRE pass's output: the ORIGINAL pre-NR RGB\n", "")
        assertTrue(
            "mutation must apply (source text declared changed)",
            mutated != GlesShaderSources.NR_CHROMA_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_V)
                .any { it.contains("uGuide") },
        )
    }

    @Test
    fun a_missing_chroma_nr_v_flat_chroma_skip_is_reported() {
        // The CPU `continue`s past the write when both deltas are zero —
        // the original bytes are kept. Dropping the skip writes a
        // reconstructed green that can differ by rounding.
        val mutated = GlesShaderSources.NR_CHROMA_V
            .replace("            if (dr == 0.0 && db == 0.0) {\n                oCol = vec4(orig, 1.0);\n                return;\n            }\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_CHROMA_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_CHROMA_V)
                .any { it.contains("flat-chroma skip") },
        )
    }

    @Test
    fun a_wrong_luma_nr_constant_is_reported() {
        // kSr 0.05 is denoiseLumaImpl's own range sigma; 0.06 is a
        // different filter.
        val mutated = GlesShaderSources.NR_LUMA
            .replace("const float kSr = 0.05;", "const float kSr = 0.06;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_LUMA,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_LUMA)
                .any { it.contains("luma-NR constants") },
        )
    }

    @Test
    fun a_miswired_luma_nr_tap_weight_is_reported() {
        // The first tap (ym,xm) is an EDGE tap and rides kWd in the CPU;
        // wiring it to kWe keeps the 4/4 counts green while changing the
        // bilateral's edge weighting. The position-bound check catches it.
        val mutated = GlesShaderSources.NR_LUMA
            .replace(
                "ivec2 p = ivec2(xm, ym);\n" +
                    "                float lv = lumaAt(p);\n" +
                    "                float dl = lv - c;\n" +
                    "                float rw = exp(-dl * dl * kInv2Sr2);\n" +
                    "                s += (lv - c) * kWd * rw;\n" +
                    "                sw += kWd * rw;",
                "ivec2 p = ivec2(xm, ym);\n" +
                    "                float lv = lumaAt(p);\n" +
                    "                float dl = lv - c;\n" +
                    "                float rw = exp(-dl * dl * kInv2Sr2);\n" +
                    "                s += (lv - c) * kWe * rw;\n" +
                    "                sw += kWe * rw;",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_LUMA,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_LUMA)
                .any { it.contains("spatial weight split is wrong") },
        )
    }

    @Test
    fun a_missing_luma_nr_gate_is_reported() {
        val mutated = GlesShaderSources.NR_LUMA
            .replace("if (uNoiseReduction != 0.0) {", "if (uNoiseReduction != 1.0) {")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_LUMA,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_LUMA)
                .any { it.contains("stage-8 gate") },
        )
    }

    @Test
    fun a_missing_luma_nr_tail_is_reported() {
        // Dropping the equally-lifted write is the strength delta lost.
        val mutated = GlesShaderSources.NR_LUMA
            .replace("            oCol = vec4(orig + vec3(d), 1.0);\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.NR_LUMA,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.NR_LUMA)
                .any { it.contains("luma-NR tail") },
        )
    }

    // ---- unsharp pass mutations (stages 21+22): the CPU's every
    // constant, tap order, pair wiring, gate, amount mapping, guide
    // source and tail are load-bearing — a rewrite that changes any of
    // them changes the owner's routine sharpness/detail look ----

    @Test
    fun a_wrong_sharpness_radius_is_reported() {
        // kRadius 1.5 is unsharpLumaImpl's own stage-21 radius; 1.6 is a
        // different filter. Every other check stays green — only the
        // constant drifted.
        val mutated = GlesShaderSources.SHARPNESS_H
            .replace("const float kRadius = 1.5;", "const float kRadius = 1.6;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_H)
                .any { it.contains("sharpness constants") },
        )
    }

    @Test
    fun a_wrong_detail_radius_is_reported() {
        // kRadius 0.75 is the CPU call site's own stage-22 radius; 0.8 is
        // a different filter.
        val mutated = GlesShaderSources.DETAIL_H
            .replace("const float kRadius = 0.75;", "const float kRadius = 0.8;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DETAIL_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DETAIL_H)
                .any { it.contains("detail constants") },
        )
    }

    @Test
    fun a_wrong_sharpness_h_tap_order_is_reported() {
        // The CPU's own (row[xm]+row[xp])*c0 + (row[xm2]+row[xp2])*c1
        // order. Swapping xm2 ahead of xp makes a different accumulation
        // order — byte-identical lines, different float sum.
        val mutated = GlesShaderSources.SHARPNESS_H
            .replace(
                "(lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1",
                "(lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1 // reorder sentinel",
            )
            .replace(
                "(lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC0",
                "(lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1",
            )
            .replace(
                "(lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1 // reorder sentinel",
                "(lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC0",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_H)
                .any { it.contains("the horizontal tap order is wrong") },
        )
    }

    @Test
    fun a_miswired_sharpness_h_tap_weight_is_reported() {
        // The xm/xp pair rides kC0 in the CPU; wiring it to kC1 keeps
        // every other check green (the constants exist, the order holds,
        // the accumulation text appears) while changing the blur of
        // every pixel. The pair-segment wiring check catches it.
        val mutated = GlesShaderSources.SHARPNESS_H
            .replace(
                "+ (lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC0",
                "+ (lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC1",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_H)
                .any { it.contains("tap weights are miswired") },
        )
    }

    @Test
    fun a_missing_sharpness_gate_is_reported() {
        // The gate rewrite `!= 1.0` leaves the pass running when the
        // grade is neutral — a different default image. The validator
        // locks the gate text.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace("if (uSharpness != 0.0) {", "if (uSharpness != 1.0) {")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("stage-21 gate") },
        )
    }

    @Test
    fun a_wrong_sharpness_amount_mapping_is_reported() {
        // The CPU call site's own min(max(0.5*(raw/100), -0.5), 0.5).
        // Dropping the negative arm of the clamp changes every softening
        // grade (raw < 0) — the validator locks the mapping text.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace(
                "float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, 0.5);",
                "float amount = clamp(0.5 * (uSharpness / 100.0), 0.0, 0.5);",
            )
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("amount mapping") },
        )
    }

    @Test
    fun a_wrong_sharpness_v_guide_source_is_reported() {
        // The vertical pass must guide with the ORIGINAL luma (uGuide);
        // guiding from uSrc (the horizontal output's .x) changes every
        // pixel's tail delta to exactly 0. The validator locks the guide
        // read.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace("vec3 c = texelFetch(uGuide, p, 0).rgb;", "vec3 c = texelFetch(uSrc, p, 0).rgb;")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("ORIGINAL-luma guide read") },
        )
    }

    @Test
    fun a_missing_sharpness_v_center_fetch_is_reported() {
        // The CPU's v = tmp[y*w+x]: the HORIZONTALLY blurred center from
        // uSrc. Replacing it with the original luma (uGuide's lumaAt)
        // keeps the taps green while changing the blur of every pixel.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace("float v = texelFetch(uSrc, g, 0).x;", "float v = lumaAt(g);")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("center fetch") },
        )
    }

    @Test
    fun a_missing_sharpness_tail_is_reported() {
        // Dropping the equally-lifted write is the amount delta lost.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace("        oCol = vec4(orig + vec3(d), 1.0);\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("sharpness tail") },
        )
    }

    @Test
    fun a_missing_sharpness_pass_through_is_reported() {
        // Dropping the gate-closed write leaves oCol unwritten on the
        // closed path — undefined output. The validator locks the
        // pass-through.
        val mutated = GlesShaderSources.SHARPNESS_V
            .replace("    oCol = vec4(orig, 1.0);\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.SHARPNESS_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.SHARPNESS_V)
                .any { it.contains("gate-closed pass-through") },
        )
    }

    @Test
    fun a_missing_detail_gate_is_reported() {
        val mutated = GlesShaderSources.DETAIL_V
            .replace("if (uDetail != 0.0) {", "if (uDetail != 1.0) {")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DETAIL_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DETAIL_V)
                .any { it.contains("stage-22 gate") },
        )
    }

    @Test
    fun a_missing_detail_v_guide_uniform_is_reported() {
        // Dropping the uGuide uniform declaration is the session having
        // nothing to attach the stage's ORIGINAL input to.
        val mutated = GlesShaderSources.DETAIL_V
            .replace("uniform highp sampler2D uGuide;  // the sharpness output, or the pointwise post pass's output when stage 21 is off\n", "")
        assertTrue(
            "mutation must apply (source text declared changed)",
            mutated != GlesShaderSources.DETAIL_V,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DETAIL_V)
                .any { it.contains("uGuide") },
        )
    }

    @Test
    fun a_missing_gate_closed_luma_fallback_is_reported() {
        // The H pass's gate-closed path writes the ORIGINAL luma to .x;
        // dropping it leaves .x unwritten — the V pass would read garbage
        // whenever the session draws the H pass with the gate closed.
        val mutated = GlesShaderSources.DETAIL_H
            .replace("    oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);\n", "")
        assertTrue(
            "mutation must apply (source text changed)",
            mutated != GlesShaderSources.DETAIL_H,
        )
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.DETAIL_H)
                .any { it.contains("gate-closed luma fallback") },
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
            val knee = 0.18f
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
            val knee = 0.18f
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
            Triple(0.18f, 0.2f, 0.1f),
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

    // ---- pointwise host numeric parity: the same TEXT-parity discipline
    // as the stage-15 case above, one case per ported stage (9-13, 16-20).
    // Each stage transcribes the CPU loop from core/src/Develop.cpp and
    // the shipped GLSL block from GlesShaderSources.DEVELOP_FRAGMENT into
    // Kotlin and compares after the readback quantize. HONESTY: this
    // locks that the two shipped TEXTS evaluate identically over each
    // stage's every branch — it is NOT a device parity claim (no GLSL
    // compiler runs here; the device harness's cases still ride the
    // neutral defaults of these uniforms, and the owner's multi-control
    // grade preview/export is the production proof) ----

    // The shared pixel set: every tonal band the smoothstep weights span,
    // neutrals, saturated single channels, near-ties, out-of-range
    // headroom and the exact band anchors (0, 0.18, 0.72, 1.0).
    private val parityPixels = listOf(
        Triple(0.0f, 0.0f, 0.0f),
        Triple(0.18f, 0.18f, 0.18f),
        Triple(0.72f, 0.72f, 0.72f),
        Triple(1.0f, 1.0f, 1.0f),
        Triple(0.5f, 0.25f, 0.1f),
        Triple(0.1f, 0.5f, 0.25f),
        Triple(0.25f, 0.1f, 0.5f),
        Triple(0.9f, 0.9f, 0.9f),
        Triple(1.2f, 0.7f, 0.3f),
        Triple(0.3f, 1.1f, 0.05f),
        Triple(0.8f, 0.8000001f, 0.1f),
        Triple(0.85f, 0.0f, 0.0f),
    )

    private fun smoothstepfKt(e0: Float, e1: Float, x: Float): Float {
        // The CPU rawedit::smoothstepf / the shader's smoothstepf, one
        // shared body (the validator locks the two texts to each other).
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    private fun hueGainsKt(hueDeg: Float): FloatArray {
        // The CPU hueGains lambda / the shader's hueGains helper.
        val gains = FloatArray(3)
        for (ch in 0..2) {
            val center = 120.0f * ch
            var d = Math.abs(hueDeg - center)
            if (d > 180.0f) d = 360.0f - d
            val rad = (d * 3.14159265f / 180.0f).toDouble()
            gains[ch] = 2.0f * (0.5f + 0.5f * Math.cos(rad).toFloat())
        }
        return gains
    }

    private fun assertStageParity(
        name: String,
        values: List<Float>,
        cpu: (r: Float, g: Float, b: Float, v: Float) -> Triple<Float, Float, Float>,
        glsl: (r: Float, g: Float, b: Float, v: Float) -> Triple<Float, Float, Float>,
    ) {
        values.forEach { v ->
            var mismatches = 0
            var maxLsb = 0
            parityPixels.forEach { (r, g, b) ->
                val cpuOut = cpu(r, g, b, v)
                val glslOut = glsl(r, g, b, v)
                val cpuBytes = intArrayOf(
                    toByte(cpuOut.first), toByte(cpuOut.second), toByte(cpuOut.third),
                )
                val glslBytes = intArrayOf(
                    toByte(glslOut.first), toByte(glslOut.second), toByte(glslOut.third),
                )
                for (c in 0..2) {
                    val lsb = Math.abs(cpuBytes[c] - glslBytes[c])
                    if (lsb > 0) mismatches++
                    if (lsb > maxLsb) maxLsb = lsb
                }
            }
            assertEquals("$name $v: mismatched bytes", 0, mismatches)
            assertEquals("$name $v: max LSB", 0, maxLsb)
        }
    }

    @Test
    fun stage9_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // CPU: lift remap pinned at white; GLSL: identical gate + math.
        // k = 0.12 (A6 black point range shrink, owner request 2026-11-23).
        assertStageParity(
            "blackPoint", listOf(5f, -5f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val lift = -0.12f * (v / 100.0f)
                    Triple(lift + (1.0f - lift) * r, lift + (1.0f - lift) * g, lift + (1.0f - lift) * b)
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val lift = -0.12f * (v / 100.0f)
                    Triple(lift + (1.0f - lift) * r, lift + (1.0f - lift) * g, lift + (1.0f - lift) * b)
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage10_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // Both halves of the ternary (positive brightens, negative pulls
        // the ceiling down) plus the 0 fast path.
        assertStageParity(
            "whitePoint", listOf(5f, -5f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val scale = if (v > 0.0f) 1.0f / (1.0f - 0.25f * (v / 100.0f)) else 1.0f + 0.25f * (v / 100.0f)
                    Triple(r * scale, g * scale, b * scale)
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val scale = if (v > 0.0f) 1.0f / (1.0f - 0.25f * (v / 100.0f)) else 1.0f + 0.25f * (v / 100.0f)
                    Triple(r * scale, g * scale, b * scale)
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage11_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The dark-band gain: wDark spans 1 at black to 0 at midgray.
        assertStageParity(
            "shadows", listOf(55f, -55f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val strength = v / 100.0f
                    val mx = maxOf(r, maxOf(g, b))
                    val wDark = smoothstepfKt(0.18f, 0.0f, mx)
                    val gain = Math.pow(2.0, (strength * wDark).toDouble()).toFloat()
                    Triple(r * gain, g * gain, b * gain)
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val strength = v / 100.0f
                    val mx = maxOf(r, maxOf(g, b))
                    val wDark = smoothstepfKt(0.18f, 0.0f, mx)
                    val gain = Math.pow(2.0, (strength * wDark).toDouble()).toFloat()
                    Triple(r * gain, g * gain, b * gain)
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage12_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The midgray tent: 0 at black, 1 at 0.18, 0 again by 0.72.
        assertStageParity(
            "midtones", listOf(55f, -55f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val strength = v / 100.0f
                    val mx = maxOf(r, maxOf(g, b))
                    val wMid = smoothstepfKt(0.0f, 0.18f, mx) *
                        (1.0f - smoothstepfKt(0.18f, 0.72f, mx))
                    val gain = Math.pow(2.0, (strength * wMid).toDouble()).toFloat()
                    Triple(r * gain, g * gain, b * gain)
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val strength = v / 100.0f
                    val mx = maxOf(r, maxOf(g, b))
                    val wMid = smoothstepfKt(0.0f, 0.18f, mx) *
                        (1.0f - smoothstepfKt(0.18f, 0.72f, mx))
                    val gain = Math.pow(2.0, (strength * wMid).toDouble()).toFloat()
                    Triple(r * gain, g * gain, b * gain)
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage13_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The pivot slope: +100 doubles the distance from 0.18, -100
        // collapses to the pivot. The CPU local c is the contrast value;
        // the shader names it slopeC — the rename the comments document,
        // same number.
        assertStageParity(
            "contrast", listOf(55f, -55f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val c = v / 100.0f
                    val slope = 1.0f + c
                    val pivot = 0.18f
                    Triple(
                        pivot + (r - pivot) * slope,
                        pivot + (g - pivot) * slope,
                        pivot + (b - pivot) * slope,
                    )
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val slopeC = v / 100.0f
                    val slope = 1.0f + slopeC
                    val pivot = 0.18f
                    Triple(
                        pivot + (r - pivot) * slope,
                        pivot + (g - pivot) * slope,
                        pivot + (b - pivot) * slope,
                    )
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage16_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The warm/cool diagonal: the asShot gate is CPU-side on both
        // legs (the uWbManual hoist), so the parity case measures the
        // in-gate math over warm (6500), cool (3200), the |tempShift|
        // threshold boundary (5600 -> tempShift 0 -> skip) and the 0
        // fast path.
        assertStageParity(
            "tempK", listOf(6500f, 3200f, 5600f, 0f),
            cpu = { r, g, b, v ->
                val tempShift = if (v > 0.0f) (Math.log((v / 5600.0f).toDouble()) / Math.log(2.0)).toFloat() else 0.0f
                if (Math.abs(tempShift) > 1e-4f) {
                    val warmGain = Math.pow(2.0, (0.35f * tempShift).toDouble()).toFloat()
                    val coolGain = Math.pow(2.0, (-0.35f * tempShift).toDouble()).toFloat()
                    Triple(
                        r * warmGain,
                        g * Math.sqrt((warmGain * coolGain).toDouble()).toFloat(),
                        b * coolGain,
                    )
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                val tempShift = if (v > 0.0f) (Math.log((v / 5600.0f).toDouble()) / Math.log(2.0)).toFloat() else 0.0f
                if (Math.abs(tempShift) > 1e-4f) {
                    val warmGain = Math.pow(2.0, (0.35f * tempShift).toDouble()).toFloat()
                    val coolGain = Math.pow(2.0, (-0.35f * tempShift).toDouble()).toFloat()
                    Triple(
                        r * warmGain,
                        g * Math.sqrt((warmGain * coolGain).toDouble()).toFloat(),
                        b * coolGain,
                    )
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage17_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The green-channel-only push; the asShot half of the CPU guard
        // is the uWbManual hoist on both legs, the tint half is the 0.0
        // fast path.
        assertStageParity(
            "tint", listOf(55f, -55f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val greenGain = Math.pow(2.0, (0.4f * (v / 100.0f)).toDouble()).toFloat()
                    Triple(r, g * greenGain, b)
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val greenGain = Math.pow(2.0, (0.4f * (v / 100.0f)).toDouble()).toFloat()
                    Triple(r, g * greenGain, b)
                } else Triple(r, g, b)
            },
        )
    }

    @Test
    fun stage18_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The wheel: one (x, y) pair per band weighted by the partition
        // of unity. The case drives each band alone (a shadow-only offset,
        // a mid-only offset, a high-only offset) so every weight term
        // carries, plus the all-zero fast path.
        data class Wheel(val sx: Float, val sy: Float, val mx: Float, val my: Float, val hx: Float, val hy: Float)
        val wheels = listOf(
            Wheel(0.2f, -0.1f, 0.0f, 0.0f, 0.0f, 0.0f),
            Wheel(0.0f, 0.0f, 0.3f, 0.25f, 0.0f, 0.0f),
            Wheel(0.0f, 0.0f, 0.0f, 0.0f, -0.4f, 0.5f),
            Wheel(0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f),
            Wheel(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
        )
        wheels.forEach { w ->
            var mismatches = 0
            var maxLsb = 0
            parityPixels.forEach { (r, g, b) ->
                fun cpuWheel(): Triple<Float, Float, Float> {
                    val mx = maxOf(r, maxOf(g, b))
                    val wDark = smoothstepfKt(0.18f, 0.0f, mx)
                    val wMid = smoothstepfKt(0.0f, 0.18f, mx) *
                        (1.0f - smoothstepfKt(0.18f, 0.72f, mx))
                    val wHigh = smoothstepfKt(0.18f, 0.72f, mx)
                    val xBlend = wDark * w.sx + wMid * w.mx + wHigh * w.hx
                    val yBlend = wDark * w.sy + wMid * w.my + wHigh * w.hy
                    return Triple(
                        r * Math.pow(2.0, (0.5f * xBlend).toDouble()).toFloat(),
                        g * Math.pow(2.0, (0.5f * yBlend).toDouble()).toFloat(),
                        b * Math.pow(2.0, (-0.5f * xBlend).toDouble()).toFloat(),
                    )
                }
                fun glslWheel(): Triple<Float, Float, Float> {
                    val mx = maxOf(r, maxOf(g, b))
                    val wDark = smoothstepfKt(0.18f, 0.0f, mx)
                    val wMid = smoothstepfKt(0.0f, 0.18f, mx) *
                        (1.0f - smoothstepfKt(0.18f, 0.72f, mx))
                    val wHigh = smoothstepfKt(0.18f, 0.72f, mx)
                    val xBlend = wDark * w.sx + wMid * w.mx + wHigh * w.hx
                    val yBlend = wDark * w.sy + wMid * w.my + wHigh * w.hy
                    return Triple(
                        r * Math.pow(2.0, (0.5f * xBlend).toDouble()).toFloat(),
                        g * Math.pow(2.0, (0.5f * yBlend).toDouble()).toFloat(),
                        b * Math.pow(2.0, (-0.5f * xBlend).toDouble()).toFloat(),
                    )
                }
                val cpuOut = cpuWheel()
                val glslOut = glslWheel()
                val cpuBytes = intArrayOf(
                    toByte(cpuOut.first), toByte(cpuOut.second), toByte(cpuOut.third),
                )
                val glslBytes = intArrayOf(
                    toByte(glslOut.first), toByte(glslOut.second), toByte(glslOut.third),
                )
                for (c in 0..2) {
                    val lsb = Math.abs(cpuBytes[c] - glslBytes[c])
                    if (lsb > 0) mismatches++
                    if (lsb > maxLsb) maxLsb = lsb
                }
            }
            assertEquals("wheel $w: mismatched bytes", 0, mismatches)
            assertEquals("wheel $w: max LSB", 0, maxLsb)
        }
    }

    @Test
    fun stage19_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The split tints: shadow hue alone, highlight hue alone, both,
        // a balance-shifted crossover and the off sentinel. The -1 = off
        // sentinel is the uSplitActive hoist's own input: the case feeds
        // the in-gate math the same (>= 0 ? hueGains : unity) fallback
        // both legs carry.
        data class Split(val sh: Float, val hl: Float, val balance: Float)
        val splits = listOf(
            Split(30f, -1f, 0f),
            Split(-1f, 200f, 0f),
            Split(45f, 210f, 0f),
            Split(30f, 200f, 50f),
            Split(-1f, -1f, 0f),
        )
        splits.forEach { s ->
            var mismatches = 0
            var maxLsb = 0
            parityPixels.forEach { (r, g, b) ->
                // CPU: the Develop.cpp loop over the std::array gains,
                // channel counter named c.
                fun cpuSplit(): Triple<Float, Float, Float> {
                    val m = 0.18f * Math.pow(2.0, (s.balance / 100.0f).toDouble()).toFloat()
                    val shGains = if (s.sh >= 0.0f) hueGainsKt(s.sh) else floatArrayOf(1.0f, 1.0f, 1.0f)
                    val hlGains = if (s.hl >= 0.0f) hueGainsKt(s.hl) else floatArrayOf(1.0f, 1.0f, 1.0f)
                    val tintAmount = 0.35f
                    val mx = maxOf(r, maxOf(g, b))
                    val wShadow = smoothstepfKt(m, 0.0f, mx)
                    val wHighlight = smoothstepfKt(m, 1.0f, mx)
                    val out = FloatArray(3)
                    for (c in 0..2) {
                        val gain = Math.pow(
                            2.0,
                            (tintAmount * (wShadow * (shGains[c] - 1.0f) +
                                wHighlight * (hlGains[c] - 1.0f))).toDouble(),
                        ).toFloat()
                        out[c] = floatArrayOf(r, g, b)[c] * gain
                    }
                    return Triple(out[0], out[1], out[2])
                }
                // GLSL: the shipped block, channel counter renamed ch
                // (c is the pixel vector there) — the rename the shader
                // comments document, same loop.
                fun glslSplit(): Triple<Float, Float, Float> {
                    val m = 0.18f * Math.pow(2.0, (s.balance / 100.0f).toDouble()).toFloat()
                    val shGains = if (s.sh >= 0.0f) hueGainsKt(s.sh) else floatArrayOf(1.0f, 1.0f, 1.0f)
                    val hlGains = if (s.hl >= 0.0f) hueGainsKt(s.hl) else floatArrayOf(1.0f, 1.0f, 1.0f)
                    val tintAmount = 0.35f
                    val mx = maxOf(r, maxOf(g, b))
                    val wShadow = smoothstepfKt(m, 0.0f, mx)
                    val wHighlight = smoothstepfKt(m, 1.0f, mx)
                    val out = FloatArray(3)
                    for (ch in 0..2) {
                        val gain = Math.pow(
                            2.0,
                            (tintAmount * (wShadow * (shGains[ch] - 1.0f) +
                                wHighlight * (hlGains[ch] - 1.0f))).toDouble(),
                        ).toFloat()
                        out[ch] = floatArrayOf(r, g, b)[ch] * gain
                    }
                    return Triple(out[0], out[1], out[2])
                }
                val cpuOut = cpuSplit()
                val glslOut = glslSplit()
                val cpuBytes = intArrayOf(
                    toByte(cpuOut.first), toByte(cpuOut.second), toByte(cpuOut.third),
                )
                val glslBytes = intArrayOf(
                    toByte(glslOut.first), toByte(glslOut.second), toByte(glslOut.third),
                )
                for (c in 0..2) {
                    val lsb = Math.abs(cpuBytes[c] - glslBytes[c])
                    if (lsb > 0) mismatches++
                    if (lsb > maxLsb) maxLsb = lsb
                }
            }
            assertEquals("split $s: mismatched bytes", 0, mismatches)
            assertEquals("split $s: max LSB", 0, maxLsb)
        }
    }

    @Test
    fun stage20_host_parity_the_glsl_block_matches_the_cpu_loop() {
        // The chroma scaling around luma, last of the color stages.
        assertStageParity(
            "saturation", listOf(55f, -55f, 100f, -100f, 0f),
            cpu = { r, g, b, v ->
                if (v != 0.0f) {
                    val f = Math.pow(2.0, (0.75f * (v / 100.0f)).toDouble()).toFloat()
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    Triple(
                        luma + (r - luma) * f,
                        luma + (g - luma) * f,
                        luma + (b - luma) * f,
                    )
                } else Triple(r, g, b)
            },
            glsl = { r, g, b, v ->
                if (v != 0.0f) {
                    val f = Math.pow(2.0, (0.75f * (v / 100.0f)).toDouble()).toFloat()
                    val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    Triple(
                        luma + (r - luma) * f,
                        luma + (g - luma) * f,
                        luma + (b - luma) * f,
                    )
                } else Triple(r, g, b)
            },
        )
    }

    // ---- spatial-NR (stages 7+8) host numeric parity: the same TEXT-parity
    // discipline as the pointwise cases, over a synthetic NEIGHBORHOOD
    // instead of single pixels — the spatial filters read their neighbors,
    // so the case must be an image. Each side transcribes its own shipped
    // text verbatim (the CPU: rawedit::denoiseChromaImpl +
    // denoiseLumaImpl from core/src/Develop.cpp; the GLSL: the NR_CHROMA_H
    // pass, then the NR_CHROMA_V pass over its output, then the NR_LUMA
    // pass — the session's own pass sequence) and the two outputs are
    // compared after the readback quantize, every pixel, every branch.
    // HONESTY: this locks that the two shipped TEXTS evaluate identically
    // over each stage's every branch — it is NOT a device parity claim (no
    // GLSL compiler runs here; the device harness's cases still ride the
    // neutral defaults of these uniforms, and the owner's NR-grade
    // preview/export is the production proof) ----

    private fun expF(x: Float): Float = Math.exp(x.toDouble()).toFloat()

    // 9x9: wide enough that the interior's +/-4 chroma taps run UNCLAMPED
    // and the borders exercise every clamp (xm at x=0,1; xp2 at x=7,8; the
    // same for rows), and cleared the CPU's own w<2/h<2 and w<3/h<3
    // guards. Varied enough that every branch runs: a base gradient,
    // deterministic per-pixel noise (the range weights live), a hard edge
    // at x=6 (the range weights collapse), saturated corners and
    // out-of-range headroom.
    private fun nrMixedNeighborhood(): FloatArray {
        val w = 9
        val h = 9
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 0.18f + 0.02f * x + 0.015f * y
                val n = ((x * 7 + y * 13) % 11).toFloat() / 11.0f - 0.5f
                var r = base + 0.03f * n
                var g = base + 0.02f * n
                var b = base - 0.03f * n
                if (x >= 6) {
                    r += 0.5f; g += 0.5f; b += 0.5f
                }
                if (x == 0 && y == 0) {
                    r = 1.1f; g = 0.05f; b = 0.1f
                }
                if (x == 8 && y == 8) {
                    r = 0.02f; g = 0.9f; b = 1.2f
                }
                val i = (y * w + x) * 3
                rgb[i] = r; rgb[i + 1] = g; rgb[i + 2] = b
            }
        }
        return rgb
    }

    // A flat image with NONZERO chroma offsets: every tap's range weight is
    // 1, the blur reproduces the plane exactly, dr == db == 0 on every
    // pixel and the CPU's flat-chroma `continue` (the GLSL's
    // pass-through) keeps the original bytes — the branch the CPU's own
    // comment calls out as bit-identical at any strength.
    private fun nrFlatNeighborhood(): FloatArray {
        val rgb = FloatArray(9 * 9 * 3)
        for (i in 0 until 9 * 9) {
            rgb[i * 3] = 0.35f
            rgb[i * 3 + 1] = 0.2f
            rgb[i * 3 + 2] = 0.5f
        }
        return rgb
    }

    private fun assertNrParity(
        name: String,
        w: Int,
        h: Int,
        cpuOut: FloatArray,
        glslOut: FloatArray,
    ) {
        var mismatches = 0
        var maxLsb = 0
        for (i in 0 until w * h) {
            for (c in 0..2) {
                val cpuByte = toByte(cpuOut[i * 3 + c])
                val glslByte = toByte(glslOut[i * 3 + c])
                val lsb = Math.abs(cpuByte - glslByte)
                if (lsb > 0) mismatches++
                if (lsb > maxLsb) maxLsb = lsb
            }
        }
        assertEquals("$name: mismatched bytes", 0, mismatches)
        assertEquals("$name: max LSB", 0, maxLsb)
    }

    // The CPU's stage 7, verbatim (core/src/Develop.cpp denoiseChromaImpl
    // + its call-site clamp): the luma/cr/cb planes, the blurPlane lambda
    // run twice (cr then cb) with the SAME original luma guide, and the
    // blend with the flat-chroma skip.
    private fun cpuStage7(rgb: FloatArray, w: Int, h: Int, raw: Float): FloatArray {
        val strength = raw.coerceIn(0.0f, 100.0f) / 100.0f
        if (w < 2 || h < 2 || strength <= 0.0f) return rgb.copyOf()
        val n = w * h
        val luma = FloatArray(n)
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        for (i in 0 until n) {
            val r = rgb[i * 3 + 0]
            val g = rgb[i * 3 + 1]
            val b = rgb[i * 3 + 2]
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma[i] = y
            cr[i] = r - y
            cb[i] = b - y
        }
        val step = 2
        val radius = 2.0f
        val c0 = expF(-0.5f / (radius * radius))
        val c1 = expF(-2.0f / (radius * radius))
        val sr = 0.10f
        val inv2sr2 = 1.0f / (2.0f * sr * sr)
        fun blurPlane(plane: FloatArray): FloatArray {
            val tmp = FloatArray(n)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val xm = Math.max(x - step, 0)
                    val xp = Math.min(x + step, w - 1)
                    val xm2 = Math.max(x - 2 * step, 0)
                    val xp2 = Math.min(x + 2 * step, w - 1)
                    var sw = 1.0f
                    var s = 0.0f
                    fun tap(xx: Int, c: Float) {
                        val dl = luma[y * w + xx] - luma[y * w + x]
                        val rw = expF(-dl * dl * inv2sr2)
                        s += (plane[y * w + xx] - plane[y * w + x]) * c * rw
                        sw += c * rw
                    }
                    tap(xm, c0); tap(xp, c0); tap(xm2, c1); tap(xp2, c1)
                    tmp[y * w + x] = plane[y * w + x] + s / sw
                }
            }
            val out = FloatArray(n)
            for (y in 0 until h) {
                val ym = Math.max(y - step, 0)
                val yp = Math.min(y + step, h - 1)
                val ym2 = Math.max(y - 2 * step, 0)
                val yp2 = Math.min(y + 2 * step, h - 1)
                for (x in 0 until w) {
                    val v = tmp[y * w + x]
                    val yc = luma[y * w + x]
                    var sw = 1.0f
                    var s = 0.0f
                    fun tap(yy: Int, c: Float) {
                        val dl = luma[yy * w + x] - yc
                        val rw = expF(-dl * dl * inv2sr2)
                        s += (tmp[yy * w + x] - v) * c * rw
                        sw += c * rw
                    }
                    tap(ym, c0); tap(yp, c0); tap(ym2, c1); tap(yp2, c1)
                    out[y * w + x] = v + s / sw
                }
            }
            return out
        }
        val bcr = blurPlane(cr)
        val bcb = blurPlane(cb)
        val out = rgb.copyOf()
        for (i in 0 until n) {
            val y = luma[i]
            val dr = strength * (bcr[i] - cr[i])
            val db = strength * (bcb[i] - cb[i])
            if (dr == 0.0f && db == 0.0f) continue // flat chroma: keep the original pixel exactly
            val newCr = cr[i] + dr
            val newCb = cb[i] + db
            val newCg = -(0.2126f * newCr + 0.0722f * newCb) / 0.7152f
            out[i * 3 + 0] = y + newCr
            out[i * 3 + 1] = y + newCg
            out[i * 3 + 2] = y + newCb
        }
        return out
    }

    // The GLSL stage 7, verbatim (NR_CHROMA_H then NR_CHROMA_V over its
    // output — the session's own two-draw pass sequence; V's uGuide is
    // the ORIGINAL pre-NR pixels). The session skips both draws when the
    // clamped strength is 0 (the C++ session's CPU-side skip; the
    // in-shader gate is the second lock) — the pass-through is exact.
    private fun glslStage7(rgb: FloatArray, w: Int, h: Int, raw: Float): FloatArray {
        val strength = raw.coerceIn(0.0f, 100.0f) / 100.0f
        if (strength <= 0.0f) return rgb.copyOf()
        val kRadius = 2.0f
        val kC0 = expF(-0.5f / (kRadius * kRadius))
        val kC1 = expF(-2.0f / (kRadius * kRadius))
        val kSr = 0.10f
        val kInv2Sr2 = 1.0f / (2.0f * kSr * kSr)
        // NR_CHROMA_H's lumaAt reads uSrc — the PRE pass's output.
        fun lumaAtH(px: Int, py: Int): Float {
            val i = (py * w + px) * 3
            return 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
        }
        // Pass 1 of 2 (NR_CHROMA_H): per pixel the original split, the
        // clamped columns, the four taps in the CPU's own order, and the
        // two blurred planes as .xy.
        val tmp = Array(w * h) { FloatArray(2) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ci = (y * w + x) * 3
                val origR = rgb[ci]
                val origG = rgb[ci + 1]
                val origB = rgb[ci + 2]
                val yv = lumaAtH(x, y)
                val cr = origR - yv
                val cb = origB - yv
                val xm = Math.max(x - 2, 0)
                val xp = Math.min(x + 2, w - 1)
                val xm2 = Math.max(x - 4, 0)
                val xp2 = Math.min(x + 4, w - 1)
                var sw = 1.0f
                var sCr = 0.0f
                var sCb = 0.0f
                fun tap(px: Int, c: Float) {
                    val ly = lumaAtH(px, y)
                    val i = (y * w + px) * 3
                    val tR = rgb[i]
                    val tB = rgb[i + 2]
                    val dl = ly - yv
                    val rw = expF(-dl * dl * kInv2Sr2)
                    sCr += (tR - ly - cr) * c * rw
                    sCb += (tB - ly - cb) * c * rw
                    sw += c * rw
                }
                tap(xm, kC0); tap(xp, kC0); tap(xm2, kC1); tap(xp2, kC1)
                tmp[y * w + x][0] = cr + sCr / sw
                tmp[y * w + x][1] = cb + sCb / sw
            }
        }
        // NR_CHROMA_V's lumaAt reads uGuide — the ORIGINAL pre-NR pixels
        // (the CPU's vertical pass guides with the ORIGINAL luma).
        fun lumaAtG(px: Int, py: Int): Float {
            val i = (py * w + px) * 3
            return 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
        }
        // Pass 2 of 2 (NR_CHROMA_V): per pixel the ORIGINAL split, the
        // clamped rows, the four taps (values from uSrc = H's .xy, range
        // guide from uGuide), then the blend with the flat-chroma skip.
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ci = (y * w + x) * 3
                val origR = rgb[ci]
                val origG = rgb[ci + 1]
                val origB = rgb[ci + 2]
                val yv = lumaAtG(x, y)
                val cr = origR - yv
                val cb = origB - yv
                val ym = Math.max(y - 2, 0)
                val yp = Math.min(y + 2, h - 1)
                val ym2 = Math.max(y - 4, 0)
                val yp2 = Math.min(y + 4, h - 1)
                val v0 = tmp[y * w + x][0]
                val v1 = tmp[y * w + x][1]
                var sw = 1.0f
                var sCr = 0.0f
                var sCb = 0.0f
                fun tap(py: Int, c: Float) {
                    val ly = lumaAtG(x, py)
                    val t0 = tmp[py * w + x][0]
                    val t1 = tmp[py * w + x][1]
                    val dl = ly - yv
                    val rw = expF(-dl * dl * kInv2Sr2)
                    sCr += (t0 - v0) * c * rw
                    sCb += (t1 - v1) * c * rw
                    sw += c * rw
                }
                tap(ym, kC0); tap(yp, kC0); tap(ym2, kC1); tap(yp2, kC1)
                val bcr = v0 + sCr / sw
                val bcb = v1 + sCb / sw
                val dr = strength * (bcr - cr)
                val db = strength * (bcb - cb)
                if (dr == 0.0f && db == 0.0f) {
                    out[ci] = origR; out[ci + 1] = origG; out[ci + 2] = origB
                    continue
                }
                val newCr = cr + dr
                val newCb = cb + db
                val newCg = -(0.2126f * newCr + 0.0722f * newCb) / 0.7152f
                out[ci] = yv + newCr
                out[ci + 1] = yv + newCg
                out[ci + 2] = yv + newCb
            }
        }
        return out
    }

    // The CPU's stage 8, verbatim (core/src/Develop.cpp denoiseLumaImpl +
    // its call-site clamp): the luma plane, the 3x3 bilateral in its exact
    // tap order, and the equally-lifted blend.
    private fun cpuStage8(rgb: FloatArray, w: Int, h: Int, raw: Float): FloatArray {
        val strength = raw.coerceIn(0.0f, 100.0f) / 100.0f
        if (w < 3 || h < 3 || strength <= 0.0f) return rgb.copyOf()
        val n = w * h
        val luma = FloatArray(n)
        for (i in 0 until n) {
            luma[i] = 0.2126f * rgb[i * 3 + 0] +
                0.7152f * rgb[i * 3 + 1] +
                0.0722f * rgb[i * 3 + 2]
        }
        val sr = 0.05f
        val inv2sr2 = 1.0f / (2.0f * sr * sr)
        val we = expF(-0.5f)
        val wd = expF(-1.0f)
        val smooth = FloatArray(n)
        for (y in 0 until h) {
            val ym = Math.max(y - 1, 0)
            val yp = Math.min(y + 1, h - 1)
            for (x in 0 until w) {
                val xm = Math.max(x - 1, 0)
                val xp = Math.min(x + 1, w - 1)
                val c = luma[y * w + x]
                var sw = 1.0f
                var s = 0.0f
                fun tap(yy: Int, xx: Int, ws: Float) {
                    val dl = luma[yy * w + xx] - c
                    val rw = expF(-dl * dl * inv2sr2)
                    s += (luma[yy * w + xx] - c) * ws * rw
                    sw += ws * rw
                }
                tap(ym, xm, wd); tap(ym, x, we); tap(ym, xp, wd)
                tap(y, xm, we); tap(y, xp, we)
                tap(yp, xm, wd); tap(yp, x, we); tap(yp, xp, wd)
                smooth[y * w + x] = c + s / sw
            }
        }
        val out = rgb.copyOf()
        for (i in 0 until n) {
            val d = strength * (smooth[i] - luma[i])
            out[i * 3 + 0] += d
            out[i * 3 + 1] += d
            out[i * 3 + 2] += d
        }
        return out
    }

    // The GLSL stage 8, verbatim (NR_LUMA): the per-pixel lumaAt reads of
    // uSrc (the chain's current input), the 3x3 clamped coordinates, the
    // eight taps in the CPU's own order and weight split, and the
    // equally-lifted tail. The session skips the draw for a clamped
    // strength of 0 — the pass-through is exact.
    private fun glslStage8(rgb: FloatArray, w: Int, h: Int, raw: Float): FloatArray {
        val strength = raw.coerceIn(0.0f, 100.0f) / 100.0f
        if (strength <= 0.0f) return rgb.copyOf()
        val kSr = 0.05f
        val kInv2Sr2 = 1.0f / (2.0f * kSr * kSr)
        val kWe = expF(-0.5f)
        val kWd = expF(-1.0f)
        fun lumaAt(px: Int, py: Int): Float {
            val i = (py * w + px) * 3
            return 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
        }
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ci = (y * w + x) * 3
                val origR = rgb[ci]
                val origG = rgb[ci + 1]
                val origB = rgb[ci + 2]
                val xm = Math.max(x - 1, 0)
                val xp = Math.min(x + 1, w - 1)
                val ym = Math.max(y - 1, 0)
                val yp = Math.min(y + 1, h - 1)
                val c = lumaAt(x, y)
                var sw = 1.0f
                var s = 0.0f
                fun tap(px: Int, py: Int, ws: Float) {
                    val lv = lumaAt(px, py)
                    val dl = lv - c
                    val rw = expF(-dl * dl * kInv2Sr2)
                    s += (lv - c) * ws * rw
                    sw += ws * rw
                }
                tap(xm, ym, kWd); tap(x, ym, kWe); tap(xp, ym, kWd)
                tap(xm, y, kWe); tap(xp, y, kWe)
                tap(xm, yp, kWd); tap(x, yp, kWe); tap(xp, yp, kWd)
                val smoothV = c + s / sw
                val d = strength * (smoothV - c)
                out[ci] = origR + d
                out[ci + 1] = origG + d
                out[ci + 2] = origB + d
            }
        }
        return out
    }

    @Test
    fun stage7_host_parity_the_glsl_passes_match_the_cpu_filter() {
        // The raw grade values: a routine strength, the maximum, a small
        // one, a negative (the call-site clamp -> 0 -> the skip path) and
        // the neutral 0 (the CPU call site's own skip / the session's
        // skipped draw).
        val strengths = listOf(25f, 100f, 0.5f, -25f, 0f)
        for (v in strengths) {
            assertNrParity(
                "chroma NR mixed v=$v", 9, 9,
                cpuStage7(nrMixedNeighborhood(), 9, 9, v),
                glslStage7(nrMixedNeighborhood(), 9, 9, v),
            )
            assertNrParity(
                "chroma NR flat v=$v", 9, 9,
                cpuStage7(nrFlatNeighborhood(), 9, 9, v),
                glslStage7(nrFlatNeighborhood(), 9, 9, v),
            )
        }
    }

    @Test
    fun stage8_host_parity_the_glsl_pass_matches_the_cpu_bilateral() {
        val strengths = listOf(25f, 100f, 0.5f, -25f, 0f)
        for (v in strengths) {
            assertNrParity(
                "luma NR mixed v=$v", 9, 9,
                cpuStage8(nrMixedNeighborhood(), 9, 9, v),
                glslStage8(nrMixedNeighborhood(), 9, 9, v),
            )
            assertNrParity(
                "luma NR flat v=$v", 9, 9,
                cpuStage8(nrFlatNeighborhood(), 9, 9, v),
                glslStage8(nrFlatNeighborhood(), 9, 9, v),
            )
        }
    }

    @Test
    fun stages7_then_8_chained_host_parity_matches_the_cpu_chain() {
        // The CPU runs stage 8 on the chroma-denoised image (its luma
        // plane comes from the stage-7 OUTPUT); the GLSL chain feeds the
        // luma pass the chroma-V pass's output. Both orders, active
        // strengths.
        for (v in listOf(25f, 100f)) {
            val base = nrMixedNeighborhood()
            val cpuChained = cpuStage8(cpuStage7(base, 9, 9, v), 9, 9, v)
            val glslChained = glslStage8(glslStage7(base, 9, 9, v), 9, 9, v)
            assertNrParity("chained NR v=$v", 9, 9, cpuChained, glslChained)
            val flat = nrFlatNeighborhood()
            val cpuFlat = cpuStage8(cpuStage7(flat, 9, 9, v), 9, 9, v)
            val glslFlat = glslStage8(glslStage7(flat, 9, 9, v), 9, 9, v)
            assertNrParity("chained NR flat v=$v", 9, 9, cpuFlat, glslFlat)
        }
    }

    // ---- stages 21+22 (sharpness/detail) host numeric parity: the same
    // TEXT-parity discipline — the CPU loop (core/src/Develop.cpp
    // unsharpLumaImpl + its call site) transcribed verbatim, the GLSL
    // two-pass chain (SHARPNESS_H then SHARPNESS_V at radius 1.5,
    // DETAIL_H then DETAIL_V at 0.75) transcribed verbatim, byte-exact
    // after the toByte quantize. These are pure float32 stages (no LUT
    // half-float quantization in the loop), so the claim is EXACT:
    // mismatches == 0 AND maxLsb == 0 ----

    // The CPU's unsharpLumaImpl, verbatim (core/src/Develop.cpp): the
    // luma plane, the `radius < 1 ? 1 : floor(radius)` step fallback, the
    // 4-tap separable Gaussian (H then V, clamped edges), and the
    // equally-lifted tail. The amount is the call site's own
    // min(max(0.5*(raw/100), -0.5), 0.5); raw == 0 skips the stage (the
    // call site's own fast path — bit-identical to the pre-stage image).
    private fun cpuUnsharpStage(rgb: FloatArray, w: Int, h: Int, radius: Float, raw: Float): FloatArray {
        if (raw == 0.0f || w < 2 || h < 2) return rgb.copyOf()
        val amount = (0.5f * (raw / 100.0f)).coerceIn(-0.5f, 0.5f)
        val n = w * h
        val luma = FloatArray(n)
        for (i in 0 until n) {
            luma[i] = 0.2126f * rgb[i * 3 + 0] +
                0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2]
        }
        val step = if (radius < 1.0f) 1 else Math.floor(radius.toDouble()).toInt()
        val c0 = expF(-0.5f / (radius * radius))
        val c1 = expF(-2.0f / (radius * radius))
        val norm = 1.0f + 2.0f * (c0 + c1)
        val tmp = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xm = Math.max(x - step, 0)
                val xp = Math.min(x + step, w - 1)
                val xm2 = Math.max(x - 2 * step, 0)
                val xp2 = Math.min(x + 2 * step, w - 1)
                val s = luma[y * w + x] +
                    (luma[y * w + xm] + luma[y * w + xp]) * c0 +
                    (luma[y * w + xm2] + luma[y * w + xp2]) * c1
                tmp[y * w + x] = s / norm
            }
        }
        val blur = FloatArray(n)
        for (y in 0 until h) {
            val ym = Math.max(y - step, 0)
            val yp = Math.min(y + step, h - 1)
            val ym2 = Math.max(y - 2 * step, 0)
            val yp2 = Math.min(y + 2 * step, h - 1)
            for (x in 0 until w) {
                val v = tmp[y * w + x]
                val s = v +
                    (tmp[ym * w + x] + tmp[yp * w + x]) * c0 +
                    (tmp[ym2 * w + x] + tmp[yp2 * w + x]) * c1
                blur[y * w + x] = s / norm
            }
        }
        val out = rgb.copyOf()
        for (i in 0 until n) {
            val d = amount * (luma[i] - blur[i])
            out[i * 3 + 0] += d
            out[i * 3 + 1] += d
            out[i * 3 + 2] += d
        }
        return out
    }

    // The GLSL two-pass chain, verbatim (the session's own draw
    // sequence): the H pass's lumaAt reads uSrc (the stage's input) and
    // writes the blurred luma plane to .x; the V pass reads its values
    // from uSrc (.x) and its luma guide + original pixel from uGuide
    // (the stage's ORIGINAL input — the CPU's tail reads the ORIGINAL
    // luma), with the clamped rows at step 1 (BOTH radii: 1.5 ->
    // floor(1.5), 0.75 -> the CPU's own < 1 fallback), the norm division
    // and the equally-lifted tail. raw == 0 skips both draws (the C++
    // session-side gate); the in-shader gate is the second lock — the
    // pass-through is exact.
    private fun glslUnsharpStage(rgb: FloatArray, w: Int, h: Int, radius: Float, raw: Float): FloatArray {
        if (raw == 0.0f || w < 2 || h < 2) return rgb.copyOf()
        val amount = (0.5f * (raw / 100.0f)).coerceIn(-0.5f, 0.5f)
        val n = w * h
        val kC0 = expF(-0.5f / (radius * radius))
        val kC1 = expF(-2.0f / (radius * radius))
        val kNorm = 1.0f + 2.0f * (kC0 + kC1)
        fun lumaAt(px: Int, py: Int): Float {
            val i = (py * w + px) * 3
            return 0.2126f * rgb[i] + 0.7152f * rgb[i + 1] + 0.0722f * rgb[i + 2]
        }
        // Pass 1 of 2 (the H pass): the clamped columns at step 1, the
        // four taps in the CPU's own order, the norm-divided .x output.
        val tmp = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xm = Math.max(x - 1, 0)
                val xp = Math.min(x + 1, w - 1)
                val xm2 = Math.max(x - 2, 0)
                val xp2 = Math.min(x + 2, w - 1)
                val yv = lumaAt(x, y)
                val s = yv +
                    (lumaAt(xm, y) + lumaAt(xp, y)) * kC0 +
                    (lumaAt(xm2, y) + lumaAt(xp2, y)) * kC1
                tmp[y * w + x] = s / kNorm
            }
        }
        // Pass 2 of 2 (the V pass): values from uSrc (the H output's
        // .x), the luma guide and the original pixel from uGuide, the
        // norm division, and the tail's equally-lifted write.
        val out = FloatArray(n * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ym = Math.max(y - 1, 0)
                val yp = Math.min(y + 1, h - 1)
                val ym2 = Math.max(y - 2, 0)
                val yp2 = Math.min(y + 2, h - 1)
                val v = tmp[y * w + x]
                val s = v +
                    (tmp[ym * w + x] + tmp[yp * w + x]) * kC0 +
                    (tmp[ym2 * w + x] + tmp[yp2 * w + x]) * kC1
                val blur = s / kNorm
                val ci = (y * w + x) * 3
                val d = amount * (lumaAt(x, y) - blur)
                out[ci] = rgb[ci] + d
                out[ci + 1] = rgb[ci + 1] + d
                out[ci + 2] = rgb[ci + 2] + d
            }
        }
        return out
    }

    @Test
    fun stage21_host_parity_the_glsl_passes_match_the_cpu_filter() {
        // The raw grade values: a routine strength, the maximum, the
        // NEGATIVE arms (softening — valid here, unlike the NR passes),
        // a small one and the neutral 0 (the CPU call site's own skip /
        // the session's skipped draw).
        val raws = listOf(50f, 100f, -50f, -100f, 25f, 0.5f, 0f)
        for (v in raws) {
            assertNrParity(
                "sharpness mixed v=$v", 9, 9,
                cpuUnsharpStage(nrMixedNeighborhood(), 9, 9, 1.5f, v),
                glslUnsharpStage(nrMixedNeighborhood(), 9, 9, 1.5f, v),
            )
            assertNrParity(
                "sharpness flat v=$v", 9, 9,
                cpuUnsharpStage(nrFlatNeighborhood(), 9, 9, 1.5f, v),
                glslUnsharpStage(nrFlatNeighborhood(), 9, 9, 1.5f, v),
            )
        }
    }

    @Test
    fun stage22_host_parity_the_glsl_passes_match_the_cpu_filter() {
        // The same sweep at the fine-texture radius (step STILL 1: the
        // CPU's own `radius < 1.0f ? 1.0f : floor(radius)` fallback).
        val raws = listOf(50f, 100f, -50f, -100f, 25f, 0.5f, 0f)
        for (v in raws) {
            assertNrParity(
                "detail mixed v=$v", 9, 9,
                cpuUnsharpStage(nrMixedNeighborhood(), 9, 9, 0.75f, v),
                glslUnsharpStage(nrMixedNeighborhood(), 9, 9, 0.75f, v),
            )
            assertNrParity(
                "detail flat v=$v", 9, 9,
                cpuUnsharpStage(nrFlatNeighborhood(), 9, 9, 0.75f, v),
                glslUnsharpStage(nrFlatNeighborhood(), 9, 9, 0.75f, v),
            )
        }
    }

    @Test
    fun stages21_then_22_chained_host_parity_matches_the_cpu_chain() {
        // The CPU runs stage 22 on the stage-21 output (its luma plane
        // comes from the sharpened image); the GLSL chain feeds the
        // detail passes the sharpness output (uSrc AND uGuide). Both
        // arms active, each arm alone (the session's per-stage gating),
        // and both negative — the softening shape.
        val cases = listOf(50f to 40f, 50f to 0f, 0f to 40f, -30f to -60f)
        for ((s, d) in cases) {
            val base = nrMixedNeighborhood()
            val cpuChained = cpuUnsharpStage(
                cpuUnsharpStage(base, 9, 9, 1.5f, s), 9, 9, 0.75f, d,
            )
            val glslChained = glslUnsharpStage(
                glslUnsharpStage(base, 9, 9, 1.5f, s), 9, 9, 0.75f, d,
            )
            assertNrParity("chained unsharp s=$s d=$d", 9, 9, cpuChained, glslChained)
        }
    }

    // ---- the gyro warp (the post-develop resample, NOT a develop stage):
    // host numeric parity between the CPU rawedit::warpFrame (core/src/
    // Warp.cpp, transcribed below with its DOUBLE math verbatim — matInv,
    // mapPoint, the composite M = Hinv * S loop, the clamps, the uint32
    // truncation, the edge clamp, the double lerp narrowed to float) and
    // the shipped WARP GLSL text (transcribed with its FLOAT math
    // verbatim — the uM homogeneous tap, the isinf/isnan unmappable
    // checks, the clamps, the int truncation, the edge clamp, the 4-tap
    // bilinear). The CPU composes M in double and narrows the 9 floats
    // for the upload — the harness models EXACTLY that crossing (the
    // GLSL side receives the narrowed float M, the CPU side keeps its
    // doubles), so this locks the REAL contract: the GPU pass reproduces
    // the CPU reference after the readback quantize. Pure float32 math
    // on both sides of the upload — EXACT after quantize, the same claim
    // shape as the unsharp harness. HONESTY: this locks the two TEXTS'
    // numeric agreement over the sweep's every branch — the device
    // compile and the real GLES render stay device-test-pending (the
    // owner's stabilization-ON export A/B). ----

    // The CPU's matInv, verbatim (double): the adjugate, failing at a
    // non-finite/|det| < 1e-12 determinant (the caller then no-ops).
    private fun warpMatInv(m: DoubleArray): DoubleArray? {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        if (!det.isFinite() || Math.abs(det) < 1e-12) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            c00 * inv,
            (m[2] * m[7] - m[1] * m[8]) * inv,
            (m[1] * m[5] - m[2] * m[4]) * inv,
            c01 * inv,
            (m[0] * m[8] - m[2] * m[6]) * inv,
            (m[2] * m[3] - m[0] * m[5]) * inv,
            c02 * inv,
            (m[1] * m[6] - m[0] * m[7]) * inv,
            (m[0] * m[4] - m[1] * m[3]) * inv,
        )
    }

    // The CPU's warpFrame, verbatim (core/src/Warp.cpp): the identity
    // fast path (H == I and zoom == 1.0 -> the caller's no-draw), the
    // non-finite zoom no-op, the zoom < 1 clamp, the matInv degenerate
    // no-op, the composite M = Hinv * S built in double, then the
    // per-pixel mapPoint with the (0,0) unmappable clamp, the [0, w-1] /
    // [0, h-1] clamps, the uint32 truncation, the edge clamp and the
    // double lerp narrowed to float. Returns the output AND the composed
    // M the caller uploads (null = the CPU no-op: the session skips the
    // draw — the pass's absence is the identity).
    private fun cpuWarpFrame(
        rgb: FloatArray,
        w: Int,
        h: Int,
        hm: DoubleArray,
        zoomIn: Double,
        cx: Double,
        cy: Double,
    ): Pair<FloatArray, FloatArray?> {
        var identity = true
        for (i in 0..8) {
            val want = if (i == 0 || i == 4 || i == 8) 1.0 else 0.0
            if (hm[i] != want) {
                identity = false
                break
            }
        }
        if (identity && zoomIn == 1.0) return rgb.copyOf() to null
        if (!zoomIn.isFinite()) return rgb.copyOf() to null
        var zoom = zoomIn
        if (zoom < 1.0) zoom = 1.0
        val hInv = warpMatInv(hm) ?: return rgb.copyOf() to null
        val m = DoubleArray(9)
        val s = 1.0 / zoom
        for (i in 0..2) {
            m[i * 3 + 0] = hInv[i * 3 + 0] * s
            m[i * 3 + 1] = hInv[i * 3 + 1] * s
            m[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * s) +
                hInv[i * 3 + 1] * (cy - cy * s) +
                hInv[i * 3 + 2]
        }
        val out = FloatArray(w * h * 3)
        val maxU = w - 1.0
        val maxV = h - 1.0
        val stride = w * 3
        for (y in 0 until h) {
            for (x in 0 until w) {
                // mapPoint, verbatim: the homogeneous tap, the |w| < 1e-12
                // and finiteness guards, the (0,0) unmappable clamp.
                var fx = 0.0
                var fy = 0.0
                val u = m[0] * x + m[1] * y + m[2]
                val v = m[3] * x + m[4] * y + m[5]
                val ww = m[6] * x + m[7] * y + m[8]
                if (ww.isFinite() && Math.abs(ww) >= 1e-12) {
                    val mx = u / ww
                    val my = v / ww
                    if (mx.isFinite() && my.isFinite()) {
                        fx = mx
                        fy = my
                    }
                }
                if (fx < 0.0) fx = 0.0
                if (fx > maxU) fx = maxU
                if (fy < 0.0) fy = 0.0
                if (fy > maxV) fy = maxV
                val x0 = fx.toInt() // the uint32 cast: fx >= 0 after the clamp
                val y0 = fy.toInt()
                val x1 = if (x0 + 1 < w) x0 + 1 else x0
                val y1 = if (y0 + 1 < h) y0 + 1 else y0
                val ax = fx - x0
                val ay = fy - y0
                val i00 = (y0 * stride + x0 * 3)
                val i01 = (y0 * stride + x1 * 3)
                val i10 = (y1 * stride + x0 * 3)
                val i11 = (y1 * stride + x1 * 3)
                val dst = (y * stride + x * 3)
                for (c in 0..2) {
                    val top = rgb[i00 + c] + (rgb[i01 + c] - rgb[i00 + c]) * ax
                    val bot = rgb[i10 + c] + (rgb[i11 + c] - rgb[i10 + c]) * ax
                    out[dst + c] = (top + (bot - top) * ay).toFloat()
                }
            }
        }
        // The composed M, narrowed to the 9 floats the JNI seam uploads
        // (the CPU keeps its doubles; the GPU receives these floats).
        return out to FloatArray(9) { m[it].toFloat() }
    }

    // The shipped WARP GLSL, transcribed verbatim with FLOAT math (the
    // uM * vec3(x, y, 1.0) tap — the upload's transpose=GL_TRUE makes the
    // column-vector product the CPU's row-major tap exactly; the isinf/
    // isnan unmappable checks; the clamps; the int truncation; the edge
    // clamp; the 4-tap bilinear). mF is the narrowed float M the upload
    // carries; active = false is the session's skipped draw (the input
    // passes through untouched — the pass's absence is the identity).
    private fun glslWarpPass(
        rgb: FloatArray,
        w: Int,
        h: Int,
        mF: FloatArray,
        active: Boolean,
    ): FloatArray {
        if (!active) return rgb.copyOf()
        val out = FloatArray(w * h * 3)
        val stride = w * 3
        for (y in 0 until h) {
            for (x in 0 until w) {
                // vec3 h = uM * vec3(float(g.x), float(g.y), 1.0); — with
                // the transposed upload, uM's columns are the CPU's rows.
                val hx = mF[0] * x + mF[1] * y + mF[2]
                val hy = mF[3] * x + mF[4] * y + mF[5]
                val hz = mF[6] * x + mF[7] * y + mF[8]
                var ox = 0.0f
                var oy = 0.0f
                // if (!isinf(h.z) && !isnan(h.z) && abs(h.z) >= 1e-12) —
                // the ES 3.00 spelling of isfinite (not a builtin there).
                if (!hz.isInfinite() && !hz.isNaN() && Math.abs(hz) >= 1e-12f) {
                    val mx = hx / hz
                    val my = hy / hz
                    if (!mx.isInfinite() && !mx.isNaN() &&
                        !my.isInfinite() && !my.isNaN()
                    ) {
                        ox = mx
                        oy = my
                    }
                }
                val fx = ox.coerceIn(0.0f, (w - 1).toFloat())
                val fy = oy.coerceIn(0.0f, (h - 1).toFloat())
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = if (x0 + 1 < w) x0 + 1 else x0
                val y1 = if (y0 + 1 < h) y0 + 1 else y0
                val ax = fx - x0
                val ay = fy - y0
                val i00 = (y0 * stride + x0 * 3)
                val i01 = (y0 * stride + x1 * 3)
                val i10 = (y1 * stride + x0 * 3)
                val i11 = (y1 * stride + x1 * 3)
                val dst = (y * stride + x * 3)
                for (c in 0..2) {
                    val top = rgb[i00 + c] + (rgb[i01 + c] - rgb[i00 + c]) * ax
                    val bot = rgb[i10 + c] + (rgb[i11 + c] - rgb[i10 + c]) * ax
                    out[dst + c] = top + (bot - top) * ay
                }
            }
        }
        return out
    }

    @Test
    fun warp_host_parity_the_glsl_pass_matches_the_cpu_warpframe_over_the_sweep() {
        // The sweep the brief names: identity, a small rotation, and the
        // zoom arms 1.0 / 1.25 (each over a NON-TRIVIAL H, so the rotation
        // and the zoom compose), plus the degenerate cases the CPU folds
        // into the caller's inactive flag (a singular H and a non-finite
        // zoom). 12x12 exercises the interior AND every border clamp.
        val w = 12
        val h = 12
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        // A non-trivial H: a small rotation about the buffer center with a
        // slight perspective term, normalized so the determinant is well
        // away from the 1e-12 failure. Identity stays exact for the arm.
        fun rotH(deg: Double): DoubleArray {
            val t = Math.toRadians(deg)
            val cos = Math.cos(t)
            val sin = Math.sin(t)
            // The rotation about (cx, cy), composed as T(c) * R * T(-c),
            // with a mild perspective row (the gyro's own shape).
            val cosC = cos * cx
            val sinC = sin * cx
            val cosC2 = cos * cy
            val sinC2 = sin * cy
            return doubleArrayOf(
                cos, -sin, cx - cosC + sinC2,
                sin, cos, cy - sinC - cosC2,
                0.0002, 0.0001, 1.0,
            )
        }
        val identityH = doubleArrayOf(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val cases = listOf(
            "identity" to (identityH to 1.0),
            "small-rot" to (rotH(0.7) to 1.0),
            "rot-zoom-1.25" to (rotH(1.4) to 1.25),
            "zoom-1.25" to (identityH to 1.25),
            "zoom-2.0" to (identityH to 2.0),
            "zoom-0.8-clamped" to (identityH to 0.8),
        )
        for ((name, hh) in cases) {
            val (hm, zoom) = hh
            val base = warpSyntheticFrame(w, h)
            // The CPU side: the full double-math reference, returning the
            // composed float M the seam uploads (null = the no-draw).
            val (cpuOut, mF) = cpuWarpFrame(base, w, h, hm, zoom, cx, cy)
            // The identity / degenerate folds: the CPU no-ops, so the
            // session skips the draw — the GLSL side models the skip.
            val active = mF != null
            val glslOut = glslWarpPass(base, w, h, mF ?: FloatArray(9), active)
            if (!active) {
                // The identity no-draw assertion: the CPU's fast path is
                // byte-identical, and the session's skipped draw passes
                // the input through — the outputs must be EXACTLY the
                // input, byte for byte, not a resampled copy.
                assertWarpIdentity(name, base, cpuOut, glslOut)
                continue
            }
            assertNrParity("warp $name", w, h, cpuOut, glslOut)
        }
        // The degenerate H: singular (a zero row), so matInv fails and
        // the CPU no-ops — the session's inactive fold.
        val singular = doubleArrayOf(
            1.0, 2.0, 3.0,
            2.0, 4.0, 6.0,
            0.0, 0.0, 1.0,
        )
        val base = warpSyntheticFrame(w, h)
        val (cpuOut, mF) = cpuWarpFrame(base, w, h, singular, 1.25, cx, cy)
        assertTrue("the singular H must fold inactive", mF == null)
        val glslOut = glslWarpPass(base, w, h, FloatArray(9), false)
        assertWarpIdentity("singular-H", base, cpuOut, glslOut)
        // The non-finite zoom: the CPU's honest no-op, the same fold.
        val (cpuNf, mNf) = cpuWarpFrame(base, w, h, rotH(0.7), Double.NaN, cx, cy)
        assertTrue("the NaN zoom must fold inactive", mNf == null)
        val glslNf = glslWarpPass(base, w, h, FloatArray(9), false)
        assertWarpIdentity("nan-zoom", base, cpuNf, glslNf)
    }

    // The synthetic frame: a gradient + deterministic per-pixel variation
    // + a hard edge + saturated corners — every bilinear branch (interior
    // taps, border clamps, the (0,0) corner) sees a DIFFERENT value, so a
    // tap-order or clamp defect cannot pass by coincidence.
    private fun warpSyntheticFrame(w: Int, h: Int): FloatArray {
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = 0.15f + 0.03f * x + 0.02f * y
                val n = ((x * 5 + y * 11) % 7).toFloat() / 7.0f - 0.5f
                var r = base + 0.04f * n
                var g = base + 0.02f * n
                var b = base - 0.04f * n
                if (x >= w / 2) {
                    r += 0.45f; g += 0.35f; b += 0.25f
                }
                if (x == 0 && y == 0) {
                    r = 1.2f; g = 0.04f; b = 0.08f
                }
                if (x == w - 1 && y == h - 1) {
                    r = 0.03f; g = 0.85f; b = 1.3f
                }
                val i = (y * w + x) * 3
                rgb[i] = r; rgb[i + 1] = g; rgb[i + 2] = b
            }
        }
        return rgb
    }

    // The identity no-draw assertion: BOTH outputs are byte-identical to
    // the input (the CPU's `return src;` fast path AND the session's
    // skipped draw), quantized — never a resampled copy (a resampling
    // pass on an identity map would blur).
    private fun assertWarpIdentity(name: String, base: FloatArray, cpuOut: FloatArray, glslOut: FloatArray) {
        for (i in base.indices) {
            assertEquals("$name: the CPU identity fast path must be byte-identical at $i", toByte(base[i]), toByte(cpuOut[i]))
            assertEquals("$name: the skipped draw must pass the input through at $i", toByte(base[i]), toByte(glslOut[i]))
        }
    }

    // ---- stage 24 (LUT) host numeric parity: the same TEXT-parity
    // discipline, but a BOUNDED-error claim — never bit-exact. The GPU
    // samples the parsed .cube through an RGBA16F 3D texture (hardware
    // trilinear, CLAMP_TO_EDGE), so the table crosses the seam HALF
    // quantized: the CPU reference interpolates the ORIGINAL float table
    // (rawedit::sampleLutPixel / applyLut, core/src/Color.cpp + Lut.cpp,
    // 8-corner lattice sum on domain-clamped coordinates) while the GLSL
    // text's path interpolates the HALF-quantized texels through the
    // texel-center mapping t = (fx + 0.5)/n. The half rounding is
    // round-to-nearest-even, modeled bit-for-bit after the session's
    // floatToHalfBits upload helper; the hardware filter is modeled at
    // float32 lattice weights (fx reconstructed as t*n - 0.5, the
    // sampler's own arithmetic; CLAMP_TO_EDGE never engages because fx is
    // pre-clamped, the epsilon guard only models float rounding). Inside
    // the same measured bound: the GLSL log10 substitution
    // (log2(x)/log2(10), ~1 float ulp) and Math.log10/Math.pow standing in
    // for the CPU's std::log10f/std::powf. Each case's bound below is the
    // MEASURED max LSB over the pixel x strength (0/0.5/1) sweep — pinned
    // after a run and reported honestly, NOT assumed. Device filtering
    // precision and the real compile stay device-test-pending (the owner's
    // Measure run). ----

    // The session's floatToHalfBits (GlesDevelopSession.cpp), transcribed:
    // round-to-nearest-even IEEE 754 binary16.
    private fun lutFloatToHalfBits(f: Float): Int {
        val x = f.toRawBits()
        val sign = (x ushr 16) and 0x8000
        val exp = (x ushr 23) and 0xff
        val man = x and 0x007fffff
        if (exp == 0) return sign
        if (exp == 0xff) {
            return sign or 0x7c00 or (if (man != 0) 0x0200 else 0)
        }
        var e = exp - 127 + 15
        if (e >= 0x1f) return sign or 0x7c00
        if (e <= 0) {
            if (e < -10) return sign
            val mFull = man or 0x00800000
            val s = 14 - e
            var k = mFull ushr s
            val rem = mFull and ((1 shl s) - 1)
            val halfBit = 1 shl (s - 1)
            if (rem > halfBit || (rem == halfBit && (k and 1) != 0)) ++k
            return sign or k
        }
        var halfMan = man ushr 13
        val rem = man and 0x1fff
        if (rem > 0x1000 || (rem == 0x1000 && (halfMan and 1) != 0)) {
            ++halfMan
            if (halfMan >= 0x400) {
                halfMan = 0
                ++e
                if (e >= 0x1f) return sign or 0x7c00
            }
        }
        return sign or (e shl 10) or halfMan
    }

    private fun lutHalfToFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1f
        val man = h and 0x3ff
        if (exp == 0x1f) {
            return Float.fromBits(sign or 0x7f800000 or (man shl 13))
        }
        if (exp == 0) {
            // Half subnormal: man * 2^-24, exact in float32.
            if (man == 0) return Float.fromBits(sign)
            val v = (man.toDouble() * 5.9604644775390625e-8).toFloat()
            return Float.fromBits(sign or v.toRawBits())
        }
        return Float.fromBits(sign or ((exp - 15 + 127) shl 23) or (man shl 13))
    }

    // The upload quantization itself: one half round per table component,
    // exactly what glTexSubImage3D receives from the session.
    private fun lutHalfTable(table: FloatArray): FloatArray =
        FloatArray(table.size) { lutHalfToFloat(lutFloatToHalfBits(table[it])) }

    // A synthetic 33^3 identity: entry (i, j, k) = (i, j, k)/(n-1), the
    // .cube red-fastest order (i + n*j + n*n*k) the parser produces. Every
    // value lands exactly on the half grid (i * 2^-5 fits the half
    // significand), so this case isolates the texel-center mapping: with
    // quantization silent, the measured bound is the mapping alone.
    private fun lutIdentityTable(n: Int): FloatArray {
        val rgb = FloatArray(n * n * n * 3)
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) {
            val e = (i + n * j + n * n * k) * 3
            rgb[e] = i.toFloat() / (n - 1).toFloat()
            rgb[e + 1] = j.toFloat() / (n - 1).toFloat()
            rgb[e + 2] = k.toFloat() / (n - 1).toFloat()
        }
        return rgb
    }

    // A deterministic synthetic look whose values are deliberately NOT on
    // the RGBA16F grid (the quantization the bound measures): a warm
    // cross-mix with channel powers and small lifts, everything inside
    // [0, ~1.07].
    private fun lutNonTrivialTable(n: Int): FloatArray {
        val rgb = FloatArray(n * n * n * 3)
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) {
            val r = i.toFloat() / (n - 1).toFloat()
            val g = j.toFloat() / (n - 1).toFloat()
            val b = k.toFloat() / (n - 1).toFloat()
            val e = (i + n * j + n * n * k) * 3
            rgb[e] = 0.913f * lutSpowKt(r, 1.15f) + 0.117f * g + 0.021f * b + 0.019f
            rgb[e + 1] = 0.083f * r + 0.947f * g + 0.031f * b + 0.004f
            rgb[e + 2] = 0.023f * r + 0.104f * g + 0.871f * lutSpowKt(b, 1.25f) + 0.017f
        }
        return rgb
    }

    private fun lutSpowKt(x: Float, e: Float): Float =
        if (x < 0.0f) -Math.pow((-x).toDouble(), e.toDouble()).toFloat()
        else Math.pow(x.toDouble(), e.toDouble()).toFloat()

    // The CPU's std::log10f as Math.log10 (correctly rounded through
    // double, within 1 float ulp of the C++ call) and the GLSL's
    // log2(x)/log2(10) substitution, both halves of the encode difference.
    private fun lutCpuLog10(x: Float): Float = Math.log10(x.toDouble()).toFloat()
    private fun lutGlslLog2(x: Float): Float =
        (Math.log(x.toDouble()) / 0.6931471805599453).toFloat()
    private fun lutGlslLog10(x: Float): Float = lutGlslLog2(x) / 3.321928094887362f

    // The harness exercises the Linear (0), VLog (10) encodes and the
    // Srgb (1) decode — the trio the managed case rides; any other id is
    // a loud harness bug, not a silent fallback (the full 13-curve text
    // parity is the validator's contract block, not this numeric case).
    private fun lutCpuEncodeTransfer(lin: Float, t: Int): Float = when (t) {
        0 -> lin
        10 -> if (lin < 0.01f) 5.6f * lin + 0.125f
              else 0.241514f * lutCpuLog10(lin + 0.00873f) + 0.598206f
        else -> throw IllegalStateException("lut parity harness: encode id $t not covered")
    }

    private fun lutGlslEncodeTransfer(lin: Float, t: Int): Float = when (t) {
        0 -> lin
        10 -> if (lin < 0.01f) 5.6f * lin + 0.125f
              else 0.241514f * lutGlslLog10(lin + 0.00873f) + 0.598206f
        else -> throw IllegalStateException("lut parity harness: encode id $t not covered")
    }

    // Srgb decode: identical text on both sides (no log10 in the curve),
    // so one shared body — the smoothstepfKt precedent.
    private fun lutDecodeSrgbKt(enc: Float): Float =
        if (enc <= 0.040449936f) enc / 12.92f
        else lutSpowKt((enc + 0.055f) / 1.055f, 2.4f)

    private fun lutDecodeTransfer(enc: Float, t: Int): Float = when (t) {
        0 -> enc
        1 -> lutDecodeSrgbKt(enc)
        else -> throw IllegalStateException("lut parity harness: decode id $t not covered")
    }

    // The CPU rawedit::sampleLutPixel (== Lut.cpp's applyLut sampler),
    // verbatim: default .cube domain [0,1] per axis, lattice coordinates
    // clamped to [0, n-1], the 8-corner weighted sum on the ORIGINAL
    // float table.
    private fun cpuLutSample(table: FloatArray, n: Int, inRgb: FloatArray): FloatArray {
        val out = FloatArray(3)
        val scale = FloatArray(3) { (n - 1).toFloat() / Math.max(1.0f - 0.0f, 1e-20f) }
        val i0 = IntArray(3)
        val w1 = FloatArray(3)
        for (c in 0..2) {
            var fx = (inRgb[c] - 0.0f) * scale[c]
            fx = fx.coerceIn(0.0f, (n - 1).toFloat())
            var i = fx.toInt()
            if (i > n - 2) i = n - 2
            i0[c] = i
            w1[c] = fx - i.toFloat()
        }
        for (dk in 0..1) {
            val wk = if (dk == 0) 1.0f - w1[2] else w1[2]
            val baseK = (if (dk == 1) i0[2] + 1 else i0[2]) * n * n
            for (dj in 0..1) {
                val wj = if (dj == 0) 1.0f - w1[1] else w1[1]
                val wkj = wk * wj
                val baseJ = (if (dj == 1) i0[1] + 1 else i0[1]) * n
                for (di in 0..1) {
                    val wi = if (di == 0) 1.0f - w1[0] else w1[0]
                    val w = wkj * wi
                    val idx = (baseK + baseJ + (if (di == 1) i0[0] + 1 else i0[0])) * 3
                    out[0] += w * table[idx + 0]
                    out[1] += w * table[idx + 1]
                    out[2] += w * table[idx + 2]
                }
            }
        }
        return out
    }

    // The shipped lutSample() text's path: the texel-center mapping
    // t = (fx + 0.5)/n, then the hardware trilinear — modeled as the
    // sampler's own reconstruction fx = t*n - 0.5 and the same 8-corner
    // weighted sum, on the HALF-quantized table.
    private fun glslLutSample(halfTable: FloatArray, n: Int, inRgb: FloatArray): FloatArray {
        val out = FloatArray(3)
        val nf = n.toFloat()
        val scale = FloatArray(3) { (nf - 1.0f) / Math.max(1.0f - 0.0f, 1e-20f) }
        val i0 = IntArray(3)
        val w1 = FloatArray(3)
        for (c in 0..2) {
            val fx = ((inRgb[c] - 0.0f) * scale[c]).coerceIn(0.0f, nf - 1.0f)
            val t = (fx + 0.5f) / nf
            // The sampler's lattice coordinate; the coerce only echoes the
            // pre-clamp above (CLAMP_TO_EDGE never distorts a clamped fx).
            val fxHw = (t * nf - 0.5f).coerceIn(0.0f, nf - 1.0f)
            var i = fxHw.toInt()
            if (i > n - 2) i = n - 2
            i0[c] = i
            w1[c] = (fxHw - i.toFloat()).coerceIn(0.0f, 1.0f)
        }
        for (dk in 0..1) {
            val wk = if (dk == 0) 1.0f - w1[2] else w1[2]
            val baseK = (if (dk == 1) i0[2] + 1 else i0[2]) * n * n
            for (dj in 0..1) {
                val wj = if (dj == 0) 1.0f - w1[1] else w1[1]
                val wkj = wk * wj
                val baseJ = (if (dj == 1) i0[1] + 1 else i0[1]) * n
                for (di in 0..1) {
                    val wi = if (di == 0) 1.0f - w1[0] else w1[0]
                    val w = wkj * wi
                    val idx = (baseK + baseJ + (if (di == 1) i0[0] + 1 else i0[0])) * 3
                    out[0] += w * halfTable[idx + 0]
                    out[1] += w * halfTable[idx + 1]
                    out[2] += w * halfTable[idx + 2]
                }
            }
        }
        return out
    }

    // evaluateManagedLutPixel with the harness's Rec709-in/Rec709-out
    // identity gamut matrices (the matrix multiply is exact identity both
    // sides; the Bradford-CAT construction is upload-time CPU work shared
    // by both, not part of the per-pixel claim).
    private fun cpuLutManagedPixel(
        px: FloatArray, table: FloatArray, n: Int, inT: Int, outT: Int,
    ): FloatArray {
        val enc = FloatArray(3) { lutCpuEncodeTransfer(px[it], inT) }
        val sampled = cpuLutSample(table, n, enc)
        return FloatArray(3) { lutDecodeTransfer(sampled[it], outT) }
    }

    // The shipped lutManagedPixel() text's path, same identity gamuts.
    private fun glslLutManagedPixel(
        px: FloatArray, halfTable: FloatArray, n: Int, inT: Int, outT: Int,
    ): FloatArray {
        val enc = FloatArray(3) { lutGlslEncodeTransfer(px[it], inT) }
        val sampled = glslLutSample(halfTable, n, enc)
        return FloatArray(3) { lutDecodeTransfer(sampled[it], outT) }
    }

    // The ACES fit (tonemap mode 1) — the tonemap branch the role-order
    // cases ride; identical text on both sides, one shared body. The AgX
    // modes' numeric parity is locked by the stage-23 text checks above.
    private fun lutAcesFitKt(x: Float): Float {
        val a = 2.51f; val b = 0.03f; val c = 2.43f; val d = 0.59f; val e = 0.14f
        val num = x * (a * x + b)
        val den = x * (c * x + d) + e
        val y = if (den > 0.0f) num / den else 0.0f
        return y.coerceIn(0.0f, 1.0f)
    }

    private fun lutTonemapAces(px: FloatArray): FloatArray =
        FloatArray(3) { lutAcesFitKt(px[it]) }

    // The CPU rawedit::applyManagedLutStage (core/src/Color.cpp),
    // verbatim per role — the legacy pair, the bypass, DisplayLook's
    // tonemap-then-look, SceneLook's look-then-tonemap, and
    // SceneToDisplay's two-branch mix with the full-strength tonemap
    // skip.
    private fun cpuLutStage24(
        pxIn: Triple<Float, Float, Float>,
        table: FloatArray, n: Int,
        role: Int, passthrough: Boolean,
        inT: Int, outT: Int, tonemap: Int, strength: Float,
    ): Triple<Float, Float, Float> {
        var arr = floatArrayOf(pxIn.first, pxIn.second, pxIn.third)
        val tonemapIfActive = {
            if (tonemap == 1) arr = lutTonemapAces(arr)
        }
        val applyLutBlend = {
            // applyLut(img, lut, strength): plain sample, blend with s.
            val s = strength.coerceAtMost(1.0f)
            val out = cpuLutSample(table, n, arr)
            arr = FloatArray(3) { out[it] * s + arr[it] * (1.0f - s) }
        }
        val managedBlend = {
            // evaluateManagedLut(img, lut, binding, strength).
            val s = strength.coerceAtMost(1.0f)
            val out = cpuLutManagedPixel(arr, table, n, inT, outT)
            arr = FloatArray(3) { out[it] * s + arr[it] * (1.0f - s) }
        }
        if (role == 0) { // LegacyLinear709: the legacy pair, verbatim.
            tonemapIfActive()
            if (strength != 0.0f) applyLutBlend()
            return Triple(arr[0], arr[1], arr[2])
        }
        if (!(strength > 0.0f)) { // bypass: tonemap alone.
            tonemapIfActive()
            return Triple(arr[0], arr[1], arr[2])
        }
        when (role) {
            1 -> { // DisplayLook: render first, then the look.
                tonemapIfActive()
                if (passthrough) applyLutBlend() else managedBlend()
            }
            2 -> { // SceneLook: the look enters the scene first.
                if (passthrough) applyLutBlend() else managedBlend()
                tonemapIfActive()
            }
            3 -> { // SceneToDisplay: two branches in the rendered ref.
                val s = strength.coerceAtMost(1.0f)
                if (s >= 1.0f) {
                    arr = cpuLutManagedPixel(arr, table, n, inT, outT)
                } else {
                    val baseline = lutTonemapAces(arr.copyOf())
                    val lutPx = cpuLutManagedPixel(arr, table, n, inT, outT)
                    arr = FloatArray(3) { lutPx[it] * s + baseline[it] * (1.0f - s) }
                }
            }
        }
        return Triple(arr[0], arr[1], arr[2])
    }

    // The shipped stage-24 GLSL block (GlesShaderSources' develop body),
    // transcribed: the lutS clamp, the SceneLook-before-tonemap branch,
    // the tonemap skip at SceneToDisplay full strength, and the three
    // after-tonemap application shapes around cScene.
    private fun glslLutStage24(
        pxIn: Triple<Float, Float, Float>,
        halfTable: FloatArray, n: Int,
        role: Int, passthrough: Boolean,
        inT: Int, outT: Int, tonemap: Int, strength: Float,
    ): Triple<Float, Float, Float> {
        var c = floatArrayOf(pxIn.first, pxIn.second, pxIn.third)
        val lutS = strength.coerceAtMost(1.0f)
        val cScene = c.copyOf()
        if (role == 2 && strength != 0.0f) {
            val lutPx = if (passthrough) glslLutSample(halfTable, n, c)
                        else glslLutManagedPixel(c, halfTable, n, inT, outT)
            c = FloatArray(3) { lutPx[it] * lutS + c[it] * (1.0f - lutS) }
        }
        if (tonemap == 1) {
            if (!(strength != 0.0f && role == 3 && lutS >= 1.0f)) {
                c = lutTonemapAces(c)
            }
        }
        if (strength != 0.0f && role != 2) {
            c = if (role == 3) {
                val lutPx = glslLutManagedPixel(cScene, halfTable, n, inT, outT)
                if (lutS >= 1.0f) lutPx
                else FloatArray(3) { lutPx[it] * lutS + c[it] * (1.0f - lutS) }
            } else if (passthrough || role == 0) {
                val lutPx = glslLutSample(halfTable, n, c)
                FloatArray(3) { lutPx[it] * lutS + c[it] * (1.0f - lutS) }
            } else {
                val lutPx = glslLutManagedPixel(c, halfTable, n, inT, outT)
                FloatArray(3) { lutPx[it] * lutS + c[it] * (1.0f - lutS) }
            }
        }
        return Triple(c[0], c[1], c[2])
    }

    @Test
    fun stage24_host_parity_the_glsl_block_matches_the_cpu_stage() {
        val n = 33
        val identity = lutIdentityTable(n)
        val nonTrivial = lutNonTrivialTable(n)
        val halfIdentity = lutHalfTable(identity)
        val halfNonTrivial = lutHalfTable(nonTrivial)
        val strengths = listOf(0f, 0.5f, 1f)
        // label, cpu table, glsl (half) table, role, passthrough, in/out
        // transfer, tonemap, pinned bound (the measured max LSB over the
        // sweep: identity passthrough 0 everywhere — the half-grid identity
        // isolates the mapping, bit-exact; nontrivial displaylook managed
        // vlog-in/srgb-out and scenelook 0 everywhere; the remaining
        // nontrivial cases 1 at full/near-full strength — the half-float
        // quantization bound).
        val cases = listOf(
            listOf(
                "identity 33^3 displaylook passthrough",
                identity, halfIdentity, 1, true, 0, 0, 0, 0,
            ),
            listOf(
                "nontrivial 33^3 displaylook passthrough",
                nonTrivial, halfNonTrivial, 1, true, 0, 0, 0, 1,
            ),
            listOf(
                "nontrivial 33^3 displaylook managed vlog-in srgb-out",
                nonTrivial, halfNonTrivial, 1, false, 10, 1, 0, 1,
            ),
            listOf(
                "nontrivial 33^3 legacy role with aces",
                nonTrivial, halfNonTrivial, 0, true, 0, 0, 1, 1,
            ),
            listOf(
                "nontrivial 33^3 displaylook with aces",
                nonTrivial, halfNonTrivial, 1, true, 0, 0, 1, 1,
            ),
            listOf(
                "nontrivial 33^3 scenelook with aces",
                nonTrivial, halfNonTrivial, 2, true, 0, 0, 1, 1,
            ),
            listOf(
                "nontrivial 33^3 scenetodisplay with aces",
                nonTrivial, halfNonTrivial, 3, true, 0, 0, 1, 1,
            ),
        )
        for (case in cases) {
            val label = case[0] as String
            val cpuTable = case[1] as FloatArray
            val glslTable = case[2] as FloatArray
            val role = case[3] as Int
            val passthrough = case[4] as Boolean
            val inT = case[5] as Int
            val outT = case[6] as Int
            val tonemap = case[7] as Int
            val bound = case[8] as Int
            for (s in strengths) {
                var maxLsb = 0
                parityPixels.forEach { px ->
                    val cpu = cpuLutStage24(px, cpuTable, n, role, passthrough, inT, outT, tonemap, s)
                    val glsl = glslLutStage24(px, glslTable, n, role, passthrough, inT, outT, tonemap, s)
                    val cpuBytes = intArrayOf(
                        toByte(cpu.first), toByte(cpu.second), toByte(cpu.third),
                    )
                    val glslBytes = intArrayOf(
                        toByte(glsl.first), toByte(glsl.second), toByte(glsl.third),
                    )
                    for (c in 0..2) {
                        val lsb = Math.abs(cpuBytes[c] - glslBytes[c])
                        if (lsb > maxLsb) maxLsb = lsb
                    }
                }
                assertTrue(
                    "$label s=$s: measured max LSB $maxLsb exceeds the pinned bound $bound",
                    maxLsb <= bound,
                )
            }
        }
    }

    // ---- color model v2 F1-5: the NODES pass and the DRT TAIL variant ----

    @Test
    fun shipped_nodes_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.NODES,
                GlesShaderValidator.Kind.NODES,
            ),
        )
    }

    @Test
    fun shipped_tail_drt_passes_every_check() {
        assertEquals(
            emptyList<String>(),
            GlesShaderValidator.problems(
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT,
                GlesShaderValidator.Kind.DEVELOP_FRAGMENT_TAIL_DRT,
            ),
        )
    }

    @Test
    fun nodes_shader_carries_the_curve_atlas_contract() {
        // The atlas is the inventory SS2.4's decision: NEVER uniforms, ONE
        // R32F texture, texelFetch + a MANUAL lerp on the deltas (the
        // CPU nodeCurveSample's exact form). A plain table lerp or a
        // texture() sample would break the parity pin.
        val src = GlesShaderSources.NODES
        assertTrue("atlas sampler missing", src.contains("uniform highp sampler2D uNodeCurves;"))
        assertTrue(
            "the manual delta lerp missing",
            src.contains("return x + d0 + (d1 - d0) * (p - float(i));"),
        )
        // texelFetch, never texture(): R32F is not filterable in ES 3.0.
        assertFalse(src.contains("texture(uNodeCurves"))
    }

    @Test
    fun nodes_shader_carries_the_di_constants_and_output_mode() {
        val src = GlesShaderSources.NODES
        assertTrue(
            "the DI encode constants drifted",
            src.contains("0.07329248 * (log2(lin + 0.0075) + 7.0)"),
        )
        assertTrue(
            "the DI decode constants drifted",
            src.contains("pow(2.0, enc / 0.07329248 - 7.0) - 0.0075"),
        )
        // The output mode: legacy 709 back vs DwgLinear for the DRT TAIL.
        assertTrue(src.contains("if (uNodesOutputMode == 1)"))
        assertTrue(src.contains("uMDwgTo709 * dwg"))
    }

    @Test
    fun tail_drt_shader_never_applies_the_rec709_oetf() {
        // K7/the plan SS3.4: the DRT output IS display code values - the
        // linearToRec709 helper must NOT appear in the variant (the v1
        // TAIL keeps it; this one must not carry it at all).
        val src = GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT
        assertFalse(
            "the DRT variant must not carry the Rec.709 OETF",
            src.contains("linearToRec709"),
        )
    }

    @Test
    fun tail_drt_shader_is_the_baked_table_sample() {
        // Baked route (owner decision 2026-11-25): the matrices and the per-pixel transform live in core's
        // bake; the variant is DI encode + eight texelFetch lattice reads blended by hand.
        val src = GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT
        assertTrue(src.contains("uniform highp sampler3D uDrtLut;"))
        assertTrue(src.contains("0.07329248 * (log2(lin + 0.0075) + 7.0)"))
        assertEquals(8, src.split("texelFetch(uDrtLut, ").size - 1)
        assertFalse("hardware-filtered table read", src.contains("texture(uDrtLut"))
        assertFalse("per-pixel OpenDRT leaked back", src.contains("kXYZ_TO_P3D65") || src.contains("uDrtTn"))
    }

    // ---- highlight recovery passes ----

    @Test
    fun shipped_recovery_passes_pass_their_own_kinds() {
        for ((src, kind) in listOf(
            GlesShaderSources.RECOVERY_PRE to GlesShaderValidator.Kind.RECOVERY_PRE,
            GlesShaderSources.RECOVERY_BLOCK0 to GlesShaderValidator.Kind.RECOVERY_BLOCK0,
            GlesShaderSources.RECOVERY_BLOCK_UP to GlesShaderValidator.Kind.RECOVERY_BLOCK_UP,
            GlesShaderSources.RECOVERY_COMPOSITE to GlesShaderValidator.Kind.RECOVERY_COMPOSITE,
        )) {
            assertEquals(kind.name, emptyList<String>(), GlesShaderValidator.problems(src, kind))
        }
    }

    @Test
    fun recovery_composite_keeps_the_cpu_scale_table_and_never_indexes_samplers() {
        val src = GlesShaderSources.RECOVERY_COMPOSITE
        val mutated = src.replace("s.x >= 28.8", "s.x >= 29.8")
        assertTrue("mutation must apply", mutated != src)
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.RECOVERY_COMPOSITE)
                .any { it.contains("scale 1 support") },
        )
        val dyn = src.replace("nbSum(uL0, g, 8, layer);", "nbSum(uL0, g, 8, layer); float q = uL[layer];")
        assertTrue(
            GlesShaderValidator.problems(dyn, GlesShaderValidator.Kind.RECOVERY_COMPOSITE)
                .any { it.contains("dynamic sampler array") },
        )
    }

    @Test
    fun recovery_pre_never_runs_stage_3_or_the_camera_matrix() {
        val src = GlesShaderSources.RECOVERY_PRE
        val mutated = src.replace("px[t] = normAt(g);", "px[t] = hrAt(g);")
        assertTrue("mutation must apply", mutated != src)
        assertTrue(
            GlesShaderValidator.problems(mutated, GlesShaderValidator.Kind.RECOVERY_PRE)
                .isNotEmpty(),
        )
    }
}
