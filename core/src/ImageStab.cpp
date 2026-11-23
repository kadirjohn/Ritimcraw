// rawedit::ImageStab — see ImageStab.h and docs/IMAGE_STAB_PLAN.md.
#include <rawedit/ImageStab.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace rawedit {

namespace {

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

struct Pt {
    double x = 0.0, y = 0.0;
};

// ---- sampling -------------------------------------------------------------

// Bilinear sample with edge replication (coarse pyramid levels near the
// border still give a gradient from their interior half).
inline float sampleClamped(const std::vector<float>& a, int w, int h, double x,
                           double y) {
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x > w - 1) x = w - 1;
    if (y > h - 1) y = h - 1;
    const int x0 = std::min(static_cast<int>(x), w - 2 < 0 ? 0 : w - 2);
    const int y0 = std::min(static_cast<int>(y), h - 2 < 0 ? 0 : h - 2);
    const int x1 = std::min(x0 + 1, w - 1);
    const int y1 = std::min(y0 + 1, h - 1);
    const float fx = static_cast<float>(x - x0);
    const float fy = static_cast<float>(y - y0);
    const float* r0 = &a[static_cast<size_t>(y0) * w];
    const float* r1 = &a[static_cast<size_t>(y1) * w];
    const float top = r0[x0] + (r0[x1] - r0[x0]) * fx;
    const float bot = r1[x0] + (r1[x1] - r1[x0]) * fx;
    return top + (bot - top) * fy;
}

// ---- Lucas-Kanade ---------------------------------------------------------

// One level of LK. A = template level (gradients used), B = target level.
// (px, py) is the template point at THIS level; (gx, gy) the running
// displacement guess (in/out). Returns false when the patch is textureless
// (min eigenvalue below minEig) or the solve degenerates.
bool lkLevel(const ImagePyramid::Level& A, const ImagePyramid::Level& B,
             double px, double py, double& gx, double& gy, int win,
             int maxIter, double eps, double minEig, bool strictBounds) {
    const int n = win * win;
    const double half = (win - 1) * 0.5;
    float tI[32 * 32], tX[32 * 32], tY[32 * 32];
    if (win > 32) return false;
    if (strictBounds) {
        if (px - half < 0 || py - half < 0 || px + half > A.w - 1 ||
            py + half > A.h - 1)
            return false;
    }
    double gxx = 0, gxy = 0, gyy = 0;
    int k = 0;
    for (int j = 0; j < win; ++j) {
        for (int i = 0; i < win; ++i, ++k) {
            const double sx = px - half + i, sy = py - half + j;
            const float I = sampleClamped(A.img, A.w, A.h, sx, sy);
            const float X = sampleClamped(A.gx, A.w, A.h, sx, sy);
            const float Y = sampleClamped(A.gy, A.w, A.h, sx, sy);
            tI[k] = I;
            tX[k] = X;
            tY[k] = Y;
            gxx += static_cast<double>(X) * X;
            gxy += static_cast<double>(X) * Y;
            gyy += static_cast<double>(Y) * Y;
        }
    }
    gxx /= n;
    gxy /= n;
    gyy /= n;
    const double tr = gxx + gyy;
    const double det = gxx * gyy - gxy * gxy;
    const double disc = std::sqrt(std::max(0.0, tr * tr * 0.25 - det));
    if (tr * 0.5 - disc < minEig || det <= 1e-12) return false;
    for (int it = 0; it < maxIter; ++it) {
        double bx = 0, by = 0;
        k = 0;
        for (int j = 0; j < win; ++j) {
            for (int i = 0; i < win; ++i, ++k) {
                const float J = sampleClamped(B.img, B.w, B.h,
                                              px + gx - half + i,
                                              py + gy - half + j);
                const double e = static_cast<double>(J) - tI[k];
                bx += e * tX[k];
                by += e * tY[k];
            }
        }
        // d = -G^-1 b  (n cancels against the 1/n in G: use summed G).
        const double Gxx = gxx * n, Gxy = gxy * n, Gyy = gyy * n;
        const double d = Gxx * Gyy - Gxy * Gxy;
        const double dx = -(Gyy * bx - Gxy * by) / d;
        const double dy = -(-Gxy * bx + Gxx * by) / d;
        gx += dx;
        gy += dy;
        if (std::fabs(dx) < eps && std::fabs(dy) < eps) break;
        // Runaway guard: more than a window away from the template point.
        if (std::fabs(gx) > 4.0 * win || std::fabs(gy) > 4.0 * win)
            return false;
    }
    return true;
}

