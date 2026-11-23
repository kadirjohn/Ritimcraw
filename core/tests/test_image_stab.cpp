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
#include <cstdio>
#include <string>
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

// ===========================================================================
// S2: luma, analyzer, analysis file
// ===========================================================================

namespace {
std::vector<std::vector<uint8_t>> pan(const Texture& tex, int n, double dx, double dy) {
    std::vector<std::vector<uint8_t>> f;
    for (int i = 0; i < n; ++i)
        f.push_back(render(tex, Truth{dx * i, dy * i, 1.0, 0.0}, 3.0, 128.0, 0.5, 300 + i));
    return f;
}
ImagePath analyzeAll(const std::vector<std::vector<uint8_t>>& fr, size_t upTo,
                     uint32_t expected) {
    ImageStabAnalyzer an(ImageStabOptions(), expected);
    for (size_t i = 0; i < upTo; ++i) an.push(view(fr[i]), static_cast<int64_t>(i) * 33333);
    return an.path();
}
bool sameRecs(const ImagePath& a, const ImagePath& b) {
    if (a.recs.size() != b.recs.size()) return false;
    for (size_t i = 0; i < a.recs.size(); ++i) {
        const auto& x = a.recs[i];
        const auto& y = b.recs[i];
        if (x.ptsUs != y.ptsUs || x.tx != y.tx || x.ty != y.ty || x.logS != y.logS ||
            x.theta != y.theta || x.inlierRatio8 != y.inlierRatio8 ||
            x.coverage8 != y.coverage8 || x.nInliers != y.nInliers ||
            x.residualMilliPx != y.residualMilliPx || x.status != y.status)
            return false;
    }
    return true;
}
}  // namespace

TEST_CASE("analyzer: path of a steady pan matches the truth per pair") {
    const Texture tex(91);
    const auto fr = pan(tex, 10, 2.3, -1.1);
    const ImagePath p = analyzeAll(fr, fr.size(), 10);
    REQUIRE(p.recs.size() == 10);
    CHECK(p.complete);
    CHECK(p.workW == static_cast<uint32_t>(W));
    CHECK(p.recs[0].tx == 0.0f);
    for (size_t i = 1; i < p.recs.size(); ++i) {
        CHECK(p.recs[i].status == static_cast<uint8_t>(PairStatus::Ok));
        CHECK(std::fabs(p.recs[i].tx * H - 2.3) < 0.1);
        CHECK(std::fabs(p.recs[i].ty * H + 1.1) < 0.1);
        CHECK(p.recs[i].ptsUs == static_cast<int64_t>(i) * 33333);
    }
}

TEST_CASE("analyzer: resume from a checkpoint is bit-identical") {
    const Texture tex(92);
    const auto fr = pan(tex, 12, 1.7, 0.9);
    const ImagePath whole = analyzeAll(fr, fr.size(), 12);
    // First 7 frames, then continue from the partial path.
    ImagePath partial = analyzeAll(fr, 7, 12);
    CHECK_FALSE(partial.complete);
    ImageStabAnalyzer an(ImageStabOptions(), 12);
    REQUIRE(an.resume(partial));
    an.seed(view(fr[6]));
    for (size_t i = 7; i < fr.size(); ++i)
        an.push(view(fr[i]), static_cast<int64_t>(i) * 33333);
    CHECK(sameRecs(an.path(), whole));
    CHECK(an.path().complete);
    // A path for a different clip length does not resume.
    ImageStabAnalyzer other(ImageStabOptions(), 99);
    CHECK_FALSE(other.resume(partial));
}

