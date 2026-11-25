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

    /*
     * The develop fragment family (deliverable D + the spatial-NR port's
     * split). The FUSED pass — recomposed below from the shared text
     * fragments, byte-identical to the pre-split constant — is the
     * fragment-shader counterpart of the CPU develop chain for a NEUTRAL
     * grade, ONE PASS (honest fusion: every covered stage is per-pixel
     * pure, and the only
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
     *   2  lens shading — the exact rawedit::sampleShading bilinear on
     *      the clip's 4-plane map (uShading, GL_R32F, planes stacked
     *      vertically, read with texelFetch: R32F is not texture-
     *      filterable in ES 3.0, and the CPU's own bilinear is carried
     *      verbatim anyway). Applied only when uHasShading = 1 — the
     *      CPU's own stage-2 gate (grade.keepVignetting && map present)
     *      computed CPU-side, because the fragment path KNOWS the map
     *      state (BackendRules.fragmentShadingApplies; the CPU path
     *      keeps its own identical guard — the asymmetry is documented
     *      there);
     *   3  highlight reconstruction — the CPU stage-3 cell pass
     *      verbatim (per-color minV/clipped/present over the site's own
     *      2x2 cell, ceiling from unclipped colors with the all-clipped
     *      fallback, cap = ceiling / gain[color] applied only to sites
     *      >= 1.0), gated by uHr (grade.highlightReconstruction); the
     *      per-color stats are recomputed for each site's own cell —
     *      identical math to the CPU's once-per-cell computation, which
     *      the same cell's sites all read;
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
     *   9  black point — uBlackPoint carries the RAW grade.blackPoint
     *      value (0.0 = the CPU's own `if (grade.blackPoint != 0.0f)`
     *      skip, so the unset uniform's 0.0 default is exactly the fast
     *      path), and the shader runs the CPU stage-9 endpoint remap
     *      verbatim: lift = -0.12 * bp/100, x' = lift + (1 - lift) * x
     *      (toe remap pinned at white; k = 0.12 per A6, owner request
     *      2026-11-23);
     *  10  white point — uWhitePoint carries the RAW grade.whitePoint
     *      value and the shader runs the CPU stage-10 uniform scale
     *      verbatim: x' = x / (1 - 0.25*wp/100) for wp > 0, x' = x *
     *      (1 + 0.25*wp/100) for wp <= 0 (shoulder remap pinned at
     *      black);
     *  11  shadows — uShadows carries the RAW grade.shadows value and the
     *      shader runs the CPU stage-11 dark-band gain verbatim: wDark =
     *      smoothstepf(0.18, 0, mx), gain = 2^(strength * wDark) with
     *      strength = shadows/100 (the same smoothstepf helper the CPU
     *      core/src/Develop.cpp ships, ported verbatim);
     *  12  midtones — uMidtones carries the RAW grade.midtones value and
     *      the shader runs the CPU stage-12 midgray tent verbatim: wMid
     *      = smoothstepf(0, 0.18, mx) * (1 - smoothstepf(0.18, 0.72,
     *      mx)), gain = 2^(strength * wMid);
     *  13  contrast — uContrast carries the RAW grade.contrast value and
     *      the shader runs the CPU stage-13 slope verbatim: x' = 0.18 +
     *      (x - 0.18) * (1 + contrast/100) around the midgray pivot (the
     *      CPU local c renamed for the pixel vector c);
     *  14  exposure 2^ev — uExposure (1.0 = neutral; the CPU guard
     *      exposureEv != 0 maps to exactly 1.0);
     *  15  highlights — uHighlights carries the RAW grade.highlights value
     *      (0.0 = the CPU's own `if (grade.highlights != 0.0f)` skip, so
     *      the unset uniform's 0.0 default is exactly the fast path), and
     *      the shader runs the CPU stage-15 shoulder verbatim
     *      (core/src/Develop.cpp): knee 0.40, span = 1 - knee, s =
     *      -highlights / 100 (recovery -> +, lift -> -), the monotone
     *      shoulder knee + span * (t / (1 + t)) on pixels whose mx >
     *      knee, the blend mx + s * (shoulder - mx), and the uniform
     *      per-pixel gain newMx / mx (neutrals stay neutral);
     *  16  temperature — uWbManual carries the CPU-computed !grade.asShot
     *      gate (an unset int uniform defaults to 0 = as-shot = the CPU's
     *      own skip, so the frozen parity harness never trips the stage),
     *      uTempK carries the RAW grade.tempK value, and the shader runs
     *      the CPU stage-16 math verbatim: tempShift = log2(tempK/5600)
     *      with the tempK > 0 guard and the |tempShift| > 1e-4 threshold,
     *      r *= 2^(0.35*shift), g *= sqrt(warm*cool), b *= 2^(-0.35*shift);
     *  17  tint — uTint carries the RAW grade.tint value behind the same
     *      uWbManual gate, and the shader runs the CPU stage-17 math
     *      verbatim: g *= 2^(0.4 * tint/100);
     *  18  color wheel — the six uWheel* uniforms carry the RAW grade
     *      offsets (each 0.0 default is the CPU's own neutral, so the
     *      unset-uniform shape skips the stage exactly like the CPU's
     *      wheelActive check), and the shader runs the CPU stage-18 blend
     *      verbatim: the tone-slider band weights (an exact partition of
     *      unity), the weighted x/y blends, then r *= 2^(0.5*xBlend),
     *      g *= 2^(0.5*yBlend), b *= 2^(-0.5*xBlend);
     *  19  split tone — uSplitActive carries the CPU-computed
     *      splitShadowHue >= 0 || splitHighlightHue >= 0 gate. The -1 =
     *      off sentinel CANNOT live on the raw hue uniforms: the GLSL
     *      default of an unset float uniform is 0.0, a VALID hue that
     *      would flip the CPU's `>= 0.0` gate on and break the frozen
     *      parity harness, so the gate is hoisted CPU-side exactly like
     *      uWbManual/uHr. Inside the gate uSplitShadowHue /
     *      uSplitHighlightHue / uSplitBalance carry the RAW grade values
     *      and the shader runs the CPU stage-19 math verbatim: m = 0.18 *
     *      2^(balance/100), the hueGains raised-cosine windows (120
     *      degrees apart, average 1; the CPU lambda's channel index
     *      renamed for the pixel vector c), the per-side >= 0 fallback to
     *      unity gains, wShadow/wHighlight smoothstepf bands, and the
     *      diagonal per-channel gain 2^(0.35 * (wShadow*(sh-1) +
     *      wHighlight*(hl-1)));
     *  20  saturation — uSaturation carries the RAW grade.saturation
     *      value and the shader runs the CPU stage-20 chroma scaling
     *      verbatim: f = 2^(0.75 * sat/100), luma = 0.2126r + 0.7152g +
     *      0.0722b, x' = luma + (x - luma) * f;
     *  23  tonemap — uTonemap, the exact tonemapFrameImpl math
     *      (core/src/Develop.cpp): 1 = ACES (the project's Narkowicz
     *      2015 fit, per-component, [0,1]-clamped); 2-5 = AgX (the
     *      essential fit: inset, log2 encode around 0.18 clamped to the
     *      [-12.47393, 4.026069] EV window, the 6th-order contrast
     *      polynomial, the look variant, outset, [0,1] clamp,
     *      pow 2.2). The AgX pow-2.2 tail is part of the CPU tonemap
     *      math itself (the look's output encoding is what the CPU
     *      leaves in the buffer); the Rec.709 OETF composes ON TOP of
     *      it on the CPU side too (the JNI bridge tail) — exactly one
     *      OETF on each side, no double encode. Punchy (2) keeps the
     *      legacy minimal-fit order (luma from PRE-power values); the
     *      other looks use the ASC CDL order (slope, power, THEN luma
     *      of the powered values, then sat) — the CPU distinction is
     *      preserved;
     *  OETF linearToRec709 — the exact Transfer.cpp curve (4.5*lin below
     *      0.018, else 1.099*pow(lin, 0.45) - 0.099), unclamped, exactly
     *      as the CPU tail leaves it; applied only when uOetf == 1.
     *
     * Stages NOT covered by THIS fused text (honest): none of the
     * fragment-covered stages any more — the gyro warp joined the
     * covered set with the warp port (it is not a develop stage at
     * all: the CPU runs it after developCpu, on the linear output), and
     * the session chain runs it as the separate WARP pass between the
     * unsharp passes and the tail, exactly where the CPU pipeline
     * applies it; the warp is UNCONDITIONALLY covered (pure math, no
     * texture-format leg), so BackendRules carries no warp token in
     * its runtime vocabulary. Stages 21-22
     * (sharpness/detail) are fragment-covered since the sharpness/detail
     * port, but NOT by this fused pass: they are neighborhood stages,
     * so the session chain runs them as the separate SHARPNESS_H/
     * SHARPNESS_V/DETAIL_H/DETAIL_V passes between
     * DEVELOP_FRAGMENT_POST (the 9-20 pointwise draw) and
     * DEVELOP_FRAGMENT_TAIL (the 23+24+OETF draw) below; the fused text
     * keeps its no-unsharp shape because the frozen device parity
     * harness compiles it as ONE draw. Stage 24 (LUT) joined the
     * covered set with the LUT port: the shader carries the CPU's exact
     * role/transfer contract behind uLutStrength, sampling the parsed
     * .cube through a 3D RGBA16F texture (hardware trilinear, the
     * CPU's texel-center mapping) — CONDITIONAL at runtime: when the
     * device's LUT texture path is unavailable the job falls back with
     * the lut token (the session's upload check latches unhealthy; see
     * BackendRules.fragmentUncoveredStages' lutTexturePathOk). Stages
     * 7-8 (spatial NR) are fragment-covered since the NR port, but NOT
     * by this fused pass: they are neighborhood stages, so the session
     * chain runs them as the separate NR_* passes between
     * DEVELOP_FRAGMENT_PRE and DEVELOP_FRAGMENT_POST (below); the
     * fused text keeps its no-NR shape because the frozen device
     * parity harness compiles it as ONE draw.
     *
     * Parity shape: the harness runs THREE cases against THIS pass. Case
     * 0 pins uHasShading = 0, uHr = 0, uHasProfile = 0, uExposure = 1.0,
     * uOetf = 0 — stages 1+4+5, developLinear's exact stage list (the
     * case must stay byte-honest regardless of the clip's real map, so
     * the existing verdict keeps its meaning). Case 1 runs the DEFAULT
     * grade's real stage list — uHasShading = 1 with the clip's actual
     * 4-plane map uploaded as a GL_R32F texture, uHr = 1 with
     * uHrGain = guarded wbGains (meta.wbGains with the >0 fallback — a
     * DIFFERENT vector from uWb's 1/asShotNeutral), uHasProfile = 0,
     * uExposure = 1.0, uOetf = 0 — against a CPU reference the harness
     * computes in C++ from the exported rawedit primitives (stage 1 +
     * sampleShading + the stage-3 pass + demosaicBilinear + the WB
     * tail), so core/src/Develop.cpp stays untouched. Case 2 sweeps
     * uTonemap over modes 1-5 (every shipped ACES/AgX look): per mode
     * the reference is the REAL exported rawedit::developCpu run with a
     * tonemap-only Grade (everything else default, so the stage list is
     * exactly 1+2+3+4+5+23 — the REAL production develop path) on an
     * identity-matrices COPY of the meta (forwardMatrix AND colorMatrix
     * identity -> buildCameraToRec709 returns false -> stage 6 skipped,
     * the same shape uHasProfile = 0 pins), quantized clamp [0,1] +
     * lround x65535; the fragment pass runs case-1's stage-2/3 gates
     * with uTonemap = mode, uHasProfile = 0, uExposure = 1.0, uOetf = 0
     * (the AgX pow-2.2 tail is in-shader tonemap math; the Rec.709 OETF
     * stays OFF on both sides — exactly one OETF each, no double
     * encode); the verdict is the WORST case across the five modes,
     * samples accumulate 5 x n x 3. All cases land
     * in an RGBA32F attachment, whose contents the STORE_FRAGMENT pass
     * moves to an SSBO for glMapBufferRange; the CPU then clamps [0,1]
     * and quantizes uint16 exactly like the developLinear tail and
     * compares (PASS <= 1 LSB, <= 0.01% mismatch, the same verdict
     * rules as the compute harness). The WB output is deliberately NOT
     * clamped in-shader (the baseline rule: do not early-clamp
     * scene-linear values) — the harness tail does the clamp, matching
     * developLinear's clamp-after-WB order.
     *
     * The ten pointwise grade stages (9-13, 16-20) keep that verdict
     * byte-honest by construction: the harness NEVER binds their
     * uniforms, and every one of those uniforms' GLSL defaults (0.0 for
     * the raw values, 0 for the CPU-computed gates uWbManual and
     * uSplitActive) is the EXACT neutral fast path the CPU's own guards
     * skip — no stage fires on an unset uniform, so the harness's cases
     * 0/1/2 still measure exactly the stage lists they pinned before
     * the port.
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
    // ---- the develop chain's shared text fragments (the NR port's split) --
    //
    // The session chain draws the develop stages as PRE -> [NR] ->
    // POST(9-20) -> [SHARPNESS/DETAIL] -> [WARP, gated per frame] ->
    // TAIL(23+24+OETF) through RGBA32F intermediates
    // (DEVELOP_FRAGMENT_PRE, the NR_* passes, DEVELOP_FRAGMENT_POST, the
    // four unsharp passes, the WARP pass and DEVELOP_FRAGMENT_TAIL
    // below); the fused parity pass recomposes
    // THE SAME fragments, so the split chain and the frozen harness cannot
    // drift. Each fragment is the fused pass's own text, cut at a copy
    // boundary (the CPU's stage 6/9 boundary - the NR stages' position in
    // rawedit::developCpu - and the 20/23 boundary, the unsharp stages'
    // position). The WARP pass is NOT a develop stage and is not part of
    // the fused recomposition: the CPU pipeline runs the warp after
    // developCpu on its linear output, so the chain draws it between the
    // unsharp passes and the tail, and the fused parity pass never
    // includes it (the parity harness pins the warp off).

    /** Stages 1-6's uniforms (the pre-pass set) - the fused pass's own text. */
    private const val kDevelopPreUniforms: String = """uniform highp usampler2D uCfa;  // RAW CFA: GL_R16UI, uint16 samples 0..65535, row-major CPU order
uniform ivec2 uSize;            // CFA width/height in pixels
uniform ivec4 uCell;            // layoutFor cell: (cell[0][0], cell[0][1], cell[1][0], cell[1][1])
uniform vec4 uBlack;            // per-slot black levels, frame-preferred (CPU-computed)
uniform vec4 uRange;            // per-slot white-black ranges, guard included (CPU-computed)
uniform int uHasShading;        // 1 = apply stage 2 (the CPU gate keepVignetting && map present, CPU-computed)
uniform highp sampler2D uShading; // the clip's 4-plane shading map: GL_R32F, planes stacked vertically
uniform ivec2 uShadingSize;     // one plane's size: (shadingW, shadingH)
uniform int uHr;                // 1 = apply stage 3 (grade.highlightReconstruction)
uniform vec3 uHrGain;           // stage-3 WB gains = meta.wbGains with the >0 guard (CPU-computed)
uniform vec3 uWb;               // WB gains = 1/asShotNeutral, >0 guard included (CPU-computed)
uniform mat3 uCam2rgb;          // camera->Rec.709, the CPU row-major matrix uploaded with transpose=GL_TRUE
uniform int uHasProfile;        // 1 = apply uCam2rgb (stage 6); 0 = identity (no profile / parity shape)
"""

    /**
     * Stages 9-20's uniforms (the pointwise post-pass set) - the fused
     * pass's own text, cut at the 20/23 boundary (the CPU's stage-21/22
     * position, where the unsharp passes sit between the pointwise stages
     * and the tail).
     */
    private const val kDevelopPointwiseUniforms: String = """uniform float uExposure;        // 2^ev (stage 14); 1.0 = neutral
uniform float uHighlights;      // raw grade.highlights (stage 15); 0.0 = the CPU's own skip
uniform float uBlackPoint;      // raw grade.blackPoint (stage 9); 0.0 = the CPU's own skip
uniform float uWhitePoint;      // raw grade.whitePoint (stage 10); 0.0 = the CPU's own skip
uniform float uShadows;         // raw grade.shadows (stage 11); 0.0 = the CPU's own skip
uniform float uMidtones;        // raw grade.midtones (stage 12); 0.0 = the CPU's own skip
uniform float uContrast;        // raw grade.contrast (stage 13); 0.0 = the CPU's own skip
uniform int uWbManual;          // 1 = !grade.asShot (stages 16+17 gate, CPU-computed); 0 = as-shot, skip both
uniform float uTempK;           // raw grade.tempK (stage 16); the shader computes tempShift = log2(uTempK/5600)
uniform float uTint;            // raw grade.tint (stage 17); 0.0 = the CPU's own skip
uniform float uWheelShadowX;    // raw grade.wheelShadowX (stage 18); the shader blends the three bands
uniform float uWheelShadowY;
uniform float uWheelMidX;
uniform float uWheelMidY;
uniform float uWheelHighX;
uniform float uWheelHighY;
uniform int uSplitActive;       // 1 = splitShadowHue >= 0 || splitHighlightHue >= 0 (stage 19 gate, CPU-computed)
uniform float uSplitShadowHue;      // raw grade.splitShadowHue; -1 = off (only read when uSplitActive == 1)
uniform float uSplitHighlightHue;   // raw grade.splitHighlightHue; -1 = off (only read when uSplitActive == 1)
uniform float uSplitBalance;       // raw grade.splitBalance
uniform float uSaturation;     // raw grade.saturation (stage 20); 0.0 = the CPU's own skip
"""

    /**
     * Stages 23+24+OETF's uniforms (the tail-pass set) - the fused pass's
     * own text, the 20/23 cut's other half.
     */
    private const val kDevelopTailUniforms: String = """uniform int uTonemap;           // grade.tonemap (stage 23): 0 off, 1 ACES, 2-5 AgX Punchy/Golden/Greyscale/Base
uniform int uOetf;              // 1 = apply linearToRec709 (export shape); 0 = linear endpoint (parity)
uniform float uLutStrength;      // raw grade.lutStrength (stage 24), ALREADY [0,1] (GradeK percent / 100, mapped Kotlin-side); 0.0 = the CPU's own skip
uniform highp sampler3D uLut;    // the parsed .cube as RGBA32F (texelFetch, trilinear by hand in lutSample); unit 5; a dummy 2x2x2 table when no LUT is bound
uniform int uLutSize;            // the table's LUT_3D_SIZE N (2..128); 0 = no table uploaded (the strength gate keeps the sampler unread)
uniform vec3 uLutDomainMin;      // the .cube DOMAIN_MIN per axis (default 0,0,0)
uniform vec3 uLutDomainMax;      // the .cube DOMAIN_MAX per axis (default 1,1,1)
uniform int uLutRole;            // rawedit LutRole: 0 LegacyLinear709, 1 DisplayLook, 2 SceneLook, 3 SceneToDisplay
uniform int uLutPassthrough;     // 1 = the binding degenerates to the plain sampler (both matrices identity, both transfers Linear)
uniform int uLutInTransfer;      // rawedit TransferId of the table's input encoding (0 Linear .. 12 Cineon)
uniform int uLutOutTransfer;     // rawedit TransferId of the table's output encoding
uniform mat3 uLutWorkingToInput;  // working (linear 709) -> LUT input gamut, row-major uploaded with transpose=GL_TRUE
uniform mat3 uLutOutputToWorking; // LUT output gamut -> working, row-major uploaded with transpose=GL_TRUE
"""

    /** The shared single color output (the fused pass's own line). */
    private const val kDevelopOutDecl: String = "out vec4 oCol;\n"

    /** Stages 1-6's helpers (siteAt .. kDx/kDy) - the fused pass's own text. */
    private const val kDevelopPreHelpers: String = """// siteAt(x, y) = cell[y&1][x&1] - the CPU layoutFor lookup.
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

// Stage 2: the CPU rawedit::sampleShading bilinear, verbatim (core/src/
// Develop.cpp). u/v are the CPU's normalized sensor coordinates (x*du,
// y*dv with du = 1/(w-1) guard), plane is the CPU cellSlot order the map
// is stored in. The map rides uShading as GL_R32F with the 4 planes
// stacked vertically (texel y = plane * shadingH + row), so texelFetch
// reads p[plane * shadingW * shadingH + y * shadingW + x] exactly like
// the CPU's row-major, plane-major indexing - R32F is not texture-
// filterable in ES 3.0, and the CPU bilinear is carried verbatim anyway.
// The CPU's two data-shape guards are carried by the uniform gate, not
// duplicated in-shader: the empty-map guard (return 1.0) is uHasShading
// = 0 (the CPU-side gate computed the map state), and the plane-range
// guard is structurally satisfied - cellSlot is always 0..3 and the
// container populates exactly 4 planes whenever it populates the map
// (core/src/Source.cpp), so the harness uploads the texture only in
// that shape and plane is never out of range.
float sampleShading(float u, float v, int plane) {
    float fx = min(max(u, 0.0), 1.0) * (float(uShadingSize.x) - 1.0);
    float fy = min(max(v, 0.0), 1.0) * (float(uShadingSize.y) - 1.0);
    int x0 = int(fx);
    int y0 = int(fy);
    int x1 = min(x0 + 1, uShadingSize.x - 1);
    int y1 = min(y0 + 1, uShadingSize.y - 1);
    float tx = fx - float(x0);
    float ty = fy - float(y0);
    int planeOffset = plane * uShadingSize.y;
    float a = texelFetch(uShading, ivec2(x0, planeOffset + y0), 0).r
        + (texelFetch(uShading, ivec2(x1, planeOffset + y0), 0).r
        - texelFetch(uShading, ivec2(x0, planeOffset + y0), 0).r) * tx;
    float b = texelFetch(uShading, ivec2(x0, planeOffset + y1), 0).r
        + (texelFetch(uShading, ivec2(x1, planeOffset + y1), 0).r
        - texelFetch(uShading, ivec2(x0, planeOffset + y1), 0).r) * tx;
    return a + (b - a) * ty;
}

// The post-stage-2 CFA value at p: stage 1 clamped [0,1], then the
// shading gain (which may push it above 1.0 - the CPU multiplies the
// normalized buffer, and the stage-3 cap reads exactly these values).
// uHasShading carries the CPU's own stage-2 gate (keepVignetting && map
// present) computed CPU-side; 0 skips the gain exactly like the CPU.
float normAt(ivec2 p) {
    float v = stage1(p);
    if (uHasShading == 1) {
        float du = uSize.x > 1 ? 1.0 / float(uSize.x - 1) : 0.0;
        float dv = uSize.y > 1 ? 1.0 / float(uSize.y - 1) : 0.0;
        int slot = (p.y & 1) * 2 + (p.x & 1);
        v *= sampleShading(float(p.x) * du, float(p.y) * dv, slot);
    }
    return v;
}

// Stage 3: highlight reconstruction - the CPU stage-3 cell pass,
// verbatim (core/src/Develop.cpp). The CPU computes the per-color stats
// once per 2x2 cell and applies the cap in a second pass over the same
// cell; a fragment invocation sees only its own pixel, so hrAt
// recomputes ITS OWN cell's stats (the same four normAt reads the CPU
// made) - identical math, identical answers, just recomputed per site.
// A neighbor in a DIFFERENT cell gets that neighbor's own cell ceiling,
// exactly like the CPU (stage 3 caps the whole norm buffer before the
// demosaic reads it).
float hrAt(ivec2 p) {
    if (uHr != 1) return normAt(p);
    int x0 = (p.x / 2) * 2;
    int y0 = (p.y / 2) * 2;
    int x1 = min(x0 + 1, uSize.x - 1);
    int y1 = min(y0 + 1, uSize.y - 1);

    // First pass: per-color minV / clipped / present over the cell's
    // sites (the CPU's exact loop, colors from layout.cell[yy&1][xx&1]).
    float minV[3];
    bool clipped[3];
    bool present[3];
    for (int c = 0; c < 3; ++c) {
        minV[c] = -1.0;
        clipped[c] = false;
        present[c] = false;
    }
    for (int yy = y0; yy <= y1; ++yy) {
        for (int xx = x0; xx <= x1; ++xx) {
            int color = siteAt(ivec2(xx, yy));
            float v = normAt(ivec2(xx, yy));
            present[color] = true;
            if (minV[color] < 0.0 || v < minV[color]) minV[color] = v;
            if (v >= 1.0) clipped[color] = true;
        }
    }

    // Ceiling from colors that still have unclipped sites.
    float ceiling = -1.0;
    for (int c = 0; c < 3; ++c) {
        if (!present[c] || clipped[c]) continue;
        float level = minV[c] * uHrGain[c];
        if (ceiling < 0.0 || level < ceiling) ceiling = level;
    }
    // Every color clipped: fall back to the lowest level overall
    // (uniform fully-clipped patches stay at 1.0).
    if (ceiling < 0.0) {
        for (int c = 0; c < 3; ++c) {
            if (!present[c]) continue;
            float level = minV[c] * uHrGain[c];
            if (ceiling < 0.0 || level < ceiling) ceiling = level;
        }
    }
    if (ceiling < 0.0) return normAt(p);

    // Second pass: cap only the clipped sites of this cell. The CPU caps
    // the whole buffer's clipped sites; every site of this cell is one
    // invocation of its own, so capping THIS site reproduces the CPU's
    // buffer exactly. cap = ceiling / gain[wbIndex(color)] with the CPU's
    // wbIndex(siteColor) = siteColor.
    float v = normAt(p);
    if (v < 1.0) return v;
    float cap = ceiling / uHrGain[siteAt(p)];
    return v > cap ? cap : v;
}

// The CPU kernel's 8-neighborhood offsets, same order (kDx, kDy).
const int kDx[8] = int[8](-1, 1, 0, 0, -1, -1, 1, 1);
const int kDy[8] = int[8](0, 0, -1, 1, -1, 1, -1, 1);
"""

    /**
     * The POST stages' OETF helper (linearToRec709) - the fused pass's own
     * text, first piece of the 20/23 helpers cut. Pure math, no uniforms;
     * only the tail pass's OETF block calls it (the pointwise stages 9-20
     * never do), so it belongs to the TAIL pass's helper set.
     */
    private const val kDevelopOetfHelper: String = """// The CPU OETF, rawedit::linearToRec709 (core/src/Transfer.cpp): 4.5*lin
// below 0.018, else 1.099*pow(lin, 0.45) - 0.099, UNCLAMPED (the CPU tail
// clamps after, in toByte; this pass leaves it to the caller).
float linearToRec709(float lin) {
    if (lin < 0.018) return 4.5 * lin;
    return 1.099 * pow(lin, 0.45) - 0.099;
}
"""

    /** Stages 9-20's helpers (smoothstepf + hueGains) - the pointwise pass's own text. */
    private const val kDevelopPointwiseHelpers: String = """// The CPU rawedit::smoothstepf (core/src/Develop.cpp), verbatim: Hermite
// smoothstep with clamped edges, 0 for x <= e0, 1 for x >= e1. The tone
// stages (11, 12) and the color stages (18, 19) blend their band remaps in
// with this so each control stays inside its tonal region.
float smoothstepf(float e0, float e1, float x) {
    float t = min(max((x - e0) / (e1 - e0), 0.0), 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Stage 19's hue -> per-channel gain windows, the CPU's hueGains lambda
// verbatim (core/src/Develop.cpp): raised-cosine windows 120 degrees apart
// (red peaks at 0, green 120, blue 240), each rescaled by 2 so its average
// over the circle is 1 - the tint pushes around unity instead of lifting
// the whole band. The CPU's loop counter is named c (the pixel vector is c
// here); this helper's channel index is ch - same loop, renamed honestly.
vec3 hueGains(float hueDeg) {
    vec3 gains;
    for (int ch = 0; ch < 3; ++ch) {
        float center = 120.0 * float(ch);  // r, g, b peaks (the explicit conversion the Adreno compiler requires; 120.0f * c is implicit on the CPU side)
        float d = abs(hueDeg - center);
        if (d > 180.0) d = 360.0 - d;  // circular distance
        gains[ch] = 2.0 * (0.5 + 0.5 * cos(d * 3.14159265 / 180.0));
    }
    return gains;
}
"""

    /**
     * Stages 23+24's helpers (the tonemap block + the LUT block) - the
     * fused pass's own text, the 20/23 helpers cut's tail half. The tonemap
     * block reads uTonemap; the LUT block reads the uLut* uniforms - both
     * are the tail pass's own set.
     */
    private const val kDevelopTailHelpers: String = """// Stage 23: tonemap - the CPU rawedit::tonemapFrameImpl math verbatim
// (core/src/Develop.cpp). Every constant, guard and evaluation order is
// the CPU's; no GLSL built-in substitutions (the CPU helpers carry
// explicitly, including the clamps that keep pow() bases non-negative -
// GLSL pow of a negative base is undefined for the same reason the CPU
// guards it).
// ACES: the project's Narkowicz 2015 fit (NOT full ACES), per-component,
// with the CPU's den>0 guard and [0,1] clamp.
float acesFit(float x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    float num = x * (a * x + b);
    float den = x * (c * x + d) + e;
    float y = den > 0.0 ? num / den : 0.0;
    return min(max(y, 0.0), 1.0);
}

// AgX 6th-order contrast polynomial (monotone on [0,1]; the 7th-order
// variant is not). Dips to -0.00232 at t=0 - an artifact of the fit, not
// a designed toe - so agxEncode clamps the result to [0,1] BEFORE any
// pow() sees it (NaN guard).
float agxContrastApprox(float x) {
    float x2 = x * x;
    float x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
         - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

// One AgX channel: log2 encode around 0.18 clamped to the
// [-12.47393, 4.026069] EV window, contrast, [0,1] clamp (the CPU's
// exact order; the v<=0 guard keeps log2's argument positive).
float agxEncode(float v, float minEv, float maxEv) {
    if (v <= 0.0) return 0.0;
    float t = log2(v / 0.18);
    t = min(max(t, minEv), maxEv);
    t = (t - minEv) / (maxEv - minEv);
    return min(max(agxContrastApprox(t), 0.0), 1.0);
}

// The AgX inset matrix rows, explicit (the CPU's inset[c*3+0..2] rows -
// dot products mirror the CPU row indexing exactly; a GLSL mat3 is
// column-major and would need a transpose the reader must verify).
const vec3 kAgxInsetR = vec3(0.856627153315983, 0.0951212405381588, 0.0482516061458583);
const vec3 kAgxInsetG = vec3(0.137318972929847, 0.761241990602591, 0.101439036467562);
const vec3 kAgxInsetB = vec3(0.11189821299995, 0.0767994186031903, 0.811302368396859);
// The AgX outset matrix rows, same explicit shape.
const vec3 kAgxOutsetR = vec3(1.1271005818144366432, -0.1106066430966032116, -0.016493938717834568157);
const vec3 kAgxOutsetG = vec3(-0.14132976349843826565, 1.1578237022162717623, -0.01649393871783425265);
const vec3 kAgxOutsetB = vec3(-0.14132976349843824772, -0.11060664309660291788, 1.2519364065950402828);

// The four AgX looks, indexed [uTonemap - 2] exactly like the CPU's
// looks[mode - 2] (2 Punchy, 3 Golden, 4 Greyscale, 5 Base). Slope/power
// per channel, sat, luma weights (Greyscale's are BT.2020 - the config
// it comes from applies the matrix in the post-inset 2020-primaries
// space). Punchy's legacy order (luma from PRE-power values) is the
// uTonemap == 2 branch in tonemap below - kept bit-exactly as the CPU
// ships it.
const vec3 kAgxSlope[4] = vec3[4](
    vec3(1.0, 1.0, 1.0),
    vec3(1.0, 0.9, 0.5),
    vec3(1.0, 1.0, 1.0),
    vec3(1.0, 1.0, 1.0));
const vec3 kAgxPower[4] = vec3[4](
    vec3(1.35, 1.35, 1.35),
    vec3(0.8, 0.8, 0.8),
    vec3(1.0, 1.0, 1.0),
    vec3(1.0, 1.0, 1.0));
const float kAgxSat[4] = float[4](1.4, 1.3, 0.0, 1.0);
const vec3 kAgxLumaW[4] = vec3[4](
    vec3(0.2126, 0.7152, 0.0722),
    vec3(0.2126, 0.7152, 0.0722),
    vec3(0.2589235355689848, 0.6104985346066525, 0.13057792982436284),
    vec3(0.2126, 0.7152, 0.0722));

// Stage 23 applied to one pixel - the CPU tonemapFrameImpl loop body
// verbatim: mode 1 ACES per-component; 2-5 AgX inset -> agxEncode ->
// look -> outset -> clamp -> pow 2.2. The pow(o, 2.2) tail is part of
// the CPU tonemap math itself (the look's output encoding); the Rec.709
// OETF (uOetf) composes ON TOP of it exactly like the CPU chain's JNI
// tail - exactly one OETF each, no double encode.
vec3 tonemapPixel(vec3 c) {
    if (uTonemap == 1) {
        return vec3(acesFit(c.r), acesFit(c.g), acesFit(c.b));
    }
    if (uTonemap < 2 || uTonemap > 5) return c;

    int look = uTonemap - 2;
    const float minEv = -12.47393;
    const float maxEv = 4.026069;
    vec3 v;
    v.x = agxEncode(dot(kAgxInsetR, c), minEv, maxEv);
    v.y = agxEncode(dot(kAgxInsetG, c), minEv, maxEv);
    v.z = agxEncode(dot(kAgxInsetB, c), minEv, maxEv);
    if (uTonemap == 2) {
        // Punchy exactly as the minimal fit shipped it (pre-2025.04.20
        // iolite order): luma from the PRE-power sigmoid values, then
        // power, then sat around that luma. Bit-for-bit the old path.
        float luma = dot(kAgxLumaW[look], v);
        v = luma + kAgxSat[look] * (pow(v, kAgxPower[look]) - luma);
    } else {
        // ASC CDL order (current iolite / OCIO CDLTransform): slope,
        // power, THEN luma of the powered values, then sat.
        v = pow(v * kAgxSlope[look], kAgxPower[look]);
        float luma = dot(kAgxLumaW[look], v);
        v = luma + kAgxSat[look] * (v - luma);
    }
    vec3 o;
    o.x = dot(kAgxOutsetR, v);
    o.y = dot(kAgxOutsetG, v);
    o.z = dot(kAgxOutsetB, v);
    // Outset, clamp to the display box, Rec.709 gamma 2.2 - the clamp
    // BEFORE the pow keeps the base non-negative (NaN guard).
    o = clamp(o, 0.0, 1.0);
    return pow(o, vec3(2.2));
}

// ---- Stage 24: the LUT block (the LUT GPU port) ----
//
// The CPU reference is rawedit::applyManagedLutStage + evaluateManagedLutPixel
// + sampleLutPixel + compileLutBinding (core/src/Color.cpp), transcribed
// verbatim below. The table itself is NOT baked into the text: it lives in a
// 3D texture uploaded once per LUT revision by the session (RGBA16F, one mip,
// GL_LINEAR + CLAMP_TO_EDGE on all three axes - hardware trilinear, the GLES
// 3.x core float format that needs no extension to filter), and the shader
// reproduces the CPU's exact texel-center mapping so the hardware filter IS
// the CPU lattice interpolation:
//     fx = clamp((enc - domainMin) * (n-1)/max(domainMax - domainMin, 1e-20), 0, n-1)
//     t = (fx + 0.5) / n        (texel i's center is (i + 0.5)/n)
// The pre-clamped fx keeps t inside [0.5/n, (n-0.5)/n], so CLAMP_TO_EDGE
// never distorts an edge sample. Half-float table quantization means the
// result is a BOUNDED-error match to the CPU (max LSB measured by the JVM
// parity harness), not a bit-exact one - the honest claim.

// The CPU rawedit::spow (core/src/Color.cpp), verbatim: signed pow, so a
// negative encoded value keeps its sign through the gamma curves.
float spow(float x, float e) {
    return x < 0.0 ? -pow(-x, e) : pow(x, e);
}

// The CPU rawedit::encodeTransfer (core/src/Color.cpp), verbatim per curve,
// switching on the rawedit TransferId ordinals. GLSL ES has no log10: the
// CPU's std::log10(x) is log2(x)/log2(10) (the ~1-ulp difference is part of
// the bounded-error parity claim, measured by the harness).
float lutLog10(float x) {
    return log2(x) / 3.321928094887362;  // log2(10)
}
float lutEncodeTransfer(float lin, int t) {
    if (t == 1) {  // Srgb
        return lin <= 0.0031308 ? 12.92 * lin
             : 1.055 * spow(lin, 1.0 / 2.4) - 0.055;
    }
    if (t == 2) {  // Rec709OETF - the shared linearToRec709 curve
        return lin < 0.018 ? 4.5 * lin : 1.099 * pow(lin, 0.45) - 0.099;
    }
    if (t == 3) {  // Gamma24
        return spow(lin, 1.0 / 2.4);
    }
    if (t == 4) {  // DaVinciIntermediate
        return lin <= 0.00262409 ? 10.44426855 * lin
             : 0.07329248 * (lutLog10(lin + 0.0075) + 7.0);
    }
    if (t == 5) {  // Acescct
        return lin <= 0.0078125
             ? 10.5402377416545 * lin + 0.0729055341958355
             : (log2(lin) + 9.72) / 17.52;
    }
    if (t == 6) {  // ArriLogC3
        return lin > 0.010591
             ? 0.247190 * lutLog10(5.555556 * lin + 0.052272) + 0.385537
             : 5.367655 * lin + 0.092809;
    }
    if (t == 7) {  // SonyLog3
        return lin >= 0.01125000
             ? (420.0 + lutLog10((lin + 0.01) / 0.19) * 261.5) / 1023.0
             : (lin * (171.2102946929 - 95.0) / 0.01125000 + 95.0) / 1023.0;
    }
    if (t == 8) {  // CanonLog3
        if (lin < -0.014) {
            return -0.36726845 * lutLog10(1.0 - 14.98325 * lin) + 0.12783901;
        }
        return lin <= 0.014 ? 1.9754798 * lin + 0.12512219
             : 0.36726845 * lutLog10(14.98325 * lin + 1.0) + 0.12240537;
    }
    if (t == 9) {  // AppleLog
        return lin >= 0.01
             ? 0.08550479 * log2(lin + 0.00964052) + 0.69336945
             : lin >= -0.05641088
             ? 47.28711236 * (lin + 0.05641088) * (lin + 0.05641088)
             : 0.0;
    }
    if (t == 10) {  // VLog
        return lin < 0.01 ? 5.6 * lin + 0.125
             : 0.241514 * lutLog10(lin + 0.00873) + 0.598206;
    }
    if (t == 11) {  // FujifilmFLog
        return lin < 0.00089 ? 8.735631 * lin + 0.092864
             : 0.344676 * lutLog10(0.555556 * lin + 0.009468) + 0.790453;
    }
    if (t == 12) {  // Cineon
        return (685.0 + 300.0 * lutLog10(
            lin * (1.0 - 0.0107977516232771) + 0.0107977516232771)) / 1023.0;
    }
    return lin;  // 0 Linear
}

// The CPU rawedit::decodeTransfer (core/src/Color.cpp), verbatim per curve.
float lutDecodeTransfer(float enc, int t) {
    if (t == 1) {  // Srgb
        return enc <= 0.040449936 ? enc / 12.92
             : spow((enc + 0.055) / 1.055, 2.4);
    }
    if (t == 2) {  // Rec709OETF
        return enc <= 0.081 ? enc / 4.5 : spow((enc + 0.099) / 1.099, 1.0 / 0.45);
    }
    if (t == 3) {  // Gamma24
        return spow(enc, 2.4);
    }
    if (t == 4) {  // DaVinciIntermediate
        return enc <= 0.02740668 ? enc / 10.44426855
             : pow(2.0, enc / 0.07329248 - 7.0) - 0.0075;
    }
    if (t == 5) {  // Acescct
        return enc > 0.155251141552511
             ? pow(2.0, enc * 17.52 - 9.72)
             : (enc - 0.0729055341958355) / 10.5402377416545;
    }
    if (t == 6) {  // ArriLogC3
        return enc > 5.367655 * 0.010591 + 0.092809
             ? (pow(10.0, (enc - 0.385537) / 0.247190) - 0.052272) / 5.555556
             : (enc - 0.092809) / 5.367655;
    }
    if (t == 7) {  // SonyLog3
        return enc >= 171.2102946929 / 1023.0
             ? pow(10.0, (enc * 1023.0 - 420.0) / 261.5) * 0.19 - 0.01
             : (enc * 1023.0 - 95.0) * 0.01125000 / (171.2102946929 - 95.0);
    }
    if (t == 8) {  // CanonLog3
        if (enc < 1.9754798 * -0.014 + 0.12512219) {
            return (1.0 - pow(10.0, (enc - 0.12783901) / -0.36726845)) / 14.98325;
        }
        return enc <= 1.9754798 * 0.014 + 0.12512219
             ? (enc - 0.12512219) / 1.9754798
             : (pow(10.0, (enc - 0.12240537) / 0.36726845) - 1.0) / 14.98325;
    }
    if (t == 9) {  // AppleLog
        return enc >= 47.28711236 * 0.06641088 * 0.06641088
             ? pow(2.0, (enc - 0.69336945) / 0.08550479) - 0.00964052
             : enc >= 0.0
             ? sqrt(enc / 47.28711236) - 0.05641088
             : -0.05641088;
    }
    if (t == 10) {  // VLog
        return enc < 0.181 ? (enc - 0.125) / 5.6
             : pow(10.0, (enc - 0.598206) / 0.241514) - 0.00873;
    }
    if (t == 11) {  // FujifilmFLog
        return enc < 0.100537775223865 ? (enc - 0.092864) / 8.735631
             : pow(10.0, (enc - 0.790453) / 0.344676) / 0.555556
               - 0.009468 / 0.555556;
    }
    if (t == 12) {  // Cineon
        return (pow(10.0, (1023.0 * enc - 685.0) / 300.0)
              - 0.0107977516232771) / (1.0 - 0.0107977516232771);
    }
    return enc;  // 0 Linear
}

// The CPU rawedit::sampleLutPixel's lattice math (core/src/Color.cpp),
// reproduced through the 3D texture's hardware trilinear filter: the
// domain-normalized texel-center mapping above. The CPU guards (n < 2,
// rgb.size() == n^3*3) are the session's upload-time checks - a table that
// failed them never reaches the texture.
vec3 lutSample(vec3 enc) {
    float n = float(uLutSize);
    vec3 span = uLutDomainMax - uLutDomainMin;
    vec3 scale = (n - 1.0) / max(span, vec3(1e-20));
    vec3 fx = clamp((enc - uLutDomainMin) * scale, vec3(0.0), vec3(n - 1.0));
    // Trilinear BY HAND over a float32 table (2026-11-24): the old RGBA16F table through the hardware filter
    // differed from the CPU lattice by 1 LSB on 3-5% of samples (half-float table values plus the filter's
    // 8-bit fractional weights); fetching the eight lattice neighbours at full float precision and blending
    // them here is the CPU's own arithmetic. Texel (x,y,z) = table entry (r,g,b), the layout glTexImage3D
    // already gets, so no index reordering.
    vec3 f0 = floor(fx);
    vec3 w = fx - f0;
    ivec3 i0 = ivec3(f0);
    ivec3 hi = ivec3(int(n) - 1);
    ivec3 i1 = min(i0 + ivec3(1), hi);
    vec3 c000 = texelFetch(uLut, ivec3(i0.x, i0.y, i0.z), 0).rgb;
    vec3 c100 = texelFetch(uLut, ivec3(i1.x, i0.y, i0.z), 0).rgb;
    vec3 c010 = texelFetch(uLut, ivec3(i0.x, i1.y, i0.z), 0).rgb;
    vec3 c110 = texelFetch(uLut, ivec3(i1.x, i1.y, i0.z), 0).rgb;
    vec3 c001 = texelFetch(uLut, ivec3(i0.x, i0.y, i1.z), 0).rgb;
    vec3 c101 = texelFetch(uLut, ivec3(i1.x, i0.y, i1.z), 0).rgb;
    vec3 c011 = texelFetch(uLut, ivec3(i0.x, i1.y, i1.z), 0).rgb;
    vec3 c111 = texelFetch(uLut, ivec3(i1.x, i1.y, i1.z), 0).rgb;
    vec3 c00 = mix(c000, c100, w.x);
    vec3 c10 = mix(c010, c110, w.x);
    vec3 c01 = mix(c001, c101, w.x);
    vec3 c11 = mix(c011, c111, w.x);
    return mix(mix(c00, c10, w.y), mix(c01, c11, w.y), w.z);
}

// The CPU rawedit::evaluateManagedLutPixel (core/src/Color.cpp), verbatim:
// working -> input gamut -> encode -> sample -> decode -> output gamut.
vec3 lutManagedPixel(vec3 px) {
    vec3 gamutIn = uLutWorkingToInput * px;
    vec3 enc = vec3(
        lutEncodeTransfer(gamutIn.x, uLutInTransfer),
        lutEncodeTransfer(gamutIn.y, uLutInTransfer),
        lutEncodeTransfer(gamutIn.z, uLutInTransfer));
    vec3 sampled = lutSample(enc);
    vec3 dec = vec3(
        lutDecodeTransfer(sampled.x, uLutOutTransfer),
        lutDecodeTransfer(sampled.y, uLutOutTransfer),
        lutDecodeTransfer(sampled.z, uLutOutTransfer));
    return uLutOutputToWorking * dec;
}
"""

    /** main()'s guard head - shared by the fused pass, PRE and POST. */
    private const val kDevelopMainHead: String = """void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
"""

    /** main()'s PRE body: the demosaic + WB + cam2rgb, cut at the 6/9 boundary. */
    private const val kDevelopPreBody: String = """    int maxI = uSize.x - 1;
    int maxJ = uSize.y - 1;
    int ownSite = siteAt(g);
    float px[3];
    for (int t = 0; t < 3; ++t) {
        if (t == ownSite) {
            px[t] = hrAt(g);
        } else {
            float sum = 0.0;
            int n = 0;
            for (int k = 0; k < 8; ++k) {
                int nx = min(max(g.x + kDx[k], 0), maxI);
                int ny = min(max(g.y + kDy[k], 0), maxJ);
                ivec2 np = ivec2(nx, ny);
                if (siteAt(np) == t) {
                    sum += hrAt(np);
                    n += 1;
                }
            }
            px[t] = n > 0 ? sum / float(n) : hrAt(g);
        }
    }
    vec3 c = vec3(px[0], px[1], px[2]) * uWb;
    if (uHasProfile == 1) {
        c = uCam2rgb * c;
    }
"""

    /**
     * main()'s pointwise POST body: stage 9 through the stage-20 saturation
     * block, cut at the 20/23 boundary (the CPU's stage-21/22 position - the
     * unsharp passes sit between the pointwise stages and the tail). The
     * pointwise pass appends [kDevelopWriteOut]; the fused pass continues
     * straight into [kDevelopTailBody].
     */
    private const val kDevelopPointwiseBody: String = """    // Stage 9: black point - the CPU stage-9 endpoint remap, verbatim
    // (core/src/Develop.cpp). uBlackPoint carries the RAW grade.blackPoint
    // value; 0.0 skips the whole stage exactly like the CPU's `if
    // (grade.blackPoint != 0.0f)`. lift is uniform-derived so it must NOT
    // be const (GLSL ES 3.00). k = 0.12 (A6 black point range shrink,
    // owner request 2026-11-23; was 0.25).
    if (uBlackPoint != 0.0) {
        float lift = -0.12 * (uBlackPoint / 100.0);
        c = lift + (1.0 - lift) * c;
    }
    // Stage 10: white point - the CPU stage-10 uniform scale, verbatim.
    // uWhitePoint carries the RAW grade.whitePoint value; 0.0 skips the
    // stage exactly like the CPU. scale is uniform-derived (no const).
    if (uWhitePoint != 0.0) {
        float scale = uWhitePoint > 0.0
            ? 1.0 / (1.0 - 0.25 * (uWhitePoint / 100.0))
            : 1.0 + 0.25 * (uWhitePoint / 100.0);
        c *= scale;
    }
    // Stage 11: shadows - the CPU stage-11 dark-band gain, verbatim
    // (smoothstepf above is the CPU smoothstepf). strength/mx/wDark/gain
    // are uniform-derived (no const).
    if (uShadows != 0.0) {
        float strength = uShadows / 100.0;
        float mx = max(c.r, max(c.g, c.b));
        float wDark = smoothstepf(0.18, 0.0, mx);  // 1 at black
        float gain = pow(2.0, strength * wDark);
        c *= gain;
    }
    // Stage 12: midtones - the CPU stage-12 midgray tent, verbatim.
    if (uMidtones != 0.0) {
        float strength = uMidtones / 100.0;
        float mx = max(c.r, max(c.g, c.b));
        float wMid = smoothstepf(0.0, 0.18, mx)
                   * (1.0 - smoothstepf(0.18, 0.72, mx));
        float gain = pow(2.0, strength * wMid);
        c *= gain;
    }
    // Stage 13: contrast - the CPU stage-13 slope around the midgray
    // pivot, verbatim. The CPU local c (contrast/100) collides with the
    // pixel vector c here, so it is renamed slopeC - same math, renamed
    // honestly. pivot is literal-only so it keeps const.
    if (uContrast != 0.0) {
        float slopeC = uContrast / 100.0;
        float slope = 1.0 + slopeC;
        const float pivot = 0.18;
        c = pivot + (c - pivot) * slope;
    }
    c *= uExposure;
    // Stage 15: highlights - the CPU stage-15 shoulder, verbatim (core/src/
    // Develop.cpp). uHighlights carries the RAW grade.highlights value; 0.0
    // skips the whole stage exactly like the CPU's `if (grade.highlights
    // != 0.0f)`. knee/span are literal-only so they keep const; s/mx/over/
    // t/shoulder/newMx/gain are uniform-derived and must NOT (GLSL ES 3.00).
    if (uHighlights != 0.0) {
        const float knee = 0.40;
        const float span = 1.0 - knee;
        float s = -uHighlights / 100.0;  // recovery -> +1, lift -> -1
        float mx = max(c.r, max(c.g, c.b));
        if (mx > knee) {
            float over = mx - knee;
            float t = over / span;               // 0..1+ above the knee
            // Monotone shoulder: bounded, order-preserving for all t.
            float shoulder = knee + span * (t / (1.0 + t));
            // Blend original -> shoulder by strength (s in [-1, 1]).
            float newMx = mx + s * (shoulder - mx);
            float gain = mx > 0.0 ? newMx / mx : 1.0;
            c *= gain;
        }
    }
    // Stage 16: temperature - the CPU stage-16 warm/cool diagonal gain,
    // verbatim (core/src/Develop.cpp). uWbManual carries the CPU-computed
    // !grade.asShot gate (0 = as-shot: skip both 16 and 17 exactly like
    // the CPU's `if (!grade.asShot)`); uTempK carries the RAW grade.tempK
    // value and the shader computes tempShift = log2(uTempK/5600) with the
    // CPU's own tempK > 0 guard and |tempShift| > 1e-4 threshold. The raw
    // hue sentinel question does not arise here, but the gate hoist is the
    // same idiom as stage 19's uSplitActive: the GLSL default of an unset
    // int uniform is 0, which must be the exact CPU skip so the frozen
    // device parity harness (which never binds these uniforms) stays
    // byte-honest.
    if (uWbManual == 1) {
        float tempShift = uTempK > 0.0 ? log2(uTempK / 5600.0) : 0.0;
        if (abs(tempShift) > 1e-4) {
            float warmGain = pow(2.0, 0.35 * tempShift);   // red up when warm
            float coolGain = pow(2.0, -0.35 * tempShift);  // blue up when cool
            c.r *= warmGain;
            // Green rides the geometric mean so luminance is preserved.
            c.g *= sqrt(warmGain * coolGain);
            c.b *= coolGain;
        }
    }
    // Stage 17: tint - the CPU stage-17 green-channel gain, verbatim.
    // The CPU guard is `!grade.asShot && grade.tint != 0.0f`; uWbManual
    // carries the asShot half (CPU-computed) and the 0.0 GLSL default of
    // uTint carries the tint half.
    if (uWbManual == 1 && uTint != 0.0) {
        float greenGain = pow(2.0, 0.4 * (uTint / 100.0));
        c.g *= greenGain;
    }
    // Stage 18: color wheel - the CPU stage-18 tonal-band blend, verbatim
    // (COL-13). The band weights reuse the tone-slider bands (an exact
    // partition of unity); the six offsets are RAW grade values, and the
    // GLSL 0.0 default of each is the CPU's own neutral, so an unset
    // uniform block skips the stage exactly like the CPU's wheelActive
    // check.
    if (uWheelShadowX != 0.0 || uWheelShadowY != 0.0 ||
        uWheelMidX != 0.0 || uWheelMidY != 0.0 ||
        uWheelHighX != 0.0 || uWheelHighY != 0.0) {
        float mx = max(c.r, max(c.g, c.b));
        float wDark = smoothstepf(0.18, 0.0, mx);
        float wMid = smoothstepf(0.0, 0.18, mx)
                   * (1.0 - smoothstepf(0.18, 0.72, mx));
        float wHigh = smoothstepf(0.18, 0.72, mx);
        float xBlend = wDark * uWheelShadowX
                     + wMid  * uWheelMidX
                     + wHigh * uWheelHighX;
        float yBlend = wDark * uWheelShadowY
                     + wMid  * uWheelMidY
                     + wHigh * uWheelHighY;
        c.r *= pow(2.0,  0.5 * xBlend);  // red: warm axis
        c.g *= pow(2.0,  0.5 * yBlend);  // green: tint axis
        c.b *= pow(2.0, -0.5 * xBlend);  // blue: cool axis
    }
    // Stage 19: split tone - the CPU stage-19 band tints, verbatim
    // (COL-13). uSplitActive carries the CPU-computed splitShadowHue >= 0
    // || splitHighlightHue >= 0 gate: the CPU's -1 = off sentinel CANNOT
    // live on the raw hue uniforms here, because the GLSL default of an
    // unset float uniform is 0.0 - a VALID hue that would flip the gate
    // on and break the frozen device parity harness (which never binds
    // these uniforms). Inside the gate the shader keeps the CPU's own
    // per-side `>= 0.0` fallback (hueGains above is the CPU lambda, with
    // the channel index renamed ch because c is the pixel vector here).
    if (uSplitActive == 1) {
        float m = 0.18 * pow(2.0, uSplitBalance / 100.0);
        vec3 sh = uSplitShadowHue >= 0.0
            ? hueGains(uSplitShadowHue) : vec3(1.0, 1.0, 1.0);
        vec3 hl = uSplitHighlightHue >= 0.0
            ? hueGains(uSplitHighlightHue) : vec3(1.0, 1.0, 1.0);
        const float tintAmount = 0.35;  // stops at full weight (literal-only: const)
        float mx = max(c.r, max(c.g, c.b));
        float wShadow = smoothstepf(m, 0.0, mx);   // 1 at black, 0 at m
        float wHighlight = smoothstepf(m, 1.0, mx); // 0 at m, 1 at white
        for (int ch = 0; ch < 3; ++ch) {
            float gain = pow(
                2.0, tintAmount * (wShadow * (sh[ch] - 1.0)
                                 + wHighlight * (hl[ch] - 1.0)));
            c[ch] *= gain;
        }
    }
    // Stage 20: saturation - the CPU stage-20 chroma scaling around
    // luma, verbatim. uSaturation carries the RAW grade.saturation value;
    // 0.0 skips the stage exactly like the CPU's `if (grade.saturation
    // != 0.0f)`.
    if (uSaturation != 0.0) {
        float f = pow(2.0, 0.75 * (uSaturation / 100.0));
        float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
        c = luma + (c - luma) * f;
    }
"""

    /**
     * main()'s TAIL body: the stage-24 LUT block, the tonemap, the OETF and
     * the oCol write + main close (the fused tail's own final lines). The
     * tail pass carries its own close because the fused pass's POST body
     * ended with it; the pointwise pass appends [kDevelopWriteOut] instead.
     */
    private const val kDevelopTailBody: String = """    // Stage 24: the LUT - the CPU rawedit::applyManagedLutStage role
    // contract (core/src/Color.cpp), verbatim in each role's CPU order.
    // uLutStrength carries the RAW grade value ALREADY mapped to rawedit's
    // [0,1] scale (the Kotlin seam divides the GradeK percent by 100),
    // and 0.0 skips the whole stage exactly like the CPU's own
    // `if (!(lutStrength > 0.0f))` bypass. SceneLook (role 2) applies the
    // LUT BEFORE tonemap (the CPU order); every other role applies it
    // AFTER tonemap, before the OETF; SceneToDisplay (role 3) at full
    // strength SKIPS tonemap (the CPU's s >= 1 branch). cScene preserves
    // the pre-tonemap pixel for SceneToDisplay's mixed-strength blend,
    // which samples the SCENE pixel like the CPU's baseline copy.
    float lutS = min(uLutStrength, 1.0);  // the CPU's own clamp
    vec3 cScene = c;  // the pre-tonemap pixel (SceneToDisplay's LUT input)
    if (uLutRole == 2 && uLutStrength != 0.0) {
        // SceneLook: LUT first, then tonemap (the CPU order).
        vec3 lutPx = uLutPassthrough == 1 ? lutSample(c) : lutManagedPixel(c);
        c = lutPx * lutS + c * (1.0 - lutS);
    }
    if (uTonemap != 0) {
        if (!(uLutStrength != 0.0 && uLutRole == 3 && lutS >= 1.0)) {
            c = tonemapPixel(c);
        }
    }
    if (uLutStrength != 0.0 && uLutRole != 2) {
        // Legacy (0), DisplayLook (1) and SceneToDisplay (3) all apply
        // after tonemap, before the OETF. Legacy and DisplayLook blend
        // against the tonemapped pixel; SceneToDisplay blends against
        // the tonemapped BASELINE while its LUT samples the SCENE pixel
        // (the CPU's per-pixel two-branch form), and at full strength
        // it REPLACES the pixel outright (tonemap already skipped).
        if (uLutRole == 3) {
            vec3 lutPx = lutManagedPixel(cScene);
            if (lutS >= 1.0) {
                c = lutPx;
            } else {
                c = lutPx * lutS + c * (1.0 - lutS);
            }
        } else if (uLutPassthrough == 1 || uLutRole == 0) {
            // Legacy (0) is always the plain sampler (the CPU's applyLut
            // route); DisplayLook (1) takes the plain sampler only when
            // the binding degenerates.
            vec3 lutPx = lutSample(c);
            c = lutPx * lutS + c * (1.0 - lutS);
        } else {
            vec3 lutPx = lutManagedPixel(c);
            c = lutPx * lutS + c * (1.0 - lutS);
        }
    }
    if (uOetf == 1) {
        c = vec3(linearToRec709(c.r), linearToRec709(c.g), linearToRec709(c.b));
    }
    oCol = vec4(c, 1.0);
}
"""

    /** The fused pass's own banner + precision block (its own text). */
    private const val kDevelopFusedBanner: String = """// Fused develop pass - stages 1+2+3+4+5+6+9+10+11+12+13+14+15+16+17+18+19+20+23+24+OETF
// of rawedit::developCpu (core/src/Develop.cpp). See the Kotlin doc above.

precision highp float;
precision highp int;
"""

    /** The write + main close shared by PRE and POST (the fused tail's own lines). */
    private const val kDevelopWriteOut: String = """    oCol = vec4(c, 1.0);
}
"""

    /** POST's own input read: the chain's RGBA32F intermediate, 1:1 texel. */
    private const val kDevelopPostFetch: String = "    vec3 c = texelFetch(uSrc, g, 0).rgb;\n"

    /** PRE's own banner + precision block. */
    private const val kDevelopPreBanner: String = """// Develop pre pass - stages 1+2+3+4+5+6 of rawedit::developCpu
// (core/src/Develop.cpp): normalization, lens shading, highlight
// reconstruction, demosaic, WB, camera->Rec.709. The session chain's
// first develop draw: reads the RAW CFA, writes the post-demosaic
// linear RGB to the RGBA32F intermediate the NR passes and the post
// pass read (the CPU chain's stage 7/8 position - between stages 6
// and 9). The stage text is byte-identical to the fused pass's own PRE
// fragments above, so the split chain and the parity harness cannot
// drift.

precision highp float;
precision highp int;
"""

    /** POST's own banner + precision block. */
    private const val kDevelopPostBanner: String = """// Develop post pass - stages 9+10+11+12+13+14+15+16+17+18+19+20
// of rawedit::developCpu (core/src/Develop.cpp). The session chain's
// pointwise draw: reads the chain's RGBA32F intermediate (the pre
// pass's output, or the NR passes' output when the grade uses spatial
// NR - the CPU chain's stage 7/8 position), applies every per-pixel
// stage up to saturation, writes the pointwise-graded pixel to the
// chain's next intermediate (the CPU chain's stage-21/22 position -
// the sharpness/detail passes read it when the grade uses them, else
// the tail pass does). The stage text is byte-identical to the fused
// pass's own pointwise fragments above, so the split chain and the
// parity harness cannot drift.

precision highp float;
precision highp int;
"""

    /** TAIL's own banner + precision block. */
    private const val kDevelopTailBanner: String = """// Develop tail pass - stages 23+24+OETF of rawedit::developCpu
// (core/src/Develop.cpp). The session chain's last develop draw: reads
// the chain's RGBA32F intermediate (the pointwise pass's output, or
// the sharpness/detail passes' output when the grade uses them - the
// CPU chain's stage-21/22 position), applies the tonemap, the LUT and
// the OETF, writes the developed frame to the session's develop
// texture (the store/blit/window/stats passes all read THAT texture -
// the tail is always the develop chain's last draw). The stage text is
// byte-identical to the fused pass's own tail fragments above, so the
// split chain and the parity harness cannot drift.

precision highp float;
precision highp int;
"""

    /**
     * The FUSED develop fragment pass — recomposed byte-identically from
     * the shared fragments above (the pre-split constant's own text, cut
     * at the stage 6/9 copy boundary and reassembled here). The frozen
     * device parity harness (GlesPipeline.cpp's developFragmentParity)
     * compiles THIS text as ONE draw, so the fused shape stays the
     * single source of truth the harness rides; the session chain's
     * PRE/[NR]/POST split composes the same fragments, and drift between
     * the two chains is impossible by construction. See the family doc
     * above for the stage list, the parity shape and the input/output
     * contract; the validator's Kind.DEVELOP_FRAGMENT contract locks the
     * recomposed text.
     */
    const val DEVELOP_FRAGMENT: String =
        "#version 300 es\n\n" + kDevelopFusedBanner + "\n" +
            kDevelopPreUniforms + kDevelopPointwiseUniforms + kDevelopTailUniforms + "\n" +
            kDevelopOutDecl + "\n" + kDevelopPreHelpers + "\n" +
            kDevelopOetfHelper + "\n" + kDevelopPointwiseHelpers + "\n" +
            kDevelopTailHelpers + "\n" + kDevelopMainHead +
            kDevelopPreBody + kDevelopPointwiseBody + kDevelopTailBody

    /**
     * The develop chain's PRE pass (the spatial-NR port's split) — stages
     * 1-6 of the CPU develop chain as a STANDALONE draw: reads the RAW CFA
     * (uCfa, the same input contract as the fused pass), writes the
     * post-demosaic, post-WB, post-camera-matrix linear Rec.709 pixel to
     * an RGBA32F intermediate. That intermediate is the CPU chain's stage
     * 7/8 input position: the NR passes (NR_CHROMA_H/NR_CHROMA_V,
     * NR_LUMA below) read it as their ORIGINAL guide, and
     * DEVELOP_FRAGMENT_POST consumes the chain's final texture.
     *
     * The stage text is the fused pass's own PRE fragments (uniforms,
     * helpers, main head and body up to the stage-6 camera matrix), so
     * the split chain and the parity harness cannot drift; the validator
     * locks the PRE contract (Kind.DEVELOP_FRAGMENT_PRE), including that
     * NO post-stage uniform leaks into this pass.
     *
     * When the grade uses no spatial NR, the session still draws PRE then
     * POST through the intermediate (the pass structure is fixed; only
     * the NR draws are gated per-grade), so the split chain's no-NR shape
     * is one extra full-frame copy against the fused pass — the honest
     * cost of the NR position, paid only on the session path, never on
     * the frozen parity harness.
     */
    const val DEVELOP_FRAGMENT_PRE: String =
        "#version 300 es\n\n" + kDevelopPreBanner + "\n" +
            kDevelopPreUniforms + "\n" + kDevelopOutDecl + "\n" +
            kDevelopPreHelpers + "\n" + kDevelopMainHead +
            kDevelopPreBody + kDevelopWriteOut

    /**
     * The develop chain's POINTWISE POST pass (the sharpness/detail port's
     * 20/23 cut) — stages 9-20 of the CPU develop chain as a STANDALONE
     * draw: reads the chain's RGBA32F intermediate 1:1 (uSrc — the PRE
     * pass's output when no NR stage ran, else the last NR pass's output;
     * the CPU chain's stage 7/8 output position), applies every per-pixel
     * stage up to saturation verbatim, writes the pointwise-graded pixel
     * to the chain's next intermediate (the CPU chain's stage-21/22
     * position — the sharpness/detail passes read it when the grade uses
     * them, else the tail pass does).
     *
     * The stage text is the fused pass's own pointwise fragments, so the
     * split chain and the parity harness cannot drift; the validator locks
     * the POST contract (Kind.DEVELOP_FRAGMENT_POST), including that NO
     * pre-stage uniform (uCfa, uCam2rgb, the demosaic) and NO tail-stage
     * uniform (uTonemap, uOetf, the uLut* set) leaks into this pass, and
     * the stage application order.
     */
    const val DEVELOP_FRAGMENT_POST: String =
        "#version 300 es\n\n" + kDevelopPostBanner + "\n" +
            "uniform highp sampler2D uSrc;  // the chain's RGBA32F intermediate: the pre pass's or the NR passes' output\n" +
            "uniform ivec2 uSize;            // width/height in pixels (the intermediate; same dims as the CFA)\n" +
            kDevelopPointwiseUniforms + "\n" + kDevelopOutDecl + "\n" +
            kDevelopPointwiseHelpers + "\n" + kDevelopMainHead +
            kDevelopPostFetch + kDevelopPointwiseBody + kDevelopWriteOut

    /**
     * The develop chain's TAIL pass (the sharpness/detail port's 20/23
     * cut) — stages 23 + 24 + OETF of the CPU develop chain as a
     * STANDALONE draw: reads the chain's RGBA32F intermediate 1:1 (uSrc —
     * the pointwise pass's output when no unsharp stage ran, else the
     * last sharpness/detail pass's output; the CPU chain's stage-21/22
     * output position), applies the tonemap, the LUT and the OETF
     * verbatim, writes the developed frame to the session's develop
     * texture (the store/blit/window/stats passes all read THAT texture —
     * the tail is always the develop chain's last draw).
     *
     * The stage text is the fused pass's own tail fragments (the OETF
     * helper, the tonemap/LUT helpers and the tail body with its own oCol
     * write + close), so the split chain and the parity harness cannot
     * drift; the validator locks the TAIL contract
     * (Kind.DEVELOP_FRAGMENT_TAIL), including that NO pointwise-stage
     * uniform (uExposure .. uSaturation) and NO pre-stage uniform leaks
     * into this pass, and the 23/24 application order.
     */
    const val DEVELOP_FRAGMENT_TAIL: String =
        "#version 300 es\n\n" + kDevelopTailBanner + "\n" +
            "uniform highp sampler2D uSrc;  // the chain's RGBA32F intermediate: the pointwise pass's or the unsharp passes' output\n" +
            "uniform ivec2 uSize;            // width/height in pixels (the intermediate; same dims as the CFA)\n" +
            kDevelopTailUniforms + "\n" + kDevelopOutDecl + "\n" +
            kDevelopOetfHelper + "\n" + kDevelopTailHelpers + "\n" + kDevelopMainHead +
            kDevelopPostFetch + kDevelopTailBody

    /**
     * Spatial chroma NR, the HORIZONTAL half (CPU stage 7, pass 1 of 2) —
     * rawedit::denoiseChromaImpl's horizontal blurPlane run, verbatim
     * (core/src/Develop.cpp). The CPU runs its blurPlane lambda TWICE
     * (the cr plane, then the cb plane) with the SAME original luma guide
     * and identical weights; the two runs are independent per-pixel, so
     * ONE pass computing BOTH planes reproduces both exactly.
     *
     * Input: uSrc — the PRE pass's RGBA32F output (the ORIGINAL
     * post-demosaic RGB; this pass's input position IS the CPU's chroma
     * NR input). Output: .xy = the horizontally blurred (cr, cb) planes
     * (the CPU's tmp buffers); .zw unused. The vertical pass
     * (NR_CHROMA_V) reads .xy back beside the ORIGINAL pixels (uGuide)
     * and finishes the CPU's blend — the two-pass shape is the CPU's own
     * (its blurPlane is a horizontal pass into tmp, then a vertical pass
     * into out; collapsing the directions would change the image).
     *
     * uChromaDenoise carries the RAW grade value, gated
     * `if (uChromaDenoise != 0.0)` exactly like the pointwise stages;
     * strength = clamp(raw, 0, 100)/100 with the CPU's own <= 0 no-op
     * (a drawn pass with a closed gate writes the input through
     * unchanged — and the C++ session skips the NR draws entirely for a
     * clamped strength of 0 or a frame under the CPU's w<2/h<2 guard).
     * texelFetch does NOT clamp: the CPU's max/min coordinate guards are
     * load-bearing and carried verbatim, as is the exact tap order
     * (xm, xp, xm2, xp2) — the accumulation order is part of the math.
     */
    const val NR_CHROMA_H: String = """#version 300 es

// Spatial chroma NR, the horizontal half (stage 7 pass 1 of 2) - the CPU
// rawedit::denoiseChromaImpl's horizontal blurPlane run, verbatim (core/
// src/Develop.cpp). The CPU runs its blurPlane lambda TWICE (the cr plane,
// then the cb plane) with the SAME original luma guide and identical
// weights; the two runs are independent, so this pass computes BOTH planes
// in one draw and reproduces both exactly. It writes only the blurred
// planes to .xy - the vertical pass (NR_CHROMA_V) reads them back beside
// the ORIGINAL pixels (uGuide) and finishes the CPU's blend. See the
// Kotlin doc above for the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;   // the develop PRE pass's RGBA32F output (the original post-demosaic RGB)
uniform ivec2 uSize;            // width/height in pixels
uniform float uChromaDenoise;   // raw grade.chromaDenoise; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (its const initializers; literal-
// derived, and exp() of constant arguments is a constant expression in
// ES 3.00, so they keep const exactly like the CPU's).
const float kRadius = 2.0;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kSr = 0.10;
const float kInv2Sr2 = 1.0 / (2.0 * kSr * kSr);

// The CPU's luma split at p (the guide plane the CPU computes once per
// pixel; recomputed per read here - the hrAt precedent: identical math,
// identical answers).
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uSrc, g, 0).rgb;
    // The CPU call site gates the whole stage on grade.chromaDenoise !=
    // 0 and the impl clamps + early-outs; the C++ session skips the NR
    // draws entirely for a clamped strength of 0, and this in-shader
    // gate keeps a drawn pass an exact pass-through besides (the
    // pointwise stages' own gate idiom).
    if (uChromaDenoise != 0.0) {
        float strength = clamp(uChromaDenoise, 0.0, 100.0) / 100.0;
        if (strength > 0.0) {
            // The CPU's per-pixel split, verbatim: y the luma, cr/cb the
            // chroma deltas. This pass's input is the ORIGINAL image, so
            // these are the CPU's cr/cb planes.
            float y = lumaAt(g);
            float cr = orig.r - y;
            float cb = orig.b - y;
            // The CPU's horizontal pass, verbatim: the clamped columns
            // (texelFetch does NOT clamp - the max/min guards are the
            // load-bearing lines), the exact tap order xm, xp, xm2, xp2,
            // center-relative accumulation (s starts 0, sw starts 1 - the
            // center is sw's own 1.0), and the ORIGINAL luma as the range
            // guide.
            int xm = max(g.x - 2, 0);
            int xp = min(g.x + 2, uSize.x - 1);
            int xm2 = max(g.x - 4, 0);
            int xp2 = min(g.x + 4, uSize.x - 1);
            float sw = 1.0;
            float sCr = 0.0;
            float sCb = 0.0;
            // tap(xm, c0)
            {
                ivec2 p = ivec2(xm, g.y);
                float ly = lumaAt(p);
                vec3 t = texelFetch(uSrc, p, 0).rgb;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.r - ly - cr) * kC0 * rw;
                sCb += (t.b - ly - cb) * kC0 * rw;
                sw += kC0 * rw;
            }
            // tap(xp, c0)
            {
                ivec2 p = ivec2(xp, g.y);
                float ly = lumaAt(p);
                vec3 t = texelFetch(uSrc, p, 0).rgb;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.r - ly - cr) * kC0 * rw;
                sCb += (t.b - ly - cb) * kC0 * rw;
                sw += kC0 * rw;
            }
            // tap(xm2, c1)
            {
                ivec2 p = ivec2(xm2, g.y);
                float ly = lumaAt(p);
                vec3 t = texelFetch(uSrc, p, 0).rgb;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.r - ly - cr) * kC1 * rw;
                sCb += (t.b - ly - cb) * kC1 * rw;
                sw += kC1 * rw;
            }
            // tap(xp2, c1)
            {
                ivec2 p = ivec2(xp2, g.y);
                float ly = lumaAt(p);
                vec3 t = texelFetch(uSrc, p, 0).rgb;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.r - ly - cr) * kC1 * rw;
                sCb += (t.b - ly - cb) * kC1 * rw;
                sw += kC1 * rw;
            }
            // tmp = row + s/sw, both planes - the CPU's horizontal output.
            float tmpCr = cr + sCr / sw;
            float tmpCb = cb + sCb / sw;
            oCol = vec4(tmpCr, tmpCb, 0.0, 1.0);
            return;
        }
    }
    // Gate closed (or a nonpositive clamp): exact pass-through.
    oCol = vec4(orig, 1.0);
}
"""

    /**
     * Spatial chroma NR, the VERTICAL half + the blend (CPU stage 7, pass
     * 2 of 2) — rawedit::denoiseChromaImpl's vertical blurPlane run and
     * its per-pixel blend, verbatim (core/src/Develop.cpp).
     *
     * Inputs: uSrc — NR_CHROMA_H's output (.xy = the horizontally blurred
     * cr/cb planes, the CPU's tmp buffers); uGuide — the PRE pass's
     * RGBA32F output (the ORIGINAL pre-NR RGB). The CPU's vertical pass
     * guides with the ORIGINAL luma (NOT the horizontal output — the
     * second pass keeps the original luma guide), and the blend needs the
     * original chroma beside the blurred chroma, so BOTH textures are
     * load-bearing. Output: the chroma-denoised RGB (the CPU's blend
     * writes y+newCr, y+newCg, y+newCb with the green reconstructed from
     * the kept luma; the flat-chroma skip dr == 0 && db == 0 keeps the
     * ORIGINAL bytes — the CPU `continue`s past the write, here the
     * original texel is written, the same bytes).
     *
     * uChromaDenoise carries the RAW grade value with the same gate and
     * clamp as NR_CHROMA_H (both passes of one CPU stage share one
     * grade control). The tap order (ym, yp, ym2, yp2) and the
     * center-relative accumulation are the CPU's own, verbatim.
     */
    const val NR_CHROMA_V: String = """#version 300 es

// Spatial chroma NR, the vertical half + the blend (stage 7 pass 2 of 2)
// - the CPU rawedit::denoiseChromaImpl's vertical blurPlane run and its
// per-pixel blend, verbatim (core/src/Develop.cpp). Reads the horizontal
// pass's blurred planes from uSrc (.xy) and the ORIGINAL pre-NR pixels
// from uGuide (the develop PRE pass's output): the CPU's vertical pass
// guides with the ORIGINAL luma (NOT the horizontal output), and its
// blend needs the original chroma beside the blurred chroma. Both
// planes' vertical runs are independent, so one draw computes both
// exactly like the horizontal pass. See the Kotlin doc above for the
// full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;    // NR_CHROMA_H's output: .xy = the horizontally blurred (cr, cb)
uniform highp sampler2D uGuide;  // the develop PRE pass's output: the ORIGINAL pre-NR RGB
uniform ivec2 uSize;             // width/height in pixels
uniform float uChromaDenoise;   // raw grade.chromaDenoise; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (same as the horizontal pass).
const float kRadius = 2.0;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kSr = 0.10;
const float kInv2Sr2 = 1.0 / (2.0 * kSr * kSr);

// The CPU's luma split at p of the ORIGINAL image (uGuide): the guide
// plane the CPU computed once from its input.
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uGuide, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uGuide, g, 0).rgb;
    if (uChromaDenoise != 0.0) {
        float strength = clamp(uChromaDenoise, 0.0, 100.0) / 100.0;
        if (strength > 0.0) {
            // The CPU's per-pixel split of the ORIGINAL pixel, verbatim.
            float y = lumaAt(g);
            float cr = orig.r - y;
            float cb = orig.b - y;
            // The CPU's vertical pass, verbatim: the clamped rows, the
            // exact tap order ym, yp, ym2, yp2, center-relative
            // accumulation, the blurred planes as values (uSrc) and the
            // ORIGINAL luma as the range guide (uGuide) - the CPU's
            // second pass keeps the ORIGINAL luma guide.
            int ym = max(g.y - 2, 0);
            int yp = min(g.y + 2, uSize.y - 1);
            int ym2 = max(g.y - 4, 0);
            int yp2 = min(g.y + 4, uSize.y - 1);
            vec2 v = texelFetch(uSrc, g, 0).xy;
            float sw = 1.0;
            float sCr = 0.0;
            float sCb = 0.0;
            // tap(ym, c0)
            {
                ivec2 p = ivec2(g.x, ym);
                float ly = lumaAt(p);
                vec2 t = texelFetch(uSrc, p, 0).xy;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.x - v.x) * kC0 * rw;
                sCb += (t.y - v.y) * kC0 * rw;
                sw += kC0 * rw;
            }
            // tap(yp, c0)
            {
                ivec2 p = ivec2(g.x, yp);
                float ly = lumaAt(p);
                vec2 t = texelFetch(uSrc, p, 0).xy;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.x - v.x) * kC0 * rw;
                sCb += (t.y - v.y) * kC0 * rw;
                sw += kC0 * rw;
            }
            // tap(ym2, c1)
            {
                ivec2 p = ivec2(g.x, ym2);
                float ly = lumaAt(p);
                vec2 t = texelFetch(uSrc, p, 0).xy;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.x - v.x) * kC1 * rw;
                sCb += (t.y - v.y) * kC1 * rw;
                sw += kC1 * rw;
            }
            // tap(yp2, c1)
            {
                ivec2 p = ivec2(g.x, yp2);
                float ly = lumaAt(p);
                vec2 t = texelFetch(uSrc, p, 0).xy;
                float dl = ly - y;
                float rw = exp(-dl * dl * kInv2Sr2);
                sCr += (t.x - v.x) * kC1 * rw;
                sCb += (t.y - v.y) * kC1 * rw;
                sw += kC1 * rw;
            }
            // out = v + s/sw, both planes - the CPU's blurred chroma.
            float bcr = v.x + sCr / sw;
            float bcb = v.y + sCb / sw;
            // The CPU's blend, verbatim: the strength-scaled delta, the
            // flat-chroma skip (dr == 0 && db == 0 keeps the ORIGINAL
            // bytes - the CPU `continue`s past the write; here the
            // original texel is written, the same bytes), and the green
            // reconstruction from the kept luma.
            float dr = strength * (bcr - cr);
            float db = strength * (bcb - cb);
            if (dr == 0.0 && db == 0.0) {
                oCol = vec4(orig, 1.0);
                return;
            }
            float newCr = cr + dr;
            float newCb = cb + db;
            float newCg = -(0.2126 * newCr + 0.0722 * newCb) / 0.7152;
            oCol = vec4(y + newCr, y + newCg, y + newCb, 1.0);
            return;
        }
    }
    // Gate closed (or a nonpositive clamp): exact pass-through.
    oCol = vec4(orig, 1.0);
}
"""

    /**
     * Spatial luma NR (CPU stage 8) — rawedit::denoiseLumaImpl, verbatim
     * (core/src/Develop.cpp): a true 3x3 luma bilateral, ONE pass. The
     * CPU computes a luma plane once and smooths it with the center plus
     * 8 clamped neighbors (9 taps); the smoothed-luma delta scaled by
     * strength is added to r, g and b equally. NO approximation: the
     * range weights are the CPU's own exp(-dl*dl/2*sr^2) with sr = 0.05,
     * the spatial weights are its exp(-0.5)/exp(-1.0) edge/center pair,
     * and the tap order is its exact call order (the accumulation order
     * is part of the math).
     *
     * Input: uSrc — the chain's current RGBA32F texture: the chroma-NR
     * output when stage 7 ran, else the PRE pass's output (the CPU runs
     * luma NR on the chroma-denoised image — its luma plane is computed
     * from THIS pass's input, not the pre-NR original). Output: the
     * luma-lifted RGB. uNoiseReduction carries the RAW grade value with
     * the same gate/clamp idiom as the chroma passes; the C++ session
     * skips the draw for a clamped strength of 0 or a frame under the
     * CPU's w<3/h<3 guard.
     */
    const val NR_LUMA: String = """#version 300 es

// Spatial luma NR (stage 8) - the CPU rawedit::denoiseLumaImpl, verbatim
// (core/src/Develop.cpp): a true 3x3 luma bilateral, ONE pass. The CPU
// computes a luma plane once and smooths it with 8 clamped neighbors
// (center + 8 taps = 9); the smoothed-luma delta scaled by strength is
// added to r, g and b equally. This pass reads the chain's current input
// (the chroma-NR output when stage 7 ran, else the develop PRE output -
// the CPU runs luma NR on the chroma-denoised image), derives every luma
// tap from it, and writes the lifted RGB. See the Kotlin doc above for
// the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;      // the chain's RGBA32F input: the chroma-NR output, or the PRE output when stage 7 is off
uniform ivec2 uSize;              // width/height in pixels
uniform float uNoiseReduction;    // raw grade.noiseReduction; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (its const initializers).
const float kSr = 0.05;
const float kInv2Sr2 = 1.0 / (2.0 * kSr * kSr);
const float kWe = exp(-0.5);
const float kWd = exp(-1.0);

// The CPU's luma split at p of THIS pass's input (the plane the CPU
// computes once per pixel; recomputed per read - identical math).
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uSrc, g, 0).rgb;
    if (uNoiseReduction != 0.0) {
        float strength = clamp(uNoiseReduction, 0.0, 100.0) / 100.0;
        if (strength > 0.0) {
            // The CPU's 3x3 clamped coordinates, verbatim (texelFetch
            // does NOT clamp - the max/min guards are load-bearing).
            int xm = max(g.x - 1, 0);
            int xp = min(g.x + 1, uSize.x - 1);
            int ym = max(g.y - 1, 0);
            int yp = min(g.y + 1, uSize.y - 1);
            float c = lumaAt(g);
            float sw = 1.0;
            float s = 0.0;
            // The CPU's tap order, verbatim: the 8 neighbors in the exact
            // call order (the center is sw's own initial 1.0).
            // tap(ym, xm, wd)
            {
                ivec2 p = ivec2(xm, ym);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWd * rw;
                sw += kWd * rw;
            }
            // tap(ym, x, we)
            {
                ivec2 p = ivec2(g.x, ym);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWe * rw;
                sw += kWe * rw;
            }
            // tap(ym, xp, wd)
            {
                ivec2 p = ivec2(xp, ym);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWd * rw;
                sw += kWd * rw;
            }
            // tap(y, xm, we)
            {
                ivec2 p = ivec2(xm, g.y);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWe * rw;
                sw += kWe * rw;
            }
            // tap(y, xp, we)
            {
                ivec2 p = ivec2(xp, g.y);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWe * rw;
                sw += kWe * rw;
            }
            // tap(yp, xm, wd)
            {
                ivec2 p = ivec2(xm, yp);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWd * rw;
                sw += kWd * rw;
            }
            // tap(yp, x, we)
            {
                ivec2 p = ivec2(g.x, yp);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWe * rw;
                sw += kWe * rw;
            }
            // tap(yp, xp, wd)
            {
                ivec2 p = ivec2(xp, yp);
                float lv = lumaAt(p);
                float dl = lv - c;
                float rw = exp(-dl * dl * kInv2Sr2);
                s += (lv - c) * kWd * rw;
                sw += kWd * rw;
            }
            // smooth = c + s/sw; d = strength * (smooth - luma), added to
            // r, g, b equally - the CPU's tail, verbatim.
            float smoothV = c + s / sw;
            float d = strength * (smoothV - c);
            oCol = vec4(orig + vec3(d), 1.0);
            return;
        }
    }
    // Gate closed (or a nonpositive clamp): exact pass-through.
    oCol = vec4(orig, 1.0);
}
"""

    /**
     * Sharpness, the HORIZONTAL half (CPU stage 21, pass 1 of 2) —
     * rawedit::unsharpLumaImpl's horizontal blur run at radius 1.5,
     * verbatim (core/src/Develop.cpp). The CPU computes a luma plane
     * once, blurs it horizontally into tmp (a 4-tap discrete Gaussian at
     * floor(radius) spacing, clamped edges, norm-divided), then
     * vertically, and finally adds the scaled high-pass delta to r, g
     * *and* b equally. This pass reproduces the HORIZONTAL blur only:
     * it writes the blurred-luma plane to .x (the CPU's tmp buffer) —
     * a drawn pass with a closed gate writes the ORIGINAL luma to .x
     * instead, so the vertical pass's center-relative math stays exact
     * in both shapes. The vertical pass (SHARPNESS_V) reads .x back
     * beside the stage's ORIGINAL RGB (uGuide) and finishes the CPU's
     * tail — the two-pass shape is the CPU's own (its blur is a
     * horizontal pass into tmp, then a vertical pass into blur;
     * collapsing the directions would change the image).
     *
     * Input: uSrc — the pointwise POST pass's RGBA32F output (this
     * pass's input position IS the CPU's stage-21 input: after the
     * pointwise stages, before stage 22). uSharpness carries the RAW
     * grade.sharpness value, gated `if (uSharpness != 0.0)` exactly
     * like the CPU call site's own outer gate; the amount mapping is the
     * CPU's own, computed in-shader: clamp(0.5 * (raw/100), -0.5, 0.5)
     * — NEGATIVE values are valid (softening), so there is NO
     * [0,100] clamp and NO strength sub-gate (unlike the NR passes);
     * a drawn pass with a closed gate is an exact pass-through, and the
     * C++ session skips the unsharp draws entirely for a raw value of 0
     * or a frame under the CPU's w<2/h<2 guard. texelFetch does NOT
     * clamp: the CPU's max/min coordinate guards are load-bearing and
     * carried verbatim, as is the exact tap order (xm, xp, xm2, xp2).
     */
    const val SHARPNESS_H: String = """#version 300 es

// Sharpness, the horizontal half (stage 21 pass 1 of 2) - the CPU
// rawedit::unsharpLumaImpl's horizontal blur run at radius 1.5, verbatim
// (core/src/Develop.cpp). Writes the horizontally blurred luma plane to
// .x (the CPU's tmp buffer); the vertical pass (SHARPNESS_V) reads it back
// beside the ORIGINAL pixels (uGuide) and finishes the CPU's tail. See the
// Kotlin doc above for the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;   // the pointwise post pass's RGBA32F output (this stage's input)
uniform ivec2 uSize;            // width/height in pixels
uniform float uSharpness;       // raw grade.sharpness; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (radius 1.5 -> step 1 both directions;
// exp() of constant arguments is a constant expression in ES 3.00, so they
// keep const exactly like the CPU's).
const float kRadius = 1.5;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kNorm = 1.0 + 2.0 * (kC0 + kC1);

// The CPU's luma split at p (the plane the CPU computes once per pixel;
// recomputed per read - identical math, identical answers).
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uSrc, g, 0).rgb;
    // The CPU call site gates the whole stage on grade.sharpness != 0;
    // this in-shader gate keeps a drawn pass an exact pass-through (the
    // pointwise stages' own gate idiom). The amount mapping is the CPU
    // call site's own: 0.5 * (raw/100) clamped to [-0.5, 0.5] - NEGATIVE
    // values are valid (softening), so no [0,100] clamp, no sub-gate.
    if (uSharpness != 0.0) {
        float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, 0.5);
        // The CPU's horizontal pass, verbatim: the clamped columns (step
        // = 1 for radius 1.5 - floor(1.5)), the exact tap order xm, xp,
        // xm2, xp2, and the norm division (taps sum to 1, so a flat
        // region's blur equals its value and the CPU's delta is exactly
        // 0 there).
        float y = lumaAt(g);
        int xm = max(g.x - 1, 0);
        int xp = min(g.x + 1, uSize.x - 1);
        int xm2 = max(g.x - 2, 0);
        int xp2 = min(g.x + 2, uSize.x - 1);
        float s = y
            + (lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC0
            + (lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1;
        oCol = vec4(s / kNorm, 0.0, 0.0, 1.0);
        return;
    }
    // Gate closed: the ORIGINAL luma to .x, so the vertical pass's
    // center-relative math stays exact (its blur degenerates to the
    // original luma; the CPU's tail delta is exactly 0).
    oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);
}
"""

    /**
     * Sharpness, the VERTICAL half + the difference (CPU stage 21, pass
     * 2 of 2) — rawedit::unsharpLumaImpl's vertical blur run and its
     * high-pass tail, verbatim (core/src/Develop.cpp).
     *
     * Inputs: uSrc — SHARPNESS_H's output (.x = the horizontally blurred
     * luma plane, the CPU's tmp buffer); uGuide — this stage's ORIGINAL
     * input (the pointwise POST pass's RGBA32F output). The CPU's
     * vertical pass centers on the HORIZONTAL OUTPUT at the same pixel
     * (its v = tmp[y*w+x]), guides with the same tmp plane's rows, and
     * the tail's delta is amount * (ORIGINAL luma - blur) added to r, g
     * and b equally — so BOTH textures are load-bearing. Output: the
     * sharpened RGB (the CPU's tail writes orig.rgb + vec3(d)).
     *
     * uSharpness carries the RAW grade value with the same gate and
     * amount mapping as SHARPNESS_H (both passes of one CPU stage share
     * one grade control). The tap order (ym, yp, ym2, yp2) and the norm
     * division are the CPU's own, verbatim.
     */
    const val SHARPNESS_V: String = """#version 300 es

// Sharpness, the vertical half + the difference (stage 21 pass 2 of 2) -
// the CPU rawedit::unsharpLumaImpl's vertical blur run and its high-pass
// tail, verbatim (core/src/Develop.cpp). Reads the horizontal pass's
// blurred luma plane from uSrc (.x) and the ORIGINAL pixels from uGuide
// (the pointwise post pass's output): the CPU's vertical pass centers on
// the HORIZONTAL OUTPUT at the same pixel, and its tail's delta is
// amount * (ORIGINAL luma - blur) added to r, g and b equally. See the
// Kotlin doc above for the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;    // SHARPNESS_H's output: .x = the horizontally blurred luma
uniform highp sampler2D uGuide;  // the pointwise post pass's output: the ORIGINAL pre-sharpen RGB
uniform ivec2 uSize;             // width/height in pixels
uniform float uSharpness;        // raw grade.sharpness; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (same as the horizontal pass).
const float kRadius = 1.5;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kNorm = 1.0 + 2.0 * (kC0 + kC1);

// The CPU's luma split at p of the ORIGINAL image (uGuide): the plane the
// CPU computed once from its input, and the plane its tail's delta reads.
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uGuide, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uGuide, g, 0).rgb;
    if (uSharpness != 0.0) {
        float amount = clamp(0.5 * (uSharpness / 100.0), -0.5, 0.5);
        // The CPU's vertical pass, verbatim: the clamped rows (step = 1
        // for radius 1.5), the exact tap order ym, yp, ym2, yp2, the
        // CENTER tap = the horizontal output at the SAME pixel (its
        // v = tmp[y*w+x]), and the norm division.
        int ym = max(g.y - 1, 0);
        int yp = min(g.y + 1, uSize.y - 1);
        int ym2 = max(g.y - 2, 0);
        int yp2 = min(g.y + 2, uSize.y - 1);
        float v = texelFetch(uSrc, g, 0).x;
        float s = v
            + (texelFetch(uSrc, ivec2(g.x, ym), 0).x
                + texelFetch(uSrc, ivec2(g.x, yp), 0).x) * kC0
            + (texelFetch(uSrc, ivec2(g.x, ym2), 0).x
                + texelFetch(uSrc, ivec2(g.x, yp2), 0).x) * kC1;
        float blur = s / kNorm;
        // The CPU's tail, verbatim: the scaled high-pass delta from the
        // ORIGINAL luma, added to r, g and b equally (chroma and hue
        // are preserved - no chroma halos).
        float d = amount * (lumaAt(g) - blur);
        oCol = vec4(orig + vec3(d), 1.0);
        return;
    }
    // Gate closed: exact pass-through (the CPU's amount-0 shape).
    oCol = vec4(orig, 1.0);
}
"""

    /**
     * Detail, the HORIZONTAL half (CPU stage 22, pass 1 of 2) — the same
     * rawedit::unsharpLumaImpl structure at radius 0.75 (the CPU call
     * site reuses the same kernel for the fine-texture scale; only the
     * radius — and therefore the Gaussian constants — differ from
     * stage 21). Verbatim (core/src/Develop.cpp): the luma plane, the
     * 4-tap discrete Gaussian at floor(radius) spacing (step 1 for
     * 0.75 too: the CPU's `radius < 1.0f ? 1.0f : floor(radius)`), the
     * clamped edges, the norm division. Writes the blurred-luma plane
     * to .x (the CPU's tmp buffer); the vertical pass (DETAIL_V) reads
     * it back beside the stage's ORIGINAL RGB (uGuide — the sharpness
     * output when stage 21 ran, else the pointwise POST pass's output:
     * the CPU runs stage 22 on the stage-21 output).
     *
     * uDetail carries the RAW grade.detail value with the same gate
     * and amount mapping as SHARPNESS_H (the CPU call site's own:
     * clamp(0.5 * (raw/100), -0.5, 0.5), negatives valid).
     */
    const val DETAIL_H: String = """#version 300 es

// Detail, the horizontal half (stage 22 pass 1 of 2) - the CPU
// rawedit::unsharpLumaImpl's horizontal blur run at radius 0.75, verbatim
// (core/src/Develop.cpp): the same kernel as stage 21 at the fine-texture
// scale. Writes the horizontally blurred luma plane to .x (the CPU's tmp
// buffer); the vertical pass (DETAIL_V) reads it back beside the ORIGINAL
// pixels (uGuide) and finishes the CPU's tail. See the Kotlin doc above
// for the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;   // the sharpness output, or the pointwise post pass's output when stage 21 is off
uniform ivec2 uSize;            // width/height in pixels
uniform float uDetail;          // raw grade.detail; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (radius 0.75 -> step 1 both
// directions, the CPU's `radius < 1.0f ? 1.0f : floor(radius)`; exp() of
// constant arguments is a constant expression in ES 3.00, so they keep
// const exactly like the CPU's).
const float kRadius = 0.75;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kNorm = 1.0 + 2.0 * (kC0 + kC1);

// The CPU's luma split at p (the plane the CPU computes once per pixel;
// recomputed per read - identical math, identical answers).
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uSrc, g, 0).rgb;
    // The CPU call site gates the whole stage on grade.detail != 0; the
    // amount mapping is the CPU call site's own: 0.5 * (raw/100) clamped
    // to [-0.5, 0.5] - NEGATIVE values are valid (softening).
    if (uDetail != 0.0) {
        float amount = clamp(0.5 * (uDetail / 100.0), -0.5, 0.5);
        // The CPU's horizontal pass, verbatim: the clamped columns (step
        // = 1 for radius 0.75 - the CPU's own < 1 fallback), the exact
        // tap order xm, xp, xm2, xp2, and the norm division.
        float y = lumaAt(g);
        int xm = max(g.x - 1, 0);
        int xp = min(g.x + 1, uSize.x - 1);
        int xm2 = max(g.x - 2, 0);
        int xp2 = min(g.x + 2, uSize.x - 1);
        float s = y
            + (lumaAt(ivec2(xm, g.y)) + lumaAt(ivec2(xp, g.y))) * kC0
            + (lumaAt(ivec2(xm2, g.y)) + lumaAt(ivec2(xp2, g.y))) * kC1;
        oCol = vec4(s / kNorm, 0.0, 0.0, 1.0);
        return;
    }
    // Gate closed: the ORIGINAL luma to .x, so the vertical pass's
    // center-relative math stays exact (its blur degenerates to the
    // original luma; the CPU's tail delta is exactly 0).
    oCol = vec4(lumaAt(g), 0.0, 0.0, 1.0);
}
"""

    /**
     * Detail, the VERTICAL half + the difference (CPU stage 22, pass
     * 2 of 2) — rawedit::unsharpLumaImpl's vertical blur run and its
     * high-pass tail at radius 0.75, verbatim (core/src/Develop.cpp).
     *
     * Inputs: uSrc — DETAIL_H's output (.x = the horizontally blurred
     * luma plane, the CPU's tmp buffer); uGuide — this stage's ORIGINAL
     * input (the sharpness output when stage 21 ran, else the pointwise
     * POST pass's output — the CPU runs stage 22 on the stage-21
     * output). The center tap is the HORIZONTAL OUTPUT at the same
     * pixel; the tail's delta is amount * (ORIGINAL luma - blur) added
     * to r, g and b equally. Output: the detail-graded RGB.
     *
     * uDetail carries the RAW grade value with the same gate and
     * amount mapping as DETAIL_H. The tap order (ym, yp, ym2, yp2) and
     * the norm division are the CPU's own, verbatim.
     */
    const val DETAIL_V: String = """#version 300 es

// Detail, the vertical half + the difference (stage 22 pass 2 of 2) - the
// CPU rawedit::unsharpLumaImpl's vertical blur run and its high-pass tail
// at radius 0.75, verbatim (core/src/Develop.cpp). Reads the horizontal
// pass's blurred luma plane from uSrc (.x) and the ORIGINAL pixels from
// uGuide (the sharpness output when stage 21 ran, else the pointwise post
// pass's output): the CPU's vertical pass centers on the HORIZONTAL
// OUTPUT at the same pixel, and its tail's delta is amount * (ORIGINAL
// luma - blur) added to r, g and b equally. See the Kotlin doc above for
// the full stage contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;    // DETAIL_H's output: .x = the horizontally blurred luma
uniform highp sampler2D uGuide;  // the sharpness output, or the pointwise post pass's output when stage 21 is off
uniform ivec2 uSize;             // width/height in pixels
uniform float uDetail;           // raw grade.detail; 0.0 = the CPU call site's own skip

out vec4 oCol;

// The CPU's own constants, verbatim (same as the horizontal pass).
const float kRadius = 0.75;
const float kC0 = exp(-0.5 / (kRadius * kRadius));
const float kC1 = exp(-2.0 / (kRadius * kRadius));
const float kNorm = 1.0 + 2.0 * (kC0 + kC1);

// The CPU's luma split at p of the ORIGINAL image (uGuide): the plane the
// CPU computed once from its input, and the plane its tail's delta reads.
float lumaAt(ivec2 p) {
    vec3 c = texelFetch(uGuide, p, 0).rgb;
    return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 orig = texelFetch(uGuide, g, 0).rgb;
    if (uDetail != 0.0) {
        float amount = clamp(0.5 * (uDetail / 100.0), -0.5, 0.5);
        // The CPU's vertical pass, verbatim: the clamped rows (step = 1
        // for radius 0.75 - the CPU's own < 1 fallback), the exact tap
        // order ym, yp, ym2, yp2, the CENTER tap = the horizontal output
        // at the SAME pixel (its v = tmp[y*w+x]), and the norm division.
        int ym = max(g.y - 1, 0);
        int yp = min(g.y + 1, uSize.y - 1);
        int ym2 = max(g.y - 2, 0);
        int yp2 = min(g.y + 2, uSize.y - 1);
        float v = texelFetch(uSrc, g, 0).x;
        float s = v
            + (texelFetch(uSrc, ivec2(g.x, ym), 0).x
                + texelFetch(uSrc, ivec2(g.x, yp), 0).x) * kC0
            + (texelFetch(uSrc, ivec2(g.x, ym2), 0).x
                + texelFetch(uSrc, ivec2(g.x, yp2), 0).x) * kC1;
        float blur = s / kNorm;
        // The CPU's tail, verbatim: the scaled high-pass delta from the
        // ORIGINAL luma, added to r, g and b equally (chroma and hue
        // are preserved - no chroma halos).
        float d = amount * (lumaAt(g) - blur);
        oCol = vec4(orig + vec3(d), 1.0);
        return;
    }
    // Gate closed: exact pass-through (the CPU's amount-0 shape).
    oCol = vec4(orig, 1.0);
}
"""

    /**
     * Gyro warp (the stabilization resample) — the frozen CPU reference
     * rawedit::warpFrame's per-pixel map, verbatim (core/src/Warp.cpp).
     * The warp is NOT a develop stage: the CPU pipeline runs it AFTER
     * developCpu, on the linear output, so the session chain draws this
     * pass between the four unsharp passes (21/22) and the tail
     * (23 + 24 + OETF) — the OETF stays in the tail, applied exactly
     * once.
     *
     * The composite map: p_src = Hinv( c + (p_out - c)/Z ) with Z =
     * max(zoom, 1) and c the buffer center. The CALLER resolves the
     * frame's stabilization plan on its own thread (the plan cache is
     * not GL-thread-safe), composes M = Hinv * S ONCE per frame (S the
     * scale-about-center map — the CPU's own loop in warpFrame), narrows
     * the 9 doubles to floats and uploads them here row-major with
     * glUniformMatrix3fv(..., GL_TRUE, ...) — the uCam2rgb idiom: the
     * transpose makes `uM * vec3(x, y, 1.0)` the CPU mapPoint's exact
     * row-major homogeneous tap (u = m[0]x + m[1]y + m[2],
     * v = m[3]x + m[4]y + m[5], w = m[6]x + m[7]y + m[8]).
     *
     * Semantics locked to the CPU, verbatim: an unmappable output pixel
     * (a non-finite or ~zero w — |w| < 1e-12 — or a non-finite landing
     * point) clamps to (0, 0), the documented edge behavior (isfinite
     * is not an ES 3.00 builtin, so the check is isinf/isnan); the
     * mapped coordinates clamp to [0, w-1] / [0, h-1]; the integer
     * truncation is the CPU's uint32 cast (floor — the coordinates are
     * >= 0 after the clamp); x1/y1 are the EDGE clamp (x0 + 1 while
     * inside, else x0 — never a mirror); the bilinear tap order is
     * p00 = (x0, y0), p01 = (x1, y0), p10 = (x0, y1), p11 = (x1, y1),
     * the top/bot row lerp then the vertical lerp.
     *
     * NO in-shader gate: the caller folds inactive (no gyro / the grade
     * not stabilizing), identity (H == I and zoom == 1 — the CPU's
     * byte-identical fast path) and degenerate (a singular H, a
     * non-finite zoom) plans into warpActive = false and the session
     * SKIPS the draw entirely — the pass's absence is the identity,
     * because a resampling pass on an identity map would blur.
     *
     * uSrc rides unit 1 (the chain's pass-source texture, rebound per
     * draw); uSize is the buffer dims; the output is the warped RGB,
     * alpha 1.
     */
    const val WARP: String = """#version 300 es

// Gyro warp - the CPU rawedit::warpFrame's per-pixel resample, verbatim
// (core/src/Warp.cpp). The caller resolves the frame's stabilization
// plan on its own thread and composes the inverse-sampling map ONCE per
// frame - M = Hinv * S, S the scale-about-center map at zoom Z - and
// uploads the 9 floats here row-major (transpose=GL_TRUE: the uCam2rgb
// idiom, so uM * vec3(x, y, 1.0) is the CPU mapPoint's exact row tap).
// NO gate: the session skips this draw entirely when the warp is
// inactive - the pass's ABSENCE is the identity (a resample on an
// identity map would blur). See the Kotlin doc above for the contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;  // the chain's pass-source texture (unit 1)
uniform ivec2 uSize;           // width/height in pixels
uniform mat3 uM;               // the composed map M = Hinv * S, row-major

out vec4 oCol;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    // The CPU's mapPoint, verbatim: the homogeneous tap, then the
    // perspective divide. Unmappable pixels (a non-finite or ~zero w,
    // or a non-finite landing) clamp to (0, 0) - the CPU's documented
    // edge behavior. isfinite is not an ES 3.00 builtin, so the check
    // is isinf/isnan.
    vec3 h = uM * vec3(float(g.x), float(g.y), 1.0);
    float ox = 0.0;
    float oy = 0.0;
    if (!isinf(h.z) && !isnan(h.z) && abs(h.z) >= 1e-12) {
        float mx = h.x / h.z;
        float my = h.y / h.z;
        if (!isinf(mx) && !isnan(mx) && !isinf(my) && !isnan(my)) {
            ox = mx;
            oy = my;
        }
    }
    // The CPU's clamps, verbatim: [0, w-1] / [0, h-1], truncation (the
    // coordinates are >= 0 after the clamp, so int() is the CPU's
    // uint32 cast), and the EDGE clamp - x1 is x0 + 1 while inside,
    // else x0; never a mirror.
    float fx = clamp(ox, 0.0, float(uSize.x - 1));
    float fy = clamp(oy, 0.0, float(uSize.y - 1));
    int x0 = int(fx);
    int y0 = int(fy);
    int x1 = x0 + 1 < uSize.x ? x0 + 1 : x0;
    int y1 = y0 + 1 < uSize.y ? y0 + 1 : y0;
    float ax = fx - float(x0);
    float ay = fy - float(y0);
    // The CPU's bilinear, verbatim tap order: p00 = (x0, y0),
    // p01 = (x1, y0), p10 = (x0, y1), p11 = (x1, y1); the top/bot row
    // lerp, then the vertical lerp.
    vec3 p00 = texelFetch(uSrc, ivec2(x0, y0), 0).rgb;
    vec3 p01 = texelFetch(uSrc, ivec2(x1, y0), 0).rgb;
    vec3 p10 = texelFetch(uSrc, ivec2(x0, y1), 0).rgb;
    vec3 p11 = texelFetch(uSrc, ivec2(x1, y1), 0).rgb;
    vec3 top = p00 + (p01 - p00) * ax;
    vec3 bot = p10 + (p11 - p10) * ax;
    oCol = vec4(top + (bot - top) * ay, 1.0);
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

    /**
     * Blit fragment pass (deliverable F core, owner request 2026-11-12
     * §10 "Encoder Surface export") — the pass MediaCodecMp4Writer's
     * Surface-input mode draws to the encoder's input Surface: one
     * fullscreen draw of the developed frame texture per exported frame.
     *
     * Unlike the passes above (which the NATIVE parity harness compiles
     * through the JNI seam), this one is compiled by the WRITER itself,
     * in Kotlin EGL14/GLES30 at export time (MediaCodecMp4Writer's
     * buildBlitProgram) — still THIS exact text, still host-validated
     * (Kind.BLIT_FRAGMENT), so there is no second copy to drift.
     *
     * Program: FULLSCREEN_VERTEX + BLIT_FRAGMENT (both #version 300 es —
     * GLSL ES forbids linking mixed versions into one program). One
     * glDrawArrays(GL_TRIANGLE_STRIP, 0, 4) per frame onto the EGL
     * window surface created on the codec's createInputSurface; the
     * writer then stamps the frame's pts (eglPresentationTimeANDROID,
     * nanoseconds) and swaps.
     *
     * THE Y FLIP: the encoder consumes the Surface buffer TOP-row-first
     * (buffer row 0 = the image's top), while GL's gl_FragCoord row 0 is
     * the render target's BOTTOM row. The frame texture is uploaded
     * top-row-first (texture row 0 = the image's top — the CPU pipeline's
     * own packed order), so destination row g.y (bottom-up) must read
     * source row uSize.y - 1 - g.y (top-down). texelFetch's INTEGER
     * coordinates make the flip exact — there is no half-pixel math here
     * to get wrong.
     *
     * THE ROTATION FOLD (the surface-gpu rotation deliverable): uRotation
     * carries the frame's EFFECTIVE orientation normalized to 0/90/180/270
     * (RotationState.effectiveDeg — the same set the CPU rotate path
     * handles), and the fold reproduces RotationState.rotateRgb888's
     * per-degree source index math in integer texel space, inverted
     * dest->source. uSize stays the SOURCE's w x h on every arm; a
     * quarter turn (90/270) swaps the DESTINATION viewport to h x w (the
     * session sets glViewport accordingly), so the upright frame lands
     * upright — no second pass, no CPU readback. 0 keeps today's plain
     * Y flip arm byte-identical. The uniform defaults to 0 in GLSL, so
     * the WRITER's Surface mode (which never sets it — it blits upright
     * RGB888) compiles the SAME text and runs the 0 arm unchanged; only
     * the develop session sets it.
     *
     * Input: uSrc — the developed frame, GL_RGB8, width x height,
     * top-row-first, uploaded per frame by the writer (glTexImage2D,
     * unpack alignment 1). Output: the window surface's back buffer
     * (RGBA8888 — the recordable EGL config; 8 bits is the honest
     * precision for this stage: the frame it receives is the pipeline's
     * packed RGB888, and the encode target is 8-bit SDR YUV; the float
     * FBO domain belongs to the parity harness, not the encoder blit).
     * No filtering (texelFetch), no wrap (coordinates never leave
     * [0, size-1]), no readback of any kind — this pass's entire job is
     * to land the frame in the encoder.
     */
    const val BLIT_FRAGMENT: String = """#version 300 es

