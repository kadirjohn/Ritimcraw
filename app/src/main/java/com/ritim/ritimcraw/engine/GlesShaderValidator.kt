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
            "uniform vec3 uWb;",
            "uniform mat3 uCam2rgb;",
            "uniform int uHasProfile;",
            "uniform float uExposure;",
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
        if (!s.contains("n > 0 ? sum / float(n) : stage1(g)")) {
            out += "missing the n==0 center fallback " +
                "(`n > 0 ? sum / float(n) : stage1(g)`)"
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
        // The OETF constants — rawedit::linearToRec709 (Transfer.cpp).
        if (!s.contains("if (lin < 0.018) return 4.5 * lin;")) {
            out += "missing the OETF linear segment " +
                "(`if (lin < 0.018) return 4.5 * lin;` — rawedit::linearToRec709)"
        }
        if (!s.contains("1.099 * pow(lin, 0.45) - 0.099")) {
            out += "missing the OETF power segment " +
                "(`1.099 * pow(lin, 0.45) - 0.099` — rawedit::linearToRec709)"
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
