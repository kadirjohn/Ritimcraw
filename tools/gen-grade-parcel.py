#!/usr/bin/env python3
"""The grade parcel's single source of truth (the parcel crossing, commit a).

The SCALAR grade fields cross the JNI seam as ONE flat FloatArray. The
wire layout — name, kind, neutral default, slot index — is defined ONCE in
the SCHEMA list below, and BOTH mirrors are GENERATED from it:

  - app/src/main/java/com/ritim/ritimcraw/engine/GradeParcel.kt
    (the Kotlin offsets + the GradeK encoder + the validating decoder)
  - app/src/main/cpp/gles/GradeParcel.h
    (the gles::DevelopGrade struct + the constexpr slot set + the
    validating C++ decode + applyGradeToRequest, the generated fold onto
    the session request structs)

Hand-mirroring offsets on either side is FORBIDDEN: edit the SCHEMA here
and regenerate. A new grade field is ONE schema row here (which regenerates
the encoder line, the decoder line, the offset, and the struct member) plus
the GradeK property itself.

Stale-mirror catches (two independent ones):
  1. `python3 tools/gen-grade-parcel.py --check` regenerates both files in
     memory and diffs them against the committed copies — any drift
     (hand-edit, forgotten regeneration after a schema change) prints the
     file, the first differing line pairs, and EXITS 1 (wireable as a
     gate step).
  2. GradeParcelTest (the JVM suite) reads the committed C++ header from
     the repo and asserts every constexpr slot equals the Kotlin offset —
     so a regenerated-but-not-cross-checked mirror cannot pass the suite
     the owner runs on every commit.

Evolution rule: fields APPEND. Never reorder, never reuse an offset — the
slot index is a wire contract with already-written parcels. Booleans cross
as 0.0f/1.0f and decode with != 0.0f (after a strict 0/1 validation).
tonemap crosses as int-as-float. lutStrength is NOT a parcel field: it
belongs to the per-job LUT binding group (lutPath, lutStrength, and the
five interpretation ordinals), which stays flat at the seam.

Layout history: 2026-11-25 (owner decision, 6b62faa) REMOVED highlightsCurve
(slot 29 of the 31-slot era) and renumbered highlightRecovery 30 -> 29
(SLOT_COUNT 31 -> 30). The SCHEMA list below is the CURRENT committed
layout; the append-only rule holds from here.

Usage:
  python3 tools/gen-grade-parcel.py           # (re)generate both mirrors
  python3 tools/gen-grade-parcel.py --check   # verify committed mirrors
"""

import os
import sys

# ---------------------------------------------------------------------------
# The schema: (name, kind, default). The list order IS the slot order.
# kind: "float" | "bool" | "int" (int crosses as float; decode casts back).
# APPEND ONLY — see the module docstring (the one removal so far: the
# 2026-11-25 highlightsCurve removal, owner decision, committed as 6b62faa).
# ---------------------------------------------------------------------------
SCHEMA = [
    ("chromaDenoise", "float", "0.0"),
    ("noiseReduction", "float", "0.0"),
    ("temporalNr", "bool", "false"),
    ("temporalStrength", "float", "0.0"),
    ("sharpness", "float", "0.0"),
    ("detail", "float", "0.0"),
    ("exposureEv", "float", "0.0"),
    ("highlights", "float", "0.0"),
    ("blackPoint", "float", "0.0"),
    ("whitePoint", "float", "0.0"),
    ("shadows", "float", "0.0"),
    ("midtones", "float", "0.0"),
    ("contrast", "float", "0.0"),
    ("tempK", "float", "5600.0"),
    ("tint", "float", "0.0"),
    ("saturation", "float", "0.0"),
    ("asShot", "bool", "true"),
    ("wheelShadowX", "float", "0.0"),
    ("wheelShadowY", "float", "0.0"),
    ("wheelMidX", "float", "0.0"),
    ("wheelMidY", "float", "0.0"),
    ("wheelHighX", "float", "0.0"),
    ("wheelHighY", "float", "0.0"),
    ("splitShadowHue", "float", "-1.0"),
    ("splitHighlightHue", "float", "-1.0"),
    ("splitBalance", "float", "0.0"),
    ("highlightReconstruction", "bool", "true"),
    ("keepVignetting", "bool", "true"),
    ("tonemap", "int", "0"),
    ("highlightRecovery", "bool", "false"),
]

