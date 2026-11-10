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
//   5. WB gains (division by asShotNeutral)
//   6. color transform to linear Rec.709 (DNG ForwardMatrix/ColorMatrix)
//   7. black point (toe remap; white stays pinned)
//   8. white point (shoulder remap; black stays pinned)
//   9. shadows (gain weighted toward black)
//  10. midtones (gain weighted around midgray)
//  11. contrast (linear slope around the midgray pivot)
//  12. exposure as 2^ev
//  13. highlights (monotone soft shoulder above the knee)
//  14. temperature (warm/cool diagonal gain)
//  15. tint (green/magenta gain)
//  16. saturation (chroma scaling around luma; neutrals stay neutral)
//
// Stages 7-11 (the tone controls) run after the camera->Rec.709 transform
// and before exposure: their weights and pivots are defined in scene-referred
// linear terms (midgray = 0.18), so they classify tones of the scene, not of
// the post-exposure image — an exposure change then shifts the whole graded
// range uniformly instead of moving the shadows/midtones anchors. Endpoints
// remap first (7-8), then the regional gains (9-10), then the global slope
// (11). Saturation runs LAST (16), after all per-channel gains, so it scales
// the final chroma; it is the only stage that mixes channels.
//
// Every grade stage is guarded by `field != neutral`, so a grade with all
// fields neutral executes none of them and is bit-identical to the
// pre-grade pipeline (locked by test_develop.cpp golden values).
//
// All math is float. ImageF is the common linear working space.

#pragma once

#include <cstdint>
#include <vector>

#include <rawedit/Source.h>

namespace rawedit {

// Grade parameters. All drive developCpu (the shared RAW color path):
//   exposureEv  : linear gain 2^ev (stage 12)
//   highlights  : [-100,100] soft shoulder on near-clipped highlights (13)
//   tempK       : [2000,50000] warm/cool diagonal gain around 5600 K (14)
//   tint        : [-100,100] green/magenta push (15)
//   saturation  : [-100,100] chroma scaling around luma, 0 neutral (16)
//   blackPoint  : [-100,100] tonal endpoint remap; negative lifts the toe
//                 (fades blacks), positive crushes it. 0 neutral (7)
//   whitePoint  : [-100,100] tonal endpoint remap; negative pulls the
//                 shoulder down, positive extends it toward clip. 0 neutral (8)
//   shadows     : [-100,100] gain weighted toward black. 0 neutral (9)
//   midtones    : [-100,100] gain weighted around midgray (0.18). 0 neutral (10)
//   contrast    : [-100,100] linear slope around the midgray pivot. 0 neutral (11)
// The tone controls (7-11) are defined on scene-referred linear values before
// exposure; see the stage-order comment above for why. All neutral defaults
// mean a default-constructed Grade is a no-op against the ungraded pipeline.
struct Grade {
    float exposureEv = 0.0f;
    float highlights  = 0.0f;
    float tempK = 5600.0f;
    float tint  = 0.0f;
    float saturation = 0.0f;
    float blackPoint = 0.0f;
    float whitePoint = 0.0f;
    float shadows = 0.0f;
    float midtones = 0.0f;
    float contrast = 0.0f;
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
