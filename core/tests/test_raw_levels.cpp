// test_raw_levels — night C4 (D1, docs/DCG_PLAN.md, report P1): the per-clip
// reversible "RAW Levels" override's pure logic contracts.
//
//   - resolveRawLevels: override > frame dynamic > container metadata, the
//     SAME answer developCpu's stage-1 chain always gave when the override is
//     empty (the resolver-equivalence proof: resolved against the pre-D1
//     expression's exact float ops, per slot, with and without dynamic
//     levels, with partial overrides on one slot at a time);
//   - developCpu equality: an empty override is BIT-IDENTICAL to the old
//     call (memcmp over the whole output — the golden-preservation rule);
//   - developCpu override arithmetic: black pedestal 1023 / white 16383 style
//     DCG frames, an override shifts the levels and the normalized output
//     moves by exactly the stage-1 math (synthetic flat patches pin the
//     numbers, not eyeballing);
//   - measureRawLevelsCandidate: the "Ölç" candidate generator — candidate
//     where green saturates with a sharp peak (the recoveryClipLevels gates),
//     NO_CANDIDATE (never fabricated numbers) where the plateau is
//     unreliable, and the candidate white maps back to the exact planted
//     code through the same stage-1 normalization.
//
// Pure logic, desktop only, synthetic fixtures — no cwd needs.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

using namespace rawedit;

namespace {

constexpr uint32_t kW = 64, kH = 48;
constexpr uint16_t kBlack = 1023, kWhite = 16383;  // the DCG-style 16-bit container levels
constexpr float kRange = float(kWhite) - float(kBlack);

int siteColor(uint32_t x, uint32_t y) {  // RGGB
    return ((y & 1) == 0) ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
}

FrameInfo makeInfo() {
    FrameInfo info;
    info.width = kW; info.height = kH; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(kW), int(kH)};
    return info;
}

RawMeta makeMeta() {
    RawMeta meta;
    for (int s = 0; s < 4; ++s) meta.black[s] = kBlack;
    meta.white = kWhite;
    meta.wbGains[0] = meta.wbGains[1] = meta.wbGains[2] = 1.0f;
    return meta;
}

// A flat per-channel patch: site of color c reads codeFor[c].
std::vector<uint16_t> flatCfa(const uint16_t codeFor[3]) {
    std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x)
            cfa[size_t(y) * kW + x] = codeFor[siteColor(x, y)];
    return cfa;
}

// developCpu stage-1 normalization, the pre-D1 expression (pinned from the
// 23cce18 source: useFrameBlack keyed on dynamicBlack[0] >= 0, dynamicWhite
// > 0, per-slot black, range guarded to 1.0) — the resolver-equivalence
// oracle. Same float ops in the same order.
struct Resolved { float white; float black[4]; };
Resolved preD1Resolution(const FrameInfo& info, const RawMeta& meta) {
    Resolved r;
    const bool useFrameBlack = info.dynamicBlack[0] >= 0;
    r.white = info.dynamicWhite > 0
        ? static_cast<float>(info.dynamicWhite)
        : static_cast<float>(meta.white);
    for (int slot = 0; slot < 4; ++slot)
        r.black[slot] = useFrameBlack
            ? static_cast<float>(info.dynamicBlack[slot])
            : static_cast<float>(meta.black[slot]);
    return r;
}

float normOf(float blackSlot, float whiteV, uint16_t code) {
    const float range = whiteV > blackSlot ? whiteV - blackSlot : 1.0f;
    const float v = (float(code) - blackSlot) / range;
    return std::min(std::max(v, 0.0f), 1.0f);
}

}  // namespace

// ---- the resolver: override > frame dynamic > container metadata ---------

