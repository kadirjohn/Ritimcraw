// test_highlight_recovery — M1 of docs/HIGHLIGHT_RECOVERY_PLAN.md: synthetic ground truth.
//
// A smooth warm-to-cool scene whose per-channel level exceeds the sensor's clip point is rendered twice:
// (a) clipped at the raw white (what the sensor stores) and (b) with a white level 4x higher so nothing
// clips (the ground truth, same pipeline). Off / Neutral (stage 3) / Recovery are compared to the truth
// inside the clipped region. Numbers are printed (doctest MESSAGE); the CHECKs state relations, not
// magic numbers: recovery must beat Neutral, never go below the clipped value, and be an exact no-op on
// an unclipped image.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace rawedit;

namespace {

constexpr uint32_t kW = 256, kH = 192;
constexpr float kBlack = 64.0f, kWhite = 4095.0f;
constexpr float kNeutral[3] = {0.55f, 1.0f, 0.70f};  // as-shot neutral (sensor space)

// True per-channel radiance after WB (equal channels = neutral light). Brightness ramps 0.3 -> 3.0 along x,
// the tint drifts along both axes (warm at the top-left, cool at the bottom-right).
float gDrift = 0.08f;  // tint drift across the scene: mild (realistic) by default, 0.25 = stress case

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

struct Rendered { ImageF img; };

void makeMeta(RawMeta& m, float white) {
    for (int i = 0; i < 4; ++i) m.black[i] = uint16_t(kBlack);
    m.white = uint16_t(white);
    for (int c = 0; c < 3; ++c) { m.asShotNeutral[c] = kNeutral[c]; m.wbGains[c] = 1.0f / kNeutral[c]; }
    m.colorMatrix[0] = m.colorMatrix[4] = m.colorMatrix[8] = 1;
    m.forwardMatrix[0] = m.forwardMatrix[4] = m.forwardMatrix[8] = 1;
}

// scale: norm = radiance * neutral * scale; clipSensor: clamp the norm at 1 (the sensor), else keep unbounded.
ImageF develop(bool clipSensor, float levelScale, bool neutralStage3, bool recovery) {
    FrameInfo info;
    info.width = kW; info.height = kH; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(kW), int(kH)};
    RawMeta meta;
    const float white = clipSensor ? kWhite : kBlack + (kWhite - kBlack) * levelScale;
    makeMeta(meta, white);
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

struct Err { double lin = 0; double hueRG = 0; double hueBG = 0; long n = 0; double below = 0; };

// True when a pixel within 3 px horizontally has all three channels clipped (those become neutral-capped by design).
bool nearAllClipped(uint32_t x, uint32_t y) {
    for (int dx = -3; dx <= 3; ++dx) {
        const int xx = std::min(std::max(int(x) + dx, 0), int(kW) - 1);
        float s[3];
        sceneRgb(uint32_t(xx), y, s);
        const float t = kRecoveryClipThreshold - 0.005f;  // the mask threshold (and a hair of bilinear margin)
        if (s[0] * kNeutral[0] >= t && s[1] * kNeutral[1] >= t && s[2] * kNeutral[2] >= t) return true;
    }
    return false;
}

// Error inside the region where 1 or 2 channels are clipped in the scene (radiance*neutral >= 1).
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
            const double gtRG = std::log(std::max(truth.rgb[i] , 1e-6f) / std::max(truth.rgb[i + 1], 1e-6f));
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

}  // namespace

TEST_CASE("recovery_stress_tint_drift_report_only") {
    // A harsher scene (0.25 drift): the numbers are printed so the limit of the ratio estimator is on record.
    gDrift = 0.25f;
    const float K = 4.0f;
    const ImageF truth = develop(false, K, false, false);
    const ImageF neutral = develop(true, 1, true, false), recov = develop(true, 1, true, true);
    for (int band = 1; band <= 2; ++band) {
        const Err en = measure(neutral, truth, K, band, band), er = measure(recov, truth, K, band, band);
        std::printf("M1 STRESS drift=0.25 [%d channel(s) clipped] pixels=%ld mean|err|/gt: neutral=%.4f recovery=%.4f hue R/G: neutral=%.4f recovery=%.4f\n",
                    band, er.n, en.lin, er.lin, en.hueRG, er.hueRG);
    }
    gDrift = 0.08f;
}

