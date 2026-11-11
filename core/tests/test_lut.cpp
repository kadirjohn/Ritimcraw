// test_lut — .cube 3D LUT parse + apply (COL-07).
//
// Parser cases run on hand-written cube text (istringstream) so every
// branch is deterministic. The vendored 65^3 cube
// (third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube) is parsed for real
// when present (WARN + skip otherwise, the test_transfer.cpp convention)
// and sampled at exact lattice points. applyLut is checked on exact
// lattice coordinates (no interpolation), between-lattice coordinates
// (trilinear), strength 0/0.5/1, DOMAIN clamping, and the no-LUT
// bit-identity of developCpu/developEncoded (golden hash re-lock).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/Source.h>
#include <rawedit/Transfer.h>

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// A 2x2x2 identity cube: entry (i, j, k) = (i, j, k) / 1 — the lattice
// spans [0,1]^3 and maps every lattice point to itself.
const char* kIdentity2 =
    "TITLE \"identity\"\n"
    "LUT_3D_SIZE 2\n"
    "0 0 0\n"
    "1 0 0\n"
    "0 1 0\n"
    "1 1 0\n"
    "0 0 1\n"
    "1 0 1\n"
    "0 1 1\n"
    "1 1 1\n";

// A 2x2x2 double cube: maps (r, g, b) -> (2r, 2g, 2b). Values above 1 are
// legal in .cube (HDR cubes) and must be kept unclamped.
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

// A 3x3x3 cube with a per-axis ramp: entry (i, j, k) = (i/2, j/2, k/2).
// Between-lattice trilinear on this table is exact linear interpolation,
// so the expected value is computable in closed form.
std::string ramp3() {
    std::ostringstream os;
    os << "LUT_3D_SIZE 3\n";
    for (int k = 0; k < 3; ++k)
        for (int j = 0; j < 3; ++j)
            for (int i = 0; i < 3; ++i)
                os << (i / 2.0) << " " << (j / 2.0) << " " << (k / 2.0) << "\n";
    return os.str();
}

Lut mustParse(const char* text) {
    Result<Lut> r = parseCubeText(text);
    REQUIRE(r.isOk());
    return std::move(*r.value);
}

// One-pixel ImageF helper.
ImageF px(float r, float g, float b) {
    ImageF img;
    img.w = 1;
    img.h = 1;
    img.rgb = {r, g, b};
    return img;
}

float at(const ImageF& img, int c) { return img.rgb[c]; }

} // namespace

TEST_CASE("parse_identity_cube") {
    Lut lut = mustParse(kIdentity2);
    CHECK(lut.n == 2);
    CHECK(lut.rgb.size() == 8 * 3);
    CHECK(lut.title == "identity");
    CHECK(lut.domainMin[0] == 0.0f);
    CHECK(lut.domainMax[0] == 1.0f);
    // Red-fastest row order: row 1 is (1, 0, 0).
    CHECK(lut.rgb[3] == 1.0f);
    CHECK(lut.rgb[4] == 0.0f);
    CHECK(lut.rgb[5] == 0.0f);
    // Row 4 (index i=0, j=0, k=1) is (0, 0, 1).
    CHECK(lut.rgb[12] == 0.0f);
    CHECK(lut.rgb[14] == 1.0f);
}

TEST_CASE("parse_skips_comments_blanks_and_unknown_keywords") {
    std::string text = std::string("# a comment\n\n") +
        "LUT_3D_SIZE 2\n" +
        "# mid-table comment\n" +
        "0 0 0 # trailing comment\n" +
        "1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
    Lut lut = mustParse(text.c_str());
    CHECK(lut.n == 2);
    CHECK(lut.rgb.size() == 24);
    CHECK(lut.rgb[6] == 0.0f); // row 2 = (0, 1, 0), comment stripped
    CHECK(lut.rgb[7] == 1.0f);
}