TEST_CASE("analysis file: round trip, CRC, truncation, stale version") {
    const Texture tex(93);
    const ImagePath p = analyzeAll(pan(tex, 6, 1.0, 0.5), 6, 6);
    const std::vector<uint8_t> b = serializeImagePath(p);
    CHECK(b.size() == 56 + 6 * 32);
    ImagePath q;
    REQUIRE(parseImagePath(b.data(), b.size(), q) == PathParse::Ok);
    CHECK(sameRecs(p, q));
    CHECK(q.complete == p.complete);
    CHECK(q.frameCount == p.frameCount);
    CHECK(q.workW == p.workW);
    // Re-serializing the parsed copy is byte-identical.
    CHECK(serializeImagePath(q) == b);

    std::vector<uint8_t> bad = b;
    bad[56 + 40] ^= 0x01;  // flip a bit inside a record
    CHECK(parseImagePath(bad.data(), bad.size(), q) == PathParse::BadCrc);
    bad = b;
    bad[0] ^= 0xFF;
    CHECK(parseImagePath(bad.data(), bad.size(), q) == PathParse::BadMagic);
    CHECK(parseImagePath(b.data(), b.size() - 5, q) == PathParse::Truncated);
    CHECK(parseImagePath(b.data(), 10, q) == PathParse::Truncated);
    bad = b;
    bad[8] = 99;  // a different analysisVersion = stale
    CHECK(parseImagePath(bad.data(), bad.size(), q) == PathParse::BadVersion);

    // File wrapper (atomic write) round trip, in the working directory.
    const std::string f = "ritim_image_stab_test.ristab";
    REQUIRE(writeImagePathFile(f, p));
    ImagePath r;
    REQUIRE(readImagePathFile(f, r) == PathParse::Ok);
    CHECK(sameRecs(p, r));
    std::remove(f.c_str());
    CHECK(readImagePathFile(f, r) != PathParse::Ok);
}

TEST_CASE("priorFromRecords keeps the velocity for two lost frames") {
    std::vector<PairRecord> recs(6);
    for (auto& r : recs) r.status = static_cast<uint8_t>(PairStatus::Ok);
    recs[3].tx = 0.01f;
    PairMotion m;
    CHECK_FALSE(priorFromRecords(recs, 1, m));   // only the identity record
    CHECK(priorFromRecords(recs, 4, m));
    CHECK(m.tx == doctest::Approx(0.01f));
    recs[3].status = static_cast<uint8_t>(PairStatus::Lost);
    CHECK(priorFromRecords(recs, 5, m));         // record 4 is still usable
    recs[4].status = static_cast<uint8_t>(PairStatus::Lost);
    CHECK_FALSE(priorFromRecords(recs, 5, m));   // both lost: no prior
}

TEST_CASE("cfaToLuma: black level, 2x2 equal weights, area average, size") {
    const int cw = 1280, ch = 960;  // CFA size
    std::vector<uint16_t> cfa(static_cast<size_t>(cw) * ch, 64);  // == black
    const int32_t black[4] = {64, 64, 64, 64};
    std::vector<uint8_t> l;
    int w = 0, h = 0;
    REQUIRE(cfaToLuma(cfa.data(), cw, ch, black, 1023, 320, l, w, h));
    CHECK(w == 320);  // cells 640 / factor 2
    CHECK(h == 240);
    for (uint8_t v : l) CHECK(v == 0);
    // Full-scale -> 255.
    std::fill(cfa.begin(), cfa.end(), 1023);
    REQUIRE(cfaToLuma(cfa.data(), cw, ch, black, 1023, 320, l, w, h));
    for (uint8_t v : l) CHECK(v == 255);
    // Equal weights: two of the four sites lit == half signal.
    for (int y = 0; y < ch; ++y)
        for (int x = 0; x < cw; ++x)
            cfa[static_cast<size_t>(y) * cw + x] = ((y & 1) == (x & 1)) ? 1023 : 64;
    REQUIRE(cfaToLuma(cfa.data(), cw, ch, black, 1023, 320, l, w, h));
    const int expect = static_cast<int>(std::sqrt(0.5) * 255.0 + 0.5);
    CHECK(l[100] == expect);
    // Per-slot black: a frame whose slot 2 black is higher stays dark where
    // every site equals its own black.
    const int32_t black2[4] = {64, 64, 200, 64};
    for (int y = 0; y < ch; ++y)
        for (int x = 0; x < cw; ++x)
            cfa[static_cast<size_t>(y) * cw + x] = ((y & 1) && !(x & 1)) ? 200 : 64;
    REQUIRE(cfaToLuma(cfa.data(), cw, ch, black2, 1023, 320, l, w, h));
    for (uint8_t v : l) CHECK(v == 0);
    // Invalid input.
    CHECK_FALSE(cfaToLuma(nullptr, cw, ch, black, 1023, 320, l, w, h));
}

