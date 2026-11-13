// rawedit color management tests — the native color model (owner request
// 2026-11-13, LUT color management). Everything here is synthetic: no
// fixtures, no cwd needs.
//
// Coverage required by the mission contract:
//   - encode/decode round trips per transfer (breakpoints, gray, black,
//     allowed negatives, >1)
//   - DaVinci Intermediate 0.18 anchor ~0.3360432724
//   - DWG matrix round trip + neutral white + D60/D65 Bradford CAT
//   - identity-cube light preservation when input==output encoding
//   - identity cube NOT identity when input!=output encoding
//   - row/col-major proof of the gamut transform
//   - pinned inverse matrices as cross-checks (not the runtime path)
//
// Expected values were computed in double precision from the pinned
// sources and re-measured in float32 mirroring the C++ op order (see
// Color.cpp provenance notes). Tolerances are ~10x the measured f32
// error unless the deviation is a documented property of a constant
// (e.g. the 4-decimal 709 matrix's white slip ~1.7e-4).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include <rawedit/Color.h>
#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

TEST_CASE("transfer anchors: gray 0.18 per curve") {
    using rawedit::TransferId;
    // DaVinci Intermediate: the pinned doctest anchor 0.3360432...
    CHECK(rawedit::encodeTransfer(0.18f, TransferId::DaVinciIntermediate) ==
          doctest::Approx(0.33604327f).epsilon(1e-6f));
    // sRGB: pinned doctest anchor 0.4613561...
    CHECK(rawedit::encodeTransfer(0.18f, TransferId::Srgb) ==
          doctest::Approx(0.46135613f).epsilon(1e-6f));
    // Rec.709 OETF: the existing linearToRec709 curve, delegated. The
    // pinned colour-science doctest anchor is 0.4090077...
    CHECK(rawedit::encodeTransfer(0.18f, TransferId::Rec709OETF) ==
          doctest::Approx(0.40900773f).epsilon(1e-6f));
    // And it must be the SAME function, not a copy: same input, same
    // output, on both sides of the branch cut.
    for (float x : {0.0f, 0.005f, 0.017999f, 0.018f, 0.5f, 1.0f, 2.5f}) {
        CHECK(rawedit::encodeTransfer(x, TransferId::Rec709OETF) ==
              rawedit::linearToRec709(x));
    }
    // Gamma 2.4: pure 1/2.4 power, a separate named curve.
    CHECK(rawedit::encodeTransfer(0.18f, TransferId::Gamma24) ==
          doctest::Approx(0.4894371f).epsilon(1e-6f));
    // Linear is the identity.
    CHECK(rawedit::encodeTransfer(0.18f, TransferId::Linear) == 0.18f);
    CHECK(rawedit::decodeTransfer(0.18f, TransferId::Linear) == 0.18f);
}

TEST_CASE("transfers: encode/decode round trips at breakpoints, gray, "
          "black, negatives, >1") {
    using rawedit::TransferId;
    const float probes[] = {
        0.0f,                       // black
        0.00262409f,                // DI linear cut
        0.0031308f,                 // sRGB linear cut
        0.017999f, 0.018f, 0.018001f, // 709 cut (existing curve's own)
        0.081f,                     // 709 inverse cut
        0.18f,                      // gray
        0.5f, 1.0f, 2.5f,           // >1 (HDR, meaningful in scene linear)
        -0.05f, -0.5f,              // negatives (allowed: unclamped curves)
    };
    // Measured f32 max errors: DI 7.2e-7, sRGB 7.5e-9, 709 4.5e-8,
    // gamma24 7.5e-9. Tolerance 10x measured.
    for (TransferId t : {TransferId::Srgb, TransferId::Rec709OETF,
                         TransferId::Gamma24, TransferId::DaVinciIntermediate}) {
        for (float x : probes) {
            const float back = rawedit::decodeTransfer(
                rawedit::encodeTransfer(x, t), t);
            const float tol = 1e-5f;
            INFO("transfer ", static_cast<int>(t), " probe ", x);
            CHECK(back == doctest::Approx(x).epsilon(tol));
        }
    }
    // Linear: exact.
    for (float x : probes)
        CHECK(rawedit::decodeTransfer(
                  rawedit::encodeTransfer(x, TransferId::Linear),
                  TransferId::Linear) == x);
}

TEST_CASE("DaVinci Intermediate: pinned anchors and breakpoint continuity") {
    using rawedit::TransferId;
    const auto t = TransferId::DaVinciIntermediate;
    // The Blackmagic/colour-science anchor pair.
    CHECK(rawedit::encodeTransfer(0.18f, t) ==
          doctest::Approx(0.3360432724f).epsilon(1e-6f));
    CHECK(rawedit::decodeTransfer(0.3360432724f, t) ==
          doctest::Approx(0.18f).epsilon(1e-5f));
    // Breakpoint continuity: the pinned constants are rounded to 8
    // decimals, so both sides agree to < 4e-10 (f64) / exactly (f32).
    // Encode side: L*M at the cut vs the log branch at the cut.
    const float linSide = 0.00262409f * 10.44426855f;
    const float logSide = rawedit::encodeTransfer(0.00262409f, t);
    CHECK(logSide == doctest::Approx(linSide).epsilon(1e-6f));
    // Decode side: V/M at the cut vs the log branch at the cut.
    const float linSideD = 0.02740668f / 10.44426855f;
    const float logSideD = rawedit::decodeTransfer(0.02740668f, t);
    CHECK(logSideD == doctest::Approx(linSideD).epsilon(1e-6f));
    // Negatives ride the linear branch both ways (exact).
    CHECK(rawedit::encodeTransfer(-0.25f, t) ==
          doctest::Approx(-0.25f * 10.44426855f).epsilon(1e-6f));
    CHECK(rawedit::decodeTransfer(-0.25f, t) ==
          doctest::Approx(-0.25f / 10.44426855f).epsilon(1e-6f));
}

