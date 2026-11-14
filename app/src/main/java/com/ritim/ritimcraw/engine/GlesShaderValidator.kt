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

        /** STORE_FRAGMENT — GLSL ES 3.10 fragment (SSBO write), readback. */
        STORE_FRAGMENT("#version 310 es"),

        /** BLIT_FRAGMENT — GLSL ES 3.00 fragment, the encoder-Surface blit. */
        BLIT_FRAGMENT("#version 300 es"),

        /** WINDOW_FRAGMENT — GLSL ES 3.00 fragment, the preview window/stats draw. */
        WINDOW_FRAGMENT("#version 300 es"),
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
            Kind.STORE_FRAGMENT -> storeFragmentProblems(stripped, out)
            Kind.BLIT_FRAGMENT -> blitFragmentProblems(stripped, out)
            Kind.WINDOW_FRAGMENT -> windowFragmentProblems(stripped, out)
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
        // The uniforms the harness looks up and sets (gles/GlesPipeline.cpp
        // fragment path). Names double as the C++ lookup contract.
        val uniforms = listOf(
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
        )
        for (u in uniforms) {
            if (!s.contains(u)) {
                out += "missing `$u` (the C++ harness sets it)"
            }
        }
        if (!s.contains("out vec4 oCol;")) {
            out += "missing `out vec4 oCol;` (the single color attachment output)"
        }
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
        // The OETF constants — rawedit::linearToRec709 (Transfer.cpp).
        if (!s.contains("if (lin < 0.018) return 4.5 * lin;")) {
            out += "missing the OETF linear segment " +
                "(`if (lin < 0.018) return 4.5 * lin;` — rawedit::linearToRec709)"
        }
        if (!s.contains("1.099 * pow(lin, 0.45) - 0.099")) {
            out += "missing the OETF power segment " +
                "(`1.099 * pow(lin, 0.45) - 0.099` — rawedit::linearToRec709)"
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
        if (!s.contains("const float knee = 0.75;")) {
            out += "missing the stage-15 knee " +
                "(`const float knee = 0.75;` — the CPU stage-15 shoulder start)"
        }
        if (!s.contains("const float span = 1.0 - knee;")) {
            out += "missing the stage-15 span " +
                "(`const float span = 1.0 - knee;` — the CPU's 1 - knee)"
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
            s.contains("const float luma") || s.contains("const float s =") ||
            s.contains("const float mx") || s.contains("const float over") ||
            s.contains("const float t =") || s.contains("const float shoulder") ||
            s.contains("const float newMx") || s.contains("const float gain")
        ) {
            out += "a tonemap or highlights local uses `const` with a " +
                "non-constant initializer (GLSL ES 3.00 requires constant " +
                "expressions for const; the CPU's read-only const does " +
                "not carry — drop the qualifier, keep the literal-only " +
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
        // The application point: highlights and tonemap sit in the CPU's
        // stage order between the exposure multiply (stage 14) and the
        // OETF (the JNI tail's position) — 14 -> 15 -> 23 -> OETF.
        val exposureIdx = s.indexOf("c *= uExposure;")
        val highlightsIdx = s.indexOf("if (uHighlights != 0.0) {")
        val tonemapIdx = s.indexOf("if (uTonemap != 0) {")
        val oetfIdx = s.indexOf("if (uOetf == 1) {")
        if (exposureIdx < 0 || highlightsIdx < 0 || tonemapIdx < 0 ||
            oetfIdx < 0 ||
            !(exposureIdx < highlightsIdx && highlightsIdx < tonemapIdx &&
                tonemapIdx < oetfIdx)
        ) {
            out += "the highlights/tonemap application point is wrong " +
                "(both must sit between `c *= uExposure;` and the OETF " +
                "block, highlights first — the CPU stage order " +
                "14 -> 15 -> 23 -> OETF)"
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
        if (!s.contains("1.0 - uv.y")) {
            out += "missing the Y flip (`1.0 - uv.y` — the developed " +
                "texture is top-row-first; without the fold the window " +
                "and stats draws are upside down)"
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
