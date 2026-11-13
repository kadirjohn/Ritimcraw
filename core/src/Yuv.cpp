// RGB888 -> YUV 4:2:0 conversion — see rawedit/Yuv.h for the byte-exact
// contract with export/YuvConverter.kt.
//
// This TU MUST compile with -ffp-contract=off (set for exactly this file in
// tools/build-desktop.sh and core/CMakeLists.txt): the chroma expression
// 128 + c0*r + c1*g + c2*b is a multiply-add chain that clang would
// otherwise fuse into FMAs, single-rounding steps the Kotlin path rounds
// separately. Every other rule of the contract is enforced by structure:
// the scalar path below is the Kotlin loop transcribed line by line, the
// NEON path is proven byte-equal to it by the randomized frame sweep in
// core/tests/test_yuv.cpp (which runs the NEON code on arm64 hosts).

#include <rawedit/Yuv.h>

#include <algorithm>
#include <cmath>

#if RAWEDIT_YUV_NEON
#include <arm_neon.h>
#endif

namespace rawedit {
namespace {

// Kotlin Float.roundToInt() == JVM Math.round(float): exact round-half-up
// (ties toward positive infinity) for every value this converter can
// produce. OpenJDK implements Math.round(float) with a bit trick whose
// result for |v| < 2^24 is the mathematically exact floor(v + 1/2); the
// double addition below is exact for every |v| < 2^24 (a float has a
// 24-bit significand and adding the half costs at most one more bit), so
// the two agree on the entire reachable domain — luma in [16, 235],
// chroma in [-96, 352] before the clamp.
// NOT floorf(v + 0.5f): the float addition double-rounds at binade
// boundaries (e.g. 0.49999997f rounds to 1 there and to 0 here).
inline int roundHalfUp(float v) {
    return static_cast<int>(std::floor(static_cast<double>(v) + 0.5));
}

inline uint8_t clampToByte(int v, int lo, int hi) {
    return static_cast<uint8_t>(v < lo ? lo : (v > hi ? hi : v));
}

// ---- shared scalar kernels: the Kotlin loops, factored per sample so the
// NEON path can fall back to them at frame edges without duplicating math.

// One luma sample. The expression keeps Kotlin's left-associative order:
// ((16 + y0*r/255) + y1*g/255) + y2*b/255, each "/" binding to its own
// multiply first — the same tokens as YuvConverter.kt's luma loop.
inline uint8_t lumaByte(const uint8_t* px, const RgbToYuvMatrix& m) {
    const float r = px[0];
    const float g = px[1];
    const float b = px[2];
    const float y =
        ((16.0f + m.y[0] * r / 255.0f) + m.y[1] * g / 255.0f) + m.y[2] * b / 255.0f;
    return clampToByte(roundHalfUp(y), 16, 235);
}

// One chroma sample: the exact integer 2x2 RGB sums (edge-clamped for odd
// dimensions), divided by 1020, quantized ONCE at the end.
inline void chromaBytes(
    const uint8_t* rgb,
    int width,
    int height,
    int cx,
    int cy,
    const RgbToYuvMatrix& m,
    uint8_t* cb,
    uint8_t* cr) {
    int rSum = 0;
    int gSum = 0;
    int bSum = 0;
    for (int dy = 0; dy <= 1; ++dy) {
        for (int dx = 0; dx <= 1; ++dx) {
            const int x = std::min(cx * 2 + dx, width - 1);
            const int y = std::min(cy * 2 + dy, height - 1);
            const uint8_t* p = rgb + (static_cast<size_t>(y) * width + x) * 3;
            rSum += p[0];
            gSum += p[1];
            bSum += p[2];
        }
    }
    // Kotlin divides by (n * 255f) with n counted in the loop, but the 2x2
    // loop always runs four times (the edge clamp only changes WHICH
    // pixels are read), so n is structurally 4 and the divisor is 1020f.
    const float r = static_cast<float>(rSum) / 1020.0f;
    const float g = static_cast<float>(gSum) / 1020.0f;
    const float b = static_cast<float>(bSum) / 1020.0f;
    const float cbf = ((128.0f + m.cb[0] * r) + m.cb[1] * g) + m.cb[2] * b;
    const float crf = ((128.0f + m.cr[0] * r) + m.cr[1] * g) + m.cr[2] * b;
    *cb = clampToByte(roundHalfUp(cbf), 16, 240);
    *cr = clampToByte(roundHalfUp(crf), 16, 240);
}

} // namespace

size_t i420PackedSize(int width, int height) {
    const size_t cw = static_cast<size_t>(width + 1) / 2;
    const size_t ch = static_cast<size_t>(height + 1) / 2;
    return static_cast<size_t>(width) * static_cast<size_t>(height) + 2 * cw * ch;
}

namespace detail {

// The scalar reference: YuvConverter.kt transcribed loop for loop. Handles
// every plane layout (any rowStride >= row bytes, any pixelStride >= 1).
void rgb888ToI420Scalar(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& m,
    const YuvPlanes& out) {
    for (int row = 0; row < height; ++row) {
        const uint8_t* src = rgb + static_cast<size_t>(row) * width * 3;
        uint8_t* dst = out.y + static_cast<size_t>(row) * out.yStride;
        for (int col = 0; col < width; ++col) {
            dst[col] = lumaByte(src + static_cast<size_t>(col) * 3, m);
        }
    }

    const int cw = (width + 1) / 2;
    const int ch = (height + 1) / 2;
    for (int cy = 0; cy < ch; ++cy) {
        uint8_t* uRow = out.u + static_cast<size_t>(cy) * out.uStride;
        uint8_t* vRow = out.v + static_cast<size_t>(cy) * out.vStride;
        for (int cx = 0; cx < cw; ++cx) {
            uint8_t cb = 0;
            uint8_t cr = 0;
            chromaBytes(rgb, width, height, cx, cy, m, &cb, &cr);
            uRow[static_cast<size_t>(cx) * out.uPixelStride] = cb;
            vRow[static_cast<size_t>(cx) * out.vPixelStride] = cr;
        }
    }
}

#if RAWEDIT_YUV_NEON

// Round-half-up for lanes clamped into [lo, hi] (lo >= 16): every lane v
// then satisfies v >= 16 > 1, so floor(v) >= v/2 and v - floor(v) is exact
// (Sterbenz's lemma), which makes the >= 0.5 comparison exact too — the
// result reproduces JVM Math.round on every reachable value, with vrndm
// supplying the floor. Clamping BEFORE rounding is byte-identical to
// Kotlin's round-then-clamp for integer bounds: below lo both give lo,
// above hi both give hi, in between the clamp is a no-op. The randomized
// sweep in core/tests/test_yuv.cpp checks this against the scalar
// reference on every frame it generates.
float32x4_t roundHalfUpClamped(
    float32x4_t v,
    float32x4_t lo,
    float32x4_t hi) {
    v = vmaxq_f32(v, lo);
    v = vminq_f32(v, hi);
    const float32x4_t fl = vrndmq_f32(v);        // exact floor (round down)
    const float32x4_t frac = vsubq_f32(v, fl);   // exact: v >= 16 (Sterbenz)
    const uint32x4_t up = vcgeq_f32(frac, vdupq_n_f32(0.5f));
    return vaddq_f32(
        fl, vbslq_f32(up, vdupq_n_f32(1.0f), vdupq_n_f32(0.0f)));
}

namespace {

// Two rounded quads (exact integers in [16, 240]) -> 8 bytes, low quad
// first. Adding 1.0f to the floor in roundHalfUpClamped is exact (both
// operands are integers below 2^24), so every lane is an integer-valued
// float and the unsigned conversion below is exact.
inline uint8x8_t narrowToU8(float32x4_t lo, float32x4_t hi) {
    const uint32x4_t ulo = vcvtq_u32_f32(lo);
    const uint32x4_t uhi = vcvtq_u32_f32(hi);
    const uint16x4_t l16 = vmovn_u32(ulo);
    const uint16x4_t h16 = vmovn_u32(uhi);
    return vmovn_u16(vcombine_u16(l16, h16));
}

// One luma quad: Kotlin's ((16 + m0*r/255) + m1*g/255) + m2*b/255 with the
// same operation order — vector float ops are IEEE float32, so each vmul/
// vdiv/vadd rounds exactly where the Kotlin float op rounds.
inline float32x4_t lumaQuad(
    float32x4_t r,
    float32x4_t g,
    float32x4_t b,
    float32x4_t m0,
    float32x4_t m1,
    float32x4_t m2,
    float32x4_t s16,
    float32x4_t s255) {
    float32x4_t y = vaddq_f32(s16, vdivq_f32(vmulq_f32(m0, r), s255));
    y = vaddq_f32(y, vdivq_f32(vmulq_f32(m1, g), s255));
    y = vaddq_f32(y, vdivq_f32(vmulq_f32(m2, b), s255));
    return y;
}

// Luma for the first `count` pixels of one row (count is a multiple of 8).
inline void lumaRowNeon(
    const uint8_t* src,
    uint8_t* dst,
    int count,
    float32x4_t m0,
    float32x4_t m1,
    float32x4_t m2,
    float32x4_t s16,
    float32x4_t s235,
    float32x4_t s255) {
    for (int i = 0; i < count; i += 8) {
        const uint8x8x3_t px = vld3_u8(src + static_cast<size_t>(i) * 3);
        const uint16x8_t r16 = vmovl_u8(px.val[0]);
        const uint16x8_t g16 = vmovl_u8(px.val[1]);
        const uint16x8_t b16 = vmovl_u8(px.val[2]);
        const float32x4_t rLo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(r16)));
        const float32x4_t rHi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(r16)));
        const float32x4_t gLo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(g16)));
        const float32x4_t gHi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(g16)));
        const float32x4_t bLo = vcvtq_f32_u32(vmovl_u16(vget_low_u16(b16)));
        const float32x4_t bHi = vcvtq_f32_u32(vmovl_u16(vget_high_u16(b16)));
        const float32x4_t yLo =
            lumaQuad(rLo, gLo, bLo, m0, m1, m2, s16, s255);
        const float32x4_t yHi =
            lumaQuad(rHi, gHi, bHi, m0, m1, m2, s16, s255);
        vst1_u8(
            dst + i,
            narrowToU8(
                roundHalfUpClamped(yLo, s16, s235),
                roundHalfUpClamped(yHi, s16, s235)));
    }
}

