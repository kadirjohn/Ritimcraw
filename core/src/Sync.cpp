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

} // namespace rawedit
