package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.LibrarySortKey
import com.ritim.ritimcraw.data.MediaSource

/**
 * The Library toolbar's ordering rule (library toolbar redesign,
 * 2026-11-16), pure and JVM-testable — the composable stays a thin shell.
 *
 * The three menu fields map to the record's own values:
 *  - NAME -> [MediaSource.displayOrId] (the displayName, or the id when
 *    the record never got a real name), case-insensitive — a clip's name
 *    is user-facing text, and "b.mp4" belongs between "A.mp4" and
 *    "C.mp4", not after both.
 *  - DURATION -> [MediaSource.durationUs] (0 while unprobed — an unprobed
 *    clip sorts as 0, the honest "unknown" value, never faked).
 *  - RESOLUTION -> width*height in pixels (the megapixel ordering the
 *    row's "3840×2160" line implies; 0x0 unprobed records sort as 0).
 *
 * Direction: [ascending] false reverses the CHOSEN FIELD's comparison
 * only — the spec's tiebreak ("ties broken by Name ascending") stays
 * ascending in BOTH directions, which keeps the order DETERMINISTIC and
 * human-checkable: two clips with the same duration are always in name
 * order, never in whatever order a stable sort happened to leave them.
 * For NAME itself the tiebreak is the same value (a name tie falls
 * through to the stable import order).
 *
 * The DEFAULT (key = null) is TODAY'S ORDER: the catalog's own order —
 * the "sources" array's order, i.e. import order — which is what the
 * list rendered before the toolbar existed. The function returns the
 * list as-is then (no copy), so the never-touched state is literally
 * today's behavior.
 */
object LibrarySort {

    /**
     * The visible list's order for the given key + direction. Pure: no
     * mutation, no state — the screen calls it on every composition with
     * the current [sources] and the toolbar's persisted pair.
     *
     * [key] null = today's order (import order) — returned unchanged.
     */
    fun apply(
        sources: List<MediaSource>,
        key: LibrarySortKey?,
        ascending: Boolean,
    ): List<MediaSource> {
        if (key == null) return sources
        val field = when (key) {
            LibrarySortKey.NAME -> compareBy<MediaSource> { nameOf(it) }
            LibrarySortKey.DURATION -> compareBy { it.durationUs }
            LibrarySortKey.RESOLUTION -> compareBy { it.width.toLong() * it.height }
        }
        val directed = if (ascending) field else field.reversed()
        return sources.sortedWith(
            directed.thenComparator { a, b -> nameOf(a).compareTo(nameOf(b)) }
        )
    }

    /** The tiebreak's (and NAME's) value: displayOrId, case-folded — one
     *  definition so the name ordering is identical in both places. */
    private fun nameOf(source: MediaSource): String = source.displayOrId.lowercase()
}
