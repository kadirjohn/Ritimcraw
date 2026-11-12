// rawedit::Sync — implementation (stabilization Phase 3). The model and
// its error budget are documented in Sync.h; nothing here interprets file
// formats (the anchored estimator takes already-read values, so the core
// stays free of GCSV/MCRAW I/O — the JNI layer feeds it from Gyro.h
// loaders in Phase 4).

#include <rawedit/Sync.h>

#include <cmath>

namespace rawedit {

SyncModel SyncModel::fromOffsetMs(double offsetMs) {
    SyncModel m;
    // ms -> ns with round-to-nearest (ties away from zero via std::round);
    // int64 range at double precision holds ±2^53 ns ≈ ±104 days of
    // offset, far beyond any real trim.
    m.offsetNs = static_cast<int64_t>(std::round(offsetMs * static_cast<double>(nsPerMs)));
    return m;
}

double SyncModel::offsetMs() const {
    return static_cast<double>(offsetNs) / static_cast<double>(nsPerMs);
}

// ±25 ms is a CONSERVATIVE SHIPPED BOUND, not a measurement (audit fix
// 2026-11-12). The measured parts of the budget are the two ms
// quantizations (header ±0.5 ms, recvd ±0.5 ms — Sync.h); the dominant
// term, the first-frame sensor→receive lag, is UNOBSERVABLE from the
// files, and the within-clip receive jitter measured on the fixtures
// (−13.2..+3.5 ms, −7.2..+7.7 ms) bounds only the JITTER, not the
// constant lag. 25 ms is an engineering judgment sized so that envelope
// fits with margin; a real bound needs optical-flow refinement (planned,
// GY-04) or an owner measurement.
static constexpr double kAnchorErrorBoundMs = 25.0;

SyncEstimate estimateOffsetFromAnchors(int64_t frame0WallMs,
                                       int64_t gyroStartWallMs,
                                       int64_t frame0CaptureNs) {
    SyncEstimate est;
    if (frame0WallMs <= 0 || gyroStartWallMs <= 0 || frame0CaptureNs < 0)
        return est; // no anchor on both sides -> ManualRequired, no fabrication
    est.model.offsetNs =
        (frame0WallMs - gyroStartWallMs) * SyncModel::nsPerMs - frame0CaptureNs;
    est.confidence = SyncConfidence::Anchored;
    est.source = "anchored:recvdMs-vs-gcsvHeader";
    est.errorBoundMs = kAnchorErrorBoundMs;
    return est;
}

SyncEstimate manualOffsetMs(double offsetMs) {
    SyncEstimate est;
    est.model = SyncModel::fromOffsetMs(offsetMs);
    est.confidence = SyncConfidence::ManualRequired;
    est.source = "manual";
    // A manual offset has no measured error bound; 0 means "unknown",
    // not "exact" (Sync.h).
    est.errorBoundMs = 0.0;
    return est;
}

SyncEstimate trimOffsetMs(const SyncEstimate& base, double trimMs) {
    SyncEstimate est = base;
    est.model = SyncModel::fromOffsetMs(base.model.offsetMs() + trimMs);
    est.source = base.source + "+trim";
    // Trimming adds the user's own uncertainty to whatever bound the base
    // estimate carried. A bound of 0 means UNKNOWN (Sync.h), not "exact"
    // — unknown + trim is still unknown, so it stays 0.
    est.errorBoundMs = base.errorBoundMs > 0.0
        ? base.errorBoundMs + std::fabs(trimMs)
        : 0.0;
    return est;
}

double FrameSync::insideFraction() const {
    if (frameCount == 0)
        return 0.0;
    const size_t inside = frameCount - beforeGyroStart - afterGyroEnd;
    return static_cast<double>(inside) / static_cast<double>(frameCount);
}

FrameSync framesToImu(const std::vector<int64_t>& frameCaptureNs,
                      const SyncModel& model,
                      int64_t gyroFirstTs, int64_t gyroLastTs) {
    FrameSync out;
    out.frameCount = frameCaptureNs.size();
    out.imuTimestampNs.reserve(frameCaptureNs.size());

    // Degenerate gyro span (empty log, or a corrupt inverted extent): no
    // frame can be inside. Still map the timestamps (the numbers are
    // well-defined); the caller sees firstInside == npos.
    const bool spanUsable = gyroLastTs >= gyroFirstTs;

    for (size_t i = 0; i < frameCaptureNs.size(); ++i) {
        const int64_t imuTs = model.toImu(frameCaptureNs[i]);
        out.imuTimestampNs.push_back(imuTs);
        if (!spanUsable) {
            // Count against the END that would clamp: everything before
            // gyroFirstTs (which is also >= gyroLastTs here) -> "before".
            ++out.beforeGyroStart;
            continue;
        }
        if (imuTs < gyroFirstTs) {
            ++out.beforeGyroStart;
        } else if (imuTs > gyroLastTs) {
            ++out.afterGyroEnd;
        } else if (out.firstInside == FrameSync::npos) {
            out.firstInside = i;
        }
        // lastInside updates while frames stay inside; sorted input makes
        // the inside set contiguous so the last assignment wins.
        if (imuTs >= gyroFirstTs && imuTs <= gyroLastTs)
            out.lastInside = i;
    }
    return out;
}

} // namespace rawedit
