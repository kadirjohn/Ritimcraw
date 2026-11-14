// Production GPU develop session (M3 commit a). See GlesDevelopSession.h
// for the lifecycle/threading/color contracts. This translation unit is
// Android-only (EGL/GLES headers exist only in the NDK; the desktop build
// never compiles it — host-side coverage lives in the Kotlin shader-source
// validator + the parity decision rules and their JVM tests).
//
// The draw/readback body mirrors the DEVICE-PROVEN harness
// (GlesPipeline.cpp developFragmentParity — fragment parity PASS at 1 LSB on
// the owner's phone): same CPU-computed uniform rules, same texture units
// (CFA GL_TEXTURE0, the chain's pass sources GL_TEXTURE1, scratch
// GL_TEXTURE2, shading GL_TEXTURE3, the NR vertical pass's ORIGINAL guide
// GL_TEXTURE4), same SSBO binding 0, same transpose=GL_TRUE row-major
// matrix upload, same store-pass feedback-loop avoidance (the store pass
// draws to the scratch FBO, never to the develop FBO whose texture is the
// source), same mapped-SSBO readback (glReadPixels is used NOWHERE, the
// recorded baseline invariant). The differences are the point of this
// file: a persistent context, programs and uniform locations resolved once
// at open, immutable glTexStorage2D storage allocated once per resolution/
// resource change, glTexSubImage2D per-frame uploads, and a dedicated GL
// thread with blocking (bounded one-job) handoff. The spatial-NR port
// splits the fused develop draw at the CPU's stage 6/9 boundary: PRE
// (stages 1-6) -> the three NR passes at the CPU's exact stage 7/8
// position (gated per grade, skipped C++-side for a clamped strength of 0
// or a frame under the CPU's own w/h guards) -> POST (stages 9-20+23+OETF)
// into the develop texture — the same shared fragments the fused text
// composes, so the chains cannot drift.
//
// The production uniform VALUES differ from the harness's pinned ones by
// design — the harness pinned uHasProfile=0 / uExposure=1.0 / uOetf=0 to
// isolate the parity cases; production opens the gates the export tail
// needs: uHasProfile/uCam2rgb from the real buildCameraToRec709 matrix,
// uExposure = 2^ev (grade.exposureEv), uHighlights = grade.highlights raw
// (the shader computes s = -highlights/100 — the CPU stage-15 shoulder),
// uOetf = 1 (the export shape — the shader's linearToRec709 is textually
// identical to core/src/Transfer.cpp's, so the readback's toByte quantize
// reproduces nativeExportFrame's pack loop: toByte(linearToRec709(rgb))
// with no second OETF application), and the ten pointwise grade stages'
// raw values (uBlackPoint, uWhitePoint, uShadows, uMidtones, uContrast,
// uTempK, uTint, the six color-wheel offsets, uSplitShadowHue,
// uSplitHighlightHue, uSplitBalance, uSaturation — each 0.0f is the
// CPU's own neutral skip) with the two CPU-computed int gates the shader
// branches on: uWbManual = !grade.asShot (stages 16/17 run the manual
// temp/tint path only when the AS-shot gains are off) and uSplitActive =
// splitShadowHue >= 0 || splitHighlightHue >= 0 (stage 19's own guard,
// hoisted exactly like the shading gate).

#include "GlesDevelopSession.h"

#include <algorithm>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <utility>

// ANativeWindow_release: the surface job releases the transferred window
// reference on the GL thread (the header's ownership contract).
#include <android/native_window.h>

#include <GLES3/gl31.h>

// Stage 24 LUT (the LUT port, commit (a)): the session parses the .cube
// with core's frozen parser and compiles the managed binding with core's
// frozen compiler — the session NEVER re-implements either; the CPU seam
// (jni_bridge.cpp) owns the same pair through the same headers, so the
// two paths cannot drift in interpretation.
#include <rawedit/Lut.h>
#include <rawedit/Color.h>

#include "GlesPipeline.h"

namespace gles {
namespace {

// ---- Documented copies (each verbatim from its source, which stays the
// ---- owner of the text; see the per-copy notes). -------------------------

// parseGlesVersion — verbatim from GlesPipeline.cpp (the harness's own
// copy; "OpenGL ES 3.2 ..." -> (3, 2), malformed -> (0, 0): never guess a
// version). Copied because the original lives in that TU's anonymous
// namespace; when GlesPipeline.cpp changes the parse, this copy must
// change with it (the harness and the session must agree on the gate).
std::pair<int, int> parseGlesVersion(const std::string& version) {
    if (version.rfind("OpenGL ES ", 0) != 0) return std::make_pair(0, 0);
    const size_t base = 10;  // strlen("OpenGL ES ")
    int major = 0;
    int minor = 0;
    size_t i = base;
    while (i < version.size() && version[i] >= '0' && version[i] <= '9') {
        major = major * 10 + (version[i] - '0');
        ++i;
    }
    if (i == base || i >= version.size() || version[i] != '.') {
        return std::make_pair(major, 0);
    }
    ++i;
    while (i < version.size() && version[i] >= '0' && version[i] <= '9') {
        minor = minor * 10 + (version[i] - '0');
        ++i;
    }
    return std::make_pair(major, minor);
}

// cellFor — verbatim from GlesPipeline.cpp (itself the flattened
// core/src/Develop.cpp layoutFor): (cell[0][0], cell[0][1], cell[1][0],
// cell[1][1]), the four site codes the shader's uCell expects. Same
// keep-in-sync rule as parseGlesVersion.
void cellFor(const rawedit::FrameInfo& info, int cell[4]) {
    switch (info.cfa) {
        case rawedit::CfaPattern::GRBG:
            cell[0] = 1; cell[1] = 0; cell[2] = 2; cell[3] = 1;
            return;
        case rawedit::CfaPattern::GBRG:
            cell[0] = 1; cell[1] = 2; cell[2] = 0; cell[3] = 1;
            return;
        case rawedit::CfaPattern::BGGR:
            cell[0] = 2; cell[1] = 1; cell[2] = 1; cell[3] = 0;
            return;
        case rawedit::CfaPattern::RGGB:
        default:
            cell[0] = 0; cell[1] = 1; cell[2] = 1; cell[3] = 2;
            return;
    }
}

// ---- 3x3 row-major matrix helpers + buildCameraToRec709 — verbatim from
// ---- core/src/Develop.cpp (the anonymous-namespace originals; core/ is
// ---- READ-ONLY so this is a documented copy, the same pattern
// ---- GlesPipeline.cpp's case-1 reference uses). developCpu's stages 5+6
// ---- run UNCONDITIONALLY on this matrix for every grade (the asShot
// ---- toggle gates only stages 16/17, which the shader now covers behind
// ---- the CPU-computed uWbManual gate), so the session still needs no
// ---- asShot special case here: the Kotlin-side coverage gate already
// ---- guaranteed the grade's active stages are all fragment-covered.
struct Mat3 {
    float m[9];
};

Mat3 mul3(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            float s = 0.0f;
            for (int k = 0; k < 3; ++k)
                s += a.m[i * 3 + k] * b.m[k * 3 + j];
            r.m[i * 3 + j] = s;
        }
    return r;
}

Mat3 inv3(const Mat3& a) {
    const float* m = a.m;
    const float det =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::abs(det) < 1e-12f)
        return Mat3{{1,0,0, 0,1,0, 0,0,1}};
    const float inv = 1.0f / det;
    Mat3 r{};
    r.m[0] =  (m[4] * m[8] - m[5] * m[7]) * inv;
    r.m[1] = -(m[1] * m[8] - m[2] * m[7]) * inv;
    r.m[2] =  (m[1] * m[5] - m[2] * m[4]) * inv;
    r.m[3] = -(m[3] * m[8] - m[5] * m[6]) * inv;
    r.m[4] =  (m[0] * m[8] - m[2] * m[6]) * inv;
    r.m[5] = -(m[0] * m[5] - m[2] * m[3]) * inv;
    r.m[6] =  (m[3] * m[7] - m[4] * m[6]) * inv;
    r.m[7] = -(m[0] * m[7] - m[1] * m[6]) * inv;
    r.m[8] =  (m[0] * m[4] - m[1] * m[3]) * inv;
    return r;
}

Mat3 xyzD50ToRec709() {
    static const float bradford[9] = {
        0.8951f,  0.2664f, -0.1614f,
       -0.7502f,  1.7135f,  0.0367f,
        0.0389f, -0.0685f,  1.0296f,
    };
    const float wpD50[3] = {0.9642f, 1.0f, 0.8249f};
    const float wpD65[3] = {0.9505f, 1.0f, 1.0890f};

    float srcCone[3], dstCone[3];
    for (int i = 0; i < 3; ++i) {
        srcCone[i] = bradford[i*3+0]*wpD50[0] + bradford[i*3+1]*wpD50[1] + bradford[i*3+2]*wpD50[2];
        dstCone[i] = bradford[i*3+0]*wpD65[0] + bradford[i*3+1]*wpD65[1] + bradford[i*3+2]*wpD65[2];
    }
    // D = diag(dstCone/srcCone) folded into Bradford: M = B^-1 * D * B
    Mat3 B{}; for (int i = 0; i < 9; ++i) B.m[i] = bradford[i];
    Mat3 D{}; D.m[0]=dstCone[0]/srcCone[0]; D.m[4]=dstCone[1]/srcCone[1]; D.m[8]=dstCone[2]/srcCone[2];
    const Mat3 adaptM = mul3(inv3(B), mul3(D, B));

    static const float xyz2rgb[9] = {
        3.2406f, -1.5372f, -0.4986f,
       -0.9689f,  1.8758f,  0.0415f,
        0.0557f, -0.2040f,  1.0570f,
    };
    Mat3 RGB{}; for (int i = 0; i < 9; ++i) RGB.m[i] = xyz2rgb[i];
    return mul3(RGB, adaptM);
}

bool isIdentity3(const float* m) {
    return m[0] == 1 && m[4] == 1 && m[8] == 1
        && m[1] == 0 && m[2] == 0 && m[3] == 0
        && m[5] == 0 && m[6] == 0 && m[7] == 0;
}

// Camera-normalized RGB -> linear Rec.709 (DNG 1.6 §6; identity profile ->
// passthrough + false). Verbatim copy; see the note above.
bool buildCameraToRec709(const rawedit::RawMeta& meta, float out[9]) {
    const bool hasForward = !isIdentity3(meta.forwardMatrix);
    const bool hasColor = !isIdentity3(meta.colorMatrix);
    if (!hasForward && !hasColor) {
        for (int i = 0; i < 9; ++i)
            out[i] = (i == 0 || i == 4 || i == 8) ? 1.0f : 0.0f;
        return false; // passthrough: identity profile
    }
    Mat3 cam2xyz{};
    Mat3 FM{}, CAL{}, CM{};
    for (int i = 0; i < 9; ++i) {
        FM.m[i] = meta.forwardMatrix[i];
        CAL.m[i] = meta.calibration[i];
        CM.m[i] = meta.colorMatrix[i];
    }
    if (hasForward) {
        cam2xyz = mul3(FM, CAL);
    } else {
        cam2xyz = inv3(mul3(CAL, CM));
    }
    const Mat3 total = mul3(xyzD50ToRec709(), cam2xyz);
    for (int i = 0; i < 9; ++i)
        out[i] = total.m[i];
    return true;
}

// ---- GL helpers — verbatim from GlesPipeline.cpp's anonymous namespace
// ---- (same keep-in-sync rule). -------------------------------------------

struct GlShader {
    GLuint id = 0;
    ~GlShader() { if (id != 0) glDeleteShader(id); }
};

struct GlProgram {
    GLuint id = 0;
    ~GlProgram() { if (id != 0) glDeleteProgram(id); }
};

// First GL error after [op], or GL_NO_ERROR. Used only to name a failed
// step honestly in the report.
GLenum glErrorAfter(const char* op, std::string* detail) {
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR && detail != nullptr && detail->empty()) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%s: gl error 0x%04x", op, (unsigned)err);
        *detail = buf;
    }
    return err;
}

