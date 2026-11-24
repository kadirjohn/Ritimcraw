// test_focus_fit — the effective-focal calibration layer's recovery proofs
// (core/tests/image_stab_focus_fit.hpp; the closed-form k and the honest
// confidence window that image_stab_measure's `focus-fit` rides on).
//
// The recovery test is the full pipeline in miniature and SYNTHETIC:
//   - a frame clock + a gyro-rate walk (a constant rate about Y plus a
//     slower roll about Z, the pan/roll channels that reach the image),
//   - orientation integration through the production integrateGyro,
//   - predictions at the container focal (gyroPairImageMotion),
//   - MEASURED pairs synthesized as pred * kTrue (the linearity the fit
//     rides on is exact for the translation channels) plus per-pair noise,
//   - the fit must recover kTrue.
// A k of 1.0 must come back as 1.0 (no fabrication), and noise must WIDEN
// the confidence window rather than biasing the estimate.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include <rawedit/Gyro.h>
#include <rawedit/ImageStab.h>
#include <rawedit/OrientationEstimate.h>

#include "image_stab_focus_fit.hpp"
#include "image_stab_gyro.hpp"

using namespace rawedit;

namespace {

// A deterministic pan/roll rate walk: 6 deg/s about Y (pan -> translation)
// plus 2 deg/s about Z (roll -> theta). dt = 1/30 s, N pairs.
OrientationSeries series(int pairs) {
    std::vector<ImuSample> g;
    const double wy = 6.0 * 3.14159265358979323846 / 180.0;
    const double wz = 2.0 * 3.14159265358979323846 / 180.0;
    const int64_t dt = 33333333;  // ~1/30 s
    for (int i = 0; i <= pairs; ++i)
        g.push_back({i * dt, 0.0f, float(wy), float(wz)});
    return integrateGyro(g);
}

struct Synth {
    std::vector<GyroPairImage> pred;   // at k = 1 (container focal)
    std::vector<PairMotion> meas;      // pred * kTrue + noise, workH units
    std::vector<int64_t> ptsUs;
};

std::vector<int64_t> clockOf(int pairs) {
    std::vector<int64_t> t(pairs + 1);
    for (int i = 0; i <= pairs; ++i) t[i] = int64_t(i) * 33333;  // us
    return t;
}

Synth buildSynth(int pairs, double kTrue, double noisePx, uint64_t seed) {
    const OrientationSeries s = series(pairs);
    const double fpx = 1000.0;
    const double workH = 360.0;
    Synth out;
    out.ptsUs = clockOf(pairs);
    std::vector<uint8_t> inside;
    out.pred = predictPairsFromSeries(s, out.ptsUs, 0, fpx, &inside);
    uint64_t rng = seed * 6364136223846793005ull + 1442695040888963407ull;
    auto rnd = [&]() {
        rng = rng * 6364136223846793005ull + 1442695040888963407ull;
        return (double(rng >> 11) / 9007199254740992.0 - 0.5) * 2.0;  // [-1,1)
    };
    out.meas.assign(pairs + 1, PairMotion{});
    out.meas[0].status = PairStatus::Lost;
    for (int i = 1; i <= pairs; ++i) {
        out.meas[i].status = PairStatus::Ok;
        out.meas[i].tx = float((kTrue * out.pred[i].tx + noisePx * rnd()) / workH);
        out.meas[i].ty = float((kTrue * out.pred[i].ty + noisePx * rnd()) / workH);
        out.meas[i].theta = out.pred[i].theta + 0.0002 * rnd();
    }
    return out;
}

}  // namespace

TEST_CASE("focus-fit: closed form recovers a known k exactly (no noise)") {
    const double kTrue = 1.18;
    const Synth syn = buildSynth(200, kTrue, 0.0, 1);
    std::vector<uint8_t> inside(syn.pred.size(), 1);
    const FocusFitQuad q = focusQuad(syn.pred, syn.meas, 360.0, inside);
    CHECK(q.n == 200);
    CHECK(q.kBest() == doctest::Approx(kTrue).epsilon(1e-6));
    CHECK(q.rmsAtK(kTrue) == doctest::Approx(0.0).scale(1e9));  // ~0
    CHECK(q.rmsAtK(1.0) > q.rmsAtK(kTrue) * 10.0);
}

TEST_CASE("focus-fit: k = 1.0 passes through as exactly 1.0") {
    const Synth syn = buildSynth(200, 1.0, 0.0, 7);
    std::vector<uint8_t> inside(syn.pred.size(), 1);
    const FocusFitQuad q = focusQuad(syn.pred, syn.meas, 360.0, inside);
    CHECK(q.kBest() == doctest::Approx(1.0).epsilon(1e-6));
}

