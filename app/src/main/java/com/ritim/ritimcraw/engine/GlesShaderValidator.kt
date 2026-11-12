package com.ritim.ritimcraw.engine

/**
 * Host syntax + contract validator for the GLSL ES 3.10 compute sources
 * (owner request 2026-11-12, deliverable C).
 *
 * HONESTY: this is NOT a compile. This host has no GLSL compiler (no
 * glslangValidator, no glslc) and no EGL, so no compile claim is made
 * anywhere on the host. What this check DOES do, on the exact text the
 * device will compile (GlesShaderSources — the single source of truth):
 *
 *  - ASCII printable only (a stray unicode dash from an editor paste
 *    would otherwise surface only as an opaque device compile error);
 *  - `#version 310 es` as the first statement;
 *  - balanced () [] {} after comment stripping;
 *  - the precision statement the pipeline requires (highp float);
 *  - the DISPATCH contract: local_size_x/y = 8 — the C++ harness in
 *    gles/GlesPipeline.cpp divides the grid by exactly 8;
 *  - the JNI harness contract: the InBuf/OutBuf shader storage blocks
 *    declared with layout(std430, binding = 0/1) — the ES 3.10 explicit
 *    binding points the harness's glBindBufferBase(…, 0/1, …) attaches
 *    the buffers to, and the std430 packing the C++ side's tight float
 *    arrays assume — plus the uSize/uCell uniforms the harness sets.
 *    A plain `buffer InBuf { … }` (the old form, no std430/binding) is
 *    REJECTED: it relies on default packing while the C++ side assumes
 *    tight arrays, and without binding= the block-to-buffer attachment
 *    is undefined by this text.
 *
 * The real compile is device-test-pending (the owner's Measure run).
 * Pure JVM: no Android classes, no file IO.
 */
object GlesShaderValidator {

    /**
     * Every problem found in [source] (empty list = passed all checks).
     * Each entry is a human-readable one-liner for the test failure or
     * the debug log.
     */
    fun problems(source: String): List<String> {
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
        if (firstLine != "#version 310 es") {
            out += "first statement must be `#version 310 es` (got: $firstLine)"
        }
        out += balanceProblems(stripped)
        if (!stripped.contains("precision highp float;")) {
            out += "missing `precision highp float;`"
        }
        if (!stripped.contains("layout(local_size_x = 8, local_size_y = 8) in;")) {
            out += "missing `layout(local_size_x = 8, local_size_y = 8) in;` " +
                "(the C++ harness dispatches a grid divided by exactly 8)"
        }
        if (!stripped.contains("layout(std430, binding = 0) readonly buffer InBuf")) {
            out += "missing `layout(std430, binding = 0) readonly buffer InBuf` " +
                "(the C++ harness attaches the input SSBO to binding point 0 " +
                "with glBindBufferBase and assumes std430 tight float packing)"
        }
        if (!stripped.contains("layout(std430, binding = 1) writeonly buffer OutBuf")) {
            out += "missing `layout(std430, binding = 1) writeonly buffer OutBuf` " +
                "(the C++ harness attaches the output SSBO to binding point 1 " +
                "with glBindBufferBase and assumes std430 tight float packing)"
        }
        if (!stripped.contains("uniform ivec2 uSize;")) {
            out += "missing `uniform ivec2 uSize;` (the C++ harness sets it)"
        }
        if (!stripped.contains("uniform ivec4 uCell;")) {
            out += "missing `uniform ivec4 uCell;` (the C++ harness sets it)"
        }
        return out
    }

    /** True when [source] passes every check (the shipped source must). */
    fun validate(source: String): Boolean = problems(source).isEmpty()

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
