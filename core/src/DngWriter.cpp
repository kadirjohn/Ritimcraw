// Minimal little-endian TIFF/DNG writers for CFA and Linear DNG frames. See
// rawedit/DngWriter.h for the interface contract.
//
// Layout: 8-byte TIFF header, one IFD (tags ascending), the external-value
// payload, then the pixel strip. Values that fit in 4 bytes are inline; the
// rest live in the payload with offsets computed up front, so nothing is
// back-patched.

#include <rawedit/DngWriter.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <vector>

namespace rawedit {
namespace {

// TIFF data types used here.
constexpr uint16_t kTypeByte = 1;
constexpr uint16_t kTypeAscii = 2;
constexpr uint16_t kTypeShort = 3;
constexpr uint16_t kTypeLong = 4;
constexpr uint16_t kTypeRational = 5;
constexpr uint16_t kTypeSRational = 10;

// TIFF/DNG tags written by this file (ascending).
constexpr uint16_t kTagNewSubfileType = 254;
constexpr uint16_t kTagImageWidth = 256;
constexpr uint16_t kTagImageLength = 257;
constexpr uint16_t kTagBitsPerSample = 258;
constexpr uint16_t kTagCompression = 259;
constexpr uint16_t kTagPhotometric = 262;
constexpr uint16_t kTagStripOffsets = 273;
constexpr uint16_t kTagOrientation = 274;
constexpr uint16_t kTagSamplesPerPixel = 277;
constexpr uint16_t kTagRowsPerStrip = 278;
constexpr uint16_t kTagStripByteCounts = 279;
constexpr uint16_t kTagCFARepeatPatternDim = 33421;
constexpr uint16_t kTagCFAPattern = 33422;
constexpr uint16_t kTagDNGVersion = 50706;
constexpr uint16_t kTagCFALayout = 50711;
constexpr uint16_t kTagBlackLevelRepeatDim = 50713;
constexpr uint16_t kTagBlackLevel = 50714;
constexpr uint16_t kTagWhiteLevel = 50717;
constexpr uint16_t kTagColorMatrix1 = 50721;
constexpr uint16_t kTagAsShotNeutral = 50728;
constexpr uint16_t kTagActiveArea = 50829;
constexpr uint16_t kTagImageDescription = 270;
constexpr uint16_t kTagCFAPlaneColor = 50710;
// PhotometricInterpretation VALUES (the tag itself is 262).
constexpr uint16_t kPhotometricCfa = 32803;
constexpr uint16_t kPhotometricLinearRaw = 34892;

size_t typeSize(uint16_t type) {
    switch (type) {
        case kTypeByte:      return 1;
        case kTypeAscii:     return 1;
        case kTypeShort:     return 2;
        case kTypeLong:      return 4;
        case kTypeRational:  return 8;
        case kTypeSRational: return 8;
    }
    return 0;
}

// TIFF orientation for FrameInfo::orientationDeg (degrees CW to upright):
// 1 = normal, 6 = rotate 90 CW, 3 = 180, 8 = rotate 270 CW.
uint16_t tiffOrientation(int orientationDeg) {
    switch (((orientationDeg % 360) + 360) % 360) {
        case 90:  return 6;
        case 180: return 3;
        case 270: return 8;
        default:  return 1;
    }
}

// 2x2 CFA channel colors as CFAPattern codes: 0=R, 1=G, 2=B, in the same
// (r, g, g, b) scan order as RawMeta::black.
void cfaCodes(CfaPattern p, uint8_t out[4]) {
    switch (p) {
        case CfaPattern::RGGB: out[0] = 0; out[1] = 1; out[2] = 1; out[3] = 2; break;
        case CfaPattern::GRBG: out[0] = 1; out[1] = 0; out[2] = 2; out[3] = 1; break;
        case CfaPattern::GBRG: out[0] = 1; out[1] = 2; out[2] = 0; out[3] = 1; break;
        case CfaPattern::BGGR: out[0] = 2; out[1] = 1; out[2] = 1; out[3] = 0; break;
    }
}

// One IFD entry: inline bytes when count*typeSize <= 4, else an absolute
// file offset resolved while building the payload.
struct Entry {
    uint16_t tag = 0;
    uint16_t type = 0;
    uint32_t count = 0;
    uint8_t value[4] = {0, 0, 0, 0};
};

void put16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xff));
}

