// rawedit RGB888 -> YUV 4:2:0 conversion — the native twin of the Kotlin
// export/YuvConverter.kt (MP4 export path).
//
// BYTE-EXACT CONTRACT: this converter reproduces the Kotlin computation
// bit-for-bit, so the export can move its per-frame RGB->I420 step into
// native code without changing a single output byte:
//   - coefficients and operation order exactly as YuvConverter.kt:
//     luma  ((16 + m0*r/255) + m1*g/255) + m2*b/255 per pixel; chroma from
//     the exact integer 2x2 RGB sums (edge-clamped for odd dimensions)
//     divided by 1020, quantized ONCE at the end — no intermediate
//     rounding;
//   - rounding is Kotlin Float.roundToInt() == JVM Math.round(float) ==
//     floor(double(v) + 0.5) — ties toward positive infinity, NOT
//     floorf(v + 0.5f), which double-rounds at binade boundaries;
//   - clamps after rounding: Y in [16, 235], chroma in [16, 240];
//   - this TU must compile with -ffp-contract=off (see tools/build-desktop.sh
//     and core/CMakeLists.txt): otherwise the compiler may fuse the chroma
//     multiply-add chain into FMAs, single-rounding intermediate steps the
//     Kotlin path rounds separately.
//
// libyuv is deliberately NOT used: its fixed-point coefficients and integer
// 2x2 chroma pre-rounding produce different bytes (e.g. pure BT.709 green
// (0,255,0) -> Y 173 here, 172 with libyuv's fixed-point tables).
//
// Output goes straight into caller-described planes (YuvPlanes), so the JNI
// bridge can write directly into MediaCodec Image buffers with their
// rowStrides/pixelStrides (planar or semi-planar) with no intermediate
// packed buffer. rgb888ToI420Packed is the tightly-packed layout the
// Kotlin YuvConverter.toI420 produces.

#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>

// NEON path: NEON available AND ARMv8 (the app ships arm64-v8a only).
// vrndm (VRNDM) is an ARMv8 instruction; ARMv7 NEON cannot round float32
// vectors and falls back to the scalar reference.
// Keep in sync with the guard in core/src/Yuv.cpp and core/tests/test_yuv.cpp.
#if defined(__ARM_NEON) && (defined(__aarch64__) || __ARM_ARCH >= 8)
#define RAWEDIT_YUV_NEON 1
#else
#define RAWEDIT_YUV_NEON 0
#endif

#if RAWEDIT_YUV_NEON
#include <arm_neon.h>
#endif

namespace rawedit {

// Limited-range 8-bit (studio swing) RGB -> YUV matrix, the exact float
// coefficients of YuvConverter.kt's YuvMatrix enum (float literals, same
// bits as the Kotlin floatArrayOf values).
struct RgbToYuvMatrix {
    float y[3];   // luma coefficients
    float cb[3];  // Cb coefficients
    float cr[3];  // Cr coefficients
};

inline constexpr RgbToYuvMatrix kYuvMatrixBt601{
    {65.481f, 128.553f, 24.966f},
    {-37.797f, -74.203f, 112.0f},
    {112.0f, -93.786f, -18.214f},
};

inline constexpr RgbToYuvMatrix kYuvMatrixBt709{
    // 219 * 0.2126 / 0.7152 / 0.0722, then chroma scaled by 224.
    {46.5594f, 156.6288f, 15.8118f},
    {-25.664f, -86.336f, 112.0f},
    {112.0f, -101.734f, -10.270f},
};

// Destination planes, the MediaCodec Image geometry (rowStride/pixelStride)
// generalized. Luma is always one byte per pixel (pixelStride 1); chroma
// carries a per-plane pixelStride:
//   - planar I420: uPixelStride == vPixelStride == 1, u and v independent;
//   - semi-planar NV12: uPixelStride == vPixelStride == 2 and
//     v == u + 1 (interleaved UV) — the NEON path stores cb/cr pairs
//     directly;
//   - any other layout (or heap buffers) works too, via the scalar path.
// All strides are in bytes; plane pointers point at the plane's first
// byte (buffer position 0 in the Image's ByteBuffer terms).
struct YuvPlanes {
    uint8_t* y = nullptr;
    int32_t yStride = 0;
    uint8_t* u = nullptr;
    int32_t uStride = 0;
    int32_t uPixelStride = 1;
    uint8_t* v = nullptr;
    int32_t vStride = 0;
    int32_t vPixelStride = 1;
};

// Byte size of a tightly packed I420 frame: Y (w*h) + U (cw*ch) + V (cw*ch),
// cw = (w+1)/2, ch = (h+1)/2 — YuvConverter.toI420's output size.
size_t i420PackedSize(int width, int height);

// Converts packed row-major RGB888 (3 bytes per pixel, exactly what
// nativeExportFrame returns) into the caller's planes. Byte-exact with
// YuvConverter.toI420/toNv12 for the matching layout (see the contract
// above). Requires rgb >= w*h*3 readable bytes, width/height >= 1, and
// planes with room for the frame at their strides. Does not allocate,
// does not touch memory outside the described planes.
void rgb888ToI420(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    const YuvPlanes& out);

// Convenience for the tightly packed I420 layout: writes Y then U then V
// into dst (i420PackedSize bytes). Same bytes as YuvConverter.toI420.
void rgb888ToI420Packed(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    uint8_t* dst);

// ---- test seam: the two implementations, for byte-exactness tests ----
namespace detail {

// Kotlin Float.roundToInt() == JVM Math.round(float): exact round-half-up
// for every value this converter produces (|v| < 2^24 — see Yuv.cpp).
// The double addition is exact on that domain, so this reproduces the
// OpenJDK bit-trick result without the bit trick.
inline int roundHalfUp(float v) {
    return static_cast<int>(std::floor(static_cast<double>(v) + 0.5));
}

// The scalar reference: a line-by-line transcription of YuvConverter.kt
// (the non-NEON fallback and the oracle for the NEON path). Handles any
// plane layout.
void rgb888ToI420Scalar(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    const YuvPlanes& out);

#if RAWEDIT_YUV_NEON
// The NEON rounding kernel: round-half-up of lanes clamped to [lo, hi]
// (lo >= 16) — provably identical to roundHalfUp + clamp on the reachable
// domain (see Yuv.cpp). Exposed for the rounding sweep in test_yuv.cpp.
float32x4_t roundHalfUpClamped(
    float32x4_t v,
    float32x4_t lo,
    float32x4_t hi);

// The NEON path: same bytes as the scalar reference (proven by the
// randomized frame sweep in core/tests/test_yuv.cpp on arm64). Handles the
// interior of any size with vectors; frame edges (odd width/height tails,
// non-fast chroma layouts) fall back to transcribed scalar loops.
void rgb888ToI420Neon(
    const uint8_t* rgb,
    int width,
    int height,
    const RgbToYuvMatrix& matrix,
    const YuvPlanes& out);
#endif

} // namespace detail

} // namespace rawedit
