// rawedit::Thumbnail — fast preview develop via CFA decimation.
//
// A full developCpu pass on a 3840x2160 frame is far too slow for a live
// preview strip, so the thumbnail path first decimates the CFA and then
// runs the SAME pipeline (developCpu, unchanged stages and order) on the
// reduced mosaic. No pipeline logic is duplicated: developThumbnail builds
// a smaller CFA and hands it to developCpu.
//
// Decimation math (documented exactly; strideEvery = N >= 1):
//   cellCols = width / 2, cellRows = height / 2        (floor; the last
//     row/column of an odd-sized frame is never sampled)
//   sampledCols = ceil(cellCols / N), sampledRows = ceil(cellRows / N)
//   outW = 2 * sampledCols, outH = 2 * sampledRows
//   Output cell (ocx, ocy) copies source cell (ocx*N, ocy*N) verbatim:
//     out(2*ocx+dx, 2*ocy+dy) = cfa(2*ocx*N+dx, 2*ocy*N+dy), dx,dy in {0,1}
//
// Because sampling is cell-aligned, the reduced mosaic keeps the source's
// 2x2 repeating pattern (an RGGB source stays RGGB), so per-channel black
// levels and the shading-map plane order stay valid. Examples:
//   3840x2160, N=8 -> 480x270      512x512, N=8 -> 64x64
//   3840x2160, N=1 -> 3840x2160 (identity with developCpu)
//
// Accuracy notes: the reduced mosaic's sites are N cells apart, so the
// per-cell highlight reconstruction and the bilinear demosaic see a
// coarser neighborhood, and the lens shading gain is sampled at the
// reduced image's normalized coordinates (the sensor position of output
// pixel x is x*N, so the vignette lands slightly compressed at the
// borders). Flat and smooth regions match developCpu closely; only
// fine per-pixel detail differs, which a preview does not need.
//
// Invalid input (cfa == nullptr, width/height < 2, strideEvery < 1)
// returns an empty ImageF (w = h = 0) — never throws.

#pragma once

#include <cstdint>

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

namespace rawedit {

// Develops a decimated preview of one CFA frame. cfa is the FULL frame
// (width*height samples per info); strideEvery is the decimation factor N
// from the header math above. The returned ImageF is (2*ceil(width/2/N))
// x (2*ceil(height/2/N)) in the linear working space, or empty on invalid
// input.
//
// lut: optional stage-20 .cube 3D LUT (COL-07), forwarded to the inner
// developCpu so the preview and the export run the SAME pipeline — the
// owner's acceptance check compares them on-device. nullptr = no LUT
// (the pre-LUT look, bit-identical); defaulted so every existing call
// site is unchanged.
ImageF developThumbnail(const uint16_t* cfa, const FrameInfo& info,
                        const RawMeta& meta, const Grade& grade, int strideEvery,
                        const Lut* lut = nullptr);

// Output dimensions for the given frame size and stride, per the header
// math: (2*ceil(width/2/N)) x (2*ceil(height/2/N)). Returns 0x0 for an
// invalid stride or a frame smaller than one 2x2 cell. Exposed so callers
// can size UI surfaces without running the pipeline.
uint32_t thumbnailWidth(uint32_t width, int strideEvery);
uint32_t thumbnailHeight(uint32_t height, int strideEvery);

} // namespace rawedit
