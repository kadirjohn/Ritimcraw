// test_dng_sequence — the CinemaDNG sequence export core (night E-D3,
// docs/CINEMADNG_EXPORT_PLAN.md R1-2/R1-3/R1-4):
//   1. DngWriter's optional TimeCodes (51043) / FrameRate (51044) tags:
//      presence, byte layout, ascending order, and the BYTE-IDENTICAL
//      default (a default DngSequenceMeta writes nothing new);
//   2. the SMPTE timecode helpers' arithmetic (24/25/30 rollover,
//      drop-frame flag, the 23.976/29.97 families);
//   3. DngSequenceWriter: the frame loop, file naming (the 999999
//      boundary), progress, cancel = partial result preserved (no
//      silent delete), failure = written frames preserved;
//   4. WavWriter: the 44-byte header field by field, the range slice,
//      verbatim samples, the >4 GiB saturation, zero/single/huge frame
//      counts.
//
// The reader for tag verification is THIS file's own small TIFF parser
// (the test_dng pattern, repeated here because the task asks for the
// tag type/count/value proof next to the sequence tests).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/DngSequenceWriter.h>
#include <rawedit/DngWriter.h>
#include <rawedit/Source.h>
#include <rawedit/WavWriter.h>

#include <atomic>
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
    REQUIRE(rd32(b, ifdOff + 2 + static_cast<size_t>(n) * 12) == 0);
    return tags;
}

Tag get(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    const auto it = tags.find(tag);
    REQUIRE_MESSAGE(it != tags.end(), "tag missing");
    return it->second;
}

bool has(const std::map<uint16_t, Tag>& tags, uint16_t tag) {
    return tags.count(tag) != 0;
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

// The synthetic frame every sequence test writes (64x48 RGGB, the
// test_dng fixture shape).
FrameInfo seqFrame() {
    FrameInfo frame;
    frame.ptsUs = 0;
    frame.width = 64;
    frame.height = 48;
    frame.bitDepth = 16;
    frame.cfa = CfaPattern::RGGB;
    frame.activeArea = Rect{0, 0, 64, 48};
    frame.orientationDeg = 0;
    return frame;
}

RawMeta seqMeta() {
    RawMeta meta;
    for (int i = 0; i < 4; ++i) meta.black[i] = 64;
    meta.white = 1023;
    return meta;
}

std::vector<uint16_t> seqPixels(size_t i) {
    // Distinct per frame so a misordered write is caught by content.
    std::vector<uint16_t> cfa(64 * 48);
    for (size_t k = 0; k < cfa.size(); ++k)
        cfa[k] = static_cast<uint16_t>(64 + ((i * 977 + k) % 900));
    return cfa;
}

} // namespace

// ---- 1. the byte-identical default ----

TEST_CASE("dng_sequence_meta_default_is_byte_identical") {
    const FrameInfo frame = seqFrame();
    const RawMeta meta = seqMeta();
    std::vector<uint16_t> cfa(64 * 48, 500);
    std::vector<uint16_t> rgb(64 * 48 * 3, 700);

    REQUIRE(writeCfaDng("/tmp/seq-def-a.dng", cfa.data(), frame, meta).isOk());
    REQUIRE(writeCfaDng("/tmp/seq-def-b.dng", cfa.data(), frame, meta,
                        DngExtraMeta{}, DngSequenceMeta{}).isOk());
    CHECK(readFile("/tmp/seq-def-a.dng") == readFile("/tmp/seq-def-b.dng"));
    const auto cfaTags = parseIfd(readFile("/tmp/seq-def-b.dng"));
    CHECK(cfaTags.size() == 21);
    CHECK(!has(cfaTags, 51043));
    CHECK(!has(cfaTags, 51044));

    REQUIRE(writeLinearDng("/tmp/seq-def-c.dng", rgb.data(), frame, meta).isOk());
    REQUIRE(writeLinearDng("/tmp/seq-def-d.dng", rgb.data(), frame, meta,
                          DngExtraMeta{}, DngSequenceMeta{}).isOk());
    CHECK(readFile("/tmp/seq-def-c.dng") == readFile("/tmp/seq-def-d.dng"));
    const auto linTags = parseIfd(readFile("/tmp/seq-def-d.dng"));
    CHECK(linTags.size() == 21);
    CHECK(!has(linTags, 51043));
    CHECK(!has(linTags, 51044));

    for (const char* p : {"/tmp/seq-def-a.dng", "/tmp/seq-def-b.dng",
                          "/tmp/seq-def-c.dng", "/tmp/seq-def-d.dng"})
        std::remove(p);
}

