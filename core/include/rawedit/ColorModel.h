// rawedit color model v2 — the CPU reference for the NODES stage (F1-1).
//
// docs/COLOR_MODEL_V2_PLAN.md §2-§3. Decisions in force (owner K1-A):
//   * develop stays in linear Rec.709 (BT.709 primaries, D65). DaVinci Wide
//     Gamut (DWG) and DaVinci Intermediate (DI) exist ONLY inside the NODES
//     stage: 709 -> DWG (matrix) -> DI (encode) -> nodes -> DI decode -> DWG
//     -> 709 (matrix), or hands the DWG-linear result to the OpenDRT output
//     (OpenDrt.h).
//   * The DI/DWG constants and the 709<->DWG matrix are NOT redefined here:
//     everything goes through Color.h (encodeTransfer/decodeTransfer with
//     TransferId::DaVinciIntermediate, gamutToGamut(Rec709, DaVinciWideGamut)).
//   * An empty or all-neutral chain with NodesOutput::Rec709Linear touches
//     nothing: the image stays bit-identical (the plan's "nodes=[] adds no
//     pass" contract, mirrored on the CPU).
//
// The node math is a port of Klipp's GradeNode (engine/Grade.kt, N1 subset:
// primaries, log wheels, curves, node LUT, mix/enable). Left out on purpose
// (N2+, plan §3.3): HSL curves, qualifier, window, blur/pixelate/denoise,
// density, film, node cache, input EI. Deviations from the Klipp port:
//   * pivot default is the DI code of 18 % grey (0.336043...), not Klipp's
//     camera-log 0.435; the log-wheel zone limits default to DI codes three
//     stops either side of grey (see kNodeLogLow/kNodeLogHigh).
//   * the node LUT runs through the managed LUT binding (Color.h) so its
//     input/output encoding is the LUT's own interpretation; its result is
//     NOT clamped to 0..1 (DI values reach past it by design).
//   * hue/saturation use Rec.709 luma weights on the DI code values, as
//     Klipp does on its log codes (one constant, kNodeLuma, if that is ever
//     revisited).
//
// Nothing here is wired into developCpu/developEncoded yet: that is F1-6.

#pragma once

#include <cstddef>
#include <string>
#include <utility>
#include <vector>

#include <rawedit/Color.h>
#include <rawedit/Lut.h>

namespace rawedit {

struct ImageF; // Develop.h
struct Lut;    // Lut.h

// ---- DI / DWG glue ----------------------------------------------------

// 18 % grey in DaVinci Intermediate: (log2(0.18 + 0.0075) + 7) * 0.07329248
// = 0.336043272 (computed in the plan, re-checked in test_color_model).
constexpr float kDiMidGray = 0.336043272f;

// Row-major 3x3, linear light: 709 -> DWG and DWG -> 709.
// Both are gamutToGamut() results (D65 both sides, so the CAT is identity).
void rec709ToDwgMatrix(float out[9]);
void dwgToRec709Matrix(float out[9]);

// One pixel, linear Rec.709 -> DI code (DWG linear in between), unclamped.
void linear709ToDi(const float in[3], float out[3]);
// The inverse: DI code -> linear Rec.709.
void diToLinear709(const float in[3], float out[3]);
// Linear Rec.709 -> DWG linear (matrix only) and back.
void linear709ToDwg(const float in[3], float out[3]);
void dwgToLinear709(const float in[3], float out[3]);

// ---- Node data (N1) ---------------------------------------------------

// A color wheel: puck position (x, y in the unit circle) + master (-1..1).
struct NodeWheel {
    float x = 0.0f, y = 0.0f, master = 0.0f;
    bool isNeutral() const { return x == 0.0f && y == 0.0f && master == 0.0f; }
};

// A curve: control points (x, y) in 0..1, sorted by x; two points
// (0,0)-(1,1) = identity. Monotone cubic (Fritsch-Carlson) -> 256 values.
struct NodeCurve {
    std::vector<std::pair<float, float>> points{{0.0f, 0.0f}, {1.0f, 1.0f}};
    bool isIdentity() const {
        return points.size() == 2 && points[0] == std::make_pair(0.0f, 0.0f) &&
               points[1] == std::make_pair(1.0f, 1.0f);
    }
};

// Log-wheel zone limits in DI code values: the codes of 18 % grey / 8 and
// 18 % grey * 8 (+-3 stops), rounded to 3 decimals. DI(0.0225) = 0.1423...,
// DI(1.44) = 0.5521... (test_color_model asserts the derivation).
constexpr float kNodeLogLow = 0.142f;
constexpr float kNodeLogHigh = 0.552f;

struct GradeNode {
    bool enabled = true;
    NodeWheel lift, gamma, gain, offset;
    float contrast = 1.0f;
    float pivot = kDiMidGray;
    float sat = 1.0f;
    float hue = 0.0f;  // degrees, rotation around the grey axis
    float temp = 0.0f; // -1..1
    float tint = 0.0f; // -1..1
    NodeCurve curveY, curveR, curveG, curveB;
    NodeWheel logShadow, logMid, logHi;
    float logLow = kNodeLogLow;
    float logHigh = kNodeLogHigh;
    float mix = 1.0f;

