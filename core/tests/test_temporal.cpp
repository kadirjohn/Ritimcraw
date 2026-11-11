// Temporal NR unit tests (DN-05..DN-09 + denoise research 2026-11-11).
//
// Synthetic frames: flat or textured CFA buffers (black 64 / white 1023,
// same levels as the develop fixtures) with deterministic LCG noise (sum of
// 4 uniforms, variance exactly sigma^2, bounded). Textured frames use a
// fixed white-noise field: every proxy block is unique, so block matching
// has exactly one correct answer and no aliasing (a sinusoidal test pattern
// would alias at 1/8 proxy scale and let the +-4 search find fake matches).
//
// Real-clip tests run only when RAWEDIT_REALCLIP=1 (2 GB fixture, decode is
// slow); the default run prints them as SKIP.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Source.h>
#include <rawedit/Temporal.h>

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// ---- deterministic noise (LCG + 4-uniform sum, var exactly sigma^2) ----
struct Lcg {
    uint32_t s;
    explicit Lcg(uint32_t seed) : s(seed ? seed : 1u) {}
    float u() { // [0,1)
        s = 1664525u * s + 1013904223u;
        return static_cast<float>(s >> 8) / 16777216.0f;
    }
    float gauss(float sigma) { // var = sigma^2, bounded +-3.46 sigma
        const float sum = u() + u() + u() + u() - 2.0f;
        return sum * sigma * 1.7320508f;
    }
};

constexpr uint32_t kTw = 256;  // small-frame edge (2x2 block grid)
constexpr uint32_t kLw = 512;  // textured-frame edge (4x4 block grid)
constexpr uint16_t kBlack = 64, kWhite = 1023;
constexpr float kRange = static_cast<float>(kWhite - kBlack);

FrameInfo testInfo(uint32_t w, uint32_t h, int64_t ptsUs = 0) {
    FrameInfo info;
    info.width = w;
    info.height = h;
    info.ptsUs = ptsUs;
    info.cfa = CfaPattern::RGGB;
    return info;
}

RawMeta testMeta() {
    RawMeta m;
    m.black[0] = m.black[1] = m.black[2] = m.black[3] = kBlack;
    m.white = kWhite;
    return m;
}

// Pattern frame: level(x,y) in normalized [0,1] + noise (sigmaDn in DN).
std::vector<uint16_t> makeFrame(uint32_t w, uint32_t h,
                                const std::function<float(uint32_t, uint32_t)>& level,
                                uint32_t seed, float sigmaDn) {
    std::vector<uint16_t> f(static_cast<size_t>(w) * h);
    Lcg rng(seed);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float lv = std::min(std::max(level(x, y), 0.02f), 0.98f);
            float v = kBlack + lv * kRange + rng.gauss(sigmaDn);
            v = std::min(std::max(v, 0.0f), 65535.0f);
            f[static_cast<size_t>(y) * w + x] = static_cast<uint16_t>(v + 0.5f);
        }
    return f;
}

auto flat(float level) {
    return [level](uint32_t, uint32_t) { return level; };
}

// Fixed white-noise texture field, 512x512, values in [0.2, 0.7]: unique
// content at every position (deterministic; no aliasing at any shift).
const std::vector<float>& textureField() {
    static const std::vector<float> field = [] {
        std::vector<float> v(static_cast<size_t>(kLw) * kLw);
        Lcg rng(20261111u);
        for (uint32_t y = 0; y < kLw; ++y)
            for (uint32_t x = 0; x < kLw; ++x)
                v[static_cast<size_t>(y) * kLw + x] = 0.325f + 0.25f * rng.u();
        return v;
    }();
    return field;
}

float texture(uint32_t x, uint32_t y) {
    return textureField()[static_cast<size_t>(y) * kLw + x];
}

// Texture shifted by (sx, sy) PROXY pixels (8 CFA px each), edge-clamped.
auto shiftedTexture(int sx, int sy) {
    return [sx, sy](uint32_t x, uint32_t y) {
        const int xx = static_cast<int>(x) - (sx << 3);
        const int yy = static_cast<int>(y) - (sy << 3);
        return texture(static_cast<uint32_t>(std::min(std::max(xx, 0), 511)),
                       static_cast<uint32_t>(std::min(std::max(yy, 0), 511)));
    };
}