void put32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 16) & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 24) & 0xff));
}

void setInlineShorts(Entry& e, std::initializer_list<uint16_t> shorts) {
    size_t off = 0;
    for (uint16_t v : shorts) {
        e.value[off++] = static_cast<uint8_t>(v & 0xff);
        e.value[off++] = static_cast<uint8_t>((v >> 8) & 0xff);
    }
}

void setInlineLong(Entry& e, uint32_t v) {
    e.value[0] = static_cast<uint8_t>(v & 0xff);
    e.value[1] = static_cast<uint8_t>((v >> 8) & 0xff);
    e.value[2] = static_cast<uint8_t>((v >> 16) & 0xff);
    e.value[3] = static_cast<uint8_t>((v >> 24) & 0xff);
}

// Float -> (numerator, denominator) with denominator 1e6. Signed numerator
// for SRATIONAL, unsigned for RATIONAL.
void putRational(std::vector<uint8_t>& payload, double v, bool signedNum) {
    const int64_t num = static_cast<int64_t>(std::llround(v * 1000000.0));
    if (signedNum) {
        put32(payload, static_cast<uint32_t>(static_cast<int32_t>(num)));
    } else {
        put32(payload, static_cast<uint32_t>(num));
    }
    put32(payload, 1000000u);
}

} // namespace

