package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * settingsItemShape tests (v2 polish C1, owner spec 2026-11-15): the
 * group's corner pattern — 20dp outer corners, 4dp inner corners — is
 * the only thing that makes separate rows read as ONE rounded block;
 * the spec's workflow demands unit tests for the group shape by
 * index/count (only/first/mid/last).
 *
 * RoundedCornerShape implements equals over its four CornerSize fields
 * (CornerSize is a private data class per flavor), so shape equality is
 * structural — no reflection needed. Compose shape classes are pure
 * Kotlin and run in JVM unit tests (same pattern as RitimIconsTest's
 * addPathNodes usage).
 */
class SettingsGroupShapesTest {

    private val all20 = RoundedCornerShape(20.dp)
    private val firstShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = 4.dp,
        bottomStart = 4.dp,
    )
    private val midShape = RoundedCornerShape(4.dp)
    private val lastShape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 4.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )

    @Test
    fun singleItemGroupIsFullyRounded() {
        assertEquals(all20, settingsItemShape(0, 1))
    }

    @Test
    fun firstOfManyIsTopRounded() {
        assertEquals(firstShape, settingsItemShape(0, 2))
        assertEquals(firstShape, settingsItemShape(0, 3))
    }

    @Test
    fun middleItemsHaveOnlyInnerCorners() {
        assertEquals(midShape, settingsItemShape(1, 3))
        assertEquals(midShape, settingsItemShape(1, 4))
        assertEquals(midShape, settingsItemShape(2, 4))
    }

    @Test
    fun lastOfManyIsBottomRounded() {
        assertEquals(lastShape, settingsItemShape(1, 2))
        assertEquals(lastShape, settingsItemShape(2, 3))
        assertEquals(lastShape, settingsItemShape(3, 4))
    }

    @Test
    fun outOfRangeIndicesClampToGroupEdges() {
        // Defensive clamp: an index past the group's end is treated as
        // the last item (bottom-rounded), a negative one as the first.
        assertEquals(lastShape, settingsItemShape(5, 2))
        assertEquals(firstShape, settingsItemShape(-3, 3))
    }
}