// Spatial variance (DN^2) over a rect [x0,x1) x [y0,y1).
double variance(const std::vector<uint16_t>& f, uint32_t w,
                uint32_t x0, uint32_t y0, uint32_t x1, uint32_t y1) {
    double sum = 0.0, sum2 = 0.0;
    long long n = 0;
    for (uint32_t y = y0; y < y1; ++y)
        for (uint32_t x = x0; x < x1; ++x) {
            const double v = f[static_cast<size_t>(y) * w + x];
            sum += v;
            sum2 += v * v;
            ++n;
        }
    const double mean = sum / n;
    return sum2 / n - mean * mean;
}

double meanOf(const std::vector<uint16_t>& f, uint32_t w,
              uint32_t x0, uint32_t y0, uint32_t x1, uint32_t y1) {
    double sum = 0.0;
    long long n = 0;
    for (uint32_t y = y0; y < y1; ++y)
        for (uint32_t x = x0; x < x1; ++x) {
            sum += f[static_cast<size_t>(y) * w + x];
            ++n;
        }
    return sum / n;
}

// Exact Camera2 profile for a constant sigma (S=0, O=sigma_norm^2).
NoiseProfile constantProfile(float sigmaDn) {
    NoiseProfile np;
    np.valid = true;
    const float s = sigmaDn / kRange;
    for (int k = 0; k < 4; ++k) {
        np.s[k] = 0.0f;
        np.o[k] = s * s;
    }
    return np;
}

// Real-clip gating, same pattern as test_source.cpp.
bool realclipEnabled() { return std::getenv("RAWEDIT_REALCLIP") != nullptr; }

std::string realclipPath() {
    if (const char* p = std::getenv("RAWEDIT_CLIP"))
        return p;
    return "/Users/kc4ca/projects/Ritimcraw/STEPS/media/test/261110_004208_VIDEO_24mm.mcraw";
}

} // namespace

// ---- PTS eligibility (research: duplicates and large gaps rejected) ----

TEST_CASE("pts_eligibility_rejects_duplicates_and_gaps") {
    const int64_t dur = 33333; // ~30 fps
    CHECK_FALSE(temporalNeighborEligible(0, dur));       // duplicate frame
    CHECK(temporalNeighborEligible(dur, dur));           // adjacent
    CHECK(temporalNeighborEligible(-dur, dur));          // reverse order ok
    CHECK(temporalNeighborEligible((3 * dur) / 2, dur)); // 1.5x boundary
    CHECK_FALSE(temporalNeighborEligible((3 * dur) / 2 + 1, dur)); // past it
    CHECK_FALSE(temporalNeighborEligible(10 * dur, dur)); // dropped frames
    CHECK_FALSE(temporalNeighborEligible(dur, 0));       // no duration info
    CHECK_FALSE(temporalNeighborEligible(dur, -5));
}

// ---- luma proxy ----

TEST_CASE("proxy_is_one_eighth_scale_and_pattern_independent") {
    const RawMeta m = testMeta();
    std::vector<uint16_t> f = makeFrame(kTw, kTw, flat(0.5f), 7, 0.0f);
    const FrameInfo info = testInfo(kTw, kTw);
    const LumaProxy p = buildLumaProxy(f.data(), info, m);
    CHECK(p.w == kTw / 8);
    CHECK(p.h == kTw / 8);
    CHECK(p.v.size() == static_cast<size_t>(p.w) * p.h);
    // Flat 0.5: every sample is exactly 544 DN -> (544-64)/959. DN
    // quantization is part of the round trip, so check the exact value.
    for (float v : p.v)
        CHECK(v == doctest::Approx(480.0f / 959.0f).epsilon(1e-6));

    // Same content under a different CFA pattern gives the same proxy:
    // the 4 stride-2 samples hit each 2x2 cell slot once, any pattern.
    FrameInfo bggr = testInfo(kTw, kTw);
    bggr.cfa = CfaPattern::BGGR;
    const LumaProxy q = buildLumaProxy(f.data(), bggr, m);
    CHECK(q.v == p.v);

    // Too small to block-match: empty proxy (the caller rejects).
    const FrameInfo tiny = testInfo(64, 64);
    CHECK(buildLumaProxy(f.data(), tiny, m).w == 0);
}

