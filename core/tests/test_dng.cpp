// test_dng — round-trip the DNG writer's own output through a mini IFD
// parser and CHECK every tag the writer claims to write.
//
// The synthetic frame: 64x48 RGGB, black 64, white 1023, neutral WB,
// identity-plus-offset color matrix. The parser reads the little-endian
// TIFF header, walks the single IFD, and resolves inline and external
// values so each tag can be verified without any external tool.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/DngWriter.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

std::vector<uint8_t> readFile(const char* path) {
    std::ifstream in(path, std::ios::binary);
    REQUIRE_MESSAGE(in, "cannot reopen written file");
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                                 std::istreambuf_iterator<char>());
}

uint16_t rd16(const std::vector<uint8_t>& b, size_t off) {
    return static_cast<uint16_t>(b[off] | (b[off + 1] << 8));
}

uint32_t rd32(const std::vector<uint8_t>& b, size_t off) {
    return static_cast<uint32_t>(b[off]) |
           (static_cast<uint32_t>(b[off + 1]) << 8) |
           (static_cast<uint32_t>(b[off + 2]) << 16) |
           (static_cast<uint32_t>(b[off + 3]) << 24);
}

struct Tag {
    uint16_t type = 0;
    uint32_t count = 0;
    std::vector<uint8_t> value; // resolved bytes
};

// Minimal single-IFD parser: header + entries + external values.
std::map<uint16_t, Tag> parseIfd(const std::vector<uint8_t>& b) {
    REQUIRE(b.size() >= 8);
    REQUIRE(b[0] == 'I');
    REQUIRE(b[1] == 'I');
    REQUIRE(rd16(b, 2) == 42);
    const uint32_t ifdOff = rd32(b, 4);
    const uint16_t n = rd16(b, ifdOff);

    static const size_t kSizes[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8};
    std::map<uint16_t, Tag> tags;
    for (uint16_t i = 0; i < n; ++i) {
        const size_t e = ifdOff + 2 + static_cast<size_t>(i) * 12;
        REQUIRE(e + 12 <= b.size());
        Tag t;
        t.type = rd16(b, e + 2);
        t.count = rd32(b, e + 4);
        const size_t len = t.count * kSizes[t.type < 11 ? t.type : 0];
        if (len <= 4) {
            t.value.assign(b.begin() + e + 8, b.begin() + e + 8 + len);
        } else {
            const uint32_t off = rd32(b, e + 8);
            REQUIRE(off + len <= b.size());
            t.value.assign(b.begin() + off, b.begin() + off + len);
        }
        tags[rd16(b, e)] = t;
    }
    // Next-IFD offset must be 0 (single IFD).
    REQUIRE(rd32(b, ifdOff + 2 + static_cast<size_t>(n) * 12) == 0);
    return tags;
}

Tag get(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    const auto it = tags.find(tag);
    REQUIRE_MESSAGE(it != tags.end(), "tag missing");
    return it->second;
}

uint32_t tagLong(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    const Tag t = get(tags, tag);
    REQUIRE(t.type == 4);
    REQUIRE(t.count == 1);
    return rd32(t.value, 0);
}

uint16_t tagShort(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    const Tag t = get(tags, tag);
    REQUIRE(t.type == 3);
    REQUIRE(t.count == 1);
    return rd16(t.value, 0);
}

} // namespace

