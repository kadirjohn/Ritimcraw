// test_transfer — Mi-Log curve + encoded develop.
//
// milog_matches_vendor_lut samples the vendored 65^3 cube
// (third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube) and compares
// linearToRec709(miLogToLinear(x)) against the LUT's R output. The LUT
// composes curve + Rec.2020->709 gamut + OETF, so exactness is not
// expected (tolerance 2e-2; see the DCTL for the intended composition).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Transfer.h>

#include <cmath>
#include <cstdio>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// Minimal .cube parser: LUT_3D_SIZE N then N^3 RGB triplets, red fastest.
// Index order in .cube: data[i + N*j + N^2*k] = (r=i/N, g=j/N, b=k/N).
struct Cube {
    int n = 0;
    std::vector<float> rgb; // n^3 * 3
};

Cube loadCube(const char* path) {
    Cube c;
    std::ifstream in(path);
    REQUIRE(in);
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("LUT_3D_SIZE", 0) == 0) {
            std::istringstream ls(line.substr(11));
            ls >> c.n;
            break;
        }
    }
    REQUIRE(c.n > 0);
    c.rgb.reserve(static_cast<size_t>(c.n) * c.n * c.n * 3);
    float r, g, b;
    while (in >> r >> g >> b)
        c.rgb.push_back(r), c.rgb.push_back(g), c.rgb.push_back(b);
    return c;
}

} // namespace

TEST_CASE("milog_matches_vendor_lut") {
    // What the vendored cube actually contains (measured against the
    // whitepaper + DCTL): per-channel Mi-Log decode, then an UNDOCUMENTED
    // filmic tone S-curve (toe lift below 0.018 linear, hard clip at 1.0
    // video for codes >= 0.875, i.e. ~+4.65 stops over 18%), then a slight
    // per-channel display-space gain (G/R ~= 0.988, B/R ~= 0.978 on gray),
    // then the Rec.709 OETF. It is NOT a plain curve+OETF composition, so a
    // whole-domain comparison cannot hold. The whitepaper (Mi-Log_Whitepaper
    // .pdf, Table 1) documents the curve anchors; the cube's baked tone
    // curve is near-identity only for codes in [0.23, 0.36].
    //
    // This test therefore checks, in order:
    //  (a) the whitepaper Table 1 anchors: miLogToLinear(code) equals the
    //      documented scene reflection (0%, 18%, 90%, 1152%) — the curve
    //      itself is the documented, load-bearing part;
    //  (b) 9 points in the cube's near-identity mid region (x in
    //      [0.2344, 0.3594], diagonal): linearToRec709(miLogToLinear(x))
    //      matches the LUT's R output within 2e-2.

    // Whitepaper Table 1 (Mi-Log_Whitepaper.pdf, "Xiaomi Log Curve's
    // encoded signal with scene reflection").
    CHECK(miLogToLinear(0.14742742f) == doctest::Approx(0.0f).epsilon(1e-4));
    CHECK(miLogToLinear(0.45345968f) == doctest::Approx(0.18f).epsilon(1e-4));
    CHECK(miLogToLinear(0.66086763f) == doctest::Approx(0.90f).epsilon(1e-4));
    CHECK(miLogToLinear(1.0f) == doctest::Approx(11.52f).epsilon(1e-4));

    // The cube lives in third_party/; tests run from the repo root.
    Cube cube;
    const char* paths[] = {
        "third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
        "../third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
    };
    bool loaded = false;
    for (const char* p : paths) {
        std::ifstream probe(p);
        if (probe) {
            cube = loadCube(p);
            loaded = true;
            break;
        }
    }
    if (!loaded) {
        WARN_MESSAGE(false, "vendor cube not found — skipping LUT comparison");
        return;
    }
    REQUIRE(cube.n == 65);
    REQUIRE(cube.rgb.size() == static_cast<size_t>(65) * 65 * 65 * 3);

    // 9 grid points on the diagonal, codes 0.2344..0.3594 (indices 15..23
    // of 64), where the LUT's baked tone curve is near-identity.
    int checked = 0;
    for (int i = 15; i <= 23; ++i) {
        const float x = static_cast<float>(i) / 64.0f;
        const size_t idx =
            (static_cast<size_t>(i) + static_cast<size_t>(cube.n) * i +
             static_cast<size_t>(cube.n) * cube.n * i) * 3;
        const float lutR = cube.rgb[idx];
        const float ours = linearToRec709(
            std::max(miLogToLinear(x), 0.0f));
        CHECK_MESSAGE(std::fabs(ours - lutR) < 2e-2f,
                      "x=", x, " ours=", ours, " lut=", lutR);
        ++checked;
    }
    CHECK(checked == 9);

    // The LUT's display output must be monotonic on the diagonal (sanity
    // for the composed transform we model).
    float prev = -1.0f;
    for (int i = 0; i < 65; ++i) {
        const size_t idx =
            (static_cast<size_t>(i) + static_cast<size_t>(cube.n) * i +
             static_cast<size_t>(cube.n) * cube.n * i) * 3;
        CHECK(cube.rgb[idx] >= prev);
        prev = cube.rgb[idx];
    }
}