TEST_CASE("dng_writes_timecodes_and_frame_rate_tags") {
    const FrameInfo frame = seqFrame();
    const RawMeta meta = seqMeta();
    std::vector<uint16_t> cfa(64 * 48, 400);

    DngSequenceMeta seq;
    seq.hasTimeCodes = true;
    // 01:02:03:04 at exact 25 fps: hour flag bit clear (25 fps), frame
    // field 4, no drop-frame flag.
    uint8_t code[8] = {0};
    smpteTimeCodes(1, 2, 3, 4, 0, 25.0, code);
    std::memcpy(seq.timeCodes, code, 8);
    seq.hasFrameRate = true;
    seq.frameRateNum = 30000; // 29.97 as the EXACT rational
    seq.frameRateDen = 1001;

    REQUIRE(writeCfaDng("/tmp/seq-tags.dng", cfa.data(), frame, meta,
                        DngExtraMeta{}, seq).isOk());
    const std::vector<uint8_t> b = readFile("/tmp/seq-tags.dng");
    const auto tags = parseIfd(b);
    CHECK(tags.size() == 23); // 21 + TimeCodes + FrameRate

    // TimeCodes (51043): BYTE x8, the bytes verbatim.
    const Tag tc = get(tags, 51043);
    CHECK(tc.type == 1);
    CHECK(tc.count == 8);
    CHECK(tc.value.size() == 8);
    // Every external value offset even (the TIFF word rule).
    CHECK((tc.value.size() % 2) == 0);
    CHECK(std::memcmp(tc.value.data(), code, 8) == 0);

    // FrameRate (51044): RATIONAL x1, 30000/1001 — the exact rational,
    // not a 1e6 approximation.
    const Tag fr = get(tags, 51044);
    CHECK(fr.type == 5);
    CHECK(fr.count == 1);
    CHECK(fr.value.size() == 8);
    CHECK(rd32(fr.value, 0) == 30000u);
    CHECK(rd32(fr.value, 4) == 1001u);

    // The base contract is untouched.
    CHECK(tagShort(tags, 262) == 32803);
    CHECK(tagLong(tags, 279) == 64u * 48u * 2u);
    // Tags ascending (51043/51044 sit after every base tag).
    uint16_t prev = 0;
    for (const auto& kv : tags) {
        CHECK(kv.first > prev);
        prev = kv.first;
    }
    std::remove("/tmp/seq-tags.dng");
}

TEST_CASE("dng_sequence_tags_write_in_both_arms") {
    // The Linear arm carries the same sequence identity: a Linear DNG
    // sequence (not exported tonight) would need the same tags.
    const FrameInfo frame = seqFrame();
    const RawMeta meta = seqMeta();
    std::vector<uint16_t> rgb(64 * 48 * 3, 300);

    DngSequenceMeta seq;
    seq.hasFrameRate = true;
    seq.frameRateNum = 25;
    seq.frameRateDen = 1;
    REQUIRE(writeLinearDng("/tmp/seq-lin.dng", rgb.data(), frame, meta,
                          DngExtraMeta{}, seq).isOk());
    const auto tags = parseIfd(readFile("/tmp/seq-lin.dng"));
    CHECK(tags.size() == 22);
    CHECK(!has(tags, 51043)); // timecode absent on purpose
    const Tag fr = get(tags, 51044);
    CHECK(rd32(fr.value, 0) == 25u);
    CHECK(rd32(fr.value, 4) == 1u);
    std::remove("/tmp/seq-lin.dng");
}

