#include <rawedit/Develop.h>

#include <algorithm>
#include <cmath>
#include <rawedit/Lut.h>

namespace rawedit {

namespace {

// Site color codes within the repeating 2x2 CFA cell: 0 = R, 1 = G, 2 = B.
// cell[cellY][cellX] gives the color of the site at (even+cellX, even+cellY).
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

// blackLevel[4] and the shading map planes are indexed by 2x2 cell position
// (cellY*2 + cellX), matching the container's 2x2 (r, g, g, b) ordering.
inline int cellSlot(int cellY, int cellX) { return cellY * 2 + cellX; }

// wbGains index for a site color: 0 = R, 1 = G, 2 = B.
inline int wbIndex(int siteColor) { return siteColor; }

// ---- 3x3 row-major matrix helpers for the DNG color pipeline. ----
struct Mat3 {
    float m[9];
};

Mat3 mul3(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            float s = 0.0f;
            for (int k = 0; k < 3; ++k)
                s += a.m[i * 3 + k] * b.m[k * 3 + j];
            r.m[i * 3 + j] = s;
        }
    return r;
}

// Invert a 3x3 (cofactor expansion). Returns identity when singular.
Mat3 inv3(const Mat3& a) {
    const float* m = a.m;
    const float det =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::abs(det) < 1e-12f)
        return Mat3{{1,0,0, 0,1,0, 0,0,1}};
    const float inv = 1.0f / det;
    Mat3 r{};
    r.m[0] =  (m[4] * m[8] - m[5] * m[7]) * inv;
    r.m[1] = -(m[1] * m[8] - m[2] * m[7]) * inv;
    r.m[2] =  (m[1] * m[5] - m[2] * m[4]) * inv;
    r.m[3] = -(m[3] * m[8] - m[5] * m[6]) * inv;
    r.m[4] =  (m[0] * m[8] - m[2] * m[6]) * inv;
    r.m[5] = -(m[0] * m[5] - m[2] * m[3]) * inv;
    r.m[6] =  (m[3] * m[7] - m[4] * m[6]) * inv;
    r.m[7] = -(m[0] * m[7] - m[1] * m[6]) * inv;
    r.m[8] =  (m[0] * m[4] - m[1] * m[3]) * inv;
    return r;
}

// XYZ (D50) -> linear Rec.709 (D65) via Bradford chromatic adaptation.
// Fixed matrices: Bradford cone response, D50/D65 white points, and the
// sRGB/Rec.709 XYZ(D65)->RGB matrix (IEC 61966-2-1).
Mat3 xyzD50ToRec709() {
    static const float bradford[9] = {
        0.8951f,  0.2664f, -0.1614f,
       -0.7502f,  1.7135f,  0.0367f,
        0.0389f, -0.0685f,  1.0296f,
    };
    const float wpD50[3] = {0.9642f, 1.0f, 0.8249f};
    const float wpD65[3] = {0.9505f, 1.0f, 1.0890f};

    float srcCone[3], dstCone[3], adapt[9] = {1,0,0, 0,1,0, 0,0,1};
    for (int i = 0; i < 3; ++i) {
        srcCone[i] = bradford[i*3+0]*wpD50[0] + bradford[i*3+1]*wpD50[1] + bradford[i*3+2]*wpD50[2];
        dstCone[i] = bradford[i*3+0]*wpD65[0] + bradford[i*3+1]*wpD65[1] + bradford[i*3+2]*wpD65[2];
    }
    // D = diag(dstCone/srcCone) folded into Bradford: M = B^-1 * D * B
    Mat3 B{}; for (int i = 0; i < 9; ++i) B.m[i] = bradford[i];
    Mat3 D{}; D.m[0]=dstCone[0]/srcCone[0]; D.m[4]=dstCone[1]/srcCone[1]; D.m[8]=dstCone[2]/srcCone[2];
    const Mat3 adaptM = mul3(inv3(B), mul3(D, B));

    static const float xyz2rgb[9] = {
        3.2406f, -1.5372f, -0.4986f,
       -0.9689f,  1.8758f,  0.0415f,
        0.0557f, -0.2040f,  1.0570f,
    };
    Mat3 RGB{}; for (int i = 0; i < 9; ++i) RGB.m[i] = xyz2rgb[i];
    return mul3(RGB, adaptM);
}

bool isIdentity3(const float* m) {
    return m[0] == 1 && m[4] == 1 && m[8] == 1
        && m[1] == 0 && m[2] == 0 && m[3] == 0
        && m[5] == 0 && m[6] == 0 && m[7] == 0;
}

