// rawedit .cube export — bake the grade's COLOR chain into a 3D LUT (E7).
//
// WHAT IS BAKED (the owner's E7 wording: "the current grade's color part"):
//   1. the legacy tone/color stages 9-20 of a rawedit::Grade (black point,
//      white point, shadows, midtones, contrast, exposure, highlights,
//      temperature, tint, the color wheel, split tone, saturation) — the
//      SAME code developCpu runs, via applyToneColorStagesCpu (never a
//      re-implementation; float op order identical to the render path);
//   2. the v2 node chain (rawedit::applyNodes, N1) when one is given;
//   3. the output: either the legacy tonemap + stage-24 LUT pair
//      (applyManagedLutStage) or the OpenDRT output (applyOpenDrt /
//      applyOpenDrtBaked), per CubeExportOutput;
//   4. nothing else: geometry (crop/straighten/stab), the spatial noise
//      stages 7-8 (chroma/luma denoise) and the sharpening stages 21-22
//      (sharpness/detail) are EXCLUDED — they are not per-pixel pure and a
//      3D LUT cannot carry them. highlightReconstruction/highlightRecovery
//      (stage 3) and the WB/color-matrix stages 1-6 are camera-side, not
//      grade color: they do not apply to an arbitrary LUT input either.
//      The LUT bake takes a fully-developed, demosaiced, white-balanced,
//      color-transformed RGB triple as its input — exactly what the
//      app's pipeline hands to stage 9.
//
// INPUT SPACE (owner decision K1-A, docs/COLOR_MODEL_V2_PLAN.md §2):
//   CubeExportInput::Rec709Linear — the pipeline's working space AFTER
//   stage 6: scene-referred LINEAR Rec.709 (BT.709 primaries, D65). This
//   is the space developCpu hands to stage 9, so a lattice sample r,g,b is
//   the linear-709 value a developed pixel would carry there. K1-A keeps
//   DWG/DI inside the NODES stage only, and the bake mirrors that: the
//   DI encode/decode happens INSIDE applyNodes, invisible here.
//   CubeExportInput::Rec709Code — the same space with the Rec.709 OETF
//   (linearToRec709) pre-applied to the lattice input and inverted on the
//   output, for consumers that expect a display-encoded 709 cube (the
//   common "video" LUT convention). The DEFAULT is the linear one: it is
//   the honest grid over the chain's real input space, and the plan's
//   COL-07 contract already DEFINES this app's LUT input as linear 709.
//
// OUTPUT SPACE:
//   CubeExportOutput::LegacyTonemapLut — applyManagedLutStage on the
//   stage-23/24 point (grade.tonemap ACES/AgX then grade's LUT with the
//   managed binding), and the output stays LINEAR 709 (the app's tail
//   applies the OETF later). This is what the app renders for a legacy
//   grade, minus the OETF.
//   CubeExportOutput::OpenDrt — the v2 output block (applyNodes with
//   NodesOutput::DwgLinear then the OpenDRT transform). The output is
//   DISPLAY CODE VALUES 0..1 (the plan §3.4 note: the DRT output is
//   display-coded already; the app skips the OETF there). A .cube of
//   this flavor is what a Resolve round trip of a DRT grade wants.
//
// FORMAT: the Adobe .cube 3D layout (the same convention the parser in
// rawedit/Lut.h reads): "TITLE", "LUT_3D_SIZE N", then N^3 rows "r g b"
// with RED the fastest axis — row m holds entry (m % N, (m/N) % N, m/N^2).
// Values are written with "%.6f". CLAMP POLICY: every output sample is
// clamped to 0..1 before it is written (NaN -> 0): .cube consumers
// (Resolve included) clamp table values at load or render time anyway,
// and Klipp's own bake (engine/OpenDrt.kt composite) clamps identically.
// The internal bake does NOT clamp between stages: the chain sees the
// honest intermediate values (negatives and >1 reach later stages), so
// the bake equals the render path's arithmetic; only the FILE is clamped.
// DOMAIN_MIN/MAX are not written: the default 0..1 domain is what the
// bake spans (writing it would state the same thing twice).
//
// DETERMINISM: the bake is a pure function of (spec, grade, chain, n).
// The row split below is the bakeOpenDrtLut pattern: each (g, b) row owns
// its own outputs, so the parallel split never changes a value.
//
// ORDER AND SOURCE-KIND CAVEATS (verified against the render paths, E7):
//   * The bake's stage order is the v2 MODEL's documented render order
//     (ClipGradeV2: "develop, legacy, nodes, output") and the GPU
//     pipeline's own pass order (POST 9-20 -> unsharp -> warp -> NODES ->
//     TAIL 23/24+OETF; GpuDevelopPipeline draw 5/10.5/11). The CPU export
//     leg runs the 23/24 pair INSIDE developCpu, i.e. BEFORE the node
//     chain — an existing CPU/GPU ordering divergence for a
//     tonemap+nodes grade; the bake follows the GPU/v2-model order (the
//     GPU is the primary render path for NODES grades, the CPU arm the
//     fallback). The G5 wiring can compose either by calling the pieces
//     differently if byte-parity with the CPU leg is ever wanted.
//   * Stages 9-20 are the MCRAW develop path's semantics. The ENCODED
//     (Mi-Log) path applies temp/tint/exposure in its own WB loop and
//     runs NO tone-band stage (Transfer.h's own record); its node chain
//     and output are the same as MCRAW's. The bake evaluates what it is
//     handed — G5 decides per source kind which Grade fields to pass
//     (for an encoded clip the tone-band fields do not match its render).
//   * With output = OpenDrt the legacy tonemap/LUT pair NEVER runs (the
//     DRT replaces the output — the GPU tail-drt rule: "tonemap/LUT/OETF
//     all skipped"); grade.tonemap/grade.lutStrength are read only in
//     the LegacyTonemapLut flavor.