TEST_CASE("dng_sequence_tags_merge_with_extra_meta") {
    // The full extra set + the sequence tags: the IFD stays ascending,
    // the count is 21 + 10 + 2.
    const FrameInfo frame = seqFrame();
    const RawMeta meta = seqMeta();
    std::vector<uint16_t> cfa(64 * 48, 600);

    DngExtraMeta extra;
    extra.make = "Xiaomi";
    extra.model = "25010PN30G";
    extra.captureTimeUtc = dngDateTimeUtc(1763862896000LL);

    DngSequenceMeta seq;
    seq.hasTimeCodes = true;
    seq.hasFrameRate = true;
    seq.frameRateNum = 24;
    seq.frameRateDen = 1;
    uint8_t code[8] = {0};
    smpteTimeCodes(0, 0, 0, 0, 0, 24.0, code);
    std::memcpy(seq.timeCodes, code, 8);

    REQUIRE(writeCfaDng("/tmp/seq-both.dng", cfa.data(), frame, meta,
                       extra, seq).isOk());
    const auto tags = parseIfd(readFile("/tmp/seq-both.dng"));
    // 21 base + 5 extras (Make, Model, DateTime, DateTimeOriginal,
    // OffsetTimeOriginal) + TimeCodes + FrameRate.
    CHECK(tags.size() == 28);
    CHECK(has(tags, 271));   // Make
    CHECK(has(tags, 306));   // DateTime
    CHECK(has(tags, 51043)); // TimeCodes
    CHECK(has(tags, 51044)); // FrameRate
    uint16_t prev = 0;
    for (const auto& kv : tags) {
        CHECK(kv.first > prev);
        prev = kv.first;
    }
    std::remove("/tmp/seq-both.dng");
}

// ---- 2. the SMPTE helpers ----

TEST_CASE("smpte_timecode_layout_and_flags") {
    uint8_t code[8] = {0};

    SUBCASE("exact 25: no flags, fields verbatim") {
        smpteTimeCodes(1, 2, 3, 4, 0, 25.0, code);
        CHECK(code[0] == 1);
        CHECK(code[1] == 2);
        CHECK(code[2] == 3);
        CHECK(code[3] == 4);
        CHECK(code[4] == 0); // no drop-frame
    }
    SUBCASE("24 family sets bgf8") {
        smpteTimeCodes(0, 0, 0, 0, 0, 24.0, code);
        CHECK((code[0] & 0x80) != 0); // bgf8 flag on the hour byte
        CHECK((code[0] & 0x7f) == 0);
        smpteTimeCodes(0, 0, 0, 0, 0, 24000.0 / 1001.0, code);
        CHECK((code[0] & 0x80) != 0); // 23.976 counts as the 24 family
    }
    SUBCASE("29.97 sets drop-frame; exact 30 does not") {
        smpteTimeCodes(0, 0, 0, 0, 0, 30000.0 / 1001.0, code);
        CHECK(code[4] == 1);
        CHECK((code[0] & 0x80) == 0);
        smpteTimeCodes(0, 0, 0, 0, 0, 30.0, code);
        CHECK(code[4] == 0);
    }
    SUBCASE("frame rollover at 25") {
        smpteTimeCodes(0, 0, 0, 23, 2, 25.0, code);
        CHECK(code[3] == 0); // 23 + 2 rolls to frame 0
        CHECK(code[2] == 1); // next second
        CHECK((code[2] & 0x7f) == 1);
    }
    SUBCASE("second/minute/hour rollover") {
        smpteTimeCodes(0, 0, 59, 24, 1, 25.0, code);
        CHECK(code[2] == 0);
        CHECK(code[1] == 1); // minute rolls
        smpteTimeCodes(0, 59, 59, 24, 1, 25.0, code);
        CHECK(code[1] == 0);
        CHECK(code[0] == 1); // hour rolls
        smpteTimeCodes(23, 59, 59, 24, 1, 25.0, code);
        CHECK((code[0] & 0x7f) == 0); // hour wraps at 24
    }
    SUBCASE("raw passthrough keeps the user-bit tail") {
        uint8_t in[8] = {0, 0, 0, 0, 0, 0xAB, 0xCD, 0xEF};
        smpteTimeCodesRaw(in, 0, 25.0, code);
        CHECK(code[5] == 0xAB);
        CHECK(code[6] == 0xCD);
        CHECK(code[7] == 0xEF);
    }
}

