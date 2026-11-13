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

// ---- Commit (e): the priority-2 camera curves. Every expected value is
// the pinned source's own math in double precision, re-measured in float
// mirroring the C++ op order; tolerances are ~10x the measured f32 error
// unless the deviation is a documented property of the rounded pinned
// constants (branch-cut discontinuities, pinned separately below).

TEST_CASE("commit (e) anchors: gray 0.18 and black/white per camera curve") {
    using rawedit::TransferId;
    struct A { const char* name; TransferId t; float anchor; };
    const A anchors[] = {
        // ACES CTL lib_ACES.util.baked_param (cct) — f32 diff 2.2e-9.
        {"ACEScct", TransferId::Acescct, 0.4135884024924423f},
        // colour-science arri.py EI 800 row (doctest anchor) — 4.7e-9.
        {"LogC3", TransferId::ArriLogC3, 0.39100683203408376f},
        // colour-science sony.py (doctest anchor) — 4.1e-9.
        {"SLog3", TransferId::SonyLog3, 0.41055718475073316f},
        // Canon lin_to_CLog3 main() (doctest anchor) — 2.1e-8.
        {"CLog3", TransferId::CanonLog3, 0.3309579103868913f},
        // Apple CSC.Apple.AppleLog.ctl (doctest anchor) — 2.1e-10.
        {"AppleLog", TransferId::AppleLog, 0.4882724585268676f},
        // Panasonic AVC-Intra V-Log white paper — 6.6e-9.
        {"VLog", TransferId::VLog, 0.42331144876013616f},
        // Fujifilm log_encoding_FLog (doctest anchor) — 3.0e-8.
        {"FLog", TransferId::FujifilmFLog, 0.45931845866162124f},
        // Cineon: the pinned form, bo = 10^((95-685)/300) — 4.2e-9.
        {"Cineon", TransferId::Cineon, 0.4573196130854184f},
    };
    for (const auto& a : anchors) {
        INFO("anchor ", a.name);
        CHECK(rawedit::encodeTransfer(0.18f, a.t) ==
              doctest::Approx(a.anchor).epsilon(1e-6f));
    }
    // Cineon's pinned anchors at black and white (f32 diffs 6e-9/1.7e-8).
    CHECK(rawedit::encodeTransfer(0.0f, TransferId::Cineon) ==
          doctest::Approx(0.09286412512218964f).epsilon(1e-6f));
    CHECK(rawedit::encodeTransfer(1.0f, TransferId::Cineon) ==
          doctest::Approx(0.6695992179863147f).epsilon(1e-6f));
}

