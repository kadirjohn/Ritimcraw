package com.ritim.ritimcraw.ui.theme

import com.ritim.ritimcraw.data.ThemeMode

/** A resolved theme selection: the mode plus the last custom theme id. */
data class ThemeChoice(val mode: ThemeMode, val customThemeId: String)

/**
 * Pure selection logic of the Settings theme control (JVM-tested).
 *
 * The control's slots, left to right: C (custom), Light, System, Dark.
 * A tap on a slot and a drag that ends over a slot go through the same
 * [choose]; the C slot always activates the LAST chosen custom theme.
 */
object ThemeSelection {
    /** Visual slot order of the control. */
    val slots: List<ThemeMode> =
        listOf(ThemeMode.CUSTOM, ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.DARK)

    const val CUSTOM_SLOT = 0

    fun slotOf(mode: ThemeMode): Int = slots.indexOf(mode).coerceAtLeast(0)

    /** The last custom theme: the stored id if it still exists, else the default. */
    fun lastCustomId(stored: String?): String = ThemePalettes.resolve(stored).id

    /** Tapping or dragging onto [slot]; C activates the last custom theme. */
    fun choose(slot: Int, storedCustomId: String?): ThemeChoice =
        ThemeChoice(slots[slot.coerceIn(0, slots.lastIndex)], lastCustomId(storedCustomId))

    /** Picking [paletteId] in the picker: custom mode, remembered as last custom. */
    fun pickCustom(paletteId: String): ThemeChoice =
        ThemeChoice(ThemeMode.CUSTOM, lastCustomId(paletteId))

    /** The palette in effect, or null for System/Light/Dark. */
    fun activePalette(mode: ThemeMode, storedCustomId: String?): ThemePalette? =
        if (mode == ThemeMode.CUSTOM) ThemePalettes.resolve(storedCustomId) else null

    /** Dark/light of the whole app: the palette's own for CUSTOM, else the mode's. */
    fun isDark(mode: ThemeMode, storedCustomId: String?, systemInDarkTheme: Boolean): Boolean =
        activePalette(mode, storedCustomId)?.isDark ?: mode.isDark(systemInDarkTheme)
}
