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

// ---- Priority-2 transfer constants (commit (e), pinned verbatim —
// see Color.h's provenance block for the per-constant sources) ----

// ACEScct (transfer_functions/aces.py, CONSTANTS_ACES_CCT)
constexpr float kCctXBrk = 0.0078125f;
constexpr float kCctYBrk = 0.155251141552511f;
constexpr float kCctA = 10.5402377416545f;
constexpr float kCctB = 0.0729055341958355f;

// ARRI LogC3 (transfer_functions/arri.py,
// DATA_ALEXA_LOG_C_CURVE_CONVERSION["SUP 3.x"]
// ["Linear Scene Exposure Factor"][800])
constexpr float kLogC3Cut = 0.010591f;
constexpr float kLogC3A = 5.555556f;
constexpr float kLogC3B = 0.052272f;
constexpr float kLogC3C = 0.247190f;
constexpr float kLogC3D = 0.385537f;
constexpr float kLogC3E = 5.367655f;
constexpr float kLogC3F = 0.092809f;

// Sony S-Log3 (transfer_functions/sony.py, in_reflection=True)
constexpr float kSlog3Cut = 0.01125000f;
constexpr float kSlog3EncCut = 171.2102946929f / 1023.0f;

// Canon C-Log3 (CSC.Canon.ACES_to_CLog3_CGamut.ctl, lin_to_CLog3 —
// the curve verbatim; the CTL main()'s /0.9 wrapper is NOT part of it)
constexpr float kCLog3Cut = 0.014f;
constexpr float kCLog3ASub = -0.36726845f; // negative branch log slope
constexpr float kCLog3BSub = 0.12783901f; // negative branch offset
constexpr float kCLog3LinA = 1.9754798f;
constexpr float kCLog3LinB = 0.12512219f;
constexpr float kCLog3ASup = 0.36726845f;  // positive branch log slope
constexpr float kCLog3BSup = 0.12240537f;  // positive branch offset
constexpr float kCLog3K = 14.98325f;
constexpr float kCLog3EncCutLo = kCLog3LinA * -kCLog3Cut + kCLog3LinB;
constexpr float kCLog3EncCutHi = kCLog3LinA * kCLog3Cut + kCLog3LinB;

// Apple Log (transfer_functions/apple_log_profile.py,
// CONSTANTS_APPLE_LOG_PROFILE — the shared curve behind Apple Log and
// Apple Log 2)
constexpr float kAppleR0 = -0.05641088f;
constexpr float kAppleRt = 0.01f;
constexpr float kAppleSigma = 47.28711236f;
constexpr float kAppleBeta = 0.00964052f;
constexpr float kAppleGamma = 0.08550479f;
constexpr float kAppleDelta = 0.69336945f;
constexpr float kApplePt = kAppleSigma * (kAppleRt - kAppleR0)
                             * (kAppleRt - kAppleR0); // P_t = sigma*(Rt-R0)^2

// Panasonic V-Log (CSC.Panasonic.ACES_to_VLog_VGamut.ctl, lin_to_VLog)
constexpr float kVLogCut = 0.01f;
constexpr float kVLogLinA = 5.6f;
constexpr float kVLogLinB = 0.125f;
constexpr float kVLogC = 0.241514f;
constexpr float kVLogD = 0.598206f;
constexpr float kVLogE = 0.00873f;
constexpr float kVLogEncCut = kVLogLinA * kVLogCut + kVLogLinB; // 0.181

// Fujifilm F-Log (transfer_functions/fujifilm_f_log.py, CONSTANTS_FLOG)
constexpr float kFLogCut1 = 0.00089f;
constexpr float kFLogCut2 = 0.100537775223865f;
constexpr float kFLogA = 0.555556f;
constexpr float kFLogB = 0.009468f;
constexpr float kFLogC = 0.344676f;
constexpr float kFLogD = 0.790453f;
constexpr float kFLogE = 8.735631f;
constexpr float kFLogF = 0.092864f;

