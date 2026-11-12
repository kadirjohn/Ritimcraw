// rawedit::RawStats — PRE-DEVELOP CFA statistics (feature N6, the RAW
// histogram's compute core).
//
// The RAW histogram reads the sensor domain: the pristine decoded CFA,
// BEFORE any develop stage runs. It therefore does NOT move with
// exposure/WB/LUT/grade sliders (they all act after the sensor data);
// it changes only with the frame. That stability is the point — it shows
// what the sensor captured, while the processed histogram tracks the
// displayed result. The two domains are never conflated: RAW clip counts
// describe sensor-range clipping, not clipping of the displayed image.
//
// The level resolution mirrors Develop.cpp stage 1 bit-for-bit (the
// frame's dynamic levels win, the container RawMeta values are the
// fallback), so the RAW histogram's domain mapping matches the develop
// domain exactly: a sample the develop stage would normalize to X lands
// in the bin that corresponds to X.

#pragma once

#include <cstdint>

#include <rawedit/Source.h>

namespace rawedit {

// One RAW-domain histogram pass over a CFA.
//
// Channel order everywhere: 0 = R, 1 = G (both green sites combined),
// 2 = B — the photosite channels of the Bayer pattern.
struct RawHistogramStats {
    static constexpr int kBins = 256;
    static constexpr int kChannels = 3;

    // Per-photosite-channel 256-bin histograms of the sample value
    // normalized to [0,1] against that site's [black, white] range (the
    // same normalization Develop stage 1 applies). Clipped samples pile
    // into the end bins exactly like Develop's [0,1] clamp: samples at
    // or above white land in bin 255, samples at or below black in bin 0.
    uint32_t bins[kChannels][kBins] = {};

    // RAW highlight clips per channel: samples >= white (the single
    // resolved white level). Sensor-domain counts — never compared with
    // the processed image's clipping.
    uint32_t whiteClips[kChannels] = {0, 0, 0};

    // RAW shadow clips per channel: samples <= that site's black level.
    uint32_t blackClips[kChannels] = {0, 0, 0};

    // Samples accumulated per channel (the clip thresholds' denominator;
    // G counts both green sites, so it is ~2x R and B).
    uint32_t samples[kChannels] = {0, 0, 0};

    // The black levels actually used, in 2x2 cell-slot order (r, g, g, b)
    // — the frame's dynamic levels when it carried them, the container
    // fallback otherwise (the -1 sentinel resolution below).
    int32_t blackUsed[4] = {-1, -1, -1, -1};

    // The white level actually used (single value, like Develop).
    int32_t whiteUsed = -1;
};

// One pass over the CFA: per-photosite-channel 256-bin histograms of the
// sample value normalized to [0,1], plus per-channel RAW clip counts.
//
// Level resolution — mirrors Develop.cpp stage 1 exactly:
//   - black[i] < 0 for slot 0 means "this frame did not carry black
//     levels"; ALL slots then fall back to blackFallback[i] (Develop's
//     all-or-nothing useFrameBlack, keyed on dynamicBlack[0] >= 0).
//   - white > 0 wins; otherwise whiteFallback (Develop's dynamicWhite
//     rule).
// Each site normalizes against ITS cell slot's black level and the single
// white level; the two green slots feed the same G histogram (most
// containers carry equal green levels; a split pair keeps each site's
// own level, exactly like the develop stage).
//
// No allocation beyond the output struct; single pass; no deps beyond
// Source.h's CfaPattern.
RawHistogramStats rawHistogramStats(const uint16_t* cfa, uint32_t w, uint32_t h,
                                    CfaPattern pattern,
                                    const int32_t black[4],
                                    const int32_t blackFallback[4],
                                    int32_t white, int32_t whiteFallback);

} // namespace rawedit