// Blit pass: developed frame texture -> the encoder's input Surface
// (MediaCodecMp4Writer Surface-input mode). See the Kotlin doc above.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;  // the frame: GL_RGB8, w x h, top-row-first
uniform ivec2 uSize;          // SOURCE frame width/height in pixels
uniform int uRotation;        // effective orientation, 0/90/180/270 (default 0)

out vec4 oCol;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    // The Y flip: destination row g.y counts from the BOTTOM (GL), the
    // texture's row 0 is the image's TOP row (the upload order), and the
    // encoder reads the Surface buffer top-first. Integer texelFetch
    // coordinates - the flip is exact.
    // The rotation fold (uRotation, 0/90/180/270): rotateRgb888's per-degree
    // destination index math, inverted dest->source. uSize is ALWAYS the
    // SOURCE's w x h; a quarter turn's destination viewport is h x w (the
    // session swaps glViewport), so g spans the rotated frame while src
    // spans the source. 0 = today's plain Y flip, byte-identical.
    ivec2 src;
    if (uRotation == 90) {
        src = ivec2(uSize.x - 1 - g.y, uSize.y - 1 - g.x);
    } else if (uRotation == 180) {
        src = ivec2(uSize.x - 1 - g.x, g.y);
    } else if (uRotation == 270) {
        src = ivec2(g.y, g.x);
    } else {
        src = ivec2(g.x, uSize.y - 1 - g.y);
    }
    oCol = vec4(texelFetch(uSrc, src, 0).rgb, 1.0);
}
"""

    /**
     * Window fragment pass (M4, the SurfaceView GPU preview deliverable) —
     * the pass the GPU preview session (GlesPreviewSession.cpp) draws BOTH
     * the editor's SurfaceView window and its downsampled stats target
     * with: the developed preview texture, aspect-fit letterboxed into the
     * destination rect, the frame orientation folded in-shader.
     *
     * Program: FULLSCREEN_VERTEX + WINDOW_FRAGMENT (both #version 300
     * es). One glDrawArrays(GL_TRIANGLE_STRIP, 0, 4) per target; the
     * fullscreen strip covers the whole surface, so the pass paints the
     * letterbox bars itself (pixels outside uRect write black — no clear
     * is needed to get the bars).
     *
     * WHY NOT BLIT_FRAGMENT: the blit is a texelFetch 1:1 with an exact
     * Y flip — integer coordinates cannot letterbox-scale (an upscale
     * reads outside the source, a downscale crops it). This pass samples
     * NORMALIZED (texture() + CLAMP_TO_EDGE + LINEAR on filterable
     * formats), maps each destination pixel through uRect, and clamps
     * to the source's half-texel span so an upscale never blends past
     * the edge texels.
     *
     * THE ORIENTATION FOLD: uOrientation carries FrameInfo.orientationDeg
     * normalized to 0/90/180/270 — the same fold developToArgb applies
     * per-pixel on the CPU preview path, reproduced in normalized space
     * and verified against its per-case source index. The source texture
     * is top-row-first (texture row 0 = the image's top, the CPU
     * pipeline's packed order) while gl_FragCoord counts rows bottom-up,
     * so the default arm carries the Y flip; both the window surface's
     * compositor and the stats readback pack consume the result
     * top-first.
     *
     * HALF-TEXEL CLAMP: uSrcSize exists for the clamp (an unused uniform
     * would be optimized out and the session's all-uniforms lookup would
     * fail loudly) — normalized st never crosses the source's edge
     * texel centers.
     *
     * Input: uSrc — the session's RGBA32F develop texture (top-row-first)
     * for BOTH the window and stats draws, sampled NEAREST — the NEAREST
     * PIN (owner device evidence 2026-11-15, the white window): the
     * session still reads OES_texture_float_linear in its own context at
     * open, but since the white-window fix the verdict is NOT fed to the
     * develop texture's MIN/MAG filter; on the failing Adreno the
     * extension is advertised yet the WINDOW pass's LINEAR reads of the
     * RGBA32F texture painted WHITE (771 frames, zero GL errors, the
     * stats draw of the SAME texture healthy), so the pin rides the
     * session side and the develop texture stays NEAREST — the encoder
     * blit's proven sampling shape. There is deliberately NO intermediate
     * present texture: a second chained window pass would double-flip —
     * one fold maps a top-row-first source to a bottom-up destination,
     * and composing two folds yields an upside-down image (the fold
     * family has no identity arm). Output: the window surface (RGBA8888)
     * or the stats FBO (RGBA8). No readback of any kind — this pass only
     * lands pixels.
     *
     * THE CROP BLOCK (A2 GPU crop/straighten preview, owner request
     * 2026-11-23, guide A2): an active crop/straighten no longer forces
     * the whole preview to the CPU — the window pass maps it here, in
     * shader, with the CPU reference's exact math. SIX uniforms
     * (uCropOn/uCropRect/uCropOut/uCropZoom/uCropCos/uCropSin) carry
     * GpuCropMap.h planCrop's frame plan; the rect is CONTAINER-upright
     * fractions (Kotlin already mapRect'ed it through CropState — the
     * exact PublishCrop/applyToArgb space mapping; the manual quarter
     * turn rides PreviewSurface's graphicsLayer OUTSIDE the draw).
     *
     * VERIFIED SPACE DERIVATION (the compose order, checked against all
     * four fold arms): uv IS the upright-display normalized coordinate
     * (x rightward, y bottom-up; the drawn rect covers upright content),
     * and the uOrientation fold is exactly what turns upright uv into
     * sensor st — so the crop map runs on uv BEFORE the fold and
     * composes ORIENTATION-INDEPENDENTLY: an upright top-down position
     * p = (px, py) maps to the fold's input as `uvFull = (px, 1.0 -
     * py)` under EVERY one of 0/90/180/270 (each fold arm solved against
     * p reduces to this — e.g. orient 90: fold st = (1-uv.y, 1-uv.x)
     * with sensor = (py, 1-px) gives uv = (px, 1-py)). The crop's output
     * px offsets use the CPU convention (applyToArgb's own dx/dy, both
     * verified equal): dx = (uv.x - 0.5) * uCropOut.x, dy = (0.5 -
     * uv.y) * uCropOut.y — then the inverse map R(-theta)/zoom about the
     * rect centre, exactly as applyToArgb.
     *
     * CROP SAMPLING IS MANUAL BILINEAR — 4x texelFetch with per-axis
     * index clamping, mirroring CropState.sampleArgbBilinear exactly —
     * because the develop texture stays NEAREST (the pin above; the
     * white window's verdict). The CPU clamps texel INDICES, never
     * normalized coordinates: the half-texel st clamp must NOT run on
     * the crop branch (it would break parity at the edges). uCropOn = 0
     * keeps today's path BYTE-IDENTICAL: the same fold, the same
     * half-texel clamp, the same texture(uSrc, st).
     *
     * A3 PLACEMENT (pinned): the zebra block (guide A3) composes AFTER
     * this crop block — it reads the FINAL sampled color and rect-local
     * coords; keep the sampling result in `c` and the rect-local uv
     * available at the bottom of main().
     *
     * THE ZEBRA BLOCK (A3 GPU zebra, owner request 2026-11-23, guide §A3):
     * zebra on/off no longer forces the CPU preview path — the WINDOW
     * draw paints the classic 45-degree stripes HERE, at window
     * resolution (crisp, never the blocky 256-px pack), with the values
     * SINGLE-SOURCED from Kotlin (ZebraOverlay.STRIPE_PERIOD /
     * ZebraOverlay.STRIPE_ALPHA / FrameHistogram.WHITE_CLIP_VALUE the GPU
     * arm threads through the seam — no literal in this file carries
     * them). FOUR uniforms carry the block's knobs; the DECISION MATH
     * mirrors ZebraOverlay.zebraStriped byte-exactly:
     *  - toByte quantize: int(clamp(c, 0, 1) * 255 + 0.5) — GLSL int()
     *    truncates, so the + 0.5 makes truncation the CPU toByte's exact
     *    round-ties-up (jni_bridge.cpp's toByte, the stats pack's OWN
     *    quantize; a *256 scale or a half-down round breaks agreement at
     *    the 252/253 boundary, exactly where the threshold lives);
     *  - luma8 = (54 * r8 + 183 * g8 + 19 * b8) >> 8 — FrameHistogram.luma
     *    on the quantized bytes (integer byte space, highp int);
     *  - rect-local window coords: gl_FragCoord.xy is the pixel CENTER
     *    (x + 0.5, y + 0.5), so p = ivec2(gl_FragCoord.xy - uRect.xy -
     *    0.5) strips the half — PINNED choice: it makes (p.x + p.y) the
     *    CPU's integer (x + y), so the stripe PHASE matches the CPU
     *    zebra the owner compares against (guide acceptance: "clipped
     *    areas match the CPU zebra");
     *  - striped when ((p.x + p.y) / uZebraPeriod) % 2 == 0 — GLSL
     *    integer division truncates toward zero and p is non-negative
     *    inside uRect, so / then % 2 matches Kotlin's (x + y) / period
     *    and 1;
     *  - the striped pixel blends toward black on the FLOAT rgb before
     *    the write (c * (1 - uZebraAlpha)); the target's RGBA8 quantize
     *    lands within +-1 LSB of the CPU's byte-domain (v * (255 -
     *    200)) / 255 (the twin/window surface write quantizes the same
     *    round-ties-up), and non-striped + letterbox pixels are
     *    untouched (the letterbox early-return stays BEFORE any zebra
     *    work — the bars stay pure black).
     * WINDOW DRAW ONLY: draw 3 (the stats window pass) sets uZebraOn = 0
     * — the scope compute keeps analyzing the CLEAN frame. The CPU
     * backend keeps ZebraOverlay.paint. Manual quarter rotation via
     * graphicsLayer rotates the stripes — acceptable (guide design
     * point 5).
     */
    const val WINDOW_FRAGMENT: String = """#version 300 es