// One chroma quad: the 2x2 RGB sums of four adjacent chroma samples. The
// two source rows are distinct, unclamped pixel rows (the caller keeps
// edge rows scalar). vpaddl does the pairwise byte adds (each sum fits
// u16: 255+255 = 510) and the row add lands in u16 with the final 4-pixel
// sum <= 1020 — exact integer arithmetic, identical to Kotlin's rSum
// accumulation.
inline void chromaQuadNeon(
    const uint8_t* row0,
    const uint8_t* row1,
    float32x4_t c0,
    float32x4_t c1,
    float32x4_t c2,
    float32x4_t d0,
    float32x4_t d1,
    float32x4_t d2,
    float32x4_t s128,
    float32x4_t s1020,
    float32x4_t* cb,
    float32x4_t* cr) {
    const uint8x8x3_t p0 = vld3_u8(row0);
    const uint8x8x3_t p1 = vld3_u8(row1);
    const uint16x4_t rS = vadd_u16(vpaddl_u8(p0.val[0]), vpaddl_u8(p1.val[0]));
    const uint16x4_t gS = vadd_u16(vpaddl_u8(p0.val[1]), vpaddl_u8(p1.val[1]));
    const uint16x4_t bS = vadd_u16(vpaddl_u8(p0.val[2]), vpaddl_u8(p1.val[2]));
    // Kotlin's rSum.toFloat() / (n * 255f): the sum is exact in u32, the
    // conversion is exact, and the vector float32 division rounds exactly
    // where the Kotlin division rounds.
    const float32x4_t r = vdivq_f32(vcvtq_f32_u32(vmovl_u16(rS)), s1020);
    const float32x4_t g = vdivq_f32(vcvtq_f32_u32(vmovl_u16(gS)), s1020);
    const float32x4_t b = vdivq_f32(vcvtq_f32_u32(vmovl_u16(bS)), s1020);
    // ((128 + c0*r) + c1*g) + c2*b — Kotlin's order. The intrinsics map
    // one-to-one to instructions, so nothing fuses into an FMA here.
    *cb = vaddq_f32(vaddq_f32(vaddq_f32(s128, vmulq_f32(c0, r)), vmulq_f32(c1, g)), vmulq_f32(c2, b));
    *cr = vaddq_f32(vaddq_f32(vaddq_f32(s128, vmulq_f32(d0, r)), vmulq_f32(d1, g)), vmulq_f32(d2, b));
}

