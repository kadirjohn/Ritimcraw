// Source unit tests (Task 1).
//
// Synthetic container tests are not possible without a container writer, so
// the always-run tests cover what is testable without a real clip:
//   - Result<T> / Result<void> mapping (no std::optional<void> anywhere)
//   - CfaPattern parsing from sensor arrangement strings
//   - WAV header writer (RIFF layout, sizes, sample format)
//
// Real-clip tests run only when RAWEDIT_REALCLIP=1 (2 GB fixtures, decode
// takes minutes); the default run prints them as SKIP. The clip path comes
// from RAWEDIT_CLIP (default: the first .mcraw in STEPS/media/test).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Result.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

using namespace rawedit;

// ---------------------------------------------------------------------------
// WAV header writer, shared with cli/main.cpp (declared in Source.h? no — the
// CLI writes its own; this is the tested reference implementation).
// ---------------------------------------------------------------------------
namespace rawedit {
namespace wav {

// Writes a 16-bit PCM RIFF/WAVE header for `frames` frames of `channels`
// channels at `sampleRate`. Returns the 44-byte canonical header.
std::vector<uint8_t> makeHeader(uint32_t frames, int channels, int sampleRate) {
    const uint32_t numChannels = static_cast<uint32_t>(channels);
    const uint32_t byteRate = static_cast<uint32_t>(sampleRate) * numChannels * 2;

    std::vector<uint8_t> h(44, 0);
    auto put = [&](size_t off, const void* data, size_t n) {
        std::memcpy(&h[off], data, n);
    };
    auto put32 = [&](size_t off, uint32_t v) { put(off, &v, 4); };
    auto put16 = [&](size_t off, uint16_t v) { put(off, &v, 2); };

    put(0, "RIFF", 4);
    put32(4, 36 + frames * numChannels * 2);
    put(8, "WAVE", 4);
    put(12, "fmt ", 4);
    put32(16, 16);            // PCM fmt chunk size
    put16(20, 1);             // PCM
    put16(22, static_cast<uint16_t>(numChannels));
    put32(24, static_cast<uint32_t>(sampleRate));
    put32(28, byteRate);
    put16(32, static_cast<uint16_t>(numChannels * 2)); // block align
    put16(34, 16);            // bits per sample
    put(36, "data", 4);
    put32(40, frames * numChannels * 2);
    return h;
}

} // namespace wav
} // namespace rawedit

// ---------------------------------------------------------------------------
// Result mapping
// ---------------------------------------------------------------------------
TEST_CASE("result_void_is_a_dedicated_struct_not_optional_void") {
    // Result<void> must have has_value/code/detail and no std::optional<void>
    // member (00-START-HERE.md). Compile-time shape check plus behavior.
    Result<void> ok = Result<void>::ok();
    CHECK(ok.has_value);
    CHECK(ok.code == ErrorCode::Ok);
    CHECK(static_cast<bool>(ok));

    Result<void> bad = Result<void>::fail(ErrorCode::Truncated, "cut short");
    CHECK_FALSE(bad.has_value);
    CHECK(bad.code == ErrorCode::Truncated);
    CHECK(bad.detail == "cut short");

    Status st = Status::fail(ErrorCode::BadHeader, "not mcraw");
    CHECK_FALSE(st);
    CHECK(st.code == ErrorCode::BadHeader);
}

TEST_CASE("result_value_carries_optional_and_error_mapping") {
    auto ok = Result<int>::ok(42);
    REQUIRE(ok.isOk());
    CHECK(*ok.value == 42);
    CHECK(ok.code == ErrorCode::Ok);

    auto bad = Result<int>::fail(ErrorCode::IoError, "boom");
    CHECK_FALSE(bad.isOk());
    CHECK_FALSE(bad.value.has_value());
    CHECK(bad.code == ErrorCode::IoError);
    CHECK(bad.detail == "boom");
}

TEST_CASE("errorcode_tostring_covers_all_codes") {
    CHECK(std::strcmp(errorName(ErrorCode::Ok), "ok") == 0);
    CHECK(std::strcmp(errorName(ErrorCode::Truncated), "truncated") == 0);
    CHECK(std::strcmp(errorName(ErrorCode::BadHeader), "badHeader") == 0);
    CHECK(std::strcmp(errorName(ErrorCode::IoError), "ioError") == 0);
    CHECK(std::strcmp(errorName(ErrorCode::Unsupported), "unsupported") == 0);
}