// Window pass: the developed preview texture -> the SurfaceView window
// (aspect-fit letterbox) or the downsampled stats target. See the
// Kotlin doc above.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;  // the developed frame, top-row-first
uniform ivec2 uSrcSize;        // source width/height in pixels
uniform vec4 uRect;            // destination rect: origin + size in pixels
uniform int uOrientation;      // 0/90/180/270, degrees CW to upright
// The crop block (A2, owner request 2026-11-23, guide A2): the frame
// plan GlesPreviewSession uploads from gles::planCrop. uCropOut is the
// CROPPED output dims (px); uCropRect is the mapRect'ed rect fractions
// (container-upright); uCropZoom/uCropCos/uCropSin carry the auto-zoom
// and the double-theta trig pair (CropState's precision -- GLSL never
// runs its own sin/cos on the angle).
uniform int uCropOn;           // 1 = the crop map runs (the identity gate)
uniform vec4 uCropRect;        // crop rect: x/y origin + w/h, upright fractions
uniform vec2 uCropOut;         // cropped output dims (pixels)
uniform float uCropZoom;       // the straighten auto-zoom (>= 1)
uniform float uCropCos;        // cos(theta) from a double theta cast
uniform float uCropSin;        // sin(theta), the same precision
// The zebra block (A3, owner request 2026-11-23, guide A3): the four
// knobs the window draw sets from the request -- the values arrive from
// Kotlin (ZebraOverlay.STRIPE_PERIOD / ZebraOverlay.STRIPE_ALPHA /
// FrameHistogram.WHITE_CLIP_VALUE the GPU arm threads through the seam;
// single-sourced, never literals here). uZebraOn = 0 keeps today's path
// byte-identical (the stats draw always pins it so: CLEAN scopes).
uniform int uZebraOn;          // 1 = the zebra decisions run (the gate)
uniform int uZebraThreshold;   // the clip value, luma8 >= this stripes
uniform int uZebraPeriod;      // the stripe period in window pixels
uniform float uZebraAlpha;     // [0,1]: the black blend fraction

