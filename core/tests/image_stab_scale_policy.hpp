// image_stab_scale_policy.hpp — the S11a scale-lock counterfactual, as a
// pure, testable record rewrite (measurement tool layer; production plan
// code untouched — the plan gains its own scaleLock parameter only in the
// S11a production package, after owner approval).
//
// WHAT THIS MODELS (docs/S11_ROBUSTNESS_NOTES.md, items 2/4/6): the walk
// clip's drift comes from a systematic +logS bias on MEASURED (Ok/Weak)
// pairs — walking parallax that the free 4-DOF similarity absorbs as
// per-pair scale and the composition accumulates exponentially. The
// counterfactual asks: what would the plan do if the scale channel were
// locked (or clamped)? The honest way to answer that WITHOUT touching
// the production planner is to rewrite the RECORDS (logS only) and hand
// them to ImageStabPlan::build as-is.
//
// POLICIES:
//   Free   — verbatim copy of every record (bit-identical; the sandwich
//            proof: a plan built on Free records must equal a plan built
//            on the original records, so the tool's read+rewrite path
//            adds nothing).
//   Lock   — logS forced to 0 on every MEASURED record (status Ok/Weak).
//            Lost records already carry 0; untouched. theta/tx/ty and all
//            quality fields are bit-identical to the source records.
//   Clamp  — logS clamped to [-band, +band] (|logS| > band -> sign(band)).
//            The measured per-pair bias of the walk clip (+0.0122) sits
//            INSIDE every tested band, which is exactly why the notes'
//            simulation found clamping ineffective for parallax drift —
//            the band rows in the tool's table substantiate that.
//
// The rewrite is quantization-safe: toMotion/toRecord round-trip records
// bit-identically (lround on u8/255*255 returns the same u8; the milli-px
// round trip likewise), and the only field written back is logS (f32).
// test_scale_tool.cpp proves all three properties.
#pragma once

#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

#include <rawedit/ImageStab.h>

namespace rawedit {

enum class ScalePolicy : uint8_t {
    Free = 0,   // verbatim (bit-identical copy)
    Lock = 1,   // logS = 0 on measured pairs
    Clamp = 2,  // |logS| <= band on measured pairs
};

// The name shown in the scale-test table rows.
inline const char* scalePolicyName(ScalePolicy p, double band) {
    if (p == ScalePolicy::Free) return "free";
    if (p == ScalePolicy::Lock) return "lock";
    static thread_local char buf[32];
    std::snprintf(buf, sizeof buf, "clamp-%.3f", band);
    return buf;
}

// Rewrites `recs` under `policy`. Lost records (status 2) are copied as-is
// in every policy: they already carry identity motion and their (zero)
// logS is not a measurement. Measured records (status 0/1) keep every
// field except logS in Lock/Clamp. Free returns a bit-identical copy.
inline std::vector<PairRecord> applyScalePolicy(const std::vector<PairRecord>& recs,
                                                ScalePolicy policy, double band = 0.0) {
    std::vector<PairRecord> out;
    out.reserve(recs.size());
    for (const PairRecord& r : recs) {
        PairRecord q = r;  // verbatim start: Free is the untouched copy
        if (policy != ScalePolicy::Free && r.status != static_cast<uint8_t>(PairStatus::Lost)) {
            const double ls = r.logS;
            if (policy == ScalePolicy::Lock) {
                // Write through the same quantization the analyzer uses:
                // logS is an f32 in the record; setting 0.0 is exact.
                PairMotion m = toMotion(r);
                m.logS = 0.0;
                q = toRecord(r.ptsUs, m);
                // Quality fields round-trip bit-identically (proved in
                // test_scale_tool.cpp); tx/ty/theta are f32 copies.
                q.inlierRatio8 = r.inlierRatio8;
                q.coverage8 = r.coverage8;
                q.nInliers = r.nInliers;
                q.residualMilliPx = r.residualMilliPx;
                q.status = r.status;
            } else {  // Clamp
                const double lim = std::fabs(band);
                if (std::fabs(ls) > lim) {
                    PairMotion m = toMotion(r);
                    m.logS = (ls > 0 ? lim : -lim);
                    q = toRecord(r.ptsUs, m);
                    q.inlierRatio8 = r.inlierRatio8;
                    q.coverage8 = r.coverage8;
                    q.nInliers = r.nInliers;
                    q.residualMilliPx = r.residualMilliPx;
                    q.status = r.status;
                }
            }
        }
        out.push_back(q);
    }
    return out;
}

// The accumulated scale of a record chain: exp(sum of MEASURED logS) — the
// number the S11 notes call "the accumulated factor" (18007x as-is on the
// walk clip). Lost records contribute nothing (their stored logS is 0).
inline double accumulatedScale(const std::vector<PairRecord>& recs) {
    double s = 0.0;
    for (const PairRecord& r : recs) s += r.logS;
    return std::exp(s);
}

}  // namespace rawedit
