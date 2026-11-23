// image_stab_measure — reproducible numbers for the image-stab core (S1..S3).
// Desktop tool, run by hand (not a test); built by tools/build-desktop.sh.
//
//   image_stab_measure bench [pairs=40]
//       Stage timing of estimatePair on synthetic 640x360 frames
//       (no fixtures): pyramid / corners / LK / fit, ms per pair.
//   image_stab_measure analyze <clip.mcraw> [maxFrames=0(all)] [out.ristab]
//       Runs the Source-driven analysis; prints read / luma / pyramid /
//       corners / LK / fit ms per frame, the Ok/Weak/Lost split, the
//       real-time factor. Writes the analysis file when given.
//   image_stab_measure plan <clip.mcraw | file.ristab> [maxFrames=0] [fps-note]
//       Plan sweep: acceleration and jerk columns (raw vs stabilized, PTS
//       scaled, separate columns), residual, zoom mean/max, limited
//       fraction; zoom-rate A/B (0.05 / 0.10 / 0.20 1/s + unlimited) and
//       the envelope-follower reference (4 s / 0.2 s, an APPROXIMATION of
//       Gyroflow's follower, not verified against its source).
//   image_stab_measure plan synth [seconds=24] [fps=30] [amp=1]
//       The same sweep on a SYNTHETIC handheld path (sway + mid-band + tremor
//       + a pan; deterministic): the two fixture clips are nearly static, so
//       this is the only input where the stabilizer has work to do.
//   image_stab_measure lumacheck <clip.mcraw> [pairs=20]
//       Raw-CFA luma path vs developThumbnail luma path: difference of the
//       pair motions in working-resolution pixels (S2 acceptance < 0.1 px).
//   image_stab_measure gyrocheck <clip.mcraw> <clip.gcsv> [offsetScanMs=150]
//       Gyro cross-check (S2): parse the sidecar GCSV, integrate the
//       angular rate (VQF + gyro-only bias-corrected reference), anchor
//       the frame<->IMU clock via Sync.h's wall anchors, then scan the
//       sync offset +-offsetScanMs around the anchor and compare, per
//       usable pair, the gyro-predicted center displacement (rotation
//       homography, focal from the container's 35mm-equiv value) with the
//       image measurement. Acceptance hypothesis (plan S4): residual
//       shift RMS < ~1 px at the working resolution. Prints the rotation-
//       channel comparison (roll) too; partial honesty if the sync
//       threshold is not met.
//   image_stab_measure scale-test <file.ristab>
//       S11a scale-lock counterfactual (docs/S11_ROBUSTNESS_NOTES.md):
//       rewrites the stored records' logS channel under four policies
//       (free / lock / clamp-0.002 / clamp-0.005) and rebuilds the plan
//       for each, in both modes (Smooth, Lock). Rows: meanA, limited%,
//       zoom mean/max, invalidSampleFrames, the accumulated raw-path
//       scale exp(sum logS), and the acceleration/jerk ratios (the same
//       PTS-scaled metrics as `plan`). The free rows must equal a plain
//       `plan` run — the sandwich proof that the read+rewrite path adds
//       nothing (test_scale_tool.cpp pins that on synthetic records).
//
// Metric definitions (docs/IMAGE_STAB_PLAN.md section 4): the path is in
// pixels at 1080 px height; derivatives use the REAL PTS spacing, never an
// assumed constant frame interval.
//   velocity  v_i = (x_i - x_{i-1}) / dt_i                       (px/s)
//   accel     a_i = (v_i - v_{i-1}) / ((dt_i + dt_{i-1}) / 2)    (px/s^2)   2nd difference
//   jerk      j_i = (a_i - a_{i-1}) / (ta_i - ta_{i-1})          (px/s^3)   3rd difference
// reported as RMS over frames and over both axes, raw and stabilized,
// plus out/raw ratios in separate columns.
#include <rawedit/Develop.h>
#include <rawedit/Gyro.h>
#include <rawedit/ImageStab.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Source.h>
#include <rawedit/Stabilization.h>
#include <rawedit/Sync.h>
#include <rawedit/Thumbnail.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "image_stab_gyro.hpp"
#include "image_stab_scale_policy.hpp"

using namespace rawedit;

