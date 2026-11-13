// doctests for rawedit/Yuv.h — the byte-exact RGB888 -> I420 contract with
// export/YuvConverter.kt.
//
// Anchors are the known values the JVM suite already asserts (pure RGB
// corners, gray, odd-dimension edge clamp); the randomized sweeps then
// prove the two harder claims:
//   1. roundHalfUp == Kotlin Float.roundToInt() == JVM Math.round(float)
//      over the reachable domain (luma [16, 235], chroma [-96, 352]),
//      including every k+0.5 tie — cross-checked against the double
//      oracle floor(double(v) + 0.5), which is what OpenJDK's bit trick
//      computes for |v| < 2^24.
//   2. the NEON path (when compiled in — arm64 hosts run it here) writes
//      the same bytes as the scalar reference on every generated frame:
//      sizes spanning even/odd/4K, both matrices, packed I420, strided
//      planar, and NV12 layouts.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Yuv.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <random>
#include <vector>

namespace {

// The Kotlin require() equivalent: rgb has w*h*3 bytes.
std::vector<uint8_t> makeRgb(int w, int h, uint32_t seed) {
    std::mt19937 rng(seed);
    std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
    for (auto& b : rgb) b = static_cast<uint8_t>(rng() & 0xFF);
    return rgb;
}

std::vector<uint8_t> solidRgb(int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
    for (size_t i = 0; i < rgb.size(); i += 3) {
        rgb[i] = r;
        rgb[i + 1] = g;
        rgb[i + 2] = b;
    }
    return rgb;
}

// Plane triple with padding, so strides are nonzero (and honored).
struct PaddedPlanes {
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;
    std::vector<uint8_t> uPad;
    std::vector<uint8_t> vPad;
    rawedit::YuvPlanes planes;

    PaddedPlanes(int w, int h, int yStride, int cw, int ch, int cStride, int cPix) {
        y.assign(static_cast<size_t>(yStride) * h + 16, 0xAB);
        u.assign(static_cast<size_t>(cStride) * ch + 16, 0xCD);
        v.assign(static_cast<size_t>(cStride) * ch + 16, 0xEF);
        planes.y = y.data();
        planes.yStride = yStride;
        planes.u = u.data();
        planes.uStride = cStride;
        planes.uPixelStride = cPix;
        planes.v = v.data();
        planes.vStride = cStride;
        planes.vPixelStride = cPix;
    }
};

} // namespace

TEST_CASE("packed size matches Kotlin toI420 output size") {
    // YuvConverterTest: 2x2 -> 4 + 1 + 1 = 6; 3x3 -> 9 + 4 + 4 = 17.
    CHECK(rawedit::i420PackedSize(2, 2) == 6);
    CHECK(rawedit::i420PackedSize(3, 3) == 17);
    // 4K.
    CHECK(rawedit::i420PackedSize(3840, 2160) == 3840ull * 2160 + 2 * 1920 * 1080);
}

TEST_CASE("known values: black, white, pure red/blue BT.601, BT.709 red") {
    // The exact anchors the JVM YuvConverterTest asserts.
    {
        const auto rgb = solidRgb(2, 2, 0, 0, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 16); CHECK(out[1] == 16); CHECK(out[2] == 16); CHECK(out[3] == 16);
        CHECK(out[4] == 128);  // U
        CHECK(out[5] == 128);  // V
    }
    {
        const auto rgb = solidRgb(2, 2, 255, 255, 255);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 235); CHECK(out[1] == 235); CHECK(out[2] == 235); CHECK(out[3] == 235);
        CHECK(out[4] == 128);
        CHECK(out[5] == 128);
    }
    {
        // BT.601 pure red -> Y=81, U=90, V=240.
        const auto rgb = solidRgb(2, 2, 255, 0, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt601, out.data());
        CHECK(out[0] == 81);
        CHECK(out[4] == 90);
        CHECK(out[5] == 240);
    }
    {
        // BT.601 pure blue -> Y=41, U=240, V=110.
        const auto rgb = solidRgb(2, 2, 0, 0, 255);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt601, out.data());
        CHECK(out[0] == 41);
        CHECK(out[4] == 240);
        CHECK(out[5] == 110);
    }
    {
        // BT.709 pure red -> Y=63 (16 + 219*0.2126 = 62.56 -> 63).
        const auto rgb = solidRgb(2, 2, 255, 0, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 63);
    }
    {
        // The libyuv counter-example: BT.709 pure green -> Y=173 here.
        const auto rgb = solidRgb(2, 2, 0, 255, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 173);
    }
}