TEST_CASE("dng_roundtrip_tags") {
    const char* path = "/tmp/rawedit-test-dng.dng";

    // Synthetic 64x48 RGGB frame: a simple gradient with black offset.
    FrameInfo frame;
    frame.ptsUs = 123456789;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    frame.activeArea = Rect{0, 0, 64, 48};
    frame.orientationDeg = 0;

    RawMeta meta;
    meta.black[0] = 64; meta.black[1] = 64;
    meta.black[2] = 64; meta.black[3] = 64;
    meta.white = 1023;
    meta.asShotNeutral[0] = 0.5f;
    meta.asShotNeutral[1] = 1.0f;
    meta.asShotNeutral[2] = 0.25f;
    meta.colorMatrix[0] = 1.0f;  meta.colorMatrix[1] = 0.0f;  meta.colorMatrix[2] = 0.0f;
    meta.colorMatrix[3] = -0.5f; meta.colorMatrix[4] = 1.0f;  meta.colorMatrix[5] = 0.1f;
    meta.colorMatrix[6] = 0.0f;  meta.colorMatrix[7] = 0.0f;  meta.colorMatrix[8] = 1.5f;

    std::vector<uint16_t> cfa(64 * 48);
    for (size_t i = 0; i < cfa.size(); ++i)
        cfa[i] = static_cast<uint16_t>(64 + (i % 960) / 30); // 64..95

    const Result<void> res = writeCfaDng(path, cfa.data(), frame, meta);
    REQUIRE(res.isOk());

    const std::vector<uint8_t> b = readFile(path);

    // Header.
    REQUIRE(b.size() == 8 + 2 + 21 * 12 + 4 + 120 + 64 * 48 * 2);

    const auto tags = parseIfd(b);

    // Core TIFF tags.
    CHECK(tagLong(tags, 254) == 0);                       // NewSubfileType
    CHECK(tagLong(tags, 256) == 64);                      // ImageWidth
    CHECK(tagLong(tags, 257) == 48);                      // ImageLength
    CHECK(tagShort(tags, 258) == 16);                      // BitsPerSample
    CHECK(tagShort(tags, 259) == 1);                       // Compression
    CHECK(tagShort(tags, 262) == 32803);                   // Photometric CFA
    CHECK(tagShort(tags, 274) == 1);                       // Orientation
    CHECK(tagShort(tags, 277) == 1);                       // SamplesPerPixel
    CHECK(tagLong(tags, 278) == 48);                        // RowsPerStrip
    const uint32_t stripBytes = tagLong(tags, 279);        // StripByteCounts
    CHECK(stripBytes == 64 * 48 * 2);
    const uint32_t stripOff = tagLong(tags, 273);          // StripOffsets
    CHECK(stripOff + stripBytes == b.size());

    // CFA tags.
    const Tag dim = get(tags, 33421);                      // CFARepeatPatternDim
    CHECK(dim.type == 3);
    CHECK(dim.count == 2);
    CHECK(rd16(dim.value, 0) == 2);
    CHECK(rd16(dim.value, 2) == 2);
    const Tag pat = get(tags, 33422);                      // CFAPattern
    CHECK(pat.type == 1);
    CHECK(pat.count == 4);
    CHECK(pat.value[0] == 0); // R
    CHECK(pat.value[1] == 1); // G
    CHECK(pat.value[2] == 1); // G
    CHECK(pat.value[3] == 2); // B

    // DNG tags.
    const Tag ver = get(tags, 50706);                      // DNGVersion
    CHECK(ver.type == 1);
    CHECK(ver.count == 4);
    CHECK(ver.value[0] == 1);
    CHECK(ver.value[1] == 4);
    CHECK(ver.value[2] == 0);
    CHECK(ver.value[3] == 0);
    CHECK(tagShort(tags, 50711) == 1);                     // CFALayout

    const Tag rep = get(tags, 50713);                      // BlackLevelRepeatDim
    CHECK(rep.type == 3);
    CHECK(rep.count == 2);
    CHECK(rd16(rep.value, 0) == 2);
    CHECK(rd16(rep.value, 2) == 2);

    const Tag black = get(tags, 50714);                    // BlackLevel
    CHECK(black.type == 3);
    CHECK(black.count == 4);
    for (int i = 0; i < 4; ++i)
        CHECK(rd16(black.value, static_cast<size_t>(i) * 2) == 64);

    CHECK(tagLong(tags, 50717) == 1023);                   // WhiteLevel

    const Tag cm = get(tags, 50721);                        // ColorMatrix1
    CHECK(cm.type == 10); // SRATIONAL
    CHECK(cm.count == 9);
    CHECK(cm.value.size() == 72);
    // Element i: numerator at i*8, denominator 1000000 at i*8+4.
    CHECK(rd32(cm.value, 0) == 1000000);                    // [0] = 1.0
    CHECK(rd32(cm.value, 4) == 1000000);
    CHECK(rd32(cm.value, 24) == static_cast<uint32_t>(-500000)); // [3] = -0.5
    CHECK(rd32(cm.value, 28) == 1000000);
    CHECK(rd32(cm.value, 40) == 100000);                    // [5] = 0.1
    CHECK(rd32(cm.value, 64) == 1500000);                   // [8] = 1.5
    CHECK(rd32(cm.value, 68) == 1000000);

    const Tag neutral = get(tags, 50728);                   // AsShotNeutral
    CHECK(neutral.type == 5); // RATIONAL
    CHECK(neutral.count == 3);
    CHECK(neutral.value.size() == 24);
    CHECK(rd32(neutral.value, 0) == 500000);                // 0.5
    CHECK(rd32(neutral.value, 4) == 1000000);
    CHECK(rd32(neutral.value, 8) == 1000000);               // 1.0
    CHECK(rd32(neutral.value, 16) == 250000);               // 0.25
    CHECK(rd32(neutral.value, 20) == 1000000);

    const Tag area = get(tags, 50829);                      // ActiveArea
    CHECK(area.type == 4);
    CHECK(area.count == 4);
    CHECK(rd32(area.value, 0) == 0);                        // top
    CHECK(rd32(area.value, 4) == 0);                        // left
    CHECK(rd32(area.value, 8) == 48);                       // bottom
    CHECK(rd32(area.value, 12) == 64);                      // right

    // Pixel data round-trip: the strip holds the CFA samples verbatim.
    for (size_t i = 0; i < cfa.size(); ++i) {
        const uint16_t got = rd16(b, stripOff + i * 2);
        if (got != cfa[i]) { CHECK(false); break; }
    }

    // Tags must be ascending (TIFF requirement).
    uint16_t prev = 0;
    for (const auto& kv : tags) {
        CHECK(kv.first > prev);
        prev = kv.first;
    }

    std::remove(path);
}