    // Node LUT (N1: at most kMaxNodeLuts nodes per chain). NON-OWNING: the
    // caller keeps the parsed Lut alive for the duration of the call (the
    // runtime resolves lutId -> Lut). lutMix mixes the LUT result into the
    // node's DI value, in DI.
    const Lut* lut = nullptr;
    LutInterpretation lutInterp; // the LUT's own input/output encoding
    float lutMix = 1.0f;

    bool hasCurves() const {
        return !(curveY.isIdentity() && curveR.isIdentity() &&
                 curveG.isIdentity() && curveB.isIdentity());
    }
    bool hasLog() const {
        return !(logShadow.isNeutral() && logMid.isNeutral() && logHi.isNeutral());
    }
    bool hasLut() const { return lut != nullptr && lutMix > 0.0001f; }
    // A neutral node is the identity: applyNodes skips it entirely.
    bool isNeutral() const;
};

// N1 limits (owner K8; the GPU uniform/texture budget, plan §5.4).
constexpr size_t kMaxNodes = 8;
constexpr size_t kMaxNodeLuts = 4;

// "" when the chain fits the N1 limits; otherwise a short reason.
std::string validateNodeChain(const GradeNode* nodes, size_t n);

enum class NodesOutput {
    Rec709Linear, // legacy output: back to linear 709; empty chain = no-op
    DwgLinear,    // the OpenDRT input: DWG linear (always converts)
};

// Applies the chain in place. The image is linear Rec.709 (develop's
// space). Returns false (and leaves the image untouched) when the chain
// exceeds the N1 limits (validateNodeChain). Pure CPU reference; the GPU
// shader must match it (F1-5/F1-7).
bool applyNodes(ImageF& img, const GradeNode* nodes, size_t n,
                NodesOutput out = NodesOutput::Rec709Linear);

// ---- Pieces exposed for the tests / the future GPU parity harness ------

// The composite curve tables: kNodeCurveN entries per channel over DI code
// values [kNodeCurveLo, kNodeCurveHi] (log wheels first, then Y, then the
// channel curve); the value stored is the curve's output, not a delta.
constexpr int kNodeCurveN = 512;
constexpr float kNodeCurveLo = -0.5f;
constexpr float kNodeCurveHi = 1.5f;
float nodeCurveX(int i);
// A 256-entry table of a curve over 0..1 (monotone cubic).
std::vector<float> nodeCurveTable(const NodeCurve& c);
// The log wheels on one channel as a kNodeCurveN table.
std::vector<float> nodeLogCurve(float shadow, float mid, float high,
                                float low, float top);
// A composite table at any x: linear between entries, straight beyond.
float nodeCurveSample(const float* table, float x);

// One DI pixel through one node (the unit the shader ports). Returns
// without change for a neutral node. di is in/out.
void applyNodeDi(const GradeNode& node, float di[3]);

// ---- The GPU uniform pack (F1-5) -------------------------------------------
//
// The NODES shader's per-node uniforms are the CPU compile()'s own
// values (docs/F1_5_GPU_OPS_INVENTORY.md §2: "derleme-zamanı" — the wheel
// cosine/atan2 fold, the primaries solve, the hue matrix, the WB vector —
// all INIT-time and NEVER per-pixel). This struct is exactly the
// ColorModel.cpp Compiled shape, promoted out of the anonymous namespace
// so the Kotlin parcel layer (NodeParcel.kt mirrors this layout) and the
// C++ pipeline fold the SAME numbers; the shader only re-applies them.
struct CompiledNode {
    bool lutActive = false;
    float lift[3] = {0, 0, 0}, gain[3] = {0, 0, 0}, offset[3] = {0, 0, 0},
          gammaExp[3] = {0, 0, 0};
    float ySide[3] = {0, 1, 1};  // the luminance side, always run (a port)
    float contrast = 1.0f, pivot = kDiMidGray;
    float wb[3] = {1, 1, 1};
    float sat = 1.0f;
    float hueMat[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // row-major
    float mix = 1.0f;
    // The composite curve table, 3 * kNodeCurveN (values, not deltas), or
    // empty when the node has no curves/log wheels — the GPU's atlas row
    // and the CPU's applyCompiled consume the SAME bytes.
    std::vector<float> curves;
    // The node LUT's binding (compileLutBinding output), copy when active.
    CompiledLutBinding lutBinding{};
    float lutMix = 1.0f;
};

// Compiles one node into the uniform pack. [lut] may be null with
// node.hasLut() false; otherwise the caller keeps the Lut alive (the
// pack carries the BINDING, not the table — the GPU uploads tables
// separately per revision).
CompiledNode compileNode(const GradeNode& node);

// One DI pixel through the compiled pack — applyNodeDi's second half,
// the exact function the shader's loop body must match (used by the
// headless GLSL-parity tests to pin the order of operations).
void applyCompiledNode(const CompiledNode& p, float di[3]);

} // namespace rawedit
