package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN-13 Stage A tests: the honest status model. The two hard rules are
 * enforced at construction — an estimate without a basis is a guess
 * (rejected), and a KNOWN/ESTIMATED field never carries a null value.
 */
class MetadataRecordTest {

    private fun allUnknownRecord(): MetadataRecord = MetadataRecord(
        sourceId = "src-x",
        kind = SourceKind.MCRAW,
        durationUs = Field.unknown(),
        frameCount = Field.unknown(),
        fpsNominal = Field.unknown(),
        fpsMeasured = Field.unknown(),
        camera = Field.unknown(),
        lens = Field.unknown(),
        resolution = Field.unknown(),
        bitDepth = Field.unknown(),
        primaries = Field.unknown(),
        transfer = Field.unknown(),
        matrix = Field.unknown(),
        range = Field.unknown(),
        audio = Field.unknown(),
        fileSize = Field.unknown(),
        fileIdentity = Field.unknown(),
    )

    @Test
    fun known_field_carries_value_and_status() {
        val f = Field.known(3840)
        assertEquals(3840, f.value)
        assertEquals(FieldStatus.KNOWN, f.status)
        assertNull(f.basis)
    }

    @Test
    fun unknown_field_is_null() {
        val f: Field<Int> = Field.unknown()
        assertNull(f.value)
        assertEquals(FieldStatus.UNKNOWN, f.status)
    }

    @Test
    fun estimated_field_requires_a_basis() {
        val f = Field.estimated(623, "duration x nominal fps")
        assertEquals(623, f.value)
        assertEquals(FieldStatus.ESTIMATED, f.status)
        assertEquals("duration x nominal fps", f.basis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimated_without_basis_is_rejected() {
        Field.estimated(623, "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimated_with_null_basis_is_rejected() {
        // The factory takes a non-null basis; the raw constructor is the
        // path a caller could misuse with null.
        Field<Int>(623, FieldStatus.ESTIMATED, basis = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun known_with_null_value_is_rejected() {
        Field<Int>(null, FieldStatus.KNOWN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimated_with_null_value_is_rejected() {
        Field<Int>(null, FieldStatus.ESTIMATED, "basis")
    }

    @Test
    fun record_is_empty_when_every_field_is_unknown() {
        val r = allUnknownRecord()
        assertTrue(r.isEmpty)
        assertFalse(r.hasKnownFields)
    }

    @Test
    fun record_not_empty_when_any_field_is_known() {
        val r = allUnknownRecord().copy(fileIdentity = Field.known("abc123"))
        assertFalse(r.isEmpty)
        assertTrue(r.hasKnownFields)
    }

    @Test
    fun record_not_empty_when_any_field_is_estimated() {
        val r = allUnknownRecord().copy(
            fpsMeasured = Field.estimated(59.94, "span of real pts"),
        )
        assertFalse(r.isEmpty)
    }

    @Test
    fun estimated_frame_count_flag_pairs_with_estimated_status() {
        val r = allUnknownRecord().copy(
            frameCount = Field.estimated(623, "duration x fps"),
            frameCountEstimated = true,
        )
        assertEquals(FieldStatus.ESTIMATED, r.frameCount.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimated_flag_on_known_count_is_rejected() {
        allUnknownRecord().copy(
            frameCount = Field.known(623),
            frameCountEstimated = true,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun estimated_flag_on_unknown_count_is_rejected() {
        allUnknownRecord().copy(frameCountEstimated = true)
    }
}
