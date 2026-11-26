// test_raw_diagnostics — D0 (docs/DCG_PLAN.md): rawDiagnostics() on SYNTHETIC frames that
// reproduce the `hr_levels` numbers of the three inspected clips:
//   (a) 24 mm DCG-like: black 1023, white 16383, codes multiples of 16, G peak 16368 with 78 sites,
//       NO plateau by the project's sharp-peak rule (the peak holds < 25% of the 0.4% band);
//   (b) 99 mm night-like: black 0, white 16383, G plateau at 16356 (~19k sites), R/B plateaus at
//       10063 / 7747 (capped at green's clip level times the neutral ratio);
//   (c) 24 mm normal RAW: black 64, white 1023, G peak 1023 with 19 sites;
//   (d) degenerate frames: all-saturated, tiny, odd-sized, null, sites above white, dynamic levels.
// The plateau verdict is recoveryClipLevels' own (no second detector): every case cross-checks
// reliable/threshold against a direct recoveryClipLevels call, so scope and develop read one answer.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/RawStats.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <vector>

using namespace rawedit;

namespace {

struct Special { uint16_t code; uint32_t count; };

struct ChanSpec {
    uint16_t lo = 0;          // ramp base code
    uint32_t span = 1;        // ramp width (codes)
    uint32_t step = 1;        // code granularity (16 = DCG grid)
    std::vector<Special> specials;  // placed first, in raster order of the channel's sites
};

// RGGB frame: per channel, the first sum(specials) sites take the special codes, the rest ramp.
std::vector<uint16_t> makeFrame(uint32_t w, uint32_t h, const ChanSpec spec[3]) {
    std::vector<uint16_t> cfa(static_cast<size_t>(w) * h);
    uint32_t idx[3] = {0, 0, 0};
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const int c = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
            const ChanSpec& s = spec[c];
            uint32_t i = idx[c]++;
            uint16_t v = 0;
            bool done = false;
            for (const Special& sp : s.specials) {
                if (i < sp.count) { v = sp.code; done = true; break; }
                i -= sp.count;
            }
            if (!done) v = static_cast<uint16_t>(s.lo + ((i * 37u) % s.span) * s.step);
            cfa[static_cast<size_t>(y) * w + x] = v;
        }
    return cfa;
}

FrameInfo makeInfo(uint32_t w, uint32_t h) {
    FrameInfo info;
    info.width = w; info.height = h; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(w), int(h)};
    return info;
}

RawMeta makeMeta(uint16_t black, uint16_t white) {
    RawMeta m;
    for (int s = 0; s < 4; ++s) m.black[s] = black;
    m.white = white;
    return m;
}

// One answer: the diagnostics' plateau verdict equals recoveryClipLevels' own.
void checkSameAnswer(const RawDiagnostics& d, const std::vector<uint16_t>& cfa,
                     const FrameInfo& info, const RawMeta& meta) {
    const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), info, meta);
    for (int c = 0; c < 3; ++c) {
        CHECK(d.plateauThreshold[c] == lv.threshold[c]);
        CHECK(d.reliablePlateau[c] == (lv.threshold[c] < 1.0f));
        CHECK(d.nearPeak[c] == lv.plateau[c]);
    }
}

void print(const char* name, const RawDiagnostics& d) {
    std::printf("%s: black %d/%d/%d/%d white %d | peak R %u G %u (Gr %u Gb %u) B %u | at %llu/%llu/%llu "
                "near %llu/%llu/%llu above %llu/%llu/%llu | reliable %d/%d/%d thr %.4f/%.4f/%.4f\n",
                name, d.blackDeclared[0], d.blackDeclared[1], d.blackDeclared[2], d.blackDeclared[3],
                d.whiteDeclared, d.peakRaw[0], d.peakRaw[1], d.peakRawPos[1], d.peakRawPos[2], d.peakRaw[2],
                (unsigned long long)d.atPeak[0], (unsigned long long)d.atPeak[1], (unsigned long long)d.atPeak[2],
                (unsigned long long)d.nearPeak[0], (unsigned long long)d.nearPeak[1], (unsigned long long)d.nearPeak[2],
                (unsigned long long)d.aboveWhite[0], (unsigned long long)d.aboveWhite[1], (unsigned long long)d.aboveWhite[2],
                d.reliablePlateau[0], d.reliablePlateau[1], d.reliablePlateau[2],
                d.plateauThreshold[0], d.plateauThreshold[1], d.plateauThreshold[2]);
}

constexpr uint32_t kW = 256, kH = 192;

}  // namespace

