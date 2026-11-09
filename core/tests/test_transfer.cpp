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
