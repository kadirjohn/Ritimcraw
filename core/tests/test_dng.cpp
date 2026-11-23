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

TEST_CASE("linear_dng_roundtrip_tags") {
    // RAW-13: the Linear DNG contract — demosaiced, scene-referred linear
    // 16-bit RGB, PhotometricInterpretation 34892 (LinearRaw), 3 samples
    // per pixel, WB baked in (AsShotNeutral = 1), BlackLevel 0 /
    // WhiteLevel 65535 (stage-1 normalization already mapped the sensor
    // range to 0..1), the stage list recorded in ImageDescription, and the
    // pixels round-tripped verbatim.
    const char* path = "/tmp/rawedit-test-linear-dng.dng";

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

    // Synthetic demosaiced payload: a simple RGB gradient, 3 samples per
    // pixel, distinct per channel so the round-trip proves order.
    std::vector<uint16_t> rgb(64 * 48 * 3);
    for (size_t i = 0; i < rgb.size(); ++i)
        rgb[i] = static_cast<uint16_t>(1000 + (i % 3000));

    const Result<void> res = writeLinearDng(path, rgb.data(), frame, meta);
    REQUIRE(res.isOk());

    const std::vector<uint8_t> b = readFile(path);
    const auto tags = parseIfd(b);

    // Core TIFF tags.
    CHECK(tagLong(tags, 254) == 0);                       // NewSubfileType
    CHECK(tagLong(tags, 256) == 64);                      // ImageWidth
    CHECK(tagLong(tags, 257) == 48);                      // ImageLength
    CHECK(tagShort(tags, 259) == 1);                      // Compression
    CHECK(tagShort(tags, 262) == 34892);                  // Photometric LinearRaw
    CHECK(tagShort(tags, 274) == 1);                      // Orientation
    CHECK(tagShort(tags, 277) == 3);                      // SamplesPerPixel
    CHECK(tagLong(tags, 278) == 48);                       // RowsPerStrip
    const uint32_t stripBytes = tagLong(tags, 279);       // StripByteCounts
    CHECK(stripBytes == 64 * 48 * 3 * 2);
    const uint32_t stripOff = tagLong(tags, 273);         // StripOffsets
    CHECK(stripOff + stripBytes == b.size());

    // BitsPerSample: 3 SHORTs, all 16 — 6 bytes, so the value is external.
    const Tag bps = get(tags, 258);
    CHECK(bps.type == 3);
    CHECK(bps.count == 3);
    CHECK(bps.value.size() == 6);
    for (int i = 0; i < 3; ++i)
        CHECK(rd16(bps.value, static_cast<size_t>(i) * 2) == 16);

    // ImageDescription (270): the recorded stage list, NUL-terminated.
    const Tag desc = get(tags, 270);
    CHECK(desc.type == 2); // ASCII
    REQUIRE(desc.count > 1);
    CHECK(desc.value.back() == 0);
    const std::string text(desc.value.begin(), desc.value.end() - 1);
    CHECK(text.find("stages = black/white normalization") != std::string::npos);
    CHECK(text.find("bilinear demosaic") != std::string::npos);
    CHECK(text.find("as-shot white balance") != std::string::npos);
    CHECK(text.find("developLinear") != std::string::npos);

    // CFAPlaneColor (50710): RGB plane order.
    const Tag planes = get(tags, 50710);
    CHECK(planes.type == 1);
    CHECK(planes.count == 3);
    CHECK(planes.value[0] == 0);
    CHECK(planes.value[1] == 1);
    CHECK(planes.value[2] == 2);

    CHECK(tagShort(tags, 50711) == 1);                    // CFALayout Rectangular

    const Tag ver = get(tags, 50706);                     // DNGVersion
    CHECK(ver.type == 1);
    CHECK(ver.count == 4);
    CHECK(ver.value[0] == 1);
    CHECK(ver.value[1] == 4);
    CHECK(ver.value[2] == 0);
    CHECK(ver.value[3] == 0);

    // BlackLevel: 3 SHORTs, all 0 (stage 1 already normalized).
    const Tag rep = get(tags, 50713);                     // BlackLevelRepeatDim
    CHECK(rep.type == 3);
    CHECK(rep.count == 2);
    CHECK(rd16(rep.value, 0) == 1);
    CHECK(rd16(rep.value, 2) == 1);
    const Tag black = get(tags, 50714);
    CHECK(black.type == 3);
    CHECK(black.count == 3);
    CHECK(black.value.size() == 6);
    for (int i = 0; i < 3; ++i)
        CHECK(rd16(black.value, static_cast<size_t>(i) * 2) == 0);

    CHECK(tagLong(tags, 50717) == 65535);                 // WhiteLevel

    // ColorMatrix1 kept as-is (the pixels stay in the camera primaries).
    const Tag cm = get(tags, 50721);
    CHECK(cm.type == 10);
    CHECK(cm.count == 9);
    CHECK(rd32(cm.value, 0) == 1000000);                  // [0] = 1.0
    CHECK(rd32(cm.value, 24) == static_cast<uint32_t>(-500000)); // [3] = -0.5

    // AsShotNeutral = (1,1,1): the WB is baked into the pixels.
    const Tag neutral = get(tags, 50728);
    CHECK(neutral.type == 5);
    CHECK(neutral.count == 3);
    for (int i = 0; i < 3; ++i) {
        CHECK(rd32(neutral.value, static_cast<size_t>(i) * 8) == 1000000);
        CHECK(rd32(neutral.value, static_cast<size_t>(i) * 8 + 4) == 1000000);
    }

    const Tag area = get(tags, 50829);                     // ActiveArea
    CHECK(area.type == 4);
    CHECK(area.count == 4);
    CHECK(rd32(area.value, 0) == 0);
    CHECK(rd32(area.value, 4) == 0);
    CHECK(rd32(area.value, 8) == 48);
    CHECK(rd32(area.value, 12) == 64);

    // Pixel data round-trip: the strip holds the RGB samples verbatim.
    for (size_t i = 0; i < rgb.size(); ++i) {
        const uint16_t got = rd16(b, stripOff + i * 2);
        if (got != rgb[i]) { CHECK(false); break; }
    }

    // Tags must be ascending (TIFF requirement).
    uint16_t prev = 0;
    for (const auto& kv : tags) {
        CHECK(kv.first > prev);
        prev = kv.first;
    }

    std::remove(path);
}

