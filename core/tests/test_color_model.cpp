// F1-1: color model v2 — DI/DWG glue + the CPU node reference
// (docs/COLOR_MODEL_V2_PLAN.md §2, §3, §10 layer 1). Synthetic fixtures
// only, no cwd needs.
//
// Coverage:
//   - DI 18 % grey anchor, log-wheel zone limits derivation
//   - 709 <-> DWG matrix: identity round trip, white, equality with the
//     Klipp/OpenDRT DWG matrix (the plan's "doğrulanmadı" item)
//   - 709 -> DWG -> DI -> DWG -> 709 round trips incl. negatives and >1
//     (K1 owner condition, part 1)
//   - K1 owner condition, part 2: negative linear-709 values pass the
//     develop stages 5-22 unclipped (CPU; developCpu + the NR / sharpen
//     helpers)
//   - nodes: neutral = identity, nodes=[] bit-identical fast path, each
//     control's closed-form vector, mix/enable, curves, log wheels, node
//     LUT, N1 limits, DWG-linear output, negatives through the chain
//
// Measured tolerances are written next to each check (MESSAGE lines print
// the measured value when the test runs).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include <rawedit/Color.h>
#include <rawedit/ColorModel.h>
#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/Source.h>

using namespace rawedit;

namespace {

ImageF makeImage(uint32_t w, uint32_t h, const std::vector<float>& rgb) {
    ImageF img;
    img.w = w;
    img.h = h;
    img.rgb = rgb;
    return img;
}

ImageF onePixel(float r, float g, float b) { return makeImage(1, 1, {r, g, b}); }

// OpenDRT.dctl / Klipp DWG -> XYZ matrix (Klipp OpenDrt.kt M.DWG_TO_XYZ),
// copied here only to compare with Color.cpp's pinned matrix.
const float kKlippDwgToXyz[9] = {
    0.700622392093671609f, 0.148774815123196763f, 0.101058719834803246f,
    0.274118510906649016f, 0.873631895940436665f, -0.147750406847085763f,
    -0.0989629128832311411f, -0.137895325075543307f, 1.32591598871865268f};

// A synthetic flat CFA patch, as test_develop.cpp's syntheticFlat but with
// a ForwardMatrix so camera RGB is non-trivially mapped to Rec.709.
struct Flat {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
};

Flat flatPatch(float lr, float lg, float lb, uint32_t w = 16, uint32_t h = 16) {
    Flat s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};
    for (int i = 0; i < 4; ++i)
        s.meta.black[i] = 64;
    s.meta.white = 1023;
    s.meta.wbGains[0] = s.meta.wbGains[1] = s.meta.wbGains[2] = 1.0f;
    // The REAL 24mm clip's ForwardMatrix (test_develop.cpp): strongly
    // saturated camera colors leave the Rec.709 gamut.
    const float fm[9] = {0.6328125f, 0.109375f, 0.21875f,
                         0.21875f, 0.7578125f, 0.0234375f,
                         -0.0390625f, -0.453125f, 1.3203125f};
    for (int i = 0; i < 9; ++i) {
        s.meta.forwardMatrix[i] = fm[i];
        s.meta.calibration[i] = (i == 0 || i == 4 || i == 8) ? 1.0f : 0.0f;
    }
    const uint16_t v[3] = {static_cast<uint16_t>(64 + lr * (1023 - 64)),
                           static_cast<uint16_t>(64 + lg * (1023 - 64)),
                           static_cast<uint16_t>(64 + lb * (1023 - 64))};
    s.cfa.assign(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const int site = (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2);
            s.cfa[static_cast<size_t>(y) * w + x] = v[site];
        }
    return s;
}

float minChannel(const ImageF& img, int c) {
    float m = 1e30f;
    for (size_t i = static_cast<size_t>(c); i < img.rgb.size(); i += 3)
        m = std::min(m, img.rgb[i]);
    return m;
}

