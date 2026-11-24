// test_transfer — Mi-Log curve + encoded develop.
//
// milog_matches_vendor_lut samples the vendored 65^3 cube
// (third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube) and compares
// linearToRec709(miLogToLinear(x)) against the LUT's R output. The LUT
// composes curve + Rec.2020->709 gamut + OETF, so exactness is not
// expected (tolerance 2e-2; see the DCTL for the intended composition).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Color.h>
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
    // COL-15/COL-16 on the encoded (Mi-Log) path: the same stages 19-21 the
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

// ---------------------------------------------------------------------
// E0: bit depth / range / matrix / transfer / primaries are explicit.
// The oracle below is an independent double-precision implementation of
// the SPEC (ITU-R BT.2020 NCL matrix, P010 code extraction, full/limited
// expansion, the Mi-Log DCTL constants, the BT.2020 -> BT.709 matrix of
// ITU-R BT.2087) - it shares no code with Transfer.cpp.
// ---------------------------------------------------------------------
namespace e0 {

double miLogOracle(double x) {
    const double R0 = -0.09023729, Rt = 0.01974185, A = 0.09271529,
                 B = 0.01384578, G = 0.67291850, C = 18.10531998;
    const double Pt = C * (Rt - R0) * (Rt - R0);
    if (x < 0) return R0;
    if (x >= Pt) return std::pow(2.0, (x - G) / A) - B;
    return std::sqrt(x / C) + R0;
}

// BT.2020 NCL: Kr 0.2627, Kb 0.0593 (derived coefficients, not copied).
void ycc2020(double y, double cb, double cr, double rgb[3]) {
    const double Kr = 0.2627, Kb = 0.0593, Kg = 1.0 - Kr - Kb;
    rgb[0] = y + 2.0 * (1.0 - Kr) * cr;
    rgb[2] = y + 2.0 * (1.0 - Kb) * cb;
    rgb[1] = (y - Kr * rgb[0] - Kb * rgb[2]) / Kg;
}

// Linear BT.2020 -> BT.709 (shared D65 white), ITU-R BT.2087 (6 dp).
const double M2020to709[9] = {
     1.660491, -0.587641, -0.072850,
    -0.124550,  1.132900, -0.008349,
    -0.018151, -0.100579,  1.118730};

// Develop one pixel by the spec. codeY/Cb/Cr are 10-bit CODES.
void oraclePixel10(int codeY, int codeCb, int codeCr, bool full, bool gamut,
                   double out[3]) {
    double y, cb, cr;
    if (full) {
        y = codeY / 1023.0;
        cb = (codeCb - 512.0) / 1023.0;
        cr = (codeCr - 512.0) / 1023.0;
    } else {
        y = (codeY - 64.0) / 876.0;
        cb = (codeCb - 512.0) / 896.0;
        cr = (codeCr - 512.0) / 896.0;
    }
    double rgb[3];
    ycc2020(y, cb, cr, rgb);
    for (int i = 0; i < 3; ++i) rgb[i] = miLogOracle(rgb[i]);
    if (gamut) {
        double t[3];
        for (int i = 0; i < 3; ++i)
            t[i] = M2020to709[i * 3] * rgb[0] + M2020to709[i * 3 + 1] * rgb[1] +
                   M2020to709[i * 3 + 2] * rgb[2];
        for (int i = 0; i < 3; ++i) out[i] = t[i];
    } else {
        for (int i = 0; i < 3; ++i) out[i] = rgb[i];
    }
}

void put16(std::vector<uint8_t>& buf, size_t sample, int code10, int lowGarbage = 0) {
    const unsigned word = (static_cast<unsigned>(code10) << 6) | static_cast<unsigned>(lowGarbage & 63);
    buf[sample * 2] = static_cast<uint8_t>(word & 0xFF);
    buf[sample * 2 + 1] = static_cast<uint8_t>(word >> 8);
}

// A P010 frame: w x h luma + (w/2 x h/2) interleaved CbCr, deterministic
// "image" (gradient + chroma patches), 16-bit LE MSB-aligned words.
struct Frame10 {
    YuvFrame f;
    std::vector<int> Y, Cb, Cr; // codes, per luma pixel / per chroma sample
};

Frame10 makeP010(uint32_t w, uint32_t h, bool full, int lowGarbage = 0) {
    Frame10 o;
    o.f.width = w;
    o.f.height = h;
    o.f.semiPlanar = true;
    o.f.bitDepth = 10;
    o.f.fullRange = full;
    o.f.matrix = YuvMatrix::BT2020;
    o.f.transfer = EncodedTransfer::MiLog;
    o.f.primaries = EncodedPrimaries::BT2020;
    o.f.stride = static_cast<int>(w) * 2;
    o.f.uvStride = static_cast<int>(w / 2) * 4;
    o.f.y.assign(static_cast<size_t>(w) * h * 2, 0);
    o.f.u.assign(static_cast<size_t>(w / 2) * (h / 2) * 4, 0);
    const int lo = full ? 0 : 64, hi = full ? 1023 : 940;
    o.Y.resize(static_cast<size_t>(w) * h);
    for (uint32_t r = 0; r < h; ++r)
        for (uint32_t c = 0; c < w; ++c) {
            const int code = lo + static_cast<int>((hi - lo) * (c * 7 + r * 3) / (7 * (w - 1) + 3 * (h - 1)));
            o.Y[r * w + c] = code;
            put16(o.f.y, r * w + c, code, lowGarbage);
        }
    o.Cb.resize(static_cast<size_t>(w / 2) * (h / 2));
    o.Cr.resize(o.Cb.size());
    for (uint32_t r = 0; r < h / 2; ++r)
        for (uint32_t c = 0; c < w / 2; ++c) {
            const int cb = 512 + static_cast<int>(((c * 5 + r * 11) % 17) * 16) - 128;
            const int cr = 512 + static_cast<int>(((c * 13 + r * 3) % 19) * 14) - 126;
            o.Cb[r * (w / 2) + c] = cb;
            o.Cr[r * (w / 2) + c] = cr;
            put16(o.f.u, (r * (w / 2) + c) * 2, cb, lowGarbage);
            put16(o.f.u, (r * (w / 2) + c) * 2 + 1, cr, lowGarbage);
        }
    return o;
}

Grade neutralGrade() {
    Grade g; // tempK 5600, tint 0, exposure 0: unit gains
    return g;
}

} // namespace e0