// Pyramidal LK, point (x, y) at level 0 of A -> displacement (gx, gy) at
// level 0 of B. (gx, gy) carries the initial guess in and the result out.
bool lkPyramid(const ImagePyramid& A, const ImagePyramid& B, double x, double y,
               double& gx, double& gy, const ImageStabOptions& o) {
    const int L = static_cast<int>(A.levels.size());
    const double minEig =
        (o.floorGradRel * A.scale) * (o.floorGradRel * A.scale);
    gx /= static_cast<double>(1 << (L - 1));
    gy /= static_cast<double>(1 << (L - 1));
    for (int l = L - 1; l >= 0; --l) {
        const double s = 1.0 / static_cast<double>(1 << l);
        // Level 0 must keep the whole window inside the frame; coarse
        // levels replicate the border so border corners still get a
        // coarse estimate.
        if (!lkLevel(A.levels[l], B.levels[l], x * s, y * s, gx, gy, o.window,
                     o.maxIter, o.iterEps, l == 0 ? minEig : minEig * 0.25,
                     l == 0))
            return false;
        if (l > 0) {
            gx *= 2.0;
            gy *= 2.0;
        }
    }
    return true;
}

// ---- corners --------------------------------------------------------------

// Shi-Tomasi: min eigenvalue of the 5x5-box-averaged structure tensor at
// level 0, then a grid pick (<= maxPerCell per cell, >= minDist apart).
std::vector<Pt> pickCorners(const ImagePyramid& P, const ImageStabOptions& o) {
    const auto& L0 = P.levels[0];
    const int w = L0.w, h = L0.h;
    const int R = 2;  // 5x5
    std::vector<float> ixx(static_cast<size_t>(w) * h), ixy(ixx.size()),
        iyy(ixx.size());
    for (size_t i = 0; i < ixx.size(); ++i) {
        const float a = L0.gx[i], b = L0.gy[i];
        ixx[i] = a * a;
        ixy[i] = a * b;
        iyy[i] = b * b;
    }
    // Separable box sums (horizontal then vertical), zero outside.
    auto box = [&](std::vector<float>& v) {
        std::vector<float> tmp(v.size());
        for (int y = 0; y < h; ++y) {
            const float* r = &v[static_cast<size_t>(y) * w];
            float* t = &tmp[static_cast<size_t>(y) * w];
            for (int x = 0; x < w; ++x) {
                float s = 0;
                for (int k = -R; k <= R; ++k) {
                    const int xx = x + k;
                    if (xx >= 0 && xx < w) s += r[xx];
                }
                t[x] = s;
            }
        }
        for (int y = 0; y < h; ++y) {
            float* r = &v[static_cast<size_t>(y) * w];
            for (int x = 0; x < w; ++x) {
                float s = 0;
                for (int k = -R; k <= R; ++k) {
                    const int yy = y + k;
                    if (yy >= 0 && yy < h) s += tmp[static_cast<size_t>(yy) * w + x];
                }
                r[x] = s;
            }
        }
    };
    box(ixx);
    box(ixy);
    box(iyy);
    const float inv = 1.0f / 25.0f;
    std::vector<float> resp(ixx.size());
    for (size_t i = 0; i < resp.size(); ++i) {
        const float a = ixx[i] * inv, b = ixy[i] * inv, c = iyy[i] * inv;
        const float t = (a + c) * 0.5f;
        const float d = std::sqrt(std::max(0.0f, ((a - c) * 0.5f) * ((a - c) * 0.5f) + b * b));
        resp[i] = t - d;
    }
    const int margin = o.window / 2 + 2;
    const int cw = std::max(1, (w - 2 * margin) / o.gridCols);
    const int ch = std::max(1, (h - 2 * margin) / o.gridRows);
    struct Cand { float r; int x, y; };
    std::vector<std::vector<Cand>> cells(static_cast<size_t>(o.gridCols) * o.gridRows);
    float strongest = 0;
    for (int gy = 0; gy < o.gridRows; ++gy) {
        for (int gx = 0; gx < o.gridCols; ++gx) {
            std::vector<Cand> c;
            const int x0 = margin + gx * cw, y0 = margin + gy * ch;
            const int x1 = std::min(x0 + cw, w - margin);
            const int y1 = std::min(y0 + ch, h - margin);
            for (int y = y0; y < y1; ++y)
                for (int x = x0; x < x1; ++x) {
                    const float r = resp[static_cast<size_t>(y) * w + x];
                    // Local-maximum test (3x3) thins the candidates.
                    bool top = true;
                    for (int dy = -1; dy <= 1 && top; ++dy)
                        for (int dx = -1; dx <= 1; ++dx)
                            if ((dx || dy) &&
                                resp[static_cast<size_t>(y + dy) * w + x + dx] > r) {
                                top = false;
                                break;
                            }
                    if (top && r > 0) c.push_back({r, x, y});
                }
            std::stable_sort(c.begin(), c.end(), [](const Cand& a, const Cand& b) {
                if (a.r != b.r) return a.r > b.r;
                if (a.y != b.y) return a.y < b.y;
                return a.x < b.x;
            });
            std::vector<Cand> pick;
            for (const Cand& k : c) {
                bool ok = true;
                for (const Cand& p : pick)
                    if (std::abs(p.x - k.x) < o.cornerMinDist &&
                        std::abs(p.y - k.y) < o.cornerMinDist) {
                        ok = false;
                        break;
                    }
                if (ok) pick.push_back(k);
                if (static_cast<int>(pick.size()) >= o.maxPerCell) break;
            }
            if (!pick.empty()) strongest = std::max(strongest, pick[0].r);
            cells[static_cast<size_t>(gy) * o.gridCols + gx] = std::move(pick);
        }
    }
    const double floorGrad = o.floorGradRel * P.scale;
    const float thr = static_cast<float>(
        std::max(floorGrad * floorGrad, o.qualityRel * static_cast<double>(strongest)));
    std::vector<Pt> out;
    for (const auto& cell : cells)
        for (const Cand& k : cell)
            if (k.r >= thr) out.push_back({static_cast<double>(k.x), static_cast<double>(k.y)});
    if (static_cast<int>(out.size()) > o.maxPoints) {
        // Deterministic thinning: keep an evenly spread subset.
        std::vector<Pt> thin;
        const double step = static_cast<double>(out.size()) / o.maxPoints;
        for (int i = 0; i < o.maxPoints; ++i) thin.push_back(out[static_cast<size_t>(i * step)]);
        out.swap(thin);
    }
    return out;
}