TEST_CASE("inverse_roundtrip_monotonic") {
    float prev = -1e9f;
    for (int i = 0; i <= 32; ++i) {
        const float x = static_cast<float>(i) / 32.0f;
        const float y = linearToRec709(miLogToLinear(x));
        CHECK(y >= prev);
        prev = y;
    }
}

TEST_CASE("negative_input_clamps_to_R_0") {
    const float r0 = -0.09023729f;
    CHECK(miLogToLinear(-1.0f) == doctest::Approx(r0));
    CHECK(miLogToLinear(-0.001f) == doctest::Approx(r0));
    // Just above zero enters the toe (sqrt(x/c) + R_0 > R_0).
    CHECK(miLogToLinear(0.0f) == doctest::Approx(r0));
    CHECK(miLogToLinear(0.0001f) > r0);
}

TEST_CASE("developEncoded_grade_and_range") {
    // 4x2 semi-planar NV12 frame, neutral gray (limited-range Y=128 -> mid).
    YuvFrame f;
    f.width = 4;
    f.height = 2;
    f.semiPlanar = true;
    f.y.assign(8, 128);
    f.u.assign(4, 128); // interleaved UV, all neutral
    Grade neutral;
    ImageF img = developEncoded(f, neutral, true);
    CHECK(img.w == 4);
    CHECK(img.h == 2);
    // Limited-range Y=128 expands to full-range (128-16)/219 = 0.512, then
    // the Mi-Log curve decodes it.
    const float mid = img.rgb[0];
    CHECK(mid == doctest::Approx(miLogToLinear((128.0f - 16.0f) / 219.0f))
                              .epsilon(1e-5));
    // All pixels equal (neutral chroma).
    for (size_t i = 0; i < img.rgb.size(); ++i)
        CHECK(img.rgb[i] == doctest::Approx(mid).epsilon(1e-6));

    // +2 EV quadruples the output (2^2).
    Grade ev2;
    ev2.exposureEv = 2.0f;
    ImageF img2 = developEncoded(f, ev2, true);
    CHECK(img2.rgb[0] == doctest::Approx(mid * 4.0f).epsilon(1e-4));

    // Warm temp raises R, lowers B.
    Grade warm;
    warm.tempK = 6600.0f;
    ImageF imgW = developEncoded(f, warm, true);
    CHECK(imgW.rgb[0] > mid);
    CHECK(imgW.rgb[2] < mid);
}