// ---- motion estimation on synthetic proxies (pure shift) ----

TEST_CASE("block_matching_finds_pure_shift_exactly") {
    const RawMeta m = testMeta();
    const int sx = 3, sy = -2; // proxy px, inside the +-4 radius
    std::vector<uint16_t> c = makeFrame(kLw, kLw, texture, 1, 0.0f);
    std::vector<uint16_t> n = makeFrame(kLw, kLw, shiftedTexture(sx, sy), 2, 0.0f);
    const FrameInfo info = testInfo(kLw, kLw);
    const LumaProxy pc = buildLumaProxy(c.data(), info, m);
    const LumaProxy pn = buildLumaProxy(n.data(), info, m);
    REQUIRE(pc.w == 64);
    REQUIRE(pn.w == 64);

    std::vector<MotionVector> mv;
    uint32_t bw = 0, bh = 0;
    matchProxyBlocks(pc, pn, mv, bw, bh);
    REQUIRE(bw == 4);
    REQUIRE(bh == 4);
    REQUIRE(mv.size() == 16);
    for (const auto& v : mv) {
        CHECK(v.dx == sx);
        CHECK(v.dy == sy);
    }
    // Interior block (1,1): the matched window is fully in bounds, so the
    // white-noise content aligns exactly — MAD is identically zero.
    CHECK(mv[1 * 4 + 1].mad == 0.0f);
}

TEST_CASE("block_matching_beyond_radius_does_not_lock_on") {
    const RawMeta m = testMeta();
    const int sx = 15; // proxy px, far beyond the +-4 radius
    std::vector<uint16_t> c = makeFrame(kLw, kLw, texture, 1, 0.0f);
    std::vector<uint16_t> n = makeFrame(kLw, kLw, shiftedTexture(sx, 0), 2, 0.0f);
    const FrameInfo info = testInfo(kLw, kLw);
    std::vector<MotionVector> mv;
    uint32_t bw = 0, bh = 0;
    matchProxyBlocks(buildLumaProxy(c.data(), info, m),
                     buildLumaProxy(n.data(), info, m), mv, bw, bh);
    REQUIRE(mv.size() == 16);
    // White noise has no aliasing: nothing inside the window matches, so
    // no block may report a confident match (the true offset is 15 px).
    int confident = 0;
    for (const auto& v : mv)
        if (v.mad <= 0.01f)
            ++confident;
    CHECK(confident == 0);
}

// ---- disabled path: bit-identical by construction ----

TEST_CASE("temporal_off_is_bit_identical") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 1, 6.0f);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 2, 6.0f);
    std::vector<uint16_t> x = makeFrame(kTw, kTw, flat(0.3f), 3, 6.0f);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts; // strength 0: default off
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), x.data(), m, ts, out.data(), &rep);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
    CHECK(rep.prev.present == false); // no evaluation ran at all
    CHECK(rep.mergeMs == 0.0);
}

// ---- the point of the stage: noise reduction on a static window ----
// (flat frames are also the zero-texture case: block matching returns
// noise-driven vectors, and the STATIC-FIRST decision must accept the
// neighbor at zero motion — the flat-wall/sky case the gates exist to
// keep, not reject.)

