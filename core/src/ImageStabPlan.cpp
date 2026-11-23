// rawedit::ImageStab — S3 plan (see ImageStab.h, docs/IMAGE_STAB_PLAN.md
// section 3.3/3.4). Built with -ffp-contract=off (Yuv.cpp precedent) so the
// double math rounds identically on every compiler/target; a plan is a pure
// function of (records, params, range, aspect).
#include <rawedit/ImageStab.h>

#include <algorithm>
#include <cmath>
#include <limits>

namespace rawedit {

// ---- Sim2 -----------------------------------------------------------------

Sim2 simCompose(const Sim2& o, const Sim2& i) {
    Sim2 r;
    r.s = o.s * i.s;
    r.theta = o.theta + i.theta;
    const double c = std::cos(o.theta), sn = std::sin(o.theta);
    r.tx = o.s * (c * i.tx - sn * i.ty) + o.tx;
    r.ty = o.s * (sn * i.tx + c * i.ty) + o.ty;
    return r;
}

Sim2 simInverse(const Sim2& a) {
    Sim2 r;
    r.s = 1.0 / a.s;
    r.theta = -a.theta;
    const double c = std::cos(-a.theta), sn = std::sin(-a.theta);
    r.tx = -r.s * (c * a.tx - sn * a.ty);
    r.ty = -r.s * (sn * a.tx + c * a.ty);
    return r;
}

Sim2 simOfMotion(const PairMotion& m) {
    Sim2 r;
    r.s = std::exp(m.logS);
    r.theta = m.theta;
    r.tx = m.tx;
    r.ty = m.ty;
    return r;
}

namespace {

constexpr double kInf = std::numeric_limits<double>::infinity();

Sim2 simOfChannels(double tx, double ty, double logS, double th) {
    Sim2 r;
    r.s = std::exp(logS);
    r.theta = th;
    r.tx = tx;
    r.ty = ty;
    return r;
}

// ---- 3x3 helpers (single definition for the plan) ---------------------------

bool matInv3(const double m[9], double out[9]) {
    const double c00 = m[4] * m[8] - m[5] * m[7];
    const double c01 = m[5] * m[6] - m[3] * m[8];
    const double c02 = m[3] * m[7] - m[4] * m[6];
    const double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
    if (!std::isfinite(det) || std::fabs(det) < 1e-12) return false;
    const double inv = 1.0 / det;
    out[0] = c00 * inv;
    out[1] = (m[2] * m[7] - m[1] * m[8]) * inv;
    out[2] = (m[1] * m[5] - m[2] * m[4]) * inv;
    out[3] = c01 * inv;
    out[4] = (m[0] * m[8] - m[2] * m[6]) * inv;
    out[5] = (m[2] * m[3] - m[0] * m[5]) * inv;
    out[6] = c02 * inv;
    out[7] = (m[1] * m[6] - m[0] * m[7]) * inv;
    out[8] = (m[0] * m[4] - m[1] * m[3]) * inv;
    return true;
}

// ---- 1D filters ---------------------------------------------------------------

double alphaFor(double dt, double tau) {
    if (!(tau > 0.0)) return 1.0;
    return 1.0 - std::exp(-dt / tau);
}

// Zero-lag fixed-tau smoothing: forward then backward one-pole.
void smoothPair(std::vector<double>& v, const std::vector<double>& dts, double tau) {
    const size_t n = v.size();
    for (size_t i = 1; i < n; ++i) v[i] = v[i - 1] + alphaFor(dts[i], tau) * (v[i] - v[i - 1]);
    for (size_t i = n - 1; i-- > 0;)
        v[i] = v[i + 1] + alphaFor(dts[i + 1], tau) * (v[i] - v[i + 1]);
}

// Velocity-adaptive zero-lag pair with per-frame alphas.
std::vector<double> adaptivePair(const std::vector<double>& p, const std::vector<double>& alpha) {
    const size_t n = p.size();
    std::vector<double> f(p);
    for (size_t i = 1; i < n; ++i) f[i] = f[i - 1] + alpha[i] * (p[i] - f[i - 1]);
    std::vector<double> b(f);
    for (size_t i = n - 1; i-- > 0;) b[i] = b[i + 1] + alpha[i + 1] * (f[i] - b[i + 1]);
    return b;
}

std::vector<double> rollingExtreme(const std::vector<double>& v, int half, bool wantMax) {
    const int n = static_cast<int>(v.size());
    std::vector<double> o(v.size());
    for (int i = 0; i < n; ++i) {
        double e = v[i];
        const int lo = std::max(0, i - half), hi = std::min(n - 1, i + half);
        for (int j = lo; j <= hi; ++j) e = wantMax ? std::max(e, v[j]) : std::min(e, v[j]);
        o[i] = e;
    }
    return o;
}

// Normalized Gaussian over +-half samples (sigma = half/2); the weights are
// positive and sum to 1 at the clip ends too, so a convex combination of
// values >= x stays >= x (the zoom proof) and one of values <= x stays <= x.
std::vector<double> gauss(const std::vector<double>& v, int half) {
    const int n = static_cast<int>(v.size());
    if (half <= 0) return v;
    const double sigma = std::max(0.5, half * 0.5);
    std::vector<double> w(static_cast<size_t>(2 * half + 1));
    for (int k = -half; k <= half; ++k) w[static_cast<size_t>(k + half)] = std::exp(-0.5 * k * k / (sigma * sigma));
    std::vector<double> o(v.size());
    for (int i = 0; i < n; ++i) {
        double acc = 0, ws = 0;
        const int lo = std::max(0, i - half), hi = std::min(n - 1, i + half);
        for (int j = lo; j <= hi; ++j) {
            const double ww = w[static_cast<size_t>(j - i + half)];
            acc += ww * v[j];
            ws += ww;
        }
        o[i] = acc / ws;
    }
    return o;
}

double medianOf(std::vector<double> v) {
    if (v.empty()) return 0.0;
    std::sort(v.begin(), v.end());
    const size_t m = v.size() / 2;
    return v.size() % 2 ? v[m] : 0.5 * (v[m - 1] + v[m]);
}

// Upper envelope of lz whose slope is bounded: Z_i >= lz_i, |dZ/dt| <= rate.
// (rate <= 0 leaves it untouched.)
void rateLimitUp(std::vector<double>& g, const std::vector<double>& dts, double rate) {
    if (!(rate > 0.0)) return;
    const size_t n = g.size();
    for (size_t i = 1; i < n; ++i) g[i] = std::max(g[i], g[i - 1] - rate * dts[i]);
    for (size_t i = n - 1; i-- > 0;) g[i] = std::max(g[i], g[i + 1] - rate * dts[i + 1]);
}

}  // namespace

// ---- coverage ------------------------------------------------------------------

double requiredZoom(const Sim2& corr, double aspect, double margin) {
    // p_src = Cinv(q / Z) = (A q) / Z + b, A = linear part of Cinv.
    const Sim2 inv = simInverse(corr);
    const double c = std::cos(inv.theta), sn = std::sin(inv.theta);
    const double a00 = inv.s * c, a01 = -inv.s * sn, a10 = inv.s * sn, a11 = inv.s * c;
    // The margin protects the half-pixel conventions of a NON-trivial warp
    // (rotation about c = (w/2, h/2), not the pixel-box centre). The identity
    // needs none and must cost exactly zoom 1, so the margin ramps in with
    // the correction's size (in heights) over the first 0.002.
    const double dev = std::max({std::fabs(corr.tx), std::fabs(corr.ty),
                                 0.5 * std::fabs(corr.theta), 0.5 * std::fabs(std::log(corr.s))});
    margin *= std::min(1.0, dev / 0.002);
    const double hx = aspect * 0.5 - margin, hy = 0.5 - margin;
    // Allowed range of u/Z per axis: [lo - b, hi - b].
    const double Lx = -hx - inv.tx, Ux = hx - inv.tx;
    const double Ly = -hy - inv.ty, Uy = hy - inv.ty;
    if (Lx > 0 || Ux < 0 || Ly > 0 || Uy < 0) return kInf;
    double z = 1.0;
    const double qx[2] = {-aspect * 0.5, aspect * 0.5};
    const double qy[2] = {-0.5, 0.5};
    for (double x : qx)
        for (double y : qy) {
            const double ux = a00 * x + a01 * y;
            const double uy = a10 * x + a11 * y;
            if (ux > 0) {
                if (Ux <= 0) return kInf;
                z = std::max(z, ux / Ux);
            } else if (ux < 0) {
                if (Lx >= 0) return kInf;
                z = std::max(z, ux / Lx);
            }
            if (uy > 0) {
                if (Uy <= 0) return kInf;
                z = std::max(z, uy / Uy);
            } else if (uy < 0) {
                if (Ly >= 0) return kInf;
                z = std::max(z, uy / Ly);
            }
        }
    return z;
}

void simToHomographyPx(const Sim2& corr, int w, int h, double H[9]) {
    const double cx = w * 0.5, cy = h * 0.5;
    const double a = corr.s * std::cos(corr.theta), b = corr.s * std::sin(corr.theta);
    // A = [[a, -b], [b, a]]; translation = c - A c + t*h.
    H[0] = a;  H[1] = -b; H[2] = cx - (a * cx - b * cy) + corr.tx * h;
    H[3] = b;  H[4] = a;  H[5] = cy - (b * cx + a * cy) + corr.ty * h;
    H[6] = 0;  H[7] = 0;  H[8] = 1;
}

bool composeWarpM(const double H[9], double zoom, int w, int h, double M[9]) {
    double hInv[9];
    if (!matInv3(H, hInv)) return false;
    if (!(zoom >= 1.0) || !std::isfinite(zoom)) zoom = 1.0;
    const double cx = w * 0.5, cy = h * 0.5;
    const double s = 1.0 / zoom;
    for (int i = 0; i < 3; ++i) {
        M[i * 3 + 0] = hInv[i * 3 + 0] * s;
        M[i * 3 + 1] = hInv[i * 3 + 1] * s;
        M[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * s) + hInv[i * 3 + 1] * (cy - cy * s) +
                       hInv[i * 3 + 2];
    }
    return true;
}

bool frameWarpM(const ImageStabFrame& f, int w, int h, double M[9], bool* active) {
    const bool ident = f.corr.s == 1.0 && f.corr.theta == 0.0 && f.corr.tx == 0.0 &&
                       f.corr.ty == 0.0 && f.zoom == 1.0;
    if (active) *active = !ident;
    if (ident) {
        for (int i = 0; i < 9; ++i) M[i] = (i % 4 == 0) ? 1.0 : 0.0;
        return true;
    }
    double H[9];
    simToHomographyPx(f.corr, w, h, H);
    return composeWarpM(H, f.zoom, w, h, M);
}

double coverageSlackPx(const Sim2& corr, double zoom, int w, int h) {
    double H[9], M[9];
    simToHomographyPx(corr, w, h, H);
    if (!composeWarpM(H, zoom, w, h, M)) return -kInf;
    double slack = kInf;
    const double xs[2] = {0.0, static_cast<double>(w - 1)};
    const double ys[2] = {0.0, static_cast<double>(h - 1)};
    for (double x : xs)
        for (double y : ys) {
            const double sx = M[0] * x + M[1] * y + M[2];
            const double sy = M[3] * x + M[4] * y + M[5];
            slack = std::min({slack, sx, (w - 1) - sx, sy, (h - 1) - sy});
        }
    return slack;
}

// ---- the plan ---------------------------------------------------------------------

ImageStabPlan ImageStabPlan::build(const std::vector<PairRecord>& recs,
                                   const ImageStabParams& prm, size_t rangeStart,
                                   size_t rangeEnd, double aspect) {
    ImageStabPlan plan;
    const size_t n = recs.size();
    plan.frames_.assign(n, ImageStabFrame{});
    plan.raw_.assign(n, Sim2{});
    plan.target_.assign(n, Sim2{});
    if (n == 0) return plan;

    // Frame intervals (s). Non-positive/missing -> the clip's median.
    std::vector<double> dts(n, 0.0);
    {
        std::vector<double> pos;
        for (size_t i = 1; i < n; ++i) {
            const double d = (recs[i].ptsUs - recs[i - 1].ptsUs) * 1e-6;
            dts[i] = d;
            if (d > 0) pos.push_back(d);
        }
        const double med = pos.empty() ? 1.0 / 30.0 : medianOf(pos);
        for (size_t i = 1; i < n; ++i)
            if (!(dts[i] > 0.0)) dts[i] = med;
        dts[0] = med;
    }
    std::vector<double> dtPos(dts.begin() + 1, dts.end());
    const double medDt = dtPos.empty() ? 1.0 / 30.0 : medianOf(dtPos);
    auto framesFor = [&](double sec) {
        return std::max(1, static_cast<int>(std::lround(sec / medDt)));
    };

    // 1) Path: P_i = delta_i o P_{i-1}; a lost pair repeats the last good
    //    delta for two frames, then stops (velocity hold).
    std::vector<double> ch[4];
    for (auto& c : ch) c.assign(n, 0.0);
    {
        Sim2 P;
        Sim2 lastGood;
        int lostRun = 0;
        plan.raw_[0] = P;
        for (size_t i = 1; i < n; ++i) {
            const PairMotion m = toMotion(recs[i]);
            Sim2 d;
            if (m.status != PairStatus::Lost) {
                d = simOfMotion(m);
                lastGood = d;
                lostRun = 0;
            } else if (++lostRun <= 2) {
                d = lastGood;
            }
            P = simCompose(d, P);
            plan.raw_[i] = P;
        }
        for (size_t i = 0; i < n; ++i) {
            ch[0][i] = plan.raw_[i].tx;
            ch[1][i] = plan.raw_[i].ty;
            ch[2][i] = std::log(plan.raw_[i].s);
            ch[3][i] = plan.raw_[i].theta;
        }
    }

    const bool lock = prm.mode == 1;
    const double strength = std::max(0.0, std::min(1.0, prm.strength));
    if (n < 2 || (!lock && strength <= 0.0)) {
        plan.target_ = plan.raw_;
        return plan;  // exact pass-through: identity corrections, zoom 1
    }

    // 2) Target path T.
    std::vector<double> T[4];
    if (lock) {
        const size_t a = std::min(rangeStart, n - 1);
        const size_t b = std::min(std::max(rangeEnd, a), n - 1);
        for (int k = 0; k < 4; ++k) {
            const double med = medianOf(std::vector<double>(ch[k].begin() + a, ch[k].begin() + b + 1));
            T[k].assign(n, med);
        }
    } else {
        const double tauLow = std::pow(2.0, 2.0 * strength - 1.0);
        const double tauHigh = 0.1;
        // Intentional-motion speed (heights/s) from a pre-smoothed path.
        std::vector<double> q[4];
        for (int k = 0; k < 4; ++k) {
            q[k] = ch[k];
            smoothPair(q[k], dts, 0.25);
        }
        std::vector<double> speed(n, 0.0);
        for (size_t i = 1; i < n; ++i) {
            const double dx = q[0][i] - q[0][i - 1], dy = q[1][i] - q[1][i - 1];
            const double dl = std::fabs(q[2][i] - q[2][i - 1]), dr = std::fabs(q[3][i] - q[3][i - 1]);
            speed[i] = (std::hypot(dx, dy) + 0.5 * (dl + dr)) / dts[i];
        }
        smoothPair(speed, dts, tauHigh);
        std::vector<double> alpha(n, 1.0);
        for (size_t i = 1; i < n; ++i) {
            const double r = std::max(0.0, std::min(1.0, speed[i] / std::max(1e-6, prm.panSpeed)));
            alpha[i] = std::max(0.0, std::min(1.0, alphaFor(dts[i], tauLow) * (1.0 - r) +
                                                       alphaFor(dts[i], tauHigh) * r));
        }
        for (int k = 0; k < 4; ++k) T[k] = adaptivePair(ch[k], alpha);
    }
    for (size_t i = 0; i < n; ++i)
        plan.target_[i] = simOfChannels(T[0][i], T[1][i], T[2][i], T[3][i]);

    // 3) Budget + dynamic zoom.
    const double limit = std::max(1.0, prm.zoomLimit);
    const double tol = 1e-9;
    const int nW = framesFor(prm.zoomWindowSec);

    // Confidence cap: pairs the tracker did not trust get less correction
    // (smoothed by a short window so the fade is not a step). Applied as a
    // min() on top of the budget, not folded into the budget's long window.
    std::vector<double> cap(n, 1.0);
    for (size_t i = 0; i < n; ++i) {
        const uint8_t st = recs[i].status;
        cap[i] = st == static_cast<uint8_t>(PairStatus::Ok) ? 1.0
                 : st == static_cast<uint8_t>(PairStatus::Weak) ? 0.5 : 0.0;
    }
    {
        const int nC = framesFor(0.2);
        cap = gauss(rollingExtreme(cap, nC, false), nC);
    }

    auto corrAt = [&](size_t i, double a) {
        const Sim2 Tc = simOfChannels(ch[0][i] + a * (T[0][i] - ch[0][i]),
                                      ch[1][i] + a * (T[1][i] - ch[1][i]),
                                      ch[2][i] + a * (T[2][i] - ch[2][i]),
                                      ch[3][i] + a * (T[3][i] - ch[3][i]));
        return simCompose(Tc, simInverse(plan.raw_[i]));
    };
    auto zOf = [&](size_t i, double a) {
        return requiredZoom(corrAt(i, a), aspect, prm.edgeMargin);
    };

    std::vector<double> aRaw(n);
    for (size_t i = 0; i < n; ++i) {
        double a = 1.0;
        if (zOf(i, a) > limit + tol) {
            double lo = 0.0, hi = a;
            for (int it = 0; it < 12; ++it) {
                const double mid = 0.5 * (lo + hi);
                if (zOf(i, mid) <= limit + tol) lo = mid; else hi = mid;
            }
            a = lo;
        }
        aRaw[i] = a;
    }

    // Exact verification reference size (aspect-matched 720-high).
    const int refH = 720;
    const int refW = std::max(16, static_cast<int>(std::lround(aspect * refH)));
    std::vector<double> aSm(n), zs(n), Z(n);
    auto resmooth = [&]() {
        aSm = gauss(rollingExtreme(aRaw, nW, false), nW);
        // The tracker-confidence cap has its own SHORT window: a lost stretch
        // must not wipe out the correction for seconds around it.
        for (size_t i = 0; i < n; ++i) aSm[i] = std::min(aSm[i], cap[i]);
        std::vector<double> lz(n);
        for (size_t i = 0; i < n; ++i) {
            const double z = zOf(i, aSm[i]);
            zs[i] = z;
            lz[i] = std::log(std::min(std::max(z, 1.0), 1e3));
        }
        std::vector<double> g = gauss(rollingExtreme(lz, nW, true), nW);
        rateLimitUp(g, dts, prm.zoomRate);
        for (size_t i = 0; i < n; ++i) Z[i] = std::exp(g[i]);
    };
    auto violates = [&](size_t i) {
        if (Z[i] > limit + 1e-6) return true;
        return coverageSlackPx(corrAt(i, aSm[i]), Z[i], refW, refH) < -0.05;
    };

    int rounds = 0;
    for (; rounds < 6; ++rounds) {
        resmooth();
        bool any = false;
        for (size_t i = 0; i < n; ++i)
            if (violates(i)) {
                aRaw[i] *= 0.7;
                any = true;
            }
        if (!any) break;
    }
    int hard = 0;
    if (rounds >= 6) {
        // Last resort: the frames still failing get no correction at all
        // (identity always passes at any zoom >= 1), then smooth once more.
        resmooth();
        for (size_t i = 0; i < n; ++i)
            if (violates(i)) {
                aRaw[i] = 0.0;
                ++hard;
            }
        resmooth();
    }
    int invalid = 0;
    for (size_t i = 0; i < n; ++i)
        if (violates(i)) ++invalid;

    plan.zreq_ = zs;
    // 4) Output + stats.
    double sumZ = 0, sumA = 0, maxZ = 1, maxRate = 0;
    int limited = 0;
    for (size_t i = 0; i < n; ++i) {
        ImageStabFrame& f = plan.frames_[i];
        f.a = aSm[i];
        f.zoom = std::max(1.0, Z[i]);
        f.corr = corrAt(i, aSm[i]);
        sumZ += f.zoom;
        sumA += f.a;
        maxZ = std::max(maxZ, f.zoom);
        if (f.a < 0.999) ++limited;
        if (i > 0) maxRate = std::max(maxRate, std::fabs(std::log(f.zoom) - std::log(plan.frames_[i - 1].zoom)) / dts[i]);
    }
    plan.stats_.meanZoom = sumZ / n;
    plan.stats_.maxZoom = maxZ;
    plan.stats_.meanA = sumA / n;
    plan.stats_.limitedFraction = static_cast<double>(limited) / n;
    plan.stats_.maxZoomRate = maxRate;
    plan.stats_.budgetRounds = rounds;
    plan.stats_.hardFallbackFrames = hard;
    plan.stats_.invalidSampleFrames = invalid;
    return plan;
}

}  // namespace rawedit