TEST_CASE("smpte_fps_classification") {
    uint8_t flags[2] = {9, 9};
    smpteFpsFlags(24.0, flags);
    CHECK(flags[0] == 1);
    CHECK(flags[1] == 0);
    smpteFpsFlags(24000.0 / 1001.0, flags);
    CHECK(flags[0] == 1);
    CHECK(flags[1] == 0);
    smpteFpsFlags(25.0, flags);
    CHECK(flags[0] == 0);
    CHECK(flags[1] == 0);
    smpteFpsFlags(30000.0 / 1001.0, flags);
    CHECK(flags[0] == 0);
    CHECK(flags[1] == 1);
    smpteFpsFlags(30.0, flags);
    CHECK(flags[0] == 0);
    CHECK(flags[1] == 0);
    // An unclassifiable rate: no flag, never a guess.
    smpteFpsFlags(17.5, flags);
    CHECK(flags[0] == 0);
    CHECK(flags[1] == 0);
}

// ---- 3. DngSequenceWriter ----

TEST_CASE("sequence_plan_naming_and_validity") {
    DngSequencePlan plan;
    CHECK(plan.fileName(0) == "clip_000000.dng");
    CHECK(plan.fileName(42) == "clip_000042.dng");
    CHECK(plan.fileName(999999) == "clip_999999.dng");
    // The 999999 boundary: 7 digits stay 6-wide only when the number
    // fits; a bigger number widens (snprintf %0*zu widens, never
    // truncates — the ordering stays numeric).
    CHECK(plan.fileName(1000000) == "clip_1000000.dng");
    CHECK(plan.fileName(1234567) == "clip_1234567.dng");

    plan.naming.prefix = "shot";
    plan.naming.frameDigits = 4;
    CHECK(plan.fileName(7) == "shot_0007.dng");

    std::string why;
    plan.outputDir = "/tmp";
    CHECK(plan.valid(why));

    DngSequencePlan bad;
    bad.outputDir = "/tmp";
    bad.fpsNum = 0;
    CHECK(!bad.valid(why));
    CHECK(why.find("fps") != std::string::npos);

    DngSequencePlan badDigits = bad;
    badDigits.fpsNum = 30;
    badDigits.naming.frameDigits = 0;
    CHECK(!badDigits.valid(why));

    DngSequencePlan badPrefix = badDigits;
    badPrefix.naming.frameDigits = 6;
    badPrefix.naming.prefix = "a/b";
    CHECK(!badPrefix.valid(why));

    DngSequencePlan badTc = badPrefix;
    badTc.naming.prefix = "clip";
    badTc.startTimecode.minute = 99;
    CHECK(!badTc.valid(why));

    DngSequencePlan noDir = badTc;
    noDir.startTimecode.minute = 0;
    noDir.outputDir.clear();
    CHECK(!noDir.valid(why));
}

