package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The grade parcel's JVM locks (GRADE PARCEL, commit a).
 *
 * THE MECHANISM: the parcel layout (30 scalar slots, wire order, defaults,
 * types) is defined ONCE in tools/gen-grade-parcel.py's SCHEMA; the script
 * GENERATES both mirrors - this side (GradeParcel.kt: the SLOT_* offsets,
 * encode, the validating decode) and the C++ side
 * (app/src/main/cpp/gles/GradeParcel.h: the gles::DevelopGrade struct, the
 * constexpr slot set, the validating decode, the request/rawedit folds) -
 * and COMMITTED. Hand-mirroring offsets on either side is forbidden.
 *
 * THE STALE-MIRROR CATCHES (two, independent):
 *   1. `python3 tools/gen-grade-parcel.py --check` refuses a drifted
 *      mirror (regenerate with the same script);
 *   2. the header cross-check below reads the COMMITTED C++ header from
 *      the repo and asserts every constexpr slot equals the Kotlin offset
 *      - so a mirror regenerated on one side only fails the JVM suite,
 *      whichever side drifted.
 *
 * WHAT THIS FILE LOCKS:
 *   - the round trip: encode a fully-populated GradeK (booleans in BOTH
 *     states across the four boolean slots, sharpness/detail at -100 - the
 *     legal negative softening range) -> decode -> every field equal,
 *     negatives verbatim, never clamped;
 *   - the length guard: a parcel of any length other than SLOT_COUNT is
 *     refused with an honest message (never a silent misread);
 *   - the finiteness guard: NaN and Infinity are refused in ANY slot;
 *   - the boolean strictness: a boolean slot outside strict 0.0f/1.0f
 *     (0.37) is refused - no truthy decode;
 *   - the C++ mirror cross-check (see above).
 *
 * The encode/decode pair is pure Kotlin - no .so needed - so the JVM suite
 * runs all of this on the host.
 */
class GradeParcelTest {

    // ---- the round trip (every field, negatives verbatim) ---------------

    @Test
    fun round_trip_carries_every_field() {
        // Every scalar set to a distinctive non-default value; sharpness and
        // detail at -100 (the legal negative softening range the CPU's
        // amount mapping defines - the decode must return them VERBATIM,
        // never clamp); the four booleans in MIXED states so both wire
        // values (0.0f/1.0f) are exercised before the flip test inverts
        // them.
        val grade = GradeK(
            chromaDenoise = 37.5f,
            noiseReduction = 62.0f,
            temporalNr = true,
            temporalStrength = 44.0f,
            sharpness = -100f,
            detail = -100f,
            exposureEv = 1.25f,
            highlights = -33f,
            blackPoint = 7f,
            whitePoint = 91f,
            shadows = 12f,
            midtones = -8f,
            contrast = 20f,
            tempK = 3900f,
            tint = 15f,
            saturation = 30f,
            asShot = false,
            wheelShadowX = -0.25f,
            wheelShadowY = 0.5f,
            wheelMidX = 0.75f,
            wheelMidY = -0.125f,
            wheelHighX = 0.375f,
            wheelHighY = -0.625f,
            splitShadowHue = 40f,
            splitHighlightHue = 210f,
            splitBalance = -55f,
            highlightReconstruction = false,
            keepVignetting = true,
            tonemap = 2,
        )
        val parcel = GradeParcel.encode(grade)
        assertEquals(GradeParcel.SLOT_COUNT, parcel.size)

        // Spot-lock the wire order against the SLOT_* constants (the full
        // order is proven by the field-equality assertions below - a
        // swapped pair would surface as two mismatches).
        assertEquals(-100f, parcel[GradeParcel.SLOT_SHARPNESS])
        assertEquals(-100f, parcel[GradeParcel.SLOT_DETAIL])
        assertEquals(1.0f, parcel[GradeParcel.SLOT_TEMPORAL_NR])
        assertEquals(0.0f, parcel[GradeParcel.SLOT_AS_SHOT])
        assertEquals(2.0f, parcel[GradeParcel.SLOT_TONEMAP])

        val decoded = GradeParcel.decode(parcel)
        assertEquals(37.5f, decoded.chromaDenoise, 0f)
        assertEquals(62.0f, decoded.noiseReduction, 0f)
        assertEquals(true, decoded.temporalNr)
        assertEquals(44.0f, decoded.temporalStrength, 0f)
        assertEquals(-100f, decoded.sharpness, 0f)
        assertEquals(-100f, decoded.detail, 0f)
        assertEquals(1.25f, decoded.exposureEv, 0f)
        assertEquals(-33f, decoded.highlights, 0f)
        assertEquals(7f, decoded.blackPoint, 0f)
        assertEquals(91f, decoded.whitePoint, 0f)
        assertEquals(12f, decoded.shadows, 0f)
        assertEquals(-8f, decoded.midtones, 0f)
        assertEquals(20f, decoded.contrast, 0f)
        assertEquals(3900f, decoded.tempK, 0f)
        assertEquals(15f, decoded.tint, 0f)
        assertEquals(30f, decoded.saturation, 0f)
        assertEquals(false, decoded.asShot)
        assertEquals(-0.25f, decoded.wheelShadowX, 0f)
        assertEquals(0.5f, decoded.wheelShadowY, 0f)
        assertEquals(0.75f, decoded.wheelMidX, 0f)
        assertEquals(-0.125f, decoded.wheelMidY, 0f)
        assertEquals(0.375f, decoded.wheelHighX, 0f)
        assertEquals(-0.625f, decoded.wheelHighY, 0f)
        assertEquals(40f, decoded.splitShadowHue, 0f)
        assertEquals(210f, decoded.splitHighlightHue, 0f)
        assertEquals(-55f, decoded.splitBalance, 0f)
        assertEquals(false, decoded.highlightReconstruction)
        assertEquals(true, decoded.keepVignetting)
        assertEquals(2, decoded.tonemap)
    }

