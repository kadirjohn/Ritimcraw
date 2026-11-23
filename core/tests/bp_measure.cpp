// bp_measure — A6 black-point k sweep on real night fixtures (desktop tool).
//
// Usage: bp_measure <clip.mcraw> [clip2.mcraw ...]
//
// Owner request 2026-11-23, guide A6: stage 9 (black point) uses
// lift = -k * bp/100 with k = 0.25, so bp = +100 subtracts 0.25 linear
// (about half of display brightness) and 53-61% of night-frame pixels go
// fully black. This tool sweeps candidate k values against the real
// production pipeline and reports % fully black + mean display luma, so k
// can be chosen from measured numbers instead of a guess. It changes NO
// production constant — the sweep is local to this binary.
//
// Core mechanism (why applying stage 9 AFTER developThumbnail is exact):
// with a default-neutral Grade, every stage after 9 (10-20 tone/color,
// 21-22 unsharp, 23 tonemap, 24 LUT) is gated by "!= neutral" and is a
// no-op, so applying the stage-9 remap to developThumbnail's OUTPUT is
// exactly the in-chain result. That lets the tool measure any candidate k
// against the real production pipeline (the same demosaic, WB, shading,
// cam->Rec.709 the app runs) without editing Develop.cpp. The display
// transform mirrors the jni_bridge tail verbatim: per channel
// enc = linearToRec709(x), clamp to [0,1], quantize with
// (int)(enc * 255.0f + 0.5f).
//
// "% fully black" counts display bytes (all three channels 0) AFTER the
// OETF + clamp + quantization — the number the owner actually sees on the
// screen. The candidate remap is applied on the LINEAR floats and
// negatives are allowed (the clamp happens in the display transform, same
// as production); clamping before the remap would bias the crush count.
//
// Decision rule (guide A6 target): among candidates whose bp=+100 average
// black% is <= 15, pick the LARGEST k (strongest deepening that stays in
// budget), preferring the one landing closest to 10-15%. If every
// candidate exceeds 15, take k = 0.04 and FLAG it as over-budget (owner
// decision pending, guide "Decisions pending" #3). bp = -100 must not
// wash out absurdly: with small k the lifted floor is k linear, i.e.
// display OETF(k) — smaller k strictly reduces washout, so this check is
// expected to pass; the numbers are reported anyway.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// bench_develop.cpp's stride arithmetic: the smallest stride-every that
// keeps the decimated thumbnail within the cap on both axes.
int strideFor(const FrameInfo& f, int cap) {
    int s = std::max(1, int((std::max(f.width, f.height) + cap - 1) / cap));
    while (std::max(thumbnailWidth(f.width, s), thumbnailHeight(f.height, s)) > unsigned(cap))
        s++;
    return s;
}

// jni_bridge.cpp's toByte, verbatim: OETF output is unclamped; clamp to
// [0,1] then quantize 0..255, ties resolved up.
uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

struct Measured {
    double blackPct = 0.0; // % pixels whose three display bytes are all 0
    double meanLuma = 0.0;  // mean of (r+g+b)/3 over display bytes, 0-255
};

// Display transform + measurement for one linear image, optionally after
// applying the candidate stage-9 remap x' = lift + (1 - lift) * x per
// channel on the linear floats (negatives allowed — the clamp lives in
// the display transform, same as production).
Measured measure(const ImageF& img, bool applyRemap, float lift) {
    Measured m;
    const size_t n = static_cast<size_t>(img.w) * img.h;
    if (n == 0) return m;
    unsigned long long blackCount = 0;
    double lumaSum = 0.0;
    for (size_t p = 0; p < n; ++p) {
        float rgb[3];
        for (int c = 0; c < 3; ++c) {
            float x = img.rgb[p * 3 + c];
            if (applyRemap) x = lift + (1.0f - lift) * x;
            rgb[c] = linearToRec709(x);
        }
        const uint8_t r = toByte(rgb[0]);
        const uint8_t g = toByte(rgb[1]);
        const uint8_t b = toByte(rgb[2]);
        if (r == 0 && g == 0 && b == 0) ++blackCount;
        lumaSum += (double(r) + double(g) + double(b)) / 3.0;
    }
    m.blackPct = 100.0 * double(blackCount) / double(n);
    m.meanLuma = lumaSum / double(n);
    return m;
}

const char* clipTag(const char* path) {
    // Compact tag for the report: the file name without directories and
    // the VIDEO_...mm.mcraw suffix noise.
    const char* slash = std::strrchr(path, '/');
    const char* base = slash ? slash + 1 : path;
    return base;
}

} // namespace

