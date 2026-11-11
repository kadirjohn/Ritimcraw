// rawedit LUT — parse + apply. See rawedit/Lut.h for the format and the
// COL-07 color-space contract.

#include <rawedit/Lut.h>

#include <rawedit/Develop.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <istream>
#include <sstream>

namespace rawedit {

namespace {

// The .cube spec's practical grid bound (Adobe tools cap at 128^3).
constexpr int kMaxN = 128;

bool finite3(float r, float g, float b) {
    return std::isfinite(r) && std::isfinite(g) && std::isfinite(b);
}

// Splits a data row. Returns false unless the line is exactly three
// finite floats (a trailing comment is tolerated — spec examples show
// them, and authors use them).
bool parseRow(const std::string& line, float out[3]) {
    std::istringstream ls(line);
    float r, g, b;
    if (!(ls >> r >> g >> b))
        return false;
    std::string rest;
    if (ls >> rest && rest != "#")
        return false;
    if (!finite3(r, g, b))
        return false;
    out[0] = r;
    out[1] = g;
    out[2] = b;
    return true;
}

} // namespace

Result<Lut> parseCube(std::istream& in) {
    Lut lut;
    bool sawSize = false;
    std::string line;
    long rows = 0;
    long need = 0;

    while (std::getline(in, line)) {
        // Strip comments (an unquoted '#' to end of line) and trim.
        const std::string::size_type hash = line.find('#');
        if (hash != std::string::npos)
            line = line.substr(0, hash);
        const char ws[] = " \t\r\n";
        const auto first = line.find_first_not_of(ws);
        if (first == std::string::npos)
            continue; // blank
        const auto last = line.find_last_not_of(ws);
        line = line.substr(first, last - first + 1);

        // The .cube convention: a line starting with a letter is a
        // keyword line; everything else is a data row. The split is
        // unambiguous — data rows start with a sign, digit or dot.
        if (std::isalpha(static_cast<unsigned char>(line[0])) != 0) {
            std::istringstream ls(line);
            std::string kw;
            ls >> kw;
            if (kw == "LUT_1D_SIZE") {
                return Result<Lut>::fail(
                    ErrorCode::Unsupported,
                    "lut: LUT_1D_SIZE is not supported — only 3D cubes");
            }
            if (kw == "LUT_3D_SIZE") {
                if (sawSize)
                    return Result<Lut>::fail(
                        ErrorCode::BadHeader,
                        "lut: duplicate LUT_3D_SIZE line");
                int n = 0;
                if (!(ls >> n) || n < 2)
                    return Result<Lut>::fail(
                        ErrorCode::BadHeader,
                        "lut: LUT_3D_SIZE must be an integer >= 2");
                std::string extra;
                if (ls >> extra)
                    return Result<Lut>::fail(
                        ErrorCode::BadHeader,
                        "lut: LUT_3D_SIZE must be an integer >= 2");
                if (n > kMaxN)
                    return Result<Lut>::fail(
                        ErrorCode::Unsupported,
                        "lut: LUT_3D_SIZE " + std::to_string(n) +
                            " exceeds the 128-point bound");
                lut.n = n;
                need = static_cast<long>(n) * n * n;
                sawSize = true;
                lut.rgb.reserve(static_cast<size_t>(need) * 3);
                continue;
            }
            // The spec's own example puts DOMAIN_MIN/MAX between the
            // size line and the table, so keywords are accepted both
            // before LUT_3D_SIZE and after it — until the first data row
            // lands. Past that point the table is size-declared, and a
            // keyword line inside it means the file is not what it
            // claims.
            if (rows > 0)
                return Result<Lut>::fail(
                    ErrorCode::BadHeader,
                    "lut: keyword line inside the table");
            if (kw == "DOMAIN_MIN" || kw == "DOMAIN_MAX") {
                float v[3];
                if (!parseRow(line.substr(kw.size()), v))
                    return Result<Lut>::fail(
                        ErrorCode::BadHeader,
                        "lut: " + kw + " needs three numbers");
                float* dst = kw == "DOMAIN_MIN" ? lut.domainMin : lut.domainMax;
                dst[0] = v[0];
                dst[1] = v[1];
                dst[2] = v[2];
                continue;
            }
            if (kw == "TITLE") {
                // TITLE is a quoted string; keep the inner text.
                std::string rest = line.substr(kw.size());
                const auto q1 = rest.find('"');
                if (q1 != std::string::npos) {
                    const auto q2 = rest.find('"', q1 + 1);
                    lut.title = rest.substr(
                        q1 + 1, q2 == std::string::npos
                                    ? std::string::npos
                                    : q2 - q1 - 1);
                }
                continue;
            }
            // Unknown keyword: skip (spec advice for forward
            // compatibility).
            continue;
        }

        // A data row.
        if (!sawSize)
            return Result<Lut>::fail(
                ErrorCode::BadHeader,
                "lut: data row before LUT_3D_SIZE");
        float v[3];
        if (!parseRow(line, v))
            return Result<Lut>::fail(
                ErrorCode::BadHeader,
                "lut: malformed table row " + std::to_string(rows + 1) +
                    " — expected three numbers");
        if (rows >= need)
            return Result<Lut>::fail(
                ErrorCode::BadHeader,
                "lut: more than " + std::to_string(need) +
                    " table rows for LUT_3D_SIZE " + std::to_string(lut.n));
        lut.rgb.push_back(v[0]);
        lut.rgb.push_back(v[1]);
        lut.rgb.push_back(v[2]);
        ++rows;
    }

    if (!sawSize)
        return Result<Lut>::fail(
            ErrorCode::BadHeader, "lut: missing LUT_3D_SIZE line");
    if (rows < need)
        return Result<Lut>::fail(
            ErrorCode::Truncated,
            "lut: table has " + std::to_string(rows) + " of " +
                std::to_string(need) + " rows");
    for (int c = 0; c < 3; ++c) {
        if (!(lut.domainMax[c] > lut.domainMin[c]))
            return Result<Lut>::fail(
                ErrorCode::BadHeader,
                "lut: DOMAIN_MAX must exceed DOMAIN_MIN on every axis");
    }
    return Result<Lut>::ok(std::move(lut));
}

Result<Lut> parseCubeText(const std::string& text) {
    std::istringstream in(text);
    return parseCube(in);
}

Result<Lut> parseCubeFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return Result<Lut>::fail(
            ErrorCode::IoError, "lut: cannot open " + path);
    return parseCube(in);
}

