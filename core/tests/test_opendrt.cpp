// F1-4: OpenDRT v1.1.0 C++ reference tests (docs/COLOR_MODEL_V2_PLAN.md
// §4.4). Runs from the repo root (build-desktop.sh does `cd $ROOT`): the
// reference vectors live in core/tests/data/.
//
// ORIGIN OF THE REFERENCE VECTORS (be exact about this):
//   core/tests/data/opendrt_klipp_sdr.txt and opendrt_klipp_hlg.txt are
//   VERBATIM subsets of Klipp's test resources opendrt_ref.txt /
//   opendrt_hlg_ref.txt (S-Log3 / S-Gamut3.Cine and DaVinci Intermediate
//   input rows only; 768 SDR + 384 HLG rows). Klipp's OpenDrtTest.kt says
//   they are "output from OpenDRT.dctl v1.1.0 compiled as C++ (unchanged
//   code, same presets)". That is Klipp's claim; this repository does not
//   have the DCTL, did not regenerate the vectors and cannot verify the
//   claim. What the tests prove is therefore: "this C++ port reproduces
//   the numbers Klipp's port is held to" — an equivalence with Klipp's
//   reference data, not an independent check against Jed Smith's DCTL.
//   The inputs of the vectors are ENCODED code values; the tests convert
//   them to the transform's entry point with the input curve/gamut below
//   (S-Log3: Klipp's own oetf-8 formula + Klipp's S-Gamut3.Cine matrix,
//   kept test-local; DI: Color.cpp's decodeTransfer + the library's DWG
//   matrix, i.e. the same path the NODES stage hands over).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include <rawedit/Color.h>
#include <rawedit/ColorModel.h>
#include <rawedit/Develop.h>
#include <rawedit/OpenDrt.h>

using namespace rawedit;

namespace {

// Klipp OpenDrt.kt: oetf id 8 (Sony S-Log3, 0..1 code -> scene linear) and
// M.SGAMUT3CINE_TO_XYZ.
float slog3ToLinear(float x) {
    if (x < 171.2102946929f / 1023.0f)
        return (x * 1023.0f - 95.0f) * 0.01125f / (171.2102946929f - 95.0f);
    return std::pow(10.0f, (x * 1023.0f - 420.0f) / 261.5f) * (0.18f + 0.01f) - 0.01f;
}
const float kSGamut3CineToXyz[9] = {
    0.599083920758327171f, 0.248925516115423628f, 0.102446490177920776f,
    0.215075820115587457f, 0.88506850174372842f, -0.100144321859315821f,
    -0.0320658495445057951f, -0.0276583906794915374f, 1.1487819909838759f};

struct Row {
    int oetf, gamut, look, tonescale, cwp;
    float grey;
    int display;
    float in[3];
    float out[3];
    std::string text;
};

std::vector<Row> loadRows(const std::string& path) {
    std::ifstream f(path);
    std::vector<Row> rows;
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#')
            continue;
        std::istringstream ss(line);
        Row r{};
        ss >> r.oetf >> r.gamut >> r.look >> r.tonescale >> r.cwp >> r.grey >> r.display >>
            r.in[0] >> r.in[1] >> r.in[2] >> r.out[0] >> r.out[1] >> r.out[2];
        r.text = line;
        if (ss)
            rows.push_back(r);
    }
    return rows;
}

// Enters the transform at the point the row's input encoding reduces to.
void runRow(const Row& r, const OpenDrt& t, float px[3]) {
    px[0] = r.in[0]; px[1] = r.in[1]; px[2] = r.in[2];
    if (r.oetf == 1) { // DaVinci Intermediate code -> DWG linear
        for (int i = 0; i < 3; ++i)
            px[i] = decodeTransfer(px[i], TransferId::DaVinciIntermediate);
        t.applyDwgLinear(px);
    } else { // oetf 8: S-Log3 / S-Gamut3.Cine -> XYZ
        for (int i = 0; i < 3; ++i)
            px[i] = slog3ToLinear(px[i]);
        const float x = px[0], y = px[1], z = px[2];
        px[0] = kSGamut3CineToXyz[0] * x + kSGamut3CineToXyz[1] * y + kSGamut3CineToXyz[2] * z;
        px[1] = kSGamut3CineToXyz[3] * x + kSGamut3CineToXyz[4] * y + kSGamut3CineToXyz[5] * z;
        px[2] = kSGamut3CineToXyz[6] * x + kSGamut3CineToXyz[7] * y + kSGamut3CineToXyz[8] * z;
        t.applyXyz(px);
    }
}

