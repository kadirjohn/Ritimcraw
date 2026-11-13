// rawedit color management — gamut and transfer-function definitions for
// managed LUT application (owner request 2026-11-13, LUT color management).
//
// The legacy LUT path (COL-07, Lut.h) DEFINED its contract as linear
// Rec.709 in / linear Rec.709 out and applied every table against that
// definition, whatever space the cube was actually authored for. This
// header defines the managed model that replaces that assumption: a LUT
// interpretation declares the color encoding its table expects, and the
// pipeline converts the working space to that encoding before sampling
// and back after.
//
// Gamuts: Rec.709 and sRGB share the same primaries (a single identity
// here — Rec709); Rec.2020; DaVinci Wide Gamut. Gamut conversions happen
// in LINEAR light only, as 3x3 matrices in row-major order (m[0..2] is
// the first row), applied per pixel as
//   r' = m[0]*r + m[1]*g + m[2]*b   (and cyclically for g', b').
//
// Transfers: Linear (identity), sRGB, Rec.709 OETF (the EXISTING
// rawedit::linearToRec709 curve — never renamed, never altered; this
// model references it, it does not replace it), Gamma 2.4 (a separate
// named curve, not the 709 OETF), and DaVinci Intermediate.
//
// Provenance of the ported math (pinned local sources under
// STEPS/owner's requests/2026-11-12-ritiMCRAW-export-gpu-offline-agent/
// sources/, colour-science pin 7ca0c87, BSD-3-Clause, Copyright 2013
// Colour Developers):
//   - DaVinci Intermediate constants and both branch forms:
//     colour/models/rgb/transfer_functions/davinci_intermediate.py
//   - sRGB encode/decode branch forms: .../transfer_functions/srgb.py
//   - sRGB/Rec.709 primaries and the 4-decimal RGB<->XYZ matrix pair:
//     .../datasets/srgb.py (the BT.709 dataset derives its matrix at
//     runtime from the same primaries, so the sRGB dataset pair is the
//     pinned literal for both)
//   - DaVinci Wide Gamut primaries and the corrected RGB<->XYZ matrix
//     pair: .../datasets/davinci_wide_gamut.py
//   - Rec.2020 primaries: .../datasets/itur_bt_2020.py; its matrix is
//     derived at runtime there via normalised_primary_matrix(), so this
//     port derives it the same way (see xyzGamutToGamut below)
//   - D65 chromaticity (0.3127, 0.3290): ACES CTLs pin 79688e5 (the
//     colour-science subset carries no illuminant numerics)
//   - The signed power (spow) used by colour-science's sRGB curves is
//     implemented locally (colour/algebra is not in the pinned subset)
//   - The Rec.709 OETF decode is the mathematical inverse of the app's
//     existing linearToRec709 (Transfer.h); colour-science's
//     oetf_inverse_BT709 delegates to oetf_inverse_BT601, which is not
//     in the pinned subset — the inverse is derived, not ported.
// The math is ported to C++; no Python runs on Android.
//
// Negative and HDR policy, per transfer:
//   Linear        — values pass through unchanged (negatives and >1
//                   are meaningful scene-referred values)
//   Rec.709 OETF  — unclamped, like the existing linearToRec709; the
//                   decode's linear branch (V <= 4.5*0.018) covers
//                   negatives exactly
//   sRGB          — encode: L <= 0.0031308 uses the linear branch
//                   (negatives included); decode: V compared against
//                   the ENCODED breakpoint sRGB(0.0031308) =
//                   0.040449936, then V/12.92 or spow((V+0.055)/1.055,
//                   2.4) — signed power keeps negatives reversible
//   Gamma 2.4     — spow(L, 1/2.4) / spow(V, 2.4) with signed power
//   DaVinci      — L <= 0.00262409 uses L*M (negatives included);
//                   decode V <= 0.02740668 uses V/M. The curve is
//                   C0-continuous at the breakpoint (both sides give
//                   0.0274067006593695 encode-side and
//                   0.002624088021941948 decode-side, in double
//                   precision — the pinned constants are rounded to
//                   8 decimals, so the branch cut differs from the
//                   true crossover by < 4e-10; encoded values between
//                   the branch cut and the true crossover decode with
//                   at most ~3.3e-11 error)
//
// The .cube table-domain clamp stays the sampler's (Lut.cpp): this model
// never adds a pipeline clamp ahead of the table.

#pragma once

#include <string>

