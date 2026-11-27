// E5-C offline ducking analysis tests (night D1): envelope_db /
// activity / duck_keyframes — a port of filmcraft's
// crates/audio-dsp/src/ducking.rs #[cfg(test)] block (5 tests) with
// the crate's own numbers and tolerances, plus port-level hostile-
// input cases (empty channels, zero-length, huge hop/window). The
// keyframe lists are (time_s, level_db) pairs — the F4 interface the
// plan's §3.5 names. Synthetic only, no cwd needs.
// -ffp-contract=off matches the Ducking.cpp build.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Ducking.h>

#include <cmath>
#include <vector>

namespace {

using rawedit::kDuckingFloorDb;
using rawedit::duck_activity;
using rawedit::duck_envelope_db;
using rawedit::duck_keyframes;
using rawedit::duck_sensitivity_threshold_db;

bool strictly_increasing(const std::vector<std::pair<double, double>> &k) {
    for (std::size_t i = 1; i < k.size(); ++i) {
        if (!(k[i].first > k[i - 1].first)) return false;
    }
    return true;
}

bool same(const std::vector<std::pair<double, double>> &a,
          const std::vector<std::pair<double, double>> &b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (std::fabs(a[i].first - b[i].first) > 1e-9 ||
            std::fabs(a[i].second - b[i].second) > 1e-9) {
            return false;
        }
    }
    return true;
}

} // namespace

// ---- crate ducking.rs vectors (5 tests) ----

TEST_CASE("filmcraft: envelope levels") {
    // crate envelope_levels: 1 s silence + 1 s of a +-0.1 square wave
    // (-20 dBFS RMS); hop 10 ms, window 20 ms -> 200 points.
    const float sr = 1000.0f;
    std::vector<float> x(2000);
    for (std::size_t i = 0; i < 2000; ++i) {
        if (i < 1000) {
            x[i] = 0.0f;
        } else if (i % 2 == 0) {
            x[i] = 0.1f;
        } else {
            x[i] = -0.1f;
        }
    }
    const float *ch[2] = {x.data(), x.data()};
    const std::vector<float> e = duck_envelope_db(ch, 2, x.size(), sr, 0.01f, 0.02f);
    REQUIRE(e.size() == 200);
    CHECK(e[10] == kDuckingFloorDb);
    CHECK(std::fabs(e[150] + 20.0f) < 0.01f);
    // The window straddling the edge is in between.
    CHECK(e[100] < -20.0f);
    CHECK(e[100] > -30.0f);
    // Empty channel set -> no envelope.
    CHECK(duck_envelope_db(nullptr, 0, 0, sr, 0.01f, 0.02f).empty());
}

TEST_CASE("filmcraft: activity bridges then drops blips") {
    // crate activity_bridges_then_drops_blips.
    const float hop = 0.01f;
    std::vector<float> env(300, -80.0f);
    // Speech 0.50-1.00 and 1.10-1.50 (gap 0.1 s -> bridged), blip
    // 2.00-2.03 (dropped).
    for (auto ab : {std::pair<int, int>{50, 100}, {110, 150}, {200, 203}}) {
        for (int i = ab.first; i < ab.second; ++i) env[i] = -20.0f;
    }
    auto r = duck_activity(env, hop, -40.0f, 0.1f, 0.2f);
    REQUIRE(r.size() == 1);
    CHECK(std::fabs(r[0].first - 0.5) < 1e-6);
    CHECK(std::fabs(r[0].second - 1.5) < 1e-6);
    // Without bridging both remain; the blip still goes.
    r = duck_activity(env, hop, -40.0f, 0.1f, 0.0f);
    CHECK(r.size() == 2);
    // A region running to the end is closed at the end.
    std::vector<float> env2(10, -80.0f);
    for (std::size_t i = 5; i < 10; ++i) env2[i] = 0.0f;
    r = duck_activity(env2, 0.1f, -40.0f, 0.0f, 0.0f);
    REQUIRE(r.size() == 1);
    CHECK(std::fabs(r[0].first - 0.5) < 1e-9);
    CHECK(std::fabs(r[0].second - 1.0) < 1e-9);
}