TEST_CASE("resolve_matches_the_pre_d1_expression_when_empty") {
    // The resolver-equivalence proof: with an EMPTY override the resolved
    // levels are IDENTICAL (float ==) to the pre-D1 stage-1 expression for
    // every combination of dynamic-level presence.
    const RawMeta meta = makeMeta();
    const RawLevelsOverride empty{};
    CHECK(empty.empty());
    FrameInfo info = makeInfo();
    // (a) no dynamic levels at all (sentinels): container metadata.
    {
        float w; float b[4];
        resolveRawLevels(empty, info, meta, w, b);
        const Resolved ref = preD1Resolution(info, meta);
        CHECK(w == ref.white);
        for (int s = 0; s < 4; ++s) CHECK(b[s] == ref.black[s]);
        CHECK(w == float(kWhite));
        CHECK(b[3] == float(kBlack));
    }
    // (b) dynamic black only (white stays the sentinel): frame blacks,
    //     container white — the all-or-nothing useFrameBlack rule.
    {
        FrameInfo fi = info;
        for (int s = 0; s < 4; ++s) fi.dynamicBlack[s] = 1100 + s;
        float w; float b[4];
        resolveRawLevels(empty, fi, meta, w, b);
        const Resolved ref = preD1Resolution(fi, meta);
        CHECK(w == ref.white);
        for (int s = 0; s < 4; ++s) CHECK(b[s] == ref.black[s]);
        CHECK(w == float(kWhite));
        CHECK(b[2] == 1102.0f);
    }
    // (c) dynamic white only: frame white, container blacks.
    {
        FrameInfo fi = info;
        fi.dynamicWhite = 15000;
        float w; float b[4];
        resolveRawLevels(empty, fi, meta, w, b);
        const Resolved ref = preD1Resolution(fi, meta);
        CHECK(w == ref.white);
        for (int s = 0; s < 4; ++s) CHECK(b[s] == ref.black[s]);
        CHECK(w == 15000.0f);
        CHECK(b[0] == float(kBlack));
    }
    // (d) both dynamic: the frame wins over the container everywhere.
    {
        FrameInfo fi = info;
        for (int s = 0; s < 4; ++s) fi.dynamicBlack[s] = 900 + s;
        fi.dynamicWhite = 16000;
        float w; float b[4];
        resolveRawLevels(empty, fi, meta, w, b);
        const Resolved ref = preD1Resolution(fi, meta);
        CHECK(w == ref.white);
        for (int s = 0; s < 4; ++s) CHECK(b[s] == ref.black[s]);
        CHECK(w == 16000.0f);
        CHECK(b[1] == 901.0f);
    }
}

TEST_CASE("resolve_override_wins_per_slot_and_frame_beats_metadata") {
    const RawMeta meta = makeMeta();
    FrameInfo info = makeInfo();
    for (int s = 0; s < 4; ++s) info.dynamicBlack[s] = 1100 + s;
    info.dynamicWhite = 15000;

    // One slot overridden: that slot takes the override, every other slot
    // keeps the frame's dynamic value (the per-slot graft, all-or-nothing
    // frame rule preserved below the override).
    {
        RawLevelsOverride lv;
        lv.black[2] = 2048.0f;
        CHECK(!lv.empty());
        float w; float b[4];
        resolveRawLevels(lv, info, meta, w, b);
        CHECK(b[2] == 2048.0f);          // the override
        CHECK(b[0] == 1100.0f);          // frame dynamic
        CHECK(b[1] == 1101.0f);
        CHECK(b[3] == 1103.0f);
        CHECK(w == 15000.0f);            // frame white, no override
    }
    // White overridden: wins over the frame's dynamic white; blacks keep
    // the frame's.
    {
        RawLevelsOverride lv;
        lv.white = 15800.0f;
        float w; float b[4];
        resolveRawLevels(lv, info, meta, w, b);
        CHECK(w == 15800.0f);
        CHECK(b[0] == 1100.0f);
        CHECK(b[3] == 1103.0f);
    }
    // Everything overridden: the frame and the container are both ignored.
    {
        RawLevelsOverride lv;
        lv.white = 16300.0f;
        for (int s = 0; s < 4; ++s) lv.black[s] = 100.0f + s;
        float w; float b[4];
        resolveRawLevels(lv, info, meta, w, b);
        CHECK(w == 16300.0f);
        for (int s = 0; s < 4; ++s) CHECK(b[s] == 100.0f + s);
    }
    // No frame levels at all: the override beats the container metadata.
    {
        FrameInfo bare = makeInfo();
        RawLevelsOverride lv;
        lv.white = 14000.0f;
        lv.black[0] = 64.0f;
        float w; float b[4];
        resolveRawLevels(lv, bare, meta, w, b);
        CHECK(w == 14000.0f);
        CHECK(b[0] == 64.0f);
        CHECK(b[1] == float(kBlack));    // container fallback
    }
}