TEST_CASE("dng_rejects_bad_input") {
    FrameInfo frame;
    frame.width = 64;
    frame.height = 48;
    RawMeta meta;
    std::vector<uint16_t> cfa(64 * 48);

    CHECK(!writeCfaDng("/tmp/should-not-exist.dng", nullptr, frame, meta).isOk());

    FrameInfo zero = frame;
    zero.width = 0;
    CHECK(!writeCfaDng("/tmp/should-not-exist.dng", cfa.data(), zero, meta).isOk());

    FrameInfo deep = frame;
    deep.bitDepth = 10;
    CHECK(!writeCfaDng("/tmp/should-not-exist.dng", cfa.data(), deep, meta).isOk());

    // No partial file left behind.
    std::ifstream in("/tmp/should-not-exist.dng");
    CHECK(!in.good());
}

TEST_CASE("dng_writes_frame_preferred_levels") {
    // Owner request 2026-11-11 section 3: a frame that carried dynamic
    // black/white levels writes THOSE into the BlackLevel (50714) and
    // WhiteLevel (50717) tags, while the container meta holds different
    // values. The CFA pixels are written verbatim (never gain-multiplied),
    // so the tags are the only place the frame calibration can land.
    const char* path = "/tmp/rawedit-test-dng-frame-levels.dng";

    FrameInfo frame;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    frame.activeArea = Rect{0, 0, 64, 48};
    frame.dynamicBlack[0] = 100;
    frame.dynamicBlack[1] = 100;
    frame.dynamicBlack[2] = 100;
    frame.dynamicBlack[3] = 100;
    frame.dynamicWhite = 2000;

    RawMeta meta;
    meta.black[0] = 64; meta.black[1] = 64;
    meta.black[2] = 64; meta.black[3] = 64;
    meta.white = 1023;

    std::vector<uint16_t> cfa(64 * 48, 100);
    const Result<void> res = writeCfaDng(path, cfa.data(), frame, meta);
    REQUIRE(res.isOk());

    const auto tags = parseIfd(readFile(path));
    const Tag black = get(tags, 50714);
    REQUIRE(black.count == 4);
    for (int i = 0; i < 4; ++i)
        CHECK(rd16(black.value, static_cast<size_t>(i) * 2) == 100);
    CHECK(tagLong(tags, 50717) == 2000);

    std::remove(path);
}

TEST_CASE("dng_falls_back_to_container_levels") {
    // The FrameInfo sentinel (-1) means "this frame did not carry a
    // level": the container RawMeta values must land in the tags.
    const char* path = "/tmp/rawedit-test-dng-container-levels.dng";

    FrameInfo frame;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    frame.activeArea = Rect{0, 0, 64, 48};
    for (int i = 0; i < 4; ++i)
        REQUIRE(frame.dynamicBlack[i] == -1);
    REQUIRE(frame.dynamicWhite == -1);

    RawMeta meta;
    meta.black[0] = 64; meta.black[1] = 64;
    meta.black[2] = 64; meta.black[3] = 64;
    meta.white = 1023;

    std::vector<uint16_t> cfa(64 * 48, 64);
    const Result<void> res = writeCfaDng(path, cfa.data(), frame, meta);
    REQUIRE(res.isOk());

    const auto tags = parseIfd(readFile(path));
    const Tag black = get(tags, 50714);
    REQUIRE(black.count == 4);
    for (int i = 0; i < 4; ++i)
        CHECK(rd16(black.value, static_cast<size_t>(i) * 2) == 64);
    CHECK(tagLong(tags, 50717) == 1023);

    std::remove(path);
}