bool allFinite(const ImageF& img) {
    for (float v : img.rgb)
        if (!std::isfinite(v))
            return false;
    return true;
}

} // namespace

TEST_CASE("DI anchors: 18 percent grey and the log-wheel zone limits") {
    CHECK(encodeTransfer(0.18f, TransferId::DaVinciIntermediate) ==
          doctest::Approx(kDiMidGray).epsilon(1e-6f));
    // The plan's hand calculation: (log2(0.18 + 0.0075) + 7) * 0.07329248.
    const double hand = (std::log2(0.18 + 0.0075) + 7.0) * 0.07329248;
    CHECK(hand == doctest::Approx(0.336043272).epsilon(1e-7));
    // Zone limits = DI codes of grey/8 and grey*8, rounded to 3 decimals.
    CHECK(encodeTransfer(0.18f / 8.0f, TransferId::DaVinciIntermediate) ==
          doctest::Approx(kNodeLogLow).epsilon(2e-3));
    CHECK(encodeTransfer(0.18f * 8.0f, TransferId::DaVinciIntermediate) ==
          doctest::Approx(kNodeLogHigh).epsilon(2e-3));
    CHECK(std::lround(encodeTransfer(0.18f / 8.0f, TransferId::DaVinciIntermediate) * 1000) == 142);
    CHECK(std::lround(encodeTransfer(0.18f * 8.0f, TransferId::DaVinciIntermediate) * 1000) == 552);
}

TEST_CASE("709 <-> DWG matrices: identity round trip, white, Klipp matrix") {
    float a[9], b[9];
    rec709ToDwgMatrix(a);
    dwgToRec709Matrix(b);
    // b * a == I
    double worst = 0.0;
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            double s = 0.0;
            for (int k = 0; k < 3; ++k)
                s += static_cast<double>(b[i * 3 + k]) * a[k * 3 + j];
            worst = std::max(worst, std::abs(s - (i == j ? 1.0 : 0.0)));
        }
    MESSAGE("709->DWG->709 matrix product, max |M - I| = " << worst);
    CHECK(worst < 5e-7); // measured below; f32 matrices from a computed inverse

    // D65 white maps to D65 white (rows of M sum to ~1 for a neutral input).
    const float white[3] = {1.0f, 1.0f, 1.0f};
    float w[3];
    linear709ToDwg(white, w);
    MESSAGE("DWG of 709 white = " << w[0] << " " << w[1] << " " << w[2]);
    for (int i = 0; i < 3; ++i)
        CHECK(w[i] == doctest::Approx(1.0f).epsilon(3e-4f)); // 4-dp 709 matrix white slip

    // Color.cpp's pinned DWG -> XYZ equals the Klipp/OpenDRT matrix.
    float ours[9];
    gamutToXyz(GamutId::DaVinciWideGamut, ours);
    double d = 0.0;
    for (int i = 0; i < 9; ++i)
        d = std::max(d, static_cast<double>(std::abs(ours[i] - kKlippDwgToXyz[i])));
    MESSAGE("Color.cpp DWG->XYZ vs Klipp/OpenDRT DWG->XYZ, max |diff| = " << d);
    CHECK(d < 1e-6);
}

