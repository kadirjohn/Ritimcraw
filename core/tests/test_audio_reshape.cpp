// E5-B audio reshape tests (night G1): the 16-bit whole-buffer boundary
// over the planar-f32 E5-B cores (StreamResampler + convertChannels).
//
// What these cases pin that test_resample does NOT (it owns the f32
// core): the 16-bit IDENTITY CONTRACT (a SOURCE request copies the
// buffer bit-identically — no f32 round trip, no requantize), the
// output shapes (frames follow the rates; samples follow the channels),
// and the hostile shapes' honest passthrough. The f32 cores' own
// behaviour (block independence, the exact rational phase, the BS.775
// coefficients) is test_resample's territory and is NOT repeated here.
//
// Bit-exactness pins use memcmp (tolerance 0), the D2 chain precedent.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/AudioReshape.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

/// A deterministic stereo sine buffer: L = sin, R = 0.7 * sin(phase+).
std::vector<int16_t> stereoSine(size_t frames, double hz, double rate,
                                double amp = 12000.0) {
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        const double t = 2.0 * kPi * hz * static_cast<double>(i) / rate;
        pcm[i * 2] = static_cast<int16_t>(std::lround(amp * std::sin(t)));
        pcm[i * 2 + 1] = static_cast<int16_t>(std::lround(amp * 0.7 * std::sin(t + 0.5)));
    }
    return pcm;
}

bool bitEqual(const std::vector<int16_t> &a, const std::vector<int16_t> &b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * 2) == 0;
}

} // namespace

// ---- the IDENTITY CONTRACT: SOURCE settings copy untouched ----
TEST_CASE("identity: no rate and no channel change copies bit-identically") {
    const auto pcm = stereoSine(1000, 997.0, 48000.0);
    const auto r = rawedit::reshapePcm(pcm, 48000, 2, 0, 0);
    CHECK(bitEqual(pcm, r.pcm));
    CHECK(r.frames == 1000);
}

TEST_CASE("identity: explicit same rate and same channels also copy untouched") {
    const auto pcm = stereoSine(500, 440.0, 44100.0);
    const auto r = rawedit::reshapePcm(pcm, 44100, 2, 44100, 2);
    CHECK(bitEqual(pcm, r.pcm));
}

// ---- hostile shapes: honest passthrough, never a guess ----
TEST_CASE("hostile: zero frames and bad shapes pass the buffer through") {
    const auto pcm = stereoSine(10, 100.0, 48000.0);
    // zero frames: nothing claimed
    auto r = rawedit::reshapePcm(pcm.data(), 0, 48000, 2, 48000, 1);
    CHECK(r.pcm.empty());
    CHECK(r.frames == 0);
    // bad rate: copied, shape as given
    r = rawedit::reshapePcm(pcm.data(), 10, 0, 2, 48000, 1);
    CHECK(bitEqual(pcm, r.pcm));
    CHECK(r.frames == 10);
    // bad channels: the honest shape keeps the buffer's own stride (the
    // copy clamps the stride to 1 — the caller's gate owns the mistake,
    // the core never guesses a layout it was not told)
    r = rawedit::reshapePcm(pcm.data(), 10, 48000, 0, 48000, 1);
    CHECK(r.pcm.size() == 10); // frames * ch with ch clamped to 1
    CHECK(r.frames == 10);
    // a null buffer with claimed frames: empty, never a read
    r = rawedit::reshapePcm(nullptr, 10, 48000, 2, 48000, 1);
    CHECK(r.pcm.empty());
}

// ---- remix: shapes and the BS.775 fold at the 16-bit boundary ----
TEST_CASE("stereo->mono halves the samples; the fold is BS.775 [k, k]") {
    // 200 frames of pure L (R = 0): mono = k*L, the -3.01 dB fold.
    const size_t frames = 200;
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        pcm[i * 2] = static_cast<int16_t>(i % 1000);
        pcm[i * 2 + 1] = 0;
    }
    const auto r = rawedit::reshapePcm(pcm, 48000, 2, 0, 1);
    REQUIRE(r.pcm.size() == frames);
    REQUIRE(r.frames == frames);
    // The first source frame of the interleaved stereo copy at i=1: the
    // mono sample is k * 1/32768 * 32768 rounded — a fixed number.
    const double k = 0.70710678118654752440;
    for (size_t i = 0; i < frames; ++i) {
        const double want = std::lround(k * static_cast<double>(pcm[i * 2]));
        CHECK(r.pcm[i] == static_cast<int16_t>(want));
    }
}

TEST_CASE("mono->stereo doubles the samples; dual mono is the exact copy") {
    const size_t frames = 128;
    std::vector<int16_t> pcm(frames);
    for (size_t i = 0; i < frames; ++i) pcm[i] = static_cast<int16_t>(i * 31);
    const auto r = rawedit::reshapePcm(pcm, 48000, 1, 0, 2);
    REQUIRE(r.pcm.size() == frames * 2);
    REQUIRE(r.frames == frames);
    for (size_t i = 0; i < frames; ++i) {
        // The unity [1; 1] rows copy the sample to both channels; the
        // lround(x * 32768 / 32768 * ...) chain returns it unchanged
        // (norm then toPcm16 is the identity on 16-bit values).
        CHECK(r.pcm[i * 2] == pcm[i]);
        CHECK(r.pcm[i * 2 + 1] == pcm[i]);
    }
}

