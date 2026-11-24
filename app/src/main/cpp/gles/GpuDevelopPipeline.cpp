// The shared GPU develop pipeline — the implementation of the object
// GpuDevelopPipeline.h documents (the develop chain ONCE, as a reusable
// object both production sessions own one instance of and delegate to).
// This translation unit is Android-only like the sessions (EGL/GLES
// headers exist only in the NDK; the desktop build never compiles it —
// host-side coverage lives in the Kotlin shader-source validator + the
// parity decision rules and their JVM tests).
//
// PROVENANCE (the behavior freeze): the body moved here VERBATIM from the
// two sessions' chain — GlesDevelopSession.cpp's developPass and
// GlesPreviewSession.cpp's inline chain were textually identical from the
// CPU-uniform computation through the TAIL draw (the LUT block
// byte-for-byte), and the moved text keeps the DEVICE-PROVEN harness's
// rules (GlesPipeline.cpp developFragmentParity — fragment parity PASS at
// 1 LSB on the owner's phone): same CPU-computed uniform rules, same
// texture units (CFA GL_TEXTURE0, the chain's pass sources GL_TEXTURE1,
// scratch GL_TEXTURE2, shading GL_TEXTURE3, the vertical passes' guide
// GL_TEXTURE4, the LUT 3D table GL_TEXTURE5), same SSBO binding 0, same
// transpose=GL_TRUE row-major matrix uploads, same per-grade/per-frame
// gating, same texA/texB/texC slot threading. The two sessions' honest
// divergences ride the Config: the develop texture's filter (the preview's
// probed OES_texture_float_linear verdict vs the develop session's
// NEAREST) and the store-scratch pair (the develop session's readback
// path; the preview's stats pack owns its own, session-side). The shading
// upload carries the preview's richer identity-keyed rule for BOTH
// sessions — behavior-identical within the develop session's
// one-clip-per-session contract (see the shading block's comment).
//
// OWNERSHIP (the header's rule, restated for the implementation): an
// instance is created, initialized, drawn and torn down on the OWNING
// session's GL thread with that session's context current; every GL
// object below belongs to that one context. The pipeline has NO thread, NO
// handoff and NO latch — the sessions keep theirs, verbatim. The develop
// session's store/SSBO readback and encoder blit, and the preview
// session's window pass and stats pack, stay SESSION-SIDE (the store
// program + its locations and the withStoreScratch pair are exposed
// through the header's accessors so each session runs its own tail).
//
// Status vocabulary (the mission's honesty rule): this code COMPILES for
// Android but its runtime behavior is DEVICE-TEST-PENDING — the owner's
// covered-grade GPU preview + export are the production proof. The math
// it runs is the math Measure already proved on device.

#include "GpuDevelopPipeline.h"
#include "ChainSlots.h"
#include "NodeParcel.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <utility>

#include <GLES3/gl31.h>
#include <android/log.h>

// Stage 24 LUT (the LUT port, commit (a)): the pipeline parses the .cube
// with core's frozen parser and compiles the managed binding with core's
// frozen compiler — the pipeline NEVER re-implements either; the CPU seam
// (jni_bridge.cpp) owns the same pair through the same headers, so the
// two paths cannot drift in interpretation.
#include <rawedit/Lut.h>
#include <rawedit/Color.h>

namespace gles {
namespace {

// ---- Documented copies (each verbatim from its source, which stays the
// ---- owner of the text; see the per-copy notes). -------------------------

// parseGlesVersion — verbatim from GlesPipeline.cpp (the harness's own
// copy; "OpenGL ES 3.2 ..." -> (3, 2), malformed -> (0, 0): never guess a
// version). Copied because the original lives in that TU's anonymous
// namespace; when GlesPipeline.cpp changes the parse, this copy must
// change with it (the harness and the sessions must agree on the gate).
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
// ---- the CPU-computed uWbManual gate), so the chain still needs no
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
    // RGBA32F + NEAREST: the shader fetches texels and blends them itself (lutSample), so no float filtering
    // extension is needed and the table keeps full precision.
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    const float dummy[32] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                             1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                             1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA32F, 2, 2, 2, 0,
                 GL_RGBA, GL_FLOAT, dummy);
}

// The stage-24 neutral pins (the LUT port, commit (a)): the values the TAIL
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

// Microseconds between two steady_clock points — the span arithmetic for
// the DrawResult's two timing fields (the jni_bridge.cpp elapsedMs
// precedent, at microsecond resolution because the per-frame spans are
// ms-scale; the preview session's own helper, carried verbatim).
inline double elapsedUs(const std::chrono::steady_clock::time_point& a,
                        const std::chrono::steady_clock::time_point& b) {
    return std::chrono::duration<double, std::micro>(b - a).count();
}

} // namespace

// ---- Impl -----------------------------------------------------------------

struct GpuDevelopPipeline::Impl {
    // The owning session's two construction-time choices (the header's
    // Config): the develop texture's filter and whether the allocation
    // also creates the store-scratch pair. Set once at init.
    Config config;
    // The split develop chain's programs (the sharpness/detail port's
    // three-way 20/23 cut on the spatial-NR port's 6/9 cut): pre runs
    // stages 1-6 into an RGBA32F intermediate, the three NR passes sit
    // at the CPU's exact stage 7/8 position between them (gated per
    // grade), post runs the pointwise stages 9-20, the four unsharp
    // passes (gated per grade) run the CPU's stages 21/22 at their
    // exact position, and tail runs stages 23+24+OETF into the develop
    // texture. The fused DEVELOP_FRAGMENT stays the frozen parity
    // harness's own source (GlesPipeline.cpp); the split programs
    // compile the same shared fragments, so the two chains cannot
    // drift. Store (the readback/stats pass) is the twelfth program;
    // the sessions' own tail programs (the develop session's encoder
    // blit, the preview session's window pass) stay session-side.
    // E0b-4b: the encoded-frame pass 1 (compiled in init with the shared vertex shader) and its two
    // integer plane textures (re-created when the source dims or depth change).
    GLuint yuvProgram = 0;
    GLuint yuvYTex = 0, yuvCTex = 0;
    int yuvSrcW = 0, yuvSrcH = 0, yuvBits = 0;
    GLuint preProgram = 0;
    GLuint postProgram = 0;
    GLuint tailProgram = 0;
    GLuint sharpnessHProgram = 0;
    GLuint sharpnessVProgram = 0;
    GLuint detailHProgram = 0;
    GLuint detailVProgram = 0;
    // The gyro warp pass (NOT a develop stage: the CPU runs warpFrame
    // after developCpu, on the linear output). Drawn between the four
    // unsharp passes and the tail, gated per FRAME (req.warpActive) —
    // the caller composes M = Hinv * S on its own thread and folds
    // inactive/identity/degenerate plans into warpActive = false, so
    // the pass's absence is the identity (a resample on an identity map
    // would blur).
    GLuint warpProgram = 0;
    GLuint nrChromaHProgram = 0;
    GLuint nrChromaVProgram = 0;
    GLuint nrLumaProgram = 0;
    GLuint storeProgram = 0;
    // Uniform locations, resolved ONCE at init. The develop set is SPLIT
    // with the programs: the 13 PRE uniforms (uCfa..uHasProfile — the
    // pre-stage set) resolve on preProgram, the 21 POINTWISE uniforms
    // (uExposure..uSaturation) on postProgram, the 13 TAIL uniforms
    // (uTonemap/uOetf plus the stage-24 LUT set) on tailProgram. The
    // NR passes carry their own small sets: uSrc/uSize/uChromaDenoise on
    // the horizontal pass, uSrc/uGuide/uSize/uChromaDenoise on the
    // vertical (uGuide = the ORIGINAL pre-NR image — the CPU's second
    // pass keeps the original luma guide), uSrc/uSize/uNoiseReduction on
    // the luma. The unsharp passes carry the NR passes' own shape:
    // uSrc/uSize/uSharpness (or uDetail) on the H pass, uSrc/uGuide/
    // uSize/uSharpness (or uDetail) on the V (uGuide = the stage's own
    // input — the CPU's tail reads the ORIGINAL luma, not the sharpened
    // one). Store keeps its own pair.
    GLint cfaLoc = -1, sizeLoc = -1, cellLoc = -1, blackLoc = -1, rangeLoc = -1,
          hasShadingLoc = -1, shadingLoc = -1, shadingSizeLoc = -1, hrLoc = -1,
          hrGainLoc = -1, wbLoc = -1, camLoc = -1, hasProfileLoc = -1,
          exposureLoc = -1, highlightsLoc = -1,
          blackPointLoc = -1, whitePointLoc = -1, shadowsLoc = -1,
          midtonesLoc = -1, contrastLoc = -1, wbManualLoc = -1, tempKLoc = -1,
          tintLoc = -1, wheelShadowXLoc = -1, wheelShadowYLoc = -1,
          wheelMidXLoc = -1, wheelMidYLoc = -1, wheelHighXLoc = -1,
          wheelHighYLoc = -1, splitActiveLoc = -1, splitShadowHueLoc = -1,
          splitHighlightHueLoc = -1, splitBalanceLoc = -1, saturationLoc = -1;
    // The tail's tonemap/OETF pair + the stage-24 LUT set (the LUT port)
    // — all on tailProgram, all resolved at init with the same >= 0
    // contract check as the rest. uLutStrength/uLutSize/uLutDomainMin/
    // uLutDomainMax/uLutRole/uLutPassthrough/uLutInTransfer/
    // uLutOutTransfer feed the shader's own role/gamut/transfer contract
    // (the CPU applyManagedLutStage semantics transcribed); uLut is the
    // sampler3D pinned to unit 5 at init (the static binding block
    // below); the two mat3s carry core's compiled binding matrices,
    // uploaded row-major with transpose=GL_TRUE exactly like uCam.
    GLint tonemapLoc = -1, oetfLoc = -1;
    GLint lutStrengthLoc = -1, lutLoc = -1, lutSizeLoc = -1,
          lutDomainMinLoc = -1, lutDomainMaxLoc = -1, lutRoleLoc = -1,
          lutPassthroughLoc = -1, lutInTransferLoc = -1,
          lutOutTransferLoc = -1, lutWorkingToInputLoc = -1,
          lutOutputToWorkingLoc = -1;
    // The unsharp passes' uniform sets (stages 21/22), each on its own
    // program — the NR passes' own per-program shape.
    GLint sharpnessHSrcLoc = -1, sharpnessHSizeLoc = -1, sharpnessHStrengthLoc = -1;
    GLint sharpnessVSrcLoc = -1, sharpnessVGuideLoc = -1, sharpnessVSizeLoc = -1,
          sharpnessVStrengthLoc = -1;
    GLint detailHSrcLoc = -1, detailHSizeLoc = -1, detailHStrengthLoc = -1;
    GLint detailVSrcLoc = -1, detailVGuideLoc = -1, detailVSizeLoc = -1,
          detailVStrengthLoc = -1;
    // The gyro warp pass's own set (uSrc/uSize/uM), on warpProgram —
    // the chain-pass shape. uM uploads row-major with transpose=GL_TRUE
    // (the uCam2rgb idiom), so the shader's uM * vec3(x, y, 1) is the
    // CPU mapPoint's exact row tap.
    GLint warpSrcLoc = -1, warpSizeLoc = -1, warpMLoc = -1;
    // POST's own uSrc/uSize (the chain-intermediate input the NR position
    // feeds) and the NR passes' uniform sets, each on its own program.
    GLint postSrcLoc = -1, postSizeLoc = -1;
    // TAIL's own uSrc/uSize (the chain-intermediate input the unsharp
    // position feeds).
    GLint tailSrcLoc = -1, tailSizeLoc = -1;
    GLint nrChromaHSrcLoc = -1, nrChromaHSizeLoc = -1, nrChromaHStrengthLoc = -1;
    GLint nrChromaVSrcLoc = -1, nrChromaVGuideLoc = -1, nrChromaVSizeLoc = -1,
          nrChromaVStrengthLoc = -1;
    GLint nrLumaSrcLoc = -1, nrLumaSizeLoc = -1, nrLumaStrengthLoc = -1;
    GLint storeSrcLoc = -1, storeSizeLoc = -1;
    GLuint cfaTex = 0, shadingTex = 0, developTex = 0, scratchTex = 0;
    // The stage-24 LUT 3D texture (RGBA16F, unit 5 — the LUT port, commit
    // (a)). Allocated ONCE per LUT revision, never per frame: [lutTex]
    // holds the CURRENT table (the real parsed cube, or the 2x2x2 dummy
    // when no LUT is bound — the sampler uniform is always set, keeping
    // the program's uniform set complete either way, the shading dummy's
    // own rule), and the cache fields below decide whether an upload is
    // needed.
    GLuint lutTex = 0;
    // The LUT revision cache: [lutUploadedPath] is the path whose table is
    // CURRENTLY in [lutTex] ("" = the dummy is bound); [lutParsedPath]/
    // [lutParsed] hold the last parse (kept so a strength step across 0
    // does not re-read the file); [lutBinding] the last compiled binding
    // (keyed by the five ordinals in [lutBindingKey]); [lutBindingOk]
    // whether a binding was compiled at all (role < 0 = legacy needs no
    // binding). The owning session's own GL-thread cache — the JNI
    // bridge's thread_local caches belong to engine threads and cannot be
    // shared here.
    std::string lutUploadedPath;
    std::string lutParsedPath;
    std::unique_ptr<rawedit::Lut> lutParsed;
    bool lutBindingOk = false;
    rawedit::CompiledLutBinding lutBinding{};
    int lutBindingKey[5] = {-2, -2, -2, -2, -2};  // role,in,out gamut,in,out transfer
    // The chain's RGBA32F ping-pong intermediates (the spatial-NR port,
    // threaded through the unsharp section): A = the PRE pass's output
    // (and the chroma vertical pass's ORIGINAL guide), B/C = the NR
    // passes' scratch pair; the four unsharp passes and the TAIL pass
    // ride the same three slots (the ping-pong threading in draw
    // — no draw reads and writes the same texture). TAIL always writes
    // developTex (the owning session's post-chain passes read THAT
    // texture — writing the tail's output to an intermediate the chain
    // also reads would be a framebuffer feedback loop).
    GLuint texA = 0, texB = 0, texC = 0;
    GLuint developFbo = 0, scratchFbo = 0;
    GLuint fboA = 0, fboB = 0, fboC = 0;
    // WW8 per-pass probe one-shot (see armPassProbe in the header).
    bool passProbeArmed = false;
    GLuint outBuf = 0;
    // Immutable storage currently allocated (0 = none yet). Per-frame work
    // touches none of these fields — glTexSubImage2D uploads data into the
    // existing storage, and the SSBO keeps its size while w/h hold.
    // [shadingUploaded] is the preview session's identity key's third leg
    // (the map's byte size — a same-dims swap with a different row count
    // must re-upload).
    uint32_t cfaW = 0, cfaH = 0;
    uint32_t shadingW = 0, shadingH = 0;
    size_t shadingUploaded = 0;
    uint64_t outBytes = 0;

