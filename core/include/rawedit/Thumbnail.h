// rawedit::Thumbnail — fast preview develop via CFA decimation.
//
// A full developCpu pass on a 3840x2160 frame is far too slow for a live
// preview strip, so the thumbnail path first decimates the CFA and then
// runs the SAME pipeline (developCpu, unchanged stages and order) on the
// reduced mosaic. No pipeline logic is duplicated: developThumbnail builds
// a smaller CFA and hands it to developCpu.
//
// Reduction math (documented exactly; strideEvery = N >= 1):
//   cellCols = width / 2, cellRows = height / 2        (floor; the last
//     row/column of an odd-sized frame is never sampled)
//   sampledCols = ceil(cellCols / N), sampledRows = ceil(cellRows / N)
//   outW = 2 * sampledCols, outH = 2 * sampledRows
//   Output cell (ocx, ocy) is the BINNED mean of the N x N source cells
//   (ocx*N .. ocx*N+N-1, ocy*N .. ocy*N+N-1), site by site:
//     out(2*ocx+dx, 2*ocy+dy) = round(mean over i,j in [0,N) of
//                                     cfa(2*(ocx*N+i)+dx, 2*(ocy*N+j)+dy))
//   dx,dy in {0,1}: every output site averages the SAME-COLOUR sites of its
//   block, so colour is never mixed and the 2x2 pattern is kept. A block
//   cut by the right/bottom edge averages the cells that exist.
//   (P5, 2026-11-25: this replaced a verbatim copy of ONE cell per block,
//   which threw away (N*N-1)/(N*N) of the samples with no filter in front
//   of it: at 4K -> 1280 (N=3) 8/9 of the samples were dropped, so edges
//   crawled and sensor noise stayed at full amplitude. Averaging cuts the
//   noise by ~N and acts as a box low-pass. Export is stride 1 and untouched.)
//   All four sites of an output cell are centred on the SAME block centre,
//   so the reduced image has no colour phase offset between R, G and B.
//
// Because binning is cell-aligned, the reduced mosaic keeps the source's
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
#include <vector>

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
// binding: optional managed-LUT interpretation (owner request
// 2026-11-13, rawedit/Color.h), forwarded to the inner developCpu with
// the LUT so the preview runs the SAME managed stage-23/24 sequence the
// export runs — the acceptance check compares them on-device. nullptr =
// the legacy sequence, bit-identical; appended AFTER lut with a nullptr
// default so no existing call site changes.
// rawLevels: the D1 per-clip RAW Levels override (Develop.h), forwarded
// to the inner developCpu so the preview resolves levels the SAME way
// the export does. Empty default = the pre-D1 resolution, bit-identical;
// appended LAST so no existing call site changes.
ImageF developThumbnail(const uint16_t* cfa, const FrameInfo& info,
                        const RawMeta& meta, const Grade& grade, int strideEvery,
                        const Lut* lut = nullptr,
                        const CompiledLutBinding* binding = nullptr,
                        const RawLevelsOverride& rawLevels = RawLevelsOverride());

// The reduction itself, shared by developThumbnail and the GPU preview
// path (one implementation, no copy to drift): bins the full CFA [cfa]
// (width*height samples) by N into [out] (outW*outH samples, dims per the
// header math). N = 1 returns the source samples verbatim (the export
// guarantee). Returns false and leaves [out] empty on invalid input
// (cfa == nullptr, width/height < 2, strideEvery < 1).
bool reduceCfaBinned(const uint16_t* cfa, uint32_t width, uint32_t height,
                     int strideEvery, std::vector<uint16_t>& out);

// Output dimensions for the given frame size and stride, per the header
// math: (2*ceil(width/2/N)) x (2*ceil(height/2/N)). Returns 0x0 for an
// invalid stride or a frame smaller than one 2x2 cell. Exposed so callers
// can size UI surfaces without running the pipeline.
uint32_t thumbnailWidth(uint32_t width, int strideEvery);
uint32_t thumbnailHeight(uint32_t height, int strideEvery);

// D3 (adaptive Highlights knee): the clip's luma P85 at UNIT gain, measured ONCE per clip.
// `frames` evenly spaced frames (first..last) are decoded, developed with a NEUTRAL grade
// (stages 1-13 only, tonemap off, default RAW levels) on a CFA binned so the long edge is
// <= maxDim, every pixel's Rec.709 luma pooled, and the kHighlightKneePercentile quantile
// of the pool returned. This is the value stored in Grade::highlightKneeRef.
// Returns the ref (> 0), or 0 when nothing could be measured (no frames / decode failure /
// an all-black pool) -- the caller then keeps the legacy fixed knee.
float measureHighlightKneeRef(Source& src, int frames = 8, int maxDim = 480);

} // namespace rawedit