TEST_CASE("filmcraft: sensitivity mapping") {
    // crate sensitivity_mapping.
    CHECK(duck_sensitivity_threshold_db(5.0f) == -40.0f);
    CHECK(duck_sensitivity_threshold_db(0.0f) == -20.0f);
    CHECK(duck_sensitivity_threshold_db(10.0f) == -60.0f);
    CHECK(duck_sensitivity_threshold_db(99.0f) == -60.0f);
}

TEST_CASE("filmcraft: duck keyframes basic and merge") {
    // crate duck_keyframes_basic_and_merge.
    auto k = duck_keyframes({{2.0, 4.0}}, 0.0, 10.0, 0.0, 15.0, 0.5);
    REQUIRE(k.size() == 4);
    CHECK(same(k, {{1.5, 0.0}, {2.0, -15.0}, {4.0, -15.0}, {4.5, 0.0}}));
    // Ramps overlap (4.5 > 4.8 - 0.5 = 4.3) -> stay ducked.
    k = duck_keyframes({{2.0, 4.0}, {4.8, 6.0}}, 0.0, 10.0, -3.0, 12.0, 0.5);
    REQUIRE(k.size() == 4);
    CHECK(same(k, {{1.5, -3.0}, {2.0, -15.0}, {6.0, -15.0}, {6.5, -3.0}}));
    // Separate regions (unsorted input is sorted by start).
    k = duck_keyframes({{5.0, 6.0}, {1.0, 2.0}}, 0.0, 10.0, 0.0, 10.0, 0.25);
    CHECK(k.size() == 8);
    CHECK(strictly_increasing(k));
    // No regions -> no keyframes.
    CHECK(duck_keyframes({}, 0.0, 10.0, 0.0, 10.0, 0.5).empty());
}

TEST_CASE("filmcraft: duck keyframes clamped to clip") {
    // crate duck_keyframes_clamped_to_clip: the edge cases.
    auto k = duck_keyframes({{-1.0, 2.0}}, 0.0, 10.0, 0.0, 10.0, 0.5);
    REQUIRE(k.size() == 3);
    CHECK(same(k, {{0.0, -10.0}, {2.0, -10.0}, {2.5, 0.0}}));
    // Ramp partially before the clip: ramp value at the edge.
    k = duck_keyframes({{0.25, 2.0}}, 0.0, 10.0, 0.0, 10.0, 0.5);
    REQUIRE(k.size() >= 2);
    CHECK(std::fabs(k[0].first - 0.0) < 1e-9);
    CHECK(std::fabs(k[0].second - -5.0) < 1e-9);
    CHECK(std::fabs(k[1].first - 0.25) < 1e-9);
    CHECK(std::fabs(k[1].second - -10.0) < 1e-9);
    // Region runs past the end: ducked at the end edge.
    k = duck_keyframes({{8.0, 12.0}}, 0.0, 10.0, 0.0, 10.0, 0.5);
    REQUIRE(k.size() == 3);
    CHECK(same(k, {{7.5, 0.0}, {8.0, -10.0}, {10.0, -10.0}}));
    // Up-ramp cut by the end.
    k = duck_keyframes({{8.0, 9.75}}, 0.0, 10.0, 0.0, 10.0, 0.5);
    REQUIRE(!k.empty());
    CHECK(std::fabs(k.back().first - 10.0) < 1e-9);
    CHECK(std::fabs(k.back().second - -5.0) < 1e-9);
    // Outside the clip: nothing; zero fade still strictly increasing.
    CHECK(duck_keyframes({{11.0, 12.0}}, 0.0, 10.0, 0.0, 10.0, 0.5).empty());
    k = duck_keyframes({{2.0, 3.0}}, 0.0, 10.0, 0.0, 10.0, 0.0);
    CHECK(strictly_increasing(k));
    CHECK(k.size() == 4);
}

