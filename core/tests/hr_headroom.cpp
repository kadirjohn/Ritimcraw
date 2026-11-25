// hr_headroom - the recovery headroom-cap measurement (docs/HIGHLIGHT_RECOVERY_PLAN.md, owner request 2026-11-25).
//
// Usage: hr_headroom <clip.mcraw> <frameIndex> [coreX,coreY,coreW,coreH]
//   (a) distribution of estimate / clipped-value over every re-estimated channel-pixel (uncapped), overall and by
//       how large the reference channel is relative to its own clip level;
//   (b) a sweep of headroom K x minimum-reference-fraction: pixels changed vs the uncapped recovery, the maximum
//       linear value after the camera matrix, and (when a core box is given) the mean linear RGB of that box so a
//       rule that kills the genuinely recovered core shows up.
// All numbers come from the frame; the percentile unit is a plain ratio (est/clipped), refFrac is dimensionless.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace rawedit;

static double pct(std::vector<float>& v, double p) {
    if (v.empty()) return 0.0;
    const size_t i = std::min(v.size() - 1, size_t(p * double(v.size() - 1) + 0.5));
    std::nth_element(v.begin(), v.begin() + long(i), v.end());
    return v[i];
}

static void dist(const char* title, std::vector<float> r) {
    size_t g4 = 0, g8 = 0, g16 = 0;
    for (float x : r) { g4 += x > 4.0f; g8 += x > 8.0f; g16 += x > 16.0f; }
    std::vector<float> a = r, b = r, c = r, d = r;
    std::printf("%s: n=%zu p50=%.3f p90=%.3f p99=%.3f max=%.3f  >4x=%zu >8x=%zu >16x=%zu\n", title, r.size(),
                pct(a, 0.5), pct(b, 0.9), pct(c, 0.99), pct(d, 1.0), g4, g8, g16);
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: hr_headroom <clip.mcraw> <frame> [x,y,w,h]\n"); return 2; }
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    const size_t fi = size_t(std::strtoull(argv[2], nullptr, 10));
    auto dec = src.readFrame(fi);
    if (!dec.isOk()) { std::fprintf(stderr, "decode: %s\n", dec.detail.c_str()); return 1; }
    const std::vector<uint16_t>& cfa = *dec.value;
    const FrameInfo info = src.frameInfo(fi);
    const RawMeta meta = src.rawMeta();
    long cx = -1, cy = 0, cw = 0, ch = 0;
    if (argc > 3) std::sscanf(argv[3], "%ld,%ld,%ld,%ld", &cx, &cy, &cw, &ch);

    Grade g;
    g.highlightRecovery = true;
    RecoveryStats stats;
    RecoveryParams none;
    setRecoveryDiagnostics(&none, &stats);
    const ImageF base = developCpu(cfa.data(), info, meta, g);
    setRecoveryDiagnostics(nullptr, nullptr);
    std::printf("frame %zu of %s: recovered channel-pixels (uncapped): %zu\n", fi, argv[1], stats.ratio.size());
    dist("all", stats.ratio);
    const float edges[4] = {0.02f, 0.05f, 0.1f, 1e9f};
    float lo = 0.0f;
    for (float hi : edges) {
        std::vector<float> sub;
        for (size_t i = 0; i < stats.ratio.size(); ++i)
            if (stats.refFrac[i] >= lo && stats.refFrac[i] < hi) sub.push_back(stats.ratio[i]);
        char t[96];
        std::snprintf(t, sizeof t, "refFrac in [%.2f,%.2f)", lo, hi > 1e8f ? 9.99f : hi);
        dist(t, sub);
        lo = hi;
    }
    {   // isolation of the >4x channel-pixels: how many have >= 3 other >4x channel-pixels (any channel) within radius 4
        const uint32_t W = info.width;
        std::vector<std::pair<int, int>> hot;
        for (size_t i = 0; i < stats.ratio.size(); ++i)
            if (stats.ratio[i] > 4.0f) hot.push_back({int(stats.pix[i] % W), int(stats.pix[i] / W)});
        size_t isolated = 0;
        for (const auto& a : hot) {
            int near = 0;
            for (const auto& b : hot)
                if (&a != &b && std::abs(a.first - b.first) <= 4 && std::abs(a.second - b.second) <= 4) ++near;
            if (near < 3) ++isolated;
        }
        std::printf(">4x channel-pixels: %zu, isolated (fewer than 3 other >4x within 4 px): %zu\n", hot.size(), isolated);
    }
    for (int c = 0; c < 3; ++c) {
        std::vector<float> sub;
        for (size_t i = 0; i < stats.ratio.size(); ++i) if (stats.chan[i] == c) sub.push_back(stats.ratio[i]);
        char t[32];
        std::snprintf(t, sizeof t, "channel %c", "RGB"[c]);
        dist(t, sub);
    }

    auto summarize = [&](const ImageF& im, double* core) {
        float mx = 0.0f;
        for (float v : im.rgb) mx = std::max(mx, v);
        core[0] = core[1] = core[2] = 0.0;
        if (cx >= 0) {
            for (long y = cy; y < cy + ch; ++y)
                for (long x = cx; x < cx + cw; ++x)
                    for (int c = 0; c < 3; ++c) core[c] += im.rgb[(size_t(y) * im.w + size_t(x)) * 3 + c];
            for (int c = 0; c < 3; ++c) core[c] /= double(cw * ch);
        }
        return mx;
    };
    double coreBase[3];
    const float maxBase = summarize(base, coreBase);
    std::printf("\nrule sweep (changed = pixels differing from the uncapped recovery):\n");
    std::printf("  K=none  minRef=0     : max=%.3f core=(%.3f %.3f %.3f)\n", maxBase, coreBase[0], coreBase[1], coreBase[2]);
    const float ks[6] = {0.0f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f};
    const float fs[4] = {0.0f, 0.02f, 0.05f, 0.1f};
    for (float k : ks)
        for (float f : fs) {
            if (k == 0.0f && f == 0.0f) continue;
            RecoveryParams p;
            p.headroom = k;
            p.minRefFrac = f;
            setRecoveryDiagnostics(&p, nullptr);
            const ImageF im = developCpu(cfa.data(), info, meta, g);
            setRecoveryDiagnostics(nullptr, nullptr);
            size_t changed = 0;
            for (size_t i = 0; i < im.rgb.size(); i += 3)
                if (im.rgb[i] != base.rgb[i] || im.rgb[i + 1] != base.rgb[i + 1] || im.rgb[i + 2] != base.rgb[i + 2]) ++changed;
            double core[3];
            const float mx = summarize(im, core);
            std::printf("  K=%-5.0f minRef=%.2f : changed=%zu max=%.3f core=(%.3f %.3f %.3f)\n", k, f, changed, mx, core[0], core[1], core[2]);
        }
    return 0;
}