#include <cstring>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s clip.mcraw [clip2.mcraw ...]\n", argv[0]);
        return 2;
    }

    const float ks[] = {0.25f, 0.12f, 0.08f, 0.06f, 0.04f};
    const int nK = int(sizeof(ks) / sizeof(ks[0]));
    const float bps[] = {100.0f, 50.0f, -100.0f};
    const int nBp = int(sizeof(bps) / sizeof(bps[0]));

    // Averages across ALL measured (clip, frame) pairs, per (k, bp).
    std::vector<std::vector<Measured>> avg(nK, std::vector<Measured>(nBp));
    std::vector<std::vector<int>> cnt(nK, std::vector<int>(nBp, 0));
    Measured avgNeutral;
    int cntNeutral = 0;

    for (int a = 1; a < argc; ++a) {
        const char* path = argv[a];
        auto opened = Source::open(std::string(path));
        if (!opened.isOk()) {
            std::fprintf(stderr, "open %s: %s\n", path, opened.detail.c_str());
            return 1;
        }
        Source& src = **opened.value;
        const size_t frames = src.frameCount();
        if (frames == 0) {
            std::fprintf(stderr, "%s: no frames\n", path);
            return 1;
        }

        // Frames {0, mid} per clip (dedupe when frameCount < 2 — a
        // single-frame clip measures once).
        std::vector<size_t> which;
        which.push_back(0);
        if (frames > 1) which.push_back(frames / 2);

        for (size_t fi : which) {
            auto decoded = src.readFrame(fi);
            if (!decoded.isOk()) {
                std::fprintf(stderr, "%s: decode frame %zu failed: %s\n", path, fi,
                              decoded.detail.c_str());
                return 1;
            }
            const std::vector<uint16_t> cfa = std::move(*decoded.value);
            const FrameInfo info = src.frameInfo(fi);
            const RawMeta meta = src.rawMeta();

            // Develop ONCE per frame with a default neutral Grade — see
            // the header: stages 10-24 are neutral no-ops, so the stage-9
            // remap on this output is exactly the in-chain result.
            const int s = strideFor(info, 1280);
            Grade neutral;
            const ImageF img =
                developThumbnail(cfa.data(), info, meta, neutral, s);
            if (img.w == 0 || img.h == 0) {
                std::fprintf(stderr, "%s: frame %zu develop failed\n", path, fi);
                return 1;
            }
            const uint32_t tw = thumbnailWidth(info.width, s);
            const uint32_t th = thumbnailHeight(info.height, s);
            std::printf("clip=%s f=%zu thumb=%ux%u (stride %d)\n", clipTag(path), fi,
                        tw, th, s);

            const Measured neutralM = measure(img, false, 0.0f);
            std::printf("clip=%s f=%zu neutral              black=%.1f%% mean=%.1f\n",
                        clipTag(path), fi, neutralM.blackPct, neutralM.meanLuma);
            avgNeutral.blackPct += neutralM.blackPct;
            avgNeutral.meanLuma += neutralM.meanLuma;
            ++cntNeutral;

            for (int ik = 0; ik < nK; ++ik) {
                for (int ib = 0; ib < nBp; ++ib) {
                    const float k = ks[ik];
                    const float bp = bps[ib];
                    const float lift = -k * bp / 100.0f;
                    const Measured m = measure(img, true, lift);
                    std::printf("clip=%s f=%zu k=%.2f bp=%+5.0f black=%.1f%% mean=%.1f\n",
                                clipTag(path), fi, k, bp, m.blackPct, m.meanLuma);
                    avg[ik][ib].blackPct += m.blackPct;
                    avg[ik][ib].meanLuma += m.meanLuma;
                    ++cnt[ik][ib];
                }
            }
        }
    }

    std::printf("\n== summary (averaged over %d frame(s), %d neutral) ==\n",
                cnt[0][0], cntNeutral);
    std::printf("%-6s %-6s %10s %10s\n", "k", "bp", "black%", "mean");
    for (int ib = 0; ib < nBp; ++ib) {
        for (int ik = 0; ik < nK; ++ik) {
            const int n = cnt[ik][ib];
            if (n == 0) continue;
            std::printf("%-6.2f %+5.0f %9.1f%% %10.1f\n", ks[ik], bps[ib],
                        avg[ik][ib].blackPct / n, avg[ik][ib].meanLuma / n);
        }
    }
    if (cntNeutral > 0)
        std::printf("neutral       %9.1f%% %10.1f\n", avgNeutral.blackPct / cntNeutral,
                    avgNeutral.meanLuma / cntNeutral);

    // Decision rule: among candidates whose bp=+100 average black% is
    // <= 15, the LARGEST k wins (strongest deepening in budget). The bp
    // sweep arrays list k in DESCENDING order, so the first in-budget hit
    // is the answer. Prefer landing closest to 10-15%: among in-budget
    // candidates, the largest k is also the closest to the 10-15% band,
    // so the first hit is stated as chosen. If nothing is in budget, take
    // the smallest k and FLAG it.
    const int pos100 = 0; // bps[0] == +100
    int chosen = -1;
    for (int ik = 0; ik < nK; ++ik) {
        const int n = cnt[ik][pos100];
        if (n > 0 && avg[ik][pos100].blackPct / n <= 15.0) {
            chosen = ik;
            break;
        }
    }
    bool overBudget = false;
    if (chosen < 0) {
        chosen = nK - 1; // k = 0.04, the smallest crush
        overBudget = true;
    }
    const int nChosen = cnt[chosen][pos100];
    const float kChosen = ks[chosen];
    const double crush100 = nChosen ? avg[chosen][pos100].blackPct / nChosen : 0.0;
    const int neg100 = 2; // bps[2] == -100
    const int nNeg = cnt[chosen][neg100];
    const double negMean = nNeg ? avg[chosen][neg100].meanLuma / nNeg : 0.0;
    const double negBlack = nNeg ? avg[chosen][neg100].blackPct / nNeg : 0.0;
    std::printf("\nchosen k=%.2f: bp=+100 black=%.1f%% | bp=-100 mean=%.1f (black=%.1f%%)%s\n",
                kChosen, crush100, negMean, negBlack,
                overBudget ? "  [OVER BUDGET — owner decision pending, guide Decisions pending #3]" : "");
    return 0;
}
