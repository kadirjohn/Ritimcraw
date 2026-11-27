// recovery_glsl_twin.hpp — I7 (H brief, 2026-11-27): the line-by-line C++ twin of the
// highlight-recovery GLSL passes (GlesShaderSources.RECOVERY_BLOCK0 / RECOVERY_BLOCK_UP /
// RECOVERY_COMPOSITE, the joint-estimate block written by D8 in commit 2b2da0dc).
// RESEARCH BRANCH ONLY (research-recovery-glsl-twin, on top of
// research-recovery-joint-estimate — never merged to main by the night agent).
//
// What this is NOT: it is not recoverClippedHighlights and not a copy of it. Every
// expression below is a translation of the SHADER's text, in the shader's own float32
// precision and statement order:
//   - pairTerm: RECOVERY_COMPOSITE's pair statistic (shared by BLOCK0/BLOCK_UP text);
//   - the block pyramid: RECOVERY_BLOCK0 (16 taps, 4px blocks) and RECOVERY_BLOCK_UP
//     (uFactor x uFactor children: 2 for 4->8, then 4 for ->32 ->128 ->512), each in the
//     GLSL's y-major accumulation order;
//   - nbSum / ratioForW: RECOVERY_COMPOSITE's neighbourhood sum and the 5-scale ratio
//     WITH the window of the supplying scale (9x9, 8/32/128/512 — the exact GLSL
//     threshold literals);
//   - the joint two-channel estimate: the WLS fit in log space, weights 1/window of
//     each ratio's scale — the CPU oracle's D8 formula, here in the GLSL's float32
//     arithmetic — and the independent fallback loop.
// The twin is compiled with -ffp-contract=off (tools/build-desktop.sh line; Yuv.cpp /
// Loudness.cpp precedent) so the float32 chain is plain IEEE — no desktop FMA
// contraction. The DEVICE GPU may still contract or round its transcendentals
// differently: that residual difference is exactly what the owner's on-device parity
// sweep must measure. THIS file prepares that sweep's precondition: it measures the
// formula-level float32-vs-double distance against the CPU oracle on identical inputs,
// under the shipped Recovery parity thresholds (maxDiffLsb <= 1, mismatch <= 0.01%).
//
// Keep-in-sync rule (the test_gpu_nodes_logic.cpp rule): if the shader text changes,
// this file must change with it, and GlesRecoveryTwinTextTest.kt pins the shared formula
// lines token-by-token so the two cannot drift silently.
//
// GPU twin device parity: NOT VERIFIED (no phone at night; the owner's morning sweep).

#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