TEST_CASE("parse_title_domain_and_out_of_range_values") {
    const char* text =
        "TITLE \"teal & orange\"\n"
        "DOMAIN_MIN 0.0 0.0 0.0\n"
        "DOMAIN_MAX 1.0 1.0 1.0\n"
        "LUT_3D_SIZE 2\n"
        "0 0 0\n1.5 0 0\n0 -1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
    Lut lut = mustParse(text);
    CHECK(lut.title == "teal & orange");
    // Out-of-range table values are kept unclamped (HDR cubes).
    CHECK(lut.rgb[3] == 1.5f);
    CHECK(lut.rgb[7] == -1.0f);
}

TEST_CASE("parse_vendor_cube_65") {
    const char* paths[] = {
        "third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
        "../third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
    };
    std::string found;
    for (const char* p : paths) {
        std::ifstream probe(p);
        if (probe) {
            found = p;
            break;
        }
    }
    if (found.empty()) {
        WARN_MESSAGE(false, "vendor cube not found — skipping LUT parse check");
        return;
    }
    Result<Lut> r = parseCubeFile(found);
    REQUIRE(r.isOk());
    Lut& lut = *r.value;
    REQUIRE(lut.n == 65);
    REQUIRE(lut.rgb.size() == static_cast<size_t>(65) * 65 * 65 * 3);
    // Known rows, measured from the file: row 0 = (0, 0, 0), row 1
    // (i=1, j=0, k=0) = (0.00117494, 0, 0), last row = (1, 1, 1).
    CHECK(lut.rgb[0] == doctest::Approx(0.0f));
    CHECK(lut.rgb[3] == doctest::Approx(0.00117494f).epsilon(1e-4));
    CHECK(lut.rgb[4] == doctest::Approx(0.0f));
    CHECK(lut.rgb.back() == doctest::Approx(1.0f));
}