TEST_CASE("commit (e) round trips: breakpoints, gray, >1, allowed "
          "negatives") {
    using rawedit::TransferId;
    // Measured f32 max errors per curve (probe compiled against the real
    // Color.cpp): ACEScct 7.2e-7, LogC3 9.5e-7, SLog3 2.4e-7, CLog3 2.4e-7,
    // AppleLog 2.4e-7, VLog 4.8e-7, FLog 2.4e-7, Cineon 4.8e-7. Tolerance
    // 10x measured. Probe sets are per curve: each set straddles the
    // curve's own branch cut(s) (both sides), the gray anchor, an HDR
    // value, and the negatives its pinned policy allows.
    struct R { const char* name; TransferId t; std::vector<float> probes; };
    const R rts[] = {
        // ACEScct: linear cut 0.0078125; A*L+B carries negatives
        // (Color.h policy: reversible).
        {"ACEScct", TransferId::Acescct,
         {0.0f, 0.0078125f, 0.0078126f, 0.18f, 1.0f, 2.5f, -0.05f, -0.5f}},
        // LogC3: linear cut 0.010591 (log branch needs L > cut); E*L+F
        // carries negatives. 4.0 is the >1 HDR probe.
        {"LogC3", TransferId::ArriLogC3,
         {0.0f, 0.010591f, 0.010592f, 0.18f, 1.0f, 4.0f, -0.05f, -0.5f}},
        // SLog3: log cut 0.01125 (log branch takes L >= cut); the linear
        // branch carries negatives.
        {"SLog3", TransferId::SonyLog3,
         {0.0f, 0.01125f, 0.01126f, 0.18f, 1.0f, 2.5f, -0.05f, -0.5f}},
        // CLog3: three branches; the linear band covers [-0.014, 0.014]
        // (both encoded cuts probed from inside), and moderate negatives
        // (L < 1/14.98325) ride the negative log branch.
        {"CLog3", TransferId::CanonLog3,
         {0.0f, -0.014f, -0.0139f, 0.0139f, 0.014f, 0.18f, 1.0f, 2.5f,
          -0.05f, -0.5f}},
        // AppleLog: negatives below R_0 (-0.05641088) are NOT reversible
        // (the pinned encode clips them to 0 — Color.h policy), so the
        // negative probes ride the sqrt branch: R_0 itself and above.
        {"AppleLog", TransferId::AppleLog,
         {0.0f, 0.01f, 0.010001f, 0.18f, 1.0f, 2.5f, -0.05641088f, -0.03f}},
        // VLog: linear cut 0.01 (log branch takes L >= cut); 5.6*L+0.125
        // carries negatives.
        {"VLog", TransferId::VLog,
         {0.0f, 0.01f, 0.010001f, 0.18f, 1.0f, 2.5f, -0.05f, -0.5f}},
        // FLog: linear cut 0.00089; E*L+F carries negatives. 0.00089
        // itself is NOT probed: in f32 its encode lands 3 ulps above the
        // decode cut (log branch, 4e-9 round trip), but that is a
        // knife-edge the band test pins separately — see below.
        {"FLog", TransferId::FujifilmFLog,
         {0.0f, 0.0005f, 0.001f, 0.18f, 1.0f, 2.5f, -0.05f, -0.0225f}},
        // Cineon: L >= -bo/(1-bo) keeps the log argument positive; -0.005
        // is inside that range.
        {"Cineon", TransferId::Cineon,
         {0.0f, 0.18f, 1.0f, 2.5f, -0.005f}},
    };
    const float tol = 1e-5f;
    for (const auto& r : rts) {
        INFO("round trip ", r.name);
        for (float x : r.probes) {
            INFO("probe ", x);
            const float back = rawedit::decodeTransfer(
                rawedit::encodeTransfer(x, r.t), r.t);
            CHECK(back == doctest::Approx(x).epsilon(tol));
        }
    }
}