TEST_CASE("static_window_reduces_noise_variance") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const float sigma = 6.0f; // DN per frame
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 11, sigma);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 12, sigma);
    std::vector<uint16_t> x = makeFrame(kTw, kTw, flat(0.3f), 13, sigma);
    std::vector<uint16_t> out(c.size(), 0);

    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), x.data(), m, ts, out.data(), &rep);

    CHECK(rep.prev.eligible);
    CHECK(rep.next.eligible);
    CHECK(rep.prev.meanWeight == doctest::Approx(0.40f).epsilon(0.05));

    const double varC = variance(c, kTw, 32, 32, 224, 224);
    const double varOut = variance(out, kTw, 32, 32, 224, 224);
    // Both neighbors at 0.40: residual variance ~0.41x -> sigma ~0.64x.
    CHECK(varOut < 0.75 * varC);
    CHECK(varOut > 0.15 * varC); // sanity: not flattened/clipped
    // No level shift: the merge moves noise, not content.
    const double dMean = meanOf(out, kTw, 32, 32, 224, 224)
                       - meanOf(c, kTw, 32, 32, 224, 224);
    CHECK(std::fabs(dMean) < 1.5);
}

TEST_CASE("metadata_noise_profile_drives_the_residual_gate") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const float sigma = 6.0f;
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 21, sigma);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 22, sigma);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalReport rep;

    // Exact profile for the actual noise: full weight, variance drops.
    const NoiseProfile exact = constantProfile(sigma);
    TemporalSettings ts;
    ts.strength = 100.0f;
    ts.centerNoise = &exact;
    ts.prevNoise = &exact;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK(rep.prev.eligible);
    CHECK(variance(out, kTw, 32, 32, 224, 224)
          < 0.8 * variance(c, kTw, 32, 32, 224, 224));

    // Absurdly small claimed sigma (0.5 DN for 6 DN noise): the sigma
    // taper rejects the static path, the gain-ratio/motion path finds no
    // consistent matches on flat noise, and the accept-rate gate drops
    // the whole neighbor -> exact copy.
    std::fill(out.begin(), out.end(), 0);
    const NoiseProfile tiny = constantProfile(0.5f);
    TemporalSettings ts2;
    ts2.strength = 100.0f;
    ts2.centerNoise = &tiny;
    ts2.prevNoise = &tiny;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts2, out.data(), &rep);
    CHECK_FALSE(rep.prev.eligible);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
}

// ---- safety gates ----

TEST_CASE("scene_cut_rejects_neighbor_exactly") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.30f), 31, 5.0f);
    // Same mean luma, completely different distribution: fine stripes
    // (mean 0.30, values 0.22/0.38) — fails the histogram gate, not the
    // mean-luma gate: this is specifically the scene-cut detector.
    auto stripes = [](uint32_t x, uint32_t) {
        return (x / 8) % 2 == 0 ? 0.22f : 0.38f;
    };
    std::vector<uint16_t> p = makeFrame(kTw, kTw, stripes, 32, 5.0f);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK_FALSE(rep.prev.eligible);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
}

TEST_CASE("sudden_exposure_jump_rejects_neighbor") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.50f), 41, 5.0f);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.62f), 42, 5.0f); // +24%
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK_FALSE(rep.prev.eligible);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
}

TEST_CASE("shift_beyond_radius_never_ghosts") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kLw, kLw);
    std::vector<uint16_t> c = makeFrame(kLw, kLw, texture, 51, 5.0f);
    std::vector<uint16_t> p = makeFrame(kLw, kLw, shiftedTexture(15, 0), 52, 5.0f);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    // No block can match within +-4 proxy px; the static path's
    // zero-motion residual is far beyond the noise model; the neighbor is
    // rejected and the output is the untouched center. A naive average
    // would smear the 120-px displacement across the whole frame (the
    // ghosting A/B).
    CHECK_FALSE(rep.prev.eligible);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
}

