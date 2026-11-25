// test_recovery_clip_levels — night A2 (2026-11-26): unit contracts of recoveryClipLevels
// (developCpu's per-frame, per-channel measured clip levels, docs/HIGHLIGHT_RECOVERY_PLAN.md M0/r3).
// Pure logic, desktop only. DCG-style synthetic frames: 16-step codes (a 10-bit dual-conversion-gain
// sensor in a 16-bit container), black 1023, white 16383. The gates in Develop.cpp are the ground
// truth this pins:
//   - own saturation: maxNorm >= 0.995 AND a sharp peak (>= 16 sites in the top 0.4% band AND
//     >= 16 sites at the peak code AND the peak holding >= 25% of the band);
//   - R/B plateaus below the top of the range count only while green itself saturates;
//   - a smooth bright patch (spread over many codes, no pile-up) is NOT saturation: the threshold
//     stays 1.0 (only sites at the white level itself are clipped);
//   - zero-sized / null inputs return the defaults (threshold 1.0, zero counts).
// Every case prints its measured numbers (hr_levels-style); the CHECKs pin exact thresholds —
// maxNorm - 0.004, recomputed with the same float ops — and the 1.0 refusals, plus the raw-integer
// scan's agreement with a per-site float reference (the clip_levels fast-scan contract, here on
// the DCG 16-step grid where the 0.4% window spans only ~4 codes).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cstdio>
#include <vector>

using namespace rawedit;

namespace {

constexpr uint32_t kW = 128, kH = 96;
constexpr uint16_t kBlack = 1023, kWhite = 16383;  // DCG-like 16-bit container levels
const float kRange = float(kWhite) - float(kBlack);
constexpr float kBand = 0.004f;  // Develop.cpp's plateau window: the top 0.4% of the range
constexpr uint32_t kMinPlateau = 16;  // Develop.cpp's kMinPlateau: the sites gate
constexpr float kNormGate = 0.995f;   // Develop.cpp's own-saturation gate
constexpr float kNoLevel = 1.0f;      // the default: no sharp peak, only the white level itself counts

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
    return meta;
}

// Develop.cpp's stage-1 per-site normalization (uniform levels here), same float ops.
float normOf(uint16_t code) {
    const float v = (float(code) - float(kBlack)) / kRange;
    return std::min(std::max(v, 0.0f), 1.0f);
}

// DCG noise background: cycling 16-step codes 1024..7408 per channel with a stride-7 phase.
// Never a pile-up at any channel maximum, and never inside a band window the cases open
// (all planted peaks sit at >= 8096; the lowest window floor to reach them is raw 8035, ramp max 7408).
void ramp(std::vector<uint16_t>& cfa) {
    cfa.assign(size_t(kW) * kH, 0);
    uint32_t idx[3] = {0, 0, 0};
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            cfa[size_t(y) * kW + x] = uint16_t(1024 + 16 * ((idx[c]++ * 7) % 400));
        }
}

uint32_t sitesOf(int color) { return color == 1 ? kW * kH / 2 : kW * kH / 4; }

// Plant exactly `count` sites of `color` at `code`, skipping the first `skip` same-color sites of
// the row-major scan (so successive plants of one color stack instead of overwriting each other).
// Exact spectral control: the peak's top/plateau counts are the planted constants, nothing else
// can reach the window.
void plant(std::vector<uint16_t>& cfa, int color, uint16_t code, uint32_t count, uint32_t skip) {
    REQUIRE(skip + count <= sitesOf(color));
    uint32_t done = 0, seen = 0;
    for (uint32_t y = 0; y < kH && done < count; ++y)
        for (uint32_t x = 0; x < kW && done < count; ++x)
            if (siteColor(x, y) == color) {
                if (seen++ < skip) continue;
                cfa[size_t(y) * kW + x] = code;
                ++done;
            }
    REQUIRE(done == count);
}

// The definition as a per-site float scan (the shape test_highlight_recovery's fast-scan case uses):
// recoveryClipLevels must reproduce these maxima/counts exactly on the DCG grid.
struct Ref { float maxNorm[3] = {0, 0, 0}; uint64_t band[3] = {0, 0, 0}, top[3] = {0, 0, 0}; };
Ref reference(const std::vector<uint16_t>& cfa) {
    Ref r;
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            r.maxNorm[c] = std::max(r.maxNorm[c], normOf(cfa[size_t(y) * kW + x]));
        }
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const int c = siteColor(x, y);
            const float v = normOf(cfa[size_t(y) * kW + x]);
            if (v >= r.maxNorm[c] - kBand) ++r.band[c];
            if (v >= r.maxNorm[c]) ++r.top[c];
        }
    return r;
}

