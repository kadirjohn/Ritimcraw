// GCSV IMU parser + embedded-MCRAW IMU loader tests (stabilization
// Phase 1: parse + normalize only).
//
// Synthetic strings exercise the exact fixture format. The real-file smoke
// tests read the STEPS/media/test fixtures when present and skip otherwise
// (the fixtures are gitignored, so CI machines without STEPS/ still pass).
//
// The MCRAW embedded-IMU tests build a synthetic container in the same way
// the vendored decoder's own DecoderGyroTest.cpp / DecoderAccelerometerTest
// do (Apache-2.0, third_party/motioncam-decoder/tests) — the real fixtures
// carry no embedded IMU, so a synthetic container is the only way to
// exercise the populated path with real container bytes.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Gyro.h>

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <unistd.h>
#include <vector>

#include <motioncam/Container.hpp>
#include <motioncam/MotionSample.hpp>

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

Result<ImuData> parseText(const char* text) {
    std::istringstream in(text);
    return parseGcsv(in);
}

// True when the real fixture exists (guarded so machines without the
// gitignored STEPS/ tree still pass).
bool fixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

// ---------------------------------------------------------------------------
// Synthetic MCRAW container builder (mirrors the vendored decoder's own
// DecoderGyroTest.cpp writeContainer, extended with accelerometer chunks).
// ---------------------------------------------------------------------------

std::string tempPath() {
    static int seq = 0;
    std::ostringstream name;
    name << "/tmp/rawedit-test-mcraw-" << getpid() << "-" << (seq++) << ".mcraw";
    return name.str();
}

template <class T>
void writeValue(std::ofstream& out, const T& v) {
    out.write(reinterpret_cast<const char*>(&v), sizeof(T));
}

int64_t outOffset(std::ofstream& out) {
    return static_cast<int64_t>(out.tellp());
}

// Writes one gyro or accelerometer data chunk and returns its offset entry.
motioncam::BufferOffset writeSampleChunk(
    std::ofstream& out, motioncam::Type type,
    const std::vector<motioncam::MotionSample>& samples) {
    const motioncam::BufferOffset offset{outOffset(out), samples.front().timestampNs};
    const uint32_t version = type == motioncam::Type::GYRO_DATA
        ? motioncam::GYRO_DATA_VERSION
        : motioncam::ACCELEROMETER_DATA_VERSION;
    const motioncam::Item item{
        type,
        static_cast<uint32_t>(8 + sizeof(motioncam::MotionSample) * samples.size())};
    writeValue(out, item);
    writeValue(out, version);
    writeValue(out, static_cast<uint32_t>(samples.size()));
    out.write(reinterpret_cast<const char*>(samples.data()),
              static_cast<std::streamsize>(sizeof(motioncam::MotionSample) * samples.size()));
    return offset;
}

