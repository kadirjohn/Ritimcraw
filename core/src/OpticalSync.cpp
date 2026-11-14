// rawedit::OpticalSync — implementation (GY-04). The method, the
// mapping contract and the honesty rules are documented in
// OpticalSync.h; this file holds the mechanics: luma preparation, the
// documented 2-pass translation search, the MAD gate, the Huber cost
// sweep, the curvature confidence and the window logic.
//
// Sync.cpp stays untouched by all of this (I/O-free clock model);
// only applyOpticalRefinement below writes SyncEstimate fields, and
// only additively.

#include <rawedit/OpticalSync.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

#include <rawedit/Stabilization.h>

namespace rawedit {

namespace {

// ---------------------------------------------------------------------------
// Luma preparation (per frame, once): the ImageF is linear RGB; the
// refinement works on a normalized luma so thresholds are
// scale-independent.
// ---------------------------------------------------------------------------

// Normalized luma (Rec.709 weights) from the linear RGB image. Empty
// image -> empty vector. Values stay in [0,1] when the RGB does.
std::vector<float> lumaOf(const ImageF& img) {
    std::vector<float> l(static_cast<size_t>(img.w) * img.h, 0.0f);
    if (img.w == 0 || img.h == 0)
        return l;
    const size_t n = l.size();
    for (size_t i = 0; i < n; ++i) {
        const float r = img.rgb[3 * i + 0];
        const float g = img.rgb[3 * i + 1];
        const float b = img.rgb[3 * i + 2];
        l[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
    return l;
}

// Box blur (radius 1 -> 3x3) of the luma plane; the low-pass that kills
// per-pixel noise before the translation search (the matched pattern
// must be the scene, not the noise). Border: replicate the edge.
std::vector<float> blurLuma(const std::vector<float>& l, uint32_t w, uint32_t h) {
    if (w == 0 || h == 0 || l.size() != static_cast<size_t>(w) * h)
        return l;
    std::vector<float> out(l.size(), 0.0f);
    for (uint32_t y = 0; y < h; ++y) {
        const uint32_t y0 = y > 0 ? y - 1 : 0;
        const uint32_t y2 = y + 1 < h ? y + 1 : h - 1;
        for (uint32_t x = 0; x < w; ++x) {
            const uint32_t x0 = x > 0 ? x - 1 : 0;
            const uint32_t x2 = x + 1 < w ? x + 1 : w - 1;
            const float s =
                l[static_cast<size_t>(y0) * w + x0] + l[static_cast<size_t>(y0) * w + x] +
                l[static_cast<size_t>(y0) * w + x2] + l[static_cast<size_t>(y) * w + x0] +
                l[static_cast<size_t>(y) * w + x] + l[static_cast<size_t>(y) * w + x2] +
                l[static_cast<size_t>(y2) * w + x0] + l[static_cast<size_t>(y2) * w + x] +
                l[static_cast<size_t>(y2) * w + x2];
            out[static_cast<size_t>(y) * w + x] = s * (1.0f / 9.0f);
        }
    }
    return out;
}

struct Plane {
    uint32_t w = 0, h = 0;
    std::vector<float> v;
};

// 4x box decimation of the blurred luma (pass-1 search plane). Averages
// each 4x4 block; the last partial block is skipped (the search region
// keeps a border anyway).
Plane downsample4(const std::vector<float>& l, uint32_t w, uint32_t h) {
    Plane p;
    if (w < 4 || h < 4)
        return p;
    p.w = w / 4;
    p.h = h / 4;
    p.v.assign(static_cast<size_t>(p.w) * p.h, 0.0f);
    for (uint32_t y = 0; y < p.h; ++y)
        for (uint32_t x = 0; x < p.w; ++x) {
            float s = 0.0f;
            for (uint32_t dy = 0; dy < 4; ++dy)
                for (uint32_t dx = 0; dx < 4; ++dx)
                    s += l[static_cast<size_t>(4 * y + dy) * w + 4 * x + dx];
            p.v[static_cast<size_t>(y) * p.w + x] = s * (1.0f / 16.0f);
        }
    return p;
}

// ---------------------------------------------------------------------------
// The measured translation between consecutive frames (the IMAGE side
// of the residual; computed ONCE per pair — it does not depend on the
// candidate offset).
//
// DOCUMENTED CHOICE (brief): a 2-pass coarse-to-fine translation
// search, not phase correlation —
//   pass 1: +-16 INTEGER steps on the 4x-decimated luma (the search's
//           +-64 thumbnail px of headroom for fast motion),
//   pass 2: +-2 px at 0.5 px steps (bilinear) around pass 1's best on
//           the blurred full-res luma,
// metric SAD over a fixed central region (the border is excluded: the
// production warp's edge clamp smears content inward, and a compared
// region that includes smeared borders biases the measured shift).
// Chosen because it is exactly testable against synthetic ground truth
// and needs no FFT dependency; the brief explicitly allows it.
//
// SIGN CONVENTION (binding, pinned by the synthetic test): the returned
// (dx, dy) is the CONTENT displacement from the earlier frame to the
// later frame — the position change of a scene point. The SAD search
// finds the shift s minimizing sum|a(x+s)-b(x)|, which is s = -dx (the
// content at x-s in a sits at x in b); the negation happens here so the
// residual consumer compares like with like (predictedTranslation
// returns the same content-displacement convention).
// ---------------------------------------------------------------------------

struct PairMatch {
    double dx = 0.0, dy = 0.0; // CONTENT displacement, earlier -> later (px)
    double score = 0.0;        // (sadMedian - sadBest)/sadMedian, [0,1]
    bool accepted = false;     // texture + match-score acceptance
};

// SAD over the central region (border px excluded) between plane a
// shifted by (sdx, sdy) and plane b, integer sampling. Returns a huge
// value when the region does not fit.
double sadCentered(const Plane& a, const Plane& b, int sdx, int sdy, uint32_t border) {
    if (a.w != b.w || a.h != b.h || a.v.empty())
        return std::numeric_limits<double>::max();
    const int x0 = static_cast<int>(border);
    const int x1 = static_cast<int>(a.w) - static_cast<int>(border);
    const int y0 = static_cast<int>(border);
    const int y1 = static_cast<int>(a.h) - static_cast<int>(border);
    if (x1 - x0 < 8 || y1 - y0 < 8)
        return std::numeric_limits<double>::max();
    double sad = 0.0;
    for (int y = y0; y < y1; ++y) {
        const int sy = y + sdy;
        if (sy < 0 || sy >= static_cast<int>(a.h))
            return std::numeric_limits<double>::max();
        const float* ra = a.v.data() + static_cast<size_t>(sy) * a.w;
        const float* rb = b.v.data() + static_cast<size_t>(y) * b.w;
        for (int x = x0; x < x1; ++x) {
            const int sx = x + sdx;
            if (sx < 0 || sx >= static_cast<int>(a.w))
                return std::numeric_limits<double>::max();
            sad += std::fabs(static_cast<double>(ra[sx]) - rb[x]);
        }
    }
    return sad;
}

// Bilinear sample of a plane, edge-clamped.
float sampleBilinear(const Plane& p, double x, double y) {
    if (p.w == 0 || p.h == 0)
        return 0.0f;
    if (x < 0.0) x = 0.0;
    if (y < 0.0) y = 0.0;
    if (x > static_cast<double>(p.w) - 1.0) x = static_cast<double>(p.w) - 1.0;
    if (y > static_cast<double>(p.h) - 1.0) y = static_cast<double>(p.h) - 1.0;
    const int x0 = static_cast<int>(x);
    const int y0 = static_cast<int>(y);
    const int x1 = x0 + 1 < static_cast<int>(p.w) ? x0 + 1 : x0;
    const int y1 = y0 + 1 < static_cast<int>(p.h) ? y0 + 1 : y0;
    const double fx = x - x0, fy = y - y0;
    const auto at = [&](int xx, int yy) {
        return p.v[static_cast<size_t>(yy) * p.w + xx];
    };
    const double top = at(x0, y0) * (1.0 - fx) + at(x1, y0) * fx;
    const double bot = at(x0, y1) * (1.0 - fx) + at(x1, y1) * fx;
    return static_cast<float>(top * (1.0 - fy) + bot * fy);
}

// SAD over the central region with bilinear sampling at a SUB-PIXEL
// shift (pass 2). Same border exclusion.
double sadCenteredSubpixel(const Plane& a, const Plane& b, double sdx, double sdy,
                           uint32_t border) {
    if (a.w != b.w || a.h != b.h || a.v.empty())
        return std::numeric_limits<double>::max();
    const int x0 = static_cast<int>(border);
    const int x1 = static_cast<int>(a.w) - static_cast<int>(border);
    const int y0 = static_cast<int>(border);
    const int y1 = static_cast<int>(a.h) - static_cast<int>(border);
    if (x1 - x0 < 8 || y1 - y0 < 8)
        return std::numeric_limits<double>::max();
    double sad = 0.0;
    for (int y = y0; y < y1; ++y) {
        const double sy = static_cast<double>(y) + sdy;
        if (sy < -1.0 || sy > static_cast<double>(a.h))
            return std::numeric_limits<double>::max();
        for (int x = x0; x < x1; ++x) {
            const double sx = static_cast<double>(x) + sdx;
            if (sx < -1.0 || sx > static_cast<double>(a.w))
                return std::numeric_limits<double>::max();
            sad += std::fabs(static_cast<double>(sampleBilinear(a, sx, sy)) -
                             b.v[static_cast<size_t>(y) * b.w + x]);
        }
    }
    return sad;
}

// The 2-pass search for one pair. `a` is the earlier frame's blurred
// luma plane, `b` the later's (a4/b4 their 4x decimations). Returns the
// CONTENT displacement (sign convention above) plus acceptance data.
PairMatch measurePair(const Plane& a, const Plane& b, const Plane& a4,
                      const Plane& b4, const OpticalSyncOpts& opts) {
    PairMatch m;
    if (a.v.empty() || b.v.empty() || a4.v.empty() || b4.v.empty())
        return m;

    // --- pass 1: +-16 integer steps on the 4x-decimated luma. Border 16
    // (decimated px) makes every candidate shift VALID (the compared
    // region stays inside the plane), i.e. the documented +-64
    // thumbnail-px headroom is real, not clipped by validity (the
    // region-size check in sadCentered guards smaller planes). ---
    const uint32_t border4 = 16;
    double bestSad = std::numeric_limits<double>::max();
    double medianSad = 0.0;
    int bestDx = 0, bestDy = 0;
    {
        // Collect FINITE sads only for the median baseline: shifts that
        // push the region off the plane return HUGE, and a median
        // polluted with HUGE would make every pair trivially "accepted"
        // (the weak-match gate would never fire).
        std::vector<double> sads;
        sads.reserve(33 * 33);
        for (int dy = -16; dy <= 16; ++dy)
            for (int dx = -16; dx <= 16; ++dx) {
                const double s = sadCentered(a4, b4, dx, dy, border4);
                if (s == std::numeric_limits<double>::max())
                    continue;
                sads.push_back(s);
                if (s < bestSad) {
                    bestSad = s;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        if (sads.empty())
            return m; // region does not fit the decimated plane at all
        std::vector<double> sorted(sads);
        std::sort(sorted.begin(), sorted.end());
        medianSad = sorted[sorted.size() / 2];
    }

    // --- pass 2: +-2 px at 0.5 px steps around pass 1's best (scaled
    // 4x), on the full blurred luma. Border 64 px matches pass 1's
    // reach so every pass-1 best stays evaluable here; it also excludes
    // the develop/warp edge artifacts (the thumbnail decimation's edge
    // cells, the production warp's edge-clamp smear) from the compared
    // region — the documented 64 px border. ---
    const uint32_t border = 64;
    const double cx = static_cast<double>(bestDx) * 4.0;
    const double cy = static_cast<double>(bestDy) * 4.0;
    double fineSad = std::numeric_limits<double>::max();
    double fineSx = cx, fineSy = cy;
    for (int sy = -4; sy <= 4; ++sy)
        for (int sx = -4; sx <= 4; ++sx) {
            const double sdx = cx + static_cast<double>(sx) * 0.5;
            const double sdy = cy + static_cast<double>(sy) * 0.5;
            const double s = sadCenteredSubpixel(a, b, sdx, sdy, border);
            if (s < fineSad) {
                fineSad = s;
                fineSx = sdx;
                fineSy = sdy;
            }
        }

    // Content displacement = -(best shift) (sign convention above). When
    // no sub-pixel candidate was valid (tiny images), pass 1's integer
    // best stands — honest coarse evidence, never a fabricated 0.
    m.dx = -fineSx;
    m.dy = -fineSy;

    // Acceptance (on ONE plane — pass 1's, a scale-free ratio): a real
    // match must beat the median shift by a meaningful fraction (a
    // flat/noise patch matches everything; its best-vs-median gap is
    // ~0). Pass 2 only refines the displacement; it does not change the
    // verdict.
    m.score = medianSad > 1e-12 ? (medianSad - bestSad) / medianSad : 0.0;
    m.accepted = m.score >= opts.minMatchScore;
    return m;
}

// ---------------------------------------------------------------------------
// The predicted translation (the GYRO side; depends on the candidate
// offset): q_rel between the two frames' orientations at the candidate
// times, through frameTransform — the SAME homography the production
// warp uses — evaluated at the principal point.
//
// H = frameTransform(conj(q_j)*q_i) maps frame-i (SOURCE) pixels to
// frame-j (OUTPUT) pixels (the production's actual->target direction;
// frameTransform is a homomorphism on the D-conjugated rotations, so
// source=i/target=j composes exactly like the production warp). The
// content at frame-i's center lands at H(center) in frame j, so the
// predicted CONTENT displacement is H(center) - center — the same
// convention measurePair returns.
// ---------------------------------------------------------------------------
void predictedTranslation(const double qRel[4], double focalLengthPx, uint32_t w,
                          uint32_t h, double& dx, double& dy) {
    double H[9];
    frameTransform(qRel, focalLengthPx, static_cast<int>(w), static_cast<int>(h), H);
    const double cx = static_cast<double>(w) / 2.0;
    const double cy = static_cast<double>(h) / 2.0;
    const double mappedX = H[0] * cx + H[1] * cy + H[2];
    const double mappedY = H[3] * cx + H[4] * cy + H[5];
    const double mappedZ = H[6] * cx + H[7] * cy + H[8];
    if (std::fabs(mappedZ) < 1e-12) {
        dx = 0.0;
        dy = 0.0;
        return;
    }
    dx = mappedX / mappedZ - cx;
    dy = mappedY / mappedZ - cy;
}

// Huber loss on the residual magnitude.
double huber(double r, double delta) {
    if (r <= delta)
        return 0.5 * r * r;
    return delta * (r - 0.5 * delta);
}

// ---------------------------------------------------------------------------
// Cost evaluation (shared by the coarse sweep, the fine sweep and the
// curvature probes): the Huber sum over the window's currently-accepted
// pairs at one candidate offset.
// ---------------------------------------------------------------------------

struct WindowInput {
    size_t firstPair = 0; // index into the pair arrays
    size_t pairCount = 0;
};

double windowCost(const WindowInput& win,
                  const std::vector<std::pair<size_t, size_t>>& pairs,
                  const std::vector<int64_t>& repTimes,
                  const std::vector<PairMatch>& matches,
                  const OrientationSeries& series,
                  const OpticalSyncIntrinsics& intr,
                  const OpticalSyncOpts& opts,
                  uint32_t w, uint32_t h, int64_t offsetNs) {
    double cost = 0.0;
    for (size_t k = 0; k < win.pairCount; ++k) {
        const size_t pi = win.firstPair + k;
        if (!matches[pi].accepted)
            continue;
        const size_t i = pairs[pi].first;
        const size_t j = pairs[pi].second;
        const PairMatch& m = matches[pi];
        const int64_t ti = repTimes[i] + offsetNs;
        const int64_t tj = repTimes[j] + offsetNs;
        double qi[4], qj[4];
        series.orientationAt(ti, qi);
        series.orientationAt(tj, qj);
        double qc[4];
        quatConj(qj, qc);
        double qrel[4];
        quatMultiply(qc, qi, qrel);
        double pdx = 0.0, pdy = 0.0;
        predictedTranslation(qrel, intr.focalLengthPx, w, h, pdx, pdy);
        const double rx = m.dx - pdx;
        const double ry = m.dy - pdy;
        cost += huber(std::sqrt(rx * rx + ry * ry), opts.huberDeltaPx);
    }
    return cost;
}

// The coarse sweep: the cost over [anchor-range, anchor+range] at the
// coarse step. Fills costCurve/offsetsCurve and returns the best offset.
int64_t sweepCoarse(const WindowInput& win,
                    const std::vector<std::pair<size_t, size_t>>& pairs,
                    const std::vector<int64_t>& repTimes,
                    const std::vector<PairMatch>& matches,
                    const OrientationSeries& series,
                    const OpticalSyncIntrinsics& intr,
                    const OpticalSyncOpts& opts,
                    uint32_t w, uint32_t h,
                    int64_t anchorOffsetNs,
                    std::vector<double>& costCurve,
                    std::vector<int64_t>& offsetsCurve) {
    const int64_t stepNs = static_cast<int64_t>(std::llround(opts.coarseStepMs * 1e6));
    const int64_t rangeNs = static_cast<int64_t>(std::llround(opts.searchRangeMs * 1e6));
    costCurve.clear();
    offsetsCurve.clear();
    double bestCost = std::numeric_limits<double>::max();
    int64_t bestOffset = anchorOffsetNs;
    for (int64_t off = anchorOffsetNs - rangeNs; off <= anchorOffsetNs + rangeNs;
         off += stepNs) {
        const double cost =
            windowCost(win, pairs, repTimes, matches, series, intr, opts, w, h, off);
        costCurve.push_back(cost);
        offsetsCurve.push_back(off);
        if (cost < bestCost) {
            bestCost = cost;
            bestOffset = off;
        }
    }
    return bestOffset;
}

} // namespace

// ---------------------------------------------------------------------------
// refineOffsetOptically — the public entry (the header carries the
// contract; the flow: validate -> windows -> per-window refinement ->
// cross-window agreement -> provenance).
// ---------------------------------------------------------------------------
OpticalSyncResult refineOffsetOptically(const SyncEstimate& anchor,
                                       const std::vector<OpticalSyncFrame>& frames,
                                       const OrientationSeries& orientationSeries,
                                       const OpticalSyncIntrinsics& intrinsics,
                                       const OpticalSyncOpts& opts) {
    OpticalSyncResult out;

    // ---- validation: never a crash, never a guess ----
    if (frames.size() < 2) {
        out.provenance = "refine-rejected reason=no-frames";
        return out;
    }
    if (orientationSeries.empty()) {
        out.provenance = "refine-rejected reason=no-series";
        return out;
    }
    if (!(intrinsics.focalLengthPx > 0.0)) {
        out.provenance = "refine-rejected reason=no-focal";
        return out;
    }
    const uint32_t w = frames[0].image.w;
    const uint32_t h = frames[0].image.h;
    if (w < 64 || h < 36) {
        out.provenance = "refine-rejected reason=image-too-small";
        return out;
    }
    for (const OpticalSyncFrame& f : frames) {
        if (f.image.w != w || f.image.h != h) {
            out.provenance = "refine-rejected reason=image-size-mismatch";
            return out;
        }
    }
    for (size_t i = 1; i < frames.size(); ++i) {
        if (frames[i].captureNs <= frames[i - 1].captureNs) {
            out.provenance = "refine-rejected reason=frames-unsorted";
            return out;
        }
    }

    // Representative times (the MAPPING CONTRACT: the refined offset is
    // defined against repTimeNs; frames without the correction fall
    // back to the raw capture stamp — the caller states which).
    std::vector<int64_t> repTimes(frames.size());
    for (size_t i = 0; i < frames.size(); ++i)
        repTimes[i] =
            frames[i].repTimeNs != 0 ? frames[i].repTimeNs : frames[i].captureNs;

    // ---- luma preparation (once per frame) ----
    std::vector<Plane> blur(frames.size());
    std::vector<Plane> dec4(frames.size());
    for (size_t i = 0; i < frames.size(); ++i) {
        const std::vector<float> l = lumaOf(frames[i].image);
        blur[i].w = w;
        blur[i].h = h;
        blur[i].v = blurLuma(l, w, h);
        dec4[i] = downsample4(blur[i].v, w, h);
    }

    // Weak-texture rejection: the blurred luma's variance must clear
    // the floor (a flat wall matches any shift; its residuals are pure
    // noise — the audit's "reject low-motion/uninformative" frames).
    std::vector<bool> textured(frames.size(), false);
    for (size_t i = 0; i < frames.size(); ++i) {
        const std::vector<float>& v = blur[i].v;
        if (v.empty())
            continue;
        double mean = 0.0;
        for (float f : v)
            mean += f;
        mean /= static_cast<double>(v.size());
        double var = 0.0;
        for (float f : v)
            var += (f - mean) * (f - mean);
        var /= static_cast<double>(v.size());
        textured[i] = var >= opts.minLumaVariance;
    }

    // ---- consecutive pairs (the residual's unit) ----
    std::vector<std::pair<size_t, size_t>> pairs;
    std::vector<PairMatch> matches;
    pairs.reserve(frames.size() - 1);
    matches.reserve(frames.size() - 1);
    for (size_t i = 0; i + 1 < frames.size(); ++i) {
        PairMatch m = measurePair(blur[i], blur[i + 1], dec4[i], dec4[i + 1], opts);
        m.accepted = m.accepted && textured[i] && textured[i + 1];
        pairs.emplace_back(i, i + 1);
        matches.push_back(m);
    }

    // ---- 3 windows: early/middle/late thirds of the pairs, capped ----
    const size_t totalPairs = pairs.size();
    const size_t perWindow = std::min<size_t>(totalPairs, opts.maxPairsPerWindow);
    if (perWindow < 3) {
        out.provenance = "refine-rejected reason=too-few-pairs";
        return out;
    }
    out.windows.resize(3);
    std::vector<WindowInput> win(3);
    for (int k = 0; k < 3; ++k) {
        const size_t thirdStart = totalPairs * static_cast<size_t>(k) / 3;
        const size_t thirdEnd = totalPairs * static_cast<size_t>(k + 1) / 3;
        const size_t thirdLen = thirdEnd > thirdStart ? thirdEnd - thirdStart : 0;
        if (thirdLen == 0) {
            win[k].firstPair = thirdStart;
            win[k].pairCount = 0;
            continue;
        }
        const size_t count = std::min(thirdLen, perWindow);
        // Center the capped selection inside the third.
        win[k].firstPair = thirdStart + (thirdLen - count) / 2;
        win[k].pairCount = count;
    }

    // ---- per-window refinement ----
    const int64_t anchorOffsetNs = anchor.model.offsetNs;
    for (int k = 0; k < 3; ++k) {
        OpticalSyncWindowResult& wr = out.windows[static_cast<size_t>(k)];
        wr.pairsTotal = win[k].pairCount;
        size_t accepted = 0;
        for (size_t kk = 0; kk < win[k].pairCount; ++kk)
            if (matches[win[k].firstPair + kk].accepted)
                ++accepted;
        wr.pairsAccepted = accepted;

        if (win[k].pairCount == 0 || accepted < 3) {
            wr.note = "no-evidence";
            continue;
        }

        // Informativeness: gyro angular-velocity variance over the
        // window x the acceptance rate (the audit's scoring). The
        // per-pair rotation angle between the candidate-time
        // orientations at the ANCHOR offset (a ranking statistic only —
        // it does not bias the search).
        {
            std::vector<double> angles;
            angles.reserve(win[k].pairCount);
            for (size_t kk = 0; kk < win[k].pairCount; ++kk) {
                const size_t i = pairs[win[k].firstPair + kk].first;
                const size_t j = pairs[win[k].firstPair + kk].second;
                double qi[4], qj[4], qrel[4];
                orientationSeries.orientationAt(repTimes[i] + anchorOffsetNs, qi);
                orientationSeries.orientationAt(repTimes[j] + anchorOffsetNs, qj);
                relativeRotation(qi, qj, qrel);
                angles.push_back(relativeRotationAngleDeg(qrel));
            }
            double mean = 0.0;
            for (double a : angles)
                mean += a;
            mean /= static_cast<double>(angles.size());
            double var = 0.0;
            for (double a : angles)
                var += (a - mean) * (a - mean);
            var /= static_cast<double>(angles.size());
            const double acceptRate =
                static_cast<double>(accepted) / static_cast<double>(win[k].pairCount);
            wr.score = var * acceptRate;
        }

        // ---- coarse sweep on the ACCEPTED pairs ----
        std::vector<double> costCurve;
        std::vector<int64_t> offsetsCurve;
        const int64_t coarseBest =
            sweepCoarse(win[k], pairs, repTimes, matches, orientationSeries, intrinsics,
                       opts, w, h, anchorOffsetNs, costCurve, offsetsCurve);

        // ---- MAD outlier gate (at the coarse best): reject residual
        // magnitudes > median + scale*1.4826*MAD, CAPPED at the top
        // quartile of the window's accepted pairs. The gate runs at the
        // coarse best because outliers must be identified before they
        // can drag the fine minimum; the cap keeps the gate from
        // rejecting the evidence that exposes a wrong offset.
        // DEGENERATE-CASE HONESTY: when nearly all residuals are tiny
        // and near-equal (a sharp well with clean data), the MAD gate
        // threshold hugs the median and would flag the honest pairs
        // with the largest-but-still-small residuals — the gate then
        // rejects nothing (residual-magnitude floor: only residuals
        // above the Huber knee are even GATEABLE; a pair already at
        // zero cost contributes nothing to reject). ----
        std::vector<double> residMag;
        residMag.reserve(accepted);
        for (size_t kk = 0; kk < win[k].pairCount; ++kk) {
            const size_t pi = win[k].firstPair + kk;
            if (!matches[pi].accepted)
                continue;
            const size_t i = pairs[pi].first;
            const size_t j = pairs[pi].second;
            const PairMatch& m = matches[pi];
            const int64_t ti = repTimes[i] + coarseBest;
            const int64_t tj = repTimes[j] + coarseBest;
            double qi[4], qj[4];
            orientationSeries.orientationAt(ti, qi);
            orientationSeries.orientationAt(tj, qj);
            double qc[4];
            quatConj(qj, qc);
            double qrel[4];
            quatMultiply(qc, qi, qrel);
            double pdx = 0.0, pdy = 0.0;
            predictedTranslation(qrel, intrinsics.focalLengthPx, w, h, pdx, pdy);
            const double rx = m.dx - pdx;
            const double ry = m.dy - pdy;
            residMag.push_back(std::sqrt(rx * rx + ry * ry));
        }
        double medResid = 0.0, mad = 0.0;
        {
            std::vector<double> sorted(residMag);
            std::sort(sorted.begin(), sorted.end());
            medResid = sorted[sorted.size() / 2];
            std::vector<double> absDev(sorted.size());
            for (size_t i = 0; i < sorted.size(); ++i)
                absDev[i] = std::fabs(sorted[i] - medResid);
            std::sort(absDev.begin(), absDev.end());
            mad = absDev[absDev.size() / 2];
        }
        const double gate = medResid + opts.madSigmaScale * 1.4826 * mad;
        // Residual-magnitude floor: only residuals above one Huber knee
        // are gateable (see the comment above).
        const double gateFloor = opts.huberDeltaPx;
        std::vector<size_t> gateIdx;
        for (size_t idx = 0; idx < residMag.size(); ++idx)
            if (residMag[idx] > gate && residMag[idx] > gateFloor)
                gateIdx.push_back(idx);
        const size_t cap = accepted / 4; // top quartile, hard cap
        if (gateIdx.size() > cap) {
            // Keep the MOST outlying (largest residual) up to the cap.
            std::sort(gateIdx.begin(), gateIdx.end(),
                      [&](size_t a, size_t b) { return residMag[a] > residMag[b]; });
            gateIdx.resize(cap);
        }
        // gateIdx indexes the accepted-subset array in window order;
        // map back to the window's pair slots and flip acceptance for
        // the fine pass.
        {
            size_t acc = 0;
            for (size_t kk = 0; kk < win[k].pairCount; ++kk) {
                const size_t pi = win[k].firstPair + kk;
                if (!matches[pi].accepted)
                    continue;
                if (std::find(gateIdx.begin(), gateIdx.end(), acc) != gateIdx.end())
                    matches[pi].accepted = false;
                ++acc;
            }
        }
        size_t used = 0;
        for (size_t kk = 0; kk < win[k].pairCount; ++kk)
            if (matches[win[k].firstPair + kk].accepted)
                ++used;
        wr.pairsUsed = used;
        if (used < 3) {
            wr.note = "mad-gate-depleted";
            continue;
        }

        // ---- fine sweep on the surviving pairs ----
        const int64_t fineStepNs =
            static_cast<int64_t>(std::llround(opts.fineStepMs * 1e6));
        const int64_t fineRangeNs =
            static_cast<int64_t>(std::llround(opts.fineRangeMs * 1e6));
        double fineCost = std::numeric_limits<double>::max();
        int64_t fineBest = coarseBest;
        for (int64_t off = coarseBest - fineRangeNs; off <= coarseBest + fineRangeNs;
             off += fineStepNs) {
            const double cost = windowCost(win[k], pairs, repTimes, matches,
                                          orientationSeries, intrinsics, opts, w, h,
                                          off);
            if (cost < fineCost) {
                fineCost = cost;
                fineBest = off;
            }
        }

        // ---- confidence: peakiness + curvature on the curve of the
        // SURVIVING pairs (the costs the verdict leans on must come
        // from the pairs the estimate actually used) ----
        double cmin = std::numeric_limits<double>::max();
        double cmed = 0.0;
        {
            std::vector<double> usedCurve;
            std::vector<int64_t> usedOffsets;
            // Re-run the coarse sweep reading the POST-gate acceptance.
            sweepCoarse(win[k], pairs, repTimes, matches, orientationSeries,
                       intrinsics, opts, w, h, anchorOffsetNs, usedCurve, usedOffsets);
            std::vector<double> sorted(usedCurve);
            std::sort(sorted.begin(), sorted.end());
            cmin = sorted.front();
            cmed = sorted[sorted.size() / 2];
        }
        cmin = std::min(cmin, fineCost); // the fine best may dip lower
        const double peakiness = cmed > 1e-12 ? 1.0 - cmin / cmed : 0.0;
        wr.peakiness = peakiness;

        // Curvature at the fine minimum: parabola through the fine cost
        // at fineBest-step and fineBest+step (per fineStepMs^2).
        const double cMinus =
            windowCost(win[k], pairs, repTimes, matches, orientationSeries, intrinsics,
                       opts, w, h, fineBest - fineStepNs);
        const double cPlus =
            windowCost(win[k], pairs, repTimes, matches, orientationSeries, intrinsics,
                       opts, w, h, fineBest + fineStepNs);
        const double curvaturePerMs2 =
            (cMinus + cPlus - 2.0 * fineCost) /
            (opts.fineStepMs * opts.fineStepMs);

        // Error bound: the offset half-width where the parabolic fit
        // exceeds the minimum by one Huber-delta^2 — delta*sqrt(2/kappa),
        // a sensitivity statement on the MEASURED curve. Floored at the
        // fine step, capped at the anchor bound.
        double boundMs = 0.0;
        if (curvaturePerMs2 > 1e-12)
            boundMs = opts.huberDeltaPx * std::sqrt(2.0 / curvaturePerMs2);
        boundMs = std::max(boundMs, opts.fineStepMs);
        if (anchor.errorBoundMs > 0.0)
            boundMs = std::min(boundMs, anchor.errorBoundMs);

        const bool sharpWell = curvaturePerMs2 > 1e-9 && peakiness >= opts.minPeakiness;
        const bool improvesAnchor =
            anchor.errorBoundMs <= 0.0 || boundMs < anchor.errorBoundMs;
        if (!sharpWell || !improvesAnchor) {
            wr.note = "flat-or-not-improving";
            continue;
        }

        wr.refined = true;
        wr.offsetNs = fineBest;
        wr.errorBoundMs = boundMs;
        {
            char buf[128];
            std::snprintf(buf, sizeof(buf), "w%d:off=%.1fms used=%zu pk=%.2f b=%.1f",
                          k, static_cast<double>(fineBest) / 1e6, used, peakiness,
                          boundMs);
            wr.note = buf;
        }
    }

    int refinedWindows = 0;
    for (const OpticalSyncWindowResult& wr : out.windows)
        if (wr.refined)
            ++refinedWindows;
    if (refinedWindows == 0) {
        // Carry the per-window notes into the rejection provenance: the
        // caller (and the test) must be able to see WHICH gate refused and
        // per-window evidence, not just the top-level verdict.
        std::string reason = "refine-rejected reason=no-window-refined";
        for (size_t wi = 0; wi < out.windows.size(); ++wi) {
            reason += " [w";
            reason += std::to_string(wi);
            reason += ": ";
            reason += out.windows[wi].note.empty() ? "empty" : out.windows[wi].note;
            reason += "]";
        }
        out.provenance = reason;
        return out;
    }

    // ---- cross-window agreement: the largest group of refined windows
    // agreeing within the tolerance; the reported offset is the group's
    // MEDIAN. Below 2 agreeing windows the best-scoring refined window
    // stands alone. Disagreement is reported, never averaged away. ----
    std::vector<int> refinedIdx;
    for (int k = 0; k < 3; ++k)
        if (out.windows[static_cast<size_t>(k)].refined)
            refinedIdx.push_back(k);
    std::vector<int> group; // the chosen group's window indices
    if (refinedIdx.size() >= 2) {
        size_t bestGroupSize = 0;
        double bestGroupScore = -1.0;
        std::vector<int> bestGroup;
        for (int c : refinedIdx) {
            const double cOffMs =
                static_cast<double>(out.windows[static_cast<size_t>(c)].offsetNs) / 1e6;
            std::vector<int> grp;
            double score = 0.0;
            for (int o : refinedIdx) {
                const double oOffMs =
                    static_cast<double>(out.windows[static_cast<size_t>(o)].offsetNs) /
                    1e6;
                if (std::fabs(oOffMs - cOffMs) <= opts.agreementToleranceMs) {
                    grp.push_back(o);
                    score += out.windows[static_cast<size_t>(o)].score;
                }
            }
            if (grp.size() > bestGroupSize ||
                (grp.size() == bestGroupSize && score > bestGroupScore)) {
                bestGroupSize = grp.size();
                bestGroupScore = score;
                bestGroup = grp;
            }
        }
        group = bestGroup;
    } else {
        group = refinedIdx; // a single refined window stands alone
    }

    // The group's median offset (a single member: its own offset).
    std::vector<double> groupOffMs;
    for (int g : group)
        groupOffMs.push_back(
            static_cast<double>(out.windows[static_cast<size_t>(g)].offsetNs) / 1e6);
    std::sort(groupOffMs.begin(), groupOffMs.end());
    const double medianOffMs =
        groupOffMs[groupOffMs.size() / 2]; // sizes 1..3: the middle value
    out.refined = true;
    out.refinedOffsetNs = static_cast<int64_t>(std::llround(medianOffMs * 1e6));
    // The chosen group's bound (the tightest refined bound among the
    // group's windows — the group agrees within tolerance, so the
    // tightest measured sensitivity is the honest report).
    double bound = out.windows[static_cast<size_t>(group[0])].errorBoundMs;
    for (int g : group)
        bound = std::min(bound, out.windows[static_cast<size_t>(g)].errorBoundMs);
    out.errorBoundMs = bound;

    // Provenance: "refined:optical windows=k accepted=j offsetMs=..."
    // plus the repTime source (the audit's visibility requirement) and
    // the per-window fragments.
    {
        std::string p = "refined:optical";
        size_t usedTotal = 0;
        for (const OpticalSyncWindowResult& wr : out.windows)
            usedTotal += wr.pairsUsed;
        char buf[96];
        std::snprintf(buf, sizeof(buf), " windows=%zu accepted=%zu offsetMs=%.1f",
                      group.size(), usedTotal,
                      static_cast<double>(out.refinedOffsetNs) / 1e6);
        p += buf;
        std::snprintf(buf, sizeof(buf), " boundMs=%.1f", out.errorBoundMs);
        p += buf;
        p += " repTime=";
        p += opts.repTimeSource ? opts.repTimeSource : "unspecified";
        p += " [";
        for (size_t i = 0; i < out.windows.size(); ++i) {
            if (i)
                p += "; ";
            p += out.windows[i].note;
        }
        p += "]";
        out.provenance = p;
    }
    return out;
}

// ---------------------------------------------------------------------------
// applyOpticalRefinement — the additive fold into SyncEstimate.
// ---------------------------------------------------------------------------
SyncEstimate applyOpticalRefinement(const SyncEstimate& anchor,
                                    const OpticalSyncResult& result) {
    SyncEstimate est = anchor;
    if (!result.refined) {
        // The header contract: the anchor returned UNCHANGED except the
        // rejection note appended to source — the caller keeps the
        // anchor, nothing fabricated, and the UI can show WHY.
        if (!result.provenance.empty()) {
            if (!est.source.empty())
                est.source += " | ";
            est.source += result.provenance;
        }
        return est;
    }
    est.confidence = SyncConfidence::Refined;
    est.refinedOffsetNs = result.refinedOffsetNs;
    est.errorBoundMs = result.errorBoundMs;
    est.source = result.provenance;
    return est;
}

} // namespace rawedit
