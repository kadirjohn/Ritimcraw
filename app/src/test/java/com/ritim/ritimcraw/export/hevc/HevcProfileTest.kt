package com.ritim.ritimcraw.export.hevc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HevcProfile contract tests (night research 2026-11-16, package B5):
 * every profile's MediaFormat contract data, asserted against the
 * android-34 SDK jar values (the minSdk) — the exact key names the
 * Android docs define (KEY_PROFILE="profile" etc.), the profile/level/
 * color constants, the bit depths, and the FEATURE_* gates with their
 * API levels as DATA.
 *
 * The three HEVC profiles are SEPARATE experiments (the research's
 * rule): the tests prove they differ field by field, never aliases.
 * 10-bit is NOT the same feature as HDR: HEVC10_SDR is 10-bit with NO
 * HDR gate and NO static metadata.
 *
 * Pure JVM; no android.media anywhere (the model carries the constants
 * as data — these tests pin them to the verified SDK values).
 */
class HevcProfileTest {

    // ---- the profile set ----

    @Test
    fun the_four_profiles_exist_and_are_distinct_values() {
        // DISTINCT enum values, never aliases of one "HDR" toggle.
        val all = HevcProfile.entries
        assertEquals(4, all.size)
        assertTrue(all.contains(HevcProfile.AVC_SDR))
        assertTrue(all.contains(HevcProfile.HEVC10_SDR))
        assertTrue(all.contains(HevcProfile.HLG))
        assertTrue(all.contains(HevcProfile.HDR10))
        // the three HEVC experiments pairwise differ in transfer
        val transfers = all.filter { it.isHevc }.map { it.colorTransfer }.toSet()
        assertEquals(3, transfers.size)
    }

    @Test
    fun hevc_profiles_are_hevc_and_avc_baseline_is_not() {
        assertTrue(HevcProfile.HEVC10_SDR.isHevc)
        assertTrue(HevcProfile.HLG.isHevc)
        assertTrue(HevcProfile.HDR10.isHevc)
        assertFalse(HevcProfile.AVC_SDR.isHevc)
        assertEquals("video/hevc", HevcProfile.HEVC10_SDR.mime)
        assertEquals("video/hevc", HevcProfile.HLG.mime)
        assertEquals("video/hevc", HevcProfile.HDR10.mime)
        assertEquals("video/avc", HevcProfile.AVC_SDR.mime)
    }

    // ---- AVC_SDR: the current baseline, byte-for-byte the writer's contract ----

    @Test
    fun avc_sdr_matches_today_s_writer_contract() {
        // MediaCodecMp4Writer.videoFormat today: video/avc,
        // AVCProfileHigh(8), BT709(1)/limited(2)/SMPTE170m(3), 8-bit.
        val avc = HevcProfile.AVC_SDR
        assertEquals("video/avc", avc.mime)
        assertEquals(8, avc.profile)          // AVCProfileHigh
        assertEquals(4096, avc.level)          // AVCLevel41
        assertEquals(1, avc.colorStandard)    // COLOR_STANDARD_BT709
        assertEquals(2, avc.colorRange)        // COLOR_RANGE_LIMITED
        assertEquals(3, avc.colorTransfer)    // COLOR_TRANSFER_SDR_VIDEO
        assertEquals(8, avc.bitDepth)
        assertFalse(avc.isTenBit)
        assertFalse(avc.requiresHdrStaticInfo)
        assertNull(avc.feature)
        assertEquals("SMPTE170m", avc.transferName)
        assertEquals("BT709", avc.colorStandardName)
    }

    // ---- HEVC10_SDR: 10-bit precision, NO HDR anything ----

    @Test
    fun hevc10_sdr_is_ten_bit_main10_with_sdr_transfer_and_no_gate() {
        val sdr10 = HevcProfile.HEVC10_SDR
        assertEquals("video/hevc", sdr10.mime)
        assertEquals(10, sdr10.bitDepth)
        assertTrue(sdr10.isTenBit)
        assertEquals(2, sdr10.profile)        // HEVCProfileMain10
        assertEquals(16384, sdr10.level)        // HEVCMainTierLevel5
        // SAME transfer/gamut as today's AVC SDR — no HDR curve.
        assertEquals(3, sdr10.colorTransfer)  // COLOR_TRANSFER_SDR_VIDEO
        assertEquals(1, sdr10.colorStandard) // COLOR_STANDARD_BT709
        assertEquals(2, sdr10.colorRange)
        assertFalse(sdr10.requiresHdrStaticInfo)
        assertNull(sdr10.feature)               // gates on nothing but Main10
        assertEquals("SMPTE170m", sdr10.transferName)
        assertEquals("BT709", sdr10.colorStandardName)
    }

