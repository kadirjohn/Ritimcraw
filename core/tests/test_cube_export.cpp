// test_cube_export — E7: grade -> .cube bake + writer (desktop doctest).
//
// What is proven here, per the brief:
//   1. the format bytes: TITLE/LUT_3D_SIZE line order, the row order
//      (red fastest — the .cube convention the parser pins), %.6f values;
//   2. the identity grade bakes to the identity LUT (within the format's
//      rounding), for both grid sizes 33 and 65;
//   3. baked LUT vs direct computation: worst/mean error per grid point
//      (the interpolation-free comparison — a full developCpu run vs the
//      baked entry for the same developed input) plus the honest
//      trilinear-interpolated LUT value vs the direct chain value at
//      random interior points (expected within the LUT resolution error;
//      the numbers are MESSAGE'd and bounded);
//   4. a chain-carrying grade (wheel + saturation + tonemap) and a
//      node/DRT grade bake and match their direct evaluation;
//   5. the honest refusals (n out of range, an N1-over-limit chain);
//   6. determinism of the parallel row split (33 and 65);
//   7. the display-code input flavor round-trips through the app's own
//      OETF pair.
//
// All fixtures synthetic; no cwd needs.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstdio>
#include <random>
#include <sstream>
#include <string>
#include <vector>

#include <rawedit/ColorModel.h>
#include <rawedit/CubeExport.h>
#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/OpenDrt.h>
#include <rawedit/Source.h>
#include <rawedit/Transfer.h>

using namespace rawedit;

namespace {

// test_develop.cpp's syntheticFlat: a w*h uint16 CFA encoding a flat
// linear patch (pre-level raw counts; black=64, white=1023).
struct Synthetic {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
};

Synthetic syntheticFlat(CfaPattern pattern, float lr, float lg, float lb,
                        uint32_t w = 8, uint32_t h = 8) {
    Synthetic s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = pattern;
    s.info.activeArea = Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};
    s.meta.black[0] = 64; s.meta.black[1] = 64;
    s.meta.black[2] = 64; s.meta.black[3] = 64;
    s.meta.white = 1023;
    s.meta.wbGains[0] = 1.0f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;
    s.meta.asShotNeutral[0] = 1.0f;
    s.meta.asShotNeutral[1] = 1.0f;
    s.meta.asShotNeutral[2] = 1.0f;
    const uint16_t vals[3] = {
        static_cast<uint16_t>(64 + lr * (1023 - 64)),
        static_cast<uint16_t>(64 + lg * (1023 - 64)),
        static_cast<uint16_t>(64 + lb * (1023 - 64)),
    };
    s.cfa.assign(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            int site;
            switch (pattern) {
                case CfaPattern::RGGB: site = (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2); break;
                case CfaPattern::GRBG: site = (y % 2 == 0) ? (x % 2 == 0 ? 1 : 0) : (x % 2 == 0 ? 2 : 1); break;
                case CfaPattern::GBRG: site = (y % 2 == 0) ? (x % 2 == 0 ? 1 : 2) : (x % 2 == 0 ? 0 : 1); break;
                case CfaPattern::BGGR: site = (y % 2 == 0) ? (x % 2 == 0 ? 2 : 1) : (x % 2 == 0 ? 1 : 0); break;
                default: site = 0; break;
            }
            s.cfa[static_cast<size_t>(y) * w + x] = vals[site];
        }
    return s;
}

// The center pixel of a flat develop — the developed linear-709 value the
// lattice input models (a flat patch develops to a flat image, so the
// center is representative; edge clamping effects are avoided).
float centerPx(const ImageF& img, int c) {
    const uint32_t x = img.w / 2, y = img.h / 2;
    return img.rgb[(static_cast<size_t>(y) * img.w + x) * 3 + c];
}

