// stripe_measure — A7 (night 2026-11-26) hand-run driver: the striped
// encoded develop's 8K equality and peak-memory evidence.
//
//   stripe_measure <whole|striped|equal> [width height]
//   defaults: equal 7680 4320 (8-bit synthetic NV12, strong vertical
//   variation; see encoded_stripe_frame.hpp)
//
//   whole   — developEncoded on the full frame (the pre-A7 CPU oracle's
//             memory shape: the whole ImageF + its stage planes at once)
//   striped — the striped loop (the A7 path's shape: bounded stripe views)
//   equal   — both, then a byte compare of the FULL pipeline output
//             (develop + the JNI tail linearToRec709 -> clamp -> round)
//
// Run from the repo root (the vendor Mi-Log cube is used as the LUT stage
// when it parses). The peak-memory number for the report is the external
// measure:
//   /usr/bin/time -l build/desktop/stripe_measure whole
//   /usr/bin/time -l build/desktop/stripe_measure striped
// (maximum resident set size). `equal` is the bit-exactness proof at 8K.
#include "encoded_stripe_frame.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

using namespace rawedit;

namespace {
uint64_t fnv1a64(const std::vector<uint8_t>& b) {
    uint64_t h = 1469598103934665603ull;
    for (uint8_t v : b) {
        h ^= v;
        h *= 1099511628211ull;
    }
    return h;
}
} // namespace

int main(int argc, char** argv) {
    const std::string mode = argc > 1 ? argv[1] : "equal";
    const uint32_t W = argc > 2 ? (uint32_t)std::atoi(argv[2]) : 7680;
    const uint32_t H = argc > 3 ? (uint32_t)std::atoi(argv[3]) : 4320;
    if (mode != "whole" && mode != "striped" && mode != "equal") {
        fprintf(stderr, "usage: stripe_measure <whole|striped|equal> [width height]\n");
        return 2;
    }
    Grade g = stripefix::stageHeavyGrade();
    g.tonemap = 1; // ACES: a heavier per-pixel tail, the parity-path worst case

    const YuvFrame f = stripefix::makeFrame(W, H);
    printf("stripe_measure: %s %ux%u 8-bit NV12 synthetic (seed 1), "
           "chromaDenoise=%.0f noiseReduction=%.0f sharpness=%.0f detail=%.0f tonemap=%d\n",
           mode.c_str(), W, H, g.chromaDenoise, g.noiseReduction, g.sharpness, g.detail, g.tonemap);

    std::vector<uint8_t> rgbA, rgbB;
    const uint32_t M = rawedit::kEncodedStripeMargin;
    const uint32_t stripeRows =
        (W > 0) ? std::min(512u, (unsigned)((25165824ull / (9ull * W * 4ull)) - 2ull * M) & ~1u) : 512u;

    if (mode == "whole" || mode == "equal") {
        const std::clock_t t0 = std::clock();
        const ImageF img = developEncoded(f, g, true);
        stripefix::tailToRgb888(img, &rgbA);
        printf("whole:   %.2f s, rgb888 hash %016llx (%zu bytes)\n",
               double(std::clock() - t0) / CLOCKS_PER_SEC,
               (unsigned long long)fnv1a64(rgbA), rgbA.size());
    }
    if (mode == "striped" || mode == "equal") {
        const std::clock_t t0 = std::clock();
        const ImageF img = stripefix::developStriped(f, g, true, nullptr, nullptr, stripeRows);
        stripefix::tailToRgb888(img, &rgbB);
        printf("striped: %.2f s (%u-row stripes), rgb888 hash %016llx (%zu bytes)\n",
               double(std::clock() - t0) / CLOCKS_PER_SEC, stripeRows,
               (unsigned long long)fnv1a64(rgbB), rgbB.size());
    }
    if (mode == "equal") {
        if (rgbA.size() != rgbB.size()) {
            printf("DIFF: size %zu vs %zu\n", rgbA.size(), rgbB.size());
            return 1;
        }
        const bool eq = std::memcmp(rgbA.data(), rgbB.data(), rgbA.size()) == 0;
        printf("equal: %s (byte-exact full pipeline, %ux%u)\n", eq ? "YES" : "NO", W, H);
        return eq ? 0 : 1;
    }
    return 0;
}