Result<void> writeCfaDng(const std::string& path, const uint16_t* cfa,
                         const FrameInfo& frame, const RawMeta& meta) {
    if (cfa == nullptr)
        return Result<void>::fail(ErrorCode::Unsupported, "cfa buffer is null");
    if (frame.width == 0 || frame.height == 0)
        return Result<void>::fail(ErrorCode::Unsupported, "empty frame");
    if (frame.bitDepth != 16)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "only 16-bit CFA supported");

    const uint32_t w = frame.width;
    const uint32_t h = frame.height;
    const uint64_t stripBytes64 = static_cast<uint64_t>(w) * h * 2;
    if (stripBytes64 > 0xffffffffull)
        return Result<void>::fail(ErrorCode::Unsupported, "strip too large");
    const uint32_t stripBytes = static_cast<uint32_t>(stripBytes64);

    uint8_t codes[4];
    cfaCodes(frame.cfa, codes);

    // External payload: BlackLevel (8B), ColorMatrix1 (72B), AsShotNeutral
    // (24B), ActiveArea (16B) = 120 bytes, laid out in tag order.
    std::vector<uint8_t> payload;
    payload.reserve(128);

    Entry blackLevel;
    blackLevel.tag = kTagBlackLevel;
    blackLevel.type = kTypeShort;
    blackLevel.count = 4;
    blackLevel.value[0] = blackLevel.value[1] = 0; // offset filled below
    blackLevel.value[2] = blackLevel.value[3] = 0;

    Entry colorMatrix;
    colorMatrix.tag = kTagColorMatrix1;
    colorMatrix.type = kTypeSRational;
    colorMatrix.count = 9;

    Entry asShotNeutral;
    asShotNeutral.tag = kTagAsShotNeutral;
    asShotNeutral.type = kTypeRational;
    asShotNeutral.count = 3;

    Entry activeArea;
    activeArea.tag = kTagActiveArea;
    activeArea.type = kTypeLong;
    activeArea.count = 4;

    const uint32_t ifdOffset = 8;
    const size_t entryCount = 21;
    const uint32_t payloadOffset =
        ifdOffset + static_cast<uint32_t>(2 + 12 * entryCount + 4);

    uint32_t cursor = payloadOffset;
    auto place = [&](Entry& e) {
        const size_t bytes = e.count * typeSize(e.type);
        setInlineLong(e, cursor);
        cursor += static_cast<uint32_t>(bytes);
    };

    place(blackLevel);
    // Frame-preferred levels (owner request 2026-11-11 section 3): the
    // frame's dynamic black/white levels win when it carried them; the
    // container RawMeta values are the fallback. The CFA pixels are
    // written verbatim — never gain-multiplied — so the levels written
    // here are exactly the calibration that matches the stored samples.
    {
        const int32_t fb[4] = {
            frame.dynamicBlack[0], frame.dynamicBlack[1],
            frame.dynamicBlack[2], frame.dynamicBlack[3],
        };
        const bool useFrameBlack = fb[0] >= 0;
        for (int i = 0; i < 4; ++i)
            put16(payload, static_cast<uint16_t>(
                useFrameBlack ? fb[i] : meta.black[i]));
    }

    place(colorMatrix);
    for (int i = 0; i < 9; ++i)
        putRational(payload, meta.colorMatrix[i], /*signedNum=*/true);

    place(asShotNeutral);
    for (int i = 0; i < 3; ++i)
        putRational(payload, meta.asShotNeutral[i], /*signedNum=*/false);

    place(activeArea);
    const Rect& a = frame.activeArea;
    const uint32_t top = a.h > 0 ? static_cast<uint32_t>(a.y) : 0;
    const uint32_t left = a.w > 0 ? static_cast<uint32_t>(a.x) : 0;
    const uint32_t bottom = a.h > 0 ? static_cast<uint32_t>(a.y + a.h) : h;
    const uint32_t right = a.w > 0 ? static_cast<uint32_t>(a.x + a.w) : w;
    put32(payload, top);
    put32(payload, left);
    put32(payload, bottom);
    put32(payload, right);

    const uint32_t stripOffset = cursor;

    // IFD entries, ascending by tag.
    std::vector<Entry> entries;

    Entry e;
    e.tag = kTagNewSubfileType; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, 0); entries.push_back(e);

    e = Entry{};
    e.tag = kTagImageWidth; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, w); entries.push_back(e);

    e = Entry{};
    e.tag = kTagImageLength; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, h); entries.push_back(e);

    e = Entry{};
    e.tag = kTagBitsPerSample; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {16}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagCompression; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {1}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagPhotometric; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {kPhotometricCfa}); entries.push_back(e); // CFA

    e = Entry{};
    e.tag = kTagStripOffsets; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, stripOffset); entries.push_back(e);

    e = Entry{};
    e.tag = kTagOrientation; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {tiffOrientation(frame.orientationDeg)});
    entries.push_back(e);

    e = Entry{};
    e.tag = kTagSamplesPerPixel; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {1}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagRowsPerStrip; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, h); entries.push_back(e);

    e = Entry{};
    e.tag = kTagStripByteCounts; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, stripBytes); entries.push_back(e);

    e = Entry{};
    e.tag = kTagCFARepeatPatternDim; e.type = kTypeShort; e.count = 2;
    setInlineShorts(e, {2, 2}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagCFAPattern; e.type = kTypeByte; e.count = 4;
    e.value[0] = codes[0]; e.value[1] = codes[1];
    e.value[2] = codes[2]; e.value[3] = codes[3];
    entries.push_back(e);

    e = Entry{};
    e.tag = kTagDNGVersion; e.type = kTypeByte; e.count = 4;
    e.value[0] = 1; e.value[1] = 4; e.value[2] = 0; e.value[3] = 0;
    entries.push_back(e); // DNG 1.4.0.0

    e = Entry{};
    e.tag = kTagCFALayout; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {1}); entries.push_back(e); // Rectangular

    e = Entry{};
    e.tag = kTagBlackLevelRepeatDim; e.type = kTypeShort; e.count = 2;
    setInlineShorts(e, {2, 2}); entries.push_back(e);

    entries.push_back(blackLevel);

    e = Entry{};
    e.tag = kTagWhiteLevel; e.type = kTypeLong; e.count = 1;
    // Frame-preferred white level, same rule as the BlackLevel payload
    // above (owner request 2026-11-11 section 3).
    setInlineLong(e, frame.dynamicWhite > 0
        ? static_cast<uint32_t>(frame.dynamicWhite)
        : static_cast<uint32_t>(meta.white));
    entries.push_back(e);

    entries.push_back(colorMatrix);
    entries.push_back(asShotNeutral);
    entries.push_back(activeArea);

    // Serialize.
    std::vector<uint8_t> file;
    file.reserve(static_cast<size_t>(stripOffset) + stripBytes);

    // TIFF header: little-endian, magic 42, IFD at 8.
    file.push_back('I'); file.push_back('I');
    put16(file, 42);
    put32(file, ifdOffset);

    put16(file, static_cast<uint16_t>(entries.size()));
    for (const Entry& ent : entries) {
        put16(file, ent.tag);
        put16(file, ent.type);
        put32(file, ent.count);
        file.insert(file.end(), ent.value, ent.value + 4);
    }
    put32(file, 0); // no next IFD

    file.insert(file.end(), payload.begin(), payload.end());
    if (file.size() != stripOffset)
        return Result<void>::fail(ErrorCode::IoError, "internal layout error");

    const uint8_t* strip = reinterpret_cast<const uint8_t*>(cfa);
    file.insert(file.end(), strip, strip + stripBytes);

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out)
        return Result<void>::fail(ErrorCode::IoError,
                                  "cannot open " + path);
    out.write(reinterpret_cast<const char*>(file.data()),
              static_cast<std::streamsize>(file.size()));
    out.close();
    if (!out) {
        std::remove(path.c_str());
        return Result<void>::fail(ErrorCode::IoError, "writing " + path +
                                   " failed (removed partial file)");
    }
    return Result<void>::ok();
}