TEST_CASE("(a) 24 mm DCG-like: black 1023 white 16383, peak 16368 x78, no plateau by the sharp-peak rule") {
    ChanSpec s[3];
    s[0] = {1040, 400, 16, {}};
    s[1] = {1040, 900, 16, {{16368, 78}, {16352, 150}, {16336, 150}, {16320, 150}}};
    s[2] = {1040, 400, 16, {}};
    const auto cfa = makeFrame(kW, kH, s);
    const FrameInfo info = makeInfo(kW, kH);
    const RawMeta meta = makeMeta(1023, 16383);
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, meta);
    print("dcg24", d);
    CHECK(d.valid);
    for (int p = 0; p < 4; ++p) CHECK(d.blackDeclared[p] == 1023);
    CHECK(d.whiteDeclared == 16383);
    CHECK(d.peakRaw[1] == 16368);
    CHECK(d.atPeak[1] == 78);
    // The 78 peak sites fill the first Gr row; the Gb row tops out one code grid step lower.
    CHECK(d.peakRawPos[1] == 16368);
    CHECK(d.peakRawPos[2] == 16352);
    CHECK(d.peakRaw[0] == 1040 + 399 * 16);
    CHECK(d.peakRaw[2] == 1040 + 399 * 16);
    // Plateau support: 78 + 3 x 150 (codes 16320..16352 are inside 0.4% of the 15360 range = 61 codes).
    CHECK(d.nearPeak[1] == 528);
    for (int c = 0; c < 3; ++c) {
        CHECK(d.aboveWhite[c] == 0);
        CHECK_FALSE(d.reliablePlateau[c]);
        CHECK(d.plateauThreshold[c] == 1.0f);
    }
    checkSameAnswer(d, cfa, info, meta);
}

TEST_CASE("(b) 99 mm night-like: black 0, G plateau 16356 (~19k), R/B plateaus 10063 / 7747") {
    ChanSpec s[3];
    s[0] = {0, 9800, 1, {{10063, 6000}}};
    s[1] = {0, 16200, 1, {{16356, 19000}}};
    s[2] = {0, 7600, 1, {{7747, 5000}}};
    const auto cfa = makeFrame(kW, kH, s);
    const FrameInfo info = makeInfo(kW, kH);
    const RawMeta meta = makeMeta(0, 16383);
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, meta);
    print("night99", d);
    CHECK(d.peakRaw[0] == 10063);
    CHECK(d.peakRaw[1] == 16356);
    CHECK(d.peakRaw[2] == 7747);
    CHECK(d.atPeak[0] == 6000);
    CHECK(d.atPeak[1] == 19000);
    CHECK(d.atPeak[2] == 5000);
    CHECK(d.nearPeak[1] == 19000);
    for (int c = 0; c < 3; ++c) {
        CHECK(d.aboveWhite[c] == 0);
        CHECK(d.reliablePlateau[c]);
    }
    const float gNorm = 16356.0f / 16383.0f;
    CHECK(d.plateauThreshold[1] == doctest::Approx(gNorm - 0.004f).epsilon(1e-5));
    CHECK(d.plateauThreshold[0] == doctest::Approx(10063.0f / 16383.0f - 0.004f).epsilon(1e-5));
    CHECK(d.plateauThreshold[2] == doctest::Approx(7747.0f / 16383.0f - 0.004f).epsilon(1e-5));
    checkSameAnswer(d, cfa, info, meta);
}

TEST_CASE("(c) 24 mm normal RAW: black 64 white 1023, G peak 1023 x19") {
    ChanSpec s[3];
    s[0] = {64, 400, 1, {{700, 1}}};
    s[1] = {64, 800, 1, {{1023, 19}}};
    s[2] = {64, 400, 1, {{700, 1}}};
    const auto cfa = makeFrame(kW, kH, s);
    const FrameInfo info = makeInfo(kW, kH);
    const RawMeta meta = makeMeta(64, 1023);
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, meta);
    print("raw24", d);
    for (int p = 0; p < 4; ++p) CHECK(d.blackDeclared[p] == 64);
    CHECK(d.whiteDeclared == 1023);
    CHECK(d.peakRaw[1] == 1023);
    CHECK(d.atPeak[1] == 19);
    CHECK(d.nearPeak[1] == 19);
    for (int c = 0; c < 3; ++c) CHECK(d.aboveWhite[c] == 0);
    // 19 sites at the very white level: a sharp saturation peak, reported by the same detector.
    CHECK(d.reliablePlateau[1]);
    CHECK(d.plateauThreshold[1] == doctest::Approx(1.0f - 0.004f).epsilon(1e-5));
    CHECK_FALSE(d.reliablePlateau[0]);
    CHECK_FALSE(d.reliablePlateau[2]);
    checkSameAnswer(d, cfa, info, meta);
}

