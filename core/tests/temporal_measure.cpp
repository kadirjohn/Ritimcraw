// temporal_measure — Temporal NR round-2 measurement harness
// (docs/TEMPORAL_NR.md). Desktop measurement tool, not a test; built by
// tools/build-desktop.sh, run by hand.
//
// Usage:
//   temporal_measure noise <clip.mcraw> <centerFrame> [strength=100]
//   temporal_measure ghost [strength=100]
//
// noise — REAL-CLIP NOISE METRIC (precise definition in docs/TEMPORAL_NR.md):
// decodes 7 frames around <centerFrame>, merges the 5 interior frames with
// their real PTS windows (the bridge contract: frameDuration = median of the
// two adjacent deltas), and measures the per-pixel TEMPORAL STD across the 5
// raw centers and across the 5 merged outputs on the STATIC REGION — the
// plateau cores ([32,96) mod 128 both axes, the region feathering never
// touches) of the blocks whose zero-motion proxy MAD0 is at or below
// 2.5x the noise model's expected |proxy difference| on BOTH adjacent pairs
// of the middle window. Reports the MEDIAN and the STD of the per-pixel
// temporal stds (DN) — the task's definition — plus a decorrelated
// single-pair estimator (see the doc: consecutive merged outputs share raw
// frames, so the 5-output temporal std reads low; the |NR1 - NR5|/sqrt(2)
// estimator across two DISJOINT windows does not).
//
// ghost — SYNTHETIC MOVING-EDGE SCENE with a clean reference (documented
// seed 20261126, deterministic LCG 4-uniform-sum noise, variance exact):
// 3840x2160 CFA (cam72 geometry), black 64 / white 1023, plate = white-noise
// texture 0.30 + 0.20*u, a hard-edged 384x384 object (level 0.85, step ~431
// DN over the plate mean) moving +vx CFA px/frame along x, per-frame noise
// sigma = 8 DN (cam72's measured band; the doc pins the exact number and
// command). Speed sweep 1, 2, 4, 8, 24 px/frame (0.125 .. 3 proxy px — the
// integer match grid is 8 CFA px, so 1..7 is sub-grid). Per speed the tool
// merges the middle window with an exact noise profile and reports, against
// the noiseless center render:
//   band (pixels whose clean content differs between center and either
//        neighbor = exactly where a warped sample can inject wrong-time
//        content, the ghost-risk region):
//     G_MAF  = mean |NR - clean_c| over the band (DN)
//     G_bias = |mean (NR - clean_c) over the band| (DN) — the systematic
//              ghost term (noise averages out; a trailing smear does not)
//     band_std = std(NR - clean_c) over the band (DN) — residual noise near
//              motion
//   edge lag: sub-pixel position (50% crossing of the row-mean profile) of
//     the leading and trailing object edges in NR vs clean, in CFA px
//     (positive = the NR edge sits ahead of the truth in the motion
//     direction; a trailing smear reads negative on the trailing edge)
//   static (a far strip the object never reaches): std(NR - clean),
//     std(C - clean) = the injected sigma, for scale
//   the merge report (accept, meanWeight, floor, mafd) per side
//
// No fixture is downloaded, nothing is assumed: every number printed comes
// from this binary on this machine.

#include <rawedit/Source.h>
#include <rawedit/Temporal.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// ---------------- shared helpers ----------------

// Deterministic noise LCG (same family as test_temporal: 4-uniform sum,
// variance exactly sigma^2, bounded +-3.46 sigma).
struct Lcg {
    uint32_t s;
    explicit Lcg(uint32_t seed) : s(seed ? seed : 1u) {}
    float u() {
        s = 1664525u * s + 1013904223u;
        return static_cast<float>(s >> 8) / 16777216.0f;
    }
    float gauss(float sigma) {
        const float sum = u() + u() + u() + u() - 2.0f;
        return sum * sigma * 1.7320508f;
    }
};

constexpr uint16_t kBlack = 64, kWhite = 1023;
constexpr float kRange = static_cast<float>(kWhite - kBlack);

// 2x2 cell-position slot, same indexing as Temporal.cpp's cellSlot.
inline int cellSlot(uint32_t x, uint32_t y) {
    return static_cast<int>(((y & 1u) << 1) | (x & 1u));
}