// ===========================================================================
// S3: plan (path, smoothing/lock, dynamic zoom, budget, exact coverage)
// ===========================================================================

#include <rawedit/Warp.h>

namespace {

// A synthetic handheld pair-record series: slow intentional drift + shake
// (an AR(1) velocity) + optional jumps and lost stretches. Normalized units.
std::vector<PairRecord> shakyRecords(uint64_t seed, int n, double shake, double drift,
                                     double jumpProb = 0.0, double lostProb = 0.0,
                                     double fps = 30.0) {
    Rnd r(seed);
    auto g = [&]() { return (r.next() + r.next() + r.next() - 1.5) * 2.0; };  // ~N(0,1)
    std::vector<PairRecord> out;
    double vx = 0, vy = 0, vr = 0;
    for (int i = 0; i < n; ++i) {
        PairMotion m;
        m.status = PairStatus::Ok;
        m.inlierRatio = 0.9f;
        m.coverage = 1.0f;
        if (i > 0) {
            vx = 0.7 * vx + shake * g();
            vy = 0.7 * vy + shake * g();
            vr = 0.7 * vr + shake * 0.8 * g();
            m.tx = vx + drift;
            m.ty = vy + drift * 0.5;
            m.theta = vr;
            m.logS = shake * 0.1 * g();
            if (jumpProb > 0 && r.next() < jumpProb) {
                m.tx += (r.next() - 0.5) * 0.3;
                m.ty += (r.next() - 0.5) * 0.3;
            }
            if (lostProb > 0 && r.next() < lostProb) {
                m = PairMotion{};
                m.status = PairStatus::Lost;
            }
        }
        out.push_back(toRecord(static_cast<int64_t>(std::llround(i * 1e6 / fps)), m));
    }
    return out;
}

bool sameFrames(const ImageStabPlan& a, const ImageStabPlan& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        const auto& x = a.frame(i);
        const auto& y = b.frame(i);
        if (x.zoom != y.zoom || x.a != y.a || x.corr.s != y.corr.s ||
            x.corr.theta != y.corr.theta || x.corr.tx != y.corr.tx || x.corr.ty != y.corr.ty)
            return false;
    }
    return true;
}

}  // namespace

TEST_CASE("plan: strength 0 and an empty or 1-frame clip are exact pass-through") {
    ImageStabParams p;
    p.strength = 0.0;
    const auto recs = shakyRecords(1, 60, 0.004, 0.0005);
    const ImageStabPlan pl = ImageStabPlan::build(recs, p, 0, 59, 16.0 / 9.0);
    REQUIRE(pl.size() == 60);
    for (size_t i = 0; i < pl.size(); ++i) {
        CHECK(pl.frame(i).zoom == 1.0);
        double M[9];
        bool active = true;
        REQUIRE(frameWarpM(pl.frame(i), 1280, 720, M, &active));
        CHECK_FALSE(active);
    }
    CHECK(ImageStabPlan::build({}, ImageStabParams(), 0, 0, 1.5).size() == 0);
    const ImageStabPlan one = ImageStabPlan::build(shakyRecords(2, 1, 0.01, 0), ImageStabParams(), 0, 0, 1.5);
    REQUIRE(one.size() == 1);
    CHECK(one.frame(0).zoom == 1.0);
}