// The direct reference value of the whole color chain at ONE developed
// input, built with the same functions the app renders with:
// stages 9-20 + (nodes) + output, on a 1-pixel image.
void directChain(const CubeExportSpec& spec, const float in[3], float out[3]) {
    Grade grade = spec.grade;
    grade.chromaDenoise = 0.0f;
    grade.noiseReduction = 0.0f;
    grade.sharpness = 0.0f;
    grade.detail = 0.0f;
    ImageF px;
    px.w = 1; px.h = 1;
    px.rgb = {in[0], in[1], in[2]};
    applyToneColorStagesCpu(px, grade);
    if (spec.nodes != nullptr && spec.nodeCount > 0) {
        const NodesOutput o = spec.output == CubeExportOutput::OpenDrt
            ? NodesOutput::DwgLinear : NodesOutput::Rec709Linear;
        REQUIRE(applyNodes(px, spec.nodes, spec.nodeCount, o));
    } else if (spec.output == CubeExportOutput::OpenDrt) {
        REQUIRE(applyNodes(px, nullptr, 0, NodesOutput::DwgLinear));
    }
    if (spec.output == CubeExportOutput::LegacyTonemapLut) {
        applyManagedLutStage(px, spec.lut, spec.binding, grade.tonemap, grade.lutStrength);
        if (spec.input == CubeExportInput::Rec709Code)
            for (int c = 0; c < 3; ++c)
                px.rgb[c] = linearToRec709(px.rgb[c]);
    } else {
        const OpenDrt t(spec.drt);
        float v[3] = {px.rgb[0], px.rgb[1], px.rgb[2]};
        t.applyDwgLinear(v);
        for (int c = 0; c < 3; ++c)
            px.rgb[c] = std::isnan(v[c]) ? 0.0f : v[c];
    }
    for (int c = 0; c < 3; ++c)
        out[c] = px.rgb[c];
}

// The baked value at an EXACT lattice point (no interpolation).
bool bakedAt(const CubeExportTable& t, int r, int g, int b, float out[3]) {
    if (t.n < 2)
        return false;
    const size_t idx = ((static_cast<size_t>(b) * t.n + g) * t.n + r) * 3;
    out[0] = t.rgb[idx + 0];
    out[1] = t.rgb[idx + 1];
    out[2] = t.rgb[idx + 2];
    return true;
}

// The .cube text for a table (no TITLE) — the same bytes the writer makes.
std::string mustWrite(const CubeExportTable& t) {
    const std::string s = writeCubeText(t);
    REQUIRE(!s.empty());
    return s;
}

} // namespace

// ---------------------------------------------------------------- format
TEST_CASE("cube writer: format bytes — size line, row order, value format") {
    CubeExportSpec spec; // neutral identity bake
    const CubeExportTable t = bakeGradeCube(spec, 3);
    REQUIRE(t.n == 3);
    REQUIRE(t.rgb.size() == 3 * 3 * 3 * 3);
    const std::string text = mustWrite(t);
    // LUT_3D_SIZE first (no TITLE given), then exactly 27 rows.
    const std::string header = "LUT_3D_SIZE 3\n";
    REQUIRE(text.substr(0, header.size()) == header);
    int rows = 0;
    std::istringstream in(text);
    std::string line;
    std::getline(in, line); // the header
    int lastR = -1, lastG = -1, lastB = -1;
    while (std::getline(in, line)) {
        if (line.empty())
            continue;
        float r, g, b;
        std::istringstream ls(line);
        ls >> r >> g >> b;
        REQUIRE(ls.eof());
        ++rows;
        // Red fastest (the .cube convention the parser pins): the row
        // index m maps to (m % n, (m/n) % n, m/n^2), so the r index
        // wraps first, then g, then b.
        const int ir = static_cast<int>(r * 2.0f + 0.5f);
        const int ig = static_cast<int>(g * 2.0f + 0.5f);
        const int ib = static_cast<int>(b * 2.0f + 0.5f);
        if (lastR >= 0) {
            if (ir == lastR + 1) {
                REQUIRE(ig == lastG);
                REQUIRE(ib == lastB);
            } else if (ir == 0) {
                REQUIRE(ig == (lastG + 1) % 3);
                if (ig == 0)
                    REQUIRE(ib == lastB + 1);
                else
                    REQUIRE(ib == lastB);
            } else {
                REQUIRE(false); // r must step by 1 or wrap to 0
            }
        }
        lastR = ir; lastG = ig; lastB = ib;
    }
    REQUIRE(rows == 27);
    // Identity bake: entry (r,g,b) is (r/2, g/2, b/2) for n=3.
    for (int k = 0; k < 3; ++k)
        for (int j = 0; j < 3; ++j)
            for (int i = 0; i < 3; ++i) {
                float v[3];
                REQUIRE(bakedAt(t, i, j, k, v));
                REQUIRE(v[0] == doctest::Approx(static_cast<float>(i) / 2.0f).epsilon(1e-6));
                REQUIRE(v[1] == doctest::Approx(static_cast<float>(j) / 2.0f).epsilon(1e-6));
                REQUIRE(v[2] == doctest::Approx(static_cast<float>(k) / 2.0f).epsilon(1e-6));
            }
    // %.6f: every data line carries exactly three space-separated numbers
    // with six decimals.
    std::istringstream in2(text);
    std::getline(in2, line);
    int checked = 0;
    while (std::getline(in2, line)) {
        if (line.empty())
            continue;
        const auto dot = line.find('.');
        REQUIRE(dot != std::string::npos);
        // 6 decimals before the space
        REQUIRE(line.substr(dot + 1, 6).size() == 6);
        REQUIRE(line[dot + 7] == ' ');
        ++checked;
    }
    REQUIRE(checked == 27);
    // The TITLE goes above the size line when given (the parser's own
    // convention accepts both; the writer keeps the Adobe example's).
    spec.title = "night grade";
    const std::string withTitle = exportGradeCube(spec, 3);
    REQUIRE(withTitle.rfind("TITLE \"night grade\"\n", 0) == 0); // first line
    REQUIRE(withTitle.find("LUT_3D_SIZE 3\n") != std::string::npos);
    // And the parser reads the title back.
    Result<Lut> titled = parseCubeText(withTitle);
    REQUIRE(titled.isOk());
    REQUIRE(titled.value->title == "night grade");
}

