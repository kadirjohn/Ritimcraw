package com.ritim.ritimcraw.util

/**
 * Compile-time feature switches. Flags gate behavior at the point of use;
 * they never rewrite stored data (GradeK, ExportStore keep whatever the
 * user saved, so flipping a flag back restores the old behavior as-is).
 */
object FeatureFlags {

    /**
     * Gyro stabilization (owner decision 2026-11-23): paused until the
     * MotionCam update that makes gyro-backed MCRAW stabilization easier.
     * The code stays (Stabilization.cpp, Sync.cpp, OpticalSync.cpp, the
     * Stabilize tab); with the flag false every preview and export path
     * renders unstabilized, no gyro log is matched or probed on import,
     * and the Stabilize tab shows the "Gyro mode will come with the
     * MotionCam update" notice instead of the controls. The image-based
     * mode (plan section 5b) is a separate, later package.
     */
    const val GYRO_STABILIZATION_ENABLED = false
}
