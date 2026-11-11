#include <rawedit/Gyro.h>

#include <motioncam/Decoder.hpp>

#include <cstdio>
#include <istream>
#include <fstream>
#include <utility>

namespace rawedit {

namespace {

// First line of every GyroFlow IMU LOG. "CAMERA IMU LOG" is also seen in
// the wild (telemetry-parser accepts it); no fixture uses it, so it is
// rejected here — extending the signature is a one-line change if a real
// file ever needs it.
constexpr const char* kSignature = "GYROFLOW IMU LOG";

// Column header, the only form the GCSV spec defines and the only one
// accepted (see Gyro.h "Header policy").
constexpr const char* kColumns = "t,gx,gy,gz,ax,ay,az";

// Strips a trailing \r (CRLF files) and any trailing whitespace.
std::string chomp(std::string line) {
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r'
                             || line.back() == ' ' || line.back() == '\t'))
        line.pop_back();
    return line;
}

// Splits on commas, keeping empty fields (the rows end with one).
std::vector<std::string> splitCsv(const std::string& line) {
    std::vector<std::string> fields;
    std::string cur;
    for (const char c : line) {
        if (c == ',') {
            fields.push_back(cur);
            cur.clear();
        } else {
            cur.push_back(c);
        }
    }
    fields.push_back(cur);
    return fields;
}

// Strict integer parse: the whole field must be digits (a leading sign is
// allowed); no whitespace, no trailing junk.
bool parseInt(const std::string& s, long long& out) {
    if (s.empty())
        return false;
    size_t i = 0;
    bool neg = false;
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-';
        i = 1;
        if (s.size() == 1)
            return false;
    }
    long long v = 0;
    for (; i < s.size(); ++i) {
        if (s[i] < '0' || s[i] > '9')
            return false;
        v = v * 10 + (s[i] - '0');
        if (v > 9223372036854775807LL) // guard before the negation below
            return false;
    }
    out = neg ? -v : v;
    return true;
}

// Strict decimal parse for the header scales (tscale/gscale/ascale):
// optional sign, digits, optional fractional part. No exponent support —
// the fixtures use plain decimals ("0.0021144340", "0.0000000100").
bool parseDouble(const std::string& s, double& out) {
    if (s.empty())
        return false;
    size_t i = 0;
    bool neg = false;
    if (s[0] == '+' || s[0] == '-') {
        neg = s[0] == '-';
        i = 1;
    }
    double v = 0.0;
    bool anyDigits = false;
    for (; i < s.size() && s[i] >= '0' && s[i] <= '9'; ++i) {
        v = v * 10.0 + (s[i] - '0');
        anyDigits = true;
    }
    if (i < s.size() && s[i] == '.') {
        ++i;
        double scale = 0.1;
        for (; i < s.size() && s[i] >= '0' && s[i] <= '9'; ++i) {
            v += (s[i] - '0') * scale;
            scale *= 0.1;
            anyDigits = true;
        }
    }
    if (!anyDigits || i != s.size())
        return false;
    out = neg ? -v : v;
    return true;
}

// Row shapes, classified by field count. See Gyro.h "Row policy": the first
// data row fixes the shape family and every later row must repeat it.
enum class RowShape {
    Malformed,
    GyroOnly,   // t,gx,gy,gz[,,]       (4 or 5 fields)
    WithAccel,  // t,gx,gy,gz,ax,ay,az[,]
};

RowShape classifyRow(const std::vector<std::string>& f) {
    const size_t nf = f.size();
    if (nf == 8 && f[7].empty())
        return RowShape::WithAccel;   // fixture shape: trailing comma
    if (nf == 7 && !f[4].empty() && !f[5].empty() && !f[6].empty())
        return RowShape::WithAccel;   // no trailing comma
    if (nf == 4 && !f[3].empty())
        return RowShape::GyroOnly;
    if (nf == 5 && f[4].empty())
        return RowShape::GyroOnly;
    return RowShape::Malformed;
}

