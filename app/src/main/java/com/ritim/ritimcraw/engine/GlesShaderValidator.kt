package com.ritim.ritimcraw.engine

/**
 * Host syntax + contract validator for the GLSL ES sources
 * (owner request 2026-11-12, deliverable C for the compute source;
 * deliverable D adds the fragment chain: fullscreen vertex, fused develop
 * fragment, SSBO readback fragment).
 *
 * HONESTY: this is NOT a compile. This host has no GLSL compiler (no
 * glslangValidator, no glslc) and no EGL, so no compile claim is made
 * anywhere on the host. What this check DOES do, on the exact text the
 * device will compile (GlesShaderSources — the single source of truth):
 *
 *  - ASCII printable only (a stray unicode dash from an editor paste
 *    would otherwise surface only as an opaque device compile error);
 *  - the `#version` line the kind requires as the first statement
 *    (310 es for compute/SSBO-fragment, 300 es for the fragment chain);
 *  - balanced () [] {} after comment stripping;
 *  - the precision statement the pipeline requires (highp float);
 *  - glReadPixels appears NOWHERE (the recorded baseline invariant —
 *    every readback goes through a mapped SSBO);
 *  - the per-kind CONTRACT: the uniforms/blocks/binding points the
 *    C++ harness in gles/GlesPipeline.cpp looks up and sets, and the
 *    CPU-kernel text the fragment pass must mirror (kDx/kDy order,
 *    clamp-before-site-test, own-site passthrough, n==0 fallback, the
 *    OETF constants). A silent edit of either copy — shader or CPU —
 *    surfaces here as a loud problem string.
 *
 * The real compile is device-test-pending (the owner's Measure run).
 * Pure JVM: no Android classes, no file IO.
 */
object GlesShaderValidator {

    /** The shader kinds the pipeline ships, each with its required version. */
    enum class Kind(internal val versionLine: String) {
        /** DEMOSAIC_COMPUTE — GLSL ES 3.10 compute, SSBOs in/out. */
        COMPUTE("#version 310 es"),

        /** FULLSCREEN_VERTEX — GLSL ES 3.00, the draw-only vertex stage. */
        FULLSCREEN_VERTEX("#version 300 es"),

        /** FULLSCREEN_VERTEX_310 — the same stage, 310 es (see GlesShaderSources). */
        FULLSCREEN_VERTEX_310("#version 310 es"),

        /** DEVELOP_FRAGMENT — GLSL ES 3.00 fragment, the fused develop pass. */
        DEVELOP_FRAGMENT("#version 300 es"),

        /** DEVELOP_FRAGMENT_PRE — GLSL ES 3.00 fragment, the split chain's stages 1-6 draw. */
        DEVELOP_FRAGMENT_PRE("#version 300 es"),

        /** DEVELOP_FRAGMENT_POST — GLSL ES 3.00 fragment, the split chain's stages 9-20 pointwise draw. */
        DEVELOP_FRAGMENT_POST("#version 300 es"),

        /** DEVELOP_FRAGMENT_TAIL — GLSL ES 3.00 fragment, the split chain's stages 23+24+OETF draw. */
        DEVELOP_FRAGMENT_TAIL("#version 300 es"),

        /** SHARPNESS_H — GLSL ES 3.00 fragment, sharpness's horizontal half (stage 21, pass 1 of 2). */
        SHARPNESS_H("#version 300 es"),

        /** SHARPNESS_V — GLSL ES 3.00 fragment, sharpness's vertical half + difference (stage 21, pass 2 of 2). */
        SHARPNESS_V("#version 300 es"),

        /** DETAIL_H — GLSL ES 3.00 fragment, detail's horizontal half (stage 22, pass 1 of 2). */
        DETAIL_H("#version 300 es"),

        /** DETAIL_V — GLSL ES 3.00 fragment, detail's vertical half + difference (stage 22, pass 2 of 2). */
        DETAIL_V("#version 300 es"),

        /** WARP — GLSL ES 3.00 fragment, the gyro stabilization warp (NOT a develop stage; the CPU's post-develop resample). */
        WARP("#version 300 es"),

        /** NR_CHROMA_H — GLSL ES 3.00 fragment, chroma NR's horizontal half (stage 7, pass 1 of 2). */
        NR_CHROMA_H("#version 300 es"),

        /** NR_CHROMA_V — GLSL ES 3.00 fragment, chroma NR's vertical half + blend (stage 7, pass 2 of 2). */
        NR_CHROMA_V("#version 300 es"),

        /** NR_LUMA — GLSL ES 3.00 fragment, the spatial luma bilateral (stage 8). */
        NR_LUMA("#version 300 es"),

        /** STORE_FRAGMENT — GLSL ES 3.10 fragment (SSBO write), readback. */
        STORE_FRAGMENT("#version 310 es"),

        /** BLIT_FRAGMENT — GLSL ES 3.00 fragment, the encoder-Surface blit. */
        BLIT_FRAGMENT("#version 300 es"),

        /** WINDOW_FRAGMENT — GLSL ES 3.00 fragment, the preview window/stats draw. */
        WINDOW_FRAGMENT("#version 300 es"),

        /**
         * NODES — GLSL ES 3.00 fragment, the color model v2 grade node
         * chain (F1-5): 709 -> DWG -> DI at the head, the in-shader node
         * loop, the output mode's matrix at the tail. Reads the chain's
         * live RGBA32F intermediate (unit 1) + the curve atlas (unit 6,
         * R32F texelFetch) + up to 4 node-LUT samplers (units 7..10).
         */
        NODES("#version 300 es"),

        /**
         * DEVELOP_FRAGMENT_TAIL_DRT — GLSL ES 3.00 fragment, the TAIL's
         * OpenDRT variant (color model v2 F1-5): reads the NODES pass's
         * DWG-LINEAR output and applies OpenDRT v1.1.0 per pixel (the C++
         * reference's own operation order), emitting display code values
         * directly — the OETF is NOT applied (the DRT output is display
         * coded already; plan §3.4).
         */
        DEVELOP_FRAGMENT_TAIL_DRT("#version 300 es"),

        /** RECOVERY_PRE - GLSL ES 3.00 fragment, highlight recovery's demosaic + WB + clip-mask pass (replaces PRE when recovery is on). */
        RECOVERY_PRE("#version 300 es"),

        /** RECOVERY_BLOCK0 - GLSL ES 3.00 fragment, the 8-pixel block sums of the ordered-pair statistics (MRT). */
        RECOVERY_BLOCK0("#version 300 es"),

        /** RECOVERY_BLOCK_UP - GLSL ES 3.00 fragment, the 4x4 block-sum reduction to the 32/128/512 levels (MRT). */
        RECOVERY_BLOCK_UP("#version 300 es"),

        /** RECOVERY_COMPOSITE - GLSL ES 3.00 fragment, ratio selection + estimate + camera matrix. */
        RECOVERY_COMPOSITE("#version 300 es"),
    }

    /**
     * Every problem found in [source] as a COMPUTE-kind shader (empty
     * list = passed all checks) — the deliverable-C entry point, kept
     * for the compute harness and its tests.
     */
    fun problems(source: String): List<String> = problems(source, Kind.COMPUTE)

    /**
     * Every problem found in [source] as a [kind] shader (empty list =
     * passed all checks). Each entry is a human-readable one-liner for
     * the test failure or the debug log.
     */
    fun problems(source: String, kind: Kind): List<String> {
        val out = mutableListOf<String>()
        if (source.isBlank()) {
            out += "empty source"
            return out
        }
        for (ch in source) {
            if (ch != '\n' && ch != '\t' && (ch.code < 32 || ch.code > 126)) {
                out += "non-ascii or control character U+%04X".format(ch.code)
                break
            }
        }
        val stripped = stripComments(source)
        val firstLine = stripped.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
        if (firstLine != kind.versionLine) {
            out += "first statement must be `${kind.versionLine}` (got: $firstLine)"
        }
        out += balanceProblems(stripped)
        if (!stripped.contains("precision highp float;")) {
            out += "missing `precision highp float;`"
        }
        if (stripped.contains("glReadPixels")) {
            out += "glReadPixels is banned (readback goes through a mapped " +
                "SSBO — the recorded baseline invariant)"
        }
        when (kind) {
            Kind.COMPUTE -> computeProblems(stripped, out)
            Kind.FULLSCREEN_VERTEX,
            Kind.FULLSCREEN_VERTEX_310,
            -> fullscreenVertexProblems(stripped, out)
            Kind.DEVELOP_FRAGMENT -> developFragmentProblems(stripped, out)
            Kind.DEVELOP_FRAGMENT_PRE -> developFragmentPreProblems(stripped, out)
            Kind.DEVELOP_FRAGMENT_POST -> developFragmentPostProblems(stripped, out)
            Kind.DEVELOP_FRAGMENT_TAIL -> developFragmentTailProblems(stripped, out)
            Kind.SHARPNESS_H -> sharpnessHProblems(stripped, out)
            Kind.SHARPNESS_V -> sharpnessVProblems(stripped, out)
            Kind.DETAIL_H -> detailHProblems(stripped, out)
            Kind.DETAIL_V -> detailVProblems(stripped, out)
            Kind.WARP -> warpProblems(stripped, out)
            Kind.NR_CHROMA_H -> nrChromaHProblems(stripped, out)
            Kind.NR_CHROMA_V -> nrChromaVProblems(stripped, out)
            Kind.NR_LUMA -> nrLumaProblems(stripped, out)
            Kind.STORE_FRAGMENT -> storeFragmentProblems(stripped, out)
            Kind.BLIT_FRAGMENT -> blitFragmentProblems(stripped, out)
            Kind.WINDOW_FRAGMENT -> windowFragmentProblems(stripped, out)
            Kind.NODES -> nodesProblems(stripped, out)
            Kind.DEVELOP_FRAGMENT_TAIL_DRT -> tailDrtProblems(stripped, out)
            Kind.RECOVERY_PRE -> recoveryPreProblems(stripped, out)
            Kind.RECOVERY_BLOCK0 -> recoveryBlock0Problems(stripped, out)
            Kind.RECOVERY_BLOCK_UP -> recoveryBlockUpProblems(stripped, out)
            Kind.RECOVERY_COMPOSITE -> recoveryCompositeProblems(stripped, out)
        }
        return out
    }

    /** True when [source] passes every check (the shipped source must). */
    fun validate(source: String): Boolean = problems(source).isEmpty()

    /** True when [source] passes every [kind] check (the shipped sources must). */
    fun validate(source: String, kind: Kind): Boolean = problems(source, kind).isEmpty()

    // ---- per-kind contracts ----
    // Every check names the C++ line that depends on it, so a rename in
    // either copy fails loudly here instead of silently on the device.

    private fun computeProblems(s: String, out: MutableList<String>) {
        if (!s.contains("layout(local_size_x = 8, local_size_y = 8) in;")) {
            out += "missing `layout(local_size_x = 8, local_size_y = 8) in;` " +
                "(the C++ harness dispatches a grid divided by exactly 8)"
        }
        if (!s.contains("layout(std430, binding = 0) readonly buffer InBuf")) {
            out += "missing `layout(std430, binding = 0) readonly buffer InBuf` " +
                "(the C++ harness attaches the input SSBO to binding point 0 " +
                "with glBindBufferBase and assumes std430 tight float packing)"
        }
        if (!s.contains("layout(std430, binding = 1) writeonly buffer OutBuf")) {
            out += "missing `layout(std430, binding = 1) writeonly buffer OutBuf` " +
                "(the C++ harness attaches the output SSBO to binding point 1 " +
                "with glBindBufferBase and assumes std430 tight float packing)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the C++ harness sets it)"
        }
        if (!s.contains("uniform ivec4 uCell;")) {
            out += "missing `uniform ivec4 uCell;` (the C++ harness sets it)"
        }
    }

    private fun fullscreenVertexProblems(s: String, out: MutableList<String>) {
        // The harness draws glDrawArrays(TRIANGLE_STRIP, 0, 4) with NO
        // attributes and NO uniforms: the position must come from the
        // hard-coded corner array indexed by gl_VertexID.
        if (!s.contains("gl_Position = vec4(kPos[gl_VertexID] * 2.0 - 1.0, 0.0, 1.0);")) {
            out += "missing the `gl_Position = vec4(kPos[gl_VertexID] * 2.0 - ` " +
                "`1.0, 0.0, 1.0);` fullscreen assignment (the harness binds no " +
                "attributes and no uniforms — position must come from the " +
                "gl_VertexID-indexed corner array)"
        }
        if (Regex("""\buniform\b""").containsMatchIn(s)) {
            out += "a `uniform` appeared in the fullscreen vertex shader " +
                "(the harness sets no vertex uniforms; the fragment passes " +
                "derive their pixel from gl_FragCoord)"
        }
    }