double medianOf(std::vector<double>& v) {
    if (v.empty()) return 0.0;
    const size_t k = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + static_cast<long>(k), v.end());
    double m = v[k];
    if (v.size() % 2 == 0) {
        const double a = *std::max_element(v.begin(), v.begin() + static_cast<long>(k));
        m = (m + a) * 0.5;
    }
    return m;
}

// Zero-motion block MAD on the proxies (reimplementation of Temporal.cpp's
// blockMadFull(0,0) for the harness's static-region detection): mean over
// the 16x16 proxy block of |a - b - meanOffset|, meanOffset = the pair's
// global proxy mean difference (the production de-bias).
float blockMad0(const LumaProxy& a, const LumaProxy& b, uint32_t bx, uint32_t by,
                float meanOffset) {
    double sad = 0.0;
    for (uint32_t y = 0; y < 16; ++y)
        for (uint32_t x = 0; x < 16; ++x)
            sad += std::fabs((a.v[static_cast<size_t>(by + y) * a.w + (bx + x)]
                            - b.v[static_cast<size_t>(by + y) * b.w + (bx + x)])
                           - meanOffset);
    return static_cast<float>(sad / 256.0);
}

float proxyMeanDiff(const LumaProxy& c, const LumaProxy& n) {
    double mc = 0.0, mn = 0.0;
    for (size_t i = 0; i < c.v.size(); ++i) {
        mc += c.v[i];
        mn += n.v[i];
    }
    return static_cast<float>(mc / static_cast<double>(c.v.size())
                             - mn / static_cast<double>(n.v.size()));
}

// Expected |proxy pixel difference| under the noise model. One proxy pixel
// is the mean of 4 CFA samples; two independent frames' proxy pixels
// differ with variance (varA + varB)/4 = var/2, where var = S*signal + O is
// the Camera2 single-sample variance (fallback sigma^2 without a profile).
// E|N(0, s)| = s*sqrt(2/pi) = 0.79788*s.
float expectedProxyAbsDiff(const NoiseProfile* np, float signal) {
    float var1;
    if (np && np->valid) {
        var1 = np->s[1] * signal + np->o[1]; // slot 1: green, majority slot
    } else {
        var1 = temporalFallbackSigma() * temporalFallbackSigma();
    }
    return 0.7978845608f * std::sqrt(var1 * 0.5f);
}

// Static mask for the noise command (shared helper). Returns a byte mask
// (w*h), 1 = static (plateau core of a block whose zero-motion MAD0 is at
// or below 2.5x the model's expected |proxy diff| on both pairs), and fills
// blockStatic with the per-block 0/1 of both-pairs-and.
std::vector<uint8_t> staticMask(const LumaProxy& cA, const LumaProxy& cB,
                                const LumaProxy& cC, const NoiseProfile* np,
                                float meanSignal,
                                std::vector<uint8_t>& blockStatic) {
    const uint32_t pw = cA.w, ph = cA.h;
    const uint32_t nbw = pw / 16, nbh = ph / 16;
    blockStatic.assign(static_cast<size_t>(nbw) * nbh, 0);
    const float thr = 2.5f * expectedProxyAbsDiff(np, meanSignal);
    const float moP = proxyMeanDiff(cB, cA); // pair (prev, center)
    const float moN = proxyMeanDiff(cB, cC); // pair (center, next)
    for (uint32_t by = 0; by < nbh; ++by)
        for (uint32_t bx = 0; bx < nbw; ++bx) {
            const float mp = blockMad0(cB, cA, bx * 16, by * 16, moP);
            const float mn = blockMad0(cB, cC, bx * 16, by * 16, moN);
            blockStatic[static_cast<size_t>(by) * nbw + bx] =
                (mp <= thr && mn <= thr) ? 1 : 0;
        }
    // Covered pixels (the block grid is floor-sized) inside block plateau
    // cores: u in [32, 96) both axes — outside the 32-px feather bands.
    const uint32_t w = pw << 3, h = ph << 3;
    std::vector<uint8_t> mask(static_cast<size_t>(w) * h, 0);
    for (uint32_t by = 0; by < nbh; ++by)
        for (uint32_t bx = 0; bx < nbw; ++bx) {
            if (!blockStatic[static_cast<size_t>(by) * nbw + bx]) continue;
            for (uint32_t yy = by * 128 + 32; yy < by * 128 + 96; ++yy)
                for (uint32_t xx = bx * 128 + 32; xx < bx * 128 + 96; ++xx)
                    mask[static_cast<size_t>(yy) * w + xx] = 1;
        }
    return mask;
}

