// test_highlight_knee — D3 (owner decision 2026-11-26): the adaptive Highlights knee.
//
//   - highlightKnee: unmeasured (0 / negative / NaN / Inf) -> the legacy 0.18; measured ->
//     clamp(ref * gainScale, kMin, kMax); a Gain change re-derives it with no re-measurement;
//   - lumaQuantile / appendLumaSamples: the P85 helpers (exact on known inputs);
//   - developCpu: an UNSET ref (the default) is BIT-IDENTICAL to a grade that never had the field
//     (the legacy knee), and a ref whose knee clamps to 0.18 is bit-identical too; a dark clip's
//     ref moves the Highlights effect in the intended direction and never reorders tones.
// Pure logic, synthetic fixtures, desktop only.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

using namespace rawedit;

namespace {

constexpr uint32_t kW = 32, kH = 32;

FrameInfo makeInfo() {
    FrameInfo info;
    info.width = kW; info.height = kH; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(kW), int(kH)};
    return info;
}

RawMeta makeMeta() {
    RawMeta meta;
    for (int s = 0; s < 4; ++s) meta.black[s] = 0;
    meta.white = 16383;
    meta.wbGains[0] = meta.wbGains[1] = meta.wbGains[2] = 1.0f;
    return meta;
}

// A horizontal ramp, 0 .. maxNorm of the white level, the same on every site.
std::vector<uint16_t> ramp(float maxNorm) {
    std::vector<uint16_t> cfa(size_t(kW) * kH);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x)
            cfa[size_t(y) * kW + x] = uint16_t(std::lround(maxNorm * 16383.0f * float(x) / float(kW - 1)));
    return cfa;
}

ImageF dev(const std::vector<uint16_t>& cfa, const Grade& g) {
    return developCpu(cfa.data(), makeInfo(), makeMeta(), g);
}

bool bitEqual(const ImageF& a, const ImageF& b) {
    return a.rgb.size() == b.rgb.size() &&
           std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * sizeof(float)) == 0;
}

}  // namespace

TEST_CASE("knee_is_legacy_when_unmeasured") {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    for (float ref : {0.0f, -1.0f, nan, inf, -inf})
        CHECK(highlightKnee(ref, 1.0f) == kHighlightKneeMax);
    CHECK(kHighlightKneeMax == 0.18f);  // the legacy fixed knee, unchanged
    CHECK(highlightKnee(0.05f, nan) == kHighlightKneeMax);
    CHECK(highlightKnee(0.05f, 0.0f) == kHighlightKneeMax);
}

TEST_CASE("knee_follows_the_clip_and_is_bounded") {
    CHECK(highlightKnee(0.05f, 1.0f) == doctest::Approx(0.05f));
    CHECK(highlightKnee(0.0076f, 3.25f) == doctest::Approx(0.0247f).epsilon(1e-3));  // DCG at +1.7 EV
    CHECK(highlightKnee(0.0001f, 1.0f) == kHighlightKneeMin);   // very dark clip: lower bound
    CHECK(highlightKnee(0.30f, 1.0f) == kHighlightKneeMax);     // bright clip: legacy knee
    CHECK(highlightKnee(5.0f, 8.0f) == kHighlightKneeMax);
    // Gain is a uniform scale: ref * 2^ev equals the post-Gain percentile.
    CHECK(highlightKnee(0.03f, 2.0f) == doctest::Approx(0.06f));
    CHECK(highlightKnee(0.03f, 4.0f) == doctest::Approx(0.12f));
    CHECK(highlightKnee(0.03f, 8.0f) == kHighlightKneeMax);
}

TEST_CASE("quantile_and_luma_samples_are_exact_on_known_inputs") {
    std::vector<float> v;
    for (int i = 0; i < 101; ++i) v.push_back(float(i));
    CHECK(lumaQuantile(v, 0.85f) == doctest::Approx(85.0f));
    CHECK(lumaQuantile(v, 0.0f) == 0.0f);
    CHECK(lumaQuantile(v, 1.0f) == 100.0f);
    std::vector<float> empty;
    CHECK(lumaQuantile(empty, 0.85f) == 0.0f);

    ImageF im; im.w = 4; im.h = 1;
    im.rgb = {1, 1, 1,  0, 0, 0,  -1, -1, -1,  std::numeric_limits<float>::quiet_NaN(), 0, 0};
    std::vector<float> out;
    appendLumaSamples(im, 1, out);
    REQUIRE(out.size() == 2);                  // the negative and the NaN pixels are skipped
    CHECK(out[0] == doctest::Approx(1.0f));
    CHECK(out[1] == 0.0f);
    out.clear();
    appendLumaSamples(im, 2, out);             // every 2nd pixel: indices 0 and 2
    CHECK(out.size() == 1);
}