TEST_CASE("plan: smoothing removes shake (acceleration and jerk drop)") {
    const auto recs = shakyRecords(3, 240, 0.003, 0.0004);
    ImageStabParams p;
    p.strength = 0.5;
    p.zoomLimit = 1.5;
    const ImageStabPlan pl = ImageStabPlan::build(recs, p, 0, 239, 16.0 / 9.0);
    // RMS of the 2nd and 3rd difference of the x path, raw vs stabilized
    // output path (the target the camera ends up following).
    auto rms = [&](const std::vector<Sim2>& path, int order) {
        std::vector<double> x;
        for (const Sim2& s : path) x.push_back(s.tx);
        for (int o = 0; o < order; ++o) {
            std::vector<double> d;
            for (size_t i = 1; i < x.size(); ++i) d.push_back(x[i] - x[i - 1]);
            x = d;
        }
        double ss = 0;
        for (double v : x) ss += v * v;
        return std::sqrt(ss / static_cast<double>(x.size()));
    };
    // Output path = corr o raw.
    std::vector<Sim2> out;
    for (size_t i = 0; i < pl.size(); ++i)
        out.push_back(simCompose(pl.frame(i).corr, pl.rawPath()[i]));
    CHECK(rms(out, 2) < 0.2 * rms(pl.rawPath(), 2));
    CHECK(rms(out, 3) < 0.2 * rms(pl.rawPath(), 3));
    CHECK(pl.stats().invalidSampleFrames == 0);
}

TEST_CASE("plan: requiredZoom is the tight closed form") {
    Rnd r(5);
    for (int k = 0; k < 300; ++k) {
        Sim2 c;
        c.s = std::exp((r.next() - 0.5) * 0.1);
        c.theta = (r.next() - 0.5) * 0.1;
        c.tx = (r.next() - 0.5) * 0.08;
        c.ty = (r.next() - 0.5) * 0.08;
        const double aspect = 16.0 / 9.0;
        const double z = requiredZoom(c, aspect, 0.0);
        REQUIRE(std::isfinite(z));
        CHECK(z >= 1.0);
        // Continuous box (no margin): at z every corner is inside...
        auto minSlack = [&](double zoom) {
            const Sim2 inv = simInverse(c);
            double worst = 1e9;
            for (double sx : {-aspect * 0.5, aspect * 0.5})
                for (double sy : {-0.5, 0.5}) {
                    const double qx = sx / zoom, qy = sy / zoom;
                    const double cs = std::cos(inv.theta), sn = std::sin(inv.theta);
                    const double px = inv.s * (cs * qx - sn * qy) + inv.tx;
                    const double py = inv.s * (sn * qx + cs * qy) + inv.ty;
                    worst = std::min({worst, px + aspect * 0.5, aspect * 0.5 - px, py + 0.5, 0.5 - py});
                }
            return worst;
        };
        CHECK(minSlack(z) >= -1e-12);
        if (z > 1.0 + 1e-9) CHECK(minSlack(z * 0.995) < 0.0);  // ...and it is tight
    }
    // A translation larger than the frame cannot be covered by any zoom.
    Sim2 far;
    far.tx = 5.0;
    CHECK(std::isinf(requiredZoom(far, 16.0 / 9.0, 0.0)));
}