    // --- color model v2 F1-5: the NODES pass + the TAIL's DRT variant ---
    // Added through initNodes() (the ADDITIVE door — none of the pipeline's
    // original init changes); both stay 0/unresolved until then, and the
    // pre-F1-5 request shape never touches them.
    GLuint nodesProgram = 0;
    GLuint tailDrtProgram = 0;
    // The NODES pass's own uniforms (all resolved at initNodes on
    // nodesProgram). The matrix pair uploads row-major with
    // transpose=GL_TRUE — the uCam2rgb idiom.
    GLint nodesSrcLoc = -1, nodesSizeLoc = -1, nodesCountLoc = -1,
          nodesOutputModeLoc = -1;
    GLint nodesM709ToDwgLoc = -1, nodesMDwgTo709Loc = -1,
          nodesCurvesLoc = -1;
    // The per-node arrays (NodeParcel.h's pack, one slot per node).
    GLint nodesPrimaryLoc = -1, nodesGainLoc = -1, nodesOffsetLoc = -1,
          nodesGammaExpLoc = -1, nodesYSideLoc = -1,
          nodesContrastPivotLoc = -1, nodesWbLoc = -1, nodesHueMatLoc = -1,
          nodesFlagsLoc = -1;
    // The node-LUT sampler set (units 7..10) + their per-slot shape /
    // binding uniforms (the stage-24 LUT port's own contract, per slot).
    GLint nodesLutLoc = -1, nodesLutShapeLoc = -1, nodesLutShapeHiLoc = -1,
          nodesLutWorkingLoc = -1, nodesLutOutputLoc = -1;
    // The DRT TAIL's own uSrc/uSize (the variant reads the NODES pass's
    // DWG-linear output) + the resolved-preset uniform block's locations,
    // all resolved at initNodes on tailDrtProgram.
    GLint drtSrcLoc = -1, drtSizeLoc = -1;
    GLint drtEotfLoc = -1, drtHdrLoc = -1, drtCwpLoc = -1,
          drtNormLoc = -1, drtNormHdrLoc = -1;
    GLint drtTnLoc = -1, drtRsLoc = -1, drtToeLoc = -1, drtBrlRgbLoc = -1,
          drtBrlpRgbLoc = -1, drtLconLoc = -1, drtHconLoc = -1, drtTsLoc = -1,
          drtHueRLoc = -1, drtHueGLoc = -1, drtHueBLoc = -1, drtHueCLoc = -1,
          drtHueMLoc = -1, drtHueYLoc = -1, drtConf0Loc = -1, drtConf1Loc = -1,
          drtConf2Loc = -1, drtPtLoc = -1, drtPt2Loc = -1, drtPt3Loc = -1,
          drtPt4Loc = -1;
    // initNodes' recorded limits (the inventory §1.3's missing-query fix).
    NodesLimits limits;
    // The curve atlas (unit 6): 512 x 24 GL_R32F, texelFetch-only (no
    // filter state matters; NEAREST pinned anyway per the Adreno 32F
    // rule). Rows past nodeCount*3 stay the zero-delta identity; the
    // atlas is (re)filled only when the chain revision changes.
    GLuint nodesAtlasTex = 0;
    uint64_t nodesChainKey = 0;  // the revision key (req.nodeChainHash)
    bool nodesChainValid = false;  // the uniform cache/reflect the key
    // UP TO 4 node-LUT tables (units 7..10), the revision-cached upload —
    // the stage-24 LUT port's own pattern, one table per parcel lutIndex.
    GLuint nodeLutTex[4] = {0, 0, 0, 0};
    uint64_t nodeLutKey[4] = {0, 0, 0, 0};  // hash of each slot's content
    bool nodesInitialized = false;
    // The revision cache's CPU-side state (rebuilt when nodeChainHash
    // changes; the packs feed the per-draw uniform uploads):
    // [nodesPacks] the compiled packs (core's compileNode — the SAME
    // values the CPU reference and the Kotlin mirror produce);
    // [nodesAtlasRows] the 512x24 atlas as one flat row-major copy (the
    // compiled packs' composite curves; the neutral rows beyond the chain
    // stay the zero-delta identity);
    // [nodesNeutralPack]/[nodesNeutralNode] the inert surplus slots beyond
    // the chain (the shader's uniform arrays stay complete).
    std::vector<rawedit::CompiledNode> nodesPacks;
    std::vector<float> nodesAtlasRows;
    rawedit::CompiledNode nodesNeutralPack;
    rawedit::GradeNode nodesNeutralNode;

    // --- GL-thread methods (called only on the owning session's GL thread) --
    bool init(const std::string& vertexSource,
              const std::string& vertex310Source,
              const std::string& preFragmentSource,
              const std::string& postFragmentSource,
              const std::string& tailFragmentSource,
              const std::string& sharpnessHSource,
              const std::string& sharpnessVSource,
              const std::string& detailHSource,
              const std::string& detailVSource,
              const std::string& warpFragmentSource,
              const std::string& nrChromaHSource,
              const std::string& nrChromaVSource,
              const std::string& nrLumaSource,
              const std::string& storeFragmentSource,
              const Config& config,
              std::string* detail);
    // The whole chain (input contract, CPU uniforms, allocation, upload,
    // the pre -> [NR] -> pointwise -> [unsharp] -> [warp] -> [nodes] ->
    // tail draws into developFbo) — the body both sessions' chain bodies
    // were, plus the F1-5 NODES/DRT pair (only when the request carries
    // them and initNodes ran).
    DrawResult draw(const DevelopFrameRequest& req);
    // The F1-5 ADDITIVE door: compiles the NODES + DRT programs, resolves
    // their uniforms, allocates the atlas + the dummy node-LUT tables and
    // reads the two device limits (the inventory §1.3's missing query).
    bool initNodes(const std::string& nodesSource, const std::string& vertexSource,
                   const std::string& tailDrtSource, NodesLimits* limits,
                   std::string* detail);
    // The drtUniforms block's per-draw fold (the flat float block's one
    // consumer; the location table is initNodes' own).
    void drtFoldUniforms(const float* block);
    void teardown();
};

// ---- init -------------------------------------------------------------------