TEST_CASE("parse_error_cases") {
    const char* cases[] = {
        // missing size
        "TITLE \"x\"\n0 0 0\n",
        // size below 2
        "LUT_3D_SIZE 1\n0 0 0\n",
        // malformed size
        "LUT_3D_SIZE abc\n0 0 0\n",
        // data before the size line
        "0 0 0\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
        // short table
        "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n",
        // malformed row (two numbers)
        "LUT_3D_SIZE 2\n0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
        // non-numeric row inside the table
        "LUT_3D_SIZE 2\n0 0 0\n1 0 0\nTITLE \"inside\"\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
        // too many rows
        "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n0.5 0.5 0.5\n",
        // duplicate size
        "LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
    };
    for (const char* text : cases) {
        Result<Lut> r = parseCubeText(text);
        const bool badCode =
            r.code == ErrorCode::BadHeader || r.code == ErrorCode::Truncated;
        CHECK_MESSAGE(!r.isOk(), "expected failure for: ", text);
        CHECK_MESSAGE(badCode, "expected badHeader/truncated for: ", text);
    }

    // 1D cubes are rejected as unsupported.
    Result<Lut> oneD = parseCubeText("LUT_1D_SIZE 2\n0\n1\n");
    CHECK(!oneD.isOk());
    CHECK(oneD.code == ErrorCode::Unsupported);

    // Oversized grids are rejected as unsupported.
    Result<Lut> huge = parseCubeText("LUT_3D_SIZE 129\n");
    CHECK(!huge.isOk());
    CHECK(huge.code == ErrorCode::Unsupported);

    // Degenerate domain is rejected.
    Result<Lut> dom = parseCubeText(
        "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 0.5 0.5 0.5\n"
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n");
    CHECK(dom.isOk()); // a valid, smaller domain
    Result<Lut> badDom = parseCubeText(
        "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 0 0 0\n"
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n");
    CHECK(!badDom.isOk());
    CHECK(badDom.code == ErrorCode::BadHeader);

    // Unopenable file.
    Result<Lut> missing = parseCubeFile("no/such/lut.cube");
    CHECK(!missing.isOk());
    CHECK(missing.code == ErrorCode::IoError);
}

TEST_CASE("apply_identity_cube_is_passthrough_at_full_strength") {
    Lut lut = mustParse(kIdentity2);
    ImageF img = px(0.25f, 0.5f, 1.0f);
    applyLut(img, lut, 1.0f);
    CHECK(at(img, 0) == doctest::Approx(0.25f).epsilon(1e-6));
    CHECK(at(img, 1) == doctest::Approx(0.5f).epsilon(1e-6));
    CHECK(at(img, 2) == doctest::Approx(1.0f).epsilon(1e-6));
}

TEST_CASE("apply_exact_lattice_points_skip_interpolation") {
    // The double cube at lattice corners: identity cube corners doubled.
    Lut dbl = mustParse(kDouble2);
    ImageF corner = px(1.0f, 0.0f, 1.0f);
    applyLut(corner, dbl, 1.0f);
    CHECK(at(corner, 0) == doctest::Approx(2.0f).epsilon(1e-6));
    CHECK(at(corner, 1) == doctest::Approx(0.0f).epsilon(1e-6));
    CHECK(at(corner, 2) == doctest::Approx(2.0f).epsilon(1e-6));
}

TEST_CASE("apply_trilinear_between_lattice_points") {
    // Ramp3: entry (i, j, k) = (i/2, j/2, k/2) over [0,1]^3. Trilinear
    // interpolation between lattice samples is then EXACT linear
    // interpolation, so (0.5, 0.25, 0.75) maps to itself.
    Lut lut = mustParse(ramp3().c_str());

    ImageF img = px(0.5f, 0.25f, 0.75f);
    applyLut(img, lut, 1.0f);
    CHECK(at(img, 0) == doctest::Approx(0.5f).epsilon(1e-6));
    CHECK(at(img, 1) == doctest::Approx(0.25f).epsilon(1e-6));
    CHECK(at(img, 2) == doctest::Approx(0.75f).epsilon(1e-6));

    // Non-identity table: the double cube at a half corner — (0.5, 0.5, 0.5)
    // is the center of the cell; every corner is (2r, 2g, 2b), and
    // trilinear at the cell center averages the 8 corners = (1, 1, 1).
    Lut dbl = mustParse(kDouble2);
    ImageF mid = px(0.5f, 0.5f, 0.5f);
    applyLut(mid, dbl, 1.0f);
    CHECK(at(mid, 0) == doctest::Approx(1.0f).epsilon(1e-6));
    CHECK(at(mid, 1) == doctest::Approx(1.0f).epsilon(1e-6));
    CHECK(at(mid, 2) == doctest::Approx(1.0f).epsilon(1e-6));

    // Asymmetric point: (0.25, 0.5, 0.75) on the double cube doubles each
    // channel by linearity.
    ImageF asym = px(0.25f, 0.5f, 0.75f);
    applyLut(asym, dbl, 1.0f);
    CHECK(at(asym, 0) == doctest::Approx(0.5f).epsilon(1e-6));
    CHECK(at(asym, 1) == doctest::Approx(1.0f).epsilon(1e-6));
    CHECK(at(asym, 2) == doctest::Approx(1.5f).epsilon(1e-6));
}

TEST_CASE("apply_strength_blend") {
    Lut dbl = mustParse(kDouble2);

    // strength 0 = exact bypass.
    ImageF zero = px(0.3f, 0.4f, 0.5f);
    applyLut(zero, dbl, 0.0f);
    CHECK(at(zero, 0) == doctest::Approx(0.3f).epsilon(1e-6));
    CHECK(at(zero, 1) == doctest::Approx(0.4f).epsilon(1e-6));
    CHECK(at(zero, 2) == doctest::Approx(0.5f).epsilon(1e-6));

    // strength 0.5 = mean of table output and original.
    ImageF half = px(0.3f, 0.4f, 0.5f);
    applyLut(half, dbl, 0.5f);
    CHECK(at(half, 0) == doctest::Approx(0.45f).epsilon(1e-6));
    CHECK(at(half, 1) == doctest::Approx(0.6f).epsilon(1e-6));
    CHECK(at(half, 2) == doctest::Approx(0.75f).epsilon(1e-6));

    // strength 1 = pure table.
    ImageF full = px(0.3f, 0.4f, 0.5f);
    applyLut(full, dbl, 1.0f);
    CHECK(at(full, 0) == doctest::Approx(0.6f).epsilon(1e-6));
    CHECK(at(full, 1) == doctest::Approx(0.8f).epsilon(1e-6));
    CHECK(at(full, 2) == doctest::Approx(1.0f).epsilon(1e-6));

    // Above 1 clamps to the pure table.
    ImageF over = px(0.3f, 0.4f, 0.5f);
    applyLut(over, dbl, 2.0f);
    CHECK(at(over, 0) == doctest::Approx(0.6f).epsilon(1e-6));
}

TEST_CASE("apply_clamps_outside_domain_to_table_edges") {
    // Domain [-1, 1]^3 on the identity cube: input above the max samples
    // the (1, 1, 1) corner, input below the min samples (0, 0, 0).
    const char* text =
        "DOMAIN_MIN -1.0 -1.0 -1.0\n"
        "DOMAIN_MAX 1.0 1.0 1.0\n"
        "LUT_3D_SIZE 2\n"
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
    Lut lut = mustParse(text);

    ImageF above = px(5.0f, 5.0f, 5.0f);
    applyLut(above, lut, 1.0f);
    CHECK(at(above, 0) == doctest::Approx(1.0f).epsilon(1e-6));
    CHECK(at(above, 1) == doctest::Approx(1.0f).epsilon(1e-6));
    CHECK(at(above, 2) == doctest::Approx(1.0f).epsilon(1e-6));

    ImageF below = px(-5.0f, -5.0f, -5.0f);
    applyLut(below, lut, 1.0f);
    CHECK(at(below, 0) == doctest::Approx(0.0f).epsilon(1e-6));
    CHECK(at(below, 1) == doctest::Approx(0.0f).epsilon(1e-6));
    CHECK(at(below, 2) == doctest::Approx(0.0f).epsilon(1e-6));

    // Mid-domain value still interpolates: 0.0 is the cell center of
    // [-1, 1], so identity -> 0.5.
    ImageF mid = px(0.0f, 0.0f, 0.0f);
    applyLut(mid, lut, 1.0f);
    CHECK(at(mid, 0) == doctest::Approx(0.5f).epsilon(1e-6));
}

TEST_CASE("apply_degenerate_tables_are_noops") {
    ImageF img = px(0.3f, 0.4f, 0.5f);
    const float before[3] = {img.rgb[0], img.rgb[1], img.rgb[2]};

    Lut tiny; // n < 2
    tiny.n = 1;
    tiny.rgb = {0.9f, 0.9f, 0.9f};
    applyLut(img, tiny, 1.0f);

    Lut mismatch; // row buffer does not match n^3 * 3
    mismatch.n = 2;
    mismatch.rgb.assign(8 * 3 - 1, 0.9f);
    applyLut(img, mismatch, 1.0f);

    // Non-finite strength is a bypass too.
    Lut dbl = mustParse(kDouble2);
    applyLut(img, dbl, std::nanf(""));

    CHECK(img.rgb[0] == doctest::Approx(before[0]).epsilon(1e-6));
    CHECK(img.rgb[1] == doctest::Approx(before[1]).epsilon(1e-6));
    CHECK(img.rgb[2] == doctest::Approx(before[2]).epsilon(1e-6));
}

TEST_CASE("apply_vendor_cube_diagonal_is_monotonic") {
    const char* paths[] = {
        "third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
        "../third_party/xiaomi-milog/Mi-LogTo709_3DLUT.cube",
    };
    std::string found;
    for (const char* p : paths) {
        std::ifstream probe(p);
        if (probe) {
            found = p;
            break;
        }
    }
    if (found.empty()) {
        WARN_MESSAGE(false, "vendor cube not found — skipping LUT apply check");
        return;
    }
    Result<Lut> r = parseCubeFile(found);
    REQUIRE(r.isOk());
    Lut& lut = *r.value;
    REQUIRE(lut.n == 65);

    // applyLut on the diagonal inputs x = i/64 must reproduce the
    // diagonal lattice entries (exact lattice, no interpolation), and
    // the result must be monotonic (the composed Mi-Log -> 709 transform
    // is monotone on the diagonal).
    float prev = -1.0f;
    for (int i = 0; i < 65; ++i) {
        const float x = static_cast<float>(i) / 64.0f;
        ImageF img = px(x, x, x);
        applyLut(img, lut, 1.0f);
        const size_t idx =
            (static_cast<size_t>(i) + static_cast<size_t>(lut.n) * i +
             static_cast<size_t>(lut.n) * lut.n * i) * 3;
        CHECK(at(img, 0) == doctest::Approx(lut.rgb[idx]).epsilon(1e-5));
        CHECK(at(img, 1) == doctest::Approx(lut.rgb[idx + 1]).epsilon(1e-5));
        CHECK(at(img, 2) == doctest::Approx(lut.rgb[idx + 2]).epsilon(1e-5));
        CHECK(at(img, 0) >= prev);
        prev = at(img, 0);
    }
}

// ---------------------------------------------------------------------------
// developCpu / developEncoded seam: no LUT = bit-identical (the golden
// hashes of test_develop.cpp re-locked here against the new signature),
// LUT + strength = the stage runs after tonemap.
// ---------------------------------------------------------------------------

namespace {

// Same synthetic fixture builder as test_develop.cpp (RGGB, black 64,
// white 1023, identity color matrix, flat linear patch).
struct Synthetic {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
};

Synthetic syntheticFlat(float lr, float lg, float lb,
                        uint32_t w = 8, uint32_t h = 8) {
    Synthetic s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};

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

uint64_t fnv1a(const ImageF& img) {
    uint64_t h = 1469598103934665603ULL;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(img.rgb.data());
    const size_t n = img.rgb.size() * sizeof(float);
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

} // namespace

TEST_CASE("developCpu_golden_hashes_unchanged_without_lut") {
    // The three golden fixtures of test_develop.cpp, re-locked with the
    // new signature: nullptr LUT (explicit and defaulted) must stay
    // bit-identical — the binding no-LUT requirement of COL-07.
    {
        // Fixture A: default grade on a 0.18 flat patch.
        Synthetic s = syntheticFlat(0.18f, 0.18f, 0.18f);
        auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
        CHECK(fnv1a(img) == 0x88593750a9564183ULL);
    }
    {
        // Fixture B: the four original controls active on a colored
        // patch, manual WB (asShot = false runs stages 14-15).
        Synthetic s = syntheticFlat(0.55f, 0.30f, 0.12f);
        Grade g;
        g.exposureEv = 0.7f;
        g.highlights = -35.0f;
        g.tempK = 7200.0f;
        g.tint = 12.0f;
        g.asShot = false;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g, nullptr);
        CHECK(fnv1a(img) == 0x38150540c239af83ULL);
    }
    {
        // Fixture C: exposure + heavy highlights recovery.
        Synthetic s = syntheticFlat(0.95f, 0.95f, 0.95f);
        Grade g;
        g.exposureEv = 2.1f;
        g.highlights = -55.0f;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
        CHECK(fnv1a(img) == 0x1cea742e0c17bf03ULL);
    }
}

TEST_CASE("developCpu_lut_stage_runs_after_tonemap") {
    // The double cube doubles whatever value stage 20 receives, so the
    // expectations are DERIVED from the actual pipeline outputs instead
    // of hardcoded constants. The flat patch is uniform after develop
    // (bilinear demosaic of a flat CFA); the center pixel avoids edge
    // effects.
    Synthetic s = syntheticFlat(0.18f, 0.18f, 0.18f);
    Lut dbl = mustParse(kDouble2);

    const auto centerOf = [](const ImageF& img) {
        const uint32_t x0 = img.w / 2 - 1, y0 = img.h / 2 - 1;
        return img.rgb[(static_cast<size_t>(y0) * img.w + x0) * 3];
    };

    // No LUT: the developed value v (about 0.18 for this fixture).
    Grade neutral;
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, neutral, nullptr);
    const float v = centerOf(plain);

    // Full-strength LUT doubles it exactly — the double table is linear,
    // so trilinear interpolation reproduces 2x for any in-domain x.
    Grade gLut;
    gLut.lutStrength = 1.0f;
    auto withLut = developCpu(s.cfa.data(), s.info, s.meta, gLut, &dbl);
    const float got = centerOf(withLut);
    CHECK(got == doctest::Approx(2.0f * v).epsilon(1e-4f));
    CHECK(got > v); // the stage really ran

    // Tonemap alone: c = ACES(v).
    Grade tm;
    tm.tonemap = 1; // ACES
    auto tmOnly = developCpu(s.cfa.data(), s.info, s.meta, tm, nullptr);
    const float c = centerOf(tmOnly);

    // Tonemap + LUT: the LUT must see the TONEMAPPED value (stage 20
    // after 19), so the result is 2*c. Had the stage run before the
    // tonemap it would be ACES(2v), which differs from 2*ACES(v) — ACES
    // is not linear — so this check pins the order.
    Grade tmLut;
    tmLut.tonemap = 1;
    tmLut.lutStrength = 1.0f;
    auto lutAfterTm = developCpu(s.cfa.data(), s.info, s.meta, tmLut, &dbl);
    const float after = centerOf(lutAfterTm);
    CHECK(after == doctest::Approx(2.0f * c).epsilon(1e-4f));
    CHECK(after > c);
}

TEST_CASE("developCpu_lut_strength_zero_is_bit_identical") {
    Synthetic s = syntheticFlat(0.4f, 0.4f, 0.4f);
    Lut dbl = mustParse(kDouble2);

    Grade neutral;
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, neutral, nullptr);
    Grade zero;
    zero.lutStrength = 0.0f;
    auto bypassed = developCpu(s.cfa.data(), s.info, s.meta, zero, &dbl);
    CHECK(fnv1a(plain) == fnv1a(bypassed));
}