// ---- developCpu equality: unset = bit-identical ---------------------------

TEST_CASE("developcpu_with_an_empty_override_is_bit_identical") {
    // THE golden-preservation contract: an empty override (all sentinels,
    // the default) reproduces the pre-D1 output BIT FOR BIT. Both calls run
    // the same developCpu; the only difference is the appended parameter's
    // default. A flat mid patch and a ramped patch both prove it (a flat
    // patch alone could hide a range bug that a spread of codes exposes).
    const RawMeta meta = makeMeta();
    const FrameInfo info = makeInfo();
    Grade grade;  // neutral: every stage skipped, stage 1 is the whole output

    // (a) flat mid-grey patch.
    {
        const uint16_t codes[3] = {8192, 8192, 8192};
        const auto cfa = flatCfa(codes);
        const ImageF a = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr);
        const ImageF b = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, RawLevelsOverride());
        REQUIRE(a.rgb.size() == b.rgb.size());
        REQUIRE(a.rgb.size() > 0);
        CHECK(std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * sizeof(float)) == 0);
    }
    // (b) a per-channel ramp (every code from black to white spread over
    //     the frame — the clamped ends and the mid range all exercised).
    {
        std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
        uint32_t i = 0;
        for (uint32_t y = 0; y < kH; ++y)
            for (uint32_t x = 0; x < kW; ++x)
                cfa[size_t(y) * kW + x] = uint16_t(1023 + ((i++ * 37) % 15361));
        const ImageF a = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr);
        const ImageF b = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, RawLevelsOverride());
        REQUIRE(a.rgb.size() == b.rgb.size());
        CHECK(std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * sizeof(float)) == 0);
    }
    // (c) frame dynamic levels present (the useFrameBlack branch): the
    //     empty override must keep the frame-preferred resolution identical
    //     too, not only the container fallback.
    {
        FrameInfo fi = info;
        for (int s = 0; s < 4; ++s) fi.dynamicBlack[s] = 1100 + s;
        fi.dynamicWhite = 15000;
        const uint16_t codes[3] = {12000, 12000, 12000};
        const auto cfa = flatCfa(codes);
        const ImageF a = developCpu(cfa.data(), fi, meta, grade, nullptr, nullptr);
        const ImageF b = developCpu(cfa.data(), fi, meta, grade, nullptr, nullptr, RawLevelsOverride());
        REQUIRE(a.rgb.size() == b.rgb.size());
        CHECK(std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * sizeof(float)) == 0);
    }
}

// ---- developCpu override arithmetic: SET moves the output exactly -----------