TEST_CASE("sequence_timecode_per_frame") {
    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.fpsNum = 30000;
    plan.fpsDen = 1001;
    plan.startTimecode = TimecodeStart{0, 0, 0, 0};
    uint8_t code[8] = {0};

    // Frame 0: the start code itself (drop-frame flag from 29.97).
    plan.timeCodesOf(0, code);
    CHECK(code[4] == 1);
    CHECK((code[0] & 0x7f) == 0);
    CHECK(code[3] == 0);

    // Frame 30: 29.97 rolls the frame field at 30; frame 30 is second 1.
    plan.timeCodesOf(30, code);
    CHECK((code[2] & 0x7f) == 1);
    CHECK((code[3] & 0x7f) == 0);
}

TEST_CASE("sequence_writes_frames_naming_progress") {
    // A 3-frame sequence: names, content order, progress counts, and
    // the per-frame TimeCodes/FrameRate in each written file.
    const RawMeta meta = seqMeta();

    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.fpsNum = 25;
    plan.fpsDen = 1;
    plan.frameCount = 3;

    const FrameInfo frame = seqFrame();
    std::vector<std::vector<uint16_t>> pixels = {
        seqPixels(0), seqPixels(1), seqPixels(2)};

    std::vector<std::pair<size_t, size_t>> progress;
    const SequenceResult res = writeDngSequence(
        plan, meta, DngExtraMeta{},
        [&](size_t i, SequenceFrameData& out, int&, std::string&, std::string&) {
            out.cfa = pixels[i];
            out.frame = frame;
            return true;
        },
        {},
        [&](size_t done, size_t total) { progress.emplace_back(done, total); });

    REQUIRE(res.state == SequenceState::Complete);
    CHECK(res.framesWritten == 3);
    CHECK(res.framesTotal == 3);
    REQUIRE(res.writtenPaths.size() == 3);
    CHECK(res.writtenPaths[0] == "/tmp/clip_000000.dng");
    CHECK(res.writtenPaths[1] == "/tmp/clip_000001.dng");
    CHECK(res.writtenPaths[2] == "/tmp/clip_000002.dng");
    REQUIRE(progress.size() == 3);
    CHECK(progress[0] == std::pair<size_t, size_t>{1, 3});
    CHECK(progress[2] == std::pair<size_t, size_t>{3, 3});

    for (size_t i = 0; i < 3; ++i) {
        const std::vector<uint8_t> b = readFile(res.writtenPaths[i].c_str());
        const auto tags = parseIfd(b);
        // The base tags all present and correct.
        CHECK(tagShort(tags, 262) == 32803);
        CHECK(tagLong(tags, 279) == 64u * 48u * 2u);
        CHECK(tagLong(tags, 50717) == 1023u);
        // The sequence tags: this frame's code and the exact rational.
        const Tag tc = get(tags, 51043);
        CHECK(tc.count == 8);
        CHECK((tc.value[3] & 0x7f) == i); // frame field = i
        const Tag fr = get(tags, 51044);
        CHECK(rd32(fr.value, 0) == 25u);
        CHECK(rd32(fr.value, 4) == 1u);
        // The CFA data is byte-identical to the frame the provider
        // handed over (the source pixels, verbatim).
        const uint32_t stripOff = tagLong(tags, 273);
        CHECK(std::memcmp(b.data() + stripOff, pixels[i].data(),
                          pixels[i].size() * 2) == 0);
        std::remove(res.writtenPaths[i].c_str());
    }
}

TEST_CASE("sequence_cancel_preserves_written_frames") {
    // A synthetic cancel after frame 1: frames 0 and 1 STAY on disk,
    // the state is Cancelled, the written list names exactly them —
    // never a silent delete (the plan's partial-result rule).
    const RawMeta meta = seqMeta();
    const FrameInfo frame = seqFrame();

    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.frameCount = 5;

    std::atomic<int> provided{0};
    const SequenceResult res = writeDngSequence(
        plan, meta, DngExtraMeta{},
        [&](size_t i, SequenceFrameData& out, int&, std::string&, std::string&) {
            out.cfa = seqPixels(i);
            out.frame = frame;
            provided.fetch_add(1);
            return true;
        },
        /*shouldCancel=*/[&provided]() { return provided.load() >= 2; });

    REQUIRE(res.state == SequenceState::Cancelled);
    CHECK(res.framesWritten == 2);
    CHECK(res.framesTotal == 5);
    REQUIRE(res.writtenPaths.size() == 2);
    // The frames are on disk and readable (preserved, not deleted).
    for (const std::string& p : res.writtenPaths) {
        std::ifstream in(p);
        CHECK(in.good());
        std::remove(p.c_str());
    }
}