// A rounded quad as 8 bytes with the 4 samples duplicated — the low half
// feeds the planar per-byte stores, and vzip pairs it with the cr quad
// into the 8 contiguous bytes an NV12 UV plane wants.
inline uint8x8_t quadToU8(float32x4_t v) {
    const uint16x4_t h = vmovn_u32(vcvtq_u32_f32(v));
    return vmovn_u16(vcombine_u16(h, h));
}

// Store one chroma quad. Fast paths for the two layouts MediaCodec actually
// produces — planar (pixelStride 1) and NV12 (v == u + 1, pixelStride 2) —
// and a per-byte fallback honoring any other stride/pixelStride pair.
inline void storeChromaQuad(
    const YuvPlanes& out,
    bool nv12,
    int cy,
    int cx0,
    float32x4_t cb,
    float32x4_t cr) {
    if (nv12) {
        // v == u + 1, both pixelStrides 2, same row stride: the quad is 8
        // contiguous UV bytes at the u plane.
        const uint8x8_t uv = vzip_u8(quadToU8(cb), quadToU8(cr)).val[0];
        vst1_u8(
            out.u + static_cast<size_t>(cy) * out.uStride +
                static_cast<size_t>(cx0) * 2,
            uv);
        return;
    }
    const uint32x4_t cbu = vcvtq_u32_f32(cb);
    const uint32x4_t cru = vcvtq_u32_f32(cr);
    uint8_t* up = out.u + static_cast<size_t>(cy) * out.uStride +
        static_cast<size_t>(cx0) * out.uPixelStride;
    uint8_t* vp = out.v + static_cast<size_t>(cy) * out.vStride +
        static_cast<size_t>(cx0) * out.vPixelStride;
    *up = static_cast<uint8_t>(vgetq_lane_u32(cbu, 0));
    *vp = static_cast<uint8_t>(vgetq_lane_u32(cru, 0));
    up += out.uPixelStride; vp += out.vPixelStride;
    *up = static_cast<uint8_t>(vgetq_lane_u32(cbu, 1));
    *vp = static_cast<uint8_t>(vgetq_lane_u32(cru, 1));
    up += out.uPixelStride; vp += out.vPixelStride;
    *up = static_cast<uint8_t>(vgetq_lane_u32(cbu, 2));
    *vp = static_cast<uint8_t>(vgetq_lane_u32(cru, 2));
    up += out.uPixelStride; vp += out.vPixelStride;
    *up = static_cast<uint8_t>(vgetq_lane_u32(cbu, 3));
    *vp = static_cast<uint8_t>(vgetq_lane_u32(cru, 3));
}

} // namespace