TEST_CASE("developcpu_override_shifts_levels_by_the_stage1_math") {
    // The SET case pins the arithmetic on synthetic DCG-style frames
    // (black pedestal 1023, white 16383). A flat patch at a known raw code,
    // an override that moves white down: every site must normalize against
    // the OVERRIDDEN levels — (code - black) / (white - black) with the new
    // white, clamped — and the demosaiced output must show it (the flat
    // patch makes the bilinear demosaic exact: a site's own channel is its
    // own value).
    const RawMeta meta = makeMeta();
    const FrameInfo info = makeInfo();
    Grade grade;

    // The planted code sits at a known fraction of the declared range.
    const uint16_t planted = 12287;  // (12287 - 1023) / 15360 = 0.73125 of the declared range
    const uint16_t codes[3] = {planted, planted, planted};
    const auto cfa = flatCfa(codes);

    // (a) override white to 14335 ((14335 - 1023) = 13312 range): the same
    //     raw code now normalizes HIGHER — exactly (12287-1023)/13312.
    {
        RawLevelsOverride lv;
        lv.white = 14335.0f;
        const ImageF img = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, lv);
        const float expect = normOf(float(kBlack), 14335.0f, planted);
        CHECK(std::abs(expect - 0.8461544f) < 2e-6f);  // the number itself, not a tautology
        // Identity profile (meta matrices identity) + neutral grade: the
        // demosaiced center pixel's channels are the site values.
        const float got = img.rgb[(size_t(kH / 2) * kW + kW / 2) * 3];
        CHECK(got == doctest::Approx(expect).epsilon(1e-5));
    }
    // (b) override black UP to 2048: the pedestal shrinks the range, the
    //     same code normalizes lower — exactly (12287-2048)/(16383-2048).
    {
        RawLevelsOverride lv;
        for (int s = 0; s < 4; ++s) lv.black[s] = 2048.0f;
        const ImageF img = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, lv);
        const float expect = (float(planted) - 2048.0f) / (float(kWhite) - 2048.0f);
        CHECK(std::abs(expect - 0.7142663f) < 2e-6f);
        const float got = img.rgb[(size_t(kH / 2) * kW + kW / 2) * 3];
        CHECK(got == doctest::Approx(expect).epsilon(1e-5));
    }
    // (c) white and black both overridden: the full stage-1 substitution.
    {
        RawLevelsOverride lv;
        lv.white = 14335.0f;
        for (int s = 0; s < 4; ++s) lv.black[s] = 2048.0f;
        const ImageF img = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, lv);
        const float expect = (float(planted) - 2048.0f) / (14335.0f - 2048.0f);
        const float got = img.rgb[(size_t(kH / 2) * kW + kW / 2) * 3];
        CHECK(got == doctest::Approx(expect).epsilon(1e-5));
    }
    // (d) per-slot blacks differ: the two green slots normalize against
    //     their OWN overridden blacks (the slot wiring is the contract).
    {
        RawLevelsOverride lv;
        lv.black[1] = 1024.0f;  // slot 1: one green
        lv.black[3] = 3072.0f;  // slot 3: blue
        const ImageF img = developCpu(cfa.data(), info, meta, grade, nullptr, nullptr, lv);
        const size_t c = (size_t(kH / 2) * kW + kW / 2) * 3;
        // Center is (1,1) mod 2 in a 64x48 frame: a blue site on RGGB.
        CHECK(img.rgb[c + 2] == doctest::Approx((float(planted) - 3072.0f) / (float(kWhite) - 3072.0f)).epsilon(1e-5));
    }
    // (e) clipping follows the override: a code ABOVE the overridden white
    //     clamps to 1.0, a code below the overridden black clamps to 0.
    {
        RawLevelsOverride lv;
        lv.white = 12000.0f;
        const uint16_t over[3] = {16383, 16383, 16383};   // the declared white, over the override
        const uint16_t under[3] = {512, 512, 512};       // under the 1023 pedestal
        const ImageF hi = developCpu(flatCfa(over).data(), info, meta, grade, nullptr, nullptr, lv);
        const ImageF lo = developCpu(flatCfa(under).data(), info, meta, grade, nullptr, nullptr, lv);
        CHECK(hi.rgb[(size_t(kH / 2) * kW + kW / 2) * 3] == 1.0f);
        CHECK(lo.rgb[(size_t(kH / 2) * kW + kW / 2) * 3] == 0.0f);
    }
}

// ---- measureRawLevelsCandidate: the "Ölç" candidate generator --------------

