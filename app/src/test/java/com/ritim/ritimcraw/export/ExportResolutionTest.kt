package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.RotationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExportResolution tests (the pure rules the sheet's chips and the
 * exporter's encoder dims both consume). The expert's warning checklist,
 * each locked by a test:
 *  - 4K 16:9 ladder: Original/EQUAL/recommended, UHD EQUAL, FHD/HD
 *    DOWNSCALE.
 *  - DCI 4096x2160: Original 4096x2160 is NOT the UHD entry — UHD
 *    resolves to 3840x2024 (fits-within), a different, visibly smaller
 *    option.
 *  - 4:3 NO-CROP PROOF: a 4:3 source picking FHD resolves to its OWN
 *    aspect (1440x1080), never a 16:9 crop; the aspect ratio is
 *    preserved on every entry.
 *  - Vertical orientation: a portrait source transposes the bounds
 *    (UHD = 2160x3840) and stays portrait.
 *  - Upscale flagging: an HD source's UHD entry is UPSCALE, never the
 *    recommendation.
 *  - Even-snap: odd source dims resolve even on every axis.
 *  - Codec-refusal: the seam's refusal disables the ENTRY (Original
 *    exempt), never silently rewrites its size.
 */
class ExportResolutionTest {

    // ---- helpers ----

    private fun dims(w: Int, h: Int) = RotationState.Dims(w, h)

    private fun fullCrop() = CropState.RectF(0f, 0f, 1f, 1f)

    /** The effective size of an uncropped, unrotated source. */
    private fun effective(w: Int, h: Int): RotationState.Dims =
        ExportResolution.effectiveSourceSize(w, h, 0, 0, 0f, 0f, 1f, 1f)

    // ---- effective source size (reuse chain) ----

    @Test
    fun effectiveSourceSize_is_the_crop_and_rotation_math() {
        // Reuse proof: the chain IS RotationState + CropState, so
        // "Original" equals what the exporter produces today.
        val neutral = ExportResolution.effectiveSourceSize(
            4032, 3024, 0, 0, 0f, 0f, 1f, 1f,
        )
        assertEquals(dims(4032, 3024), neutral)

        // Quarter-turn container: dims swap.
        val rotated = ExportResolution.effectiveSourceSize(
            4032, 3024, 90, 0, 0f, 0f, 1f, 1f,
        )
        assertEquals(dims(3024, 4032), rotated)

        // Manual rotation composes with the container value.
        val manual = ExportResolution.effectiveSourceSize(
            4032, 3024, 90, 90, 0f, 0f, 1f, 1f,
        )
        assertEquals(dims(4032, 3024), manual)

        // Crop applies over the upright dims, even-snapped.
        val cropped = ExportResolution.effectiveSourceSize(
            4033, 3023, 0, 0, 0f, 0f, 0.5f, 0.5f,
        )
        assertEquals(dims(2016, 1512), cropped)
    }

    @Test
    fun effectiveSourceSize_even_snaps_odd_crops() {
        // A crop that lands on odd pixels snaps even (min 2) — the
        // encoder's own rule, reused from CropState.snapEven.
        val odd = ExportResolution.effectiveSourceSize(
            7, 5, 0, 0, 0f, 0f, 1f, 1f,
        )
        assertEquals(dims(6, 4), odd)
    }

    // ---- 4K 16:9 ladder ----

    @Test
    fun ladder_4k16x9_original_recommended_uhd_equal_fhd_hd_downscale() {
        val src = effective(3840, 2160)
        val ladder = ExportResolution.ladder(src, 30.0)

        assertEquals(4, ladder.size)
        val byKind = ladder.associateBy { it.kind }

        val original = byKind.getValue(ExportResolution.Kind.ORIGINAL)
        assertEquals(dims(3840, 2160), dims(original.width, original.height))
        assertEquals(ExportResolution.Scale.EQUAL, original.scale)
        assertTrue(original.recommended)
        assertTrue(original.supported)

        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertEquals(dims(3840, 2160), dims(uhd.width, uhd.height))
        assertEquals(ExportResolution.Scale.EQUAL, uhd.scale)
        assertFalse(uhd.recommended)

        val fhd = byKind.getValue(ExportResolution.Kind.FHD)
        assertEquals(dims(1920, 1080), dims(fhd.width, fhd.height))
        assertEquals(ExportResolution.Scale.DOWNSCALE, fhd.scale)

        val hd = byKind.getValue(ExportResolution.Kind.HD)
        assertEquals(dims(1280, 720), dims(hd.width, hd.height))
        assertEquals(ExportResolution.Scale.DOWNSCALE, hd.scale)
    }