// ---- similarity fit -------------------------------------------------------

struct Sim {
    // z' = a z + t on complex numbers, centered pixel coordinates.
    double ar = 1, ai = 0, tr = 0, ti = 0;
    Pt apply(const Pt& p) const {
        return {ar * p.x - ai * p.y + tr, ai * p.x + ar * p.y + ti};
    }
};

// Exact similarity through two correspondences.
bool simFrom2(const Pt& a1, const Pt& b1, const Pt& a2, const Pt& b2, Sim& s) {
    const double dx = a2.x - a1.x, dy = a2.y - a1.y;
    const double n2 = dx * dx + dy * dy;
    if (n2 < 1e-9) return false;
    const double ex = b2.x - b1.x, ey = b2.y - b1.y;
    // a = e / d (complex)
    s.ar = (ex * dx + ey * dy) / n2;
    s.ai = (ey * dx - ex * dy) / n2;
    s.tr = b1.x - (s.ar * a1.x - s.ai * a1.y);
    s.ti = b1.y - (s.ai * a1.x + s.ar * a1.y);
    return true;
}

// Least-squares similarity over the selected indices.
bool simLsq(const std::vector<Pt>& a, const std::vector<Pt>& b,
            const std::vector<int>& idx, Sim& s) {
    if (idx.size() < 2) return false;
    double cax = 0, cay = 0, cbx = 0, cby = 0;
    for (int i : idx) {
        cax += a[i].x; cay += a[i].y; cbx += b[i].x; cby += b[i].y;
    }
    const double inv = 1.0 / static_cast<double>(idx.size());
    cax *= inv; cay *= inv; cbx *= inv; cby *= inv;
    double num_r = 0, num_i = 0, den = 0;
    for (int i : idx) {
        const double zx = a[i].x - cax, zy = a[i].y - cay;
        const double wx = b[i].x - cbx, wy = b[i].y - cby;
        // conj(z) * w
        num_r += zx * wx + zy * wy;
        num_i += zx * wy - zy * wx;
        den += zx * zx + zy * zy;
    }
    if (den < 1e-9) return false;
    s.ar = num_r / den;
    s.ai = num_i / den;
    s.tr = cbx - (s.ar * cax - s.ai * cay);
    s.ti = cby - (s.ai * cax + s.ar * cay);
    return true;
}

