package com.ritim.ritimcraw.export

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The single shared engine thread for all native export work (DNG + MP4).
 *
 * One motioncam decoder is not concurrency-safe and the JNI bridge's error
 * state is thread-local, so every native export call runs here, one at a
 * time — no matter how many exporters exist or who calls them. Daemon so it
 * never keeps the process alive. The queue runner (one job at a time) sits
 * on top of this; this thread is the hard guarantee beneath it.
 */
internal object ExportEngine {
    val thread: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "rawedit-export").apply { isDaemon = true }
        }
    }
}