    // ---- DCI 4096 distinction ----

    @Test
    fun dci_original_and_uhd_are_different_options() {
        val src = effective(4096, 2160)
        val ladder = ExportResolution.ladder(src, 24.0)
        val byKind = ladder.associateBy { it.kind }

        // Original keeps the DCI frame exactly.
        val original = byKind.getValue(ExportResolution.Kind.ORIGINAL)
        assertEquals(4096, original.width)
        assertEquals(2160, original.height)
        assertEquals(ExportResolution.Scale.EQUAL, original.scale)
        assertTrue(original.recommended)

        // UHD is the 3840x2160 bound FIT WITHIN the DCI aspect: 3840 wide
        // limits, height = 3840 * 2160/4096 = 2025 -> even-snap 2024.
        // A DIFFERENT, visibly smaller option — never an alias.
        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertEquals(3840, uhd.width)
        assertEquals(2024, uhd.height)
        assertEquals(ExportResolution.Scale.DOWNSCALE, uhd.scale)
        assertFalse(uhd.recommended)
    }

    // ---- 4:3 no-crop proof ----

    @Test
    fun fourByThree_fhd_keeps_source_aspect_never_crops_to_16x9() {
        val src = effective(4032, 3024) // 4:3
        val ladder = ExportResolution.ladder(src, 30.0)
        val byKind = ladder.associateBy { it.kind }

        // FHD means "fits within 1920x1080, aspect preserved": the
        // height limits, width = 1080 * 4032/3024 = 1440. The output is
        // 1440x1080 — 4:3 — NOT 1920x1080. The SIZE decision never
        // makes a FRAMING decision.
        val fhd = byKind.getValue(ExportResolution.Kind.FHD)
        assertEquals(1440, fhd.width)
        assertEquals(1080, fhd.height)
        assertEquals(ExportResolution.Scale.DOWNSCALE, fhd.scale)

        // UHD the same way: 2160 limits, width = 2160 * 4032/3024 = 2880.
        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertEquals(2880, uhd.width)
        assertEquals(2160, uhd.height)

        // HD: 720 limits, width = 720 * 4032/3024 = 960.
        val hd = byKind.getValue(ExportResolution.Kind.HD)
        assertEquals(960, hd.width)
        assertEquals(720, hd.height)

        // Aspect preserved on every entry (within the even-snap's 1px).
        val srcRatio = 4032.0 / 3024.0
        ladder.forEach {
            val ratio = it.width.toDouble() / it.height
            assertTrue(
                "kind ${it.kind} ratio $ratio drifted from $srcRatio",
                kotlin.math.abs(ratio - srcRatio) < 0.01,
            )
        }
    }

    @Test
    fun custom_bound_also_preserves_aspect() {
        val src = effective(4032, 3024) // 4:3
        // Custom 1920x1080 is a BOUND, not a forcing: the same 4:3
        // source resolves to 1440x1080 — no crop, no stretch, no bars.
        val out = ExportResolution.outputSize(
            ExportResolution.Kind.CUSTOM, src, 1920, 1080,
        )
        assertEquals(dims(1440, 1080), out)
    }

    // ---- vertical orientation ----

    @Test
    fun vertical_source_transposes_bounds_and_stays_portrait() {
        // A portrait 2160x3840 source: UHD means 2160x3840 (EQUAL),
        // FHD 1080x1920, HD 720x1280 — the long edge stays the height.
        val src = effective(2160, 3840)
        val ladder = ExportResolution.ladder(src, 30.0)
        val byKind = ladder.associateBy { it.kind }

        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertEquals(2160, uhd.width)
        assertEquals(3840, uhd.height)
        assertEquals(ExportResolution.Scale.EQUAL, uhd.scale)

        val fhd = byKind.getValue(ExportResolution.Kind.FHD)
        assertEquals(1080, fhd.width)
        assertEquals(1920, fhd.height)

        val hd = byKind.getValue(ExportResolution.Kind.HD)
        assertEquals(720, hd.width)
        assertEquals(1280, hd.height)

        // Every entry stays portrait.
        ladder.forEach { assertTrue(it.height > it.width) }
    }