TEST_CASE("(d) all-saturated frame: every site at white") {
    ChanSpec s[3];
    for (auto& c : s) c = {16383, 1, 1, {}};
    const auto cfa = makeFrame(kW, kH, s);
    const FrameInfo info = makeInfo(kW, kH);
    const RawMeta meta = makeMeta(1023, 16383);
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, meta);
    print("allsat", d);
    const uint64_t n = uint64_t(kW) * kH;
    CHECK(d.peakRaw[0] == 16383);
    CHECK(d.atPeak[0] == n / 4);
    CHECK(d.atPeak[1] == n / 2);
    CHECK(d.atPeak[2] == n / 4);
    CHECK(d.nearPeak[1] == n / 2);
    for (int c = 0; c < 3; ++c) CHECK(d.aboveWhite[c] == 0);
    checkSameAnswer(d, cfa, info, meta);
}

TEST_CASE("sites above the declared white are counted per channel") {
    ChanSpec s[3];
    s[0] = {1040, 400, 16, {{16383 + 10, 3}}};
    s[1] = {1040, 900, 16, {{16383, 5}, {16383 + 1, 7}}};
    s[2] = {1040, 400, 16, {}};
    const auto cfa = makeFrame(kW, kH, s);
    const FrameInfo info = makeInfo(kW, kH);
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, makeMeta(1023, 16383));
    CHECK(d.aboveWhite[0] == 3);
    CHECK(d.aboveWhite[1] == 7);   // exactly at white is NOT above
    CHECK(d.aboveWhite[2] == 0);
    CHECK(d.peakRaw[0] == 16393);
}

TEST_CASE("frame-dynamic levels win over the container levels, like Develop stage 1") {
    ChanSpec s[3];
    for (auto& c : s) c = {2000, 100, 1, {}};
    const auto cfa = makeFrame(kW, kH, s);
    FrameInfo info = makeInfo(kW, kH);
    info.dynamicBlack[0] = 100; info.dynamicBlack[1] = 101; info.dynamicBlack[2] = 102; info.dynamicBlack[3] = 103;
    info.dynamicWhite = 4000;
    const RawDiagnostics d = rawDiagnostics(cfa.data(), info, makeMeta(0, 16383));
    CHECK(d.whiteDeclared == 4000);
    CHECK(d.blackDeclared[0] == 100);  // R
    CHECK(d.blackDeclared[1] == 101);  // Gr (RGGB slot 1)
    CHECK(d.blackDeclared[2] == 102);  // Gb (slot 2)
    CHECK(d.blackDeclared[3] == 103);  // B
}

TEST_CASE("tiny, odd and empty frames never crash and say so") {
    const RawMeta meta = makeMeta(64, 1023);
    {   // 1x1
        const std::vector<uint16_t> cfa = {900};
        const RawDiagnostics d = rawDiagnostics(cfa.data(), makeInfo(1, 1), meta);
        CHECK(d.valid);
        CHECK(d.peakRaw[0] == 900);
        CHECK(d.atPeak[0] == 1);
        CHECK_FALSE(d.reliablePlateau[0]);
    }
    {   // 2x2, all different
        const std::vector<uint16_t> cfa = {100, 200, 300, 400};  // R Gr / Gb B
        const RawDiagnostics d = rawDiagnostics(cfa.data(), makeInfo(2, 2), meta);
        CHECK(d.peakRawPos[0] == 100);
        CHECK(d.peakRawPos[1] == 200);
        CHECK(d.peakRawPos[2] == 300);
        CHECK(d.peakRawPos[3] == 400);
        CHECK(d.peakRaw[1] == 300);
        for (int c = 0; c < 3; ++c) CHECK_FALSE(d.reliablePlateau[c]);
    }
    {   // 3x3 odd
        const std::vector<uint16_t> cfa(9, 500);
        const RawDiagnostics d = rawDiagnostics(cfa.data(), makeInfo(3, 3), meta);
        CHECK(d.valid);
        CHECK(d.atPeak[0] + d.atPeak[1] + d.atPeak[2] == 9);
    }
    {   // null / zero-sized
        CHECK_FALSE(rawDiagnostics(nullptr, makeInfo(4, 4), meta).valid);
        const std::vector<uint16_t> cfa(4, 1);
        CHECK_FALSE(rawDiagnostics(cfa.data(), makeInfo(0, 0), meta).valid);
    }
}
