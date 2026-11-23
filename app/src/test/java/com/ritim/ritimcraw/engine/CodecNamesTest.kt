package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The F0 device-report capability formatters (plan 13.1). Values are android-36 SDK constants. */
class CodecNamesTest {

    @Test
    fun reportMimesCoverAvcHevcDvAv1Apv() {
        assertEquals(
            listOf("video/avc", "video/hevc", "video/dolby-vision", "video/av01", "video/apv"),
            CodecProbe.REPORT_MIMES,
        )
    }

    @Test
    fun profileNamesPerCodec() {
        assertEquals("High", CodecNames.profileName(CodecNames.MIME_AVC, 8))
        assertEquals("Main10", CodecNames.profileName(CodecNames.MIME_HEVC, 2))
        assertEquals("Main10HDR10", CodecNames.profileName(CodecNames.MIME_HEVC, 4096))
        assertEquals("Main10", CodecNames.profileName(CodecNames.MIME_AV1, 2))
        assertEquals("422-10", CodecNames.profileName(CodecNames.MIME_APV, 1))
        assertEquals("Profile 8 (DvheSt)", CodecNames.profileName(CodecNames.MIME_DV, 256))
        assertEquals("Profil 8 (DvheSt)", CodecNames.profileName(CodecNames.MIME_DV, 256, "Profil"))
        assertEquals("Profile 5 (DvheStn)", CodecNames.profileName(CodecNames.MIME_DV, 32))
        // unknown values are shown raw, never guessed
        assertEquals("0x7777", CodecNames.profileName(CodecNames.MIME_HEVC, 0x7777))
        assertEquals("0x2", CodecNames.profileName("video/x-other", 2))
    }

    @Test
    fun levelNames() {
        assertEquals("4.1", CodecNames.levelName(CodecNames.MIME_AVC, 4096))
        assertEquals("5.1", CodecNames.levelName(CodecNames.MIME_AVC, 32768))
        assertEquals("5", CodecNames.levelName(CodecNames.MIME_HEVC, 16384)) // MainTierLevel5
        assertEquals("5 High", CodecNames.levelName(CodecNames.MIME_HEVC, 32768)) // HighTierLevel5
        assertEquals("0x7000", CodecNames.levelName(CodecNames.MIME_DV, 0x7000)) // not tabulated: raw
        // Dolby Vision / AV1 / APV levels are names, not hex (values from the 15 Ultra's dumpsys)
        assertEquals("8K 60", CodecNames.levelName(CodecNames.MIME_DV, 2048))
        assertEquals("UHD 60", CodecNames.levelName(CodecNames.MIME_DV, 0x100))
        assertEquals("4.1", CodecNames.levelName(CodecNames.MIME_AV1, 512))
        assertEquals("6.1", CodecNames.levelName(CodecNames.MIME_AV1, 131072))
        assertEquals("2.0", CodecNames.levelName(CodecNames.MIME_AV1, 1))
        assertEquals("7.1 Band 3", CodecNames.levelName(CodecNames.MIME_APV, 2097160))
        // a shape the dump does not confirm stays raw
        assertEquals("0x30", CodecNames.levelName(CodecNames.MIME_APV, 0x30))
        assertEquals("0x3", CodecNames.levelName(CodecNames.MIME_AV1, 3))
    }

    @Test
    fun describeProfileLevelsKeepsHighestLevelPerProfileInDeclaredOrder() {
        val pls = listOf(
            ProfileLevel(1, 4096), ProfileLevel(2, 16384),
            ProfileLevel(1, 65536), ProfileLevel(2, 1024),
        )
        assertEquals(
            listOf("Main@5.1", "Main10@5"),
            // HEVC: profile 1 -> max(4096, 65536) = Main tier 5.1; profile 2 -> 16384 = 5
            CodecNames.describeProfileLevels(CodecNames.MIME_HEVC, pls),
        )
        assertTrue(CodecNames.describeProfileLevels(CodecNames.MIME_HEVC, emptyList()).isEmpty())
    }

    @Test
    fun theUltrasDolbyVisionEncoderReadsAsProfile8At8k60() {
        assertEquals(
            listOf("Profile 8 (DvheSt)@8K 60"),
            CodecNames.describeProfileLevels(CodecNames.MIME_DV, listOf(ProfileLevel(256, 2048))),
        )
        assertEquals(
            listOf("422-10@7.1 Band 3"),
            CodecNames.describeProfileLevels(CodecNames.MIME_APV, listOf(ProfileLevel(1, 2097160))),
        )
    }

    @Test
    fun colorFormatNames() {
        assertEquals("Surface", CodecNames.colorFormatName(2130708361))
        assertEquals("YUV420Flexible", CodecNames.colorFormatName(2135033992))
        assertEquals("YUV422Flexible", CodecNames.colorFormatName(2135042184))
        assertEquals("RGBAFlexible", CodecNames.colorFormatName(2134288520))
        assertEquals("RGBA1010102", CodecNames.colorFormatName(2130750114))
        assertEquals("RGBAFloat16", CodecNames.colorFormatName(2130710294))
        assertEquals("P010", CodecNames.colorFormatName(54))
        assertEquals("0x1234", CodecNames.colorFormatName(0x1234))
        // duplicates collapse, order kept
        assertEquals(
            listOf("Surface", "P010"),
            CodecNames.describeColorFormats(listOf(2130708361, 54, 2130708361)),
        )
    }

    @Test
    fun bitrateModesAndFormatting() {
        assertEquals("CBR", CodecNames.bitrateModeName(2))
        assertEquals("VBR", CodecNames.bitrateModeName(1))
        assertEquals("CQ", CodecNames.bitrateModeName(0))
        assertEquals("CBR_FD", CodecNames.bitrateModeName(3))
        assertEquals("mode 9", CodecNames.bitrateModeName(9))
        assertEquals("160 Mbps", CodecNames.formatBitrate(160_000_000))
        assertEquals("62.5 Mbps", CodecNames.formatBitrate(62_500_000))
        assertEquals("800 kbps", CodecNames.formatBitrate(800_000))
    }
}