TEST_CASE("commit (e) branch cuts: continuity or documented discontinuity, "
          "per curve") {
    using rawedit::TransferId;
    // Each check recomputes BOTH branch values at the cut from the pinned
    // constants (the same arithmetic Color.cpp uses), then pins the
    // measured gap. "Continuous" here means the f32 recomputation agrees
    // within the f32 noise floor.
    {
        // ACEScct: cut 0.0078125. Linear side A*x+B, log side
        // (log2(x)+9.72)/17.52. f64 gap 1.7e-16; f32 recomputation exact.
        const float lin = 10.5402377416545f * 0.0078125f
                        + 0.0729055341958355f;
        const float log = (std::log2(0.0078125f) + 9.72f) / 17.52f;
        CHECK(lin == doctest::Approx(log).epsilon(1e-6f));
        // The decode cut is the LINEAR branch's value at the cut (the
        // encode's own breakpoint convention).
        CHECK(rawedit::decodeTransfer(0.1552511415525113f,
                                      TransferId::Acescct) ==
              doctest::Approx(0.0078125f).epsilon(1e-5f));
    }
    {
        // LogC3: cut 0.010591. Linear side E*x+F, log side
        // C*log10(A*x+B)+D. The pinned EI 800 constants are rounded, so
        // the branches are ~2.5e-7 discontinuous (f64 2.5017e-7 —
        // measured; the curve's own property, documented in Color.h, not
        // altered). Pin the measured gap, not a false continuity claim.
        const float lin = 5.367655f * 0.010591f + 0.092809f;
        const float log = 0.247190f * std::log10(5.555556f * 0.010591f
                                                + 0.052272f) + 0.385537f;
        // Pin the RANGE, not a false continuity claim: the jump is
        // clearly discontinuous (> 1e-7) and bounded (< 1e-6).
        const float jump = std::fabs(lin - log);
        CHECK(jump > 1e-7f);
        CHECK(jump < 1e-6f);
        // Decode cut: E*cut + F (the linear branch's value at the cut).
        CHECK(rawedit::decodeTransfer(0.149657834105f,
                                      TransferId::ArriLogC3) ==
              doctest::Approx(0.010591f).epsilon(1e-5f));
    }
    {
        // SLog3: cut 0.01125. Linear side (95 + L*(171.2102946929-95)/
        // cut)/1023, log side (420 + 261.5*log10((L+0.01)/0.19))/1023.
        // f64 gap 3.85e-14 — continuous to the f32 noise floor.
        const float lin = (0.01125f * (171.2102946929f - 95.0f) / 0.01125f
                           + 95.0f) / 1023.0f;
        const float log = (420.0f + 261.5f * std::log10(
                              (0.01125f + 0.01f) / (0.18f + 0.01f)))
                          / 1023.0f;
        CHECK(lin == doctest::Approx(log).epsilon(1e-6f));
        // The decode cut is the pinned 171.2102946929/1023.
        CHECK(rawedit::decodeTransfer(171.2102946929f / 1023.0f,
                                      TransferId::SonyLog3) ==
              doctest::Approx(0.01125f).epsilon(1e-5f));
    }
    {
        // CLog3: the two encoded cuts (the linear branch's values at
        // ±0.014). f64 discontinuities 2.56e-9 both cuts; f32
        // recomputation: 0 below (exact), 1.5e-8 above.
        const float cutLo = 1.9754798f * -0.014f + 0.12512219f;
        const float cutHi = 1.9754798f * 0.014f + 0.12512219f;
        CHECK(cutLo == doctest::Approx(0.0974654728f).epsilon(1e-6f));
        CHECK(cutHi == doctest::Approx(0.1527789072f).epsilon(1e-6f));
        // Decode at the cuts lands exactly on ±0.014 (the linear branch).
        CHECK(rawedit::decodeTransfer(cutLo, TransferId::CanonLog3) ==
              doctest::Approx(-0.014f).epsilon(1e-6f));
        CHECK(rawedit::decodeTransfer(cutHi, TransferId::CanonLog3) ==
              doctest::Approx(0.014f).epsilon(1e-6f));
    }
    {
        // AppleLog: P_t = sigma*(R_t - R_0)^2 = 0.208555315954642; the
        // log branch's value at R_t is 2.75e-9 above it (f64 measured) —
        // the sqrt branch's value at P_t is R_t to 4.4e-10.
        CHECK(rawedit::decodeTransfer(0.208555315954642f,
                                      TransferId::AppleLog) ==
              doctest::Approx(0.01f).epsilon(1e-6f));
        // Below P_t the sqrt branch takes over: decode(0) = R_0 (the
        // encode's floor — negatives NOT reversible, by design).
        CHECK(rawedit::decodeTransfer(0.0f, TransferId::AppleLog) ==
              doctest::Approx(-0.05641088f).epsilon(1e-6f));
        // And the encode clips R < R_0 to 0 (the pinned floor).
        CHECK(rawedit::encodeTransfer(-0.5f, TransferId::AppleLog) ==
              doctest::Approx(0.0f).epsilon(1e-6f));
    }
    {
        // VLog: cut 0.01. Linear side 5.6*L + 0.125 = 0.181 exactly; log
        // side 0.1809996888 — the pinned constants are rounded, so the
        // branches are ~3.1e-7 discontinuous (f64 3.1123e-7 measured;
        // documented in Color.h, not altered).
        const float lin = 5.6f * 0.01f + 0.125f;
        const float log = 0.241514f * std::log10(0.01f + 0.00873f)
                        + 0.598206f;
        // Pin the RANGE: clearly discontinuous (> 1e-7), bounded
        // (< 1e-6).
        const float jump = std::fabs(lin - log);
        CHECK(jump > 1e-7f);
        CHECK(jump < 1e-6f);
        // Decode cut: 0.181 (the linear branch's value at the cut).
        CHECK(rawedit::decodeTransfer(0.181f, TransferId::VLog) ==
              doctest::Approx(0.01f).epsilon(1e-5f));
    }
    {
        // FLog: the encode's linear branch at the cut (E*cut + F) and the
        // decode's cut2 differ by ~1.0e-4 (f64 1.0094e-4 measured) — the
        // rounded pinned constants make the band, and values in it decode
        // through the log branch (documented in Color.h, not altered).
        const float linAtCut = 8.735631f * 0.00089f + 0.092864f;
        CHECK(linAtCut == doctest::Approx(0.10063871159f).epsilon(1e-6f));
        // Pin the RANGE: the band is wide (> 1e-4) and bounded (< 2e-4).
        const float band = linAtCut - 0.100537775223865f;
        CHECK(band > 1e-4f);
        CHECK(band < 2e-4f);
        // Both sides of the band decode through the log branch:
        // dec(cut2) = 0.00089 (the cut's own linear value), and the
        // linear side decodes 1.2e-5 off it (inside the band).
        CHECK(rawedit::decodeTransfer(0.100537775223865f,
                                      TransferId::FujifilmFLog) ==
              doctest::Approx(0.00089f).epsilon(1e-5f));
        CHECK(rawedit::decodeTransfer(linAtCut,
                                      TransferId::FujifilmFLog) ==
              doctest::Approx(0.000902090f).epsilon(1e-4f));
    }
}