TEST_CASE("measure_candidate_from_a_saturated_green_plateau") {
    // The DCG scene (test_recovery_clip_levels' own fixture shape): green
    // saturates sharply at 16352 with R/B piles below the top — a candidate
    // IS produced and its white is the planted green code, mapped back
    // through the same stage-1 normalization.
    const RawMeta meta = makeMeta();
    const FrameInfo info = makeInfo();
    std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
    // DCG noise background (never a pile-up).
    uint32_t idx[3] = {0, 0, 0};
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            cfa[size_t(y) * kW + x] = uint16_t(1024 + 16 * ((idx[c]++ * 7) % 400));
        }
    const uint32_t gSites = kW * kH / 2, rSites = kW * kH / 4;
    (void)gSites; (void)rSites;
    // Plant 500 green sites at 16352, 182 red at 8096, 152 blue at 12096
    {
        uint32_t done = 0;
        for (uint32_t y = 0; y < kH && done < 500; ++y)
            for (uint32_t x = 0; x < kW && done < 500; ++x)
                if (siteColor(x, y) == 1) { cfa[size_t(y) * kW + x] = 16352; ++done; }
        done = 0;
        for (uint32_t y = 0; y < kH && done < 182; ++y)
            for (uint32_t x = 0; x < kW && done < 182; ++x)
                if (siteColor(x, y) == 0) { cfa[size_t(y) * kW + x] = 8096; ++done; }
        done = 0;
        for (uint32_t y = 0; y < kH && done < 152; ++y)
            for (uint32_t x = 0; x < kW && done < 152; ++x)
                if (siteColor(x, y) == 2) { cfa[size_t(y) * kW + x] = 12096; ++done; }
    }
    const RawLevelsCandidate cand = measureRawLevelsCandidate(cfa.data(), info, meta);
    CHECK(cand.verdict == RawLevelsCandidate::Verdict::Candidate);
    // The candidate white is the measured G plateau code (16352), NOT the
    // declared white and NOT a rounded fraction: the exact planted code.
    CHECK(cand.white == 16352.0f);
    // The blacks are what the frame resolves to (certified, not invented).
    for (int s = 0; s < 4; ++s) CHECK(cand.black[s] == float(kBlack));
    // The measured numbers ride along (recoveryClipLevels' own output).
    CHECK(cand.maxNorm[1] == normOf(float(kBlack), float(kWhite), 16352));
    CHECK(cand.topCount[1] == 500);
    CHECK(cand.plateau[1] == 500);
    CHECK(cand.topCount[0] == 182);
    CHECK(cand.topCount[2] == 152);
    // The thresholds the recovery stages would use.
    CHECK(cand.threshold[1] == cand.maxNorm[1] - 0.004f);
    CHECK(cand.threshold[0] == cand.maxNorm[0] - 0.004f);
    CHECK(cand.threshold[2] == cand.maxNorm[2] - 0.004f);
}

