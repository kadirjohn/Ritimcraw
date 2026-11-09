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
// legacy clips), YUV->RGB, then WB/temp/tint + exposure grade in the same
// engine as the MCRAW path. Mi-Log clips carry Rec.2020 primaries (see the
// DCTL); the gamut conversion to the working space lands with the ACES
// pass, so today the RGB is treated as the working space directly.
ImageF developEncoded(const YuvFrame& frame, const Grade& grade, bool isBt709);

} // namespace rawedit