TEST_CASE("sequence_failure_preserves_written_frames") {
    // Frame 2's decode fails: frames 0-1 stay, state Failed, the
    // stage/code/detail name the failure honestly.
    const RawMeta meta = seqMeta();
    const FrameInfo frame = seqFrame();

    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.frameCount = 4;

    const SequenceResult res = writeDngSequence(
        plan, meta, DngExtraMeta{},
        [&](size_t i, SequenceFrameData& out, int& code, std::string& stage,
            std::string& detail) {
            if (i == 2) {
                code = static_cast<int>(ErrorCode::Truncated);
                stage = "frame read";
                detail = "frame 2 decode failed";
                return false;
            }
            out.cfa = seqPixels(i);
            out.frame = frame;
            return true;
        });

    REQUIRE(res.state == SequenceState::Failed);
    CHECK(res.framesWritten == 2);
    CHECK(res.failCode == static_cast<int>(ErrorCode::Truncated));
    CHECK(res.failStage == "frame read");
    CHECK(res.failDetail == "frame 2 decode failed");
    for (const std::string& p : res.writtenPaths) {
        std::ifstream in(p);
        CHECK(in.good());
        std::remove(p.c_str());
    }
}

TEST_CASE("sequence_zero_frames_is_complete_empty") {
    const RawMeta meta = seqMeta();
    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.frameCount = 0;

    const SequenceResult res = writeDngSequence(
        plan, meta, DngExtraMeta{},
        [](size_t, SequenceFrameData&, int&, std::string&, std::string&) {
            return true; // never called (frameCount 0)
        });
    CHECK(res.state == SequenceState::Complete);
    CHECK(res.framesWritten == 0);
    CHECK(res.writtenPaths.empty());
}

TEST_CASE("sequence_single_frame_and_invalid_plan") {
    const RawMeta meta = seqMeta();
    const FrameInfo frame = seqFrame();

    // Single frame: 1 written, names the 000000 slot.
    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.frameCount = 1;
    const SequenceResult ok = writeDngSequence(
        plan, meta, DngExtraMeta{},
        [&](size_t, SequenceFrameData& out, int&, std::string&, std::string&) {
            out.cfa = seqPixels(0);
            out.frame = frame;
            return true;
        });
    REQUIRE(ok.state == SequenceState::Complete);
    CHECK(ok.framesWritten == 1);
    CHECK(ok.writtenPaths[0] == "/tmp/clip_000000.dng");
    std::remove("/tmp/clip_000000.dng");

    // An invalid plan (no provider / zero fps): Failed before any file.
    DngSequencePlan bad;
    bad.outputDir = "/tmp";
    bad.frameCount = 3;
    bad.fpsNum = 0;
    const SequenceResult badRes = writeDngSequence(
        bad, meta, DngExtraMeta{},
        [&](size_t, SequenceFrameData&, int&, std::string&, std::string&) {
            return true;
        });
    CHECK(badRes.state == SequenceState::Failed);
    CHECK(badRes.failStage == "sequence plan");
    CHECK(badRes.writtenPaths.empty());

    // A missing provider is honest too.
    const SequenceResult noProv = writeDngSequence(
        plan, meta, DngExtraMeta{}, nullptr);
    CHECK(noProv.state == SequenceState::Failed);
    CHECK(noProv.writtenPaths.empty());
}

