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

// How an encoded clip's YCbCr samples are to be read (E0). The container
// (MediaFormat KEY_COLOR_STANDARD / RANGE / TRANSFER + the track profile)
// declares these; the Kotlin side maps them to this vocabulary ONCE
// (EncodedColorSpec) and preview and export hand the same values down.
enum class YuvMatrix : int {
    FromFlag = -1,  // legacy: developEncoded's isBt709 argument decides (601/709)
    BT601 = 0,
    BT709 = 1,
    BT2020 = 2,     // BT.2020 non-constant-luminance (Mi-Log: matrix 9)
};

// The transfer curve the decoded RGB carries. MiLog and Linear are handled
// here; the SDR curves map onto Color.h decodeTransfer.
enum class EncodedTransfer : int {
    MiLog = 0,    // Xiaomi Mi-Log (the app's encoded default; see miLogToLinear)
    Rec709 = 1,   // BT.709 / BT.601 / BT.2020 SDR OETF (Color.h Rec709OETF)
    Srgb = 2,
    Linear = 3,
};

enum class EncodedPrimaries : int {
    BT709 = 0,    // already the working space: no gamut step
    BT2020 = 1,   // converted to the Rec.709 working space in linear light
};

// One decoded frame of an encoded (Mi-Log) clip: planar or semi-planar YUV.
//
// Sample layout: bitDepth == 8 -> one byte per sample. bitDepth > 8 -> one
// 16-bit LITTLE-ENDIAN word per sample with the value in the HIGH bits (MSB
// aligned: exactly MediaCodec's P010), so a 10-bit code is word >> 6.
// Strides are always BYTES per row. Range: limited (SMPTE 16-235 luma /
// 16-240 chroma, scaled to the depth) unless fullRange; the develop expands
// either to full before the matrix.
//
// The defaults (8-bit, limited, matrix from the legacy isBt709 flag,
// Mi-Log, BT.709 primaries) are bit-identical to the pre-E0 develop, so
// every older caller is untouched.
struct YuvFrame {
    uint32_t width = 0;   // luma width
    uint32_t height = 0;  // luma height
    bool semiPlanar = true; // true: Y + interleaved UV; false: Y, U, V planes
    std::vector<uint8_t> y;
    std::vector<uint8_t> u;
    std::vector<uint8_t> v;  // semiPlanar: interleaved UV in u, v unused
    int stride = 0;        // bytes per luma row (0 = width * bytesPerSample)
    int uvStride = 0;      // bytes per chroma row (0 = packed: chroma width * (2 if semiPlanar) * bytesPerSample)
    int bitDepth = 8;      // 8, or 10/12/16 as 16-bit MSB-aligned words
    bool fullRange = false;
    YuvMatrix matrix = YuvMatrix::FromFlag;
    EncodedTransfer transfer = EncodedTransfer::MiLog;
    EncodedPrimaries primaries = EncodedPrimaries::BT709;
};

// Develops one decoded Mi-Log frame: range expansion (limited or full, 8 or
// 10+ bit, per the frame's own fields) -> YUV->RGB with the frame's matrix
// (BT.601/709/2020; FromFlag defers to isBt709) -> the frame's transfer
// decode (Mi-Log by default) -> BT.2020 primaries to the Rec.709 working
// space (E0), then WB/temp/tint + exposure grade, and after the
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
// the DCTL): a frame that declares EncodedPrimaries::BT2020 is converted to
// the Rec.709 working space after the transfer decode (E0); the default
// BT709 declaration keeps the pre-E0 behavior (the RGB IS the working space).
//   lut: optional stage-22 LUT (COL-07). nullptr = no LUT selected — the
//   stage is skipped entirely, so every existing call site stays
//   bit-identical to the pre-LUT pipeline.
//   binding: optional managed-LUT interpretation (owner request
//   2026-11-13, rawedit/Color.h) compiled ahead of time. nullptr = the
//   legacy tonemap+LUT sequence, byte-identical. When present, the pair
//   routes through applyManagedLutStage exactly like developCpu's stages
//   23/24 (same roles, same ordering, same strength semantics), so the
//   two pipelines stay in lockstep. Appended AFTER lut with a nullptr
//   default so no existing call site changes.
ImageF developEncoded(const YuvFrame& frame, const Grade& grade, bool isBt709, const Lut* lut = nullptr, const CompiledLutBinding* binding = nullptr);

} // namespace rawedit