// Compile one shader of [type] from [source] (the caller-provided Kotlin
// text). Returns the GL name on success and writes a compile-failure
// detail into [detail]; returns 0 on failure (no GL object leaks: the
// caller wraps the name in GlShader).
GLuint compileShader(GLenum type, const std::string& source, const char* stage,
                     std::string* detail) {
    GLuint id = glCreateShader(type);
    if (id == 0) {
        if (detail != nullptr && detail->empty()) *detail = std::string("glCreateShader ") + stage;
        return 0;
    }
    const char* src = source.c_str();
    glShaderSource(id, 1, &src, nullptr);
    glCompileShader(id);
    GLint compiled = GL_FALSE;
    glGetShaderiv(id, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetShaderInfoLog(id, sizeof(log) - 1, &len, log);
        if (detail != nullptr && detail->empty()) {
            *detail = std::string(stage) + "-compile: " + log;
        }
        glDeleteShader(id);
        return 0;
    }
    return id;
}

// Link [vert]+[frag] into a program, writing a link-failure detail into
// [detail] on failure. Returns 0 on failure (the caller wraps the name).
GLuint linkProgram(GLuint vert, GLuint frag, const char* stage, std::string* detail) {
    GLuint id = glCreateProgram();
    if (id == 0) {
        if (detail != nullptr && detail->empty()) *detail = std::string("glCreateProgram ") + stage;
        return 0;
    }
    glAttachShader(id, vert);
    glAttachShader(id, frag);
    glLinkProgram(id);
    GLint linked = GL_FALSE;
    glGetProgramiv(id, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetProgramInfoLog(id, sizeof(log) - 1, &len, log);
        if (detail != nullptr && detail->empty()) {
            *detail = std::string(stage) + "-link: " + log;
        }
        glDeleteProgram(id);
        return 0;
    }
    return id;
}

// linearToRec709 output is unclamped; clamp then quantize to 0..255 — the
// toByte tail verbatim from jni_bridge.cpp (which owns the text). The
// shader already applied linearToRec709 (uOetf=1); this quantize is the
// ONLY CPU-side per-pixel work on the readback.
inline uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

// floatToHalfBits — round-to-nearest-even IEEE 754 binary16, written for
// the stage-24 LUT upload (the LUT port, commit (a)). The table crosses
// to the GPU as RGBA16F: half precision IS the quantization the parity
// claim bounds (the brief's half-quantization rule — the parity harness
// models exactly this rounding, and the bounded-error claim is measured
// against it, never assumed away). No float->half helper exists anywhere
// in app cpp or core (checked; the only half literal in the tree is the
// harness's raw 0x3c00 dummy), so this is a fresh, self-contained
// converter — the standard bit form. It runs ONCE per LUT revision
// (never per frame), so clarity beats speed. Specials: a zero or
// subnormal 32-bit input flushes to a signed half zero (nearest
// representable); Inf/NaN cross to half Inf/NaN (a parsed .cube never
// carries them, but the upload stays total); exponent overflow saturates
// to half infinity — a .cube value beyond half range is already far
// outside any display, and saturation is what a hardware half upload
// would do with the same input.
inline uint16_t floatToHalfBits(float f) {
    uint32_t x = 0;
    __builtin_memcpy(&x, &f, sizeof(x));
    const uint32_t sign = (x >> 16) & 0x8000u;
    const uint32_t exp = (x >> 23) & 0xffu;
    const uint32_t man = x & 0x007fffffu;
    if (exp == 0) return static_cast<uint16_t>(sign);
    if (exp == 0xffu) {
        return static_cast<uint16_t>(sign | 0x7c00u | (man != 0u ? 0x0200u : 0u));
    }
    // Re-bias 127 -> 15; e is the half exponent field the value lands in.
    int32_t e = static_cast<int32_t>(exp) - 127 + 15;
    if (e >= 0x1f) return static_cast<uint16_t>(sign | 0x7c00u);  // overflow
    if (e <= 0) {
        // Half-subnormal range (or underflow). value = mFull * 2^(e-38)
        // with mFull the 24-bit (implicit + 23) mantissa; the half
        // subnormal step is 2^-24, so the quantum index is
        // k = round(mFull >> (14 - e)). e < -10 always rounds to zero.
        if (e < -10) return static_cast<uint16_t>(sign);
        const uint32_t mFull = man | 0x00800000u;
        const uint32_t s = static_cast<uint32_t>(14 - e);  // 14..24
        uint32_t k = mFull >> s;
        const uint32_t rem = mFull & ((1u << s) - 1u);
        const uint32_t halfBit = 1u << (s - 1u);
        if (rem > halfBit || (rem == halfBit && (k & 1u) != 0u)) ++k;
        // k == 0x400 (a round-up from just under 2^-14) carries into the
        // exponent field naturally: sign|0x400 IS the smallest NORMAL
        // half, which is exactly the correctly rounded result.
        return static_cast<uint16_t>(sign | k);
    }
    // Normal half: keep the mantissa's top 10 bits, round the 13 dropped
    // bits to nearest with ties to even; a mantissa carry bumps the
    // exponent (and an exponent carry past 0x1e saturates to infinity).
    uint32_t halfMan = man >> 13;
    const uint32_t rem = man & 0x1fffu;
    if (rem > 0x1000u || (rem == 0x1000u && (halfMan & 1u) != 0u)) {
        ++halfMan;
        if (halfMan >= 0x400u) {
            halfMan = 0u;
            ++e;
            if (e >= 0x1f) return static_cast<uint16_t>(sign | 0x7c00u);
        }
    }
    return static_cast<uint16_t>(sign | (static_cast<uint32_t>(e) << 10) | halfMan);
}

// bindLutDummyTex — the stage-24 LUT's no-table placeholder (the LUT port,
// commit (a)): a 2x2x2 RGBA16F 3D texture, every texel 1.0, hardware
// trilinear + CLAMP_TO_EDGE on all three axes, ONE mip level. The shader's
// strength gate (uLutStrength 0) means it is never sampled on the no-LUT
// path — the point of the dummy is keeping the sampler3D complete, the
// shading dummy's own rule (a statically-declared sampler whose unit is
// never set would default to unit 0 and collide with uCfa). The harness's
// GlesPipeline.cpp lutDummy is the same table (0x3c00 = half(1.0)).
inline void bindLutDummyTex(GLuint tex) {
    glActiveTexture(GL_TEXTURE5);
    glBindTexture(GL_TEXTURE_3D, tex);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    const uint16_t dummy[16] = {0x3c00u, 0x3c00u, 0x3c00u, 0x3c00u,
                                0x3c00u, 0x3c00u, 0x3c00u, 0x3c00u,
                                0x3c00u, 0x3c00u, 0x3c00u, 0x3c00u,
                                0x3c00u, 0x3c00u, 0x3c00u, 0x3c00u};
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, 2, 2, 2, 0,
                 GL_RGBA, GL_HALF_FLOAT, dummy);
}

// The stage-24 neutral pins (the LUT port, commit (a)): the values the POST
// draw uploads when no LUT is active, so the uniform set stays complete and
// the shader's own strength gate is the ONLY thing that skips stage 24 —
// the raw-grade-value idiom every other knob above rides. The domain pins
// are the .cube parser's own defaults (DOMAIN_MIN 0, DOMAIN_MAX 1 per
// axis); kLutIdentity is the row-major identity the binding matrices pin to
// (the shader's legacy role-0 branch never reads them, but the uniform
// upload stays honest).
constexpr float kLutDomainMinDefault[3] = {0.0f, 0.0f, 0.0f};
constexpr float kLutDomainMaxDefault[3] = {1.0f, 1.0f, 1.0f};
constexpr float kLutIdentity[9] = {1.0f, 0.0f, 0.0f,
                                   0.0f, 1.0f, 0.0f,
                                   0.0f, 0.0f, 1.0f};

} // namespace

// ---- Impl -----------------------------------------------------------------

struct DevelopSession::Impl {
    // --- GL-thread-owned state (touched ONLY on the GL thread; the mutex
    // --- guards only the handoff/latch fields below) ----------------------
    EglSession egl;
    // The split develop chain's programs (the spatial-NR port): pre runs
    // stages 1-6 into an RGBA32F intermediate, the three NR passes sit
    // at the CPU's exact stage 7/8 position between them (gated per
    // grade), post runs stages 9-20+23+OETF into the develop texture.
    // The fused DEVELOP_FRAGMENT stays the frozen parity harness's own
    // source (GlesPipeline.cpp); the split programs compile the same
    // shared fragments, so the two chains cannot drift.
    GLuint preProgram = 0;
    GLuint postProgram = 0;
    GLuint nrChromaHProgram = 0;
    GLuint nrChromaVProgram = 0;
    GLuint nrLumaProgram = 0;
    GLuint storeProgram = 0;
    // Uniform locations, resolved ONCE at open. The develop set is SPLIT
    // with the programs: the 13 PRE uniforms (uCfa..uHasProfile — the
    // pre-stage set) resolve on preProgram, the 34 POST uniforms
    // (uExposure..uOetf plus the stage-24 LUT set) on postProgram. The
    // NR passes carry their own small sets: uSrc/uSize/uChromaDenoise on
    // the horizontal pass, uSrc/uGuide/uSize/uChromaDenoise on the
    // vertical (uGuide = the ORIGINAL pre-NR image — the CPU's second
    // pass keeps the original luma guide), uSrc/uSize/uNoiseReduction on
    // the luma. Store and blit keep their own pairs.
    GLint cfaLoc = -1, sizeLoc = -1, cellLoc = -1, blackLoc = -1, rangeLoc = -1,
          hasShadingLoc = -1, shadingLoc = -1, shadingSizeLoc = -1, hrLoc = -1,
          hrGainLoc = -1, wbLoc = -1, camLoc = -1, hasProfileLoc = -1,
          exposureLoc = -1, highlightsLoc = -1, tonemapLoc = -1, oetfLoc = -1,
          blackPointLoc = -1, whitePointLoc = -1, shadowsLoc = -1,
          midtonesLoc = -1, contrastLoc = -1, wbManualLoc = -1, tempKLoc = -1,
          tintLoc = -1, wheelShadowXLoc = -1, wheelShadowYLoc = -1,
          wheelMidXLoc = -1, wheelMidYLoc = -1, wheelHighXLoc = -1,
          wheelHighYLoc = -1, splitActiveLoc = -1, splitShadowHueLoc = -1,
          splitHighlightHueLoc = -1, splitBalanceLoc = -1, saturationLoc = -1;
    // The stage-24 LUT set (the LUT port, commit (a)) — all on postProgram,
    // all resolved at open with the same >= 0 contract check as the rest.
    // uLutStrength/uLutSize/uLutDomainMin/uLutDomainMax/uLutRole/
    // uLutPassthrough/uLutInTransfer/uLutOutTransfer feed the shader's
    // own role/gamut/transfer contract (the CPU applyManagedLutStage
    // semantics transcribed); uLut is the sampler3D pinned to unit 5 at
    // open (the static binding block below); the two mat3s carry
    // core's compiled binding matrices, uploaded row-major with
    // transpose=GL_TRUE exactly like uCam.
    GLint lutStrengthLoc = -1, lutLoc = -1, lutSizeLoc = -1,
          lutDomainMinLoc = -1, lutDomainMaxLoc = -1, lutRoleLoc = -1,
          lutPassthroughLoc = -1, lutInTransferLoc = -1,
          lutOutTransferLoc = -1, lutWorkingToInputLoc = -1,
          lutOutputToWorkingLoc = -1;
    // POST's own uSrc/uSize (the chain-intermediate input the NR position
    // feeds) and the NR passes' uniform sets, each on its own program.
    GLint postSrcLoc = -1, postSizeLoc = -1;
    GLint nrChromaHSrcLoc = -1, nrChromaHSizeLoc = -1, nrChromaHStrengthLoc = -1;
    GLint nrChromaVSrcLoc = -1, nrChromaVGuideLoc = -1, nrChromaVSizeLoc = -1,
          nrChromaVStrengthLoc = -1;
    GLint nrLumaSrcLoc = -1, nrLumaSizeLoc = -1, nrLumaStrengthLoc = -1;
    GLint storeSrcLoc = -1, storeSizeLoc = -1;
    GLint blitSrcLoc = -1, blitSizeLoc = -1, blitRotLoc = -1;
    GLuint blitProgram = 0;
    GLuint cfaTex = 0, shadingTex = 0, developTex = 0, scratchTex = 0;
    // The stage-24 LUT 3D texture (RGBA16F, unit 5 — the LUT port, commit
    // (a)). Allocated ONCE per LUT revision, never per frame: [lutTex]
    // holds the CURRENT table (the real parsed cube, or the 2x2x2 dummy
    // when no LUT is bound — the sampler uniform is always set, keeping
    // the program's uniform set complete either way, the shading dummy's
    // own rule), and the cache fields below decide whether an upload is
    // needed. GL-thread-owned.
    GLuint lutTex = 0;
    // The LUT revision cache: [lutUploadedPath] is the path whose table is
    // CURRENTLY in [lutTex] ("" = the dummy is bound); [lutParsedPath]/
    // [lutParsed] hold the last parse (kept so a strength step across 0
    // does not re-read the file); [lutBinding] the last compiled binding
    // (keyed by the five ordinals in [lutBindingKey]); [lutBindingOk]
    // whether a binding was compiled at all (role < 0 = legacy needs no
    // binding). The session's own GL-thread cache — the JNI bridge's
    // thread_local caches belong to engine threads and cannot be shared
    // here.
    std::string lutUploadedPath;
    std::string lutParsedPath;
    std::unique_ptr<rawedit::Lut> lutParsed;
    bool lutBindingOk = false;
    rawedit::CompiledLutBinding lutBinding{};
    int lutBindingKey[5] = {-2, -2, -2, -2, -2};  // role,in,out gamut,in,out transfer
    // The chain's RGBA32F ping-pong intermediates (the spatial-NR port):
    // A = the PRE pass's output (and the chroma vertical pass's ORIGINAL
    // guide), B/C = the NR passes' scratch pair. POST always writes
    // developTex (the store/blit passes read THAT texture — writing
    // POST's output to an intermediate the chain also reads would be a
    // framebuffer feedback loop).
    GLuint texA = 0, texB = 0, texC = 0;
    GLuint developFbo = 0, scratchFbo = 0;
    GLuint fboA = 0, fboB = 0, fboC = 0;
    GLuint outBuf = 0;
    // The encoder window surface (M5). EGL_NO_SURFACE until a successful
    // setEncoderSurface attach; destroyed on the GL thread (in
    // runSurfaceJob, on detach, and in teardown). GL-thread-owned.
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    // eglPresentationTimeANDROID (EGL_ANDROID_presentation_time), resolved
    // ONCE at init via eglGetProcAddress. Null on a device without the
    // extension — the encoder render then refuses honestly with that
    // reason named (never a crash, never a wall-clock stamp).
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;
    // Immutable storage currently allocated (0 = none yet). Per-frame work
    // touches none of these fields — glTexSubImage2D uploads data into the
    // existing storage, and the SSBO keeps its size while w/h hold.
    uint32_t cfaW = 0, cfaH = 0;
    uint32_t shadingW = 0, shadingH = 0;
    uint64_t outBytes = 0;

