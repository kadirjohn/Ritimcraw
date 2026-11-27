// hr_joint_estimate — D8 night research (docs/HIGHLIGHT_RECOVERY_PLAN.md "Queue" item 2,
// 2026-11-27): measure, on the M1 synthetic scene, what the 1-2-channel-clipped band is
// made of and what the joint two-channel estimate would need. Run by hand, not a test.
//
//   hr_joint_estimate pairs [drift=0.08]   pixel-type counts: which (c1,c2) clipped pair and
//                                          which reference channel r each band pixel uses
//   hr_joint_estimate support [drift=0.08] per pair (c,r): the 9x9 valid-sample count W at the
//                                          band's consumer pixels, the scale histogram
//                                          (9x9 / 8 / 32 / 128 / 512-block / none), and the
//                                          pair ratio rho12's own support
//   hr_joint_estimate m1 [drift=0.08]      the M1 [1/2 channel clipped] table (hue R/G, B/G,
//                                          linear error) for Neutral and Recovery, plus the
//                                          pair breakdown of the errors (per pair type)
//
// The scene is the test_highlight_recovery fixture (same sceneRgb, same develop path), so the
// numbers line up with the doctest M1 lines. Everything is printed from the run; nothing is
// assumed. RESEARCH BRANCH ONLY (research-recovery-joint-estimate): never merged to main.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

constexpr uint32_t kW = 256, kH = 192;
constexpr float kBlack = 64.0f, kWhite = 4095.0f;
constexpr float kNeutral[3] = {0.55f, 1.0f, 0.70f};  // as-shot neutral (sensor space), as in the test
constexpr float kHeadroom = 4.0f;                     // the shipped K

float gDrift = 0.08f;

void sceneRgb(uint32_t x, uint32_t y, float out[3]) {
    const float u = float(x) / float(kW - 1), v = float(y) / float(kH - 1);
    const float lum = 0.3f + 2.7f * u;
    const float warm = 1.0f + gDrift * (1.0f - u) * (1.0f - v) - 0.8f * gDrift * u * v;
    out[0] = lum * warm;
    out[1] = lum;
    out[2] = lum / warm;
}

int siteColor(uint32_t x, uint32_t y) {  // RGGB
    return ((y & 1) == 0) ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
}

// True when a pixel within 3 px horizontally has all three channels clipped (neutral-capped by design).
bool nearAllClipped(uint32_t x, uint32_t y) {
    for (int dx = -3; dx <= 3; ++dx) {
        const int xx = std::min(std::max(int(x) + dx, 0), int(kW) - 1);
        float s[3];
        sceneRgb(uint32_t(xx), y, s);
        const float t = 0.99f;
        if (s[0] * kNeutral[0] >= t && s[1] * kNeutral[1] >= t && s[2] * kNeutral[2] >= t) return true;
    }
    return false;
}

struct Scene {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
    RecoveryClipLevels levels;   // the thresholds developCpu itself used
    std::vector<float> norm;     // stage-1 normalized sites
    std::vector<uint8_t> preClip;
};

// The M1 fixture's own develop-input chain, rebuilt outside developCpu: clipped CFA + the
// measured clip levels + the pre-shading clip flags. bit-identical inputs to the reference's
// stage-1/threshold expressions (uniform black/white here, so the per-slot split is invisible).
Scene makeScene() {
    Scene s;
    s.info.width = kW; s.info.height = kH; s.info.bitDepth = 16; s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, int(kW), int(kH)};
    for (int i = 0; i < 4; ++i) s.meta.black[i] = uint16_t(kBlack);
    s.meta.white = uint16_t(kWhite);
    for (int c = 0; c < 3; ++c) { s.meta.asShotNeutral[c] = kNeutral[c]; s.meta.wbGains[c] = 1.0f / kNeutral[c]; }
    s.meta.colorMatrix[0] = s.meta.colorMatrix[4] = s.meta.colorMatrix[8] = 1;
    s.meta.forwardMatrix[0] = s.meta.forwardMatrix[4] = s.meta.forwardMatrix[8] = 1;

    s.cfa.assign(size_t(kW) * kH, 0);
    s.norm.assign(size_t(kW) * kH, 0.0f);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            float sc[3];
            sceneRgb(x, y, sc);
            const int c = siteColor(x, y);
            float nv = std::min(sc[c] * kNeutral[c], 1.0f);
            s.norm[size_t(y) * kW + x] = nv;
            s.cfa[size_t(y) * kW + x] = uint16_t(kBlack + nv * (kWhite - kBlack) + 0.5f);
        }
    s.levels = recoveryClipLevels(s.cfa.data(), s.info, s.meta);
    s.preClip.assign(size_t(kW) * kH, 0);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const size_t i = size_t(y) * kW + x;
            s.preClip[i] = s.norm[i] >= s.levels.threshold[siteColor(x, y)] ? 1 : 0;
        }
    return s;
}