TEST_CASE("K1 part 1: 709 -> DWG -> DI -> back, negatives and >1 survive") {
    // Dense grid, including negative linear values (out-of-gamut camera
    // colors) and super-whites (a 100x scene).
    const float vals[] = {-0.5f, -0.1f, -0.01f, -0.001f, 0.0f, 0.001f, 0.0026f,
                          0.0027f, 0.01f, 0.18f, 0.5f, 1.0f, 4.0f, 16.0f, 100.0f};
    double worstAbs = 0.0, worstRel = 0.0;
    double worstDwgAbs = 0.0;
    size_t n = 0;
    for (float r : vals)
        for (float g : vals)
            for (float b : vals) {
                const float in[3] = {r, g, b};
                float dwg[3], back709[3], di[3], rt[3];
                linear709ToDwg(in, dwg);
                dwgToLinear709(dwg, back709);
                linear709ToDi(in, di);
                diToLinear709(di, rt);
                for (int c = 0; c < 3; ++c) {
                    REQUIRE(std::isfinite(di[c]));
                    REQUIRE(std::isfinite(rt[c]));
                    worstDwgAbs = std::max(worstDwgAbs, static_cast<double>(std::abs(back709[c] - in[c])));
                    const double e = std::abs(rt[c] - in[c]);
                    worstAbs = std::max(worstAbs, e);
                    worstRel = std::max(worstRel, e / std::max(1.0, static_cast<double>(std::abs(in[c]))));
                }
                ++n;
            }
    MESSAGE("grid points: " << n);
    MESSAGE("709->DWG->709 (matrices only) max abs err = " << worstDwgAbs);
    MESSAGE("709->DI->709 max abs err = " << worstAbs << ", max rel(vs max(1,|x|)) = " << worstRel);
    // Measured: 1.5e-5 abs (matrices, at |x| up to 100) and 6.7e-5 relative
    // for the full DI trip (3375 grid points). The DI figure is dominated by
    // cancellation between a 100x channel and sub-1 channels in the matrix
    // step, not by the DI curve itself. Pinned at 1.5x the measurement.
    CHECK(worstDwgAbs < 2.5e-5);
    CHECK(worstRel < 1e-4);
    // A negative input stays negative through DI (linear segment keeps sign).
    const float neg[3] = {-0.05f, 0.2f, 0.1f};
    float dwg[3];
    linear709ToDwg(neg, dwg);
    float di[3], rt[3];
    linear709ToDi(neg, di);
    diToLinear709(di, rt);
    CHECK(rt[0] < 0.0f);
    CHECK(rt[0] == doctest::Approx(-0.05f).epsilon(1e-4f));
}

TEST_CASE("K1 part 2: develop stages keep negative linear-709 values (not clipped)") {
    // Camera patches chosen so the camera->709 matrix yields a NEGATIVE red:
    // a bright one (tone stages keyed on max channel see "highlights") and a
    // dark one (they see "shadows"/"midtones").
  for (float greenLevel : {0.9f, 0.12f}) {
    CAPTURE(greenLevel);
    Flat s = flatPatch(0.0f, greenLevel, 0.0f);

    Grade base;
    base.asShot = true;
    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, base);
    REQUIRE(allFinite(neutral));
    const float negR = minChannel(neutral, 0);
    MESSAGE("neutral develop, min R = " << negR);
    // The premise of the whole test: the patch is out of Rec.709 gamut.
    REQUIRE(negR < -0.01f);

    struct Case { std::string name; Grade g; };
    std::vector<Case> cases;
    auto add = [&](const char* name, auto&& fn) {
        Grade g = base;
        fn(g);
        cases.push_back({name, g});
    };
    add("exposure +1", [](Grade& g) { g.exposureEv = 1.0f; });
    add("exposure -2", [](Grade& g) { g.exposureEv = -2.0f; });
    add("blackPoint +50", [](Grade& g) { g.blackPoint = 50.0f; });
    add("blackPoint -50", [](Grade& g) { g.blackPoint = -50.0f; });
    add("whitePoint +50", [](Grade& g) { g.whitePoint = 50.0f; });
    add("shadows +60", [](Grade& g) { g.shadows = 60.0f; });
    add("midtones -40", [](Grade& g) { g.midtones = -40.0f; });
    add("contrast +50", [](Grade& g) { g.contrast = 50.0f; });
    add("highlights -50", [](Grade& g) { g.highlights = -50.0f; });
    add("temp 3500", [](Grade& g) { g.asShot = false; g.tempK = 3500.0f; });
    add("tint +40", [](Grade& g) { g.asShot = false; g.tint = 40.0f; });
    add("wheels", [](Grade& g) { g.wheelShadowX = 0.5f; g.wheelMidY = -0.4f; g.wheelHighX = 0.3f; });
    add("split tone", [](Grade& g) { g.splitShadowHue = 200.0f; g.splitHighlightHue = 30.0f; g.splitBalance = 10.0f; });
    add("saturation +30", [](Grade& g) { g.saturation = 30.0f; });
    add("sharpness+detail", [](Grade& g) { g.sharpness = 60.0f; g.detail = 60.0f; });
    add("chroma+luma NR", [](Grade& g) { g.chromaDenoise = 50.0f; g.noiseReduction = 50.0f; });
    add("everything at once", [](Grade& g) {
        g.exposureEv = 0.5f; g.contrast = 20.0f; g.shadows = 20.0f; g.midtones = 10.0f;
        g.highlights = -20.0f; g.saturation = 10.0f; g.sharpness = 30.0f; g.detail = 30.0f;
        g.chromaDenoise = 30.0f; g.noiseReduction = 30.0f; g.wheelHighY = 0.2f;
    });
    for (const Case& c : cases) {
        auto img = developCpu(s.cfa.data(), s.info, s.meta, c.g);
        CAPTURE(c.name);
        REQUIRE(allFinite(img));
        // Not clipped to >= 0 anywhere on the way: red stays negative
        // (stages that scale/offset/saturate it may change the value, but
        // none may floor it at 0).
        const float m = minChannel(img, 0);
        MESSAGE("green " << greenLevel << ", " << c.name << ": min R = " << m);
        CHECK(m < -1e-4f);
    }
  }
}