// Hermite smoothstep with clamped edges: 0 for x <= e0, 1 for x >= e1.
// The tone stages blend their endpoint remaps in with this so each control
// stays inside its tonal region.
inline float smoothstepf(float e0, float e1, float x) {
    const float t = std::min(std::max((x - e0) / (e1 - e0), 0.0f), 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// ---- Detail controls (COL-15): luminance-only unsharp mask. ----
// Both Sharpness (stage 19) and Detail (stage 20) run the same operator at
// different scales: compute the Rec.709 luma plane, blur it with a separable
// Gaussian of the given radius (px), then add `amount * (luma - blur)` to
// EVERY channel equally — chroma and hue are preserved (no chroma halos).
// Sharpness uses radius 1.5 px (edge scale), Detail radius 0.75 px (fine
// texture), per COL-15's "edge sharpness and fine-texture contrast on
// separate scales". Amount = 0.5 * (v/100) from the [-100,100] slider,
// clamped to [-0.5, 0.5]: +100 adds half the local high-pass delta (crisp),
// -100 subtracts half of it (soft). The kernel is a 4-tap discrete Gaussian
// (sigma = radius) with clamped edges; taps sum to 1 so a flat region is
// bit-identical (its blur equals its value, the delta is exactly 0).
void unsharpLumaImpl(ImageF& img, float radius, float amount) {
    const uint32_t w = img.w, h = img.h;
    if (w < 2 || h < 2)
        return; // a 1-px image has no local contrast to sharpen

    // Luma plane (Rec.709 weights, same as stage 18).
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i) {
        const float r = img.rgb[i * 3 + 0];
        const float g = img.rgb[i * 3 + 1];
        const float b = img.rgb[i * 3 + 2];
        luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    // Separable blur: horizontal then vertical, clamped edges. 4 taps at
    // floor(radius) spacing approximate a Gaussian of sigma = radius.
    const int step = static_cast<int>(radius < 1.0f ? 1.0f : std::floor(radius));
    const float c0 = std::exp(-0.5f * (1.0f * 1.0f) / (radius * radius));
    const float c1 = std::exp(-0.5f * (2.0f * 2.0f) / (radius * radius));
    const float norm = 1.0f + 2.0f * (c0 + c1);

    std::vector<float> tmp(n);
    for (uint32_t y = 0; y < h; ++y) {
        const float* row = &luma[static_cast<size_t>(y) * w];
        for (uint32_t x = 0; x < w; ++x) {
            const int xm = std::max<int>(static_cast<int>(x) - step, 0);
            const int xp = std::min(static_cast<int>(x) + step, static_cast<int>(w) - 1);
            const int xm2 = std::max<int>(static_cast<int>(x) - 2 * step, 0);
            const int xp2 = std::min(static_cast<int>(x) + 2 * step, static_cast<int>(w) - 1);
            const float s = row[x] + (row[xm] + row[xp]) * c0 + (row[xm2] + row[xp2]) * c1;
            tmp[static_cast<size_t>(y) * w + x] = s / norm;
        }
    }
    std::vector<float> blur(n);
    for (uint32_t y = 0; y < h; ++y) {
        const int ym = std::max<int>(static_cast<int>(y) - step, 0);
        const int yp = std::min(static_cast<int>(y) + step, static_cast<int>(h) - 1);
        const int ym2 = std::max<int>(static_cast<int>(y) - 2 * step, 0);
        const int yp2 = std::min(static_cast<int>(y) + 2 * step, static_cast<int>(h) - 1);
        for (uint32_t x = 0; x < w; ++x) {
            const float v = tmp[static_cast<size_t>(y) * w + x];
            const float s = v + (tmp[static_cast<size_t>(ym) * w + x]
                              + tmp[static_cast<size_t>(yp) * w + x]) * c0
                       + (tmp[static_cast<size_t>(ym2) * w + x]
                              + tmp[static_cast<size_t>(yp2) * w + x]) * c1;
            blur[static_cast<size_t>(y) * w + x] = s / norm;
        }
    }

    // Add the scaled high-pass delta to every channel equally.
    for (size_t i = 0; i < n; ++i) {
        const float d = amount * (luma[i] - blur[i]);
        img.rgb[i * 3 + 0] += d;
        img.rgb[i * 3 + 1] += d;
        img.rgb[i * 3 + 2] += d;
    }
}

// ---- Noise reduction (DN-01..DN-08, spatial half). ----
// Two independent stages, both on the demosaiced, color-transformed RGB
// BEFORE any tone control (see the stage-order comment in Develop.h for the
// full reasoning: noise is a property of the sensor signal, and the later
// sharpening/detail unsharp masks amplify what survives). Both take
// strength in [0,1] (Grade stores [0,100]; the caller divides). Both are
// edge-aware: a small bilateral range weight exp(-dl^2 / (2*sr^2)) on
// luminance differences suppresses taps across an edge, so noise flattens
// in flat regions while real edges survive. The spatial kernel is the SAME
// 4-tap separable Gaussian as the detail stages (step = floor(radius),
// clamped edges, taps sum to 1), so a flat region's blur equals its value
// exactly — the stages are bit-identical on flat input at any strength.
//
// Radius / sigma_r mapping (documented per the request):
//   chroma stage: radius 2 px (color blotches are low-frequency; a wider
//     kernel smooths them without touching luma detail), sigma_r = 0.10
//     of the local luma scale — chroma offsets ride on luma structure, so
//     the range gate is generous.
//   luma stage: radius 1 px (luma noise is high-frequency; one-pixel taps
//     are enough and keep the operator cheap), sigma_r = 0.05 — tighter
//     than chroma so real luminance texture is not mistaken for noise.
// sigma_r values are absolute (linear working space, midgray 0.18): luma
// differences beyond ~2*sigma_r (0.10 resp. 0.20) are treated as structure.
void denoiseChromaImpl(ImageF& img, float strength) {
    const uint32_t w = img.w, h = img.h;
    if (w < 2 || h < 2 || strength <= 0.0f)
        return;

    const size_t n = static_cast<size_t>(w) * h;
    // Luma plane and the two chroma offsets c = channel - luma.
    std::vector<float> luma(n);
    std::vector<float> cr(n), cb(n);
    for (size_t i = 0; i < n; ++i) {
        const float r = img.rgb[i * 3 + 0];
        const float g = img.rgb[i * 3 + 1];
        const float b = img.rgb[i * 3 + 2];
        const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        luma[i] = y;
        cr[i] = r - y;
        cb[i] = b - y;
    }

    // 4-tap separable Gaussian, radius 2 px (same shape as unsharpLuma's).
    const int step = 2;
    const float radius = 2.0f;
    const float c0 = std::exp(-0.5f / (radius * radius));
    const float c1 = std::exp(-2.0f / (radius * radius));
    // Bilateral range gate on luma differences (edge-aware chroma).
    const float sr = 0.10f;
    const float inv2sr2 = 1.0f / (2.0f * sr * sr);

    // Edge-aware separable blur of one chroma plane: horizontal then
    // vertical pass, each tap weighted by the Gaussian spatial weight AND
    // the range weight against the center pixel's luma. The center tap has
    // range weight 1 and spatial weight 1, so a flat plane (blur == value)
    // reproduces itself exactly.
    const auto blurPlane = [&](const std::vector<float>& plane, std::vector<float>& out) {
        std::vector<float> tmp(n);
        for (uint32_t y = 0; y < h; ++y) {
            const float* row = &plane[static_cast<size_t>(y) * w];
            const float* yrow = &luma[static_cast<size_t>(y) * w];
            for (uint32_t x = 0; x < w; ++x) {
                const int xm = std::max<int>(static_cast<int>(x) - step, 0);
                const int xp = std::min<int>(static_cast<int>(x) + step, static_cast<int>(w) - 1);
                const int xm2 = std::max<int>(static_cast<int>(x) - 2 * step, 0);
                const int xp2 = std::min<int>(static_cast<int>(x) + 2 * step, static_cast<int>(w) - 1);
                float sw = 1.0f, s = 0.0f;
                const auto tap = [&](int xx, float c) {
                    const float dl = yrow[xx] - yrow[x];
                    const float rw = std::exp(-dl * dl * inv2sr2);
                    s += (row[xx] - row[x]) * c * rw;
                    sw += c * rw;
                };
                tap(xm, c0); tap(xp, c0); tap(xm2, c1); tap(xp2, c1);
                tmp[static_cast<size_t>(y) * w + x] = row[x] + s / sw;
            }
        }
        out.assign(n, 0.0f);
        for (uint32_t y = 0; y < h; ++y) {
            const int ym = std::max<int>(static_cast<int>(y) - step, 0);
            const int yp = std::min<int>(static_cast<int>(y) + step, static_cast<int>(h) - 1);
            const int ym2 = std::max<int>(static_cast<int>(y) - 2 * step, 0);
            const int yp2 = std::min<int>(static_cast<int>(y) + 2 * step, static_cast<int>(h) - 1);
            for (uint32_t x = 0; x < w; ++x) {
                const float v = tmp[static_cast<size_t>(y) * w + x];
                const float yc = luma[static_cast<size_t>(y) * w + x];
                float sw = 1.0f, s = 0.0f;
                const auto tap = [&](int yy, float c) {
                    const float dl = luma[static_cast<size_t>(yy) * w + x] - yc;
                    const float rw = std::exp(-dl * dl * inv2sr2);
                    s += (tmp[static_cast<size_t>(yy) * w + x] - v) * c * rw;
                    sw += c * rw;
                };
                tap(ym, c0); tap(yp, c0); tap(ym2, c1); tap(yp2, c1);
                out[static_cast<size_t>(y) * w + x] = v + s / sw;
            }
        }
    };
    std::vector<float> bcr(n), bcb(n);
    blurPlane(cr, bcr);
    blurPlane(cb, bcb);

    // Blend: out = (1-s)*c + s*blur(c), accumulated center-relative as
    // c + s*(blur(c) - c) so a flat chroma region is bit-identical at any
    // strength (the delta is exactly 0 there — no float-rounding drift).
    // Luma is untouched. Green's chroma offset is reconstructed so the
    // luma-weighted chroma sum stays zero (0.2126*cr + 0.7152*cg +
    // 0.0722*cb = 0) on the blended offsets too — the reconstructed
    // pixel's Rec.709 luma is exactly y. When both blended chroma offsets
    // equal the originals the pixel keeps its exact original bytes (the
    // reconstruction would otherwise round y + 0 + 0 differently).
    for (size_t i = 0; i < n; ++i) {
        const float y = luma[i];
        const float dr = strength * (bcr[i] - cr[i]);
        const float db = strength * (bcb[i] - cb[i]);
        if (dr == 0.0f && db == 0.0f)
            continue; // flat chroma: keep the original pixel exactly
        const float newCr = cr[i] + dr;
        const float newCb = cb[i] + db;
        const float newCg = -(0.2126f * newCr + 0.0722f * newCb) / 0.7152f;
        img.rgb[i * 3 + 0] = y + newCr;
        img.rgb[i * 3 + 1] = y + newCg;
        img.rgb[i * 3 + 2] = y + newCb;
    }
}

// Spatial (luma) denoise — stage 8. Edge-preserving smoothing of the luma
// plane: per pixel, a weighted average of the 3x3 neighborhood whose weight
// is the Gaussian spatial weight (radius 1 px, so the 8 neighbors at
// distance 1 and sqrt(2)) times the bilateral range weight
// exp(-dl^2 / (2*sr^2)) on the LUMA difference to the center. Noise —
// small, sign-random deviations — averages toward the neighborhood mean,
// while a tap across a real edge (|dl| >> sr) collapses to ~0 weight and
// the edge survives. The smoothed luma is blended by strength and the
// DELTA (out - in) is added to r, g, b equally — the unsharpLuma idiom —
// so chroma and hue are preserved. A flat region is bit-identical at any
// strength (all taps equal the center, the weighted average is the center).
// sigma_r = 0.05 in the linear working space (midgray 0.18): luma
// differences above ~0.1 read as structure, below it as noise.
void denoiseLumaImpl(ImageF& img, float strength) {
    const uint32_t w = img.w, h = img.h;
    if (w < 3 || h < 3 || strength <= 0.0f)
        return; // a 1-2 px image has no neighborhood to average

    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i)
        luma[i] = 0.2126f * img.rgb[i * 3 + 0]
                + 0.7152f * img.rgb[i * 3 + 1]
                + 0.0722f * img.rgb[i * 3 + 2];

    const float sr = 0.05f;
    const float inv2sr2 = 1.0f / (2.0f * sr * sr);
    // Gaussian spatial weights for the 3x3 neighborhood (sigma = 1 px):
    // center 1, edge neighbors exp(-1/2), diagonal neighbors exp(-1).
    const float we = std::exp(-0.5f), wd = std::exp(-1.0f);

    std::vector<float> smooth(n);
    for (uint32_t y = 0; y < h; ++y) {
        const int ym = std::max<int>(static_cast<int>(y) - 1, 0);
        const int yp = std::min<int>(static_cast<int>(y) + 1, static_cast<int>(h) - 1);
        for (uint32_t x = 0; x < w; ++x) {
            const int xm = std::max<int>(static_cast<int>(x) - 1, 0);
            const int xp = std::min<int>(static_cast<int>(x) + 1, static_cast<int>(w) - 1);
            const float c = luma[static_cast<size_t>(y) * w + x];
            // Center-relative accumulation (sum of weighted DIFFERENCES):
            // a flat neighborhood sums to exactly 0, so a flat region
            // reproduces itself bit-exactly at any strength.
            float sw = 1.0f, s = 0.0f;
            const auto tap = [&](int yy, int xx, float ws) {
                const float dl = luma[static_cast<size_t>(yy) * w + xx] - c;
                const float rw = std::exp(-dl * dl * inv2sr2);
                s += (luma[static_cast<size_t>(yy) * w + xx] - c) * ws * rw;
                sw += ws * rw;
            };
            tap(ym, xm, wd); tap(ym, x, we); tap(ym, xp, wd);
            tap(y, xm, we);                 tap(y, xp, we);
            tap(yp, xm, wd); tap(yp, x, we); tap(yp, xp, wd);
            // The bilateral sum sw is at least 1 (the center tap's weight),
            // so the quotient is a bounded local mean — noise averages
            // toward the neighborhood, never amplified.
            smooth[static_cast<size_t>(y) * w + x] = c + s / sw;
        }
    }

    // Blend by strength as a center-relative delta, d = s*(smooth - in): a
    // flat region (smooth == in) is bit-identical at any strength — the
    // delta is exactly 0, no float-rounding drift. The delta is added to
    // every channel equally (chroma/hue preserved).
    for (size_t i = 0; i < n; ++i) {
        const float d = strength * (smooth[i] - luma[i]);
        img.rgb[i * 3 + 0] += d;
        img.rgb[i * 3 + 1] += d;
        img.rgb[i * 3 + 2] += d;
    }
}

// ---- Tonemapping (COL-16). ----
// ACES: the Narkowicz 2015 fit (see Develop.h for the version/working-space/
// target-display record). Monotone, clamped to [0,1].
inline float acesFit(float x) {
    const float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
    const float num = x * (a * x + b);
    const float den = x * (c * x + d) + e;
    const float y = den > 0.0f ? num / den : 0.0f;
    return std::min(std::max(y, 0.0f), 1.0f);
}

// AgX: 2022 Filmic AgX essential fit (Troy Sobotka), minimal implementation
// after Benjamin Wrensch (iolite) — constants verbatim from that MIT-licensed
// minimal fit; see Develop.h for the full COL-16 record. The 6th-order
// contrast polynomial is used (it is monotone on [0,1]; the 7th-order variant
// is not). Punchy look: power 1.35, sat 1.4. Output is Rec.709-gamma-2.2
// encoded, which the bridge's Rec.709 OETF then treats as display linear.
inline float agxContrastApprox(float x) {
    const float x2 = x * x;
    const float x4 = x2 * x2;
    return 15.5f * x4 * x2 - 40.14f * x4 * x + 31.96f * x4
         - 6.868f * x2 * x + 0.4298f * x2 + 0.1191f * x - 0.00232f;
}

// One AgX channel: inset (done by the caller), log2 encode, contrast.
// The contrast fit is clamped to [0,1] — the sigmoid's intended range: the
// 6th-order polynomial dips to -0.00232 at t=0 (an artifact of the fit, not
// a designed toe), and the Punchy look's pow() must never see a negative
// base (NaN guard; GLSL pow of a negative base is undefined for the same
// reason).
inline float agxEncode(float v, float minEv, float maxEv) {
    if (v <= 0.0f)
        return 0.0f;
    float t = std::log2(v / 0.18f);
    t = std::min(std::max(t, minEv), maxEv);
    t = (t - minEv) / (maxEv - minEv);
    return std::min(std::max(agxContrastApprox(t), 0.0f), 1.0f);
}

void tonemapFrameImpl(ImageF& img, int mode) {
    if (mode == 1) {
        // ACES: per-channel fit on the linear working values.
        for (auto& v : img.rgb)
            v = acesFit(v);
        return;
    }
    if (mode != 2)
        return;

    // AgX: inset -> log2 encode clamped to [-12.47393, 4.026069] EV around
    // 0.18 -> 6th-order contrast approx -> Punchy look -> outset -> gamma 2.2.
    static const float inset[9] = {
        0.856627153315983f, 0.0951212405381588f, 0.0482516061458583f,
        0.137318972929847f, 0.761241990602591f, 0.101439036467562f,
        0.11189821299995f,  0.0767994186031903f, 0.811302368396859f,
    };
    static const float outset[9] = {
        1.1271005818144366432f, -0.1106066430966032116f, -0.016493938717834568157f,
        -0.14132976349843826565f, 1.1578237022162717623f, -0.01649393871783425265f,
        -0.14132976349843824772f, -0.11060664309660291788f, 1.2519364065950402828f,
    };
    static const float minEv = -12.47393f, maxEv = 4.026069f;

    for (size_t i = 0; i < img.rgb.size(); i += 3) {
        float v[3];
        for (int c = 0; c < 3; ++c) {
            const float r = img.rgb[i + 0], g = img.rgb[i + 1], b = img.rgb[i + 2];
            v[c] = inset[c * 3 + 0] * r + inset[c * 3 + 1] * g + inset[c * 3 + 2] * b;
            v[c] = agxEncode(v[c], minEv, maxEv);
        }
        // Punchy look: power 1.35, sat 1.4 (ASC-CDL-style around luma).
        const float luma = 0.2126f * v[0] + 0.7152f * v[1] + 0.0722f * v[2];
        for (int c = 0; c < 3; ++c)
            v[c] = luma + 1.4f * (std::pow(v[c], 1.35f) - luma);
        // Outset, clamp to the display box, Rec.709 gamma 2.2.
        for (int c = 0; c < 3; ++c) {
            float o = outset[c * 3 + 0] * v[0] + outset[c * 3 + 1] * v[1] + outset[c * 3 + 2] * v[2];
            o = std::min(std::max(o, 0.0f), 1.0f);
            img.rgb[i + c] = std::pow(o, 2.2f);
        }
    }
}

// Camera-normalized RGB (already divided by asShotNeutral) -> linear Rec.709.
// DNG 1.6 §6 ForwardMatrix path: XYZ_D50 = FM * CAL * cameraNorm.
// Documented ColorMatrix fallback: XYZ = (CAL * CM)^-1 * cameraNorm (CM is
// XYZ -> camera, so its inverse maps camera -> XYZ; CAL * CM is the full
// camera -> XYZ per the spec's cameraRGB equation read backwards).
// When BOTH matrices are identity (synthetic fixtures / metadata-less
// sources), the stage is a passthrough — an identity camera profile needs
// no colorimetric interpretation, and applying the D50->D65 Rec.709 matrix
// alone would tint neutrals.
bool buildCameraToRec709(const RawMeta& meta, float out[9]) {
    const bool hasForward = !isIdentity3(meta.forwardMatrix);
    const bool hasColor = !isIdentity3(meta.colorMatrix);
    if (!hasForward && !hasColor) {
        for (int i = 0; i < 9; ++i)
            out[i] = (i == 0 || i == 4 || i == 8) ? 1.0f : 0.0f;
        return false; // passthrough: identity profile
    }
    Mat3 cam2xyz{};
    Mat3 FM{}, CAL{}, CM{};
    for (int i = 0; i < 9; ++i) {
        FM.m[i] = meta.forwardMatrix[i];
        CAL.m[i] = meta.calibration[i];
        CM.m[i] = meta.colorMatrix[i];
    }
    if (hasForward) {
        cam2xyz = mul3(FM, CAL);
    } else {
        cam2xyz = inv3(mul3(CAL, CM));
    }
    const Mat3 total = mul3(xyzD50ToRec709(), cam2xyz);
    for (int i = 0; i < 9; ++i)
        out[i] = total.m[i];
    return true;
}

} // namespace