    // --- Handoff state (guarded by [mutex]) --------------------------------
    // Job kinds (the M4 GlesPreviewSession shape, extended for M5):
    //   Render   — a readback render (runJob); failures LATCH (mid-render
    //              GL failure = context event).
    //   Surface  — an encoder-surface attach/detach (runSurfaceJob);
    //              failures NEVER latch (the context is fine — the window
    //              isn't). A detach (null window) always processes, even
    //              on a latched-unhealthy session, so teardown paths can
    //              release the surface.
    //   Encoder   — a render-to-encoder (runEncoderJob); failures LATCH
    //              (same mid-render semantics as Render).
    enum class JobKind { None, Render, Surface, Encoder };
    std::mutex mutex;
    std::condition_variable cvRequest;
    std::condition_variable cvDone;
    std::thread glThread;
    bool threadRunning = false;
    // The one in-flight job (bounded by construction: every submitter
    // blocks on cvDone until the GL thread clears [pendingKind], so at
    // most one request is ever in flight — regardless of kind).
    JobKind pendingKind = JobKind::None;
    const DevelopFrameRequest* pendingReq = nullptr;
    DevelopFrameResult* pendingRes = nullptr;
    int64_t pendingPtsUs = 0;
    int pendingRotationDeg = 0;
    // The window to attach (or null to detach) — borrowed for the job's
    // duration only; ownership transferred at setEncoderSurface entry.
    ANativeWindow* pendingWindow = nullptr;
    bool pendingSurfaceOk = false;
    std::string pendingSurfaceDetail;
    bool closeRequested = false;
    // Latched failure (the header's failure semantics): set on the GL
    // thread, read through the mutex (a failed session never recovers —
    // the dispatcher falls the export back to CPU with this reason).
    bool healthyFlag = true;
    std::string failureDetail;

    // --- GL-thread methods (called only from the GL thread) ----------------
    bool initOnGlThread(const std::string& vertexSource,
                        const std::string& vertex310Source,
                        const std::string& preFragmentSource,
                        const std::string& postFragmentSource,
                        const std::string& nrChromaHSource,
                        const std::string& nrChromaVSource,
                        const std::string& nrLumaSource,
                        const std::string& storeFragmentSource,
                        const std::string& blitFragmentSource,
                        std::string* detail);
    void runJob(const DevelopFrameRequest& req, DevelopFrameResult& out);
    // The shared develop pass (input contract, CPU uniforms, allocation,
    // upload, the pre -> [NR] -> post draw chain into developFbo) — the
    // part runJob and runEncoderJob share verbatim. False with
    // out.detail set on failure.
    bool developPass(const DevelopFrameRequest& req, DevelopFrameResult& out);
    void runSurfaceJob(ANativeWindow* window, bool* ok, std::string* detail);
    void runEncoderJob(const DevelopFrameRequest& req, int64_t ptsUs,
                       int rotationDeg, DevelopFrameResult& out);
    void teardownOnGlThread();
    // The thread's whole life: serve jobs until close (entered on the GL
    // thread itself, right after initOnGlThread reports).
    void glThreadLoop();
};

// ---- The GL thread's whole life ----------------------------------------------

void DevelopSession::Impl::glThreadLoop() {
    // Entered with the context live (or already failed at init) and the
    // lock NOT held. open() waits for init's outcome before returning;
    // close() joins us. Every EGL/GL call for the session's lifetime —
    // including teardown — happens on THIS thread (EGL contexts are not
    // portable across threads; the same rule GlesPipeline.h documents for
    // the harness).
    std::unique_lock<std::mutex> lock(mutex);
    while (true) {
        cvRequest.wait(lock, [&]() { return closeRequested || pendingKind != JobKind::None; });
        if (closeRequested && pendingKind == JobKind::None) break;

        // Run the job with the lock RELEASED (healthy()/close() must stay
        // live from other threads during the render, and the render can
        // take hundreds of milliseconds). The pending* fields still point
        // at the caller's buffers — safe because every submitter is
        // BLOCKED on cvDone until we clear [pendingKind] below, so nothing
        // touches them while the lock is open.
        const JobKind kind = pendingKind;
        const DevelopFrameRequest* req = pendingReq;
        DevelopFrameResult* out = pendingRes;
        const int64_t ptsUs = pendingPtsUs;
        const int rotationDeg = pendingRotationDeg;
        ANativeWindow* window = pendingWindow;
        bool* surfaceOk = &pendingSurfaceOk;
        std::string* surfaceDetail = &pendingSurfaceDetail;
        lock.unlock();
        if (kind == JobKind::Surface) {
            // An attach/detach job. NEVER latches (see the Impl comment):
            // the outcome lands in pendingSurfaceOk/pendingSurfaceDetail.
            runSurfaceJob(window, surfaceOk, surfaceDetail);
        } else if (kind == JobKind::Encoder) {
            runEncoderJob(*req, ptsUs, rotationDeg, *out);
        } else {
            runJob(*req, *out);
        }
        lock.lock();
        // Publish under the lock AND latch RENDER/ENCODER failures (the
        // header's semantics): a mid-render GL failure or an
        // input-contract failure makes the session unhealthy — later
        // renderFrame calls fail fast with the same detail instead of
        // retrying a broken context, and the Kotlin dispatcher falls the
        // export back to CPU with this reason. A SURFACE job's failure is
        // NOT latched (the context is fine — the window isn't).
        if ((kind == JobKind::Render || kind == JobKind::Encoder) && !out->ok) {
            healthyFlag = false;
            if (failureDetail.empty()) failureDetail = out->detail;
        }
        pendingKind = JobKind::None;
        pendingReq = nullptr;
        pendingRes = nullptr;
        pendingPtsUs = 0;
        pendingRotationDeg = 0;
        pendingWindow = nullptr;
        // notify_all: TWO different threads can be parked on cvDone (the
        // engine thread in renderFrame/renderFrameToEncoder, the exporter
        // thread in setEncoderSurface). A notify_one could wake the wrong
        // waiter, which would re-sleep on the predicate and leave the
        // actual waiter stuck until the next spurious wake.
        cvDone.notify_all();
        // Loop: drain any close requested while the job ran.
    }
    // Exit path: closeRequested && no pending job. Tear down the GL state
    // NOW, on this thread, in the lock-free window before close() joins
    // us. teardownOnGlThread releases the EGL context too, so the Impl's
    // egl member's own destructor later finds NO_* handles
    // (EglSession::release is idempotent — safe on a partially-opened
    // session, and safe when the destructor later runs on another thread
    // because the handles are already NO_* and the guard skips all).
    lock.unlock();
    teardownOnGlThread();
}

// ---- open -------------------------------------------------------------------

std::unique_ptr<DevelopSession> DevelopSession::open(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& preFragmentSource,
        const std::string& postFragmentSource,
        const std::string& nrChromaHSource,
        const std::string& nrChromaVSource,
        const std::string& nrLumaSource,
        const std::string& storeFragmentSource,
        const std::string& blitFragmentSource,
        std::string* detail) {
    std::unique_ptr<DevelopSession> session(new DevelopSession);
    session->impl_ = std::unique_ptr<Impl>(new Impl);
    Impl* impl = session->impl_.get();

    // The GL thread: opens the context + compiles on itself (EGL
    // confinement), reports the outcome through a dedicated init handshake,
    // then serves jobs until close. open() blocks until init reports.
    std::mutex initMutex;
    std::condition_variable initCv;
    bool initReported = false;
    bool initOk = false;
    std::string initDetail;

    // Capture list is explicit on purpose: [impl] and the nine sources BY
    // VALUE (a `[&]` would dangle — this thread outlives open()'s frame),
    // the init-handshake locals by reference (safe: each is touched
    // exactly once, before the notify, and open() does not return until
    // the notification arrives).
    impl->glThread = std::thread(
        [impl, vertexSource, vertex310Source, preFragmentSource,
         postFragmentSource, nrChromaHSource, nrChromaVSource,
         nrLumaSource, storeFragmentSource, blitFragmentSource, &initMutex,
         &initCv, &initReported, &initOk, &initDetail]() {
            std::string d;
            const bool ok = impl->initOnGlThread(
                vertexSource, vertex310Source, preFragmentSource,
                postFragmentSource, nrChromaHSource, nrChromaVSource,
                nrLumaSource, storeFragmentSource, blitFragmentSource, &d);
            // Report init's outcome to open() FIRST, then serve the loop
            // (or exit it on failure — open() joins the thread below).
            {
                std::lock_guard<std::mutex> lock(initMutex);
                initOk = ok;
                initDetail = d;
                initReported = true;
            }
            initCv.notify_one();
            if (!ok) {
                // Latch the failure (the header's semantics) so the loop
                // exits without ever serving a job (nothing can be
                // pending — renderFrame is unreachable until open returns
                // success) and healthy() reports false with the reason.
                std::lock_guard<std::mutex> lock(impl->mutex);
                impl->closeRequested = true;
                impl->healthyFlag = false;
                impl->failureDetail = d;
            }
            impl->glThreadLoop();
        });

    // Wait for init's outcome. The handshake locals are safe to capture by
    // reference precisely because of this wait: open() cannot leave this
    // frame until initReported flips, and the thread touches each local
    // exactly once before that flip.
    {
        std::unique_lock<std::mutex> lock(initMutex);
        initCv.wait(lock, [&]() { return initReported; });
    }
    if (!initOk) {
        if (detail != nullptr) *detail = initDetail;
        // The thread latched closeRequested itself; join it (its teardown
        // already ran inside glThreadLoop's exit path).
        if (impl->glThread.joinable()) impl->glThread.join();
        return nullptr;
        // ~unique_ptr<DevelopSession> NOT reached here — the session is
        // returned null; the local [session] destroys with the joined
        // thread member and the released EGL handles. Nothing leaks.
    }
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->threadRunning = true;
    }
    return session;
}