// Builds a minimal MCRAW with optional gyro and accelerometer streams (and
// one dummy frame, like the vendored test uses). metadata is the container
// JSON written after the header.
void writeMcraw(const std::string& path,
                const char* metadata,
                const std::vector<motioncam::MotionSample>* gyro,
                const std::vector<motioncam::MotionSample>* accel) {
    std::ofstream out(path, std::ios::binary);

    motioncam::Header header{};
    header.version = motioncam::CONTAINER_VERSION;
    std::memcpy(header.ident, motioncam::CONTAINER_ID, sizeof(motioncam::CONTAINER_ID));
    writeValue(out, header);

    const motioncam::Item metadataItem{motioncam::Type::METADATA,
                                       static_cast<uint32_t>(std::strlen(metadata))};
    writeValue(out, metadataItem);
    out.write(metadata, static_cast<std::streamsize>(std::strlen(metadata)));

    std::vector<motioncam::BufferOffset> gyroOffsets;
    if (gyro && !gyro->empty())
        gyroOffsets.push_back(writeSampleChunk(out, motioncam::Type::GYRO_DATA, *gyro));

    std::vector<motioncam::BufferOffset> accelOffsets;
    if (accel && !accel->empty())
        accelOffsets.push_back(writeSampleChunk(out, motioncam::Type::ACCELEROMETER_DATA, *accel));

    // One dummy frame so the container is otherwise well-formed (the frame
    // index may legitimately be empty, but a frame keeps it realistic).
    // NOTE the ordering: Decoder::readExtra() walks forward from the LAST
    // frame's offset, so the GYRO_INDEX/ACCELEROMETER_INDEX items must come
    // after the frame data in file order (the vendored DecoderGyroTest.cpp
    // lays the file out the same way).
    const int64_t frameTsNs = 1'010'000'000;
    const motioncam::BufferOffset frameOffset{outOffset(out), frameTsNs};
    const uint8_t frameData = 0;
    writeValue(out, motioncam::Item{motioncam::Type::BUFFER, sizeof(frameData)});
    writeValue(out, frameData);
    writeValue(out, metadataItem);
    out.write(metadata, static_cast<std::streamsize>(std::strlen(metadata)));

    if (!gyroOffsets.empty()) {
        const uint32_t itemSize = static_cast<uint32_t>(
            sizeof(motioncam::GyroIndex) + sizeof(motioncam::BufferOffset) * gyroOffsets.size());
        writeValue(out, motioncam::Item{motioncam::Type::GYRO_INDEX, itemSize});
        writeValue(out, motioncam::GyroIndex{motioncam::GYRO_INDEX_VERSION,
                                             static_cast<uint32_t>(gyroOffsets.size())});
        out.write(reinterpret_cast<const char*>(gyroOffsets.data()),
                  static_cast<std::streamsize>(sizeof(motioncam::BufferOffset) * gyroOffsets.size()));
    }
    if (!accelOffsets.empty()) {
        const uint32_t itemSize = static_cast<uint32_t>(
            sizeof(motioncam::AccelerometerIndex) + sizeof(motioncam::BufferOffset) * accelOffsets.size());
        writeValue(out, motioncam::Item{motioncam::Type::ACCELEROMETER_INDEX, itemSize});
        writeValue(out, motioncam::AccelerometerIndex{motioncam::ACCELEROMETER_INDEX_VERSION,
                                                      static_cast<uint32_t>(accelOffsets.size())});
        out.write(reinterpret_cast<const char*>(accelOffsets.data()),
                  static_cast<std::streamsize>(sizeof(motioncam::BufferOffset) * accelOffsets.size()));
    }

    const int64_t frameIndexDataOffset = outOffset(out);
    writeValue(out, frameOffset);
    writeValue(out, motioncam::Item{motioncam::Type::BUFFER_INDEX,
                                    sizeof(motioncam::BufferIndex)});
    writeValue(out, motioncam::BufferIndex{
        static_cast<int32_t>(motioncam::INDEX_MAGIC_NUMBER), 1, frameIndexDataOffset});
}

