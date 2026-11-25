// hr_measure — M0 of docs/HIGHLIGHT_RECOVERY_PLAN.md (desktop tool, not a test).
//
// Usage: hr_measure <clip.mcraw> <frameIndex>
//
// Answers, on a REAL frame, the two questions about where clipping really sits:
//   (1) Do channels saturate AT the metadata white level, or below it? The
//       per-channel distribution of the top raw codes (before lens shading) and
//       the share of sites at >= 1.00 / 0.99 / 0.98 / 0.97 / 0.95 of the
//       normalized range tell which clip threshold fits.
//   (2) What does stage 3's own test (norm * lens-shading gain >= 1.0, after
//       the stage-1 clamp) call "clipped", versus the raw >= white truth?
//       Sites it misses (raw at white but shading gain < 1) and sites it
//       over-flags (raw below white pushed over 1.0 by a gain > 1).
// Regions: the whole frame, and the "bright tiles" = the top 1% of 64x64 tiles
// by mean normalized G (so the window/lamp is found without guessing the
// orientation). Everything printed comes from the frame; nothing is assumed.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// 0 = R, 1 = G, 2 = B for the site at (x, y).
int colorAt(CfaPattern p, uint32_t x, uint32_t y) {
    const int cx = int(x & 1), cy = int(y & 1);
    switch (p) {
        case CfaPattern::RGGB: return (cy == 0 && cx == 0) ? 0 : (cy == 1 && cx == 1) ? 2 : 1;
        case CfaPattern::BGGR: return (cy == 0 && cx == 0) ? 2 : (cy == 1 && cx == 1) ? 0 : 1;
        case CfaPattern::GRBG: return (cy == 0 && cx == 1) ? 0 : (cy == 1 && cx == 0) ? 2 : 1;
        case CfaPattern::GBRG: return (cy == 0 && cx == 1) ? 2 : (cy == 1 && cx == 0) ? 0 : 1;
    }
    return 1;
}

struct ChanStats {
    uint64_t n = 0;
    uint32_t maxRaw = 0;
    uint64_t atWhite = 0;               // raw >= white
    uint64_t ge[5] = {0, 0, 0, 0, 0};   // norm >= 1.00 .99 .98 .97 .95
    uint64_t stage3Flag = 0;            // norm * shadingGain >= 1.0
    uint64_t missed = 0;                // raw >= white but stage 3 would not flag
    uint64_t overFlag = 0;              // raw < white but stage 3 flags
    std::map<uint32_t, uint64_t> top;   // raw code -> count, for raw >= 0.9 * white
    std::map<uint32_t, uint64_t> all;   // raw code -> count (every site)
};

const float kThr[5] = {1.00f, 0.99f, 0.98f, 0.97f, 0.95f};
const char* kName[3] = {"R", "G", "B"};

