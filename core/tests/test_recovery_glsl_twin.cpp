// test_recovery_glsl_twin — I7 (H brief, 2026-11-27): the RECOVERY_COMPOSITE GLSL block's
// desktop numerical-twin test (research-recovery-glsl-twin, on top of D8's
// research-recovery-joint-estimate — RESEARCH BRANCH, never merged to main by the night agent).
//
// What is compared: the C++ twin (recovery_glsl_twin.hpp — the shader text translated
// line-by-line into float32 C++, the test_gpu_nodes_logic.cpp keep-in-sync pattern) against
// the CPU oracle rawedit::recoverClippedHighlights (core/src/Develop.cpp, the WLS joint
// estimate of commit c22d45c4) on IDENTICAL inputs: the same WB camera RGB and the same
// demosaiced clip mask developCpu feeds the oracle (the hr_joint_estimate fixture logic,
// D8's own measurement harness of this branch), plus the hr_equivalence direct patterns
// (blobs / random / checkers / all-clip / no-clip) so every branch of the shader runs —
// 1-channel, 2-channel joint, 2-channel fallback, 3-channel, and the untouched pass-through.
//
// The metric is the SHIPPED Recovery parity metric, verbatim: GlesPipeline.cpp's comparison
// expression (clamp [0,1], lround x 65535) with GpuParityRules' thresholds — maxDiffLsb <= 1
// and mismatch share <= 0.01% (mismatch * 10000 <= samples). NO LOOSENING: if the numbers sit
// at or over the wall, the test fails and the report carries the numbers (the brief's stop
// rule). Because the isolated recovery output exceeds 1.0 where the sensor clipped (the whole
// point of recovery), the clamp-domain metric is blind above 1.0 — the test therefore also
// reports (MESSAGE, and checks structurally) the agreement on the recovered channels in
// relative terms, and pins the bit-exact invariants both implementations share:
//   - untouched pixels (no clipped channel) are bit-identical,
//   - 3-clip pixels are the exact same float min (bit-identical),
//   - the twin never goes below the clipped input value (the oracle's own lower-bound rule).
//
// What this test CANNOT prove (device parity, the owner's morning sweep): the GPU's own
// transcendental precision, its FMA contraction, driver rounding. The twin models the shader
// SOURCE in plain IEEE float32 (-ffp-contract=off on this TU); the distance from the oracle
// measured here is the FORMULA-level float32-vs-double distance — the precondition the
// device sweep needs before it can attribute its own residual to the driver.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include "recovery_glsl_twin.hpp"

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

using namespace rawedit;