inline double resid2(const Sim& s, const Pt& a, const Pt& b) {
    const Pt p = s.apply(a);
    const double dx = p.x - b.x, dy = p.y - b.y;
    return dx * dx + dy * dy;
}

// Fixed-seed xorshift64*; re-seeded per call -> deterministic.
struct Rng {
    uint64_t s = 0x9E3779B97F4A7C15ull;
    uint32_t next() {
        s ^= s >> 12;
        s ^= s << 25;
        s ^= s >> 27;
        return static_cast<uint32_t>((s * 0x2545F4914F6CDD1Dull) >> 32);
    }
};

}  // namespace

// ---- public ---------------------------------------------------------------

double lumaScale(const LumaFrame& f) {
    if (!f.data || f.w <= 0 || f.h <= 0) return 1.0;
    uint32_t hist[256] = {0};
    // Subsample every 2nd pixel: enough for a percentile.
    size_t n = 0;
    for (int y = 0; y < f.h; y += 2) {
        const uint8_t* r = f.data + static_cast<size_t>(y) * f.stride;
        for (int x = 0; x < f.w; x += 2, ++n) ++hist[r[x]];
    }
    const size_t lo = n / 100, hi = n - n / 100;
    size_t acc = 0;
    int p1 = 0, p99 = 255;
    bool gotLo = false;
    for (int v = 0; v < 256; ++v) {
        acc += hist[v];
        if (!gotLo && acc > lo) { p1 = v; gotLo = true; }
        if (acc >= hi) { p99 = v; break; }
    }
    return std::max(1.0, static_cast<double>(p99 - p1));
}

