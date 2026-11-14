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
     *      verbatim: lift = -0.25 * bp/100, x' = lift + (1 - lift) * x
     *      (toe remap pinned at white);
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
     *      (core/src/Develop.cpp): knee 0.75, span = 1 - knee, s =
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
     * Stages NOT covered by THIS fused text (honest): 21-22
     * sharpness/detail, 24 LUT, and the gyro warp — the BackendRules
     * stage-coverage rules fall back to CPU with a reason line whenever
     * one of those is active in the grade. Stages 7-8 (spatial NR) are
     * fragment-covered since the NR port, but NOT by this fused pass:
     * they are neighborhood stages, so the session chain runs them as
     * the separate NR_* passes between DEVELOP_FRAGMENT_PRE and
     * DEVELOP_FRAGMENT_POST (below); the fused text keeps its no-NR
     * shape because the frozen device parity harness compiles it as ONE
     * draw.
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
    // The session chain draws the develop stages as PRE -> [NR] -> POST
    // through RGBA32F intermediates (DEVELOP_FRAGMENT_PRE, the NR_* passes
    // and DEVELOP_FRAGMENT_POST below); the fused parity pass recomposes
    // THE SAME fragments, so the split chain and the frozen harness cannot
    // drift. Each fragment is the fused pass's own text, cut at a copy
    // boundary (the CPU's stage 6/9 boundary - the NR stages' position in
    // rawedit::developCpu).

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

    /** Stages 9-23+OETF's uniforms (the post-pass set) - the fused pass's own text. */
    private const val kDevelopPostUniforms: String = """uniform float uExposure;        // 2^ev (stage 14); 1.0 = neutral
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
uniform int uTonemap;           // grade.tonemap (stage 23): 0 off, 1 ACES, 2-5 AgX Punchy/Golden/Greyscale/Base
uniform int uOetf;              // 1 = apply linearToRec709 (export shape); 0 = linear endpoint (parity)
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

    /** The POST stages' helpers (linearToRec709 .. tonemapPixel) - the fused pass's own text. */
    private const val kDevelopPostHelpers: String = """// The CPU OETF, rawedit::linearToRec709 (core/src/Transfer.cpp): 4.5*lin
// below 0.018, else 1.099*pow(lin, 0.45) - 0.099, UNCLAMPED (the CPU tail
// clamps after, in toByte; this pass leaves it to the caller).
float linearToRec709(float lin) {
    if (lin < 0.018) return 4.5 * lin;
    return 1.099 * pow(lin, 0.45) - 0.099;
}

// The CPU rawedit::smoothstepf (core/src/Develop.cpp), verbatim: Hermite
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
        float center = 120.0 * ch;  // r, g, b peaks
        float d = abs(hueDeg - center);
        if (d > 180.0) d = 360.0 - d;  // circular distance
        gains[ch] = 2.0 * (0.5 + 0.5 * cos(d * 3.14159265 / 180.0));
    }
    return gains;
}