// The demosaiced WB camera RGB recoverClippedHighlights sees (stage 4 + the recovery WB
// division; no shading, no matrix — the fixture's meta has none of either in effect).
ImageF wbCameraRgb(const Scene& s) {
    std::vector<float> maskCfa(size_t(kW) * kH);
    for (size_t i = 0; i < maskCfa.size(); ++i) maskCfa[i] = s.preClip[i] ? 1.0f : 0.0f;
    ImageF img = demosaicBilinear(s.norm, kW, kH, CfaPattern::RGGB);
    const float inv[3] = {1.0f / kNeutral[0], 1.0f / kNeutral[1], 1.0f / kNeutral[2]};
    for (size_t i = 0; i < img.rgb.size(); i += 3) {
        img.rgb[i + 0] *= inv[0];
        img.rgb[i + 1] *= inv[1];
        img.rgb[i + 2] *= inv[2];
    }
    return img;
}

ImageF clipMaskImage(const Scene& s) {
    std::vector<float> maskCfa(size_t(kW) * kH);
    for (size_t i = 0; i < maskCfa.size(); ++i) maskCfa[i] = s.preClip[i] ? 1.0f : 0.0f;
    return demosaicBilinear(maskCfa, kW, kH, CfaPattern::RGGB);
}

// The reference's own scale table (Develop.cpp kRecoveryScaleTable), mirrored here for the
// scale histogram. The min-support values are the reference's exact doubles.
struct Scale { int kind; int param; double minSupport; };
const Scale kScales[5] = {
    {0, 4, 0.05 * 81.0},
    {1, 8, 0.05 * 9.0 * 64.0},
    {1, 32, 0.05 * 9.0 * 1024.0},
    {1, 128, 0.05 * 9.0 * 16384.0},
    {1, 512, 0.05 * 9.0 * 262144.0},
};

// Full develop of the fixture through the real pipeline (Neutral or Recovery).
ImageF developFull(bool clipSensor, float levelScale, bool neutralStage3, bool recovery) {
    FrameInfo info;
    info.width = kW; info.height = kH; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(kW), int(kH)};
    RawMeta meta;
    const float white = clipSensor ? kWhite : kBlack + (kWhite - kBlack) * levelScale;
    for (int i = 0; i < 4; ++i) meta.black[i] = uint16_t(kBlack);
    meta.white = uint16_t(white);
    for (int c = 0; c < 3; ++c) { meta.asShotNeutral[c] = kNeutral[c]; meta.wbGains[c] = 1.0f / kNeutral[c]; }
    meta.colorMatrix[0] = meta.colorMatrix[4] = meta.colorMatrix[8] = 1;
    meta.forwardMatrix[0] = meta.forwardMatrix[4] = meta.forwardMatrix[8] = 1;
    std::vector<uint16_t> cfa(size_t(kW) * kH);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            float s[3];
            sceneRgb(x, y, s);
            const int c = siteColor(x, y);
            float norm = s[c] * kNeutral[c];
            if (clipSensor) norm = std::min(norm, 1.0f);
            cfa[size_t(y) * kW + x] = uint16_t(kBlack + norm * (kWhite - kBlack) + 0.5f);
        }
    Grade g;
    g.highlightReconstruction = neutralStage3;
    g.highlightRecovery = recovery;
    return developCpu(cfa.data(), info, meta, g);
}

struct Err { double lin = 0; double hueRG = 0; double hueBG = 0; long n = 0; };