namespace rawedit {

// ---- Gamuts (primaries define the gamut; white point is fixed D65 for
// all four — see whitePointGamut) ----
enum class GamutId {
    Rec709,     // = sRGB primaries (single identity)
    Rec2020,
    DaVinciWideGamut,
};

// ---- Transfer functions ----
enum class TransferId {
    Linear,
    Srgb,
    Rec709OETF,     // the existing linearToRec709 curve
    Gamma24,        // pure gamma 2.4, a separate named curve
    DaVinciIntermediate,
};

// ---- LUT roles: where in the develop pipeline a managed LUT applies ----
enum class LutRole {
    // The COL-07 stage-24 path, byte-identical: linear Rec.709 in, table,
    // linear Rec.709 out, before the single Rec.709 OETF. Old records
    // default here.
    LegacyLinear709,
    // A display look: applied AFTER the render (ACES/AgX) on the rendered
    // linear Rec.709 image. Encode to the LUT input encoding, sample,
    // decode back to rendered linear Rec.709. Strength mixes in the
    // rendered reference.
    DisplayLook,
    // A scene look: applied BEFORE the render on scene-linear working
    // data. Encode, sample, decode back to scene-linear. ACES/AgX then
    // applies ONCE, after. Strength mixes in the scene reference.
    SceneLook,
    // A scene-to-display conversion LUT (e.g. DWG/Intermediate -> Rec.709):
    // entered BEFORE the render; the LUT branch is ALREADY rendered
    // output — ACES/AgX never applies on top of it. Strength N in (0,100)
    // mixes the LUT branch with the baseline (rendered) branch, both
    // evaluated in rendered linear Rec.709.
    SceneToDisplay,
};

// One color encoding: a gamut plus a transfer function. This is what a
// LUT interpretation declares for its input or output.
struct ColorEncoding {
    GamutId gamut = GamutId::Rec709;
    TransferId transfer = TransferId::Linear;
};

// A LUT interpretation: input encoding, output encoding, and the role
// that decides where the pipeline applies it. Immutable once stored.
struct LutInterpretation {
    ColorEncoding input;
    ColorEncoding output;
    LutRole role = LutRole::LegacyLinear709;
};

// ---- Transfer functions ----

// Encode linear scene light to the transfer's encoded value. Unclamped;
// negative handling per the policy notes above.
float encodeTransfer(float linear, TransferId t);
// Decode an encoded value back to linear. The inverse of encodeTransfer,
// exact to float precision at the breakpoints, gray, black, allowed
// negatives, and >1 (see tests).
float decodeTransfer(float encoded, TransferId t);

// ---- Gamut matrices ----

// M_gamut->XYZ for the gamut's D65 white point, row-major 3x3.
// Rec709: the pinned 4-decimal sRGB dataset matrix, verbatim.
// Rec2020: derived from its primaries by normalised primary matrix
// (the pinned BT.2020 dataset derives the same way).
// DaVinciWideGamut: the pinned corrected matrix, verbatim.
void gamutToXyz(GamutId g, float out[9]);

// M_XYZ->gamut: the computed inverse of gamutToXyz(g). The pinned
// dataset inverse matrices (4-dp for 709, 8-dp for DWG) are cross-checks
// in the tests, not the runtime path: the computed inverse keeps
// A->B->A round trips exact (the 4-dp pinned inverse slips by ~2e-5).
void xyzToGamut(GamutId g, float out[9]);

// The gamut's white point as XYZ tristimulus with Y = 1 (D65 for all
// four gamuts; kept as data so the CAT below has a source).
void whitePointGamut(GamutId g, float out[3]);

// Bradford chromatic adaptation from srcWhite to dstWhite (XYZ, Y=1):
// M = B^-1 * D * B with D = diag(dstCone/srcCone), B the Bradford cone
// response matrix — the same construction as Develop.cpp's
// xyzD50ToRec709. CAT(w, w) is the identity.
void bradfordCatXyz(const float srcWhite[3], const float dstWhite[3],
                    float out[9]);

// M_A_to_B = inv(M_B_to_XYZ) * CAT(whiteA->whiteB) * M_A_to_XYZ,
// row-major. Applied in linear light. With A == B this is the identity.
void gamutToGamut(GamutId from, GamutId to, float out[9]);

// ---- Managed LUT application ----

struct Lut;    // Lut.h
struct ImageF; // Develop.h

// A compiled binding: the interpretation plus the matrices baked at
// compile time (no per-frame JSON, no per-frame matrix inversion).
// Copyable, immutable in use; the Lut it is applied against is passed
// separately at call time.
struct CompiledLutBinding {
    LutInterpretation interp;
    // working (linear Rec.709) -> LUT input gamut, linear
    float workingToInput[9];
    // LUT output gamut -> working (linear Rec.709), linear
    float outputToWorking[9];
    // True when both matrices are the identity and both transfers are
    // Linear — the managed path degenerates to the legacy sampler.
    bool passthroughEncodings;
};

// Compiles the binding: derives the two matrices from the
// interpretation (the table's own domain comes from the parsed Lut at
// apply time, not from the binding).
CompiledLutBinding compileLutBinding(const LutInterpretation& interp);

// Applies a managed LUT to an image in place. The image must be in the
// space the role contract names (see LutRole above and the wiring in
// Develop.cpp/Transfer.cpp): for SceneLook the caller passes
// scene-linear; for DisplayLook/SceneToDisplay the caller passes the
// image at the role's insertion point and the strength mixing happens
// there (see evaluateManagedLutPixel below for the per-pixel contract).
//
// The pipeline at the call site:
//   workingToInput gamut (linear) -> encodeTransfer -> trilinear sample
//   (the EXISTING sampler, untouched) -> decodeTransfer ->
//   outputToWorking.
//
// This is the frame-level entry for the SceneLook role; DisplayLook and
// SceneToDisplay use the per-pixel form so the two-branch strength mix
// can share the stage point.
void evaluateManagedLut(ImageF& img, const Lut& lut,
                        const CompiledLutBinding& binding, float strength);

// Per-pixel form: converts one working-space pixel to the binding's
// input encoding, samples the table, and returns the pixel converted
// back to the working space. Negative/HDR policy is the encoding's (see
// above); the table-domain clamp is the sampler's.
void evaluateManagedLutPixel(const Lut& lut, const CompiledLutBinding& binding,
                            const float rgbIn[3], float rgbOut[3]);

// A short stable signature of the interpretation (role + input/output
// gamut + transfer), for cache keys. Two interpretations with the same
// signature are interchangeable for rendering purposes.
std::string interpretationSignature(const LutInterpretation& interp);

} // namespace rawedit