OpenDrtSpec specOf(const Row& r, bool hdr) {
    OpenDrtSpec s;
    s.look = r.look;
    s.tonescale = r.tonescale;
    s.cwp = r.cwp;
    s.cwpLimit = r.cwp != 0 ? 0.4f : 0.25f; // as Klipp's OpenDrtTest builds it
    s.grey = r.grey;
    // vector column 7: 1 = sRGB 2.2 display (Klipp display 0), 0 = Rec.1886
    s.display = hdr ? OpenDrtDisplay::Hlg1000
                    : (r.display == 1 ? OpenDrtDisplay::SdrSrgb22 : OpenDrtDisplay::SdrRec1886);
    return s;
}

// Largest deviation over a vector file; also returns the worst row.
double worstDeviation(const std::vector<Row>& rows, bool hdr, std::string* worstRow,
                      size_t* nanCount) {
    double worst = 0.0;
    *nanCount = 0;
    for (const Row& r : rows) {
        const OpenDrt t(specOf(r, hdr));
        float px[3];
        runRow(r, t, px);
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(px[c])) {
                ++*nanCount;
                continue;
            }
            const double d = std::abs(static_cast<double>(px[c]) - r.out[c]);
            if (d > worst) {
                worst = d;
                if (worstRow)
                    *worstRow = r.text;
            }
        }
    }
    return worst;
}

} // namespace

TEST_CASE("OpenDRT SDR: matches Klipp's reference vectors (S-Log3 + DI inputs)") {
    const auto rows = loadRows("core/tests/data/opendrt_klipp_sdr.txt");
    REQUIRE(rows.size() == 768);
    std::string worstRow;
    size_t nans = 0;
    const double worst = worstDeviation(rows, false, &worstRow, &nans);
    MESSAGE("SDR: " << rows.size() << " rows, worst |out - ref| = " << worst
                    << ", non-finite = " << nans << ", at: " << worstRow);
    CHECK(nans == 0);
    // Klipp's own bound on this data is 2e-3 (the reference is printed to
    // 6 decimals; the encoded inputs to 4). Measured here: 6.6e-7, i.e.
    // print-rounding level (0.5e-6) plus float noise. Pinned at 2e-6.
    CHECK(worst < 2e-6);
}

TEST_CASE("OpenDRT HLG-1000: matches Klipp's reference vectors") {
    const auto rows = loadRows("core/tests/data/opendrt_klipp_hlg.txt");
    REQUIRE(rows.size() == 384);
    std::string worstRow;
    size_t nans = 0;
    const double worst = worstDeviation(rows, true, &worstRow, &nans);
    MESSAGE("HLG: " << rows.size() << " rows, worst |out - ref| = " << worst
                    << ", non-finite = " << nans << ", at: " << worstRow);
    CHECK(nans == 0);
    // Measured 7.7e-7 (same print-rounding floor). Pinned at 2e-6.
    CHECK(worst < 2e-6);
}