// External stage entry points (declared in Develop.h). Thin wrappers over
// the internal implementations above so developCpu and developEncoded
// (Transfer.cpp) run the SAME denoise, detail and tonemapping stages while
// the math helpers stay internal to this TU.
void denoiseChroma(ImageF& img, float strength) {
    denoiseChromaImpl(img, strength);
}

void denoiseLuma(ImageF& img, float strength) {
    denoiseLumaImpl(img, strength);
}

void unsharpLuma(ImageF& img, float radius, float amount) {
    unsharpLumaImpl(img, radius, amount);
}

void tonemapFrame(ImageF& img, int mode) {
    tonemapFrameImpl(img, mode);
}

float sampleShading(const RawMeta& meta, float u, float v, int plane) {
    if (meta.lensShading.empty() || meta.shadingW == 0 || meta.shadingH == 0)
        return 1.0f; // identity when the map is empty

    const size_t planeSize = static_cast<size_t>(meta.shadingW) * meta.shadingH;
    if (plane < 0 || static_cast<size_t>(plane) >= meta.lensShading.size() / planeSize)
        return 1.0f;

    const float* p = meta.lensShading.data() + static_cast<size_t>(plane) * planeSize;

    // Bilinear sample at normalized sensor coordinates, clamped to the edges.
    const float w = static_cast<float>(meta.shadingW);
    const float h = static_cast<float>(meta.shadingH);
    const float fx = std::min(std::max(u, 0.0f), 1.0f) * (w - 1.0f);
    const float fy = std::min(std::max(v, 0.0f), 1.0f) * (h - 1.0f);
    const int x0 = static_cast<int>(fx);
    const int y0 = static_cast<int>(fy);
    const int x1 = std::min(x0 + 1, static_cast<int>(meta.shadingW) - 1);
    const int y1 = std::min(y0 + 1, static_cast<int>(meta.shadingH) - 1);
    const float tx = fx - static_cast<float>(x0);
    const float ty = fy - static_cast<float>(y0);

    const auto row = [&](int y, int x) {
        return p[static_cast<size_t>(y) * meta.shadingW + x];
    };
    const float a = row(y0, x0) + (row(y0, x1) - row(y0, x0)) * tx;
    const float b = row(y1, x0) + (row(y1, x1) - row(y1, x0)) * tx;
    return a + (b - a) * ty;
}

