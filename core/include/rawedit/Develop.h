// rawedit CPU develop reference — spec section 12, render order item 1
// (MCRAW/DNG path). This CPU pipeline is the reference that later GPU work
// must match.
//
// Order (spec section 12, render order item 1):
//   1. black/white normalization per CFA channel
//   2. lens shading gain (bilinear on the shading map; identity when empty)
//   3. highlight reconstruction: clip all channels to the lowest clipped
//      channel's level so clipped areas go neutral
//   4. bilinear demosaic (all four CFA patterns)
//   5. WB gains
//   6. color matrix (3x3 row-major)
//   7. exposure as 2^ev
//
// All math is float. ImageF is the common linear working space.

#pragma once

#include <cstdint>
#include <vector>

#include <rawedit/Source.h>

namespace rawedit {

// Grade parameters (subset used by the reference develop).
struct Grade {
    float exposureEv = 0.0f;
    float highlights  = 0.0f; // reserved; the reference pipeline currently
                             // applies highlight reconstruction only
    float tempK = 5600.0f;   // reserved for the graded path
    float tint  = 0.0f;      // reserved for the graded path
};

// Linear RGB float image, row-major, 3 floats per pixel (r, g, b).
struct ImageF {
    uint32_t w = 0;
    uint32_t h = 0;
    std::vector<float> rgb;
};

// Develops one CFA frame into linear RGB.
//   cfa: width*height uint16 samples, row-major, 2x2 repeating pattern
//        given by info.cfa.
//   meta: per-clip RAW metadata (levels, color matrix, WB, shading map).
// Returns a (width x height) ImageF in the linear working space.
ImageF developCpu(const uint16_t* cfa, const FrameInfo& info, const RawMeta& meta, const Grade& grade);

// Bilinearly samples the lens shading gain map at normalized sensor
// coordinates (u, v in [0,1]). plane selects the CFA channel plane (0..3,
// 2x2 order). Returns 1.0f when the map is empty.
float sampleShading(const RawMeta& meta, float u, float v, int plane);

// Bilinear demosaic of a CFA buffer into linear RGB (no levels, no color).
// Exposed for tests; developCpu calls it after normalization stages.
ImageF demosaicBilinear(const std::vector<float>& cfa, uint32_t w, uint32_t h, CfaPattern pattern);

} // namespace rawedit
