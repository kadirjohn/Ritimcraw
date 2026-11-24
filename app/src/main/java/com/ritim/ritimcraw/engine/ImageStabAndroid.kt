package com.ritim.ritimcraw.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import java.io.File

/**
 * Process-wide "what the app is doing" flags the analysis service stands
 * down for. [playing] is set by the editor while its playback clock runs;
 * the export side is read from the export store (no edit to the exporter).
 */
object ImageStabActivity {
    @Volatile var playing: Boolean = false
}

/**
 * The Android-side [ImageStabSignals]: playback from [ImageStabActivity],
 * export from [exportingProbe], thermal headroom from
 * [PowerManager.getThermalHeadroom] (10 s forecast; NaN = the device does
 * not report one = no thermal objection).
 */
class AndroidImageStabSignals(
    context: Context,
    private val exportingProbe: () -> Boolean,
) : ImageStabSignals {
    private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun exporting(): Boolean = exportingProbe()
    override fun playing(): Boolean = ImageStabActivity.playing
    override fun thermalHeadroom(): Float? = try {
        power?.getThermalHeadroom(THERMAL_FORECAST_SECONDS)?.takeUnless { it.isNaN() }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val THERMAL_FORECAST_SECONDS = 10
    }
}

object ImageStabAndroid {
    /** `filesDir/cache/imagestab/` (plan section 3.6; NOT cacheDir, the OS may clear it). */
    fun cacheDir(filesDir: File): File = File(File(filesDir, "cache"), "imagestab")

    /** A descriptor source on the clip behind [uri] (the native analysis dup()s it). */
    fun fdSource(context: Context, uri: String): ImageStabFdSource = ImageStabFdSource(
        open = {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.detachFd() ?: -1
            } catch (e: Exception) {
                -1
            }
        },
        close = { fd ->
            try {
                ParcelFileDescriptor.adoptFd(fd).close()
            } catch (e: Exception) {
                // already closed
            }
        },
    )
}