TEST_CASE("plan: the edge is never visible (5000 random walks, all modes)") {
    struct Asp { int w, h; };
    const Asp asp[] = {{1280, 720}, {1440, 1080}, {720, 1280}};
    int totalFrames = 0, limitedPlans = 0;
    for (int k = 0; k < 5000; ++k) {
        Rnd r(1000 + k);
        const double shake = 0.0005 + r.next() * 0.02;     // up to far above the limit
        const double drift = (r.next() - 0.5) * 0.004;
        const auto recs = shakyRecords(5000 + k, 50 + static_cast<int>(r.next() * 60), shake,
                                       drift, 0.03, 0.04, r.next() < 0.5 ? 30.0 : 60.0);
        ImageStabParams p;
        p.mode = (k % 3 == 0) ? 1 : (k % 3 == 1 ? 0 : 2);   // lock / smooth / horizon->smooth
        p.strength = 0.1 + 0.9 * r.next();
        p.zoomLimit = 1.0 + 0.5 * r.next();
        p.zoomRate = (k % 4 == 0) ? 0.0 : 0.05 + 0.2 * r.next();
        const Asp a = asp[k % 3];
        const size_t n = recs.size();
        const ImageStabPlan pl = ImageStabPlan::build(recs, p, n / 5, n - 1 - n / 5,
                                                      static_cast<double>(a.w) / a.h);
        REQUIRE(pl.stats().invalidSampleFrames == 0);
        if (pl.stats().limitedFraction > 0) ++limitedPlans;
        for (size_t i = 0; i < n; ++i) {
            const ImageStabFrame& f = pl.frame(i);
            totalFrames++;
            if (!(f.zoom >= 1.0 && f.zoom <= p.zoomLimit + 1e-6)) FAIL("zoom out of [1, limit]");
            // The runtime composition at TWO tiers (export / preview sizes).
            if (coverageSlackPx(f.corr, f.zoom, a.w, a.h) < -0.05) FAIL("edge visible (tier 1)");
            if (coverageSlackPx(f.corr, f.zoom, a.w / 2, a.h / 2) < -0.05) FAIL("edge visible (tier 2)");
        }
    }
    CHECK(totalFrames > 200000);
    CHECK(limitedPlans > 500);  // the walks do exceed the limit: the budget is exercised
}

TEST_CASE("plan: no clamped samples (edges and corners) through the shared matrix") {
    const auto recs = shakyRecords(7, 120, 0.006, 0.0006);
    ImageStabParams p;
    p.zoomLimit = 1.2;
    const int W2 = 640, H2 = 360;
    const ImageStabPlan pl = ImageStabPlan::build(recs, p, 0, 119, static_cast<double>(W2) / H2);
    int clamped = 0;
    for (size_t i = 0; i < pl.size(); ++i) {
        double M[9];
        bool active;
        REQUIRE(frameWarpM(pl.frame(i), W2, H2, M, &active));
        // every border output pixel centre
        for (int t = 0; t < W2; ++t)
            for (int y : {0, H2 - 1}) {
                const double sx = M[0] * t + M[1] * y + M[2], sy = M[3] * t + M[4] * y + M[5];
                if (sx < -0.05 || sx > W2 - 1 + 0.05 || sy < -0.05 || sy > H2 - 1 + 0.05) ++clamped;
            }
        for (int t = 0; t < H2; ++t)
            for (int x : {0, W2 - 1}) {
                const double sx = M[0] * x + M[1] * t + M[2], sy = M[3] * x + M[4] * t + M[5];
                if (sx < -0.05 || sx > W2 - 1 + 0.05 || sy < -0.05 || sy > H2 - 1 + 0.05) ++clamped;
            }
    }
    CHECK(clamped == 0);
}

TEST_CASE("composeWarpM is the matrix warpFrame samples through") {
    const int w = 96, h = 64;
    ImageF src;
    src.w = w;
    src.h = h;
    src.rgb.resize(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float* p = &src.rgb[(static_cast<size_t>(y) * w + x) * 3];
            p[0] = static_cast<float>(x);      // a linear ramp: bilinear is exact
            p[1] = static_cast<float>(y);
            p[2] = 0.0f;
        }
    Sim2 c;
    c.s = 1.01;
    c.theta = 0.012;
    c.tx = 0.006;
    c.ty = -0.004;
    const double zoom = 1.12;
    double H[9], M[9];
    simToHomographyPx(c, w, h, H);
    REQUIRE(composeWarpM(H, zoom, w, h, M));
    const ImageF out = warpFrame(src, H, zoom, w * 0.5, h * 0.5);
    REQUIRE(out.w == static_cast<uint32_t>(w));
    double worst = 0;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const double sx = M[0] * x + M[1] * y + M[2], sy = M[3] * x + M[4] * y + M[5];
            if (sx < 0 || sx > w - 1 || sy < 0 || sy > h - 1) continue;  // clamped in warpFrame
            const float* p = &out.rgb[(static_cast<size_t>(y) * w + x) * 3];
            worst = std::max({worst, std::fabs(p[0] - sx), std::fabs(p[1] - sy)});
        }
    CHECK(worst < 1e-3);
}