// Bilinear demosaic of a float CFA into linear RGB. For every pixel and every
// output channel: the pixel's own color is taken as-is; other channels are the
// average of the nearest sites of that color in the 8-neighborhood (with edge
// clamping). For a Bayer pattern every 8-neighborhood of a site contains sites
// of both other colors, so this is a single uniform rule for all four patterns.
ImageF demosaicBilinear(const std::vector<float>& cfa, uint32_t w, uint32_t h, CfaPattern pattern) {
    ImageF img;
    img.w = w;
    img.h = h;
    img.rgb.assign(static_cast<size_t>(w) * h * 3, 0.0f);

    const CfaLayout layout = layoutFor(pattern);
    const int maxI = static_cast<int>(w) - 1;
    const int maxJ = static_cast<int>(h) - 1;

    const auto siteAt = [&](int x, int y) {
        return layout.cell[y & 1][x & 1];
    };
    const auto at = [&](int x, int y) {
        return cfa[static_cast<size_t>(y) * w + x];
    };

    static const int kDx[8] = {-1, 1, 0, 0, -1, -1, 1, 1};
    static const int kDy[8] = {0, 0, -1, 1, -1, 1, -1, 1};

    for (int y = 0; y <= maxJ; ++y) {
        for (int x = 0; x <= maxI; ++x) {
            const int ownSite = siteAt(x, y);
            float* px = &img.rgb[(static_cast<size_t>(y) * w + x) * 3];

            for (int t = 0; t < 3; ++t) {
                if (t == ownSite) {
                    px[t] = at(x, y);
                    continue;
                }
                float sum = 0.0f;
                int n = 0;
                for (int k = 0; k < 8; ++k) {
                    const int nx = std::min(std::max(x + kDx[k], 0), maxI);
                    const int ny = std::min(std::max(y + kDy[k], 0), maxJ);
                    if (siteAt(nx, ny) == t) {
                        sum += at(nx, ny);
                        ++n;
                    }
                }
                px[t] = n > 0 ? sum / static_cast<float>(n) : at(x, y);
            }
        }
    }

    return img;
}