    @Test
    fun ten_bit_is_not_the_same_feature_as_hdr() {
        // The research's rule, as data: the 10-bit SDR profile carries
        // no HDR gate and no static metadata; only HDR10 does.
        assertFalse(HevcProfile.HEVC10_SDR.requiresHdrStaticInfo)
        assertNull(HevcProfile.HEVC10_SDR.feature)
        assertTrue(HevcProfile.HDR10.requiresHdrStaticInfo)
        assertTrue(HevcProfile.HDR10.feature != null)
    }

    // ---- HLG: BT2020/ARIB STD-B67, no metadata, the API-gated feature ----

    @Test
    fun hlg_is_main10_bt2020_arib_std_b67_without_static_metadata() {
        val hlg = HevcProfile.HLG
        assertEquals("video/hevc", hlg.mime)
        assertEquals(10, hlg.bitDepth)
        assertEquals(2, hlg.profile)            // HEVCProfileMain10
        assertEquals(6, hlg.colorStandard)    // COLOR_STANDARD_BT2020
        assertEquals(7, hlg.colorTransfer)    // COLOR_TRANSFER_HLG
        assertEquals(2, hlg.colorRange)
        assertEquals("HLG", hlg.transferName)  // ARIB STD-B67
        assertEquals("BT2020", hlg.colorStandardName)
        // Scene-referred: no HDR static metadata blob.
        assertFalse(hlg.requiresHdrStaticInfo)
    }

    @Test
    fun hlg_gate_is_the_api_gated_hlg_editing_feature() {
        val gate = HevcProfile.HLG.feature
        assertTrue(gate != null)
        assertEquals("hlg-editing", gate!!.featureName)   // FEATURE_HlgEditing
        assertEquals("FEATURE_HlgEditing", gate.docName)
        // The API gate as DATA: the constant does not exist at minSdk
        // 34 (first present, @FlaggedApi, in the API 36 SDK) — an
        // API-35+ feature must not be called unguarded on minSdk 34.
        assertEquals(36, gate.requiredApiLevel)
        assertTrue(gate.requiredApiLevel > 34)
    }

    // ---- HDR10: Main10HDR10, PQ, static metadata, the hdr-editing gate ----

    @Test
    fun hdr10_is_main10hdr10_pq_bt2020_with_static_metadata() {
        val hdr10 = HevcProfile.HDR10
        assertEquals("video/hevc", hdr10.mime)
        assertEquals(10, hdr10.bitDepth)
        assertEquals(4096, hdr10.profile)      // HEVCProfileMain10HDR10
        assertEquals(16384, hdr10.level)        // HEVCMainTierLevel5
        assertEquals(6, hdr10.colorStandard)  // COLOR_STANDARD_BT2020
        assertEquals(6, hdr10.colorTransfer) // COLOR_TRANSFER_ST2084 (PQ)
        assertEquals(2, hdr10.colorRange)
        assertEquals("PQ", hdr10.transferName)  // SMPTE ST2084
        assertEquals("BT2020", hdr10.colorStandardName)
        // The docs' rule: KEY_HDR_STATIC_INFO "must be provided to video
        // encoders for HDR video content".
        assertTrue(hdr10.requiresHdrStaticInfo)
    }

    @Test
    fun hdr10_gate_is_hdr_editing_readable_at_min_sdk() {
        val gate = HevcProfile.HDR10.feature
        assertTrue(gate != null)
        assertEquals("hdr-editing", gate!!.featureName)   // FEATURE_HdrEditing
        assertEquals("FEATURE_HdrEditing", gate.docName)
        // Present in the android-34 SDK jar — queryable at minSdk 34.
        assertTrue(gate.requiredApiLevel <= 34)
        assertEquals(29, gate.requiredApiLevel)
    }