TEST_CASE("occluded_block_gets_zero_weight_background_merges") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const int objY0 = 48, objSize = 60; // inside proxy block (0,0)
    auto withObject = [&](int ox) {
        // ox is this lambda's parameter: capture BY VALUE in the returned
        // lambda — a by-reference capture dangles once withObject returns.
        return [&, ox](uint32_t x, uint32_t y) {
            return (static_cast<int>(x) >= ox && static_cast<int>(x) < ox + objSize
                    && static_cast<int>(y) >= objY0
                    && static_cast<int>(y) < objY0 + objSize)
                       ? 0.80f
                       : 0.30f;
        };
    };
    std::vector<uint16_t> c = makeFrame(kTw, kTw, withObject(48), 61, 5.0f);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, withObject(208), 62, 5.0f);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);

    // The static background blocks match; the moving-object blocks do not.
    CHECK(rep.prev.eligible);
    CHECK(rep.prev.acceptRate < 1.0f);
    CHECK(rep.prev.acceptRate >= 0.25f);
    // Object region (inside rejected block (0,0)): weight 0 -> pixels keep
    // the center value exactly (the normalize/denormalize round trip is
    // exact for integer inputs).
    for (uint32_t y = objY0; y < objY0 + objSize; ++y)
        for (uint32_t x = 48; x < 48 + objSize; ++x)
            CHECK(out[static_cast<size_t>(y) * kTw + x]
                  == c[static_cast<size_t>(y) * kTw + x]);
    // Background (block (1,1), far from both object positions): merged,
    // variance reduced (single neighbor: ~0.59x).
    CHECK(variance(out, kTw, 176, 176, 240, 240)
          < 0.8 * variance(c, kTw, 176, 176, 240, 240));
}

// ---- window clamping (DN-09) ----

TEST_CASE("first_and_last_frame_clamp_window_no_crash") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const float sigma = 6.0f;
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 71, sigma);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 72, sigma);
    std::vector<uint16_t> x = makeFrame(kTw, kTw, flat(0.3f), 73, sigma);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    TemporalReport rep;

    // First frame: no prev, next only.
    temporalMergeCfa(c.data(), info, nullptr, x.data(), m, ts, out.data(), &rep);
    CHECK(rep.next.eligible);
    CHECK(variance(out, kTw, 32, 32, 224, 224)
          < 0.85 * variance(c, kTw, 32, 32, 224, 224)); // one neighbor

    // Last frame: prev only.
    std::fill(out.begin(), out.end(), 0);
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK(rep.prev.eligible);
    CHECK(variance(out, kTw, 32, 32, 224, 224)
          < 0.85 * variance(c, kTw, 32, 32, 224, 224));

    // Lonely frame: no neighbors at all -> exact copy, no crash.
    std::fill(out.begin(), out.end(), 0);
    temporalMergeCfa(c.data(), info, nullptr, nullptr, m, ts, out.data(), &rep);
    CHECK(std::memcmp(out.data(), c.data(), out.size() * sizeof(uint16_t)) == 0);
}

TEST_CASE("mismatched_neighbor_dimensions_are_rejected") {
    // temporalMergeCfa reads every frame through the SHARED FrameInfo, so a
    // caller that lies about a neighbor's stride is a contract violation the
    // merge cannot observe — the JNI bridge enforces it by comparing the
    // neighbor frameInfos (dimensions + pattern) before calling. What the
    // core enforces is the proxy level: a neighbor whose proxy dims differ
    // from the center's never matches, so it can never blend.
    const RawMeta m = testMeta();

    // 264 CFA px wide -> 33 proxy px vs the center's 32.
    const FrameInfo infoWide = testInfo(kTw + 8, kTw);
    std::vector<uint16_t> wide = makeFrame(kTw + 8, kTw, flat(0.3f), 81, 6.0f);
    const LumaProxy pw = buildLumaProxy(wide.data(), infoWide, m);
    CHECK(pw.w == kTw / 8 + 1);

    const FrameInfo info = testInfo(kTw, kTw);
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 82, 6.0f);
    const LumaProxy pc = buildLumaProxy(c.data(), info, m);
    CHECK(pc.w == kTw / 8);

    // Dimension mismatch: matching refuses to run — the block grid comes
    // from the center and every vector stays the zero default, so a
    // mismatched neighbor contributes nothing anywhere.
    std::vector<MotionVector> mv;
    uint32_t mw = 99, mh = 99;
    matchProxyBlocks(pc, pw, mv, mw, mh);
    CHECK(mw == (kTw / 8) / 16);
    CHECK(mh == (kTw / 8) / 16);
    for (const MotionVector& v : mv) {
        CHECK(v.dx == 0);
        CHECK(v.dy == 0);
        CHECK(v.mad == 0.0f);
    }
}