void report(const char* title, const ChanStats (&cs)[3], float white) {
    std::printf("\n== %s ==\n", title);
    for (int c = 0; c < 3; ++c) {
        const ChanStats& s = cs[c];
        if (s.n == 0) continue;
        const double d = 100.0 / double(s.n);
        std::printf("%s: sites=%llu maxRaw=%u (%.4f of white)\n", kName[c],
                    (unsigned long long)s.n, s.maxRaw, double(s.maxRaw) / white);
        std::printf("   share norm>=1.00:%.3f%% >=0.99:%.3f%% >=0.98:%.3f%% >=0.97:%.3f%% >=0.95:%.3f%%\n",
                    s.ge[0] * d, s.ge[1] * d, s.ge[2] * d, s.ge[3] * d, s.ge[4] * d);
        std::printf("   stage3 flags (norm*shading>=1): %.3f%%  missed(raw>=white, not flagged): %.3f%%  "
                    "over-flagged(raw<white, flagged): %.3f%%\n",
                    s.stage3Flag * d, s.missed * d, s.overFlag * d);
        std::printf("   top raw codes (code:count) from the maximum down:");
        {
            int shown = 0;
            for (auto it = s.all.rbegin(); it != s.all.rend() && shown < 10; ++it, ++shown)
                std::printf(" %u:%llu", it->first, (unsigned long long)it->second);
            std::printf("\n");
        }
        std::vector<std::pair<uint64_t, uint32_t>> v;
        for (const auto& kv : s.top) v.push_back({kv.second, kv.first});
        std::sort(v.rbegin(), v.rend());
        std::printf("   most frequent raw codes >= 0.9*white:");
        for (size_t i = 0; i < v.size() && i < 6; ++i)
            std::printf(" %u(x%llu,%.4f)", v[i].second, (unsigned long long)v[i].first,
                        double(v[i].second) / white);
        std::printf("\n");
    }
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: hr_measure <clip.mcraw> <frameIndex>\n");
        return 2;
    }
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) {
        std::fprintf(stderr, "open: %s\n", opened.detail.c_str());
        return 1;
    }
    Source& src = **opened.value;
    const size_t fi = size_t(std::strtoull(argv[2], nullptr, 10));
    if (fi >= src.frameCount()) {
        std::fprintf(stderr, "frame %zu >= frameCount %zu\n", fi, src.frameCount());
        return 1;
    }
    auto dec = src.readFrame(fi);
    if (!dec.isOk()) {
        std::fprintf(stderr, "decode: %s\n", dec.detail.c_str());
        return 1;
    }
    const std::vector<uint16_t>& cfa = *dec.value;
    const FrameInfo info = src.frameInfo(fi);
    const RawMeta meta = src.rawMeta();

    const bool useDynBlack = info.dynamicBlack[0] >= 0;
    const float white = info.dynamicWhite > 0 ? float(info.dynamicWhite) : float(meta.white);
    float black[4], range[4];
    for (int s = 0; s < 4; ++s) {
        black[s] = useDynBlack ? float(info.dynamicBlack[s]) : float(meta.black[s]);
        range[s] = white > black[s] ? white - black[s] : 1.0f;
    }
    std::printf("clip=%s frame=%zu/%zu  %ux%u cfa=%d bitDepth=%u orientationDeg=%d\n", argv[1], fi,
                src.frameCount(), info.width, info.height, int(info.cfa), info.bitDepth,
                info.orientationDeg);
    std::printf("levels: metaWhite=%u dynamicWhite=%d used=%.0f  black(used)= %.0f %.0f %.0f %.0f "
                "(dynamicBlack=%s)\n",
                meta.white, info.dynamicWhite, white, black[0], black[1], black[2], black[3],
                useDynBlack ? "yes" : "no");
    std::printf("wbGains = %.4f %.4f %.4f  lensShadingPlanes=%zu (%ux%u)\n", meta.wbGains[0],
                meta.wbGains[1], meta.wbGains[2],
                meta.shadingW && meta.shadingH ? meta.lensShading.size() / (size_t(meta.shadingW) * meta.shadingH) : 0,
                meta.shadingW, meta.shadingH);

    std::printf("asShotNeutral = %.4f %.4f %.4f  -> 1/neutral = %.4f %.4f %.4f  (stage 5 divides by neutral; "
                "stage 3 uses wbGains above: a mismatch makes stage 3's 'neutral' cap tinted)\n",
                meta.asShotNeutral[0], meta.asShotNeutral[1], meta.asShotNeutral[2],
                1.0f / meta.asShotNeutral[0], 1.0f / meta.asShotNeutral[1], 1.0f / meta.asShotNeutral[2]);
    const uint32_t w = info.width, h = info.height;
    // Bright tiles: top 1% of 64x64 tiles by mean normalized G.
    const uint32_t T = 64, tw = (w + T - 1) / T, th = (h + T - 1) / T;
    std::vector<double> tileSum(size_t(tw) * th, 0.0);
    std::vector<uint32_t> tileN(size_t(tw) * th, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = int((y & 1) * 2 + (x & 1));
            if (colorAt(info.cfa, x, y) != 1) continue;
            const float v = std::min(std::max((float(cfa[size_t(y) * w + x]) - black[slot]) / range[slot], 0.0f), 1.0f);
            const size_t t = size_t(y / T) * tw + x / T;
            tileSum[t] += v;
            tileN[t]++;
        }
    std::vector<std::pair<double, size_t>> ranked;
    for (size_t t = 0; t < tileSum.size(); ++t)
        if (tileN[t]) ranked.push_back({tileSum[t] / tileN[t], t});
    std::sort(ranked.rbegin(), ranked.rend());
    const size_t keep = std::max<size_t>(1, ranked.size() / 100);
    std::vector<char> bright(tileSum.size(), 0);
    for (size_t i = 0; i < keep; ++i) bright[ranked[i].second] = 1;
    std::printf("bright tiles: %zu of %zu (top 1%% by mean G; brightest mean G = %.4f, cutoff = %.4f)\n",
                keep, ranked.size(), ranked.front().first, ranked[keep - 1].first);

    ChanStats whole[3], region[3];
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = int((y & 1) * 2 + (x & 1));
            const int c = colorAt(info.cfa, x, y);
            const uint32_t raw = cfa[size_t(y) * w + x];
            const float norm = std::min(std::max((float(raw) - black[slot]) / range[slot], 0.0f), 1.0f);
            const float gain = sampleShading(meta, w > 1 ? float(x) / float(w - 1) : 0.0f,
                                             h > 1 ? float(y) / float(h - 1) : 0.0f, slot);
            const bool inBright = bright[size_t(y / T) * tw + x / T] != 0;
            for (int pass = 0; pass < 2; ++pass) {
                if (pass == 1 && !inBright) continue;
                ChanStats& s = (pass == 0 ? whole : region)[c];
                s.n++;
                s.maxRaw = std::max(s.maxRaw, raw);
                const bool atW = float(raw) >= white;
                if (atW) s.atWhite++;
                for (int k = 0; k < 5; ++k)
                    if (norm >= kThr[k] - 1e-6f) s.ge[k]++;
                const bool flag = norm * gain >= 1.0f;
                if (flag) s.stage3Flag++;
                if (atW && !flag) s.missed++;
                if (!atW && flag) s.overFlag++;
                if (norm >= 0.9f) s.top[raw]++;
                s.all[raw]++;
            }
        }
    }
    {
        const RecoveryClipLevels lv = recoveryClipLevels(cfa.data(), info, meta);
        std::printf("recoveryClipLevels (normalized raw, >1 = none): R=%.5f G=%.5f B=%.5f\n", lv.threshold[0],
                    lv.threshold[1], lv.threshold[2]);
        Grade a; a.highlightReconstruction = true;
        Grade b = a; b.highlightRecovery = true;
        // HR_K=<K> overrides the shipped headroom cap (0 = uncapped) for before/after comparisons.
        RecoveryParams diag;
        if (const char* k = std::getenv("HR_K")) { diag.headroom = float(std::atof(k)); setRecoveryDiagnostics(&diag, nullptr); }
        const ImageF ia = developCpu(cfa.data(), info, meta, a), ib = developCpu(cfa.data(), info, meta, b);
        setRecoveryDiagnostics(nullptr, nullptr);
        size_t changed = 0, above1 = 0, lowered = 0;
        float maxA = 0, maxB = 0;
        size_t maxAt = 0;
        double sumGain = 0;
        for (size_t i = 0; i < ia.rgb.size(); i += 3) {
            bool ch = false;
            for (int c = 0; c < 3; ++c) {
                maxA = std::max(maxA, ia.rgb[i + c]);
                if (ib.rgb[i + c] > maxB) maxAt = i;
                maxB = std::max(maxB, ib.rgb[i + c]);
                if (ib.rgb[i + c] != ia.rgb[i + c]) ch = true;
                if (ib.rgb[i + c] < ia.rgb[i + c] - 1e-6f) ++lowered;  // matrix cross-terms: a raised G lowers R/B (negative off-diagonals)
            }
            if (ch) {
                ++changed;
                sumGain += double(ib.rgb[i] + ib.rgb[i + 1] + ib.rgb[i + 2]) / std::max(1e-6f, ia.rgb[i] + ia.rgb[i + 1] + ia.rgb[i + 2]);
            }
            if (ib.rgb[i] > 1.0f || ib.rgb[i + 1] > 1.0f || ib.rgb[i + 2] > 1.0f) ++above1;
        }
        if (const char* crop = std::getenv("HR_CROP")) {  // x,y,w,h: writes hr_neutral.ppm / hr_recovery.ppm (+ _2ev = exposure -2 EV)
            long cx = 0, cy = 0, cw = 0, ch = 0;
            if (std::sscanf(crop, "%ld,%ld,%ld,%ld", &cx, &cy, &cw, &ch) == 4) {
                const struct { const ImageF* im; const char* name; } outs[2] = {{&ia, "hr_neutral"}, {&ib, "hr_recovery"}};
                for (const auto& o : outs)
                    for (int ev = 0; ev < 2; ++ev) {
                        const std::string fn = std::string(o.name) + (ev ? "_2ev.ppm" : ".ppm");
                        std::FILE* f = std::fopen(fn.c_str(), "wb");
                        if (!f) continue;
                        std::fprintf(f, "P6\n%ld %ld\n255\n", cw, ch);
                        const float gain = ev ? 0.25f : 1.0f;
                        for (long y = cy; y < cy + ch; ++y)
                            for (long x = cx; x < cx + cw; ++x)
                                for (int c = 0; c < 3; ++c) {
                                    const float v = std::min(std::max(o.im->rgb[(size_t(y) * o.im->w + size_t(x)) * 3 + c] * gain, 0.0f), 1.0f);
                                    std::fputc(int(std::pow(v, 1.0f / 2.2f) * 255.0f + 0.5f), f);
                                }
                        std::fclose(f);
                    }
            }
        }
        const double npx = double(ia.rgb.size() / 3);
        std::printf("M2 Neutral vs Recovery (full-res develop, neutral grade otherwise): pixels changed=%zu (%.4f%%), "
                    "mean sum(RGB) ratio on changed=%.3f, channel-values lower than Neutral (matrix cross-terms)=%zu, pixels with a channel >1.0: recovery=%zu\n"
                    "   max linear value: neutral=%.3f recovery=%.3f\n",
                    changed, 100.0 * changed / npx, changed ? sumGain / changed : 0.0, lowered, above1, maxA, maxB);
        const size_t px = maxAt / 3;
        std::printf("   recovery max at (%zu,%zu): neutral=(%.3f %.3f %.3f) recovery=(%.3f %.3f %.3f)\n", px % ia.w, px / ia.w,
                    ia.rgb[maxAt], ia.rgb[maxAt + 1], ia.rgb[maxAt + 2], ib.rgb[maxAt], ib.rgb[maxAt + 1], ib.rgb[maxAt + 2]);
        {   // the 5x5 neighbourhood of the maximum: recovered G (and R/B) next to what the neutral render has
            const long cx = long(px % ia.w), cy = long(px / ia.w);
            for (long dy = -2; dy <= 2; dy += 2) {
                std::printf("   row y=%ld:", cy + dy);
                for (long dx = -4; dx <= 4; dx += 2) {
                    const size_t q = (size_t(cy + dy) * ia.w + size_t(cx + dx)) * 3;
                    std::printf(" [%.2f %.2f %.2f|%.2f %.2f %.2f]", ia.rgb[q], ia.rgb[q + 1], ia.rgb[q + 2], ib.rgb[q], ib.rgb[q + 1], ib.rgb[q + 2]);
                }
                std::printf("\n");
            }
        }
    }
    report("whole frame", whole, white);
    report("bright tiles (window / lamp)", region, white);
    return 0;
}