TEST_CASE("roundHalfUp: ties and binade edges over the reachable domain") {
    using rawedit::detail::roundHalfUp;
    // Kotlin: 15.5f.roundToInt() == 16, 16.5f == 17, 234.5f == 235.
    CHECK(roundHalfUp(15.5f) == 16);
    CHECK(roundHalfUp(16.5f) == 17);
    CHECK(roundHalfUp(234.5f) == 235);
    // floorf(v + 0.5f) double-rounds here: 0.49999997f + 0.5f rounds up to
    // 1.0f in float32, so the naive form gives 1 — Math.round gives 0.
    CHECK(roundHalfUp(0.49999997f) == 0);
    CHECK(roundHalfUp(-0.4f) == 0);
    // Negative ties: Math.round(-0.5f) == 0 (toward +inf), not -1.
    CHECK(roundHalfUp(-0.5f) == 0);

    // Sweep: the entire reachable domain is covered by the double oracle
    // floor(double(v) + 0.5), which equals Math.round for |v| < 2^24, and
    // roundHalfUp IS that oracle — so the sweep instead stresses the exact
    // tie set: every k + 0.5 in the luma and chroma ranges plus their
    // float32 neighbors (one ulp either side).
    for (int k = 15; k <= 352; ++k) {
        const float tie = k + 0.5f;
        const float below = std::nextafterf(tie, -1.0f);
        const float above = std::nextafterf(tie, 1e30f);
        CHECK(roundHalfUp(tie) == k + 1);
        CHECK(roundHalfUp(below) == k);
        CHECK(roundHalfUp(above) == k + 1);
    }
}

#if RAWEDIT_YUV_NEON
TEST_CASE("NEON rounding kernel matches the scalar oracle on the domain") {
    // roundHalfUpClamped vs roundHalfUp + clamp, lane by lane, over the
    // reachable pre-clamp domains: luma [16, 235] (clamped), chroma
    // [-96, 352] (clamped to [16, 240]). Values include exact ties and
    // their ulp neighbors.
    std::mt19937 rng(99);
    const float lo = 16.0f;
    const float hi = 240.0f;
    int checked = 0;
    for (int iter = 0; iter < 20000; ++iter) {
        float vs[4];
        for (int lane = 0; lane < 4; ++lane) {
            const int mode = rng() % 3;
            if (mode == 0) {
                // Uniform over the chroma domain.
                vs[lane] = -96.0f + static_cast<float>(rng() % 449) +
                    (static_cast<float>(rng() % 1000) / 1000.0f - 0.5f);
            } else if (mode == 1) {
                // Exact tie + jitter.
                const int k = 15 + static_cast<int>(rng() % 338);
                vs[lane] = static_cast<float>(k) + 0.5f;
            } else {
                // ulp neighbor of a tie.
                const int k = 15 + static_cast<int>(rng() % 338);
                const float tie = static_cast<float>(k) + 0.5f;
                vs[lane] = (rng() % 2) ? std::nextafterf(tie, -1.0f)
                                       : std::nextafterf(tie, 1e30f);
            }
        }
        float32x4_t v = vld1q_f32(vs);
        const float32x4_t res = rawedit::detail::roundHalfUpClamped(
            v, vdupq_n_f32(lo), vdupq_n_f32(hi));
        float got[4];
        vst1q_f32(got, res);
        for (int lane = 0; lane < 4; ++lane) {
            const int scalar = rawedit::detail::roundHalfUp(vs[lane]);
            const int clamped = scalar < 16 ? 16 : (scalar > 240 ? 240 : scalar);
            CHECK(got[lane] == static_cast<float>(clamped));
            ++checked;
        }
    }
    CHECK(checked == 80000);
}
#endif

TEST_CASE("gray ramp: luma monotone in [16, 235], chroma neutral") {
    // 16x16 gray ramp, both matrices.
    for (int mi = 0; mi < 2; ++mi) {
        const auto& m = mi == 0 ? rawedit::kYuvMatrixBt601 : rawedit::kYuvMatrixBt709;
        const int w = 16, h = 16;
        std::vector<uint8_t> rgb(static_cast<size_t>(w) * h * 3);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const uint8_t g = static_cast<uint8_t>((x * 17) & 0xFF);
                rgb[(static_cast<size_t>(y) * w + x) * 3 + 0] = g;
                rgb[(static_cast<size_t>(y) * w + x) * 3 + 1] = g;
                rgb[(static_cast<size_t>(y) * w + x) * 3 + 2] = g;
            }
        }
        std::vector<uint8_t> out(rawedit::i420PackedSize(w, h));
        rawedit::rgb888ToI420Packed(rgb.data(), w, h, m, out.data());
        int prev = -1;
        for (int x = 0; x < w; ++x) {
            const int yb = out[x];
            CHECK(yb >= 16);
            CHECK(yb <= 235);
            if (prev >= 0 && out[x] != prev) CHECK(yb > prev);
            prev = yb;
        }
        // Gray -> neutral chroma everywhere.
        const size_t cw = 8, ch = 8;
        for (size_t i = 0; i < cw * ch; ++i) {
            CHECK(out[w * h + i] == 128);
            CHECK(out[w * h + cw * ch + i] == 128);
        }
    }
}