ImagePyramid buildPyramid(const LumaFrame& f, int levels) {
    ImagePyramid P;
    P.scale = lumaScale(f);
    levels = std::max(1, levels);
    P.levels.resize(static_cast<size_t>(levels));
    {
        auto& L = P.levels[0];
        L.w = f.w;
        L.h = f.h;
        L.img.resize(static_cast<size_t>(f.w) * f.h);
        for (int y = 0; y < f.h; ++y) {
            const uint8_t* r = f.data + static_cast<size_t>(y) * f.stride;
            float* d = &L.img[static_cast<size_t>(y) * f.w];
            for (int x = 0; x < f.w; ++x) d[x] = r[x];
        }
    }
    for (int l = 1; l < levels; ++l) {
        const auto& s = P.levels[l - 1];
        auto& d = P.levels[l];
        d.w = std::max(1, s.w / 2);
        d.h = std::max(1, s.h / 2);
        d.img.resize(static_cast<size_t>(d.w) * d.h);
        for (int y = 0; y < d.h; ++y)
            for (int x = 0; x < d.w; ++x) {
                const int sx = std::min(2 * x, s.w - 1), sy = std::min(2 * y, s.h - 1);
                const int sx1 = std::min(sx + 1, s.w - 1), sy1 = std::min(sy + 1, s.h - 1);
                d.img[static_cast<size_t>(y) * d.w + x] =
                    0.25f * (s.img[static_cast<size_t>(sy) * s.w + sx] +
                             s.img[static_cast<size_t>(sy) * s.w + sx1] +
                             s.img[static_cast<size_t>(sy1) * s.w + sx] +
                             s.img[static_cast<size_t>(sy1) * s.w + sx1]);
            }
    }
    for (auto& L : P.levels) {
        L.gx.assign(L.img.size(), 0.0f);
        L.gy.assign(L.img.size(), 0.0f);
        for (int y = 0; y < L.h; ++y) {
            const int ym = std::max(y - 1, 0), yp = std::min(y + 1, L.h - 1);
            for (int x = 0; x < L.w; ++x) {
                const int xm = std::max(x - 1, 0), xp = std::min(x + 1, L.w - 1);
                const size_t i = static_cast<size_t>(y) * L.w + x;
                L.gx[i] = (L.img[static_cast<size_t>(y) * L.w + xp] -
                           L.img[static_cast<size_t>(y) * L.w + xm]) * 0.5f;
                L.gy[i] = (L.img[static_cast<size_t>(yp) * L.w + x] -
                           L.img[static_cast<size_t>(ym) * L.w + x]) * 0.5f;
            }
        }
    }
    return P;
}

PairMotion estimatePair(const LumaFrame& prev, const LumaFrame& cur,
                        const ImageStabOptions& opts, const PairMotion* prior,
                        StageTimes* times) {
    PairMotion lost;
    if (!prev.data || !cur.data || prev.w != cur.w || prev.h != cur.h ||
        prev.w < 64 || prev.h < 64)
        return lost;
    const auto t0 = Clock::now();
    const ImagePyramid a = buildPyramid(prev, opts.levels);
    const ImagePyramid b = buildPyramid(cur, opts.levels);
    const double ms = msSince(t0);
    StageTimes st;
    PairMotion m = estimatePair(a, b, opts, prior, &st);
    st.pyramidMs += ms;
    if (times) times->add(st);
    return m;
}

