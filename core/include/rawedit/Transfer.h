// rawedit transfer curves — Mi-Log decode and display encode.
//
// Mi-Log is Xiaomi's log curve carried by encoded (Mi-Log) clips. The
// constants below are copied verbatim from the vendored Xiaomi DCTL
// third_party/xiaomi-milog/Mi-Log_to_ACES_AP0.dctl (mlog_to_lin function);
// the same constants appear in the Mi-Log whitepaper PDF. Per that DCTL,
// Mi-Log samples are encoded in the Rec.2020 primaries — the DCTL converts
// Rec.2020 -> XYZ -> D60 CAT02 -> ACES AP0 after the curve. The full gamut
// conversion lands with the ACES pass (sprint item 5); this header only
// covers the curve and the Rec.709 display OETF.

#pragma once

#include <cstdint>
#include <vector>

#include <rawedit/Develop.h>

namespace rawedit {

// Mi-Log encoded value -> scene linear exposure.
// Piecewise (constants from the DCTL above):
//   x >= P_t : 2^((x - g)/a) - b          (log segment)
//   0 <= x < P_t : sqrt(x/c) + R_0        (linear toe)
//   x < 0 : R_0
float miLogToLinear(float x);

// Scene linear -> Rec.709 display code (standard Rec.709 OETF).
// lin < 0.018 : 4.5*lin, else 1.099 * lin^0.45 - 0.099. Output is not
// clamped; callers clamp to [0,1] for display.
float linearToRec709(float lin);

// One decoded frame of an encoded (Mi-Log) clip: planar or semi-planar YUV.
// Data is limited-range (SMPTE) 8-bit unless yuv10 indicates 10-bit; the
// develop stage expands limited -> full before conversion.
struct YuvFrame {
    uint32_t width = 0;   // luma width
    uint32_t height = 0;  // luma height
    bool semiPlanar = true; // true: Y + interleaved UV; false: Y, U, V planes
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;  // semiPlanar: interleaved UV in u, v unused
    int stride = 0;        // bytes per luma row (0 = width)
    int uvStride = 0;      // bytes per chroma row (0 = width/2)
};

// Develops one decoded Mi-Log frame: limited->full YUV (BT.601/709 chosen
// by matrixFromIsbt709; encoded clips carry BT.709 tags, BT.601 kept for
// legacy clips), YUV->RGB, then WB/temp/tint + exposure grade, and after the
// grade the same denoise stages, detail stages, optional tonemapping and
// optional LUT the MCRAW path runs (Grade.chromaDenoise radius 2 px and
// Grade.noiseReduction 3x3 bilateral FIRST — the codec's noise is in the
// decoded pixels and the detail stages would amplify it; then
// Grade.sharpness radius 1.5 px, Grade.detail radius 0.75 px —
// luminance-only unsharp masks — then Grade.tonemap ACES/AgX, then the
// COL-07 .cube LUT; see Develop.h and rawedit/Lut.h for the records).
// All genuinely apply to an encoded clip: its decoded pixels are
// ordinary linear RGB. Neutral grades skip every new stage (exact
// passthrough, the old look). Mi-Log clips carry Rec.2020 primaries (see
// the DCTL); the gamut conversion to the working space lands with the ACES
// pass, so today the RGB is treated as the working space directly.
//   lut: optional stage-22 LUT (COL-07). nullptr = no LUT selected — the
//   stage is skipped entirely, so every existing call site stays
//   bit-identical to the pre-LUT pipeline.
ImageF developEncoded(const YuvFrame& frame, const Grade& grade, bool isBt709, const Lut* lut = nullptr);

} // namespace rawedit