ImageF developCpu(const uint16_t* cfa, const FrameInfo& info, const RawMeta& meta, const Grade& grade, const Lut* lut) {
    const uint32_t w = info.width;
    const uint32_t h = info.height;
    ImageF img;

    if (w == 0 || h == 0 || cfa == nullptr)
        return img;

    const CfaLayout layout = layoutFor(info.cfa);
    const size_t n = static_cast<size_t>(w) * h;

    // ---- Stages 1-3 operate on the CFA, per site. ----
    std::vector<float> norm(n);

    // Stage 1: black/white normalization per CFA channel. The frame's
    // dynamic RAW levels win when it carried them (owner request
    // 2026-11-11 section 3: Android Camera2 defines dynamic black/white
    // levels as per-frame RAW calibration); the FrameInfo sentinel -1
    // means "this frame did not report a level", and the container
    // RawMeta values are the fallback. Hoisted out of the loop: the
    // chosen levels are constant for the whole frame.
    const bool useFrameBlack = info.dynamicBlack[0] >= 0;
    const float white = info.dynamicWhite > 0
        ? static_cast<float>(info.dynamicWhite)
        : static_cast<float>(meta.white);
    float black[4];
    for (int slot = 0; slot < 4; ++slot)
        black[slot] = useFrameBlack
            ? static_cast<float>(info.dynamicBlack[slot])
            : static_cast<float>(meta.black[slot]);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = cellSlot(y & 1, x & 1);
            float v = (static_cast<float>(cfa[y * static_cast<size_t>(w) + x]) - black[slot]) / range[slot];
            norm[y * static_cast<size_t>(w) + x] = std::min(std::max(v, 0.0f), 1.0f);
        }
    }

    // Stage 2: lens shading gain (bilinear on the shading map in sensor
    // coordinates; identity when the map is empty). Skipped when the grade
    // asks to KEEP the optical vignette (grade.keepVignetting false) — the
    // frame then keeps the vignette the sensor actually recorded.
    if (grade.keepVignetting && !meta.lensShading.empty() && meta.shadingW > 0 && meta.shadingH > 0) {
        const float du = w > 1 ? 1.0f / static_cast<float>(w - 1) : 0.0f;
        const float dv = h > 1 ? 1.0f / static_cast<float>(h - 1) : 0.0f;
        for (uint32_t y = 0; y < h; ++y) {
            for (uint32_t x = 0; x < w; ++x) {
                const int slot = cellSlot(y & 1, x & 1);
                const float gain = sampleShading(meta,
                    static_cast<float>(x) * du, static_cast<float>(y) * dv, slot);
                norm[y * static_cast<size_t>(w) + x] *= gain;
            }
        }
    }

    // Stage 3: highlight reconstruction — a channel clipped at white carries
    // no level information, so it is capped at the level the still-unclipped
    // channels in its 2x2 cell carry; that keeps clipped areas neutral instead
    // of magenta. Per cell, per color: track the minimum normalized level and
    // whether every site of that color is clipped. The ceiling L is the
    // lowest post-WB level among colors that still have an unclipped site
    // (when every color is clipped, among all colors — a uniform fully
    // clipped patch then stays at 1.0). Every site at or above white is then
    // clipped to the pre-WB value that maps to L (L / gain).
    // Toggleable (RAW-04): grade.highlightReconstruction false skips this
    // stage entirely, so clipped sites keep their raw clipped level and can
    // read magenta — the sensor-channel artifact the owner wants optional.
    if (grade.highlightReconstruction) {
        const float gain[3] = {
            meta.wbGains[0] > 0.0f ? meta.wbGains[0] : 1.0f,
            meta.wbGains[1] > 0.0f ? meta.wbGains[1] : 1.0f,
            meta.wbGains[2] > 0.0f ? meta.wbGains[2] : 1.0f,
        };
        const uint32_t cellCols = (w + 1) / 2;
        const uint32_t cellRows = (h + 1) / 2;
        for (uint32_t cy = 0; cy < cellRows; ++cy) {
            for (uint32_t cx = 0; cx < cellCols; ++cx) {
                const int x0 = static_cast<int>(cx * 2);
                const int y0 = static_cast<int>(cy * 2);
                const int x1 = std::min(x0 + 1, static_cast<int>(w) - 1);
                const int y1 = std::min(y0 + 1, static_cast<int>(h) - 1);

                float minV[3] = {-1.0f, -1.0f, -1.0f};
                bool clipped[3] = {false, false, false};
                bool present[3] = {false, false, false};
                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        const int color = layout.cell[yy & 1][xx & 1];
                        const float v = norm[static_cast<size_t>(yy) * w + xx];
                        present[color] = true;
                        if (minV[color] < 0.0f || v < minV[color])
                            minV[color] = v;
                        if (v >= 1.0f)
                            clipped[color] = true;
                    }
                }

                // Ceiling from colors that still have unclipped sites.
                float ceiling = -1.0f;
                for (int c = 0; c < 3; ++c) {
                    if (!present[c] || clipped[c])
                        continue;
                    const float level = minV[c] * gain[c];
                    if (ceiling < 0.0f || level < ceiling)
                        ceiling = level;
                }
                // Every color clipped: fall back to the lowest level overall
                // (uniform fully-clipped patches stay at 1.0).
                if (ceiling < 0.0f) {
                    for (int c = 0; c < 3; ++c) {
                        if (!present[c])
                            continue;
                        const float level = minV[c] * gain[c];
                        if (ceiling < 0.0f || level < ceiling)
                            ceiling = level;
                    }
                }
                if (ceiling < 0.0f)
                    continue;

                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        const size_t idx = static_cast<size_t>(yy) * w + xx;
                        if (norm[idx] < 1.0f)
                            continue; // unclipped sites keep their real level
                        const float cap = ceiling / gain[wbIndex(layout.cell[yy & 1][xx & 1])];
                        if (norm[idx] > cap)
                            norm[idx] = cap;
                    }
                }
            }
        }
    }

    // ---- Stage 4: bilinear demosaic. ----
    img = demosaicBilinear(norm, w, h, info.cfa);

    // ---- Stage 5+6: white balance + camera color transform (DNG 1.6 §6). ----
    // The container's ColorMatrix1 maps XYZ -> native camera RGB: multiplying
    // it onto camera RGB (the old code) inverts the wrong direction and puts
    // a green cast on neutrals. The correct path, when ForwardMatrix1 is
    // present (it is in MCRAW containers), is:
    //   1. normalize camera RGB by asShotNeutral (the WB itself — applying
    //      wbGains first and then dividing by asShotNeutral would double it),
    //   2. XYZ_D50 = ForwardMatrix * Calibration * cameraNorm,
    //   3. adapt D50 -> D65 (Bradford) and map XYZ -> linear Rec.709.
    // With no ForwardMatrix, fall back to the documented ColorMatrix path:
    // XYZ = (CAL * CM)^-1 * cameraNorm (then the same D50/D65 handling; CM is
    // calibrated for the D65-ish illuminant here, which is the best
    // documented approximation).
    {
        // Combined camera-normalized -> linear Rec.709 (D65) matrix.
        float cam2rgb[9];
        const bool hasProfile = buildCameraToRec709(meta, cam2rgb);

        const float invNeutral[3] = {
            meta.asShotNeutral[0] > 0.0f ? 1.0f / meta.asShotNeutral[0] : 1.0f,
            meta.asShotNeutral[1] > 0.0f ? 1.0f / meta.asShotNeutral[1] : 1.0f,
            meta.asShotNeutral[2] > 0.0f ? 1.0f / meta.asShotNeutral[2] : 1.0f,
        };

        for (size_t i = 0; i < img.rgb.size(); i += 3) {
            // WB == division by asShotNeutral (reciprocal of wbGains).
            float r = img.rgb[i + 0] * invNeutral[0];
            float g = img.rgb[i + 1] * invNeutral[1];
            float b = img.rgb[i + 2] * invNeutral[2];
            if (hasProfile) {
                const float rr = cam2rgb[0] * r + cam2rgb[1] * g + cam2rgb[2] * b;
                const float gg = cam2rgb[3] * r + cam2rgb[4] * g + cam2rgb[5] * b;
                const float bb = cam2rgb[6] * r + cam2rgb[7] * g + cam2rgb[8] * b;
                r = rr; g = gg; b = bb;
            }
            img.rgb[i + 0] = r;
            img.rgb[i + 1] = g;
            img.rgb[i + 2] = b;
        }
    }

    // ---- Stages 7-8: noise reduction (owner request DN-01..DN-08). ----
    // Chroma first, then luma: the chroma stage's edge gate uses the luma
    // structure of the still-noisy image either way, but running chroma
    // first gives the luma stage a cleaner image to smooth. Both run on the
    // demosaiced, color-transformed RGB before every tone control — noise
    // is a property of the sensor signal, and the sharpening/detail stages
    // (19-20) amplify what survives. Guarded by `!= 0`, so a neutral grade
    // allocates nothing and stays bit-identical to the old pipeline.
    if (grade.chromaDenoise != 0.0f)
        denoiseChroma(img, std::min(std::max(grade.chromaDenoise, 0.0f), 100.0f) / 100.0f);
    if (grade.noiseReduction != 0.0f)
        denoiseLuma(img, std::min(std::max(grade.noiseReduction, 0.0f), 100.0f) / 100.0f);

    // ---- Stages 9-13: tone controls (owner request 2026-11-10 section 2). ----
    // These run after camera->Rec.709 and before exposure, on scene-referred
    // linear values (midgray = 0.18): the shadows/midtones/contrast anchors
    // classify the scene, so an exposure change after them shifts the whole
    // graded range uniformly instead of re-bucketing tones. Each stage is a
    // per-channel scalar remap (neutrals stay neutral); only the shape of the
    // curve differs. All are guarded by `!= neutral` so a neutral grade
    // executes none of them (bit-identical to the pre-tone pipeline).

    // Stage 9: black point (grade.blackPoint in [-100, 100], 0 = neutral).
    // Endpoint remap of the toe, pinned at white: x' = lift + (1 - lift) * x
    // with lift = -0.25 * bp/100. Positive bp (lift < 0) crushes: the toe
    // crossing zero clamps to black at display, and everything below white
    // darkens toward it. Negative bp (lift > 0) lifts the floor — at
    // bp = -100 the input floor 0 maps to 0.25 (faded/filmic blacks). The
    // map is monotone increasing in x, x = 1 stays 1, and the scalar remap
    // applies per channel, so neutrals stay neutral and hue order above the
    // clamp is preserved.
    if (grade.blackPoint != 0.0f) {
        const float lift = -0.25f * (grade.blackPoint / 100.0f);
        for (auto& v : img.rgb)
            v = lift + (1.0f - lift) * v;
    }

    // Stage 10: white point (grade.whitePoint in [-100, 100], 0 = neutral).
    // Endpoint remap of the shoulder, mirror image of stage 9 and pinned at
    // black (0 stays 0, the toe is untouched): a uniform scale. Positive
    // brightens the shoulder — x' = x / (1 - 0.25*wp/100), at wp = +100 a
    // scale of 4/3, so brights rise and clip at display sooner. Negative
    // pulls the ceiling down — x' = x * (1 + 0.25*wp/100), at wp = -100 a
    // scale of 3/4, compressing highlights into range. Monotone in x; a
    // scalar per channel, so neutrals stay neutral.
    if (grade.whitePoint != 0.0f) {
        const float scale = grade.whitePoint > 0.0f
            ? 1.0f / (1.0f - 0.25f * (grade.whitePoint / 100.0f))
            : 1.0f + 0.25f * (grade.whitePoint / 100.0f);
        for (auto& v : img.rgb)
            v *= scale;
    }

    // Stage 11: shadows (grade.shadows in [-100, 100], 0 = neutral).
    // Gain weighted toward black, applied uniformly per pixel (diagonal
    // gain — neutrals stay neutral). weight = smoothstepf(0.18, 0, mx): 1 at
    // black, 0 at midgray and above; gain = 2^(strength * weight), the same
    // exponential-gain idiom as temp/tint. Positive lifts shadows (2x at
    // the floor when +100), negative deepens them (0.5x at -100) —
    // symmetric, never zero, no inversion.
    if (grade.shadows != 0.0f) {
        const float strength = grade.shadows / 100.0f;
        for (size_t i = 0; i < img.rgb.size(); i += 3) {
            const float mx = std::max(img.rgb[i], std::max(img.rgb[i + 1], img.rgb[i + 2]));
            const float wDark = smoothstepf(0.18f, 0.0f, mx); // 1 at black
            const float gain = std::pow(2.0f, strength * wDark);
            img.rgb[i + 0] *= gain;
            img.rgb[i + 1] *= gain;
            img.rgb[i + 2] *= gain;
        }
    }

    // Stage 12: midtones (grade.midtones in [-100, 100], 0 = neutral).
    // Gain weighted around midgray, the same diagonal-gain shape as
    // shadows. weight = smoothstepf(0, 0.18, mx) * (1 - smoothstepf(0.18,
    // 0.72, mx)): a tent that is 0 at black, 1 at midgray (0.18) and 0 again
    // by 0.72, so shadows and highlights keep their own controls; gain =
    // 2^(strength * weight). Positive brightens mids, negative darkens them.
    if (grade.midtones != 0.0f) {
        const float strength = grade.midtones / 100.0f;
        for (size_t i = 0; i < img.rgb.size(); i += 3) {
            const float mx = std::max(img.rgb[i], std::max(img.rgb[i + 1], img.rgb[i + 2]));
            const float wMid = smoothstepf(0.0f, 0.18f, mx)
                            * (1.0f - smoothstepf(0.18f, 0.72f, mx));
            const float gain = std::pow(2.0f, strength * wMid);
            img.rgb[i + 0] *= gain;
            img.rgb[i + 1] *= gain;
            img.rgb[i + 2] *= gain;
        }
    }

    // Stage 13: contrast (grade.contrast in [-100, 100], 0 = neutral).
    // Linear slope around the midgray pivot in scene-referred space:
    // x' = pivot + (x - pivot) * slope, pivot = 0.18, slope = 1 + c with
    // c = contrast/100. +100 doubles the distance from midgray (slope 2),
    // -100 flattens it to zero slope (all tones collapse to the pivot, no
    // inversion). Monotone for every setting; every channel of a neutral
    // pixel moves identically, so neutrals stay neutral.
    if (grade.contrast != 0.0f) {
        const float c = grade.contrast / 100.0f;
        const float slope = 1.0f + c;
        const float pivot = 0.18f;
        for (auto& v : img.rgb)
            v = pivot + (v - pivot) * slope;
    }

    // ---- Stage 14: exposure as 2^ev. ----
    if (grade.exposureEv != 0.0f) {
        const float scale = std::pow(2.0f, grade.exposureEv);
        for (auto& v : img.rgb)
            v *= scale;
    }

    // ---- Stage 15: highlights (grade.highlights in [-100, 100]). ----
    // Negative = recovery: a monotone shoulder compresses the over-knee
    // region toward the knee (filmic rolloff, detail comes back). Positive =
    // a gentle lift of the same region. Neutrals stay neutral (uniform gain
    // per pixel), mid-tones below the knee are untouched.
    //
    // The old quadratic factor (1 - 0.65*s*t) was NOT monotone in t: for
    // recovery (s > 0) it crossed zero at t = 1/(0.65*s) and went negative
    // beyond, so a bright pixel above the crossing came out DARKER than a
    // dimmer one (order reversal) and a very bright one went negative, which
    // the Rec.709 clamp turned into black (owner finding 2026-11-10:
    // sky turning black at Highlights -55 with Exposure +2.1 EV). The new
    // shoulder is monotone for every strength and never goes below the
    // knee: shoulder(t) = knee + (1-knee)*t/(1+t), blending between the
    // original value and the shoulder by the recovery strength s, so at
    // s = 0 (highlights = 0) nothing changes and at full recovery the
    // over-knee region maps to a bounded, order-preserving rolloff.
    if (grade.highlights != 0.0f) {
        const float knee = 0.75f;
        const float span = 1.0f - knee;
        const float s = -grade.highlights / 100.0f; // recovery -> +1, lift -> -1
        for (size_t i = 0; i < img.rgb.size(); i += 3) {
            const float mx = std::max(img.rgb[i], std::max(img.rgb[i + 1], img.rgb[i + 2]));
            if (mx <= knee)
                continue;
            const float over = mx - knee;
            const float t = over / span;               // 0..1+ above the knee
            // Monotone shoulder: bounded, order-preserving for all t.
            const float shoulder = knee + span * (t / (1.0f + t));
            // Blend original -> shoulder by strength (s in [-1, 1]).
            const float newMx = mx + s * (shoulder - mx);
            const float gain = mx > 0.0f ? newMx / mx : 1.0f;
            img.rgb[i + 0] *= gain;
            img.rgb[i + 1] *= gain;
            img.rgb[i + 2] *= gain;
        }
    }

    // ---- Stage 16: temperature (grade.tempK, 2000..50000, neutral 5600). ----
    // Warm/cool adjustment in linear space around the as-shot state: a
    // smooth red/blue push proportional to log2(tempK/5600), applied as a
    // diagonal gain so neutrals stay neutral (no cast on gray).
    // Skipped when the grade requests the camera's as-shot WB
    // (grade.asShot true): asShotNeutral (stage 5) already carries the
    // recorded WB, and an as-shot grade is equivalent to tempK 5600 /
    // tint 0 — both stages would be no-ops anyway. Manual values stay
    // stored in tempK/tint and come back the moment asShot is false.
    if (!grade.asShot) {
        const float tempShift =
            grade.tempK > 0.0f ? std::log2(grade.tempK / 5600.0f) : 0.0f;
        if (std::abs(tempShift) > 1e-4f) {
            const float warmGain = std::pow(2.0f, 0.35f * tempShift);   // red up when warm
            const float coolGain = std::pow(2.0f, -0.35f * tempShift);  // blue up when cool
            for (size_t i = 0; i < img.rgb.size(); i += 3) {
                img.rgb[i + 0] *= warmGain;
                // Green rides the geometric mean so luminance is preserved.
                img.rgb[i + 1] *= std::sqrt(warmGain * coolGain);
                img.rgb[i + 2] *= coolGain;
            }
        }
    }

    // ---- Stage 17: tint (grade.tint in [-100, 100], 0 = neutral). ----
    // Green/magenta push on the green channel only: negative -> magenta
    // (green down), positive -> green up. Smooth, bounded, neutral-safe.
    // Skipped together with stage 16 when the WB source is as-shot.
    if (!grade.asShot && grade.tint != 0.0f) {
        const float greenGain = std::pow(2.0f, 0.4f * (grade.tint / 100.0f));
        for (size_t i = 0; i < img.rgb.size(); i += 3)
            img.rgb[i + 1] *= greenGain;
    }

    // ---- Stage 18: saturation (grade.saturation in [-100, 100], 0 = neutral). ----
    // Chroma scaling around luma, LAST so it scales the final chroma after
    // every per-channel gain: r' = luma + (r - luma) * f, same for g/b, with
    // luma = 0.2126r + 0.7152g + 0.0722b (Rec.709). f = 2^(0.75 * sat/100):
    // +100 scales chroma by 2^0.75 ~= 1.68, -100 by 2^-0.75 ~= 0.59 —
    // symmetric in stops, never clamped to 0 chroma, so hue is preserved
    // for every setting and a neutral pixel (r = g = b) is untouched (its
    // chroma is 0). Monotone, no hue twists.
    if (grade.saturation != 0.0f) {
        const float f = std::pow(2.0f, 0.75f * (grade.saturation / 100.0f));
        for (size_t i = 0; i < img.rgb.size(); i += 3) {
            const float luma = 0.2126f * img.rgb[i + 0]
                            + 0.7152f * img.rgb[i + 1]
                            + 0.0722f * img.rgb[i + 2];
            img.rgb[i + 0] = luma + (img.rgb[i + 0] - luma) * f;
            img.rgb[i + 1] = luma + (img.rgb[i + 1] - luma) * f;
            img.rgb[i + 2] = luma + (img.rgb[i + 2] - luma) * f;
        }
    }

    // ---- Stage 19: sharpness (grade.sharpness in [-100, 100], 0 = neutral). ----
    // ---- Stage 20: detail    (grade.detail    in [-100, 100], 0 = neutral). ----
    // COL-15: edge sharpness and fine-texture contrast on separate scales.
    // Both are luminance-only unsharp masks (see unsharpLuma): Sharpness at
    // radius 1.5 px (edge scale), Detail at radius 0.75 px (fine texture).
    // Amount = 0.5 * (v/100), clamped to [-0.5, 0.5]. When BOTH are neutral
    // the fast path below allocates no blur and touches nothing — bit
    // identical to the pre-detail pipeline (locked by the golden tests).
    if (grade.sharpness != 0.0f || grade.detail != 0.0f) {
        if (grade.sharpness != 0.0f) {
            const float amount =
                std::min(std::max(0.5f * (grade.sharpness / 100.0f), -0.5f), 0.5f);
            unsharpLuma(img, 1.5f, amount);
        }
        if (grade.detail != 0.0f) {
            const float amount =
                std::min(std::max(0.5f * (grade.detail / 100.0f), -0.5f), 0.5f);
            unsharpLuma(img, 0.75f, amount);
        }
    }

    // ---- Stage 21: tonemapping (grade.tonemap, 0 = off, 1 = ACES, 2 = AgX). ----
    // COL-16: optional display tone compression, LAST, after all grade
    // stages. Input/output is the linear working space (linear Rec.709
    // primaries); the Rec.709 OETF stays in the JNI bridge tail. The ACES and
    // AgX fits, their versions, working spaces and target displays are
    // recorded in the Grade comment block in Develop.h. 0 = exact
    // passthrough (the old look).
    if (grade.tonemap != 0)
        tonemapFrame(img, grade.tonemap);

    // ---- Stage 22: LUT (lut + grade.lutStrength in [0, 1], 0 = bypass). ----
    // COL-07: optional .cube 3D LUT, LAST, after tonemapping. Input/output
    // is the linear working space (linear Rec.709 primaries); the Rec.709
    // OETF stays in the JNI bridge tail. The table is per-clip user data
    // passed as `lut` (nullptr = none selected); the strength blend and the
    // color-space contract are recorded in rawedit/Lut.h and the Grade
    // comment block. No LUT or strength 0 = exact passthrough (the old
    // look); the guard keeps the neutral fast path allocation-free.
    if (lut != nullptr && grade.lutStrength != 0.0f)
        applyLut(img, *lut, grade.lutStrength);

    return img;
}