// The NEON path: same bytes as the scalar reference (the randomized frame
// sweep in core/tests/test_yuv.cpp proves it on arm64, where this code
// runs in the desktop suite). Bulk rows/columns go through the vector
// kernels; frame edges — the luma columns past the last full 8-pixel
// group, the chroma columns past the last full 4-sample group, and the
// clamped last chroma row of odd-height frames — fall back to the exact
// scalar kernels, so odd sizes and every plane layout stay covered.
void rgb888ToI420Neon(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& m,
    const YuvPlanes& out) {
    const int cw = (width + 1) / 2;
    const int ch = (height + 1) / 2;

    // ---- luma: vector over the leading full 8-pixel groups of each row.
    const int vecCols = width & ~7;
    if (vecCols > 0) {
        const float32x4_t m0 = vdupq_n_f32(m.y[0]);
        const float32x4_t m1 = vdupq_n_f32(m.y[1]);
        const float32x4_t m2 = vdupq_n_f32(m.y[2]);
        const float32x4_t s16 = vdupq_n_f32(16.0f);
        const float32x4_t s235 = vdupq_n_f32(235.0f);
        const float32x4_t s255 = vdupq_n_f32(255.0f);
        for (int row = 0; row < height; ++row) {
            const uint8_t* src = rgb + static_cast<size_t>(row) * width * 3;
            uint8_t* dst = out.y + static_cast<size_t>(row) * out.yStride;
            lumaRowNeon(src, dst, vecCols, m0, m1, m2, s16, s235, s255);
            for (int col = vecCols; col < width; ++col) {
                dst[col] = lumaByte(src + static_cast<size_t>(col) * 3, m);
            }
        }
    } else {
        rgb888ToI420Scalar(rgb, width, height, m, out);
        return;
    }

    // ---- chroma: vector over full 4-sample groups. A group at chroma
    // column cx covers pixel columns [2*cx, 2*cx + 8), so groups exist
    // while 2*cx + 8 <= width; vector rows need both pixel rows
    // (2*cy + 1 <= height - 1). Everything else stays scalar.
    const int vecChromaCols = std::min((width / 8) * 4, cw);
    const int vecChromaRows = height / 2;
    const bool nv12 = out.v == out.u + 1 && out.uPixelStride == 2 &&
        out.vPixelStride == 2 && out.vStride == out.uStride;

    if (vecChromaCols > 0 && vecChromaRows > 0) {
        const float32x4_t c0 = vdupq_n_f32(m.cb[0]);
        const float32x4_t c1 = vdupq_n_f32(m.cb[1]);
        const float32x4_t c2 = vdupq_n_f32(m.cb[2]);
        const float32x4_t d0 = vdupq_n_f32(m.cr[0]);
        const float32x4_t d1 = vdupq_n_f32(m.cr[1]);
        const float32x4_t d2 = vdupq_n_f32(m.cr[2]);
        const float32x4_t s128 = vdupq_n_f32(128.0f);
        const float32x4_t s1020 = vdupq_n_f32(1020.0f);
        const float32x4_t s16 = vdupq_n_f32(16.0f);
        const float32x4_t s240 = vdupq_n_f32(240.0f);
        for (int cy = 0; cy < vecChromaRows; ++cy) {
            const uint8_t* row0 = rgb + static_cast<size_t>(cy * 2) * width * 3;
            const uint8_t* row1 = rgb + static_cast<size_t>(cy * 2 + 1) * width * 3;
            for (int cx = 0; cx < vecChromaCols; cx += 4) {
                float32x4_t cb;
                float32x4_t cr;
                chromaQuadNeon(
                    row0 + static_cast<size_t>(cx) * 6,
                    row1 + static_cast<size_t>(cx) * 6,
                    c0, c1, c2, d0, d1, d2, s128, s1020, &cb, &cr);
                storeChromaQuad(
                    out, nv12, cy, cx,
                    roundHalfUpClamped(cb, s16, s240),
                    roundHalfUpClamped(cr, s16, s240));
            }
        }
    }

    // ---- chroma edges: the clamped last row of odd-height frames and the
    // columns past the full groups (odd widths read their last pixel
    // column twice, exactly like Kotlin's min(cx*2 + dx, width - 1)).
    for (int cy = 0; cy < ch; ++cy) {
        const int cxStart = (cy < vecChromaRows) ? vecChromaCols : 0;
        if (cxStart >= cw) continue;
        uint8_t* uRow = out.u + static_cast<size_t>(cy) * out.uStride;
        uint8_t* vRow = out.v + static_cast<size_t>(cy) * out.vStride;
        for (int cx = cxStart; cx < cw; ++cx) {
            uint8_t cb = 0;
            uint8_t cr = 0;
            chromaBytes(rgb, width, height, cx, cy, m, &cb, &cr);
            uRow[static_cast<size_t>(cx) * out.uPixelStride] = cb;
            vRow[static_cast<size_t>(cx) * out.vPixelStride] = cr;
        }
    }
}

#endif // RAWEDIT_YUV_NEON

} // namespace detail

void rgb888ToI420(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    const YuvPlanes& out) {
    // width/height >= 1 and plane capacity are the caller's contract (the
    // Kotlin require() equivalent lives at the JNI boundary, where the
    // buffer sizes are known); degenerate sizes are a no-op, not a crash.
    if (width <= 0 || height <= 0) return;
#if RAWEDIT_YUV_NEON
    detail::rgb888ToI420Neon(rgb, width, height, matrix, out);
#else
    detail::rgb888ToI420Scalar(rgb, width, height, matrix, out);
#endif
}

void rgb888ToI420Packed(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    uint8_t* dst) {
    if (width <= 0 || height <= 0) return;
    const int cw = (width + 1) / 2;
    const int ch = (height + 1) / 2;
    YuvPlanes p;
    p.y = dst;
    p.yStride = width;
    p.u = dst + static_cast<size_t>(width) * height;
    p.uStride = cw;
    p.uPixelStride = 1;
    p.v = p.u + static_cast<size_t>(cw) * ch;
    p.vStride = cw;
    p.vPixelStride = 1;
    rgb888ToI420(rgb, width, height, matrix, p);
}

} // namespace rawedit