TEST_CASE("sequence_naming_at_999999_boundary_ordering") {
    // The written file names at the 999999 boundary sort NUMERICALLY
    // (the reader's frameNumberOf rule): the 7-digit frame after the
    // 6-digit one, never lexicographic (clip_1000000 < clip_999999 as
    // strings — the wrong order a string sort would give).
    DngSequencePlan plan;
    plan.outputDir = "/tmp";
    plan.frameCount = 3;
    // A wide frame count cannot be written for real (1000000 frames);
    // the boundary is proven on the NAME plan only — the names of
    // frames 999998, 999999 and 1000000 in ascending numeric order.
    CHECK(plan.fileName(999998) == "clip_999998.dng");
    CHECK(plan.fileName(999999) == "clip_999999.dng");
    CHECK(plan.fileName(1000000) == "clip_1000000.dng");

    // Numeric-order proof: extract the numbers with the reader-side
    // rule (trailing digit run) and check ascending.
    const size_t idx[3] = {999998, 999999, 1000000};
    unsigned long long prev = 0;
    for (size_t k = 0; k < 3; ++k) {
        const std::string name = plan.fileName(idx[k]);
        const size_t us = name.rfind('_');
        REQUIRE(us != std::string::npos);
        unsigned long long n = 0;
        for (size_t p = us + 1; p < name.size() && name[p] != '.'; ++p) {
            REQUIRE(name[p] >= '0');
            REQUIRE(name[p] <= '9');
            n = n * 10ull + static_cast<unsigned long long>(name[p] - '0');
        }
        CHECK(n > prev);
        prev = n;
    }
}

// ---- 4. WavWriter ----

TEST_CASE("wav_header_field_by_field") {
    // 4 interleaved stereo samples at 48000 Hz: the header checked
    // byte by byte against the canonical 44-byte layout.
    std::vector<int16_t> pcm = {100, -100, 200, -200, 300, -300, 400, -400};
    REQUIRE(writeWav("/tmp/seq-audio.wav", pcm, 48000, 2).isOk());

    const std::vector<uint8_t> b = readFile("/tmp/seq-audio.wav");
    REQUIRE(b.size() == 44 + pcm.size() * 2);

    // RIFF header.
    CHECK(std::memcmp(b.data() + 0, "RIFF", 4) == 0);
    CHECK(rd32(b, 4) == 36u + pcm.size() * 2);
    CHECK(std::memcmp(b.data() + 8, "WAVE", 4) == 0);

    // fmt chunk: PCM, 2 channels, 48000 Hz, byteRate, blockAlign, bits.
    CHECK(std::memcmp(b.data() + 12, "fmt ", 4) == 0);
    CHECK(rd32(b, 16) == 16u);             // fmt size
    CHECK(rd16(b, 20) == 1);               // PCM
    CHECK(rd16(b, 22) == 2);               // channels
    CHECK(rd32(b, 24) == 48000u);          // sample rate
    CHECK(rd32(b, 28) == 48000u * 2 * 2);  // byte rate
    CHECK(rd16(b, 32) == 4);               // block align
    CHECK(rd16(b, 34) == 16);              // bits per sample

    // data chunk + verbatim samples.
    CHECK(std::memcmp(b.data() + 36, "data", 4) == 0);
    CHECK(rd32(b, 40) == pcm.size() * 2);
    CHECK(std::memcmp(b.data() + 44, pcm.data(), pcm.size() * 2) == 0);

    std::remove("/tmp/seq-audio.wav");
}