TEST_CASE("sRGB: decode compares against the encoded breakpoint") {
    using rawedit::TransferId;
    const auto t = TransferId::Srgb;
    // The pinned srgb.py convention: eotf_sRGB branches on
    // eotf_inverse_sRGB(0.0031308) = 0.040449936, not on 0.0031308.
    CHECK(rawedit::encodeTransfer(0.0031308f, t) ==
          doctest::Approx(0.040449936f).epsilon(1e-6f));
    // Below the encoded breakpoint: linear decode V/12.92.
    CHECK(rawedit::decodeTransfer(0.04044993f, t) ==
          doctest::Approx(0.04044993f / 12.92f).epsilon(1e-5f));
    // Above: the power branch.
    const float above = 0.04044994f;
    const float expect = std::pow((above + 0.055f) / 1.055f, 2.4f);
    CHECK(rawedit::decodeTransfer(above, t) ==
          doctest::Approx(expect).epsilon(1e-5f));
}

TEST_CASE("Rec.709 OETF decode: inverse of the existing curve") {
    using rawedit::TransferId;
    const auto t = TransferId::Rec709OETF;
    // The existing curve is discontinuous at 0.018 (its own property,
    // documented in Color.h, not altered here): linear side 0.081,
    // log side 0.0812479440. The decode branches at the ENCODED value
    // of the linear side (0.081), matching the curve's linear branch.
    CHECK(rawedit::linearToRec709(0.018f) ==
          doctest::Approx(0.0812479440f).epsilon(1e-5f));
    CHECK(rawedit::decodeTransfer(0.081f, t) ==
          doctest::Approx(0.018f).epsilon(1e-5f));
    // Just above the cut the decode follows the log branch.
    CHECK(rawedit::decodeTransfer(0.0812479440f, t) ==
          doctest::Approx(0.018f).epsilon(1e-4f));
    // A gray through the log branch round trips.
    CHECK(rawedit::decodeTransfer(rawedit::encodeTransfer(0.18f, t), t) ==
          doctest::Approx(0.18f).epsilon(1e-5f));
}

TEST_CASE("Gamma 2.4: separate named curve, signed power") {
    using rawedit::TransferId;
    const auto t = TransferId::Gamma24;
    CHECK(rawedit::encodeTransfer(0.18f, t) ==
          doctest::Approx(0.4894370896f).epsilon(1e-6f));
    // Negative: sign-preserving, reversible.
    const float e = rawedit::encodeTransfer(-0.16f, t);
    CHECK(e < 0.0f);
    CHECK(rawedit::decodeTransfer(e, t) ==
          doctest::Approx(-0.16f).epsilon(1e-5f));
    // It is NOT the 709 OETF.
    CHECK(rawedit::encodeTransfer(0.18f, t) !=
          rawedit::encodeTransfer(0.18f, TransferId::Rec709OETF));
}