    @Test
    fun vertical_effective_size_from_container_rotation() {
        // A 3840x2160 sensor clip with a 90-degree container rotation is
        // effectively PORTRAIT 2160x3840 — the ladder must orient by the
        // EFFECTIVE dims, not the sensor dims.
        val src = ExportResolution.effectiveSourceSize(
            3840, 2160, 90, 0, 0f, 0f, 1f, 1f,
        )
        assertEquals(dims(2160, 3840), src)
        val fhd = ExportResolution.choice(ExportResolution.Kind.FHD, src, 30.0)
        assertEquals(1080, fhd.width)
        assertEquals(1920, fhd.height)
    }

    // ---- upscale flagging ----

    @Test
    fun hd_source_uhd_entry_is_flagged_upscale_and_not_recommended() {
        val src = effective(1280, 720)
        val ladder = ExportResolution.ladder(src, 30.0)
        val byKind = ladder.associateBy { it.kind }

        // Only Original is the recommendation.
        assertEquals(1, ladder.count { it.recommended })
        assertTrue(byKind.getValue(ExportResolution.Kind.ORIGINAL).recommended)

        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertEquals(ExportResolution.Scale.UPSCALE, uhd.scale)
        assertFalse(uhd.recommended)

        val fhd = byKind.getValue(ExportResolution.Kind.FHD)
        assertEquals(ExportResolution.Scale.UPSCALE, fhd.scale)

        // HD on an HD source is EQUAL, and FHD is honestly flagged.
        assertEquals(ExportResolution.Scale.EQUAL, byKind.getValue(ExportResolution.Kind.HD).scale)
    }

    @Test
    fun custom_upscale_is_flagged() {
        val src = effective(1920, 1080)
        val up = ExportResolution.choice(
            ExportResolution.Kind.CUSTOM, src, 30.0, customWidth = 3840, customHeight = 2160,
        )
        assertEquals(ExportResolution.Scale.UPSCALE, up.scale)
        assertEquals(dims(3840, 2160), dims(up.width, up.height))
    }

    // ---- even-snap ----

    @Test
    fun resolved_outputs_are_even_snapped() {
        // An odd-sized source: every fixed entry resolves even on both
        // axes (the encoder's rule).
        val src = effective(1003, 751) // odd, roughly 4:3
        val ladder = ExportResolution.ladder(src, 30.0)
        ladder.forEach {
            assertEquals(0, it.width % 2)
            assertEquals(0, it.height % 2)
        }
        val custom = ExportResolution.choice(
            ExportResolution.Kind.CUSTOM, src, 30.0,
            customWidth = 1919, customHeight = 1079,
        )
        assertEquals(0, custom.width % 2)
        assertEquals(0, custom.height % 2)
    }

    // ---- codec-refusal seam ----

    @Test
    fun codec_refusal_disables_the_entry_and_never_rewrites_its_size() {
        val src = effective(3840, 2160)
        // The seam refuses everything 4K-and-up at this rate.
        val codec = ExportResolution.CodecSupport { w, h, fps ->
            !(w >= 3840 && h >= 2000 && fps > 24.0)
        }
        val ladder = ExportResolution.ladder(src, 30.0, codec)
        val byKind = ladder.associateBy { it.kind }

        // UHD is refused at 30fps: the entry is DISABLED, but its size
        // is still the honest 3840x2160 — a refusal never silently
        // becomes another size.
        val uhd = byKind.getValue(ExportResolution.Kind.UHD)
        assertFalse(uhd.supported)
        assertEquals(3840, uhd.width)
        assertEquals(2160, uhd.height)

        // FHD/HD pass the seam.
        assertTrue(byKind.getValue(ExportResolution.Kind.FHD).supported)
        assertTrue(byKind.getValue(ExportResolution.Kind.HD).supported)

        // ORIGINAL is never codec-gated: it is today's behavior, and
        // the device's honest verdict arrives at encode time as it
        // always has.
        val original = byKind.getValue(ExportResolution.Kind.ORIGINAL)
        assertTrue(original.supported)
        assertTrue(original.recommended)
    }

    @Test
    fun no_codec_seam_means_no_entry_is_gated() {
        val src = effective(3840, 2160)
        val ladder = ExportResolution.ladder(src, 30.0, codec = null as ExportResolution.CodecSupport?)
        assertTrue(ladder.all { it.supported })
    }

    @Test
    fun custom_requires_positive_dims() {
        val src = effective(1920, 1080)
        var threw = false
        try {
            ExportResolution.outputSize(ExportResolution.Kind.CUSTOM, src, 0, 1080)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun source_must_be_usable() {
        var threw = false
        try {
            ExportResolution.outputSize(ExportResolution.Kind.FHD, dims(0, 0))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
