// EncodedYuvPass.h - E0b-2 (docs/ENCODED_GPU_DECODE_PLAN.md): the GPU twin of the FIRST stage of
// rawedit::developEncoded, headless. One fragment pass reads the decoded Y plane and the interleaved
// CbCr plane as INTEGER textures (the raw code values, no driver conversion), then does exactly what
// core/src/Transfer.cpp does per pixel: YCbCr -> RGB with the frame's own matrix and range, the
// transfer decode (Mi-Log / Rec.709 / sRGB / linear), the BT.2020 -> Rec.709 primaries matrix in
// linear light, and the WB gains x exposure. EncodedColorSpec's ordinals are the only source of the
// colour decisions (no second rule here); the later stages (denoise, detail, tonemap, LUT) are the
// existing chain's and are not part of this pass. The pass writes linear RGB to an RGBA32F target.
//
// This file is the proof that the shader math equals the CPU oracle (instrumentation parity test
// EncodedYuvPassParityTest); it is not wired to the export or the preview yet (E0b-3/E0b-4).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace gles {

struct EncodedYuvPassParams {
    int width = 0;
    int height = 0;
    int stride = 0;     // luma bytes per row (0 = width * bytesPerSample)
    int uvStride = 0;   // chroma bytes per row (0 = ceil(width/2) * 2 * bytesPerSample)
    int bitDepth = 8;   // 8, or 10 = 16-bit little-endian words, value in the TOP ten bits (P010)
    bool fullRange = false;
    int matrix = 1;     // EncodedColorSpec.nativeMatrix: 0 BT601, 1 BT709, 2 BT2020
    int transfer = 0;   // EncodedColorSpec.nativeTransfer: 0 MiLog, 1 Rec709, 2 Srgb, 3 Linear
    int primaries = 0;  // EncodedColorSpec.nativePrimaries: 0 BT709, 1 BT2020
    float exposureEv = 0.0f;
    float tempK = 5600.0f;
    float tint = 0.0f;
    // E0b-4a "reduce first, then process": 1 = full size. N > 1 makes the output ceil(width/N) x
    // ceil(height/N); each output pixel's Y is the integer-rounded mean of its N x N luma block and
    // each 2x2 pixel group's CbCr the rounded mean of the N x N chroma block over the same area
    // (blocks cut by the right/bottom edge average the samples that exist). The reduced planes are
    // therefore a legal 4:2:0 frame of the smaller size, which is the CPU oracle's input shape.
    int reduce = 1;
};

// Opens a throwaway EGL pbuffer context, runs the pass over the semi-planar planes and reads the linear
// RGB back (outW*outH*3 floats, row 0 = the top row; outW/outH per [EncodedYuvPassParams::reduce]).
// Returns false with a reason in *detail.
bool runEncodedYuvPassHeadless(const uint8_t* yPlane, size_t yLen,
                               const uint8_t* cPlane, size_t cLen,
                               const EncodedYuvPassParams& params,
                               std::vector<float>* outLinearRgb, std::string* detail);

// Timing bench (device): uploads the planes and runs the pass [frames] times into a reduced
// target without reading anything back, glFinish after each frame, and reports the mean
// milliseconds of the upload and of the draw. What the preview would pay per frame on the GPU side.
bool benchEncodedYuvPassHeadless(const uint8_t* yPlane, size_t yLen,
                                 const uint8_t* cPlane, size_t cLen,
                                 const EncodedYuvPassParams& params, int frames,
                                 double* uploadMs, double* drawMs, std::string* detail);

}  // namespace gles