// Maps a caught motioncam exception onto an ErrorCode. Mirrors
// classifyError in Source.cpp (kept local so this file stays independent;
// the two must stay in sync). Distinguishes "not an MCRAW at all"
// (BadHeader) from "an MCRAW damaged mid-file" (Truncated).
ErrorCode classifyDecoderError(const std::string& what) {
    if (what.find("Invalid header id") != std::string::npos
        || what.find("Invalid container version") != std::string::npos
        || what.find("Invalid camera metadata") != std::string::npos
        || what.find("Failed to open") != std::string::npos
        || what.find("Invalid file descriptor") != std::string::npos
        || what.find("failed to open") != std::string::npos) {
        return ErrorCode::BadHeader;
    }
    return ErrorCode::Truncated;
}

// Reads one integer container-metadata key into `out` (no change when the
// key is absent or not a number).
void getJsonInt(const nlohmann::json& j, const char* key, int& out) {
    const auto it = j.find(key);
    if (it != j.end() && it->is_number())
        out = it->get<int>();
}

} // namespace

bool ImuData::aligned() const {
    if (accel.size() != gyro.size())
        return false;
    for (size_t i = 0; i < gyro.size(); ++i)
        if (gyro[i].timestampNs != accel[i].timestampNs)
            return false;
    return true;
}

int64_t ImuData::firstTs() const {
    return gyro.empty() ? 0 : gyro.front().timestampNs;
}

int64_t ImuData::lastTs() const {
    return gyro.empty() ? 0 : gyro.back().timestampNs;
}

int64_t ImuData::spanNs() const {
    return lastTs() - firstTs();
}

int64_t ImuData::periodNs() const {
    if (gyro.size() < 2)
        return 0;
    return spanNs() / static_cast<int64_t>(gyro.size() - 1);
}