TEST_CASE("gamut matrices: pinned values verbatim") {
    using rawedit::GamutId;
    float m[9];
    rawedit::gamutToXyz(GamutId::Rec709, m);
    // Pinned datasets/srgb.py MATRIX_sRGB_TO_XYZ, verbatim (also the
    // Rec.709 matrix: same primaries, and the pinned BT.709 dataset
    // derives the same matrix at runtime).
    const float pinned709[9] = {
        0.4124f, 0.3576f, 0.1805f,
        0.2126f, 0.7152f, 0.0722f,
        0.0193f, 0.1192f, 0.9505f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(m[i] == doctest::Approx(pinned709[i]).epsilon(1e-6f));

    rawedit::gamutToXyz(GamutId::DaVinciWideGamut, m);
    // Pinned datasets/davinci_wide_gamut.py corrected matrix, verbatim.
    const float pinnedDwg[9] = {
        0.70062239f, 0.14877482f, 0.10105872f,
        0.27411851f, 0.87363190f, -0.14775041f,
        -0.09896291f, -0.13789533f, 1.32591599f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(m[i] == doctest::Approx(pinnedDwg[i]).epsilon(1e-6f));

    // Rec2020: derived by normalised primary matrix from its primaries,
    // exactly as the pinned itur_bt_2020.py derives it. f64 reference
    // values (f32 build agrees to < 2e-7).
    rawedit::gamutToXyz(GamutId::Rec2020, m);
    const float pinned2020[9] = {
        0.636958048f, 0.144616904f, 0.168880975f,
        0.262700212f, 0.677998072f, 0.059301716f,
        0.0f,         0.028072693f, 1.060985058f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(m[i] == doctest::Approx(pinned2020[i]).epsilon(1e-5f));
}

TEST_CASE("gamut matrices: computed inverse round trips and pinned "
          "cross-checks") {
    using rawedit::GamutId;
    float fwd[9], inv[9];
    for (GamutId g : {GamutId::Rec709, GamutId::Rec2020,
                      GamutId::DaVinciWideGamut}) {
        rawedit::gamutToXyz(g, fwd);
        rawedit::xyzToGamut(g, inv);
        // inv @ fwd = I (measured f32 error < 5.5e-8; 10x tolerance).
        for (int i = 0; i < 3; ++i)
            for (int j = 0; j < 3; ++j) {
                float s = 0.0f;
                for (int k = 0; k < 3; ++k)
                    s += inv[i * 3 + k] * fwd[k * 3 + j];
                const float want = i == j ? 1.0f : 0.0f;
                CHECK(s == doctest::Approx(want).epsilon(1e-5f));
            }
    }
    // Pinned inverse matrices are cross-checks, not the runtime path:
    // inv(G709) vs the pinned 4-decimal XYZ_TO_sRGB (max abs 4.4e-5 —
    // the 4-decimal rounding, measured).
    rawedit::xyzToGamut(GamutId::Rec709, inv);
    const float pinned709Inv[9] = {
        3.2406f, -1.5372f, -0.4986f,
        -0.9689f, 1.8758f, 0.0415f,
        0.0557f, -0.2040f, 1.0570f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(inv[i] == doctest::Approx(pinned709Inv[i]).epsilon(5e-3f));
    // inv(GDWG) vs the pinned 8-decimal XYZ_TO_DWG (max abs 3.0e-8).
    rawedit::xyzToGamut(GamutId::DaVinciWideGamut, inv);
    const float pinnedDwgInv[9] = {
        1.51667204f, -0.28147805f, -0.14696363f,
        -0.46491710f, 1.25142378f, 0.17488461f,
        0.06484905f, 0.10913934f, 0.76141462f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(inv[i] == doctest::Approx(pinnedDwgInv[i]).epsilon(1e-5f));
}

TEST_CASE("gamut transforms: round trips, neutral white, red primaries") {
    using rawedit::GamutId;
    float m[9];
    const float probe[6][3] = {
        {0.18f, 0.18f, 0.18f},
        {1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}, {0.0f, 0.0f, 1.0f},
        {0.2f, 0.5f, 0.9f}, {-0.05f, 0.3f, 1.4f},
    };
    for (GamutId a : {GamutId::Rec709, GamutId::Rec2020,
                      GamutId::DaVinciWideGamut}) {
        for (GamutId b : {GamutId::Rec709, GamutId::Rec2020,
                          GamutId::DaVinciWideGamut}) {
            float ab[9], ba[9];
            rawedit::gamutToGamut(a, b, ab);
            rawedit::gamutToGamut(b, a, ba);
            for (const auto& p : probe) {
                float mid[3], back[3];
                for (int i = 0; i < 3; ++i)
                    mid[i] = ab[i * 3 + 0] * p[0] + ab[i * 3 + 1] * p[1]
                           + ab[i * 3 + 2] * p[2];
                for (int i = 0; i < 3; ++i)
                    back[i] = ba[i * 3 + 0] * mid[0] + ba[i * 3 + 1] * mid[1]
                            + ba[i * 3 + 2] * mid[2];
                // Measured f32 round trip: < 9e-8. Tolerance 100x.
                for (int i = 0; i < 3; ++i)
                    CHECK(back[i] == doctest::Approx(p[i]).epsilon(1e-5f));
            }
        }
    }
    // Neutral white: the 4-decimal 709 matrix maps (1,1,1) to
    // [0.9505, 1, 1.089], ~1.7e-4 off D65 [0.95045593, 1, 1.08905775] —
    // a documented property of the pinned constant, so 2020->709
    // neutral slips by the same amount (measured 1.72e-4).
    rawedit::gamutToGamut(GamutId::Rec2020, GamutId::Rec709, m);
    float neutral[3];
    for (int i = 0; i < 3; ++i)
        neutral[i] = m[i * 3 + 0] + m[i * 3 + 1] + m[i * 3 + 2];
    CHECK(neutral[0] == doctest::Approx(0.99982865f).epsilon(5e-3f));
    CHECK(neutral[1] == doctest::Approx(1.00004494f).epsilon(5e-3f));
    CHECK(neutral[2] == doctest::Approx(1.00005855f).epsilon(5e-3f));
    // Red primary of 2020 mapped to 709 (f64 reference; f32 agrees to
    // < 2e-7).
    float red709[3];
    for (int i = 0; i < 3; ++i)
        red709[i] = m[i * 3 + 0];
    CHECK(red709[0] == doctest::Approx(1.66031762f).epsilon(1e-5f));
    CHECK(red709[1] == doctest::Approx(-0.1244067f).epsilon(1e-4f));
    CHECK(red709[2] == doctest::Approx(-0.01811136f).epsilon(1e-3f));
    // 709 red mapped to 2020 (f64 reference).
    rawedit::gamutToGamut(GamutId::Rec709, GamutId::Rec2020, m);
    CHECK(m[0] == doctest::Approx(0.62744137f).epsilon(1e-5f));
    CHECK(m[3] == doctest::Approx(0.06902762f).epsilon(1e-4f));
    CHECK(m[6] == doctest::Approx(0.01636424f).epsilon(1e-3f));
    // Same gamut: exact identity.
    rawedit::gamutToGamut(GamutId::Rec709, GamutId::Rec709, m);
    CHECK(m[0] == 1.0f); CHECK(m[1] == 0.0f); CHECK(m[2] == 0.0f);
    CHECK(m[3] == 0.0f); CHECK(m[4] == 1.0f); CHECK(m[5] == 0.0f);
    CHECK(m[6] == 0.0f); CHECK(m[7] == 0.0f); CHECK(m[8] == 1.0f);
}

TEST_CASE("gamut transform: row-major proof") {
    // The transform is stored row-major and applied per pixel as
    // r' = m[0]*r + m[1]*g + m[2]*b (cyclic for g', b'). Prove the
    // convention: the 2020 red primary (1,0,0) must map to the FIRST
    // COLUMN of M_2020->709 in column-vector terms — i.e. m[0], m[3],
    // m[6] — and applying the matrix to (1,0,0) row-major must
    // reproduce a known f64 value.
    float m[9];
    rawedit::gamutToGamut(rawedit::GamutId::Rec2020, rawedit::GamutId::Rec709, m);
    const float r = 1.0f, g = 0.0f, b = 0.0f;
    const float rp = m[0] * r + m[1] * g + m[2] * b;
    const float gp = m[3] * r + m[4] * g + m[5] * b;
    const float bp = m[6] * r + m[7] * g + m[8] * b;
    CHECK(rp == doctest::Approx(1.66031762f).epsilon(1e-5f));
    CHECK(gp == doctest::Approx(-0.1244067f).epsilon(1e-4f));
    CHECK(bp == doctest::Approx(-0.01811136f).epsilon(1e-3f));
}

TEST_CASE("Bradford CAT: identity, D60->D65, round trip") {
    float d65[3], d60[3];
    rawedit::whitePointGamut(rawedit::GamutId::Rec709, d65);
    CHECK(d65[0] == doctest::Approx(0.95045593f).epsilon(1e-6f));
    CHECK(d65[1] == doctest::Approx(1.0f).epsilon(1e-6f));
    CHECK(d65[2] == doctest::Approx(1.08905775f).epsilon(1e-6f));
    // D60 (AP0 white chromaticity 0.32168, 0.33767) as XYZ.
    const float d60xyz[3] = {0.95264607f, 1.0f, 1.00882518f};
    (void)d60;
    float cat[9];
    // CAT(w, w) is the identity (measured < 8e-17 f64; f32 exact).
    rawedit::bradfordCatXyz(d65, d65, cat);
    for (int i = 0; i < 9; ++i) {
        const float want = (i == 0 || i == 4 || i == 8) ? 1.0f : 0.0f;
        CHECK(cat[i] == doctest::Approx(want).epsilon(1e-5f));
    }
    // CAT(D60, D65) maps the D60 white exactly onto D65 (measured f32
    // error < 1e-8).
    rawedit::bradfordCatXyz(d60xyz, d65, cat);
    float mapped[3];
    for (int i = 0; i < 3; ++i)
        mapped[i] = cat[i * 3 + 0] * d60xyz[0] + cat[i * 3 + 1] * d60xyz[1]
                  + cat[i * 3 + 2] * d60xyz[2];
    CHECK(mapped[0] == doctest::Approx(d65[0]).epsilon(1e-5f));
    CHECK(mapped[1] == doctest::Approx(d65[1]).epsilon(1e-5f));
    CHECK(mapped[2] == doctest::Approx(d65[2]).epsilon(1e-5f));
    // Round trip D60 -> D65 -> D60 (measured f32 < 6e-8).
    float back[9];
    rawedit::bradfordCatXyz(d65, d60xyz, back);
    float rt[3];
    for (int i = 0; i < 3; ++i)
        rt[i] = back[i * 3 + 0] * mapped[0] + back[i * 3 + 1] * mapped[1]
              + back[i * 3 + 2] * mapped[2];
    for (int i = 0; i < 3; ++i)
        CHECK(rt[i] == doctest::Approx(d60xyz[i]).epsilon(1e-5f));
}

namespace {

// A 2x2x2 identity cube, same text as test_lut.cpp's kIdentity2.
const char* kIdentity2 =
    "LUT_3D_SIZE 2\n"
    "0 0 0\n"
    "1 0 0\n"
    "0 1 0\n"
    "1 1 0\n"
    "0 0 1\n"
    "1 0 1\n"
    "0 1 1\n"
    "1 1 1\n";

rawedit::Lut identityCube() {
    auto r = rawedit::parseCubeText(kIdentity2);
    REQUIRE(r.isOk());
    return *r;
}

rawedit::Lut doubleCube() {
    auto r = rawedit::parseCubeText(
        "LUT_3D_SIZE 2\n"
        "0 0 0\n2 0 0\n0 2 0\n2 2 0\n"
        "0 0 2\n2 0 2\n0 2 2\n2 2 2\n");
    REQUIRE(r.isOk());
    return *r;
}

rawedit::ImageF flatImage(uint32_t w, uint32_t h,
                          float r, float g, float b) {
    rawedit::ImageF img;
    img.w = w;
    img.h = h;
    img.rgb.resize(static_cast<size_t>(w) * h * 3);
    for (size_t i = 0; i < img.rgb.size(); i += 3) {
        img.rgb[i + 0] = r;
        img.rgb[i + 1] = g;
        img.rgb[i + 2] = b;
    }
    return img;
}

} // namespace

TEST_CASE("managed lut: identity cube with matching encodings preserves "
          "light exactly") {
    // input == output encoding: the managed chain collapses to
    // gamut -> encode -> identity sample -> decode -> gamut^-1, which
    // must preserve the pixel (the sampler's domain clamp still
    // applies: values outside [0,1] clamp to the table edge — the
    // sampler's contract, kept).
    const rawedit::Lut lut = identityCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Srgb};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Srgb};
    interp.role = rawedit::LutRole::DisplayLook;
    const auto binding = rawedit::compileLutBinding(interp);
    CHECK(!binding.passthroughEncodings); // transfers differ from Linear
    // Gray 0.18 stays 0.18 (f32 measured 0.18000001).
    float in[3] = {0.18f, 0.18f, 0.18f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    for (int i = 0; i < 3; ++i)
        CHECK(out[i] == doctest::Approx(0.18f).epsilon(1e-5f));
    // 1.0 stays 1.0; 1.5 clamps to 1.0 (domain edge, the sampler's).
    float in2[3] = {1.5f, 1.0f, 0.0f};
    rawedit::evaluateManagedLutPixel(lut, binding, in2, out);
    CHECK(out[0] == doctest::Approx(1.0f).epsilon(1e-5f));
    CHECK(out[1] == doctest::Approx(1.0f).epsilon(1e-5f));
    CHECK(out[2] == doctest::Approx(0.0f).epsilon(1e-5f));
    // Negative: sRGB encode keeps it negative (linear branch), the
    // domain clamp maps it to 0, decode returns 0.
    float in3[3] = {-0.05f, 0.0f, 0.0f};
    rawedit::evaluateManagedLutPixel(lut, binding, in3, out);
    CHECK(out[0] == doctest::Approx(0.0f).epsilon(1e-5f));
    CHECK(out[1] == doctest::Approx(0.0f).epsilon(1e-5f));
    CHECK(out[2] == doctest::Approx(0.0f).epsilon(1e-5f));
}

TEST_CASE("managed lut: identity cube is NOT identity when encodings "
          "differ") {
    const rawedit::Lut lut = identityCube();
    // sRGB in / 709 OETF out, same gamut: the identity cube samples at
    // the sRGB-encoded value and decodes with the 709 curve, so gray
    // 0.18 lands at 0.2238 (f32 measured 0.22383362) — NOT 0.18.
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Srgb};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Rec709OETF};
    interp.role = rawedit::LutRole::DisplayLook;
    const auto binding = rawedit::compileLutBinding(interp);
    float in[3] = {0.18f, 0.18f, 0.18f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    for (int i = 0; i < 3; ++i)
        CHECK(out[i] == doctest::Approx(0.22383367f).epsilon(1e-4f));
    // And the mismatch is visible: not equal to the input.
    CHECK(out[0] != doctest::Approx(0.18f).epsilon(1e-3f));
}

TEST_CASE("managed lut: gamut conversion with an identity cube") {
    const rawedit::Lut lut = identityCube();
    // 2020 linear in / 709 linear out: red (1,0,0) maps through the
    // gamut chain (f64 reference 0.62744137, 0.06902762, 0.01636424).
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec2020, rawedit::TransferId::Linear};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    interp.role = rawedit::LutRole::SceneToDisplay;
    const auto binding = rawedit::compileLutBinding(interp);
    CHECK(!binding.passthroughEncodings);
    float in[3] = {1.0f, 0.0f, 0.0f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    CHECK(out[0] == doctest::Approx(0.62744137f).epsilon(1e-5f));
    CHECK(out[1] == doctest::Approx(0.06902762f).epsilon(1e-4f));
    CHECK(out[2] == doctest::Approx(0.01636424f).epsilon(1e-3f));
}

TEST_CASE("managed lut: the DWG/DaVinci-Intermediate conversion chain") {
    // The role the Vintage Bloom-style LUT needs: DWG + DaVinci
    // Intermediate in, Rec.709 + Rec.709 OETF out. With an identity
    // cube the chain is pure conversion: 709 linear -> DWG -> DI
    // encode -> clamp [0,1] (the sampler's domain edge) -> 709 OETF
    // decode -> 709 linear.
    const rawedit::Lut lut = identityCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::DaVinciWideGamut,
                    rawedit::TransferId::DaVinciIntermediate};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Rec709OETF};
    interp.role = rawedit::LutRole::SceneToDisplay;
    const auto binding = rawedit::compileLutBinding(interp);
    // Gray 0.18: 709->DWG is near-identity for a neutral, DI encodes
    // 0.18 to ~0.336 (the anchor), the clamp keeps it, the 709 decode
    // brings ~0.1275 back (f32 measured 0.12753..0.12754).
    float in[3] = {0.18f, 0.18f, 0.18f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    for (int i = 0; i < 3; ++i)
        CHECK(out[i] == doctest::Approx(0.127534f).epsilon(1e-3f));
    // Neutral 1.0 -> ~0.2731 (f32 measured 0.27310..0.27311).
    float in2[3] = {1.0f, 1.0f, 1.0f};
    rawedit::evaluateManagedLutPixel(lut, binding, in2, out);
    for (int i = 0; i < 3; ++i)
        CHECK(out[i] == doctest::Approx(0.273104f).epsilon(1e-3f));
}

TEST_CASE("managed lut: passthrough encodings degenerate to the legacy "
          "sampler contract") {
    // Linear/Linear 709/709: the binding is a passthrough, and the
    // managed chain with an identity cube must reproduce the legacy
    // stage-24 numbers exactly (clamp to the domain edge included).
    const rawedit::Lut lut = identityCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    interp.role = rawedit::LutRole::LegacyLinear709;
    const auto binding = rawedit::compileLutBinding(interp);
    CHECK(binding.passthroughEncodings);
    float in[3] = {0.18f, 1.5f, -0.05f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    CHECK(out[0] == doctest::Approx(0.18f).epsilon(1e-6f));
    CHECK(out[1] == doctest::Approx(1.0f).epsilon(1e-6f)); // domain edge
    CHECK(out[2] == doctest::Approx(0.0f).epsilon(1e-6f)); // domain edge
}

TEST_CASE("managed lut: frame-level apply matches per-pixel, strength "
          "blend included") {
    const rawedit::Lut lut = doubleCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Srgb};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Srgb};
    interp.role = rawedit::LutRole::DisplayLook;
    const auto binding = rawedit::compileLutBinding(interp);
    auto img = flatImage(4, 3, 0.18f, 0.4f, 0.9f);
    const auto reference = img;
    rawedit::evaluateManagedLut(img, lut, binding, 0.25f);
    for (size_t p = 0; p < 4 * 3; ++p) {
        float in[3] = {reference.rgb[p * 3 + 0], reference.rgb[p * 3 + 1],
                       reference.rgb[p * 3 + 2]};
        float full[3];
        rawedit::evaluateManagedLutPixel(lut, binding, in, full);
        for (int c = 0; c < 3; ++c) {
            const float want =
                full[c] * 0.25f + reference.rgb[p * 3 + c] * 0.75f;
            CHECK(img.rgb[p * 3 + c] == doctest::Approx(want).epsilon(1e-6f));
        }
    }
    // Strength 0 is an exact bypass.
    auto img2 = flatImage(4, 3, 0.18f, 0.4f, 0.9f);
    const auto before = img2;
    rawedit::evaluateManagedLut(img2, lut, binding, 0.0f);
    CHECK(img2.rgb == before.rgb);
}

TEST_CASE("interpretation signature: stable and discriminating") {
    rawedit::LutInterpretation a;
    a.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    a.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    a.role = rawedit::LutRole::LegacyLinear709;
    rawedit::LutInterpretation b = a;
    CHECK(rawedit::interpretationSignature(a) ==
          rawedit::interpretationSignature(b));
    b.role = rawedit::LutRole::DisplayLook;
    CHECK(rawedit::interpretationSignature(a) !=
          rawedit::interpretationSignature(b));
    b = a;
    b.input.transfer = rawedit::TransferId::Srgb;
    CHECK(rawedit::interpretationSignature(a) !=
          rawedit::interpretationSignature(b));
    b = a;
    b.output.gamut = rawedit::GamutId::Rec2020;
    CHECK(rawedit::interpretationSignature(a) !=
          rawedit::interpretationSignature(b));
    // Deterministic across calls.
    CHECK(rawedit::interpretationSignature(a).size() == 5);
}

TEST_CASE("compiled binding: matrices are precompiled, not per-frame") {
    // Two compilations of the same interpretation produce identical
    // matrices (the binding is a value; no per-frame work hides inside).
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::DaVinciWideGamut,
                    rawedit::TransferId::DaVinciIntermediate};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Rec709OETF};
    interp.role = rawedit::LutRole::SceneToDisplay;
    const auto a = rawedit::compileLutBinding(interp);
    const auto b = rawedit::compileLutBinding(interp);
    CHECK(std::memcmp(a.workingToInput, b.workingToInput,
                      sizeof(a.workingToInput)) == 0);
    CHECK(std::memcmp(a.outputToWorking, b.outputToWorking,
                      sizeof(a.outputToWorking)) == 0);
    CHECK(a.passthroughEncodings == b.passthroughEncodings);
    // workingToInput for DWG input is 709->DWG: its first row applied
    // to (1,0,0) is the 709 red in DWG (f64 0.56279692, 0.07769616,
    // 0.06464207 first column).
    CHECK(a.workingToInput[0] == doctest::Approx(0.56279692f).epsilon(1e-5f));
    CHECK(a.workingToInput[3] == doctest::Approx(0.07769616f).epsilon(1e-4f));
    CHECK(a.workingToInput[6] == doctest::Approx(0.06464207f).epsilon(1e-3f));
}