// Cineon (transfer_functions/cineon.py): black_offset =
// 10 ** ((95 - 685) / 300) = 0.0107977516232771 (pinned derivation; the
// constant is computed at compile time from the pinned formula)
constexpr float kCineonBlackOffset =
    0.0107977516232771f; // 10^((95-685)/300), pinned doctest value

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

// ARRI Wide Gamut 3 RGB->XYZ (datasets/arri.py,
// MATRIX_ARRI_WIDE_GAMUT_3_TO_XYZ, verbatim).
const float kMAwg3ToXyz[9] = {
    0.638008f, 0.214704f, 0.097744f,
    0.291954f, 0.823841f, -0.115795f,
    0.002798f, -0.067034f, 1.153294f,
};

// S-Gamut3.Cine RGB->XYZ (datasets/sony.py,
// MATRIX_S_GAMUT3_CINE_TO_XYZ, verbatim).
const float kMSg3cToXyz[9] = {
    0.5990839208f, 0.2489255161f, 0.1024464902f,
    0.2150758201f, 0.8850685017f, -0.1001443219f,
    -0.0320658495f, -0.0276583907f, 1.1487819910f,
};

// ---- Primaries (x, y) ----
const float kP2020[3][2] = {
    {0.7080f, 0.2920f}, {0.1700f, 0.7970f}, {0.1310f, 0.0460f},
};
// AP1 / ACEScg (datasets/aces.py, AP1)
const float kPAp1[3][2] = {
    {0.71300f, 0.29300f}, {0.16500f, 0.83000f}, {0.12800f, 0.04400f},
};
// Canon Cinema Gamut (datasets/canon_cinema_gamut.py)
const float kPCinemaGamut[3][2] = {
    {0.7400f, 0.2700f}, {0.1700f, 1.1400f}, {0.0800f, -0.1000f},
};
// Apple Wide Gamut (CSC.Apple.ACES_to_AppleLog2.ctl, APPLE_WIDE_GAMUT_PRI)
const float kPAppleWg[3][2] = {
    {0.725f, 0.301f}, {0.221f, 0.814f}, {0.068f, -0.076f},
};
// Panasonic V-Gamut (CSC.Panasonic.ACES_to_VLog_VGamut.ctl,
// PANASONIC_VGAMUT_PRI)
const float kPVgamut[3][2] = {
    {0.730f, 0.280f}, {0.165f, 0.840f}, {0.100f, -0.030f},
};

// ---- White points (XYZ, Y = 1). D65 chromaticity (0.3127, 0.3290) from
// the ACES CTLs pin; the ACES white (0.32168, 0.33767) ~D60 is the AP0
// white in the CTLs and the ACEScg/AP1 dataset white — used by tests to
// prove the CAT. All gamuts are D65 except ACEScg (ACES white). ----
void xyzFromChromaticity(float x, float y, float out[3]) {
    out[0] = x / y;
    out[1] = 1.0f;
    out[2] = (1.0f - x - y) / y;
}

void d65Xyz(float out[3]) { xyzFromChromaticity(0.3127f, 0.3290f, out); }