// ---- temporal-distance decay of the weight cap (atadenoise) ----

TEST_CASE("distant_neighbor_weight_decays_with_temporal_distance") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const float sigma = 6.0f;
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 111, sigma);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 112, sigma);
    std::vector<uint16_t> out(c.size(), 0);
    TemporalReport rep;

    // Adjacent (dPts = 1 duration): full cap 0.40.
    TemporalSettings ts;
    ts.strength = 100.0f;
    ts.prevDPtsUs = 33333;
    ts.frameDurationUs = 33333;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK(rep.prev.eligible);
    CHECK(rep.prev.meanWeight == doctest::Approx(0.40f).epsilon(0.05));

    // 1.5 durations out: cap x (1/1.5) = 0.267 (atadenoise get_weights).
    std::fill(out.begin(), out.end(), 0);
    ts.prevDPtsUs = (33333 * 3) / 2;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK(rep.prev.eligible);
    CHECK(rep.prev.meanWeight == doctest::Approx(0.40f * (2.0f / 3.0f)).epsilon(0.05));

    // Unspecified (0): no decay — direct callers and tests.
    std::fill(out.begin(), out.end(), 0);
    ts.prevDPtsUs = 0;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), &rep);
    CHECK(rep.prev.eligible);
    CHECK(rep.prev.meanWeight == doctest::Approx(0.40f).epsilon(0.05));
}

// ---- statelessness: the scrub/ring-cache contract (DN-09) ----

TEST_CASE("merge_is_stateless_scrub_safe") {
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    const float sigma = 6.0f;
    // "Frame 10" window and "frame 40" window (different scenes).
    std::vector<uint16_t> c10 = makeFrame(kTw, kTw, flat(0.3f), 91, sigma);
    std::vector<uint16_t> p10 = makeFrame(kTw, kTw, flat(0.3f), 92, sigma);
    std::vector<uint16_t> c40 = makeFrame(kTw, kTw, flat(0.6f), 93, sigma);
    std::vector<uint16_t> n40 = makeFrame(kTw, kTw, flat(0.6f), 94, sigma);

    std::vector<uint16_t> a(c10.size()), b(c40.size()), a2(c10.size());
    TemporalSettings ts;
    ts.strength = 100.0f;
    // Simulated scrub: 10 (with its neighbor) -> 40 -> back to 10.
    temporalMergeCfa(c10.data(), info, p10.data(), nullptr, m, ts, a.data(), nullptr);
    temporalMergeCfa(c40.data(), info, nullptr, n40.data(), m, ts, b.data(), nullptr);
    temporalMergeCfa(c10.data(), info, p10.data(), nullptr, m, ts, a2.data(), nullptr);
    CHECK(a == a2); // no state leaked between renders
    // And each frame's output stays in its own scene.
    CHECK(std::fabs(meanOf(a, kTw, 32, 32, 224, 224) - (0.3 * kRange + kBlack)) < 3.0);
    CHECK(std::fabs(meanOf(b, kTw, 32, 32, 224, 224) - (0.6 * kRange + kBlack)) < 3.0);
}

TEST_CASE("output_buffer_never_aliases_inputs") {
    // The contract the ring cache relies on: the merge writes only to
    // `out`; inputs stay pristine (temporal output is never an input).
    const RawMeta m = testMeta();
    const FrameInfo info = testInfo(kTw, kTw);
    std::vector<uint16_t> c = makeFrame(kTw, kTw, flat(0.3f), 101, 6.0f);
    std::vector<uint16_t> p = makeFrame(kTw, kTw, flat(0.3f), 102, 6.0f);
    const std::vector<uint16_t> cCopy = c;
    const std::vector<uint16_t> pCopy = p;
    std::vector<uint16_t> out(c.size(), 0);
    TemporalSettings ts;
    ts.strength = 100.0f;
    temporalMergeCfa(c.data(), info, p.data(), nullptr, m, ts, out.data(), nullptr);
    CHECK(c == cCopy);
    CHECK(p == pCopy);
}

// ---- real clip (gated) ----

