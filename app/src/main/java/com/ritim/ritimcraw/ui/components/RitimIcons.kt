package com.ritim.ritimcraw.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Settings redesign's icon set (owner spec 2026-11-14, verbatim SVG
 * path data — every path here is the spec's own, not a Material glyph).
 *
 * All icons are 24x24 viewBox, STROKE based: strokeWidth 1.75, round
 * line cap and join, no fill — except the filled theme glyphs (the
 * [Contrast] disc's half and the settings v2 [AutoThemeA] letter,
 * which is a filled even-odd vector, not a font). Icons are built as
 * ImageVectors (the Destination
 * enum's existing pattern: core Material glyphs are ImageVectors, so
 * the custom paths materialize the same way) and tinted by the
 * component's color role at the call site — the vectors carry no color
 * of their own (black stroke/fill, tinted by Icon's tint parameter).
 *
 * materialPath() is NOT used: it hardcodes fill = Black and stroke =
 * null, the exact opposite of this set's stroke-based contract. The
 * paths go through the plain Builder API with explicit stroke
 * parameters (or fill for the two filled halves).
 */
private const val STROKE_WIDTH = 1.75f

/** One stroke-based 24x24 vector from the spec's SVG path strings. */
private fun strokeIcon(
    name: String,
    paths: List<String>,
    extra: (ImageVector.Builder.() -> Unit) = {},
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    paths.forEach { pathData ->
        addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    extra()
}.build()

/** One filled 24x24 vector (the spec's half-filled theme glyphs). */
private fun filledIcon(
    name: String,
    strokePaths: List<String> = emptyList(),
    fillPaths: List<String> = emptyList(),
    extra: (ImageVector.Builder.() -> Unit) = {},
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    strokePaths.forEach { pathData ->
        addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    fillPaths.forEach { pathData ->
        addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        )
    }
    extra()
}.build()

/** ellipse as SVG path data: an arc pair inside a move-to box. */
private fun circlePath(cx: Float, cy: Float, r: Float): String {
    // An SVG circle = two half arcs with the same round join; rendered
    // with the same stroke parameters as every other path here.
    return "M ${cx - r} $cy a $r $r 0 1 0 ${2 * r} 0 a $r $r 0 1 0 ${-2 * r} 0"
}

/** rect as SVG path data (rounded corners via arc sweeps). */
private fun rectPath(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    val right = x + w - rx
    val bottom = y + h - rx
    return "M ${x + rx} $y H $right A $rx $rx 0 0 1 ${x + w} ${y + rx} V $bottom " +
        "A $rx $rx 0 0 1 $right ${y + h} H ${x + rx} A $rx $rx 0 0 1 $x $bottom " +
        "V ${y + rx} A $rx $rx 0 0 1 ${x + rx} $y Z"
}

/**
 * eye (Preview quality): the spec's eye outline plus a 3-radius pupil
 * circle.
 */
val Eye: ImageVector = strokeIcon(
    name = "Eye",
    paths = listOf(
        "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z",
        circlePath(cx = 12f, cy = 12f, r = 3f),
    ),
)

/** cpu (Image processing, Device report summary). */
val Cpu: ImageVector = strokeIcon(
    name = "Cpu",
    paths = listOf(
        rectPath(x = 7f, y = 7f, w = 10f, h = 10f, rx = 1.5f),
        "M9 3v4M15 3v4M9 17v4M15 17v4M3 9h4M3 15h4M17 9h4M17 15h4",
    ),
)

/** folder (Gyro log folder). */
val Folder: ImageVector = strokeIcon(
    name = "Folder",
    paths = listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"),
)

/** report (Device report). */
val Report: ImageVector = strokeIcon(
    name = "Report",
    paths = listOf(
        rectPath(x = 4f, y = 3f, w = 16f, h = 18f, rx = 3f),
        "M8 8h8M8 12h8M8 16h5",
    ),
)

/** chevron_right (navigation rows). */
val ChevronRight: ImageVector = strokeIcon(
    name = "ChevronRight",
    paths = listOf("M9 6l6 6-6 6"),
)

/** arrow_back (Device report top bar). */
val ArrowBack: ImageVector = strokeIcon(
    name = "ArrowBack",
    paths = listOf("M19 12H5M11 6l-6 6 6 6"),
)

/** copy (Device report top bar). */
val Copy: ImageVector = strokeIcon(
    name = "Copy",
    paths = listOf(
        rectPath(x = 9f, y = 9f, w = 11f, h = 11f, rx = 2.5f),
        "M5 15V6a2 2 0 0 1 2-2h9",
    ),
)

/** info (expandable rows' info toggle). */
val Info: ImageVector = strokeIcon(
    name = "Info",
    paths = listOf(
        circlePath(cx = 12f, cy = 12f, r = 9f),
        "M12 11v5",
        // The spec's dot: "M12 8h.01" — a zero-length segment that the
        // round line cap renders as the dot. addPathNodes parses it as a
        // horizontal line to x=12.01, and the round cap closes it.
        "M12 8h.01",
    ),
)

/** gauge (Measure button). */
val Gauge: ImageVector = strokeIcon(
    name = "Gauge",
    paths = listOf(
        "M3.5 17a8.5 8.5 0 1 1 17 0",
        "M12 17l4-5",
        "M12 17h.01",
    ),
)

/** player bar (Auto hide media player bar): a frame with a play-and-seek strip at the bottom. */
val PlayerBar: ImageVector = strokeIcon(
    name = "PlayerBar",
    paths = listOf(
        "M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M3 15h18",
        "M7 17.5h.01",
        "M10 17.5h8",
    ),
)

/** activity (Preview stats readback). */
val Activity: ImageVector = strokeIcon(
    name = "Activity",
    paths = listOf("M3 12h4l3-7 4 14 3-7h4"),
)

/**
 * contrast (Theme row tile): the disc is stroked; the LEFT half is
 * FILLED with the same tint (spec: path "M12 3a9 9 0 0 1 0 18z" filled).
 */
val Contrast: ImageVector = filledIcon(
    name = "Contrast",
    strokePaths = listOf(circlePath(cx = 12f, cy = 12f, r = 9f)),
    fillPaths = listOf("M12 3a9 9 0 0 1 0 18z"),
)

/**
 * sun (Theme slot 0, Light): the disc plus eight rays (the spec's
 * full-ray set).
 */
val Sun: ImageVector = strokeIcon(
    name = "Sun",
    paths = listOf(
        circlePath(cx = 12f, cy = 12f, r = 4f),
        "M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8",
    ),
)

/**
 * autoThemeA (Theme slot 1, System; settings v2 polish C4, 2026-11-15):
 * a capital "A" as a FILLED vector — not a Text, not a font. The outline
 * comes from Inter Medium (SIL Open Font License), scaled to a 14.4 unit
 * cap height on the 24 unit grid, baseline y = 19.2; the counter (the
 * triangle hole between the crossbar and the apex) is the second
 * subpath, so the EVEN-ODD fill rule cuts it out of the filled letter.
 * No proprietary platform font is traced here — the provenance is the
 * OFL-licensed Inter outline only.
 */
val AutoThemeA: ImageVector = ImageVector.Builder(
    name = "AutoThemeA",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = addPathNodes(
            "M5.48 19.2H7.86L9.15 15.4H14.81L16.14 19.2H18.52L13.3 4.8H10.6Z" +
                "M9.78 13.57L10.58 11.19C10.94 10.09 11.38 8.59 11.94 6.53" +
                "C12.51 8.57 12.95 10.04 13.33 11.19L14.16 13.57Z",
        ),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    )
}.build()

/** moon (Theme slot 2, Dark). */
val Moon: ImageVector = strokeIcon(
    name = "Moon",
    paths = listOf("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"),
)

// ---- Bottom navigation icons (the spec's paths replace the current
// glyphs; labels and routes stay, and the outlined/filled enum shape
// stays — the same vector serves both states, NavigationBar's own
// selected/unselected tints do the differentiation, exactly as the
// gear pair did before). ----

/** Library (bottom nav): the play triangle. */
val NavLibrary: ImageVector = strokeIcon(
    name = "NavLibrary",
    paths = listOf("M7 5l12 7-12 7z"),
)

/** Projects (bottom nav): the list lines with dot bullets. */
val NavProjects: ImageVector = strokeIcon(
    name = "NavProjects",
    paths = listOf(
        "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
    ),
)

/** Exports (bottom nav): the door with the arrow out. */
val NavExports: ImageVector = strokeIcon(
    name = "NavExports",
    paths = listOf(
        "M15 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3",
    ),
)

/** Settings (bottom nav): the sliders. */
val NavSettings: ImageVector = strokeIcon(
    name = "NavSettings",
    paths = listOf(
        "M3 6h10M18 6h3M3 12h4M12 12h9M3 18h11M19.5 18H21",
        circlePath(cx = 15.5f, cy = 6f, r = 2.5f),
        circlePath(cx = 9.5f, cy = 12f, r = 2.5f),
        circlePath(cx = 17f, cy = 18f, r = 2.5f),
    ),
)

// ---- Library toolbar icons (library toolbar redesign, 2026-11-16):
// the sort button, the view-mode toggle, the import "+" and the filter
// chip's expand/check pair — the spec's own stroke paths, same contract
// as the set above (24x24, strokeWidth 1.75, round cap/join, no fill;
// tinted by the call site's color role). ----

/** sort (Library toolbar sort button). */
val Sort: ImageVector = strokeIcon(
    name = "Sort",
    paths = listOf("M3 6h18M3 12h12M3 18h6"),
)

/** grid_view (view toggle in list mode: switch to grid). */
val GridView: ImageVector = strokeIcon(
    name = "GridView",
    paths = listOf(
        rectPath(x = 4f, y = 4f, w = 6f, h = 6f, rx = 1.5f),
        rectPath(x = 14f, y = 4f, w = 6f, h = 6f, rx = 1.5f),
        rectPath(x = 4f, y = 14f, w = 6f, h = 6f, rx = 1.5f),
        rectPath(x = 14f, y = 14f, w = 6f, h = 6f, rx = 1.5f),
    ),
)

/** view_list (view toggle in grid mode: switch to list). */
val ViewList: ImageVector = strokeIcon(
    name = "ViewList",
    paths = listOf("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"),
)

/** add (Library toolbar import button). */
val Add: ImageVector = strokeIcon(
    name = "Add",
    paths = listOf("M12 5v14M5 12h14"),
)

/** expand_more (filter chip's trailing arrow). */
val ExpandMore: ImageVector = strokeIcon(
    name = "ExpandMore",
    paths = listOf("M6 9l6 6 6-6"),
)

/** check (selected filter/sort menu item's leading mark). */
val Check: ImageVector = strokeIcon(
    name = "Check",
    paths = listOf("M5 12.5l4.5 4.5L19 7.5"),
)