namespace {

// ---------- the shipped parity metric (GlesPipeline.cpp + GpuParityRules, verbatim) ----------

// GlesPipeline.cpp's comparison: clamp [0,1], then lround of the float product x 65535.
inline int quantize16(float v) {
    v = std::min(std::max(v, 0.0f), 1.0f);
    return static_cast<int>(std::lround(v * 65535.0f));
}

struct Parity {
    int maxDiffLsb = 0;
    long long mismatch = 0;
    long long samples = 0;
    long long over1 = 0;  // samples differing by MORE than 1 LSB (the device suite's report shape)
};

Parity parityOf(const std::vector<float>& twin, const std::vector<float>& oracle) {
    REQUIRE(twin.size() == oracle.size());
    Parity p;
    for (size_t i = 0; i < twin.size(); ++i) {
        const int d = std::abs(quantize16(twin[i]) - quantize16(oracle[i]));
        if (d > p.maxDiffLsb) p.maxDiffLsb = d;
        if (d > 0) ++p.mismatch;
        if (d > 1) ++p.over1;
        ++p.samples;
    }
    return p;
}

// GpuParityRules' shipped thresholds (PASS_MAX_LSB = 1, PASS_MISMATCH_PER_MYRIAD = 1:
// mismatch * 10000 <= samples, i.e. 0.01%). Same numbers, same direction, no loosening.
void checkShippedThresholds(const char* fixture, const Parity& p) {
    const double share = p.samples > 0 ? 100.0 * double(p.mismatch) / double(p.samples) : 0.0;
    std::printf("I7 %s: shipped-parity samples=%lld maxDiffLsb=%d mismatch=%lld (%.4f%%) over1=%lld\n",
                fixture, p.samples, p.maxDiffLsb, p.mismatch, share, p.over1);
    CHECK(p.maxDiffLsb <= 1);
    CHECK(p.mismatch * 10000 <= p.samples);
}

// ---------- the fixtures ----------

struct Rng {
    uint32_t s;
    uint32_t next() { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s; }
    float f() { return float(next()) / 4294967295.0f; }
};

// The M1 scene: test_highlight_recovery's own warm-ramp scene (the fixture D8's
// hr_joint_estimate measures with), rebuilt the way developCpu builds the oracle's inputs.
constexpr uint32_t kW = 256, kH = 192;
constexpr float kBlack = 64.0f, kWhite = 4095.0f;
constexpr float kNeutral[3] = {0.55f, 1.0f, 0.70f};  // as-shot neutral (sensor space)
constexpr float kRecoveryEps = 1e-4f;                 // the pair gate (kRecoveryEps, Develop.cpp)

void sceneRgb(uint32_t x, uint32_t y, float out[3]) {
    const float u = float(x) / float(kW - 1), v = float(y) / float(kH - 1);
    const float lum = 0.3f + 2.7f * u;
    const float warm = 1.0f + 0.08f * (1.0f - u) * (1.0f - v) - 0.8f * 0.08f * u * v;
    out[0] = lum * warm;
    out[1] = lum;
    out[2] = lum / warm;
}

int siteColor(uint32_t x, uint32_t y) {  // RGGB
    return ((y & 1) == 0) ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
}

// hr_joint_estimate's Scene: the clipped CFA, the measured clip levels (recoveryClipLevels,
// the same thresholds developCpu used), the pre-shading clip flags, then the demosaiced
// WB camera RGB + clip mask recoverClippedHighlights receives. Bit-identical input chain.
struct M1Scene {
    RecoveryClipLevels levels;
    ImageF img;    // WB camera RGB (demosaicBilinear of the clipped norm + the WB division)
    ImageF mask;   // demosaiced clip mask (0/1 sites through the same bilinear)
};

M1Scene makeM1() {
    M1Scene s;
    FrameInfo info;
    info.width = kW; info.height = kH; info.bitDepth = 16; info.cfa = CfaPattern::RGGB;
    info.activeArea = Rect{0, 0, int(kW), int(kH)};
    RawMeta meta;
    for (int i = 0; i < 4; ++i) meta.black[i] = uint16_t(kBlack);
    meta.white = uint16_t(kWhite);
    for (int c = 0; c < 3; ++c) { meta.asShotNeutral[c] = kNeutral[c]; meta.wbGains[c] = 1.0f / kNeutral[c]; }
    meta.colorMatrix[0] = meta.colorMatrix[4] = meta.colorMatrix[8] = 1;
    meta.forwardMatrix[0] = meta.forwardMatrix[4] = meta.forwardMatrix[8] = 1;
    std::vector<uint16_t> cfa(size_t(kW) * kH);
    std::vector<float> norm(size_t(kW) * kH);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            float sc[3];
            sceneRgb(x, y, sc);
            const int c = siteColor(x, y);
            const float nv = std::min(sc[c] * kNeutral[c], 1.0f);
            norm[size_t(y) * kW + x] = nv;
            cfa[size_t(y) * kW + x] = uint16_t(kBlack + nv * (kWhite - kBlack) + 0.5f);
        }
    s.levels = recoveryClipLevels(cfa.data(), info, meta);
    std::vector<float> maskCfa(size_t(kW) * kH);
    for (uint32_t y = 0; y < kH; ++y)
        for (uint32_t x = 0; x < kW; ++x) {
            const size_t i = size_t(y) * kW + x;
            const bool clipped = norm[i] >= s.levels.threshold[siteColor(x, y)];
            maskCfa[i] = clipped ? 1.0f : 0.0f;
        }
    s.img = demosaicBilinear(norm, kW, kH, CfaPattern::RGGB);
    s.mask = demosaicBilinear(maskCfa, kW, kH, CfaPattern::RGGB);
    const float inv[3] = {1.0f / kNeutral[0], 1.0f / kNeutral[1], 1.0f / kNeutral[2]};
    for (size_t i = 0; i < s.img.rgb.size(); i += 3) {
        s.img.rgb[i + 0] *= inv[0];
        s.img.rgb[i + 1] *= inv[1];
        s.img.rgb[i + 2] *= inv[2];
    }
    return s;
}