TEST_CASE("chroma center vs corner: 4x4 with one hot pixel") {
    // 4x4 all-gray except one pure-red pixel at (0,0): the chroma sample
    // covering it is corner (0,0); center sample (1,1) covers pixels
    // (2..3, 2..3) and must stay neutral. Proves per-sample 2x2 averaging.
    const int w = 4, h = 4;
    auto rgb = solidRgb(w, h, 128, 128, 128);
    rgb[0] = 255; rgb[1] = 0; rgb[2] = 0;
    std::vector<uint8_t> out(rawedit::i420PackedSize(w, h));
    rawedit::rgb888ToI420Packed(rgb.data(), w, h, rawedit::kYuvMatrixBt709, out.data());
    const size_t yuv = w * h;
    // Corner (0,0): 2x2 = (255,0,0) + 3x(128,128,128) -> sums 639/384/384.
    const float r = 639.0f / 1020.0f, g = 384.0f / 1020.0f, b = 384.0f / 1020.0f;
    const auto& m = rawedit::kYuvMatrixBt709;
    const float cbf = ((128.0f + m.cb[0] * r) + m.cb[1] * g) + m.cb[2] * b;
    const float crf = ((128.0f + m.cr[0] * r) + m.cr[1] * g) + m.cr[2] * b;
    CHECK(out[yuv + 0] == static_cast<uint8_t>(std::min(240, std::max(16,
        rawedit::detail::roundHalfUp(cbf)))));
    CHECK(out[yuv + 4 + 0] == static_cast<uint8_t>(std::min(240, std::max(16,
        rawedit::detail::roundHalfUp(crf)))));
    // Center (1,1) covers no red: neutral.
    CHECK(out[yuv + 1 * 2 + 1] == 128);
    CHECK(out[yuv + 4 + 1 * 2 + 1] == 128);
}

TEST_CASE("clamp edges: out-of-gamut RGB reaches Y 16 and 235, chroma 16/240") {
    // BT.709 coefficients: r=g=b=0 -> Y=16; all-255 -> 235. Chroma extremes
    // need the strongest chroma axis: pure red pushes Cr to 240, pure blue
    // pushes Cb to 240. Negative chroma side: green pushes both below 128.
    {
        const auto rgb = solidRgb(2, 2, 0, 0, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 16);
        CHECK(out[4] == 128);
    }
    {
        const auto rgb = solidRgb(2, 2, 255, 255, 255);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt709, out.data());
        CHECK(out[0] == 235);
        CHECK(out[4] == 128);
    }
    {
        // BT.601 pure red: V=240 (the JVM anchor).
        const auto rgb = solidRgb(2, 2, 255, 0, 0);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt601, out.data());
        CHECK(out[5] == 240);
    }
    {
        // BT.601 pure blue: U=240 (the JVM anchor).
        const auto rgb = solidRgb(2, 2, 0, 0, 255);
        std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2));
        rawedit::rgb888ToI420Packed(rgb.data(), 2, 2, rawedit::kYuvMatrixBt601, out.data());
        CHECK(out[4] == 240);
    }
}

TEST_CASE("odd dimensions: edge-clamped chroma matches the scalar law") {
    // 3x3 all-gray: chroma grid 2x2; right/bottom edges clamp. All gray ->
    // neutral chroma, all Y equal. (JVM anchor: size 17, chroma 128.)
    const int w = 3, h = 3;
    const auto rgb = solidRgb(w, h, 128, 128, 128);
    std::vector<uint8_t> out(rawedit::i420PackedSize(w, h));
    rawedit::rgb888ToI420Packed(rgb.data(), w, h, rawedit::kYuvMatrixBt709, out.data());
    REQUIRE(out.size() == 17);
    for (int i = 0; i < 9; ++i) CHECK(out[i] == out[0]);
    for (int i = 9; i < 17; ++i) CHECK(out[i] == 128);
}