void acesXyz(float out[3]) { xyzFromChromaticity(0.32168f, 0.33767f, out); }

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
        case GamutId::Acescg: {
            // AP1 primaries + ACES white: npm-derived, the same way the
            // pinned ACES dataset derives MATRIX_AP1_TO_XYZ.
            float w[3];
            acesXyz(w);
            npm(kPAp1, w, out);
            return out;
        }
        case GamutId::ArriWideGamut3:
            std::memcpy(out.m, kMAwg3ToXyz, sizeof(out.m));
            return out;
        case GamutId::SGamut3Cine:
            std::memcpy(out.m, kMSg3cToXyz, sizeof(out.m));
            return out;
        case GamutId::CinemaGamut: {
            float w[3];
            d65Xyz(w);
            npm(kPCinemaGamut, w, out);
            return out;
        }
        case GamutId::AppleWideGamut: {
            float w[3];
            d65Xyz(w);
            npm(kPAppleWg, w, out);
            return out;
        }
        case GamutId::VGamut: {
            float w[3];
            d65Xyz(w);
            npm(kPVgamut, w, out);
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
        case TransferId::Acescct:
            // Pinned: lin <= X_BRK -> A*lin + B; else
            // (log2(lin) + 9.72) / 17.52.
            return linear <= kCctXBrk
                ? kCctA * linear + kCctB
                : (std::log2(linear) + 9.72f) / 17.52f;
        case TransferId::ArriLogC3:
            // Pinned SUP 3.x EI 800: t = c*log10(a*x + b) + d above the
            // cut, e*x + f below (negatives included).
            return linear > kLogC3Cut
                ? kLogC3C * std::log10(kLogC3A * linear + kLogC3B) + kLogC3D
                : kLogC3E * linear + kLogC3F;
        case TransferId::SonyLog3:
            // Pinned (in_reflection=True): the log branch above 0.01125,
            // the linear-to-code-value branch below (negatives included),
            // in the pinned op order.
            return linear >= kSlog3Cut
                ? (420.0f
                   + std::log10((linear + 0.01f) / (0.18f + 0.01f)) * 261.5f)
                      / 1023.0f
                : (linear * (171.2102946929f - 95.0f) / 0.01125000f + 95.0f)
                      / 1023.0f;
        case TransferId::CanonLog3:
            // Pinned CTL curve verbatim (the main() /0.9 wrapper is NOT
            // part of the curve — see Color.h provenance).
            if (linear < -kCLog3Cut)
                return kCLog3ASub * std::log10(1.0f - kCLog3K * linear)
                       + kCLog3BSub;
            if (linear <= kCLog3Cut)
                return kCLog3LinA * linear + kCLog3LinB;
            return kCLog3ASup * std::log10(kCLog3K * linear + 1.0f)
                   + kCLog3BSup;
        case TransferId::AppleLog:
            // Pinned: R >= R_t -> gamma*log2(R + beta) + delta;
            // R_0 <= R < R_t -> sigma*(R - R_0)^2; R < R_0 -> 0 (the
            // curve's own floor — negatives are not reversible here).
            if (linear >= kAppleRt)
                return kAppleGamma * std::log2(linear + kAppleBeta)
                       + kAppleDelta;
            if (linear >= kAppleR0)
                return kAppleSigma * (linear - kAppleR0) * (linear - kAppleR0);
            return 0.0f;
        case TransferId::VLog:
            // Pinned CTL encode: linear below the cut, log above.
            return linear < kVLogCut
                ? kVLogLinA * linear + kVLogLinB
                : kVLogC * std::log10(linear + kVLogE) + kVLogD;
        case TransferId::FujifilmFLog:
            // Pinned: below cut1 the linear branch (negatives included),
            // above it c*log10(a*x + b) + d.
            return linear < kFLogCut1
                ? kFLogE * linear + kFLogF
                : kFLogC * std::log10(kFLogA * linear + kFLogB) + kFLogD;
        case TransferId::Cineon:
            // Pinned: (685 + 300*log10(x*(1-bo) + bo)) / 1023.
            return (685.0f
                    + 300.0f
                          * std::log10(
                              linear * (1.0f - kCineonBlackOffset)
                              + kCineonBlackOffset))
                   / 1023.0f;
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
        case TransferId::Acescct:
            // Pinned: V > Y_BRK -> 2 ** (V*17.52 - 9.72); else (V-B)/A.
            return encoded > kCctYBrk
                ? std::pow(2.0f, encoded * 17.52f - 9.72f)
                : (encoded - kCctB) / kCctA;
        case TransferId::ArriLogC3:
            // Pinned inverse: above e*cut + f, (10^((t-d)/c) - b)/a;
            // below, (t-f)/e.
            return encoded > kLogC3E * kLogC3Cut + kLogC3F
                ? (std::pow(10.0f, (encoded - kLogC3D) / kLogC3C) - kLogC3B)
                      / kLogC3A
                : (encoded - kLogC3F) / kLogC3E;
        case TransferId::SonyLog3:
            // Pinned inverse: V >= 171.2102946929/1023 ->
            // 10^((V*1023 - 420)/261.5) * 0.19 - 0.01; else the linear
            // branch inverse, in the pinned op order.
            return encoded >= kSlog3EncCut
                ? std::pow(10.0f, (encoded * 1023.0f - 420.0f) / 261.5f)
                      * (0.18f + 0.01f) - 0.01f
                : (encoded * 1023.0f - 95.0f) * 0.01125000f
                      / (171.2102946929f - 95.0f);
        case TransferId::CanonLog3:
            // Derived inverse (the CTL pins only the encode): compare
            // against the ENCODED cuts (the linear branch's values at
            // ±0.014), invert each branch.
            if (encoded < kCLog3EncCutLo)
                return (1.0f
                        - std::pow(10.0f,
                                   (encoded - kCLog3BSub) / kCLog3ASub))
                       / kCLog3K;
            if (encoded <= kCLog3EncCutHi)
                return (encoded - kCLog3LinB) / kCLog3LinA;
            return (std::pow(10.0f, (encoded - kCLog3BSup) / kCLog3ASup)
                    - 1.0f)
                   / kCLog3K;
        case TransferId::AppleLog:
            // Pinned inverse: P >= P_t -> 2^((P - delta)/gamma) - beta;
            // 0 <= P < P_t -> sqrt(P/sigma) + R_0; P < 0 -> R_0 (the
            // encode floor's inverse — the pinned decode returns R_0).
            if (encoded >= kApplePt)
                return std::pow(2.0f, (encoded - kAppleDelta) / kAppleGamma)
                       - kAppleBeta;
            if (encoded >= 0.0f)
                return std::sqrt(encoded / kAppleSigma) + kAppleR0;
            return kAppleR0;
        case TransferId::VLog:
            // Derived inverse (the CTL pins only the encode): the
            // encoded cut is the linear branch's value at 0.01 = 0.181.
            return encoded < kVLogEncCut
                ? (encoded - kVLogLinB) / kVLogLinA
                : std::pow(10.0f, (encoded - kVLogD) / kVLogC) - kVLogE;
        case TransferId::FujifilmFLog:
            // Pinned inverse: V < cut2 -> (V - f)/e; else
            // (10^((V - d)/c))/a - b/a.
            return encoded < kFLogCut2
                ? (encoded - kFLogF) / kFLogE
                : std::pow(10.0f, (encoded - kFLogD) / kFLogC) / kFLogA
                      - kFLogB / kFLogA;
        case TransferId::Cineon:
            // Pinned: (10^((1023*V - 685)/300) - bo) / (1 - bo).
            return (std::pow(10.0f, (1023.0f * encoded - 685.0f) / 300.0f)
                    - kCineonBlackOffset)
                   / (1.0f - kCineonBlackOffset);
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
    // All gamuts are D65 except ACEScg, whose white is the ACES
    // chromaticity (0.32168, 0.33767) ~D60 — the AP0/AP1 dataset white.
    if (g == GamutId::Acescg) {
        acesXyz(out);
        return;
    }
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
            case GamutId::Acescg: return 'A';
            case GamutId::ArriWideGamut3: return 'W';
            case GamutId::SGamut3Cine: return 'G';
            case GamutId::CinemaGamut: return 'C';
            case GamutId::AppleWideGamut: return 'P';
            case GamutId::VGamut: return 'V';
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
            case TransferId::Acescct: return 'c';
            case TransferId::ArriLogC3: return 'a';
            case TransferId::SonyLog3: return 'y';
            case TransferId::CanonLog3: return 'n';
            case TransferId::AppleLog: return 'p';
            case TransferId::VLog: return 'v';
            case TransferId::FujifilmFLog: return 'f';
            case TransferId::Cineon: return 'o';
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

void applyManagedLutStage(ImageF& img, const Lut* lut,
                          const CompiledLutBinding* binding,
                          int tonemapMode, float lutStrength) {
    // Legacy pair, verbatim: the exact statements the pre-management
    // stage 23/24 sequence ran, so every nullptr-binding call site, every
    // LegacyLinear709 record, and every passthrough binding stay
    // byte-identical to the old pipeline (locked by the golden hashes).
    const auto legacyPair = [&]() {
        if (tonemapMode != 0)
            tonemapFrame(img, tonemapMode);
        if (lut != nullptr && lutStrength != 0.0f)
            applyLut(img, *lut, lutStrength);
    };

    if (binding == nullptr || lut == nullptr
        || binding->interp.role == LutRole::LegacyLinear709) {
        legacyPair();
        return;
    }

    // Strength 0 (or NaN): the LUT is bypassed, so the whole stage is the
    // legacy tonemap alone — byte-identical to the un-LUT'd chain.
    if (!(lutStrength > 0.0f)) {
        if (tonemapMode != 0)
            tonemapFrame(img, tonemapMode);
        return;
    }

    switch (binding->interp.role) {
        case LutRole::LegacyLinear709:
            break; // unreachable (handled above)

        case LutRole::DisplayLook: {
            // Render first, then the look on the rendered image. The
            // strength blend happens in the rendered reference.
            if (tonemapMode != 0)
                tonemapFrame(img, tonemapMode);
            if (binding->passthroughEncodings) {
                // Linear/Linear 709/709: the managed chain degenerates to
                // the legacy sampler; route to applyLut so the numbers are
                // byte-identical to the legacy stage at this point.
                applyLut(img, *lut, lutStrength);
            } else {
                evaluateManagedLut(img, *lut, *binding, lutStrength);
            }
            return;
        }

        case LutRole::SceneLook: {
            // The look enters the scene: LUT on the scene-linear image
            // first, then the render applies ONCE, after. ACES/AgX never
            // runs on top of the LUT's own output.
            if (binding->passthroughEncodings) {
                applyLut(img, *lut, lutStrength); // degenerate: legacy stage
            } else {
                evaluateManagedLut(img, *lut, *binding, lutStrength);
            }
            if (tonemapMode != 0)
                tonemapFrame(img, tonemapMode);
            return;
        }

        case LutRole::SceneToDisplay: {
            // The LUT branch is ALREADY rendered output — tonemap never
            // applies on top of it. Full strength is the pure LUT branch
            // alone (no baseline, no copy); strength in (0,1) mixes the
            // two branches in the rendered reference:
            //   out = lut(px)*s + tonemap(px)*(1-s)
            // computed per pixel at this shared stage point — one frame
            // copy, no second develop. The baseline branch is the legacy
            // chain's stage 23 (tonemap of the scene image), so a mixed
            // grade is exactly (1-s) of the un-LUT'd chain plus s of the
            // conversion.
            const float s = std::min(lutStrength, 1.0f);
            if (s >= 1.0f) {
                evaluateManagedLut(img, *lut, *binding, 1.0f);
                return;
            }
            ImageF baseline = img; // the scene image, pre-tonemap
            if (tonemapMode != 0)
                tonemapFrame(baseline, tonemapMode);
            const size_t pixels = static_cast<size_t>(img.w) * img.h;
            if (pixels == 0 || img.rgb.size() != pixels * 3)
                return; // degenerate frame: nothing to mix
            for (size_t p = 0; p < pixels; ++p) {
                const float* scene = &img.rgb[p * 3];
                float lutPx[3];
                evaluateManagedLutPixel(*lut, *binding, scene, lutPx);
                float* out = &img.rgb[p * 3];
                for (int c = 0; c < 3; ++c)
                    out[c] = lutPx[c] * s + baseline.rgb[p * 3 + c] * (1.0f - s);
            }
            return;
        }
    }
}

} // namespace rawedit