bool GpuDevelopPipeline::Impl::init(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& preFragmentSource,
        const std::string& postFragmentSource,
        const std::string& tailFragmentSource,
        const std::string& sharpnessHSource,
        const std::string& sharpnessVSource,
        const std::string& detailHSource,
        const std::string& detailVSource,
        const std::string& warpFragmentSource,
        const std::string& nrChromaHSource,
        const std::string& nrChromaVSource,
        const std::string& nrLumaSource,
        const std::string& storeFragmentSource,
        const Config& config,
        std::string* detail) {
    this->config = config;

    // --- capability gates (THIS context, not a probe's throwaway one) --------
    // STORE_FRAGMENT needs ES 3.1 (fragment-shader SSBO access); the
    // develop chain's RGBA32F intermediates need EXT_color_buffer_float.
    // Re-read in the OWNING session's context, which the session opened
    // before calling init (egl.open stays session-side) — never trusted
    // from a probe's throwaway one.
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
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
    // The develop chain is the SPLIT of the fused DEVELOP_FRAGMENT at the
    // CPU's stage 6/9 and 20/23 boundaries (the spatial-NR port's shape,
    // extended): pre runs stages 1-6, the three NR passes sit at the CPU's
    // exact stage 7/8 position, the pointwise post runs 9-20, the four
    // unsharp passes sit at the CPU's exact stage 21/22 position (sharpness
    // then detail, each an H+V pair riding the NR mission's multi-pass
    // infrastructure), and the tail runs 23+24+OETF. The split recomposes
    // the fused text BYTE-IDENTICALLY (the host-checked invariant), so the
    // chains cannot drift.
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader preFragShader;
    preFragShader.id = compileShader(GL_FRAGMENT_SHADER, preFragmentSource,
                                     "develop-pre-fragment", detail);
    if (preFragShader.id == 0) return false;
    preProgram = linkProgram(vertShader.id, preFragShader.id, "develop-pre", detail);
    if (preProgram == 0) return false;
    {
        GlShader yuvFragShader;
        yuvFragShader.id = compileShader(GL_FRAGMENT_SHADER, encodedYuvFragmentSource(),
                                         "encoded-yuv-fragment", detail);
        if (yuvFragShader.id == 0) return false;
        yuvProgram = linkProgram(vertShader.id, yuvFragShader.id, "encoded-yuv", detail);
        if (yuvProgram == 0) return false;
    }
    GlShader postFragShader;
    postFragShader.id = compileShader(GL_FRAGMENT_SHADER, postFragmentSource,
                                      "develop-post-fragment", detail);
    if (postFragShader.id == 0) return false;
    postProgram = linkProgram(vertShader.id, postFragShader.id, "develop-post", detail);
    if (postProgram == 0) return false;
    GlShader tailFragShader;
    tailFragShader.id = compileShader(GL_FRAGMENT_SHADER, tailFragmentSource,
                                     "develop-tail-fragment", detail);
    if (tailFragShader.id == 0) return false;
    tailProgram = linkProgram(vertShader.id, tailFragShader.id, "develop-tail", detail);
    if (tailProgram == 0) return false;
    // The four unsharp passes (stages 21/22 — the CPU's unsharpLumaImpl
    // verbatim as H+V pairs): sharpness (radius 1.5) then detail (radius
    // 0.75), each split so the vertical pass can read the H pass's blurred
    // luma as uSrc while keeping the STAGE'S INPUT (the original
    // pre-sharpen RGB) as the uGuide it takes luma from — the CPU's
    // two-buffer shape.
    GlShader sharpnessHFragShader;
    sharpnessHFragShader.id = compileShader(GL_FRAGMENT_SHADER, sharpnessHSource,
                                            "sharpness-h-fragment", detail);
    if (sharpnessHFragShader.id == 0) return false;
    sharpnessHProgram = linkProgram(vertShader.id, sharpnessHFragShader.id,
                                   "sharpness-h", detail);
    if (sharpnessHProgram == 0) return false;
    GlShader sharpnessVFragShader;
    sharpnessVFragShader.id = compileShader(GL_FRAGMENT_SHADER, sharpnessVSource,
                                            "sharpness-v-fragment", detail);
    if (sharpnessVFragShader.id == 0) return false;
    sharpnessVProgram = linkProgram(vertShader.id, sharpnessVFragShader.id,
                                   "sharpness-v", detail);
    if (sharpnessVProgram == 0) return false;
    GlShader detailHFragShader;
    detailHFragShader.id = compileShader(GL_FRAGMENT_SHADER, detailHSource,
                                        "detail-h-fragment", detail);
    if (detailHFragShader.id == 0) return false;
    detailHProgram = linkProgram(vertShader.id, detailHFragShader.id,
                                 "detail-h", detail);
    if (detailHProgram == 0) return false;
    GlShader detailVFragShader;
    detailVFragShader.id = compileShader(GL_FRAGMENT_SHADER, detailVSource,
                                         "detail-v-fragment", detail);
    if (detailVFragShader.id == 0) return false;
    detailVProgram = linkProgram(vertShader.id, detailVFragShader.id,
                                 "detail-v", detail);
    if (detailVProgram == 0) return false;
    // The gyro warp pass (NOT a develop stage — the CPU runs warpFrame
    // after developCpu, on the linear output). Drawn between the unsharp
    // passes and the tail, gated per FRAME at the session level.
    GlShader warpFragShader;
    warpFragShader.id = compileShader(GL_FRAGMENT_SHADER, warpFragmentSource,
                                      "warp-fragment", detail);
    if (warpFragShader.id == 0) return false;
    warpProgram = linkProgram(vertShader.id, warpFragShader.id, "warp", detail);
    if (warpProgram == 0) return false;
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
    // Program ownership is already in the Impl (linkProgram's result is
    // assigned straight into the member); the GlShader guards deleting the
    // SHADER objects at scope exit is correct OpenGL — an attached shader
    // stays flagged-alive until its program dies.

    // --- uniform locations, resolved ONCE ----------------------------------
    // The develop set is SPLIT with the programs: the 13 PRE uniforms
    // (uCfa..uHasProfile) resolve on preProgram, the 21 POINTWISE uniforms
    // (uExposure..uSaturation) on postProgram, the 13 TAIL uniforms
    // (uTonemap/uOetf plus the stage-24 LUT set) on tailProgram, and the
    // four unsharp sets on their own programs. Every lookup joins the same
    // >= 0 contract check — a program compiled without its uniforms is a
    // shader-contract break, not a runtime condition.
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
    if (postSrcLoc < 0 || postSizeLoc < 0 || exposureLoc < 0 ||
        highlightsLoc < 0 ||
        blackPointLoc < 0 || whitePointLoc < 0 || shadowsLoc < 0 ||
        midtonesLoc < 0 || contrastLoc < 0 || wbManualLoc < 0 ||
        tempKLoc < 0 || tintLoc < 0 || wheelShadowXLoc < 0 ||
        wheelShadowYLoc < 0 || wheelMidXLoc < 0 || wheelMidYLoc < 0 ||
        wheelHighXLoc < 0 || wheelHighYLoc < 0 || splitActiveLoc < 0 ||
        splitShadowHueLoc < 0 || splitHighlightHueLoc < 0 ||
        splitBalanceLoc < 0 || saturationLoc < 0) {
        *detail = "shader-contract: missing a develop-post uniform";
        return false;
    }
    // TAIL's own uSrc/uSize (the chain-intermediate input the unsharp
    // section leaves the image in) + the tonemap/OETF pair and the
    // stage-24 LUT set (the LUT port) — all on tailProgram.
    tailSrcLoc = glGetUniformLocation(tailProgram, "uSrc");
    tailSizeLoc = glGetUniformLocation(tailProgram, "uSize");
    tonemapLoc = glGetUniformLocation(tailProgram, "uTonemap");
    oetfLoc = glGetUniformLocation(tailProgram, "uOetf");
    lutStrengthLoc = glGetUniformLocation(tailProgram, "uLutStrength");
    lutLoc = glGetUniformLocation(tailProgram, "uLut");
    lutSizeLoc = glGetUniformLocation(tailProgram, "uLutSize");
    lutDomainMinLoc = glGetUniformLocation(tailProgram, "uLutDomainMin");
    lutDomainMaxLoc = glGetUniformLocation(tailProgram, "uLutDomainMax");
    lutRoleLoc = glGetUniformLocation(tailProgram, "uLutRole");
    lutPassthroughLoc = glGetUniformLocation(tailProgram, "uLutPassthrough");
    lutInTransferLoc = glGetUniformLocation(tailProgram, "uLutInTransfer");
    lutOutTransferLoc = glGetUniformLocation(tailProgram, "uLutOutTransfer");
    lutWorkingToInputLoc = glGetUniformLocation(tailProgram, "uLutWorkingToInput");
    lutOutputToWorkingLoc = glGetUniformLocation(tailProgram, "uLutOutputToWorking");
    if (tailSrcLoc < 0 || tailSizeLoc < 0 || tonemapLoc < 0 || oetfLoc < 0 ||
        lutStrengthLoc < 0 || lutLoc < 0 || lutSizeLoc < 0 ||
        lutDomainMinLoc < 0 || lutDomainMaxLoc < 0 || lutRoleLoc < 0 ||
        lutPassthroughLoc < 0 || lutInTransferLoc < 0 ||
        lutOutTransferLoc < 0 || lutWorkingToInputLoc < 0 ||
        lutOutputToWorkingLoc < 0) {
        *detail = "shader-contract: missing a develop-tail uniform";
        return false;
    }
    // The four unsharp passes' sets (stages 21/22): uSrc/uSize/uSharpness
    // on the horizontal passes, uSrc/uGuide/uSize/uSharpness on the
    // vertical (uGuide = the STAGE'S INPUT — the original pre-sharpen
    // RGB the CPU's second pass keeps taking luma from), the same shape
    // for detail's pair.
    sharpnessHSrcLoc = glGetUniformLocation(sharpnessHProgram, "uSrc");
    sharpnessHSizeLoc = glGetUniformLocation(sharpnessHProgram, "uSize");
    sharpnessHStrengthLoc = glGetUniformLocation(sharpnessHProgram, "uSharpness");
    if (sharpnessHSrcLoc < 0 || sharpnessHSizeLoc < 0 ||
        sharpnessHStrengthLoc < 0) {
        *detail = "shader-contract: missing a sharpness-h uniform";
        return false;
    }
    sharpnessVSrcLoc = glGetUniformLocation(sharpnessVProgram, "uSrc");
    sharpnessVGuideLoc = glGetUniformLocation(sharpnessVProgram, "uGuide");
    sharpnessVSizeLoc = glGetUniformLocation(sharpnessVProgram, "uSize");
    sharpnessVStrengthLoc = glGetUniformLocation(sharpnessVProgram, "uSharpness");
    if (sharpnessVSrcLoc < 0 || sharpnessVGuideLoc < 0 ||
        sharpnessVSizeLoc < 0 || sharpnessVStrengthLoc < 0) {
        *detail = "shader-contract: missing a sharpness-v uniform";
        return false;
    }
    detailHSrcLoc = glGetUniformLocation(detailHProgram, "uSrc");
    detailHSizeLoc = glGetUniformLocation(detailHProgram, "uSize");
    detailHStrengthLoc = glGetUniformLocation(detailHProgram, "uDetail");
    if (detailHSrcLoc < 0 || detailHSizeLoc < 0 || detailHStrengthLoc < 0) {
        *detail = "shader-contract: missing a detail-h uniform";
        return false;
    }
    detailVSrcLoc = glGetUniformLocation(detailVProgram, "uSrc");
    detailVGuideLoc = glGetUniformLocation(detailVProgram, "uGuide");
    detailVSizeLoc = glGetUniformLocation(detailVProgram, "uSize");
    detailVStrengthLoc = glGetUniformLocation(detailVProgram, "uDetail");
    if (detailVSrcLoc < 0 || detailVGuideLoc < 0 || detailVSizeLoc < 0 ||
        detailVStrengthLoc < 0) {
        *detail = "shader-contract: missing a detail-v uniform";
        return false;
    }
    // The gyro warp pass's own set: uSrc/uSize/uM (the composed map,
    // uploaded row-major with transpose=GL_TRUE at draw time).
    warpSrcLoc = glGetUniformLocation(warpProgram, "uSrc");
    warpSizeLoc = glGetUniformLocation(warpProgram, "uSize");
    warpMLoc = glGetUniformLocation(warpProgram, "uM");
    if (warpSrcLoc < 0 || warpSizeLoc < 0 || warpMLoc < 0) {
        *detail = "shader-contract: missing a warp uniform";
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

    // --- static texture-unit bindings (the unit assignment never changes) --
    // Unit map: 0 = CFA, 1 = the chain's pass-source texture (rebound per
    // draw; also the store draw's source), 2 = the store pass's scratch,
    // 3 = shading, 4 = the vertical passes' guide (texA for NR, the stage's
    // input for the unsharp passes — a unit nothing else samples, so the
    // guide survives the per-draw rebinds on unit 1 untouched), 5 = the
    // stage-24 LUT 3D texture (the LUT port, commit (a); bound once here —
    // the upload block in draw swaps the texture OBJECT on that unit per
    // LUT revision, the unit assignment itself never moves).
    glUseProgram(preProgram);
    glUniform1i(cfaLoc, 0);      // CFA: GL_TEXTURE0
    glUniform1i(shadingLoc, 3);  // shading: GL_TEXTURE3
    glUseProgram(postProgram);
    glUniform1i(postSrcLoc, 1); // post source: GL_TEXTURE1 (the chain's
                                // intermediate, rebound per frame)
    glUseProgram(tailProgram);
    glUniform1i(tailSrcLoc, 1); // tail source: GL_TEXTURE1 (the chain's
                                // final intermediate, rebound per frame)
    glUniform1i(lutLoc, 5);     // LUT 3D texture: GL_TEXTURE5 (pinned at
                                // init — a statically-declared sampler3D
                                // whose unit is never set would default to
                                // 0 and collide with uCfa, the harness's
                                // own lutDummy comment)
    glUseProgram(sharpnessHProgram);
    glUniform1i(sharpnessHSrcLoc, 1);  // unsharp sources: GL_TEXTURE1
    glUseProgram(sharpnessVProgram);   // (rebound per draw along the chain)
    glUniform1i(sharpnessVSrcLoc, 1);
    glUniform1i(sharpnessVGuideLoc, 4);  // the stage's input guide: unit 4
    glUseProgram(detailHProgram);
    glUniform1i(detailHSrcLoc, 1);
    glUseProgram(detailVProgram);
    glUniform1i(detailVSrcLoc, 1);
    glUniform1i(detailVGuideLoc, 4);  // the stage's input guide: unit 4
    glUseProgram(warpProgram);
    glUniform1i(warpSrcLoc, 1);  // warp source: GL_TEXTURE1 (the chain's
                                 // live intermediate, rebound per draw)
    glUseProgram(nrChromaHProgram);
    glUniform1i(nrChromaHSrcLoc, 1);  // NR sources: GL_TEXTURE1 (rebound
    glUseProgram(nrChromaVProgram);   // per draw along the chain)
    glUniform1i(nrChromaVSrcLoc, 1);
    glUniform1i(nrChromaVGuideLoc, 4);  // the ORIGINAL pre-NR guide: unit 4
    glUseProgram(nrLumaProgram);
    glUniform1i(nrLumaSrcLoc, 1);
    glUseProgram(storeProgram);
    glUniform1i(storeSrcLoc, 1); // store source: GL_TEXTURE1
    glUseProgram(0);
    return true;
}

// ---- initNodes (color model v2 F1-5 — the ADDITIVE door) ------------------

bool GpuDevelopPipeline::Impl::initNodes(const std::string& nodesSource,
                                         const std::string& vertexSource,
                                         const std::string& tailDrtSource,
                                         NodesLimits* limits,
                                         std::string* detail) {
    // Same GL-thread + context contract as init(); called once by the
    // sessions' F1-6 binding AFTER init(). Both programs compile from the
    // Kotlin single-source-of-truth texts.
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader nodesFrag;
    nodesFrag.id = compileShader(GL_FRAGMENT_SHADER, nodesSource,
                                 "nodes-fragment", detail);
    if (nodesFrag.id == 0) return false;
    GLuint nodesNew = linkProgram(vertShader.id, nodesFrag.id, "nodes", detail);
    if (nodesNew == 0) return false;
    GlShader drtFrag;
    drtFrag.id = compileShader(GL_FRAGMENT_SHADER, tailDrtSource,
                               "tail-drt-fragment", detail);
    if (drtFrag.id == 0) return false;
    GLuint drtNew = linkProgram(vertShader.id, drtFrag.id, "tail-drt", detail);
    if (drtNew == 0) return false;
    // Swap only after BOTH compiled (a partial init keeps the old programs;
    // every delete below is guarded on the old handles).
    if (nodesProgram != 0) glDeleteProgram(nodesProgram);
    if (tailDrtProgram != 0) glDeleteProgram(tailDrtProgram);
    nodesProgram = nodesNew;
    tailDrtProgram = drtNew;

    // The uniform locations, resolved with the SAME >= 0 contract as the
    // chain's sets — a NODES program without its uniforms is a
    // shader-contract break, not a runtime condition.
    const auto resolve = [&](GLuint prog, const char* name) {
        return glGetUniformLocation(prog, name);
    };
    nodesSrcLoc = resolve(nodesProgram, "uSrc");
    nodesSizeLoc = resolve(nodesProgram, "uSize");
    nodesCountLoc = resolve(nodesProgram, "uNodesCount");
    nodesOutputModeLoc = resolve(nodesProgram, "uNodesOutputMode");
    nodesM709ToDwgLoc = resolve(nodesProgram, "uM709ToDwg");
    nodesMDwgTo709Loc = resolve(nodesProgram, "uMDwgTo709");
    nodesCurvesLoc = resolve(nodesProgram, "uNodeCurves");
    nodesPrimaryLoc = resolve(nodesProgram, "uNodePrimary");
    nodesGainLoc = resolve(nodesProgram, "uNodeGain");
    nodesOffsetLoc = resolve(nodesProgram, "uNodeOffset");
    nodesGammaExpLoc = resolve(nodesProgram, "uNodeGammaExp");
    nodesYSideLoc = resolve(nodesProgram, "uNodeYSide");
    nodesContrastPivotLoc = resolve(nodesProgram, "uNodeContrastPivot");
    nodesWbLoc = resolve(nodesProgram, "uNodeWb");
    nodesHueMatLoc = resolve(nodesProgram, "uNodeHueMat");
    nodesFlagsLoc = resolve(nodesProgram, "uNodeFlags");
    nodesLutLoc = resolve(nodesProgram, "uNodeLut");
    nodesLutShapeLoc = resolve(nodesProgram, "uNodeLutShape");
    nodesLutShapeHiLoc = resolve(nodesProgram, "uNodeLutShapeHi");
    nodesLutWorkingLoc = resolve(nodesProgram, "uNodeLutWorkingToInput");
    nodesLutOutputLoc = resolve(nodesProgram, "uNodeLutOutputToWorking");
    if (nodesSrcLoc < 0 || nodesSizeLoc < 0 || nodesCountLoc < 0 ||
        nodesOutputModeLoc < 0 || nodesM709ToDwgLoc < 0 ||
        nodesMDwgTo709Loc < 0 || nodesCurvesLoc < 0 || nodesPrimaryLoc < 0 ||
        nodesGainLoc < 0 || nodesOffsetLoc < 0 || nodesGammaExpLoc < 0 ||
        nodesYSideLoc < 0 || nodesContrastPivotLoc < 0 || nodesWbLoc < 0 ||
        nodesHueMatLoc < 0 || nodesFlagsLoc < 0 || nodesLutLoc < 0 ||
        nodesLutShapeLoc < 0 || nodesLutShapeHiLoc < 0 ||
        nodesLutWorkingLoc < 0 || nodesLutOutputLoc < 0) {
        *detail = "shader-contract: missing a nodes uniform";
        return false;
    }
    drtSrcLoc = resolve(tailDrtProgram, "uSrc");
    drtSizeLoc = resolve(tailDrtProgram, "uSize");
    drtEotfLoc = resolve(tailDrtProgram, "uDrtEotf");
    drtHdrLoc = resolve(tailDrtProgram, "uDrtHdr");
    drtCwpLoc = resolve(tailDrtProgram, "uDrtCwp");
    drtNormLoc = resolve(tailDrtProgram, "uNormScalar");
    drtNormHdrLoc = resolve(tailDrtProgram, "uNormScalarHdr");
    drtTnLoc = resolve(tailDrtProgram, "uDrtTn");
    drtRsLoc = resolve(tailDrtProgram, "uDrtRs");
    drtToeLoc = resolve(tailDrtProgram, "uDrtToe");
    drtBrlRgbLoc = resolve(tailDrtProgram, "uDrtBrlRgb");
    drtBrlpRgbLoc = resolve(tailDrtProgram, "uDrtBrlpRgb");
    drtLconLoc = resolve(tailDrtProgram, "uDrtLcon");
    drtHconLoc = resolve(tailDrtProgram, "uDrtHcon");
    drtTsLoc = resolve(tailDrtProgram, "uDrtTs");
    drtHueRLoc = resolve(tailDrtProgram, "uDrtHueR");
    drtHueGLoc = resolve(tailDrtProgram, "uDrtHueG");
    drtHueBLoc = resolve(tailDrtProgram, "uDrtHueB");
    drtHueCLoc = resolve(tailDrtProgram, "uDrtHueC");
    drtHueMLoc = resolve(tailDrtProgram, "uDrtHueM");
    drtHueYLoc = resolve(tailDrtProgram, "uDrtHueY");
    drtConf0Loc = resolve(tailDrtProgram, "uDrtConf0");
    drtConf1Loc = resolve(tailDrtProgram, "uDrtConf1");
    drtConf2Loc = resolve(tailDrtProgram, "uDrtConf2");
    drtPtLoc = resolve(tailDrtProgram, "uDrtPt");
    drtPt2Loc = resolve(tailDrtProgram, "uDrtPt2");
    drtPt3Loc = resolve(tailDrtProgram, "uDrtPt3");
    drtPt4Loc = resolve(tailDrtProgram, "uDrtPt4");
    if (drtSrcLoc < 0 || drtSizeLoc < 0 || drtEotfLoc < 0 || drtHdrLoc < 0 ||
        drtCwpLoc < 0 || drtNormLoc < 0 || drtNormHdrLoc < 0 || drtTnLoc < 0 ||
        drtRsLoc < 0 || drtToeLoc < 0 || drtBrlRgbLoc < 0 ||
        drtBrlpRgbLoc < 0 || drtLconLoc < 0 || drtHconLoc < 0 ||
        drtTsLoc < 0 || drtHueRLoc < 0 || drtHueGLoc < 0 || drtHueBLoc < 0 ||
        drtHueCLoc < 0 || drtHueMLoc < 0 || drtHueYLoc < 0 ||
        drtConf0Loc < 0 || drtConf1Loc < 0 || drtConf2Loc < 0 ||
        drtPtLoc < 0 || drtPt2Loc < 0 || drtPt3Loc < 0 || drtPt4Loc < 0) {
        *detail = "shader-contract: missing a tail-drt uniform";
        return false;
    }

    // The static unit bindings: the NODES source rides the chain's unit 1;
    // the atlas pins unit 6; the node-LUT set pins units 7..10 (glUniform1i
    // per ARRAY ELEMENT — a sampler ARRAY takes one unit per element).
    glUseProgram(nodesProgram);
    glUniform1i(nodesSrcLoc, 1);
    glUniform1i(nodesCurvesLoc, 6);
    for (int s = 0; s < 4; ++s)
        glUniform1i(nodesLutLoc + s, 7 + s);
    glUseProgram(tailDrtProgram);
    glUniform1i(drtSrcLoc, 1);
    glUseProgram(0);

    // The atlas: 512 x 24 GL_R32F, texelFetch-only (NEAREST pinned — the
    // Adreno 32F rule; no filter path exists in the shader). Storage is
    // allocated ONCE; the CONTENT re-uploads per chain revision (the
    // shading map's identity-keyed pattern: the per-frame work is a
    // glTexSubImage2D only when the request's chain hash changed).
    if (nodesAtlasTex == 0) glGenTextures(1, &nodesAtlasTex);
    glActiveTexture(GL_TEXTURE6);
    glBindTexture(GL_TEXTURE_2D, nodesAtlasTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, 512, 24);
    if (glErrorAfter("glTexStorage2D(R32F nodes atlas)", detail) != GL_NO_ERROR)
        return false;
    // The zero-delta fill: nodeCurveX(i) - x_i = 0 (the identity composite
    // the CPU's own neutral-row claim pins); rows past the live chain stay
    // this forever.
    {
        std::vector<float> zero(512 * 24, 0.0f);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 512, 24, GL_RED, GL_FLOAT,
                        zero.data());
        if (glErrorAfter("glTexSubImage2D(R32F nodes atlas)", detail) != GL_NO_ERROR)
            return false;
    }
    // The four dummy node-LUT tables (units 7..10): a 2x2x2 all-1.0
    // RGBA16F table each — the sampler-completeness rule (bindLutDummyTex's
    // own), never sampled while a slot's uNodeFlags hasLut is 0.
    for (int s = 0; s < 4; ++s) {
        if (nodeLutTex[s] == 0) glGenTextures(1, &nodeLutTex[s]);
        glActiveTexture(GL_TEXTURE7 + s);
        glBindTexture(GL_TEXTURE_3D, nodeLutTex[s]);
        bindLutDummyTex(nodeLutTex[s]);  // binds GL_TEXTURE5 — rebind below
        glActiveTexture(GL_TEXTURE7 + s);
        glBindTexture(GL_TEXTURE_3D, nodeLutTex[s]);
        if (glErrorAfter("glTexImage3D(RGBA16F node-lut dummy)", detail) != GL_NO_ERROR)
            return false;
        nodeLutKey[s] = 0;  // 0 = the dummy
    }
    // Reset unit 5's binding (bindLutDummyTex bound GL_TEXTURE5; the chain's
    // stage-24 LUT keeps that unit — the real table rebinds at draw when
    // its own revision cache says so, the existing lutTex block).
    glActiveTexture(GL_TEXTURE5);
    glBindTexture(GL_TEXTURE_3D, lutTex);
    // Invalidate the chain cache: the first nodesActive draw rebuilds.
    nodesChainKey = 0;
    nodesChainValid = false;
    nodesInitialized = nodesProgram != 0 && tailDrtProgram != 0;

    // The two device limits — the inventory §1.3's missing query, answered
    // in THIS context (the same re-read rule as the capability gates):
    //   - GL_MAX_FRAGMENT_UNIFORM_VECTORS bounds the NODES pass's
    //     ~110-115-vec4 set (§2.6);
    //   - GL_MAX_TEXTURE_IMAGE_UNITS bounds the worst case 1 + 1 + 4 + the
    //     chain's own unit map (§2.5).
    // The values are RECORDED, not gated on: the ES 3.0 spec minimum is
    // 224 vectors / 16 units; the owner's device measured in the preview
    // probe decides whether the §4.2 narrowings are ever needed (K8).
    GLint fragUniforms = 0;
    glGetIntegerv(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &fragUniforms);
    GLint units = 0;
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &units);
    while (glGetError() != GL_NO_ERROR) {}  // the queries are reads; log-only
    limits->fragmentUniformVectors = fragUniforms;
    limits->textureImageUnits = units;
    this->limits = *limits;
    __android_log_print(ANDROID_LOG_INFO, "ritim-trace",
        "nodes init: ok=%d maxFragmentUniformVectors=%d maxTextureImageUnits=%d",
        nodesInitialized ? 1 : 0, fragUniforms, units);
    if (!nodesInitialized) {
        limits->ok = false;
        return false;
    }
    limits->ok = true;
    return true;
}