// hr_equivalence's buildDirect patterns (the byte-equality oracle's own fixture family,
// night A6): 0 ramp+three discs, 1 random blobs, 2 fully random values+mask, 3 per-channel
// checkers with exact 0.5 boundaries, 4 all clipped, 5 no clip. Every branch of the shader.
void buildDirect(int pattern, uint32_t w, uint32_t h, uint32_t seed, ImageF& img, ImageF& mask) {
    img.w = mask.w = w; img.h = mask.h = h;
    const size_t n = size_t(w) * h;
    img.rgb.assign(n * 3, 0.0f);
    mask.rgb.assign(n * 3, 0.0f);
    Rng rng{seed * 40503u + 7u};

    struct Blob { float cx, cy, rad, amp; bool cr, cg, cb; };
    std::vector<Blob> blobs;
    if (pattern == 0) {
        blobs.push_back({0.30f, 0.40f, 0.025f, 3.0f, true,  true,  false});
        blobs.push_back({0.72f, 0.65f, 0.050f, 2.5f, false, true,  true});
        blobs.push_back({0.85f, 0.18f, 0.090f, 2.0f, true,  false, true});
    } else if (pattern == 1) {
        Rng br{seed * 7u + 99u};
        for (int b = 0; b < 12; ++b) {
            const float u3 = br.f(), u4 = br.f();
            blobs.push_back({br.f(), br.f(), 0.02f + 0.10f * br.f(), 1.5f + 2.5f * u3 * u4,
                             u3 > 0.33f, br.f() > 0.25f, u4 > 0.33f});
        }
    }

    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float u = w > 1 ? float(x) / float(w - 1) : 0.0f;
            const float v = h > 1 ? float(y) / float(h - 1) : 0.0f;
            const size_t i = (size_t(y) * w + x) * 3;
            float s[3] = {0.3f, 0.3f, 0.3f};
            if (pattern == 0 || pattern == 1) {
                const float lum = 0.25f + 0.95f * u + 0.10f * v;
                const float warm = 1.0f + 0.12f * (1.0f - v);
                s[0] = lum * warm; s[1] = lum; s[2] = lum / warm;
                for (const Blob& b : blobs) {
                    const float dx = u - b.cx, dy = v - b.cy;
                    const float amp = b.amp * std::exp(-(dx * dx + dy * dy) / (2.0f * b.rad * b.rad));
                    s[0] += amp * (b.cr ? 1.0f : 0.4f);
                    s[1] += amp * (b.cg ? 1.0f : 0.4f);
                    s[2] += amp * (b.cb ? 1.0f : 0.4f);
                }
            } else if (pattern == 2) {
                for (int c = 0; c < 3; ++c) s[c] = -0.02f + 1.7f * rng.f();
            } else if (pattern == 3) {
                const float lum = 0.25f + 0.95f * u;
                s[0] = lum * 1.6f; s[1] = lum * 1.6f; s[2] = lum;
            } else if (pattern == 4) {
                for (int c = 0; c < 3; ++c) s[c] = 0.9f + 0.4f * rng.f();
            } else if (pattern == 5) {
                for (int c = 0; c < 3; ++c) s[c] = 0.10f + 0.30f * u;
            }
            for (int c = 0; c < 3; ++c) s[c] += (rng.f() - 0.5f) * 0.03f;
            // the exact-eps band of the A6 fixture (a kEps reference must not count as support)
            if ((pattern == 0 && y == h / 7) || (pattern == 1 && (y % 37) == 0)) {
                s[1] = kRecoveryEps;
                if ((x & 3) == 0) s[0] = kRecoveryEps * 1.000001f;
                if ((x & 3) == 1) s[2] = kRecoveryEps * 0.999999f;
            }
            img.rgb[i] = s[0]; img.rgb[i + 1] = s[1]; img.rgb[i + 2] = s[2];

            float m[3] = {0.0f, 0.0f, 0.0f};
            if (pattern == 2) {
                const float q[5] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
                for (int c = 0; c < 3; ++c) m[c] = q[rng.next() % 5];
            } else if (pattern == 3) {  // per-channel checkers: 1-2 channel pixels + exact 0.5
                const uint32_t ckb = ((x / 8) + (y / 8)) & 1;
                const uint32_t ck2 = ((x / 16) + (y / 16)) & 1;
                const bool border = x < 3 || y < 3 || x + 3 >= w || y + 3 >= h;
                m[0] = border ? 1.0f : (ckb ? 1.0f : 0.5f);           // R at the >= 0.5 boundary
                m[1] = (border || ck2 == 0) ? 1.0f : 0.0f;            // G coarse checker
                m[2] = 0.0f;                                          // B unclipped ...
                if (y == h / 3 || x == w / 2) m[2] = 0.5f;            // ... except the boundary band
            } else if (pattern == 4) {
                for (int c = 0; c < 3; ++c) m[c] = 1.0f;
            } else if (pattern == 0 || pattern == 1) {
                for (int c = 0; c < 3; ++c) {
                    const float val = s[c];
                    m[c] = val >= 1.05f ? 1.0f : val >= 0.98f ? 0.75f
                         : val >= 0.94f ? 0.5f : val >= 0.88f ? 0.25f : 0.0f;
                }
                for (const Blob& b : blobs) {
                    const float dx = u - b.cx, dy = v - b.cy;
                    const float d2 = dx * dx + dy * dy;
                    if (d2 < b.rad * b.rad) {
                        const float core = d2 < 0.25f * b.rad * b.rad ? 1.0f : 0.5f;
                        if (b.cr) m[0] = core;
                        if (b.cg) m[1] = core;
                        if (b.cb) m[2] = core;
                    }
                }
                if (pattern == 0 && y == h / 5) { m[0] = m[1] = m[2] = 0.5f; }  // exact-boundary row
            }
            mask.rgb[i] = m[0]; mask.rgb[i + 1] = m[1]; mask.rgb[i + 2] = m[2];
        }
}