TEST_CASE("e0_p010_full_bt2020_milog_matches_oracle") {
    for (bool full : {true, false}) {
        auto fr = e0::makeP010(32, 16, full);
        ImageF img = developEncoded(fr.f, e0::neutralGrade(), true);
        REQUIRE(img.w == 32);
        REQUIRE(img.h == 16);
        double worst = 0;
        for (uint32_t r = 0; r < 16; ++r)
            for (uint32_t c = 0; c < 32; ++c) {
                double want[3];
                e0::oraclePixel10(fr.Y[r * 32 + c], fr.Cb[(r / 2) * 16 + c / 2],
                                  fr.Cr[(r / 2) * 16 + c / 2], full, true, want);
                for (int ch = 0; ch < 3; ++ch) {
                    const double got = img.rgb[(r * 32 + c) * 3 + ch];
                    // float vs double; the limit is core's pinned 4-dp sRGB matrix (Color.cpp)
                    // vs the exact BT.2087 matrix (~1e-4 per entry) times the LARGEST channel,
                    // since off-diagonal terms mix a bright neighbour channel (Mi-Log reaches 11.5)
                    const double wmax = std::max({std::fabs(want[0]), std::fabs(want[1]), std::fabs(want[2])});
                    const double tol = 1.5e-3 + 1.2e-3 * wmax;
                    worst = std::max(worst, std::fabs(got - want[ch]) / tol);
                    CHECK_MESSAGE(std::fabs(got - want[ch]) <= tol,
                                  "full=", full, " r=", r, " c=", c, " ch=", ch,
                                  " got=", got, " want=", want[ch]);
                }
            }
        MESSAGE("e0 oracle worst error / tolerance, full=", full, ": ", worst);
    }
}

TEST_CASE("e0_p010_low_bits_are_ignored_and_codes_are_msb_aligned") {
    auto clean = e0::makeP010(16, 8, true, 0);
    auto dirty = e0::makeP010(16, 8, true, 63); // the 6 padding bits set
    ImageF a = developEncoded(clean.f, e0::neutralGrade(), true);
    ImageF b = developEncoded(dirty.f, e0::neutralGrade(), true);
    REQUIRE(a.rgb.size() == b.rgb.size());
    for (size_t i = 0; i < a.rgb.size(); ++i) CHECK(a.rgb[i] == b.rgb[i]);

    // A 10-bit white (full range, code 1023, neutral chroma) is 1.0, NOT
    // 1023/255: the word is read as 10 bits.
    YuvFrame f;
    f.width = 2; f.height = 2; f.bitDepth = 10; f.fullRange = true;
    f.matrix = YuvMatrix::BT2020;
    f.transfer = EncodedTransfer::Linear;
    f.y.assign(8, 0); f.u.assign(8, 0);
    for (int i = 0; i < 4; ++i) e0::put16(f.y, i, 1023);
    e0::put16(f.u, 0, 512); e0::put16(f.u, 1, 512);
    ImageF img = developEncoded(f, e0::neutralGrade(), true);
    for (float v : img.rgb) CHECK(v == doctest::Approx(1.0f).epsilon(1e-5));
}