// The test's own error measure, band-limited to [minClipped, maxClipped] scene channels.
Err measure(const ImageF& out, const ImageF& truth, float truthScale, int minClipped, int maxClipped) {
    Err e;
    for (uint32_t y = 2; y + 2 < kH; ++y)
        for (uint32_t x = 2; x + 2 < kW; ++x) {
            float s[3];
            sceneRgb(x, y, s);
            int nc = 0;
            for (int c = 0; c < 3; ++c) nc += (s[c] * kNeutral[c] >= 1.0f);
            if (nc < minClipped || nc > maxClipped) continue;
            if (nearAllClipped(x, y)) continue;
            const size_t i = (size_t(y) * kW + x) * 3;
            double sum = 0; int cnt = 0;
            for (int c = 0; c < 3; ++c) {
                if (s[c] * kNeutral[c] < 1.0f) continue;
                const double gt = truth.rgb[i + c] * truthScale;
                sum += std::fabs(out.rgb[i + c] - gt) / std::max(gt, 1e-6);
                ++cnt;
            }
            e.lin += sum / cnt;
            const double gtRG = std::log(std::max(truth.rgb[i], 1e-6f) / std::max(truth.rgb[i + 1], 1e-6f));
            const double gtBG = std::log(std::max(truth.rgb[i + 2], 1e-6f) / std::max(truth.rgb[i + 1], 1e-6f));
            const double oRG = std::log(std::max(out.rgb[i], 1e-6f) / std::max(out.rgb[i + 1], 1e-6f));
            const double oBG = std::log(std::max(out.rgb[i + 2], 1e-6f) / std::max(out.rgb[i + 1], 1e-6f));
            e.hueRG += std::fabs(oRG - gtRG);
            e.hueBG += std::fabs(oBG - gtBG);
            ++e.n;
        }
    if (e.n) { e.lin /= e.n; e.hueRG /= e.n; e.hueBG /= e.n; }
    return e;
}

// The scale decision of the reference for one (c, r) pair at pixel (x, y), rebuilt from the
// same expressions (9x9 integral of ok/ratio sums; block neighbourhoods for the coarser
// scales). Returns the scale index that supplied rho, or -1 when no scale had support, and
// the 9x9 valid-sample count W.
int scaleAt(const ImageF& img, const std::vector<uint8_t>& clipped, int c, int r, uint32_t px, uint32_t py, double& w9) {
    const uint32_t w = img.w, h = img.h;
    auto ok = [&](uint32_t x, uint32_t y) {
        const size_t i = (size_t(y) * w + x) * 3;
        const float vc = img.rgb[i + c], vr = img.rgb[i + r];
        return !clipped[i + c] && !clipped[i + r] && vr > 1e-4 && vc >= 0.0f;
    };
    // 9x9 box, clamped at the borders — the reference's integral over the same rect.
    {
        double cnt = 0;
        const int y0 = std::max(0, int(py) - 4), y1 = std::min(int(h) - 1, int(py) + 4);
        const int x0 = std::max(0, int(px) - 4), x1 = std::min(int(w) - 1, int(px) + 4);
        for (int y = y0; y <= y1; ++y)
            for (int x = x0; x <= x1; ++x)
                if (ok(uint32_t(x), uint32_t(y))) ++cnt;
        w9 = cnt;
        if (cnt >= kScales[0].minSupport) return 0;
    }
    for (int s = 1; s < 5; ++s) {
        const int b = kScales[s].param;
        const int by = int(py / uint32_t(b)), bx = int(px / uint32_t(b));
        const int bw = int((w + b - 1) / b), bh = int((h + b - 1) / b);
        double cnt = 0;
        for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
                const int yy = by + dy, xx = bx + dx;
                if (yy < 0 || yy >= bh || xx < 0 || xx >= bw) continue;
                const uint32_t yEnd = std::min(uint32_t((yy + 1) * b), h);
                const uint32_t xEnd = std::min(uint32_t((xx + 1) * b), w);
                for (uint32_t y = uint32_t(yy * b); y < yEnd; ++y)
                    for (uint32_t x = uint32_t(xx * b); x < xEnd; ++x)
                        if (ok(x, y)) ++cnt;
            }
        if (cnt >= kScales[s].minSupport) return s;
    }
    return -1;
}