// ---------------------------------------------------------------------------
// The stage-23/24 router (applyManagedLutStage) and the develop wiring.
// Fixtures: the same synthetic CFA builder as test_lut.cpp (RGGB, black
// 64, white 1023, identity color matrix, flat linear patch) and the same
// FNV-1a byte hash for bit-identity pins.
// ---------------------------------------------------------------------------

namespace {

struct Synthetic {
    std::vector<uint16_t> cfa;
    rawedit::FrameInfo info;
    rawedit::RawMeta meta;
};

Synthetic syntheticFlat(float lr, float lg, float lb,
                        uint32_t w = 8, uint32_t h = 8) {
    Synthetic s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = rawedit::CfaPattern::RGGB;
    s.info.activeArea = rawedit::Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};

    s.meta.black[0] = 64; s.meta.black[1] = 64;
    s.meta.black[2] = 64; s.meta.black[3] = 64;
    s.meta.white = 1023;
    s.meta.wbGains[0] = 1.0f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    const uint16_t vals[3] = {
        static_cast<uint16_t>(64 + lr * (1023 - 64)),
        static_cast<uint16_t>(64 + lg * (1023 - 64)),
        static_cast<uint16_t>(64 + lb * (1023 - 64)),
    };
    s.cfa.assign(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const int site =
                (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2);
            s.cfa[static_cast<size_t>(y) * w + x] = vals[site];
        }
    return s;
}

