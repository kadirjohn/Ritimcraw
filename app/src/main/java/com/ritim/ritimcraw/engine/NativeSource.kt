package com.ritim.ritimcraw.engine

import android.graphics.Rect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Kotlin view of rawedit::FrameInfo (sprint plan Task 3).
 * cfa: 0=RGGB 1=GRBG 2=GBRG 3=BGGR (rawedit::CfaPattern order).
 */
data class FrameInfoK(
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val cfa: Int,
    val active: Rect,
    val orientationDeg: Int,
)

/**
 * Result of a native call. `ok` mirrors rawedit::Result: value is meaningful
 * only when code == 0 (ErrorCode::Ok).
 *
 * codes (rawedit::ErrorCode): 0 ok, 1 truncated, 2 badHeader, 3 ioError,
 * 4 unsupported.
 */
data class NativeResult<T>(
    val code: Int,
    val detail: String,
    val value: T?,
) {
    val ok: Boolean get() = code == 0
}

/**
 * Handle-based wrapper over the JNI bridge to librawedit_jni.so.
 *
 * One NativeSource owns one native rawedit::Source, and one motioncam
 * decoder instance is NOT safe for concurrent use (upstream README) — so
 * every native call is dispatched on a single dedicated engine thread
 * ([engine]), and each call runs to completion before the next starts.
 * Callers get results through the [ExecutorService] they pass in (or use
 * the convenience synchronous methods from that thread).
 *
 * The library may be absent (no NDK build yet); [nativeAvailable] reports
 * that and every method fails cleanly with code 3 (ioError) instead of
 * crashing, so the app shell runs without the .so.
 */
class NativeSource private constructor(
    private val handle: Long,
    private val engine: ExecutorService,
) : AutoCloseable {

    val frameCount: Int
        get() = requireOpen { nativeFrameCount(handle) }

    fun frameInfo(i: Int): FrameInfoK =
        requireOpen { nativeFrameInfo(handle, i) ?: error("frameInfo returned null") }

    fun readFrame(i: Int): ShortArray =
        requireOpen { nativeReadFrame(handle, i) ?: error("readFrame returned null") }

    fun readAudio(): ShortArray =
        requireOpen { nativeReadAudio(handle) ?: error("readAudio returned null") }

    override fun close() {
        if (!closed) {
            closed = true
            engine.execute { nativeCloseSource(handle) }
            engine.shutdown()
        }
    }

    @Volatile
    private var closed = false

    private fun <T> requireOpen(block: () -> T): T {
        check(!closed) { "NativeSource is closed" }
        if (!nativeAvailable) {
            throw IllegalStateException(nativeUnavailableMessage)
        }
        return block()
    }

    companion object {
        /**
         * True when librawedit_jni.so loaded. While false (no NDK build in
         * this environment), every operation throws IllegalStateException
         * with a recovery message instead of UnsatisfiedLinkError.
         */
        @Volatile
        var nativeAvailable: Boolean = false
            private set

        val nativeUnavailableMessage: String =
            "The native engine is not available in this build (librawedit_jni.so " +
                "not loaded). Recovery: install a build produced with the NDK."

        init {
            try {
                System.loadLibrary("rawedit_jni")
                nativeAvailable = true
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
            } catch (e: SecurityException) {
                nativeAvailable = false
            }
        }

        /**
         * Opens a clip from a POSIX fd (from SAF). The bridge dups the fd.
         * Returns a failed NativeResult (not an exception) when the file is
         * not a valid MCRAW, with stage + cause + recovery in `detail`.
         */
        fun open(fd: Int): NativeResult<NativeSource> {
            if (!nativeAvailable) {
                return NativeResult(3, nativeUnavailableMessage, null)
            }
            val engine = Executors.newSingleThreadExecutor { r ->
                Thread(r, "rawedit-engine").apply { isDaemon = true }
            }
            // The JNI open itself is fast (reads headers/index); run it on
            // the engine thread too so the decoder is constructed where it
            // is used.
            val task = engine.submit<Pair<Int, String?>> {
                val h = nativeOpenSource(fd)
                if (h == 0L) {
                    Pair(0, null)
                } else {
                    Pair(h.toInt(), null)
                }
            }
            return try {
                val (h, _) = task.get()
                if (h == 0) {
                    engine.shutdown()
                    // Fetch the last error detail from the bridge.
                    NativeResult(2, nativeLastError() ?: "open failed", null)
                } else {
                    NativeResult(0, "", NativeSource(h.toLong(), engine))
                }
            } catch (e: RejectedExecutionException) {
                NativeResult(3, "engine shut down", null)
            } catch (e: java.util.concurrent.ExecutionException) {
                engine.shutdown()
                NativeResult(3, "open: ${e.cause?.message ?: e.message}", null)
            }
        }

        // ---- JNI (implemented in app/src/main/cpp/jni_bridge.cpp) ----

        @JvmStatic private external fun nativeOpenSource(fd: Int): Long
        @JvmStatic private external fun nativeFrameCount(handle: Long): Int
        @JvmStatic private external fun nativeFrameInfo(handle: Long, i: Int): FrameInfoK?
        @JvmStatic private external fun nativeReadFrame(handle: Long, i: Int): ShortArray?
        @JvmStatic private external fun nativeReadAudio(handle: Long): ShortArray?
        @JvmStatic private external fun nativeCloseSource(handle: Long): Long
        @JvmStatic private external fun nativeLastError(): String?
    }
}
