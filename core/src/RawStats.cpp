// rawedit::RawStats — PRE-DEVELOP CFA statistics (feature N6, the RAW
// histogram's compute core). See RawStats.h for the domain contract.
//
// The level resolution and the per-site normalization below mirror
// Develop.cpp stage 1 bit-for-bit, so a sample the develop stage would
// normalize to X lands in the bin that corresponds to X — the RAW
// histogram's domain mapping matches the develop domain exactly.

#include <rawedit/RawStats.h>

#include <algorithm>

namespace rawedit {

namespace {

// Site color codes within the repeating 2x2 CFA cell: 0 = R, 1 = G, 2 = B.
// Mirrors Develop.cpp's layoutFor table (the two stay in sync — the test
// suite covers all four patterns against the same site mapping Develop's
// tests use): cell[cellY][cellX] is the color of the site at
// (even+cellX, even+cellY).
struct CfaLayout {
    int cell[2][2];
};

CfaLayout layoutFor(CfaPattern p) {
    switch (p) {
        case CfaPattern::RGGB: return CfaLayout{{{0, 1}, {1, 2}}};
        case CfaPattern::GRBG: return CfaLayout{{{1, 0}, {2, 1}}};
        case CfaPattern::GBRG: return CfaLayout{{{1, 2}, {0, 1}}};
        case CfaPattern::BGGR: return CfaLayout{{{2, 1}, {1, 0}}};
    }
    return CfaLayout{{{0, 1}, {1, 2}}};
}

} // namespace

RawHistogramStats rawHistogramStats(const uint16_t* cfa, uint32_t w, uint32_t h,
                                    CfaPattern pattern,
                                    const int32_t black[4],
                                    const int32_t blackFallback[4],
                                    int32_t white, int32_t whiteFallback) {
    RawHistogramStats out;
    if (cfa == nullptr || w == 0 || h == 0) return out;

    // ---- Level resolution: Develop.cpp stage 1, mirrored exactly. ----
    // Frame black levels are all-or-nothing keyed on slot 0 (Develop's
    // useFrameBlack: dynamicBlack[0] >= 0 means the frame carried levels);
    // the white level is single and wins when > 0. Levels stay integers
    // here (they are integers everywhere upstream) so the clip counts
    // compare exactly; only the normalization goes through float, exactly
    // like the develop stage's hoisted per-slot range.
    const bool useFrameBlack = black[0] >= 0;
    const int32_t whiteUsed = white > 0 ? white : whiteFallback;
    int32_t blackUsed[4];
    float blackF[4];
    float rangeF[4];
    const float whiteF = static_cast<float>(whiteUsed);
    for (int slot = 0; slot < 4; ++slot) {
        blackUsed[slot] = useFrameBlack ? black[slot] : blackFallback[slot];
        blackF[slot] = static_cast<float>(blackUsed[slot]);
        rangeF[slot] = whiteF > blackF[slot] ? whiteF - blackF[slot] : 1.0f;
    }
    for (int slot = 0; slot < 4; ++slot) out.blackUsed[slot] = blackUsed[slot];
    out.whiteUsed = whiteUsed;

    // ---- One pass over the CFA. ----
    // Each site normalizes against ITS cell slot's black level and the
    // single white level (the develop stage's per-slot range); the two
    // green slots feed the same G histogram. Samples at or above white /
    // at or below black clamp into the end bins exactly like the develop
    // stage's [0,1] clamp, so the bins and the clip counts agree.
    const CfaLayout layout = layoutFor(pattern);
    for (uint32_t y = 0; y < h; ++y) {
        const int cellY = static_cast<int>(y & 1u);
        for (uint32_t x = 0; x < w; ++x) {
            const int cellX = static_cast<int>(x & 1u);
            const int slot = cellY * 2 + cellX;
            const int ch = layout.cell[cellY][cellX];
            const uint16_t raw = cfa[static_cast<size_t>(y) * w + x];
            const float v = (static_cast<float>(raw) - blackF[slot]) / rangeF[slot];
            const float clamped = v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
            const int bin = static_cast<int>(clamped * 256.0f);
            out.bins[ch][bin < RawHistogramStats::kBins - 1 ? bin
                                                            : RawHistogramStats::kBins - 1]++;
            out.samples[ch]++;
            // RAW clip counts (sensor domain — never compared with the
            // processed image's clipping). Guarded: a level that never
            // resolved (white <= 0 / black < 0) must not count every
            // sample as clipped.
            if (whiteUsed > 0 && static_cast<int32_t>(raw) >= whiteUsed)
                out.whiteClips[ch]++;
            if (blackUsed[slot] >= 0 && static_cast<int32_t>(raw) <= blackUsed[slot])
                out.blackClips[ch]++;
        }
    }
    return out;
}

RawDiagnostics rawDiagnostics(const uint16_t* cfa, const FrameInfo& info, const RawMeta& meta) {
    RawDiagnostics d;
    const uint32_t w = info.width, h = info.height;
    if (cfa == nullptr || w == 0 || h == 0) return d;
    d.valid = true;
    // Level resolution: identical to Develop stage 1 (and rawHistogramStats).
    const bool useFrameBlack = info.dynamicBlack[0] >= 0;
    const int32_t white = info.dynamicWhite > 0 ? info.dynamicWhite : static_cast<int32_t>(meta.white);
    d.whiteDeclared = white;
    const CfaLayout layout = layoutFor(info.cfa);
    // Position (R, Gr, Gb, B) of each 2x2 cell slot: a green is Gr when its row holds the R site.
    int posOfSlot[4];
    for (int cy = 0; cy < 2; ++cy)
        for (int cx = 0; cx < 2; ++cx) {
            const int color = layout.cell[cy][cx];
            const bool redRow = layout.cell[cy][0] == 0 || layout.cell[cy][1] == 0;
            posOfSlot[cy * 2 + cx] = color == 0 ? 0 : (color == 2 ? 3 : (redRow ? 1 : 2));
            const int slot = cy * 2 + cx;
            d.blackDeclared[posOfSlot[slot]] =
                useFrameBlack ? info.dynamicBlack[slot] : static_cast<int32_t>(meta.black[slot]);
        }
    // Peaks per slot (raw integers), then per position / channel.
    uint16_t slotMax[4] = {0, 0, 0, 0};
    for (uint32_t y = 0; y < h; ++y) {
        const uint16_t* row = cfa + static_cast<size_t>(y) * w;
        const int cy = static_cast<int>(y & 1u);
        uint16_t m0 = slotMax[cy * 2], m1 = slotMax[cy * 2 + 1];
        uint32_t x = 0;
        for (; x + 1 < w; x += 2) { m0 = std::max(m0, row[x]); m1 = std::max(m1, row[x + 1]); }
        if (x < w) m0 = std::max(m0, row[x]);
        slotMax[cy * 2] = m0;
        slotMax[cy * 2 + 1] = m1;
    }
    for (int slot = 0; slot < 4; ++slot) {
        const int pos = posOfSlot[slot];
        const int ch = layout.cell[slot / 2][slot % 2];
        d.peakRawPos[pos] = std::max(d.peakRawPos[pos], slotMax[slot]);
        d.peakRaw[ch] = std::max(d.peakRaw[ch], slotMax[slot]);
    }
    // Sites at the peak and above white (one pass; sites above white are an expected zero).
    for (uint32_t y = 0; y < h; ++y) {
        const uint16_t* row = cfa + static_cast<size_t>(y) * w;
        const int cy = static_cast<int>(y & 1u);
        for (uint32_t x = 0; x < w; ++x) {
            const int ch = layout.cell[cy][x & 1u];
            const uint16_t v = row[x];
            if (v == d.peakRaw[ch]) d.atPeak[ch]++;
            if (white > 0 && static_cast<int32_t>(v) > white) d.aboveWhite[ch]++;
        }
    }
    // The project's own plateau detector: one answer for the scope and the develop path.
    const RecoveryClipLevels lv = recoveryClipLevels(cfa, info, meta);
    for (int c = 0; c < 3; ++c) {
        d.nearPeak[c] = lv.plateau[c];
        d.plateauThreshold[c] = lv.threshold[c];
        d.reliablePlateau[c] = lv.threshold[c] < 1.0f;
    }
    return d;
}

} // namespace rawedit
