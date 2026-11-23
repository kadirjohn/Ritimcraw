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
#include <rawedit/ImageStab.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

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
        for (const Sim2& q : raw.rawPath()) {
            mnx = std::min(mnx, q.tx * K); mxx = std::max(mxx, q.tx * K);
            mny = std::min(mny, q.ty * K); mxy = std::max(mxy, q.ty * K);
            mxr = std::max(mxr, std::fabs(q.theta) * 180.0 / 3.141592653589793);
        }
        std::printf("raw path excursion (px @1080): x %.1f..%.1f  y %.1f..%.1f  |roll| max %.3f deg\n", mnx, mxx, mny, mxy, mxr);
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

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: image_stab_measure bench|analyze|plan|lumacheck ... (see header)\n");
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
    std::fprintf(stderr, "bad arguments\n");
    return 2;
}