TEST_CASE("plan: preview and export tiers map the same normalized geometry") {
    const auto recs = shakyRecords(9, 100, 0.005, 0.0005);
    const ImageStabPlan pl = ImageStabPlan::build(recs, ImageStabParams(), 0, 99, 16.0 / 9.0);
    for (size_t i = 0; i < pl.size(); i += 7) {
        double Ma[9], Mb[9];
        bool act;
        REQUIRE(frameWarpM(pl.frame(i), 1280, 720, Ma, &act));
        REQUIRE(frameWarpM(pl.frame(i), 3840, 2160, Mb, &act));
        for (double u : {-0.8, 0.0, 0.7})
            for (double v : {-0.5, 0.1, 0.5}) {
                auto map = [&](const double* M, double h) {
                    const double w = h * 16.0 / 9.0;
                    const double x = w * 0.5 + u * h, y = h * 0.5 + v * h;
                    return std::pair<double, double>{
                        ((M[0] * x + M[1] * y + M[2]) - w * 0.5) / h,
                        ((M[3] * x + M[4] * y + M[5]) - h * 0.5) / h};
                };
                const auto pa = map(Ma, 720.0), pb = map(Mb, 2160.0);
                CHECK(std::fabs(pa.first - pb.first) < 1e-9);
                CHECK(std::fabs(pa.second - pb.second) < 1e-9);
            }
    }
}

TEST_CASE("plan: range independence (Smooth) and range dependence (Lock)") {
    const auto recs = shakyRecords(11, 150, 0.004, 0.001);
    ImageStabParams p;
    const ImageStabPlan s1 = ImageStabPlan::build(recs, p, 0, 149, 1.7);
    const ImageStabPlan s2 = ImageStabPlan::build(recs, p, 30, 90, 1.7);
    CHECK(sameFrames(s1, s2));
    p.mode = 1;
    const ImageStabPlan l1 = ImageStabPlan::build(recs, p, 0, 149, 1.7);
    const ImageStabPlan l2 = ImageStabPlan::build(recs, p, 30, 90, 1.7);
    CHECK_FALSE(sameFrames(l1, l2));
    // Lock target is constant = the range median of the path.
    for (size_t i = 1; i < l1.size(); ++i) {
        CHECK(l1.targetPath()[i].tx == l1.targetPath()[0].tx);
        CHECK(l1.targetPath()[i].theta == l1.targetPath()[0].theta);
    }
}

TEST_CASE("plan: deterministic, and zoom stays inside its own rate limit") {
    const auto recs = shakyRecords(13, 200, 0.006, 0.0008, 0.02, 0.02);
    ImageStabParams p;
    p.zoomLimit = 1.3;
    p.zoomRate = 0.10;
    const ImageStabPlan a = ImageStabPlan::build(recs, p, 0, 199, 16.0 / 9.0);
    const ImageStabPlan b = ImageStabPlan::build(recs, p, 0, 199, 16.0 / 9.0);
    CHECK(sameFrames(a, b));
    CHECK(a.stats().maxZoomRate <= 0.10 * (1.0 + 1e-6));
    CHECK(a.stats().maxZoom <= 1.3 + 1e-6);
}