TEST_CASE("K1 part 2b: NR and sharpen helpers keep negatives on a structured image") {
    // A 16x16 image with a negative-red region next to a positive one, so
    // the edge-aware filters actually work on negatives.
    ImageF img;
    img.w = 16;
    img.h = 16;
    img.rgb.resize(16 * 16 * 3);
    for (uint32_t y = 0; y < 16; ++y)
        for (uint32_t x = 0; x < 16; ++x) {
            float* p = &img.rgb[(static_cast<size_t>(y) * 16 + x) * 3];
            const bool left = x < 8;
            p[0] = left ? -0.2f - 0.01f * static_cast<float>(y) : 0.4f;
            p[1] = 0.5f;
            p[2] = 0.3f;
        }
    for (int which = 0; which < 3; ++which) {
        ImageF t = img;
        if (which == 0) denoiseChroma(t, 0.8f);
        if (which == 1) denoiseLuma(t, 0.8f);
        if (which == 2) unsharpLuma(t, 1.5f, 0.5f);
        CAPTURE(which);
        REQUIRE(allFinite(t));
        // deep inside the negative half the value is still clearly negative
        const float inside = t.rgb[(static_cast<size_t>(8) * 16 + 2) * 3];
        CHECK(inside < -0.1f);
    }
}

TEST_CASE("nodes: nodes=[] and neutral chains are bit-identical (no work)") {
    std::vector<float> src;
    for (int i = 0; i < 16 * 3; ++i)
        src.push_back(0.05f * static_cast<float>(i) - 0.3f);
    ImageF a = makeImage(4, 4, src);
    CHECK(applyNodes(a, nullptr, 0));
    CHECK(std::memcmp(a.rgb.data(), src.data(), src.size() * sizeof(float)) == 0);

    GradeNode neutral;                  // all defaults
    GradeNode disabled;                 // would change the image, but off
    disabled.enabled = false;
    disabled.contrast = 2.0f;
    GradeNode mixZero;
    mixZero.mix = 0.0f;
    mixZero.contrast = 2.0f;
    GradeNode chain[3] = {neutral, disabled, mixZero};
    ImageF b = makeImage(4, 4, src);
    CHECK(applyNodes(b, chain, 3));
    CHECK(std::memcmp(b.rgb.data(), src.data(), src.size() * sizeof(float)) == 0);
}

