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

    /**
     * Background proxy generation (G3, docs/PROXY_PLAN.md): the P1 cache and
     * the E2 pure layers are always compiled in; this flag gates the WIRED
     * leg — the runtime's drive loop, the auto-enqueue pass and the storage
     * page's actions. Ships DARK for the first device leg (the owner's
     * morning run must confirm the real MediaCodec encode, the thermal
     * behaviour and the playback switch before the queue ever runs by
     * itself); the flag turns true after that acceptance, the owner's call.
     * While false: the runtime is constructed (its sweep only deletes crash
     * leftovers) but never started, no clip is ever auto-enqueued, and the
     * storage page renders read-only (no delete/cancel actions).
     */
    const val PROXY_PRODUCTION_DEFAULT = false

    /**
     * The proxy flag as the app reads it: the compiled default, or, in DEBUG
     * builds only, `adb shell setprop debug.ritim.proxy 1` for a device test
     * (read once per process; staging/release never read the property, the
     * same rule as the native debug.ritim.* switches). One flag governs the
     * runtime start, the auto-enqueue AND playback use of proxies: off means
     * already-generated proxies are ignored too.
     */
    val proxyEnabled: Boolean by lazy {
        resolveProxyEnabled(
            PROXY_PRODUCTION_DEFAULT,
            com.ritim.ritimcraw.BuildConfig.DEBUG,
        ) { readSystemProperty("debug.ritim.proxy") }
    }

    /** Pure resolution (JVM-testable): the property can only ever TURN ON, and only in debug. */
    fun resolveProxyEnabled(default: Boolean, isDebug: Boolean, prop: () -> String?): Boolean =
        default || (isDebug && prop() == "1")

    private fun readSystemProperty(name: String): String? = runCatching {
        // Debug-only path (isDebug gate above); loadClass, not Class.forName, so no R8 keep rule is needed:
        // release/staging never reach this line.
        ClassLoader.getSystemClassLoader().loadClass("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as? String
    }.getOrNull()
}