Result<ImuData> parseGcsv(std::istream& in) {
    ImuData data;

    std::string line;
    if (!std::getline(in, line))
        return Result<ImuData>::fail(ErrorCode::Truncated,
            "gcsv: empty stream (expected the GYROFLOW IMU LOG signature)");
    if (chomp(line) != kSignature)
        return Result<ImuData>::fail(ErrorCode::BadHeader,
            "gcsv: line 1 is not the GYROFLOW IMU LOG signature");

    // key,value header lines until the column header. tscale and gscale are
    // required; ascale is required only when the data rows turn out to carry
    // accelerometer columns (checked below, once the shape is known);
    // orientation and timestamp are carried along (the sync anchors:
    // the axis remap and ImuData.logStartWallMs); version/id/fwversion/
    // vendor and unknown keys are ignored. A key without a value is
    // malformed.
    double tscale = 0.0;
    double gscale = 0.0;
    double ascale = 0.0;
    bool sawColumns = false;
    long lineNo = 1;
    while (std::getline(in, line)) {
        ++lineNo;
        const std::string row = chomp(line);
        if (row.empty())
            continue; // tolerate stray blank lines in the header block

        if (row == kColumns) {
            sawColumns = true;
            break;
        }

        const std::vector<std::string> kv = splitCsv(row);
        if (kv.size() != 2 || kv[0].empty() || kv[1].empty())
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo)
                + " is not a key,value header or the column header: \"" + row + "\"");
        if (kv[0] == "tscale") {
            if (!parseDouble(kv[1], tscale) || tscale <= 0.0)
                return Result<ImuData>::fail(ErrorCode::BadHeader,
                    "gcsv: line " + std::to_string(lineNo) + " has a bad tscale: \"" + kv[1] + "\"");
        } else if (kv[0] == "gscale") {
            if (!parseDouble(kv[1], gscale) || gscale <= 0.0)
                return Result<ImuData>::fail(ErrorCode::BadHeader,
                    "gcsv: line " + std::to_string(lineNo) + " has a bad gscale: \"" + kv[1] + "\"");
        } else if (kv[0] == "ascale") {
            if (!parseDouble(kv[1], ascale) || ascale <= 0.0)
                return Result<ImuData>::fail(ErrorCode::BadHeader,
                    "gcsv: line " + std::to_string(lineNo) + " has a bad ascale: \"" + kv[1] + "\"");
        } else if (kv[0] == "orientation") {
            data.orientation = kv[1];
        } else if (kv[0] == "timestamp") {
            // Optional sync anchor (stabilization Phase 3/5): epoch-ms
            // wall time of the log start. Unparseable or non-positive
            // keeps the -1 sentinel — absent is the honest state, sync
            // degrades to ManualRequired downstream; a bad optional key
            // does not fail the whole file.
            long long ts = -1;
            if (parseInt(kv[1], ts) && ts > 0)
                data.logStartWallMs = ts;
        }
        // version, id, fwversion, vendor and any unknown key:
        // shape-checked above, otherwise ignored.
    }
    if (!sawColumns)
        return Result<ImuData>::fail(ErrorCode::Truncated,
            "gcsv: stream ended before the \"" + std::string(kColumns) + "\" column header");
    if (tscale <= 0.0 || gscale <= 0.0)
        return Result<ImuData>::fail(ErrorCode::BadHeader,
            "gcsv: missing tscale/gscale header (both are required to scale samples)");

    // Data rows. The first row fixes the shape (gyro-only vs accelerometer
    // columns); every later row must repeat it.
    bool expectAccel = false;
    bool shapeFixed = false;
    long long prevTick = -1;
    bool havePrev = false;
    while (std::getline(in, line)) {
        ++lineNo;
        const std::string row = chomp(line);
        if (row.empty())
            continue; // tolerate stray blank lines between data rows

        const std::vector<std::string> f = splitCsv(row);
        const RowShape shape = classifyRow(f);
        if (shape == RowShape::Malformed)
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " has " + std::to_string(f.size())
                + " fields, expected t,gx,gy,gz[,ax,ay,az,]: \"" + row + "\"");

        const bool rowHasAccel = shape == RowShape::WithAccel;
        if (!shapeFixed) {
            expectAccel = rowHasAccel;
            shapeFixed = true;
            if (expectAccel && ascale <= 0.0)
                return Result<ImuData>::fail(ErrorCode::BadHeader,
                    "gcsv: data rows carry accelerometer columns but the header has no ascale");
        } else if (rowHasAccel != expectAccel) {
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " switches row shape (accelerometer columns"
                " come and go); a log must be gyro-only or gyro+accel throughout");
        }

        long long t = 0, gx = 0, gy = 0, gz = 0;
        if (!parseInt(f[0], t) || !parseInt(f[1], gx) || !parseInt(f[2], gy)
            || !parseInt(f[3], gz))
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " has a non-integer field: \"" + row + "\"");
        long long ax = 0, ay = 0, az = 0;
        if (expectAccel
            && (!parseInt(f[4], ax) || !parseInt(f[5], ay) || !parseInt(f[6], az)))
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " has a non-integer field: \"" + row + "\"");

        if (havePrev && t <= prevTick)
            return Result<ImuData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " tick " + std::to_string(t)
                + " does not increase (previous " + std::to_string(prevTick) + ")");
        prevTick = t;
        havePrev = true;

        // Unit chain (Gyro.h has the full derivation): t*tscale seconds ->
        // ns, rounded; gyro counts * gscale -> rad/s; accel counts * ascale
        // -> m/s^2. Each scale is applied exactly once.
        const int64_t ts = static_cast<int64_t>(
            static_cast<double>(t) * tscale * 1e9 + 0.5);
        ImuSample g;
        g.timestampNs = ts;
        g.x = static_cast<float>(static_cast<double>(gx) * gscale);
        g.y = static_cast<float>(static_cast<double>(gy) * gscale);
        g.z = static_cast<float>(static_cast<double>(gz) * gscale);
        data.gyro.push_back(g);

        if (expectAccel) {
            ImuSample a;
            a.timestampNs = ts; // same row: index-locked with the gyro sample
            a.x = static_cast<float>(static_cast<double>(ax) * ascale);
            a.y = static_cast<float>(static_cast<double>(ay) * ascale);
            a.z = static_cast<float>(static_cast<double>(az) * ascale);
            data.accel.push_back(a);
        }
    }

    if (data.gyro.empty())
        return Result<ImuData>::fail(ErrorCode::Truncated,
            "gcsv: column header found but no data rows followed");

    return Result<ImuData>::ok(std::move(data));
}