out vec4 oCol;

// The 8-bit level of a float color: clamp [0,1], round-ties-up x255 (toByte's shape).
vec3 q8(vec3 v) {
    return floor(clamp(v, 0.0, 1.0) * 255.0 + 0.5) / 255.0;
}

void main() {
    // Destination pixel -> rect-local [0,1]. Pixels outside the rect are
    // the letterbox bars: black, not a smeared edge sample.
    vec2 uv = (gl_FragCoord.xy - uRect.xy) / uRect.zw;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        oCol = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // --- the crop map (A2, 2026-11-23): runs on uv BEFORE the orientation
    // --- fold (the fold consumes uvFull, never the raw uv). The drawn
    // --- rect now covers the CROPPED output, so uv is output-crop-local;
    // --- the map walks it back to the full upright frame's display uv.
    vec2 uvFull;
    if (uCropOn == 1) {
        // The upright dims (the fold's own swap): rect fractions and the
        // output px offsets live in upright space.
        vec2 up = (uOrientation == 90 || uOrientation == 270)
            ? vec2(uSrcSize.yx) : vec2(uSrcSize);
        // Output px offsets in the CPU convention (applyToArgb's dx/dy,
        // verified equal on both axes): dy grows DOWN over the output,
        // which is the (0.5 - uv.y) swap on the bottom-up uv axis.
        float dx = (uv.x - 0.5) * uCropOut.x;
        float dy = (0.5 - uv.y) * uCropOut.y;
        // The inverse map R(-theta)/zoom about the rect centre -- EXACTLY
        // applyToArgb's per-pixel math (cropSourcePosition).
        float ox = (dx * uCropCos + dy * uCropSin) / uCropZoom;
        float oy = (-dx * uCropSin + dy * uCropCos) / uCropZoom;
        vec2 ctr = (uCropRect.xy + uCropRect.zw * 0.5) * up;
        // Back to the fold's input: upright display uv, the
        // orientation-independent form (top-down py flips to bottom-up:
        // 1.0 - ... on the y axis -- the source-side flip the fold below
        // carries is the OTHER flip; this one lands the sample in the
        // upright frame, that one turns upright into sensor space).
        uvFull = vec2((ctr.x + ox) / up.x, 1.0 - (ctr.y + oy) / up.y);
    } else {
        uvFull = uv;  // today's path verbatim
    }
    // Orientation fold into source-normalized st (developToArgb's
    // per-case rotation fold, verified against its source index). The
    // source is top-row-first; the destination counts rows bottom-up, so
    // the default arm carries the Y flip. Since A2 the fold reads
    // uvFull (the crop-mapped uv when the crop is on, the raw uv
    // otherwise) on EVERY arm.
    vec2 st;
    if (uOrientation == 90) {
        st = vec2(1.0 - uvFull.y, 1.0 - uvFull.x);
    } else if (uOrientation == 180) {
        st = vec2(1.0 - uvFull.x, uvFull.y);
    } else if (uOrientation == 270) {
        st = vec2(uvFull.y, uvFull.x);
    } else {
        st = vec2(uvFull.x, 1.0 - uvFull.y);
    }
    // THE SAMPLING FORK (A2): crop-on uses MANUAL bilinear -- 4x
    // texelFetch, mirroring CropState.sampleArgbBilinear's tap geometry
    // EXACTLY (floor(s - 0.5) indices clamped per axis, the +1 tap
    // collapsed at the frame edge, weights coerced [0,1]) -- because the
    // develop texture is pinned NEAREST (the white-window pin; the CPU
    // twin clamps texel INDICES, so NO half-texel st clamp runs on this
    // branch). Crop-off keeps today's half-texel clamp + texture() fetch,
    // byte-identical to the pre-A2 pass.
    vec3 c;
    if (uCropOn == 1) {
        vec2 sp = st * vec2(uSrcSize);        // SOURCE px (st is over the source dims)
        ivec2 p0 = clamp(ivec2(floor(sp - 0.5)), ivec2(0), uSrcSize - ivec2(1));
        ivec2 p1 = min(p0 + 1, uSrcSize - 1);  // the +1 tap, per axis
        vec2 f = clamp(sp - 0.5 - vec2(p0), 0.0, 1.0);  // coerced weights
        // Each tap is quantized to the 8-bit level the CPU twin interpolates
        // (clamp [0,1], round-ties-up x255): the CPU samples the already
        // clamped/rounded ARGB, while this texture is float and may hold
        // values above 1.0 (clipped highlights), so interpolating the raw
        // taps and clamping afterwards disagreed with the CPU on every
        // clipped edge and speck (device crop sweep 2026-11-25: sparse
        // 40-80 LSB outliers on highlights).
        vec3 c00 = q8(texelFetch(uSrc, p0, 0).rgb);
        vec3 c10 = q8(texelFetch(uSrc, ivec2(p1.x, p0.y), 0).rgb);
        vec3 c01 = q8(texelFetch(uSrc, ivec2(p0.x, p1.y), 0).rgb);
        vec3 c11 = q8(texelFetch(uSrc, p1, 0).rgb);
        vec3 topo = c00 + (c10 - c00) * f.x;
        vec3 bottom = c01 + (c11 - c01) * f.x;
        c = topo + (bottom - topo) * f.y;
    } else {
        // Half-texel clamp: normalized sampling never crosses the source's
        // edge texel centers (bilinear would blend against the clamp border
        // otherwise). This line is why uSrcSize exists.
        vec2 texelHalf = vec2(0.5) / vec2(uSrcSize);
        st = clamp(st, texelHalf, vec2(1.0) - texelHalf);
        c = texture(uSrc, st).rgb;
    }
    // THE ZEBRA BLOCK (A3, owner request 2026-11-23, guide A3): composes
    // AFTER the crop sampling above -- it reads the FINAL sampled color
    // `c` and the rect-local coords, never re-samples. The decision math
    // is ZebraOverlay.zebraStriped's exact byte-for-byte mirror (the
    // Kotlin doc above pins each token).
    oCol = vec4(c, 1.0);
    if (uZebraOn == 1) {
        // (a) toByte quantize per channel: clamp [0,1] then
        // round-ties-up v*255 + 0.5 -- int() truncates, so the + 0.5 IS
        // the round; jni_bridge.cpp toByte's exact shape (the stats
        // pack's own quantize), not a *256 approximation.
        int r8 = int(clamp(c.r, 0.0, 1.0) * 255.0 + 0.5);
        int g8 = int(clamp(c.g, 0.0, 1.0) * 255.0 + 0.5);
        int b8 = int(clamp(c.b, 0.0, 1.0) * 255.0 + 0.5);
        // (b) Rec.709 luma on the quantized bytes -- FrameHistogram.luma.
        int luma8 = (54 * r8 + 183 * g8 + 19 * b8) >> 8;
        // (c) Rect-local WINDOW pixel coords: gl_FragCoord.xy is the
        // pixel CENTER (x+0.5, y+0.5), so strip the half -- (p.x + p.y)
        // is then the CPU zebra's integer (x + y) and the stripe PHASE
        // matches the CPU pattern the owner compares against (pinned
        // choice; a raw gl_FragCoord read would phase-shift every
        // stripe by the half).
        ivec2 p = ivec2(gl_FragCoord.xy - uRect.xy - vec2(0.5));
        // (d) striped when ((x + y) / period) is even -- GLSL integer
        // division truncates toward zero and p is non-negative inside
        // uRect, so / then % 2 matches Kotlin's (x + y) / period and 1.
        bool striped = ((p.x + p.y) / uZebraPeriod) % 2 == 0;
        // (e) The blend toward black on the FLOAT rgb before the RGBA8
        // write: the byte quantize lands within +-1 LSB of the CPU's
        // byte-domain (v * (255 - 200)) / 255 -- the twin/window surface
        // write quantizes with the same round-ties-up. Non-striped and
        // letterbox pixels are untouched.
        if (luma8 >= uZebraThreshold && striped) {
            oCol.rgb *= (1.0 - uZebraAlpha);
        }
    }
}
"""

    /**
     * The NODES pass (color model v2 F1-5) — the grade node chain as ONE
     * draw between the warp and the TAIL (plan §3.2 stage 6, §5.3's
     * one-pass decision K8). The chain:
     *
     *   linear 709 --(uM709ToDwg)--> DWG linear --(DI encode)--> DI
     *     --[node 0 .. uNodeCount-1]--> DI
     *   --(DI decode)--> DWG linear --(uNodesOutputMode selects)-->
     *     0: uMDwgTo709 -> linear 709        (TAIL's v1 variant reads it)
     *     1: DWG linear                      (TAIL's DRT variant reads it)
     *
     * The node math is rawedit::applyCompiledNode CORE/src/ColorModel.cpp
     * VERBATIM (the per-pixel order: primaries, the luminance side,
     * contrast*wb, the hue matrix, saturation, the curves, the node LUT,
     * mix) — every value that is INIT-time on the CPU (the wheel cosines,
     * the primaries solve, the hue rotation) arrives PRE-COMPILED through
     * the NodeParcel uniforms (gles/NodeParcel.h; the inventory §2.1's
     * "derleme-zamanı" split), so the shader only re-applies.
     *
     * THE CURVE ATLAS (the inventory §2.4): the composite curve tables
     * (3 x 512 floats per node) NEVER ride uniforms — they live in ONE
     * 2D R32F atlas, unit 6: row = node*3 + channel, column 0..511, the
     * table's VALUE at x_i over DI range [-0.5, 1.5] exactly as
     * rawedit::nodeCurveTable computed it. Sampling is rawedit::
     * nodeCurveSample VERBATIM: texelFetch the bracketing pair + a manual
     * lerp on the DELTAS (t[i] - x_i) — R32F is texelFetch-only on Adreno
     * and the CPU's own lerp is carried by hand either way, the
     * sampleShading precedent. A node without curves skips the fetch
     * entirely (uNodeHasCurves gate).
     *
     * THE NODE LUT (the inventory §2.5): at most 4 nodes carry a LUT; the
     * tables are 3D RGBA16F on units 7..10, uploaded revision-cached by
     * the pipeline in the lutIndex order the parcel fixes, using the SAME
     * format/binding contract as the stage-24 LUT (DOMAIN scaling,
     * encode/decode transfers, the working-space matrices). The branch is
     * the CPU applyCompiled's own order: DI -> DWG -> 709 (the binding's
     * working space) -> the managed sample -> back to DI, mixed by
     * uNodeLutMix in DI.
     *
     * uNodesCount = 0 + outputMode 0 = the pure conversion: DECODE +
     * the 709 matrix only (the plan's "empty chain + legacy" never adds
     * the pass at all — the pipeline skips it; the shape is defended so a
     * drawn zero-node pass is the 709 -> DWG -> 709 round trip).
     *
     * The pass reads ONE texel and writes one: in = the chain's live slot
     * (unit 1), out = chainNextSlot (ChainSlots' kPassNodes, no guide).
     */
    const val NODES: String = """#version 300 es