    @Test
    fun round_trip_flips_every_boolean() {
        // The four boolean slots' OTHER states: the mixed round trip above
        // exercises true/false across the slots; this inverts all four so
        // each slot individually proves both 0.0f and 1.0f decode.
        val grade = GradeK(
            temporalNr = true,
            asShot = false,
            highlightReconstruction = false,
            keepVignetting = true,
        )
        val decoded = GradeParcel.decode(GradeParcel.encode(grade))
        assertEquals(true, decoded.temporalNr)
        assertEquals(false, decoded.asShot)
        assertEquals(false, decoded.highlightReconstruction)
        assertEquals(true, decoded.keepVignetting)

        val flipped = grade.copy(
            temporalNr = false,
            asShot = true,
            highlightReconstruction = true,
            keepVignetting = false,
        )
        val flippedDecoded = GradeParcel.decode(GradeParcel.encode(flipped))
        assertEquals(false, flippedDecoded.temporalNr)
        assertEquals(true, flippedDecoded.asShot)
        assertEquals(true, flippedDecoded.highlightReconstruction)
        assertEquals(false, flippedDecoded.keepVignetting)
    }

    @Test
    fun default_grade_round_trips_the_nonzero_neutrals() {
        // The schema's non-zero-neutral defaults: tempK 5600, the split hues
        // -1 (off), asShot/highlightReconstruction/keepVignetting true,
        // temporalNr false, tonemap 0. A default GradeK must cross the wire
        // and come back with the SAME defaults - a schema default that
        // drifts from GradeK's own defaults fails here.
        val decoded = GradeParcel.decode(GradeParcel.encode(GradeK()))
        assertEquals(5600f, decoded.tempK, 0f)
        assertEquals(-1f, decoded.splitShadowHue, 0f)
        assertEquals(-1f, decoded.splitHighlightHue, 0f)
        assertEquals(false, decoded.temporalNr)
        assertEquals(true, decoded.asShot)
        assertEquals(true, decoded.highlightReconstruction)
        assertEquals(true, decoded.keepVignetting)
        assertEquals(0, decoded.tonemap)
    }

    // ---- the guards (honest refusals, never a silent misread) -----------