# Trailing // comments the committed struct members carry (added by hand in
# the highlightsCurve era and kept by the byte contract: a regenerate must
# reproduce the committed mirrors exactly). Keyed by schema name.
CPP_MEMBER_COMMENTS = {
    "highlightRecovery": "highlight recovery (replaces stage 3 with a real re-estimate)",
}

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN_PATH = os.path.join(
    REPO, "app/src/main/java/com/ritim/ritimcraw/engine/GradeParcel.kt"
)
CPP_PATH = os.path.join(REPO, "app/src/main/cpp/gles/GradeParcel.h")

RECOVERY = (
    "(recovery: re-encode the grade with GradeParcel.encode; a parcel of a "
    "different length comes from a different schema version - never a "
    "silent misread)"
)


def const_name(name):
    """camelCase -> SCREAMING_SNAKE for the Kotlin offset constants."""
    out = []
    for ch in name:
        if ch.isupper():
            out.append("_")
            out.append(ch)
        else:
            out.append(ch.upper())
    return "".join(out)


def cpp_const_name(name):
    """camelCase -> PascalCase prefixed with kGradeParcel for constexpr slots."""
    return "kGradeParcel" + name[0].upper() + name[1:]


def kotlin_default(kind, default):
    if kind == "float":
        return default + "f"
    if kind == "bool":
        return default
    return default  # int


def cpp_default(kind, default):
    if kind == "float":
        return default + "f"
    if kind == "bool":
        return default
    return default  # int


# ---------------------------------------------------------------------------
# The Kotlin mirror
# ---------------------------------------------------------------------------

# PROSE COUNTS: the "30 SCALAR" (the Kotlin doc below) and "ALL 30 schema
# fields" (the C++ rawedit-fold doc) are plain wording (comments, not runtime
# code) and must equal len(SCHEMA). They were 29 until 2026-11-26, when
# `--check` was found failing on main after a comment-only housekeeping commit
# had corrected the Kotlin mirror to 30; this file now states the same number.
# Bump them together with SCHEMA.