void checkScanMatchesReference(const RecoveryClipLevels& lv, const std::vector<uint16_t>& cfa) {
    const Ref r = reference(cfa);
    for (int c = 0; c < 3; ++c) {
        CHECK(lv.maxNorm[c] == r.maxNorm[c]);
        CHECK(lv.plateau[c] == r.band[c]);
        CHECK(lv.topCount[c] == r.top[c]);
    }
}

// threshold R G B (1.0 = none measured) | maxNorm R G B | topCount R G B | band(<=0.4% below max) R G B
void printLevels(const char* tag, const RecoveryClipLevels& lv) {
    std::printf("A2 %-38s threshold R G B = %.5f %.5f %.5f | maxNorm = %.5f %.5f %.5f | "
                "topCount = %llu %llu %llu | band = %llu %llu %llu\n", tag, lv.threshold[0], lv.threshold[1],
                lv.threshold[2], lv.maxNorm[0], lv.maxNorm[1], lv.maxNorm[2], (unsigned long long)lv.topCount[0],
                (unsigned long long)lv.topCount[1], (unsigned long long)lv.topCount[2],
                (unsigned long long)lv.plateau[0], (unsigned long long)lv.plateau[1],
                (unsigned long long)lv.plateau[2]);
}

}  // namespace

TEST_CASE("clip_levels_dcg_scene_measures_all_three_channels") {
    // The measured real behaviour (hr_measure, M0): green saturates slightly below the metadata
    // white (closest DCG code 16352 = 0.99798) while red/blue the pipeline caps pile up sharply at
    // 0.46/0.72 of the range. All three thresholds must come out measured, none the 1.0 default.
    // Green owns its saturation; red/blue sit below the top gate and count only through it.
    std::vector<uint16_t> cfa;
    ramp(cfa);
    plant(cfa, 0, 8096, 182, 0);    // red pile: 0.46048 of the range
    plant(cfa, 1, 16352, 500, 0);   // green saturates: 0.99798 (the closest DCG code to the 0.9984 plateau)
    plant(cfa, 2, 12096, 152, 0);   // blue pile: 0.72090 of the range
    const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
    printLevels("dcg scene (R+B piles, G clipped)", lv);
    checkScanMatchesReference(lv, cfa);
    for (int c = 0; c < 3; ++c) {
        CHECK(lv.threshold[c] == lv.maxNorm[c] - kBand);
        CHECK(lv.threshold[c] < kNoLevel);
    }
    CHECK(lv.maxNorm[0] == normOf(8096));
    CHECK(lv.maxNorm[1] == normOf(16352));
    CHECK(lv.maxNorm[2] == normOf(12096));
    CHECK(lv.topCount[0] == 182); CHECK(lv.plateau[0] == 182);
    CHECK(lv.topCount[1] == 500); CHECK(lv.plateau[1] == 500);
    CHECK(lv.topCount[2] == 152); CHECK(lv.plateau[2] == 152);
    CHECK(lv.maxNorm[1] >= kNormGate);
    CHECK(lv.maxNorm[0] < kNormGate);
    CHECK(lv.maxNorm[2] < kNormGate);
}

TEST_CASE("clip_levels_sharp_peak_accepts_its_exact_boundaries") {
    // (a) The minimum peak that passes: exactly 16 sites at 16352, all 16 in the band (16*4 >= 16).
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16352, kMinPlateau, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("minimal peak (16 sites)", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.topCount[1] == 16);
        CHECK(lv.plateau[1] == 16);
        CHECK(lv.threshold[1] == normOf(16352) - kBand);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (b) The share boundary: a peak of 32 holding exactly 25% of a 128-wide band (32*4 == 128) passes.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16352, 32, 0);    // the peak
        plant(cfa, 1, 16336, 32, 32);   // one DCG code down: still in the top window, below the peak
        plant(cfa, 1, 16320, 32, 64);
        plant(cfa, 1, 16304, 32, 96);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("share boundary (peak 32/128 = 25%)", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.topCount[1] == 32);
        CHECK(lv.plateau[1] == 128);
        CHECK(lv.threshold[1] == normOf(16352) - kBand);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (c) The norm-gate boundary from above: 16320 = 0.99590 >= 0.995 passes (its neighbour
    //     16304 = 0.99486 is the negative side, next case).
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16320, 32, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("norm boundary (0.99590 >= 0.995)", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.maxNorm[1] == normOf(16320));
        CHECK(lv.maxNorm[1] >= kNormGate);
        CHECK(lv.threshold[1] == normOf(16320) - kBand);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
}

