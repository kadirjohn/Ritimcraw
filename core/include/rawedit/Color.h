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
// here — Rec709); Rec.2020; DaVinci Wide Gamut — plus the priority-2
// camera gamuts (owner request 2026-11-13, commit (e)): ACEScg (AP1
// primaries, ACES ~D60 white), ARRI Wide Gamut 3, S-Gamut3.Cine, Canon
// Cinema Gamut, Apple Wide Gamut, and Panasonic V-Gamut. F-Gamut is NOT
// offered: the pinned subset carries no Fujifilm gamut dataset, so that
// pairing stays visibly unsupported (see the BLOCKED note in the mission
// report). Gamut conversions happen in LINEAR light only, as 3x3
// matrices in row-major order (m[0..2] is the first row), applied per
// pixel as
//   r' = m[0]*r + m[1]*g + m[2]*b   (and cyclically for g', b').
//
// Transfers: Linear (identity), sRGB, Rec.709 OETF (the EXISTING
// rawedit::linearToRec709 curve — never renamed, never altered; this
// model references it, it does not replace it), Gamma 2.4 (a separate
// named curve, not the 709 OETF), and DaVinci Intermediate — plus the
// priority-2 camera profiles (owner request 2026-11-13, commit (e)):
// ACEScct, ARRI LogC3 (SUP 3.x EI 800), Sony S-Log3, Canon C-Log3,
// Apple Log (the shared curve behind both Apple Log and Apple Log 2 —
// the gamut distinguishes the two), Panasonic V-Log, Fujifilm F-Log,
// and Cineon.
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
//   Priority-2 profiles (commit (e)), same pins:
//   - ACEScct constants and both branch forms:
//     .../transfer_functions/aces.py (CONSTANTS_ACES_CCT)
//   - ARRI LogC3 SUP 3.x EI 800 curve constants (cut, a, b, c, d, e, f)
//     and both branch forms: .../transfer_functions/arri.py
//     (DATA_ALEXA_LOG_C_CURVE_CONVERSION["SUP 3.x"]["Linear Scene
//     Exposure Factor"][800])
//   - S-Log3 encode/decode: .../transfer_functions/sony.py
//     (log_encoding_SLog3 / log_decoding_SLog3, in_reflection=True —
//     no 0.9 scaling). Cross-checked against the pinned Sony CTL.
//   - Canon C-Log3 curve: CSC.Canon.ACES_to_CLog3_CGamut.ctl
//     (lin_to_CLog3, verbatim). The CTL's main() divides linear input
//     by 0.9 before the curve — that is the CSC pipeline wrapper, not
//     part of the published curve (every other CSC CTL passes the
//     gamut-converted linear straight to its curve); this port follows
//     the curve itself, and the 0.9 scene-linear scaling is NOT baked
//     in. The decode is the mathematical inverse of the three branches
//     (the CTL pins only the encode).
//   - Apple Log curve: .../transfer_functions/apple_log_profile.py
//     (CONSTANTS_APPLE_LOG_PROFILE) — the ONE curve behind both Apple
//     Log and Apple Log 2 (the pinned CTLs share identical constants);
//     the gamut distinguishes them (Apple Log pairs with Rec.2020
//     primaries, Apple Log 2 with Apple Wide Gamut), so one TransferId
//     serves both. Decode per the pinned inverse (P_t branch point).
//   - Panasonic V-Log encode: CSC.Panasonic.ACES_to_VLog_VGamut.ctl
//     (lin_to_VLog, verbatim). The CTL pins only the encode; the decode
//     is the mathematical inverse of the two branches (the same
//     derivation precedent as the Rec.709 OETF decode).
//   - Fujifilm F-Log constants and both branch forms:
//     .../transfer_functions/fujifilm_f_log.py (CONSTANTS_FLOG)
//   - Cineon constants and both branch forms:
//     .../transfer_functions/cineon.py (black_offset =
//     10 ** ((95 - 685) / 300))
//   - ACEScg/AP1 primaries .../datasets/aces.py (AP1); ACES white
//     chromaticity (0.32168, 0.33767) from the ACES CTLs (AP0 white);
//     the AP1 matrix is npm-derived there, so this port derives it the
//     same way
//   - ARRI Wide Gamut 3 RGB->XYZ matrix:
//     .../datasets/arri.py (MATRIX_ARRI_WIDE_GAMUT_3_TO_XYZ, verbatim)
//   - S-Gamut3.Cine RGB->XYZ matrix:
//     .../datasets/sony.py (MATRIX_S_GAMUT3_CINE_TO_XYZ, verbatim)
//   - Canon Cinema Gamut primaries: .../datasets/canon_cinema_gamut.py
//     (npm-derived there, so derived here the same way)
//   - Apple Wide Gamut primaries: CSC.Apple.ACES_to_AppleLog2.ctl
//     (APPLE_WIDE_GAMUT_PRI); Apple Log's Rec.2020 pairing:
//     CSC.Apple.ACES_to_AppleLog.ctl (REC2020_PRI)
//   - Panasonic V-Gamut primaries:
//     CSC.Panasonic.ACES_to_VLog_VGamut.ctl (PANASONIC_VGAMUT_PRI)
//   - F-Gamut: NOT PORTED — the pinned subset carries no Fujifilm
//     gamut dataset; the F-Log transfer ships without its native
//     gamut pairing.
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
//   ACEScct       — L <= 0.0078125 uses A*L + B (negatives included:
//                   A*L+B stays finite and reversible); decode
//                   V > 0.155251141552511 uses 2^(V*17.52 - 9.72),
//                   else (V-B)/A. The pinned constants are rounded, so
//                   the branch cut differs from the true crossover by
//                   ~1e-13 (measured; harmless either side).
//   ARRI LogC3    — L > 0.010591 uses the log branch, else E*L + F
//                   (negatives included). The pinned EI 800 constants
//                   are rounded, so the branches are ~2.5e-7
//                   discontinuous at the cut (measured; the curve's
//                   own property, documented, not altered). Decode:
//                   V > E*cut + F uses (10^((V-D)/C) - B)/A, else
//                   (V-F)/E.
//   S-Log3        — L >= 0.01125 uses the log branch, else the linear
//                   branch (95 + L*(171.2102946929-95)/0.01125)/1023
//                   (negatives included). Decode: V >= 171.2102946929/
//                   1023 uses the log branch, else the linear inverse.
//                   The pinned constants are rounded, so the branches
//                   are ~3.9e-14 discontinuous at the cut (measured;
//                   the curve's own property, documented, not altered).
//   Canon C-Log3  — three branches: L < -0.014 uses the negative log
//                   branch (defined for L < 1/14.98325, so moderate
//                   negatives are finite and reversible), the linear
//                   branch covers [-0.014, 0.014], and L > 0.014 uses
//                   the positive log branch. The pinned constants are
//                   rounded, so the branch cuts are ~2.6e-9
//                   discontinuous (measured; the curve's own property,
//                   documented, not altered). Decode compares against
//                   the ENCODED cuts (the linear branch's values at
//                   ±0.014) and inverts each branch.
//   Apple Log     — R >= 0.01 uses G*log2(R + beta) + delta; R_0 <= R
//                   < 0.01 uses sigma*(R - R_0)^2; R < R_0 encodes 0.
//                   Decode: P >= P_t (= sigma*(R_t - R_0)^2 =
//                   0.208555315954642) uses 2^((P - delta)/gamma) -
//                   beta; 0 <= P < P_t uses sqrt(P/sigma) + R_0; P < 0
//                   decodes R_0 (the encode's floor — negatives are
//                   NOT reversible through this curve, by design: the
//                   pinned encode clips them to 0).
//   V-Log         — L < 0.01 uses 5.6*L + 0.125 (negatives included);
//                   else 0.241514*log10(L + 0.00873) + 0.598206. The
//                   pinned constants are rounded, so the branches are
//                   ~3.1e-7 discontinuous at the cut (measured; the
//                   curve's own property, documented, not altered).
//                   Decode: V < 0.181 uses (V - 0.125)/5.6, else
//                   10^((V - 0.598206)/0.241514) - 0.00873 (derived —
//                   the CTL pins only the encode).
//   F-Log         — L < 0.00089 uses E*L + F (negatives included);
//                   else C*log10(A*L + B) + D. Decode: V < cut2
//                   (0.100537775223865) uses (V - F)/E, else
//                   (10^((V - D)/C))/A - B/A, exactly the pinned
//                   inverse. The pinned constants are rounded, so the
//                   encode's linear branch at the cut (0.10063871159)
//                   and the decode's cut2 differ by ~1.0e-4 — values
//                   in that band decode through the log branch
//                   (measured; the curve's own property, documented,
//                   not altered).
//   Cineon        — the pinned form: black_offset =
//                   10^((95-685)/300); encode (685 + 300*log10(L*(1-
//                   bo) + bo))/1023, decode (10^((1023*V - 685)/300)
//                   - bo)/(1 - bo). L >= -bo/(1-bo) keeps the log
//                   argument positive (moderate negatives reversible);
//                   the linear term B*L with B = 0.0 handles L = 0.
//                   Anchors: 0.0 -> 0.09286412512218964, 0.18 ->
//                   0.4573196130854184, 1.0 -> 0.6695992179863147
//                   (pinned doctests).
//
// The .cube table-domain clamp stays the sampler's (Lut.cpp): this model
// never adds a pipeline clamp ahead of the table.