FrameInfo synthInfo(uint32_t w, uint32_t h, int64_t ptsUs) {
    FrameInfo info;
    info.width = w;
    info.height = h;
    info.ptsUs = ptsUs;
    info.cfa = CfaPattern::RGGB;
    info.orientationDeg = 2;
    return info;
}

RawMeta synthMeta() {
    RawMeta m;
    for (int s = 0; s < 4; ++s)
        m.black[s] = kBlack;
    m.white = kWhite;
    return m;
}

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

} // namespace

// ---------------- noise command (real clip) ----------------

static int cmdNoise(const char* path, size_t centerArg, float strength) {
    const auto tOpen0 = std::chrono::steady_clock::now();
    auto res = Source::open(path);
    if (!res.isOk()) {
        std::fprintf(stderr, "open: %s\n", res.detail.c_str());
        return 1;
    }
    Source& src = **res.value;
    const size_t n = src.frameCount();
    if (n < 7) {
        std::fprintf(stderr, "clip has %zu frames; the noise metric needs 7 around the center\n", n);
        return 1;
    }
    size_t center = centerArg;
    if (center == 0 || center + 3 >= n) center = n / 2; // 0 = auto
    if (center < 3 || center + 3 >= n) {
        std::fprintf(stderr, "center %zu out of the decodable range [3, %zu]\n", center, n - 4);
        return 1;
    }
    std::vector<std::vector<uint16_t>> f(7);
    for (int k = 0; k < 7; ++k) {
        auto d = src.readFrame(center - 3 + static_cast<size_t>(k));
        if (!d.isOk()) {
            std::fprintf(stderr, "decode %zu: %s\n", center - 3 + k, d.detail.c_str());
            return 1;
        }
        f[k] = std::move(*d.value);
    }
    const auto tDecode = std::chrono::steady_clock::now();

    const FrameInfo cInfo = src.frameInfo(center);
    const uint32_t w = cInfo.width, h = cInfo.height;
    const RawMeta meta = src.rawMeta();
    const NoiseProfile npc = src.noiseProfile(center);
    const NoiseProfile npp = src.noiseProfile(center - 1);
    const NoiseProfile npn = src.noiseProfile(center + 1);

    // Merged outputs for the 5 interior frames, bridge-contract windows.
    std::vector<std::vector<uint16_t>> out(5);
    TemporalReport reps[5];
    for (int i = 0; i < 5; ++i) {
        const size_t t = center - 2 + static_cast<size_t>(i);
        const FrameInfo ti = src.frameInfo(t);
        const int64_t dPrev = src.frameInfo(t - 1).ptsUs - ti.ptsUs;
        const int64_t dNext = src.frameInfo(t + 1).ptsUs - ti.ptsUs;
        const int64_t dur = (dPrev + dNext) / 2; // local median of 2
        TemporalSettings ts;
        ts.strength = strength;
        ts.centerNoise = &npc;
        ts.prevNoise = &npp;
        ts.nextNoise = &npn;
        ts.prevDPtsUs = dPrev;
        ts.nextDPtsUs = dNext;
        ts.frameDurationUs = dur;
        // Trend cache unavailable to a cold harness: negative = unknown,
        // absolute MAFD gate only (scrub-start contract).
        out[i].assign(f[i + 1].size(), 0);
        temporalMergeCfa(f[i + 1].data(), ti, f[i].data(), f[i + 2].data(),
                        src.rawMeta(), ts, out[i].data(), &reps[i]);
    }
    const auto tMerge = std::chrono::steady_clock::now();

    // Static region from the middle window's pairs ((f2,f3), (f3,f4)).
    const FrameInfo infoMid = synthInfo(w, h, 0);
    const LumaProxy p2 = buildLumaProxy(f[2].data(), infoMid, meta);
    const LumaProxy p3 = buildLumaProxy(f[3].data(), infoMid, meta);
    const LumaProxy p4 = buildLumaProxy(f[4].data(), infoMid, meta);
    // Mean signal over the center proxy (drives the noise model).
    double meanSig = 0.0;
    for (float v : p3.v) meanSig += v;
    meanSig /= static_cast<double>(p3.v.size());
    std::vector<uint8_t> blockStatic;
    const std::vector<uint8_t> mask =
        staticMask(p2, p3, p4, &npc, static_cast<float>(meanSig), blockStatic);

    // Per-pixel temporal std across the 5 raw centers / 5 merged outputs on
    // the static region; median and std of the per-pixel stds.
    long long staticPx = 0;
    for (uint8_t m : mask) staticPx += m;
    std::vector<double> stdRaw, stdOut;
    stdRaw.reserve(4096);
    stdOut.reserve(4096);
    double sumAbsOut = 0.0; // mean |out - center| over static px
    const size_t npix = static_cast<size_t>(w) * h;
    for (size_t i = 0; i < npix; ++i) {
        if (!mask[i]) continue;
        double mr = 0, mo = 0;
        for (int k = 0; k < 5; ++k) {
            mr += f[k + 1][i];
            mo += out[k][i];
        }
        mr *= 0.2;
        mo *= 0.2;
        double vr = 0, vo = 0;
        for (int k = 0; k < 5; ++k) {
            const double dr = f[k + 1][i] - mr, do_ = out[k][i] - mo;
            vr += dr * dr;
            vo += do_ * do_;
        }
        stdRaw.push_back(std::sqrt(vr / 4.0));
        stdOut.push_back(std::sqrt(vo / 4.0));
        sumAbsOut += std::fabs(out[2][i] - f[3][i]);
    }
    // std-of-stds needs plain moments (medianOf shuffles; compute first).
    auto sd = [](const std::vector<double>& v) {
        if (v.empty()) return 0.0;
        double s = 0, s2 = 0;
        for (double x : v) { s += x; s2 += x * x; }
        const double m = s / v.size();
        return std::sqrt(std::max(s2 / v.size() - m * m, 0.0));
    };
    const double rawStdOf = sd(stdRaw);
    const double outStdOf = sd(stdOut);
    const double medRaw = medianOf(stdRaw);
    const double medOut = medianOf(stdOut);
    // Decorrelated residual estimator: windows of out[0] (frames center-3..
    // center-1) and out[4] (frames center+1..center+3) are DISJOINT raw sets.
    std::vector<double> decorr;
    decorr.reserve(4096);
    double decorrSum = 0.0;
    long long decorrN = 0;
    for (size_t i = 0; i < npix; ++i) {
        if (!mask[i]) continue;
        decorr.push_back(std::fabs(static_cast<double>(out[0][i]) - out[4][i])
                         / std::sqrt(2.0));
        decorrSum += decorr.back();
        ++decorrN;
    }
    const double decorrMed = medianOf(decorr);

    std::printf("== temporal_measure noise ==\n");
    std::printf("clip=%s center=%zu frames=%zu..%zu (%zu centers) %ux%u cfa=%d "
                "bitDepth=%u strength=%g\n",
                path, center, center - 2, center + 2, n, w, h, int(cInfo.cfa),
                cInfo.bitDepth, strength);
    std::printf("decode7 ms=%.1f  merge5 ms=%.1f  static_px=%lld\n",
                std::chrono::duration<double, std::milli>(tDecode - tOpen0)
                    .count() / 7.0 * 7.0,
                std::chrono::duration<double, std::milli>(tMerge - tDecode).count(),
                staticPx);
    double ts0 = std::chrono::duration<double, std::milli>(tMerge - tDecode).count() / 5.0;
    std::printf("mean merge ms/frame=%.1f\n", ts0);
    for (int i = 0; i < 5; ++i) {
        const size_t t = center - 2 + static_cast<size_t>(i);
        std::printf("t=%zu: prev elig=%d acc=%.3f meanW=%.4f floor=%.5f mafd=%.2f | "
                    "next elig=%d acc=%.3f meanW=%.4f floor=%.5f mafd=%.2f | "
                    "ms proxy=%.1f match=%.1f merge=%.1f\n",
                    t, int(reps[i].prev.eligible), reps[i].prev.acceptRate,
                    reps[i].prev.meanWeight, reps[i].prev.noiseFloor, reps[i].prev.mafd,
                    int(reps[i].next.eligible), reps[i].next.acceptRate,
                    reps[i].next.meanWeight, reps[i].next.noiseFloor, reps[i].next.mafd,
                    reps[i].proxyMs, reps[i].matchMs, reps[i].mergeMs);
    }
    std::printf("static region: blocks=%zu/%zu px=%lld (%.1f%% of frame)\n",
                static_cast<size_t>(std::count(blockStatic.begin(), blockStatic.end(), 1)),
                blockStatic.size(), staticPx,
                100.0 * static_cast<double>(staticPx) / static_cast<double>(npix));
    const double modelSigma =
        std::sqrt(2.0 * (npc.valid ? npc.s[1] * meanSig + npc.o[1]
                                    : 2.0 * temporalFallbackSigma()
                                               * temporalFallbackSigma()))
        * kRange;
    std::printf("noise model: meanSignal=%.4f sigma_diff_sample=%.2f DN (profile valid=%d)\n",
                meanSig, modelSigma, int(npc.valid));
    std::printf("sigma_in  (5 raw frames, per-px temporal std): median=%.3f DN std=%.3f DN\n",
                medRaw, rawStdOf);
    std::printf("sigma_out (5 NR outputs, per-px temporal std): median=%.3f DN std=%.3f DN\n",
                medOut, outStdOf);
    std::printf("ratio out/in=%.4f (variance %.4f)\n",
                medOut / std::max(medRaw, 1e-9),
                (medOut * medOut) / std::max(medRaw * medRaw, 1e-9));
    std::printf("decorrelated |NRdisjoint1-NRdisjoint2|/sqrt2: median=%.3f DN mean=%.3f DN n=%lld\n",
                decorrMed, decorrSum / std::max<long long>(decorrN, 1), decorrN);
    std::printf("mean |NR_center-raw_center| over static = %.3f DN\n",
                sumAbsOut / std::max(staticPx, 1ll));
    return 0;
}

