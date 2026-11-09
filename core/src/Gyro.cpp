#include <rawedit/Gyro.h>

#include <fstream>
#include <istream>
#include <utility>

namespace rawedit {

namespace {

// First line of every GyroFlow IMU LOG.
constexpr const char* kSignature = "GYROFLOW IMU LOG";

// Column header the fixtures use. The trailing comma of the data rows makes
// the header itself 7 fields (the rows' 8th field is empty).
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

} // namespace

Result<GyroData> parseGcsv(std::istream& in) {
    GyroData data;

    std::string line;
    if (!std::getline(in, line))
        return Result<GyroData>::fail(ErrorCode::Truncated,
            "gcsv: empty stream (expected the GYROFLOW IMU LOG signature)");
    if (chomp(line) != kSignature)
        return Result<GyroData>::fail(ErrorCode::BadHeader,
            "gcsv: line 1 is not the GYROFLOW IMU LOG signature");

    // key,value header lines until the column header. tscale and gscale are
    // required; version/id/orientation/fwversion/timestamp/vendor/ascale are
    // carried along or ignored. A key without a value is malformed.
    double tscale = 0.0;
    double gscale = 0.0;
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
            return Result<GyroData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo)
                + " is not a key,value header or the column header: \"" + row + "\"");
        if (kv[0] == "tscale") {
            if (!parseDouble(kv[1], tscale) || tscale <= 0.0)
                return Result<GyroData>::fail(ErrorCode::BadHeader,
                    "gcsv: line " + std::to_string(lineNo) + " has a bad tscale: \"" + kv[1] + "\"");
        } else if (kv[0] == "gscale") {
            if (!parseDouble(kv[1], gscale) || gscale <= 0.0)
                return Result<GyroData>::fail(ErrorCode::BadHeader,
                    "gcsv: line " + std::to_string(lineNo) + " has a bad gscale: \"" + kv[1] + "\"");
        } else if (kv[0] == "orientation") {
            data.orientation = kv[1];
        }
        // version, id, fwversion, timestamp, vendor, ascale: shape-checked
        // above, otherwise ignored by the gyro path.
    }
    if (!sawColumns)
        return Result<GyroData>::fail(ErrorCode::Truncated,
            "gcsv: stream ended before the \"" + std::string(kColumns) + "\" column header");
    if (tscale <= 0.0 || gscale <= 0.0)
        return Result<GyroData>::fail(ErrorCode::BadHeader,
            "gcsv: missing tscale/gscale header (both are required to scale samples)");

    // Data rows: t,gx,gy,gz,ax,ay,az,  (trailing comma -> 8 fields, last empty).
    // Rows with fewer fields (e.g. a gyro-only t,gx,gy,gz, log) are accepted as
    // long as they carry t plus the three gyro columns.
    long long prevTick = -1;
    bool havePrev = false;
    while (std::getline(in, line)) {
        ++lineNo;
        const std::string row = chomp(line);
        if (row.empty())
            continue;

        const std::vector<std::string> f = splitCsv(row);
        // Gyro-only logs stop after gz (4 fields); the fixtures have 8 with
        // an empty trailing field. Anything in between must still end with
        // an empty field or a real value — only exact shapes are accepted.
        const size_t nf = f.size();
        const bool fixtureShape = (nf == 8 && f[7].empty())
            || (nf == 7 && !f[6].empty())
            || (nf == 4 && !f[3].empty());
        if (!fixtureShape)
            return Result<GyroData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " has " + std::to_string(nf)
                + " fields, expected t,gx,gy,gz[,ax,ay,az,]: \"" + row + "\"");

        long long t = 0, gx = 0, gy = 0, gz = 0;
        if (!parseInt(f[0], t) || !parseInt(f[1], gx) || !parseInt(f[2], gy)
            || !parseInt(f[3], gz))
            return Result<GyroData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " has a non-integer field: \"" + row + "\"");

        if (havePrev && t <= prevTick)
            return Result<GyroData>::fail(ErrorCode::BadHeader,
                "gcsv: line " + std::to_string(lineNo) + " tick " + std::to_string(t)
                + " does not increase (previous " + std::to_string(prevTick) + ")");
        prevTick = t;
        havePrev = true;

        GyroSample s;
        s.timestampNs = static_cast<int64_t>(static_cast<double>(t) * tscale * 1e9 + 0.5);
        s.x = static_cast<float>(static_cast<double>(gx) * gscale);
        s.y = static_cast<float>(static_cast<double>(gy) * gscale);
        s.z = static_cast<float>(static_cast<double>(gz) * gscale);
        data.samples.push_back(s);
    }

    if (data.samples.empty())
        return Result<GyroData>::fail(ErrorCode::Truncated,
            "gcsv: column header found but no data rows followed");

    return Result<GyroData>::ok(std::move(data));
}

Result<GyroData> parseGcsv(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return Result<GyroData>::fail(ErrorCode::IoError,
            "gcsv: failed to open " + path + " (recovery: check the path and read permission)");
    auto res = parseGcsv(in);
    if (!in.bad())
        return res;
    return Result<GyroData>::fail(ErrorCode::IoError,
        "gcsv: stream error while reading " + path);
}

} // namespace rawedit