TEST_CASE("focus-fit: noisy measurements keep the center, widen the window") {
    const double kTrue = 1.22;
    const Synth quiet = buildSynth(300, kTrue, 0.05, 11);
    const Synth noisy = buildSynth(300, kTrue, 1.20, 12);  // large px noise
    std::vector<uint8_t> inside(quiet.pred.size(), 1);
    const FocusFitQuad q1 = focusQuad(quiet.pred, quiet.meas, 360.0, inside);
    const FocusFitQuad q2 = focusQuad(noisy.pred, noisy.meas, 360.0, inside);
    // Center: both recover kTrue within the honest tolerance.
    CHECK(q1.kBest() == doctest::Approx(kTrue).epsilon(0.01));
    CHECK(q2.kBest() == doctest::Approx(kTrue).epsilon(0.01));
    // Window: the noisy one must be WIDER (that is the point of the
    // confidence being honest, not optimistic).
    double lo1, hi1, lo2, hi2;
    focusConfidence(q1, 0.02, lo1, hi1);
    focusConfidence(q2, 0.02, lo2, hi2);
    CHECK(0.5 * (hi1 - lo1) < 0.5 * (hi2 - lo2));
    CHECK(lo2 < kTrue);
    CHECK(kTrue < hi2);
    // Degenerate quad (no usable pairs): window collapses to the point.
    std::vector<uint8_t> noneInside(quiet.pred.size(), 0);
    const FocusFitQuad q0 = focusQuad(quiet.pred, quiet.meas, 360.0, noneInside);
    double lo0, hi0;
    focusConfidence(q0, 0.02, lo0, hi0);
    CHECK(lo0 == hi0);
}

TEST_CASE("focus-fit: per-channel k separates anisotropic scale (x only)") {
    // The real clips' suspect: an anisotropic effective focal (x and y
    // scaled differently). Build a measurement whose x channel carries
    // kX = 1.22 and leave y at 1.0; the x-only fit must return 1.22 while
    // the 2D fit lands between the two.
    const double kX = 1.22, kY = 1.0;
    const Synth base = buildSynth(200, 1.0, 0.0, 9);
    Synth syn;
    syn.pred = base.pred;
    syn.meas = base.meas;
    for (size_t i = 1; i < syn.meas.size(); ++i) {
        syn.meas[i].tx = float((kX * base.pred[i].tx) / 360.0);
        syn.meas[i].ty = float((kY * base.pred[i].ty) / 360.0);
    }
    std::vector<uint8_t> inside(syn.pred.size(), 1);
    const FocusFitQuad q = focusQuad(syn.pred, syn.meas, 360.0, inside);
    CHECK(q.kBestX() == doctest::Approx(kX).epsilon(1e-6));
    CHECK(q.kBestY() == doctest::Approx(kY).epsilon(1e-6));
    // The 2D blend lands strictly between (the pan channel dominates the
    // magnitude here, so it lands closer to kX than to kY).
    CHECK(q.kBest() > 1.0);
    CHECK(q.kBest() < kX + 0.01);
}

TEST_CASE("focus-fit: prediction scaling is exactly linear (the linearity axiom)") {
    const Synth syn = buildSynth(60, 1.0, 0.0, 3);
    const double k = 1.216;
    const std::vector<GyroPairImage> scaled = scalePredictions(syn.pred, k);
    for (size_t i = 1; i < scaled.size(); ++i) {
        CHECK(scaled[i].tx == doctest::Approx(k * syn.pred[i].tx).epsilon(1e-12));
        CHECK(scaled[i].ty == doctest::Approx(k * syn.pred[i].ty).epsilon(1e-12));
        CHECK(scaled[i].theta == syn.pred[i].theta);  // rotation channel untouched
    }
}

TEST_CASE("focus-fit: usable-pair gating skips Lost and outside-span pairs") {
    const double kTrue = 1.18;
    Synth syn = buildSynth(100, kTrue, 0.0, 5);
    std::vector<uint8_t> inside(syn.pred.size(), 1);
    syn.meas[10].status = PairStatus::Lost;
    syn.meas[20].status = PairStatus::Lost;
    inside[30] = 0;
    inside[40] = 0;
    const FocusFitQuad q = focusQuad(syn.pred, syn.meas, 360.0, inside);
    CHECK(q.n == 96);  // 100 measured pairs (records 1..100) minus 2 Lost minus 2 outside
    CHECK(q.kBest() == doctest::Approx(kTrue).epsilon(1e-6));
}