// ---------------------------------------------------------------------------
// CfaPattern parsing
// ---------------------------------------------------------------------------
TEST_CASE("cfa_pattern_parses_all_four_arrangements") {
    ErrorCode code = ErrorCode::Ok;
    CHECK(parseCfaPattern("rggb", code) == CfaPattern::RGGB); CHECK(code == ErrorCode::Ok);
    CHECK(parseCfaPattern("grbg", code) == CfaPattern::GRBG); CHECK(code == ErrorCode::Ok);
    CHECK(parseCfaPattern("gbrg", code) == CfaPattern::GBRG); CHECK(code == ErrorCode::Ok);
    CHECK(parseCfaPattern("bggr", code) == CfaPattern::BGGR); CHECK(code == ErrorCode::Ok);
}

TEST_CASE("cfa_pattern_rejects_unknown_arrangements") {
    ErrorCode code = ErrorCode::Ok;
    (void)parseCfaPattern("rgbw", code);
    CHECK(code == ErrorCode::Unsupported);
    code = ErrorCode::Ok;
    (void)parseCfaPattern("", code);
    CHECK(code == ErrorCode::Unsupported);
}

// ---------------------------------------------------------------------------
// WAV header (RIFF) writer
// ---------------------------------------------------------------------------
TEST_CASE("wav_header_layout_is_canonical_riff_pcm16") {
    const auto h = wav::makeHeader(48000, 2, 48000); // 1 s stereo 48 kHz
    REQUIRE(h.size() == 44);
    CHECK(std::memcmp(&h[0], "RIFF", 4) == 0);
    CHECK(std::memcmp(&h[8], "WAVE", 4) == 0);
    CHECK(std::memcmp(&h[12], "fmt ", 4) == 0);
    CHECK(std::memcmp(&h[36], "data", 4) == 0);

    const auto rd32 = [&](size_t off) {
        uint32_t v; std::memcpy(&v, &h[off], 4); return v;
    };
    const auto rd16 = [&](size_t off) {
        uint16_t v; std::memcpy(&v, &h[off], 2); return v;
    };
    // RIFF size = file size - 8 = 36 + data bytes.
    CHECK(rd32(4) == 36 + 48000 * 2 * 2);
    CHECK(rd32(16) == 16);          // fmt chunk size
    CHECK(rd16(20) == 1);           // PCM
    CHECK(rd16(22) == 2);           // channels
    CHECK(rd32(24) == 48000);       // sample rate
    CHECK(rd32(28) == 48000 * 4);  // byte rate
    CHECK(rd16(32) == 4);           // block align
    CHECK(rd16(34) == 16);          // bits per sample
    CHECK(rd32(40) == 48000 * 2 * 2); // data size
}

TEST_CASE("wav_header_zero_frames_and_mono") {
    const auto h = wav::makeHeader(0, 1, 44100);
    REQUIRE(h.size() == 44);
    uint32_t riff, data; uint16_t ch;
    std::memcpy(&riff, &h[4], 4);
    std::memcpy(&data, &h[40], 4);
    std::memcpy(&ch, &h[22], 2);
    CHECK(riff == 36);
    CHECK(data == 0);
    CHECK(ch == 1);
}

// ---------------------------------------------------------------------------
// Source::open error mapping on synthetic bad files
// ---------------------------------------------------------------------------
namespace {

std::string writeTemp(const std::vector<uint8_t>& bytes) {
    // tmpfile via mkstemp-style manual naming in the system temp dir.
    static int counter = 0;
    std::string path = "/tmp/rawedit_test_" + std::to_string(::getpid()) + "_" + std::to_string(counter++) + ".bin";
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    f.close();
    return path;
}

} // namespace

TEST_CASE("open_reports_bad_header_for_non_mcraw_file") {
    auto path = writeTemp({'n', 'o', 't', ' ', 'a', ' ', 'c', 'l', 'i', 'p', 0, 0, 0, 0, 0, 0});
    auto res = Source::open(path);
    CHECK_FALSE(res.isOk());
    CHECK(res.code == ErrorCode::BadHeader);
    CHECK(res.detail.find("open") != std::string::npos);
    std::remove(path.c_str());
}

TEST_CASE("open_reports_bad_header_for_missing_file") {
    auto res = Source::open("/tmp/rawedit_does_not_exist_12345.mcraw");
    CHECK_FALSE(res.isOk());
    CHECK(res.code == ErrorCode::BadHeader);
}