    @Test
    fun wrong_length_is_refused() {
        // The length guard: the parcel carries its length; a decode of any
        // other length refuses with an honest message naming both lengths -
        // a parcel from a different schema version can never be misread as
        // this one. Both directions are refused (short AND long).
        val parcel = GradeParcel.encode(GradeK())
        for (wrong in listOf(parcel.copyOf(parcel.size - 1), parcel + 1.0f)) {
            try {
                GradeParcel.decode(wrong)
                throw AssertionError("expected IllegalStateException for length " + wrong.size)
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the refusal must name the length mismatch: " + expected.message,
                    expected.message!!.contains("unknown length"),
                )
                assertTrue(
                    "the refusal must name the expected length: " + expected.message,
                    expected.message!!.contains(GradeParcel.SLOT_COUNT.toString()),
                )
            }
        }
    }

    @Test
    fun non_finite_values_are_refused() {
        // NaN and Infinity are refused in ANY slot - a single corrupt float
        // must never reach the develop math. Exposure carries the NaN, tint
        // the Infinity (two different slots so the loop's name in the
        // message is exercised twice).
        val withNan = GradeParcel.encode(GradeK())
        withNan[GradeParcel.SLOT_EXPOSURE_EV] = Float.NaN
        try {
            GradeParcel.decode(withNan)
            throw AssertionError("expected IllegalStateException for NaN")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must name the slot: " + expected.message,
                expected.message!!.contains("exposureEv"),
            )
            assertTrue(
                "the refusal must say the value is not finite: " + expected.message,
                expected.message!!.contains("not finite"),
            )
        }

        val withInf = GradeParcel.encode(GradeK())
        withInf[GradeParcel.SLOT_TINT] = Float.POSITIVE_INFINITY
        try {
            GradeParcel.decode(withInf)
            throw AssertionError("expected IllegalStateException for Infinity")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must name the slot: " + expected.message,
                expected.message!!.contains("tint"),
            )
        }
    }

    @Test
    fun boolean_slots_refuse_non_binary_values() {
        // The strict boolean check: 0.37 is a REFUSAL, not a truthy true -
        // each of the four boolean slots refuses any value outside strict
        // 0.0f/1.0f. (0.0f and 1.0f themselves decode - the round trips
        // above prove that.)
        for (slot in listOf(
            GradeParcel.SLOT_TEMPORAL_NR,
            GradeParcel.SLOT_AS_SHOT,
            GradeParcel.SLOT_HIGHLIGHT_RECONSTRUCTION,
            GradeParcel.SLOT_KEEP_VIGNETTING,
            GradeParcel.SLOT_HIGHLIGHT_RECOVERY,
            GradeParcel.SLOT_GLITTER_SUPPRESS,
        )) {
            val parcel = GradeParcel.encode(GradeK())
            parcel[slot] = 0.37f
            try {
                GradeParcel.decode(parcel)
                throw AssertionError("expected IllegalStateException for slot " + slot)
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "the refusal must demand the binary encoding: " + expected.message,
                    expected.message!!.contains("must be 0.0 or 1.0"),
                )
                assertTrue(
                    "the refusal must name the offending value: " + expected.message,
                    expected.message!!.contains("0.37"),
                )
            }
        }
    }

    // ---- the stale-mirror catch (the committed C++ header) --------------

    /**
     * The committed C++ mirror, located from the test working directory.
     * The two direct candidates cover the conventional working dirs
     * (Gradle's module dir: src/main/cpp/...; the repo root:
     * app/src/main/cpp/...), and the walk-up covers any runner that starts
     * deeper in the tree (e.g. a build dir): at every ancestor, both
     * suffixes are checked. A runner that still finds nothing fails
     * honestly with the paths it tried.
     */
    private fun committedHeader(): String {
        val suffixes = listOf(
            "src/main/cpp/gles/GradeParcel.h",
            "app/src/main/cpp/gles/GradeParcel.h",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (suffix in suffixes) {
                val candidate = File(dir, suffix)
                candidates.add(candidate)
                if (candidate.exists()) {
                    return candidate.readText()
                }
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the committed C++ parcel mirror; tried " +
                candidates.joinToString(", ") { it.absolutePath } +
                " (working dir " + File(".").absolutePath + ")",
        )
    }

    /** PascalCase -> UPPER_SNAKE, the mechanical name bridge (ChromaDenoise -> CHROMA_DENOISE). */
    private fun upperSnake(cppName: String): String =
        cppName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    @Test
    fun the_committed_cpp_header_matches_the_kotlin_offsets() {
        // The stale-mirror catch: the Kotlin SLOT_* constants (read by
        // REFLECTION off GradeParcel itself - this test hand-mirrors
        // NOTHING) must match the committed C++ header's constexpr slots
        // one for one, by name and by value, and the slot counts must
        // agree. Whichever side drifts (a mirror regenerated on one side
        // only, a hand-edited constant, a schema edit without a regenerate)
        // fails HERE with the regeneration command in the message. The
        // generator's own --check mode is the first catch; this is the
        // second, and it reads the file the NDK actually compiles.
        val text = committedHeader()

        val countMatch = Regex("constexpr int kGradeParcelSlotCount = (\\d+);").find(text)
        assertNotNull("the committed header must declare kGradeParcelSlotCount", countMatch)
        assertEquals(
            "the slot COUNT drifted between the Kotlin offsets and the committed C++ header - regenerate with python3 tools/gen-grade-parcel.py",
            GradeParcel.SLOT_COUNT,
            countMatch!!.groupValues[1].toInt(),
        )

        val cppSlots = mutableMapOf<String, Int>()
        for (match in Regex("constexpr int kGradeParcel(\\w+) = (\\d+);").findAll(text)) {
            val name = match.groupValues[1]
            if (name == "SlotCount") continue
            cppSlots[upperSnake(name)] = match.groupValues[2].toInt()
        }

        val kotlinSlots = GradeParcel::class.java.declaredFields
            .filter { it.name.startsWith("SLOT_") && it.name != "SLOT_COUNT" && it.name != "SLOT_NAMES" }
        assertEquals(
            "the schema has " + GradeParcel.SLOT_COUNT + " fields on the Kotlin side",
            GradeParcel.SLOT_COUNT,
            kotlinSlots.size,
        )
        for (field in kotlinSlots) {
            field.isAccessible = true
            val kotlinOffset = field.get(null) as Int
            val slotName = field.name.removePrefix("SLOT_")
            assertTrue(
                "the committed C++ header has no kGradeParcel constant for " + field.name +
                    " - regenerate with python3 tools/gen-grade-parcel.py",
                cppSlots.containsKey(slotName),
            )
            assertEquals(
                field.name + " drifted between the Kotlin offsets and the committed C++ header - regenerate with python3 tools/gen-grade-parcel.py",
                kotlinOffset,
                cppSlots[slotName],
            )
        }
        assertEquals(
            "the committed C++ header carries slots the Kotlin side does not - regenerate with python3 tools/gen-grade-parcel.py",
            GradeParcel.SLOT_COUNT,
            cppSlots.size,
        )
    }
}