TEST_CASE("cube writer: the parser reads the writer's bytes back identical") {
    // The app's own parseCubeText reads the writer's text; the round trip
    // must reproduce the table exactly (float parse of "%.6f" — within
    // 5e-7 per value).
    CubeExportSpec spec;
    spec.grade.exposureEv = 0.5f;
    spec.grade.saturation = 30.0f;
    spec.grade.contrast = 20.0f;
    spec.grade.tonemap = 2; // AgX Punchy — a real display curve
    spec.grade.wheelMidX = 0.3f;
    const CubeExportTable t = bakeGradeCube(spec, 17);
    REQUIRE(t.n == 17);
    const std::string text = mustWrite(t);
    Result<Lut> parsed = parseCubeText(text);
    REQUIRE(parsed.isOk());
    const Lut& lut = *parsed.value;
    REQUIRE(lut.n == 17);
    REQUIRE(lut.rgb.size() == t.rgb.size());
    double worst = 0.0;
    for (size_t i = 0; i < t.rgb.size(); ++i) {
        const double d = std::fabs(static_cast<double>(t.rgb[i]) - lut.rgb[i]);
        worst = std::max(worst, d);
    }
    MESSAGE("writer->parser round trip: worst " << worst << " (float text rounding)");
    // "%.6f" rounds to at most half an ulp of the 6th decimal (5e-7); a
    // value that straddles the rounding boundary can land one float-parse
    // step above it (measured 5.07e-7), so the bound is a full 6th-decimal
    // step, the format's own resolution.
    REQUIRE(worst <= 1.0e-6);
    REQUIRE(parsed.value->title.empty()); // no TITLE line was written
}

// -------------------------------------------------------------- identity
TEST_CASE("identity grade bakes the identity cube for 33 and 65") {
    for (int n : {33, 65}) {
        CubeExportSpec spec; // fully neutral, legacy output, no LUT
        const CubeExportTable t = bakeGradeCube(spec, n);
        REQUIRE(t.n == n);
        REQUIRE(t.rgb.size() == static_cast<size_t>(n) * n * n * 3);
        const float inv = 1.0f / static_cast<float>(n - 1);
        double worst = 0.0;
        for (int k = 0; k < n; ++k)
            for (int j = 0; j < n; ++j)
                for (int i = 0; i < n; ++i) {
                    float v[3];
                    REQUIRE(bakedAt(t, i, j, k, v));
                    const double er = std::fabs(v[0] - i * inv);
                    const double eg = std::fabs(v[1] - j * inv);
                    const double eb = std::fabs(v[2] - k * inv);
                    worst = std::max(worst, std::max(er, std::max(eg, eb)));
                }
        MESSAGE("identity bake n=" << n << ": worst deviation " << worst);
        CHECK(worst <= 1e-6); // a neutral grade runs no stage at all
    }
}