TEST_CASE("nodes: a forced-active neutral node is the identity to float precision") {
    // Not skipped by isNeutral (mix 0.99999 > 0.0001, tiny gain.master
    // nudge below float visibility is NOT used): use a real pass through
    // the pipeline with an explicitly neutral-valued but "non-neutral by
    // flag" node: a LUT-less node whose hue is 360 degrees (rotation = I
    // within float) exercises hue/sat/contrast/wb/primaries arithmetic.
    GradeNode n;
    n.hue = 360.0f;
    REQUIRE_FALSE(n.isNeutral());
    const float vals[] = {-0.02f, 0.0f, 0.01f, 0.18f, 0.5f, 1.0f, 8.0f};
    double worst = 0.0;
    for (float r : vals)
        for (float g : vals)
            for (float b : vals) {
                const float in[3] = {r, g, b};
                float di[3], di0[3];
                linear709ToDi(in, di);
                std::memcpy(di0, di, sizeof(di));
                applyNodeDi(n, di);
                for (int c = 0; c < 3; ++c)
                    worst = std::max(worst, static_cast<double>(std::abs(di[c] - di0[c])));
            }
    MESSAGE("hue=360 node, max |DI out - DI in| = " << worst);
    CHECK(worst < 2e-6);
}

TEST_CASE("nodes: closed-form vectors per control (DI domain)") {
    const float g = kDiMidGray;
    auto run = [](const GradeNode& n, float a, float b, float c, float out[3]) {
        out[0] = a; out[1] = b; out[2] = c;
        applyNodeDi(n, out);
    };
    float o[3];

    GradeNode contrast;
    contrast.contrast = 2.0f;
    run(contrast, g, g, g, o); // pivot is a fixed point
    CHECK(o[0] == doctest::Approx(g).epsilon(1e-6f));
    run(contrast, g + 0.1f, g + 0.1f, g + 0.1f, o); // 2x away from pivot
    CHECK(o[1] == doctest::Approx(g + 0.2f).epsilon(1e-5f));
    run(contrast, g - 0.1f, g, g, o);
    CHECK(o[0] == doctest::Approx(g - 0.2f).epsilon(1e-5f));

    GradeNode gain;
    gain.gain.master = 0.5f; // slope 1 + 0.5
    run(gain, 0.2f, 0.4f, 0.6f, o);
    CHECK(o[0] == doctest::Approx(0.3f).epsilon(1e-5f));
    CHECK(o[1] == doctest::Approx(0.6f).epsilon(1e-5f));
    CHECK(o[2] == doctest::Approx(0.9f).epsilon(1e-5f));

    GradeNode offs;
    offs.offset.master = 0.2f; // + 0.25 * master
    run(offs, 0.2f, 0.4f, 0.6f, o);
    CHECK(o[0] == doctest::Approx(0.25f).epsilon(1e-5f));
    CHECK(o[2] == doctest::Approx(0.65f).epsilon(1e-5f));

    GradeNode temp;
    temp.temp = 0.5f; // wb = (1.09, 1, 0.91) on DI codes
    run(temp, 0.4f, 0.4f, 0.4f, o);
    CHECK(o[0] == doctest::Approx(0.4f * 1.09f).epsilon(1e-5f));
    CHECK(o[1] == doctest::Approx(0.4f).epsilon(1e-5f));
    CHECK(o[2] == doctest::Approx(0.4f * 0.91f).epsilon(1e-5f));

    GradeNode sat;
    sat.sat = 0.0f; // fully desaturated: all channels = luma
    run(sat, 0.2f, 0.4f, 0.6f, o);
    const float luma = 0.2126f * 0.2f + 0.7152f * 0.4f + 0.0722f * 0.6f;
    CHECK(o[0] == doctest::Approx(luma).epsilon(1e-5f));
    CHECK(o[2] == doctest::Approx(luma).epsilon(1e-5f));
    // grey is a fixed point of saturation
    sat.sat = 1.7f;
    run(sat, 0.4f, 0.4f, 0.4f, o);
    CHECK(o[0] == doctest::Approx(0.4f).epsilon(1e-6f));

    GradeNode hue;
    hue.hue = 120.0f; // 120 degrees about the grey axis cycles the channels
    run(hue, 0.6f, 0.4f, 0.2f, o);
    CHECK(o[0] == doctest::Approx(0.2f).epsilon(1e-4f));
    CHECK(o[1] == doctest::Approx(0.6f).epsilon(1e-4f));
    CHECK(o[2] == doctest::Approx(0.4f).epsilon(1e-4f));

    GradeNode half;
    half.gain.master = 0.5f;
    half.mix = 0.5f; // halfway between in (0.4) and graded (0.6)
    run(half, 0.4f, 0.4f, 0.4f, o);
    CHECK(o[0] == doctest::Approx(0.5f).epsilon(1e-5f));
}

