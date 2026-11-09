#include <rawedit/Develop.h>

#include <algorithm>
#include <cmath>

namespace rawedit {

namespace {

// Site color codes within the repeating 2x2 CFA cell: 0 = R, 1 = G, 2 = B.
// cell[cellY][cellX] gives the color of the site at (even+cellX, even+cellY).
struct CfaLayout {
    int cell[2][2];
};

CfaLayout layoutFor(CfaPattern p) {
    switch (p) {
        case CfaPattern::RGGB: return CfaLayout{{{0, 1}, {1, 2}}};
        case CfaPattern::GRBG: return CfaLayout{{{1, 0}, {2, 1}}};
        case CfaPattern::GBRG: return CfaLayout{{{1, 2}, {0, 1}}};
        case CfaPattern::BGGR: return CfaLayout{{{2, 1}, {1, 0}}};
    }
    return CfaLayout{{{0, 1}, {1, 2}}};
}

// blackLevel[4] and the shading map planes are indexed by 2x2 cell position
// (cellY*2 + cellX), matching the container's 2x2 (r, g, g, b) ordering.
inline int cellSlot(int cellY, int cellX) { return cellY * 2 + cellX; }

// wbGains index for a site color: 0 = R, 1 = G, 2 = B.
inline int wbIndex(int siteColor) { return siteColor; }

} // namespace

float sampleShading(const RawMeta& meta, float u, float v, int plane) {
    if (meta.lensShading.empty() || meta.shadingW == 0 || meta.shadingH == 0)
        return 1.0f; // identity when the map is empty

    const size_t planeSize = static_cast<size_t>(meta.shadingW) * meta.shadingH;
    if (plane < 0 || static_cast<size_t>(plane) >= meta.lensShading.size() / planeSize)
        return 1.0f;

    const float* p = meta.lensShading.data() + static_cast<size_t>(plane) * planeSize;

    // Bilinear sample at normalized sensor coordinates, clamped to the edges.
    const float w = static_cast<float>(meta.shadingW);
    const float h = static_cast<float>(meta.shadingH);
    const float fx = std::min(std::max(u, 0.0f), 1.0f) * (w - 1.0f);
    const float fy = std::min(std::max(v, 0.0f), 1.0f) * (h - 1.0f);
    const int x0 = static_cast<int>(fx);
    const int y0 = static_cast<int>(fy);
    const int x1 = std::min(x0 + 1, static_cast<int>(meta.shadingW) - 1);
    const int y1 = std::min(y0 + 1, static_cast<int>(meta.shadingH) - 1);
    const float tx = fx - static_cast<float>(x0);
    const float ty = fy - static_cast<float>(y0);

    const auto row = [&](int y, int x) {
        return p[static_cast<size_t>(y) * meta.shadingW + x];
    };
    const float a = row(y0, x0) + (row(y0, x1) - row(y0, x0)) * tx;
    const float b = row(y1, x0) + (row(y1, x1) - row(y1, x0)) * tx;
    return a + (b - a) * ty;
}

// Bilinear demosaic of a float CFA into linear RGB. For every pixel and every
// output channel: the pixel's own color is taken as-is; other channels are the
// average of the nearest sites of that color in the 8-neighborhood (with edge
// clamping). For a Bayer pattern every 8-neighborhood of a site contains sites
// of both other colors, so this is a single uniform rule for all four patterns.
ImageF demosaicBilinear(const std::vector<float>& cfa, uint32_t w, uint32_t h, CfaPattern pattern) {
    ImageF img;
    img.w = w;
    img.h = h;
    img.rgb.assign(static_cast<size_t>(w) * h * 3, 0.0f);

    const CfaLayout layout = layoutFor(pattern);
    const int maxI = static_cast<int>(w) - 1;
    const int maxJ = static_cast<int>(h) - 1;

    const auto siteAt = [&](int x, int y) {
        return layout.cell[y & 1][x & 1];
    };
    const auto at = [&](int x, int y) {
        return cfa[static_cast<size_t>(y) * w + x];
    };

    static const int kDx[8] = {-1, 1, 0, 0, -1, -1, 1, 1};
    static const int kDy[8] = {0, 0, -1, 1, -1, 1, -1, 1};

    for (int y = 0; y <= maxJ; ++y) {
        for (int x = 0; x <= maxI; ++x) {
            const int ownSite = siteAt(x, y);
            float* px = &img.rgb[(static_cast<size_t>(y) * w + x) * 3];

            for (int t = 0; t < 3; ++t) {
                if (t == ownSite) {
                    px[t] = at(x, y);
                    continue;
                }
                float sum = 0.0f;
                int n = 0;
                for (int k = 0; k < 8; ++k) {
                    const int nx = std::min(std::max(x + kDx[k], 0), maxI);
                    const int ny = std::min(std::max(y + kDy[k], 0), maxJ);
                    if (siteAt(nx, ny) == t) {
                        sum += at(nx, ny);
                        ++n;
                    }
                }
                px[t] = n > 0 ? sum / static_cast<float>(n) : at(x, y);
            }
        }
    }

    return img;
}

ImageF developCpu(const uint16_t* cfa, const FrameInfo& info, const RawMeta& meta, const Grade& grade) {
    const uint32_t w = info.width;
    const uint32_t h = info.height;
    ImageF img;

    if (w == 0 || h == 0 || cfa == nullptr)
        return img;

    const CfaLayout layout = layoutFor(info.cfa);
    const size_t n = static_cast<size_t>(w) * h;

    // ---- Stages 1-3 operate on the CFA, per site. ----
    std::vector<float> norm(n);

    // Stage 1: black/white normalization per CFA channel.
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = cellSlot(y & 1, x & 1);
            const float black = static_cast<float>(meta.black[slot]);
            const float white = static_cast<float>(meta.white);
            const float range = white > black ? white - black : 1.0f;
            float v = (static_cast<float>(cfa[y * static_cast<size_t>(w) + x]) - black) / range;
            norm[y * static_cast<size_t>(w) + x] = std::min(std::max(v, 0.0f), 1.0f);
        }
    }

    // Stage 2: lens shading gain (bilinear on the shading map in sensor
    // coordinates; identity when the map is empty).
    if (!meta.lensShading.empty() && meta.shadingW > 0 && meta.shadingH > 0) {
        const float du = w > 1 ? 1.0f / static_cast<float>(w - 1) : 0.0f;
        const float dv = h > 1 ? 1.0f / static_cast<float>(h - 1) : 0.0f;
        for (uint32_t y = 0; y < h; ++y) {
            for (uint32_t x = 0; x < w; ++x) {
                const int slot = cellSlot(y & 1, x & 1);
                const float gain = sampleShading(meta,
                    static_cast<float>(x) * du, static_cast<float>(y) * dv, slot);
                norm[y * static_cast<size_t>(w) + x] *= gain;
            }
        }
    }

    // Stage 3: highlight reconstruction — a channel clipped at white carries
    // no level information, so it is capped at the level the still-unclipped
    // channels in its 2x2 cell carry; that keeps clipped areas neutral instead
    // of magenta. Per cell, per color: track the minimum normalized level and
    // whether every site of that color is clipped. The ceiling L is the
    // lowest post-WB level among colors that still have an unclipped site
    // (when every color is clipped, among all colors — a uniform fully
    // clipped patch then stays at 1.0). Every site at or above white is then
    // clipped to the pre-WB value that maps to L (L / gain).
    {
        const float gain[3] = {
            meta.wbGains[0] > 0.0f ? meta.wbGains[0] : 1.0f,
            meta.wbGains[1] > 0.0f ? meta.wbGains[1] : 1.0f,
            meta.wbGains[2] > 0.0f ? meta.wbGains[2] : 1.0f,
        };
        const uint32_t cellCols = (w + 1) / 2;
        const uint32_t cellRows = (h + 1) / 2;
        for (uint32_t cy = 0; cy < cellRows; ++cy) {
            for (uint32_t cx = 0; cx < cellCols; ++cx) {
                const int x0 = static_cast<int>(cx * 2);
                const int y0 = static_cast<int>(cy * 2);
                const int x1 = std::min(x0 + 1, static_cast<int>(w) - 1);
                const int y1 = std::min(y0 + 1, static_cast<int>(h) - 1);

                float minV[3] = {-1.0f, -1.0f, -1.0f};
                bool clipped[3] = {false, false, false};
                bool present[3] = {false, false, false};
                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        const int color = layout.cell[yy & 1][xx & 1];
                        const float v = norm[static_cast<size_t>(yy) * w + xx];
                        present[color] = true;
                        if (minV[color] < 0.0f || v < minV[color])
                            minV[color] = v;
                        if (v >= 1.0f)
                            clipped[color] = true;
                    }
                }

                // Ceiling from colors that still have unclipped sites.
                float ceiling = -1.0f;
                for (int c = 0; c < 3; ++c) {
                    if (!present[c] || clipped[c])
                        continue;
                    const float level = minV[c] * gain[c];
                    if (ceiling < 0.0f || level < ceiling)
                        ceiling = level;
                }
                // Every color clipped: fall back to the lowest level overall
                // (uniform fully-clipped patches stay at 1.0).
                if (ceiling < 0.0f) {
                    for (int c = 0; c < 3; ++c) {
                        if (!present[c])
                            continue;
                        const float level = minV[c] * gain[c];
                        if (ceiling < 0.0f || level < ceiling)
                            ceiling = level;
                    }
                }
                if (ceiling < 0.0f)
                    continue;

                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        const size_t idx = static_cast<size_t>(yy) * w + xx;
                        if (norm[idx] < 1.0f)
                            continue; // unclipped sites keep their real level
                        const float cap = ceiling / gain[wbIndex(layout.cell[yy & 1][xx & 1])];
                        if (norm[idx] > cap)
                            norm[idx] = cap;
                    }
                }
            }
        }
    }

    // ---- Stage 4: bilinear demosaic. ----
    img = demosaicBilinear(norm, w, h, info.cfa);

    // ---- Stage 5: white balance gains. ----
    for (size_t i = 0; i < img.rgb.size(); i += 3) {
        img.rgb[i + 0] *= meta.wbGains[0];
        img.rgb[i + 1] *= meta.wbGains[1];
        img.rgb[i + 2] *= meta.wbGains[2];
    }

    // ---- Stage 6: color matrix (3x3 row-major). ----
    {
        const bool identity =
            meta.colorMatrix[0] == 1 && meta.colorMatrix[4] == 1 && meta.colorMatrix[8] == 1
            && meta.colorMatrix[1] == 0 && meta.colorMatrix[2] == 0
            && meta.colorMatrix[3] == 0 && meta.colorMatrix[5] == 0
            && meta.colorMatrix[6] == 0 && meta.colorMatrix[7] == 0;
        if (!identity) {
            const float* m = meta.colorMatrix;
            for (size_t i = 0; i < img.rgb.size(); i += 3) {
                const float r = img.rgb[i + 0];
                const float g = img.rgb[i + 1];
                const float b = img.rgb[i + 2];
                img.rgb[i + 0] = m[0] * r + m[1] * g + m[2] * b;
                img.rgb[i + 1] = m[3] * r + m[4] * g + m[5] * b;
                img.rgb[i + 2] = m[6] * r + m[7] * g + m[8] * b;
            }
        }
    }

    // ---- Stage 7: exposure as 2^ev. ----
    if (grade.exposureEv != 0.0f) {
        const float scale = std::pow(2.0f, grade.exposureEv);
        for (auto& v : img.rgb)
            v *= scale;
    }

    return img;
}

} // namespace rawedit