PairMotion estimatePair(const ImagePyramid& A, const ImagePyramid& B,
                        const ImageStabOptions& o, const PairMotion* prior,
                        StageTimes* times) {
    PairMotion out;
    if (A.levels.empty() || B.levels.size() != A.levels.size() ||
        A.levels[0].w != B.levels[0].w || A.levels[0].h != B.levels[0].h)
        return out;
    const int w = A.levels[0].w, h = A.levels[0].h;
    const double cx = w * 0.5, cy = h * 0.5;

    auto t = Clock::now();
    const std::vector<Pt> corners = pickCorners(A, o);
    if (times) times->cornersMs += msSince(t);

    // Prior in working-res pixels: p_cur = a p_prev + t about the center.
    Sim prSim;
    if (prior && prior->status != PairStatus::Lost) {
        const double s = std::exp(prior->logS);
        prSim.ar = s * std::cos(prior->theta);
        prSim.ai = s * std::sin(prior->theta);
        prSim.tr = prior->tx * h;
        prSim.ti = prior->ty * h;
    }

    t = Clock::now();
    std::vector<Pt> pa, pb;  // centered pixel coordinates
    for (const Pt& c : corners) {
        const Pt centered{c.x - cx, c.y - cy};
        const Pt guess = prSim.apply(centered);
        double gx = guess.x - centered.x, gy = guess.y - centered.y;
        if (!lkPyramid(A, B, c.x, c.y, gx, gy, o)) continue;
        // Backward check: from the tracked position back into A.
        const double qx = c.x + gx, qy = c.y + gy;
        if (qx < 0 || qy < 0 || qx > w - 1 || qy > h - 1) continue;
        double bx = -gx, by = -gy;
        if (!lkPyramid(B, A, qx, qy, bx, by, o)) continue;
        const double fb = std::hypot(gx + bx, gy + by);
        if (fb > o.fbMax) continue;
        pa.push_back(centered);
        pb.push_back({centered.x + gx, centered.y + gy});
    }
    if (times) times->lkMs += msSince(t);

    t = Clock::now();
    const int n = static_cast<int>(pa.size());
    out.nTracked = static_cast<uint16_t>(std::min(n, 65535));
    if (n < 2) {
        if (times) times->fitMs += msSince(t);
        return out;
    }

    // RANSAC, 2-point similarity, MSAC cost.
    Rng rng;
    const double thr2 = o.inlierThresh * o.inlierThresh;
    const double sepMin = o.minSampleSepRel * h;
    Sim best;
    double bestCost = 1e300;
    bool have = false;
    for (int it = 0; it < o.ransacIters; ++it) {
        int i = static_cast<int>(rng.next() % static_cast<uint32_t>(n));
        int j = static_cast<int>(rng.next() % static_cast<uint32_t>(n));
        if (i == j) continue;
        if (std::hypot(pa[i].x - pa[j].x, pa[i].y - pa[j].y) < sepMin) continue;
        Sim s;
        if (!simFrom2(pa[i], pb[i], pa[j], pb[j], s)) continue;
        const double sc = std::hypot(s.ar, s.ai);
        if (sc < 0.7 || sc > 1.4) continue;
        double cost = 0;
        for (int k = 0; k < n; ++k) cost += std::min(resid2(s, pa[k], pb[k]), thr2);
        if (cost < bestCost) {
            bestCost = cost;
            best = s;
            have = true;
        }
    }
    if (!have) {
        // Every sample was rejected (tiny spread): degrade to all-point LSQ.
        std::vector<int> all(static_cast<size_t>(n));
        for (int k = 0; k < n; ++k) all[k] = k;
        have = simLsq(pa, pb, all, best);
        if (!have) {
            if (times) times->fitMs += msSince(t);
            return out;
        }
    }
    std::vector<int> inl;
    for (int pass = 0; pass < 3; ++pass) {
        inl.clear();
        for (int k = 0; k < n; ++k)
            if (resid2(best, pa[k], pb[k]) < thr2) inl.push_back(k);
        Sim r;
        if (!simLsq(pa, pb, inl, r)) break;
        best = r;
    }
    inl.clear();
    double sse = 0;
    for (int k = 0; k < n; ++k) {
        const double r2 = resid2(best, pa[k], pb[k]);
        if (r2 < thr2) {
            inl.push_back(k);
            sse += r2;
        }
    }
    out.nInliers = static_cast<uint16_t>(inl.size());
    out.inlierRatio = static_cast<float>(inl.size()) / static_cast<float>(n);
    out.residualRms = inl.empty() ? 0.0f
                                  : static_cast<float>(std::sqrt(sse / static_cast<double>(inl.size())));
    // 3x3 coverage of the inliers' source positions.
    bool occ[9] = {false};
    for (int k : inl) {
        const int gx = std::min(2, std::max(0, static_cast<int>((pa[k].x + cx) * 3.0 / w)));
        const int gy = std::min(2, std::max(0, static_cast<int>((pa[k].y + cy) * 3.0 / h)));
        occ[gy * 3 + gx] = true;
    }
    int cnt = 0;
    for (bool b : occ) cnt += b ? 1 : 0;
    out.coverage = static_cast<float>(cnt) / 9.0f;

    const bool ok = out.nInliers >= o.okMinInliers && out.inlierRatio >= o.okMinRatio &&
                    out.coverage >= o.okMinCoverage;
    const bool weak = out.nInliers >= o.weakMinInliers && out.inlierRatio >= o.weakMinRatio;
    out.status = ok ? PairStatus::Ok : (weak ? PairStatus::Weak : PairStatus::Lost);
    if (out.status != PairStatus::Lost) {
        out.tx = best.tr / h;
        out.ty = best.ti / h;
        out.logS = std::log(std::hypot(best.ar, best.ai));
        out.theta = std::atan2(best.ai, best.ar);
    }
    if (times) times->fitMs += msSince(t);
    return out;
}

}  // namespace rawedit