TEST_CASE("nodes: curves — identity, a point curve, and extension beyond 0..1") {
    NodeCurve id;
    const std::vector<float> t = nodeCurveTable(id);
    REQUIRE(t.size() == 256);
    for (int i = 0; i < 256; ++i)
        CHECK(t[static_cast<size_t>(i)] == doctest::Approx(static_cast<float>(i) / 255.0f).epsilon(1e-6f));

    GradeNode n;
    n.curveY.points = {{0.0f, 0.0f}, {0.5f, 0.7f}, {1.0f, 1.0f}};
    float o[3] = {0.5f, 0.5f, 0.5f};
    applyNodeDi(n, o);
    MESSAGE("curve (0.5 -> 0.7) at 0.5: " << o[0]);
    CHECK(o[0] == doctest::Approx(0.7f).epsilon(2e-3f));
    // ends pinned
    float lo[3] = {0.0f, 0.0f, 0.0f}, hi[3] = {1.0f, 1.0f, 1.0f};
    applyNodeDi(n, lo);
    applyNodeDi(n, hi);
    CHECK(std::abs(lo[0]) < 1e-5f);
    CHECK(hi[0] == doctest::Approx(1.0f).epsilon(1e-5f));
    // beyond 0..1 the curve continues straight (values keep their order)
    float below[3] = {-0.2f, -0.2f, -0.2f}, above[3] = {1.2f, 1.2f, 1.2f};
    applyNodeDi(n, below);
    applyNodeDi(n, above);
    CHECK(below[0] < lo[0]);
    CHECK(above[0] > hi[0]);

    // the composite table is monotone for a monotone curve
    NodeCurve c;
    c.points = {{0.0f, 0.1f}, {0.4f, 0.3f}, {1.0f, 0.9f}};
    const std::vector<float> tc = nodeCurveTable(c);
    for (size_t i = 1; i < tc.size(); ++i)
        CHECK(tc[i] >= tc[i - 1]);
}