// ---------- the run pair: oracle + twin on identical inputs ----------

struct RunPair {
    std::vector<float> oracle;
    std::vector<float> twin;
    std::vector<uint8_t> bits;    // the twin's clip-bits input (the shader's alpha channel)
    uint32_t w = 0, h = 0;
    // per-pixel clip counts of the input (the branch census)
    long n0 = 0, n1 = 0, n2 = 0, n3 = 0;
    long jointFired = 0;          // twin: 2-clip pixels the joint path estimated together
    long fallback = 0;             // twin: 2-clip pixels that fell back to the independent loop
};

// The oracle with the SHIPPED recovery params developCpu passes (kRecoveryHeadroom = 4,
// kRecoveryMinRefFrac = 0; refLevel feeds only the stats, not collected here).
RunPair runBoth(const ImageF& img, const ImageF& mask) {
    RunPair rp;
    rp.w = img.w; rp.h = img.h;
    const size_t n = size_t(img.w) * img.h;
    rp.bits.assign(n, 0);
    for (size_t i = 0; i < n; ++i) {
        const bool c0 = mask.rgb[i * 3 + 0] >= 0.5f;
        const bool c1 = mask.rgb[i * 3 + 1] >= 0.5f;
        const bool c2 = mask.rgb[i * 3 + 2] >= 0.5f;
        rp.bits[i] = uint8_t((c0 ? 1 : 0) | (c1 ? 2 : 0) | (c2 ? 4 : 0));
        const int nc = (c0 ? 1 : 0) + (c1 ? 1 : 0) + (c2 ? 1 : 0);
        if (nc == 0) ++rp.n0; else if (nc == 1) ++rp.n1; else if (nc == 2) ++rp.n2; else ++rp.n3;
    }
    ImageF oracleImg = img;
    RecoveryParams shipped;
    shipped.headroom = 4.0f;  // kRecoveryHeadroom (Develop.cpp); minRefFrac ships 0 = off
    recoverClippedHighlights(oracleImg, mask, shipped, nullptr);
    rp.oracle = oracleImg.rgb;

    // the twin: the joint-path census (which 2-clip pixels all three ratios served)
    const glslTwin::Pyramid py = glslTwin::buildPyramid(img.rgb.data(), rp.bits.data(), img.w, img.h);
    rp.twin.assign(n * 3, 0.0f);
    glslTwin::recoverComposite(py, img.rgb.data(), rp.bits.data(), img.w, img.h, rp.twin.data());
    for (size_t i = 0; i < n; ++i) {
        const uint8_t b = rp.bits[i];
        const int nc = (b & 1 ? 1 : 0) + (b & 2 ? 1 : 0) + (b & 4 ? 1 : 0);
        if (nc != 2) continue;
        const bool cf0 = (b & 1) != 0, cf1 = (b & 2) != 0, cf2 = (b & 4) != 0;
        int r;
        if (!cf1) r = 1;
        else r = (img.rgb[i * 3] >= img.rgb[i * 3 + 2]) ? (cf0 ? 2 : 0) : (cf2 ? 0 : 2);
        const int c1 = cf0 ? 0 : 1, c2 = cf2 ? 2 : 1;
        const glslTwin::Vec2 rr1 = glslTwin::ratioForW(py, img.rgb.data(), rp.bits.data(),
                                                       img.w, img.h, int(i % img.w), int(i / img.w), c1, r);
        const glslTwin::Vec2 rr2 = glslTwin::ratioForW(py, img.rgb.data(), rp.bits.data(),
                                                       img.w, img.h, int(i % img.w), int(i / img.w), c2, r);
        const glslTwin::Vec2 rr12 = glslTwin::ratioForW(py, img.rgb.data(), rp.bits.data(),
                                                        img.w, img.h, int(i % img.w), int(i / img.w), c1, c2);
        if (rr1.x > 0.0f && rr2.x > 0.0f && rr12.x > 0.0f) ++rp.jointFired;
        else ++rp.fallback;
    }
    return rp;
}

