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
//  17. sharpness (luminance unsharp mask at edge scale; chroma untouched)
//  18. detail (luminance unsharp mask at fine-texture scale; chroma untouched)
//  19. tonemapping (optional display tone compression; LAST, after grade)
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
// RAW metadata stages 2, 3, 14 and 15 are additionally toggleable by the
// Grade booleans below (keepVignetting, highlightReconstruction, asShot);
// each defaults to the pre-toggle behavior, so the defaults preserve the
// current look exactly.
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
//
// Detail controls (owner request 2026-11-10 section 3, COL-15):
//   sharpness  : [-100,100] edge sharpening. Luminance-only unsharp mask at
//                radius 1.5 px (COL-15's "kenar keskinligi" — edge scale).
//                0 neutral (17).
//   detail     : [-100,100] fine-texture contrast. Second luminance-only
//                unsharp mask at radius 0.75 px (COL-15's "ince doku
//                kontrastini ayri olceklerde isle" — the two scales stay
//                separate, Detail is its own control). 0 neutral (18).
// Amount mapping for both: the unsharp weight is 0.5 * (v/100) on the
// [-100,100] slider, clamped to [-0.5, 0.5] — +100 adds half the local
// high-pass delta (crisp), -100 subtracts half of it (soft/blur). Both work
// on the luma plane only and add the SAME delta to r, g and b, so chroma and
// hue are preserved (no chroma halos). When both are 0 the stage allocates
// no blur and is an exact passthrough.
//
// Tonemapping (owner request 2026-11-10 section 3, COL-16). Applied AFTER
// all grade stages (17-18 included), BEFORE the Rec.709 OETF that lives in
// the JNI bridge tail — so the stage input/output is linear RGB in the
// working space (linear Rec.709 primaries), and the OETF still happens
// outside, unchanged. Per COL-16 the selected transform's version, working
// space and target display are recorded here:
//   tonemap    : 0 = off (default — exact passthrough, old look preserved),
//                1 = ACES, 2 = AgX (19).
//   ACES (tonemap 1): "ACES Filmic Tone Mapping Curve", Krzysztof
//   Narkowicz, 2015 (the widely used RRT+ODT fit; aces_fit x = (x(2.51x +
//   0.03)) / (x(2.43x + 0.59) + 0.14), clamped to [0,1]). Working space:
//   treated as ACEScg-domain linear (the Rec.709-primaries working image is
//   fed to the fit directly, the standard approximation used by the fit).
//   Version: 2015 single-stage fit, no per-channel look. Target display:
//   Rec.709 / sRGB-class, ~1.0 nits-anchored output (the fit maps 1.0 to
//   ~0.804 and 0.18 to ~0.267).
//   AgX (tonemap 2): 2022 Filmic AgX (Troy Sobotka, github.com/sobotka/AgX),
//   essential/minimal fit after Benjamin Wrensch's MIT-licensed minimal
//   implementation (iolite-engine.com/blog_posts/minimal_agx_implementation,
//   which derives its constants from Sobotka's config): inset to AgX space,
//   log2 encode clamped to [-12.47393, 4.026069] EV around 0.18, 6th-order
//   contrast polynomial (agxDefaultContrastApprox_6th: 15.5x^6 - 40.14x^5 +
//   31.96x^4 - 6.868x^3 + 0.4298x^2 + 0.1191x - 0.00232 — monotone on
//   [0,1], unlike the 7th-order variant), Punchy look (power 1.35, sat 1.4,
//   the AgX "punchy" tuning Sobotka ships), outset back, Rec.709 gamma 2.2.
//   Working space: linear Rec.709 in (the inset matrix maps it to AgX's
//   space). Version: 2022 essential fit with the Punchy look. Target
//   display: Rec.709 / sRGB-class with a 2.2 EOTF (the minimal fit's
//   documented display assumption).
//
// RAW metadata toggles (spec RAW-07, COL-09, RAW-04, RAW-06). All three
// default to TRUE, which is exactly the pre-toggle pipeline, so a
// default-constructed Grade stays bit-identical to the old output:
//   asShot                : WB source selector. true = the camera's recorded
//                           as-shot WB (stage 5 divides by meta.asShotNeutral
//                           and stages 14-15 are SKIPPED — asShot true is
//                           equivalent to tempK 5600 / tint 0, which the old
//                           neutral grade already ran as no-ops). false =
//                           manual WB: stages 14-15 run and tempK/tint carry
//                           the user's values. The manual values stay stored
//                           in tempK/tint either way, so toggling back on
//                           never loses them.
//   highlightReconstruction: stage 3 toggle. true = clipped channels are
//                           capped at the lowest clipped channel's level so
//                           clipped areas go neutral (the old behavior).
//                           false = stage 3 is skipped and clipped sites keep
//                           their raw clipped level, which can read magenta —
//                           that sensor-channel artifact is exactly what the
//                           owner wants to be able to keep or drop.
//   keepVignetting        : stage 2 toggle. true = the embedded lens shading
//                           map is applied (vignette corrected, the old
//                           behavior). false = stage 2 is skipped and the
//                           frame keeps the optical vignette the sensor
//                           actually recorded.
// The Highlights TONE slider (stage 13) is independent of
// highlightReconstruction: it remaps near-clipped tones after the fact and
// never decides whether the sensor channels are reconstructed.
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
    bool asShot = true;
    bool highlightReconstruction = true;
    bool keepVignetting = true;
    float sharpness = 0.0f;
    float detail = 0.0f;
    int tonemap = 0; // 0 off, 1 ACES, 2 AgX — see the comment block above
};

// Linear RGB float image, row-major, 3 floats per pixel (r, g, b).
struct ImageF {
    uint32_t w = 0;
    uint32_t h = 0;
    std::vector<float> rgb;
};

// Internal stage helpers shared by developCpu and developEncoded: the
// encoded (Mi-Log) path runs the SAME detail stages (17-18) and tonemapping
// stage (19) on its decoded RGB, so both pipelines stay in lockstep
// (COL-15/COL-16). Not part of the stable surface — declared here only so
// the two develop implementations share one definition.
void unsharpLuma(ImageF& img, float radius, float amount);
void tonemapFrame(ImageF& img, int mode);

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
