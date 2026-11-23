package com.ritim.ritimcraw.export

import android.content.Context
import com.ritim.ritimcraw.data.JsonParser

/**
 * "Remember the last settings" (E1c): the sheet reopens with what the user
 * exported with last. Stored as the same versioned wire string the job
 * carries ([ExportSettingsWire]) in a small app-private SharedPreferences
 * file - a per-viewer convenience, never the job's truth (the job snapshots
 * its own settings at queue time). An unreadable value (newer version,
 * corrupt) falls back to the default instead of guessing.
 */
object ExportSettingsPrefs {
    private const val FILE = "export_prefs"
    private const val KEY = "lastMp4Settings"

    fun encode(s: ExportSettings): String = ExportSettingsWire.render(s)

    fun decode(text: String?): ExportSettings? {
        if (text.isNullOrBlank()) return null
        return try {
            (ExportSettingsWire.parse(JsonParser(text).parseValue()) as? ExportSettingsWire.Parsed.Ok)?.settings
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun load(context: Context): ExportSettings =
        runCatching { decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)) }
            .getOrNull() ?: ExportSheetModel.defaultSettings()

    fun save(context: Context, s: ExportSettings) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, encode(s)).apply()
        }
    }
}