// ---------------- ghost command (synthetic, documented seed) ----------------

namespace {

constexpr uint32_t kGw = 3840, kGh = 2160; // cam72 geometry
constexpr uint32_t kObjSize = 384;
constexpr uint32_t kObjY0 = 700;
constexpr int64_t kFrameDurUs = 33333;
constexpr float kPlateBase = 0.30f, kPlateAmp = 0.20f;
constexpr float kObjLevel = 0.85f;
constexpr uint32_t kObjX0Center = 1600; // center frame's object left edge
constexpr float kGhostSigmaDn = 8.0f;   // cam72-measured band; doc pins it

// Clean scene: plate texture + hard object. k = frame index in the window
// (0,1,2 = prev/center/next); object left edge at x0 + v*k.
float sceneLevel(int k, uint32_t x, uint32_t y, uint32_t vx,
                 const std::vector<float>& tex) {
    const int xx = static_cast<int>(x) - static_cast<int>(vx) * k;
    const int objL = static_cast<int>(kObjX0Center);
    const bool inObj = xx >= objL && xx < objL + static_cast<int>(kObjSize)
        && y >= kObjY0 && y < kObjY0 + kObjSize;
    if (inObj) return kObjLevel;
    return tex[static_cast<size_t>(y) * kGw + x];
}

std::vector<uint16_t> renderFrame(int k, uint32_t vx, float sigmaDn,
                                  const std::vector<float>& tex, uint32_t seedBase,
                                  bool addNoise) {
    std::vector<uint16_t> f(static_cast<size_t>(kGw) * kGh);
    Lcg rng(seedBase + static_cast<uint32_t>(k));
    for (uint32_t y = 0; y < kGh; ++y)
        for (uint32_t x = 0; x < kGw; ++x) {
            const float lv = sceneLevel(k, x, y, vx, tex);
            float v = kBlack + lv * kRange;
            if (addNoise) v += rng.gauss(sigmaDn);
            v = std::min(std::max(v, 0.0f), 65535.0f);
            f[static_cast<size_t>(y) * kGw + x] = static_cast<uint16_t>(v + 0.5f);
        }
    return f;
}

} // namespace