namespace rawedit {
namespace glslTwin {

// GLSL vec2/vec3 with the shader's .x/.y/.z field names (the text-parity test matches
// the shader's field-access tokens verbatim).
struct Vec2 { float x, y; };
struct Vec3 { float x, y, z; };

// The ordered-pair index of (c, r): p = c*2 + (r > c ? r-1 : r) — the GLSL's layer
// formula (RECOVERY_BLOCK0's main and RECOVERY_COMPOSITE's ratioForW both derive it).
inline int pairLayer(int c, int r) { return c * 2 + (r > c ? r - 1 : r); }

// The pair statistic of one pixel — RECOVERY_COMPOSITE's pairTerm, verbatim:
//   bool ok = (bits & mc) == 0 && (bits & mr) == 0 && vr > 1e-4 && vc >= 0.0;
//   return ok ? vec3(1.0, vc, vr) : vec3(0.0);
// vc/vr are picked by the shader's dot(t.rgb, sc) — reproduced as the same ordered
// float32 multiply-add chain (one-hot selectors: x*s0 + y*s1 + z*s2).
inline Vec3 pairTerm(const float v[3], uint8_t bits, int mc, int mr,
                     const float sc[3], const float sr[3]) {
    const float vc = v[0] * sc[0] + v[1] * sc[1] + v[2] * sc[2];
    const float vr = v[0] * sr[0] + v[1] * sr[1] + v[2] * sr[2];
    const bool ok = (bits & mc) == 0 && (bits & mr) == 0 && vr > 1e-4f && vc >= 0.0f;
    return ok ? Vec3{1.0f, vc, vr} : Vec3{0.0f, 0.0f, 0.0f};
}

// The block pyramid — RECOVERY_BLOCK0 + RECOVERY_BLOCK_UP's texture contents:
// level 0: 4px block sums (grid ceil(w/4) x ceil(h/4)); levels 1..4: 8/32/128/512px
// blocks. Every cell holds (W, A, B) of the ordered pair layer p = pairLayer(c, r)
// (6 layers; the GPU draws them as two 3-pair MRT passes — the VALUES per layer are
// identical either way, the draw split is a GL runtime detail).
struct Pyramid {
    uint32_t w = 0, h = 0;
    int gridW[5] = {0, 0, 0, 0, 0}, gridH[5] = {0, 0, 0, 0, 0};
    // level[k][((by * gridW + bx) * 6 + layer) * 3 + {0=W, 1=A, 2=B}]
    std::vector<float> level[5];
    float* cell(int k, int bx, int by, int layer) {
        return &level[k][(size_t(by) * gridW[k] + bx) * 6 * 3 + size_t(layer) * 3];
    }
    const float* cell(int k, int bx, int by, int layer) const {
        return &level[k][(size_t(by) * gridW[k] + bx) * 6 * 3 + size_t(layer) * 3];
    }
};

// The one-hot selectors of channel ch (the GLSL's vec3(float(c == 0), ...) pattern).
inline void oneHot(int ch, float s[3]) { s[0] = float(ch == 0); s[1] = float(ch == 1); s[2] = float(ch == 2); }

// RECOVERY_BLOCK0's main, every 4px block: 16 taps in the GLSL's y-major order, the
// out-of-frame taps weighted 0 (inb), the fetch coordinate clamped (min(p, uSize - 1)).
inline void block0Sums(const float* img, const uint8_t* bitsArr, uint32_t w, uint32_t h, Pyramid* py) {
    const int gw = py->gridW[0], gh = py->gridH[0];
    py->level[0].assign(size_t(gw) * gh * 6 * 3, 0.0f);
    int mc[6], mr[6];
    float sc[6][3], sr[6][3];
    for (int p = 0; p < 6; ++p) {  // p = c*2 + (r > c ? r-1 : r), derived exactly as the shader does
        const int c = p / 2, q = p - c * 2;
        const int r = q >= c ? q + 1 : q;
        mc[p] = 1 << c;
        mr[p] = 1 << r;
        oneHot(c, sc[p]);
        oneHot(r, sr[p]);
    }
    for (int by = 0; by < gh; ++by)
        for (int bx = 0; bx < gw; ++bx) {
            Vec3 s[6] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
            const int ox = bx * 4, oy = by * 4;  // ivec2 o = b * 4;
            for (int y = 0; y < 4; ++y)
                for (int x = 0; x < 4; ++x) {
                    int px = ox + x, pyy = oy + y;
                    const float inb = (px < int(w) && pyy < int(h)) ? 1.0f : 0.0f;
                    // texelFetch(uSrc, min(p, uSize - 1), 0) — the clamped fetch.
                    px = px >= int(w) ? int(w) - 1 : px;
                    pyy = pyy >= int(h) ? int(h) - 1 : pyy;
                    const size_t i = (size_t(pyy) * w + px) * 3;
                    for (int p = 0; p < 6; ++p) {
                        const Vec3 t = pairTerm(&img[i], bitsArr[i / 3], mc[p], mr[p], sc[p], sr[p]);
                        s[p].x += inb * t.x;
                        s[p].y += inb * t.y;
                        s[p].z += inb * t.z;
                    }
                }
            for (int p = 0; p < 6; ++p) {
                float* dst = py->cell(0, bx, by, p);
                dst[0] = s[p].x; dst[1] = s[p].y; dst[2] = s[p].z;
            }
        }
}

// RECOVERY_BLOCK_UP's sumLayer + main, every block of level k: uFactor x uFactor
// children (2: 4->8, 4: 8->32->128->512), y-major, the out-of-grid children weighted 0
// and skipped (the shader clamps their fetch coordinates; a 0-weighted fetch cannot
// change the sum).
inline void blockUpSums(const Pyramid& py, int k, int uFactor, Pyramid* out) {
    const int pw = py.gridW[k - 1], ph = py.gridH[k - 1];
    const int gw = out->gridW[k], gh = out->gridH[k];
    out->level[k].assign(size_t(gw) * gh * 6 * 3, 0.0f);
    for (int by = 0; by < gh; ++by)
        for (int bx = 0; bx < gw; ++bx) {
            Vec3 s[6] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
            const int ox = bx * uFactor, oy = by * uFactor;  // ivec2 o = ivec2(gl_FragCoord.xy) * uFactor;
            for (int y = 0; y < 4; ++y)
                for (int x = 0; x < 4; ++x) {
                    int px = ox + x, pyy = oy + y;
                    const float wgt = (x < uFactor && y < uFactor && px < pw && pyy < ph) ? 1.0f : 0.0f;
                    // texelFetch(uPrev, ivec3(min(p, uPrevSize - 1), layer), 0) — the clamped
                    // fetch ALWAYS happens (a 0-weighted fetch contributes 0.0 to the float sum;
                    // kept faithful to the shader text rather than skipped).
                    px = px >= pw ? pw - 1 : px;
                    pyy = pyy >= ph ? ph - 1 : pyy;
                    for (int p = 0; p < 6; ++p) {
                        const float* t = py.cell(k - 1, px, pyy, p);
                        s[p].x += wgt * t[0];
                        s[p].y += wgt * t[1];
                        s[p].z += wgt * t[2];
                    }
                }
            for (int p = 0; p < 6; ++p) {
                float* dst = out->cell(k, bx, by, p);
                dst[0] = s[p].x; dst[1] = s[p].y; dst[2] = s[p].z;
            }
        }
}

// Builds the whole pyramid the way GpuDevelopPipeline::recoveryDraw does: level 0 at
// 4px, then 8 (uFactor 2), then 32/128/512 (uFactor 4).
inline Pyramid buildPyramid(const float* img, const uint8_t* bitsArr, uint32_t w, uint32_t h) {
    Pyramid py;
    py.w = w; py.h = h;
    int div = 4;
    for (int k = 0; k < 5; ++k, div *= (k == 1 ? 2 : 4)) {
        py.gridW[k] = (int(w) + div - 1) / div;
        py.gridH[k] = (int(h) + div - 1) / div;
    }
    block0Sums(img, bitsArr, w, h, &py);
    blockUpSums(py, 1, 2, &py);
    blockUpSums(py, 2, 4, &py);
    blockUpSums(py, 3, 4, &py);
    blockUpSums(py, 4, 4, &py);
    return py;
}

// RECOVERY_COMPOSITE's nbSum: the 3x3 block-neighbourhood sum around pixel g's block,
// clipped to the grid, in the shader's dy-then-dx order.
inline Vec3 nbSum(const Pyramid& py, int k, int bx, int by, int layer) {
    const int gw = py.gridW[k], gh = py.gridH[k];
    Vec3 a{0.0f, 0.0f, 0.0f};
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            const int qx = bx + dx, qy = by + dy;
            if (qx < 0 || qy < 0 || qx >= gw || qy >= gh) continue;
            const float* t = py.cell(k, qx, qy, layer);
            a.x += t[0]; a.y += t[1]; a.z += t[2];
        }
    return a;
}

// RECOVERY_COMPOSITE's ratioForW: the ratio v_c / v_r near g at the finest scale with
// >= 5% support (the exact GLSL threshold literals), WITH the window of the supplying
// scale (9x9, 8/32/128/512). Returns (ratio, window), or (-1, 0) when no scale has
// support. `ratioFor` in the shader is this function's .x — kept separate so the
// fallback loop's text matches the shader's own helper split.
inline Vec2 ratioForW(const Pyramid& py, const float* img, const uint8_t* bitsArr,
                      uint32_t w, uint32_t h, int px, int pyy, int c, int r) {
    const int layer = pairLayer(c, r);
    const int mc = 1 << c;
    const int mr = 1 << r;
    float sc[3], sr[3];
    oneHot(c, sc);
    oneHot(r, sr);
    Vec3 s{0.0f, 0.0f, 0.0f};
    for (int dy = -4; dy <= 4; ++dy)
        for (int dx = -4; dx <= 4; ++dx) {
            const int qx = px + dx, qy = pyy + dy;
            if (qx < 0 || qy < 0 || qx >= int(w) || qy >= int(h)) continue;
            const size_t i = (size_t(qy) * w + qx) * 3;
            const Vec3 t = pairTerm(&img[i], bitsArr[i / 3], mc, mr, sc, sr);
            s.x += t.x; s.y += t.y; s.z += t.z;
        }
    if (s.x >= 4.05f && s.z > 0.0f) return Vec2{s.y / s.z, 9.0f};
    s = nbSum(py, 1, px / 8, pyy / 8, layer);
    if (s.x >= 28.8f && s.z > 0.0f) return Vec2{s.y / s.z, 8.0f};
    s = nbSum(py, 2, px / 32, pyy / 32, layer);
    if (s.x >= 460.8f && s.z > 0.0f) return Vec2{s.y / s.z, 32.0f};
    s = nbSum(py, 3, px / 128, pyy / 128, layer);
    if (s.x >= 7372.8f && s.z > 0.0f) return Vec2{s.y / s.z, 128.0f};
    s = nbSum(py, 4, px / 512, pyy / 512, layer);
    if (s.x >= 117964.8f && s.z > 0.0f) return Vec2{s.y / s.z, 512.0f};
    return Vec2{-1.0f, 0.0f};
}

// RECOVERY_COMPOSITE's main, the whole frame (uHasProfile = 0: the camera matrix is the
// develop chain's stage 6, outside recoverClippedHighlights' contract, and the CPU
// oracle carries none — so the desktop comparison domain is the pre-matrix recovery
// output). Writes the recovered RGB into `out` (w*h*3 floats).
inline void recoverComposite(const Pyramid& py, const float* img, const uint8_t* bitsArr,
                             uint32_t w, uint32_t h, float* out) {
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const size_t i = (size_t(y) * w + x) * 3;
            float v[3] = {img[i], img[i + 1], img[i + 2]};
            const uint8_t bits = bitsArr[i / 3];
            const bool cf[3] = {(bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0};
            const int nc = int(cf[0]) + int(cf[1]) + int(cf[2]);
            if (nc == 3) {
                const float m = std::min(v[0], std::min(v[1], v[2]));
                v[0] = v[1] = v[2] = m;  // no information: neutral at the lowest level, nothing invented
            } else if (nc > 0) {
                int r;
                if (!cf[1]) r = 1;
                else r = (v[0] >= v[2]) ? (cf[0] ? 2 : 0) : (cf[2] ? 0 : 2);
                const float vr = v[r];
                float o[3] = {v[0], v[1], v[2]};
                bool joint = false;
                Vec2 jointE{0.0f, 0.0f};
                if (nc == 2) {
                    // Joint two-channel estimate (the CPU's research-recovery-joint-estimate branch):
                    // the two clipped channels are estimated together - the two independent ratios and
                    // the pair's own ratio c1/c2 reconciled by a weighted least-squares fit in log
                    // space, each weighted 1/window of its scale (a finer scale's ratio is localized;
                    // a coarse block-scale mean carries scene drift as bias). Falls back to the
                    // independent loop when any of the three ratios is unavailable.
                    const int c1 = cf[0] ? 0 : 1;
                    const int c2 = cf[2] ? 2 : 1;
                    const Vec2 r1 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c1, r);
                    const Vec2 r2 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c2, r);
                    const Vec2 r12 = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c1, c2);
                    if (r1.x > 0.0f && r2.x > 0.0f && r12.x > 0.0f) {
                        const float w1 = 1.0f / r1.y, w2 = 1.0f / r2.y, w3 = 1.0f / r12.y;
                        const float D = w1 * w2 + w1 * w3 + w2 * w3;
                        const float a1 = std::log(vr * r1.x);
                        const float a2 = std::log(vr * r2.x);
                        const float a12 = std::log(r12.x);
                        const float x1 = (w1 * (w2 + w3) * a1 + w2 * w3 * (a2 + a12)) / D;
                        const float x2 = (w1 * w3 * (a1 - a12) + w2 * (w1 + w3) * a2) / D;
                        joint = true;
                        jointE = Vec2{std::exp(x1), std::exp(x2)};
                    }
                }
                if (joint) {
                    const float est[2] = {jointE.x, jointE.y};
                    const int chan[2] = {cf[0] ? 0 : 1, cf[2] ? 2 : 1};
                    for (int k = 0; k < 2; ++k) {
                        const int c = chan[k];
                        float e = std::max(v[c], est[k]);  // the clipped value is a lower bound
                        if (v[c] > 0.0f) e = std::min(e, 4.0f * v[c]);  // headroom cap K = 4 (CPU kRecoveryHeadroom)
                        o[c] = e;
                    }
                } else {
                    for (int c = 0; c < 3; ++c) {
                        if (!cf[c]) continue;
                        const float rho = ratioForW(py, img, bitsArr, w, h, int(x), int(y), c, r).x;
                        if (rho > 0.0f) {
                            float e = std::max(v[c], vr * rho);  // the clipped value is a lower bound
                            if (v[c] > 0.0f) e = std::min(e, 4.0f * v[c]);  // headroom cap K = 4 (CPU kRecoveryHeadroom)
                            o[c] = e;
                        }
                    }
                }
                v[0] = o[0]; v[1] = o[1]; v[2] = o[2];
            }
            out[i] = v[0]; out[i + 1] = v[1]; out[i + 2] = v[2];
        }
}

// Convenience: the full twin pass (pyramid + composite) on flat buffers.
inline std::vector<float> recoverGlslTwin(const float* img, const uint8_t* bitsArr,
                                          uint32_t w, uint32_t h) {
    const Pyramid py = buildPyramid(img, bitsArr, w, h);
    std::vector<float> out(size_t(w) * h * 3, 0.0f);
    recoverComposite(py, img, bitsArr, w, h, out.data());
    return out;
}

}  // namespace glslTwin
}  // namespace rawedit