// Stage 23: tonemap - the CPU rawedit::tonemapFrameImpl math verbatim
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
     * main()'s POST body: stage 9 through the tonemap/OETF tail and the
     * oCol write + main close (the fused tail's own final lines). PRE
     * appends its own [kDevelopWriteOut] instead; POST ends here.
     */
    private const val kDevelopPostBody: String = """    // Stage 9: black point - the CPU stage-9 endpoint remap, verbatim
    // (core/src/Develop.cpp). uBlackPoint carries the RAW grade.blackPoint
    // value; 0.0 skips the whole stage exactly like the CPU's `if
    // (grade.blackPoint != 0.0f)`. lift is uniform-derived so it must NOT
    // be const (GLSL ES 3.00).
    if (uBlackPoint != 0.0) {
        float lift = -0.25 * (uBlackPoint / 100.0);
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
        const float knee = 0.75;
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
    if (uTonemap != 0) {
        c = tonemapPixel(c);
    }
    if (uOetf == 1) {
        c = vec3(linearToRec709(c.r), linearToRec709(c.g), linearToRec709(c.b));
    }
    oCol = vec4(c, 1.0);
}
"""

    /** The fused pass's own banner + precision block (its own text). */
    private const val kDevelopFusedBanner: String = """// Fused develop pass - stages 1+2+3+4+5+6+9+10+11+12+13+14+15+16+17+18+19+20+23+OETF
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
    private const val kDevelopPostBanner: String = """// Develop post pass - stages 9+10+11+12+13+14+15+16+17+18+19+20+23+OETF
// of rawedit::developCpu (core/src/Develop.cpp). The session chain's
// last develop draw: reads the chain's RGBA32F intermediate (the pre
// pass's output, or the NR passes' output when the grade uses spatial
// NR - the CPU chain's stage 7/8 position), applies every per-pixel
// stage, writes the developed frame. The stage text is byte-identical
// to the fused pass's own POST fragments above, so the split chain and
// the parity harness cannot drift.

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
            kDevelopPreUniforms + kDevelopPostUniforms + "\n" +
            kDevelopOutDecl + "\n" + kDevelopPreHelpers + "\n" +
            kDevelopPostHelpers + "\n" + kDevelopMainHead +
            kDevelopPreBody + kDevelopPostBody

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
     * The develop chain's POST pass (the spatial-NR port's split) —
     * stages 9-20 + 23 + OETF of the CPU develop chain as a STANDALONE
     * draw: reads the chain's RGBA32F intermediate 1:1 (uSrc — the PRE
     * pass's output when no NR stage ran, else the last NR pass's
     * output; the CPU chain's stage 7/8 output position), applies every
     * per-pixel stage verbatim, writes the developed frame to the
     * session's develop texture (the store/blit/window/stats passes all
     * read THAT texture — POST is always the develop chain's last draw).
     *
     * The stage text is the fused pass's own POST fragments, so the split
     * chain and the parity harness cannot drift; the validator locks the
     * POST contract (Kind.DEVELOP_FRAGMENT_POST), including that NO
     * pre-stage uniform (uCfa, uCam2rgb, the demosaic) leaks into this
     * pass, and the stage application order.
     */
    const val DEVELOP_FRAGMENT_POST: String =
        "#version 300 es\n\n" + kDevelopPostBanner + "\n" +
            "uniform highp sampler2D uSrc;  // the chain's RGBA32F intermediate: the pre pass's or the NR passes' output\n" +
            "uniform ivec2 uSize;            // width/height in pixels (the intermediate; same dims as the CFA)\n" +
            kDevelopPostUniforms + "\n" + kDevelopOutDecl + "\n" +
            kDevelopPostHelpers + "\n" + kDevelopMainHead +
            kDevelopPostFetch + kDevelopPostBody

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
     * for BOTH the window and stats draws, sampled NEAREST, or LINEAR
     * when the device advertises OES_texture_float_linear (the session
     * gates the extension at open and sets the develop texture's MIN/MAG
     * filter at allocation; without the extension, 32F is only
     * NEAREST-filterable). There is deliberately NO intermediate present
     * texture: a second chained window pass would double-flip — one fold
     * maps a top-row-first source to a bottom-up destination, and
     * composing two folds yields an upside-down image (the fold family
     * has no identity arm). Output: the window surface (RGBA8888) or the
     * stats FBO (RGBA8). No readback of any kind — this pass only lands
     * pixels.
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

out vec4 oCol;

void main() {
    // Destination pixel -> rect-local [0,1]. Pixels outside the rect are
    // the letterbox bars: black, not a smeared edge sample.
    vec2 uv = (gl_FragCoord.xy - uRect.xy) / uRect.zw;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        oCol = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    // Orientation fold into source-normalized st (developToArgb's
    // per-case rotation fold, verified against its source index). The
    // source is top-row-first; the destination counts rows bottom-up, so
    // the default arm carries the Y flip.
    vec2 st;
    if (uOrientation == 90) {
        st = vec2(1.0 - uv.y, 1.0 - uv.x);
    } else if (uOrientation == 180) {
        st = vec2(1.0 - uv.x, uv.y);
    } else if (uOrientation == 270) {
        st = vec2(uv.y, uv.x);
    } else {
        st = vec2(uv.x, 1.0 - uv.y);
    }
    // Half-texel clamp: normalized sampling never crosses the source's
    // edge texel centers (bilinear would blend against the clamp border
    // otherwise). This line is why uSrcSize exists.
    vec2 texelHalf = vec2(0.5) / vec2(uSrcSize);
    st = clamp(st, texelHalf, vec2(1.0) - texelHalf);
    oCol = vec4(texture(uSrc, st).rgb, 1.0);
}
"""
}