const std::vector<motioncam::MotionSample>& syntheticGyro() {
    static const std::vector<motioncam::MotionSample> g = {
        {1'000'000'000, 0.1f, -0.2f, 0.3f},
        {1'002'500'000, 0.4f, 0.5f, -0.6f},
        {1'005'000'000, -0.7f, 0.8f, 0.9f},
    };
    return g;
}

const std::vector<motioncam::MotionSample>& syntheticAccel() {
    // Gravity-sized values, m/s^2 incl. gravity (Decoder.hpp semantics).
    static const std::vector<motioncam::MotionSample> a = {
        {1'000'500'000, 0.2f, 0.1f, 9.8f},
        {1'003'000'000, -0.1f, 0.3f, 9.7f},
    };
    return a;
}

} // namespace

// ---------------------------------------------------------------------------
// GCSV: exact values, scale chain, header variants, row policy
// ---------------------------------------------------------------------------

TEST_CASE("synthetic_gcsv_parses_exact_values") {
    auto res = parseText(kSyntheticGcsv);
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;

    CHECK(d.sampleCount() == 3);
    CHECK(d.orientation == "XYZ");
    CHECK(d.hasAccel());
    CHECK(d.aligned()); // GCSV rows are index-locked by construction

    // Timestamps: t * tscale * 1e9, rounded. tscale = 0.0021144340 s.
    //   t=0 -> 0 ns exactly (rounding of 0.0 + 0.5 truncates to 0)
    //   t=1 -> 2114434 ns, t=2 -> 4228868 ns
    CHECK(d.gyro[0].timestampNs == 0);
    CHECK(d.gyro[1].timestampNs == 2114434);
    CHECK(d.gyro[2].timestampNs == 4228868);
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 4228868);
    CHECK(d.spanNs() == 4228868);
    CHECK(d.periodNs() == 2114434);

    // Gyro: counts * gscale (1e-8 rad/s per count). The chain, verified
    // against telemetry-parser gcsv.rs @9350bc2: it computes an intermediate
    // gyro_scale = 1/gscale * PI/180 tagged "deg/s" and util.rs into_scaled
    // multiplies raw/raw2unit * unit2deg("deg/s" = 180/PI) — the PI/180 and
    // 180/PI cancel, netting raw * gscale in rad/s. gscale is therefore
    // applied exactly once here, with no double PI/180 conversion.
    CHECK(d.gyro[0].x == doctest::Approx(1138872 * 1e-8));
    CHECK(d.gyro[0].y == doctest::Approx(5122492 * 1e-8));
    CHECK(d.gyro[0].z == doctest::Approx(1610019 * 1e-8));
    CHECK(d.gyro[2].x == doctest::Approx(1225171 * 1e-8));
    CHECK(d.gyro[2].y == doctest::Approx(4878530 * 1e-8));
    CHECK(d.gyro[2].z == doctest::Approx(1571041 * 1e-8));

    // Accel: counts * ascale (1e-8 m/s^2 per count for MotionCam). The GCSV
    // spec calls the ascale product "g", but MotionCam files are m/s^2:
    // the fixtures' raw*ascale magnitudes cluster at gravity (mean |a| =
    // 9.855 / 9.701 m/s^2 in the two real files) — as g that would be a
    // sustained ~9.86 g handheld, impossible. telemetry-parser tags Motion
    // Cam accelerometer data "m/s^2" and Decoder.hpp documents
    // loadAccelerometerData as "m/s^2 including gravity".
    CHECK(d.accel[0].x == doctest::Approx(721675301 * 1e-8));
    CHECK(d.accel[0].y == doctest::Approx(25842485 * 1e-8));
    CHECK(d.accel[0].z == doctest::Approx(669990349 * 1e-8));
    CHECK(d.accel[2].x == doctest::Approx(715118388 * 1e-8));
    CHECK(d.accel[2].y == doctest::Approx(31011017 * 1e-8));
    CHECK(d.accel[2].z == doctest::Approx(667789053 * 1e-8));

    // GCSV rows carry both sensors at the same tick: the streams are
    // index-locked pairwise.
    for (size_t i = 0; i < d.gyro.size(); ++i)
        CHECK(d.gyro[i].timestampNs == d.accel[i].timestampNs);

    // Strictly increasing timestamps.
    CHECK(d.gyro[0].timestampNs < d.gyro[1].timestampNs);
    CHECK(d.gyro[1].timestampNs < d.gyro[2].timestampNs);

    // The header "timestamp" sync anchor (stabilization Phase 5): the
    // epoch-ms wall time of the log start, carried verbatim.
    CHECK(d.logStartWallMs == 1794260510503);
}

TEST_CASE("gcsv_timestamp_anchor_is_optional_and_honest") {
    // Absent key -> the -1 sentinel (sync degrades to ManualRequired,
    // never fabricated); a malformed or non-positive value does too —
    // and neither fails the file, which must keep parsing. Non-const:
    // the malformed/non-positive SUBCASEs rewrite the timestamp value in
    // place (doctest re-runs the whole body per SUBCASE, so each starts
    // from the pristine kSyntheticGcsv copy).
    std::string base(kSyntheticGcsv);
    const std::string tsLine = "timestamp,1794260510503\n";

    SUBCASE("absent keeps the sentinel") {
        const std::string text = base.substr(0, base.find(tsLine))
            + base.substr(base.find(tsLine) + tsLine.size());
        const Result<ImuData> res = parseText(text.c_str());
        REQUIRE(res.isOk());
        CHECK((*res.value).logStartWallMs == -1);
    }
    SUBCASE("malformed keeps the sentinel") {
        const Result<ImuData> res = parseText(
            base.replace(base.find(tsLine) + strlen("timestamp,"),
                         tsLine.size() - strlen("timestamp,\n"),
                         "not-a-number")
                        .c_str());
        REQUIRE(res.isOk());
        CHECK((*res.value).logStartWallMs == -1);
    }
    SUBCASE("non-positive keeps the sentinel") {
        const Result<ImuData> res = parseText(
            base.replace(base.find(tsLine) + strlen("timestamp,"),
                         tsLine.size() - strlen("timestamp,\n"),
                         "0")
                        .c_str());
        REQUIRE(res.isOk());
        CHECK((*res.value).logStartWallMs == -1);
    }
}

TEST_CASE("scale_chain_gyro_is_radians_per_second") {
    // The manager-required check that gscale is applied once (no double
    // PI/180): pick gscale = PI/180 (i.e. 1 count = 1 deg/s) and verify the
    // parser returns the deg/s value directly converted once to rad/s.
    //   raw=180, gscale=PI/180 -> 180 * PI/180 = PI rad/s? NO:
    //   raw * gscale = 180 * (PI/180) = PI rad/s. If the parser (wrongly)
    //   divided by PI/180 again it would produce PI/(PI/180) = 180.
    // A wrong extra *180/PI would give PI*180/PI = 180 as well; the exact
    // value PI distinguishes correct from both wrong chains.
    const double pi = 3.14159265358979323846;
    std::ostringstream g;
    g << "GYROFLOW IMU LOG\n"
      << "tscale,1.0\n"
      << "gscale," << std::setprecision(17) << (pi / 180.0) << "\n"
      << "t,gx,gy,gz,ax,ay,az\n"
      << "0,180,0,0,\n";
    // NOTE: parseDouble has no exponent support; PI/180 prints as
    // 0.017453292519943295 — plain decimals, so this parses.
    auto res = parseText(g.str().c_str());
    REQUIRE(res.isOk());
    CHECK((*res.value).gyro[0].x == doctest::Approx(pi).epsilon(1e-6));
}

TEST_CASE("scale_chain_accel_fixture_evidence") {
    // Real-file evidence, executable: the first row of the real fixture
    // (ax=721675301, ascale=1e-8) is 7.2168 m/s^2. Interpreted as g that
    // would be 70.78 m/s^2 — the phone is handheld, not in a fighter jet;
    // the raw*ascale value IS m/s^2 including gravity (x is one component,
    // |a| for the row is ~9.85). The test pins the parser to the m/s^2
    // interpretation: accel = raw * ascale, no 9.80665 factor.
    const char* g =
        "GYROFLOW IMU LOG\n"
        "tscale,0.0021144340\n"
        "gscale,0.0000000100\n"
        "ascale,0.0000000100\n"
        "t,gx,gy,gz,ax,ay,az\n"
        "0,1138872,5122492,1610019,721675301,25842485,669990349,\n";
    auto res = parseText(g);
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;
    CHECK(d.accel[0].x == doctest::Approx(7.21675301));
    // If the parser wrongly applied the g->m/s^2 factor the value would be
    // ~70.78; the gravity-clustered magnitude proves the direct reading.
    const float mag = std::sqrt(d.accel[0].x * d.accel[0].x
                                + d.accel[0].y * d.accel[0].y
                                + d.accel[0].z * d.accel[0].z);
    CHECK(mag == doctest::Approx(9.8558).epsilon(0.001));
}

TEST_CASE("header_variants") {
    SUBCASE("gyro-only log needs no ascale") {
        // t,gx,gy,gz, rows (trailing comma after gz) with no ascale header:
        // valid — the accel stream is simply absent.
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,\n"
            "1,4,5,6,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        const ImuData& d = *res.value;
        CHECK(d.sampleCount() == 2);
        CHECK(!d.hasAccel());
        CHECK(!d.aligned());
        CHECK(d.gyro[1].x == doctest::Approx(0.4));
    }
    SUBCASE("gyro-only log without trailing comma") {
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3\n"
            "1,4,5,6\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK((*res.value).sampleCount() == 2);
        CHECK(!(*res.value).hasAccel());
    }
    SUBCASE("accel rows without ascale are rejected") {
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n";
        auto res = parseText(g);
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("ascale present but unused is fine") {
        // A gyro-only log that still declares ascale (like a file trimmed
        // of its accel columns) parses; ascale is simply unused.
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "ascale,0.0000000100\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK(!(*res.value).hasAccel());
    }
    SUBCASE("no orientation key") {
        // Orientation is optional; it is stored raw and only consumed in
        // Phase 2 (axis remap). Absent means empty, never a guessed default.
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK((*res.value).orientation.empty());
    }
    SUBCASE("orientation key stored raw") {
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "orientation,YxZ\n"
            "ascale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK((*res.value).orientation == "YxZ");
    }
}

TEST_CASE("row_policy") {
    SUBCASE("negative counts and ticks are accepted") {
        // Gyro counts go negative in the real files; the parser must keep the
        // sign through both the tick and the rate fields.
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.0000000100\n"
            "ascale,0.0000000100\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,-19540853,-21074514,-12950654,8135597,823130989,502971315,\n"
            "3,19540853,0,-1,-1,-2,-3,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        const ImuData& d = *res.value;
        CHECK(d.sampleCount() == 2);
        CHECK(d.gyro[0].x == doctest::Approx(-19540853 * 1e-8));
        CHECK(d.gyro[0].z == doctest::Approx(-12950654 * 1e-8));
        CHECK(d.accel[0].x == doctest::Approx(8135597 * 1e-8));
        // t=3, tscale=0.5 s -> 1.5e9 ns.
        CHECK(d.gyro[1].timestampNs == 1500000000);
        CHECK(d.firstTs() == 0);
        CHECK(d.lastTs() == 1500000000);
    }
    SUBCASE("blank lines are tolerated") {
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "\n"
            "gscale,0.1\n"
            "ascale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n"
            "\n"
            "1,7,8,9,10,11,12,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK((*res.value).sampleCount() == 2);
    }
    SUBCASE("mid-file shape switch is rejected") {
        // First row has accel columns, second drops them: the log must be
        // consistent (mixing would misalign the two streams).
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "ascale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n"
            "1,7,8,9,\n";
        auto res = parseText(g);
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("trailing comma variance within accel rows is fine") {
        // 8 fields (trailing comma) and 7 fields (no trailing comma) are the
        // same shape family — writers differ; only gyro-only vs accel may
        // not switch.
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "ascale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n"
            "1,7,8,9,10,11,12\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        CHECK((*res.value).sampleCount() == 2);
        CHECK((*res.value).hasAccel());
    }
    SUBCASE("non-consecutive ticks are accepted") {
        // The tick is an index, not a counter: gaps mean dropped samples,
        // not corruption (the timestamp comes from t * tscale either way).
        const char* g =
            "GYROFLOW IMU LOG\n"
            "tscale,0.5\n"
            "gscale,0.1\n"
            "ascale,0.1\n"
            "t,gx,gy,gz,ax,ay,az\n"
            "0,1,2,3,4,5,6,\n"
            "5,7,8,9,10,11,12,\n"
            "9,1,2,3,4,5,6,\n";
        auto res = parseText(g);
        REQUIRE(res.isOk());
        const ImuData& d = *res.value;
        CHECK(d.sampleCount() == 3);
        CHECK(d.gyro[1].timestampNs == 2500000000);
        CHECK(d.gyro[2].timestampNs == 4500000000);
    }
    SUBCASE("non-increasing tick") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nascale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "2,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("repeated tick") {
        auto res = parseText(
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nascale,0.1\nt,gx,gy,gz,ax,ay,az\n"
            "1,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
    SUBCASE("missing file reports io error") {
        auto res = parseGcsv("/nonexistent/definitely-missing.gcsv");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::IoError);
    }
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
            "GYROFLOW IMU LOG\ntscale,0.5\ngscale,0.1\nascale,0.1\nt,gx,gy,gz,ax,ay,az\n"
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
    SUBCASE("data rows but no header at all") {
        auto res = parseText("0,1,2,3,4,5,6,\n1,7,8,9,10,11,12,\n");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);
    }
}

// ---------------------------------------------------------------------------
// Real fixtures (gitignored; guarded so machines without STEPS/ still pass)
// ---------------------------------------------------------------------------

TEST_CASE("real_fixture_full_file"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv")) {
        MESSAGE("SKIP: STEPS/media/test/261110_004150_VIDEO_24mm.gcsv not present");
        return;
    }
    auto res = parseGcsv("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv");
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;
    // Full file: 5500 data rows (t 0..5499, contiguous), ~2.114 ms per tick.
    CHECK(d.sampleCount() == 5500);
    CHECK(d.hasAccel());
    CHECK(d.aligned());
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 5499 * 2114434LL);
    CHECK(d.spanNs() == 5499 * 2114434LL);
    CHECK(d.periodNs() == 2114434);

    // Strictly increasing timestamps across the whole file.
    for (size_t i = 1; i < d.gyro.size(); ++i)
        CHECK(d.gyro[i - 1].timestampNs < d.gyro[i].timestampNs);

    // Sane handheld ranges, measured on this exact file (see the mission
    // report): gyro |w| in [0.000347, 0.163769] rad/s; accel |a| in
    // [8.5948, 10.7569] m/s^2 with mean 9.8553 — gravity-clustered, which
    // is the empirical proof of the m/s^2 unit interpretation.
    double sumMag = 0.0;
    float minMag = 1e9f, maxMag = 0.0f, maxW = 0.0f;
    for (size_t i = 0; i < d.gyro.size(); ++i) {
        const float w = std::sqrt(d.gyro[i].x * d.gyro[i].x
                                 + d.gyro[i].y * d.gyro[i].y
                                 + d.gyro[i].z * d.gyro[i].z);
        maxW = std::max(maxW, w);
        const float m = std::sqrt(d.accel[i].x * d.accel[i].x
                                 + d.accel[i].y * d.accel[i].y
                                 + d.accel[i].z * d.accel[i].z);
        minMag = std::min(minMag, m);
        maxMag = std::max(maxMag, m);
        sumMag += m;
    }
    CHECK(maxW < 0.2f);          // measured 0.163769
    CHECK(minMag > 8.5f);       // measured 8.5948
    CHECK(maxMag < 10.8f);      // measured 10.7569
    CHECK(sumMag / d.gyro.size() == doctest::Approx(9.8553).epsilon(0.005));
}

TEST_CASE("real_fixture_second_file"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv")) {
        MESSAGE("SKIP: STEPS/media/test/261110_004208_VIDEO_24mm.gcsv not present");
        return;
    }
    auto res = parseGcsv("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv");
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;
    // 5447 data rows (t 0..5446), tscale 0.0021144760 -> 2114476 ns/tick.
    CHECK(d.sampleCount() == 5447);
    CHECK(d.hasAccel());
    CHECK(d.lastTs() == 5446 * 2114476LL);
    CHECK(d.orientation == "XYZ");
}

// ---------------------------------------------------------------------------
// Embedded MCRAW IMU loader
// ---------------------------------------------------------------------------

TEST_CASE("mcraw_synthetic_container_loads_both_streams") {
    // The real fixtures have no embedded IMU, so the populated path is
    // verified against a synthetic container built exactly like the
    // vendored decoder's own DecoderGyroTest.cpp builds one.
    const std::string path = tempPath();
    writeMcraw(path,
               "{\"sensorOrientation\":90,\"timestampSource\":1}",
               &syntheticGyro(), &syntheticAccel());

    auto res = parseImuFromMcraw(path);
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;

    CHECK(d.sampleCount() == 3);
    CHECK(d.hasAccel());
    CHECK(!d.aligned()); // independent streams: different rates/timestamps

    // Gyro values pass through unmodified (already rad/s in the container).
    CHECK(d.gyro[0].timestampNs == 1'000'000'000);
    CHECK(d.gyro[0].x == doctest::Approx(0.1f));
    CHECK(d.gyro[0].y == doctest::Approx(-0.2f));
    CHECK(d.gyro[0].z == doctest::Approx(0.3f));
    CHECK(d.gyro[2].timestampNs == 1'005'000'000);
    CHECK(d.gyro[2].x == doctest::Approx(-0.7f));

    // Accel values pass through unmodified (m/s^2 incl. gravity).
    CHECK(d.accel[0].timestampNs == 1'000'500'000);
    CHECK(d.accel[0].z == doctest::Approx(9.8f));
    CHECK(d.accel[1].timestampNs == 1'003'000'000);

    // Raw container keys recorded for Phase 2/3, never interpreted here.
    CHECK(d.timestampSource == 1);
    CHECK(d.sensorOrientation == 90);
    // MCRAW has no "orientation" header key; it must stay empty, not guessed.
    CHECK(d.orientation.empty());

    std::remove(path.c_str());
}

TEST_CASE("mcraw_gyro_only_container") {
    const std::string path = tempPath();
    writeMcraw(path, "{}", &syntheticGyro(), nullptr);

    auto res = parseImuFromMcraw(path);
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;
    CHECK(d.sampleCount() == 3);
    CHECK(!d.hasAccel());
    CHECK(!d.aligned());
    // Keys absent from the container stay -1 (absent), never guessed.
    CHECK(d.timestampSource == -1);
    CHECK(d.sensorOrientation == -1);

    std::remove(path.c_str());
}

TEST_CASE("mcraw_accel_only_container") {
    // A container with accelerometer but no gyro: the accel stream is
    // loaded and the gyro stream is empty. Unusual, but the format allows
    // it and Phase 2 can still gravity-align.
    const std::string path = tempPath();
    writeMcraw(path, "{}", nullptr, &syntheticAccel());

    auto res = parseImuFromMcraw(path);
    REQUIRE(res.isOk());
    const ImuData& d = *res.value;
    CHECK(d.sampleCount() == 0);
    CHECK(d.hasAccel());
    CHECK(d.accel.size() == 2);
    CHECK(d.firstTs() == 0);
    CHECK(d.lastTs() == 0); // gyro is the primary stream for extent

    std::remove(path.c_str());
}

TEST_CASE("mcraw_without_imu_is_unsupported") {
    // Container with a frame but no gyro/accelerometer items — exactly the
    // shape of the real fixtures (hasGyroData() == 0, hasAccelerometerData()
    // == 0). Unsupported, with a recovery hint pointing at the sidecar.
    const std::string path = tempPath();
    writeMcraw(path, "{}", nullptr, nullptr);

    auto res = parseImuFromMcraw(path);
    REQUIRE(!res.isOk());
    CHECK(res.code == ErrorCode::Unsupported);
    CHECK(res.detail.find("gcsv") != std::string::npos);

    std::remove(path.c_str());
}

TEST_CASE("mcraw_error_mapping") {
    SUBCASE("missing file is io error") {
        auto res = parseImuFromMcraw("/nonexistent/definitely-missing.mcraw");
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::IoError);
    }
    SUBCASE("not an mcraw is bad header") {
        // A file that opens but is not a MotionCam container.
        const std::string path = tempPath();
        {
            std::ofstream out(path, std::ios::binary);
            out << "this is definitely not a motioncam container, just text";
        }
        auto res = parseImuFromMcraw(path);
        REQUIRE(!res.isOk());
        CHECK(res.code == ErrorCode::BadHeader);

        std::remove(path.c_str());
    }
}

TEST_CASE("mcraw_real_fixture_has_no_embedded_imu"
          * doctest::description("real recording; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw")) {
        MESSAGE("SKIP: STEPS/media/test/261110_004150_VIDEO_24mm.mcraw not present");
        return;
    }
    // The real Xiaomi 15 Ultra recordings carry no embedded IMU (verified
    // with a probe against the vendored decoder: hasGyroData() == 0,
    // hasAccelerometerData() == 0). The loader must say exactly that,
    // with the sidecar .gcsv named as the recovery path.
    auto res = parseImuFromMcraw("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw");
    REQUIRE(!res.isOk());
    CHECK(res.code == ErrorCode::Unsupported);
}
