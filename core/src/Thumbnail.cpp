#include <rawedit/Thumbnail.h>

#include <vector>

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

ImageF developThumbnail(const uint16_t* cfa, const FrameInfo& info,
                        const RawMeta& meta, const Grade& grade, int strideEvery,
                        const Lut* lut) {
    ImageF img;

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    if (cfa == nullptr || w < 2 || h < 2 || strideEvery < 1)
        return img;

    const uint32_t outW = thumbnailWidth(w, strideEvery);
    const uint32_t outH = thumbnailHeight(h, strideEvery);
    if (outW == 0 || outH == 0)
        return img;

    const uint32_t n = static_cast<uint32_t>(strideEvery);

    // Cell-aligned decimation: output cell (ocx, ocy) copies source cell
    // (ocx*n, ocy*n) verbatim. A 2x2-blocked copy of an RGGB (or any
    // repeating 2x2) source is the same pattern, so the reduced mosaic is
    // developed with the source's CfaPattern unchanged.
    std::vector<uint16_t> small(static_cast<size_t>(outW) * outH);
    for (uint32_t ocy = 0; ocy < outH / 2; ++ocy) {
        const uint32_t sy0 = ocy * n; // source cell row (even)
        for (uint32_t ocx = 0; ocx < outW / 2; ++ocx) {
            const uint32_t sx0 = ocx * n; // source cell column (even)
            for (int dy = 0; dy < 2; ++dy) {
                const uint32_t sy = 2 * sy0 + static_cast<uint32_t>(dy);
                for (int dx = 0; dx < 2; ++dx) {
                    const uint32_t sx = 2 * sx0 + static_cast<uint32_t>(dx);
                    small[(static_cast<size_t>(2 * ocy + dy) * outW)
                          + (2 * ocx + static_cast<uint32_t>(dx))] =
                        cfa[static_cast<size_t>(sy) * w + sx];
                }
            }
        }
    }

    // Same pipeline, same order, no duplication: the reduced mosaic goes
    // through the full developCpu reference path.
    FrameInfo smallInfo = info;
    smallInfo.width = outW;
    smallInfo.height = outH;
    img = developCpu(small.data(), smallInfo, meta, grade, lut);
    return img;
}

} // namespace rawedit