// The processing stages baked into a Linear DNG's pixels (RAW-13: "Lineer
// çıktıya hangi aşamaların işlendiğini kaydet" — record which stages were
// processed into the linear output). Written verbatim into ImageDescription
// (270) so a reader can tell exactly what this file is. The develop itself
// lives in Develop.cpp developLinear (the JNI export calls it); this
// constant is the single source of the recorded list — the develop and the
// tag must never drift apart, so the string names the develop function and
// the test pins its presence. The tone controls (9-20, color wheel and
// split tone included) are part of the NOT-run list: the linear DNG is
// scene-referred data and carries no display look.
const char* const kLinearDngStages =
    "ritiMCRAW linear DNG: stages = black/white normalization "
    "(frame-preferred levels), bilinear demosaic, as-shot white balance "
    "(divide by AsShotNeutral); no shading, no highlight reconstruction, "
    "no camera-to-Rec.709, no tone controls, no LUT, no denoise. "
    "developed by developLinear.";

Result<void> writeLinearDng(const std::string& path, const uint16_t* rgb,
                            const FrameInfo& frame, const RawMeta& meta) {
    if (rgb == nullptr)
        return Result<void>::fail(ErrorCode::Unsupported, "rgb buffer is null");
    if (frame.width == 0 || frame.height == 0)
        return Result<void>::fail(ErrorCode::Unsupported, "empty frame");
    if (frame.bitDepth != 16)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "only 16-bit linear supported");

    const uint32_t w = frame.width;
    const uint32_t h = frame.height;
    const uint64_t stripBytes64 = static_cast<uint64_t>(w) * h * 3 * 2;
    if (stripBytes64 > 0xffffffffull)
        return Result<void>::fail(ErrorCode::Unsupported, "strip too large");
    const uint32_t stripBytes = static_cast<uint32_t>(stripBytes64);

    const std::string description(kLinearDngStages);
    const uint32_t descriptionBytes =
        static_cast<uint32_t>(description.size() + 1); // NUL-terminated

    // External payload, laid out in tag order: BitsPerSample (6B),
    // ImageDescription (N bytes), BlackLevel (6B), ColorMatrix1 (72B),
    // AsShotNeutral (24B), ActiveArea (16B). WhiteLevel is a constant
    // (65535) and travels inline; CFAPlaneColor (3 bytes) fits inline at
    // the tag site.
    std::vector<uint8_t> payload;
    payload.reserve(128 + descriptionBytes);

    Entry bitsPerSample;
    bitsPerSample.tag = kTagBitsPerSample;
    bitsPerSample.type = kTypeShort;
    bitsPerSample.count = 3;

    Entry imageDescription;
    imageDescription.tag = kTagImageDescription;
    imageDescription.type = kTypeAscii;
    imageDescription.count = descriptionBytes;

    Entry blackLevel;
    blackLevel.tag = kTagBlackLevel;
    blackLevel.type = kTypeShort;
    blackLevel.count = 3;

    Entry colorMatrix;
    colorMatrix.tag = kTagColorMatrix1;
    colorMatrix.type = kTypeSRational;
    colorMatrix.count = 9;

    Entry asShotNeutral;
    asShotNeutral.tag = kTagAsShotNeutral;
    asShotNeutral.type = kTypeRational;
    asShotNeutral.count = 3;

    Entry activeArea;
    activeArea.tag = kTagActiveArea;
    activeArea.type = kTypeLong;
    activeArea.count = 4;

    const uint32_t ifdOffset = 8;
    const size_t entryCount = 21;
    const uint32_t payloadOffset =
        ifdOffset + static_cast<uint32_t>(2 + 12 * entryCount + 4);

    uint32_t cursor = payloadOffset;
    auto place = [&](Entry& e) {
        const size_t bytes = e.count * typeSize(e.type);
        setInlineLong(e, cursor);
        cursor += static_cast<uint32_t>(bytes);
    };

    place(bitsPerSample);
    put16(payload, 16); put16(payload, 16); put16(payload, 16);

    place(imageDescription);
    payload.insert(payload.end(), description.begin(), description.end());
    payload.push_back(0); // ASCII values are NUL-terminated

    place(blackLevel);
    // Stage-1 normalization already mapped the sensor range to 0..1 and the
    // pixels were scaled to 16 bits, so the stored samples' black is 0 —
    // a reader must not subtract a sensor black again.
    put16(payload, 0); put16(payload, 0); put16(payload, 0);

    place(colorMatrix);
    for (int i = 0; i < 9; ++i)
        putRational(payload, meta.colorMatrix[i], /*signedNum=*/true);

    place(asShotNeutral);
    // AsShotNeutral = (1,1,1): the WB is already baked into the pixels by
    // developLinear, so a reader must NOT re-apply it (double WB would
    // shift neutrals). The identity neutral is the DNG contract for
    // "already white-balanced data".
    putRational(payload, 1.0, /*signedNum=*/false);
    putRational(payload, 1.0, /*signedNum=*/false);
    putRational(payload, 1.0, /*signedNum=*/false);

    place(activeArea);
    const Rect& a = frame.activeArea;
    const uint32_t top = a.h > 0 ? static_cast<uint32_t>(a.y) : 0;
    const uint32_t left = a.w > 0 ? static_cast<uint32_t>(a.x) : 0;
    const uint32_t bottom = a.h > 0 ? static_cast<uint32_t>(a.y + a.h) : h;
    const uint32_t right = a.w > 0 ? static_cast<uint32_t>(a.x + a.w) : w;
    put32(payload, top);
    put32(payload, left);
    put32(payload, bottom);
    put32(payload, right);

    const uint32_t stripOffset = cursor;

    // IFD entries, ascending by tag (21 entries — the CFA set minus
    // CFARepeatPatternDim/CFAPattern, plus ImageDescription/CFAPlaneColor).
    std::vector<Entry> entries;

    Entry e;
    e.tag = kTagNewSubfileType; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, 0); entries.push_back(e);

    e = Entry{};
    e.tag = kTagImageWidth; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, w); entries.push_back(e);

    e = Entry{};
    e.tag = kTagImageLength; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, h); entries.push_back(e);

    entries.push_back(bitsPerSample); // 258, external, placed above

    e = Entry{};
    e.tag = kTagCompression; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {1}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagPhotometric; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {kPhotometricLinearRaw}); entries.push_back(e); // LinearRaw

    entries.push_back(imageDescription); // 270, external, placed above

    e = Entry{};
    e.tag = kTagStripOffsets; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, stripOffset); entries.push_back(e);

    e = Entry{};
    e.tag = kTagOrientation; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {tiffOrientation(frame.orientationDeg)});
    entries.push_back(e);

    e = Entry{};
    e.tag = kTagSamplesPerPixel; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {3}); entries.push_back(e);

    e = Entry{};
    e.tag = kTagRowsPerStrip; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, h); entries.push_back(e);

    e = Entry{};
    e.tag = kTagStripByteCounts; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, stripBytes); entries.push_back(e);

    e = Entry{};
    e.tag = kTagDNGVersion; e.type = kTypeByte; e.count = 4;
    e.value[0] = 1; e.value[1] = 4; e.value[2] = 0; e.value[3] = 0;
    entries.push_back(e); // DNG 1.4.0.0

    e = Entry{};
    e.tag = kTagCFAPlaneColor; e.type = kTypeByte; e.count = 3;
    e.value[0] = 0; e.value[1] = 1; e.value[2] = 2; // RGB
    entries.push_back(e);

    e = Entry{};
    e.tag = kTagCFALayout; e.type = kTypeShort; e.count = 1;
    setInlineShorts(e, {1}); entries.push_back(e); // Rectangular

    e = Entry{};
    e.tag = kTagBlackLevelRepeatDim; e.type = kTypeShort; e.count = 2;
    setInlineShorts(e, {1, 1}); entries.push_back(e);

    entries.push_back(blackLevel); // 50714, external, placed above

    e = Entry{};
    e.tag = kTagWhiteLevel; e.type = kTypeLong; e.count = 1;
    setInlineLong(e, 65535); entries.push_back(e);

    entries.push_back(colorMatrix);
    entries.push_back(asShotNeutral);
    entries.push_back(activeArea);

    // Serialize.
    std::vector<uint8_t> file;
    file.reserve(static_cast<size_t>(stripOffset) + stripBytes);

    // TIFF header: little-endian, magic 42, IFD at 8.
    file.push_back('I'); file.push_back('I');
    put16(file, 42);
    put32(file, ifdOffset);

    put16(file, static_cast<uint16_t>(entries.size()));
    for (const Entry& ent : entries) {
        put16(file, ent.tag);
        put16(file, ent.type);
        put32(file, ent.count);
        file.insert(file.end(), ent.value, ent.value + 4);
    }
    put32(file, 0); // no next IFD

    file.insert(file.end(), payload.begin(), payload.end());
    if (file.size() != stripOffset)
        return Result<void>::fail(ErrorCode::IoError, "internal layout error");

    const uint8_t* strip = reinterpret_cast<const uint8_t*>(rgb);
    file.insert(file.end(), strip, strip + stripBytes);

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out)
        return Result<void>::fail(ErrorCode::IoError,
                                  "cannot open " + path);
    out.write(reinterpret_cast<const char*>(file.data()),
              static_cast<std::streamsize>(file.size()));
    out.close();
    if (!out) {
        std::remove(path.c_str());
        return Result<void>::fail(ErrorCode::IoError, "writing " + path +
                                   " failed (removed partial file)");
    }
    return Result<void>::ok();
}

} // namespace rawedit