namespace {

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

// ---- synthetic frames for `bench` ----------------------------------------------

std::vector<uint8_t> synthFrame(int w, int h, double dx, double dy, uint64_t seed) {
    struct Wave { double kx, ky, ph, amp; };
    std::vector<Wave> ws;
    uint64_t s = seed * 6364136223846793005ull + 1;
    auto rnd = [&]() {
        s = s * 6364136223846793005ull + 1442695040888963407ull;
        return static_cast<double>(s >> 11) / 9007199254740992.0;
    };
    for (int i = 0; i < 60; ++i) {
        const double wl = 6.0 + rnd() * 74.0, a = rnd() * 6.283185307179586;
        ws.push_back({std::cos(a) * 6.283185307179586 / wl, std::sin(a) * 6.283185307179586 / wl,
                      rnd() * 6.283185307179586, wl / 20.0});
    }
    std::vector<uint8_t> out(static_cast<size_t>(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            double v = 0;
            for (const auto& k : ws) v += k.amp * std::sin(k.kx * (x - w * 0.5 - dx) + k.ky * (y - h * 0.5 - dy) + k.ph);
            const double q = 128.0 + 3.0 * v;
            out[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(std::min(255.0, std::max(0.0, q)));
        }
    return out;
}

int cmdBench(int pairs) {
    const int w = 640, h = 360;
    std::vector<std::vector<uint8_t>> fr;
    for (int i = 0; i <= pairs; ++i) fr.push_back(synthFrame(w, h, 1.7 * i, -0.9 * i, 7));
    StageTimes st;
    PairMotion prior;
    bool havePrior = false;
    const auto t0 = Clock::now();
    int ok = 0;
    for (int i = 1; i <= pairs; ++i) {
        const PairMotion m = estimatePair({w, h, w, fr[i - 1].data()}, {w, h, w, fr[i].data()},
                                          ImageStabOptions(), havePrior ? &prior : nullptr, &st);
        prior = m;
        havePrior = m.status != PairStatus::Lost;
        ok += m.status == PairStatus::Ok;
    }
    const double total = msSince(t0);
    std::printf("bench %dx%d, %d pairs (ok %d): total %.2f ms/pair\n", w, h, pairs, ok, total / pairs);
    std::printf("  pyramid %.2f | corners %.2f | LK(fwd+bwd) %.2f | fit(RANSAC+LSQ) %.2f  ms/pair\n",
                st.pyramidMs / pairs, st.cornersMs / pairs, st.lkMs / pairs, st.fitMs / pairs);
    return 0;
}

// ---- clip analysis ---------------------------------------------------------------

int cmdAnalyze(const std::string& clip, size_t maxFrames, const std::string& outFile, ImagePath* keep) {
    auto src = Source::open(clip);
    if (!src.isOk()) { std::fprintf(stderr, "open failed: %s\n", clip.c_str()); return 1; }
    Source& s = **src;
    const FrameInfo fi = s.frameInfo(0);
    std::printf("clip %s: %u x %u CFA, %zu frames\n", clip.c_str(), fi.width, fi.height, s.frameCount());
    ImagePath path;
    AnalyzeStats st;
    AnalyzeControl ctl;
    size_t limit = maxFrames;
    if (limit) {
        // Analyze only the first `limit` frames: cancel from the progress hook.
        ctl.cancelled = [&]() { return false; };
    }
    size_t done = 0;
    ctl.progress = [&](size_t d, size_t) { done = d; };
    if (limit) ctl.cancelled = [&]() { return done >= limit; };
    const auto t0 = Clock::now();
    const AnalyzeResult r = analyzeSource(s, ImageStabOptions(), ctl, path, &st);
    const double wall = msSince(t0);
    if (r == AnalyzeResult::Failed) { std::fprintf(stderr, "analysis failed\n"); return 1; }
    int ok = 0, weak = 0, lost = 0;
    for (size_t i = 1; i < path.recs.size(); ++i) {
        const uint8_t c = path.recs[i].status;
        ok += c == 0; weak += c == 1; lost += c == 2;
    }
    const double n = static_cast<double>(std::max<size_t>(1, st.frames));
    const double span = path.recs.size() > 1
        ? (path.recs.back().ptsUs - path.recs.front().ptsUs) * 1e-6 : 0.0;
    std::printf("analyzed %zu frames (work %ux%u): wall %.1f s for %.2f s of clip = %.2fx clip time\n",
                path.recs.size(), path.workW, path.workH, wall / 1e3, span, span > 0 ? wall / 1e3 / span : 0.0);
    std::printf("per frame ms: decode(readFrame) %.2f | cfaToLuma %.2f | pyramid %.2f | corners %.2f | LK %.2f | fit %.2f\n",
                st.readMs / n, st.lumaMs / n, st.track.pyramidMs / n, st.track.cornersMs / n,
                st.track.lkMs / n, st.track.fitMs / n);
    std::printf("pairs: Ok %d | Weak %d | Lost %d\n", ok, weak, lost);
    if (!outFile.empty()) {
        std::printf("write %s: %s\n", outFile.c_str(), writeImagePathFile(outFile, path) ? "ok" : "FAILED");
    }
    if (keep) *keep = std::move(path);
    return 0;
}

// ---- metrics ------------------------------------------------------------------------

struct Deriv { double acc2 = 0, jerk2 = 0; size_t na = 0, nj = 0; };

// RMS of the 2nd and 3rd difference of a 2D path (px @ 1080 high) on real PTS.
void derivRms(const std::vector<double>& x, const std::vector<double>& y,
              const std::vector<double>& t, double& accRms, double& jerkRms) {
    const size_t n = x.size();
    std::vector<double> vx, vy, tv;
    for (size_t i = 1; i < n; ++i) {
        const double dt = t[i] - t[i - 1];
        if (!(dt > 0)) continue;
        vx.push_back((x[i] - x[i - 1]) / dt);
        vy.push_back((y[i] - y[i - 1]) / dt);
        tv.push_back(0.5 * (t[i] + t[i - 1]));
    }
    std::vector<double> ax, ay, ta;
    for (size_t i = 1; i < vx.size(); ++i) {
        const double dt = tv[i] - tv[i - 1];
        ax.push_back((vx[i] - vx[i - 1]) / dt);
        ay.push_back((vy[i] - vy[i - 1]) / dt);
        ta.push_back(0.5 * (tv[i] + tv[i - 1]));
    }
    double sa = 0, sj = 0;
    for (size_t i = 0; i < ax.size(); ++i) sa += ax[i] * ax[i] + ay[i] * ay[i];
    size_t nj = 0;
    for (size_t i = 1; i < ax.size(); ++i) {
        const double dt = ta[i] - ta[i - 1];
        const double jx = (ax[i] - ax[i - 1]) / dt, jy = (ay[i] - ay[i - 1]) / dt;
        sj += jx * jx + jy * jy;
        ++nj;
    }
    accRms = ax.empty() ? 0 : std::sqrt(sa / ax.size());
    jerkRms = nj ? std::sqrt(sj / nj) : 0;
}

double residualRms(const std::vector<double>& x, const std::vector<double>& y,
                   const std::vector<double>& t) {
    const size_t n = x.size();
    double ss = 0;
    size_t cnt = 0;
    for (size_t i = 0; i < n; ++i) {
        double sx = 0, sy = 0;
        size_t k = 0;
        for (size_t j = 0; j < n; ++j)
            if (std::fabs(t[j] - t[i]) <= 0.5) { sx += x[j]; sy += y[j]; ++k; }
        const double dx = x[i] - sx / k, dy = y[i] - sy / k;
        ss += dx * dx + dy * dy;
        ++cnt;
    }
    return cnt ? std::sqrt(ss / cnt) : 0;
}

// Envelope follower reference: attack 0.2 s (rises), release 4 s (falls),
// forward then backward; the applied zoom is max(follower, z) so coverage holds.
void followerRef(const std::vector<double>& z, const std::vector<double>& dts, double& meanZ,
                 double& maxZ, double& undershoot) {
    const size_t n = z.size();
    std::vector<double> e(z);
    auto step = [&](double cur, double target, double dt) {
        const double tau = target > cur ? 0.2 : 4.0;
        return cur + (target - cur) * (1.0 - std::exp(-dt / tau));
    };
    for (size_t i = 1; i < n; ++i) e[i] = step(e[i - 1], z[i], dts[i]);
    std::vector<double> b(e);
    for (size_t i = n - 1; i-- > 0;) b[i] = step(b[i + 1], std::max(z[i], e[i]), dts[i + 1]);
    double s = 0, m = 1;
    size_t under = 0;
    for (size_t i = 0; i < n; ++i) {
        if (b[i] < z[i] - 1e-9) ++under;
        const double v = std::max(b[i], z[i]);
        s += v;
        m = std::max(m, v);
    }
    meanZ = s / n;
    maxZ = m;
    undershoot = static_cast<double>(under) / n;
}

// Deterministic handheld-like path in px @ 1080 high: slow sway, mid-band
// wobble, tremor, a 2 s intentional pan, small roll. NOT real data.
ImagePath synthPath(double seconds, double fps, double amp) {
    ImagePath p;
    const size_t n = static_cast<size_t>(seconds * fps);
    p.workW = 640;
    p.workH = 360;
    p.frameCount = static_cast<uint32_t>(n);
    p.complete = true;
    struct Comp { double f, ax, ay, ar, px, py, pr; };
    const Comp comps[] = {{0.35, 38, 24, 0.010, 0.3, 1.1, 2.0}, {1.7, 12, 9, 0.004, 2.2, 0.4, 5.1},
                          {4.3, 5, 4, 0.002, 4.0, 3.3, 0.9},   {8.7, 2.2, 1.8, 0.001, 1.5, 5.5, 2.6}};
    auto at = [&](double t, double& x, double& y, double& r) {
        x = y = r = 0;
        for (const Comp& c : comps) {
            x += amp * c.ax * std::sin(6.283185307179586 * c.f * t + c.px);
            y += amp * c.ay * std::sin(6.283185307179586 * c.f * t + c.py);
            r += amp * c.ar * std::sin(6.283185307179586 * c.f * t + c.pr);
        }
        const double pan = std::min(1.0, std::max(0.0, (t - 8.0) / 2.0));  // 8..10 s ramp
        x += 400.0 * pan * pan * (3 - 2 * pan);                           // smoothstep 400 px
    };
    double px0 = 0, py0 = 0, pr0 = 0;
    at(0, px0, py0, pr0);
    for (size_t i = 0; i < n; ++i) {
        const double t = static_cast<double>(i) / fps;
        double x, y, r;
        at(t, x, y, r);
        double xp, yp, rp;
        at(i ? t - 1.0 / fps : 0, xp, yp, rp);
        PairMotion m;
        m.status = PairStatus::Ok;
        m.inlierRatio = 0.9f;
        m.coverage = 1.0f;
        if (i) {
            m.tx = (x - xp) / 1080.0;
            m.ty = (y - yp) / 1080.0;
            m.theta = r - rp;
        }
        p.recs.push_back(toRecord(static_cast<int64_t>(std::llround(t * 1e6)), m));
    }
    return p;
}

int cmdPlan(const std::string& file, size_t maxFrames, const std::string& tag,
            double synthSeconds = 24, double synthFps = 30, double synthAmp = 1) {
    ImagePath path;
    if (file == "synth") {
        path = synthPath(synthSeconds, synthFps, synthAmp);
    } else if (file.size() > 7 && file.substr(file.size() - 7) == ".ristab") {
        if (readImagePathFile(file, path) != PathParse::Ok) { std::fprintf(stderr, "bad analysis file\n"); return 1; }
    } else {
        if (cmdAnalyze(file, maxFrames, "", &path) != 0) return 1;
    }
    const size_t n = path.recs.size();
    std::printf("\n== plan sweep: %s (%zu frames, work %ux%u) ==\n", tag.empty() ? file.c_str() : tag.c_str(),
                n, path.workW, path.workH);
    std::vector<double> t(n);
    for (size_t i = 0; i < n; ++i) t[i] = (path.recs[i].ptsUs - path.recs[0].ptsUs) * 1e-6;
    std::vector<double> dts(n, 1.0 / 30.0);
    for (size_t i = 1; i < n; ++i) dts[i] = std::max(1e-4, t[i] - t[i - 1]);
    const double aspect = path.workH ? static_cast<double>(path.workW) / path.workH : 16.0 / 9.0;
    const double K = 1080.0;  // report px at 1080 height
    {
        const ImageStabPlan raw = ImageStabPlan::build(path.recs, ImageStabParams(), 0, n - 1, aspect);
        double mnx = 1e9, mxx = -1e9, mny = 1e9, mxy = -1e9, mxr = 0;
        double mnS = 1e9, mxS = -1e9;
        for (const Sim2& q : raw.rawPath()) {
            mnx = std::min(mnx, q.tx * K); mxx = std::max(mxx, q.tx * K);
            mny = std::min(mny, q.ty * K); mxy = std::max(mxy, q.ty * K);
            mxr = std::max(mxr, std::fabs(q.theta) * 180.0 / 3.141592653589793);
            mnS = std::min(mnS, q.s); mxS = std::max(mxS, q.s);
        }
        std::printf("raw path excursion (px @1080): x %.1f..%.1f  y %.1f..%.1f  |roll| max %.3f deg  scale %.4f..%.4f\n",
                    mnx, mxx, mny, mxy, mxr, mnS, mxS);
    }

    // Pan-speed calibration data (plan section 3.3 Smooth's panSpeed
    // hypothesis 0.5 heights/s; measured here per clip): the RAW path's
    // per-pair translational speed in heights/s (px @1080 / 1080 / dt),
    // plus the same after the 0.25 s pre-smooth the plan uses for the
    // intentional-motion detector. Reported as percentiles.
    {
        std::vector<double> vRaw, vSm;
        const ImageStabPlan raw = ImageStabPlan::build(path.recs, ImageStabParams(), 0, n - 1, aspect);
        const std::vector<Sim2>& rp = raw.rawPath();
        for (size_t i = 1; i < n && i < rp.size(); ++i) {
            const double dt = t[i] - t[i - 1];
            if (!(dt > 0)) continue;
            const double dx = (rp[i].tx - rp[i - 1].tx) * K / dt;
            const double dy = (rp[i].ty - rp[i - 1].ty) * K / dt;
            vRaw.push_back(std::hypot(dx, dy) / K);  // heights/s
        }
        if (vRaw.size() > 4) {
            std::vector<double> vSm(vRaw.size());
            // 0.25 s one-pole pre-smooth, matching the plan's detector input.
            double avg = vRaw[0];
            for (size_t i = 0; i < vRaw.size(); ++i) {
                const double dt = t[i + 1] - t[i];
                const double a = 1.0 - std::exp(-dt / 0.25);
                avg += a * (vRaw[i] - avg);
                vSm[i] = avg;
            }
            auto pct = [](std::vector<double> v, double p) {
                if (v.empty()) return 0.0;
                std::sort(v.begin(), v.end());
                return v[std::min(v.size() - 1, static_cast<size_t>(p * (v.size() - 1)))];
            };
            std::printf("pan speed (heights/s, raw pair speeds): p50 %.3f | p90 %.3f | p99 %.3f | max %.3f\n",
                        pct(vRaw, 0.5), pct(vRaw, 0.9), pct(vRaw, 0.99), pct(vRaw, 1.0));
            std::printf("pan speed after 0.25s pre-smooth (the plan's detector input): p50 %.3f | p90 %.3f | p99 %.3f | max %.3f\n",
                        pct(vSm, 0.5), pct(vSm, 0.9), pct(vSm, 0.99), pct(vSm, 1.0));
        }
    }

    // Confidence score components (plan section 3.10's proposal, additive):
    //   C = 0.4*okFraction + 0.25*medianInlierRatio + 0.2*meanCoverage
    //       + 0.15*(1 - normalize(residualRms))
    // residualRms here is the inlier residual at working res, px; normalized
    // by the inlier threshold (1.5 px) and clamped to [0,1].
    {
        size_t ok = 0, all = 0;
        std::vector<double> inl, cov, resid;
        for (size_t i = 1; i < path.recs.size(); ++i) {
            const PairMotion m = toMotion(path.recs[i]);
            ++all;
            if (m.status == PairStatus::Ok) ++ok;
            inl.push_back(m.inlierRatio);
            cov.push_back(m.coverage);
            resid.push_back(m.residualRms);
        }
        auto med = [](std::vector<double> v) {
            if (v.empty()) return 0.0;
            std::sort(v.begin(), v.end());
            return v.size() % 2 ? v[v.size() / 2] : 0.5 * (v[v.size() / 2 - 1] + v[v.size() / 2]);
        };
        auto meanRes = [&]() {
            double s = 0;
            for (double r : resid) s += r;
            return resid.empty() ? 0.0 : s / resid.size();
        };
        const double okFrac = all ? double(ok) / double(all) : 0;
        const double medInl = med(inl);
        double covSum = 0;
        for (double c : cov) covSum += c;
        const double meanCov = cov.empty() ? 0 : covSum / cov.size();
        const double resNorm = std::min(1.0, meanRes() / 1.5);
        const double score = 0.4 * okFrac + 0.25 * medInl + 0.2 * meanCov
                             + 0.15 * (1.0 - resNorm);
        const char* grade = score >= 0.8 ? "Good" : score >= 0.55 ? "Fair"
                            : score >= 0.3 ? "Weak" : "Unusable";
        std::printf("confidence: okFraction %.3f | medianInlierRatio %.3f | meanCoverage %.3f"
                    " | meanResid %.3f px (norm %.3f)\n",
                    okFrac, medInl, meanCov, meanRes(), resNorm);
        std::printf("confidence score C = %.3f -> %s (thresholds 0.8/0.55/0.3; proposal, owner approval pending)\n",
                    score, grade);
    }

    std::printf("\nstrength sweep (Smooth, zoomLimit 1.15, zoomRate 0.10, window 1.0 s)\n");
    std::printf("%-8s | %9s %9s %6s | %10s %10s %6s | %8s %8s | %6s %6s %6s %5s\n",
                "strength", "acc raw", "acc out", "ratio", "jerk raw", "jerk out", "ratio",
                "resid px", "raw res", "zoomAv", "zoomMx", "limit%", "inval");
    for (double strength : {0.25, 0.5, 0.75, 1.0}) {
        ImageStabParams p;
        p.strength = strength;
        const ImageStabPlan pl = ImageStabPlan::build(path.recs, p, 0, n - 1, aspect);
        std::vector<double> rx(n), ry(n), ox(n), oy(n);
        for (size_t i = 0; i < n; ++i) {
            const Sim2 out = simCompose(pl.frame(i).corr, pl.rawPath()[i]);
            rx[i] = pl.rawPath()[i].tx * K; ry[i] = pl.rawPath()[i].ty * K;
            ox[i] = out.tx * K; oy[i] = out.ty * K;
        }
        double ar, jr, ao, jo;
        derivRms(rx, ry, t, ar, jr);
        derivRms(ox, oy, t, ao, jo);
        const double rres = residualRms(rx, ry, t), ores = residualRms(ox, oy, t);
        std::printf("%-8.2f | %9.1f %9.1f %6.3f | %10.0f %10.0f %6.3f | %8.2f %8.2f | %6.3f %6.3f %6.1f %5d\n",
                    strength, ar, ao, ar > 0 ? ao / ar : 0, jr, jo, jr > 0 ? jo / jr : 0, ores, rres,
                    pl.stats().meanZoom, pl.stats().maxZoom, pl.stats().limitedFraction * 100.0,
                    pl.stats().invalidSampleFrames);
    }
    std::printf("(acc = 2nd difference px/s^2, jerk = 3rd difference px/s^3, px @ 1080 high, PTS-scaled)\n");

    std::vector<double> zreq;
    for (double lim : {1.15, 1.25}) {
        std::printf("\nzoom speed A/B (strength 0.5, zoomLimit %.2f): max |d ln Z/dt| allowed, 1/s\n", lim);
        std::printf("%-22s | %6s %6s | %10s | %6s %6s\n", "variant", "zoomAv", "zoomMx", "measuredMax", "limit%", "hard");
        zreq.clear();
        for (double rate : {0.05, 0.10, 0.20, 0.0}) {
            ImageStabParams p;
            p.strength = 0.5;
            p.zoomLimit = lim;
            p.zoomRate = rate;
            const ImageStabPlan pl = ImageStabPlan::build(path.recs, p, 0, n - 1, aspect);
            if (zreq.empty()) zreq = pl.requiredZoomSeries();
            char nm[48];
            if (rate > 0) std::snprintf(nm, sizeof nm, "rate %.2f /s", rate); else std::snprintf(nm, sizeof nm, "unlimited");
            std::printf("%-22s | %6.3f %6.3f | %10.3f | %6.1f %6d\n", nm, pl.stats().meanZoom, pl.stats().maxZoom,
                        pl.stats().maxZoomRate, pl.stats().limitedFraction * 100.0, pl.stats().hardFallbackFrames);
        }
        double mz, xz, under;
        followerRef(zreq, dts, mz, xz, under);
        std::printf("%-22s | %6.3f %6.3f | %10s | (follower alone below z on %.1f%% of frames)\n",
                    "envelope-ref 4s/0.2s*", mz, xz, "-", under * 100.0);
    }
    std::printf("  * approximation of Gyroflow's Envelope Follower, NOT verified against its source\n");

    std::printf("\nzoom limit sweep (strength 0.5, rate 0.10): what the limit costs\n");
    std::printf("%-8s | %6s %6s | %6s %6s %6s\n", "limit", "zoomAv", "zoomMx", "meanA", "limit%", "hard");
    for (double lim : {1.0, 1.05, 1.10, 1.15, 1.25, 1.5}) {
        ImageStabParams p;
        p.zoomLimit = lim;
        const ImageStabPlan pl = ImageStabPlan::build(path.recs, p, 0, n - 1, aspect);
        std::printf("%-8.2f | %6.3f %6.3f | %6.3f %6.1f %6d\n", lim, pl.stats().meanZoom, pl.stats().maxZoom,
                    pl.stats().meanA, pl.stats().limitedFraction * 100.0, pl.stats().hardFallbackFrames);
    }
    return 0;
}

// ---- raw-luma vs developed-luma ------------------------------------------------------

int cmdLumaCheck(const std::string& clip, int pairs) {
    auto src = Source::open(clip);
    if (!src.isOk()) { std::fprintf(stderr, "open failed\n"); return 1; }
    Source& s = **src;
    const size_t total = s.frameCount();
    const FrameInfo f0 = s.frameInfo(0);
    int stride = std::max<int>(1, static_cast<int>((std::max(f0.width, f0.height) + 639) / 640));
    std::printf("lumacheck: %zu frames, thumbnail stride %d\n", total, stride);
    auto lumaRaw = [&](size_t i, std::vector<uint8_t>& l, int& w, int& h) {
        auto cfa = s.readFrame(i);
        if (!cfa.isOk()) return false;
        const FrameInfo fi = s.frameInfo(i);
        int32_t black[4];
        const bool dyn = fi.dynamicBlack[0] >= 0;
        for (int k = 0; k < 4; ++k) black[k] = dyn ? fi.dynamicBlack[k] : s.rawMeta().black[k];
        const int32_t white = fi.dynamicWhite > 0 ? fi.dynamicWhite : s.rawMeta().white;
        return cfaToLuma(cfa.value->data(), static_cast<int>(fi.width), static_cast<int>(fi.height),
                         black, white, 640, l, w, h);
    };
    auto lumaDev = [&](size_t i, std::vector<uint8_t>& l, int& w, int& h) {
        auto cfa = s.readFrame(i);
        if (!cfa.isOk()) return false;
        const ImageF im = developThumbnail(cfa.value->data(), s.frameInfo(i), s.rawMeta(), Grade{}, stride);
        if (im.w == 0) return false;
        w = static_cast<int>(im.w);
        h = static_cast<int>(im.h);
        l.resize(static_cast<size_t>(w) * h);
        for (size_t k = 0; k < l.size(); ++k) {
            const float y = 0.2126f * im.rgb[3 * k] + 0.7152f * im.rgb[3 * k + 1] + 0.0722f * im.rgb[3 * k + 2];
            l[k] = static_cast<uint8_t>(std::sqrt(std::min(1.0f, std::max(0.0f, y))) * 255.0f + 0.5f);
        }
        return true;
    };
    std::vector<double> diffs;
    for (int k = 0; k < pairs; ++k) {
        const size_t i = 1 + (static_cast<size_t>(k) * (total - 2)) / std::max(1, pairs);
        std::vector<uint8_t> a0, a1, b0, b1;
        int aw, ah, bw, bh, w2, h2;
        if (!lumaRaw(i - 1, a0, aw, ah) || !lumaRaw(i, a1, w2, h2)) return 1;
        if (!lumaDev(i - 1, b0, bw, bh) || !lumaDev(i, b1, w2, h2)) return 1;
        const PairMotion ma = estimatePair({aw, ah, aw, a0.data()}, {aw, ah, aw, a1.data()});
        const PairMotion mb = estimatePair({bw, bh, bw, b0.data()}, {bw, bh, bw, b1.data()});
        // Compare in pixels of a 360-high working frame.
        const double dx = (ma.tx - mb.tx) * 360.0, dy = (ma.ty - mb.ty) * 360.0;
        const double d = std::hypot(dx, dy);
        std::printf("  pair %zu: raw(%dx%d, %s) tx=%.3f ty=%.3f | dev(%dx%d, %s) tx=%.3f ty=%.3f | diff %.3f px\n", i,
                    aw, ah, ma.status == PairStatus::Ok ? "Ok" : ma.status == PairStatus::Weak ? "Weak" : "Lost",
                    ma.tx * 360, ma.ty * 360, bw, bh,
                    mb.status == PairStatus::Ok ? "Ok" : mb.status == PairStatus::Weak ? "Weak" : "Lost",
                    mb.tx * 360, mb.ty * 360, d);
        if (ma.status != PairStatus::Lost && mb.status != PairStatus::Lost) diffs.push_back(d);
    }
    if (!diffs.empty()) {
        std::sort(diffs.begin(), diffs.end());
        double mean = 0;
        for (double d : diffs) mean += d;
        std::printf("diff over %zu pairs (px @360 high): mean %.3f median %.3f max %.3f\n", diffs.size(),
                    mean / diffs.size(), diffs[diffs.size() / 2], diffs.back());
    }
    return 0;
}

// ---- gyro cross-check (gyrocheck) ---------------------------------------------------

int cmdGyroCheck(const std::string& clip, const std::string& gcsv, double scanMs) {
    // 1) The clip's pair measurements (analysis, no checkpoint file).
    auto src = Source::open(clip);
    if (!src.isOk()) { std::fprintf(stderr, "open failed: %s\n", clip.c_str()); return 1; }
    Source& s = **src;
    std::printf("gyrocheck: analyzing %s for the image side ...\n", clip.c_str());
    ImagePath path;
    AnalyzeStats st;
    AnalyzeControl ctl;
    const auto t0 = Clock::now();
    if (analyzeSource(s, ImageStabOptions(), ctl, path, &st) != AnalyzeResult::Done) {
        std::fprintf(stderr, "analysis failed\n");
        return 1;
    }
    const double analyzeWall = msSince(t0) / 1e3;
    std::vector<PairMotion> meas(path.recs.size());
    for (size_t i = 0; i < path.recs.size(); ++i) meas[i] = toMotion(path.recs[i]);
    std::vector<int64_t> pts(path.recs.size());
    for (size_t i = 0; i < path.recs.size(); ++i) pts[i] = path.recs[i].ptsUs;
    const FrameInfo fi0 = s.frameInfo(0);

    // 2) Gyro side: parse, remap to the canonical camera frame, integrate.
    auto parsed = parseGcsv(gcsv);
    if (!parsed.isOk()) { std::fprintf(stderr, "gcsv: %s\n", parsed.detail.c_str()); return 1; }
    ImuData imu = std::move(*parsed.value);
    if (imu.logStartWallMs <= 0) {
        std::fprintf(stderr, "gcsv carries no wall-clock `timestamp` header: sync would be manual\n");
        return 1;
    }
    const AxisRemap remap = axisRemapForImu(imu);
    if (!remap.known) { std::fprintf(stderr, "axis remap unknown (orientation metadata unusable)\n"); return 1; }
    std::vector<ImuSample> gyro, accel;
    gyro.reserve(imu.gyro.size());
    for (ImuSample sm : imu.gyro) { remap.apply(sm.x, sm.y, sm.z); gyro.push_back(sm); }
    accel.reserve(imu.accel.size());
    for (ImuSample sm : imu.accel) { remap.apply(sm.x, sm.y, sm.z); accel.push_back(sm); }
    const VqfOrientation vqf = estimateOrientationVqf(gyro, accel);
    OrientationSeries gyroRefSeries;
    {
        std::vector<ImuSample> g2 = gyro;
        for (auto& sm : g2) {
            sm.x -= float(vqf.gyroBias[0]);
            sm.y -= float(vqf.gyroBias[1]);
            sm.z -= float(vqf.gyroBias[2]);
        }
        // Bias-corrected gyro-only reference (drifts over ~a clip, no accel).
        gyroRefSeries = integrateGyro(g2);
    }
    std::printf("gyro: %zu samples, %.1f Hz, span %.3f s; video %zu frames, span %.3f s\n",
                imu.gyro.size(), double(imu.gyro.size() - 1) / (imu.spanNs() * 1e-9),
                imu.spanNs() * 1e-9, path.recs.size(),
                (pts.back() - pts.front()) * 1e-6);

    // 3) Focal: the container's 35mm-equivalent, converted at the working
    //    height (the same resolution the image tx/ty are scaled to).
    const RawMeta& meta = s.rawMeta();
    const double f35 = meta.focalLengthMm > 0 ? meta.focalLengthMm : 23.9;
    const double workWAspect = double(path.workW) / double(path.workH ? path.workH : 1);
    const double workH = path.workH ? path.workH : 360;
    const double workW = workH * workWAspect;
    const double fpx = focalLengthPxFromEquivalent(f35, int(workW));
    std::printf("focal: container %.3f mm 35mm-eq -> %.1f px (assumed: width spans 36mm) at %dx%d working\n",
                f35, fpx, int(workW), int(workH));

    // 4) The sync anchor (Sync.h wall anchors; receive lag unobservable, so
    //    the anchor offset gets SCANNED +-scanMs).
    const SyncEstimate anchor = estimateOffsetFromAnchors(fi0.recvdMs, imu.logStartWallMs,
                                                          fi0.ptsUs * 1000);
    if (anchor.confidence != SyncConfidence::Anchored) {
        std::fprintf(stderr, "anchor estimate unavailable (recvdMs %lld / logStart %lld)\n",
                     static_cast<long long>(fi0.recvdMs), static_cast<long long>(imu.logStartWallMs));
        return 1;
    }
    std::printf("anchor offset %.1f ms (stated bound +-%.0f ms); scanning +-%.0f ms in 5 ms steps\n",
                anchor.model.offsetNs * 1e-6, anchor.errorBoundMs, scanMs);

    // 5) The scan. For each candidate offset compare both orientation
    //    estimators; keep each one's best.
    struct ScanPoint {
        double offsMs = 0;
        double shiftRms = 0;
        double thetaRmsDeg = 0;
        size_t nUsed = 0;
    };
    std::vector<ScanPoint> scanVqf, scanInt;
    int64_t bestVqf = anchor.model.offsetNs, bestInt = anchor.model.offsetNs;
    double bestVqfRms = 1e18, bestIntRms = 1e18;
    GyroCompare cmpVqf, cmpInt;
    for (int step = -int(scanMs / 5); step <= int(scanMs / 5); ++step) {
        const int64_t offs = anchor.model.offsetNs + int64_t(step) * 5 * 1000000;
        for (int which = 0; which < 2; ++which) {
            const OrientationSeries& series = which == 0 ? vqf.series : gyroRefSeries;
            std::vector<uint8_t> inside;
            const std::vector<GyroPairImage> pred = predictPairsFromSeries(series, pts, offs, fpx, &inside);
            const GyroCompare c = compareGyroImage(pred, meas, double(workH), inside);
            auto& pts_ = which == 0 ? scanVqf : scanInt;
            pts_.push_back({offs * 1e-6, c.shiftRms, c.thetaRmsDeg, c.n});
            if (c.n > meas.size() / 4 && c.shiftRms < (which == 0 ? bestVqfRms : bestIntRms)) {
                if (which == 0) { bestVqfRms = c.shiftRms; bestVqf = offs; cmpVqf = c; }
                else { bestIntRms = c.shiftRms; bestInt = offs; cmpInt = c; }
            }
        }
    }
    const double anchorMs = anchor.model.offsetNs * 1e-6;

    std::printf("\nsync scan (offset ms -> shift residual RMS px @%d): VQF | gyro-only\n", int(workH));
    for (size_t i = 0; i < scanVqf.size(); ++i)
        std::printf("  %+7.0f | %8.2f | %8.2f\n", scanVqf[i].offsMs, scanVqf[i].shiftRms,
                    i < scanInt.size() ? scanInt[i].shiftRms : 0.0);

    auto reportBest = [&](const char* name, int64_t offs, const GyroCompare& c) {
        std::printf("\n[%s] best offset %+lld ms (anchor %+lld -> scan delta %+lld ms)\n",
                    name, static_cast<long long>(offs * 1e-6),
                    static_cast<long long>(anchorMs),
                    static_cast<long long>(offs / 1000000 - int64_t(anchorMs)));
        std::printf("  pairs used %zu (lost %zu, outside gyro span %zu)\n", c.n, c.nLost, c.nOutside);
        std::printf("  shift residual: x RMS %.3f px | y RMS %.3f px | 2D RMS %.3f px | p90 %.3f px\n",
                    c.txRms, c.tyRms, c.shiftRms, c.p90);
        std::printf("  corr(pred,meas): x %.3f | y %.3f | slope through origin: x %.3f | y %.3f\n",
                    c.txCorr, c.tyCorr, c.txSlope, c.tySlope);
        std::printf("  rotation channel: theta residual RMS %.4f deg (%.3f px at half-height), corr %.3f\n",
                    c.thetaRmsDeg, c.thetaRmsPx, c.thetaCorr);
    };
    reportBest("vqf", bestVqf, cmpVqf);
    reportBest("gyro-only", bestInt, cmpInt);

    const double accept = 1.0;  // px @ working res
    const double bestRms = std::min(bestVqfRms, cmpInt.n ? bestIntRms : 1e18);
    std::printf("\nverdict (hypothesis: shift RMS < %.1f px @ working res): ", accept);
    if (bestRms < accept) {
        std::printf("PASS (%.2f px)\n", bestRms);
    } else {
        // Partial honesty: report the rotation channel verdict separately.
        const double thetaBest = std::min(cmpVqf.n ? cmpVqf.thetaRmsPx : 1e18,
                                          cmpInt.n ? cmpInt.thetaRmsPx : 1e18);
        std::printf("shift FAIL (best %.2f px)", bestRms);
        if (thetaBest < 1.0)
            std::printf(" — PARTIAL: rotation channel validated (%.3f px at half-height), translation above sync threshold\n",
                        thetaBest);
        else
            std::printf(" — full FAIL (rotation residual %.3f px too)\n", thetaBest);
    }
    std::printf("analysis wall %.1f s\n", analyzeWall);
// ---- scale-lock counterfactual (scale-test; S11a) -------------------------------

int cmdScaleTest(const std::string& file) {
    ImagePath path;
    if (readImagePathFile(file, path) != PathParse::Ok) {
        std::fprintf(stderr, "bad analysis file: %s\n", file.c_str());
        return 1;
    }
    const size_t n = path.recs.size();
    if (n < 3) { std::fprintf(stderr, "too few records\n"); return 1; }
    std::vector<double> t(n);
    for (size_t i = 0; i < n; ++i) t[i] = (path.recs[i].ptsUs - path.recs[0].ptsUs) * 1e-6;
    const double aspect = path.workH ? static_cast<double>(path.workW) / path.workH : 16.0 / 9.0;
    const double K = 1080.0;
    std::printf("\n== scale-test: %s (%zu frames, work %ux%u; strength 0.5, zoomLimit 1.15, zoomRate 0.10) ==\n",
                file.c_str(), n, path.workW, path.workH);
    std::printf("policies: free = stored records verbatim | lock = logS->0 on measured pairs"
                " | clamp-S = |logS|<=S on measured pairs (S11 notes' counterfactual)\n");

    struct Row { const char* policy; int mode; double meanA, limitPct, zoomAv, zoomMx, accAcc, accOut, accRatio,
                         jerkRatio, accum, dThetaDeg, dtxPx, dtyPx; int inval; };
    std::vector<Row> rows;
    struct Cfg { const char* name; ScalePolicy pol; double band; };
    const Cfg cfgs[] = {
        {"free", ScalePolicy::Free, 0.0},
        {"lock", ScalePolicy::Lock, 0.0},
        {"clamp-0.002", ScalePolicy::Clamp, 0.002},
        {"clamp-0.005", ScalePolicy::Clamp, 0.005},
    };
    for (const Cfg& cfg : cfgs) {
        const std::vector<PairRecord> recs = applyScalePolicy(path.recs, cfg.pol, cfg.band);
        for (int mode = 0; mode < 2; ++mode) {
            ImageStabParams p;
            p.mode = mode;
            p.strength = 0.5;   // the notes' counterfactual configuration
            p.zoomLimit = 1.15;
            p.zoomRate = 0.10;
            const ImageStabPlan pl = ImageStabPlan::build(recs, p, 0, n - 1, aspect);
            std::vector<double> rx(n), ry(n), ox(n), oy(n);
            for (size_t i = 0; i < n; ++i) {
                const Sim2 out = simCompose(pl.frame(i).corr, pl.rawPath()[i]);
                rx[i] = pl.rawPath()[i].tx * K; ry[i] = pl.rawPath()[i].ty * K;
                ox[i] = out.tx * K; oy[i] = out.ty * K;
            }
            double ar, jr, ao, jo;
            derivRms(rx, ry, t, ar, jr);
            derivRms(ox, oy, t, ao, jo);
            // Channel-preservation columns (S11 notes): how much does the
            // policy move each TARGET-path channel vs the plan built on the
            // ORIGINAL records? Record-level surgicality (theta untouched,
            // logS only) is pinned by test_scale_tool; at the PATH level
            // x/y legitimately shift under lock (the accumulated-scale
            // coupling in simCompose is exactly what the policy removes).
            ImageStabParams p0 = p;
            const ImageStabPlan ref = ImageStabPlan::build(path.recs, p0, 0, n - 1, aspect);
            double dTheta = 0, dtx = 0, dty = 0;
            for (size_t i = 0; i < n; ++i) {
                dTheta = std::max(dTheta, std::fabs(pl.targetPath()[i].theta - ref.targetPath()[i].theta));
                dtx = std::max(dtx, std::fabs(pl.targetPath()[i].tx - ref.targetPath()[i].tx));
                dty = std::max(dty, std::fabs(pl.targetPath()[i].ty - ref.targetPath()[i].ty));
            }
            rows.push_back({cfg.name, mode, pl.stats().meanA, pl.stats().limitedFraction * 100.0,
                            pl.stats().meanZoom, pl.stats().maxZoom, ar, ao, ar > 0 ? ao / ar : 0,
                            jr > 0 ? jo / jr : 0, accumulatedScale(recs),
                            dTheta * 180.0 / 3.141592653589793, dtx * K, dty * K,
                            pl.stats().invalidSampleFrames});
        }
    }
    std::printf("\n%-12s %-7s | %6s %6s %7s %7s %5s | %9s | %7s %7s %7s %6s | %7s %10s\n",
                "policy", "mode", "meanA", "limit%", "zoomAv", "zoomMx", "inval",
                "rawScale", "accRaw", "accOut", "accR", "jerkR", "dTheta", "dTx/dTy(px)");
    for (const Row& r : rows) {
        std::printf("%-12s %-7s | %6.3f %6.1f %7.3f %7.3f %5d | %8.2fx | %7.1f %7.1f %6.3f %6.3f | %7.4f %5.1f/%-5.1f\n",
                    r.policy, r.mode ? "Lock" : "Smooth", r.meanA, r.limitPct, r.zoomAv, r.zoomMx,
                    r.inval, r.accum, r.accAcc, r.accOut, r.accRatio, r.jerkRatio, r.dThetaDeg,
                    r.dtxPx, r.dtyPx);
    }
    std::printf("(acc/jerk ratios: out/raw, px @1080, PTS-scaled. rawScale = exp(sum stored logS) of the"
                " policy's record chain — the drift number from the S11 notes. dTheta/dTx/dTy: max target-path"
                " channel change vs the ORIGINAL records' plan. theta is expected 0 for lock/clamp on"
                " lock-only-record rewrites (record-level bit-exactness is pinned by test_scale_tool);"
                " tx/ty DO move on the PATH under lock because the accumulated-scale coupling in"
                " simCompose is exactly what the policy removes — that is the counterfactual working.)\n");
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: image_stab_measure bench|analyze|plan|lumacheck|gyrocheck|scale-test ... (see header)\n");
        return 2;
    }
    const std::string cmd = argv[1];
    if (cmd == "bench") return cmdBench(argc > 2 ? std::atoi(argv[2]) : 40);
    if (cmd == "analyze" && argc > 2)
        return cmdAnalyze(argv[2], argc > 3 ? std::strtoull(argv[3], nullptr, 10) : 0,
                          argc > 4 ? argv[4] : "", nullptr);
    if (cmd == "plan" && argc > 2 && std::string(argv[2]) == "synth")
        return cmdPlan("synth", 0, "synthetic handheld", argc > 3 ? std::atof(argv[3]) : 24,
                       argc > 4 ? std::atof(argv[4]) : 30, argc > 5 ? std::atof(argv[5]) : 1);
    if (cmd == "plan" && argc > 2)
        return cmdPlan(argv[2], argc > 3 ? std::strtoull(argv[3], nullptr, 10) : 0, argc > 4 ? argv[4] : "");
    if (cmd == "lumacheck" && argc > 2) return cmdLumaCheck(argv[2], argc > 3 ? std::atoi(argv[3]) : 20);
    if (cmd == "gyrocheck" && argc > 3)
        return cmdGyroCheck(argv[2], argv[3], argc > 4 ? std::atof(argv[4]) : 150.0);
    if (cmd == "scale-test" && argc > 2) return cmdScaleTest(argv[2]);
    std::fprintf(stderr, "bad arguments\n");
    return 2;
}