TEST_CASE("OpenDRT: vectors cover every look and tonescale preset in the data") {
    // The S-Log3 rows are the 720-row sweep: a coverage claim for the
    // report (which presets the equivalence actually exercised).
    const auto rows = loadRows("core/tests/data/opendrt_klipp_sdr.txt");
    bool look[7] = {}, ts[14] = {};
    bool cwp[7] = {};
    for (const Row& r : rows) {
        look[r.look] = true;
        ts[r.tonescale] = true;
        cwp[r.cwp] = true;
    }
    std::string lk, tsl, cw;
    for (int i = 0; i < 7; ++i) lk += look[i] ? char('0' + i) : '-';
    for (int i = 0; i < 14; ++i) tsl += ts[i] ? "x" : "-";
    for (int i = 0; i < 7; ++i) cw += cwp[i] ? char('0' + i) : '-';
    MESSAGE("looks " << lk << "  tonescales " << tsl << "  cwp " << cw);
    // looks 0..6 at least once
    for (int i = 0; i < 7; ++i)
        CHECK(look[i]);
}

TEST_CASE("OpenDRT: DWG-linear entry equals the XYZ entry through the DWG matrix") {
    // applyDwgLinear = OpenDRT's DWG->XYZ matrix + applyXyz; and that
    // matrix is the one Color.cpp pins (test_color_model checks equality
    // to 0). Cross-check the two paths on a few colors.
    OpenDrtSpec spec;
    const OpenDrt t(spec);
    float xyzM[9];
    gamutToXyz(GamutId::DaVinciWideGamut, xyzM);
    const float colors[][3] = {{0.18f, 0.18f, 0.18f}, {0.5f, 0.2f, 0.1f}, {2.0f, 1.0f, 0.3f}, {0.01f, 0.4f, 0.05f}};
    for (const auto& c : colors) {
        float a[3] = {c[0], c[1], c[2]};
        t.applyDwgLinear(a);
        float x[3] = {xyzM[0] * c[0] + xyzM[1] * c[1] + xyzM[2] * c[2],
                      xyzM[3] * c[0] + xyzM[4] * c[1] + xyzM[5] * c[2],
                      xyzM[6] * c[0] + xyzM[7] * c[1] + xyzM[8] * c[2]};
        t.applyXyz(x);
        for (int i = 0; i < 3; ++i)
            CHECK(a[i] == doctest::Approx(x[i]).epsilon(1e-6f));
    }
}

TEST_CASE("OpenDRT: structural properties over all looks, tonescales and displays") {
    // Not reference data: invariants any correct implementation keeps.
    const OpenDrtDisplay displays[] = {OpenDrtDisplay::SdrSrgb22, OpenDrtDisplay::SdrRec1886,
                                       OpenDrtDisplay::Hlg1000};
    for (int look = 0; look < 7; ++look)
        for (int tonescale = 0; tonescale < 14; ++tonescale)
            for (int cwp : {0, 3, 6})
                for (OpenDrtDisplay disp : displays) {
                    OpenDrtSpec s;
                    s.look = look;
                    s.tonescale = tonescale;
                    s.cwp = cwp;
                    s.cwpLimit = 0.4f;
                    s.display = disp;
                    const OpenDrt t(s);
                    CAPTURE(look); CAPTURE(tonescale); CAPTURE(cwp);
                    // black -> ~black (display floor), grey ramp monotone,
                    // everything finite and inside 0..1.
                    float prevY = -1.0f;
                    for (int i = 0; i <= 24; ++i) {
                        const float lin = (i == 0) ? 0.0f : 0.0005f * std::pow(1.45f, static_cast<float>(i));
                        float px[3] = {lin, lin, lin};
                        t.applyDwgLinear(px);
                        for (int c = 0; c < 3; ++c) {
                            REQUIRE(std::isfinite(px[c]));
                            REQUIRE(px[c] >= 0.0f);
                            REQUIRE(px[c] <= 1.0f);
                        }
                        // neutral stays near-neutral with an explicit D65 white
                        // (the looks' own creative white tints on purpose, e.g.
                        // Aery is D93)
                        if (cwp == 3)
                            REQUIRE(std::abs(px[0] - px[2]) < 0.06f);
                        REQUIRE(px[1] >= prevY - 1e-5f);
                        prevY = px[1];
                    }
                    // gamut stress: saturated, negative and huge values
                    const float stress[][3] = {{1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}, {0.0f, 0.0f, 1.0f},
                                               {-0.05f, 0.2f, 0.3f}, {100.0f, 100.0f, 100.0f},
                                               {50.0f, 0.001f, 0.001f}, {0.001f, 0.001f, 30.0f}};
                    for (const auto& c : stress) {
                        float px[3] = {c[0], c[1], c[2]};
                        t.applyDwgLinear(px);
                        // The HLG signal is not clamped after the inverse
                        // EOTF (faithful to the reference): a saturated
                        // colour with a tiny luminance can land marginally
                        // above 1.0 (measured 1.0062). The 10-bit quantiser
                        // (E2) must clamp; SDR is clamped inside the DRT.
                        const float hi = disp == OpenDrtDisplay::Hlg1000 ? 1.05f : 1.0f;
                        for (int k = 0; k < 3; ++k) {
                            REQUIRE(std::isfinite(px[k]));
                            REQUIRE(px[k] >= 0.0f);
                            REQUIRE(px[k] <= hi);
                        }
                    }
                }
}