TEST_CASE("commit (e) negative anchors: each curve's pinned negative "
          "policy, measured") {
    using rawedit::TransferId;
    // The linear branches carry negatives exactly (f32 measured): the
    // encode is the branch's own arithmetic, so pin it verbatim.
    CHECK(rawedit::encodeTransfer(-0.05f, TransferId::Acescct) ==
          doctest::Approx(-0.45410635288688955f).epsilon(1e-6f));
    CHECK(rawedit::encodeTransfer(-0.05f, TransferId::ArriLogC3) ==
          doctest::Approx(-0.17557375f).epsilon(1e-6f));
    CHECK(rawedit::encodeTransfer(-0.05f, TransferId::SonyLog3) ==
          doctest::Approx(-0.23823306046660148f).epsilon(1e-6f));
    CHECK(rawedit::encodeTransfer(-0.05f, TransferId::VLog) ==
          doctest::Approx(-0.155f).epsilon(1e-6f));
    CHECK(rawedit::encodeTransfer(-0.0225f, TransferId::FujifilmFLog) ==
          doctest::Approx(-0.10368769749999998f).epsilon(1e-6f));
    // AppleLog's sqrt branch: R_0 <= R < R_t encodes sigma*(R-R_0)^2.
    CHECK(rawedit::encodeTransfer(-0.03f, TransferId::AppleLog) ==
          doctest::Approx(0.032984396171723926f).epsilon(1e-6f));
    // Cineon's log argument stays positive at -0.005 (>= -bo/(1-bo)).
    CHECK(rawedit::encodeTransfer(-0.005f, TransferId::Cineon) ==
          doctest::Approx(0.014844100669590491f).epsilon(1e-6f));
    // CLog3's negative log branch — the regression for the decode sign
    // fix (found by the round-trip verification, fixed before commit):
    // moderate negatives (L < 1/14.98325) must encode finite and decode
    // back to the input. f32 measured: -0.05 -> 0.038655166 ->
    // -0.050000004 (3.7e-9); -0.5 -> -0.213349983 -> -0.5 exact.
    const float e05 = rawedit::encodeTransfer(-0.05f,
                                              TransferId::CanonLog3);
    CHECK(e05 == doctest::Approx(0.038655154255723065f).epsilon(1e-6f));
    CHECK(rawedit::decodeTransfer(e05, TransferId::CanonLog3) ==
          doctest::Approx(-0.05f).epsilon(1e-6f));
    const float e5 = rawedit::encodeTransfer(-0.5f, TransferId::CanonLog3);
    CHECK(e5 == doctest::Approx(-0.21335000374006766f).epsilon(1e-6f));
    CHECK(rawedit::decodeTransfer(e5, TransferId::CanonLog3) ==
          doctest::Approx(-0.5f).epsilon(1e-6f));
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

TEST_CASE("commit (e) gamuts: pinned matrices verbatim, npm references, "
          "white points") {
    using rawedit::GamutId;
    float m[9];
    // ARRI AWG3: the pinned colour-science MATRIX_ARRI_WIDE_GAMUT_3_TO_XYZ
    // verbatim (memcpy'd at runtime). f32 diff 0 (same constants).
    rawedit::gamutToXyz(GamutId::ArriWideGamut3, m);
    const float pinnedAwg3[9] = {
        0.638008f, 0.214704f, 0.097744f,
        0.291954f, 0.823841f, -0.115795f,
        0.002798f, -0.067034f, 1.153294f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(m[i] == doctest::Approx(pinnedAwg3[i]).epsilon(1e-6f));
    // Sony S-Gamut3.Cine: the pinned MATRIX_S_GAMUT3_CINE_TO_XYZ verbatim
    // (memcpy'd at runtime). f32 diff 0.
    rawedit::gamutToXyz(GamutId::SGamut3Cine, m);
    const float pinnedSg3c[9] = {
        0.5990839208f, 0.2489255161f, 0.1024464902f,
        0.2150758201f, 0.8850685017f, -0.1001443219f,
        -0.0320658495f, -0.0276583907f, 1.1487819910f,
    };
    for (int i = 0; i < 9; ++i)
        CHECK(m[i] == doctest::Approx(pinnedSg3c[i]).epsilon(1e-6f));

    // The four npm-derived gamuts: f64 normalised-primary-matrix
    // references computed from the pinned primaries (the same
    // derivation the pinned datasets use). Measured f32 diffs: ACEScg
    // 6.6e-8, CinemaGamut 5.2e-8, AppleWG 1.0e-7, V-Gamut 2.0e-7.
    // Tolerance 2e-6 (10x the worst).
    struct G { const char* name; GamutId g; const float ref[9]; };
    const G npm[] = {
        // AP1 / ACEScg (datasets/aces.py, AP1 primaries, ACES white)
        {"ACEScg", GamutId::Acescg,
         {0.6624541811085053f, 0.13400420645643313f, 0.15618768700490782f,
          0.27222871678091454f, 0.6740817658111483f, 0.05368951740793706f,
          -0.005574649490394108f, 0.004060733528982825f,
          1.0103391003129973f}},
        // Canon Cinema Gamut (datasets/canon_cinema_gamut.py primaries)
        {"CinemaGamut", GamutId::CinemaGamut,
         {0.7160496465515205f, 0.1296834778757396f, 0.10472280262441157f,
          0.2612613575255548f, 0.8696421457549597f, -0.13090350328051448f,
          -0.009676346575020556f, -0.23648163612634865f,
          1.3352157334612476f}},
        // Apple Wide Gamut (CSC.Apple.ACES_to_AppleLog2.ctl primaries)
        {"AppleWideGamut", GamutId::AppleWideGamut,
         {0.6514914801673071f, 0.2215531132940403f, 0.07741133359032426f,
          0.27048129038670266f, 0.8160372589201303f, -0.08651854930683299f,
          -0.023363832392206848f, -0.03508759712801535f,
          1.1475091802801007f}},
        // Panasonic V-Gamut (CSC.Panasonic.ACES_to_VLog_VGamut.ctl)
        {"V-Gamut", GamutId::VGamut,
         {0.679644469878474f, 0.15221141243975453f, 0.11860004473344309f,
          0.26068555009037364f, 0.7748944633296593f, -0.035580013420032926f,
          -0.009310198217513352f, -0.004612467043628929f,
          1.1029804160210208f}},
    };
    for (const auto& g : npm) {
        INFO("npm matrix ", g.name);
        rawedit::gamutToXyz(g.g, m);
        for (int i = 0; i < 9; ++i)
            CHECK(m[i] == doctest::Approx(g.ref[i]).epsilon(2e-6f));
    }

    // White points: ACEScg's white is the ACES chromaticity
    // (0.32168, 0.33767) ~D60 (f32 measured 0.952646136/1/1.008825183;
    // diff < 9e-8); every other gamut's is D65.
    float w[3];
    rawedit::whitePointGamut(GamutId::Acescg, w);
    CHECK(w[0] == doctest::Approx(0.9526460745698463f).epsilon(1e-6f));
    CHECK(w[1] == doctest::Approx(1.0f).epsilon(1e-6f));
    CHECK(w[2] == doctest::Approx(1.0088251843515859f).epsilon(1e-6f));
    for (GamutId g : {GamutId::ArriWideGamut3, GamutId::SGamut3Cine,
                      GamutId::CinemaGamut, GamutId::AppleWideGamut,
                      GamutId::VGamut}) {
        INFO("white point ", static_cast<int>(g));
        rawedit::whitePointGamut(g, w);
        CHECK(w[0] == doctest::Approx(0.9504559270516716f).epsilon(1e-6f));
        CHECK(w[1] == doctest::Approx(1.0f).epsilon(1e-6f));
        CHECK(w[2] == doctest::Approx(1.0890577507598784f).epsilon(1e-6f));
    }
}

TEST_CASE("commit (e) gamut transforms: round trips through the Bradford "
          "CAT, all nine gamuts") {
    using rawedit::GamutId;
    const float probe[6][3] = {
        {0.18f, 0.18f, 0.18f},
        {1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}, {0.0f, 0.0f, 1.0f},
        {0.2f, 0.5f, 0.9f}, {-0.05f, 0.3f, 1.4f},
    };
    // Every gamut against Rec.709 (measured f32 round trip < 2.4e-7;
    // tolerance 40x). ACEScg exercises the D60 <-> D65 CAT both ways.
    const GamutId all[] = {
        GamutId::Rec709, GamutId::Rec2020, GamutId::DaVinciWideGamut,
        GamutId::Acescg, GamutId::ArriWideGamut3, GamutId::SGamut3Cine,
        GamutId::CinemaGamut, GamutId::AppleWideGamut, GamutId::VGamut,
    };
    for (GamutId a : all) {
        INFO("gamut round trip ", static_cast<int>(a));
        float fwd[9], back[9];
        rawedit::gamutToGamut(a, GamutId::Rec709, fwd);
        rawedit::gamutToGamut(GamutId::Rec709, a, back);
        for (const auto& p : probe) {
            float mid[3], rt[3];
            for (int i = 0; i < 3; ++i)
                mid[i] = fwd[i * 3 + 0] * p[0] + fwd[i * 3 + 1] * p[1]
                       + fwd[i * 3 + 2] * p[2];
            for (int i = 0; i < 3; ++i)
                rt[i] = back[i * 3 + 0] * mid[0] + back[i * 3 + 1] * mid[1]
                      + back[i * 3 + 2] * mid[2];
            for (int i = 0; i < 3; ++i)
                CHECK(rt[i] == doctest::Approx(p[i]).epsilon(1e-5f));
        }
    }
    // The ACEScg neutral chain: (1,1,1) in ACEScg maps to the SAME
    // 4-decimal 709 white slip as Rec2020 (measured f32
    // 0.999828696/1.000045180/1.000058413) — the pinned 4-decimal 709
    // matrix's own documented property, carried through the CAT.
    float fwd[9];
    rawedit::gamutToGamut(GamutId::Acescg, GamutId::Rec709, fwd);
    float neutral[3];
    for (int i = 0; i < 3; ++i)
        neutral[i] = fwd[i * 3 + 0] + fwd[i * 3 + 1] + fwd[i * 3 + 2];
    CHECK(neutral[0] == doctest::Approx(0.99982865f).epsilon(5e-3f));
    CHECK(neutral[1] == doctest::Approx(1.00004494f).epsilon(5e-3f));
    CHECK(neutral[2] == doctest::Approx(1.00005855f).epsilon(5e-3f));
    // Same gamut: exact identity (the shortcut, not a computed CAT).
    float m[9];
    rawedit::gamutToGamut(GamutId::Acescg, GamutId::Acescg, m);
    CHECK(m[0] == 1.0f); CHECK(m[1] == 0.0f); CHECK(m[2] == 0.0f);
    CHECK(m[3] == 0.0f); CHECK(m[4] == 1.0f); CHECK(m[5] == 0.0f);
    CHECK(m[6] == 0.0f); CHECK(m[7] == 0.0f); CHECK(m[8] == 1.0f);
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

TEST_CASE("commit (e) managed chain: identity cube, Acescg/Acescct in, "
          "Rec.709 OETF out") {
    // Gray 0.18 through the full managed chain (gamut CAT + ACEScct
    // encode + identity sample + 709 OETF decode + gamut back): f32
    // measured 0.183632389/0.183624640/0.183623224. The channels differ
    // because the neutral passes through the D60 <-> D65 CAT; the value
    // is NOT 0.18 (the encodings genuinely differ), and it is close to
    // the f64 no-CAT reference 0.18362666 (the 709 matrix's own white
    // slip ~1.7e-4 explains the rest).
    const rawedit::Lut lut = identityCube();
    rawedit::LutInterpretation interp;
    interp.input = {rawedit::GamutId::Acescg, rawedit::TransferId::Acescct};
    interp.output = {rawedit::GamutId::Rec709,
                     rawedit::TransferId::Rec709OETF};
    interp.role = rawedit::LutRole::DisplayLook;
    const auto binding = rawedit::compileLutBinding(interp);
    float in[3] = {0.18f, 0.18f, 0.18f}, out[3];
    rawedit::evaluateManagedLutPixel(lut, binding, in, out);
    CHECK(out[0] == doctest::Approx(0.183632389f).epsilon(1e-4f));
    CHECK(out[1] == doctest::Approx(0.183624640f).epsilon(1e-4f));
    CHECK(out[2] == doctest::Approx(0.183623224f).epsilon(1e-4f));
    // And visibly not the passthrough value.
    CHECK(out[0] != doctest::Approx(0.18f).epsilon(1e-3f));
}

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