// The RAW-13 linear develop: the minimal, fully-documented stage list that
// turns CFA samples into the LINEAR DNG's pixel data. Exactly three stages
// of the full developCpu pipeline run (the ones a scene-referred linear
// deliverable needs and nothing the display path owns):
//   stage 1  — black/white normalization per CFA channel, frame-preferred
//              levels (the same rule developCpu stage 1 uses)
//   stage 4  — bilinear demosaic (demosaicBilinear, the shared code)
//   stage 5  — as-shot white balance: multiply by 1/asShotNeutral
// Deliberately NOT run: lens shading (2), highlight reconstruction (3),
// camera->Rec.709 (6), denoise (7-8), the tone controls (9-18), the detail
// stages (19-20), tonemapping (21) and the LUT (22). The output stays in
// the camera-native RGB primaries, scene-referred, linear — "Görüntüleme
// ton eşlemesi ile lineer veri çıkışı ayrı yollar" (the display tone map
// and the linear data output are separate paths; owner spec RAW-13). The
// stage list is recorded verbatim in the DNG's ImageDescription tag (see
// DngWriter.cpp kLinearDngStages, which names this function — the two must
// not drift apart).
//
// Output: width*height*3 uint16 samples, row-major, r-g-b order, the float
// 0..1 values scaled by 65535 (rounded, clamped) — the exact bit layout
// writeLinearDng serializes into the strip.
std::vector<uint16_t> developLinear(const uint16_t* cfa, const FrameInfo& info,
                                    const RawMeta& meta) {
    const uint32_t w = info.width;
    const uint32_t h = info.height;
    std::vector<uint16_t> out;
    if (w == 0 || h == 0 || cfa == nullptr)
        return out;

    const size_t n = static_cast<size_t>(w) * h;

    // Stage 1: black/white normalization per CFA channel — copied from
    // developCpu stage 1 (same frame-preferred rule, same slot mapping).
    std::vector<float> norm(n);
    const bool useFrameBlack = info.dynamicBlack[0] >= 0;
    const float white = info.dynamicWhite > 0
        ? static_cast<float>(info.dynamicWhite)
        : static_cast<float>(meta.white);
    float black[4];
    for (int slot = 0; slot < 4; ++slot)
        black[slot] = useFrameBlack
            ? static_cast<float>(info.dynamicBlack[slot])
            : static_cast<float>(meta.black[slot]);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = cellSlot(y & 1, x & 1);
            const float v =
                (static_cast<float>(cfa[y * static_cast<size_t>(w) + x]) -
                 black[slot]) / range[slot];
            norm[y * static_cast<size_t>(w) + x] =
                std::min(std::max(v, 0.0f), 1.0f);
        }
    }

    // Stage 4: bilinear demosaic (the shared code path with developCpu).
    const ImageF img = demosaicBilinear(norm, w, h, info.cfa);

    // Stage 5: as-shot white balance — multiply by 1/asShotNeutral, the
    // same per-channel reciprocals developCpu stage 5 computes. No camera
    // ->Rec.709 after it: the pixels stay in the camera-native primaries
    // (ColorMatrix1 in the DNG describes them; AsShotNeutral is written as
    // (1,1,1) because this multiply already applied the WB).
    const float invNeutral[3] = {
        meta.asShotNeutral[0] > 0.0f ? 1.0f / meta.asShotNeutral[0] : 1.0f,
        meta.asShotNeutral[1] > 0.0f ? 1.0f / meta.asShotNeutral[1] : 1.0f,
        meta.asShotNeutral[2] > 0.0f ? 1.0f / meta.asShotNeutral[2] : 1.0f,
    };

    // Float 0..1 -> uint16 (x 65535, rounded, clamped).
    out.resize(img.rgb.size());
    for (size_t i = 0; i < img.rgb.size(); ++i) {
        float v = img.rgb[i] * invNeutral[i % 3];
        v = std::min(std::max(v, 0.0f), 1.0f);
        out[i] = static_cast<uint16_t>(std::lround(v * 65535.0f));
    }
    return out;
}

} // namespace rawedit