TEST_CASE("wav_range_slice_and_mono") {
    // The range slice: start 2, end 6 (half-open) of a mono stream —
    // exactly samples 2..5, byte-identical, and no resampling (the
    // rate travels verbatim even for an odd one).
    std::vector<int16_t> pcm(8);
    for (int i = 0; i < 8; ++i) pcm[i] = static_cast<int16_t>(1000 + i);

    WavRange range;
    range.startSample = 2;
    range.endSample = 6;
    REQUIRE(writeWav("/tmp/seq-slice.wav", pcm, 44100, 1, range).isOk());
    const std::vector<uint8_t> b = readFile("/tmp/seq-slice.wav");
    CHECK(rd32(b, 24) == 44100u);
    CHECK(rd16(b, 22) == 1);
    CHECK(rd32(b, 40) == 4 * 2);
    for (int i = 0; i < 4; ++i)
        CHECK(rd16(b, 44 + i * 2) == pcm[2 + i]);
    std::remove("/tmp/seq-slice.wav");

    // endSample = 0: the whole tail from startSample.
    WavRange tail;
    tail.startSample = 6;
    REQUIRE(writeWav("/tmp/seq-tail.wav", pcm, 16000, 1, tail).isOk());
    const std::vector<uint8_t> tb = readFile("/tmp/seq-tail.wav");
    CHECK(rd32(tb, 40) == 2 * 2);
    std::remove("/tmp/seq-tail.wav");
}

TEST_CASE("wav_sizes_and_saturation") {
    // riffSize = 36 + dataBytes, uint32 fields, saturation at the
    // spec's 4 GiB ceiling (honest, never a silent wrap).
    WavSizes small = wavSizes(0);
    CHECK(small.riffSize == 36u);
    CHECK(small.dataSize == 0u);
    CHECK(!small.saturated);

    WavSizes one = wavSizes(2);
    CHECK(one.riffSize == 38u);
    CHECK(one.dataSize == 2u);

    // One byte under the ceiling: fits exactly, not saturated.
    WavSizes atEdge = wavSizes(0xffffffffull - 36ull);
    CHECK(atEdge.riffSize == 0xffffffffu);
    CHECK(!atEdge.saturated);

    // Past the ceiling: capped (never wrapped).
    WavSizes past = wavSizes(0x100000000ull - 36ull);
    CHECK(past.riffSize == 0xffffffffu);
    CHECK(past.saturated);
}

TEST_CASE("wav_rejects_bad_and_empty_range") {
    std::vector<int16_t> pcm(4, 42);

    CHECK(!writeWav("/tmp/seq-bad.wav", pcm, 0, 2).isOk());   // rate 0
    CHECK(!writeWav("/tmp/seq-bad.wav", pcm, 48000, 0).isOk()); // channels 0
    CHECK(!writeWav("/tmp/seq-bad.wav", pcm, 48000, 2, WavRange{10, 0}).isOk()); // start past end

    // A range that cuts a stereo frame in half is refused (honest,
    // never a truncated channel).
    WavRange cuts;
    cuts.startSample = 1;
    cuts.endSample = 4; // 3 samples of stereo
    CHECK(!writeWav("/tmp/seq-bad.wav", pcm, 48000, 2, cuts).isOk());

    std::ifstream in("/tmp/seq-bad.wav");
    CHECK(!in.good()); // nothing left behind
}

TEST_CASE("wav_zero_and_huge_sample_counts") {
    // Zero samples (a clip with no audio): a 44-byte header-only file
    // is legal and honest (the caller decides whether to write one).
    std::vector<int16_t> empty;
    REQUIRE(writeWav("/tmp/seq-empty.wav", empty, 48000, 2).isOk());
    CHECK(readFile("/tmp/seq-empty.wav").size() == 44);
    std::remove("/tmp/seq-empty.wav");

    // A huge data size: the SIZE MATH saturates at the WAV spec's 4 GiB
    // ceiling (pure helpers only — no 4 GiB file is written by a test).
    const size_t hugeBytes = 0x1'0000'0000ull; // 4 GiB of samples
    const WavSizes huge = wavSizes(hugeBytes);
    CHECK(huge.saturated);
    CHECK(huge.riffSize == 0xffffffffu);
    // An empty vector's range helpers are 0 (no bytes carried).
    CHECK(wavRangeDataBytes(empty, WavRange{}) == 0);
    CHECK(wavRangeSampleCount(empty, WavRange{10, 0}) == 0);
}