// ---- port additions ----

TEST_CASE("port: envelope hostile input stays finite and honest") {
    const float sr = 1000.0f;
    // Zero-length signal -> no envelope points.
    std::vector<float> empty;
    const float *ech[1] = {empty.data()};
    CHECK(duck_envelope_db(ech, 1, 0, sr, 0.01f, 0.02f).empty());
    // All-silence signal reads FLOOR at every hop.
    std::vector<float> z(500, 0.0f);
    const float *zch[1] = {z.data()};
    const auto e = duck_envelope_db(zch, 1, z.size(), sr, 0.01f, 0.02f);
    REQUIRE(e.size() == 50);
    for (float v : e) {
        CHECK(v == kDuckingFloorDb);
    }
    // Hop larger than the signal -> a single windowed point.
    const auto one = duck_envelope_db(zch, 1, z.size(), sr, 10.0f, 0.02f);
    CHECK(one.size() == 1);
    // A window larger than the signal clips at the edges (no OOB).
    const auto wide = duck_envelope_db(zch, 1, z.size(), sr, 0.01f, 60.0f);
    CHECK(wide.size() == 50);
    // NaN input does not crash and never reads above FLOOR + NaN
    // comparison quirk: NaN > threshold is false in activity, so a
    // NaN envelope reads as "off" — assert finite-count output only.
    std::vector<float> nanv(100, 0.0f);
    nanv[50] = std::nanf("");
    const float *nch[1] = {nanv.data()};
    const auto ne = duck_envelope_db(nch, 1, nanv.size(), sr, 0.01f, 0.02f);
    CHECK(ne.size() == 10);
    // Channel-averaged power: two channels each at amp a give the
    // same envelope as one channel at amp a (mean power).
    std::vector<float> mono(1000, 0.1f);
    const float *mch[1] = {mono.data()};
    const auto em = duck_envelope_db(mch, 1, mono.size(), sr, 0.01f, 0.02f);
    const float *stch[2] = {mono.data(), mono.data()};
    const auto est = duck_envelope_db(stch, 2, mono.size(), sr, 0.01f, 0.02f);
    REQUIRE(em.size() == est.size());
    for (std::size_t i = 0; i < em.size(); ++i) {
        CHECK(std::fabs(em[i] - est[i]) < 1e-4f);
    }
}

TEST_CASE("port: keyframes on degenerate regions keep the contract") {
    // Empty/negative-length regions are dropped (b > a filter).
    CHECK(duck_keyframes({{2.0, 2.0}, {3.0, 1.0}}, 0.0, 10.0, 0.0, 10.0, 0.5).empty());
    // reduce_db sign does not matter (|reduce|).
    const auto a = duck_keyframes({{2.0, 4.0}}, 0.0, 10.0, 0.0, 15.0, 0.5);
    const auto b = duck_keyframes({{2.0, 4.0}}, 0.0, 10.0, 0.0, -15.0, 0.5);
    CHECK(same(a, b));
    // fade clamped to >= 1 ms: a zero fade still yields 4 distinct
    // keyframes (covered by the crate case above); a huge fade
    // spanning multiple regions merges them.
    const auto merged = duck_keyframes({{2.0, 3.0}, {4.0, 5.0}}, 0.0, 10.0, 0.0, 10.0, 5.0);
    // Ramp starts at -3 and +(-1): first group's ramp overlaps the
    // second region's ramp -> single ducked span.
    CHECK(merged.size() == 4);
    CHECK(strictly_increasing(merged));
    // A clip with zero/negative extent admits nothing.
    CHECK(duck_keyframes({{2.0, 4.0}}, 5.0, 5.0, 0.0, 10.0, 0.5).empty());
    CHECK(duck_keyframes({{2.0, 4.0}}, 5.0, 1.0, 0.0, 10.0, 0.5).empty());
}