TEST_CASE("developEncoded_detail_and_tonemap_stages") {
    // COL-15/COL-16 on the encoded (Mi-Log) path: the same stages 17-19 the
    // MCRAW pipeline runs apply to a decoded, graded encoded frame. On a
    // FLAT frame every stage is a no-op (a flat region's blur equals its
    // value; tonemap off is passthrough), so the neutral look is preserved
    // exactly — the binding requirement.
    YuvFrame f;
    f.width = 8;
    f.height = 8;
    f.semiPlanar = true;
    f.y.assign(64, 128);
    f.u.assign(64, 128);

    Grade neutral;
    ImageF base = developEncoded(f, neutral, true);

    Grade detail;
    detail.sharpness = 100.0f;
    detail.detail = 100.0f;
    detail.tonemap = 0;
    ImageF flat = developEncoded(f, detail, true);
    for (size_t i = 0; i < base.rgb.size(); ++i)
        CHECK(flat.rgb[i] == doctest::Approx(base.rgb[i]).epsilon(1e-7));

    // Tonemap 0 vs the pre-stage output: bit-for-bit.
    Grade off;
    off.tonemap = 0;
    ImageF offImg = developEncoded(f, off, true);
    for (size_t i = 0; i < base.rgb.size(); ++i)
        CHECK(offImg.rgb[i] == base.rgb[i]);

    // ACES and AgX genuinely apply: the flat frame's linear value is
    // miLogToLinear((128-16)/219) ~= 0.28512 (limited-range Y=128 through
    // the Mi-Log curve), and the fits evaluated at that value are the
    // anchors: ACES 0.42036064, AgX 0.58001508 (computed in float64 from
    // the same definitions test_develop locks for the MCRAW path; a flat
    // linear patch through ACES/AgX depends only on the value, not on
    // where the pixels came from).
    Grade aces;
    aces.tonemap = 1;
    ImageF acesImg = developEncoded(f, aces, true);
    CHECK(acesImg.rgb[0] == doctest::Approx(0.42036064f).epsilon(1e-3));
    CHECK(acesImg.rgb[0] > 0.0f);
    CHECK(acesImg.rgb[0] < 1.0f);

    Grade agx;
    agx.tonemap = 2;
    ImageF agxImg = developEncoded(f, agx, true);
    CHECK(agxImg.rgb[0] == doctest::Approx(0.58001508f).epsilon(1e-3));
    CHECK(agxImg.rgb[0] < 1.0f);

    // And a TEXTURED frame really sharpens: a vertical edge in the luma
    // plane gains local contrast with sharpness on, flat rows untouched.
    YuvFrame e;
    e.width = 16;
    e.height = 16;
    e.semiPlanar = true;
    e.y.assign(256, 0);
    e.u.assign(256, 128);
    for (uint32_t row = 0; row < 16; ++row)
        for (uint32_t col = 0; col < 16; ++col)
            e.y[row * 16 + col] = static_cast<uint8_t>(col < 8 ? 96 : 160);
    ImageF edgePlain = developEncoded(e, Grade{}, true);
    Grade sharp;
    sharp.sharpness = 100.0f;
    ImageF edgeSharp = developEncoded(e, sharp, true);
    auto rowRange = [&](const ImageF& img, uint32_t row) {
        float lo = 1e9f, hi = -1e9f;
        for (uint32_t col = 0; col < 16; ++col) {
            const float v = img.rgb[(size_t(row) * 16 + col) * 3 + 1];
            lo = std::min(lo, v);
            hi = std::max(hi, v);
        }
        return hi - lo;
    };
    CHECK(rowRange(edgeSharp, 8) > rowRange(edgePlain, 8) + 0.005f);
    // Flat interior column far from the edge: unchanged to float rounding
    // (the luma sum and blur normalization round, so a flat region can
    // shift by an ulp or two — visually zero, exactly the old look).
    const size_t left = (size_t(8) * 16 + 2) * 3;
    CHECK(std::abs(edgeSharp.rgb[left + 0] - edgePlain.rgb[left + 0]) < 1e-6f);
    CHECK(std::abs(edgeSharp.rgb[left + 1] - edgePlain.rgb[left + 1]) < 1e-6f);
    CHECK(std::abs(edgeSharp.rgb[left + 2] - edgePlain.rgb[left + 2]) < 1e-6f);
}