TEST_CASE("e0_range_expansion_limited_vs_full") {
    // Limited 10-bit: 64 -> 0, 940 -> 1. Full 10-bit: 0 -> 0, 1023 -> 1.
    // Linear transfer so the expansion is observable directly.
    auto gray = [](int code, bool full) {
        YuvFrame f;
        f.width = 2; f.height = 2; f.bitDepth = 10; f.fullRange = full;
        f.matrix = YuvMatrix::BT709;
        f.transfer = EncodedTransfer::Linear;
        f.y.assign(8, 0); f.u.assign(8, 0);
        for (int i = 0; i < 4; ++i) e0::put16(f.y, i, code);
        e0::put16(f.u, 0, 512); e0::put16(f.u, 1, 512);
        return developEncoded(f, e0::neutralGrade(), true).rgb[0];
    };
    CHECK(gray(64, false) == doctest::Approx(0.0f).epsilon(1e-5));
    CHECK(gray(940, false) == doctest::Approx(1.0f).epsilon(1e-5));
    CHECK(gray(0, true) == doctest::Approx(0.0f).epsilon(1e-5));
    CHECK(gray(1023, true) == doctest::Approx(1.0f).epsilon(1e-5));
    CHECK(gray(512, true) == doctest::Approx(512.0f / 1023.0f).epsilon(1e-5));
    CHECK(gray(512, false) == doctest::Approx((512.0f - 64.0f) / 876.0f).epsilon(1e-5));
}

TEST_CASE("e0_gray_stays_gray_through_the_2020_gamut_step") {
    // The BT.2020 -> 709 matrix rows sum to 1 (shared D65 white): neutral
    // stays neutral.
    auto fr = e0::makeP010(8, 8, true);
    for (size_t i = 0; i < fr.f.u.size() / 2; ++i) e0::put16(fr.f.u, i, 512);
    ImageF img = developEncoded(fr.f, e0::neutralGrade(), true);
    for (size_t p = 0; p < img.rgb.size(); p += 3) {
        CHECK(img.rgb[p] == doctest::Approx(img.rgb[p + 1]).epsilon(1e-3));
        CHECK(img.rgb[p] == doctest::Approx(img.rgb[p + 2]).epsilon(1e-3));
    }
}

TEST_CASE("e0_legacy_defaults_are_bit_identical_to_pre_e0") {
    // 8-bit limited NV12, BT.709 flag, defaults: the exact pre-E0 math.
    YuvFrame f;
    f.width = 6; f.height = 4; f.semiPlanar = true;
    f.y.resize(24); f.u.resize(12);
    for (size_t i = 0; i < f.y.size(); ++i) f.y[i] = static_cast<uint8_t>(16 + (i * 9) % 220);
    for (size_t i = 0; i < f.u.size(); ++i) f.u[i] = static_cast<uint8_t>(40 + (i * 23) % 180);
    for (bool bt709 : {true, false}) {
        ImageF img = developEncoded(f, e0::neutralGrade(), bt709);
        for (uint32_t r = 0; r < 4; ++r)
            for (uint32_t c = 0; c < 6; ++c) {
                const float y = f.y[r * 6 + c];
                const float u = f.u[(r / 2) * 6 + (c / 2) * 2];
                const float v = f.u[(r / 2) * 6 + (c / 2) * 2 + 1];
                const float yc = (y - 16.0f) / 219.0f;
                const float cb = (u - 128.0f) / 224.0f;
                const float cr = (v - 128.0f) / 224.0f;
                float rr, gg, bb;
                if (bt709) {
                    rr = yc + 1.5748f * cr;
                    gg = yc - 0.187324f * cb - 0.468124f * cr;
                    bb = yc + 1.8556f * cb;
                } else {
                    rr = yc + 1.402f * cr;
                    gg = yc - 0.344136f * cb - 0.714136f * cr;
                    bb = yc + 1.772f * cb;
                }
                const float want[3] = {miLogToLinear(rr), miLogToLinear(gg), miLogToLinear(bb)};
                for (int ch = 0; ch < 3; ++ch)
                    CHECK(img.rgb[(r * 6 + c) * 3 + ch] == want[ch]);
            }
    }
}

TEST_CASE("e0_sdr_transfers_decode_through_color_h") {
    // A Rec.709-tagged SDR clip must NOT get the Mi-Log inverse.
    YuvFrame f;
    f.width = 2; f.height = 2; f.y.assign(4, 128); f.u.assign(2, 128);
    f.matrix = YuvMatrix::BT709;
    f.transfer = EncodedTransfer::Rec709;
    const float code = (128.0f - 16.0f) / 219.0f;
    ImageF img = developEncoded(f, e0::neutralGrade(), true);
    CHECK(img.rgb[0] == doctest::Approx(decodeTransfer(code, TransferId::Rec709OETF)).epsilon(1e-6));
    f.transfer = EncodedTransfer::Srgb;
    img = developEncoded(f, e0::neutralGrade(), true);
    CHECK(img.rgb[0] == doctest::Approx(decodeTransfer(code, TransferId::Srgb)).epsilon(1e-6));
}
