// GCSV gyro parser tests. Synthetic strings exercise the exact fixture
// format; the real-file smoke test reads the first ~100 lines of the
// STEPS/media/test fixture when present and skips otherwise (the fixture is
// gitignored, so CI machines without STEPS/ still pass).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Gyro.h>

#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

using namespace rawedit;

namespace {

// A minimal GCSV in the exact shape of the real fixtures (MotionCam /
// GyroFlow IMU LOG): signature, key,value headers, column header, then
// rows of t,gx,gy,gz,ax,ay,az, with a trailing comma.
const char* kSyntheticGcsv =
    "GYROFLOW IMU LOG\n"
    "version,1.2\n"
    "id,Xiaomi.25010PN30G\n"
    "orientation,XYZ\n"
    "fwversion,5.0.9-pro\n"
    "timestamp,1794260510503\n"
    "vendor,MotionCam\n"
    "tscale,0.0021144340\n"
    "gscale,0.0000000100\n"
    "ascale,0.0000000100\n"
    "t,gx,gy,gz,ax,ay,az\n"
    "0,1138872,5122492,1610019,721675301,25842485,669990349,\n"
    "1,1237001,5006425,1511890,728949640,29934289,665443910,\n"
    "2,1225171,4878530,1571041,715118388,31011017,667789053,\n";

Result<GyroData> parseText(const char* text) {
    std::istringstream in(text);
    return parseGcsv(in);
}

// True when the real fixture exists (guarded so machines without the
// gitignored STEPS/ tree still pass).
bool fixtureAvailable() {
    std::FILE* f = std::fopen(
        "STEPS/media/test/261110_004150_VIDEO_24mm.gcsv", "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

} // namespace

TEST_CASE("synthetic_gcsv_parses_exact_values") {
    auto res = parseText(kSyntheticGcsv);
    REQUIRE(res.isOk());
    const GyroData& d = *res.value;

    CHECK(d.sampleCount() == 3);
    CHECK(d.orientation == "XYZ");

    // Timestamps: t * tscale * 1e9, rounded. tscale = 0.0021144340 s.
    //   t=0 -> 0 ns exactly (rounding of 0.0 + 0.5 truncates to 0)
    //   t=1 -> 2114434 ns, t=2 -> 4228868 ns
    CHECK(d.samples[0].timestampNs == 0);
    CHECK(d.samples[1].timestampNs == 2114434);
    CHECK(d.samples[2].timestampNs == 4228868);
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 4228868);
    CHECK(d.spanNs() == 4228868);

    // Angular rates: counts * gscale (1e-8 rad/s per count).
    CHECK(d.samples[0].x == doctest::Approx(1138872 * 1e-8));
    CHECK(d.samples[0].y == doctest::Approx(5122492 * 1e-8));
    CHECK(d.samples[0].z == doctest::Approx(1610019 * 1e-8));
    CHECK(d.samples[2].x == doctest::Approx(1225171 * 1e-8));
    CHECK(d.samples[2].y == doctest::Approx(4878530 * 1e-8));
    CHECK(d.samples[2].z == doctest::Approx(1571041 * 1e-8));

    // Strictly increasing timestamps.
    CHECK(d.samples[0].timestampNs < d.samples[1].timestampNs);
    CHECK(d.samples[1].timestampNs < d.samples[2].timestampNs);
}

TEST_CASE("negative_counts_and_ticks_are_accepted") {
    // Gyro counts go negative in the real files; the parser must keep the
    // sign through both the tick and the rate fields.
    const char* g =
        "GYROFLOW IMU LOG\n"
        "tscale,0.5\n"
        "gscale,0.0000000100\n"
        "t,gx,gy,gz,ax,ay,az\n"
        "0,-19540853,-21074514,-12950654,8135597,823130989,502971315,\n"
        "3,19540853,0,-1,-1,-2,-3,\n";
    auto res = parseText(g);
    REQUIRE(res.isOk());
    const GyroData& d = *res.value;
    CHECK(d.sampleCount() == 2);
    CHECK(d.samples[0].x == doctest::Approx(-19540853 * 1e-8));
    CHECK(d.samples[0].z == doctest::Approx(-12950654 * 1e-8));
    // t=3, tscale=0.5 s -> 1.5e9 ns.
    CHECK(d.samples[1].timestampNs == 1500000000);
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 1500000000);
}

TEST_CASE("malformed_rows_report_errors_without_crashing") {
    SUBCASE("wrong signature") {
        auto res = parseText("GRAVITYFLOW IMU LOG\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("empty stream") {
        std::istringstream in("");
        auto res = parseGcsv(in);
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::Truncated);
    }
    SUBCASE("missing column header") {
        auto res = parseText("GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::Truncated);
    }
    SUBCASE("missing tscale") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("header row with no value") {
        auto res = parseText("GYROFLOW IMU LOG\ntscale,\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n0,1,2,3,4,5,6,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("non-integer data field") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "0,12x70,5006425,1511890,728949640,29934289,665443910,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("wrong field count") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "0,1237001,5006425,1511890,728949640,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("non-increasing tick") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "2,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("repeated tick") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "1,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("data rows but no header at all") {
        auto res = parseText("0,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("missing file reports io error") {
        auto res = parseGcsv("/nonexistent/definitely-missing.gcsv");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::IoError);
    }
}

TEST_CASE("real_fixture_smoke"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable()) {
        MESSAGE("SKIP: STEPS/media/test/261110_004150_VIDEO_24mm.gcsv not present");
        return;
    }

    // The full file is 318 KB / 5511 lines; the mission asks for a smoke over
    // the first ~100 lines (1 signature + 10 header + 89 data rows).
    std::ifstream in("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv", std::ios::binary);
    REQUIRE(in.good());
    std::stringstream buf;
    std::string line;
    for (int i = 0; i < 100 && std::getline(in, line); ++i) {
        buf << line << "\n";
    }
    auto res = parseGcsv(buf);
    REQUIRE(res.isOk());
    const GyroData& d = *res.value;

    // 11 header lines + 89 data rows.
    CHECK(d.sampleCount() == 89);
    CHECK(d.sampleCount() > 0);
    CHECK(d.firstTs() < d.lastTs());
    CHECK(d.orientation == "XYZ");

    // Monotonic, strictly increasing timestamps.
    for (size_t i = 1; i < d.samples.size(); ++i)
        CHECK(d.samples[i - 1].timestampNs < d.samples[i].timestampNs);

    // tscale from the fixture: 0.0021144340 s -> ~472.9 Hz.
    CHECK(d.samples[1].timestampNs == 2114434);

    // Rates are in a sane physical range for handheld video (< ~10 rad/s).
    for (const auto& s : d.samples) {
        CHECK(std::fabs(s.x) < 10.0f);
        CHECK(std::fabs(s.y) < 10.0f);
        CHECK(std::fabs(s.z) < 10.0f);
    }
}

TEST_CASE("real_fixture_full_file_smoke"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable()) {
        MESSAGE("SKIP: STEPS/media/test/261110_004150_VIDEO_24mm.gcsv not present");
        return;
    }
    auto res = parseGcsv("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv");
    REQUIRE(res.isOk());
    const GyroData& d = *res.value;
    // Full file: 5500 data rows (t 0..5499), ~2.114 ms per tick.
    CHECK(d.sampleCount() == 5500);
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 5499 * 2114434LL);
    CHECK(d.spanNs() == 5499 * 2114434LL);
}