TEST_CASE("recovery_vs_neutral_vs_off_against_ground_truth") {
    const float K = 4.0f;
    const ImageF truth = develop(false, K, false, false);  // unclipped, white level K x higher
    const ImageF off = develop(true, 1, false, false);
    const ImageF neutral = develop(true, 1, true, false);
    const ImageF recov = develop(true, 1, true, true);
    REQUIRE(truth.w == kW);
    for (int band = 0; band < 2; ++band) {
        const int lo = band == 0 ? 1 : 2, hi = band == 0 ? 1 : 2;
        const char* name = band == 0 ? "1 channel clipped" : "2 channels clipped";
        const Err eo = measure(off, truth, K, lo, hi), en = measure(neutral, truth, K, lo, hi),
                  er = measure(recov, truth, K, lo, hi);
        std::printf("M1 [%s] pixels=%ld  mean|err|/gt on clipped channels: off=%.4f neutral=%.4f recovery=%.4f | "
                    "hue |dlog R/G|: off=%.4f neutral=%.4f recovery=%.4f | |dlog B/G|: off=%.4f neutral=%.4f recovery=%.4f\n",
                    name, er.n, eo.lin, en.lin, er.lin, eo.hueRG, en.hueRG, er.hueRG, eo.hueBG, en.hueBG, er.hueBG);
        CHECK(er.n > 100);
        CHECK(er.lin < en.lin);
        CHECK(er.hueRG <= en.hueRG + 1e-9);
        CHECK(er.hueBG <= en.hueBG + 1e-9);
    }
    // Recovery never goes below what the off (clipped) render shows, per pixel and channel, in the 1-2 channel region.
    long below = 0;
    for (uint32_t y = 2; y + 2 < kH; ++y)
        for (uint32_t x = 2; x + 2 < kW; ++x) {
            float s[3];
            sceneRgb(x, y, s);
            int nc = 0;
            for (int c = 0; c < 3; ++c) nc += (s[c] * kNeutral[c] >= 1.0f);
            if (nc < 1 || nc > 2 || nearAllClipped(x, y)) continue;
            const size_t i = (size_t(y) * kW + x) * 3;
            for (int c = 0; c < 3; ++c)
                if (recov.rgb[i + c] < off.rgb[i + c] - 1e-4f) ++below;
        }
    std::printf("M1 recovery pixels*channels below the off render in the 1-2 channel region: %ld\n", below);
    CHECK(below == 0);
    // Values above 1.0 are produced (the point of recovery).
    float mx = 0;
    for (size_t i = 0; i < recov.rgb.size(); ++i) mx = std::max(mx, recov.rgb[i]);
    std::printf("M1 recovery max linear value = %.3f (off max = %.3f, neutral max = %.3f)\n", mx,
                *std::max_element(off.rgb.begin(), off.rgb.end()), *std::max_element(neutral.rgb.begin(), neutral.rgb.end()));
    CHECK(mx > 1.05f);
}

TEST_CASE("recovery_is_a_bit_exact_no_op_on_an_unclipped_image") {
    // Same scene dimmed so no site reaches the clip threshold: recovery must equal the Neutral render exactly.
    FrameInfo info;
    info.width = 64; info.height = 64; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, 64, 64};
    RawMeta meta;
    makeMeta(meta, kWhite);
    std::vector<uint16_t> cfa(64 * 64);
    for (uint32_t y = 0; y < 64; ++y)
        for (uint32_t x = 0; x < 64; ++x) {
            float s[3];
            sceneRgb(x * 4, y * 3, s);
            const int c = siteColor(x, y);
            const float norm = std::min(0.9f * s[c] * kNeutral[c] / 3.0f, 0.9f);
            cfa[size_t(y) * 64 + x] = uint16_t(kBlack + norm * (kWhite - kBlack) + 0.5f);
        }
    Grade a; a.highlightReconstruction = true;
    Grade b = a; b.highlightRecovery = true;
    const ImageF ra = developCpu(cfa.data(), info, meta, a), rb = developCpu(cfa.data(), info, meta, b);
    REQUIRE(ra.rgb.size() == rb.rgb.size());
    long diff = 0;
    for (size_t i = 0; i < ra.rgb.size(); ++i) diff += ra.rgb[i] != rb.rgb[i];
    CHECK(diff == 0);
}