TEST_CASE("out-of-phase stereo folds to silence in mono") {
    const size_t frames = 300;
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        const double t = 2.0 * kPi * 997.0 * static_cast<double>(i) / 48000.0;
        pcm[i * 2] = static_cast<int16_t>(std::lround(8000.0 * std::sin(t)));
        pcm[i * 2 + 1] = static_cast<int16_t>(std::lround(-8000.0 * std::sin(t)));
    }
    const auto r = rawedit::reshapePcm(pcm, 48000, 2, 0, 1);
    for (int16_t x : r.pcm) CHECK(x == 0); // k*(L + -L) = 0 every frame
}

// ---- resample: the output frame count follows the rates (plan §4.3) ----
TEST_CASE("44.1 -> 48 grows the frame count by the rate ratio") {
    const size_t frames = 44100; // 1 s at 44.1 kHz
    const auto pcm = stereoSine(frames, 1000.0, 44100.0);
    const auto r = rawedit::reshapePcm(pcm, 44100, 2, 48000, 0);
    REQUIRE(r.pcm.size() == 48000 * 2);
    REQUIRE(r.frames == 48000);
}

TEST_CASE("48 -> 44.1 shrinks the frame count by the rate ratio") {
    const size_t frames = 48000; // 1 s at 48 kHz
    const auto pcm = stereoSine(frames, 1000.0, 48000.0);
    const auto r = rawedit::reshapePcm(pcm, 48000, 2, 44100, 0);
    REQUIRE(r.frames == 44100);
    REQUIRE(r.pcm.size() == 44100 * 2);
}

TEST_CASE("resampled tone keeps its level at the same duration") {
    // A 1 kHz tone at -12 dBFS for 1 s, resampled 48->44.1: the RMS of
    // the interior (skip the one-sample-lag boundary) stays within the
    // linear interpolator's own error — the tone survived the trip.
    const size_t frames = 48000;
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        const double t = 2.0 * kPi * 1000.0 * static_cast<double>(i) / 48000.0;
        const int16_t s = static_cast<int16_t>(std::lround(8000.0 * std::sin(t)));
        pcm[i * 2] = s;
        pcm[i * 2 + 1] = s;
    }
    const auto r = rawedit::reshapePcm(pcm, 48000, 2, 44100, 0);
    double sum = 0.0;
    size_t n = 0;
    for (size_t i = 10; i < r.frames - 10; ++i) { // skip the boundary
        sum += static_cast<double>(r.pcm[i * 2]) * static_cast<double>(r.pcm[i * 2]);
        ++n;
    }
    const double rms = std::sqrt(sum / static_cast<double>(n));
    CHECK(rms == doctest::Approx(8000.0 / std::sqrt(2.0)).epsilon(0.02));
}

// ---- both at once: remix then resample in one call ----
TEST_CASE("stereo 44.1 -> mono 48 lands the requested shape") {
    const size_t frames = 44100;
    const auto pcm = stereoSine(frames, 500.0, 44100.0);
    const auto r = rawedit::reshapePcm(pcm, 44100, 2, 48000, 1);
    REQUIRE(r.frames == 48000);
    REQUIRE(r.pcm.size() == 48000);
}

// ---- determinism: the same request twice is bit-identical ----
TEST_CASE("determinism: two runs of the same reshape are bit-identical") {
    const auto pcm = stereoSine(20000, 440.0, 48000.0);
    const auto a = rawedit::reshapePcm(pcm, 48000, 2, 44100, 1);
    const auto b = rawedit::reshapePcm(pcm, 48000, 2, 44100, 1);
    CHECK(bitEqual(a.pcm, b.pcm));
    CHECK(a.frames == b.frames);
}

// ---- block-boundary honesty: a very short buffer is still finite ----
TEST_CASE("short: one and two frames reshape without crashing") {
    // The frame count follows the rate ratio exactly (plan §4.3's own
    // math): a 1-frame 48 kHz buffer is 1/48000 s, which is 0.92
    // frames at 44.1 kHz — 0 honest frames. Never invented length.
    const auto one = stereoSine(1, 440.0, 48000.0);
    auto r = rawedit::reshapePcm(one, 48000, 2, 44100, 0);
    CHECK(r.frames == 0);
    CHECK(r.pcm.empty());
    // 2 frames -> 1.84 -> 1 frame: the honest count.
    const auto two = stereoSine(2, 440.0, 48000.0);
    r = rawedit::reshapePcm(two, 48000, 2, 44100, 0);
    CHECK(r.frames == 1);
    CHECK(r.pcm.size() == 2);
    // The remix keeps the frame count whatever the length.
    r = rawedit::reshapePcm(one, 48000, 2, 0, 1);
    CHECK(r.frames == 1);
    CHECK(r.pcm.size() == 1);
}