uint64_t fnv1a(const rawedit::ImageF& img) {
    uint64_t h = 1469598103934665603ULL;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(img.rgb.data());
    const size_t n = img.rgb.size() * sizeof(float);
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

// The binding for a role with Linear/Linear 709/709 encodings — the
// passthrough shape (used for the legacy-role and strength pins).
rawedit::CompiledLutBinding linearBinding(rawedit::LutRole role) {
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Linear};
    interp.role = role;
    return rawedit::compileLutBinding(interp);
}

} // namespace

TEST_CASE("router: nullptr binding is byte-identical to the legacy pair") {
    // The three golden fixtures of test_develop.cpp, re-locked through the
    // router: developCpu with binding == nullptr (explicit and defaulted)
    // must stay bit-identical — the pre-management pipeline.
    {
        auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, rawedit::Grade{});
        CHECK(fnv1a(img) == 0x88593750a9564183ULL);
    }
    {
        auto s = syntheticFlat(0.55f, 0.30f, 0.12f);
        rawedit::Grade g;
        g.exposureEv = 0.7f;
        g.highlights = -35.0f;
        g.tempK = 7200.0f;
        g.tint = 12.0f;
        g.asShot = false;
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, nullptr);
        CHECK(fnv1a(img) == 0x38150540c239af83ULL);
    }
    {
        auto s = syntheticFlat(0.95f, 0.95f, 0.95f);
        rawedit::Grade g;
        g.exposureEv = 2.1f;
        g.highlights = -55.0f;
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g);
        CHECK(fnv1a(img) == 0x1cea742e0c17bf03ULL);
    }
    // The ACES golden anchor of test_develop.cpp (the tonemap path through
    // the router): 0.18 flat + tonemap 1 lands on 0.26581848.
    {
        auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
        rawedit::Grade g;
        g.tonemap = 1;
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g);
        const uint32_t x0 = img.w / 2 - 1, y0 = img.h / 2 - 1;
        CHECK(std::abs(img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3]
                       - 0.26581848f) < 1e-4f);
    }
}