TEST_CASE("measure_refuses_when_the_plateau_is_unreliable") {
    const RawMeta meta = makeMeta();
    const FrameInfo info = makeInfo();
    std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
    uint32_t idx[3] = {0, 0, 0};
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            cfa[size_t(y) * kW + x] = uint16_t(1024 + 16 * ((idx[c]++ * 7) % 400));
        }
    // (a) a flat bright patch — the A2 test's own recipe: 15 green sites
    //     per DCG code across 39 codes (16352 down to 15744). The 0.4%
    //     window catches 4 codes (band 60 >= 16) but no single code holds
    //     16 sites (top 15 < 16): NO candidate — and the white field stays
    //     at its no-candidate sentinel, no fabricated numbers.
    {
        std::vector<uint16_t> flat = cfa;
        uint32_t planted = 0;
        for (uint32_t y = 0; y < kH; ++y)
            for (uint32_t x = 0; x < kW; ++x)
                if (siteColor(x, y) == 1) {
                    flat[size_t(y) * kW + x] =
                        uint16_t(16352 - 16 * (planted++ / 15));
                    if (planted >= 585) { y = kH; break; }
                }
        const RawLevelsCandidate cand = measureRawLevelsCandidate(flat.data(), info, meta);
        CHECK(cand.verdict == RawLevelsCandidate::Verdict::NoCandidate);
        CHECK(cand.white < 0.0f);  // never fabricated
        for (int s = 0; s < 4; ++s) CHECK(cand.black[s] == float(kBlack));  // certified only
        CHECK(cand.maxNorm[1] >= 0.995f);  // the measurement still reports honestly
        CHECK(cand.topCount[1] < 16);
    }
    // (b) green piles sharply but BELOW the 0.995 gate: still no candidate.
    {
        std::vector<uint16_t> low = cfa;
        uint32_t done = 0;
        for (uint32_t y = 0; y < kH && done < 300; ++y)
            for (uint32_t x = 0; x < kW && done < 300; ++x)
                if (siteColor(x, y) == 1) { low[size_t(y) * kW + x] = 16304; ++done; }
        const RawLevelsCandidate cand = measureRawLevelsCandidate(low.data(), info, meta);
        CHECK(cand.verdict == RawLevelsCandidate::Verdict::NoCandidate);
        CHECK(cand.white < 0.0f);
        CHECK(cand.topCount[1] == 300);
    }
    // (c) nothing bright at all (the dim ramp only): no candidate, honest zeros.
    {
        const RawLevelsCandidate cand = measureRawLevelsCandidate(cfa.data(), info, meta);
        CHECK(cand.verdict == RawLevelsCandidate::Verdict::NoCandidate);
        CHECK(cand.white < 0.0f);
        CHECK(cand.maxNorm[1] < 0.995f);
    }
    // (d) degenerate input: a null buffer / zero-sized frame never measures.
    {
        FrameInfo zero = makeInfo();
        zero.width = 0;
        const RawLevelsCandidate cand = measureRawLevelsCandidate(nullptr, zero, meta);
        CHECK(cand.verdict == RawLevelsCandidate::Verdict::NoCandidate);
        CHECK(cand.white < 0.0f);
        CHECK(cand.topCount[1] == 0);
    }
}

TEST_CASE("measure_white_maps_back_through_the_frame_levels") {
    // The inverse mapping uses the frame's RESOLVED levels, not the
    // container's: with dynamic levels present, the same normalized plateau
    // maps back to a different raw code. (The candidate white must be the
    // RAW code the user would type into the override — the raw domain — so
    // the inverse runs through the same stage-1 chain the develop uses.)
    RawMeta meta = makeMeta();
    FrameInfo info = makeInfo();
    for (int s = 0; s < 4; ++s) info.dynamicBlack[s] = 1000 + s;
    info.dynamicWhite = 16200;
    std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
    uint32_t idx[3] = {0, 0, 0};
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            cfa[size_t(y) * kW + x] = uint16_t(1024 + 16 * ((idx[c]++ * 7) % 400));
        }
    // Plant green at 16192 = a sharp, saturated peak under the frame's white.
    uint32_t done = 0;
    for (uint32_t y = 0; y < kH && done < 400; ++y)
        for (uint32_t x = 0; x < kW && done < 400; ++x)
            if (siteColor(x, y) == 1) { cfa[size_t(y) * kW + x] = 16192; ++done; }
    const RawLevelsCandidate cand = measureRawLevelsCandidate(cfa.data(), info, meta);
    REQUIRE(cand.verdict == RawLevelsCandidate::Verdict::Candidate);
    CHECK(cand.white == 16192.0f);  // the planted raw code, mapped back exactly
    // The blacks are the frame's dynamic values.
    CHECK(cand.black[0] == 1000.0f);
    CHECK(cand.black[1] == 1001.0f);
    CHECK(cand.black[3] == 1003.0f);
}
