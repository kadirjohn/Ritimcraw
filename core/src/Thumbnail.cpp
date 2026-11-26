#include <rawedit/Thumbnail.h>

#include <algorithm>
#include <vector>

#include <rawedit/Parallel.h>

namespace rawedit {

uint32_t thumbnailWidth(uint32_t width, int strideEvery) {
    if (width < 2 || strideEvery < 1)
        return 0;
    const uint32_t cellCols = width / 2;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t sampled = (cellCols + n - 1) / n; // ceil
    return 2 * sampled;
}

uint32_t thumbnailHeight(uint32_t height, int strideEvery) {
    if (height < 2 || strideEvery < 1)
        return 0;
    const uint32_t cellRows = height / 2;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t sampled = (cellRows + n - 1) / n; // ceil
    return 2 * sampled;
}

bool reduceCfaBinned(const uint16_t* cfa, uint32_t width, uint32_t height,
                     int strideEvery, std::vector<uint16_t>& out) {
    out.clear();
    if (cfa == nullptr || width < 2 || height < 2 || strideEvery < 1)
        return false;
    const uint32_t outW = thumbnailWidth(width, strideEvery);
    const uint32_t outH = thumbnailHeight(height, strideEvery);
    if (outW == 0 || outH == 0)
        return false;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t cellCols = width / 2;
    const uint32_t cellRows = height / 2;
    const uint32_t sampledCols = outW / 2;
    const uint32_t sampledRows = outH / 2;

    if (n == 1) {
        // Identity (the export guarantee): the first outW x outH samples,
        // which is the whole frame except an odd last row/column.
        out.resize(static_cast<size_t>(outW) * outH);
        for (uint32_t y = 0; y < outH; ++y)
            std::copy(cfa + static_cast<size_t>(y) * width,
                      cfa + static_cast<size_t>(y) * width + outW,
                      out.begin() + static_cast<size_t>(y) * outW);
        return true;
    }

    out.assign(static_cast<size_t>(outW) * outH, 0);
    // Per output cell row: accumulate the N source cell rows x 2 sample rows
    // into four per-column sums (the four sites), then divide. Rows are
    // independent, so they run in parallel.
    parallelForRows(sampledRows, [&](size_t rowBegin, size_t rowEnd) {
        std::vector<uint32_t> sum(static_cast<size_t>(sampledCols) * 4);
        for (size_t ocy = rowBegin; ocy < rowEnd; ++ocy) {
            std::fill(sum.begin(), sum.end(), 0u);
            const uint32_t cy0 = static_cast<uint32_t>(ocy) * n;
            const uint32_t cyCount = std::min(n, cellRows - cy0);
            for (uint32_t j = 0; j < cyCount; ++j) {
                for (uint32_t dy = 0; dy < 2; ++dy) {
                    const uint16_t* row =
                        cfa + static_cast<size_t>(2 * (cy0 + j) + dy) * width;
                    for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                        const uint32_t cx0 = ocx * n;
                        const uint32_t cxCount = std::min(n, cellCols - cx0);
                        const uint16_t* p = row + 2 * static_cast<size_t>(cx0);
                        uint32_t s0 = 0, s1 = 0;
                        for (uint32_t i = 0; i < cxCount; ++i) {
                            s0 += p[2 * i];
                            s1 += p[2 * i + 1];
                        }
                        sum[static_cast<size_t>(ocx) * 4 + dy * 2 + 0] += s0;
                        sum[static_cast<size_t>(ocx) * 4 + dy * 2 + 1] += s1;
                    }
                }
            }
            for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                const uint32_t cxCount = std::min(n, cellCols - ocx * n);
                const uint32_t cnt = cxCount * cyCount; // cells that exist in this block
                for (uint32_t dy = 0; dy < 2; ++dy) {
                    for (uint32_t dx = 0; dx < 2; ++dx) {
                        const uint32_t v = (sum[static_cast<size_t>(ocx) * 4 + dy * 2 + dx] + cnt / 2) / cnt;
                        out[(static_cast<size_t>(2 * ocy + dy) * outW) + (2 * ocx + dx)] =
                            static_cast<uint16_t>(v);
                    }
                }
            }
        }
    });
    return true;
}

ImageF developThumbnail(const uint16_t* cfa, const FrameInfo& info,
                        const RawMeta& meta, const Grade& grade, int strideEvery,
                        const Lut* lut, const CompiledLutBinding* binding,
                        const RawLevelsOverride& rawLevels) {
    ImageF img;

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    if (cfa == nullptr || w < 2 || h < 2 || strideEvery < 1)
        return img;

    const uint32_t outW = thumbnailWidth(w, strideEvery);
    const uint32_t outH = thumbnailHeight(h, strideEvery);
    if (outW == 0 || outH == 0)
        return img;

    std::vector<uint16_t> small;
    if (!reduceCfaBinned(cfa, w, h, strideEvery, small))
        return img;

    // Same pipeline, same order, no duplication: the reduced mosaic goes
    // through the full developCpu reference path.
    FrameInfo smallInfo = info;
    smallInfo.width = outW;
    smallInfo.height = outH;
    img = developCpu(small.data(), smallInfo, meta, grade, lut, binding, rawLevels);
    return img;
}

} // namespace rawedit
