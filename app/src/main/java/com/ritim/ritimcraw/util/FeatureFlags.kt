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

    /**
     * Image-based (gyro-free) stabilization, docs/IMAGE_STAB_PLAN.md. The
     * engine, analysis service and resolver ship dark: the flag turns true
     * only after the S5 UI and the S12 on-device acceptance (owner decision
     * 2026-11-23, plan section 6 item 10). While false, `engineFor` never
     * returns IMAGE, so every preview/export path is byte-for-byte the
     * pre-S4 behavior; a stored `stabilizationEngine = 1` is kept untouched.
     */
    const val IMAGE_STABILIZATION_ENABLED = false
}