TEST_CASE("nodes: log wheels — neutral identity, mid wheel raises the mid zone") {
    const std::vector<float> id = nodeLogCurve(0.0f, 0.0f, 0.0f, kNodeLogLow, kNodeLogHigh);
    double worst = 0.0;
    for (int i = 0; i < kNodeCurveN; ++i)
        worst = std::max(worst, static_cast<double>(std::abs(id[static_cast<size_t>(i)] - nodeCurveX(i))));
    MESSAGE("neutral log curve max |table - x| = " << worst);
    CHECK(worst < 1e-5);

    GradeNode n;
    n.logMid.master = 0.5f; // amount = master * 0.5 = 0.25
    const float mid = 0.5f * (kNodeLogLow + kNodeLogHigh);
    float o[3] = {mid, mid, mid};
    applyNodeDi(n, o);
    CHECK(o[0] > mid + 0.005f);
    // zone ends stay put to within the soft transition width
    float e[3] = {kNodeLogHigh + 0.2f, kNodeLogHigh + 0.2f, kNodeLogHigh + 0.2f};
    applyNodeDi(n, e);
    CHECK(std::abs(e[0] - (kNodeLogHigh + 0.2f)) < 0.005f);
    // tones keep their order (never folds back)
    float prev = -10.0f;
    for (int i = 0; i < 400; ++i) {
        const float x = -0.3f + 0.004f * static_cast<float>(i);
        float p[3] = {x, x, x};
        applyNodeDi(n, p);
        CHECK(p[0] >= prev);
        prev = p[0];
    }
}

TEST_CASE("nodes: node LUT runs through the managed binding, unclamped") {
    // 2^3 LUT with out = 0.5 * in (domain 0..1): corner (i,j,k) = 0.5*(i,j,k).
    Lut half;
    half.n = 2;
    for (int k = 0; k < 2; ++k)
        for (int j = 0; j < 2; ++j)
            for (int i = 0; i < 2; ++i) {
                half.rgb.push_back(0.5f * static_cast<float>(i));
                half.rgb.push_back(0.5f * static_cast<float>(j));
                half.rgb.push_back(0.5f * static_cast<float>(k));
            }
    Lut ident = half;
    for (float& v : ident.rgb)
        v *= 2.0f;

    GradeNode n;
    n.lut = &half; // default interp: linear Rec.709 in/out
    n.lutMix = 1.0f;
    REQUIRE_FALSE(n.isNeutral());

    // linear 0.18 -> LUT -> 0.09 linear, back to DI
    float di[3];
    const float lin[3] = {0.18f, 0.18f, 0.18f};
    linear709ToDi(lin, di);
    float o[3] = {di[0], di[1], di[2]};
    applyNodeDi(n, o);
    float back[3];
    diToLinear709(o, back);
    MESSAGE("LUT half: 0.18 -> " << back[0] << " (expect 0.09)");
    CHECK(back[0] == doctest::Approx(0.09f).epsilon(2e-4f));

    // identity LUT (n=2, out=in): grey 0.18 is unchanged
    n.lut = &ident;
    float o2[3] = {di[0], di[1], di[2]};
    applyNodeDi(n, o2);
    for (int c = 0; c < 3; ++c)
        CHECK(o2[c] == doctest::Approx(di[c]).epsilon(1e-4f));

    // lutMix 0.5: halfway in DI between input and the LUT result
    n.lut = &half;
    n.lutMix = 0.5f;
    float o3[3] = {di[0], di[1], di[2]};
    applyNodeDi(n, o3);
    float full[3] = {di[0], di[1], di[2]};
    n.lutMix = 1.0f;
    applyNodeDi(n, full);
    CHECK(o3[0] == doctest::Approx(0.5f * (di[0] + full[0])).epsilon(1e-4f));

    // lutMix 0 = no LUT: the node is neutral again
    n.lutMix = 0.0f;
    CHECK(n.isNeutral());

    // a value above the LUT domain is not clamped by the node: the sampler
    // holds the table edge but the DI mix keeps the node's own continuity
    n.lutMix = 1.0f;
    float bright[3];
    const float big[3] = {4.0f, 4.0f, 4.0f};
    linear709ToDi(big, bright);
    applyNodeDi(n, bright);
    CHECK(std::isfinite(bright[0]));
}