static int cmdGhost(float strength) {
    std::printf("== temporal_measure ghost ==\n");
    std::printf("scene: %ux%u RGGB black=%u white=%u plate=%.2f+%.2f*u "
                "obj=%ux%u@(%u,%u) level=%.2f sigma=%.1f DN dur=%lldus "
                "seed=20261126\n",
                kGw, kGh, kBlack, kWhite, kPlateBase, kPlateAmp, kObjSize,
                kObjSize, kObjX0Center, kObjY0, kObjLevel, kGhostSigmaDn,
                static_cast<long long>(kFrameDurUs));

    // Plate texture: deterministic white-noise field.
    std::vector<float> tex(static_cast<size_t>(kGw) * kGh);
    {
        Lcg rng(20261126u);
        for (size_t i = 0; i < tex.size(); ++i)
            tex[i] = kPlateBase + kPlateAmp * rng.u();
    }
    const RawMeta meta = synthMeta();
    const FrameInfo infoC = synthInfo(kGw, kGh, kFrameDurUs);

    const uint32_t speeds[] = {1, 2, 4, 8, 24};
    std::printf("speed(px/frame) proxy-px  acceptP acceptN meanWP meanWN  "
                "G_MAF(DN) G_bias(DN) bandStd(DN) lagLead(px) lagTrail(px) "
                "staticStdOut(DN)\n");
    for (uint32_t vx : speeds) {
        std::vector<uint16_t> window[3];
        for (int k = 0; k < 3; ++k)
            window[k] = renderFrame(k, vx, kGhostSigmaDn, tex, 700000u, true);
        // Clean center render (no noise).
        const std::vector<uint16_t> cleanC = renderFrame(1, vx, 0.0f, tex, 0, false);

        const NoiseProfile prof = constantProfile(kGhostSigmaDn);
        TemporalSettings ts;
        ts.strength = strength;
        ts.centerNoise = &prof;
        ts.prevNoise = &prof;
        ts.nextNoise = &prof;
        ts.prevDPtsUs = -kFrameDurUs;
        ts.nextDPtsUs = kFrameDurUs;
        ts.frameDurationUs = kFrameDurUs;
        std::vector<uint16_t> out(window[1].size(), 0);
        TemporalReport rep;
        temporalMergeCfa(window[1].data(), infoC, window[0].data(), window[2].data(),
                         meta, ts, out.data(), &rep);

        // Band: clean content differs between the center and either
        // neighbor. Frame k's object left edge sits at kObjX0Center +
        // vx*k, so the swept columns span [kObjX0Center, kObjX0Center +
        // 2*vx + kObjSize); the lo/hi window brackets that and the
        // content test below picks exactly the differing columns.
        const int lo = static_cast<int>(kObjX0Center) - 1;
        const int hi = static_cast<int>(kObjX0Center) + static_cast<int>(kObjSize)
            + 2 * static_cast<int>(vx) + 1;
        long long bandN = 0;
        double sumD = 0, sumAbsD = 0, sumD2 = 0;
        for (uint32_t y = kObjY0; y < kObjY0 + kObjSize; ++y)
            for (int x = std::max(lo, 0); x < std::min(hi, static_cast<int>(kGw)); ++x) {
                const uint32_t xu = static_cast<uint32_t>(x);
                const float ccl = sceneLevel(1, xu, y, vx, tex);
                const float ncl0 = sceneLevel(0, xu, y, vx, tex);
                const float ncl2 = sceneLevel(2, xu, y, vx, tex);
                if (std::fabs(ncl0 - ccl) < 0.05f * 0.45f
                    && std::fabs(ncl2 - ccl) < 0.05f * 0.45f)
                    continue; // content unchanged: not band
                const size_t i = static_cast<size_t>(y) * kGw + xu;
                const double d = static_cast<double>(out[i]) - static_cast<double>(cleanC[i]);
                ++bandN;
                sumD += d;
                sumAbsD += std::fabs(d);
                sumD2 += d * d;
            }
        const double gMaf = bandN ? sumAbsD / bandN : 0;
        const double gBias = bandN ? std::fabs(sumD / bandN) : 0;
        const double bandStd = bandN > 1
            ? std::sqrt(std::max(sumD2 / bandN - (sumD / bandN) * (sumD / bandN), 0.0))
            : 0;

        // Edge lag: 50%-crossing of the row-mean profile (200 rows inside
        // the object) around each clean edge, NR vs clean.
        auto edgePos = [&](const std::vector<uint16_t>& img, int edgeX) {
            double prof[81];
            for (int dx = -40; dx <= 40; ++dx) {
                const int x = edgeX + dx;
                double s = 0;
                for (uint32_t y = kObjY0 + 100; y < kObjY0 + 300; ++y)
                    s += img[static_cast<size_t>(y) * kGw + x];
                prof[dx + 40] = s / 200.0;
            }
            // local bg/obj levels from the profile ends
            const double bg = (prof[0] + prof[1] + prof[2] + prof[3]) * 0.25;
            const double ob = (prof[77] + prof[78] + prof[79] + prof[80]) * 0.25;
            const double cross = (bg + ob) * 0.5;
            for (int i = 1; i < 81; ++i) {
                if ((prof[i - 1] < cross) != (prof[i] < cross) || prof[i] == cross) {
                    // first crossing (leading edge: rising)
                    const double dy = prof[i] - prof[i - 1];
                    if (std::fabs(dy) < 1e-9) return static_cast<double>(edgeX + i - 40);
                    const double f = (cross - prof[i - 1]) / dy;
                    return edgeX - 40 + (i - 1) + f;
                }
            }
            return static_cast<double>(edgeX);
        };
        // The CENTER frame's edges (k = 1): object left edge at x0 + vx.
        const int leadX = static_cast<int>(kObjX0Center) + static_cast<int>(vx);
        const int trailX = leadX + static_cast<int>(kObjSize);
        const double lagLead = edgePos(out, leadX) - edgePos(cleanC, leadX);
        const double lagTrail = edgePos(out, trailX) - edgePos(cleanC, trailX);

        // Static strip: far from the object in x (left margin).
        double sOut = 0, sOut2 = 0, sRaw = 0, sRaw2 = 0;
        long long sN = 0;
        for (uint32_t y = 200; y < 1900; ++y)
            for (uint32_t x = 64; x < 512; ++x) {
                const size_t i = static_cast<size_t>(y) * kGw + x;
                const double d = static_cast<double>(out[i]) - cleanC[i];
                const double dr = static_cast<double>(window[1][i]) - cleanC[i];
                ++sN;
                sOut += d;
                sOut2 += d * d;
                sRaw += dr;
                sRaw2 += dr * dr;
            }
        const double mo = sOut / sN, mr = sRaw / sN;
        const double stdOutStatic = std::sqrt(std::max(sOut2 / sN - mo * mo, 0.0));
        const double stdRawStatic = std::sqrt(std::max(sRaw2 / sN - mr * mr, 0.0));

        std::printf("%u %s  %.3f %.3f  %.4f %.4f  %8.3f %8.3f %8.3f  %+7.3f %+7.3f  %7.3f (raw in %7.3f) bandN=%lld\n",
                    vx, (vx % 8 == 0 ? "grid " : "sub  "),
                    rep.prev.acceptRate, rep.next.acceptRate,
                    rep.prev.meanWeight, rep.next.meanWeight,
                    gMaf, gBias, bandStd, lagLead, lagTrail,
                    stdOutStatic, stdRawStatic, bandN);
    }
    return 0;
}

int main(int argc, char** argv) {
    if (argc >= 3 && std::strcmp(argv[1], "noise") == 0) {
        const float strength = argc >= 5 ? float(std::atof(argv[4])) : 100.0f;
        return cmdNoise(argv[2], std::strtoull(argv[3], nullptr, 10), strength);
    }
    if (argc >= 2 && std::strcmp(argv[1], "ghost") == 0) {
        const float strength = argc >= 3 ? float(std::atof(argv[2])) : 100.0f;
        return cmdGhost(strength);
    }
    std::fprintf(stderr,
                 "usage: temporal_measure noise <clip.mcraw> <centerFrame> [strength=100]\n"
                 "       temporal_measure ghost [strength=100]\n");
    return 2;
}