Result<ImuData> parseGcsv(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return Result<ImuData>::fail(ErrorCode::IoError,
            "gcsv: failed to open " + path + " (recovery: check the path and read permission)");
    auto res = parseGcsv(in);
    if (!in.bad())
        return res;
    return Result<ImuData>::fail(ErrorCode::IoError,
        "gcsv: stream error while reading " + path);
}

namespace {

// Appends one loaded motioncam stream to `out`, rejecting a backwards
// timestamp jump as corruption (equal timestamps are kept — resampling is
// Phase 2's business). detail names the stream and the sample index.
Result<void> appendStream(std::vector<ImuSample>& out,
                           const std::vector<motioncam::MotionSample>& raw,
                           const char* streamName) {
    for (size_t i = 0; i < raw.size(); ++i) {
        if (i > 0 && raw[i].timestampNs < raw[i - 1].timestampNs)
            return Result<void>::fail(ErrorCode::BadHeader,
                std::string("mcraw imu: ") + streamName + " timestamp jumps backwards at sample "
                + std::to_string(i) + " (corrupt index; recovery: re-export the recording)");
        ImuSample s;
        s.timestampNs = raw[i].timestampNs;
        s.x = raw[i].x;
        s.y = raw[i].y;
        s.z = raw[i].z;
        out.push_back(s);
    }
    return Result<void>::ok();
}

} // namespace

Result<ImuData> parseImuFromMcraw(const std::string& path) {
    // Pre-check with fopen so "cannot open the path" (IoError) is
    // distinguishable from "opened, but not an MCRAW" (BadHeader) — the
    // decoder throws the same string for both.
    if (std::FILE* probe = std::fopen(path.c_str(), "rb")) {
        std::fclose(probe);
    } else {
        return Result<ImuData>::fail(ErrorCode::IoError,
            "mcraw imu: failed to open " + path + " (recovery: check the path and read permission)");
    }

    ImuData data;
    try {
        // Own read-only decoder: no frames are decoded, only the index is
        // walked. One Decoder per call; not for concurrent use.
        motioncam::Decoder decoder(path);

        // Raw container keys for the later phases (never interpreted here).
        data.timestampSource = -1;
        data.sensorOrientation = -1;
        data.lensFacing = -1;
        const nlohmann::json& cm = decoder.getContainerMetadata();
        getJsonInt(cm, "timestampSource", data.timestampSource);
        getJsonInt(cm, "sensorOrientation", data.sensorOrientation);
        getJsonInt(cm, "lensFacing", data.lensFacing);

        const bool hasGyro = decoder.hasGyroData();
        const bool hasAccel = decoder.hasAccelerometerData();
        if (!hasGyro && !hasAccel)
            return Result<ImuData>::fail(ErrorCode::Unsupported,
                "mcraw imu: " + path + " has no embedded gyro or accelerometer data"
                " (MotionCam only writes embedded IMU when the phone exposes it;"
                " recovery: use the sidecar .gcsv)");

        // Two independent streams (Gyro.h explains why they are not merged):
        // gyro in rad/s, accelerometer in m/s^2 including gravity, both on
        // the container's nanosecond timeline that getFrames() also uses.
        if (hasGyro) {
            std::vector<motioncam::MotionSample> raw;
            decoder.loadGyroData(raw);
            if (auto res = appendStream(data.gyro, raw, "gyro"); !res.isOk())
                return Result<ImuData>::fail(res.code, res.detail);
        }
        if (hasAccel) {
            std::vector<motioncam::MotionSample> raw;
            decoder.loadAccelerometerData(raw);
            if (auto res = appendStream(data.accel, raw, "accelerometer"); !res.isOk())
                return Result<ImuData>::fail(res.code, res.detail);
        }
    } catch (const std::exception& e) {
        return Result<ImuData>::fail(classifyDecoderError(e.what()),
            std::string("mcraw imu: ") + e.what()
            + " (recovery: verify the file is a complete MotionCam recording)");
    } catch (...) {
        return Result<ImuData>::fail(ErrorCode::IoError,
            "mcraw imu: unknown decoder error (recovery: verify the file is a"
            " complete MotionCam recording)");
    }

    return Result<ImuData>::ok(std::move(data));
}

} // namespace rawedit
