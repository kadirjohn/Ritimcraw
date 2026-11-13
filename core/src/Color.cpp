// rawedit color management — implementation. See Color.h for the model,
// the per-transfer negative/HDR policies, and the pinned-source
// provenance of every constant below (colour-science pin 7ca0c87,
// BSD-3-Clause, Copyright 2013 Colour Developers; ACES CTLs pin 79688e5
// for D65; Blackmagic Design's Wide Gamut Intermediate documentation
// for the DaVinci Intermediate constants and the corrected DWG matrix).

#include <rawedit/Color.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>

#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/Transfer.h>

namespace rawedit {

namespace {

// ---- Self-contained row-major 3x3 helpers. Develop.cpp keeps its own
// copies in an anonymous namespace; these mirror that precedent so
// Color.cpp does not depend on Develop.cpp internals. ----
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

// Invert a 3x3 (cofactor expansion). Returns identity when singular —
// same guard as Develop.cpp's inv3.
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

void apply3(const Mat3& m, const float in[3], float out[3]) {
    out[0] = m.m[0] * in[0] + m.m[1] * in[1] + m.m[2] * in[2];
    out[1] = m.m[3] * in[0] + m.m[4] * in[1] + m.m[5] * in[2];
    out[2] = m.m[6] * in[0] + m.m[7] * in[1] + m.m[8] * in[2];
}

bool isIdentity(const Mat3& m) {
    return m.m[0] == 1 && m.m[4] == 1 && m.m[8] == 1
        && m.m[1] == 0 && m.m[2] == 0 && m.m[3] == 0
        && m.m[5] == 0 && m.m[6] == 0 && m.m[7] == 0;
}

Mat3 identity3() { return Mat3{{1,0,0, 0,1,0, 0,0,1}}; }

// ---- Signed power: colour-science's colour.algebra.spow, implemented
// locally (the pinned subset carries no algebra module). Sign-preserving
// so negatives stay reversible through the sRGB and gamma curves. ----
inline float spow(float x, float e) {
    return x < 0.0f ? -std::pow(-x, e) : std::pow(x, e);
}

// ---- DaVinci Intermediate constants (pinned
// transfer_functions/davinci_intermediate.py, verbatim) ----
constexpr float kDiA = 0.0075f;
constexpr float kDiB = 7.0f;
constexpr float kDiC = 0.07329248f;
constexpr float kDiM = 10.44426855f;
constexpr float kDiLinCut = 0.00262409f;
constexpr float kDiLogCut = 0.02740668f;

// ---- sRGB constants (pinned transfer_functions/srgb.py) ----
constexpr float kSrgbLinCut = 0.0031308f;
constexpr float kSrgbEncodedBreakpoint = 0.040449936f; // sRGB(0.0031308)

// ---- Rec.709 OETF: the existing rawedit::linearToRec709 (Transfer.h),
// never redefined here. Its decode is the mathematical inverse; the
// linear branch of the existing curve is 4.5*L below L = 0.018, so the
// encoded breakpoint of the inverse is 4.5 * 0.018 = 0.081. The existing
// curve is 2.48e-4 discontinuous at 0.018 (log side 0.0812479440) — that
// is the curve's own property, documented, not altered. ----
constexpr float k709InvLinCut = 0.081f;

// ---- Pinned gamut matrices, verbatim ----
// sRGB dataset (datasets/srgb.py) — also the Rec.709 matrix (the BT.709
// dataset derives the same matrix from the same primaries at runtime).
const float kM709ToXyz[9] = {
    0.4124f, 0.3576f, 0.1805f,
    0.2126f, 0.7152f, 0.0722f,
    0.0193f, 0.1192f, 0.9505f,
};
// DaVinci Wide Gamut corrected pair (datasets/davinci_wide_gamut.py).
const float kMDwgToXyz[9] = {
    0.70062239f, 0.14877482f, 0.10105872f,
    0.27411851f, 0.87363190f, -0.14775041f,
    -0.09896291f, -0.13789533f, 1.32591599f,
};

// ---- Primaries (x, y) ----
const float kP2020[3][2] = {
    {0.7080f, 0.2920f}, {0.1700f, 0.7970f}, {0.1310f, 0.0460f},
};

// ---- White points (XYZ, Y = 1). D65 chromaticity (0.3127, 0.3290) from
// the ACES CTLs pin; D60 (0.32168, 0.33767) is the AP0 white, used by
// tests to prove the CAT. All four gamuts are D65. ----
void xyzFromChromaticity(float x, float y, float out[3]) {
    out[0] = x / y;
    out[1] = 1.0f;
    out[2] = (1.0f - x - y) / y;
}

void d65Xyz(float out[3]) { xyzFromChromaticity(0.3127f, 0.3290f, out); }

// ---- Bradford cone response (same numerics as Develop.cpp) ----
const float kBradford[9] = {
    0.8951f,  0.2664f, -0.1614f,
   -0.7502f,  1.7135f,  0.0367f,
    0.0389f, -0.0685f,  1.0296f,
};

// ---- Normalised primary matrix: columns are the unit-luminance XYZ of
// each primary; column scale factors c solve M_raw * c = white. The
// pinned BT.2020 dataset derives its matrix exactly this way. ----
void npm(const float prim[3][2], const float white[3], Mat3& out) {
    float cols[9]; // column-major raw matrix
    for (int p = 0; p < 3; ++p) {
        const float x = prim[p][0], y = prim[p][1];
        cols[p * 3 + 0] = x / y;
        cols[p * 3 + 1] = 1.0f;
        cols[p * 3 + 2] = (1.0f - x - y) / y;
    }
    // Solve M_raw * c = white with M_raw stored column-major: row i of
    // the row-major transpose is column i of M_raw.
    Mat3 raw{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            raw.m[i * 3 + j] = cols[j * 3 + i];
    const Mat3 rawInv = inv3(raw);
    float c[3];
    apply3(rawInv, white, c);
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            out.m[i * 3 + j] = raw.m[i * 3 + j] * c[j];
}

Mat3 gamutToXyzMat(GamutId g) {
    Mat3 out{};
    switch (g) {
        case GamutId::Rec709:
            std::memcpy(out.m, kM709ToXyz, sizeof(out.m));
            return out;
        case GamutId::DaVinciWideGamut:
            std::memcpy(out.m, kMDwgToXyz, sizeof(out.m));
            return out;
        case GamutId::Rec2020: {
            float w[3];
            d65Xyz(w);
            npm(kP2020, w, out);
            return out;
        }
    }
    return identity3();
}

} // namespace

// ---- Transfer functions ----

float encodeTransfer(float linear, TransferId t) {
    switch (t) {
        case TransferId::Linear:
            return linear;
        case TransferId::Srgb:
            return linear <= kSrgbLinCut
                ? linear * 12.92f
                : 1.055f * spow(linear, 1.0f / 2.4f) - 0.055f;
        case TransferId::Rec709OETF:
            // The existing curve, never redefined. Delegates so the two
            // can never drift apart.
            return linearToRec709(linear);
        case TransferId::Gamma24:
            return spow(linear, 1.0f / 2.4f);
        case TransferId::DaVinciIntermediate:
            return linear <= kDiLinCut
                ? linear * kDiM
                : kDiC * (std::log2(linear + kDiA) + kDiB);
    }
    return linear;
}

float decodeTransfer(float encoded, TransferId t) {
    switch (t) {
        case TransferId::Linear:
            return encoded;
        case TransferId::Srgb:
            // Decode compares against the ENCODED breakpoint, as the
            // pinned srgb.py does (eotf_sRGB compares V against
            // eotf_inverse_sRGB(0.0031308) = 0.040449936).
            return encoded <= kSrgbEncodedBreakpoint
                ? encoded / 12.92f
                : spow((encoded + 0.055f) / 1.055f, 2.4f);
        case TransferId::Rec709OETF:
            // Inverse of the existing curve: linear branch V/4.5 below
            // the encoded cut 4.5*0.018 = 0.081, log branch
            // spow((V+0.099)/1.099, 1/0.45) above.
            return encoded <= k709InvLinCut
                ? encoded / 4.5f
                : spow((encoded + 0.099f) / 1.099f, 1.0f / 0.45f);
        case TransferId::Gamma24:
            return spow(encoded, 2.4f);
        case TransferId::DaVinciIntermediate:
            // The pinned form is 2 ** ((V / C) - B) - A; the base is
            // always positive so no signed power is needed.
            return encoded <= kDiLogCut
                ? encoded / kDiM
                : std::pow(2.0f, (encoded / kDiC) - kDiB) - kDiA;
    }
    return encoded;
}

// ---- Gamut matrices ----

void gamutToXyz(GamutId g, float out[9]) {
    const Mat3 m = gamutToXyzMat(g);
    std::memcpy(out, m.m, sizeof(float) * 9);
}

void xyzToGamut(GamutId g, float out[9]) {
    const Mat3 inv = inv3(gamutToXyzMat(g));
    std::memcpy(out, inv.m, sizeof(float) * 9);
}

void whitePointGamut(GamutId g, float out[3]) {
    (void)g; // all four gamuts are D65
    d65Xyz(out);
}

void bradfordCatXyz(const float srcWhite[3], const float dstWhite[3],
                    float out[9]) {
    Mat3 B{};
    std::memcpy(B.m, kBradford, sizeof(B.m));
    float srcCone[3], dstCone[3];
    apply3(B, srcWhite, srcCone);
    apply3(B, dstWhite, dstCone);
    Mat3 D = identity3();
    D.m[0] = dstCone[0] / srcCone[0];
    D.m[4] = dstCone[1] / srcCone[1];
    D.m[8] = dstCone[2] / srcCone[2];
    const Mat3 cat = mul3(inv3(B), mul3(D, B));
    std::memcpy(out, cat.m, sizeof(float) * 9);
}

void gamutToGamut(GamutId from, GamutId to, float out[9]) {
    if (from == to) {
        const Mat3 id = identity3();
        std::memcpy(out, id.m, sizeof(float) * 9);
        return;
    }
    float wFrom[3], wTo[3];
    whitePointGamut(from, wFrom);
    whitePointGamut(to, wTo);
    float cat[9];
    bradfordCatXyz(wFrom, wTo, cat);
    Mat3 A{}, B{};
    std::memcpy(A.m, cat, sizeof(A.m));
    B = gamutToXyzMat(from);
    const Mat3 toXyzInv = inv3(gamutToXyzMat(to));
    const Mat3 m = mul3(toXyzInv, mul3(A, B));
    std::memcpy(out, m.m, sizeof(float) * 9);
}

// ---- Managed LUT application ----

CompiledLutBinding compileLutBinding(const LutInterpretation& interp) {
    CompiledLutBinding b{};
    b.interp = interp;

    float in[9], out[9];
    gamutToGamut(GamutId::Rec709, interp.input.gamut, in);
    gamutToGamut(interp.output.gamut, GamutId::Rec709, out);
    std::memcpy(b.workingToInput, in, sizeof(b.workingToInput));
    std::memcpy(b.outputToWorking, out, sizeof(b.outputToWorking));

    Mat3 win{}, wout{};
    std::memcpy(win.m, b.workingToInput, sizeof(win.m));
    std::memcpy(wout.m, b.outputToWorking, sizeof(wout.m));
    b.passthroughEncodings =
        isIdentity(win) && isIdentity(wout)
        && interp.input.transfer == TransferId::Linear
        && interp.output.transfer == TransferId::Linear;
    return b;
}

namespace {

// Trilinear sample of a parsed Lut at an encoded input — the SAME
// sampler arithmetic as Lut.cpp's applyLut (lattice coordinates from
// the table's domain, clamped to the domain edges, 8-corner blend),
// factored to a per-pixel form so the managed path can share it without
// touching the legacy stage-24 code. Entry (i, j, k) lives at
// rgb[(i + n*j + n*n*k) * 3].
void sampleLutPixel(const Lut& lut, const float in[3], float out[3]) {
    const int n = lut.n;
    if (n < 2 || lut.rgb.size() != static_cast<size_t>(n) * n * n * 3) {
        out[0] = in[0];
        out[1] = in[1];
        out[2] = in[2];
        return;
    }
    float scale[3];
    for (int c = 0; c < 3; ++c)
        scale[c] = static_cast<float>(n - 1) /
                   std::max(lut.domainMax[c] - lut.domainMin[c], 1e-20f);

    float fx[3];
    int i0[3], i1[3];
    float w1[3];
    for (int c = 0; c < 3; ++c) {
        fx[c] = (in[c] - lut.domainMin[c]) * scale[c];
        fx[c] = std::min(std::max(fx[c], 0.0f), static_cast<float>(n - 1));
        i0[c] = static_cast<int>(fx[c]);
        i0[c] = std::min(i0[c], n - 2);
        i1[c] = i0[c] + 1;
        w1[c] = fx[c] - static_cast<float>(i0[c]);
    }

    float acc[3] = {0.0f, 0.0f, 0.0f};
    for (int dk = 0; dk <= 1; ++dk) {
        const float wk = dk == 0 ? 1.0f - w1[2] : w1[2];
        const size_t baseK =
            static_cast<size_t>(dk ? i1[2] : i0[2]) * n * n;
        for (int dj = 0; dj <= 1; ++dj) {
            const float wj = dj == 0 ? 1.0f - w1[1] : w1[1];
            const float wkj = wk * wj;
            const size_t baseJ =
                static_cast<size_t>(dj ? i1[1] : i0[1]) * n;
            for (int di = 0; di <= 1; ++di) {
                const float wi = di == 0 ? 1.0f - w1[0] : w1[0];
                const float w = wkj * wi;
                const size_t idx =
                    (baseK + baseJ +
                     static_cast<size_t>(di ? i1[0] : i0[0])) * 3;
                acc[0] += w * lut.rgb[idx + 0];
                acc[1] += w * lut.rgb[idx + 1];
                acc[2] += w * lut.rgb[idx + 2];
            }
        }
    }
    out[0] = acc[0];
    out[1] = acc[1];
    out[2] = acc[2];
}

} // namespace

void evaluateManagedLutPixel(const Lut& lut,
                              const CompiledLutBinding& binding,
                              const float rgbIn[3], float rgbOut[3]) {
    // working (linear Rec.709) -> input gamut, linear
    float gamutIn[3];
    {
        Mat3 m{};
        std::memcpy(m.m, binding.workingToInput, sizeof(m.m));
        apply3(m, rgbIn, gamutIn);
    }
    // encode
    float enc[3];
    enc[0] = encodeTransfer(gamutIn[0], binding.interp.input.transfer);
    enc[1] = encodeTransfer(gamutIn[1], binding.interp.input.transfer);
    enc[2] = encodeTransfer(gamutIn[2], binding.interp.input.transfer);
    // sample (the existing sampler arithmetic, domain clamp included)
    float sampled[3];
    sampleLutPixel(lut, enc, sampled);
    // decode
    float dec[3];
    dec[0] = decodeTransfer(sampled[0], binding.interp.output.transfer);
    dec[1] = decodeTransfer(sampled[1], binding.interp.output.transfer);
    dec[2] = decodeTransfer(sampled[2], binding.interp.output.transfer);
    // output gamut -> working (linear Rec.709)
    {
        Mat3 m{};
        std::memcpy(m.m, binding.outputToWorking, sizeof(m.m));
        apply3(m, dec, rgbOut);
    }
}

void evaluateManagedLut(ImageF& img, const Lut& lut,
                        const CompiledLutBinding& binding, float strength) {
    if (!(strength > 0.0f))
        return; // 0 (or NaN) = exact bypass, same contract as applyLut
    const float s = std::min(strength, 1.0f);
    const size_t pixels = static_cast<size_t>(img.w) * img.h;
    if (pixels == 0 || img.rgb.size() != pixels * 3)
        return;
    for (size_t p = 0; p < pixels; ++p) {
        const float* in = &img.rgb[p * 3];
        float out[3];
        evaluateManagedLutPixel(lut, binding, in, out);
        float* px = &img.rgb[p * 3];
        px[0] = out[0] * s + px[0] * (1.0f - s);
        px[1] = out[1] * s + px[1] * (1.0f - s);
        px[2] = out[2] * s + px[2] * (1.0f - s);
    }
}

std::string interpretationSignature(const LutInterpretation& interp) {
    auto gamutChar = [](GamutId g) -> char {
        switch (g) {
            case GamutId::Rec709: return '0';
            case GamutId::Rec2020: return '2';
            case GamutId::DaVinciWideGamut: return 'D';
        }
        return '?';
    };
    auto transferChar = [](TransferId t) -> char {
        switch (t) {
            case TransferId::Linear: return 'l';
            case TransferId::Srgb: return 's';
            case TransferId::Rec709OETF: return '7';
            case TransferId::Gamma24: return 'g';
            case TransferId::DaVinciIntermediate: return 'd';
        }
        return '?';
    };
    auto roleChar = [](LutRole r) -> char {
        switch (r) {
            case LutRole::LegacyLinear709: return 'L';
            case LutRole::DisplayLook: return 'D';
            case LutRole::SceneLook: return 'S';
            case LutRole::SceneToDisplay: return 'T';
        }
        return '?';
    };
    std::ostringstream os;
    os << roleChar(interp.role)
       << gamutChar(interp.input.gamut) << transferChar(interp.input.transfer)
       << gamutChar(interp.output.gamut) << transferChar(interp.output.transfer);
    return os.str();
}

} // namespace rawedit