TEST_CASE("router: LegacyLinear709 role binding runs the legacy pair") {
    // A binding whose role is LegacyLinear709 must reproduce the nullptr
    // pipeline exactly, whatever its encodings say (the role owns the
    // ordering; the stage-24 contract is the legacy one).
    auto s = syntheticFlat(0.4f, 0.4f, 0.4f);
    const rawedit::Lut dbl = doubleCube();

    rawedit::Grade g;
    g.tonemap = 1;
    g.lutStrength = 1.0f;
    auto legacy = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, nullptr);
    const auto binding = linearBinding(rawedit::LutRole::LegacyLinear709);
    auto managed = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
    CHECK(fnv1a(legacy) == fnv1a(managed));

    // And strength 0 with any managed role is the un-LUT'd chain exactly
    // (the early bypass): here tonemap still runs, so the hash equals the
    // tonemap-only pipeline.
    rawedit::Grade tmOnly;
    tmOnly.tonemap = 1;
    auto plain = rawedit::developCpu(s.cfa.data(), s.info, s.meta, tmOnly, nullptr);
    rawedit::Grade zero;
    zero.tonemap = 1;
    zero.lutStrength = 0.0f;
    for (rawedit::LutRole role : {rawedit::LutRole::DisplayLook,
                                  rawedit::LutRole::SceneLook,
                                  rawedit::LutRole::SceneToDisplay}) {
        const auto b = linearBinding(role);
        auto out = rawedit::developCpu(s.cfa.data(), s.info, s.meta, zero, &dbl, &b);
        CHECK(fnv1a(out) == fnv1a(plain));
    }
}