// ---- draw -------------------------------------------------------------------

GpuDevelopPipeline::DrawResult GpuDevelopPipeline::Impl::draw(
        const DevelopFrameRequest& req) {
    DrawResult r;
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;
    const bool yuvIn = req.yuv != nullptr;
    if (req.meta == nullptr || w == 0 || h == 0 ||
        (yuvIn ? (req.yuv->y == nullptr || req.yuv->c == nullptr || yuvProgram == 0)
               : (req.cfa == nullptr || req.cfa->size() != static_cast<size_t>(w) * h))) {
        r.detail = yuvIn ? "input: yuv planes missing" : "input: cfa size != w*h";
        return r;
    }
    r.width = w;
    r.height = h;

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

    // --- WW8 per-pass probe (DEBUG one-shot, armPassProbe) ----------------
    // Consumed here so a mid-chain failure cannot leave it armed. The
    // uniform dump prints every CPU-side input the PRE/POST passes see;
    // passProbe() reads the center texel of a chain FBO as raw GL_FLOAT
    // (RGBA32F + EXT_color_buffer_float makes GL_RGBA/GL_FLOAT the
    // guaranteed readback pair). It binds only GL_READ_FRAMEBUFFER; every
    // following pass rebinds GL_FRAMEBUFFER (both targets), so the chain's
    // state is untouched. Probe GL errors are swallowed, never reported.
    const bool passProbe = passProbeArmed;
    passProbeArmed = false;
    if (passProbe) {
        float shMin = 0.0f, shMax = 0.0f;
        if (!req.meta->lensShading.empty()) {
            const auto mm = std::minmax_element(req.meta->lensShading.begin(),
                                                req.meta->lensShading.end());
            shMin = *mm.first;
            shMax = *mm.second;
        }
        __android_log_print(ANDROID_LOG_INFO, "ritim-trace",
            "pass probe: uniforms %ux%u cell=(%d,%d,%d,%d) "
            "white=%.1f metaWhite=%d dynWhite=%d dynBlack0=%d "
            "black=(%.1f,%.1f,%.1f,%.1f) range=(%.1f,%.1f,%.1f,%.1f) "
            "applyShading=%d shading=%ux%u n=%zu min=%.4f max=%.4f "
            "invNeutral=(%.4f,%.4f,%.4f) hr=%d hrGain=(%.4f,%.4f,%.4f) "
            "hasProfile=%d cam2rgb=[%.4f %.4f %.4f; %.4f %.4f %.4f; %.4f %.4f %.4f] "
            "exposure=%.4f ev=%.3f nr=(%.1f,%.1f) sharp=%.1f detail=%.1f "
            "warp=%d tonemap=%d lut=%.2f grade[%s]",
            static_cast<unsigned>(w), static_cast<unsigned>(h),
            cell[0], cell[1], cell[2], cell[3],
            static_cast<double>(white), static_cast<int>(req.meta->white),
            static_cast<int>(req.info.dynamicWhite),
            static_cast<int>(req.info.dynamicBlack[0]),
            static_cast<double>(black[0]), static_cast<double>(black[1]),
            static_cast<double>(black[2]), static_cast<double>(black[3]),
            static_cast<double>(range[0]), static_cast<double>(range[1]),
            static_cast<double>(range[2]), static_cast<double>(range[3]),
            applyShading ? 1 : 0,
            static_cast<unsigned>(req.meta->shadingW),
            static_cast<unsigned>(req.meta->shadingH),
            req.meta->lensShading.size(),
            static_cast<double>(shMin), static_cast<double>(shMax),
            static_cast<double>(invNeutral[0]), static_cast<double>(invNeutral[1]),
            static_cast<double>(invNeutral[2]),
            req.highlightReconstruction ? 1 : 0,
            static_cast<double>(hrGain[0]), static_cast<double>(hrGain[1]),
            static_cast<double>(hrGain[2]),
            hasProfile ? 1 : 0,
            static_cast<double>(cam2rgb[0]), static_cast<double>(cam2rgb[1]),
            static_cast<double>(cam2rgb[2]), static_cast<double>(cam2rgb[3]),
            static_cast<double>(cam2rgb[4]), static_cast<double>(cam2rgb[5]),
            static_cast<double>(cam2rgb[6]), static_cast<double>(cam2rgb[7]),
            static_cast<double>(cam2rgb[8]),
            static_cast<double>(exposure), static_cast<double>(req.exposureEv),
            static_cast<double>(req.chromaDenoise),
            static_cast<double>(req.noiseReduction),
            static_cast<double>(req.sharpness), static_cast<double>(req.detail),
            req.warpActive ? 1 : 0, static_cast<int>(req.tonemap),
            static_cast<double>(req.lutStrength),
            gradeProbeSummary(req).c_str());
    }
    auto probePassFbo = [&](const char* tag, GLuint fbo) {
        if (!passProbe) return;
        while (glGetError() != GL_NO_ERROR) {}
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        GLfloat px[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
        glReadPixels(static_cast<GLint>(w / 2), static_cast<GLint>(h / 2), 1, 1,
                     GL_RGBA, GL_FLOAT, px);
        const GLenum err = glGetError();
        while (glGetError() != GL_NO_ERROR) {}
        __android_log_print(ANDROID_LOG_INFO, "ritim-trace",
            "pass probe: %-10s center=(%.5f,%.5f,%.5f,%.5f)%s err=0x%x",
            tag, static_cast<double>(px[0]), static_cast<double>(px[1]),
            static_cast<double>(px[2]), static_cast<double>(px[3]),
            (px[0] >= 1.0f || px[1] >= 1.0f || px[2] >= 1.0f) ? " >=1.0" : "",
            static_cast<unsigned>(err));
    };

    // --- storage allocation ONCE per resolution/resource change ------------
    // Immutable glTexStorage2D + per-frame glTexSubImage2D uploads: the
    // audit's finding against glTexImage2D-per-frame. The shading map is
    // per-CLIP (constant across frames), so its texture uploads when its
    // identity changes (below); the CFA is per-frame.
    //
    // [r.developReallocated] (white-window round 7): the entry mismatch is
    // the once-per-resolution branch's own key — the frame this branch
    // runs on is the frame the develop texture (and the chain siblings)
    // were DELETED and RE-CREATED. Recorded at ENTRY, before any delete:
    // a mid-branch failure still reports the realloc frame honestly (the
    // session maps the failure to its own latch rule; the flag only names
    // the allocation event). The probe prints it as realloc= on the same
    // line as the three-way pixel pair — see DrawResult's field comment
    // for why THIS is the real reportable state where the round-7 brief
    // imagined a "chain skip" (there is none; the TAIL always writes).
    r.developReallocated = (cfaW != w || cfaH != h);
    if (cfaW != w || cfaH != h) {
        // (Re)allocate the resolution-dependent storage. Any previous
        // allocation is deleted first (the preview session's tier ladder
        // changes dims mid-session; the develop session's one clip never
        // does, but the shared object serves both).
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
        // CFA row y. The preview session's REDUCED mosaic keeps the 2x2
        // pattern (cell-aligned decimation — Thumbnail.h's math, done by
        // the JNI entry), so uCell/uBlack/uRange and the shading plane
        // order stay valid at that tier too.
        glGenTextures(1, &cfaTex);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, cfaTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(R16UI)", &r.detail) != GL_NO_ERROR) return r;

        // develop texture: RGBA32F (EXT_color_buffer_float, gated in THIS
        // context at init). The owning session's post-chain passes sample
        // it — the preview's window pass through aspect-fit scaling, so
        // the filter is the config's choice: LINEAR when the device
        // advertises OES_texture_float_linear, NEAREST otherwise (32F is
        // only NEAREST-filterable without it — correct either way,
        // aliased without); the develop session's store draw and encoder
        // blit are 1:1, so it passes NEAREST. A FILTER choice, not a gate.
        // THE NEAREST PIN (owner device evidence 2026-11-15, the white
        // window): the PREVIEW session stopped feeding LINEAR through this
        // config — on the failing Adreno the extension is ADVERTISED yet
        // the window pass's LINEAR reads of this RGBA32F texture painted
        // WHITE (771 frames, zero GL errors, the stats draw of the SAME
        // texture healthy), so the preview pins NEAREST here; NEAREST is
        // the encoder blit's proven sampling shape. The config field and
        // this branch stay for the develop session's honest NEAREST and
        // for the day the pin lifts.
        glGenTextures(1, &developTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, developTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                        config.developLinearFilter ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER,
                        config.developLinearFilter ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA32F)", &r.detail) != GL_NO_ERROR) return r;
        glGenFramebuffers(1, &developFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               developTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            r.detail = "develop fbo incomplete (RGBA32F not renderable here)";
            return r;
        }

        // The chain's three RGBA32F ping-pong intermediates (the spatial-NR
        // port): A = the PRE pass's output (and the chroma vertical pass's
        // ORIGINAL guide), B/C = the NR passes' scratch pair. NEAREST +
        // CLAMP_TO_EDGE — the NR passes fetch 1:1 texels and clamp their
        // own offsets, exactly like the CPU's index clamps; no filtering,
        // no wrap. Allocated ONCE per resolution with the rest (the
        // preview's tier ladder changes them).
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
            if (glErrorAfter("glTexStorage2D(RGBA32F chain)", &r.detail) != GL_NO_ERROR) {
                return r;
            }
            glGenFramebuffers(1, fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, *tex, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                r.detail = "chain fbo incomplete (RGBA32F not renderable here)";
                return r;
            }
        }

        // The develop config's store-scratch pair (withStoreScratch):
        // scratch RGBA8, the store pass's throwaway target — never the
        // develop FBO, whose texture is the source: a feedback loop. The
        // RGBA8 contents are never read; the SSBO is the real output.
        if (config.withStoreScratch) {
            glGenTextures(1, &scratchTex);
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, scratchTex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8,
                           static_cast<GLsizei>(w), static_cast<GLsizei>(h));
            if (glErrorAfter("glTexStorage2D(RGBA8)", &r.detail) != GL_NO_ERROR) return r;
            glGenFramebuffers(1, &scratchFbo);
            glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   scratchTex, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                r.detail = "store fbo incomplete";
                return r;
            }

            // SSBO: w*h*3 floats, STREAM_READ (the store pass writes it,
            // the map reads it back — the harness's exact sizes and usage).
            const uint64_t bytes = static_cast<uint64_t>(w) * h * 3 * sizeof(float);
            glGenBuffers(1, &outBuf);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
            glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(bytes),
                         nullptr, GL_STREAM_READ);
            if (glErrorAfter("glBufferData(out)", &r.detail) != GL_NO_ERROR) return r;
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf);
            outBytes = bytes;
        }
        cfaW = w;
        cfaH = h;
    }

    // The shading map: uploaded when its identity changes (dims or size —
    // the preview session's rule, carried here for BOTH sessions; the
    // develop session serves one export's frames and the map is constant
    // across frames of one clip, so the identity key matches every frame
    // after the first — the same once-per-session cadence its own
    // once-per-texture rule produced. The one cadence difference: a frame
    // with applyShading false re-uploads the 1x1 dummy, a texture the
    // shader never samples (uHasShading=0) — the rendered bytes are
    // identical either way). A same-dims same-size content swap within
    // one session is not detected and is outside the one-clip-per-session
    // contract. The 1x1 dummy covers the absent-map case (the shader only
    // fetches when uHasShading==1, so the dummy is never read; the
    // sampler uniform is always set, keeping the program's uniform set
    // complete either way).
    const size_t mapSize = req.meta->lensShading.size();
    if (shadingTex == 0 || shadingW != req.meta->shadingW ||
        shadingH != req.meta->shadingH || shadingUploaded != mapSize) {
        if (shadingTex != 0) { glDeleteTextures(1, &shadingTex); shadingTex = 0; }
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
            if (glErrorAfter("glTexStorage2D(R32F shading)", &r.detail) != GL_NO_ERROR) return r;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(req.meta->shadingW),
                            static_cast<GLsizei>(req.meta->shadingH * 4),
                            GL_RED, GL_FLOAT, req.meta->lensShading.data());
            if (glErrorAfter("glTexSubImage2D(R32F shading)", &r.detail) != GL_NO_ERROR) return r;
            shadingW = req.meta->shadingW;
            shadingH = req.meta->shadingH;
        } else {
            const float dummy = 1.0f;
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, 1, 1);
            if (glErrorAfter("glTexStorage2D(R32F dummy)", &r.detail) != GL_NO_ERROR) return r;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, &dummy);
            if (glErrorAfter("glTexSubImage2D(R32F dummy)", &r.detail) != GL_NO_ERROR) return r;
            shadingW = 1;
            shadingH = 1;
        }
        shadingUploaded = mapSize;
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
    //     precedent: never a silent drop of stage 24). The owning session's
    //     latch rule (its GL thread loop latches render failures) then
    //     falls the caller back to CPU with this reason, the honest
    //     fallback.
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
            // An empty cache key is also the initial state; it does not
            // prove a complete dummy texture has been created yet.
            if (lutTex == 0 || lutUploadedPath != "") {
                if (lutTex == 0) glGenTextures(1, &lutTex);
                bindLutDummyTex(lutTex);
                if (glErrorAfter("glTexImage3D(RGBA16F lut dummy)", &r.detail) != GL_NO_ERROR) return r;
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
                        if (lutTex == 0 || lutUploadedPath != "") {
                            if (lutTex == 0) glGenTextures(1, &lutTex);
                            bindLutDummyTex(lutTex);
                            if (glErrorAfter("glTexImage3D(RGBA16F lut dummy)", &r.detail) != GL_NO_ERROR) return r;
                            lutUploadedPath.clear();
                        }
                        // This frame renders with the gate closed (the
                        // strength pin below), the honest no-LUT look.
                    } else {
                        r.detail = std::string("lut: ") +
                                     rawedit::errorName(parsed.code) + " — " +
                                     parsed.detail +
                                     " (recovery: pick a valid .cube file)";
                        return r;
                    }
                } else {
                    lutParsed = std::make_unique<rawedit::Lut>(std::move(*parsed.value));
                    lutParsedPath = path;
                }
            }
            if (lutParsedPath == path && lutParsed != nullptr) {
                const rawedit::Lut& lut = *lutParsed;
                const size_t n = static_cast<size_t>(lut.n);
                // rgb -> rgba32f interleave, in the table's own s/r/t order (no reordering).
                std::vector<float> rgba(n * n * n * 4);
                const size_t count = n * n * n;
                for (size_t e = 0; e < count; ++e) {
                    rgba[e * 4 + 0] = lut.rgb[e * 3 + 0];
                    rgba[e * 4 + 1] = lut.rgb[e * 3 + 1];
                    rgba[e * 4 + 2] = lut.rgb[e * 3 + 2];
                    rgba[e * 4 + 3] = 1.0f;  // alpha unused, pinned 1.0
                }
                if (lutTex == 0) glGenTextures(1, &lutTex);
                glActiveTexture(GL_TEXTURE5);
                glBindTexture(GL_TEXTURE_3D, lutTex);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
                glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA32F,
                             static_cast<GLsizei>(lut.n),
                             static_cast<GLsizei>(lut.n),
                             static_cast<GLsizei>(lut.n), 0,
                             GL_RGBA, GL_FLOAT, rgba.data());
                if (glErrorAfter("glTexImage3D(RGBA32F lut)", &r.detail) != GL_NO_ERROR) return r;
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

    // --- per-frame upload: the CFA, into the immutable storage ---------------
    // The upload span (the preview session's placement, carried verbatim):
    // tUpload0 before the unit bind, tUpload1 after the error check.
    using SteadyClock = std::chrono::steady_clock;
    const SteadyClock::time_point tUpload0 = SteadyClock::now();
    if (yuvIn) {
        // E0b-4b: the decoded planes as integer textures (raw code words; the pass converts).
        const EncodedYuvPassParams& yp = req.yuv->params;
        const bool wide = yp.bitDepth > 8;
        const int bps = wide ? 2 : 1;
        const int cw = (yp.width + 1) / 2;
        const int ch = (yp.height + 1) / 2;
        const int ystride = yp.stride > 0 ? yp.stride : yp.width * bps;
        const int cstride = yp.uvStride > 0 ? yp.uvStride : cw * 2 * bps;
        const long long yNeed = static_cast<long long>(yp.height - 1) * ystride + static_cast<long long>(yp.width) * bps;
        const long long cNeed = static_cast<long long>(ch - 1) * cstride + static_cast<long long>(cw) * 2 * bps;
        if (ystride % bps != 0 || cstride % (2 * bps) != 0 ||
            static_cast<long long>(req.yuv->yLen) < yNeed || static_cast<long long>(req.yuv->cLen) < cNeed ||
            (yp.bitDepth != 8 && yp.bitDepth != 10) || yp.reduce < 1 ||
            static_cast<uint32_t>((yp.width + yp.reduce - 1) / yp.reduce) != w ||
            static_cast<uint32_t>((yp.height + yp.reduce - 1) / yp.reduce) != h) {
            r.detail = "input: yuv planes/dims do not match the request";
            return r;
        }
        if (yuvSrcW != yp.width || yuvSrcH != yp.height || yuvBits != yp.bitDepth) {
            if (yuvYTex != 0) { glDeleteTextures(1, &yuvYTex); yuvYTex = 0; }
            if (yuvCTex != 0) { glDeleteTextures(1, &yuvCTex); yuvCTex = 0; }
            glGenTextures(1, &yuvYTex);
            glBindTexture(GL_TEXTURE_2D, yuvYTex);
            glTexStorage2D(GL_TEXTURE_2D, 1, wide ? GL_R16UI : GL_R8UI, yp.width, yp.height);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glGenTextures(1, &yuvCTex);
            glBindTexture(GL_TEXTURE_2D, yuvCTex);
            glTexStorage2D(GL_TEXTURE_2D, 1, wide ? GL_RG16UI : GL_RG8UI, cw, ch);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            yuvSrcW = yp.width; yuvSrcH = yp.height; yuvBits = yp.bitDepth;
            if (glErrorAfter("glTexStorage2D(yuv planes)", &r.detail) != GL_NO_ERROR) return r;
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, yuvYTex);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, ystride / bps);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, yp.width, yp.height, GL_RED_INTEGER,
                        wide ? GL_UNSIGNED_SHORT : GL_UNSIGNED_BYTE, req.yuv->y);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, yuvCTex);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, cstride / (2 * bps));
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, cw, ch, GL_RG_INTEGER,
                        wide ? GL_UNSIGNED_SHORT : GL_UNSIGNED_BYTE, req.yuv->c);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        if (glErrorAfter("glTexSubImage2D(yuv planes)", &r.detail) != GL_NO_ERROR) return r;
    } else {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cfaTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                    static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                    GL_RED_INTEGER, GL_UNSIGNED_SHORT, req.cfa->data());
    if (glErrorAfter("glTexSubImage2D(R16UI)", &r.detail) != GL_NO_ERROR) return r;
    }
    const SteadyClock::time_point tUpload1 = SteadyClock::now();
    r.uploadUs = elapsedUs(tUpload0, tUpload1);

    // --- draw 1: the PRE pass (stages 1-6) into texA ------------------------
    // The chain shape (the spatial-NR port, extended by the unsharp port):
    // PRE -> [NR, gated per grade] -> POST-pointwise -> [sharpness/detail,
    // gated per grade] -> [warp, gated per frame] -> TAIL, with the NR
    // passes at the CPU's exact stage 7/8 position and the unsharp passes
    // at its exact 21/22 position. texA is both PRE's output and the
    // vertical chroma pass's ORIGINAL guide (bound on unit 4, which no
    // other draw touches); the pass sources ride unit 1 with per-draw
    // rebinds; the TAIL pass ALWAYS writes developTex (the owning
    // session's post-chain passes read THAT texture — writing the tail's
    // output to an intermediate the chain also reads would be a
    // framebuffer feedback loop). The tDevelop span covers the WHOLE
    // chain honestly (the preview's rule: it always covered the one fused
    // draw; the chain is the new one draw).
    const SteadyClock::time_point tDevelop0 = SteadyClock::now();
    if (yuvIn) {
        // E0b-4b: pass 1 is the YUV pass, into the same texA the PRE pass fills (reduced dims).
        glUseProgram(yuvProgram);
        glBindFramebuffer(GL_FRAMEBUFFER, fboA);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, yuvYTex);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, yuvCTex);
        setEncodedYuvUniforms(yuvProgram, req.yuv->params);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(encoded-yuv)", &r.detail) != GL_NO_ERROR) return r;
    } else {
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
    if (glErrorAfter("glDrawArrays(pre)", &r.detail) != GL_NO_ERROR) return r;
    }
    auto probePass = [&](const char* tag, GLuint tex) {
        probePassFbo(tag, tex == texA ? fboA : (tex == texB ? fboB : fboC));
    };
    probePass("pre", texA);

    // --- draws 2-4: the spatial NR passes (stages 7/8), gated per grade ----
    // The CPU call sites' own gate + clamp (Develop.cpp: gate != 0.0f,
    // then clamp [0,100]/100 — the impl's strength <= 0 early-out): the
    // chain skips the draws entirely for a clamped strength of 0, and
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
        if (glErrorAfter("glDrawArrays(nr-chroma-h)", &r.detail) != GL_NO_ERROR) return r;
        probePass("nr-chroma-h", texB);

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
        if (glErrorAfter("glDrawArrays(nr-chroma-v)", &r.detail) != GL_NO_ERROR) return r;
        probePass("nr-chroma-v", texC);
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
        if (glErrorAfter("glDrawArrays(nr-luma)", &r.detail) != GL_NO_ERROR) return r;
        probePass("nr-luma", texB);
        chainTex = texB;
    }

    // --- draw 5: the POST pass (the pointwise stages 9-20) into a chain slot -
    // The three-way split's middle cut: the pointwise stages write a chain
    // intermediate (NOT developTex — the unsharp section and the tail still
    // read the chain; writing developTex here would let the tail read a
    // texture the session's post-chain passes also read while the chain
    // runs). The slot rotation threads the three ping-pong slots
    // A->B->C->A: each unsharp H pass writes the slot after its input,
    // each V pass the slot after that, so no draw reads and writes the
    // same texture and each V pass's guide (its STAGE'S INPUT, bound on
    // unit 4) stays live through its own draw.
    // Every pass's slots come from the pure planner (ChainSlots.h, unit-
    // tested on desktop); a pass's texture and FBO are looked up from the
    // same planned slot.
    const GLuint slotTex[] = {texA, texB, texC};
    const GLuint slotFbo[] = {fboA, fboB, fboC};
    const int nrLive = chainSlotAfterNr(runChroma, runLuma);  // == chainTex's slot
    const bool runSharp = req.sharpness != 0.0f && w >= 2 && h >= 2;
    const bool runDetail = req.detail != 0.0f && w >= 2 && h >= 2;
    // F1-5: the NODES pass runs when the request carries a chain (or the
    // DRT output) AND the additive door opened (initNodes). A
    // nodesActive request on a pre-F1-5 pipeline REFUSES the frame
    // honestly — never a silent no-op drop of the grade's nodes.
    const bool runNodes = req.nodesActive && (req.nodeCount > 0 || req.outputMode == 1);
    if (runNodes && !nodesInitialized) {
        r.detail = "nodes: the NODES pass was not initialized "
                   "(recovery: the session must call initNodes before "
                   "nodesActive requests)";
        return r;
    }
    const ChainPlan plan = planChain(nrLive, runSharp, runDetail, req.warpActive,
                                     runNodes);
    const ChainPass& postPlan = plan.passes[0];
    GLuint postSrc = slotTex[postPlan.in];
    GLuint postDst = slotTex[postPlan.out];
    glUseProgram(postProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, postSrc);
    glBindFramebuffer(GL_FRAMEBUFFER,
                      slotFbo[postPlan.out]);
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
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(post)", &r.detail) != GL_NO_ERROR) return r;
    probePass("post", postDst);
    chainTex = postDst;

    // --- draws 6-9: the unsharp passes (stages 21/22), gated per grade ------
    // The CPU call sites' own gate (Develop.cpp: the outer gate is
    // `sharpness != 0.0f || detail != 0.0f`, each stage's own gate is its
    // raw value != 0.0f) + the impl's own w<2/h<2 guard. NO clamp here —
    // the CPU's amount mapping min(max(0.5*(raw/100), -0.5), 0.5) runs
    // in-shader, so NEGATIVE raw values are valid softening and a raw 0
    // is the only skip. The chain skips the draws entirely for a raw
    // 0 or a too-small frame; the in-shader gate is the second lock — a
    // skipped draw and a gate-closed draw produce the same pass-through.
    // The slot rotation: A -> B -> C -> A (the ping-pong threading worked
    // out for every NR state — no draw reads and writes the same texture).
    // Each unsharp stage is an H pass (input -> the next slot) + a V pass
    // (the H output + the STAGE'S INPUT as the uGuide on unit 4 -> the
    // slot after that). Sharpness's guide is the pointwise output; the
    // tail of the chain hands detail the sharpness output as its guide.
    // (the slot plan was computed above, from ChainSlots.h)
    if (runSharp) {
        // Stage 21, horizontal (radius 1.5): sharpIn -> the next slot. The
        // H pass blurs ONLY luma — its .x output is the horizontally
        // blurred luma (the gate-closed branch writes the original luma
        // so a gate-closed draw is an exact pass-through).
        const ChainPass& sH = plan.passes[plan.sharpH];
        const ChainPass& sV = plan.passes[plan.sharpV];
        const GLuint sharpIn = slotTex[sH.in];
        const GLuint sharpHOut = slotTex[sH.out];
        glUseProgram(sharpnessHProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sharpIn);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[sH.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(sharpnessHSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(sharpnessHStrengthLoc, req.sharpness);  // raw; the shader gates
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(sharpness-h)", &r.detail) != GL_NO_ERROR) return r;
        probePass("sharp-h", sharpHOut);

        // Stage 21, vertical: sharpHOut + the guide sharpIn (unit 4) ->
        // the next slot. The V pass finishes the blur (the H output's .x
        // carries no RGB — the guide supplies the ORIGINAL luma and RGB
        // the CPU's second buffer keeps), then applies the high-pass
        // difference + the amount blend in the same draw.
        const GLuint sharpOut = slotTex[sV.out];
        glUseProgram(sharpnessVProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sharpHOut);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, sharpIn);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[sV.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(sharpnessVSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(sharpnessVStrengthLoc, req.sharpness);  // raw; the shader gates
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(sharpness-v)", &r.detail) != GL_NO_ERROR) return r;
        probePass("sharp-v", sharpOut);
        chainTex = sharpOut;
    }
    if (runDetail) {
        // Stage 22, horizontal (radius 0.75): chainTex (the sharpness
        // output, or the pointwise output when stage 21 is off) -> the
        // next slot.
        const ChainPass& dH = plan.passes[plan.detailH];
        const ChainPass& dV = plan.passes[plan.detailV];
        const GLuint detailIn = slotTex[dH.in];
        const GLuint detailHOut = slotTex[dH.out];
        glUseProgram(detailHProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, detailIn);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[dH.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(detailHSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(detailHStrengthLoc, req.detail);  // raw; the shader gates
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(detail-h)", &r.detail) != GL_NO_ERROR) return r;
        probePass("detail-h", detailHOut);

        // Stage 22, vertical: detailHOut + the guide detailIn (unit 4) ->
        // the next slot — the CPU's second buffer keeping the stage's
        // own input luma.
        const GLuint detailOut = slotTex[dV.out];
        glUseProgram(detailVProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, detailHOut);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, detailIn);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[dV.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(detailVSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1f(detailVStrengthLoc, req.detail);  // raw; the shader gates
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(detail-v)", &r.detail) != GL_NO_ERROR) return r;
        probePass("detail-v", detailOut);
        chainTex = detailOut;
    }
    // --- draw 10: the gyro warp pass, gated per FRAME -----------------------
    // NOT a develop stage: the CPU pipeline runs rawedit::warpFrame AFTER
    // developCpu, on the linear output, so the chain draws it here — after
    // the unsharp passes, BEFORE the tail, so the OETF stays in the tail
    // and is applied exactly once. The CALLER resolved the frame's
    // stabilization plan on its own thread, composed M = Hinv * S once and
    // folded inactive / identity / degenerate plans into warpActive = false
    // — the CPU's identity fast path is byte-identical, so the pass's
    // ABSENCE is the identity (a resampling pass on an identity map would
    // blur). There is NO in-shader gate; req.warpActive is the only lock.
    if (req.warpActive) {
        // The warp reads its planned input (unit 1) and writes its planned
        // output. 2026-11-23 owner report: the old code bound the NEXT slot's
        // FBO but recorded the slot AFTER it as the output, so TAIL read the
        // un-graded PRE image whenever stabilization was on. Texture and FBO
        // now come from the same planned slot.
        const ChainPass& wp = plan.passes[plan.warp];
        const GLuint warpOut = slotTex[wp.out];
        glUseProgram(warpProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, slotTex[wp.in]);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[wp.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(warpSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        // Row-major CPU composite map, transpose=GL_TRUE — the uCam2rgb
        // idiom (the shader's uM * vec3(x, y, 1) is mapPoint's exact tap).
        glUniformMatrix3fv(warpMLoc, 1, GL_TRUE, req.warpM);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(warp)", &r.detail) != GL_NO_ERROR) return r;
        probePass("warp", warpOut);
        chainTex = warpOut;
    }
    // --- draw 10.5: the NODES pass (color model v2 F1-5) ---------------------
    // AFTER the warp (the warp resamples in LINEAR light; the nodes work
    // in DI — plan §3.2 stage 6), BEFORE the tail, exactly the planned
    // kPassNodes slot (in = the chain's live slot, out = the next, no
    // guide — 3 slots stay sufficient; the 2026-11-23 warp-slot lesson's
    // same-planned-slot rule). The pass converts 709 -> DWG -> DI at the
    // head, loops the compiled node packs, and decodes back — the
    // head/tail conversions and the loop body are pinned against the CPU
    // reference by core/tests/test_gpu_nodes_logic.cpp.
    if (runNodes) {
        const ChainPass& np2 = plan.passes[plan.nodes];
        const GLuint nodesOut = slotTex[np2.out];
        // --- the revision cache: recompile the packs + refill the atlas
        // only when the chain changed (the shading map's identity-keyed
        // pattern; the key is the caller's cheap content hash). The
        // compile is core's own compileNode() - the single source of
        // truth the CPU reference, the Kotlin mirror and the headless
        // desktop test all ride. Live sliders = uniform updates, never a
        // recompile (plan SS5.5).
        const bool revisionChanged = !nodesChainValid || nodesChainKey != req.nodeChainHash;
        if (revisionChanged) {
            // Validate through the N1 limits FIRST (the same caps the CPU's
            // validateNodeChain enforces; the chain is borrowed values).
            const std::string chainErr = rawedit::validateNodeChain(
                req.nodeChain, static_cast<size_t>(req.nodeCount));
            if (!chainErr.empty()) {
                r.detail = "nodes: " + chainErr +
                           " (recovery: cap the chain at the N1 limits)";
                return r;
            }
            nodesPacks.assign(
                static_cast<size_t>(req.nodeCount), rawedit::CompiledNode());
            std::vector<float>& atlas = nodesAtlasRows;
            atlas.assign(512 * 24, 0.0f);
            for (int i = 0; i < req.nodeCount; ++i) {
                nodesPacks[static_cast<size_t>(i)] =
                    rawedit::compileNode(req.nodeChain[i]);
                const rawedit::CompiledNode& p =
                    nodesPacks[static_cast<size_t>(i)];
                if (!p.curves.empty()) {
                    std::memcpy(&atlas[static_cast<size_t>(i) * 3 * 512],
                                p.curves.data(), p.curves.size() * sizeof(float));
                }
            }
            glActiveTexture(GL_TEXTURE6);
            glBindTexture(GL_TEXTURE_2D, nodesAtlasTex);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 512, 24, GL_RED,
                            GL_FLOAT, atlas.data());
            if (glErrorAfter("glTexSubImage2D(R32F nodes atlas)", &r.detail) != GL_NO_ERROR)
                return r;
            nodesChainKey = req.nodeChainHash;
            nodesChainValid = true;
            // The node-LUT slot content keys are (chainHash, lut-pointer)
            // pairs, so a revision change naturally re-uploads below.
        }
        // --- the draw: per-frame uniform uploads from the cached packs.
        glUseProgram(nodesProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, slotTex[np2.in]);
        glBindFramebuffer(GL_FRAMEBUFFER, slotFbo[np2.out]);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(nodesSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
        glUniform1i(nodesCountLoc, req.nodeCount);
        glUniform1i(nodesOutputModeLoc, req.outputMode);
        float mToDwg[9], mTo709[9];
        rawedit::rec709ToDwgMatrix(mToDwg);
        rawedit::dwgToRec709Matrix(mTo709);
        glUniformMatrix3fv(nodesM709ToDwgLoc, 1, GL_TRUE, mToDwg);
        glUniformMatrix3fv(nodesMDwgTo709Loc, 1, GL_TRUE, mTo709);
        // The per-node packs (NodeParcel.h's layout); nodes past the chain
        // carry the neutral pack so the shader's arrays stay complete.
        float primary[8][4], gain[8][4], offs[8][4], gammaExp[8][4],
              ySide[8][4], contrastPivot[8][4], wb[8][4], flags[8][4];
        float hueMat[8][9];
        int lutSlot = 0;
        for (int i = 0; i < 8; ++i) {
            const bool live = i < req.nodeCount;
            const rawedit::CompiledNode& pack =
                live ? nodesPacks[static_cast<size_t>(i)] : nodesNeutralPack;
            const rawedit::GradeNode& n =
                live ? req.nodeChain[i] : nodesNeutralNode;
            for (int c = 0; c < 3; ++c) {
                primary[i][c] = pack.lift[c];
                gain[i][c] = pack.gain[c];
                offs[i][c] = pack.offset[c];
                gammaExp[i][c] = pack.gammaExp[c];
                ySide[i][c] = pack.ySide[c];
                wb[i][c] = pack.wb[c];
            }
            primary[i][3] = gain[i][3] = offs[i][3] = gammaExp[i][3] = 0.0f;
            ySide[i][3] = 0.0f;
            contrastPivot[i][0] = pack.contrast;
            contrastPivot[i][1] = pack.pivot;
            contrastPivot[i][2] = pack.sat;
            contrastPivot[i][3] = pack.mix;
            wb[i][3] = 0.0f;
            for (int m = 0; m < 9; ++m)
                hueMat[i][m] = pack.hueMat[m];
            const bool lutOn = live && n.hasLut();
            const int slot = lutOn ? lutSlot++ : -1;
            flags[i][0] = (live && (n.hasCurves() || n.hasLog())) ? 1.0f : 0.0f;
            flags[i][1] = lutOn ? 1.0f : 0.0f;
            flags[i][2] = static_cast<float>(slot);
            flags[i][3] = pack.lutMix;
            if (lutOn) {
                // --- the node-LUT revision upload (the stage-24 LUT port's
                // pattern, per slot): the table crosses as RGBA16F once per
                // (chain, Lut pointer) change; the .cube parse stays with
                // the CALLER (the lutPath seam, the plan's
                // strings-separately rule) - the pipeline never re-parses.
                const uint64_t contentKey = req.nodeChainHash ^
                    (reinterpret_cast<uintptr_t>(n.lut) * 0x9e3779b97f4a7c15ull);
                if (nodeLutKey[slot] != contentKey) {
                    const rawedit::Lut& lut = *n.lut;
                    const size_t cnt = static_cast<size_t>(lut.n) * lut.n * lut.n;
                    std::vector<uint16_t> rgba(cnt * 4);
                    for (size_t e = 0; e < cnt; ++e) {
                        rgba[e * 4 + 0] = floatToHalfBits(lut.rgb[e * 3 + 0]);
                        rgba[e * 4 + 1] = floatToHalfBits(lut.rgb[e * 3 + 1]);
                        rgba[e * 4 + 2] = floatToHalfBits(lut.rgb[e * 3 + 2]);
                        rgba[e * 4 + 3] = 0x3c00u;
                    }
                    glActiveTexture(GL_TEXTURE7 + slot);
                    glBindTexture(GL_TEXTURE_3D, nodeLutTex[slot]);
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
                    if (glErrorAfter("glTexImage3D(RGBA16F node-lut)", &r.detail) != GL_NO_ERROR)
                        return r;
                    nodeLutKey[slot] = contentKey;
                }
                // The slot's shape + binding uniforms - the binding is
                // core's own compileLutBinding on the node's interp (the
                // stage-24 LUT port's contract). 4 x 12 floats per draw is
                // the live-knob profile (plan SS5.5: uniform updates only).
                const rawedit::CompiledLutBinding b =
                    rawedit::compileLutBinding(n.lutInterp);
                // Shape layout (the shader contract): .x = size n, .yzw =
                // domainMin; the Hi vec4's .xyz = domainMax.
                glUniform4f(nodesLutShapeLoc + slot, static_cast<float>(n.lut->n),
                            n.lut->domainMin[0], n.lut->domainMin[1],
                            n.lut->domainMin[2]);
                glUniform4f(nodesLutShapeHiLoc + slot,
                            n.lut->domainMax[0], n.lut->domainMax[1],
                            n.lut->domainMax[2], 0.0f);
                glUniformMatrix3fv(nodesLutWorkingLoc + slot, 1, GL_TRUE,
                                   b.workingToInput);
                glUniformMatrix3fv(nodesLutOutputLoc + slot, 1, GL_TRUE,
                                   b.outputToWorking);
            }
        }
        glUniform4fv(nodesPrimaryLoc, 8, &primary[0][0]);
        glUniform4fv(nodesGainLoc, 8, &gain[0][0]);
        glUniform4fv(nodesOffsetLoc, 8, &offs[0][0]);
        glUniform4fv(nodesGammaExpLoc, 8, &gammaExp[0][0]);
        glUniform4fv(nodesYSideLoc, 8, &ySide[0][0]);
        glUniform4fv(nodesContrastPivotLoc, 8, &contrastPivot[0][0]);
        glUniform4fv(nodesWbLoc, 8, &wb[0][0]);
        glUniform4fv(nodesFlagsLoc, 8, &flags[0][0]);
        for (int i = 0; i < 8; ++i)
            glUniformMatrix3fv(nodesHueMatLoc + i, 1, GL_TRUE, hueMat[i]);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(nodes)", &r.detail) != GL_NO_ERROR) return r;
        probePass("nodes", nodesOut);
        chainTex = nodesOut;
    }
    // --- draw 11: the TAIL pass (stages 23+24+OETF) into developTex ---------
    // The TAIL pass ALWAYS writes developTex — the owning session's
    // post-chain passes (the develop session's store/blit, the preview
    // session's window/stats) read THAT texture. Color model v2 (F1-5):
    // an outputMode-1 request draws the OpenDRT VARIANT program instead —
    // the DRT takes the NODES pass's DWG-LINEAR output and writes display
    // CODE VALUES directly (tonemap/LUT/OETF all skipped: the DRT output
    // is display coded already, plan §3.4); the variant's uniforms ride
    // the request's pre-compiled drtUniforms block.
    const bool drtTail = runNodes && req.outputMode == 1;
    if (drtTail) {
        if (req.drtUniforms == nullptr) {
            r.detail = "nodes: outputMode 1 needs the DRT uniform pack "
                       "(recovery: the binding resolves the OpenDRT presets "
                       "before the nodes request)";
            return r;
        }
        glUseProgram(tailDrtProgram);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, slotTex[plan.finalSlot]);
        glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
        glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        glUniform2i(drtSizeLoc, static_cast<GLint>(w), static_cast<GLsizei>(h));
        // --- the drtUniforms fold: the resolved preset + the Impl
        // constructor's constants, CROSS-compiled Kotlin-side (the same
        // pre-compiled-packs rule the node chain rides; the values are
        // pinned by the JVM tests against core's own OpenDrt outputs in
        // F1-6/F1-7). The block layout: [0] eotf (int-as-float), [1] hdr,
        // [2] cwp, [3] normScalar, [4] normScalarHdr, then the 25 floats
        // of the five hue pairs + the pt groups + the conf groups in the
        // DRT_TAIL_UNIFORM_SLOTS order (GpuCropMap.h's requestCrop
        // precedent: a flat float block defined once, consumed by name
        // through the location table resolved at initNodes).
        // The fold runs per draw (the live-knob profile; no recompile).
        drtFoldUniforms(req.drtUniforms);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        if (glErrorAfter("glDrawArrays(tail-drt)", &r.detail) != GL_NO_ERROR) return r;
        probePassFbo("tail-drt", developFbo);
        const SteadyClock::time_point tDevelop1 = SteadyClock::now();
        r.developUs = elapsedUs(tDevelop0, tDevelop1);
        r.ok = true;
        return r;
    }
    glUseProgram(tailProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, slotTex[plan.finalSlot]);
    glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(tailSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform1i(tonemapLoc, req.tonemap);
    glUniform1i(oetfLoc, 1);  // linearToRec709 in-shader (the shared shape:
                              // the export's toByte readback and the
                              // preview's window both want the OETF applied
                              // exactly once, here)
    // Stage 24 LUT (the LUT port): the raw strength ([0,1], Kotlin-mapped)
    // + the table's shape/binding uniforms. The neutral pins when no LUT
    // is active (strength 0, size 0, role 0) keep the TAIL draw
    // byte-identical to the pre-LUT chain — the shader's own gate is the
    // skip, exactly like every raw grade value above. The role rides the
    // request's ordinal clamped to the shader's own vocabulary [0,3] — an
    // ordinal the shader does not know falls back to role 0 (the legacy
    // plain-sampler pair), the same honest fallback the CPU's nullptr
    // binding takes, never a silent mis-render.
    // THE STRENGTH PIN (WW8 fix, 2026-11-23): the shader's ONLY stage-24
    // gate is uLutStrength != 0, so an inactive LUT (empty path, missing
    // file, parse not cached) MUST upload 0 — otherwise the shader samples
    // the all-1.0 dummy table at full strength and the frame goes white
    // (device evidence: post center 0.022 -> tail center 1.0, lut=1.00 with
    // no table). The CPU skips stage 24 without a table; this matches it.
    const float lutStrengthPinned = lutActive ? req.lutStrength : 0.0f;
    if (passProbe) {
        __android_log_print(ANDROID_LOG_INFO, "ritim-trace",
            "pass probe: lut active=%d pathEmpty=%d reqStrength=%.3f "
            "uploaded=%.3f size=%d role=%d",
            lutActive ? 1 : 0, req.lutPath.empty() ? 1 : 0,
            static_cast<double>(req.lutStrength),
            static_cast<double>(lutStrengthPinned),
            lutActive ? lutParsed->n : 0, req.lutRole);
    }
    glUniform1f(lutStrengthLoc, lutStrengthPinned);
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
    if (glErrorAfter("glDrawArrays(tail)", &r.detail) != GL_NO_ERROR) return r;
    probePassFbo("tail", developFbo);
    const SteadyClock::time_point tDevelop1 = SteadyClock::now();
    r.developUs = elapsedUs(tDevelop0, tDevelop1);
    // The developed frame now lives in developTex (RGBA32F, OETF applied).
    // The owning session continues from here: the develop session's runJob
    // runs the store pass + SSBO readback (runEncoderJob blits this
    // texture straight to the encoder window surface instead); the preview
    // session runs its window pass + stats pack.
    r.ok = true;
    return r;
}

// ---- teardown ---------------------------------------------------------------

void GpuDevelopPipeline::Impl::teardown() {
    // The owning session calls this inside its own teardownOnGlThread (the
    // reverse of creation, on the GL thread). Safe on a partially-
    // initialized pipeline: every handle starts at 0. The session deletes
    // its own session-specific resources (its blit/window program, its
    // encoder/window surface, its EGL) in its own established order.
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
    if (yuvYTex != 0) { glDeleteTextures(1, &yuvYTex); yuvYTex = 0; }
    if (yuvCTex != 0) { glDeleteTextures(1, &yuvCTex); yuvCTex = 0; }
    yuvSrcW = yuvSrcH = yuvBits = 0;
    if (yuvProgram != 0) { glDeleteProgram(yuvProgram); yuvProgram = 0; }
    if (preProgram != 0) { glDeleteProgram(preProgram); preProgram = 0; }
    if (postProgram != 0) { glDeleteProgram(postProgram); postProgram = 0; }
    if (tailProgram != 0) { glDeleteProgram(tailProgram); tailProgram = 0; }
    if (sharpnessHProgram != 0) { glDeleteProgram(sharpnessHProgram); sharpnessHProgram = 0; }
    if (sharpnessVProgram != 0) { glDeleteProgram(sharpnessVProgram); sharpnessVProgram = 0; }
    if (detailHProgram != 0) { glDeleteProgram(detailHProgram); detailHProgram = 0; }
    if (detailVProgram != 0) { glDeleteProgram(detailVProgram); detailVProgram = 0; }
    if (warpProgram != 0) { glDeleteProgram(warpProgram); warpProgram = 0; }
    if (nrChromaHProgram != 0) { glDeleteProgram(nrChromaHProgram); nrChromaHProgram = 0; }
    if (nrChromaVProgram != 0) { glDeleteProgram(nrChromaVProgram); nrChromaVProgram = 0; }
    if (nrLumaProgram != 0) { glDeleteProgram(nrLumaProgram); nrLumaProgram = 0; }
    if (storeProgram != 0) { glDeleteProgram(storeProgram); storeProgram = 0; }
    // The F1-5 additions (initNodes' own objects; guarded no-ops on a
    // pre-F1-5 session — every handle starts at 0).
    if (nodesProgram != 0) { glDeleteProgram(nodesProgram); nodesProgram = 0; }
    if (tailDrtProgram != 0) { glDeleteProgram(tailDrtProgram); tailDrtProgram = 0; }
    for (int s = 0; s < 4; ++s) {
        if (nodeLutTex[s] != 0) { glDeleteTextures(1, &nodeLutTex[s]); nodeLutTex[s] = 0; }
    }
    if (nodesAtlasTex != 0) { glDeleteTextures(1, &nodesAtlasTex); nodesAtlasTex = 0; }
    nodesInitialized = false;
    nodesChainValid = false;
    nodesChainKey = 0;
}

// ---- the drtUniforms fold (F1-5) -------------------------------------------
//
// The flat block's layout lives in GpuDevelopPipeline.h (kDrtUniformSlots);
// this is its ONE consumer — the locations were resolved at initNodes and
// the per-draw cost is the live-knob profile (uniforms update, never a
// recompile).

void GpuDevelopPipeline::Impl::drtFoldUniforms(const float* b) {
    glUniform1i(drtEotfLoc, static_cast<int>(b[0]));
    glUniform1f(drtHdrLoc, b[1]);
    glUniform1f(drtCwpLoc, b[2]);
    glUniform1f(drtNormLoc, b[3]);
    glUniform1f(drtNormHdrLoc, b[4]);
    glUniform4fv(drtTnLoc, 1, &b[5]);
    glUniform4fv(drtRsLoc, 1, &b[9]);
    glUniform4fv(drtToeLoc, 1, &b[13]);
    glUniform4fv(drtBrlRgbLoc, 1, &b[17]);
    glUniform4fv(drtBrlpRgbLoc, 1, &b[21]);
    glUniform4fv(drtLconLoc, 1, &b[25]);
    glUniform4fv(drtHconLoc, 1, &b[29]);
    glUniform4fv(drtTsLoc, 1, &b[33]);
    glUniform2f(drtHueRLoc, b[37], b[38]);
    glUniform2f(drtHueGLoc, b[39], b[40]);
    glUniform2f(drtHueBLoc, b[41], b[42]);
    glUniform2f(drtHueCLoc, b[43], b[44]);
    glUniform2f(drtHueMLoc, b[45], b[46]);
    glUniform2f(drtHueYLoc, b[47], b[48]);
    glUniform4fv(drtConf0Loc, 1, &b[49]);
    glUniform4fv(drtConf1Loc, 1, &b[53]);
    glUniform4fv(drtConf2Loc, 1, &b[57]);
    glUniform4fv(drtPtLoc, 1, &b[61]);
    glUniform4fv(drtPt2Loc, 1, &b[65]);
    glUniform4fv(drtPt3Loc, 1, &b[69]);
    glUniform4fv(drtPt4Loc, 1, &b[73]);
}

// ---- public surface ---------------------------------------------------------

GpuDevelopPipeline::GpuDevelopPipeline() : impl_(std::make_unique<Impl>()) {}

GpuDevelopPipeline::~GpuDevelopPipeline() = default;

bool GpuDevelopPipeline::init(const std::string& vertexSource,
                              const std::string& vertex310Source,
                              const std::string& preFragmentSource,
                              const std::string& postFragmentSource,
                              const std::string& tailFragmentSource,
                              const std::string& sharpnessHSource,
                              const std::string& sharpnessVSource,
                              const std::string& detailHSource,
                              const std::string& detailVSource,
                              const std::string& warpFragmentSource,
                              const std::string& nrChromaHSource,
                              const std::string& nrChromaVSource,
                              const std::string& nrLumaSource,
                              const std::string& storeFragmentSource,
                              const Config& config,
                              std::string* detail) {
    return impl_->init(vertexSource, vertex310Source, preFragmentSource,
                       postFragmentSource, tailFragmentSource, sharpnessHSource,
                       sharpnessVSource, detailHSource, detailVSource,
                       warpFragmentSource, nrChromaHSource, nrChromaVSource,
                       nrLumaSource, storeFragmentSource, config, detail);
}

std::string gradeProbeSummary(const DevelopFrameRequest& r) {
    char buf[320];
    std::snprintf(buf, sizeof(buf),
        "ev=%.3f hi=%.1f bp=%.1f wp=%.1f sh=%.1f mid=%.1f con=%.1f sat=%.1f "
        "temp=%.0f tint=%.1f asShot=%d sharp=%.1f det=%.1f nr=(%.1f,%.1f) tm=%d",
        static_cast<double>(r.exposureEv), static_cast<double>(r.highlights),
        static_cast<double>(r.blackPoint), static_cast<double>(r.whitePoint),
        static_cast<double>(r.shadows), static_cast<double>(r.midtones),
        static_cast<double>(r.contrast), static_cast<double>(r.saturation),
        static_cast<double>(r.tempK), static_cast<double>(r.tint),
        r.asShot ? 1 : 0, static_cast<double>(r.sharpness),
        static_cast<double>(r.detail), static_cast<double>(r.chromaDenoise),
        static_cast<double>(r.noiseReduction), r.tonemap);
    return std::string(buf);
}

uint64_t gradeProbeHash(const DevelopFrameRequest& r) {
    // FNV-1a over the float bit patterns of every develop control.
    const float v[] = {
        r.exposureEv, r.highlights, r.blackPoint, r.whitePoint, r.shadows,
        r.midtones, r.contrast, r.tempK, r.tint, r.saturation,
        r.asShot ? 1.0f : 0.0f, r.sharpness, r.detail, r.chromaDenoise,
        r.noiseReduction, static_cast<float>(r.tonemap),
        r.wheelShadowX, r.wheelShadowY, r.wheelMidX, r.wheelMidY,
        r.wheelHighX, r.wheelHighY, r.splitShadowHue, r.splitHighlightHue,
        r.splitBalance,
    };
    uint64_t h = 1469598103934665603ull;
    for (float f : v) {
        uint32_t bits;
        std::memcpy(&bits, &f, sizeof(bits));
        for (int i = 0; i < 4; ++i) {
            h ^= (bits >> (8 * i)) & 0xffu;
            h *= 1099511628211ull;
        }
    }
    return h;
}

GpuDevelopPipeline::DrawResult GpuDevelopPipeline::draw(
        const DevelopFrameRequest& req) {
    return impl_->draw(req);
}

void GpuDevelopPipeline::armPassProbe() { impl_->passProbeArmed = true; }
GLuint GpuDevelopPipeline::developTex() const { return impl_->developTex; }
GLuint GpuDevelopPipeline::storeProgram() const { return impl_->storeProgram; }
GLint GpuDevelopPipeline::storeSrcLoc() const { return impl_->storeSrcLoc; }
GLint GpuDevelopPipeline::storeSizeLoc() const { return impl_->storeSizeLoc; }
GLuint GpuDevelopPipeline::scratchFbo() const { return impl_->scratchFbo; }
GLuint GpuDevelopPipeline::outBuf() const { return impl_->outBuf; }
uint64_t GpuDevelopPipeline::outBytes() const { return impl_->outBytes; }

bool GpuDevelopPipeline::initNodes(const std::string& nodesFragmentSource,
                                    const std::string& vertexSource,
                                    const std::string& tailDrtSource,
                                    NodesLimits* limits,
                                    std::string* detail) {
    return impl_->initNodes(nodesFragmentSource, vertexSource, tailDrtSource,
                            limits, detail);
}

GpuDevelopPipeline::NodesLimits GpuDevelopPipeline::nodesLimits() const {
    return impl_->limits;
}

void GpuDevelopPipeline::teardown() { impl_->teardown(); }

} // namespace gles
