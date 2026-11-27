// E5-C audio effect skeleton tests (night D1): ParamSpec/ParamValues/
// Smoothed — the port of filmcraft's smooth.rs #[cfg(test)] block (3
// tests) plus ParamSpec::sanitize / ParamValues semantics vectors
// derived from lib.rs (NaN -> default, clamp, choice/toggle rounding,
// unknown-id refusals). Synthetic only, no cwd needs.
//
// VECTOR ORIGIN: the Smoothed cases below carry the crate's own
// numbers and tolerances (smooth.rs); the ParamSpec/ParamValues cases
// assert the documented lib.rs behavior (sanitize's NaN mapping,
// set/get round trip, the v/on/idx helpers). -ffp-contract=off
// matches the port build (irrelevant for these integers-in-disguise,
// kept for uniformity with the audio TUs).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/AudioFx.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>

namespace {

using rawedit::ParamSpec;
using rawedit::ParamUnit;
using rawedit::ParamValues;
using rawedit::Smoothed;
using rawedit::db_to_gain;
using rawedit::gain_to_db;
using rawedit::rust_as_u32;

} // namespace

TEST_CASE("smoothed: ramps linearly and lands exactly") {
    // crate smooth.rs ramps_linearly_and_lands_exactly.
    Smoothed s(0.0f, 4);
    s.set(1.0f);
    const float v[6] = {s.tick(), s.tick(), s.tick(), s.tick(), s.tick(), s.tick()};
    const float want[6] = {0.25f, 0.5f, 0.75f, 1.0f, 1.0f, 1.0f};
    for (int i = 0; i < 6; ++i) {
        CHECK(v[i] == want[i]);
    }
    CHECK(!s.is_smoothing());
}

TEST_CASE("smoothed: retarget mid-ramp is continuous") {
    // crate smooth.rs retarget_mid_ramp_is_continuous.
    Smoothed s(0.0f, 10);
    s.set(1.0f);
    for (int i = 0; i < 5; ++i) {
        (void)s.tick();
    }
    const float mid = s.value();
    s.set(0.0f);
    const float n = s.tick();
    CHECK(std::fabs(n - mid) <= 0.1f + 1e-6f);
}

TEST_CASE("smoothed: zero ramp is immediate") {
    // crate smooth.rs zero_ramp_is_immediate.
    Smoothed s(0.0f, 0);
    s.set(3.0f);
    CHECK(s.value() == 3.0f);
}

TEST_CASE("smoothed: with_ms rounds the ramp length like the crate") {
    // Smoothed::with_ms: (sr * ms / 1000).round().max(0) as u32.
    // 48000 * 20 / 1000 = 960 exactly.
    Smoothed s = Smoothed::with_ms(1.0f, 48000.0f, 20.0f);
    s.set(2.0f);
    CHECK(s.is_smoothing());
    int ticks = 0;
    while (s.is_smoothing()) {
        (void)s.tick();
        ++ticks;
        REQUIRE(ticks < 10000);
    }
    CHECK(ticks == 960);
    CHECK(s.value() == 2.0f);
    // snap / set_immediate / target.
    s.set_immediate(4.0f);
    CHECK(s.value() == 4.0f);
    CHECK(s.target() == 4.0f);
    CHECK(!s.is_smoothing());
}