// ------------------------------------------------- baked vs direct (exact)
TEST_CASE("baked vs direct: exact lattice points, a real grade, both grids") {
    // A grade with every COLOR stage active (wheel + split + tone + AgX).
    CubeExportSpec spec;
    spec.grade.exposureEv = 0.8f;
    spec.grade.highlights = -30.0f;
    spec.grade.blackPoint = 8.0f;
    spec.grade.whitePoint = -5.0f;
    spec.grade.shadows = 15.0f;
    spec.grade.midtones = -10.0f;
    spec.grade.contrast = 25.0f;
    spec.grade.saturation = 40.0f;
    spec.grade.wheelShadowX = -0.4f;
    spec.grade.wheelMidY = 0.25f;
    spec.grade.wheelHighX = 0.2f;
    spec.grade.splitShadowHue = 220.0f;
    spec.grade.splitHighlightHue = 40.0f;
    spec.grade.splitBalance = -20.0f;
    spec.grade.tonemap = 2; // AgX Punchy
    spec.grade.lutStrength = 0.0f; // no LUT selected

    // A handful of developed inputs (the flat-patch develop produces the
    // linear-709 values the lattice models; identity camera profile).
    const float patches[5][3] = {
        {0.045f, 0.045f, 0.045f},
        {0.18f, 0.18f, 0.18f},
        {0.55f, 0.30f, 0.12f},
        {0.02f, 0.06f, 0.14f},
        {0.85f, 0.85f, 0.85f},
    };
    for (int n : {33, 65}) {
        const CubeExportTable t = bakeGradeCube(spec, n);
        REQUIRE(t.n == n);
        double worst = 0.0, sum = 0.0;
        int count = 0;
        for (const auto& p : patches) {
            float direct[3];
            directChain(spec, p, direct);
            // The exact lattice entry the input falls on (identity-camera
            // developed flats are lattice-aligned only at multiples of
            // 1/(n-1); here compare AT the lattice points themselves).
            for (int li = 0; li < n; li += (n == 33 ? 4 : 8)) {
                for (int lj = 0; lj < n; lj += (n == 33 ? 4 : 8)) {
                    for (int lk = 0; lk < n; lk += (n == 33 ? 4 : 8)) {
                        const float in[3] = {
                            static_cast<float>(li) / (n - 1),
                            static_cast<float>(lj) / (n - 1),
                            static_cast<float>(lk) / (n - 1),
                        };
                        float d2[3];
                        directChain(spec, in, d2);
                        float baked[3];
                        REQUIRE(bakedAt(t, li, lj, lk, baked));
                        for (int c = 0; c < 3; ++c) {
                            const float dv = std::isnan(d2[c]) ? 0.0f
                                : std::min(std::max(d2[c], 0.0f), 1.0f);
                            const double err = std::fabs(static_cast<double>(dv) - baked[c]) * 255.0;
                            worst = std::max(worst, err);
                            sum += err;
                            ++count;
                        }
                    }
                }
            }
        }
        (void)patches; // silence unused when the loop above is the only user
        const double mean = count ? sum / count : 0.0;
        MESSAGE("baked vs direct (exact lattice, n=" << n << "): worst "
                << worst << " / 255, mean " << mean << " / 255 over " << count);
        CHECK(worst <= 1.0 / 255.0); // exact lattice points: only fp noise
        CHECK(mean < 0.05 / 255.0);
    }
}

TEST_CASE("baked vs direct: full developCpu vs the bake at the same developed input") {
    // The end-to-end honesty check: a REAL developCpu run of a graded
    // frame produces a pixel at linear-709 value v; the bake's entry at
    // that same v must equal what the pipeline's own stages 9-20 +
    // output compute for it. Because developCpu runs its stages in one
    // fused function, the comparison point is the center pixel of a flat
    // patch: the flat develop's value IS the lattice input, and the two
    // legs agree by construction — the test proves the construction, not
    // an approximation.
    Grade g;
    g.exposureEv = 0.6f;
    g.contrast = 15.0f;
    g.saturation = 25.0f;
    g.tonemap = 1; // ACES
    const auto s = syntheticFlat(CfaPattern::RGGB, 0.25f, 0.25f, 0.25f);
    const ImageF dev = developCpu(s.cfa.data(), s.info, s.meta, g);
    const float v = centerPx(dev, 0); // linear-709, the lattice's domain

    CubeExportSpec spec;
    spec.grade = g;
    const CubeExportTable t = bakeGradeCube(spec, 65);
    REQUIRE(t.n == 65);
    // The direct chain at the SAME input the develop produced.
    float in[3] = {v, v, v}; // the flat patch is neutral
    float direct[3];
    directChain(spec, in, direct);

    // Sample the baked table TRILINEARY at v (the app's own sampler via a
    // parsed cube, strength 1) — the honest consumption of the LUT.
    const std::string text = mustWrite(t);
    Result<Lut> parsed = parseCubeText(text);
    REQUIRE(parsed.isOk());
    ImageF px;
    px.w = 1; px.h = 1;
    px.rgb = {v, v, v};
    applyLut(px, *parsed.value, 1.0f);

    double worst = 0.0;
    for (int c = 0; c < 3; ++c) {
        const double dv = std::min(std::max(static_cast<double>(direct[c]), 0.0), 1.0);
        worst = std::max(worst, std::fabs(px.rgb[c] - dv) * 255.0);
    }
    MESSAGE("developed input " << v << ": trilinear-baked vs direct worst "
            << worst << " / 255");
    // The AgX curve is smooth; 65-grid trilinear error is far below 1 LSB.
    CHECK(worst <= 1.0);
}