bool DevelopSession::Impl::initOnGlThread(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& preFragmentSource,
        const std::string& postFragmentSource,
        const std::string& nrChromaHSource,
        const std::string& nrChromaVSource,
        const std::string& nrLumaSource,
        const std::string& storeFragmentSource,
        const std::string& blitFragmentSource,
        std::string* detail) {
    // --- EGL + capability gates (THIS context, not the probe's) ------------
    std::string d;
    // windowCapable + recordable: the session's config must be able to
    // wrap a MediaCodec input Surface later (M5) — the SAME config is
    // handed to eglCreateWindowSurface in runSurfaceJob. The pbuffer stays
    // the session's own current surface; the recordable flag is what the
    // codec's BufferQueue consumer needs from the producer config
    // (EGL_ANDROID_recordable).
    if (!egl.open(&d, /*windowCapable=*/true, /*recordable=*/true)) {
        *detail = "egl: " + d;
        return false;
    }
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
    // STORE_FRAGMENT needs ES 3.1 (fragment-shader SSBO access); the
    // develop pass itself is 300 es but its readback is not.
    if (mm.first < 3 || (mm.first == 3 && mm.second < 1)) {
        *detail = "context-not-31: " + version;
        return false;
    }
    const GLubyte* e = glGetString(GL_EXTENSIONS);
    const std::string extensions = e != nullptr ? reinterpret_cast<const char*>(e) : "";
    if (extensions.find("GL_EXT_color_buffer_float") == std::string::npos) {
        *detail = "no EXT_color_buffer_float (RGBA32F not renderable)";
        return false;
    }

    // --- programs, compiled ONCE -------------------------------------------
    // The develop pair is the SPLIT of the fused DEVELOP_FRAGMENT at the
    // CPU's stage 6/9 boundary (the spatial-NR port's shape): pre runs
    // stages 1-6, post runs 9-20+23+OETF, and the three NR passes sit at
    // the CPU's exact stage 7/8 position between them. The fused text
    // stays the frozen parity harness's own source; these programs
    // compile the same shared fragments, so the chains cannot drift.
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader preFragShader;
    preFragShader.id = compileShader(GL_FRAGMENT_SHADER, preFragmentSource,
                                     "develop-pre-fragment", detail);
    if (preFragShader.id == 0) return false;
    preProgram = linkProgram(vertShader.id, preFragShader.id, "develop-pre", detail);
    if (preProgram == 0) return false;
    GlShader postFragShader;
    postFragShader.id = compileShader(GL_FRAGMENT_SHADER, postFragmentSource,
                                      "develop-post-fragment", detail);
    if (postFragShader.id == 0) return false;
    postProgram = linkProgram(vertShader.id, postFragShader.id, "develop-post", detail);
    if (postProgram == 0) return false;
    // The three spatial-NR passes (the CPU's denoiseChromaImpl as H+V —
    // the vertical pass reads the ORIGINAL pre-NR image as its luma
    // guide — and denoiseLumaImpl as the one-pass 3x3 bilateral).
    GlShader nrChromaHFragShader;
    nrChromaHFragShader.id = compileShader(GL_FRAGMENT_SHADER, nrChromaHSource,
                                           "nr-chroma-h-fragment", detail);
    if (nrChromaHFragShader.id == 0) return false;
    nrChromaHProgram = linkProgram(vertShader.id, nrChromaHFragShader.id,
                                   "nr-chroma-h", detail);
    if (nrChromaHProgram == 0) return false;
    GlShader nrChromaVFragShader;
    nrChromaVFragShader.id = compileShader(GL_FRAGMENT_SHADER, nrChromaVSource,
                                           "nr-chroma-v-fragment", detail);
    if (nrChromaVFragShader.id == 0) return false;
    nrChromaVProgram = linkProgram(vertShader.id, nrChromaVFragShader.id,
                                   "nr-chroma-v", detail);
    if (nrChromaVProgram == 0) return false;
    GlShader nrLumaFragShader;
    nrLumaFragShader.id = compileShader(GL_FRAGMENT_SHADER, nrLumaSource,
                                        "nr-luma-fragment", detail);
    if (nrLumaFragShader.id == 0) return false;
    nrLumaProgram = linkProgram(vertShader.id, nrLumaFragShader.id, "nr-luma", detail);
    if (nrLumaProgram == 0) return false;
    GlShader vert310Shader;
    vert310Shader.id = compileShader(GL_VERTEX_SHADER, vertex310Source, "vertex310", detail);
    if (vert310Shader.id == 0) return false;
    GlShader storeFragShader;
    storeFragShader.id = compileShader(GL_FRAGMENT_SHADER, storeFragmentSource, "store-fragment", detail);
    if (storeFragShader.id == 0) return false;
    storeProgram = linkProgram(vert310Shader.id, storeFragShader.id, "store", detail);
    if (storeProgram == 0) return false;
    // The blit program (M5): the SAME BLIT_FRAGMENT the Surface-mode writer
    // proved on device, linked against the plain 300es fullscreen vertex.
    // It copies the developed texture to the encoder window surface with
    // the Y flip the writer's path established (the develop pass's texel
    // row 0 is the image top; the window surface presents row 0 at the
    // bottom).
    GlShader blitFragShader;
    blitFragShader.id = compileShader(GL_FRAGMENT_SHADER, blitFragmentSource,
                                      "blit-fragment", detail);
    if (blitFragShader.id == 0) return false;
    blitProgram = linkProgram(vertShader.id, blitFragShader.id, "blit", detail);
    if (blitProgram == 0) return false;
    // Program ownership is already in the Impl (linkProgram's result is
    // assigned straight into the member); the GlShader guards deleting the
    // SHADER objects at scope exit is correct OpenGL — an attached shader
    // stays flagged-alive until its program dies.

    // --- uniform locations, resolved ONCE ----------------------------------
    // The develop set is SPLIT with the programs: the 13 PRE uniforms
    // (uCfa..uHasProfile) resolve on preProgram, the 34 POST uniforms
    // (uExposure..uOetf plus the stage-24 LUT set) on postProgram. The
    // LUT lookups join the same >= 0 contract check as the rest — a
    // postProgram compiled without its LUT uniforms is a shader-contract
    // break, not a runtime condition.
    cfaLoc = glGetUniformLocation(preProgram, "uCfa");
    sizeLoc = glGetUniformLocation(preProgram, "uSize");
    cellLoc = glGetUniformLocation(preProgram, "uCell");
    blackLoc = glGetUniformLocation(preProgram, "uBlack");
    rangeLoc = glGetUniformLocation(preProgram, "uRange");
    hasShadingLoc = glGetUniformLocation(preProgram, "uHasShading");
    shadingLoc = glGetUniformLocation(preProgram, "uShading");
    shadingSizeLoc = glGetUniformLocation(preProgram, "uShadingSize");
    hrLoc = glGetUniformLocation(preProgram, "uHr");
    hrGainLoc = glGetUniformLocation(preProgram, "uHrGain");
    wbLoc = glGetUniformLocation(preProgram, "uWb");
    camLoc = glGetUniformLocation(preProgram, "uCam2rgb");
    hasProfileLoc = glGetUniformLocation(preProgram, "uHasProfile");
    if (cfaLoc < 0 || sizeLoc < 0 || cellLoc < 0 || blackLoc < 0 ||
        rangeLoc < 0 || hasShadingLoc < 0 || shadingLoc < 0 ||
        shadingSizeLoc < 0 || hrLoc < 0 || hrGainLoc < 0 || wbLoc < 0 ||
        camLoc < 0 || hasProfileLoc < 0) {
        *detail = "shader-contract: missing a develop-pre uniform";
        return false;
    }
    // POST's own uSrc/uSize (the chain-intermediate input) + the pointwise
    // set.
    postSrcLoc = glGetUniformLocation(postProgram, "uSrc");
    postSizeLoc = glGetUniformLocation(postProgram, "uSize");
    exposureLoc = glGetUniformLocation(postProgram, "uExposure");
    highlightsLoc = glGetUniformLocation(postProgram, "uHighlights");
    blackPointLoc = glGetUniformLocation(postProgram, "uBlackPoint");
    whitePointLoc = glGetUniformLocation(postProgram, "uWhitePoint");
    shadowsLoc = glGetUniformLocation(postProgram, "uShadows");
    midtonesLoc = glGetUniformLocation(postProgram, "uMidtones");
    contrastLoc = glGetUniformLocation(postProgram, "uContrast");
    wbManualLoc = glGetUniformLocation(postProgram, "uWbManual");
    tempKLoc = glGetUniformLocation(postProgram, "uTempK");
    tintLoc = glGetUniformLocation(postProgram, "uTint");
    wheelShadowXLoc = glGetUniformLocation(postProgram, "uWheelShadowX");
    wheelShadowYLoc = glGetUniformLocation(postProgram, "uWheelShadowY");
    wheelMidXLoc = glGetUniformLocation(postProgram, "uWheelMidX");
    wheelMidYLoc = glGetUniformLocation(postProgram, "uWheelMidY");
    wheelHighXLoc = glGetUniformLocation(postProgram, "uWheelHighX");
    wheelHighYLoc = glGetUniformLocation(postProgram, "uWheelHighY");
    splitActiveLoc = glGetUniformLocation(postProgram, "uSplitActive");
    splitShadowHueLoc = glGetUniformLocation(postProgram, "uSplitShadowHue");
    splitHighlightHueLoc = glGetUniformLocation(postProgram, "uSplitHighlightHue");
    splitBalanceLoc = glGetUniformLocation(postProgram, "uSplitBalance");
    saturationLoc = glGetUniformLocation(postProgram, "uSaturation");
    tonemapLoc = glGetUniformLocation(postProgram, "uTonemap");
    oetfLoc = glGetUniformLocation(postProgram, "uOetf");
    // The stage-24 LUT set (the LUT port, commit (a)) — the same resolved-
    // once contract as the rest of the POST set.
    lutStrengthLoc = glGetUniformLocation(postProgram, "uLutStrength");
    lutLoc = glGetUniformLocation(postProgram, "uLut");
    lutSizeLoc = glGetUniformLocation(postProgram, "uLutSize");
    lutDomainMinLoc = glGetUniformLocation(postProgram, "uLutDomainMin");
    lutDomainMaxLoc = glGetUniformLocation(postProgram, "uLutDomainMax");
    lutRoleLoc = glGetUniformLocation(postProgram, "uLutRole");
    lutPassthroughLoc = glGetUniformLocation(postProgram, "uLutPassthrough");
    lutInTransferLoc = glGetUniformLocation(postProgram, "uLutInTransfer");
    lutOutTransferLoc = glGetUniformLocation(postProgram, "uLutOutTransfer");
    lutWorkingToInputLoc = glGetUniformLocation(postProgram, "uLutWorkingToInput");
    lutOutputToWorkingLoc = glGetUniformLocation(postProgram, "uLutOutputToWorking");
    if (postSrcLoc < 0 || postSizeLoc < 0 || exposureLoc < 0 ||
        highlightsLoc < 0 || tonemapLoc < 0 || oetfLoc < 0 ||
        blackPointLoc < 0 || whitePointLoc < 0 || shadowsLoc < 0 ||
        midtonesLoc < 0 || contrastLoc < 0 || wbManualLoc < 0 ||
        tempKLoc < 0 || tintLoc < 0 || wheelShadowXLoc < 0 ||
        wheelShadowYLoc < 0 || wheelMidXLoc < 0 || wheelMidYLoc < 0 ||
        wheelHighXLoc < 0 || wheelHighYLoc < 0 || splitActiveLoc < 0 ||
        splitShadowHueLoc < 0 || splitHighlightHueLoc < 0 ||
        splitBalanceLoc < 0 || saturationLoc < 0 ||
        lutStrengthLoc < 0 || lutLoc < 0 || lutSizeLoc < 0 ||
        lutDomainMinLoc < 0 || lutDomainMaxLoc < 0 || lutRoleLoc < 0 ||
        lutPassthroughLoc < 0 || lutInTransferLoc < 0 ||
        lutOutTransferLoc < 0 || lutWorkingToInputLoc < 0 ||
        lutOutputToWorkingLoc < 0) {
        *detail = "shader-contract: missing a develop-post uniform";
        return false;
    }
    // The NR passes' own small sets: uSrc/uSize/uChromaDenoise on the
    // horizontal pass, uSrc/uGuide/uSize/uChromaDenoise on the vertical
    // (uGuide = the ORIGINAL pre-NR image — the CPU's second pass keeps
    // the original luma guide), uSrc/uSize/uNoiseReduction on the luma.
    nrChromaHSrcLoc = glGetUniformLocation(nrChromaHProgram, "uSrc");
    nrChromaHSizeLoc = glGetUniformLocation(nrChromaHProgram, "uSize");
    nrChromaHStrengthLoc = glGetUniformLocation(nrChromaHProgram, "uChromaDenoise");
    if (nrChromaHSrcLoc < 0 || nrChromaHSizeLoc < 0 ||
        nrChromaHStrengthLoc < 0) {
        *detail = "shader-contract: missing a nr-chroma-h uniform";
        return false;
    }
    nrChromaVSrcLoc = glGetUniformLocation(nrChromaVProgram, "uSrc");
    nrChromaVGuideLoc = glGetUniformLocation(nrChromaVProgram, "uGuide");
    nrChromaVSizeLoc = glGetUniformLocation(nrChromaVProgram, "uSize");
    nrChromaVStrengthLoc = glGetUniformLocation(nrChromaVProgram, "uChromaDenoise");
    if (nrChromaVSrcLoc < 0 || nrChromaVGuideLoc < 0 ||
        nrChromaVSizeLoc < 0 || nrChromaVStrengthLoc < 0) {
        *detail = "shader-contract: missing a nr-chroma-v uniform";
        return false;
    }
    nrLumaSrcLoc = glGetUniformLocation(nrLumaProgram, "uSrc");
    nrLumaSizeLoc = glGetUniformLocation(nrLumaProgram, "uSize");
    nrLumaStrengthLoc = glGetUniformLocation(nrLumaProgram, "uNoiseReduction");
    if (nrLumaSrcLoc < 0 || nrLumaSizeLoc < 0 || nrLumaStrengthLoc < 0) {
        *detail = "shader-contract: missing a nr-luma uniform";
        return false;
    }
    storeSrcLoc = glGetUniformLocation(storeProgram, "uSrc");
    storeSizeLoc = glGetUniformLocation(storeProgram, "uSize");
    if (storeSrcLoc < 0 || storeSizeLoc < 0) {
        *detail = "shader-contract: missing uSrc/uSize";
        return false;
    }
    blitSrcLoc = glGetUniformLocation(blitProgram, "uSrc");
    blitSizeLoc = glGetUniformLocation(blitProgram, "uSize");
    blitRotLoc = glGetUniformLocation(blitProgram, "uRotation");
    if (blitSrcLoc < 0 || blitSizeLoc < 0 || blitRotLoc < 0) {
        *detail = "shader-contract: missing blit uSrc/uSize/uRotation";
        return false;
    }

    // --- presentation-time extension (M5) ------------------------------------
    // eglPresentationTimeANDROID is an EXTENSION: resolved dynamically, and
    // its ABSENCE is legal (older devices). Null is not an init failure —
    // the readback path never needs it; the encoder path refuses honestly
    // with this named reason instead (never a crash, never a wall-clock
    // stamp pretending to be media time).
    presentationTime = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
        eglGetProcAddress("eglPresentationTimeANDROID"));

    // --- static texture-unit bindings (the unit assignment never changes) --
    // Unit map: 0 = CFA, 1 = the chain's pass-source texture (rebound per
    // draw), 2 = the store pass's scratch, 3 = shading, 4 = the NR vertical
    // pass's ORIGINAL guide (texA — a unit nothing else samples, so the
    // guide survives the per-draw rebinds on unit 1 untouched), 5 = the
    // stage-24 LUT 3D texture (the LUT port, commit (a); bound once here —
    // the upload block in developPass swaps the texture OBJECT on that
    // unit per LUT revision, the unit assignment itself never moves).
    glUseProgram(preProgram);
    glUniform1i(cfaLoc, 0);      // CFA: GL_TEXTURE0
    glUniform1i(shadingLoc, 3);  // shading: GL_TEXTURE3
    glUseProgram(postProgram);
    glUniform1i(postSrcLoc, 1); // post source: GL_TEXTURE1 (the chain's
                                // final intermediate, rebound per frame)
    glUniform1i(lutLoc, 5);     // LUT 3D texture: GL_TEXTURE5 (pinned at
                                // open — a statically-declared sampler3D
                                // whose unit is never set would default to
                                // 0 and collide with uCfa, the harness's
                                // own lutDummy comment)
    glUseProgram(nrChromaHProgram);
    glUniform1i(nrChromaHSrcLoc, 1);  // NR sources: GL_TEXTURE1 (rebound
    glUseProgram(nrChromaVProgram);   // per draw along the chain)
    glUniform1i(nrChromaVSrcLoc, 1);
    glUniform1i(nrChromaVGuideLoc, 4);  // the ORIGINAL pre-NR guide: unit 4
    glUseProgram(nrLumaProgram);
    glUniform1i(nrLumaSrcLoc, 1);
    glUseProgram(storeProgram);
    glUniform1i(storeSrcLoc, 1); // store source: GL_TEXTURE1
    glUseProgram(blitProgram);
    glUniform1i(blitSrcLoc, 1);  // blit source: GL_TEXTURE1 (the developed
                                 // texture — the same unit the store pass
                                 // reads it from)
    glUseProgram(0);
    return true;
}