TEST_CASE("param spec: sanitize clamps, rounds choices/toggles, NaN -> default") {
    // crate lib.rs ParamSpec::sanitize.
    const ParamSpec spec = ParamSpec::make("gain", "Gain", -40.0f, 40.0f, 0.0f,
                                           ParamUnit::Decibels);
    CHECK(spec.sanitize(3.0f) == 3.0f);
    CHECK(spec.sanitize(-100.0f) == -40.0f);
    CHECK(spec.sanitize(100.0f) == 40.0f);
    CHECK(std::isnan(spec.sanitize(std::numeric_limits<float>::quiet_NaN())) ==
          false);
    CHECK(spec.sanitize(std::numeric_limits<float>::quiet_NaN()) ==
          spec.default_value);
    CHECK(spec.log_scale == false);

    static const char *const kChoices[] = {"Peak", "RMS"};
    const ParamSpec ch = ParamSpec::choice("detector", "Detector", kChoices, 2, 0);
    CHECK(ch.min == 0.0f);
    CHECK(ch.max == 1.0f);
    CHECK(ch.sanitize(0.7f) == 1.0f); // rounded
    CHECK(ch.sanitize(0.2f) == 0.0f);
    CHECK(ch.default_value == 0.0f);
    CHECK(ch.choice_count == 2);

    const ParamSpec tog = ParamSpec::toggle("true_peak", "True Peak", true);
    CHECK(tog.default_value == 1.0f);
    CHECK(tog.sanitize(0.4f) == 0.0f);
    CHECK(tog.unit == ParamUnit::Toggle);

    const ParamSpec lg = ParamSpec::log("release", "Release", 1.0f, 3000.0f,
                                        100.0f, ParamUnit::Milliseconds);
    CHECK(lg.log_scale == true);
}

TEST_CASE("param values: set/get round trip, unknown ids refuse, defaults") {
    // crate lib.rs ParamValues (the registry test's per-effect
    // expectations, applied to a hand-built spec list).
    static const ParamSpec specs[] = {
        ParamSpec::make("threshold", "Threshold", -60.0f, 0.0f, -20.0f,
                        ParamUnit::Decibels),
        ParamSpec::log("ratio", "Ratio", 1.0f, 30.0f, 4.0f, ParamUnit::Ratio),
        ParamSpec::toggle("on", "On", false),
    };
    ParamValues pv(specs, 3);
    CHECK(pv.count() == 3);
    CHECK(pv.v("threshold") == -20.0f);
    CHECK(pv.v("ratio") == 4.0f);
    CHECK(pv.v("on") == 0.0f);
    CHECK(!pv.on("on"));

    CHECK(pv.set("ratio", 1000.0f));       // clamps into range
    CHECK(pv.v("ratio") == 30.0f);
    CHECK(pv.set("ratio", std::numeric_limits<float>::quiet_NaN()));
    CHECK(pv.v("ratio") == 4.0f);          // NaN -> default
    CHECK(!pv.set("nope", 1.0f));          // unknown id refused
    CHECK(pv.get("nope") == std::nullopt);

    CHECK(pv.set("on", 1.0f));
    CHECK(pv.on("on"));
    CHECK(pv.idx("on") == 1);
    // get returns the sanitized value.
    auto got = pv.get("threshold");
    REQUIRE(got.has_value());
    CHECK(*got == -20.0f);
}

TEST_CASE("helpers: db_to_gain / gain_to_db round the crate's way") {
    // crate lib.rs db_to_gain/gain_to_db: 10f32.powf(db/20), 20*log10(|g|).
    CHECK(db_to_gain(0.0f) == doctest::Approx(1.0f));
    CHECK(db_to_gain(-20.0f) == doctest::Approx(0.1f).epsilon(1e-6f));
    CHECK(gain_to_db(1.0f) == doctest::Approx(0.0f));
    CHECK(gain_to_db(0.1f) == doctest::Approx(-20.0f).epsilon(1e-6f));
    CHECK(gain_to_db(-0.1f) == doctest::Approx(-20.0f).epsilon(1e-6f)); // |g|
    CHECK(std::isinf(gain_to_db(0.0f))); // -inf for 0
    // rust_as_u32: Rust `as u32` (truncate toward zero, saturate,
    // NaN/negative -> 0).
    CHECK(rust_as_u32(2.7f) == 2u);
    CHECK(rust_as_u32(-1.0f) == 0u);
    CHECK(rust_as_u32(std::numeric_limits<float>::quiet_NaN()) == 0u);
    CHECK(rust_as_u32(1e30f) == 0xFFFFFFFFu);
}