// The NODES pass - rawedit::applyNodes' per-pixel math, verbatim (core/src/
// ColorModel.cpp; the parcel's uniforms are its own CompiledNode values).
// See the Kotlin doc above for the chain and the atlas contract.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;     // the chain's RGBA32F intermediate (unit 1): linear Rec.709
uniform ivec2 uSize;              // width/height in pixels
uniform int uNodesCount;          // 0..8; 0 = the conversion-only shape
uniform int uNodesOutputMode;     // 0: decode -> uMDwgTo709 (TAIL's v1 variant); 1: emit DWG linear (DRT variant)
uniform mat3 uM709ToDwg;          // row-major, uploaded transpose=GL_TRUE (the uCam2rgb idiom)
uniform mat3 uMDwgTo709;          // row-major, same idiom; the legacy output only
// The curve atlas: unit 6, GL_R32F, w=512 (the kNodeCurveN columns), h=24
// (8 nodes x 3 channels; rows past uNodesCount*3 are zero). texelFetch only.
uniform highp sampler2D uNodeCurves;

// Per-node uniforms (NodeParcel.h's compiled-pack layout, one array slot
// per node). All values are the rawedit::compileNode outputs - the CPU's
// own init-time math, never recomputed per pixel.
uniform vec4 uNodePrimary[8];       // lift.xyz (w unused)
uniform vec4 uNodeGain[8];         // gain.xyz
uniform vec4 uNodeOffset[8];       // offset.xyz
uniform vec4 uNodeGammaExp[8];     // gammaExp.xyz
uniform vec4 uNodeYSide[8];        // ySide.xyz (a, b, e)
uniform vec4 uNodeContrastPivot[8]; // contrast, pivot, sat, mix
uniform vec4 uNodeWb[8];           // wb.rgb (w unused)
uniform mat3 uNodeHueMat[8];       // the hue rotation, row-major, transpose=GL_TRUE
uniform vec4 uNodeFlags[8];        // x: hasCurves, y: hasLut, z: lutIndex, w: lutMix