#pragma once

#include <string>

namespace rawedit {

// ---- Gamuts (primaries define the gamut; white point per gamut —
// D65 for all EXCEPT ACEScg, whose white is the ACES chromaticity
// (0.32168, 0.33767) ~D60 — see whitePointGamut. New values APPEND;
// the ordinals are pinned by LutInterpTest and the JNI seam.) ----
enum class GamutId {
    Rec709,     // = sRGB primaries (single identity)
    Rec2020,
    DaVinciWideGamut,
    Acescg,         // AP1 primaries, ACES ~D60 white (commit (e))
    ArriWideGamut3, // ARRI AWG3, D65 (commit (e))
    SGamut3Cine,    // Sony S-Gamut3.Cine, D65 (commit (e))
    CinemaGamut,    // Canon Cinema Gamut, D65 (commit (e))
    AppleWideGamut, // Apple Wide Gamut, D65 (commit (e))
    VGamut,         // Panasonic V-Gamut, D65 (commit (e))
    // F-Gamut is deliberately absent: no pinned Fujifilm gamut dataset
    // (see the provenance note above and the mission report).
};

// ---- Transfer functions ----
enum class TransferId {
    Linear,
    Srgb,
    Rec709OETF,     // the existing linearToRec709 curve
    Gamma24,        // pure gamma 2.4, a separate named curve
    DaVinciIntermediate,
    Acescct,        // ACEScct (commit (e))
    ArriLogC3,      // ARRI LogC3, SUP 3.x EI 800 (commit (e))
    SonyLog3,       // Sony S-Log3 (commit (e))
    CanonLog3,      // Canon C-Log3 (commit (e))
    AppleLog,       // Apple Log / Apple Log 2 shared curve (commit (e))
    VLog,           // Panasonic V-Log (commit (e))
    FujifilmFLog,   // Fujifilm F-Log (commit (e))
    Cineon,         // Cineon log (commit (e))
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

// M_gamut->XYZ for the gamut's white point, row-major 3x3.
// Rec709: the pinned 4-decimal sRGB dataset matrix, verbatim.
// Rec2020: derived from its primaries by normalised primary matrix
// (the pinned BT.2020 dataset derives the same way).
// DaVinciWideGamut: the pinned corrected matrix, verbatim.
// Acescg: npm-derived from AP1 primaries + ACES white (the pinned
// ACES dataset derives the same way).
// ArriWideGamut3 / SGamut3Cine: the pinned matrices, verbatim.
// CinemaGamut / AppleWideGamut / VGamut: npm-derived from their
// pinned primaries + D65 (the Cinema Gamut dataset derives the same
// way; Apple/Panasonic primaries are pinned in the CTLs).
void gamutToXyz(GamutId g, float out[9]);

// M_XYZ->gamut: the computed inverse of gamutToXyz(g). The pinned
// dataset inverse matrices (4-dp for 709, 8-dp for DWG) are cross-checks
// in the tests, not the runtime path: the computed inverse keeps
// A->B->A round trips exact (the 4-dp pinned inverse slips by ~2e-5).
void xyzToGamut(GamutId g, float out[9]);

// The gamut's white point as XYZ tristimulus with Y = 1 (D65 for all
// gamuts except ACEScg, whose white is the ACES chromaticity
// (0.32168, 0.33767) ~D60; kept as data so the CAT below has a source
// — gamutToGamut adapts between whites with Bradford).
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
// This is the frame-level entry for the SceneLook and DisplayLook roles
// (their strength mix is a single-branch blend in the role's reference);
// the SceneToDisplay mixed-strength path uses the per-pixel form so the
// two-branch blend can share the stage point (see applyManagedLutStage).
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

// The stage-23/24 router: applies tonemapping (grade.tonemap, 0 = off,
// 1 = ACES, 2-5 = AgX — rawedit::tonemapFrame) and the LUT in the order
// the binding's role names, on the image at the stage-23 point (after
// all grade stages, before the Rec.709 OETF that lives in the caller's
// tail). One shared entry so developCpu and developEncoded stay in
// lockstep (rawedit::tonemapFrame is reachable through Develop.h).
//
//   binding == nullptr, lut == nullptr, or role LegacyLinear709:
//     the legacy pair verbatim — tonemapFrame (if grade.tonemap != 0),
//     then applyLut (if lut and grade.lutStrength != 0) — byte-identical
//     to the pre-management stage 23/24 sequence. Old records and all
//     existing call sites land here.
//   DisplayLook: tonemapFrame first, then evaluateManagedLut (the
//     strength blend happens in the rendered reference). A
//     passthroughEncodings binding (Linear/Linear 709/709) routes to
//     applyLut verbatim — the managed chain degenerates to the legacy
//     sampler, so the numbers stay byte-identical there too.
//   SceneLook: evaluateManagedLut on the scene-linear image first, then
//     tonemapFrame ONCE after — ACES/AgX never runs on top of the
//     LUT's own rendered output.
//   SceneToDisplay: the LUT branch is already rendered output —
//     tonemapFrame never applies on top of it. Strength 0 = the legacy
//     pair (exact bypass, byte-identical); strength 1 = the managed
//     branch alone, no tonemap even when grade.tonemap != 0; strength
//     in (0,1) = BOTH branches evaluated at this stage point and mixed
//     in the rendered reference: baseline = tonemapFrame of a copy of
//     the scene image (byte-identical to the un-LUT'd chain's stage 23),
//     lut = evaluateManagedLutPixel of the scene image, and
//     out = lut*s + baseline*(1-s). One frame copy, no second develop.
//
// tonemapMode == 0 with any binding behaves as the legacy pair (no
// tonemap step), so a neutral grade stays byte-identical everywhere.
void applyManagedLutStage(ImageF& img, const Lut* lut,
                          const CompiledLutBinding* binding,
                          int tonemapMode, float lutStrength);

} // namespace rawedit