TEST_CASE("linear_dng_rejects_bad_input") {
    FrameInfo frame;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    RawMeta meta;
    std::vector<uint16_t> rgb(64 * 48 * 3);

    CHECK(!writeLinearDng("/tmp/should-not-exist-linear.dng", nullptr, frame, meta).isOk());

    FrameInfo zero = frame;
    zero.width = 0;
    CHECK(!writeLinearDng("/tmp/should-not-exist-linear.dng", rgb.data(), zero, meta).isOk());

    FrameInfo deep = frame;
    deep.bitDepth = 10;
    CHECK(!writeLinearDng("/tmp/should-not-exist-linear.dng", rgb.data(), deep, meta).isOk());

    // No partial file left behind.
    std::ifstream in("/tmp/should-not-exist-linear.dng");
    CHECK(!in.good());
}

// ---- 2026-11-23: optional provenance tags (DngExtraMeta), LINEAR_DNG_GAPS
// G1/G2/G3/G7 writer half, G6 rotated orientation pin. ----

namespace {

FrameInfo extraFrame(int orientationDeg = 0) {
    FrameInfo frame;
    frame.ptsUs = 123456789;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    frame.activeArea = Rect{0, 0, 64, 48};
    frame.orientationDeg = orientationDeg;
    return frame;
}

RawMeta extraRawMeta() {
    RawMeta meta;
    for (int i = 0; i < 4; ++i) meta.black[i] = 64;
    meta.white = 1023;
    return meta;
}

DngExtraMeta fullExtra() {
    DngExtraMeta x;
    x.make = "Xiaomi";
    x.model = "25010PN30G";
    x.uniqueCameraModel = "Xiaomi 15 Ultra";
    x.captureTimeUtc = dngDateTimeUtc(1763862896000LL);
    x.fNumber = 1.63;
    x.focalLength35mm = 23.4; // rounds to 23
    x.lensModel = "main 23mm";
    x.hasForwardMatrix1 = true;
    const float fm[9] = {0.6f, 0.2f, 0.15f, 0.25f, 0.7f, 0.05f, 0.0f, -0.1f, 0.9f};
    for (int i = 0; i < 9; ++i) x.forwardMatrix1[i] = fm[i];
    return x;
}

std::string tagAscii(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    const Tag t = get(tags, tag);
    REQUIRE(t.type == 2);
    REQUIRE(!t.value.empty());
    CHECK(t.value.back() == 0); // NUL-terminated
    return std::string(t.value.begin(), t.value.end() - 1);
}

double tagRational(const std::map<uint16_t, Tag>& tags, uint16_t tag, size_t i = 0) {
    const Tag t = get(tags, tag);
    REQUIRE((t.type == 5 || t.type == 10));
    const uint32_t num = rd32(t.value, i * 8);
    const uint32_t den = rd32(t.value, i * 8 + 4);
    REQUIRE(den != 0);
    if (t.type == 10)
        return static_cast<double>(static_cast<int32_t>(num)) / static_cast<int32_t>(den);
    return static_cast<double>(num) / den;
}

// Every external value offset in the IFD must be even (TIFF word rule)
// and the tags strictly ascending.
void checkIfdShape(const std::vector<uint8_t>& b) {
    static const size_t kSizes[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8};
    const uint32_t ifdOff = rd32(b, 4);
    const uint16_t n = rd16(b, ifdOff);
    int prev = -1;
    for (uint16_t i = 0; i < n; ++i) {
        const size_t e = ifdOff + 2 + static_cast<size_t>(i) * 12;
        const uint16_t tag = rd16(b, e);
        CHECK(static_cast<int>(tag) > prev);
        prev = tag;
        const uint16_t type = rd16(b, e + 2);
        const size_t len = rd32(b, e + 4) * kSizes[type < 11 ? type : 0];
        // The pre-existing linear payload (BlackLevel after the odd-length
        // description) is grandfathered; the NEW tags must be even.
        const bool newTag = tag == 271 || tag == 272 || tag == 306 ||
            tag == 33437 || tag == 36867 || tag == 36881 || tag == 37386 ||
            tag == 42036 || tag == 50708 || tag == 50964;
        if (len > 4 && newTag) CHECK((rd32(b, e + 8) & 1u) == 0u);
    }
}

} // namespace