// Node LUT sampler set (the stage-24 LUT port's own contract, per slot).
uniform highp sampler3D uNodeLut[4];      // units 7..10 (RGBA16F, trilinear)

// GLSL ES 3.00 allows a sampler array to be indexed only by a constant expression (a dynamic index needs
// GL_EXT_gpu_shader5, and Adreno refuses it: "'sampler3D' : requires extension GL_EXT_gpu_shader5"), so the
// slot select is spelled out: four constant-indexed samples, one taken.
vec3 nodeLutSample(int s, vec3 t) {
    if (s == 0) return texture(uNodeLut[0], t).rgb;
    if (s == 1) return texture(uNodeLut[1], t).rgb;
    if (s == 2) return texture(uNodeLut[2], t).rgb;
    return texture(uNodeLut[3], t).rgb;
}
uniform vec4 uNodeLutShape[4];           // x: size N, yzw: domainMin (the dummy pins 0)
uniform vec4 uNodeLutShapeHi[4];         // xyz: domainMax (w unused)
uniform vec4 uNodeLutInterp[4];          // x: inTransfer, y: outTransfer, z: passthrough, w: reserved
uniform mat3 uNodeLutWorkingToInput[4]; // row-major, transpose=GL_TRUE
uniform mat3 uNodeLutOutputToWorking[4];

