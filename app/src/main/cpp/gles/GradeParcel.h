// GENERATED FILE - DO NOT EDIT. Regenerate with:
//   python3 tools/gen-grade-parcel.py
// The schema (the single source of truth) lives in that script; a stale
// mirror is caught by its --check mode and by the JVM suite's
// GradeParcelTest cross-checking these constants against the Kotlin
// offsets.
#pragma once

#include <cmath>
#include <string>

namespace gles {

// The scalar grade packet decoded ONCE per call from the flat FloatArray
// parcel crossing the JNI seam (the per-frame parameter collapse). The
// struct is the decode target BOTH consumers take: the CPU export path
// (jni_bridge converts it to rawedit::Grade) and the GPU sessions (the
// struct-taking renderFrame overloads). Field order follows the schema's
// canonical order; members map from slots BY NAME (see decodeGradeParcel),
// never by position.
//
// WIRE RULES: slots APPEND (never reorder, never reuse an offset); booleans
// cross as 0.0f/1.0f and decode with != 0.0f after a strict 0/1 check;
// tonemap crosses as int-as-float; negatives are preserved verbatim
// (sharpness/detail to -100 are valid softening - no clamping); NaN and
// Infinity are refused in any slot. lutStrength and the rest of the LUT
// binding stay OUT of the parcel (per-job, session-level), as do the warp
// plan input/verdict and the job mechanics - see GradeParcel.kt's doc for
// the full exclusion list.
struct DevelopGrade {
    float chromaDenoise = 0.0f;
    float noiseReduction = 0.0f;
    bool temporalNr = false;
    float temporalStrength = 0.0f;
    float sharpness = 0.0f;
    float detail = 0.0f;
    float exposureEv = 0.0f;
    float highlights = 0.0f;
    float blackPoint = 0.0f;
    float whitePoint = 0.0f;
    float shadows = 0.0f;
    float midtones = 0.0f;
    float contrast = 0.0f;
    float tempK = 5600.0f;
    float tint = 0.0f;
    float saturation = 0.0f;
    bool asShot = true;
    float wheelShadowX = 0.0f;
    float wheelShadowY = 0.0f;
    float wheelMidX = 0.0f;
    float wheelMidY = 0.0f;
    float wheelHighX = 0.0f;
    float wheelHighY = 0.0f;
    float splitShadowHue = -1.0f;
    float splitHighlightHue = -1.0f;
    float splitBalance = 0.0f;
    bool highlightReconstruction = true;
    bool keepVignetting = true;
    int tonemap = 0;
    bool highlightRecovery = false;  // highlight recovery (replaces stage 3 with a real re-estimate)
};

constexpr int kGradeParcelSlotCount = 30;

constexpr int kGradeParcelChromaDenoise = 0;
constexpr int kGradeParcelNoiseReduction = 1;
constexpr int kGradeParcelTemporalNr = 2;
constexpr int kGradeParcelTemporalStrength = 3;
constexpr int kGradeParcelSharpness = 4;
constexpr int kGradeParcelDetail = 5;
constexpr int kGradeParcelExposureEv = 6;
constexpr int kGradeParcelHighlights = 7;
constexpr int kGradeParcelBlackPoint = 8;
constexpr int kGradeParcelWhitePoint = 9;
constexpr int kGradeParcelShadows = 10;
constexpr int kGradeParcelMidtones = 11;
constexpr int kGradeParcelContrast = 12;
constexpr int kGradeParcelTempK = 13;
constexpr int kGradeParcelTint = 14;
constexpr int kGradeParcelSaturation = 15;
constexpr int kGradeParcelAsShot = 16;
constexpr int kGradeParcelWheelShadowX = 17;
constexpr int kGradeParcelWheelShadowY = 18;
constexpr int kGradeParcelWheelMidX = 19;
constexpr int kGradeParcelWheelMidY = 20;
constexpr int kGradeParcelWheelHighX = 21;
constexpr int kGradeParcelWheelHighY = 22;
constexpr int kGradeParcelSplitShadowHue = 23;
constexpr int kGradeParcelSplitHighlightHue = 24;
constexpr int kGradeParcelSplitBalance = 25;
constexpr int kGradeParcelHighlightReconstruction = 26;
constexpr int kGradeParcelKeepVignetting = 27;
constexpr int kGradeParcelTonemap = 28;
constexpr int kGradeParcelHighlightRecovery = 29;

// Slot names, index-aligned, for honest refusal messages.
inline constexpr const char* kGradeParcelSlotNames[] = {
"chromaDenoise",
"noiseReduction",
"temporalNr",
"temporalStrength",
"sharpness",
"detail",
"exposureEv",
"highlights",
"blackPoint",
"whitePoint",
"shadows",
"midtones",
"contrast",
"tempK",
"tint",
"saturation",
"asShot",
"wheelShadowX",
"wheelShadowY",
"wheelMidX",
"wheelMidY",
"wheelHighX",
"wheelHighY",
"splitShadowHue",
"splitHighlightHue",
"splitBalance",
"highlightReconstruction",
"keepVignetting",
"tonemap",
"highlightRecovery",
};

// Decodes the flat parcel into [out] by NAME. Returns false with the full
// honest message (including recovery) in [error] on any refusal: unknown
// length, a non-finite value, or a boolean slot outside strict 0.0f/1.0f.
// The caller (jni_bridge) rides its setError contract - no rawedit
// exception ever crosses the boundary.
inline bool decodeGradeParcel(const float* parcel, int length, DevelopGrade* out,
                              std::string* error) {
    if (length != kGradeParcelSlotCount) {
        *error = "grade parcel: unknown length " + std::to_string(length) +
                 " (expected " + std::to_string(kGradeParcelSlotCount) + ") (recovery: re-encode the grade with GradeParcel.encode; a parcel of a different length comes from a different schema version - never a silent misread)";
        return false;
    }
    for (int i = 0; i < kGradeParcelSlotCount; ++i) {
        if (!std::isfinite(parcel[i])) {
            *error = std::string("grade parcel: ") + kGradeParcelSlotNames[i] +
                     " is not finite (recovery: the parcel must carry finite floats)";
            return false;
        }
    }
    if (parcel[kGradeParcelTemporalNr] != 0.0f && parcel[kGradeParcelTemporalNr] != 1.0f) {
        *error = std::string("grade parcel: temporalNr must be 0.0 or 1.0 (got ") +
                     std::to_string(parcel[kGradeParcelTemporalNr]) +
                     ") (recovery: booleans cross the parcel as 0.0f/1.0f)";
        return false;
    }
    if (parcel[kGradeParcelAsShot] != 0.0f && parcel[kGradeParcelAsShot] != 1.0f) {
        *error = std::string("grade parcel: asShot must be 0.0 or 1.0 (got ") +
                     std::to_string(parcel[kGradeParcelAsShot]) +
                     ") (recovery: booleans cross the parcel as 0.0f/1.0f)";
        return false;
    }
    if (parcel[kGradeParcelHighlightReconstruction] != 0.0f && parcel[kGradeParcelHighlightReconstruction] != 1.0f) {
        *error = std::string("grade parcel: highlightReconstruction must be 0.0 or 1.0 (got ") +
                     std::to_string(parcel[kGradeParcelHighlightReconstruction]) +
                     ") (recovery: booleans cross the parcel as 0.0f/1.0f)";
        return false;
    }
    if (parcel[kGradeParcelKeepVignetting] != 0.0f && parcel[kGradeParcelKeepVignetting] != 1.0f) {
        *error = std::string("grade parcel: keepVignetting must be 0.0 or 1.0 (got ") +
                     std::to_string(parcel[kGradeParcelKeepVignetting]) +
                     ") (recovery: booleans cross the parcel as 0.0f/1.0f)";
        return false;
    }
    if (parcel[kGradeParcelHighlightRecovery] != 0.0f && parcel[kGradeParcelHighlightRecovery] != 1.0f) {
        *error = std::string("grade parcel: highlightRecovery must be 0.0 or 1.0 (got ") +
                     std::to_string(parcel[kGradeParcelHighlightRecovery]) +
                     ") (recovery: booleans cross the parcel as 0.0f/1.0f)";
        return false;
    }
    out->chromaDenoise = parcel[kGradeParcelChromaDenoise];
    out->noiseReduction = parcel[kGradeParcelNoiseReduction];
    out->temporalNr = parcel[kGradeParcelTemporalNr] != 0.0f;
    out->temporalStrength = parcel[kGradeParcelTemporalStrength];
    out->sharpness = parcel[kGradeParcelSharpness];
    out->detail = parcel[kGradeParcelDetail];
    out->exposureEv = parcel[kGradeParcelExposureEv];
    out->highlights = parcel[kGradeParcelHighlights];
    out->blackPoint = parcel[kGradeParcelBlackPoint];
    out->whitePoint = parcel[kGradeParcelWhitePoint];
    out->shadows = parcel[kGradeParcelShadows];
    out->midtones = parcel[kGradeParcelMidtones];
    out->contrast = parcel[kGradeParcelContrast];
    out->tempK = parcel[kGradeParcelTempK];
    out->tint = parcel[kGradeParcelTint];
    out->saturation = parcel[kGradeParcelSaturation];
    out->asShot = parcel[kGradeParcelAsShot] != 0.0f;
    out->wheelShadowX = parcel[kGradeParcelWheelShadowX];
    out->wheelShadowY = parcel[kGradeParcelWheelShadowY];
    out->wheelMidX = parcel[kGradeParcelWheelMidX];
    out->wheelMidY = parcel[kGradeParcelWheelMidY];
    out->wheelHighX = parcel[kGradeParcelWheelHighX];
    out->wheelHighY = parcel[kGradeParcelWheelHighY];
    out->splitShadowHue = parcel[kGradeParcelSplitShadowHue];
    out->splitHighlightHue = parcel[kGradeParcelSplitHighlightHue];
    out->splitBalance = parcel[kGradeParcelSplitBalance];
    out->highlightReconstruction = parcel[kGradeParcelHighlightReconstruction] != 0.0f;
    out->keepVignetting = parcel[kGradeParcelKeepVignetting] != 0.0f;
    out->tonemap = static_cast<int>(parcel[kGradeParcelTonemap]);
    out->highlightRecovery = parcel[kGradeParcelHighlightRecovery] != 0.0f;
    return true;
}

// Folds the decoded grade onto a session request (DevelopFrameRequest or
// PreviewFrameRequest - both expose the same grade members, so one
// generated fold serves both). GENERATED from the schema: the
// request-visible fields are the schema minus the temporal pair, which
// dies at the bridge's temporal merge BEFORE the request exists (the
// session request never sees temporalNr/temporalStrength). A schema field
// with no matching request member fails the compile at the instantiation
// site - the honest catch, never a silent drop.
template <typename RequestT>
inline void applyGradeToRequest(const DevelopGrade& grade, RequestT* req) {
    req->chromaDenoise = grade.chromaDenoise;
    req->noiseReduction = grade.noiseReduction;
    req->sharpness = grade.sharpness;
    req->detail = grade.detail;
    req->exposureEv = grade.exposureEv;
    req->highlights = grade.highlights;
    req->blackPoint = grade.blackPoint;
    req->whitePoint = grade.whitePoint;
    req->shadows = grade.shadows;
    req->midtones = grade.midtones;
    req->contrast = grade.contrast;
    req->tempK = grade.tempK;
    req->tint = grade.tint;
    req->saturation = grade.saturation;
    req->asShot = grade.asShot;
    req->wheelShadowX = grade.wheelShadowX;
    req->wheelShadowY = grade.wheelShadowY;
    req->wheelMidX = grade.wheelMidX;
    req->wheelMidY = grade.wheelMidY;
    req->wheelHighX = grade.wheelHighX;
    req->wheelHighY = grade.wheelHighY;
    req->splitShadowHue = grade.splitShadowHue;
    req->splitHighlightHue = grade.splitHighlightHue;
    req->splitBalance = grade.splitBalance;
    req->highlightReconstruction = grade.highlightReconstruction;
    req->keepVignetting = grade.keepVignetting;
    req->tonemap = grade.tonemap;
    req->highlightRecovery = grade.highlightRecovery;
}

// Folds the decoded grade onto a rawedit::Grade (the CPU export path's
// developCpu input). ALL 29 schema fields copy by name - the temporal pair
// included (rawedit::Grade carries them so the whole grade pipeline
// transports the controls; the bridge consumes the temporal stage on the
// CFA before developCpu, exactly as the flat path's gradeFromKotlin always
// did). lutStrength is NOT folded: it is not a schema field (per-job LUT
// binding group), so the caller sets it from its own flat param - the
// generated fold and gradeFromKotlin agree field-for-field, which the
// export round-trip test locks.
template <typename GradeT>
inline void applyGradeToRawedit(const DevelopGrade& grade, GradeT* out) {
    out->chromaDenoise = grade.chromaDenoise;
    out->noiseReduction = grade.noiseReduction;
    out->temporalNr = grade.temporalNr;
    out->temporalStrength = grade.temporalStrength;
    out->sharpness = grade.sharpness;
    out->detail = grade.detail;
    out->exposureEv = grade.exposureEv;
    out->highlights = grade.highlights;
    out->blackPoint = grade.blackPoint;
    out->whitePoint = grade.whitePoint;
    out->shadows = grade.shadows;
    out->midtones = grade.midtones;
    out->contrast = grade.contrast;
    out->tempK = grade.tempK;
    out->tint = grade.tint;
    out->saturation = grade.saturation;
    out->asShot = grade.asShot;
    out->wheelShadowX = grade.wheelShadowX;
    out->wheelShadowY = grade.wheelShadowY;
    out->wheelMidX = grade.wheelMidX;
    out->wheelMidY = grade.wheelMidY;
    out->wheelHighX = grade.wheelHighX;
    out->wheelHighY = grade.wheelHighY;
    out->splitShadowHue = grade.splitShadowHue;
    out->splitHighlightHue = grade.splitHighlightHue;
    out->splitBalance = grade.splitBalance;
    out->highlightReconstruction = grade.highlightReconstruction;
    out->keepVignetting = grade.keepVignetting;
    out->tonemap = grade.tonemap;
    out->highlightRecovery = grade.highlightRecovery;
}

}  // namespace gles