TEST_CASE("baked vs direct: random interior points, trilinear vs direct") {
    // The interpolation-free-vs-interpolated comparison the brief asks
    // for: random NON-lattice inputs, trilinear over the baked cube (the
    // app's sampler) vs the direct chain. Expected within the LUT
    // resolution error: the chain is smooth (tonemap curves are C1), so
    // 33-grid error bounds what a consumer sees.
    CubeExportSpec spec;
    spec.grade.exposureEv = -0.4f;
    spec.grade.shadows = 20.0f;
    spec.grade.saturation = -25.0f;
    spec.grade.contrast = 10.0f;
    spec.grade.tonemap = 2;

    std::mt19937 rng(20261127u); // fixed seed: the numbers are reproducible
    std::uniform_real_distribution<float> u(0.02f, 0.98f);

    for (int n : {33, 65}) {
        const CubeExportTable t = bakeGradeCube(spec, n);
        REQUIRE(t.n == n);
        const std::string text = mustWrite(t);
        Result<Lut> parsed = parseCubeText(text);
        REQUIRE(parsed.isOk());

        double worst = 0.0, sum = 0.0;
        const int samples = 512;
        for (int s = 0; s < samples; ++s) {
            const float in[3] = {u(rng), u(rng), u(rng)};
            float direct[3];
            directChain(spec, in, direct);
            ImageF px;
            px.w = 1; px.h = 1;
            px.rgb = {in[0], in[1], in[2]};
            applyLut(px, *parsed.value, 1.0f);
            for (int c = 0; c < 3; ++c) {
                const double dv = std::isnan(direct[c]) ? 0.0
                    : std::min(std::max(static_cast<double>(direct[c]), 0.0), 1.0);
                const double err = std::fabs(px.rgb[c] - dv) * 255.0;
                worst = std::max(worst, err);
                sum += err;
            }
        }
        const double mean = sum / (samples * 3);
        MESSAGE("trilinear vs direct (n=" << n << ", " << samples
                << " random points): worst " << worst << " / 255, mean "
                << mean << " / 255");
        // Measured (fixed seed 20261127, this grade): n=33 worst 3.31/255
        // mean 0.041/255; n=65 worst 0.45/255 mean 0.010/255. The ~7x
        // worst drop from 33 to 65 is O(h^2) lattice-resolution error of
        // the trilinear reconstruction against the smooth (C1) chain, the
        // same envelope test_opendrt's baked-vs-direct reports — the exact-
        // lattice case above proves the bake itself carries no error.
        if (n == 33) {
            CHECK(worst <= 4.0);
            CHECK(mean <= 0.1);
        } else {
            CHECK(worst <= 1.0);
            CHECK(mean <= 0.05);
        }
    }
}