    // ---- the three HEVC experiments differ field by field ----

    @Test
    fun hevc10_sdr_and_hlg_differ_in_transfer_and_gamut_only_where_hdr_begins() {
        // Same profile (Main10), same depth; HLG swaps transfer+gamut
        // and adds the gate — the two are SEPARATE experiments.
        assertEquals(HevcProfile.HEVC10_SDR.profile, HevcProfile.HLG.profile)
        assertEquals(HevcProfile.HEVC10_SDR.bitDepth, HevcProfile.HLG.bitDepth)
        assertNotEquals(HevcProfile.HEVC10_SDR.colorTransfer, HevcProfile.HLG.colorTransfer)
        assertNotEquals(HevcProfile.HEVC10_SDR.colorStandard, HevcProfile.HLG.colorStandard)
        assertNull(HevcProfile.HEVC10_SDR.feature)
        assertTrue(HevcProfile.HLG.feature != null)
    }

    @Test
    fun hlg_and_hdr10_differ_in_profile_transfer_and_metadata() {
        assertNotEquals(HevcProfile.HLG.profile, HevcProfile.HDR10.profile)
        assertNotEquals(HevcProfile.HLG.colorTransfer, HevcProfile.HDR10.colorTransfer)
        assertFalse(HevcProfile.HLG.requiresHdrStaticInfo)
        assertTrue(HevcProfile.HDR10.requiresHdrStaticInfo)
        assertNotEquals(HevcProfile.HLG.feature, HevcProfile.HDR10.feature)
    }

    // ---- the MediaFormat key names (the docs' EXACT strings) ----

    @Test
    fun media_format_entries_use_the_docs_exact_key_names() {
        // MediaFormat.KEY_PROFILE="profile", KEY_LEVEL="level",
        // KEY_COLOR_STANDARD="color-standard", KEY_COLOR_RANGE=
        // "color-range", KEY_COLOR_TRANSFER="color-transfer",
        // KEY_MIME="mime" — the android-34 jar's exact values.
        val entries = HevcProfile.HDR10.mediaFormatEntries().associate { it.key to it.value }
        assertEquals("video/hevc", entries["mime"])
        assertEquals("4096", entries["profile"])
        assertEquals("16384", entries["level"])
        assertEquals("6", entries["color-standard"])
        assertEquals("2", entries["color-range"])
        assertEquals("6", entries["color-transfer"])
        assertEquals(6, HevcProfile.HDR10.mediaFormatEntries().size)
    }

    @Test
    fun avc_sdr_entries_match_the_writer_s_key_order() {
        // The same six keys, in the writer's videoFormat order, so a
        // diff against today's contract is honest.
        val keys = HevcProfile.AVC_SDR.mediaFormatEntries().map { it.key }
        assertEquals(listOf("mime", "profile", "level", "color-standard", "color-range", "color-transfer"), keys)
        val entries = HevcProfile.AVC_SDR.mediaFormatEntries().associate { it.key to it.value }
        assertEquals("video/avc", entries["mime"])
        assertEquals("8", entries["profile"])
        assertEquals("4096", entries["level"])
        assertEquals("1", entries["color-standard"])
        assertEquals("2", entries["color-range"])
        assertEquals("3", entries["color-transfer"])
    }

    @Test
    fun every_profile_sets_profile_and_level_together() {
        // The docs' encoder rule: "profile (if set) must be set together
        // with KEY_LEVEL level" — every profile here carries BOTH.
        for (p in HevcProfile.entries) {
            val entries = p.mediaFormatEntries().associate { it.key to it.value }
            assertTrue("${p.name} missing profile key", entries.containsKey("profile"))
            assertTrue("${p.name} missing level key", entries.containsKey("level"))
        }
    }

    // ---- labels (the picker's vocabulary) ----

    @Test
    fun labels_name_the_depth_and_the_experiment() {
        assertTrue(HevcProfile.AVC_SDR.label.contains("AVC"))
        assertTrue(HevcProfile.AVC_SDR.label.contains("8-bit"))
        assertTrue(HevcProfile.HEVC10_SDR.label.contains("10-bit"))
        assertTrue(HevcProfile.HLG.label.contains("HLG"))
        assertTrue(HevcProfile.HDR10.label.contains("HDR10"))
    }
}