out vec4 oCol;

// The composite curve sample - rawedit::nodeCurveSample VERBATIM (core/src/
// ColorModel.cpp): the bracketing texel pair's DELTAS (value minus x_i)
// lerped by the fractional position. x beyond the range clamps to the end
// texels' deltas exactly like the CPU's index clamp.
const float kCurveLo = -0.5;
const float kCurveHi = 1.5;
const float kCurveN = 512.0;
float nodeCurveAtlas(int row, float x) {
    float p = (x - kCurveLo) / (kCurveHi - kCurveLo) * (kCurveN - 1.0);
    int i = min(max(int(floor(p)), 0), 511 - 1);
    float x0 = kCurveLo + float(i) * (kCurveHi - kCurveLo) / (kCurveN - 1.0);
    float x1 = kCurveLo + float(i + 1) * (kCurveHi - kCurveLo) / (kCurveN - 1.0);
    float d0 = texelFetch(uNodeCurves, ivec2(i, row), 0).r - x0;
    float d1 = texelFetch(uNodeCurves, ivec2(i + 1, row), 0).r - x1;
    return x + d0 + (d1 - d0) * (p - float(i));
}

// The CPU rawedit::transfer helpers, the DI pair only (the two curves this
// pass needs; the managed LUT's table encodings reuse the tail helpers'
// lutEncodeTransfer when a node LUT crosses encodings - the binding
// contract carries the ordinals the same way).
// DI encode: lin <= 0.00262409 ? 10.44426855 * lin : 0.07329248 * (log2(lin + 0.0075) + 7.0)
float diEncode(float lin) {
    return lin <= 0.00262409 ? 10.44426855 * lin
         : 0.07329248 * (log2(lin + 0.0075) + 7.0);
}
// DI decode: enc <= 0.02740668 ? enc / 10.44426855 : pow(2.0, enc / 0.07329248 - 7.0) - 0.0075
float diDecode(float enc) {
    return enc <= 0.02740668 ? enc / 10.44426855
         : pow(2.0, enc / 0.07329248 - 7.0) - 0.0075;
}

