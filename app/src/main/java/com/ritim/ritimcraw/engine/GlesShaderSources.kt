package com.ritim.ritimcraw.engine

/**
 * GLSL ES 3.10 shader sources — the single source of truth for the GPU
 * pipeline (owner request 2026-11-12, deliverable C).
 *
 * The source text lives HERE, not in a C++ string and not in an asset
 * file: the host validator (GlesShaderValidator) checks this exact text
 * in JVM tests, and the device compiles THIS EXACT TEXT through the JNI
 * seam (nativeGlesParity's computeSource argument) — there is no second
 * copy to drift.
 *
 * Honesty level: the host validates SYNTAX + CONTRACT only (this host
 * has no GLSL compiler — no glslangValidator, no glslc); the real
 * compile is device-test-pending. No compile claim is made here.
 */
object GlesShaderSources {

    /**
     * Bilinear demosaic — the exact GLSL counterpart of
     * rawedit::demosaicBilinear (core/src/Develop.cpp), the algorithm the
     * owner pinned as the GPU parity target:
     *
     *  - own site passes through unchanged;
     *  - every other channel averages the same-site samples among the 8
     *    neighbors (kDx/kDy below, the CPU kernel's order);
     *  - neighbor coordinates clamp to the image FIRST, then the site
     *    test decides membership (the CPU order);
     *  - no same-site neighbor -> fall back to the center sample;
     *  - site codes 0=R 1=G 2=B; the 2x2 layout arrives as
     *    uCell = (cell[0][0], cell[0][1], cell[1][0], cell[1][1]) per the
     *    layoutFor table, so ONE source serves all four patterns.
     *
     * Input: the stage-1-normalized CFA (black/white per slot, clamped
     * [0,1]) as a scalar float shader storage buffer. Output:
     * scene-linear RGB, 3 tightly packed floats per pixel, white balance
     * NOT applied (the harness applies the developLinear stage-5 tail on
     * the readback, isolating stage 4). No textures, no samplers, no
     * image units, no float framebuffers — GLES 3.1 core only.
     */
    const val DEMOSAIC_COMPUTE: String = """#version 310 es

// Bilinear demosaic - the GLSL counterpart of rawedit::demosaicBilinear
// (core/src/Develop.cpp). Site codes 0=R 1=G 2=B.

precision highp float;
precision highp int;

layout(local_size_x = 8, local_size_y = 8) in;

uniform ivec2 uSize;   // CFA width/height in pixels
uniform ivec4 uCell;   // layoutFor cell: (cell[0][0], cell[0][1], cell[1][0], cell[1][1])

buffer InBuf { float cfa[]; };   // stage-1-normalized CFA, row-major, w*h
buffer OutBuf { float rgb[]; };  // demosaiced scene-linear RGB, row-major, 3 per pixel

// The CPU kernel's 8-neighborhood offsets, same order (kDx, kDy).
const int kDx[8] = int[8](-1, 1, 0, 0, -1, -1, 1, 1);
const int kDy[8] = int[8](0, 0, -1, 1, -1, 1, -1, 1);

// siteAt(x, y) = cell[y&1][x&1] - the CPU layoutFor lookup.
int siteAt(ivec2 p) {
    int row = p.y & 1;
    int col = p.x & 1;
    return row == 0 ? (col == 0 ? uCell.x : uCell.y)
                    : (col == 0 ? uCell.z : uCell.w);
}

float cfaAt(ivec2 p) {
    return cfa[p.y * uSize.x + p.x];
}

void main() {
    ivec2 g = ivec2(gl_GlobalInvocationID.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) return;
    int maxI = uSize.x - 1;
    int maxJ = uSize.y - 1;
    int ownSite = siteAt(g);
    float px[3];
    for (int t = 0; t < 3; ++t) {
        if (t == ownSite) {
            px[t] = cfaAt(g);
        } else {
            float sum = 0.0;
            int n = 0;
            for (int k = 0; k < 8; ++k) {
                int nx = min(max(g.x + kDx[k], 0), maxI);
                int ny = min(max(g.y + kDy[k], 0), maxJ);
                ivec2 np = ivec2(nx, ny);
                if (siteAt(np) == t) {
                    sum += cfaAt(np);
                    n += 1;
                }
            }
            px[t] = n > 0 ? sum / float(n) : cfaAt(g);
        }
    }
    int base = (g.y * uSize.x + g.x) * 3;
    rgb[base + 0] = px[0];
    rgb[base + 1] = px[1];
    rgb[base + 2] = px[2];
}
"""
}