// The relative-agreement report on the recovered region (the channels the twin changed vs
// the input): worst/mean |twin - oracle| / |oracle|, and how many sit beyond one 16-bit
// step (1/65535) — the clamp-domain metric is blind above 1.0, this is the honest companion.
struct RelStats { long n = 0; double worst = 0, mean = 0; long beyond1Lsb = 0; };
RelStats relAgreement(const RunPair& rp, const ImageF& input) {
    RelStats st;
    const size_t n = size_t(rp.w) * rp.h;
    for (size_t i = 0; i < n; ++i)
        for (int c = 0; c < 3; ++c) {
            const size_t k = i * 3 + c;
            if (rp.twin[k] == input.rgb[k]) continue;  // untouched by both (bit-equal there)
            const double o = rp.oracle[k];
            const double rel = std::fabs(double(rp.twin[k]) - o) / std::max(std::fabs(o), 1e-9);
            st.worst = std::max(st.worst, rel);
            st.mean += rel;
            if (rel > 1.0 / 65535.0) ++st.beyond1Lsb;
            ++st.n;
        }
    if (st.n) st.mean /= st.n;
    return st;
}

}  // namespace

TEST_CASE("twin_vs_oracle_m1_scene_shipped_parity_thresholds") {
    const M1Scene s = makeM1();
    const RunPair rp = runBoth(s.img, s.mask);
    std::printf("I7 m1-scene 256x192: clip census n0=%ld n1=%ld n2=%ld n3=%ld | twin joint=%ld fallback=%ld\n",
                rp.n0, rp.n1, rp.n2, rp.n3, rp.jointFired, rp.fallback);
    REQUIRE(rp.n2 > 100);   // the joint path must actually run on this fixture
    REQUIRE(rp.n1 > 100);
    const Parity p = parityOf(rp.twin, rp.oracle);
    checkShippedThresholds("m1-scene", p);
    const RelStats rel = relAgreement(rp, s.img);
    std::printf("I7 m1-scene: recovered channel-samples=%ld rel|twin-oracle| worst=%.3e mean=%.3e beyond 1/65535: %ld (%.4f%%)\n",
                rel.n, rel.worst, rel.mean, rel.beyond1Lsb,
                rel.n > 0 ? 100.0 * double(rel.beyond1Lsb) / double(rel.n) : 0.0);
    // The joint path must have carried the overwhelming majority of the 2-clip band
    // (D8 measured rho12's own support as non-zero across the whole band).
    CHECK(rp.jointFired > rp.fallback);
}