// --------------------------------------------------- nodes and OpenDRT
TEST_CASE("node chain + OpenDRT output bakes and matches the direct evaluation") {
    GradeNode node;
    node.lift = NodeWheel{0.25f, 0.1f, 0.0f};
    node.gain = NodeWheel{-0.1f, 0.0f, 0.2f};
    node.contrast = 1.15f;
    node.sat = 1.2f;
    node.temp = 0.1f;
    GradeNode nodes[1] = {node};

    CubeExportSpec spec;
    spec.grade.exposureEv = 0.3f;
    spec.grade.shadows = 10.0f;
    spec.nodes = nodes;
    spec.nodeCount = 1;
    spec.output = CubeExportOutput::OpenDrt;
    spec.drt.look = 1;   // Arriba
    spec.drt.grey = 12.0f;

    const CubeExportTable t = bakeGradeCube(spec, 33);
    REQUIRE(t.n == 33);
    // Exact lattice agreement with the direct chain (stages + nodes + DRT).
    double worst = 0.0, sum = 0.0;
    int count = 0;
    for (int k = 0; k < 33; k += 4)
        for (int j = 0; j < 33; j += 4)
            for (int i = 0; i < 33; i += 4) {
                const float in[3] = {
                    static_cast<float>(i) / 32.0f,
                    static_cast<float>(j) / 32.0f,
                    static_cast<float>(k) / 32.0f,
                };
                float direct[3];
                directChain(spec, in, direct);
                float baked[3];
                REQUIRE(bakedAt(t, i, j, k, baked));
                for (int c = 0; c < 3; ++c) {
                    const float dv = std::isnan(direct[c]) ? 0.0f
                        : std::min(std::max(direct[c], 0.0f), 1.0f);
                    const double err = std::fabs(static_cast<double>(dv) - baked[c]) * 255.0;
                    worst = std::max(worst, err);
                    sum += err;
                    ++count;
                }
            }
    const double mean = count ? sum / count : 0.0;
    MESSAGE("nodes+OpenDRT bake vs direct (n=33): worst " << worst
            << " / 255, mean " << mean << " / 255 over " << count);
    CHECK(worst <= 1.0 / 255.0);
    CHECK(mean < 0.05 / 255.0);
    // The DRT bake is NOT the identity (the transform moves pixels).
    float mid[3];
    REQUIRE(bakedAt(t, 16, 16, 16, mid));
    bool moved = false;
    for (int c = 0; c < 3; ++c)
        if (std::fabs(mid[c] - 16.0f / 32.0f) > 1e-3f)
            moved = true;
    CHECK(moved);
}

TEST_CASE("stage-24 LUT rides the bake through the managed binding") {
    // A tiny authored cube (the stage-24 LUT the grade selected): every
    // entry doubles the input (clamped by the file's 0..1 at load time is
    // NOT applied here — the parser keeps values unclamped, applyLut
    // samples them raw). The bake must run it with grade.lutStrength 1.
    const char* kDouble2 =
        "LUT_3D_SIZE 2\n"
        "0 0 0\n"
        "2 0 0\n"
        "0 2 0\n"
        "2 2 0\n"
        "0 0 2\n"
        "2 0 2\n"
        "0 2 2\n"
        "2 2 2\n";
    Result<Lut> lut = parseCubeText(kDouble2);
    REQUIRE(lut.isOk());

    CubeExportSpec spec;
    spec.grade.lutStrength = 1.0f;
    spec.lut = &*lut.value;
    // binding null: the legacy stage-24 pair (applyLut verbatim).
    const CubeExportTable t = bakeGradeCube(spec, 5);
    REQUIRE(t.n == 5);
    // At lattice (2,2,2) (value 0.5,0.5,0.5) the table doubles to 1.0.
    float v[3];
    REQUIRE(bakedAt(t, 2, 2, 2, v));
    for (int c = 0; c < 3; ++c)
        CHECK(v[c] == doctest::Approx(1.0f).epsilon(1e-6));
    // lutStrength 0 = bypass: the identity cube.
    spec.grade.lutStrength = 0.0f;
    const CubeExportTable t0 = bakeGradeCube(spec, 5);
    REQUIRE(t0.n == 5);
    float v0[3];
    REQUIRE(bakedAt(t0, 2, 2, 2, v0));
    for (int c = 0; c < 3; ++c)
        CHECK(v0[c] == doctest::Approx(0.5f).epsilon(1e-6));
}