TEST_CASE("nodes: N1 limits (8 nodes, 4 node LUTs)") {
    Lut ident;
    ident.n = 2;
    for (int k = 0; k < 2; ++k)
        for (int j = 0; j < 2; ++j)
            for (int i = 0; i < 2; ++i) {
                ident.rgb.push_back(static_cast<float>(i));
                ident.rgb.push_back(static_cast<float>(j));
                ident.rgb.push_back(static_cast<float>(k));
            }
    std::vector<GradeNode> nine(9);
    for (auto& n : nine)
        n.contrast = 1.1f;
    CHECK_FALSE(validateNodeChain(nine.data(), 9).empty());
    CHECK(validateNodeChain(nine.data(), 8).empty());

    std::vector<GradeNode> luts(5);
    for (auto& n : luts)
        n.lut = &ident;
    CHECK_FALSE(validateNodeChain(luts.data(), 5).empty());
    CHECK(validateNodeChain(luts.data(), 4).empty());

    const std::vector<float> src = {0.1f, 0.2f, 0.3f};
    ImageF img = makeImage(1, 1, src);
    CHECK_FALSE(applyNodes(img, nine.data(), 9));
    CHECK(img.rgb == src); // refused chain leaves the image untouched
    ImageF ok = makeImage(1, 1, src);
    CHECK(applyNodes(ok, nine.data(), 8));
    CHECK(ok.rgb != src);
}

TEST_CASE("nodes: image entry — 709 in/out, DWG-linear out, negatives preserved") {
    GradeNode n;
    n.gain.master = 0.5f; // DI x1.5
    // 709-out == matrices applied to the DI-domain result
    ImageF img = onePixel(0.18f, 0.18f, 0.18f);
    CHECK(applyNodes(img, &n, 1));
    float di[3];
    const float lin[3] = {0.18f, 0.18f, 0.18f};
    linear709ToDi(lin, di);
    for (float& v : di)
        v *= 1.5f;
    float expect[3];
    diToLinear709(di, expect);
    for (int c = 0; c < 3; ++c)
        CHECK(img.rgb[static_cast<size_t>(c)] == doctest::Approx(expect[static_cast<size_t>(c)]).epsilon(2e-5f));

    // DwgLinear out == DI decode only (no 709 matrix back)
    ImageF dwgImg = onePixel(0.18f, 0.18f, 0.18f);
    CHECK(applyNodes(dwgImg, &n, 1, NodesOutput::DwgLinear));
    float dwgExpect[3], viaMatrix[3];
    dwgToLinear709(dwgImg.rgb.data(), viaMatrix);
    for (int c = 0; c < 3; ++c)
        CHECK(viaMatrix[c] == doctest::Approx(expect[c]).epsilon(2e-5f));
    (void)dwgExpect;

    // Empty chain + DwgLinear: pure 709->DWG matrix
    ImageF e = onePixel(0.2f, 0.5f, 0.1f);
    CHECK(applyNodes(e, nullptr, 0, NodesOutput::DwgLinear));
    float m[3];
    const float in[3] = {0.2f, 0.5f, 0.1f};
    linear709ToDwg(in, m);
    for (int c = 0; c < 3; ++c)
        CHECK(e.rgb[static_cast<size_t>(c)] == m[c]);

    // Negative DWG value (outside DWG, e.g. a camera primary) through a
    // contrast node: stays negative and finite, in both output spaces.
    const float negDwg[3] = {-0.01f, 0.2f, 0.2f};
    float neg709[3];
    dwgToLinear709(negDwg, neg709);
    GradeNode c;
    c.contrast = 1.3f;
    ImageF ni = onePixel(neg709[0], neg709[1], neg709[2]);
    CHECK(applyNodes(ni, &c, 1, NodesOutput::DwgLinear));
    CHECK(std::isfinite(ni.rgb[0]));
    CHECK(ni.rgb[0] < 0.0f);
    ImageF n709 = onePixel(neg709[0], neg709[1], neg709[2]);
    CHECK(applyNodes(n709, &c, 1));
    CHECK(std::isfinite(n709.rgb[0]));
    CHECK(std::isfinite(n709.rgb[1]));
    CHECK(std::isfinite(n709.rgb[2]));
}