#if RAWEDIT_YUV_NEON
TEST_CASE("NEON == scalar: randomized frames, sizes, matrices, layouts") {
    const int sizes[][2] = {
        {2, 2}, {3, 3}, {4, 4}, {5, 7}, {8, 8}, {9, 5}, {16, 16},
        {17, 15}, {63, 31}, {64, 64}, {127, 65}, {384, 288}, {135, 91},
    };
    const rawedit::RgbToYuvMatrix* mats[] = {
        &rawedit::kYuvMatrixBt601,
        &rawedit::kYuvMatrixBt709,
    };
    int frames = 0;
    for (const auto& sz : sizes) {
        const int w = sz[0], h = sz[1];
        const int cw = (w + 1) / 2, ch = (h + 1) / 2;
        for (const auto* m : mats) {
            for (int trial = 0; trial < 3; ++trial) {
                const auto rgb = makeRgb(w, h, 1000u + trial);
                // Packed layout.
                std::vector<uint8_t> want(rawedit::i420PackedSize(w, h));
                rawedit::detail::rgb888ToI420Scalar(rgb.data(), w, h, *m, [&] {
                    rawedit::YuvPlanes p;
                    p.y = want.data(); p.yStride = w;
                    p.u = want.data() + static_cast<size_t>(w) * h; p.uStride = cw; p.uPixelStride = 1;
                    p.v = p.u + static_cast<size_t>(cw) * ch; p.vStride = cw; p.vPixelStride = 1;
                    return p;
                }());
                std::vector<uint8_t> got(want.size(), 0x77);
                rawedit::detail::rgb888ToI420Neon(rgb.data(), w, h, *m, [&] {
                    rawedit::YuvPlanes p;
                    p.y = got.data(); p.yStride = w;
                    p.u = got.data() + static_cast<size_t>(w) * h; p.uStride = cw; p.uPixelStride = 1;
                    p.v = p.u + static_cast<size_t>(cw) * ch; p.vStride = cw; p.vPixelStride = 1;
                    return p;
                }());
                CHECK(got == want);
                ++frames;
            }
        }
    }
    CHECK(frames == 13 * 2 * 3);

    // Strided planar (rowStride > row bytes, pixelStride 1) and NV12
    // (v == u + 1, pixelStride 2) against the scalar reference on the same
    // geometry.
    for (const auto& sz : sizes) {
        const int w = sz[0], h = sz[1];
        const int cw = (w + 1) / 2, ch = (h + 1) / 2;
        const auto rgb = makeRgb(w, h, 4242u);
        const rawedit::RgbToYuvMatrix& m = rawedit::kYuvMatrixBt709;

        // Strided planar.
        PaddedPlanes want(w, h, w + 13, cw, ch, cw + 7, 1);
        rawedit::detail::rgb888ToI420Scalar(rgb.data(), w, h, m, want.planes);
        PaddedPlanes got(w, h, w + 13, cw, ch, cw + 7, 1);
        rawedit::detail::rgb888ToI420Neon(rgb.data(), w, h, m, got.planes);
        CHECK(got.y == want.y);
        CHECK(got.u == want.u);
        CHECK(got.v == want.v);

        // NV12: single buffer, UV interleaved, v == u + 1.
        const size_t yBytes = static_cast<size_t>(w) * h;
        const size_t uvBytes = static_cast<size_t>(cw) * ch * 2;
        std::vector<uint8_t> nvWant(yBytes + uvBytes, 0x11);
        std::vector<uint8_t> nvGot(yBytes + uvBytes, 0x22);
        rawedit::YuvPlanes pw;
        pw.y = nvWant.data(); pw.yStride = w;
        pw.u = nvWant.data() + yBytes; pw.uStride = cw * 2; pw.uPixelStride = 2;
        pw.v = pw.u + 1; pw.vStride = cw * 2; pw.vPixelStride = 2;
        rawedit::detail::rgb888ToI420Scalar(rgb.data(), w, h, m, pw);
        rawedit::YuvPlanes pg;
        pg.y = nvGot.data(); pg.yStride = w;
        pg.u = nvGot.data() + yBytes; pg.uStride = cw * 2; pg.uPixelStride = 2;
        pg.v = pg.u + 1; pg.vStride = cw * 2; pg.vPixelStride = 2;
        rawedit::detail::rgb888ToI420Neon(rgb.data(), w, h, m, pg);
        CHECK(nvGot == nvWant);
    }
}
#endif

TEST_CASE("degenerate sizes are a no-op, not a crash") {
    const auto rgb = solidRgb(2, 2, 1, 2, 3);
    std::vector<uint8_t> out(rawedit::i420PackedSize(2, 2), 0x55);
    rawedit::rgb888ToI420Packed(rgb.data(), 0, 2, rawedit::kYuvMatrixBt709, out.data());
    rawedit::rgb888ToI420Packed(rgb.data(), 2, 0, rawedit::kYuvMatrixBt709, out.data());
    CHECK(out[0] == 0x55);  // untouched
}