#pragma once

#include <memory>
#include <string>
#include <vector>

#include <rawedit/ColorModel.h>
#include <rawedit/Color.h>
#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/OpenDrt.h>

namespace rawedit {

// The lattice input's encoding. Rec709Linear is the default (see the
// header's INPUT SPACE record).
enum class CubeExportInput {
    Rec709Linear, // scene-referred linear Rec.709 (the stage-9 input)
    Rec709Code,   // display-encoded Rec.709 (OETF pre-applied)
};

// The output stage. LegacyTonemapLut is the v1 tail (tonemap + stage-24
// LUT, linear 709 out); OpenDrt is the v2 output block (display code out).
enum class CubeExportOutput {
    LegacyTonemapLut,
    OpenDrt,
};

// Everything one bake needs. Default-constructed = the neutral legacy
// bake (an all-neutral Grade bakes to the identity, within the format's
// own rounding).
struct CubeExportSpec {
    Grade grade;                     // stages 9-20 + the legacy tail fields
    const GradeNode* nodes = nullptr; // the v2 chain (N1); null/empty = none
    size_t nodeCount = 0;
    CubeExportOutput output = CubeExportOutput::LegacyTonemapLut;
    const Lut* lut = nullptr;        // the stage-24 LUT (LegacyTonemapLut only)
    const CompiledLutBinding* binding = nullptr; // its managed binding
    OpenDrtSpec drt;                 // the OpenDrt output (OpenDrt only)
    CubeExportInput input = CubeExportInput::Rec709Linear;
    std::string title;               // the TITLE line ("" = none)
};

// The baked table in .cube row order (red fastest), n^3 * 3 floats, values
// already clamped to 0..1 — the exact bytes the writer emits.
struct CubeExportTable {
    int n = 0;
    std::vector<float> rgb;
};

// Bakes the spec into an n x n x n table (n = 33 or 65 expected; any
// n in 2..128 is accepted, the parser's own bound). Deterministic.
// Returns n = 0 on an invalid grid or a chain that exceeds the N1 limits
// (validateNodeChain — the honest refusal, never a silent bake).
CubeExportTable bakeGradeCube(const CubeExportSpec& spec, int n);

// Renders the table as .cube text (TITLE when non-empty, LUT_3D_SIZE,
// then the rows). Locale-independent ("%.*f" through the C locale,
// snprintf's own default) so the bytes do not depend on the host.
std::string writeCubeText(const CubeExportTable& table);

// Convenience: bake + render in one call.
std::string exportGradeCube(const CubeExportSpec& spec, int n);

} // namespace rawedit