    private fun developFragmentProblems(s: String, out: MutableList<String>) {
        // The uniforms the pipeline looks up and sets. Names double as the
        // C++ lookup contract. Honest split: the gles/GlesPipeline.cpp parity
        // harness binds the stage 1-6 + uExposure/uTonemap/uOetf entries
        // (and ONLY those — the grade uniforms' GLSL defaults must be the
        // exact neutral fast path so the frozen harness stays byte-honest);
        // the develop/preview SESSIONS (GlesDevelopSession,
        // GlesPreviewSession) bind the grade entries per frame. The list is
        // the PRE set + the pointwise set + the tail set (the split passes
        // share the exact same declarations with the fused pass).
        for (u in kDevelopPreUniformDecls + kDevelopPointwiseUniformDecls + kDevelopTailUniformDecls) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ harness sets it)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        checkDevelopPreStageText(s, out)
        checkDevelopPointwiseStageText(s, out)
        checkDevelopTailStageText(s, out)
        // The application point: the whole pointwise grade chain sits in
        // the CPU's stage order — 9 -> 10 -> 11 -> 12 -> 13 -> 14 -> 15 ->
        // 16 -> 17 -> 18 -> 19 -> 20 -> 23 -> 24 (LUT) -> OETF (core/src/
        // Develop.cpp developCpu, then the JNI tail's OETF). Any stage
        // moved across a neighbor is a different image, not a refactor.
        // Stage 24's SceneLook (role 2) block sits BEFORE tonemap and the
        // every-other-role block AFTER it — applyManagedLutStage's own
        // per-role order. The fused chain additionally pins the 6/9
        // boundary: the camera matrix (the last PRE stage) must precede
        // stage 9 — the exact cut the split passes share.
        val cam2rgbIdx = s.indexOf("c = uCam2rgb * c;")
        val blackPointIdx = s.indexOf("if (uBlackPoint != 0.0) {")
        val whitePointIdx = s.indexOf("if (uWhitePoint != 0.0) {")
        val shadowsIdx = s.indexOf("if (uShadows != 0.0) {")
        val midtonesIdx = s.indexOf("if (uMidtones != 0.0) {")
        val contrastIdx = s.indexOf("if (uContrast != 0.0) {")
        val exposureIdx = s.indexOf("c *= uExposure;")
        val highlightsIdx = s.indexOf("if (uHighlights != 0.0) {")
        val wbIdx = s.indexOf("if (uWbManual == 1) {")
        val tintIdx = s.indexOf("if (uWbManual == 1 && uTint != 0.0) {")
        val wheelIdx = s.indexOf(
            "if (uWheelShadowX != 0.0 || uWheelShadowY != 0.0 ||"
        )
        val splitIdx = s.indexOf("if (uSplitActive == 1) {")
        val saturationIdx = s.indexOf("if (uSaturation != 0.0) {")
        val lutSceneIdx = s.indexOf("if (uLutRole == 2 && uLutStrength != 0.0) {")
        val tonemapIdx = s.indexOf("if (uTonemap != 0) {")
        val lutPostIdx = s.indexOf("if (uLutStrength != 0.0 && uLutRole != 2) {")
        val oetfIdx = s.indexOf("if (uOetf == 1) {")
        val order = listOf(
            cam2rgbIdx, blackPointIdx, whitePointIdx, shadowsIdx,
            midtonesIdx, contrastIdx, exposureIdx, highlightsIdx, wbIdx,
            tintIdx, wheelIdx, splitIdx, saturationIdx, lutSceneIdx,
            tonemapIdx, lutPostIdx, oetfIdx,
        )
        if (order.any { it < 0 } || order.zipWithNext().any { (a, b) -> a >= b }) {
            out += "the highlights/tonemap/LUT application point is wrong " +
                "(the pointwise grade chain must sit in the CPU stage order " +
                "9 -> 10 -> 11 -> 12 -> 13 -> 14 (exposure) -> 15 -> 16 -> " +
                "17 -> 18 -> 19 -> 20 -> 23 (tonemap) -> 24 (LUT) -> OETF, " +
                "each block between its CPU neighbors — developCpu's exact " +
                "order, with stage 24's SceneLook block BEFORE tonemap and " +
                "every other role's AFTER it)"
        }
    }


    // ---- highlight recovery passes (docs/HIGHLIGHT_RECOVERY_PLAN.md) --------
    // Each check names the C++ line (GpuDevelopPipeline.cpp recovery draws) or the
    // CPU line (core/src/Develop.cpp recoverClippedHighlights) that depends on it.

    private fun recoveryRequire(s: String, out: MutableList<String>, text: String, why: String) {
        if (!s.contains(text)) out += "missing `$text` ($why)"
    }

    private fun recoveryPreProblems(s: String, out: MutableList<String>) {
        for (u in kDevelopPreUniformDecls) recoveryRequire(s, out, u, "the recovery PRE pass shares the PRE uniform set")
        recoveryRequire(s, out, "uniform vec3 uClipThr;", "the session uploads the CPU's recoveryClipLevels thresholds")
        recoveryRequire(s, out, "out vec4 oCol;", "single color output")
        recoveryRequire(s, out, "return stage1(p) >= uClipThr[siteAt(p)] ? 1.0 : 0.0;",
            "the clip mask is the PRE-shading stage-1 value against its channel threshold (the CPU's norm >= threshold)")
        recoveryRequire(s, out, "px[t] = normAt(g);", "stage 3 is REPLACED: the pixel is the lens-shaded value, never hrAt")
        recoveryRequire(s, out, "mk[t] = n > 0 ? msum / float(n) : clipAt(g);", "the mask is demosaiced by the same bilinear as the pixels")
        recoveryRequire(s, out, "vec3 c = vec3(px[0], px[1], px[2]) * uWb;", "WB division before recovery, no camera matrix here")
        recoveryRequire(s, out, "oCol = vec4(c, bits);", "rgb + the 3-bit clip mask in alpha")
        if (s.contains("hrAt(g)") || s.contains("uCam2rgb *")) out += "recovery PRE must not run stage 3 or the camera matrix"
    }

    private fun recoveryBlock0Problems(s: String, out: MutableList<String>) {
        recoveryRequire(s, out, "uniform highp sampler2D uSrc;", "the chain source on unit 1")
        recoveryRequire(s, out, "uniform ivec2 uSize;", "full-resolution size")
        recoveryRequire(s, out, "uniform int uPairBase;", "first ordered pair of the draw")
        for (i in 0..2) recoveryRequire(s, out, "layout(location = $i) out vec4 oP$i;", "MRT attachment $i of the layered block texture")
        recoveryRequire(s, out, "bool ok = ((bits >> c) & 1) == 0 && ((bits >> r) & 1) == 0 && t[r] > 1e-4 && t[c] >= 0.0;",
            "the CPU's unclipped-pair predicate (!clipped[c] && !clipped[r] && vr > kEps && vc >= 0)")
        recoveryRequire(s, out, "ivec2 o = b * 8;", "8-pixel blocks, grid aligned at 0")
        recoveryRequire(s, out, "if (p.x >= uSize.x || p.y >= uSize.y) continue;", "partial edge blocks count only real pixels")
    }

    private fun recoveryBlockUpProblems(s: String, out: MutableList<String>) {
        recoveryRequire(s, out, "uniform highp sampler2DArray uPrev;", "the previous level's layered sums on unit 1")
        recoveryRequire(s, out, "uniform ivec2 uPrevSize;", "the previous level's grid")
        recoveryRequire(s, out, "uniform int uPairBase;", "first layer of the draw")
        for (i in 0..2) recoveryRequire(s, out, "layout(location = $i) out vec4 oP$i;", "MRT attachment $i")
        recoveryRequire(s, out, "ivec2 o = ivec2(gl_FragCoord.xy) * 4;", "4x4 children per output block (8 -> 32 -> 128 -> 512)")
        recoveryRequire(s, out, "if (p.x >= uPrevSize.x || p.y >= uPrevSize.y) continue;", "partial edge blocks")
    }

    private fun recoveryCompositeProblems(s: String, out: MutableList<String>) {
        recoveryRequire(s, out, "uniform highp sampler2D uSrc;", "the recovery PRE output on unit 1")
        for ((i, u) in listOf("uL0", "uL1", "uL2", "uL3").withIndex())
            recoveryRequire(s, out, "uniform highp sampler2DArray $u;", "block level $i on unit ${12 + i}")
        recoveryRequire(s, out, "uniform ivec2 uSize;", "full-resolution size")
        recoveryRequire(s, out, "uniform mat3 uCam2rgb;", "camera matrix uploaded transpose=GL_TRUE")
        recoveryRequire(s, out, "uniform int uHasProfile;", "profile gate")
        recoveryRequire(s, out, "out vec4 oCol;", "single color output")
        recoveryRequire(s, out, "for (int dy = -4; dy <= 4; ++dy) {", "the CPU's 9x9 box scale")
        recoveryRequire(s, out, "if (s.x >= 4.05 && s.z > 0.0) return s.y / s.z;", "scale 0 support 5% of 81")
        recoveryRequire(s, out, "nbSum(uL0, g, 8, layer);", "scale 1: 8-pixel blocks")
        recoveryRequire(s, out, "if (s.x >= 28.8 && s.z > 0.0) return s.y / s.z;", "scale 1 support 5% of 9*64")
        recoveryRequire(s, out, "nbSum(uL1, g, 32, layer);", "scale 2: 32-pixel blocks")
        recoveryRequire(s, out, "if (s.x >= 460.8 && s.z > 0.0) return s.y / s.z;", "scale 2 support")
        recoveryRequire(s, out, "nbSum(uL2, g, 128, layer);", "scale 3: 128-pixel blocks")
        recoveryRequire(s, out, "if (s.x >= 7372.8 && s.z > 0.0) return s.y / s.z;", "scale 3 support")
        recoveryRequire(s, out, "nbSum(uL3, g, 512, layer);", "scale 4: 512-pixel blocks")
        recoveryRequire(s, out, "if (s.x >= 117964.8 && s.z > 0.0) return s.y / s.z;", "scale 4 support")
        recoveryRequire(s, out, "if (!cf[1]) r = 1;", "reference channel: green when it is unclipped")
        recoveryRequire(s, out, "else r = (v.r >= v.b) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);", "reference channel when green is clipped")
        recoveryRequire(s, out, "if (rho > 0.0) o[c] = max(v[c], vr * rho);", "estimate = ref x ratio, never below the clipped value")
        recoveryRequire(s, out, "v = vec3(m);", "all three clipped: neutral at the lowest channel")
        // A dynamic index into a sampler array is invalid on Adreno ES 3.00: every level is one
        // constant uniform, never indexed.
        if (Regex("""uL\d?\s*\[""").containsMatchIn(s)) out += "dynamic sampler array indexing is banned (Adreno ES 3.00)"
    }

    /** Stages 1-6's uniform declarations (the PRE pass set; the fused pass's own text). */
    private val kDevelopPreUniformDecls = listOf(
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
    )

    /** Stages 9-20's uniform declarations (the pointwise pass set; the fused pass's own text). */
    private val kDevelopPointwiseUniformDecls = listOf(
        "uniform float uExposure;",
        "uniform float uHighlights;",
        "uniform float uBlackPoint;",
        "uniform float uWhitePoint;",
        "uniform float uShadows;",
        "uniform float uMidtones;",
        "uniform float uContrast;",
        "uniform int uWbManual;",
        "uniform float uTempK;",
        "uniform float uTint;",
        "uniform float uWheelShadowX;",
        "uniform float uWheelShadowY;",
        "uniform float uWheelMidX;",
        "uniform float uWheelMidY;",
        "uniform float uWheelHighX;",
        "uniform float uWheelHighY;",
        "uniform int uSplitActive;",
        "uniform float uSplitShadowHue;",
        "uniform float uSplitHighlightHue;",
        "uniform float uSplitBalance;",
        "uniform float uSaturation;",
    )

    /** Stages 23+24+OETF's uniform declarations (the tail pass set; the fused pass's own text). */
    private val kDevelopTailUniformDecls = listOf(
        "uniform int uTonemap;",
        "uniform int uOetf;",
        // Stage 24 LUT (the LUT port, commit (a)): the raw strength ([0,1];
        // 0.0 is the CPU call site's own skip), the 3D table on unit 5, the
        // table's shape (size 0 = no table), and the managed binding's
        // role/passthrough/transfers/matrices. All pinned neutral by the
        // sessions when no LUT is active — the no-LUT chain stays
        // byte-identical.
        "uniform float uLutStrength;",
        "uniform highp sampler3D uLut;",
        "uniform int uLutSize;",
        "uniform vec3 uLutDomainMin;",
        "uniform vec3 uLutDomainMax;",
        "uniform int uLutRole;",
        "uniform int uLutPassthrough;",
        "uniform int uLutInTransfer;",
        "uniform int uLutOutTransfer;",
        "uniform mat3 uLutWorkingToInput;",
        "uniform mat3 uLutOutputToWorking;",
    )

    /**
     * The PRE stage text (stages 1-6): the demosaic kernel mirror, the
     * stage-1/2/3 contracts. Shared by the fused pass and the standalone
     * DEVELOP_FRAGMENT_PRE — the same shared fragments, the same checks.
     */
    private fun checkDevelopPreStageText(s: String, out: MutableList<String>) {
        // The CPU kernel mirror — the same text locks the compute variant
        // (rawedit::demosaicBilinear, core/src/Develop.cpp).
        if (!s.contains("int[8](-1, 1, 0, 0, -1, -1, 1, 1)")) {
            out += "missing the CPU kernel's kDx offsets in order " +
                "(rawedit::demosaicBilinear)"
        }
        if (!s.contains("int[8](0, 0, -1, 1, -1, 1, -1, 1)")) {
            out += "missing the CPU kernel's kDy offsets in order " +
                "(rawedit::demosaicBilinear)"
        }
        val clampIdx = s.indexOf("min(max(g.x + kDx[k], 0), maxI)")
        val testIdx = s.indexOf("if (siteAt(np) == t)")
        if (clampIdx < 0 || testIdx < 0 || clampIdx >= testIdx) {
            out += "the clamp must precede the site test " +
                "(rawedit::demosaicBilinear clamps neighbor coordinates " +
                "FIRST; a wrap-first shader would diverge at every border pixel)"
        }
        if (!s.contains("if (t == ownSite)")) {
            out += "missing the own-site passthrough (`if (t == ownSite)`)"
        }
        if (!s.contains("n > 0 ? sum / float(n) : hrAt(g)")) {
            out += "missing the n==0 center fallback " +
                "(`n > 0 ? sum / float(n) : hrAt(g)`)"
        }
        // Stage 1: the slot order and the clamp the CPU applies.
        if (!s.contains("int slot = (p.y & 1) * 2 + (p.x & 1);")) {
            out += "missing the CPU cellSlot order " +
                "(`int slot = (p.y & 1) * 2 + (p.x & 1);` — the uBlack/uRange " +
                "indexing)"
        }
        if (!s.contains("clamp(v, 0.0, 1.0)")) {
            out += "missing the stage-1 clamp (`clamp(v, 0.0, 1.0)`)"
        }
        // Stage 2: the CPU rawedit::sampleShading bilinear, verbatim — the
        // clamped normalized coordinates, the edge-clamped x1/y1, the
        // plane-stacked texelFetch layout the C++ upload builds, and the
        // two-lerp tail. A silent edit of either copy (shader or CPU)
        // surfaces here.
        if (!s.contains(
                "min(max(u, 0.0), 1.0) * (float(uShadingSize.x) - 1.0)"
            )
        ) {
            out += "missing the shading bilinear's clamped fx " +
                "(`min(max(u, 0.0), 1.0) * (float(uShadingSize.x) - 1.0)` — " +
                "rawedit::sampleShading)"
        }
        if (!s.contains("int planeOffset = plane * uShadingSize.y;")) {
            out += "missing the shading plane stacking " +
                "(`int planeOffset = plane * uShadingSize.y;` — the C++ upload " +
                "stacks the 4 planes vertically, texel y = plane*shadingH + row)"
        }
        if (!s.contains("return a + (b - a) * ty;")) {
            out += "missing the shading bilinear tail " +
                "(`return a + (b - a) * ty;` — rawedit::sampleShading)"
        }
        if (!s.contains(
                "v *= sampleShading(float(p.x) * du, float(p.y) * dv, slot);"
            )
        ) {
            out += "missing the stage-2 gain multiply " +
                "(`v *= sampleShading(float(p.x) * du, float(p.y) * dv, slot);` " +
                "— the CPU stage-2 loop, gain on the post-stage-1 value)"
        }
        if (!s.contains("if (uHr != 1) return normAt(p);")) {
            out += "missing the stage-3 gate " +
                "(`if (uHr != 1) return normAt(p);` — grade.highlightReconstruction " +
                "off skips the whole stage, exactly like the CPU)"
        }
        // Stage 3: the CPU highlight-reconstruction cell pass, verbatim —
        // the clipped criterion, the ceiling level, the unclipped passthrough,
        // and the cap.
        if (!s.contains("if (v >= 1.0) clipped[color] = true;")) {
            out += "missing the stage-3 clipped criterion " +
                "(`if (v >= 1.0) clipped[color] = true;` — the CPU's >= 1.0 test)"
        }
        if (!s.contains("float level = minV[c] * uHrGain[c];")) {
            out += "missing the stage-3 ceiling level " +
                "(`float level = minV[c] * uHrGain[c];` — the CPU's minV * gain)"
        }
        if (!s.contains("if (v < 1.0) return v;")) {
            out += "missing the stage-3 unclipped passthrough " +
                "(`if (v < 1.0) return v;` — unclipped sites keep their real level)"
        }
        if (!s.contains("float cap = ceiling / uHrGain[siteAt(p)];")) {
            out += "missing the stage-3 cap " +
                "(`float cap = ceiling / uHrGain[siteAt(p)];` — the CPU's " +
                "ceiling / gain[wbIndex(color)] with wbIndex(site) = site)"
        }
    }

    /**
     * The pointwise stage text (stages 9-20): smoothstepf's Hermite form,
     * the stage gates and math, and the pointwise const discipline. Shared
     * by the fused pass and the standalone DEVELOP_FRAGMENT_POST — the
     * same shared fragments, the same checks.
     */
    private fun checkDevelopPointwiseStageText(s: String, out: MutableList<String>) {
        // The CPU rawedit::smoothstepf helper (core/src/Develop.cpp), which
        // the tone stages (11, 12) and the color stages (18, 19) blend
        // their band remaps in with. A silent edit of the Hermite form
        // surfaces here (the CPU side is pinned by core's own doctests).
        if (!s.contains(
                "float t = min(max((x - e0) / (e1 - e0), 0.0), 1.0);"
            )
        ) {
            out += "missing the smoothstepf clamp " +
                "(`float t = min(max((x - e0) / (e1 - e0), 0.0), 1.0);` — " +
                "rawedit::smoothstepf, the shared band-weight helper)"
        }
        if (!s.contains("return t * t * (3.0 - 2.0 * t);")) {
            out += "missing the smoothstepf Hermite tail " +
                "(`return t * t * (3.0 - 2.0 * t);` — rawedit::smoothstepf)"
        }
        // Stages 9-13: the CPU pointwise grade loop, verbatim (each in its
        // own `!= 0.0` gate — the CPU's own skip; the uniforms carry RAW
        // grade values and the shader computes the same intermediates).
        // The stage-13 local c (contrast/100) is renamed slopeC because
        // the pixel vector is c here — the rename is honest and locked.
        if (!s.contains("if (uBlackPoint != 0.0) {")) {
            out += "missing the stage-9 gate " +
                "(`if (uBlackPoint != 0.0) {` — grade.blackPoint == 0 skips " +
                "the whole stage, exactly like the CPU's own " +
                "`if (grade.blackPoint != 0.0f)`)"
        }
        if (!s.contains("float lift = -0.12 * (uBlackPoint / 100.0);")) {
            out += "missing the stage-9 lift " +
                "(`float lift = -0.12 * (uBlackPoint / 100.0);` — the CPU's " +
                "-0.12 * blackPoint/100 endpoint remap, A6 owner request 2026-11-23)"
        }
        if (!s.contains("c = lift + (1.0 - lift) * c;")) {
            out += "missing the stage-9 remap " +
                "(`c = lift + (1.0 - lift) * c;` — the CPU stage-9 " +
                "lift + (1 - lift) * v form)"
        }
        if (!s.contains("if (uWhitePoint != 0.0) {")) {
            out += "missing the stage-10 gate " +
                "(`if (uWhitePoint != 0.0) {` — grade.whitePoint == 0 skips " +
                "the whole stage, exactly like the CPU)"
        }
        if (!s.contains(
                "float scale = uWhitePoint > 0.0"
            )
        ) {
            out += "missing the stage-10 scale head " +
                "(`float scale = uWhitePoint > 0.0` — the CPU's sign split)"
        }
        if (!s.contains("? 1.0 / (1.0 - 0.25 * (uWhitePoint / 100.0))")) {
            out += "missing the stage-10 positive scale " +
                "(`? 1.0 / (1.0 - 0.25 * (uWhitePoint / 100.0))` — the CPU's " +
                "wp > 0 arm)"
        }
        if (!s.contains(": 1.0 + 0.25 * (uWhitePoint / 100.0);")) {
            out += "missing the stage-10 negative scale " +
                "(`: 1.0 + 0.25 * (uWhitePoint / 100.0);` — the CPU's " +
                "wp <= 0 arm)"
        }
        if (!s.contains("c *= scale;")) {
            out += "missing the stage-10 scale multiply " +
                "(`c *= scale;` — the CPU stage-10 uniform scale)"
        }
        if (!s.contains("if (uShadows != 0.0) {")) {
            out += "missing the stage-11 gate " +
                "(`if (uShadows != 0.0) {` — grade.shadows == 0 skips the " +
                "whole stage, exactly like the CPU)"
        }
        if (!s.contains("float wDark = smoothstepf(0.18, 0.0, mx);")) {
            out += "missing the stage-11 dark-band weight " +
                "(`float wDark = smoothstepf(0.18, 0.0, mx);` — the CPU's " +
                "smoothstepf(0.18, 0, mx), 1 at black)"
        }
        if (!s.contains("float gain = pow(2.0, strength * wDark);")) {
            out += "missing the stage-11 gain " +
                "(`float gain = pow(2.0, strength * wDark);` — the CPU's " +
                "pow(2, strength * wDark))"
        }
        if (!s.contains("if (uMidtones != 0.0) {")) {
            out += "missing the stage-12 gate " +
                "(`if (uMidtones != 0.0) {` — grade.midtones == 0 skips the " +
                "whole stage, exactly like the CPU)"
        }
        if (!s.contains(
                "* (1.0 - smoothstepf(0.18, 0.72, mx));"
            )
        ) {
            out += "missing the stage-12 tent tail " +
                "(`* (1.0 - smoothstepf(0.18, 0.72, mx));` — the CPU's " +
                "(1 - smoothstepf(0.18, 0.72, mx)) falloff)"
        }
        if (!s.contains("float gain = pow(2.0, strength * wMid);")) {
            out += "missing the stage-12 gain " +
                "(`float gain = pow(2.0, strength * wMid);` — the CPU's " +
                "pow(2, strength * wMid))"
        }
        if (!s.contains("if (uContrast != 0.0) {")) {
            out += "missing the stage-13 gate " +
                "(`if (uContrast != 0.0) {` — grade.contrast == 0 skips the " +
                "whole stage, exactly like the CPU)"
        }
        if (!s.contains("float slopeC = uContrast / 100.0;")) {
            out += "missing the stage-13 slope head " +
                "(`float slopeC = uContrast / 100.0;` — the CPU's c = " +
                "contrast/100, renamed slopeC because the pixel vector is c)"
        }
        if (!s.contains("float slope = 1.0 + slopeC;")) {
            out += "missing the stage-13 slope " +
                "(`float slope = 1.0 + slopeC;` — the CPU's slope = 1 + c)"
        }
        if (!s.contains("c = pivot + (c - pivot) * slope;")) {
            out += "missing the stage-13 pivot remap " +
                "(`c = pivot + (c - pivot) * slope;` — the CPU stage-13 " +
                "pivot + (v - pivot) * slope form)"
        }
        // Stage 15: the CPU highlights shoulder, verbatim — the stage-15
        // loop in rawedit::developCpu (core/src/Develop.cpp). The gate,
        // the knee/span constants, the strength SIGN (recovery -> +,
        // lift -> -), the mx > knee test, the monotone shoulder and the
        // uniform gain are all load-bearing; a silent edit of any of
        // them surfaces here (the CPU side is pinned by core's own
        // doctests, the GLSL side by these contract checks + the host
        // parity test).
        if (!s.contains("if (uHighlights != 0.0) {")) {
            out += "missing the stage-15 gate " +
                "(`if (uHighlights != 0.0) {` — grade.highlights == 0 skips " +
                "the whole stage, exactly like the CPU's own " +
                "`if (grade.highlights != 0.0f)`)"
        }
        if (!s.contains("float knee = uHlKnee;")) {
            out += "missing the stage-15 knee " +
                "(`float knee = uHlKnee;` — the CPU stage-15 shoulder start, 0.75 legacy / 0.40 v1)"
        }
        if (!s.contains("float span = 1.0 - knee;")) {
            out += "missing the stage-15 span " +
                "(`float span = 1.0 - knee;` — the CPU's 1 - knee)"
        }
        if (!s.contains("float s = -uHighlights / 100.0;")) {
            out += "missing the stage-15 strength " +
                "(`float s = -uHighlights / 100.0;` — the SIGN is the " +
                "recovery/lift direction; a dropped minus flips recovery " +
                "into lift)"
        }
        if (!s.contains("float mx = max(c.r, max(c.g, c.b));")) {
            out += "missing the stage-15 max " +
                "(`float mx = max(c.r, max(c.g, c.b));` — the CPU's per-pixel " +
                "channel max)"
        }
        if (!s.contains("if (mx > knee) {")) {
            out += "missing the stage-15 knee test " +
                "(`if (mx > knee) {` — the CPU skips pixels with mx <= knee)"
        }
        if (!s.contains("float shoulder = knee + span * (t / (1.0 + t));")) {
            out += "missing the stage-15 monotone shoulder " +
                "(`float shoulder = knee + span * (t / (1.0 + t));` — bounded, " +
                "order-preserving for all t, the CPU's exact form)"
        }
        if (!s.contains("float newMx = mx + s * (shoulder - mx);")) {
            out += "missing the stage-15 blend " +
                "(`float newMx = mx + s * (shoulder - mx);` — original blended " +
                "toward the shoulder by strength, s in [-1, 1])"
        }
        if (!s.contains("float gain = mx > 0.0 ? newMx / mx : 1.0;")) {
            out += "missing the stage-15 gain guard " +
                "(`float gain = mx > 0.0 ? newMx / mx : 1.0;` — the mx==0 " +
                "fallback keeps black at exactly 0)"
        }
        if (!s.contains("c *= gain;")) {
            out += "missing the stage-15 gain multiply " +
                "(`c *= gain;` — the uniform per-pixel gain, neutrals stay " +
                "neutral)"
        }
        // Stages 16-20: the CPU pointwise color grade loop, verbatim. The
        // 16/17 asShot gate is hoisted to the CPU-computed int uWbManual
        // and the 19 split gate to uSplitActive (the GLSL default of an
        // unset int uniform is 0, which must be the exact CPU skip so the
        // frozen device parity harness — which never binds these — stays
        // byte-honest). The uniforms carry RAW grade values; the shader
        // computes the same intermediates the CPU loops compute.
        if (!s.contains("if (uWbManual == 1) {")) {
            out += "missing the stage-16 gate " +
                "(`if (uWbManual == 1) {` — uWbManual carries the " +
                "CPU-computed !grade.asShot; 0 skips both 16 and 17, " +
                "exactly like the CPU's `if (!grade.asShot)`)"
        }
        if (!s.contains(
                "float tempShift = uTempK > 0.0 ? log2(uTempK / 5600.0) : 0.0;"
            )
        ) {
            out += "missing the stage-16 tempShift " +
                "(`float tempShift = uTempK > 0.0 ? log2(uTempK / 5600.0) : ` " +
                "`0.0;` — the CPU's tempK > 0 guard and log2(tempK/5600))"
        }
        if (!s.contains("if (abs(tempShift) > 1e-4) {")) {
            out += "missing the stage-16 threshold " +
                "(`if (abs(tempShift) > 1e-4) {` — the CPU's own " +
                "|tempShift| > 1e-4 skip)"
        }
        if (!s.contains("float warmGain = pow(2.0, 0.35 * tempShift);")) {
            out += "missing the stage-16 warm gain " +
                "(`float warmGain = pow(2.0, 0.35 * tempShift);` — the CPU's " +
                "pow(2, 0.35*tempShift) on red)"
        }
        if (!s.contains("float coolGain = pow(2.0, -0.35 * tempShift);")) {
            out += "missing the stage-16 cool gain " +
                "(`float coolGain = pow(2.0, -0.35 * tempShift);` — the CPU's " +
                "pow(2, -0.35*tempShift) on blue)"
        }
        if (!s.contains("c.g *= sqrt(warmGain * coolGain);")) {
            out += "missing the stage-16 green geometric mean " +
                "(`c.g *= sqrt(warmGain * coolGain);` — the CPU's " +
                "g *= sqrt(warmGain*coolGain), luminance-preserving)"
        }
        if (!s.contains("if (uWbManual == 1 && uTint != 0.0) {")) {
            out += "missing the stage-17 gate " +
                "(`if (uWbManual == 1 && uTint != 0.0) {` — the CPU's " +
                "`!grade.asShot && grade.tint != 0.0f` split across the " +
                "hoisted gate and the raw value)"
        }
        if (!s.contains("float greenGain = pow(2.0, 0.4 * (uTint / 100.0));")) {
            out += "missing the stage-17 green gain " +
                "(`float greenGain = pow(2.0, 0.4 * (uTint / 100.0));` — " +
                "the CPU's pow(2, 0.4*(tint/100)))"
        }
        if (!s.contains(
                "if (uWheelShadowX != 0.0 || uWheelShadowY != 0.0 ||"
            )
        ) {
            out += "missing the stage-18 gate head " +
                "(`if (uWheelShadowX != 0.0 || uWheelShadowY != 0.0 ||` — " +
                "the CPU's wheelActive any-of-six check, one raw value per " +
                "wheel offset)"
        }
        if (!s.contains("float wHigh = smoothstepf(0.18, 0.72, mx);")) {
            out += "missing the stage-18 high-band weight " +
                "(`float wHigh = smoothstepf(0.18, 0.72, mx);` — the CPU's " +
                "high band, completing the exact partition of unity with " +
                "wDark + wMid)"
        }
        if (!s.contains(
                "float xBlend = wDark * uWheelShadowX"
            )
        ) {
            out += "missing the stage-18 xBlend head " +
                "(`float xBlend = wDark * uWheelShadowX` — the CPU's " +
                "wDark*shadowX + wMid*midX + wHigh*highX warm/cool blend)"
        }
        if (!s.contains("c.g *= pow(2.0,  0.5 * yBlend);")) {
            out += "missing the stage-18 green ride " +
                "(`c.g *= pow(2.0,  0.5 * yBlend);` — the CPU's green rides " +
                "the tint axis; this locks the exact per-axis pow(2, 0.5*x) " +
                "form and that it is GREEN that moves with yBlend)"
        }
        if (!s.contains("c.b *= pow(2.0, -0.5 * xBlend);")) {
            out += "missing the stage-18 blue counter " +
                "(`c.b *= pow(2.0, -0.5 * xBlend);` — the CPU's blue moves " +
                "OPPOSITE the warm axis, the -0.5 sign is load-bearing)"
        }
        if (!s.contains("if (uSplitActive == 1) {")) {
            out += "missing the stage-19 gate " +
                "(`if (uSplitActive == 1) {` — uSplitActive carries the " +
                "CPU-computed splitShadowHue >= 0 || splitHighlightHue >= 0; " +
                "the raw -1 = off sentinel cannot live on the hue uniforms " +
                "because 0.0 is a VALID hue and the GLSL default of an unset " +
                "float uniform is 0.0)"
        }
        if (!s.contains("vec3 gains;")) {
            out += "missing the hueGains declaration " +
                "(`vec3 gains;` — the CPU hueGains lambda's gains array, " +
                "renamed honestly (c is the pixel vector))"
        }
        if (!s.contains("gains[ch] = 2.0 * (0.5 + 0.5 * cos(d * 3.14159265 / 180.0));")) {
            out += "missing the hueGains raised-cosine window " +
                "(`gains[ch] = 2.0 * (0.5 + 0.5 * cos(d * 3.14159265 / ` " +
                "`180.0));` — the CPU's 2*(0.5 + 0.5*cos(d*pi/180)), rescaled " +
                "by 2 so the average over the circle is 1)"
        }
        if (!s.contains("float m = 0.18 * pow(2.0, uSplitBalance / 100.0);")) {
            out += "missing the stage-19 balance pivot " +
                "(`float m = 0.18 * pow(2.0, uSplitBalance / 100.0);` — the " +
                "CPU's m = 0.18*pow(2, splitBalance/100) shadow/highlight " +
                "crossover)"
        }
        if (!s.contains("const float tintAmount = 0.35;")) {
            out += "missing the stage-19 tint amount " +
                "(`const float tintAmount = 0.35;` — the CPU's 0.35 stop, " +
                "literal-only so it keeps const)"
        }
        if (!s.contains("float wShadow = smoothstepf(m, 0.0, mx);")) {
            out += "missing the stage-19 shadow band " +
                "(`float wShadow = smoothstepf(m, 0.0, mx);` — the CPU's " +
                "smoothstepf(m, 0, mx), 1 at black, 0 at m)"
        }
        if (!s.contains("float wHighlight = smoothstepf(m, 1.0, mx);")) {
            out += "missing the stage-19 highlight band " +
                "(`float wHighlight = smoothstepf(m, 1.0, mx);` — the CPU's " +
                "smoothstepf(m, 1, mx), 0 at m, 1 at white)"
        }
        if (!s.contains(
                "float gain = pow(\n" +
                    "                2.0, tintAmount * (wShadow * (sh[ch] - 1.0)"
            )
        ) {
            out += "missing the stage-19 per-channel gain " +
                "(`float gain = pow(2.0, tintAmount * (wShadow * (sh[ch] - ` " +
                "`1.0) + wHighlight * (hl[ch] - 1.0)));` — the CPU's " +
                "pow(2, tintAmount*(wShadow*(sh[c]-1) + wHighlight*(hl[c]-1))) " +
                "with the channel index renamed ch)"
        }
        if (!s.contains("if (uSaturation != 0.0) {")) {
            out += "missing the stage-20 gate " +
                "(`if (uSaturation != 0.0) {` — grade.saturation == 0 skips " +
                "the whole stage, exactly like the CPU)"
        }
        if (!s.contains("float f = pow(2.0, 0.75 * (uSaturation / 100.0));")) {
            out += "missing the stage-20 chroma factor " +
                "(`float f = pow(2.0, 0.75 * (uSaturation / 100.0));` — the " +
                "CPU's pow(2, 0.75*(sat/100)))"
        }
        if (!s.contains("float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;")) {
            out += "missing the stage-20 luma " +
                "(`float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;` " +
                "— the CPU's Rec.709 luma weights)"
        }
        if (!s.contains("c = luma + (c - luma) * f;")) {
            out += "missing the stage-20 chroma scaling " +
                "(`c = luma + (c - luma) * f;` — the CPU stage-20 " +
                "luma + (v - luma) * f form)"
        }
        // GLSL ES 3.00 const requires a CONSTANT-expression initializer.
        // The CPU's `const` is read-only, NOT the GLSL meaning — a `const` on
        // a parameter/uniform-derived local is a device compile error. The
        // literal-only declarations (the stage-13 pivot, the stage-19
        // tintAmount, the stage-15 knee/span) keep const; these must not.
        if (s.contains("const float lift") || s.contains("const float scale") ||
            s.contains("const float strength") || s.contains("const float wDark") ||
            s.contains("const float wMid") || s.contains("const float slopeC") ||
            s.contains("const float slope =") || s.contains("const float tempShift") ||
            s.contains("const float warmGain") || s.contains("const float coolGain") ||
            s.contains("const float greenGain") || s.contains("const float xBlend") ||
            s.contains("const float yBlend") || s.contains("const float wHigh") ||
            s.contains("const float m =") || s.contains("const vec3 sh") ||
            s.contains("const vec3 hl") || s.contains("const float wShadow") ||
            s.contains("const float wHighlight") || s.contains("const float f =") ||
            s.contains("const vec3 gains") || s.contains("const float s =") ||
            s.contains("const float mx") || s.contains("const float over") ||
            s.contains("const float t =") || s.contains("const float shoulder") ||
            s.contains("const float newMx") || s.contains("const float gain") ||
            s.contains("const float luma")
        ) {
            out += "a pointwise-stage local uses `const` " +
                "with a non-constant initializer (GLSL ES 3.00 requires " +
                "constant expressions for const; the CPU's read-only const " +
                "does not carry — drop the qualifier, keep the literal-only " +
                "declarations)"
        }
    }

    /**
     * The tail stage text (stages 23 + 24 + OETF): the AgX/ACES tonemap
     * contracts, the LUT block, the const discipline and the 23/24
     * application order. Shared by the fused pass and the standalone
     * DEVELOP_FRAGMENT_TAIL — the same shared fragments, the same checks.
     */
    private fun checkDevelopTailStageText(s: String, out: MutableList<String>) {
        // The OETF constants — rawedit::linearToRec709 (Transfer.cpp). The
        // helper belongs to the tail pass (only the tail's OETF block calls
        // it; the pointwise pass's stages 9-20 never do).
        if (!s.contains("if (lin < 0.018) return 4.5 * lin;")) {
            out += "missing the OETF linear segment " +
                "(`if (lin < 0.018) return 4.5 * lin;` — rawedit::linearToRec709)"
        }
        if (!s.contains("1.099 * pow(lin, 0.45) - 0.099")) {
            out += "missing the OETF power segment " +
                "(`1.099 * pow(lin, 0.45) - 0.099` — rawedit::linearToRec709)"
        }
        // Stage 23: the CPU tonemap math verbatim — rawedit::
        // tonemapFrameImpl (core/src/Develop.cpp). The rational-function
        // products and the cancelling polynomial terms are
        // precision-sensitive; a silent edit of any constant surfaces
        // here (the CPU side is pinned by core's own doctests, the GLSL
        // side by these contract checks + the device parity case 2).
        if (!s.contains("const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;")) {
            out += "missing the ACES fit constants " +
                "(`const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, " +
                "e = 0.14;` — rawedit::acesFit, the Narkowicz 2015 fit)"
        }
        if (!s.contains("float num = x * (a * x + b);")) {
            out += "missing the ACES numerator product " +
                "(`float num = x * (a * x + b);` — rawedit::acesFit; the " +
                "rational-function products are precision-sensitive)"
        }
        if (!s.contains("float den = x * (c * x + d) + e;")) {
            out += "missing the ACES denominator product " +
                "(`float den = x * (c * x + d) + e;` — rawedit::acesFit)"
        }
        if (!s.contains("float y = den > 0.0 ? num / den : 0.0;")) {
            out += "missing the ACES den>0 guard " +
                "(`float y = den > 0.0 ? num / den : 0.0;` — " +
                "rawedit::acesFit keeps the rational function defined at 0)"
        }
        if (!s.contains("return min(max(y, 0.0), 1.0);")) {
            out += "missing the ACES [0,1] clamp " +
                "(`return min(max(y, 0.0), 1.0);` — rawedit::acesFit)"
        }
        if (!s.contains(
                "15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4"
            )
        ) {
            out += "missing the AgX contrast polynomial head " +
                "(`15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4` — " +
                "rawedit::agxContrastApprox, the 6th-order fit)"
        }
        if (!s.contains("- 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;")) {
            out += "missing the AgX contrast polynomial tail " +
                "(`- 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - " +
                "0.00232;` — rawedit::agxContrastApprox)"
        }
        if (!s.contains("if (v <= 0.0) return 0.0;")) {
            out += "missing the AgX log2 guard " +
                "(`if (v <= 0.0) return 0.0;` — rawedit::agxEncode keeps " +
                "log2's argument positive)"
        }
        if (!s.contains("const float minEv = -12.47393;")) {
            out += "missing the AgX EV window floor " +
                "(`const float minEv = -12.47393;` — rawedit::tonemapFrameImpl)"
        }
        if (!s.contains("const float maxEv = 4.026069;")) {
            out += "missing the AgX EV window ceiling " +
                "(`const float maxEv = 4.026069;` — rawedit::tonemapFrameImpl)"
        }
        if (!s.contains("return min(max(agxContrastApprox(t), 0.0), 1.0);")) {
            out += "missing the AgX [0,1] clamp before any pow " +
                "(`return min(max(agxContrastApprox(t), 0.0), 1.0);` — " +
                "the polynomial dips to -0.00232 at t=0 and the Punchy " +
                "pow() must never see a negative base)"
        }
        if (!s.contains("const vec3 kAgxInsetR = vec3(0.856627153315983, 0.0951212405381588, 0.0482516061458583);")) {
            out += "missing the AgX inset row 0 " +
                "(`kAgxInsetR` — rawedit::tonemapFrameImpl inset[0..2])"
        }
        if (!s.contains("const vec3 kAgxInsetG = vec3(0.137318972929847, 0.761241990602591, 0.101439036467562);")) {
            out += "missing the AgX inset row 1 " +
                "(`kAgxInsetG` — rawedit::tonemapFrameImpl inset[3..5])"
        }
        if (!s.contains("const vec3 kAgxInsetB = vec3(0.11189821299995, 0.0767994186031903, 0.811302368396859);")) {
            out += "missing the AgX inset row 2 " +
                "(`kAgxInsetB` — rawedit::tonemapFrameImpl inset[6..8])"
        }
        if (!s.contains("const vec3 kAgxOutsetR = vec3(1.1271005818144366432, -0.1106066430966032116, -0.016493938717834568157);")) {
            out += "missing the AgX outset row 0 " +
                "(`kAgxOutsetR` — rawedit::tonemapFrameImpl outset[0..2])"
        }
        if (!s.contains("const vec3 kAgxOutsetG = vec3(-0.14132976349843826565, 1.1578237022162717623, -0.01649393871783425265);")) {
            out += "missing the AgX outset row 1 " +
                "(`kAgxOutsetG` — rawedit::tonemapFrameImpl outset[3..5])"
        }
        if (!s.contains("const vec3 kAgxOutsetB = vec3(-0.14132976349843824772, -0.11060664309660291788, 1.2519364065950402828);")) {
            out += "missing the AgX outset row 2 " +
                "(`kAgxOutsetB` — rawedit::tonemapFrameImpl outset[6..8])"
        }
        // The look table: slope/power/sat/lumaW per mode, indexed
        // [uTonemap - 2] exactly like the CPU's looks[mode - 2].
        if (!s.contains("vec3(1.0, 0.9, 0.5),")) {
            out += "missing the Golden look slope " +
                "(`vec3(1.0, 0.9, 0.5)` — looks[1].slope, AgX Golden)"
        }
        if (!s.contains("const float kAgxSat[4] = float[4](1.4, 1.3, 0.0, 1.0);")) {
            out += "missing the AgX look saturation table " +
                "(`const float kAgxSat[4] = float[4](1.4, 1.3, 0.0, 1.0);` " +
                "— Punchy 1.4, Golden 1.3, Greyscale 0.0, Base 1.0)"
        }
        if (!s.contains(
                "vec3(0.2589235355689848, 0.6104985346066525, 0.13057792982436284),"
            )
        ) {
            out += "missing the Greyscale BT.2020 luma weights " +
                "(`vec3(0.2589235355689848, 0.6104985346066525, " +
                "0.13057792982436284),` — looks[2].lumaW, the AgX " +
                "Greyscale look's BT.2020 matrix rows verbatim)"
        }
        if (!s.contains("vec3(1.35, 1.35, 1.35),")) {
            out += "missing the Punchy look power " +
                "(`vec3(1.35, 1.35, 1.35),` — looks[0].power, AgX Punchy)"
        }
        // The two evaluation orders — Punchy's legacy order (luma from
        // PRE-power values) vs the ASC CDL order (pow(v*slope, power)
        // FIRST, then luma of the powered values). The CPU distinction
        // is part of value 2 (byte-identical old grades); a swap here
        // would silently change every Punchy render.
        if (!s.contains("if (uTonemap == 2) {")) {
            out += "missing the Punchy legacy-order branch " +
                "(`if (uTonemap == 2) {` — looks[0].punchyLegacyOrder, " +
                "the pre-2025.04.20 iolite form the CPU keeps bit-exactly)"
        }
        val punchyLumaIdx = s.indexOf("float luma = dot(kAgxLumaW[look], v);")
        val punchyPowIdx = s.indexOf("v = luma + kAgxSat[look] * (pow(v, kAgxPower[look]) - luma);")
        if (punchyLumaIdx < 0 || punchyPowIdx < 0 || punchyLumaIdx >= punchyPowIdx) {
            out += "the Punchy legacy order is broken (luma must precede " +
                "the pow — luma from PRE-power sigmoid values, the CPU's " +
                "punchyLegacyOrder branch)"
        }
        if (!s.contains("v = pow(v * kAgxSlope[look], kAgxPower[look]);")) {
            out += "missing the ASC CDL slope+power " +
                "(`v = pow(v * kAgxSlope[look], kAgxPower[look]);` — the " +
                "non-Punchy looks apply slope and power FIRST, then take " +
                "luma of the powered values)"
        }
        val cdlPowIdx = s.indexOf("v = pow(v * kAgxSlope[look], kAgxPower[look]);")
        val cdlLumaIdx = s.indexOf("float luma = dot(kAgxLumaW[look], v);", cdlPowIdx + 1)
        if (cdlPowIdx < 0 || cdlLumaIdx < 0) {
            out += "the ASC CDL order is broken (the luma of the powered " +
                "values must follow the slope+power line)"
        }
        // GLSL ES 3.00 const requires a CONSTANT-expression initializer.
        // The CPU's `const` is read-only, NOT the GLSL meaning — a `const` on
        // a parameter/uniform-derived local is a device compile error. The
        // literal-only declarations (the ACES a/b/c/d/e, minEv/maxEv and the
        // global look tables) keep const; these must not.
        if (s.contains("const float num") || s.contains("const float den") ||
            s.contains("const float y = den") || s.contains("const float x2") ||
            s.contains("const float x4") || s.contains("const int look") ||
            s.contains("const float luma")
        ) {
            out += "a tonemap local uses `const` " +
                "with a non-constant initializer (GLSL ES 3.00 requires " +
                "constant expressions for const; the CPU's read-only const " +
                "does not carry — drop the qualifier, keep the literal-only " +
                "declarations)"
        }
        // The tail: clamp BEFORE the pow-2.2 (NaN guard) and the pow
        // itself — the AgX output encoding the CPU leaves in the buffer
        // (the Rec.709 OETF composes on top on BOTH sides; exactly one
        // OETF each).
        if (!s.contains("o = clamp(o, 0.0, 1.0);")) {
            out += "missing the AgX outset clamp " +
                "(`o = clamp(o, 0.0, 1.0);` — before the pow, the NaN guard)"
        }
        if (!s.contains("return pow(o, vec3(2.2));")) {
            out += "missing the AgX gamma-2.2 tail " +
                "(`return pow(o, vec3(2.2));` — the look's output " +
                "encoding, rawedit::tonemapFrameImpl's last line)"
        }
        // ---- Stage 24: the LUT block (the LUT GPU port) ----
        //
        // The CPU reference is rawedit::applyManagedLutStage +
        // evaluateManagedLutPixel + sampleLutPixel + compileLutBinding
        // (core/src/Color.cpp). The table lives in the session's 3D
        // texture (RGBA32F, one mip, GL_NEAREST, read with texelFetch and
        // blended by hand), so the shader's contract is the domain mapping,
        // the lattice split plus the role/strength order; every line below
        // is load-bearing. Device parity against the CPU lattice: 0 LSB.
        if (!s.contains("return x < 0.0 ? -pow(-x, e) : pow(x, e);")) {
            out += "missing the LUT signed pow " +
                "(`return x < 0.0 ? -pow(-x, e) : pow(x, e);` — " +
                "rawedit::spow: a negative encoded value keeps its sign " +
                "through the gamma curves)"
        }
        if (!s.contains("return log2(x) / 3.321928094887362;")) {
            out += "missing the LUT log10 transcription " +
                "(`return log2(x) / 3.321928094887362;` — GLSL ES has no " +
                "log10; log2(x)/log2(10) stands in for std::log10, its " +
                "~1-ulp difference part of the bounded-error claim)"
        }
        // The texel-center mapping: the hardware trilinear filter must BE
        // the CPU lattice interpolation — the domain normalization, the
        // pre-clamp and the (i + 0.5)/n center are all three required
        // (any one changed and the sampled texel weights differ).
        if (!s.contains("vec3 scale = (n - 1.0) / max(span, vec3(1e-20));")) {
            out += "missing the LUT domain scale " +
                "(`vec3 scale = (n - 1.0) / max(span, vec3(1e-20));` — " +
                "sampleLutPixel's (n-1)/max(domainMax - domainMin, 1e-20), " +
                "componentwise)"
        }
        if (!s.contains(
            "vec3 fx = clamp((enc - uLutDomainMin) * scale, vec3(0.0), vec3(n - 1.0));"
        )) {
            out += "missing the LUT pre-clamp " +
                "(`vec3 fx = clamp((enc - uLutDomainMin) * scale, " +
                "vec3(0.0), vec3(n - 1.0));` — sampleLutPixel's clamp to " +
                "[0, n-1], which also keeps t inside the texel range so " +
                "CLAMP_TO_EDGE never distorts an edge sample)"
        }
        // The lattice read (2026-11-24): the table is a float32 3D texture read with texelFetch and blended
        // by hand, the CPU's own 8-corner trilinear arithmetic (the half-float table + hardware filter it
        // replaced differed by 1 LSB on 3-5% of samples). The floor/fraction split, the index clamp at the
        // table edge, the eight corner fetches and the nested mix are all required.
        if (!s.contains("vec3 f0 = floor(fx);") || !s.contains("vec3 w = fx - f0;")) {
            out += "missing the LUT lattice split " +
                "(`vec3 f0 = floor(fx); vec3 w = fx - f0;` — sampleLutPixel's i0 and fractional weights)"
        }
        if (!s.contains("ivec3 i1 = min(i0 + ivec3(1), hi);")) {
            out += "missing the LUT upper-corner clamp " +
                "(`ivec3 i1 = min(i0 + ivec3(1), hi);` — the last lattice cell reads itself, as the CPU does)"
        }
        if (s.split("texelFetch(uLut, ").size - 1 < 8) {
            out += "missing the eight LUT corner fetches (`texelFetch(uLut, ...)` x8)"
        }
        if (!s.contains("return mix(mix(c00, c10, w.y), mix(c01, c11, w.y), w.z);")) {
            out += "missing the LUT trilinear blend " +
                "(`return mix(mix(c00, c10, w.y), mix(c01, c11, w.y), w.z);` — x, then y, then z)"
        }
        // The managed chain: working -> input gamut -> encode -> sample ->
        // decode -> output gamut (evaluateManagedLutPixel, verbatim).
        if (!s.contains("vec3 gamutIn = uLutWorkingToInput * px;")) {
            out += "missing the LUT input-gamut matrix " +
                "(`vec3 gamutIn = uLutWorkingToInput * px;` — " +
                "compileLutBinding's workingToInput, the chain's head)"
        }
        if (!s.contains("lutEncodeTransfer(gamutIn.x, uLutInTransfer),")) {
            out += "missing the LUT per-channel encode " +
                "(`lutEncodeTransfer(gamutIn.x, uLutInTransfer),` — the " +
                "binding's input transfer, applied per channel)"
        }
        if (!s.contains("return uLutOutputToWorking * dec;")) {
            out += "missing the LUT output-gamut matrix " +
                "(`return uLutOutputToWorking * dec;` — " +
                "compileLutBinding's outputToWorking, the chain's tail)"
        }
        // The role/strength contract (applyManagedLutStage's exact shape):
        // strength 0 skips the whole stage (the CPU's own
        // `if (!(lutStrength > 0.0f))` bypass), SceneLook (role 2) applies
        // the LUT BEFORE tonemap, SceneToDisplay (role 3) at full strength
        // SKIPS tonemap and samples the SCENE pixel, and every other role
        // applies after tonemap — legacy (0) always the plain sampler,
        // DisplayLook (1) the plain sampler only when the binding
        // degenerates to passthrough.
        if (!s.contains("float lutS = min(uLutStrength, 1.0);")) {
            out += "missing the LUT strength clamp " +
                "(`float lutS = min(uLutStrength, 1.0);` — the CPU's own " +
                "min(strength, 1.0f) that gates the SceneToDisplay branches)"
        }
        if (!s.contains("vec3 cScene = c;")) {
            out += "missing the LUT scene-pixel preservation " +
                "(`vec3 cScene = c;` — the pre-tonemap pixel SceneToDisplay's " +
                "LUT samples, the CPU's baseline-copy form)"
        }
        if (!s.contains("if (uLutRole == 2 && uLutStrength != 0.0) {")) {
            out += "missing the SceneLook LUT gate " +
                "(`if (uLutRole == 2 && uLutStrength != 0.0) {` — role 2 " +
                "applies the LUT BEFORE tonemap, the CPU SceneLook order)"
        }
        if (!s.contains(
            "if (!(uLutStrength != 0.0 && uLutRole == 3 && lutS >= 1.0)) {"
        )) {
            out += "missing the SceneToDisplay tonemap skip " +
                "(`if (!(uLutStrength != 0.0 && uLutRole == 3 && lutS >= " +
                "1.0)) {` — role 3 at full strength replaces the pixel " +
                "outright; tonemap must not run first, the CPU's s >= 1 " +
                "branch)"
        }
        if (!s.contains("if (uLutStrength != 0.0 && uLutRole != 2) {")) {
            out += "missing the post-tonemap LUT gate " +
                "(`if (uLutStrength != 0.0 && uLutRole != 2) {` — every " +
                "role but SceneLook applies the LUT AFTER tonemap, before " +
                "the OETF)"
        }
        if (!s.contains("vec3 lutPx = lutManagedPixel(cScene);")) {
            out += "missing the SceneToDisplay managed sample " +
                "(`vec3 lutPx = lutManagedPixel(cScene);` — role 3's LUT " +
                "reads the SCENE pixel while the blend baseline stays " +
                "tonemapped, the CPU's per-pixel two-branch form)"
        }
        if (!s.contains("else if (uLutPassthrough == 1 || uLutRole == 0) {")) {
            out += "missing the LUT plain-sampler branch " +
                "(`else if (uLutPassthrough == 1 || uLutRole == 0) {` — " +
                "legacy (0) is always the plain sampler; DisplayLook (1) " +
                "takes it only when the binding degenerates)"
        }
        if (!s.contains("c = lutPx * lutS + c * (1.0 - lutS);")) {
            out += "missing the LUT strength blend " +
                "(`c = lutPx * lutS + c * (1.0 - lutS);` — the CPU's " +
                "lutPx*s + baseline*(1-s) mix, the strength knob's whole " +
                "effect)"
        }
        // The application point: the tail's own four blocks sit in the
        // CPU's stage order — 24 (LUT, SceneLook) -> 23 (tonemap) -> 24
        // (LUT, every other role) -> OETF (core/src/Develop.cpp
        // developCpu, then the JNI tail's OETF). Any block moved across a
        // neighbor is a different image, not a refactor. Stage 24's
        // role-2 (SceneLook) block sits BEFORE tonemap and the
        // every-other-role block AFTER it — applyManagedLutStage's own
        // per-role order — so both LUT gates are pinned here around
        // tonemap, between the fetch and the OETF. The pointwise gates
        // (blackPoint..saturation) are deliberately NOT part of this
        // check — the standalone TAIL pass never contains the pointwise
        // text (the 20/23 cut lands before it); the fused pass checks the
        // full chain including every pointwise gate in its own
        // full-chain assertion (and rides this check too, where the
        // pointwise blocks precede the tail's and the relative order is
        // implied).
        val tonemapIdx = s.indexOf("if (uTonemap != 0) {")
        val lutSceneIdx = s.indexOf("if (uLutRole == 2 && uLutStrength != 0.0) {")
        val lutPostIdx = s.indexOf("if (uLutStrength != 0.0 && uLutRole != 2) {")
        val oetfIdx = s.indexOf("if (uOetf == 1) {")
        val order = listOf(
            lutSceneIdx, tonemapIdx, lutPostIdx, oetfIdx,
        )
        if (order.any { it < 0 } || order.zipWithNext().any { (a, b) -> a >= b }) {
            out += "the tonemap/LUT application point is wrong " +
                "(the tail blocks must sit in the CPU stage order " +
                "24 (LUT SceneLook) -> 23 (tonemap) -> 24 (LUT, every other " +
                "role) -> OETF, each block between its CPU neighbors — " +
                "developCpu's exact order, with stage 24's SceneLook block " +
                "BEFORE tonemap and every other role's AFTER it, " +
                "applyManagedLutStage's own per-role order)"
        }
    }

    /**
     * DEVELOP_FRAGMENT_PRE's contract: the fused pass's own PRE uniform
     * set + the PRE stage text, PLUS the split-chain boundary honesty —
     * the camera matrix must close the pass (the 6/9 cut), and NO
     * post-stage uniform may appear (this pass must be a complete,
     * self-contained stages 1-6 draw; a leaked post uniform would mean
     * the PRE/POST fragments were cut at the wrong boundary).
     */
    private fun developFragmentPreProblems(s: String, out: MutableList<String>) {
        for (u in kDevelopPreUniformDecls) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ session sets it)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        checkDevelopPreStageText(s, out)
        // The 6/9 cut: the camera matrix is the LAST PRE stage, so the
        // pass must contain it (the main body runs through `c = uCam2rgb
        // * c;`) and must END there — the write-out close follows the
        // PRE fragments' own tail.
        if (!s.contains("c = uCam2rgb * c;")) {
            out += "missing the stage-6 camera matrix close " +
                "(`c = uCam2rgb * c;` — the 6/9 cut: the camera matrix is the " +
                "LAST PRE stage, so the PRE pass's main body must run through it)"
        }
        // The boundary honesty: NO post-stage uniform may appear in the
        // PRE pass. A leaked declaration would mean the cut landed inside
        // the pointwise chain, which would double-apply those stages
        // (once here, once in POST).
        for (u in kDevelopPointwiseUniformDecls + kDevelopTailUniformDecls) {
            if (s.contains(u)) {
                out += "a POST-stage uniform leaked into the PRE pass " +
                    "(`$u` — the 6/9 cut must land between stages 6 and 9; " +
                    "a pointwise uniform in PRE means those stages run TWICE)"
            }
        }
    }

    /**
     * DEVELOP_FRAGMENT_POST's contract: uSrc (the chain intermediate) +
     * the pointwise uniform set (stages 9-20) + the pointwise stage
     * text, PLUS the boundary honesty — NO pre-stage uniform, NO
     * tail-stage uniform and no demosaic text may appear (this pass
     * must be a complete, self-contained stages 9-20 pointwise draw
     * whose ONLY input is the intermediate texel; the stages 23+24+OETF
     * tail is the TAIL pass's, a separate draw).
     */
    private fun developFragmentPostProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the chain's RGBA32F intermediate — the PRE pass's " +
                "output, or the NR passes' when the grade uses spatial NR)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it — " +
                "the intermediate's dims, same as the CFA's)"
        }
        for (u in kDevelopPointwiseUniformDecls) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ session sets it)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        // The 1:1 input read: POST's pixel comes from the intermediate,
        // not from any RAW state.
        if (!s.contains("vec3 c = texelFetch(uSrc, g, 0).rgb;")) {
            out += "missing the intermediate fetch " +
                "(`vec3 c = texelFetch(uSrc, g, 0).rgb;` — POST's ONLY input; " +
                "the demosaic/WB/matrix work is the PRE pass's, already " +
                "baked into the intermediate)"
        }
        checkDevelopPointwiseStageText(s, out)
        // The boundary honesty: NO pre-stage uniform may appear in the
        // POST pass. A leaked declaration would mean the cut landed
        // inside the stages 1-6 chain (or the PRE fragments were pasted
        // whole), which would re-run the demosaic on the intermediate.
        // uSize is the one honest exception: POST declares its OWN uSize
        // for the intermediate's dims (the same value the PRE pass's CFA
        // dims carry), so the shared name is excluded from the leak list.
        for (u in kDevelopPreUniformDecls.filter { it != "uniform ivec2 uSize;" }) {
            if (s.contains(u)) {
                out += "a PRE-stage uniform leaked into the POST pass " +
                    "(`$u` — the 6/9 cut must land between stages 6 and 9; " +
                    "a stages 1-6 uniform in POST means the demosaic would " +
                    "re-run on the intermediate)"
            }
        }
        // And NO tail-stage uniform either: the 20/23 cut must land
        // between the stage-20 saturation close and the stage-23 tonemap.
        // A leaked tail uniform would mean the pointwise pass also runs
        // the tonemap/LUT/OETF work, which would double-apply the tail
        // (once here, once in the TAIL pass).
        for (u in kDevelopTailUniformDecls) {
            if (s.contains(u)) {
                out += "a TAIL-stage uniform leaked into the POST pass " +
                    "(`$u` — the 20/23 cut must land between the stage-20 " +
                    "saturation close and the stage-23 tonemap; a tail " +
                    "uniform in POST means the tail stages run TWICE)"
            }
        }
        // And no demosaic text: the kernel arrays exist only in the PRE
        // fragments.
        if (s.contains("int[8](-1, 1, 0, 0, -1, -1, 1, 1)")) {
            out += "the demosaic kernel leaked into the POST pass " +
                "(the kDx offsets are PRE-text; POST's input is the " +
                "intermediate texel, not the CFA)"
        }
    }

    /**
     * DEVELOP_FRAGMENT_TAIL's contract: uSrc (the chain intermediate —
     * the pointwise pass's output, or the detail pass's when the grade
     * uses sharpness/detail) + the tail uniform set (stages 23+24+OETF)
     * + the tail stage text, PLUS the boundary honesty — NO pre-stage
     * uniform, NO pointwise uniform and no demosaic text may appear
     * (this pass must be a complete, self-contained stages 23+24+OETF
     * draw whose ONLY input is the intermediate texel).
     */
    private fun developFragmentTailProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the chain's RGBA32F intermediate — the pointwise " +
                "pass's output, or the unsharp passes' when the grade uses " +
                "sharpness/detail)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it — " +
                "the intermediate's dims, same as the CFA's)"
        }
        for (u in kDevelopTailUniformDecls) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ session sets it)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        // The 1:1 input read: TAIL's pixel comes from the intermediate,
        // not from any RAW state.
        if (!s.contains("vec3 c = texelFetch(uSrc, g, 0).rgb;")) {
            out += "missing the intermediate fetch " +
                "(`vec3 c = texelFetch(uSrc, g, 0).rgb;` — TAIL's ONLY input; " +
                "the stages 9-20 pointwise work is the POST pass's, already " +
                "baked into the intermediate)"
        }
        checkDevelopTailStageText(s, out)
        // The boundary honesty: NO pre-stage uniform may appear in the
        // TAIL pass. A leaked declaration would mean the cut landed
        // inside the stages 1-6 chain (or the PRE fragments were pasted
        // whole), which would re-run the demosaic on the intermediate.
        // uSize is the one honest exception, same as the POST pass's.
        for (u in kDevelopPreUniformDecls.filter { it != "uniform ivec2 uSize;" }) {
            if (s.contains(u)) {
                out += "a PRE-stage uniform leaked into the TAIL pass " +
                    "(`$u` — the tail is a stages 23+24+OETF draw; a " +
                    "stages 1-6 uniform in TAIL means the demosaic would " +
                    "re-run on the intermediate)"
            }
        }
        // And NO pointwise-stage uniform either: the 20/23 cut must land
        // between the stage-20 saturation close and the stage-23 tonemap.
        // A leaked pointwise uniform would mean the tail pass also runs
        // the stages 9-20 chain, which would double-apply the pointwise
        // work (once in POST, once here).
        for (u in kDevelopPointwiseUniformDecls) {
            if (s.contains(u)) {
                out += "a POINTWISE-stage uniform leaked into the TAIL pass " +
                    "(`$u` — the 20/23 cut must land between the stage-20 " +
                    "saturation close and the stage-23 tonemap; a pointwise " +
                    "uniform in TAIL means the stages 9-20 run TWICE)"
            }
        }
        // And no demosaic text: the kernel arrays exist only in the PRE
        // fragments.
        if (s.contains("int[8](-1, 1, 0, 0, -1, -1, 1, 1)")) {
            out += "the demosaic kernel leaked into the TAIL pass " +
                "(the kDx offsets are PRE-text; TAIL's input is the " +
                "intermediate texel, not the CFA)"
        }
    }

    /**
     * NR_CHROMA_H's contract — the CPU denoiseChromaImpl horizontal pass
     * (core/src/Develop.cpp), verbatim: the uniforms, the gate idiom, the
     * constants, the clamped columns, the exact tap order and the
     * center-relative accumulation, the two-plane output and the
     * pass-through.
     */
    private fun nrChromaHProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the develop PRE pass's output — the ORIGINAL " +
                "post-demosaic RGB, this pass's CPU-input position)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uChromaDenoise;")) {
            out += "missing `uniform float uChromaDenoise;` (the session " +
                "passes the RAW grade value; the gate + clamp idiom below " +
                "is the second lock)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uChromaDenoise != 0.0) {")) {
            out += "missing the stage-7 gate " +
                "(`if (uChromaDenoise != 0.0) {` — grade.chromaDenoise == 0 " +
                "skips the whole stage, exactly like the CPU's own " +
                "`if (grade.chromaDenoise != 0.0f)`)"
        }
        if (!s.contains("float strength = clamp(uChromaDenoise, 0.0, 100.0) / 100.0;")) {
            out += "missing the stage-7 strength clamp " +
                "(`float strength = clamp(uChromaDenoise, 0.0, 100.0) / ` " +
                "`100.0;` — the CPU call site's min(max(raw, 0), 100)/100)"
        }
        // The CPU's own constants, verbatim.
        if (!s.contains("const float kRadius = 2.0;") ||
            !s.contains("const float kC0 = exp(-0.5 / (kRadius * kRadius));") ||
            !s.contains("const float kC1 = exp(-2.0 / (kRadius * kRadius));") ||
            !s.contains("const float kSr = 0.10;") ||
            !s.contains("const float kInv2Sr2 = 1.0 / (2.0 * kSr * kSr);")
        ) {
            out += "missing the CPU's chroma-NR constants " +
                "(kRadius 2.0 / kC0 = exp(-0.5/r^2) / kC1 = exp(-2/r^2) / " +
                "kSr 0.10 / kInv2Sr2 — denoiseChromaImpl's own const block, " +
                "verbatim; exp() of constant arguments is a constant " +
                "expression in ES 3.00, so they keep const)"
        }
        // The clamped columns — texelFetch does NOT clamp, so the max/min
        // guards are load-bearing.
        if (!s.contains("int xm = max(g.x - 2, 0);") ||
            !s.contains("int xp = min(g.x + 2, uSize.x - 1);") ||
            !s.contains("int xm2 = max(g.x - 4, 0);") ||
            !s.contains("int xp2 = min(g.x + 4, uSize.x - 1);")
        ) {
            out += "missing the horizontal pass's clamped columns " +
                "(`int xm = max(g.x - 2, 0);` … `int xp2 = min(g.x + 4, ` " +
                "`uSize.x - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards are load-bearing and carried verbatim)"
        }
        // The exact tap order xm, xp, xm2, xp2 — the accumulation order is
        // part of the math.
        val xmTapIdx = s.indexOf("ivec2 p = ivec2(xm, g.y);")
        val xpTapIdx = s.indexOf("ivec2 p = ivec2(xp, g.y);")
        val xm2TapIdx = s.indexOf("ivec2 p = ivec2(xm2, g.y);")
        val xp2TapIdx = s.indexOf("ivec2 p = ivec2(xp2, g.y);")
        if (listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx).any { it < 0 } ||
            xmTapIdx >= xpTapIdx || xpTapIdx >= xm2TapIdx || xm2TapIdx >= xp2TapIdx
        ) {
            out += "the horizontal tap order is wrong " +
                "(xm, xp, xm2, xp2 in that exact order — denoiseChromaImpl's " +
                "own tap(xm,c0); tap(xp,c0); tap(xm2,c1); tap(xp2,c1); " +
                "sequence; the accumulation order is part of the math)"
        }
        // The tap-weight wiring: xm/xp ride kC0 and xm2/xp2 ride kC1 — the
        // CPU's own tap(xm,c0); tap(xp,c0); tap(xm2,c1); tap(xp2,c1)
        // pairing. A wiring swap keeps every other check green (the
        // constants exist, the coordinates stay in order, the accumulation
        // line text still appears) while changing the blur of every
        // pixel, so the check binds each weight to its tap block
        // directly. Skipped when the order check already fired (the
        // block boundaries are only meaningful on an ordered chain).
        val hTaps = listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx)
        if (hTaps.first() >= 0 && hTaps.zipWithNext().all { (a, b) -> a < b }) {
            val hWeights = listOf("kC0", "kC0", "kC1", "kC1")
            for (k in hTaps.indices) {
                val end = if (k + 1 < hTaps.size) hTaps[k + 1] else s.length
                if (!s.substring(hTaps[k], end).contains("* ${hWeights[k]} * rw;")) {
                    out += "the horizontal tap weights are miswired " +
                        "(xm and xp ride kC0, xm2 and xp2 ride kC1 — the " +
                        "CPU's own tap(xm,c0); tap(xp,c0); tap(xm2,c1); " +
                        "tap(xp2,c1) pairing; a swapped weight changes the " +
                        "blur of every pixel)"
                    break
                }
            }
        }
        // The center-relative accumulation: the CPU's tap adds
        // (row[xx] - row[x]) * c * rw — the neighbor's chroma delta
        // against the CENTER, with the center's own weight in sw's 1.0.
        if (!s.contains("sCr += (t.r - ly - cr) * kC0 * rw;") ||
            !s.contains("sCb += (t.b - ly - cb) * kC0 * rw;")
        ) {
            out += "missing the horizontal tap's center-relative chroma " +
                "accumulation (`sCr += (t.r - ly - cr) * kC0 * rw;` — the " +
                "CPU's (row[xx] - row[x]) * c * rw with row[x] = the " +
                "center's own chroma: t.r - ly is the neighbor's cr, minus " +
                "the center's cr; both planes in one pass)"
        }
        if (!s.contains("float tmpCr = cr + sCr / sw;") ||
            !s.contains("float tmpCb = cb + sCb / sw;")
        ) {
            out += "missing the horizontal output " +
                "(`float tmpCr = cr + sCr / sw;` / `float tmpCb = cb + ` " +
                "`sCb / sw;` — the CPU's tmp[y*w+x] = row[x] + s/sw, both " +
                "planes; the vertical pass reads these back from .xy)"
        }
        if (!s.contains("oCol = vec4(tmpCr, tmpCb, 0.0, 1.0);")) {
            out += "missing the horizontal pass's two-plane output " +
                "(`oCol = vec4(tmpCr, tmpCb, 0.0, 1.0);` — .xy carries the " +
                "blurred planes, the CPU's tmp buffers; .zw unused)"
        }
        if (!s.contains("oCol = vec4(orig, 1.0);")) {
            out += "missing the gate-closed pass-through " +
                "(`oCol = vec4(orig, 1.0);` — a drawn pass with a closed " +
                "gate writes the input through unchanged)"
        }
    }

    /**
     * NR_CHROMA_V's contract — the CPU denoiseChromaImpl vertical pass +
     * blend (core/src/Develop.cpp), verbatim: the two inputs (uSrc the
     * horizontal output, uGuide the ORIGINAL pre-NR pixels), the gate,
     * the clamped rows, the exact tap order, the ORIGINAL-luma guide, the
     * flat-chroma skip and the green reconstruction.
     */
    private fun nrChromaVProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches NR_CHROMA_H's output — .xy = the horizontally " +
                "blurred cr/cb planes, the CPU's tmp buffers)"
        }
        if (!s.contains("uniform highp sampler2D uGuide;")) {
            out += "missing `uniform highp sampler2D uGuide;` (the session " +
                "attaches the develop PRE pass's output — the ORIGINAL " +
                "pre-NR RGB; the CPU's vertical pass guides with the " +
                "ORIGINAL luma and the blend needs the original chroma, so " +
                "this texture is load-bearing, not a convenience)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uChromaDenoise;")) {
            out += "missing `uniform float uChromaDenoise;` (the session " +
                "passes the RAW grade value; both passes of one CPU stage " +
                "share one grade control)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uChromaDenoise != 0.0) {")) {
            out += "missing the stage-7 gate " +
                "(`if (uChromaDenoise != 0.0) {` — grade.chromaDenoise == 0 " +
                "skips the whole stage, exactly like the CPU's own gate)"
        }
        if (!s.contains("float strength = clamp(uChromaDenoise, 0.0, 100.0) / 100.0;")) {
            out += "missing the stage-7 strength clamp " +
                "(`float strength = clamp(uChromaDenoise, 0.0, 100.0) / ` " +
                "`100.0;` — the CPU call site's min(max(raw, 0), 100)/100)"
        }
        // The ORIGINAL-luma guide: lumaAt must read uGuide, not uSrc —
        // the CPU's second pass keeps the original luma guide.
        if (!s.contains("vec3 c = texelFetch(uGuide, p, 0).rgb;")) {
            out += "missing the ORIGINAL-luma guide read " +
                "(`vec3 c = texelFetch(uGuide, p, 0).rgb;` in lumaAt — the " +
                "CPU's vertical pass guides with the ORIGINAL luma, NOT " +
                "the horizontal output; guiding from uSrc would change " +
                "every pixel's range weights)"
        }
        // The clamped rows.
        if (!s.contains("int ym = max(g.y - 2, 0);") ||
            !s.contains("int yp = min(g.y + 2, uSize.y - 1);") ||
            !s.contains("int ym2 = max(g.y - 4, 0);") ||
            !s.contains("int yp2 = min(g.y + 4, uSize.y - 1);")
        ) {
            out += "missing the vertical pass's clamped rows " +
                "(`int ym = max(g.y - 2, 0);` … `int yp2 = min(g.y + 4, ` " +
                "`uSize.y - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards are load-bearing and carried verbatim)"
        }
        // The exact tap order ym, yp, ym2, yp2.
        val ymTapIdx = s.indexOf("ivec2 p = ivec2(g.x, ym);")
        val ypTapIdx = s.indexOf("ivec2 p = ivec2(g.x, yp);")
        val ym2TapIdx = s.indexOf("ivec2 p = ivec2(g.x, ym2);")
        val yp2TapIdx = s.indexOf("ivec2 p = ivec2(g.x, yp2);")
        if (listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx).any { it < 0 } ||
            ymTapIdx >= ypTapIdx || ypTapIdx >= ym2TapIdx || ym2TapIdx >= yp2TapIdx
        ) {
            out += "the vertical tap order is wrong " +
                "(ym, yp, ym2, yp2 in that exact order — denoiseChromaImpl's " +
                "own tap(ym,c0); tap(yp,c0); tap(ym2,c1); tap(yp2,c1); " +
                "sequence)"
        }
        // The tap-weight wiring: ym/yp ride kC0 and ym2/yp2 ride kC1 —
        // the CPU's own tap(ym,c0); tap(yp,c0); tap(ym2,c1); tap(yp2,c1)
        // pairing. Position-bound for the same reason as the horizontal
        // pass: a wiring swap keeps the counts and the order green while
        // changing the blur of every pixel.
        val vTaps = listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx)
        if (vTaps.first() >= 0 && vTaps.zipWithNext().all { (a, b) -> a < b }) {
            val vWeights = listOf("kC0", "kC0", "kC1", "kC1")
            for (k in vTaps.indices) {
                val end = if (k + 1 < vTaps.size) vTaps[k + 1] else s.length
                if (!s.substring(vTaps[k], end).contains("* ${vWeights[k]} * rw;")) {
                    out += "the vertical tap weights are miswired " +
                        "(ym and yp ride kC0, ym2 and yp2 ride kC1 — the " +
                        "CPU's own tap(ym,c0); tap(yp,c0); tap(ym2,c1); " +
                        "tap(yp2,c1) pairing; a swapped weight changes the " +
                        "blur of every pixel)"
                    break
                }
            }
        }
        // The center fetch: the CPU's `v = tmp[y*w+x]` — the blurred
        // value the vertical pass centers on.
        if (!s.contains("vec2 v = texelFetch(uSrc, g, 0).xy;")) {
            out += "missing the vertical pass's center fetch " +
                "(`vec2 v = texelFetch(uSrc, g, 0).xy;` — the CPU's " +
                "v = tmp[y*w+x]: the HORIZONTALLY blurred center, not the " +
                "original pixel; values from uSrc, range guide from uGuide)"
        }
        // The blurred output.
        if (!s.contains("float bcr = v.x + sCr / sw;") ||
            !s.contains("float bcb = v.y + sCb / sw;")
        ) {
            out += "missing the vertical output " +
                "(`float bcr = v.x + sCr / sw;` / `float bcb = v.y + sCb / ` " +
                "`sw;` — the CPU's out[y*w+x] = v + s/sw, both planes)"
        }
        // The blend: the flat-chroma skip keeps the ORIGINAL bytes (the
        // CPU `continue`s past the write).
        if (!s.contains("float dr = strength * (bcr - cr);") ||
            !s.contains("float db = strength * (bcb - cb);")
        ) {
            out += "missing the blend delta " +
                "(`float dr = strength * (bcr - cr);` / `float db = ` " +
                "`strength * (bcb - cb);` — the CPU's strength-scaled " +
                "chroma delta)"
        }
        if (!s.contains("if (dr == 0.0 && db == 0.0) {") ||
            !s.contains("oCol = vec4(orig, 1.0);")
        ) {
            out += "missing the flat-chroma skip " +
                "(`if (dr == 0.0 && db == 0.0) { oCol = vec4(orig, 1.0); ` " +
                "`... }` — the CPU `continue`s past the write, keeping the " +
                "original bytes; the pass writes the ORIGINAL texel, the " +
                "same bytes)"
        }
        // The green reconstruction + the kept-luma write.
        if (!s.contains("float newCg = -(0.2126 * newCr + 0.0722 * newCb) / 0.7152;") ||
            !s.contains("oCol = vec4(y + newCr, y + newCg, y + newCb, 1.0);")
        ) {
            out += "missing the blend write " +
                "(`float newCg = -(0.2126 * newCr + 0.0722 * newCb) / ` " +
                "`0.7152;` + `oCol = vec4(y + newCr, y + newCg, y + newCb, ` " +
                "`1.0);` — the CPU's green reconstruction from the kept luma " +
                "and the y+chroma write)"
        }
    }

    /**
     * NR_LUMA's contract — the CPU denoiseLumaImpl (core/src/Develop.cpp),
     * verbatim: the uniforms, the gate, the constants, the 3x3 clamped
     * coordinates, the exact 8-tap order and the smoothed-delta tail.
     */
    private fun nrLumaProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the chain's current RGBA32F input — the chroma-NR " +
                "output when stage 7 ran, else the PRE pass's output; the " +
                "CPU runs luma NR on the chroma-denoised image)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uNoiseReduction;")) {
            out += "missing `uniform float uNoiseReduction;` (the session " +
                "passes the RAW grade value; the gate + clamp idiom below " +
                "is the second lock)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uNoiseReduction != 0.0) {")) {
            out += "missing the stage-8 gate " +
                "(`if (uNoiseReduction != 0.0) {` — grade.noiseReduction == " +
                "0 skips the whole stage, exactly like the CPU's own " +
                "`if (grade.noiseReduction != 0.0f)`)"
        }
        if (!s.contains("float strength = clamp(uNoiseReduction, 0.0, 100.0) / 100.0;")) {
            out += "missing the stage-8 strength clamp " +
                "(`float strength = clamp(uNoiseReduction, 0.0, 100.0) / ` " +
                "`100.0;` — the CPU call site's min(max(raw, 0), 100)/100)"
        }
        // The CPU's own constants, verbatim.
        if (!s.contains("const float kSr = 0.05;") ||
            !s.contains("const float kInv2Sr2 = 1.0 / (2.0 * kSr * kSr);") ||
            !s.contains("const float kWe = exp(-0.5);") ||
            !s.contains("const float kWd = exp(-1.0);")
        ) {
            out += "missing the CPU's luma-NR constants " +
                "(kSr 0.05 / kInv2Sr2 / kWe = exp(-0.5) / kWd = exp(-1.0) " +
                "— denoiseLumaImpl's own const block, verbatim)"
        }
        // The 3x3 clamped coordinates.
        if (!s.contains("int xm = max(g.x - 1, 0);") ||
            !s.contains("int xp = min(g.x + 1, uSize.x - 1);") ||
            !s.contains("int ym = max(g.y - 1, 0);") ||
            !s.contains("int yp = min(g.y + 1, uSize.y - 1);")
        ) {
            out += "missing the 3x3 clamped coordinates " +
                "(`int xm = max(g.x - 1, 0);` … `int yp = min(g.y + 1, ` " +
                "`uSize.y - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards are load-bearing and carried verbatim)"
        }
        // The exact 8-tap order — the CPU's own call order, with the edge
        // taps on wd and the center-row/column taps on we.
        val tapIdx = listOf(
            "ivec2 p = ivec2(xm, ym);",   // tap(ym, xm, wd)
            "ivec2 p = ivec2(g.x, ym);",  // tap(ym, x, we)
            "ivec2 p = ivec2(xp, ym);",   // tap(ym, xp, wd)
            "ivec2 p = ivec2(xm, g.y);",  // tap(y, xm, we)
            "ivec2 p = ivec2(xp, g.y);",  // tap(y, xp, we)
            "ivec2 p = ivec2(xm, yp);",   // tap(yp, xm, wd)
            "ivec2 p = ivec2(g.x, yp);",  // tap(yp, x, we)
            "ivec2 p = ivec2(xp, yp);",   // tap(yp, xp, wd)
        ).map { s.indexOf(it) }
        if (tapIdx.any { it < 0 } || tapIdx.zipWithNext().any { (a, b) -> a >= b }) {
            out += "the luma tap order is wrong " +
                "((ym,xm,wd), (ym,x,we), (ym,xp,wd), (y,xm,we), (y,xp,we), " +
                "(yp,xm,wd), (yp,x,we), (yp,xp,wd) in that exact order — " +
                "denoiseLumaImpl's own call order; the accumulation order is " +
                "part of the math)"
        }
        // The spatial weights, position-bound: the edge taps ride wd and
        // the center-row/column taps ride we, EACH AT ITS OWN TAP — the
        // CPU's own tap(ym,xm,wd); tap(ym,x,we); … call order. The old
        // count check (4 wd / 4 we) was position-blind: swapping wd and
        // we between two taps kept both counts green while inverting the
        // bilateral's edge/center weighting, so the check now binds each
        // weight to its tap block in the chain instead of counting.
        if (tapIdx.first() >= 0 && tapIdx.zipWithNext().all { (a, b) -> a < b }) {
            val lumaWeights = listOf(
                "kWd",  // (ym, xm) edge
                "kWe",  // (ym, x) center column
                "kWd",  // (ym, xp) edge
                "kWe",  // (y, xm) center row
                "kWe",  // (y, xp) center row
                "kWd",  // (yp, xm) edge
                "kWe",  // (yp, x) center column
                "kWd",  // (yp, xp) edge
            )
            for (k in tapIdx.indices) {
                val end = if (k + 1 < tapIdx.size) tapIdx[k + 1] else s.length
                if (!s.substring(tapIdx[k], end).contains("* ${lumaWeights[k]} * rw;")) {
                    out += "the spatial weight split is wrong " +
                        "(the edge taps (ym,xm), (ym,xp), (yp,xm), (yp,xp) " +
                        "ride kWd and the center-row/column taps ride kWe, " +
                        "each at its own tap in the chain — the CPU's own " +
                        "wd/we call order; a swap changes every pixel)"
                    break
                }
            }
        }
        // The tail: the smoothed luma and the equally-lifted RGB.
        if (!s.contains("float smoothV = c + s / sw;") ||
            !s.contains("float d = strength * (smoothV - c);") ||
            !s.contains("oCol = vec4(orig + vec3(d), 1.0);")
        ) {
            out += "missing the luma-NR tail " +
                "(`float smoothV = c + s / sw;` + `float d = strength * ` " +
                "`(smoothV - c);` + `oCol = vec4(orig + vec3(d), 1.0);` — " +
                "the CPU's smooth = c + s/sw then the strength-scaled delta " +
                "added to r, g, b equally)"
        }
        if (!s.contains("oCol = vec4(orig, 1.0);")) {
            out += "missing the gate-closed pass-through " +
                "(`oCol = vec4(orig, 1.0);` — a drawn pass with a closed " +
                "gate writes the input through unchanged)"
        }
    }

    /**
     * SHARPNESS_H's contract — the CPU unsharpLumaImpl horizontal blur run
     * at radius 1.5 (core/src/Develop.cpp), verbatim: the uniforms, the
     * gate idiom, the amount mapping (NEGATIVES valid — no [0,100] clamp,
     * unlike the NR passes), the constants, the luma helper, the clamped
     * columns at step 1, the exact tap order xm, xp, xm2, xp2, the
     * norm-divided .x output and the gate-closed original-luma fallback.
     */
    private fun sharpnessHProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the pointwise POST pass's output — this stage's " +
                "CPU-input position: after the pointwise stages, before " +
                "stage 22)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uSharpness;")) {
            out += "missing `uniform float uSharpness;` (the session passes " +
                "the RAW grade value; the gate + amount idiom below is the " +
                "second lock)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uSharpness != 0.0) {")) {
            out += "missing the stage-21 gate " +
                "(`if (uSharpness != 0.0) {` — grade.sharpness == 0 skips " +
                "the whole stage, exactly like the CPU call site's own " +
                "`if (grade.sharpness != 0.0f || grade.detail != 0.0f)` " +
                "outer gate's sharpness arm)"
        }
        if (!s.contains("float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, 0.5);")) {
            out += "missing the stage-21 amount mapping " +
                "(`float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, ` " +
                "`0.5);` — the CPU call site's own min(max(0.5*(raw/100), " +
                "-0.5), 0.5); NEGATIVE values are valid (softening), so " +
                "there is NO [0,100] clamp and NO strength sub-gate, unlike " +
                "the NR passes)"
        }
        // The CPU's own constants, verbatim (radius 1.5 — step = 1 for both
        // directions, the CPU's `radius < 1.0f ? 1.0f : floor(radius)`
        // resolving to floor(1.5); exp() of constant arguments is a
        // constant expression in ES 3.00, so they keep const).
        if (!s.contains("const float kRadius = 1.5;") ||
            !s.contains("const float kC0 = exp(-0.5 / (kRadius * kRadius));") ||
            !s.contains("const float kC1 = exp(-2.0 / (kRadius * kRadius));") ||
            !s.contains("const float kNorm = 1.0 + 2.0 * (kC0 + kC1);")
        ) {
            out += "missing the CPU's sharpness constants " +
                "(kRadius 1.5 / kC0 = exp(-0.5/r^2) / kC1 = exp(-2/r^2) / " +
                "kNorm = 1 + 2*(c0+c1) — unsharpLumaImpl's own const block " +
                "at radius 1.5, verbatim)"
        }
        // The luma split — the plane the CPU computes once per pixel
        // (recomputed per read: identical math, identical answers).
        if (!s.contains("vec3 c = texelFetch(uSrc, p, 0).rgb;") ||
            !s.contains("return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;")
        ) {
            out += "missing the luma helper " +
                "(`float lumaAt(ivec2 p)` reading uSrc with the CPU's own " +
                "Rec.709 weights 0.2126/0.7152/0.0722 — unsharpLumaImpl's " +
                "luma[i] split, verbatim)"
        }
        // The clamped columns at step 1 — texelFetch does NOT clamp, so
        // the max/min guards are load-bearing (the CPU's own
        // max(x - step, 0) / min(x + step, w - 1) at step = 1).
        if (!s.contains("int xm = max(g.x - 1, 0);") ||
            !s.contains("int xp = min(g.x + 1, uSize.x - 1);") ||
            !s.contains("int xm2 = max(g.x - 2, 0);") ||
            !s.contains("int xp2 = min(g.x + 2, uSize.x - 1);")
        ) {
            out += "missing the horizontal pass's clamped columns " +
                "(`int xm = max(g.x - 1, 0);` … `int xp2 = min(g.x + 2, ` " +
                "`uSize.x - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards at step 1 (radius 1.5 -> floor(1.5)) are " +
                "load-bearing and carried verbatim)"
        }
        // The exact tap order xm, xp, xm2, xp2 — the accumulation order is
        // part of the math (unsharpLumaImpl's own tap sequence).
        val xmTapIdx = s.indexOf("lumaAt(ivec2(xm, g.y))")
        val xpTapIdx = s.indexOf("lumaAt(ivec2(xp, g.y))")
        val xm2TapIdx = s.indexOf("lumaAt(ivec2(xm2, g.y))")
        val xp2TapIdx = s.indexOf("lumaAt(ivec2(xp2, g.y))")
        if (listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx).any { it < 0 } ||
            xmTapIdx >= xpTapIdx || xpTapIdx >= xm2TapIdx || xm2TapIdx >= xp2TapIdx
        ) {
            out += "the horizontal tap order is wrong " +
                "(xm, xp, xm2, xp2 in that exact order — unsharpLumaImpl's " +
                "own (row[xm]+row[xp])*c0 + (row[xm2]+row[xp2])*c1 " +
                "sequence; the accumulation order is part of the math)"
        }
        // The tap-weight wiring: the xm/xp pair rides kC0 and the xm2/xp2
        // pair kC1 — the CPU's own c0/c1 pairing, position-bound for the
        // same reason as the NR passes (a wiring swap keeps every other
        // check green while changing the blur of every pixel). The
        // accumulation is ONE expression — the weight follows the pair's
        // SECOND tap — so the check is pair-segment based, not per-tap.
        // Skipped when the order check already fired.
        val hTaps = listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx)
        if (hTaps.first() >= 0 && hTaps.zipWithNext().all { (a, b) -> a < b }) {
            if (!s.substring(hTaps[0], hTaps[2]).contains("* kC0") ||
                !s.substring(hTaps[2]).contains("* kC1")
            ) {
                out += "the horizontal tap weights are miswired " +
                    "(the xm/xp pair rides kC0 and the xm2/xp2 pair kC1 — " +
                    "the CPU's own (row[xm]+row[xp])*c0 + (row[xm2]+row[xp2])" +
                    "*c1 pairing; a swapped weight changes the blur of " +
                    "every pixel)"
            }
        }
        // The center + the norm division: the CPU's s = row[x] + ... then
        // tmp = s/norm — written straight into .x (the CPU's tmp buffer).
        if (!s.contains("float y = lumaAt(g);") ||
            !s.contains("oCol = vec4(s / kNorm, 0.0, 0.0, 1.0);")
        ) {
            out += "missing the horizontal output " +
                "(`float y = lumaAt(g);` + `oCol = vec4(s / kNorm, 0.0, ` " +
                "`0.0, 1.0);` — the CPU's s = row[x] + (…)*c0 + (…)*c1 then " +
                "tmp = s/norm, written to .x for the vertical pass to read " +
                "back; the taps sum to 1, so a flat region's blur equals its " +
                "value)"
        }
        // The gate-closed fallback: the ORIGINAL luma to .x, so the
        // vertical pass's center-relative math stays exact in both shapes.
        if (!s.contains("oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);")) {
            out += "missing the gate-closed luma fallback " +
                "(`oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);` — a drawn pass " +
                "with a closed gate writes the ORIGINAL luma to .x, so the " +
                "vertical pass's blur degenerates to the original luma and " +
                "the tail delta is exactly 0)"
        }
    }

    /**
     * SHARPNESS_V's contract — the CPU unsharpLumaImpl vertical blur run +
     * high-pass tail at radius 1.5 (core/src/Develop.cpp), verbatim: the
     * two inputs (uSrc the horizontal output, uGuide the ORIGINAL
     * pre-sharpen pixels), the gate, the amount mapping, the constants,
     * the ORIGINAL-luma guide, the clamped rows at step 1, the exact tap
     * order ym, yp, ym2, yp2, the HORIZONTAL-OUTPUT center fetch, the
     * norm-divided blur, the scaled delta from the ORIGINAL luma and the
     * equally-lifted RGB write.
     */
    private fun sharpnessVProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches SHARPNESS_H's output — .x = the horizontally " +
                "blurred luma plane, the CPU's tmp buffer)"
        }
        if (!s.contains("uniform highp sampler2D uGuide;")) {
            out += "missing `uniform highp sampler2D uGuide;` (the session " +
                "attaches the pointwise POST pass's output — the ORIGINAL " +
                "pre-sharpen RGB; the CPU's tail delta reads the ORIGINAL " +
                "luma and the write needs the original pixel, so this " +
                "texture is load-bearing, not a convenience)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uSharpness;")) {
            out += "missing `uniform float uSharpness;` (the session passes " +
                "the RAW grade value; both passes of one CPU stage share " +
                "one grade control)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uSharpness != 0.0) {")) {
            out += "missing the stage-21 gate " +
                "(`if (uSharpness != 0.0) {` — grade.sharpness == 0 skips " +
                "the whole stage, exactly like the CPU call site's own gate)"
        }
        if (!s.contains("float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, 0.5);")) {
            out += "missing the stage-21 amount mapping " +
                "(`float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, ` " +
                "`0.5);` — the CPU call site's own min(max(0.5*(raw/100), " +
                "-0.5), 0.5), same mapping as SHARPNESS_H)"
        }
        // The CPU's own constants, verbatim (same as the horizontal pass).
        if (!s.contains("const float kRadius = 1.5;") ||
            !s.contains("const float kC0 = exp(-0.5 / (kRadius * kRadius));") ||
            !s.contains("const float kC1 = exp(-2.0 / (kRadius * kRadius));") ||
            !s.contains("const float kNorm = 1.0 + 2.0 * (kC0 + kC1);")
        ) {
            out += "missing the CPU's sharpness constants " +
                "(kRadius 1.5 / kC0 / kC1 / kNorm — the same const block as " +
                "the horizontal pass, verbatim)"
        }
        // The ORIGINAL-luma guide: lumaAt must read uGuide, not uSrc —
        // the CPU's tail reads the ORIGINAL luma (luma[i] - blur[i]).
        if (!s.contains("vec3 c = texelFetch(uGuide, p, 0).rgb;")) {
            out += "missing the ORIGINAL-luma guide read " +
                "(`vec3 c = texelFetch(uGuide, p, 0).rgb;` in lumaAt — the " +
                "CPU's tail computes luma[i] - blur[i] against the " +
                "ORIGINAL luma, NOT the blurred plane; guiding from uSrc " +
                "would zero the delta of every pixel)"
        }
        // The clamped rows at step 1.
        if (!s.contains("int ym = max(g.y - 1, 0);") ||
            !s.contains("int yp = min(g.y + 1, uSize.y - 1);") ||
            !s.contains("int ym2 = max(g.y - 2, 0);") ||
            !s.contains("int yp2 = min(g.y + 2, uSize.y - 1);")
        ) {
            out += "missing the vertical pass's clamped rows " +
                "(`int ym = max(g.y - 1, 0);` … `int yp2 = min(g.y + 2, ` " +
                "`uSize.y - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards at step 1 are load-bearing and carried " +
                "verbatim)"
        }
        // The exact tap order ym, yp, ym2, yp2.
        val ymTapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, ym), 0).x")
        val ypTapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, yp), 0).x")
        val ym2TapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, ym2), 0).x")
        val yp2TapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, yp2), 0).x")
        if (listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx).any { it < 0 } ||
            ymTapIdx >= ypTapIdx || ypTapIdx >= ym2TapIdx || ym2TapIdx >= yp2TapIdx
        ) {
            out += "the vertical tap order is wrong " +
                "(ym, yp, ym2, yp2 in that exact order — unsharpLumaImpl's " +
                "own (tmp[ym]+tmp[yp])*c0 + (tmp[ym2]+tmp[yp2])*c1 " +
                "sequence)"
        }
        // The tap-weight wiring, pair-segment based (same reason as the
        // horizontal pass — the weight follows the pair's second tap).
        val vTaps = listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx)
        if (vTaps.first() >= 0 && vTaps.zipWithNext().all { (a, b) -> a < b }) {
            if (!s.substring(vTaps[0], vTaps[2]).contains("* kC0") ||
                !s.substring(vTaps[2]).contains("* kC1")
            ) {
                out += "the vertical tap weights are miswired " +
                    "(the ym/yp pair rides kC0 and the ym2/yp2 pair kC1 — " +
                    "the CPU's own (tmp[ym]+tmp[yp])*c0 + (tmp[ym2]+tmp[yp2])" +
                    "*c1 pairing; a swapped weight changes the blur of " +
                    "every pixel)"
            }
        }
        // The center fetch: the CPU's `v = tmp[y*w+x]` — the HORIZONTALLY
        // blurred value the vertical pass centers on, NOT the original.
        if (!s.contains("float v = texelFetch(uSrc, g, 0).x;")) {
            out += "missing the vertical pass's center fetch " +
                "(`float v = texelFetch(uSrc, g, 0).x;` — the CPU's " +
                "v = tmp[y*w+x]: the HORIZONTALLY blurred center, not the " +
                "original luma; values from uSrc, luma guide from uGuide)"
        }
        // The blur + the tail: the CPU's blur = s/norm then
        // d = amount * (luma[i] - blur[i]), added to r, g and b equally.
        if (!s.contains("float blur = s / kNorm;") ||
            !s.contains("float d = amount * (lumaAt(g) - blur);") ||
            !s.contains("oCol = vec4(orig + vec3(d), 1.0);")
        ) {
            out += "missing the sharpness tail " +
                "(`float blur = s / kNorm;` + `float d = amount * (lumaAt(g) ` " +
                "`- blur);` + `oCol = vec4(orig + vec3(d), 1.0);` — the " +
                "CPU's blur = s/norm then the amount-scaled high-pass delta " +
                "from the ORIGINAL luma, added to r, g, b equally (chroma " +
                "and hue preserved — no chroma halos))"
        }
        if (!s.contains("oCol = vec4(orig, 1.0);")) {
            out += "missing the gate-closed pass-through " +
                "(`oCol = vec4(orig, 1.0);` — a drawn pass with a closed " +
                "gate writes the input through unchanged)"
        }
    }

    /**
     * DETAIL_H's contract — the CPU unsharpLumaImpl horizontal blur run at
     * radius 0.75 (core/src/Develop.cpp), verbatim: the same kernel as
     * stage 21 at the fine-texture scale, so the same checks with the
     * radius-0.75 constants (step still 1: the CPU's
     * `radius < 1.0f ? 1.0f : floor(radius)` fallback) and the uDetail
     * gate/amount.
     */
    private fun detailHProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the chain's current RGBA32F input — the sharpness " +
                "output when stage 21 ran, else the pointwise POST pass's " +
                "output; the CPU runs stage 22 on the stage-21 output)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uDetail;")) {
            out += "missing `uniform float uDetail;` (the session passes " +
                "the RAW grade value; the gate + amount idiom below is the " +
                "second lock)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uDetail != 0.0) {")) {
            out += "missing the stage-22 gate " +
                "(`if (uDetail != 0.0) {` — grade.detail == 0 skips the " +
                "whole stage, exactly like the CPU call site's own " +
                "`if (grade.sharpness != 0.0f || grade.detail != 0.0f)` " +
                "outer gate's detail arm)"
        }
        if (!s.contains("float amount = clamp(0.5 * (uDetail / 100.0), -0.5, 0.5);")) {
            out += "missing the stage-22 amount mapping " +
                "(`float amount = clamp(0.5 * (uDetail / 100.0), -0.5, ` " +
                "`0.5);` — the CPU call site's own min(max(0.5*(raw/100), " +
                "-0.5), 0.5); NEGATIVE values are valid (softening), so " +
                "there is NO [0,100] clamp and NO strength sub-gate, unlike " +
                "the NR passes)"
        }
        // The CPU's own constants, verbatim (radius 0.75 — step = 1 both
        // directions, the CPU's `radius < 1.0f ? 1.0f : floor(radius)`
        // fallback; exp() of constant arguments is a constant expression
        // in ES 3.00, so they keep const).
        if (!s.contains("const float kRadius = 0.75;") ||
            !s.contains("const float kC0 = exp(-0.5 / (kRadius * kRadius));") ||
            !s.contains("const float kC1 = exp(-2.0 / (kRadius * kRadius));") ||
            !s.contains("const float kNorm = 1.0 + 2.0 * (kC0 + kC1);")
        ) {
            out += "missing the CPU's detail constants " +
                "(kRadius 0.75 / kC0 = exp(-0.5/r^2) / kC1 = exp(-2/r^2) / " +
                "kNorm = 1 + 2*(c0+c1) — unsharpLumaImpl's own const block " +
                "at radius 0.75, verbatim; step is STILL 1: the CPU's own " +
                "`radius < 1.0f ? 1.0f : floor(radius)` fallback)"
        }
        // The luma split — the same helper as SHARPNESS_H, reading uSrc.
        if (!s.contains("vec3 c = texelFetch(uSrc, p, 0).rgb;") ||
            !s.contains("return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;")
        ) {
            out += "missing the luma helper " +
                "(`float lumaAt(ivec2 p)` reading uSrc with the CPU's own " +
                "Rec.709 weights 0.2126/0.7152/0.0722 — unsharpLumaImpl's " +
                "luma[i] split, verbatim)"
        }
        // The clamped columns at step 1 (radius 0.75 -> the CPU's own
        // < 1 fallback, still step 1).
        if (!s.contains("int xm = max(g.x - 1, 0);") ||
            !s.contains("int xp = min(g.x + 1, uSize.x - 1);") ||
            !s.contains("int xm2 = max(g.x - 2, 0);") ||
            !s.contains("int xp2 = min(g.x + 2, uSize.x - 1);")
        ) {
            out += "missing the horizontal pass's clamped columns " +
                "(`int xm = max(g.x - 1, 0);` … `int xp2 = min(g.x + 2, ` " +
                "`uSize.x - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards at step 1 (radius 0.75 -> the CPU's own " +
                "< 1 fallback) are load-bearing and carried verbatim)"
        }
        // The exact tap order xm, xp, xm2, xp2.
        val xmTapIdx = s.indexOf("lumaAt(ivec2(xm, g.y))")
        val xpTapIdx = s.indexOf("lumaAt(ivec2(xp, g.y))")
        val xm2TapIdx = s.indexOf("lumaAt(ivec2(xm2, g.y))")
        val xp2TapIdx = s.indexOf("lumaAt(ivec2(xp2, g.y))")
        if (listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx).any { it < 0 } ||
            xmTapIdx >= xpTapIdx || xpTapIdx >= xm2TapIdx || xm2TapIdx >= xp2TapIdx
        ) {
            out += "the horizontal tap order is wrong " +
                "(xm, xp, xm2, xp2 in that exact order — unsharpLumaImpl's " +
                "own (row[xm]+row[xp])*c0 + (row[xm2]+row[xp2])*c1 " +
                "sequence; the accumulation order is part of the math)"
        }
        // The tap-weight wiring, pair-segment based (the accumulation is
        // ONE expression — the weight follows the pair's second tap).
        val hTaps = listOf(xmTapIdx, xpTapIdx, xm2TapIdx, xp2TapIdx)
        if (hTaps.first() >= 0 && hTaps.zipWithNext().all { (a, b) -> a < b }) {
            if (!s.substring(hTaps[0], hTaps[2]).contains("* kC0") ||
                !s.substring(hTaps[2]).contains("* kC1")
            ) {
                out += "the horizontal tap weights are miswired " +
                    "(the xm/xp pair rides kC0 and the xm2/xp2 pair kC1 — " +
                    "the CPU's own c0/c1 pairing; a swapped weight changes " +
                    "the blur of every pixel)"
            }
        }
        // The center + the norm division, written to .x.
        if (!s.contains("float y = lumaAt(g);") ||
            !s.contains("oCol = vec4(s / kNorm, 0.0, 0.0, 1.0);")
        ) {
            out += "missing the horizontal output " +
                "(`float y = lumaAt(g);` + `oCol = vec4(s / kNorm, 0.0, ` " +
                "`0.0, 1.0);` — the CPU's s = row[x] + (…)*c0 + (…)*c1 then " +
                "tmp = s/norm, written to .x for the vertical pass to read " +
                "back; the taps sum to 1, so a flat region's blur equals its " +
                "value)"
        }
        // The gate-closed fallback: the ORIGINAL luma to .x.
        if (!s.contains("oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);")) {
            out += "missing the gate-closed luma fallback " +
                "(`oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);` — a drawn pass " +
                "with a closed gate writes the ORIGINAL luma to .x, so the " +
                "vertical pass's blur degenerates to the original luma and " +
                "the tail delta is exactly 0)"
        }
    }

    /**
     * DETAIL_V's contract — the CPU unsharpLumaImpl vertical blur run +
     * high-pass tail at radius 0.75 (core/src/Develop.cpp), verbatim: the
     * same shape as SHARPNESS_V with the radius-0.75 constants and the
     * uDetail gate/amount; uGuide is the stage's ORIGINAL input (the
     * sharpness output when stage 21 ran, else the pointwise POST pass's
     * output — the CPU runs stage 22 on the stage-21 output).
     */
    private fun detailVProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches DETAIL_H's output — .x = the horizontally " +
                "blurred luma plane, the CPU's tmp buffer)"
        }
        if (!s.contains("uniform highp sampler2D uGuide;")) {
            out += "missing `uniform highp sampler2D uGuide;` (the session " +
                "attaches the stage's ORIGINAL input — the sharpness output " +
                "when stage 21 ran, else the pointwise POST pass's output; " +
                "the CPU's tail delta reads the ORIGINAL luma and the write " +
                "needs the original pixel, so this texture is load-bearing, " +
                "not a convenience)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it)"
        }
        if (!s.contains("uniform float uDetail;")) {
            out += "missing `uniform float uDetail;` (the session passes " +
                "the RAW grade value; both passes of one CPU stage share " +
                "one grade control)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        if (!s.contains("if (uDetail != 0.0) {")) {
            out += "missing the stage-22 gate " +
                "(`if (uDetail != 0.0) {` — grade.detail == 0 skips the " +
                "whole stage, exactly like the CPU call site's own gate)"
        }
        if (!s.contains("float amount = clamp(0.5 * (uDetail / 100.0), -0.5, 0.5);")) {
            out += "missing the stage-22 amount mapping " +
                "(`float amount = clamp(0.5 * (uDetail / 100.0), -0.5, ` " +
                "`0.5);` — the CPU call site's own min(max(0.5*(raw/100), " +
                "-0.5), 0.5), same mapping as DETAIL_H)"
        }
        // The CPU's own constants, verbatim (same as the horizontal pass).
        if (!s.contains("const float kRadius = 0.75;") ||
            !s.contains("const float kC0 = exp(-0.5 / (kRadius * kRadius));") ||
            !s.contains("const float kC1 = exp(-2.0 / (kRadius * kRadius));") ||
            !s.contains("const float kNorm = 1.0 + 2.0 * (kC0 + kC1);")
        ) {
            out += "missing the CPU's detail constants " +
                "(kRadius 0.75 / kC0 / kC1 / kNorm — the same const block " +
                "as the horizontal pass, verbatim)"
        }
        // The ORIGINAL-luma guide: lumaAt must read uGuide, not uSrc.
        if (!s.contains("vec3 c = texelFetch(uGuide, p, 0).rgb;")) {
            out += "missing the ORIGINAL-luma guide read " +
                "(`vec3 c = texelFetch(uGuide, p, 0).rgb;` in lumaAt — the " +
                "CPU's tail computes luma[i] - blur[i] against the " +
                "ORIGINAL luma, NOT the blurred plane; guiding from uSrc " +
                "would zero the delta of every pixel)"
        }
        // The clamped rows at step 1.
        if (!s.contains("int ym = max(g.y - 1, 0);") ||
            !s.contains("int yp = min(g.y + 1, uSize.y - 1);") ||
            !s.contains("int ym2 = max(g.y - 2, 0);") ||
            !s.contains("int yp2 = min(g.y + 2, uSize.y - 1);")
        ) {
            out += "missing the vertical pass's clamped rows " +
                "(`int ym = max(g.y - 1, 0);` … `int yp2 = min(g.y + 2, ` " +
                "`uSize.y - 1);` — texelFetch does NOT clamp; the CPU's " +
                "max/min guards at step 1 are load-bearing and carried " +
                "verbatim)"
        }
        // The exact tap order ym, yp, ym2, yp2.
        val ymTapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, ym), 0).x")
        val ypTapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, yp), 0).x")
        val ym2TapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, ym2), 0).x")
        val yp2TapIdx = s.indexOf("texelFetch(uSrc, ivec2(g.x, yp2), 0).x")
        if (listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx).any { it < 0 } ||
            ymTapIdx >= ypTapIdx || ypTapIdx >= ym2TapIdx || ym2TapIdx >= yp2TapIdx
        ) {
            out += "the vertical tap order is wrong " +
                "(ym, yp, ym2, yp2 in that exact order — unsharpLumaImpl's " +
                "own (tmp[ym]+tmp[yp])*c0 + (tmp[ym2]+tmp[yp2])*c1 " +
                "sequence)"
        }
        // The tap-weight wiring, pair-segment based.
        val vTaps = listOf(ymTapIdx, ypTapIdx, ym2TapIdx, yp2TapIdx)
        if (vTaps.first() >= 0 && vTaps.zipWithNext().all { (a, b) -> a < b }) {
            if (!s.substring(vTaps[0], vTaps[2]).contains("* kC0") ||
                !s.substring(vTaps[2]).contains("* kC1")
            ) {
                out += "the vertical tap weights are miswired " +
                    "(the ym/yp pair rides kC0 and the ym2/yp2 pair kC1 — " +
                    "the CPU's own c0/c1 pairing; a swapped weight changes " +
                    "the blur of every pixel)"
            }
        }
        // The center fetch: the CPU's `v = tmp[y*w+x]`.
        if (!s.contains("float v = texelFetch(uSrc, g, 0).x;")) {
            out += "missing the vertical pass's center fetch " +
                "(`float v = texelFetch(uSrc, g, 0).x;` — the CPU's " +
                "v = tmp[y*w+x]: the HORIZONTALLY blurred center, not the " +
                "original luma; values from uSrc, luma guide from uGuide)"
        }
        // The blur + the tail.
        if (!s.contains("float blur = s / kNorm;") ||
            !s.contains("float d = amount * (lumaAt(g) - blur);") ||
            !s.contains("oCol = vec4(orig + vec3(d), 1.0);")
        ) {
            out += "missing the detail tail " +
                "(`float blur = s / kNorm;` + `float d = amount * (lumaAt(g) ` " +
                "`- blur);` + `oCol = vec4(orig + vec3(d), 1.0);` — the " +
                "CPU's blur = s/norm then the amount-scaled high-pass delta " +
                "from the ORIGINAL luma, added to r, g, b equally (chroma " +
                "and hue preserved — no chroma halos))"
        }
        if (!s.contains("oCol = vec4(orig, 1.0);")) {
            out += "missing the gate-closed pass-through " +
                "(`oCol = vec4(orig, 1.0);` — a drawn pass with a closed " +
                "gate writes the input through unchanged)"
        }
    }

    /**
     * WARP's contract — the CPU rawedit::warpFrame (core/src/Warp.cpp,
     * FROZEN) as a GLSL fragment pass: the composed inverse-sampling map
     * uM (row-major, uploaded with transpose=GL_TRUE — the uCam2rgb
     * idiom), the homogeneous mapPoint tap with the (0,0) unmappable
     * clamp, the CPU's exact clamp/truncation/edge-clamp bilinear
     * semantics. NOT a develop stage: the CPU runs it after developCpu,
     * on the linear output, and the session draws it between the unsharp
     * passes and the tail. NO OETF here (the tail owns it, applied
     * exactly once) and NO in-shader gate (the session skips the draw
     * entirely for an inactive/identity/degenerate plan — the pass's
     * absence is the identity).
     */
    private fun warpProblems(s: String, out: MutableList<String>) {
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "binds the chain's live intermediate on unit 1 — the " +
                "unsharp output, or the pointwise POST output when stages " +
                "21/22 are off)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the session sets it to " +
                "the frame's dims — the clamps and the edge guard read it)"
        }
        if (!s.contains("uniform mat3 uM;")) {
            out += "missing `uniform mat3 uM;` (the session uploads the " +
                "caller-composed M = Hinv * S with glUniformMatrix3fv " +
                "transpose=GL_TRUE — the row-major CPU contract, the " +
                "uCam2rgb idiom)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        // The homogeneous tap: the CPU's mapPoint, verbatim.
        if (!s.contains("vec3 h = uM * vec3(float(g.x), float(g.y), 1.0);")) {
            out += "missing the homogeneous map tap " +
                "(`vec3 h = uM * vec3(float(g.x), float(g.y), 1.0);` — " +
                "mapPoint's u/v/w triple; transpose=GL_TRUE makes this the " +
                "CPU's row-major multiply exactly)"
        }
        // The unmappable check: the CPU's !isfinite(w) || fabs(w) < 1e-12
        // plus the non-finite ox/oy, folded into the (0,0) clamp. isfinite
        // is NOT an ES 3.00 builtin, so the shader spells it
        // isinf/isnan.
        if (!s.contains("if (!isinf(h.z) && !isnan(h.z) && abs(h.z) >= 1e-12) {")) {
            out += "missing the unmappable denominator check " +
                "(`if (!isinf(h.z) && !isnan(h.z) && abs(h.z) >= 1e-12) {` — " +
                "mapPoint's !isfinite(w) || fabs(w) < 1e-12 fail, which " +
                "clamps the tap to (0,0))"
        }
        if (!s.contains("if (!isinf(mx) && !isnan(mx) && !isinf(my) && !isnan(my)) {") ||
            !s.contains("float mx = h.x / h.z;") ||
            !s.contains("float my = h.y / h.z;")
        ) {
            out += "missing the non-finite output check " +
                "(`float mx = h.x / h.z;` + `float my = h.y / h.z;` + " +
                "`if (!isinf(mx) && !isnan(mx) && !isinf(my) && !isnan(my)) {` " +
                "— mapPoint's isfinite(ox) && isfinite(oy) return, which " +
                "clamps the tap to (0,0))"
        }
        // The CPU's clamps: std::min(std::max(ox, 0.0), w-1) both axes.
        if (!s.contains("float fx = clamp(ox, 0.0, float(uSize.x - 1));") ||
            !s.contains("float fy = clamp(oy, 0.0, float(uSize.y - 1));")
        ) {
            out += "missing the source-coordinate clamps " +
                "(`clamp(ox, 0.0, float(uSize.x - 1))` / `clamp(oy, 0.0, ` " +
                "`float(uSize.y - 1))` — warpFrame's own min(max(...)) pair; " +
                "texelFetch does NOT clamp, so these are load-bearing)"
        }
        // The truncation + the edge clamp: x0 = (uint32_t)fx (truncation
        // == floor since fx >= 0 after the clamp), x1 = x0+1 only when
        // x0+1 < w, else x0 — the CPU's EDGE CLAMP, never a mirror.
        if (!s.contains("int x0 = int(fx);") || !s.contains("int y0 = int(fy);") ||
            !s.contains("int x1 = x0 + 1 < uSize.x ? x0 + 1 : x0;") ||
            !s.contains("int y1 = y0 + 1 < uSize.y ? y0 + 1 : y0;")
        ) {
            out += "missing the truncation + edge clamp " +
                "(`int x0 = int(fx);` … `int x1 = x0 + 1 < uSize.x ? ` " +
                "`x0 + 1 : x0;` — the CPU's static_cast<uint32_t> then " +
                "x0+1-if-inside-else-x0 pair; a mirror or wrap here changes " +
                "every border pixel)"
        }
        // Exactly four texelFetch taps, the CPU's tap order
        // p00/p01/p10/p11 = (x0,y0)/(x1,y0)/(x0,y1)/(x1,y1).
        val p00Idx = s.indexOf("texelFetch(uSrc, ivec2(x0, y0), 0)")
        val p01Idx = s.indexOf("texelFetch(uSrc, ivec2(x1, y0), 0)")
        val p10Idx = s.indexOf("texelFetch(uSrc, ivec2(x0, y1), 0)")
        val p11Idx = s.indexOf("texelFetch(uSrc, ivec2(x1, y1), 0)")
        if (listOf(p00Idx, p01Idx, p10Idx, p11Idx).any { it < 0 } ||
            p00Idx >= p01Idx || p01Idx >= p10Idx || p10Idx >= p11Idx
        ) {
            out += "the bilinear tap order is wrong or a tap is missing " +
                "(p00, p01, p10, p11 — exactly four texelFetch reads in " +
                "that order, warpFrame's own tap sequence)"
        }
        val tapCount = Regex("texelFetch\\(").findAll(s).count()
        if (tapCount != 4) {
            out += "expected exactly 4 texelFetch taps (got $tapCount — " +
                "the CPU's bilinear reads exactly p00/p01/p10/p11, nothing " +
                "else)"
        }
        // The CPU's lerp structure: top/bot rows then the vertical mix —
        // the double-precision lerp narrowed to float, verbatim shape.
        if (!s.contains("vec3 top = p00 + (p01 - p00) * ax;") ||
            !s.contains("vec3 bot = p10 + (p11 - p10) * ax;") ||
            !s.contains("oCol = vec4(top + (bot - top) * ay, 1.0);")
        ) {
            out += "missing the bilinear lerp " +
                "(`vec3 top = p00 + (p01 - p00) * ax;` + `vec3 bot = ` " +
                "`p10 + (p11 - p10) * ax;` + `oCol = vec4(top + (bot - top) ` " +
                "`* ay, 1.0);` — warpFrame's top/bot/lerp sequence, the " +
                "CPU's exact arithmetic order)"
        }
        // NO OETF: the tail owns it. A warp that tone-maps would apply
        // the OETF twice (the tail draws after this pass).
        if (s.contains("linearToRec709") || s.contains("uOetf")) {
            out += "the warp must NOT apply an OETF " +
                "(no linearToRec709, no uOetf — the TAIL pass owns the " +
                "OETF and draws AFTER the warp; a second one here would " +
                "double tone-map every pixel)"
        }
        // NO in-shader gate: the session skips the draw entirely for an
        // inactive/identity/degenerate plan — the pass's absence is the
        // identity. A gate uniform would be dead vocabulary.
        if (s.contains("uActive") || s.contains("uWarp")) {
            out += "the warp must NOT carry an in-shader gate " +
                "(no uActive/uWarp — the session skips the draw entirely " +
                "when the plan folds to inactive/identity/degenerate; the " +
                "pass's ABSENCE is the identity, so a gate uniform would " +
                "be dead vocabulary)"
        }
    }

    private fun storeFragmentProblems(s: String, out: MutableList<String>) {
        if (!s.contains("layout(std430, binding = 0) writeonly buffer OutBuf")) {
            out += "missing `layout(std430, binding = 0) writeonly buffer OutBuf` " +
                "(the C++ harness attaches the readback SSBO to binding point 0 " +
                "with glBindBufferBase and assumes std430 tight float packing)"
        }
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the C++ harness " +
                "attaches the developed color attachment as a texture)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the C++ harness sets it)"
        }
        if (!s.contains("texelFetch(uSrc, g, 0)")) {
            out += "missing the `texelFetch(uSrc, g, 0)` readback fetch " +
                "(integer texel fetch — no sampling filters are involved)"
        }
        // The throwaway color output keeps the pass legal on drivers that
        // dislike zero-output fragment shaders (the SSBO is the real
        // output; the RGBA8 target is never read).
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the throwaway color output — " +
                "the SSBO is the real output, but the pass must still write " +
                "a legal fragment output)"
        }
    }

    private fun blitFragmentProblems(s: String, out: MutableList<String>) {
        // The uniforms MediaCodecMp4Writer's blit program looks up and
        // sets after linking (buildBlitProgram). Names double as the
        // Kotlin lookup contract.
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the writer " +
                "attaches the developed frame texture)"
        }
        if (!s.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the writer sets it)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the window surface's color output)"
        }
        if (!s.contains("texelFetch(uSrc, src, 0)")) {
            out += "missing the `texelFetch(uSrc, src, 0)` fetch " +
                "(integer texel fetch — no sampling filters are involved)"
        }
        // The Y flip — the one line that keeps the encode right-side up:
        // the encoder reads the Surface buffer top-row-first while GL's
        // gl_FragCoord row 0 is the render target's bottom row.
        if (!s.contains("uSize.y - 1 - g.y")) {
            out += "missing the Y flip (`uSize.y - 1 - g.y` — the encoder " +
                "consumes the Surface buffer top-row-first; without the " +
                "flip every encoded frame is upside down)"
        }
        // The rotation fold (the surface-gpu rotation deliverable): the
        // develop session sets uRotation to the frame's effective
        // orientation (0/90/180/270) and the fold maps each rotated
        // destination pixel back to its source texel — rotateRgb888's
        // index math inverted. The 0 arm keeps the Y flip above
        // byte-identical, and the WRITER (which never sets uRotation —
        // GLSL defaults it to 0) compiles the same text unchanged.
        if (!s.contains("uniform int uRotation;")) {
            out += "missing `uniform int uRotation;` (the session passes " +
                "the effective orientation normalized to 0/90/180/270, " +
                "defaulting to 0 so the writer's Surface mode is unchanged)"
        }
        if (!s.contains("uRotation == 90")) {
            out += "missing the rotation fold (`uRotation == 90` — " +
                "rotateRgb888's quarter-turn index math, reproduced " +
                "in-shader; without it a 90/270 frame blits sideways)"
        }
    }

    private fun windowFragmentProblems(s: String, out: MutableList<String>) {
        // The uniforms GlesPreviewSession's window program looks up and
        // sets after linking. Names double as the Kotlin lookup contract.
        if (!s.contains("uniform highp sampler2D uSrc;")) {
            out += "missing `uniform highp sampler2D uSrc;` (the session " +
                "attaches the developed preview texture)"
        }
        if (!s.contains("uniform ivec2 uSrcSize;")) {
            out += "missing `uniform ivec2 uSrcSize;` (the session sets it — " +
                "the half-texel clamp depends on it, and an optimized-out " +
                "uniform would fail the session's all-uniforms lookup)"
        }
        if (!s.contains("uniform vec4 uRect;")) {
            out += "missing `uniform vec4 uRect;` (the session computes the " +
                "aspect-fit letterbox rect)"
        }
        if (!s.contains("uniform int uOrientation;")) {
            out += "missing `uniform int uOrientation;` (the session passes " +
                "FrameInfo.orientationDeg normalized to 0/90/180/270)"
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the window surface's color output)"
        }
        if (!s.contains("texture(uSrc, st)")) {
            out += "missing the `texture(uSrc, st)` fetch (normalized " +
                "sampling — the aspect-fit scale the blit's texelFetch " +
                "cannot do)"
        }
        // The orientation fold — developToArgb's per-case rotation fold in
        // normalized space; without it a 90/180/270 frame draws rotated.
        if (!s.contains("uOrientation == 90")) {
            out += "missing the orientation fold (`uOrientation == 90` — " +
                "the CPU preview path's upright rotation, reproduced " +
                "in-shader)"
        }
        // The Y flip — the source texture is top-row-first while
        // gl_FragCoord counts rows bottom-up (the same trap the blit's
        // flip guards against, folded into the default arm).
        if (!s.contains("1.0 - uvFull.y")) {
            out += "missing the Y flip (`1.0 - uvFull.y` — the developed " +
                "texture is top-row-first; without the fold the window " +
                "and stats draws are upside down; since A2 the fold reads " +
                "uvFull, the crop-mapped uv)"
        }
        // The A2 crop pins (owner request 2026-11-23, guide A2): the six
        // uniforms exist because GlesPreviewSession's window program
        // looks each up (an optimized-out uniform fails the all-uniforms
        // lookup), and the gate + manual bilinear are the crop math's
        // own tokens — without them the crop block could be quietly
        // dropped while the uniforms still link.
        if (!s.contains("uniform int uCropOn;")) {
            out += "missing `uniform int uCropOn;` (the session's " +
                "windowCropOnLoc lookup — the crop gate; without it " +
                "an active crop renders uncropped)"
        }
        if (!s.contains("uniform vec4 uCropRect;")) {
            out += "missing `uniform vec4 uCropRect;` (the session's " +
                "windowCropRectLoc lookup — the mapRect'ed crop " +
                "rect, container-upright fractions)"
        }
        if (!s.contains("uniform vec2 uCropOut;")) {
            out += "missing `uniform vec2 uCropOut;` (the session's " +
                "windowCropOutLoc lookup — the cropped output dims " +
                "the rect-local uv walks back from)"
        }
        if (!s.contains("uniform float uCropZoom;")) {
            out += "missing `uniform float uCropZoom;` (the session's " +
                "windowCropZoomLoc lookup — the straighten " +
                "auto-zoom; without it a straightened crop shows empty " +
                "triangles)"
        }
        if (!s.contains("uniform float uCropCos;")) {
            out += "missing `uniform float uCropCos;` (the session's " +
                "windowCropCosLoc lookup — the double-theta cos the " +
                "CPU reference uses; GLSL's own cos would drift)"
        }
        if (!s.contains("uniform float uCropSin;")) {
            out += "missing `uniform float uCropSin;` (the session's " +
                "windowCropSinLoc lookup — the double-theta sin " +
                "pair's other half)"
        }
        if (!s.contains("uCropOn == 1")) {
            out += "missing the crop gate (`uCropOn == 1` — without " +
                "the gate the crop uniforms are dead text and a cropped " +
                "request renders uncropped)"
        }
        if (!s.contains("texelFetch(uSrc")) {
            out += "missing the manual bilinear (`texelFetch(uSrc` — " +
                "the crop branch's 4-tap sampling, mirroring " +
                "CropState.sampleArgbBilinear; texture() on the NEAREST-" +
                "pinned develop texture would give blocky crop edges)"
        }
        // The A3 zebra pins (owner request 2026-11-23, guide A3): the four
        // uniforms exist because GlesPreviewSession's window program looks
        // each up (an optimized-out zebra uniform fails the all-uniforms
        // lookup), and the uZebraOn == 1 gate token keeps the decision
        // math from being quietly dropped while the uniforms still link.
        if (!s.contains("uniform int uZebraOn;")) {
            out += "missing `uniform int uZebraOn;` (the session's " +
                "windowZebraOnLoc lookup — the zebra gate; without it " +
                "a zebra-on request renders clean and the clip warning " +
                "silently vanishes)"
        }
        if (!s.contains("uniform int uZebraThreshold;")) {
            out += "missing `uniform int uZebraThreshold;` (the session's " +
                "windowZebraThresholdLoc lookup — the display-referred " +
                "clip value FrameHistogram.WHITE_CLIP_VALUE rides; a " +
                "hardcoded threshold would break the single-source rule)"
        }
        if (!s.contains("uniform int uZebraPeriod;")) {
            out += "missing `uniform int uZebraPeriod;` (the session's " +
                "windowZebraPeriodLoc lookup — ZebraOverlay.STRIPE_PERIOD " +
                "rides; a hardcoded period would break the single-source " +
                "rule)"
        }
        if (!s.contains("uniform float uZebraAlpha;")) {
            out += "missing `uniform float uZebraAlpha;` (the session's " +
                "windowZebraAlphaLoc lookup — ZebraOverlay.STRIPE_ALPHA/255 " +
                "rides; a hardcoded alpha would break the single-source " +
                "rule)"
        }
        if (!s.contains("uZebraOn == 1")) {
            out += "missing the zebra gate (`uZebraOn == 1` — without " +
                "the gate the zebra uniforms are dead text and the stats " +
                "draw's clean-scope pin has nothing to pin)"
        }
    }

    // ---- NODES's contract (color model v2 F1-5). Every check names the
    // ---- C++ line it mirrors (core/src/ColorModel.cpp: applyCompiled /
    //     nodeCurveSample) or the pipeline uniform it depends on, so a
    //     rename breaks HERE, on the host, not silently on the device.

    /** The NODES pass's uniform set (gles/NodeParcel.h's pack). */
    private val kNodesUniformDecls = listOf(
        "uniform highp sampler2D uSrc;",
        "uniform ivec2 uSize;",
        "uniform int uNodesCount;",
        "uniform int uNodesOutputMode;",
        "uniform mat3 uM709ToDwg;",
        "uniform mat3 uMDwgTo709;",
        "uniform highp sampler2D uNodeCurves;",
        "uniform vec4 uNodePrimary[8];",
        "uniform vec4 uNodeGain[8];",
        "uniform vec4 uNodeOffset[8];",
        "uniform vec4 uNodeGammaExp[8];",
        "uniform vec4 uNodeYSide[8];",
        "uniform vec4 uNodeContrastPivot[8];",
        "uniform vec4 uNodeWb[8];",
        "uniform mat3 uNodeHueMat[8];",
        "uniform vec4 uNodeFlags[8];",
        "uniform highp sampler3D uNodeLut[4];",
    )

    private fun nodesProblems(s: String, out: MutableList<String>) {
        for (u in kNodesUniformDecls) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ pipeline resolves it at init — the NODES pass's own contract)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
        // The chain head/tail order: the conversions cannot be dropped.
        if (!s.contains("vec3 dwg = uM709ToDwg * lin;")) {
            out += "missing the chain head `vec3 dwg = uM709ToDwg * lin;` (rawedit::linear709ToDi's matrix step — transpose=GL_TRUE uploads make this the CPU's row-major multiply)"
        }
        if (!s.contains("diEncode(dwg.r)")) {
            out += "missing the DI encode at the chain head (diEncode — the CPU encodeTransfer's DaVinciIntermediate curve, verbatim constants)"
        }
        if (!s.contains("diDecode(di.r)")) {
            out += "missing the DI decode at the chain tail (diDecode — decodeTransfer's DaVinciIntermediate curve)"
        }
        // The node loop must exist and iterate uNodesCount.
        if (!s.contains("for (int i = 0; i < uNodesCount; ++i)")) {
            out += "missing the node loop `for (int i = 0; i < uNodesCount; ++i)` (applyNodes' per-pixel chain; the loop bound is the parcel's nodeCount)"
        }
        // The per-node math order (applyCompiled): primaries -> y side ->
        // contrast*wb -> hue -> sat -> curves -> LUT -> mix.
        val order = listOf(
            "void applyNode(inout vec3 di, int i)",
            "uNodePrimary[i].xyz + uNodeGain[i].xyz * di + uNodeOffset[i].xyz",
            "pow(max(yv, 0.0), uNodeYSide[i].z)",
            "((di - pivot) * contrast + pivot) * uNodeWb[i].xyz",
            "uNodeHueMat[i] * di",
            "luma + (h - luma) * sat",
            "nodeCurveAtlas(i * 3 + 0, di.r)",
            "uNodeFlags[i].y == 1.0",
            "c0 + (di - c0) * mixv",
        )
        var last = -1
        for (token in order) {
            val at = s.indexOf(token)
            if (at < 0) {
                out += "missing the node-math token `$token` (applyCompiled's own order — the element is the parity target)"
            } else if (at < last) {
                out += "the node-math order broke at `$token` (applyCompiled's sequence: primaries, the y side, contrast*wb, hue, sat, curves, LUT, mix)"
            } else {
                last = at
            }
        }
        // The curve atlas sampling: nodeCurveSample's manual lerp on the
        // DELTAS — texelFetch (R32F is not filterable; Adreno's NEAREST-only
        // 32F rule) with the CPU's own bracket + clamp.
        if (!s.contains("texelFetch(uNodeCurves, ivec2(i, row), 0).r - x0")) {
            out += "missing the atlas DELTA read (`texelFetch(uNodeCurves, ivec2(i, row), 0).r - x0` — nodeCurveSample reads the table's value MINUS x_i; the R32F atlas is texelFetch-only per Adreno's 32F NEAREST rule)"
        }
        if (!s.contains("return x + d0 + (d1 - d0) * (p - float(i));")) {
            out += "missing the atlas lerp (`return x + d0 + (d1 - d0) * (p - float(i));` — nodeCurveSample's exact delta form, NOT a plain table lerp)"
        }
        // The kNodeCurveN/range constants must match ColorModel.h.
        if (!s.contains("const float kCurveLo = -0.5;") ||
            !s.contains("const float kCurveHi = 1.5;") ||
            !s.contains("const float kCurveN = 512.0;")) {
            out += "missing the curve-range constants (kNodeCurveLo -0.5 / kNodeCurveHi 1.5 / kNodeCurveN 512 — ColorModel.h's own values)"
        }
        // The DI encode/decode constants must be the pinned Color.cpp set.
        if (!s.contains("lin <= 0.00262409 ? 10.44426855 * lin") ||
            !s.contains("0.07329248 * (log2(lin + 0.0075) + 7.0)")) {
            out += "missing the DI encode constants (kDiLinCut 0.00262409 / kDiM 10.44426855 / kDiC 0.07329248 / kDiA 0.0075 / kDiB 7 — Color.cpp's pinned set)"
        }
        if (!s.contains("enc <= 0.02740668 ? enc / 10.44426855") ||
            !s.contains("pow(2.0, enc / 0.07329248 - 7.0) - 0.0075")) {
            out += "missing the DI decode constants (kDiLogCut 0.02740668 — Color.cpp's pinned set)"
        }
        // The output mode branch.
        if (!s.contains("if (uNodesOutputMode == 1)")) {
            out += "missing the output-mode branch (`if (uNodesOutputMode == 1)` — NodesOutput::DwgLinear vs the 709 matrix back, the plan §3.2 tail)"
        }
        // The node LUT branch must sample through the managed-binding shape.
        if (!s.contains("nodeLutSample(s, t)") || !s.contains("texture(uNodeLut[3], t).rgb")) {
            out += "missing the node-LUT sample (`nodeLutSample(s, t)` over four constant-indexed `texture(uNodeLut[k], t)` — a dynamic sampler-array index does not compile on Adreno; the stage-24 LUT port's own texel-center mapping, per slot)"
        }
        // No develop-stage uniform may leak into NODES: the pass is a pure
        // chain transform, not a re-run of POST.
        // uSize is the one honest exception (every chain pass shares the
        // dims uniform); the REST of the develop sets may not appear.
        for (u in kDevelopPointwiseUniformDecls +
            kDevelopPreUniformDecls.filter { it != "uniform ivec2 uSize;" } +
            kDevelopTailUniformDecls) {
            if (s.contains(u)) {
                out += "a develop-stage uniform leaked into NODES (`$u` — the pass transforms the chain's linear-709 input; any stage uniform means a stage re-ran)"
            }
        }
    }

    // ---- the DRT TAIL variant's contract (baked route): the sampler/uniform vocabulary and the
    // ---- DI-encode + by-hand trilinear shape, nothing per-pixel OpenDRT.

    private fun tailDrtProblems(s: String, out: MutableList<String>) {
        for (u in listOf(
            "uniform highp sampler2D uSrc;",
            "uniform ivec2 uSize;",
            "uniform highp sampler3D uDrtLut;",
            "uniform float uDrtLutN;",
        )) {
            if (!s.contains(u)) out += "missing the DRT TAIL uniform `$u`"
        }
        if (!s.contains("0.07329248 * (log2(lin + 0.0075) + 7.0)") || !s.contains("10.44426855 * lin")) {
            out += "missing the DI encode (Color.cpp's pinned constants; the table's input axis is the DI code)"
        }
        if (s.split("texelFetch(uDrtLut, ").size - 1 != 8) {
            out += "the by-hand trilinear needs exactly eight lattice fetches (texelFetch(uDrtLut, ...)); a hardware-filtered texture() would break the CPU parity"
        }
        if (s.contains("texture(uDrtLut")) {
            out += "the DRT table must not be sampled through the hardware filter (use texelFetch + mix)"
        }
        if (!s.contains("isnan(rgb.r)")) out += "missing the NaN -> 0 rule (the bake's own)"
        for (leaked in listOf("uDrtTn", "uDrtRs", "kXYZ_TO_P3D65", "uDrtEotf")) {
            if (s.contains(leaked)) out += "the per-pixel OpenDRT leaked back into the baked variant (`$leaked`)"
        }
    }

    /**
     * Removes line comments (slash-slash to end of line) and block
     * comments (slash-asterisk to asterisk-slash; GLSL has no string
     * literals, so no quote handling is needed). Keeps newlines so line
     * structure survives.
     */
    internal fun stripComments(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                while (i < source.length && source[i] != '\n') ++i
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                i += 2
                while (i + 1 < source.length &&
                    !(source[i] == '*' && source[i + 1] == '/')) ++i
                i = if (i + 1 < source.length) i + 2 else source.length
            } else {
                sb.append(c)
                ++i
            }
        }
        return sb.toString()
    }

    private fun balanceProblems(stripped: String): List<String> {
        val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
        val open = ArrayDeque<Char>()
        for (c in stripped) {
            if (c in pairs.keys) {
                open.addLast(c)
            } else if (c in pairs.values) {
                val opener = open.removeLastOrNull()
                    ?: return listOf("unbalanced `$c` with no opener")
                if (pairs[opener] != c) {
                    return listOf("unbalanced `$opener` closed by `$c`")
                }
            }
        }
        return if (open.isEmpty()) {
            emptyList()
        } else {
            listOf("unbalanced `${open.last()}` never closed")
        }
    }
}