TEST_CASE("OpenDRT: grey / display / cwp parameters move the picture the right way") {
    OpenDrtSpec base;
    auto grey18 = [](const OpenDrtSpec& s) {
        const OpenDrt t(s);
        float px[3] = {0.18f, 0.18f, 0.18f};
        t.applyDwgLinear(px);
        return px[1];
    };
    OpenDrtSpec bright = base;
    bright.grey = 18.0f;
    CHECK(grey18(bright) > grey18(base)); // higher mid grey = brighter image
    OpenDrtSpec tv = base;
    tv.display = OpenDrtDisplay::SdrRec1886;
    CHECK(grey18(tv) != grey18(base));
    OpenDrtSpec hlg = base;
    hlg.display = OpenDrtDisplay::Hlg1000;
    CHECK(grey18(hlg) != grey18(base));

    // creative white: D93 pushes a neutral toward blue, D50 toward red
    OpenDrtSpec d93 = base, d50 = base;
    d93.cwp = 1; d93.cwpLimit = 1.0f;
    d50.cwp = 6; d50.cwpLimit = 1.0f;
    float a[3] = {0.5f, 0.5f, 0.5f}, b[3] = {0.5f, 0.5f, 0.5f};
    OpenDrt(d93).applyDwgLinear(a);
    OpenDrt(d50).applyDwgLinear(b);
    MESSAGE("D93 neutral: " << a[0] << " " << a[1] << " " << a[2]
                            << "  D50 neutral: " << b[0] << " " << b[1] << " " << b[2]);
    CHECK(a[2] > a[0]);
    CHECK(b[0] > b[2]);
}

TEST_CASE("OpenDRT: whole-image entry (nodes DWG output -> OpenDRT) end to end") {
    // 709 linear -> NODES (empty chain, DWG-linear out) -> OpenDRT: the
    // F1-5 data path on the CPU. Equals the per-pixel route.
    ImageF img;
    img.w = 2;
    img.h = 1;
    img.rgb = {0.18f, 0.18f, 0.18f, 0.5f, 0.1f, 0.05f};
    ImageF ref = img;
    REQUIRE(applyNodes(img, nullptr, 0, NodesOutput::DwgLinear));
    OpenDrtSpec spec;
    applyOpenDrt(img, spec);
    const OpenDrt t(spec);
    for (int p = 0; p < 2; ++p) {
        float dwg[3];
        linear709ToDwg(&ref.rgb[static_cast<size_t>(p) * 3], dwg);
        t.applyDwgLinear(dwg);
        for (int c = 0; c < 3; ++c)
            CHECK(img.rgb[static_cast<size_t>(p) * 3 + c] == dwg[c]);
    }
    // NaN input becomes 0 (Klipp's bake rule)
    ImageF nan;
    nan.w = 1;
    nan.h = 1;
    nan.rgb = {std::nanf(""), 0.2f, 0.2f};
    applyOpenDrt(nan, spec);
    for (float v : nan.rgb)
        CHECK(std::isfinite(v));
}
