// OpenDRT v1.1.0 — C++ reference (F1-4). docs/COLOR_MODEL_V2_PLAN.md §4.
//
// A line-by-line port of Klipp's Kotlin port (engine/OpenDrt.kt), which in
// turn is a port of Jed Smith's OpenDRT.dctl v1.1.0
// (https://github.com/jedypod/open-display-transform). OpenDRT is
// GPL-3.0-or-later; see NOTICE (owner decision K6: licensing is out of
// scope, the provenance is recorded). The look/tonescale presets, matrices
// and constants are carried over unchanged; only the container differs.
//
// Input contract: DaVinci Wide Gamut LINEAR (the NODES stage's
// NodesOutput::DwgLinear). The DWG -> XYZ matrix used inside is OpenDRT's
// own constant (OpenDRT.dctl), not Color.cpp's; test_color_model asserts
// the two agree.
//
// Output: display-referred code values in 0..1 (the sRGB-2.2 / Rec.1886
// encoding, or the HLG 1000 nit signal as float). 10-bit quantisation and
// HLG signalling are E1d/E2's, not this file's. PQ is not ported (K7).
//
// Float arithmetic, mirroring the Kotlin/GLSL operation order so the GPU
// pass (F1-5) can be held to the same numbers.

#pragma once

#include <atomic>
#include <cstddef>

#include <memory>
#include <vector>

namespace rawedit {

struct ImageF; // Develop.h

enum class OpenDrtDisplay {
    SdrSrgb22,  // phone/web: sRGB display, 2.2, 100 nit peak (Klipp display 0)
    SdrRec1886, // TV: Rec.1886, 2.4, 100 nit peak (Klipp display 1)
    Hlg1000,    // Rec.2100 HLG / Rec.2020 (P3 limited), 1000 nit peak
};

struct OpenDrtSpec {
    int look = 0;      // 0 Standard, 1 Arriba, 2 Sylvan, 3 Colorful, 4 Aery,
                       // 5 Dystopic, 6 Umbra
    int tonescale = 0; // 0 = from the look; 1..13 = Klipp's TONESCALE list
                       // (Low, Medium, High, 6 look names, ACES 1.x, ACES 2.0,
                       // Marvelous Tonescape, DaGrinchi ToneGroan)
    int cwp = 0;       // creative white point: 0 = look's, 1..6 = D93, D75,
                       // D65, D60, D55, D50
    float cwpLimit = 0.25f; // 0..1, only with cwp != 0
    float grey = 10.0f;     // mid grey luminance in nits (3..25)
    OpenDrtDisplay display = OpenDrtDisplay::SdrSrgb22;
};

// A prepared transform: presets resolved and the tonescale constants
// computed once. Immutable; apply* are const and thread-safe.
class OpenDrt {
public:
    explicit OpenDrt(const OpenDrtSpec& spec);
    ~OpenDrt();
    OpenDrt(const OpenDrt&) = delete;
    OpenDrt& operator=(const OpenDrt&) = delete;

    // CIE XYZ (D65) -> display code values, in place. This is the point the
    // reference vectors enter at (any input encoding reduces to XYZ).
    void applyXyz(float rgb[3]) const;
    // DaVinci Wide Gamut linear -> display code values, in place.
    void applyDwgLinear(float rgb[3]) const;

private:
    struct Impl;
    Impl* impl_;
};

// ---- The baked route (owner decision 2026-11-25) -------------------------
// The OpenDRT is baked into ONE 3D table whose input axes are DaVinci Intermediate CODE values (0..1 spans
// -0.0075..100 linear in DWG, so highlights and shadows both fit), output is display code values (0..1).
// The GPU applies the table through the same by-hand trilinear as every other LUT stage and the CPU
// reference applies the SAME table with the same arithmetic, so the two agree to the usual 1 LSB; the
// table itself differs from the direct transform by its lattice error (measured in test_opendrt).
constexpr int kOpenDrtLutSize = 65;

struct OpenDrtLut {
    int n = 0;
    std::vector<float> rgb; // n^3 * 3, red fastest (the .cube row order Lut uses)
};

// Bakes [spec] (NaN -> 0, clamped to 0..1 like Klipp's bake). Deterministic; ~0.3 s for n = 65.
// [cancel] (optional, polled per row): when it turns true the bake stops early and returns an EMPTY table
// (n = 0); a bake that was not cancelled is bit-identical to the one without the pointer.
OpenDrtLut bakeOpenDrtLut(const OpenDrtSpec& spec, int n = kOpenDrtLutSize,
                          const std::atomic<bool>* cancel = nullptr);

// Cached bake (last 4 specs, thread-safe): the editor drags knobs, the table is rebuilt only for a new spec.
std::shared_ptr<const OpenDrtLut> bakedOpenDrtLut(const OpenDrtSpec& spec);
// Cache lookup only (refreshes recency, never bakes): null on a miss.
std::shared_ptr<const OpenDrtLut> peekBakedOpenDrtLut(const OpenDrtSpec& spec);
// The cached bake with a cancel flag: null when cancelled (a cancelled bake is never cached).
std::shared_ptr<const OpenDrtLut> bakedOpenDrtLutCancellable(const OpenDrtSpec& spec,
                                                             const std::atomic<bool>& cancel);

// Whole image, DWG linear in, display code values out, through [lut] (DI-encode, trilinear).
void applyOpenDrtBaked(ImageF& img, const OpenDrtLut& lut);

// Whole image, DWG linear in, display code values out (NaN -> 0 as Klipp's
// bake does). Builds the transform once.
void applyOpenDrt(ImageF& img, const OpenDrtSpec& spec);

} // namespace rawedit
