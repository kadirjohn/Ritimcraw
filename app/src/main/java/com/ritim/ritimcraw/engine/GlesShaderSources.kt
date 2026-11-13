package com.ritim.ritimcraw.engine

/**
 * GLSL ES shader sources — the single source of truth for the GPU
 * pipeline (owner request 2026-11-12, deliverable C; fragment chain added
 * by deliverable D, owner request 2026-11-12 §D).
 *
 * The source text lives HERE, not in a C++ string and not in an asset
 * file: the host validator (GlesShaderValidator) checks this exact text
 * in JVM tests, and the device compiles THIS EXACT TEXT through the JNI
 * seam (nativeGlesParity's source argument) — there is no second copy
 * to drift.
 *
 * Honesty level: the host validates SYNTAX + CONTRACT only (this host
 * has no GLSL compiler — no glslangValidator, no glslc); the real
 * compile is device-test-pending. No compile claim is made here.
 */
object GlesShaderSources {

    /**
     * Bilinear demosaic (compute variant, deliverable C) — the exact GLSL
     * counterpart of rawedit::demosaicBilinear (core/src/Develop.cpp), the
     * algorithm the owner pinned as the GPU parity target:
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
     *
     * SSBO BINDING (audit fix 2026-11-13): GLSL ES 3.10 supports explicit
     * layout(binding=N) on shader storage blocks — the ES-native
     * mechanism, replacing the old glShaderStorageBlockBinding lookup
     * (that function is desktop GL 4.2+ ONLY, not an ES command at all,
     * so eglGetProcAddress could only ever return null on ES and the
     * parity run stopped there). The blocks also declare std430
     * explicitly: the C++ side assumes tightly packed scalar float
     * arrays, and std430 guarantees exactly that packing (a plain
     * `buffer` declaration would rely on the default). The C++ harness
     * attaches the buffers to the SAME binding points with the core
     * ES 3.1 glBindBufferBase(…, 0, inBuf) / (…, 1, outBuf).
     */
    const val DEMOSAIC_COMPUTE: String = """#version 310 es

// Bilinear demosaic - the GLSL counterpart of rawedit::demosaicBilinear
// (core/src/Develop.cpp). Site codes 0=R 1=G 2=B.

precision highp float;
precision highp int;

layout(local_size_x = 8, local_size_y = 8) in;

uniform ivec2 uSize;   // CFA width/height in pixels
uniform ivec4 uCell;   // layoutFor cell: (cell[0][0], cell[0][1], cell[1][0], cell[1][1])

// std430 = tightly packed scalar float arrays (what the C++ side assumes);
// binding = the ES 3.10 explicit binding point, matched by the harness's
// glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, inBuf) and (..., 1, outBuf).
// readonly/writeonly state the access each side gets.
layout(std430, binding = 0) readonly buffer InBuf { float cfa[]; };   // stage-1-normalized CFA, row-major, w*h
layout(std430, binding = 1) writeonly buffer OutBuf { float rgb[]; };  // demosaiced scene-linear RGB, row-major, 3 per pixel

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

    /** The fullscreen vertex body shared by both versioned sources (zero drift). */
    private const val kFullscreenBody: String = """
// Fullscreen triangle-strip: 4 hard-coded NDC corners, no attributes,
// no varyings (the fragment passes use gl_FragCoord).

precision highp float;

const vec2 kPos[4] = vec2[4](vec2(0.0, 0.0), vec2(1.0, 0.0),
                             vec2(0.0, 1.0), vec2(1.0, 1.0));

void main() {
    gl_Position = vec4(kPos[gl_VertexID] * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * Fullscreen triangle-strip vertex shader (deliverable D) — the one
     * vertex stage every fragment pass in the chain draws with. Four
     * hard-coded corner positions, no attributes, no varyings, no
     * uniforms: the fragment passes derive their pixel from gl_FragCoord,
     * so there is nothing to link but the program itself. A draw is
     * glDrawArrays(GL_TRIANGLE_STRIP, 0, 4) with no VBO bound — the
     * corner array is indexed by gl_VertexID (ES 3.00).
     *
     * ES 3.0 (the develop chain is a fragment pipeline, not compute):
     * #version 300 es. No precision-sensitive work happens here.
     */
    const val FULLSCREEN_VERTEX: String = "#version 300 es\n" + kFullscreenBody

    /**
     * The SAME fullscreen vertex stage, #version 310 es (deliverable D):
     * GLSL ES forbids linking shaders with different #version lines into
     * one program, and the STORE_FRAGMENT readback pass is ES 3.10 (its
     * SSBO write needs 3.1 fragment-shader SSBO access), so its program
     * needs a 310-es vertex stage. The body past the version line is
     * VERBATIM the same text as FULLSCREEN_VERTEX (both compose the
     * shared [kFullscreenBody] above), so the two programs cannot drift:
     * the harness links FULLSCREEN_VERTEX + DEVELOP_FRAGMENT and
     * FULLSCREEN_VERTEX_310 + STORE_FRAGMENT, and both draw the same
     * fullscreen geometry the same way.
     */
    const val FULLSCREEN_VERTEX_310: String = "#version 310 es\n" + kFullscreenBody

    /**
     * Fused develop fragment pass (deliverable D) — the fragment-shader
     * counterpart of the CPU develop chain for a NEUTRAL grade, ONE PASS
     * (honest fusion: every covered stage is per-pixel pure, and the only
     * neighborhood read is the demosaic, which reads the stage-1 CFA
     * texture directly — fusing loses nothing and saves a full-frame
     * round trip between every stage).
     *
     * Stages covered (the parity-first set; CPU reference
     * rawedit::developCpu, core/src/Develop.cpp):
     *   1  black/white normalization per CFA slot — uBlack/uRange carry
     *      the FRAME-PREFERRED levels computed CPU-side (per-frame
     *      dynamicBlack/dynamicWhite when the frame carries them, else
     *      the container RawMeta black/white — the exact stage-1 fallback
     *      the baseline doc pins as parity-relevant), and the per-slot
     *      range guard (white > black ? white - black : 1.0);
     *   4  bilinear demosaic — the exact demosaicBilinear kernel (own
     *      site through, 8-neighborhood same-color average, clamp FIRST
     *      then site test, n==0 center fallback, all four CFA patterns
     *      through uCell);
     *   5  WB gains — multiply by uWb (the CPU 1/asShotNeutral vector,
     *      including the >0 guard's 1.0 fallback, computed CPU-side);
     *   6  camera -> linear Rec.709 — uCam2rgb is the CPU-BUILT matrix
     *      (rawedit::buildCameraToRec709: ForwardMatrix/ColorMatrix +
     *      calibration + the Bradford-adapted XYZ_D50 -> Rec.709), so
     *      the Bradford math is NEVER ported to GLSL; the C++ side
     *      uploads the CPU row-major array with glUniformMatrix3fv(...,
     *      GL_TRUE, ...), whose transpose=true makes the GLSL mat3 the
     *      SAME mathematical matrix, so `uCam2rgb * c` reproduces the
     *      CPU row-major multiply exactly. uHasProfile = 0 skips the
     *      multiply (no-profile clips / the parity shape);
     *  14  exposure 2^ev — uExposure (1.0 = neutral; the CPU guard
     *      exposureEv != 0 maps to exactly 1.0);
     *  OETF linearToRec709 — the exact Transfer.cpp curve (4.5*lin below
     *      0.018, else 1.099*pow(lin, 0.45) - 0.099), unclamped, exactly
     *      as the CPU tail leaves it; applied only when uOetf == 1.
     *
     * Stages NOT covered (honest): 2 lens shading, 3 highlight
     * reconstruction, 7-8 denoise, 9-13 tone controls, 15-24 highlights/
     * temp-tint/wheel/split/saturation/sharpness/detail/tonemap/LUT, and
     * the gyro warp — the BackendRules stage-coverage rules (commit d)
     * fall back to CPU with a reason line whenever one of those is active
     * in the grade.
     *
     * Parity shape: the harness runs THIS pass with uHasProfile = 0,
     * uExposure = 1.0, uOetf = 0 — stages 1+4+5, developLinear's exact
     * stage list — into an RGBA32F attachment, whose contents the
     * STORE_FRAGMENT pass moves to an SSBO for glMapBufferRange; the CPU
     * then clamps [0,1] and quantizes uint16 exactly like the
     * developLinear tail and compares (PASS <= 1 LSB, <= 0.01% mismatch,
     * the same verdict rules as the compute harness). The WB output is
     * deliberately NOT clamped in-shader (the baseline rule: do not
     * early-clamp scene-linear values) — the harness tail does the
     * clamp, matching developLinear's clamp-after-WB order.
     *
     * Input: uCfa — the RAW (NOT stage-1-normalized) CFA as a 2D integer
     * texture: GL_R16UI, uint16 samples 0..65535 in their native values,
     * uploaded row-major CPU order (texture row y = CFA row y).
     * Normalization happens HERE (stage 1 in-shader) so the pass covers
     * the full stage list. uBlack/uRange are per-slot in the CPU
     * cellSlot order: slot = (y&1)*2 + (x&1).
     *
     * Output: the RGBA32F color attachment (EXT_color_buffer_float,
     * gated at runtime — floatFboOk; RGBA32F, NOT RGBA16F, because the
     * 1-LSB parity budget of 1/65535 is tighter than float16's ~2^-11
     * step), scene-linear Rec.709 in rgb, alpha 1. The fragment's pixel
     * is derived from gl_FragCoord (the harness draws a w x h viewport);
     * the SSBO index the STORE pass writes is computed from the same
     * coordinate, so the readback lands in CPU row order with NO flip
     * anywhere in the parity path (the attachment's own visual
     * orientation is a transport detail nobody reads).
     *
     * ES 3.0 fragment chain (no compute, no SSBOs here — ES 3.00 has
     * neither in fragment shaders): #version 300 es. glReadPixels is
     * used NOWHERE (the recorded baseline invariant).
     */
    const val DEVELOP_FRAGMENT: String = """#version 300 es

// Fused develop pass - stages 1+4+5+6+14+OETF of rawedit::developCpu
// (core/src/Develop.cpp) for a neutral grade. See the Kotlin doc above.

precision highp float;
precision highp int;

uniform highp usampler2D uCfa;  // RAW CFA: GL_R16UI, uint16 samples 0..65535, row-major CPU order
uniform ivec2 uSize;            // CFA width/height in pixels
uniform ivec4 uCell;            // layoutFor cell: (cell[0][0], cell[0][1], cell[1][0], cell[1][1])
uniform vec4 uBlack;            // per-slot black levels, frame-preferred (CPU-computed)
uniform vec4 uRange;            // per-slot white-black ranges, guard included (CPU-computed)
uniform vec3 uWb;               // WB gains = 1/asShotNeutral, >0 guard included (CPU-computed)
uniform mat3 uCam2rgb;          // camera->Rec.709, the CPU row-major matrix uploaded with transpose=GL_TRUE
uniform int uHasProfile;        // 1 = apply uCam2rgb (stage 6); 0 = identity (no profile / parity shape)
uniform float uExposure;        // 2^ev (stage 14); 1.0 = neutral
uniform int uOetf;              // 1 = apply linearToRec709 (export shape); 0 = linear endpoint (parity)

out vec4 oCol;

// siteAt(x, y) = cell[y&1][x&1] - the CPU layoutFor lookup.
int siteAt(ivec2 p) {
    int row = p.y & 1;
    int col = p.x & 1;
    return row == 0 ? (col == 0 ? uCell.x : uCell.y)
                    : (col == 0 ? uCell.z : uCell.w);
}

// Stage 1: normalization per slot - the CPU stage-1 math, clamp included.
// slot = (y&1)*2 + (x&1) is the CPU cellSlot order of uBlack/uRange.
float stage1(ivec2 p) {
    int slot = (p.y & 1) * 2 + (p.x & 1);
    float v = (float(texelFetch(uCfa, p, 0).r) - uBlack[slot]) / uRange[slot];
    return clamp(v, 0.0, 1.0);
}

// The CPU kernel's 8-neighborhood offsets, same order (kDx, kDy).
const int kDx[8] = int[8](-1, 1, 0, 0, -1, -1, 1, 1);
const int kDy[8] = int[8](0, 0, -1, 1, -1, 1, -1, 1);

// The CPU OETF, rawedit::linearToRec709 (core/src/Transfer.cpp): 4.5*lin
// below 0.018, else 1.099*pow(lin, 0.45) - 0.099, UNCLAMPED (the CPU tail
// clamps after, in toByte; this pass leaves it to the caller).
float linearToRec709(float lin) {
    if (lin < 0.018) return 4.5 * lin;
    return 1.099 * pow(lin, 0.45) - 0.099;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    int maxI = uSize.x - 1;
    int maxJ = uSize.y - 1;
    int ownSite = siteAt(g);
    float px[3];
    for (int t = 0; t < 3; ++t) {
        if (t == ownSite) {
            px[t] = stage1(g);
        } else {
            float sum = 0.0;
            int n = 0;
            for (int k = 0; k < 8; ++k) {
                int nx = min(max(g.x + kDx[k], 0), maxI);
                int ny = min(max(g.y + kDy[k], 0), maxJ);
                ivec2 np = ivec2(nx, ny);
                if (siteAt(np) == t) {
                    sum += stage1(np);
                    n += 1;
                }
            }
            px[t] = n > 0 ? sum / float(n) : stage1(g);
        }
    }
    vec3 c = vec3(px[0], px[1], px[2]) * uWb;
    if (uHasProfile == 1) {
        c = uCam2rgb * c;
    }
    c *= uExposure;
    if (uOetf == 1) {
        c = vec3(linearToRec709(c.r), linearToRec709(c.g), linearToRec709(c.b));
    }
    oCol = vec4(c, 1.0);
}
"""

    /**
     * SSBO readback fragment pass (deliverable D parity harness) — moves
     * the developed RGBA32F color attachment into a shader storage buffer
     * so the harness can glMapBufferRange it back: glReadPixels is used
     * NOWHERE (the recorded baseline invariant; deliverable C reads back
     * the same way, through a mapped SSBO).
     *
     * ES 3.1 (fragment shaders gain SSBO access in 3.1): #version 310 es.
     * One fullscreen draw over a w x h viewport; each invocation
     * texelFetches its own pixel of the source texture and writes 3
     * floats (rgb; alpha is always 1.0 and never read) at the SAME
     * CPU-row-major index the parity comparison uses. The harness draws
     * this to a throwaway w x h RGBA8 FBO — never to an FBO that still
     * has the source texture attached (that would be a feedback loop).
     *
     * The pixel comes from gl_FragCoord (the same coordinate the develop
     * pass used for its CFA fetch and output), so the readback lands in
     * CPU row order with no flip anywhere.
     *
     * The pass writes a throwaway color output too: a fragment shader
     * with ZERO declared outputs is spec-gray on some ES drivers (the
     * spec's "at least one output" wording is desktop-GL-flavored), so
     * oCol gets a benign write while the SSBO carries the real payload.
     * The harness draws this pass to a throwaway RGBA8 FBO whose contents
     * nobody reads — the SSBO is the output.
     */
    const val STORE_FRAGMENT: String = """#version 310 es

// Readback pass: developed color attachment -> SSBO, one texel per
// invocation, CPU row-major order. See the Kotlin doc above.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;  // the developed RGBA32F color attachment (as a texture)
uniform ivec2 uSize;          // width/height in pixels

// std430 = tightly packed scalar float arrays (what the C++ side assumes);
// binding 0 = the ES 3.10 explicit binding point the harness's
// glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf) attaches to.
layout(std430, binding = 0) writeonly buffer OutBuf { float rgb[]; };  // 3 floats per pixel, row-major

// Throwaway color output: the harness never reads the RGBA8 target this
// pass draws to (the SSBO is the real output); the write keeps the shader
// legal on drivers that dislike zero-output fragment shaders.
out vec4 oCol;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) return;
    vec3 c = texelFetch(uSrc, g, 0).rgb;
    int base = (g.y * uSize.x + g.x) * 3;
    rgb[base + 0] = c.r;
    rgb[base + 1] = c.g;
    rgb[base + 2] = c.b;
    oCol = vec4(c, 1.0);
}
"""
}