TEST_CASE("clip_levels_refuses_below_the_sharp_peak_gates") {
    // Each case fails exactly one leg of the three-part sharp test (band >= 16, top >= 16,
    // top*4 >= band) or the 0.995 norm gate; the threshold must fall back to the 1.0 default.
    // (a) norm gate: 500 sites sharp at 16304 = 0.99486 < 0.995 — a pile in the dark corner of
    //     the window is not own saturation.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16304, 500, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("sharp but norm 0.99486 < 0.995", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.maxNorm[1] == normOf(16304));
        CHECK(lv.maxNorm[1] < kNormGate);
        CHECK(lv.topCount[1] == 500);      // sharp legs would pass...
        CHECK(lv.plateau[1] == 500);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == kNoLevel);  // ...but the gate refuses
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (b) sites gate: 15 sites at the peak (band 30 >= 16 passes, top 15 < 16 refuses).
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16352, 15, 0);
        plant(cfa, 1, 16336, 15, 15);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("15 sites at the peak (< 16)", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.maxNorm[1] >= kNormGate);
        CHECK(lv.topCount[1] == 15);
        CHECK(lv.plateau[1] == 30);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (c) share gate: the peak holds 16 of a 65-wide band (16*4 = 64 < 65, i.e. 24.6% < 25%).
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16352, 16, 0);
        plant(cfa, 1, 16336, 17, 16);
        plant(cfa, 1, 16320, 16, 33);
        plant(cfa, 1, 16304, 16, 49);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("share 16/65 = 24.6% (< 25%)", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.maxNorm[1] >= kNormGate);
        CHECK(lv.topCount[1] == 16);
        CHECK(lv.plateau[1] == 65);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
}

TEST_CASE("clip_levels_flat_bright_patch_is_not_saturation") {
    // A smooth bright region: 585 green sites spread 15 per DCG code across 39 codes
    // (16352 down to 15744). The 0.4% window catches 4 codes (band 60 >= 16) but no single
    // code holds 16 sites (top 15 < 16): a highlight without a pile-up must not be measured.
    std::vector<uint16_t> cfa;
    ramp(cfa);
    for (uint32_t i = 0; i < 39; ++i)
        plant(cfa, 1, uint16_t(16352 - 16 * i), 15, i * 15);
    const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
    printLevels("flat bright patch (585 over 39 codes)", lv);
    checkScanMatchesReference(lv, cfa);
    CHECK(lv.maxNorm[1] == normOf(16352));
    CHECK(lv.maxNorm[1] >= kNormGate);  // the patch reaches the top of the range...
    CHECK(lv.plateau[1] == 60);         // ...4 codes make it into the window
    CHECK(lv.topCount[1] == 15);        // ...but no code holds 16 sites
    CHECK(lv.threshold[0] == kNoLevel);
    CHECK(lv.threshold[1] == kNoLevel);  // no pile-up: no measurement
    CHECK(lv.threshold[2] == kNoLevel);
}