TEST_CASE("developEncoded_lut_stage_and_bit_identity") {
    // Neutral gray NV12 frame (limited Y=128 -> mid), the same fixture
    // style as test_transfer.cpp.
    YuvFrame f;
    f.width = 4;
    f.height = 2;
    f.semiPlanar = true;
    f.y.assign(8, 128);
    f.u.assign(4, 128);
    Grade neutral;

    // No LUT: bit-identical between nullptr and the defaulted param.
    auto plain = developEncoded(f, neutral, true, nullptr);
    auto def = developEncoded(f, neutral, true);
    CHECK(fnv1a(plain) == fnv1a(def));

    // LUT at full strength changes the output; strength 0 does not.
    Lut dbl = mustParse(kDouble2);
    Grade zero;
    auto bypassed = developEncoded(f, zero, true, &dbl);
    CHECK(fnv1a(bypassed) == fnv1a(plain));

    Grade full;
    full.lutStrength = 1.0f;
    auto lut = developEncoded(f, full, true, &dbl);
    CHECK(fnv1a(lut) != fnv1a(plain));
    // The double table is linear, so full strength must give exactly 2x
    // the no-LUT value at every pixel — derived, not a hardcoded constant
    // (Y=128 decodes to about 0.29 via Mi-Log, so 2x is about 0.58).
    const float r = lut.rgb[0];
    CHECK(r == doctest::Approx(2.0f * plain.rgb[0]).epsilon(1e-5f));
    CHECK(r > plain.rgb[0]);
}