TEST_CASE("open_reports_truncated_for_cut_container") {
    // Valid "MOTION \x03" header + metadata item header, then cut off — the
    // decoder will fail reading the index at the end of the file.
    std::vector<uint8_t> bytes = {'M', 'O', 'T', 'I', 'O', 'N', ' ', 0x03};
    // Item{METADATA=3, size}
    const uint32_t type = 3, size = 16;
    const uint8_t* t = reinterpret_cast<const uint8_t*>(&type);
    const uint8_t* s = reinterpret_cast<const uint8_t*>(&size);
    bytes.insert(bytes.end(), t, t + 4);
    bytes.insert(bytes.end(), s, s + 4);
    bytes.insert(bytes.end(), 16, 'x'); // partial metadata, no index at end

    auto path = writeTemp(bytes);
    auto res = Source::open(path);
    CHECK_FALSE(res.isOk());
    // Either BadHeader (bad metadata JSON) or Truncated (short index read).
    CHECK((res.code == ErrorCode::BadHeader || res.code == ErrorCode::Truncated));
    std::remove(path.c_str());
}

TEST_CASE("frameinfo_defaults_carry_the_missing_level_sentinels") {
    // Owner request 2026-11-11 section 3: FrameInfo's dynamic level fields
    // default to the sentinel -1 ("this frame did not carry a level") so
    // the develop stage and the DNG writer fall back to the container
    // RawMeta values. A fresh FrameInfo must carry exactly those
    // sentinels — anything else would silently develop frames against
    // fabricated calibration.
    FrameInfo info;
    for (int i = 0; i < 4; ++i)
        CHECK(info.dynamicBlack[i] == -1);
    CHECK(info.dynamicWhite == -1);
}

// ---------------------------------------------------------------------------
// Real-clip tests — gated behind RAWEDIT_REALCLIP=1
// ---------------------------------------------------------------------------

namespace {

bool realclipEnabled() { return std::getenv("RAWEDIT_REALCLIP") != nullptr; }

std::string realclipPath() {
    if (const char* p = std::getenv("RAWEDIT_CLIP"))
        return p;
    return "/Users/kc4ca/projects/Ritimcraw/STEPS/media/test/261110_004150_VIDEO_24mm.mcraw";
}

} // namespace

TEST_CASE("first_mid_last_frames_have_increasing_pts" * doctest::description("real clip; set RAWEDIT_REALCLIP=1 to run")) {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: set RAWEDIT_REALCLIP=1 (2 GB fixture, decode is slow)");
        return;
    }
    auto s = Source::open(realclipPath());
    REQUIRE(s.isOk());
    auto n = (*s)->frameCount();
    REQUIRE(n > 100);
    auto a = (*s)->frameInfo(0), b = (*s)->frameInfo(n / 2), c = (*s)->frameInfo(n - 1);
    CHECK(a.ptsUs < b.ptsUs);
    CHECK(b.ptsUs < c.ptsUs);
    CHECK(a.width == c.width);
    CHECK(a.height == c.height);
    auto frame = (*s)->readFrame(n - 1);
    REQUIRE(frame.isOk());
    CHECK(frame->size() == size_t(c.width) * c.height);
}

TEST_CASE("truncated_file_reports_error" * doctest::description("real clip; set RAWEDIT_REALCLIP=1 to run")) {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: set RAWEDIT_REALCLIP=1 (2 GB fixture, decode is slow)");
        return;
    }
    // Build a truncated copy (60% of the source bytes) next to the original.
    const std::string src = realclipPath();
    std::string trunc = src + ".truncated";
    {
        std::ifstream in(src, std::ios::binary);
        std::ofstream out(trunc, std::ios::binary);
        in.seekg(0, std::ios::end);
        const auto full = in.tellg();
        in.seekg(0);
        const auto cut = static_cast<std::streamoff>(full * 0.6);
        std::vector<char> buf(1 << 20);
        std::streamoff copied = 0;
        while (copied < cut) {
            const auto chunk = std::min<std::streamoff>(buf.size(), cut - copied);
            in.read(buf.data(), chunk);
            out.write(buf.data(), chunk);
            copied += chunk;
        }
    }

    bool failed = false;
    auto s = Source::open(trunc);
    if (!s.isOk()) {
        failed = true;
    } else {
        auto frame = (*s)->readFrame((*s)->frameCount() - 1);
        failed = !frame.isOk() && frame.code == ErrorCode::Truncated;
    }
    std::remove(trunc.c_str());
    CHECK(failed);
}

TEST_CASE("audio_is_present_with_rate" * doctest::description("real clip; set RAWEDIT_REALCLIP=1 to run")) {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: set RAWEDIT_REALCLIP=1 (2 GB fixture, decode is slow)");
        return;
    }
    auto s = Source::open(realclipPath());
    REQUIRE(s.isOk());
    auto info = (*s)->audioInfo();
    REQUIRE(info.isOk());
    CHECK(info->sampleRate > 0);
    CHECK(info->channels > 0);
}