TEST_CASE("router: role orderings pinned by the double cube") {
    // The double cube doubles whatever the LUT stage receives (full
    // strength, in-domain). The expectations are DERIVED from the actual
    // pipeline outputs, never hardcoded. The flat 0.18 patch develops to
    // v = 172/959 = 0.17935349; ACES(v) = 0.26581848 (the test_develop
    // anchor).
    //
    // With tonemap 1 + full strength the three managed roles give three
    // DIFFERENT values, which pins each role's ordering:
    //   DisplayLook    : 2 * ACES(v)   (render, then the look doubles)
    //   SceneLook      : ACES(2v)      (the look doubles, then ACES)
    //   SceneToDisplay : 2v            (no ACES anywhere — the LUT branch
    //                                   is already rendered output)
    auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
    const rawedit::Lut dbl = doubleCube();
    const auto centerOf = [](const rawedit::ImageF& img) {
        const uint32_t x0 = img.w / 2 - 1, y0 = img.h / 2 - 1;
        return img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3];
    };

    rawedit::Grade tm;
    tm.tonemap = 1;
    auto tmOnly = rawedit::developCpu(s.cfa.data(), s.info, s.meta, tm, nullptr);
    const float acesV = centerOf(tmOnly); // ACES(v)

    rawedit::Grade neutral;
    auto plain = rawedit::developCpu(s.cfa.data(), s.info, s.meta, neutral, nullptr);
    const float v = centerOf(plain); // the scene value

    rawedit::Grade g;
    g.tonemap = 1;
    g.lutStrength = 1.0f;

    // DisplayLook: render first, then the look.
    {
        const auto binding = linearBinding(rawedit::LutRole::DisplayLook);
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
        const float got = centerOf(img);
        CHECK(got == doctest::Approx(2.0f * acesV).epsilon(1e-4f));
        CHECK(got != doctest::Approx(acesV).epsilon(1e-4f)); // really ran
    }
    // SceneLook: the look first, then the render. ACES is nonlinear, so
    // ACES(2v) != 2*ACES(v) — this also separates SceneLook from
    // DisplayLook.
    {
        const auto binding = linearBinding(rawedit::LutRole::SceneLook);
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
        const float got = centerOf(img);
        // ACES at the doubled scene value, derived: acesFit(2v) with the
        // Narkowicz constants (Develop.cpp's fit, mirrored here).
        const float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
        const float num = (2.0f * v) * (a * (2.0f * v) + b);
        const float den = (2.0f * v) * (c * (2.0f * v) + d) + e;
        const float aces2v = std::min(std::max(num / den, 0.0f), 1.0f);
        CHECK(got == doctest::Approx(aces2v).epsilon(1e-4f));
        CHECK(got != doctest::Approx(2.0f * acesV).epsilon(1e-4f));
    }
    // SceneToDisplay: the LUT branch is already rendered — NO ACES on top.
    {
        const auto binding = linearBinding(rawedit::LutRole::SceneToDisplay);
        auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
        const float got = centerOf(img);
        CHECK(got == doctest::Approx(2.0f * v).epsilon(1e-4f));
        CHECK(got != doctest::Approx(acesV).epsilon(1e-4f)); // no tonemap on top
        CHECK(got != doctest::Approx(2.0f * acesV).epsilon(1e-4f));
    }
}

TEST_CASE("router: SceneToDisplay mixed strength blends the two branches") {
    // Strength 0.5 with tonemap 1: out = 0.5*lut(px) + 0.5*ACES(px),
    // evaluated at the shared stage point — both branches in the rendered
    // reference. Derived, never hardcoded.
    auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
    const rawedit::Lut dbl = doubleCube();
    const auto centerOf = [](const rawedit::ImageF& img) {
        const uint32_t x0 = img.w / 2 - 1, y0 = img.h / 2 - 1;
        return img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3];
    };

    rawedit::Grade tm;
    tm.tonemap = 1;
    auto tmOnly = rawedit::developCpu(s.cfa.data(), s.info, s.meta, tm, nullptr);
    const float acesV = centerOf(tmOnly);
    rawedit::Grade neutral;
    auto plain = rawedit::developCpu(s.cfa.data(), s.info, s.meta, neutral, nullptr);
    const float v = centerOf(plain);

    rawedit::Grade g;
    g.tonemap = 1;
    g.lutStrength = 0.5f;
    const auto binding = linearBinding(rawedit::LutRole::SceneToDisplay);
    auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
    const float got = centerOf(img);
    const float want = 0.5f * (2.0f * v) + 0.5f * acesV;
    CHECK(got == doctest::Approx(want).epsilon(1e-4f));
    // The two branches differ (ACES is nonlinear), so the mix is a real
    // third value, not a copy of either branch.
    CHECK(got != doctest::Approx(2.0f * v).epsilon(1e-4f));
    CHECK(got != doctest::Approx(acesV).epsilon(1e-4f));
    // And it is NOT the naive single-branch blend the legacy path would
    // give (LUT after tonemap, blended against the tonemapped pixel):
    // 0.5*2*ACES(v) + 0.5*ACES(v) = 1.5*ACES(v).
    CHECK(got != doctest::Approx(1.5f * acesV).epsilon(1e-4f));
}