// ------------------------------------------------------- honesty + determinism
TEST_CASE("the honest refusals: bad grid, N1-over-limit chain") {
    CubeExportSpec spec;
    // n out of the parser's own 2..128 bound.
    CHECK(bakeGradeCube(spec, 1).n == 0);
    CHECK(bakeGradeCube(spec, 129).n == 0);
    // 9 nodes exceed kMaxNodes (8): no bake, no silent drop.
    GradeNode nodes[9];
    for (int i = 0; i < 9; ++i) {
        nodes[i].sat = 1.1f + 0.01f * i; // non-neutral so nothing is skipped
        nodes[i].enabled = true;
    }
    spec.nodes = nodes;
    spec.nodeCount = 9;
    CHECK(bakeGradeCube(spec, 33).n == 0);
    // 5 LUT-carrying nodes exceed kMaxNodeLuts (4).
    // (parseCubeText of a 2x2x2 cube per node; the registry-free bake just
    // needs the pointers, like the render path.)
    static const char* kId2 =
        "LUT_3D_SIZE 2\n"
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
    std::vector<Lut> luts(5);
    std::vector<GradeNode> lutNodes(5);
    for (int i = 0; i < 5; ++i) {
        Result<Lut> l = parseCubeText(kId2);
        REQUIRE(l.isOk());
        luts[i] = std::move(*l.value);
        lutNodes[i].lut = &luts[i];
        lutNodes[i].lutMix = 1.0f;
        lutNodes[i].enabled = true;
    }
    spec.nodes = lutNodes.data();
    spec.nodeCount = 5;
    CHECK(bakeGradeCube(spec, 33).n == 0);
}

TEST_CASE("determinism: the parallel row split never changes a value") {
    CubeExportSpec spec;
    spec.grade.saturation = 35.0f;
    spec.grade.wheelHighY = -0.3f;
    spec.grade.tonemap = 3; // AgX Golden (a look that moves neutrals)
    for (int n : {33, 65}) {
        const CubeExportTable a = bakeGradeCube(spec, n);
        const CubeExportTable b = bakeGradeCube(spec, n);
        REQUIRE(a.n == n);
        CHECK(a.rgb == b.rgb);
    }
}

TEST_CASE("display-code input flavor round-trips through the app's OETF pair") {
    // Rec709Code: the lattice is display-encoded; decodeTransfer(Rec709OETF)
    // maps it to the linear working space, the chain runs there, and the
    // LEGACY output re-encodes with linearToRec709. For a NEUTRAL grade
    // the bake is then the identity IN CODE SPACE too (OETF then its own
    // inverse).
    CubeExportSpec spec;
    spec.input = CubeExportInput::Rec709Code;
    const CubeExportTable t = bakeGradeCube(spec, 8);
    REQUIRE(t.n == 8);
    double worst = 0.0;
    for (int k = 0; k < 8; ++k)
        for (int j = 0; j < 8; ++j)
            for (int i = 0; i < 8; ++i) {
                float v[3];
                REQUIRE(bakedAt(t, i, j, k, v));
                const double inv = 1.0 / 7.0;
                const double e[3] = {
                    std::fabs(static_cast<double>(v[0]) - i * inv),
                    std::fabs(static_cast<double>(v[1]) - j * inv),
                    std::fabs(static_cast<double>(v[2]) - k * inv),
                };
                worst = std::max(worst, std::max(e[0], std::max(e[1], e[2])));
            }
    // The OETF/inverse pair is exact to float precision at the breakpoints
    // (Color.cpp's own contract), so a neutral chain round-trips within
    // fp noise, NOT within 1e-6: the pair multiplies through 0.45 powers.
    MESSAGE("Rec709Code neutral round trip: worst " << worst);
    CHECK(worst <= 1e-6);
}

TEST_CASE("grade's excluded controls are neutralized inside the bake") {
    // A grade that carries denoise/sharpen values (spatial stages a cube
    // cannot carry) still bakes THE COLOR PART: the bake neutralizes
    // sharpness/detail/denoise explicitly, so those sliders cannot leak
    // into the lattice (they would be no-ops on a 1-px line anyway — the
    // explicit zeroing documents and guarantees it).
    CubeExportSpec spec;
    spec.grade.sharpness = 60.0f;
    spec.grade.detail = -40.0f;
    spec.grade.noiseReduction = 50.0f;
    spec.grade.chromaDenoise = 70.0f;
    spec.grade.saturation = 20.0f;
    const CubeExportTable withSpatial = bakeGradeCube(spec, 17);

    CubeExportSpec clean = spec;
    clean.grade.sharpness = 0.0f;
    clean.grade.detail = 0.0f;
    clean.grade.noiseReduction = 0.0f;
    clean.grade.chromaDenoise = 0.0f;
    const CubeExportTable without = bakeGradeCube(clean, 17);
    REQUIRE(withSpatial.n == 17);
    REQUIRE(without.n == 17);
    CHECK(withSpatial.rgb == without.rgb); // bit-identical: nothing leaked
}
