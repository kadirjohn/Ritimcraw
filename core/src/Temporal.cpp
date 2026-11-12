// rawedit::Temporal — implementation. See Temporal.h for the design.
//
// Cost shape (single-thread, desktop -O2): the proxy is built from a
// stride-2 subsample of each 8x8 CFA block (4 samples, one per slot —
// pattern independent, ~4x cheaper than averaging all 64 samples); block
// matching runs on 1/8-scale proxies; the merge walks the full CFA once
// with a per-block weight lookup. Everything is single-threaded by
// contract (one Source per thread; the JNI bridge is single-thread).

#include <rawedit/Temporal.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>

namespace rawedit {

namespace {

// ---- constants (documented in Temporal.h; collected here) ----
constexpr int kProxyShift = 3;           // 1/8 scale: 8x8 CFA px per proxy px
constexpr int kBlockSize = 16;           // proxy-px block edge (128 CFA px)
constexpr int kBlockShift = 4;
constexpr int kSearchRadius = 4;         // proxy px = 32 CFA px
constexpr float kMaxNeighborWeight = 0.40f; // researched 0.35-0.5 band
constexpr float kMeanLumaReject = 0.04f;    // 4% of normalized range
constexpr float kHistTvReject = 0.30f;      // TV distance of 32-bin histograms
constexpr float kMinBlockAcceptRate = 0.25f;
constexpr float kResidualTaperZero = 3.0f;  // x sigma at which weight hits 0
constexpr float kResidualTaperFull = 1.5f;  // x sigma with full weight
constexpr float kPixelGuardSigma = 6.0f;    // per-pixel occlusion guard
constexpr int kHistBins = 32;
// Source-comparison research 2026-11-11 (FFmpeg/OpenCV/ginzburg study):
constexpr float kZeroBias = 0.5f;        // tie-break band around min cost, x floor
constexpr float kRegScale = 0.5f;        // MV regularization per proxy px, x floor
constexpr float kFloorQuantile = 0.25f;  // noise-floor quantile over winner MADs
constexpr float kFloorGate = 3.0f;       // hard gate: used MAD <= 3x measured floor
constexpr float kSeedAccept = 2.0f;      // item #4 early-accept: mean sub-SAD at
                                         // or below 2x the PREVIOUS pair-in-time's
                                         // floor skips the exhaustive scan
constexpr float kMinConfidence = 0.1f;   // ginzburg threshold: below this a
                                         // "gain" is matching noise, reject
// Source-comparison item #8 (FFmpeg scdet get_scene_score convention):
// MAFD = mean absolute frame difference x100 on the normalized proxies,
// reject > 10. The trend (memory) form uses the PREVIOUS pair's MAFD,
// supplied by the caller (bridge-owned cache, negative = unknown).
constexpr float kSceneCutMafd = 10.0f;

double nowMs() {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// 2x2 cell-position slot (0..3, the container's r/g/g/b order of
// RawMeta::black and NoiseProfile). Same indexing as Develop.cpp's
// cellSlot(y & 1, x & 1): black levels and noise profiles are indexed by
// CELL POSITION, not by color, and a 2x2 cell holds one site of each slot.
inline int cellSlot(uint32_t x, uint32_t y) {
    return static_cast<int>(((y & 1u) << 1) | (x & 1u));
}

// Normalized signal of one raw sample: per-slot black/white normalization
// (develop stage 1 math, mirrored exactly). Safe against a degenerate
// range (white <= black -> 1.0 denominator, as in Develop.cpp).
inline float normSignal(uint16_t v, const RawMeta& meta, int slot) {
    const float black = static_cast<float>(meta.black[slot]);
    const float white = static_cast<float>(meta.white);
    const float range = white > black ? white - black : 1.0f;
    return (static_cast<float>(v) - black) / range;
}

// Variance of one sample from the Camera2 model (normalized units), or the
// fallback sigma squared when the clip carries no profile.
inline float varOf(const NoiseProfile* np, float signal, int slot) {
    if (np && np->valid) {
        const float v = np->s[slot] * signal + np->o[slot];
        if (v > 1e-9f)
            return v;
    }
    const float f = temporalFallbackSigma();
    return f * f;
}

// Sigma of the DIFFERENCE of two independent samples (center and one
// neighbor, each with its own profile): sqrt(va + vn).
inline float sigmaDiff(const NoiseProfile* a, const NoiseProfile* b,
                       float signal, int slot) {
    return std::sqrt(varOf(a, signal, slot) + varOf(b, signal, slot));
}

// Weight taper on the block residual (in units of sigma): full at/below
// kResidualTaperFull, zero at/above kResidualTaperZero, linear between.
inline float residualTaper(float r) {
    if (r <= kResidualTaperFull)
        return 1.0f;
    if (r >= kResidualTaperZero)
        return 0.0f;
    return (kResidualTaperZero - r) / (kResidualTaperZero - kResidualTaperFull);
}

// Temporal-distance decay of the weight cap (atadenoise get_weights): a
// neighbor k frame-durations out shares less temporal correlation, so
// its cap scales as 1/k, clamped to 1 at and below one duration. dPts
// of 0 (unspecified — direct callers, tests) decays nothing; the bridge
// owns PTS eligibility and passes the real deltas.
inline float distanceDecay(int64_t dPts, int64_t frameDurationUs) {
    if (dPts == 0 || frameDurationUs <= 0)
        return 1.0f;
    const int64_t d = dPts < 0 ? -dPts : dPts;
    const float k = static_cast<float>(d) / static_cast<float>(frameDurationUs);
    return std::min(1.0f, 1.0f / std::max(k, 1.0f));
}

// Even-CFA scaling of a proxy vector: proxy px = 8 CFA px, always even,
// so a warp offset never crosses CFA slots (no chroma bleed by
// construction). (Function so the invariant is stated once.)
inline int proxyToCfa(int d) { return d << kProxyShift; }

// Full-res block geometry: one proxy block is 128x128 CFA px, and the
// merge feathers block fields over the outer quarter of each block edge.
constexpr int kBlockCfaShift = kProxyShift + kBlockShift; // 7
constexpr int kBlockCfa = 1 << kBlockCfaShift;           // 128
constexpr int kFeatherCfa = kBlockCfa / 4;               // 32 (25% a side)

// OBMC feather candidates along one axis. `u` is the pixel's position
// within its 128-px block; inside the outer 32-px bands the adjacent
// block shares the pixel with complementary linear ramps (50/50 at the
// boundary itself, continuous into the plateau outside the bands).
// hasLeft/hasRight say whether the adjacent block exists on this
// neighbor's grid; a missing adjacent block leaves the containing block
// alone. Weights renormalize at the pixel level over ACCEPTED blocks
// only, so a rejected block never contributes a sample or a weight.
// Returns the candidate count (1 or 2); offs are block-index offsets
// from the containing block.
inline int axisCandidates(uint32_t u, bool hasLeft, bool hasRight,
                          int offs[2], float wts[2]) {
    if (u < static_cast<uint32_t>(kFeatherCfa) && hasLeft) {
        offs[0] = -1;
        wts[0] = static_cast<float>(kFeatherCfa - u) / (2.0f * kFeatherCfa);
        offs[1] = 0;
        wts[1] = static_cast<float>(kFeatherCfa + u) / (2.0f * kFeatherCfa);
        return 2;
    }
    if (u >= static_cast<uint32_t>(kBlockCfa - kFeatherCfa) && hasRight) {
        offs[0] = 0;
        wts[0] = static_cast<float>(kBlockCfa + kFeatherCfa - u)
            / (2.0f * kFeatherCfa);
        offs[1] = 1;
        wts[1] = static_cast<float>(u - (kBlockCfa - kFeatherCfa))
            / (2.0f * kFeatherCfa);
        return 2;
    }
    offs[0] = 0;
    wts[0] = 1.0f;
    return 1;
}

// One neighbor's full evaluation, shared by prev/next.
struct NeighborEval {
    bool present = false;
    bool eligible = false;    // passed the global gates
    float acceptRate = 0.0f;  // fraction of blocks passing local gates
    float meanWeight = 0.0f;  // mean weight actually applied (post-merge)
    float noiseFloor = 0.0f;  // measured 25th-pct winner MAD (diagnostic)
    float mafd = 0.0f;        // this pair's scene-score (item #8, diagnostic)
    LumaProxy proxy;          // kept alive for the merge (warp reuse)
    std::vector<MotionVector> fwd;  // center -> neighbor, per block
    // neighbor -> center: the F/B consistency anchor. NEVER seeded (item
    // #4) — inflating F/B agreement is the ghost direction. The PREV
    // side's copy is the collocated predictor for the next side's seeded
    // forward search (it is a full-search field by construction).
    std::vector<MotionVector> bwd;
    std::vector<float> blockWeight; // final per-block weight (0 = rejected)
    uint32_t bw = 0, bh = 0;        // block grid dims
};

} // namespace

bool temporalNeighborEligible(int64_t dPts, int64_t frameDurationUs) {
    if (frameDurationUs <= 0)
        return false;
    const int64_t d = dPts < 0 ? -dPts : dPts;
    return d > 0 && d <= (3 * frameDurationUs) / 2;
}

LumaProxy buildLumaProxy(const uint16_t* cfa, const FrameInfo& info,
                         const RawMeta& meta) {
    LumaProxy p;
    if (!cfa || info.width < 32u || info.height < 32u)
        return p;
    const uint32_t w = info.width, h = info.height;
    p.w = w >> kProxyShift;
    p.h = h >> kProxyShift;
    // Block matching needs at least a 2x2 block grid (a 16x16-px block
    // grid); smaller frames keep an empty proxy and are rejected.
    if (p.w < 2u * kBlockSize || p.h < 2u * kBlockSize) {
        p.w = p.h = 0;
        p.v.clear();
        return p;
    }
    p.v.assign(static_cast<size_t>(p.w) * p.h, 0.0f);

    // Proxy pixel (px, py) = mean of the 4 stride-2 samples of its 8x8 CFA
    // block: positions (0,0),(2,0),(0,2),(2,2) relative to the block
    // origin — one sample of each 2x2 cell slot, so the average is
    // CFA-pattern independent at 1/4 the read cost of the full block.
    for (uint32_t py = 0; py < p.h; ++py) {
        const uint32_t y0 = py << kProxyShift;
        for (uint32_t px = 0; px < p.w; ++px) {
            const uint32_t x0 = px << kProxyShift;
            const float s0 = normSignal(cfa[static_cast<size_t>(y0) * w + x0],
                                        meta, cellSlot(x0, y0));
            const float s1 = normSignal(cfa[static_cast<size_t>(y0) * w + (x0 + 2)],
                                        meta, cellSlot(x0 + 2, y0));
            const float s2 = normSignal(cfa[static_cast<size_t>(y0 + 2) * w + x0],
                                        meta, cellSlot(x0, y0 + 2));
            const float s3 = normSignal(cfa[static_cast<size_t>(y0 + 2) * w + (x0 + 2)],
                                        meta, cellSlot(x0 + 2, y0 + 2));
            p.v[static_cast<size_t>(py) * p.w + px] = (s0 + s1 + s2 + s3) * 0.25f;
        }
    }
    return p;
}

namespace {

// SAD of the 16x16 block at proxy origin (bx, by) of `a` against `b`
// shifted by (dx, dy), on the 2x2-subsampled lattice (64 samples).
// Out-of-bounds b samples edge-replicate (only edge blocks ever hit it).
float blockSadSub(const LumaProxy& a, const LumaProxy& b,
                  uint32_t bx, uint32_t by, int dx, int dy) {
    float sad = 0.0f;
    for (int y = 0; y < kBlockSize; y += 2) {
        int sy = static_cast<int>(by) + y + dy;
        sy = std::min(std::max(sy, 0), static_cast<int>(b.h) - 1);
        const float* aRow = &a.v[static_cast<size_t>(by + y) * a.w + bx];
        const float* bRow = &b.v[static_cast<size_t>(sy) * b.w];
        for (int x = 0; x < kBlockSize; x += 2) {
            int sx = static_cast<int>(bx) + x + dx;
            sx = std::min(std::max(sx, 0), static_cast<int>(b.w) - 1);
            sad += std::fabs(aRow[x] - bRow[sx]);
        }
    }
    return sad;
}

// Full-sampling mean absolute difference for the winner (256 samples).
// meanOffset (source-comparison item #9, mean-normalized SAD): subtracted
// from every sample difference before the absolute value. The merge passes
// the pair's GLOBAL proxy mean difference (what the mean-luma gate already
// computes), so auto-exposure micro-flicker — a uniform level step between
// adjacent frames — no longer inflates winner MADs, the measured noise
// floor, and the static-path residual. Deliberately GLOBAL, not per-block:
// a per-block mean subtraction zeroes the residual of a local content
// change (measured: a block brightening by 0.30 of range drops from MAD
// 0.300 to 0.0035 ~= the noise floor, i.e. a guaranteed ghost), while the
// global offset keeps it (measured 0.225 >> noise -> rejected). Uniform
// steps are bounded by the 4% mean-luma gate; the search cost
// (blockSadSub) stays plain — the measured argmin shift under flicker is
// +0.15-0.35% p50, noise level.
float blockMadFull(const LumaProxy& a, const LumaProxy& b,
                   uint32_t bx, uint32_t by, int dx, int dy,
                   float meanOffset) {
    float sad = 0.0f;
    for (int y = 0; y < kBlockSize; ++y) {
        int sy = static_cast<int>(by) + y + dy;
        sy = std::min(std::max(sy, 0), static_cast<int>(b.h) - 1);
        const float* aRow = &a.v[static_cast<size_t>(by + y) * a.w + bx];
        const float* bRow = &b.v[static_cast<size_t>(sy) * b.w];
        for (int x = 0; x < kBlockSize; ++x) {
            int sx = static_cast<int>(bx) + x + dx;
            sx = std::min(std::max(sx, 0), static_cast<int>(b.w) - 1);
            sad += std::fabs((aRow[x] - bRow[sx]) - meanOffset);
        }
    }
    return sad / float(kBlockSize * kBlockSize);
}

// Global gates on the proxies: mean luma shift + histogram TV distance.
// Both computed once per neighbor; either failing rejects it.
// meanDiffOut (optional) receives the pair's proxy mean difference
// mean(c) - mean(n) — the #9 de-bias offset, already computed for the
// mean-luma gate, handed to the caller at zero extra cost.
bool globalGatesPass(const LumaProxy& c, const LumaProxy& n,
                     float* meanDiffOut = nullptr,
                     float* mafdOut = nullptr,
                     float prevPairMafd = -1.0f) {
    // Mean luma shift (sudden exposure jump: not a cut, still unsafe).
    float mc = 0.0f, mn = 0.0f;
    for (size_t i = 0; i < c.v.size(); ++i)
        mc += c.v[i];
    for (size_t i = 0; i < n.v.size(); ++i)
        mn += n.v[i];
    mc /= static_cast<float>(c.v.size());
    mn /= static_cast<float>(n.v.size());
    if (meanDiffOut)
        *meanDiffOut = mc - mn;

    // MAFD scene-cut gate (source-comparison item #8, FFmpeg scdet
    // get_scene_score convention): mean |center - neighbor| x100 on the
    // normalized proxies, reject > 10. Memoryless on its own; the TREND
    // form compares against the caller-supplied MAFD of the PREVIOUS pair
    // in time (bridge-owned cache; negative = unknown, delta inactive) —
    // reject when the pair-to-pair MAFD JUMPS by more than the threshold
    // (a cut inside an otherwise continuous sequence shows up as the
    // jump). Measured margin on the 4K fixture: quiet pairs 0.37-0.38 vs
    // the 10 gate; a same-histogram structure cut reads 4.08 (below the
    // MAFD gate — the accept-rate gate already rejects it), a fence cut
    // 19.55, a fence slide 39.99 (both already rejected by the histogram
    // gates today). The value is the pre-match early exit: cuts with
    // near-equal histograms and means pay the ~11 ms match cost before
    // the local gates drop them. The score is computed (and reported)
    // BEFORE the mean-luma gate's early return on purpose: a cut that
    // also shifts exposure must still leave its true MAFD in the
    // bridge's trend cache, or the pair after it would compare against a
    // fake quiet 0 and the trend blast radius would silently vanish.
    float mad = 0.0f;
    for (size_t i = 0; i < c.v.size(); ++i)
        mad += std::fabs(c.v[i] - n.v[i]);
    mad *= 100.0f / static_cast<float>(c.v.size());
    if (mafdOut)
        *mafdOut = mad;
    if (std::fabs(mc - mn) > kMeanLumaReject)
        return false;
    if (mad > kSceneCutMafd)
        return false;
    if (prevPairMafd >= 0.0f && std::fabs(mad - prevPairMafd) > kSceneCutMafd)
        return false;

    // 32-bin histograms on [0, 1] (clamped), TV distance = 0.5*sum|a-b|.
    int hc[kHistBins] = {0};
    int hn[kHistBins] = {0};
    for (size_t i = 0; i < c.v.size(); ++i) {
        const int b = std::min(std::max(static_cast<int>(c.v[i] * kHistBins), 0),
                               kHistBins - 1);
        ++hc[b];
    }
    for (size_t i = 0; i < n.v.size(); ++i) {
        const int b = std::min(std::max(static_cast<int>(n.v[i] * kHistBins), 0),
                               kHistBins - 1);
        ++hn[b];
    }
    const float inv = 1.0f / static_cast<float>(c.v.size());
    float tv = 0.0f;
    for (int i = 0; i < kHistBins; ++i)
        tv += std::fabs(static_cast<float>(hc[i] - hn[i])) * inv;
    return tv * 0.5f <= kHistTvReject;
}

} // namespace

void matchProxyBlocks(const LumaProxy& center, const LumaProxy& neighbor,
                      std::vector<MotionVector>& out, uint32_t& outW,
                      uint32_t& outH, float meanOffset) {
    outW = center.w / kBlockSize;
    outH = center.h / kBlockSize;
    out.assign(static_cast<size_t>(outW) * outH, MotionVector{});
    if (neighbor.w != center.w || neighbor.h != center.h || outW == 0 || outH == 0)
        return;

    for (uint32_t by = 0; by < outH; ++by) {
        for (uint32_t bx = 0; bx < outW; ++bx) {
            const uint32_t ox = bx * kBlockSize;
            const uint32_t oy = by * kBlockSize;
            int bestDx = 0, bestDy = 0;
            float bestSad = blockSadSub(center, neighbor, ox, oy, 0, 0);
            for (int dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
                for (int dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                    if (dx == 0 && dy == 0)
                        continue;
                    const float sad = blockSadSub(center, neighbor, ox, oy, dx, dy);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            MotionVector mv;
            mv.dx = bestDx;
            mv.dy = bestDy;
            mv.mad = blockMadFull(center, neighbor, ox, oy, bestDx, bestDy,
                                  meanOffset);
            out[static_cast<size_t>(by) * outW + bx] = mv;
        }
    }
}

void matchProxyBlocksSeeded(const LumaProxy& center, const LumaProxy& neighbor,
                           const std::vector<MotionVector>& seed, float accept,
                           std::vector<MotionVector>& out, uint32_t& outW,
                           uint32_t& outH, float meanOffset) {
    outW = center.w / kBlockSize;
    outH = center.h / kBlockSize;
    out.assign(static_cast<size_t>(outW) * outH, MotionVector{});
    if (neighbor.w != center.w || neighbor.h != center.h || outW == 0 || outH == 0)
        return;
    // Empty / wrong-size seed: plain full scan (the conservative default —
    // every gate input then comes from the exhaustive search).
    const bool seeded = seed.size() == static_cast<size_t>(outW) * outH;
    // Early-accept threshold, EPZS-style (item #4): accept the better of
    // {(0,0), collocated} without the 81-scan when its MEAN subsampled
    // SAD (sum / 64 samples — the same scale as the winner MAD and the
    // floor) is already <= accept. accept = 2x the PREVIOUS pair-in-time's
    // floor is the production value (the current pair's floor does not
    // exist before its search); a non-positive accept disables the early
    // accept and every block runs the full scan, keeping the field a
    // true argmin wherever the predictor is untrusted.
    const float invSub =
        1.0f / static_cast<float>((kBlockSize / 2) * (kBlockSize / 2));
    const float thresh = accept > 0.0f ? accept : std::numeric_limits<float>::max();

    for (uint32_t by = 0; by < outH; ++by) {
        for (uint32_t bx = 0; bx < outW; ++bx) {
            const uint32_t ox = bx * kBlockSize;
            const uint32_t oy = by * kBlockSize;
            const size_t bi = static_cast<size_t>(by) * outW + bx;
            int bestDx = 0, bestDy = 0;
            float bestSad = blockSadSub(center, neighbor, ox, oy, 0, 0);
            bool early = false;
            if (seeded) {
                // Collocated predictor FIRST (velocity continuation): the
                // same block's vector on the previous pair-in-time. Only a
                // STRICT improvement displaces zero — the tie goes to
                // (0,0), the zero-bias direction.
                const MotionVector& s = seed[bi];
                if (s.dx != 0 || s.dy != 0) {
                    const float sad = blockSadSub(center, neighbor, ox, oy,
                                                  s.dx, s.dy);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = s.dx;
                        bestDy = s.dy;
                    }
                }
                // Early accept (EPZS-style, item #4): the predictor held —
                // no 81-candidate scan for this block.
                early = bestSad * invSub <= thresh;
            }
            if (!early) {
                for (int dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
                    for (int dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                        if (dx == 0 && dy == 0)
                            continue;
                        const float sad = blockSadSub(center, neighbor,
                                                       ox, oy, dx, dy);
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestDx = dx;
                            bestDy = dy;
                        }
                    }
                }
            }
            MotionVector mv;
            mv.dx = bestDx;
            mv.dy = bestDy;
            mv.mad = blockMadFull(center, neighbor, ox, oy, bestDx, bestDy,
                                  meanOffset);
            out[bi] = mv;
        }
    }
}

namespace {

// Pass 2 of forward matching: re-decide each vector with the measured
// noise floor known (source-comparison 2026-11-11 items: zero-bias
// tie-break + MV regularization). cost = mean subsampled SAD +
// kRegScale*floor per proxy px of |mv - pred| (FFmpeg minterpolate
// get_sbad); the predictor is the per-axis median of the already-decided
// left / top / top-right vectors (EPZS-style spatial predictor — needs
// no cross-frame state, so the merge stays stateless/scrub-safe).
// Candidates within kZeroBias*floor of the best cost are ties, and ties
// go to the SHORTEST vector (ginzburg _argmin_zero_biased): inside the
// tie band every offset is statistically equivalent, and the shortest
// one cannot invent motion that is not there. Raster order guarantees
// the predictors are already decided when read.
void refineForward(const LumaProxy& c, const LumaProxy& n, float floor,
                   std::vector<MotionVector>& fwd,
                   uint32_t bw, uint32_t bh, float meanOffset) {
    const float tol = kZeroBias * floor;
    const float reg = kRegScale * floor;
    const float invSub =
        1.0f / static_cast<float>((kBlockSize / 2) * (kBlockSize / 2));
    const auto median3 = [](int a, int b, int m) {
        return std::max(std::min(a, b), std::min(std::max(a, b), m));
    };
    for (uint32_t by = 0; by < bh; ++by) {
        for (uint32_t bx = 0; bx < bw; ++bx) {
            const uint32_t ox = bx * kBlockSize;
            const uint32_t oy = by * kBlockSize;

            // Spatial predictor from decided neighbors (mean of two —
            // FFmpeg's P_MEDIAN -> P_AVERAGE fallback).
            int pvx = 0, pvy = 0;
            {
                const MotionVector* cands[3];
                int nc = 0;
                if (bx > 0)
                    cands[nc++] = &fwd[static_cast<size_t>(by) * bw + (bx - 1)];
                if (by > 0)
                    cands[nc++] = &fwd[static_cast<size_t>(by - 1) * bw + bx];
                if (by > 0 && bx + 1 < bw)
                    cands[nc++] = &fwd[static_cast<size_t>(by - 1) * bw + (bx + 1)];
                if (nc == 1) {
                    pvx = cands[0]->dx;
                    pvy = cands[0]->dy;
                } else if (nc == 2) {
                    pvx = (cands[0]->dx + cands[1]->dx) / 2;
                    pvy = (cands[0]->dy + cands[1]->dy) / 2;
                } else if (nc == 3) {
                    pvx = median3(cands[0]->dx, cands[1]->dx, cands[2]->dx);
                    pvy = median3(cands[0]->dy, cands[1]->dy, cands[2]->dy);
                }
            }

            // (0,0) first: bestNorm2 = 0 means zero is only displaced by
            // a candidate strictly better than tol beyond it — the bias
            // is structural, not a tie-break afterthought.
            int bestDx = 0, bestDy = 0;
            float bestNorm2 = 0.0f;
            float bestCost =
                blockSadSub(c, n, ox, oy, 0, 0) * invSub
                + reg * static_cast<float>(std::abs(pvx) + std::abs(pvy));
            for (int dy = -kSearchRadius; dy <= kSearchRadius; ++dy) {
                for (int dx = -kSearchRadius; dx <= kSearchRadius; ++dx) {
                    if (dx == 0 && dy == 0)
                        continue;
                    const float cost =
                        blockSadSub(c, n, ox, oy, dx, dy) * invSub
                        + reg * static_cast<float>(std::abs(dx - pvx)
                                                 + std::abs(dy - pvy));
                    const int n2 = dx * dx + dy * dy;
                    if (cost < bestCost - tol
                        || (cost <= bestCost + tol && n2 < bestNorm2)) {
                        bestCost = cost;
                        bestDx = dx;
                        bestDy = dy;
                        bestNorm2 = n2;
                    }
                }
            }
            MotionVector mv;
            mv.dx = bestDx;
            mv.dy = bestDy;
            mv.mad = blockMadFull(c, n, ox, oy, bestDx, bestDy, meanOffset);
            fwd[static_cast<size_t>(by) * bw + bx] = mv;
        }
    }
}

} // namespace

void temporalMergeCfa(const uint16_t* center, const FrameInfo& info,
                      const uint16_t* prev, const uint16_t* next,
                      const RawMeta& meta, const TemporalSettings& settings,
                      uint16_t* out, TemporalReport* report) {
    if (report)
        *report = TemporalReport{};

    const size_t n = static_cast<size_t>(info.width) * info.height;
    if (!center || !out || n == 0)
        return;

    // Disabled / degenerate path: byte-exact copy (bit-identical to the
    // no-temporal pipeline by construction).
    if (settings.strength <= 0.0f) {
        std::memcpy(out, center, n * sizeof(uint16_t));
        return;
    }

    const double t0 = nowMs();

    // ---- proxies (center once, neighbors per-side) ----
    const LumaProxy cp = buildLumaProxy(center, info, meta);
    NeighborEval ev, en;
    if (prev) {
        ev.present = true;
        ev.proxy = buildLumaProxy(prev, info, meta);
    }
    if (next) {
        en.present = true;
        en.proxy = buildLumaProxy(next, info, meta);
    }
    const double t1 = nowMs();

    // Center proxy unusable (tiny frame): no matching possible; both
    // neighbors ineligible -> exact copy below.
    const bool centerOk = cp.w >= 2u * kBlockSize && cp.h >= 2u * kBlockSize;

    const double t2 = nowMs(); // matchMs measured across both sides below

    // ---- per-neighbor evaluation ----
    const float cap = kMaxNeighborWeight * std::min(std::max(settings.strength, 0.0f), 100.0f) / 100.0f;
    // Temporal-distance decay (atadenoise get_weights): a neighbor 1.5
    // frame-durations out carries 2/3 of the cap. dPts = 0 means
    // "adjacent / unspecified" (the bridge owns eligibility; direct
    // callers and tests) and decays nothing.
    const float capPrev = cap * distanceDecay(settings.prevDPtsUs, settings.frameDurationUs);
    const float capNext = cap * distanceDecay(settings.nextDPtsUs, settings.frameDurationUs);
    auto evaluate = [&](NeighborEval& e, const NoiseProfile* np, float capN,
                        float prevPairMafd, const std::vector<MotionVector>* seed,
                        float seedAccept) {
        if (!e.present || !centerOk || capN <= 0.0f)
            return;
        if (e.proxy.w != cp.w || e.proxy.h != cp.h)
            return; // dimension mismatch: reject, never blend
        float meanOffset = 0.0f;
        if (!globalGatesPass(cp, e.proxy, &meanOffset, &e.mafd, prevPairMafd))
            return; // exposure jump / scene cut (absolute or MAFD trend)

        // Forward (center -> neighbor) and backward block matching. Winner
        // MADs are de-biased by the pair's global mean difference (#9):
        // the floor and every residual gate read the motion content, not
        // the auto-exposure level step riding on top of it.
        // Item #4 (EPZS MV reuse, in-window only): the NEXT side's forward
        // search is SEEDED with the prev side's backward field — the
        // collocated predictor, velocity continuation one pair earlier —
        // and early-accepts a block whose best of {(0,0), predictor} is
        // already within kSeedAccept x the PREV pair's floor (the only
        // floor in hand before this pair's search; fixture: 98.1% of
        // blocks early-accept, 0.05 ms vs 1.04 ms, seeded floor -1.6% vs
        // full — not inflated). A miss runs the same exhaustive scan, so
        // the field stays a true argmin exactly where the predictor
        // failed. The seed is only ever a FULL-SEARCH field (the prev
        // side's bwd is exhaustive by construction), never a seeded one;
        // the BACKWARD field — the F/B consistency anchor — is never
        // seeded (inflating F/B agreement is the ghost direction); and
        // the PREV side has no earlier field in this window, so it stays
        // plain. Pass 2 re-decides every vector from scratch, so the
        // seeded pass-1 field's only surviving product is the floor.
        uint32_t fwdW = 0, fwdH = 0, bwdW = 0, bwdH = 0;
        if (seed && seedAccept > 0.0f)
            matchProxyBlocksSeeded(cp, e.proxy, *seed,
                                   kSeedAccept * seedAccept, e.fwd, fwdW,
                                   fwdH, meanOffset);
        else
            matchProxyBlocks(cp, e.proxy, e.fwd, fwdW, fwdH, meanOffset);
        matchProxyBlocks(e.proxy, cp, e.bwd, bwdW, bwdH, -meanOffset);
        e.bw = fwdW;
        e.bh = fwdH;
        if (fwdW == 0 || bwdW != fwdW || bwdH != fwdH)
            return;

        // MEASURED NOISE FLOOR (source-comparison item: ginzburg
        // _noise_floor, adapted from MSE to MAD): 25th percentile of the
        // pass-1 winner MAD field. This is the self-calibration the
        // metadata profile cannot provide — it measures what matching
        // actually achieves on THIS frame pair, so a metadata profile
        // claiming far more noise than the frames contain can no longer
        // admit changed content through the sigma taper: every block is
        // additionally bounded by kFloorGate x the measured floor.
        std::vector<float> mads(e.fwd.size());
        for (size_t i = 0; i < e.fwd.size(); ++i)
            mads[i] = e.fwd[i].mad;
        const size_t k = static_cast<size_t>(
            static_cast<float>(mads.size()) * kFloorQuantile);
        std::nth_element(mads.begin(), mads.begin() + static_cast<long>(k),
                         mads.end());
        e.noiseFloor = mads[k];

        // Pass 2: re-decide the forward vectors with the floor known
        // (zero-bias tie-break + spatial-predictor regularization).
        refineForward(cp, e.proxy, e.noiseFloor, e.fwd, fwdW, fwdH, meanOffset);

        e.blockWeight.assign(static_cast<size_t>(fwdW) * fwdH, 0.0f);
        int accepted = 0;
        for (uint32_t by = 0; by < fwdH; ++by) {
            for (uint32_t bx = 0; bx < fwdW; ++bx) {
                const size_t bi = static_cast<size_t>(by) * fwdW + bx;
                const uint32_t ox = bx * kBlockSize;
                const uint32_t oy = by * kBlockSize;

                // Block mean signal drives the noise model (slot 1 green,
                // the majority slot).
                float mean = 0.0f;
                for (int y = 0; y < kBlockSize; ++y)
                    for (int x = 0; x < kBlockSize; ++x)
                        mean += cp.v[static_cast<size_t>(oy + y) * cp.w + (ox + x)];
                mean /= float(kBlockSize * kBlockSize);
                const float sig = sigmaDiff(settings.centerNoise, np, mean, 1);

                // STATIC-FIRST block decision. The zero-motion residual
                // is measured first: when the block already agrees to
                // within the noise model (flat wall, sky, locked shot)
                // it merges AT ZERO MOTION with the sigma-taper
                // confidence. Matching cannot improve on zero motion
                // there — its noise-driven vectors are meaningless — so
                // this both fixes the flat-content case the F/B gate
                // alone used to reject and generalizes the earlier F/B
                // "rescue". The hard floor gate bounds the static path
                // too: a metadata sigma that is too generous cannot
                // admit a block whose zero-motion residual far exceeds
                // the MEASURED floor.
                const float mad0 = blockMadFull(cp, e.proxy, ox, oy, 0, 0,
                                                meanOffset);
                const float gate = kFloorGate * e.noiseFloor;
                MotionVector use{0, 0, mad0};
                float conf = 0.0f;
                const float r0 = sig > 1e-9f ? mad0 / sig : 1e9f;
                if (r0 <= kResidualTaperZero && mad0 <= gate) {
                    conf = residualTaper(r0);
                } else {
                    // MOTION path: F/B consistency (UnFlow criterion,
                    // 1.0 proxy-px per axis) + the hard floor gate on the
                    // warped residual + gain-ratio confidence (ginzburg
                    // _warp_confidence) MULTIPLIED BY the sigma taper on
                    // the warped residual. The gain ratio alone is not a
                    // safety gate: on decorrelated content the min over
                    // 81 offsets of a noisy cost field is always below
                    // the zero-motion cost, so "gain" is a property of
                    // selection bias, not of alignment — the 15-px-shift
                    // test proved it (best-of-81 garbage beat zero motion
                    // by > 10% everywhere). The sigma taper restores the
                    // absolute bound: the WARPED residual itself must sit
                    // within the noise model (full confidence at
                    // <= 1.5 sigma, zero at 3 sigma), and the gain ratio
                    // then scales how much of the improvement we trust.
                    const MotionVector& f = e.fwd[bi];
                    const MotionVector& b = e.bwd[bi];
                    if (std::abs(f.dx + b.dx) <= 1 && std::abs(f.dy + b.dy) <= 1
                        && f.mad <= gate) {
                        const float gain = (mad0 - f.mad)
                            / std::max(mad0, e.noiseFloor + 1e-12f);
                        const float gainC = std::min(std::max(gain, 0.0f), 1.0f);
                        const float rw = sig > 1e-9f ? f.mad / sig : 1e9f;
                        conf = residualTaper(rw) * gainC;
                        use = f;
                    }
                }
                // Confidence floor (ginzburg threshold): a "gain" below
                // 0.1 is matching noise on content that did not really
                // align — flat rejection is the conservative answer.
                if (conf >= kMinConfidence) {
                    e.fwd[bi] = use; // the merge warps by this vector
                    e.blockWeight[bi] = capN * conf;
                    ++accepted;
                }
            }
        }
        e.acceptRate = static_cast<float>(accepted) / static_cast<float>(fwdW * fwdH);
        // Motion storm / cut: too few confident blocks -> whole neighbor
        // rejected (weights stay 0).
        e.eligible = e.acceptRate >= kMinBlockAcceptRate;
        if (!e.eligible)
            std::fill(e.blockWeight.begin(), e.blockWeight.end(), 0.0f);
    };
    evaluate(ev, settings.prevNoise, capPrev, settings.prevPairMafd,
             nullptr, 0.0f);
    // Item #4: the next side's forward search is seeded from the prev
    // side's backward field, but ONLY when the prev side is ELIGIBLE
    // (global gates AND local block gates passed, so the fields exist and
    // the pair is measured-normal — the fixture case). A rejected prev
    // side leaves no seed and the next side runs the plain full scan:
    // the unmeasured configurations (motion storm, cut-adjacent) keep
    // the status-quo cost and the status-quo gate inputs — the
    // conservative choice under the owner's rule.
    const std::vector<MotionVector>* nextSeed =
        (ev.eligible
         && ev.bwd.size() == static_cast<size_t>(ev.bw) * ev.bh
         && ev.bw != 0 && ev.noiseFloor > 0.0f)
            ? &ev.bwd
            : nullptr;
    evaluate(en, settings.nextNoise, capNext, settings.nextPairMafd,
             nextSeed, ev.eligible ? ev.noiseFloor : 0.0f);
    const double t3 = nowMs();

    // ---- merge (or exact copy when no neighbor survived) ----
    const bool anyPrev = ev.eligible;
    const bool anyNext = en.eligible;
    if (!anyPrev && !anyNext) {
        std::memcpy(out, center, n * sizeof(uint16_t));
        if (report) {
            report->proxyMs = t1 - t0;
            report->matchMs = t3 - t2;
            report->mergeMs = 0.0;
            // Neighbor stats still matter in the all-rejected case — the
            // MAFD diagnostic (item #8) is exactly what explains a global
            // rejection; the merge-loop copy below is unreachable here.
            report->prev.present = ev.present;
            report->prev.eligible = ev.eligible;
            report->prev.acceptRate = ev.acceptRate;
            report->prev.noiseFloor = ev.noiseFloor;
            report->prev.mafd = ev.mafd;
            report->next.present = en.present;
            report->next.eligible = en.eligible;
            report->next.acceptRate = en.acceptRate;
            report->next.noiseFloor = en.noiseFloor;
            report->next.mafd = en.mafd;
        }
        return;
    }

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    // Full-res merge with overlap feathering (source-comparison item:
    // non-overlapping blocks leave visible weight steps at boundaries —
    // FFmpeg obmc_tab / ginzburg cosine feather). The per-block weight
    // field is spread over the outer quarter of each block edge with
    // linear ramps (50/50 at a boundary, continuous into the plateau),
    // applied as an outer product of per-axis ramps: a pixel blends its
    // containing block's warped sample with its band neighbors' samples.
    // CONSERVATIVE VARIANT, deliberately different from both references:
    // candidate weights renormalize at the pixel over ACCEPTED blocks
    // only — a rejected (occluded / moved) block contributes neither a
    // sample nor a weight, so its side of a boundary keeps the center
    // pixel intact instead of being dragged by a neighbor's vector. The
    // block grid is floor(proxy/16), so tail pixels clamp to the edge
    // block (both neighbors share the center-derived grid).
    const uint32_t maxBy = std::max(ev.eligible ? ev.bh - 1 : 0,
                                    en.eligible ? en.bh - 1 : 0);
    const uint32_t maxBx = std::max(ev.eligible ? ev.bw - 1 : 0,
                                    en.eligible ? en.bw - 1 : 0);
    double wSumPrev = 0.0;
    double wSumNext = 0.0;
    long long counted = 0;
    for (uint32_t y = 0; y < h; ++y) {
        const size_t row = static_cast<size_t>(y) * w;
        const uint32_t by = std::min(y >> kBlockCfaShift, maxBy);
        const uint32_t uY = y & (kBlockCfa - 1);
        int offY[2];
        float wY[2];
        const int ncy = axisCandidates(uY, by > 0, by + 1 < maxBy + 1, offY, wY);
        for (uint32_t x = 0; x < w; ++x) {
            const uint32_t bx = std::min(x >> kBlockCfaShift, maxBx);
            const uint32_t uX = x & (kBlockCfa - 1);
            int offX[2];
            float wX[2];
            const int ncx = axisCandidates(uX, bx > 0, bx + 1 < maxBx + 1, offX, wX);
            const int slot = cellSlot(x, y);
            const float cSignal = normSignal(center[row + x], meta, slot);
            // Per-pixel guard sigma (same for every candidate block of
            // this pixel: it depends only on the center sample).
            const float sigP = sigmaDiff(settings.centerNoise, settings.prevNoise,
                                         cSignal, slot);
            const float sigN = sigmaDiff(settings.centerNoise, settings.nextNoise,
                                         cSignal, slot);
            float acc = cSignal; // weight 1.0 center, always
            float wsum = 1.0f;
            if (anyPrev) {
                for (int iy = 0; iy < ncy; ++iy) {
                    const uint32_t byi =
                        static_cast<uint32_t>(static_cast<int>(by) + offY[iy]);
                    for (int ix = 0; ix < ncx; ++ix) {
                        const uint32_t bxi =
                            static_cast<uint32_t>(static_cast<int>(bx) + offX[ix]);
                        const size_t bi = static_cast<size_t>(byi) * ev.bw + bxi;
                        const float bw_ = ev.blockWeight[bi];
                        if (bw_ <= 0.0f)
                            continue; // rejected block: no sample, no weight
                        const float wgt = wX[ix] * wY[iy];
                        const MotionVector& mv = ev.fwd[bi];
                        const int nx = static_cast<int>(x) + proxyToCfa(mv.dx);
                        const int ny = static_cast<int>(y) + proxyToCfa(mv.dy);
                        if (nx < 0 || nx >= static_cast<int>(w)
                            || ny < 0 || ny >= static_cast<int>(h))
                            continue;
                        const float nSignal =
                            normSignal(prev[static_cast<size_t>(ny) * w + nx],
                                       meta, slot);
                        // Per-pixel occlusion guard: a residual beyond
                        // 6 sigma zeroes THIS pixel's weight only.
                        const float d = std::fabs(nSignal - cSignal);
                        if (sigP > 1e-9f && d <= kPixelGuardSigma * sigP) {
                            acc += bw_ * wgt * nSignal;
                            wsum += bw_ * wgt;
                            wSumPrev += bw_ * wgt;
                        }
                    }
                }
            }
            if (anyNext) {
                for (int iy = 0; iy < ncy; ++iy) {
                    const uint32_t byi =
                        static_cast<uint32_t>(static_cast<int>(by) + offY[iy]);
                    for (int ix = 0; ix < ncx; ++ix) {
                        const uint32_t bxi =
                            static_cast<uint32_t>(static_cast<int>(bx) + offX[ix]);
                        const size_t bi = static_cast<size_t>(byi) * en.bw + bxi;
                        const float bw_ = en.blockWeight[bi];
                        if (bw_ <= 0.0f)
                            continue;
                        const float wgt = wX[ix] * wY[iy];
                        const MotionVector& mv = en.fwd[bi];
                        const int nx = static_cast<int>(x) + proxyToCfa(mv.dx);
                        const int ny = static_cast<int>(y) + proxyToCfa(mv.dy);
                        if (nx < 0 || nx >= static_cast<int>(w)
                            || ny < 0 || ny >= static_cast<int>(h))
                            continue;
                        const float nSignal =
                            normSignal(next[static_cast<size_t>(ny) * w + nx],
                                       meta, slot);
                        const float d = std::fabs(nSignal - cSignal);
                        if (sigN > 1e-9f && d <= kPixelGuardSigma * sigN) {
                            acc += bw_ * wgt * nSignal;
                            wsum += bw_ * wgt;
                            wSumNext += bw_ * wgt;
                        }
                    }
                }
            }

            // Back to raw DN with this frame's normalization (the inverse
            // of stage 1; the develop pipeline re-normalizes identically).
            const float black = static_cast<float>(meta.black[slot]);
            const float white = static_cast<float>(meta.white);
            const float range = white > black ? white - black : 1.0f;
            const float dn = acc / wsum * range + black;
            const long v = std::lround(dn);
            out[row + x] = static_cast<uint16_t>(
                std::min(std::max(v, 0L), static_cast<long>(65535)));
            ++counted;
        }
    }
    const double t4 = nowMs();

    if (report) {
        report->proxyMs = t1 - t0;
        report->matchMs = t3 - t2;
        report->mergeMs = t4 - t3;
        report->prev.present = ev.present;
        report->prev.eligible = ev.eligible;
        report->prev.acceptRate = ev.acceptRate;
        report->prev.meanWeight = counted > 0
            ? static_cast<float>(wSumPrev / static_cast<double>(counted))
            : 0.0f;
        report->prev.noiseFloor = ev.noiseFloor;
        report->prev.mafd = ev.mafd;
        report->next.present = en.present;
        report->next.eligible = en.eligible;
        report->next.acceptRate = en.acceptRate;
        report->next.meanWeight = counted > 0
            ? static_cast<float>(wSumNext / static_cast<double>(counted))
            : 0.0f;
        report->next.noiseFloor = en.noiseFloor;
        report->next.mafd = en.mafd;
    }
}

} // namespace rawedit