def gen_kotlin():
    count = len(SCHEMA)
    names = [row[0] for row in SCHEMA]

    slot_consts = []
    for i, (name, _kind, _default) in enumerate(SCHEMA):
        slot_consts.append("    const val SLOT_%s = %d" % (const_name(name), i))

    names_array = []
    for name in names:
        names_array.append('            "%s",' % name)

    data_class = []
    for name, kind, _default in SCHEMA:
        kt = {"float": "Float", "bool": "Boolean", "int": "Int"}[kind]
        data_class.append("        val %s: %s," % (name, kt))

    encode = []
    for name, kind, _default in SCHEMA:
        if kind == "bool":
            encode.append("        if (grade.%s) 1.0f else 0.0f," % name)
        elif kind == "int":
            encode.append("        grade.%s.toFloat()," % name)
        else:
            encode.append("        grade.%s," % name)

    bool_checks = []
    for name, kind, _default in SCHEMA:
        if kind == "bool":
            bool_checks.append(
                "        if (parcel[SLOT_%s] != 0.0f && parcel[SLOT_%s] != 1.0f) {\n"
                "            refuse(\n"
                '                "grade parcel: %s must be 0.0 or 1.0 (got " +\n'
                "                    parcel[SLOT_%s] +\n"
                '                ") (recovery: booleans cross the parcel as 0.0f/1.0f)",\n'
                "            )\n"
                "        }" % (const_name(name), const_name(name), name, const_name(name))
            )

    decode = []
    for name, kind, _default in SCHEMA:
        if kind == "bool":
            decode.append("            %s = parcel[SLOT_%s] != 0.0f," % (name, const_name(name)))
        elif kind == "int":
            decode.append("            %s = parcel[SLOT_%s].toInt()," % (name, const_name(name)))
        else:
            decode.append("            %s = parcel[SLOT_%s]," % (name, const_name(name)))

    return """package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK

// GENERATED FILE - DO NOT EDIT. Regenerate with:
//   python3 tools/gen-grade-parcel.py
// The schema (the single source of truth) lives in that script; a stale
// mirror is caught by its --check mode and by the JVM suite's
// GradeParcelTest cross-checking the committed C++ header against these
// offsets.

/**
 * The grade parcel: the 30 SCALAR grade fields as ONE flat FloatArray
 * crossing the JNI seam (the per-frame parameter collapse). One source of
 * truth - the schema in tools/gen-grade-parcel.py - generates BOTH this
 * object and the C++ mirror (gles/GradeParcel.h, the gles::DevelopGrade
 * struct + the constexpr slot set + the validating decode + the generated
 * request fold).
 *
 * WIRE RULES
 *   - Slot order is a wire contract: fields APPEND, never reorder, never
 *     reuse an offset. [SLOT_COUNT] is the length the decode accepts.
 *   - Booleans cross as 0.0f/1.0f and decode with != 0.0f, after a strict
 *     0/1 validation (0.37 is a refusal, not a truthy true).
 *   - tonemap crosses as int-as-float; decode casts back.
 *   - Valid NEGATIVE values are preserved verbatim (sharpness/detail to
 *     -100 are legal softening; the decode never clamps).
 *   - NaN and Infinity are refused in any slot.
 *
 * The parcel is built ONCE per grade change (an immutable parameter
 * packet), not re-encoded per frame.
 *
 * WHAT IS DELIBERATELY NOT IN THE PARCEL (the exclusion list):
 *   - the LUT binding: lutPath, lutStrength, lutRole, lutInGamut,
 *     lutInTransfer, lutOutGamut, lutOutTransfer - a per-JOB, session-level
 *     upload, not a per-frame scalar;
 *   - the gyro warp plan input: gyroPath, stabMode, stabStrength,
 *     stabZoomLimit, stabFocalMm, stabTrimMs - per-frame plan data that
 *     feeds the warp resolver, not a grade scalar;
 *   - the warp verdict: warpActive, warpM - per-frame plan output;
 *   - job mechanics: handles, frameIndex, maxDim, ptsUs, rotationDeg;
 *   - GradeK-only fields: version, lutId, lutInterp, the stabilization
 *     group, the crop group, statsMode.
 *
 * REFUSAL CHANNELS (both honest, never a silent misread): the Kotlin
 * decode throws IllegalStateException with a recovery message; the C++
 * decode has no exception channel across the JNI boundary, so the bridge
 * rides its setError contract (error code + stage + message, read back via
 * nativeLastError) and returns the empty value.
 */
object GradeParcel {

    /** The one length the decode accepts; any other length is a refusal. */
    const val SLOT_COUNT = %d

%s

    /** Slot names, index-aligned, for honest refusal messages. */
    private val SLOT_NAMES = arrayOf(
%s
    )

    /** The typed decode target: every scalar field, booleans as Boolean. */
    data class DecodedGrade(
%s
    )

    /**
     * Encodes the scalar grade fields of [grade] into the flat parcel.
     * The temporal pair rides the parcel but dies at the bridge (consumed
     * by the bridge's temporal merge before the request) - encode the
     * EFFECTIVE value the caller threaded (the preview's tier fold is
     * applied by the caller before encoding, via grade.copy).
     */
    fun encode(grade: GradeK): FloatArray = floatArrayOf(
%s
    )

    /**
     * Decodes and validates the parcel. Refuses (IllegalStateException,
     * honest message, never a silent misread) on: a length other than
     * [SLOT_COUNT], a non-finite value in any slot, or a boolean slot
     * outside strict 0.0f/1.0f. Negative values are returned verbatim.
     */
    fun decode(parcel: FloatArray): DecodedGrade {
        if (parcel.size != SLOT_COUNT) {
            refuse(
                "grade parcel: unknown length " + parcel.size + " (expected " + SLOT_COUNT + ") " +
                    "%s",
            )
        }
        for (i in parcel.indices) {
            if (!parcel[i].isFinite()) {
                refuse(
                    "grade parcel: " + SLOT_NAMES[i] + " is not finite " +
                        "(recovery: the parcel must carry finite floats)",
                )
            }
        }
%s
        return DecodedGrade(
%s
        )
    }

    private fun refuse(message: String): Nothing = throw IllegalStateException(message)
}
""" % (
        count,
        "\n".join(slot_consts),
        "\n".join(names_array),
        "\n".join(data_class),
        "\n".join(encode),
        RECOVERY,
        "\n".join(bool_checks),
        "\n".join(decode),
    )