TEST_CASE("dng_datetime_utc_format") {
    CHECK(dngDateTimeUtc(0) == "1970:01:01 00:00:00");
    CHECK(dngDateTimeUtc(1763862896000LL) == "2025:11:23 01:54:56");
    CHECK(dngDateTimeUtc(1763862896999LL) == "2025:11:23 01:54:56"); // ms truncate
    CHECK(dngDateTimeUtc(-1).empty()); // FrameInfo.recvdMs "absent"
}

TEST_CASE("dng_extra_meta_default_is_byte_identical") {
    // The honesty rule: a default DngExtraMeta writes NO new tag, so both
    // arms stay byte-identical to the pre-extra writer (21 entries).
    const FrameInfo frame = extraFrame();
    const RawMeta meta = extraRawMeta();
    std::vector<uint16_t> cfa(64 * 48, 500);
    std::vector<uint16_t> rgb(64 * 48 * 3, 700);

    REQUIRE(writeCfaDng("/tmp/rawedit-x-a.dng", cfa.data(), frame, meta).isOk());
    REQUIRE(writeCfaDng("/tmp/rawedit-x-b.dng", cfa.data(), frame, meta, DngExtraMeta{}).isOk());
    CHECK(readFile("/tmp/rawedit-x-a.dng") == readFile("/tmp/rawedit-x-b.dng"));
    CHECK(parseIfd(readFile("/tmp/rawedit-x-b.dng")).size() == 21);

    REQUIRE(writeLinearDng("/tmp/rawedit-x-c.dng", rgb.data(), frame, meta).isOk());
    REQUIRE(writeLinearDng("/tmp/rawedit-x-d.dng", rgb.data(), frame, meta, DngExtraMeta{}).isOk());
    CHECK(readFile("/tmp/rawedit-x-c.dng") == readFile("/tmp/rawedit-x-d.dng"));
    const auto lin = parseIfd(readFile("/tmp/rawedit-x-d.dng"));
    CHECK(lin.size() == 21);
    CHECK(lin.count(271) == 0);
    CHECK(lin.count(36867) == 0);
    CHECK(lin.count(41989) == 0);

    for (const char* p : {"/tmp/rawedit-x-a.dng", "/tmp/rawedit-x-b.dng",
                          "/tmp/rawedit-x-c.dng", "/tmp/rawedit-x-d.dng"})
        std::remove(p);
}

namespace {

void checkFullExtraTags(const std::map<uint16_t, Tag>& tags) {
    CHECK(tagAscii(tags, 271) == "Xiaomi");
    CHECK(tagAscii(tags, 272) == "25010PN30G");
    CHECK(tagAscii(tags, 50708) == "Xiaomi 15 Ultra");
    CHECK(tagAscii(tags, 306) == "2025:11:23 01:54:56");
    CHECK(tagAscii(tags, 36867) == "2025:11:23 01:54:56");
    CHECK(tagAscii(tags, 36881) == "+00:00"); // the string is UTC
    CHECK(tagRational(tags, 33437) == doctest::Approx(1.63));
    CHECK(tagShort(tags, 41989) == 23);       // 35mm-equivalent, rounded
    CHECK(tags.count(37386) == 0);            // no PHYSICAL focal invented
    CHECK(tagAscii(tags, 42036) == "main 23mm");
    const Tag fm = get(tags, 50964);
    CHECK(fm.type == 10);
    CHECK(fm.count == 9);
    CHECK(tagRational(tags, 50964, 0) == doctest::Approx(0.6));
    CHECK(tagRational(tags, 50964, 7) == doctest::Approx(-0.1));
    CHECK(tagRational(tags, 50964, 8) == doctest::Approx(0.9));
}

} // namespace