TEST_CASE("unset_ref_and_clamped_ref_are_bit_identical_to_the_legacy_knee") {
    const auto cfa = ramp(0.9f);
    for (float hl : {-100.0f, -40.0f, 25.0f, 100.0f}) {
        Grade legacy; legacy.highlights = hl;                  // highlightKneeRef untouched (0)
        Grade unset = legacy; unset.highlightKneeRef = 0.0f;
        Grade bright = legacy; bright.highlightKneeRef = 0.40f; // clamps to 0.18
        const ImageF a = dev(cfa, legacy), b = dev(cfa, unset), c = dev(cfa, bright);
        CHECK(bitEqual(a, b));
        CHECK(bitEqual(a, c));
    }
}

TEST_CASE("dark_clip_ref_lowers_the_knee_and_recovery_stays_monotone") {
    const auto cfa = ramp(0.20f);  // a dark clip: nothing above ~0.2 linear
    Grade base; base.highlights = 0.0f;
    Grade old; old.highlights = -100.0f;
    Grade adaptive = old; adaptive.highlightKneeRef = 0.02f;   // knee = 0.02 (> kMin)
    const ImageF b = dev(cfa, base), o = dev(cfa, old), n = dev(cfa, adaptive);
    // Recovery pulls the bright end down further with the lower knee.
    const size_t last = (size_t(kW) - 1) * 3 + 1;   // G of the brightest column (row 0)
    CHECK(n.rgb[last] < o.rgb[last]);
    CHECK(o.rgb[last] <= b.rgb[last]);
    // Order-preserving along the ramp (monotone shoulder, for every knee).
    for (uint32_t x = 1; x < kW; ++x)
        CHECK(n.rgb[size_t(x) * 3 + 1] >= n.rgb[size_t(x - 1) * 3 + 1]);
    // Below the knee nothing moves.
    CHECK(n.rgb[3 * 1 + 1] == doctest::Approx(b.rgb[3 * 1 + 1]).epsilon(1e-6));
    // A Gain change re-derives the knee: at +2 EV the same ref gives 4x the knee (0.08), the effect differs.
    Grade g2 = adaptive; g2.exposureEv = 2.0f;
    Grade g2old = old; g2old.exposureEv = 2.0f;
    const ImageF n2 = dev(cfa, g2), o2 = dev(cfa, g2old);
    CHECK_FALSE(bitEqual(n2, o2));
}

TEST_CASE("clip_normalised_shoulder_equals_the_legacy_shoulder_on_a_rescaled_clip") {
    // The shoulder runs on mx * k, k = 0.18 / clipKnee, and the gain is newMx / mx on that scale.
    // So a clip whose pixels are all c times a reference clip, with the knee ref scaled by the same c,
    // develops to exactly c times the reference's Highlights output (up to float rounding): the slider
    // acts on a dark clip like on the bright one it is normalised to.
    const auto bright = ramp(0.90f);
    Grade ref; ref.highlights = -100.0f; ref.highlightKneeRef = 0.18f;   // knee 0.18: legacy scale 1
    const ImageF r = dev(bright, ref);
    const float c = 0.25f;                                               // a dark twin: 4x dimmer
    std::vector<uint16_t> dark(bright.size());
    for (size_t i = 0; i < bright.size(); ++i) dark[i] = uint16_t(std::lround(float(bright[i]) * c));
    Grade g; g.highlights = -100.0f; g.highlightKneeRef = 0.18f * c;     // knee 0.045
    const ImageF d = dev(dark, g);
    size_t checked = 0;
    for (size_t i = 0; i < r.rgb.size(); ++i) {
        if (r.rgb[i] < 0.02f) continue;                                  // skip sub-LSB noise of the 14-bit rounding
        CHECK(d.rgb[i] == doctest::Approx(c * r.rgb[i]).epsilon(5e-3));
        ++checked;
    }
    CHECK(checked > 100);
}

TEST_CASE("legacy_scale_is_exactly_one") {
    // 0.18f / 0.18f == 1.0f exactly (IEEE division): clips at the legacy knee multiply mx by 1.0f.
    CHECK(kHighlightKneeMax / highlightKnee(0.0f, 1.0f) == 1.0f);
    CHECK(kHighlightKneeMax / highlightKnee(0.5f, 1.0f) == 1.0f);
}