# ---------------------------------------------------------------------------
# The C++ mirror
# ---------------------------------------------------------------------------

def gen_cpp():
    count = len(SCHEMA)

    struct_members = []
    for name, kind, default in SCHEMA:
        cpp_type = {"float": "float", "bool": "bool", "int": "int"}[kind]
        line = "    %s %s = %s;" % (cpp_type, name, cpp_default(kind, default))
        if name in CPP_MEMBER_COMMENTS:
            line += "  // " + CPP_MEMBER_COMMENTS[name]
        struct_members.append(line)

    slot_consts = []
    for i, (name, _kind, _default) in enumerate(SCHEMA):
        slot_consts.append("constexpr int %s = %d;" % (cpp_const_name(name), i))

    names_array = []
    for name, _kind, _default in SCHEMA:
        names_array.append('"%s",' % name)

    bool_checks = []
    for name, kind, _default in SCHEMA:
        if kind == "bool":
            bool_checks.append(
                "    if (parcel[%s] != 0.0f && parcel[%s] != 1.0f) {\n"
                "        *error = std::string(\"grade parcel: %s must be 0.0 or 1.0 (got \") +\n"
                "                     std::to_string(parcel[%s]) +\n"
                "                     \") (recovery: booleans cross the parcel as 0.0f/1.0f)\";\n"
                "        return false;\n"
                "    }" % (cpp_const_name(name), cpp_const_name(name), name, cpp_const_name(name))
            )

    copies = []
    for name, kind, _default in SCHEMA:
        if kind == "bool":
            copies.append("    out->%s = parcel[%s] != 0.0f;" % (name, cpp_const_name(name)))
        elif kind == "int":
            copies.append(
                "    out->%s = static_cast<int>(parcel[%s]);" % (name, cpp_const_name(name))
            )
        else:
            copies.append("    out->%s = parcel[%s];" % (name, cpp_const_name(name)))

    # The session-side fold: the request-visible fields = the schema minus
    # the temporal pair (which dies at the bridge's temporal merge, before
    # the request exists). Generated like everything else - the session
    # overloads must not hand-mirror the field list.
    request_fields = [row for row in SCHEMA if row[0] not in ("temporalNr", "temporalStrength")]
    applies = []
    for name, _kind, _default in request_fields:
        applies.append("    req->%s = grade.%s;" % (name, name))

    # The CPU-side fold: ALL 30 schema fields (the temporal pair included -
    # rawedit::Grade carries them so the whole grade pipeline transports the
    # controls; the bridge consumes the temporal stage before developCpu).
    # lutStrength is NOT a schema field (per-job LUT binding group): the
    # caller sets it from its own flat param after this fold.
    rawedit_copies = []
    for name, _kind, _default in SCHEMA:
        rawedit_copies.append("    out->%s = grade.%s;" % (name, name))

    return """// GENERATED FILE - DO NOT EDIT. Regenerate with:
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
%s
};

constexpr int kGradeParcelSlotCount = %d;

%s

// Slot names, index-aligned, for honest refusal messages.
inline constexpr const char* kGradeParcelSlotNames[] = {
%s
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
                 " (expected " + std::to_string(kGradeParcelSlotCount) + ") %s";
        return false;
    }
    for (int i = 0; i < kGradeParcelSlotCount; ++i) {
        if (!std::isfinite(parcel[i])) {
            *error = std::string("grade parcel: ") + kGradeParcelSlotNames[i] +
                     " is not finite (recovery: the parcel must carry finite floats)";
            return false;
        }
    }
%s
%s
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
%s
}

// Folds the decoded grade onto a rawedit::Grade (the CPU export path's
// developCpu input). ALL 30 schema fields copy by name - the temporal pair
// included (rawedit::Grade carries them so the whole grade pipeline
// transports the controls; the bridge consumes the temporal stage on the
// CFA before developCpu, exactly as the flat path's gradeFromKotlin always
// did). lutStrength is NOT folded: it is not a schema field (per-job LUT
// binding group), so the caller sets it from its own flat param - the
// generated fold and gradeFromKotlin agree field-for-field, which the
// export round-trip test locks.
template <typename GradeT>
inline void applyGradeToRawedit(const DevelopGrade& grade, GradeT* out) {
%s
}

}  // namespace gles
""" % (
        "\n".join(struct_members),
        count,
        "\n".join(slot_consts),
        "\n".join(names_array),
        RECOVERY,
        "\n".join(bool_checks),
        "\n".join(copies),
        "\n".join(applies),
        "\n".join(rawedit_copies),
    )