TEST_CASE("cfa_dng_writes_extra_provenance_tags") {
    const char* path = "/tmp/rawedit-x-cfa-full.dng";
    const FrameInfo frame = extraFrame();
    const RawMeta meta = extraRawMeta();
    std::vector<uint16_t> cfa(64 * 48);
    for (size_t i = 0; i < cfa.size(); ++i) cfa[i] = static_cast<uint16_t>(64 + i % 900);

    REQUIRE(writeCfaDng(path, cfa.data(), frame, meta, fullExtra()).isOk());
    const std::vector<uint8_t> b = readFile(path);
    const auto tags = parseIfd(b);
    CHECK(tags.size() == 21 + 10);
    checkFullExtraTags(tags);
    checkIfdShape(b);
    // The base contract is untouched: CFA photometric, pattern, pixels.
    CHECK(tagShort(tags, 262) == 32803);
    const uint32_t stripOff = tagLong(tags, 273);
    CHECK(tagLong(tags, 279) == 64u * 48u * 2u);
    REQUIRE(stripOff + 64u * 48u * 2u == b.size());
    CHECK(std::memcmp(b.data() + stripOff, cfa.data(), cfa.size() * 2) == 0);
    std::remove(path);
}

TEST_CASE("linear_dng_writes_extra_provenance_tags") {
    const char* path = "/tmp/rawedit-x-lin-full.dng";
    const FrameInfo frame = extraFrame();
    const RawMeta meta = extraRawMeta();
    std::vector<uint16_t> rgb(64 * 48 * 3);
    for (size_t i = 0; i < rgb.size(); ++i) rgb[i] = static_cast<uint16_t>(2000 + i % 5000);

    REQUIRE(writeLinearDng(path, rgb.data(), frame, meta, fullExtra()).isOk());
    const std::vector<uint8_t> b = readFile(path);
    const auto tags = parseIfd(b);
    CHECK(tags.size() == 21 + 10);
    checkFullExtraTags(tags);
    checkIfdShape(b);
    CHECK(tagShort(tags, 262) == 34892);
    // The linear contract survives the extras (identity neutral, 0/65535).
    CHECK(tagRational(tags, 50728, 0) == doctest::Approx(1.0));
    CHECK(tagLong(tags, 50717) == 65535u);
    const uint32_t stripOff = tagLong(tags, 273);
    REQUIRE(stripOff + rgb.size() * 2 == b.size());
    CHECK(std::memcmp(b.data() + stripOff, rgb.data(), rgb.size() * 2) == 0);
    std::remove(path);
}

TEST_CASE("dng_extra_physical_focal_only_when_supplied") {
    const char* path = "/tmp/rawedit-x-focal.dng";
    std::vector<uint16_t> cfa(64 * 48, 300);
    DngExtraMeta x;
    x.focalLengthMm = 6.9; // a real physical focal from some future source
    REQUIRE(writeCfaDng(path, cfa.data(), extraFrame(), extraRawMeta(), x).isOk());
    const auto tags = parseIfd(readFile(path));
    CHECK(tags.size() == 22);
    CHECK(tagRational(tags, 37386) == doctest::Approx(6.9));
    CHECK(tags.count(41989) == 0);
    CHECK(tags.count(306) == 0); // no capture time supplied -> none written
    std::remove(path);
}

TEST_CASE("dng_rotated_orientation_values_both_arms") {
    // LINEAR_DNG_GAPS G6: the rotated Orientation values in the ACTUAL
    // files (not only the JVM mirror): 90 -> 6, 180 -> 3, 270 -> 8,
    // and -90 normalizes to 270 -> 8.
    std::vector<uint16_t> cfa(64 * 48, 300);
    std::vector<uint16_t> rgb(64 * 48 * 3, 300);
    const int degs[] = {0, 90, 180, 270, -90, 450};
    const uint16_t want[] = {1, 6, 3, 8, 8, 6};
    for (int i = 0; i < 6; ++i) {
        const FrameInfo f = extraFrame(degs[i]);
        REQUIRE(writeCfaDng("/tmp/rawedit-x-o1.dng", cfa.data(), f, extraRawMeta()).isOk());
        REQUIRE(writeLinearDng("/tmp/rawedit-x-o2.dng", rgb.data(), f, extraRawMeta()).isOk());
        CHECK(tagShort(parseIfd(readFile("/tmp/rawedit-x-o1.dng")), 274) == want[i]);
        CHECK(tagShort(parseIfd(readFile("/tmp/rawedit-x-o2.dng")), 274) == want[i]);
    }
    std::remove("/tmp/rawedit-x-o1.dng");
    std::remove("/tmp/rawedit-x-o2.dng");
}