void applyLut(ImageF& img, const Lut& lut, float strength) {
    const int n = lut.n;
    if (n < 2)
        return;
    if (lut.rgb.size() != static_cast<size_t>(n) * n * n * 3)
        return;
    if (!(strength > 0.0f))
        return; // 0 (or NaN) = exact bypass
    const float s = std::min(strength, 1.0f);

    const size_t pixels =
        static_cast<size_t>(img.w) * img.h;
    if (pixels == 0 || img.rgb.size() != pixels * 3)
        return;

    // Domain per axis, pre-scaled so a lattice coordinate is
    // (x - domainMin) * scale with clamping to [0, n-1].
    float scale[3];
    for (int c = 0; c < 3; ++c)
        scale[c] = static_cast<float>(n - 1) /
                   std::max(lut.domainMax[c] - lut.domainMin[c], 1e-20f);

    for (size_t p = 0; p < pixels; ++p) {
        float* px = &img.rgb[p * 3];

        // Lattice coordinates, clamped to the domain edges (.cube
        // convention: outside-domain inputs sample the edge).
        float fx[3];
        int i0[3], i1[3];
        float w1[3];
        for (int c = 0; c < 3; ++c) {
            fx[c] = (px[c] - lut.domainMin[c]) * scale[c];
            fx[c] = std::min(std::max(fx[c], 0.0f), static_cast<float>(n - 1));
            i0[c] = static_cast<int>(fx[c]);
            i0[c] = std::min(i0[c], n - 2); // fx == n-1 lands on the last cell
            i1[c] = i0[c] + 1;
            w1[c] = fx[c] - static_cast<float>(i0[c]);
        }

        // Trilinear over the 8 surrounding lattice entries. Entry
        // (i, j, k) is at rgb[(i + n*j + n*n*k) * 3].
        float out[3] = {0.0f, 0.0f, 0.0f};
        for (int dk = 0; dk <= 1; ++dk) {
            const float wk = dk == 0 ? 1.0f - w1[2] : w1[2];
            const size_t baseK =
                static_cast<size_t>(dk ? i1[2] : i0[2]) * n * n;
            for (int dj = 0; dj <= 1; ++dj) {
                const float wj = dj == 0 ? 1.0f - w1[1] : w1[1];
                const float wkj = wk * wj;
                const size_t baseJ =
                    static_cast<size_t>(dj ? i1[1] : i0[1]) * n;
                for (int di = 0; di <= 1; ++di) {
                    const float wi = di == 0 ? 1.0f - w1[0] : w1[0];
                    const float w = wkj * wi;
                    const size_t idx =
                        (baseK + baseJ + static_cast<size_t>(di ? i1[0] : i0[0])) * 3;
                    out[0] += w * lut.rgb[idx + 0];
                    out[1] += w * lut.rgb[idx + 1];
                    out[2] += w * lut.rgb[idx + 2];
                }
            }
        }

        // Strength blend against the original pixel.
        px[0] = out[0] * s + px[0] * (1.0f - s);
        px[1] = out[1] * s + px[1] * (1.0f - s);
        px[2] = out[2] * s + px[2] * (1.0f - s);
    }
}

} // namespace rawedit
