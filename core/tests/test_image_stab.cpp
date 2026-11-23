// ImageStab tests (S1: pairwise motion estimation).
//
// Synthetic ground truth: a band-limited procedural texture f(x, y) is
// evaluated ANALYTICALLY at the inverse-mapped coordinate, so a frame
// pair has an exactly known similarity and no resampling blur confounds
// the tracker's own accuracy.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/ImageStab.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

using namespace rawedit;

namespace {

constexpr int W = 640, H = 360;

struct Rnd {
    uint64_t s;
    explicit Rnd(uint64_t seed) : s(seed * 6364136223846793005ull + 1442695040888963407ull) {}
    double next() {  // [0,1)
        s = s * 6364136223846793005ull + 1442695040888963407ull;
        return static_cast<double>(s >> 11) / 9007199254740992.0;
    }
};

struct Texture {
    struct Wave { double kx, ky, ph, amp; };
    std::vector<Wave> w;
    explicit Texture(uint64_t seed, int n = 60) {
        Rnd r(seed);
        for (int i = 0; i < n; ++i) {
            const double wl = 6.0 + r.next() * 74.0;  // wavelength 6..80 px
            const double a = r.next() * 6.283185307179586;
            w.push_back({std::cos(a) * 6.283185307179586 / wl,
                         std::sin(a) * 6.283185307179586 / wl,
                         r.next() * 6.283185307179586, wl / 20.0});  // ~1/f: amplitude grows with wavelength
        }
    }
    double at(double x, double y) const {
        double v = 0;
        for (const auto& k : w) v += k.amp * std::sin(k.kx * x + k.ky * y + k.ph);
        return v;
    }
};

// p_cur = s R(theta) p_prev + t, in CENTERED pixel coordinates.
struct Truth {
    double tx = 0, ty = 0, s = 1, th = 0;  // px, px, ratio, rad
};

uint8_t quant(double v, double gain, double bias) {
    const double q = std::floor(bias + gain * v + 0.5);
    return static_cast<uint8_t>(std::min(255.0, std::max(0.0, q)));
}

// frame(p) = f(M^-1 p) with M = truth. Optional per-block outlier motion
// and additive noise (deterministic).
std::vector<uint8_t> render(const Texture& tex, const Truth& m, double gain = 3.0,
                            double bias = 128.0, double noise = 0.0,
                            uint64_t noiseSeed = 1, double outlierFrac = 0.0,
                            double outlierShift = 25.0) {
    std::vector<uint8_t> out(static_cast<size_t>(W) * H);
    const double cs = std::cos(m.th), sn = std::sin(m.th);
    Rnd nr(noiseSeed);
    Rnd br(777);
    // 8x6 blocks of 80x60 px; a fixed hash picks the outlier blocks.
    std::vector<int> blockOut(48, 0);
    int want = static_cast<int>(48 * outlierFrac + 0.5);
    while (want > 0) {
        const int k = static_cast<int>(br.next() * 48);
        if (!blockOut[k]) { blockOut[k] = 1; --want; }
    }
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            double px = x - W * 0.5, py = y - H * 0.5;
            // inverse of p' = s R p + t  ->  p = R^-1 (p' - t) / s
            double ux = px - m.tx, uy = py - m.ty;
            double qx = (cs * ux + sn * uy) / m.s;
            double qy = (-sn * ux + cs * uy) / m.s;
            const int bx = x / 80, by = y / 60;
            if (blockOut[by * 8 + bx]) {
                qx = px - outlierShift;  // outlier content slides differently
                qy = py + outlierShift * 0.6;
            }
            double v = tex.at(qx, qy);
            double nz = 0;
            if (noise > 0) nz = (nr.next() + nr.next() + nr.next() - 1.5) * 2.0 * noise;
            out[static_cast<size_t>(y) * W + x] = quant(v, gain, bias + nz);
        }
    return out;
}

LumaFrame view(const std::vector<uint8_t>& v) { return {W, H, W, v.data()}; }

struct Err { double tx, ty, s, th; };
Err errOf(const PairMotion& pm, const Truth& t) {
    // pair motion is in normalized (/H) units.
    return {std::fabs(pm.tx * H - t.tx), std::fabs(pm.ty * H - t.ty),
            std::fabs(std::exp(pm.logS) - t.s), std::fabs(pm.theta - t.th)};
}

}  // namespace

TEST_CASE("known similarity is recovered below 0.1 px / 0.02 deg / 0.1%") {
    const Truth cases[] = {
        {3.7, -2.2, 1.0, 0.0},
        {-1.3, 0.8, 1.003, 0.004},
        {6.1, 4.4, 0.995, -0.01},
        {0.4, -0.3, 1.0, 0.0175},        // 1 degree
        {22.0, -15.0, 1.01, 0.02},       // needs the pyramid
    };
    int ci = 0;
    for (const Truth& t : cases) {
        const Texture tex(100 + ci++);
        const auto a = render(tex, Truth{});
        const auto b = render(tex, t);
        const PairMotion pm = estimatePair(view(a), view(b));
        CAPTURE(t.tx); CAPTURE(t.ty); CAPTURE(t.s); CAPTURE(t.th);
        REQUIRE(pm.status == PairStatus::Ok);
        const Err e = errOf(pm, t);
        CHECK(e.tx < 0.1);
        CHECK(e.ty < 0.1);
        CHECK(e.th < 0.02 * 3.141592653589793 / 180.0);
        CHECK(e.s < 0.001);
        CHECK(pm.residualRms < 0.5f);
    }
}