// One node - rawedit::applyCompiledNode VERBATIM (core/src/ColorModel.cpp):
// primaries, the luminance side, contrast*wb, the hue matrix, saturation,
// the curves, the node LUT, the mix. di is in/out.
const vec3 kNodeLuma = vec3(0.2126, 0.7152, 0.0722);
void applyNode(inout vec3 di, int i) {
    vec3 c0 = di;
    float yIn = dot(kNodeLuma, di);
    // primaries: lift + gain * x + offset, then pow(x, gammaExp) when != 1
    vec3 v = uNodePrimary[i].xyz + uNodeGain[i].xyz * di + uNodeOffset[i].xyz;
    vec3 ge = uNodeGammaExp[i].xyz;
    di = vec3(ge.x == 1.0 ? v.x : pow(max(v.x, 0.0), ge.x),
              ge.y == 1.0 ? v.y : pow(max(v.y, 0.0), ge.y),
              ge.z == 1.0 ? v.z : pow(max(v.z, 0.0), ge.z));
    // the luminance side (always run - a port; neutral masters = identity)
    float yv = uNodeYSide[i].x + uNodeYSide[i].y * yIn + dot(kNodeLuma, uNodeOffset[i].xyz);
    if (uNodeYSide[i].z != 1.0) yv = pow(max(yv, 0.0), uNodeYSide[i].z);
    di += yv - dot(kNodeLuma, di);
    // contrast about the pivot, then the WB vector
    float pivot = uNodeContrastPivot[i].y;
    float contrast = uNodeContrastPivot[i].x;
    di = ((di - pivot) * contrast + pivot) * uNodeWb[i].xyz;
    // the hue rotation matrix, then saturation about the 709 luma
    vec3 h = uNodeHueMat[i] * di;
    float luma = dot(kNodeLuma, h);
    float sat = uNodeContrastPivot[i].z;
    di = luma + (h - luma) * sat;  // not clamped: below-black/above-white reach the curves
    // the composite curves (the atlas fetch, gated per node)
    if (uNodeFlags[i].x == 1.0) {
        di.r = nodeCurveAtlas(i * 3 + 0, di.r);
        di.g = nodeCurveAtlas(i * 3 + 1, di.g);
        di.b = nodeCurveAtlas(i * 3 + 2, di.b);
    }
    // the node LUT: the CPU's own order (DI -> DWG -> 709, the managed
    // binding's sample, back), mixed in DI by lutMix
    if (uNodeFlags[i].y == 1.0) {
        int s = int(uNodeFlags[i].z);
        vec3 lin = vec3(diDecode(di.r), diDecode(di.g), diDecode(di.b));
        vec3 l709 = uMDwgTo709 * lin;
        // the managed sample - the tail's lutManagedPixel shape, per slot
        float n = uNodeLutShape[s].x;
        vec3 span = uNodeLutShapeHi[s].xyz - uNodeLutShape[s].yzw;
        vec3 scale = (n - 1.0) / max(span, vec3(1e-20));
        vec3 fx = clamp((l709 - uNodeLutShape[s].yzw) * scale, vec3(0.0), vec3(n - 1.0));
        vec3 t = (fx + 0.5) / n;
        vec3 sampled = nodeLutSample(s, t);
        vec3 out709 = uNodeLutOutputToWorking[s] * sampled;
        vec3 outDwg = uM709ToDwg * out709;
        vec3 outDi = vec3(diEncode(outDwg.r), diEncode(outDwg.g), diEncode(outDwg.b));
        di = di + (outDi - di) * uNodeFlags[i].w;
    }
    // the mix
    float mixv = uNodeContrastPivot[i].w;
    di = c0 + (di - c0) * mixv;
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 lin = texelFetch(uSrc, g, 0).rgb;
    // the chain head: 709 -> DWG -> DI (rawedit::linear709ToDi)
    vec3 dwg = uM709ToDwg * lin;
    vec3 di = vec3(diEncode(dwg.r), diEncode(dwg.g), diEncode(dwg.b));
    for (int i = 0; i < uNodesCount; ++i) {
        applyNode(di, i);
    }
    // the chain tail: DI -> DWG, then the output mode's matrix
    dwg = vec3(diDecode(di.r), diDecode(di.g), diDecode(di.b));
    if (uNodesOutputMode == 1) {
        oCol = vec4(dwg, 1.0);  // DwgLinear: the DRT TAIL reads this
    } else {
        oCol = vec4(uMDwgTo709 * dwg, 1.0);
    }
}
"""

    /**
     * The TAIL pass's OpenDRT VARIANT, BAKED ROUTE (owner decision 2026-11-25; replaces the per-pixel GLSL
     * port, whose numerics never matched core). Reads the NODES pass's DWG-LINEAR output, DaVinci-Intermediate
     * encodes it, and samples the 65^3 table core bakes from its own OpenDrt (rawedit::bakedOpenDrtLut) with
     * the same by-hand trilinear every LUT stage uses; the CPU reference applies the SAME table
     * (applyOpenDrtBaked), so the two agree to the usual 1 LSB. Output is display code values (no OETF).
     */
    const val DEVELOP_FRAGMENT_TAIL_DRT: String = """#version 300 es

// The OpenDRT TAIL variant, baked route: DWG linear -> DI code -> 3D table -> display code.

precision highp float;
precision highp int;

uniform highp sampler2D uSrc;      // the NODES pass's DWG-LINEAR RGBA32F output
uniform ivec2 uSize;               // width/height in pixels
uniform highp sampler3D uDrtLut;   // RGBA32F, texel (x,y,z) = table entry (r,g,b), red fastest
uniform float uDrtLutN;            // table edge length

out vec4 oCol;

// DI encode (Color.cpp's pinned constants)
float diEncode(float lin) {
    return lin <= 0.00262409 ? 10.44426855 * lin
         : 0.07329248 * (log2(lin + 0.0075) + 7.0);
}

vec3 drtLutSample(vec3 enc) {
    float n = uDrtLutN;
    vec3 fx = clamp(enc * (n - 1.0), vec3(0.0), vec3(n - 1.0));
    vec3 f0 = floor(fx);
    vec3 w = fx - f0;
    ivec3 i0 = ivec3(f0);
    ivec3 hi = ivec3(int(n) - 1);
    ivec3 i1 = min(i0 + ivec3(1), hi);
    vec3 c000 = texelFetch(uDrtLut, ivec3(i0.x, i0.y, i0.z), 0).rgb;
    vec3 c100 = texelFetch(uDrtLut, ivec3(i1.x, i0.y, i0.z), 0).rgb;
    vec3 c010 = texelFetch(uDrtLut, ivec3(i0.x, i1.y, i0.z), 0).rgb;
    vec3 c110 = texelFetch(uDrtLut, ivec3(i1.x, i1.y, i0.z), 0).rgb;
    vec3 c001 = texelFetch(uDrtLut, ivec3(i0.x, i0.y, i1.z), 0).rgb;
    vec3 c101 = texelFetch(uDrtLut, ivec3(i1.x, i0.y, i1.z), 0).rgb;
    vec3 c011 = texelFetch(uDrtLut, ivec3(i0.x, i1.y, i1.z), 0).rgb;
    vec3 c111 = texelFetch(uDrtLut, ivec3(i1.x, i1.y, i1.z), 0).rgb;
    vec3 c00 = mix(c000, c100, w.x);
    vec3 c10 = mix(c010, c110, w.x);
    vec3 c01 = mix(c001, c101, w.x);
    vec3 c11 = mix(c011, c111, w.x);
    return mix(mix(c00, c10, w.y), mix(c01, c11, w.y), w.z);
}

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    if (g.x >= uSize.x || g.y >= uSize.y) discard;
    vec3 dwg = texelFetch(uSrc, g, 0).rgb;
    vec3 enc = vec3(diEncode(dwg.r), diEncode(dwg.g), diEncode(dwg.b));
    vec3 rgb = drtLutSample(enc);
    if (isnan(rgb.r)) rgb.r = 0.0;
    if (isnan(rgb.g)) rgb.g = 0.0;
    if (isnan(rgb.b)) rgb.b = 0.0;
    oCol = vec4(rgb, 1.0);
}
"""
}