TEST_CASE("plan: lost stretches fade the correction instead of inventing motion") {
    auto recs = shakyRecords(15, 120, 0.0015, 0.0);
    for (size_t i = 50; i < 70; ++i) {
        PairMotion m;
        m.status = PairStatus::Lost;
        recs[i] = toRecord(recs[i].ptsUs, m);
    }
    ImageStabParams p;
    p.zoomLimit = 1.5;
    const ImageStabPlan pl = ImageStabPlan::build(recs, p, 0, 119, 16.0 / 9.0);
    CHECK(pl.frame(60).a < 0.05);   // deep inside the gap: no correction
    CHECK(pl.frame(20).a > 0.9);    // well-tracked part: full correction
    CHECK(pl.stats().invalidSampleFrames == 0);
}

// ---- gyrocheck helpers (S2 cross-check derivation; image_stab_gyro.hpp) ------------

#include "image_stab_gyro.hpp"

#include <rawedit/Gyro.h>
#include <rawedit/OrientationEstimate.h>

namespace {

// A quaternion for a small right-handed rotation of `deg` about axis.
void quatFromAxisAngleDeg(double ax, double ay, double az, double deg, double out[4]) {
    const double rad = deg * 3.14159265358979323846 / 180.0;
    const double half = rad * 0.5;
    const double n = std::sqrt(ax * ax + ay * ay + az * az);
    out[0] = std::cos(half);
    out[1] = ax / n * std::sin(half);
    out[2] = ay / n * std::sin(half);
    out[3] = az / n * std::sin(half);
}

}  // namespace