TEST_CASE("real_clip_temporal_merge_reduces_noise_and_reports_timing"
          * doctest::description("real clip; set RAWEDIT_REALCLIP=1 to run")) {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: set RAWEDIT_REALCLIP=1 (2 GB fixture, decode is slow)");
        return;
    }
    auto res = Source::open(realclipPath());
    REQUIRE(res.isOk());
    Source& src = **res.value;
    const size_t n = src.frameCount();
    REQUIRE(n > 10);
    const size_t mid = n / 2;

    const NoiseProfile npc = src.noiseProfile(mid);
    CHECK(npc.valid); // the 2026-11 fixtures carry the Camera2 profile
    MESSAGE("noiseProfile S0=" << npc.s[0] << " O0=" << npc.o[0]);

    const FrameInfo info = src.frameInfo(mid);
    auto fc = src.readFrame(mid);
    auto fp = src.readFrame(mid - 1);
    auto fn = src.readFrame(mid + 1);
    REQUIRE(fc.isOk());
    REQUIRE(fp.isOk());
    REQUIRE(fn.isOk());

    // PTS eligibility with the real cadence.
    const int64_t dur = src.frameInfo(1).ptsUs - src.frameInfo(0).ptsUs;
    CHECK(temporalNeighborEligible(src.frameInfo(mid - 1).ptsUs - info.ptsUs, dur));
    CHECK(temporalNeighborEligible(src.frameInfo(mid + 1).ptsUs - info.ptsUs, dur));

    const NoiseProfile npp = src.noiseProfile(mid - 1);
    const NoiseProfile npn = src.noiseProfile(mid + 1);
    TemporalSettings ts;
    ts.strength = 100.0f;
    ts.centerNoise = &npc;
    ts.prevNoise = &npp;
    ts.nextNoise = &npn;
    ts.prevDPtsUs = src.frameInfo(mid - 1).ptsUs - info.ptsUs;
    ts.nextDPtsUs = src.frameInfo(mid + 1).ptsUs - info.ptsUs;
    ts.frameDurationUs = dur;

    std::vector<uint16_t> out(fc->size(), 0);
    TemporalReport rep;
    temporalMergeCfa(fc->data(), info, fp->data(), fn->data(), src.rawMeta(),
                     ts, out.data(), &rep);
    MESSAGE("prev: eligible=" << rep.prev.eligible << " accept="
            << rep.prev.acceptRate << " meanW=" << rep.prev.meanWeight
            << " floor=" << rep.prev.noiseFloor);
    MESSAGE("next: eligible=" << rep.next.eligible << " accept="
            << rep.next.acceptRate << " meanW=" << rep.next.meanWeight
            << " floor=" << rep.next.noiseFloor);
    MESSAGE("timing ms: proxy=" << rep.proxyMs << " match=" << rep.matchMs
            << " merge=" << rep.mergeMs);

    // Whatever the gates decided, the output stays close to the center:
    // temporal NR moves noise, not content.
    double sumAbs = 0.0;
    for (size_t i = 0; i < out.size(); ++i)
        sumAbs += std::fabs(static_cast<double>(out[i]) - static_cast<double>((*fc)[i]));
    MESSAGE("mean |out-center| = " << sumAbs / out.size() << " DN");
    CHECK(sumAbs / out.size() < 4.0);

    // Variance on a central crop drops when any neighbor merged.
    if (rep.prev.eligible || rep.next.eligible) {
        const uint32_t w = info.width;
        const uint32_t x0 = w / 2 - 128, y0 = info.height / 2 - 128;
        const double varC = variance(*fc, w, x0, y0, x0 + 256, y0 + 256);
        const double varO = variance(out, w, x0, y0, x0 + 256, y0 + 256);
        MESSAGE("center-crop var " << varC << " -> " << varO);
        CHECK(varO < varC);
    }

    // Strength 0 must be bit-identical (the disabled-path contract).
    std::vector<uint16_t> off(fc->size(), 0);
    TemporalSettings ts0;
    temporalMergeCfa(fc->data(), info, fp->data(), fn->data(), src.rawMeta(),
                     ts0, off.data(), nullptr);
    CHECK(off == *fc);
}