TEST_CASE("twin_vs_oracle_direct_fixtures_every_branch") {
    struct Case { const char* name; int pattern; uint32_t w, h, seed; };
    const Case cases[] = {
        {"d-ramp-64x48", 0, 64, 48, 1},
        {"d-blobs-129x83", 1, 129, 83, 4},
        {"d-random-101x67", 2, 101, 67, 5},
        {"d-checker-256x192", 3, 256, 192, 12},
        {"d-allclip-64x48", 4, 64, 48, 13},
        {"d-noclip-33x17", 5, 33, 17, 14},
    };
    for (const Case& cs : cases) {
        ImageF img, mask;
        buildDirect(cs.pattern, cs.w, cs.h, cs.seed, img, mask);
        const RunPair rp = runBoth(img, mask);
        std::printf("I7 %s: clip census n0=%ld n1=%ld n2=%ld n3=%ld | twin joint=%ld fallback=%ld\n",
                    cs.name, rp.n0, rp.n1, rp.n2, rp.n3, rp.jointFired, rp.fallback);
        const Parity p = parityOf(rp.twin, rp.oracle);
        checkShippedThresholds(cs.name, p);
        const RelStats rel = relAgreement(rp, img);
        std::printf("I7 %s: recovered channel-samples=%ld rel|twin-oracle| worst=%.3e mean=%.3e beyond 1/65535: %ld (%.4f%%)\n",
                    cs.name, rel.n, rel.worst, rel.mean, rel.beyond1Lsb,
                    rel.n > 0 ? 100.0 * double(rel.beyond1Lsb) / double(rel.n) : 0.0);
        if (cs.pattern == 2 || cs.pattern == 1)
            CHECK(rp.jointFired + rp.fallback > 0);  // 2-clip pixels exist on these patterns
    }
}

TEST_CASE("twin_bit_exact_on_unrecovered_and_allclip_pixels") {
    // The shared invariants: no clipped channel -> the pixel passes through both
    // implementations untouched (bit-identical to the input AND to each other);
    // all three clipped -> the exact same float min (bit-identical); and in the
    // 1-2 channel region the twin never outputs below the clipped input value on a
    // CLIPPED channel (the oracle's own lower-bound rule — the M1 test measures the
    // same). The all-clip neutral cap deliberately lowers channels to their minimum:
    // that is the no-information rule both implementations share, not a violation,
    // so it is counted nowhere.
    const M1Scene s = makeM1();
    const RunPair rp = runBoth(s.img, s.mask);
    long diff0 = 0, diff3 = 0, below = 0;
    const size_t n = size_t(rp.w) * rp.h;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t b = rp.bits[i];
        const int nc = (b & 1 ? 1 : 0) + (b & 2 ? 1 : 0) + (b & 4 ? 1 : 0);
        for (int c = 0; c < 3; ++c) {
            const size_t k = i * 3 + c;
            if (nc == 0 && (rp.twin[k] != s.img.rgb[k] || rp.oracle[k] != s.img.rgb[k])) ++diff0;
            if (nc == 3 && rp.twin[k] != rp.oracle[k]) ++diff3;
            if ((nc == 1 || nc == 2) && (b & (1 << c)) && rp.twin[k] < s.img.rgb[k]) ++below;
        }
    }
    std::printf("I7 bit-exactness: untouched-pixel mismatches=%ld allclip mismatches=%ld below-clipped=%ld\n",
                diff0, diff3, below);
    CHECK(diff0 == 0);
    CHECK(diff3 == 0);
    CHECK(below == 0);
}

TEST_CASE("twin_is_a_bit_exact_no_op_when_nothing_clips") {
    // The no-clip control (hr_equivalence's d12 pattern): both implementations leave
    // every pixel bit-identical to the input, and to each other.
    ImageF img, mask;
    buildDirect(5, 33, 17, 14, img, mask);
    const RunPair rp = runBoth(img, mask);
    REQUIRE(rp.n0 == long(img.w) * long(img.h));
    long diff = 0;
    for (size_t k = 0; k < rp.twin.size(); ++k) {
        if (rp.twin[k] != img.rgb[k]) ++diff;
        if (rp.oracle[k] != img.rgb[k]) ++diff;
        if (rp.twin[k] != rp.oracle[k]) ++diff;
    }
    std::printf("I7 no-clip control: mismatches=%ld\n", diff);
    CHECK(diff == 0);
}