const char* kChan = "RGB";

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: hr_joint_estimate pairs|support|m1 [drift=0.08]\n");
        return 2;
    }
    const std::string mode = argv[1];
    if (argc > 2) gDrift = float(std::atof(argv[2]));
    std::printf("hr_joint_estimate mode=%s drift=%.2f scene=%ux%u\n", mode.c_str(), gDrift, kW, kH);

    const Scene s = makeScene();
    std::printf("clip thresholds: R=%.4f G=%.4f B=%.4f (maxNorm %.4f %.4f %.4f, plateau %llu %llu %llu, top %llu %llu %llu)\n",
                s.levels.threshold[0], s.levels.threshold[1], s.levels.threshold[2],
                s.levels.maxNorm[0], s.levels.maxNorm[1], s.levels.maxNorm[2],
                (unsigned long long)s.levels.plateau[0], (unsigned long long)s.levels.plateau[1],
                (unsigned long long)s.levels.plateau[2],
                (unsigned long long)s.levels.topCount[0], (unsigned long long)s.levels.topCount[1],
                (unsigned long long)s.levels.topCount[2]);

    // ---- the recovery input: WB camera RGB + the demosaiced clip mask ----
    const ImageF img = wbCameraRgb(s);
    const ImageF mask = clipMaskImage(s);
    const size_t n = size_t(kW) * kH;
    std::vector<uint8_t> clipped(n * 3, 0);
    for (size_t i = 0; i < n; ++i) {
        clipped[i * 3 + 0] = mask.rgb[i * 3 + 0] >= 0.5f ? 1 : 0;
        clipped[i * 3 + 1] = mask.rgb[i * 3 + 1] >= 0.5f ? 1 : 0;
        clipped[i * 3 + 2] = mask.rgb[i * 3 + 2] >= 0.5f ? 1 : 0;
    }

    // ---- band pixels: the test's 1-2 channel bands, per clip pattern ----
    // pattern key: the pair type (RG, GB, RB) for 2-channel pixels; the single channel for 1-channel.
    struct Counts {
        long pixels = 0;
        long refIsB = 0, refIsR = 0;   // the reference the pattern's rule picks (G never: 1-2 channel => G clipped or r=B/R)
    };
    std::map<std::string, Counts> band1, band2;   // band1: 1 channel clipped; band2: 2 channels
    for (uint32_t y = 2; y + 2 < kH; ++y)
        for (uint32_t x = 2; x + 2 < kW; ++x) {
            float sc[3];
            sceneRgb(x, y, sc);
            int nc = 0;
            for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
            if (nc < 1 || nc > 2 || nearAllClipped(x, y)) continue;
            const size_t i = size_t(y) * kW + x;
            const uint8_t* cf = &clipped[i * 3];
            const int ncf = cf[0] + cf[1] + cf[2];
            if (ncf == 0 || ncf == 3) continue;
            if (ncf == 1) {
                const int c = cf[0] ? 0 : (cf[1] ? 1 : 2);
                ++band1[std::string(1, kChan[c])].pixels;
                continue;
            }
            int c1 = -1, c2 = -1;
            for (int c = 0; c < 3; ++c)
                if (cf[c]) { if (c1 < 0) c1 = c; else c2 = c; }
            const std::string key = std::string(1, kChan[c1]) + kChan[c2];
            Counts& cc = band2[key];
            ++cc.pixels;
            if (!cf[1]) continue;  // G unclipped: the reference is G (counted separately below)
            // G clipped: reference = the unclipped one of R/B (or the larger of the two when only one is unclipped)
            if (!cf[0]) ++cc.refIsR;
            else if (!cf[2]) ++cc.refIsB;
        }

    std::printf("\n== band pixel types (scene truth 1-2 channels clipped AND the mask flags 1-2) ==\n");
    std::printf("1-channel-clipped pixels by channel:");
    for (const auto& kv : band1) std::printf(" %s=%ld", kv.first.c_str(), kv.second.pixels);
    std::printf("\n2-channel-clipped pixels by pair:");
    for (const auto& kv : band2)
        std::printf(" %s=%ld (ref R=%ld B=%ld)", kv.first.c_str(), kv.second.pixels, kv.second.refIsR, kv.second.refIsB);
    std::printf("\n");
    // The G-referenced share of the 2-channel pixels (the pair contains G unclipped => impossible for a 2-clip mask;
    // the mask's 2-clip patterns are (R,G),(G,B),(R,B) and the reference is B or R only when G is clipped).
    long gRef = 0, total2 = 0;
    for (const auto& kv : band2) total2 += kv.second.pixels;
    for (uint32_t y = 2; y + 2 < kH; ++y)
        for (uint32_t x = 2; x + 2 < kW; ++x) {
            float sc[3];
            sceneRgb(x, y, sc);
            int nc = 0;
            for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
            if (nc != 2 || nearAllClipped(x, y)) continue;
            const size_t i = size_t(y) * kW + x;
            const uint8_t* cf = &clipped[i * 3];
            if (cf[0] + cf[1] + cf[2] != 2) continue;
            if (!cf[1]) ++gRef;  // G unclipped and 2 channels clipped: the reference is G
        }
    std::printf("2-channel mask pixels with the G reference: %ld of %ld\n", gRef, total2);

    if (mode == "pairs") return 0;

    if (mode == "support") {
        // Per (c, r) pair the recovery actually reads, at the band's consumer pixels: the 9x9
        // valid-sample count W and the scale that supplies rho.
        struct Sup { long n = 0; double w9Sum = 0; long scaleHist[6] = {0, 0, 0, 0, 0, 0}; double w9Min = 1e18, w9Max = 0; };
        std::map<std::string, Sup> sup;
        for (uint32_t y = 2; y + 2 < kH; ++y)
            for (uint32_t x = 2; x + 2 < kW; ++x) {
                float sc[3];
                sceneRgb(x, y, sc);
                int nc = 0;
                for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
                if (nc < 1 || nc > 2 || nearAllClipped(x, y)) continue;
                const size_t i = size_t(y) * kW + x;
                const uint8_t* cf = &clipped[i * 3];
                const int ncf = cf[0] + cf[1] + cf[2];
                if (ncf == 0 || ncf == 3) continue;
                int r;
                if (!cf[1]) r = 1;
                else r = (img.rgb[i * 3] >= img.rgb[i * 3 + 2]) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);
                double w9 = 0;
                const int scl = scaleAt(img, clipped, cf[0] ? 0 : (cf[1] ? 1 : 2), r, x, y, w9);
                const std::string key = std::string(1, kChan[cf[0] ? 0 : (cf[1] ? 1 : 2)]) + "->" + kChan[r];
                Sup& sv = sup[key];
                ++sv.n;
                sv.w9Sum += w9;
                sv.w9Min = std::min(sv.w9Min, w9);
                sv.w9Max = std::max(sv.w9Max, w9);
                sv.scaleHist[scl + 1] += 1;
            }
        std::printf("\n== per-pair 9x9 valid-sample count W and chosen scale, at the band's consumer pixels ==\n");
        std::printf("(scale histogram: none/9x9/8/32/128/512)\n");
        for (const auto& kv : sup) {
            const Sup& sv = kv.second;
            std::printf("%-6s n=%-6ld W9 mean=%7.1f min=%4.0f max=%4.0f scale:", kv.first.c_str(), sv.n,
                        sv.w9Sum / std::max(1.0, double(sv.n)), sv.w9Min, sv.w9Max);
            for (int k = 0; k < 6; ++k) std::printf(" %ld", sv.scaleHist[k]);
            std::printf("\n");
        }
        // The pair ratio rho12's own support: at each 2-channel pixel, the (c1, c2) pair where
        // BOTH are clipped is read from neighbours where both are unclipped — the same 9x9 walk
        // with c = c1, r = c2 (the pair's own ratio), which is what the joint estimate reads.
        std::map<std::string, Sup> sup12;
        for (uint32_t y = 2; y + 2 < kH; ++y)
            for (uint32_t x = 2; x + 2 < kW; ++x) {
                float sc[3];
                sceneRgb(x, y, sc);
                int nc = 0;
                for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
                if (nc != 2 || nearAllClipped(x, y)) continue;
                const size_t i = size_t(y) * kW + x;
                const uint8_t* cf = &clipped[i * 3];
                if (cf[0] + cf[1] + cf[2] != 2) continue;
                int c1 = -1, c2 = -1;
                for (int c = 0; c < 3; ++c)
                    if (cf[c]) { if (c1 < 0) c1 = c; else c2 = c; }
                double w9 = 0;
                const int scl = scaleAt(img, clipped, c1, c2, x, y, w9);
                const std::string key = std::string("rho ") + kChan[c1] + kChan[c2];
                Sup& sv = sup12[key];
                ++sv.n;
                sv.w9Sum += w9;
                sv.w9Min = std::min(sv.w9Min, w9);
                sv.w9Max = std::max(sv.w9Max, w9);
                sv.scaleHist[scl + 1] += 1;
            }
        std::printf("\n== the pair's own ratio rho(c1,c2): 9x9 valid count and scale at the same pixels ==\n");
        for (const auto& kv : sup12) {
            const Sup& sv = kv.second;
            std::printf("%-8s n=%-6ld W9 mean=%7.1f min=%4.0f max=%4.0f scale:", kv.first.c_str(), sv.n,
                        sv.w9Sum / std::max(1.0, double(sv.n)), sv.w9Min, sv.w9Max);
            for (int k = 0; k < 6; ++k) std::printf(" %ld", sv.scaleHist[k]);
            std::printf("\n");
        }
        return 0;
    }

    if (mode == "m1") {
        const ImageF truth = developFull(false, kHeadroom, false, false);
        const ImageF neutral = developFull(true, 1, true, false);
        const ImageF recov = developFull(true, 1, true, true);
        for (int band = 1; band <= 2; ++band) {
            const Err en = measure(neutral, truth, kHeadroom, band, band), er = measure(recov, truth, kHeadroom, band, band);
            std::printf("M1 [%d channel%s clipped] pixels=%ld | hue R/G: neutral=%.4f recovery=%.4f | hue B/G: neutral=%.4f recovery=%.4f | lin: neutral=%.4f recovery=%.4f\n",
                        band, band == 1 ? "" : "s", er.n, en.hueRG, er.hueRG, en.hueBG, er.hueBG, en.lin, er.lin);
        }
        // The measured band's MASK composition: how many of the band pixels the mask flags with
        // 1 / 2 / 3 clipped channels, and for the 2-flag ones which pair. This says which code
        // path each band pixel actually takes in recoverClippedHighlights.
        long hist[4] = {0, 0, 0, 0};
        std::map<std::string, long> pairHist;
        for (uint32_t y = 2; y + 2 < kH; ++y)
            for (uint32_t x = 2; x + 2 < kW; ++x) {
                float sc[3];
                sceneRgb(x, y, sc);
                int nc = 0;
                for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
                if (nc != 2 || nearAllClipped(x, y)) continue;
                const size_t i = size_t(y) * kW + x;
                const uint8_t* cf = &clipped[i * 3];
                const int ncf = cf[0] + cf[1] + cf[2];
                ++hist[ncf];
                if (ncf == 2) {
                    std::string key;
                    for (int c = 0; c < 3; ++c)
                        if (cf[c]) key += kChan[c];
                    ++pairHist[key];
                }
            }
        std::printf("M1 2-channel band mask composition: ncf=0:%ld ncf=1:%ld ncf=2:%ld ncf=3:%ld\n",
                    hist[0], hist[1], hist[2], hist[3]);
        std::printf("M1 2-channel band ncf=2 pairs:");
        for (const auto& kv : pairHist) std::printf(" %s=%ld", kv.first.c_str(), kv.second);
        std::printf("\n");
        // Per mask pair: the hue error of BOTH Neutral and Recovery (the pair the brief names).
        struct PErr { long n = 0; double dRGn = 0, dBGn = 0, dRGr = 0, dBGr = 0, linN = 0, linR = 0; };
        std::map<std::string, PErr> perPair;
        for (uint32_t y = 2; y + 2 < kH; ++y)
            for (uint32_t x = 2; x + 2 < kW; ++x) {
                float sc[3];
                sceneRgb(x, y, sc);
                int nc = 0;
                for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
                if (nc != 2 || nearAllClipped(x, y)) continue;
                const size_t i = size_t(y) * kW + x;
                const uint8_t* cf = &clipped[i * 3];
                const int ncf = cf[0] + cf[1] + cf[2];
                if (ncf != 2) continue;
                std::string key;
                for (int c = 0; c < 3; ++c)
                    if (cf[c]) key += kChan[c];
                PErr& e = perPair[key];
                const size_t t = i * 3;
                double sumN = 0, sumR = 0; int cnt = 0;
                for (int c = 0; c < 3; ++c) {
                    if (sc[c] * kNeutral[c] < 1.0f) continue;
                    const double gt = truth.rgb[t + c] * kHeadroom;
                    sumN += std::fabs(neutral.rgb[t + c] - gt) / std::max(gt, 1e-6);
                    sumR += std::fabs(recov.rgb[t + c] - gt) / std::max(gt, 1e-6);
                    ++cnt;
                }
                e.linN += sumN / cnt; e.linR += sumR / cnt;
                const double gtRG = std::log(std::max(truth.rgb[t], 1e-6f) / std::max(truth.rgb[t + 1], 1e-6f));
                const double gtBG = std::log(std::max(truth.rgb[t + 2], 1e-6f) / std::max(truth.rgb[t + 1], 1e-6f));
                for (int which = 0; which < 2; ++which) {
                    const ImageF& im = which == 0 ? neutral : recov;
                    const double oRG = std::log(std::max(im.rgb[t], 1e-6f) / std::max(im.rgb[t + 1], 1e-6f));
                    const double oBG = std::log(std::max(im.rgb[t + 2], 1e-6f) / std::max(im.rgb[t + 1], 1e-6f));
                    if (which == 0) { e.dRGn += std::fabs(oRG - gtRG); e.dBGn += std::fabs(oBG - gtBG); }
                    else { e.dRGr += std::fabs(oRG - gtRG); e.dBGr += std::fabs(oBG - gtBG); }
                }
                ++e.n;
            }
        for (const auto& kv : perPair) {
            const PErr& e = kv.second;
            if (!e.n) continue;
            std::printf("M1 pair %s n=%ld | hue R/G: neutral=%.4f recovery=%.4f | hue B/G: neutral=%.4f recovery=%.4f | lin: neutral=%.4f recovery=%.4f\n",
                        kv.first.c_str(), e.n, e.dRGn / e.n, e.dRGr / e.n, e.dBGn / e.n, e.dBGr / e.n, e.linN / e.n, e.linR / e.n);
        }
        // Per chosen-scale bucket (the scale that supplied rho for the clipped G, referenced to
        // R): the hue error of both renders — which scale's estimates actually carry the band.
        {
            struct BErr { long n = 0; double dRGn = 0, dBGn = 0, dRGr = 0, dBGr = 0; };
            std::map<int, BErr> perBucket;
            for (uint32_t y = 2; y + 2 < kH; ++y)
                for (uint32_t x = 2; x + 2 < kW; ++x) {
                    float sc[3];
                    sceneRgb(x, y, sc);
                    int nc = 0;
                    for (int c = 0; c < 3; ++c) nc += (sc[c] * kNeutral[c] >= 1.0f);
                    if (nc != 2 || nearAllClipped(x, y)) continue;
                    const size_t i = size_t(y) * kW + x;
                    const uint8_t* cf = &clipped[i * 3];
                    if (cf[0] + cf[1] + cf[2] != 2) continue;
                    // the reference this pattern picks + the clipped channel's scale
                    int r;
                    if (!cf[1]) r = 1;
                    else r = (img.rgb[i * 3] >= img.rgb[i * 3 + 2]) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);
                    const int cClipped = cf[0] ? 0 : (cf[1] ? 1 : 2);
                    double w9 = 0;
                    const int scl = scaleAt(img, clipped, cClipped, r, x, y, w9);
                    BErr& e = perBucket[scl];
                    const size_t t = i * 3;
                    const double gtRG = std::log(std::max(truth.rgb[t], 1e-6f) / std::max(truth.rgb[t + 1], 1e-6f));
                    const double gtBG = std::log(std::max(truth.rgb[t + 2], 1e-6f) / std::max(truth.rgb[t + 1], 1e-6f));
                    const double nRG = std::log(std::max(neutral.rgb[t], 1e-6f) / std::max(neutral.rgb[t + 1], 1e-6f));
                    const double nBG = std::log(std::max(neutral.rgb[t + 2], 1e-6f) / std::max(neutral.rgb[t + 1], 1e-6f));
                    const double rRG = std::log(std::max(recov.rgb[t], 1e-6f) / std::max(recov.rgb[t + 1], 1e-6f));
                    const double rBG = std::log(std::max(recov.rgb[t + 2], 1e-6f) / std::max(recov.rgb[t + 1], 1e-6f));
                    e.dRGn += std::fabs(nRG - gtRG); e.dBGn += std::fabs(nBG - gtBG);
                    e.dRGr += std::fabs(rRG - gtRG); e.dBGr += std::fabs(rBG - gtBG);
                    ++e.n;
                }
            for (const auto& kv : perBucket) {
                const BErr& e = kv.second;
                std::printf("M1 band scale=%d n=%ld | hue R/G: neutral=%.4f recovery=%.4f | hue B/G: neutral=%.4f recovery=%.4f\n",
                            kv.first, e.n, e.dRGn / e.n, e.dRGr / e.n, e.dBGn / e.n, e.dBGr / e.n);
            }
        }
        return 0;
    }

    std::fprintf(stderr, "unknown mode %s\n", mode.c_str());
    return 2;
}