def clip(line, width=160):
    """Gate-log hygiene: one differing line, truncated, never a page dump."""
    return line if len(line) <= width else line[:width] + " ..."


def drift_lines(committed, generated, limit=8):
    """The first [limit] differing line pairs (committed vs schema) with
    line numbers, for a clear nonzero-exit gate message."""
    al = committed.splitlines()
    bl = generated.splitlines()
    out = []
    for i in range(max(len(al), len(bl))):
        la = al[i] if i < len(al) else "<EOF>"
        lb = bl[i] if i < len(bl) else "<EOF>"
        if la != lb:
            out.append("    line %d committed: %s" % (i + 1, clip(la)))
            out.append("    line %d schema:    %s" % (i + 1, clip(lb)))
            if len(out) >= 2 * limit:
                break
    return out


def main():
    check = "--check" in sys.argv[1:]
    generated = [(KOTLIN_PATH, gen_kotlin()), (CPP_PATH, gen_cpp())]
    stale = False
    for path, text in generated:
        rel = os.path.relpath(path, REPO)
        if not os.path.exists(path):
            if check:
                print("%s: MISSING (run without --check to generate)" % rel)
                stale = True
                continue
            with open(path, "w") as f:
                f.write(text)
            print("%s: generated" % rel)
            continue
        with open(path, "r") as f:
            committed = f.read()
        if committed == text:
            print("%s: up to date" % rel)
            continue
        stale = True
        if check:
            print("%s: STALE" % rel)
            print("  schema drift, first differing lines (committed vs schema):")
            for drift in drift_lines(committed, text):
                print(drift)
            print("  regenerate with: python3 tools/gen-grade-parcel.py")
        else:
            with open(path, "w") as f:
                f.write(text)
            print("%s: regenerated" % rel)
    if check:
        if stale:
            print(
                "GRADE PARCEL SCHEMA DRIFT: the %d-slot schema does not match "
                "the committed mirrors (exit 1 for gates)" % len(SCHEMA)
            )
            sys.exit(1)
        print("grade parcel schema: OK (SLOT_COUNT %d)" % len(SCHEMA))


if __name__ == "__main__":
    main()