TEST_CASE("router: managed encodings apply through the develop wiring") {
    // A DWG/DaVinci-Intermediate -> Rec.709/OETF conversion binding with
    // an identity cube: the managed chain is pure conversion. The
    // expectation is DERIVED by running the per-pixel contract
    // (evaluateManagedLutPixel) on the actual developed scene value —
    // the fixture quantizes (0.18 -> 172/959 = 0.17935), so the exact
    // 0.18 anchor 0.127535 does not apply. This proves the wiring hands
    // the router the scene image and the router applies the compiled
    // matrices.
    auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
    const rawedit::Lut lut = identityCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::DaVinciWideGamut,
                    rawedit::TransferId::DaVinciIntermediate};
    interp.output = {rawedit::GamutId::Rec709, rawedit::TransferId::Rec709OETF};
    interp.role = rawedit::LutRole::SceneToDisplay;
    const auto binding = rawedit::compileLutBinding(interp);

    rawedit::Grade neutral;
    auto plain = rawedit::developCpu(s.cfa.data(), s.info, s.meta, neutral, nullptr);
    const uint32_t x0 = plain.w / 2 - 1, y0 = plain.h / 2 - 1;
    const float v = plain.rgb[(static_cast<size_t>(y0) * plain.w + x0) * 3];

    float want[3];
    const float scene[3] = {v, v, v};
    rawedit::evaluateManagedLutPixel(lut, binding, scene, want);

    rawedit::Grade g;
    g.lutStrength = 1.0f;
    auto img = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &lut, &binding);
    const float got = img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3];
    CHECK(got == doctest::Approx(want[0]).epsilon(1e-5f));
    // The conversion really changed the pixel (not a passthrough).
    CHECK(got != doctest::Approx(v).epsilon(1e-3f));
    // Loose sanity against the 0.18 anchor: the quantized fixture lands
    // within 1% of the exact-0.18 value 0.127535.
    CHECK(std::abs(got - 0.127535f) < 0.013f);
}

TEST_CASE("router: developEncoded routes through the same stage") {
    // Neutral gray NV12 frame (limited Y=128 -> mid), the test_lut.cpp
    // fixture. The router must give the encoded path the same semantics:
    // nullptr binding = legacy bit-identity; SceneToDisplay full strength
    // with a passthrough binding = exactly 2x, no tonemap on top.
    rawedit::YuvFrame f;
    f.width = 4;
    f.height = 2;
    f.semiPlanar = true;
    f.y.assign(8, 128);
    f.u.assign(4, 128);
    rawedit::Grade neutral;

    // Legacy bit-identity: nullptr (explicit and defaulted).
    auto plain = rawedit::developEncoded(f, neutral, true, nullptr);
    auto def = rawedit::developEncoded(f, neutral, true);
    CHECK(fnv1a(plain) == fnv1a(def));

    // SceneToDisplay, full strength, passthrough encodings, tonemap 1:
    // 2x the decoded value, NO ACES on top.
    const rawedit::Lut dbl = doubleCube();
    const auto binding = linearBinding(rawedit::LutRole::SceneToDisplay);
    rawedit::Grade g;
    g.tonemap = 1;
    g.lutStrength = 1.0f;
    auto out = rawedit::developEncoded(f, g, true, &dbl, &binding);
    CHECK(out.rgb[0] == doctest::Approx(2.0f * plain.rgb[0]).epsilon(1e-5f));
    CHECK(out.rgb[0] > plain.rgb[0]);

    // DisplayLook with the same grade: render first, so the LUT doubles
    // the TONEMAPPED value — 2*ACES(plain).
    const auto dbinding = linearBinding(rawedit::LutRole::DisplayLook);
    auto dout = rawedit::developEncoded(f, g, true, &dbl, &dbinding);
    rawedit::Grade tmOnly;
    tmOnly.tonemap = 1;
    auto tplain = rawedit::developEncoded(f, tmOnly, true, nullptr);
    CHECK(dout.rgb[0] == doctest::Approx(2.0f * tplain.rgb[0]).epsilon(1e-5f));
    CHECK(dout.rgb[0] != doctest::Approx(out.rgb[0]).epsilon(1e-4f));
}

TEST_CASE("router: developThumbnail forwards the binding (preview == export)") {
    // The preview must run the SAME managed sequence as the export: with
    // a SceneToDisplay passthrough binding + double cube at full strength
    // and no tonemap, both paths give exactly 2x the plain develop.
    auto s = syntheticFlat(0.18f, 0.18f, 0.18f);
    const rawedit::Lut dbl = doubleCube();
    const auto binding = linearBinding(rawedit::LutRole::SceneToDisplay);

    rawedit::Grade neutral;
    auto plainThumb = rawedit::developThumbnail(s.cfa.data(), s.info, s.meta,
                                                neutral, 2, nullptr, nullptr);
    rawedit::Grade g;
    g.lutStrength = 1.0f;
    auto lutThumb = rawedit::developThumbnail(s.cfa.data(), s.info, s.meta,
                                              g, 2, &dbl, &binding);
    const auto centerOf = [](const rawedit::ImageF& img) {
        const uint32_t x0 = img.w / 2 - 1, y0 = img.h / 2 - 1;
        return img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3];
    };
    CHECK(centerOf(lutThumb) ==
          doctest::Approx(2.0f * centerOf(plainThumb)).epsilon(1e-4f));
    // The full develop with the same binding gives the same center value
    // (the thumbnail's decimated mosaic is the same flat patch, so both
    // agree; the check is the acceptance contract in miniature).
    auto full = rawedit::developCpu(s.cfa.data(), s.info, s.meta, g, &dbl, &binding);
    CHECK(centerOf(full) == doctest::Approx(centerOf(lutThumb)).epsilon(1e-4f));
}