// ---- the frame job ------------------------------------------------------------

bool DevelopSession::Impl::developPass(const DevelopFrameRequest& req,
                                       DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;
    if (req.cfa == nullptr || req.meta == nullptr ||
        w == 0 || h == 0 ||
        req.cfa->size() != static_cast<size_t>(w) * h) {
        out.detail = "input: cfa size != w*h";
        return false;
    }
    out.width = w;
    out.height = h;

    // --- CPU-computed uniforms (documented copies of developCpu's own
    // stage-1/5/6 math — the harness runs the same rules; the shader
    // applies them in-hardware) --------------------------------------------
    const bool useFrameBlack = req.info.dynamicBlack[0] >= 0;
    const float white = req.info.dynamicWhite > 0
        ? static_cast<float>(req.info.dynamicWhite)
        : static_cast<float>(req.meta->white);
    float black[4];
    for (int slot = 0; slot < 4; ++slot)
        black[slot] = useFrameBlack
            ? static_cast<float>(req.info.dynamicBlack[slot])
            : static_cast<float>(req.meta->black[slot]);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;
    const float invNeutral[3] = {
        req.meta->asShotNeutral[0] > 0.0f ? 1.0f / req.meta->asShotNeutral[0] : 1.0f,
        req.meta->asShotNeutral[1] > 0.0f ? 1.0f / req.meta->asShotNeutral[1] : 1.0f,
        req.meta->asShotNeutral[2] > 0.0f ? 1.0f / req.meta->asShotNeutral[2] : 1.0f,
    };
    int cell[4];
    cellFor(req.info, cell);
    // Stage 2's gate: the grade's keepVignetting AND the map's presence —
    // the same fragmentShadingApplies rule the Kotlin coverage gate uses.
    const bool shadingPresent = !req.meta->lensShading.empty() &&
                                 req.meta->shadingW > 0 && req.meta->shadingH > 0;
    const bool applyShading = req.keepVignetting && shadingPresent;
    // Stage 3's gain vector: meta.wbGains with the >0 guard (a DIFFERENT
    // vector from uWb's 1/asShotNeutral).
    const float hrGain[3] = {
        req.meta->wbGains[0] > 0.0f ? req.meta->wbGains[0] : 1.0f,
        req.meta->wbGains[1] > 0.0f ? req.meta->wbGains[1] : 1.0f,
        req.meta->wbGains[2] > 0.0f ? req.meta->wbGains[2] : 1.0f,
    };
    // Stage 6's matrix (the documented buildCameraToRec709 copy).
    float cam2rgb[9];
    const bool hasProfile = buildCameraToRec709(*req.meta, cam2rgb);
    // Stage 14: exposure = 2^ev (developCpu's own convention; ev==0 gives
    // exactly 1.0f, matching the CPU's neutral gate).
    const float exposure = std::pow(2.0f, req.exposureEv);
    // Stages 16/17's asShot gate: the CPU's own `if (!grade.asShot)` branch,
    // hoisted to the int uniform the shader branches on (1 = run the manual
    // temp/tint path). Same shape as the shading gate above.
    const int wbManual = req.asShot ? 0 : 1;
    // Stage 19's own guard: the CPU's `if (splitShadowHue >= 0 ||
    // splitHighlightHue >= 0)` branch, hoisted to the int uniform.
    const int splitActive =
        (req.splitShadowHue >= 0.0f || req.splitHighlightHue >= 0.0f) ? 1 : 0;

    // --- storage allocation ONCE per resolution/resource change ------------
    // Immutable glTexStorage2D + per-frame glTexSubImage2D uploads: the
    // audit's finding against glTexImage2D-per-frame. The shading map is
    // per-CLIP (constant across frames), so its texture uploads once and
    // stays; the CFA is per-frame.
    if (cfaW != w || cfaH != h) {
        // (Re)allocate the resolution-dependent storage. Any previous
        // allocation is deleted first (a resolution change mid-export is
        // impossible for one clip, but the session outlives clips by
        // design).
        if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
        if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
        if (scratchTex != 0) { glDeleteTextures(1, &scratchTex); scratchTex = 0; }
        if (texA != 0) { glDeleteTextures(1, &texA); texA = 0; }
        if (texB != 0) { glDeleteTextures(1, &texB); texB = 0; }
        if (texC != 0) { glDeleteTextures(1, &texC); texC = 0; }
        if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
        if (scratchFbo != 0) { glDeleteFramebuffers(1, &scratchFbo); scratchFbo = 0; }
        if (fboA != 0) { glDeleteFramebuffers(1, &fboA); fboA = 0; }
        if (fboB != 0) { glDeleteFramebuffers(1, &fboB); fboB = 0; }
        if (fboC != 0) { glDeleteFramebuffers(1, &fboC); fboC = 0; }
        if (outBuf != 0) { glDeleteBuffers(1, &outBuf); outBuf = 0; }
        outBytes = 0;
        cfaW = 0; cfaH = 0;

        // CFA texture: GL_R16UI, the RAW samples in their native values
        // (normalization is stage 1 IN-SHADER — that is the point of the
        // fused pass; the CPU-computed uBlack/uRange carry the
        // frame-preferred levels). Row-major CPU order: texture row y =
        // CFA row y.
        glGenTextures(1, &cfaTex);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, cfaTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(R16UI)", &out.detail) != GL_NO_ERROR) return false;

        // develop FBO: RGBA32F attachment (EXT_color_buffer_float, gated
        // in THIS context at open).
        glGenTextures(1, &developTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, developTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA32F)", &out.detail) != GL_NO_ERROR) return false;
        glGenFramebuffers(1, &developFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               developTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "develop fbo incomplete (RGBA32F not renderable here)";
            return false;
        }

        // The chain's three RGBA32F ping-pong intermediates (the spatial-NR
        // port): A = the PRE pass's output (and the chroma vertical pass's
        // ORIGINAL guide), B/C = the NR passes' scratch pair. NEAREST +
        // CLAMP_TO_EDGE — the NR passes fetch 1:1 texels and clamp their
        // own offsets, exactly like the CPU's index clamps; no filtering,
        // no wrap. Allocated ONCE per resolution with the rest.
        for (int slot = 0; slot < 3; ++slot) {
            GLuint* tex = slot == 0 ? &texA : (slot == 1 ? &texB : &texC);
            GLuint* fbo = slot == 0 ? &fboA : (slot == 1 ? &fboB : &fboC);
            glGenTextures(1, tex);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, *tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F,
                           static_cast<GLsizei>(w), static_cast<GLsizei>(h));
            if (glErrorAfter("glTexStorage2D(RGBA32F chain)", &out.detail) != GL_NO_ERROR) {
                return false;
            }
            glGenFramebuffers(1, fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, *tex, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                out.detail = "chain fbo incomplete (RGBA32F not renderable here)";
                return false;
            }
        }

        // scratch RGBA8 (the store pass's throwaway target — never the
        // develop FBO, whose texture is the source: a feedback loop). The
        // RGBA8 contents are never read; the SSBO is the real output.
        glGenTextures(1, &scratchTex);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, scratchTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA8)", &out.detail) != GL_NO_ERROR) return false;
        glGenFramebuffers(1, &scratchFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               scratchTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "store fbo incomplete";
            return false;
        }

        // SSBO: w*h*3 floats, STREAM_READ (the store pass writes it, the
        // map reads it back — the harness's exact sizes and usage).
        const uint64_t bytes = static_cast<uint64_t>(w) * h * 3 * sizeof(float);
        glGenBuffers(1, &outBuf);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(bytes),
                     nullptr, GL_STREAM_READ);
        if (glErrorAfter("glBufferData(out)", &out.detail) != GL_NO_ERROR) return false;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf);
        outBytes = bytes;
        cfaW = w;
        cfaH = h;
    }

    // The shading map: per-CLIP, so once per session (the session serves
    // one export's frames; the map is constant across frames of one clip,
    // and a map appearing mid-session is not possible — the meta is
    // per-clip). The 1x1 dummy covers the absent-map case (the shader only
    // fetches when uHasShading==1, so the dummy is never read; the
    // sampler uniform is always set, keeping the program's uniform set
    // complete either way).
    if (shadingTex == 0) {
        glGenTextures(1, &shadingTex);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, shadingTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (applyShading) {
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F,
                           static_cast<GLsizei>(req.meta->shadingW),
                           static_cast<GLsizei>(req.meta->shadingH * 4));
            if (glErrorAfter("glTexStorage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return false;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(req.meta->shadingW),
                            static_cast<GLsizei>(req.meta->shadingH * 4),
                            GL_RED, GL_FLOAT, req.meta->lensShading.data());
            if (glErrorAfter("glTexSubImage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return false;
            shadingW = req.meta->shadingW;
            shadingH = req.meta->shadingH;
        } else {
            const float dummy = 1.0f;
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, 1, 1);
            if (glErrorAfter("glTexStorage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return false;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, &dummy);
            if (glErrorAfter("glTexSubImage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return false;
            shadingW = 1;
            shadingH = 1;
        }
    }

    // --- stage 24 LUT: parse + binding + 3D texture, ONCE per revision -------
    // The LUT port, commit (a). The parse/binding semantics mirror the CPU
    // seam (jni_bridge.cpp's resolveLut/resolveBinding) exactly:
    //   - no path (empty) = no LUT: the dummy table + the strength gate
    //     closed (uLutStrength 0) reproduce the no-LUT chain byte-for-byte;
    //   - a MISSING file (parse IoError) renders as no LUT — LutStore's
    //     Kotlin check already passed, so a file removed between the check
    //     and this open degrades to the no-LUT look instead of failing the
    //     whole clip (the resolveLut precedent);
    //   - a PARSE failure (BadHeader/Unsupported/Truncated) fails the frame
    //     LOUDLY — ok=false with the reason in [detail] (the resolveLut
    //     precedent: never a silent drop of stage 24). The caller's latch
    //     rule (runJob latches render failures) then falls the export back
    //     to CPU with this reason, the honest fallback.
    // The texture: RGBA16F, hardware trilinear (GL_LINEAR — ES 3.x core
    // filters 16-bit float), CLAMP_TO_EDGE on all three axes, ONE mip
    // level. The table's memory order (entry (i,j,k) at
    // rgb[(i + n*j + n*n*k)*3], red fastest) IS GL_TEXTURE_3D's s/r/t
    // order, so the upload is a straight rgb->rgba interleave through
    // floatToHalfBits — NO reordering. Half precision is the quantization
    // the parity claim bounds (measured, never assumed away).
    // The revision cache: [lutParsedPath]/[lutParsed] keep the LAST parse
    // (keyed by path, SEPARATE from the upload key — a strength step across
    // 0 unbinds the table but must NOT re-read the file on the way back),
    // [lutUploadedPath] names the table currently in [lutTex] ("" = the
    // dummy), so a path that did not change does not re-read or re-upload;
    // the binding cache keys on the five ordinals. The dummy (2x2x2, all
    // 1.0, bindLutDummyTex) keeps the sampler3D complete when no LUT is
    // bound — the shading dummy's own rule — and the shader's strength
    // gate means it is never sampled on the no-LUT path.
    {
        const std::string& path = req.lutPath;
        const bool wantLut = !path.empty() && req.lutStrength != 0.0f;
        // The binding compiles whenever the ordinals change (a legacy
        // role < 0 needs none — the shader's role-0 branch is the legacy
        // pair). Compiled BEFORE the table decision so a role change on
        // the same path still rebinds.
        if (req.lutRole >= 0) {
            const int key[5] = {req.lutRole, req.lutInGamut, req.lutInTransfer,
                                req.lutOutGamut, req.lutOutTransfer};
            bool keyMatch = lutBindingOk;
            for (int i = 0; i < 5; ++i) keyMatch = keyMatch && lutBindingKey[i] == key[i];
            if (!keyMatch) {
                rawedit::LutInterpretation interp;
                interp.role = static_cast<rawedit::LutRole>(req.lutRole);
                interp.input.gamut = static_cast<rawedit::GamutId>(req.lutInGamut);
                interp.input.transfer = static_cast<rawedit::TransferId>(req.lutInTransfer);
                interp.output.gamut = static_cast<rawedit::GamutId>(req.lutOutGamut);
                interp.output.transfer = static_cast<rawedit::TransferId>(req.lutOutTransfer);
                lutBinding = rawedit::compileLutBinding(interp);
                for (int i = 0; i < 5; ++i) lutBindingKey[i] = key[i];
                lutBindingOk = true;
            }
        }
        if (!wantLut) {
            // No LUT for this frame: bind the dummy table (the strength
            // gate below keeps it unread). [lutUploadedPath] tracks the
            // dummy so a later frame WITH a LUT re-uploads; the PARSE
            // cache [lutParsedPath] survives this transition by design —
            // a strength step 0 -> s > 0 on the same path re-uploads the
            // table but never re-reads the file.
            if (lutUploadedPath != "") {
                if (lutTex == 0) glGenTextures(1, &lutTex);
                bindLutDummyTex(lutTex);
                if (glErrorAfter("glTexImage3D(RGBA16F lut dummy)", &out.detail) != GL_NO_ERROR) return false;
                lutUploadedPath.clear();
            }
        } else if (lutUploadedPath != path) {
            // A LUT is active and the table on the unit is not this path's.
            // Parse ONLY on a [lutParsedPath] miss (the file read is the
            // expensive step; the upload alone may repeat after a
            // strength 0 -> s transition, which is cheap GL work).
            if (lutParsedPath != path || lutParsed == nullptr) {
                auto parsed = rawedit::parseCubeFile(path);
                if (!parsed.isOk()) {
                    // The resolveLut split, verbatim: a MALFORMED cube is a
                    // loud frame failure (never a silent drop of stage 24);
                    // a MISSING file (IoError) degrades to the no-LUT look.
                    lutParsed.reset();
                    lutParsedPath.clear();
                    if (parsed.code == rawedit::ErrorCode::IoError) {
                        if (lutUploadedPath != "") {
                            if (lutTex == 0) glGenTextures(1, &lutTex);
                            bindLutDummyTex(lutTex);
                            if (glErrorAfter("glTexImage3D(RGBA16F lut dummy)", &out.detail) != GL_NO_ERROR) return false;
                            lutUploadedPath.clear();
                        }
                        // This frame renders with the gate closed (the
                        // strength pin below), the honest no-LUT look.
                    } else {
                        out.detail = std::string("lut: ") +
                                     rawedit::errorName(parsed.code) + " — " +
                                     parsed.detail +
                                     " (recovery: pick a valid .cube file)";
                        return false;
                    }
                } else {
                    lutParsed = std::make_unique<rawedit::Lut>(std::move(*parsed.value));
                    lutParsedPath = path;
                }
            }
            if (lutParsedPath == path && lutParsed != nullptr) {
                const rawedit::Lut& lut = *lutParsed;
                const size_t n = static_cast<size_t>(lut.n);
                // rgb -> rgba interleave through the half converter, in the
                // table's own s/r/t order (no reordering).
                std::vector<uint16_t> rgba(n * n * n * 4);
                const size_t count = n * n * n;
                for (size_t e = 0; e < count; ++e) {
                    rgba[e * 4 + 0] = floatToHalfBits(lut.rgb[e * 3 + 0]);
                    rgba[e * 4 + 1] = floatToHalfBits(lut.rgb[e * 3 + 1]);
                    rgba[e * 4 + 2] = floatToHalfBits(lut.rgb[e * 3 + 2]);
                    rgba[e * 4 + 3] = 0x3c00u;  // alpha unused, pinned 1.0
                }
                if (lutTex == 0) glGenTextures(1, &lutTex);
                glActiveTexture(GL_TEXTURE5);
                glBindTexture(GL_TEXTURE_3D, lutTex);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
                glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F,
                             static_cast<GLsizei>(lut.n),
                             static_cast<GLsizei>(lut.n),
                             static_cast<GLsizei>(lut.n), 0,
                             GL_RGBA, GL_HALF_FLOAT, rgba.data());
                if (glErrorAfter("glTexImage3D(RGBA16F lut)", &out.detail) != GL_NO_ERROR) return false;
                lutUploadedPath = path;
            }
        }
    }
    // What the POST draw reads: a table is ACTIVE only when this frame
    // wants the LUT AND the parse survived (a missing file degrades to
    // the no-LUT look, so the pins below — not a stale table — carry the
    // frame). The parsed table stays cached for the next frame's upload.
    const bool lutActive = !req.lutPath.empty() && req.lutStrength != 0.0f
        && lutParsed != nullptr && lutParsedPath == req.lutPath;

    // --- per-frame upload: the CFA, into the immutable storage -------------
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cfaTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                    static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                    GL_RED_INTEGER, GL_UNSIGNED_SHORT, req.cfa->data());
    if (glErrorAfter("glTexSubImage2D(R16UI)", &out.detail) != GL_NO_ERROR) return false;

    // --- draw 1: the PRE pass (stages 1-6) into texA ------------------------
    // The chain shape (the spatial-NR port): PRE -> [NR, gated per grade]
    // -> POST, with the NR passes at the CPU's exact stage 7/8 position.
    // texA is both PRE's output and the vertical chroma pass's ORIGINAL
    // guide (bound on unit 4, which no other draw touches); the NR
    // sources ride unit 1 with per-draw rebinds; POST ALWAYS writes
    // developTex (the store/blit passes read THAT texture — writing
    // POST's output to an intermediate the chain also reads would be a
    // framebuffer feedback loop).
    glUseProgram(preProgram);
    glBindFramebuffer(GL_FRAMEBUFFER, fboA);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(sizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform4i(cellLoc, cell[0], cell[1], cell[2], cell[3]);
    glUniform4f(blackLoc, black[0], black[1], black[2], black[3]);
    glUniform4f(rangeLoc, range[0], range[1], range[2], range[3]);
    glUniform1i(hasShadingLoc, applyShading ? 1 : 0);
    glUniform2i(shadingSizeLoc,
                static_cast<GLint>(applyShading ? req.meta->shadingW : 1),
                static_cast<GLint>(applyShading ? req.meta->shadingH : 1));
    glUniform1i(hrLoc, req.highlightReconstruction ? 1 : 0);
    glUniform3f(hrGainLoc, hrGain[0], hrGain[1], hrGain[2]);
    glUniform3f(wbLoc, invNeutral[0], invNeutral[1], invNeutral[2]);
    // transpose=GL_TRUE: row-major upload, the CPU contract.
    glUniformMatrix3fv(camLoc, 1, GL_TRUE, cam2rgb);
    glUniform1i(hasProfileLoc, hasProfile ? 1 : 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(pre)", &out.detail) != GL_NO_ERROR) return false;

    // --- draws 2-4: the spatial NR passes (stages 7/8), gated per grade ----
    // The CPU call sites' own gate + clamp (Develop.cpp: gate != 0.0f,
    // then clamp [0,100]/100 — the impl's strength <= 0 early-out): the
    // session skips the draws entirely for a clamped strength of 0, and
    // mirrors the impls' own w/h guards (chroma w<2/h<2, luma w<3/h<3).
    // The in-shader gate + clamp is the second lock — a skipped draw and
    // a gate-closed draw produce the same pass-through.
    const float chromaStrength = req.chromaDenoise != 0.0f
        ? std::min(std::max(req.chromaDenoise, 0.0f), 100.0f) / 100.0f
        : 0.0f;
    const float lumaStrength = req.noiseReduction != 0.0f
        ? std::min(std::max(req.noiseReduction, 0.0f), 100.0f) / 100.0f
        : 0.0f;
    const bool runChroma = chromaStrength > 0.0f && w >= 2 && h >= 2;
    const bool runLuma = lumaStrength > 0.0f && w >= 3 && h >= 3;
    // The chain's live texture after the NR section: texA when no chroma
    // pass ran, texC after the chroma pair (H: A->B, V: B+guide(A)->C),
    // texB after a luma-only pass (A->B).
    GLuint chainTex = texA;
    if (runChroma) {
        // Stage 7, horizontal: texA -> texB. The pass reads each texel's
        // OWN luma as the guide (the CPU's first pass does — only the
        // second keeps the original).
        glUseProgram(nrChromaHProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texA);
        glBindFramebuffer(GL_FRAMEBUFFER, fboB);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(nrChromaHSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(nrChromaHStrengthLoc, req.chromaDenoise);  // raw; the shader gates + clamps
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(nr-chroma-h)", &out.detail) != GL_NO_ERROR) return false;

        // Stage 7, vertical: texB + the ORIGINAL guide texA (unit 4) ->
        // texC. uGuide is texA — PRE's untouched output, exactly the
        // CPU's second-pass original-luma rule.
        glUseProgram(nrChromaVProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texB);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, texA);
        glBindFramebuffer(GL_FRAMEBUFFER, fboC);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(nrChromaVSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(nrChromaVStrengthLoc, req.chromaDenoise);  // raw; the shader gates + clamps
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(nr-chroma-v)", &out.detail) != GL_NO_ERROR) return false;
        chainTex = texC;
    }
    if (runLuma) {
        // Stage 8, the one-pass 3x3 bilateral: the chain's live texture
        // -> texB (free — the chroma pair left it consumed, but texB is
        // not the chain's live texture at this point, and the luma pass
        // reads only its own source). Reads texC when chroma ran, texA
        // when it did not.
        const GLuint lumaSrc = chainTex;
        glUseProgram(nrLumaProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, lumaSrc);
        glBindFramebuffer(GL_FRAMEBUFFER, fboB);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(nrLumaSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(nrLumaStrengthLoc, req.noiseReduction);  // raw; the shader gates + clamps
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(nr-luma)", &out.detail) != GL_NO_ERROR) return false;
        chainTex = texB;
    }

    // --- draw 5: the POST pass (stages 9-20+23+OETF) into developTex ------
    glUseProgram(postProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, chainTex);
    glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(postSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform1f(exposureLoc, exposure);
    glUniform1f(highlightsLoc, req.highlights);  // raw; the shader computes s
    // The ten pointwise stages' raw grade values (each 0.0f is the CPU's
    // own neutral skip) + the two hoisted int gates.
    glUniform1f(blackPointLoc, req.blackPoint);
    glUniform1f(whitePointLoc, req.whitePoint);
    glUniform1f(shadowsLoc, req.shadows);
    glUniform1f(midtonesLoc, req.midtones);
    glUniform1f(contrastLoc, req.contrast);
    glUniform1i(wbManualLoc, wbManual);
    glUniform1f(tempKLoc, req.tempK);
    glUniform1f(tintLoc, req.tint);
    glUniform1f(wheelShadowXLoc, req.wheelShadowX);
    glUniform1f(wheelShadowYLoc, req.wheelShadowY);
    glUniform1f(wheelMidXLoc, req.wheelMidX);
    glUniform1f(wheelMidYLoc, req.wheelMidY);
    glUniform1f(wheelHighXLoc, req.wheelHighX);
    glUniform1f(wheelHighYLoc, req.wheelHighY);
    glUniform1i(splitActiveLoc, splitActive);
    glUniform1f(splitShadowHueLoc, req.splitShadowHue);
    glUniform1f(splitHighlightHueLoc, req.splitHighlightHue);
    glUniform1f(splitBalanceLoc, req.splitBalance);
    glUniform1f(saturationLoc, req.saturation);
    glUniform1i(tonemapLoc, req.tonemap);
    glUniform1i(oetfLoc, 1);  // the export shape: linearToRec709 in-shader
    // Stage 24 LUT (the LUT port, commit (a)): the raw strength ([0,1],
    // Kotlin-mapped) + the table's shape/binding uniforms. The neutral
    // pins when no LUT is active (strength 0, size 0, role 0) keep the
    // POST draw byte-identical to the pre-LUT chain — the shader's own
    // gate is the skip, exactly like every raw grade value above. The
    // role rides the request's ordinal clamped to the shader's own
    // vocabulary [0,3] — an ordinal the shader does not know falls back
    // to role 0 (the legacy plain-sampler pair), the same honest
    // fallback the CPU's nullptr binding takes, never a silent
    // mis-render.
    glUniform1f(lutStrengthLoc, req.lutStrength);
    glUniform1i(lutSizeLoc, lutActive ? lutParsed->n : 0);
    glUniform3fv(lutDomainMinLoc, 1, lutActive ? lutParsed->domainMin : kLutDomainMinDefault);
    glUniform3fv(lutDomainMaxLoc, 1, lutActive ? lutParsed->domainMax : kLutDomainMaxDefault);
    glUniform1i(lutRoleLoc, std::min(std::max(req.lutRole, 0), 3));
    glUniform1i(lutPassthroughLoc, lutBindingOk && lutActive
                   ? (lutBinding.passthroughEncodings ? 1 : 0) : 0);
    glUniform1i(lutInTransferLoc, req.lutInTransfer);
    glUniform1i(lutOutTransferLoc, req.lutOutTransfer);
    // Row-major CPU contract matrices, uploaded with transpose=GL_TRUE —
    // the uCam precedent (the GLSL text documents the same).
    glUniformMatrix3fv(lutWorkingToInputLoc, 1, GL_TRUE,
                       lutBindingOk && lutActive ? lutBinding.workingToInput : kLutIdentity);
    glUniformMatrix3fv(lutOutputToWorkingLoc, 1, GL_TRUE,
                       lutBindingOk && lutActive ? lutBinding.outputToWorking : kLutIdentity);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(post)", &out.detail) != GL_NO_ERROR) return false;
    // The developed frame now lives in developTex (RGBA32F, OETF applied).
    // runJob continues with the store pass + SSBO readback; runEncoderJob
    // blits this texture straight to the encoder window surface instead.
    return true;
}

void DevelopSession::Impl::runJob(const DevelopFrameRequest& req,
                                  DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;

    if (!developPass(req, out)) return;

    // --- draw 2: the store pass into the SSBO (never glReadPixels) ---------
    glUseProgram(storeProgram);
    glActiveTexture(GL_TEXTURE1);  // the develop texture, unit 1 (the same
                                   // unit the develop pass wrote; the sampler
                                   // uniform was set once at open)
    glBindTexture(GL_TEXTURE_2D, developTex);
    glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(storeSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    // Re-bind the SSBO to indexed binding 0 before EVERY store draw (the
    // harness binds once per call; a persistent session must not rely on
    // the allocation branch's binding surviving unrelated state changes —
    // one redundant glBindBufferBase per frame is free next to the draw).
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(store)", &out.detail) != GL_NO_ERROR) return;

    // --- readback: a MAPPED buffer, NEVER glReadPixels ---------------------
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
    void* mapped = glMapBufferRange(
        GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(outBytes),
        GL_MAP_READ_BIT);
    if (mapped == nullptr) {
        glErrorAfter("glMapBufferRange", &out.detail);
        if (out.detail.empty()) out.detail = "glMapBufferRange returned null";
        return;
    }
    const float* gpu = static_cast<const float*>(mapped);
    const size_t n3 = static_cast<size_t>(w) * h * 3;
    out.rgb888.assign(n3, 0);
    for (size_t i = 0; i < n3; ++i) {
        // The shader applied the OETF (uOetf=1); the tail does what
        // nativeExportFrame's pack loop does after linearToRec709: the
        // toByte quantize (clamp [0,1], v*255+0.5).
        out.rgb888[i] = toByte(gpu[i]);
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    out.ok = true;
}

void DevelopSession::Impl::runSurfaceJob(ANativeWindow* window, bool* ok,
                                         std::string* detail) {
    // Attach/detach the encoder window surface (M4's runSurfaceJob shape).
    // Entered with the pbuffer current and the lock released. NEVER latches
    // (the Impl comment): a failure leaves the session healthy, the encoder
    // path just stays unavailable and the caller falls back with this
    // reason in ONE log line.
    *ok = false;
    if (detail != nullptr) detail->clear();

    // OWNERSHIP (the header's contract, the M4 setSurface shape): the
    // caller transferred its ANativeWindow reference at setEncoderSurface
    // entry; THIS job is the served path, so the release happens here, on
    // the GL thread, on EVERY attach path (success and failure —
    // eglCreateWindowSurface acquires the window's own reference on
    // success, so ours is redundant; on failure nothing else holds it). A
    // DETACH carries no reference. The fast-refusal paths (closed /
    // unhealthy session) release on the CALLING thread instead — see
    // setEncoderSurface().
    if (window == nullptr) {
        // Detach: destroy any attached encoder surface, then nothing more.
        // The pbuffer is current again and the session serves readback jobs
        // as before.
        if (encoderSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
            eglDestroySurface(egl.display, encoderSurface);
            encoderSurface = EGL_NO_SURFACE;
        }
        *ok = true;
        return;
    }

    // Destroy any previous encoder surface first (a re-attach replaces it;
    // EGL owns at most one window surface per ANativeWindow here).
    if (encoderSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        eglDestroySurface(egl.display, encoderSurface);
        encoderSurface = EGL_NO_SURFACE;
    }

    // The session's OWN config (opened window-capable + recordable at init)
    // — the SAME config the context was created with, which is what makes
    // eglCreateWindowSurface legal here. EGL_RECORDABLE_ANDROID is a
    // config-selection attribute only (EGL_ANDROID_recordable defines it
    // for eglChooseConfig/eglGetConfigAttrib lists): passing it in the
    // SURFACE attribs is invalid, and the Xiaomi 15 Ultra's EGL rejects
    // the call with EGL_BAD_ATTRIBUTE. The flag rides the CONFIG — the
    // recordable=true egl.open at init already gave the codec's
    // BufferQueue consumer what it needs from the producer config. Null
    // surface attribs, matching the writer path and the preview session's
    // device-proven wrap.
    EGLSurface created = eglCreateWindowSurface(egl.display, egl.config,
                                                 window, nullptr);
    if (created == EGL_NO_SURFACE) {
        // Read the thread-local EGL error HERE, before any cleanup —
        // it does not survive later calls on this thread.
        const EGLint eglErr = eglGetError();
        if (detail != nullptr) {
            char buf[96];
            snprintf(buf, sizeof(buf),
                     "eglCreateWindowSurface(encoder) failed eglError=0x%04x",
                     (unsigned)eglErr);
            *detail = buf;
        }
        ANativeWindow_release(window);
        return;
    }
    // Probe make-current on the new surface BEFORE accepting it: an
    // unusable surface must be refused now, not discovered mid-render.
    if (!eglMakeCurrent(egl.display, created, created, egl.context)) {
        // Same-thread, pre-cleanup eglGetError (thread-local state).
        const EGLint eglErr = eglGetError();
        if (detail != nullptr) {
            char buf[96];
            snprintf(buf, sizeof(buf),
                     "eglMakeCurrent(encoder) failed eglError=0x%04x",
                     (unsigned)eglErr);
            *detail = buf;
        }
        eglDestroySurface(egl.display, created);
        ANativeWindow_release(window);
        return;
    }
    encoderSurface = created;
    // Swap as fast as frames arrive — an export is not a display; pacing
    // would only add latency in front of the codec.
    eglSwapInterval(egl.display, 0);
    // Back to the pbuffer: the session's default current surface (the FBO
    // draws do not care about the window binding, but every job starts
    // from the same state).
    eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
    ANativeWindow_release(window);
    *ok = true;
}

void DevelopSession::Impl::runEncoderJob(const DevelopFrameRequest& req,
                                         int64_t ptsUs,
                                         int rotationDeg,
                                         DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;

    if (encoderSurface == EGL_NO_SURFACE) {
        out.detail = "encoder surface not attached";
        return;
    }
    if (presentationTime == nullptr) {
        // Honest gate (the header's rule): the extension was absent at
        // init; the frame is NOT stamped with a guess. The caller falls
        // back with this reason.
        out.detail = "eglPresentationTimeANDROID unavailable";
        return;
    }
    if (ptsUs < 0) {
        out.detail = "encoder pts must be non-negative us";
        return;
    }

    // The shared develop pass — the EXACT same draw the readback path
    // runs (same uniforms, same allocation, same FBO); only the tail
    // differs (a blit to the window surface instead of the store pass +
    // SSBO readback).
    if (!developPass(req, out)) return;

    // --- the blit: developTex -> the encoder window surface ----------------
    // Make the ENCODER surface current (the pbuffer stays the session's
    // default; the readback path and this path stay independent).
    if (!eglMakeCurrent(egl.display, encoderSurface, encoderSurface,
                        egl.context)) {
        out.detail = "eglMakeCurrent(encoder) failed";
        return;
    }
    // BLIT_FRAGMENT's Y flip reproduces the writer's Surface-mode
    // orientation verbatim (the develop pass's texel row 0 is the image
    // top; the window surface presents row 0 at the bottom). The rotation
    // fold rides the SAME draw: uSize stays the SOURCE's w x h (the
    // texture's own dims — the fold maps each rotated destination pixel
    // back to its source texel), while a quarter turn's DESTINATION
    // viewport is the swapped h x w (the encoder surface was configured
    // with the rotated dims; the fold's g walks that surface).
    const bool swap = (rotationDeg == 90 || rotationDeg == 270);
    const GLsizei dstW = swap ? static_cast<GLsizei>(h) : static_cast<GLsizei>(w);
    const GLsizei dstH = swap ? static_cast<GLsizei>(w) : static_cast<GLsizei>(h);
    glUseProgram(blitProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, developTex);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);  // draw to the window surface
    glViewport(0, 0, dstW, dstH);
    glUniform2i(blitSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform1i(blitRotLoc, rotationDeg);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(blit)", &out.detail) != GL_NO_ERROR) {
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    // Media time, NEVER the wall clock: the SAME normalized pts the
    // writer path stamps (PtsTimeline.ptsNs's math — us -> ns).
    if (presentationTime(egl.display, encoderSurface,
                         static_cast<EGLnsecsANDROID>(ptsUs) * 1000) !=
        EGL_TRUE) {
        out.detail = "eglPresentationTimeANDROID failed";
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    if (eglSwapBuffers(egl.display, encoderSurface) != EGL_TRUE) {
        out.detail = "eglSwapBuffers(encoder) failed";
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    // Back to the pbuffer for the next job (state symmetry).
    eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
    out.width = w;
    out.height = h;
    out.ok = true;
}

// ---- renderFrame / close / health ---------------------------------------------

DevelopFrameResult DevelopSession::renderFrame(const DevelopFrameRequest& req) {
    DevelopFrameResult res;
    if (impl_ == nullptr) {
        res.detail = "session not open";
        return res;
    }
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        res.detail = "session closed";
        return res;
    }
    if (!impl_->healthyFlag) {
        res.detail = impl_->failureDetail.empty()
            ? std::string("session unhealthy") : impl_->failureDetail;
        return res;
    }
    // In-flight guard: renderFrame blocks, so ONE well-behaved caller can
    // never trip this — but a second concurrent caller would overwrite
    // the pending fields and corrupt the first job's handoff. Fail it
    // loudly instead (the export engine thread is the one legal caller).
    if (impl_->pendingKind != Impl::JobKind::None) {
        res.detail = "renderFrame re-entered while a frame was in flight";
        return res;
    }
    impl_->pendingKind = Impl::JobKind::Render;
    impl_->pendingReq = &req;
    impl_->pendingRes = &res;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    // [res] was filled by the GL thread while we held the wait; the
    // unique_lock releases on return.
    return res;
}

bool DevelopSession::setEncoderSurface(ANativeWindow* window,
                                       std::string* detail) {
    if (impl_ == nullptr) {
        if (detail != nullptr) *detail = "session not open";
        return false;
    }
    // Ownership transfers at call entry (the M4 setSurface shape): the
    // caller acquired (or nulls) the ANativeWindow; the GL thread releases
    // it on every path. Fast refusals below release HERE on the calling
    // thread instead.
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "session closed";
        return false;
    }
    // A DETACH (null window) always processes — even on a latched-unhealthy
    // session (teardown paths must be able to release the surface). An
    // ATTACH on an unhealthy session is refused: the context is broken and
    // the attach would be a lie.
    if (window != nullptr && !impl_->healthyFlag) {
        ANativeWindow_release(window);
        if (detail != nullptr) {
            *detail = impl_->failureDetail.empty()
                ? std::string("session unhealthy") : impl_->failureDetail;
        }
        return false;
    }
    if (impl_->pendingKind != Impl::JobKind::None) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "setEncoderSurface re-entered while a job was in flight";
        return false;
    }
    impl_->pendingKind = Impl::JobKind::Surface;
    impl_->pendingWindow = window;
    impl_->pendingSurfaceOk = false;
    impl_->pendingSurfaceDetail.clear();
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    const bool ok = impl_->pendingSurfaceOk;
    if (detail != nullptr && !ok && !impl_->pendingSurfaceDetail.empty()) {
        *detail = impl_->pendingSurfaceDetail;
    }
    return ok;
}

DevelopFrameResult DevelopSession::renderFrameToEncoder(
        const DevelopFrameRequest& req, int64_t ptsUs, int rotationDeg) {
    DevelopFrameResult res;
    if (impl_ == nullptr) {
        res.detail = "session not open";
        return res;
    }
    // Fast validate on the CALLING thread (never hand the GL thread a job
    // whose contract is already broken): the fold supports exactly the
    // normalize(deg) set — 0/90/180/270. Mp4Exporter gates first; this is
    // the second lock, honest and named (never a sideways encode).
    const int norm = ((rotationDeg % 360) + 360) % 360;
    if (norm != 0 && norm != 90 && norm != 180 && norm != 270) {
        res.detail = "encoder blit rotation must be 0/90/180/270 (got " +
                     std::to_string(rotationDeg) + ")";
        return res;
    }
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        res.detail = "session closed";
        return res;
    }
    if (!impl_->healthyFlag) {
        res.detail = impl_->failureDetail.empty()
            ? std::string("session unhealthy") : impl_->failureDetail;
        return res;
    }
    if (impl_->pendingKind != Impl::JobKind::None) {
        res.detail = "renderFrameToEncoder re-entered while a job was in flight";
        return res;
    }
    impl_->pendingKind = Impl::JobKind::Encoder;
    impl_->pendingReq = &req;
    impl_->pendingRes = &res;
    impl_->pendingPtsUs = ptsUs;
    impl_->pendingRotationDeg = norm;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    return res;
}

void DevelopSession::close() {
    if (impl_ == nullptr) return;
    std::thread joiner;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->threadRunning) return;  // already closed (idempotent)
        impl_->closeRequested = true;
        impl_->cvRequest.notify_one();
        // Move the thread handle out so we can join WITHOUT the mutex
        // (joining while holding it would deadlock: the loop's lock
        // acquisition would block forever).
        joiner = std::move(impl_->glThread);
        impl_->threadRunning = false;
    }
    if (joiner.joinable()) joiner.join();
}

DevelopSession::~DevelopSession() {
    close();
    // impl_'s destruction now frees the PODs; every GL object was deleted
    // on the GL thread in teardownOnGlThread (glThreadLoop's exit path),
    // and EglSession::release is idempotent with NO_* handles.
}

bool DevelopSession::healthy() const {
    if (impl_ == nullptr) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->healthyFlag;
}

std::string DevelopSession::failureDetail() const {
    // By value (the header's contract): the GL thread may latch a new
    // detail under the mutex while a caller held a returned reference.
    if (impl_ == nullptr) return std::string();
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->failureDetail;
}

void DevelopSession::Impl::teardownOnGlThread() {
    // Called from the GL thread's exit path (glThreadLoop); the reverse of
    // creation. Safe on a partially-initialized session: every handle
    // starts at 0/NO_*.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (scratchFbo != 0) { glDeleteFramebuffers(1, &scratchFbo); scratchFbo = 0; }
    if (fboA != 0) { glDeleteFramebuffers(1, &fboA); fboA = 0; }
    if (fboB != 0) { glDeleteFramebuffers(1, &fboB); fboB = 0; }
    if (fboC != 0) { glDeleteFramebuffers(1, &fboC); fboC = 0; }
    if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
    if (scratchTex != 0) { glDeleteTextures(1, &scratchTex); scratchTex = 0; }
    if (texA != 0) { glDeleteTextures(1, &texA); texA = 0; }
    if (texB != 0) { glDeleteTextures(1, &texB); texB = 0; }
    if (texC != 0) { glDeleteTextures(1, &texC); texC = 0; }
    if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
    if (shadingTex != 0) { glDeleteTextures(1, &shadingTex); shadingTex = 0; }
    if (lutTex != 0) { glDeleteTextures(1, &lutTex); lutTex = 0; }
    if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
    if (outBuf != 0) { glDeleteBuffers(1, &outBuf); outBuf = 0; }
    if (preProgram != 0) { glDeleteProgram(preProgram); preProgram = 0; }
    if (postProgram != 0) { glDeleteProgram(postProgram); postProgram = 0; }
    if (nrChromaHProgram != 0) { glDeleteProgram(nrChromaHProgram); nrChromaHProgram = 0; }
    if (nrChromaVProgram != 0) { glDeleteProgram(nrChromaVProgram); nrChromaVProgram = 0; }
    if (nrLumaProgram != 0) { glDeleteProgram(nrLumaProgram); nrLumaProgram = 0; }
    if (storeProgram != 0) { glDeleteProgram(storeProgram); storeProgram = 0; }
    if (blitProgram != 0) { glDeleteProgram(blitProgram); blitProgram = 0; }
    // The encoder window surface dies BEFORE the EGL session release (it
    // belongs to this display); the pbuffer is current, so destroying the
    // window surface is legal.
    if (encoderSurface != EGL_NO_SURFACE) {
        eglDestroySurface(egl.display, encoderSurface);
        encoderSurface = EGL_NO_SURFACE;
    }
    egl.release();
}

} // namespace gles