TEST_CASE("40 percent outlier blocks do not bias the estimate") {
    const Truth t{4.2, -3.1, 1.004, 0.006};
    for (uint64_t seed : {11ull, 12ull, 13ull}) {
        const Texture tex(seed);
        const auto a = render(tex, Truth{});
        const auto b = render(tex, t, 3.0, 128.0, 0.0, 1, 0.40, 25.0);
        const PairMotion pm = estimatePair(view(a), view(b));
        CAPTURE(seed);
        REQUIRE(pm.status != PairStatus::Lost);
        const Err e = errOf(pm, t);
        CHECK(e.tx < 0.1);
        CHECK(e.ty < 0.1);
        CHECK(e.th < 0.02 * 3.141592653589793 / 180.0);
        CHECK(e.s < 0.001);
    }
}

TEST_CASE("static scene with sensor noise gives a flat path") {
    const Texture tex(21);
    std::vector<std::vector<uint8_t>> fr;
    for (int i = 0; i < 20; ++i)
        fr.push_back(render(tex, Truth{}, 3.0, 128.0, 1.5, 1000 + i));
    double maxPair = 0, cumX = 0, cumY = 0, maxCum = 0;
    for (size_t i = 1; i < fr.size(); ++i) {
        const PairMotion pm = estimatePair(view(fr[i - 1]), view(fr[i]));
        REQUIRE(pm.status == PairStatus::Ok);
        maxPair = std::max({maxPair, std::fabs(pm.tx * H), std::fabs(pm.ty * H)});
        cumX += pm.tx * H;
        cumY += pm.ty * H;
        maxCum = std::max({maxCum, std::fabs(cumX), std::fabs(cumY)});
    }
    CHECK(maxPair < 0.05);
    CHECK(maxCum < 0.1);
}

TEST_CASE("identical frames give exactly zero motion") {
    const Texture tex(31);
    const auto a = render(tex, Truth{});
    const PairMotion pm = estimatePair(view(a), view(a));
    REQUIRE(pm.status == PairStatus::Ok);
    CHECK(std::fabs(pm.tx) < 1e-6);
    CHECK(std::fabs(pm.ty) < 1e-6);
    CHECK(std::fabs(pm.logS) < 1e-6);
    CHECK(std::fabs(pm.theta) < 1e-6);
}

TEST_CASE("estimatePair is deterministic (bit-identical across runs)") {
    const Texture tex(41);
    const Truth t{5.5, 2.5, 1.002, 0.003};
    const auto a = render(tex, Truth{});
    const auto b = render(tex, t, 3.0, 128.0, 1.0, 5, 0.3);
    const PairMotion p1 = estimatePair(view(a), view(b));
    const PairMotion p2 = estimatePair(view(a), view(b));
    CHECK(p1.tx == p2.tx);
    CHECK(p1.ty == p2.ty);
    CHECK(p1.logS == p2.logS);
    CHECK(p1.theta == p2.theta);
    CHECK(p1.nInliers == p2.nInliers);
    CHECK(p1.nTracked == p2.nTracked);
    CHECK(p1.residualRms == p2.residualRms);
    // Pyramid reuse path gives the same answer as the frame path.
    const ImageStabOptions o;
    const ImagePyramid pa = buildPyramid(view(a), o.levels);
    const ImagePyramid pb = buildPyramid(view(b), o.levels);
    const PairMotion p3 = estimatePair(pa, pb, o);
    CHECK(p3.tx == p1.tx);
    CHECK(p3.theta == p1.theta);
}

TEST_CASE("flat and unrelated frames are Lost, not a made-up motion") {
    std::vector<uint8_t> flat(static_cast<size_t>(W) * H, 120);
    PairMotion pm = estimatePair(view(flat), view(flat));
    CHECK(pm.status == PairStatus::Lost);
    CHECK(pm.tx == 0.0);
    // Two unrelated textures (scene cut).
    const auto a = render(Texture(51), Truth{});
    const auto b = render(Texture(52), Truth{});
    pm = estimatePair(view(a), view(b));
    CHECK(pm.status != PairStatus::Ok);
}

TEST_CASE("thresholds follow the frame's own luma scale") {
    // Same scene at 1/3 contrast (flat log-like clip) is still tracked.
    const Texture tex(61);
    const Truth t{3.2, -1.7, 1.0, 0.005};
    const auto a = render(tex, Truth{}, 1.0, 128.0);
    const auto b = render(tex, t, 1.0, 128.0);
    const PairMotion pm = estimatePair(view(a), view(b));
    REQUIRE(pm.status == PairStatus::Ok);
    const Err e = errOf(pm, t);
    CHECK(e.tx < 0.15);
    CHECK(e.ty < 0.15);
}

TEST_CASE("a large pan needs the prior; with it the pair recovers") {
    const Texture tex(71);
    const Truth t{70.0, -40.0, 1.0, 0.0};
    const auto a = render(tex, Truth{});
    const auto b = render(tex, t);
    PairMotion prior;
    prior.tx = 70.0 / H;
    prior.ty = -40.0 / H;
    prior.status = PairStatus::Ok;
    const PairMotion pm = estimatePair(view(a), view(b), ImageStabOptions(), &prior);
    REQUIRE(pm.status != PairStatus::Lost);
    const Err e = errOf(pm, t);
    CHECK(e.tx < 0.15);
    CHECK(e.ty < 0.15);
}

TEST_CASE("stage times are reported") {
    const Texture tex(81);
    const auto a = render(tex, Truth{});
    const auto b = render(tex, Truth{2.0, 1.0, 1.0, 0.0});
    StageTimes st;
    (void)estimatePair(view(a), view(b), ImageStabOptions(), nullptr, &st);
    CHECK(st.pyramidMs > 0.0);
    CHECK(st.cornersMs > 0.0);
    CHECK(st.lkMs > 0.0);
    CHECK(st.fitMs >= 0.0);
}