TEST_CASE("gyrocheck helper: identity orientation gives zero motion") {
    const double id[4] = {1, 0, 0, 0};
    const GyroPairImage m = gyroPairImageMotion(id, id, 1000.0);
    CHECK(m.tx == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(m.ty == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(m.theta == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(m.s == doctest::Approx(1.0).epsilon(1e-12));
}

TEST_CASE("gyrocheck helper: a pure pan predicts a pure horizontal shift") {
    // Camera pans by 0.30 deg about its Y axis (X right, Y up, Z to viewer).
    // The pinhole geometry gives |t| = f * |tan(phi)| with the sign pinned
    // by this test. The perspective also rescales the off-center ray by
    // 1/cos(phi) (a similarity approximation error of ~1e-4 at this angle).
    const double id[4] = {1, 0, 0, 0};
    double pan[4];
    quatFromAxisAngleDeg(0, 1, 0, 0.30, pan);
    const double f = 1000.0;
    const GyroPairImage m = gyroPairImageMotion(id, pan, f);
    const double expect = f * std::tan(0.30 * 3.14159265358979323846 / 180.0);
    CHECK(std::fabs(m.tx) == doctest::Approx(expect).epsilon(1e-9));
    CHECK(m.ty == doctest::Approx(0.0).epsilon(1e-9));
    CHECK(m.theta == doctest::Approx(0.0).epsilon(1e-9));
    CHECK(m.s == doctest::Approx(1.0).epsilon(1e-4));
    // Sign: a +Y rotation (left in the camera's world frame) must move the
    // scene to +u (right in the image); pin it explicitly.
    CHECK(m.tx > 0);
}

TEST_CASE("gyrocheck helper: a pure tilt predicts a pure vertical shift") {
    const double id[4] = {1, 0, 0, 0};
    double tilt[4];
    quatFromAxisAngleDeg(1, 0, 0, 0.20, tilt);  // +X rotation: up-tilt
    const double f = 800.0;
    const GyroPairImage m = gyroPairImageMotion(id, tilt, f);
    const double expect = f * std::tan(0.20 * 3.14159265358979323846 / 180.0);
    CHECK(std::fabs(m.ty) == doctest::Approx(expect).epsilon(1e-9));
    CHECK(m.tx == doctest::Approx(0.0).epsilon(1e-9));
    CHECK(m.theta == doctest::Approx(0.0).epsilon(1e-9));
}

TEST_CASE("gyrocheck helper: a pure roll predicts theta = +rho and no shift") {
    // A +rho roll about camera +Z (towards the viewer, right-handed) leaves
    // the center ray fixed; the image content rotates by the SAME signed
    // angle in the y-down image frame's CCW convention (pinned here; the
    // scene-vs-camera intuition is easy to get wrong — the test is the
    // contract the gyrocheck scan relies on).
    const double id[4] = {1, 0, 0, 0};
    const double rho = 0.15;  // rad, small
    double roll[4];
    quatFromAxisAngleDeg(0, 0, 1, rho * 180.0 / 3.14159265358979323846, roll);
    const GyroPairImage m = gyroPairImageMotion(id, roll, 900.0);
    CHECK(m.theta == doctest::Approx(rho).epsilon(1e-9));
    CHECK(m.tx == doctest::Approx(0.0).epsilon(1e-9));
    CHECK(m.ty == doctest::Approx(0.0).epsilon(1e-9));
    CHECK(m.s == doctest::Approx(1.0).epsilon(1e-9));
}

TEST_CASE("gyrocheck helper: integrateGyro of a known rate reproduces the roll channel") {
    // A constant 3 deg/s roll integrates to a 0.1 deg step per (1/30) s;
    // the helper recovers the step in theta with the pin-verified sign.
    std::vector<ImuSample> g;
    const double w = 3.0 * 3.14159265358979323846 / 180.0;  // rad/s about +Z
    const int64_t dt = 1000000000LL / 30;                     // 30 fps in ns
    for (int i = 0; i <= 30; ++i)
        g.push_back({i * dt, 0.0f, 0.0f, float(w)});
    const OrientationSeries s = integrateGyro(g);
    double qPrev[4], qCur[4];
    s.orientationAt(10 * dt, qPrev);
    s.orientationAt(11 * dt, qCur);
    const GyroPairImage m = gyroPairImageMotion(qPrev, qCur, 1000.0);
    CHECK(m.theta == doctest::Approx(w * dt * 1e-9).epsilon(1e-6));
    CHECK(m.tx == doctest::Approx(0.0).epsilon(1e-6));
    CHECK(m.ty == doctest::Approx(0.0).epsilon(1e-6));
}

TEST_CASE("gyrocheck helper: compareGyroImage counts, skips and correlates") {
    // 5 pairs, one Lost and one outside the gyro span; the measured x
    // carries a 4 px bias on top of a large true signal so the
    // through-origin slope stays near 1.
    const double f = 1000.0;
    const double id[4] = {1, 0, 0, 0};
    std::vector<GyroPairImage> pred(6);
    std::vector<PairMotion> meas(6, PairMotion{});
    std::vector<uint8_t> inside(6, 1);
    for (size_t i = 1; i < 6; ++i) {
        double pan[4];
        quatFromAxisAngleDeg(0, 1, 0, 0.6 * double(i), pan);  // ~10.5 px/deg * i
        pred[i] = gyroPairImageMotion(id, pan, f);
        meas[i].status = PairStatus::Ok;
        meas[i].tx = float((pred[i].tx - 4.0) / 360.0);  // 4 px bias, workH 360
        meas[i].ty = 0;
    }
    meas[3].status = PairStatus::Lost;
    inside[4] = 0;  // pair 4 outside the gyro span
    const GyroCompare c = compareGyroImage(pred, meas, 360.0, inside);
    CHECK(c.n == 3);  // 5 usable minus 1 Lost minus 1 outside
    CHECK(c.nLost == 1);
    CHECK(c.nOutside == 1);
    CHECK(c.txRms == doctest::Approx(4.0).epsilon(1e-6));    // the bias is the residual
    CHECK(c.txSlope == doctest::Approx(0.85).epsilon(0.05)); // bias pulls, signal dominates
    CHECK(c.txCorr > 0.99);
}
