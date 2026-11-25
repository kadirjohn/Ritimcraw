package com.ritim.ritimcraw.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A custom app theme as plain ARGB ints (0xAARRGGBB, always opaque) so the
 * whole table is pure Kotlin and JVM-testable. [ThemePalettes] defines the
 * list; the Compose conversion lives in ThemePaletteScheme.kt.
 *
 * Every Material3 role the app reads is present. Names are style labels
 * only ("Nord-style"); the colour values are original approximations.
 */
data class ThemePalette(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
) {
    /** Every colour role, for sanity tests (all must be opaque). */
    fun allColors(): List<Int> = listOf(
        primary, onPrimary, primaryContainer, onPrimaryContainer,
        secondary, onSecondary, secondaryContainer, onSecondaryContainer,
        tertiary, onTertiary, background, onBackground, surface, onSurface,
        surfaceVariant, onSurfaceVariant, surfaceContainerLowest,
        surfaceContainerLow, surfaceContainer, surfaceContainerHigh,
        surfaceContainerHighest, outline, outlineVariant, error, onError,
        errorContainer, onErrorContainer,
    )
}

/** WCAG colour math on opaque ARGB ints. */
object ColorMath {
    private fun channel(c: Int, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    fun luminance(c: Int): Double =
        0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)

    /** WCAG contrast ratio, 1 .. 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Linear blend of [from] toward [to] by [t] (0..1), opaque result. */
    fun blend(from: Int, to: Int, t: Double): Int {
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/**
 * The palette table. Each entry is built from a handful of anchor colours;
 * containers and surface steps are derived by [make] so the roles stay
 * consistent. Order is display order within each group.
 */
object ThemePalettes {
    /** The theme a user who never picked one gets when tapping "C". */
    const val DEFAULT_ID = "camera-dark"

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    @Suppress("LongParameterList")
    private fun make(
        id: String, name: String, dark: Boolean,
        bg: Long, surface: Long, onSurface: Long,
        variant: Long, onVariant: Long, outline: Long,
        primary: Long, onPrimary: Long, secondary: Long, tertiary: Long,
        error: Long,
    ): ThemePalette {
        val bgI = bg.toInt()
        val sI = surface.toInt()
        val onS = onSurface.toInt()
        val pI = primary.toInt()
        val secI = secondary.toInt()
        val errI = error.toInt()
        val cm = ColorMath
        val containerBase = if (dark) 0.30 else 0.20
        return ThemePalette(
            id = id, name = name, isDark = dark,
            primary = pI, onPrimary = onPrimary.toInt(),
            primaryContainer = cm.blend(sI, pI, containerBase),
            onPrimaryContainer = onS,
            secondary = secI,
            onSecondary = if (dark) bgI else WHITE,
            secondaryContainer = cm.blend(sI, secI, containerBase),
            onSecondaryContainer = onS,
            tertiary = tertiary.toInt(),
            onTertiary = if (dark) bgI else WHITE,
            background = bgI, onBackground = onS,
            surface = sI, onSurface = onS,
            surfaceVariant = variant.toInt(), onSurfaceVariant = onVariant.toInt(),
            surfaceContainerLowest = bgI,
            surfaceContainerLow = cm.blend(sI, onS, 0.03),
            surfaceContainer = cm.blend(sI, onS, 0.06),
            surfaceContainerHigh = cm.blend(sI, onS, 0.09),
            surfaceContainerHighest = cm.blend(sI, onS, 0.13),
            outline = outline.toInt(),
            outlineVariant = cm.blend(sI, onS, 0.22),
            error = errI,
            onError = if (dark) BLACK else WHITE,
            errorContainer = cm.blend(sI, errI, containerBase),
            onErrorContainer = onS,
        )
    }

    val all: List<ThemePalette> = listOf(
        // ---- Dark ----
        make("camera-dark", "Camera Yellow", true,
            0xFF0B0B0B, 0xFF151515, 0xFFF2F2F2, 0xFF2A2A2C, 0xFFB8B8BD, 0xFF77777C,
            0xFFFFCC0A, 0xFF1A1400, 0xFFE3BE45, 0xFFFF9F0A, 0xFFFF7A6E),
        make("amoled", "AMOLED Black", true,
            0xFF000000, 0xFF0A0A0A, 0xFFEDEDED, 0xFF1C1C1C, 0xFFA3A3A3, 0xFF6A6A6A,
            0xFF7CC4FF, 0xFF00172B, 0xFFB0BEC5, 0xFFFFB74D, 0xFFFF8A80),
        make("lightroom-dark", "Studio Gray", true,
            0xFF1B1B1B, 0xFF262626, 0xFFE2E2E2, 0xFF353535, 0xFFAAAAAA, 0xFF707070,
            0xFF7DB4F5, 0xFF0F1A26, 0xFFB4B4B4, 0xFFD9A441, 0xFFFF8C82),
        make("mocha", "Mocha", true,
            0xFF1E1E2E, 0xFF24243A, 0xFFCDD6F4, 0xFF313244, 0xFFA6ADC8, 0xFF7C819B,
            0xFFCBA6F7, 0xFF1E1E2E, 0xFF89B4FA, 0xFFF5C2E7, 0xFFF38BA8),
        make("nord-style", "Nord-style", true,
            0xFF2E3440, 0xFF353C4A, 0xFFECEFF4, 0xFF434C5E, 0xFFD8DEE9, 0xFF7B88A1,
            0xFF88C0D0, 0xFF2E3440, 0xFF81A1C1, 0xFFA3BE8C, 0xFFE58A94),
        make("dracula-style", "Dracula-style", true,
            0xFF282A36, 0xFF2F3142, 0xFFF8F8F2, 0xFF44475A, 0xFFC3C6DA, 0xFF7C86B8,
            0xFFBD93F9, 0xFF282A36, 0xFFFF79C6, 0xFF50FA7B, 0xFFFF6E6E),
        make("solarized-dark", "Solarized Dark-style", true,
            0xFF002B36, 0xFF073642, 0xFFB5C2C2, 0xFF0F4452, 0xFF93A1A1, 0xFF5F8087,
            0xFF2AA198, 0xFF00252E, 0xFF4BA3DC, 0xFFD0A21A, 0xFFF0706D),
        make("gruvbox-dark", "Gruvbox Dark-style", true,
            0xFF282828, 0xFF32302F, 0xFFEBDBB2, 0xFF3C3836, 0xFFBDAE93, 0xFF8A7B6E,
            0xFFFABD2F, 0xFF282828, 0xFF8EC07C, 0xFFD3869B, 0xFFFB5C48),
        make("tokyo-night", "Tokyo Night-style", true,
            0xFF1A1B26, 0xFF24283B, 0xFFC0CAF5, 0xFF2F3549, 0xFF9AA5CE, 0xFF6E78A3,
            0xFF7AA2F7, 0xFF1A1B26, 0xFFBB9AF7, 0xFF9ECE6A, 0xFFF7768E),
        make("one-dark", "One Dark-style", true,
            0xFF21252B, 0xFF282C34, 0xFFABB2BF, 0xFF333842, 0xFF9DA5B4, 0xFF6F7886,
            0xFF61AFEF, 0xFF11202E, 0xFFC678DD, 0xFF98C379, 0xFFE06C75),
        make("midnight", "Midnight Blue", true,
            0xFF0B1220, 0xFF111A2E, 0xFFE6ECF8, 0xFF1C2742, 0xFFA9B6D0, 0xFF6E7FA3,
            0xFF5AA9FF, 0xFF001A33, 0xFF7FD6C2, 0xFFFFC27A, 0xFFFF8B8B),
        make("forest", "Forest", true,
            0xFF0E1712, 0xFF15221A, 0xFFE3EDE6, 0xFF213328, 0xFFA8BDB0, 0xFF6F8A78,
            0xFF7FD19A, 0xFF05210F, 0xFFC5D88A, 0xFFE8B572, 0xFFFF8E86),
        // ---- Light ----
        make("camera-light", "Camera Orange", false,
            0xFFFFFBF4, 0xFFFFFFFF, 0xFF1D1B16, 0xFFF3EADB, 0xFF5B5445, 0xFF857B68,
            0xFFB04A00, 0xFFFFFFFF, 0xFF8A5A00, 0xFF9C3F00, 0xFFB3261E),
        make("latte", "Latte", false,
            0xFFEFF1F5, 0xFFFFFFFF, 0xFF4C4F69, 0xFFDCE0E8, 0xFF5C5F77, 0xFF7C7F93,
            0xFF8839EF, 0xFFFFFFFF, 0xFF1E66F5, 0xFF9C4A00, 0xFFD20F39),
        make("paper", "Paper", false,
            0xFFF6F6F4, 0xFFFFFFFF, 0xFF1F2328, 0xFFE9E9E6, 0xFF545A62, 0xFF7F858D,
            0xFF1F5FBF, 0xFFFFFFFF, 0xFF4A5565, 0xFF8A4B00, 0xFFB3261E),
        make("solarized-light", "Solarized Light-style", false,
            0xFFFDF6E3, 0xFFFFFBEF, 0xFF1E3A42, 0xFFEEE8D5, 0xFF4F6468, 0xFF7E8F8F,
            0xFF1F6F8B, 0xFFFFFFFF, 0xFF157A6E, 0xFF8A6500, 0xFFB3261E),
    )

    fun byId(id: String?): ThemePalette? = all.firstOrNull { it.id == id }

    /** Unknown or null ids resolve to the default theme. */
    fun resolve(id: String?): ThemePalette = byId(id) ?: all.first { it.id == DEFAULT_ID }

    val dark: List<ThemePalette> get() = all.filter { it.isDark }
    val light: List<ThemePalette> get() = all.filter { !it.isDark }
}