TEST_CASE("clip_levels_rb_plateaus_need_green_saturation") {
    // (a) Yes-case: green saturates (500 at 16352) and the R/B piles are sharp below the top of
    //     the range — both piggy-back on green's saturation and get measured at their own peaks.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 0, 8096, 182, 0);
        plant(cfa, 1, 16352, 500, 0);
        plant(cfa, 2, 12096, 152, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("G clipped -> R/B measured", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.threshold[0] == lv.maxNorm[0] - kBand);
        CHECK(lv.threshold[1] == lv.maxNorm[1] - kBand);
        CHECK(lv.threshold[2] == lv.maxNorm[2] - kBand);
        CHECK(lv.threshold[0] < kNoLevel);
        CHECK(lv.threshold[2] < kNoLevel);
    }
    // (b) No-case: the same sharp R/B piles but green never saturates (only the dim ramp) —
    //     R/B saturation cannot be certified without green, so all thresholds stay 1.0.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 0, 8096, 182, 0);
        plant(cfa, 2, 12096, 152, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("G not clipped -> R/B refused", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.topCount[0] == 182);  // the R pile is sharp...
        CHECK(lv.plateau[0] == 182);
        CHECK(lv.topCount[2] == 152);
        CHECK(lv.plateau[2] == 152);
        CHECK(lv.maxNorm[0] < kNormGate);
        CHECK(lv.threshold[0] == kNoLevel);  // ...and still refused
        CHECK(lv.threshold[1] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (c) No-case: green peaks sharply too, but below the 0.995 gate (16304) — the R/B rule
    //     requires green SATURATED, not merely sharp.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16304, 500, 0);
        plant(cfa, 0, 8096, 182, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("G sharp but 0.995 -> R/B refused", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.topCount[0] == 182);   // both piles sharp...
        CHECK(lv.topCount[1] == 500);
        CHECK(lv.maxNorm[1] < kNormGate);  // ...green not saturated
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
}

TEST_CASE("clip_levels_single_channel_saturation_only_measures_that_channel") {
    // (a) Green alone saturates: only G is measured, R/B keep the 1.0 default (their ramp tops,
    //     8 sites, are noise-sharp at best and never pass without green's piggy-back anyway).
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 1, 16352, 500, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("G-only saturation", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == lv.maxNorm[1] - kBand);
        CHECK(lv.threshold[1] < kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
    // (b) Red alone piles sharply (182 at 8096): R saturation below the top gate is not
    //     certified without green, so nothing is measured at all.
    {
        std::vector<uint16_t> cfa;
        ramp(cfa);
        plant(cfa, 0, 8096, 182, 0);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("R-only sharp pile", lv);
        checkScanMatchesReference(lv, cfa);
        CHECK(lv.topCount[0] == 182);
        CHECK(lv.plateau[0] == 182);
        CHECK(lv.threshold[0] == kNoLevel);
        CHECK(lv.threshold[1] == kNoLevel);
        CHECK(lv.threshold[2] == kNoLevel);
    }
}

TEST_CASE("clip_levels_degenerate_inputs") {
    // (a) Zero-sized frames and a null buffer return the defaults untouched (threshold 1.0,
    //     zero maxima, zero counts).
    {
        FrameInfo info = makeInfo();
        RawMeta meta = makeMeta();
        std::vector<uint16_t> cfa(size_t(kW) * kH, 0);
        RecoveryClipLevels lv;
        info.width = 0;
        lv = recoveryClipLevels(cfa.data(), info, meta);
        printLevels("width 0", lv);
        for (int c = 0; c < 3; ++c) {
            CHECK(lv.threshold[c] == kNoLevel);
            CHECK(lv.maxNorm[c] == 0);
            CHECK(lv.plateau[c] == 0);
            CHECK(lv.topCount[c] == 0);
        }
        info = makeInfo(); info.height = 0;
        lv = recoveryClipLevels(cfa.data(), info, meta);
        printLevels("height 0", lv);
        for (int c = 0; c < 3; ++c) CHECK(lv.threshold[c] == kNoLevel);
        CHECK(lv.plateau[1] == 0);
        info = makeInfo();
        lv = recoveryClipLevels(nullptr, info, meta);
        printLevels("null buffer", lv);
        for (int c = 0; c < 3; ++c) CHECK(lv.threshold[c] == kNoLevel);
        CHECK(lv.topCount[1] == 0);
    }
    // (b) A fully saturated square (every site at the white level): all three channels saturate
    //     and the thresholds land at white - kBand. The counts are the RGGB site split itself
    //     (3072 R, 6144 G, 3072 B on a 128x96 RGGB frame) — the layout wiring on the line.
    {
        std::vector<uint16_t> cfa(size_t(kW) * kH, kWhite);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("fully saturated square", lv);
        checkScanMatchesReference(lv, cfa);
        for (int c = 0; c < 3; ++c) {
            CHECK(lv.maxNorm[c] == 1.0f);
            CHECK(lv.threshold[c] == 1.0f - kBand);
        }
        CHECK(lv.topCount[0] == 3072); CHECK(lv.plateau[0] == 3072);
        CHECK(lv.topCount[1] == 6144); CHECK(lv.plateau[1] == 6144);
        CHECK(lv.topCount[2] == 3072); CHECK(lv.plateau[2] == 3072);
    }
    // (c) A fully black frame (every site below the black level): the band/top windows collapse
    //     to "everything", so the counts are full but the maxima are 0 — no saturation claimed.
    {
        std::vector<uint16_t> cfa(size_t(kW) * kH, 512);
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), makeInfo(), makeMeta());
        printLevels("fully black square (512)", lv);
        checkScanMatchesReference(lv, cfa);
        for (int c = 0; c < 3; ++c) {
            CHECK(lv.maxNorm[c] == 0.0f);
            CHECK(lv.threshold[c] == kNoLevel);
        }
        CHECK(lv.topCount[1] == 6144);  // every site is "at" the maximum (0): degenerate but counted
    }
}
